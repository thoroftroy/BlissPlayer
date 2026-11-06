package com.blissplayer.music.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.blissplayer.core.BlissTheme
import com.blissplayer.core.ModernCanvas
import com.blissplayer.core.ModernFill

/**
 * Android Canvas adapter; modern row cards and track glyphs are drawn by the
 * shared code in `:core` (same geometry both platforms paint).
 */
internal class AndroidModernCanvas(private val canvas: Canvas) : ModernCanvas {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    private fun shaderOf(fill: ModernFill): Shader? = when (fill) {
        is ModernFill.Solid -> null
        is ModernFill.Linear -> LinearGradient(fill.x0, fill.y0, fill.x1, fill.y1,
            fill.colors.toIntArray(),
            fill.fractions?.toFloatArray(), Shader.TileMode.CLAMP)
    }

    private fun paintOf(fill: ModernFill, style: Paint.Style, strokeWidth: Float = 0f): Paint {
        paint.style = style
        paint.strokeWidth = strokeWidth
        if (fill is ModernFill.Solid) paint.color = fill.color
        paint.shader = shaderOf(fill)
        return paint
    }

    override fun roundCard(left: Float, top: Float, right: Float, bottom: Float, radius: Float,
            fill: ModernFill, edgeColor: Int, edgeWidth: Float) {
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paintOf(fill, Paint.Style.FILL))
        if (edgeWidth > 0f) {
            canvas.drawRoundRect(left, top, right, bottom, radius, radius,
                paintOf(ModernFill.Solid(edgeColor), Paint.Style.STROKE, edgeWidth))
        }
    }

    override fun circle(cx: Float, cy: Float, r: Float, fill: ModernFill?,
            edge: ModernFill?, edgeWidth: Float) {
        if (fill != null) canvas.drawCircle(cx, cy, r, paintOf(fill, Paint.Style.FILL))
        if (edge != null && edgeWidth > 0f) canvas.drawCircle(cx, cy, r, paintOf(edge, Paint.Style.STROKE, edgeWidth))
    }

    override fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float, fill: ModernFill) {
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, radius, radius, paintOf(fill, Paint.Style.FILL))
    }

    override fun poly(points: List<Pair<Float, Float>>, fill: ModernFill?,
            edge: ModernFill?, edgeWidth: Float) {
        path.reset()
        points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        path.close()
        if (fill != null) canvas.drawPath(path, paintOf(fill, Paint.Style.FILL))
        if (edge != null && edgeWidth > 0f) canvas.drawPath(path, paintOf(edge, Paint.Style.STROKE, edgeWidth))
    }
}
