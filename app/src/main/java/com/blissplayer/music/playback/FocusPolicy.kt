package com.blissplayer.music.playback

/**
 * Who paused the music and whether it may come back on its own.
 *
 * Pure decision logic (no Android calls) so the rules are unit tested:
 * the service feeds it focus/noisy/play events, it answers with player
 * actions and a human reason for the last system pause.
 *
 * Rules:
 * - A user pause always wins: nothing automatic resumes afterwards.
 * - A transient focus loss (call, maps, assistant) pauses and resumes
 *   alone when focus returns.
 * - A permanent loss (another music/video/game app) pauses and stays paused
 *   with a clear reason: without re-requesting we get no regain callback,
 *   so music never restarts over another app uninvited. If focus does come
 *   back while we are still the auto-paused owner, we take it. The user
 *   resumes with one tap.
 * - Duck requests only lower the volume while held.
 * - Ignore mode: focus events do nothing at all.
 */
class FocusPolicy {

    enum class Event {
        PLAY_STARTED,
        USER_PAUSE,
        STOPPED,
        FOCUS_GAIN,
        FOCUS_TRANSIENT,
        FOCUS_DUCK,
        FOCUS_LOSS,
        NOISY,
    }

    enum class Action { NONE, PAUSE, RESUME, DUCK_ON, DUCK_OFF }

    /** Last pause came from the user (or a stop): never auto-resume. */
    var userPaused = true
        private set

    /** We paused for focus/noise and may resume when it clears. */
    var autoPaused = false
        private set

    /** Volume is currently ducked under another app. */
    var ducked = false
        private set

    /** Why the music stopped: focus-transient, focus-permanent, noisy, user, null while playing. */
    var pauseReason: String? = null
        private set

    /** Focus was refused while starting: record it like any other stop. */
    fun noteDenied() {
        pauseReason = "focus-denied"
    }

    fun onEvent(e: Event, respectFocus: Boolean, isPlaying: Boolean): List<Action> {
        if (!respectFocus && (e == Event.FOCUS_GAIN || e == Event.FOCUS_TRANSIENT ||
                    e == Event.FOCUS_DUCK || e == Event.FOCUS_LOSS)
        ) {
            return listOf(Action.NONE)
        }
        return when (e) {
            Event.PLAY_STARTED -> {
                userPaused = false
                autoPaused = false
                pauseReason = null
                if (ducked) {
                    ducked = false
                    listOf(Action.DUCK_OFF)
                } else listOf(Action.NONE)
            }
            Event.USER_PAUSE -> {
                userPaused = true
                autoPaused = false
                pauseReason = "user"
                listOf(Action.NONE)
            }
            Event.STOPPED -> {
                userPaused = true
                autoPaused = false
                ducked = false
                pauseReason = "user"
                listOf(Action.NONE)
            }
            Event.FOCUS_TRANSIENT -> {
                if (!isPlaying || userPaused && !autoPaused) {
                    // Not playing, or already user-paused: nothing to save.
                    if (!isPlaying) pauseReason = pauseReason ?: "focus-transient"
                    listOf(Action.NONE)
                } else {
                    autoPaused = true
                    pauseReason = "focus-transient"
                    listOf(Action.PAUSE)
                }
            }
            Event.FOCUS_DUCK -> {
                if (!isPlaying || userPaused && !autoPaused) listOf(Action.NONE)
                else {
                    ducked = true
                    listOf(Action.DUCK_ON)
                }
            }
            Event.FOCUS_LOSS -> {
                if (!isPlaying || userPaused && !autoPaused) {
                    if (!isPlaying) pauseReason = pauseReason ?: "focus-permanent"
                    listOf(Action.NONE)
                } else {
                    autoPaused = true
                    pauseReason = "focus-permanent"
                    listOf(Action.PAUSE)
                }
            }
            Event.NOISY -> {
                if (!isPlaying || userPaused && !autoPaused) listOf(Action.NONE)
                else {
                    autoPaused = true
                    pauseReason = "noisy"
                    listOf(Action.PAUSE)
                }
            }
            Event.FOCUS_GAIN -> {
                val out = mutableListOf<Action>()
                if (ducked) {
                    ducked = false
                    out.add(Action.DUCK_OFF)
                }
                if (autoPaused && !userPaused) {
                    autoPaused = false
                    pauseReason = null
                    out.add(Action.RESUME)
                }
                if (out.isEmpty()) out.add(Action.NONE)
                out
            }
        }
    }

    companion object {
        /** One-line explanation for the More screen. Null while playing. */
        fun describe(reason: String?): String? = when (reason) {
            "focus-transient" -> "Paused: another app briefly took audio (call, maps, assistant). It should resume alone."
            "focus-permanent" -> "Paused: another app is playing audio (video, game, music). Tap play to take sound back."
            "noisy" -> "Paused: headphones or Bluetooth disconnected."
            "focus-denied" -> "Could not start: another app holds the audio."
            "stalled" -> "Playback froze (position stopped moving) and was restarted. If it repeats, the file or the audio output is struggling."
            "buffering" -> "A file would not load, so it was skipped. If it repeats, the file is broken."
            "user", null -> null
            else -> "Paused: $reason."
        }
    }
}
