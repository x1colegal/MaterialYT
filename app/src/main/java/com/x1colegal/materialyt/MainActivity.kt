package com.x1colegal.materialyt
import androidx.compose.ui.platform.LocalFocusManager

import android.app.PictureInPictureParams
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.text.Html
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.app.NotificationCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerControlView
import com.google.android.exoplayer2.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo

enum class ThemeMode { SYSTEM, LIGHT, DARK, OLED }
enum class CodecChoice(val label: String, val tokens: List<String>) {
    H264("AVC", listOf("avc1", "h264")), HEVC("HEVC", listOf("hev1", "hvc1", "hevc")),
    AV1("AV1", listOf("av01", "av1")), VP9("VP9", listOf("vp9", "vp09"))
}
enum class AudioCodecChoice(val label: String, val tokens: List<String>) {
    MP4A("MP4A / AAC", listOf("mp4a", "aac")), OPUS("Opus", listOf("opus")), VORBIS("Vorbis", listOf("vorbis"))
}
enum class QualityChoice(val label: String, val height: Int) {
    AUTO("Auto", 0), UHD2160("2160p", 2160), QHD1440("1440p", 1440), FHD1080("1080p", 1080), HD720("720p", 720), SD480("480p", 480), SD360("360p", 360)
}

private data class PlayerChoice(val url: String, val codec: String, val height: Int, val fps: Int, val bitrate: Int, val videoOnly: Boolean, val audioTrackName: String = "", val audioTrackId: String = "", val originalAudio: Boolean = false, val sabrFormat: SabrFormat? = null)

private val videoHttpClient by lazy {
    OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

internal fun videoDataSourceFactory(streamUrl: String): DataSource.Factory = ChunkedDataSource.Factory(
    OkHttpDataSource.Factory(videoHttpClient)
        .setDefaultRequestProperties(YouTubeRepository.mediaHeaders(streamUrl))
)

/** SABR uses protobuf POST requests; applying progressive-download Range chunking breaks them. */
internal fun sabrDataSourceFactory(streamUrl: String): DataSource.Factory =
    OkHttpDataSource.Factory(videoHttpClient)
        .setDefaultRequestProperties(YouTubeRepository.mediaHeaders(streamUrl))

private fun selectVideoStream(streams: List<PlayerChoice>, codec: CodecChoice, quality: QualityChoice): PlayerChoice? {
    val valid = streams.filter { it.url.startsWith("http") }
    val matchingCodec = valid.filter { stream -> codec.tokens.any { stream.codec.lowercase().contains(it) } }
    if (quality == QualityChoice.AUTO) return (matchingCodec.ifEmpty { valid }).maxByOrNull { it.height }
    matchingCodec.firstOrNull { it.height == quality.height }?.let { return it }
    matchingCodec.minByOrNull { kotlin.math.abs(it.height - quality.height) }?.let { return it }
    valid.firstOrNull { it.height == quality.height }?.let { return it }
    return valid.minByOrNull { kotlin.math.abs(it.height - quality.height) }
}

private fun selectAudioStream(streams: List<PlayerChoice>, preferredCodec: AudioCodecChoice): PlayerChoice? {
    val valid = streams.filter { it.url.startsWith("http") }
    val originals = valid.filter { it.originalAudio || it.audioTrackName.contains("original", true) }.ifEmpty { valid }
    val matching = originals.filter { track -> preferredCodec.tokens.any { token -> track.codec.contains(token, true) } }
    return matching.maxByOrNull { it.bitrate } ?: originals.maxByOrNull { it.bitrate } ?: valid.maxByOrNull { it.bitrate }
}

private fun audioChoiceKey(stream: PlayerChoice): String = when {
    stream.audioTrackId.isNotBlank() -> "id:${stream.audioTrackId}|${stream.codec.lowercase()}"
    stream.audioTrackName.isNotBlank() -> "name:${stream.audioTrackName.lowercase()}|${stream.codec.lowercase()}"
    stream.sabrFormat != null -> "itag:${stream.sabrFormat.itag}"
    else -> "stream:${stream.url}|${stream.codec.lowercase()}|${stream.bitrate}"
}

private fun visibleAudioChoices(streams: List<PlayerChoice>, selected: PlayerChoice?): List<PlayerChoice> =
    (streams + listOfNotNull(selected)).distinctBy(::audioChoiceKey)

private fun audioChoiceLabel(stream: PlayerChoice): String = stream.audioTrackName.ifBlank {
    if (stream.originalAudio) "Original audio" else "Default audio"
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1000)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.autoMarquee(): Modifier = basicMarquee(
    iterations = Int.MAX_VALUE,
    initialDelayMillis = 1_500,
    repeatDelayMillis = 2_000
)
enum class AppColor(val title: String, val seed: Long) {
    PURPLE("Purple", 0xFF8B5CF6), RED("YouTube Red", 0xFFFF1744), BLUE("Bright Blue", 0xFF2196F3), BLUE_CYAN("Blue Cyan", 0xFF00B8D4),
    GREEN("Forest", 0xFF43A047), ORANGE("Amber", 0xFFFF8F00), PINK("Rose", 0xFFFF4081), TEAL("Teal", 0xFF00A896),
    INDIGO("Indigo", 0xFF4555A5), CYAN("Cyan", 0xFF006874), LIME("Lime", 0xFF526600),
    BROWN("Cocoa", 0xFF805610), MAGENTA("Magenta", 0xFF9C3D88), SLATE("Slate", 0xFF526070)
}

class MainActivity : ComponentActivity() {
    companion object { private const val ACTION_PIP_TOGGLE = "com.x1colegal.materialyt.PIP_TOGGLE" }
    private var player: ExoPlayer? = null
    fun registerCurrentPlayer(p: ExoPlayer) { player = p }
    private var visiblePlayerView: PlayerView? = null
    private var videoPlayerActive = false
    var isFullscreen by mutableStateOf(false)
        private set
    var pipMode by mutableStateOf(false)
        private set
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PIP_TOGGLE) {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
                updatePipAction()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pipReceiver, IntentFilter(ACTION_PIP_TOGGLE), RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(pipReceiver, IntentFilter(ACTION_PIP_TOGGLE))
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 71)
        setContent { MaterialYtRoot(this) { player = it } }
    }

    private val artworkCache = mutableMapOf<String, android.graphics.Bitmap>()
    private val playbackNotificationScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    fun showPlaybackNotification(session: MediaSessionCompat, title: String, subtitle: String, thumbnailUrl: String?, playing: Boolean) {
        val channelId = "materialyt_app_playback"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channelId, "Playback", NotificationManager.IMPORTANCE_LOW))
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

        fun notifyWithBitmap(bitmap: android.graphics.Bitmap?) {
            val builder = NotificationCompat.Builder(this, channelId).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title).setContentText(subtitle).setContentIntent(launch).setOnlyAlertOnce(true)
                .setOngoing(playing).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setStyle(MediaStyle().setMediaSession(session.sessionToken))
            val metaBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
            if (bitmap != null) {
                builder.setLargeIcon(bitmap)
                metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
                metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
            }
            if (!thumbnailUrl.isNullOrBlank()) {
                metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, thumbnailUrl)
                metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, thumbnailUrl)
            }
            session.setMetadata(metaBuilder.build())
            manager.notify(72, builder.build())
        }

        val cached = thumbnailUrl?.let { artworkCache[it] }
        notifyWithBitmap(cached)
        if (cached == null && !thumbnailUrl.isNullOrBlank()) {
            playbackNotificationScope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        val conn = java.net.URL(thumbnailUrl).openConnection()
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        android.graphics.BitmapFactory.decodeStream(conn.getInputStream())
                    }.getOrNull()
                }
                if (loaded != null) {
                    artworkCache[thumbnailUrl] = loaded
                    notifyWithBitmap(loaded)
                }
            }
        }
    }

    fun stopPlaybackNotification() { getSystemService(NotificationManager::class.java).cancel(72) }
    fun showMusicNotification(session: MediaSessionCompat, track: FeedItem, playing: Boolean) {
        showPlaybackNotification(session, track.title, track.subtitle, track.thumbnail, playing)
    }
    fun stopMusicNotification() { stopPlaybackNotification() }

    fun fullscreen(enabled: Boolean) {
        isFullscreen = enabled
        requestedOrientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        applyFullscreenState()
    }

    private fun applyFullscreenState() {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (isFullscreen) {
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (isFullscreen) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else View.SYSTEM_UI_FLAG_VISIBLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isFullscreen && !pipMode) applyFullscreenState()
    }

    override fun onStop() {
        super.onStop()
        if (!pipMode && !getSharedPreferences("settings", 0).getBoolean("background_play", false)) {
            player?.pause()
            
        }
    }

    fun pip() {
        visiblePlayerView?.hideController()
        if (Build.VERSION.SDK_INT >= 26) enterPictureInPictureMode(pipParams())
    }

    private fun pipParams(): PictureInPictureParams {
        val playing = player?.isPlaying == true
        val videoFormat = player?.videoFormat
        val videoWidth = videoFormat?.width?.takeIf { it > 0 } ?: 16
        val videoHeight = videoFormat?.height?.takeIf { it > 0 } ?: 9
        val pixelRatio = videoFormat?.pixelWidthHeightRatio?.takeIf { it > 0f } ?: 1f
        val displayRatio = (videoWidth.toFloat() * pixelRatio / videoHeight).coerceIn(0.45f, 2.35f)
        val aspectRatio = Rational((displayRatio * 1_000).roundToInt(), 1_000)
        val intent = PendingIntent.getBroadcast(this, 73, Intent(ACTION_PIP_TOGGLE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val action = RemoteAction(
            android.graphics.drawable.Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (playing) "Pause" else "Play", if (playing) "Pause video" else "Play video", intent
        )
        return PictureInPictureParams.Builder().setAspectRatio(aspectRatio).setActions(listOf(action)).build()
    }

    fun updatePipAction() {
        if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode) setPictureInPictureParams(pipParams())
    }


    fun visiblePlayerView(view: PlayerView?) { visiblePlayerView = view }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            visiblePlayerView?.hideController()
        } else {
            applyFullscreenState()
        }
    }

    fun videoPlayerActive(value: Boolean) { videoPlayerActive = value }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && videoPlayerActive && player?.isPlaying == true && !isInPictureInPictureMode) pip()
    }

    fun immersive(enabled: Boolean) {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (enabled) View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else View.SYSTEM_UI_FLAG_VISIBLE
    }

    override fun onDestroy() { runCatching { unregisterReceiver(pipReceiver) }; player?.release(); super.onDestroy() }
}

private fun colorScheme(seed: ComposeColor, dark: Boolean, oled: Boolean): ColorScheme {
    fun mix(color: ComposeColor, target: ComposeColor, amount: Float) = ComposeColor(
        red = color.red + (target.red - color.red) * amount,
        green = color.green + (target.green - color.green) * amount,
        blue = color.blue + (target.blue - color.blue) * amount,
        alpha = 1f
    )
    val primary = seed
    val secondary = mix(seed, if (dark) ComposeColor.White else ComposeColor(0xFF203040), if (dark) .12f else .20f)
    val tertiary = mix(seed, ComposeColor(0xFFFF4081), .24f)
    return if (dark) darkColorScheme(
        primary = primary, onPrimary = ComposeColor.Black, primaryContainer = mix(seed, ComposeColor.Black, .48f),
        onPrimaryContainer = mix(seed, ComposeColor.White, .78f),
        secondary = secondary, onSecondary = ComposeColor.Black,
        secondaryContainer = mix(seed, ComposeColor.Black, .58f), onSecondaryContainer = mix(seed, ComposeColor.White, .84f),
        tertiary = tertiary, onTertiary = ComposeColor.Black,
        tertiaryContainer = mix(tertiary, ComposeColor.Black, .58f), onTertiaryContainer = mix(tertiary, ComposeColor.White, .84f),
        background = if (oled) ComposeColor.Black else ComposeColor(0xFF111318),
        surface = if (oled) ComposeColor.Black else ComposeColor(0xFF111318),
        surfaceVariant = if (oled) ComposeColor(0xFF121212) else mix(seed, ComposeColor(0xFF202124), .8f)
    ) else lightColorScheme(
        primary = primary, onPrimary = ComposeColor.White, primaryContainer = mix(seed, ComposeColor.White, .80f),
        onPrimaryContainer = mix(seed, ComposeColor.Black, .55f),
        secondary = secondary, onSecondary = ComposeColor.White,
        secondaryContainer = mix(seed, ComposeColor.White, .82f), onSecondaryContainer = mix(seed, ComposeColor.Black, .58f),
        tertiary = tertiary, onTertiary = ComposeColor.White,
        tertiaryContainer = mix(tertiary, ComposeColor.White, .82f), onTertiaryContainer = mix(tertiary, ComposeColor.Black, .58f),
        background = mix(seed, ComposeColor.White, .96f), surface = ComposeColor.White,
        surfaceVariant = mix(seed, ComposeColor.White, .88f)
    )
}

@Composable
private fun MaterialYtRoot(activity: MainActivity, onPlayer: (ExoPlayer) -> Unit) {
    val registerPlayer: (ExoPlayer) -> Unit = { p ->
        activity.registerCurrentPlayer(p)
        onPlayer(p)
    }
    val prefs = remember { activity.getSharedPreferences("settings", 0) }
    var theme by remember { mutableStateOf(runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)) }
    var color by remember { mutableStateOf(runCatching { AppColor.valueOf(prefs.getString("color", "PURPLE")!!) }.getOrDefault(AppColor.PURPLE)) }
    var codec by remember { mutableStateOf(runCatching { CodecChoice.valueOf(prefs.getString("codec", "H264")!!) }.getOrDefault(CodecChoice.H264)) }
    var audioCodec by remember { mutableStateOf(runCatching { AudioCodecChoice.valueOf(prefs.getString("audio_codec", "MP4A")!!) }.getOrDefault(AudioCodecChoice.MP4A)) }
    var quality by remember { mutableStateOf(runCatching { QualityChoice.valueOf(prefs.getString("quality", "AUTO")!!) }.getOrDefault(QualityChoice.AUTO)) }
    var videoDecoderMode by remember { mutableStateOf(runCatching { DecoderMode.valueOf(prefs.getString("video_decoder_mode", "AUTO")!!) }.getOrDefault(DecoderMode.AUTO)) }
    var audioDecoderMode by remember { mutableStateOf(runCatching { DecoderMode.valueOf(prefs.getString("audio_decoder_mode", "AUTO")!!) }.getOrDefault(DecoderMode.AUTO)) }
    PlaybackPreferences.videoDecoderMode = videoDecoderMode
    PlaybackPreferences.audioDecoderMode = audioDecoderMode
    
    val systemDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val dark = theme == ThemeMode.DARK || theme == ThemeMode.OLED || (theme == ThemeMode.SYSTEM && systemDark)
    val base = ComposeColor(color.seed)
    val scheme = colorScheme(base, dark, theme == ThemeMode.OLED)
    SideEffect {
        activity.window.statusBarColor = Color.BLACK
        activity.window.navigationBarColor = if (theme == ThemeMode.OLED) Color.BLACK else scheme.surface.value.toInt()
    }
    MaterialTheme(colorScheme = scheme) {
        AppScaffold(activity, theme, color, codec, audioCodec, quality, videoDecoderMode, audioDecoderMode, onTheme = { theme = it; prefs.edit().putString("theme", it.name).apply() },
            onColor = { color = it; prefs.edit().putString("color", it.name).apply() },
            onCodec = { codec = it; prefs.edit().putString("codec", it.name).apply() },
            onAudioCodec = { audioCodec = it; prefs.edit().putString("audio_codec", it.name).apply() },
            onQuality = { quality = it; prefs.edit().putString("quality", it.name).apply() },
            onVideoDecoderMode = { videoDecoderMode = it; PlaybackPreferences.videoDecoderMode = it; prefs.edit().putString("video_decoder_mode", it.name).apply() }, onAudioDecoderMode = { audioDecoderMode = it; PlaybackPreferences.audioDecoderMode = it; prefs.edit().putString("audio_decoder_mode", it.name).apply() }, registerPlayer)
    }
}

