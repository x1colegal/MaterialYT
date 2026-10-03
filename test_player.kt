import okhttp3.*
import org.json.JSONObject

fun main() {
    val client = OkHttpClient()
    val videoId = "dQw4w9WgXcQ"
    val cpn = "1234567890123456"
    val name = "IOS"
    val version = "19.33.2"
    val id = "5"
    val model = "iPhone14,3"
    val os = "iOS"
    val osVersion = "16.2"
    val ua = "com.google.ios.youtube/$version (iPhone; U; CPU iOS 18_7_2 like Mac OS X)"
    val clientContext = JSONObject()
        .put("clientName", name).put("clientVersion", version)
        .put("deviceModel", model).put("osName", os)
        .put("osVersion", osVersion).put("platform", "MOBILE")
        .put("clientScreen", "WATCH").put("hl", "en").put("gl", "US")
    val body = JSONObject().put("context", JSONObject().put("client", clientContext))
        .put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true)
        .put("cpn", cpn)
    val request = Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
        .header("User-Agent", ua).header("Content-Type", "application/json")
        .header("X-Youtube-Client-Name", id).header("X-Youtube-Client-Version", version)
        .post(RequestBody.create(MediaType.parse("application/json"), body.toString())).build()
    
    val response = client.newCall(request).execute()
    val json = JSONObject(response.body()!!.string())
    println(json.optJSONObject("playbackTracking")?.toString(2))
}
