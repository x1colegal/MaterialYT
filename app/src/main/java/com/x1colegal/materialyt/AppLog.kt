package com.x1colegal.materialyt

import android.util.Log

object AppLog {
    private const val TAG = "MaterialYTDiag"

    fun event(message: String) {
        Log.println(Log.ERROR, TAG, message.take(3500))
    }

    fun failure(area: String, error: Throwable) {
        Log.e(TAG, "$area failed: ${error.javaClass.simpleName}: ${error.message}", error)
    }
}
