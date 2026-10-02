package com.x1colegal.materialyt

import android.content.Context
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.util.concurrent.TimeUnit

object HttpBackend : Downloader() {
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

    override fun execute(request: Request): Response {
        val url = request.url()
        val builder = okhttp3.Request.Builder().url(url)
        request.headers().forEach { (name, values) -> values.forEach { builder.addHeader(name, it) } }

        val isYouTube = url.contains("youtube.com") || url.contains("googlevideo.com") || url.contains("youtubei")
        if (isYouTube) {
            val origin = if (url.contains("music.youtube.com")) "https://music.youtube.com" else "https://www.youtube.com"
            val accountCookies = YouTubeRepository.cookies(origin)
            
            // Only inject cookies globally for googlevideo.com (ExoPlayer). 
            // YouTubeRepository handles its own cookies, and injecting into youtubei ruins NewPipe's anonymous requests.
            if (url.contains("googlevideo.com") && accountCookies.isNotBlank()) {
                val existingCookie = builder.build().header("Cookie")
                val mergedCookie = if (existingCookie.isNullOrBlank()) accountCookies else "$existingCookie; $accountCookies"
                builder.header("Cookie", mergedCookie)
            }
            
            if (url.contains("googlevideo.com")) {
                YouTubeRepository.authorization(origin)?.let { builder.header("Authorization", it) }
                builder.header("Origin", origin)
                builder.header("X-Origin", origin)
                builder.header("X-Goog-AuthUser", "0")
                builder.header("X-Youtube-Bootstrap-Logged-In", YouTubeRepository.signedIn().toString())
            }

            if (builder.build().header("User-Agent").isNullOrBlank()) {
                builder.header("User-Agent", YouTubeRepository.UA)
            }
        }

        val bytes = request.dataToSend()
        val body = bytes?.toRequestBody(builder.build().header("Content-Type")?.toMediaTypeOrNull())
        builder.method(request.httpMethod(), if (request.httpMethod() in listOf("GET", "HEAD")) null else body ?: ByteArray(0).toRequestBody())
        client.newCall(builder.build()).execute().use { response ->
            val headers = response.headers.toMultimap()
            return Response(response.code, response.message, headers, response.body?.string(), response.request.url.toString())
        }
    }
}
