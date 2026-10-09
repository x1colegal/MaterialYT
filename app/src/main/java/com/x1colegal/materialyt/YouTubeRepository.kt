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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class FeedItem(val id: String, val title: String, val subtitle: String, val thumbnail: String, val thumbnailWidth: Int, val thumbnailHeight: Int, val channelThumbnail: String, val url: String, val playlist: Boolean = false, val channel: Boolean = false, val channelUrl: String = "")
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
data class VideoDetails(val title: String, val author: String, val authorUrl: String = "", val authorAvatar: String = "", val viewCount: Long = 0L, val durationSeconds: Long = 0L, val likeText: String = "", val publishedDate: String = "", val description: String = "")
data class LyricLine(val text: String, val startMs: Long, val endMs: Long)
data class CommunityPost(val id: String, val text: String, val published: String, val likes: String, val comments: String, val image: String = "")
data class CommunityComment(val id: String, val author: String, val text: String, val published: String, val likes: String)

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
    private val playerJavaScriptCache = mutableMapOf<String, String>()
    private val nChallengeCache = mutableMapOf<String, String>()
    private val videoDetailsCache = mutableMapOf<String, VideoDetails>()
    fun videoDetails(videoId: String): VideoDetails? = synchronized(videoDetailsCache) { videoDetailsCache[videoId] }
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
        return try {
            val bootstrap = bootstrap("https://www.youtube.com", "WEB")
            val data = post("https://www.youtube.com/youtubei/v1/browse?key=${bootstrap.key}",
                JSONObject().put("context", context("WEB", bootstrap.version)).put("browseId", "FEwhat_to_watch"), bootstrap.origin)
            homeToken = findContinuationToken(data)
            val uniqueItems = parseItems(data).distinctBy { it.id }
            synchronized(homeSeenIds) { homeSeenIds.clear(); homeSeenIds.addAll(uniqueItems.map { it.id }) }
            AppLog.event("feed=home success items=${uniqueItems.size} hasToken=${homeToken != null}")
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
        return try {
            val browseId = when (name) { "home" -> "FEwhat_to_watch"; "history" -> "FEhistory"; "playlists" -> "FEplaylist_aggregation"; else -> null }
            val bootstrap = bootstrap("https://www.youtube.com", "WEB")
            val data = if (browseId != null) post("https://www.youtube.com/youtubei/v1/browse?key=${bootstrap.key}",
                JSONObject().put("context", context("WEB", bootstrap.version)).put("browseId", browseId), bootstrap.origin)
            else initialData(get(url))
            val uniqueItems = parseItems(data).distinctBy { it.id }
            AppLog.event("feed=$name success mode=${if (browseId == null) "html" else "browse"} items=${uniqueItems.size}")
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
        val likeText = findLikeText(primary).orEmpty()
        val published = text(primary?.opt("dateText")).orEmpty().ifBlank { current.publishedDate }
        val description = secondary?.optJSONObject("attributedDescription")?.optString("content").orEmpty()
            .ifBlank { text(secondary?.opt("description")).orEmpty() }
            .ifBlank { current.description }
        return current.copy(likeText = likeText, publishedDate = published, description = description).also {
            synchronized(videoDetailsCache) { videoDetailsCache[videoId] = it }
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
        val name = owner?.let { text(it.opt("title")) }
            ?: findString(response.optJSONObject("videoDetails"), "author")
            ?: error("Channel data is unavailable")
        return ChannelInfo(name, owner?.let(::findThumbnail).orEmpty())
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

    private fun browse(origin: String, browseId: String, clientName: String): List<FeedItem> {
        val bootstrap = bootstrap(origin, clientName)
        val body = JSONObject().put("context", context(clientName, bootstrap.version)).put("browseId", browseId)
        return parseItems(post("$origin/youtubei/v1/browse?key=${bootstrap.key}", body, origin))
    }

    private data class Bootstrap(val key: String, val version: String, val origin: String)

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

    private fun post(url: String, body: JSONObject, origin: String): JSONObject {
        val request = Request.Builder().url(url).header("Content-Type", "application/json").apply {
            authenticatedHeaders(origin).forEach { (key, value) -> header(key, value) }
            header("X-Youtube-Client-Name", if (origin.contains("music.")) "67" else "1")
            clientVersions[origin]?.let { header("X-Youtube-Client-Version", it) }
            visitorData[origin]?.let { header("X-Goog-Visitor-Id", it) }
        }.post(body.toString().toRequestBody(jsonType)).build()
        return client.newCall(request).execute().use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) { AppLog.event("authenticated POST host=${request.url.host} HTTP ${it.code} body=${raw.take(400)}"); error("YouTube returned HTTP ${it.code}") }
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
            val channelId = renderer.optString("channelId").ifBlank { findString(renderer, "browseId")?.takeIf { it.startsWith("UC") }.orEmpty() }
            val playlistId = renderer.optString("playlistId").ifBlank { findString(renderer, "playlistId").orEmpty() }
                .ifBlank { renderer.optString("contentId").takeUnless { it.length == 11 }.orEmpty() }
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
            val rawSubtitle = channelSubtitle ?: musicArtist ?: text(renderer.opt("shortBylineText")) ?: text(renderer.opt("ownerText")) ?: text(renderer.opt("longBylineText")) ?: text(renderer.opt("subtitle"))
                ?: columns.drop(1).firstOrNull()
                ?: deepText(renderer, "shortBylineText") ?: deepText(renderer, "ownerText") ?: deepText(renderer, "longBylineText") ?: deepText(renderer, "subtitle")
                ?: inferChannelName(renderer, title)?.takeIf { '<' !in it && '>' !in it } ?: "Unknown channel"
            val subtitle = (if (musicRenderer) {
                val parts = rawSubtitle.split(" • ").map(String::trim).filter(String::isNotBlank)
                val leadingType = parts.firstOrNull()?.lowercase() in setOf("song", "video", "episode", "album", "playlist")
                (if (leadingType) parts.getOrNull(1) else parts.firstOrNull())
                    ?.substringBefore(": ")?.trim().orEmpty().ifBlank { rawSubtitle }
            } else rawSubtitle).replace(Regex("(\\d+),(\\d+)"), "$1.$2")
            if (containsAdMarker(renderer) || isAdString(title) || isAdString(subtitle)) return@walkFeedRenderers
            val thumbnails = renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                ?: renderer.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val providedThumbnail = thumbnails?.optJSONObject((thumbnails.length() - 1).coerceAtLeast(0))?.optString("url").orEmpty()
                .ifBlank { findThumbnail(renderer).orEmpty() }.let { if (it.startsWith("//")) "https:$it" else it }
            val isMusicItem = renderer.has("flexColumns") || renderer.has("thumbnailRenderer") || renderer.has("subtitle")
            val thumbnail = if ((isMusicItem || isPlaylist || isChannel) && providedThumbnail.isNotBlank()) providedThumbnail else "https://i.ytimg.com/vi/$id/maxresdefault.jpg"
            val selectedThumb = thumbnails?.optJSONObject((thumbnails.length() - 1).coerceAtLeast(0))
            val thumbWidth = if (isMusicItem || isChannel) selectedThumb?.optInt("width")?.takeIf { it > 0 } ?: 1 else 16
            val thumbHeight = if (isMusicItem || isChannel) selectedThumb?.optInt("height")?.takeIf { it > 0 } ?: 1 else 9
            val channelThumbnail = findChannelThumbnail(renderer).orEmpty().ifBlank { if (isChannel) thumbnail else "" }
            val url = when { isChannel -> "https://www.youtube.com/channel/$id"; isPlaylist -> "https://www.youtube.com/playlist?list=$id"; else -> "https://www.youtube.com/watch?v=$id" }
            output.putIfAbsent(id, FeedItem(id, title, subtitle, thumbnail, thumbWidth, thumbHeight, channelThumbnail, url, isPlaylist, isChannel))
        }
        return output.values.toList()
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
                if (!blocked) {
                    val supported = setOf(
                        "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer", "playlistRenderer", "gridPlaylistRenderer", "channelRenderer", "gridChannelRenderer",
                        "reelItemRenderer", "musicTwoRowItemRenderer", "musicResponsiveListItemRenderer", "lockupViewModel"
                    )
                    keys.filter { it in supported }.mapNotNull { key -> value.optJSONObject(key)?.apply { put("_materialytRendererType", key) } }
                        .filterNot { containsAdMarker(it) }
                        .forEach(consumer)
                }
                keys.forEach { walkFeedRenderers(value.opt(it), blocked, consumer) }
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

    private fun findThumbnail(value: Any?): String? = when (value) {
        is JSONObject -> value.optString("url").takeIf { it.contains("ytimg.com") || it.contains("ggpht.com") }?.let { if (it.startsWith("//")) "https:$it" else it }
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
        val assignment = Regex("(?:var\\s+ytInitialData|window\\[\\\"ytInitialData\\\"])\\s*=\\s*")
        assignment.findAll(html).forEach { match ->
            var start = match.range.last + 1
            while (start < html.length && html[start].isWhitespace()) start++
            if (start >= html.length) return@forEach
            val raw = when (html[start]) {
                '{' -> extractObject(html, start)
                '\'', '"' -> extractJsString(html, start)
                else -> null
            }
            raw?.let {
                runCatching { JSONObject(it) }.getOrNull()?.let { parsed ->
                    if (parsed.has("contents") || parsed.has("continuationContents")) return parsed
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
