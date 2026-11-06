package com.blissplayer.core

import org.junit.Assert.*
import org.junit.Test

class ModernRowDrawingTest {
    private data class Rect(val l: Float, val t: Float, val r: Float, val b: Float, val rad: Float,
        val fill: ModernFill?, val edge: Int, val edgeW: Float)
    private data class Circ(val cx: Float, val cy: Float, val r: Float,
        val fill: ModernFill?, val edge: ModernFill?, val edgeW: Float)
    private data class Poly(val pts: List<Pair<Float, Float>>, val fill: ModernFill?,
        val edge: ModernFill?, val edgeW: Float)

    private class RecordingCanvas : ModernCanvas {
        val cards = mutableListOf<Rect>()
        val circles = mutableListOf<Circ>()
        val rects = mutableListOf<Rect>()
        val polys = mutableListOf<Poly>()
        override fun roundCard(l: Float, t: Float, r: Float, b: Float, radius: Float,
                fill: ModernFill, edgeColor: Int, edgeWidth: Float) {
            cards += Rect(l, t, r, b, radius, fill, edgeColor, edgeWidth)
        }
        override fun circle(cx: Float, cy: Float, r: Float, fill: ModernFill?,
                edge: ModernFill?, edgeWidth: Float) {
            circles += Circ(cx, cy, r, fill, edge, edgeWidth)
        }
        override fun roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, fill: ModernFill) {
            rects += Rect(l, t, r, b, radius, fill, 0, 0f)
        }
        override fun poly(pts: List<Pair<Float, Float>>, fill: ModernFill?,
                edge: ModernFill?, edgeWidth: Float) {
            polys += Poly(pts, fill, edge, edgeWidth)
        }
    }

    @Test fun aquaCardsUseMaterialStopsAndHairline() {
        val c = RecordingCanvas()
        RowCardDrawing.draw(c, 2f, 2f, 100f, 60f, 2f, BlissTheme.AQUA, selected = false)
        assertEquals(1, c.cards.size)
        val card = c.cards.single()
        assertEquals(8f, card.rad, 0f) // 4dp radius, like MaterialDrawable ROW
        assertEquals(0x66FFFFFF, card.edge)
        assertEquals(1.5f, card.edgeW, 0f) // 0.75dp hairline
        val stops = ((card.fill as ModernFill.Linear).colors)
        assertEquals(ThemeGradients.AQUA_ROW, stops)
        RowCardDrawing.draw(c, 2f, 2f, 100f, 60f, 2f, BlissTheme.AQUA, selected = true)
        val sel = c.cards.last()
        assertEquals(ThemeGradients.AQUA_ROW_SELECTED,
            (sel.fill as ModernFill.Linear).colors)
        assertEquals(ThemeGradients.AQUA_ROW_SELECTED_EDGE, sel.edge)
    }

    @Test fun superCardsUsePanelStops() {
        val c = RecordingCanvas()
        RowCardDrawing.draw(c, 0f, 0f, 100f, 60f, 1f, BlissTheme.SUPER, selected = false)
        val card = c.cards.single()
        assertEquals(14f, card.rad, 0f)
        assertEquals(ThemeGradients.SUPER_PANEL, (card.fill as ModernFill.Linear).colors)
        RowCardDrawing.draw(c, 0f, 0f, 100f, 60f, 1f, BlissTheme.SUPER, selected = true)
        assertEquals(ThemeGradients.SUPER_ROW_SELECTED,
            (c.cards.last().fill as ModernFill.Linear).colors)
    }

    @Test fun retroThemesDrawNoModernCard() {
        val c = RecordingCanvas()
        RowCardDrawing.draw(c, 0f, 0f, 100f, 60f, 1f, BlissTheme.BLISS, selected = false)
        RowCardDrawing.draw(c, 0f, 0f, 100f, 60f, 1f, BlissTheme.DARK, selected = true)
        assertTrue(c.cards.isEmpty())
    }

    @Test fun aquaGlyphIsDiscWithRingsAndHub() {
        val c = RecordingCanvas()
        TrackGlyphDrawing.draw(c, 0f, 0f, 100f, BlissTheme.AQUA, group = false)
        assertEquals(1 + 4 + 1, c.circles.size)
        val disc = c.circles.first()
        assertEquals(50f, disc.cx, 0f)
        assertEquals(45f, disc.r, 0f)
        assertEquals(ThemeGlyphs.AQUA_DISC, (disc.fill as ModernFill.Linear).colors)
        assertEquals(1.5f, c.circles[1].edgeW, 0f) // max(1, 100*.015)
        val hub = c.circles.last()
        assertEquals(ModernFill.Solid(ThemeGlyphs.AQUA_HUB), hub.fill)
        assertTrue(c.rects.isEmpty())
        TrackGlyphDrawing.draw(c, 0f, 0f, 100f, BlissTheme.AQUA, group = true)
        assertEquals(1, c.rects.size)
        assertEquals(ModernFill.Solid(ThemeGlyphs.AQUA_GROUP_BOX), c.rects.single().fill)
        assertEquals(3f, c.rects.single().rad)
    }

    @Test fun superGlyphIsHexagonWithPlayOrBox() {
        val c = RecordingCanvas()
        TrackGlyphDrawing.draw(c, 0f, 0f, 100f, BlissTheme.SUPER, group = false)
        assertEquals(2, c.polys.size) // hexagon outline + play triangle
        val hex = c.polys[0]
        assertEquals(6, hex.pts.size)
        // First vertex straight up, radius .43 of the box.
        assertEquals(50f, hex.pts[0].first, 0.01f)
        assertEquals(50f - 43f, hex.pts[0].second, 0.01f)
        // Edge carries the neon gradient like ThemeGlyphDrawable's shader.
        assertTrue(hex.edge is ModernFill.Linear)
        assertEquals(3, c.polys[1].pts.size)
        TrackGlyphDrawing.draw(c, 0f, 0f, 100f, BlissTheme.SUPER, group = true)
        assertEquals(1, c.rects.size)
        TrackGlyphDrawing.draw(c, 0f, 0f, 0f, BlissTheme.SUPER, group = false)
    }

    @Test fun retroThemesDrawNoModernGlyph() {
        val c = RecordingCanvas()
        TrackGlyphDrawing.draw(c, 0f, 0f, 100f, BlissTheme.BLISS, group = false)
        TrackGlyphDrawing.draw(c, 0f, 0f, 100f, BlissTheme.DARK, group = true)
        assertTrue(c.circles.isEmpty() && c.polys.isEmpty() && c.rects.isEmpty())
    }
}
