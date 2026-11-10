@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.blissplayer.music.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.DecoratedMediaCustomViewStyle
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.blissplayer.music.MainActivity
import com.blissplayer.music.R
import com.blissplayer.music.data.BlissDb
import com.blissplayer.music.data.SongAnalyzer
import com.blissplayer.music.model.Seg
import com.blissplayer.music.model.Song
import com.blissplayer.music.playback.LoudnessScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.log10

/**
 * Background player. Owns ExoPlayer, loudness/boost gain math, skip segments,
 * and the persistent playback notification (song, progress bar, skip buttons)
 * that also drives the lockscreen, headset buttons, and car Bluetooth.
 * Keeps playing with the app closed and the screen off.
 *
 * finalGain(song) = normalizeGain(song) x userBoost, split across two stages:
 *  - ExoPlayer volume handles cuts (gain up to 100%)
 *  - framework LoudnessEnhancer on the player's audio session handles boost (above 100%)
 * Both are public APIs, so this works on any device without custom DSP code.
 *
 * Media3 owns audio focus, including transient loss/resume while the UI is gone.
 * Focus is managed here, not by Media3: transient interruptions pause and
 * resume alone, permanent takeovers and unplugs pause with a recorded
 * reason, and a user pause is never overridden. A stall watchdog restarts
 * frozen playback without reopening the app.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerService : MediaSessionService() {

    companion object {
        const val ACTION_TOGGLE = "bliss.toggle"
        const val ACTION_NEXT = "bliss.next"
        const val ACTION_PREV = "bliss.prev"
        const val ACTION_STOP = "bliss.stop"
        const val ACTION_FAV = "bliss.fav"
        const val ACTION_ANALYZER_STOP = "bliss.analyzer_stop"
        const val ACTION_EQ_STOP = "bliss.eq_stop"
        const val ACTION_TRIM_STOP = "bliss.trim_stop"
        const val ACTION_DUPE_STOP = "bliss.dupe_stop"
        const val ACTION_ORGANIZE_STOP = "bliss.organize_stop"
        private const val CHANNEL = "bliss_playback"
        private const val NOTIF_ID = 1001
        private const val PROGRESS_CHANNEL = "bliss_progress"
        private const val ANALYZER_NOTIF_ID = 1002
        private const val EQ_NOTIF_ID = 1003
        private const val TRIM_NOTIF_ID = 1004
        private const val DUPE_NOTIF_ID = 1005
        private const val ORGANIZE_NOTIF_ID = 1006
    }

    lateinit var player: ExoPlayer
        private set
    private var session: MediaSession? = null
    private var enhancer: LoudnessEnhancer? = null
    lateinit var volumeManager: VolumeManager
        private set
    lateinit var tone: ToneControl
        private set
    var lastPlaybackError: String? = null
        private set
    private val failedItems = mutableSetOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastResumeSave = 0L
    private var lastDevice: String? = null
    private val volumeListener: () -> Unit = {
        boost = volumeManager.boost
        if (::player.isInitialized) applyGainFor(player.currentMediaItem)
        val device = volumeManager.currentDevice
        if (lastDevice != null && lastDevice != device) {
            android.widget.Toast.makeText(this,
                "$device: ${(boost * 100).toInt()}% boost", android.widget.Toast.LENGTH_SHORT).show()
        }
        lastDevice = device
    }

    /** mediaId -> baked normalize multiplier from the Equalize scan. */
    @Volatile var normGains: Map<Long, Double> = emptyMap()

    /** Master amp set from the UI (1.0 = 100%). Persisted per Bluetooth device. */
    @Volatile var boost: Float = 1.0f

    /** Skip segments per song. Refreshed from the DB whenever they change. */
    @Volatile var segCache: Map<Long, List<Seg>> = emptyMap()

    /** While set, auto skip is off for this song so boundaries can be heard. */
    @Volatile var previewSongId: Long? = null

    private lateinit var db: BlissDb
    private val focusPolicy = FocusPolicy()
    private var audioManager: android.media.AudioManager? = null
    private var focusRequest: android.media.AudioFocusRequest? = null
    private var focusHeld = false
    private var waitingForFocus = false
    /** Set when our own focus/noise handler paused: not a user pause. */
    private var systemPauseArmed = false
    // Stall watchdog: position must advance while supposed to be playing.
    private var lastWatchPos = -1L
    private var lastWatchTime = 0L
    private var stallCount = 0
    private var bufferingSince = 0L
    private var bufferingItem: String? = null
    private val focusListener = android.media.AudioManager.OnAudioFocusChangeListener { change ->
        val respect = respectFocus()
        when (change) {
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                applyFocusActions(focusPolicy.onEvent(FocusPolicy.Event.FOCUS_GAIN, respect, player.isPlaying))
                // Delayed grant from an earlier start: take it unless paused meanwhile.
                if (waitingForFocus && !focusPolicy.userPaused) ensureFocusThenPlay()
            }
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                applyFocusActions(focusPolicy.onEvent(FocusPolicy.Event.FOCUS_TRANSIENT, respect, player.isPlaying))
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                applyFocusActions(focusPolicy.onEvent(FocusPolicy.Event.FOCUS_DUCK, respect, player.isPlaying))
            android.media.AudioManager.AUDIOFOCUS_LOSS -> {
                applyFocusActions(focusPolicy.onEvent(FocusPolicy.Event.FOCUS_LOSS, respect, player.isPlaying))
                if (respect) abandonFocus()
            }
        }
        persistPauseReason()
    }
    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            if (!::player.isInitialized || !player.isPlaying) return
            waitingForFocus = false
            systemPauseArmed = true
            applyFocusActions(focusPolicy.onEvent(FocusPolicy.Event.NOISY, true, true))
            persistPauseReason()
            // Keep the paused controls visible. Without an immediate repost
            // the bar can vanish until the next tick, which only runs while playing.
            pinForeground(false)
        }
    }
    private var noisyRegistered = false

    private fun respectFocus(): Boolean =
        getSharedPreferences("bliss", MODE_PRIVATE).getString("focus_mode", "CallsOnly") != "Off"

    private fun applyFocusActions(actions: List<FocusPolicy.Action>) {
        if (!::player.isInitialized) return
        for (a in actions) when (a) {
            FocusPolicy.Action.NONE -> {}
            FocusPolicy.Action.PAUSE -> {
                systemPauseArmed = true
                player.pause()
            }
            FocusPolicy.Action.RESUME -> ensureFocusThenPlay()
            FocusPolicy.Action.DUCK_ON, FocusPolicy.Action.DUCK_OFF -> applyGainFor(player.currentMediaItem)
        }
    }

    /** Request audio focus, then play. Retries on transient denial via regain. */
    private fun ensureFocusThenPlay() {
        if (!::player.isInitialized) return
        if (!respectFocus()) {
            focusHeld = false
            waitingForFocus = false
            player.play()
            return
        }
        if (focusHeld) {
            player.play()
            return
        }
        when (requestFocus()) {
            android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                focusHeld = true
                waitingForFocus = false
                player.play()
            }
            android.media.AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                // Another app is finishing: stay quiet until it lets go.
                // Record the intent so the coming gain actually starts us.
                waitingForFocus = true
                focusPolicy.onEvent(FocusPolicy.Event.PLAY_STARTED, respectFocus(), false)
            }
            else -> {
                waitingForFocus = false
                focusPolicy.noteDenied()
                persistPauseReason()
                systemPauseArmed = true
                player.pause()
                android.widget.Toast.makeText(this,
                    "Another app is playing audio.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestFocus(): Int {
        val am = audioManager ?: return android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED
        val req = focusRequest ?: android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .setAcceptsDelayedFocusGain(true)
            .build()
            .also { focusRequest = it }
        return runCatching { am.requestAudioFocus(req) }
            .getOrDefault(android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED)
    }

    private fun abandonFocus() {
        focusHeld = false
        waitingForFocus = false
        val am = audioManager
        val req = focusRequest
        if (am != null && req != null) runCatching { am.abandonAudioFocusRequest(req) }
    }

    private fun persistPauseReason() {
        getSharedPreferences("bliss", MODE_PRIVATE).edit()
            .putString("last_pause_reason", focusPolicy.pauseReason).apply()
    }
    private val notifManager by lazy { getSystemService(NotificationManager::class.java) }
    private var userDismissedNotif = false
    private val notifTickHandler = Handler(Looper.getMainLooper())
    private var notifTickCount = 0
    private val notifTick = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying) {
                postNotif()
                // Route checks ride along rarely: device changes also arrive
                // through the audio callback and Bluetooth broadcasts, so a
                // 5s poll here costs nothing visible.
                if (notifTickCount++ % 5 == 0) volumeManager.refreshRoute()
                watchPosition()
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastResumeSave > 15000) {
                    savePlaybackState()
                    lastResumeSave = now
                }
                notifTickHandler.postDelayed(this, 1000)
            }
        }
    }

    /**
     * The notification can claim playback while the position never moves
     * (wedged decoder/effect/output). Catch it and recover without the user
     * reopening the app: first re-kick the renderer, then skip the file.
     */
    private fun watchPosition() {
        if (!::player.isInitialized || !player.isPlaying) return
        val pos = player.currentPosition
        val now = android.os.SystemClock.elapsedRealtime()
        if (pos != lastWatchPos) {
            lastWatchPos = pos
            lastWatchTime = now
            stallCount = 0
            return
        }
        if (now - lastWatchTime < 5_000) return
        lastWatchTime = now
        stallCount++
        if (stallCount == 1) {
            recordStall("stalled")
            player.seekTo(pos)
            player.prepare()
        } else {
            recordStall("stalled")
            skipStalled()
        }
    }
    private val segTickHandler = Handler(Looper.getMainLooper())
    private val segTick = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying) {
                enforceSegments()
                segTickHandler.postDelayed(this, 250)
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            applyGainFor(item)
            savePlaybackState()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // A queue exists: the card follows it. Foreground status is
            // owned by play state, not by the queue.
            if (player.mediaItemCount > 0) postNotif()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (!::player.isInitialized) return
            val itemId = player.currentMediaItem?.mediaId
            if (state == Player.STATE_BUFFERING && player.playWhenReady) {
                // Local files never buffer long: something is wedged.
                if (bufferingItem != itemId) {
                    bufferingItem = itemId
                    bufferingSince = android.os.SystemClock.elapsedRealtime()
                } else if (android.os.SystemClock.elapsedRealtime() - bufferingSince > 15_000) {
                    bufferingItem = null
                    recordStall("buffering")
                    skipStalled()
                }
            } else {
                bufferingItem = null
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady && player.playerError == null) {
                lastPlaybackError = null
                failedItems.clear()
            }
            savePlaybackState()
        }

        override fun onPlayerError(error: PlaybackException) {
            val resume = player.playWhenReady
            failedItems.add(player.currentMediaItem?.mediaId.orEmpty())
            lastPlaybackError = "Cannot play ${player.currentMediaItem?.mediaMetadata?.title ?: "song"}: ${error.errorCodeName}"
            getSharedPreferences("bliss", MODE_PRIVATE).edit()
                .putString("last_playback_error", lastPlaybackError).apply()
            // Bound recovery: do not spin forever over an all-broken/repeating queue.
            val next = (1 until player.mediaItemCount).map { (player.currentMediaItemIndex + it) % player.mediaItemCount }
                .firstOrNull { player.getMediaItemAt(it).mediaId !in failedItems }
            if (next != null) {
                val failedId = player.currentMediaItem?.mediaId
                notifTickHandler.post {
                    if (next >= player.mediaItemCount || player.currentMediaItem?.mediaId != failedId) return@post
                    player.seekTo(next, 0)
                    player.prepare()
                    player.playWhenReady = resume
                }
            } else player.pause()
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            runCatching { enhancer?.release() }
            enhancer = null
            if (audioSessionId != C.AUDIO_SESSION_ID_UNSET && audioSessionId != 0) {
                enhancer = runCatching {
                    LoudnessEnhancer(audioSessionId).apply { enabled = true }
                }.getOrNull()
                tone.attach(audioSessionId)
            } else {
                tone.release()
            }
            applyGainFor(player.currentMediaItem)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            postNotif()
            // Foreground only while sound is coming out. Paused keeps the
            // card without the permanent foreground blame.
            pinForeground(isPlaying)
            notifTickHandler.removeCallbacks(notifTick)
            segTickHandler.removeCallbacks(segTick)
            if (isPlaying) {
                applyGainFor(player.currentMediaItem)
                applyFocusActions(focusPolicy.onEvent(FocusPolicy.Event.PLAY_STARTED, respectFocus(), true))
                persistPauseReason()
                ensureFocusHeld()
                lastWatchPos = -1L
                stallCount = 0
                notifTickHandler.post(notifTick)
                segTickHandler.post(segTick)
            } else {
                if (systemPauseArmed) {
                    systemPauseArmed = false
                } else {
                    focusPolicy.onEvent(FocusPolicy.Event.USER_PAUSE, respectFocus(), false)
                    persistPauseReason()
                    if (respectFocus()) abandonFocus()
                }
            }
        }
    }

    /** Every UI play path funnels here so focus is claimed before sound. */
    fun userPlay() {
        getSharedPreferences("bliss", MODE_PRIVATE).edit()
            .putBoolean("stopped_by_user", false).apply()
        ensureSession()
        ensureFocusThenPlay()
    }

    /** Playing without held focus (direct player.play() paths): claim it. */
    private fun ensureFocusHeld() {
        if (!::player.isInitialized || !respectFocus() || focusHeld) return
        when (requestFocus()) {
            android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> focusHeld = true
            android.media.AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> waitingForFocus = true
            else -> {
                waitingForFocus = false
                focusPolicy.noteDenied()
                persistPauseReason()
                systemPauseArmed = true
                player.pause()
            }
        }
    }

    /** Frozen-but-"playing" watchdog shared by the position and buffering checks. */
    private fun recordStall(kind: String) {
        focusPolicy.onEvent(FocusPolicy.Event.USER_PAUSE, true, false)
        getSharedPreferences("bliss", MODE_PRIVATE).edit()
            .putString("last_pause_reason", kind).apply()
    }

    /** A stall leaves the current file; try the next song before giving up. */
    private fun skipStalled() {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        if (player.mediaItemCount == 1 || player.currentMediaItemIndex >= player.mediaItemCount - 1) {
            systemPauseArmed = true
            player.pause()
            return
        }
        player.seekToNextMediaItem()
        player.prepare()
        lastWatchPos = -1L
        stallCount = 0
    }

    override fun onCreate() {
        super.onCreate()
        db = BlissDb(this)
        tone = ToneControl(getSharedPreferences("bliss", MODE_PRIVATE))
        normGains = db.allGains()
        refreshSegments()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false // focus is managed below: resume-on-regain, ducking, reasons
            )
            .setWakeMode(C.WAKE_MODE_LOCAL) // CPU stays up for gapless local playback
            .setHandleAudioBecomingNoisy(false) // own noisy receiver, so pauses attribute correctly
            .build()
        player.addListener(listener)
        player.setPlaybackSpeed(getSharedPreferences("bliss", MODE_PRIVATE).getFloat("speed", 1f))

        audioManager = getSystemService(android.media.AudioManager::class.java)
        if (!noisyRegistered) {
            noisyRegistered = true
            runCatching {
                androidx.core.content.ContextCompat.registerReceiver(
                    this, noisyReceiver,
                    android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }
        }
        configureAudioFocus()
        volumeManager = (application as com.blissplayer.music.BlissApplication).volumeManager
        volumeManager.addListener(volumeListener)
        volumeManager.onDeviceConnected = { device ->
            // An autoplay device is itself an explicit play intent: it
            // clears a previous user stop, so reconnection always works.
            if (volumeManager.isAutoplay(device)) {
                getSharedPreferences("bliss", MODE_PRIVATE).edit()
                    .putBoolean("stopped_by_user", false).apply()
                if (player.mediaItemCount > 0) startPlayback() else restoreSavedSession(play = true)
            }
        }
        volumeManager.start()
        volumeListener()

        ensureSession()
        setMediaNotificationProvider(notifProvider)
    }

    /** A swipe-away stop releases the session so Media3 cannot repost its
     * card; the next play rebuilds it. */
    private fun ensureSession() {
        if (session != null) return
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(pi)
            .build()
    }

    fun configureAudioFocus() {
        if (!::player.isInitialized) return
        if (!respectFocus()) {
            abandonFocus()
        } else if (player.isPlaying) {
            ensureFocusHeld()
        }
    }

    /** Android 15+ requires foreground status before a background focus request. */
    private fun startPlayback() {
        if (player.mediaItemCount == 0) return
        userDismissedNotif = false
        ensureSession()
        runCatching { startForeground(NOTIF_ID, buildNotification()) }.onFailure {
            lastPlaybackError = "Android blocked background playback: ${it.message}"
            getSharedPreferences("bliss", MODE_PRIVATE).edit()
                .putString("last_playback_error", lastPlaybackError).apply()
        }
        // Recover from a wedged idle state (route change left the renderer
        // with no duration and a dead play flag). Re-prepare so the toggle
        // always makes sound and the position bar always moves.
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
            runCatching { player.prepare() }
            lastWatchPos = -1L
            lastWatchTime = android.os.SystemClock.elapsedRealtime()
            stallCount = 0
            bufferingItem = null
        }
        waitingForFocus = false
        ensureFocusThenPlay()
        // The bar must move immediately after a tap, not after the next tick.
        postNotif()
    }

    private fun savePlaybackState() {
        if (!::player.isInitialized) return
        val prefs = getSharedPreferences("bliss", MODE_PRIVATE)
        if (player.mediaItemCount == 0) {
            prefs.edit().putBoolean("resume_playing", false).apply()
            return
        }
        prefs.edit()
            .putString("resume_queue", (0 until player.mediaItemCount).joinToString(",") { player.getMediaItemAt(it).mediaId })
            .putInt("resume_index", player.currentMediaItemIndex.coerceAtLeast(0))
            .putLong("resume_pos", player.currentPosition.coerceAtLeast(0))
            .putInt("resume_repeat", player.repeatMode)
            .putBoolean("resume_playing", player.playWhenReady && player.playbackState != Player.STATE_ENDED)
            .apply()
    }

    private fun restoreSavedSession(play: Boolean) {
        ensureSession()
        scope.launch {
            val prefs = getSharedPreferences("bliss", MODE_PRIVATE)
            val ids = prefs.getString("resume_queue", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
            if (ids.isEmpty()) return@launch
            val savedIndex = prefs.getInt("resume_index", 0).coerceIn(0, ids.lastIndex)
            val position = prefs.getLong("resume_pos", 0).coerceAtLeast(0)
            try {
                val library = withContext(Dispatchers.IO) { com.blissplayer.music.data.MusicScanner.scanAll(this@PlayerService) }
                    .associateBy { it.mediaId }
                if (player.mediaItemCount != 0) return@launch
                val found = ids.mapNotNull { library[it] }
                if (found.isEmpty()) return@launch
                val index = found.indexOfFirst { it.mediaId == ids[savedIndex] }.coerceAtLeast(0)
                val items = found.map { song -> MediaItem.Builder().setMediaId(song.mediaId.toString())
                    .setUri(song.contentUri).setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album)
                        .apply { setArtworkUri(com.blissplayer.music.data.DefaultArt.artworkUri(this@PlayerService)) }.build()).build() }
                player.setMediaItems(items, index, position)
                player.repeatMode = prefs.getInt("resume_repeat", Player.REPEAT_MODE_ALL).coerceIn(0, 2)
                player.prepare()
                if (play) startPlayback() else player.pause()
                // Killed while swiped away: the queue exists but no UI will
                // repost the card, so pin it now. Foreground only if the
                // queue was playing; paused restores as a background card.
                if (player.mediaItemCount > 0) {
                    pinForeground(play)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastPlaybackError = "Could not restore playback: ${e.message}"
            }
        }
    }

    /** Foreground only while sound is coming out. Paused keeps the card
     * through a plain notify, without the permanent foreground blame that
     * gets the app battery-restricted. Stickiness plus the saved queue
     * bring everything back if the system reclaims the process. */
    private fun pinForeground(playing: Boolean) {
        if (!::player.isInitialized) return
        if (playing && player.mediaItemCount > 0) {
            runCatching { startForeground(NOTIF_ID, buildNotification()) }
        } else {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
            } else {
                @Suppress("DEPRECATION")
                runCatching { stopForeground(false) }
            }
            postNotif()
        }
    }

    /** Jump over any skip segment the play head lands in. Chained zones resolve in order. */
    private fun enforceSegments() {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        if (previewSongId == id) return
        val segs = segCache[id] ?: return
        if (segs.isEmpty()) return
        var pos = player.currentPosition
        var guard = 0
        while (guard++ < 8) {
            val hit = segs.firstOrNull { pos >= it.startMs && pos < it.endMs } ?: break
            pos = hit.endMs.coerceAtMost(player.duration.coerceAtLeast(0))
            player.seekTo(pos)
        }
    }

    fun refreshSegments() {
        // Loading every song's segments each refresh is heavy on huge libraries,
        // so cache only songs that actually have segments.
        val map = mutableMapOf<Long, List<Seg>>()
        runCatching {
            db.readableDatabase.rawQuery("SELECT DISTINCT media_id FROM segments", null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val list = db.segmentsFor(id)
                    if (list.isNotEmpty()) map[id] = list
                }
            }
        }
        segCache = map
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away never stops music or its controls: pause,
        // play and Bluetooth autoplay keep working with no UI open. Only
        // the notification's own swipe (ACTION_STOP) or an empty queue
        // ends the service.
        if (::player.isInitialized && player.mediaItemCount > 0) {
            // Card survives without the app; foreground only while playing.
            pinForeground(player.isPlaying)
            return
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            val prefs = getSharedPreferences("bliss", MODE_PRIVATE)
            // A user stop is definitive: a system restart must not resurrect
            // music the user already dismissed.
            if (prefs.getBoolean("stopped_by_user", false)) {
                stopSelf()
                return@onStartCommand START_STICKY
            }
            // Killed while swiped away: bring the queue back (paused unless
            // it was playing) so controls and autoplay keep working.
            if (prefs.getString("resume_queue", "").orEmpty().isNotEmpty()) {
                restoreSavedSession(play = prefs.getBoolean("resume_playing", false))
            }
        }
        if (::player.isInitialized) {
            when (intent?.action) {
                ACTION_TOGGLE -> if (player.playWhenReady) player.pause() else startPlayback()
                ACTION_NEXT -> player.seekToNextMediaItem()
                ACTION_PREV -> player.seekToPreviousMediaItem()
                ACTION_FAV -> toggleFavoriteNow()
                ACTION_ANALYZER_STOP -> cancelAnalyzer()
                ACTION_EQ_STOP -> {
                    // Old builds shared one Stop for eq/trim/dupes. Cancel all
                    // three so a stale Stop never leaves work running to repost.
                    cancelEqualizer()
                    cancelTrim(silent = true)
                    cancelDupes(silent = true)
                }
                ACTION_TRIM_STOP -> cancelTrim()
                ACTION_DUPE_STOP -> cancelDupes()
                ACTION_ORGANIZE_STOP -> cancelOrganize()
                // Swiping the notification away means done with music: stop it all.
                // This is the user's definitive stop: no restart, no restore,
                // until the user presses play again or an autoplay device connects.
                // The session is released too: Media3 posts its card
                // asynchronously, so a cancel alone would pop back up.
                ACTION_STOP -> {
                    focusPolicy.onEvent(FocusPolicy.Event.STOPPED, respectFocus(), false)
                    userDismissedNotif = true
                    persistPauseReason()
                    abandonFocus()
                    getSharedPreferences("bliss", MODE_PRIVATE).edit()
                        .putBoolean("stopped_by_user", true)
                        .remove("resume_queue")
                        .remove("resume_index")
                        .remove("resume_pos")
                        .remove("resume_playing")
                        .apply()
                    player.pause()
                    player.clearMediaItems()
                    runCatching { notifManager.cancel(NOTIF_ID) }
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    runCatching { session?.release() }
                    session = null
                    stopSelf()
                }
            }
        }
        super.onStartCommand(intent, flags, startId)
        // Never stop on its own: keep playing in other apps, screen off, swiped away.
        return START_STICKY
    }

    private val notifProvider = object : MediaNotification.Provider {
        override fun createNotification(
            session: MediaSession,
            customLayout: com.google.common.collect.ImmutableList<androidx.media3.session.CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            callback: MediaNotification.Provider.Callback
        ): MediaNotification {
            return MediaNotification(NOTIF_ID, buildNotification())
        }

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: android.os.Bundle
        ): Boolean = false
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Song info and playback controls"
            notifManager.createNotificationChannel(ch)
        }
    }

    private fun actionIntent(action: String, code: Int): PendingIntent {
        val i = Intent(this, PlayerService::class.java).setAction(action)
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val simple = getSharedPreferences("bliss", MODE_PRIVATE).getString("notif_style", "Full") != "Full"
        val md = player.currentMediaItem?.mediaMetadata
        val playing = player.isPlaying
        val dur = player.duration.coerceAtLeast(0)
        val pos = player.currentPosition.coerceIn(0, dur)
        val title = md?.title?.toString() ?: "BlissPlayer"
        val artist = listOfNotNull(
            md?.artist?.toString(),
            md?.albumTitle?.toString()
        ).joinToString(" | ").ifBlank { "Ready" }
        val playIcon = if (playing) R.drawable.ic_pause_w else R.drawable.ic_play_w
        val curId = player.currentMediaItem?.mediaId?.toLongOrNull()
        val loved = curId?.let { runCatching { db.isFavorite(it) }.getOrDefault(false) } ?: false
        // The user's star: white art, bright when loved, dimmed when not.
        val favAlpha = if (loved) 255 else 110
        val favLabel = if (loved) "Unfavorite" else "Favorite"

        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setDeleteIntent(actionIntent(ACTION_STOP, 5))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", actionIntent(ACTION_PREV, 1))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                actionIntent(ACTION_TOGGLE, 2)
            )
            .addAction(android.R.drawable.ic_media_next, "Next", actionIntent(ACTION_NEXT, 3))
            .addAction(if (loved) android.R.drawable.star_on else android.R.drawable.star_off, favLabel, actionIntent(ACTION_FAV, 6))
            .setStyle(
                if (simple) MediaStyle()
                    .setMediaSession(session?.sessionCompatToken)
                    .setShowActionsInCompactView(0, 1, 2)
                else DecoratedMediaCustomViewStyle()
                    .setMediaSession(session?.sessionCompatToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setColor(0xFF0058E6.toInt())
        // Default art in both styles: the full Bliss hill, full-bleed.
        b.setLargeIcon(notifArt())
        if (!simple) {
            b.setSubText("BlissPlayer")
            val small = RemoteViews(packageName, R.layout.xp_notif_small).apply {
                setTextViewText(R.id.n_title, title)
                setTextViewText(R.id.n_artist, artist)
                setImageViewResource(R.id.n_play, playIcon)
                setImageViewResource(R.id.n_next, R.drawable.ic_next_w)
                setImageViewResource(R.id.n_fav, R.drawable.notif_star_w)
                setInt(R.id.n_fav, "setImageAlpha", favAlpha)
                setOnClickPendingIntent(R.id.n_play, actionIntent(ACTION_TOGGLE, 2))
                setOnClickPendingIntent(R.id.n_next, actionIntent(ACTION_NEXT, 3))
                setOnClickPendingIntent(R.id.n_fav, actionIntent(ACTION_FAV, 6))
                if (dur > 0) setProgressBar(R.id.n_progress, 1000, ((pos * 1000) / dur).toInt().coerceIn(0, 1000), false)
                else setProgressBar(R.id.n_progress, 1000, 0, false)
            }
            val big = RemoteViews(packageName, R.layout.xp_notif_big).apply {
                setTextViewText(R.id.n_title, title)
                setTextViewText(R.id.n_artist, artist)
                setImageViewResource(R.id.n_play, playIcon)
                setImageViewResource(R.id.n_next, R.drawable.ic_next_w)
                setImageViewResource(R.id.n_prev, R.drawable.ic_prev_w)
                setImageViewResource(R.id.n_fav, R.drawable.notif_star_w)
                setInt(R.id.n_fav, "setImageAlpha", favAlpha)
                setOnClickPendingIntent(R.id.n_play, actionIntent(ACTION_TOGGLE, 2))
                setOnClickPendingIntent(R.id.n_next, actionIntent(ACTION_NEXT, 3))
                setOnClickPendingIntent(R.id.n_prev, actionIntent(ACTION_PREV, 1))
                setOnClickPendingIntent(R.id.n_fav, actionIntent(ACTION_FAV, 6))
                setTextViewText(R.id.n_time, "${fmt(pos)} / ${fmt(dur)}")
                if (dur > 0) setProgressBar(R.id.n_progress, 1000, ((pos * 1000) / dur).toInt().coerceIn(0, 1000), false)
                else setProgressBar(R.id.n_progress, 1000, 0, false)
            }
            b.setCustomContentView(small)
            b.setCustomBigContentView(big)
        }
        // Always show a progress bar so the position never vanishes. When the
        // duration is unknown (player idle after a route change) show zero
        // instead of hiding the bar entirely.
        if (dur > 0) b.setProgress(dur.toInt().coerceAtLeast(1), pos.toInt(), false)
        else b.setProgress(1000, 0, false)
        return b.build()
    }

    /** Default notification art: the full Bliss hill, center-cropped full-bleed
     * (cover, never stretched). Falls back to the painted gradient if missing. */
    private fun notifArt(): android.graphics.Bitmap = cachedNotifArt

    private val cachedNotifArt: android.graphics.Bitmap by lazy {
        fullBleedArt() ?: gradientArt()
    }

    private fun fullBleedArt(): android.graphics.Bitmap? =
        com.blissplayer.music.data.DefaultArt.bitmap(this)

    private fun gradientArt(): android.graphics.Bitmap {
        val size = 512
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint()
        val grad = android.graphics.LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            0xFF0058E6.toInt(), 0xFF3A93FF.toInt(),
            android.graphics.Shader.TileMode.CLAMP
        )
        paint.shader = grad
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null
        val icon = android.graphics.BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        if (icon != null) {
            val s = (size * 0.52).toInt()
            val scaled = android.graphics.Bitmap.createScaledBitmap(icon, s, s, true)
            c.drawBitmap(scaled, ((size - s) / 2).toFloat(), ((size - s) / 2 - 30).toFloat(), null)
        }
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = 52f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        c.drawText("BlissPlayer", (size / 2).toFloat(), (size - 52).toFloat(), paint)
        val frame = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 10f
            color = 0xFFFFFFFF.toInt()
        }
        c.drawRect(5f, 5f, (size - 5).toFloat(), (size - 5).toFloat(), frame)
        return bmp
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Notification heart: favorite/unfavorite the playing song from anywhere. */
    private fun toggleFavoriteNow() {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        scope.launch(Dispatchers.IO) {
            val on = try {
                val cur = db.isFavorite(id)
                db.setFavorite(id, !cur)
                !cur
            } catch (e: Exception) { return@launch }
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    this@PlayerService,
                    if (on) "Added to Favorites." else "Removed from Favorites.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                postNotif()
            }
        }
    }

    private fun postNotif() {
        if (!::player.isInitialized) return
        // Respect the user swiping the card away: never resurrect it while
        // nothing is playing. Pressing play (or a device autoplay) re-enables it.
        if (userDismissedNotif && !player.isPlaying) return
        runCatching { notifManager.notify(NOTIF_ID, buildNotification()) }
    }

    fun repostNotif() {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        postNotif()
    }

    // ---------- background library jobs (analyzer + equalizer) ----------
    // Both run here (not in the activity) so swiping the app away, rotating,
    // or pressing back never interrupts them. Progress shows only as a
    // silent notification plus an in-app status line: no pop-ups.

    /** In-app progress observer: (kind, done, total, status line). */
    var progressListener: ((kind: String, done: Int, total: Int, text: String) -> Unit)? = null

    private var analyzerJob: Job? = null
    private var eqJob: Job? = null
    private var trimJob: Job? = null
    private var dupeJob: Job? = null
    private var organizeJob: Job? = null

    data class DupePair(val a: Song, val b: Song, val score: Int)
    data class NameClash(val title: String, val artist: String, val songs: List<Song>)
    var dupePairs: List<DupePair>? = null
        private set
    var dupeClashes: List<NameClash>? = null
        private set

    fun takeDupes(): Pair<List<DupePair>, List<NameClash>>? {
        val p = dupePairs ?: return null
        val c = dupeClashes ?: emptyList()
        dupePairs = null
        dupeClashes = null
        return p to c
    }

    /** Finished analyzer proposals waiting for review (cleared on take). */
    var pendingProposals: List<com.blissplayer.core.AnalyzerRules.Proposal>? = null
        private set

    fun takeProposals(): List<com.blissplayer.core.AnalyzerRules.Proposal>? {
        val p = pendingProposals
        pendingProposals = null
        return p
    }

    val analyzerRunning: Boolean get() = analyzerJob?.isActive == true
    val eqRunning: Boolean get() = eqJob?.isActive == true
    val trimRunning: Boolean get() = trimJob?.isActive == true
    val dupeRunning: Boolean get() = dupeJob?.isActive == true
    val organizeRunning: Boolean get() = organizeJob?.isActive == true
    val anyWorkRunning: Boolean get() = analyzerRunning || eqRunning || trimRunning || dupeRunning || organizeRunning

    private fun ensureProgressChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(PROGRESS_CHANNEL, "Library work", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Silent analyzer/equalizer progress"
            ch.setSound(null, null)
            notifManager.createNotificationChannel(ch)
        }
    }

    private fun progressNotif(
        id: Int, title: String, text: String, done: Int, total: Int, stopAction: String, stopCode: Int
    ) {
        ensureProgressChannel()
        val open = PendingIntent.getActivity(
            this, 40 + id, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, PROGRESS_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setSilent(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), done.coerceIn(0, total.coerceAtLeast(1)), false)
            .addAction(0, "Stop", actionIntent(stopAction, stopCode))
            .build()
        runCatching { notifManager.notify(id, n) }
    }

    private fun doneNotif(id: Int, title: String, text: String) {
        ensureProgressChannel()
        val open = PendingIntent.getActivity(
            this, 50 + id, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, PROGRESS_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setSilent(true)
            .setAutoCancel(true)
            .build()
        runCatching { notifManager.notify(id, n) }
    }

    private fun fmtEta(done: Int, total: Int, elapsedMs: Long): String {
        if (done <= 0 || total <= 0) return "starting"
        val rate = done / (elapsedMs.coerceAtLeast(1) / 1000.0)
        if (rate <= 0) return "starting"
        val left = ((total - done) / rate).toLong()
        return when {
            left < 5 -> "finishing up"
            left < 90 -> "about $left sec left"
            else -> "about ${left / 60} min left"
        }
    }

    /** Analyzer in the service: survives UI death, silent progress only. */
    fun startAnalyzer(
        songs: List<Song>, online: Boolean,
        genres: Map<Long, String>, albums: Map<Long, String>
    ) {
        analyzerJob?.cancel()
        pendingProposals = null
        val total = songs.size
        if (total == 0) return
        val t0 = System.currentTimeMillis()
        progressNotif(ANALYZER_NOTIF_ID, "Analyzing $total songs",
            "Starting.", 0, total, ACTION_ANALYZER_STOP, 11)
        progressListener?.invoke("analyzer", 0, total, "Starting.")
        analyzerJob = scope.launch(Dispatchers.Default) {
            try {
                val cache = object : SongAnalyzer.LookupCache {
                    override fun get(title: String, artist: String) =
                        runCatching { db.getLookup(SongAnalyzer.cacheKey(title, artist)) }.getOrNull()

                    override fun put(title: String, artist: String, hit: SongAnalyzer.CachedLookup) {
                        runCatching { db.putLookup(SongAnalyzer.cacheKey(title, artist), hit) }
                    }
                }
                val proposals = try {
                    SongAnalyzer.analyze(
                        songs, { genres[it] ?: "Uncategorized" },
                        { albums[it] ?: "Unknown album" },
                        online,
                        onProgress = { done, tot, phase ->
                            val eta = fmtEta(done, tot, System.currentTimeMillis() - t0)
                            val text = when (phase) {
                                "names" -> "Cleaning names ($done of $tot, $eta)."
                                "lookup" -> "Checking online index ($done of $tot, $eta)."
                                else -> "Finishing up."
                            }
                            progressNotif(ANALYZER_NOTIF_ID, "Analyzing $tot songs",
                                text, done, tot, ACTION_ANALYZER_STOP, 11)
                            progressListener?.invoke("analyzer", done, tot, text)
                        },
                        lookupCache = cache
                    )
                } catch (e: SongAnalyzer.OfflineException) {
                    pendingProposals = e.partial
                    doneNotif(ANALYZER_NOTIF_ID, "Analyzer done (offline)",
                        "No internet: cleaned names only, ${e.partial.size} to review.")
                    progressListener?.invoke("analyzer", total, total, "Done offline.")
                    return@launch
                }
                for (s in songs) {
                    if (!isActive) break
                    runCatching { db.markAnalyzed(s.mediaId, db.fingerprint(s.sizeBytes, s.modified)) }
                }
                runCatching { notifManager.cancel(ANALYZER_NOTIF_ID) }
                if (proposals.isEmpty()) {
                    doneNotif(ANALYZER_NOTIF_ID, "Analyzer done", "Nothing to fix.")
                    progressListener?.invoke("analyzer", total, total, "Done: nothing to fix.")
                } else {
                    pendingProposals = proposals
                    doneNotif(ANALYZER_NOTIF_ID, "Analyzer done",
                        "${proposals.size} songs to review. Tap to open.")
                    progressListener?.invoke("analyzer", total, total,
                        "Done: ${proposals.size} to review.")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                runCatching { notifManager.cancel(ANALYZER_NOTIF_ID) }
                doneNotif(ANALYZER_NOTIF_ID, "Analyzer stopped", "What finished is kept.")
                progressListener?.invoke("analyzer", 0, total, "Stopped.")
            }
        }
    }

    fun cancelAnalyzer() {
        analyzerJob?.cancel()
        runCatching { notifManager.cancel(ANALYZER_NOTIF_ID) }
        progressListener?.invoke("analyzer", 0, 0, "Stopped.")
    }

    /** Equalizer in the service: silent progress, gains to db, no pop-ups. */
    fun startEqualizer(songs: List<Song>, strength: Float, accuracy: Float = 0.35f) {
        eqJob?.cancel()
        val total = songs.size
        if (total == 0) return
        val t0 = System.currentTimeMillis()
        progressNotif(EQ_NOTIF_ID, "Equalizing $total songs",
            "Starting.", 0, total, ACTION_EQ_STOP, 12)
        progressListener?.invoke("equalizer", 0, total, "Starting.")
        eqJob = scope.launch(Dispatchers.Default) {
            try {
                val cached = db.getMeasures().mapValues { LoudnessScanner.Cached(it.value.first, it.value.second) }
                val res = LoudnessScanner.equalizeAll(
                    this@PlayerService, songs,
                    cached = cached,
                    strength = strength,
                    accuracy = accuracy,
                    putGain = { id, mult -> db.putGain(id, mult) },
                    putCache = { id, rms, mod -> db.putMeasure(id, rms, mod) },
                    onProgress = { done, tot, title, _ ->
                        val eta = fmtEta(done, tot, System.currentTimeMillis() - t0)
                        val text = "Listening ($done of $tot, $eta): $title"
                        progressNotif(EQ_NOTIF_ID, "Equalizing $tot songs",
                            text, done, tot, ACTION_EQ_STOP, 12)
                        progressListener?.invoke("equalizer", done, tot, text)
                    }
                )
                for (s in songs) {
                    if (!isActive) break
                    runCatching { db.markEq(s.mediaId, db.fingerprint(s.sizeBytes, s.modified)) }
                }
                runCatching { refreshGains() }
                val prefs = getSharedPreferences("bliss", MODE_PRIVATE)
                prefs.edit()
                    .putLong("eq_last", System.currentTimeMillis())
                    .putInt("eq_measured", res.measured)
                    .putInt("eq_up", res.raised)
                    .putInt("eq_down", res.lowered)
                    .putFloat("eq_strength_used", strength)
                    .apply()
                runCatching { notifManager.cancel(EQ_NOTIF_ID) }
                val reused = if (res.reused > 0) " ${res.reused} reused." else ""
                doneNotif(EQ_NOTIF_ID, "Equalize done",
                    "Up ${res.raised}, down ${res.lowered}.$reused Tap to open.")
                progressListener?.invoke("equalizer", total, total,
                    "Done: up ${res.raised}, down ${res.lowered}.")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                runCatching { notifManager.cancel(EQ_NOTIF_ID) }
                doneNotif(EQ_NOTIF_ID, "Equalize stopped", "What finished is kept.")
                progressListener?.invoke("equalizer", 0, total, "Stopped.")
            }
        }
    }

    fun cancelEqualizer() {
        eqJob?.cancel()
        runCatching { notifManager.cancel(EQ_NOTIF_ID) }
        progressListener?.invoke("equalizer", 0, 0, "Stopped.")
    }

    /** Trim silence: waveform edges, parallel, no realtime listening. */
    fun startSilenceTrim(songs: List<Song>) {
        if (trimJob?.isActive == true) return
        trimJob?.cancel()
        val total = songs.size
        if (total == 0) return
        val t0 = System.currentTimeMillis()
        progressNotif(TRIM_NOTIF_ID, "Trimming silence in $total songs",
            "Starting.", 0, total, ACTION_TRIM_STOP, 13)
        progressListener?.invoke("trim", 0, total, "Starting.")
        trimJob = scope.launch(Dispatchers.Default) {
            var added = 0
            var skippedDone = 0
            try {
                val sem = kotlinx.coroutines.sync.Semaphore((Runtime.getRuntime().availableProcessors() * 2).coerceIn(8, 12))
                val done = java.util.concurrent.atomic.AtomicInteger(0)
                val states = runCatching { db.stateMap(songs.map { it.mediaId }) }.getOrDefault(emptyMap())
                // Per-file tracking: a finished file is never decoded again.
                val todo = songs.filter { s ->
                    val st = states[s.mediaId]
                    if (st != null && st.trimmed && st.fingerprint == db.fingerprint(s.sizeBytes, s.modified)) {
                        skippedDone++
                        return@filter false
                    }
                    val existing = runCatching { db.segmentsFor(s.mediaId) }.getOrDefault(emptyList())
                    val hasLead = existing.any { it.startMs <= 500 && it.label == "Silence" }
                    val hasTrail = existing.any { it.endMs >= s.durationMs - 1000 && it.label == "Silence" }
                    if (hasLead && hasTrail) {
                        runCatching { db.markTrimmed(s.mediaId, db.fingerprint(s.sizeBytes, s.modified)) }
                        skippedDone++
                        false
                    } else true
                }
                if (todo.isEmpty()) {
                    runCatching { notifManager.cancel(TRIM_NOTIF_ID) }
                    doneNotif(TRIM_NOTIF_ID, "Silence trim done", "Already trimmed. Nothing to do.")
                    progressListener?.invoke("trim", total, total, "Done: already trimmed.")
                    return@launch
                }
                coroutineScope {
                    todo.map { s ->
                        async {
                            sem.withPermit {
                                ensureActive()
                                val res = try {
                                    LoudnessScanner.silenceTrim(
                                        this@PlayerService,
                                        android.net.Uri.parse(s.contentUri),
                                        s.durationMs
                                    )
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    null
                                }
                                if (res != null) {
                                    val (start, end) = res
                                    runCatching {
                                        val existing = db.segmentsFor(s.mediaId)
                                        val hasLead = start > 0 && existing.any { it.startMs <= 500 && it.label == "Silence" }
                                        val hasTrail = end < s.durationMs && existing.any { it.endMs >= s.durationMs - 1000 && it.label == "Silence" }
                                        if (start > 0 && !hasLead) {
                                            db.addSegment(s.mediaId, 0, start, "Silence")
                                            synchronized(this@PlayerService) { added++ }
                                        }
                                        if (end < s.durationMs && !hasTrail) {
                                            db.addSegment(s.mediaId, end, s.durationMs, "Silence")
                                            synchronized(this@PlayerService) { added++ }
                                        }
                                    }
                                }
                                runCatching { db.markTrimmed(s.mediaId, db.fingerprint(s.sizeBytes, s.modified)) }
                                val d = done.incrementAndGet()
                                if (d % 10 == 0 || d == todo.size) {
                                    ensureActive()
                                    val eta = fmtEta(d, todo.size, System.currentTimeMillis() - t0)
                                    val text = "Waveform edges ($d of ${todo.size}, $eta): ${s.title}"
                                    progressNotif(TRIM_NOTIF_ID, "Trimming silence in $total songs",
                                        text, d, todo.size, ACTION_TRIM_STOP, 13)
                                    progressListener?.invoke("trim", d, todo.size, text)
                                }
                            }
                        }
                    }.forEach { it.await() }
                }
                runCatching { refreshSegments() }
                runCatching { notifManager.cancel(TRIM_NOTIF_ID) }
                val reuse = if (skippedDone > 0) " $skippedDone already trimmed." else ""
                doneNotif(TRIM_NOTIF_ID, "Silence trim done", "$added edge segments added.$reuse Tap to open.")
                progressListener?.invoke("trim", total, total, "Done: $added edge segments added.$reuse")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    runCatching { notifManager.cancel(TRIM_NOTIF_ID) }
                    progressListener?.invoke("trim", 0, total, "Stopped.")
                    throw e
                }
                runCatching { notifManager.cancel(TRIM_NOTIF_ID) }
                doneNotif(TRIM_NOTIF_ID, "Silence trim stopped", "What finished is kept.")
                progressListener?.invoke("trim", 0, total, "Stopped.")
            }
        }
    }

    fun cancelTrim(silent: Boolean = false) {
        trimJob?.cancel()
        runCatching { notifManager.cancel(TRIM_NOTIF_ID) }
        // Also clear the legacy shared id in case this Stop came from an old notification.
        runCatching { notifManager.cancel(EQ_NOTIF_ID) }
        if (!silent) progressListener?.invoke("trim", 0, 0, "Stopped.")
    }

    /** Dupe finder: waveform fingerprints compared, never realtime playback. */
    fun startDupeFinder(songs: List<Song>) {
        dupeJob?.cancel()
        dupePairs = null
        dupeClashes = null
        val total = songs.size
        if (total == 0) return
        val t0 = System.currentTimeMillis()
        progressNotif(DUPE_NOTIF_ID, "Finding duplicates in $total songs",
            "Starting.", 0, total, ACTION_DUPE_STOP, 14)
        progressListener?.invoke("dupes", 0, total, "Starting.")
        dupeJob = scope.launch(Dispatchers.Default) {
            try {
                ensureActive()
                // Narrow first: same duration within 3s, or same normalized title.
                fun normTitle(s: Song): String {
                    return s.title.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim().replace(Regex("\\s+"), " ")
                }
                fun normArtist(s: Song): String {
                    return s.artist.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim().replace(Regex("\\s+"), " ")
                }
                val byDur = songs.groupBy { it.durationMs / 3000 }
                val candidates = mutableSetOf<Pair<Long, Long>>()
                for ((_, group) in byDur) {
                    if (group.size < 2 || group.size > 60) continue
                    for (i in group.indices) for (j in i + 1 until group.size) {
                        candidates.add(group[i].mediaId to group[j].mediaId)
                    }
                }
                val byName = songs.groupBy { normTitle(it) + "|" + normArtist(it) }
                for ((_, group) in byName) {
                    if (group.size < 2 || group.size > 30) continue
                    for (i in group.indices) for (j in i + 1 until group.size) {
                        candidates.add(group[i].mediaId to group[j].mediaId)
                    }
                }
                // Cap pairs so huge libraries still finish.
                val pairList = candidates.toList().take(4000)
                val byId = songs.associateBy { it.mediaId }
                val prints = java.util.concurrent.ConcurrentHashMap<Long, com.blissplayer.core.AudioFingerprint.Print>()
                val needIds = pairList.flatMap { listOf(it.first, it.second) }.toSet().take(600)
                var reusedFp = 0
                // Waveform cache: a redo never re-listens to an unchanged file.
                for (id in needIds) {
                    val s = byId[id] ?: continue
                    runCatching { db.dupeFine(id, s.modified) }.getOrNull()?.let {
                        prints[id] = it
                        reusedFp++
                    }
                }
                val toPrint = needIds.filter { !prints.containsKey(it) }
                val doneFp = java.util.concurrent.atomic.AtomicInteger(0)
                val sem = kotlinx.coroutines.sync.Semaphore((Runtime.getRuntime().availableProcessors() * 2).coerceIn(8, 12))
                coroutineScope {
                    toPrint.map { id ->
                        async {
                            sem.withPermit {
                                ensureActive()
                                val s = byId[id] ?: return@withPermit
                                val p = try {
                                    LoudnessScanner.fingerprint(this@PlayerService, android.net.Uri.parse(s.contentUri), s.durationMs)
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    null
                                }
                                if (p != null) {
                                    prints[id] = p
                                    runCatching {
                                        db.putDupeFine(id, p.rms, p.fine, p.durationMs, s.modified)
                                    }
                                }
                                val d = doneFp.incrementAndGet()
                                if (d % 10 == 0 || d == toPrint.size) {
                                    ensureActive()
                                    val eta = fmtEta(d, toPrint.size, System.currentTimeMillis() - t0)
                                    val text = "Waveform compare ($d of ${toPrint.size}, $eta)"
                                    progressNotif(DUPE_NOTIF_ID, "Finding duplicates in $total songs",
                                        text, d, toPrint.size, ACTION_DUPE_STOP, 14)
                                    progressListener?.invoke("dupes", d, toPrint.size, text)
                                }
                            }
                        }
                    }.forEach { it.await() }
                }
                val bar = com.blissplayer.core.AudioFingerprint.DUPE_THRESHOLD
                val pairs = mutableListOf<DupePair>()
                val seen = mutableSetOf<Pair<Long, Long>>()
                fun note(aId: Long, bId: Long, score: Int) {
                    if (score < bar) return
                    val key = if (aId < bId) aId to bId else bId to aId
                    if (!seen.add(key)) return
                    val a = byId[aId] ?: return
                    val b = byId[bId] ?: return
                    pairs.add(DupePair(a, b, score))
                }
                for ((aId, bId) in pairList) {
                    val pa = prints[aId] ?: continue
                    val pb = prints[bId] ?: continue
                    note(aId, bId, com.blissplayer.core.AudioFingerprint.similarity(pa, pb))
                }
                // Same title and artist: a heard match is a duplicate pair,
                // otherwise the shared name is a tag clash worth fixing.
                val clashes = mutableListOf<NameClash>()
                for ((_, group) in byName) {
                    if (group.size < 2) continue
                    var best = 0
                    var heard = 0
                    for (i in group.indices) for (j in i + 1 until group.size) {
                        val pa = prints[group[i].mediaId] ?: continue
                        val pb = prints[group[j].mediaId] ?: continue
                        heard++
                        val score = com.blissplayer.core.AudioFingerprint.similarity(pa, pb)
                        if (score > best) best = score
                        note(group[i].mediaId, group[j].mediaId, score)
                    }
                    // Unheard groups (too many pairs to fingerprint) are
                    // never claimed as clashes: only heard differences are.
                    if (heard > 0 && best < bar) {
                        val first = group.first()
                        clashes.add(NameClash(first.title, first.artist, group))
                    }
                }
                pairs.sortByDescending { it.score }
                dupePairs = pairs.take(200)
                dupeClashes = clashes.take(100)
                runCatching { notifManager.cancel(DUPE_NOTIF_ID) }
                val reuse = if (reusedFp > 0) " $reusedFp waveforms reused." else ""
                val msg = "${pairs.size} similar pairs, ${clashes.size} same name different audio.$reuse Tap to open."
                doneNotif(DUPE_NOTIF_ID, "Dupe finder done", msg)
                progressListener?.invoke("dupes", total, total, "Done: $msg")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    runCatching { notifManager.cancel(DUPE_NOTIF_ID) }
                    progressListener?.invoke("dupes", 0, total, "Stopped.")
                    throw e
                }
                runCatching { notifManager.cancel(DUPE_NOTIF_ID) }
                doneNotif(DUPE_NOTIF_ID, "Dupe finder stopped", "What finished is kept.")
                progressListener?.invoke("dupes", 0, total, "Stopped.")
            }
        }
    }

    fun cancelDupes(silent: Boolean = false) {
        dupeJob?.cancel()
        runCatching { notifManager.cancel(DUPE_NOTIF_ID) }
        runCatching { notifManager.cancel(EQ_NOTIF_ID) }
        if (!silent) progressListener?.invoke("dupes", 0, 0, "Stopped.")
    }

    /** Files the last organize run could not move without a write grant. */
    var organizePending: List<Song>? = null
        private set

    fun takeOrganizePending(): List<Song>? {
        val p = organizePending
        organizePending = null
        return p
    }

    data class OrganizeSummary(
        val moved: Int, val alreadyDone: Int,
        val needsPermission: Int, val failed: Int, val notes: List<String>
    )

    /** Structured result of the last organize run (cleared on take). */
    var organizeSummary: OrganizeSummary? = null
        private set

    fun takeOrganizeSummary(): OrganizeSummary? {
        val s = organizeSummary
        organizeSummary = null
        return s
    }

    /**
     * Organize in the service: survives back/rotate/swipe-away with a silent
     * progress notification and Stop. Finished files are tracked per file, so
     * a rerun only touches new or changed songs. When Android demands a
     * write grant, the leftovers wait in organizePending for the UI to ask
     * once, then the run resumes with just those files.
     */
    fun startOrganize(songs: List<Song>) {
        if (organizeJob?.isActive == true) return
        organizeJob?.cancel()
        organizePending = null
        val total = songs.size
        if (total == 0) return
        val t0 = System.currentTimeMillis()
        progressNotif(ORGANIZE_NOTIF_ID, "Organizing $total songs",
            "Starting.", 0, total, ACTION_ORGANIZE_STOP, 15)
        progressListener?.invoke("organize", 0, total, "Starting.")
        organizeJob = scope.launch(Dispatchers.Default) {
            try {
                val states = runCatching { db.stateMap(songs.map { it.mediaId }) }.getOrDefault(emptyMap())
                var skippedDone = 0
                val todo = songs.filter { s ->
                    val st = states[s.mediaId]
                    if (st != null && st.organized && st.fingerprint == db.fingerprint(s.sizeBytes, s.modified)) {
                        skippedDone++
                        false
                    } else true
                }
                if (todo.isEmpty()) {
                    runCatching { notifManager.cancel(ORGANIZE_NOTIF_ID) }
                    doneNotif(ORGANIZE_NOTIF_ID, "Organize done", "Already organized. Nothing to do.")
                    progressListener?.invoke("organize", total, total, "Done: already organized.")
                    return@launch
                }
                val res = SongAnalyzer.organize(this@PlayerService, todo) { done, tot, name ->
                    val eta = fmtEta(done, tot, System.currentTimeMillis() - t0)
                    val text = if (name.isBlank()) "Done." else "Moving ($done of $tot, $eta): $name"
                    progressNotif(ORGANIZE_NOTIF_ID, "Organizing $total songs",
                        text, done, tot, ACTION_ORGANIZE_STOP, 15)
                    progressListener?.invoke("organize", done, tot, text)
                }
                for (id in todo.map { it.mediaId }) {
                    if (!isActive) break
                    val s = todo.firstOrNull { it.mediaId == id } ?: continue
                    if (res.needsPermission.contains(s.contentUri)) continue
                    runCatching { db.markOrganized(id, db.fingerprint(s.sizeBytes, s.modified)) }
                }
                runCatching { notifManager.cancel(ORGANIZE_NOTIF_ID) }
                val reuse = if (skippedDone > 0) " $skippedDone already organized." else ""
                val failed = (res.skipped - res.alreadyOrganized - res.needsPermission.size).coerceAtLeast(0)
                organizeSummary = OrganizeSummary(
                    res.moved, res.alreadyOrganized + skippedDone,
                    res.needsPermission.size, failed, res.notes
                )
                if (res.needsPermission.isNotEmpty()) {
                    organizePending = todo.filter { res.needsPermission.contains(it.contentUri) }
                    doneNotif(ORGANIZE_NOTIF_ID, "Organize needs permission",
                        "Moved ${res.moved}.$reuse Tap to allow the rest.")
                    progressListener?.invoke("organize", total, total,
                        "Done: moved ${res.moved}.$reuse Needs permission.")
                } else {
                    doneNotif(ORGANIZE_NOTIF_ID, "Organize done",
                        "Moved ${res.moved} songs.$reuse Tap to open.")
                    progressListener?.invoke("organize", total, total,
                        "Done: moved ${res.moved}.$reuse")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    runCatching { notifManager.cancel(ORGANIZE_NOTIF_ID) }
                    progressListener?.invoke("organize", 0, total, "Stopped.")
                    throw e
                }
                runCatching { notifManager.cancel(ORGANIZE_NOTIF_ID) }
                doneNotif(ORGANIZE_NOTIF_ID, "Organize stopped", "What finished is kept.")
                progressListener?.invoke("organize", 0, total, "Stopped.")
            }
        }
    }

    fun cancelOrganize() {
        organizeJob?.cancel()
        runCatching { notifManager.cancel(ORGANIZE_NOTIF_ID) }
        progressListener?.invoke("organize", 0, 0, "Stopped.")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        savePlaybackState()
        scope.cancel()
        abandonFocus()
        if (noisyRegistered) {
            noisyRegistered = false
            runCatching { unregisterReceiver(noisyReceiver) }
        }
        notifTickHandler.removeCallbacksAndMessages(null)
        segTickHandler.removeCallbacksAndMessages(null)
        volumeManager.removeListener(volumeListener)
        volumeManager.onDeviceConnected = null
        volumeManager.stop()
        tone.release()
        session?.release()
        if (::player.isInitialized) {
            player.removeListener(listener)
            player.release()
        }
        runCatching { enhancer?.release() }
        db.close()
        super.onDestroy()
    }

    /** Recompute output gain for the now-playing item. Up to 2000 percent, no quality cap. */
    fun applyGainFor(item: MediaItem?) {
        val id = item?.mediaId?.toLongOrNull()
        val norm = if (id != null) (normGains[id] ?: 1.0).toFloat() else 1.0f
        val duck = if (focusPolicy.ducked) 0.25f else 1f
        val total = (norm * boost * tone.gain() * duck).coerceIn(0.0001f, 80.0f)
        // Stage 1: ExoPlayer volume does everything up to unity.
        player.volume = total.coerceIn(0.0f, 1.0f)
        // Stage 2: enhancer adds the rest as dB above unity. The phone clamps to its own max.
        // If the session callback never fired, the enhancer may be missing:
        // retry from the live session so boost above 100% never silently drops.
        var enh = enhancer
        if (enh == null && total > 1.0f && ::player.isInitialized) {
            val sid = runCatching { player.audioSessionId }.getOrDefault(0)
            if (sid != 0 && sid != C.AUDIO_SESSION_ID_UNSET) {
                enh = runCatching {
                    LoudnessEnhancer(sid).apply { enabled = true }
                }.getOrNull()
                enhancer = enh
            }
        }
        if (enh != null && total > 1.0f) {
            val mb = (20f * log10(total) * 100).toInt().coerceIn(0, 4000)
            runCatching { enh.setTargetGain(mb) }
        } else if (enh != null) {
            runCatching { enh.setTargetGain(0) }
        }
    }

    fun refreshGains() {
        normGains = db.allGains()
        if (::player.isInitialized) applyGainFor(player.currentMediaItem)
    }

    /**
     * The equalizer needs a live audio session, which only exists after the
     * player has prepared something. The session callback does not always
     * refire, so attach on demand when the mixer opens: works paused too,
     * only a never-played session truly has nothing to attach to.
     */
    fun ensureToneAttached(): Boolean {
        if (tone.bands().isNotEmpty()) return true
        val id = runCatching { if (::player.isInitialized) player.audioSessionId else 0 }.getOrDefault(0)
        if (id != 0 && id != C.AUDIO_SESSION_ID_UNSET) tone.attach(id)
        return tone.bands().isNotEmpty()
    }

    /** Simple local binder so MainActivity can call play/queue helpers directly. */
    inner class LocalBinder : android.os.Binder() {
        fun service(): PlayerService = this@PlayerService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == "com.blissplayer.music.PLAYER_UI") binder else super.onBind(intent)
    }
}
