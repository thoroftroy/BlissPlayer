# Original Prompt
Work in Downloads, I want you to make me a music player for my android phone (google pixel 10 pro) with some very specific things in it. Firstly make a todo list of this physically as a note which you check off as you go. Next here are the specificatyions, For theming make it look like an app from windows xp, im talking the light gray/white mode or blues and ther segmented bars, everyhting, also include a darkmode which still feels windows xp. Make sure to acctually build the apk and use my github to sync it and everyhitng, make it as lightweight as poissible i dont want it burning my battery, Make it super minimal and simpliscitic but with as much customization as possible. This is used mainly to play music downloaded from youtubem, You can try grouping artists/albums its partially in the metadata. i would like playlist support as well as a simple play all muysic button. It should have options to loop, loop the single song, or randomize. When its randomizing the que should acutally show the new random order, pressing randomize again will rerandomize it in the actual que. Adding a song to the que places it next even while randomized ( This seems obsous but the one im replacing couldnt do that right). I would like to be able to edit metadata, the song name, etc as well as delete songs from within the app. Now lets talk about the most important things which are specific to my usecase. I mostly just listen to the songs in my car but my car speekers are weird, i have to use poweramp right now to amplify the music volume by nearly 200% to be able to hear it well, id like that built in. ALso beacuse we are downloading from youtube and other sources many of the songs have differnt volumes, poweramp tries to equaliszee but it sucks. I want the equilization built in, not in real time but baked. Basically i want to be able to press a button to equalize all the music (also let me choose which dir it looks for music in but automatyically support all directoreies on the device and all major file extentions, anyways) equaliaxtion should find the average volume of all songs then raise the lower songs up and lower teh higher songs down, i want it to automatically and smoohtly do this so i press  equalize once and everyhting just works right and i dont have to change my bolume anymore, it shouild work with the amplification as well so i can amplify higher or lower as needed outside of the equalization (for myt headphones verses my car speakers for example). Everything needs to look good, be intuative, not sound ai, be explained, and easy for anyone to use. Make sure it all works perfectly and has every feature requested perfectly as well as anyhting else you think would be helpful to making this a good app.Im not really sure what to call it but ill come up with an icon shortly, first make the notes file then come up with some nnames and ask me for confirmation ebfore building anyhting else (also lets get it on github im assuming you need some intervention from me to do that but get it setrup so i can just use github desktop easily for this). Dont assume things, ask questions


# Music Player Project, TODO / Build Notes
Device target: Google Pixel 10 Pro (Android)
Work dir: ~/Downloads/MusicPlayer/
GitHub user (from gitconfig): Rednave_b

Goal: super lightweight, minimal battery, minimal + simplistic UI but maximum customization.
Windows XP theming (light: gray/white + blues + segmented bars) + dark mode that still feels XP.
Primary use: play music downloaded from YouTube, in car + headphones.

---

## Decisions confirmed by user (2026-10-29)
- App name: **BlissPlayer** (package `com.blissplayer.music`, repo `BlissPlayer`)
- Equalize: **Option A, safe gains DB** (non-destructive, undoable)
- Boost: **auto-switch per Bluetooth device** + Car / Headphones / Normal presets, 200% baseline (allow up to 400% with limiter warning, to confirm in testing)
- GitHub: keep local repo fully committed so user can Add Local Repository in GitHub Desktop → Publish. No `gh` CLI on this machine, so manual publish.

---

## [ ] 0. Naming + GitHub setup (DO FIRST, need user confirmation)
- [x] Create this notes file
- [x] User picks app name: BlissPlayer
- [x] User confirms icon plan: user supplied BlissIcon.png (360px) → mipmap densities generated, wired as launcher icon
- [x] `git init` in MusicPlayer/, initial commit with this file (branch `main`)
- [ ] User: GitHub Desktop → File → Add Local Repository → ~/Downloads/MusicPlayer → Publish repository (private or public), steps at bottom of file
- [ ] Confirm private vs public repo

Name candidates (pick one or suggest your own):
1. XPlayer, short, XP nod
2. WinAmp'd, XP-era joke, might be trademark-adjacent
3. LunaPlay, after XP's "Luna" theme name
4. BlissPlayer, after XP Bliss wallpaper
5. XPods / XP Music, dead simple, clear

