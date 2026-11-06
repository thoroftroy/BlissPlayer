package com.blissplayer.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import com.blissplayer.core.ThemePalettes as P

/**
 * The same 4 theme personalities as Android (Bliss/Dark Bliss/Aqua Bliss/Super Bliss).
 */
enum class DeskTheme(val key: String, val title: String) {
    BLISS("bliss", "Bliss"),
    DARK("dark", "Dark Bliss"),
    AQUA("aqua", "Aqua Bliss"),
    SUPER("super", "Super Bliss");

    companion object {
        fun from(key: String?): DeskTheme = entries.firstOrNull { it.key == key } ?: BLISS
    }

    /** Shared definition; Android reads the same values from `:core`. */
    val bliss: com.blissplayer.core.BlissTheme get() = com.blissplayer.core.BlissTheme.from(key)
}

/**
 * The Android XP materials repainted in Java2D from the same specs:
 * xp_title_bar (Luna blue + gloss + outline), xp_button (raised/sunken
 * bevels), xp_panel (raised face), xp_display (dark-green LCD), xp_field
 * (sunken white), xp_status, xp_column_header, xp_row_selected (+ dotted
 * focus frame), xp_tab (orange top strip), xp_start (green Play), gel pills
 * (Aqua), neon glass (Super). Per-theme colors come from values/colors.xml.
 */
object XpPaint {
    private fun c(argb: Int) = Color(argb, true)

    data class M(
        val titleTop: Color, val titleMid: Color, val titleBot: Color,
        val titleGloss: Int, val titleBorder: Color, val titleBorderW: Int,
        val titleText: Color, val titleShadow: Color?,
        val btnTop: Color, val btnMid: Color, val btnBot: Color,
        val btnBorder: Color, val btnLight: Color, val btnShade: Color,
        val btnText: Color,
        val face: Color, val faceLight: Color, val faceShade: Color,
        val well: Color, val select: Color, val selectText: Color,
        val ink: Color, val dim: Color,
        val displayTop: Color, val displayBot: Color, val displayOuter: Color,
        val lcdInk: Color, val lcdDim: Color,
        val fieldTop: Color, val fieldBot: Color,
        val statusTop: Color, val statusBot: Color,
        val tabOrange: Color = Color(0xF5, 0xA2, 0x3B),
        val startTop: Color = Color(0x8F, 0xD1, 0x4F),
        val startMid: Color = Color(0x3C, 0x9C, 0x3C),
        val startBot: Color = Color(0x2E, 0x7D, 0x32),
        val startBorder: Color = Color(0x1B, 0x5E, 0x20),
        val gelTop: Color = Color(0x7D, 0xB9, 0xE8),
        val gelMid: Color = Color(0x2E, 0x86, 0xD4),
        val gelBot: Color = Color(0x1A, 0x5F, 0xA8),
        val gelBorder: Color = Color(0x0E, 0x3D, 0x6E),
        val neon: Color = Color(0x00, 0xF0, 0xFF),
        val pink: Color = Color(0xFF, 0x2E, 0x9A)
    )

    val BLISS = M(
        titleTop = c(P.XP_TITLE_START), titleMid = Color(0x2A, 0x7F, 0xFF), titleBot = c(P.XP_TITLE_END),
        titleGloss = 0x99, titleBorder = c(P.XP_BORDER), titleBorderW = 1,
        titleText = c(P.XP_TITLE_TEXT), titleShadow = Color(0x80, 0x00, 0x00, 0x00),
        btnTop = c(P.XP_BUTTON_TOP), btnMid = c(P.XP_FACE), btnBot = c(P.XP_FACE_DARK),
        btnBorder = c(P.XP_BORDER), btnLight = Color.WHITE, btnShade = Color(0x80, 0x80, 0x80),
        btnText = Color(0x1A, 0x1A, 0x1A),
        face = c(P.XP_FACE), faceLight = Color.WHITE, faceShade = Color(0x80, 0x80, 0x80),
        well = Color.WHITE, select = c(P.XP_SELECT), selectText = Color.WHITE,
        ink = Color(0x1A, 0x1A, 0x1A), dim = c(P.XP_TEXT_DIM),
        displayTop = Color(0x18, 0x2B, 0x24), displayBot = Color(0x0B, 0x17, 0x14), displayOuter = Color(0x08, 0x0C, 0x0A),
        lcdInk = Color(0xCA, 0xEF, 0x9B), lcdDim = Color(0x99, 0xC9, 0xB0),
        fieldTop = Color(0x80, 0x80, 0x80), fieldBot = Color.WHITE,
        statusTop = c(P.XP_FACE_DARK), statusBot = Color.WHITE
    )

