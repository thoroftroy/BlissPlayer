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
import com.blissplayer.core.BlissTheme
import com.blissplayer.core.TrackGlyphDrawing
import com.blissplayer.music.ui.AndroidModernCanvas
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
        } else if (kind != Kind.BRAND) {
            // Track/group glyphs are shared with Linux (same geometry, same stops).
            val size = minOf(w, h)
            TrackGlyphDrawing.draw(com.blissplayer.music.ui.AndroidModernCanvas(c),
                (w - size) / 2f, (h - size) / 2f, size, theme.bliss, kind == Kind.GROUP)
        }
        c.restoreToCount(save)
    }

    private fun darken(c: Int) = Color.rgb((Color.red(c) * .65f).toInt(),
        (Color.green(c) * .65f).toInt(), (Color.blue(c) * .65f).toInt())
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
