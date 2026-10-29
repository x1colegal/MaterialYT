# Changelog

All notable changes to MaterialYT are documented in this file.

## [4.1] - 2026-10-29

### Fixed
- Replace rejected WEB SABR PoTokens and endpoints inside the active session when stream protection requests re-attestation.

## [4.0] - 2026-10-29

### Fixed
- Resolve ambiguous player subscription controls through the authoritative channel state instead of inactive subscribe commands embedded in modern view models.

## [3.12] - 2026-10-28

### Fixed
- Bind WEB player and SABR PoTokens to the identifiers selected by YouTube's current player configuration, preventing streams from progressing from attestation pending to attestation required.

## [3.11] - 2026-10-28

### Fixed
- Restore Community Posts with the current BROWSE continuation used by post detail pages.
- Load Community comments from item sections that no longer expose the watch-page `comments-section` target identifier.
- Merge authenticated Community actions from the same public BROWSE continuation that provides complete comment entities.
- Update newly posted and edited replies immediately without requiring repeated refreshes.
- Replace optimistic Community replies with YouTube-confirmed rows so refresh and mutation controls update correctly.
- Refresh the parent Community thread before reloading replies so newly created reply continuations and View replies controls appear immediately.
- Reconcile optimistic Community comments by ownership and text, preventing duplicate local/server rows and preserving pending comments across refreshes.
- Show the actions menu immediately for newly posted comments and replies while YouTube confirms their mutation commands.
- Match optimistic Community items only against newly observed server IDs, preserving repeated comments with identical text.
- Keep optimistic Community rows in place while replacing them with confirmed server objects, and mark newly published items as owned immediately.
- Include authenticated Community rows that have not reached the public thread cache yet, exposing their real IDs and mutation commands without waiting.

## [3.10] - 2026-10-28

### Changed
- Remove the unstable Community Posts tab and its entry points from channel pages; video, Shorts, and YT Music comments remain available.

## [3.9] - 2026-10-27

### Added
- Open Community comment replies in a dedicated paginated sheet and publish authenticated replies directly from a post.

### Fixed
- Hide the Subscribe button when viewing the signed-in account's own channel.
- Merge authenticated Community reply commands with public comment entities so reply composition remains available without sacrificing complete author and text data.
- Send Community reply continuations to their declared BROWSE endpoint and accept reply-composition parameters returned when the reply thread opens.

## [3.8] - 2026-10-27

### Fixed
- Advance YT Music queues and Up Next directly inside the foreground playback service so the next track prepares and starts while the app is in the background or the screen is locked.
- Preserve Android Auto browse and search queues inside the playback service instead of depending on the activity UI to advance them.
- Load music artwork in the playback service so notifications and Android Auto receive it without opening the app UI.
- Keep Community post media separate from author avatars and load existing post comments through YouTube's current comment renderers.
- Show the signed-in channel identity for newly published Community comments.
- Load Community threads through their dedicated BROWSE continuation and request high-resolution post and profile images.
- Parse Community comment entity payloads directly so authenticated threads retain author names, avatars, and comment text.
- Support nested authenticated Community comment payloads instead of falling back to empty renderer placeholders.
- Discover Community comment entities anywhere in authenticated BROWSE responses instead of assuming a fixed mutation path.
- Decode nested serialized JSON fields in authenticated Community comment entities.
- Load public Community threads without account headers when signed-in BROWSE omits comment entity data, while keeping comment publishing authenticated.
- Bootstrap Community reading from a fully public post page so its continuation token does not inherit the signed-in entity-less response mode.
- Match Community BROWSE requests to the desktop WEB client with Origin and Referer headers so YouTube includes framework entity updates.
- Keep Community comment metadata concise by showing its publication time without an unlabeled raw like count.

### Added
- Add a dedicated Community post screen with post media, author details, comments, reload support, and authenticated comment publishing.
- Open a video's channel when its avatar is selected in the Home feed.

## [3.7] - 2026-10-26

### Fixed
- Preserve the YT Music system-bar mode after closing playback settings so the Android navigation bar stays hidden while the status bar remains visible.

