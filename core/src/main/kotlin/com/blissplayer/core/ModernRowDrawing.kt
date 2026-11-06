package com.blissplayer.core

/** Fill for modern canvas shapes. Coordinates are absolute pixels. */
sealed interface ModernFill {
    data class Solid(val color: Int) : ModernFill
    data class Linear(
        val colors: List<Int>,
        val fractions: List<Float>? = null,
        val x0: Float = 0f, val y0: Float = 0f, val x1: Float = 0f, val y1: Float = 0f
    ) : ModernFill
}

/**
 * Minimal canvas both renderers implement. Android maps to Canvas/Paint,
 * Linux to Graphics2D. All coordinates are pixels in the local box.
 */
interface ModernCanvas {
    fun roundCard(
        left: Float, top: Float, right: Float, bottom: Float, radius: Float,
        fill: ModernFill, edgeColor: Int, edgeWidth: Float
    )
    fun circle(cx: Float, cy: Float, r: Float, fill: ModernFill?, edge: ModernFill? = null, edgeWidth: Float = 0f)
    fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float, fill: ModernFill)
    fun poly(points: List<Pair<Float, Float>>, fill: ModernFill?, edge: ModernFill? = null, edgeWidth: Float = 0f)
}

/** Glyph colors from `ThemeGlyphDrawable`; disc/hex geometry shared too. */
object ThemeGlyphs {
    val AQUA_DISC = listOf(0xFFF9FFFF.toInt(), 0xFF6DB3E4.toInt(), 0xFFE4F6FF.toInt(), 0xFF387ABA.toInt())
    const val AQUA_RING = 0x7799D6F3.toInt()
    const val AQUA_HUB = 0xDDFFFFFF.toInt()
    const val AQUA_GROUP_BOX = 0xBD277DCA.toInt()
    val SUPER_HEX = listOf(0xFF9BFFE5.toInt(), 0xFF86B3FF.toInt(), 0xFFFFB0D0.toInt())
}

/**
 * Rounded row cards, mirroring `MaterialDrawable`'s ROW branch (same stops,
 * radii and hairline insets, driven by dp density). Bliss/Dark keep XP
 * drawables and never call this.
 */
object RowCardDrawing {
    fun draw(canvas: ModernCanvas, left: Float, top: Float, right: Float, bottom: Float,
             density: Float, theme: BlissTheme, selected: Boolean) {
        if (theme == BlissTheme.AQUA) {
            val stops = if (selected) ThemeGradients.AQUA_ROW_SELECTED else ThemeGradients.AQUA_ROW
            canvas.roundCard(left, top, right, bottom, 4f * density,
                ModernFill.Linear(stops, y0 = top, y1 = bottom),
                if (selected) ThemeGradients.AQUA_ROW_SELECTED_EDGE else ThemeGradients.AQUA_ROW_EDGE,
                0.75f * density)
        } else if (theme == BlissTheme.SUPER) {
            val stops = if (selected) ThemeGradients.SUPER_ROW_SELECTED else ThemeGradients.SUPER_PANEL
            canvas.roundCard(left, top, right, bottom, 14f * density,
                ModernFill.Linear(stops, y0 = top, y1 = bottom),
                if (selected) ThemePalettes.SP_BORDER else 0x66B01EFF, 0.75f * density)
        }
    }
}

/**
 * Track/group glyphs, mirroring `ThemeGlyphDrawable` TRACK/GROUP for the
 * modern themes (CD for Aqua, hexagon for Super). Box is `size` px.
 */
object TrackGlyphDrawing {
    fun draw(canvas: ModernCanvas, x: Float, y: Float, size: Float, theme: BlissTheme, group: Boolean) {
        if (size <= 0) return
        if (theme == BlissTheme.AQUA) {
            val cx = x + size / 2f; val cy = y + size / 2f; val radius = size * 0.45f
            canvas.circle(cx, cy, radius,
                ModernFill.Linear(ThemeGlyphs.AQUA_DISC, x0 = x, y0 = y, x1 = x + size, y1 = y + size))
            for (i in 2..5) {
                canvas.circle(cx, cy, radius * i / 6f, null,
                    ModernFill.Solid(ThemeGlyphs.AQUA_RING), maxOf(1f, size * 0.015f))
            }
            canvas.circle(cx, cy, radius * 0.18f, ModernFill.Solid(ThemeGlyphs.AQUA_HUB))
            if (group) {
                canvas.roundRect(x + size * 0.14f, y + size * 0.44f, x + size * 0.85f, y + size * 0.85f,
                    3f, ModernFill.Solid(ThemeGlyphs.AQUA_GROUP_BOX))
            }
        } else if (theme == BlissTheme.SUPER) {
            val cx = x + size / 2f; val cy = y + size / 2f; val radius = size * 0.43f
            val hex = (0..5).map { i ->
                val a = i * 1.0471976f - 1.5707964f
                (cx + kotlin.math.cos(a) * radius) to (cy + kotlin.math.sin(a) * radius)
            }
            val grad = ModernFill.Linear(ThemeGlyphs.SUPER_HEX, x0 = x, y0 = y, x1 = x + size, y1 = y + size)
            canvas.poly(hex, null, grad, maxOf(1f, size * 0.035f))
            if (group) {
                canvas.roundRect(cx - size * 0.16f, cy - size * 0.16f, cx + size * 0.16f, cy + size * 0.16f,
                    2f, grad)
            } else {
                canvas.poly(listOf((x + size * 0.43f) to (y + size * 0.31f),
                    (x + size * 0.69f) to (y + size * 0.5f),
                    (x + size * 0.43f) to (y + size * 0.69f)), grad)
            }
        }
    }
}
