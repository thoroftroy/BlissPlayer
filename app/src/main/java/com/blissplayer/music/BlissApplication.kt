package com.blissplayer.music

import android.app.Application

class BlissApplication : Application() {
    val volumeManager by lazy {
        com.blissplayer.music.playback.VolumeManager(this,
            com.blissplayer.music.data.BlissDb(this), getSharedPreferences("bliss", MODE_PRIVATE))
    }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
