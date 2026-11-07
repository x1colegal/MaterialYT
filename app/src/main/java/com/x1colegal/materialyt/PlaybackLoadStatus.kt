package com.x1colegal.materialyt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class PlaybackLoadMessage(val mediaId: String, val text: String)

object PlaybackLoadStatus {
    private val mutableMessage = MutableStateFlow<PlaybackLoadMessage?>(null)
    private val bufferingMedia = ConcurrentHashMap.newKeySet<String>()
    private val startedMedia = ConcurrentHashMap.newKeySet<String>()
    val message = mutableMessage.asStateFlow()

    fun beginInitialLoad(mediaId: String) {
        startedMedia -= mediaId
        bufferingMedia += mediaId
    }

    fun markPlaybackStarted(mediaId: String) {
        startedMedia += mediaId
        bufferingMedia -= mediaId
        clear(mediaId)
    }

    fun hasPlaybackStarted(mediaId: String): Boolean = mediaId in startedMedia

    fun show(mediaId: String, text: String) {
        mutableMessage.value = PlaybackLoadMessage(mediaId, text)
    }

    fun showSabrBackoff(mediaId: String, text: String) {
        val allowed = if (mediaId !in startedMedia) {
            true
        } else {
            mediaId in bufferingMedia && PlaybackPreferences.showSabrBackoffDuringBuffering
        }
        if (allowed) show(mediaId, text)
    }

    fun setBuffering(mediaId: String, buffering: Boolean) {
        if (buffering) {
            bufferingMedia += mediaId
        } else {
            bufferingMedia -= mediaId
            val current = mutableMessage.value
            if (current?.mediaId == mediaId && current.text.startsWith("SABR requested backoff")) {
                mutableMessage.value = null
            }
        }
    }

    fun clear(mediaId: String, expectedText: String? = null) {
        val current = mutableMessage.value
        if (current?.mediaId == mediaId && (expectedText == null || current.text == expectedText)) {
            mutableMessage.value = null
        }
    }
}
