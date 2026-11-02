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
