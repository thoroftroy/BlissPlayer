package com.blissplayer.core

import org.junit.Assert.*
import org.junit.Test

class SegmentBarDrawingTest {
    private data class Shape(val type: String, val left: Float, val right: Float, val color: Int, val stroke: Float, val gradient: SegmentGradient?)
    private class RecordingCanvas : SegmentCanvas {
        val shapes = mutableListOf<Shape>()
        override fun rect(left: Float, top: Float, right: Float, bottom: Float, color: Int, stroke: Float) {
            shapes += Shape("rect", left, right, color, stroke, null)
        }
        override fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float, color: Int, stroke: Float, gradient: SegmentGradient?) {
            shapes += Shape("round", left, right, color, stroke, gradient)
        }
        override fun circle(x: Float, y: Float, radius: Float, color: Int, stroke: Float, gradient: SegmentGradient?) {
            shapes += Shape("circle", x - radius, x + radius, color, stroke, gradient)
        }
    }

    @Test fun retroTracksUseAndroidPaletteAndClampSegments() {
        for (theme in listOf(SegmentTheme.BLISS, SegmentTheme.DARK)) {
            val canvas = RecordingCanvas()
            SegmentBarDrawing.draw(canvas, 208f, 30f, 1f, theme, 100, 200,
                listOf(SegmentRange(-20, 300, selected = true)))
            val segment = canvas.shapes.single { it.color == 0xFFD32F2F.toInt() }
            assertEquals(4f, segment.left, 0f)
            assertEquals(204f, segment.right, 0f)
            assertTrue(canvas.shapes.any { it.stroke == 4f && it.color == -1 })
            assertEquals(if (theme == SegmentTheme.BLISS) -1 else 0xFF26292F.toInt(), canvas.shapes.first().color)
        }
    }

    @Test fun modernThemesHaveDistinctSharedGradientsAndSelection() {
        for (theme in listOf(SegmentTheme.AQUA, SegmentTheme.SUPER)) {
            val canvas = RecordingCanvas()
            SegmentBarDrawing.draw(canvas, 220f, 30f, 1f, theme, 50, 100,
                listOf(SegmentRange(25, 75, selected = true)), .25f)
            val fill = canvas.shapes.first { it.gradient != null }
            assertEquals(10f, fill.left, 0f)
            assertEquals(110f, fill.right, 0f)
            assertEquals(theme == SegmentTheme.SUPER, fill.gradient!!.mirror)
            assertTrue(canvas.shapes.any { it.type == "round" && it.stroke == 4f })
            assertEquals(2, canvas.shapes.count { it.type == "circle" })
        }
    }

    @Test fun emptyAndInvalidInputsAreSafe() {
        val canvas = RecordingCanvas()
        SegmentBarDrawing.draw(canvas, 0f, 30f, 1f, SegmentTheme.SUPER, 3, 0, emptyList())
        assertTrue(canvas.shapes.isEmpty())
        SegmentBarDrawing.draw(canvas, 200f, 30f, 1f, SegmentTheme.AQUA, -1, 0, listOf(SegmentRange(10, 0)))
        assertEquals(0, canvas.shapes.count { it.color == 0xFFDB5252.toInt() })
    }

    @Test fun touchMappingUsesAndroidPaddingAndClamps() {
        assertEquals(0L, SegmentBarDrawing.positionForX(4f, 208f, 1f, 1000))
        assertEquals(500L, SegmentBarDrawing.positionForX(104f, 208f, 1f, 1000))
        assertEquals(1000L, SegmentBarDrawing.positionForX(300f, 208f, 1f, 1000))
        assertEquals(0L, SegmentBarDrawing.positionForX(4f, 0f, 1f, 1000))
    }
}