## [3.6] - 2026-10-26

> **Major playback update:** MaterialYT now uses one native WEB + PoToken + SABR playback stack across regular videos, Shorts, YT Music, and Android Auto.

### Changed
- Remove the legacy compatibility extractor, its bundled source module, backend selector, dependencies, adapters, and documentation.
- Route playback, metadata, channels, playlists, search suggestions, comments, and music streams through MaterialYT's native YouTube implementation.
- Keep the standalone WEB BotGuard and PoToken runtime dedicated to native SABR playback.

### Fixed
- Restore fitted system-bar bounds after leaving fullscreen, including after orientation changes and focus transitions.
- Keep the tablet player inside the visible viewport without adding a persistent empty system-bar strip.
- Remove the orphaned playback-backend explanation from Settings.

## [3.5] - 2026-10-26

### Fixed
- Remove the global black system-bar strip from tablet pages by letting each Scaffold paint and apply its own safe insets.
- Keep tablet players edge-to-edge so the YT Music navigation no longer overlaps a separately reserved system-bar area.

## [3.4] - 2026-10-26

### Fixed
- Keep the real video title when autoplay opens a normal YouTube Up Next recommendation instead of preserving the `Up Next` section label as video metadata.

## [3.3] - 2026-10-26

> **Big update:** This release significantly expands YT Music, playlists, autoplay, account-backed search history, and playback integration across MaterialYT.

### Changed
- Rebuild the native YT Music home from the actual WEB_REMIX sections, preserving category navigation, section titles, compact song rows, and horizontal album and playlist shelves.
- Load additional YT Music home sections as the user reaches the end instead of stopping after the first two server-provided shelves.
- Remove the nonfunctional YT Music category chip bar and render server carousels, including Continue playing, as horizontally scrollable shelves.
- Keep YT Music focused on music by filtering video and UGC entries from Home, search, playlists, and Up Next queues.
- Keep YT Music carousel cards at a stable height, remove the redundant Now playing label, expose playlist results in search, and allow failed home continuations to be retried.
- Keep artist names directly below single-line carousel titles and hide YouTube's inaccessible Saved Shorts pseudo-playlist instead of exposing a permanently empty screen.
- Report YT Music playback through authenticated WEB_REMIX tracking on `music.youtube.com` so listening activity can populate Continue hearing instead of being treated only as regular YouTube playback.
- Load signed-in YouTube search history through YouTube's lightweight native suggestions service before using the hidden Google My Activity fallback.
- Match the current YouTube web client's authenticated suggestion request for faster search-screen startup.

### Added
- Add a native Continue playing card backed by the active music session, replacing the old history-shaped continuation presentation.
- Automatically play the next playlist video, or YouTube's first valid Up Next recommendation, after a visible five-second countdown.
- Keep picture-in-picture black during the video autoplay countdown instead of rendering the full countdown UI inside PiP.
- Advance immediately to the next playable YT Music playlist item, or the first valid music-only Up Next track outside a playlist, when playback ends.
- Create public, unlisted, or private playlists from the signed-in Account playlist screen.
- Save the current video to an existing account playlist directly from the player actions.
- Save Shorts and YT Music tracks to an existing account playlist from their respective players.
- Recognize authenticated library playlists as editable even when YouTube omits the legacy `isEditable` field, preserve their per-video removal tokens, and avoid treating unrelated Up Next recommendations as playlist contents.
- Load the mobile-only Saved Shorts collection through authenticated Android and MWEB playlist surfaces when the desktop WEB client returns an empty `VLYS` page.
- Delete owned playlists and remove individual videos when YouTube marks the playlist as editable.
- Open YT Music playlists in a native track-list screen and play their songs through the YT Music player.

