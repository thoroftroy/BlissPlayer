# Testing

## 0.14 round (Nov 9, 2026, BlissTest2 API 35, full YMusic library on device)

- Device truth first: swipe-stop, trim per-file, waveform sanity, organize service, notification persistence and search were each reproduced or measured live on the emulator with the real 1175-song library, not just unit-tested.

- Library work steps each run alone in the service with silent progress plus Stop, and each tracks every file: trim and organize marks live in song_state (DB v15), dupe waveforms live in the dupe_fp cache.
- Waveform scorer rebuilt (48 loudness windows plus 48 timbre windows, Pearson, both must agree). Same file twice scores 100; 15 diverse real pairs score 20-37. The old scorer called such pairs 95.
- Trim of the full library completes, marks every file, and a redo finishes in 0.009s with "Already trimmed".
- Organize runs in the service with a Stop action, skips finished files, collects write-grant leftovers for one system Allow, then resumes with just those files and reports counts in a completion dialog.
- Notification swipe is definitive: stopped_by_user is set, queue and resume prefs are cleared, the card stays gone; app swipe still keeps background play. Collapsed notification carries the scrub bar.
- Boost re-applies on every play start and recovers a missing enhancer from the live session.
- LibraryWorkV14Test 4/4 green on device (trim per-file, waveform sanity, organize service, swipe-stop).
- JVM: 150 checks green, including the YMusic offline diagnostic over the real
  Testing Files (1177 files: 99.7 percent artist coverage, 96.0 percent proposals, 767 high).
- Search verified live on the emulator with real key events into the app field:
  magnifier opens the row, "kryptonite" filters 1195 songs to the 1 match,
  tapping the match plays it (media session confirms the title, position
  advances). SearchTest locks the open and filter steps in.
- Full connected suite first pass 30/36: the 6 failures triaged one by one (below).
- Still open: <fill after suite runs>.


## Shared theme tokens, cards and glyphs (in progress)

- `:core` now holds the theme system both platforms read: `BlissTheme`
  (keys, modern flags, ink/secondary/accent, bundled font assets),
  `ThemePalettes` (verbatim `colors.xml` ARGB), `ThemeGradients` + `ThemeGlyphs`
  (verbatim `MaterialDrawable`/`ThemeGlyphDrawable` stops). `ThemeTokensTest`
  pins the values; Android `MobileTheme` delegates to it.
- Linux `XpPaint` palettes are built from those tokens
  (`palettesComeFromSharedThemeTokens` test); existing pixel tests
  (title bars, buttons, selected row, display, sections) still pass, proving
  no visual change from the migration.
