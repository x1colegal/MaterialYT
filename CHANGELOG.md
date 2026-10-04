# Changelog

All notable changes to MaterialYT are documented in this file.

## [1.4.25] - 2026-10-04

### Changed
- Added automatic repeating marquee animation with pauses for overflowing video and YT Music miniplayer titles and channel or artist names.
- Added explicit ellipsis overflow to titles and subtitles across Home, search, channel, playlist, Shorts, and player layouts.

## [1.4.24] - 2026-10-04

### Fixed
- Routed YT Music playback through the same chunked OkHttp datasource and client-specific headers used by regular videos, fixing HTTP 403 responses across all extracted audio candidates.

## [1.4.23] - 2026-10-04

### Fixed
- Kept the YT Music home load alive behind the player so closing a song no longer replaces active search results.
- Retried alternate extracted audio streams when a specific YT Music stream fails, while respecting the configured backend mode.
- Stopped treating legitimate videos that disclose paid content as search advertisements, preserving the actual first search result.

## [1.4.22] - 2026-10-04

### Fixed
- Preserved YT Music search results after leaving the music player and added an explicit close-search button.
- Initialized authenticated standard YouTube watch tracking for NewPipe music playback, including Android Auto, so played songs are recorded in the normal YouTube history.

## [1.4.21] - 2026-10-04

### Added
- Added a three-dot video details button showing the description, view count, and like count.

## [1.4.20] - 2026-10-04

### Fixed
- Kept playlist content visible behind the video miniplayer instead of showing a black screen.
- Preserved YouTube search results when leaving a video and added an explicit close-search button.
- Hid the app navigation bar when playlist videos enter picture-in-picture mode.

## [1.4.19] - 2026-10-03

### Fixed
- Restored VisionOS as the standard NewPipe player client to avoid iOS GVS HTTP 403 responses, with iOS used only when VisionOS rejects unavailable content such as made-for-kids videos.
- Matched media requests to the actual VisionOS or iOS User-Agent used to extract each stream, preventing client-mismatch HTTP 403 responses.

## [1.4.18] - 2026-10-03

### Fixed
- Fixed NewPipe's integrated WEB PoToken request using an anonymous HTTP context after the signed-in WEB player had already proven playable.
- Limited browser authentication injection to the NewPipe PoToken player request so the iOS extraction client remains anonymous.
- Replaced the VisionOS player client with iOS across NewPipe playback, restoring made-for-kids videos and adaptive qualities up to 1080p where available.
- Standardized NewPipe extraction on iOS without internal client fallbacks.

### Added
- Added playback backend settings for Force NewPipe, NewPipe with WEB fallback, and Force WEB.
- Disabled WEB fallback by default and documented that forced backends may be limited to 360p for some videos.
- Restricted WEB PoToken extraction to Force WEB and NewPipe with WEB fallback modes.

## [1.4.17] - 2026-10-03

### Changed
- Embedded the NewPipe Extractor source as a local Gradle module so YouTube extraction fixes can ship with MaterialYT.
- Integrated WEB PoToken acquisition into NewPipe's player fallback for regular videos, Shorts, and YT Music.
- Routed NewPipe adaptive stream URLs through MaterialYT's current EJS challenge solver.

### Fixed
- Fixed NewPipe playback failing while the WEB fallback still worked after YouTube requested bot verification.
- Preserved adaptive qualities by applying both the streaming PoToken and resolved `n` parameter to extracted URLs.

## [1.4.16] - 2026-10-03

### Fixed
- Fixed pausing YT Music terminating the app by keeping all ExoPlayer state reads on its application thread.
- Moved only the paused watch-history network report to the IO dispatcher.

## [1.4.15] - 2026-10-03

### Fixed
- Fixed the YT Music miniplayer close button and downward swipe unexpectedly terminating the app.
- Made miniplayer dismissal asynchronous and idempotent to avoid mutating ExoPlayer during Compose pointer dispatch.
- Added consistent horizontal and vertical spacing between YT Music cards on tablets.

## [1.4.14] - 2026-10-03

### Added
- Added automatic WEB PoToken session warm-up and one-time NewPipe retry after YouTube returns `LOGIN_REQUIRED`.

### Changed
- Restored NewPipe-first adaptive stream extraction for regular videos, Shorts, YT Music, and Android Auto playback.
- Kept the authenticated internal player pipeline as a fallback instead of accepting its 360p combined format first.

### Fixed
- Fixed higher qualities and codecs disappearing after a playback error.
- Fixed Shorts initially remaining paused instead of starting playback.
- Fixed comment reply threads mixing normal top-level comments into replies.

## [1.4.1] - 2026-10-02

### Fixed
- Fixed missing video metadata (title showing "Video", missing channel/views) when playing videos by extracting and using videoDetails from the YouTube player response.

## [1.4.0] - 2026-10-02

### Fixed
- Bypass broken NewPipe VISIONOS extraction in VideoScreen by prioritizing YouTubeRepository player streams.
- Optimized n-challenge and signature deciphering by lazy-caching player JavaScript.

## [1.3.9] - 2026-10-02

### Fixed
- Set `MWEB` as the primary player client ahead of `VISIONOS` to avoid `VISIONOS player response is not valid`.

## [1.3.8] - 2026-10-02

### Fixed
- Fixed signature deobfuscation failure (`Could not parse deobfuscation function`) by routing cipher solving through EJS player solver.

## [1.3.7] - 2026-10-02

### Fixed
- Fixed `VISIONOS player response is not valid` by adding authenticated `MWEB` player client to the player stream pipeline.
- Completely removed GitHub Actions CI workflow files.

## [1.3.6] - 2026-10-02

### Fixed
- Fixed bot attestation `LOGIN_REQUIRED: Sign-in to confirm you're not a robot` by injecting signed-in cookies, SAPISIDHASH authorization, and headers into all NewPipeExtractor HTTP requests.
- Integrated NewPipeExtractor stream extraction as a fallback for `playerStreams` when mobile players encounter bot verification.
- Fixed GitHub Actions CI build failure (`debug.keystore not found for signing config 'debug'`).

## [1.3.5] - 2026-10-02

### Fixed
- Fixed video miniplayer being pushed up into the middle of the screen by removing duplicate bottom padding.

## [1.3.4] - 2026-10-02

### Fixed
- Restored navigation bar and rail visibility when minimizing video or exiting PiP mode.
- Fixed transparent background on VideoScreen by wrapping container in an opaque theme Surface.
- Ensured system bars and navigation are properly restored on exiting Picture-in-Picture.

## [1.2.7] - 2026-10-02

### Fixed

- Fully fixed Android 7 (API 24) Java crash (`toUnmodifiableList`) that broke video and comment loading by manually patching the underlying extractor dependency.

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
## [1.3.3] - 2026-10-02

### Fixed
- Fixed black screen behind minimized video player by retaining the underlying feed layout when video is minimized.
- Styled video miniplayer to exactly match YT Music MiniPlayer dimensions (66dp height, 50dp rounded thumbnail box, and matching tonal elevations).
