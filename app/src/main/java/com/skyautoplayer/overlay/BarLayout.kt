package com.skyautoplayer.overlay

/**
 * Width budget, marquee rule and the collapse/expand geometry of the in-game
 * control bar (W10 / W11).
 *
 * Deliberately free of Android types: [PlaybackOverlayService] converts dp to px
 * and calls in, so every rule below is covered by plain JVM unit tests.
 */
object BarLayout {

    // ── W19 uniform button size ────────────────────────────────────────
    //
    // W10 had seven different widths (32/36/42/38/36/46/46dp) picked for text
    // glyphs. Now that every control is a 24dp vector icon on a 40dp square,
    // one size covers all of them:
    //
    //   7 x 40dp = 280dp of buttons
    //   + fixed 140dp title + 76dp clock + 8dp left padding = 504dp (63% of 800dp)
    //
    // Still far below the pre-W10 worst case (~640dp / 80%), and the width is
    // still independent of the song name.
    const val BUTTON_DP = 40
    const val MINIMIZE_BTN_DP = BUTTON_DP
    const val PREV_DP = BUTTON_DP
    const val PLAY_DP = BUTTON_DP
    const val STOP_DP = BUTTON_DP
    const val NEXT_DP = BUTTON_DP
    const val LIBRARY_DP = BUTTON_DP
    const val CALIBRATE_DP = BUTTON_DP
    const val BUTTON_TOTAL_DP = 7 * BUTTON_DP

    /** W10.1: the title area is a constant, so buttons never drift sideways. */
    const val TITLE_WIDTH_DP = 140
    const val TIME_WIDTH_DP = 76

    /**
     * Row padding. Vertical padding is 4dp top and bottom; the left is 8dp on
     * purpose so the minimise button's centre sits one ball radius (24dp) in
     * from the window's left edge. That keeps the W11.1 offset x >= 0, so a bar
     * at x = 0 (the default) collapses into a ball that is still fully on
     * screen - no clamp, and therefore no invariant error at all.
     */
    const val BAR_PADDING_DP = 4
    const val BAR_PADDING_LEFT_DP = 8
    const val BAR_PADDING_RIGHT_DP = 0

    /** Button height, i.e. the content height of a control row. */
    const val BAR_CONTENT_DP = BUTTON_DP

    /** Whole row/window height: content plus the 4dp vertical padding. */
    const val BAR_HEIGHT_DP = BAR_CONTENT_DP + 2 * BAR_PADDING_DP // 48

    /** Whole-window width budget: 280 + 140 + 76 + 8 + 0 = 504dp. */
    const val TOTAL_WIDTH_DP =
        BUTTON_TOTAL_DP + TITLE_WIDTH_DP + TIME_WIDTH_DP +
            BAR_PADDING_LEFT_DP + BAR_PADDING_RIGHT_DP

    // ── W10.2 marquee ──────────────────────────────────────────────────
    /** A 99-character name would need ~15s per pass; 40 keeps it readable. */
    const val MARQUEE_LIMIT = 40

    // ── W11 floating ball ──────────────────────────────────────────────
    /** >= 48dp is the accessibility minimum touch target - do not shrink. */
    const val BALL_DP = 48

    /**
     * The ball is a touchable window: any note injected under it is swallowed.
     * It therefore never sits below this fraction of the screen height, which
     * keeps it clear of Sky's instrument keys in the lower band.
     */
    const val BALL_SAFE_BOTTOM_FRACTION = 0.55f

    /**
     * W10.2: truncate before marqueeing. Returns the title untouched when it is
     * short enough (median bundled title length is 8, longest is 99).
     */
    fun marqueeText(title: String, limit: Int = MARQUEE_LIMIT): String =
        if (limit > 0 && title.length > limit) title.take(limit) + "…" else title

    /**
     * Centre of the leftmost (`≡` minimise) button in *window-local* pixels.
     *
     * The row is padded, so the button is NOT at the window origin - ignoring
     * the padding is what makes the naive formula land 4dp off in x and 4dp off
     * in y on this device. The service prefers the real measured centre
     * (`getLocationInWindow`) and only falls back to this before the first
     * layout pass.
     */
    fun minimizeButtonCenter(
        paddingLeftPx: Int,
        paddingTopPx: Int,
        buttonWidthPx: Int,
        barHeightPx: Int
    ): IntArray = intArrayOf(paddingLeftPx + buttonWidthPx / 2, paddingTopPx + barHeightPx / 2)

    /**
     * Offset from the bar's top-left corner to the ball's top-left corner.
     *
     * `ballX = barX + offsetX` makes the ball's centre coincide with the
     * minimise button's centre - the W11.1 invariant. The reverse is
     * [barFromBall], so dragging the ball elsewhere and expanding puts the
     * button back under the ball.
     */
    fun ballOffset(buttonCenterX: Int, buttonCenterY: Int, ballSizePx: Int): IntArray =
        intArrayOf(buttonCenterX - ballSizePx / 2, buttonCenterY - ballSizePx / 2)

    /**
     * The offset the work order documents in W11.1, i.e. when the minimise
     * button is assumed to sit at the window origin:
     * `ballX = barX - (BALL-BTN)/2`, `ballY = barY - (BALL-BAR_H)/2`.
     * Kept as an executable reference for the unit tests.
     */
    fun documentedBallOffset(
        ballDp: Int = BALL_DP,
        buttonDp: Int = MINIMIZE_BTN_DP,
        barHeightDp: Int = BAR_HEIGHT_DP
    ): IntArray = intArrayOf(-(ballDp - buttonDp) / 2, -(ballDp - barHeightDp) / 2)

    /** Bar top-left -> ball top-left (W11.1). */
    fun ballFromBar(barX: Int, barY: Int, offsetX: Int, offsetY: Int): IntArray =
        intArrayOf(barX + offsetX, barY + offsetY)

    /** Ball top-left -> bar top-left; exact inverse of [ballFromBar]. */
    fun barFromBall(ballX: Int, ballY: Int, offsetX: Int, offsetY: Int): IntArray =
        intArrayOf(ballX - offsetX, ballY - offsetY)

    /**
     * W11.6: keeps the ball (and therefore its drop position) out of the lower
     * key band. The bar itself is left where the user put it.
     */
    fun clampBallY(
        ballY: Int,
        screenHeight: Int,
        ballSizePx: Int,
        safeBottomFraction: Float = BALL_SAFE_BOTTOM_FRACTION
    ): Int {
        val max = (screenHeight * safeBottomFraction).toInt() - ballSizePx
        return ballY.coerceIn(0, max.coerceAtLeast(0))
    }

    // ── W20 progress ring ──────────────────────────────────────────────
    //
    // The arc starts at 12 o'clock (-90 degrees, Canvas measures from 3
    // o'clock) and its SWEEP shrinks with progress, so the grey track grows
    // behind it. Kept here so the formula is unit-tested rather than buried in
    // onDraw.

    /** Degrees at which the green arc starts, for progress in 0..1. */
    fun ringStartAngle(progress: Float): Float = -90f + 360f * progress.coerceIn(0f, 1f)

    /** How much green is left to draw, for progress in 0..1. */
    fun ringSweepAngle(progress: Float): Float = 360f * (1f - progress.coerceIn(0f, 1f))
}
