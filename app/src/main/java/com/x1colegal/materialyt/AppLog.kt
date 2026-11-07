package com.x1colegal.materialyt

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val TAG = "MaterialYTDiag"
    private var logFile: File? = null
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        runCatching {
            val file = File(context.filesDir, "app_diag.log")
            if (file.length() > 5 * 1024 * 1024) {
                file.delete()
            }
            logFile = file
        }
    }

    fun event(message: String) {
        val text = message.take(3500)
        Log.println(Log.ERROR, TAG, text)
        writeToFile("EVENT", text)
    }

    fun failure(area: String, error: Throwable) {
        val msg = "$area failed: ${error.javaClass.simpleName}: ${error.message}"
        Log.e(TAG, msg, error)
        val stack = Log.getStackTraceString(error)
        writeToFile("FAILURE", "$msg\n$stack")
    }

    private fun writeToFile(level: String, text: String) {
        val file = logFile ?: return
        runCatching {
            synchronized(this) {
                val timestamp = timeFormat.format(Date())
                FileWriter(file, true).use { writer ->
                    writer.write("[$timestamp] [$level] $text\n")
                }
            }
        }
    }
}
