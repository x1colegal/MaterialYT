package com.x1colegal.materialyt.sabrmodern

import android.net.Uri
import android.os.SystemClock
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.BaseDataSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.x1colegal.materialyt.AppLog
import com.x1colegal.materialyt.MaterialYtApp
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal class ModernSabrStream(
    val id: String,
    private val videoConfig: SabrVideoConfig?,
    private val audioConfig: SabrConfig?,
    private val client: OkHttpClient,
    initialStartMs: Long = 0L,
) {
    val videoBuffer: SabrBuffer?
    val audioBuffer: SabrBuffer
    private var session: Runnable
    private var sessionThread: Thread? = null
    private var requestedStartMs = initialStartMs.coerceAtLeast(0L)
    private var currentSessionStartMs = 0L
    private var currentMarginMs = INITIAL_SEEK_MARGIN_MS
    private var overshootRestarts = 0
    private var hintedPlaybackTimeMs: Long? = initialStartMs.takeIf { it > 0L }
    private var hintCreatedAtMs = if (initialStartMs > 0L) SystemClock.elapsedRealtime() else 0L

    init {
        val dir = File(MaterialYtApp.instance.cacheDir, "sabr-modern").apply { mkdirs() }
        val coldStartMs = if (requestedStartMs > 0L) (requestedStartMs - currentMarginMs).coerceAtLeast(0L) else 0L
        currentSessionStartMs = coldStartMs
        if (videoConfig != null) {
            videoBuffer = SabrBuffer(videoConfig.videoFormat.contentLength, File(dir, "$id-video.part"))
            audioBuffer = SabrBuffer(videoConfig.audioFormat.contentLength, File(dir, "$id-audio.part"))
            if (coldStartMs > 0L) {
                videoBuffer.resetDemandFrom(positionForTime(coldStartMs, videoConfig.durationMs, videoConfig.videoFormat.contentLength))
                audioBuffer.resetDemandFrom(positionForTime(coldStartMs, videoConfig.durationMs, videoConfig.audioFormat.contentLength))
            }
            session = SabrVideoSession(videoConfig, client, videoBuffer, audioBuffer,
                startTimeMs = coldStartMs,
                paceAheadVideoBytes = 32L * 1024 * 1024,
                paceAheadAudioBytes = 8L * 1024 * 1024)
        } else {
            val cfg = requireNotNull(audioConfig)
            videoBuffer = null
            audioBuffer = SabrBuffer(cfg.format.contentLength, File(dir, "$id-audio.part"))
            if (coldStartMs > 0L) {
                audioBuffer.resetDemandFrom(positionForTime(coldStartMs, cfg.durationMs, cfg.format.contentLength))
            }
            session = SabrSession(cfg, client, audioBuffer, startTimeMs = coldStartMs, paceAheadBytes = 8L * 1024 * 1024)
        }
        if (requestedStartMs > 0L) {
            AppLog.event("SABR cold start id=$id targetMs=$requestedStartMs startMs=$coldStartMs")
        }
        startSession(session)
    }

    fun buffer(video: Boolean): SabrBuffer = if (video) requireNotNull(videoBuffer) else audioBuffer
    fun length(video: Boolean): Long = buffer(video).expectedLength

    /** Supply ExoPlayer's real media time so a byte-range open never has to guess through VBR. */
    @Synchronized
    fun hintPlaybackTime(positionMs: Long) {
        val normalizedPositionMs = positionMs.coerceAtLeast(0L)
        hintedPlaybackTimeMs = normalizedPositionMs
        hintCreatedAtMs = SystemClock.elapsedRealtime()
        AppLog.event("SABR seek hint id=$id positionMs=$normalizedPositionMs")
        // Zero is a real seek target. Ignoring it leaves a session previously cold-started at a
        // history position (for example 03:00) anchored there forever, so no bytes covering the
        // beginning can ever arrive. Avoid restarting only when this session is already at zero.
        if (videoConfig != null && kotlin.math.abs(normalizedPositionMs - requestedStartMs) >= SEEK_COALESCE_MS) {
            overshootRestarts = 0
            currentMarginMs = INITIAL_SEEK_MARGIN_MS
            val position = positionForTime(normalizedPositionMs, videoConfig.durationMs, videoConfig.videoFormat.contentLength)
            restartAt(normalizedPositionMs, video = true, position = position, source = "hint", marginMs = currentMarginMs)
        }
    }

    /** Re-aim SABR at a distant ExoPlayer seek instead of draining linearly from the old position. */
    @Synchronized
    fun requestPosition(video: Boolean, position: Long) {
        val requestedBuffer = buffer(video)
        if (position <= 0L || requestedBuffer.coveredAt(position)) return
        val now = SystemClock.elapsedRealtime()
        val hintedTarget = hintedPlaybackTimeMs?.takeIf { now - hintCreatedAtMs <= SEEK_HINT_TTL_MS }

        // A normal DataSource reopen at the current frontier is not a seek. Let the live drain fill
        // the tiny gap instead of cancelling it and turning ordinary playback into restart churn.
        val precedingEnd = requestedBuffer.coverageEndBefore(position)
        if (precedingEnd > 0L && position - precedingEnd <= NEAR_FRONTIER_BYTES) {
            requestedBuffer.raiseDemandTo(position)
            return
        }

        // If the active session already landed its first segment PAST the byte offset ExoPlayer
        // needs (e.g. a keyframe/GOP boundary just before startTimeMs), step back and re-aim.
        if (reaimIfLandedPast(video, position, "open")) return

        // For merged A/V, both DataSources open for one player seek. They must share ONE real media
        // time; deriving separate times from VBR byte offsets produced conflicting restarts.
        if (hintedTarget != null) {
            restartAt(hintedTarget, video, position, "player", marginMs = currentMarginMs)
            return
        }

        // Without a player hint, audio is the only safe estimator: its bitrate is approximately
        // constant. A video-only byte ratio is invalid for VBR and must not cancel the A/V session.
        if (videoConfig != null && video) {
            requestedBuffer.raiseDemandTo(position)
            AppLog.event("SABR seek waiting for audio reference id=$id position=$position")
            return
        }
        val durationMs = videoConfig?.durationMs ?: audioConfig?.durationMs ?: 0L
        val contentLength = requestedBuffer.expectedLength
        if (durationMs <= 0L || contentLength <= 0L) {
            requestedBuffer.raiseDemandTo(position)
            return
        }
        val targetMs = position.coerceAtMost(contentLength) * durationMs / contentLength
        overshootRestarts = 0
        currentMarginMs = INITIAL_SEEK_MARGIN_MS
        restartAt(targetMs, video, position, "audio-estimate", marginMs = currentMarginMs)
    }

    /**
     * Called while [ModernSabrDataSource.read] waits for [position]. If the session landed its first
     * segment AFTER [position] (a GOP/keyframe boundary before [currentSessionStartMs]), a forward
     * drain can never cover [position] — step [currentSessionStartMs] back with a wider margin.
     */
    @Synchronized
    fun ensureCoveragePossible(video: Boolean, position: Long) {
        val requestedBuffer = buffer(video)
        if (position <= 0L || requestedBuffer.coveredAt(position) || requestedBuffer.failed()) return
        val initCovered = requestedBuffer.available()
        if (initCovered > 0L && position >= initCovered) {
            reaimIfLandedPast(video, position, "read")
        }
    }

    @Synchronized
    private fun reaimIfLandedPast(video: Boolean, position: Long, trigger: String): Boolean {
        val anchor = when (val active = session) {
            is SabrVideoSession -> if (video) active.firstVideoOffset else active.firstAudioOffset
            is SabrSession -> active.firstWrittenOffset
            else -> -1L
        }
        val requestedBuffer = buffer(video)
        val initCovered = requestedBuffer.available()
        // Anchor is the byte offset of the first non-init media segment.
        // ExoPlayer requests the init segment first (offset 0..initCovered), and then requests
        // the seek/resume position around anchor. An overshoot only occurs if position is BEFORE
        // the first media segment anchor, BUT NOT inside the initial header (initCovered).
        // Since initCovered is just the header length (e.g. 2345 bytes), position >= initCovered
        // is NOT enough: position must be the media target (positionForTime), not the init header!
        val videoTarget = videoConfig?.let { positionForTime(requestedStartMs, it.durationMs, it.videoFormat.contentLength) } ?: 0L
        val audioTarget = videoConfig?.let { positionForTime(requestedStartMs, it.durationMs, it.audioFormat.contentLength) }
            ?: audioConfig?.let { positionForTime(requestedStartMs, it.durationMs, it.format.contentLength) } ?: 0L
        val targetOffset = if (video) videoTarget else audioTarget
        // Only trigger overshoot re-aim if the reader is trying to read near targetOffset and anchor landed past it
        if (anchor <= position || position < targetOffset || currentSessionStartMs <= 0L) return false
        if (overshootRestarts >= SabrSeekLogic.MAX_SEEK_RESTARTS) return false
        overshootRestarts++
        currentMarginMs = (currentMarginMs * 2L).coerceAtMost(MAX_SEEK_MARGIN_MS)
        val proportionalMs = if (anchor > 0L) (currentSessionStartMs * position / anchor) else currentSessionStartMs
        val nextStartMs = (minOf(currentSessionStartMs, proportionalMs) - currentMarginMs).coerceAtLeast(0L)
        AppLog.event(
            "SABR seek overshoot re-aim id=$id video=$video trigger=$trigger position=$position anchor=$anchor " +
                "prevStartMs=$currentSessionStartMs nextStartMs=$nextStartMs marginMs=$currentMarginMs attempt=$overshootRestarts"
        )
        restartAt(
            targetMs = requestedStartMs,
            video = video,
            position = position,
            source = "overshoot-$trigger",
            marginMs = currentMarginMs,
            explicitStartMs = nextStartMs,
            forceRestart = true,
        )
        return true
    }

    @Synchronized
    private fun restartAt(
        targetMs: Long,
        video: Boolean,
        position: Long,
        source: String,
        marginMs: Long = currentMarginMs,
        explicitStartMs: Long? = null,
        forceRestart: Boolean = false,
    ) {
        val requestedBuffer = buffer(video)
        // The paired DataSource opening second observes the same explicit target and only raises its
        // demand watermark; it never cancels the replacement created by the first opener.
        if (!forceRestart && kotlin.math.abs(targetMs - requestedStartMs) < SEEK_COALESCE_MS && requestedStartMs > 0L) {
            requestedBuffer.raiseDemandTo(position)
            return
        }
        val startMs = explicitStartMs ?: (targetMs - marginMs).coerceAtLeast(0L)
        when (val old = session) {
            is SabrVideoSession -> old.cancel()
            is SabrSession -> old.cancel()
        }
        val videoTarget = videoConfig?.let { positionForTime(startMs, it.durationMs, it.videoFormat.contentLength) }
        val audioTarget = videoConfig?.let { positionForTime(startMs, it.durationMs, it.audioFormat.contentLength) }
            ?: audioConfig?.let { positionForTime(startMs, it.durationMs, it.format.contentLength) }
        videoTarget?.let { videoBuffer?.resetDemandFrom(it) }
        audioTarget?.let(audioBuffer::resetDemandFrom)
        // ModernSabrDataSource does not run an outer retry loop on session death, so a failed
        // session must mark its buffer errored (restartable = false) instead of leaving read() waiting.
        session = if (videoConfig != null) {
            SabrVideoSession(videoConfig, client, requireNotNull(videoBuffer), audioBuffer,
                startTimeMs = startMs, paceAheadVideoBytes = 32L * 1024 * 1024,
                paceAheadAudioBytes = 8L * 1024 * 1024, restartable = false)
        } else {
            SabrSession(requireNotNull(audioConfig), client, audioBuffer, startTimeMs = startMs,
                paceAheadBytes = 8L * 1024 * 1024, restartable = false)
        }
        requestedStartMs = targetMs
        currentSessionStartMs = startMs
        AppLog.event("SABR seek restart id=$id targetMs=$targetMs startMs=$startMs video=$video source=$source")
        startSession(session)
    }

    private fun positionForTime(timeMs: Long, durationMs: Long, length: Long): Long =
        if (durationMs > 0L) (timeMs * length / durationMs).coerceIn(0L, length - 1L) else 0L

    private fun startSession(value: Runnable) {
        sessionThread = Thread(value, "materialyt-sabr-$id").apply { isDaemon = true; start() }
    }

    companion object {
        private const val SEEK_HINT_TTL_MS = 15_000L
        private const val SEEK_COALESCE_MS = 10_000L
        private const val INITIAL_SEEK_MARGIN_MS = 12_000L
        private const val MAX_SEEK_MARGIN_MS = 60_000L
        private const val NEAR_FRONTIER_BYTES = 512L * 1024L
    }

    fun destroy() {
        when (val active = session) {
            is SabrVideoSession -> active.cancel()
            is SabrSession -> active.cancel()
        }
        videoBuffer?.release(true)
        audioBuffer.release(true)
    }
}

