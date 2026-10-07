# MaterialYT

[![Android 5.0+](https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Material 3](https://img.shields.io/badge/UI-Material_3-6750A4)](https://m3.material.io/)
[![Latest release](https://img.shields.io/github/v/release/x1colegal/MaterialYT?display_name=tag&sort=semver)](https://github.com/x1colegal/MaterialYT/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

MaterialYT is a native YouTube and YouTube Music client for Android 5.0 and newer. It combines a responsive Material 3 interface, native SABR playback, an embedded NewPipe Extractor compatibility backend, authenticated YouTube sessions, OkHttp, and ExoPlayer without requiring a YouTube Data API key.

Only Google sign-in is presented as a web page. Home, History, search, channels, playlists, Community Posts, comments, YouTube Music, lyrics, settings, and playback are rendered by the app's native Compose interface.

> [!IMPORTANT]
> MaterialYT is an independent project and is not affiliated with, endorsed by, or sponsored by Google, YouTube, or NewPipe. YouTube and YouTube Music are trademarks of Google LLC. YouTube frequently changes its private interfaces, so extraction features can require maintenance without notice.

## Features

### YouTube

- Personalized Home and History feeds for signed-in accounts
- Infinite Home, search, and channel video pagination where YouTube supplies continuations
- Native search results for videos, channels, and playlists
- Channel pages with Videos and Community Posts
- Playlist pages with queue-aware previous and next controls
- Signed-in account channel and playlist access
- Native video comments, replies, and Community Post comments
- Video details including description, views, likes, and publication date when exposed by YouTube
- Watch-time reporting to the signed-in YouTube history

### YouTube Music

- Music-oriented Home and search interfaces
- Persistent foreground playback service
- Miniplayer with playback controls, animated scrolling metadata, close button, and swipe-to-dismiss
- Android media notification and system Now Playing integration
- Android Auto browsing, playback, queue controls, and metadata
- Real artwork aspect ratios in lists
- Seekable playback timeline with played and buffered progress
- Timed lyrics when YouTube Music exposes synchronized lyric data
- Music listening reported through the YouTube account history

### Shorts

> [!NOTE]
> Shorts support is currently hidden because its upstream extraction flow is unstable. It will be reintroduced after its feed and playback behavior are reliable again.

The retained Shorts player already uses the same SABR playback pipeline as regular videos and YouTube Music; only its feed and public UI remain disabled.

### Playback

- Fullscreen playback with immersive system bars
- Android 8.0+ picture-in-picture with play and pause actions
- Automatic PiP when leaving the app during video playback
- PiP aspect ratio matched to the active video
- Video miniplayer that keeps playback alive while browsing
- Large forward buffer with visible buffered progress
- Playback speed and manual quality selection
- Resolution, frame rate, codec, and bitrate information
- Preferred AVC, HEVC, VP9, or AV1 video codec
- Preferred MP4A or Opus audio codec
- Original audio track selection by default, with manual alternate-track selection
- Automatic, hardware, or software decoding preferences for video and audio

### Appearance and devices

- Material 3 interface written with Jetpack Compose
- System, Light, Dark, and OLED Pure Black themes
- Multiple strong Material color palettes
- Black status bar in Light mode
- Responsive phone and tablet layouts
- Tablet grids, two-pane players, and navigation rail
- Legacy launcher resources for Android 5–7 and adaptive icons for modern Android versions

## Playback backends

MaterialYT provides three explicit playback modes. SABR is the default and recommended option:

| Mode | Behavior |
| --- | --- |
| **SABR (Recommended)** | Uses native VISIONOS playback with adaptive, independently selectable audio and video formats. |
| **Force NewPipe** | Uses only the embedded NewPipe Extractor compatibility path. It may encounter `LOGIN_REQUIRED`, bot-verification responses, missing formats, or breakage when YouTube changes extractor-facing clients. |
| **NewPipe + SABR fallback** | Tries NewPipe first, then switches to SABR when NewPipe extraction fails. |

The SABR backend parses YouTube's UMP media stream, keeps audio and video selection independent, refreshes playback configuration when requested by the server, and uses disk-backed buffering. The embedded extractor remains available for compatibility and experimentation, but is not the recommended backend. Format availability still depends on the selected backend, the video, YouTube's current server behavior, account restrictions, region, and device decoder support.

## Networking

MaterialYT uses OkHttp for its native backend and media transport. The Settings screen provides:

- **HTTP/2** with HTTP/1.1 fallback
- **HTTP/1.1** only
- **HTTP/1.0 compatibility**, implemented with HTTP/1.1 connection-close semantics because OkHttp does not emit HTTP/1.0 request lines

Backend selection and HTTP protocol selection are separate settings. Changing the network protocol does not silently change the playback backend.

## Google account integration

The Account screen opens Google's HTTPS sign-in flow inside Android System WebView. After login, MaterialYT reuses the locally stored YouTube session cookies for authenticated internal requests.

The signed-in session powers features such as:

- Home recommendations
- History reading and watch-time updates
- YouTube Music personalization
- Account channel and playlists
- Community content and comments
- Comment posting where YouTube grants permission

MaterialYT does not collect the Google password in its native UI. Clearing the application's storage removes the local cookies, preferences, and account session. Google may reject embedded sign-in on particular accounts, WebView versions, or devices.

## Android Auto

`AutoMusicService` exposes YouTube Music through Android's media browser APIs. Supported surfaces can display the current track, artwork, playback state, queue navigation, and transport controls.

Android Auto availability depends on the Android Auto version, the phone, the vehicle or head unit, and whether externally distributed media applications are allowed by the platform configuration.

## Architecture

| Area | Implementation |
| --- | --- |
| Language | Kotlin and Java |
| UI | Jetpack Compose and Material 3 |
| Default playback | Native VISIONOS player request with SABR/UMP media transport |
| Compatibility extraction | Embedded NewPipe Extractor fork |
| Authenticated data | Internal YouTube web responses using the local WebView cookie session |
| Networking | OkHttp |
| Playback | ExoPlayer with OkHttp media data sources |
| Images | Coil |
| JavaScript challenges | yt-dlp EJS challenge solver assets with pinned SHA-256 hashes |
| Car integration | AndroidX Media browser service |
| Minimum Android version | Android 5.0 / API 21 |
| Target SDK | Android API 36 |

### Main source files

```text
app/src/main/java/com/x1colegal/materialyt/
├── AutoMusicService.kt       # Music service, Android Auto, notification, and queue state
├── EjsChallengeSolver.kt     # Signature and n-challenge JavaScript execution
├── HttpBackend.kt            # NewPipe downloader and selectable OkHttp protocol mode
├── MainActivity.kt           # Compose navigation, feeds, players, comments, and settings
├── MaterialYtApp.kt          # Application and extractor initialization
├── NewPipePoTokenProvider.kt # Local BotGuard WebView and PoToken generation
├── PlaybackBackend.kt        # Explicit SABR and NewPipe backend preferences
├── SabrMediaFactory.kt       # ExoPlayer media source for separate SABR audio and video
├── sabrmodern/               # SABR session, UMP parsing, requests, and buffering
└── YouTubeRepository.kt      # Authenticated feeds, music, metadata, history, and comments

newpipe-extractor/             # Embedded and modified NewPipe Extractor module
```

## Building

### Requirements

- JDK 17
- Android SDK Platform 36
- A configured Android SDK path
- Internet access for the initial Gradle dependency and EJS asset download

```bash
git clone https://github.com/x1colegal/MaterialYT.git
cd MaterialYT
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew --daemon :app:assembleRelease
```

The release APK is generated under:

```text
app/build/outputs/apk/release/
```

Release builds currently use the configured debug signing key. Anyone distributing a fork should configure and protect their own release keystore.

## Known limitations

- YouTube's internal APIs, player clients, continuation formats, BotGuard challenges, and stream requirements are undocumented and can change at any time.
- Some videos may be unavailable on one playback client but available on another.
- NewPipe compatibility mode may receive `LOGIN_REQUIRED`, bot-verification responses, incomplete format lists, or extraction failures after YouTube changes its private player behavior.
- Comments, playlists, account pages, lyrics, audio tracks, likes, and other metadata appear only when YouTube returns them to the current session.
- PiP requires Android 8.0 or newer.
- Codec and hardware-decoder support depend on the Android device.
- Android Auto visibility for a sideloaded build depends on platform policy and developer settings.
- Casting, downloads, subscriptions, and SponsorBlock are not currently implemented.

## Contributing

Issues and pull requests are welcome. Keep source code, user-facing app text, documentation, commit messages, changelogs, and release notes in English.

Useful bug reports include:

- MaterialYT version
- Android and System WebView versions
- Selected playback backend and HTTP mode
- Selected codec, quality, and decoder mode
- Whether the account is signed in
- A reproducible YouTube URL or video ID
- Relevant log output with private account data removed

## License

MaterialYT's original source code is available under the [MIT License](LICENSE).

The embedded [NewPipe Extractor](newpipe-extractor/) module is licensed under GPL-3.0-or-later. Combined binaries and redistributed builds must also comply with the licenses of NewPipe Extractor and all other included dependencies.

---

Developed with Antigravity + Codex — most of the project was developed with Codex.
