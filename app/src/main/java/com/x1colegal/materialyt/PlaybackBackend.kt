package com.x1colegal.materialyt

import android.content.Context
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor

enum class PlaybackBackend(val label: String) {
    WEB_ONLY("SABR (Recommended)"),
    WEB_WITH_NEWPIPE_FALLBACK("SABR + NewPipe fallback"),
    NEWPIPE_ONLY("Force NewPipe"),
    NEWPIPE_WITH_WEB_FALLBACK("NewPipe + SABR fallback")
}

object PlaybackBackendPreferences {
    @Volatile
    var backend: PlaybackBackend = PlaybackBackend.WEB_ONLY
        private set

    fun initialize(context: Context) {
        val saved = context.getSharedPreferences("settings", 0)
            .getString("playback_backend", PlaybackBackend.WEB_ONLY.name)
        backend = runCatching { PlaybackBackend.valueOf(saved!!) }
            .getOrDefault(PlaybackBackend.WEB_ONLY)
        YoutubeStreamExtractor.setWebPoTokenFallbackEnabled(
            backend == PlaybackBackend.NEWPIPE_WITH_WEB_FALLBACK
        )
    }

    fun set(context: Context, value: PlaybackBackend) {
        backend = value
        YoutubeStreamExtractor.setWebPoTokenFallbackEnabled(
            value == PlaybackBackend.NEWPIPE_WITH_WEB_FALLBACK
        )
        context.getSharedPreferences("settings", 0).edit()
            .putString("playback_backend", value.name).apply()
    }

    fun useNewPipe(): Boolean = backend != PlaybackBackend.WEB_ONLY
        && backend != PlaybackBackend.WEB_WITH_NEWPIPE_FALLBACK
    fun useWebWhenNewPipeFails(): Boolean = backend != PlaybackBackend.NEWPIPE_ONLY
    fun useNewPipeWhenWebFails(): Boolean = backend == PlaybackBackend.WEB_WITH_NEWPIPE_FALLBACK
}
