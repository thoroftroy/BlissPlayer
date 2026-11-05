package com.blissplayer.core

/**
 * The shared behavioral contracts (§1.2). Android implements them with
 * Media3/MediaSession/MediaStore; Linux implements them with VLC-based
 * playback, filesystem access and SQLite-JDBC. UI and use-cases program
 * against these, never against platform classes.
 *
 * "Identical behavior" means: same queue/loop/resume/skip-segment rules,
 * same preset/state models, same organize safety (never touch unticked),
 * same playlist portability and missing-song policy. Documented platform
 * differences: gapless (VLC best-effort), DSP sonics (shared controls,
 * different engines), MPRIS provided by VLC itself on Linux.
 */

/** One playable item in platform-neutral terms. */
data class QueueItem(
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long
)

enum class LoopMode { OFF, ALL, ONE }

/**
 * Playback engine. Implementations own the audio backend; the queue model,
 * repeat/resume/skip-segment decisions and gain *values* come from shared
 * code and are applied through here.
 */
interface PlayerEngine {
    fun setQueue(items: List<QueueItem>, index: Int, positionMs: Long)
    fun play()
    fun pause()
    fun toggle(): Boolean
    fun next()
    fun prev()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun setLoop(mode: LoopMode)
    /** 1.0 = unity. Combines boost preset and loudness gain for the item. */
    fun setVolumeMultiplier(mult: Double)
    val isPlaying: Boolean
    val positionMs: Long
    val durationMs: Long
    fun release()
}

/** Now-playing state + transport commands, one model for notification/MPRIS/tray. */
data class NowPlaying(
    val item: QueueItem?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long
)

interface PlaybackSession {
    fun publish(state: NowPlaying)
    fun clear()
    /** Remote command from notification/tray/MPRIS/media keys. */
    fun onCommand(cmd: TransportCommand)
}

enum class TransportCommand { PLAY, PAUSE, TOGGLE, NEXT, PREV, FAVORITE }

/** EQ state model shared by both DSP paths. */
data class EqState(
    val enabled: Boolean,
    /** Ordered band gains in dB, platform maps bands to its engine. */
    val bandsDb: List<Float>,
    val preampDb: Float
)

interface AudioFx {
    fun apply(state: EqState)
    fun current(): EqState
    /** Human-readable, non-technical summary for the mixer UI. */
    fun describe(state: EqState): String
}

/** Portable library entry for scanning/organize decisions. */
data class LibraryEntry(
    val key: String,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long
)

interface LibraryAccess {
    /** All scannable roots (MediaStore volumes / desktop music folders). */
    fun roots(): List<String>
    fun scan(enabledRoots: Set<String>, onProgress: (done: Int, total: Int) -> Unit): List<LibraryEntry>
    /**
     * Move entries into Artist/Album destinations. Only entries under
     * enabled roots are ever touched; returns per-entry errors, never throws.
     */
    fun organize(entries: List<LibraryEntry>, destinations: Map<String, String>): Map<String, String>
}

/** Tag read/write behind platform file access (content URI/FD vs path). */
data class TagData(
    val title: String,
    val artist: String,
    val album: String,
    val genre: String
)

interface TagGateway {
    fun read(key: String): TagData?
    /** Returns an error string, or null on success. */
    fun write(key: String, tags: TagData): String?
}

/** Progress + completion notices; tray presence on Linux. */
interface NotificationGateway {
    fun progress(title: String, done: Int, total: Int, stopAction: (() -> Unit)? = null)
    fun done(title: String, message: String)
    fun nowPlaying(state: NowPlaying)
}

/** Prefs + platform-appropriate paths (filesDir vs ~/.config/blissplayer). */
interface SettingsStore {
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getFloat(key: String, default: Float): Float
    fun putFloat(key: String, value: Float)
}

interface AppPaths {
    val databaseFile: String
    val playlistDir: String
    val cacheDir: String
    val exportDir: String
}

/** Skip-segment + resume rules live here so both UIs order identically. */
object PlaybackRules {
    /** Effective start position honoring a leading skip segment. */
    fun startPosition(segments: List<Pair<Long, Long>>, resumeMs: Long): Long {
        val pos = resumeMs.coerceAtLeast(0)
        for ((s, e) in segments.sortedBy { it.first }) {
            if (pos in s until e) return e
        }
        return pos
    }

    /** Next position after `pos`, jumping over any segment containing it. */
    fun skipOver(segments: List<Pair<Long, Long>>, pos: Long): Long {
        for ((s, e) in segments.sortedBy { it.first }) {
            if (pos in s until e) return e
        }
        return pos
    }
}
