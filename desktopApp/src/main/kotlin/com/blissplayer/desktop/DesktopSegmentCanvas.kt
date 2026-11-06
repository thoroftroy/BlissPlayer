package com.blissplayer.desktop

import com.blissplayer.core.SegmentCanvas
import com.blissplayer.core.SegmentGradient
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.MultipleGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D

/** Java2D adapter for the same drawing commands used by Android. */
internal class DesktopSegmentCanvas(private val graphics: Graphics2D) : SegmentCanvas {
    init { graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON) }

    private fun draw(shape: java.awt.Shape, color: Int, stroke: Float, gradient: SegmentGradient?) {
        graphics.paint = if (gradient == null) Color(color, true) else LinearGradientPaint(
            gradient.x0, gradient.y0, gradient.x1, gradient.y1,
            gradient.colors.indices.map { it.toFloat() / (gradient.colors.size - 1) }.toFloatArray(),
            gradient.colors.map { Color(it, true) }.toTypedArray(),
            if (gradient.mirror) MultipleGradientPaint.CycleMethod.REFLECT else MultipleGradientPaint.CycleMethod.NO_CYCLE
        )
        if (stroke > 0f) { graphics.stroke = BasicStroke(stroke); graphics.draw(shape) } else graphics.fill(shape)
    }

    override fun rect(left: Float, top: Float, right: Float, bottom: Float, color: Int, stroke: Float) =
        draw(Rectangle2D.Float(left, top, right - left, bottom - top), color, stroke, null)

    override fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float, color: Int, stroke: Float, gradient: SegmentGradient?) =
        draw(RoundRectangle2D.Float(left, top, right - left, bottom - top, radius * 2, radius * 2), color, stroke, gradient)

    override fun circle(x: Float, y: Float, radius: Float, color: Int, stroke: Float, gradient: SegmentGradient?) =
        draw(Ellipse2D.Float(x - radius, y - radius, radius * 2, radius * 2), color, stroke, gradient)
}
