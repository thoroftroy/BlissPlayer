package com.blissplayer.core

/** Platform-independent drawing commands for the Android segment seek bar. Coordinates are pixels. */
interface SegmentCanvas {
    fun rect(left: Float, top: Float, right: Float, bottom: Float, color: Int, stroke: Float = 0f)
    fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float, color: Int, stroke: Float = 0f, gradient: SegmentGradient? = null)
    fun circle(x: Float, y: Float, radius: Float, color: Int, stroke: Float = 0f, gradient: SegmentGradient? = null)
}

data class SegmentGradient(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val colors: List<Int>, val mirror: Boolean = false)
data class SegmentRange(val startMs: Long, val endMs: Long, val selected: Boolean = false)
enum class SegmentTheme { BLISS, DARK, AQUA, SUPER }

/** The same Android-derived track, segments and thumb are painted by both front ends. */
object SegmentBarDrawing {
    private fun c(value: Long) = value.toInt()

    /** Android's existing touch mapping, shared so desktop seeking behaves the same. */
    fun positionForX(x: Float, width: Float, density: Float, durationMs: Long): Long {
        val max = durationMs.coerceAtLeast(0)
        val pad = 4f * density.coerceAtLeast(0f)
        val usable = width - 2f * pad
        if (usable <= 0f || !x.isFinite() || !width.isFinite() || !density.isFinite()) return 0L
        return (((x - pad) / usable).coerceIn(0f, 1f) * max).toLong()
    }

    fun draw(canvas: SegmentCanvas, width: Float, height: Float, density: Float, theme: SegmentTheme,
             positionMs: Long, durationMs: Long, segments: List<SegmentRange>, rainbowPhase: Float = 0f) {
        if (width <= 0f || height <= 0f || density <= 0f) return
        val max = durationMs.coerceAtLeast(1)
        fun fraction(ms: Long) = (ms.toDouble() / max.toDouble()).coerceIn(0.0, 1.0).toFloat()
        val frac = fraction(positionMs)
        val modern = theme == SegmentTheme.AQUA || theme == SegmentTheme.SUPER
        if (!modern) {
            val dark = theme == SegmentTheme.DARK
            val pad = 4f * density
            val top = pad + 6f
            val bottom = height - pad - 6f
            val right = (width - pad).coerceAtLeast(pad)
            val trackWidth = right - pad
            val edge = c(if (dark) 0xFF4A525E else 0xFF003C74)
            canvas.rect(pad, top, right, bottom, c(if (dark) 0xFF26292F else 0xFFFFFFFF))
            val fill = trackWidth * frac
            val block = 8f * density
            var x = pad
            while (x < pad + fill) {
                canvas.rect(x, top + 2f, minOf(x + block - 2f, pad + fill), bottom - 2f, c(0xFF3E9E4F))
                x += block
            }
            segments.forEach { segment ->
                val a = pad + trackWidth * fraction(segment.startMs)
                val b = pad + trackWidth * fraction(segment.endMs)
                if (b > a) {
                    canvas.rect(a, top, b, bottom, c(0xFFD32F2F))
                    if (segment.selected) canvas.rect(a, top, b, bottom, c(0xFFFFFFFF), 4f)
                }
            }
            canvas.rect(pad, top, right, bottom, edge, 3f)
            val tx = pad + trackWidth * frac
            val tw = 9f * density
            canvas.rect(tx - tw, pad, tx + tw, height - pad, c(if (dark) 0xFF3A4048 else 0xFFECE9D8))
            canvas.rect(tx - tw, pad, tx + tw, height - pad, edge, 3f)
            return
        }
        val aqua = theme == SegmentTheme.AQUA
        val pad = 10f * density
        val right = (width - pad).coerceAtLeast(pad)
        val cy = height / 2f
        val top = cy - 3.5f * density
        val bottom = cy + 3.5f * density
        val trackWidth = right - pad
        canvas.roundRect(pad, top, right, bottom, 4f * density, c(if (aqua) 0xFF93ADBD else 0xFF1B3147))
        val gradient = if (aqua) {
            SegmentGradient(0f, top, 0f, bottom, listOf(c(0xFFB4EEFF), c(0xFF187DE2), c(0xFF074DA1)))
        } else {
            val span = trackWidth.coerceAtLeast(1f)
            SegmentGradient(pad - span * rainbowPhase, 0f, pad + span * (1.6f - rainbowPhase), 0f,
                (0..7).map { hsv((rainbowPhase + it / 7f) % 1f, 0.85f, 1f) }, true)
        }
        if (frac > 0f) canvas.roundRect(pad, top, pad + trackWidth * frac, bottom, 4f * density, 0, gradient = gradient)
        segments.forEach { segment ->
            val a = pad + trackWidth * fraction(segment.startMs)
            val b = pad + trackWidth * fraction(segment.endMs)
            if (b > a) {
                canvas.roundRect(a, top, b, bottom, 2f * density, c(if (aqua) 0xFFDB5252 else 0xFFFF729D))
                if (segment.selected) canvas.roundRect(a, top, b, bottom, 2f * density, c(0xFFFFFFFF), 4f)
            }
        }
        val tx = pad + trackWidth * frac
        val thumbGradient = SegmentGradient(0f, cy - 9f * density, 0f, cy + 9f * density,
            if (aqua) listOf(c(0xFFFFFFFF), c(0xFFA9CBDD), c(0xFFF8FDFF)) else listOf(c(0xFFFFFFFF), c(0xFF9CFFE4)))
        canvas.circle(tx, cy, 9f * density, 0, gradient = thumbGradient)
        canvas.circle(tx, cy, 9f * density, c(if (aqua) 0xFF648EAF else 0xFF6AB8C9), density)
    }

    private fun hsv(h: Float, s: Float, v: Float): Int {
        val sector = ((h % 1f + 1f) % 1f) * 6f
        val i = sector.toInt()
        val f = sector - i
        val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
        val (r, g, b) = when (i) {
            0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t)
            3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q)
        }
        return (0xff shl 24) or ((r * 255 + .5f).toInt() shl 16) or ((g * 255 + .5f).toInt() shl 8) or (b * 255 + .5f).toInt()
    }
}
