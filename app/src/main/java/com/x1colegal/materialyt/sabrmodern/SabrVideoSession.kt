package com.x1colegal.materialyt.sabrmodern

import com.x1colegal.materialyt.AppLog
import com.x1colegal.materialyt.PlaybackLoadStatus
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Everything one DUAL-TRACK (video + audio) SABR session needs. The video counterpart of [SabrConfig].
 * The server serves the EXACT [videoFormat] itag we pin via preferredVideoFormatId (see
 * [SabrMessages.abrRequestVideo]); [nTransform] n-transforms the ciphered web-client SABR url and
 * [urlPot] is the videoId-bound pot appended as `&pot=` (both null/identity for a direct client).
 */
internal class SabrVideoConfig(
    val mediaId: String,
    val sabrUrl: String,
    val ustreamerConfig: ByteArray,
    val videoFormat: SabrMessages.Format,
    val audioFormat: SabrMessages.Format,
    val poToken: ByteArray,
    val clientInfo: SabrMessages.ClientInfo,
    val userAgent: String,
    val nTransform: (String) -> String,
    val urlPot: String? = null,
    // Display metadata surfaced in the song-details sheet (persisted as a FormatEntity by MusicService).
    val streamClientLabel: String = "SABR",
    val videoMimeType: String = "",
    val videoBitrate: Int = 0,
    /** The roster key of the client this config resolved over (the stall-fallback bookkeeping id). */
    val clientKey: String? = null,
    /** approxDurationMs from the /player video format — the byte<->time estimate a seek-restart needs. */
    val durationMs: Long = 0,
    /** The listen's cpn for watch-time CDN correlation (DIRECT stampCpn parity); null = download (no stamp). */
    val cpn: () -> String? = { null },
    val reloadPlayer: ((String) -> Pair<String, ByteArray>)? = null,
    val refreshPlayer: (() -> Pair<String, ByteArray>)? = null,
    /** Returns the same fresh streaming PoToken in its URL-safe and decoded protobuf forms. */
    val refreshPoToken: (() -> Pair<String, ByteArray>)? = null,
    val heartbeatIntervalMs: Long = 10_000L,
    val heartbeatToken: String = "",
    val heartbeatServerData: String = "",
    /** Returns the next heartbeatServerData, if YouTube rotates it. */
    val checkHeartbeat: ((String?) -> String?)? = null,
    /** Browser session headers. Restricted/owner-only broadcasts require the account cookie at GVS. */
    val requestHeaders: Map<String, String> = emptyMap(),
)

/**
 * Drives ONE dual-track SABR continuation loop on a background thread, reassembling the video-only and
 * audio streams into [videoBuffer] and [audioBuffer] respectively. A faithful port of the proven
 * `tests/sabr-video.mjs` drain: each UMP response interleaves both tracks' segments, routed to a track
 * by its MEDIA_HEADER itag; each track reassembles positionally (byte-exact regardless of order); the
 * request advances playerTimeMs to the MINIMUM buffered end across the two tracks (you can't play past
 * the least-buffered track) and echoes playback cookies + SABR context updates until BOTH tracks'
 * TAILS are covered. Seek ([startTimeMs] > 0) and demand pacing follow [SabrSession]'s proven contract
 * (`tests/sabr-video.mjs START_S`: the dual-track cold seek is honoured on both tracks, and a seeked
 * session gets NO end_segment_number — completion is judged by byte coverage).
 */
