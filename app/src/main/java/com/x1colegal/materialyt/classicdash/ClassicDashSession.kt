package com.x1colegal.materialyt.classicdash

import android.util.Xml
import com.x1colegal.materialyt.AppLog
import com.x1colegal.materialyt.YouTubeRepository
import com.x1colegal.materialyt.sabrmodern.LiveMp4TimelineNormalizer
import com.x1colegal.materialyt.sabrmodern.SabrBuffer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

internal class ClassicDashVodSession(
    private val id: String,
    private val client: OkHttpClient,
    private val videoUrl: String?,
    private val videoItag: Int,
    private val videoBuffer: SabrBuffer?,
    private val audioUrl: String,
    private val audioItag: Int,
    private val audioBuffer: SabrBuffer,
    private val durationMs: Long,
    initialStartMs: Long = 0L,
) : ClassicDashSessionHandle {
    private val cancelled = AtomicBoolean(false)
    private val videoTrack = videoUrl?.let { url ->
        val buf = requireNotNull(videoBuffer)
        val initialPos = initialOffsetFor(initialStartMs, buf)
        VodTrackState("video", url, videoItag, buf, 32L * 1024 * 1024, initialPos)
    }
    private val audioTrack = VodTrackState(
        "audio", audioUrl, audioItag, audioBuffer, 8L * 1024 * 1024,
        initialOffsetFor(initialStartMs, audioBuffer),
    )

    private val threads = mutableListOf<Thread>()

    private class VodTrackState(
        val label: String,
        val url: String,
        val itag: Int,
        val buffer: SabrBuffer,
        val paceAheadBytes: Long,
        initialCursor: Long,
    ) {
        val lock = Object()
        var pendingSeek: Long? = if (initialCursor > 0L) initialCursor else null
        var cursor: Long = 0L
        var initDone: Boolean = false
        val lastHintMs = AtomicLong(0L)
    }

    fun start() {
        AppLog.event(
            "ClassicDash VOD start id=$id videoLen=${videoBuffer?.expectedLength ?: -1L} " +
                "audioLen=${audioBuffer.expectedLength} durationMs=$durationMs",
        )
        videoTrack?.let { track ->
            threads += Thread({ runTrack(track) }, "classicdash-vod-v-$id").apply { isDaemon = true; start() }
        }
        threads += Thread({ runTrack(audioTrack) }, "classicdash-vod-a-$id").apply { isDaemon = true; start() }
    }

    private fun initialOffsetFor(startMs: Long, buffer: SabrBuffer): Long {
        if (startMs <= 0L || durationMs <= 0L || buffer.expectedLength >= SabrBuffer.MAX_BUFFER_BYTES) return 0L
        return (startMs * buffer.expectedLength / durationMs).coerceIn(0L, buffer.expectedLength - 1L)
    }

    override fun requestPosition(video: Boolean, position: Long) {
        val track = if (video) videoTrack else audioTrack
        track ?: return
        if (track.buffer.coveredAt(position)) return
        synchronized(track.lock) {
            val endAhead = track.cursor + JUMP_THRESHOLD_BYTES
            if (position < track.cursor || position > endAhead) {
                track.pendingSeek = position
                track.lock.notifyAll()
            }
        }
    }

    override fun hintPlaybackTime(positionMs: Long) {
        if (durationMs <= 0L || positionMs < 0L) return
        videoTrack?.let { hintTrack(it, positionMs) }
        hintTrack(audioTrack, positionMs)
    }

    private fun hintTrack(track: VodTrackState, positionMs: Long) {
        if (track.buffer.expectedLength >= SabrBuffer.MAX_BUFFER_BYTES) return
        val prev = track.lastHintMs.getAndSet(positionMs)
        if (abs(positionMs - prev) < 1_000L) return
        val est = (positionMs * track.buffer.expectedLength / durationMs)
            .coerceIn(0L, (track.buffer.expectedLength - 1L).coerceAtLeast(0L))
        track.buffer.raiseDemandTo(est)
        requestPosition(track.label == "video", est)
    }

    override fun cancel() {
        cancelled.set(true)
        synchronized(audioTrack.lock) { audioTrack.lock.notifyAll() }
        videoTrack?.let { synchronized(it.lock) { it.lock.notifyAll() } }
        threads.forEach { it.interrupt() }
    }

    private fun runTrack(track: VodTrackState) {
        val buffer = track.buffer
        val growable = buffer.expectedLength == SabrBuffer.MAX_BUFFER_BYTES
        var failures = 0
        try {
            // Always fetch the first 256 KB first so ExoPlayer has moov/sidx/init atoms immediately.
            if (!track.initDone && !cancelled.get()) {
                val initEnd = if (growable) CHUNK_BYTES - 1 else minOf(256L * 1024L, buffer.expectedLength) - 1L
                if (initEnd >= 0L) {
                    val streamed = streamRange(track.url, 0L, initEnd, buffer) {
                        cancelled.get() || synchronized(track.lock) { track.pendingSeek != null }
                    }
                    track.cursor = streamed
                }
                track.initDone = true
            }

            while (!cancelled.get()) {
                val nextSeek = synchronized(track.lock) {
                    val s = track.pendingSeek
                    track.pendingSeek = null
                    if (s != null) track.cursor = s
                    s
                }
                if (nextSeek != null) {
                    buffer.resetDemandFrom(nextSeek)
                }

                // Advance past already covered intervals.
                val covEnd = buffer.coverageEndFrom(track.cursor)
                if (covEnd > track.cursor) {
                    track.cursor = covEnd
                }

                val limit = buffer.expectedLength
                if (!growable && track.cursor >= limit) {
                    if (buffer.completeFromZero()) {
                        buffer.markComplete()
                        return
                    }
                    // Tail reached after a seek; wait for another seek or cancellation.
                    synchronized(track.lock) {
                        if (!cancelled.get() && track.pendingSeek == null) {
                            track.lock.wait(250)
                        }
                    }
                    continue
                }

                buffer.awaitDemand(track.paceAheadBytes) {
                    cancelled.get() || synchronized(track.lock) { track.pendingSeek != null }
                }
                if (cancelled.get()) return
                if (synchronized(track.lock) { track.pendingSeek != null }) continue

                val start = track.cursor
                val end = if (growable) start + CHUNK_BYTES - 1L else minOf(start + CHUNK_BYTES - 1L, limit - 1L)
                if (end < start) continue

                val written = try {
                    streamRange(track.url, start, end, buffer) {
                        cancelled.get() || synchronized(track.lock) { track.pendingSeek != null }
                    }
                } catch (e: InterruptedIOException) {
                    if (cancelled.get()) return
                    throw e
                } catch (e: IOException) {
                    if (cancelled.get()) return
                    failures++
                    AppLog.event("ClassicDash VOD ${track.label} retry=$failures itag=${track.itag} start=$start: ${e.message}")
                    if (failures >= MAX_CHUNK_RETRIES) {
                        AppLog.event("ClassicDash VOD ${track.label} failed itag=${track.itag}: ${e.message}")
                        buffer.markError("ClassicDash ${track.label} HTTP error: ${e.message}")
                        return
                    }
                    Thread.sleep(250L * failures)
                    continue
                }

                if (written <= 0L) {
                    if (growable) {
                        buffer.markComplete()
                        return
                    }
                    failures++
                    if (failures >= MAX_CHUNK_RETRIES) {
                        buffer.markError("ClassicDash ${track.label} empty response at $start")
                        return
                    }
                    Thread.sleep(250L)
                    continue
                }

                failures = 0
                track.cursor = start + written
                if (start < 4L * 1024L * 1024L) {
                    AppLog.event("ClassicDash VOD ${track.label} chunk start=$start written=$written")
                }

                if (growable && written < (end - start + 1L)) {
                    buffer.markComplete()
                    return
                }
            }
        } catch (_: InterruptedException) {
            // Cancelled
        } catch (t: Throwable) {
            if (!cancelled.get()) {
                AppLog.event("ClassicDash VOD ${track.label} crash itag=${track.itag}: ${t.message}")
                buffer.markError(t.message ?: "ClassicDash VOD error")
            }
        }
    }

    private fun streamRange(
        url: String,
        start: Long,
        end: Long,
        buffer: SabrBuffer,
        shouldAbort: () -> Boolean,
    ): Long {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", YouTubeRepository.VISIONOS_UA)
            .header("Range", "bytes=$start-$end")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                throw IOException("HTTP ${resp.code}")
            }
            val stream = resp.body?.byteStream() ?: return 0L
            val chunk = ByteArray(32 * 1024)
            var offset = start
            var total = 0L
            while (!shouldAbort()) {
                val n = stream.read(chunk)
                if (n <= 0) break
                buffer.writeAt(offset, chunk, 0, n)
                offset += n
                total += n
            }
            return total
        }
    }

    companion object {
        private const val CHUNK_BYTES = 2L * 1024L * 1024L
        private const val JUMP_THRESHOLD_BYTES = 2L * 1024L * 1024L
        private const val MAX_CHUNK_RETRIES = 4
    }
}

