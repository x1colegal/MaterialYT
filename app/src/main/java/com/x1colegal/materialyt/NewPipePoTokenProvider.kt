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
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class WebPoTokenSession(
    val visitorData: String,
    val playerRequestPoToken: String,
    val streamingDataPoToken: String
)

/** Standalone WEB PoToken session. It has no dependency on NewPipe's player implementation. */
object WebPoTokenProvider {
    private val lock = Any()
    private var runtime: BotGuardRuntime? = null
    private var visitorData: String? = null
    private var playerRequestToken: String? = null
    private var sessionKey: String? = null

    fun reset() = synchronized(lock) {
        runtime?.close()
        runtime = null
        visitorData = null
        playerRequestToken = null
        sessionKey = null
    }

    fun get(
        videoId: String,
        requestedVisitorData: String,
        origin: String = "https://www.youtube.com",
        userAgent: String = YouTubeRepository.WEB_PLAYER_UA,
    ): WebPoTokenSession = synchronized(lock) {
        require(requestedVisitorData.isNotBlank()) { "YouTube returned empty visitorData" }
        val cookies = YouTubeRepository.cookies(origin)
        val requestedSessionKey = "$origin\u0000$userAgent\u0000$requestedVisitorData\u0000$cookies"
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
            // The visitor-bound token authenticates /player and the SABR streamerContext.
            // It must be minted before the per-video streaming token on this runtime.
            playerRequestToken = current.mint(requestedVisitorData)
            runtime = current
        }
        // The video-bound token belongs on the media/SABR URL as the pot query parameter.
        val streamingToken = current!!.mint(videoId)
        AppLog.event("WEB PoToken ready visitor=${requestedVisitorData.length} player=${playerRequestToken?.length ?: 0} streaming=${streamingToken.length}")
        WebPoTokenSession(requestedVisitorData, playerRequestToken!!, streamingToken)
    }
}

/** Adapter used only when the explicitly selected NewPipe backend asks for a PoToken. */
object NewPipePoTokenProvider : PoTokenProvider {
    override fun getWebClientPoToken(videoId: String): PoTokenResult? = runCatching {
        val visitor = YouTubeRepository.standaloneWebVisitorData()
        val token = WebPoTokenProvider.get(videoId, visitor)
        PoTokenResult(token.visitorData, token.playerRequestPoToken, token.streamingDataPoToken)
    }.onFailure { AppLog.failure("NewPipe PoToken adapter", it) }.getOrNull()

    fun reset() {
        WebPoTokenProvider.reset()
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
              const id=$quoted, out=obtainPoToken(new Uint8Array([$bytes]));
              MaterialYtPoToken.token(id,Array.from(out).join(','));
            } catch(e) { MaterialYtPoToken.error($quoted,String(e)); }
        """.trimIndent(), null) }
        return result.get(20, TimeUnit.SECONDS)
    }

    fun expired(): Boolean = Instant.now().isAfter(expiresAt)

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
                val generated = JSONArray(request(GENERATE_URL, JSONArray().put(REQUEST_KEY).put(response).toString()))
                val token = decodeBase64(generated.getString(0)).joinToString(",") { (it.toInt() and 0xff).toString() }
                expiresAt = Instant.now().plusSeconds((generated.optLong(1, 3600) - 600).coerceAtLeast(60))
                main.post { webView?.evaluateJavascript("""
                    try {
                      getMinter=webPoSignalOutput[0];
                      mintCallback=getMinter(new Uint8Array([$token]));
                      if (typeof mintCallback === 'undefined') throw Error('PoToken mintCallback is undefined');
                      webPoSignalOutput=null; getMinter=null;
                      MaterialYtPoToken.initialized();
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
        AppLog.event("NewPipe PoToken using homepage challenge eventId=${ytcfg?.contains("EVENT_ID") == true}")
        Challenge(parseDescrambledChallenge(raw), ytcfg)
    }.onFailure { AppLog.failure("NewPipe PoToken homepage challenge", it) }.getOrNull()

    private fun legacyChallenge(): Challenge {
        AppLog.event("NewPipe PoToken using legacy Create challenge")
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
        return okhttp3.OkHttpClient().newCall(call).execute().use {
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
