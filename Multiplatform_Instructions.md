# BlissPlayer Multiplatform Refactor — Detailed Guide & Checklist

> **Goal (read first):** keep the Android app, and refactor into **one project** that builds **Android + Linux (desktop)** from shared code. Share as much as possible (backend/domain logic, data models, behavior, and the look-and-feel system). Where Android-only APIs exist, keep Android behavior the same and add a Linux implementation behind the same interface. The app should feel **identical in capability and materials/panel style**, with **different layouts** per mobile vs desktop (and technically allow switching to the desktop layout on mobile and switching to mobile layout on desktop). **Separate builds, same project, shared codebase.**

**Repo context (current state):** single Android Gradle module (`:app`), Kotlin, Android Views/XML UI (64 XML resources, 4 themes, custom views like `SegmentSeekBar`), `MainActivity.kt` ~5.9k LOC, `PlayerService` (Media3 `ExoPlayer` + `MediaSessionService`) ~1.1k, hand-rolled `SQLiteOpenHelper` DB (`BlissDb`), `SongAnalyzer`/`MusicBrainzLookup`/`WikiLookup` (`java.net.HttpURLConnection` + `org.json`), tag I/O via `jaudiotagger`, audio effects via Android `Equalizer`/`LoudnessEnhancer`, coroutines, minSdk 29 / target 35. JVM unit tests exist for analyzer/parser/focus/motion/shuffle/stats.
---

## How to use this document
- Work **in phases**. Keep the Android app building, tested, and shippable after every phase.
- Check items off (`- [ ]` → `- [x]`) as you complete them. Add notes/links under each section.
- When a choice is marked **DECIDE**, pick one and record it here before implementing that area.
- Prefer **extract → interface → second implementation** over big-bang rewrites.
- Do all work possible before you stop, do not stop for any reason if there is ANYTHING else you can possibly impliment.
---

## 0. Important Notes
Compose Multiplatform (Android + Desktop/JVM):** one shared UI codebase, adaptive mobile/desktop layouts, in-app layout toggle is easy, shared theme tokens/materials/panel components.

## 0.1 Decisions, you decide what is best for this
- [ ] **D-2 Target/runtime for desktop:** JVM desktop (Compose Desktop / Swing/JavaFX shell / etc.) vs Kotlin/Native. Given `jaudiotagger` (JVM) and current tooling, **JVM desktop is the smoothest**. **Decision:** ____________________\
- [ ] **D-3 Shared DB layer:** e.g. **SQLDelight** (multiplatform, generates typed queries from schema) vs a thin shared repository with Android `SQLite` + desktop JDBC/SQLite driver. **Decision:** ____________________
- [ ] **D-4 Shared HTTP/JSON for analyzer/lookup (and playlist JSON):** keep JVM-friendly `HttpURLConnection`/`org.json` behind interfaces, or move to **Ktor + kotlinx.serialization** (multiplatform-friendly). **Decision:** ____________________
- [ ] **D-5 Linux playback backend (functionally identical contract, different impl):** candidates to evaluate — GStreamer (via bindings), VLCJ, or another JVM-capable backend. Must support local files, gapless where feasible, seek/speed, and audio-session hooks for effects/volume if possible. **Decision:** ____________________
- [ ] **D-6 Linux “session/now playing” integration:** **MPRIS** + media keys + desktop notifications + **system tray** (hide/show, controls). Pick libraries/approach for your target distros/desktops. **Decision:** ____________________
- [ ] **D-7 Audio effects parity level:** (a) identical *controls/state/presets* with best-effort per-platform DSP, or (b) pursue closer sonic parity via a shared DSP path. (a) is pragmatic; be explicit. **Decision:** ____________________
- [ ] **D-8 Playlist portability identity:** how an imported entry re-links to a local file (see §6). **Decision:** ____________________

---

## 1. Target architecture (north star)

