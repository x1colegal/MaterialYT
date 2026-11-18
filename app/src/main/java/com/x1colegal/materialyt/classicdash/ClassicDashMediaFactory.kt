package com.x1colegal.materialyt.classicdash

import android.content.Context
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.source.CompositeSequenceableLoaderFactory
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.SequenceableLoader
import com.x1colegal.materialyt.VisionOsFormat
import com.x1colegal.materialyt.VisionOsVodPlayback
import com.x1colegal.materialyt.sabrmodern.SabrBuffer
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.util.concurrent.TimeUnit

internal object ClassicDashMediaFactory {
    private val clenRegex = Regex("""[?&]clen=(\d+)""")

    internal val mediaHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 16
            })
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val liveLoaderFactory = CompositeSequenceableLoaderFactory { loaders ->
        object : SequenceableLoader {
            override fun getBufferedPositionUs(): Long =
                loaders.minOfOrNull { it.bufferedPositionUs } ?: C.TIME_END_OF_SOURCE

            override fun getNextLoadPositionUs(): Long =
                loaders.minOfOrNull { it.nextLoadPositionUs } ?: C.TIME_END_OF_SOURCE

            override fun continueLoading(positionUs: Long): Boolean {
                var continued = false
                for (loader in loaders) {
                    if (loader.continueLoading(positionUs)) continued = true
                }
                return continued
            }

            override fun isLoading(): Boolean = loaders.any { it.isLoading }
            override fun reevaluateBuffer(positionUs: Long) = loaders.forEach { it.reevaluateBuffer(positionUs) }
        }
    }

    fun hintSeek(videoId: String, positionMs: Long) {
        ClassicDashRegistry.hintPlaybackTime(videoId, positionMs)
    }

    fun releaseVideo(videoId: String) {
        ClassicDashRegistry.destroyForVideo(videoId)
    }

    fun create(
        context: Context,
        playback: VisionOsVodPlayback,
        videoFormat: VisionOsFormat?,
        audioFormat: VisionOsFormat?,
        preferredHeight: Int = 0,
        preferredAudioLang: String? = null,
        initialStartMs: Long = 0L,
    ): MediaSource {
        val dir = File(context.cacheDir, "classicdash").apply { mkdirs() }
        val isLive = playback.isLive || videoFormat == null

        if (videoFormat != null && audioFormat != null) {
            val id = "${playback.videoId}-vod-${videoFormat.itag}-${audioFormat.itag}"
            val videoLen = parseContentLength(videoFormat.url)
            val audioLen = parseContentLength(audioFormat.url)
            val videoBuf = SabrBuffer(
                expectedLength = videoLen ?: SabrBuffer.MAX_BUFFER_BYTES,
                file = File(dir, "$id-video.part"),
                growable = videoLen == null,
            )
            val audioBuf = SabrBuffer(
                expectedLength = audioLen ?: SabrBuffer.MAX_BUFFER_BYTES,
                file = File(dir, "$id-audio.part"),
                growable = audioLen == null,
            )
            val session = ClassicDashVodSession(
                id = id,
                client = mediaHttpClient,
                videoUrl = videoFormat.url,
                videoItag = videoFormat.itag,
                videoBuffer = videoBuf,
                audioUrl = audioFormat.url,
                audioItag = audioFormat.itag,
                audioBuffer = audioBuf,
                durationMs = playback.durationMs,
                initialStartMs = initialStartMs,
            )
            ClassicDashRegistry.put(ClassicDashStream(id, playback.isLive, videoBuf, audioBuf, session))
            session.start()

            val videoFactory = ProgressiveMediaSource.Factory(ClassicDashDataSource.Factory(true), DefaultExtractorsFactory())
            val audioFactory = ProgressiveMediaSource.Factory(ClassicDashDataSource.Factory(false), DefaultExtractorsFactory())
            if (playback.isLive) {
                videoFactory.setContinueLoadingCheckIntervalBytes(Int.MAX_VALUE)
                audioFactory.setContinueLoadingCheckIntervalBytes(Int.MAX_VALUE)
            }
            val videoSource = videoFactory.createMediaSource(MediaItem.fromUri("classicdash://$id/video"))
            val audioSource = audioFactory.createMediaSource(MediaItem.fromUri("classicdash://$id/audio"))
            return if (playback.isLive) {
                MergingMediaSource(true, false, liveLoaderFactory, videoSource, audioSource)
            } else {
                MergingMediaSource(true, videoSource, audioSource)
            }
        }

        // Live MPD (ANDROID_VR dashManifestUrl)
        val id = "${playback.videoId}-live-${preferredHeight}-${preferredAudioLang.orEmpty()}"
        val videoBuf = SabrBuffer(SabrBuffer.MAX_BUFFER_BYTES, File(dir, "$id-video.part"), growable = true)
        val audioBuf = SabrBuffer(SabrBuffer.MAX_BUFFER_BYTES, File(dir, "$id-audio.part"), growable = true)
        val session = ClassicDashLiveSession(
            id = id,
            client = mediaHttpClient,
            manifestUrl = playback.dashManifestUrl,
            videoBuffer = videoBuf,
            audioBuffer = audioBuf,
            preferredHeight = preferredHeight,
            preferredAudioLang = preferredAudioLang,
        )
        ClassicDashRegistry.put(ClassicDashStream(id, isLive = true, videoBuffer = videoBuf, audioBuffer = audioBuf, session = session))
        session.start()

        val videoFactory = ProgressiveMediaSource.Factory(ClassicDashDataSource.Factory(true), DefaultExtractorsFactory())
            .setContinueLoadingCheckIntervalBytes(Int.MAX_VALUE)
        val audioFactory = ProgressiveMediaSource.Factory(ClassicDashDataSource.Factory(false), DefaultExtractorsFactory())
            .setContinueLoadingCheckIntervalBytes(Int.MAX_VALUE)
        val videoSource = videoFactory.createMediaSource(MediaItem.fromUri("classicdash://$id/video"))
        val audioSource = audioFactory.createMediaSource(MediaItem.fromUri("classicdash://$id/audio"))
        return MergingMediaSource(true, false, liveLoaderFactory, videoSource, audioSource)
    }

    fun createAudio(
        context: Context,
        trackId: String,
        playback: VisionOsVodPlayback,
        audioFormat: VisionOsFormat?,
        preferredAudioLang: String? = null,
    ): MediaSource {
        val dir = File(context.cacheDir, "classicdash").apply { mkdirs() }
        if (audioFormat != null) {
            val id = "$trackId-audio-${audioFormat.itag}"
            val audioLen = parseContentLength(audioFormat.url)
            val audioBuf = SabrBuffer(
                expectedLength = audioLen ?: SabrBuffer.MAX_BUFFER_BYTES,
                file = File(dir, "$id-audio.part"),
                growable = audioLen == null,
            )
            val session = ClassicDashVodSession(
                id = id,
                client = mediaHttpClient,
                videoUrl = null,
                videoItag = 0,
                videoBuffer = null,
                audioUrl = audioFormat.url,
                audioItag = audioFormat.itag,
                audioBuffer = audioBuf,
                durationMs = playback.durationMs,
            )
            ClassicDashRegistry.put(ClassicDashStream(id, playback.isLive, null, audioBuf, session))
            session.start()
            val factory = ProgressiveMediaSource.Factory(ClassicDashDataSource.Factory(false), DefaultExtractorsFactory())
            if (playback.isLive) factory.setContinueLoadingCheckIntervalBytes(Int.MAX_VALUE)
            return factory.createMediaSource(MediaItem.fromUri("classicdash://$id/audio"))
        }

        val id = "$trackId-audio-live"
        val audioBuf = SabrBuffer(SabrBuffer.MAX_BUFFER_BYTES, File(dir, "$id-audio.part"), growable = true)
        val session = ClassicDashLiveSession(
            id = id,
            client = mediaHttpClient,
            manifestUrl = playback.dashManifestUrl,
            videoBuffer = null,
            audioBuffer = audioBuf,
            preferredHeight = 0,
            preferredAudioLang = preferredAudioLang,
        )
        ClassicDashRegistry.put(ClassicDashStream(id, isLive = true, videoBuffer = null, audioBuffer = audioBuf, session = session))
        session.start()
        return ProgressiveMediaSource.Factory(ClassicDashDataSource.Factory(false), DefaultExtractorsFactory())
            .setContinueLoadingCheckIntervalBytes(Int.MAX_VALUE)
            .createMediaSource(MediaItem.fromUri("classicdash://$id/audio"))
    }

    private fun parseContentLength(url: String): Long? =
        clenRegex.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { SabrBuffer.lengthValid(it) }
}