### Fixed
- Try direct WEB browse and authenticated WEB_REMIX playlist paths when a playlist exposes no videos through its standard `VL` browse response.
- Use the YT Music `WEB_REMIX` queue for music autoplay instead of regular YouTube video recommendations.
- Reset autoplayed and playlist videos to `00:00` instead of restoring the previous video's history position.
- Keep generic search suggestions out of the signed-in search-history list.
- Include the signed-in YT Music playlist library in the native Music home screen.
- Parse the current `lockupViewModel` playlist rows used by Liked Videos, Saved Shorts, Watch Later and newly created playlists.
- Send the current `playlistId` field when deleting an owned playlist instead of the rejected legacy field.
- Allow Watch Later row removal when YouTube provides a membership token without exposing playlist deletion for system playlists.
- Recognize account and system playlist IDs exposed through YouTube's current lockup renderers.
- Follow playlist-specific continuation commands so playlist pages load their complete video list.
- Fall back to the authenticated playlist page when a private playlist returns no usable videos through the initial Innertube browse response.
- Parse `playlistVideoRenderer` entries as videos instead of accidentally treating and filtering them as nested playlist cards.
- Keep recommendations out of playlist contents so saving one YT Music track does not appear to add unrelated songs.
- Detect Shorts renderers inside playlists and open them with the native vertical Shorts player.
- Hide the app navigation bar while playing a video opened from Account playlists.
- Insert newly created playlists into the Account UI immediately without requiring a manual refresh.

## [3.2] - 2026-10-25

### Fixed
- Prevent picture-in-picture windows from inheriting a stale square or stretched aspect ratio from previous playback.
- Refresh the PiP window when ExoPlayer publishes the video's final dimensions during the Android transition.
- Preserve widescreen and ultrawide video ratios throughout Android's supported PiP range, with proportional letterboxing only beyond the platform limit.

## [3.1] - 2026-10-24

### Changed
- Replace device-local recent searches with the signed-in Google account's YouTube search history.
- Read existing YouTube searches internally from Google My Activity while keeping the web surface hidden from the app UI.
- Use native authenticated YT Music search-history renderers and feedback tokens for removal.

### Fixed
- Make Clear all operate on the YouTube account history instead of only deleting an app-local list.
- Keep authenticated YouTube and YT Music suggestions separate and sourced from their respective services.

## [3.0] - 2026-10-24

### Added
- Add separate recent-search histories to YouTube and YT Music search screens, including per-item removal and Clear all.

### Fixed
- Keep fast cross-screen feed cancellation without exposing a persistent `Canceled` error; returning to a canceled screen now starts its load again.
- Cancel superseded feed bootstrap requests as soon as the user changes screens instead of letting old homepage requests continue consuming the connection.
- Keep non-16:9 picture-in-picture letterboxing opaque so stale video frames cannot appear behind the PiP surface.

## [2.12] - 2026-10-24

### Fixed
- Match the current channel's subscription state entity instead of reading subscription data from unrelated recommended videos.

## [2.11] - 2026-10-23

### Fixed
- Remove reserved system-bar space from the tablet player while fullscreen or in picture-in-picture mode.
- Keep the picture-in-picture window at the last valid video aspect ratio during playback state changes.
- Keep feed title and channel identity stable while player metadata and formats are loading.
- Keep YT Music cards, player, notification and Android Auto on the same stable title and full artist name.
- Remove the YouTube-generated `- Topic` suffix from music artist names.
- Resolve original feed metadata before rendering so cards no longer flash translated labels.

## [2.10] - 2026-10-23

### Fixed
- Keep tablet player content and the miniplayer inside the visible viewport instead of measuring behind the system-bar areas.

## [2.9] - 2026-10-23

### Fixed
- Seed the video player metadata cache from WEB Innertube feed lookups to prevent localized titles and channel names from flashing before canonical metadata appears.

## [2.8] - 2026-10-23

### Changed
- Retrieve canonical video and channel metadata through authenticated WEB Innertube player responses instead of oEmbed.

## [2.7] - 2026-10-22

### Changed
- Move YT Music like and dislike actions into the player header to preserve space for playback controls.

## [2.6] - 2026-10-22

### Fixed
- Keep the status bar truly black with light icons in light theme across feeds and YT Music.
- Keep YT Music player navigation labels and icons readable in light theme.

### Changed
- Increase the MaterialYT header logo size.

## [2.5] - 2026-10-22

