package com.x1colegal.materialyt

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class WebPoTokenSession(
    val visitorData: String,
    val playerRequestPoToken: String,
    val streamingDataPoToken: String
)

/** Standalone WEB PoToken session used by native SABR playback. */
object WebPoTokenProvider {
    private val lock = Any()
    private var runtime: BotGuardRuntime? = null
    private var visitorData: String? = null
    private var sessionKey: String? = null
    private var preparingSessionKey: String? = null
    private val coldStartRandom = SecureRandom()

    private fun sessionKey(origin: String, userAgent: String, visitor: String, cookies: String): String {
        val stableAuthNames = setOf(
            "SID", "HSID", "SSID", "APISID", "SAPISID",
            "__Secure-1PSID", "__Secure-3PSID",
            "__Secure-1PAPISID", "__Secure-3PAPISID",
        )
        val authIdentity = cookies.split(';').map { it.trim() }
            .mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) null else entry.substring(0, separator) to entry.substring(separator + 1)
            }
            .filter { it.first in stableAuthNames }
            .sortedBy { it.first }
            .joinToString(";") { "${it.first}=${it.second}" }
        return "$origin\u0000$userAgent\u0000$visitor\u0000$authIdentity"
    }

    fun reset() = synchronized(lock) {
        runtime?.close()
        runtime = null
        visitorData = null
        sessionKey = null
        preparingSessionKey = null
    }

    /**
     * Returns a real content-bound proof when the page minter is already ready. Otherwise returns
     * the same cold-start token format used by YouTube WEB and prepares BotGuard in parallel.
     */
    fun getOrColdStart(
        videoId: String,
        requestedVisitorData: String,
        streamingContentBinding: String,
        origin: String = "https://www.youtube.com",
        userAgent: String = YouTubeRepository.WEB_PLAYER_UA,
    ): WebPoTokenSession {
        require(requestedVisitorData.isNotBlank()) { "YouTube returned empty visitorData" }
        val cookies = YouTubeRepository.cookies(origin)
        val requestedSessionKey = sessionKey(origin, userAgent, requestedVisitorData, cookies)
        val readyRuntime = synchronized(lock) {
            runtime?.takeIf {
                sessionKey == requestedSessionKey && !it.expired() && it.readyNow()
            }
        }
        if (readyRuntime != null) {
            return get(videoId, requestedVisitorData, streamingContentBinding, origin, userAgent)
        }

        val shouldPrepare = synchronized(lock) {
            if (preparingSessionKey == requestedSessionKey) false
            else {
                preparingSessionKey = requestedSessionKey
                true
            }
        }
        if (shouldPrepare) {
            Thread({
                runCatching {
                    get(videoId, requestedVisitorData, streamingContentBinding, origin, userAgent)
                }.onSuccess {
                    AppLog.event("WEB PoToken definitive minter prepared after cold start video=$videoId")
                }.onFailure {
                    AppLog.failure("WEB PoToken cold-start background preparation video=$videoId", it)
                }
                synchronized(lock) {
                    if (preparingSessionKey == requestedSessionKey) preparingSessionKey = null
                }
            }, "materialyt-web-pot-cold-$videoId").apply { isDaemon = true; start() }
        }

        val playerToken = createColdStartToken(videoId)
        val streamingToken = if (streamingContentBinding == videoId) playerToken
            else createColdStartToken(streamingContentBinding)
        AppLog.event(
            "WEB PoToken cold start video=$videoId player=${playerToken.length} " +
                "streaming=${streamingToken.length} sameBinding=${streamingContentBinding == videoId}"
        )
        return WebPoTokenSession(requestedVisitorData, playerToken, streamingToken)
    }

    /** Returns a definitive proof only when this exact browser/minter session is already ready. */
    fun getIfReady(
        videoId: String,
        requestedVisitorData: String,
        streamingContentBinding: String,
        origin: String = "https://www.youtube.com",
        userAgent: String = YouTubeRepository.WEB_PLAYER_UA,
    ): WebPoTokenSession? {
        val cookies = YouTubeRepository.cookies(origin)
        val requestedSessionKey = sessionKey(origin, userAgent, requestedVisitorData, cookies)
        val available = synchronized(lock) {
            runtime != null && sessionKey == requestedSessionKey && runtime?.expired() == false &&
                runtime?.readyNow() == true
        }
        return if (available) get(
            videoId,
            requestedVisitorData,
            streamingContentBinding,
            origin,
            userAgent,
        ) else null
    }

    /** Port of BgUtils createColdStartToken(). */
    private fun createColdStartToken(contentBinding: String, clientState: Int = 1): String {
        val binding = contentBinding.toByteArray(Charsets.UTF_8)
        val timestamp = System.currentTimeMillis() / 1_000L
        val key0 = coldStartRandom.nextInt(256)
        val key1 = coldStartRandom.nextInt(256)
        val header = byteArrayOf(
            key0.toByte(), key1.toByte(), 0, clientState.toByte(),
            (timestamp ushr 24).toByte(), (timestamp ushr 16).toByte(),
            (timestamp ushr 8).toByte(), timestamp.toByte(),
        )
        require(header.size + binding.size <= 255) { "Cold-start PoToken binding is too long" }
        val packet = ByteArray(2 + header.size + binding.size)
        packet[0] = 34
        packet[1] = (header.size + binding.size).toByte()
        header.copyInto(packet, 2)
        binding.copyInto(packet, 2 + header.size)
        for (index in 2 until header.size + binding.size) {
            val packetIndex = 2 + index
            packet[packetIndex] = (packet[packetIndex].toInt() xor packet[2 + index % 2].toInt()).toByte()
        }
        return Base64.encodeToString(packet, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    fun get(
        videoId: String,
        requestedVisitorData: String,
        streamingContentBinding: String,
        origin: String = "https://www.youtube.com",
        userAgent: String = YouTubeRepository.WEB_PLAYER_UA,
        forceFreshChallenge: Boolean = false,
    ): WebPoTokenSession = synchronized(lock) {
        require(requestedVisitorData.isNotBlank()) { "YouTube returned empty visitorData" }
        val cookies = YouTubeRepository.cookies(origin)
        val requestedSessionKey = sessionKey(origin, userAgent, requestedVisitorData, cookies)
        if (forceFreshChallenge) {
            // STREAM_PROTECTION_STATUS=ATTESTATION_REQUIRED means the server rejected the proof
            // minted by this BotGuard challenge. Minting the same binding with the same minter is
            // not a renewal: it reproduces the rejected proof. Rebuild the runtime so GenerateIT
            // supplies fresh token material before the active SABR session retries.
            runtime?.close()
            runtime = null
            visitorData = null
            sessionKey = null
            AppLog.event("WEB PoToken invalidated for fresh stream-protection challenge video=$videoId")
        }
        for (attempt in 0..1) {
            try {
                var current = runtime
                if (current == null || current.expired() || sessionKey != requestedSessionKey) {
                    current?.close()
                    visitorData = requestedVisitorData
                    sessionKey = requestedSessionKey
                    current = BotGuardRuntime(
                        MaterialYtApp.instance.applicationContext,
                        origin,
                        userAgent,
                        requestedVisitorData,
                        cookies,
                    )
                    current.initialize()
                    runtime = current
                }
                // Player tokens are always content-bound to the video. GVS/SABR binding is selected from
                // the current ytcfg experiment: video id when content tokens are enabled, otherwise the
                // signed-in Data Sync ID (or visitorData for a guest session).
                val active = current ?: error("WEB PoToken runtime was not created")
                val playerToken = active.mint(videoId)
                val streamingToken = if (streamingContentBinding == videoId) playerToken
                    else active.mint(streamingContentBinding)
                AppLog.event(
                    "WEB PoToken ready visitor=${requestedVisitorData.length} player=${playerToken.length} " +
                        "streaming=${streamingToken.length} sameBinding=${streamingContentBinding == videoId}"
                )
                return@synchronized WebPoTokenSession(requestedVisitorData, playerToken, streamingToken)
            } catch (error: Throwable) {
                runtime?.close()
                runtime = null
                visitorData = null
                sessionKey = null
                if (error is InterruptedException || Thread.currentThread().isInterrupted) throw error
                if (attempt > 0) throw error
                AppLog.event("WEB PoToken runtime retry video=$videoId error=${error.javaClass.simpleName}")
            }
        }
        error("WEB PoToken runtime retry exhausted")
    }
}

private class BotGuardRuntime(
    private val context: Context,
    private val origin: String,
    private val userAgent: String,
    private val visitorData: String,
    private val cookies: String,
) {
    private data class Challenge(val data: String, val ytcfg: String?)

    private val main = Handler(Looper.getMainLooper())
    private val ready = CompletableFuture<Unit>()
    private val mintResults = mutableMapOf<String, CompletableFuture<String>>()
    private var webView: WebView? = null
    private var expiresAt = Instant.EPOCH

    fun initialize() {
        main.post {
            val view = WebView(context)
            webView = view
            view.settings.javaScriptEnabled = true
            view.settings.blockNetworkLoads = true
            view.settings.userAgentString = userAgent
            view.webChromeClient = WebChromeClient()
            view.addJavascriptInterface(Bridge(), "MaterialYtPoToken")
            val html = context.assets.open("po_token.html").bufferedReader().use { it.readText() }
                .replace("</script>", "\nMaterialYtPoToken.pageReady()</script>")
            view.loadDataWithBaseURL(origin, html, "text/html", "utf-8", null)
        }
        ready.get(30, TimeUnit.SECONDS)
    }

    fun mint(identifier: String): String {
        val result = CompletableFuture<String>()
        synchronized(mintResults) { mintResults[identifier] = result }
        val quoted = JSONObject.quote(identifier)
        val bytes = identifier.toByteArray().joinToString(",") { (it.toInt() and 0xff).toString() }
        main.post { webView?.evaluateJavascript("""
            try {
              const id=$quoted;
              Promise.resolve(obtainPoToken(new Uint8Array([$bytes])))
                .then(out=>MaterialYtPoToken.token(id,Array.from(out).join(',')))
                .catch(e=>MaterialYtPoToken.error(id,String(e)));
            } catch(e) { MaterialYtPoToken.error($quoted,String(e)); }
        """.trimIndent(), null) }
        return result.get(20, TimeUnit.SECONDS)
    }

    fun expired(): Boolean = Instant.now().isAfter(expiresAt)

    fun readyNow(): Boolean = ready.isDone && !ready.isCompletedExceptionally

    fun close() = main.post {
        webView?.apply { loadUrl("about:blank"); removeAllViews(); destroy() }
        webView = null
    }

    private inner class Bridge {
        @JavascriptInterface fun pageReady() = Thread {
            runCatching {
                val challenge = homepageChallenge() ?: legacyChallenge()
                val config = challenge.ytcfg?.let { "window.yt={config_:${it}};" }.orEmpty()
                main.post { webView?.evaluateJavascript("""
                    try { $config data=${challenge.data}; runBotGuard(data).then(r=>{
                      webPoSignalOutput=r.webPoSignalOutput;
                      MaterialYtPoToken.botguard(r.botguardResponse);
                    }).catch(e=>MaterialYtPoToken.initError(String(e)));
                    } catch(e) { MaterialYtPoToken.initError(String(e)); }
                """.trimIndent(), null) }
            }.onFailure(ready::completeExceptionally)
        }.start()

        @JavascriptInterface fun botguard(response: String) = Thread {
            runCatching {
                val rawGenerated = request(GENERATE_URL, JSONArray().put(REQUEST_KEY).put(response).toString()).trim()
                val (encodedToken, ttlSeconds) = if (rawGenerated.startsWith("{")) {
                    val generated = JSONObject(rawGenerated)
                    generated.getString("integrityToken") to generated.optLong("estimatedTtlSecs", 3600)
                } else {
                    val generated = JSONArray(rawGenerated)
                    generated.getString(0) to generated.optLong(1, 3600)
                }
                val token = decodeBase64(encodedToken).joinToString(",") { (it.toInt() and 0xff).toString() }
                expiresAt = Instant.now().plusSeconds((ttlSeconds - 600).coerceAtLeast(60))
                main.post { webView?.evaluateJavascript("""
                    try {
                      getMinter=webPoSignalOutput[0];
                      Promise.resolve(getMinter(new Uint8Array([$token]))).then(callback=>{
                        if (typeof callback !== 'function') throw Error('PoToken mintCallback is invalid');
                        mintCallback=callback;
                        webPoSignalOutput=null; getMinter=null;
                        MaterialYtPoToken.initialized();
                      }).catch(e=>MaterialYtPoToken.initError(String(e)));
                    } catch(e) { MaterialYtPoToken.initError(String(e)); }
                """.trimIndent(), null) }
            }.onFailure(ready::completeExceptionally)
        }.start()

        @JavascriptInterface fun initialized() { ready.complete(Unit) }

        @JavascriptInterface fun token(identifier: String, bytes: String) {
            // BgUtils/SmartTube keep standard Base64 padding and only switch to the URL-safe
            // alphabet. Removing '=' changes the WebPo token accepted by the player endpoint.
            val encoded = Base64.encodeToString(
                bytes.split(',').filter { it.isNotBlank() }.map { it.toInt().toByte() }.toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP
            )
            synchronized(mintResults) { mintResults.remove(identifier) }?.complete(encoded)
        }

        @JavascriptInterface fun error(identifier: String, message: String) =
            synchronized(mintResults) { mintResults.remove(identifier) }?.completeExceptionally(IllegalStateException(message)) ?: Unit

        @JavascriptInterface fun initError(message: String) { ready.completeExceptionally(IllegalStateException(message)) }
    }

    private fun homepageChallenge(): Challenge? = runCatching {
        val html = request(origin, null)
        val ytcfg = Regex("""ytcfg\.set\((\{.+?\})\);""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
        val payload = Regex("""window\.ytAtN\(\s*(\{[\s\S]*?\})\s*\)""")
            .find(html)?.groupValues?.get(1) ?: return@runCatching null
        val raw = parseLooseJson(payload).optString("R")
            .takeIf { it.contains("bgChallenge") && it.contains("program") && it.contains("interpreterUrl") }
            ?: return@runCatching null
        AppLog.event("WEB PoToken using homepage challenge eventId=${ytcfg?.contains("EVENT_ID") == true}")
        Challenge(parseDescrambledChallenge(raw), ytcfg)
    }.onFailure { AppLog.failure("WEB PoToken homepage challenge", it) }.getOrNull()

    private fun legacyChallenge(): Challenge {
        AppLog.event("WEB PoToken using legacy Create challenge")
        return Challenge(parseChallenge(request(CREATE_URL, "[\"$REQUEST_KEY\"]")), null)
    }

    private fun parseDescrambledChallenge(raw: String): String {
        val bg = JSONObject(raw).getJSONObject("bgChallenge")
        val interpreterUrl = bg.getJSONObject("interpreterUrl")
            .getString("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue")
        val absoluteUrl = if (interpreterUrl.startsWith("//")) "https:$interpreterUrl" else interpreterUrl
        val interpreter = request(absoluteUrl, null)
        return JSONObject()
            .put("interpreterJavascript", JSONObject()
                .put("privateDoNotAccessOrElseSafeScriptWrappedValue", interpreter)
                .put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", interpreterUrl))
            .put("interpreterHash", bg.optString("interpreterHash"))
            .put("program", bg.getString("program"))
            .put("globalName", bg.getString("globalName"))
            .put("clientExperimentsStateBlob", bg.optString("clientExperimentsStateBlob"))
            .toString()
    }

    private fun parseLooseJson(value: String): JSONObject {
        var normalized = Regex("""\\x([0-9A-Fa-f]{2})""").replace(value) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        normalized = Regex(""",\s*([}\]])""").replace(normalized, "\$1")
        normalized = Regex("""'((?:[^'\\]|\\[\s\S])*)'""").replace(normalized) {
            JSONObject.quote(it.groupValues[1].replace("\\'", "'"))
        }
        normalized = Regex("""([{,]\s*)([a-zA-Z0-9_\$]+)\s*:""").replace(normalized, "\$1\"\$2\":")
        return JSONObject(normalized)
    }

    private fun request(url: String, body: String?): String {
        val builder = okhttp3.Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", if (body == null) "*/*" else "application/json")
            .header("Accept-Language", "en-US,en;q=0.7")
            .header("X-Goog-Visitor-Id", visitorData)
        if (cookies.isNotBlank()) builder.header("Cookie", cookies)
        if (body != null) builder.post(body.toRequestBody("application/json+protobuf".toMediaType()))
            .header("x-goog-api-key", GOOGLE_API_KEY).header("x-user-agent", "grpc-web-javascript/0.1")
        val call = builder.build()
        return with(HttpBackend) {
            okhttp3.OkHttpClient.Builder().configurePostQuantumTls().build()
        }.newCall(call).execute().use {
            check(it.isSuccessful) { "BotGuard HTTP ${it.code}" }
            it.body.string()
        }
    }

    private fun parseChallenge(raw: String): String {
        val outer = JSONArray(raw)
        val challenge = if (outer.length() > 1 && outer.opt(1) is String) JSONArray(descramble(outer.getString(1))) else outer.getJSONArray(0)
        fun firstString(array: JSONArray?): String? = array?.let { a -> (0 until a.length()).firstNotNullOfOrNull { a.opt(it) as? String } }
        return JSONObject().put("messageId", challenge.getString(0))
            .put("interpreterJavascript", JSONObject()
                .put("privateDoNotAccessOrElseSafeScriptWrappedValue", firstString(challenge.optJSONArray(1)))
                .put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", firstString(challenge.optJSONArray(2))))
            .put("interpreterHash", challenge.getString(3)).put("program", challenge.getString(4))
            .put("globalName", challenge.getString(5)).put("clientExperimentsStateBlob", challenge.getString(7)).toString()
    }

    private fun descramble(value: String): String = decodeBase64(value).map { (it + 97).toByte() }.toByteArray().decodeToString()
    private fun decodeBase64(value: String): ByteArray = Base64.decode(value.replace('-', '+').replace('_', '/').replace('.', '='), Base64.DEFAULT)

    companion object {
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val CREATE_URL = "https://www.youtube.com/api/jnn/v1/Create"
        private const val GENERATE_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT"
    }
}
