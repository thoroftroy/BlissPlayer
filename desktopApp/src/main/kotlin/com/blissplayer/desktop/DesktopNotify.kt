package com.blissplayer.desktop

import com.blissplayer.core.NowPlaying
import com.blissplayer.core.NotificationGateway
import com.blissplayer.core.PlaybackSession
import com.blissplayer.core.TransportCommand
import com.blissplayer.core.TrayMenu
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import javax.swing.ImageIcon

/** Desktop notifications + tray; now-playing/session model shared with Android. */
class DesktopNotify : NotificationGateway {
    private var tray: TrayIcon? = null
    var onCommand: (TransportCommand) -> Unit = {}

    fun installTray(tint: java.awt.Color, show: () -> Unit) {
        if (SystemTray.isSupported()) {
            if (tray != null) return
            val image = trayImage(tint)
            val popup = PopupMenu()
            for (item in TrayMenu.ITEMS) {
                MenuItem(item.label).apply {
                    val id = item.id
                    addActionListener { TrayMenu.dispatch(id, show, onCommand) { quit?.invoke() } }
                    popup.add(this)
                }
            }
            val icon = TrayIcon(image, "BlissPlayer", popup)
        icon.isImageAutoSize = true
        icon.addActionListener { show() }
        runCatching { SystemTray.getSystemTray().add(icon) }
        tray = icon
        }
    }

    /** Generated note emblem in the theme accent (no art assets needed). */
    private fun trayImage(tint: java.awt.Color): java.awt.Image {
        val img = java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = tint
        g.fillRoundRect(2, 2, 60, 60, 16, 16)
        g.color = java.awt.Color(0x11, 0x11, 0x11)
        g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 34)
        g.drawString("\u266A", 18, 46)
        g.dispose()
        return img.getScaledInstance(24, 24, java.awt.Image.SCALE_SMOOTH)
    }

    var quit: (() -> Unit)? = null

    /** Extra tray backend to stop on quit (native SNI); AWT is in [removeTray]. */
    var trayBackend: (() -> Unit)? = null

    fun tooltipText(): String = tray?.toolTip ?: "BlissPlayer"

    fun removeTray() {
        tray?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        tray = null
    }

    override fun progress(title: String, done: Int, total: Int, stopAction: (() -> Unit)?) {
        // Progress lives in the app window (EDT-safe callers); tray tooltip mirrors it.
        tray?.toolTip = if (total > 0) "$title — $done/$total" else title
    }

    override fun done(title: String, message: String) {
        tray?.toolTip = "BlissPlayer"
        notify(title, message)
    }

    override fun nowPlaying(state: NowPlaying) {
        tray?.toolTip = state.item?.let { "${it.title} — ${it.artist}" } ?: "BlissPlayer"
    }

    fun notify(title: String, message: String) {
        val ok = runCatching {
            ProcessBuilder("notify-send", "BlissPlayer: $title", message.take(300)).start().waitFor() == 0
        }.getOrDefault(false)
        if (!ok) tray?.displayMessage("BlissPlayer: $title", message.take(300), TrayIcon.MessageType.INFO)
    }
}

class DesktopSession(
    private val notify: DesktopNotify,
    private val transport: (TransportCommand) -> Unit
) : PlaybackSession {
    init { notify.onCommand = transport }

    override fun publish(state: NowPlaying) = notify.nowPlaying(state)
    override fun clear() = notify.nowPlaying(NowPlaying(null, false, 0, 0))
    override fun onCommand(cmd: TransportCommand) = transport(cmd)
}