### Fixed
- Resolve the official channel name when YouTube returns a combined `@handle • subscribers` metadata label.
- Prefer primary uploader metadata instead of YouTube-translated video titles, descriptions, and channel names.
- Format ISO publication timestamps as readable English dates throughout video and Shorts interfaces.

### Changed
- Replace embedded Home and YT Music search fields with dedicated search screens and live query suggestions.
- Move search-result clearing and feed refresh actions into the main feed toolbar.

## [2.4] - 2026-10-22

### Overview
- This is a major update across nearly every part of MaterialYT, covering video and music players, Shorts, channels, playlists, comments, recommendations, account actions, responsive layouts, and metadata loading.

### Added
- Added authenticated Like and Dislike actions to videos and YT Music, and Like actions to Shorts.
- Added authenticated channel subscription controls to the phone video screen.
- Added a native related-video feed below comments.
- Added full comments, replies, pagination, and posting to the phone YT Music player.
- Added channel banners and authenticated Subscribe controls to channel pages.

### Improved
- Reworked the phone video screen around the classic YouTube watch layout with compact metadata and working action controls.
- Refresh the displayed Like count from YouTube after rating changes.
- Adapted channel and playlist pages for tablet displays.
- Moved Shorts metadata and descriptions into the title-driven details view.
- Reworked the tablet video player with watch information and actions below the video, Recommended beside it, and contextual details or comments in the side panel.
- Decoupled video metadata from playback preparation so titles and channel information appear immediately while slow tablet playback is still loading.
- Updated the tablet YT Music player with Like and Dislike actions plus switchable Lyrics and Comments panels.
- Added infinite pagination to the Recommended feed on phone and tablet video players.

### Fixed
- Fixed the phone channel header layout and prevented unrelated response fields from overriding the real subscription state.
- Copy video links directly on older Android versions whose legacy share panel has no copy action.

## [2.3] - 2026-10-21

### Fixed
- Removed duplicated status-bar spacing from tablet feeds, tabs, and the YT Music player.

## [2.2] - 2026-10-20

### Added
- Show newly posted comments and replies immediately with the data returned by YouTube.
- Added edit and delete actions for owned comments and replies when YouTube exposes the required account actions.

### Fixed
- Fixed reply editing across current and legacy YouTube InnerTube command variants.
- Keep the status bar visible and the navigation bar hidden in the YT Music player, with improved top-action placement.

## [2.1] - 2026-10-20

### Added
- Added dedicated comment reply screens with infinite pagination for comments and replies.
- Added authenticated posting for new comments and replies.
- Made the replies screen available on comments with no existing replies so the first reply can be posted.
- Prevented the reply composer from stealing focus and opening the keyboard when the replies screen appears on tablets.

## [2.0] - 2026-10-19

### Fixed
- Recovered audio-only and video SABR sessions when YouTube repeatedly returns startup context without initialization or media segments, preventing endless buffering.

## [1.5.12] - 2026-10-18

### Added
- Added playback settings to the YT Music player with the same real Audio track selection experience used by videos and Shorts.

## [1.5.11] - 2026-10-18

### Improved
- Added the full interactive video description experience to Shorts, including preserved line breaks, clickable links and timestamps, channel navigation, and link copying.

## [1.5.10] - 2026-10-18

### Fixed
- Restored sharp YT Music artwork by selecting the largest available thumbnail and requesting a high-resolution source while preserving its aspect ratio.

## [1.5.9] - 2026-10-18

### Fixed
- Kept fullscreen video edge-to-edge while moving its bottom controls safely inside rounded screens without a transparent gap.

## [1.5.8] - 2026-10-17

### Improved
- Preserved the original line breaks in video descriptions.
- Made web links, protocol-less social links, and YouTube channel paths such as `/@creator` clickable.
- Made description timestamps and chapter timestamps seek the active video to the selected position.
- Preserved YouTube's structured description targets so labelled Discord, social, membership, profile, and chapter links remain clickable.
- Added a Copy link action to video details.
- Registered MaterialYT as a handler for YouTube, YouTube Music, youtu.be, channel, playlist, Shorts, live, and embed links.
- Unwrapped YouTube redirect links before routing so Discord, Instagram, Twitter, and other external destinations open normally.
- Closed video details and suppressed automatic PiP when intentionally opening an external description link.

