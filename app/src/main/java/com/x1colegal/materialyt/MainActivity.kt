package com.x1colegal.materialyt

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
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
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
enum class DecoderMode(val label: String) { AUTO("Auto"), HARDWARE("Force HW"), SOFTWARE("Force SW") }

private object PlaybackPreferences {
    @Volatile var decoderMode = DecoderMode.AUTO
}

private data class PlayerChoice(val url: String, val codec: String, val height: Int, val fps: Int, val bitrate: Int, val videoOnly: Boolean, val audioTrackName: String = "", val audioTrackId: String = "", val originalAudio: Boolean = false)

private val videoHttpClient by lazy {
    OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

private fun videoDataSourceFactory(): DataSource.Factory = ChunkedDataSource.Factory(
    OkHttpDataSource.Factory(videoHttpClient)
        .setDefaultRequestProperties(YouTubeRepository.mediaHeaders())
)

private fun bufferedPlayer(context: android.content.Context): ExoPlayer {
    val mode = PlaybackPreferences.decoderMode
    val renderers = DefaultRenderersFactory(context)
        .setEnableDecoderFallback(mode == DecoderMode.AUTO)
        .setMediaCodecSelector(MediaCodecSelector { mimeType, secure, tunneling ->
            val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
            when (mode) {
                DecoderMode.AUTO -> decoders.sortedBy { it.softwareOnly }
                DecoderMode.HARDWARE -> decoders.filter { it.hardwareAccelerated && !it.softwareOnly }
                DecoderMode.SOFTWARE -> decoders.filter { it.softwareOnly || !it.hardwareAccelerated }
            }
        })
    return ExoPlayer.Builder(context, renderers)
    .setLoadControl(DefaultLoadControl.Builder()
        .setBufferDurationsMs(60_000, 180_000, 750, 1_500)
        .setTargetBufferBytes(192 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build())
    .build()
}

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

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1000)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
enum class AppColor(val title: String, val seed: Long) {
    PURPLE("Purple", 0xFF8B5CF6), RED("YouTube Red", 0xFFFF1744), BLUE("Bright Blue", 0xFF2196F3), BLUE_CYAN("Blue Cyan", 0xFF00B8D4),
    GREEN("Forest", 0xFF43A047), ORANGE("Amber", 0xFFFF8F00), PINK("Rose", 0xFFFF4081), TEAL("Teal", 0xFF00A896),
    INDIGO("Indigo", 0xFF4555A5), CYAN("Cyan", 0xFF006874), LIME("Lime", 0xFF526600),
    BROWN("Cocoa", 0xFF805610), MAGENTA("Magenta", 0xFF9C3D88), SLATE("Slate", 0xFF526070)
}

class MainActivity : ComponentActivity() {
    companion object { private const val ACTION_PIP_TOGGLE = "com.x1colegal.materialyt.PIP_TOGGLE" }
    private var player: ExoPlayer? = null
    private var visiblePlayerView: PlayerView? = null
    private var videoPlayerActive = false
    private var fullscreenEnabled = false
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

    fun showMusicNotification(session: MediaSessionCompat, track: FeedItem, playing: Boolean) {
        val channelId = "materialyt_app_playback"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channelId, "Music playback", NotificationManager.IMPORTANCE_LOW))
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        manager.notify(72, NotificationCompat.Builder(this, channelId).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(track.title).setContentText(track.subtitle).setContentIntent(launch).setOnlyAlertOnce(true)
            .setOngoing(playing).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setStyle(MediaStyle().setMediaSession(session.sessionToken)).build())
    }

    fun stopMusicNotification() { getSystemService(NotificationManager::class.java).cancel(72) }

    fun fullscreen(enabled: Boolean) {
        fullscreenEnabled = enabled
        requestedOrientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        applyFullscreenState()
    }

    private fun applyFullscreenState() {
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.let { controller ->
            if (fullscreenEnabled) {
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            } else controller.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        }
        window.decorView.systemUiVisibility = if (fullscreenEnabled) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else View.SYSTEM_UI_FLAG_VISIBLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && fullscreenEnabled && !pipMode) applyFullscreenState()
    }

    fun pip() {
        visiblePlayerView?.hideController()
        if (Build.VERSION.SDK_INT >= 26) enterPictureInPictureMode(pipParams())
    }

    private fun pipParams(): PictureInPictureParams {
        val playing = player?.isPlaying == true
        val intent = PendingIntent.getBroadcast(this, 73, Intent(ACTION_PIP_TOGGLE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val action = RemoteAction(
            android.graphics.drawable.Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (playing) "Pause" else "Play", if (playing) "Pause video" else "Play video", intent
        )
        return PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).setActions(listOf(action)).build()
    }

    fun updatePipAction() {
        if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode) setPictureInPictureParams(pipParams())
    }


    fun visiblePlayerView(view: PlayerView?) { visiblePlayerView = view }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) visiblePlayerView?.hideController()
    }

    fun videoPlayerActive(value: Boolean) { videoPlayerActive = value }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && videoPlayerActive && player?.isPlaying == true && !isInPictureInPictureMode) pip()
    }

    fun immersive(enabled: Boolean) {
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
    val prefs = remember { activity.getSharedPreferences("settings", 0) }
    var theme by remember { mutableStateOf(runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)) }
    var color by remember { mutableStateOf(runCatching { AppColor.valueOf(prefs.getString("color", "PURPLE")!!) }.getOrDefault(AppColor.PURPLE)) }
    var codec by remember { mutableStateOf(runCatching { CodecChoice.valueOf(prefs.getString("codec", "H264")!!) }.getOrDefault(CodecChoice.H264)) }
    var audioCodec by remember { mutableStateOf(runCatching { AudioCodecChoice.valueOf(prefs.getString("audio_codec", "MP4A")!!) }.getOrDefault(AudioCodecChoice.MP4A)) }
    var quality by remember { mutableStateOf(runCatching { QualityChoice.valueOf(prefs.getString("quality", "AUTO")!!) }.getOrDefault(QualityChoice.AUTO)) }
    var decoderMode by remember { mutableStateOf(runCatching { DecoderMode.valueOf(prefs.getString("decoder_mode", "AUTO")!!) }.getOrDefault(DecoderMode.AUTO)) }
    PlaybackPreferences.decoderMode = decoderMode
    val systemDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val dark = theme == ThemeMode.DARK || theme == ThemeMode.OLED || (theme == ThemeMode.SYSTEM && systemDark)
    val base = ComposeColor(color.seed)
    val scheme = colorScheme(base, dark, theme == ThemeMode.OLED)
    SideEffect {
        activity.window.statusBarColor = Color.BLACK
        activity.window.navigationBarColor = if (theme == ThemeMode.OLED) Color.BLACK else scheme.surface.value.toInt()
    }
    MaterialTheme(colorScheme = scheme) {
        AppScaffold(activity, theme, color, codec, audioCodec, quality, decoderMode, onTheme = { theme = it; prefs.edit().putString("theme", it.name).apply() },
            onColor = { color = it; prefs.edit().putString("color", it.name).apply() },
            onCodec = { codec = it; prefs.edit().putString("codec", it.name).apply() },
            onAudioCodec = { audioCodec = it; prefs.edit().putString("audio_codec", it.name).apply() },
            onQuality = { quality = it; prefs.edit().putString("quality", it.name).apply() },
            onDecoderMode = { decoderMode = it; PlaybackPreferences.decoderMode = it; prefs.edit().putString("decoder_mode", it.name).apply() }, onPlayer)
    }
}

