package com.x1colegal.materialyt

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.conscrypt.Conscrypt
import java.security.Security
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

object HttpBackend {
    enum class Mode(val label: String) { HTTP_1_1("HTTP/1.1"), HTTP_2("HTTP/2") }

    private val PREFERRED_NAMED_GROUPS = arrayOf("X25519MLKEM768", "X25519", "secp256r1", "secp384r1")

    @Volatile private var conscryptInstalled = false

    private fun ensureConscryptInstalled() {
        if (conscryptInstalled) return
        synchronized(this) {
            if (conscryptInstalled) return
            runCatching {
                val provider = Conscrypt.newProvider()
                val existing = Security.getProvider(provider.name)
                if (existing == null || !Conscrypt.isConscrypt(existing)) {
                    Security.insertProviderAt(provider, 1)
                }
                System.setProperty("jdk.tls.namedGroups", PREFERRED_NAMED_GROUPS.joinToString(","))
                AppLog.event("Conscrypt TLS installed groups=${PREFERRED_NAMED_GROUPS.joinToString(",")}")
            }.onFailure { AppLog.failure("Conscrypt TLS provider install", it) }
            conscryptInstalled = true
        }
    }

    fun OkHttpClient.Builder.configurePostQuantumTls(): OkHttpClient.Builder {
        ensureConscryptInstalled()
        runCatching {
            val provider = Conscrypt.newProvider()
            val sslContext = SSLContext.getInstance("TLS", provider).apply { init(null, null, null) }
            val factory = sslContext.socketFactory
            Conscrypt.setNamedGroups(factory, PREFERRED_NAMED_GROUPS)
            sslSocketFactory(factory, Conscrypt.getDefaultX509TrustManager())
        }.onFailure { AppLog.failure("Conscrypt namedGroups setup", it) }
        return this
    }

    private lateinit var context: Context
    @Volatile private var client = buildClient(Mode.HTTP_2)
    var mode: Mode = Mode.HTTP_2
        private set

    fun initialize(value: Context) {
        ensureConscryptInstalled()
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
        val protocols = if (mode == Mode.HTTP_2) listOf(Protocol.HTTP_2, Protocol.HTTP_1_1) else listOf(Protocol.HTTP_1_1)
        return OkHttpClient.Builder()
            .configurePostQuantumTls()
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
                chain.proceed(builder.build())
            }.build()
    }

    fun playbackClient(): OkHttpClient = client
}
