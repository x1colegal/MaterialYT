# Changelog

All notable changes to MaterialYT are documented in this file.

## [6.10] - 2026-11-13

### Added
- Add a dedicated responsive Shorts shelf to the YouTube Home and search results, opening results directly in the optimized Shorts player.
- Add an explicit back button when a Short is opened from Home or search.

### Fixed
- Preserve author profile pictures and canonical metadata when opening regular playlists, including subsequent continuation pages.
- Keep mixes free from misleading single-author profile metadata.

## [6.9] - 2026-11-13

### Changed
- Show scheduled premiere and live stream thumbnails at their original brightness instead of applying a progressive dark overlay.

## [6.8] - 2026-11-13

### Added
- Add a dedicated scheduled-event screen for premieres and scheduled live streams, with the event thumbnail, an English countdown, exact local start date and time, and a progressive thumbnail reveal during the final 24 hours.
- Show confirmed live-chat participation states for signed-out, subscribers-only, members-only, disabled, unavailable, and read-only chats.

### Fixed
- Replace unresolved and localized upcoming labels such as `Scheduled for DATE_PLACEHOLDER` with stable English UI.
- Prevent ExoPlayer controls and video output from appearing behind a scheduled event that has not started.
- Avoid transient relative publication labels such as `Started streaming 20 minutes ago` before the canonical fixed publication date is available.
- Prevent false live-chat errors while chat access is still being resolved.
- Detect completed live broadcasts as VODs, retain their duration and seeking behavior, and avoid exposing writable live chat after the broadcast ends.

## [6.7] - 2026-11-13

### Fixed
- Continue requesting and buffering every playable media segment YouTube provides while SABR stream protection is pending (`protection=2`), keeping playback alive while a fresh PoToken is generated.
- Exclude dry pending-protection responses from SABR stall limits and prevent duplicate synchronous attestation refreshes while the asynchronous challenge is running.
- Preserve the active player, playback position, and accumulated audio/video buffers throughout attestation renewal on videos, live streams, and YouTube Music.
- Reject media returned with required protection (`protection=3`) while retaining valid grace-window media delivered under `protection=2`.

## [6.6] - 2026-11-13

### Fixed
- Preserve active YouTube Music and audio-only VOD playback when SABR reports pending stream protection (`protection=2`) on slower devices.
- Pause stale SABR requests while Android System WebView generates a fresh streaming PoToken, preventing the dry-response budget from ending the session with `incomplete drain` before attestation completes.
- Install refreshed URL and protobuf PoTokens atomically, reset only proof-bound transport state, and retain the current ExoPlayer instance, playback position, and already buffered media.

## [6.5] - 2026-11-13

MaterialYT 6.5 is probably one of the largest releases in the project's history. It took approximately two days to complete, with development alternating between Codex (5.6 Sol) and Antigravity (Gemini 3.8 Flash).

### Added
- Native live stream playback via YouTube SABR (`SabrMediaFactory`), with full quality selection (Auto, 1080p, 720p, 480p, 360p, 240p, 144p), bitrate, frame rate, and codec details, with synchronized audio and live-edge playback.
- Live Chat for live streams: real-time streaming chat messages, live badges for moderators, channel owner, and sponsors, Super Chat highlighting, and chat message sending.
- "Live" tab in channel screen (`ChannelScreen`) showing past and active live streams from `/streams`.
- Premiere support with scheduled-start countdowns and live chat before and during the premiere.
- Premiere and upcoming live stream discovery: upcoming premieres and scheduled live streams now appear directly on channel views, with "PREMIERE" / "UPCOMING" badges and schedule times.

