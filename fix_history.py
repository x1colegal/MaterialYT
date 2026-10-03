with open('app/src/main/java/com/x1colegal/materialyt/YouTubeRepository.kt', 'r') as f:
    lines = f.readlines()

new_lines = []
in_auth = False
in_report = False
brace_count = 0

for line in lines:
    if "private fun authenticatedPlayerTracking(videoId: String, cpn: String, music: Boolean): JSONObject?" in line:
        in_auth = True
        brace_count = line.count('{') - line.count('}')
        new_lines.append("""    private fun authenticatedPlayerTracking(videoId: String, cpn: String, music: Boolean): JSONObject? {
        val origin = "https://www.youtube.com"
        val html = runCatching { get("$origin/watch?v=$videoId") }.getOrNull() ?: return null
        val match = Regex("ytInitialPlayerResponse\\\\s*=\\\\s*(\\\\{.+?\\\\});").find(html)?.groupValues?.get(1) ?: return null
        val response = runCatching { JSONObject(match) }.getOrNull() ?: return null
        val tracking = response.optJSONObject("playbackTracking")
        AppLog.event("tracking_fetch WEB_HTML video=$videoId tracking_found=${tracking != null}")
        return tracking
    }
""")
        if brace_count <= 0: in_auth = False
        continue

    if in_auth:
        brace_count += line.count('{') - line.count('}')
        if brace_count <= 0:
            in_auth = False
        continue

    if "fun reportPlayback(videoId: String, fromMs: Long, toMs: Long, paused: Boolean = false) {" in line:
        in_report = True
        brace_count = line.count('{') - line.count('}')
        new_lines.append("""    fun reportPlayback(videoId: String, fromMs: Long, toMs: Long, paused: Boolean = false) {
        if (!signedIn() || toMs <= fromMs) return
        val tracking = synchronized(playbackTracking) { playbackTracking[videoId] } ?: return
        val first = synchronized(tracking) { if (!tracking.started) { tracking.started = true; true } else false }
        val clientName = "WEB"
        fun statsUrl(base: String, playback: Boolean): okhttp3.HttpUrl? = base.toHttpUrlOrNull()?.newBuilder()
            ?.setQueryParameter("ver", "2")
            ?.setQueryParameter("c", clientName)
            ?.setQueryParameter("cpn", tracking.cpn)
            ?.setQueryParameter("cmt", "%.3f".format(java.util.Locale.US, toMs / 1000.0))
            ?.setQueryParameter("rt", "%.3f".format(java.util.Locale.US, (System.currentTimeMillis() - tracking.startedAtMs) / 1000.0))
            ?.setQueryParameter("final", "0")
            ?.apply {
                if (!playback) {
                    setQueryParameter("st", "%.3f".format(java.util.Locale.US, fromMs / 1000.0))
                    setQueryParameter("et", "%.3f".format(java.util.Locale.US, toMs / 1000.0))
                    setQueryParameter("state", if (paused) "paused" else "playing")
                }
            }?.build()
        fun send(url: okhttp3.HttpUrl) {
            val origin = "https://www.youtube.com"
            val request = Request.Builder().url(url).apply { authenticatedHeaders(origin).forEach { (key, value) -> header(key, value) } }.build()
            client.newCall(request).execute().use { response ->
                AppLog.event("playback tracking video=$videoId music=${tracking.music} kind=${if (url.encodedPath.contains("watchtime")) "watchtime" else "playback"} state=${if (paused) "paused" else "playing"} HTTP ${response.code}")
            }
        }
        if (first) statsUrl(tracking.playbackUrl, true)?.let(::send)
        statsUrl(tracking.watchtimeUrl, false)?.let(::send)
    }
""")
        if brace_count <= 0: in_report = False
        continue

    if in_report:
        brace_count += line.count('{') - line.count('}')
        if brace_count <= 0:
            in_report = False
        continue

    new_lines.append(line)

with open('app/src/main/java/com/x1colegal/materialyt/YouTubeRepository.kt', 'w') as f:
    f.writelines(new_lines)
