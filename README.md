# MaterialYT

<p align="center">
  <strong>A native Material 3 client for YouTube and YouTube Music on Android 5.0+</strong>
</p>

<p align="center">
  <a href="https://github.com/x1colegal/MaterialYT/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/x1colegal/MaterialYT?display_name=tag&sort=semver"></a>
  <a href="CHANGELOG.md"><img alt="Changelog" src="https://img.shields.io/badge/Changelog-active-2EA44F"></a>
  <img alt="Android 5.0+" src="https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Material 3" src="https://img.shields.io/badge/UI-Material%203-6750A4">
  <a href="LICENSE"><img alt="MIT License" src="https://img.shields.io/badge/License-MIT-yellow.svg"></a>
</p>

MaterialYT combines YouTube, YouTube Music, Shorts, account-aware feeds, native playback, and Android Auto in one Jetpack Compose application. It uses YouTube's internal web responses, a native SABR/UMP transport, an embedded NewPipe Extractor compatibility backend, OkHttp, and ExoPlayer. No YouTube Data API key is required.

The app renders its own native interface. Only Google sign-in is displayed through Android System WebView.

> [!IMPORTANT]
> MaterialYT is an independent project. It is not affiliated with, endorsed by, or sponsored by Google, YouTube, or NewPipe. YouTube and YouTube Music are trademarks of Google LLC. YouTube's private interfaces can change without notice and may temporarily break extraction or account features.

## Highlights

- Native Material 3 interface for Android 5.0 and newer
- YouTube Home, History, search, channels, playlists, comments, and Community Posts
- YouTube Music Home, search, foreground playback, synchronized lyrics, and Android Auto
- Vertical Shorts player with continuation-based recommendations
- Native SABR/UMP playback with independently selectable audio and video formats
- Embedded NewPipe Extractor as an optional compatibility backend
- Signed-in feeds and YouTube History reporting without a Data API key
- Fullscreen, picture-in-picture, persistent miniplayers, and large buffering
- Phone, tablet, Android Auto, and legacy Android support

## YouTube

- Personalized Home feed for signed-in accounts
- History reading and watch-progress reporting
- Resume partially watched History videos from YouTube's saved position
- Restart completed History videos from `00:00`
- Infinite Home and search pagination while YouTube provides continuations
- Search results for videos, channels, and playlists
- Channel pages with Videos, Shorts, and Community Posts
- Playlist pages with playable queues and previous/next controls
- Native comments and replies
- Community Post content and comments
- Video details with description, views, likes, and publication date when available
- Account channel and playlist access

The regular YouTube player keeps playing in a miniplayer while the user changes tabs. History resume is intentionally limited to regular videos; YouTube Music and Shorts always start from their normal playback position.

## YouTube Music

- Music-focused Home and search screens
- Persistent foreground playback service
- Played and buffered progress with seeking
- Timed lyrics when synchronized data is available
- Artwork-aware layouts and native metadata
- Preferred MP4A or Opus audio codec
- Original audio selected by default, with manual track selection
- Android media notification and system Now Playing integration
- Miniplayer with play/pause, close, swipe-to-dismiss, and scrolling metadata
- Listening activity reported to the signed-in YouTube History

## Android Auto

MaterialYT exposes YouTube Music through Android's media browser APIs. Android Auto can browse available music, start playback, display artwork and metadata, and control the active session.

The music service requests media audio focus, runs as a foreground playback service, and keeps CPU and network access available during preparation and playback. Playback started from Android Auto continues when the phone is locked without requiring the MaterialYT activity to be opened first.

Availability of sideloaded media apps depends on the Android Auto version, phone, vehicle or head unit, and platform developer settings.

## Shorts

- Native vertical player without ExoPlayer's standard controller UI
- Continuation-based recommendation feed
- Automatic advance when the next recommendation becomes available
- Comments, channel navigation, playback settings, and video details
- Audio-track selection and account History reporting
- The same SABR and compatibility backend infrastructure used by regular playback

Shorts relies on frequently changing internal Reel endpoints. Temporary feed or continuation breakage is possible when YouTube changes those responses.

## Playback

- Native WEB + PoToken + SABR/UMP playback by default
- WEB, VISIONOS, ANDROID_VR, and WEB_REMIX client handling where required
- Separate adaptive audio and video selection
- AVC, HEVC, VP9, and AV1 video preferences
- MP4A and Opus audio preferences
- Resolution, frame rate, codec, and bitrate information
- Manual quality and playback-speed controls
- Automatic, hardware, or software decoding preferences
- Decoder recreation at the current position after supported runtime decoder failures
- Fullscreen immersive playback
- Android 8.0+ picture-in-picture with play/pause actions
- Automatic PiP when leaving an active regular video
- Display-aspect-aware PiP windows
- Disk-backed SABR reassembly and large forward buffering

### Playback backends

| Mode | Behavior |
| --- | --- |
| **SABR (Recommended)** | Uses the native WEB + PoToken + SABR path and its client fallbacks. This is the default. |
| **SABR + NewPipe fallback** | Uses SABR first and tries NewPipe if SABR extraction fails. |
| **Force NewPipe** | Uses only the embedded NewPipe Extractor path. |
| **NewPipe + SABR fallback** | Tries NewPipe first and falls back to SABR. |

