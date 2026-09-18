package com.skyautoplayer.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import com.skyautoplayer.calibration.CoordinateTransformer
import com.skyautoplayer.calibration.DisplaySnapshot
import com.skyautoplayer.application.PlaybackRuntime
import com.skyautoplayer.gesture.*
import com.skyautoplayer.storage.CalibrationStore

class PlayerAccessibilityService : AccessibilityService() {
    /**
     * 本服务不参与"浮层可见性"的判定 —— 见 OverlayController.ownAppForeground：
     * 该状态由 MainActivity 的生命周期驱动（`onResume` / `onPause`）。
     * 用窗口事件判定是不成立的：浮层窗口本身属于本包，隐藏浮层又会触发新的窗口状态变化，
     * 会造成 hide/show 无限振荡。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "AccessibilityService 已连接")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        Log.e(TAG, "AccessibilityService 已断开")
        PlaybackRuntime.accessibilityDisconnected()
        super.onDestroy()
    }

    fun dispatch(request: GestureRequest, callback: (GestureCompletion) -> Unit): DispatchSubmission {
        if (request.points.isEmpty() || request.points.size != request.keys.size) return DispatchSubmission.Invalid("points must match keys")
        val builder = GestureDescription.Builder()
        val durationMs = (request.holdUs / 1_000L).coerceAtLeast(1L)
        request.points.forEach { point ->
            val path = Path().apply { moveTo(point.x, point.y) }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
        }
        // One platform dispatch containing every stroke preserves chord simultaneity.
        Log.d(TAG, "DIAG dispatchGesture: requestId=${request.requestId}, keys=${request.keys}, points=${request.points.size}, durationMs=$durationMs")
        val accepted = dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { Log.d(TAG, "DIAG dispatchGesture completed: ${request.requestId}"); callback(GestureCompletion.Completed) }
            override fun onCancelled(gestureDescription: GestureDescription?) { Log.e(TAG, "DIAG dispatchGesture cancelled: ${request.requestId}"); callback(GestureCompletion.Cancelled) }
        }, null)
        Log.d(TAG, "DIAG dispatchGesture accepted=$accepted, requestId=${request.requestId}")
        return if (accepted) DispatchSubmission.Accepted else DispatchSubmission.Rejected("Android rejected gesture")
    }

    companion object { @Volatile var instance: PlayerAccessibilityService? = null }
}

class AndroidGestureSink(private val resolvedPoints: () -> List<PointF>) : GestureSink {
    constructor(store: CalibrationStore, profileId: String, display: () -> DisplaySnapshot) : this({
        val snapshot = display()
        Log.d(TAG, "DIAG snapshot: displayId=${snapshot.displayId} orientation=${snapshot.orientation} " +
            "size=${snapshot.widthPx}x${snapshot.heightPx} contentBounds=${snapshot.contentBounds} " +
            "barInsets=${snapshot.systemBarInsets} densityDpi=${snapshot.densityDpi}")
        val profile = store.load(profileId)
        if (profile == null) {
            Log.e(TAG, "DIAG snapshot: profile '$profileId' NOT FOUND")
        } else {
            Log.d(TAG, "DIAG profile: profileId=${profile.profileId} displayId=${profile.displayId} " +
                "orientation=${profile.orientation} size=${profile.displayWidthPx}x${profile.displayHeightPx} " +
                "profileBounds=${profile.contentBounds} barInsets=${profile.systemBarInsets}")
        }
        profile?.let { CoordinateTransformer().resolve(it, snapshot) } ?: emptyList()
    })

    override fun dispatchChord(request: GestureRequest, onCompletion: (GestureCompletion) -> Unit): DispatchSubmission {
        val service = PlayerAccessibilityService.instance
        if (service == null) {
            Log.e(TAG, "无法派发手势：AccessibilityService.instance == null")
            return DispatchSubmission.Unavailable
        }
        val keyPoints = resolvedPoints()
        Log.d(TAG, "DIAG sink: keys=${request.keys} resolvedCount=${keyPoints.size} first=${keyPoints.firstOrNull()}")
        val resolved = request.copy(points = request.keys.sorted().mapNotNull { keyPoints.getOrNull(it) })
        if (resolved.points.size != request.keys.size) {
            Log.e(TAG, "DIAG sink: DISTINCT profile missing/incomplete - keys=${request.keys} resolved=${resolved.points.size}")
            return DispatchSubmission.Invalid("calibration profile is missing or incomplete")
        }
        Log.d(TAG, "DIAG sink: resolvedPoints=${resolved.points}")
        return service.dispatch(resolved, onCompletion)
    }
}

private const val TAG = "AutoPlay"
