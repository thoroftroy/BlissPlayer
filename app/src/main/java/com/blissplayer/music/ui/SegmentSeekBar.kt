package com.blissplayer.music.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.blissplayer.music.model.Seg
import com.blissplayer.music.ui.theme.MobileTheme

/**
 * XP style seek bar that paints skip segments red. Touch to seek.
 * Light gray face, beveled border, segmented green progress, red skip zones.
 */
class SegmentSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var maxMs: Long = 1L
    var posMs: Long = 0L
    var segments: List<Seg> = emptyList()
    /** Id of the selected segment, drawn with a white frame. */
    var selectedId: Long? = null
    var dark: Boolean = false
    var onSeek: ((ms: Long) -> Unit)? = null
    private val theme = MobileTheme.read(context)

    /** Scrolling rainbow phase for Super; advanced by a 2fps self-loop (idle-friendly). */
    private var rainbowPhase = 0f
    private var rainbowRunning = false
    private val rainbowStep = Runnable {
        rainbowRunning = false
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return@Runnable
        if (theme == MobileTheme.SUPER && motionFull() && isAttachedToWindow) {
            rainbowPhase = (rainbowPhase + 0.4f) % 1f
            invalidate()
            rainbowLoop()
        }
    }

    private fun motionFull(): Boolean {
        val motion = context.getSharedPreferences("bliss", Context.MODE_PRIVATE)
            .getString("theme_motion", "full") ?: "full"
        return motion == "full" && android.animation.ValueAnimator.areAnimatorsEnabled()
    }

    private fun rainbowLoop() {
        if (rainbowRunning) return
        rainbowRunning = true
        postDelayed(rainbowStep, 350)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (theme == MobileTheme.SUPER) rainbowLoop()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(rainbowStep)
        rainbowRunning = false
        super.onDetachedFromWindow()
    }

    private var dragging = false
    private var dragMs: Long = 0L

    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val played = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val skip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thumbEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val selectFrame = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFFFFFFFF.toInt()
    }

    fun setPosition(pos: Long, max: Long) {
        if (!dragging) {
            posMs = pos.coerceIn(0, max.coerceAtLeast(1))
            maxMs = max.coerceAtLeast(1)
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (30 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(c: Canvas) {
        if (theme.modern) {
            drawModern(c)
            return
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 4f * resources.displayMetrics.density
        val top = pad + 6f
        val bottom = h - pad - 6f

        face.color = if (dark) 0xFF26292F.toInt() else 0xFFFFFFFF.toInt()
        border.color = if (dark) 0xFF4A525E.toInt() else 0xFF003C74.toInt()
        played.color = 0xFF3E9E4F.toInt()
        skip.color = 0xFFD32F2F.toInt()
        thumb.color = if (dark) 0xFF3A4048.toInt() else 0xFFECE9D8.toInt()
        thumbEdge.color = if (dark) 0xFF4A525E.toInt() else 0xFF003C74.toInt()

        // Track.
        c.drawRect(pad, top, w - pad, bottom, face)
        // Segmented green progress in 8dp blocks with gaps.
        val frac = (if (dragging) dragMs else posMs).toDouble() / maxMs.toDouble()
        val fillW = (w - pad * 2) * frac.coerceIn(0.0, 1.0)
        val block = 8f * resources.displayMetrics.density
        var x = pad
        while (x < pad + fillW) {
            c.drawRect(x, top + 2, minOf(x + block - 2, pad + fillW.toFloat()), bottom - 2, played)
            x += block
        }
        // Skip zones in red on top.
        segments.forEach { s ->
            val a = pad + (w - pad * 2) * (s.startMs.toDouble() / maxMs.toDouble()).coerceIn(0.0, 1.0)
            val b = pad + (w - pad * 2) * (s.endMs.toDouble() / maxMs.toDouble()).coerceIn(0.0, 1.0)
            if (b > a) {
                c.drawRect(a.toFloat(), top, b.toFloat(), bottom, skip)
                if (s.id == selectedId) c.drawRect(a.toFloat(), top, b.toFloat(), bottom, selectFrame)
            }
        }
        c.drawRect(pad, top, w - pad, bottom, border)
        // Thumb square.
        val tx = (pad + (w - pad * 2) * frac.coerceIn(0.0, 1.0)).toFloat()
        val tw = 9f * resources.displayMetrics.density
        c.drawRect(tx - tw, pad, tx + tw, h - pad, thumb)
        c.drawRect(tx - tw, pad, tx + tw, h - pad, thumbEdge)
    }

    private fun drawModern(c: Canvas) {
        val d = resources.displayMetrics.density
        val pad = 10f * d
        val cy = height / 2f
        val track = RectF(pad, cy - 3.5f * d, width - pad, cy + 3.5f * d)
        val frac = ((if (dragging) dragMs else posMs).toDouble() / maxMs.coerceAtLeast(1)).coerceIn(0.0, 1.0).toFloat()
        face.color = if (theme == MobileTheme.AQUA) 0xFF93ADBD.toInt() else 0xFF1B3147.toInt()
        c.drawRoundRect(track, 4f * d, 4f * d, face)
        val fill = RectF(track).apply { right = left + width() * frac }
        if (theme == MobileTheme.SUPER) {
            // Scrolling rainbow: bands travel the bar as it plays.
            val n = 7
            val colors = IntArray(n + 1)
            for (i in 0..n) {
                val hue = ((rainbowPhase * 360f + i * (360f / n)) % 360f + 360f) % 360f
                colors[i] = android.graphics.Color.HSVToColor(255, floatArrayOf(hue, 0.85f, 1f))
            }
            val span = track.width().coerceAtLeast(1f)
            played.shader = LinearGradient(
                track.left - span * rainbowPhase, 0f,
                track.left + span * (1.6f - rainbowPhase), 0f,
                colors, null, Shader.TileMode.MIRROR
            )
        } else {
            played.shader = LinearGradient(0f, track.top, 0f, track.bottom,
                if (theme == MobileTheme.AQUA) intArrayOf(0xFFB4EEFF.toInt(), 0xFF187DE2.toInt(), 0xFF074DA1.toInt())
                else intArrayOf(0xFFDEFFF2.toInt(), 0xFF76FFDA.toInt(), 0xFF80BBFF.toInt()), null, Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(fill, 4f * d, 4f * d, played); played.shader = null
        skip.color = if (theme == MobileTheme.AQUA) 0xFFDB5252.toInt() else 0xFFFF729D.toInt()
        segments.forEach { s ->
            val a = track.left + track.width() * (s.startMs.toDouble() / maxMs.coerceAtLeast(1)).coerceIn(0.0, 1.0).toFloat()
            val b = track.left + track.width() * (s.endMs.toDouble() / maxMs.coerceAtLeast(1)).coerceIn(0.0, 1.0).toFloat()
            if (b > a) {
                val rect = RectF(a, track.top, b, track.bottom)
                c.drawRoundRect(rect, 2f * d, 2f * d, skip)
                if (s.id == selectedId) c.drawRoundRect(rect, 2f * d, 2f * d, selectFrame)
            }
        }
        val tx = track.left + track.width() * frac
        thumb.shader = LinearGradient(0f, cy - 9f * d, 0f, cy + 9f * d,
            if (theme == MobileTheme.AQUA) intArrayOf(0xFFFFFFFF.toInt(), 0xFFA9CBDD.toInt(), 0xFFF8FDFF.toInt())
            else intArrayOf(0xFFFFFFFF.toInt(), 0xFF9CFFE4.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(tx, cy, 9f * d, thumb); thumb.shader = null
        thumbEdge.color = if (theme == MobileTheme.AQUA) 0xFF648EAF.toInt() else 0xFF6AB8C9.toInt()
        thumbEdge.strokeWidth = d
        c.drawCircle(tx, cy, 9f * d, thumbEdge)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                dragMs = xToMs(e.x)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                dragMs = xToMs(e.x)
                onSeek?.invoke(dragMs)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onSeek?.invoke(xToMs(e.x))
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun xToMs(x: Float): Long {
        val pad = 4f * resources.displayMetrics.density
        val frac = ((x - pad) / (width - pad * 2)).coerceIn(0f, 1f)
        return (frac * maxMs).toLong()
    }
}
