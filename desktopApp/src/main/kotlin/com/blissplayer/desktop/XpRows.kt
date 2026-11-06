package com.blissplayer.desktop

import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.image.BufferedImage
import javax.swing.JComponent

/** Star art per theme: Bliss lightened, Dark default, Aqua blue-shifted, Super animated rainbow. */
object StarArt {
    private val cache = mutableMapOf<String, BufferedImage?>()

    /** The user's 192px art carries wide transparent margins; crop once so the star fills its box. */
    private val cropped = mutableMapOf<String, BufferedImage?>()
    fun croppedOf(name: String): BufferedImage? = cropped.getOrPut(name) {
        val src = XpArt.image(name) ?: return@getOrPut null
        var x0 = src.width; var y0 = src.height; var x1 = 0; var y1 = 0
        for (x in 0 until src.width) for (y in 0 until src.height) {
            if (src.getRGB(x, y) ushr 24 > 8) {
                if (x < x0) x0 = x; if (y < y0) y0 = y
                if (x > x1) x1 = x; if (y > y1) y1 = y
            }
        }
        if (x1 <= x0 || y1 <= y0) return@getOrPut src
        src.getSubimage(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    fun image(theme: DeskTheme, loved: Boolean, tick: Int = 0): BufferedImage? {
        val key = "${theme.key}:$loved:${if (theme == DeskTheme.SUPER && loved) tick % 24 else 0}"
        cache[key]?.let { return it }
        val img = if (!loved) {
            croppedOf(if (theme == DeskTheme.BLISS) "fav_star_outline_lt.png" else "fav_star_outline.png")
        } else when (theme) {
            DeskTheme.BLISS -> brighten(croppedOf("fav_star_filled.png"), 1.08f)
            DeskTheme.DARK -> croppedOf("fav_star_filled.png")
            DeskTheme.AQUA -> blueShift(croppedOf("fav_star_filled.png"))
            DeskTheme.SUPER -> rainbowStar(croppedOf("fav_star_filled.png"), (tick % 24) / 24f)
        }
        if (img != null && cache.size < 64) cache[key] = img
        return img
    }

    fun brighten(src: BufferedImage?, f: Float): BufferedImage? {
        if (src == null) return null
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.drawImage(src, 0, 0, null)
        g.dispose()
        return java.awt.image.RescaleOp(floatArrayOf(f, f, f, 1f), floatArrayOf(0f, 0f, 0f, 0f), null).filter(out, null)
    }

    fun blueShift(src: BufferedImage?): BufferedImage? {
        if (src == null) return null
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until src.width) for (y in 0 until src.height) {
            val argb = src.getRGB(x, y)
            val a = argb ushr 24
            if (a == 0) continue
            val hsb = Color.RGBtoHSB(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF, null)
            out.setRGB(x, y, (a shl 24) or (Color.HSBtoRGB(0.58f, hsb[1].coerceAtLeast(0.5f), hsb[2]) and 0xFFFFFF))
        }
        return out
    }

    fun rainbowStar(src: BufferedImage?, phase: Float): BufferedImage? {
        if (src == null) return null
        val w = src.width; val h = src.height
        val grad = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = grad.createGraphics() as java.awt.Graphics2D
        val n = 7
        val colors = Array(n + 1) { i -> Color.getHSBColor((phase + i / n.toFloat()) % 1f, 0.9f, 1f) }
        g.paint = java.awt.LinearGradientPaint(0f, h.toFloat(), w.toFloat(), 0f,
            (0..n).map { it / n.toFloat() }.toFloatArray(), colors)
        g.fillRect(0, 0, w, h)
        g.dispose()
        val mask = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val mg = mask.createGraphics()
        mg.drawImage(grad, 0, 0, null)
        mg.composite = java.awt.AlphaComposite.DstIn
        mg.drawImage(src, 0, 0, null)
        mg.dispose()
        val out = BufferedImage(w + 8, h + 8, BufferedImage.TYPE_INT_ARGB)
        val og = out.createGraphics()
        val halo = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val hg = halo.createGraphics()
        hg.drawImage(src, 0, 0, null)
        hg.composite = java.awt.AlphaComposite.SrcIn
        hg.color = Color.BLACK
        hg.fillRect(0, 0, w, h)
        hg.dispose()
        og.drawImage(halo, 5, 5, null)
        og.drawImage(mask, 4, 4, null)
        og.dispose()
        return out
    }
}

data class RowData(
    val title: String,
    val sub: String,
    val dur: String,
    val isGroup: Boolean = false,
    val isNew: Boolean = false,
    val loved: Boolean = false,
    val playing: Boolean = false,
    val selected: Boolean = false,
    val stripe: Boolean = false
)

/**
 * One library row, painted entirely by hand: stripe/selected background,
 * folder-or-note glyph, two-line text, duration, star, dots. The hit zones
 * used by mouse handling are derived from the same constants.
 */
class RowPanel(private val themeOf: () -> DeskTheme, private val tickOf: () -> Int = { 0 }) : JComponent() {
    var row: RowData? = null