### Fixed
- Fixed premiere player opening stream before broadcast start: player now waits on upcoming screen without failing SABR extraction or spinning.
- Automatic premiere start detection: background watcher continuously monitors upcoming broadcasts and automatically starts playback as soon as the premiere goes live.
- Dismissed "Starting soon" overlay as soon as playback starts or the broadcast is live.
- Corrected live stream status detection in `sabrPlaybackInfo` and `cachePlayerLiveDetails` to properly recognize live broadcasts and premieres.
- Fixed bottom navigation bar (phone) and navigation rail (tablet) item selection becoming unselected when video playback is active in the miniplayer; active tab remains selected while browsing.
- Comments composer re-appears smoothly with animation when scrolling up anywhere in the comments list, instead of only being accessible at the very top of comments.
- Fixed live playback stopping after only a few seconds by exposing the growing SABR stream as an unbounded ExoPlayer data source and using an expandable disk-backed spool.
- Keep both live audio and video extractors draining continuously, preventing either track from being starved when ExoPlayer merges two unbounded progressive sources.
- Validate every live fragmented-MP4 boundary before appending it to the growing spool, retrying malformed or incomplete fragments instead of terminating playback with an oversized-atom parser error.
- Normalize live fragment decode timelines while preserving continuous playback across YouTube sequence-window gaps.
- Fixed live PoToken refreshes mutating the active broadcast URL; refreshed proofs now remain in the SABR streamer context.
- Improved SABR live timeline normalization, protection recovery, and diagnostics.

## [6.4] - 2026-11-11

### Fixed
- Keep active video and music playback alive in the miniplayer when switching between app destinations.
- Parse the current `lockupViewModel` playlist rows and reload the signed-in unavailable-items view used by YouTube for Liked Videos.
- Request high-resolution artwork for playlist cards and playlist headers.
- Keep four-digit and larger playlist positions on one line.
- Hide the video player's Subscribe button when the uploader is the signed-in channel.
- Keep the phone video surface pinned while scrolling player metadata, comments, and recommendations.

## [6.3] - 2026-11-11

### Added
- Comprehensive YouTube Music playlist management matching YouTube:
  - Rich playlist detail view (`MusicPlaylistScreen`) featuring a high-resolution cover header, title, author, track counter, "Play all", and "Shuffle" buttons.
  - Delete playlist action in the playlist header bar with permanent deletion confirmation.
  - Remove track action on editable playlist rows with confirmation dialog.
  - Save track to playlist action directly from individual music playlist rows.
  - Playlist creation dialog accessible directly from the YouTube Music section headers.
  - Tablet split layout for music playlists (album cover and actions on the left panel, scrollable track list with indices on the right panel).
  - In `MusicPlayer`: added Skip Previous and Skip Next playback controls alongside Play/Pause on both phone and tablet layouts.
  - In `MusicPlayer`: added dedicated "Queue" ("Up Next") tab displaying the active playlist/queue with live playing indicator, track indices, and one-tap track selection.
  - Unified playlist dialog (`SaveVideoToPlaylistDialog`) aggregating both YouTube Music and YouTube video playlists.
- Native YouTube Music library playlists: Fetch user playlists and albums directly from YouTube Music endpoints (`FEmusic_liked_playlists` and `FEmusic_library_landing` via `WEB_REMIX` client and `https://music.youtube.com`), replacing regular YouTube video playlists in the YT Music feed with native music playlists and albums.
- Support parsing YouTube Music playlist and album identifiers (`VL`, `PL`, `MPRE`, `LM`, `SS`, `SE`) and direct album browse navigation without synthetic `VL` prefixing.
- Add support for artists and channels in YouTube Music, including parsing music artist/channel renderers (`musicResponsiveListItemRenderer`, `musicCardShelfRenderer`), dedicated `MusicChannelShelf` rows in search and browse, and interactive navigation to artist channel pages from search, shelves, tracks, and the music player.
- Add "See all" chevron (`>`) to YouTube Music section titles (such as "Playlists" and carousels) to view all items in a full-screen grid or list with back navigation.
- Add user Playlists section directly below "Listen Again" on the YouTube Music home screen.
- Add Android Auto voice/text search support (`onSearch`, `onPlayFromSearch`, `onPrepareFromSearch`), media queue publishing, and slot reservations for previous/next controls.