### 1.1 Modules / project shape (illustrative — adjust to D-1/D-2)
- [ ] Confirm final module layout and record it.
```text
BlissPlayer/                    (one Gradle project/repo)
  core/                         SHARED (pure/domain + interfaces)
    domain models, analyzer, loudness/normalize/skip/queue math,
    shuffle/stats/favorites rules, playlist import/export logic,
    tag-metadata logic (abstracted I/O), library query/schema contracts
  core-android/ or android impl bindings (if needed)
  core-desktop/ or desktop impl bindings (if needed)
  ui/                           SHARED UI (if D-1 = A/C): design system + screens + adaptive layouts
  androidApp/                   Android entry: Activity/Service wiring, permissions, Android impls
  desktopApp/                   Linux/JVM entry: window/tray wiring, desktop impls
```
- [ ] Rule: **`core` has no Android framework imports and no desktop-UI imports.** Platform code lives behind interfaces (expect/actual or DI).

### 1.2 The shared contract (interfaces) — define before wiring a second platform
- [ ] `PlayerEngine` — load queue/items, play/pause/stop, seek, next/prev, speed, loop mode, position/duration/state, gapless behavior, error reporting. Applies volume/normalization/skip-segment behavior per the shared rules.
- [ ] `PlaybackSession` / `NowPlayingIntegration` — publish now-playing metadata + playback state; handle remote commands (play/pause/next/prev/seek/fav). Android = MediaSession/notification; Linux = MPRIS + media keys + notification/tray.
- [ ] `AudioFx` — EQ bands, preamp, loudness/boost/normalization controls; attach/detach to the active audio session/backend; expose state/presets (shared model).
- [ ] `LibraryAccess` — enumerate/scan audio, watch/refresh, resolve/open items, and (where supported) organize/move — abstracting MediaStore/SAF/permissions vs desktop filesystem.
- [ ] `TagGateway` — read/write tags given a platform file handle/path/descriptor (wraps `jaudiotagger` usage behind portable I/O).
- [ ] `NotificationGateway` — playback/scan/analyzer progress & completion notifications; tray presence on Linux.
- [ ] `SettingsStore` / `AppPaths` — prefs, cache dirs, export locations (platform-appropriate).
- [ ] `Clock`/dispatchers as needed for testability (you already use coroutines/test dispatchers in places).
- [ ] Document each interface’s **behavioral contract** (what “identical behavior” means) next to the code.

### 1.3 Keep Android stable throughout
- [ ] Every phase ends with: Android builds, unit tests pass, and a quick smoke run (play/pause/next, background notification, seek, a scan/analyzer action, playlist open).
- [ ] Do not change Android user-facing behavior unless explicitly intended and noted.

---

## 2. Phase plan (check off in order; phases can overlap carefully)

### Phase 0 — Baseline & safety net
- [ ] Run and record a green baseline on your machine/emulator: `./gradlew :app:testDebugUnitTest` (and `:app:connectedDebugAndroidTest` if you use the `BlissTest2` flow). Save key notes in `TESTING.md` as you go.
- [ ] Tag/branch the refactor (e.g. `multiplatform-refactor`). Keep commits small and bisectable.
- [ ] Inventory “Android-coupled” hotspots to decouple (start here): `MainActivity.kt` (~5.9k), `playback/PlayerService.kt`, `playback/ToneControl.kt`, `playback/VolumeManager.kt`, `playback/LoudnessScanner.kt`, `playback/FocusPolicy.kt`/output routing, `data/BlissDb.kt`, `data/MusicScanner.kt`, `data/TagTools.kt`, `data/*Lookup*`, `data/PlaylistJson.kt`, notification/launcher wiring in `AndroidManifest.xml`.
- [ ] Identify pure/near-pure logic to extract first (low risk, high reuse): shuffle/stats/favorites math & rules (`ShuffleMath`, stats tests), skip-segment/gain/normalize calculations, analyzer parsing/cleanup/merge/confidence (`SongAnalyzer` logic + existing tests), playlist (de)serialization model, group-merge.

