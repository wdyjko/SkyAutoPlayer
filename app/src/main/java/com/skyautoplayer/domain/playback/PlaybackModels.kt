package com.skyautoplayer.domain.playback

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Ready(val title: String, val positionUs: Long, val durationUs: Long) : PlaybackState
    data class Playing(val title: String, val positionUs: Long, val durationUs: Long, val speed: Double) : PlaybackState
    data class Paused(val title: String, val positionUs: Long, val durationUs: Long, val speed: Double) : PlaybackState
    data class Error(val message: String, val cause: Throwable? = null) : PlaybackState
}

sealed interface PlaybackCommand {
    data class Load(val timeline: com.skyautoplayer.domain.timeline.SongTimeline) : PlaybackCommand
    data object Play : PlaybackCommand
    data object Pause : PlaybackCommand
    data object Resume : PlaybackCommand
    data object Stop : PlaybackCommand
    data class Seek(val positionUs: Long) : PlaybackCommand
    data class SetSpeed(val speed: Double) : PlaybackCommand
    data class GestureCompleted(val requestId: Long) : PlaybackCommand
    data class GestureCancelled(val requestId: Long, val reason: String? = null) : PlaybackCommand
    data object ServiceDisconnected : PlaybackCommand
}