- Modern rows now share code: `RowCardDrawing` (rounded cards, same stops/
  hairlines/geometry as `MaterialDrawable` ROW — Aqua delegated on Android
  too) and `TrackGlyphDrawing` (CD/hexagon, delegated by Android's
  `ThemeGlyphDrawable`; Super's animated sheen stays platform-side).
  Verified by `ModernRowDrawingTest`, desktop `PolishShots`, and real Xvfb
  windows against the Android screenshots: Aqua glass cards + CDs + round
  dot buttons, Super dark-red cards + hexagons. Bliss/Dark XP paths
  byte-identical (pixel tests green).
- Remaining gaps: Super's animated sheen/sweep has no static equivalent;
  deck/transport/buttons/dialogs still separate implementations.

## Tray + cleanup work (in progress, Nov 5, 2026)

- Shared tray model: `:core` `TrayMenu` (ids, labels, dispatch) is used by the
  Linux AWT fallback and the new native SNI tray; `TrayMenuTest` covers all
  five entries, the unknown-id case, and that Quit sends no transport command
  (the old code also fired PAUSE on Quit — fixed).
- Native StatusNotifierItem tray (`DesktopSniTray`, dbus-java): exports
  `/StatusNotifierItem` + `/Menu` (com.canonical.dbusmenu) and registers with
  `org.kde.StatusNotifierWatcher`. Verified against the real Plasma panel:
  the item appears in `RegisteredStatusNotifierItems`, its properties
  (Category/Id/Title/Status/Menu/ItemIsMenu) introspect correctly,
  `GetLayout` returns the 5 labeled entries over the wire, and `Event`
  clicks for Play/Pause (TOGGLE), Show and Quit dispatch through the shared
  model — Quit exits the full desktop app and unregisters the item.
- `DesktopSniTrayTest` replays layout + clicks over the real session bus
  (two connections, no watcher registration so no panel icon in tests).
- AWT fallback kept for X11 desktops without SNI (SNI is attempted first,
  independent of `SystemTray.isSupported()`).
- Escape hatch: `AppImage --quit` (or `pkill -f blissplayer-desktop.jar`)
  stops a running instance whose tray is unreachable; pid file is cleaned up
  on quit. Verified on an isolated instance.
- Cleanup: removed superseded `BlissPlayer-linux-v0.11.22.tar.gz`,
  `BlissPlayer-v0.11.23-debug.apk`, legacy `BlissPlayer-x86_64.AppImage`;
  `build-linux.sh` now prunes older versioned tarballs/AppImages and keeps
  only the current build; the unversioned AppImage name is git-ignored.
- Linux MPRIS: during a real `DesktopPlayer` VLC session,
  `org.mpris.MediaPlayer2.vlc` appears on the session bus (tight 0.3 s
  polling; the window is only a few seconds in the headless smoke).
- Android regression: full 20-test connected run on the rebooted emulator had
  4 failures ("library never loaded" x3 + crash-alert assert) while the
  emulator system process was ANR'd and manual taps ran concurrently.
  Re-running `StabilityRegressionTest` alone on the healthy emulator:
  15/15 green, including the 4 previously failing tests. No app regression;
  the bulk suites remain slow by design (~6 min for the class).
- Theme screenshots, all four, both platforms, populated libraries
  (Android 587 songs, Linux 2 songs): Bliss and Dark Bliss match closely
  (Luna titles, stripes, LCD deck). Aqua and Super match in palette and
  information architecture but NOT in materials: Android uses glass/rounded
  cards, CD glyphs, round dot buttons and glass/neon decks; Linux renders
  tinted flat XP rows with square controls. That gap stays open (native UIs
  per D-1); no parity claimed.
- JVM suites: core 26+3, desktop 16, app 99 (6 skipped) — green.

## Shared seek-bar work (0.11.25, Nov 5, 2026)

- `:core:test`: 26 tests passed; `:desktopApp:test`: 15 passed;
  `:app:testDebugUnitTest`: 99 tests, 6 skipped, no failures.
- Android 0.11.24 was installed on the emulator and the Bliss now-playing
  dialog was visually inspected with the shared seek-bar renderer. The
  0.11.25 APK was built and installed; that later build includes the shared
  seek-position mapping as well. This is not a full Android regression run.
- Linux: all four themes were inspected in actual isolated Xvfb windows with
  empty libraries from the 0.11.24 desktop build. The final 0.11.25 AppImage
  was launched and its Bliss and Dark empty-library screens inspected. Its
  embedded jar matches the freshly built desktop jar by SHA-256. These checks
  do not establish theme parity or verify a real-library workflow.
- The bulk of the Android and Linux UI remains separate; adaptive shared
  screens, end-to-end functional parity, physical Bluetooth/car behavior,
  MPRIS and visual comparisons across all Android themes remain open.

# 0.8.5 regression checks

Run JVM checks with `./gradlew :app:testDebugUnitTest`.
APK builds automatically increment the patch and version code in
`app/version.properties`. `./build-apk.sh` also copies the versioned APK to the
repository root. Compilation, JVM tests, lint and dry runs do not increment it.
Run Android checks on a **disposable emulator**, not a personal phone:

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

The Android suite creates and deletes its own quiet, generated M4A copies. It
temporarily changes the emulator's screen/power state and app settings. It checks:

- Actual M4A tag round-tripping and refreshed MediaStore titles/artists.
- Analyzer/organizer progress updating real views on the main thread.
- Real MediaStore moves, repeat organization, and explained move failures.
- Dismissal/recreation of an old crash alert without exporting to Downloads.
- Car/wired/speaker route snapshots through the **production** output handler,
  including stale Bluetooth routes after disconnect, with the Activity destroyed.
- Service playback, track advancement, transient audio-focus recovery and explicit
  user pause, with the Activity closed, screen off and forced idle.

Verified on November 2, 2026 (0.8.31, versionCode 47): **59 JVM checks passed**,
four optional network tests skipped; **seven Android API 35 emulator tests passed**
(StabilityRegressionTest incl. a review-select-all-apply regression) plus the
BulkYMusicTest bulk suites (queue-dots menu, headless bulk apply), on BlissTest2;
Android lint clean. Version number ran fast because every build/test invocation
increments it per the Message 37 rule.

Bulk analyzer evidence (JVM, real tags from all 1177 files in
`~/Downloads/YMusic`): **96.0% proposal coverage (1130/1177)**,
**99.7% artist coverage (1174/1177)**. On-device bulk evidence (two batches,
6GB emulator disk): batch 1 (590 files) analyzed to 567 proposals with the real
confirm/apply dialogs, and headless apply wrote 95/101 with Bill Withers fixed;
batch 2 (587 files) completed the full user flow Analyze -> Apply confident ->
system Allow -> Analyzer done, 286/301 proposals with My Way now
Frank Sinatra in MediaStore. adb-pushed files stay `is_pending=1` until
finalized per ID, or the app (correctly) sees nothing; clear it after pushing.

Manual emulator checks on the final APK (screenshots, not only dumps):
startup background-play prompt shown with Open settings / Not now; Play starts
music with the mini player immediately showing the right song, pause icon and
progress (fixed a real desync where it lied "Nothing playing" while playing);
other apps in the foreground for ~25s and screen-off for 40s both advanced
playback with no pause; Control Panel sections, boost warnings, per-device
presets and XP theming inspected in light mode.

The route snapshots emulate audio-policy events, **not a Bluetooth radio or a real
car**. Physical Bluetooth/USB handoffs, OEM battery restrictions, long-duration
playback, acoustic quality and hearing safety still need real-device testing.
Android may intentionally pause for headphone disconnects, permanent focus loss,
force-stop, permission revocation or user battery restrictions.

Emulator caveats seen this round: `adb input tap` lands ~100px off and
non-deterministically, and uiautomator snapshots go stale (screencap is ground
truth). The on-device Analyze-button tap-through was not completed for that
reason; the dialog/apply flow is covered by the instrumentation suite.

The test fixture is a three-second 440 Hz sine at very low volume, generated with
FFmpeg/AAC, not a user music file. Test reports are in `app/build/reports/`.

## 0.9 round (Nov 3, 2026, v0.9.20)
- Apple/iTunes lookup removed entirely; pipeline is now MusicBrainz-first
  (title/artist/album/genre tags, studio-album release-group preference,
  1 req/s bucket, circuit breaker) + Wikipedia second (search, combined
  categories+infobox details call, genre backfill for confirmed titles).
- Full JVM suite green (71 checks, includes new MusicBrainz parse/fold and
  studio-album tests). FocusPolicy pure unit tests green.
- Connected StabilityRegressionTest 10/10 green on BlissTest2: swipe-away
  keeps notification + advancing position, More-pane tree/preset-delete/
  mixer/EQ-slider/organize entry, Downloads-leftovers organize (3/3 moved),
  plus all prior regressions (focus resume, doze, crash privacy, queue menu,
  select-all, tag writes).
- Organize-all verified end-to-end on device: 587/587 files moved from
  Music/YMusic into Music/BlissPlayer/Artist/Album.
- Analyzer bulk measurement over all 1177 YMusic files (JVM, live APIs):
  1147/1177 proposals (97.5%), 1050 albums filled, genre the remaining gap
  (MusicBrainz tags sparse; Wikipedia backfill added). Bulk run in progress
  with persistent resume cache; per-API throttles (Apple 403-boxed this IP,
  MusicBrainz 1/s, Wikipedia burst limits) make a full pass ~1h, cached once.
- Shipped BlissPlayer-v0.9.20-debug.apk (installed + launch-verified).
- NOT verified: real-car Bluetooth handoff, physical aux, long-duration
  handset playback, acoustic quality/hearing safety.

## 0.10 round (Nov 4, 2026, v0.10.18)
- Incremental analyzer/equalizer: song_state fingerprints, New/changed, Redo
  done, Everything scopes with counts; both run in PlayerService with silent
  progress notifications (Stop actions) + in-app status lines + ETA; no modal
  pop-ups; proposals review opens on return.
- Back button walks search -> drill -> tab history instead of exiting.
- Status strip tap opens the exact-boost dialog; playback speed 0.5x-2x in
  now-playing (persisted, restored at service start).
- Consent retries queued per operation: delete-after-Allow works (verified by
  the organize/Downloads tests exercising the same queue).
- Organize covers enabled folders only (unticked never touched); hint updated.
  Verified end-to-end (587/587 moved); Downloads-leftover test green.
- Canonical artists/albums: descriptions, years, casing and version words
  merge (unit-tested); Artists/Albums tabs, drills, pickers, rows, organize
  destinations all use canonical keys; studio-album release-group preference.
- More regrouped (sound cluster, tools together), blurbs shortened,
  per-section XP hues; Playlists tab folded into a More dialog (list, songs,
  add, play, delete; JSON autosave kept).
- Notification styles fixed for real: Full uses
  DecoratedMediaCustomViewStyle (MediaStyle ignores custom views, which is
  why both looked the same); shade shows the media row (test green).
- Paused notification persists after swipe-away (test green).
- Themes Bliss/Dark Bliss/Aqua Bliss/Super Bliss on Android (screenshots for
  all four) + Linux (screenshots for all four); Super has particles, tap
  explosions, drifting background, tab swoosh.
- Rust bliss_core (names/genre/organize/loudness/focus/full cleanLocal port):
  13 unit green + 1177/1177 byte-identical parity vs Kotlin. JNI bridge in the
  APK (arm64/v7a/x64) driving displayAlbum with fallback; JVM parity green.
- Linux eframe app (same core): scan/play/queue/speed/boost/EQ/dsp biquads/
  analyzer+review/organize/equalize/playlists/themes/tray/mill/online
  MB+Wikipedia; sandboxed to ~/Downloads (refusal exit 2, tested); GUI
  screenshot-verified in all themes; m4a playback verified (5s advanced);
  online probes return albums/genres; AppImage built and dump-verified.
- JVM 74 green, lint 0 errors, connected StabilityRegression 14 green,
  BulkYMusic 3/3 green (headless online 120-file bulk + new UI flow).
- NOT verified: real-car Bluetooth, physical aux, long handset playback,
  acoustic safety; Linux tray icon (no appindicator lib on this box),
  Linux long-session stability.

## 0.10 mobile theme finish (Nov 4, 2026)
- Fixed the real reason Super effects were invisible in tests and weak on
  taps: the old decor onTouchListener never saw child-targeted touches, and
  ambient particles spawned below the viewport. The window-callback
  interceptor now observes every tap without consuming it; bursts, rings and
  press physics fire on real interaction (screenshot-verified: particle
  trails + shockwave ring on a Super dialog tap).
- Tamed the runaway clock that hung the theme test for 20+ minutes: adaptive
  pacing (full speed ~60fps for 4s after interaction, 8fps idle heartbeat),
  plus a user Motion setting (Full/Reduced/Off) and system-animator respect.
  Theme test now completes in ~33s.
- Aqua is Snow Leopard, not white paint: brushed-metal grain, gel-pill
  buttons with lens reflection, translucent water-lit panels/dialogs with
  caustics, reflected (non-green) mini display, dark-ink titles, silver
  system bars with dark icons, mac-style traffic-light glyphs.
- Super is its own thing: Outfit type, neon glass everywhere (toolbar,
  panels, rows, dialogs, fields), roaming sheen + orbiting spectral edges,
  AGSL plasma backdrop on API 33+ (orb fallback on 29-32), hexagon glyphs,
  pulsing title glow, tap explosions, tab swooshes, overshoot springs.
- Covered every control: tabs, library rows (recycled-safe), dots buttons,
  mini/now-playing decks, seek bars, section headers, presets, pickers,
  mixer, queue, review/apply/organize/delete dialogs with role-tagged
  actions, crash/startup prompts. Bliss/Dark paths byte-untouched.
- Proof: MotionFieldTest 5/5 JVM green (deterministic bursts/expiry/seeding);
  themeCycleAppliesAllFour green incl. two-frame Super animation diff assert;
  full connected suite 17/17 green; JVM 79 tests 0 failures; lint 0 errors.
- NOT verified: real-car Bluetooth handoff, physical aux, long-duration
  handset playback, acoustic quality/hearing safety (unchanged standing gaps).

## 0.10 Linux UI rewrite (Nov 4, 2026)
- First Linux UI was generic egui: rejected. Rewrote ui.rs as a faithful
  port: XP title bar, 5 tabs, green Play/Mix, crumb, header, zebra song rows
  with dots menus, clickable status strip, dark-green mini deck (transport,
  position bar, speed/mixer/boost), queue window, color-coded More sections
  in the same order as Android, same dialogs (mixer/boost/playlists/review/
  delete-confirm), Super particle bursts + ambient drift + pulsing title.
- Screenshot-verified all four Linux themes (Bliss/Dark/Aqua/Super);
  Aqua fixed from washed-out to dark-on-silver gel.
- AppImage rebuilt from the final binary and dump-verified.

## 0.10 favorites / icons / tabs round (Nov 5, 2026, v0.10.25–v0.10.51)
- Fixed blank AutoBoost/buttons-on-first-open: More-pane sync setters ran
  mid-build and no-op'd on null views; all update* calls now run after every
  section exists (verified: Boost/Auto-device text populated on first open).
- Aqua toolbar uses the user's BlissIconAqua art (aqua_brand); Super toolbar
  uses the user's BlissIconSuper art (super_brand). Super header no longer
  says "Light Engine".
- Favorites: pre-built playlist (auto-created, undeletable, always first in
  the Playlists tab with a divider), star toggle in every song row, song
  menu, queue menu, Now Playing, and the notification (action + RemoteViews
  star, bright/dim). Exempt from cycle rarity by default; Shuffle settings
  add a mode switch (Exempt / Boosted via favorites-rarity slider / Like
  regular songs). Stars are the user's art: flat shapes on retro (Bliss
  light-yellow w/ black outline, Dark gold), textured blue-shifted on Aqua,
  animated scrolling-rainbow shader on Super; empties are flat gray (retro)
  or pre-lightened art (modern). Playlist row shows a filled gold star.