### Fixed
- Keep SABR playback on the active endpoint when YouTube reports `ATTESTATION_PENDING`: refresh the streaming PoToken in place, ignore the stale protection response, preserve buffered media and protocol context, honor the server-provided retry limit, and reserve full player reloads for explicit `RELOAD_PLAYER_RESPONSE` frames.
- Request original YouTube channel and artist artwork from `yt3` image hosts and select the largest available avatar candidate, preventing visibly pixelated profile images in YouTube Music results.
- Expand YouTube Music search with native filtered sections for songs, albums, artists, playlists, profiles, podcasts, and episodes, including working full-section navigation.
- Limit standard YouTube playlist parsing to the playlist's own video-list renderer so unrelated recommendations cannot appear as playlist entries.
- Add a save-to-playlist action to every video row in standard YouTube playlists.
- Preserve edge-to-edge system bars when switching songs from the YouTube Music queue, even if the previous player's disposal completes after the replacement is visible.
- Recognize YouTube Music's editable-playlist header renderer so account-owned playlists retain edit and delete actions without exposing them on saved third-party playlists.
- Continue loading YouTube Music playlist tracks after the first 24 entries when the list reaches its end.
- Keep the expanded playlist section open after creating a playlist and refresh it in place.
- Populate the YouTube Music queue with the full native Up Next response instead of showing only the current song.
- Keep SABR protection refresh and media-URL maintenance status internal after playback has started, preventing stale loading text from covering active YouTube Music playback.
- Open a selected YouTube Music playlist directly from the expanded playlist section and keep the current track visible when no explicit queue is available.
- Open the create-playlist dialog immediately from the YouTube Music playlist section instead of deferring it until that screen is closed.
- Only expose YouTube Music playlist deletion and track removal when YouTube explicitly marks the playlist as editable, instead of treating every saved playlist as account-owned.
- Fix playlist video playback lifecycle so exiting or navigating back from a playlist screen (`PlaylistScreen`) does not close the playing video. Video playback is hoisted to root `AppScaffold` with active playlist queue and index context, keeping playback uninterrupted in full player or miniplayer while browsing.
- Add hardware/gesture `BackHandler` in `PlaylistScreen` and `ChannelScreen` so system back gestures cleanly return to the previous view without bubbling to parent activities.
- Support opening playlists directly from video recommendations and description links.
- Fix previous video button in playlist video playback: Solved stale state capture in `VideoScreen` where long-running `LaunchedEffect(player)` loop captured initial `playlistIndex = 0` closure, continually disabling the `exo_prev` button even when on video 2 or later. Used `rememberUpdatedState` for playlist index and items, added index change side-effects, and hooked controller visibility listeners to ensure controls reflect the active playlist position immediately.
- Remove category chips from YouTube Music home screen and ensure section title clicks open the full section instead of mistaking section headers for channel links.
- Clean up Android Auto root structure to a clean single-feed layout without redundant navigation tabs.
- Fix mix and playlist playback order so the seed video matches the cover thumbnail.

## [6.2] - 2026-11-10

### Fixed
- Fix reply sheets layout and scrolling lockups when viewing long parent comments by moving the parent comment inside the scrollable `LazyColumn`, replacing fixed `fillMaxHeight` with flexible `.weight(1f)` bounds, and adding expandable "Read more" / "Show less" toggles for lengthy comments.

## [6.1] - 2026-11-10

### Fixed
- Normalize published date strings and strip redundant prefixes (`Premiered`, `Published on`, `Streamed live on`) across both full dates and ISO timestamps in `displayPublishedDate`, and stabilize `publishedDate` in `stableMetadata` to prevent UI flickering between short, prefixed, and expanded month formats when metadata refreshes asynchronously.

## [6.0] - 2026-11-07

### Fixed
- Rotate SABR player sessions mid-stream (`refreshPlayer`) in `SabrVideoSession` and `SabrSession` when YouTube signals `STREAM_PROTECTION_STATUS = 3` (`ATTESTATION_REQUIRED`), attestation stalls, or dry buffer cutoffs, requesting fresh player endpoints, ustreamer configuration, and streaming PoTokens without stopping playback or failing with `incomplete drain` around the 1-minute mark.
- Fix mid-stream session rotation endpoint assignment by using the refreshed URL directly rather than re-running URL preparation, preventing `n` challenge corruption and duplicate stale `pot`/`cpn` parameters that caused `HTTP 403 Forbidden`.
- Request clean player sessions (`forceFreshPoToken = true`) directly during SABR session rotation, bypassing unsupported ad playback context payloads that returned `UNPLAYABLE`.

## [5.12] - 2026-11-07

