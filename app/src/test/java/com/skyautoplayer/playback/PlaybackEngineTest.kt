package com.skyautoplayer.playback

import com.skyautoplayer.domain.playback.PlaybackState
import com.skyautoplayer.domain.timeline.SongTimeline
import com.skyautoplayer.domain.timeline.TimelineEvent
import com.skyautoplayer.gesture.DispatchSubmission
import com.skyautoplayer.gesture.GestureCompletion
import com.skyautoplayer.gesture.GestureRequest
import com.skyautoplayer.gesture.GestureSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEngineTest {
    @Test
    fun `pause and play resume from absolute song position`() = runTest {
        var nowNanos = 0L
        val engine = PlaybackEngine(CompletingSink(), MonotonicClock { nowNanos }, this)
        engine.load(song(events = listOf(TimelineEvent(1_000_000, setOf(0), 10_000))))
        runCurrent()
        engine.play()
        runCurrent()

        nowNanos = 250_000_000
        engine.pause()
        advanceTimeBy(10)
        runCurrent()
        assertEquals(250_000, assertIs<PlaybackState.Paused>(engine.state.value).positionUs)

        engine.play()
        runCurrent()
        nowNanos = 400_000_000
        engine.pause()
        advanceTimeBy(10)
        runCurrent()
        assertEquals(400_000, assertIs<PlaybackState.Paused>(engine.state.value).positionUs)

        engine.stop()
        runCurrent()
        assertEquals(0, assertIs<PlaybackState.Ready>(engine.state.value).positionUs)
        engine.close()
    }

    @Test
    fun `events at the same timestamp are submitted as one chord`() = runTest {
        val sink = CompletingSink()
        val engine = PlaybackEngine(sink, MonotonicClock { 0L }, this)
        engine.load(song(events = listOf(
            TimelineEvent(0, setOf(0, 4), 30_000),
            TimelineEvent(0, setOf(4, 9), 60_000)
        )))
        runCurrent()
        engine.play()
        runCurrent()

        assertEquals(1, sink.requests.size)
        assertEquals(setOf(0, 4, 9), sink.requests.single().keys)
        assertEquals(60_000, sink.requests.single().holdUs)
        engine.close()
    }

    private fun song(events: List<TimelineEvent>) = SongTimeline(
        timelineId = "test",
        title = "Test song",
        durationUs = 2_000_000,
        events = events
    )

    private class CompletingSink : GestureSink {
        val requests = mutableListOf<GestureRequest>()

        override fun dispatchChord(
            request: GestureRequest,
            onCompletion: (GestureCompletion) -> Unit
        ): DispatchSubmission {
            requests += request
            onCompletion(GestureCompletion.Completed)
            return DispatchSubmission.Accepted
        }
    }
}
