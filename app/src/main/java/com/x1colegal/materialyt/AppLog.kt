package com.x1colegal.materialyt

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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

    fun exportToDownloads(context: Context): String {
        val source = logFile ?: File(context.filesDir, "app_diag.log")
        check(source.exists() && source.length() > 0L) { "No diagnostic logs recorded yet" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "MaterialYT-diag-$stamp.log"
        val snapshot = synchronized(this) { source.readBytes() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create log file in Downloads")
            resolver.openOutputStream(uri)?.use { it.write(snapshot) }
                ?: error("Could not open Downloads output stream")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Download/$fileName"
        }
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val dest = File(downloadsDir, fileName)
        dest.writeBytes(snapshot)
        return dest.absolutePath
    }
}
