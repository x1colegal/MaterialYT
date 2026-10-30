package com.x1colegal.materialyt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AutoMusicService : MediaBrowserServiceCompat() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSessionCompat
    private lateinit var player: ExoPlayer
    private var tracks = emptyList<FeedItem>()
    private var currentTrack: FeedItem? = null
    private var currentArtwork: android.graphics.Bitmap? = null
    private var closingPlayback = false
    private var audioCandidates = emptyList<PlayableStream>()
    private var audioCandidateIndex = -1
    private var webFallbackAttempted = false
    private var extractionRetryCount = 0
    private var playGeneration = 0
    private var preferredAudioCodec = AudioCodecChoice.MP4A
    private var playbackQueue = emptyList<FeedItem>()
    private var playbackQueueIndex = -1
    private var autoplayTrack: FeedItem? = null
    private val browserTracks = mutableMapOf<String, FeedItem>()
    private val browserQueues = mutableMapOf<String, Pair<List<FeedItem>, Int>>()
    private lateinit var playbackWakeLock: PowerManager.WakeLock
    private lateinit var playbackWifiLock: WifiManager.WifiLock

    private fun acquirePlaybackLocks() {
        if (!playbackWakeLock.isHeld) playbackWakeLock.acquire()
        if (!playbackWifiLock.isHeld) playbackWifiLock.acquire()
        AppLog.event("music playback locks acquired wake=${playbackWakeLock.isHeld} wifi=${playbackWifiLock.isHeld}")
    }

    private fun releasePlaybackLocks() {
        if (playbackWifiLock.isHeld) playbackWifiLock.release()
        if (playbackWakeLock.isHeld) playbackWakeLock.release()
        AppLog.event("music playback locks released")
    }

    companion object {
        const val CHANNEL_ID = "materialyt_playback"
        const val NOTIFICATION_ID = 71
        val nowPlaying = androidx.compose.runtime.mutableStateOf<FeedItem?>(null)
        val playing = androidx.compose.runtime.mutableStateOf(false)
        val position = androidx.compose.runtime.mutableLongStateOf(0L)
        val bufferedPosition = androidx.compose.runtime.mutableLongStateOf(0L)
        val duration = androidx.compose.runtime.mutableLongStateOf(0L)
        val availableAudioTracks = androidx.compose.runtime.mutableStateOf<List<PlayableStream>>(emptyList())
        val selectedAudioTrack = androidx.compose.runtime.mutableStateOf<PlayableStream?>(null)
        private var instance: AutoMusicService? = null
        private const val ACTION_PLAY = "com.x1colegal.materialyt.PLAY"
        private const val ACTION_KEEP_ALIVE = "com.x1colegal.materialyt.KEEP_ALIVE"
        private const val ACTION_DISMISS = "com.x1colegal.materialyt.DISMISS_PLAYBACK"
        private const val ROOT_ID = "materialyt_music"
        private const val FEED_ID = "materialyt_auto_feed"
        private const val PLAYLIST_PREFIX = "materialyt_auto_playlist:"
        private const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val STYLE_SINGLE_ITEM = "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT"
        private const val STYLE_GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
        private const val LEGACY_STYLE_SUPPORTED = "android.auto.media.CONTENT_STYLE_SUPPORTED"
        private const val LEGACY_STYLE_PLAYABLE = "android.auto.media.CONTENT_STYLE_PLAYABLE_HINT"
        private const val LEGACY_STYLE_BROWSABLE = "android.auto.media.CONTENT_STYLE_BROWSABLE_HINT"
        private const val STYLE_GRID = 2

        fun play(context: Context, track: FeedItem, codec: AudioCodecChoice, queue: List<FeedItem> = emptyList(), queueIndex: Int = -1) {
            val intent = Intent(context, AutoMusicService::class.java).setAction(ACTION_PLAY)
                .putExtra("id", track.id).putExtra("title", track.title).putExtra("subtitle", track.subtitle)
                .putExtra("thumbnail", track.thumbnail).putExtra("thumbnailWidth", track.thumbnailWidth)
                .putExtra("thumbnailHeight", track.thumbnailHeight).putExtra("codec", codec.name)
                .putStringArrayListExtra("queueIds", ArrayList(queue.map { it.id }))
                .putStringArrayListExtra("queueTitles", ArrayList(queue.map { it.title }))
                .putStringArrayListExtra("queueSubtitles", ArrayList(queue.map { it.subtitle }))
                .putStringArrayListExtra("queueThumbnails", ArrayList(queue.map { it.thumbnail }))
                .putStringArrayListExtra("queueUrls", ArrayList(queue.map { it.url }))
                .putExtra("queueWidths", queue.map { it.thumbnailWidth }.toIntArray())
                .putExtra("queueHeights", queue.map { it.thumbnailHeight }.toIntArray())
                .putExtra("queueIndex", queueIndex)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
        fun pause() { instance?.player?.let { if (it.isPlaying) it.pause() } }
        fun toggle() { instance?.player?.let { if (it.isPlaying) it.pause() else it.play() } }
        fun seekTo(value: Long) { instance?.player?.seekTo(value) }
        fun selectAudioTrack(stream: PlayableStream) { instance?.switchAudioTrack(stream) }
        fun close() { instance?.requestClosePlayback() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW)
        )
        player = PlayerFactory.musicPlayer(this)
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        player.setHandleAudioBecomingNoisy(true)
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        playbackWakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:music-playback")
            .apply { setReferenceCounted(false) }
        @Suppress("DEPRECATION")
        playbackWifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:music-network")
            .apply { setReferenceCounted(false) }
        session = MediaSessionCompat(this, "MaterialYT Auto").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    AppLog.event("Android Auto playFromMediaId id=$mediaId")
                    playMediaId(mediaId)
                }
                override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
                    AppLog.event("Android Auto prepareFromMediaId id=$mediaId")
                    playMediaId(mediaId)
                }
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) { YouTubeRepository.musicSearch(query.orEmpty()) } }.getOrDefault(emptyList())
                        result.firstOrNull()?.let { play(it, preferredAudioCodec, result, 0) }
                    }
                }
                override fun onPlay() {
                    AppLog.event("Android Auto play state=${player.playbackState} mediaItems=${player.mediaItemCount}")
                    if (currentTrack != null) acquirePlaybackLocks()
                    player.play()
                }
                override fun onPause() { player.pause(); releasePlaybackLocks() }
                override fun onStop() { requestClosePlayback() }
                override fun onSeekTo(pos: Long) = player.seekTo(pos)
                override fun onSkipToNext() {
                    val next = playbackQueue.getOrNull(playbackQueueIndex + 1) ?: autoplayTrack
                    if (next != null) play(next, preferredAudioCodec, playbackQueue, playbackQueueIndex + 1)
                }
                override fun onSkipToPrevious() {
                    if (player.currentPosition > 5_000L) player.seekTo(0L)
                    else playbackQueue.getOrNull(playbackQueueIndex - 1)?.let {
                        play(it, preferredAudioCodec, playbackQueue, playbackQueueIndex - 1)
                    }
                }
            })
            isActive = true
        }
        sessionToken = session.sessionToken
        var endReported = false
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publishState()
                val id = currentTrack?.id
                if (!isPlaying && id != null) {
                    // ExoPlayer must only be read on its application looper. Capture the
                    // position here, then move only the network report to the IO dispatcher.
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { YouTubeRepository.reportPlayback(id, maxOf(0, pos - 1000L), pos, paused = true) }
                    }
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                currentTrack?.id?.let { id ->
                    if (playbackState == Player.STATE_READY) PlaybackLoadStatus.clear(id)
                    else if (playbackState == Player.STATE_BUFFERING && PlaybackLoadStatus.message.value?.mediaId == id) {
                        PlaybackLoadStatus.show(id, "Buffering media…")
                    }
                }
                if (playbackState == Player.STATE_READY && player.currentPosition < player.duration.coerceAtLeast(0L) - 1_000L) endReported = false
                if (playbackState == Player.STATE_ENDED && !endReported) {
                    endReported = true
                    val endedId = currentTrack?.id
                    val endedAt = player.currentPosition.coerceAtLeast(0L)
                    AppLog.event("music playback ended video=$endedId")
                    if (endedId != null) scope.launch(Dispatchers.IO) {
                        runCatching { YouTubeRepository.reportPlayback(endedId, maxOf(0L, endedAt - 1_000L), endedAt, ended = true) }
                    }
                    advanceAfterEnd()
                }
                publishState(); publishMetadata()
            }
            override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                val failedTrack = currentTrack ?: return
                AppLog.failure("music stream candidate=${audioCandidateIndex + 1}/${audioCandidates.size} video=${failedTrack.id}", error)
                scope.launch { playNextAudioCandidate(failedTrack, playGeneration) }
            }
        })
        publishState()
        scope.launch {
            var lastReported = 0L
            var reportedTrackId: String? = null
            while (true) {
                position.longValue = player.currentPosition.coerceAtLeast(0L); bufferedPosition.longValue = player.bufferedPosition.coerceAtLeast(position.longValue); duration.longValue = player.duration.coerceAtLeast(0L); publishState()
                val id = currentTrack?.id
                if (id != reportedTrackId) { reportedTrackId = id; lastReported = 0L }
                val knownDuration = duration.longValue
                if (!endReported && id != null && knownDuration > 0L && position.longValue >= knownDuration - 500L) {
                    endReported = true
                    AppLog.event("music playback reached end video=$id position=${position.longValue} duration=$knownDuration")
                    scope.launch(Dispatchers.IO) {
                        runCatching { YouTubeRepository.reportPlayback(id, maxOf(0L, position.longValue - 1_000L), position.longValue, ended = true) }
                    }
                    advanceAfterEnd()
                }
                val due = lastReported == 0L && position.longValue >= 1_000L || position.longValue - lastReported >= 10_000L
                if (id != null && player.isPlaying && due) {
                        runCatching { withContext(Dispatchers.IO) { YouTubeRepository.reportPlayback(id, lastReported, position.longValue) } }
                        lastReported = position.longValue
                } else if (position.longValue < lastReported) lastReported = position.longValue
                kotlinx.coroutines.delay(500)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            requestClosePlayback()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PLAY) {
            val id = intent.getStringExtra("id").orEmpty()
            if (id.isNotBlank()) {
                val ids = intent.getStringArrayListExtra("queueIds").orEmpty()
                val titles = intent.getStringArrayListExtra("queueTitles").orEmpty()
                val subtitles = intent.getStringArrayListExtra("queueSubtitles").orEmpty()
                val thumbnails = intent.getStringArrayListExtra("queueThumbnails").orEmpty()
                val urls = intent.getStringArrayListExtra("queueUrls").orEmpty()
                val widths = intent.getIntArrayExtra("queueWidths") ?: intArrayOf()
                val heights = intent.getIntArrayExtra("queueHeights") ?: intArrayOf()
                val queue = ids.indices.map { index ->
                    FeedItem(ids[index], titles.getOrElse(index) { "" }, subtitles.getOrElse(index) { "" },
                        thumbnails.getOrElse(index) { "" }, widths.getOrElse(index) { 1 }, heights.getOrElse(index) { 1 }, "",
                        urls.getOrElse(index) { "https://www.youtube.com/watch?v=${ids[index]}" })
                }
                play(FeedItem(id, intent.getStringExtra("title").orEmpty(), intent.getStringExtra("subtitle").orEmpty(),
                    intent.getStringExtra("thumbnail").orEmpty(), intent.getIntExtra("thumbnailWidth", 1), intent.getIntExtra("thumbnailHeight", 1), "", "https://www.youtube.com/watch?v=$id"),
                    runCatching { AudioCodecChoice.valueOf(intent.getStringExtra("codec") ?: "MP4A") }.getOrDefault(AudioCodecChoice.MP4A),
                    queue, intent.getIntExtra("queueIndex", -1))
            }
        }
        return START_STICKY
    }

    private fun playMediaId(mediaId: String?) {
        if (mediaId == null) return
        browserTracks[mediaId]?.let { track ->
            val (queue, index) = browserQueues[mediaId] ?: (listOf(track) to 0)
            play(track, preferredAudioCodec, queue, index)
            return
        }
        tracks.indexOfFirst { it.id == mediaId }.takeIf { it >= 0 }?.let { index -> play(tracks[index], preferredAudioCodec, tracks, index) } ?: scope.launch {
            tracks = runCatching { originalMetadataItems(withContext(Dispatchers.IO) { YouTubeRepository.music() }) }.getOrDefault(emptyList())
            tracks.indexOfFirst { it.id == mediaId }.takeIf { it >= 0 }?.let { index -> play(tracks[index], preferredAudioCodec, tracks, index) }
        }
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?) =
        BrowserRoot("materialyt_music", null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.detach()
        scope.launch {
            val children = runCatching {
                when {
                    parentId == ROOT_ID -> listOf(feedRootItem())
                    parentId == FEED_ID -> combinedRootItems()
                    parentId.startsWith(PLAYLIST_PREFIX) -> playlistTracks(parentId)
                    else -> emptyList()
                }
            }.onFailure { AppLog.failure("Android Auto browse parent=$parentId", it) }
                .getOrDefault(emptyList())
            result.sendResult(children.toMutableList())
        }
    }

    private fun feedRootItem(): MediaBrowserCompat.MediaItem {
        val extras = Bundle().apply {
            putInt(STYLE_PLAYABLE, STYLE_GRID)
            putInt(STYLE_BROWSABLE, STYLE_GRID)
            putBoolean(LEGACY_STYLE_SUPPORTED, true)
            putInt(LEGACY_STYLE_PLAYABLE, STYLE_GRID)
            putInt(LEGACY_STYLE_BROWSABLE, STYLE_GRID)
        }
        val icon = Uri.parse("android.resource://$packageName/${R.mipmap.ic_launcher}")
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(FEED_ID)
            .setTitle("Home")
            .setIconUri(icon)
            .setExtras(extras)
            .build()
        return MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    private suspend fun combinedRootItems(): List<MediaBrowserCompat.MediaItem> {
        val page = withContext(Dispatchers.IO) { YouTubeRepository.musicHome() }
        val output = mutableListOf<MediaBrowserCompat.MediaItem>()
        val visiblePlaylistIds = mutableSetOf<String>()
        page.sections.forEachIndexed { sectionIndex, section ->
            val items = originalMetadataItems(section.items)
            val queue = items.filter { !it.playlist && !it.channel && it.id.length == 11 }
            items.forEach { item ->
                val group = section.title.ifBlank { "Music" }
                if (item.playlist) {
                    val playlistId = item.id.removePrefix("VL")
                    if (playlistId.isNotBlank() && visiblePlaylistIds.add(playlistId)) {
                        output += browserItem("$PLAYLIST_PREFIX${Uri.encode(playlistId)}", item.title, item.subtitle, item.thumbnail, playable = false, groupTitle = group)
                    }
                } else if (!item.channel && item.id.length == 11) {
                    val index = queue.indexOfFirst { it.id == item.id }
                    if (index >= 0) {
                        val mediaId = "track:section-$sectionIndex:$index:${item.id}"
                        browserTracks[mediaId] = item
                        browserQueues[mediaId] = queue to index
                        output += browserItem(mediaId, item.title, item.subtitle, item.thumbnail, playable = true, groupTitle = group)
                    }
                }
            }
        }
        val playlists = originalMetadataItems(withContext(Dispatchers.IO) { YouTubeRepository.music() })
            .filter { it.playlist }
            .distinctBy { it.id.removePrefix("VL") }
        playlists.forEach { playlist ->
            val playlistId = playlist.id.removePrefix("VL")
            if (playlistId.isNotBlank() && visiblePlaylistIds.add(playlistId)) {
                output += browserItem("$PLAYLIST_PREFIX${Uri.encode(playlistId)}", playlist.title, playlist.subtitle, playlist.thumbnail, playable = false, groupTitle = "Playlists")
            }
        }
        tracks = page.sections.flatMap { it.items }.filter { !it.playlist && !it.channel && it.id.length == 11 }
        return output
    }

    private suspend fun playlistTracks(parentId: String): List<MediaBrowserCompat.MediaItem> {
        val playlistId = Uri.decode(parentId.removePrefix(PLAYLIST_PREFIX)).removePrefix("VL")
        if (playlistId.isBlank()) return emptyList()
        val queue = originalMetadataItems(withContext(Dispatchers.IO) { YouTubeRepository.musicPlaylistVideos(playlistId) })
            .filter { !it.playlist && !it.channel && it.id.length == 11 }
        if (queue.isEmpty()) return emptyList()
        val playAllId = "play-all:$playlistId"
        browserTracks[playAllId] = queue.first()
        browserQueues[playAllId] = queue to 0
        return listOf(browserItem(playAllId, "Play playlist", "${queue.size} songs", queue.first().thumbnail, playable = true)) +
            registerPlayableQueue("playlist-$playlistId", queue)
    }

    private fun registerPlayableQueue(scopeId: String, queue: List<FeedItem>): List<MediaBrowserCompat.MediaItem> =
        queue.mapIndexed { index, track ->
            val mediaId = "track:$scopeId:$index:${track.id}"
            browserTracks[mediaId] = track
            browserQueues[mediaId] = queue to index
            browserItem(mediaId, track.title, track.subtitle, track.thumbnail, playable = true)
        }

    private fun browserItem(mediaId: String, title: String, subtitle: String, thumbnail: String?, playable: Boolean, groupTitle: String? = null): MediaBrowserCompat.MediaItem {
        val extras = Bundle().apply {
            putInt(STYLE_SINGLE_ITEM, STYLE_GRID)
            groupTitle?.takeIf(String::isNotBlank)?.let { putString(STYLE_GROUP_TITLE, it) }
        }
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .setSubtitle(subtitle.takeIf(String::isNotBlank))
            .apply { thumbnail?.takeIf(String::isNotBlank)?.let { setIconUri(Uri.parse(it)) } }
            .setExtras(extras)
            .build()
        return MediaBrowserCompat.MediaItem(description, if (playable) MediaBrowserCompat.MediaItem.FLAG_PLAYABLE else MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    private fun play(track: FeedItem, preferredCodec: AudioCodecChoice = AudioCodecChoice.MP4A, queue: List<FeedItem> = emptyList(), queueIndex: Int = -1) {
        // Android Auto normally binds MediaBrowserService without starting it. A bound-only
        // service can be suspended as soon as the screen turns off; playback launched from the
        // app did not have this problem because ACTION_PLAY starts the foreground service first.
        // Promote Auto-originated playback to the same started-service lifecycle.
        val keepAlive = Intent(this, AutoMusicService::class.java).setAction(ACTION_KEEP_ALIVE)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(keepAlive) else startService(keepAlive)
        AppLog.event("music service promoted from browser binding to started foreground service")
        val generation = ++playGeneration
        scope.launch {
            acquirePlaybackLocks()
            currentTrack = track
            preferredAudioCodec = preferredCodec
            playbackQueue = queue
            playbackQueueIndex = queueIndex
            autoplayTrack = null
            audioCandidates = emptyList()
            audioCandidateIndex = -1
            availableAudioTracks.value = emptyList()
            selectedAudioTrack.value = null
            webFallbackAttempted = false
            extractionRetryCount = 0
            nowPlaying.value = track
            currentArtwork = null
            publishMetadata()
            // Become a foreground media service before artwork/extraction network work. This
            // keeps Android Auto playback alive when the phone is locked or the UI never opens.
            startForeground(NOTIFICATION_ID, notification())
            scope.launch(Dispatchers.IO) {
                runCatching { YouTubeRepository.preparePlaybackTracking(track.id, music = true) }
                    .onFailure { AppLog.failure("prepare standard history tracking music=${track.id}", it) }
            }
            scope.launch {
                autoplayTrack = runCatching { withContext(Dispatchers.IO) { YouTubeRepository.musicUpNext(track.id) } }
                    .onFailure { AppLog.failure("music service up next video=${track.id}", it) }
                    .getOrNull()
                AppLog.event("music service up next video=${track.id} next=${autoplayTrack?.id ?: "none"}")
            }
            // Artwork belongs to the playback service, not to the Compose screen. Fetch it in
            // parallel so Android Auto/lock-screen metadata is complete even when the UI never
            // opens, without delaying stream extraction or the beginning of playback.
            scope.launch {
                val artwork = runCatching { withContext(Dispatchers.IO) { YouTubeRepository.loadArtwork(track.thumbnail) } }
                    .onFailure { AppLog.failure("music artwork video=${track.id}", it) }
                    .getOrNull()
                if (currentTrack?.id == track.id && generation == playGeneration) {
                    currentArtwork = artwork
                    publishMetadata()
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                    AppLog.event("music artwork video=${track.id} loaded=${artwork != null}")
                }
            }
            val audioStreams = extractAudioStreams(track)
            if (currentTrack?.id != track.id || generation != playGeneration) return@launch
            availableAudioTracks.value = audioStreams.distinctBy(::audioStreamKey)
            setAudioCandidates(audioStreams, preferredCodec)
            playNextAudioCandidate(track, generation)
        }
    }

    private fun advanceAfterEnd() {
        val endedTrack = currentTrack ?: return
        val nextIndex = playbackQueueIndex + 1
        val next = if (playbackQueueIndex >= 0) playbackQueue.getOrNull(nextIndex) else null
        when {
            next != null -> {
                AppLog.event("music service advancing queue from=${endedTrack.id} to=${next.id} index=$nextIndex")
                play(next, preferredAudioCodec, playbackQueue, nextIndex)
            }
            autoplayTrack != null -> {
                val autoplay = autoplayTrack ?: return
                AppLog.event("music service advancing autoplay from=${endedTrack.id} to=${autoplay.id}")
                play(autoplay, preferredAudioCodec)
            }
            else -> AppLog.event("music service has no next track after=${endedTrack.id}")
        }
    }

    private suspend fun extractAudioStreams(track: FeedItem): List<PlayableStream> {
        var audioStreams = runCatching { withContext(Dispatchers.IO) { YouTubeRepository.sabrPlaybackInfo(track.id, music = true) } }
                .onFailure { AppLog.failure("music SABR primary video=${track.id}", it) }
                .getOrNull()?.let { playback ->
                    playback.formats.filter { it.audio }.map { format ->
                        PlayableStream(playback.serverAbrStreamingUrl, format.codec, 0, 0,
                            format.bitrate, true, false, format.originalAudio,
                            format.audioTrackName, format.audioTrackId, playback, format)
                    }
                }.orEmpty()
        if (audioStreams.isEmpty()) {
            audioStreams = runCatching { withContext(Dispatchers.IO) { YouTubeRepository.playerStreams(track.id, music = true) } }
                .getOrNull()?.filter { it.audio }.orEmpty()
        }
        return audioStreams
    }

    private fun setAudioCandidates(audioStreams: List<PlayableStream>, codec: AudioCodecChoice) {
        val original = audioStreams.filter { it.audioTrackName.contains("original", true) }.ifEmpty { audioStreams.filter { it.originalAudio } }.ifEmpty { audioStreams }
        audioCandidates = original.sortedWith(compareByDescending<PlayableStream> { stream -> codec.tokens.any { stream.codec.contains(it, true) } }.thenByDescending { it.bitrate })
        audioCandidateIndex = -1
    }

    private suspend fun playNextAudioCandidate(track: FeedItem, generation: Int) {
        if (currentTrack?.id != track.id || generation != playGeneration) return
        val next = audioCandidateIndex + 1
        if (next >= audioCandidates.size) {
            if (!webFallbackAttempted) {
                webFallbackAttempted = true
                val webStreams = withContext(Dispatchers.IO) {
                    runCatching { YouTubeRepository.playerStreams(track.id, music = true) }.getOrDefault(emptyList())
                }.filter { it.audio }
                val currentKeys = availableAudioTracks.value.mapTo(mutableSetOf(), ::audioStreamKey)
                val additional = webStreams.filterNot { audioStreamKey(it) in currentKeys }.sortedWith(
                    compareByDescending<PlayableStream> { stream -> preferredAudioCodec.tokens.any { stream.codec.contains(it, true) } }
                        .thenByDescending { it.bitrate }
                )
                audioCandidates = audioCandidates + additional
                availableAudioTracks.value = (availableAudioTracks.value + additional).distinctBy(::audioStreamKey)
                if (additional.isNotEmpty()) return playNextAudioCandidate(track, generation)
            }
            if (extractionRetryCount < 1) {
                extractionRetryCount++
                AppLog.event("music refreshing extraction after failed first attempt video=${track.id}")
                kotlinx.coroutines.delay(500)
                val refreshed = extractAudioStreams(track)
                if (currentTrack?.id != track.id || generation != playGeneration) return
                availableAudioTracks.value = refreshed.distinctBy(::audioStreamKey)
                setAudioCandidates(refreshed, preferredAudioCodec)
                webFallbackAttempted = false
                if (audioCandidates.isNotEmpty()) return playNextAudioCandidate(track, generation)
            }
            AppLog.event("music exhausted all stream candidates video=${track.id}")
            return
        }
        audioCandidateIndex = next
        val stream = audioCandidates[next]
        selectedAudioTrack.value = stream
        AppLog.event("music trying stream candidate=${next + 1}/${audioCandidates.size} codec=${stream.codec} bitrate=${stream.bitrate} video=${track.id}")
        val source = if (stream.sabrPlayback != null && stream.sabrFormat != null) {
            SabrMediaFactory.createAudio(stream.sabrPlayback, stream.sabrFormat,
                sabrDataSourceFactory(stream.sabrPlayback.serverAbrStreamingUrl))
        } else ProgressiveMediaSource.Factory(videoDataSourceFactory(stream.url))
            .createMediaSource(MediaItem.fromUri(stream.url))
        player.setMediaSource(source)
        player.seekTo(0L)
        player.prepare()
        player.playWhenReady = true
        session.isActive = true
    }

    private fun audioStreamKey(stream: PlayableStream): String = when {
        stream.audioTrackId.isNotBlank() -> "id:${stream.audioTrackId}|${stream.codec.lowercase()}"
        stream.sabrFormat != null -> "itag:${stream.sabrFormat.itag}"
        stream.audioTrackName.isNotBlank() -> "name:${stream.audioTrackName.lowercase()}|${stream.codec.lowercase()}"
        else -> "stream:${stream.url}|${stream.codec.lowercase()}|${stream.bitrate}"
    }

    private fun switchAudioTrack(stream: PlayableStream) {
        val track = currentTrack ?: return
        val generation = playGeneration
        val resumePosition = player.currentPosition.coerceAtLeast(0L)
        val resumePlayback = player.playWhenReady
        val source = if (stream.sabrPlayback != null && stream.sabrFormat != null) {
            SabrMediaFactory.createAudio(stream.sabrPlayback, stream.sabrFormat,
                sabrDataSourceFactory(stream.sabrPlayback.serverAbrStreamingUrl))
        } else ProgressiveMediaSource.Factory(videoDataSourceFactory(stream.url))
            .createMediaSource(MediaItem.fromUri(stream.url))
        audioCandidateIndex = audioCandidates.indexOfFirst { audioStreamKey(it) == audioStreamKey(stream) }
        selectedAudioTrack.value = stream
        player.setMediaSource(source, resumePosition)
        player.prepare()
        player.playWhenReady = resumePlayback
        AppLog.event("music audio track selected codec=${stream.codec} bitrate=${stream.bitrate} video=${track.id} generation=$generation")
    }

    private fun publishMetadata() {
        val track = currentTrack ?: return
        val builder = MediaMetadataCompat.Builder().putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track.id)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title).putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, track.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.subtitle).putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, track.subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, track.thumbnail).putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, track.thumbnail)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player.duration.coerceAtLeast(0L))
        currentArtwork?.let {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
        }
        session.setMetadata(builder.build())
    }

    private fun notification(): android.app.Notification {
        val track = currentTrack
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val dismiss = PendingIntent.getService(this, 1, Intent(this, AutoMusicService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(track?.title ?: "MaterialYT").setContentText(track?.subtitle ?: "Loading music…")
            .setContentIntent(launch).setDeleteIntent(dismiss).setOnlyAlertOnce(true).setOngoing(player.isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setStyle(MediaStyle().setMediaSession(session.sessionToken))
        currentArtwork?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    private fun publishState() {
        val state = when {
            player.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            player.playbackState == Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        playing.value = player.isPlaying
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP)
            .setState(state, player.currentPosition.coerceAtLeast(0L), if (player.isPlaying) 1f else 0f).build())
        if (currentTrack != null) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun requestClosePlayback() {
        if (closingPlayback) return
        closingPlayback = true
        // Remove the Compose miniplayer before mutating ExoPlayer. Stopping the player
        // synchronously from the nested close/drag gesture can invalidate its own UI tree.
        nowPlaying.value = null
        playing.value = false
        currentTrack = null
        currentArtwork = null
        availableAudioTracks.value = emptyList()
        selectedAudioTrack.value = null
        position.longValue = 0L
        bufferedPosition.longValue = 0L
        duration.longValue = 0L
        scope.launch {
            kotlinx.coroutines.yield()
            runCatching {
                player.pause()
                player.clearMediaItems()
                releasePlaybackLocks()
                session.setMetadata(null)
                session.setPlaybackState(PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH)
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0f).build())
                // Let the active video MediaSession become Android Auto's Now Playing
                // session when a mixed YT Music playlist advances to a video item.
                session.isActive = false
                stopForeground(true)
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            }.onFailure { AppLog.failure("close music miniplayer", it) }
            closingPlayback = false
        }
    }

    override fun onDestroy() { instance = null; nowPlaying.value = null; playing.value = false; availableAudioTracks.value = emptyList(); selectedAudioTrack.value = null; releasePlaybackLocks(); scope.cancel(); player.release(); session.release(); super.onDestroy() }

}
