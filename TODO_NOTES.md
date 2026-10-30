# Music Player Project — TODO / Build Notes
Device target: Google Pixel 10 Pro (Android)
Work dir: ~/Downloads/MusicPlayer/
GitHub user (from gitconfig): Rednave_b

Goal: super lightweight, minimal battery, minimal + simplistic UI but maximum customization.
Windows XP theming (light: gray/white + blues + segmented bars) + dark mode that still feels XP.
Primary use: play music downloaded from YouTube, in car + headphones.

---

## [ ] 0. Naming + GitHub setup (DO FIRST, need user confirmation)
- [x] Create this notes file
- [ ] User picks app name (see candidates below)
- [ ] User confirms icon plan (user to provide icon later — placeholder XP-style icon for now?)
- [ ] `git init` in MusicPlayer/, initial commit with this file
- [ ] User creates empty repo on github.com (or I give commands) named e.g. `xp-music-player`
- [ ] Link local -> GitHub, so GitHub Desktop can just open ~/Downloads/MusicPlayer and push/pull
- [ ] Confirm private vs public repo

Name candidates (pick one or suggest your own):
1. XPlayer — short, XP nod
2. WinAmp'd — XP-era joke, might be trademark-adjacent
3. LunaPlay — after XP's "Luna" theme name
4. BlissPlayer — after XP Bliss wallpaper
5. XPods / XP Music — dead simple, clear

## [ ] 1. Project scaffold (after name confirmed)
- [ ] Native Kotlin + Media3/ExoPlayer (recommended for lightest battery/CPU) — confirm no Flutter/React Native
- [ ] Min SDK + Target SDK for Pixel 10 Pro (suggest min 26 / target 35-36, confirm)
- [ ] Package name e.g. `com.rednaveb.lunaplay` — confirm
- [ ] Gradle wrapper, debug signing, `build-apk.sh` one-command build
- [ ] Verify APK builds + installs (`adb install`)

## [ ] 2. Library / scanning
- [ ] Setting: choose which folder(s) to scan, default = auto-scan all device storage
- [ ] Support major extensions: mp3, m4a/aac, opus, ogg, flac, wav, mkv-audio? (confirm list)
- [ ] MediaStore scan + file fallback (for YouTube downloads with bad paths)
- [ ] Group by Artists / Albums from metadata (partial metadata tolerant — "Unknown Artist/Album" fallback)
- [ ] Song list: title, artist, album, duration, file size, format
- [ ] Search + sort (A–Z, newest, longest, etc.)
- [ ] Play All Music button (big, obvious, XP button style)

## [ ] 3. Playback core
- [ ] Play / pause / next / prev, seek bar (XP segmented style)
- [ ] Notification + lockscreen controls, headset/Bluetooth + car (Android Auto basic?) controls
- [ ] Handle audio focus (calls/nav), resume after interrupt
- [ ] Background playback, low-power (no animations draining battery, no network, no analytics)
- [ ] Lightweight check: cold start time, idle battery drain, APK size (< ~5 MB goal)

## [ ] 4. Queue logic (must be exact)
- [ ] Queue screen always shows TRUE current order
- [ ] Modes: Loop All (loop queue) / Loop One (repeat single) / Shuffle
- [ ] Shuffle = actually reorders queue list and display updates; press Shuffle again = re-shuffles current queue
- [ ] "Add to queue" / "Play next" inserts directly after current song, even when shuffled (regression test this)
- [ ] Remove / reorder (drag) / clear queue, save queue as playlist

## [ ] 5. Playlists
- [ ] Create / rename / delete playlists, add single song / album / artist to playlist
- [ ] Playlists stored locally (Room/MStore), export/import simple format (m3u?)
- [ ] Confirm: need playlist folders or just flat list?

## [ ] 6. Metadata edit + delete (in-app)
- [ ] Edit: title, artist, album, album artist?, genre, year, track # — writes back to file tag (needs SAF/File write permission flow)
- [ ] Delete: delete from library AND option to delete file from storage (with XP-style "Are you sure?" dialog + confirm)
- [ ] After edit/delete: rescan + refresh UI immediately

