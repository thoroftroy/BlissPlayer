package com.blissplayer.music.ui

import android.content.Context
import android.graphics.Canvas
import com.blissplayer.core.SegmentBarDrawing
import com.blissplayer.core.SegmentRange
import com.blissplayer.core.SegmentTheme
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
        SegmentBarDrawing.draw(
            AndroidSegmentCanvas(c), width.toFloat(), height.toFloat(), resources.displayMetrics.density,
            when (theme) {
                MobileTheme.BLISS -> if (dark) SegmentTheme.DARK else SegmentTheme.BLISS
                MobileTheme.DARK -> if (dark) SegmentTheme.DARK else SegmentTheme.BLISS
                MobileTheme.AQUA -> SegmentTheme.AQUA
                MobileTheme.SUPER -> SegmentTheme.SUPER
            },
            if (dragging) dragMs else posMs, maxMs,
            segments.map { SegmentRange(it.startMs, it.endMs, it.id == selectedId) }, rainbowPhase
        )
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
        return SegmentBarDrawing.positionForX(x, width.toFloat(), resources.displayMetrics.density, maxMs)
    }
}
