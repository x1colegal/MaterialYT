package com.x1colegal.materialyt

import android.net.Uri
import android.util.Base64
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.CompositeSequenceableLoader
import com.google.android.exoplayer2.source.CompositeSequenceableLoaderFactory
import com.google.android.exoplayer2.source.SequenceableLoader
import com.google.android.exoplayer2.upstream.DataSource
import com.x1colegal.materialyt.sabrmodern.ModernSabrDataSource
import com.x1colegal.materialyt.sabrmodern.ModernSabrRegistry
import com.x1colegal.materialyt.sabrmodern.ModernSabrStream
import com.x1colegal.materialyt.sabrmodern.SabrConfig
import com.x1colegal.materialyt.sabrmodern.SabrMessages
import com.x1colegal.materialyt.sabrmodern.SabrVideoConfig

/** Current SABR/UMP transport with positional, disk-backed A/V reassembly. */
object SabrMediaFactory {
    private fun streamId(info: SabrPlaybackInfo, video: SabrFormat, audio: SabrFormat) =
        "${info.videoId}-${video.itag}-${audio.itag}"

    fun hintSeek(info: SabrPlaybackInfo, video: SabrFormat, audio: SabrFormat, positionMs: Long) {
        ModernSabrRegistry.hintPlaybackTime(streamId(info, video, audio), positionMs)
    }

    fun reportPlaybackTime(info: SabrPlaybackInfo, video: SabrFormat, audio: SabrFormat, positionMs: Long) {
        ModernSabrRegistry.reportPlaybackTime(streamId(info, video, audio), positionMs)
    }

    fun releaseVideo(videoId: String) {
        ModernSabrRegistry.destroyForVideo(videoId)
    }

    fun create(info: SabrPlaybackInfo, video: SabrFormat, audio: SabrFormat, upstreamFactory: DataSource.Factory, initialStartMs: Long = 0L): MediaSource {
        require(!video.audio && audio.audio)
        require(video.contentLength > 0 && audio.contentLength > 0) { "SABR formats have no content length" }
        AppLog.event(
            "SABR create video=${info.videoId} clientId=${info.clientId} videoItag=${video.itag} videoXtags=${video.xtags} " +
                "audioItag=${audio.itag} audioTrackId=${audio.audioTrackId} audioXtags=${audio.xtags}"
        )
        val id = streamId(info, video, audio)
        val config = SabrVideoConfig(
            info.videoId,
            info.serverAbrStreamingUrl, decode(info.videoPlaybackUstreamerConfig),
            video.wireFormat(), audio.wireFormat(), decode(info.streamingPoToken), clientInfo(info),
            info.userAgent, { it }, durationMs = info.durationMs,
            reloadPlayer = { token ->
                val refreshed = YouTubeRepository.reloadSabrPlayback(info, token)
                refreshed.first to decode(refreshed.second)
            },
            refreshPlayer = when (info.clientId) {
                1, 67 -> ({
                    val refreshed = YouTubeRepository.refreshWebSabrAdPlayback(info)
                    refreshed.first to decode(refreshed.second)
                })
                101 -> ({
                    val refreshed = YouTubeRepository.sabrPlaybackInfo(info.videoId)
                    refreshed.serverAbrStreamingUrl to decode(refreshed.videoPlaybackUstreamerConfig)
                })
                else -> null
            },
            refreshPoToken = if (info.clientId == 1) ({
                YouTubeRepository.refreshWebSabrPoToken(info).let { it to decode(it) }
            }) else null,
            heartbeatIntervalMs = info.heartbeatIntervalMs,
            heartbeatToken = info.heartbeatToken,
            heartbeatServerData = info.heartbeatServerData,
            checkHeartbeat = if (info.durationMs <= 0L) ({ serverData ->
                YouTubeRepository.checkSabrLiveHeartbeat(info, serverData)
            }) else null,
            requestHeaders = YouTubeRepository.mediaHeaders(info.serverAbrStreamingUrl),
        )
        PlaybackLoadStatus.showInitial(info.videoId, "Getting init segments…")
        ModernSabrRegistry.put(ModernSabrStream(id, config, null, HttpBackend.playbackClient(), initialStartMs = initialStartMs))
        val videoItem = MediaItem.fromUri(Uri.parse("sabrmodern://$id/video"))
        val audioItem = MediaItem.fromUri(Uri.parse("sabrmodern://$id/audio"))
        // SabrVideoSession rewrites every live tfdt relative to the first emitted fragment. Honour
        // those normalized timestamps here. IGNORE_TFDT made ExoPlayer repeatedly expose only the
        // first 83 ms/frame of each appended response instead of advancing the live timeline.
        val extractors = DefaultExtractorsFactory()
        val videoFactory = ProgressiveMediaSource.Factory(ModernSabrDataSource.Factory(true), extractors)
        val audioFactory = ProgressiveMediaSource.Factory(ModernSabrDataSource.Factory(false), extractors)
        if (info.durationMs <= 0L) {
            // ProgressiveMediaPeriod normally pauses a load task after roughly 1 MiB and relies on
            // continueLoading() to reopen its condition variable. With two independent infinite
            // progressive sources inside MergingMediaSource, the video task could remain paused while
            // audio kept loading, freezing the picture after a bitrate-dependent 4-62 seconds even
            // though the SABR video spool continued growing. A live source never reaches EOF, so keep
            // both extractors draining continuously instead of using the finite-file byte checkpoint.
            videoFactory.setContinueLoadingCheckIntervalBytes(Int.MAX_VALUE)
            audioFactory.setContinueLoadingCheckIntervalBytes(Int.MAX_VALUE)
        }
        val videoSource = videoFactory.createMediaSource(videoItem)
        val audioSource = audioFactory.createMediaSource(audioItem)
        return if (info.durationMs <= 0L) {
            // ProgressiveMediaPeriod closes each child's load condition once it discovers that
            // child's format. The default composite loader then advances only the child with the
            // earliest next-load position. With two endless progressive live tracks this is
            // timing-dependent and can starve audio forever after the first fragment while video
            // continues draining. Explicitly wake both selected children on every load pass.
            val liveLoaderFactory = CompositeSequenceableLoaderFactory { loaders ->
                val timeline = CompositeSequenceableLoader(loaders)
                object : SequenceableLoader {
                    override fun getBufferedPositionUs(): Long = timeline.bufferedPositionUs
                    override fun getNextLoadPositionUs(): Long = timeline.nextLoadPositionUs
                    override fun continueLoading(positionUs: Long): Boolean {
                        var progressed = false
                        for (loader in loaders) progressed = loader.continueLoading(positionUs) || progressed
                        return progressed
                    }
                    override fun isLoading(): Boolean = loaders.any { it.isLoading }
                    override fun reevaluateBuffer(positionUs: Long) {
                        loaders.forEach { it.reevaluateBuffer(positionUs) }
                    }
                }
            }
            MergingMediaSource(true, false, liveLoaderFactory, videoSource, audioSource)
        } else {
            MergingMediaSource(true, videoSource, audioSource)
        }
    }

