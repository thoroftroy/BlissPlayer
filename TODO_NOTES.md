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

## [ ] 0. Naming + GitHub setup (two steps waiting on you)
- [x] Create this notes file
- [x] User picks app name: BlissPlayer
- [x] User icon: BlissIcon.png (360px) turned into all mipmap sizes, wired as launcher icon
- [x] `git init` in MusicPlayer/, committed, branch `main`
- [X] WAITING ON YOU: GitHub Desktop, File, Add Local Repository, pick ~/Downloads/MusicPlayer, Publish (private or public). Steps at bottom of file.
- [X] WAITING ON YOU: say private or public so the note can record it (Private for now)

Name candidates (picked #4):
1. XPlayer, short, XP nod
2. WinAmp'd, XP-era joke, might be trademark-adjacent
3. LunaPlay, after XP's "Luna" theme name
4. BlissPlayer, after XP Bliss wallpaper (CHOSEN)
5. XPods / XP Music, dead simple, clear

## [x] 1. Project scaffold (name confirmed: BlissPlayer)
- [x] Native Kotlin + Media3/ExoPlayer (lightest: no Compose, no network, no analytics)
- [x] Min SDK 29 (raised from 26, needed for the edit/delete permission flows) / target+compile 35
- [x] Package `com.blissplayer.music`
- [x] Gradle wrapper + one-command `./build-apk.sh`
- [x] APK builds clean: lint + assemble pass every time. Current: `BlissPlayer-v0.4-debug.apk`, ~6.7 MB, versionName 0.4.0 (frozen per user, code bumps silently)
- [x] Installed and launching on-device (user past v0.2.1, crash fixed, app in daily use)

## [x] 2. Library / scanning (code done, on-device in use)
- [x] Setting: choose which folder(s) to scan, default = scan everything automatically
- [x] Extensions: mp3, m4a/aac, opus, ogg, flac, wav (mkv-audio skipped, video container, out of scope)
- [x] MediaStore scan, tolerant of partial and missing tags
- [x] Group by Artists / Albums ("Unknown artist/album" fallback)
- [x] Song list: title, artist, album, duration, file size, format
- [ ] Search + sort: NOT BUILT (never asked for after v1, say the word)
- [x] Play all music button, big and green like the XP Start button
- [x] Shuffle button
- [x] Explorer header row + durations on every song row

## [x] 3. Playback core (code done, on-device in use)
- [x] Play / pause / next / prev, XP segmented seek bar (custom drawn, red skip zones in v0.4)
- [x] Notification + lockscreen + headset + Bluetooth/car controls (custom XP notification, persistent while playing)
- [x] Audio focus handled by hand: passing sounds never pause music, only a real loss like a call pauses it. Wake mode on so local playback never stalls.
- [x] Background playback: foreground service, keeps playing with the app closed or the screen off
- [x] Lightweight: no network, no analytics, no background work except while playing. APK ~6.7 MB.
- [ ] Measured cold start / battery drain numbers: NOT MEASURED (no lab setup, say the word)

## [x] 4. Queue logic, exact (code done, on-device in use)
- [x] Queue screen always shows the true current order (mirror checked against the player before every op)
- [x] Modes: Loop Off / Loop All / Loop One, Shuffle that favors less-played songs
- [x] Shuffle reorders the queue itself and the screen shows it. Tap again = re-shuffle. Gapless since v0.5 (playing song untouched).
- [x] Play next inserts directly after the current song, even when shuffled
- [x] Remove (swipe, hold) / reorder (drag), all surgical, no rebuild stutter
- [ ] Clear queue button: NOT BUILT (say the word)
- [ ] Save queue as playlist: NOT BUILT (say the word)

## [x] 5. Playlists (code done, overhauled in v0.6)
- [x] Create / rename / delete, add songs, artists, albums (picker with filter in v0.6)
- [x] Creating a playlist lands straight inside it
- [x] Stored locally in app DB
- [ ] m3u export/import: NOT BUILT (was a maybe, never confirmed)
- [ ] Playlist folders: NOT BUILT (flat list only, never confirmed)

## [x] 6. Metadata edit + delete, in app (code done, on-device in use)
- [x] Edit: title, artist, album, genre (writes the file tag AND the app copy, Uncategorized when empty)
- [ ] Album artist / year / track fields: NOT BUILT (editor covers the four used ones, say the word)
- [x] Delete song + file with confirm dialog
- [x] Rescan + refresh UI immediately after edit/delete

## [x] 7. Volume Boost (code done, on-device in use)
- [x] Boost 1 to 2000 percent, type any exact number. No cap, warnings instead (past 150 quality drops, past 500 expect distortion). Hardware sets the final ceiling.
- [x] Applies live on top of equalization (final = per-song norm x boost)
- [x] User presets with names (fixed presets removed per user), tap to use, hold to delete
- [x] Per-device auto memory with on/off toggle (car BT vs headphones)
- [x] Phone volume guard: optional restore when the OS lowers volume, throttled, points at the real system switch

## [x] 8. One-tap Equalize, baked not real-time (code done, user confirms it works well)
- [x] Measures with RMS sampling (two windows per song, all CPU cores, cache by file date). EBU R128 skipped, RMS won for speed.
- [x] Target = average loudness across all songs. Quiet up, loud down, 12 dB window.
- [x] Safe gains in app DB, originals untouched, one-tap Undo
- [x] Progress with live time estimate. Skip when nothing changed, status line shows last run and coverage.
- [ ] Per-song before/after dB readout: NOT BUILT (status line covers it, say the word)
- [x] Works combined with Boost

## [x] 9. XP theming, intuitive + explained (code done, on-device in use)
- [x] Light: Luna blue title bars, gray faces, segmented bars, beveled buttons, green Start play button
- [x] Dark: same XP language in dark grays/navy, paints the full background since v0.5, switches live with no restart
- [x] Theme toggle in More
- [ ] Accent color / font size / album art toggles: NOT BUILT (minimal UI won, say the word)
- [x] Every feature explained in its own section, plain words, no AI copy. Help page removed per user, About shows name + version only.
- [x] Product wording everywhere: README and app read as a general music player, nothing user-specific

## [x] 10. Settings / customization (code done, Minus the not-built bits below)
- [x] Music folders picker, rescan
- [ ] File-type toggles: NOT BUILT (all major types always on, say the word)
- [x] Boost presets + per-device toggle + volume guard + equalize + undo
- [x] Theme (XP light / XP dark), play counts display toggle
- [x] Stats section: library size, songs played, total plays, skips, time listening, most played song and artist
- [ ] Explained walkthrough page: REMOVED per user (each feature explains itself)

## [ ] 11. Build, test, ship (code side done, two steps waiting on you)
- [x] `./build-apk.sh` one-command build, lint clean every time
- [x] Device tested by user across versions: install, scan, play all, shuffle, play-next, loop, playlists, edit, delete, boost, equalize, dark/light, notification, segments
- [X] WAITING ON YOU: push to GitHub via Desktop, tag a release, attach the APK so the phone can pull it
- [ ] Release (signed, shrunk) build: NOT DONE (debug APK only so far, say the word)

## Open questions for user, all answered, kept for the record
1. App name: BlissPlayer. Package com.blissplayer.music. Repo private/public: still open, waiting on you.
2. Equalize: Option A, safe gains DB. Confirmed working well by user.
3. Boost: uncapped (1 to 2000 percent) with warnings. Per-device auto memory with toggle. Confirmed.
4. Playlist extras: picker built. m3u + Android Auto: never confirmed, not built.
5. Icon: user-supplied BlissIcon, wired in.
6. GitHub: local repo ready, Desktop publish waiting on you.

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

## [x] 13. Launch crash fix (2026-10-29, v0.2.1, confirmed fixed by user daily use since)
- [x] Found it: the segmented bar drawable put a shape XML inside a `<bitmap>` tag, which Android cannot decode. It inflated on the main screen, instant crash on launch. Rebuilt the segmented bar in code from a real PNG tile.
- [x] Bluetooth device lookup now permission safe (was a second crash path).
- [x] `lintDebug` is clean and runs before every build from now on.
- [x] Min SDK 26 to 29 (Android 10+, needed for the delete/edit permission flows anyway).
- [x] Crash reporter: if it ever crashes again, reopening the app shows the log with a Copy button. Paste it back here.
- [x] User installs and confirms launch works (user on v0.3+ since, no launch crash reported).

## [x] 14. Round 3 polish, v0.3.0 (2026-10-29, user feedback)
- [x] Shuffle everything is now Shuffle, Now Playing shuffle button always says Shuffle
- [x] Persistent notification built directly: song, progress bar, prev/play/next, lockscreen, headset, car BT. Plus a status row in More that opens notification settings when blocked.
- [x] Retro XP push: explorer header row, durations, mini progress bar with time, section title bars, active tab looks pressed
- [x] Skip forward and back use media item jumps, queue consistency guard, refresh on every seek, current song highlighted and auto scrolled
- [x] Boost presets removed. User presets with names, 1 to 500 percent, tap to use, hold to delete. Auto device memory with on/off toggle. Cap raised to 500.
- [x] Theme switch applies live, no restart, no lost place
- [x] Fullscreen button and code removed
- [x] Equalize uses all CPU cores and shows a live time estimate

## [x] 15. Round 4, v0.4.0 (2026-10-29): phone volume guard
- [x] Honest answer first: no normal app can switch the loud protection off, it is OS enforced. Real switch is in sound settings.
- [x] SafeVolumeGuard: while music plays, a dropped system volume is put straight back. Throttled (3 restores a minute, then 5 min cooldown with a pointer to settings). Opt in, off by default.
- [x] More pane: Phone volume guard section with plain explainer, on/off toggle, Open sound settings button.
- [x] Note: BlissPlayer Boost works on the signal, not the system slider, so keeping system volume moderate and boosting in app already avoids most warnings.

## [x] 16. Round 5, v0.5.0 (2026-10-29, user feedback)
- [x] Gapless shuffle: the playing song is never touched, only the songs behind it are swapped. No pause, no restart. Same for the main Shuffle button and drag reorder.
- [x] Boost: tap the percent to type any exact number from 1 to 2000. No quality cap, warnings instead (past 150 quality drops, past 500 expect distortion).
- [x] Dark XP mode paints the real background and every label, no restart, no lost place.
- [x] Toast audit: popups now only for errors, deletes, long op results, and the volume guard fighting the phone.
- [x] Themed notification: XP blue bar layout with buttons and progress bar in the shade. Lockscreen and car controls unchanged.

## [x] 17. Round 6, v0.6.0 (2026-10-29, user feedback)
- [x] Help section removed. About shows app name and version only.
- [x] Loudness section carries a status line (last run, set counts, coverage). Re run is blocked when nothing changed, with a measure anyway option.
- [x] Now Playing dialog follows track changes: header text updates, highlight and autoscroll fixed.
- [x] Playlists: creating one lands straight inside it. Add songs picker with artists, albums, individual songs, and a filter.
- [x] Play counts toggle in Look and feel. Lists and queue show total plays, cycle plays, and next up chance by the duration.
- [x] Stats section in More: library size, songs played, total plays, skips, time listening, most played song and artist. Skips counted from manual jumps, listen time accumulated while playing.
- [x] Every More section carries its own plain-words explainer (Music, Playback controls, Boost, Phone volume guard, Loudness, Look and feel, Stats, About). Verified, no AI copy.

## [x] 18. Round 7, still v0.4 (2026-10-29, user feedback, version frozen per user)
- [x] Skip segments: saved per song, editor with start/end in minutes:seconds, set to now buttons, tap to hear, hold to edit or delete. Red zones on the player bar, auto skip in the service. Testing pauses skipping for that song until the editor closes.
- [x] Pause gaps: passing notification sounds no longer pause music, only a real loss like a call does. Wake mode on for gapless local playback. App closed or screen off keeps playing.
- [x] Genres: stored per song, Uncategorized when empty, editor field writes the file tag and the app copy, Details shows it.
- [x] Version stays 0.4.0 until the user says bump. Code bumps silently so installs replace cleanly. One stable APK name.

## Standing process rules (from user feedback, apply to every round)
- [x] Temp todo file per round, deleted when done (rounds 2, 3, 4, 5, 6, 7 all did this).
- [x] No emojis and no em dashes in user-facing text, ever. Lint-clean + copy-checked each round.
- [x] README and app read as a general music player. User setup details go into behavior only, never into words.
- [x] Version stays 0.4.0 until the user says bump. Invisible code bumps only.
- [x] Toasts only for errors, guidance, destructive confirms, long-op results, and the volume guard fighting the phone.
- [x] Never drop a requested feature silently. If it cannot be done (OS blocks it), say so plainly with the real alternative.

## [x] 19. Round 8, still v0.4 (2026-10-29): smart search
- [x] Search bar on the library, debounced, results replace the list while active, tap crumb or X to clear.
- [x] Fuzzy match: lowercased, accents stripped, typo tolerant (1 typo short words, 2 long), ranked title first, then artist, album, genre.
- [x] Filters: artist text, album text, genre picker, playlist picker, removable chips. Song titles still match loosely with filters on.
- [x] Playing from results keeps the results as the queue context.

## [x] 20. Round 9, still v0.4 (2026-10-29): file names in search
- [x] File names (extension stripped) join the search index with fuzzy matching. Ranked below title, artist, album, genre. No lyrics, per user.

## [x] 19. Round 10, still v0.5 (2026-10-29): sandbox emulator + wake crash
- [x] Emulator (Android 35, x86_64, KVM) + AVD BlissPhone + launch script, all outside the repo. Repo stays product-only.
- [x] Disc1 (19 FLACs) pushed to Music/Disc1, APK in Download, both visible in the Files app. Reboot indexes them: 19 of 19 found.
- [x] Found it by testing: pressing play crashed without WAKE_LOCK (needed by the stay-awake fix). Permission declared, verified playing with position advancing and auto-advance.
- [x] Crash dialog now has Copy and Save to file (BlissPlayer-crash.txt in Download), since copying out is awkward.
- [x] Python GTK control panel (BlissPlayer-Sandbox.py, outside repo): start, force stop, restart, install/update app, push songs, screenshot.
