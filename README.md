# BlissPlayer

A music player for Android with an Explorer-style library, classic control
panels, and a retro playback deck, in light and dark XP themes.

It plays the music on your phone, understands messy YouTube-download names,
looks up real titles, artists, albums and genres online, files everything
into a tidy `Music/BlissPlayer/Artist/Album` tree, keeps every song at the
same volume, and can push quiet outputs much louder when the phone alone is
not enough.

## Install

1. Copy the newest `BlissPlayer-v0.9.x-debug.apk` to the phone (USB, Bluetooth,
   Drive, whatever you like) and tap it. Allow "install from unknown sources"
   when asked.
2. Open BlissPlayer, grant the music/audio permission, then tap Play.
3. Accept the background-play prompt (or change it later in More) so music
   keeps going with the screen off. Swiping the app away never stops music;
   only swiping the notification itself stops it.

Requires Android 10 or newer (best on Android 13+).

## Everyday use

- **Songs / Artists / Albums / Genres / Playlists** tabs browse the library.
  The mini player always shows the true song, and the queue ("Up next")
  always shows the true play order: tap to jump, swipe to remove, drag to
  reorder. The 3-dots button on a queue song opens a menu; it never deletes.
- **Your music (More tab):** a collapsible folder tree of every folder with
  music. Tick to include, untick to skip — unticked folders stay listed,
  grayed out, and are never removed. Expand all / Collapse all work in bulk.
- **Song analyzer (More tab):** cleans "Official Video [abc123]" style names,
  then looks each song up on MusicBrainz and Wikipedia for title, artist,
  album and genre. Nothing is ever guessed or hardcoded: confident fixes apply
  with one tap ("Apply confident"), the rest wait in review (with Select all).
  Needs internet; answers are cached so each song is looked up once ever.
- **Organize into BlissPlayer folder (More tab):** moves every song in the
  library — including leftovers in Downloads or unticked folders — into
  `Music/BlissPlayer/Artist/Album`. Android asks once to allow the batch.
- **Loudness (More tab):** one tap samples every song and evens out volumes
  (nothing rewritten, Undo is one tap). The **Equalize strength** slider
  chooses subtle-to-maximum, from gentle nudges to landing every song on
  the average.
- **Boost (More tab):** master volume to 2000%, named presets with an explicit
  Delete, per-output memory, and automatic Bluetooth presets: the saved boost
  (and autoplay) comes back when a device reconnects; disconnecting falls back
  to speaker/wired. Boost above 100% can distort or damage hearing/speakers.
- **Live sound mixer (More tab):** bass/midrange/treble/preamp with headroom,
  live and file-safe. Attaches as soon as a song has an audio session.
- **Playback controls (More tab):** background play, audio-interruption
  behavior (duck, or pause-and-resume), and the pause reason line that says
  why music last paused.

## Privacy

- Crash reports stay private inside the app and are shown once. Nothing is
  ever saved to Downloads or sent anywhere; you can copy the report or export
  it to a file you choose.
- Lookups send song titles/artists to MusicBrainz and Wikipedia (that is how
  titles, albums and genres resolve). No account, no tracking, no ads.

## For developers

- `./build-apk.sh` builds the debug APK; the version patch and versionCode in
  `app/version.properties` increment on every APK/build invocation.
- `./gradlew :app:testDebugUnitTest` runs the JVM suite (includes analyzer,
  parser and focus-policy tests).
- `./gradlew :app:connectedDebugAndroidTest` runs the on-device suite on the
  `BlissTest2` emulator (regressions, bulk apply, organize, More-pane fixes).
- `BLISS_YMUSIC_ONLINE=1 ./gradlew :app:testDebugUnitTest --tests
  "com.blissplayer.music.YMusicOnlineTest"` measures analyzer coverage over
  `~/Downloads/YMusic` (slow, throttled by the lookup APIs; results resume
  from `/tmp/opencode/ycache.json`).
- Notes live in `TESTING.md` (verification evidence) and `TODO_NOTES.md`
  (user-message log).
