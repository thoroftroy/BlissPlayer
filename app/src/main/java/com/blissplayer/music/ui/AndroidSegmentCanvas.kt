package com.blissplayer.music.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.blissplayer.core.SegmentCanvas
import com.blissplayer.core.SegmentGradient

/** Android Canvas adapter; the seek bar's actual drawing is shared in core. */
internal class AndroidSegmentCanvas(private val canvas: Canvas) : SegmentCanvas {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun prepare(color: Int, stroke: Float, gradient: SegmentGradient?) {
        paint.color = if (gradient == null) color else android.graphics.Color.WHITE
        paint.style = if (stroke > 0f) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = stroke
        paint.shader = gradient?.let {
            LinearGradient(it.x0, it.y0, it.x1, it.y1, it.colors.toIntArray(), null,
                if (it.mirror) Shader.TileMode.MIRROR else Shader.TileMode.CLAMP)
        }
    }

    override fun rect(left: Float, top: Float, right: Float, bottom: Float, color: Int, stroke: Float) {
        prepare(color, stroke, null)
        canvas.drawRect(left, top, right, bottom, paint)
    }

    override fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float, color: Int, stroke: Float, gradient: SegmentGradient?) {
        prepare(color, stroke, gradient)
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
    }

    override fun circle(x: Float, y: Float, radius: Float, color: Int, stroke: Float, gradient: SegmentGradient?) {
        prepare(color, stroke, gradient)
        canvas.drawCircle(x, y, radius, paint)
    }
}
