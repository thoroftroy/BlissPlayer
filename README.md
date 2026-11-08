# BlissPlayer

A music player for Android with an Explorer-style library, classic control
panels, and a retro playback deck — in four distinct themes: **Bliss**,
**Dark Bliss**, **Aqua Bliss** (Snow Leopard glass and metal), and
**Super Bliss** (animated neon rainbow).

It plays the music on your phone, understands messy YouTube-download names,
looks up real titles, artists, albums and genres online, files everything
into a tidy `Music/BlissPlayer/Artist/Album` tree, keeps every song at the
same volume, and can push quiet outputs much louder when the phone alone is
not enough.

## Install

1. Copy the newest `BlissPlayer-v*-debug.apk` to the phone (USB, Bluetooth,
   Drive, whatever you like) and tap it. Allow "install from unknown sources"
   when asked. Only the newest APK is kept; older builds are deleted.
2. Open BlissPlayer, grant the music/audio permission, then tap Play.
3. Accept the background-play prompt (or change it later in More) so music
   keeps going with the screen off. Swiping the app away never stops music,
   and the notification stays even while paused; only swiping the
   notification itself stops it.

Requires Android 10 or newer (best on Android 13+).

## Themes

Pick in More → Look and feel → Theme. Each theme has its own type,
materials, controls, dialogs and motion — not a recolor. The home-screen
icon swaps to match the theme.

- **Bliss** — the classic XP face: Tahoma-spirited type, beige panels,
  blue title bars, green Play, segmented progress. Clicks answer with tiny
  pixel pops, nothing more.
- **Dark Bliss** — the same XP soul after lights-out, same restrained clicks.
- **Aqua Bliss** — Snow Leopard: brushed-metal grain, gel-pill buttons with
  a reflected lens, translucent water-lit panels and dialogs with drifting
  caustics, dark-ink titles, mac-style track/group glyphs, the user's own
  Aqua note artwork in the toolbar. Silver system bars with dark icons.
- **Super Bliss** — over-the-top neon rainbow: Outfit type, glowing
  glass everywhere, a fast aurora drifting behind everything, tap explosions
  with triple shockwave rings, springy buttons. The user's rainbow note art
  in the toolbar.

Motion setting (Full / Reduced / Off) plus a separate Click feedback setting
(Full / Gentle / Off) in Look and feel, honoring the system animator toggle;
ambient motion settles to a heartbeat when idle.

## Everyday use

- **Songs / Artists / Albums / Genres / Playlists** tabs browse the library.
  The mini player always shows the true song, and the queue ("Up next")
  always shows the true play order: tap to jump, swipe to remove, drag to
  reorder. The 3-dots button on a queue song opens a menu; it never deletes.
- **Favorites:** tap the star on any song row (or in its menu, the Now
  Playing screen, or the notification) to star it; tap again to unstar.
  Stars live in the pre-built Favorites playlist, always first in the
  Playlists tab. The star is the user's art: flat shapes on retro themes,
  textured blue on Aqua, animated scrolling rainbow on Super. In Shuffle
  settings you choose how stars play: exempt from cycles by default
  (same odds no matter how often they play), boosted by the favorites-rarity
  slider, or treated like regular songs.
- **Your music (More tab):** a collapsible folder tree of every folder with
  music. Tick to include, untick to skip — unticked folders stay listed,
  grayed out, and are never removed or touched by anything. Expand all /
  Collapse all work in bulk.
- **Song analyzer (More tab):** runs in the background with a silent progress
  notification (no pop-ups), skips songs already done, offers New/changed,
  Redo done, or Everything with a time estimate. It cleans "Official Video
  [abc123]" style names, then looks each song up on MusicBrainz and Wikipedia
  for title, artist, album and genre. Nothing is ever guessed or hardcoded:
  confident fixes apply with one tap ("Apply confident"), the rest wait in
  review (with Select all). Needs internet; answers are cached so each song
  is looked up once ever.
- **Organize into BlissPlayer folder (More tab):** moves songs in the
  *enabled* folders into `Music/BlissPlayer/Artist/Album`. Unticked folders
  are never moved. Android asks once to allow the batch.
- **Loudness (More tab):** runs in the background like the analyzer
  (skip-done, estimates, silent progress); one tap samples every song and
  evens out volumes (nothing rewritten, Undo is one tap). The **Equalize
  strength** slider chooses subtle-to-maximum, from gentle nudges to landing
  every song on the average.
- **Boost (More tab):** master volume to 2000%, named presets with an explicit
  Delete, per-output memory, and automatic Bluetooth presets: the saved boost
  (and autoplay) comes back when a device reconnects; disconnecting falls
  back to speaker/wired. Boost above 100% can distort or damage
  hearing/speakers. Tapping the status strip opens the exact-boost dialog.
- **Live sound mixer (More tab):** bass/midrange/treble/preamp with headroom,
  live and file-safe. Attaches as soon as a song has an audio session.
- **Playback controls (More tab):** background play, audio-interruption
  behavior (duck, or pause-and-resume), playback speed (0.5x–2x), loop mode
  (Loop All by default, remembered), and the pause reason line that says why
  music last paused.
- **Stats (More tab):** totals, full listens, skips, listening time, top
  songs/artists/genres, top-3 skips/finishes/marathons, and this-month / this-
  year buckets with their own tops. A toggle turns all tracking off, and
  Share stats file saves everything as JSON.
- **Playlists tab + Open playlists (More):** same lists, songs, adding,
  playback, rename and delete (Favorites itself can't be renamed or
  deleted), autosaved as JSON.
- **Notification:** the Bliss hill fills the system media card as the
  default background, with Previous/Play/Next plus a star to
  favorite/unfavorite the current song without opening the app.

## Privacy

- Crash reports stay private inside the app and are shown once. Nothing is
  ever saved to Downloads or sent anywhere; you can copy the report or export
  it to a file you choose.
- Lookups send song titles/artists to MusicBrainz and Wikipedia (that is how
  titles, albums and genres resolve). No account, no tracking, no ads.

## For developers

- `./build-apk.sh` builds the debug APK; the version patch and versionCode in
  `app/version.properties` increment on every APK/build invocation, and older
  versioned APKs in the repo root are pruned so only the newest remains.
- `./gradlew :app:testDebugUnitTest` runs the JVM suite (analyzer, parser,
  focus-policy and motion-model tests).
- `./gradlew :app:connectedDebugAndroidTest` runs the on-device suite on the
  `BlissTest2` emulator (regressions, bulk apply, organize, themes incl.
  animation proof, notification shades).
- `BLISS_YMUSIC_ONLINE=1 ./gradlew :app:testDebugUnitTest --tests
  "com.blissplayer.music.YMusicOnlineTest"` measures analyzer coverage over
  `~/Downloads/YMusic` (slow, throttled by the lookup APIs; results resume
  from `/tmp/opencode/ycache.json`).
- Notes live in `TESTING.md` (verification evidence) and `TODO_NOTES.md`
  (user-message log).
