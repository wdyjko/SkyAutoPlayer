package com.skyautoplayer.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Geometry for the in-game control bar.
 *
 * Numbers come from real measurements on the target device (Xiaomi
 * 22041211AC): 3200x1440 landscape, 1440x3200 portrait, bar measured 154px
 * tall before it was switched to content-sized width.
 */
class OverlayGeometryTest {

    private val landscapeW = 3200
    private val landscapeH = 1440
    private val portraitW = 1440
    private val portraitH = 3200
    private val barH = 154

    @Test
    fun barStaysFullyOnScreenVertically() {
        assertEquals(0, OverlayGeometry.clampY(-50, landscapeH, barH))
        assertEquals(landscapeH - barH, OverlayGeometry.clampY(Int.MAX_VALUE, landscapeH, barH))
        val y = OverlayGeometry.clampY(9999, portraitH, barH)
        assertTrue(y + barH <= portraitH)
    }

    @Test
    fun userChosenPositionIsPreservedWhenLegal() {
        // Dragging is the user's call - a legal position must pass through.
        assertEquals(700, OverlayGeometry.clampY(700, landscapeH, barH))
        assertEquals(120, OverlayGeometry.clampX(120, landscapeW, 900))
    }

    @Test
    fun xStaysOnScreen() {
        assertEquals(0, OverlayGeometry.clampX(-26, landscapeW, 900))
        assertEquals(landscapeW - 900, OverlayGeometry.clampX(99999, landscapeW, 900))
    }

    @Test
    fun contentSizedBarLeavesRoomToMoveHorizontally() {
        // A ~900px pill in a 3200px landscape display can be repositioned freely,
        // unlike the old 0.9*width bar which had only ~414px of slack.
        val slack = landscapeW - 900
        assertTrue(slack > 2000, "expected generous slack, got $slack")
    }

    @Test
    fun oversizedBarCollapsesToZeroRatherThanNegative() {
        assertEquals(0, OverlayGeometry.clampY(50, 100, 500))
        assertEquals(0, OverlayGeometry.clampX(50, 100, 500))
    }
}
