package com.x1colegal.materialyt

import android.content.Context
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector

enum class DecoderMode(val label: String) { AUTO("Auto"), HARDWARE("Force HW"), SOFTWARE("Force SW") }

object PlaybackPreferences {
    @Volatile var videoDecoderMode = DecoderMode.AUTO
    @Volatile var audioDecoderMode = DecoderMode.AUTO
}

object PlayerFactory {
    fun bufferedPlayer(context: Context): ExoPlayer {
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(PlaybackPreferences.videoDecoderMode == DecoderMode.AUTO || PlaybackPreferences.audioDecoderMode == DecoderMode.AUTO)
            .setMediaCodecSelector(MediaCodecSelector { mimeType, secure, tunneling ->
                val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                val mode = if (mimeType.startsWith("audio/")) PlaybackPreferences.audioDecoderMode else PlaybackPreferences.videoDecoderMode
                when (mode) {
                    DecoderMode.AUTO -> decoders.sortedBy { it.softwareOnly }
                    DecoderMode.HARDWARE -> decoders.filter { it.hardwareAccelerated && !it.softwareOnly }
                    DecoderMode.SOFTWARE -> decoders.filter { it.softwareOnly || !it.hardwareAccelerated }
                }
            })
        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(60_000, 180_000, 750, 1_500)
                .setTargetBufferBytes(192 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build())
            .build()
    }
}
