package com.x1colegal.materialyt

import android.content.Context
import okhttp3.Call
import okhttp3.CipherSuite
import okhttp3.Connection
import okhttp3.ConnectionSpec
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.TlsVersion
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.BCSSLSocket
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import org.bouncycastle.tls.NamedGroup
import org.bouncycastle.tls.TlsContext
import java.security.KeyStore
import java.security.Security
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object HttpBackend {
    enum class Mode(val label: String) { HTTP_1_1("HTTP/1.1"), HTTP_2("HTTP/2") }

    private val PREFERRED_NAMED_GROUPS = arrayOf("X25519MLKEM768", "x25519", "secp256r1", "secp384r1")
    private val STRICT_AES_256_SPEC = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        .cipherSuites(
            CipherSuite.TLS_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        )
        .build()
    private val FALLBACK_TLS_SPEC = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        .cipherSuites(
            CipherSuite.TLS_AES_128_GCM_SHA256,
            CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        )
        .build()
    private val tlsEventListener = object : EventListener() {
        override fun connectionAcquired(call: Call, connection: Connection) {
            val handshake = connection.handshake() ?: return
            val bcSocket = connection.socket() as? BCSSLSocket
            val group = bcSocket?.let {
                runCatching {
                    val conn = it.connection ?: return@runCatching null
                    val m = conn.javaClass.getDeclaredMethod("getTlsContext").apply { isAccessible = true }
                    val tlsCtx = m.invoke(conn) as? TlsContext ?: return@runCatching null
                    val ng = tlsCtx.securityParametersConnection?.negotiatedGroup ?: -1
                    if (ng >= 0) NamedGroup.getName(ng) else null
                }.getOrNull()
            } ?: "unknown"
            val url = call.request().url.let { "${it.scheme}://${it.host}${it.encodedPath}" }
            AppLog.event(
                "TLS url=$url version=${handshake.tlsVersion.javaName} " +
                    "cipher=${handshake.cipherSuite.javaName} group=$group"
            )
        }
    }

    @Volatile private var tlsInstalled = false
    @Volatile private var systemTrustManager: X509TrustManager? = null
    @Volatile private var bcJsseProvider: BouncyCastleJsseProvider? = null

    private fun ensureTlsInstalled() {
        if (tlsInstalled) return
        synchronized(this) {
            if (tlsInstalled) return
            runCatching {
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as KeyStore?)
                systemTrustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
                Security.removeProvider("BC")
                Security.addProvider(BouncyCastleProvider())
                val jsse = BouncyCastleJsseProvider()
                Security.removeProvider(jsse.name)
                Security.addProvider(jsse)
                bcJsseProvider = jsse
                System.setProperty("jdk.tls.namedGroups", PREFERRED_NAMED_GROUPS.joinToString(","))
                AppLog.event("BouncyCastle TLS installed groups=${PREFERRED_NAMED_GROUPS.joinToString(",")}")
            }.onFailure { AppLog.failure("BouncyCastle TLS provider install", it) }
            tlsInstalled = true
        }
    }

    fun OkHttpClient.Builder.configurePostQuantumTls(): OkHttpClient.Builder {
        ensureTlsInstalled()
        retryOnConnectionFailure(true)
        connectionSpecs(listOf(STRICT_AES_256_SPEC, FALLBACK_TLS_SPEC, ConnectionSpec.CLEARTEXT))
        eventListener(tlsEventListener)
        runCatching {
            val provider = bcJsseProvider ?: return@runCatching
            val trustManager = systemTrustManager ?: return@runCatching
            val sslContext = SSLContext.getInstance("TLSv1.3", provider).apply {
                init(null, arrayOf(trustManager), null)
            }
            sslSocketFactory(sslContext.socketFactory, trustManager)
        }.onFailure { AppLog.failure("BouncyCastle SSLContext setup", it) }
        return this
    }

    private lateinit var context: Context
    @Volatile private var client = buildClient(Mode.HTTP_2)
    var mode: Mode = Mode.HTTP_2
        private set

    fun initialize(value: Context) {
        ensureTlsInstalled()
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