@Composable
private fun AppScaffold(
    activity: MainActivity, theme: ThemeMode, color: AppColor, codec: CodecChoice, audioCodec: AudioCodecChoice, quality: QualityChoice, videoDecoderMode: DecoderMode, audioDecoderMode: DecoderMode,
    onTheme: (ThemeMode) -> Unit, onColor: (AppColor) -> Unit, onCodec: (CodecChoice) -> Unit, onAudioCodec: (AudioCodecChoice) -> Unit, onQuality: (QualityChoice) -> Unit,
    onVideoDecoderMode: (DecoderMode) -> Unit, onAudioDecoderMode: (DecoderMode) -> Unit,
    onPlayer: (ExoPlayer) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var reopenMusicPlayer by remember { mutableIntStateOf(0) }
    var authenticated by remember { mutableStateOf(YouTubeRepository.signedIn()) }
    var playerMode by remember { mutableStateOf(false) }
    val hideNavigation = playerMode || activity.isFullscreen
    val tabs = buildList {
        add("Home" to Icons.Default.OndemandVideo)
        add("Shorts" to Icons.Default.SmartDisplay)
        add("YT Music" to Icons.Default.Album)
        if (authenticated) add("History" to Icons.Default.History)
        add("Account" to Icons.Default.AccountCircle)
    }
    val content: @Composable (PaddingValues) -> Unit = { padding ->
        val effectivePadding = if (activity.isFullscreen) PaddingValues(0.dp) else padding
        Box(Modifier.padding(effectivePadding).fillMaxSize()) {
            when (tabs.getOrNull(tab)?.first) {
                "Home" -> HomeScreen(activity, codec, audioCodec, quality, onPlayer, { playerMode = it }) { tab = tabs.indexOfFirst { it.first == "Account" } }
                "Shorts" -> ShortsScreen(activity, codec, audioCodec, quality, onPlayer) { playerMode = it }
                "YT Music" -> MusicScreen(activity, audioCodec, onPlayer, reopenMusicPlayer) { playerMode = it }
                "History" -> NativeFeedScreen(activity, "History", Icons.Default.History, codec, audioCodec, quality, onPlayer, { playerMode = it }, loader = { YouTubeRepository.history() })
                else -> AccountScreen(activity, theme, color, codec, audioCodec, quality, videoDecoderMode, audioDecoderMode, onTheme, onColor, onCodec, onAudioCodec, onQuality, onVideoDecoderMode, onAudioDecoderMode, onPlayer) { authenticated = it; tab = tabs.indexOfFirst { entry -> entry.first == "Account" } }
            }
        }
    }
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    if (tablet) {
        Row(Modifier.fillMaxSize()) {
            if (!hideNavigation) NavigationRail {
                Spacer(Modifier.weight(1f))
                tabs.forEachIndexed { index, item -> NavigationRailItem(selected = tab == index, onClick = { playerMode = false; tab = index }, icon = { Icon(item.second, null) }, label = { Text(item.first) }) }
                Spacer(Modifier.weight(1f))
            }
            Scaffold(Modifier.weight(1f), bottomBar = { if (!hideNavigation) MiniPlayer { tab = tabs.indexOfFirst { it.first == "YT Music" }; reopenMusicPlayer++ } }, content = content)
        }
    } else {
        Scaffold(bottomBar = {
            if (!hideNavigation) Column { MiniPlayer { tab = tabs.indexOfFirst { it.first == "YT Music" }; reopenMusicPlayer++ }; NavigationBar { tabs.forEachIndexed { index, item -> NavigationBarItem(selected = tab == index, onClick = { playerMode = false; tab = index }, icon = { Icon(item.second, null) }, label = { Text(item.first, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, softWrap = false) }) } } }
        }, content = content)
    }
}

@Composable
private fun MiniPlayer(openMusic: () -> Unit) {
    val track = AutoMusicService.nowPlaying.value ?: return
    var downwardDrag by remember { mutableFloatStateOf(0f) }
    val animatedOffset by animateFloatAsState(
        targetValue = downwardDrag.coerceAtLeast(0f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "miniplayer_drag"
    )
    val alpha = (1f - (animatedOffset / 100f)).coerceIn(0f, 1f)

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()
    ) {
        Surface(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, animatedOffset.roundToInt()) }
                .graphicsLayer { this.alpha = alpha }
                .height(66.dp)
                .pointerInput(track.id) {
                    detectVerticalDragGestures(
                        onDragStart = { downwardDrag = 0f },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            downwardDrag += amount
                        },
                        onDragEnd = {
                            if (downwardDrag > 48f) AutoMusicService.close()
                            downwardDrag = 0f
                        },
                        onDragCancel = { downwardDrag = 0f }
                    )
                }
                .clickable(onClick = openMusic),
            tonalElevation = 6.dp
        ) {
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(track.thumbnail, null, Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                Column(Modifier.padding(horizontal = 10.dp).weight(1f)) {
                    Text(track.title, Modifier.autoMarquee(), maxLines = 1, softWrap = false, style = MaterialTheme.typography.titleSmall)
                    Text(track.subtitle, Modifier.autoMarquee(), maxLines = 1, softWrap = false, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { AutoMusicService.toggle() }) {
                    Icon(if (AutoMusicService.playing.value) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause")
                }
                IconButton(onClick = { AutoMusicService.close() }) {
                    Icon(Icons.Default.Close, "Close")
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(activity: MainActivity, codec: CodecChoice, audioCodec: AudioCodecChoice = AudioCodecChoice.MP4A, quality: QualityChoice, onPlayer: (ExoPlayer) -> Unit, onPlayerMode: (Boolean) -> Unit, openAccount: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<InfoItem>>(emptyList()) }
    var homeFeed by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var feed by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var selectedChannel by remember { mutableStateOf<String?>(null) }
    var selectedResult by remember { mutableStateOf<InfoItem?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    val homeListState = rememberLazyListState()
    val homeGridState = rememberLazyGridState()
    fun scrollHomeToTop() = scope.launch {
        homeListState.scrollToItem(0)
        homeGridState.scrollToItem(0)
    }
    LaunchedEffect(selected) { onPlayerMode(selected != null) }
    // Render selected video on top of feed so miniplayer has feed content underneath
    val videoOverlay: @Composable () -> Unit = {
        if (selected != null) {
            VideoScreen(activity, selected!!, codec, audioCodec, quality, onPlayer, onPlayerMode = onPlayerMode, onBack = {
                selected = null
                onPlayerMode(false)
            })
        }
    }
    if (selectedPlaylist != null) { PlaylistScreen(activity, selectedPlaylist!!, codec, audioCodec, quality, onPlayer, onPlayerMode = onPlayerMode) { selectedPlaylist = null }; return }
    if (selectedChannel != null) { ChannelScreen(activity, selectedChannel!!, codec, audioCodec, quality, onPlayer) { selectedChannel = null }; return }
    selectedResult?.let { item ->
        when (item.infoType) {
            InfoItem.InfoType.CHANNEL -> ChannelScreen(activity, item.url, codec, audioCodec, quality, onPlayer) { selectedResult = null }
            InfoItem.InfoType.PLAYLIST -> PlaylistScreen(activity, item.url, codec, audioCodec, quality, onPlayer, onPlayerMode = onPlayerMode) { selectedResult = null }
            else -> Unit
        }
        return
    }
    LaunchedEffect(Unit) {
        if (YouTubeRepository.signedIn() && homeFeed.isEmpty()) {
            loading = true
            runCatching { withContext(Dispatchers.IO) { YouTubeRepository.home() } }.onSuccess { homeFeed = it; if (query.isBlank()) feed = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }
            loading = false
        }
    }
    fun loadMore() {
        if (loadingMore || feed.isEmpty()) return
        loadingMore = true
        scope.launch {
            val more = runCatching { withContext(Dispatchers.IO) {
                if (query.isBlank()) YouTubeRepository.homeContinuation() else YouTubeRepository.searchContinuation(query)
            } }.getOrDefault(emptyList())
            if (more.isNotEmpty()) {
                feed = (feed + more).distinctBy { it.id }
                if (query.isBlank()) homeFeed = feed
            }
            loadingMore = false
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search YouTube") }, shape = CircleShape, leadingIcon = {
                IconButton(onClick = {
                    scrollHomeToTop()
                    query = ""
                    results = emptyList()
                    error = null
                    feed = homeFeed
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }) { Icon(Icons.Default.Close, "Exit search") }
            }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); keyboardController?.hide(); if (query.isNotBlank()) { scrollHomeToTop(); scope.launch { loading = true; error = null; runCatching { withContext(Dispatchers.IO) { YouTubeRepository.search(query) } }.onSuccess { feed = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message ?: "Search failed" }; loading = false } } }))
            IconButton(onClick = {
                focusManager.clearFocus()
                keyboardController?.hide()
                if (query.isNotBlank()) { scrollHomeToTop(); scope.launch {
                    loading = true; error = null
                    runCatching { withContext(Dispatchers.IO) { YouTubeRepository.search(query) } }
                        .onSuccess { feed = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message ?: "Search failed" }
                    loading = false
                } }
            }) { Icon(Icons.Default.Search, "Search") }
            IconButton(onClick = {
                query = ""
                focusManager.clearFocus()
                keyboardController?.hide()
                scope.launch {
                    loading = true; error = null; results = emptyList()
                    runCatching { withContext(Dispatchers.IO) { YouTubeRepository.home() } }
                        .onSuccess { homeFeed = it; feed = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }
                    loading = false
                }
            }) { Icon(Icons.Default.Refresh, "Refresh") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (!loading && results.isEmpty() && feed.isEmpty()) {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Icon(Icons.Default.PlayCircle, null, Modifier.size(68.dp), tint = MaterialTheme.colorScheme.primary)
                Text(if (YouTubeRepository.signedIn()) "Your feed is currently empty" else "Search YouTube", Modifier.padding(16.dp))
            }
        } else {
            val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
            if (tablet) {
                LaunchedEffect(homeGridState, feed.size, query) {
                    snapshotFlow { homeGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                        if (feed.isNotEmpty() && last >= feed.lastIndex - 3) loadMore()
                    }
                }
                LazyVerticalGrid(GridCells.Adaptive(320.dp), state = homeGridState, contentPadding = PaddingValues(16.dp)) {
                    if (results.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("Channels and playlists", Modifier.padding(bottom = 16.dp), style = MaterialTheme.typography.titleLarge) }
                    gridItems(results, span = { GridItemSpan(maxLineSpan) }) { item -> ResultRow(item) { selectedResult = item } }
                    if (feed.isNotEmpty() && results.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("Videos", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.titleLarge) }
                    gridItems(feed) { item -> Box(Modifier.padding(8.dp)) { FeedRow(item) { when { item.channel -> selectedChannel = item.url; item.playlist -> selectedPlaylist = item.url; else -> selected = item.url } } } }
                    if (loadingMore) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                }
            } else {
                LaunchedEffect(homeListState, feed.size, query) {
                    snapshotFlow { homeListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                        if (feed.isNotEmpty() && last >= feed.lastIndex - 3) loadMore()
                    }
                }
                LazyColumn(state = homeListState) {
                    if (results.isNotEmpty()) item { Text("Channels and playlists", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge) }
                    items(results) { item -> ResultRow(item) { selectedResult = item } }
                    if (feed.isNotEmpty() && results.isNotEmpty()) item { Text("Videos", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge) }
                    items(feed) { item -> FeedRow(item) { when { item.channel -> selectedChannel = item.url; item.playlist -> selectedPlaylist = item.url; else -> selected = item.url } } }
                    if (loadingMore) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                }
            }
        }
        }
        videoOverlay()
    }
}

@Composable
private fun NativeFeedScreen(activity: MainActivity, title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, codec: CodecChoice, audioCodec: AudioCodecChoice = AudioCodecChoice.MP4A, quality: QualityChoice, onPlayer: (ExoPlayer) -> Unit, onPlayerMode: (Boolean) -> Unit, onBack: (() -> Unit)? = null, loader: () -> List<FeedItem>) {
    var feed by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var selectedChannel by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(selected) { onPlayerMode(selected != null) }
    val videoOverlay: @Composable () -> Unit = { if (selected != null) VideoScreen(activity, selected!!, codec, audioCodec, quality, onPlayer, onPlayerMode = onPlayerMode, onBack = { selected = null; onPlayerMode(false) }) }
    if (selectedPlaylist != null) { PlaylistScreen(activity, selectedPlaylist!!, codec, audioCodec, quality, onPlayer, onPlayerMode = onPlayerMode) { selectedPlaylist = null }; return }
    if (selectedChannel != null) { ChannelScreen(activity, selectedChannel!!, codec, audioCodec, quality, onPlayer) { selectedChannel = null }; return }
    LaunchedEffect(refreshKey) { loading = true; error = null; runCatching { withContext(Dispatchers.IO) { loader() } }.onSuccess { feed = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }; loading = false }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } else Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium); IconButton(onClick = { refreshKey++ }) { Icon(Icons.Default.Refresh, "Refresh") } }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (!loading && !YouTubeRepository.signedIn()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Sign in from Account to load your $title recommendations.") }
        val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
        if (tablet) {
            LazyVerticalGrid(GridCells.Adaptive(320.dp), contentPadding = PaddingValues(8.dp)) {
                gridItems(feed) { item -> Box(Modifier.padding(8.dp)) { FeedRow(item) { when { item.channel -> selectedChannel = item.url; item.playlist -> selectedPlaylist = item.url; else -> selected = item.url } } } }
            }
        } else {
            LazyColumn { items(feed) { item -> FeedRow(item) { when { item.channel -> selectedChannel = item.url; item.playlist -> selectedPlaylist = item.url; else -> selected = item.url } } } }
        }
        }
        videoOverlay()
    }
}

