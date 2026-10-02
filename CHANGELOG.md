# Changelog

All notable changes to MaterialYT are documented in this file.

## [1.2.6] - 2026-10-02

### Added

- Native grid layouts for videos and results on tablets.
- Added larger, scaled-up MiniPlayer for tablet devices.

### Fixed

- Fixed Android 7 (API 24) crash when loading comments due to missing `toUnmodifiableList` Java API by adding core library desugaring.
- Fixed keyboard getting stuck when opening search on tablets by adding IME search action support.

### Fixed

- Fixed YT Music failing to play by also applying the NewPipe stream extraction fallback to the music service and legacy music player.

### Changed

- Made NewPipe stream extraction the primary player option, falling back to internal API extraction instead of the other way around.

### Fixed

- Fixed Shorts playback failing silently when internal YouTube players fail due to bot attestation. Shorts now fall back to NewPipe stream extraction just like standard videos.
- Decreased the font size of the `NavigationBar` items so they don't clip off the bottom edge of the screen.
- Fixed an issue where channel avatars in search results wouldn't load because they lacked an HTTP protocol prefix.

## [1.2.2] - 2026-10-02

### Fixed

- Fixed bot attestation (`LOGIN_REQUIRED: Sign in to confirm you're not a bot`) block on video streams by passing authentication headers to internal mobile players.
- Re-added fallback to `IOS` and `ANDROID` clients when the primary player fails.
- Fixed NewPipe error masking when internal clients fail.

## [1.2.1] - 2026-10-02

### Fixed

- Rely exclusively on VisionOS player client, removing unstable iOS player fallback that triggered `LOGIN_REQUIRED: Sign in to confirm you're not a robot`.
- Filtered ads ("Sponsored", "Patrocinado", "Anúncio", and ad renderers) from history, Shorts, and search feeds.
- Channel items in search results now render with dedicated circular avatar cards and channel info rather than 16:9 video thumbnails.
- Subscriber and view counts now format exact integers for 1-999 and dot-separated notation for 1K-999K, 1M+, and 1B+ (e.g., 5.4K, 5.2M).

## [1.2.0] - 2026-10-01

### Added

- Native Shorts player with vertical paging, responsive tablet centering, live decoder settings, and audio codec preference options.
- Embedded `yt-dlp/ejs` signature deciphering solver (`solveSig`) for videos with protected signature ciphers (e.g. kids channels and restricted content).
- Multi-client player fallback chain (`VISIONOS` -> `IOS` -> `ANDROID`) and ExoPlayer automatic stream fallback on HTTP 403 or data source errors.
- Tablet 2-column layout for YouTube Music (cover art & playback controls on left, synchronized lyrics on right) and 2-column layout for standard video player (player & metadata on left, comments on right).
- Responsive 2-column grid for tablet Music track lists and left-aligned Material 3 `NavigationRail`.

### Changed

- Stream resolution now drops base URLs without valid deobfuscated signatures instead of failing with HTTP 403 Forbidden.
- Media playback requests now provide standard web browser User-Agent, Origin, and Referer headers to ensure CDN compatibility.

### Fixed

- Fixed Shorts audio defaulting to OPUS regardless of user MP4A / AAC codec selection.
- Fixed HTTP 403 playback errors on signature-ciphered videos.
- Fixed UI layout clipping on small screens in the YouTube Music player.

## [1.1.0] - 2026-09-29

### Added

- Native signed-in Home, History, playlists, channels, and YouTube Music experiences.
- Playback watch-time reporting for account history.
- YouTube Music search, synchronized lyrics, persistent playback, media notification, miniplayer, and Android Auto integration.
- Video and audio format selection with codec, resolution, frame rate, and bitrate details.
- Manual refresh actions for Home, History, and YouTube Music.

### Changed

- Routed adaptive video and audio playback through a shared OkHttp HTTP/2 client.
- Added timestamp alignment for separately delivered video and audio tracks.
- Increased forward buffering while retaining fast playback startup.
- Refined phone, tablet, fullscreen, music-player, channel, playlist, and launcher-icon UI.
- Removed automatic quality fallback; the chosen format remains stable during playback.

### Fixed

- Fixed video startup failures caused by unaligned adaptive-track timestamps.
- Fixed authenticated feeds, history parsing, channel names, playlists, artwork ratios, and HTML leaking into comments.
- Fixed Android Auto playback synchronization and Android media-session metadata.

## [1.0.0] - 2026-09-27

### Added

- Material 3 navigation for Home, YT Music, and Account.
- NewPipe Extractor search, stream discovery, and comment extraction.
- OkHttp backend controls for HTTP/1.0 compatibility, HTTP/1.1, and HTTP/2.
- Native video playback with codec preferences, fullscreen mode, and picture-in-picture on Android 8+.
- Google login WebView with native authenticated Home, History, YT Music, playback, and comment posting.
- System, Light, Dark, and OLED Pure Black themes.
- Twelve selectable Material color palettes with responsive single-line controls.
- Android 5.0+ support.
