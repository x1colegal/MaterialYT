package com.x1colegal.materialyt

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import android.os.SystemClock
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.roundToInt

data class FeedItem(val id: String, val title: String, val subtitle: String, val thumbnail: String, val thumbnailWidth: Int, val thumbnailHeight: Int, val channelThumbnail: String, val url: String, val playlist: Boolean = false, val channel: Boolean = false, val channelUrl: String = "", val resumePositionMs: Long = 0L, val short: Boolean = false, val setVideoId: String = "", val musicVideo: Boolean = false)
data class MusicCategory(val title: String, val browseId: String, val params: String = "")
data class MusicHomeSection(val title: String, val strapline: String = "", val items: List<FeedItem>, val horizontal: Boolean = false, val continuation: String? = null)
data class MusicHomePage(val categories: List<MusicCategory>, val sections: List<MusicHomeSection>, val hasMore: Boolean = false)
data class PlaylistPage(val items: List<FeedItem>, val editable: Boolean, val deletable: Boolean)
data class CanonicalVideoMetadata(val title: String, val author: String)
data class PlayableStream(val url: String, val codec: String, val height: Int, val fps: Int, val bitrate: Int, val audio: Boolean, val videoOnly: Boolean, val originalAudio: Boolean = false, val audioTrackName: String = "", val audioTrackId: String = "", val sabrPlayback: SabrPlaybackInfo? = null, val sabrFormat: SabrFormat? = null)
data class SabrFormat(
    val itag: Int,
    val mimeType: String,
    val codec: String,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val contentLength: Long,
    val lastModified: Long,
    val xtags: String,
    val audio: Boolean,
    val originalAudio: Boolean = false,
    val audioTrackName: String = "",
    val audioTrackId: String = ""
)
data class SabrPlaybackInfo(
    val videoId: String,
    val serverAbrStreamingUrl: String,
    val videoPlaybackUstreamerConfig: String,
    val playerPoToken: String,
    val streamingPoToken: String,
    val clientVersion: String,
    val userAgent: String,
    val clientId: Int,
    val osName: String,
    val osVersion: String,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val durationMs: Long,
    val formats: List<SabrFormat>
)
data class ChannelInfo(val name: String, val thumbnail: String)
data class NativeChannelPage(
    val id: String,
    val name: String,
    val avatar: String,
    val banner: String,
    val description: String,
    val subscribers: String,
    val items: List<FeedItem>,
)
data class AccountSearchEntry(val text: String, val feedbackToken: String? = null)
data class DescriptionLinkTarget(val start: Int, val end: Int, val target: String)
data class VideoDetails(val title: String, val author: String, val authorUrl: String = "", val authorAvatar: String = "", val viewCount: Long = 0L, val durationSeconds: Long = 0L, val likeText: String = "", val publishedDate: String = "", val description: String = "", val descriptionLinks: List<DescriptionLinkTarget> = emptyList())
enum class VideoRating { NONE, LIKE, DISLIKE }
data class VideoInteractionState(
    val rating: VideoRating = VideoRating.NONE,
    val subscribed: Boolean = false,
    val channelId: String = "",
    val likeText: String = "",
    val related: List<FeedItem> = emptyList(),
    val relatedContinuation: String? = null,
)
data class RelatedVideoPage(val items: List<FeedItem>, val continuation: String? = null)
data class LyricLine(val text: String, val startMs: Long, val endMs: Long)
data class CommunityPost(val id: String, val text: String, val published: String, val likes: String, val comments: String, val image: String = "", val avatar: String = "")
data class CommunityComment(
    val id: String,
    val author: String,
    val text: String,
    val published: String,
    val likes: String,
    val avatar: String = "",
    val replyCount: Int = 0,
    val repliesContinuation: String? = null,
    val createReplyParams: String? = null,
    val isOwn: Boolean = false,
    val updateParams: String? = null,
    val updateIsReply: Boolean = false,
    val updateApiUrl: String? = null,
    val deleteParams: String? = null,
)
data class CommunityPostThread(val comments: List<CommunityComment>, val createCommentParams: String? = null)
data class CommunityReplyPage(
    val replies: List<CommunityComment>,
    val continuation: String? = null,
    val createReplyParams: String? = null,
)
data class VideoComment(
    val id: String,
    val author: String,
    val text: String,
    val published: String,
    val likes: String,
    val avatar: String = "",
    val replyCount: Int = 0,
    val repliesContinuation: String? = null,
    val createReplyParams: String? = null,
    val isOwn: Boolean = false,
    val authorChannelId: String = "",
    val updateParams: String? = null,
    val updateIsReply: Boolean = false,
    val updateApiUrl: String? = null,
    val deleteParams: String? = null,
)
data class VideoCommentPage(
    val items: List<VideoComment>,
    val continuation: String? = null,
    val createCommentParams: String? = null,
)

object YouTubeRepository {
    private fun audioOnlyMusicItems(items: List<FeedItem>): List<FeedItem> =
        items.filter { it.playlist || it.channel || !it.musicVideo }