- Playlists is a top tab again (Songs/Artists/Albums/Genres/Playlists/More);
  More moved to index 5. Pre-existing `tab != 5` guards became live.
- Home-screen icon follows the theme via 4 launcher aliases (Bliss/Dark/
  Aqua-rainbow art); verified exactly one resolves per theme. (Fixed a real
  bug: alias classes live under com.blissplayer.music, not the .debug app id.)
- Notification/session art is the BlissFull hill full-bleed everywhere; fixed
  an OOM (Media3 clones artworkData per queue item) by publishing an
  android.resource artwork URI instead of bytes.
- Click feedback setting (Full/Gentle/Off) in Look and feel; classic themes
  get tiny XP pixel-pops with no rings, modern keep bursts. Retro styling
  bleed fixed by gating all modern helpers behind theme.modern (stock XP
  buttons, segmented progress, beige panels back on Bliss/Dark).
- Loop defaults to All and the choice persists (repeat_mode pref + restore).
- Proof: JVM suite green (incl. FavoritesTest), lint clean, Stability suite
  14/14 (1 skip) + Bulk suite 3/3 green on BlissTest2, screenshots for Bliss
  (stock XP + stars), Aqua (blue star), Super (rainbow star + aurora),
  Playlists tab, and the Bliss-hill system media card with star action.