## [ ] 7. Volume: Boost (car speakers) — KEY FEATURE
- [ ] Master Gain / Pre-amp slider, up to +200% (confirm: 200% enough or want 300-400%?)
- [ ] Applies live on top of equalization (Gain = system volume × boost × per-song norm)
- [ ] Presets: e.g. Car / Headphones / Normal — one-tap switch (user asked: amplify higher/lower as needed)
- [ ] Safety: limiter to prevent clipping/distortion, warning at >150%?
- [ ] Confirm: remember separate boost per output device (car BT vs headphones)?

## [ ] 8. Volume: One-tap Equalize / Normalize (baked, not real-time) — KEY FEATURE
- [ ] "EQUALIZE ALL" button: scans selected music dir, measures average loudness per song (EBU R128 / RMS — confirm method)
- [ ] Computes target = average loudness across all songs
- [ ] Raises quiet songs, lowers loud songs toward target, smoothly (no pumping) — one press, no per-song fiddling
- [ ] OPEN QUESTION (need answer before build): "baked" = ?
  - Option A (recommended, non-destructive): store per-song gain in app DB, applied at playback — originals untouched, instant undo, fast
  - Option B (destructive): actually rewrite volume of MP3/FLAC files — permanent, slow, risky, can't undo
  - Default to A unless user insists on B. Confirm.
- [ ] Show progress bar (XP style) + per-song before/after dB + "Undo / Reset" button
- [ ] Works combined with Boost: final = normalized gain × user boost
- [ ] So user never touches phone volume between songs

## [ ] 9. XP Theming (light + dark), intuitive + explained
- [ ] Light theme: Luna blue title bars, light gray (#ECE9D8-ish) faces, segmented progress bars, Tahoma-like font, beveled buttons, green Start-like play button?
- [ ] Dark theme: same XP language but dark grays/navy, still beveled/segmented (not flat Material)
- [ ] Theme toggle in Settings + quick toggle on main screen
- [ ] Customization screen: accent color? title-bar gradient? font size? (minimal UI but max customization — confirm which knobs)
- [ ] Every screen has short plain-English helper text ("This button makes all songs the same loudness…"), no AI-sounding copy
- [ ] Empty states explain what to do ("No music found — tap Scan or copy YouTube downloads to /Music")

## [ ] 10. Settings / customization
- [ ] Music folders picker, rescan, file-type toggles
- [ ] Boost + loudness target + limiter toggles
- [ ] Theme (XP light / XP dark), font size, show/hide album art?
- [ ] "Explain like I'm new" help page with car/headphone walkthrough

## [ ] 11. Build, test, ship
- [ ] `./build-apk.sh` produces `app-debug.apk` (+ release later if wanted)
- [ ] Manual test checklist on Pixel 10 Pro: install, scan YouTube downloads, play all, shuffle re-shuffle, play-next while shuffled, loop modes, playlist CRUD, edit tag, delete song, boost 200% in car, equalize-all then verify no volume jumps, dark/light switch
- [ ] Push to GitHub, tag v0.1, attach APK to Release (so user can download straight to phone)
- [ ] GitHub Desktop check: open repo, see history, push/pull works

## Open questions for user (STOP — answer before any code)
1. App name + package name + repo name + private/public?
2. Equalize = Option A (safe gains DB) or B (rewrite files)?
3. Boost cap: 200% or higher? Need per-device memory (car vs headphones auto-switch)?
4. Playlist extras: m3u export needed? Android Auto needed?
5. Icon: you said you'll make one — placeholder OK until then? What size/style (XP icon 48px pixel style)?
6. GitHub: can you create the empty repo, or want me to walk you through it click-by-click?

---
Log:
- 2026-10-29: notes file created, awaiting user confirmation. Nothing else built per instructions.