    companion object {
        const val H = 64
        const val STAR_X = 88
        const val DOTS_W = 44
    }

    init { preferredSize = Dimension(100, H) }

    override fun paintComponent(g: Graphics) {
        val theme = themeOf()
        val m = XpPaint.of(theme)
        val v = row ?: run {
            g.color = m.face; g.fillRect(0, 0, width, height); return
        }
        val w = width; val h = height
        val hl = v.selected || v.playing
        if (hl) {
            XpPaint.rowSelected(g, w, h, m)
        } else {
            g.color = when {
                theme == DeskTheme.BLISS -> if (v.stripe) Color(0xF0, 0xF5, 0xFC) else Color.WHITE
                theme == DeskTheme.DARK -> if (v.stripe) Color(0x25, 0x2E, 0x3C) else Color(0x1B, 0x22, 0x2D)
                else -> if (v.stripe) m.face else m.faceLight
            }
            g.fillRect(0, 0, w, h)
        }
        val ink = if (hl) m.selectText else if (theme == DeskTheme.BLISS || theme == DeskTheme.AQUA) Color(0x1A, 0x1A, 0x1A) else m.ink
        val dim = if (hl) m.selectText else m.dim
        // Glyph: two-tone folder for groups, slim note for songs.
        if (v.isGroup || v.isNew) {
            g.color = if (hl) m.selectText else if (theme == DeskTheme.BLISS || theme == DeskTheme.AQUA) Color(0xD8, 0xD0, 0xB8) else m.faceLight
            g.fillRoundRect(8, h / 2 - 8, 22, 14, 3, 3)
            g.fillRoundRect(8, h / 2 - 11, 12, 6, 2, 2)
            g.color = if (hl) m.selectText else m.dim
            g.drawRoundRect(8, h / 2 - 8, 22, 14, 3, 3)
            g.drawRoundRect(8, h / 2 - 11, 12, 6, 2, 2)
        } else {
            g.color = if (hl) m.selectText else m.dim
            g.fillOval(8, h / 2 - 1, 10, 8)
            g.fillRect(17, h / 2 - 15, 2, 15)
        }
        // Texts.
        g.font = XpArt.sized(theme, 14, true)
        g.color = ink
        val title = (if (v.playing) "> " else "") + v.title
        g.drawString(title, 44, h / 2 - 6)
        g.font = XpArt.sized(theme, 11, false)
        g.color = dim
        g.drawString(v.sub, 44, h / 2 + 14)
        // Duration, right-aligned clear of the star.
        if (v.dur.isNotBlank()) {
            val fm = g.fontMetrics
            g.drawString(v.dur, w - STAR_X - 12 - fm.stringWidth(v.dur), h / 2 + 4)
        }
        // Star + dots.
        if (!v.isGroup && !v.isNew) {
            StarArt.image(theme, v.loved, tickOf())?.let { g.drawImage(it, w - STAR_X, h / 2 - 14, 28, 28, null) }
        }
        XpPaint.dots(g, w - DOTS_W / 2, h / 2, if (hl) m.selectText else m.ink)
    }
}
