# BlissPlayer

Tiny, offline, battery friendly music player with a full Windows XP look. Light Luna blue plus dark XP night mode.

In plain words: copy your songs (YouTube downloads are fine: mp3, m4a, opus, ogg, flac, wav) anywhere on the phone, tap Scan, tap Play all music. Volumes jumping between songs? Run Equalize once. Car too quiet? Set Boost near 200 percent once. The car remembers its own boost, headphones remember theirs.

## What is in this build

- Songs, Artists, Albums (grouped from file tags, tolerant of missing tags), Playlists
- Play all music and Shuffle everything buttons
- Loop Off, Loop All, Loop One. Smart Shuffle that favors songs you play less, and resets once everything has played
- Shuffle truly reorders the queue on screen. Tap again to shuffle again
- Play next always lands right after the current song, even when shuffled
- The queue screen is the real play order: tap to jump, swipe to remove, drag to reorder
- Playlists: create, rename, delete, add songs, play a whole playlist
- Edit song details (title, artist, album) and delete songs from inside the app
- Boost amp 25 to 400 percent with per device memory, Car and Headphones presets, peaks capped past 150 percent
- One tap Equalize: samples every song, centers all on the average, quiet up and loud down. Safe (gains live in the app, originals untouched), one tap Undo, repeat runs skip songs that did not change
- Playback notification with title, artist, position bar, and skip buttons. Works on the lockscreen and over Bluetooth
- Fullscreen toggle plus padding that respects the status bar and gesture bar

## Build

```bash
./build-apk.sh
```

APK lands at `BlissPlayer-v0.2-debug.apk` in this folder (copied from the Gradle output).

Needs: JDK 17+, Android SDK (build tools 35, platform 35). `local.properties` points at `~/Android/Sdk`. Change it if yours differs.

Install: copy the APK to the Pixel and tap it (allow install from unknown sources when asked), or `adb install -r BlissPlayer-v0.2-debug.apk`.

## Publish to GitHub (GitHub Desktop)

1. GitHub Desktop, File, Add Local Repository, pick this folder.
2. Publish repository, name it `BlissPlayer`, Private or Public.
3. Push future changes the same way.

## Notes

- Launcher icon is the supplied BlissIcon.
- Folder picker covers internal storage paths. Anything it cannot resolve falls back to scan everything, with a note.
- No home screen widget or Android Auto UI yet. Bluetooth, notification, and lockscreen controls work.
