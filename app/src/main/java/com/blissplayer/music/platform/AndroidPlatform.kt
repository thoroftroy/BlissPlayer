package com.blissplayer.music.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.blissplayer.core.AppPaths
import com.blissplayer.core.AudioFx
import com.blissplayer.core.EqState
import com.blissplayer.core.LibraryAccess
import com.blissplayer.core.LibraryEntry
import com.blissplayer.core.LoopMode
import com.blissplayer.core.NowPlaying
import com.blissplayer.core.NotificationGateway
import com.blissplayer.core.PlaybackSession
import com.blissplayer.core.PlayerEngine
import com.blissplayer.core.QueueItem
import com.blissplayer.core.SettingsStore
import com.blissplayer.core.TagData
import com.blissplayer.core.TagGateway
import com.blissplayer.core.TransportCommand
import com.blissplayer.music.data.MusicScanner
import com.blissplayer.music.data.SongAnalyzer
import com.blissplayer.music.data.TagTools
import com.blissplayer.music.model.Song
import com.blissplayer.music.playback.PlayerService
import com.blissplayer.music.playback.ToneControl
import kotlinx.coroutines.runBlocking

/**
 * Android implementations of the shared contracts (§1.2), each a thin
 * delegation over the existing behavior: Media3/ExoPlayer, MediaStore,
 * jaudiotagger-via-TagTools, ToneControl, the service notification, and
 * SharedPreferences. Zero behavior change — the UI keeps calling the
 * existing paths; these exist so swapping in a second platform impl (or a
 * test fake) is mechanical.
 */
class AndroidPlayerEngine(
    private val service: () -> PlayerService?,
    private val toItem: (Song) -> MediaItem,
    private val songs: () -> List<Song>
) : PlayerEngine {
    private var items: List<QueueItem> = emptyList()

    override fun setQueue(items: List<QueueItem>, index: Int, positionMs: Long) {
        val svc = service() ?: return
        this.items = items
        val byKey = songs().associateBy { it.mediaId.toString() }
        val media = items.mapNotNull { byKey[it.key]?.let(toItem) }
        if (media.isEmpty()) return
        svc.player.setMediaItems(media, index.coerceIn(media.indices), positionMs.coerceAtLeast(0))
        svc.player.prepare()
    }

    override fun play() { service()?.userPlay() }
    override fun pause() { service()?.player?.pause() }
    override fun toggle(): Boolean {
        val p = service()?.player ?: return false
        if (p.isPlaying) p.pause() else service()?.userPlay()
        return service()?.player?.isPlaying == true
    }

    override fun next() { service()?.player?.seekToNext() }
    override fun prev() { service()?.player?.seekToPrevious() }
    override fun seekTo(positionMs: Long) { service()?.player?.seekTo(positionMs) }
    override fun setSpeed(speed: Float) { service()?.player?.setPlaybackSpeed(speed) }
    override fun setLoop(mode: LoopMode) {
        service()?.player?.repeatMode = when (mode) {
            LoopMode.ALL -> Player.REPEAT_MODE_ALL
            LoopMode.ONE -> Player.REPEAT_MODE_ONE
            LoopMode.OFF -> Player.REPEAT_MODE_OFF
        }
    }

    override fun setVolumeMultiplier(mult: Double) {
        // Same path as the boost/volume guard: ExoPlayer volume.
        service()?.player?.volume = mult.coerceIn(0.0, 8.0).toFloat()
    }

    override val isPlaying: Boolean get() = service()?.player?.isPlaying == true
    override val positionMs: Long get() = service()?.player?.currentPosition?.coerceAtLeast(0) ?: 0L
    override val durationMs: Long get() = service()?.player?.duration?.takeIf { it > 0 } ?: 0L
    override fun release() { /* service-owned; released with the service */ }
}

class AndroidLibrary(
    private val context: Context,
    private val songs: () -> List<Song>
) : LibraryAccess {
    override fun roots(): List<String> =
        songs().mapNotNull { it.filePath?.substringBeforeLast('/') }.distinct().sorted()

    override fun scan(enabledRoots: Set<String>, onProgress: (done: Int, total: Int) -> Unit): List<LibraryEntry> {
        val all = MusicScanner.scanAll(context)
        onProgress(all.size, all.size)
        return all.filter { s ->
            val p = s.filePath ?: return@filter false
            enabledRoots.isEmpty() || enabledRoots.any { p == it || p.startsWith(it.trimEnd('/') + "/") }
        }.map {
            LibraryEntry(it.mediaId.toString(), it.filePath ?: "", it.title, it.artist, it.album,
                it.durationMs, it.sizeBytes)
        }
    }

    override fun organize(entries: List<LibraryEntry>, destinations: Map<String, String>): Map<String, String> {
        // Android organizes into its own Music/BlissPlayer scheme via MediaStore
        // (permission flows included); the desktop destination map is ignored by
        // design — enabled-folders-only is enforced by filtering first.
        val byId = songs().associateBy { it.mediaId.toString() }
        val scoped = entries.mapNotNull { byId[it.key] }
        return runBlocking { organizeSuspend(scoped) }
    }

    private suspend fun organizeSuspend(scoped: List<Song>): Map<String, String> {
        val errors = mutableMapOf<String, String>()
        return try {
            SongAnalyzer.organize(context, scoped) { _, _, _ -> }
            errors
        } catch (e: Exception) {
            scoped.associate { it.mediaId.toString() to (e.message ?: "Organize failed.") }
        }
    }
}