@Composable
private fun MusicScreen(activity: MainActivity, audioCodec: AudioCodecChoice, onPlayer: (ExoPlayer) -> Unit, reopenPlayerRequest: Int, onPlayerMode: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var homeTracks by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var selected by remember { mutableStateOf<FeedItem?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val musicListState = rememberLazyListState()
    val musicGridState = rememberLazyGridState()
    fun scrollMusicToTop() = scope.launch {
        musicListState.scrollToItem(0)
        musicGridState.scrollToItem(0)
    }
    LaunchedEffect(reopenPlayerRequest) {
        if (reopenPlayerRequest > 0) selected = AutoMusicService.nowPlaying.value
    }
    LaunchedEffect(selected) { onPlayerMode(selected != null) }
    fun load(block: () -> List<FeedItem>) { scope.launch { loading = true; error = null; runCatching { withContext(Dispatchers.IO) { block() } }.onSuccess { tracks = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }; loading = false } }
    LaunchedEffect(Unit) { if (YouTubeRepository.signedIn()) { runCatching { withContext(Dispatchers.IO) { YouTubeRepository.music() } }.onSuccess { homeTracks = it; tracks = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }; loading = false } else loading = false }
    fun loadMore() {
        if (query.isBlank() || loadingMore || tracks.isEmpty()) return
        loadingMore = true
        scope.launch {
            val more = runCatching { withContext(Dispatchers.IO) { YouTubeRepository.musicContinuation(query) } }.getOrDefault(emptyList())
            if (more.isNotEmpty()) {
                tracks = (tracks + more).distinctBy { it.id }
                if (query.isBlank()) homeTracks = tracks
            }
            loadingMore = false
        }
    }
    if (selected != null) { MusicPlayer(activity, selected!!, audioCodec) { selected = null }; return }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search music") }, shape = CircleShape, leadingIcon = {
                IconButton(onClick = {
                    scrollMusicToTop()
                    query = ""
                    error = null
                    tracks = homeTracks
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }) { Icon(Icons.Default.Close, "Exit search") }
            }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); keyboardController?.hide(); if (query.isNotBlank()) { scrollMusicToTop(); load { YouTubeRepository.musicSearch(query) } } }))
            IconButton(onClick = { if (query.isNotBlank()) { scrollMusicToTop(); load { YouTubeRepository.musicSearch(query) } } }) { Icon(Icons.Default.Search, "Search music") }
            IconButton(onClick = {
                query = ""
                scope.launch {
                    loading = true; error = null
                    runCatching { withContext(Dispatchers.IO) { YouTubeRepository.music() } }
                        .onSuccess { homeTracks = it; tracks = it }
                        .onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }
                    loading = false
                }
            }) { Icon(Icons.Default.Refresh, "Refresh") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
        if (tablet) {
            LaunchedEffect(musicGridState, tracks.size, query) {
                snapshotFlow { musicGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                    if (query.isNotBlank() && tracks.isNotEmpty() && last >= tracks.lastIndex - 3) loadMore()
                }
            }
            LazyVerticalGrid(
                GridCells.Adaptive(minSize = 300.dp),
                state = musicGridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                gridItems(tracks) { track -> FeedRow(track) { selected = track } }
                if (loadingMore) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            }
        } else {
            LaunchedEffect(musicListState, tracks.size, query) {
                snapshotFlow { musicListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                    if (query.isNotBlank() && tracks.isNotEmpty() && last >= tracks.lastIndex - 3) loadMore()
                }
            }
            LazyColumn(state = musicListState) {
                items(tracks) { track -> FeedRow(track) { selected = track } }
                if (loadingMore) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            }
        }
    }
}

@Composable
private fun MusicPlayer(activity: MainActivity, track: FeedItem, preferredCodec: AudioCodecChoice, onBack: () -> Unit) {
    var showLyrics by remember { mutableStateOf(false) }
    
    var lyrics by remember { mutableStateOf<List<LyricLine>?>(null) }
    var lyricsError by remember { mutableStateOf<String?>(null) }
    
    val lyricsState = rememberLazyListState()
    val position = AutoMusicService.position.longValue
    val duration = AutoMusicService.duration.longValue
    val buffered = AutoMusicService.bufferedPosition.longValue
    val playing = AutoMusicService.playing.value
    val musicLoadStatus by PlaybackLoadStatus.message.collectAsState()
    val musicLoadMessage = musicLoadStatus?.takeIf { it.mediaId == track.id }?.text
    BackHandler { onBack() }
    DisposableEffect(Unit) {
        activity.immersive(true)
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.immersive(false)
        }
    }
    LaunchedEffect(track.id, preferredCodec) {
        if (AutoMusicService.nowPlaying.value?.id != track.id || (!AutoMusicService.playing.value && AutoMusicService.position.longValue == 0L)) {
            AutoMusicService.play(activity, track, preferredCodec)
        }
    }
    if ((showLyrics || LocalConfiguration.current.smallestScreenWidthDp >= 600) && lyrics == null && lyricsError == null) LaunchedEffect(track.id) {
        runCatching { withContext(Dispatchers.IO) { YouTubeRepository.lyrics(track.id) } }
            .onSuccess { lyrics = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) lyricsError = it.message ?: "Lyrics unavailable" }
    }
    val lines = lyrics.orEmpty()
    val timed = lines.any { it.startMs >= 0 }
    val currentLine = if (lines.isEmpty()) 0 else if (timed) lines.indexOfLast { it.startMs in 0..position }.coerceAtLeast(0)
        else if (duration > 0) ((position.toDouble() / duration) * lines.size).toInt().coerceIn(lines.indices) else 0
    LaunchedEffect(currentLine, showLyrics) { if (lines.isNotEmpty()) lyricsState.animateScrollToItem(currentLine, -300) }
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val backgroundArtwork = remember(track.thumbnail) {
        if (Build.VERSION.SDK_INT >= 31) track.thumbnail
        else ImageRequest.Builder(activity)
            .data(track.thumbnail)
            .transformations(LegacyBlurTransformation(activity, radius = 12f, sampling = 2f))
            .build()
    }
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            backgroundArtwork,
            null,
            if (Build.VERSION.SDK_INT >= 31) Modifier.matchParentSize().blur(22.dp) else Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
        )
        Box(Modifier.matchParentSize().background(ComposeColor.Black.copy(alpha = .64f)))
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().displayCutoutPadding().statusBarsPadding().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.Close, "Close player", tint = ComposeColor.White) }
                Text("Now playing", Modifier.weight(1f), color = ComposeColor.White, style = MaterialTheme.typography.titleLarge)
                
            }
            if (tablet) {
                Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Box(Modifier.sizeIn(maxWidth = 360.dp, maxHeight = 360.dp).aspectRatio(1f).clip(RoundedCornerShape(20.dp))) {
                            AsyncImage(track.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (musicLoadMessage != null) MusicPlaybackLoadingStatus(musicLoadMessage, Modifier.align(Alignment.TopCenter).padding(top = 18.dp))
                        }
                        Spacer(Modifier.height(18.dp)); Text(track.title, color = ComposeColor.White, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center); Text(track.subtitle, color = ComposeColor.White.copy(alpha = .72f), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(14.dp))
                        Canvas(Modifier.fillMaxWidth().height(36.dp).pointerInput(duration) { detectTapGestures { point -> if (duration > 0) AutoMusicService.seekTo((duration * (point.x / size.width)).toLong()) } }) {
                            val total = duration.coerceAtLeast(1L).toFloat(); val y = center.y; val stroke = 9.dp.toPx()
                            drawLine(ComposeColor.Gray.copy(alpha = .48f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
                            drawLine(ComposeColor.LightGray.copy(alpha = .78f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width * (buffered / total).coerceIn(0f, 1f), y), strokeWidth = stroke, cap = StrokeCap.Round)
                            drawLine(ComposeColor.White, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width * (position / total).coerceIn(0f, 1f), y), strokeWidth = stroke, cap = StrokeCap.Round)
                        }
                        Row(Modifier.fillMaxWidth()) { Text(formatTime(position), color = ComposeColor.White.copy(alpha = .75f)); Spacer(Modifier.weight(1f)); Text(formatTime(duration), color = ComposeColor.White.copy(alpha = .75f)) }
                        Spacer(Modifier.height(8.dp))
                        IconButton(onClick = { AutoMusicService.toggle() }, Modifier.size(76.dp)) { Icon(if (playing) Icons.Default.PauseCircle else Icons.Default.PlayCircle, "Play or pause", Modifier.fillMaxSize(), tint = ComposeColor.White) }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().background(ComposeColor.Black.copy(alpha = .28f), RoundedCornerShape(20.dp)).padding(16.dp)) {
                        Text("Lyrics", color = ComposeColor.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
                        LazyColumn(state = lyricsState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (lines.isEmpty()) item { Text(lyricsError ?: if (lyrics == null) "Loading lyrics…" else "Lyrics unavailable", color = ComposeColor.White.copy(alpha = .7f), style = MaterialTheme.typography.bodyLarge) }
                            else items(lines.size) { index -> Text(lines[index].text, Modifier.clickable(enabled = lines[index].startMs >= 0) { AutoMusicService.seekTo(lines[index].startMs) }, color = if (index == currentLine) ComposeColor.White else ComposeColor.White.copy(alpha = .48f), style = if (index == currentLine) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium) }
                        }
                    }
                }
            } else {
                Crossfade(showLyrics, label = "music page", modifier = Modifier.weight(1f)) { lyricsPage ->
                    if (lyricsPage) LazyColumn(state = lyricsState, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 220.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (lines.isEmpty()) item { Text(lyricsError ?: "Loading lyrics…", color = ComposeColor.White, style = MaterialTheme.typography.headlineSmall) }
                        else items(lines.size) { index -> Text(lines[index].text, Modifier.clickable(enabled = lines[index].startMs >= 0) { AutoMusicService.seekTo(lines[index].startMs) }, color = if (index == currentLine) ComposeColor.White else ComposeColor.White.copy(alpha = .48f), style = if (index == currentLine) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge) }
                    } else Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Box(Modifier.sizeIn(maxWidth = 320.dp, maxHeight = 320.dp).aspectRatio(1f).clip(RoundedCornerShape(20.dp))) {
                            AsyncImage(track.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (musicLoadMessage != null) MusicPlaybackLoadingStatus(musicLoadMessage, Modifier.align(Alignment.TopCenter).padding(top = 18.dp))
                        }
                        Spacer(Modifier.height(22.dp)); Text(track.title, color = ComposeColor.White, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center); Text(track.subtitle, color = ComposeColor.White.copy(alpha = .72f), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Canvas(Modifier.fillMaxWidth().height(36.dp).pointerInput(duration) { detectTapGestures { point -> if (duration > 0) AutoMusicService.seekTo((duration * (point.x / size.width)).toLong()) } }) {
                            val total = duration.coerceAtLeast(1L).toFloat(); val y = center.y; val stroke = 9.dp.toPx()
                            drawLine(ComposeColor.Gray.copy(alpha = .48f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
                            drawLine(ComposeColor.LightGray.copy(alpha = .78f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width * (buffered / total).coerceIn(0f, 1f), y), strokeWidth = stroke, cap = StrokeCap.Round)
                            drawLine(ComposeColor.White, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width * (position / total).coerceIn(0f, 1f), y), strokeWidth = stroke, cap = StrokeCap.Round)
                        }
                        Row(Modifier.fillMaxWidth()) { Text(formatTime(position), color = ComposeColor.White.copy(alpha = .75f)); Spacer(Modifier.weight(1f)); Text(formatTime(duration), color = ComposeColor.White.copy(alpha = .75f)) }
                        IconButton(onClick = { AutoMusicService.toggle() }, Modifier.size(82.dp)) { Icon(if (playing) Icons.Default.PauseCircle else Icons.Default.PlayCircle, "Play or pause", Modifier.fillMaxSize(), tint = ComposeColor.White) }
                    }
                }
                NavigationBar(containerColor = ComposeColor.Black.copy(alpha = .38f)) { NavigationBarItem(!showLyrics, { showLyrics = false }, { Icon(Icons.Default.GraphicEq, null) }, label = { Text("Player") }); NavigationBarItem(showLyrics, { showLyrics = true }, { Icon(Icons.Default.Lyrics, null) }, label = { Text("Lyrics") }) }
            }
        }
    }
}

