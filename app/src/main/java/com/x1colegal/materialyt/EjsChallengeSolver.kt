package com.x1colegal.materialyt

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs the pinned yt-dlp/ejs solver in Android's local JavaScript runtime. */
object EjsChallengeSolver {
    private val main = Handler(Looper.getMainLooper())
    private val ready = CountDownLatch(1)
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    fun initialize(context: Context) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val lib = context.assets.open("yt.solver.lib.min.js").bufferedReader().use { it.readText() }
        val core = context.assets.open("yt.solver.core.min.js").bufferedReader().use { it.readText() }
        webView = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { ready.countDown() }
            }
            loadDataWithBaseURL("https://www.youtube.com/", "<html><body><script>$lib</script><script>$core</script></body></html>", "text/html", "UTF-8", null)
        }
    }

    fun solveN(playerJavaScript: String, challenge: String): String {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(ready.await(15, TimeUnit.SECONDS)) { "EJS runtime did not initialize" }
        val input = JSONObject()
            .put("type", "player")
            .put("player", playerJavaScript)
            .put("output_preprocessed", false)
            .put("requests", JSONArray().put(JSONObject().put("type", "n").put("challenges", JSONArray().put(challenge))))
        val done = CountDownLatch(1)
        var callbackValue: String? = null
        main.post {
            webView.evaluateJavascript("JSON.stringify(jsc(${input}))") { value -> callbackValue = value; done.countDown() }
        }
        check(done.await(30, TimeUnit.SECONDS)) { "EJS n challenge timed out" }
        val encoded = callbackValue ?: error("EJS returned no result")
        val decoded = JSONTokener(encoded).nextValue() as? String ?: error("EJS returned invalid JSON")
        val response = JSONObject(decoded).getJSONArray("responses").getJSONObject(0)
        if (response.optString("type") != "result") error(response.optString("error", "EJS could not solve n challenge"))
        return response.getJSONObject("data").getString(challenge)
    }
}
