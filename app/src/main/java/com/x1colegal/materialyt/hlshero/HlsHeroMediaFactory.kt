package com.x1colegal.materialyt.hlshero

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.source.CompositeMediaSource
import com.google.android.exoplayer2.source.CompositeSequenceableLoaderFactory
import com.google.android.exoplayer2.source.ForwardingTimeline
import com.google.android.exoplayer2.source.MediaPeriod
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.SequenceableLoader
import com.google.android.exoplayer2.upstream.Allocator
import com.google.android.exoplayer2.upstream.TransferListener
import com.google.android.exoplayer2.util.Util
import com.x1colegal.materialyt.classicdash.ClassicDashMediaFactory
import com.x1colegal.materialyt.sabrmodern.SabrBuffer
import java.io.File

internal object HlsHeroMediaFactory {
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

    fun releaseVideo(videoId: String) {
        HlsHeroRegistry.destroyForVideo(videoId)
    }

    fun create(
        context: Context,
        videoId: String,
        hlsManifestUrl: String,
        isLive: Boolean,
        preferredHeight: Int = 0,
        preferredAudioLang: String? = null,
        durationMs: Long = 0L,
        initialStartMs: Long = 0L,
    ): MediaSource {
        val dir = File(context.cacheDir, "hlshero").apply { mkdirs() }
        val id = "$videoId-hls-$preferredHeight-${preferredAudioLang.orEmpty()}"
        val videoBuf = SabrBuffer(SabrBuffer.MAX_BUFFER_BYTES, File(dir, "$id-video.part"), growable = true)
        val audioBuf = SabrBuffer(SabrBuffer.MAX_BUFFER_BYTES, File(dir, "$id-audio.part"), growable = true)
        return HlsHeroCompositeMediaSource(
            id = id,
            hlsManifestUrl = hlsManifestUrl,
            isLive = isLive,
            audioOnly = false,
            durationMs = durationMs,
            initialStartMs = initialStartMs,
            videoBuffer = videoBuf,
            audioBuffer = audioBuf,
            preferredHeight = preferredHeight,
            preferredAudioLang = preferredAudioLang,
        )
    }

    fun createAudio(
        context: Context,
        trackId: String,
        hlsManifestUrl: String,
        isLive: Boolean,
        preferredAudioLang: String? = null,
        durationMs: Long = 0L,
    ): MediaSource {
        val dir = File(context.cacheDir, "hlshero").apply { mkdirs() }
        val id = "$trackId-audio-hls-${preferredAudioLang.orEmpty()}"
        val audioBuf = SabrBuffer(SabrBuffer.MAX_BUFFER_BYTES, File(dir, "$id-audio.part"), growable = true)
        return HlsHeroCompositeMediaSource(
            id = id,
            hlsManifestUrl = hlsManifestUrl,
            isLive = isLive,
            audioOnly = true,
            durationMs = durationMs,
            initialStartMs = 0L,
            videoBuffer = null,
            audioBuffer = audioBuf,
            preferredHeight = 0,
            preferredAudioLang = preferredAudioLang,
        )
    }

    private class HlsHeroCompositeMediaSource(
        private val id: String,
        private val hlsManifestUrl: String,
        private val isLive: Boolean,
        private val audioOnly: Boolean,
        private val durationMs: Long,
        private val initialStartMs: Long,
        private val videoBuffer: SabrBuffer?,
        private val audioBuffer: SabrBuffer,
        private val preferredHeight: Int,
        private val preferredAudioLang: String?,
    ) : CompositeMediaSource<Void>() {
        private var childSource: MediaSource? = null
        private var mediaItem: MediaItem = MediaItem.fromUri("hlshero://$id")
        @Volatile
        private var released = false

        override fun getMediaItem(): MediaItem = mediaItem

        override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
            super.prepareSourceInternal(mediaTransferListener)
            released = false
            val handler = Handler(Looper.myLooper() ?: Looper.getMainLooper())
            val session = HlsHeroSession(
                id = id,
                client = ClassicDashMediaFactory.mediaHttpClient,
                masterUrl = hlsManifestUrl,
                isLive = isLive,
                audioOnly = audioOnly,
                initialStartMs = initialStartMs,
                videoBuffer = videoBuffer,
                audioBuffer = audioBuffer,
                preferredHeight = preferredHeight,
                preferredAudioLang = preferredAudioLang,
            ) { hasSeparateAudio ->
                handler.post {
                    if (released) return@post
                    val source = buildChildSource(hasSeparateAudio)
                    childSource = source
                    prepareChildSource(null, source)
                }
            }
            HlsHeroRegistry.put(HlsHeroStream(id, isLive, videoBuffer, audioBuffer, session))
            session.start()
        }

        private fun buildChildSource(hasSeparateAudio: Boolean): MediaSource {
            if (audioOnly) {
                val factory = ProgressiveMediaSource.Factory(HlsHeroDataSource.Factory(false), DefaultExtractorsFactory())
                if (isLive) factory.setContinueLoadingCheckIntervalBytes(Int.MAX_VALUE)
                return factory.createMediaSource(MediaItem.fromUri("hlshero://$id/audio"))
            }
            val videoFactory = ProgressiveMediaSource.Factory(HlsHeroDataSource.Factory(true), DefaultExtractorsFactory())
            if (isLive) videoFactory.setContinueLoadingCheckIntervalBytes(Int.MAX_VALUE)
            val videoSource = videoFactory.createMediaSource(MediaItem.fromUri("hlshero://$id/video"))
            if (!hasSeparateAudio) return videoSource

            val audioFactory = ProgressiveMediaSource.Factory(HlsHeroDataSource.Factory(false), DefaultExtractorsFactory())
            if (isLive) audioFactory.setContinueLoadingCheckIntervalBytes(Int.MAX_VALUE)
            val audioSource = audioFactory.createMediaSource(MediaItem.fromUri("hlshero://$id/audio"))
            return if (isLive) {
                MergingMediaSource(true, false, liveLoaderFactory, videoSource, audioSource)
            } else {
                MergingMediaSource(true, videoSource, audioSource)
            }
        }

        override fun onChildSourceInfoRefreshed(
            childSourceId: Void?,
            mediaSource: MediaSource,
            newTimeline: Timeline,
        ) {
            if (!isLive && durationMs > 0L) {
                val durationUs = Util.msToUs(durationMs)
                refreshSourceInfo(object : ForwardingTimeline(newTimeline) {
                    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
                        super.getWindow(windowIndex, window, defaultPositionProjectionUs)
                        window.durationUs = durationUs
                        window.isDynamic = false
                        window.isSeekable = true
                        return window
                    }

                    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
                        super.getPeriod(periodIndex, period, setIds)
                        period.durationUs = durationUs
                        return period
                    }
                })
            } else {
                refreshSourceInfo(newTimeline)
            }
        }

        override fun createPeriod(
            id: MediaSource.MediaPeriodId,
            allocator: Allocator,
            startPositionUs: Long,
        ): MediaPeriod = requireNotNull(childSource).createPeriod(id, allocator, startPositionUs)

        override fun releasePeriod(mediaPeriod: MediaPeriod) {
            childSource?.releasePeriod(mediaPeriod)
        }

        override fun releaseSourceInternal() {
            released = true
            childSource = null
            super.releaseSourceInternal()
        }
    }
}