- Final artifact: BlissPlayer-v0.10.51-debug.apk (only APK kept).
- NOT verified: real-car Bluetooth, physical aux, long handset sessions,
  acoustic safety (standing gaps, unchanged).

## 0.10 stats/readability round (Nov 5, 2026, v0.10.53–v0.10.57)
- Removed the theme subtitle lines everywhere (Aqua "MUSIC LIBRARY" and
  Super "LIGHT ENGINE" identity rows are gone; toolbar shows the name only).
- Fun stats: song_stats table (skips, completions, listen_ms, last_played)
  plus a 2-year-pruned stat_events log; stint tracking closes on track
  change (auto=finish, bail<50%=skip), seeks rebase the stint, pause/saves
  flush. Stats panel is headed cards (Library / All time / Top 3 / month /
  year), with a tracking toggle (off also flattens Smart Shuffle, said on
  the tin) and Share-stats JSON export (verified: picker saves valid JSON
  matching on-screen numbers). Buckets verified live (8 plays, 2 finishes,
  1 skip with tops); skip path verified in DB (skips=1 + listen 273s).
- Readability: favorites shuffle modes, folder tree, and boost presets use
  bullet lists; More items get control/description spacing + indented
  explanations; stat cards group every topic.
- Super progress is a scrolling rainbow in the mini bar AND the Now Playing
  / landscape seek bars (thumb circle kept). All self-loops run at an
  idle-friendly 2fps after proving 80ms loops starved UiAutomator.
