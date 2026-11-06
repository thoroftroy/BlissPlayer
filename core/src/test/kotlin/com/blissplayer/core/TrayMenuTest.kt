package com.blissplayer.core

import org.junit.Assert.*
import org.junit.Test

class TrayMenuTest {
    @Test fun everyItemDispatchesExactlyOnce() {
        val seen = mutableListOf<String>()
        for (item in TrayMenu.ITEMS) {
            val handled = TrayMenu.dispatch(
                item.id,
                show = { seen += "show" },
                command = { seen += it.name },
                quit = { seen += "quit" }
            )
            assertTrue("item ${item.id} handled", handled)
        }
        assertEquals(listOf("show", "TOGGLE", "NEXT", "PREV", "quit"), seen)
    }

    @Test fun quitDoesNotSendATransportCommand() {
        val commands = mutableListOf<TransportCommand>()
        var quit = 0
        assertTrue(TrayMenu.dispatch(5, show = {}, command = { commands += it }, quit = { quit++ }))
        assertEquals(1, quit)
        assertTrue("Quit must not pause/toggle playback on the way out", commands.isEmpty())
    }

    @Test fun unknownIdIsNotHandled() {
        assertFalse(TrayMenu.dispatch(99, show = {}, command = {}, quit = {}))
    }
}
