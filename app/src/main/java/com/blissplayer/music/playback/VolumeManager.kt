package com.blissplayer.music.playback

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.blissplayer.music.data.BlissDb

/** Owned by PlayerService, not the Activity: presets still switch with the UI closed. */
class VolumeManager(
    private val context: Context,
    private val db: BlissDb,
    private val prefs: SharedPreferences
) {
    companion object {
        const val KEY_MANUAL = "boost_manual"
        const val KEY_AUTO_DEVICE = "auto_device"
        const val DEVICE_SPEAKER = OutputRouting.SPEAKER
        const val MIN = 0.01f
        const val MAX = 20.0f
        fun autoplayKey(device: String) = "autoplay_dev_" + device.hashCode().toString()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val memory = OutputPresetMemory(
        { name -> db.getBoost(name, Double.NaN).takeIf { it.isFinite() }?.toFloat() },
        { name, value -> db.putBoost(name, value.toDouble()) },
        db.getBoost(DEVICE_SPEAKER, 1.0).toFloat()
    )
    val currentDevice: String get() = memory.device
    val boost: Float get() = memory.boost
    private var started = false
    private val listeners = mutableSetOf<() -> Unit>()
    var onDeviceConnected: ((String) -> Unit)? = null

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    private fun notifyChanged() = listeners.toList().forEach { it() }

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshRoute()
    }
    private val refreshLater = Runnable { refreshRoute() }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            refreshRoute()
            // Audio policy can finish rerouting after the Bluetooth broadcast.
            handler.removeCallbacks(refreshLater)
            handler.postDelayed(refreshLater, 500)
        }
    }

    fun start() {
        if (started) return
        started = true
        audio.registerAudioDeviceCallback(devices, handler)
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }, ContextCompat.RECEIVER_EXPORTED) // Bluetooth broadcasts come from a privileged system app
        refreshRoute() // query reality; never restore a stale last_device string
        notifyChanged()
    }

    fun stop() {
        if (!started) return
        started = false
        audio.unregisterAudioDeviceCallback(devices)
        runCatching { context.unregisterReceiver(receiver) }
        handler.removeCallbacksAndMessages(null)
    }

    fun refreshRoute() {
        val available = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull(::output)
        val routed = if (Build.VERSION.SDK_INT >= 33) runCatching {
            audio.getAudioDevicesForAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .mapNotNull(::output)
        }.getOrNull() else null
        reconcileOutputs(available, routed)
    }

    /** The same entry point accepts deterministic route snapshots in regression tests. */
    fun reconcileOutputs(available: List<AudioOutput>, routed: List<AudioOutput>?) {
        val selected = OutputRouting.select(available, routed)
        val changed = memory.select(selected.name, prefs.getBoolean(KEY_AUTO_DEVICE, true))
        if (changed) {
            prefs.edit().putString("last_device", currentDevice).putFloat(KEY_MANUAL, boost).apply()
            notifyChanged()
            if (selected.wireless) onDeviceConnected?.invoke(currentDevice)
        }
    }

    private fun output(info: AudioDeviceInfo): AudioOutput? {
        val name = info.productName?.toString()?.trim().orEmpty()
        val wireless = info.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            info.type == AudioDeviceInfo.TYPE_HEARING_AID || (Build.VERSION.SDK_INT >= 31 &&
            info.type in setOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER))
        if (info.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return OutputRouting.speaker
        if (!wireless && info.type !in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_HDMI)) return null
        val label = if (wireless) "BT: ${name.ifBlank { "Bluetooth audio" }}"
            else "Wired: ${name.ifBlank { "Headphones / USB" }}"
        return AudioOutput(info.id.toString(), label, wireless)
    }

    fun setBoost(mult: Float) {
        memory.setBoost(mult)
        prefs.edit().putFloat(KEY_MANUAL, boost).apply()
        notifyChanged()
    }

    /** Applying a preset does not pretend to route audio to an unconnected device. */
    fun useDevicePreset(device: String) = setBoost(db.getBoost(device, 1.0).toFloat())

    fun allDevices(): List<Pair<String, Double>> {
        val out = mutableListOf<Pair<String, Double>>()
        db.readableDatabase.rawQuery("SELECT device_name, gain_mult FROM boost", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getDouble(1))
        }
        return out.sortedWith(compareBy({ it.first != DEVICE_SPEAKER }, { it.first.lowercase() }))
    }

    fun forgetDevice(device: String) {
        if (device == DEVICE_SPEAKER || device == currentDevice) return
        db.writableDatabase.delete("boost", "device_name=?", arrayOf(device))
        prefs.edit().remove(autoplayKey(device)).apply()
        notifyChanged()
    }

    fun isAutoplay(device: String): Boolean = prefs.getBoolean(autoplayKey(device), false)
    fun setAutoplay(device: String, on: Boolean) { prefs.edit().putBoolean(autoplayKey(device), on).apply() }
}