internal object ModernSabrRegistry {
    private val streams = ConcurrentHashMap<String, ModernSabrStream>()
    fun put(stream: ModernSabrStream) {
        // Destroy any older Dual-Track video streams so previous videos do not keep draining in the background.
        if (!stream.id.contains("-audio-")) {
            val staleKeys = streams.keys.filter { it != stream.id && !it.contains("-audio-") }
            for (key in staleKeys) {
                streams.remove(key)?.destroy()
            }
        }
        streams.put(stream.id, stream)?.destroy()
    }
    fun get(id: String): ModernSabrStream? = streams[id]
    fun hintPlaybackTime(id: String, positionMs: Long) { streams[id]?.hintPlaybackTime(positionMs) }
    fun destroyForVideo(videoId: String) {
        val prefix = "$videoId-"
        for (key in streams.keys.filter { it.startsWith(prefix) }) {
            streams.remove(key)?.destroy()
        }
    }
}

internal class ModernSabrDataSource(private val video: Boolean) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var stream: ModernSabrStream? = null
    private var position = 0L
    private var remaining = 0L
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val id = dataSpec.uri.host ?: throw IOException("Missing SABR stream id")
        val value = ModernSabrRegistry.get(id) ?: throw IOException("SABR stream expired: $id")
        stream = value
        position = dataSpec.position
        value.requestPosition(video, position)
        remaining = value.length(video) - position
        transferStarted(dataSpec)
        started = true
        return remaining
    }

    override fun read(target: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val wanted = minOf(readLength.toLong(), remaining).toInt()
        val activeStream = stream ?: return C.RESULT_END_OF_INPUT
        val buffer = activeStream.buffer(video)
        while (true) {
            val count = buffer.readCovered(position, target, offset, wanted)
            if (count > 0) {
                position += count
                remaining -= count
                bytesTransferred(count)
                return count
            }
            activeStream.ensureCoveragePossible(video, position)
            buffer.awaitChange(250)
        }
    }

    override fun getUri(): Uri? = uri
    override fun close() {
        stream = null
        if (started) transferEnded()
        started = false
        uri = null
    }

    class Factory(private val video: Boolean) : DataSource.Factory {
        override fun createDataSource(): DataSource = ModernSabrDataSource(video)
    }
}