### Added
- Show the `"SABR requested backoff, waiting Xs"` notice only during initial media startup by default, and add a `"Show SABR backoff while buffering"` toggle in Settings (disabled by default) to display it during mid-playback buffering as well.

### Fixed
- Cold-start SABR streams directly at the requested resume timestamp with a 12-second keyframe margin (`INITIAL_SEEK_MARGIN_MS = 12_000L`) and automatically widen the margin if the server lands past the target byte offset, fixing infinite buffering when resuming videos from History (such as at `05:03`).
- Anchor the SABR demand-pacing watermark to the first received media offset on seek/resume so mid-stream cold starts are never throttled relative to byte `0`.
- Avoid solving the `n` challenge a second time in `refreshWebSabrAdPlayback` and `reloadSabrPlayback` when `webPoPlayer` already resolved `serverAbrStreamingUrl`, preventing `HTTP 403` errors during session refresh.
- Refresh cached `playbackTracking` (`videostatsPlaybackUrl` and `videostatsWatchtimeUrl`) whenever `refreshWebSabrAdPlayback` or `reloadSabrPlayback` rotates the WEB player session mid-stream, and flush the final playback timestamp on player disposal so closing a video immediately after seeking or watching saves the resume position.
- Block `/api/stats/playback`, `/api/stats/watchtime`, and `/api/stats/qoe` inside `WebStreamAttestationProvider`'s hidden bootstrap WebView so it never reports `cmt=0` with signed-in cookies, and fix duration and progress extraction in `scanResumeMetadata` (including `thumbnailBadgeViewModel` and `thumbnailOverlayTimeStatusRenderer`) so reopened videos do not reset to `00:00`.
- Include user activity timestamp (`lact`), session elapsed time (`rt`), valid playback state (`state=playing`), and video duration (`len`) in `/api/stats/watchtime` beacons, dispatch `/api/stats/playback` strictly on startup rather than on pause, and send properly bounded `st < et` watchtime intervals with `final=1` on pause and player disposal, restoring YouTube server-side watch history synchronization.
- Cancel and clean up previous `ModernSabrStream` sessions when switching or closing videos so background `SabrVideoSession` threads do not leak.

## [5.11] - 2026-11-06

### Added
- Add a `"Prioritize video quality over preferred codec"` setting (disabled by default) that selects higher-resolution and higher-framerate streams before falling back to preferred video codec ties, while continuing to respect the user's preferred resolution cap.

### Fixed
- Reuse `playbackTracking` directly from the authenticated `webPoPlayer` response instead of sending a duplicate unattested `/player` request with the same `cpn` that YouTube rejected as `UNPLAYABLE`.
- Refresh the streaming `PoToken` immediately when `STREAM_PROTECTION_STATUS=3` arrives during an empty SABR startup handshake instead of discarding the active SABR context and failing the session, and keep the refreshed `pot=` query parameter synchronized with the protobuf `poToken`.
- Fall back to a clean player resolution with a freshly minted `PoToken` when `adPlayback` recovery with the original `cpn` fails.
- Unregister the active `ExoPlayer` from `MainActivity` before releasing it on disposal so `onStop()` never posts a pause command to a dead player thread.
- Stop `AutoMusicService` from broadcasting `MediaSession` state updates every 500ms while paused or closed, and deduplicate repeated playback lock and system-bar diagnostic logs.

## [5.10] - 2026-11-06

### Fixed
- Display SABR backoff countdowns only while the player is actively buffering so routine background waits do not cover playing video, Shorts, or music.
- Preserve the active `VideoScreen` composition, `ExoPlayer` instance, and `PlayerView` surface across video transitions and switch target views cleanly so Picture-in-Picture never turns black when advancing to the next video.

## [5.9] - 2026-11-04

### Fixed
- Select the video's explicitly tagged original-language audio instead of treating the session's default dubbed track as the original.
- Show a visible countdown when YouTube's SABR policy requests a temporary backoff instead of leaving playback apparently stuck.

## [5.8] - 2026-11-04

