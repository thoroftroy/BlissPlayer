package com.blissplayer.desktop

import com.blissplayer.core.TransportCommand
import com.blissplayer.core.TrayMenu
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusBoundProperty
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/**
 * Native KDE StatusNotifierItem tray (no XEmbed bridge): the icon registers
 * itself on D-Bus and serves a real `com.canonical.dbusmenu` menu, so the
 * Plasma panel shows Show / Play-Pause / Next / Previous / Quit and every
 * entry dispatches through the shared [TrayMenu] model.
 */
class DesktopSniTray(
    private val show: () -> Unit = {},
    private val onCommand: (TransportCommand) -> Unit = {},
    private val quit: () -> Unit = {},
    private val tooltip: () -> String = { "BlissPlayer" }
) {
    private var connection: DBusConnection? = null
    private var serviceName: String? = null
    @Volatile var active = false
        private set

    fun start(): Boolean {
        if (active) return true
        return try {
            val conn = DBusConnectionBuilder.forSessionBus().build()
            conn.exportObject(SniItem())
            conn.exportObject(SniMenu())
            val name = "org.kde.StatusNotifierItem-blissplayer-${ProcessHandle.current().pid()}"
            conn.requestBusName(name)
            conn.getRemoteObject(
                "org.kde.StatusNotifierWatcher", "/StatusNotifierWatcher", SniWatcher::class.java
            ).RegisterStatusNotifierItem(name)
            connection = conn
            serviceName = name
            active = true
            true
        } catch (e: Exception) {
            System.err.println("BlissPlayer: native tray unavailable (${e.message}), using fallback.")
            stop()
            false
        }
    }

    fun stop() {
        runCatching { connection?.unExportObject("/StatusNotifierItem") }
        runCatching { connection?.unExportObject("/Menu") }
        runCatching { serviceName?.let { connection?.releaseBusName(it) } }
        runCatching { connection?.disconnect() }
        connection = null
        serviceName = null
        active = false
    }

    private var menuRevision = 1

    // ---- D-Bus interfaces ----

    @DBusInterfaceName("org.kde.StatusNotifierWatcher")
    interface SniWatcher : DBusInterface {
        fun RegisterStatusNotifierItem(service: String)
        override fun getObjectPath(): String = "/StatusNotifierWatcher"
    }

    class SniIcon(
        @Position(0) var width: Int = 0,
        @Position(1) var height: Int = 0,
        @Position(2) var bytes: ByteArray = ByteArray(0)
    ) : Struct()

    class SniToolTip(
        @Position(0) var name: String = "",
        @Position(1) var pixmaps: List<SniIcon> = emptyList(),
        @Position(2) var title: String = "",
        @Position(3) var text: String = ""
    ) : Struct()

    @DBusInterfaceName("org.kde.StatusNotifierItem")
    inner class SniItem : DBusInterface {
        override fun getObjectPath(): String = "/StatusNotifierItem"
        private val pixmap by lazy { trayPixmap() }
        fun ContextMenu(x: Int, y: Int) {}
        fun Activate(x: Int, y: Int) { SwingUtilities.invokeLater { show() } }
        fun SecondaryActivate(x: Int, y: Int) {}
        fun Scroll(delta: Int, orientation: String) {}

        @DBusBoundProperty fun getCategory(): String = "ApplicationStatus"
        @DBusBoundProperty fun getId(): String = "blissplayer"
        @DBusBoundProperty fun getTitle(): String = "BlissPlayer"
        @DBusBoundProperty fun getStatus(): String = "Active"
        @DBusBoundProperty fun getWindowId(): Int = 0
        @DBusBoundProperty fun getIconName(): String = ""
        @DBusBoundProperty fun getIconPixmap(): List<SniIcon> = listOf(pixmap)
        @DBusBoundProperty fun getOverlayIconName(): String = ""
        @DBusBoundProperty fun getOverlayIconPixmap(): List<SniIcon> = emptyList()
        @DBusBoundProperty fun getAttentionIconName(): String = ""
        @DBusBoundProperty fun getAttentionIconPixmap(): List<SniIcon> = emptyList()
        @DBusBoundProperty fun getAttentionMovieName(): String = ""
        @DBusBoundProperty fun getToolTip(): SniToolTip =
            SniToolTip("BlissPlayer", emptyList(), "BlissPlayer", tooltip())
        @DBusBoundProperty fun getMenu(): DBusPath = DBusPath("/Menu")
        @DBusBoundProperty fun isItemIsMenu(): Boolean = false
    }

    class MenuNode(
        @Position(0) var id: Int = 0,
        @Position(1) var properties: Map<String, Variant<*>> = emptyMap(),
        @Position(2) var children: List<Variant<*>> = emptyList()
    ) : Struct()

    class MenuItemProps(
        @Position(0) var id: Int = 0,
        @Position(1) var properties: Map<String, Variant<*>> = emptyMap()
    ) : Struct()

    class MenuLayout(
        @Position(0) var revision: UInt32 = UInt32(0),
        @Position(1) var layout: MenuNode = MenuNode()
    ) : Struct()

    @DBusInterfaceName("com.canonical.dbusmenu")
    interface SniMenuRemote : DBusInterface {
        fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): MenuLayout
        fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProps>
        fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)
        fun AboutToShow(id: Int): Boolean
    }

    @DBusInterfaceName("com.canonical.dbusmenu")
    inner class SniMenu : SniMenuRemote, DBusInterface {
        override fun getObjectPath(): String = "/Menu"

        private fun propsFor(itemId: Int): Map<String, Variant<*>> {
            val item = TrayMenu.byId(itemId) ?: return emptyMap()
            return mapOf(
                "label" to Variant(item.label),
                "enabled" to Variant(true),
                "visible" to Variant(true),
                "type" to Variant("standard")
            )
        }

        override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): MenuLayout {
            val children = TrayMenu.ITEMS.map { item ->
                Variant(MenuNode(item.id, propsFor(item.id), emptyList()))
            }
            val root = MenuNode(0, mapOf("children-display" to Variant("submenu")), children)
            return MenuLayout(UInt32(menuRevision.toLong()), root)
        }

        override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProps> =
            ids.mapNotNull { id -> TrayMenu.byId(id)?.let { MenuItemProps(id, propsFor(id)) } }

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId != "clicked") return
            val handled = TrayMenu.dispatch(id, show, onCommand, quit)
            if (!handled) System.err.println("BlissPlayer: unknown tray menu id $id")
        }

        override fun AboutToShow(id: Int): Boolean = false

        @DBusBoundProperty fun getVersion(): UInt32 = UInt32(3)
        @DBusBoundProperty fun getStatus(): String = "normal"
        @DBusBoundProperty fun getIconThemePath(): List<String> = emptyList()
        @DBusBoundProperty fun getTextDirection(): String = "ltr"
    }

    companion object {
        /** Same note emblem the AWT fallback draws, encoded as ARGB for the panel. */
        fun trayPixmap(size: Int = 64): SniIcon {
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.color = java.awt.Color(0xDF, 0xDF, 0xDF)
            g.fillRoundRect(2, 2, size - 4, size - 4, 16, 16)
            g.color = java.awt.Color(0x11, 0x11, 0x11)
            g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, (size * 0.53).toInt())
            g.drawString("\u266A", (size * 0.28).toInt(), (size * 0.72).toInt())
            g.dispose()
            val bytes = ByteArray(size * size * 4)
            var p = 0
            for (y in 0 until size) for (x in 0 until size) {
                val argb = img.getRGB(x, y)
                bytes[p++] = (argb ushr 24).toByte()
                bytes[p++] = (argb shr 16).toByte()
                bytes[p++] = (argb shr 8).toByte()
                bytes[p++] = argb.toByte()
            }
            return SniIcon(size, size, bytes)
        }
    }
}
