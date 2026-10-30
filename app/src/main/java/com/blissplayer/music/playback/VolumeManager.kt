package com.blissplayer.music.playback

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.blissplayer.music.data.BlissDb

/**
 * Master amp ("Boost") with per-device memory.
 * Your car needs ~200% but headphones don't, BlissPlayer remembers each
 * Bluetooth device's boost and restores it automatically on connect.
 * "Speaker" (phone speaker / wired) is stored under its own key.
 */
class VolumeManager(
    private val context: Context,
    private val db: BlissDb,
    private val prefs: SharedPreferences,
    private val onBoostChanged: (Float) -> Unit
) {
    companion object {
        const val KEY_MANUAL = "boost_manual"
        const val DEVICE_SPEAKER = "Phone speaker / wired"
        const val MIN = 0.25f
        const val MAX = 4.0f // 400% hard cap; UI warns past 150%
    }

    var currentDevice: String = DEVICE_SPEAKER
        private set
    var boost: Float = 1.0f
        private set

    private val btReceiver = object : BroadcastReceiver() {
        @android.annotation.SuppressLint("MissingPermission")
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                val name = runCatching {
                    val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    dev?.name?.takeIf { !it.isNullOrBlank() } ?: dev?.address
                }.getOrNull()
                if (name == null) return
                setDevice("BT: $name", saveManual = true)
            } else if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                setDevice(DEVICE_SPEAKER, saveManual = true)
            }
        }
    }

    fun start() {
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        ContextCompat.registerReceiver(context, btReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        // Restore last device's boost.
        currentDevice = prefs.getString("last_device", DEVICE_SPEAKER) ?: DEVICE_SPEAKER
        boost = db.getBoost(currentDevice, prefs.getFloat(KEY_MANUAL, 1.0f).toDouble()).toFloat()
        onBoostChanged(boost)
    }

    fun stop() = runCatching { context.unregisterReceiver(btReceiver) }

    fun setBoost(mult: Float) {
        boost = mult.coerceIn(MIN, MAX)
        db.putBoost(currentDevice, boost.toDouble())
        prefs.edit().putFloat(KEY_MANUAL, boost).apply()
        onBoostChanged(boost)
    }

    fun setDevice(device: String, saveManual: Boolean) {
        if (saveManual) {
            db.putBoost(currentDevice, boost.toDouble())
            prefs.edit().putFloat(KEY_MANUAL, boost).apply()
        }
        currentDevice = device
        prefs.edit().putString("last_device", device).apply()
        boost = db.getBoost(device, prefs.getFloat(KEY_MANUAL, 1.0f).toDouble()).toFloat()
        onBoostChanged(boost)
    }
}