### Fixed
- Rebuild the BotGuard runtime when SABR explicitly requires fresh stream attestation instead of minting another proof from the rejected challenge.
- Replace the streaming PoToken atomically in both the SABR endpoint and protobuf request while preserving buffered media, playback position, and continuation state.
- Recover immediately when stream protection escalates from pending to required without being blocked by the normal refresh cooldown.

## [5.7] - 2026-11-04

### Fixed
- Rebind reused `PlayerView` instances to the new ExoPlayer when opening another video, preventing a black PiP window.
- Reset and refresh the PiP aspect ratio for every new player instead of retaining the previous video's shape.

## [5.6] - 2026-11-03

### Fixed
- Restart a SABR session at the beginning when seeking from a restored history position to `00:00`.
- Clamp seeks at the exact media duration to the final playable second, avoiding an empty byte range that remained stuck buffering.

## [5.5] - 2026-11-02

### Fixed
- Reuse the initialized playback-attestation WebView when YouTube updates its cookies during PoToken or attestation requests.
- Prevent an unnecessary attestation runtime rebuild from leaving SABR playback permanently stuck on `Buffering media…`.

## [5.4] - 2026-11-02

### Fixed
- Mute the entire playback-attestation WebView before navigation so its hidden YouTube player cannot temporarily take audio focus and start music playback without sound.
- Keep the existing JavaScript silence guard as a compatibility fallback for older Android System WebView versions.

## [5.3] - 2026-11-01

### Fixed
- Add an explicit lyrics retry action and automatically retry a timed-out request when returning to the Lyrics page.
- Preserve the edge-to-edge player viewport throughout gesture-navigation app switching, preventing temporary black system bars and UI resizing.

## [5.2] - 2026-11-01

### Fixed
- Refresh the streaming PoToken inside the active SABR session after repeated limited-playback responses, preserving its endpoint, protocol state, and buffered media instead of regenerating playback attestation or restarting the player.
- Invalidate the previous music source before loading a newly selected track so stale player callbacks cannot clear its loading stage or advance the wrong queue.
- Preserve the current music position when recovering with another audio stream candidate instead of restarting playback from the beginning.
- Keep an explicit failure status visible when every audio stream candidate is exhausted.

## [5.1] - 2026-11-01

### Fixed
- Submit WEB playback attestation through YouTube's expected `attestationResponse` envelope with exactly one encoding pass.
- Match YouTube's single-submit attestation lifecycle instead of regenerating proof on every pending SABR protection response, preserving buffered playback and preventing escalation to rejected protection states.

## [5.0] - 2026-10-31

### Fixed
- Generate and submit a fresh playback attestation for every new SABR protection challenge while coalescing only requests that overlap the same in-flight proof.
- Prevent a completed attestation from being reused when YouTube returns another `protection=2`, avoiding escalation to `protection=3` without rebuilding the player session or discarding buffered media.

## [4.12] - 2026-10-31

### Fixed
- Renew only the active WEB playback attestation when SABR reports protection pending, preserving the existing endpoint, PoToken, request context, and buffered media instead of rebuilding the entire player session.
- Keep a shared attestation renewal alive when an individual player or UI waiter is cancelled, and prevent one protection response from triggering duplicate renewals or leaving the media downloader stopped.

## [4.11] - 2026-10-31

### Fixed
- Prevent hidden playback-attestation WebViews from leaking pre-roll audio by blocking autoplay until both YouTube's player and all HTML media elements are muted, then starting the muted attestation playback.
- Initialize Android's official edge-to-edge support when the activity is created, keeping the same transparent system-bar path on phones and tablets without tablet-only workarounds.
- Keep edge-to-edge enabled for the activity lifetime and prevent theme recompositions from overwriting player-owned transparent system bars on Android 5-7.
- Stop Android 5+'s internal `screen_simple` decor root from consuming the status-bar inset before Compose; Android 17 stable still declares `fitsSystemWindows` there, so the correction applies consistently across supported versions.
- Give the YT Music top controls more breathing room below the status bar on phones and tablets.
- Preserve centered YT Music titles when they fit while keeping marquee scrolling for long titles.
- Allow WebView to receive the media bytes required to initialize YouTube playback attestation on every Android version; hidden playback remains muted through the player API instead of request blocking.
- Preserve YT Music search relevance by parsing its result shelves in server order, including the native top-song card renderer, while continuing to exclude music-video items mixed into results.
- Avoid competing BotGuard and playback-attestation WebViews on Android 5-7, clean up failed attestation runtimes, and report the attestation stage separately from the player-data request.