@Composable
private fun AppScaffold(
    activity: MainActivity, theme: ThemeMode, color: AppColor, codec: CodecChoice, audioCodec: AudioCodecChoice, quality: QualityChoice, decoderMode: DecoderMode,
    onTheme: (ThemeMode) -> Unit, onColor: (AppColor) -> Unit, onCodec: (CodecChoice) -> Unit, onAudioCodec: (AudioCodecChoice) -> Unit, onQuality: (QualityChoice) -> Unit,
    onDecoderMode: (DecoderMode) -> Unit,
    onPlayer: (ExoPlayer) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var authenticated by remember { mutableStateOf(YouTubeRepository.signedIn()) }
    var playerMode by remember { mutableStateOf(false) }
    val tabs = buildList {
        add("Home" to Icons.Default.OndemandVideo)
        add("Shorts" to Icons.Default.SmartDisplay)
        add("YT Music" to Icons.Default.Album)
        if (authenticated) add("History" to Icons.Default.History)
        add("Account" to Icons.Default.AccountCircle)
    }
    val content: @Composable (PaddingValues) -> Unit = { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tabs.getOrNull(tab)?.first) {
                "Home" -> HomeScreen(activity, codec, audioCodec, quality, onPlayer, { playerMode = it }) { tab = tabs.indexOfFirst { it.first == "Account" } }
                "Shorts" -> ShortsScreen(activity, codec, audioCodec, quality, onPlayer) { playerMode = it }
                "YT Music" -> MusicScreen(activity, audioCodec, onPlayer) { playerMode = it }
                "History" -> NativeFeedScreen(activity, "History", Icons.Default.History, codec, audioCodec, quality, onPlayer, { playerMode = it }, loader = { YouTubeRepository.history() })
                else -> AccountScreen(activity, theme, color, codec, audioCodec, quality, decoderMode, onTheme, onColor, onCodec, onAudioCodec, onQuality, onDecoderMode, onPlayer) { authenticated = it; tab = tabs.indexOfFirst { entry -> entry.first == "Account" } }
            }
        }
    }
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    if (tablet) {
        Row(Modifier.fillMaxSize()) {
            if (!playerMode) NavigationRail {
                Spacer(Modifier.weight(1f))
                tabs.forEachIndexed { index, item -> NavigationRailItem(selected = tab == index, onClick = { playerMode = false; tab = index }, icon = { Icon(item.second, null) }, label = { Text(item.first) }) }
                Spacer(Modifier.weight(1f))
            }
            Scaffold(Modifier.weight(1f), bottomBar = { if (!playerMode) MiniPlayer { tab = tabs.indexOfFirst { it.first == "YT Music" } } }, content = content)
        }
    } else {
        Scaffold(bottomBar = {
            if (!playerMode) Column { MiniPlayer { tab = tabs.indexOfFirst { it.first == "YT Music" } }; NavigationBar { tabs.forEachIndexed { index, item -> NavigationBarItem(selected = tab == index, onClick = { playerMode = false; tab = index }, icon = { Icon(item.second, null) }, label = { Text(item.first) }) } } }
        }, content = content)
    }
}