@Composable
private fun MusicPlaybackLoadingStatus(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier.background(ComposeColor.Black, CircleShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = ComposeColor.White,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(9.dp))
        Text(message, color = ComposeColor.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun PlaybackLoadingStatus(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        modifier.background(ComposeColor.Black, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        color = ComposeColor.White,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun MusicPlayerLegacy(activity: MainActivity, track: FeedItem, onPlayer: (ExoPlayer) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var lyrics by remember { mutableStateOf<List<LyricLine>?>(null) }
    var showLyrics by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var bufferedPosition by remember { mutableLongStateOf(0L) }
    var mediaLoaded by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    val player = remember { PlayerFactory.bufferedPlayer(context).also(onPlayer) }
    val mediaSession = remember { MediaSessionCompat(context, "MaterialYT Player") }
    val lyricsState = rememberLazyListState()
    BackHandler { onBack() }
    DisposableEffect(Unit) {
        context.stopService(Intent(context, AutoMusicService::class.java))
        activity.immersive(true)
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = player.play()
            override fun onPause() = player.pause()
            override fun onStop() = player.stop()
            override fun onSeekTo(pos: Long) = player.seekTo(pos)
        })
        mediaSession.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track.id)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, track.thumbnail).build())
        mediaSession.isActive = true
        activity.showMusicNotification(mediaSession, track, false)
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value; activity.showMusicNotification(mediaSession, track, value); mediaSession.setPlaybackState(PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_STOP)
                .setState(if (value) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, player.currentPosition.coerceAtLeast(0L), if (value) 1f else 0f).build()) }
            override fun onPlayerError(cause: PlaybackException) { Log.e("MaterialYT", "music player ${cause.errorCodeName}", cause); error = "${cause.errorCodeName}: ${cause.cause?.message ?: cause.localizedMessage}" }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); activity.stopMusicNotification(); mediaSession.isActive = false; mediaSession.release(); activity.immersive(false); player.release() }
    }
    LaunchedEffect(track.id) {
        // Match the v1.2.8 video path: NewPipe exposes the full audio format set first.
        var audioStreams = if (PlaybackBackendPreferences.useNewPipe()) runCatching { withContext(Dispatchers.IO) { YouTubeRepository.newPipeStreamInfo(track.id) } }
            .getOrNull()?.audioStreams?.filter { it.content.startsWith("http") }?.map {
                val name = it.audioTrackName?.takeIf { n -> n.isNotBlank() } ?: it.audioLocale?.displayName?.takeIf { n -> n.isNotBlank() }.orEmpty()
                val original = it.audioTrackType?.name?.contains("ORIGINAL", true) == true || name.contains("original", true)
                PlayableStream(it.content, it.codec.orEmpty(), 0, 0, it.bitrate, true, false, original, name, it.audioTrackId.orEmpty())
            }.orEmpty() else emptyList()
        if (audioStreams.isEmpty() && PlaybackBackendPreferences.useWebWhenNewPipeFails()) {
            audioStreams = runCatching { withContext(Dispatchers.IO) { YouTubeRepository.playerStreams(track.id, music = true) } }
                .getOrNull()?.filter { it.audio }.orEmpty()
        }
        val audio = audioStreams.maxByOrNull { it.bitrate }
        if (audio != null) {
            val source = ProgressiveMediaSource.Factory(DefaultHttpDataSource.Factory().setDefaultRequestProperties(YouTubeRepository.mediaHeaders(audio.url)))
                .createMediaSource(MediaItem.fromUri(audio.url))
            player.setMediaSource(source); player.prepare(); player.playWhenReady = true; mediaLoaded = true
        } else if (error == null) error = "No playable audio stream was found"
    }
    LaunchedEffect(Unit) { while (true) { position = player.currentPosition.coerceAtLeast(0L); bufferedPosition = player.bufferedPosition.coerceAtLeast(position); mediaSession.setPlaybackState(PlaybackStateCompat.Builder()
        .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_STOP)
        .setState(if (player.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, position, if (player.isPlaying) 1f else 0f).build()); delay(500) } }
    LaunchedEffect(track.id) {
        var lastReported = 0L
        while (true) {
            delay(10_000)
            val current = player.currentPosition.coerceAtLeast(0L)
            if (player.isPlaying && current > lastReported) {
                runCatching { withContext(Dispatchers.IO) { YouTubeRepository.reportPlayback(track.id, lastReported, current) } }
                lastReported = current
            } else if (current < lastReported) lastReported = current
        }
    }
    var lyricsError by remember { mutableStateOf<String?>(null) }
    if (showLyrics && lyrics == null && lyricsError == null) LaunchedEffect(track.id) { runCatching { withContext(Dispatchers.IO) { YouTubeRepository.lyrics(track.id) } }.onSuccess { lyrics = it }.onFailure { lyricsError = it.message ?: "Lyrics unavailable" } }
    val lines = lyrics.orEmpty()
    val hasRealTiming = lines.any { it.startMs >= 0 }
    val currentLine = if (lines.isEmpty()) 0 else if (hasRealTiming) lines.indexOfLast { it.startMs in 0..position }.coerceAtLeast(0)
        else if (player.duration > 0) ((position.toDouble() / player.duration) * lines.size).toInt().coerceIn(lines.indices) else 0
    LaunchedEffect(currentLine, showLyrics) { if (showLyrics && lines.isNotEmpty()) lyricsState.animateScrollToItem(currentLine, -300) }
    Box(Modifier.fillMaxSize()) {
        AsyncImage(track.thumbnail, null, Modifier.matchParentSize().blur(42.dp), contentScale = ContentScale.Crop)
        Box(Modifier.matchParentSize().background(ComposeColor.Black.copy(alpha = .62f)))
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().displayCutoutPadding().statusBarsPadding().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.Close, "Close player", tint = ComposeColor.White) }
                Text("Now playing", Modifier.weight(1f), color = ComposeColor.White, style = MaterialTheme.typography.titleLarge)
            }
            Crossfade(showLyrics, label = "player page", modifier = Modifier.weight(1f)) { lyricsPage ->
                if (lyricsPage) {
                    LazyColumn(state = lyricsState, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 220.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (lines.isEmpty()) item { Text(lyricsError ?: "Loading lyrics…", color = ComposeColor.White, style = MaterialTheme.typography.headlineSmall) }
                        else items(lines.size) { index -> Text(lines[index].text, Modifier.clickable(enabled = lines[index].startMs >= 0) { player.seekTo(lines[index].startMs) }, color = if (index == currentLine) ComposeColor.White else ComposeColor.White.copy(alpha = .48f), style = if (index == currentLine) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge) }
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        AsyncImage(track.thumbnail, null, Modifier.fillMaxWidth().aspectRatio(track.thumbnailWidth.toFloat() / track.thumbnailHeight.coerceAtLeast(1)).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.height(24.dp)); Text(track.title, color = ComposeColor.White, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(track.subtitle, color = ComposeColor.White.copy(alpha = .72f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        AnimatedVisibility(error != null, enter = fadeIn(), exit = fadeOut()) { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
                        val duration = player.duration.takeIf { it > 0 } ?: 0L
                        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                                val total = duration.coerceAtLeast(1L).toFloat()
                                drawLine(ComposeColor.White.copy(alpha = .18f), androidx.compose.ui.geometry.Offset(0f, center.y), androidx.compose.ui.geometry.Offset(size.width, center.y), strokeWidth = size.height)
                                drawLine(ComposeColor.White.copy(alpha = .48f), androidx.compose.ui.geometry.Offset(0f, center.y), androidx.compose.ui.geometry.Offset(size.width * (bufferedPosition / total).coerceIn(0f, 1f), center.y), strokeWidth = size.height)
                            }
                            Slider(value = position.coerceAtMost(duration).toFloat(), onValueChange = { player.seekTo(it.toLong()) }, valueRange = 0f..duration.coerceAtLeast(1L).toFloat(), colors = SliderDefaults.colors(inactiveTrackColor = ComposeColor.Transparent))
                        }
                        Row(Modifier.fillMaxWidth()) { Text(formatTime(position), color = ComposeColor.White.copy(alpha = .75f), style = MaterialTheme.typography.labelSmall); Spacer(Modifier.weight(1f)); Text("Buffered ${formatTime(bufferedPosition)} • ${formatTime(duration)}", color = ComposeColor.White.copy(alpha = .75f), style = MaterialTheme.typography.labelSmall) }
                        IconButton(onClick = { if (isPlaying) player.pause() else player.play() }, Modifier.size(82.dp)) {
                            Icon(if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle, "Play or pause", Modifier.fillMaxSize(), tint = ComposeColor.White)
                        }
                    }
                }
            }
            NavigationBar(containerColor = ComposeColor.Black.copy(alpha = .38f)) { NavigationBarItem(!showLyrics, { showLyrics = false }, { Icon(Icons.Default.GraphicEq, null) }, label = { Text("Player") }); NavigationBarItem(showLyrics, { showLyrics = true }, { Icon(Icons.Default.Lyrics, null) }, label = { Text("Lyrics") }) }
        }
    }
}

private fun formatCount(count: Long): String = when {
    count < 0L -> ""
    count < 1_000L -> count.toString()
    count < 1_000_000L -> {
        val k = count / 1_000.0
        if (count % 1_000L == 0L || k >= 100.0) {
            "${count / 1_000L}K"
        } else {
            val formatted = String.format(java.util.Locale.US, "%.1f", k)
            if (formatted.endsWith(".0")) "${count / 1_000L}K" else "${formatted}K"
        }
    }
    count < 1_000_000_000L -> {
        val m = count / 1_000_000.0
        if (count % 1_000_000L == 0L || m >= 100.0) {
            "${count / 1_000_000L}M"
        } else {
            val formatted = String.format(java.util.Locale.US, "%.1f", m)
            if (formatted.endsWith(".0")) "${count / 1_000_000L}M" else "${formatted}M"
        }
    }
    else -> {
        val b = count / 1_000_000_000.0
        val formatted = String.format(java.util.Locale.US, "%.1f", b)
        if (formatted.endsWith(".0")) "${count / 1_000_000_000L}B" else "${formatted}B"
    }
}

