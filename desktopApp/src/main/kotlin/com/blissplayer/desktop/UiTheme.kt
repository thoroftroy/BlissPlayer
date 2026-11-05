package com.blissplayer.desktop

import java.awt.Color
import java.awt.Component
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.UIManager

/**
 * The same 4 theme personalities as Android (Bliss/Dark/Aqua/Super), rebuilt
 * with Swing materials: same hues, same per-theme font, same panel roles.
 * Layout is adaptive (phone/desktop toggle); the look system is shared.
 */
enum class DeskTheme(val key: String, val title: String) {
    BLISS("bliss", "Bliss"),
    DARK("dark", "Dark Bliss"),
    AQUA("aqua", "Aqua Bliss"),
    SUPER("super", "Super Bliss");

    companion object {
        fun from(key: String?): DeskTheme = entries.firstOrNull { it.key == key } ?: BLISS
    }
}

object UiTheme {
    data class Palette(
        val face: Color, val panel: Color, val well: Color,
        val text: Color, val dim: Color, val accent: Color,
        val titleBar: Color, val titleText: Color, val fontName: String
    )

    fun palette(t: DeskTheme): Palette = when (t) {
        DeskTheme.BLISS -> Palette(
            Color(0xEC, 0xE9, 0xD8), Color(0xF5, 0xF4, 0xEA), Color.WHITE,
            Color(0x1A, 0x1A, 0x1A), Color(0x5A, 0x5A, 0x5A), Color(0x24, 0x5D, 0xDA),
            Color(0x00, 0x58, 0xE6), Color.WHITE, "Tahoma")
        DeskTheme.DARK -> Palette(
            Color(0x25, 0x2B, 0x35), Color(0x2E, 0x35, 0x42), Color(0x17, 0x1C, 0x24),
            Color(0xE8, 0xE8, 0xE8), Color(0xA8, 0xAD, 0xB5), Color(0x7C, 0xA7, 0xEE),
            Color(0x10, 0x21, 0x3B), Color.WHITE, "Noto Sans")
        DeskTheme.AQUA -> Palette(
            Color(0xE8, 0xE8, 0xEC), Color(0xF2, 0xF3, 0xF6), Color.WHITE,
            Color(0x19, 0x2C, 0x42), Color(0x4F, 0x63, 0x7A), Color(0x09, 0x6D, 0xE1),
            Color(0xC8, 0xD2, 0xDD), Color(0x1A, 0x1A, 0x1A), "Fira Sans")
        DeskTheme.SUPER -> Palette(
            Color(0x1A, 0x12, 0x30), Color(0x24, 0x1B, 0x44), Color(0x0A, 0x06, 0x18),
            Color(0xF4, 0xF7, 0xFF), Color(0xAF, 0xBE, 0xD7), Color(0x00, 0xF0, 0xFF),
            Color(0x0D, 0x0A, 0x1A), Color(0x6D, 0xFF, 0xE1), "Noto Sans Mono")
    }

    fun font(t: DeskTheme, size: Int, bold: Boolean): Font {
        val name = palette(t).fontName
        var f = runCatching { Font(name, if (bold) Font.BOLD else Font.PLAIN, size) }.getOrNull()
        if (f == null || (f.family == "Dialog" && name != "Tahoma")) {
            val fallbacks = listOf("DejaVu Sans", "Noto Sans", Font.SANS_SERIF)
            f = fallbacks.asSequence()
                .map { Font(it, if (bold) Font.BOLD else Font.PLAIN, size) }
                .firstOrNull { it.family != "Dialog" }
                ?: Font(Font.SANS_SERIF, if (bold) Font.BOLD else Font.PLAIN, size)
        }
        return f
    }

    fun applyFrame(root: JComponent, t: DeskTheme) {
        val p = palette(t)
        root.background = p.face
        root.foreground = p.text
        root.font = font(t, 13, false)
        UIManager.put("Label.foreground", p.text)
        UIManager.put("List.background", p.well)
        UIManager.put("List.foreground", p.text)
        UIManager.put("List.selectionBackground", p.accent)
        UIManager.put("List.selectionForeground", Color.WHITE)
        UIManager.put("TextField.background", p.well)
        UIManager.put("TextField.foreground", p.text)
        UIManager.put("TextField.caretForeground", p.text)
        UIManager.put("Button.background", p.panel)
        UIManager.put("Button.foreground", p.text)
        UIManager.put("Panel.background", p.face)
        UIManager.put("ScrollPane.background", p.face)
        UIManager.put("ToolTip.background", p.panel)
        UIManager.put("ToolTip.foreground", p.text)
    }

    fun panelBorder(t: DeskTheme) =
        BorderFactory.createLineBorder(palette(t).accent.darker(), 1)

    fun styleSection(label: javax.swing.JLabel, t: DeskTheme) {
        val p = palette(t)
        label.font = font(t, 14, true)
        label.foreground = Color.WHITE
        label.background = when (t) {
            DeskTheme.BLISS -> Color(0x00, 0x58, 0xE6)
            DeskTheme.DARK -> Color(0x3A, 0x4A, 0x63)
            DeskTheme.AQUA -> Color(0x2E, 0x86, 0xD4)
            DeskTheme.SUPER -> Color(0x5B, 0x2E, 0x9E)
        }
        label.isOpaque = true
        label.border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
        UIManager.put("unused", p)
    }

    fun styleRow(c: Component, t: DeskTheme, selected: Boolean, stripe: Boolean) {
        val p = palette(t)
        c.background = when {
            selected -> p.accent
            stripe && t == DeskTheme.DARK -> Color(0x25, 0x2E, 0x3C)
            stripe -> p.panel
            else -> p.face
        }
        c.foreground = if (selected) Color.WHITE else p.text
    }
}