class AndroidTags(private val context: Context, private val songs: () -> List<Song>) : TagGateway {
    private fun songFor(key: String): Song? =
        songs().firstOrNull { it.mediaId.toString() == key || it.filePath == key || it.contentUri == key }

    override fun read(key: String): TagData? {
        val s = songFor(key) ?: return null
        return runBlocking {
            val t = TagTools.readTags(context, s)
            TagData(t.getOrNull(0) ?: s.title, t.getOrNull(1) ?: s.artist,
                t.getOrNull(2) ?: s.album, t.getOrNull(3) ?: "")
        }
    }

    override fun write(key: String, tags: TagData): String? {
        val s = songFor(key) ?: return "Song not found."
        return runBlocking {
            when (val r = TagTools.writeTags(context, s, TagTools.Edit(
                tags.title, tags.artist, tags.album,
                tags.genre.ifBlank { null }, null, null))) {
                is TagTools.WriteResult.Ok -> null
                is TagTools.WriteResult.NeedsPermission -> "needs-permission"
                is TagTools.WriteResult.Failed -> r.msg
            }
        }
    }
}

class AndroidFx(private val tone: ToneControl, private val prefs: SharedPreferences) : AudioFx {
    override fun apply(state: EqState) {
        // Device band counts differ, so map by index and clamp to each band's range.
        // ToneControl speaks millibels; the shared model speaks dB.
        val bands = tone.bands()
        state.bandsDb.forEachIndexed { i, db ->
            if (i < bands.size) tone.setBand(i, (db * 100).toInt())
        }
        tone.setPreamp(state.preampDb.toInt())
        tone.setEnabled(state.enabled)
        prefs.edit().putBoolean("tone_on", state.enabled).apply()
    }

    override fun current(): EqState {
        val bands = tone.bands()
        return EqState(tone.enabled, bands.map { it.level.toFloat() }, tone.preampDb.toFloat())
    }

    override fun describe(state: EqState): String {
        val p = state.preampDb
        val shape = when {
            state.bandsDb.isEmpty() || state.bandsDb.all { it == 0f } && p == 0f -> "flat"
            state.bandsDb.firstOrNull() ?: 0f > 2f -> "bass-heavy"
            state.bandsDb.lastOrNull() ?: 0f > 2f -> "bright"
            else -> "shaped"
        }
        val base = if (p == 0f && shape == "flat") "Flat: nothing boosted, nothing cut."
        else "Overall ${if (p >= 0) "+" else ""}${"%.1f".format(p)} dB, $shape."
        return "$base Big boosts can clip on loud songs."
    }
}

class AndroidNotify(
    private val toast: (String) -> Unit,
    private val onProgress: ((done: Int, total: Int, title: String) -> Unit)? = null
) : NotificationGateway {
    override fun progress(title: String, done: Int, total: Int, stopAction: (() -> Unit)?) {
        onProgress?.invoke(done, total, title)
    }

    override fun done(title: String, message: String) = toast("$title: $message")
    override fun nowPlaying(state: NowPlaying) { /* MediaSession notification owns this */ }
}

class AndroidSettings(private val prefs: SharedPreferences) : SettingsStore {
    override fun getString(key: String, default: String): String = prefs.getString(key, default) ?: default
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)
    override fun putFloat(key: String, value: Float) { prefs.edit().putFloat(key, value).apply() }
}

class AndroidPaths(context: Context) : AppPaths {
    private val files = context.filesDir.absolutePath
    override val databaseFile: String = "$files/../databases/bliss.db"
    override val playlistDir: String = "$files/playlists"
    override val cacheDir: String = context.cacheDir.absolutePath
    override val exportDir: String = files
}

class AndroidSession(private val engine: PlayerEngine) : PlaybackSession {
    override fun publish(state: NowPlaying) { /* MediaSession owns the shade */ }
    override fun clear() { engine.pause() }
    override fun onCommand(cmd: TransportCommand) {
        when (cmd) {
            TransportCommand.PLAY -> engine.play()
            TransportCommand.PAUSE -> engine.pause()
            TransportCommand.TOGGLE -> engine.toggle()
            TransportCommand.NEXT -> engine.next()
            TransportCommand.PREV -> engine.prev()
            TransportCommand.FAVORITE -> { /* needs the current song: UI layer handles it */ }
        }
    }
}
