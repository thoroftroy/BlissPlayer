package com.blissplayer.music

import com.blissplayer.music.ui.theme.MotionField
import org.junit.Assert.*
import org.junit.Test

/**
 * The Super Bliss motion model is pure Kotlin with a fixed seed, so its
 * behavior is pinned here on the JVM: bursts spawn, rings expire, ambient
 * seeds inside the viewport, and identical seeds run identically.
 */
class MotionFieldTest {

    @Test
    fun burstSpawnsSparksAndRing() {
        val f = MotionField()
        f.burst(100f, 200f)
        assertEquals(1, f.burstCount)
        assertEquals(36, f.sparks.size)
        assertEquals(1, f.rings.size)
        assertTrue(f.sparks.all { !it.ambient })
    }

    @Test
    fun particlesExpire() {
        val f = MotionField()
        f.burst(50f, 50f)
        repeat(200) { f.step(0.05f, 400f, 800f, 1f, ambient = false) }
        assertTrue(f.sparks.isEmpty())
        assertTrue(f.rings.isEmpty())
    }

    @Test
    fun ambientSeedsInsideViewport() {
        val f = MotionField()
        f.step(0.016f, 400f, 800f, 1f, ambient = true)
        assertEquals(26, f.sparks.count { it.ambient })
        assertTrue(f.sparks.all { it.x in 0f..400f && it.y in 0f..800f })
    }

    @Test
    fun sameSeedSameRun() {
        val a = MotionField(1234L)
        val b = MotionField(1234L)
        a.burst(10f, 20f)
        b.burst(10f, 20f)
        repeat(10) {
            a.step(0.016f, 400f, 800f, 1f, ambient = false)
            b.step(0.016f, 400f, 800f, 1f, ambient = false)
        }
        assertEquals(a.sparks.size, b.sparks.size)
        assertEquals(a.sparks.map { it.x to it.y }, b.sparks.map { it.x to it.y })
    }

    @Test
    fun zeroSizeNeverSeeds() {
        val f = MotionField()
        f.step(0.016f, 0f, 0f, 1f, ambient = true)
        assertTrue(f.sparks.isEmpty())
    }
}