## [x] 1. Project scaffold (name confirmed: BlissPlayer)
- [x] Native Kotlin + Media3/ExoPlayer (lightest: no Compose, no network, no analytics)
- [x] Min 26 / target+compile 35 for Pixel 10 Pro
- [x] Package `com.blissplayer.music`
- [x] Gradle wrapper + one-command `./build-apk.sh`
- [x] Verify APK builds, BUILD SUCCESSFUL 2026-10-29 (`BlissPlayer-v0.1-debug.apk`, 7.5 MB, minSdk 26 / target 35). On-device install test pending on Pixel.

## [x] 2. (code done 2026-10-29, on-device test pending) Library / scanning
- [ ] Setting: choose which folder(s) to scan, default = auto-scan all device storage
- [ ] Support major extensions: mp3, m4a/aac, opus, ogg, flac, wav, mkv-audio? (confirm list)
- [ ] MediaStore scan + file fallback (for YouTube downloads with bad paths)
- [ ] Group by Artists / Albums from metadata (partial metadata tolerant, "Unknown Artist/Album" fallback)
- [ ] Song list: title, artist, album, duration, file size, format
- [ ] Search + sort (A–Z, newest, longest, etc.)
- [ ] Play All Music button (big, obvious, XP button style)

## [x] 3. (code done 2026-10-29, on-device test pending) Playback core
- [ ] Play / pause / next / prev, seek bar (XP segmented style)
- [ ] Notification + lockscreen controls, headset/Bluetooth + car (Android Auto basic?) controls
- [ ] Handle audio focus (calls/nav), resume after interrupt
- [ ] Background playback, low-power (no animations draining battery, no network, no analytics)
- [ ] Lightweight check: cold start time, idle battery drain, APK size (< ~5 MB goal)

## [x] 4. (code done 2026-10-29, on-device test pending) Queue logic (must be exact)
- [ ] Queue screen always shows TRUE current order
- [ ] Modes: Loop All (loop queue) / Loop One (repeat single) / Shuffle
- [ ] Shuffle = actually reorders queue list and display updates; press Shuffle again = re-shuffles current queue
- [ ] "Add to queue" / "Play next" inserts directly after current song, even when shuffled (regression test this)
- [ ] Remove / reorder (drag) / clear queue, save queue as playlist

## [x] 5. (code done 2026-10-29, on-device test pending) Playlists
- [ ] Create / rename / delete playlists, add single song / album / artist to playlist
- [ ] Playlists stored locally (Room/MStore), export/import simple format (m3u?)
- [ ] Confirm: need playlist folders or just flat list?

## [x] 6. (code done 2026-10-29, on-device test pending) Metadata edit + delete (in-app)
- [ ] Edit: title, artist, album, album artist?, genre, year, track #, writes back to file tag (needs SAF/File write permission flow)
- [ ] Delete: delete from library AND option to delete file from storage (with XP-style "Are you sure?" dialog + confirm)
- [ ] After edit/delete: rescan + refresh UI immediately

## [x] 7. (code done 2026-10-29, on-device test pending) Volume: Boost (car speakers), KEY FEATURE
- [ ] Master Gain / Pre-amp slider, up to +200% (confirm: 200% enough or want 300-400%?)
- [ ] Applies live on top of equalization (Gain = system volume × boost × per-song norm)
- [ ] Presets: e.g. Car / Headphones / Normal, one-tap switch (user asked: amplify higher/lower as needed)
- [ ] Safety: limiter to prevent clipping/distortion, warning at >150%?
- [ ] Confirm: remember separate boost per output device (car BT vs headphones)?

## [x] 8. (code done 2026-10-29, on-device test pending) Volume: One-tap Equalize / Normalize (baked, not real-time), KEY FEATURE
- [ ] "EQUALIZE ALL" button: scans selected music dir, measures average loudness per song (EBU R128 / RMS, confirm method)
- [ ] Computes target = average loudness across all songs
- [ ] Raises quiet songs, lowers loud songs toward target, smoothly (no pumping), one press, no per-song fiddling
- [ ] OPEN QUESTION (need answer before build): "baked" = ?
  - Option A (recommended, non-destructive): store per-song gain in app DB, applied at playback, originals untouched, instant undo, fast
  - Option B (destructive): actually rewrite volume of MP3/FLAC files, permanent, slow, risky, can't undo
  - Default to A unless user insists on B. Confirm.
