package com.blissplayer.music.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import com.blissplayer.music.R

/**
 * The user's filled star with a scrolling rainbow shader: bands of color
 * sweep through the star on a self-driven 2fps heartbeat (idle-friendly). Stops itself when
 * nothing observes it anymore. Reduced-motion callers must use static mode.
 */
class FavStarDrawable(
    private val star: Bitmap,
    private val animated: Boolean,
    /** Fixed hue shift for static themed stars (Aqua blue); 0 = untouched. */
    private val staticHue: Float = 0f
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** Black silhouette paint: loved stars get a dark halo so they read on any background. */
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.LightingColorFilter(0xFF000000.toInt(), 0)
    }
    private val staticFilter: ColorFilter? =
        if (!animated && staticHue != 0f) ColorMatrixColorFilter(hueMatrix(staticHue)) else null
    private var scratch: Bitmap? = null
    private var phase = 0f
    private val haloBounds = android.graphics.Rect()
    private var scheduled = false
    private val stepper = Runnable {
        scheduled = false
        if (!animated || callback == null) return@Runnable
        // System animators off means stop everything, like the layer clock.
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return@Runnable
        phase = (phase + 0.4f) % 1f
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (animated) kick()
    }

    private fun kick() {
        if (scheduled || !animated || callback == null) return
        scheduled = true
        scheduleSelf(stepper, SystemClock.uptimeMillis() + 350)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        // Black halo behind the star: selected stars never blend in.
        val pad = (minOf(b.width(), b.height()) * 0.07f).coerceAtLeast(1f)
        haloBounds.set(
            (b.left - pad).toInt(), (b.top - pad).toInt(),
            (b.right + pad).toInt(), (b.bottom + pad).toInt()
        )
        canvas.drawBitmap(star, null, haloBounds, haloPaint)
        if (animated) {
            drawScrollingRainbow(canvas, b)
            kick()
            return
        }
        paint.colorFilter = staticFilter
        paint.xfermode = null
        paint.shader = null
        canvas.drawBitmap(star, null, b, paint)
    }

    /**
     * Scrolling rainbow INSIDE the star: a diagonal rainbow gradient sweeps
     * through the star mask, so bands of color visibly travel across it.
     */
    private fun drawScrollingRainbow(canvas: Canvas, b: android.graphics.Rect) {
        val w = star.width
        val h = star.height
        var s = scratch
        if (s == null || s.width != w || s.height != h) {
            runCatching { s?.recycle() }
            s = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            scratch = s
        }
        val sc = android.graphics.Canvas(s!!)
        // Rainbow stops, spun by phase so the bands scroll diagonally.
        val n = 7
        val colors = IntArray(n + 1)
        for (i in 0..n) {
            val hue = ((phase * 360f + i * (360f / n)) % 360f + 360f) % 360f
            colors[i] = android.graphics.Color.HSVToColor(255, floatArrayOf(hue, 0.9f, 1f))
        }
        paint.xfermode = null
        paint.colorFilter = null
        paint.shader = android.graphics.LinearGradient(
            0f, h.toFloat(), w.toFloat(), 0f, colors, null,
            android.graphics.Shader.TileMode.MIRROR
        )
        sc.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        // Keep the gradient only where the star is opaque.
        paint.shader = null
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        sc.drawBitmap(star, 0f, 0f, paint)
        paint.xfermode = null
        canvas.drawBitmap(s!!, null, b, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        @Volatile private var cached: Bitmap? = null
        @Volatile private var blued: Bitmap? = null

        /** Hue rotation matrix shared by the animated and blue static stars. */
        fun hueMatrix(degrees: Float): ColorMatrix {
            val m = ColorMatrix()
            val cosA = Math.cos(Math.toRadians(degrees.toDouble())).toFloat()
            val sinA = Math.sin(Math.toRadians(degrees.toDouble())).toFloat()
            val lumR = 0.213f; val lumG = 0.715f; val lumB = 0.072f
            m.set(floatArrayOf(
                lumR + cosA * (1 - lumR) + sinA * (-lumR), lumG + cosA * (-lumG) + sinA * (-lumG), lumB + cosA * (-lumB) + sinA * (1 - lumB), 0f, 0f,
                lumR + cosA * (-lumR) + sinA * (0.143f), lumG + cosA * (1 - lumG) + sinA * (0.140f), lumB + cosA * (-lumB) + sinA * (-0.283f), 0f, 0f,
                lumR + cosA * (-lumR) + sinA * (-(1 - lumR)), lumG + cosA * (-lumG) + sinA * (lumG), lumB + cosA * (1 - lumB) + sinA * (lumB), 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            return m
        }

        /** Shared star bitmap; each animated row still gets its own Drawable. */
        fun bitmapOf(ctx: android.content.Context): Bitmap? {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val opts = BitmapFactory.Options().apply { inScaled = false }
                val raw = BitmapFactory.decodeResource(ctx.resources, R.drawable.fav_star_filled, opts)
                    ?: return null
                // Downscale the 2000px art once; rows draw it at ~40dp.
                val scale = 192f / maxOf(raw.width, raw.height).toFloat()
                val w = (raw.width * scale).toInt().coerceAtLeast(1)
                val h = (raw.height * scale).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(raw, w, h, true)
                if (small != raw) runCatching { raw.recycle() }
                cached = small
                return small
            }
        }

        /** Static star (callers tint), or null when the art is missing. */
        fun staticOf(ctx: android.content.Context): Drawable? {
            val b = bitmapOf(ctx.applicationContext) ?: return null
            return FavStarDrawable(b, false)
        }

        /** Animated rainbow star, or a static fallback when art/motion is off. */
        fun rainbowOf(ctx: android.content.Context, animate: Boolean): Drawable? {
            val b = bitmapOf(ctx.applicationContext)
                ?: return ctx.getDrawable(R.drawable.fav_star_filled)
            return FavStarDrawable(b, animate)
        }

        /**
         * Aqua star: the textured art hue-shifted to Bliss blue. Facets stay,
         * only the hue moves. Cached; one bitmap per process.
         */
        fun bluedOf(ctx: android.content.Context): Drawable? {
            blued?.let { return FavStarDrawable(it, false) }
            synchronized(this) {
                blued?.let { return FavStarDrawable(it, false) }
                val src = bitmapOf(ctx.applicationContext)
                    ?: return ctx.getDrawable(R.drawable.fav_star_filled)
                val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                p.colorFilter = ColorMatrixColorFilter(hueMatrix(160f))
                c.drawBitmap(src, 0f, 0f, p)
                blued = out
                return FavStarDrawable(out, false)
            }
        }
    }
}
