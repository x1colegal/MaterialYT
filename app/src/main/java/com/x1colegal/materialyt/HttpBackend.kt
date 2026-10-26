package com.x1colegal.materialyt

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

object HttpBackend {
    enum class Mode(val label: String) { HTTP_1_0("HTTP/1.0"), HTTP_1_1("HTTP/1.1"), HTTP_2("HTTP/2") }

    private lateinit var context: Context
    @Volatile private var client = buildClient(Mode.HTTP_2)
    var mode: Mode = Mode.HTTP_2
        private set

    fun initialize(value: Context) {
        context = value.applicationContext
        val saved = context.getSharedPreferences("settings", 0).getString("http", Mode.HTTP_2.name)
        setMode(runCatching { Mode.valueOf(saved!!) }.getOrDefault(Mode.HTTP_2))
    }

    fun setMode(value: Mode) {
        mode = value
        client = buildClient(value)
        if (::context.isInitialized) context.getSharedPreferences("settings", 0).edit().putString("http", value.name).apply()
    }

    private fun buildClient(mode: Mode): OkHttpClient {
        // OkHttp does not emit HTTP/1.0 request lines. HTTP/1.0 mode therefore uses
        // HTTP/1.1 with connection reuse disabled, the closest safe compatibility behavior.
        val protocols = if (mode == Mode.HTTP_2) listOf(Protocol.HTTP_2, Protocol.HTTP_1_1) else listOf(Protocol.HTTP_1_1)
        return OkHttpClient.Builder()
            .protocols(protocols)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                val builder = req.newBuilder()
                val isYouTube = url.contains("youtube.com") || url.contains("googlevideo.com") || url.contains("youtubei")
                if (req.header("User-Agent").isNullOrBlank()) {
                    builder.header("User-Agent", if (isYouTube) YouTubeRepository.UA else "Mozilla/5.0 (Android) MaterialYT/1.0")
                }
                if (mode == Mode.HTTP_1_0) builder.header("Connection", "close")
                chain.proceed(builder.build())
            }.build()
    }

    fun playbackClient(): OkHttpClient = client
}
