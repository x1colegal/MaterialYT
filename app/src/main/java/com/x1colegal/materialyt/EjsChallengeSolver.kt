package com.x1colegal.materialyt

import android.content.Context
import android.os.SystemClock
import com.eclipsesource.v8.V8
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Runs the pinned yt-dlp/ejs solver in an isolated native V8 runtime. */
object EjsChallengeSolver {
    private lateinit var libraryCode: String
    private lateinit var solverCode: String
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MaterialYT-EJS").apply { isDaemon = true }
    }
    private var runtime: V8? = null
    private val preprocessedPlayers = object : LinkedHashMap<String, String>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 3
    }

    fun initialize(context: Context) {
        libraryCode = context.assets.open("yt.solver.lib.min.js").bufferedReader().use { it.readText() }
        solverCode = context.assets.open("yt.solver.core.min.js").bufferedReader().use { it.readText() }
    }

    fun solveN(playerJavaScript: String, challenge: String): String =
        solve(playerJavaScript, "n", challenge)

    fun solveSignature(playerJavaScript: String, signature: String): String =
        solve(playerJavaScript, "sig", signature)

    private fun solve(playerJavaScript: String, type: String, challenge: String): String {
        val playerKey = MessageDigest.getInstance("SHA-256").digest(playerJavaScript.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return executor.submit<String> {
            val startedAt = SystemClock.elapsedRealtime()
            val cachedPlayer = preprocessedPlayers[playerKey]
            val input = JSONObject()
                .put("type", if (cachedPlayer == null) "player" else "preprocessed")
                .apply {
                    if (cachedPlayer == null) {
                        put("player", playerJavaScript)
                        put("output_preprocessed", true)
                    } else {
                        put("preprocessed_player", cachedPlayer)
                    }
                }
                .put("requests", JSONArray().put(JSONObject()
                    .put("type", type)
                    .put("challenges", JSONArray().put(challenge))))

            val v8 = runtime ?: V8.createV8Runtime().also {
                it.executeVoidScript("$libraryCode\nvar meriyah=lib.meriyah,astring=lib.astring;\n$solverCode")
                runtime = it
            }
            val decoded = v8.executeStringScript("JSON.stringify(jsc($input));")
                ?: error("EJS returned no result")
            val result = JSONObject(decoded)
            result.optString("preprocessed_player").takeIf(String::isNotBlank)?.let {
                preprocessedPlayers[playerKey] = it
            }
            val response = result.getJSONArray("responses").getJSONObject(0)
            if (response.optString("type") != "result") {
                error(response.optString("error", "EJS could not solve $type challenge"))
            }
            AppLog.event(
                "EJS solved type=$type cachedPlayer=${cachedPlayer != null} elapsed=${SystemClock.elapsedRealtime() - startedAt}ms"
            )
            response.getJSONObject("data").getString(challenge)
        }.get()
    }
}
