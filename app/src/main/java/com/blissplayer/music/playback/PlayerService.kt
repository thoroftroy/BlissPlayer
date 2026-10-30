@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.blissplayer.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.os.IBinder
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.blissplayer.music.MainActivity
import com.blissplayer.music.data.BlissDb
import kotlin.math.log10

/**
 * Background player. Owns ExoPlayer + loudness/boost gain math.
 *
 * finalGain(song) = normalizeGain(song) × userBoost, split across two stages:
 *  - ExoPlayer volume handles cuts (gain ≤ 100%)
 *  - framework LoudnessEnhancer on the player's audio session handles boost (> 100%)
 * Both are public APIs, so this works on any device without custom DSP code.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerService : MediaSessionService() {

    lateinit var player: ExoPlayer
        private set
    private var session: MediaSession? = null
    private var enhancer: LoudnessEnhancer? = null

    /** mediaId -> baked normalize multiplier from the Equalize-All scan. */
    @Volatile var normGains: Map<Long, Double> = emptyMap()

    /** Master amp set from the UI (1.0 = 100%). Persisted per Bluetooth device. */
    @Volatile var boost: Float = 1.0f

    private lateinit var db: BlissDb
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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        player.removeListener(listener)
        player.release()
        runCatching { enhancer?.release() }
        super.onDestroy()
    }

    /** Recompute output gain for the now-playing item. Clamped to 5%..400%. */
    fun applyGainFor(item: MediaItem?) {
        val id = item?.mediaId?.toLongOrNull()
        val norm = if (id != null) (normGains[id] ?: 1.0).toFloat() else 1.0f
        val total = (norm * boost).coerceIn(0.05f, 4.0f)
        // Stage 1: ExoPlayer volume does everything up to unity.
        player.volume = total.coerceIn(0.0f, 1.0f)
        // Stage 2: enhancer adds the rest as dB above unity (e.g. 2.0x = +6 dB).
        val enh = enhancer
        if (enh != null && total > 1.0f) {
            val mb = (20f * log10(total) * 100).toInt().coerceIn(0, 1500)
            runCatching { enh.setTargetGain(mb) }
        } else if (enh != null) {
            runCatching { enh.setTargetGain(0) }
        }
    }

    fun refreshGains() {
        normGains = db.allGains()
        applyGainFor(player.currentMediaItem)
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
