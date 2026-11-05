package com.blissplayer.music.ui.theme

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Theme-specific artwork, not the XP file/folder icons recolored. */
class ThemeGlyphDrawable(
    private val theme: MobileTheme,
    private val kind: Kind = Kind.TRACK
) : Drawable() {
    enum class Kind { TRACK, GROUP, BRAND }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    override fun draw(c: Canvas) {
        val save = c.save()
        c.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        if (w <= 0 || h <= 0) { c.restoreToCount(save); return }
        if (theme == MobileTheme.AQUA && kind == Kind.BRAND) {
            // The Snow Leopard chrome motif is decorative, not fake window controls.
            val radius = minOf(w / 7.5f, h / 2.5f)
            intArrayOf(0xFFEE665A.toInt(), 0xFFFFCE50.toInt(), 0xFF73C857.toInt()).forEachIndexed { i, color ->
                val x = w * ((i + .7f) / 3.4f)
                paint.shader = RadialGradient(x - radius * .3f, h * .5f - radius * .4f, radius * 1.7f,
                    intArrayOf(Color.WHITE, color, darken(color)), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(x, h * .5f, radius, paint)
            }
            paint.shader = null
        } else if (theme == MobileTheme.AQUA) {
            val cx = w * .5f; val cy = h * .5f; val radius = minOf(w, h) * .45f
            paint.shader = LinearGradient(0f, 0f, w, h,
                intArrayOf(0xFFF9FFFF.toInt(), 0xFF6DB3E4.toInt(), 0xFFE4F6FF.toInt(), 0xFF387ABA.toInt()),
                null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, radius, paint); paint.shader = null
            paint.style = Paint.Style.STROKE; paint.strokeWidth = maxOf(1f, w * .015f)
            paint.color = 0x7799D6F3
            for (i in 2..5) c.drawCircle(cx, cy, radius * i / 6f, paint)
            paint.style = Paint.Style.FILL
            paint.color = 0xDDFFFFFF.toInt(); c.drawCircle(cx, cy, radius * .18f, paint)
            if (kind == Kind.GROUP) {
                rect.set(w * .14f, h * .44f, w * .85f, h * .85f)
                paint.color = 0xBD277DCA.toInt(); c.drawRoundRect(rect, 3f, 3f, paint)
            }
        } else {
            val cx = w * .5f; val cy = h * .5f
            paint.shader = LinearGradient(0f, 0f, w, h,
                intArrayOf(0xFF9BFFE5.toInt(), 0xFF86B3FF.toInt(), 0xFFFFB0D0.toInt()), null, Shader.TileMode.CLAMP)
            path.reset()
            val radius = minOf(w, h) * .43f
            for (i in 0..5) {
                val a = i * 1.0471976f - 1.5707964f
                val x = cx + cos(a) * radius; val y = cy + sin(a) * radius
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = maxOf(1f, w * .035f)
            c.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            if (kind == Kind.GROUP) {
                rect.set(cx - w * .16f, cy - h * .16f, cx + w * .16f, cy + h * .16f)
                c.drawRoundRect(rect, 2f, 2f, paint)
            } else {
                path.reset(); path.moveTo(w * .43f, h * .31f)
                path.lineTo(w * .69f, h * .5f); path.lineTo(w * .43f, h * .69f); path.close()
                c.drawPath(path, paint)
            }
            paint.shader = null
        }
        c.restoreToCount(save)
    }

    private fun darken(c: Int) = Color.rgb((Color.red(c) * .65f).toInt(),
        (Color.green(c) * .65f).toInt(), (Color.blue(c) * .65f).toInt())
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
