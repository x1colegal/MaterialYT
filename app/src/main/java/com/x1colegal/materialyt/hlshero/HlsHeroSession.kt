package com.x1colegal.materialyt.hlshero

import com.x1colegal.materialyt.AppLog
import com.x1colegal.materialyt.YouTubeRepository
import com.x1colegal.materialyt.sabrmodern.LiveMp4TimelineNormalizer
import com.x1colegal.materialyt.sabrmodern.SabrBuffer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

internal class HlsHeroSession(
    private val id: String,
    private val client: OkHttpClient,
    private val masterUrl: String,
    private val isLive: Boolean,
    private val audioOnly: Boolean,
    private val initialStartMs: Long = 0L,
    private val videoBuffer: SabrBuffer?,
    private val audioBuffer: SabrBuffer,
    private val preferredHeight: Int,
    private val preferredAudioLang: String?,
    private val onLayoutReady: (hasSeparateAudio: Boolean) -> Unit,
) : HlsHeroSessionHandle {
    private val cancelled = AtomicBoolean(false)
    private val threads = mutableListOf<Thread>()
    private var mainThread: Thread? = null

    private data class HlsAudioRendition(
        val groupId: String,
        val lang: String,
        val isDefault: Boolean,
        val uri: String,
    )

    private data class HlsVariant(
        val bandwidth: Long,
        val height: Int,
        val audioGroupId: String?,
        val uri: String,
    )

    private data class MediaPlaylist(
        val mediaSequence: Long,
        val targetDurationMs: Long,
        val hasEndList: Boolean,
        val mapUri: String?,
        val segmentUris: List<String>,
        val segmentDurationsMs: List<Long>,
    )

    fun start() {
        mainThread = Thread({ runSession() }, "hlshero-master-$id").apply { isDaemon = true; start() }
    }

    override fun cancel() {
        cancelled.set(true)
        mainThread?.interrupt()
        synchronized(threads) {
            threads.forEach { it.interrupt() }
        }
    }

    private fun runSession() {
        var layoutDispatched = false
        try {
            val masterText = fetchText(masterUrl)
            if (cancelled.get()) return

            if (!masterText.contains("#EXT-X-STREAM-INF")) {
                // Already a media playlist (muxed or audio-only).
                layoutDispatched = true
                onLayoutReady(false)
                pumpPlaylist(
                    label = if (audioOnly) "audio" else "muxed",
                    playlistUrl = masterUrl,
                    buffer = if (audioOnly) audioBuffer else requireNotNull(videoBuffer),
                    paceAheadBytes = if (audioOnly) 8L * 1024 * 1024 else 32L * 1024 * 1024,
                    initialPlaylist = parseMediaPlaylist(masterUrl, masterText),
                )
                return
            }

            val (variants, audios) = parseMasterPlaylist(masterUrl, masterText)
            if (audioOnly) {
                val audioUri = pickAudioRendition(audios, null)?.uri
                    ?: variants.minByOrNull { it.bandwidth }?.uri
                    ?: throw IOException("No audio stream in HLS master playlist")
                layoutDispatched = true
                onLayoutReady(false)
                pumpPlaylist("audio", audioUri, audioBuffer, 8L * 1024 * 1024, null)
                return
            }

            val videoVariant = pickVideoVariant(variants)
                ?: throw IOException("No video variant in HLS master playlist")
            val audioRendition = pickAudioRendition(audios, videoVariant.audioGroupId)
            val hasSeparateAudio = audioRendition != null && videoBuffer != null

            layoutDispatched = true
            onLayoutReady(hasSeparateAudio)

            if (hasSeparateAudio) {
                val vThread = Thread({
                    pumpPlaylist("video", videoVariant.uri, requireNotNull(videoBuffer), 32L * 1024 * 1024, null)
                }, "hlshero-v-$id").apply { isDaemon = true; start() }
                val aThread = Thread({
                    pumpPlaylist("audio", audioRendition!!.uri, audioBuffer, 8L * 1024 * 1024, null)
                }, "hlshero-a-$id").apply { isDaemon = true; start() }
                synchronized(threads) {
                    threads += vThread
                    threads += aThread
                }
                vThread.join()
                aThread.join()
            } else {
                val targetBuf = videoBuffer ?: audioBuffer
                pumpPlaylist("muxed", videoVariant.uri, targetBuf, 32L * 1024 * 1024, null)
            }
        } catch (_: InterruptedException) {
            // Cancelled
        } catch (t: Throwable) {
            if (!cancelled.get()) {
                AppLog.event("HlsHero session crash id=$id: ${t.message}")
                videoBuffer?.markError(t.message ?: "HlsHero error")
                audioBuffer.markError(t.message ?: "HlsHero error")
                if (!layoutDispatched) onLayoutReady(false)
            }
        }
    }

    private fun pumpPlaylist(
        label: String,
        playlistUrl: String,
        buffer: SabrBuffer,
        paceAheadBytes: Long,
        initialPlaylist: MediaPlaylist?,
    ) {
        val normalizer = LiveMp4TimelineNormalizer()
        var offset = 0L
        var nextSeq = -1L
        var mapDone = false
        var isFmp4 = false
        var failures = 0
        var first = initialPlaylist

        try {
            while (!cancelled.get()) {
                val playlist = first ?: try {
                    parseMediaPlaylist(playlistUrl, fetchText(playlistUrl))
                } catch (e: InterruptedIOException) {
                    if (cancelled.get()) return
                    throw e
                } catch (e: IOException) {
                    if (cancelled.get()) return
                    failures++
                    if (failures >= 6) {
                        buffer.markError("HLS $label playlist error: ${e.message}")
                        return
                    }
                    Thread.sleep(1_000L)
                    continue
                }
                first = null
                failures = 0

                if (playlist.mapUri != null) isFmp4 = true

                if (playlist.mapUri != null && !mapDone) {
                    val initBytes = fetchBytes(playlist.mapUri)
                    if (initBytes.isNotEmpty()) {
                        val out = if (isLive) normalizer.normalize(initBytes, "HlsHero-$label", 0) else initBytes
                        buffer.writeAt(0L, out, 0, out.size)
                        offset += out.size
                        mapDone = true
                    }
                }

                if (nextSeq < 0L) {
                    nextSeq = if (isLive && !playlist.hasEndList && playlist.segmentUris.size > LIVE_EDGE_SEGMENTS) {
                        playlist.mediaSequence + playlist.segmentUris.size - LIVE_EDGE_SEGMENTS
                    } else if (!isLive && initialStartMs > 0L && playlist.segmentDurationsMs.size == playlist.segmentUris.size) {
                        var accMs = 0L
                        var startIdx = 0
                        for (i in playlist.segmentDurationsMs.indices) {
                            if (accMs + playlist.segmentDurationsMs[i] > initialStartMs) {
                                startIdx = i
                                break
                            }
                            accMs += playlist.segmentDurationsMs[i]
                            startIdx = i
                        }
                        playlist.mediaSequence + startIdx
                    } else {
                        playlist.mediaSequence
                    }
                }

                val endSeq = playlist.mediaSequence + playlist.segmentUris.size
                var downloadedAny = false
                while (!cancelled.get() && nextSeq < endSeq) {
                    if (!isLive || playlist.hasEndList) {
                        buffer.awaitDemand(paceAheadBytes) { cancelled.get() }
                        if (cancelled.get()) return
                    }
                    val idx = (nextSeq - playlist.mediaSequence).toInt()
                    if (idx in playlist.segmentUris.indices) {
                        val segUrl = playlist.segmentUris[idx]
                        val raw = try {
                            fetchBytes(segUrl)
                        } catch (e: InterruptedIOException) {
                            if (cancelled.get()) return
                            throw e
                        } catch (e: IOException) {
                            if (cancelled.get()) return
                            AppLog.event("HlsHero $label segment error seq=$nextSeq: ${e.message}")
                            Thread.sleep(500L)
                            continue
                        }
                        if (raw.isNotEmpty()) {
                            if (!isFmp4 && looksLikeMp4Fragment(raw)) isFmp4 = true
                            val out = if (isLive && isFmp4) normalizer.normalize(raw, "HlsHero-$label", 0) else raw
                            buffer.writeAt(offset, out, 0, out.size)
                            offset += out.size
                            downloadedAny = true
                        }
                    }
                    nextSeq++
                }

                if (playlist.hasEndList || !isLive) {
                    buffer.markComplete()
                    return
                }

                val waitMs = if (downloadedAny) (playlist.targetDurationMs / 2L).coerceIn(1_000L, 2_500L) else 1_000L
                Thread.sleep(waitMs)
            }
        } catch (_: InterruptedException) {
            // Cancelled
        } catch (t: Throwable) {
            if (!cancelled.get()) {
                AppLog.event("HlsHero $label pump crash id=$id: ${t.message}")
                buffer.markError(t.message ?: "HlsHero $label error")
            }
        }
    }

    private fun looksLikeMp4Fragment(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val type = String(bytes, 4, 4, Charsets.US_ASCII)
        return type == "ftyp" || type == "styp" || type == "moof" || type == "moov" || type == "sidx"
    }

    private fun pickVideoVariant(variants: List<HlsVariant>): HlsVariant? {
        if (variants.isEmpty()) return null
        if (preferredHeight <= 0) return variants.maxByOrNull { it.height * 1_000_000L + it.bandwidth }
        val atOrBelow = variants.filter { it.height in 1..preferredHeight }
        return atOrBelow.maxByOrNull { it.height * 1_000_000L + it.bandwidth }
            ?: variants.minByOrNull { abs(it.height - preferredHeight) }
    }

    private fun pickAudioRendition(audios: List<HlsAudioRendition>, groupId: String?): HlsAudioRendition? {
        val pool = if (groupId != null) audios.filter { it.groupId == groupId }.ifEmpty { audios } else audios
        if (pool.isEmpty()) return null
        val lang = preferredAudioLang?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        if (lang != null) {
            pool.firstOrNull { it.lang.lowercase().startsWith(lang) }?.let { return it }
        }
        return pool.firstOrNull { it.isDefault } ?: pool.first()
    }

    private fun parseMasterPlaylist(baseUrl: String, text: String): Pair<List<HlsVariant>, List<HlsAudioRendition>> {
        val variants = mutableListOf<HlsVariant>()
        val audios = mutableListOf<HlsAudioRendition>()
        val lines = text.lineSequence().map { it.trim() }.toList()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-MEDIA:")) {
                val attrs = parseAttrs(line.removePrefix("#EXT-X-MEDIA:"))
                if (attrs["TYPE"] == "AUDIO" && !attrs["URI"].isNullOrBlank()) {
                    audios += HlsAudioRendition(
                        groupId = attrs["GROUP-ID"].orEmpty(),
                        lang = attrs["LANGUAGE"].orEmpty(),
                        isDefault = attrs["DEFAULT"] == "YES",
                        uri = resolveUrl(baseUrl, attrs["URI"]!!),
                    )
                }
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val attrs = parseAttrs(line.removePrefix("#EXT-X-STREAM-INF:"))
                val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L
                val height = attrs["RESOLUTION"]?.substringAfter('x')?.toIntOrNull() ?: 0
                val audioGroup = attrs["AUDIO"]
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    variants += HlsVariant(bandwidth, height, audioGroup, resolveUrl(baseUrl, lines[j]))
                    i = j
                }
            }
            i++
        }
        return variants to audios
    }

    private fun parseMediaPlaylist(baseUrl: String, text: String): MediaPlaylist {
        var mediaSeq = 0L
        var targetDurationMs = 2_000L
        var hasEndList = false
        var mapUri: String? = null
        val segments = mutableListOf<String>()
        val durationsMs = mutableListOf<Long>()
        var pendingDurMs = 2_000L

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    mediaSeq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-TARGETDURATION:") ->
                    targetDurationMs = ((line.substringAfter(':').trim().toDoubleOrNull() ?: 2.0) * 1000.0).toLong()
                line == "#EXT-X-ENDLIST" ->
                    hasEndList = true
                line.startsWith("#EXTINF:") -> {
                    val sec = line.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: 2.0
                    pendingDurMs = (sec * 1000.0).toLong().coerceAtLeast(1L)
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = parseAttrs(line.removePrefix("#EXT-X-MAP:"))
                    attrs["URI"]?.let { mapUri = resolveUrl(baseUrl, it) }
                }
                !line.startsWith("#") -> {
                    segments += resolveUrl(baseUrl, line)
                    durationsMs += pendingDurMs
                }
            }
        }
        return MediaPlaylist(mediaSeq, targetDurationMs, hasEndList, mapUri, segments, durationsMs)
    }

    private fun parseAttrs(raw: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val regex = Regex("""([A-Z0-9-]+)=("([^"]*)"|([^,]*))""")
        for (m in regex.findAll(raw)) {
            val key = m.groupValues[1]
            val valQuoted = m.groupValues[3]
            val valBare = m.groupValues[4]
            map[key] = if (m.groupValues[2].startsWith("\"")) valQuoted else valBare
        }
        return map
    }

    private fun fetchText(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", YouTubeRepository.VISIONOS_UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    private fun fetchBytes(url: String): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", YouTubeRepository.VISIONOS_UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun resolveUrl(base: String, rel: String): String {
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
        return runCatching { URI(base).resolve(rel).toString() }.getOrDefault(base + rel)
    }

    companion object {
        private const val LIVE_EDGE_SEGMENTS = 3
    }
}
