package com.x1colegal.materialyt.classicdash

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

internal interface ClassicDashSessionHandle {
    fun requestPosition(video: Boolean, position: Long)
    fun hintPlaybackTime(positionMs: Long)
    fun cancel()
}

internal class ClassicDashStream(
    val id: String,
    val isLive: Boolean,
    val videoBuffer: SabrBuffer?,
    val audioBuffer: SabrBuffer,
    private val session: ClassicDashSessionHandle,
) {
    fun buffer(video: Boolean): SabrBuffer = if (video) requireNotNull(videoBuffer) else audioBuffer

    fun length(video: Boolean): Long = buffer(video).expectedLength

    fun requestPosition(video: Boolean, position: Long) {
        val buf = buffer(video)
        buf.raiseDemandTo(position)
        session.requestPosition(video, position)
    }

    fun hintPlaybackTime(positionMs: Long) {
        session.hintPlaybackTime(positionMs)
    }

    fun destroy() {
        session.cancel()
        videoBuffer?.release(true)
        audioBuffer.release(true)
    }
}

internal object ClassicDashRegistry {
    private val streams = ConcurrentHashMap<String, ClassicDashStream>()

    fun put(stream: ClassicDashStream) {
        if (!stream.id.contains("-audio-")) {
            val staleKeys = streams.keys.filter { it != stream.id && !it.contains("-audio-") }
            for (key in staleKeys) {
                streams.remove(key)?.destroy()
            }
        }
        streams.put(stream.id, stream)?.destroy()
    }

    fun get(id: String): ClassicDashStream? = streams[id]

    fun hintPlaybackTime(videoId: String, positionMs: Long) {
        val prefix = "$videoId-"
        for ((key, stream) in streams) {
            if (key == videoId || key.startsWith(prefix)) {
                stream.hintPlaybackTime(positionMs)
            }
        }
    }

    fun destroyForVideo(videoId: String) {
        val prefix = "$videoId-"
        for (key in streams.keys.filter { it.startsWith(prefix) }) {
            streams.remove(key)?.destroy()
        }
    }
}

internal class ClassicDashDataSource(private val video: Boolean) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var stream: ClassicDashStream? = null
    private var position = 0L
    private var remaining = 0L
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val id = dataSpec.uri.host ?: throw IOException("Missing ClassicDash stream id")
        val value = ClassicDashRegistry.get(id) ?: throw IOException("ClassicDash stream expired: $id")
        stream = value
        position = dataSpec.position
        value.requestPosition(video, position)
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            value.isLive -> C.LENGTH_UNSET.toLong()
            else -> {
                val expected = value.length(video)
                if (expected == SabrBuffer.MAX_BUFFER_BYTES) C.LENGTH_UNSET.toLong() else expected - position
            }
        }
        transferStarted(dataSpec)
        started = true
        return remaining
    }

    override fun read(target: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val wanted = if (remaining == C.LENGTH_UNSET.toLong()) readLength else minOf(readLength.toLong(), remaining).toInt()
        val activeStream = stream ?: return C.RESULT_END_OF_INPUT
        val buffer = activeStream.buffer(video)
        var waitingSinceMs = 0L
        var lastWaitLogMs = 0L
        while (true) {
            val count = buffer.readCovered(position, target, offset, wanted)
            if (count > 0) {
                if (activeStream.isLive && waitingSinceMs > 0L) {
                    val waitedMs = SystemClock.elapsedRealtime() - waitingSinceMs
                    if (waitedMs >= 500L) {
                        AppLog.event(
                            "ClassicDash live reader resumed id=${activeStream.id} video=$video position=$position " +
                                "count=$count available=${buffer.available()} waitedMs=$waitedMs",
                        )
                    }
                }
                position += count
                if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count
                bytesTransferred(count)
                return count
            }
            if (buffer.isComplete() && !buffer.coveredAt(position)) return C.RESULT_END_OF_INPUT
            if (!activeStream.isLive) {
                activeStream.requestPosition(video, position)
            }
            if (activeStream.isLive) {
                val now = SystemClock.elapsedRealtime()
                if (waitingSinceMs == 0L) waitingSinceMs = now
                if (lastWaitLogMs == 0L || now - lastWaitLogMs >= 5_000L) {
                    lastWaitLogMs = now
                    AppLog.event(
                        "ClassicDash live reader waiting id=${activeStream.id} video=$video position=$position " +
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
        override fun createDataSource(): DataSource = ClassicDashDataSource(video)
    }
}
