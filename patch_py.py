import re
with open('app/src/main/java/com/x1colegal/materialyt/YouTubeRepository.kt', 'r') as f:
    content = f.read()

content = re.sub(
    r'    private fun authenticatedPlayerTracking\(videoId: String, cpn: String, music: Boolean\): JSONObject\? \{.*?\n    \}',
    open('patch_auth.txt').read().strip(),
    content,
    flags=re.DOTALL
)

content = re.sub(
    r'    fun reportPlayback\(videoId: String, fromMs: Long, toMs: Long, paused: Boolean = false\) \{.*?\n    \}',
    open('patch_report.txt').read().strip(),
    content,
    flags=re.DOTALL
)

with open('app/src/main/java/com/x1colegal/materialyt/YouTubeRepository.kt', 'w') as f:
    f.write(content)