## [4.10] - 2026-10-30

### Fixed
- Keep only active player content independent from transient navigation-bar insets while preserving normal status-bar padding on every app screen.
- Block media delivery inside the hidden WEB attestation runtime so pre-roll or video audio cannot leak before playback starts.
- Keep transparent status and navigation bars continuously visible in the YouTube Music player so system-bar transitions cannot resize its UI.
- Show the YouTube Music artwork behind the transparent status bar while keeping every player control below its inset.
- Preserve normal status-bar insets in the regular video player; edge-to-edge transparent bars now apply only to YouTube Music.
- Keep Android navigation controls visible in the regular video player and restore normal system bars when playback continues only in the Home miniplayer.

## [4.9] - 2026-10-30

### Fixed
- Prevent the hidden WEB playback-attestation player from leaking pre-roll audio before it is muted.
- Prevent one explicit YouTube Music selection from starting two concurrent playback sessions.

## [4.8] - 2026-10-30

### Fixed
- Prevent a stale foreground UI from restarting the previous song after background autoplay has already advanced the playback service.
- Coalesce concurrent WEB SABR protection renewals after seeking so restarted sessions reuse one attested endpoint, configuration, and PoToken.
- Restore the active player's requested system-bar state after returning to the app or leaving picture-in-picture.
- Keep the navigation bar hidden for an active non-fullscreen video player after returning from the background.
- Persist the top-level player UI state across foreground transitions so every active player reapplies its requested navigation-bar visibility.
- Retry WEB PoToken initialization once with a clean runtime after a WebView timeout.
- Remove legacy playback fallbacks to TV, mobile WEB, VISIONOS, IOS, and ANDROID clients; WEB plus SABR now reports its real failure directly.
- Remove the dead native/mobile playback helpers and keep WEB_REMIX and MWEB limited to non-playback YouTube Music and Saved Shorts data APIs.

## [4.7] - 2026-10-30

### Changed
- Standardize YouTube Music playback and authenticated playback tracking on the WEB client instead of WEB_REMIX.
- Let the YouTube Music player continue buffering through the end of an audio track while preserving fast startup thresholds.

### Fixed
- Clear lyrics, lyrics errors, and scroll position whenever automatic playback advances to a different track.

## [4.6] - 2026-10-30

### Changed
- Increase the playback buffer target to three minutes while preserving the existing 400 ms initial-playback and 800 ms rebuffer thresholds.

## [4.5] - 2026-10-30

### Fixed
- Prepare WEB playback attestation in parallel with player setup and wait for its submission before the first SABR media request, preventing an initial `protection=2` race.

## [4.4] - 2026-10-30

### Added
- Submit YouTube's official WEB playback attestation asynchronously before SABR stream protection can require it.

### Fixed
- Support both current `window.attmp` and legacy `yt.aba.att` playback-attestation registrations used by YouTube.
- Keep initial playback responsive while preparing and submitting attestation in a hidden, muted WebView.
- Retry attestation synchronously when YouTube requests SABR protection renewal.

## [4.3] - 2026-10-30

### Fixed
- Keep renewed WEB SABR endpoints, PoTokens, and ustreamer configurations bound to the same player session.
- Restart SABR request numbering when YouTube replaces a player session, preventing `sabr.malformed_config` responses during stream-protection renewal.

## [4.2] - 2026-10-29

### Added
- Browse the phone-style music feed, playlists, and individual playlist tracks from one Android Auto screen.
- Use playlist-aware playback queues with working previous and next controls in Android Auto.
- Start an entire playlist directly from its Android Auto browse screen.

### Changed
- Present playlists as browsable collections instead of trying to play them as songs.
- Keep feed sections as visual groups instead of turning them into navigation folders.
- Follow Android Auto's root-menu contract with one browsable Home tab and a mixed grid feed beneath it.
- Prefer large artwork cards through modern, per-item, and legacy Android Auto content-style hints.
- Stop restoring dismissed Android Now Playing sessions after the user removes them.

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
