package com.blissplayer.desktop

import com.blissplayer.core.TransportCommand
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

/**
 * Talks to the SNI tray over the real session bus (two connections in one
 * JVM): verifies the exported menu layout and that clicking every entry
 * dispatches through the shared TrayMenu model. Skipped with no session bus.
 */
class DesktopSniTrayTest {
    private fun sessionBusPresent(): Boolean {
        if (System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) {
            val uid = System.getProperty("uid") ?: runCatching {
                ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim()
            }.getOrDefault("")
            val runtimeBus = File("/run/user/$uid/bus")
            if (uid.isBlank() || !runtimeBus.exists()) return false
        }
        return true
    }

    @Test fun menuLayoutAndClicksRoundTripOverDBus() {
        Assume.assumeTrue("no D-Bus session bus here", sessionBusPresent())
        val events = Collections.synchronizedList(mutableListOf<String>())
        val shown = CountDownLatch(1)
        val tray = DesktopSniTray(
            show = { events += "show"; shown.countDown() },
            onCommand = { events += it.name },
            quit = { events += "quit" }
        )
        val probe: DBusConnection = DBusConnectionBuilder.forSessionBus().build()
        try {
            Assume.assumeTrue("native tray could not start", tray.start())
            val menu = probe.getRemoteObject(
                trayService(tray), "/Menu", DesktopSniTray.SniMenuRemote::class.java
            )
            val layout = menu.GetLayout(0, -1, emptyList())
            // The probe side demarshals structs generically (Object[]); read either shape.
            fun nodeAt(v: Variant<*>): Pair<Int, Map<String, Variant<*>>> {
                val n = v.value
                if (n is DesktopSniTray.MenuNode) return n.id to n.properties
                @Suppress("UNCHECKED_CAST")
                (n as? Array<*>)?.let { return (it[0] as Number).toInt() to (it[1] as Map<String, Variant<*>>) }
                throw AssertionError("unexpected menu node wire type ${n?.javaClass}")
            }
            val nodes = layout.layout.children.map { nodeAt(it) }
            assertEquals(listOf(1, 2, 3, 4, 5), nodes.map { it.first })
            val labels = nodes.map { (it.second["label"]?.value as String) }
            assertEquals(listOf("Show", "Play/Pause", "Next", "Previous", "Quit"), labels)
            val props = menu.GetGroupProperties(listOf(2, 5), emptyList())
            assertEquals(listOf(2, 5), props.map { it.id })

            menu.Event(1, "clicked", Variant(""), UInt32(0))
            menu.Event(3, "clicked", Variant(""), UInt32(0))
            menu.Event(5, "clicked", Variant(""), UInt32(0))
            assertTrue("Show must run", shown.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("NEXT", "show", "quit").sorted(), events.sorted())
        } finally {
            tray.stop()
            runCatching { probe.disconnect() }
        }
    }

    private fun trayService(tray: DesktopSniTray): String {
        val field = DesktopSniTray::class.java.getDeclaredField("serviceName")
        field.isAccessible = true
        return field.get(tray) as String
    }
}
