package com.blissplayer.music.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.blissplayer.music.model.Seg

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
    var dark: Boolean = false
    var onSeek: ((ms: Long) -> Unit)? = null

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

    fun setPosition(pos: Long, max: Long) {
        if (!dragging) {
            posMs = pos.coerceIn(0, max.coerceAtLeast(1))
            maxMs = max.coerceAtLeast(1)
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (30 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onDraw(c: Canvas) {
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
            if (b > a) c.drawRect(a.toFloat(), top, b.toFloat(), bottom, skip)
        }
        c.drawRect(pad, top, w - pad, bottom, border)
        // Thumb square.
        val tx = (pad + (w - pad * 2) * frac.coerceIn(0.0, 1.0)).toFloat()
        val tw = 9f * resources.displayMetrics.density
        c.drawRect(tx - tw, pad, tx + tw, h - pad, thumb)
        c.drawRect(tx - tw, pad, tx + tw, h - pad, thumbEdge)
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