## [1.5.7] - 2026-10-17

### Fixed
- Kept channel names consistent in the video player when YouTube returns a combined handle and subscriber label instead of the creator name.
- Restored channel profile pictures served from YouTube's newer `yt3.googleusercontent.com` image host.
- Selected the largest available channel avatar and requested a sharper variant when YouTube only exposes a low-resolution owner image.

## [1.5.6] - 2026-10-16

### Big loading update
- Significantly reduced loading time across the YouTube Home feed, History, and YouTube Music feeds by eliminating repeated recursive renderer scans.
- Changed feed parsing to collect watch progress, resume position, watched percentage, and duration metadata in a single traversal.
- Avoided expensive channel and playlist metadata searches for regular video entries.
- Cancelled obsolete Home requests when another signed-in feed is opened, preventing overlapping background loads on slower devices.
- Added detailed feed diagnostics for response headers, body transfer, response size, parsing, and total loading time.

### Playback
- Reduced the universal ExoPlayer buffer target to improve startup and recovery behavior while retaining substantial read-ahead buffering.
- Applied faster playback start and rebuffer thresholds consistently across supported Android versions.
- Re-aimed SABR immediately when resuming a video from History and coalesced near-identical audio/video seek requests to prevent redundant session restarts.

### Improved
- Preserved native ad filtering while removing redundant full-renderer ad scans.
- Kept feed loading responsive on older Android devices without silently lowering the selected video quality.

## [1.4.47] - 2026-10-06

### Fixed
- Sent YouTube's real audio-track ID in SABR client state, fixing multi-audio videos that returned only context and policy frames before failing with an incomplete drain.
- Stopped repeated identical SABR context updates from keeping an empty response loop alive.

## [1.4.46] - 2026-10-06

### Fixed
- Kept the active audio stream visible and selected in playback settings when YouTube omits the display name of a single SABR audio track.
- Matched audio-track selection by stable track metadata instead of complete in-memory stream-object equality.

## [1.4.45] - 2026-10-06

### Fixed
- Corrected SABR initialization and format identifiers to match YouTube's current UMP protocol, including selected audio, selected video, and format tags.
- Kept VISIONOS SABR reload requests on the same native client and applied refreshed playback configuration returned by YouTube.
- Prevented unknown-length SABR formats from producing invalid media timelines.
- Added disk-backed SABR audio and video buffering for stable long-form playback.

### Added
- Added native VISIONOS SABR playback for regular videos, YouTube Music, and the retained Shorts player.
- Preserved independent resolution, FPS, video codec, audio codec, language, and bitrate choices in SABR sessions.
- Made **SABR (Recommended)** the default and first playback backend option.
- Added decoded SABR error diagnostics for response type, action, and status.

### Changed
- Renamed user-facing WEB backend labels to SABR to describe the actual media transport.

## [1.4.44] - 2026-10-05

### Fixed
- Read Community Post comment counts from YouTube's reply button renderer instead of incorrectly displaying zero for posts that have comments.

## [1.4.43] - 2026-10-05

### Fixed
- Kept the display awake while the native video player is open and restored the normal screen timeout after leaving it.
- Requested Community Posts in English and normalized localized publication times, like counts, and comment counts into English labels.

## [1.4.42] - 2026-10-05

### Changed
- Temporarily removed Shorts from the main navigation and channel pages while its extraction flow remains unstable.
- Documented that Shorts will return after its feed and playback behavior are reliable again.

## [1.4.41] - 2026-10-04

### Fixed
- Returned Home and YT Music lists to the first item when starting or closing a search on phones and tablets.
- Read the current Shorts sequence continuation fields used after the first reel batch, restoring continuous Shorts pagination.

## [1.4.40] - 2026-10-04

### Fixed
- Extracted artist-linked runs from any position in YT Music metadata, correctly handling Home rows formatted as `Song • Artist` and search rows beginning with the artist.

## [1.4.39] - 2026-10-04