    val DARK = M(
        titleTop = c(P.XPN_TITLE_START), titleMid = c(P.XPN_SELECT), titleBot = Color(0x2A, 0x6A, 0xD4),
        titleGloss = 0x66, titleBorder = c(P.XPN_BORDER), titleBorderW = 1,
        titleText = Color.WHITE, titleShadow = Color(0x80, 0x00, 0x00, 0x00),
        btnTop = c(P.XPN_BUTTON_TOP), btnMid = c(P.XPN_FACE2), btnBot = c(P.XPN_BUTTON_BOTTOM),
        btnBorder = c(P.XPN_BORDER), btnLight = Color(0x6A, 0x72, 0x80), btnShade = Color(0x14, 0x16, 0x1A),
        btnText = c(P.XPN_TEXT),
        face = Color(0x25, 0x2B, 0x35), faceLight = Color(0x41, 0x4D, 0x60), faceShade = Color(0x10, 0x17, 0x21),
        well = Color(0x17, 0x1C, 0x24), select = c(P.XPN_SELECT), selectText = Color.WHITE,
        ink = c(P.XPN_TEXT), dim = c(P.XPN_TEXT_DIM),
        displayTop = Color(0x18, 0x2B, 0x24), displayBot = Color(0x0B, 0x17, 0x14), displayOuter = Color(0x08, 0x0C, 0x0A),
        lcdInk = Color(0xCA, 0xEF, 0x9B), lcdDim = Color(0x99, 0xC9, 0xB0),
        fieldTop = Color(0x14, 0x16, 0x1A), fieldBot = Color(0x10, 0x12, 0x16),
        statusTop = Color(0x23, 0x26, 0x2B), statusBot = Color(0x14, 0x16, 0x1A)
    )

    val AQUA = M(
        titleTop = c(P.AQ_TITLE_TOP), titleMid = c(P.AQ_TITLE_MID), titleBot = c(P.AQ_TITLE_BOT),
        titleGloss = 0xCC, titleBorder = c(P.AQ_BORDER), titleBorderW = 1,
        titleText = c(P.AQ_TEXT), titleShadow = Color(0x80, 0xFF, 0xFF, 0xFF),
        btnTop = c(P.AQ_GEL_TOP), btnMid = c(P.AQ_GEL_MID), btnBot = c(P.AQ_GEL_BOT),
        btnBorder = Color(0x0E, 0x3D, 0x6E), btnLight = Color(0xB3, 0xFF, 0xFF, 0xFF), btnShade = Color(0x0E, 0x3D, 0x6E),
        btnText = Color.WHITE,
        face = c(P.AQ_FACE), faceLight = Color.WHITE, faceShade = c(P.AQ_BORDER),
        well = Color.WHITE, select = c(P.AQ_SELECT), selectText = c(P.AQ_SELECT_TEXT),
        ink = Color(0x19, 0x2C, 0x42), dim = Color(0x4F, 0x63, 0x7A),
        displayTop = Color(0x18, 0x2B, 0x24), displayBot = Color(0x0B, 0x17, 0x14), displayOuter = Color(0x08, 0x0C, 0x0A),
        lcdInk = Color(0x19, 0x2C, 0x42), lcdDim = Color(0x4F, 0x63, 0x7A),
        fieldTop = Color(0xF2, 0xF2, 0xF5), fieldBot = Color(0xDC, 0xDE, 0xE3),
        statusTop = c(P.AQ_FACE), statusBot = Color(0xC4, 0xC7, 0xCE)
    )

    val SUPER = M(
        titleTop = c(P.SP_TITLE_TOP), titleMid = c(P.SP_TITLE_TOP), titleBot = c(P.SP_TITLE_BOT),
        titleGloss = 0xAA, titleBorder = c(P.SP_NEON), titleBorderW = 2,
        titleText = Color(0x6D, 0xFF, 0xE1), titleShadow = null,
        btnTop = c(P.SP_PINK), btnMid = Color(0x2A, 0x1B, 0x4E), btnBot = Color(0x12, 0x0B, 0x28),
        btnBorder = c(P.SP_NEON), btnLight = Color(0x66, 0x00, 0xF0, 0xFF), btnShade = Color(0x00, 0x00, 0x00),
        btnText = Color(0xF4, 0xF7, 0xFF),
        face = c(P.SP_FACE2), faceLight = Color(0x2A, 0x1B, 0x4E), faceShade = Color(0x05, 0x03, 0x10),
        well = Color(0x0A, 0x06, 0x18), select = c(P.SP_SELECT), selectText = Color.WHITE,
        ink = Color(0xF4, 0xF7, 0xFF), dim = Color(0xAF, 0xBE, 0xD7),
        displayTop = Color(0x18, 0x2B, 0x24), displayBot = Color(0x0B, 0x17, 0x14), displayOuter = Color(0x08, 0x0C, 0x0A),
        lcdInk = c(P.SP_NEON), lcdDim = Color(0xAF, 0xBE, 0xD7),
        fieldTop = Color(0x0A, 0x06, 0x18), fieldBot = Color(0x0A, 0x06, 0x18),
        statusTop = c(P.SP_FACE2), statusBot = Color(0x0D, 0x0A, 0x1A)
    )

