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
data class PlayableStream(val url: String, val codec: String, val height: Int, val fps: Int, val bitrate: Int, val audio: Boolean, val videoOnly: Boolean, val originalAudio: Boolean = false, val audioTrackName: String = "", val audioTrackId: String = "")
data class ChannelInfo(val name: String, val thumbnail: String)
data class VideoDetails(val title: String, val author: String, val authorUrl: String = "", val authorAvatar: String = "", val viewCount: Long = 0L, val durationSeconds: Long = 0L)
data class LyricLine(val text: String, val startMs: Long, val endMs: Long)
data class CommunityPost(val id: String, val text: String, val published: String, val likes: String, val comments: String, val image: String = "")
data class CommunityComment(val id: String, val author: String, val text: String, val published: String, val likes: String)

object YouTubeRepository {
    const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val clientVersions = mutableMapOf<String, String>()
    private val visitorData = mutableMapOf<String, String>()
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
        val continuation = seed.optString("sequenceContinuation").ifBlank {
            findString(seed.optJSONObject("continuationEndpoint"), "token").orEmpty()
        }
        if (continuation.isNotBlank()) result += shortsSequence(continuation, bootstrap)
        return result.distinctBy { it.id }.also { AppLog.event("shorts reel items=${it.size}") }
    }

    fun shortsContinuation(): List<FeedItem> {
        return runCatching {
            val bootstrap = bootstrap("https://www.youtube.com", "WEB")
            val token = shortsToken
            val batches = if (token != null) listOf(shortsSequence(token, bootstrap)) else List(4) { shortsBatch() }
            batches.asSequence().flatten().filter { item -> synchronized(shortsSeenIds) { shortsSeenIds.add(item.id) } }.toList()
        }.onFailure { AppLog.failure("shorts continuation", it) }.getOrDefault(emptyList())
    }

    private fun shortsSequence(token: String, bootstrap: Bootstrap): List<FeedItem> {
        val origin = "https://www.youtube.com"
        val sequence = post("$origin/youtubei/v1/reel/reel_watch_sequence?key=${bootstrap.key}", JSONObject()
            .put("context", context("WEB", bootstrap.version)).put("sequenceParams", token), origin)
        var next = sequence.optString("sequenceContinuation").ifBlank { findString(sequence, "sequenceContinuation").orEmpty() }
        val result = mutableListOf<FeedItem>()
        val entries = sequence.optJSONArray("entries")
        if (entries != null) for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            if (containsAdMarker(entry)) continue
            val watch = entry.optJSONObject("command")?.optJSONObject("reelWatchEndpoint") ?: continue
            if (containsAdMarker(watch)) continue
            val videoId = watch.optString("videoId")
            val params = watch.optString("params")
            if (videoId.length != 11 || params.isBlank()) continue
            runCatching {
                post("$origin/youtubei/v1/reel/reel_item_watch?key=${bootstrap.key}", JSONObject()
                    .put("context", context("WEB", bootstrap.version)).put("disablePlayerResponse", true)
                    .put("params", params).put("playerRequest", JSONObject().put("videoId", videoId)), origin)
            }.getOrNull()?.let { details ->
                if (!containsAdMarker(details)) {
                    reelFeedItem(details, watch)?.let(result::add)
                    val candidate = details.optString("sequenceContinuation").ifBlank {
                        findString(details, "sequenceContinuation").orEmpty().ifBlank {
                            findString(details.optJSONObject("continuationEndpoint"), "token").orEmpty()
                        }
                    }
                    if (candidate.isNotBlank() && candidate != token) next = candidate
                }
            }
        }
        shortsToken = next.takeIf { it.isNotBlank() && it != token }
        return result.distinctBy { it.id }.also { AppLog.event("shorts continuation items=${it.size} hasNext=${shortsToken != null}") }
    }

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
        val pageUrl = channelUrl.trimEnd('/') + "/posts"
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
                        val content = text(renderer.opt("contentText")) ?: text(renderer.opt("text")).orEmpty()
                        val id = renderer.optString("postId").ifBlank { content.hashCode().toString() }
                        if (content.isNotBlank()) posts.putIfAbsent(id, CommunityPost(
                            id = id,
                            text = content,
                            published = text(renderer.opt("publishedTimeText")).orEmpty().ifBlank { "Date unavailable" },
                            likes = nestedText(renderer, setOf("voteCount", "likeCount", "likeCountText")).orEmpty().ifBlank { "0 likes" },
                            comments = nestedText(renderer, setOf("replyCount", "replyCountText", "commentCount", "commentCountText")).orEmpty().ifBlank { "Comments" },
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
        repeat(3) {
            val token = findContinuationToken(data) ?: return@repeat
            if (!seen.add(token)) return@repeat
            val bootstrap = bootstrap("https://www.youtube.com", "WEB")
            data = post("https://www.youtube.com/youtubei/v1/next?key=${bootstrap.key}",
                JSONObject().put("context", context("WEB", bootstrap.version)).put("continuation", token), bootstrap.origin)
            collect(data)
        }
        return comments.values.toList()
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
            val npStreams = runCatching { newPipeStreams(videoId) }.getOrNull()
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
            val npStreams = runCatching { newPipeStreams(videoId) }.getOrNull()
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
            val npStreams = runCatching { newPipeStreams(videoId) }.getOrNull()
            if (!npStreams.isNullOrEmpty()) {
                AppLog.event("player fallback to newPipeStreams (empty streams) video=$videoId streams=${npStreams.size}")
                return npStreams
            }
            error("YouTube returned only protected stream URLs")
        }
        return streams
    }

    fun newPipeStreamInfo(videoId: String): StreamInfo {
        val url = "https://www.youtube.com/watch?v=$videoId"
        return try {
            StreamInfo.getInfo(url)
        } catch (first: Throwable) {
            if (!isLoginRequired(first)) throw first
            AppLog.event("NewPipe LOGIN_REQUIRED; refreshing integrated PoToken video=$videoId")
            NewPipePoTokenProvider.reset()
            StreamInfo.getInfo(url).also {
                AppLog.event("NewPipe retry after integrated PoToken refresh succeeded video=$videoId")
            }
        }
    }

    private fun isLoginRequired(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
        val text = "${it.javaClass.simpleName}: ${it.message}".lowercase()
        "login_required" in text || "login required" in text || "sign in to confirm" in text || "not a bot" in text
    }

    private fun playerJavaScript(videoId: String): String {
        val watchHtml = get("https://www.youtube.com/watch?v=$videoId")
        val encodedPlayerUrl = Regex("\\\"(?:jsUrl|PLAYER_JS_URL)\\\":\\\"([^\\\"]+)").find(watchHtml)?.groupValues?.get(1)
            ?: error("YouTube did not expose its player JavaScript URL")
        val playerPath = JSONObject("{\"value\":\"$encodedPlayerUrl\"}").getString("value")
        val playerUrl = if (playerPath.startsWith("http")) playerPath else "https://www.youtube.com$playerPath"
        return synchronized(playerJavaScriptCache) { playerJavaScriptCache[playerUrl] }
            ?: get(playerUrl).also { synchronized(playerJavaScriptCache) { playerJavaScriptCache[playerUrl] = it } }
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
        val newPipeUrl = if (clientSpecific) null else runCatching {
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, streamingUrl)
        }.onFailure { AppLog.failure("NewPipe n parameter video=$videoId", it) }.getOrNull()
        if (!newPipeUrl.isNullOrBlank()) {
            val solved = newPipeUrl.toHttpUrlOrNull()?.queryParameter("n")
            if (!solved.isNullOrBlank() && solved != challenge) {
                synchronized(nChallengeCache) { nChallengeCache[cacheKey] = solved }
                return newPipeUrl
            }
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

    fun preparePlaybackTracking(videoId: String) {
        if (!signedIn()) return
        if (synchronized(playbackTracking) { playbackTracking.containsKey(videoId) }) return
        val cpn = UUID.randomUUID().toString().replace("-", "").take(16)
        val tracking = authenticatedPlayerTracking(videoId, cpn, false) ?: return
        val playback = tracking.optJSONObject("videostatsPlaybackUrl")?.optString("baseUrl").orEmpty()
        val watchtime = tracking.optJSONObject("videostatsWatchtimeUrl")?.optString("baseUrl").orEmpty()
        if (playback.isNotBlank() && watchtime.isNotBlank()) {
            synchronized(playbackTracking) {
                playbackTracking[videoId] = PlaybackTracking(playback, watchtime, cpn, false)
            }
            AppLog.event("tracking prepared for standard YouTube history video=$videoId")
        }
    }

    private fun visionOsPlayer(videoId: String): JSONObject {
        return mobilePlayer(videoId, "VISIONOS", "1.02", "101", "RealityDevice14,1", "visionOS", "25.6.0.23O471")
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

    private fun webPoPlayer(videoId: String): JSONObject {
        val token = NewPipePoTokenProvider.getWebClientPoToken(videoId)
            ?: error("BotGuard did not produce a PoToken")
        val origin = "https://www.youtube.com"
        val bootstrap = bootstrap(origin, "WEB")
        val cpn = UUID.randomUUID().toString().replace("-", "").take(16)
        val body = JSONObject().put("context", context("WEB", bootstrap.version).also {
            it.getJSONObject("client").put("visitorData", token.visitorData)
        }).put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true)
            .put("cpn", cpn)
            .put("serviceIntegrityDimensions", JSONObject().put("poToken", token.playerRequestPoToken))
        val request = Request.Builder().url("$origin/youtubei/v1/player?key=${bootstrap.key}&prettyPrint=false")
            .header("User-Agent", UA).header("Content-Type", "application/json")
            .header("X-Youtube-Client-Name", "1").header("X-Youtube-Client-Version", bootstrap.version)
            .header("X-Goog-Visitor-Id", token.visitorData)
            .apply { authenticatedHeaders(origin).forEach { (key, value) -> header(key, value) } }
            .post(body.toString().toRequestBody(jsonType)).build()
        return client.newCall(request).execute().use {
            val raw = it.body.string()
            check(it.isSuccessful) { "WEB PoToken player HTTP ${it.code}" }
            JSONObject(raw).put("_materialytCpn", cpn)
                .put("_materialytStreamingPot", token.streamingDataPoToken.orEmpty())
        }
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
        val html = runCatching { get("$origin/watch?v=$videoId") }.getOrNull() ?: return null
        val match = Regex("ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});").find(html)?.groupValues?.get(1) ?: return null
        val response = runCatching { JSONObject(match) }.getOrNull() ?: return null
        val tracking = response.optJSONObject("playbackTracking")
        AppLog.event("tracking_fetch WEB_HTML video=$videoId tracking_found=${tracking != null}")
        return tracking
    }


    private fun mobilePlayer(videoId: String, name: String, version: String, id: String, model: String, os: String, osVersion: String): JSONObject {
        val ua = when (name) {
            "ANDROID_VR" -> "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
            "ANDROID" -> "com.google.android.youtube/$version (Linux; U; Android $osVersion) gzip"
            else -> "com.google.ios.youtube/$version (iPhone; U; CPU iOS 18_7_2 like Mac OS X)"
        }
        val cpn = UUID.randomUUID().toString().replace("-", "").take(16)
        val clientContext = JSONObject()
            .put("clientName", name).put("clientVersion", version)
            .put("deviceModel", model).put("osName", os)
            .put("osVersion", osVersion).put("platform", "MOBILE")
            .put("clientScreen", "WATCH").put("hl", "en").put("gl", "US")
            .apply { 
                val accountOrigin = "https://www.youtube.com"
                val accountCookies = cookies(accountOrigin)
                val loggedIn = accountCookies.isNotBlank() && name != "ANDROID_VR"
                if (!loggedIn) visitorData[accountOrigin]?.let { put("visitorData", it) }
                if (name == "ANDROID_VR") {
                    put("androidSdkVersion", 32)
                    put("deviceMake", "Oculus")
                }
            }
        val body = JSONObject().put("context", JSONObject().put("client", clientContext))
            .put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true)
            .put("cpn", cpn)
        val request = Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false&id=$videoId")
            .header("User-Agent", ua).header("Content-Type", "application/json")
            .header("X-Youtube-Client-Name", id).header("X-Youtube-Client-Version", version)
            .apply {
                val accountOrigin = "https://www.youtube.com"
                val accountCookies = cookies(accountOrigin)
                val loggedIn = accountCookies.isNotBlank() && name != "ANDROID_VR"
                if (loggedIn) header("Cookie", accountCookies)
                if (loggedIn) authorization(accountOrigin)?.let { header("Authorization", it) }
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

    private fun get(url: String): String {
        val origin = if (url.contains("music.youtube.com")) "https://music.youtube.com" else "https://www.youtube.com"
        val request = Request.Builder().url(url).header("User-Agent", UA).apply {
            cookies(origin).takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
        }.build()
        return client.newCall(request).execute().use { if (!it.isSuccessful) error("YouTube returned HTTP ${it.code}"); it.body?.string().orEmpty() }
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
            val rawSubtitle = channelSubtitle ?: text(renderer.opt("shortBylineText")) ?: text(renderer.opt("ownerText")) ?: text(renderer.opt("longBylineText")) ?: text(renderer.opt("subtitle"))
                ?: columns.drop(1).firstOrNull()
                ?: deepText(renderer, "shortBylineText") ?: deepText(renderer, "ownerText") ?: deepText(renderer, "longBylineText") ?: deepText(renderer, "subtitle")
                ?: inferChannelName(renderer, title)?.takeIf { '<' !in it && '>' !in it } ?: "Unknown channel"
            val subtitle = rawSubtitle.replace(Regex("(\\d+),(\\d+)"), "$1.$2")
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