- [ ] Show progress bar (XP style) + per-song before/after dB + "Undo / Reset" button
- [ ] Works combined with Boost: final = normalized gain × user boost
- [ ] So user never touches phone volume between songs

## [x] 9. (code done 2026-10-29, on-device test pending) XP Theming (light + dark), intuitive + explained
- [ ] Light theme: Luna blue title bars, light gray (#ECE9D8-ish) faces, segmented progress bars, Tahoma-like font, beveled buttons, green Start-like play button?
- [ ] Dark theme: same XP language but dark grays/navy, still beveled/segmented (not flat Material)
- [ ] Theme toggle in Settings + quick toggle on main screen
- [ ] Customization screen: accent color? title-bar gradient? font size? (minimal UI but max customization, confirm which knobs)
- [ ] Every screen has short plain-English helper text ("This button makes all songs the same loudness…"), no AI-sounding copy
- [ ] Empty states explain what to do ("No music found, tap Scan or copy YouTube downloads to /Music")

## [x] 10. (code done 2026-10-29, on-device test pending) Settings / customization
- [ ] Music folders picker, rescan, file-type toggles
- [ ] Boost + loudness target + limiter toggles
- [ ] Theme (XP light / XP dark), font size, show/hide album art?
- [ ] "Explain like I'm new" help page with car/headphone walkthrough

## [ ] 11. Build, test, ship
- [ ] `./build-apk.sh` produces `app-debug.apk` (+ release later if wanted)
- [ ] Manual test checklist on Pixel 10 Pro: install, scan YouTube downloads, play all, shuffle re-shuffle, play-next while shuffled, loop modes, playlist CRUD, edit tag, delete song, boost 200% in car, equalize-all then verify no volume jumps, dark/light switch
- [ ] Push to GitHub, tag v0.1, attach APK to Release (so user can download straight to phone)
- [ ] GitHub Desktop check: open repo, see history, push/pull works

## Open questions for user (STOP, answer before any code)
1. App name + package name + repo name + private/public?
2. Equalize = Option A (safe gains DB) or B (rewrite files)?
3. Boost cap: 200% or higher? Need per-device memory (car vs headphones auto-switch)?
4. Playlist extras: m3u export needed? Android Auto needed?
5. Icon: you said you'll make one, placeholder OK until then? What size/style (XP icon 48px pixel style)?
6. GitHub: can you create the empty repo, or want me to walk you through it click-by-click?

---
Log:
- 2026-10-29: notes file created, awaiting user confirmation. Nothing else built per instructions.
- 2026-10-29: user confirmed: name=BlissPlayer, equalize=safe DB, boost=per-device auto, github=local-ready publish via Desktop. Proceeding to scaffold + build.

## GitHub Desktop publish steps (for user)
1. Open GitHub Desktop → File → Add Local Repository → choose `/home/microwavedthebaby/Downloads/MusicPlayer`.
2. Click "Publish repository" → name `BlissPlayer` → Private or Public → Publish.
3. Future changes: commit in app or Desktop, then Push/Pull. APKs attached via GitHub Releases (upload `app/build/outputs/apk/debug/app-debug.apk`).

## [x] 12. Round 2 polish (2026-10-29, user feedback)
- [x] Pro copy pass, no emojis, no em dashes, plain human words
- [x] Full retro XP UI pass (title chrome, panels, status strip)
- [x] Notification with song, progress bar, skip buttons
- [x] Edge to edge insets padding + fullscreen toggle
- [x] Fix empty queue bug (mirror lost on rotate, sync from player)
- [x] Segmented XP progress bars + XP seek thumbs everywhere
- [x] Vector transport icons (play, pause, next, prev, queue, overflow)
- [x] Three dot overflow on every song row
- [x] Shuffle toast says only Shuffled
- [x] Equalize optimized (sampled windows, 4 way parallel, mtime cache)
- [x] Smart Shuffle (lifetime play count weighting, resets when all played once)
- [x] Rebuild APK, verify, commit
