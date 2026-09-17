package com.skyautoplayer.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W10 width budget / marquee rule and the W11 collapse geometry.
 *
 * Device numbers come from the target phone (Xiaomi 22041211AC / V2309A):
 * 3200x1440 landscape at density 3.5, so 800dp of usable landscape width and
 * 2800px of content.
 */
class BarLayoutTest {

    private val density = 3.5f

    private fun px(dp: Int) = (dp * density).toInt()

    // ── W10 ────────────────────────────────────────────────────────────

    @Test
    fun widthBudgetMatchesTheW19Table() {
        // W19: all seven bar controls are the same 40dp square.
        assertEquals(40, BarLayout.BUTTON_DP)
        assertEquals(280, BarLayout.BUTTON_TOTAL_DP)
        // 280 + fixed 140dp title + 76dp clock + 8dp left + 0dp right padding
        assertEquals(504, BarLayout.TOTAL_WIDTH_DP)
        // Still far below the pre-W10 worst case of ~640dp (80% of 800dp).
        assertTrue(BarLayout.TOTAL_WIDTH_DP < 640, "budget regressed: ${BarLayout.TOTAL_WIDTH_DP}dp")
    }

    @Test
    fun ringStartsAtTwelveOClockAndUnwindsClockwise() {
        // W20.2: startAngle = -90 + 360 * progress, sweep = 360 * (1 - progress).
        // At progress 0 the whole ring is green and the start sits at 12 o'clock;
        // the arc then unwinds clockwise until nothing is left.
        assertEquals(-90f, BarLayout.ringStartAngle(0f))
        assertEquals(360f, BarLayout.ringSweepAngle(0f))
        assertEquals(90f, BarLayout.ringStartAngle(0.5f))
        assertEquals(180f, BarLayout.ringSweepAngle(0.5f))  // half the ring is left
        assertEquals(270f, BarLayout.ringStartAngle(1f))
        assertEquals(0f, BarLayout.ringSweepAngle(1f))      // fully unwound
        // Out-of-range input clamps instead of wrapping the arc around.
        assertEquals(-90f, BarLayout.ringStartAngle(-2f))
        assertEquals(0f, BarLayout.ringSweepAngle(2f))
    }

    @Test
    fun marqueeTextOnlyTruncatesWhatWillNotFit() {
        // Median bundled title length is 8 - it must pass through untouched.
        assertEquals("Flower D", BarLayout.marqueeText("Flower D"))
        assertEquals("", BarLayout.marqueeText(""))
        val exact = "x".repeat(BarLayout.MARQUEE_LIMIT)
        assertEquals(exact, BarLayout.marqueeText(exact))
        val long = "妄想税".repeat(40) // 120 characters
        val cut = BarLayout.marqueeText(long)
        assertEquals(BarLayout.MARQUEE_LIMIT + 1, cut.length)
        assertTrue(cut.endsWith("…"))
        assertEquals(long.take(BarLayout.MARQUEE_LIMIT), cut.dropLast(1))
    }

    // ── W11.1 geometry invariant ───────────────────────────────────────

    @Test
    fun ballCentreLandsOnTheMinimiseButtonCentre() {
        val ball = px(BarLayout.BALL_DP)
        val offset = BarLayout.ballOffset(px(20), px(26), ball)
        assertEquals(-px(4), offset[0])
        assertEquals(px(2), offset[1])

        val barX = 1120
        val barY = 42
        val ballPos = BarLayout.ballFromBar(barX, barY, offset[0], offset[1])
        // Ball centre...
        val ballCentreX = ballPos[0] + ball / 2
        val ballCentreY = ballPos[1] + ball / 2
        // ...must equal the ≡ button centre in screen coordinates.
        assertEquals(barX + px(20), ballCentreX)
        assertEquals(barY + px(26), ballCentreY)
    }

