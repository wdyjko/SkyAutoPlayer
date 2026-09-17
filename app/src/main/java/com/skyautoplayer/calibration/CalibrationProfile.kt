package com.skyautoplayer.calibration

import android.graphics.PointF
import android.graphics.Rect

data class Insets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)
data class NormalizedPoint(val x: Float, val y: Float) {
    init { require(x in 0f..1f && y in 0f..1f) }
}

data class CalibrationProfile(
    val profileId: String,
    val displayId: Int,
    val orientation: Int,
    val naturalOrientation: Int,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val contentBounds: Rect,
    val safeInsets: Insets = Insets(),
    val systemBarInsets: Insets = Insets(),
    val cutoutInfo: String? = null,
    val navigationMode: String = "unknown",
    val densityDpi: Int = 0,
    val displayScale: Float = 1f,
    val normalizedKeyPoints: List<NormalizedPoint>,
    val coordinateSpace: String = "content",
    val gameLayoutVersion: String = "default",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val calibrationQuality: String = "manual",
    val schemaVersion: Int = 1
) {
    init { require(normalizedKeyPoints.size == 15) }
}

data class DisplaySnapshot(
    val displayId: Int,
    val orientation: Int,
    val widthPx: Int,
    val heightPx: Int,
    val contentBounds: Rect,
    val safeInsets: Insets,
    val systemBarInsets: Insets,
    val densityDpi: Int,
    val displayScale: Float = 1f
)

class CoordinateTransformer {
    fun resolve(profile: CalibrationProfile, display: DisplaySnapshot): List<PointF> =
        resolve(profile, display, rotationDelta = ((display.orientation - profile.orientation) % 4 + 4) % 4)

    /** Resolves points inside the current content area. rotationDelta is clockwise quarter turns. */
    fun resolve(profile: CalibrationProfile, display: DisplaySnapshot, rotationDelta: Int): List<PointF> {
        require(profile.displayId == display.displayId)
        val bounds = display.contentBounds
        val width = (bounds.right - bounds.left).toFloat()
        val height = (bounds.bottom - bounds.top).toFloat()
        return profile.normalizedKeyPoints.map { p ->
            val rotated = when (rotationDelta.mod(4)) {
                1 -> NormalizedPoint(1f - p.y, p.x)
                2 -> NormalizedPoint(1f - p.x, 1f - p.y)
                3 -> NormalizedPoint(p.y, 1f - p.x)
                else -> p
            }
            PointF().apply {
                x = bounds.left + rotated.x * width
                y = bounds.top + rotated.y * height
            }
        }
    }
}
