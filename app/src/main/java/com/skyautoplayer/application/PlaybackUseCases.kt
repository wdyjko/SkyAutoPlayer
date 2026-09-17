package com.skyautoplayer.application

import com.skyautoplayer.domain.playback.PlaybackCommand
import com.skyautoplayer.domain.timeline.SongTimeline
import com.skyautoplayer.playback.PlaybackEngine

class PlaybackUseCases(private val engine: PlaybackEngine) {
    fun load(timeline: SongTimeline) = engine.load(timeline)
    fun play() = engine.play()
    fun pause() = engine.pause()
    fun resume() = engine.send(PlaybackCommand.Resume)
    fun stop() = engine.stop()
    fun seek(positionUs: Long) = engine.send(PlaybackCommand.Seek(positionUs))
    fun setSpeed(speed: Double) = engine.send(PlaybackCommand.SetSpeed(speed))
}