- Now Playing favorite button removed (queue star covers it).
- Deleted the entire Rust tree + JNI .so files + bridge + parity test
  (freed ~6.5GB incl. target/); displayAlbum is pure Kotlin now.
- Proof: JVM green incl. SongStatsTest 9/9; lint clean; installed 0.10.58
  launches crash-free; kept artifact BlissPlayer-v0.10.57-debug.apk
  (code-identical to the .58 install; only APK in tree).

## 0.11 multiplatform cut (Nov 5, 2026)
- `:core` (pure JVM, no Android imports): ShuffleMath, model (Song/SongNames/
  SongStats/Seg/FolderTreeIndex), MusicBrainzLookup, WikiLookup, AnalyzerRules
  (verbatim-extracted cleanup/grouping/album/genre rules; SongAnalyzer delegates),
  LoudnessMath (LoudnessScanner delegates), PlaylistPortable v1, platform
  contracts (PlayerEngine/LibraryAccess/TagGateway/AudioFx/NotificationGateway/
  SettingsStore/AppPaths/PlaybackSession/PlaybackRules).
- `:core:test` 22/22 green; `:app:testDebugUnitTest` 99 pass (6 network-gated
  skipped) against the delegates — extraction is behavior-preserving.
- `:desktopApp` (Swing, JVM 17): filesystem scan (same exts/sys-dirs rules),
  sqlite-jdbc DB on the identical schema, jaudiotagger tags, VLC `cvlc --intf rc`
  engine (queue/play/pause/seek/next/prev/speed/loop/volume/segments/resume),
  tray + notify-send notifications (MPRIS comes from VLC itself when a session
  bus exists), 4 themes with per-theme fonts, auto/phone/desktop layout toggle,
  folders/analyzer/loudness/boost/mixer/playlists/portable-import-export/stats.