### Phase 1 — Modularize on Android (no new platform yet)
- [ ] Create a `:core` (JVM/Android-compatible) module with **no Android UI/framework deps**. Move/extract pure domain + logic into it.
- [ ] Extract and share: analyzer core (parsing, YouTube-style cleanup, merge/group/confidence, apply decisions), loudness/normalize/boost/skip-segment math, queue/transport state models, shuffle/stats/favorites rules, playlist domain model.
- [ ] Put/keep **shared JVM tests** in `:core` for the above (port/adapt existing `AnalyzerTest`, `GroupMergeTest`, `SongStatsTest`, `FavoritesTest`, shuffle/math tests). These become the parity contract for Linux later.
- [ ] Carve responsibilities out of `MainActivity` into testable presenters/view-model-like classes or use-cases in `core` (state + actions in, view renders out). Keep the Android View UI working; just thin it.
- [ ] Android app depends on `:core`; behavior unchanged; all existing tests still green.
- [ ] **Exit criteria:** `:core` holds the main brains; Android app is a thinner shell over it.

### Phase 2 — Define platform interfaces + Android adapters
- [ ] Introduce the interfaces from §1.2 in `:core` (or a `core-api` slice).
- [ ] Wrap current Android implementations behind them *without changing behavior*: Media3 `PlayerService`/ExoPlayer → `PlayerEngine`; session/notification → `NowPlayingIntegration`/`NotificationGateway`; `ToneControl`/`LoudnessEnhancer` → `AudioFx`; scanner/organize/permissions/SAF → `LibraryAccess`; tag I/O → `TagGateway`; DB → repository over shared schema/driver.
- [ ] Verify: background playback, audio focus/duck/pause-resume, unplug/BT routing, notification/lockscreen/headset/car controls, loudness/boost/EQ, scan/analyzer/organize, favorites/stats/playlists — all behave exactly as before on Android.
- [ ] **Exit criteria:** Android runs entirely through interfaces; swapping in a second impl is mechanical.

### Phase 3 — Desktop (Linux/JVM) target + minimal app on shared backend
- [ ] Add a desktop/JVM module/app target (per D-2). It depends on `:core` and provides desktop implementations of the §1.2 interfaces.
- [ ] Implement **desktop `LibraryAccess`** (filesystem scan, folder picker, watch/refresh) and **`AppPaths`/`SettingsStore`**. Reuse scanner *rules* from `core`.
- [ ] Implement **desktop DB driver** for the shared schema/queries (per D-3). Confirm migrations/data model parity.
- [ ] Implement **desktop `PlayerEngine`** (per D-5) meeting the behavioral contract: queue, play/pause/seek/next/prev, speed, loop, resume, skip segments, normalization/boost application, error state. Validate with the shared contract tests + manual gapless/seek checks.
- [ ] Implement **desktop `NowPlayingIntegration`/notifications** (per D-6): MPRIS + media keys, regular desktop notifications, **system tray** (hide/show, basic controls). Android path remains the MediaSession/foreground notification.
- [ ] Bring up a **minimal desktop UI** that exercises the shared backend end-to-end (library → play → queue → playlists → analyzer/loudness). If D-1=A/C, start in the shared UI; if D-1=B, a small desktop-only shell is fine temporarily.
- [ ] **Exit criteria:** the same `core` powers Android and a working Linux desktop build for core flows.