    fun createAudio(info: SabrPlaybackInfo, audio: SabrFormat, upstreamFactory: DataSource.Factory): MediaSource {
        require(audio.audio && audio.contentLength > 0) { "SABR audio has no content length" }
        val id = "${info.videoId}-audio-${audio.itag}"
        val config = SabrConfig(
            info.videoId,
            info.serverAbrStreamingUrl, decode(info.videoPlaybackUstreamerConfig), audio.wireFormat(),
            decode(info.streamingPoToken), clientInfo(info), info.userAgent, { it }, durationMs = info.durationMs,
            reloadPlayer = { token ->
                val refreshed = YouTubeRepository.reloadSabrPlayback(info, token)
                refreshed.first to decode(refreshed.second)
            },
            refreshPlayer = when (info.clientId) {
                1, 67 -> ({
                    val refreshed = YouTubeRepository.refreshWebSabrAdPlayback(info)
                    refreshed.first to decode(refreshed.second)
                })
                101 -> ({
                    val refreshed = YouTubeRepository.sabrPlaybackInfo(info.videoId)
                    refreshed.serverAbrStreamingUrl to decode(refreshed.videoPlaybackUstreamerConfig)
                })
                else -> null
            },
            refreshPoToken = if (info.clientId == 1) ({
                YouTubeRepository.refreshWebSabrPoToken(info).let { it to decode(it) }
            }) else null,
        )
        PlaybackLoadStatus.showInitial(info.videoId, "Getting init segments…")
        ModernSabrRegistry.put(ModernSabrStream(id, null, config, HttpBackend.playbackClient()))
        return ProgressiveMediaSource.Factory(ModernSabrDataSource.Factory(false))
            .createMediaSource(MediaItem.fromUri(Uri.parse("sabrmodern://$id/audio")))
    }

    private fun SabrFormat.wireFormat() = SabrMessages.Format(
        itag, lastModified, contentLength, xtags, audioTrackId,
        codec, width, height, fps, bitrate, audioChannels,
        targetDurationMs,
    )
    private fun clientInfo(info: SabrPlaybackInfo) = SabrMessages.ClientInfo(
        info.clientId, info.clientVersion, info.osName, info.osVersion, info.deviceMake, info.deviceModel,
    )
    private fun decode(value: String): ByteArray {
        val normalized = value.replace('-', '+').replace('_', '/')
        return Base64.decode(normalized + "=".repeat((4 - normalized.length % 4) % 4), Base64.DEFAULT)
    }
}
