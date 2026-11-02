package com.x1colegal.materialyt

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
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
        // WebView's CookieManager is process-wide and exposes cookie updates to an existing
        // WebView. Including the complete cookie header in this identity made YouTube's own
        // PoToken/attestation cookie mutations look like a login change: the ready runtime was
        // destroyed and rebuilt repeatedly in the middle of one SABR session. A later rebuild
        // could remain waiting for attmp while playback stayed in BUFFERING forever.
        val key = "$origin\u0000$userAgent"
        var current = runtime
        if (current == null || sessionKey != key) {
            current?.close()
            val candidate = Runtime(origin, userAgent, videoId)
            try {
                candidate.initialize()
                current = candidate
                runtime = candidate
                sessionKey = key
            } catch (error: Throwable) {
                candidate.close()
                throw error
            }
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
        private var playbackBootstrapStarted = false

        fun initialize() {
            main.post {
                val webView = WebView(MaterialYtApp.instance.applicationContext)
                view = webView
                // JavaScript muting happens inside YouTube's player and may be too late: the
                // Chromium media pipeline can already have created an AudioTrack and taken audio
                // focus, briefly silencing the real ExoPlayer. Mute the whole WebView before any
                // page or media pipeline exists. Keep the JS guard below for older WebView builds
                // that do not expose MUTE_AUDIO (notably devices on Android 5-7).
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) {
                    WebViewCompat.setAudioMuted(webView, true)
                    AppLog.event("WEB playback attestation WebView audio muted at provider level")
                } else {
                    AppLog.event("WEB playback attestation WebView provider mute unavailable; using JS silence guard")
                }
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                // Keep autoplay blocked until the page's player and every HTML media element
                // have been muted. Allowing it before navigation gives pre-roll ads a short
                // window to emit audio before onPageFinished can reach movie_player.
                webView.settings.mediaPlaybackRequiresUserGesture = true
                webView.settings.userAgentString = userAgent
                webView.webChromeClient = WebChromeClient()
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        if (playbackBootstrapStarted) return
                        playbackBootstrapStarted = true
                        view.evaluateJavascript(
                            """
                            (function installSilenceGuard() {
                              const silence=function() {
                                try {
                                  document.querySelectorAll('video,audio').forEach(function(media) {
                                    media.muted=true;
                                    media.defaultMuted=true;
                                    media.volume=0;
                                  });
                                  const player=document.getElementById('movie_player');
                                  if (player) {
                                    if (typeof player.setVolume === 'function') player.setVolume(0);
                                    if (typeof player.mute === 'function') player.mute();
                                  }
                                } catch (_) {}
                              };
                              silence();
                              try {
                                new MutationObserver(silence).observe(document.documentElement, {
                                  childList:true, subtree:true
                                });
                              } catch (_) {}
                              window.__materialYtSilence=silence;
                              return true;
                            })();
                            """.trimIndent()
                        ) {
                            // YouTube loads attmp lazily only after playback begins. Release the
                            // gesture gate after the silence guard exists, then start playback.
                            view.settings.mediaPlaybackRequiresUserGesture = false
                            view.evaluateJavascript(
                                """
                                try {
                                  if (window.__materialYtSilence) window.__materialYtSilence();
                                  const player=document.getElementById('movie_player');
                                  if (player && typeof player.playVideo === 'function') player.playVideo();
                                } catch (_) {}
                                (function waitForAttestation(remaining) {
                                  if (window.__materialYtSilence) window.__materialYtSilence();
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
                }
                webView.addJavascriptInterface(Bridge(), "MaterialYtAttestation")
                // Autoplay remains gated by WebSettings until the silence guard above is active.
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
                        const fields=[String(proof.challenge)];
                        if (proof.error) throw new Error('YouTube playback attestation failed: ' + proof.error);
                        else if (proof.webResponse) fields.push('r1a=' + String(proof.webResponse));
                        if (proof.adblockReporting && proof.adblockReporting.reportingStatus !== undefined)
                          fields.push('r6a=' + String(proof.adblockReporting.reportingStatus));
                        if (proof.adblockReporting && proof.adblockReporting.broadSpectrumDetectionResult !== undefined)
                          fields.push('r6b=' + String(proof.adblockReporting.broadSpectrumDetectionResult));
                        const separator=$jsAtr.indexOf('?') >= 0 ? '&' : '?';
                        const target=$jsAtr + separator + 'attestationResponse=' + encodeURIComponent(fields.join('&'));
                        const response=await fetch(target, {
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
            try {
                result.get(40, TimeUnit.SECONDS)
                AppLog.event("WEB playback attestation submitted video=$videoId")
            } finally {
                // A cancelled/timed-out waiter must not leave a dead result occupying this id.
                synchronized(results) { results.remove(id, result) }
            }
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
