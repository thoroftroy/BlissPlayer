package com.blissplayer.desktop

import com.blissplayer.core.AppPaths
import com.blissplayer.core.SettingsStore
import java.io.File
import java.util.Properties

/** ~/.config/blissplayer prefs + XDG-ish paths. No Android, no registry. */
class DesktopSettings : SettingsStore {
    private val file: File = File(System.getProperty("user.home"), ".config/blissplayer/bliss.properties")
    private val props = Properties()

    init {
        runCatching {
            if (file.exists()) file.inputStream().use { props.load(it) }
        }
    }

    private fun save() {
        runCatching {
            file.parentFile.mkdirs()
            file.outputStream().use { props.store(it, "BlissPlayer desktop") }
        }
    }

    override fun getString(key: String, default: String): String = props.getProperty(key, default)
    override fun putString(key: String, value: String) { props.setProperty(key, value); save() }
    override fun getBoolean(key: String, default: Boolean): Boolean =
        props.getProperty(key)?.toBooleanStrictOrNull() ?: default
    override fun putBoolean(key: String, value: Boolean) { props.setProperty(key, value.toString()); save() }
    override fun getFloat(key: String, default: Float): Float =
        props.getProperty(key)?.toFloatOrNull() ?: default
    override fun putFloat(key: String, value: Float) { props.setProperty(key, value.toString()); save() }
}

class DesktopPaths(musicRoot: File?) : AppPaths {
    private val home = File(System.getProperty("user.home"))
    private val base = File(home, ".config/blissplayer")
    val music: File = musicRoot ?: File(home, "Music")

    override val databaseFile: String get() = File(base, "bliss.db").absolutePath
    override val playlistDir: String get() = File(base, "playlists").absolutePath
    override val cacheDir: String get() = File(base, "cache").absolutePath
    override val exportDir: String get() = File(home, "Downloads").absolutePath
}
