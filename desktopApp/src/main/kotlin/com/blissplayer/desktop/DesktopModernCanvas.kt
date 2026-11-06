package com.blissplayer.desktop

import com.blissplayer.core.ModernCanvas
import com.blissplayer.core.ModernFill
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.geom.Path2D

/** Java2D adapter for the shared modern row art both platforms paint. */
internal class DesktopModernCanvas(private val g: Graphics2D) : ModernCanvas {
    init { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON) }

    private fun paintOf(fill: ModernFill): java.awt.Paint = when (fill) {
        is ModernFill.Solid -> Color(fill.color, true)
        is ModernFill.Linear -> {
            val n = fill.colors.size
            val fractions = fill.fractions
                ?: (0 until n).map { it.toFloat() / (n - 1).coerceAtLeast(1) }
            LinearGradientPaint(fill.x0, fill.y0, fill.x1, fill.y1,
                fractions.toFloatArray(),
                fill.colors.map { Color(it, true) }.toTypedArray())
        }
    }

    override fun roundCard(left: Float, top: Float, right: Float, bottom: Float, radius: Float,
            fill: ModernFill, edgeColor: Int, edgeWidth: Float) {
        val shape = RoundRectangle2D.Float(left, top, right - left, bottom - top, radius * 2, radius * 2)
        g.paint = paintOf(fill)
        g.fill(shape)
        if (edgeWidth > 0f) {
            g.paint = Color(edgeColor, true)
            g.stroke = BasicStroke(edgeWidth)
            g.draw(shape)
        }
    }

    override fun circle(cx: Float, cy: Float, r: Float, fill: ModernFill?,
            edge: ModernFill?, edgeWidth: Float) {
        val shape = Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2)
        if (fill != null) { g.paint = paintOf(fill); g.fill(shape) }
        if (edge != null && edgeWidth > 0f) {
            g.paint = paintOf(edge); g.stroke = BasicStroke(edgeWidth); g.draw(shape)
        }
    }

    override fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float, fill: ModernFill) {
        g.paint = paintOf(fill)
        g.fill(RoundRectangle2D.Float(left, top, right - left, bottom - top, radius * 2, radius * 2))
    }

    override fun poly(points: List<Pair<Float, Float>>, fill: ModernFill?,
            edge: ModernFill?, edgeWidth: Float) {
        val path = Path2D.Float()
        points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        path.closePath()
        if (fill != null) { g.paint = paintOf(fill); g.fill(path) }
        if (edge != null && edgeWidth > 0f) {
            g.paint = paintOf(edge); g.stroke = BasicStroke(edgeWidth); g.draw(path)
        }
    }
}
