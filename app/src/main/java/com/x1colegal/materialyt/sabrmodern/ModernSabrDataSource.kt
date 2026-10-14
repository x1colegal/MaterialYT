package com.x1colegal.materialyt.sabrmodern

import android.net.Uri
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
) {
    val videoBuffer: SabrBuffer?
    val audioBuffer: SabrBuffer
    private var session: Runnable
    private var requestedStartMs = 0L

    init {
        val dir = File(MaterialYtApp.instance.cacheDir, "sabr-modern").apply { mkdirs() }
        if (videoConfig != null) {
            videoBuffer = SabrBuffer(videoConfig.videoFormat.contentLength, File(dir, "$id-video.part"))
            audioBuffer = SabrBuffer(videoConfig.audioFormat.contentLength, File(dir, "$id-audio.part"))
            session = SabrVideoSession(videoConfig, client, videoBuffer, audioBuffer,
                paceAheadVideoBytes = 32L * 1024 * 1024,
                paceAheadAudioBytes = 8L * 1024 * 1024)
        } else {
            val cfg = requireNotNull(audioConfig)
            videoBuffer = null
            audioBuffer = SabrBuffer(cfg.format.contentLength, File(dir, "$id-audio.part"))
            session = SabrSession(cfg, client, audioBuffer, paceAheadBytes = 8L * 1024 * 1024)
        }
        startSession(session)
    }

    fun buffer(video: Boolean): SabrBuffer = if (video) requireNotNull(videoBuffer) else audioBuffer
    fun length(video: Boolean): Long = buffer(video).expectedLength

    /** Re-aim SABR at a distant ExoPlayer seek instead of draining linearly from the old position. */
    @Synchronized
    fun requestPosition(video: Boolean, position: Long) {
        val requestedBuffer = buffer(video)
        if (position <= 0L || requestedBuffer.coveredAt(position)) return
        val durationMs = videoConfig?.durationMs ?: audioConfig?.durationMs ?: 0L
        val contentLength = requestedBuffer.expectedLength
        if (durationMs <= 0L || contentLength <= 0L) {
            requestedBuffer.raiseDemandTo(position)
            return
        }
        val targetMs = position.coerceAtMost(contentLength) * durationMs / contentLength
        // Opening the paired audio/video DataSources for one seek must create only one replacement.
        if (kotlin.math.abs(targetMs - requestedStartMs) < 3_000L && requestedStartMs > 0L) {
            requestedBuffer.raiseDemandTo(position)
            return
        }
        val startMs = (targetMs - SabrSeekLogic.SEEK_MARGIN_MS).coerceAtLeast(0L)
        when (val old = session) {
            is SabrVideoSession -> old.cancel()
            is SabrSession -> old.cancel()
        }
        val videoTarget = videoConfig?.let { positionForTime(startMs, it.durationMs, it.videoFormat.contentLength) }
        val audioTarget = videoConfig?.let { positionForTime(startMs, it.durationMs, it.audioFormat.contentLength) }
            ?: audioConfig?.let { positionForTime(startMs, it.durationMs, it.format.contentLength) }
        videoTarget?.let { videoBuffer?.resetDemandFrom(it) }
        audioTarget?.let(audioBuffer::resetDemandFrom)
        session = if (videoConfig != null) {
            SabrVideoSession(videoConfig, client, requireNotNull(videoBuffer), audioBuffer,
                startTimeMs = startMs, paceAheadVideoBytes = 32L * 1024 * 1024,
                paceAheadAudioBytes = 8L * 1024 * 1024, restartable = true)
        } else {
            SabrSession(requireNotNull(audioConfig), client, audioBuffer, startTimeMs = startMs,
                paceAheadBytes = 8L * 1024 * 1024, restartable = true)
        }
        requestedStartMs = targetMs
        AppLog.event("SABR seek restart id=$id targetMs=$targetMs startMs=$startMs video=$video")
        startSession(session)
    }

    private fun positionForTime(timeMs: Long, durationMs: Long, length: Long): Long =
        if (durationMs > 0L) (timeMs * length / durationMs).coerceIn(0L, length - 1L) else 0L

    private fun startSession(value: Runnable) {
        Thread(value, "materialyt-sabr-$id").apply { isDaemon = true; start() }
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
    fun put(stream: ModernSabrStream) { streams.put(stream.id, stream)?.destroy() }
    fun get(id: String): ModernSabrStream? = streams[id]
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
        val buffer = stream?.buffer(video) ?: return C.RESULT_END_OF_INPUT
        while (true) {
            val count = buffer.readCovered(position, target, offset, wanted)
            if (count > 0) {
                position += count
                remaining -= count
                bytesTransferred(count)
                return count
            }
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
