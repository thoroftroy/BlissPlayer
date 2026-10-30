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
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.blissplayer.music.MainActivity
import com.blissplayer.music.R
import com.blissplayer.music.data.BlissDb
import kotlin.math.log10

/**
 * Background player. Owns ExoPlayer, loudness/boost gain math, and the
 * persistent playback notification (song, progress bar, skip buttons)
 * that also drives the lockscreen, headset buttons, and car Bluetooth.
 *
 * finalGain(song) = normalizeGain(song) x userBoost, split across two stages:
 *  - ExoPlayer volume handles cuts (gain up to 100%)
 *  - framework LoudnessEnhancer on the player's audio session handles boost (above 100%)
 * Both are public APIs, so this works on any device without custom DSP code.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerService : MediaSessionService() {

    companion object {
        const val ACTION_TOGGLE = "bliss.toggle"
        const val ACTION_NEXT = "bliss.next"
        const val ACTION_PREV = "bliss.prev"
        private const val CHANNEL = "bliss_playback"
        private const val NOTIF_ID = 1001
    }

    lateinit var player: ExoPlayer
        private set
    private var session: MediaSession? = null
    private var enhancer: LoudnessEnhancer? = null

    /** mediaId -> baked normalize multiplier from the Equalize scan. */
    @Volatile var normGains: Map<Long, Double> = emptyMap()

    /** Master amp set from the UI (1.0 = 100%). Persisted per Bluetooth device. */
    @Volatile var boost: Float = 1.0f

    private lateinit var db: BlissDb
    private val notifManager by lazy { getSystemService(NotificationManager::class.java) }
    private val notifTickHandler = Handler(Looper.getMainLooper())
    private val notifTick = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying) {
                postNotif()
                notifTickHandler.postDelayed(this, 1000)
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            applyGainFor(item)
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            runCatching { enhancer?.release() }
            enhancer = null
            if (audioSessionId != C.AUDIO_SESSION_ID_UNSET && audioSessionId != 0) {
                enhancer = runCatching {
                    LoudnessEnhancer(audioSessionId).apply { enabled = true }
                }.getOrNull()
            }
            applyGainFor(player.currentMediaItem)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            postNotif()
            notifTickHandler.removeCallbacks(notifTick)
            if (isPlaying) notifTickHandler.post(notifTick)
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = BlissDb(this)
        normGains = db.allGains()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true) // pause on headphone unplug
            .build()
        player.addListener(listener)

        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(pi)
            .build()
        setMediaNotificationProvider(notifProvider)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::player.isInitialized) {
            when (intent?.action) {
                ACTION_TOGGLE -> if (player.isPlaying) player.pause() else player.play()
                ACTION_NEXT -> player.seekToNextMediaItem()
                ACTION_PREV -> player.seekToPreviousMediaItem()
            }
        }
        return super.onStartCommand(intent, flags, startId)
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
        val md = player.currentMediaItem?.mediaMetadata
        val playing = player.isPlaying
        val dur = player.duration.coerceAtLeast(0)
        val pos = player.currentPosition.coerceIn(0, dur)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(md?.title?.toString() ?: "BlissPlayer")
            .setContentText(
                listOfNotNull(
                    md?.artist?.toString(),
                    md?.albumTitle?.toString()
                ).joinToString(" | ").ifBlank { "Ready" }
            )
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setDeleteIntent(actionIntent(ACTION_TOGGLE, 4))
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setShowWhen(false)
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
        if (dur > 0) b.setProgress(dur.toInt().coerceAtLeast(1), pos.toInt(), false)
        return b.build()
    }

    private fun postNotif() {
        if (!::player.isInitialized) return
        runCatching { notifManager.notify(NOTIF_ID, buildNotification()) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        notifTickHandler.removeCallbacksAndMessages(null)
        session?.release()
        player.removeListener(listener)
        player.release()
        runCatching { enhancer?.release() }
        super.onDestroy()
    }

    /** Recompute output gain for the now-playing item. Clamped to 1%..500%. */
    fun applyGainFor(item: MediaItem?) {
        val id = item?.mediaId?.toLongOrNull()
        val norm = if (id != null) (normGains[id] ?: 1.0).toFloat() else 1.0f
        val total = (norm * boost).coerceIn(0.01f, 5.0f)
        // Stage 1: ExoPlayer volume does everything up to unity.
        player.volume = total.coerceIn(0.0f, 1.0f)
        // Stage 2: enhancer adds the rest as dB above unity (e.g. 2.0x = +6 dB).
        val enh = enhancer
        if (enh != null && total > 1.0f) {
            val mb = (20f * log10(total) * 100).toInt().coerceIn(0, 1400)
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
