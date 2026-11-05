package com.blissplayer.music.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.os.Build
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Real layered materials, not color filters over XP bitmaps. Aqua uses brushed
 * aluminum, deep gel, a hard specular horizon and translucent water-lit glass.
 * Super uses animated ink/glass fills, roaming light and iridescent edge shaders.
 * A single activity clock updates visible drawables; none owns an animator.
 */
class MaterialDrawable(
    val theme: MobileTheme,
    val role: MaterialRole,
    private val density: Float,
    var accent: Int = theme.accent
) : Drawable() {
    var phase = 0f
    var motion = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val area = RectF()
    private val clip = Path()
    private val path = Path()
    private val matrix = Matrix()
    private var opacity = 255
    private var pressed = false
    private var selected = false
    private val plasma: Shader? = if (role == MaterialRole.BACKDROP && theme == MobileTheme.SUPER &&
        Build.VERSION.SDK_INT >= 33) runCatching { ThemeSceneShader.create() }.getOrNull() else null

    val animated: Boolean get() = theme.modern && role !in setOf(MaterialRole.FIELD, MaterialRole.COLUMN)

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val layer = if (opacity < 255) canvas.saveLayerAlpha(0f, 0f, w, h, opacity) else -1
        area.set(0.6f * density, 0.6f * density, w - 0.6f * density, h - 0.6f * density)
        paint.reset(); paint.isAntiAlias = true
        if (role == MaterialRole.BACKDROP) {
            if (theme == MobileTheme.AQUA) aquaWater(canvas, w, h) else superSpace(canvas, w, h)
        } else {
            if (theme == MobileTheme.AQUA) aqua(canvas, w, h) else superMaterial(canvas, w, h)
        }
        if (layer >= 0) canvas.restoreToCount(layer)
        canvas.restoreToCount(saved)
    }

    private fun rounded(canvas: Canvas, radius: Float, colors: IntArray, stops: FloatArray? = null) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 0f, max(1f, area.bottom), colors, stops, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(area, radius, radius, paint)
        paint.shader = null
    }

    private fun edge(canvas: Canvas, radius: Float, color: Int, width: Float = 0.75f) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width * density
        paint.color = color
        canvas.drawRoundRect(area, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    private fun aqua(canvas: Canvas, w: Float, h: Float) {
        val r = when (role) {
            MaterialRole.TOOLBAR -> 10f
            MaterialRole.TAB -> 7f
            MaterialRole.ROW, MaterialRole.COLUMN -> 4f
            MaterialRole.PRIMARY, MaterialRole.SECONDARY, MaterialRole.CONTROL -> 18f
            MaterialRole.ICON -> 30f
            MaterialRole.STATUS -> 8f
            else -> 14f
        } * density
        val gel = role == MaterialRole.PRIMARY || role == MaterialRole.TAB && selected
        val silver = role in setOf(MaterialRole.TOOLBAR, MaterialRole.CONTROL, MaterialRole.SECONDARY,
            MaterialRole.COLUMN, MaterialRole.ICON) || role == MaterialRole.TAB && !selected
        when {
            gel -> {
                val c = if (pressed) 0xFF074B9D.toInt() else 0xFF087BE4.toInt()
                rounded(canvas, r, intArrayOf(0xFFE2F6FF.toInt(), 0xFF68BDFF.toInt(), c,
                    0xFF07449E.toInt(), 0xFF45B6FF.toInt()), floatArrayOf(0f, .22f, .49f, .53f, 1f))
                edge(canvas, r, 0xFF134A88.toInt())
                // The characteristic Aqua lens, sharply reflected at the equator.
                val old = RectF(area)
                area.inset(1.5f * density, 1.5f * density)
                area.bottom = min(area.bottom, h * 0.45f)
                rounded(canvas, r, intArrayOf(0xCCFFFFFF.toInt(), 0x22FFFFFF))
                area.set(old)
            }
            silver -> {
                rounded(canvas, r, if (pressed) intArrayOf(0xFF8E9EAE.toInt(), 0xFFD5E0E7.toInt())
                    else intArrayOf(0xFFFBFDFF.toInt(), 0xFFDCE3E9.toInt(), 0xFFB3BFCB.toInt(), 0xFFEEF3F7.toInt()),
                    if (pressed) null else floatArrayOf(0f, .48f, .52f, 1f))
                clip.reset(); clip.addRoundRect(area, r, r, Path.Direction.CW)
                val save = canvas.save(); canvas.clipPath(clip)
                paint.shader = BitmapShader(metalGrain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                canvas.drawRect(area, paint); paint.shader = null
                canvas.restoreToCount(save)
                edge(canvas, r, 0xFF8093A6.toInt())
                paint.color = 0xCCFFFFFF.toInt()
                canvas.drawLine(r * .7f, 1.5f * density, w - r * .7f, 1.5f * density, paint)
            }
            role == MaterialRole.DISPLAY -> {
                rounded(canvas, r, intArrayOf(0xFFCEE7EA.toInt(), 0xFFF2FBF5.toInt(), 0xFFB2CED0.toInt()))
                edge(canvas, r, 0xFF688E9B.toInt())
                // Reflected glass, not the XP green monochrome display.
                paint.shader = LinearGradient(0f, 0f, w, h,
                    intArrayOf(0x66FFFFFF, 0x00FFFFFF, 0x44FFFFFF), null, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(area, r, r, paint); paint.shader = null
            }
            role == MaterialRole.ROW -> {
                rounded(canvas, r, if (selected) intArrayOf(0xFFD2EEFF.toInt(), 0xFF95CFFF.toInt())
                    else intArrayOf(0xEAFAFDFF.toInt(), 0xD4E3EFF6.toInt()))
                edge(canvas, r, if (selected) 0xFF4A9AE1.toInt() else 0x66FFFFFF)
            }
            role == MaterialRole.FIELD -> {
                rounded(canvas, r, intArrayOf(0xE3D5E4EF.toInt(), 0xEEFFFFFF.toInt()))
                edge(canvas, r, 0xFF8CA5B9.toInt())
            }
            else -> {
                // Actual translucent surfaces: water and caustics show through.
                rounded(canvas, r, when (role) {
                    MaterialRole.SECTION -> intArrayOf(0xF3FFFFFF.toInt(), 0xDAE0EBF4.toInt())
                    MaterialRole.DIALOG -> intArrayOf(0xF5F8FCFF.toInt(), 0xEDC8DCEB.toInt())
                    else -> intArrayOf(0xDCEBF6FF.toInt(), 0xABC5DEEC.toInt())
                })
                edge(canvas, r, 0xDDF8FFFF.toInt())
                val save = canvas.save()
                clip.reset(); clip.addRoundRect(area, r, r, Path.Direction.CW); canvas.clipPath(clip)
                paint.shader = LinearGradient(0f, h * .2f, 0f, h * .8f,
                    intArrayOf(0x66FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                path.reset(); path.moveTo(0f, 0f); path.lineTo(w, 0f)
                path.lineTo(w, h * .18f); path.cubicTo(w * .7f, h * .7f, w * .3f, h * .2f, 0f, h * .52f)
                path.close(); canvas.drawPath(path, paint); paint.shader = null
                if (role == MaterialRole.PANEL || role == MaterialRole.STATUS) caustics(canvas, w, h, .10f)
                canvas.restoreToCount(save)
                if (role == MaterialRole.SECTION) {
                    paint.color = accent
                    area.set(1f * density, h * .24f, 4f * density, h * .76f)
                    canvas.drawRoundRect(area, 2f * density, 2f * density, paint)
                }
            }
        }
    }

    private fun superMaterial(canvas: Canvas, w: Float, h: Float) {
        val r = when (role) {
            MaterialRole.CONTROL, MaterialRole.PRIMARY, MaterialRole.SECONDARY -> 21f
            MaterialRole.TAB -> 14f
            MaterialRole.ICON -> 30f
            MaterialRole.ROW -> 14f
            else -> 19f
        } * density
        val t = if (motion) phase else 0f
        val primary = role == MaterialRole.PRIMARY
        val alt = role == MaterialRole.SECONDARY || role == MaterialRole.TAB && selected
        val fills = when {
            primary -> intArrayOf(0xFFFFD07D.toInt(), 0xFFFF855E.toInt(), 0xFFF65C9C.toInt())
            alt -> intArrayOf(0xFFB2FFF1.toInt(), 0xFF63EFD9.toInt(), 0xFF65B8ED.toInt())
            role == MaterialRole.TOOLBAR -> intArrayOf(0xD51A3456.toInt(), 0xE92A2546.toInt(), 0xB51B4A55.toInt())
            role == MaterialRole.DIALOG -> intArrayOf(0xF418283B.toInt(), 0xF4131A2C.toInt())
            role == MaterialRole.DISPLAY -> intArrayOf(0xD721354E.toInt(), 0xB5192A38.toInt())
            role == MaterialRole.ROW && selected -> intArrayOf(0xDC294956.toInt(), 0xCC373152.toInt())
            role == MaterialRole.FIELD -> intArrayOf(0xEF0B192A.toInt(), 0xDD1B2739.toInt())
            else -> intArrayOf(0xC125344D.toInt(), 0xA619233B.toInt(), 0xB3223043.toInt())
        }
        rounded(canvas, r, fills)
        val save = canvas.save()
        clip.reset(); clip.addRoundRect(area, r, r, Path.Direction.CW); canvas.clipPath(clip)
        // Light roaming INSIDE every surface, not just an outline.
        val sheenX = w * (0.5f + 0.65f * sin(t * .42f + h * .001f))
        paint.shader = RadialGradient(sheenX, h * -.12f, max(w * .7f, h),
            if (primary || alt) 0x66FFFFFF else 0x395BA6E5, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(area, paint); paint.shader = null
        if (pressed) { paint.color = 0x55FFFFFF; canvas.drawRect(area, paint) }
        canvas.restoreToCount(save)
        // A slowly orbiting spectral edge. No high-contrast flashing.
        paint.shader = SweepGradient(w * .5f, h * .5f,
            intArrayOf(0x884AACDD.toInt(), 0xD976FFE0.toInt(), 0xBCFD93C0.toInt(), 0x776C8BCA,
                0xE6F7CC8D.toInt(), 0x884AACDD.toInt()), null)
        matrix.setRotate(t * 14f, w * .5f, h * .5f)
        paint.shader?.setLocalMatrix(matrix)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (if (selected || pressed) 1.6f else .8f) * density
        canvas.drawRoundRect(area, r, r, paint)
        paint.shader = null; paint.style = Paint.Style.FILL
        if (role == MaterialRole.SECTION) {
            paint.color = accent
            canvas.drawCircle(w - 19 * density, h * .5f, 2.4f * density, paint)
        }
    }

    private fun aquaWater(canvas: Canvas, w: Float, h: Float) {
        paint.shader = LinearGradient(0f, 0f, w * .65f, h,
            intArrayOf(0xFF122F5A.toInt(), 0xFF126FAD.toInt(), 0xFF55BBCB.toInt(), 0xFF16446D.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint); paint.shader = null
        val t = if (motion) phase else 0f
        // Long translucent Aqua ribbons, blue water wallpaper around glass panes.
        repeat(5) { i ->
            val offset = sin(t * .12f + i) * w * .13f
            paint.shader = LinearGradient(w * .1f, 0f, w, h,
                intArrayOf(0x03FFFFFF, 0x5579E4FF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            path.reset()
            path.moveTo(-w * .4f + offset, h * (.2f + i * .11f))
            path.cubicTo(w * .65f, -h * .12f, w * .1f, h * .9f, w * 1.3f, h * (.4f + i * .15f))
            path.lineTo(w * 1.3f, h * (.43f + i * .15f))
            path.cubicTo(w * .1f, h * .95f, w * .65f, -h * .06f, -w * .4f + offset, h * (.25f + i * .11f))
            path.close(); canvas.drawPath(path, paint)
        }
        paint.shader = null
        caustics(canvas, w, h, .22f)
    }

    private fun caustics(canvas: Canvas, w: Float, h: Float, strength: Float) {
        val t = if (motion) phase else 0f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = .8f * density
        paint.color = Color.argb((strength * 255).toInt(), 227, 252, 255)
        repeat(5) { n ->
            path.reset()
            val y = h * ((n + 1) / 6f)
            path.moveTo(0f, y)
            path.cubicTo(w * .28f, y + sin(t * .3f + n) * h * .12f,
                w * .6f, y - cos(t * .22f + n) * h * .16f, w, y + sin(t * .2f) * h * .05f)
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun superSpace(canvas: Canvas, w: Float, h: Float) {
        if (plasma != null && Build.VERSION.SDK_INT >= 33) {
            (plasma as RuntimeShader).setFloatUniform("resolution", w, h)
            plasma.setFloatUniform("time", if (motion) phase else 0f)
            paint.shader = plasma
            canvas.drawRect(0f, 0f, w, h, paint); paint.shader = null
        } else {
            canvas.drawColor(0xFF080E1C.toInt())
            val t = if (motion) phase else 0f
            val orbs = arrayOf(Triple(.1f, .1f, 0x903D527F.toInt()), Triple(.9f, .7f, 0x80256661.toInt()),
                Triple(.7f, .1f, 0x704E294D))
            orbs.forEachIndexed { i, o ->
                paint.shader = RadialGradient(w * o.first + sin(t * .13f + i) * w * .12f,
                    h * o.second + cos(t * .12f + i) * h * .10f, max(w, h) * .7f,
                    o.third, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, paint)
            }
            paint.shader = null
        }
        val t = if (motion) phase else 0f
        // Fine orbital arcs make the dark canvas visibly dimensional.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = .5f * density
        repeat(7) { i ->
            paint.color = if (i % 2 == 0) 0x224DE8DA else 0x22C2AAFF
            val cy = h * .45f + sin(t * .16f + i * .3f) * h * .05f
            val rr = w * (.8f + i * .23f)
            area.set(w * .55f - rr, cy - rr * .65f, w * .55f + rr, cy + rr * .65f)
            canvas.drawOval(area, paint)
        }
        paint.style = Paint.Style.FILL
    }

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val p = android.R.attr.state_pressed in state
        val s = android.R.attr.state_selected in state || android.R.attr.state_checked in state
        if (p == pressed && s == selected) return false
        pressed = p; selected = s; invalidateSelf(); return true
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { /* Material colors must not inherit XP tints. */ }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, (if (theme == MobileTheme.AQUA) 14f else 19f) * density)
        outline.alpha = .25f
    }

    companion object {
        private val metalGrain: Bitmap by lazy {
            val w = 128; val h = 64
            val pixels = IntArray(w * h)
            var state = 8191
            for (y in 0 until h) for (x in 0 until w) {
                state = state * 1103515245 + 12345
                val light = ((state ushr 19) and 7) + if (y % 3 == 0) 6 else 0
                pixels[y * w + x] = Color.argb(light, if (y % 2 == 0) 255 else 65,
                    if (y % 2 == 0) 255 else 81, if (y % 2 == 0) 255 else 96)
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}
