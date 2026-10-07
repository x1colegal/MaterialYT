package com.x1colegal.materialyt

import android.net.Uri
import android.util.Base64
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DataSource
import com.x1colegal.materialyt.sabrmodern.ModernSabrDataSource
import com.x1colegal.materialyt.sabrmodern.ModernSabrRegistry
import com.x1colegal.materialyt.sabrmodern.ModernSabrStream
import com.x1colegal.materialyt.sabrmodern.SabrConfig
import com.x1colegal.materialyt.sabrmodern.SabrMessages
import com.x1colegal.materialyt.sabrmodern.SabrVideoConfig

/** Current SABR/UMP transport with positional, disk-backed A/V reassembly. */
object SabrMediaFactory {
    fun create(info: SabrPlaybackInfo, video: SabrFormat, audio: SabrFormat, upstreamFactory: DataSource.Factory): MediaSource {
        require(!video.audio && audio.audio)
        require(video.contentLength > 0 && audio.contentLength > 0) { "SABR formats have no content length" }
        AppLog.event(
            "SABR create video=${info.videoId} videoItag=${video.itag} videoXtags=${video.xtags} " +
                "audioItag=${audio.itag} audioTrackId=${audio.audioTrackId} audioXtags=${audio.xtags}"
        )
        val id = "${info.videoId}-${video.itag}-${audio.itag}"
        val config = SabrVideoConfig(
            info.serverAbrStreamingUrl, decode(info.videoPlaybackUstreamerConfig),
            video.wireFormat(), audio.wireFormat(), decode(info.streamingPoToken), clientInfo(info),
            info.userAgent, { it }, durationMs = info.durationMs,
            reloadPlayer = { token ->
                val refreshed = YouTubeRepository.reloadSabrPlayback(info, token)
                refreshed.first to decode(refreshed.second)
            },
        )
        ModernSabrRegistry.put(ModernSabrStream(id, config, null, HttpBackend.playbackClient()))
        val videoSource = ProgressiveMediaSource.Factory(ModernSabrDataSource.Factory(true))
            .createMediaSource(MediaItem.fromUri(Uri.parse("sabrmodern://$id/video")))
        val audioSource = ProgressiveMediaSource.Factory(ModernSabrDataSource.Factory(false))
            .createMediaSource(MediaItem.fromUri(Uri.parse("sabrmodern://$id/audio")))
        return MergingMediaSource(true, videoSource, audioSource)
    }

    fun createAudio(info: SabrPlaybackInfo, audio: SabrFormat, upstreamFactory: DataSource.Factory): MediaSource {
        require(audio.audio && audio.contentLength > 0) { "SABR audio has no content length" }
        val id = "${info.videoId}-audio-${audio.itag}"
        val config = SabrConfig(
            info.serverAbrStreamingUrl, decode(info.videoPlaybackUstreamerConfig), audio.wireFormat(),
            decode(info.streamingPoToken), clientInfo(info), info.userAgent, { it }, durationMs = info.durationMs,
            reloadPlayer = { token ->
                val refreshed = YouTubeRepository.reloadSabrPlayback(info, token)
                refreshed.first to decode(refreshed.second)
            },
        )
        ModernSabrRegistry.put(ModernSabrStream(id, null, config, HttpBackend.playbackClient()))
        return ProgressiveMediaSource.Factory(ModernSabrDataSource.Factory(false))
            .createMediaSource(MediaItem.fromUri(Uri.parse("sabrmodern://$id/audio")))
    }

    private fun SabrFormat.wireFormat() = SabrMessages.Format(itag, lastModified, contentLength, xtags, audioTrackId)
    private fun clientInfo(info: SabrPlaybackInfo) = SabrMessages.ClientInfo(
        info.clientId, info.clientVersion, info.osName, info.osVersion, info.deviceMake, info.deviceModel,
    )
    private fun decode(value: String): ByteArray {
        val normalized = value.replace('-', '+').replace('_', '/')
        return Base64.decode(normalized + "=".repeat((4 - normalized.length % 4) % 4), Base64.DEFAULT)
    }
}
