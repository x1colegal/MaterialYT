with open('app/src/main/java/com/x1colegal/materialyt/YouTubeRepository.kt', 'r') as f:
    content = f.read()

import re

# Remove the tracking block from its original position
content = re.sub(
    r'        val cpn = response\.optString\("_materialytCpn"\)\n        // The regular WEB tracking client.*?synchronized\(playbackTracking\) \{ playbackTracking\[videoId\] = PlaybackTracking\(playback, watchtime, cpn, trackingAsMusic\) \}\n        \}\n',
    '',
    content,
    flags=re.DOTALL
)

# Insert it before the early return
tracking_code = """
        val cpn = response.optString("_materialytCpn").takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val trackingAsMusic = false
        val authenticatedTracking = runCatching { authenticatedPlayerTracking(videoId, cpn, trackingAsMusic) }
            .onFailure { AppLog.failure("authenticated tracking video=$videoId music=$music", it) }.getOrNull()
        (authenticatedTracking ?: response.optJSONObject("playbackTracking"))?.let { tracking ->
            val playback = tracking.optJSONObject("videostatsPlaybackUrl")?.optString("baseUrl").orEmpty()
            val watchtime = tracking.optJSONObject("videostatsWatchtimeUrl")?.optString("baseUrl").orEmpty()
            if (playback.isNotBlank() && watchtime.isNotBlank()) synchronized(playbackTracking) { playbackTracking[videoId] = PlaybackTracking(playback, watchtime, cpn, trackingAsMusic) }
        }

"""

content = re.sub(
    r'        AppLog\.event\("player client=\$clientUsed status=\$finalStatus video=\$videoId"\)\n        if \(finalStatus != "OK"\) \{',
    f'        AppLog.event("player client=$clientUsed status=$finalStatus video=$videoId")\n{tracking_code}        if (finalStatus != "OK") {{',
    content
)

with open('app/src/main/java/com/x1colegal/materialyt/YouTubeRepository.kt', 'w') as f:
    f.write(content)