@Composable
private fun ChannelRow(item: FeedItem, click: () -> Unit) {
    val avatar = item.channelThumbnail.ifBlank { item.thumbnail }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = click)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (avatar.isNotBlank()) {
            AsyncImage(
                model = avatar,
                contentDescription = null,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.padding(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(
            onClick = click,
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text("Channel", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun FeedRow(item: FeedItem, click: () -> Unit) {
    if (item.channel) {
        ChannelRow(item, click)
        return
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp).clickable(onClick = click)) {
        AsyncImage(item.thumbnail, null, Modifier.fillMaxWidth().aspectRatio(item.thumbnailWidth.toFloat() / item.thumbnailHeight.coerceAtLeast(1)).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop)
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            if (item.channelThumbnail.isNotBlank()) AsyncImage(item.channelThumbnail, null, Modifier.size(38.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            else Surface(Modifier.size(38.dp), CircleShape, color = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Default.Person, null, Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun YouTubeHeader(title: String, music: Boolean = false) {
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(32.dp), RoundedCornerShape(9.dp), color = ComposeColor(0xFFFF0033)) {
                Icon(if (music) Icons.Default.MusicNote else Icons.Default.PlayArrow, null, Modifier.padding(6.dp), tint = ComposeColor.White)
            }
            Text(title, Modifier.padding(start = 10.dp).weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton({}) { Icon(Icons.Default.Cast, "Cast") }
            IconButton({}) { Icon(Icons.Default.Notifications, "Notifications") }
            Surface(Modifier.size(30.dp), CircleShape, color = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Person, "Account", Modifier.padding(6.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun TopicChips(labels: List<String>) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(labels) { label ->
            SuggestionChip(onClick = {}, label = { Text(label) }, shape = RoundedCornerShape(8.dp))
        }
    }
}

@Composable
private fun ResultRow(item: InfoItem, showSubtitle: Boolean = true, click: () -> Unit) {
    val isChannel = item.infoType == InfoItem.InfoType.CHANNEL
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 16.dp, vertical = if (isChannel) 14.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
        val thumb = item.thumbnails.firstOrNull()?.url
        if (isChannel) {
            if (!thumb.isNullOrBlank()) {
                AsyncImage(thumb, null, Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentScale = ContentScale.Crop)
            } else {
                Surface(Modifier.size(72.dp), CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.Person, null, Modifier.padding(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        } else {
            AsyncImage(thumb, null, Modifier.size(132.dp, 76.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (showSubtitle) {
                Spacer(Modifier.height(2.dp))
                Text(item.infoType.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (isChannel) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = click, shape = CircleShape, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                Text("Channel", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun PlaylistScreen(activity: MainActivity, url: String, codec: CodecChoice, audioCodec: AudioCodecChoice = AudioCodecChoice.MP4A, quality: QualityChoice, onPlayer: (ExoPlayer) -> Unit, onPlayerMode: (Boolean) -> Unit = {}, onBack: () -> Unit) {
    var playlist by remember { mutableStateOf<org.schabi.newpipe.extractor.playlist.PlaylistInfo?>(null) }
    var playlistItems by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(url) {
        val id = (Regex("[?&]list=([^&]+)").find(url)?.groupValues?.get(1) ?: url.substringAfterLast('/')).removePrefix("VL")
        runCatching { withContext(Dispatchers.IO) { YouTubeRepository.playlistVideos(id) } }
            .onSuccess { playlistItems = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }
        loading = false
        runCatching { withContext(Dispatchers.IO) { org.schabi.newpipe.extractor.playlist.PlaylistInfo.getInfo("https://www.youtube.com/playlist?list=$id") } }
            .onSuccess { playlist = it }
    }
    Box(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }; Text("Playlist", style = MaterialTheme.typography.headlineMedium) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (!loading) LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                (playlist?.thumbnails?.lastOrNull()?.url ?: playlistItems.firstOrNull()?.thumbnail)?.let { AsyncImage(it, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(24.dp)), contentScale = ContentScale.Crop) }
                Text(playlist?.name ?: "YouTube playlist", style = MaterialTheme.typography.headlineMedium); playlist?.uploaderName?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("${playlistItems.size} videos loaded", style = MaterialTheme.typography.bodyMedium)
                if (playlistItems.isNotEmpty()) Button(onClick = { selectedIndex = 0 }, Modifier.fillMaxWidth()) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Play") }
            } }
            items(playlistItems.size) { index -> val item = playlistItems[index]; Row(Modifier.fillMaxWidth().clickable { selectedIndex = index }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text("${index + 1}", Modifier.width(34.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); AsyncImage(item.thumbnail, null, Modifier.size(128.dp, 72.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop); Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall); Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) } } }
        }
      }
      selectedIndex?.let { selected ->
        if (playlistItems.isNotEmpty()) {
            val index = selected.coerceIn(0, playlistItems.lastIndex)
            VideoScreen(activity, playlistItems[index].url, codec, audioCodec, quality, onPlayer, playlistItems, index, { selectedIndex = it }, onPlayerMode = onPlayerMode) {
                selectedIndex = null
                onPlayerMode(false)
            }
        }
      }
    }
}

@Composable
private fun ChannelScreen(activity: MainActivity, url: String, codec: CodecChoice, audioCodec: AudioCodecChoice = AudioCodecChoice.MP4A, quality: QualityChoice, onPlayer: (ExoPlayer) -> Unit, onBack: () -> Unit) {
    var channel by remember { mutableStateOf<org.schabi.newpipe.extractor.channel.ChannelInfo?>(null) }
    var channelItems by remember { mutableStateOf<List<InfoItem>>(emptyList()) }
    var posts by remember { mutableStateOf<List<CommunityPost>>(emptyList()) }
    var selectedTab by remember { mutableStateOf("videos") }
    var selectedVideo by remember { mutableStateOf<String?>(null) }
    var selectedShort by remember { mutableStateOf<FeedItem?>(null) }
    var tabHandler by remember { mutableStateOf<org.schabi.newpipe.extractor.linkhandler.ListLinkHandler?>(null) }
    var nextPage by remember { mutableStateOf<org.schabi.newpipe.extractor.Page?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler { when { selectedShort != null -> selectedShort = null; selectedVideo != null -> selectedVideo = null; else -> onBack() } }
    LaunchedEffect(url) { runCatching { withContext(Dispatchers.IO) { org.schabi.newpipe.extractor.channel.ChannelInfo.getInfo(url) } }
        .onSuccess { channel = it }.onFailure { error = it.message } }
    LaunchedEffect(channel, selectedTab) {
        val loaded = channel ?: return@LaunchedEffect
        error = null
        if (selectedTab == "posts") {
            runCatching { withContext(Dispatchers.IO) { YouTubeRepository.channelPosts(url) } }
                .onSuccess { posts = it }.onFailure { error = it.message }
        } else {
            channelItems = emptyList()
            nextPage = null
            val handler = loaded.tabs.firstOrNull { it.contentFilters.firstOrNull()?.equals(selectedTab, true) == true }
                ?: if (selectedTab == "videos") loaded.tabs.firstOrNull() else null
            tabHandler = handler
            runCatching { withContext(Dispatchers.IO) { handler?.let { org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo.getInfo(NewPipe.getService(0), it) } } }
                .onSuccess { info -> channelItems = info?.relatedItems.orEmpty(); nextPage = info?.nextPage }
                .onFailure { error = it.message }
        }
    }
    fun loadMore() {
        val handler = tabHandler ?: return
        val page = nextPage?.takeIf { org.schabi.newpipe.extractor.Page.isValid(it) } ?: return
        if (loadingMore || selectedTab == "posts") return
        loadingMore = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo.getMoreItems(NewPipe.getService(0), handler, page) } }
                .onSuccess { result -> channelItems = (channelItems + result.items).distinctBy { it.url }; nextPage = result.nextPage }
                .onFailure { error = it.message }
            loadingMore = false
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(listState, channelItems.size, selectedTab) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= listState.layoutInfo.totalItemsCount - 4) loadMore()
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } } }
        if (channel == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { message -> item { Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
        channel?.let { data ->
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    data.avatars.lastOrNull()?.url?.let { AsyncImage(it, null, Modifier.size(104.dp).clip(CircleShape), contentScale = ContentScale.Crop) }
                    Text(data.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    if (data.subscriberCount >= 0) Text("${formatCount(data.subscriberCount)} subscribers", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(data.description.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selectedTab == "videos", { selectedTab = "videos" }, { Text("Videos") }) }
                    item { FilterChip(selectedTab == "shorts", { selectedTab = "shorts" }, { Text("Shorts") }) }
                    item { FilterChip(selectedTab == "posts", { selectedTab = "posts" }, { Text("Posts") }) }
                }
            }
            if (selectedTab == "posts") {
                if (posts.isEmpty() && error == null) item { Text("No community posts were found.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(posts, key = { it.id }) { post ->
                    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(post.text, style = MaterialTheme.typography.bodyLarge)
                            if (post.image.isNotBlank()) AsyncImage(post.image, null, Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Fit)
                            Text(listOf(post.published, post.likes, post.comments).joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            CommunityCommentsButton(post)
                        }
                    }
                }
            } else {
                items(channelItems) { item -> ResultRow(item, showSubtitle = false) {
                    if (item.infoType == InfoItem.InfoType.STREAM) {
                        if (selectedTab == "shorts") {
                            val id = item.url.substringAfterLast('/').substringBefore('?').takeIf { it.length == 11 }
                                ?: Regex("[?&]v=([^&]+)").find(item.url)?.groupValues?.getOrNull(1)
                            if (id != null) selectedShort = FeedItem(
                                id, item.name, channel?.name.orEmpty(), item.thumbnails.firstOrNull()?.url.orEmpty(),
                                9, 16, channel?.avatars?.lastOrNull()?.url.orEmpty(), "https://www.youtube.com/shorts/$id",
                                channelUrl = url,
                            )
                        } else selectedVideo = item.url
                    }
                } }
                if (loadingMore) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            }
        }
    }
    selectedVideo?.let { selected -> VideoScreen(activity, selected, codec, audioCodec, quality, onPlayer) { selectedVideo = null } }
    selectedShort?.let { selected ->
        Box(Modifier.fillMaxSize().background(ComposeColor.Black)) {
            ShortPlayer(activity, selected, true, codec, audioCodec, quality, onPlayer) { }
        }
    }
    }
}

@Composable
private fun CommunityCommentsButton(post: CommunityPost) {
    var expanded by remember(post.id) { mutableStateOf(false) }
    var loading by remember(post.id) { mutableStateOf(false) }
    var loaded by remember(post.id) { mutableStateOf(false) }
    var comments by remember(post.id) { mutableStateOf<List<CommunityComment>>(emptyList()) }
    var error by remember(post.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    TextButton(onClick = {
        expanded = !expanded
        if (expanded && !loaded && !loading) {
            loading = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { YouTubeRepository.communityPostComments(post.id) } }
                    .onSuccess { comments = it; loaded = true }
                    .onFailure { error = it.message }
                loading = false
            }
        }
    }) { Icon(Icons.Default.Comment, null); Spacer(Modifier.width(8.dp)); Text(if (expanded) "Hide comments" else "View comments") }
    if (expanded) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (loaded && comments.isEmpty()) Text("No comments were returned.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        comments.forEach { comment ->
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp)) {
                Text(comment.author, style = MaterialTheme.typography.labelLarge)
                Text(comment.text, style = MaterialTheme.typography.bodyMedium)
                Text(listOf(comment.published, comment.likes).filter { it.isNotBlank() }.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ShortsScreen(activity: MainActivity, codec: CodecChoice, audioCodec: AudioCodecChoice, quality: QualityChoice, onPlayer: (ExoPlayer) -> Unit, onPlayerMode: (Boolean) -> Unit) {
    var shorts by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var channelUrl by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    if (channelUrl != null) {
        ChannelScreen(activity, channelUrl!!, codec, audioCodec, quality, onPlayer) { channelUrl = null }
        return
    }
    LaunchedEffect(Unit) {
        onPlayerMode(false)
        runCatching { withContext(Dispatchers.IO) { YouTubeRepository.shorts() } }
            .onSuccess { shorts = it; if (it.isEmpty()) error = "YouTube did not return Shorts" }
            .onFailure { error = it.message }
    }
    if (shorts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error == null) CircularProgressIndicator() else Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
        }
        return
    }
    val pager = rememberPagerState(pageCount = { shorts.size })
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage to shorts.size }.collect { (page, _) ->
            if (page < shorts.lastIndex - 5 || loadingMore) return@collect
            loadingMore = true
            var attempts = 0
            while (pager.currentPage >= shorts.lastIndex - 5 && attempts < 4) {
                val before = shorts.size
                val more = runCatching { withContext(Dispatchers.IO) { YouTubeRepository.shortsContinuation() } }
                    .onFailure { AppLog.failure("shorts UI continuation", it) }
                    .getOrDefault(emptyList())
                val wasAtEnd = pager.currentPage == before - 1
                if (more.isNotEmpty()) shorts = (shorts + more).distinctBy { it.id }
                if (shorts.size > before) {
                    attempts = 0
                    if (wasAtEnd) {
                        pager.animateScrollToPage(before)
                    }
                } else {
                    attempts++
                    delay(750L * attempts)
                }
            }
            loadingMore = false
        }
    }
    VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
        ShortPlayer(activity, shorts[page], page == pager.currentPage, codec, audioCodec, quality, onPlayer,
            waitingForNext = loadingMore && page == pager.currentPage && page == shorts.lastIndex) { channelUrl = it }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShortPlayer(activity: MainActivity, item: FeedItem, active: Boolean, codec: CodecChoice, audioCodec: AudioCodecChoice, quality: QualityChoice, onPlayer: (ExoPlayer) -> Unit, waitingForNext: Boolean = false, onOpenChannel: (String) -> Unit) {
    val context = LocalContext.current
    val player = remember(item.id) { PlayerFactory.bufferedPlayer(context).apply { repeatMode = Player.REPEAT_MODE_ONE }.also(onPlayer) }
    var streams by remember(item.id) { mutableStateOf<List<PlayerChoice>>(emptyList()) }
    var selected by remember(item.id) { mutableStateOf<PlayerChoice?>(null) }
    var audio by remember(item.id) { mutableStateOf<PlayerChoice?>(null) }
    var audioTracks by remember(item.id) { mutableStateOf<List<PlayerChoice>>(emptyList()) }
    var sabrPlayback by remember(item.id) { mutableStateOf<SabrPlaybackInfo?>(null) }
    var preferredAudioCodec by remember(item.id, audioCodec) { mutableStateOf(audioCodec) }
    var shortInfo by remember(item.id) { mutableStateOf<StreamInfo?>(null) }
    var shortMetadata by remember(item.id) { mutableStateOf<VideoDetails?>(YouTubeRepository.videoDetails(item.id)) }
    var speed by remember { mutableFloatStateOf(1f) }
    var expandedTitle by remember { mutableStateOf(false) }
    
    var showComments by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var comments by remember(item.id) { mutableStateOf<List<CommentsInfoItem>>(emptyList()) }
    var commentsLoading by remember { mutableStateOf(false) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var hasStartedPlayback by remember(item.id) { mutableStateOf(false) }
    var shortLoadMessage by remember(item.id) { mutableStateOf<String?>(null) }
    var showPlayPauseIndicator by remember { mutableStateOf(false) }
    var lastActionWasPlay by remember { mutableStateOf(false) }
    var indicatorKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(item.id) {
        PlaybackLoadStatus.message.collect { status ->
            if (!hasStartedPlayback && status?.mediaId == item.id) shortLoadMessage = status.text
        }
    }

    fun play(stream: PlayerChoice) {
        val sabr = sabrPlayback
        val source = if (sabr != null && stream.sabrFormat != null && audio?.sabrFormat != null) {
            SabrMediaFactory.create(sabr, stream.sabrFormat, audio!!.sabrFormat!!,
                sabrDataSourceFactory(sabr.serverAbrStreamingUrl))
        } else {
            val video = ProgressiveMediaSource.Factory(videoDataSourceFactory(stream.url)).createMediaSource(MediaItem.fromUri(stream.url))
            if (stream.videoOnly && audio != null) MergingMediaSource(true, video,
                ProgressiveMediaSource.Factory(videoDataSourceFactory(audio!!.url)).createMediaSource(MediaItem.fromUri(audio!!.url))) else video
        }
        player.setMediaSource(source); player.prepare(); player.setPlaybackSpeed(speed); player.playWhenReady = active
        selected = stream
    }
    LaunchedEffect(item.id, active, codec, preferredAudioCodec, quality) {
        if (!active) {
            player.stop()
            player.clearMediaItems()
            return@LaunchedEffect
        }
        streams = emptyList(); audioTracks = emptyList(); selected = null; audio = null; sabrPlayback = null; error = null
        val infoResult = if (PlaybackBackendPreferences.useNewPipe()) runCatching { withContext(Dispatchers.IO) { YouTubeRepository.newPipeStreamInfo(item.id) } }
            else Result.failure(IllegalStateException("NewPipe disabled by playback backend setting"))
        infoResult.onSuccess { 
            shortInfo = it 
            audioTracks = it.audioStreams.filter { track -> track.content.startsWith("http") }.map { track ->
                val trackName = track.audioTrackName?.takeIf { n -> n.isNotBlank() } ?: track.audioLocale?.displayName?.takeIf { n -> n.isNotBlank() }.orEmpty()
                val original = track.audioTrackType?.name?.contains("ORIGINAL", true) == true || trackName.contains("original", true)
                PlayerChoice(track.content, track.codec.orEmpty(), 0, 0, track.bitrate, false, trackName, track.audioTrackId.orEmpty(), original)
            }
            audio = selectAudioStream(audioTracks, preferredAudioCodec)
            streams = (it.videoOnlyStreams + it.videoStreams).filter { v -> v.content.startsWith("http") }
                .map { v -> PlayerChoice(v.content, v.codec.orEmpty(), v.height, v.fps, v.bitrate, v.isVideoOnly) }
                .distinctBy { "${it.height}-${it.fps}-${it.codec}" }.sortedByDescending { s -> s.height }
            selectVideoStream(streams, codec, quality)?.let { stream -> play(stream); error = null }
            AppLog.event("shorts NewPipe primary video=${item.id} videoStreams=${streams.size} audioStreams=${audioTracks.size}")
        }.onFailure { AppLog.failure("shorts NewPipe primary video=${item.id}", it) }
        if (streams.isEmpty() && PlaybackBackendPreferences.useWebWhenNewPipeFails()) {
            runCatching { withContext(Dispatchers.IO) { YouTubeRepository.sabrPlaybackInfo(item.id) } }.onSuccess { playback ->
                sabrPlayback = playback
                audioTracks = playback.formats.filter { it.audio }.map {
                    PlayerChoice(playback.serverAbrStreamingUrl, it.codec, 0, 0, it.bitrate,
                        false, it.audioTrackName, it.audioTrackId, it.originalAudio, it)
                }
                audio = selectAudioStream(audioTracks, preferredAudioCodec)
                streams = playback.formats.filter { !it.audio && it.height > 0 }.map {
                    PlayerChoice(playback.serverAbrStreamingUrl, it.codec, it.height, it.fps,
                        it.bitrate, true, sabrFormat = it)
                }.distinctBy { "${it.height}-${it.fps}-${it.codec}" }.sortedByDescending { it.height }
                selectVideoStream(streams, codec, quality)?.let { stream -> error = null; play(stream) }
                shortMetadata = YouTubeRepository.videoDetails(item.id)
                AppLog.event("shorts SABR primary video=${item.id} videoStreams=${streams.size} audioStreams=${audioTracks.size}")
            }.onFailure { AppLog.failure("shorts SABR primary video=${item.id}", it) }
        }
        if (streams.isEmpty() && PlaybackBackendPreferences.useWebWhenNewPipeFails()) {
            val extracted = runCatching { withContext(Dispatchers.IO) { YouTubeRepository.playerStreams(item.id) } }
            extracted.onSuccess { result ->
                audioTracks = result.filter { it.audio }.map { PlayerChoice(it.url, it.codec, 0, 0, it.bitrate, false, it.audioTrackName, it.audioTrackId, it.originalAudio) }
                audio = selectAudioStream(audioTracks, preferredAudioCodec)
                streams = result.filter { !it.audio && it.height > 0 }.map { PlayerChoice(it.url, it.codec, it.height, it.fps, it.bitrate, it.videoOnly) }
                    .distinctBy { "${it.height}-${it.fps}-${it.codec}" }.sortedByDescending { it.height }
                selectVideoStream(streams, codec, quality)?.let { stream -> error = null; play(stream) }
            }.onFailure { if (streams.isEmpty()) error = it.message }
        }
    }
    LaunchedEffect(active, selected) {
        if (active && selected != null) {
            player.playWhenReady = true
            player.play()
        } else if (!active) player.pause()
    }
    LaunchedEffect(active, item.id) {
        if (!active) return@LaunchedEffect
        var last = 0L
        while (true) {
            delay(if (last == 0L) 1_500 else 10_000)
            val current = player.currentPosition.coerceAtLeast(0L)
            if (player.isPlaying && current > last) {
                runCatching { withContext(Dispatchers.IO) { YouTubeRepository.reportPlayback(item.id, last, current) } }
                last = current
            }
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                player.pause()
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (active) {
                    player.play()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        val playbackListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    hasStartedPlayback = true
                    shortLoadMessage = null
                    PlaybackLoadStatus.clear(item.id)
                }
                if (!isPlaying) {
                    val pos = player.currentPosition
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { YouTubeRepository.reportPlayback(item.id, maxOf(0, pos - 1000L), pos, paused = true) } }
                }
            }
            override fun onRenderedFirstFrame() {
                hasStartedPlayback = true
                shortLoadMessage = null
                PlaybackLoadStatus.clear(item.id)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    shortLoadMessage = null
                    PlaybackLoadStatus.clear(item.id)
                }
                else if (!hasStartedPlayback && state == Player.STATE_BUFFERING && PlaybackLoadStatus.message.value?.mediaId == item.id) {
                    PlaybackLoadStatus.show(item.id, "Buffering media…")
                }
                if (state == Player.STATE_ENDED) {
                    player.seekTo(0L)
                    player.play()
                }
            }
        }
        player.addListener(playbackListener)
        onDispose {
            PlaybackLoadStatus.clear(item.id)
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(playbackListener)
            player.pause()
            player.stop()
            player.release()
        }
    }

    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    Box(Modifier.fillMaxSize().background(ComposeColor.Black), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxHeight().padding(bottom = if (waitingForNext) 72.dp else 0.dp)
            .then(if (tablet) Modifier.widthIn(max = 480.dp).aspectRatio(9f / 16f) else Modifier.fillMaxWidth())) {
            AndroidView(factory = { PlayerView(it).apply {
                this.player = player; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS); hideController()
            } }, update = { it.useController = false; it.hideController() }, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().clickable {
                if (player.isPlaying) {
                    player.pause()
                    lastActionWasPlay = false
                } else {
                    player.play()
                    lastActionWasPlay = true
                }
                indicatorKey++
                showPlayPauseIndicator = true
            })
            LaunchedEffect(indicatorKey) {
                if (indicatorKey > 0) {
                    delay(650)
                    showPlayPauseIndicator = false
                }
            }
            AnimatedVisibility(
                visible = showPlayPauseIndicator,
                enter = scaleIn(initialScale = 0.5f) + fadeIn(),
                exit = scaleOut(targetScale = 1.3f) + fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    shape = CircleShape,
                    color = ComposeColor.Black.copy(alpha = 0.6f),
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (lastActionWasPlay) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = null,
                            tint = ComposeColor.White,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }
            }
            error?.let { Text(it, color = ComposeColor.White, modifier = Modifier.align(Alignment.Center).background(ComposeColor.Black.copy(alpha = .7f)).padding(16.dp)) }
            if (!hasStartedPlayback) shortLoadMessage?.let { message ->
                PlaybackLoadingStatus(message, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
            }
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                val target = item.channelUrl.ifBlank { shortInfo?.uploaderUrl.orEmpty() }
                                if (target.isNotBlank()) onOpenChannel(target)
                            }
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val shortAvatar = shortInfo?.uploaderAvatars?.lastOrNull()?.url ?: item.channelThumbnail
                        if (shortAvatar.isNotBlank()) AsyncImage(shortAvatar, null, Modifier.size(38.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(10.dp))
                        Text(shortInfo?.uploaderName?.takeIf { it.isNotBlank() } ?: item.subtitle.ifBlank { "YouTube" }, color = ComposeColor.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(shortInfo?.name?.takeIf { it.isNotBlank() } ?: item.title, color = ComposeColor.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { expandedTitle = true })
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { showDetails = true }) { Icon(Icons.Default.MoreVert, "Short details", tint = ComposeColor.White) }
                    IconButton(onClick = {
                        showComments = true; commentsLoading = true
                    }) { Icon(Icons.Default.Comment, "Comments", tint = ComposeColor.White) }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Playback settings", tint = ComposeColor.White) }
                }
            }
            if (expandedTitle) Box(Modifier.fillMaxSize().background(ComposeColor.Black.copy(alpha = .9f)).clickable { expandedTitle = false }.padding(24.dp)) {
                Text(shortInfo?.name?.takeIf { it.isNotBlank() } ?: item.title, color = ComposeColor.White, style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            }
        }
        if (waitingForNext) Row(
            Modifier.align(Alignment.BottomCenter).height(72.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(22.dp), color = ComposeColor.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Loading more Shorts…", color = ComposeColor.White, style = MaterialTheme.typography.labelLarge)
        }
    }
    if (showDetails) AlertDialog(
        onDismissRequest = { showDetails = false },
        confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Close") } },
        title = { Text("Short details") },
        text = {
            LaunchedEffect(item.id) {
                runCatching { withContext(Dispatchers.IO) { YouTubeRepository.refreshVideoDetails(item.id) } }
                    .onSuccess { shortMetadata = it }
                    .onFailure { AppLog.failure("shorts details video=${item.id}", it) }
            }
            val views = shortInfo?.viewCount?.takeIf { it >= 0 } ?: shortMetadata?.viewCount ?: -1L
            val likes = shortInfo?.likeCount?.takeIf { it >= 0 }?.let(::formatCount)
                ?: shortMetadata?.likeText?.takeIf { it.isNotBlank() } ?: "—"
            val published = shortInfo?.uploadDate?.localDateTime?.toLocalDate()?.let { date ->
                java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG).withLocale(java.util.Locale.getDefault()).format(date)
            } ?: shortInfo?.textualUploadDate?.takeIf { it.isNotBlank() }
                ?: shortMetadata?.publishedDate?.takeIf { it.isNotBlank() }
            val raw = shortInfo?.description?.content.orEmpty().ifBlank { shortMetadata?.description.orEmpty() }
            val description = if (Build.VERSION.SDK_INT >= 24) Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString() else @Suppress("DEPRECATION") Html.fromHtml(raw).toString()
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column { Text(if (views >= 0) formatCount(views) else "—", style = MaterialTheme.typography.titleLarge); Text("Views", style = MaterialTheme.typography.labelMedium) }
                    Column { Text(likes, style = MaterialTheme.typography.titleLarge); Text("Likes", style = MaterialTheme.typography.labelMedium) }
                }
                Spacer(Modifier.height(16.dp)); Text("Published", style = MaterialTheme.typography.labelMedium); Text(published ?: "Date unavailable", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider(Modifier.padding(vertical = 16.dp)); Text("Description", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(8.dp)); Text(description.ifBlank { "No description was provided." })
            }
        }
    )
    if (showComments) {
        LaunchedEffect(item.id) {
            runCatching { withContext(Dispatchers.IO) { CommentsInfo.getInfo(item.url)?.relatedItems ?: emptyList() } }
                .onSuccess { comments = it }.onFailure { error = it.message }
            commentsLoading = false
        }
        ModalBottomSheet(onDismissRequest = { showComments = false }) {
            Text("Comments", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
            if (commentsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) { items(comments) { comment -> CommentNode(comment) } }
        }
    }
    if (showSettings) AlertDialog(onDismissRequest = { showSettings = false }, confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Done") } }, title = { Text("Playback settings") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Text("Speed", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)) { value ->
                FilterChip(speed == value, { speed = value; player.setPlaybackSpeed(value) }, { Text("${value}×") })
            } }
            Spacer(Modifier.height(16.dp)); Text("Quality", style = MaterialTheme.typography.titleMedium)
            streams.forEach { stream ->
                val bitrate = if (stream.bitrate > 0) " • ${"%.1f".format(stream.bitrate / 1_000_000.0)} Mbps" else ""
                ListItem(headlineContent = { Text("${stream.height}p • ${stream.fps} FPS • ${stream.codec.uppercase()}$bitrate") },
                    leadingContent = { RadioButton(selected == stream, null) }, modifier = Modifier.clickable { play(stream) })
            }
            Spacer(Modifier.height(16.dp)); Text("Audio codec", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(AudioCodecChoice.entries) { choice ->
                    FilterChip(preferredAudioCodec == choice, {
                        preferredAudioCodec = choice
                        audio = selectAudioStream(audioTracks, choice)
                        selected?.let(::play)
                    }, { Text(choice.label) })
                }
            }
            Spacer(Modifier.height(16.dp)); Text("Audio track", style = MaterialTheme.typography.titleMedium)
            visibleAudioChoices(audioTracks, audio).forEach { track ->
                ListItem(headlineContent = { Text(audioChoiceLabel(track)) }, supportingContent = { Text("${track.codec.uppercase()} • ${track.bitrate / 1000} kbps") },
                    leadingContent = { RadioButton(audio?.let(::audioChoiceKey) == audioChoiceKey(track), null) }, modifier = Modifier.clickable { audio = track; selected?.let(::play) })
            }
        }
    })
}

