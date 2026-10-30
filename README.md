# BlissPlayer

Tiny, offline, battery-friendly music player with a Windows XP look (light Luna blue + dark XP-night mode).

**In plain English:** copy your songs (YouTube downloads are fine — mp3, m4a, opus, ogg, flac, wav) anywhere on the phone, tap **Scan**, tap **▶ Play all music**. Volumes jumping between songs? Run **Equalize all songs** once. Car too quiet? Set **Boost** to ~200% once — the car remembers its own boost, headphones remember theirs.

## Features (v0.1)

- Songs / Artists / Albums (grouped from file tags, tolerant of missing tags) / Playlists
- ▶ Play all music + shuffle-everything buttons
- Loop off / loop all / loop one; shuffle truly reorders the queue on screen (tap again = re-shuffle)
- Play-next always lands right after the current song, even when shuffled
- Queue screen IS the play order: tap to jump, swipe to remove, drag to reorder
- Playlists: create / rename / delete, add songs, play a whole playlist
- Edit song details (title/artist/album) + delete songs from inside the app
- **Boost amp 25–400%** with per-Bluetooth-device memory + Car/Headphones presets + peak capping past 150%
- **One-tap Equalize**: measures every song, centers all on the average, quiet up / loud down, ±12 dB window. Safe (gains stored in app DB, originals untouched), one-tap Undo
- XP theming: blue gradient title bars, gray beveled buttons, green start button — plus dark XP mode

## Build

```bash
./build-apk.sh
# APK -> app/build/outputs/apk/debug/app-debug.apk
```

Needs: JDK 17+, Android SDK (build-tools 35, platform 35). `local.properties` points at `~/Android/Sdk` — adjust if yours differs.

Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Publish to GitHub (GitHub Desktop)

1. GitHub Desktop → File → Add Local Repository → pick this folder.
2. Publish repository → name it `BlissPlayer` → Private or Public.
3. Attach the APK under Releases so you can download it straight to the Pixel.

## Notes / limits in v0.1

- Placeholder launcher icon (blue XP tile + play triangle) until the real icon lands.
- Folder picker covers internal storage paths; SD-card trees fall back to scan-everything with a note.
- No home-screen widget / Android Auto UI yet — Bluetooth + notification + lockscreen controls work.