    /**
     * The real row pads 8dp on the left while the button is 40dp wide, so the
     * button centre sits 4dp *outside* one ball radius - i.e. the ball never
     * sticks out past the bar's left edge. That is what makes a flush-left bar
     * (x = 0, the default) collapse with ZERO clamp error; the first
     * implementation was 4dp out and needed a 14px clamp on device.
     */
    @Test
    fun paddedRowNeedsNoHorizontalClampAtTheScreenEdge() {
        val ball = px(BarLayout.BALL_DP)
        val offset = BarLayout.ballOffset(
            px(BarLayout.BAR_PADDING_LEFT_DP) + px(BarLayout.BUTTON_DP) / 2,
            px(BarLayout.BAR_PADDING_DP) + px(BarLayout.BUTTON_DP) / 2,
            ball
        )
        assertTrue(offset[0] >= 0, "ball must not stick out past the bar's left edge")
        assertEquals(0, offset[1])

        // bar at x = 0 -> ball at x = 4dp -> fully on screen, invariant exact.
        val ballPos = BarLayout.ballFromBar(0, 0, offset[0], offset[1])
        assertEquals(px(4), ballPos[0])
        assertTrue(ballPos[0] >= 0 && ballPos[0] + ball <= 2800)
        assertEquals(0, OverlayGeometry.clampX(ballPos[0], 2800, ball) - ballPos[0])
    }

    @Test
    fun documentedFormulaIsTheZeroPaddingSpecialCase() {
        // W11.1 states ballX = barX - (48-32)/2 and ballY = barY - (48-44)/2 for
        // a 32dp button in a 44dp row, which is exactly this offset evaluated
        // with the button sitting at the window origin. The sizes are passed
        // explicitly so the test tracks the W11.1 formula, not today's button.
        val documented = BarLayout.documentedBallOffset(ballDp = 48, buttonDp = 32, barHeightDp = 44)
        assertEquals(-8, documented[0])
        assertEquals(-2, documented[1])
        val derived = BarLayout.ballOffset(16, 22, 48)
        assertEquals(documented[0], derived[0])
        assertEquals(documented[1], derived[1])
    }

    @Test
    fun expandingTheBallRestoresTheBarExactly() {
        val ball = px(BarLayout.BALL_DP)
        val offset = BarLayout.ballOffset(px(24), px(26), ball)
        listOf(0 to 0, -14 to -7, 1120 to 42, 2799 to 4242).forEach { (x, y) ->
            val ballPos = BarLayout.ballFromBar(x, y, offset[0], offset[1])
            val back = BarLayout.barFromBall(ballPos[0], ballPos[1], offset[0], offset[1])
            assertEquals(x, back[0], "x round trip from ($x,$y)")
            assertEquals(y, back[1], "y round trip from ($x,$y)")
        }
    }

    @Test
    fun minimiseButtonCentreUsesBothPaddings() {
        // W19 geometry: 8dp left, 4dp top, 40dp square button, density 3.5.
        val centre = BarLayout.minimizeButtonCenter(
            px(BarLayout.BAR_PADDING_LEFT_DP),
            px(BarLayout.BAR_PADDING_DP),
            px(BarLayout.BUTTON_DP),
            px(BarLayout.BUTTON_DP)
        )
        assertEquals(px(28), centre[0])
        assertEquals(px(24), centre[1])
    }

    @Test
    fun ballIsClampedOutOfTheLowerKeyBand() {
        val screenH = 1440
        val ball = px(BarLayout.BALL_DP) // 168px
        assertEquals(0, BarLayout.clampBallY(-120, screenH, ball))
        val lowest = BarLayout.clampBallY(9999, screenH, ball)
        val bandTop = (screenH * BarLayout.BALL_SAFE_BOTTOM_FRACTION).toInt()
        assertEquals(bandTop - ball, lowest)
        assertTrue(lowest + ball <= bandTop, "ball bottom entered the key band")
        // A position that is already safe must pass through unchanged.
        assertEquals(120, BarLayout.clampBallY(120, screenH, ball))
    }

    @Test
    fun clampIsSafeOnADisplaySmallerThanTheBall() {
        assertEquals(0, BarLayout.clampBallY(50, 40, 200))
        assertEquals(0, BarLayout.clampBallY(-50, 40, 200))
    }
}
