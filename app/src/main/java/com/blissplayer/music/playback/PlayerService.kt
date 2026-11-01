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
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.blissplayer.music.MainActivity
import com.blissplayer.music.R
import com.blissplayer.music.data.BlissDb
import com.blissplayer.music.model.Seg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerService : MediaSessionService() {

    companion object {
        const val ACTION_TOGGLE = "bliss.toggle"
        const val ACTION_NEXT = "bliss.next"
        const val ACTION_PREV = "bliss.prev"
        const val ACTION_STOP = "bliss.stop"
        private const val CHANNEL = "bliss_playback"
        private const val NOTIF_ID = 1001
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
    private val notifManager by lazy { getSystemService(NotificationManager::class.java) }
    private val notifTickHandler = Handler(Looper.getMainLooper())
    private val notifTick = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying) {
                postNotif()
                // Also detects a selected speaker route while Bluetooth stays connected.
                volumeManager.refreshRoute()
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastResumeSave > 15000) {
                    savePlaybackState()
                    lastResumeSave = now
                }
                notifTickHandler.postDelayed(this, 1000)
            }
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
            notifTickHandler.removeCallbacks(notifTick)
            segTickHandler.removeCallbacks(segTick)
            if (isPlaying) {
                notifTickHandler.post(notifTick)
                segTickHandler.post(segTick)
            }
        }
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
                true // standard transient pause/duck/resume, independent of the Activity
            )
            .setWakeMode(C.WAKE_MODE_LOCAL) // CPU stays up for gapless local playback
            .setHandleAudioBecomingNoisy(true) // pause on headphone unplug
            .build()
        player.addListener(listener)

        configureAudioFocus()
        volumeManager = (application as com.blissplayer.music.BlissApplication).volumeManager
        volumeManager.addListener(volumeListener)
        volumeManager.onDeviceConnected = { device ->
            if (volumeManager.isAutoplay(device)) {
                if (player.mediaItemCount > 0) startPlayback() else restoreSavedSession(play = true)
            }
        }
        volumeManager.start()
        volumeListener()

        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(pi)
            .build()
        setMediaNotificationProvider(notifProvider)
    }

    fun configureAudioFocus() {
        player.setAudioAttributes(player.audioAttributes,
            getSharedPreferences("bliss", MODE_PRIVATE).getString("focus_mode", "CallsOnly") != "Off")
    }

    /** Android 15+ requires foreground status before a background focus request. */
    private fun startPlayback() {
        if (player.mediaItemCount == 0) return
        runCatching { startForeground(NOTIF_ID, buildNotification()) }.onFailure {
            lastPlaybackError = "Android blocked background playback: ${it.message}"
            getSharedPreferences("bliss", MODE_PRIVATE).edit()
                .putString("last_playback_error", lastPlaybackError).apply()
        }
        player.play()
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
                        .setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album).build()).build() }
                player.setMediaItems(items, index, position)
                player.repeatMode = prefs.getInt("resume_repeat", Player.REPEAT_MODE_OFF).coerceIn(0, 2)
                player.prepare()
                if (play) startPlayback() else player.pause()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastPlaybackError = "Could not restore playback: ${e.message}"
            }
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
        // App swiped away or closed: keep playing. Only stop when idle.
        // Do not call super when playing, or some OEMs kill the service.
        if (::player.isInitialized && player.playWhenReady && player.mediaItemCount > 0 &&
            player.playbackState != Player.STATE_ENDED) return
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && getSharedPreferences("bliss", MODE_PRIVATE).getBoolean("resume_playing", false)) {
            restoreSavedSession(play = true)
        }
        if (::player.isInitialized) {
            when (intent?.action) {
                ACTION_TOGGLE -> if (player.playWhenReady) player.pause() else startPlayback()
                ACTION_NEXT -> player.seekToNextMediaItem()
                ACTION_PREV -> player.seekToPreviousMediaItem()
                // Swiping the notification away means done with music: stop it all.
                ACTION_STOP -> {
                    player.pause()
                    player.clearMediaItems()
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
            .setOngoing(playing)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", actionIntent(ACTION_PREV, 1))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                actionIntent(ACTION_TOGGLE, 2)
            )
            .addAction(android.R.drawable.ic_media_next, "Next", actionIntent(ACTION_NEXT, 3))
            .setStyle(
                MediaStyle()
                    .setMediaSession(session?.sessionCompatToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setColor(0xFF0058E6.toInt())
        if (!simple) {
            b.setLargeIcon(notifArt())
            b.setSubText("BlissPlayer")
            val small = RemoteViews(packageName, R.layout.xp_notif_small).apply {
                setTextViewText(R.id.n_title, title)
                setTextViewText(R.id.n_artist, artist)
                setImageViewResource(R.id.n_play, playIcon)
                setImageViewResource(R.id.n_next, R.drawable.ic_next_w)
                setOnClickPendingIntent(R.id.n_play, actionIntent(ACTION_TOGGLE, 2))
                setOnClickPendingIntent(R.id.n_next, actionIntent(ACTION_NEXT, 3))
            }
            val big = RemoteViews(packageName, R.layout.xp_notif_big).apply {
                setTextViewText(R.id.n_title, title)
                setTextViewText(R.id.n_artist, artist)
                setImageViewResource(R.id.n_play, playIcon)
                setImageViewResource(R.id.n_next, R.drawable.ic_next_w)
                setImageViewResource(R.id.n_prev, R.drawable.ic_prev_w)
                setOnClickPendingIntent(R.id.n_play, actionIntent(ACTION_TOGGLE, 2))
                setOnClickPendingIntent(R.id.n_next, actionIntent(ACTION_NEXT, 3))
                setOnClickPendingIntent(R.id.n_prev, actionIntent(ACTION_PREV, 1))
                setTextViewText(R.id.n_time, "${fmt(pos)} / ${fmt(dur)}")
                if (dur > 0) setProgressBar(R.id.n_progress, 1000, ((pos * 1000) / dur).toInt().coerceIn(0, 1000), false)
            }
            b.setCustomContentView(small)
            b.setCustomBigContentView(big)
        }
        if (dur > 0) b.setProgress(dur.toInt().coerceAtLeast(1), pos.toInt(), false)
        return b.build()
    }

    /** XP artwork for the notification: blue gradient, app icon, name strip. */
    private fun notifArt(): android.graphics.Bitmap = cachedNotifArt

    private val cachedNotifArt: android.graphics.Bitmap by lazy {
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
        bmp
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun postNotif() {
        if (!::player.isInitialized) return
        runCatching { notifManager.notify(NOTIF_ID, buildNotification()) }
    }

    fun repostNotif() {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        postNotif()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        savePlaybackState()
        scope.cancel()
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
        val total = (norm * boost * tone.gain()).coerceIn(0.0001f, 80.0f)
        // Stage 1: ExoPlayer volume does everything up to unity.
        player.volume = total.coerceIn(0.0f, 1.0f)
        // Stage 2: enhancer adds the rest as dB above unity. The phone clamps to its own max.
        val enh = enhancer
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

    /** Simple local binder so MainActivity can call play/queue helpers directly. */
    inner class LocalBinder : android.os.Binder() {
        fun service(): PlayerService = this@PlayerService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == "com.blissplayer.music.PLAYER_UI") binder else super.onBind(intent)
    }
}