@Composable
private fun VideoScreen(activity: MainActivity, url: String, codec: CodecChoice, audioCodec: AudioCodecChoice = AudioCodecChoice.MP4A, quality: QualityChoice, onPlayer: (ExoPlayer) -> Unit, playlistItems: List<FeedItem> = emptyList(), playlistIndex: Int = -1, onPlaylistIndex: (Int) -> Unit = {}, onPlayerMode: ((Boolean) -> Unit)? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val videoId = Regex("[?&]v=([^&]+)").find(url)?.groupValues?.get(1) ?: url.substringAfterLast('/')
    var info by remember { mutableStateOf<StreamInfo?>(null) }
    var videoMetadata by remember { mutableStateOf<VideoDetails?>(null) }
    var channelInfo by remember { mutableStateOf<ChannelInfo?>(null) }
    var comments by remember { mutableStateOf<List<CommentsInfoItem>>(emptyList()) }
    var commentText by remember { mutableStateOf("") }
    var commentStatus by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var availableStreams by remember { mutableStateOf<List<PlayerChoice>>(emptyList()) }
    var audioStreams by remember { mutableStateOf<List<PlayerChoice>>(emptyList()) }
    var selectedAudio by remember { mutableStateOf<PlayerChoice?>(null) }
    var selectedStream by remember { mutableStateOf<PlayerChoice?>(null) }
    var sabrPlayback by remember { mutableStateOf<SabrPlaybackInfo?>(null) }
    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var hasStartedPlayback by remember(videoId) { mutableStateOf(false) }
    val videoLoadStatus by PlaybackLoadStatus.message.collectAsState()
    val videoLoadMessage = videoLoadStatus?.takeIf { it.mediaId == videoId }?.text
    var playerPosition by remember { mutableLongStateOf(0L) }
    var playerBuffered by remember { mutableLongStateOf(0L) }
    var playerDuration by remember { mutableLongStateOf(0L) }
    var showPlayerSettings by remember { mutableStateOf(false) }
    var showVideoDetails by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var channelUrl by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var playerGeneration by remember(videoId) { mutableIntStateOf(0) }
    var decoderRecoveryPosition by remember(videoId) { mutableStateOf<Long?>(null) }
    if (channelUrl != null) { ChannelScreen(activity, channelUrl!!, codec, audioCodec, quality, onPlayer) { channelUrl = null }; return }
    val player = remember(videoId, playerGeneration) { PlayerFactory.bufferedPlayer(context).also(onPlayer) }
    var isMinimized by remember { mutableStateOf(false) }
    LaunchedEffect(isMinimized) { onPlayerMode?.invoke(!isMinimized) }
    var miniplayerDismissed by remember { mutableStateOf(false) }
    var currentPlayerView by remember { mutableStateOf<PlayerView?>(null) }
    var controllerVisible by remember { mutableStateOf(true) }
    fun bindPlaylistControls(view: PlayerView) {
        val previous = view.findViewById<View>(com.google.android.exoplayer2.ui.R.id.exo_prev)
        val next = view.findViewById<View>(com.google.android.exoplayer2.ui.R.id.exo_next)
        previous?.isEnabled = playlistIndex > 0
        previous?.alpha = if (playlistIndex > 0) 1f else .35f
        previous?.setOnClickListener { if (playlistIndex > 0) onPlaylistIndex(playlistIndex - 1) }
        next?.isEnabled = playlistIndex >= 0 && playlistIndex < playlistItems.lastIndex
        next?.alpha = if (playlistIndex >= 0 && playlistIndex < playlistItems.lastIndex) 1f else .35f
        next?.setOnClickListener { if (playlistIndex >= 0 && playlistIndex < playlistItems.lastIndex) onPlaylistIndex(playlistIndex + 1) }
    }
    fun play(stream: PlayerChoice) {
        val audio = if (stream.videoOnly) selectedAudio ?: audioStreams.maxByOrNull { it.bitrate } else null
        val sabr = sabrPlayback
        val source = if (sabr != null && stream.sabrFormat != null && audio?.sabrFormat != null) {
            SabrMediaFactory.create(sabr, stream.sabrFormat, audio.sabrFormat,
                sabrDataSourceFactory(sabr.serverAbrStreamingUrl))
        } else {
            val videoSource = ProgressiveMediaSource.Factory(videoDataSourceFactory(stream.url)).createMediaSource(MediaItem.fromUri(stream.url))
            if (audio != null) {
            val audioSource = ProgressiveMediaSource.Factory(videoDataSourceFactory(audio.url)).createMediaSource(MediaItem.fromUri(audio.url))
            // YouTube's separate adaptive tracks may start on very different media
            // timestamps. Align their periods so ExoPlayer can render immediately.
            MergingMediaSource(true, videoSource, audioSource)
            } else videoSource
        }
        val position = player.currentPosition.coerceAtLeast(0L)
        player.setMediaSource(source); player.prepare(); if (position > 0) player.seekTo(position); player.setPlaybackSpeed(speed); player.playWhenReady = true
        selectedStream = stream
    }
    val mediaSession = remember(player) { MediaSessionCompat(context, "MaterialYT Video") }
    LaunchedEffect(info, videoMetadata, channelInfo) {
        if (info != null || videoMetadata != null) {
            val title = info?.name ?: videoMetadata?.title ?: "Video"
            val author = info?.uploaderName ?: channelInfo?.name ?: videoMetadata?.author ?: ""
            val thumb = info?.thumbnails?.lastOrNull()?.url ?: videoMetadata?.let { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
            activity.showPlaybackNotification(mediaSession, title, author, thumb, player.isPlaying)
        }
    }
    DisposableEffect(player, videoId) {
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.videoPlayerActive(true)
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = player.play()
            override fun onPause() = player.pause()
            override fun onStop() = player.stop()
            override fun onSeekTo(pos: Long) = player.seekTo(pos)
        })
        mediaSession.isActive = true
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                if (state == Player.STATE_READY) PlaybackLoadStatus.clear(videoId)
                else if (!hasStartedPlayback && state == Player.STATE_BUFFERING && PlaybackLoadStatus.message.value?.mediaId == videoId) {
                    PlaybackLoadStatus.show(videoId, "Buffering media…")
                }
                if (state == Player.STATE_ENDED && playlistIndex >= 0 && playlistIndex < playlistItems.lastIndex) onPlaylistIndex(playlistIndex + 1)
                mediaSession.setPlaybackState(PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO)
                    .setState(if (player.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, player.currentPosition.coerceAtLeast(0L), if (player.isPlaying) 1f else 0f).build())
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    hasStartedPlayback = true
                    PlaybackLoadStatus.clear(videoId)
                }
                activity.updatePipAction()
                if (!isPlaying) {
                    val pos = player.currentPosition
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { YouTubeRepository.reportPlayback(videoId, maxOf(0, pos - 1000L), pos, paused = true) } }
                }
                val title = info?.name ?: videoMetadata?.title ?: "Video"
                val author = info?.uploaderName ?: channelInfo?.name ?: videoMetadata?.author ?: ""
                val thumb = info?.thumbnails?.lastOrNull()?.url ?: videoMetadata?.let { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
                activity.showPlaybackNotification(mediaSession, title, author, thumb, isPlaying)
                mediaSession.setPlaybackState(PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO)
                    .setState(if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, player.currentPosition.coerceAtLeast(0L), if (isPlaying) 1f else 0f).build())
            }
            override fun onRenderedFirstFrame() {
                hasStartedPlayback = true
                decoderRecoveryPosition = null
                PlaybackLoadStatus.clear(videoId)
            }
            override fun onPlayerError(cause: PlaybackException) {
                Log.e("MaterialYT", "video player ${cause.errorCodeName}", cause)
                if (cause.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED && selectedStream != null) {
                    // A runtime MediaCodec failure cannot be recovered by preparing the same
                    // ExoPlayer instance: its renderer still owns the failed codec. Recreate the
                    // player, then rebuild the exact same video/audio source at the current point.
                    // Deliberately do not select another format, codec, or decoder mode here.
                    decoderRecoveryPosition = player.currentPosition.coerceAtLeast(playerPosition)
                    error = null
                    AppLog.event("video decoder restart video=$videoId position=${decoderRecoveryPosition} codec=${selectedStream?.codec}")
                    PlaybackLoadStatus.show(videoId, "Restarting video decoder…")
                    playerGeneration++
                } else {
                    error = "${cause.errorCodeName}: ${cause.cause?.message ?: cause.localizedMessage}"
                }
            }
        }
        player.addListener(listener)
        onDispose {
            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.stopPlaybackNotification()
            mediaSession.isActive = false
            mediaSession.release()
            activity.fullscreen(false)
            activity.videoPlayerActive(false)
            activity.visiblePlayerView(null)
            player.removeListener(listener)
            player.release()
        }
    }
    BackHandler {
        if (fullscreen) {
            fullscreen = false
            activity.fullscreen(false)
        } else if (!isMinimized) {
            isMinimized = true
        } else {
            miniplayerDismissed = true
            onBack()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            playerPosition = player.currentPosition.coerceAtLeast(0L)
            playerBuffered = player.bufferedPosition.coerceAtLeast(playerPosition)
            playerDuration = player.duration.coerceAtLeast(0L)
            if (!hasStartedPlayback && playerPosition > 0L) {
                hasStartedPlayback = true
                PlaybackLoadStatus.clear(videoId)
            }
            currentPlayerView?.let(::bindPlaylistControls)
            delay(250)
        }
    }
    LaunchedEffect(player, playerGeneration) {
        val recoveryPosition = decoderRecoveryPosition ?: return@LaunchedEffect
        val stream = selectedStream ?: return@LaunchedEffect
        play(stream)
        player.seekTo(recoveryPosition)
    }
    LaunchedEffect(url, codec, audioCodec, quality) {
        val videoId = Regex("[?&]v=([^&]+)").find(url)?.groupValues?.get(1) ?: url.substringAfterLast('/')
        // v1.2.8 behavior: NewPipe is the primary source because it exposes the complete
        // adaptive format set (AVC, VP9 and AV1). The authenticated player path remains a
        // fallback only; accepting its combined WEB format first collapses the UI to 360p.
        player.stop(); player.clearMediaItems(); availableStreams = emptyList(); audioStreams = emptyList(); selectedStream = null; selectedAudio = null; sabrPlayback = null; error = null
        val newPipeResult = if (PlaybackBackendPreferences.useNewPipe()) runCatching { withContext(Dispatchers.IO) { YouTubeRepository.newPipeStreamInfo(videoId) } }
            else Result.failure(IllegalStateException("NewPipe disabled by playback backend setting"))
        newPipeResult.onSuccess { stream ->
            info = stream
            audioStreams = stream.audioStreams.filter { it.content.startsWith("http") }.map {
                val name = it.audioTrackName?.takeIf(String::isNotBlank) ?: it.audioLocale?.displayName?.takeIf(String::isNotBlank).orEmpty()
                val original = it.audioTrackType?.name?.contains("ORIGINAL", true) == true || name.contains("original", true)
                PlayerChoice(it.content, it.codec.orEmpty(), 0, 0, it.bitrate, false, name, it.audioTrackId.orEmpty(), original)
            }
            selectedAudio = selectAudioStream(audioStreams, audioCodec)
            availableStreams = (stream.videoOnlyStreams + stream.videoStreams)
                .filter { it.content.startsWith("http") }
                .map { PlayerChoice(it.content, it.codec.orEmpty(), it.height, it.fps, it.bitrate, it.isVideoOnly) }
                .distinctBy { "${it.height}-${it.fps}-${it.codec}" }.sortedByDescending { it.height }
            selectVideoStream(availableStreams, codec, quality)?.let { chosen -> error = null; play(chosen) }
            AppLog.event("player NewPipe primary video=$videoId videoStreams=${availableStreams.size} audioStreams=${audioStreams.size}")
        }.onFailure { e ->
            AppLog.failure("player NewPipe primary video=$videoId", e)
        }
        if (availableStreams.isEmpty() && PlaybackBackendPreferences.useWebWhenNewPipeFails()) {
            runCatching { withContext(Dispatchers.IO) { YouTubeRepository.sabrPlaybackInfo(videoId) } }.onSuccess { extracted ->
                sabrPlayback = extracted
                audioStreams = extracted.formats.filter { it.audio }.map {
                    PlayerChoice(extracted.serverAbrStreamingUrl, it.codec, 0, 0, it.bitrate,
                        false, it.audioTrackName, it.audioTrackId, it.originalAudio, it)
                }
                selectedAudio = selectAudioStream(audioStreams, audioCodec)
                YouTubeRepository.videoDetails(videoId)?.let { videoMetadata = it }
                availableStreams = extracted.formats.filter { !it.audio && it.height > 0 }.map {
                    PlayerChoice(extracted.serverAbrStreamingUrl, it.codec, it.height, it.fps,
                        it.bitrate, true, sabrFormat = it)
                }.distinctBy { "${it.height}-${it.fps}-${it.codec}" }.sortedByDescending { it.height }
                selectVideoStream(availableStreams, codec, quality)?.let { chosen -> error = null; play(chosen) }
                AppLog.event("player SABR primary video=$videoId videoStreams=${availableStreams.size} audioStreams=${audioStreams.size}")
            }.onFailure { AppLog.failure("player SABR primary video=$videoId", it) }
        }
        if (availableStreams.isEmpty() && PlaybackBackendPreferences.useWebWhenNewPipeFails()) {
            runCatching { withContext(Dispatchers.IO) { YouTubeRepository.playerStreams(videoId) } }.onSuccess { extracted ->
                audioStreams = extracted.filter { it.audio }.map { PlayerChoice(it.url, it.codec, 0, 0, it.bitrate, false, it.audioTrackName, it.audioTrackId, it.originalAudio) }
                selectedAudio = selectAudioStream(audioStreams, audioCodec)
                availableStreams = extracted.filter { !it.audio && it.height > 0 }
                    .map { PlayerChoice(it.url, it.codec, it.height, it.fps, it.bitrate, it.videoOnly) }
                    .distinctBy { "${it.height}-${it.fps}-${it.codec}" }.sortedByDescending { it.height }
                YouTubeRepository.videoDetails(videoId)?.let { videoMetadata = it }
                selectVideoStream(availableStreams, codec, quality)?.let { chosen -> error = null; play(chosen) }
            }.onFailure { e ->
                AppLog.failure("player repository fallback video=$videoId", e)
                if (availableStreams.isEmpty()) error = e.message.orEmpty()
            }
        }
        runCatching { withContext(Dispatchers.IO) { CommentsInfo.getInfo(url)?.relatedItems ?: emptyList() } }.onSuccess { comments = it }
        runCatching { withContext(Dispatchers.IO) { YouTubeRepository.channelInfo(videoId) } }.onSuccess { channelInfo = it }
        runCatching { withContext(Dispatchers.IO) { YouTubeRepository.refreshVideoDetails(videoId) } }.onSuccess { videoMetadata = it }
    }
    LaunchedEffect(url) {
        val videoId = Regex("[?&]v=([^&]+)").find(url)?.groupValues?.get(1) ?: url.substringAfterLast('/')
        var lastReported = 0L
        while (true) {
            delay(if (lastReported == 0L) 1_500 else 10_000)
            val current = player.currentPosition.coerceAtLeast(0L)
            if (player.isPlaying && current > lastReported) {
                runCatching { withContext(Dispatchers.IO) { YouTubeRepository.reportPlayback(videoId, lastReported, current) } }
                lastReported = current
            } else if (current < lastReported) lastReported = current
        }
    }
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        var stoppedOutsideApp = false
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                stoppedOutsideApp = true
                if (!activity.getSharedPreferences("settings", 0).getBoolean("background_play", false)) {
                    player.pause()
                }
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && stoppedOutsideApp) {
                // Some devices keep MediaCodec bound to the destroyed background SurfaceView. Merely
                // assigning PlayerView again leaves that codec rendering audio with a frozen frame.
                // Toggle only the video track so ExoPlayer recreates its video renderer/codec while
                // retaining the active media source, SABR buffers, audio renderer and position.
                val position = player.currentPosition.coerceAtLeast(0L)
                val trackParameters = player.trackSelectionParameters
                player.trackSelectionParameters = trackParameters.buildUpon()
                    .setTrackTypeDisabled(com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO, true)
                    .build()
                currentPlayerView?.let { view ->
                    view.player = null
                    view.player = player
                    activity.visiblePlayerView(view)
                }
                player.trackSelectionParameters = trackParameters
                player.seekTo(position)
                stoppedOutsideApp = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (miniplayerDismissed) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    if (isMinimized) {
        var downwardDrag by remember { mutableFloatStateOf(0f) }
        val animatedOffset by animateFloatAsState(
            targetValue = downwardDrag.coerceAtLeast(0f),
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "video_mini_drag"
        )
        val miniAlpha = (1f - (animatedOffset / 100f)).coerceIn(0f, 1f)

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(66.dp)
                    .offset { IntOffset(0, animatedOffset.roundToInt()) }
                    .graphicsLayer { alpha = miniAlpha }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { downwardDrag = 0f },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                downwardDrag += amount
                            },
                            onDragEnd = {
                                if (downwardDrag > 48f) {
                                    miniplayerDismissed = true
                                    onBack()
                                }
                                downwardDrag = 0f
                            },
                            onDragCancel = { downwardDrag = 0f }
                        )
                    }
                    .clickable {
                        isMinimized = false
                        onPlayerMode?.invoke(true)
                    },
                tonalElevation = 6.dp
            ) {
                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ComposeColor.Black)
                    ) {
                        AndroidView(
                            factory = {
                                PlayerView(it).apply {
                                    this.player = player
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                                    currentPlayerView = this
                                    activity.visiblePlayerView(this)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            update = {
                                currentPlayerView = it
                                it.useController = false
                                it.hideController()
                                activity.visiblePlayerView(it)
                            }
                        )
                    }
                    Column(Modifier.padding(horizontal = 10.dp).weight(1f)) {
                        Text(info?.name ?: videoMetadata?.title ?: "Video", Modifier.autoMarquee(), maxLines = 1, softWrap = false, style = MaterialTheme.typography.titleSmall)
                        Text(info?.uploaderName ?: videoMetadata?.author ?: "", Modifier.autoMarquee(), maxLines = 1, softWrap = false, style = MaterialTheme.typography.bodySmall)
                    }
                    var playing by remember { mutableStateOf(player.isPlaying) }
                    DisposableEffect(player) {
                        val playListener = object : Player.Listener {
                            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                        }
                        player.addListener(playListener)
                        onDispose { player.removeListener(playListener) }
                    }
                    IconButton(onClick = { if (playing) player.pause() else player.play() }) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause")
                    }
                    IconButton(onClick = { miniplayerDismissed = true; onBack() }) {
                        Icon(Icons.Default.Close, "Close")
                    }
                }
            }
        }
        return
    }

    if (activity.pipMode) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER); currentPlayerView = this; activity.visiblePlayerView(this) } },
            modifier = Modifier.fillMaxSize(),
            update = { currentPlayerView = it; it.useController = false; it.hideController(); activity.visiblePlayerView(it) }
        )
    } else if (fullscreen) {
        Box(Modifier.fillMaxSize().background(ComposeColor.Black)) {
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player; useController = true; controllerShowTimeoutMs = 3_000; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS); setControllerVisibilityListener(PlayerControlView.VisibilityListener { visibility -> controllerVisible = visibility == View.VISIBLE }); currentPlayerView = this; activity.visiblePlayerView(this); post { bindPlaylistControls(this); hideController(); controllerVisible = false } } },
                update = { currentPlayerView = it; activity.visiblePlayerView(it); bindPlaylistControls(it) },
                modifier = Modifier.fillMaxSize().padding(bottom = 8.dp)
            )
            if (!hasStartedPlayback) videoLoadMessage?.let { message ->
                PlaybackLoadingStatus(message, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
            }
            AnimatedVisibility(controllerVisible, modifier = Modifier.align(Alignment.TopCenter)) { Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 2.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { fullscreen = false; activity.fullscreen(false) }) {
                    Icon(Icons.Default.FullscreenExit, "Exit fullscreen", tint = ComposeColor.White)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showPlayerSettings = true }) { Icon(Icons.Default.Settings, "Player settings", tint = ComposeColor.White) }
                if (Build.VERSION.SDK_INT >= 26) IconButton(onClick = { activity.pip() }) { Icon(Icons.Default.PictureInPicture, "Picture in picture", tint = ComposeColor.White) }
            } }
        }
    } else {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (tablet) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1.15f).verticalScroll(rememberScrollState())) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(ComposeColor.Black)) {
                            AndroidView({ PlayerView(it).apply { this.player = player; useController = true; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS); currentPlayerView = this; activity.visiblePlayerView(this); post { bindPlaylistControls(this) } } }, Modifier.fillMaxSize(), update = { currentPlayerView = it; activity.visiblePlayerView(it); bindPlaylistControls(it) })
                            if (!hasStartedPlayback) videoLoadMessage?.let { message ->
                                PlaybackLoadingStatus(message, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { isMinimized = true }) { Icon(Icons.Default.ArrowBack, "Back") }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { showPlayerSettings = true }) { Icon(Icons.Default.Settings, "Player settings") }
                            IconButton(onClick = { fullscreen = true; activity.fullscreen(true) }) { Icon(Icons.Default.Fullscreen, "Fullscreen") }
                            if (Build.VERSION.SDK_INT >= 26) IconButton(onClick = { activity.pip() }) { Icon(Icons.Default.PictureInPicture, "Picture in picture") }
                        }
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
                            Text(info?.name ?: videoMetadata?.title ?: "Video", Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.titleLarge)
                            IconButton(onClick = { showVideoDetails = true }) { Icon(Icons.Default.MoreVert, "Video details") }
                        }
                        Text(
                            if (info == null && videoMetadata == null) "" else "${formatCount(info?.viewCount ?: videoMetadata?.viewCount ?: 0)} views",
                            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth().clickable(enabled = !(info?.uploaderUrl.isNullOrBlank() && videoMetadata?.authorUrl.isNullOrBlank())) { channelUrl = info?.uploaderUrl ?: videoMetadata?.authorUrl }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val avatar = channelInfo?.thumbnail?.takeIf { it.isNotBlank() } ?: info?.uploaderAvatars?.lastOrNull()?.url ?: videoMetadata?.authorAvatar
                            if (!avatar.isNullOrBlank()) AsyncImage(avatar, null, Modifier.size(42.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                            else Surface(Modifier.size(42.dp), CircleShape, color = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Default.Person, null, Modifier.padding(9.dp)) }
                            Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                                Text(channelInfo?.name?.takeUnless { it.equals("Unknown channel", true) } ?: info?.uploaderName?.takeUnless { it.equals("Unknown channel", true) } ?: videoMetadata?.author?.takeUnless { it.equals("Unknown channel", true) } ?: "Loading channel…", style = MaterialTheme.typography.titleMedium)
                                val subscribers = info?.uploaderSubscriberCount ?: -1
                                if (subscribers >= 0) Text("${formatCount(subscribers)} subscribers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                    Column(Modifier.weight(0.85f).fillMaxHeight()) {
                        Text("Comments", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                        HorizontalDivider()
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(comments) { comment -> CommentNode(comment) }
                        }
                    }
                }
            } else LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(ComposeColor.Black)) {
                        AndroidView({ PlayerView(it).apply { this.player = player; useController = true; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS); currentPlayerView = this; activity.visiblePlayerView(this); post { bindPlaylistControls(this) } } }, Modifier.fillMaxSize(), update = { currentPlayerView = it; activity.visiblePlayerView(it); bindPlaylistControls(it) })
                        if (!hasStartedPlayback) videoLoadMessage?.let { message ->
                            PlaybackLoadingStatus(message, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { isMinimized = true }) { Icon(Icons.Default.ArrowBack, "Back") }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { showPlayerSettings = true }) { Icon(Icons.Default.Settings, "Player settings") }
                        IconButton(onClick = { fullscreen = true; activity.fullscreen(true) }) { Icon(Icons.Default.Fullscreen, "Fullscreen") }
                        if (Build.VERSION.SDK_INT >= 26) IconButton(onClick = { activity.pip() }) { Icon(Icons.Default.PictureInPicture, "Picture in picture") }
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
                        Text(info?.name ?: videoMetadata?.title ?: "Video", Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = { showVideoDetails = true }) { Icon(Icons.Default.MoreVert, "Video details") }
                    }
                    Text(
                        if (info == null && videoMetadata == null) "" else "${formatCount(info?.viewCount ?: videoMetadata?.viewCount ?: 0)} views",
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().clickable(enabled = !(info?.uploaderUrl.isNullOrBlank() && videoMetadata?.authorUrl.isNullOrBlank())) { channelUrl = info?.uploaderUrl ?: videoMetadata?.authorUrl }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val avatar = channelInfo?.thumbnail?.takeIf { it.isNotBlank() } ?: info?.uploaderAvatars?.lastOrNull()?.url ?: videoMetadata?.authorAvatar
                        if (!avatar.isNullOrBlank()) AsyncImage(avatar, null, Modifier.size(42.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        else Surface(Modifier.size(42.dp), CircleShape, color = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Default.Person, null, Modifier.padding(9.dp)) }
                        Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                            Text(channelInfo?.name?.takeUnless { it.equals("Unknown channel", true) } ?: info?.uploaderName?.takeUnless { it.equals("Unknown channel", true) } ?: videoMetadata?.author?.takeUnless { it.equals("Unknown channel", true) } ?: "Loading channel…", style = MaterialTheme.typography.titleMedium)
                            val subscribers = info?.uploaderSubscriberCount ?: -1
                            if (subscribers >= 0) Text("${formatCount(subscribers)} subscribers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                    Text("Comments", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                }
                items(comments) { comment -> CommentNode(comment) }
            }
        }
    }
    if (showVideoDetails) AlertDialog(
        onDismissRequest = { showVideoDetails = false },
        confirmButton = { TextButton(onClick = { showVideoDetails = false }) { Text("Close") } },
        title = { Text("Video details") },
        text = {
            val views = info?.viewCount ?: videoMetadata?.viewCount ?: -1L
            val likes = info?.likeCount ?: -1L
            val likesText = if (likes >= 0) formatCount(likes) else videoMetadata?.likeText?.takeIf { it.isNotBlank() } ?: "—"
            val publishedDate = info?.uploadDate?.localDateTime?.toLocalDate()?.let { date ->
                java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG)
                    .withLocale(java.util.Locale.getDefault()).format(date)
            } ?: info?.textualUploadDate?.takeIf { it.isNotBlank() } ?: videoMetadata?.publishedDate?.takeIf { it.isNotBlank() }
            val rawDescription = info?.description?.content.orEmpty().ifBlank { videoMetadata?.description.orEmpty() }
            val description = if (Build.VERSION.SDK_INT >= 24) Html.fromHtml(rawDescription, Html.FROM_HTML_MODE_LEGACY).toString()
                else @Suppress("DEPRECATION") Html.fromHtml(rawDescription).toString()
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column { Text(if (views >= 0) formatCount(views) else "—", style = MaterialTheme.typography.titleLarge); Text("Views", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Column { Text(likesText, style = MaterialTheme.typography.titleLarge); Text("Likes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(16.dp))
                Text("Published", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(publishedDate ?: "Date unavailable", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Text("Description", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(description.ifBlank { "No description was provided." }, style = MaterialTheme.typography.bodyMedium)
            }
        }
    )
    if (showPlayerSettings) AlertDialog(onDismissRequest = { showPlayerSettings = false }, confirmButton = { TextButton(onClick = { showPlayerSettings = false }) { Text("Done") } }, title = { Text("Playback settings") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Text("Speed", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)) { value -> FilterChip(speed == value, { speed = value; player.setPlaybackSpeed(value) }, { Text("${value}×") }) } }
            Spacer(Modifier.height(16.dp)); Text("Quality", style = MaterialTheme.typography.titleMedium)
            availableStreams.forEach { stream ->
                val bitrate = if (stream.bitrate > 0) " • ${"%.1f".format(stream.bitrate / 1_000_000.0)} Mbps" else ""
                val label = "${stream.height}p • ${if (stream.fps > 0) stream.fps else "?"} FPS • ${stream.codec.uppercase()}$bitrate"
                ListItem(headlineContent = { Text(label) }, leadingContent = { RadioButton(selectedStream == stream, onClick = null) }, modifier = Modifier.clickable { play(stream) })
            }
            Spacer(Modifier.height(16.dp)); Text("Audio track", style = MaterialTheme.typography.titleMedium)
            visibleAudioChoices(audioStreams, selectedAudio).forEach { audio ->
                val bitrate = if (audio.bitrate > 0) "${audio.bitrate / 1000} kbps" else "Unknown bitrate"
                ListItem(headlineContent = { Text(audioChoiceLabel(audio)) }, supportingContent = { Text("${audio.codec.uppercase()} • $bitrate") }, leadingContent = { RadioButton(selectedAudio?.let(::audioChoiceKey) == audioChoiceKey(audio), onClick = null) }, modifier = Modifier.clickable { selectedAudio = audio; selectedStream?.let(::play) })
            }
        }
    })
}

@Composable
private fun LoginScreen(onBack: () -> Unit, onSignedIn: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.Close, "Close login") }; Text("Sign in with Google", style = MaterialTheme.typography.titleLarge) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                settings.userAgentString = settings.userAgentString.replace("; wv", "")
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) { loading = false; if (YouTubeRepository.signedIn()) onSignedIn() }
                }
                webChromeClient = WebChromeClient()
                loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fwww.youtube.com%2F")
            }
        }, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun OwnAccountScreen(activity: MainActivity, codec: CodecChoice, audioCodec: AudioCodecChoice = AudioCodecChoice.MP4A, quality: QualityChoice, onPlayer: (ExoPlayer) -> Unit, onBack: () -> Unit) {
    var channelUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { YouTubeRepository.ownChannelUrl() } }
            .onSuccess { channelUrl = it }
            .onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }
    }
    channelUrl?.let { ChannelScreen(activity, it, codec, audioCodec, quality, onPlayer, onBack); return }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }; Text("Your Account", style = MaterialTheme.typography.headlineMedium) }
        if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else Text(error.orEmpty(), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun AccountScreen(activity: MainActivity, theme: ThemeMode, color: AppColor, codec: CodecChoice, audioCodec: AudioCodecChoice, quality: QualityChoice, videoDecoderMode: DecoderMode, audioDecoderMode: DecoderMode, onTheme: (ThemeMode) -> Unit, onColor: (AppColor) -> Unit, onCodec: (CodecChoice) -> Unit, onAudioCodec: (AudioCodecChoice) -> Unit, onQuality: (QualityChoice) -> Unit, onVideoDecoderMode: (DecoderMode) -> Unit, onAudioDecoderMode: (DecoderMode) -> Unit, onPlayer: (ExoPlayer) -> Unit, onAuth: (Boolean) -> Unit) {
    var destination by remember { mutableStateOf("account") }
    var signedIn by remember { mutableStateOf(YouTubeRepository.signedIn()) }
    var playbackBackend by remember { mutableStateOf(PlaybackBackendPreferences.backend) }
    if (destination == "login") { LoginScreen({ destination = "account" }) { signedIn = true; onAuth(true); destination = "account" }; return }
    if (destination == "your_account") { OwnAccountScreen(activity, codec, audioCodec, quality, onPlayer) { destination = "account" }; return }
    if (destination == "playlists") { NativeFeedScreen(activity, "Playlists", Icons.Default.PlaylistPlay, codec, audioCodec, quality, onPlayer, {}, { destination = "account" }) { YouTubeRepository.library() }; return }
    if (destination == "account") {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("Account", style = MaterialTheme.typography.headlineMedium) }
            item { ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) { Column(Modifier.padding(22.dp)) { Icon(Icons.Default.AccountCircle, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(12.dp)); Text(if (signedIn) "Google account connected" else "Make MaterialYT yours", style = MaterialTheme.typography.titleLarge); Text(if (signedIn) "Your session powers native Home, History, YT Music, playback and comments." else "Sign in once. Only the Google login screen uses the web; your content stays in MaterialYT's native UI."); Spacer(Modifier.height(16.dp)); Button(onClick = { destination = "login" }) { Text(if (signedIn) "Refresh sign-in" else "Sign in with Google") } } } }
            if (signedIn) item { ListItem(headlineContent = { Text("Your Account") }, supportingContent = { Text("Open your channel") }, leadingContent = { Icon(Icons.Default.AccountCircle, null) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) }, modifier = Modifier.clickable { destination = "your_account" }) }
            if (signedIn) item { ListItem(headlineContent = { Text("Playlists") }, leadingContent = { Icon(Icons.Default.PlaylistPlay, null) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) }, modifier = Modifier.clickable { destination = "playlists" }) }
            item { ListItem(headlineContent = { Text("Settings") }, supportingContent = { Text("Appearance, codec and backend protocol") }, leadingContent = { Icon(Icons.Default.Settings, null) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) }, modifier = Modifier.clickable { destination = "settings" }) }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { destination = "account" }) { Icon(Icons.Default.ArrowBack, "Back to Account") }; Text("Settings", style = MaterialTheme.typography.headlineMedium) } }
        item {
            val prefs = activity.getSharedPreferences("settings", 0)
            var backgroundPlay by remember { mutableStateOf(prefs.getBoolean("background_play", false)) }
            Row(Modifier.fillMaxWidth().clickable {
                backgroundPlay = !backgroundPlay
                prefs.edit().putBoolean("background_play", backgroundPlay).apply()
            }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Continue Playing while not in PiP or app in foreground", style = MaterialTheme.typography.titleMedium)
                    Text("Keep audio playing when app is minimized", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = backgroundPlay, onCheckedChange = {
                    backgroundPlay = it
                    prefs.edit().putBoolean("background_play", it).apply()
                })
            }
        }
        item { ChoiceSection("Theme", ThemeMode.entries, theme, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, onTheme) }
        item { ChoiceSection("Material color", AppColor.entries, color, { it.title }, onColor) }
        item { ChoiceSection("Preferred video codec", CodecChoice.entries, codec, { it.label }, onCodec) }
        item { ChoiceSection("Preferred audio codec", AudioCodecChoice.entries, audioCodec, { it.label }, onAudioCodec) }
        item { ChoiceSection("Default video quality", QualityChoice.entries, quality, { it.label }, onQuality) }
        item { ChoiceSection("Video decoder", DecoderMode.entries, videoDecoderMode, { it.label }, onVideoDecoderMode) }
        item { ChoiceSection("Audio decoder", DecoderMode.entries, audioDecoderMode, { it.label }, onAudioDecoderMode) }
        item { ChoiceSection("Playback backend", PlaybackBackend.entries, playbackBackend, { it.label }) {
            playbackBackend = it
            PlaybackBackendPreferences.set(activity, it)
        } }
        item { Text("SABR is the default and recommended backend. It uses native VISIONOS playback with adaptive audio and video. Force NewPipe is kept for compatibility, but YouTube may reject it with LOGIN_REQUIRED, bot verification, missing formats, or extractor breakage. NewPipe + SABR fallback tries NewPipe first and switches to SABR when extraction fails.", style = MaterialTheme.typography.bodySmall) }
        item { ChoiceSection("Backend HTTP", HttpBackend.Mode.entries, HttpBackend.mode, { it.label }, { HttpBackend.setMode(it) }) }
        item { Text("HTTP/1.0 compatibility disables connection reuse but uses an HTTP/1.1 request line because OkHttp intentionally cannot emit HTTP/1.0. The HTTP/2 mode advertises HTTP/2 with HTTP/1.1 fallback.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun <T> ChoiceSection(title: String, values: Iterable<T>, selected: T, label: (T) -> String, select: (T) -> Unit) {
    Column { Text(title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(6.dp)); LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(values.toList()) { value -> FilterChip(selected == value, { select(value) }, { Text(label(value), maxLines = 1) }) } } }
}
@Composable
fun CommentNode(comment: CommentsInfoItem) {
    var showReplies by remember { mutableStateOf(false) }
    var replies by remember { mutableStateOf<List<CommentsInfoItem>?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.padding(16.dp, 10.dp)) {
        Text(comment.uploaderName ?: "YouTube user", style = MaterialTheme.typography.labelLarge)
        Text((if (android.os.Build.VERSION.SDK_INT >= 24) android.text.Html.fromHtml(comment.commentText.content, android.text.Html.FROM_HTML_MODE_LEGACY) else @Suppress("DEPRECATION") android.text.Html.fromHtml(comment.commentText.content)).toString())
        Text(comment.textualLikeCount ?: "", style = MaterialTheme.typography.bodySmall)

        if (comment.replyCount > 0) {
            TextButton(onClick = {
                showReplies = !showReplies
                if (showReplies && replies == null) {
                    loading = true
                    scope.launch {
                        replies = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val repliesPage = comment.replies ?: return@withContext emptyList()
                            org.schabi.newpipe.extractor.comments.CommentsInfo.getMoreItems(
                                org.schabi.newpipe.extractor.NewPipe.getService(comment.serviceId),
                                comment.url,
                                repliesPage
                            ).items
                        } }.getOrNull()
                        loading = false
                    }
                }
            }) {
                Text(if (showReplies) "Hide replies" else "View ${comment.replyCount} replies")
            }
        }

        if (showReplies) {
            if (loading) CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp))
            replies?.forEach { reply ->
                Column(Modifier.padding(start = 32.dp, top = 8.dp, bottom = 8.dp)) {
                    Text(reply.uploaderName ?: "YouTube user", style = MaterialTheme.typography.labelLarge)
                    Text((if (android.os.Build.VERSION.SDK_INT >= 24) android.text.Html.fromHtml(reply.commentText.content, android.text.Html.FROM_HTML_MODE_LEGACY) else @Suppress("DEPRECATION") android.text.Html.fromHtml(reply.commentText.content)).toString())
                    Text(reply.textualLikeCount ?: "", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
