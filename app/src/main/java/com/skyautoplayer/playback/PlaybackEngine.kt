package com.skyautoplayer.playback

import android.util.Log
import com.skyautoplayer.domain.playback.*
import com.skyautoplayer.domain.timeline.SongTimeline
import com.skyautoplayer.gesture.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun interface MonotonicClock { fun nowNanos(): Long }

class PlaybackEngine(
    private val sink: GestureSink,
    private val clock: MonotonicClock = MonotonicClock { android.os.SystemClock.elapsedRealtimeNanos() },
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val commands = Channel<PlaybackCommand>(Channel.UNLIMITED)
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state
    private val actor = scope.launch { runActor() }

    fun send(command: PlaybackCommand) {
        debug("收到命令：$command")
        commands.trySend(command)
    }
    fun load(timeline: SongTimeline) = send(PlaybackCommand.Load(timeline))
    fun play() = send(PlaybackCommand.Play)
    fun pause() = send(PlaybackCommand.Pause)
    fun stop() = send(PlaybackCommand.Stop)
    fun close() { actor.cancel() }

    private suspend fun runActor() {
        var timeline: SongTimeline? = null
        var index = 0
        var positionUs = 0L
        var speed = 1.0
        var playing = false
        var anchorClock = 0L
        var anchorSong = 0L
        var generation = 0L
        var pendingRequestId: Long? = null
        var pendingTimeoutNanos = 0L
        var consecutiveCancellations = 0
        while (currentCoroutineContext().isActive) {
            val command = if (!playing) commands.receive() else commands.tryReceive().getOrNull()
            if (command != null) {
                when (command) {
                    is PlaybackCommand.Load -> { timeline = command.timeline; index = 0; positionUs = 0; playing = false; pendingRequestId = null; consecutiveCancellations = 0; debug("加载曲谱：title=${command.timeline.title}, events=${command.timeline.events.size}, durationUs=${command.timeline.durationUs}"); _state.value = PlaybackState.Ready(command.timeline.title, 0, command.timeline.durationUs) }
                    PlaybackCommand.Play, PlaybackCommand.Resume -> { timeline?.let { if (it.events.isEmpty()) { error("播放拒绝：SongTimeline.events 为空"); _state.value = PlaybackState.Error("曲谱没有可播放事件") } else { anchorClock = clock.nowNanos(); anchorSong = positionUs; playing = true; consecutiveCancellations = 0; generation++; debug("play() 已开始：title=${it.title}, events=${it.events.size}, positionUs=$positionUs"); _state.value = PlaybackState.Playing(it.title, positionUs, it.durationUs, speed) } } ?: run { error("play() 被调用但没有加载 SongTimeline"); _state.value = PlaybackState.Error("请先导入曲谱") } }
                    PlaybackCommand.Pause -> { if (playing) { positionUs = currentPosition(anchorClock, anchorSong, speed).coerceAtMost(timeline?.durationUs ?: Long.MAX_VALUE); playing = false; _state.value = timeline?.let { PlaybackState.Paused(it.title, positionUs, it.durationUs, speed) } ?: PlaybackState.Idle; generation++ } }
                    PlaybackCommand.Stop -> { playing = false; positionUs = 0; index = 0; pendingRequestId = null; consecutiveCancellations = 0; generation++; _state.value = timeline?.let { PlaybackState.Ready(it.title, 0, it.durationUs) } ?: PlaybackState.Idle }
                    is PlaybackCommand.Seek -> { positionUs = command.positionUs.coerceAtLeast(0); index = timeline?.events?.indexOfFirst { it.atUs >= positionUs }?.coerceAtLeast(0) ?: 0; pendingRequestId = null; if (playing) { anchorClock = clock.nowNanos(); anchorSong = positionUs }; generation++ }
                    is PlaybackCommand.SetSpeed -> { if (command.speed > 0) { if (playing) { positionUs = currentPosition(anchorClock, anchorSong, speed); anchorClock = clock.nowNanos(); anchorSong = positionUs }; speed = command.speed } }
                    is PlaybackCommand.GestureCompleted -> if (command.requestId == pendingRequestId) {
                        pendingRequestId = null
                        consecutiveCancellations = 0
                    }
                    is PlaybackCommand.GestureCancelled -> if (command.requestId == pendingRequestId) {
                        // A cancelled injection is TRANSIENT, not a song failure.
                        //
                        // On Android 14/15 the framework cancels an in-flight injected
                        // gesture as soon as a real touch reaches the screen. The player
                        // touches the overlay (minimise, drag, swipe) while the song runs,
                        // so a cancellation says nothing about whether the song can
                        // continue. Treating it as fatal stopped the whole song and showed
                        // "Error: Cancelled" - seen on Android 14/15 devices, while an
                        // Android 16 device never cancelled and therefore worked.
                        //
                        // index/positionUs were already advanced when this note was
                        // dispatched, so dropping the pending request lets the actor
                        // simply continue with the next note.
                        pendingRequestId = null
                        consecutiveCancellations++
                        debug("手势被取消，跳过当前音符继续播放：requestId=${command.requestId}, reason=${command.reason}, 连续=$consecutiveCancellations")
                        // A real touch cancels only the gestures it overlaps, so a handful
                        // of consecutive cancellations is normal. Never-ending cancellations
                        // mean the injection pipeline itself is broken, and running the rest
                        // of the song silently would be worse than reporting it.
                        if (consecutiveCancellations >= MAX_CONSECUTIVE_CANCELLATIONS) {
                            playing = false
                            _state.value = PlaybackState.Error(
                                "连续 $MAX_CONSECUTIVE_CANCELLATIONS 次手势被系统取消，请检查无障碍服务"
                            )
                        }
                    }
                    PlaybackCommand.ServiceDisconnected -> {
                        pendingRequestId = null
                        if (playing) {
                            positionUs = currentPosition(anchorClock, anchorSong, speed).coerceAtMost(timeline?.durationUs ?: Long.MAX_VALUE)
                            playing = false
                            generation++
                            _state.value = PlaybackState.Error("Accessibility service disconnected")
                        }
                    }
                }
                continue
            }
            val current = timeline ?: continue
            if (pendingRequestId != null) {
                if (clock.nowNanos() >= pendingTimeoutNanos) {
                    pendingRequestId = null
                    playing = false
                    _state.value = PlaybackState.Error("Gesture did not complete: ${GestureCompletion.TimedOut}")
                } else {
                    delay(10L)
                }
                continue
            }
            val event = current.events.getOrNull(index)
            if (event == null) {
                playing = false
                positionUs = current.durationUs
                _state.value = PlaybackState.Ready(current.title, positionUs, current.durationUs)
                continue
            }
            val deadline = anchorClock + ((event.atUs - anchorSong).coerceAtLeast(0) * 1000L / speed).toLong()
            val remaining = deadline - clock.nowNanos()
            if (remaining > 2_000_000L) {
                // Short bounded waits keep control commands responsive while the deadline remains absolute.
                delay(minOf(10L, maxOf(1L, remaining / 1_000_000L - 1L)))
                continue
            }
            if (remaining > 0) yield()
            var batchEnd = index + 1
            while (batchEnd < current.events.size && current.events[batchEnd].atUs == event.atUs) batchEnd++
            val chordEvents = current.events.subList(index, batchEnd)
            val chordKeys = chordEvents.flatMapTo(sortedSetOf()) { it.keys }
            val chordHoldUs = chordEvents.maxOf { it.holdUs }
            val requestId = generation * 1_000_000L + index
            val submission = sink.dispatchChord(GestureRequest(requestId, chordKeys, emptyList(), chordHoldUs, event.atUs)) { result ->
                debug("手势完成回调：requestId=$requestId, result=$result")
                commands.trySend(
                    when (result) {
                        GestureCompletion.Completed -> PlaybackCommand.GestureCompleted(requestId)
                        else -> PlaybackCommand.GestureCancelled(requestId, result.name)
                    }
                )
            }
            debug("当前事件触发：key=$chordKeys, 延迟=${(event.atUs - positionUs).coerceAtLeast(0)}us, holdUs=$chordHoldUs, submission=$submission")
            if (submission !is DispatchSubmission.Accepted) { playing = false; _state.value = PlaybackState.Error("Gesture submission failed: $submission"); continue }
            pendingRequestId = requestId
            pendingTimeoutNanos = clock.nowNanos() + (chordHoldUs + 1_000_000L) * 1_000L
            positionUs = event.atUs; index = batchEnd
            _state.value = PlaybackState.Playing(current.title, positionUs, current.durationUs, speed)
        }
    }

    private fun currentPosition(anchorClock: Long, anchorSong: Long, speed: Double): Long = anchorSong + (((clock.nowNanos() - anchorClock).coerceAtLeast(0L) / 1000.0) * speed).toLong()

    private fun debug(message: String) { runCatching { Log.d(TAG, message) } }
    private fun error(message: String) { runCatching { Log.e(TAG, message) } }

    private companion object {
        const val TAG = "AutoPlay"

        /**
         * Cancellations are expected whenever the player touches the screen, so this is
         * deliberately far above what one touch (or one long swipe) can produce. It only
         * trips when the injection pipeline is genuinely dead.
         */
        const val MAX_CONSECUTIVE_CANCELLATIONS = 40
    }
}