    const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
    const val WEB_PLAYER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    const val VISIONOS_UA = "com.google.visionosyoutube/1.03 (RealityDevice17,1; U; CPU visionOS 26_6_1 like Mac OS X; en_US) gzip"
    private const val YOUTUBE_SUGGEST_EXPERIMENTS = "ytzpb5_e2,ytpo.bo.lqp.elu=1,ytpo.bo.lqp.ecsc=1,ytpo.bo.lqp.mcsc=3,ytpo.bo.lqp.mec=1,ytpo.bo.lqp.rw=0.8,ytpo.bo.lqp.fw=0.2,ytpo.bo.lqp.szp=1,ytpo.bo.lqp.mz=3,ytpo.bo.lqp.al=en_us,ytpo.bo.lqp.zrm=1,ytpo.bo.lqp.er=1,ytpo.bo.ro.erl=1,ytpo.bo.ro.mlus=3,ytpo.bo.ro.erls=3,ytpo.bo.qfo.mlus=3,ytzprp.ppp.e=1,ytzprp.ppp.st=772,ytzprp.ppp.p=5"
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val clientVersions = mutableMapOf<String, String>()
    private val visitorData = mutableMapOf<String, String>()
    private val standaloneWebVisitors = mutableMapOf<String, Pair<Long, String>>()
    private val bootstrapCache = mutableMapOf<String, Pair<Long, Bootstrap>>()
    private val feedGeneration = AtomicLong(0L)
    private val sessionOwnedCommentIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val sessionOwnedCommentAuthors = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val accountPlaylistIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var signedInCommentChannelId: String? = null
    @Volatile private var signedInChannelInfo: ChannelInfo? = null
    private val activeFeedCall = AtomicReference<Call?>(null)
    private val playerJavaScriptCache = mutableMapOf<String, String>()
    private val nChallengeCache = mutableMapOf<String, String>()
    private val videoDetailsCache = mutableMapOf<String, VideoDetails>()
    private val channelNameCache = mutableMapOf<String, String>()
    private val canonicalMetadataCache = mutableMapOf<String, CanonicalVideoMetadata>()
    fun videoDetails(videoId: String): VideoDetails? = synchronized(videoDetailsCache) { videoDetailsCache[videoId] }
    fun canonicalVideoMetadata(videoId: String): CanonicalVideoMetadata? {
        if (videoId.length != 11) return null
        synchronized(canonicalMetadataCache) { canonicalMetadataCache[videoId]?.let { return it } }
        return runCatching {
            val origin = "https://www.youtube.com"
            val bootstrap = bootstrap(origin, "WEB")
            val details = post(
                "$origin/youtubei/v1/player?key=${bootstrap.key}&prettyPrint=false",
                JSONObject().put("context", context("WEB", bootstrap.version))
                    .put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true),
                origin,
            ).optJSONObject("videoDetails")
            details?.let {
                CanonicalVideoMetadata(
                    title = it.optString("title").trim(),
                    author = it.optString("author").trim(),
                ).takeIf { metadata -> metadata.title.isNotBlank() || metadata.author.isNotBlank() }
            }?.also { metadata ->
                synchronized(canonicalMetadataCache) { canonicalMetadataCache[videoId] = metadata }
                synchronized(videoDetailsCache) {
                    val current = videoDetailsCache[videoId] ?: VideoDetails("", "")
                    videoDetailsCache[videoId] = current.copy(
                        title = metadata.title.ifBlank { current.title },
                        author = metadata.author.ifBlank { current.author },
                    )
                }
            }
        }.onFailure { AppLog.failure("canonical WEB Innertube metadata video=$videoId", it) }.getOrNull()
    }
    fun seedVideoDetails(videoId: String, title: String, author: String, authorUrl: String = "", authorAvatar: String = "") {
        if (videoId.isBlank()) return
        synchronized(videoDetailsCache) {
            videoDetailsCache[videoId] = (videoDetailsCache[videoId] ?: VideoDetails(title, author)).copy(
                title = title,
                author = author,
                authorUrl = authorUrl,
                authorAvatar = authorAvatar,
            )
        }
    }
    private data class PlaybackTracking(
        val playbackUrl: String,
        val watchtimeUrl: String,
        val cpn: String,
        val music: Boolean,
        val startedAtMs: Long = System.currentTimeMillis(),
        var started: Boolean = false
    )
    private val playbackTracking = mutableMapOf<String, PlaybackTracking>()

    fun signedIn(): Boolean = cookies("https://www.youtube.com").contains("SAPISID=") || cookies("https://www.youtube.com").contains("__Secure-3PAPISID=")

    fun accountSessionExpired(): Boolean {
        if (!signedIn()) return false
        val origin = "https://www.youtube.com"
        val response = runCatching {
            val bootstrap = bootstrap(origin, "WEB")
            post("$origin/youtubei/v1/browse?key=${bootstrap.key}",
                JSONObject().put("context", context("WEB", bootstrap.version)).put("browseId", "FEhistory"), origin)
        }.onFailure { AppLog.failure("startup account session check", it) }.getOrNull() ?: return false
        return findObject(response, "signInEndpoint") != null
    }

    @Volatile
    private var homeToken: String? = null
    @Volatile private var searchToken: String? = null
    @Volatile private var activeSearchQuery: String? = null
    @Volatile private var shortsToken: String? = null
    @Volatile private var shortsTokenIsContinuation = false
    @Volatile private var musicHomeToken: String? = null
    @Volatile private var musicHomeRadioSeed: String? = null
    @Volatile private var musicHomeRadioMode = false
    @Volatile private var musicSearchToken: String? = null
    @Volatile private var activeMusicQuery: String? = null
    private val homeSeenIds = linkedSetOf<String>()
    private val shortsSeenIds = linkedSetOf<String>()

    fun home(): List<FeedItem> {
        AppLog.event("feed=home started")
        val generation = beginFeedLoad("home")
        return try {
            val started = SystemClock.elapsedRealtime()
            val bootstrap = bootstrap("https://www.youtube.com", "WEB", generation)
            val data = post("https://www.youtube.com/youtubei/v1/browse?key=${bootstrap.key}",
                JSONObject().put("context", context("WEB", bootstrap.version)).put("browseId", "FEwhat_to_watch"), bootstrap.origin, generation)
            val parseStarted = SystemClock.elapsedRealtime()
            homeToken = findContinuationToken(data)
            val uniqueItems = parseItems(data).distinctBy { it.id }
            synchronized(homeSeenIds) { homeSeenIds.clear(); homeSeenIds.addAll(uniqueItems.map { it.id }) }
            AppLog.event("feed=home success items=${uniqueItems.size} hasToken=${homeToken != null} parseMs=${SystemClock.elapsedRealtime() - parseStarted} totalMs=${SystemClock.elapsedRealtime() - started}")
            uniqueItems
        } catch (error: Throwable) {
            AppLog.failure("feed=home", error)
            throw error
        }
    }

    fun homeContinuation(): List<FeedItem> {
        val token = homeToken
        return try {
            val bootstrap = bootstrap("https://www.youtube.com", "WEB")
            val data = post("https://www.youtube.com/youtubei/v1/browse?key=${bootstrap.key}", JSONObject()
                .put("context", context("WEB", bootstrap.version)).apply {
                    if (token == null) put("browseId", "FEwhat_to_watch") else put("continuation", token)
                }, bootstrap.origin)
            val nextToken = findContinuationToken(data)
            homeToken = if (nextToken != token) nextToken else null
            parseItems(data).distinctBy { it.id }.filter { item -> synchronized(homeSeenIds) { homeSeenIds.add(item.id) } }
        } catch (error: Throwable) {
            AppLog.failure("feed=homeContinuation", error)
            emptyList()
        }
    }
    fun shorts(): List<FeedItem> {
        shortsToken = null
        shortsTokenIsContinuation = false
        synchronized(shortsSeenIds) { shortsSeenIds.clear() }
        return shortsBatch().also { items -> synchronized(shortsSeenIds) { shortsSeenIds.addAll(items.map { it.id }) } }
    }

    private fun shortsBatch(): List<FeedItem> {
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        val itemEndpoint = "$origin/youtubei/v1/reel/reel_item_watch?key=${bootstrap.key}"
        val sequenceEndpoint = "$origin/youtubei/v1/reel/reel_watch_sequence?key=${bootstrap.key}"
        val seed = post(itemEndpoint, JSONObject()
            .put("context", context("WEB", bootstrap.version))
            .put("disablePlayerResponse", true)
            .put("inputType", "REEL_WATCH_INPUT_TYPE_SEEDLESS")
            .put("params", "CA8%3D"), origin)
        val result = mutableListOf<FeedItem>()
        if (!containsAdMarker(seed)) {
            reelFeedItem(seed)?.let(result::add)
        }
        val continuation = reelInitialContinuation(seed).orEmpty()
        if (continuation.isNotBlank()) result += shortsSequence(continuation, bootstrap, false)
        return result.distinctBy { it.id }.also { AppLog.event("shorts reel items=${it.size}") }
    }

    fun shortsContinuation(): List<FeedItem> {
        return runCatching {
            val bootstrap = bootstrap("https://www.youtube.com", "WEB")
            val result = mutableListOf<FeedItem>()
            var attempts = 0
            while (result.size < 8 && attempts < 6) {
                val token = shortsToken
                val batch = if (token != null) shortsSequence(token, bootstrap, shortsTokenIsContinuation) else shortsBatch()
                result += batch.filter { item -> synchronized(shortsSeenIds) { shortsSeenIds.add(item.id) } }
                attempts++
            }
            result
        }.onFailure { AppLog.failure("shorts continuation", it) }.getOrDefault(emptyList())
    }

    private fun shortsSequence(token: String, bootstrap: Bootstrap, continuationToken: Boolean): List<FeedItem> {
        val origin = "https://www.youtube.com"
        val sequence = post("$origin/youtubei/v1/reel/reel_watch_sequence?key=${bootstrap.key}", JSONObject()
            .put("context", context("WEB", bootstrap.version))
            .put(if (continuationToken) "continuation" else "sequenceParams", token), origin)
        var next = reelNextContinuation(sequence).orEmpty()
        val result = mutableListOf<FeedItem>()
        val entries = sequence.optJSONArray("entries") ?: findArray(sequence, "entries")
        if (entries != null) for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            if (containsAdMarker(entry)) continue
            val watch = entry.optJSONObject("command")?.optJSONObject("reelWatchEndpoint") ?: continue
            if (containsAdMarker(watch)) continue
            val videoId = watch.optString("videoId")
            val params = watch.optString("params").ifBlank { watch.optString("playerParams") }
            if (videoId.length != 11 || params.isBlank()) continue
            runCatching {
                post("$origin/youtubei/v1/reel/reel_item_watch?key=${bootstrap.key}", JSONObject()
                    .put("context", context("WEB", bootstrap.version)).put("disablePlayerResponse", true)
                    .put("params", params).put("playerRequest", JSONObject().put("videoId", videoId)), origin)
            }.getOrNull()?.let { details ->
                if (!containsAdMarker(details)) {
                    reelFeedItem(details, watch)?.let(result::add)
                    if (next.isBlank()) {
                        val candidate = reelInitialContinuation(details).orEmpty()
                        if (candidate.isNotBlank() && candidate != token) next = candidate
                    }
                }
            }
        }
        shortsToken = next.takeIf { it.isNotBlank() && it != token }
        shortsTokenIsContinuation = shortsToken != null && sequence.optString("continuation").isNotBlank()
        return result.distinctBy { it.id }.also {
            AppLog.event("shorts continuation request=${if (continuationToken) "continuation" else "sequenceParams"} entries=${entries?.length() ?: 0} items=${it.size} hasNext=${shortsToken != null} nextType=${if (shortsTokenIsContinuation) "continuation" else "sequenceParams"}")
        }
    }

    private fun reelInitialContinuation(value: JSONObject): String? =
        value.optString("sequenceContinuation").takeIf(String::isNotBlank)
            ?: value.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")
                ?.optString("token")?.takeIf(String::isNotBlank)

    private fun reelNextContinuation(value: JSONObject): String? =
        value.optString("continuation").takeIf(String::isNotBlank)
            ?: value.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")
                ?.optString("token")?.takeIf(String::isNotBlank)

    private data class ShortsMetadata(val title: String, val channel: String, val avatar: String, val channelUrl: String)

    private fun findShortsMetadata(json: Any?): ShortsMetadata? {
        var title = ""
        var channel = ""
        var avatar = ""
        var channelUrl = ""
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    if (title.isBlank()) {
                        value.optJSONObject("videoTitleHeaderViewModel")?.optJSONObject("videoTitle")?.optString("content")?.takeIf { it.isNotBlank() }?.let { title = it }
                            ?: value.optJSONObject("pageHeaderViewModel")?.optJSONObject("title")?.optJSONObject("dynamicTextViewModel")?.optJSONObject("text")?.optString("content")?.takeIf { it.isNotBlank() }?.let { title = it }
                    }
                    value.optJSONObject("videoDescriptionHeaderRenderer")?.let { desc ->
                        if (channel.isBlank()) desc.optJSONObject("channel")?.optString("simpleText")?.takeIf { it.isNotBlank() }?.let { channel = it }
                        if (avatar.isBlank()) findThumbnail(desc.optJSONObject("channelThumbnail"))?.let { avatar = it }
                        if (channelUrl.isBlank()) {
                            val ep = desc.optJSONObject("channelNavigationEndpoint")?.optJSONObject("browseEndpoint")
                            val cu = ep?.optString("canonicalBaseUrl").orEmpty().ifBlank { ep?.optString("browseId").orEmpty() }
                            if (cu.isNotBlank()) channelUrl = if (cu.startsWith("http")) cu else "https://www.youtube.com${if (cu.startsWith("/")) "" else "/"}$cu"
                        }
                    }
                    value.keys().forEach { k -> walk(value.opt(k)) }
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
            }
        }
        walk(json)
        return if (title.isNotBlank() || channel.isNotBlank()) ShortsMetadata(title, channel, avatar, channelUrl) else null
    }

    private fun reelFeedItem(response: JSONObject, fallbackWatch: JSONObject? = null): FeedItem? {
        if (containsAdMarker(response) || (fallbackWatch != null && containsAdMarker(fallbackWatch))) return null
        val watch = response.optJSONObject("replacementEndpoint")?.optJSONObject("reelWatchEndpoint") ?: fallbackWatch ?: return null
        val videoId = watch.optString("videoId").takeIf { it.length == 11 } ?: return null
        val header = response.optJSONObject("overlay")?.optJSONObject("reelPlayerOverlayRenderer")
            ?.optJSONObject("reelPlayerHeaderSupportedRenderers")?.optJSONObject("reelPlayerHeaderRenderer")
        var title = header?.let { text(it.opt("reelTitleText")) }
            ?: header?.let { text(it.optJSONObject("reelTitleOnClickCommand")?.opt("title")) }
            ?: ""
        var channel = header?.let { text(it.opt("channelTitleText")) } ?: ""
        var channelThumbnail = header?.let { findThumbnail(it.opt("channelThumbnail")) }.orEmpty()
        var channelUrl = ""

        val meta = findShortsMetadata(response)
        if (meta != null) {
            if (title.isBlank()) title = meta.title
            if (channel.isBlank()) channel = meta.channel
            if (channelThumbnail.isBlank()) channelThumbnail = meta.avatar
            if (channelUrl.isBlank()) channelUrl = meta.channelUrl
        }
        if (isAdString(title) || isAdString(channel)) return null
        if (title.isBlank()) title = "Short"
        if (channel.isBlank()) channel = "YouTube"

        val thumbnail = findThumbnail(watch.opt("thumbnail")) ?: "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
        return FeedItem(videoId, title, channel, thumbnail, 9, 16, channelThumbnail, "https://www.youtube.com/shorts/$videoId", channelUrl = channelUrl)
    }
    fun history(): List<FeedItem> = feed("history", "https://www.youtube.com/feed/history")

    /** Looks up YouTube's saved watch position even when the video was opened outside History. */
    fun historyResumePosition(videoId: String): Long = history()
        .firstOrNull { it.id == videoId }
        ?.resumePositionMs
        ?.coerceAtLeast(0L)
        ?: 0L
    fun ownChannelUrl(): String {
        val origin = "https://www.youtube.com"
        val youHtml = get("$origin/feed/you")
        listOf(Regex("\\\"CHANNEL_ID\\\"\\s*:\\s*\\\"(UC[^\\\"]+)\\\""), Regex("/channel/(UC[A-Za-z0-9_-]+)"))
            .firstNotNullOfOrNull { it.find(youHtml)?.groupValues?.getOrNull(1) }
            ?.let { return "$origin/channel/$it" }
        val bootstrap = bootstrap(origin, "WEB")
        val accountMenu = runCatching {
            post("$origin/youtubei/v1/account/account_menu?key=${bootstrap.key}", JSONObject().put("context", context("WEB", bootstrap.version)), origin)
        }.getOrNull()
        accountMenu?.let(::findFirstChannelId)?.let { return "$origin/channel/$it" }
        accountMenu?.let { findString(it, "channelHandle") }?.trim()?.takeIf { it.startsWith("@") }
            ?.let { return "$origin/$it" }
        val guide = post("$origin/youtubei/v1/guide?key=${bootstrap.key}", JSONObject().put("context", context("WEB", bootstrap.version)), origin)
        val channelId = findOwnChannelId(guide) ?: findFirstChannelId(guide) ?: error("YouTube did not expose the signed-in channel")
        return "$origin/channel/$channelId"
    }
    fun yourVideos(): List<FeedItem> {
        val youHtml = runCatching { get("https://www.youtube.com/feed/you") }.getOrDefault("")
        runCatching {
            val data = initialData(youHtml)
            findSectionItems(data, "Your videos")
        }.getOrDefault(emptyList()).takeIf { it.isNotEmpty() }?.let { return it }
        val htmlChannelId = listOf(
            Regex("\\\"CHANNEL_ID\\\"\\s*:\\s*\\\"(UC[^\\\"]+)\\\""),
            Regex("/channel/(UC[A-Za-z0-9_-]+)")
        ).firstNotNullOfOrNull { it.find(youHtml)?.groupValues?.getOrNull(1) }
        if (htmlChannelId != null) return feed("your videos", "https://www.youtube.com/channel/$htmlChannelId/videos")
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        val accountMenu = runCatching {
            post("$origin/youtubei/v1/account/account_menu?key=${bootstrap.key}", JSONObject().put("context", context("WEB", bootstrap.version)), origin)
        }.getOrNull()
        val handle = accountMenu?.let { findString(it, "channelHandle") }?.trim()?.takeIf { it.startsWith("@") }
        if (handle != null) return feed("your videos", "$origin/$handle/videos")
        val guide = post("$origin/youtubei/v1/guide?key=${bootstrap.key}", JSONObject().put("context", context("WEB", bootstrap.version)), origin)
        val channelId = findOwnChannelId(guide) ?: error("YouTube did not expose the signed-in channel")
        return feed("your videos", "$origin/channel/$channelId/videos")
    }
    fun library(): List<FeedItem> = feed("playlists", "https://www.youtube.com/feed/playlists")
        .filterNot { it.playlist && it.id.removePrefix("VL") == "YS" }
        .also { items ->
        accountPlaylistIds.clear()
        accountPlaylistIds.addAll(items.filter { it.playlist }.map { it.id.removePrefix("VL") })
        AppLog.event("playlist library ${items.joinToString(" | ") { "${it.title}=${it.id}" }}")
    }

    fun createPlaylist(title: String, privacy: String = "PRIVATE"): String {
        require(signedIn()) { "Sign in again to create playlists" }
        require(title.isNotBlank()) { "Enter a playlist name" }
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        val response = post("$origin/youtubei/v1/playlist/create?key=${bootstrap.key}", JSONObject()
            .put("context", context("WEB", bootstrap.version))
            .put("title", title.trim())
            .put("privacyStatus", privacy), origin)
        return response.optString("playlistId").takeIf(String::isNotBlank)
            ?: findString(response, "playlistId")
            ?: error("YouTube did not return the new playlist")
    }

    fun addVideoToPlaylist(playlistId: String, videoId: String) {
        require(signedIn()) { "Sign in again to save videos" }
        require(videoId.length == 11) { "Invalid video" }
        val normalizedId = playlistId.removePrefix("VL")
        require(normalizedId.isNotBlank()) { "Invalid playlist" }
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        post("$origin/youtubei/v1/browse/edit_playlist?key=${bootstrap.key}", JSONObject()
            .put("context", context("WEB", bootstrap.version))
            .put("playlistId", normalizedId)
            .put("actions", JSONArray().put(JSONObject()
                .put("action", "ACTION_ADD_VIDEO")
                .put("addedVideoId", videoId))), origin)
    }

    fun deletePlaylist(playlistId: String) {
        require(signedIn()) { "Sign in again to delete playlists" }
        val normalizedId = playlistId.removePrefix("VL")
        require(normalizedId.isNotBlank()) { "Invalid playlist" }
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        post("$origin/youtubei/v1/playlist/delete?key=${bootstrap.key}", JSONObject()
            .put("context", context("WEB", bootstrap.version))
            .put("playlistId", normalizedId), origin)
    }

    fun removeVideoFromPlaylist(playlistId: String, setVideoId: String) {
        require(signedIn()) { "Sign in again to edit playlists" }
        val normalizedId = playlistId.removePrefix("VL")
        require(normalizedId.isNotBlank() && setVideoId.isNotBlank()) { "YouTube did not provide permission to remove this video" }
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        post("$origin/youtubei/v1/browse/edit_playlist?key=${bootstrap.key}", JSONObject()
            .put("context", context("WEB", bootstrap.version))
            .put("playlistId", normalizedId)
            .put("actions", JSONArray().put(JSONObject()
                .put("action", "ACTION_REMOVE_VIDEO")
                .put("setVideoId", setVideoId))), origin)
    }

    fun playlistVideos(playlistId: String): List<FeedItem> = playlistPage(playlistId).items

    fun playlistPage(playlistId: String): PlaylistPage {
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        val normalizedId = playlistId.removePrefix("VL")
        var page = post("$origin/youtubei/v1/browse?key=${bootstrap.key}", JSONObject().put("context", context("WEB", bootstrap.version)).put("browseId", "VL$normalizedId"), origin)
        var editable = findBoolean(page, "isEditable") == true || normalizedId in accountPlaylistIds
        var deletable = editable && normalizedId.startsWith("PL")
        var items = parsePlaylistVideos(page).toMutableList()
        val seen = mutableSetOf<String>()
        AppLog.event("playlist browse id=$normalizedId items=${items.size} continuation=${findPlaylistContinuationToken(page) != null}")
        // Some current playlist responses contain only a continuation in their initial
        // payload. Consume it before trying HTML; replacing the response with an empty
        // HTML page here used to discard the only route to the actual videos.
        while (seen.size < 20) {
            val token = findPlaylistContinuationToken(page) ?: break
            if (!seen.add(token)) break
            page = post("$origin/youtubei/v1/browse?key=${bootstrap.key}", JSONObject().put("context", context("WEB", bootstrap.version)).put("continuation", token), origin)
            val more = parsePlaylistVideos(page)
            AppLog.event("playlist continuation id=$normalizedId page=${seen.size} items=${more.size}")
            items += more
        }
        if (items.isEmpty() && normalizedId == "YS") {
            val mobileCandidates = listOf(
                Triple("ANDROID", "20.05.35", "3"),
                Triple("MWEB", bootstrap.version, "2"),
            )
            for ((clientName, version, clientId) in mobileCandidates) {
                val mobile = runCatching {
                    postAuthenticatedClient(
                        "$origin/youtubei/v1/browse?key=${bootstrap.key}",
                        JSONObject().put("context", if (clientName == "ANDROID") {
                            context(clientName, version).also { context -> context.getJSONObject("client")
                                .put("androidSdkVersion", 34).put("platform", "MOBILE").put("clientScreen", "PLAYLIST") }
                        } else context(clientName, version)).put("browseId", "VLYS"),
                        origin, clientId, version,
                        if (clientName == "ANDROID") "com.google.android.youtube/$version (Linux; U; Android 14) gzip" else UA,
                    )
                }.onFailure { AppLog.failure("saved shorts $clientName browse", it) }.getOrNull()
                if (mobile != null) {
                    val mobileItems = parsePlaylistVideos(mobile)
                    AppLog.event("saved shorts client=$clientName items=${mobileItems.size}")
                    items += mobileItems
                    if (items.isNotEmpty()) break
                }
            }
        }
        if (items.isEmpty() && normalizedId == "YS") {
            val mobileHtml = runCatching { initialData(get("https://m.youtube.com/playlist?list=YS&hl=en")) }
                .onFailure { AppLog.failure("saved shorts MWEB HTML", it) }.getOrNull()
            if (mobileHtml != null) {
                val mobileItems = parsePlaylistVideos(mobileHtml)
                AppLog.event("saved shorts MWEB HTML items=${mobileItems.size}")
                items += mobileItems
            }
        }
        if (items.isEmpty()) {
            val direct = runCatching {
                post("$origin/youtubei/v1/browse?key=${bootstrap.key}", JSONObject()
                    .put("context", context("WEB", bootstrap.version))
                    .put("browseId", normalizedId), origin)
            }.getOrNull()
            if (direct != null) {
                val directItems = parsePlaylistVideos(direct)
                AppLog.event("playlist direct browse id=$normalizedId items=${directItems.size}")
                items += directItems
            }
        }
        if (items.isEmpty()) {
            val remixItems = runCatching { musicPlaylistVideos(normalizedId) }
                .onFailure { AppLog.failure("playlist WEB_REMIX fallback id=$normalizedId", it) }
                .getOrDefault(emptyList())
            AppLog.event("playlist WEB_REMIX fallback id=$normalizedId items=${remixItems.size}")
            items += remixItems
        }
        if (items.isEmpty()) {
            val html = get("$origin/playlist?list=${URLEncoder.encode(normalizedId, "UTF-8")}&hl=en")
            val htmlData = runCatching { initialData(html) }
                .onFailure { AppLog.failure("playlist authenticated HTML id=$normalizedId", it) }
                .getOrNull()
            if (htmlData != null) {
                page = htmlData
                editable = editable || findBoolean(htmlData, "isEditable") == true
                deletable = deletable || (findBoolean(htmlData, "isEditable") == true && normalizedId.startsWith("PL"))
                items = parsePlaylistVideos(htmlData).toMutableList()
                AppLog.event("playlist authenticated HTML id=$normalizedId bytes=${html.length} items=${items.size} continuation=${findPlaylistContinuationToken(page) != null}")
            }
        }
        while (seen.size < 20) {
            val token = findPlaylistContinuationToken(page) ?: break
            if (!seen.add(token)) break
            page = post("$origin/youtubei/v1/browse?key=${bootstrap.key}", JSONObject().put("context", context("WEB", bootstrap.version)).put("continuation", token), origin)
            val more = parsePlaylistVideos(page)
            AppLog.event("playlist continuation id=$normalizedId page=${seen.size} items=${more.size}")
            items += more
        }
        // Watch Later is editable even when the current renderer generation omits the
        // old top-level isEditable bit. Only expose row removal when YouTube supplied
        // the membership token required by ACTION_REMOVE_VIDEO.
        editable = editable || normalizedId in accountPlaylistIds || (normalizedId == "WL" && items.any { it.setVideoId.isNotBlank() })
        deletable = deletable || (editable && normalizedId.startsWith("PL"))
        return PlaylistPage(items.distinctBy { item -> item.setVideoId.ifBlank { item.id } }, editable, deletable)
    }

    private fun parsePlaylistVideos(root: Any?): List<FeedItem> {
        val output = linkedMapOf<String, FeedItem>()
        walkFeedRenderers(root) { renderer ->
            val type = renderer.optString("_materialytRendererType")
            val parsed = parseItems(JSONObject().put(type, renderer)).firstOrNull { !it.playlist && !it.channel && it.id.length == 11 }
                ?: return@walkFeedRenderers
            val short = type == "reelItemRenderer" || renderer.optString("contentType") == "LOCKUP_CONTENT_TYPE_SHORT" ||
                findStringNearKey(renderer, "url", "/shorts/") != null || findObject(renderer, "reelWatchEndpoint") != null ||
                findStringNearKey(renderer, "webPageType", "SHORTS") != null || findStringNearKey(renderer, "iconType", "SHORTS") != null
            val candidate = parsed.copy(
                short = short,
                setVideoId = renderer.optString("setVideoId")
                    .ifBlank { findString(renderer, "setVideoId").orEmpty() }
                    .ifBlank { findString(renderer, "playlistSetVideoId").orEmpty() },
                url = if (short) "https://www.youtube.com/shorts/${parsed.id}" else parsed.url,
            )
            val previous = output[parsed.id]
            if (previous == null || previous.setVideoId.isBlank() && candidate.setVideoId.isNotBlank()) output[parsed.id] = candidate
        }
        return output.values.toList()
    }

    private fun findPlaylistContinuationToken(value: Any?): String? = when (value) {
        is JSONObject -> {
            value.optJSONObject("continuationItemRenderer")
                ?.optJSONObject("continuationEndpoint")
                ?.let { endpoint ->
                    endpoint.optJSONObject("continuationCommand")?.optString("token")
                        ?.takeIf(String::isNotBlank)
                        ?: endpoint.optJSONObject("commandExecutorCommand")
                            ?.let { findString(it, "token") }
                }
                ?: value.keys().asSequence().mapNotNull { findPlaylistContinuationToken(value.opt(it)) }.firstOrNull()
        }
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findPlaylistContinuationToken(value.opt(it)) }.firstOrNull()
        else -> null
    }
    fun search(query: String): List<FeedItem> {
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        val data = post("$origin/youtubei/v1/search?key=${bootstrap.key}", JSONObject()
            .put("context", context("WEB", bootstrap.version)).put("query", query), origin)
        activeSearchQuery = query
        searchToken = findContinuationToken(data)
        return parseItems(data)
    }

    fun searchContinuation(query: String): List<FeedItem> {
        if (activeSearchQuery != query) return emptyList()
        val token = searchToken ?: return emptyList()
        return try {
            val origin = "https://www.youtube.com"
            val bootstrap = bootstrap(origin, "WEB")
            val data = post("$origin/youtubei/v1/search?key=${bootstrap.key}", JSONObject()
                .put("context", context("WEB", bootstrap.version)).put("continuation", token), origin)
            val next = findContinuationToken(data)
            searchToken = if (next != token) next else null
            parseItems(data).distinctBy { it.id }
        } catch (error: Throwable) {
            AppLog.failure("searchContinuation", error)
            emptyList()
        }
    }

    fun channelPosts(channelUrl: String): List<CommunityPost> {
        val pageUrl = channelUrl.trimEnd('/') + "/posts?hl=en"
        val data = initialData(get(pageUrl))
        val posts = linkedMapOf<String, CommunityPost>()
        fun collect(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val renderer = value.optJSONObject("backstagePostRenderer") ?: value.optJSONObject("postRenderer")
                    if (renderer != null) {
                        fun nestedText(node: Any?, keys: Set<String>): String? = when (node) {
                            is JSONObject -> node.keys().asSequence().mapNotNull { key ->
                                if (key in keys) text(node.opt(key))?.takeIf { it.isNotBlank() } else nestedText(node.opt(key), keys)
                            }.firstOrNull()
                            is JSONArray -> (0 until node.length()).asSequence().mapNotNull { nestedText(node.opt(it), keys) }.firstOrNull()
                            else -> null
                        }
                        fun firstText(node: Any?): String? = when (node) {
                            is JSONObject -> text(node)?.takeIf { it.isNotBlank() }
                                ?: node.keys().asSequence().mapNotNull { firstText(node.opt(it)) }.firstOrNull()
                            is JSONArray -> (0 until node.length()).asSequence().mapNotNull { firstText(node.opt(it)) }.firstOrNull()
                            else -> null
                        }
                        fun buttonText(node: Any?, keys: Set<String>): String? = when (node) {
                            is JSONObject -> node.keys().asSequence().mapNotNull { key ->
                                if (key in keys) firstText(node.opt(key)) else buttonText(node.opt(key), keys)
                            }.firstOrNull()
                            is JSONArray -> (0 until node.length()).asSequence().mapNotNull { buttonText(node.opt(it), keys) }.firstOrNull()
                            else -> null
                        }
                        val content = text(renderer.opt("contentText")) ?: text(renderer.opt("text")).orEmpty()
                        val id = renderer.optString("postId").ifBlank { content.hashCode().toString() }
                        val commentCount = nestedText(renderer, setOf("replyCount", "replyCountText", "commentCount", "commentCountText"))
                            ?: buttonText(renderer, setOf("replyButton", "commentButton"))
                        val attachment = renderer.opt("backstageAttachment")
                            ?: renderer.opt("attachment")
                        if (content.isNotBlank()) posts.putIfAbsent(id, CommunityPost(
                            id = id,
                            text = content,
                            published = englishRelativeTime(text(renderer.opt("publishedTimeText")).orEmpty()).ifBlank { "Date unavailable" },
                            likes = englishCountLabel(nestedText(renderer, setOf("voteCount", "likeCount", "likeCountText")).orEmpty(), "likes", "0"),
                            comments = englishCountLabel(commentCount.orEmpty(), "comments", "0"),
                            // Do not recursively scan the whole renderer here: the first image is
                            // commonly the author's avatar, not the post attachment.
                            image = attachment?.let(::findLargestPostImage).orEmpty(),
                            avatar = (renderer.opt("authorThumbnail") ?: renderer.opt("authorPhoto"))
                                ?.let(::findLargestAvatar).orEmpty(),
                        ))
                    }
                    value.keys().forEach { collect(value.opt(it)) }
                }
                is JSONArray -> for (index in 0 until value.length()) collect(value.opt(index))
            }
        }
        collect(data)
        return posts.values.toList()
    }

    fun channelPage(channelUrl: String, tab: String = "videos"): NativeChannelPage {
        val base = channelUrl.substringBefore('?').trimEnd('/').removeSuffix("/videos").removeSuffix("/shorts")
        val pageUrl = "$base/${if (tab == "shorts") "shorts" else "videos"}?hl=en"
        val root = initialData(get(pageUrl))
        val metadata = findObject(root, "channelMetadataRenderer")?.optJSONObject("channelMetadataRenderer")
        val header = findObject(root, "pageHeaderRenderer")?.optJSONObject("pageHeaderRenderer")
            ?: findObject(root, "c4TabbedHeaderRenderer")?.optJSONObject("c4TabbedHeaderRenderer")
        val id = metadata?.optString("externalId").orEmpty()
            .ifBlank { findString(root, "channelId").orEmpty() }
        val name = metadata?.optString("title").orEmpty()
            .ifBlank { text(header?.opt("title")) ?: "YouTube channel" }
        val avatar = metadata?.optJSONObject("avatar")?.let(::findLargestAvatar).orEmpty()
            .ifBlank { header?.let(::findLargestAvatar).orEmpty() }
        val banner = findObject(root, "banner")?.let(::findLargestAvatar).orEmpty()
            .ifBlank { findObject(root, "bannerImage")?.let(::findLargestAvatar).orEmpty() }
        val description = metadata?.optString("description").orEmpty()
        val subscribers = findString(root, "subscriberCountText").orEmpty()
            .ifBlank { text(findObject(root, "subscriberCountText")) ?: "" }
        val items = parseItems(root).filter { !it.channel && !it.playlist }.map { item ->
            item.copy(
                subtitle = item.subtitle.takeUnless { it == "Unknown channel" }.orEmpty().ifBlank { name },
                channelThumbnail = item.channelThumbnail.ifBlank { avatar },
                channelUrl = base,
                short = tab == "shorts",
                url = if (tab == "shorts") "https://www.youtube.com/shorts/${item.id}" else item.url,
            )
        }.distinctBy { it.id }
        return NativeChannelPage(id, name, avatar, banner, description, subscribers, items)
    }

    fun communityPostComments(postId: String): List<CommunityComment> = communityPostThread(postId).comments

    fun communityPostThread(postId: String): CommunityPostThread {
        val postUrl = "https://www.youtube.com/post/$postId"
        val authenticatedData = initialData(get(postUrl))
        var createCommentParams = findString(authenticatedData, "createCommentParams")
        // Public and signed-in post pages issue different continuation tokens. Reusing the
        // signed-in token without account headers still requests the entity-less response.
        val publicHtml = getPublicHtml("$postUrl?hl=en")
        var data = initialData(publicHtml)
        val publicKey = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)").find(publicHtml)?.groupValues?.get(1)
            ?: error("Unable to discover YouTube's public API key")
        val publicVersion = Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)").find(publicHtml)?.groupValues?.get(1)
            ?: error("Unable to discover YouTube's public client version")
        val comments = linkedMapOf<String, CommunityComment>()
        val signedCommentActions = mutableMapOf<String, VideoComment>()
        fun collectParsed(value: JSONObject) {
            // A Community BROWSE response already carries complete comments in entity
            // mutations. Parse those directly: authenticated responses do not always keep the
            // commentViewModel -> entityKey association used by watch-page comment rendering.
            var directItems = 0
            var entityCandidates = 0
            var firstEntityShape: String? = null
            fun scalar(node: Any?, key: String): String? = when (node) {
                is JSONObject -> {
                    (node.opt(key) as? String)?.takeIf(String::isNotBlank)
                        ?: node.keys().asSequence().mapNotNull { scalar(node.opt(it), key) }.firstOrNull()
                }
                is JSONArray -> (0 until node.length()).asSequence().mapNotNull { scalar(node.opt(it), key) }.firstOrNull()
                is String -> node.trim().takeIf { it.startsWith("{") || it.startsWith("[") }?.let { raw ->
                    runCatching {
                        if (raw.startsWith("{")) scalar(JSONObject(raw), key) else scalar(JSONArray(raw), key)
                    }.getOrNull()
                }
                else -> null
            }
            fun addEntity(entity: JSONObject) {
                entityCandidates++
                val properties = entity.opt("properties")
                val author = entity.opt("author")
                val toolbar = entity.opt("toolbar")
                val id = scalar(properties, "commentId").orEmpty()
                val body = scalar(properties, "content").orEmpty()
                if (firstEntityShape == null) firstEntityShape = "entityKeys=${entity.keys().asSequence().joinToString(",")} properties=${properties?.javaClass?.simpleName} author=${author?.javaClass?.simpleName} toolbar=${toolbar?.javaClass?.simpleName} id=${id.length} body=${body.length}"
                if (id.isBlank() || body.isBlank()) return
                comments[id] = CommunityComment(
                    id = id,
                    author = scalar(author, "displayName")
                        ?: scalar(properties, "authorButtonA11y")
                        ?: "YouTube user",
                    text = body,
                    published = scalar(properties, "publishedTime").orEmpty(),
                    likes = scalar(toolbar, "likeCountNotliked")
                        ?: scalar(toolbar, "likeCountA11y").orEmpty(),
                    avatar = highResolutionAvatarUrl(scalar(author, "avatarThumbnailUrl").orEmpty()),
                )
                directItems++
            }
            fun visit(node: Any?) {
                when (node) {
                    is JSONObject -> {
                        when (val rawEntity = node.opt("commentEntityPayload")) {
                            is JSONObject -> addEntity(rawEntity)
                            is String -> runCatching { JSONObject(rawEntity) }.getOrNull()?.let(::addEntity)
                        }
                        node.keys().forEach { visit(node.opt(it)) }
                    }
                    is JSONArray -> for (index in 0 until node.length()) visit(node.opt(index))
                }
            }
            visit(value)
            val parsed = parseVideoCommentPage(value)
            parsed.items.forEach { comment ->
                val actions = signedCommentActions[comment.id]
                val rendered = CommunityComment(
                    comment.id, comment.author, comment.text, comment.published, comment.likes,
                    highResolutionAvatarUrl(comment.avatar), maxOf(comment.replyCount, actions?.replyCount ?: 0),
                    comment.repliesContinuation ?: actions?.repliesContinuation,
                    comment.createReplyParams ?: actions?.createReplyParams,
                    (actions?.isOwn == true) || comment.isOwn,
                    actions?.updateParams ?: comment.updateParams,
                    actions?.updateIsReply ?: comment.updateIsReply,
                    actions?.updateApiUrl ?: comment.updateApiUrl,
                    actions?.deleteParams ?: comment.deleteParams,
                )
                val entity = comments[comment.id]
                comments[comment.id] = if (entity == null) rendered else entity.copy(
                    replyCount = rendered.replyCount,
                    repliesContinuation = rendered.repliesContinuation,
                    createReplyParams = rendered.createReplyParams,
                    isOwn = rendered.isOwn,
                    updateParams = rendered.updateParams,
                    updateIsReply = rendered.updateIsReply,
                    updateApiUrl = rendered.updateApiUrl,
                    deleteParams = rendered.deleteParams,
                )
            }
            createCommentParams = createCommentParams ?: parsed.createCommentParams
            AppLog.event("community comment payloads candidates=$entityCandidates direct=$directItems rendered=${parsed.items.size} total=${comments.size} shape=${firstEntityShape ?: "none"}")
        }
        // Community post pages use the same comments-section bootstrap and current entity
        // renderers as watch pages. Using arbitrary page continuations can select a post/feed
        // continuation instead, yielding empty comments or author-less placeholder entities.
        val initialPublicContinuation = findPostCommentsContinuation(data)?.token
        var continuation = initialPublicContinuation
        if (YouTubeRepository.signedIn()) {
            initialPublicContinuation?.let { signedToken ->
                val signedBootstrap = bootstrap("https://www.youtube.com", "WEB")
                ensureSignedInCommentChannelId(signedBootstrap.origin, signedBootstrap)
                val signedPage = runCatching {
                    post("https://www.youtube.com/youtubei/v1/browse?key=${signedBootstrap.key}",
                        JSONObject().put("context", context("WEB", signedBootstrap.version)).put("continuation", signedToken), signedBootstrap.origin)
                }.getOrNull()
                if (signedPage != null) {
                    createCommentParams = createCommentParams ?: findString(signedPage, "createCommentParams")
                    val signedItems = parseVideoCommentPage(signedPage).items
                    val soleReplyParams = findString(signedPage, "createReplyParams").takeIf { signedItems.size == 1 }
                    signedItems.forEach { signedCommentActions[it.id] = if (it.createReplyParams == null) it.copy(createReplyParams = soleReplyParams) else it }
                    AppLog.event("community signed comment actions items=${signedCommentActions.size} replyable=${signedCommentActions.values.count { it.createReplyParams != null }} own=${signedCommentActions.values.count { it.isOwn }} editable=${signedCommentActions.values.count { it.updateParams != null }} deletable=${signedCommentActions.values.count { it.deleteParams != null }}")
                }
            }
        }
        val seen = mutableSetOf<String>()
        repeat(4) { page ->
            val token = continuation ?: return@repeat
            if (!seen.add(token)) return@repeat
            // Community post continuations are CONTINUATION_REQUEST_TYPE_BROWSE. Unlike
            // watch-page comments, sending this token to /next returns no usable thread.
            AppLog.event("community comments post=$postId page=$page endpoint=/youtubei/v1/browse")
            // Signed-in BROWSE currently returns commentViewModels without their backing
            // commentEntityPayload mutations. Read this public thread without account headers;
            // writes still use the authenticated createCommentParams path below.
            val publicContext = JSONObject().put("client", JSONObject()
                .put("clientName", "WEB").put("clientVersion", publicVersion)
                .put("hl", "en").put("gl", "US"))
            data = postPublicCommunity("https://www.youtube.com/youtubei/v1/browse?key=$publicKey",
                JSONObject().put("context", publicContext).put("continuation", token), publicVersion, postUrl)
            createCommentParams = createCommentParams ?: findString(data, "createCommentParams")
            collectParsed(data)
            continuation = parseVideoCommentPage(data).continuation
        }
        // Authenticated BROWSE can observe a just-created comment before the public thread
        // cache does. Keep those signed-only rows so optimistic UI entries can be reconciled
        // immediately with their real ids and mutation commands.
        signedCommentActions.values.forEach { signed ->
            if (signed.id !in comments && signed.text.isNotBlank()) comments[signed.id] = CommunityComment(
                signed.id, signed.author, signed.text, signed.published, signed.likes,
                highResolutionAvatarUrl(signed.avatar), signed.replyCount, signed.repliesContinuation,
                signed.createReplyParams, signed.isOwn, signed.updateParams, signed.updateIsReply,
                signed.updateApiUrl, signed.deleteParams,
            )
        }
        AppLog.event("community comments post=$postId items=${comments.size}")
        return CommunityPostThread(comments.values.toList(), createCommentParams)
    }

    fun postCommunityComment(params: String, text: String): CommunityComment {
        val comment = postCommentWithParams(params, text)
        val identity = if (comment.author == "You" || comment.avatar.isBlank()) runCatching { signedInChannelInfo() }.getOrNull() else null
        return CommunityComment(
            comment.id,
            identity?.name ?: comment.author,
            comment.text,
            comment.published,
            comment.likes,
            highResolutionAvatarUrl(identity?.thumbnail ?: comment.avatar),
            comment.replyCount,
            comment.repliesContinuation,
            comment.createReplyParams,
            true,
            comment.updateParams,
            comment.updateIsReply,
            comment.updateApiUrl,
            comment.deleteParams,
        )
    }

    fun communityRepliesPage(continuation: String): CommunityReplyPage {
        val publicHtml = getPublicHtml("https://www.youtube.com/?hl=en")
        val key = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)").find(publicHtml)?.groupValues?.get(1)
            ?: error("Unable to discover YouTube's public API key")
        val version = Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)").find(publicHtml)?.groupValues?.get(1)
            ?: error("Unable to discover YouTube's public client version")
        val publicContext = JSONObject().put("client", JSONObject()
            .put("clientName", "WEB").put("clientVersion", version)
            .put("hl", "en").put("gl", "US"))
        val response = postPublicCommunity(
            "https://www.youtube.com/youtubei/v1/browse?key=$key",
            JSONObject().put("context", publicContext).put("continuation", continuation),
            version,
            "https://www.youtube.com/",
        )
        val publicPage = parseVideoCommentPage(response)
        val signedBootstrap = bootstrap("https://www.youtube.com", "WEB")
        ensureSignedInCommentChannelId(signedBootstrap.origin, signedBootstrap)
        val signedResponse = if (signedIn()) runCatching {
            post(
                "https://www.youtube.com/youtubei/v1/browse?key=${signedBootstrap.key}",
                JSONObject().put("context", context("WEB", signedBootstrap.version)).put("continuation", continuation),
                signedBootstrap.origin,
            )
        }.getOrNull() else null
        val signedPage = signedResponse?.let(::parseVideoCommentPage)
        val signedItems = signedPage?.items.orEmpty().associateBy { it.id }
        val createReplyParams = signedResponse?.let { findString(it, "createReplyParams") }
        val publicIds = publicPage.items.mapTo(mutableSetOf()) { it.id }
        val sourceItems = if (publicPage.items.isNotEmpty()) {
            publicPage.items + signedPage?.items.orEmpty().filterNot { it.id in publicIds }
        } else signedPage?.items.orEmpty()
        val replies = sourceItems.map { comment ->
            val signed = signedItems[comment.id]
            CommunityComment(
                comment.id, comment.author, comment.text, comment.published, comment.likes,
                highResolutionAvatarUrl(comment.avatar), maxOf(comment.replyCount, signed?.replyCount ?: 0),
                comment.repliesContinuation ?: signed?.repliesContinuation,
                comment.createReplyParams ?: signed?.createReplyParams,
                (signed?.isOwn == true) || comment.isOwn,
                signed?.updateParams ?: comment.updateParams,
                signed?.updateIsReply ?: comment.updateIsReply,
                signed?.updateApiUrl ?: comment.updateApiUrl,
                signed?.deleteParams ?: comment.deleteParams,
            )
        }
        AppLog.event("community replies items=${replies.size} next=${publicPage.continuation != null || signedPage?.continuation != null} replyable=${createReplyParams != null} own=${replies.count { it.isOwn }} editable=${replies.count { it.updateParams != null }} deletable=${replies.count { it.deleteParams != null }}")
        return CommunityReplyPage(
            replies,
            publicPage.continuation ?: signedPage?.continuation,
            createReplyParams,
        )
    }

    fun postCommunityReply(params: String, text: String): CommunityComment {
        val reply = postCommentReply(params, text)
        val identity = if (reply.author == "You" || reply.avatar.isBlank()) runCatching { signedInChannelInfo() }.getOrNull() else null
        return CommunityComment(
            reply.id,
            identity?.name ?: reply.author,
            reply.text,
            reply.published,
            reply.likes,
            highResolutionAvatarUrl(identity?.thumbnail ?: reply.avatar),
            reply.replyCount,
            reply.repliesContinuation,
            reply.createReplyParams,
            true,
            reply.updateParams,
            reply.updateIsReply,
            reply.updateApiUrl,
            reply.deleteParams,
        )
    }

    fun updateCommunityComment(comment: CommunityComment, text: String): CommunityComment {
        val params = comment.updateParams ?: error("YouTube did not provide permission to edit this comment")
        val updated = updateComment(params, text, comment.updateIsReply, comment.updateApiUrl)
        return comment.copy(
            text = text.trim(),
            published = updated.published.ifBlank { comment.published },
            updateParams = updated.updateParams ?: comment.updateParams,
            updateIsReply = updated.updateIsReply || comment.updateIsReply,
            updateApiUrl = updated.updateApiUrl ?: comment.updateApiUrl,
            deleteParams = updated.deleteParams ?: comment.deleteParams,
        )
    }

    fun deleteCommunityComment(postId: String, comment: CommunityComment) {
        deleteComment("", VideoComment(
            id = comment.id,
            author = comment.author,
            text = comment.text,
            published = comment.published,
            likes = comment.likes,
            avatar = comment.avatar,
            isOwn = comment.isOwn,
            deleteParams = comment.deleteParams ?: deleteCommentParams(postId, comment.id),
        ))
    }

    private fun signedInChannelInfo(): ChannelInfo {
        signedInChannelInfo?.let { return it }
        val page = channelPage(ownChannelUrl())
        return ChannelInfo(page.name, page.avatar).also { signedInChannelInfo = it }
    }

    fun loadArtwork(url: String): android.graphics.Bitmap? {
        val normalized = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> return null
        }
        val request = Request.Builder().url(normalized)
            .header("User-Agent", UA)
            .header("Referer", "https://music.youtube.com/")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.byteStream()?.use(android.graphics.BitmapFactory::decodeStream)
        }
    }

    private data class InternalContinuation(val token: String, val apiPath: String)

    private fun continuationRequest(value: JSONObject): InternalContinuation? {
        val endpoint = value.optJSONObject("continuationEndpoint") ?: value
        val token = endpoint.optJSONObject("continuationCommand")?.optString("token").orEmpty()
        if (token.isBlank()) return null
        val apiPath = endpoint.optJSONObject("commandMetadata")?.optJSONObject("webCommandMetadata")
            ?.optString("apiUrl").orEmpty().takeIf { it.startsWith("/youtubei/") }
            ?: "/youtubei/v1/browse"
        return InternalContinuation(token, apiPath)
    }

    private fun findCommunityCommentsContinuation(value: Any?): InternalContinuation? = when (value) {
        is JSONObject -> {
            if (value.optString("targetId") == "comments-section" ||
                value.optString("sectionIdentifier") == "comment-item-section") {
                findObject(value, "continuationEndpoint")?.let(::continuationRequest)
            } else {
                value.optJSONObject("continuationEndpoint")?.let(::continuationRequest)
                    ?.takeIf { it.apiPath.contains("/next") }
                    ?: value.keys().asSequence().mapNotNull { findCommunityCommentsContinuation(value.opt(it)) }.firstOrNull()
            }
        }
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findCommunityCommentsContinuation(value.opt(it)) }.firstOrNull()
        else -> null
    }

    /**
     * Community post detail pages currently expose their initial comments continuation in an
     * itemSectionRenderer without the comments-section targetId used by watch pages. Select the
     * BROWSE endpoint from that section instead of applying the watch-page-only target check.
     */
    private fun findPostCommentsContinuation(value: Any?): InternalContinuation? = when (value) {
        is JSONObject -> {
            val section = value.optJSONObject("itemSectionRenderer")
            val items = section?.optJSONArray("contents")
            var match: InternalContinuation? = null
            if (items != null) for (index in 0 until items.length()) {
                val renderer = items.optJSONObject(index)?.optJSONObject("continuationItemRenderer") ?: continue
                val request = renderer.optJSONObject("continuationEndpoint")?.let(::continuationRequest)
                if (request?.apiPath?.contains("/browse") == true) {
                    match = request
                    break
                }
            }
            match ?: value.keys().asSequence().mapNotNull { findPostCommentsContinuation(value.opt(it)) }.firstOrNull()
        }
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findPostCommentsContinuation(value.opt(it)) }.firstOrNull()
        else -> null
    }

    private fun findTopLevelCommentsContinuation(value: Any?): InternalContinuation? {
        if (value is JSONObject) {
            val command = value.optJSONObject("reloadContinuationItemsCommand")
                ?: value.optJSONObject("appendContinuationItemsAction")
            val items = command?.optJSONArray("continuationItems")
            if (items != null) {
                for (index in items.length() - 1 downTo 0) {
                    items.optJSONObject(index)?.optJSONObject("continuationItemRenderer")
                        ?.optJSONObject("continuationEndpoint")?.let(::continuationRequest)?.let { return it }
                }
            }
            value.keys().forEach { findTopLevelCommentsContinuation(value.opt(it))?.let { return it } }
        } else if (value is JSONArray) {
            for (index in 0 until value.length()) findTopLevelCommentsContinuation(value.opt(index))?.let { return it }
        }
        return null
    }
    fun music(): List<FeedItem> {
        val home = musicHome().sections.flatMap { it.items }
        val playlists = runCatching {
            val origin = "https://music.youtube.com"
            val bootstrap = bootstrap(origin, "WEB_REMIX")
            parseItems(post("$origin/youtubei/v1/browse?key=${bootstrap.key}", JSONObject()
                .put("context", context("WEB_REMIX", bootstrap.version))
                .put("browseId", "FEmusic_liked_playlists"), origin))
        }
            .onFailure { AppLog.failure("music library playlists", it) }.getOrDefault(emptyList())
            .filter { it.playlist }
        activeMusicQuery = null
        return (playlists + home).distinctBy { "${it.playlist}:${it.id}" }
    }

    fun musicHome(category: MusicCategory? = null): MusicHomePage {
        AppLog.event("music home category=${category?.title ?: "All"} started")
        val origin = "https://music.youtube.com"
        val bootstrap = bootstrap(origin, "WEB_REMIX")
        val request = JSONObject().put("context", context("WEB_REMIX", bootstrap.version))
            .put("browseId", category?.browseId?.ifBlank { "FEmusic_home" } ?: "FEmusic_home")
        category?.params?.takeIf(String::isNotBlank)?.let { request.put("params", it) }
        val response = post("$origin/youtubei/v1/browse?key=${bootstrap.key}", request, origin)
        musicHomeToken = findNextContinuationToken(response, null)
        activeMusicQuery = null
        val categories = parseMusicCategories(response)
        val homeSections = parseMusicHomeSections(response).mapNotNull { section ->
            audioOnlyMusicItems(section.items).takeIf { it.isNotEmpty() }?.let { section.copy(items = it) }
        }.ifEmpty {
            audioOnlyMusicItems(parseItems(response)).takeIf { it.isNotEmpty() }
                ?.let { listOf(MusicHomeSection("For you", items = it)) }.orEmpty()
        }
        val historyItems = if (category == null && signedIn()) runCatching {
            val historyResponse = post("$origin/youtubei/v1/browse?key=${bootstrap.key}", JSONObject()
                .put("context", context("WEB_REMIX", bootstrap.version))
                .put("browseId", "FEmusic_history"), origin)
            parseItems(historyResponse)
                .filter { !it.playlist && !it.channel && !it.musicVideo && it.id.length == 11 }
                .distinctBy { it.id }
                .take(30)
        }.onFailure { AppLog.failure("music native history", it) }.getOrDefault(emptyList()) else emptyList()
        val sections = if (historyItems.isEmpty()) homeSections else {
            val listenAgainStrapline = homeSections.firstOrNull { it.title.contains("listen again", true) }?.strapline.orEmpty()
            listOf(MusicHomeSection("Listen Again", strapline = listenAgainStrapline, items = historyItems, horizontal = true)) +
                homeSections.filterNot {
                    it.title.contains("continue", true) || it.title.contains("listen again", true)
                }
        }
        musicHomeRadioSeed = sections.asReversed().asSequence().flatMap { it.items.asReversed().asSequence() }
            .firstOrNull { !it.playlist && !it.channel && it.id.length == 11 }?.id
        musicHomeRadioMode = false
        AppLog.event("music home categories=${categories.size} sections=${sections.size} items=${sections.sumOf { it.items.size }} nativeHistory=${historyItems.size}")
        return MusicHomePage(categories, sections, musicHomeToken != null || musicHomeRadioSeed != null)
    }

    fun musicHomeContinuation(): MusicHomePage {
        val origin = "https://music.youtube.com"
        val bootstrap = bootstrap(origin, "WEB_REMIX")
        val token = musicHomeToken
        val endpoint: String
        val body = JSONObject().put("context", context("WEB_REMIX", bootstrap.version))
        if (token != null) {
            endpoint = if (musicHomeRadioMode) "next" else "browse"
            body.put("continuation", token)
        } else {
            val seed = musicHomeRadioSeed ?: return MusicHomePage(emptyList(), emptyList())
            musicHomeRadioMode = true
            endpoint = "next"
            body.put("videoId", seed)
                .put("playlistId", "RDAMVM$seed")
                .put("enablePersistentPlaylistPanel", true)
                .put("isAudioOnly", true)
        }
        val response = post("$origin/youtubei/v1/$endpoint?key=${bootstrap.key}", body, origin)
        musicHomeToken = if (musicHomeRadioMode) findRadioContinuationToken(response, token)
            else findNextContinuationToken(response, token)
        var sections = parseMusicHomeSections(response).mapNotNull { section ->
            audioOnlyMusicItems(section.items).takeIf { it.isNotEmpty() }?.let { section.copy(items = it) }
        }
        if (sections.isEmpty()) {
            val seed = musicHomeRadioSeed
            val items = parseItems(response).filter { !it.playlist && !it.channel && !it.musicVideo && it.id.length == 11 && it.id != seed }
                .distinctBy { it.id }
            if (items.isNotEmpty()) sections = listOf(MusicHomeSection("More for you", items = items, horizontal = false))
        }
        sections.asReversed().asSequence().flatMap { it.items.asReversed().asSequence() }
            .firstOrNull { !it.playlist && !it.channel && it.id.length == 11 }?.id?.let { musicHomeRadioSeed = it }
        val hasMore = musicHomeToken != null || musicHomeRadioSeed != null
        AppLog.event("music home continuation endpoint=$endpoint radio=$musicHomeRadioMode sections=${sections.size} items=${sections.sumOf { it.items.size }} hasMore=$hasMore")
        return MusicHomePage(emptyList(), sections, hasMore)
    }

    private fun parseMusicCategories(root: Any): List<MusicCategory> {
        val result = linkedMapOf<String, MusicCategory>()
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val categoryButton = value.optJSONObject("musicNavigationButtonRenderer")
                    val categoryChip = value.optJSONObject("chipCloudChipRenderer")
                    (categoryButton ?: categoryChip)?.let { button ->
                        val title = text(button.opt("buttonText")).orEmpty()
                            .ifBlank { text(button.opt("text")).orEmpty() }.trim()
                        val endpoint = button.optJSONObject("clickCommand")?.optJSONObject("browseEndpoint")
                            ?: button.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                        val browseId = endpoint?.optString("browseId").orEmpty()
                        if (title.isNotBlank() && browseId.isNotBlank()) result[title] = MusicCategory(title, browseId, endpoint?.optString("params").orEmpty())
                    }
                    value.keys().forEach { visit(value.opt(it)) }
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
            }
        }
        visit(root)
        return result.values.toList()
    }

    private fun parseMusicHomeSections(root: Any): List<MusicHomeSection> {
        val result = mutableListOf<MusicHomeSection>()
        fun add(renderer: JSONObject, horizontal: Boolean) {
            val header = renderer.optJSONObject("header")
            val basic = header?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                ?: header?.optJSONObject("musicShelfHeaderRenderer")
            val title = text(basic?.opt("title")).orEmpty().trim()
            val strapline = text(basic?.opt("strapline")).orEmpty().trim()
            val contents = renderer.optJSONArray("contents") ?: renderer.optJSONArray("items") ?: JSONArray()
            val items = audioOnlyMusicItems(parseItems(contents)).distinctBy { "${it.playlist}:${it.channel}:${it.id}" }
            if (items.isNotEmpty()) result += MusicHomeSection(
                title.ifBlank { strapline.ifBlank { "For you" } },
                if (title.isBlank()) "" else strapline,
                items,
                horizontal,
                findContinuationToken(renderer),
            )
        }
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val carousel = value.optJSONObject("musicCarouselShelfRenderer")
                        ?: value.optJSONObject("musicImmersiveCarouselShelfRenderer")
                        ?: value.optJSONObject("gridRenderer")
                    val shelf = value.optJSONObject("musicShelfRenderer")
                    when {
                        carousel != null -> add(carousel, true)
                        shelf != null -> add(shelf, false)
                        else -> value.keys().forEach { visit(value.opt(it)) }
                    }
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
            }
        }
        visit(root)
        return result.distinctBy { section -> section.title to section.items.joinToString { it.id } }
    }

    fun musicUpNext(videoId: String): FeedItem? {
        val origin = "https://music.youtube.com"
        val bootstrap = bootstrap(origin, "WEB_REMIX")
        val response = post("$origin/youtubei/v1/next?key=${bootstrap.key}", JSONObject()
            .put("context", context("WEB_REMIX", bootstrap.version))
            .put("videoId", videoId)
            .put("playlistId", "RDAMVM$videoId")
            .put("enablePersistentPlaylistPanel", true)
            .put("isAudioOnly", true), origin)
        val candidates = parseItems(response).filter {
            it.id.length == 11 && it.id != videoId && !it.channel && !it.playlist && !it.musicVideo
        }
        AppLog.event("music up next response video=$videoId candidates=${candidates.size} ids=${candidates.take(4).joinToString { it.id }}")
        return candidates.firstOrNull()
    }

    fun musicSectionContinuation(token: String): MusicHomeSection {
        val origin = "https://music.youtube.com"
        val bootstrap = bootstrap(origin, "WEB_REMIX")
        val response = post("$origin/youtubei/v1/browse?key=${bootstrap.key}", JSONObject()
            .put("context", context("WEB_REMIX", bootstrap.version))
            .put("continuation", token), origin)
        val parsed = parseMusicHomeSections(response)
        val items = audioOnlyMusicItems(parsed.flatMap { it.items }.ifEmpty { parseItems(response) })
            .distinctBy { "${it.playlist}:${it.channel}:${it.id}" }
        val next = findNextContinuationToken(response, token)
        AppLog.event("music shelf continuation items=${items.size} hasMore=${next != null}")
        return MusicHomeSection("", items = items, horizontal = true, continuation = next)
    }

    fun musicPlaylistVideos(playlistId: String): List<FeedItem> {
        val origin = "https://music.youtube.com"
        val bootstrap = bootstrap(origin, "WEB_REMIX")
        val normalizedId = playlistId.removePrefix("VL")
        var page = post("$origin/youtubei/v1/browse?key=${bootstrap.key}", JSONObject()
            .put("context", context("WEB_REMIX", bootstrap.version))
            .put("browseId", "VL$normalizedId"), origin)
        fun shelf(response: JSONObject): JSONObject? =
            findObject(response, "musicPlaylistShelfRenderer")?.optJSONObject("musicPlaylistShelfRenderer")
                ?: findObject(response, "musicPlaylistShelfContinuation")?.optJSONObject("musicPlaylistShelfContinuation")
        fun tracks(response: JSONObject): List<FeedItem> {
            return shelf(response)?.let(::parsePlaylistVideos).orEmpty()
        }
        val output = tracks(page).toMutableList()
        val seen = mutableSetOf<String>()
        while (seen.size < 20) {
            val token = shelf(page)?.let(::findPlaylistContinuationToken) ?: break
            if (!seen.add(token)) break
            page = post("$origin/youtubei/v1/browse?key=${bootstrap.key}", JSONObject()
                .put("context", context("WEB_REMIX", bootstrap.version))
                .put("continuation", token), origin)
            output += tracks(page)
        }
        return output.filterNot { it.musicVideo }.distinctBy { it.id }
    }

    fun searchSuggestions(query: String, music: Boolean = false): List<String> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        if (music && signedIn()) return musicSearchSuggestions(clean).map { it.text }.distinct().take(12)
        if (signedIn()) {
            authenticatedYouTubeSuggestions(clean).takeIf { it.isNotEmpty() }?.let { return it.take(12) }
        }
        val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=${URLEncoder.encode(clean, "UTF-8")}"
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val root = JSONArray(response.body?.string().orEmpty())
            val values = root.optJSONArray(1) ?: return emptyList()
            (0 until values.length()).mapNotNull { index ->
                when (val value = values.opt(index)) {
                    is String -> value.trim().takeIf(String::isNotBlank)
                    is JSONArray -> value.optString(0).trim().takeIf(String::isNotBlank)
                    else -> null
                }
            }.distinct().take(12)
        }
    }

    fun accountSearchHistory(music: Boolean): List<AccountSearchEntry> {
        if (!signedIn()) return emptyList()
        if (music) return musicSearchSuggestions("").filter { it.feedbackToken != null }.distinctBy { it.text.lowercase() }

        googleActivitySearchHistory().takeIf { it.isNotEmpty() }?.let { return it }

        val fromHistoryPage = runCatching {
            val root = initialData(get("https://www.youtube.com/feed/history/search_history?hl=en"))
            val entries = linkedMapOf<String, AccountSearchEntry>()
            fun collect(value: Any?) {
                when (value) {
                    is JSONObject -> {
                        val renderer = value.optJSONObject("searchHistoryQueryRenderer")
                            ?: value.optJSONObject("historySuggestionRenderer")
                        if (renderer != null) {
                            val query = renderer.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("searchEndpoint")?.optString("query").orEmpty()
                                .ifBlank { text(renderer.opt("query")) ?: text(renderer.opt("suggestion")).orEmpty() }
                                .trim()
                            if (query.isNotBlank()) entries.putIfAbsent(query.lowercase(), AccountSearchEntry(query, findString(renderer, "feedbackToken")))
                        }
                        value.keys().forEach { collect(value.opt(it)) }
                    }
                    is JSONArray -> for (index in 0 until value.length()) collect(value.opt(index))
                }
            }
            collect(root)
            entries.values.toList()
        }.getOrDefault(emptyList())
        if (fromHistoryPage.isNotEmpty()) return fromHistoryPage
        return emptyList()
    }

    private fun googleActivitySearchHistory(): List<AccountSearchEntry> {
        val latch = CountDownLatch(1)
        val result = AtomicReference<List<AccountSearchEntry>>(emptyList())
        Handler(Looper.getMainLooper()).post {
            val webView = WebView(MaterialYtApp.instance)
            var finished = false
            fun complete(entries: List<AccountSearchEntry>) {
                if (finished) return
                finished = true
                result.set(entries)
                runCatching { webView.stopLoading(); webView.destroy() }
                latch.countDown()
            }
            val timeout = Runnable { complete(emptyList()) }
            Handler(Looper.getMainLooper()).postDelayed(timeout, 15_000)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = UA
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (finished) return@postDelayed
                        val script = """
                            (() => {
                              const out = [];
                              const add = value => { value = (value || '').trim(); if (value && !out.includes(value)) out.push(value); };
                              for (const anchor of document.querySelectorAll('a[href]')) {
                                let href = anchor.href || '';
                                try { href = decodeURIComponent(href); } catch (_) {}
                                const match = href.match(/[?&]search_query=([^&#]+)/);
                                if (match) { try { add(decodeURIComponent(match[1].replace(/\+/g, ' '))); } catch (_) { add(match[1]); } }
                              }
                              for (const line of (document.body?.innerText || '').split(/\n+/)) {
                                const match = line.trim().match(/^Searched for\s+(.+)$/i);
                                if (match) add(match[1]);
                              }
                              return JSON.stringify(out.slice(0, 30));
                            })()
                        """.trimIndent()
                        view.evaluateJavascript(script) { encoded ->
                            val entries = runCatching {
                                val jsonText = org.json.JSONTokener(encoded).nextValue() as? String ?: "[]"
                                val array = JSONArray(jsonText)
                                (0 until array.length()).mapNotNull { index ->
                                    array.optString(index).trim().takeIf(String::isNotBlank)?.let { AccountSearchEntry(it) }
                                }.distinctBy { it.text.lowercase() }
                            }.getOrDefault(emptyList())
                            AppLog.event("Google Activity search history url=${url.substringBefore('?')} items=${entries.size}")
                            complete(entries)
                        }
                    }, 2_500)
                }
            }
            webView.loadUrl("https://myactivity.google.com/product/youtube?restrict=search&hl=en")
        }
        latch.await(16, TimeUnit.SECONDS)
        return result.get()
    }

    fun removeSearchHistoryEntry(entry: AccountSearchEntry): Boolean {
        val token = entry.feedbackToken ?: return false
        val origin = "https://music.youtube.com"
        val bootstrap = bootstrap(origin, "WEB_REMIX")
        val response = post("$origin/youtubei/v1/feedback?key=${bootstrap.key}", JSONObject()
            .put("context", context("WEB_REMIX", bootstrap.version))
            .put("feedbackTokens", JSONArray().put(token)), origin)
        return response.optJSONArray("feedbackResponses")?.optJSONObject(0)?.optBoolean("isProcessed", true) != false
    }

    fun clearAccountSearchHistory(): Boolean {
        require(signedIn()) { "Sign in again to clear search history" }
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        post("$origin/youtubei/v1/history/clear_search_history?key=${bootstrap.key}", JSONObject()
            .put("context", context("WEB", bootstrap.version)), origin)
        return true
    }

    private fun musicSearchSuggestions(query: String): List<AccountSearchEntry> {
        val origin = "https://music.youtube.com"
        val bootstrap = bootstrap(origin, "WEB_REMIX")
        val response = post("$origin/youtubei/v1/music/get_search_suggestions?key=${bootstrap.key}", JSONObject()
            .put("context", context("WEB_REMIX", bootstrap.version)).put("input", query), origin)
        val output = mutableListOf<AccountSearchEntry>()
        fun collect(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val renderer = value.optJSONObject("historySuggestionRenderer")
                        ?: value.optJSONObject("searchSuggestionRenderer")?.takeIf { query.isNotBlank() }
                    if (renderer != null) {
                        val text = renderer.optJSONObject("navigationEndpoint")?.optJSONObject("searchEndpoint")
                            ?.optString("query").orEmpty().ifBlank { text(renderer.opt("suggestion")).orEmpty() }.trim()
                        if (text.isNotBlank()) output += AccountSearchEntry(text, renderer.optJSONObject("serviceEndpoint")
                            ?.optJSONObject("feedbackEndpoint")?.optString("feedbackToken")?.takeIf(String::isNotBlank))
                    }
                    value.keys().forEach { collect(value.opt(it)) }
                }
                is JSONArray -> for (index in 0 until value.length()) collect(value.opt(index))
            }
        }
        collect(response)
        return output.distinctBy { it.text.lowercase() }
    }

    private fun authenticatedYouTubeSuggestions(query: String): List<String> {
        val origin = "https://www.youtube.com"
        // Regular YouTube serves signed-in history suggestions from its native
        // suggestions service. Unlike YT Music, it has no equivalent public
        // /youtubei/v1/get_search_suggestions endpoint.
        val url = buildString {
            append("https://suggestqueries-clients6.youtube.com/complete/search")
            append("?client=youtube&gs_ri=youtube&gs_id=0&cp=0&ds=yt")
            append("&sugexp=").append(URLEncoder.encode(YOUTUBE_SUGGEST_EXPERIMENTS, "UTF-8"))
            append("&hl=en&gl=US&q=").append(URLEncoder.encode(query, "UTF-8"))
        }
        val request = Request.Builder().url(url).header("User-Agent", UA).apply {
            cookies(origin).takeIf(String::isNotBlank)?.let { header("Cookie", it) }
            header("Accept-Language", "en-US,en;q=0.9")
            header("Origin", origin)
            header("Referer", "$origin/")
        }.build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val raw = response.body?.string().orEmpty().trim()
            val start = raw.indexOf('[')
            val end = raw.lastIndexOf(']')
            if (start < 0 || end <= start) return emptyList()
            val root = JSONArray(raw.substring(start, end + 1))
            val values = root.optJSONArray(1) ?: return emptyList()
            (0 until values.length()).mapNotNull { index ->
                val value = values.opt(index)
                when (value) {
                    is String -> value
                    is JSONArray -> value.optString(0)
                    else -> null
                }?.trim()?.takeIf(String::isNotBlank)
            }.distinct().take(20).also {
                AppLog.event("account search suggestions queryBlank=${query.isBlank()} service=youtube items=${it.size}")
            }
        }
    }

    fun musicSearch(query: String): List<FeedItem> = audioOnlyMusicItems(musicRequest("search", JSONObject().put("query", query)))
        .also { activeMusicQuery = query }

    fun musicContinuation(query: String): List<FeedItem> {
        val searching = query.isNotBlank()
        if (searching && activeMusicQuery != query) return emptyList()
        val token = if (searching) musicSearchToken else musicHomeToken ?: return emptyList()
        if (token.isNullOrBlank()) return emptyList()
        val endpoint = if (searching) "search" else "browse"
        val origin = "https://music.youtube.com"
        val bootstrap = bootstrap(origin, "WEB_REMIX")
        return runCatching {
            val response = post("$origin/youtubei/v1/$endpoint?key=${bootstrap.key}", JSONObject()
                .put("context", context("WEB_REMIX", bootstrap.version)).put("continuation", token), origin)
            val next = findContinuationToken(response).takeIf { it != token }
            if (searching) musicSearchToken = next else musicHomeToken = next
            audioOnlyMusicItems(parseItems(response)).distinctBy { it.id }
        }.onFailure { AppLog.failure("music continuation", it) }.getOrDefault(emptyList())
    }

    private fun musicRequest(endpoint: String, fields: JSONObject): List<FeedItem> {
        AppLog.event("music endpoint=$endpoint started")
        val origin = "https://music.youtube.com"
        val bootstrap = bootstrap(origin, "WEB_REMIX")
        val body = fields.put("context", context("WEB_REMIX", bootstrap.version))
        val response = post("$origin/youtubei/v1/$endpoint?key=${bootstrap.key}", body, origin)
        val token = findContinuationToken(response)
        if (endpoint == "search") musicSearchToken = token else musicHomeToken = token
        return audioOnlyMusicItems(parseItems(response)).also { AppLog.event("music endpoint=$endpoint items=${it.size}") }
    }

    private fun feed(name: String, url: String): List<FeedItem> {
        AppLog.event("feed=$name started")
        val generation = beginFeedLoad(name)
        return try {
            val started = SystemClock.elapsedRealtime()
            val browseId = when (name) { "home" -> "FEwhat_to_watch"; "history" -> "FEhistory"; "playlists" -> "FEplaylist_aggregation"; else -> null }
            val bootstrap = bootstrap("https://www.youtube.com", "WEB", generation)
            val data = if (browseId != null) post("https://www.youtube.com/youtubei/v1/browse?key=${bootstrap.key}",
                JSONObject().put("context", context("WEB", bootstrap.version)).put("browseId", browseId), bootstrap.origin, generation)
            else initialData(get(url))
            var mode = if (browseId == null) "html" else "browse"
            val parseStarted = SystemClock.elapsedRealtime()
            var uniqueItems = parseItems(data).distinctBy { it.id }
            if (name == "history" && uniqueItems.isEmpty()) {
                if (findObject(data, "signInEndpoint") != null) {
                    error("Your YouTube session expired. Open Account and select Refresh sign-in.")
                }
                AppLog.event("feed=history browse returned no items; trying authenticated HTML")
                val historyHtml = get(url)
                AppLog.event(
                    "feed=history html length=${historyHtml.length} " +
                        "var=${historyHtml.contains("var ytInitialData")} " +
                        "windowSingle=${historyHtml.contains("window['ytInitialData']")} " +
                        "windowDouble=${historyHtml.contains("window[\"ytInitialData\"]")} " +
                        "jsonParse=${historyHtml.contains("ytInitialData'] = JSON.parse") || historyHtml.contains("ytInitialData\"] = JSON.parse")}"
                )
                uniqueItems = runCatching { parseItems(initialData(historyHtml)).distinctBy { it.id } }
                    .onFailure { AppLog.failure("feed=history html fallback", it) }
                    .getOrDefault(emptyList())
                if (uniqueItems.isEmpty() && (historyHtml.contains("signInEndpoint") || historyHtml.contains("Sign in"))) {
                    error("Your YouTube session expired. Open Account and select Refresh sign-in.")
                }
                mode = if (uniqueItems.isEmpty()) "empty" else "html-fallback"
            }
            AppLog.event("feed=$name success mode=$mode items=${uniqueItems.size} parseMs=${SystemClock.elapsedRealtime() - parseStarted} totalMs=${SystemClock.elapsedRealtime() - started}")
            uniqueItems
        } catch (error: Throwable) {
            AppLog.failure("feed=$name", error)
            throw error
        }
    }

    fun playerStreams(videoId: String, music: Boolean = false): List<PlayableStream> {
        runCatching { bootstrap("https://www.youtube.com/watch?v=$videoId", "WEB") }
            .onFailure { AppLog.failure("player account bootstrap video=$videoId", it) }
        var response = runCatching { webPoPlayer(videoId) }
            .onFailure { AppLog.failure("WEB PoToken player video=$videoId", it) }.getOrDefault(JSONObject())
        var clientUsed = "WEB_POT"
        var finalStatus = response.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        var firstError = response.optJSONObject("playabilityStatus")?.optString("reason").takeUnless { it.isNullOrBlank() } ?: finalStatus
        if (finalStatus != "OK") {
            response = runCatching { mwebPlayer(videoId) }.getOrDefault(JSONObject())
            clientUsed = "MWEB"
            finalStatus = response.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        }
        if (finalStatus != "OK") {
            response = runCatching { visionOsPlayer(videoId) }.getOrDefault(JSONObject())
            clientUsed = "VISIONOS"
            finalStatus = response.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        }
        if (finalStatus != "OK") {
            response = runCatching { iosPlayer(videoId) }.getOrDefault(JSONObject())
            clientUsed = "IOS"
            finalStatus = response.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        }
        if (finalStatus != "OK") {
            response = runCatching { androidPlayer(videoId) }.getOrDefault(JSONObject())
            clientUsed = "ANDROID"
            finalStatus = response.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        }

        fun streamScore(candidate: JSONObject): Int {
            if (candidate.optJSONObject("playabilityStatus")?.optString("status") != "OK") return -1
            val streaming = candidate.optJSONObject("streamingData") ?: return -1
            var playable = 0
            var audio = 0
            var maxHeight = 0
            listOf("formats", "adaptiveFormats").forEach { key ->
                streaming.optJSONArray(key)?.let { array ->
                    for (index in 0 until array.length()) array.optJSONObject(index)?.let { format ->
                        val hasUrl = format.optString("url").startsWith("http") || format.optString("signatureCipher").contains("url=")
                        if (hasUrl) {
                            playable++
                            if (format.optString("mimeType").startsWith("audio/")) audio++
                            maxHeight = maxOf(maxHeight, format.optInt("height"))
                        }
                    }
                }
            }
            return playable * 10_000 + audio * 1_000 + maxHeight
        }

        var bestScore = streamScore(response)
        if (bestScore < 21_000) {
            listOf(
                "TV_DOWNGRADED" to { tvDowngradedPlayer(videoId) },
                "MWEB" to { mwebPlayer(videoId) },
                "VISIONOS" to { visionOsPlayer(videoId) },
                "IOS" to { iosPlayer(videoId) },
                "ANDROID" to { androidPlayer(videoId) }
            ).forEach { (name, request) ->
                val candidate = runCatching(request).onFailure { AppLog.failure("player candidate=$name video=$videoId", it) }.getOrNull() ?: return@forEach
                val score = streamScore(candidate)
                AppLog.event("player candidate=$name score=$score status=${candidate.optJSONObject("playabilityStatus")?.optString("status")} reason=${candidate.optJSONObject("playabilityStatus")?.optString("reason")} video=$videoId")
                if (score > bestScore) {
                    response = candidate
                    clientUsed = name
                    finalStatus = "OK"
                    bestScore = score
                }
            }
        }
        AppLog.event("player client=$clientUsed status=$finalStatus video=$videoId")

        val cpn = response.optString("_materialytCpn").takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val trackingAsMusic = music
        val authenticatedTracking = runCatching { authenticatedPlayerTracking(videoId, cpn, trackingAsMusic) }
            .onFailure { AppLog.failure("authenticated tracking video=$videoId music=$music", it) }.getOrNull()
        (authenticatedTracking ?: response.optJSONObject("playbackTracking"))?.let { tracking ->
            val playback = tracking.optJSONObject("videostatsPlaybackUrl")?.optString("baseUrl").orEmpty()
            val watchtime = tracking.optJSONObject("videostatsWatchtimeUrl")?.optString("baseUrl").orEmpty()
            if (playback.isNotBlank() && watchtime.isNotBlank()) synchronized(playbackTracking) { playbackTracking[videoId] = PlaybackTracking(playback, watchtime, cpn, trackingAsMusic) }
        }

        if (finalStatus != "OK") {
            error(firstError)
        }
        val detailsObj = response.optJSONObject("videoDetails")
        if (detailsObj != null) {
            val title = detailsObj.optString("title")
            val author = detailsObj.optString("author")
            val length = detailsObj.optString("lengthSeconds").toLongOrNull() ?: 0L
            val views = detailsObj.optString("viewCount").toLongOrNull() ?: 0L
            val channelId = detailsObj.optString("channelId")
            synchronized(videoDetailsCache) {
                videoDetailsCache[videoId] = VideoDetails(
                    title = title,
                    author = author,
                    authorUrl = if (channelId.isNotBlank()) "https://www.youtube.com/channel/$channelId" else "",
                    viewCount = views,
                    durationSeconds = length
                )
            }
        }
        val data = response.optJSONObject("streamingData")
        if (data == null) {
            error("YouTube returned no playable streams")
        }
        val formats = mutableListOf<JSONObject>()
        listOf("formats", "adaptiveFormats").forEach { key -> data.optJSONArray(key)?.let { array -> for (i in 0 until array.length()) array.optJSONObject(i)?.let(formats::add) } }
        // Android VR exposes the adaptive formats that WEB currently withholds, but its
        // googlevideo URLs must be transformed with a compatible TV-family player. Using
        // the WEB base.js here yields an invalid `n` value and an immediate CDN 403.
        val playerJsLazy by lazy {
            runCatching {
                if (clientUsed == "TV_DOWNGRADED") tvPlayerJavaScript() else playerJavaScript(videoId)
            }.onFailure { AppLog.failure("player JavaScript client=$clientUsed video=$videoId", it) }
                .getOrNull()
        }
        val streams = formats.mapNotNull { item ->
            val rawUrl = item.optString("url").takeIf { it.startsWith("http") } ?: run {
                val cipher = item.optString("signatureCipher").split('&').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { java.net.URLDecoder.decode(it[0], "UTF-8") to java.net.URLDecoder.decode(it[1], "UTF-8") } }.toMap()
                val baseUrl = cipher["url"] ?: return@run null
                val signature = cipher["s"]?.takeIf { it.isNotBlank() }?.let { sig ->
                    runCatching {
                        playerJsLazy?.let { js -> EjsChallengeSolver.solveSignature(js, sig) }
                    }.getOrNull()
                }
                if (signature == null) baseUrl else {
                    val separator = if ('?' in baseUrl) '&' else '?'
                    "$baseUrl$separator${cipher["sp"] ?: "signature"}=${java.net.URLEncoder.encode(signature, "UTF-8")}"
                }
            } ?: return@mapNotNull null
            val urlWithCpn = if (cpn.isBlank() || "cpn=" in rawUrl) rawUrl else "$rawUrl&cpn=$cpn"
            var streamUrl = runCatching {
                solveNChallenge(videoId, urlWithCpn, playerJsLazy, clientUsed == "TV_DOWNGRADED")
            }.getOrElse {
                AppLog.failure("player n parameter video=$videoId", it)
                rawUrl
            }
            response.optString("_materialytStreamingPot").takeIf { it.isNotBlank() && "pot=" !in streamUrl }?.let {
                streamUrl += "${if ('?' in streamUrl) '&' else '?'}pot=${java.net.URLEncoder.encode(it, "UTF-8")}"
            }
            val mime = item.optString("mimeType")
            val codec = Regex("codecs=\"([^\"]+)").find(mime)?.groupValues?.get(1).orEmpty()
            val audio = mime.startsWith("audio/")
            val audioTrack = item.optJSONObject("audioTrack")
            val trackName = audioTrack?.optString("displayName").orEmpty().ifBlank { audioTrack?.optString("id").orEmpty() }
            val originalAudio = trackName.contains("original", true) || audioTrack?.optBoolean("audioIsDefault", false) == true
            PlayableStream(streamUrl, codec, item.optInt("height"), item.optInt("fps"), item.optInt("bitrate"), audio, !audio && !item.has("audioQuality"), originalAudio, trackName, audioTrack?.optString("id").orEmpty())
        }
        AppLog.event("player streams=${streams.size} formats=${formats.size} video=$videoId")
        if (streams.isEmpty()) {
            error("YouTube returned only protected stream URLs")
        }
        return streams
    }

    /** Fetches a native WEB SABR session. Direct URLs are intentionally not required here. */
    fun sabrPlaybackInfo(videoId: String, music: Boolean = false): SabrPlaybackInfo {
        val webResponse = runCatching { webPoPlayer(videoId, remix = false) }
            .onFailure { AppLog.failure("SABR WEB player video=$videoId", it) }
            .getOrDefault(JSONObject())
        val webOk = webResponse.optJSONObject("playabilityStatus")?.optString("status") == "OK"
        if (webOk) AppLog.event("SABR player selected WEB video=$videoId")

        var visionResponse = if (webOk) JSONObject() else visionOsPlayer(videoId)
        var visionStatus = visionResponse.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        var visionReason = visionResponse.optJSONObject("playabilityStatus")?.optString("reason").orEmpty()
        if (!webOk && visionStatus != "OK") {
            AppLog.event("SABR VISIONOS first attempt status=$visionStatus reason=${visionReason.take(240)} video=$videoId")
            runCatching { visionOsPlayer(videoId) }.onSuccess { retry ->
                val retryStatus = retry.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                val retryReason = retry.optJSONObject("playabilityStatus")?.optString("reason").orEmpty()
                AppLog.event("SABR VISIONOS retry status=$retryStatus reason=${retryReason.take(240)} video=$videoId")
                if (retryStatus == "OK") {
                    visionResponse = retry
                    visionStatus = retryStatus
                    visionReason = retryReason
                }
            }.onFailure { AppLog.failure("SABR VISIONOS retry video=$videoId", it) }
        }
        val visionOk = visionStatus == "OK"
        val vrResponse = if (webOk || visionOk) JSONObject() else runCatching { androidVrPlayer(videoId) }
            .onFailure { AppLog.failure("SABR ANDROID_VR player video=$videoId", it) }
            .getOrDefault(JSONObject())
        val vrStatus = vrResponse.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        val vrOk = vrStatus == "OK"
        if (!webOk && !visionOk) AppLog.event(
            "SABR ANDROID_VR status=$vrStatus reason=${vrResponse.optJSONObject("playabilityStatus")?.optString("reason").orEmpty().take(240)} video=$videoId"
        )
        val nativeOk = visionOk || vrOk
        val remixResponse = if (nativeOk || webOk) JSONObject() else webPoPlayer(videoId, remix = true)
        val remixOk = remixResponse.optJSONObject("playabilityStatus")?.optString("status") == "OK"
        val direct = nativeOk
        val response = when {
            webOk -> webResponse
            visionOk -> visionResponse.also {
                // VISIONOS is a native client. WEB PoTokens are neither required nor valid in
                // its SABR StreamerContext and can make the post-reload config malformed.
                it.put("_materialytPlayerPot", "")
                    .put("_materialytStreamingPot", "")
                    .put("_materialytClientVersion", "1.03")
                AppLog.event("SABR player selected VISIONOS video=$videoId")
            }
            vrOk -> vrResponse.also {
                it.put("_materialytPlayerPot", "")
                    .put("_materialytStreamingPot", "")
                    .put("_materialytClientVersion", "1.65.10")
                AppLog.event("SABR player selected ANDROID_VR video=$videoId")
            }
            remixOk -> remixResponse
            else -> {
                val visionStatus = visionResponse.optJSONObject("playabilityStatus")
                val reason = visionStatus?.optString("reason").orEmpty()
                val status = visionStatus?.optString("status").orEmpty()
                error(reason.ifBlank { "YouTube VISIONOS SABR player returned $status" })
            }
        }
        PlaybackLoadStatus.show(videoId, "Validating stream configuration…")
        val streaming = response.optJSONObject("streamingData")
            ?: error("YouTube SABR player returned no streamingData")
        val unresolvedUrl = streaming.optString("serverAbrStreamingUrl")
            .takeIf(String::isNotBlank) ?: error("YouTube SABR player returned no streaming URL")
        val ustreamerConfig = findString(response, "videoPlaybackUstreamerConfig")
            ?.takeIf(String::isNotBlank) ?: error("YouTube SABR player returned no streaming configuration")
        val poToken = response.optString("_materialytStreamingPot").also {
            if (!direct && it.isBlank()) error("BotGuard returned no SABR streaming PoToken")
        }
        val playerPoToken = response.optString("_materialytPlayerPot").also {
            if (!direct && it.isBlank()) error("BotGuard returned no SABR player PoToken")
        }
        val cpn = response.optString("_materialytCpn")
            .takeIf(String::isNotBlank) ?: error("YouTube SABR player returned no playback nonce")
        PlaybackLoadStatus.show(videoId, "Preparing history tracking…")
        val accountTracking = runCatching { authenticatedPlayerTracking(videoId, cpn, music) }
            .onFailure { AppLog.failure("SABR authenticated tracking video=$videoId", it) }
            .getOrNull()
        cachePlaybackTracking(videoId, accountTracking ?: response.optJSONObject("playbackTracking"), cpn, music)
        val webClient = response.optString("_materialytClientName")
        if (!direct && !response.optBoolean("_materialytNResolved")) {
            PlaybackLoadStatus.show(videoId, "Solving media URL…")
        }
        val solvedUrl = if (direct || response.optBoolean("_materialytNResolved")) unresolvedUrl else solveNChallenge(
            videoId, unresolvedUrl,
            if (webClient == "WEB") playerJavaScript(videoId) else webRemixPlayerJavaScript(videoId),
            false,
        )
        // SABR's media endpoint validates the playback session independently from /player.
        // Match the browser request by carrying the same CPN and low-latency response flag.
        val serverUrl = solvedUrl.toHttpUrlOrNull()?.newBuilder()
            ?.setQueryParameter("alr", "yes")
            ?.setQueryParameter("cpn", cpn)
            ?.apply { if (!direct) setQueryParameter("pot", poToken) }
            ?.build()?.toString() ?: solvedUrl
        PlaybackLoadStatus.show(videoId, "Reading available formats…")
        val formats = mutableListOf<SabrFormat>()
        streaming.optJSONArray("adaptiveFormats")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val itag = item.optInt("itag")
                if (itag <= 0) continue
                val mime = item.optString("mimeType")
                val codec = Regex("codecs=\"([^\"]+)").find(mime)?.groupValues?.get(1).orEmpty()
                val audio = mime.startsWith("audio/")
                val audioTrack = item.optJSONObject("audioTrack")
                val trackName = audioTrack?.optString("displayName").orEmpty()
                    .ifBlank { audioTrack?.optString("id").orEmpty() }
                val contentLength = item.optString("contentLength").toLongOrNull() ?: 0L
                // The positional SABR reassembler needs a finite target length. Do not expose
                // live/unknown-length entries as selectable VOD qualities and crash at playback.
                if (contentLength <= 0L) continue
                formats += SabrFormat(
                    itag = itag,
                    mimeType = mime.substringBefore(';'),
                    codec = codec,
                    height = item.optInt("height"),
                    fps = item.optInt("fps"),
                    bitrate = item.optInt("bitrate"),
                    contentLength = contentLength,
                    lastModified = item.optString("lastModified").toLongOrNull() ?: 0L,
                    xtags = item.optString("xtags"),
                    audio = audio,
                    originalAudio = audioTrack?.optBoolean("audioIsDefault", false) == true
                        || trackName.contains("original", true),
                    audioTrackName = trackName,
                    audioTrackId = audioTrack?.optString("id").orEmpty()
                )
            }
        }
        if (formats.none { it.audio } || formats.none { !it.audio }) {
            error("YouTube SABR response did not expose separate audio and video formats")
        }
        val durationMs = response.optJSONObject("videoDetails")?.optString("lengthSeconds")
            ?.toLongOrNull()?.times(1_000L) ?: 0L
        response.optJSONObject("videoDetails")?.let { details ->
            val channelId = details.optString("channelId")
            val microformat = response.optJSONObject("microformat")?.optJSONObject("playerMicroformatRenderer")
            synchronized(videoDetailsCache) {
                videoDetailsCache[videoId] = VideoDetails(
                    title = details.optString("title").ifBlank { "Video" },
                    author = details.optString("author"),
                    authorUrl = channelId.takeIf(String::isNotBlank)
                        ?.let { "https://www.youtube.com/channel/$it" }.orEmpty(),
                    viewCount = details.optString("viewCount").toLongOrNull() ?: 0L,
                    durationSeconds = durationMs / 1_000L,
                    publishedDate = microformat?.optString("publishDate").orEmpty()
                        .ifBlank { microformat?.optString("uploadDate").orEmpty() },
                    description = details.optString("shortDescription"),
                )
            }
        }
        AppLog.event("SABR session ready video=$videoId formats=${formats.size}")
        val nativeVr = direct && response.optString("_materialytClientName") == "ANDROID_VR"
        return SabrPlaybackInfo(videoId, serverUrl, ustreamerConfig, playerPoToken, poToken,
            response.optString("_materialytClientVersion")
                .ifBlank { if (direct) "1.03" else bootstrap("https://music.youtube.com", "WEB_REMIX").version },
            if (nativeVr) "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
                else if (direct) VISIONOS_UA else WEB_PLAYER_UA,
            if (nativeVr) 28 else if (direct) 101 else if (webClient == "WEB") 1 else 67,
            if (nativeVr) "Android" else if (direct) "visionOS" else "Windows",
            if (nativeVr) "12" else if (direct) "26.6.1" else "10.0",
            if (nativeVr) "Oculus" else if (direct) "Apple" else null,
            if (nativeVr) "Quest 3" else if (direct) "RealityDevice17,1" else null,
            durationMs, formats)
    }

    private fun playerJavaScript(videoId: String): String {
        // The cipher must come from the same desktop WEB identity that issued the SABR URL.
        val watchHtml = get("https://www.youtube.com/watch?v=$videoId", WEB_PLAYER_UA)
        val encodedPlayerUrl = Regex("\\\"(?:jsUrl|PLAYER_JS_URL)\\\":\\\"([^\\\"]+)").find(watchHtml)?.groupValues?.get(1)
            ?: error("YouTube did not expose its player JavaScript URL")
        val playerPath = JSONObject("{\"value\":\"$encodedPlayerUrl\"}").getString("value")
        val playerUrl = if (playerPath.startsWith("http")) playerPath else "https://www.youtube.com$playerPath"
        return synchronized(playerJavaScriptCache) { playerJavaScriptCache[playerUrl] }
            ?: get(playerUrl, WEB_PLAYER_UA).also { synchronized(playerJavaScriptCache) { playerJavaScriptCache[playerUrl] = it } }
    }

    private fun webRemixPlayerJavaScript(videoId: String): String {
        val watchHtml = get("https://music.youtube.com/watch?v=$videoId", WEB_PLAYER_UA)
        val encodedPlayerUrl = Regex("\\\"(?:jsUrl|PLAYER_JS_URL)\\\":\\\"([^\\\"]+)").find(watchHtml)?.groupValues?.get(1)
            ?: error("YouTube Music did not expose its player JavaScript URL")
        val playerPath = JSONObject("{\"value\":\"$encodedPlayerUrl\"}").getString("value")
        val playerUrl = if (playerPath.startsWith("http")) playerPath else "https://music.youtube.com$playerPath"
        return synchronized(playerJavaScriptCache) { playerJavaScriptCache[playerUrl] }
            ?: get(playerUrl, WEB_PLAYER_UA).also { synchronized(playerJavaScriptCache) { playerJavaScriptCache[playerUrl] = it } }
    }

    private fun tvPlayerJavaScript(): String {
        // This is the current first validated common-n player used by MediaServiceCore.
        // Keep the lookup isolated from WEB player discovery: the n transform is tied to
        // the client family that issued the adaptive stream URLs.
        val playerUrl = "https://www.youtube.com/s/player/7460dd14/tv-player-es6.vflset/tv-player-es6.js"
        return synchronized(playerJavaScriptCache) { playerJavaScriptCache[playerUrl] }
            ?: get(playerUrl).also { synchronized(playerJavaScriptCache) { playerJavaScriptCache[playerUrl] = it } }
    }

    private fun tvDowngradedPlayer(videoId: String): JSONObject {
        val origin = "https://www.youtube.com"
        val version = "5.20260901"
        val userAgent = "Mozilla/5.0 (DirectFB; Linux x86_64) Cobalt/4.13031-qa (unlike Gecko) Starboard/1"
        val cpn = UUID.randomUUID().toString().replace("-", "").take(16)
        val clientContext = JSONObject()
            .put("clientName", "TVHTML5").put("clientVersion", version)
            .put("clientScreen", "WATCH").put("platform", "TV")
            .put("browserName", "Cobalt").put("browserVersion", "4.13031-qa")
            .put("userAgent", userAgent).put("hl", "en").put("gl", "US")
        val accountCookies = cookies(origin)
        if (accountCookies.isBlank()) visitorData[origin]?.let { clientContext.put("visitorData", it) }
        val body = JSONObject().put("context", JSONObject()
            .put("client", clientContext)
            .put("user", JSONObject().put("enableSafetyMode", false).put("lockedSafetyMode", false)))
            .put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true)
            .put("cpn", cpn)
        val request = Request.Builder()
            .url("$origin/youtubei/v1/player?prettyPrint=false&id=$videoId")
            .header("User-Agent", userAgent).header("Content-Type", "application/json")
            .header("Referer", "$origin/tv/watch#/watch?v=$videoId")
            .header("Origin", origin).header("X-Origin", origin)
            .header("X-Youtube-Client-Name", "7").header("X-Youtube-Client-Version", version)
            .apply {
                if (accountCookies.isNotBlank()) header("Cookie", accountCookies)
                authorization(origin)?.let { header("Authorization", it) }
                header("X-Goog-AuthUser", "0")
                header("X-Youtube-Bootstrap-Logged-In", accountCookies.isNotBlank().toString())
                if (accountCookies.isBlank()) visitorData[origin]?.let { header("X-Goog-Visitor-Id", it) }
            }
            .post(body.toString().toRequestBody(jsonType)).build()
        return client.newCall(request).execute().use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val detail = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                error("YouTube TV player returned HTTP ${it.code}${if (detail.isBlank()) "" else ": $detail"}")
            }
            JSONObject(raw).put("_materialytCpn", cpn)
        }
    }

    private fun solveNChallenge(videoId: String, streamingUrl: String, playerJs: String? = null, clientSpecific: Boolean = false): String {
        val parsed = streamingUrl.toHttpUrlOrNull() ?: return streamingUrl
        val challenge = parsed.queryParameter("n") ?: return streamingUrl
        val cacheKey = "${if (clientSpecific) playerJs?.hashCode() ?: "client" else "web"}:$challenge"
        synchronized(nChallengeCache) { nChallengeCache[cacheKey] }?.let { solved ->
            return parsed.newBuilder().setQueryParameter("n", solved).build().toString()
        }
        val js = playerJs ?: playerJavaScript(videoId)
        val solved = EjsChallengeSolver.solveN(js, challenge)
        check(solved.isNotBlank() && solved != challenge) { "EJS returned an unchanged n challenge" }
        synchronized(nChallengeCache) { nChallengeCache[cacheKey] = solved }
        return parsed.newBuilder().setQueryParameter("n", solved).build().toString()
    }

    fun reportPlayback(videoId: String, fromMs: Long, toMs: Long, paused: Boolean = false, ended: Boolean = false) {
        if (!signedIn() || toMs <= fromMs) return
        val tracking = synchronized(playbackTracking) { playbackTracking[videoId] }
        if (tracking == null) {
            AppLog.event("playback tracking skipped video=$videoId reason=no_session")
            return
        }
        val first = synchronized(tracking) { if (!tracking.started) { tracking.started = true; true } else false }
        val source = tracking.watchtimeUrl.toHttpUrlOrNull() ?: return
        val required = listOf("ei", "vm", "of")
        if (required.any { source.queryParameter(it).isNullOrBlank() }) {
            AppLog.event("playback tracking skipped video=$videoId reason=missing_${required.filter { source.queryParameter(it).isNullOrBlank() }.joinToString("_")}")
            return
        }
        val fromSec = "%.3f".format(java.util.Locale.US, fromMs / 1000.0)
        val toSec = "%.3f".format(java.util.Locale.US, toMs / 1000.0)
        fun statsUrl(playback: Boolean): okhttp3.HttpUrl {
            val rawBase = if (playback) tracking.playbackUrl else tracking.watchtimeUrl
            val base = (if (tracking.music) rawBase.replace("https://s.youtube.com/", "https://music.youtube.com/") else rawBase).toHttpUrlOrNull()
                ?: error("Invalid YouTube tracking URL")
            return base.newBuilder()
                .setQueryParameter("ns", "yt")
                .setQueryParameter("ver", "2")
                .setQueryParameter("docid", source.queryParameter("docid") ?: videoId)
                .setQueryParameter("cpn", tracking.cpn)
                .apply {
                    if (tracking.music) {
                        setQueryParameter("c", "WEB_REMIX")
                        setQueryParameter("cver", bootstrap("https://music.youtube.com", "WEB_REMIX").version)
                        setQueryParameter("cplayer", "UNIPLAYER")
                        setQueryParameter("cplatform", "DESKTOP")
                    }
                }
                .apply {
                    if (playback) {
                        setQueryParameter("cmt", fromSec)
                        setQueryParameter("rtn", "0")
                        setQueryParameter("rt", "0")
                        setQueryParameter("fs", "0")
                        setQueryParameter("state", "paused")
                        if (ended) setQueryParameter("final", "1")
                    }
                    else {
                        setQueryParameter("st", fromSec)
                        setQueryParameter("et", toSec)
                        setQueryParameter("cmt", toSec)
                        if (paused) setQueryParameter("state", "paused") else removeAllQueryParameters("state")
                        if (ended) setQueryParameter("final", "1") else removeAllQueryParameters("final")
                    }
                }.build()
        }
        fun send(url: okhttp3.HttpUrl) {
            val origin = if (tracking.music) "https://music.youtube.com" else "https://www.youtube.com"
            val request = Request.Builder().url(url).apply { authenticatedHeaders(origin).forEach { (key, value) -> header(key, value) } }.build()
            client.newCall(request).execute().use { response ->
                AppLog.event("playback tracking video=$videoId music=${tracking.music} kind=${if (url.encodedPath.contains("watchtime")) "watchtime" else "playback"} state=${if (ended) "ended" else if (paused) "paused" else "playing"} HTTP ${response.code}")
            }
        }
        if (first || ended) send(statsUrl(true))
        send(statsUrl(false))
    }

    fun preparePlaybackTracking(videoId: String, music: Boolean = false) {
        if (!signedIn()) return
        if (synchronized(playbackTracking) { playbackTracking[videoId]?.music == music }) return
        val cpn = UUID.randomUUID().toString().replace("-", "").take(16)
        val tracking = authenticatedPlayerTracking(videoId, cpn, music) ?: return
        if (cachePlaybackTracking(videoId, tracking, cpn, music)) {
            AppLog.event("tracking prepared for ${if (music) "YouTube Music" else "standard YouTube"} history video=$videoId")
        }
    }

    private fun cachePlaybackTracking(videoId: String, tracking: JSONObject?, cpn: String, music: Boolean = false): Boolean {
        val playback = tracking?.optJSONObject("videostatsPlaybackUrl")?.optString("baseUrl").orEmpty()
        val watchtime = tracking?.optJSONObject("videostatsWatchtimeUrl")?.optString("baseUrl").orEmpty()
        if (playback.isBlank() || watchtime.isBlank()) {
            AppLog.event("playback tracking unavailable video=$videoId")
            return false
        }
        synchronized(playbackTracking) {
            playbackTracking[videoId] = PlaybackTracking(playback, watchtime, cpn, music)
        }
        AppLog.event("playback tracking cached video=$videoId source=player_response")
        return true
    }

    private fun visionOsPlayer(videoId: String, reloadToken: String? = null, cpn: String? = null): JSONObject {
        return mobilePlayer(
            videoId, "VISIONOS", "1.03", "101", "RealityDevice17,1", "visionOS", "26.6.1",
            reloadToken, cpn,
        )
    }

    private fun mwebPlayer(videoId: String): JSONObject {
        val cpn = UUID.randomUUID().toString().replace("-", "").take(16)
        val clientContext = JSONObject()
            .put("clientName", "MWEB").put("clientVersion", "2.20260901.00.00")
            .put("deviceModel", "Pixel 8 Pro").put("osName", "Android")
            .put("osVersion", "14").put("platform", "MOBILE")
            .put("clientScreen", "WATCH").put("hl", "en").put("gl", "US")
            .apply { 
                val accountOrigin = "https://www.youtube.com"
                val accountCookies = cookies(accountOrigin)
                val loggedIn = accountCookies.isNotBlank()
                if (!loggedIn) visitorData[accountOrigin]?.let { put("visitorData", it) }
            }
        val body = JSONObject().put("context", JSONObject().put("client", clientContext))
            .put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true)
            .put("cpn", cpn)
        val request = Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false&id=$videoId")
            .header("User-Agent", UA).header("Content-Type", "application/json")
            .header("X-Youtube-Client-Name", "2").header("X-Youtube-Client-Version", "2.20260901.00.00")
            .apply {
                val accountOrigin = "https://www.youtube.com"
                val accountCookies = cookies(accountOrigin)
                val loggedIn = accountCookies.isNotBlank()
                if (loggedIn) header("Cookie", accountCookies)
                authorization(accountOrigin)?.let { header("Authorization", it) }
                header("Origin", accountOrigin)
                header("X-Origin", accountOrigin)
                header("X-Goog-AuthUser", "0")
                header("X-Youtube-Bootstrap-Logged-In", loggedIn.toString())
                if (!loggedIn) visitorData[accountOrigin]?.let { header("X-Goog-Visitor-Id", it) }
            }
            .post(body.toString().toRequestBody(jsonType)).build()
        return client.newCall(request).execute().use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val detail = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                error("YouTube player returned HTTP ${it.code}${if (detail.isBlank()) "" else ": $detail"}")
            }
            JSONObject(raw).put("_materialytCpn", cpn)
        }
    }

    private fun webPoPlayer(
        videoId: String,
        remix: Boolean = true,
        reloadToken: String? = null,
        cpnOverride: String? = null,
        adPlayback: Boolean = false,
    ): JSONObject {
        val clientName = if (remix) "WEB_REMIX" else "WEB"
        val clientId = if (remix) "67" else "1"
        val origin = if (remix) "https://music.youtube.com" else "https://www.youtube.com"
        val showInitialProgress = reloadToken == null && !adPlayback
        if (showInitialProgress) PlaybackLoadStatus.show(videoId, "Preparing WEB session…")
        val bootstrap = bootstrap(origin, clientName)
        if (showInitialProgress) PlaybackLoadStatus.show(videoId, "Generating PoToken…")
        val requestedVisitor = standaloneWebVisitorData(remix)
        val authenticated = cookies(origin).let { "SAPISID=" in it || "__Secure-3PAPISID=" in it }
        val streamingBinding = when {
            bootstrap.gvsBindToVideoId -> videoId
            authenticated -> bootstrap.dataSyncId.takeIf(String::isNotBlank)
                ?: error("Signed-in WEB SABR session did not expose DATASYNC_ID")
            else -> requestedVisitor
        }
        AppLog.event(
            "WEB PoToken binding player=video gvs=${when {
                bootstrap.gvsBindToVideoId -> "video"
                authenticated -> "datasync"
                else -> "visitor"
            }}"
        )
        val token = WebPoTokenProvider.get(videoId, requestedVisitor, streamingBinding, origin, WEB_PLAYER_UA)
        if (showInitialProgress) PlaybackLoadStatus.show(videoId, "Loading player JavaScript…")
        val cpn = cpnOverride?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString().replace("-", "").take(16)
        val playerJs = if (remix) webRemixPlayerJavaScript(videoId) else playerJavaScript(videoId)
        if (showInitialProgress) PlaybackLoadStatus.show(videoId, "Reading player configuration…")
        val signatureTimestamp = Regex("signatureTimestamp['\":\\s]+(\\d{4,6})")
            .find(playerJs)?.groupValues?.get(1)?.toIntOrNull()
            ?: error("YouTube Music player did not expose signatureTimestamp")
        val body = JSONObject()
            .put("context", JSONObject()
                .put("client", JSONObject()
                    .put("clientName", clientName)
                    .put("clientVersion", bootstrap.version)
                    .put("clientScreen", "WATCH")
                    .put("platform", "DESKTOP")
                    .put("hl", "en")
                    .put("gl", "US")
                    .put("utcOffsetMinutes", 0)
                    .put("visitorData", token.visitorData))
                .put("request", JSONObject()
                    .put("internalExperimentFlags", JSONArray())
                    .put("useSsl", true))
                .put("user", JSONObject().put("lockedSafetyMode", false)))
            .put("videoId", videoId)
            .apply { if (reloadToken == null) put("cpn", cpn) }
            .put("playbackContext", JSONObject()
                .apply { if (adPlayback || reloadToken != null) put("adPlaybackContext", JSONObject().put("pyv", true)) }
                .put("contentPlaybackContext", JSONObject().put("signatureTimestamp", signatureTimestamp))
                .apply {
                    reloadToken?.let { token ->
                        put("reloadPlaybackContext", JSONObject().put("reloadPlaybackParams", JSONObject().put("token", token)))
                    }
                })
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            // A reload is still a WEB /player request and must carry the player-bound
            // integrity token. Omitting it makes restricted videos return the generic
            // "This content isn't available" playability response.
            .put("serviceIntegrityDimensions", JSONObject().put("poToken", token.playerRequestPoToken))
        val request = Request.Builder()
            .url("$origin/youtubei/v1/player?prettyPrint=false&key=${bootstrap.key}")
            .header("User-Agent", WEB_PLAYER_UA)
            .header("Content-Type", "application/json")
            .header("X-Youtube-Client-Name", clientId)
            .header("X-Youtube-Client-Version", bootstrap.version)
            .header("X-Goog-Visitor-Id", token.visitorData)
            .apply {
                val accountCookies = cookies(origin)
                if (accountCookies.isNotBlank()) header("Cookie", accountCookies)
                authorization(origin)?.let { header("Authorization", it) }
                header("Origin", origin)
                header("X-Origin", origin)
                header("X-Goog-AuthUser", "0")
                header("X-Youtube-Bootstrap-Logged-In", accountCookies.isNotBlank().toString())
            }
            .post(body.toString().toRequestBody(jsonType)).build()
        if (showInitialProgress) PlaybackLoadStatus.show(videoId, "Requesting playback data…")
        val response = client.newCall(request).execute().use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("YouTube SABR player returned HTTP ${it.code}")
            JSONObject(raw)
        }
        val streaming = response.optJSONObject("streamingData")
        val sabrUrl = streaming?.optString("serverAbrStreamingUrl").orEmpty()
        if (sabrUrl.isNotBlank()) {
            if (showInitialProgress) PlaybackLoadStatus.show(videoId, "Solving media URL…")
            streaming?.put("serverAbrStreamingUrl", solveNChallenge(videoId, sabrUrl, playerJs, false))
            response.put("_materialytNResolved", true)
        }
        return response.put("_materialytCpn", cpn)
            .put("_materialytPlayerPot", token.playerRequestPoToken)
            .put("_materialytStreamingPot", token.streamingDataPoToken)
            .put("_materialytClientVersion", bootstrap.version)
            .put("_materialytClientName", clientName)
    }

    /** Recreates a WEB SABR endpoint after the server enters its content-ad context flow. */
    fun refreshWebSabrAdPlayback(info: SabrPlaybackInfo): Pair<String, String> {
        val remix = info.clientId == 67
        check(info.clientId == 1 || remix) { "Ad playback refresh is only supported by WEB clients" }
        val originalCpn = info.serverAbrStreamingUrl.toHttpUrlOrNull()?.queryParameter("cpn")
        val response = webPoPlayer(info.videoId, remix, cpnOverride = originalCpn, adPlayback = true)
        val status = response.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        val reason = response.optJSONObject("playabilityStatus")?.optString("reason").orEmpty()
        check(status == "OK") { reason.ifBlank { "WEB ad playback refresh returned $status" } }
        val streaming = response.optJSONObject("streamingData")
            ?: error("WEB ad playback refresh returned no streamingData")
        val rawUrl = streaming.optString("serverAbrStreamingUrl").takeIf(String::isNotBlank)
            ?: error("WEB ad playback refresh returned no endpoint")
        val clientName = response.optString("_materialytClientName")
        val solved = solveNChallenge(
            info.videoId,
            rawUrl,
            if (clientName == "WEB") playerJavaScript(info.videoId) else webRemixPlayerJavaScript(info.videoId),
            false,
        )
        val cpn = response.optString("_materialytCpn").ifBlank { originalCpn.orEmpty() }
        val pot = response.optString("_materialytStreamingPot")
        val url = solved.toHttpUrlOrNull()?.newBuilder()
            ?.setQueryParameter("alr", "yes")
            ?.apply { if (cpn.isNotBlank()) setQueryParameter("cpn", cpn) }
            ?.apply { if (pot.isNotBlank()) setQueryParameter("pot", pot) }
            ?.build()?.toString() ?: solved
        val config = findString(response, "videoPlaybackUstreamerConfig")
            ?.takeIf(String::isNotBlank) ?: info.videoPlaybackUstreamerConfig
        AppLog.event("SABR WEB ad playback refresh ready video=${info.videoId}")
        return url to config
    }

    /** Rebuilds the WEB BotGuard minter and returns a fresh endpoint + GVS token for the same video. */
    fun renewWebSabrProtection(info: SabrPlaybackInfo): Pair<String, String> {
        val remix = info.clientId == 67
        check(info.clientId == 1 || remix) { "SABR protection renewal is only supported by WEB clients" }
        val originalCpn = info.serverAbrStreamingUrl.toHttpUrlOrNull()?.queryParameter("cpn")
        WebPoTokenProvider.reset()
        val response = webPoPlayer(info.videoId, remix, cpnOverride = originalCpn)
        val status = response.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        val reason = response.optJSONObject("playabilityStatus")?.optString("reason").orEmpty()
        check(status == "OK") { reason.ifBlank { "WEB protection renewal returned $status" } }
        val rawUrl = response.optJSONObject("streamingData")?.optString("serverAbrStreamingUrl")
            ?.takeIf(String::isNotBlank) ?: error("WEB protection renewal returned no endpoint")
        val cpn = response.optString("_materialytCpn").ifBlank { originalCpn.orEmpty() }
        val pot = response.optString("_materialytStreamingPot")
            .takeIf(String::isNotBlank) ?: error("WEB protection renewal returned no GVS PoToken")
        val url = rawUrl.toHttpUrlOrNull()?.newBuilder()
            ?.setQueryParameter("alr", "yes")
            ?.apply { if (cpn.isNotBlank()) setQueryParameter("cpn", cpn) }
            ?.setQueryParameter("pot", pot)
            ?.build()?.toString() ?: rawUrl
        AppLog.event("SABR WEB protection renewed video=${info.videoId} token=${pot.length}")
        return url to pot
    }

    /** Applies a SABR RELOAD_PLAYER_RESPONSE token and returns a fully prepared replacement endpoint. */
    fun reloadSabrPlayback(info: SabrPlaybackInfo, reloadToken: String): Pair<String, String> {
        val vision = info.clientId == 101
        val androidVr = info.clientId == 28
        val remix = info.clientId == 67
        val native = vision || androidVr
        val target = if (vision) "VISIONOS" else if (androidVr) "ANDROID_VR" else if (remix) "WEB_REMIX" else "WEB"
        AppLog.event("SABR reload video=${info.videoId} client=$target")
        val originalCpn = info.serverAbrStreamingUrl.toHttpUrlOrNull()?.queryParameter("cpn")
        val response = when {
            vision -> visionOsPlayer(info.videoId, reloadToken, originalCpn)
            androidVr -> mobilePlayer(info.videoId, "ANDROID_VR", "1.65.10", "28", "Quest 3", "Android", "12", reloadToken, originalCpn)
            else -> webPoPlayer(info.videoId, remix, reloadToken, originalCpn)
        }
        val status = response.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        val reason = response.optJSONObject("playabilityStatus")?.optString("reason").orEmpty()
        AppLog.event("SABR reload response video=${info.videoId} client=$target status=$status reason=${reason.take(240)}")
        if (status != "OK") error(reason.ifBlank { "SABR player reload returned $status" })
        val streaming = response.optJSONObject("streamingData") ?: error("SABR player reload returned no streamingData")
        val rawUrl = streaming.optString("serverAbrStreamingUrl").takeIf(String::isNotBlank)
            ?: error("SABR player reload returned no endpoint")
        val solved = if (native) rawUrl else solveNChallenge(
            info.videoId, rawUrl,
            if (remix) webRemixPlayerJavaScript(info.videoId) else playerJavaScript(info.videoId), false,
        )
        val cpn = response.optString("_materialytCpn")
        val pot = info.streamingPoToken
        val url = solved.toHttpUrlOrNull()?.newBuilder()
            ?.setQueryParameter("alr", "yes")
            ?.setQueryParameter("cpn", cpn)
            ?.apply { if (!native) setQueryParameter("pot", pot) }
            ?.build()?.toString() ?: solved
        // A same-client VISIONOS reload can rotate both the endpoint and its ustreamer config.
        // The browser reference keeps the original config because its reload crosses to WEB;
        // reusing the old native config with the rotated VISIONOS endpoint is rejected as malformed.
        val refreshedConfig = if (native) {
            findString(response, "videoPlaybackUstreamerConfig")?.takeIf(String::isNotBlank)
                ?: info.videoPlaybackUstreamerConfig
        } else info.videoPlaybackUstreamerConfig
        AppLog.event(
            "SABR player reload ready video=${info.videoId} client=$target " +
                "config=${info.videoPlaybackUstreamerConfig.length}->${refreshedConfig.length}"
        )
        return url to refreshedConfig
    }

    /** Gets the visitor id used by the standalone WEB player. */
    fun standaloneWebVisitorData(remix: Boolean = false): String {
        val origin = if (remix) "https://music.youtube.com" else "https://www.youtube.com"
        val clientName = if (remix) "WEB_REMIX" else "WEB"
        val clientId = if (remix) "67" else "1"
        val bootstrap = bootstrap(origin, clientName)
        // bootstrap() loads the actual signed-in page (including Cookie) and captures the
        // visitor identity paired with that account session. Prefer it over /visitor_id,
        // which otherwise creates a separate guest identity and invalidates the WEB PoToken.
        visitorData[origin]?.takeIf(String::isNotBlank)?.let { value ->
            synchronized(this) { standaloneWebVisitors[origin] = System.currentTimeMillis() to value }
            return value
        }
        synchronized(this) {
            standaloneWebVisitors[origin]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }
                ?.second?.let { return it }
        }
        val body = JSONObject().put("context", JSONObject()
            .put("client", JSONObject()
                    .put("clientName", clientName)
                .put("clientVersion", bootstrap.version)
                .put("clientScreen", "WATCH")
                .put("platform", "DESKTOP")
                .put("hl", "en").put("gl", "US").put("utcOffsetMinutes", 0))
            .put("request", JSONObject().put("internalExperimentFlags", JSONArray()).put("useSsl", true))
            .put("user", JSONObject().put("lockedSafetyMode", false)))
        val request = Request.Builder()
            .url("$origin/youtubei/v1/visitor_id?prettyPrint=false&key=${bootstrap.key}")
            .header("User-Agent", WEB_PLAYER_UA)
            .header("Content-Type", "application/json")
            .header("X-Youtube-Client-Name", clientId)
            .header("X-Youtube-Client-Version", bootstrap.version)
            .apply {
                cookies(origin).takeIf(String::isNotBlank)?.let { header("Cookie", it) }
                authorization(origin)?.let { header("Authorization", it) }
                header("Origin", origin)
                header("X-Origin", origin)
                header("X-Goog-AuthUser", "0")
            }
            .post(body.toString().toRequestBody(jsonType)).build()
        val value = client.newCall(request).execute().use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("YouTube visitor_id returned HTTP ${it.code}")
            JSONObject(raw).optJSONObject("responseContext")?.optString("visitorData").orEmpty()
        }
        check(value.isNotBlank()) { "YouTube visitor_id returned no visitorData" }
        synchronized(this) { standaloneWebVisitors[origin] = System.currentTimeMillis() to value }
        AppLog.event("$clientName standalone visitor ready length=${value.length}")
        return value
    }

    private fun iosPlayer(videoId: String): JSONObject {
        return mobilePlayer(videoId, "IOS", "20.05.1", "5", "iPhone16,2", "iOS", "18.7.2.22H124")
    }

    private fun androidPlayer(videoId: String): JSONObject {
        return mobilePlayer(videoId, "ANDROID", "20.05.35", "3", "Pixel 8 Pro", "Android", "14")
    }

    private fun androidVrPlayer(videoId: String): JSONObject {
        return mobilePlayer(videoId, "ANDROID_VR", "1.65.10", "28", "Quest 3", "Android", "12")
    }

    private fun authenticatedPlayerTracking(videoId: String, cpn: String, music: Boolean): JSONObject? {
        val origin = if (music) "https://music.youtube.com" else "https://www.youtube.com"
        val clientName = if (music) "WEB_REMIX" else "WEB"
        val bootstrap = bootstrap(origin, clientName)
        val response = post("$origin/youtubei/v1/player?key=${bootstrap.key}&prettyPrint=false",
            JSONObject().put("context", context(clientName, bootstrap.version))
                .put("videoId", videoId).put("cpn", cpn)
                .put("contentCheckOk", true).put("racyCheckOk", true), origin)
        val tracking = response.optJSONObject("playbackTracking")
        AppLog.event("tracking_fetch ${clientName}_PLAYER video=$videoId status=${response.optJSONObject("playabilityStatus")?.optString("status")} tracking_found=${tracking != null}")
        return tracking
    }

    fun refreshVideoDetails(videoId: String): VideoDetails {
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        val response = post("$origin/youtubei/v1/next?key=${bootstrap.key}",
            JSONObject().put("context", context("WEB", bootstrap.version)).put("videoId", videoId), origin)
        val current = videoDetails(videoId) ?: VideoDetails("Video", "")
        val primary = findObject(response, "videoPrimaryInfoRenderer")?.optJSONObject("videoPrimaryInfoRenderer")
        val secondary = findObject(response, "videoSecondaryInfoRenderer")?.optJSONObject("videoSecondaryInfoRenderer")
        // NEXT is localized. A native player response keeps the uploader's primary title,
        // description and channel name in videoDetails even when the UI language differs.
        val responseDetails = runCatching { visionOsPlayer(videoId).optJSONObject("videoDetails") }
            .getOrNull() ?: response.optJSONObject("videoDetails")
        // player.videoDetails contains the primary metadata. The matching microformat/NEXT
        // text may be translated for the account locale, so never let it replace these fields.
        val responseTitle = responseDetails?.optString("title")?.trim().orEmpty()
        val responseAuthor = responseDetails?.optString("author")?.trim().orEmpty()
            .takeUnless(::isChannelMetadataLabel).orEmpty()
        val responseChannelId = responseDetails?.optString("channelId").orEmpty()
        val likeText = findLikeText(primary).orEmpty()
        val published = text(primary?.opt("dateText")).orEmpty().ifBlank { current.publishedDate }
        val attributedDescription = secondary?.optJSONObject("attributedDescription")
        val description = attributedDescription?.optString("content").orEmpty()
            .ifBlank { text(secondary?.opt("description")).orEmpty() }
            .ifBlank { current.description }
        val originalDescription = responseDetails?.optString("shortDescription").orEmpty()
        val descriptionLinks = attributedDescription?.let(::attributedDescriptionLinks).orEmpty()
            .ifEmpty { current.descriptionLinks }
        return current.copy(
            title = responseTitle.ifBlank { current.title },
            author = responseAuthor.ifBlank { current.author },
            authorUrl = responseChannelId.takeIf(String::isNotBlank)?.let { "$origin/channel/$it" }.orEmpty().ifBlank { current.authorUrl },
            authorAvatar = secondary?.let(::findLargestAvatar).orEmpty().ifBlank { current.authorAvatar },
            likeText = likeText,
            publishedDate = published,
            description = originalDescription.ifBlank { description },
            descriptionLinks = descriptionLinks,
        ).also {
            synchronized(videoDetailsCache) { videoDetailsCache[videoId] = it }
        }
    }

    fun videoInteractions(videoId: String): VideoInteractionState {
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        val response = post(
            "$origin/youtubei/v1/next?key=${bootstrap.key}",
            JSONObject().put("context", context("WEB", bootstrap.version)).put("videoId", videoId),
            origin,
        )
        val primary = findObject(response, "videoPrimaryInfoRenderer")?.optJSONObject("videoPrimaryInfoRenderer")
        val owner = findObject(response, "videoOwnerRenderer")?.optJSONObject("videoOwnerRenderer")
        val subscribeRenderer = findObject(owner, "subscribeButtonRenderer")?.optJSONObject("subscribeButtonRenderer")
        val channelId = response.optJSONObject("videoDetails")?.optString("channelId").orEmpty()
            .ifBlank { owner?.let { findString(it, "browseId") }.orEmpty() }
            .ifBlank { subscribeRenderer?.optString("channelId").orEmpty() }
            .ifBlank { findString(response, "channelId").orEmpty() }
            .takeIf { it.startsWith("UC") }.orEmpty()
        val nextSubscription = subscriptionState(response, owner, channelId)
        val subscription = if (nextSubscription.first != null || channelId.isBlank()) nextSubscription else {
            runCatching { channelSubscriptionState(channelId) to "channel_browse" }
                .onFailure { AppLog.failure("video subscription fallback channel=$channelId", it) }
                .getOrDefault(null to "missing")
        }
        val subscribed = subscription.first ?: false
        AppLog.event("video interactions subscription video=$videoId channel=$channelId source=${subscription.second} subscribed=$subscribed")
        val likeStatus = findString(primary, "likeStatus").orEmpty()
        val toggleState = findString(primary, "toggleButtonStateId").orEmpty()
        val rating = when (likeStatus.uppercase()) {
            "LIKE" -> VideoRating.LIKE
            "DISLIKE" -> VideoRating.DISLIKE
            else -> when {
                toggleState.contains("STATE_ID_TYPE_LIKED", true) -> VideoRating.LIKE
                toggleState.contains("STATE_ID_TYPE_DISLIKED", true) -> VideoRating.DISLIKE
                else -> findToggledRating(primary)
            }
        }
        val related = parseItems(response)
            .filter { !it.channel && !it.playlist && it.id != videoId }
            .distinctBy { it.id }
        val relatedContinuation = findRelatedContinuationToken(response)
        // Never scan the entire /next response for this value: related-video renderers also
        // contain Like accessibility labels and can replace the current video's count.
        val likeText = findLikeText(primary).orEmpty().ifBlank { videoDetails(videoId)?.likeText.orEmpty() }
        return VideoInteractionState(rating, subscribed, channelId, likeText, related, relatedContinuation)
    }

    fun relatedVideosPage(continuation: String): RelatedVideoPage {
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        val response = post(
            "$origin/youtubei/v1/next?key=${bootstrap.key}",
            JSONObject().put("context", context("WEB", bootstrap.version)).put("continuation", continuation),
            origin,
        )
        return RelatedVideoPage(
            parseItems(response).filter { !it.channel && !it.playlist }.distinctBy { it.id },
            findRelatedContinuationToken(response) ?: findContinuationToken(response),
        )
    }

    private fun findRelatedContinuationToken(response: JSONObject): String? {
        val contents = response.optJSONObject("contents")
        val desktop = contents?.optJSONObject("twoColumnWatchNextResults")
            ?.optJSONObject("secondaryResults")?.optJSONObject("secondaryResults")
            ?.optJSONArray("results")
        if (desktop != null) return findContinuationToken(desktop)
        val continuationItems = response.optJSONArray("onResponseReceivedEndpoints")
        if (continuationItems != null) {
            for (index in continuationItems.length() - 1 downTo 0) {
                val endpoint = continuationItems.optJSONObject(index) ?: continue
                val items = endpoint.optJSONObject("appendContinuationItemsAction")?.optJSONArray("continuationItems")
                    ?: endpoint.optJSONObject("reloadContinuationItemsCommand")?.optJSONArray("continuationItems")
                if (items != null) return findContinuationToken(items)
            }
        }
        return null
    }

    fun rateVideo(videoId: String, rating: VideoRating) {
        require(signedIn()) { "Sign in to rate videos" }
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        val endpoint = when (rating) {
            VideoRating.LIKE -> "like"
            VideoRating.DISLIKE -> "dislike"
            VideoRating.NONE -> "removelike"
        }
        post(
            "$origin/youtubei/v1/like/$endpoint?key=${bootstrap.key}",
            JSONObject().put("context", context("WEB", bootstrap.version))
                .put("target", JSONObject().put("videoId", videoId)),
            origin,
        )
        AppLog.event("rating updated video=$videoId rating=$rating")
    }

    fun setSubscription(channelId: String, subscribed: Boolean) {
        require(signedIn()) { "Sign in to subscribe" }
        require(channelId.isNotBlank()) { "Channel is still loading" }
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        val endpoint = if (subscribed) "subscribe" else "unsubscribe"
        post(
            "$origin/youtubei/v1/subscription/$endpoint?key=${bootstrap.key}",
            JSONObject().put("context", context("WEB", bootstrap.version))
                .put("channelIds", JSONArray().put(channelId)),
            origin,
        )
        AppLog.event("subscription updated channel=$channelId subscribed=$subscribed")
    }

    fun channelSubscriptionState(channelId: String): Boolean {
        require(channelId.isNotBlank()) { "Channel is still loading" }
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        val response = post(
            "$origin/youtubei/v1/browse?key=${bootstrap.key}",
            JSONObject().put("context", context("WEB", bootstrap.version)).put("browseId", channelId),
            origin,
        )
        val subscription = subscriptionState(response, response, channelId)
        AppLog.event("channel subscription channel=$channelId source=${subscription.second} subscribed=${subscription.first}")
        return subscription.first ?: error("YouTube did not expose this channel's subscription state")
    }

    fun isSignedInChannel(channelId: String): Boolean {
        if (!signedIn() || channelId.isBlank()) return false
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        ensureSignedInCommentChannelId(origin, bootstrap)
        return signedInCommentChannelId == channelId
    }

    /**
     * Resolve the subscription state without reading an unrelated channel from a feed or
     * recommendation. Modern YouTube responses include both button states in the view model;
     * only the entity whose key matches stateEntityStoreKey represents the active state.
     */
    private fun subscriptionState(response: JSONObject, scope: Any?, expectedChannelId: String): Pair<Boolean?, String> {
        val legacy = findObject(scope, "subscribeButtonRenderer")?.optJSONObject("subscribeButtonRenderer")
        val legacyChannelId = legacy?.optString("channelId").orEmpty()
            .ifBlank { legacy?.let { findString(it, "channelIds") }.orEmpty() }
        if (legacy?.has("subscribed") == true &&
            (expectedChannelId.isBlank() || legacyChannelId.isBlank() || legacyChannelId == expectedChannelId)
        ) return legacy.optBoolean("subscribed") to "legacy_renderer"

        val view = findObject(scope, "subscribeButtonViewModel")?.optJSONObject("subscribeButtonViewModel")
        val viewChannelId = view?.let(::findChannelId).orEmpty()
        if (view != null && (expectedChannelId.isBlank() || viewChannelId.isBlank() || viewChannelId == expectedChannelId)) {
            val key = view.optString("stateEntityStoreKey").ifBlank {
                view.optJSONObject("subscribeButtonContent")?.optJSONObject("subscribeState")?.optString("key").orEmpty()
            }
            val entity = findSubscriptionStateEntity(response, key)
            if (entity?.has("subscribed") == true) return entity.optBoolean("subscribed") to "state_entity"
        }

        val namedState = findString(scope, "subscriptionState").orEmpty()
        if (namedState.isNotBlank()) return (
            namedState.contains("SUBSCRIBED", true) && !namedState.contains("UNSUBSCRIBED", true)
        ) to "named_state"
        // Modern subscribe view models carry commands for BOTH possible states. Merely finding a
        // subscribe/unsubscribe endpoint does not identify the active state; callers must use the
        // channel browse response when neither the renderer nor its keyed state entity resolves it.
        return null to "missing"
    }

    private fun findSubscriptionStateEntity(value: Any?, key: String): JSONObject? = when (value) {
        is JSONObject -> {
            val entity = value.optJSONObject("subscriptionStateEntity")
            if (entity != null && key.isNotBlank() && entity.optString("key") == key) entity
            else value.keys().asSequence().mapNotNull { findSubscriptionStateEntity(value.opt(it), key) }.firstOrNull()
        }
        is JSONArray -> (0 until value.length()).asSequence()
            .mapNotNull { findSubscriptionStateEntity(value.opt(it), key) }.firstOrNull()
        else -> null
    }

    private fun findChannelId(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.startsWith("UC") }
        is JSONObject -> value.optString("channelId").takeIf { it.startsWith("UC") }
            ?: value.optJSONArray("channelIds")?.let { ids ->
                (0 until ids.length()).asSequence().mapNotNull { findChannelId(ids.opt(it)) }.firstOrNull()
            }
            ?: value.keys().asSequence().mapNotNull { findChannelId(value.opt(it)) }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findChannelId(value.opt(it)) }.firstOrNull()
        else -> null
    }

    private fun findToggledRating(value: Any?): VideoRating = when (value) {
        is JSONObject -> {
            val icon = value.optJSONObject("defaultIcon")?.optString("iconType")
                .orEmpty().ifBlank { value.optJSONObject("icon")?.optString("iconType").orEmpty() }
            if (value.optBoolean("isToggled", false) && icon == "LIKE") VideoRating.LIKE
            else if (value.optBoolean("isToggled", false) && icon == "DISLIKE") VideoRating.DISLIKE
            else value.keys().asSequence().map { findToggledRating(value.opt(it)) }.firstOrNull { it != VideoRating.NONE } ?: VideoRating.NONE
        }
        is JSONArray -> (0 until value.length()).asSequence().map { findToggledRating(value.opt(it)) }.firstOrNull { it != VideoRating.NONE } ?: VideoRating.NONE
        else -> VideoRating.NONE
    }

    private fun findBoolean(value: Any?, key: String): Boolean? = when (value) {
        is JSONObject -> if (value.has(key) && value.opt(key) is Boolean) value.optBoolean(key)
            else value.keys().asSequence().mapNotNull { findBoolean(value.opt(it), key) }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findBoolean(value.opt(it), key) }.firstOrNull()
        else -> null
    }

    private fun containsString(value: Any?, needle: String): Boolean = when (value) {
        is String -> value.contains(needle, true)
        is JSONObject -> value.keys().asSequence().any { key -> key.contains(needle, true) || containsString(value.opt(key), needle) }
        is JSONArray -> (0 until value.length()).any { containsString(value.opt(it), needle) }
        else -> false
    }

    private fun attributedDescriptionLinks(description: JSONObject): List<DescriptionLinkTarget> {
        val content = description.optString("content")
        val runs = description.optJSONArray("commandRuns") ?: return emptyList()
        return (0 until runs.length()).mapNotNull { index ->
            val run = runs.optJSONObject(index) ?: return@mapNotNull null
            val start = run.optInt("startIndex", -1)
            val length = run.optInt("length", -1)
            if (start < 0 || length <= 0 || start + length > content.length) return@mapNotNull null
            val command = run.optJSONObject("onTap")?.optJSONObject("innertubeCommand")
                ?: run.optJSONObject("navigationEndpoint")
                ?: return@mapNotNull null
            val seekSeconds = command.optJSONObject("watchEndpoint")?.optLong("startTimeSeconds", -1L) ?: -1L
            val urlEndpoint = command.optJSONObject("urlEndpoint")
            val webUrl = urlEndpoint?.optString("url").orEmpty().ifBlank {
                command.optJSONObject("commandMetadata")?.optJSONObject("webCommandMetadata")?.optString("url").orEmpty()
            }
            val browse = command.optJSONObject("browseEndpoint")
            val browseUrl = browse?.optString("canonicalBaseUrl").orEmpty().ifBlank {
                browse?.optString("browseId")?.takeIf { it.startsWith("UC") }
                    ?.let { "/channel/$it" }.orEmpty()
            }
            val target = when {
                seekSeconds >= 0L -> "seek:$seekSeconds"
                webUrl.isNotBlank() -> if (webUrl.startsWith("/")) "https://www.youtube.com$webUrl" else webUrl
                browseUrl.isNotBlank() -> if (browseUrl.startsWith("/")) "https://www.youtube.com$browseUrl" else browseUrl
                else -> return@mapNotNull null
            }
            DescriptionLinkTarget(start, start + length, target)
        }
    }

    private fun findLikeText(value: Any?): String? = when (value) {
        is JSONObject -> {
            value.optJSONObject("factoidRenderer")?.let { factoid ->
                val label = text(factoid.opt("label")).orEmpty()
                if (label.contains("like", true)) {
                    text(factoid.opt("value"))?.takeIf(String::isNotBlank)?.let { return it }
                }
            }
            listOf("likeCount", "likeCountText", "likeCountNotliked", "likeCountNotLiked")
                .asSequence().mapNotNull { key ->
                    value.optString(key).takeIf(String::isNotBlank)
                        ?: text(value.opt(key))?.takeIf(String::isNotBlank)
                }.firstOrNull()
                ?: value.optString("accessibilityText").takeIf { label ->
                    label.contains("like", true) && label.any(Char::isDigit)
                }?.let { label ->
                    Regex("[0-9][0-9,.]*(?:[KMB])?", RegexOption.IGNORE_CASE).find(label)?.value
                }
                ?: value.keys().asSequence().mapNotNull { findLikeText(value.opt(it)) }.firstOrNull()
        }
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findLikeText(value.opt(it)) }.firstOrNull()
        else -> null
    }


    private fun mobilePlayer(
        videoId: String, name: String, version: String, id: String, model: String, os: String, osVersion: String,
        reloadToken: String? = null, cpnOverride: String? = null,
    ): JSONObject {
        val ua = when (name) {
            "ANDROID_VR" -> "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
            "ANDROID" -> "com.google.android.youtube/$version (Linux; U; Android $osVersion) gzip"
            "VISIONOS" -> VISIONOS_UA
            else -> "com.google.ios.youtube/$version (iPhone; U; CPU iOS 18_7_2 like Mac OS X)"
        }
        val cpn = cpnOverride?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString().replace("-", "").take(16)
        val nativeAnonymous = name == "ANDROID_VR" || name == "VISIONOS"
        val clientContext = JSONObject()
            .put("clientName", name).put("clientVersion", version)
            .put("deviceModel", model).put("osName", os)
            .put("osVersion", osVersion).put("hl", "en").put("gl", "US")
            .apply {
                if (name != "VISIONOS") {
                    put("platform", "MOBILE")
                    put("clientScreen", "WATCH")
                }
            }
            .apply { 
                val accountOrigin = "https://www.youtube.com"
                val accountCookies = cookies(accountOrigin)
                val loggedIn = accountCookies.isNotBlank() && !nativeAnonymous
                // Native anonymous clients must not inherit the signed-in WEB visitor identity.
                // A WEB-bound visitor combined with VISIONOS is rejected as LOGIN_REQUIRED.
                if (!loggedIn && !nativeAnonymous) visitorData[accountOrigin]?.let { put("visitorData", it) }
                if (name == "ANDROID_VR") {
                    put("androidSdkVersion", 32)
                    put("deviceMake", "Oculus")
                }
                if (name == "VISIONOS") put("deviceMake", "Apple")
            }
        val body = JSONObject().put("context", JSONObject().put("client", clientContext))
            .put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true)
            .put("cpn", cpn)
            .apply {
                reloadToken?.let { token ->
                    put("playbackContext", JSONObject()
                        .put("adPlaybackContext", JSONObject().put("pyv", true))
                        .put("reloadPlaybackContext", JSONObject()
                            .put("reloadPlaybackParams", JSONObject().put("token", token))))
                }
            }
        val request = Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false&id=$videoId")
            .header("User-Agent", ua).header("Content-Type", "application/json")
            .header("X-Youtube-Client-Name", id).header("X-Youtube-Client-Version", version)
            .apply {
                val accountOrigin = "https://www.youtube.com"
                val accountCookies = cookies(accountOrigin)
                val loggedIn = accountCookies.isNotBlank() && !nativeAnonymous
                if (loggedIn) header("Cookie", accountCookies)
                if (loggedIn) authorization(accountOrigin)?.let { header("Authorization", it) }
                header("Origin", accountOrigin)
                header("X-Origin", accountOrigin)
                header("X-Goog-AuthUser", "0")
                header("X-Youtube-Bootstrap-Logged-In", loggedIn.toString())
                if (!loggedIn && !nativeAnonymous) visitorData[accountOrigin]?.let { header("X-Goog-Visitor-Id", it) }
            }
            .post(body.toString().toRequestBody(jsonType)).build()
        return client.newCall(request).execute().use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val detail = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                error("YouTube player returned HTTP ${it.code}${if (detail.isBlank()) "" else ": $detail"}")
            }
            JSONObject(raw).put("_materialytCpn", cpn).put("_materialytClientName", name)
        }
    }

    fun lyrics(videoId: String): List<LyricLine> {
        val origin = "https://music.youtube.com"
        val bootstrap = bootstrap(origin, "WEB_REMIX")
        val next = post("$origin/youtubei/v1/next?key=${bootstrap.key}", JSONObject().put("context", context("WEB_REMIX", bootstrap.version)).put("videoId", videoId), origin)
        val browseId = findLyricsBrowseId(next) ?: error("Lyrics are not available for this track")
        val mobileContext = JSONObject().put("client", JSONObject().put("clientName", "ANDROID_MUSIC")
            .put("clientVersion", "7.01.05").put("androidSdkVersion", 35).put("hl", "en").put("gl", "US"))
        val response = postPublic("$origin/youtubei/v1/browse?prettyPrint=false", JSONObject().put("context", mobileContext).put("browseId", browseId), "26", "7.01.05")
        val timed = findArray(response, "timedLyricsData")
        if (timed != null) {
            val lines = (0 until timed.length()).mapNotNull { index ->
                val raw = timed.optJSONObject(index) ?: return@mapNotNull null
                val text = raw.optString("lyricLine").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val range = raw.optJSONObject("cueRange") ?: return@mapNotNull LyricLine(text, -1, -1)
                LyricLine(text, range.optString("startTimeMilliseconds").toLongOrNull() ?: -1,
                    range.optString("endTimeMilliseconds").toLongOrNull() ?: -1)
            }
            if (lines.isNotEmpty()) return lines
        }
        val plain = findLongestText(response).takeIf { it.length > 20 }
            ?: error("Lyrics are not available for this track")
        return plain.lines().filter { it.isNotBlank() }.map { LyricLine(it, -1, -1) }
    }

    fun channelInfo(videoId: String): ChannelInfo {
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap("$origin/watch?v=$videoId", "WEB")
        val response = post("$origin/youtubei/v1/next?key=${bootstrap.key}",
            JSONObject().put("context", context("WEB", bootstrap.version)).put("videoId", videoId), origin)
        val owner = findObject(response, "videoOwnerRenderer")?.optJSONObject("videoOwnerRenderer")
        val ownerName = owner?.let { text(it.opt("title")) }?.trim().orEmpty()
        val metadataName = videoDetails(videoId)?.author?.trim().orEmpty()
        val responseName = findString(response.optJSONObject("videoDetails"), "author")?.trim().orEmpty()
        val channelId = response.optJSONObject("videoDetails")?.optString("channelId").orEmpty()
            .ifBlank { owner?.let { findString(it, "browseId") }.orEmpty() }
            .takeIf { it.startsWith("UC") }.orEmpty()
        // Some NEXT layouts put the handle and subscriber label in the owner title, for
        // example "@creator • 1.2M Subscribers". That is channel metadata, not its name.
        // The player author is the channel's primary name. NEXT and channel browse can localize
        // channel names, so they are fallbacks only.
        val directName = metadataName.takeIf(String::isNotBlank)?.takeUnless(::isChannelMetadataLabel)
            ?: responseName.takeIf(String::isNotBlank)?.takeUnless(::isChannelMetadataLabel)
            ?: ownerName.takeIf(String::isNotBlank)?.takeUnless(::isChannelMetadataLabel)
        val browsedName = if (directName == null && channelId.isNotBlank()) runCatching {
            val channelResponse = post(
                "$origin/youtubei/v1/browse?key=${bootstrap.key}",
                JSONObject().put("context", context("WEB", bootstrap.version)).put("browseId", channelId),
                origin,
            )
            val metadata = findObject(channelResponse, "channelMetadataRenderer")
                ?.optJSONObject("channelMetadataRenderer")
            metadata?.optString("title")?.trim()?.takeIf(String::isNotBlank)
                ?: findObject(channelResponse, "pageHeaderRenderer")
                    ?.optJSONObject("pageHeaderRenderer")?.let { text(it.opt("pageTitle")) }?.trim()?.takeIf(String::isNotBlank)
        }.getOrNull() else null
        val name = directName ?: browsedName
            ?: error("Channel data is unavailable")
        return ChannelInfo(name, owner?.let(::findLargestAvatar).orEmpty())
    }

    private fun isChannelMetadataLabel(value: String): Boolean {
        val normalized = value.lowercase()
        return (value.startsWith("@") && '•' in value) ||
            " subscriber" in normalized || " subscribers" in normalized
    }

    private fun findLargestAvatar(value: Any?): String? {
        var bestUrl: String? = null
        var bestPixels = -1L
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val url = node.optString("url")
                    if (isYouTubeImageUrl(url)) {
                        val declaredWidth = node.optLong("width").coerceAtLeast(0L)
                        val declaredHeight = node.optLong("height").coerceAtLeast(0L)
                        val urlSize = Regex("=s(\\d+)").find(url)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                        val pixels = maxOf(declaredWidth * declaredHeight, urlSize * urlSize)
                        if (pixels > bestPixels) {
                            bestPixels = pixels
                            bestUrl = normalizeImageUrl(url)
                        }
                    }
                    node.keys().forEach { visit(node.opt(it)) }
                }
                is JSONArray -> for (index in 0 until node.length()) visit(node.opt(index))
            }
        }
        visit(value)
        return bestUrl?.let(::highResolutionAvatarUrl)
    }

    private fun highResolutionAvatarUrl(url: String): String = normalizeImageUrl(url)
        .replace(Regex("=s\\d+(?=-|$)"), "=s512")
        .replace(Regex("=w\\d+-h\\d+(?=-|$)"), "=w512-h512")

    private fun findLargestPostImage(value: Any?): String? {
        var best: JSONObject? = null
        var bestPixels = -1L
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val url = node.optString("url")
                    if (isYouTubeImageUrl(url)) {
                        val width = node.optLong("width").coerceAtLeast(1L)
                        val height = node.optLong("height").coerceAtLeast(1L)
                        if (width * height > bestPixels) { bestPixels = width * height; best = node }
                    }
                    node.keys().forEach { visit(node.opt(it)) }
                }
                is JSONArray -> for (index in 0 until node.length()) visit(node.opt(index))
            }
        }
        visit(value)
        val candidate = best ?: return null
        return highResolutionArtworkUrl(candidate.optString("url"), candidate.optInt("width"), candidate.optInt("height"))
    }

    fun authenticatedHeaders(origin: String): Map<String, String> = buildMap {
        val cookie = cookies(origin)
        if (cookie.isNotBlank()) put("Cookie", cookie)
        authorization(origin)?.let { put("Authorization", it) }
        put("Origin", origin)
        put("X-Origin", origin)
        put("X-Goog-AuthUser", "0")
        put("X-Youtube-Bootstrap-Logged-In", signedIn().toString())
        put("User-Agent", UA)
    }

    fun mediaHeaders(streamUrl: String? = null): Map<String, String> = buildMap {
        val client = streamUrl?.toHttpUrlOrNull()?.queryParameter("c")?.uppercase()
        put("User-Agent", when (client) {
            "IOS" -> "com.google.ios.youtube/21.03.2 (iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X)"
            "WEB", "MWEB" -> UA
            else -> "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; US)"
        })
    }

    fun postComment(videoId: String, text: String) {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val watchHtml = get(watchUrl)
        val bootstrap = bootstrap(watchUrl, "WEB")
        val nextBody = JSONObject().put("context", context("WEB", bootstrap.version)).put("videoId", videoId)
        val next = post("https://www.youtube.com/youtubei/v1/next?key=${bootstrap.key}", nextBody, bootstrap.origin)
        val params = findString(next, "createCommentParams")
            ?: runCatching { findString(initialData(watchHtml), "createCommentParams") }.getOrNull()
            ?: error("Comments are disabled or this account cannot comment on this video")
        val body = JSONObject().put("context", context("WEB", bootstrap.version)).put("createCommentParams", params).put("commentText", text)
        post("https://www.youtube.com/youtubei/v1/comment/create_comment?key=${bootstrap.key}", body, bootstrap.origin)
    }

    /** Load the first authenticated comments page while retaining YouTube's write commands. */
    fun videoComments(videoId: String): VideoCommentPage {
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        ensureSignedInCommentChannelId(origin, bootstrap)
        val first = post("$origin/youtubei/v1/next?key=${bootstrap.key}",
            JSONObject().put("context", context("WEB", bootstrap.version)).put("videoId", videoId), origin)
        val createParams = findString(first, "createCommentParams")
        val token = findCommentsSectionToken(first)
            ?: return VideoCommentPage(emptyList(), createCommentParams = createParams)
        val page = videoCommentsPage(token)
        return page.copy(createCommentParams = page.createCommentParams ?: createParams)
    }

    /** Load another top-level comments page or another replies page. */
    fun videoCommentsPage(continuation: String): VideoCommentPage {
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        ensureSignedInCommentChannelId(origin, bootstrap)
        val response = post("$origin/youtubei/v1/next?key=${bootstrap.key}",
            JSONObject().put("context", context("WEB", bootstrap.version)).put("continuation", continuation), origin)
        return parseVideoCommentPage(response)
    }

    private fun ensureSignedInCommentChannelId(origin: String, bootstrap: Bootstrap) {
        if (!signedIn() || signedInCommentChannelId != null) return
        val accountMenu = runCatching {
            post("$origin/youtubei/v1/account/account_menu?key=${bootstrap.key}",
                JSONObject().put("context", context("WEB", bootstrap.version)), origin)
        }.getOrNull()
        signedInCommentChannelId = accountMenu?.let(::findOwnChannelId)
            ?: accountMenu?.let(::findFirstChannelId)
    }

    fun postCommentWithParams(params: String, text: String): VideoComment {
        require(signedIn()) { "Sign in again to post comments" }
        val clean = text.trim()
        require(clean.isNotEmpty()) { "Comment cannot be empty" }
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        val response = post("$origin/youtubei/v1/comment/create_comment?key=${bootstrap.key}",
            JSONObject().put("context", context("WEB", bootstrap.version))
                .put("createCommentParams", params).put("commentText", clean), origin)
        return createdComment(response, clean)
    }

    fun postCommentReply(params: String, text: String): VideoComment {
        require(signedIn()) { "Sign in again to post replies" }
        val clean = text.trim()
        require(clean.isNotEmpty()) { "Reply cannot be empty" }
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        val response = post("$origin/youtubei/v1/comment/create_comment_reply?key=${bootstrap.key}",
            JSONObject().put("context", context("WEB", bootstrap.version))
                .put("createReplyParams", params).put("commentText", clean), origin)
        return createdComment(response, clean)
    }

    fun updateComment(params: String, text: String, isReply: Boolean = false, apiUrl: String? = null): VideoComment {
        require(signedIn()) { "Sign in again to edit comments" }
        val clean = text.trim()
        require(clean.isNotEmpty()) { "Comment cannot be empty" }
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        val suppliedEndpoint = apiUrl?.substringAfter("/youtubei/v1/comment/")?.substringBefore('?')
            ?.takeIf(String::isNotBlank)
            ?: if (isReply) "update_comment_reply" else "update_comment"
        val candidates = if (isReply) listOf(
            suppliedEndpoint to "updateCommentParams",
            "update_comment" to "updateCommentParams",
        ).distinct() else listOf(suppliedEndpoint to "updateCommentParams")
        var lastFailure: Throwable? = null
        var response: JSONObject? = null
        for ((endpoint, paramsKey) in candidates) {
            AppLog.event("comment edit request endpoint=$endpoint paramsKey=$paramsKey paramsLength=${params.length} apiUrlPresent=${!apiUrl.isNullOrBlank()}")
            val attempt = runCatching {
                post("$origin/youtubei/v1/comment/$endpoint?key=${bootstrap.key}",
                    JSONObject().put("context", context("WEB", bootstrap.version))
                        .put(paramsKey, params).put("commentText", clean), origin)
            }
            if (attempt.isSuccess) {
                response = attempt.getOrThrow()
                break
            }
            lastFailure = attempt.exceptionOrNull()
            if (lastFailure?.message != "YouTube returned HTTP 400") throw (lastFailure ?: IllegalStateException("Reply edit failed"))
        }
        val editResponse = response ?: throw (lastFailure ?: IllegalStateException("YouTube rejected the reply edit"))
        return createdComment(editResponse, clean)
    }

    fun deleteComment(videoId: String, comment: VideoComment) {
        require(signedIn()) { "Sign in again to delete comments" }
        require(comment.isOwn) { "You can only delete your own comments" }
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        val action = comment.deleteParams ?: deleteCommentParams(videoId, comment.id)
        val response = post("$origin/youtubei/v1/comment/perform_comment_action?key=${bootstrap.key}",
            JSONObject().put("context", context("WEB", bootstrap.version))
                .put("actions", JSONArray().put(action)), origin)
        val remove = findNestedObjectValue(response, "removeCommentAction")
        val result = remove?.optJSONObject("actionResult") ?: findNestedObjectValue(response, "actionResult")
        val status = result?.optString("status").orEmpty()
        if (status != "STATUS_SUCCEEDED") error(feedback(result) ?: status.ifBlank { "YouTube did not confirm deletion" })
        remove?.optString("commentId")?.takeIf(String::isNotBlank)?.let {
            check(it == comment.id) { "YouTube confirmed a different comment" }
        }
        sessionOwnedCommentIds.remove(comment.id)
    }

    private fun createdComment(response: JSONObject, fallbackText: String): VideoComment {
        val result = findNestedObjectValue(response, "actionResult")
        val status = result?.optString("status").orEmpty()
        if (status.isNotBlank() && status != "STATUS_SUCCEEDED") error(feedback(result) ?: status)
        val renderer = findObjectWithString(response, "commentRenderer", "commentId")
        val parsed = renderer?.let { parseVideoComment(JSONObject().put("commentRenderer", it), emptyMap()) }
        if (parsed != null) {
            sessionOwnedCommentIds += parsed.id
            parsed.author.commentAuthorKey()?.let(sessionOwnedCommentAuthors::add)
            return parsed.copy(
                isOwn = true,
                updateParams = findString(response, "updateCommentParams"),
                deleteParams = findString(response, "deleteCommentParams"),
            )
        }
        // A successful write may omit its renderer. Showing the submitted text avoids
        // inviting a duplicate retry; mutation actions stay hidden until a real id arrives.
        if (status == "STATUS_SUCCEEDED") return VideoComment(
            id = "local-${UUID.randomUUID()}", author = "You", text = fallbackText,
            published = "Just now", likes = "", isOwn = false,
        )
        error("YouTube did not return the posted comment")
    }

    private fun feedback(result: JSONObject?): String? =
        text(result?.opt("feedbackText"))?.takeIf(String::isNotBlank)

    private fun findNestedObjectValue(value: Any?, key: String): JSONObject? = when (value) {
        is JSONObject -> value.optJSONObject(key)
            ?: value.keys().asSequence().mapNotNull { findNestedObjectValue(value.opt(it), key) }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findNestedObjectValue(value.opt(it), key) }.firstOrNull()
        else -> null
    }

    private fun findObjectWithString(value: Any?, key: String, required: String): JSONObject? = when (value) {
        is JSONObject -> value.optJSONObject(key)?.takeIf { it.optString(required).isNotBlank() }
            ?: value.keys().asSequence().mapNotNull { findObjectWithString(value.opt(it), key, required) }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findObjectWithString(value.opt(it), key, required) }.firstOrNull()
        else -> null
    }

    private fun deleteCommentParams(videoId: String, commentId: String): String {
        val proto = ByteArrayOutputStream()
        fun varint(value: Long) { var current = value; while (current and -128L != 0L) { proto.write(((current and 127L) or 128L).toInt()); current = current ushr 7 }; proto.write(current.toInt()) }
        fun number(field: Int, value: Long) { varint((field shl 3).toLong()); varint(value) }
        fun string(field: Int, value: String) { val bytes = value.toByteArray(Charsets.UTF_8); varint(((field shl 3) or 2).toLong()); varint(bytes.size.toLong()); proto.write(bytes) }
        number(1, 6); number(2, 2); string(3, commentId); string(5, videoId)
        return URLEncoder.encode(android.util.Base64.encodeToString(proto.toByteArray(), android.util.Base64.NO_WRAP), "UTF-8")
    }

    private fun findCommentsSectionToken(value: Any?): String? = when (value) {
        is JSONObject -> {
            val section = value.optJSONObject("itemSectionRenderer")
            if (section?.optString("targetId") == "comments-section") {
                findContinuationToken(section)
            } else value.keys().asSequence().mapNotNull { findCommentsSectionToken(value.opt(it)) }.firstOrNull()
        }
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findCommentsSectionToken(value.opt(it)) }.firstOrNull()
        else -> null
    }

    private fun parseVideoCommentPage(response: JSONObject): VideoCommentPage {
        val endpoints = response.optJSONArray("onResponseReceivedEndpoints")
        var contents: JSONArray? = null
        if (endpoints != null) for (index in endpoints.length() - 1 downTo 0) {
            val endpoint = endpoints.optJSONObject(index) ?: continue
            contents = endpoint.optJSONObject("reloadContinuationItemsCommand")?.optJSONArray("continuationItems")
                ?: endpoint.optJSONObject("appendContinuationItemsAction")?.optJSONArray("continuationItems")
            if (contents != null) break
        }
        contents ?: return VideoCommentPage(emptyList())

        val mutations = mutableMapOf<String, JSONObject>()
        val mutationArray = response.optJSONObject("frameworkUpdates")?.optJSONObject("entityBatchUpdate")?.optJSONArray("mutations")
        if (mutationArray != null) for (index in 0 until mutationArray.length()) {
            mutationArray.optJSONObject(index)?.let { mutation ->
                mutation.optString("entityKey").takeIf(String::isNotBlank)?.let { mutations[it] = mutation.optJSONObject("payload") ?: JSONObject() }
            }
        }

        val comments = ArrayList<VideoComment>()
        var next: String? = null
        for (index in 0 until contents.length()) {
            val item = contents.optJSONObject(index) ?: continue
            item.optJSONObject("continuationItemRenderer")?.let { next = findContinuationToken(it) ?: next }
            parseVideoComment(item, mutations)?.let(comments::add)
        }
        return VideoCommentPage(comments.distinctBy { it.id }, next, findString(response, "createCommentParams"))
    }

    private fun parseVideoComment(item: JSONObject, mutations: Map<String, JSONObject>): VideoComment? {
        val thread = item.optJSONObject("commentThreadRenderer")
        val root = thread ?: item
        val replies = thread?.optJSONObject("replies")?.optJSONObject("commentRepliesRenderer")
        val legacy = root.optJSONObject("comment")?.optJSONObject("commentRenderer")
            ?: root.optJSONObject("commentRenderer")
        if (legacy != null) {
            val id = legacy.optString("commentId")
            if (id.isBlank()) return null
            val authorName = text(legacy.opt("authorText")).orEmpty().ifBlank { "YouTube user" }
            val authorChannelId = findString(legacy.opt("authorEndpoint"), "browseId")
                ?.takeIf { it.startsWith("UC") }
                ?: findString(legacy, "authorExternalChannelId").orEmpty()
            val updateReplyParams = findString(root, "updateReplyParams") ?: findString(legacy, "updateReplyParams")
                ?: findMenuCommand(root, "Edit", "updateReplyParams")
            val updateApiUrl = findMenuCommand(root, "Edit", "apiUrl")
            val updateParams = findString(root, "updateCommentParams") ?: findString(legacy, "updateCommentParams")
                ?: findMenuCommand(root, "Edit", "updateCommentParams") ?: updateReplyParams
            val deleteParams = findString(root, "deleteCommentParams") ?: findString(legacy, "deleteCommentParams")
                ?: findMenuCommand(root, "Delete", "action")
            val ownsActions = updateParams != null || deleteParams != null
            val authorKey = authorName.commentAuthorKey()
            if (ownsActions) authorKey?.let(sessionOwnedCommentAuthors::add)
            val isOwn = id in sessionOwnedCommentIds || ownsActions ||
                (authorChannelId.isNotBlank() && authorChannelId == signedInCommentChannelId) ||
                (authorKey != null && authorKey in sessionOwnedCommentAuthors)
            if (isOwn && updateParams == null) {
                AppLog.event("owned comment edit action missing renderer=legacy keys=${jsonKeySummary(root, legacy)}")
            }
            return VideoComment(
                id = id,
                author = authorName,
                text = text(legacy.opt("contentText")).orEmpty(),
                published = text(legacy.opt("publishedTimeText")).orEmpty(),
                likes = text(legacy.opt("voteCount")).orEmpty(),
                avatar = findThumbnail(legacy.opt("authorThumbnail")).orEmpty(),
                replyCount = legacy.optInt("replyCount", 0),
                repliesContinuation = replies?.let(::findContinuationToken),
                createReplyParams = findString(root, "createReplyParams") ?: findString(legacy, "createReplyParams"),
                isOwn = isOwn,
                authorChannelId = authorChannelId,
                updateParams = updateParams,
                updateIsReply = updateReplyParams != null,
                updateApiUrl = updateApiUrl,
                deleteParams = deleteParams,
            )
        }

        val wrapper = root.optJSONObject("commentViewModel") ?: return null
        val view = wrapper.optJSONObject("commentViewModel") ?: wrapper
        val commentPayload = mutations[view.optString("commentKey")]?.optJSONObject("commentEntityPayload")
        val properties = commentPayload?.optJSONObject("properties")
        val author = commentPayload?.optJSONObject("author")
        val toolbar = commentPayload?.optJSONObject("toolbar")
        val toolbarSurface = mutations[view.optString("toolbarSurfaceKey")]
            ?.optJSONObject("engagementToolbarSurfaceEntityPayload")
        val commentSurface = mutations[view.optString("commentSurfaceKey")]
            ?.optJSONObject("commentSurfaceEntityPayload")
        val sharedPayload = mutations[view.optString("sharedKey")]
        val id = properties?.optString("commentId").orEmpty().ifBlank { view.optString("commentId") }
        if (id.isBlank()) return null
        val authorName = author?.optString("displayName").orEmpty().ifBlank { "YouTube user" }
        val authorChannelId = author?.optString("channelId").orEmpty()
            .ifBlank { findString(author, "browseId").orEmpty() }
        val replyCountText = toolbar?.optString("replyCount").orEmpty()
        val updateReplyParams = findString(root, "updateReplyParams")
            ?: findString(commentPayload, "updateReplyParams")
            ?: findString(toolbarSurface, "updateReplyParams")
            ?: findString(commentSurface, "updateReplyParams")
            ?: findString(sharedPayload, "updateReplyParams")
            ?: findMenuCommand(root, "Edit", "updateReplyParams")
        val updateApiUrl = findMenuCommand(root, "Edit", "apiUrl")
        val updateParams = findString(root, "updateCommentParams")
            ?: findString(commentPayload, "updateCommentParams")
            ?: findString(toolbarSurface, "updateCommentParams")
            ?: findString(commentSurface, "updateCommentParams")
            ?: findString(sharedPayload, "updateCommentParams")
            ?: findMenuCommand(root, "Edit", "updateCommentParams")
            ?: updateReplyParams
        val deleteParams = findString(root, "deleteCommentParams")
            ?: findString(commentPayload, "deleteCommentParams")
            ?: findString(toolbarSurface, "deleteCommentParams")
            ?: findString(commentSurface, "deleteCommentParams")
            ?: findString(sharedPayload, "deleteCommentParams")
            ?: findMenuCommand(commentSurface, "Delete", "action")
            ?: findMenuCommand(sharedPayload, "Delete", "action")
            ?: findMenuCommand(root, "Delete", "action")
        val ownsActions = updateParams != null || deleteParams != null
        val authorKey = authorName.commentAuthorKey()
        if (ownsActions) authorKey?.let(sessionOwnedCommentAuthors::add)
        val isOwn = author?.optBoolean("isCurrentUser", false) == true ||
            id in sessionOwnedCommentIds || ownsActions ||
            (authorChannelId.isNotBlank() && authorChannelId == signedInCommentChannelId) ||
            (authorKey != null && authorKey in sessionOwnedCommentAuthors)
        if (isOwn && updateParams == null) {
            AppLog.event("owned comment edit action missing renderer=modern keys=${jsonKeySummary(root, commentPayload, toolbarSurface, commentSurface, sharedPayload)}")
        }
        return VideoComment(
            id = id,
            author = authorName,
            text = text(properties?.opt("content")).orEmpty(),
            published = properties?.optString("publishedTime").orEmpty(),
            likes = toolbar?.optString("likeCountNotliked").orEmpty(),
            avatar = findThumbnail(commentPayload?.opt("avatar")).orEmpty().ifBlank { author?.optString("avatarThumbnailUrl").orEmpty() },
            replyCount = replyCountText.filter(Char::isDigit).toIntOrNull() ?: 0,
            repliesContinuation = replies?.let(::findContinuationToken),
            createReplyParams = findString(toolbarSurface, "createReplyParams")
                ?: findString(root, "createReplyParams")
                ?: findString(commentPayload, "createReplyParams"),
            isOwn = isOwn,
            authorChannelId = authorChannelId,
            updateParams = updateParams,
            updateIsReply = updateReplyParams != null,
            updateApiUrl = updateApiUrl,
            deleteParams = deleteParams,
        )
    }

    private fun String.commentAuthorKey(): String? = trim().lowercase()
        .removePrefix("@").takeIf { it.isNotBlank() && it != "youtube user" && it != "you" }

    private fun jsonKeySummary(vararg values: Any?): String {
        val keys = linkedSetOf<String>()
        fun visit(value: Any?, depth: Int) {
            if (depth > 20 || keys.size >= 400) return
            when (value) {
                is JSONObject -> value.keys().forEach { key -> keys += key; visit(value.opt(key), depth + 1) }
                is JSONArray -> for (index in 0 until minOf(value.length(), 8)) visit(value.opt(index), depth + 1)
            }
        }
        values.forEach { visit(it, 0) }
        return keys.joinToString(",")
    }

    private fun findMenuCommand(value: Any?, label: String, commandKey: String): String? = when (value) {
        is JSONObject -> {
            val renderer = value.optJSONObject("menuServiceItemRenderer")
                ?: value.optJSONObject("menuNavigationItemRenderer")
            val itemLabel = renderer?.optString("label").orEmpty()
                .ifBlank { text(renderer?.opt("text")).orEmpty() }
            val normalizedLabel = itemLabel.trim().lowercase()
            val expectedLabels = when (label.lowercase()) {
                "edit" -> setOf("edit", "editar")
                "delete" -> setOf("delete", "remove", "excluir", "remover")
                else -> setOf(label.lowercase())
            }
            if (renderer != null && normalizedLabel in expectedLabels) {
                findString(renderer, commandKey)
            } else value.keys().asSequence().mapNotNull { findMenuCommand(value.opt(it), label, commandKey) }.firstOrNull()
        }
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findMenuCommand(value.opt(it), label, commandKey) }.firstOrNull()
        else -> null
    }

    private fun browse(origin: String, browseId: String, clientName: String): List<FeedItem> {
        val bootstrap = bootstrap(origin, clientName)
        val body = JSONObject().put("context", context(clientName, bootstrap.version)).put("browseId", browseId)
        return parseItems(post("$origin/youtubei/v1/browse?key=${bootstrap.key}", body, origin))
    }

    private data class Bootstrap(
        val key: String,
        val version: String,
        val origin: String,
        val dataSyncId: String = "",
        val gvsBindToVideoId: Boolean = false,
    )

    private fun beginFeedLoad(name: String): Long {
        val generation = feedGeneration.incrementAndGet()
        activeFeedCall.getAndSet(null)?.cancel()
        AppLog.event("feed=$name generation=$generation")
        return generation
    }

    private fun bootstrap(origin: String, clientName: String, requestGeneration: Long? = null): Bootstrap {
        val baseOrigin = if (origin.contains("music.youtube.com")) "https://music.youtube.com" else "https://www.youtube.com"
        synchronized(bootstrapCache) {
            bootstrapCache["$baseOrigin:$clientName"]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }?.second?.let { return it }
        }
        val html = get(baseOrigin, requestGeneration = requestGeneration)
        val key = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)").find(html)?.groupValues?.get(1)
            ?: error("Unable to discover YouTube's internal API key")
        val version = Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)").find(html)?.groupValues?.get(1)
            ?: if (clientName == "WEB_REMIX") "1.20260901.01.00" else "2.20260901.00.00"
        clientVersions[baseOrigin] = version
        Regex("\"VISITOR_DATA\":\"([^\"]+)").find(html)?.groupValues?.get(1)?.let { visitorData[baseOrigin] = it }
        val dataSyncId = Regex("\"DATASYNC_ID\":\"([^\"]+)").find(html)?.groupValues?.get(1).orEmpty()
        val gvsBindToVideoId = html.contains("html5_generate_content_po_token=true") ||
            html.contains("html5_generate_content_po_token%3Dtrue") ||
            html.contains("html5_generate_content_po_token\\u003dtrue")
        return Bootstrap(key, version, baseOrigin, dataSyncId, gvsBindToVideoId).also {
            synchronized(bootstrapCache) { bootstrapCache["$baseOrigin:$clientName"] = System.currentTimeMillis() to it }
        }
    }

    private fun context(name: String, version: String): JSONObject {
        val origin = if (name == "WEB_REMIX") "https://music.youtube.com" else "https://www.youtube.com"
        return JSONObject().put("client", JSONObject().put("clientName", name).put("clientVersion", version)
            .put("hl", "en").put("gl", "US").apply { visitorData[origin]?.let { put("visitorData", it) } })
    }

    private fun get(url: String, userAgent: String = UA, requestGeneration: Long? = null): String {
        val origin = if (url.contains("music.youtube.com")) "https://music.youtube.com" else "https://www.youtube.com"
        val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept-Language", "en-US,en;q=0.9").apply {
            cookies(origin).takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
        }.build()
        val call = client.newCall(request)
        if (requestGeneration != null) {
            if (requestGeneration != feedGeneration.get()) throw CancellationException("Superseded feed bootstrap")
            activeFeedCall.getAndSet(call)?.cancel()
        }
        return try {
            call.execute().use {
                if (!it.isSuccessful) error("YouTube returned HTTP ${it.code}")
                val raw = it.body?.string().orEmpty()
                if (requestGeneration != null && requestGeneration != feedGeneration.get()) {
                    throw CancellationException("Superseded feed bootstrap")
                }
                raw
            }
        } finally {
            if (requestGeneration != null) activeFeedCall.compareAndSet(call, null)
        }
    }

    private fun englishCountLabel(value: String, label: String, fallback: String): String {
        val count = value.trim().ifBlank { fallback }
            .replace(Regex("(?i)\\s*(mil)\\b"), "K")
            .replace(Regex("(?i)\\s*(mi|milh(?:ão|ões))\\b"), "M")
            .replace(',', '.')
            .replace(Regex("(?i)\\s*(likes?|curtidas?|comments?|comentários?)\\s*$"), "")
            .trim().ifBlank { fallback }
        return "$count ${if (count == "1") label.removeSuffix("s") else label}"
    }

    private fun englishRelativeTime(value: String): String {
        val normalized = value.trim().lowercase()
            .removePrefix("há ").removeSuffix(" atrás")
        val match = Regex("(\\d+)\\s+(segundo|segundos|minuto|minutos|hora|horas|dia|dias|semana|semanas|mês|meses|ano|anos)").matchEntire(normalized)
            ?: return value
        val amount = match.groupValues[1]
        val unit = when (match.groupValues[2]) {
            "segundo", "segundos" -> "second"
            "minuto", "minutos" -> "minute"
            "hora", "horas" -> "hour"
            "dia", "dias" -> "day"
            "semana", "semanas" -> "week"
            "mês", "meses" -> "month"
            else -> "year"
        }
        return "$amount $unit${if (amount == "1") "" else "s"} ago"
    }

    private fun post(url: String, body: JSONObject, origin: String, requestGeneration: Long? = null): JSONObject {
        val request = Request.Builder().url(url).header("Content-Type", "application/json").apply {
            authenticatedHeaders(origin).forEach { (key, value) -> header(key, value) }
            header("X-Youtube-Client-Name", if (origin.contains("music.")) "67" else "1")
            clientVersions[origin]?.let { header("X-Youtube-Client-Version", it) }
            visitorData[origin]?.let { header("X-Goog-Visitor-Id", it) }
        }.post(body.toString().toRequestBody(jsonType)).build()
        val call = client.newCall(request)
        if (requestGeneration != null) {
            if (requestGeneration != feedGeneration.get()) throw CancellationException("Superseded feed request")
            activeFeedCall.getAndSet(call)?.cancel()
        }
        val started = SystemClock.elapsedRealtime()
        return call.execute().use {
            val headersMs = SystemClock.elapsedRealtime() - started
            val raw = it.body?.string().orEmpty()
            val bodyMs = SystemClock.elapsedRealtime() - started
            if (!it.isSuccessful) { AppLog.event("authenticated POST host=${request.url.host} HTTP ${it.code} body=${raw.take(400)}"); error("YouTube returned HTTP ${it.code}") }
            val parsed = JSONObject(raw)
            if (requestGeneration != null) {
                AppLog.event("feed HTTP host=${request.url.host} headersMs=$headersMs bodyMs=$bodyMs bytes=${raw.length}")
                activeFeedCall.compareAndSet(call, null)
                if (requestGeneration != feedGeneration.get()) throw CancellationException("Superseded feed response")
            }
            parsed
        }
    }

    private fun postAuthenticatedClient(url: String, body: JSONObject, origin: String, clientId: String, version: String, userAgent: String): JSONObject {
        val request = Request.Builder().url(url).header("Content-Type", "application/json").apply {
            authenticatedHeaders(origin).forEach { (key, value) -> header(key, value) }
            header("User-Agent", userAgent)
            header("X-Youtube-Client-Name", clientId)
            header("X-Youtube-Client-Version", version)
            visitorData[origin]?.let { header("X-Goog-Visitor-Id", it) }
        }.post(body.toString().toRequestBody(jsonType)).build()
        return client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                AppLog.event("authenticated client=$clientId HTTP ${response.code} body=${raw.take(400)}")
                error("YouTube returned HTTP ${response.code}")
            }
            JSONObject(raw)
        }
    }

    private fun postPublic(url: String, body: JSONObject, clientName: String, version: String): JSONObject {
        val request = Request.Builder().url(url).header("User-Agent", UA).header("Content-Type", "application/json")
            .header("X-Youtube-Client-Name", clientName).header("X-Youtube-Client-Version", version)
            .post(body.toString().toRequestBody(jsonType)).build()
        return client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) { AppLog.event("player public HTTP ${response.code} body=${raw.take(400)}"); error("YouTube player returned HTTP ${response.code}") }
            JSONObject(raw)
        }
    }

    private fun postPublicCommunity(url: String, body: JSONObject, version: String, referer: String): JSONObject {
        val request = Request.Builder().url(url)
            .header("User-Agent", WEB_PLAYER_UA)
            .header("Content-Type", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Origin", "https://www.youtube.com")
            .header("Referer", referer)
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", version)
            .post(body.toString().toRequestBody(jsonType)).build()
        return client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("YouTube returned HTTP ${response.code}")
            JSONObject(raw)
        }
    }

    private fun getPublicHtml(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", WEB_PLAYER_UA)
            .header("Accept-Language", "en-US,en;q=0.9").build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("YouTube returned HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    fun cookies(url: String): String = CookieManager.getInstance().getCookie(url).orEmpty()

    fun authorization(origin: String): String? {
        val all = cookies(origin).split(';').mapNotNull { p -> p.trim().split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val time = System.currentTimeMillis() / 1000
        fun token(label: String, value: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest("$time $value $origin".toByteArray()).joinToString("") { "%02x".format(it) }
            return "$label ${time}_$digest"
        }
        val primary = all["SAPISID"] ?: all["__Secure-3PAPISID"]
        return listOfNotNull(
            primary?.let { token("SAPISIDHASH", it) },
            all["__Secure-1PAPISID"]?.let { token("SAPISID1PHASH", it) },
            all["__Secure-3PAPISID"]?.let { token("SAPISID3PHASH", it) }
        ).takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun parseItems(root: Any): List<FeedItem> {
        val output = linkedMapOf<String, FeedItem>()
        walkFeedRenderers(root) { renderer ->
            val rendererType = renderer.optString("_materialytRendererType")
            val lockupPlaylistId = if (rendererType == "lockupViewModel") {
                renderer.optString("contentId").removePrefix("VL").takeIf { contentId ->
                    contentId.isNotBlank() && contentId.length != 11 && (
                        renderer.optString("contentType").contains("PLAYLIST", true) ||
                            findString(renderer, "browseId")?.startsWith("VL") == true ||
                            findString(renderer, "playlistId") != null
                    )
                }
            } else null
            // playlistVideoRenderer is a video inside a playlist, not a playlist card.
            val musicPlaylistId = if (rendererType.startsWith("music")) findString(renderer, "browseId")?.takeIf { it.startsWith("VL") } else null
            val playlistRenderer = rendererType in setOf("playlistRenderer", "gridPlaylistRenderer") || lockupPlaylistId != null || musicPlaylistId != null
            val channelRenderer = rendererType.contains("channel", true)
            val lockupVideoId = if (rendererType == "lockupViewModel" && renderer.optString("contentType").let {
                    it == "LOCKUP_CONTENT_TYPE_VIDEO" || it == "LOCKUP_CONTENT_TYPE_SHORT"
                }) renderer.optString("contentId").takeIf { it.length == 11 } else null
            val videoId = if (playlistRenderer || channelRenderer) "" else renderer.optString("videoId")
                .ifBlank { lockupVideoId.orEmpty() }
                .ifBlank { findString(renderer, "videoId").orEmpty() }
            val channelId = if (channelRenderer) renderer.optString("channelId")
                .ifBlank { findString(renderer, "browseId")?.takeIf { it.startsWith("UC") }.orEmpty() } else ""
            val authorChannelId = if (!channelRenderer) findString(renderer, "browseId")
                ?.takeIf { it.startsWith("UC") }.orEmpty() else ""
            val playlistId = if (playlistRenderer) renderer.optString("playlistId")
                .ifBlank { findString(renderer, "playlistId").orEmpty() }
                .ifBlank { lockupPlaylistId.orEmpty() }
                .ifBlank { musicPlaylistId.orEmpty() }
                .ifBlank { findString(renderer, "browseId")?.takeIf { it.startsWith("VL") }?.removePrefix("VL").orEmpty() } else ""
            val isChannel = channelRenderer && channelId.startsWith("UC")
            val isPlaylist = playlistRenderer && playlistId.isNotBlank()
            val id = when { isChannel -> channelId; isPlaylist -> playlistId.removePrefix("VL"); else -> videoId }
            if (!isChannel && !isPlaylist && id.length != 11) return@walkFeedRenderers
            val columns = flexColumnTexts(renderer)
            val rawTitle = text(renderer.opt("title")) ?: text(renderer.opt("headline")) ?: columns.firstOrNull()
                ?: deepText(renderer, "title") ?: deepText(renderer, "headline") ?: return@walkFeedRenderers
            // Autoplay containers can expose their UI heading ("Up Next") as `title`
            // while keeping the actual video's title in `videoTitle`. Never let that
            // heading poison the metadata cache of the video that is about to open.
            val title = if (rawTitle.trim().equals("Up Next", ignoreCase = true)) {
                sequenceOf(
                    text(renderer.opt("videoTitle")),
                    deepText(renderer, "videoTitle"),
                    text(renderer.opt("headline")),
                    deepText(renderer, "headline"),
                    columns.firstOrNull(),
                ).filterNotNull().firstOrNull {
                    it.isNotBlank() && !it.trim().equals("Up Next", ignoreCase = true)
                } ?: rawTitle
            } else rawTitle
            val channelSubtitle = if (isChannel) {
                val subs = text(renderer.opt("subscriberCountText"))
                val vids = text(renderer.opt("videoCountText"))
                listOfNotNull(subs, vids).joinToString(" • ").ifBlank { null }
            } else null
            val musicRenderer = rendererType.startsWith("music") || renderer.has("flexColumns")
            val musicArtist = if (musicRenderer) findMusicArtist(renderer, title) else null
            // A byline may render as "@handle • 1.2M subscribers" when all runs are joined.
            // The run carrying the UC browse endpoint is the actual channel name.
            val linkedChannelName = findLinkedChannelName(renderer)
            val shortByline = text(renderer.opt("shortBylineText"))
            val ownerText = text(renderer.opt("ownerText"))
            val longByline = text(renderer.opt("longBylineText"))
            val rendererSubtitle = text(renderer.opt("subtitle"))
            val fullMusicArtist = if (musicRenderer) {
                (listOf(musicArtist, linkedChannelName, shortByline, ownerText, longByline, rendererSubtitle) + columns.drop(1))
                    .mapNotNull { normalizedMusicArtist(it, title) }
                    .maxByOrNull(String::length)
            } else null
            val rawSubtitle = channelSubtitle ?: fullMusicArtist ?: linkedChannelName ?: shortByline ?: ownerText ?: longByline ?: rendererSubtitle
                ?: columns.drop(1).firstOrNull()
                ?: deepText(renderer, "shortBylineText") ?: deepText(renderer, "ownerText") ?: deepText(renderer, "longBylineText") ?: deepText(renderer, "subtitle")
                ?: inferChannelName(renderer, title)?.takeIf { '<' !in it && '>' !in it } ?: "Unknown channel"
            var subtitle = (if (musicRenderer) {
                normalizedMusicArtist(rawSubtitle, title).orEmpty().ifBlank { rawSubtitle }
            } else rawSubtitle).replace(Regex("(\\d+),(\\d+)"), "$1.$2")
            if (!isChannel && !isPlaylist && isChannelMetadataLabel(subtitle) && authorChannelId.isNotBlank()) {
                subtitle = resolveChannelName(authorChannelId, id).orEmpty().ifBlank { subtitle }
            }
            // walkFeedRenderers already rejects ad subtrees before yielding a renderer. Repeating
            // the recursive ad scan here doubled the work for every history entry.
            if (isAdString(title) || isAdString(subtitle)) return@walkFeedRenderers
            val thumbnails = renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                ?: renderer.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val selectedThumb = thumbnails?.let { candidates ->
                (0 until candidates.length()).mapNotNull(candidates::optJSONObject).maxByOrNull { candidate ->
                    candidate.optLong("width").coerceAtLeast(1L) * candidate.optLong("height").coerceAtLeast(1L)
                }
            }
            val providedThumbnail = selectedThumb?.optString("url").orEmpty()
                .ifBlank { findThumbnail(renderer).orEmpty() }.let { if (it.startsWith("//")) "https:$it" else it }
            val isMusicItem = renderer.has("flexColumns") || renderer.has("thumbnailRenderer") || renderer.has("subtitle")
            val thumbWidth = if (isMusicItem || isChannel) selectedThumb?.optInt("width")?.takeIf { it > 0 } ?: 1 else 16
            val thumbHeight = if (isMusicItem || isChannel) selectedThumb?.optInt("height")?.takeIf { it > 0 } ?: 1 else 9
            val rawThumbnail = if ((isMusicItem || isPlaylist || isChannel) && providedThumbnail.isNotBlank()) providedThumbnail else "https://i.ytimg.com/vi/$id/maxresdefault.jpg"
            val thumbnail = if (isMusicItem) highResolutionArtworkUrl(rawThumbnail, thumbWidth, thumbHeight) else rawThumbnail
            val channelThumbnail = findChannelThumbnail(renderer).orEmpty().ifBlank { if (isChannel) thumbnail else "" }
            val url = when { isChannel -> "https://www.youtube.com/channel/$id"; isPlaylist -> "https://www.youtube.com/playlist?list=$id"; else -> "https://www.youtube.com/watch?v=$id" }
            val resume = scanResumeMetadata(renderer)
            val exactResumeMs = resume.resumePlaybackPositionMs?.toLong()
                ?: resume.startTimeSeconds?.times(1_000.0)?.toLong()
            val resumePercent = (resume.percentDurationWatched ?: resume.startPercent ?: 0.0).coerceIn(0.0, 100.0)
            val durationText = text(renderer.opt("lengthText")) ?: resume.durationText
            val durationMs = parseDurationMs(durationText.orEmpty())
            val rawResumePositionMs = exactResumeMs?.takeIf { it > 0L }
                ?: if (resumePercent > 0.0 && durationMs > 0L) (durationMs * resumePercent / 100.0).toLong() else 0L
            val resumePositionMs = if (
                resumePercent >= 99.5 ||
                (durationMs > 0L && rawResumePositionMs >= durationMs - 1_500L)
            ) 0L else rawResumePositionMs
            val musicVideoType = findString(renderer, "musicVideoType").orEmpty()
            val item = FeedItem(id, title, subtitle, thumbnail, thumbWidth, thumbHeight, channelThumbnail, url, isPlaylist, isChannel,
                channelUrl = authorChannelId.takeIf { it.startsWith("UC") }?.let { "https://www.youtube.com/channel/$it" }.orEmpty(),
                resumePositionMs = resumePositionMs,
                musicVideo = musicVideoType.isNotBlank() && musicVideoType != "MUSIC_VIDEO_TYPE_ATV")
            if (!isPlaylist && !isChannel) synchronized(videoDetailsCache) {
                videoDetailsCache[id] = (videoDetailsCache[id] ?: VideoDetails(title, subtitle)).copy(
                    title = title,
                    author = subtitle,
                    authorUrl = authorChannelId.takeIf { it.startsWith("UC") }?.let { "https://www.youtube.com/channel/$it" }.orEmpty(),
                    authorAvatar = channelThumbnail,
                )
            }
            val existing = output[id]
            if (existing == null) output[id] = item
            else if (resumePositionMs > existing.resumePositionMs) output[id] = existing.copy(resumePositionMs = resumePositionMs)
        }
        return output.values.toList()
    }

    private fun parseDurationMs(value: String): Long {
        val parts = value.trim().split(':').mapNotNull(String::toLongOrNull)
        if (parts.isEmpty() || parts.size > 3) return 0L
        return parts.fold(0L) { total, part -> total * 60L + part } * 1_000L
    }

    private data class ResumeMetadata(
        var resumePlaybackPositionMs: Double? = null,
        var startTimeSeconds: Double? = null,
        var percentDurationWatched: Double? = null,
        var startPercent: Double? = null,
        var durationText: String? = null,
    )

    /** Collect all history-progress fields in one tree pass instead of recursively rescanning it. */
    private fun scanResumeMetadata(root: Any?): ResumeMetadata {
        val result = ResumeMetadata()
        fun number(value: Any?): Double? = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    val child = value.opt(key)
                    when (key) {
                        "resumePlaybackPositionMs" -> if (result.resumePlaybackPositionMs == null) result.resumePlaybackPositionMs = number(child)
                        "startTimeSeconds" -> if (result.startTimeSeconds == null) result.startTimeSeconds = number(child)
                        "percentDurationWatched" -> if (result.percentDurationWatched == null) result.percentDurationWatched = number(child)
                        "startPercent" -> if (result.startPercent == null) result.startPercent = number(child)
                        "lengthText" -> if (result.durationText == null) result.durationText = text(child)
                    }
                    walk(child)
                }
                is JSONArray -> for (index in 0 until value.length()) walk(value.opt(index))
            }
        }
        walk(root)
        return result
    }

    private fun findNumber(value: Any?, key: String): Double? = when (value) {
        is JSONObject -> {
            value.opt(key).let { direct ->
                when (direct) {
                    is Number -> direct.toDouble()
                    is String -> direct.toDoubleOrNull()
                    else -> null
                }
            } ?: value.keys().asSequence().mapNotNull { findNumber(value.opt(it), key) }.firstOrNull()
        }
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findNumber(value.opt(it), key) }.firstOrNull()
        else -> null
    }

    private fun findDurationText(value: Any?): String? = when (value) {
        is String -> value.trim().takeIf { it.matches(Regex("\\d{1,3}:\\d{2}(?::\\d{2})?")) }
        is JSONObject -> value.keys().asSequence().mapNotNull { findDurationText(value.opt(it)) }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findDurationText(value.opt(it)) }.firstOrNull()
        else -> null
    }

    private fun findSectionItems(value: Any?, label: String): List<FeedItem> {
        when (value) {
            is JSONObject -> {
                if (text(value.opt("title"))?.equals(label, true) == true || text(value.opt("headline"))?.equals(label, true) == true) {
                    parseItems(value).takeIf { it.isNotEmpty() }?.let { return it }
                }
                value.keys().forEach { key -> findSectionItems(value.opt(key), label).takeIf { it.isNotEmpty() }?.let { return it } }
            }
            is JSONArray -> for (index in 0 until value.length()) findSectionItems(value.opt(index), label).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    private fun findOwnChannelId(value: Any?): String? {
        when (value) {
            is JSONObject -> {
                val labels = listOfNotNull(text(value.opt("title")), text(value.opt("text")), text(value.opt("label")))
                if (labels.any { it.equals("Your channel", true) || it.equals("View your channel", true) }) {
                    findFirstChannelId(value)?.let { return it }
                }
                value.keys().forEach { key -> findOwnChannelId(value.opt(key))?.let { return it } }
            }
            is JSONArray -> for (index in 0 until value.length()) findOwnChannelId(value.opt(index))?.let { return it }
        }
        return null
    }

    private fun findFirstChannelId(value: Any?): String? {
        when (value) {
            is JSONObject -> {
                value.optString("channelId").takeIf { it.startsWith("UC") }?.let { return it }
                value.optString("browseId").takeIf { it.startsWith("UC") }?.let { return it }
                value.keys().forEach { key -> findFirstChannelId(value.opt(key))?.let { return it } }
            }
            is JSONArray -> for (index in 0 until value.length()) findFirstChannelId(value.opt(index))?.let { return it }
        }
        return null
    }

    private fun walkFeedRenderers(value: Any?, blockedByAd: Boolean = false, consumer: (JSONObject) -> Unit) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().toList()
                val blocked = blockedByAd || keys.any { key ->
                    key.contains("adSlot", true) || key.contains("promoted", true) ||
                        key.contains("displayAd", true) || key.contains("carouselAd", true) ||
                        key.contains("adPlacement", true) || key.contains("playerAd", true) ||
                        key.contains("inFeedAd", true) || key.contains("adLayout", true) ||
                        key.contains("aboutThisAd", true) || key.contains("adBadge", true) ||
                        key.contains("advertiser", true)
                } || containsAdMarker(value.opt("adSlotRenderer")) ||
                     containsAdMarker(value.opt("inFeedAdLayoutRenderer")) ||
                     containsAdMarker(value.opt("adPlacementRenderer"))
                val consumedKeys = HashSet<String>()
                if (!blocked) {
                    val supported = setOf(
                        "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer", "playlistRenderer", "gridPlaylistRenderer", "channelRenderer", "gridChannelRenderer",
                        "videoWithContextRenderer", "videoCardRenderer", "reelItemRenderer", "musicTwoRowItemRenderer", "musicResponsiveListItemRenderer", "playlistPanelVideoRenderer", "lockupViewModel"
                    )
                    keys.forEach { key ->
                        val candidate = value.optJSONObject(key) ?: return@forEach
                        val directVideo = candidate.optString("videoId").length == 11
                        val videoContainer = (key.endsWith("Renderer") || key.endsWith("ViewModel")) && directVideo
                        if (key in supported || videoContainer) {
                            consumedKeys += key
                            candidate.put("_materialytRendererType", key)
                            // Ad containers are already propagated through `blocked`, and the
                            // parsed title/subtitle are checked before an item is emitted. A deep
                            // scan of every full renderer here was the remaining dominant cost on
                            // Android 7 history responses.
                            consumer(candidate)
                        }
                    }
                }
                // A recognized renderer is one complete feed item. Its metadata subtree contains
                // many nested endpoints/text objects but never another independent feed result.
                // Walking it again made history parsing effectively quadratic on old devices.
                keys.filterNot(consumedKeys::contains).forEach { walkFeedRenderers(value.opt(it), blocked, consumer) }
            }
            is JSONArray -> for (index in 0 until value.length()) walkFeedRenderers(value.opt(index), blockedByAd, consumer)
        }
    }

    private fun isAdString(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.equals("ad", ignoreCase = true)) return true
        val lower = trimmed.lowercase(java.util.Locale.ROOT)
        return lower == "sponsored" || lower == "patrocinado" || lower == "patrocinada" ||
            lower == "anúncio" || lower == "anuncio" || lower == "publicidade" ||
            lower == "promoted" ||
            lower.startsWith("sponsored") || lower.startsWith("patrocinad") ||
            lower.startsWith("anúncio") || lower.startsWith("anuncio") ||
            lower.startsWith("publicidade")
    }

    private fun containsAdMarker(value: Any?): Boolean = when (value) {
        is JSONObject -> value.keys().asSequence().any { key ->
            key.contains("adBadge", true) || key.contains("promoted", true) ||
                key.contains("advertiser", true) || key.contains("adSlot", true) ||
                key.contains("inFeedAd", true) || key.contains("adPlacement", true) ||
                key.contains("adLayout", true) || key.contains("aboutThisAd", true) ||
                key.contains("adContext", true) || key.contains("adTag", true) ||
                key.contains("displayAd", true) ||
                key.contains("carouselAd", true) || key.contains("playerAd", true) ||
                containsAdMarker(value.opt(key))
        }
        is JSONArray -> (0 until value.length()).any { containsAdMarker(value.opt(it)) }
        is String -> isAdString(value)
        else -> false
    }

    private fun deepText(value: Any?, key: String): String? = when (value) {
        is JSONObject -> text(value.opt(key)) ?: value.keys().asSequence().mapNotNull { deepText(value.opt(it), key) }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { deepText(value.opt(it), key) }.firstOrNull()
        else -> null
    }

    private fun flexColumnTexts(renderer: JSONObject): List<String> {
        val columns = renderer.optJSONArray("flexColumns") ?: return emptyList()
        return (0 until columns.length()).mapNotNull { index ->
            deepText(columns.opt(index), "text")?.takeIf { it.isNotBlank() }
        }
    }

    private fun normalizedMusicArtist(value: String?, title: String): String? {
        val parts = value?.split(" • ")?.map(String::trim)?.filter(String::isNotBlank).orEmpty()
        if (parts.isEmpty()) return null
        val leadingType = parts.first().lowercase() in setOf("song", "video", "episode", "album", "playlist")
        return (if (leadingType) parts.drop(1) else parts).asSequence()
            .map { it.replace(Regex("\\s+-\\s+Topic$", RegexOption.IGNORE_CASE), "").trim() }
            .firstOrNull { candidate ->
                candidate.isNotBlank() && candidate != title &&
                    !candidate.matches(Regex("\\d{1,2}:\\d{2}(:\\d{2})?")) &&
                    !candidate.matches(Regex("\\d{4}(-\\d{2}-\\d{2}.*)?")) &&
                    !candidate.matches(Regex("(?i)(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s+\\d{1,2},?\\s+\\d{4}")) &&
                    !candidate.matches(Regex("(?i)\\d+\\s+(second|minute|hour|day|week|month|year)s?\\s+ago")) &&
                    !candidate.contains(Regex("(?i)\\b(views?|plays?|released|published)\\b"))
            }
    }

    private fun findMusicArtist(value: Any?, title: String): String? {
        when (value) {
            is JSONObject -> {
                value.optJSONArray("runs")?.let { runs ->
                    val allRuns = (0 until runs.length()).mapNotNull { runs.optJSONObject(it) }
                    fun isArtistRun(run: JSONObject): Boolean {
                        val browse = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                        val browseId = browse?.optString("browseId").orEmpty()
                        val pageType = browse?.optJSONObject("browseEndpointContextSupportedConfigs")
                            ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType").orEmpty()
                        return browseId.startsWith("UC") || pageType == "MUSIC_PAGE_TYPE_ARTIST"
                    }
                    val artistIndexes = allRuns.indices.filter { isArtistRun(allRuns[it]) }
                    if (artistIndexes.isNotEmpty()) {
                        allRuns.subList(artistIndexes.first(), artistIndexes.last() + 1)
                            .joinToString("") { (it.opt("text") as? String).orEmpty() }
                            .trim().takeIf { it.isNotBlank() && it != title && !it.startsWith("Go to ", true) }
                            ?.let { return it }
                    }
                }
                value.keys().forEach { findMusicArtist(value.opt(it), title)?.let { return it } }
            }
            is JSONArray -> for (index in 0 until value.length()) findMusicArtist(value.opt(index), title)?.let { return it }
        }
        return null
    }

    private fun findLinkedChannelName(value: Any?): String? {
        when (value) {
            is JSONObject -> {
                value.optJSONArray("runs")?.let { runs ->
                    for (index in 0 until runs.length()) {
                        val run = runs.optJSONObject(index) ?: continue
                        val browseId = run.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
                        val name = run.optString("text").trim()
                        if (browseId.startsWith("UC") && name.isNotBlank() && !isChannelMetadataLabel(name)) return name
                    }
                }
                value.keys().forEach { key -> findLinkedChannelName(value.opt(key))?.let { return it } }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                findLinkedChannelName(value.opt(index))?.let { return it }
            }
        }
        return null
    }

    private fun resolveChannelName(channelId: String, videoId: String): String? {
        synchronized(channelNameCache) { channelNameCache[channelId]?.let { return it } }
        val origin = "https://www.youtube.com"
        return runCatching {
            // videoDetails.author is the uploader's primary name. Channel browse titles honor
            // hl and may therefore return an English/localized alias instead.
            val primaryName = runCatching {
                visionOsPlayer(videoId).optJSONObject("videoDetails")
                    ?.optString("author")?.trim().orEmpty()
                    .takeIf { it.isNotBlank() && !isChannelMetadataLabel(it) }
            }.getOrNull()
            val name = primaryName ?: run {
                val bootstrap = bootstrap(origin, "WEB")
                val response = post(
                    "$origin/youtubei/v1/browse?key=${bootstrap.key}",
                    JSONObject().put("context", context("WEB", bootstrap.version)).put("browseId", channelId),
                    origin,
                )
                val metadata = findObject(response, "channelMetadataRenderer")?.optJSONObject("channelMetadataRenderer")
                metadata?.optString("title")?.trim().orEmpty()
                    .ifBlank { findObject(response, "pageHeaderRenderer")?.optJSONObject("pageHeaderRenderer")?.let { text(it.opt("pageTitle")) }.orEmpty().trim() }
                    .takeIf { it.isNotBlank() && !isChannelMetadataLabel(it) }
            }
            name?.also { synchronized(channelNameCache) { channelNameCache[channelId] = it } }
        }.onFailure { AppLog.failure("primary channel name video=$videoId channel=$channelId", it) }.getOrNull()
    }

    private fun inferChannelName(renderer: JSONObject, title: String): String? {
        val found = mutableListOf<String>()
        fun collect(value: Any?) {
            when (value) {
                is JSONObject -> {
                    text(value)?.takeIf { it.isNotBlank() }?.let(found::add)
                    value.keys().forEach { collect(value.opt(it)) }
                }
                is JSONArray -> for (index in 0 until value.length()) collect(value.opt(index))
            }
        }
        collect(renderer.opt("metadata"))
        collect(renderer.opt("byline"))
        return found.firstOrNull { candidate ->
            candidate != title && !candidate.contains("view", true) && !candidate.matches(Regex("\\d{1,2}:\\d{2}(:\\d{2})?")) &&
                !candidate.equals("New", true) && !candidate.equals("Mix", true)
        }
    }

    private fun isYouTubeImageUrl(url: String): Boolean =
        url.contains("ytimg.com") || url.contains("ggpht.com") || url.contains("googleusercontent.com")

    private fun normalizeImageUrl(url: String): String = if (url.startsWith("//")) "https:$url" else url

    private fun highResolutionArtworkUrl(url: String, declaredWidth: Int, declaredHeight: Int): String {
        val normalized = normalizeImageUrl(url)
        if (!normalized.contains("ggpht.com") && !normalized.contains("googleusercontent.com")) return normalized
        val dimensions = Regex("=w(\\d+)-h(\\d+)").find(normalized)
        val sourceWidth = declaredWidth.takeIf { it > 1 } ?: dimensions?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        val sourceHeight = declaredHeight.takeIf { it > 1 } ?: dimensions?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1
        val scale = 1200f / maxOf(sourceWidth, sourceHeight).coerceAtLeast(1)
        val targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        return when {
            dimensions != null -> normalized.replaceRange(dimensions.range, "=w$targetWidth-h$targetHeight")
            Regex("=s\\d+(?=-|$)").containsMatchIn(normalized) -> normalized.replace(Regex("=s\\d+(?=-|$)"), "=s1200")
            else -> normalized
        }
    }

    private fun findThumbnail(value: Any?): String? = when (value) {
        is JSONObject -> value.optString("url").takeIf(::isYouTubeImageUrl)?.let(::normalizeImageUrl)
            ?: value.keys().asSequence().mapNotNull { findThumbnail(value.opt(it)) }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findThumbnail(value.opt(it)) }.firstOrNull()
        else -> null
    }

    private fun findChannelThumbnail(value: Any?): String? = when (value) {
        is JSONObject -> value.keys().asSequence()
            .filter { it.contains("avatar", true) || it.contains("channelThumbnail", true) }
            .mapNotNull { findThumbnail(value.opt(it)) }.firstOrNull()
            ?: value.keys().asSequence().mapNotNull { findChannelThumbnail(value.opt(it)) }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findChannelThumbnail(value.opt(it)) }.firstOrNull()
        else -> null
    }

    private fun initialData(html: String): JSONObject {
        val assignment = Regex("""(?:(?:var\s+)?ytInitialData|window\[\s*["']ytInitialData["']\s*])\s*=\s*""")
        assignment.findAll(html).forEach { match ->
            var start = match.range.last + 1
            while (start < html.length && html[start].isWhitespace()) start++
            if (html.regionMatches(start, "JSON.parse", 0, "JSON.parse".length)) {
                start = html.indexOf('(', start).takeIf { it >= 0 }?.plus(1) ?: return@forEach
                while (start < html.length && html[start].isWhitespace()) start++
            }
            if (start >= html.length) return@forEach
            val raw = when (html[start]) {
                '{' -> extractObject(html, start)
                '\'', '"' -> extractJsString(html, start)
                else -> null
            }
            raw?.let {
                runCatching { JSONObject(it) }.getOrNull()?.let { parsed ->
                    if (parsed.has("contents") || parsed.has("continuationContents")) return parsed
                    parsed.optJSONObject("response")?.let { response ->
                        if (response.has("contents") || response.has("continuationContents")) return response
                    }
                }
            }
        }
        error("YouTube did not include valid initial feed data")
    }

    private fun extractObject(html: String, start: Int): String? {
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until html.length) {
            val char = html[index]
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) return html.substring(start, index + 1)
            }
        }
        return null
    }

    private fun extractJsString(source: String, start: Int): String? {
        val quote = source[start]
        val raw = StringBuilder()
        var index = start + 1
        while (index < source.length) {
            val char = source[index]
            if (char == quote) break
            if (char == '\\' && index + 1 < source.length) {
                when (val escaped = source[++index]) {
                    'x' -> if (index + 2 < source.length) { raw.append(source.substring(index + 1, index + 3).toInt(16).toChar()); index += 2 }
                    'u' -> if (index + 4 < source.length) { raw.append(source.substring(index + 1, index + 5).toInt(16).toChar()); index += 4 }
                    'n' -> raw.append('\n')
                    'r' -> raw.append('\r')
                    't' -> raw.append('\t')
                    else -> raw.append(escaped)
                }
            } else raw.append(char)
            index++
        }
        return raw.toString()
    }

    private fun walk(value: Any?, renderer: (JSONObject) -> Unit) {
        when (value) {
            is JSONObject -> {
                if (value.has("videoId") || value.has("contentId") || value.has("navigationEndpoint")) renderer(value)
                value.keys().forEach { walk(value.opt(it), renderer) }
            }
            is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), renderer)
        }
    }

    private fun text(value: Any?): String? = when (value) {
        is String -> value
        is JSONObject -> value.optString("simpleText").takeIf { it.isNotBlank() } ?: value.optString("content").takeIf { it.isNotBlank() }
            ?: value.optJSONArray("runs")?.let { runs -> (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() } }.takeIf { !it.isNullOrBlank() }
        else -> null
    }

    private fun findString(value: Any?, key: String): String? = when (value) {
        is JSONObject -> value.optString(key).takeIf { it.isNotBlank() } ?: value.keys().asSequence().mapNotNull { findString(value.opt(it), key) }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findString(value.opt(it), key) }.firstOrNull()
        else -> null
    }

    private fun findObject(value: Any?, key: String): JSONObject? = when (value) {
        is JSONObject -> if (value.has(key)) value else value.keys().asSequence().mapNotNull { findObject(value.opt(it), key) }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findObject(value.opt(it), key) }.firstOrNull()
        else -> null
    }

    private fun findArray(value: Any?, key: String): JSONArray? = when (value) {
        is JSONObject -> value.optJSONArray(key) ?: value.keys().asSequence().mapNotNull { findArray(value.opt(it), key) }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findArray(value.opt(it), key) }.firstOrNull()
        else -> null
    }

    private fun findContinuationToken(value: Any?): String? = when (value) {
        is JSONObject -> value.optJSONObject("continuationCommand")?.optString("token")?.takeIf { it.isNotBlank() }
            ?: value.optJSONObject("nextContinuationData")?.optString("continuation")?.takeIf { it.isNotBlank() }
            ?: value.optJSONObject("nextRadioContinuationData")?.optString("continuation")?.takeIf { it.isNotBlank() }
            ?: value.optJSONObject("reloadContinuationData")?.optString("continuation")?.takeIf { it.isNotBlank() }
            ?: value.keys().asSequence().mapNotNull { findContinuationToken(value.opt(it)) }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findContinuationToken(value.opt(it)) }.firstOrNull()
        else -> null
    }

    private fun findNextContinuationToken(value: Any?, previous: String?): String? {
        val pageTokens = linkedSetOf<String>()
        val fallbackTokens = linkedSetOf<String>()
        fun collect(node: Any?) {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("continuationItemRenderer")?.let(::findContinuationToken)
                        ?.takeIf(String::isNotBlank)?.let(pageTokens::add)
                    node.optJSONObject("continuationCommand")?.optString("token")?.takeIf(String::isNotBlank)?.let(fallbackTokens::add)
                    node.optJSONObject("nextContinuationData")?.optString("continuation")?.takeIf(String::isNotBlank)?.let(fallbackTokens::add)
                    node.optJSONObject("reloadContinuationData")?.optString("continuation")?.takeIf(String::isNotBlank)?.let(fallbackTokens::add)
                    node.keys().forEach { collect(node.opt(it)) }
                }
                is JSONArray -> for (index in 0 until node.length()) collect(node.opt(index))
            }
        }
        collect(value)
        return pageTokens.firstOrNull { it != previous } ?: fallbackTokens.firstOrNull { it != previous }
    }

    private fun findRadioContinuationToken(value: Any?, previous: String?): String? {
        fun find(node: Any?): String? = when (node) {
            is JSONObject -> node.optJSONObject("nextRadioContinuationData")
                ?.optString("continuation")?.takeIf { it.isNotBlank() && it != previous }
                ?: node.keys().asSequence().mapNotNull { find(node.opt(it)) }.firstOrNull()
            is JSONArray -> (0 until node.length()).asSequence().mapNotNull { find(node.opt(it)) }.firstOrNull()
            else -> null
        }
        return find(value)
    }

    private fun findStringNearKey(value: Any?, key: String, hint: String): String? = when (value) {
        is JSONObject -> {
            val candidate = value.optString(key)
            if (candidate.contains(hint, ignoreCase = true)) candidate
            else value.keys().asSequence().mapNotNull { findStringNearKey(value.opt(it), key, hint) }.firstOrNull()
        }
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findStringNearKey(value.opt(it), key, hint) }.firstOrNull()
        else -> null
    }

    private fun findLyricsBrowseId(value: Any?): String? = when (value) {
        is JSONObject -> {
            val title = text(value.opt("title")).orEmpty()
            if (title.equals("lyrics", ignoreCase = true)) findString(value, "browseId")
            else value.keys().asSequence().mapNotNull { findLyricsBrowseId(value.opt(it)) }.firstOrNull()
        }
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findLyricsBrowseId(value.opt(it)) }.firstOrNull()
        else -> null
    }

    private fun findLongestText(value: Any?): String {
        val found = mutableListOf<String>()
        fun collect(node: Any?) {
            when (node) {
                is JSONObject -> {
                    text(node.opt("description"))?.let(found::add)
                    text(node.opt("text"))?.let(found::add)
                    node.keys().forEach { collect(node.opt(it)) }
                }
                is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i))
            }
        }
        collect(value)
        return found.maxByOrNull { it.length }.orEmpty()
    }
}
