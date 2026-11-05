package com.blissplayer.music.ui.theme

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock

/**
 * Super Bliss progress fill: a rainbow that scrolls through the bar instead
 * of sitting blue and still. Static single-frame when
 * motion is reduced. The bar container clips it (ClipDrawable), so this
 * just paints its bounds edge to edge with soft rounded ends.
 */
class RainbowBarDrawable(
    private val density: Float,
    private val animated: Boolean
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var gradient: LinearGradient? = null
    private var scheduled = false
    private val stepper = Runnable {
        scheduled = false
        if (!animated || callback == null) return@Runnable
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return@Runnable
        invalidateSelf()
    }

    private fun kick() {
        if (scheduled || !animated || !isVisible || callback == null ||
            !android.animation.ValueAnimator.areAnimatorsEnabled()) return
        scheduled = true
        scheduleSelf(stepper, SystemClock.uptimeMillis() + 33)
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        val colors = IntArray(8) { i -> Color.HSVToColor(255, floatArrayOf(i * (360f / 7f) % 360f, 0.85f, 1f)) }
        gradient = if (bounds.isEmpty) null else LinearGradient(
            0f, 0f, bounds.width().toFloat().coerceAtLeast(1f) * 1.6f, 0f,
            colors, null, Shader.TileMode.REPEAT
        )
        if (animated) kick()
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) {
            unscheduleSelf(stepper)
            scheduled = false
        } else if (animated) kick()
        return changed
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat().coerceAtLeast(1f)
        val h = b.height().toFloat().coerceAtLeast(1f)
        val off = if (animated && android.animation.ValueAnimator.areAnimatorsEnabled())
            (SystemClock.uptimeMillis() % 5000L) / 5000f else 0.15f
        matrix.setTranslate(b.left - w * 1.6f * off, 0f)
        gradient?.setLocalMatrix(matrix)
        paint.shader = gradient
        val r = (h / 2f).coerceAtMost(8f * density)
        canvas.drawRoundRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), r, r, paint)
        paint.shader = null
        if (animated) kick()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
