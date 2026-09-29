# Changelog

All notable changes to MaterialYT are documented in this file.

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
