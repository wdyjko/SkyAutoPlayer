package com.skyautoplayer.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.skyautoplayer.accessibility.PlayerAccessibilityService
import com.skyautoplayer.calibration.CalibrationProfile
import com.skyautoplayer.calibration.DisplaySnapshot
import com.skyautoplayer.calibration.Insets
import com.skyautoplayer.calibration.NormalizedPoint
import com.skyautoplayer.gesture.GestureCompletion
import com.skyautoplayer.gesture.GestureRequest
import com.skyautoplayer.storage.SharedPreferencesCalibrationStore

/** System overlay calibration surface. Only the anchor and control windows are touchable. */
class CalibrationOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var store: SharedPreferencesCalibrationStore
    private lateinit var displaySnapshot: DisplaySnapshot
    private lateinit var contentBounds: Rect
    private lateinit var profileId: String
    private lateinit var points: MutableList<NormalizedPoint>
    private val anchorViews = mutableListOf<AnchorView>()
    private val anchorParams = mutableListOf<WindowManager.LayoutParams>()
    private var controls: View? = null
    private var controlParams: WindowManager.LayoutParams? = null
    private var background: View? = null
    private val anchorSizePx by lazy { (64 * resources.displayMetrics.density).toInt().coerceAtLeast(56) }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        store = SharedPreferencesCalibrationStore(this)
        displaySnapshot = createDisplaySnapshot()
        contentBounds = displaySnapshot.contentBounds
        profileId = "display-${displaySnapshot.displayId}"
        val old = store.load(profileId)
        points = (old?.normalizedKeyPoints ?: defaultPoints()).toMutableList()
        OverlayController.setCalibrating(true)   // W1.1: hide the playback bar
        startForegroundIfNeeded()
        addBackgroundWindow()
        addAnchorWindows()
        addControlWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * W1.2: the anchors are placed at pixel positions computed from the display
     * bounds captured in [onCreate]. Rotating the device does not move overlay
     * windows, so without reprojection the whole grid drifts off screen - and
     * saving then persists garbage coordinates. `points` is normalised, so it
     * only needs re-projecting against the new bounds.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::points.isInitialized) return
        displaySnapshot = createDisplaySnapshot()
        contentBounds = displaySnapshot.contentBounds
        reprojectAnchors()
        relayoutControls()
    }

    override fun onDestroy() {
        OverlayController.setCalibrating(false)
        removeAllWindows()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addBackgroundWindow() {
        val view = View(this)
        background = view
        val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager.addView(view, params) }
    }

    private fun addAnchorWindows() {
        points.forEachIndexed { index, point ->
            val view = AnchorView(index)
            val params = overlayParams(anchorSizePx, anchorSizePx)
            val center = pointToPixels(point)
            params.x = center.x.toInt() - anchorSizePx / 2
            params.y = center.y.toInt() - anchorSizePx / 2
            anchorViews += view
            anchorParams += params
            runCatching { windowManager.addView(view, params) }
        }
    }

    private fun addControlWindow() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 4, 8, 4)
            setBackgroundColor(0xDD202124.toInt())
        }
        row.addView(Button(this).apply {
            text = "测试点击"
            setOnClickListener { testClick() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "保存"
            setOnClickListener { saveAndClose() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "退出校准"
            setOnClickListener { stopSelf() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        controls = row
        val params = overlayParams((contentBounds.width() * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = contentBounds.bottom / 20
        }
        controlParams = params
        runCatching { windowManager.addView(row, params) }
    }

    /** Re-seat the bottom button row after the bounds changed (rotation). */
    private fun relayoutControls() {
        val view = controls ?: return
        val params = controlParams ?: return
        params.width = (contentBounds.width() * 0.92f).toInt()
        params.y = contentBounds.bottom / 20
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /** Re-project the normalised anchors onto the current content bounds. */
    private fun reprojectAnchors() {
        anchorViews.forEachIndexed { index, view ->
            val params = anchorParams[index]
            val center = pointToPixels(points[index])
            params.x = center.x.toInt() - anchorSizePx / 2
            params.y = center.y.toInt() - anchorSizePx / 2
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun saveAndClose() {
        // W1.3: Sky only shows its keyboard in landscape. Calibrating in portrait
        // means the user is aligning against an app screen that has no keys, and
        // the result would overwrite the good landscape profile. Refuse, loudly.
        val orientation = displaySnapshot.orientation
        val landscape = orientation == Surface.ROTATION_90 || orientation == Surface.ROTATION_270
        if (!landscape) {
            Toast.makeText(
                this,
                "请在光遇（横屏）内校准：当前是竖屏，保存的坐标在游戏里会错位",
                Toast.LENGTH_LONG
            ).show()
            Log.d(TAG, "拒绝保存校准：orientation=$orientation（竖屏）")
            return
        }

        val now = System.currentTimeMillis()
        val old = store.load(profileId)
        store.save(
            CalibrationProfile(
                profileId = profileId,
                displayId = displaySnapshot.displayId,
                orientation = displaySnapshot.orientation,
                // W1.4: the panel's natural orientation is portrait on a phone.
                // Previously this was set to the *current* orientation, which
                // made the field meaningless.
                naturalOrientation = Surface.ROTATION_0,
                displayWidthPx = displaySnapshot.widthPx,
                displayHeightPx = displaySnapshot.heightPx,
                contentBounds = contentBounds,
                normalizedKeyPoints = points.toList(),
                createdAt = old?.createdAt ?: now,
                updatedAt = now
            )
        )
        Log.d(TAG, "校准已保存：orientation=$orientation, updatedAt=$now")
        // W1.7: hand over to the in-game control bar so the user does not have to
        // go back to the main screen and re-enter the game. Start the new
        // foreground service *before* stopping this one, otherwise there is a
        // window with no foreground service at all.
        startForegroundService(Intent(this, PlaybackOverlayService::class.java))
        stopSelf()
    }

    /**
     * W1.6: the injected tap obeys the same window hit-testing as a real finger,
     * so with the anchors touchable it lands on anchor #1 itself and measures
     * nothing. Make the anchors untouchable for the duration of the injection.
     */
    private fun testClick() {
        val service = PlayerAccessibilityService.instance ?: return
        val point = points.firstOrNull()?.let(::pointToPixels) ?: return
        setAnchorsTouchable(false)
        service.dispatch(GestureRequest(System.nanoTime(), setOf(0), listOf(point), 60_000, System.nanoTime())) { _: GestureCompletion -> }
        Handler(Looper.getMainLooper()).postDelayed({ setAnchorsTouchable(true) }, 300L)
    }

    private fun setAnchorsTouchable(touchable: Boolean) {
        anchorViews.forEachIndexed { index, view ->
            val params = anchorParams[index]
            params.flags = if (touchable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun updateAnchor(index: Int, dx: Float, dy: Float) {
        val params = anchorParams[index]
        params.x += dx.toInt()
        params.y += dy.toInt()
        runCatching { windowManager.updateViewLayout(anchorViews[index], params) }
        val centerX = params.x + anchorSizePx / 2f
        val centerY = params.y + anchorSizePx / 2f
        points[index] = NormalizedPoint(
            ((centerX - contentBounds.left) / contentBounds.width().toFloat()).coerceIn(0f, 1f),
            ((centerY - contentBounds.top) / contentBounds.height().toFloat()).coerceIn(0f, 1f)
        )
    }

    private fun pointToPixels(point: NormalizedPoint) = PointF(
        contentBounds.left + point.x * contentBounds.width(),
        contentBounds.top + point.y * contentBounds.height()
    )

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun removeAllWindows() {
        anchorViews.forEach { runCatching { windowManager.removeView(it) } }
        controls?.let { runCatching { windowManager.removeView(it) } }
        background?.let { runCatching { windowManager.removeView(it) } }
        anchorViews.clear()
        anchorParams.clear()
        controls = null
        controlParams = null
        background = null
    }

    private fun defaultPoints() = List(15) { i ->
        NormalizedPoint((i % 5 + .5f) / 5f, .55f + (i / 5) * .12f)
    }

    private fun createDisplaySnapshot(): DisplaySnapshot {
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        val metrics = resources.displayMetrics
        val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
            )
            Insets(insets.left, insets.top, insets.right, insets.bottom)
        } else Insets()
        val bounds = Rect(bars.left, bars.top, metrics.widthPixels - bars.right, metrics.heightPixels - bars.bottom)
        return DisplaySnapshot(display.displayId, display.rotation, metrics.widthPixels, metrics.heightPixels, bounds, bars, bars, metrics.densityDpi)
    }

    private fun startForegroundIfNeeded() {
        val channelId = "calibration_overlay"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channelId, "Calibration", NotificationManager.IMPORTANCE_LOW))
        startForeground(1002, Notification.Builder(this, channelId)
            .setContentTitle("Sky Auto Player")
            .setContentText("校准悬浮窗已开启")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build())
    }

    private inner class AnchorView(private val index: Int) : View(this@CalibrationOverlayService) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var lastX = 0f
        private var lastY = 0f

        init {
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.rawX
                        lastY = event.rawY
                        invalidate()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        updateAnchor(index, event.rawX - lastX, event.rawY - lastY)
                        lastX = event.rawX
                        lastY = event.rawY
                        invalidate()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        invalidate()
                        true
                    }
                    else -> false
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            paint.color = 0xDD00D9FF.toInt()
            canvas.drawCircle(width / 2f, height / 2f, width / 2f - 3f, paint)
            paint.color = Color.BLACK
            paint.textSize = 18f * resources.displayMetrics.density
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText((index + 1).toString(), width / 2f, height / 2f + paint.textSize / 3f, paint)
        }
    }

    private companion object {
        const val TAG = "AutoPlay"
    }
}