    fun of(theme: DeskTheme): M = when (theme) {
        DeskTheme.BLISS -> BLISS
        DeskTheme.DARK -> DARK
        DeskTheme.AQUA -> AQUA
        DeskTheme.SUPER -> SUPER
    }

    private fun g2(g: java.awt.Graphics): Graphics2D =
        (g.create() as Graphics2D).apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        }

    private fun vgrad(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, top: Color, mid: Color?, bot: Color) {
        val (colors, fractions) = if (mid == null) arrayOf(top, bot) to floatArrayOf(0f, 1f)
        else arrayOf(top, mid, bot) to floatArrayOf(0f, 0.5f, 1f)
        g.paint = LinearGradientPaint(x.toFloat(), y.toFloat(), x.toFloat(), (y + h).toFloat(), fractions, colors)
        g.fillRect(x, y, w, h)
    }

    /** Luna title bar: 3-stop blue, white gloss over the top half, dark outline. */
    fun titleBar(g: java.awt.Graphics, w: Int, h: Int, m: M) {
        val g2 = g2(g)
        vgrad(g2, 0, 0, w, h, m.titleTop, m.titleMid, m.titleBot)
        g2.paint = LinearGradientPaint(0f, 0f, 0f, h / 2f, floatArrayOf(0f, 1f),
            arrayOf(Color(255, 255, 255, m.titleGloss), Color(255, 255, 255, 0)))
        g2.fillRect(0, 0, w, h / 2)
        g2.color = m.titleBorder
        g2.stroke = BasicStroke(m.titleBorderW.toFloat())
        g2.draw(RoundRectangle2D.Float(1f, 1f, w - 3f, h - 2f, 14f, 14f))
        g2.dispose()
    }

    /** Chicago95 raised bevel button (white light TL, gray shade BR). */
    fun button(g: java.awt.Graphics, w: Int, h: Int, m: M, pressed: Boolean, pill: Boolean = false) {
        val g2 = g2(g)
        val r = if (pill) h / 2f else 3f
        fun rr(inset: Int) = RoundRectangle2D.Float(inset.toFloat(), inset.toFloat(),
            (w - inset * 2).toFloat(), (h - inset * 2).toFloat(), r, r)
        g2.color = m.btnBorder
        g2.fill(rr(0))
        if (pressed) {
            g2.color = Color(0x80, 0x80, 0x80)
            g2.fill(rr(1))
            vgrad(g2, 2, 2, w - 4, h - 4, m.btnShade, null, m.btnMid)
        } else {
            if (pill) {
                vgrad(g2, 1, 1, w - 2, h - 2, m.gelTop, m.gelMid, m.gelBot)
                g2.paint = LinearGradientPaint(0f, 1f, 0f, h / 2f, floatArrayOf(0f, 1f),
                    arrayOf(Color(255, 255, 255, 0xB3), Color(255, 255, 255, 0)))
                g2.fill(rr(1))
            } else {
                vgrad(g2, 1, 1, w - 2, h - 2, m.btnTop, m.btnMid, m.btnBot)
                g2.color = m.btnLight
                g2.drawLine(2, 2, w - 3, 2)
                g2.drawLine(2, 2, 2, h - 3)
                g2.color = m.btnShade
                g2.drawLine(2, h - 3, w - 3, h - 3)
                g2.drawLine(w - 3, 2, w - 3, h - 3)
            }
        }
        g2.dispose()
    }

    /** Green Play/Mix start button. */
    fun startButton(g: java.awt.Graphics, w: Int, h: Int, m: M) {
        val g2 = g2(g)
        vgrad(g2, 0, 0, w, h, m.startTop, m.startMid, m.startBot)
        g2.color = m.startBorder
        g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, 6f, 6f))
        g2.dispose()
    }

    /** Raised window face panel. */
    fun panel(g: java.awt.Graphics, w: Int, h: Int, m: M) {
        val g2 = g2(g)
        g2.color = m.faceShade
        g2.fillRect(0, 0, w, h)
        g2.color = m.face
        g2.fillRect(1, 1, w - 2, h - 2)
        g2.color = m.faceLight
        g2.drawLine(1, 1, w - 2, 1)
        g2.drawLine(1, 1, 1, h - 2)
        g2.dispose()
    }

    /** Dark-green LCD deck. */
    fun display(g: java.awt.Graphics, w: Int, h: Int, m: M) {
        val g2 = g2(g)
        g2.color = m.displayOuter
        g2.fillRect(0, 0, w, h)
        vgrad(g2, 2, 2, w - 4, h - 4, m.displayTop, null, m.displayBot)
        g2.dispose()
    }

    /** Sunken entry field. */
    fun field(g: java.awt.Graphics, w: Int, h: Int, m: M, aquaGel: Boolean = false) {
        val g2 = g2(g)
        if (aquaGel) {
            vgrad(g2, 0, 0, w, h, m.fieldTop, null, m.fieldBot)
        } else {
            g2.color = m.fieldTop
            g2.fillRect(0, 0, w, h)
            g2.color = m.fieldBot
            g2.fillRect(1, 1, w - 2, h - 2)
        }
        g2.dispose()
    }

    /** Status strip (groove look). */
    fun status(g: java.awt.Graphics, w: Int, h: Int, m: M) {
        val g2 = g2(g)
        vgrad(g2, 0, 0, w, h, m.statusTop, null, m.statusBot)
        g2.dispose()
    }

    /** Playing row: XP blue + dotted white focus frame. */
    fun rowSelected(g: java.awt.Graphics, w: Int, h: Int, m: M) {
        val g2 = g2(g)
        g2.color = m.select
        g2.fillRect(0, 0, w, h)
        g2.color = Color.WHITE
        g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(2f, 2f), 0f)
        g2.drawRect(2, 2, w - 5, h - 5)
        g2.dispose()
    }

    /** Tab: orange top strip when selected, raised face otherwise. */
    fun tab(g: java.awt.Graphics, w: Int, h: Int, m: M, selected: Boolean) {
        val g2 = g2(g)
        if (selected) {
            g2.color = m.faceShade
            g2.fillRect(0, 0, w, h)
            g2.color = m.face
            g2.fillRect(1, 1, w - 2, h - 2)
            g2.color = m.tabOrange
            g2.fillRect(1, 1, w - 2, 3)
        } else {
            g2.color = m.faceShade
            g2.fillRect(0, 0, w, h)
            g2.color = m.faceLight
            g2.fillRect(1, 2, w - 2, h - 3)
            g2.color = m.face
            g2.fillRect(2, 3, w - 4, h - 5)
        }
        g2.dispose()
    }

    /** Section header: same per-panel hue gradient as Android sectionBg(). */
    fun section(g: java.awt.Graphics, w: Int, h: Int, base: Color) {
        val g2 = g2(g)
        fun shade(c: Color, f: Float): Color {
            fun ch(v: Int) = (v * f).coerceIn(0f, 255f).toInt()
            return Color(ch(c.red), ch(c.green), ch(c.blue))
        }
        vgrad(g2, 0, 0, w, h, shade(base, 1.35f), base, shade(base, 0.75f))
        g2.color = shade(base, 0.55f)
        g2.drawRect(0, 0, w - 1, h - 1)
        g2.dispose()
    }

    /** Segmented XP progress fill: repeats your xp_seg_tile PNG, clipped. */
    fun segFill(g: java.awt.Graphics, w: Int, h: Int, tile: java.awt.image.BufferedImage?) {
        if (tile == null || w <= 0 || h <= 0) return
        val g2 = g.create() as Graphics2D
        g2.clipRect(0, 0, w, h)
        var x = 0
        while (x < w) {
            g2.drawImage(tile, x, 0, tile.width, h, null)
            x += tile.width
        }
        g2.dispose()
    }

    /** Scrolling rainbow (Super progress): one gradient, translated. */
    fun rainbowFill(g: java.awt.Graphics, w: Int, h: Int, phase: Float) {
        if (w <= 0) return
        val g2 = g.create() as Graphics2D
        val span = (w * 1.6f).coerceAtLeast(1f)
        val n = 7
        val colors = Array(n + 1) { i ->
            Color.getHSBColor(((phase + i / n.toFloat()) % 1f + 1f) % 1f, 0.85f, 1f)
        }
        g2.paint = LinearGradientPaint(0f, 0f, span, 0f,
            (0..n).map { it / n.toFloat() }.toFloatArray(), colors)
        g2.translate((-w * 1.6f * phase).toDouble(), 0.0)
        g2.fillRect(0, 0, (w * 2.6f).toInt(), h)
        g2.dispose()
    }

    /** Three small dots, like ic_more. */
    fun dots(g: java.awt.Graphics, x: Int, y: Int, color: java.awt.Color) {
        val g2 = g.create() as Graphics2D
        g2.color = color
        for (i in -1..1) g2.fillOval(x - 1, y - 1 + i * 6, 3, 3)
        g2.dispose()
    }
}
