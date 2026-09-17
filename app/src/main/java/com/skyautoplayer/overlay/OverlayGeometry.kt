package com.skyautoplayer.overlay

/**
 * Pure geometry for the in-game control bar.
 *
 * Kept free of Android types so the maths is covered by JVM unit tests.
 *
 * The bar sizes itself to its content (a drag handle, two buttons and a short
 * label), so it stays a compact pill rather than spanning the screen. Vertical
 * placement is left to the user: the only hard rule is that the bar must remain
 * reachable on screen, otherwise it could be dragged away and never recovered.
 */
object OverlayGeometry {

    fun clampX(x: Int, screenWidth: Int, barWidth: Int): Int {
        val max = (screenWidth - barWidth).coerceAtLeast(0)
        return x.coerceIn(0, max)
    }

    /**
     * Keeps the bar fully inside the display. Deliberately *not* a "stay away
     * from the keys" rule - the user positions it themselves.
     */
    fun clampY(y: Int, screenHeight: Int, barHeight: Int): Int {
        val max = (screenHeight - barHeight).coerceAtLeast(0)
        return y.coerceIn(0, max)
    }
}
