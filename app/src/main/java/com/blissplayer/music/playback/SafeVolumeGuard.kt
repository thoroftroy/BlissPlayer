package com.blissplayer.music.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Best effort answer to the phone lowering the music volume on its own.
 *
 * Android enforces its loud sound protection at OS level, so no normal app
 * can switch it off. The real off switch is in the phone settings
 * (Sound settings, usually called Media volume limit or Hearing safety).
 *
 * What this guard can do: while BlissPlayer music is playing, if the system
 * volume drops, put it straight back. If the phone keeps fighting (three
 * restores in a minute), back off for five minutes and say so, instead of
 * looping forever and burning battery.
 */
class SafeVolumeGuard(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val isPlaying: () -> Boolean
) {
    companion object {
        const val KEY_HOLD = "hold_volume"
        private const val ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_NEW = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
        private const val MAX_RESTORES = 3
        private const val WINDOW_MS = 60_000L
        private const val COOLDOWN_MS = 5 * 60_000L
    }

    var onFightingSystem: (() -> Unit)? = null

    private val audio = context.getSystemService(AudioManager::class.java)
    private val restores = ArrayDeque<Long>()
    private var cooldownUntil = 0L
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != ACTION) return
            if (!prefs.getBoolean(KEY_HOLD, false)) return
            if (!isPlaying()) return
            val type = intent.getIntExtra(EXTRA_TYPE, -1)
            if (type != AudioManager.STREAM_MUSIC) return
            val newVal = intent.getIntExtra(EXTRA_NEW, -1)
            val prevVal = intent.getIntExtra(EXTRA_PREV, -1)
            if (newVal < 0 || prevVal < 0 || newVal >= prevVal) return
            val now = SystemClock.elapsedRealtime()
            if (now < cooldownUntil) return
            restores.addLast(now)
            while (restores.isNotEmpty() && now - restores.first() > WINDOW_MS) restores.removeFirst()
            if (restores.size > MAX_RESTORES) {
                cooldownUntil = now + COOLDOWN_MS
                restores.clear()
                onFightingSystem?.invoke()
                return
            }
            runCatching { audio?.setStreamVolume(AudioManager.STREAM_MUSIC, prevVal, 0) }
        }
    }

    fun start() {
        if (registered) return
        registered = true
        val f = IntentFilter(ACTION)
        ContextCompat.registerReceiver(context, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
    }
}
