package com.blissplayer.music.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the playback card's swipe-away. A broadcast is always delivered, unlike a service
 * start from the background, so the dismissal is never lost and the card never comes back on
 * its own. If the service is gone the stop is still recorded for the next start.
 */
class NotifDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val service = PlayerService.instance?.get()
        if (service != null) service.stopByUser()
        else context.getSharedPreferences("bliss", Context.MODE_PRIVATE).edit()
            .putBoolean("stopped_by_user", true).putBoolean("resume_playing", false).apply()
    }

    companion object { const val ACTION = "com.blissplayer.music.NOTIFICATION_DISMISSED" }
}
