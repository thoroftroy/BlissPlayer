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
