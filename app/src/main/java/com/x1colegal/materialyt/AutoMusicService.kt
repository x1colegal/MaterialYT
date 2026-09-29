package com.x1colegal.materialyt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
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

    companion object {
        const val CHANNEL_ID = "materialyt_playback"
        const val NOTIFICATION_ID = 71
        val nowPlaying = androidx.compose.runtime.mutableStateOf<FeedItem?>(null)
        val playing = androidx.compose.runtime.mutableStateOf(false)
        val position = androidx.compose.runtime.mutableLongStateOf(0L)
        val bufferedPosition = androidx.compose.runtime.mutableLongStateOf(0L)
        val duration = androidx.compose.runtime.mutableLongStateOf(0L)
        private var instance: AutoMusicService? = null
        private const val ACTION_PLAY = "com.x1colegal.materialyt.PLAY"

        fun play(context: Context, track: FeedItem, codec: AudioCodecChoice) {
            val intent = Intent(context, AutoMusicService::class.java).setAction(ACTION_PLAY)
                .putExtra("id", track.id).putExtra("title", track.title).putExtra("subtitle", track.subtitle)
                .putExtra("thumbnail", track.thumbnail).putExtra("thumbnailWidth", track.thumbnailWidth)
                .putExtra("thumbnailHeight", track.thumbnailHeight).putExtra("codec", codec.name)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
        fun toggle() { instance?.player?.let { if (it.isPlaying) it.pause() else it.play() } }
        fun seekTo(value: Long) { instance?.player?.seekTo(value) }
        fun close() { instance?.stopPlayback() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW)
        )
        player = ExoPlayer.Builder(this).setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 180_000, 750, 1_500)
            .setPrioritizeTimeOverSizeThresholds(true).build()).build()
        session = MediaSessionCompat(this, "MaterialYT Auto").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    playMediaId(mediaId)
                }
                override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) = playMediaId(mediaId)
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) { YouTubeRepository.musicSearch(query.orEmpty()) } }.getOrDefault(emptyList())
                        result.firstOrNull()?.let(::play)
                    }
                }
                override fun onPlay() = player.play()
                override fun onPause() = player.pause()
                override fun onStop() { player.stop(); stopForeground(true); isActive = false }
                override fun onSeekTo(pos: Long) = player.seekTo(pos)
            })
            isActive = true
        }
        sessionToken = session.sessionToken
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = publishState()
            override fun onPlaybackStateChanged(playbackState: Int) { publishState(); publishMetadata() }
        })
        publishState()
        scope.launch {
            var lastReported = 0L
            var reportedTrackId: String? = null
            while (true) {
                position.longValue = player.currentPosition.coerceAtLeast(0L); bufferedPosition.longValue = player.bufferedPosition.coerceAtLeast(position.longValue); duration.longValue = player.duration.coerceAtLeast(0L); publishState()
                val id = currentTrack?.id
                if (id != reportedTrackId) { reportedTrackId = id; lastReported = 0L }
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
        if (intent?.action == ACTION_PLAY) {
            val id = intent.getStringExtra("id").orEmpty()
            if (id.isNotBlank()) play(FeedItem(id, intent.getStringExtra("title").orEmpty(), intent.getStringExtra("subtitle").orEmpty(),
                intent.getStringExtra("thumbnail").orEmpty(), intent.getIntExtra("thumbnailWidth", 1), intent.getIntExtra("thumbnailHeight", 1), "", "https://www.youtube.com/watch?v=$id"),
                runCatching { AudioCodecChoice.valueOf(intent.getStringExtra("codec") ?: "MP4A") }.getOrDefault(AudioCodecChoice.MP4A))
        }
        return START_STICKY
    }

    private fun playMediaId(mediaId: String?) {
        if (mediaId == null) return
        tracks.firstOrNull { it.id == mediaId }?.let(::play) ?: scope.launch {
            tracks = runCatching { withContext(Dispatchers.IO) { YouTubeRepository.music() } }.getOrDefault(emptyList())
            tracks.firstOrNull { it.id == mediaId }?.let(::play)
        }
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?) =
        BrowserRoot("materialyt_music", null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        if (parentId != "materialyt_music") { result.sendResult(mutableListOf()); return }
        result.detach()
        scope.launch {
            tracks = runCatching { withContext(Dispatchers.IO) { YouTubeRepository.music() } }.getOrDefault(emptyList())
            result.sendResult(tracks.map { track ->
                val description = MediaDescriptionCompat.Builder().setMediaId(track.id).setTitle(track.title)
                    .setSubtitle(track.subtitle).setIconUri(Uri.parse(track.thumbnail)).build()
                MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
            }.toMutableList())
        }
    }

    private fun play(track: FeedItem, preferredCodec: AudioCodecChoice = AudioCodecChoice.MP4A) {
        scope.launch {
            currentTrack = track
            nowPlaying.value = track
            publishMetadata()
            startForeground(NOTIFICATION_ID, notification())
            val audioStreams = runCatching { withContext(Dispatchers.IO) { YouTubeRepository.playerStreams(track.id, music = true) } }
                .getOrNull()?.filter { it.audio }.orEmpty()
            val original = audioStreams.filter { it.audioTrackName.contains("original", true) }.ifEmpty { audioStreams.filter { it.originalAudio } }.ifEmpty { audioStreams }
            val stream = original.filter { stream -> preferredCodec.tokens.any { stream.codec.contains(it, true) } }.maxByOrNull { it.bitrate }
                ?: original.maxByOrNull { it.bitrate } ?: return@launch
            val source = ProgressiveMediaSource.Factory(DefaultHttpDataSource.Factory().setDefaultRequestProperties(YouTubeRepository.mediaHeaders()))
                .createMediaSource(MediaItem.fromUri(stream.url))
            player.setMediaSource(source); player.prepare(); player.playWhenReady = true; session.isActive = true
        }
    }

    private fun publishMetadata() {
        val track = currentTrack ?: return
        session.setMetadata(MediaMetadataCompat.Builder().putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track.id)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title).putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, track.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.subtitle).putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, track.subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, track.thumbnail).putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, track.thumbnail)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player.duration.coerceAtLeast(0L)).build())
    }

    private fun notification(): android.app.Notification {
        val track = currentTrack
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(track?.title ?: "MaterialYT").setContentText(track?.subtitle ?: "Loading music…")
            .setContentIntent(launch).setOnlyAlertOnce(true).setOngoing(player.isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setStyle(MediaStyle().setMediaSession(session.sessionToken)).build()
    }

    private fun publishState() {
        val state = when {
            player.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            player.playbackState == Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        playing.value = player.isPlaying
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_STOP)
            .setState(state, player.currentPosition.coerceAtLeast(0L), if (player.isPlaying) 1f else 0f).build())
        if (currentTrack != null) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun stopPlayback() {
        player.stop(); currentTrack = null; nowPlaying.value = null; playing.value = false
        stopForeground(true); getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    override fun onDestroy() { instance = null; nowPlaying.value = null; playing.value = false; scope.cancel(); player.release(); session.release(); super.onDestroy() }
}