- Headless parity smoke green on the full `~/Downloads/YMusic` library too
  (1177 files — the same corpus as the Android bulk runs): scan 1177/1177,
  tag read, playlist v1 round-trip + keep-missing + fuzzy relink
  (Truck Drivin Song.m4a), loudness rms 0.329, real VLC play (pos 1000ms),
  seek to 30s, pause holds.
- Faithful-look cut: the desktop repaints the Android materials in Java2D
  from the same specs and loads the same bundled TTFs (Bliss PT Sans,
  Aqua, Super) and PNGs (stars, brand icons, launcher icons, seg tile) at
  build time, so fonts and art are identical files on both platforms.
  `XpPaintTest` (10 headless pixel tests) proves Luna/night/silver/purple
  title bars, bevels, selected-row blue + focus frame, LCD deck, seg-tile
  fill, scrolling rainbow and per-section hues. Same tabs, same More
  sections in the same order with the same hues, same dialogs/menus
  (segments editor, playlist picker, sort, filter, queue reorder, stats).
- `:desktopApp:test` 13/13 green (paint, DB migrate/organize, scan/organize, single-instance lock).
- Shared schema: `core.DbSchema` (v14, 17 tables + 3 indexes) is the single source both drivers execute — Android `BlissDb.onCreate` and desktop sqlite-jdbc run the identical statements; upgrades untouched so existing users keep their data. `DbSchemaTest` pins it.
- Shared queries: `core.LibraryQueries` (sort orders verbatim, contains-narrow, canonical group keys) with tests; both apps delegate to it. `cleanLocal` does 1177 real YMusic filenames in 94ms (12.5k/s); the folder index builds 100k paths + 200k songs in 165ms (was quadratic before).
- Android adapters (`music.platform.*`): PlayerEngine/LibraryAccess/TagGateway/AudioFx/NotificationGateway/SettingsStore/AppPaths/PlaybackSession thinly wrap the existing Media3/MediaStore/TagTools/ToneControl/service paths — zero behavior change, swapping in a second impl is now mechanical. dB/millibel unit mapping verified by reading ToneControl.
- Android portable playlists: More → Export/Import portable (SAF, v1 JSON, same core code as Linux), favorites merge/replace/ignore, share-favorites toggle, missing kept in the file and reported.
- Tray: single-instance lock (second launch exits instead of a second icon), right-click menu (Show/Play-Pause/Next/Previous/Quit), Quit tears down tray + player + DB, shutdown hook on kill.
- `BlissPlayer-linux-v0.11.22.tar.gz` (20 MB) + `BlissPlayer-x86_64.AppImage`
  (~20 MB) built via `./build-linux.sh` + appimagetool; both committed.
- NOT verified: desktop UI visually (no Xvfb on this box — launch manually);
  real-car Bluetooth/aux, long handset playback, acoustic safety (unchanged
  standing gaps); full 1177-file desktop scan timing.
