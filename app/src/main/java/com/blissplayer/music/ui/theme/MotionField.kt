package com.blissplayer.music.ui.theme

import kotlin.math.cos
import kotlin.math.sin

/** Pure, deterministic motion model; the same clock advances sparks, trails and shockwaves. */
class MotionField(seed: Long = 0xB1155L) {
    data class Spark(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        val radius: Float, var life: Float, val duration: Float, val color: Int,
        val ambient: Boolean, var previousX: Float = x, var previousY: Float = y
    )
    data class Ring(val x: Float, val y: Float, var age: Float = 0f, val color: Int)

    val sparks = mutableListOf<Spark>()
    val rings = mutableListOf<Ring>()
    var burstCount = 0
        private set
    var elapsed = 0f
        private set
    private var state = seed
    private var seeded = false
    private val palette = intArrayOf(
        0xFF6DFFE1.toInt(), 0xFFFF6AAE.toInt(), 0xFF6B9DFF.toInt(),
        0xFFFFCE68.toInt(), 0xFFD6A8FF.toInt(), 0xFFFFFFFF.toInt()
    )
    private fun random(): Float {
        state = state * 6364136223846793005L + 1442695040888963407L
        return ((state ushr 40) and 0xFFFFFF).toFloat() / 0x1000000
    }

    fun burst(x: Float, y: Float, scale: Float = 1f, ringCount: Int = 1, palette: IntArray? = null,
             lifeScale: Float = 1f) {
        burstCount++
        val pal = palette ?: this.palette
        repeat(ringCount.coerceIn(0, 3)) {
            rings.add(Ring(x, y, color = pal[(random() * pal.size).toInt()]))
        }
        if (rings.size > 12) rings.removeAll(rings.take(rings.size - 12))
        // Classic XP bursts are tiny pixel pops, not neon storms.
        val count = if (ringCount > 1) 60 else if (palette != null) 8 else 36
        repeat(count) {
            if (sparks.size >= 180) return@repeat
            val angle = random() * 6.2831853f
            val speed = (65 + random() * 195) * scale
            val duration = (0.5f + random() * 0.8f) * lifeScale
            sparks.add(Spark(x, y, cos(angle) * speed, sin(angle) * speed,
                (1.3f + random() * 2.8f) * scale, duration, duration,
                pal[(random() * pal.size).toInt()], false))
        }
    }

    fun step(seconds: Float, width: Float, height: Float, density: Float = 1f, ambient: Boolean = true,
             ambientTarget: Int = 26) {
        val dt = seconds.coerceIn(0f, 0.05f)
        elapsed += dt
        if (width <= 0 || height <= 0) return
        // Start IN the viewport. The old effect spawned below it and fell out of sight.
        if (!seeded && ambient) {
            repeat(ambientTarget) { addAmbient(width, height, density, true) }
            seeded = true
        }
        val iterator = sparks.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.life -= dt
            if (p.life <= 0) { iterator.remove(); continue }
            p.previousX = p.x
            p.previousY = p.y
            p.x += p.vx * dt
            p.y += p.vy * dt
            if (p.ambient) p.x += sin(elapsed * 0.9f + p.radius) * dt * 8f * density
            else p.vy += 105f * density * dt
        }
        val ri = rings.iterator()
        while (ri.hasNext()) { if (ri.next().also { it.age += dt }.age > 1.1f) ri.remove() }
        if (ambient && sparks.count { it.ambient } < ambientTarget) addAmbient(width, height, density, false)
    }

    private fun addAmbient(w: Float, h: Float, d: Float, anywhere: Boolean) {
        val duration = 6f + random() * 7f
        sparks.add(Spark(random() * w, if (anywhere) random() * h else h * (0.6f + random() * 0.4f),
            (random() - 0.5f) * 7f * d, -(9f + random() * 16f) * d,
            (0.7f + random() * 1.3f) * d, duration, duration,
            palette[(random() * palette.size).toInt()], true))
    }

    fun clear() { sparks.clear(); rings.clear(); seeded = false }
}
