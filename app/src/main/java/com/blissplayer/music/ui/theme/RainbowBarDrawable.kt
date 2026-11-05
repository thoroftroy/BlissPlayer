package com.blissplayer.music.ui.theme

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock

/**
 * Super Bliss progress fill: a rainbow that scrolls through the bar instead
 * of sitting blue and still. Self-driven ~12fps; static single-frame when
 * motion is reduced. The bar container clips it (ClipDrawable), so this
 * just paints its bounds edge to edge with soft rounded ends.
 */
class RainbowBarDrawable(
    private val density: Float,
    private val animated: Boolean
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var scheduled = false
    private val stepper = Runnable {
        scheduled = false
        if (!animated || callback == null) return@Runnable
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return@Runnable
        phase = (phase + 0.15f) % 1f
        invalidateSelf()
    }

    private fun kick() {
        if (scheduled || !animated || callback == null) return
        scheduled = true
        scheduleSelf(stepper, SystemClock.uptimeMillis() + 500)
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        if (animated) kick()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat().coerceAtLeast(1f)
        val h = b.height().toFloat().coerceAtLeast(1f)
        // Seven rainbow stops spun by phase: bands visibly travel the bar.
        val n = 7
        val colors = IntArray(n + 1)
        val off = if (animated) phase else 0.15f
        for (i in 0..n) {
            val hue = ((off * 360f + i * (360f / n)) % 360f + 360f) % 360f
            colors[i] = Color.HSVToColor(255, floatArrayOf(hue, 0.85f, 1f))
        }
        // Span wider than the bar so the scroll never shows a seam.
        paint.shader = LinearGradient(
            b.left - w * off, 0f, b.left + w * (1.6f - off), 0f,
            colors, null, Shader.TileMode.MIRROR
        )
        val r = (h / 2f).coerceAtMost(8f * density)
        canvas.drawRoundRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), r, r, paint)
        paint.shader = null
        if (animated) kick()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
