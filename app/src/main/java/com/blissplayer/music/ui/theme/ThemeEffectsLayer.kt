package com.blissplayer.music.ui.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos

/** Non-interactive overlay: never obscures accessibility or consumes a gesture. */
internal class ThemeEffectsLayer(context: Context, private val theme: MobileTheme) : View(context) {
    val field = MotionField()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var reduced = false
    var gentle = false
    var frames = 0L
        private set

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
    }

    fun tick(dt: Float) {
        val superMotion = theme == MobileTheme.SUPER && !reduced
        field.step(dt, width.toFloat(), height.toFloat(), resources.displayMetrics.density,
            superMotion, ambientTarget = if (superMotion) 44 else 26)
        invalidate()
    }

    /** Retro XP click palettes: classic blue/olive/harvest, or steel/amber for Dark. */
    private val blissRetro = intArrayOf(
        0xFF0058E6.toInt(), 0xFF3A93FF.toInt(), 0xFF9DBB61.toInt(),
        0xFFE8B33C.toInt(), 0xFFFFFFFF.toInt()
    )
    private val darkRetro = intArrayOf(
        0xFF7FB2E5.toInt(), 0xFF5B7A99.toInt(), 0xFFFFB84D.toInt(),
        0xFFFF7A45.toInt(), 0xFFE8E8E8.toInt()
    )

    fun tap(x: Float, y: Float) {
        val d = resources.displayMetrics.density
        // Gentle clicks: half-size, single ring, everywhere.
        val g = if (gentle) 0.5f else 1f
        when {
            theme == MobileTheme.SUPER && !reduced ->
                field.burst(x, y, d * 1.4f * g, ringCount = if (gentle) 1 else 3)
            !theme.modern ->
                // True XP: a tiny pixel pop, no expanding rings at all.
                field.burst(x, y, d * (if (reduced) .3f else .55f) * g, ringCount = 0,
                    palette = if (theme == MobileTheme.DARK) darkRetro else blissRetro,
                    lifeScale = 0.55f)
            else ->
                field.burst(x, y, d * if (reduced) .45f else 1f * g)
        }
        invalidate()
    }

    /** Animated rainbow for Super Bliss: hue drifts with time + position. Still. when reduced. */
    private fun rainbow(base: Int, x: Float, y: Float): Int {
        if (theme != MobileTheme.SUPER || reduced) return base
        val hue = ((field.elapsed * 46f + x * 0.25f + y * 0.18f + frames * 1.5f) % 360f + 360f) % 360f
        val hsv = floatArrayOf(hue, 0.85f, 1f)
        return Color.HSVToColor(Color.alpha(base), hsv)
    }

    /** Aurora blobs for Super Bliss: three big rainbow glows orbiting fast.
     * No allocations except their gradients; skipped entirely when reduced. */
    private val blobPaints = Array(3) { Paint(Paint.ANTI_ALIAS_FLAG) }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        frames++
        if (theme == MobileTheme.SUPER && !reduced && width > 0 && height > 0) {
            val w = width.toFloat()
            val h = height.toFloat()
            val t = field.elapsed
            val r = max(w, h) * 0.5f
            for (i in 0 until 3) {
                val cx = w * (0.5f + 0.44f * sin(t * 1.9f + i * 2.094f))
                val cy = h * (0.5f + 0.44f * cos(t * 1.4f + i * 1.7f + 1f))
                val hue = ((t * 95f + i * 120f) % 360f + 360f) % 360f
                val col = Color.HSVToColor(30, floatArrayOf(hue, 0.85f, 1f))
                blobPaints[i].shader = RadialGradient(cx, cy, r, col, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r, blobPaints[i])
            }
        }
        val d = resources.displayMetrics.density
        // Ambient specks are intentionally small; tap bursts are large and unmistakable.
        for (p in field.sparks) {
            if (theme == MobileTheme.AQUA) continue // Aqua uses reflected water rings, not neon confetti.
            val fraction = (p.life / p.duration).coerceIn(0f, 1f)
            if (!theme.modern) {
                // Retro XP click glint: a plus-shaped sparkle plus a pixel
                // square, fading fast. No neon glow, no ambient floaters.
                val alpha = (fraction * 255).toInt().coerceIn(0, 255)
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.color = Color.argb(alpha, Color.red(p.color), Color.green(p.color), Color.blue(p.color))
                val arm = max(2f * d, p.radius * 3.2f * fraction + 1f)
                paint.strokeWidth = max(1f, d * 0.9f)
                c.drawLine(p.x - arm, p.y, p.x + arm, p.y, paint)
                c.drawLine(p.x, p.y - arm, p.x, p.y + arm, paint)
                paint.style = Paint.Style.FILL
                val sq = max(1.5f, d * 1.4f * fraction + 0.5f)
                c.drawRect(p.x - sq, p.y - sq, p.x + sq, p.y + sq, paint)
                continue
            }
            val alpha = ((if (p.ambient) .38f + sin(fraction * 5f) * .15f else fraction) * 230).toInt().coerceIn(0, 255)
            val src = rainbow(Color.argb(alpha, Color.red(p.color), Color.green(p.color), Color.blue(p.color)), p.x, p.y)
            val color = src
            paint.shader = RadialGradient(p.x, p.y, max(1f, p.radius * if (p.ambient) 3f else 5f),
                color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(p.x, p.y, p.radius * if (p.ambient) 3f else 5f, paint)
            paint.shader = null
            paint.color = color
            if (!p.ambient) {
                paint.strokeWidth = max(.6f * d, p.radius * .45f)
                c.drawLine(p.previousX, p.previousY, p.x, p.y, paint)
            }
            c.drawCircle(p.x, p.y, max(.6f * d, p.radius * fraction), paint)
        }
        for (r in field.rings) {
            val fraction = (r.age / 1.1f).coerceIn(0f, 1f)
            // Retro rings stay tight to the fingertip; neon ones bloom wide.
            val spread = if (!theme.modern) 34f else if (reduced) 38f else 100f
            val radius = (9f + fraction * spread) * d
            val alpha = ((1f - fraction) * if (theme == MobileTheme.AQUA) 130 else 200).toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (2.5f - fraction * 2f) * d
            paint.color = if (theme == MobileTheme.AQUA) Color.argb(alpha, 238, 254, 255)
                else rainbow(Color.argb(alpha, Color.red(r.color), Color.green(r.color), Color.blue(r.color)), r.x, r.y)
            c.drawCircle(r.x, r.y, radius, paint)
            paint.strokeWidth = .7f * d
            paint.alpha = min(255, alpha / 2)
            c.drawCircle(r.x, r.y, radius * .76f, paint)
            paint.style = Paint.Style.FILL
        }
    }
}