internal class SabrVideoSession(
    private val config: SabrVideoConfig,
    private val client: OkHttpClient,
    private val videoBuffer: SabrBuffer,
    private val audioBuffer: SabrBuffer,
    /** Cold-start playerTimeMs (0 = the plain from-the-top drain). */
    private val startTimeMs: Long = 0,
    /** Optional per-iteration progress hook (contiguous bytes across BOTH tracks) — the download ring. */
    private val onProgress: ((Long) -> Unit)? = null,
    /** Per-track demand-pacing windows; 0 = full-speed drain (downloads). */
    private val paceAheadVideoBytes: Long = 0,
    private val paceAheadAudioBytes: Long = 0,
    /** Real ExoPlayer position relative to the first emitted live fragment. */
    private val playbackPositionMs: (() -> Long?)? = null,
    /** Fired on an incomplete drain (the stall-fallback bookkeeping). */
    private val onIncomplete: (() -> Unit)? = null,
    /**
     * True when [SabrVideoStream] owns this session's lifecycle and retries. A restartable session NEVER
     * marks its shared buffers errored on failure — a seek-restart reuses the SAME buffers, and a
     * one-way markError from this dying session would poison them for the valid replacement. It only
     * wakes the readers so the stream re-aims; the stream owns the terminal error (MAX_SEEK_RESTARTS /
     * destroy). A standalone (download) session keeps marking so its caller sees the failure.
     */
    private val restartable: Boolean = false,
) : Runnable {

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    // POST-count ceiling — a backstop against a pathological progressing-but-never-completing loop
    // (dry below is the real stall guard); derived from duration so a long video's legitimate drain
    // is never cut off mid-tail.
    private val maxIter: Int = if (config.durationMs <= 0L) Int.MAX_VALUE
        else ((config.durationMs / 1000L) * 3L + 1000L).coerceIn(1000L, 200_000L).toInt()

    /** Terminal failure of THIS session — see [SabrSession.failStream]. Marks/wakes BOTH tracks. */
    private fun failStream(message: String, stall: Boolean) {
        AppLog.event("SABR video failStream video=${config.mediaId} message=$message stall=$stall isLive=${config.durationMs <= 0L}")
        if (stall) onIncomplete?.invoke()
        if (restartable) { videoBuffer.notifyWaiters(); audioBuffer.notifyWaiters() }
        else { videoBuffer.markError(message); audioBuffer.markError(message) }
    }

    /** Absolute first-byte offsets of this session's tracks (-1 until known) — the seek anchors. */
    @Volatile var firstVideoOffset: Long = -1L
        private set
    @Volatile var firstAudioOffset: Long = -1L
        private set

    /** Start time this session was asked to serve from. */
    val requestedStartMs: Long get() = startTimeMs

    private val protobuf = "application/x-protobuf".toMediaType()

    /** Per-track drain state (segment bookkeeping the SabrBuffer doesn't hold). */
    private inner class Track(val itag: Int, val format: SabrMessages.Format, val buffer: SabrBuffer) {
        val seen = HashSet<Int>()
        var initWritten = false
        var firstSeq = 0
        var firstMs = 0L
        var firstOff = -1L
        var lastSeq = 0
        var bufEndMs = if (config.durationMs <= 0L) 0L else startTimeMs
        var endSeg = 0
        var liveWriteOffset = 0L
        var liveSegmentsWritten = 0
        // SABR may deliberately omit alternating live sequence numbers (for example 2085, 2087,
        // 2089) even though every returned fragment contains only one second of media. Preserve
        // timing inside each fragment, but append their decode timelines without those absent
        // network-window gaps so ProgressiveMediaSource always has contiguous playable samples.
        var liveNextDecodeMs = 0L
        var liveTimescale = 0L
        fun state(): SabrMessages.TrackState? =
            if (lastSeq > 0) SabrMessages.TrackState(format, bufEndMs, lastSeq, firstMs, firstSeq) else null
        /** This session's tail — its first written byte to contentLength — fully covered? */
        fun tailDone(): Boolean = config.durationMs > 0L && firstOff >= 0 && buffer.coverageEndFrom(firstOff) >= format.contentLength
    }

    private fun prepared(rawUrl: String): String {
        var u = config.nTransform(rawUrl)
        config.urlPot?.let { u += (if (u.contains("?")) "&" else "?") + "pot=" + java.net.URLEncoder.encode(it, "UTF-8") }
        config.cpn()?.let { u += (if (u.contains("?")) "&" else "?") + "cpn=" + it }
        return u
    }

    override fun run() {
        try {
            loop()
        } catch (e: Exception) {
            // A thread interrupt IS a cancellation (runInterruptible on a cancelled download Job), not
            // a stream failure — no error mark, no stall record; the caller discards the buffers.
            val interrupted = e is java.io.InterruptedIOException || e is InterruptedException || Thread.currentThread().isInterrupted
            AppLog.failure("SABR video session crashed in loop() video=${config.mediaId}", e)
            if (!cancelled && !interrupted) failStream(e.message ?: e.javaClass.simpleName, stall = false)
        }
    }

    private fun loop() {
        val video = Track(config.videoFormat.itag, config.videoFormat, videoBuffer)
        val audio = Track(config.audioFormat.itag, config.audioFormat, audioBuffer)
        val tracks = mapOf(video.itag to video, audio.itag to audio)
        var url = prepared(config.sabrUrl)
        var ustreamerConfig = config.ustreamerConfig
        var poToken = config.poToken
        val isLive = config.durationMs <= 0L
        // A broadcast request must not monopolize the only drain thread longer than the live media
        // already buffered. During a mobile-network/DNS handover the regular playback client can sit
        // inside one call for roughly 30 seconds, letting ExoPlayer consume the entire live window.
        // Keep VOD untouched; live retries retain the already-written growable buffers.
        val requestClient = if (isLive) client.newBuilder()
            .callTimeout(LIVE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(LIVE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build() else client
        var playerTimeMs = if (isLive) LIVE_EDGE_SENTINEL_MS else startTimeMs
        var liveTimelineAnchorMs: Long? = null
        var liveTimelineAnchoredAtElapsedMs: Long? = null
        val sessionStartedAt = android.os.SystemClock.elapsedRealtime()
        var cookie: ByteArray? = null
        val ctxByType = LinkedHashMap<Long, SabrMessages.ContextUpdate>()
        val ctxToSend = LinkedHashSet<Long>()
        var iter = 0
        var requestNumber = 0
        var dry = 0
        var attestationStalls = 0
        var emptySessionRefreshes = 0
        var midStreamRefreshes = 0
        var startupEmptyResponses = 0
        var lastProtectionRefreshAt = 0L
        var lastProtectionRefreshStatus = 0L
        var protectionRefreshes = 0
        var protectionMaxRetries = 5
        var backoffTimeMs = 0L
        var consecutiveNetworkErrors = 0
        var lastHeartbeatAt = 0L
        var heartbeatServerData = config.heartbeatServerData.takeIf(String::isNotBlank)
        val pendingPoToken = java.util.concurrent.atomic.AtomicReference<Pair<String, ByteArray>?>(null)
        val poTokenRefreshRunning = java.util.concurrent.atomic.AtomicBoolean(false)

        val maxDry = if (isLive) 40 else 6
        while (!cancelled && (isLive || (iter < maxIter && dry < maxDry))) {
            pendingPoToken.getAndSet(null)?.let { refreshed ->
                poToken = refreshed.second
                // The URL token and the protobuf token are one binding. Keeping the original URL
                // `pot` while replacing only the streamer-context proof makes live requests carry
                // two different attestations, so the server remains at protection=2 forever.
                url = url.toHttpUrlOrNull()?.newBuilder()
                    ?.setQueryParameter("pot", refreshed.first)
                    ?.build()?.toString() ?: url
                attestationStalls = 0
                dry = 0
                protectionRefreshes++
                // Cookies and SABR contexts are scoped to the proof that produced them. Sending
                // those values alongside a newly minted URL/streamer token intermittently keeps
                // the server at protection=2 until it escalates to 3. Start a fresh transport
                // exchange while retaining the already assembled media buffers and their readers.
                requestNumber = 0
                cookie = null
                ctxByType.clear()
                ctxToSend.clear()
                backoffTimeMs = 0L
                AppLog.event("SABR video installed asynchronously refreshed streaming PoToken scope=url-and-streamer-context")
            }
            // Keep requesting while WebView mints the replacement proof. YouTube deliberately
            // serves a short grace window of playable media with protection=2; consuming all of
            // it keeps playback alive on slower devices. Dry protection responses are excluded
            // from the stall budget below, and protection=3 media remains withheld.
            if (backoffTimeMs > 0) {
                val delay = backoffTimeMs.coerceAtMost(30_000L)
                backoffTimeMs = 0
                AppLog.event("SABR video policy backoff=${delay}ms")
                var remaining = delay
                var shown = ""
                while (remaining > 0 && !cancelled) {
                    shown = "SABR requested backoff, waiting ${(remaining + 999L) / 1_000L}s"
                    PlaybackLoadStatus.showSabrBackoff(config.mediaId, shown)
                    val step = minOf(remaining, 1_000L)
                    Thread.sleep(step)
                    remaining -= step
                }
                PlaybackLoadStatus.clear(config.mediaId, shown)
                if (cancelled) return
            }
            iter++
            // Demand pacing: pause only while BOTH tracks are comfortably ahead of their readers.
            if (paceAheadVideoBytes > 0 || paceAheadAudioBytes > 0) awaitDualDemand()
            if (cancelled) return
            if (isLive) {
                // Live player time is on the broadcast's absolute media clock. It must keep moving
                // while ExoPlayer buffers; tying it only to currentPosition freezes SABR's playhead
                // at the depleted frontier. Sending a VOD-style zero is worse: WEB seeks hours back
                // into the DVR window and returns a discontinuous sequence. Advance from the first
                // live timestamp with the monotonic session clock instead.
                val anchor = liveTimelineAnchorMs
                val anchoredAt = liveTimelineAnchoredAtElapsedMs
                playerTimeMs = if (anchor == null || anchoredAt == null) LIVE_EDGE_SENTINEL_MS else
                    anchor + (android.os.SystemClock.elapsedRealtime() - anchoredAt).coerceAtLeast(0L)
            }
            val body = SabrMessages.abrRequestVideo(
                ustreamerConfig = ustreamerConfig,
                videoFormat = config.videoFormat,
                audioFormat = config.audioFormat,
                video = video.state(),
                audio = audio.state(),
                poToken = poToken,
                clientInfo = config.clientInfo,
                playerTimeMs = playerTimeMs,
                cookie = cookie,
                sabrContexts = ctxToSend.mapNotNull { ctxByType[it]?.encoded() },
                unsentSabrContexts = ctxByType.keys.filterNot { it in ctxToSend },
                videoInitialized = video.initWritten,
                audioInitialized = audio.initWritten,
                elapsedMs = android.os.SystemClock.elapsedRealtime() - sessionStartedAt,
                live = isLive,
            )
            val requestUrl = url.toHttpUrlOrNull()?.newBuilder()
                // WEB numbers SABR requests from zero. Some content-ad sessions only emit their
                // initialization segments for rn=0 even though ordinary videos tolerate rn=1.
                ?.setQueryParameter("rn", requestNumber.toString())
                ?.build()?.toString() ?: url
            requestNumber++
            val req = Request.Builder()
                .url(requestUrl)
                .header("User-Agent", config.userAgent)
                .header("Accept", "application/vnd.yt-ump")
                .header("Accept-Encoding", "identity")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .apply { config.requestHeaders["Cookie"]?.takeIf(String::isNotBlank)?.let { header("Cookie", it) } }
                .post(body.toRequestBody(protobuf))
                .build()
            // An HTTP failure is NOT recorded as a client stall — a 403 is usually an expired URL, not
            // this client's fault; the error mark surfaces it and the refresh path re-resolves fresh.
            val bytes = try {
                requestClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = try { resp.body?.string() } catch (_: Exception) { null }
                        val respHdrs = resp.headers.joinToString("; ") { "${it.first}=${it.second}" }
                        val reqHdrs = req.headers.joinToString("; ") { "${it.first}=${it.second}" }
                        AppLog.event("SABR video HTTP ${resp.code} msg=${resp.message} iter=$iter bodySize=${body.size} url=$requestUrl reqHdrs=[$reqHdrs] respHdrs=[$respHdrs] body=$errBody")
                        failStream("HTTP ${resp.code}", stall = false); return
                    }
                    if (isLive) readLiveSabrRound(resp.body) else resp.body?.bytes() ?: ByteArray(0)
                }
            } catch (e: java.io.IOException) {
                if (cancelled) return
                consecutiveNetworkErrors++
                AppLog.event("SABR video HTTP transient network error video=${config.mediaId} iter=$iter consecutive=$consecutiveNetworkErrors error=${e.message} — retrying")
                if (isLive && consecutiveNetworkErrors >= LIVE_NETWORK_ERRORS_BEFORE_PLAYER_REFRESH) {
                    val refreshed = config.refreshPlayer?.let { refresh ->
                        runCatching { refresh() }
                            .onFailure { AppLog.failure("SABR live endpoint refresh after network errors", it) }
                            .getOrNull()
                    }
                    if (refreshed != null) {
                        url = refreshed.first
                        ustreamerConfig = refreshed.second
                        refreshed.first.toHttpUrlOrNull()?.queryParameter("pot")?.let { pot ->
                            runCatching {
                                android.util.Base64.decode(
                                    pot,
                                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
                                )
                            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { poToken = it }
                        }
                        requestNumber = 0
                        cookie = null
                        ctxByType.clear()
                        ctxToSend.clear()
                        dry = 0
                        consecutiveNetworkErrors = 0
                        backoffTimeMs = 0L
                        AppLog.event("SABR live endpoint refreshed after transient network failure video=${config.mediaId}")
                        continue
                    }
                }
                dry++
                Thread.sleep(1500L)
                continue
            }
            if (cancelled) return
            consecutiveNetworkErrors = 0

            // Broadcast SABR only grants a short initial media window unless the player keeps its
            // live session alive through /player/heartbeat. Do this after a successful media POST,
            // just like googlevideo's SabrStream, and retain the server data returned for the next
            // check. A heartbeat failure is deliberately non-fatal; the SABR drain keeps running.
            val heartbeat = config.checkHeartbeat
            if (isLive && heartbeat != null) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastHeartbeatAt >= config.heartbeatIntervalMs.coerceAtLeast(1_000L)) {
                    lastHeartbeatAt = now
                    runCatching { heartbeat.invoke(heartbeatServerData) }
                        .onSuccess { next -> if (!next.isNullOrBlank()) heartbeatServerData = next }
                        .onFailure { AppLog.failure("SABR live heartbeat video=${config.mediaId}", it) }
                }
            }

            val parts = SabrUmp.parse(bytes)
            val headers = LinkedHashMap<Int, SabrMessages.MediaHeader>()
            var redirect: String? = null
            val sabrErrors = ArrayList<String>()
            var gotContext = false
            var protStatus = 0L
            var reloadToken: String? = null
            var snackbarPayload: String? = null
            for (p in parts) when (p.type) {
                SabrUmp.MEDIA_HEADER -> { val h = SabrMessages.parseMediaHeader(p.payload); headers[h.headerId] = h }
                SabrUmp.NEXT_REQUEST_POLICY -> {
                    val policy = SabrMessages.parseNextRequestPolicy(p.payload)
                    policy.playbackCookie?.let { cookie = it }
                    backoffTimeMs = policy.backoffTimeMs
                    if (iter == 1) AppLog.event(
                        "SABR video policy audio=${policy.targetAudioReadaheadMs} video=${policy.targetVideoReadaheadMs} " +
                            "max=${policy.maxTimeSinceLastRequestMs} backoff=${policy.backoffTimeMs}"
                    )
                }
                SabrUmp.SABR_CONTEXT_UPDATE -> {
                    val update = SabrMessages.parseContextUpdate(p.payload)
                    val old = ctxByType[update.type]
                    if (update.type != 0L && update.value.isNotEmpty() && update.writePolicy != 0L
                        && !(update.writePolicy == 2L && old != null)) {
                        gotContext = gotContext || old == null || !old.value.contentEquals(update.value)
                        ctxByType[update.type] = update
                        if (update.sendByDefault) ctxToSend += update.type
                    }
                    if (iter == 1) AppLog.event(
                        "SABR context type=${update.type} scope=${update.scope} sendPresent=${update.hasSendByDefault} " +
                            "send=${update.sendByDefault} write=${update.writePolicy} value=${update.value.size}"
                    )
                }
                SabrUmp.SABR_CONTEXT_SENDING_POLICY -> {
                    val policy = SabrMessages.parseContextSendingPolicy(p.payload)
                    ctxToSend += policy.start
                    ctxToSend -= policy.stop.toSet()
                    policy.discard.forEach { ctxToSend -= it; ctxByType.remove(it) }
                }
                SabrUmp.SABR_REDIRECT -> redirect = SabrMessages.parseRedirectUrl(p.payload)
                SabrUmp.SABR_ERROR -> sabrErrors += SabrUmp.errorDescription(p.payload)
                SabrUmp.RELOAD_PLAYER_RESPONSE -> reloadToken = SabrUmp.reloadToken(p.payload)
                SabrUmp.SNACKBAR_MESSAGE -> snackbarPayload = p.payload.joinToString("") { "%02x".format(it) }
                SabrUmp.STREAM_PROTECTION_STATUS -> {
                    val status = SabrProto.read(p.payload)
                    protStatus = status.longAt(1)
                    status.longAt(2).takeIf { it > 0 }?.let { protectionMaxRetries = it.toInt() }
                    if (protStatus != 1L || iter <= 3) AppLog.event("SABR video protection=$protStatus iter=$iter")
                    Timber.tag(TAG).d("STREAM_PROTECTION_STATUS=$protStatus (1=OK,2=pending,3=attestation-required)")
                }
            }
            if (sabrErrors.isNotEmpty()) {
                AppLog.event("SABR video error frame iter=$iter parts=${parts.size} headers=${headers.size} ${sabrErrors.joinToString()}")
                Timber.tag(TAG).w("SABR_ERROR at iter=$iter (video ${video.lastSeq}, audio ${audio.lastSeq}) — server rejected the request")
                failStream("SABR_ERROR", stall = true); return
            }
            if (!reloadToken.isNullOrBlank() && config.reloadPlayer != null) {
                AppLog.event("SABR video reloading player iter=$iter token=${reloadToken.length}")
                val refreshed = config.reloadPlayer.invoke(reloadToken)
                url = refreshed.first
                ustreamerConfig = refreshed.second
                url.toHttpUrlOrNull()?.queryParameter("pot")?.let { pot ->
                    runCatching { android.util.Base64.decode(pot, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING) }
                        .getOrNull()?.takeIf { it.isNotEmpty() }?.let { poToken = it }
                }
                requestNumber = 0
                startupEmptyResponses = 0
                dry = 0
                continue
            }
            // A context-only response is a request to echo the new context on the next SABR POST.
            // Do not throw that state away by refreshing the player before it has been sent once.
            if (headers.isEmpty() && snackbarPayload != null && !gotContext && video.lastSeq == 0 && audio.lastSeq == 0
                && emptySessionRefreshes < 2 && config.refreshPlayer != null) {
                emptySessionRefreshes++
                AppLog.event("SABR video empty session; refreshing player attempt=$emptySessionRefreshes snackbar=$snackbarPayload")
                val refreshed = config.refreshPlayer.invoke()
                url = refreshed.first
                ustreamerConfig = refreshed.second
                url.toHttpUrlOrNull()?.queryParameter("pot")?.let { pot ->
                    runCatching { android.util.Base64.decode(pot, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING) }
                        .getOrNull()?.takeIf { it.isNotEmpty() }?.let { poToken = it }
                }
                requestNumber = 0
                cookie = null
                ctxByType.clear()
                ctxToSend.clear()
                startupEmptyResponses = 0
                backoffTimeMs = 0
                dry = 0
                continue
            }
            var refreshedProtectionDuringStartup = false
            val startupResponseWithoutMedia = headers.isEmpty() &&
                !video.initWritten && !audio.initWritten && video.lastSeq == 0 && audio.lastSeq == 0
            if (startupResponseWithoutMedia && protStatus >= SabrProtection.ATTESTATION_PENDING &&
                protStatus > lastProtectionRefreshStatus) {
                config.refreshPoToken?.let { refresh ->
                    lastProtectionRefreshAt = System.currentTimeMillis()
                    lastProtectionRefreshStatus = protStatus
                    runCatching { refresh() }
                        .onSuccess { refreshed ->
                            poToken = refreshed.second
                            url = url.toHttpUrlOrNull()?.newBuilder()
                                ?.setQueryParameter("pot", refreshed.first)
                                ?.build()?.toString() ?: url
                            attestationStalls = 0
                            refreshedProtectionDuringStartup = true
                            AppLog.event("SABR video installed fresh streaming PoToken during startup protection=$protStatus scope=url-and-streamer-context")
                        }
                        .onFailure { AppLog.failure("SABR video startup streaming PoToken refresh", it) }
                }
            }
            if (startupResponseWithoutMedia && !refreshedProtectionDuringStartup) {
                startupEmptyResponses++
                AppLog.event(
                    "SABR video startup empty response=$startupEmptyResponses refreshes=$emptySessionRefreshes " +
                        "context=$gotContext snackbar=${snackbarPayload != null} backoff=$backoffTimeMs"
                )
                if (startupEmptyResponses >= STARTUP_EMPTY_LIMIT) {
                    if (emptySessionRefreshes < MAX_STARTUP_REFRESHES && config.refreshPlayer != null) {
                        emptySessionRefreshes++
                        AppLog.event("SABR video startup stalled; refreshing player attempt=$emptySessionRefreshes")
                        val refreshed = config.refreshPlayer.invoke()
                        url = refreshed.first
                        ustreamerConfig = refreshed.second
                        url.toHttpUrlOrNull()?.queryParameter("pot")?.let { pot ->
                            runCatching { android.util.Base64.decode(pot, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING) }
                                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { poToken = it }
                        }
                        requestNumber = 0
                        cookie = null
                        ctxByType.clear()
                        ctxToSend.clear()
                        startupEmptyResponses = 0
                        backoffTimeMs = 0
                        dry = 0
                        continue
                    }
                    AppLog.event("SABR video startup stalled after $emptySessionRefreshes refreshes")
                    failStream("startup context produced no media", stall = true)
                    return
                }
            } else if (headers.isNotEmpty()) {
                startupEmptyResponses = 0
            }
            if (iter == 1) AppLog.event("SABR video first response bytes=${bytes.size} parts=${parts.size} headers=${headers.size} redirect=${redirect != null} protection=$protStatus snackbar=${snackbarPayload ?: "none"} types=${parts.joinToString { "${it.type}:${it.payload.size}" }}")

            // Which headers are NEW per track (init once, each sequence once). Resends are skipped.
            // ATTESTATION_PENDING (2) is not a rejection: WEB/iOS commonly return it together with
            // playable media while a fresh proof is being obtained. Dropping those parts made live
            // startup discard a complete first response and enter a self-created backoff loop.
            // Only ATTESTATION_REQUIRED (3) says that this response's media must not be committed.
            val mediaAllowed = protStatus < SabrProtection.ATTESTATION_REQUIRED
            if (!mediaAllowed && headers.isNotEmpty()) {
                AppLog.event(
                    "SABR video withholding media while protection=$protStatus headers=${headers.size} " +
                        "videoSeq=${video.lastSeq} audioSeq=${audio.lastSeq}",
                )
            }
            val newIds = if (!mediaAllowed) hashSetOf() else headers.values.filter { h ->
                val t = tracks[h.itag] ?: return@filter false
                if (h.isInit) !t.initWritten
                else !t.seen.contains(h.seq) && (t.lastSeq == 0 || h.seq >= t.lastSeq)
            }.mapTo(hashSetOf()) { it.headerId }

            // VOD media uses absolute byte ranges. A live response is different: each segment is a
            // self-initializing file whose byte range starts at zero, so positional writes would
            // overwrite the first segment forever (the player freezes on frame one). Assemble each
            // live segment, retain the first init, strip duplicate init boxes thereafter, and append.
            val writeCursor = HashMap<Int, Long>()
            val livePayloads = HashMap<Int, java.io.ByteArrayOutputStream>()
            val landedIds = HashSet<Int>()
            for (p in parts) {
                if (p.type != SabrUmp.MEDIA) continue
                val (id, prefix) = SabrMessages.mediaHeaderId(p.payload)
                if (id !in newIds) continue
                val header = headers[id] ?: continue
                val t = tracks[header.itag] ?: continue
                val len = p.payload.size - prefix
                if (isLive) {
                    livePayloads.getOrPut(id) { java.io.ByteArrayOutputStream() }
                        .write(p.payload, prefix, len)
                } else {
                    val cursor = writeCursor.getOrPut(id) { header.startRange }
                    t.buffer.writeAt(cursor, p.payload, prefix, len)
                    writeCursor[id] = cursor + len
                    if (len > 0) landedIds += id
                }
            }
            val liveOffsets = HashMap<Int, Long>()
            val liveDurations = HashMap<Int, Long>()
            if (isLive) {
                for (header in headers.values) {
                    if (header.headerId !in newIds) continue
                    val track = tracks[header.itag] ?: continue
                    val received = livePayloads[header.headerId]?.toByteArray() ?: continue
                    val raw = if (track.format.codec.startsWith("avc", true) ||
                        track.format.codec.startsWith("hvc", true) ||
                        track.format.codec.startsWith("hev", true) ||
                        track.format.codec.startsWith("mp4a", true)
                    ) validLiveMp4Prefix(received) else received
                    if (raw == null) {
                        AppLog.event(
                            "SABR live rejected malformed MP4 itag=${header.itag} seq=${header.seq} " +
                                "received=${received.size} boxes=${liveContainerSummary(received)}",
                        )
                        continue
                    }
                    liveDurations[header.headerId] = liveTargetDurationMs(raw)
                        .takeIf { it > 0L } ?: track.format.targetDurationMs
                    val normalized = if (track.format.codec.startsWith("avc", true) ||
                        track.format.codec.startsWith("hvc", true) ||
                        track.format.codec.startsWith("hev", true) ||
                        track.format.codec.startsWith("mp4a", true)
                    ) normalizeLiveMp4Timeline(track, raw, track.liveNextDecodeMs) else raw
                    val segment = if (track.liveSegmentsWritten == 0) normalized
                        else stripDuplicateLiveInit(track.format.codec, normalized)
                    if (track.liveSegmentsWritten < 8) AppLog.event(
                        "SABR live segment itag=${header.itag} seq=${header.seq} start=${header.startRange} " +
                            "declared=${header.contentLength} raw=${raw.size} appended=${segment.size} " +
                            "time=${header.startMs}+${header.durMs} effectiveDuration=${liveDurations[header.headerId]} " +
                            "boxes=${liveContainerSummary(raw)}"
                    )
                    val offset = track.liveWriteOffset
                    liveOffsets[header.headerId] = offset
                    track.buffer.writeAt(offset, segment, 0, segment.size)
                    track.liveWriteOffset += segment.size
                    track.liveSegmentsWritten++
                    track.liveNextDecodeMs += liveDurations[header.headerId].orEmptyDuration()
                    if (segment.isNotEmpty()) landedIds += header.headerId
                }
            }
            var newSeg = false
            for (h in headers.values) {
                // A MEDIA_HEADER without MEDIA bytes is not progress. Marking it seen used to hide
                // live protection stalls forever: YouTube returned protection=2 plus empty headers,
                // while the client kept claiming that each request delivered a segment.
                if (h.headerId !in newIds || h.headerId !in landedIds) continue
                val t = tracks[h.itag] ?: continue
                if (h.isInit) { t.initWritten = true } else {
                    t.seen.add(h.seq)
                    if (isLive) {
                        val previousAnchor = liveTimelineAnchorMs
                        if (previousAnchor == null) {
                            liveTimelineAnchorMs = h.startMs
                            liveTimelineAnchoredAtElapsedMs = android.os.SystemClock.elapsedRealtime()
                            AppLog.event(
                                "SABR live timeline anchored video=${config.mediaId} mediaTimeMs=${liveTimelineAnchorMs}",
                            )
                        }
                    }
                    if (t.firstSeq == 0 || h.seq < t.firstSeq) { t.firstSeq = h.seq; t.firstMs = h.startMs }
                    val landedOffset = if (isLive) liveOffsets[h.headerId] ?: t.liveWriteOffset else h.startRange
                    if (t.firstOff < 0 || landedOffset < t.firstOff) {
                        val firstLanding = t.firstOff < 0
                        t.firstOff = landedOffset
                        if (t === video) firstVideoOffset = t.firstOff else firstAudioOffset = t.firstOff
                        if (firstLanding && t.firstOff > 0) t.buffer.raiseDemandTo(t.firstOff)
                    }
                    if (h.seq > t.lastSeq) t.lastSeq = h.seq
                    val durationMs = h.durMs.takeIf { it > 0L }
                        ?: liveDurations[h.headerId]?.takeIf { it > 0L }
                        ?: t.format.targetDurationMs.takeIf { it > 0L }
                        ?: 1_000L
                    val end = h.startMs + durationMs
                    if (end > t.bufEndMs) t.bufEndMs = end
                    newSeg = true
                }
            }
            playerTimeMs = if (isLive) {
                val anchor = liveTimelineAnchorMs
                val anchoredAt = liveTimelineAnchoredAtElapsedMs
                if (anchor == null || anchoredAt == null) LIVE_EDGE_SENTINEL_MS else
                    anchor + (android.os.SystemClock.elapsedRealtime() - anchoredAt).coerceAtLeast(0L)
            } else {
                maxOf(
                    startTimeMs,
                    minOf(video.bufEndMs.orStartIfEmpty(video), audio.bufEndMs.orStartIfEmpty(audio)),
                )
            }
            onProgress?.invoke(videoBuffer.available() + audioBuffer.available())
            if (iter <= 3) AppLog.event(
                "SABR video progress iter=$iter headers=${headers.size} new=$newSeg " +
                    "video=${videoBuffer.available()} first=${video.firstOff} init=${video.initWritten} " +
                    "audio=${audioBuffer.available()} first=${audio.firstOff} init=${audio.initWritten}"
            )
            if (isLive && (iter <= 12 || (!newSeg && iter % 10 == 0))) AppLog.event(
                "SABR live clock iter=$iter playerTimeMs=$playerTimeMs " +
                    "reportedMs=${playbackPositionMs?.invoke()} anchorMs=$liveTimelineAnchorMs " +
                    "videoEndMs=${video.bufEndMs} audioEndMs=${audio.bufEndMs} " +
                    "headers=${headers.size} new=$newSeg dry=$dry bytes=${bytes.size}",
            )

            if (redirect != null && !newSeg) { url = prepared(redirect); continue }
            dry = if (newSeg || gotContext) 0
                else if (poTokenRefreshRunning.get()) dry
                else dry + 1
            if (poTokenRefreshRunning.get() && !newSeg) Thread.sleep(100L)
            if (isLive && !newSeg) {
                val waitMs = when {
                    backoffTimeMs > 0L -> backoffTimeMs.coerceIn(500L, 3_000L)
                    dry in 1..2 -> 1_000L
                    else -> 1_500L
                }
                Thread.sleep(waitMs)
            }
            // Attestation cap (see SabrSession): a client that can't satisfy stream protection is served a
            // free window then only STREAM_PROTECTION_STATUS>=2 — bail fast so the fallback moves on.
            attestationStalls = if (poTokenRefreshRunning.get()) 0 else
                SabrProtection.nextStalls(protStatus, madeProgress = newSeg, prev = attestationStalls)
            // Status 2 means YouTube is already waiting for a new proof. It can arrive together
            // with a final batch of playable media, so waiting for an empty response loses the
            // live edge once that small batch is consumed. Re-arm after status 1 so a later
            // 1 -> 2 transition mints again without repeatedly minting for every status-2 reply.
            if (protStatus < SabrProtection.ATTESTATION_PENDING) {
                lastProtectionRefreshStatus = 0L
                protectionRefreshes = 0
            }
            val protectionEscalated = protStatus >= SabrProtection.ATTESTATION_PENDING &&
                protStatus > lastProtectionRefreshStatus
            if (protectionEscalated && protStatus == SabrProtection.ATTESTATION_PENDING &&
                protectionRefreshes < protectionMaxRetries) {
                config.refreshPoToken?.let { refresh ->
                    lastProtectionRefreshAt = System.currentTimeMillis()
                    lastProtectionRefreshStatus = protStatus
                    if (poTokenRefreshRunning.compareAndSet(false, true)) {
                        Thread({
                            runCatching { refresh() }
                                .onSuccess(pendingPoToken::set)
                                .onFailure { AppLog.failure("SABR video asynchronous streaming PoToken refresh", it) }
                            poTokenRefreshRunning.set(false)
                        }, "materialyt-live-pot-${config.mediaId}").apply { isDaemon = true; start() }
                        AppLog.event("SABR video refreshing streaming PoToken asynchronously protection=$protStatus")
                    }
                }
            }
            val protectionRefreshDue = !poTokenRefreshRunning.get() && (
                protStatus >= SabrProtection.ATTESTATION_REQUIRED && protectionEscalated ||
                    attestationStalls >= SabrProtection.REFRESH_AFTER_STALLS &&
                    System.currentTimeMillis() - lastProtectionRefreshAt >= SabrProtection.REFRESH_COOLDOWN_MS
                )
            var protectionTokenRefreshed = false
            if (protectionRefreshDue && protectionRefreshes < protectionMaxRetries) {
                config.refreshPoToken?.let { refresh ->
                    lastProtectionRefreshAt = System.currentTimeMillis()
                    lastProtectionRefreshStatus = protStatus
                    runCatching { refresh() }
                        .onSuccess { refreshed ->
                            poToken = refreshed.second
                            url = url.toHttpUrlOrNull()?.newBuilder()
                                ?.setQueryParameter("pot", refreshed.first)
                                ?.build()?.toString() ?: url
                            attestationStalls = 0
                            dry = 0
                            protectionRefreshes++
                            requestNumber = 0
                            cookie = null
                            ctxByType.clear()
                            ctxToSend.clear()
                            backoffTimeMs = 0L
                            protectionTokenRefreshed = true
                            AppLog.event("SABR video installed fresh streaming PoToken protection=$protStatus scope=url-and-streamer-context")
                        }
                        .onFailure { AppLog.failure("SABR video streaming PoToken refresh", it) }
                }
            }
            // This response was produced for the previous token. Keep the active endpoint,
            // buffered ranges, cookies and contexts, then retry with the new token.
            if (protectionTokenRefreshed) {
                continue
            }
            val refreshPlayer = config.refreshPlayer
            val midStreamNeedsRefresh = protStatus < SabrProtection.ATTESTATION_PENDING && dry >= 3 &&
                (video.lastSeq > 0 || audio.lastSeq > 0) &&
                refreshPlayer != null && midStreamRefreshes < MAX_MIDSTREAM_REFRESHES
            if (midStreamNeedsRefresh && refreshPlayer != null) {
                midStreamRefreshes++
                AppLog.event("SABR video refreshing player session mid-stream protStatus=$protStatus stalls=$attestationStalls dry=$dry attempt=$midStreamRefreshes")
                val refreshed = runCatching { refreshPlayer() }
                    .onFailure { AppLog.failure("SABR video mid-stream player refresh", it) }
                    .getOrNull()
                if (refreshed != null) {
                    url = refreshed.first
                    ustreamerConfig = refreshed.second
                    refreshed.first.toHttpUrlOrNull()?.queryParameter("pot")?.let { pot ->
                        runCatching { android.util.Base64.decode(pot, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING) }
                            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { poToken = it }
                    }
                    requestNumber = 0
                    cookie = null
                    ctxByType.clear()
                    ctxToSend.clear()
                    dry = 0
                    attestationStalls = 0
                    backoffTimeMs = 0
                    lastProtectionRefreshStatus = 0
                    continue
                }
            }
            if (!isLive && SabrProtection.capped(attestationStalls)) {
                Timber.tag(TAG).w("SABR video attestation cap (STREAM_PROTECTION_STATUS=$protStatus) — this client cannot satisfy attestation")
                failStream("attestation-capped (protection=$protStatus)", stall = true); return
            }
            if (!isLive && video.tailDone() && audio.tailDone()) break
        }

        when {
            cancelled -> return
            !isLive && video.tailDone() && audio.tailDone() -> {
                if (videoBuffer.completeFromZero()) videoBuffer.markComplete() else videoBuffer.notifyWaiters()
                if (audioBuffer.completeFromZero()) audioBuffer.markComplete() else audioBuffer.notifyWaiters()
            }
            isLive -> {
                AppLog.event("SABR video live loop finished iter=$iter dry=$dry cancelled=$cancelled")
                failStream("live stream ended (dry=$dry)", stall = false)
            }
            else -> {
                // A shortfall on either track marks BOTH errored, never complete: a clean EOF silently
                // truncated playback mid-item, and one whole track is useless without its sibling. The
                // buffers still serve their reassembled bytes first (SabrBuffer), so playback reaches
                // the stall point and surfaces a real error; the download path rejects the shortfall
                // before ever reading.
                Timber.tag(TAG).w("SABR video incomplete: video ${videoBuffer.available()}/${config.videoFormat.contentLength}, audio ${audioBuffer.available()}/${config.audioFormat.contentLength} (start=$startTimeMs)")
                val msg = "incomplete drain: video ${videoBuffer.available()}/${config.videoFormat.contentLength}, audio ${audioBuffer.available()}/${config.audioFormat.contentLength}"
                AppLog.event("SABR video $msg")
                failStream(msg, stall = true)
            }
        }
    }

    /**
     * Pause while BOTH tracks are ahead of their readers by more than their windows — the drain keeps
     * feeding whichever track playback is closer to exhausting, and a fully-idle player (pause, or the
     * user left) stops the spend. A short poll (not a cross-buffer wait) keeps this simple; the server
     * session survives the idle gaps (proven live with 90s pauses).
     */
    private fun awaitDualDemand() {
        while (!cancelled) {
            if (videoBuffer.failed() || audioBuffer.failed()) return
            val vGap = videoBuffer.demandGap()
            val aGap = audioBuffer.demandGap()
            if (vGap <= paceAheadVideoBytes || aGap <= paceAheadAudioBytes) return
            Thread.sleep(150)
        }
    }

    private fun stripDuplicateLiveInit(codec: String, segment: ByteArray): ByteArray =
        if (codec.startsWith("opus", true) || codec.startsWith("vp", true) || codec.startsWith("av01", true)) {
            stripWebmInit(segment)
        } else stripMp4Init(segment)

    /**
     * Live googlevideo responses are streaming UMP bodies and may deliberately stay open after the
     * requested fragments finish. Read frames as they arrive and close this HTTP round once complete
     * MEDIA_END frames have landed for both selected tracks. Waiting for ResponseBody.bytes() waits
     * for connection EOF instead and eventually starves an otherwise healthy ExoPlayer buffer.
     */
    private fun readLiveSabrRound(body: okhttp3.ResponseBody?): ByteArray {
        val source = body?.source() ?: return ByteArray(0)
        val received = java.io.ByteArrayOutputStream()
        val chunk = Buffer()
        while (!cancelled) {
            val count = source.read(chunk, 64L * 1024L)
            if (count < 0L) break
            if (count == 0L) continue
            received.write(chunk.readByteArray())
            val snapshot = received.toByteArray()
            val parsed = SabrUmp.parseComplete(snapshot)
            if (parsed.parts.isEmpty()) continue

            val headerItags = HashMap<Int, Int>()
            val ended = HashSet<Int>()
            for (part in parsed.parts) when (part.type) {
                SabrUmp.MEDIA_HEADER -> runCatching { SabrMessages.parseMediaHeader(part.payload) }
                    .getOrNull()?.let { headerItags[it.headerId] = it.itag }
                SabrUmp.MEDIA_END -> runCatching { SabrMessages.mediaHeaderId(part.payload).first }
                    .getOrNull()?.let(ended::add)
            }
            val videoEnded = ended.any { headerItags[it] == config.videoFormat.itag }
            val audioEnded = ended.any { headerItags[it] == config.audioFormat.itag }
            if (videoEnded && audioEnded) {
                AppLog.event(
                    "SABR live HTTP round complete bytes=${parsed.consumedBytes} " +
                        "videoItag=${config.videoFormat.itag} audioItag=${config.audioFormat.itag}",
                )
                return snapshot.copyOf(parsed.consumedBytes)
            }
        }
        val snapshot = received.toByteArray()
        val parsed = SabrUmp.parseComplete(snapshot)
        return snapshot.copyOf(parsed.consumedBytes)
    }

    /** Rewrite every tfdt relative to this track's first live fragment, preserving all gaps/durations. */
    private fun normalizeLiveMp4Timeline(track: Track, source: ByteArray, desiredStartMs: Long): ByteArray {
        val result = source.copyOf()
        fun uint32(offset: Int): Long =
            ((result[offset].toLong() and 0xff) shl 24) or
                ((result[offset + 1].toLong() and 0xff) shl 16) or
                ((result[offset + 2].toLong() and 0xff) shl 8) or
                (result[offset + 3].toLong() and 0xff)
        fun uint64(offset: Int): Long {
            var value = 0L
            for (index in 0 until 8) value = (value shl 8) or (result[offset + index].toLong() and 0xff)
            return value
        }
        fun boxEnd(offset: Int, limit: Int): Pair<Int, Int>? {
            if (offset + 8 > limit) return null
            val smallSize = uint32(offset)
            val headerSize = if (smallSize == 1L) 16 else 8
            val size = when (smallSize) {
                0L -> (limit - offset).toLong()
                1L -> if (offset + 16 <= limit) uint64(offset + 8) else return null
                else -> smallSize
            }
            if (size < headerSize || size > limit - offset) return null
            return (offset + size.toInt()) to headerSize
        }
        fun writeUnsigned(offset: Int, value: Long, bytes: Int) {
            for (index in 0 until bytes) {
                val shift = (bytes - index - 1) * 8
                result[offset + index] = (value ushr shift).toByte()
            }
        }
        var firstDecodeTime: Long? = null
        fun visit(start: Int, limit: Int, parent: String) {
            var offset = start
            while (offset + 8 <= limit) {
                val (end, headerSize) = boxEnd(offset, limit) ?: break
                val type = String(result, offset + 4, 4, Charsets.US_ASCII)
                if (type == "moov" || type == "trak" || type == "mdia" || type == "moof" || type == "traf") {
                    visit(offset + headerSize, end, type)
                } else if (type == "mdhd" && parent == "mdia" && offset + headerSize + 20 <= end) {
                    val version = result[offset + headerSize].toInt() and 0xff
                    val timescaleOffset = offset + headerSize + if (version == 1) 20 else 12
                    if (timescaleOffset + 4 <= end) {
                        val timescale = uint32(timescaleOffset)
                        if (timescale > 0L) track.liveTimescale = timescale
                    }
                } else if (type == "tkhd" && parent == "trak" && offset + headerSize + 24 <= end) {
                    val version = result[offset + headerSize].toInt() and 0xff
                    val durationOffset = offset + headerSize + if (version == 1) 28 else 20
                    val durationBytes = if (version == 1) 8 else 4
                    if (durationOffset + durationBytes <= end) {
                        val old = if (durationBytes == 8) uint64(durationOffset) else uint32(durationOffset)
                        // Fragmented live MP4 has no finite track duration. ExoPlayer explicitly
                        // treats tkhd duration=0 as TIME_UNSET; leaving YouTube's current-window
                        // duration here makes ProgressiveMediaSource enter STATE_ENDED at its edge.
                        writeUnsigned(durationOffset, 0L, durationBytes)
                        if (track.liveSegmentsWritten == 0) AppLog.event(
                            "SABR live init tkhd itag=${track.itag} original=$old patched=0"
                        )
                    }
                } else if (type == "tfdt" && parent == "traf" && offset + headerSize + 8 <= end) {
                    val version = result[offset + headerSize].toInt() and 0xff
                    val valueOffset = offset + headerSize + 4
                    val bytes = if (version == 1) 8 else 4
                    if (valueOffset + bytes <= end) {
                        val absolute = if (bytes == 8) uint64(valueOffset) else uint32(valueOffset)
                        val first = firstDecodeTime ?: absolute.also { firstDecodeTime = it }
                        val scale = track.liveTimescale.takeIf { it > 0L } ?: 1_000L
                        val desiredTicks = desiredStartMs * scale / 1_000L
                        writeUnsigned(valueOffset, (absolute - first).coerceAtLeast(0L) + desiredTicks, bytes)
                    }
                }
                offset = end
            }
        }
        visit(0, result.size, "root")
        return result
    }

    private fun Long?.orEmptyDuration(): Long = this?.takeIf { it > 0L } ?: 1_000L

    /**
     * Keep only complete top-level MP4 atoms. A UMP MEDIA_END can occasionally arrive after a
     * truncated/trailed MEDIA payload; appending those bytes poisons the otherwise valid growing
     * fMP4 stream and FragmentedMp4Extractor later interprets media bytes as an atom size. A usable
     * live fragment must contain both moof and mdat. Returning null leaves the sequence uncommitted,
     * allowing SABR to resend it on the next request.
     */
    private fun validLiveMp4Prefix(source: ByteArray): ByteArray? {
        fun uint32(offset: Int): Long =
            ((source[offset].toLong() and 0xff) shl 24) or
                ((source[offset + 1].toLong() and 0xff) shl 16) or
                ((source[offset + 2].toLong() and 0xff) shl 8) or
                (source[offset + 3].toLong() and 0xff)
        fun uint64(offset: Int): Long {
            var value = 0L
            repeat(8) { value = (value shl 8) or (source[offset + it].toLong() and 0xff) }
            return value
        }

        var offset = 0
        var sawMoof = false
        var sawMdat = false
        while (offset + 8 <= source.size) {
            val shortSize = uint32(offset)
            val headerSize = if (shortSize == 1L) 16 else 8
            if (offset + headerSize > source.size) break
            val size = when (shortSize) {
                0L -> (source.size - offset).toLong()
                1L -> uint64(offset + 8)
                else -> shortSize
            }
            if (size < headerSize || size > Int.MAX_VALUE || size > source.size - offset) break
            val type = String(source, offset + 4, 4, Charsets.US_ASCII)
            if (type.any { it.code !in 0x20..0x7e }) break
            if (type == "moof") sawMoof = true
            if (type == "mdat") sawMdat = true
            offset += size.toInt()
        }
        if (!sawMoof || !sawMdat || offset == 0) return null
        return if (offset == source.size) source else source.copyOf(offset)
    }

    /** YouTube broadcasts carry the real segment duration in the emsg HTTP-style metadata block. */
    private fun liveTargetDurationMs(segment: ByteArray): Long {
        val key = "Target-Duration-Us: ".toByteArray(Charsets.US_ASCII)
        outer@ for (start in 0..segment.size - key.size) {
            for (index in key.indices) if (segment[start + index] != key[index]) continue@outer
            var cursor = start + key.size
            var micros = 0L
            var digits = 0
            while (cursor < segment.size) {
                val value = segment[cursor].toInt() - '0'.code
                if (value !in 0..9) break
                micros = micros * 10L + value
                digits++
                cursor++
            }
            if (digits > 0 && micros > 0L) return (micros + 999L) / 1_000L
        }
        return 0L
    }

    private fun liveContainerSummary(segment: ByteArray): String {
        if (segment.size < 8) return segment.take(8).joinToString("") { "%02x".format(it) }
        val boxes = ArrayList<String>(6)
        var offset = 0
        while (offset + 8 <= segment.size && boxes.size < 6) {
            val size = ((segment[offset].toLong() and 0xff) shl 24) or
                ((segment[offset + 1].toLong() and 0xff) shl 16) or
                ((segment[offset + 2].toLong() and 0xff) shl 8) or
                (segment[offset + 3].toLong() and 0xff)
            val type = String(segment, offset + 4, 4, Charsets.US_ASCII)
            if (type.any { it.code !in 0x20..0x7e }) break
            boxes += "$type:$size"
            if (size < 8 || size > segment.size - offset) break
            offset += size.toInt()
        }
        return boxes.joinToString(",").ifBlank {
            segment.take(12).joinToString("") { "%02x".format(it) }
        }
    }

    private fun stripMp4Init(segment: ByteArray): ByteArray {
        var offset = 0
        while (offset + 8 <= segment.size) {
            var size = ((segment[offset].toLong() and 0xff) shl 24) or
                ((segment[offset + 1].toLong() and 0xff) shl 16) or
                ((segment[offset + 2].toLong() and 0xff) shl 8) or
                (segment[offset + 3].toLong() and 0xff)
            val type = String(segment, offset + 4, 4, Charsets.US_ASCII)
            // A live segment is ftyp + moov + emsg + moof + mdat. Only ftyp/moov are the
            // repeated initialization; emsg belongs to this media fragment and must remain in
            // front of its moof. Dropping it made ExoPlayer stop exactly at a later segment edge.
            if (type != "ftyp" && type != "moov") return segment.copyOfRange(offset, segment.size)
            if (size == 1L) {
                if (offset + 16 > segment.size) break
                size = 0
                for (i in 8 until 16) size = (size shl 8) or (segment[offset + i].toLong() and 0xff)
            } else if (size == 0L) break
            if (size < 8 || size > segment.size - offset) break
            offset += size.toInt()
        }
        return segment
    }

    private fun stripWebmInit(segment: ByteArray): ByteArray {
        var offset = 0
        while (offset < segment.size) {
            val idStart = offset
            var mask = 0x80
            var idLength = 1
            val firstId = segment[offset].toInt() and 0xff
            while (idLength <= 4 && firstId and mask == 0) { idLength++; mask = mask ushr 1 }
            if (idLength > 4 || offset + idLength >= segment.size) break
            var id = 0L
            repeat(idLength) { id = (id shl 8) or (segment[offset + it].toLong() and 0xff) }
            if (id == 0x1F43B675L) return segment.copyOfRange(idStart, segment.size)
            offset += idLength
            var sizeMask = 0x80
            var sizeLength = 1
            val firstSize = segment[offset].toInt() and 0xff
            while (sizeLength <= 8 && firstSize and sizeMask == 0) { sizeLength++; sizeMask = sizeMask ushr 1 }
            if (sizeLength > 8 || offset + sizeLength > segment.size) break
            var size = (firstSize and sizeMask.inv()).toLong()
            var unknown = firstSize == (sizeMask or (sizeMask - 1))
            for (i in 1 until sizeLength) {
                val value = segment[offset + i].toInt() and 0xff
                size = (size shl 8) or value.toLong()
                if (value != 0xff) unknown = false
            }
            offset += sizeLength
            if (id == 0x18538067L) continue
            if (unknown || size > segment.size - offset) break
            offset += size.toInt()
        }
        return segment
    }

    private fun Long.orStartIfEmpty(t: Track): Long = if (t.lastSeq > 0) this else startTimeMs

    companion object {
        private const val TAG = "SabrVideoSession"
        private const val LIVE_EDGE_SENTINEL_MS = 9007199254740991L
        private const val LIVE_REQUEST_TIMEOUT_SECONDS = 12L
        private const val STARTUP_EMPTY_LIMIT = 2
        private const val LIVE_NETWORK_ERRORS_BEFORE_PLAYER_REFRESH = 3
        private const val MAX_STARTUP_REFRESHES = 2
        private const val MAX_MIDSTREAM_REFRESHES = 3
    }
}