### Phase 4 — Shared/adaptive UI, same look (materials/panels), different layouts
- [ ] (If D-1=A/C) Adopt **Compose Multiplatform** for the shared UI module. Establish a **design system** layer: theme tokens (the 4 themes — Bliss, Dark Bliss, Aqua Bliss, Super Bliss), typography, colors, surfaces/**materials**, **panel types**, and shared controls. Port the *look* (including key drawables/motion feel) — not by reusing Android XML, but by re-creating equivalent materials/components.
- [ ] Build **adaptive layouts** from the same components: compact/mobile vs desktop arrangements (an expanded, structural version of your existing horizontal vs vertical handling). Add an **in-app layout toggle** (e.g. auto / phone / desktop) so the desktop layout can be switched on technically.
- [ ] Migrate screens incrementally (keep Android usable throughout): player/now-playing + queue, library tabs (Songs/Artists/Albums/Genres/Playlists), playlist detail + **import/export**, More/settings panels (folders, analyzer, loudness, boost, mixer/EQ, playback controls, stats). Reuse `core` state/use-cases.
- [ ] Preserve per-theme character and motion settings (Full/Reduced/Off, click feedback) in the shared theme model.
- [ ] **Exit criteria:** Android + Linux share the UI code and design system; layouts adapt/toggle; Android visuals/behavior remain faithful.

### Phase 5 — Parity hardening (effects, files/organize, edge cases)
- [ ] **Audio effects:** shared preset/state model; Android keeps `Equalizer`/`LoudnessEnhancer` behavior; implement Linux `AudioFx` (per D-7). Verify attach/detach timing with the player backend.
- [ ] **File access & organize:** shared organize rules; Android keeps MediaStore/SAF/permissions flow; Linux uses filesystem + picker and safe moves. Confirm “enabled folders only / never touch unticked” semantics hold on both.
- [ ] **Tag read/write:** route through `TagGateway` over `jaudiotagger`; verify on formats you care about on both platforms (and that Android URI/SAF cases still work).
- [ ] **Playlist portability & missing-song policy** implemented end-to-end (§6) and tested across platforms.
- [ ] **Exit criteria:** feature parity per the behavioral contract; platform differences are deliberate and documented.

### Phase 6 — Performance, polish, release
- [ ] Profile **before/after** on Android (jank/main-thread I/O, startup, scan/analyzer/DB hot paths, RecyclerView/Compose perf). Fix the “small performance issues” you noted — refactoring is a good moment, but measure first.
- [ ] Profile desktop (UI responsiveness during scan/analyzer/playback, memory).
- [ ] Update build/release: Android APK flow (`build-apk.sh`, versioning) still works; add Linux packaging/distribution for the desktop build.
- [ ] Update docs: `README.md` (Linux status becomes real), `TESTING.md`, and this file with final decisions.

---

## 3. Area-by-area migration details

### 3.1 Song analyzer (shared)
- [ ] Extract parsing/cleanup (incl. “YouTube-style” name cleanup), candidate generation, merge/group/confidence, and “apply confident / review” decisions into `core`. Keep `MusicBrainzLookup`/`WikiLookup` logic shared behind a small HTTP client interface (per D-4).
- [ ] Preserve: caching/resume behavior, throttling/rate respect, “nothing guessed/hardcoded; confident vs review,” and skip-already-done modes.
- [ ] Reuse/adapt `AnalyzerTest`, `GroupMergeTest`, `CleanCorpusTest` as shared tests; keep slow/online probes (`YMusicOnlineTest`, network-gated) opt-in as today.

### 3.2 Loudness / normalization / boost / skip segments (shared math, platform apply)
- [ ] Share: normalize gain computation, boost/master math, skip-segment evaluation, resume position rules. (`LoudnessScanner` orchestration stays off-UI-thread; `SafeVolumeGuard`/policy logic shared where pure.)
- [ ] Apply per platform: Android via player volume + `LoudnessEnhancer` (current behavior preserved); Linux via its backend/mixer path. Same state model & presets.

### 3.3 Audio effects (shared state, platform DSP)
- [ ] Share: EQ band model, preamp/headroom, presets, enable/state persistence.
- [ ] Android impl: keep `Equalizer` (+ current attach behavior). Linux impl: backend/DSP equivalent (per D-7). Document any non-parity (band count/ranges) and normalize into the shared model.

### 3.4 Playback engine & queue (shared contract, platform engine)
- [ ] Share: queue model, ordering, shuffle/repeat (`Loop All` default/remembered), resume, skip segments, now-playing state, and the gain application *decisions*.
- [ ] Android impl: keep Media3/ExoPlayer + `PlayerService` semantics (gapless, wake mode) and audio-focus policy (`FocusPolicy`). Linux impl: meet the same contract (per D-5).
- [ ] Verify per platform: gapless (as supported), seek, speed, error/skip-failed-item behavior, and that “why it paused” reasons map sensibly.

### 3.5 Now playing / session / notifications / tray
- [ ] Share a single “now-playing + transport commands” state/command model (incl. favorite toggle action, consistent with current notification/UI behavior).
- [ ] Android: preserve `MediaSessionService`, lockscreen/headset/car/Bluetooth controls, and playback notification (incl. the Bliss artwork/progress style) — no regressions.
- [ ] Linux: implement **MPRIS** + media keys, regular desktop notifications, and a **system tray** icon/menu with hide-to-tray/show and basic controls. Map favorite/transport commands equivalently.

### 3.6 File access, scanning, organize (shared rules, platform I/O)
- [ ] Share: which files qualify, metadata extraction rules, “enabled folders only,” organize destination scheme (`Music/BlissPlayer/Artist/Album` concept, adapted per platform root), and never-touch-unticked/disabled semantics.
- [ ] Android: keep MediaStore/SAF/permissions and batch/confirmation behavior. Linux: filesystem enumeration + folder picker; confirm moves are safe and reversible where you promise undo.
- [ ] Keep scanning/analysis/organize off the UI thread with progress reporting (shared progress model, platform notification/progress UI).

### 3.7 Tag metadata (shared via `TagGateway`)
- [ ] Centralize tag read/write in `core` logic calling `TagGateway` (backed by `jaudiotagger`). Abstract file access (Android content URI/FD/SAF-safe path vs desktop path).
- [ ] Verify round-trips on your real library formats on both platforms.

### 3.8 Library / DB queries (shared schema & logic)
- [ ] Migrate off direct `SQLiteOpenHelper` usage into the shared schema/query layer (per D-3). Preserve tables/meaning: library items, song state, skip segments, stats/listens/finishes/skips/buckets, playlists metadata as applicable, cached analyzer/lookup results, loudness gains. Port migrations (db is currently versioned) so existing Android users keep their data.
- [ ] Keep query logic shared; only the driver/connection is platform-specific.

---

## 4. UI: same materials/panels, different layouts (shared look system)

- [ ] Define a **design system** (shared): the 4 themes, color/typography tokens, surfaces/material treatments, and **panel/control component types** (buttons, fields, rows, status/title bars, segmented progress, tabs/lists) — the semantic equivalents of today’s `xp_*`/theme drawables.
- [ ] Rebuild visuals as shared components (not Android XML resources) so Android and Linux render the **same materials/panels**. Port the 4 theme personalities (Bliss, Dark Bliss, Aqua Bliss, Super Bliss) and honor motion/click-feedback settings.
- [ ] Create **layout variants** from those same components: phone/compact vs desktop/wide (structure, navigation, density, multi-pane where it helps) — an evolution of the current horizontal vs vertical handling. Add an **in-app layout toggle** (auto/phone/desktop).
- [ ] Keep feature surfaces consistent across layouts: player/queue (reorder, swipe/remove semantics adapted per form factor), tabs, Favorites/Playlists (with import+export), More controls (folders, analyzer, loudness, boost, mixer/EQ, playback, stats).

> **Note (D-1):** literally shared, switchable layout code across Android+Linux is straightforward with a shared UI framework (Compose Multiplatform) and much harder with Android XML Views. Record your choice in D-1.

---

## 5. Decomposing `MainActivity` (critical path)
`MainActivity.kt` is ~5.9k LOC and mixes UI, state, navigation, and platform/service calls. Treat this as an explicit workstream.
- [ ] Catalog its responsibilities (library/queue UI, playback control wiring, playlists UI, analyzer/loudness/organize triggers & progress, settings/More panels, permissions/results, theme/motion/launcher icon sync).
- [ ] Extract **shared state + use-cases** into `core` (inputs/commands → state/results). No Android types.
- [ ] Leave behind thin platform views (Android Activity/Fragments or Compose screens; desktop windows) that render state and dispatch commands.
- [ ] Do this incrementally per feature/screen; keep Android working after each slice.

---

## 6. Playlists: JSON export + import, favorites sharing, missing-song handling (portable)

You asked for: export **and import** (switch between them), a setting controlling **favorites sharing**, and rules for **songs which aren’t there**.

- [ ] **Format (shared, versioned):** extend the playlist JSON (see `data/PlaylistJson.kt`) with a `formatVersion` and enough per-entry info to re-link portably. Prefer **stable, platform-neutral references** over Android-only DB IDs, e.g.:
  - relative/library path hint (when available), filename, and/or a lightweight fingerprint;
  - metadata snapshot (title/artist/album/duration) to assist matching;
  - original source URI/path as an optional hint (not relied on cross-device).
- [ ] **Export:** from Playlists (and any entry point you keep consistent), write JSON via a platform picker/location (`SettingsStore`/`AppPaths`). Include/exclude favorites per setting.
- [ ] **Import:** read JSON, validate version, and re-link entries against the local library using a deterministic strategy (record D-8). Examples to support/choose from: exact relative-path match → metadata+duration fuzzy match → mark unresolved.
- [ ] **Favorites sharing setting (shared setting, honored on both platforms):** e.g. `shareFavoritesOnExport = on/off`. On import, option/policy to merge favorites, replace, or ignore — define and make it explicit in UI.
- [ ] **Missing-song handling (define & implement consistently):** choose clear behavior and show it in the UI, e.g.
  - `skipMissing` (drop unresolved entries), or
  - `keepMissing` (retain as a “missing/unavailable” placeholder with stored metadata; never crash playback/queue), and/or
  - `attemptRelink` (manual or automatic re-match). 
  - **Decision/policy:** ____________________  (apply identically on Android + Linux)
- [ ] **Tests:** shared round-trip tests (export→import), favorites on/off, and missing/unresolved cases, including cross-platform-shaped paths.

---

## 7. Data portability & migration
- [ ] Android DB migration path preserved (existing users keep library/state/stats/gains/caches). Bump/migrate schema carefully when moving to the shared DB layer.
- [ ] Define where analyzer/loudness caches and settings live per platform (`AppPaths`), and whether/which of them are exportable.
- [ ] Keep “crash reports private, never sent/saved to Downloads” behavior (README) consistent per platform norms.

---

## 8. Testing & verification checklist (run per phase / before release)

### Shared (`core`) — automated
- [ ] Analyzer/cleanup/merge/confidence/group logic (adapt existing `AnalyzerTest`, `GroupMergeTest`, `CleanCorpusTest`).
- [ ] Loudness/normalize/boost/skip-segment/queue math.
- [ ] Shuffle, stats, favorites rules (`ShuffleMath`/`SongStats`/`Favorites` tests).
- [ ] Playlist JSON export/import round-trip, favorites toggle, missing-song policy.
- [ ] DB schema/query tests (where feasible) and migration tests for the shared layer.

### Android — regression (must stay green / equivalent)
- [ ] `./gradlew :app:testDebugUnitTest` green.
- [ ] Instrumented/connected tests you rely on (`StabilityRegressionTest`, theme/notification coverage) as applicable.
- [ ] Manual smoke: play/pause/next/prev/seek, background + screen off, notification/lockscreen/headset/car/BT controls, audio focus (duck/pause-resume) & unplug reason, EQ/boost/loudness, scan, analyzer (progress + apply/review), organize (enabled folders only), playlists import/export, favorites, stats — **no behavior regression**.

### Linux desktop — parity smoke
- [ ] Launch, scan a library, play/pause/seek/next/prev, speed/loop, resume, skip segments, normalization/boost.
- [ ] MPRIS/media keys + desktop notifications + tray hide/show/controls.
- [ ] Analyzer, loudness scan, tag read/write, organize (filesystem semantics), playlists import/export + missing-song handling, favorites/stats.
- [ ] Verify shared playlist JSON moves Android ↔ Linux per your policy.

### Performance (measure, don’t guess)
- [ ] Android: profile jank/main-thread I/O and the specific “small performance issues”; re-measure after fixes.
- [ ] Linux: profile UI responsiveness during scan/analyzer/playback.

---

## 9. Risks & gotchas (keep visible)
- [ ] **`MainActivity` gravity:** the biggest refactor risk/cost; do it incrementally with tests.
- [ ] **UI rewrite scope (if D-1=A/C):** 4 themes + materials/panels + motion are a real porting effort — budget time; keep the design system shared to avoid drift.
- [ ] **Playback parity:** “functionally identical” across Media3 and a Linux backend needs a clear contract + tests; gapless/codec edge cases may differ.
- [ ] **Audio effects parity:** platform DSPs differ; align on shared controls/state/presets and document sonic differences (D-7).
- [ ] **Android regressions:** MediaSession/foreground service, audio focus, scoped storage/SAF, and BT routing are easy to break — gate changes behind the Android smoke checklist.
- [ ] **Portable playlist linking:** path/URI schemes differ (content URIs vs filesystem paths). Solve with stable references + missing-song/relink policy (§6), not by assuming paths match.
- [ ] **`org.json`/`HttpURLConnection` on desktop JVM:** fine on JVM but confirm deps/behavior; moving to Ktor+kotlinx.serialization can simplify (D-4).
- [ ] **DB migration:** preserve existing Android user data when changing the DB layer.

---

## 10. Suggested first milestone (concrete, low-risk)
A good “Milestone 1” that delivers value and proves the architecture without touching risky Android playback/UI behavior:
- [ ] Create `:core`; extract **analyzer logic + loudness/normalize/skip math + shuffle/stats/favorites + playlist domain/JSON model** with shared tests passing.
- [ ] Add **playlist import (with favorites toggle + missing-song handling)** to the shared model (Android UI can wire it up).
- [ ] Introduce `PlayerEngine`/`LibraryAccess`/`TagGateway` interfaces; adapt Android to them with **zero behavior change**.
- [ ] Spike a **desktop JVM app** that opens the shared DB/playlist JSON and runs the analyzer/loudness logic on a folder (no full UI/playback yet). 
- [ ] Android `:app:testDebugUnitTest` + smoke still green.

---

## 11. Definition of done (for this refactor)
- [ ] One project builds **Android + Linux**; core/domain/backend logic is shared; platform-only concerns live in adapters.
- [ ] Analyzer, loudness/normalization, tags, library/query logic, and playlist import/export (favorites toggle + missing-song handling) work on both and share code/tests.
- [ ] Playback, now-playing/session/notifications (Android MediaSession/notification; Linux MPRIS/notifications/tray), audio effects, and file access/organize work per platform behind shared contracts, with Android behavior preserved.
- [ ] UI shares the same materials/panels/theme system with adaptive mobile/desktop layouts (and a layout toggle, per D-1 goal).
- [ ] Android has no user-facing regressions in core flows; performance issues measured and addressed; docs/tests updated.

---

## Reference: key current files/classes (starting points)
- UI/entry: `app/src/main/java/com/blissplayer/music/MainActivity.kt`, `app/src/main/res/layout*/`, `app/src/main/res/drawable/`, `app/src/main/res/values/themes.xml`
- Playback/session: `app/src/main/java/com/blissplayer/music/playback/PlayerService.kt`, `FocusPolicy.kt`, `OutputRouting.kt`, `ToneControl.kt`, `VolumeManager.kt`, `LoudnessScanner.kt`, `SafeVolumeGuard.kt`
- Data/domain: `data/BlissDb.kt`, `data/MusicScanner.kt`, `data/SongAnalyzer.kt`, `data/MusicBrainzLookup.kt`, `data/WikiLookup.kt`, `data/TagTools.kt`, `data/PlaylistJson.kt`, `ShuffleMath.kt`, stats/favorites logic
- Tests: `app/src/test/java/com/blissplayer/music/*` (analyzer, group merge, stats, favorites, motion, stability, shuffle)
- Build/manifest: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `build-apk.sh`

> Revisit D-1–D-8 at the top once, make the calls, and then execute phase-by-phase. Keep Android green at every step.
