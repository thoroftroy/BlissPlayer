package com.blissplayer.core

/** Tray actions shared by every Linux tray backend (AWT fallback and native SNI). */
enum class TrayAction { SHOW, TOGGLE, NEXT, PREV, QUIT }

data class TrayMenuItem(val id: Int, val action: TrayAction, val label: String)

/** Single tray menu model: both backends render these items and dispatch by id. */
object TrayMenu {
    val ITEMS = listOf(
        TrayMenuItem(1, TrayAction.SHOW, "Show"),
        TrayMenuItem(2, TrayAction.TOGGLE, "Play/Pause"),
        TrayMenuItem(3, TrayAction.NEXT, "Next"),
        TrayMenuItem(4, TrayAction.PREV, "Previous"),
        TrayMenuItem(5, TrayAction.QUIT, "Quit")
    )

    fun byId(id: Int): TrayMenuItem? = ITEMS.firstOrNull { it.id == id }

    /** Returns true when the id was known and handled. */
    fun dispatch(
        itemId: Int,
        show: () -> Unit,
        command: (TransportCommand) -> Unit,
        quit: () -> Unit
    ): Boolean {
        return when (byId(itemId)?.action) {
            TrayAction.SHOW -> { show(); true }
            TrayAction.TOGGLE -> { command(TransportCommand.TOGGLE); true }
            TrayAction.NEXT -> { command(TransportCommand.NEXT); true }
            TrayAction.PREV -> { command(TransportCommand.PREV); true }
            TrayAction.QUIT -> { quit(); true }
            null -> false
        }
    }
}