### Fixed
- Normalized unstructured YT Music accessibility subtitles by removing the song title and duration from `Artist: Song • duration` fallbacks.

## [1.4.38] - 2026-10-04

### Fixed
- Parsed all YT Music artist runs up to the first metadata separator, preserving collaborations without including the song title or duration.

## [1.4.37] - 2026-10-04

### Fixed
- Prevented YT Music accessibility objects such as `Go to Artist` from being serialized as artist names.

## [1.4.36] - 2026-10-04

### Fixed
- Kept YT Music subtitles limited to the linked artist name instead of combining the artist, song title, and duration.
- Followed each Community Post comment continuation's own internal API endpoint and added diagnostics for the scraped comment pages.

## [1.4.35] - 2026-10-04

### Fixed
- Reopened the full YT Music player when tapping its miniplayer and restored the regular video player immediately from the video miniplayer.
- Matched the Android picture-in-picture window to the video's actual display aspect ratio instead of forcing 16:9, removing rounded letterbox bands from non-16:9 videos.

## [1.4.34] - 2026-10-04

### Fixed
- Restored Community Post comments by using YouTube's browse continuation endpoint and parsing the current comment entity format.
- Kept top-level Community Post pagination separate from reply continuations.

## [1.4.33] - 2026-10-04

### Fixed

## [1.4.32] - 2026-10-04

### Fixed
- Reseeded Shorts recommendations after YouTube ends a reel sequence, filtering already delivered Shorts.
- Renewed the Home recommendation request after its continuation chain ends, filtering videos already shown in the current feed.

## [1.4.31] - 2026-10-04

### Fixed
- Automatically refreshed extraction and retried YT Music playback when every stream from the first attempt failed.
- Prevented overlapping requests for the same song from replacing a newer playback preparation.

## [1.4.30] - 2026-10-04

### Fixed
- Continued Shorts from the reel item responses when the sequence envelope omits its next token.
- Changed YT Music search to the paginated Songs shelf and disabled pagination on the intentionally finite Continue listening Home surface.

## [1.4.29] - 2026-10-04

### Fixed
- Added continuous YouTube reel sequence pagination to the main Shorts feed so more Shorts load automatically near the end.
- Added continuous pagination to the YT Music Home feed and music search results on phones and tablets.

## [1.4.28] - 2026-10-04

### Fixed
- Preserved the channel page behind video playback so minimizing a channel video no longer reveals a black screen.
- Opened channel Shorts in the native vertical Shorts player instead of the regular video player.
- Replaced unavailable Community Post views with likes and added expandable real comment threads.
- Added automatic continuation loading to channel Videos and Shorts tabs.

## [1.4.27] - 2026-10-04

### Added
- Added a three-dot details panel to Shorts with views, likes, publication date, and description.
- Added Videos, Shorts, and Community Posts sections to channel pages, including post age, views, and comment counts when YouTube provides them.

### Fixed
- Made the complete channel page scroll as one surface instead of limiting scrolling to the video list.
- Restored continuous pagination for the Home feed and YouTube search results.

## [1.4.26] - 2026-10-04

### Added
- Added the video's localized publication date to the three-dot video details panel.

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
- Matched media requests to the actual VisionOS or iOS User-Agent used to extract each stream, preventing client-mismatch HTTP 403 responses.

## [1.4.18] - 2026-10-03

### Fixed

### Added
- Disabled WEB fallback by default and documented that forced backends may be limited to 360p for some videos.

## [1.4.17] - 2026-10-03

### Changed

### Fixed
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

### Changed
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


### Changed


### Fixed

- Decreased the font size of the `NavigationBar` items so they don't clip off the bottom edge of the screen.
- Fixed an issue where channel avatars in search results wouldn't load because they lacked an HTTP protocol prefix.

## [1.2.2] - 2026-10-02

### Fixed

- Fixed bot attestation (`LOGIN_REQUIRED: Sign in to confirm you're not a bot`) block on video streams by passing authentication headers to internal mobile players.
- Re-added fallback to `IOS` and `ANDROID` clients when the primary player fails.

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
