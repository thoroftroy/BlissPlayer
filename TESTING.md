# 0.8.0 regression checks

Run JVM checks with `./gradlew :app:testDebugUnitTest`.
APK builds automatically increment the patch and version code in
`app/version.properties`. `./build-apk.sh` also copies the versioned APK to the
repository root. Compilation, JVM tests, lint and dry runs do not increment it.
Run Android checks on a **disposable emulator**, not a personal phone:

```sh
ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedDebugAndroidTest
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

Verified on November 1, 2026: **54 JVM checks passed**, four optional network tests
skipped; **six Android API 35 emulator tests passed**. Android lint has no errors
(existing advisory/UI warnings remain).

A separate UI smoke check used shell-owned M4As to exercise the real Android
write-consent dialog, not just app-owned test files. After Allow, quotation/Topic
cleanup actually persisted for Alpha, The Hearse Song and Zulu. All three moved
into the artist/album directory with unchanged media IDs and stayed in the enabled
library. Light/dark library and Control Panel screens were visually checked.

The route snapshots emulate audio-policy events, **not a Bluetooth radio or a real
car**. Physical Bluetooth/USB handoffs, OEM battery restrictions, long-duration
playback, acoustic quality and hearing safety still need real-device testing.
Android may intentionally pause for headphone disconnects, permanent focus loss,
force-stop, permission revocation or user battery restrictions.

The test fixture is a three-second 440 Hz sine at very low volume, generated with
FFmpeg/AAC, not a user music file. Test reports are in `app/build/reports/`.
