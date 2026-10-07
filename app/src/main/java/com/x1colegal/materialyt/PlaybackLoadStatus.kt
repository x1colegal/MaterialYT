package com.x1colegal.materialyt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackLoadMessage(val mediaId: String, val text: String)

object PlaybackLoadStatus {
    private val mutableMessage = MutableStateFlow<PlaybackLoadMessage?>(null)
    val message = mutableMessage.asStateFlow()

    fun show(mediaId: String, text: String) {
        mutableMessage.value = PlaybackLoadMessage(mediaId, text)
    }

    fun clear(mediaId: String) {
        if (mutableMessage.value?.mediaId == mediaId) mutableMessage.value = null
    }
}
