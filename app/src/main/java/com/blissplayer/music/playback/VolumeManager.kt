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
        const val KEY_AUTO_DEVICE = "auto_device"
        const val KEY_AUTOPLAY = "autoplay_on_connect"
        const val DEVICE_SPEAKER = "Phone speaker / wired"
        const val MIN = 0.01f
        const val MAX = 20.0f // 2000 percent. Not capped for quality, the warning text covers that.
    }

    var currentDevice: String = DEVICE_SPEAKER
        private set
    var boost: Float = 1.0f
        private set

    /** Fired after an automatic device switch so the UI can autoplay if enabled. */
    var onDeviceConnected: ((device: String) -> Unit)? = null

    private fun deviceNameFrom(intent: Intent): String? {
        return runCatching {
            val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val raw = dev?.name?.takeIf { !it.isNullOrBlank() } ?: dev?.address ?: return null
            if (raw.startsWith("BT: ")) raw else "BT: $raw"
        }.getOrNull()
    }

    private val btReceiver = object : BroadcastReceiver() {
        @android.annotation.SuppressLint("MissingPermission")
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (!prefs.getBoolean(KEY_AUTO_DEVICE, true)) return
                    val name = deviceNameFrom(intent) ?: return
                    // Save outgoing device first, then load the incoming one.
                    setDevice(name, saveManual = true)
                    onDeviceConnected?.invoke(name)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    if (!prefs.getBoolean(KEY_AUTO_DEVICE, true)) return
                    // Leaving Bluetooth: fall back to speaker/wired memory.
                    if (currentDevice.startsWith("BT: ")) {
                        setDevice(DEVICE_SPEAKER, saveManual = true)
                    }
                }
            }
        }
    }

    fun start() {
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
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
        val clean = device.trim().ifBlank { DEVICE_SPEAKER }
        if (clean == currentDevice) {
            // Still reload so manual edits apply immediately.
            boost = db.getBoost(clean, prefs.getFloat(KEY_MANUAL, 1.0f).toDouble()).toFloat()
            onBoostChanged(boost)
            return
        }
        if (saveManual) {
            db.putBoost(currentDevice, boost.toDouble())
            prefs.edit().putFloat(KEY_MANUAL, boost).apply()
        }
        currentDevice = clean
        prefs.edit().putString("last_device", clean).apply()
        boost = db.getBoost(clean, prefs.getFloat(KEY_MANUAL, 1.0f).toDouble()).toFloat()
        onBoostChanged(boost)
    }

    /** Every remembered output, speaker first, then Bluetooth, alphabetized. */
    fun allDevices(): List<Pair<String, Double>> {
        val out = mutableListOf<Pair<String, Double>>()
        runCatching {
            db.readableDatabase.rawQuery("SELECT device_name, gain_mult FROM boost", null)?.use { c ->
                while (c.moveToNext()) out.add(c.getString(0) to c.getDouble(1))
            }
        }
        if (out.none { it.first == DEVICE_SPEAKER }) {
            out.add(0, DEVICE_SPEAKER to prefs.getFloat(KEY_MANUAL, 1.0f).toDouble())
        }
        if (out.none { it.first == currentDevice }) {
            out.add(currentDevice to boost.toDouble())
        }
        return out.sortedWith(compareBy({ it.first != DEVICE_SPEAKER }, { it.first.lowercase() }))
    }

    fun forgetDevice(device: String) {
        if (device == DEVICE_SPEAKER) return
        runCatching { db.writableDatabase.delete("boost", "device_name=?", arrayOf(device)) }
        if (currentDevice == device) setDevice(DEVICE_SPEAKER, saveManual = false)
    }
}
