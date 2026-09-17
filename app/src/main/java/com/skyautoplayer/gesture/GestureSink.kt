package com.skyautoplayer.gesture

import android.graphics.PointF

data class GestureRequest(
    val requestId: Long,
    val keys: Set<Int>,
    val points: List<PointF>,
    val holdUs: Long,
    val eventAtUs: Long
)

sealed interface DispatchSubmission {
    data object Accepted : DispatchSubmission
    data class Rejected(val reason: String? = null) : DispatchSubmission
    data object Unavailable : DispatchSubmission
    data class Invalid(val reason: String) : DispatchSubmission
}

enum class GestureCompletion { Completed, Cancelled, TimedOut, ServiceDisconnected }

interface GestureSink {
    fun dispatchChord(request: GestureRequest, onCompletion: (GestureCompletion) -> Unit): DispatchSubmission
}
