package com.skyautoplayer.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared switch between the two overlays.
 *
 * The calibration anchors are touchable windows that sit on top of the control
 * bar. While calibration is running the control bar is hidden, otherwise a tap
 * meant for "play" would land on an anchor and be swallowed.
 */
object OverlayController {
    private val _calibrating = MutableStateFlow(false)
    val calibrating: StateFlow<Boolean> = _calibrating

    fun setCalibrating(value: Boolean) {
        _calibrating.value = value
    }

    /**
     * W21: is one of *our own* activities the current foreground window?
     *
     * While true the in-game overlay (bar + ball) hides itself, otherwise the
     * ball sits on top of our own cards and - being a touchable window -
     * swallows taps meant for them.
     *
     * Defaults to `false` (overlay visible): if the accessibility service is
     * disconnected nobody updates it, and the fallback must always be "show"
     * rather than "hide forever".
     */
    private val _ownAppForeground = MutableStateFlow(false)
    val ownAppForeground: StateFlow<Boolean> = _ownAppForeground

    fun setOwnAppForeground(value: Boolean) {
        _ownAppForeground.value = value
    }
}
