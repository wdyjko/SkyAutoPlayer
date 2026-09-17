package com.skyautoplayer.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.skyautoplayer.R
import com.skyautoplayer.application.PlaybackRuntime
import com.skyautoplayer.application.SongQueue
import com.skyautoplayer.domain.playback.PlaybackState
import com.skyautoplayer.storage.SongEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * In-game playback control bar.
 *
 * Lives above Sky so 播放/暂停/停止 are reachable **while the game is in front**,
 * which removes the "tap play then race to switch apps" problem: the moment the
 * user presses play they are already in the game.
 *
 * Window flags follow [CalibrationOverlayService.overlayParams]:
 * `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_NO_LIMITS`.
 * `FLAG_NOT_FOCUSABLE` is the only thing keeping Sky focused - dropping it would
 * make the overlay steal focus and the game would stop receiving input.
 *
 * The bar sizes to its content and is kept on screen by [OverlayGeometry].
 * Opening the song panel pauses playback: the panel is a wide touchable window,
 * and any note injected underneath it would be swallowed instead of played.
 */
class PlaybackOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var root: FrameLayout
    private lateinit var barColumn: LinearLayout
    private lateinit var dragBar: DragBarLayout
    private lateinit var ballView: BallProgressView
    private lateinit var minimizeButton: ImageButton
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var titleView: TextView
    private lateinit var timeView: TextView
    private lateinit var playPause: ImageButton

    /** W11: true while the bar is shrunk into the floating ball. */
    private var collapsed = false

    /** W24: last `hidden` value written to the log, so it only logs on change. */
    private var lastHiddenLogged: Boolean? = null

    /** Exact `ball = bar + offset` from the measured ≡ button centre. */
    private var ballOffset: IntArray? = null

    /** Bar width while expanded; the ball must be able to expand back to it. */
    private var expandedBarWidthPx = 0
    private var expandedBarHeightPx = 0

    /** Running window animation, cancelled if the user grabs the bar again. */
    private var windowAnimator: ValueAnimator? = null

    /** W10.4: full (untruncated) title, for the tap-to-read Toast fallback. */
    private var fullTitle: String = ""

    private var panel: View? = null
    private var panelRows: LinearLayout? = null
    private var panelScroll: ScrollView? = null
    private var pageLabel: TextView? = null
    private var searchStub: View? = null
    private var searchField: EditText? = null
    private var searchClear: View? = null
    private var searchClose: View? = null
    private var query: String = ""
    private var panelOpen = false
    private var page = 0

    private var barRow: View? = null
    private var pagerRow: View? = null

    /** True once the user has actively entered search state (IME up). */
    private var searchMode = false

    /** Re-entrancy guard for the focusable-window-then-focus-EditText ordering. */
    private var enteringSearch = false

    /** Height currently usable by the window (screen minus IME / system bars). */
    private var availableHeightPx = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ticker = Handler(Looper.getMainLooper())

    private var lastState: PlaybackState = PlaybackState.Idle
    private var lastStateAtNanos = 0L

    private val tick = object : Runnable {
        override fun run() {
            renderLabel(lastState)
            ticker.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)

        // Guarantees PlaybackRuntime.engine/playback are initialised even when
        // this service is the first thing started (e.g. from the calibration
        // flow), so the lateinit fields below can never be touched uninitialised.
        PlaybackRuntime.startForegroundService(this)

        startForegroundIfNeeded()
        buildControlBar()
        observeState()
        ticker.postDelayed(tick, TICK_MS)

        // The bar must be usable even when it is the first thing started, so
        // populate the queue from the store rather than relying on MainActivity.
        scope.launch {
            if (SongQueue.songs.value.isEmpty()) {
                val list = withContext(Dispatchers.IO) { PlaybackRuntime.songs.list() }
                SongQueue.setSongs(list)
                Log.d(TAG, "控制条已载入曲库：${list.size} 首")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * Sky runs landscape while this app's own UI is portrait, so the bar crosses
     * an orientation change on every session. Without re-clamping, a bar dragged
     * to the right in landscape (x up to ~3200) would land off-screen in portrait
     * (1440 wide) and could never be dragged back.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::root.isInitialized) return
        windowAnimator?.end()
        val metrics = resources.displayMetrics
        if (collapsed) {
            params.x = OverlayGeometry.clampX(params.x, metrics.widthPixels, ballSizePx())
            params.y = BarLayout.clampBallY(params.y, metrics.heightPixels, ballSizePx())
        } else {
            params.x = clampX(params.x)
            params.y = clampY(params.y)
        }
        runCatching { windowManager.updateViewLayout(root, params) }
    }

    override fun onDestroy() {
        ticker.removeCallbacks(tick)
        scope.cancel()
        runCatching { windowManager.removeView(root) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── UI ────────────────────────────────────────────────────────────

    private fun buildControlBar() {
        val density = resources.displayMetrics.density
        val pad = (BarLayout.BAR_PADDING_DP * density).toInt()
        val padLeft = (BarLayout.BAR_PADDING_LEFT_DP * density).toInt()
        val padRight = (BarLayout.BAR_PADDING_RIGHT_DP * density).toInt()
        val barHeight = barHeightPx()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Asymmetric on purpose - see BarLayout.BAR_PADDING_LEFT_DP.
            setPadding(padLeft, pad, padRight, pad)
            setBackgroundColor(0xDD202124.toInt())
        }

        // W19: every control is now an ImageButton of the same 40dp square with
        // a 24dp vector icon centred in it, so the icons share one line weight
        // and one visual size instead of mixing ≡ / ◀◀ / ▶ / ■ / ▶▶ / ♪ / ◎ at
        // four different font sizes and six different widths.
        //
        // W11.3: the minimise button is also the drag anchor for the collapsed
        // ball (W11.1), so it stays a plain View we can measure - but it carries
        // the same icon treatment.
        minimizeButton = iconButton(R.drawable.ic_minimize, "最小化") { collapse() }
        row.addView(minimizeButton)

        // W5.2: prev / next walk SongQueue. Switching uses the one true order:
        // stop -> load -> play, so a switched song always starts at note 0.
        row.addView(iconButton(R.drawable.ic_skip_previous, "上一首") {
            Log.i(TAG, "控制条收到命令：Prev")
            if (SongQueue.prev() != null) {
                SongQueue.loadAndPlay(SongQueue.currentIndex.value)
                refreshPanel()
            }
        })

        playPause = iconButton(R.drawable.ic_play_arrow, "播放/暂停") { onPlayPauseClicked() }
        row.addView(playPause)

        row.addView(iconButton(R.drawable.ic_stop, "停止") {
            Log.i(TAG, "控制条收到命令：Stop")
            PlaybackRuntime.playback.stop()
        })

        row.addView(iconButton(R.drawable.ic_skip_next, "下一首") {
            Log.i(TAG, "控制条收到命令：Next")
            if (SongQueue.next() != null) {
                SongQueue.loadAndPlay(SongQueue.currentIndex.value)
                refreshPanel()
            }
        })

        row.addView(iconButton(R.drawable.ic_queue_music, "曲目") { togglePanel() })

        // W1.1: calibration entry lives here so the user can align the keys
        // without leaving the game. Starting another FGS from this one is fine:
        // this service is already in the foreground.
        row.addView(iconButton(R.drawable.ic_tune, "校准") {
            Log.i(TAG, "控制条收到命令：Calibrate")
            startForegroundService(Intent(this@PlaybackOverlayService, CalibrationOverlayService::class.java))
        })

        // W7.1 + W10.1: the title and the clock stay separate views, but the
        // title is now a FIXED 140dp instead of WRAP_CONTENT + maxWidth 280dp.
        //
        // The old width was content-driven, so the whole bar stretched to ~640dp
        // (80% of an 800dp screen) for long names, the buttons slid sideways with
        // the title, and - because a median 8-character name fills nothing - the
        // marquee practically never ran. A constant width fixes all three: the
        // buttons never move, the bar is a predictable ~480dp, and any name
        // longer than the box genuinely scrolls.
        titleView = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFE8EAED.toInt())
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            isSelected = true               // marquee is driven by selection, not window focus
            marqueeRepeatLimit = -1         // keep scrolling for the whole song
            maxWidth = (BarLayout.TITLE_WIDTH_DP * density).toInt()
            setPadding(pad, 0, pad, 0)
            // W10.4 fallback: a tap shows the FULL name via Toast (needs no focus).
            setOnClickListener {
                if (fullTitle.isNotEmpty()) {
                    android.widget.Toast.makeText(
                        this@PlaybackOverlayService, fullTitle, android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        row.addView(
            titleView,
            LinearLayout.LayoutParams((BarLayout.TITLE_WIDTH_DP * density).toInt(), barButtonSizePx())
        )

        // Must not scroll: a marquee here would carry the clock off screen.
        // W10.3 moved the state suffix (「（暂停）」) out of the title and into
        // this view as a ⏸ / ▶ prefix, so the title box only ever holds a name.
        // Auto-sizing keeps "⏸ 00:05 / 01:37" inside the fixed 76dp budget.
        timeView = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFFB0B6BD.toInt())
            maxLines = 1
            isSingleLine = true
            // Right padding restores the 8dp visual inset the row no longer has.
            setPadding(pad, 0, padLeft, 0)
            setAutoSizeTextTypeUniformWithConfiguration(
                8, 11, 1, TypedValue.COMPLEX_UNIT_SP
            )
        }
        row.addView(
            timeView,
            LinearLayout.LayoutParams((BarLayout.TIME_WIDTH_DP * density).toInt(), barButtonSizePx())
        )
        barRow = row

        // W14.2: the drag intercept layer wraps ONLY the bar row. The song panel
        // is added to barColumn directly (see openPanel), i.e. it is a *sibling*
        // of the intercept layer, so scrolling the song list can never be stolen
        // and turned into a window drag.
        dragBar = DragBarLayout(this, DragGesture(this).apply {
            onDragStart = { windowAnimator?.end() }
            onDrag = { dx, dy -> moveWindowBy(dx, dy) }
            onDragEnd = { finishDrag() }
        })
        dragBar.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        // Vertical container so the song panel can grow downwards from the bar.
        barColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD202124.toInt())
            addView(dragBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        // W11.2: the ball is a second child of the same window, so collapsing is
        // a visibility/size swap instead of removeView + addView. Both children
        // are pinned to the window's top-left corner (FrameLayout), which is what
        // makes the cross-fade line up with the window shrink.
        //
        // W14.2: the ball is a sibling of barColumn, so it no longer inherits the
        // bar's intercept layer - it carries the same DragGesture itself, which
        // keeps "the minimised ball can still be dragged" (W11) working.
        ballView = BallProgressView(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ballSizePx(), ballSizePx())
            setOnClickListener { expand() }
            setOnTouchListener(ballDragListener)
        }

        // W14.2: the window root is now layout-only; everything that can start a
        // drag lives further down the tree.
        //
        // W24b: it also intercepts BACK, because dropping FLAG_NOT_FOCUSABLE for
        // the search field must always be reversible - see OverlayRootLayout.
        root = OverlayRootLayout(this).apply {
            addView(barColumn, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(ballView, FrameLayout.LayoutParams(ballSizePx(), ballSizePx()))
            onBackPressed = { down ->
                if (searchMode) {
                    // Swallow both DOWN and UP so the game never sees half a
                    // BACK, and only leave search state on UP.
                    if (!down) exitSearchMode()
                    true
                } else {
                    // Browse state: BACK belongs to the game, not to us.
                    false
                }
            }
        }

        // W24b: the second half of the same guarantee. The window only drops
        // FLAG_NOT_FOCUSABLE while the search field owns the focus; if the
        // window loses focus for any other reason (the user switched to the game
        // / home / another app, or our own activity came back to the front), the
        // search state must end right there, otherwise the overlay would keep
        // stealing the game's key focus until someone happens to press ✕.
        root.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            // Guarded so it can never race the entry sequence: only a window
            // that was already in search state, on an open panel, outside the
            // 200ms focus hand-off gets kicked out.
            if (!hasFocus && searchMode && !enteringSearch && panelOpen) {
                Log.i(TAG, "浮层失去窗口焦点：退出搜索态以恢复 FLAG_NOT_FOCUSABLE")
                exitSearchMode()
            }
        }

        // FLAG_LAYOUT_NO_LIMITS stops the window manager from resizing us for
        // the IME, and WindowInsets does not report the IME bottom for such a
        // window either. getWindowVisibleDisplayFrame() does account for it, and
        // the global-layout callback fires exactly when the IME appears/goes.
        root.viewTreeObserver.addOnGlobalLayoutListener {
            if (!panelOpen) return@addOnGlobalLayoutListener
            val visible = Rect()
            root.getWindowVisibleDisplayFrame(visible)
            availableHeightPx = if (visible.height() > 0) {
                visible.height()
            } else {
                resources.displayMetrics.heightPixels
            }
            setSearchMode(searchField?.hasFocus() == true)
            applyPanelSizing()
        }

        params = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            x = prefs.getInt(KEY_X, 0)
            y = prefs.getInt(KEY_Y, defaultY())
        }
        runCatching { windowManager.addView(root, params) }
            .onFailure { Log.e(TAG, "控制条添加失败", it) }

        // WRAP_CONTENT means the real size is only known after the first layout.
        root.post {
            params.x = clampX(params.x)
            params.y = clampY(params.y)
            runCatching { windowManager.updateViewLayout(root, params) }
            cacheBallOffset()
            Log.i(TAG, "控制条就绪：x=${params.x} y=${params.y} w=${root.width} h=${root.height}")
        }
    }

    private fun compactButton(text: String, widthDp: Int, heightPx: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 13f
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                (widthDp * resources.displayMetrics.density).toInt(), heightPx
            )
        }

    // ── W11 minimise / expand ─────────────────────────────────────────

    /** W19: the one square every bar control uses. */
    private fun barButtonSizePx(): Int =
        (BarLayout.BUTTON_DP * resources.displayMetrics.density).roundToInt()

    private fun ballSizePx(): Int =
        (BarLayout.BALL_DP * resources.displayMetrics.density).roundToInt()

    private fun barHeightPx(): Int =
        (BarLayout.BAR_HEIGHT_DP * resources.displayMetrics.density).roundToInt()

    /** The whole-bar width budget, used before the first real measurement. */
    private fun barWidthBudgetPx(): Int =
        (BarLayout.TOTAL_WIDTH_DP * resources.displayMetrics.density).roundToInt()

    /**
     * `ball = bar + offset` with the exact centre of the minimise button.
     *
     * The button does not sit at the window origin (the row is padded), so the
     * documented W11.1 formula is a few dp out. Measuring the view keeps the
     * invariant inside the 2dp tolerance; the padded formula is the pre-layout
     * fallback.
     */
    private fun cacheBallOffset() {
        val size = ballSizePx()
        val location = IntArray(2)
        if (::minimizeButton.isInitialized && minimizeButton.width > 0) {
            minimizeButton.getLocationInWindow(location)
            ballOffset = BarLayout.ballOffset(
                location[0] + minimizeButton.width / 2,
                location[1] + minimizeButton.height / 2,
                size
            )
        }
    }

    private fun ballOffsetPx(): IntArray {
        ballOffset?.let { return it }
        cacheBallOffset()
        ballOffset?.let { return it }
        val density = resources.displayMetrics.density
        return BarLayout.ballOffset(
            ((BarLayout.BAR_PADDING_LEFT_DP + BarLayout.BUTTON_DP / 2f) * density).roundToInt(),
            ((BarLayout.BAR_PADDING_DP + BarLayout.BUTTON_DP / 2f) * density).roundToInt(),
            ballSizePx()
        )
    }

    /**
     * W19: the single constructor for a bar control.
     *
     * 40dp square, 24dp vector icon centred by `CENTER_INSIDE`, tinted with the
     * shared palette, and the framework's borderless ripple as its background -
     * touch feedback with no Material dependency.
     */
    private fun iconButton(iconRes: Int, description: String, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(iconRes)
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(getColor(R.color.text_primary))
            background = borderlessRipple()
            setPadding(0, 0, 0, 0)
            // The framework's ImageButton style carries a 48dp minWidth/minHeight
            // which would silently override the 40dp square below.
            minimumWidth = 0
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(barButtonSizePx(), barButtonSizePx())
            setOnClickListener { onClick() }
        }

    /** The framework's borderless ripple, without pulling in Material. */
    private fun borderlessRipple(): Drawable? {
        val value = TypedValue()
        return if (theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, value, true
            )
        ) {
            getDrawable(value.resourceId)
        } else {
            null
        }
    }

    /** Re-tints an ImageButton, e.g. the play/pause accent while playing. */
    private fun tint(button: ImageButton, colorRes: Int) {
        button.setColorFilter(getColor(colorRes))
    }

    /**
     * W11.4: interpolate the window rectangle itself, one `updateViewLayout` per
     * frame. `R14` fallback (view-level cross-fade only) is only needed if this
     * stutters on low-end hardware.
     */
    private fun animateWindow(
        fromX: Int, fromY: Int, fromW: Int, fromH: Int,
        toX: Int, toY: Int, toW: Int, toH: Int,
        onEnd: () -> Unit
    ) {
        windowAnimator?.end()
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = COLLAPSE_ANIM_MS
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            val t = animation.animatedValue as Float
            params.x = lerp(fromX, toX, t)
            params.y = lerp(fromY, toY, t)
            params.width = lerp(fromW, toW, t)
            params.height = lerp(fromH, toH, t)
            runCatching { windowManager.updateViewLayout(root, params) }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (windowAnimator !== animation) return
                windowAnimator = null
                onEnd()
            }
        })
        windowAnimator = animator
        animator.start()
    }

    private fun lerp(from: Int, to: Int, t: Float): Int = (from + (to - from) * t).roundToInt()

    /**
     * W11.1/W11.2: shrink into the ball.
     *
     * The invariant is `ball centre == ≡ button centre`, so the ball lands
     * exactly where the button the user just pressed was - and because the bar is
     * reconstructed from the ball on expand, that stays true after the ball has
     * been dragged somewhere else.
     */
    private fun collapse() {
        if (collapsed || !::root.isInitialized) return
        if (panelOpen) closePanel()

        val metrics = resources.displayMetrics
        val size = ballSizePx()
        val offset = ballOffsetPx()

        // Measure the ROW, not the window: a panel may have just been removed and
        // the re-layout has not run yet, so root.height can still include it.
        val rowWidth = barRow?.width ?: 0
        val rowHeight = barRow?.height ?: 0
        val barW = if (rowWidth > 0) {
            rowWidth
        } else if (root.width > 0) {
            root.width
        } else {
            barWidthBudgetPx()
        }
        val barH = if (rowHeight > 0) rowHeight else barHeightPx()
        expandedBarWidthPx = barW
        expandedBarHeightPx = barH

        // W11.6: the ball must not sit over the instrument keys.
        //
        // R15: the ball is kept on screen by nudging the BAR, never by clamping
        // the ball. Clamping the ball would put the ≡ button `deviation` px away
        // from the ball centre, which is exactly the W11.1 invariant - a 14px
        // (4dp) error was measured on device before this. The bar only ever
        // moves by a few px here, because it is far wider than the ball.
        val minBarX = -offset[0]
        val maxBarX = (metrics.widthPixels - size - offset[0]).coerceAtLeast(minBarX)
        var barX = params.x
        if (barX < minBarX || barX > maxBarX) {
            barX = barX.coerceIn(minBarX, maxBarX)
            params.x = barX
            runCatching { windowManager.updateViewLayout(root, params) }
        }
        val ballX = barX + offset[0]
        val wantedBallY = params.y + offset[1]
        val ballY = BarLayout.clampBallY(wantedBallY, metrics.heightPixels, size)
        val deviation = intArrayOf(ballX - (barX + offset[0]), ballY - wantedBallY)

        collapsed = true
        ballView.visibility = View.VISIBLE
        ballView.pivotX = 0f
        ballView.pivotY = 0f
        ballView.scaleX = 0.3f
        ballView.scaleY = 0.3f
        ballView.alpha = 0f
        ballView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(COLLAPSE_ANIM_MS).start()

        barColumn.pivotX = 0f
        barColumn.pivotY = 0f
        barColumn.animate()
            .scaleX(0.15f).scaleY(0.15f).alpha(0f)
            .setDuration(COLLAPSE_ANIM_MS).start()

        animateWindow(params.x, params.y, barW, barH, ballX, ballY, size, size) {
            barColumn.visibility = View.GONE
            barColumn.alpha = 1f
            barColumn.scaleX = 1f
            barColumn.scaleY = 1f
            params.width = size
            params.height = size
            runCatching { windowManager.updateViewLayout(root, params) }
            savePosition()
        }
        Log.i(
            TAG,
            "控制条最小化：bar=($barX,${params.y}) -> ball=($ballX,$ballY)，" +
                "不变量偏差=${deviation[0]},${deviation[1]}px（clamp ${deviation[0] != 0 || deviation[1] != 0}）"
        )
    }

    /** W11.1/W11.2: grow back out of the ball, button first. */
    private fun expand() {
        if (!collapsed || !::root.isInitialized) return

        val metrics = resources.displayMetrics
        val size = ballSizePx()
        val offset = ballOffsetPx()
        val barW = if (expandedBarWidthPx > 0) expandedBarWidthPx else barWidthBudgetPx()
        val barH = if (expandedBarHeightPx > 0) expandedBarHeightPx else barHeightPx()

        // W11.5: derive the bar from the BALL, never from a stale bar_x/bar_y -
        // otherwise "drag the ball, then expand" would jump back to the old spot.
        val wantedX = params.x - offset[0]
        val wantedY = params.y - offset[1]
        val barX = OverlayGeometry.clampX(wantedX, metrics.widthPixels, barW)
        val barY = OverlayGeometry.clampY(wantedY, metrics.heightPixels, barH)

        collapsed = false
        barColumn.visibility = View.VISIBLE
        barColumn.pivotX = 0f
        barColumn.pivotY = 0f
        barColumn.scaleX = 0.15f
        barColumn.scaleY = 0.15f
        barColumn.alpha = 0f
        barColumn.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(COLLAPSE_ANIM_MS).start()

        ballView.animate().scaleX(0.3f).scaleY(0.3f).alpha(0f).setDuration(COLLAPSE_ANIM_MS).start()

        animateWindow(params.x, params.y, size, size, barX, barY, barW, barH) {
            ballView.visibility = View.GONE
            ballView.alpha = 1f
            ballView.scaleX = 1f
            ballView.scaleY = 1f
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            runCatching { windowManager.updateViewLayout(root, params) }
            cacheBallOffset()
            savePosition()
            renderLabel(lastState)
        }
        Log.i(
            TAG,
            "控制条展开：ball=(${params.x},${params.y}) -> bar=($barX,$barY)，" +
                "clamp 偏差=${barX - wantedX},${barY - wantedY}px"
        )
    }

    /** W11.3: end of a drag - clamp, then remember the position. */
    private fun finishDrag() {
        val metrics = resources.displayMetrics
        if (collapsed) {
            val size = ballSizePx()
            params.x = OverlayGeometry.clampX(params.x, metrics.widthPixels, size)
            params.y = BarLayout.clampBallY(params.y, metrics.heightPixels, size)
        } else {
            params.x = clampX(params.x)
            params.y = clampY(params.y)
        }
        runCatching { windowManager.updateViewLayout(root, params) }
        savePosition()
    }

    /** W14.2: the single place the window is actually moved during a drag. */
    private fun moveWindowBy(dx: Int, dy: Int) {
        params.x += dx
        params.y += dy
        runCatching { windowManager.updateViewLayout(root, params) }
    }

    /**
     * W14.2: the ball sits outside [DragBarLayout], so it carries its own
     * [DragGesture]. Returning false on ACTION_DOWN keeps the ball's onClick
     * (expand) alive; consuming the UP once the gesture became a drag keeps a
     * drag from also expanding the bar.
     */
    private val ballGesture: DragGesture by lazy {
        DragGesture(this).apply {
            onDragStart = {
                windowAnimator?.end()
                ballView.isPressed = false
            }
            onDrag = { dx, dy -> moveWindowBy(dx, dy) }
            onDragEnd = { finishDrag() }
        }
    }

    private val ballDragListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ballGesture.onDown(event)
                false
            }
            MotionEvent.ACTION_MOVE -> ballGesture.applyMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ballGesture.onUp()
            else -> false
        }
    }

    /**
     * W11.5: `bar_*` and `ball_*` are kept consistent by construction, so the
     * expand path never has to fall back to a stale bar position.
     */
    private fun savePosition() {
        val offset = ballOffsetPx()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
        if (collapsed) {
            prefs.putInt(KEY_BALL_X, params.x).putInt(KEY_BALL_Y, params.y)
            prefs.putInt(KEY_X, params.x - offset[0]).putInt(KEY_Y, params.y - offset[1])
        } else {
            prefs.putInt(KEY_X, params.x).putInt(KEY_Y, params.y)
            prefs.putInt(KEY_BALL_X, params.x + offset[0]).putInt(KEY_BALL_Y, params.y + offset[1])
        }
        prefs.apply()
    }

    private fun currentBarWidth(): Int =
        if (::root.isInitialized && root.width > 0) root.width
        else resources.displayMetrics.widthPixels

    private fun currentBarHeight(): Int =
        if (::root.isInitialized && root.height > 0) root.height else barHeightPx()

    /** Keeps the bar on screen so it can always be dragged back. */
    private fun clampX(x: Int): Int =
        OverlayGeometry.clampX(x, resources.displayMetrics.widthPixels, currentBarWidth())

    /** Keeps the bar fully on screen (position is otherwise the user's choice). */
    private fun clampY(y: Int): Int =
        OverlayGeometry.clampY(y, resources.displayMetrics.heightPixels, currentBarHeight())

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** Below the status bar / cutout, and clear of the instrument keys. */
    private fun defaultY(): Int {
        val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            insets.top
        } else {
            0
        }
        return bars + (8 * resources.displayMetrics.density).toInt()
    }

    // ── W5.1 song panel ───────────────────────────────────────────────

    private fun togglePanel() {
        if (panelOpen) closePanel() else openPanel()
    }

    private fun openPanel() {
        if (SongQueue.songs.value.isEmpty()) {
            titleView.text = "曲库为空"
            timeView.text = ""
            return
        }
        // The panel is a wide touchable window: any note injected under it would
        // be swallowed. Pause first so nothing is fired while it is open.
        if (PlaybackRuntime.engine.state.value is PlaybackState.Playing) {
            Log.i(TAG, "打开曲目面板：先暂停播放避免音符打到面板上")
            PlaybackRuntime.playback.pause()
        }
        panel = buildPanel()
        barColumn.addView(panel)
        // Pin the window width: with the transport bar hidden the remaining
        // WRAP_CONTENT children would otherwise collapse the panel.
        params.width = currentBarWidth().coerceAtLeast((320 * resources.displayMetrics.density).toInt())
        panelOpen = true
        page = 0
        query = ""
        // W8.2: the browse state touches NO window flags and never raises the
        // IME. The game keeps focus until the user actively taps the search box.
        searchMode = false
        enteringSearch = false
        applyPanelSizing()
        renderPage()
    }

    private fun closePanel() {
        panel?.let { barColumn.removeView(it) }
        panel = null
        panelRows = null
        panelScroll = null
        pageLabel = null
        searchStub = null
        searchField = null
        searchClear = null
        searchClose = null
        pagerRow = null
        searchMode = false
        enteringSearch = false
        barRow?.visibility = View.VISIBLE
        query = ""
        panelOpen = false
        // Back to a content-sized, draggable bar.
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        runCatching { windowManager.updateViewLayout(root, params) }
        // Hand focus back to Sky.
        setPanelFocusMode(false)
        renderLabel(lastState)
    }

    /**
     * Toggles [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE] on the overlay.
     *
     * Dropping the flag is the only way an EditText can receive keystrokes and
     * raise the IME - a non-focusable window gets neither. It is therefore kept
     * off (i.e. focusable) only while the song panel is open; the moment the
     * panel closes the flag goes back on and focus returns to the game.
     * `FLAG_NOT_TOUCH_MODAL` stays on so touches outside the bar still reach Sky.
     */
    private fun setPanelFocusMode(focusable: Boolean) {
        val notFocusable = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.flags = if (focusable) {
            params.flags and notFocusable.inv()
        } else {
            params.flags or notFocusable
        }
        params.softInputMode = if (focusable) {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
        runCatching { windowManager.updateViewLayout(root, params) }
        Log.i(TAG, "控制条焦点模式：focusable=$focusable")

        if (!focusable) {
            searchField?.clearFocus()
            runCatching {
                getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(root.windowToken, 0)
            }
        }
    }

    private fun buildPanel(): View {
        val density = resources.displayMetrics.density
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
        }

        // ── search row (W8.2 / W8.3) ──────────────────────────────────
        // Browse state shows a TextView that merely *looks* like a field: a real
        // EditText inside a FLAG_NOT_FOCUSABLE window cannot take focus, and on
        // some ROMs the first tap on one is simply swallowed. Tapping this stub
        // switches to search state, which swaps in the real EditText.
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
        }

        val stub = TextView(this).apply {
            text = "🔍 搜索曲名 / 文件名"
            textSize = 13f
            setTextColor(0xFF9AA0A6.toInt())
            maxLines = 1
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            setBackgroundColor(0x33FFFFFF)
            setOnClickListener { enterSearchMode() }
        }
        searchStub = stub
        searchRow.addView(stub, LinearLayout.LayoutParams(0, -2, 1f))

        val field = EditText(this).apply {
            hint = "搜索曲名 / 文件名"
            textSize = 13f
            setTextColor(0xFFE8EAED.toInt())
            setHintTextColor(0xFF9AA0A6.toInt())
            maxLines = 1
            isSingleLine = true
            isFocusable = true
            isFocusableInTouchMode = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            setBackgroundColor(0x33FFFFFF)
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    page = 0
                    renderPage()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
            // W8.3: BACK leaves search state only - it must not close the panel.
            // W24b: the window root now intercepts BACK before it gets here (it
            // sees the key first), so this is only a fallback if the root handler
            // is ever bypassed. Kept because it documents the original contract.
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                    event.action == android.view.KeyEvent.ACTION_UP
                ) {
                    exitSearchMode()
                    true
                } else {
                    false
                }
            }
        }
        searchField = field
        searchRow.addView(field, LinearLayout.LayoutParams(0, -2, 1f))

        val clear = compactButton("清除", 46, (32 * density).toInt()) {
            field.setText("")
            query = ""
            page = 0
            renderPage()
        }.apply { visibility = View.GONE }
        searchClear = clear
        searchRow.addView(clear)

        val closeSearch = compactButton("✕", 40, (32 * density).toInt()) { exitSearchMode() }
            .apply { visibility = View.GONE }
        searchClose = closeSearch
        searchRow.addView(closeSearch)

        column.addView(searchRow, LinearLayout.LayoutParams(-1, -2))

        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panelRows = rows

        // Capped at 40% of the screen so the panel stays in the upper band; when
        // the IME is up the budget is recomputed from the real inset instead.
        val maxHeight = (resources.displayMetrics.heightPixels * PANEL_MAX_FRACTION).toInt()
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(rows, LinearLayout.LayoutParams(-1, -2))
        }
        panelScroll = scroll
        column.addView(scroll, LinearLayout.LayoutParams(-1, maxHeight))

        val pager = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pager.addView(compactButton("◀ 上一页", 76, (32 * density).toInt()) {
            if (page > 0) {
                page--
                renderPage()
            }
        })
        pageLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFE8EAED.toInt())
            gravity = Gravity.CENTER
        }
        pager.addView(pageLabel, LinearLayout.LayoutParams(0, -2, 1f))
        pager.addView(compactButton("下一页 ▶", 76, (32 * density).toInt()) {
            val pages = pageCount()
            if (page < pages - 1) {
                page++
                renderPage()
            }
        })
        column.addView(pager, LinearLayout.LayoutParams(-1, -2))
        pagerRow = pager
        return column
    }

    /**
     * W8.1 "typing" layout.
     *
     * The landscape IME leaves only ~450px, and the transport bar (182px) is
     * useless while typing, so it is hidden to make room. The **pager row is NOT
     * hidden** (W8.5): the user must still be able to turn pages while a query
     * is active. Short results simply scroll.
     */
    private fun setSearchMode(active: Boolean) {
        if (searchMode == active) return
        searchMode = active
        barRow?.visibility = if (active) View.GONE else View.VISIBLE
        // Deliberately NOT hiding pagerRow here - see W8.5.
        applyPanelSizing()
        Log.i(TAG, "控制条搜索模式：$active")
    }

    /**
     * W8.3: browse -> search.
     *
     * Order matters. The window must become focusable *before* the EditText
     * exists/is focused, otherwise the first tap is swallowed on some ROMs.
     */
    private fun enterSearchMode() {
        if (enteringSearch || searchMode) return
        enteringSearch = true
        // Step 1: make the window focusable first. Doing this after creating the
        // EditText makes the first tap get swallowed on some ROMs.
        setPanelFocusMode(true)
        searchStub?.visibility = View.GONE
        searchField?.visibility = View.VISIBLE
        searchClear?.visibility = View.VISIBLE
        searchClose?.visibility = View.VISIBLE
        // Step 2: focus and raise the IME on a LATER frame. Focusing in the same
        // frame as the flag change is too early - the window has not finished
        // becoming focusable, and showSoftInput is silently ignored.
        root.postDelayed({
            val f = searchField
            if (f == null || !panelOpen) {
                enteringSearch = false
                return@postDelayed
            }
            f.requestFocus()
            runCatching {
                getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(f, InputMethodManager.SHOW_IMPLICIT)
            }
            // Re-assert SOFT_INPUT_STATE_VISIBLE now that the field has focus.
            runCatching { windowManager.updateViewLayout(root, params) }
            setSearchMode(f.hasFocus())
            enteringSearch = false
            applyPanelSizing()
        }, 200L)
    }

    /** W8.3: search -> browse. Focus must end up back on the game. */
    private fun exitSearchMode() {
        query = ""
        searchField?.apply {
            setText("")
            clearFocus()
        }
        runCatching {
            getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(root.windowToken, 0)
        }
        searchField?.visibility = View.GONE
        searchClear?.visibility = View.GONE
        searchClose?.visibility = View.GONE
        searchStub?.visibility = View.VISIBLE
        setSearchMode(false)
        setPanelFocusMode(false)
        enteringSearch = false
        page = 0
        renderPage()
        Log.i(TAG, "控制条退出搜索态")
    }

    /** Size the results viewport against the space the IME leaves behind. */
    private fun applyPanelSizing() {
        val scroll = panelScroll ?: return
        val density = resources.displayMetrics.density
        val screenH = resources.displayMetrics.heightPixels
        val available = if (availableHeightPx > 0) availableHeightPx else screenH

        val barH = if (searchMode) 0 else barHeightPx()
        val searchH = (44 * density).toInt()
        // W8.5: the pager keeps its space in BOTH states.
        val pagerH = (36 * density).toInt()
        val overhead = barH + searchH + pagerH + (10 * density).toInt()

        val budget = (available - overhead)
            .coerceAtLeast((28 * density).toInt())
            .coerceAtMost((screenH * PANEL_MAX_FRACTION).toInt())

        (scroll.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            if (lp.height != budget) {
                lp.height = budget
                scroll.layoutParams = lp
            }
        }
    }

    /**
     * Songs matching the current query, paired with their index in the full
     * queue so selection stays id-based (titles are not unique).
     */
    private fun filteredSongs(): List<Pair<Int, SongEntry>> =
        SongFilter.filter(SongQueue.songs.value, query)

    private fun pageCount(): Int = SongFilter.pageCount(filteredSongs().size, PAGE_SIZE)

    private fun renderPage() {
        val rows = panelRows ?: return
        val matched = filteredSongs()
        page = SongFilter.clampPage(page, matched.size, PAGE_SIZE)
        val pages = SongFilter.pageCount(matched.size, PAGE_SIZE)

        val filtering = query.isNotBlank()
        pageLabel?.text = if (filtering) {
            "匹配 ${matched.size} 首 · ${page + 1}/$pages 页"
        } else {
            "${page + 1}/$pages"
        }

        rows.removeAllViews()
        val slice = SongFilter.pageSlice(matched, page, PAGE_SIZE)
        val start = page * PAGE_SIZE
        val density = resources.displayMetrics.density
        val currentId = SongQueue.current()?.id

        if (slice.isEmpty()) {
            rows.addView(TextView(this).apply {
                text = "  没有匹配「${query.trim()}」的曲目"
                textSize = 12f
                setTextColor(0xFF9AA0A6.toInt())
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }

        slice.forEachIndexed { offset, (globalIndex, entry) ->
            val isCurrent = entry.id == currentId
            rows.addView(TextView(this).apply {
                // Numbered by position in the *filtered* result so the labels
                // match what the user is looking at.
                text = "${if (isCurrent) "▶" else " "} ${start + offset + 1}. ${entry.title} · ${fmt(entry.durationUs)}"
                textSize = 12f
                // W7.4: the panel is the *selection* surface, so seeing the whole
                // name matters. Titles run up to 99 characters (median 8), which
                // one line cannot hold.
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
                setTextColor(if (isCurrent) 0xFF7FD1B9.toInt() else 0xFFE8EAED.toInt())
                setOnClickListener { selectSong(globalIndex) }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        // The panel sits below the bar; re-clamp in case it now pushes off screen.
        root.post {
            params.y = clampY(params.y)
            runCatching { windowManager.updateViewLayout(root, params) }
        }
    }

    private fun refreshPanel() {
        if (panelOpen) renderPage()
    }

    /**
     * W8.4: selecting a song loads it but does NOT start playback.
     *
     * `closePanel()` needs 200-300ms to restore FLAG_NOT_FOCUSABLE and retract
     * the IME, while a song's first note is dispatched at t=0 - starting
     * immediately would fire that note onto the retracting panel/keyboard (the
     * same class of bug as the original "race to switch apps"). It also lets the
     * user confirm the new title on the bar before pressing ▶.
     */
    private fun selectSong(index: Int) {
        val entry: SongEntry = SongQueue.songs.value.getOrNull(index) ?: return
        Log.i(TAG, "控制条选曲（只载入不播放）：index=$index, title=${entry.title}")
        closePanel()
        SongQueue.loadOnly(index)
    }

    // ── playback wiring ───────────────────────────────────────────────

    private fun onPlayPauseClicked() {
        when (val state = PlaybackRuntime.engine.state.value) {
            is PlaybackState.Playing -> {
                Log.i(TAG, "控制条收到命令：Pause")
                PlaybackRuntime.playback.pause()
            }
            is PlaybackState.Paused -> {
                Log.i(TAG, "控制条收到命令：Resume")
                PlaybackRuntime.playback.resume()
            }
            is PlaybackState.Idle -> {
                // Nothing staged. If the library has songs, start the first one
                // from the top instead of failing with "请先导入曲谱".
                val first = SongQueue.songs.value.getOrNull(0)
                if (first == null) {
                    Log.d(TAG, "控制条：曲库为空，忽略播放")
                    titleView.text = "曲库为空"
            timeView.text = ""
                } else {
                    Log.i(TAG, "控制条收到命令：Play(首曲)")
                    SongQueue.loadAndPlay(0)
                    refreshPanel()
                }
            }
            else -> {
                // Ready / Error. Play and Resume share one engine branch and
                // continue from positionUs, so stop() first to truly restart.
                Log.i(TAG, "控制条收到命令：Stop+Play")
                PlaybackRuntime.playback.stop()
                PlaybackRuntime.playback.play()
            }
        }
    }

    private fun observeState() {
        scope.launch {
            PlaybackRuntime.engine.state.collect { state ->
                lastState = state
                lastStateAtNanos = SystemClock.elapsedRealtimeNanos()
                renderControls(state)
            }
        }
        // W1.1: hide the bar while the calibration anchors are up. The anchors are
        // touchable windows layered above this one, so a tap intended for "play"
        // would land on an anchor instead.
        // W21: and hide the whole overlay while one of our own activities is in
        // front, so the ball cannot sit on our cards or eat their taps.
        scope.launch { OverlayController.calibrating.collect { refreshOverlayVisibility() } }
        scope.launch { OverlayController.ownAppForeground.collect { refreshOverlayVisibility() } }
        // Library / selection changes must refresh the open panel.
        scope.launch {
            SongQueue.songs.collect { refreshPanel() }
        }
        scope.launch {
            SongQueue.currentIndex.collect { refreshPanel() }
        }
    }

    /**
     * W21: single place that decides whether the overlay window is on screen.
     *
     * `visibility = GONE` alone would NOT be enough: the window keeps its frame
     * and the input dispatcher keeps routing taps inside that rectangle to us,
     * where they die on an invisible view - the card underneath would still
     * never see them. `FLAG_NOT_TOUCHABLE` is what actually lets the tap fall
     * through, so the two always move together.
     *
     * W24: the flag now comes from [OverlayController.ownAppForeground] alone,
     * which MainActivity's lifecycle owns. The old `&& accessibilityConnected`
     * guard came from the days when the flag was inferred from accessibility
     * window events; it is gone, so hiding inside our own app works with the
     * accessibility service off too.
     */
    private fun refreshOverlayVisibility() {
        if (!::root.isInitialized) return
        val ownForeground = OverlayController.ownAppForeground.value
        val hide = OverlayController.calibrating.value || ownForeground

        root.visibility = if (hide) View.GONE else View.VISIBLE
        val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        params.flags = if (hide) {
            params.flags or notTouchable
        } else {
            params.flags and notTouchable.inv()
        }
        runCatching { windowManager.updateViewLayout(root, params) }
        // W24: log on change only. Logging every call turned the flicker - if it
        // ever comes back - into a log flood instead of a readable trace.
        if (lastHiddenLogged != hide) {
            lastHiddenLogged = hide
            Log.i(
                TAG,
                "浮层可见性：hidden=$hide（本应用前台=$ownForeground，" +
                    "校准中=${OverlayController.calibrating.value}）"
            )
        }
    }

    private fun renderControls(state: PlaybackState) {
        val playing = state is PlaybackState.Playing
        // W19: the play button is an icon now, so the state swap is an image +
        // tint change instead of a text change.
        playPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
        tint(playPause, if (playing) R.color.accent else R.color.text_primary)
        renderLabel(state)
    }

    private fun renderLabel(state: PlaybackState) {
        val positionUs = displayPositionUs()
        val (title, clock) = when (state) {
            is PlaybackState.Idle -> "未载入曲谱" to ""
            is PlaybackState.Error -> "错误：${state.message}" to ""
            is PlaybackState.Ready -> state.title to "${fmt(positionUs)} / ${fmt(state.durationUs)}"
            is PlaybackState.Playing -> state.title to "${fmt(positionUs)} / ${fmt(state.durationUs)}"
            // W10.3: 「（暂停）」 is no longer glued onto the title - it would eat
            // a quarter of the 140dp box. The state symbol moved to the clock.
            is PlaybackState.Paused -> state.title to "${fmt(positionUs)} / ${fmt(state.durationUs)}"
        }
        val status = when (state) {
            is PlaybackState.Playing -> "▶ "
            is PlaybackState.Paused -> "⏸ "
            else -> ""
        }
        fullTitle = title
        // W10.2: truncate first, then marquee - a 99-character name would need
        // roughly fifteen seconds per pass otherwise.
        val shown = BarLayout.marqueeText(title)
        if (titleView.text?.toString() != shown) {
            titleView.text = shown
            // Restart the marquee from the beginning on every title change.
            //
            // Toggling selection is what actually starts it: View.setSelected()
            // short-circuits when the value is unchanged, so the old
            // `isSelected = true` was a no-op (the flag was already true from
            // construction) and the marquee never ran at all. The post() also
            // guarantees the view is laid out, because TextView.startMarquee()
            // only fires once the text is wider than a non-zero viewport.
            titleView.post {
                titleView.isSelected = false
                titleView.isSelected = true
            }
        }
        val time = if (clock.isEmpty()) "" else "$status$clock"
        if (timeView.text?.toString() != time) timeView.text = time
        // W20: the ring rides on the same 200ms tick as the clock.
        renderBall(state)
    }

    /**
     * W20: collapses the playback state into "how full is the ring".
     *
     * The position comes from [displayPositionUs] - the locally interpolated
     * value - never from `PlaybackState.positionUs`, which only moves when a
     * note is dispatched and would make the arc jump.
     */
    private fun renderBall(state: PlaybackState) {
        val durationUs = when (state) {
            is PlaybackState.Ready -> state.durationUs
            is PlaybackState.Playing -> state.durationUs
            is PlaybackState.Paused -> state.durationUs
            else -> 0L
        }
        val mode = when (state) {
            is PlaybackState.Playing -> BallMode.PLAYING
            is PlaybackState.Paused -> BallMode.PAUSED
            // Idle, Error, Ready (fresh OR finished) all read as "no progress".
            else -> BallMode.IDLE
        }
        val progress = if (durationUs > 0L) {
            displayPositionUs().toFloat() / durationUs.toFloat()
        } else {
            0f
        }
        ballView.update(progress, mode)
    }

    /**
     * PlaybackState.positionUs only changes when a note or command is handled,
     * so using it directly makes the clock jump. Interpolate from the moment
     * the state arrived instead.
     */
    private fun displayPositionUs(): Long = when (val state = lastState) {
        is PlaybackState.Playing -> {
            val elapsedUs = (SystemClock.elapsedRealtimeNanos() - lastStateAtNanos) / 1_000L
            (state.positionUs + elapsedUs).coerceAtMost(state.durationUs)
        }
        is PlaybackState.Paused -> state.positionUs
        is PlaybackState.Ready -> state.positionUs
        else -> 0L
    }

    private fun fmt(us: Long): String {
        val totalSeconds = (us / 1_000_000L).coerceAtLeast(0L)
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    // ── foreground notification ───────────────────────────────────────

    private fun startForegroundIfNeeded() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Sky Auto Player")
            .setContentText("演奏控制条已开启")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * W11.3 drag state machine, W14.2 relocated (algorithm untouched).
     *
     * It used to live in the window-root container, which meant `onInterceptTouchEvent`
     * covered the whole window - including the song panel, whose ScrollView then
     * had its gestures stolen and the window moved instead of the list scrolling.
     * It is now a plain helper owned by exactly two things: [DragBarLayout] (the
     * layer that wraps only the bar row) and the ball's touch listener.
     *
     * Behaviour kept verbatim from W11.3: touches are stolen once they pass the
     * touch slop (the child button then receives ACTION_CANCEL, so a drag never
     * also fires a click), the first over-slop movement is discarded so the
     * window does not jump, the closing ACTION_UP of a drag is swallowed, and a
     * 250ms long press starts a drag too ("hold anywhere on the bar to drag").
     *
     * `rawX/rawY` incremental maths is kept on purpose: overlay windows use a
     * different coordinate space from the views, and absolute positioning is easy
     * to get wrong.
     */
    private class DragGesture(context: Context) {

        var onDragStart: (() -> Unit)? = null
        var onDrag: ((dx: Int, dy: Int) -> Unit)? = null
        var onDragEnd: (() -> Unit)? = null

        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val handler = Handler(Looper.getMainLooper())

        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f

        var dragging = false
            private set

        private val longPress = Runnable { beginDrag() }

        fun beginDrag() {
            if (dragging) return
            dragging = true
            onDragStart?.invoke()
        }

        private fun pastSlop(event: MotionEvent): Boolean =
            abs(event.rawX - downRawX) > slop || abs(event.rawY - downRawY) > slop

        fun onDown(event: MotionEvent) {
            downRawX = event.rawX
            downRawY = event.rawY
            lastRawX = event.rawX
            lastRawY = event.rawY
            dragging = false
            handler.postDelayed(longPress, LONG_PRESS_MS)
        }

        /** Intercept path: has this gesture become a drag the parent must steal? */
        fun shouldSteal(event: MotionEvent): Boolean {
            if (!dragging && pastSlop(event)) {
                // Drop the slop movement itself so the window does not jump by
                // ~8px the moment the drag starts.
                lastRawX = event.rawX
                lastRawY = event.rawY
                beginDrag()
            }
            return dragging
        }

        /** Touch-listener path: apply the movement once the drag has started. */
        fun applyMove(event: MotionEvent): Boolean {
            if (!dragging && pastSlop(event)) {
                lastRawX = event.rawX
                lastRawY = event.rawY
                beginDrag()
            }
            if (!dragging) return false
            onDrag?.invoke(
                (event.rawX - lastRawX).toInt(),
                (event.rawY - lastRawY).toInt()
            )
            lastRawX = event.rawX
            lastRawY = event.rawY
            return true
        }

        /** @return true when the closing event was consumed, i.e. no click may fire. */
        fun onUp(): Boolean {
            handler.removeCallbacks(longPress)
            if (!dragging) return false
            dragging = false
            onDragEnd?.invoke()
            return true
        }
    }

    /**
     * W14.2: the intercept layer now wraps ONLY the bar row.
     *
     * [com.skyautoplayer.overlay.PlaybackOverlayService.openPanel] adds the song
     * panel to `barColumn`, which makes it this view's *sibling* - so a swipe
     * inside the panel's ScrollView never reaches [onInterceptTouchEvent] and can
     * only ever scroll the list.
     */
    private class DragBarLayout(
        context: Context,
        private val gesture: DragGesture
    ) : FrameLayout(context) {

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gesture.onDown(ev)
                    return false
                }
                MotionEvent.ACTION_MOVE -> return gesture.shouldSteal(ev)
                // Swallows the UP when the gesture became a drag, so the button
                // under the finger does not also fire a click.
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return gesture.onUp()
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    gesture.applyMove(event)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    gesture.onUp()
                    return true
                }
            }
            // Claim the gesture even on empty padding, otherwise the container
            // never becomes the touch target and MOVEs would not reach us (the
            // ViewGroup only gets onInterceptTouchEvent while it has a target).
            return true
        }
    }

    /**
     * W24b: the overlay's window root.
     *
     * Its only job is BACK. The search field used to own the BACK key listener,
     * which only fires while that EditText happens to hold focus - tapping the
     * list or the title first left the window focusable with no way out, so the
     * overlay kept stealing the game's key focus. Handling the key at the root
     * catches every delivery path (the root is asked before the focused child),
     * and returning false in browse state leaves BACK available to the game.
     */
    private class OverlayRootLayout(context: Context) : FrameLayout(context) {

        /** @param down true for ACTION_DOWN; return true to consume the event. */
        var onBackPressed: ((down: Boolean) -> Boolean)? = null

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK &&
                onBackPressed?.invoke(event.action == KeyEvent.ACTION_DOWN) == true
            ) {
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    /** W20.4: what the ring should express. */
    private enum class BallMode { IDLE, PLAYING, PAUSED }

    /**
     * W20: the floating ball, now a real progress ring.
     *
     * Four layers, bottom to top: translucent dark disc -> grey track ring ->
     * the progress arc -> the centre icon. The arc starts at 12 o'clock and its
     * sweep *shrinks* as the song plays, so the grey track grows behind it.
     *
     * `useCenter = false` is what makes it a ring rather than a pie slice.
     * Nothing is allocated in [onDraw] - all Paints and both Rects are fields.
     */
    private inner class BallProgressView(context: Context) : View(context) {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xF0202124.toInt()
        }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = 0xFF8A9099.toInt()
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val arcRect = RectF()

        private val noteIcon: Drawable? = getDrawable(R.drawable.ic_music_note)
        private val pauseIcon: Drawable? = getDrawable(R.drawable.ic_pause)

        /** Kept across collapse/expand - the animations only scale the view. */
        private var progress = 0f
        private var mode = BallMode.IDLE

        fun update(nextProgress: Float, nextMode: BallMode) {
            val clamped = nextProgress.coerceIn(0f, 1f)
            if (clamped == progress && nextMode == mode) return
            progress = clamped
            mode = nextMode
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val density = resources.displayMetrics.density
            val stroke = BALL_RING_STROKE_DP * density
            trackPaint.strokeWidth = stroke
            progressPaint.strokeWidth = stroke
            // Stay inside the 48dp touch target: 2dp of breathing room plus half
            // the stroke, so the ring is drawn at ~44dp.
            val inset = 2 * density + stroke / 2f
            arcRect.set(inset, inset, w - inset, h - inset)
        }

        override fun onDraw(canvas: Canvas) {
            if (arcRect.isEmpty) return
            val radius = arcRect.width() / 2f
            val cx = arcRect.centerX()
            val cy = arcRect.centerY()
            canvas.drawCircle(cx, cy, radius, fillPaint)
            canvas.drawCircle(cx, cy, radius, trackPaint)
            // W20.4: IDLE draws no arc at all (fresh / finished song), while a
            // just-started PLAYING song draws the full ring and unwinds from
            // there - hence the mode check rather than a progress check.
            if (mode != BallMode.IDLE) {
                progressPaint.color = ringColor()
                canvas.drawArc(
                    arcRect,
                    BarLayout.ringStartAngle(progress),
                    BarLayout.ringSweepAngle(progress),
                    false,
                    progressPaint
                )
            }
            drawCentreIcon(canvas)
        }

        private fun ringColor(): Int =
            if (mode == BallMode.PAUSED) getColor(R.color.accent_dim) else getColor(R.color.accent)

        private fun drawCentreIcon(canvas: Canvas) {
            val icon = (if (mode == BallMode.PAUSED) pauseIcon else noteIcon) ?: return
            val size = (BALL_ICON_DP * resources.displayMetrics.density).roundToInt()
            val half = size / 2
            val cx = width / 2
            val cy = height / 2
            icon.setBounds(cx - half, cy - half, cx + half, cy + half)
            icon.setTint(
                if (mode == BallMode.PAUSED) getColor(R.color.accent_dim) else getColor(R.color.text_primary)
            )
            icon.draw(canvas)
        }
    }

    companion object {
        private const val TAG = "AutoPlay"
        private const val CHANNEL = "sky_playback"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS = "overlay_ui"
        private const val KEY_X = "bar_x"
        private const val KEY_Y = "bar_y"
        private const val KEY_BALL_X = "ball_x"
        private const val KEY_BALL_Y = "ball_y"
        private const val TICK_MS = 200L
        private const val PAGE_SIZE = 8
        private const val PANEL_MAX_FRACTION = 0.40f
        private const val COLLAPSE_ANIM_MS = 150L
        private const val LONG_PRESS_MS = 250L

        /** W20: ring geometry. */
        private const val BALL_RING_STROKE_DP = 3
        private const val BALL_ICON_DP = 22
    }
}
