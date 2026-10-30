package com.x1colegal.materialyt

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs YouTube's current playback-attestation module and submits its proof to the ATR URL. */
object WebStreamAttestationProvider {
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MaterialYT-WEB-Attestation").apply { isDaemon = true }
    }
    private var runtime: Runtime? = null
    private var sessionKey: String? = null

    fun prepareAsync(videoId: String, origin: String, userAgent: String) {
        executor.execute {
            runCatching {
                synchronized(lock) { runtime(origin, userAgent, videoId) }
            }.onFailure { AppLog.failure("WEB playback attestation preparation video=$videoId", it) }
        }
    }

    fun attest(videoId: String, cpn: String, atrUrl: String, origin: String, userAgent: String) {
        if (videoId.isBlank() || cpn.isBlank() || atrUrl.isBlank()) return
        synchronized(lock) {
            runtime(origin, userAgent, videoId).attest(videoId, cpn, atrUrl)
        }
    }

    private fun runtime(origin: String, userAgent: String, videoId: String): Runtime {
        val key = "$origin\u0000$userAgent\u0000${YouTubeRepository.cookies(origin)}"
        var current = runtime
        if (current == null || sessionKey != key) {
            current?.close()
            current = Runtime(origin, userAgent, videoId)
            current.initialize()
            runtime = current
            sessionKey = key
        }
        return current ?: error("WEB playback attestation runtime was not created")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private class Runtime(
        private val origin: String,
        private val userAgent: String,
        private val initialVideoId: String,
    ) {
        private val main = Handler(Looper.getMainLooper())
        private val ready = CompletableFuture<Unit>()
        private val results = mutableMapOf<String, CompletableFuture<Unit>>()
        private var view: WebView? = null

        fun initialize() {
            main.post {
                val webView = WebView(MaterialYtApp.instance.applicationContext)
                view = webView
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                // YouTube loads attmp lazily when playback begins. This hidden, muted
                // player has no user gesture, so autoplay must be allowed here.
                webView.settings.mediaPlaybackRequiresUserGesture = false
                webView.settings.userAgentString = userAgent
                webView.webChromeClient = WebChromeClient()
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url
                        val mediaRequest = url.host?.endsWith(".googlevideo.com", ignoreCase = true) == true ||
                            url.path?.contains("/videoplayback", ignoreCase = true) == true
                        if (!mediaRequest) return super.shouldInterceptRequest(view, request)
                        // The attestation module is activated by the player's playback attempt,
                        // but it does not need the media bytes. Returning an empty response keeps
                        // the real watch-page runtime while making pre-roll/video audio impossible.
                        return WebResourceResponse(
                            "application/octet-stream",
                            null,
                            204,
                            "No Content",
                            mapOf("Cache-Control" to "no-store"),
                            ByteArrayInputStream(ByteArray(0)),
                        )
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(
                            """
                            try {
                              const player=document.getElementById('movie_player');
                              if (player) {
                                if (typeof player.setVolume === 'function') player.setVolume(0);
                                if (typeof player.mute === 'function') player.mute();
                                if (typeof player.playVideo === 'function') player.playVideo();
                              }
                            } catch (_) {}
                            (function waitForAttestation(remaining) {
                              const modern=window.attmp && typeof window.attmp.s === 'function';
                              const legacy=window.yt && window.yt.aba && typeof window.yt.aba.att === 'function';
                              if (modern || legacy) {
                                try {
                                  const player=document.getElementById('movie_player');
                                  if (player && typeof player.pauseVideo === 'function') player.pauseVideo();
                                } catch (_) {}
                                MaterialYtAttestation.ready();
                              } else if (remaining > 0) {
                                setTimeout(function(){ waitForAttestation(remaining - 1); }, 100);
                              } else {
                                MaterialYtAttestation.initError('YouTube attestation module was not loaded');
                              }
                            })(80);
                            """.trimIndent(), null
                        )
                    }
                }
                webView.addJavascriptInterface(Bridge(), "MaterialYtAttestation")
                // Keep the real watch page stopped until the script above has muted its player.
                // With autoplay enabled in WebSettings, loading a plain /watch URL can otherwise
                // leak a fraction of a pre-roll before onPageFinished gets a chance to call mute().
                webView.loadUrl("$origin/watch?v=$initialVideoId&hl=en&autoplay=0&mute=1&playsinline=1")
            }
            ready.get(12, TimeUnit.SECONDS)
        }

        fun attest(videoId: String, cpn: String, atrUrl: String) {
            val id = "$videoId:$cpn"
            val result = CompletableFuture<Unit>()
            synchronized(results) { results[id] = result }
            val jsId = JSONObject.quote(id)
            val jsVideo = JSONObject.quote(videoId)
            val jsCpn = JSONObject.quote(cpn)
            val jsAtr = JSONObject.quote(atrUrl)
            main.post {
                view?.evaluateJavascript(
                    """
                    (async function() {
                      const requestId=$jsId;
                      try {
                        const args=['ENGAGEMENT_TYPE_PLAYBACK', {
                          cpn:$jsCpn,
                          encryptedVideoId:$jsVideo
                        }, 30000];
                        const modern=window.attmp && typeof window.attmp.s === 'function';
                        const legacy=window.yt && window.yt.aba && typeof window.yt.aba.att === 'function';
                        if (!modern && !legacy) throw new Error('YouTube attestation API disappeared');
                        const proof=modern
                          ? await window.attmp.s(args[0], args[1], args[2])
                          : await window.yt.aba.att(args[0], args[1], args[2]);
                        if (!proof || !proof.challenge) throw new Error('YouTube returned no playback attestation challenge');
                        const fields=[proof.challenge];
                        if (proof.error) fields.push('r1c=' + encodeURIComponent(String(proof.error)));
                        else if (proof.webResponse) fields.push('r1a=' + encodeURIComponent(String(proof.webResponse)));
                        if (proof.adblockReporting && proof.adblockReporting.reportingStatus !== undefined)
                          fields.push('r6a=' + encodeURIComponent(String(proof.adblockReporting.reportingStatus)));
                        if (proof.adblockReporting && proof.adblockReporting.broadSpectrumDetectionResult !== undefined)
                          fields.push('r6b=' + encodeURIComponent(String(proof.adblockReporting.broadSpectrumDetectionResult)));
                        const separator=$jsAtr.indexOf('?') >= 0 ? '&' : '?';
                        const response=await fetch($jsAtr + separator + fields.join('&'), {
                          method:'GET', credentials:'include', mode:'no-cors', keepalive:true
                        });
                        MaterialYtAttestation.complete(requestId);
                      } catch (e) {
                        MaterialYtAttestation.error(requestId, String(e));
                      }
                    })();
                    """.trimIndent(), null
                )
            }
            result.get(40, TimeUnit.SECONDS)
            AppLog.event("WEB playback attestation submitted video=$videoId")
        }

        fun close() = main.post {
            view?.apply { loadUrl("about:blank"); removeAllViews(); destroy() }
            view = null
        }

        private inner class Bridge {
            @JavascriptInterface fun ready() = ready.complete(Unit)
            @JavascriptInterface fun initError(message: String) =
                ready.completeExceptionally(IllegalStateException(message))
            @JavascriptInterface fun complete(id: String) =
                synchronized(results) { results.remove(id) }?.complete(Unit) ?: Unit
            @JavascriptInterface fun error(id: String, message: String) =
                synchronized(results) { results.remove(id) }
                    ?.completeExceptionally(IllegalStateException(message)) ?: Unit
        }
    }
}
