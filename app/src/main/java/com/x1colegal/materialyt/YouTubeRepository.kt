package com.x1colegal.materialyt

import android.webkit.CookieManager
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import android.os.SystemClock
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.roundToInt

data class FeedItem(val id: String, val title: String, val subtitle: String, val thumbnail: String, val thumbnailWidth: Int, val thumbnailHeight: Int, val channelThumbnail: String, val url: String, val playlist: Boolean = false, val channel: Boolean = false, val channelUrl: String = "", val resumePositionMs: Long = 0L)
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
data class CommunityPost(val id: String, val text: String, val published: String, val likes: String, val comments: String, val image: String = "")
data class CommunityComment(val id: String, val author: String, val text: String, val published: String, val likes: String)
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
    const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
    const val WEB_PLAYER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    const val VISIONOS_UA = "com.google.visionosyoutube/1.03 (RealityDevice17,1; U; CPU visionOS 26_6_1 like Mac OS X; en_US) gzip"
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val clientVersions = mutableMapOf<String, String>()
    private val visitorData = mutableMapOf<String, String>()
    private val standaloneWebVisitors = mutableMapOf<String, Pair<Long, String>>()
    private val bootstrapCache = mutableMapOf<String, Pair<Long, Bootstrap>>()
    private val feedGeneration = AtomicLong(0L)
    private val sessionOwnedCommentIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val sessionOwnedCommentAuthors = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var signedInCommentChannelId: String? = null
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
    @Volatile private var musicSearchToken: String? = null
    @Volatile private var activeMusicQuery: String? = null
    private val homeSeenIds = linkedSetOf<String>()
    private val shortsSeenIds = linkedSetOf<String>()

    fun home(): List<FeedItem> {
        AppLog.event("feed=home started")
        val generation = beginFeedLoad("home")
        return try {
            val started = SystemClock.elapsedRealtime()
            val bootstrap = bootstrap("https://www.youtube.com", "WEB")
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
    fun playlistVideos(playlistId: String): List<FeedItem> {
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        val normalizedId = playlistId.removePrefix("VL")
        var page = post("$origin/youtubei/v1/browse?key=${bootstrap.key}", JSONObject().put("context", context("WEB", bootstrap.version)).put("browseId", "VL$normalizedId"), origin)
        val items = parseItems(page).filterNot { it.playlist }.toMutableList()
        val seen = mutableSetOf<String>()
        repeat(8) {
            val token = findContinuationToken(page) ?: return@repeat
            if (!seen.add(token)) return@repeat
            page = post("$origin/youtubei/v1/browse?key=${bootstrap.key}", JSONObject().put("context", context("WEB", bootstrap.version)).put("continuation", token), origin)
            items += parseItems(page).filterNot { it.playlist }
        }
        return items.distinctBy { it.id }
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
                        if (content.isNotBlank()) posts.putIfAbsent(id, CommunityPost(
                            id = id,
                            text = content,
                            published = englishRelativeTime(text(renderer.opt("publishedTimeText")).orEmpty()).ifBlank { "Date unavailable" },
                            likes = englishCountLabel(nestedText(renderer, setOf("voteCount", "likeCount", "likeCountText")).orEmpty(), "likes", "0"),
                            comments = englishCountLabel(commentCount.orEmpty(), "comments", "0"),
                            image = findThumbnail(renderer).orEmpty()
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

    fun communityPostComments(postId: String): List<CommunityComment> {
        var data = initialData(get("https://www.youtube.com/post/$postId"))
        val comments = linkedMapOf<String, CommunityComment>()
        fun collect(value: Any?) {
            when (value) {
                is JSONObject -> {
                    value.optJSONObject("commentEntityPayload")?.let { entity ->
                        val properties = entity.optJSONObject("properties") ?: JSONObject()
                        val author = entity.optJSONObject("author") ?: JSONObject()
                        val toolbar = entity.optJSONObject("toolbar") ?: JSONObject()
                        val body = properties.optJSONObject("content")?.optString("content").orEmpty()
                        val id = properties.optString("commentId")
                        if (id.isNotBlank() && body.isNotBlank()) {
                            comments.putIfAbsent(id, CommunityComment(
                                id,
                                author.optString("displayName").ifBlank { "YouTube user" },
                                body,
                                properties.optString("publishedTime"),
                                toolbar.optString("likeCountA11y")
                                    .ifBlank { toolbar.optString("likeCountNotliked") }
                            ))
                        }
                    }
                    value.optJSONObject("commentRenderer")?.let { renderer ->
                        val body = text(renderer.opt("contentText")).orEmpty()
                        if (body.isNotBlank()) {
                            val id = renderer.optString("commentId").ifBlank { body.hashCode().toString() }
                            comments.putIfAbsent(id, CommunityComment(id,
                                text(renderer.opt("authorText")).orEmpty().ifBlank { "YouTube user" }, body,
                                text(renderer.opt("publishedTimeText")).orEmpty(),
                                text(renderer.opt("voteCount")).orEmpty()))
                        }
                    }
                    value.keys().forEach { collect(value.opt(it)) }
                }
                is JSONArray -> for (index in 0 until value.length()) collect(value.opt(index))
            }
        }
        collect(data)
        val seen = mutableSetOf<String>()
        repeat(4) { page ->
            val continuation = if (page == 0) findCommunityCommentsContinuation(data)
                else findTopLevelCommentsContinuation(data)
            if (continuation == null) return@repeat
            if (!seen.add(continuation.token)) return@repeat
            val bootstrap = bootstrap("https://www.youtube.com", "WEB")
            AppLog.event("community comments post=$postId page=$page endpoint=${continuation.apiPath}")
            data = post("https://www.youtube.com${continuation.apiPath}?key=${bootstrap.key}",
                JSONObject().put("context", context("WEB", bootstrap.version)).put("continuation", continuation.token), bootstrap.origin)
            collect(data)
        }
        AppLog.event("community comments post=$postId items=${comments.size}")
        return comments.values.toList()
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
            } else value.keys().asSequence().mapNotNull { findCommunityCommentsContinuation(value.opt(it)) }.firstOrNull()
        }
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findCommunityCommentsContinuation(value.opt(it)) }.firstOrNull()
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
    fun music(): List<FeedItem> = musicRequest("browse", JSONObject().put("browseId", "FEmusic_home")).also { activeMusicQuery = null }

    fun searchSuggestions(query: String): List<String> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
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

    fun musicSearch(query: String): List<FeedItem> = musicRequest("search", JSONObject().put("query", query)
        .put("params", "EgWKAQIIAWoMEA4QChADEAQQCRAF")).also { activeMusicQuery = query }

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
            parseItems(response).distinctBy { it.id }
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
        return parseItems(response).also { AppLog.event("music endpoint=$endpoint items=${it.size}") }
    }

    private fun feed(name: String, url: String): List<FeedItem> {
        AppLog.event("feed=$name started")
        val generation = beginFeedLoad(name)
        return try {
            val started = SystemClock.elapsedRealtime()
            val browseId = when (name) { "home" -> "FEwhat_to_watch"; "history" -> "FEhistory"; "playlists" -> "FEplaylist_aggregation"; else -> null }
            val bootstrap = bootstrap("https://www.youtube.com", "WEB")
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
        val allowNewPipeFallback = PlaybackBackendPreferences.backend != PlaybackBackend.WEB_ONLY
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
        val trackingAsMusic = false
        val authenticatedTracking = runCatching { authenticatedPlayerTracking(videoId, cpn, trackingAsMusic) }
            .onFailure { AppLog.failure("authenticated tracking video=$videoId music=$music", it) }.getOrNull()
        (authenticatedTracking ?: response.optJSONObject("playbackTracking"))?.let { tracking ->
            val playback = tracking.optJSONObject("videostatsPlaybackUrl")?.optString("baseUrl").orEmpty()
            val watchtime = tracking.optJSONObject("videostatsWatchtimeUrl")?.optString("baseUrl").orEmpty()
            if (playback.isNotBlank() && watchtime.isNotBlank()) synchronized(playbackTracking) { playbackTracking[videoId] = PlaybackTracking(playback, watchtime, cpn, trackingAsMusic) }
        }

        if (finalStatus != "OK") {
            val npStreams = if (allowNewPipeFallback) runCatching { newPipeStreams(videoId) }.getOrNull() else null
            if (!npStreams.isNullOrEmpty()) {
                AppLog.event("player fallback to newPipeStreams video=$videoId streams=${npStreams.size}")
                return npStreams
            }
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
            val npStreams = if (allowNewPipeFallback) runCatching { newPipeStreams(videoId) }.getOrNull() else null
            if (!npStreams.isNullOrEmpty()) {
                AppLog.event("player fallback to newPipeStreams (missing streamingData) video=$videoId streams=${npStreams.size}")
                return npStreams
            }
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
                    }.getOrElse {
                        runCatching {
                            YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, sig)
                        }.getOrNull()
                    }
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
            val npStreams = if (allowNewPipeFallback) runCatching { newPipeStreams(videoId) }.getOrNull() else null
            if (!npStreams.isNullOrEmpty()) {
                AppLog.event("player fallback to newPipeStreams (empty streams) video=$videoId streams=${npStreams.size}")
                return npStreams
            }
            error("YouTube returned only protected stream URLs")
        }
        return streams
    }

    /** Fetches a native WEB SABR session. Direct URLs are intentionally not required here. */
    fun sabrPlaybackInfo(videoId: String): SabrPlaybackInfo {
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
        val accountTracking = runCatching { authenticatedPlayerTracking(videoId, cpn, false) }
            .onFailure { AppLog.failure("SABR authenticated tracking video=$videoId", it) }
            .getOrNull()
        cachePlaybackTracking(videoId, accountTracking ?: response.optJSONObject("playbackTracking"), cpn)
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

    fun newPipeStreamInfo(videoId: String): StreamInfo {
        val url = "https://www.youtube.com/watch?v=$videoId"
        return try {
            StreamInfo.getInfo(url)
        } catch (first: Throwable) {
            if (!isLoginRequired(first)) throw first
            AppLog.event("NewPipe LOGIN_REQUIRED; refreshing integrated PoToken video=$videoId")
            NewPipePoTokenProvider.reset()
            org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
                .setWebPoTokenFallbackEnabled(true)
            try {
                StreamInfo.getInfo(url).also {
                    AppLog.event("NewPipe retry after integrated PoToken refresh succeeded video=$videoId")
                }
            } finally {
                org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
                    .setWebPoTokenFallbackEnabled(PlaybackBackendPreferences.useWebWhenNewPipeFails())
            }
        }
    }

    private fun isLoginRequired(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
        val text = "${it.javaClass.simpleName}: ${it.message}".lowercase()
        "login_required" in text || "login required" in text || "sign in to confirm" in text || "not a bot" in text
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

    fun resolveNewPipeStreamUrl(videoId: String, streamingUrl: String): String =
        solveNChallenge(videoId, streamingUrl)

    fun reportPlayback(videoId: String, fromMs: Long, toMs: Long, paused: Boolean = false) {
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
            val path = if (playback) "playback" else "watchtime"
            return "https://www.youtube.com/api/stats/$path".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("ns", "yt")
                .addQueryParameter("ver", "2")
                .addQueryParameter("docid", source.queryParameter("docid") ?: videoId)
                .apply { source.queryParameter("len")?.let { addQueryParameter("len", it) } }
                .addQueryParameter("cpn", tracking.cpn)
                .addQueryParameter("ei", source.queryParameter("ei")!!)
                .addQueryParameter("vm", source.queryParameter("vm")!!)
                .addQueryParameter("of", source.queryParameter("of")!!)
                .apply {
                    if (playback) addQueryParameter("cmt", fromSec)
                    else {
                        addQueryParameter("st", fromSec)
                        addQueryParameter("et", toSec)
                        addQueryParameter("cmt", toSec)
                    }
                }.build()
        }
        fun send(url: okhttp3.HttpUrl) {
            val origin = "https://www.youtube.com"
            val request = Request.Builder().url(url).apply { authenticatedHeaders(origin).forEach { (key, value) -> header(key, value) } }.build()
            client.newCall(request).execute().use { response ->
                AppLog.event("playback tracking video=$videoId music=${tracking.music} kind=${if (url.encodedPath.contains("watchtime")) "watchtime" else "playback"} state=${if (paused) "paused" else "playing"} HTTP ${response.code}")
            }
        }
        if (first) send(statsUrl(true))
        send(statsUrl(false))
    }

    fun preparePlaybackTracking(videoId: String) {
        if (!signedIn()) return
        if (synchronized(playbackTracking) { playbackTracking.containsKey(videoId) }) return
        val cpn = UUID.randomUUID().toString().replace("-", "").take(16)
        val tracking = authenticatedPlayerTracking(videoId, cpn, false) ?: return
        if (cachePlaybackTracking(videoId, tracking, cpn)) {
            AppLog.event("tracking prepared for standard YouTube history video=$videoId")
        }
    }

    private fun cachePlaybackTracking(videoId: String, tracking: JSONObject?, cpn: String): Boolean {
        val playback = tracking?.optJSONObject("videostatsPlaybackUrl")?.optString("baseUrl").orEmpty()
        val watchtime = tracking?.optJSONObject("videostatsWatchtimeUrl")?.optString("baseUrl").orEmpty()
        if (playback.isBlank() || watchtime.isBlank()) {
            AppLog.event("playback tracking unavailable video=$videoId")
            return false
        }
        synchronized(playbackTracking) {
            playbackTracking[videoId] = PlaybackTracking(playback, watchtime, cpn, false)
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
        val token = WebPoTokenProvider.get(videoId, standaloneWebVisitorData(remix), origin, WEB_PLAYER_UA)
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

    /** Gets the visitor id used by the standalone WEB player, without NewPipe helpers. */
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
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        val response = post("$origin/youtubei/v1/player?key=${bootstrap.key}&prettyPrint=false",
            JSONObject().put("context", context("WEB", bootstrap.version))
                .put("videoId", videoId).put("cpn", cpn)
                .put("contentCheckOk", true).put("racyCheckOk", true), origin)
        val tracking = response.optJSONObject("playbackTracking")
        AppLog.event("tracking_fetch WEB_PLAYER video=$videoId status=${response.optJSONObject("playabilityStatus")?.optString("status")} tracking_found=${tracking != null}")
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
        val subscription = subscriptionState(response, owner, channelId)
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
        if (containsString(scope, "/subscription/unsubscribe")) return true to "owner_endpoint"
        if (containsString(scope, "/subscription/subscribe")) return false to "owner_endpoint"
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

    private fun newPipeStreams(videoId: String): List<PlayableStream> {
        val info = newPipeStreamInfo(videoId)
        
        synchronized(videoDetailsCache) {
            if (!videoDetailsCache.containsKey(videoId)) {
                videoDetailsCache[videoId] = VideoDetails(
                    title = info.name.orEmpty(),
                    author = info.uploaderName.orEmpty(),
                    authorUrl = info.uploaderUrl.orEmpty(),
                    viewCount = info.viewCount,
                    durationSeconds = info.duration
                )
            }
        }

        val result = mutableListOf<PlayableStream>()
        info.videoStreams.filter { it.isUrl }.forEach { stream ->
            result += PlayableStream(stream.content, stream.codec.orEmpty(), stream.height,
                stream.fps, stream.bitrate, false, stream.isVideoOnly)
        }
        info.videoOnlyStreams.filter { it.isUrl }.forEach { stream ->
            result += PlayableStream(stream.content, stream.codec.orEmpty(), stream.height,
                stream.fps, stream.bitrate, false, true)
        }
        info.audioStreams.filter { it.isUrl }.forEach { stream ->
            val trackName = stream.audioTrackName?.takeIf { it.isNotBlank() }
                ?: stream.audioLocale?.displayName?.takeIf { it.isNotBlank() }.orEmpty()
            val original = stream.audioTrackType?.name?.contains("ORIGINAL", true) == true || trackName.contains("original", true)
            result += PlayableStream(stream.content, stream.codec.orEmpty(), 0, 0,
                stream.bitrate, true, false, original, trackName, stream.audioTrackId.orEmpty())
        }
        return result.distinctBy { it.url }
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
        // Owner renderers occasionally expose only a 48 px avatar even though the same
        // image endpoint supports larger sizes. 176 px stays small on the wire but is
        // sharp for the 42 dp player avatar on high-density displays.
        return bestUrl?.replace(Regex("=s\\d+(?=-|$)"), "=s176")
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
            suppliedEndpoint to "updateReplyParams",
            "update_comment" to "updateReplyParams",
            "update_comment_reply" to "updateCommentParams",
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
            if (renderer != null && itemLabel.equals(label, ignoreCase = true)) {
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

    private data class Bootstrap(val key: String, val version: String, val origin: String)

    private fun beginFeedLoad(name: String): Long {
        val generation = feedGeneration.incrementAndGet()
        activeFeedCall.getAndSet(null)?.cancel()
        AppLog.event("feed=$name generation=$generation")
        return generation
    }

    private fun bootstrap(origin: String, clientName: String): Bootstrap {
        val baseOrigin = if (origin.contains("music.youtube.com")) "https://music.youtube.com" else "https://www.youtube.com"
        synchronized(bootstrapCache) {
            bootstrapCache["$baseOrigin:$clientName"]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }?.second?.let { return it }
        }
        val html = get(baseOrigin)
        val key = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)").find(html)?.groupValues?.get(1)
            ?: error("Unable to discover YouTube's internal API key")
        val version = Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)").find(html)?.groupValues?.get(1)
            ?: if (clientName == "WEB_REMIX") "1.20260901.01.00" else "2.20260901.00.00"
        clientVersions[baseOrigin] = version
        Regex("\"VISITOR_DATA\":\"([^\"]+)").find(html)?.groupValues?.get(1)?.let { visitorData[baseOrigin] = it }
        return Bootstrap(key, version, baseOrigin).also { synchronized(bootstrapCache) { bootstrapCache["$baseOrigin:$clientName"] = System.currentTimeMillis() to it } }
    }

    private fun context(name: String, version: String): JSONObject {
        val origin = if (name == "WEB_REMIX") "https://music.youtube.com" else "https://www.youtube.com"
        return JSONObject().put("client", JSONObject().put("clientName", name).put("clientVersion", version)
            .put("hl", "en").put("gl", "US").apply { visitorData[origin]?.let { put("visitorData", it) } })
    }

    private fun get(url: String, userAgent: String = UA): String {
        val origin = if (url.contains("music.youtube.com")) "https://music.youtube.com" else "https://www.youtube.com"
        val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept-Language", "en-US,en;q=0.9").apply {
            cookies(origin).takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
        }.build()
        return client.newCall(request).execute().use { if (!it.isSuccessful) error("YouTube returned HTTP ${it.code}"); it.body?.string().orEmpty() }
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
            val playlistRenderer = rendererType.contains("playlist", true) || (rendererType == "lockupViewModel" && renderer.optString("contentId").startsWith("PL"))
            val channelRenderer = rendererType.contains("channel", true)
            val videoId = if (playlistRenderer || channelRenderer) "" else renderer.optString("videoId").ifBlank { findString(renderer, "videoId").orEmpty() }
            val channelId = if (channelRenderer) renderer.optString("channelId")
                .ifBlank { findString(renderer, "browseId")?.takeIf { it.startsWith("UC") }.orEmpty() } else ""
            val authorChannelId = if (!channelRenderer) findString(renderer, "browseId")
                ?.takeIf { it.startsWith("UC") }.orEmpty() else ""
            val playlistId = if (playlistRenderer) renderer.optString("playlistId")
                .ifBlank { findString(renderer, "playlistId").orEmpty() }
                .ifBlank { renderer.optString("contentId").takeUnless { it.length == 11 }.orEmpty() } else ""
            val isChannel = channelRenderer && channelId.startsWith("UC")
            val isPlaylist = playlistRenderer && playlistId.isNotBlank()
            val id = when { isChannel -> channelId; isPlaylist -> playlistId.removePrefix("VL"); else -> videoId }
            if (!isChannel && !isPlaylist && id.length != 11) return@walkFeedRenderers
            val columns = flexColumnTexts(renderer)
            val title = text(renderer.opt("title")) ?: text(renderer.opt("headline")) ?: columns.firstOrNull()
                ?: deepText(renderer, "title") ?: deepText(renderer, "headline") ?: return@walkFeedRenderers
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
            val item = FeedItem(id, title, subtitle, thumbnail, thumbWidth, thumbHeight, channelThumbnail, url, isPlaylist, isChannel, resumePositionMs = resumePositionMs)
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
                        "videoWithContextRenderer", "videoCardRenderer", "reelItemRenderer", "musicTwoRowItemRenderer", "musicResponsiveListItemRenderer", "lockupViewModel"
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
        return (if (leadingType) parts.getOrNull(1) else parts.firstOrNull())
            ?.replace(Regex("\\s+-\\s+Topic$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != title && !it.matches(Regex("\\d{1,2}:\\d{2}(:\\d{2})?")) }
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
            ?: value.keys().asSequence().mapNotNull { findContinuationToken(value.opt(it)) }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findContinuationToken(value.opt(it)) }.firstOrNull()
        else -> null
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
