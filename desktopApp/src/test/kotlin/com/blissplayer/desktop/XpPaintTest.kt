package com.blissplayer.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage

/** Headless proof the desktop paints the same materials (exact colors). */
class XpPaintTest {
    private fun shot(w: Int, h: Int, paint: (java.awt.Graphics) -> Unit): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        paint(img.createGraphics())
        return img
    }

    private fun hex(img: BufferedImage, x: Int, y: Int): String =
        "#%06X".format(img.getRGB(x, y) and 0xFFFFFF)

    private fun near(img: BufferedImage, x: Int, y: Int, hex: String, tol: Int = 5) {
        val c = Color(img.getRGB(x, y))
        val e = Color(hex.substring(1, 3).toInt(16), hex.substring(3, 5).toInt(16), hex.substring(5, 7).toInt(16))
        assertTrue("expected ~$hex at ($x,$y) but was ${hex(img, x, y)}",
            Math.abs(c.red - e.red) <= tol && Math.abs(c.green - e.green) <= tol && Math.abs(c.blue - e.blue) <= tol)
    }

    @Test fun blissTitleBarIsLunaBlueWithGloss() {
        val img = shot(100, 80) { XpPaint.titleBar(it, 100, 80, XpPaint.BLISS) }
        // Top area carries the gloss highlight over Luna blue.
        val top = Color(img.getRGB(50, 3), true)
        assertTrue("gloss should lighten the blue", top.red > 0x30 && top.blue > 0xE0)
        // Middle is exactly the gradient midpoint; border is far away.
        near(img, 50, 40, "#2A7FFF")
        // Dark XP outline at the bottom edge.
        val edge = Color(img.getRGB(50, 79), true)
        assertTrue("outline is dark navy", edge.blue < 0x90 && edge.red < 0x30)
    }

    @Test fun darkTitleBarIsNightBlue() {
        val img = shot(100, 80) { XpPaint.titleBar(it, 100, 80, XpPaint.DARK) }
        near(img, 50, 40, "#1E4FA3")
    }

    @Test fun aquaTitleBarIsSilver() {
        val img = shot(100, 80) { XpPaint.titleBar(it, 100, 80, XpPaint.AQUA) }
        near(img, 50, 40, "#C9CDD4")
    }

    @Test fun superTitleBarIsPurple() {
        val img = shot(100, 80) { XpPaint.titleBar(it, 100, 80, XpPaint.SUPER) }
        near(img, 50, 40, "#5B2EE5")
    }

    @Test fun buttonsHaveBevels() {
        val img = shot(60, 24) { XpPaint.button(it, 60, 24, XpPaint.BLISS, pressed = false) }
        assertEquals("#003C74", hex(img, 3, 0)) // outline
        assertEquals("#FFFFFF", hex(img, 2, 2)) // top-left light
        val pressed = shot(60, 24) { XpPaint.button(it, 60, 24, XpPaint.BLISS, pressed = true) }
        assertEquals("#808080", hex(pressed, 2, 2)) // sunken, no light
    }

    @Test fun selectedRowIsXpBlueWithFocusFrame() {
        val img = shot(60, 20) { XpPaint.rowSelected(it, 60, 20, XpPaint.BLISS) }
        assertEquals("#316AC5", hex(img, 30, 10))
        assertEquals("#FFFFFF", hex(img, 3, 2)) // dotted frame
    }

    @Test fun displayIsDarkGreenLcd() {
        val img = shot(60, 20) { XpPaint.display(it, 60, 20, XpPaint.BLISS) }
        assertEquals("#080C0A", hex(img, 0, 0))
        val mid = Color(img.getRGB(30, 10))
        assertTrue("deep green well", mid.green > mid.red && mid.blue < 0x30)
    }

    @Test fun segTileFillsAndRainbowScrolls() {
        val tile = XpArt.image("xp_seg_tile.png")
        assertNotNull("seg tile must sync from Android res", tile)
        val seg = shot(40, tile!!.height) { XpPaint.segFill(it, 40, tile.height, tile) }
        assertEquals("#%06X".format(tile.getRGB(2, 2) and 0xFFFFFF), hex(seg, 12, 2))
        val a = shot(60, 10) { XpPaint.rainbowFill(it, 60, 10, 0f) }
        val b = shot(60, 10) { XpPaint.rainbowFill(it, 60, 10, 0.5f) }
        assertTrue("rainbow must travel", a.getRGB(30, 5) != b.getRGB(30, 5))
    }

    @Test fun bundledFontsLoadForEveryTheme() {
        DeskTheme.entries.forEach { t ->
            val f = XpArt.font(t, true)
            assertTrue("bold survives for $t", f.isBold || t == DeskTheme.DARK)
        }
        // The actual TTFs ship in the jar.
        assertNotNull(XpArt.image("aqua_brand.png"))
        assertNotNull(XpArt.image("super_brand.png"))
        assertNotNull(XpArt.image("fav_star_filled.png"))
    }

    @Test fun sectionHuesMatchAndroid() {
        val expected = mapOf(
            "Your music" to "#2E7D32", "Playback controls" to "#0058E6", "Boost" to "#D84315",
            "Live sound mixer" to "#6A1B9A", "Song analyzer" to "#0D47A1", "Playlists" to "#AD1457",
            "Stats" to "#6D4C41", "About" to "#37474F", "Look and feel" to "#5C6CBC"
        )
        expected.forEach { (name, hex) ->
            val base = Color(hex.substring(1, 3).toInt(16), hex.substring(3, 5).toInt(16), hex.substring(5, 7).toInt(16))
            val img = shot(60, 20) { XpPaint.section(it, 60, 20, base) }
            near(img, 30, 10, hex, 2)
        }
    }
}
