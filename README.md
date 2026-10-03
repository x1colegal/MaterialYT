# 🎬 MaterialYT

[![Android 5.0+](https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Material 3](https://img.shields.io/badge/UI-Material_3-6750A4)](https://m3.material.io/)
[![Release](https://img.shields.io/badge/release-1.2.0-FF0033)](https://github.com/x1colegal/MaterialYT/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

MaterialYT is an independent native YouTube and YouTube Music client for Android. It combines a responsive Material 3 interface, NewPipe Extractor, authenticated internal YouTube responses, OkHttp, and ExoPlayer without requiring a YouTube Data API key.

> [!IMPORTANT]
> MaterialYT is not affiliated with, endorsed by, or sponsored by Google, YouTube, or NewPipe. YouTube and YouTube Music are trademarks of Google LLC. Use the application in accordance with the services' terms and your local law.

## ✨ Highlights

- 📱 Android 5.0+ (`minSdk 21`) with legacy launcher icons and Android 8.0+ adaptive icons
- 🏠 Native personalized Home and History feeds with channels, thumbnails, and comments
- ⚡ Vertical Shorts player with live decoder options, audio codec controls, and tablet centering
- 🎵 Native YouTube Music feed, search, playback, real artwork proportions, and timed lyrics
- 🚘 Android Auto music browsing and playback through the media browser service
- 🔎 Native YouTube search powered by NewPipe Extractor
- ▶️ ExoPlayer video and music players with seeking, playback speed, fullscreen, and Android 8.0+ PiP
- 🪟 Automatic PiP when leaving the app while a video is playing
- 🎞️ H.264, HEVC, AV1, and VP9 preferences with quality, FPS, and bitrate details
- ⚡ Fast-start playback with a large forward buffer, automatic 403 fallback, and signature cipher solving
- 💬 Native comment reading and signed-in comment posting
- 👤 Google sign-in used by the internal backend for personalized feeds, playback history, and comments
- 🔄 Manual refresh actions for Home, History, and YT Music
- 🎨 System, Light, Dark, and OLED Pure Black themes
- 🌈 Strong Purple, YouTube Red, Bright Blue, Blue Cyan, Green, Orange, Pink, and Teal palettes
- 📐 Responsive tablet layouts: 2-column video & music players, 2-column music grid, and left-side navigation rail
- 🌐 Selectable HTTP/1.0 compatibility, HTTP/1.1, and HTTP/2 backend modes

## 🧱 Technology

| Area | Implementation |
| --- | --- |
| UI | Kotlin, Jetpack Compose, Material 3 |
| Extraction | NewPipe Extractor |
| HTTP | OkHttp with the authenticated WebView cookie store |
| Playback | ExoPlayer with OkHttp HTTP/2 media transport |
| Images | Coil |
| Car integration | AndroidX Media browser service |
| Authentication | Google login WebView; the rest of the app remains native |

Only Google sign-in is displayed as a web page. Home, History, YT Music, search, players, lyrics, comments, account controls, and settings are rendered by native Compose UI.

## 🎛️ Playback behavior

The player lists available formats with resolution, frame rate, codec, and bitrate. Default video quality, video codec, and audio codec can be selected in Settings. MaterialYT chooses the closest available format while prioritizing the requested codec. It never changes quality automatically during playback.

Separate adaptive video and audio tracks share an OkHttp HTTP/2 client and are timestamp-aligned before playback. Playback starts quickly while ExoPlayer continues building a large forward buffer.

YouTube Music uses its own music-oriented interface. Artwork keeps the source aspect ratio instead of being forced into a square, and synchronized lyrics follow the current playback position when timed lyric data is available.

## 🔐 Account and privacy

The Account destination opens Google's HTTPS sign-in page when requested. After a successful login, MaterialYT reuses the local YouTube session cookies for internal authenticated requests. Playback watch-time events are sent to the signed-in account so watched videos and music can participate in account history. Passwords are never collected by the native interface, and the app does not ship an OAuth client secret. Clearing app storage removes the local session and preferences.

Google can reject embedded sign-in for some accounts, devices, or WebView versions. YouTube also changes its private interfaces regularly, so extractor and request logic may require maintenance over time.

## 🚘 Android Auto

MaterialYT exposes the signed-in YouTube Music catalog and playback controls through Android Auto. Because MaterialYT is distributed outside Google Play, Android Auto must allow apps from unknown sources in its developer settings before it can appear in the launcher. Availability still depends on the phone, vehicle/head unit, and Android Auto version.

## 🌐 HTTP modes

- **HTTP/2** enables HTTP/2 with normal HTTP/1.1 fallback.
- **HTTP/1.1** restricts backend requests to HTTP/1.1.
- **HTTP/1.0 compatibility** uses HTTP/1.1 with connection-close semantics because OkHttp does not emit HTTP/1.0 request lines.

These settings apply to MaterialYT's native backend. Android System WebView is used only for Google sign-in.

## 🛠️ Building

Requirements:

- JDK 17
- Android SDK Platform 36
- Internet access for initial dependency resolution

```bash
git clone https://github.com/x1colegal/MaterialYT.git
cd MaterialYT
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug --no-daemon
```

The generated debug APK is located at `app/build/outputs/apk/debug/app-debug.apk`.

## 🗺️ Project layout

```text
app/src/main/java/com/x1colegal/materialyt/
├── AutoMusicService.kt   # Android Auto music catalog and transport controls
├── EjsChallengeSolver.kt # Embedded JavaScript challenge & signature cipher solver
├── HttpBackend.kt        # Authenticated OkHttp backend for NewPipe Extractor
├── MainActivity.kt       # Compose feeds, players, lyrics, account, and settings
├── MaterialYtApp.kt      # Application and extractor initialization
└── YouTubeRepository.kt  # Home, History, Music, streams, and comments
```

## ⚠️ Platform notes

- PiP requires Android 8.0 or newer.
- Format and codec availability depends on each video and the device's decoders.
- Android Auto sideload visibility depends on its developer options and current platform policy.
- Downloads, casting, SponsorBlock, likes, and subscriptions are not currently included.

## 🤝 Contributing

Issues and pull requests are welcome. Keep source code, app text, documentation, commits, and release notes in English. For extraction or playback reports, include the Android version, WebView version, selected HTTP mode, and a reproducible URL.

## 📄 License

MaterialYT's original source is released under the [MIT License](LICENSE). The embedded NewPipe Extractor module is licensed under [GPL-3.0-or-later](newpipe-extractor/LICENSE), and combined APK distributions are subject to the GPL terms. Other dependencies retain their respective licenses.

---

Built with Kotlin, Material 3, and far too much determination. 🚀🎧
