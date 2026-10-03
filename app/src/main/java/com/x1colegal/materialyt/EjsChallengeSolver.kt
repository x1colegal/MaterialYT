package com.x1colegal.materialyt

import android.content.Context
import com.eclipsesource.v8.V8
import org.json.JSONArray
import org.json.JSONObject

/** Runs the pinned yt-dlp/ejs solver in an isolated native V8 runtime. */
object EjsChallengeSolver {
    private val runtimeLock = Any()
    private lateinit var libraryCode: String
    private lateinit var solverCode: String

    fun initialize(context: Context) {
        libraryCode = context.assets.open("yt.solver.lib.min.js").bufferedReader().use { it.readText() }
        solverCode = context.assets.open("yt.solver.core.min.js").bufferedReader().use { it.readText() }
    }

    fun solveN(playerJavaScript: String, challenge: String): String =
        solve(playerJavaScript, "n", challenge)

    fun solveSignature(playerJavaScript: String, signature: String): String =
        solve(playerJavaScript, "sig", signature)

    private fun solve(playerJavaScript: String, type: String, challenge: String): String {
        val input = JSONObject()
            .put("type", "player")
            .put("player", playerJavaScript)
            .put("output_preprocessed", false)
            .put("requests", JSONArray().put(JSONObject()
                .put("type", type)
                .put("challenges", JSONArray().put(challenge))))

        val decoded = synchronized(runtimeLock) {
            val runtime = V8.createV8Runtime()
            try {
                runtime.executeStringScript(
                    "$libraryCode\nvar meriyah=lib.meriyah,astring=lib.astring;\n$solverCode\nJSON.stringify(jsc($input));"
                ) ?: error("EJS returned no result")
            } finally {
                runtime.release(false)
            }
        }

        val response = JSONObject(decoded).getJSONArray("responses").getJSONObject(0)
        if (response.optString("type") != "result") {
            error(response.optString("error", "EJS could not solve $type challenge"))
        }
        return response.getJSONObject("data").getString(challenge)
    }
}