NewPipe mode may receive `LOGIN_REQUIRED`, bot-verification responses, incomplete format lists, or extractor failures after server changes. Available formats also depend on the video, account, region, playback client, and device decoder support.

## Account integration

Google sign-in is completed on Google's HTTPS pages inside Android System WebView. MaterialYT then reuses the locally stored YouTube session cookies for authenticated internal requests.

A signed-in session enables features such as:

- Personalized Home and YouTube Music data
- Reading and updating YouTube History
- Account channel and playlists
- Community content and comments
- Additional metadata exposed only to the active account

MaterialYT does not request or store the Google password in its native UI. Clearing app storage removes local cookies, preferences, and the account session. If the session expires, the app asks the user to refresh sign-in instead of silently treating the account as logged out.

## Appearance and device support

- Material 3 with Jetpack Compose
- System, Light, Dark, and OLED Pure Black themes
- Multiple high-contrast Material color palettes
- Black status bar in Light mode
- Responsive phone and tablet layouts
- Tablet grids, navigation rail, and two-pane players
- Legacy launcher resources for Android 5–7
- Adaptive launcher icons on modern Android versions

## Networking

MaterialYT uses OkHttp for backend and media traffic. The Settings screen provides three protocol modes:

- **HTTP/2** with HTTP/1.1 fallback
- **HTTP/1.1** only
- **HTTP/1.0 compatibility**, using HTTP/1.1 connection-close behavior because OkHttp does not emit HTTP/1.0 request lines

Network protocol selection and playback backend selection are independent settings.

## Technology

| Area | Implementation |
| --- | --- |
| Language | Kotlin and Java |
| Interface | Jetpack Compose and Material 3 |
| Playback | ExoPlayer 2.19.1 |
| Primary transport | Native SABR/UMP with disk-backed reassembly |
| Compatibility extraction | Embedded, modified NewPipe Extractor module |
| Networking | OkHttp 5 |
| Images | Coil |
| JavaScript challenges | yt-dlp EJS solver assets executed through J2V8 |
| Car integration | AndroidX Media browser service |
| Minimum SDK | API 21 / Android 5.0 |
| Target SDK | API 36 |

### Source layout

```text
app/src/main/java/com/x1colegal/materialyt/
├── AutoMusicService.kt       # Music playback, notification, and Android Auto
├── EjsChallengeSolver.kt     # Signature and n-challenge execution
├── HttpBackend.kt            # OkHttp and selectable HTTP modes
├── MainActivity.kt           # Compose UI, navigation, feeds, and players
├── MaterialYtApp.kt          # Application and extractor initialization
├── NewPipePoTokenProvider.kt # BotGuard WebView and PoToken generation
├── PlaybackBackend.kt        # Playback backend preferences
├── PlayerFactory.kt          # ExoPlayer and buffering configuration
├── SabrMediaFactory.kt       # SABR media sources for ExoPlayer
├── YouTubeRepository.kt      # Feeds, account data, metadata, and tracking
└── sabrmodern/               # UMP parsing, SABR sessions, and buffering

newpipe-extractor/             # Embedded NewPipe Extractor fork
sabr/                          # SABR protocol module
```

## Building

### Requirements

- JDK 17
- Android SDK Platform 36
- A configured Android SDK path
- Internet access for initial Gradle dependencies and pinned EJS assets

```bash
git clone https://github.com/x1colegal/MaterialYT.git
cd MaterialYT
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew --daemon :app:assembleRelease
```

The release APK is generated in `app/build/outputs/apk/release/`.

Release builds currently use the configured debug signing key. Fork maintainers should configure and protect their own release keystore before distributing builds.

## Known limitations

- YouTube's player clients, internal APIs, continuations, BotGuard challenges, and SABR messages are undocumented and can change at any time.
- Some content may work through one playback client or backend but not another.
- Comments, lyrics, likes, audio tracks, playlists, and account metadata are shown only when YouTube exposes them to the current session.
- PiP requires Android 8.0 or newer.
- Codec and hardware-decoder availability depends on the device.
- Android Auto visibility for sideloaded builds depends on platform policy.
- Casting, downloads, subscriptions, and SponsorBlock are not currently implemented.

## Contributing

Issues and pull requests are welcome. Keep source code, user-facing text, documentation, commits, changelogs, and release notes in English.

A useful bug report includes:

- MaterialYT version
- Android and System WebView versions
- Selected playback backend and network mode
- Selected quality, codecs, and decoder mode
- Sign-in state
- Reproducible video URL or ID
- Relevant logs with private account information removed

## License

MaterialYT's original source code is licensed under the [MIT License](LICENSE).

The embedded [NewPipe Extractor](newpipe-extractor/) module is licensed under GPL-3.0-or-later. Distributed binaries and forks must comply with that license and the licenses of all included dependencies.

---

Developed with Antigravity + Codex — most of the project was developed with Codex.