@Composable
private fun MiniPlayer(openMusic: () -> Unit) {
    val track = AutoMusicService.nowPlaying.value ?: return
    var downwardDrag by remember { mutableFloatStateOf(0f) }
    Surface(Modifier.fillMaxWidth().height(66.dp).pointerInput(track.id) { detectVerticalDragGestures(
        onDragStart = { downwardDrag = 0f }, onVerticalDrag = { change, amount -> change.consume(); downwardDrag += amount },
        onDragEnd = { if (downwardDrag > 48f) AutoMusicService.close(); downwardDrag = 0f }, onDragCancel = { downwardDrag = 0f }
    ) }.clickable(onClick = openMusic), tonalElevation = 6.dp) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(track.thumbnail, null, Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            Column(Modifier.padding(horizontal = 10.dp).weight(1f)) { Text(track.title, maxLines = 1, style = MaterialTheme.typography.titleSmall); Text(track.subtitle, maxLines = 1, style = MaterialTheme.typography.bodySmall) }
            IconButton(onClick = { AutoMusicService.toggle() }) { Icon(if (AutoMusicService.playing.value) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause") }
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
    var feed by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var selectedChannel by remember { mutableStateOf<String?>(null) }
    var selectedResult by remember { mutableStateOf<InfoItem?>(null) }
    LaunchedEffect(selected) { onPlayerMode(selected != null) }
    if (selected != null) {
        VideoScreen(activity, selected!!, codec, audioCodec, quality, onPlayer, onBack = { selected = null })
        return
    }
    if (selectedPlaylist != null) { PlaylistScreen(activity, selectedPlaylist!!, codec, audioCodec, quality, onPlayer) { selectedPlaylist = null }; return }
    if (selectedChannel != null) { ChannelScreen(activity, selectedChannel!!, codec, audioCodec, quality, onPlayer) { selectedChannel = null }; return }
    selectedResult?.let { item ->
        when (item.infoType) {
            InfoItem.InfoType.CHANNEL -> ChannelScreen(activity, item.url, codec, audioCodec, quality, onPlayer) { selectedResult = null }
            InfoItem.InfoType.PLAYLIST -> PlaylistScreen(activity, item.url, codec, audioCodec, quality, onPlayer) { selectedResult = null }
            else -> Unit
        }
        return
    }
    LaunchedEffect(Unit) {
        if (YouTubeRepository.signedIn()) {
            loading = true
            runCatching { withContext(Dispatchers.IO) { YouTubeRepository.home() } }.onSuccess { feed = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }
            loading = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search YouTube") }, shape = CircleShape, leadingIcon = { Icon(Icons.Default.Search, null) })
            IconButton(onClick = {
                if (query.isNotBlank()) scope.launch {
                    loading = true; error = null
                    runCatching { withContext(Dispatchers.IO) { YouTubeRepository.search(query) } }
                        .onSuccess { feed = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message ?: "Search failed" }
                    loading = false
                }
            }) { Icon(Icons.Default.Search, "Search") }
            IconButton(onClick = {
                query = ""
                scope.launch {
                    loading = true; error = null; results = emptyList()
                    runCatching { withContext(Dispatchers.IO) { YouTubeRepository.home() } }
                        .onSuccess { feed = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }
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
        } else LazyColumn {
            if (results.isNotEmpty()) item { Text("Channels and playlists", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge) }
            items(results) { item -> ResultRow(item) { selectedResult = item } }
            if (feed.isNotEmpty() && results.isNotEmpty()) item { Text("Videos", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge) }
            items(feed) { item -> FeedRow(item) { when { item.channel -> selectedChannel = item.url; item.playlist -> selectedPlaylist = item.url; else -> selected = item.url } } }
        }
    }
}

@Composable
private fun NativeFeedScreen(activity: MainActivity, title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, codec: CodecChoice, audioCodec: AudioCodecChoice = AudioCodecChoice.MP4A, quality: QualityChoice, onPlayer: (ExoPlayer) -> Unit, onPlayerMode: (Boolean) -> Unit, onBack: (() -> Unit)? = null, loader: () -> List<FeedItem>) {
    var feed by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(selected) { onPlayerMode(selected != null) }
    if (selected != null) { VideoScreen(activity, selected!!, codec, audioCodec, quality, onPlayer, onBack = { selected = null }); return }
    if (selectedPlaylist != null) { PlaylistScreen(activity, selectedPlaylist!!, codec, audioCodec, quality, onPlayer) { selectedPlaylist = null }; return }
    LaunchedEffect(refreshKey) { loading = true; error = null; runCatching { withContext(Dispatchers.IO) { loader() } }.onSuccess { feed = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }; loading = false }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } else Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium); IconButton(onClick = { refreshKey++ }) { Icon(Icons.Default.Refresh, "Refresh") } }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (!loading && !YouTubeRepository.signedIn()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Sign in from Account to load your $title recommendations.") }
        LazyColumn { items(feed) { item -> FeedRow(item) { if (item.playlist) selectedPlaylist = item.url else selected = item.url } } }
    }
}

@Composable
private fun MusicScreen(activity: MainActivity, audioCodec: AudioCodecChoice, onPlayer: (ExoPlayer) -> Unit, onPlayerMode: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var selected by remember { mutableStateOf<FeedItem?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selected) { onPlayerMode(selected != null) }
    if (selected != null) { MusicPlayer(activity, selected!!, audioCodec) { selected = null }; return }
    fun load(block: () -> List<FeedItem>) { scope.launch { loading = true; error = null; runCatching { withContext(Dispatchers.IO) { block() } }.onSuccess { tracks = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }; loading = false } }
    LaunchedEffect(Unit) { if (YouTubeRepository.signedIn()) { runCatching { withContext(Dispatchers.IO) { YouTubeRepository.music() } }.onSuccess { tracks = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }; loading = false } else loading = false }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search music") }, shape = CircleShape, leadingIcon = { Icon(Icons.Default.Search, null) })
            IconButton(onClick = { if (query.isNotBlank()) load { YouTubeRepository.musicSearch(query) } }) { Icon(Icons.Default.Search, "Search music") }
            IconButton(onClick = { query = ""; load { YouTubeRepository.music() } }) { Icon(Icons.Default.Refresh, "Refresh") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
        if (tablet) {
            LazyVerticalGrid(GridCells.Adaptive(minSize = 300.dp), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                gridItems(tracks) { track -> FeedRow(track) { selected = track } }
            }
        } else {
            LazyColumn {
                items(tracks) { track -> FeedRow(track) { selected = track } }
            }
        }
    }
}

@Composable
private fun MusicPlayer(activity: MainActivity, track: FeedItem, preferredCodec: AudioCodecChoice, onBack: () -> Unit) {
    var showLyrics by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf<List<LyricLine>?>(null) }
    var lyricsError by remember { mutableStateOf<String?>(null) }
    var selectedCodec by remember(track.id) { mutableStateOf(preferredCodec) }
    val lyricsState = rememberLazyListState()
    val position = AutoMusicService.position.longValue
    val duration = AutoMusicService.duration.longValue
    val buffered = AutoMusicService.bufferedPosition.longValue
    val playing = AutoMusicService.playing.value
    BackHandler { onBack() }
    DisposableEffect(Unit) { activity.immersive(true); onDispose { activity.immersive(false) } }
    LaunchedEffect(track.id, selectedCodec) {
        if (AutoMusicService.nowPlaying.value?.id != track.id || selectedCodec != preferredCodec) AutoMusicService.play(activity, track, selectedCodec)
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
    Box(Modifier.fillMaxSize()) {
        AsyncImage(track.thumbnail, null, Modifier.matchParentSize().blur(42.dp), contentScale = ContentScale.Crop)
        Box(Modifier.matchParentSize().background(ComposeColor.Black.copy(alpha = .64f)))
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().displayCutoutPadding().statusBarsPadding().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.Close, "Close player", tint = ComposeColor.White) }
                Text("Now playing", Modifier.weight(1f), color = ComposeColor.White, style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Audio settings", tint = ComposeColor.White) }
            }
            if (tablet) {
                Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        AsyncImage(track.thumbnail, null, Modifier.sizeIn(maxWidth = 360.dp, maxHeight = 360.dp).aspectRatio(1f).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.height(18.dp)); Text(track.title, color = ComposeColor.White, style = MaterialTheme.typography.headlineSmall, maxLines = 2, textAlign = TextAlign.Center); Text(track.subtitle, color = ComposeColor.White.copy(alpha = .72f), textAlign = TextAlign.Center)
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
                        AsyncImage(track.thumbnail, null, Modifier.sizeIn(maxWidth = 320.dp, maxHeight = 320.dp).aspectRatio(1f).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.height(22.dp)); Text(track.title, color = ComposeColor.White, style = MaterialTheme.typography.headlineSmall, maxLines = 2, textAlign = TextAlign.Center); Text(track.subtitle, color = ComposeColor.White.copy(alpha = .72f), textAlign = TextAlign.Center)
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
    if (showSettings) AlertDialog(onDismissRequest = { showSettings = false }, confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Done") } }, title = { Text("Audio settings") }, text = { Column { Text("Original audio is used by default. Selecting a codec restarts this track with the best available matching stream."); Spacer(Modifier.height(12.dp)); AudioCodecChoice.entries.forEach { choice -> FilterChip(selectedCodec == choice, { selectedCodec = choice }, { Text(choice.label) }) } } })
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
    val player = remember { bufferedPlayer(context).also(onPlayer) }
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
        runCatching { withContext(Dispatchers.IO) { YouTubeRepository.playerStreams(track.id, music = true) } }.onSuccess { streams ->
            val audio = streams.filter { it.audio }.maxByOrNull { it.bitrate }
            if (audio != null) {
                val source = ProgressiveMediaSource.Factory(DefaultHttpDataSource.Factory().setDefaultRequestProperties(YouTubeRepository.mediaHeaders()))
                    .createMediaSource(MediaItem.fromUri(audio.url))
                player.setMediaSource(source); player.prepare(); player.playWhenReady = true; mediaLoaded = true
            } else error = "No playable audio stream was found"
        }.onFailure { error = it.message }
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
                        Spacer(Modifier.height(24.dp)); Text(track.title, color = ComposeColor.White, style = MaterialTheme.typography.headlineSmall, maxLines = 2); Text(track.subtitle, color = ComposeColor.White.copy(alpha = .72f), style = MaterialTheme.typography.bodyLarge)
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

@Composable
private fun FeedRow(item: FeedItem, click: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp).clickable(onClick = click)) {
        AsyncImage(item.thumbnail, null, Modifier.fillMaxWidth().aspectRatio(item.thumbnailWidth.toFloat() / item.thumbnailHeight.coerceAtLeast(1)).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop)
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            if (item.channelThumbnail.isNotBlank()) AsyncImage(item.channelThumbnail, null, Modifier.size(38.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            else Surface(Modifier.size(38.dp), CircleShape, color = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Default.Person, null, Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Spacer(Modifier.height(3.dp))
                Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Icon(Icons.Default.MoreVert, "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun ResultRow(item: InfoItem, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(item.thumbnails.firstOrNull()?.url, null, Modifier.size(132.dp, 76.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.padding(start = 12.dp)) { Text(item.name, style = MaterialTheme.typography.titleSmall); Text(item.infoType.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun PlaylistScreen(activity: MainActivity, url: String, codec: CodecChoice, audioCodec: AudioCodecChoice = AudioCodecChoice.MP4A, quality: QualityChoice, onPlayer: (ExoPlayer) -> Unit, onBack: () -> Unit) {
    var playlist by remember { mutableStateOf<org.schabi.newpipe.extractor.playlist.PlaylistInfo?>(null) }
    var playlistItems by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    if (selectedIndex != null) {
        val index = selectedIndex!!.coerceIn(0, playlistItems.lastIndex)
        VideoScreen(activity, playlistItems[index].url, codec, audioCodec, quality, onPlayer, playlistItems, index, { selectedIndex = it }) { selectedIndex = null }
        return
    }
    LaunchedEffect(url) {
        val id = (Regex("[?&]list=([^&]+)").find(url)?.groupValues?.get(1) ?: url.substringAfterLast('/')).removePrefix("VL")
        runCatching { withContext(Dispatchers.IO) { YouTubeRepository.playlistVideos(id) } }
            .onSuccess { playlistItems = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }
        loading = false
        runCatching { withContext(Dispatchers.IO) { org.schabi.newpipe.extractor.playlist.PlaylistInfo.getInfo("https://www.youtube.com/playlist?list=$id") } }
            .onSuccess { playlist = it }
    }
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
            items(playlistItems.size) { index -> val item = playlistItems[index]; Row(Modifier.fillMaxWidth().clickable { selectedIndex = index }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text("${index + 1}", Modifier.width(34.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); AsyncImage(item.thumbnail, null, Modifier.size(128.dp, 72.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop); Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(item.title, maxLines = 2, style = MaterialTheme.typography.titleSmall); Text(item.subtitle, maxLines = 1, style = MaterialTheme.typography.bodySmall) } } }
        }
    }
}

@Composable
private fun ChannelScreen(activity: MainActivity, url: String, codec: CodecChoice, audioCodec: AudioCodecChoice = AudioCodecChoice.MP4A, quality: QualityChoice, onPlayer: (ExoPlayer) -> Unit, onBack: () -> Unit) {
    var channel by remember { mutableStateOf<org.schabi.newpipe.extractor.channel.ChannelInfo?>(null) }
    var channelItems by remember { mutableStateOf<List<InfoItem>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    if (selected != null) { VideoScreen(activity, selected!!, codec, audioCodec, quality, onPlayer) { selected = null }; return }
    LaunchedEffect(url) { runCatching { withContext(Dispatchers.IO) {
        val loaded = org.schabi.newpipe.extractor.channel.ChannelInfo.getInfo(url)
        val items = loaded.tabs.firstOrNull()?.let { org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo.getInfo(NewPipe.getService(0), it).relatedItems }.orEmpty()
        loaded to items
    } }.onSuccess { (loaded, items) -> channel = loaded; channelItems = items }.onFailure { error = it.message } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }; Text("Channel", style = MaterialTheme.typography.headlineMedium) }
        if (channel == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        channel?.let { data ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                data.avatars.lastOrNull()?.url?.let { AsyncImage(it, null, Modifier.size(104.dp).clip(CircleShape), contentScale = ContentScale.Crop) }
                Text(data.name, style = MaterialTheme.typography.headlineSmall)
                if (data.subscriberCount >= 0) Text("${data.subscriberCount} subscribers", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(data.description.orEmpty(), maxLines = 5, style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn { items(channelItems) { item -> ResultRow(item) { if (item.infoType == InfoItem.InfoType.STREAM) selected = item.url } } }
        }
    }
}

@Composable
private fun ShortsScreen(activity: MainActivity, codec: CodecChoice, audioCodec: AudioCodecChoice, quality: QualityChoice, onPlayer: (ExoPlayer) -> Unit, onPlayerMode: (Boolean) -> Unit) {
    var shorts by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
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
    VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
        ShortPlayer(activity, shorts[page], page == pager.currentPage, codec, audioCodec, quality, onPlayer)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShortPlayer(activity: MainActivity, item: FeedItem, active: Boolean, codec: CodecChoice, audioCodec: AudioCodecChoice, quality: QualityChoice, onPlayer: (ExoPlayer) -> Unit) {
    val context = LocalContext.current
    val player = remember(item.id) { bufferedPlayer(context).also(onPlayer) }
    var streams by remember(item.id) { mutableStateOf<List<PlayerChoice>>(emptyList()) }
    var selected by remember(item.id) { mutableStateOf<PlayerChoice?>(null) }
    var audio by remember(item.id) { mutableStateOf<PlayerChoice?>(null) }
    var audioTracks by remember(item.id) { mutableStateOf<List<PlayerChoice>>(emptyList()) }
    var preferredAudioCodec by remember(item.id, audioCodec) { mutableStateOf(audioCodec) }
    var shortInfo by remember(item.id) { mutableStateOf<StreamInfo?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var expandedTitle by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    var comments by remember(item.id) { mutableStateOf<List<CommentsInfoItem>>(emptyList()) }
    var commentsLoading by remember { mutableStateOf(false) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }

    fun play(stream: PlayerChoice) {
        val video = ProgressiveMediaSource.Factory(videoDataSourceFactory()).createMediaSource(MediaItem.fromUri(stream.url))
        val source = if (stream.videoOnly && audio != null) MergingMediaSource(true, video,
            ProgressiveMediaSource.Factory(videoDataSourceFactory()).createMediaSource(MediaItem.fromUri(audio!!.url))) else video
        player.setMediaSource(source); player.prepare(); player.setPlaybackSpeed(speed); player.playWhenReady = active
        selected = stream
    }
    LaunchedEffect(item.id, codec, preferredAudioCodec, quality) {
        val extracted = runCatching { withContext(Dispatchers.IO) { YouTubeRepository.playerStreams(item.id) } }
        extracted.onSuccess { result ->
            audioTracks = result.filter { it.audio }.map { PlayerChoice(it.url, it.codec, 0, 0, it.bitrate, false, it.audioTrackName, it.audioTrackId, it.originalAudio) }
            audio = selectAudioStream(audioTracks, preferredAudioCodec)
            streams = result.filter { !it.audio && it.height > 0 }.map { PlayerChoice(it.url, it.codec, it.height, it.fps, it.bitrate, it.videoOnly) }
                .distinctBy { "${it.height}-${it.fps}-${it.codec}" }.sortedByDescending { it.height }
            selectVideoStream(streams, codec, quality)?.let(::play)
        }.onFailure { error = it.message }
        runCatching { withContext(Dispatchers.IO) { StreamInfo.getInfo("https://www.youtube.com/watch?v=${item.id}") } }
            .onSuccess { shortInfo = it }
    }
    LaunchedEffect(active) { if (active) player.play() else player.pause() }
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
    DisposableEffect(player) { onDispose { player.release() } }

    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    Box(Modifier.fillMaxSize().background(ComposeColor.Black), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxHeight().then(if (tablet) Modifier.widthIn(max = 480.dp).aspectRatio(9f / 16f) else Modifier.fillMaxWidth())) {
            AndroidView(factory = { PlayerView(it).apply {
                this.player = player; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS); hideController()
            } }, update = { it.useController = false; it.hideController() }, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().clickable { if (player.isPlaying) player.pause() else player.play() })
            error?.let { Text(it, color = ComposeColor.White, modifier = Modifier.align(Alignment.Center).background(ComposeColor.Black.copy(alpha = .7f)).padding(16.dp)) }
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val shortAvatar = shortInfo?.uploaderAvatars?.lastOrNull()?.url ?: item.channelThumbnail
                        if (shortAvatar.isNotBlank()) AsyncImage(shortAvatar, null, Modifier.size(38.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(10.dp)); Text(shortInfo?.uploaderName?.takeIf { it.isNotBlank() } ?: item.subtitle.ifBlank { "YouTube" }, color = ComposeColor.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(shortInfo?.name?.takeIf { it.isNotBlank() } ?: item.title, color = ComposeColor.White, maxLines = 2, modifier = Modifier.clickable { expandedTitle = true })
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
    }
    if (showComments) {
        LaunchedEffect(item.id) {
            runCatching { withContext(Dispatchers.IO) { CommentsInfo.getInfo(item.url)?.relatedItems ?: emptyList() } }
                .onSuccess { comments = it }.onFailure { error = it.message }
            commentsLoading = false
        }
        ModalBottomSheet(onDismissRequest = { showComments = false }) {
            Text("Comments", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
            if (commentsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) { items(comments) { comment ->
                Column(Modifier.padding(20.dp, 12.dp)) {
                    Text(comment.uploaderName ?: "YouTube user", style = MaterialTheme.typography.labelLarge)
                    Text((if (Build.VERSION.SDK_INT >= 24) Html.fromHtml(comment.commentText.content, Html.FROM_HTML_MODE_LEGACY) else @Suppress("DEPRECATION") Html.fromHtml(comment.commentText.content)).toString())
                }
            } }
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
            audioTracks.distinctBy { it.url }.forEach { track ->
                val name = track.audioTrackName.ifBlank { if (track.originalAudio) "Original audio" else "Audio" }
                ListItem(headlineContent = { Text(name) }, supportingContent = { Text("${track.codec.uppercase()} • ${track.bitrate / 1000} kbps") },
                    leadingContent = { RadioButton(audio?.url == track.url, null) }, modifier = Modifier.clickable { audio = track; selected?.let(::play) })
            }
        }
    })
}

@Composable
private fun VideoScreen(activity: MainActivity, url: String, codec: CodecChoice, audioCodec: AudioCodecChoice = AudioCodecChoice.MP4A, quality: QualityChoice, onPlayer: (ExoPlayer) -> Unit, playlistItems: List<FeedItem> = emptyList(), playlistIndex: Int = -1, onPlaylistIndex: (Int) -> Unit = {}, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<StreamInfo?>(null) }
    var channelInfo by remember { mutableStateOf<ChannelInfo?>(null) }
    var comments by remember { mutableStateOf<List<CommentsInfoItem>>(emptyList()) }
    var commentText by remember { mutableStateOf("") }
    var commentStatus by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var availableStreams by remember { mutableStateOf<List<PlayerChoice>>(emptyList()) }
    var audioStreams by remember { mutableStateOf<List<PlayerChoice>>(emptyList()) }
    var selectedAudio by remember { mutableStateOf<PlayerChoice?>(null) }
    var selectedStream by remember { mutableStateOf<PlayerChoice?>(null) }
    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var playerPosition by remember { mutableLongStateOf(0L) }
    var playerBuffered by remember { mutableLongStateOf(0L) }
    var playerDuration by remember { mutableLongStateOf(0L) }
    var showPlayerSettings by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var channelUrl by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    if (channelUrl != null) { ChannelScreen(activity, channelUrl!!, codec, audioCodec, quality, onPlayer) { channelUrl = null }; return }
    val player = remember { bufferedPlayer(context).also(onPlayer) }
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
        val videoSource = ProgressiveMediaSource.Factory(videoDataSourceFactory()).createMediaSource(MediaItem.fromUri(stream.url))
        val audio = if (stream.videoOnly) selectedAudio ?: audioStreams.maxByOrNull { it.bitrate } else null
        val source = if (audio != null) {
            val audioSource = ProgressiveMediaSource.Factory(videoDataSourceFactory()).createMediaSource(MediaItem.fromUri(audio.url))
            // YouTube's separate adaptive tracks may start on very different media
            // timestamps. Align their periods so ExoPlayer can render immediately.
            MergingMediaSource(true, videoSource, audioSource)
        } else videoSource
        val position = player.currentPosition.coerceAtLeast(0L)
        player.setMediaSource(source); player.prepare(); if (position > 0) player.seekTo(position); player.setPlaybackSpeed(speed); player.playWhenReady = true
        selectedStream = stream
    }
    DisposableEffect(Unit) {
        activity.videoPlayerActive(true)
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { playbackState = state; if (state == Player.STATE_ENDED && playlistIndex >= 0 && playlistIndex < playlistItems.lastIndex) onPlaylistIndex(playlistIndex + 1) }
            override fun onIsPlayingChanged(isPlaying: Boolean) { activity.updatePipAction() }
            override fun onPlayerError(cause: PlaybackException) {
                Log.e("MaterialYT", "video player ${cause.errorCodeName}", cause)
                val is403 = cause.cause is HttpDataSource.InvalidResponseCodeException
                    || cause.cause?.message?.contains("403") == true
                    || cause.localizedMessage?.contains("403") == true
                if (is403 && availableStreams.size > 1) {
                    val fallback = availableStreams.firstOrNull { it != selectedStream }
                    if (fallback != null) {
                        availableStreams = availableStreams.filter { it != selectedStream }
                        play(fallback)
                        return
                    }
                }
                error = "${cause.errorCodeName}: ${cause.cause?.message ?: cause.localizedMessage}"
            }
        }
        player.addListener(listener)
        onDispose { activity.fullscreen(false); activity.videoPlayerActive(false); activity.visiblePlayerView(null); player.removeListener(listener); player.release() }
    }
    BackHandler(fullscreen) { fullscreen = false; activity.fullscreen(false) }
    LaunchedEffect(player) {
        while (true) {
            playerPosition = player.currentPosition.coerceAtLeast(0L)
            playerBuffered = player.bufferedPosition.coerceAtLeast(playerPosition)
            playerDuration = player.duration.coerceAtLeast(0L)
            currentPlayerView?.let(::bindPlaylistControls)
            delay(250)
        }
    }
    LaunchedEffect(url, codec, audioCodec, quality) {
        val videoId = Regex("[?&]v=([^&]+)").find(url)?.groupValues?.get(1) ?: url.substringAfterLast('/')
        player.stop(); player.clearMediaItems(); availableStreams = emptyList(); audioStreams = emptyList(); selectedStream = null; selectedAudio = null; error = null
        runCatching { withContext(Dispatchers.IO) { YouTubeRepository.playerStreams(videoId) } }.onSuccess { extracted ->
            audioStreams = extracted.filter { it.audio }.map { PlayerChoice(it.url, it.codec, 0, 0, it.bitrate, false, it.audioTrackName, it.audioTrackId, it.originalAudio) }
            selectedAudio = selectAudioStream(audioStreams, audioCodec)
            availableStreams = extracted.filter { !it.audio && it.height > 0 }.map { PlayerChoice(it.url, it.codec, it.height, it.fps, it.bitrate, it.videoOnly) }.distinctBy { "${it.height}-${it.fps}-${it.codec}" }.sortedByDescending { it.height }
            selectVideoStream(availableStreams, codec, quality)?.let { play(it) }
        }.onFailure { e ->
            AppLog.failure("player repository streams video=$videoId", e)
        }
        runCatching { withContext(Dispatchers.IO) { StreamInfo.getInfo(url) } }.onSuccess { stream ->
            info = stream
            val newPipeAudio = stream.audioStreams.filter { it.content.startsWith("http") }.map {
                val name = it.audioTrackName?.takeIf(String::isNotBlank) ?: it.audioLocale?.displayName?.takeIf(String::isNotBlank).orEmpty()
                val original = it.audioTrackType?.name?.contains("ORIGINAL", true) == true || name.contains("original", true)
                PlayerChoice(it.content, it.codec.orEmpty(), 0, 0, it.bitrate, false, name, it.audioTrackId.orEmpty(), original)
            }
            audioStreams = (audioStreams + newPipeAudio).distinctBy { "${it.audioTrackId}-${it.codec}-${it.bitrate}" }
            if (selectedAudio == null || newPipeAudio.any { it.originalAudio }) {
                selectedAudio = selectAudioStream(audioStreams, audioCodec)
            }
            if (availableStreams.isEmpty()) {
                val fallbackVideo = (stream.videoOnlyStreams + stream.videoStreams).filter { it.content.startsWith("http") }
                availableStreams = fallbackVideo.map { PlayerChoice(it.content, it.codec, it.height, it.fps, it.bitrate, it.isVideoOnly) }
                    .distinctBy { "${it.height}-${it.fps}-${it.codec}" }.sortedByDescending { it.height }
                selectVideoStream(availableStreams, codec, quality)?.let { chosen -> error = null; play(chosen) }
            }
        }.onFailure {
            if (availableStreams.isEmpty()) error = it.message
        }
        runCatching { withContext(Dispatchers.IO) { CommentsInfo.getInfo(url)?.relatedItems ?: emptyList() } }.onSuccess { comments = it }
        runCatching { withContext(Dispatchers.IO) { YouTubeRepository.channelInfo(videoId) } }.onSuccess { channelInfo = it }
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
    } else if (tablet) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1.15f).verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(ComposeColor.Black)) {
                    AndroidView({ PlayerView(it).apply { this.player = player; useController = true; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS); currentPlayerView = this; activity.visiblePlayerView(this); post { bindPlaylistControls(this) } } }, Modifier.fillMaxSize(), update = { currentPlayerView = it; activity.visiblePlayerView(it); bindPlaylistControls(it) })
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showPlayerSettings = true }) { Icon(Icons.Default.Settings, "Player settings") }
                    IconButton(onClick = { fullscreen = true; activity.fullscreen(true) }) { Icon(Icons.Default.Fullscreen, "Fullscreen") }
                    if (Build.VERSION.SDK_INT >= 26) IconButton(onClick = { activity.pip() }) { Icon(Icons.Default.PictureInPicture, "Picture in picture") }
                }
                Text(info?.name ?: "Video", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
                Text(
                    if (info == null) "" else "${info?.viewCount ?: 0} views",
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().clickable(enabled = !info?.uploaderUrl.isNullOrBlank()) { channelUrl = info?.uploaderUrl }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val avatar = channelInfo?.thumbnail?.takeIf { it.isNotBlank() } ?: info?.uploaderAvatars?.lastOrNull()?.url
                    if (!avatar.isNullOrBlank()) AsyncImage(avatar, null, Modifier.size(42.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    else Surface(Modifier.size(42.dp), CircleShape, color = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Default.Person, null, Modifier.padding(9.dp)) }
                    Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                        Text(channelInfo?.name?.takeUnless { it.equals("Unknown channel", true) } ?: info?.uploaderName?.takeUnless { it.equals("Unknown channel", true) } ?: "Loading channel…", style = MaterialTheme.typography.titleMedium)
                        val subscribers = info?.uploaderSubscriberCount ?: -1
                        if (subscribers >= 0) Text("$subscribers subscribers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            Column(Modifier.weight(0.85f).fillMaxHeight()) {
                Text("Comments", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(comments) { comment ->
                        Column(Modifier.padding(16.dp, 10.dp)) {
                            Text(comment.uploaderName ?: "YouTube user", style = MaterialTheme.typography.labelLarge)
                            Text((if (Build.VERSION.SDK_INT >= 24) Html.fromHtml(comment.commentText.content, Html.FROM_HTML_MODE_LEGACY) else @Suppress("DEPRECATION") Html.fromHtml(comment.commentText.content)).toString())
                            Text(comment.textualLikeCount ?: "", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    } else LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(ComposeColor.Black)) {
                AndroidView({ PlayerView(it).apply { this.player = player; useController = true; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS); currentPlayerView = this; activity.visiblePlayerView(this); post { bindPlaylistControls(this) } } }, Modifier.fillMaxSize(), update = { currentPlayerView = it; activity.visiblePlayerView(it); bindPlaylistControls(it) })
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showPlayerSettings = true }) { Icon(Icons.Default.Settings, "Player settings") }
                IconButton(onClick = { fullscreen = true; activity.fullscreen(true) }) { Icon(Icons.Default.Fullscreen, "Fullscreen") }
                if (Build.VERSION.SDK_INT >= 26) IconButton(onClick = { activity.pip() }) { Icon(Icons.Default.PictureInPicture, "Picture in picture") }
            }
            Text(info?.name ?: "Video", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
            Text(
                if (info == null) "" else "${info?.viewCount ?: 0} views",
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().clickable(enabled = !info?.uploaderUrl.isNullOrBlank()) { channelUrl = info?.uploaderUrl }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val avatar = channelInfo?.thumbnail?.takeIf { it.isNotBlank() } ?: info?.uploaderAvatars?.lastOrNull()?.url
                if (!avatar.isNullOrBlank()) AsyncImage(avatar, null, Modifier.size(42.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                else Surface(Modifier.size(42.dp), CircleShape, color = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Default.Person, null, Modifier.padding(9.dp)) }
                Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                    Text(channelInfo?.name?.takeUnless { it.equals("Unknown channel", true) } ?: info?.uploaderName?.takeUnless { it.equals("Unknown channel", true) } ?: "Loading channel…", style = MaterialTheme.typography.titleMedium)
                    val subscribers = info?.uploaderSubscriberCount ?: -1
                    if (subscribers >= 0) Text("$subscribers subscribers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            Text("Comments", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
        }
        items(comments) { comment ->
            Column(Modifier.padding(16.dp, 10.dp)) { Text(comment.uploaderName ?: "YouTube user", style = MaterialTheme.typography.labelLarge); Text((if (Build.VERSION.SDK_INT >= 24) Html.fromHtml(comment.commentText.content, Html.FROM_HTML_MODE_LEGACY) else @Suppress("DEPRECATION") Html.fromHtml(comment.commentText.content)).toString()); Text(comment.textualLikeCount ?: "", style = MaterialTheme.typography.bodySmall) }
        }
    }
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
            audioStreams.distinctBy { it.url }.forEach { audio ->
                val bitrate = if (audio.bitrate > 0) "${audio.bitrate / 1000} kbps" else "Unknown bitrate"
                val trackLabel = audio.audioTrackName.ifBlank { "Audio track" }
                ListItem(headlineContent = { Text(trackLabel) }, supportingContent = { Text("${audio.codec.uppercase()} • $bitrate") }, leadingContent = { RadioButton(selectedAudio?.url == audio.url, onClick = null) }, modifier = Modifier.clickable { selectedAudio = audio; selectedStream?.let(::play) })
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
private fun AccountScreen(activity: MainActivity, theme: ThemeMode, color: AppColor, codec: CodecChoice, audioCodec: AudioCodecChoice, quality: QualityChoice, decoderMode: DecoderMode, onTheme: (ThemeMode) -> Unit, onColor: (AppColor) -> Unit, onCodec: (CodecChoice) -> Unit, onAudioCodec: (AudioCodecChoice) -> Unit, onQuality: (QualityChoice) -> Unit, onDecoderMode: (DecoderMode) -> Unit, onPlayer: (ExoPlayer) -> Unit, onAuth: (Boolean) -> Unit) {
    var destination by remember { mutableStateOf("account") }
    var signedIn by remember { mutableStateOf(YouTubeRepository.signedIn()) }
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
        item { ChoiceSection("Theme", ThemeMode.entries, theme, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, onTheme) }
        item { ChoiceSection("Material color", AppColor.entries, color, { it.title }, onColor) }
        item { ChoiceSection("Preferred video codec", CodecChoice.entries, codec, { it.label }, onCodec) }
        item { ChoiceSection("Preferred audio codec", AudioCodecChoice.entries, audioCodec, { it.label }, onAudioCodec) }
        item { ChoiceSection("Default video quality", QualityChoice.entries, quality, { it.label }, onQuality) }
        item { ChoiceSection("Video decoder", DecoderMode.entries, decoderMode, { it.label }, onDecoderMode) }
        item { ChoiceSection("Backend HTTP", HttpBackend.Mode.entries, HttpBackend.mode, { it.label }, { HttpBackend.setMode(it) }) }
        item { Text("HTTP/1.0 compatibility disables connection reuse but uses an HTTP/1.1 request line because OkHttp intentionally cannot emit HTTP/1.0. The HTTP/2 mode advertises HTTP/2 with HTTP/1.1 fallback.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun <T> ChoiceSection(title: String, values: Iterable<T>, selected: T, label: (T) -> String, select: (T) -> Unit) {
    Column { Text(title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(6.dp)); LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(values.toList()) { value -> FilterChip(selected == value, { select(value) }, { Text(label(value), maxLines = 1) }) } } }
}
