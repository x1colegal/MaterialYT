package com.x1colegal.materialyt.hlshero

import android.net.Uri
import android.os.SystemClock
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.BaseDataSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.x1colegal.materialyt.AppLog
import com.x1colegal.materialyt.sabrmodern.SabrBuffer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal interface HlsHeroSessionHandle {
    fun cancel()
}

internal class HlsHeroStream(
    val id: String,
    val isLive: Boolean,
    val videoBuffer: SabrBuffer?,
    val audioBuffer: SabrBuffer,
    private val session: HlsHeroSessionHandle,
) {
    fun buffer(video: Boolean): SabrBuffer = if (video) requireNotNull(videoBuffer) else audioBuffer

    fun destroy() {
        session.cancel()
        videoBuffer?.release(true)
        audioBuffer.release(true)
    }
}

internal object HlsHeroRegistry {
    private val streams = ConcurrentHashMap<String, HlsHeroStream>()

    fun put(stream: HlsHeroStream) {
        if (!stream.id.contains("-audio-")) {
            val staleKeys = streams.keys.filter { it != stream.id && !it.contains("-audio-") }
            for (key in staleKeys) {
                streams.remove(key)?.destroy()
            }
        }
        streams.put(stream.id, stream)?.destroy()
    }

    fun get(id: String): HlsHeroStream? = streams[id]

    fun destroyForVideo(videoId: String) {
        val prefix = "$videoId-"
        for (key in streams.keys.filter { it.startsWith(prefix) }) {
            streams.remove(key)?.destroy()
        }
    }
}

internal class HlsHeroDataSource(private val video: Boolean) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var stream: HlsHeroStream? = null
    private var position = 0L
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val id = dataSpec.uri.host ?: throw IOException("Missing HlsHero stream id")
        val value = HlsHeroRegistry.get(id) ?: throw IOException("HlsHero stream expired: $id")
        stream = value
        position = dataSpec.position
        value.buffer(video).raiseDemandTo(position)
        transferStarted(dataSpec)
        started = true
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(target: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        val activeStream = stream ?: return C.RESULT_END_OF_INPUT
        val buffer = activeStream.buffer(video)
        var waitingSinceMs = 0L
        var lastWaitLogMs = 0L
        while (true) {
            val count = buffer.readCovered(position, target, offset, readLength)
            if (count > 0) {
                if (activeStream.isLive && waitingSinceMs > 0L) {
                    val waitedMs = SystemClock.elapsedRealtime() - waitingSinceMs
                    if (waitedMs >= 500L) {
                        AppLog.event(
                            "HlsHero live reader resumed id=${activeStream.id} video=$video position=$position " +
                                "count=$count available=${buffer.available()} waitedMs=$waitedMs",
                        )
                    }
                }
                position += count
                bytesTransferred(count)
                return count
            }
            if (buffer.isComplete() && !buffer.coveredAt(position)) return C.RESULT_END_OF_INPUT
            if (activeStream.isLive) {
                val now = SystemClock.elapsedRealtime()
                if (waitingSinceMs == 0L) waitingSinceMs = now
                if (lastWaitLogMs == 0L || now - lastWaitLogMs >= 5_000L) {
                    lastWaitLogMs = now
                    AppLog.event(
                        "HlsHero live reader waiting id=${activeStream.id} video=$video position=$position " +
                            "available=${buffer.available()} failed=${buffer.failed()}",
                    )
                }
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
        override fun createDataSource(): DataSource = HlsHeroDataSource(video)
    }
}