internal class ClassicDashLiveSession(
    private val id: String,
    private val client: OkHttpClient,
    private val manifestUrl: String,
    private val videoBuffer: SabrBuffer?,
    private val audioBuffer: SabrBuffer,
    private val preferredHeight: Int,
    private val preferredAudioLang: String?,
) : ClassicDashSessionHandle {
    private val cancelled = AtomicBoolean(false)
    private var thread: Thread? = null

    private data class MpdRep(
        val id: String,
        val isVideo: Boolean,
        val bandwidth: Long,
        val height: Int,
        val lang: String,
        val isMainRole: Boolean,
        val baseUrl: String,
        val initUrl: String?,
        val initRange: String?,
        val startNumber: Long,
        val segmentUrls: List<String>,
    )

    private data class ParsedMpd(
        val isDynamic: Boolean,
        val minUpdateMs: Long,
        val videoReps: List<MpdRep>,
        val audioReps: List<MpdRep>,
    )

    fun start() {
        thread = Thread({ runLive() }, "classicdash-live-$id").apply { isDaemon = true; start() }
    }

    override fun requestPosition(video: Boolean, position: Long) = Unit
    override fun hintPlaybackTime(positionMs: Long) = Unit
    override fun cancel() {
        cancelled.set(true)
        thread?.interrupt()
    }

    private fun runLive() {
        val videoNormalizer = LiveMp4TimelineNormalizer()
        val audioNormalizer = LiveMp4TimelineNormalizer()
        var videoOffset = 0L
        var audioOffset = 0L
        var videoInitDone = false
        var audioInitDone = false
        var videoStarted = false
        var audioStarted = false
        var lastVideoSegUrl: String? = null
        var lastAudioSegUrl: String? = null
        val seenVideoSegs = ArrayDeque<String>()
        val seenAudioSegs = ArrayDeque<String>()
        var failures = 0

        fun selectNewSegments(urls: List<String>, started: Boolean, lastUrl: String?, seen: ArrayDeque<String>): List<String> {
            if (urls.isEmpty()) return emptyList()
            if (!started) return urls.takeLast(LIVE_EDGE_SEGMENTS)
            if (lastUrl != null) {
                val idx = urls.lastIndexOf(lastUrl)
                if (idx >= 0) return urls.drop(idx + 1)
            }
            val seenSet = seen.toHashSet()
            return urls.filter { !seenSet.contains(it) }
        }

        fun recordSeen(url: String, seen: ArrayDeque<String>) {
            seen.addLast(url)
            while (seen.size > 64) seen.removeFirst()
        }

        try {
            while (!cancelled.get()) {
                val mpdXml = try {
                    fetchText(manifestUrl)
                } catch (e: InterruptedIOException) {
                    if (cancelled.get()) return
                    throw e
                } catch (e: IOException) {
                    if (cancelled.get()) return
                    failures++
                    if (failures >= 6) {
                        videoBuffer?.markError("MPD fetch failed: ${e.message}")
                        audioBuffer.markError("MPD fetch failed: ${e.message}")
                        return
                    }
                    Thread.sleep(1_000L)
                    continue
                }

                val parsed = parseMpd(manifestUrl, mpdXml)
                failures = 0

                val videoRep = if (videoBuffer != null) pickVideoRep(parsed.videoReps) else null
                val audioRep = pickAudioRep(parsed.audioReps)

                if (audioRep == null) {
                    AppLog.event("ClassicDash live no audio rep in MPD id=$id")
                    Thread.sleep(1_000L)
                    continue
                }

                if (videoBuffer != null && videoRep != null && !videoInitDone) {
                    val initBytes = fetchInit(videoRep)
                    if (initBytes != null && initBytes.isNotEmpty()) {
                        val patched = videoNormalizer.normalize(initBytes, "ClassicDash", videoRep.id.toIntOrNull() ?: 0)
                        videoBuffer.writeAt(0L, patched, 0, patched.size)
                        videoOffset = patched.size.toLong()
                        videoInitDone = true
                    }
                }
                if (!audioInitDone) {
                    val initBytes = fetchInit(audioRep)
                    if (initBytes != null && initBytes.isNotEmpty()) {
                        val patched = audioNormalizer.normalize(initBytes, "ClassicDash", audioRep.id.toIntOrNull() ?: 0)
                        audioBuffer.writeAt(0L, patched, 0, patched.size)
                        audioOffset = patched.size.toLong()
                        audioInitDone = true
                    }
                }

                val newVideoSegs = if (videoBuffer != null && videoRep != null) {
                    selectNewSegments(videoRep.segmentUrls, videoStarted, lastVideoSegUrl, seenVideoSegs)
                } else emptyList()
                val newAudioSegs = selectNewSegments(audioRep.segmentUrls, audioStarted, lastAudioSegUrl, seenAudioSegs)
                if (newVideoSegs.isNotEmpty()) videoStarted = true
                if (newAudioSegs.isNotEmpty()) audioStarted = true

                var downloadedAny = false
                val maxSteps = maxOf(newVideoSegs.size, newAudioSegs.size)
                for (i in 0 until maxSteps) {
                    if (cancelled.get()) return
                    if (videoBuffer != null && videoRep != null && i < newVideoSegs.size) {
                        val segRel = newVideoSegs[i]
                        val segBytes = fetchBytes(resolveUrl(videoRep.baseUrl, segRel), null)
                        val patched = videoNormalizer.normalize(segBytes, "ClassicDash", videoRep.id.toIntOrNull() ?: 0)
                        videoBuffer.writeAt(videoOffset, patched, 0, patched.size)
                        videoOffset += patched.size
                        lastVideoSegUrl = segRel
                        recordSeen(segRel, seenVideoSegs)
                        downloadedAny = true
                    }
                    if (i < newAudioSegs.size) {
                        val segRel = newAudioSegs[i]
                        val segBytes = fetchBytes(resolveUrl(audioRep.baseUrl, segRel), null)
                        val patched = audioNormalizer.normalize(segBytes, "ClassicDash", audioRep.id.toIntOrNull() ?: 0)
                        audioBuffer.writeAt(audioOffset, patched, 0, patched.size)
                        audioOffset += patched.size
                        lastAudioSegUrl = segRel
                        recordSeen(segRel, seenAudioSegs)
                        downloadedAny = true
                    }
                }

                if (!parsed.isDynamic) {
                    videoBuffer?.markComplete()
                    audioBuffer.markComplete()
                    return
                }

                val waitMs = if (downloadedAny) parsed.minUpdateMs else 1_000L
                Thread.sleep(waitMs)
            }
        } catch (_: InterruptedException) {
            // Cancelled
        } catch (t: Throwable) {
            if (!cancelled.get()) {
                AppLog.event("ClassicDash live crash id=$id: ${t.message}")
                videoBuffer?.markError(t.message ?: "ClassicDash live error")
                audioBuffer.markError(t.message ?: "ClassicDash live error")
            }
        }
    }

    private fun fetchInit(rep: MpdRep): ByteArray? {
        val initUrl = rep.initUrl ?: if (rep.initRange != null) "" else return null
        val fullUrl = if (initUrl.isEmpty()) rep.baseUrl else resolveUrl(rep.baseUrl, initUrl)
        return fetchBytes(fullUrl, rep.initRange)
    }

    private fun pickVideoRep(reps: List<MpdRep>): MpdRep? {
        if (reps.isEmpty()) return null
        if (preferredHeight <= 0) return reps.maxByOrNull { it.height * 1_000_000L + it.bandwidth }
        val atOrBelow = reps.filter { it.height in 1..preferredHeight }
        return atOrBelow.maxByOrNull { it.height * 1_000_000L + it.bandwidth }
            ?: reps.minByOrNull { abs(it.height - preferredHeight) }
    }

    private fun pickAudioRep(reps: List<MpdRep>): MpdRep? {
        if (reps.isEmpty()) return null
        val lang = preferredAudioLang?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        if (lang != null) {
            reps.filter { it.lang.lowercase().startsWith(lang) }.maxByOrNull { it.bandwidth }?.let { return it }
        }
        return reps.filter { it.isMainRole }.maxByOrNull { it.bandwidth }
            ?: reps.maxByOrNull { it.bandwidth }
    }

    private fun fetchText(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", YouTubeRepository.VISIONOS_UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    private fun fetchBytes(url: String, range: String?): ByteArray {
        val reqBuilder = Request.Builder().url(url).header("User-Agent", YouTubeRepository.VISIONOS_UA)
        if (!range.isNullOrBlank()) reqBuilder.header("Range", "bytes=$range")
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) throw IOException("HTTP ${resp.code}")
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun resolveUrl(base: String, rel: String): String {
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
        return runCatching { URI(base).resolve(rel).toString() }.getOrDefault(base + rel)
    }

    private fun parseMpd(mpdUrl: String, xml: String): ParsedMpd {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(xml.reader())

        var isDynamic = false
        var minUpdateMs = 1_500L
        var mpdBaseUrl = mpdUrl

        var adaptMime = ""
        var adaptLang = ""
        var adaptMain = false
        var adaptBaseUrl = ""
        var adaptInitUrl: String? = null
        var adaptInitRange: String? = null
        var adaptStartNumber = 1L
        var adaptSegUrls = mutableListOf<String>()

        var inRep = false
        var repId = ""
        var repMime = ""
        var repBandwidth = 0L
        var repHeight = 0
        var repLang = ""
        var repBaseUrl = ""
        var repInitUrl: String? = null
        var repInitRange: String? = null
        var repStartNumber = 1L
        var repSegUrls = mutableListOf<String>()

        val videoReps = mutableListOf<MpdRep>()
        val audioReps = mutableListOf<MpdRep>()
        val tagStack = ArrayDeque<String>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    tagStack.addLast(name)
                    when (name) {
                        "MPD" -> {
                            isDynamic = parser.getAttributeValue(null, "type") == "dynamic"
                            minUpdateMs = parseIsoDurationMs(parser.getAttributeValue(null, "minimumUpdatePeriod"))
                                .coerceIn(1_000L, 2_500L)
                        }
                        "AdaptationSet" -> {
                            adaptMime = parser.getAttributeValue(null, "mimeType").orEmpty()
                            adaptLang = parser.getAttributeValue(null, "lang").orEmpty()
                            adaptMain = false
                            adaptBaseUrl = mpdBaseUrl
                            adaptInitUrl = null
                            adaptInitRange = null
                            adaptStartNumber = 1L
                            adaptSegUrls = mutableListOf()
                        }
                        "Role" -> {
                            if (parser.getAttributeValue(null, "value") == "main") adaptMain = true
                        }
                        "Representation" -> {
                            inRep = true
                            repId = parser.getAttributeValue(null, "id").orEmpty()
                            repMime = parser.getAttributeValue(null, "mimeType") ?: adaptMime
                            repBandwidth = parser.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0L
                            repHeight = parser.getAttributeValue(null, "height")?.toIntOrNull() ?: 0
                            repLang = parser.getAttributeValue(null, "lang") ?: adaptLang
                            repBaseUrl = adaptBaseUrl
                            repInitUrl = adaptInitUrl
                            repInitRange = adaptInitRange
                            repStartNumber = adaptStartNumber
                            repSegUrls = adaptSegUrls.toMutableList()
                        }
                        "BaseURL" -> {
                            val text = parser.nextText().trim()
                            tagStack.removeLast()
                            if (text.isNotEmpty()) {
                                val resolved = resolveUrl(mpdBaseUrl, text)
                                when {
                                    inRep -> repBaseUrl = resolved
                                    tagStack.lastOrNull() == "AdaptationSet" -> adaptBaseUrl = resolved
                                    else -> mpdBaseUrl = resolved
                                }
                            }
                        }
                        "SegmentList" -> {
                            val sn = parser.getAttributeValue(null, "startNumber")?.toLongOrNull()
                            if (inRep) {
                                if (sn != null) repStartNumber = sn
                            } else {
                                if (sn != null) adaptStartNumber = sn
                            }
                        }
                        "Initialization" -> {
                            val src = parser.getAttributeValue(null, "sourceURL")
                            val range = parser.getAttributeValue(null, "range")
                            if (inRep) {
                                repInitUrl = src
                                repInitRange = range
                            } else {
                                adaptInitUrl = src
                                adaptInitRange = range
                            }
                        }
                        "SegmentURL" -> {
                            val media = parser.getAttributeValue(null, "media")
                            if (!media.isNullOrBlank()) {
                                if (inRep) repSegUrls.add(media) else adaptSegUrls.add(media)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name
                    if (tagStack.isNotEmpty()) tagStack.removeLast()
                    if (name == "Representation" && inRep) {
                        inRep = false
                        val rep = MpdRep(
                            id = repId,
                            isVideo = repMime.startsWith("video/"),
                            bandwidth = repBandwidth,
                            height = repHeight,
                            lang = repLang,
                            isMainRole = adaptMain,
                            baseUrl = repBaseUrl,
                            initUrl = repInitUrl,
                            initRange = repInitRange,
                            startNumber = repStartNumber,
                            segmentUrls = repSegUrls,
                        )
                        if (rep.isVideo) videoReps.add(rep)
                        else if (repMime.startsWith("audio/")) audioReps.add(rep)
                    }
                }
            }
            event = parser.next()
        }
        return ParsedMpd(isDynamic, minUpdateMs, videoReps, audioReps)
    }

    private fun parseIsoDurationMs(raw: String?): Long {
        if (raw.isNullOrBlank()) return 1_500L
        val match = Regex("""PT(?:(\d+(?:\.\d+)?)S)""").find(raw) ?: return 1_500L
        val sec = match.groupValues[1].toDoubleOrNull() ?: return 1_500L
        return (sec * 1000.0).toLong()
    }

    companion object {
        private const val LIVE_EDGE_SEGMENTS = 3
    }
}
