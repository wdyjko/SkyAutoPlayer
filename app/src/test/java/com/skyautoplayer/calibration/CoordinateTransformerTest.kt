package com.skyautoplayer.calibration

import android.graphics.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

class CoordinateTransformerTest {
    private fun rect(l: Int, t: Int, r: Int, b: Int) = Rect().also {
        Rect::class.java.getField("left").setInt(it, l)
        Rect::class.java.getField("top").setInt(it, t)
        Rect::class.java.getField("right").setInt(it, r)
        Rect::class.java.getField("bottom").setInt(it, b)
    }
    private val profile = CalibrationProfile("p", 1, 0, 0, 1000, 2000, rect(0, 0, 1000, 2000), normalizedKeyPoints = List(15) { NormalizedPoint(if (it == 0) 0f else 1f, if (it == 0) 0f else 1f) })

    @Test fun appliesInsetsToContentCoordinates() {
        val display = DisplaySnapshot(1, 0, 1000, 2000, rect(20, 100, 980, 1900), Insets(20, 100, 20, 100), Insets(20, 100, 20, 100), 420)
        val points = CoordinateTransformer().resolve(profile, display, 0)
        assertEquals(20f, points[0].x); assertEquals(100f, points[0].y)
        assertEquals(980f, points[1].x); assertEquals(1900f, points[1].y)
    }

    @Test fun rotatesNormalizedCoordinatesClockwise() {
        val display = DisplaySnapshot(1, 1, 2000, 1000, rect(0, 0, 2000, 1000), Insets(), Insets(), 420)
        val points = CoordinateTransformer().resolve(profile, display, 1)
        assertEquals(2000f, points[0].x); assertEquals(0f, points[0].y)
        assertEquals(0f, points[1].x); assertEquals(1000f, points[1].y)
    }
}
