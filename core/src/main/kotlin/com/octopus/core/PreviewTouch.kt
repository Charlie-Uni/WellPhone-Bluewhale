package com.octopus.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.min

@Serializable data class TouchPoint(val x: Float, val y: Float) {
    fun finite() = x.isFinite() && y.isFinite()
}

/** Coordinates are local to a fit-centred preview, never the overlay's screen position. */
data class PreviewTransform(val version: Long, val viewWidth: Int, val viewHeight: Int,
    val displayWidth: Int, val displayHeight: Int) {
    init { require(version > 0 && minOf(viewWidth, viewHeight, displayWidth, displayHeight) > 0) }
    val scale = min(viewWidth.toFloat() / displayWidth, viewHeight.toFloat() / displayHeight)
    val left = (viewWidth - displayWidth * scale) / 2
    val top = (viewHeight - displayHeight * scale) / 2
    fun map(point: TouchPoint): TouchPoint? {
        if (!point.finite()) return null
        val x = (point.x - left) / scale
        val y = (point.y - top) / scale
        return if (x >= 0 && y >= 0 && x < displayWidth && y < displayHeight) TouchPoint(x, y) else null
    }
}

sealed interface PreviewEffect {
    data class Tap(val point: TouchPoint) : PreviewEffect
    data class Begin(val point: TouchPoint) : PreviewEffect
    data class Move(val point: TouchPoint) : PreviewEffect
    data class End(val point: TouchPoint) : PreviewEffect
    data object Cancel : PreviewEffect
    data object ToggleSize : PreviewEffect
}

/** Reserve a double tap for resizing: neither tap reaches the App. No delayed tap survives reset. */
class PreviewGestures(private val slop: Float, private val doubleSlop: Float,
    private val doubleMillis: Long = 300, private val holdMillis: Long = 500) {
    private data class Press(val point: TouchPoint, val time: Long)
    private var press: Press? = null
    private var tap: Press? = null
    private var second = false
    private var streaming = false
    private var abandoned = false
    private fun distance(a: TouchPoint, b: TouchPoint) = hypot(a.x - b.x, a.y - b.y)
    fun down(point: TouchPoint, now: Long): List<PreviewEffect> {
        val output = tick(now).toMutableList()
        if (press != null) output += cancel()
        val previous = tap
        second = previous != null && now - previous.time in 0..doubleMillis && distance(point, previous.point) <= doubleSlop
        if (previous != null && !second) { output += PreviewEffect.Tap(previous.point); tap = null }
        press = Press(point, now); streaming = false; abandoned = false
        return output
    }
    fun move(point: TouchPoint, now: Long): List<PreviewEffect> {
        val current = press ?: return emptyList()
        if (abandoned) return emptyList()
        if (second) {
            if (distance(current.point, point) > slop) { abandoned = true; tap = null }
            return emptyList()
        }
        if (!streaming && distance(current.point, point) > slop) {
            streaming = true
            return listOf(PreviewEffect.Begin(current.point), PreviewEffect.Move(point))
        }
        return tick(now) + if (streaming) listOf(PreviewEffect.Move(point)) else emptyList()
    }
    fun up(point: TouchPoint, now: Long): List<PreviewEffect> {
        val current = press ?: return emptyList()
        val result = when {
            abandoned -> emptyList()
            streaming -> listOf(PreviewEffect.End(point))
            second -> { tap = null; if (distance(current.point, point) <= slop) listOf(PreviewEffect.ToggleSize) else emptyList() }
            distance(current.point, point) > slop -> emptyList()
            else -> { tap = Press(point, now); emptyList() }
        }
        press = null; streaming = false; second = false; abandoned = false
        return result
    }
    fun tick(now: Long): List<PreviewEffect> {
        val previous = tap
        if (press == null && previous != null && now - previous.time >= doubleMillis) {
            tap = null; return listOf(PreviewEffect.Tap(previous.point))
        }
        val current = press
        if (current != null && !streaming && !second && !abandoned && now - current.time >= holdMillis) {
            streaming = true; return listOf(PreviewEffect.Begin(current.point))
        }
        return emptyList()
    }
    fun cancel(): List<PreviewEffect> {
        val result = if (streaming) listOf(PreviewEffect.Cancel) else emptyList()
        press = null; tap = null; second = false; streaming = false; abandoned = false
        return result
    }
}

@Serializable data class TouchTarget(val token: SessionToken, val displayId: Int, val packageName: String,
    val viewportVersion: Long, val width: Int, val height: Int) {
    init { require(displayId > 0 && viewportVersion > 0 && width > 0 && height > 0 && packageName.isNotBlank()) }
}
@Serializable enum class TouchPhase { DOWN, MOVE, UP, CANCEL }
@Serializable data class TouchPacket(val gestureId: String, val sequence: Long, val phase: TouchPhase,
    val point: TouchPoint, val uptimeMillis: Long)

/** Reused in the shell: rejects malformed, stale, reordered and mismatched gesture packets. */
class TouchStreamGuard(private val width: Int, private val height: Int) {
    private var active: TouchPacket? = null
    private var downTime: Long = 0
    private val consumed = linkedSetOf<String>()
    fun accept(packet: TouchPacket, now: Long): Long {
        require(packet.gestureId.length in 1..80 && packet.point.finite())
        require(packet.point.x >= 0 && packet.point.x < width && packet.point.y >= 0 && packet.point.y < height)
        require(now - packet.uptimeMillis in 0..5_000)
        val previous = active
        if (packet.phase == TouchPhase.DOWN) {
            require(previous == null && packet.sequence == 0L && packet.gestureId !in consumed)
            downTime = packet.uptimeMillis
            consumed += packet.gestureId
            if (consumed.size > 128) consumed.remove(consumed.first())
        } else {
            require(previous != null && previous.gestureId == packet.gestureId)
            require(packet.uptimeMillis >= previous.uptimeMillis)
            require(if (packet.phase == TouchPhase.CANCEL) packet.sequence > previous.sequence
                else packet.sequence == previous.sequence + 1)
        }
        active = if (packet.phase == TouchPhase.UP || packet.phase == TouchPhase.CANCEL) null else packet
        return downTime
    }
    fun cancellation(now: Long): Pair<TouchPacket, Long>? = active?.let {
        TouchPacket(it.gestureId, it.sequence + 1, TouchPhase.CANCEL, it.point, now) to downTime
    }
    fun clear() { active = null }
}

fun interface HumanTouchSink { suspend fun inject(target: TouchTarget, packet: TouchPacket): Boolean }

/** One Broker human lease covers the entire streamed gesture, including terminal cleanup. */
class HumanTouchController(private val broker: SessionBroker, private val scope: CoroutineScope,
    private val sink: HumanTouchSink, private val clock: () -> Long,
    private val onFailure: (String) -> Unit = {}) {
    private data class Sample(val phase: TouchPhase, val point: TouchPoint, val time: Long)
    private class Active(val target: TouchTarget, val samples: Channel<Sample>) {
        var closing = false
        var job: Job? = null
    }
    private var active: Active? = null
    val busy get() = active != null
    fun begin(target: TouchTarget, point: TouchPoint): Boolean {
        if (!scope.isActive || active != null || !valid(target, point)) return false
        val gesture = Active(target, Channel(32))
        active = gesture
        val first = Sample(TouchPhase.DOWN, point, clock())
        gesture.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                broker.humanAction(target.token, UUID.randomUUID().toString()) { state ->
                    check(state.displayId == target.displayId && state.packageName == target.packageName)
                    val id = UUID.randomUUID().toString()
                    var sequence = 0L
                    var last = TouchPacket(id, sequence, first.phase, first.point, first.time)
                    var ended = false
                    try {
                        check(sink.inject(target, last))
                        withTimeout(15_000) {
                            for (sample in gesture.samples) {
                                currentCoroutineContext().ensureActive()
                                val authority = broker.state(target.token.sessionId)
                                check(authority.token == target.token && authority.owner == Owner.HUMAN)
                                last = TouchPacket(id, ++sequence, sample.phase, sample.point, sample.time)
                                check(sink.inject(target, last))
                                if (sample.phase == TouchPhase.UP || sample.phase == TouchPhase.CANCEL) { ended = true; break }
                            }
                        }
                    } finally {
                        if (!ended) withContext(NonCancellable) {
                            runCatching { sink.inject(target, last.copy(sequence = sequence + 1,
                                phase = TouchPhase.CANCEL, uptimeMillis = clock())) }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                onFailure("HUMAN_TOUCH_TIMEOUT")
            } catch (_: CancellationException) {
                // Revocation/hide/resize cancels, never replays the initial gesture.
            } catch (_: Exception) { onFailure("HUMAN_TOUCH_REJECTED") }
            finally {
                gesture.samples.cancel()
                if (active === gesture) active = null
            }
        }
        return true
    }
    fun move(point: TouchPoint) = offer(TouchPhase.MOVE, point)
    fun end(point: TouchPoint) = offer(TouchPhase.UP, point)
    private fun offer(phase: TouchPhase, point: TouchPoint) {
        val gesture = active ?: return
        if (gesture.closing) return
        if (!valid(gesture.target, point)) { cancel(); return }
        if (!gesture.samples.trySend(Sample(phase, point, clock())).isSuccess) {
            onFailure("HUMAN_TOUCH_BACKPRESSURE"); cancel(); return
        }
        if (phase == TouchPhase.UP) gesture.closing = true
    }
    fun cancel() { active?.let { it.closing = true; it.job?.cancel() } }
    suspend fun cancelAndJoin() { val job = active?.job; cancel(); job?.join() }
    private fun valid(target: TouchTarget, point: TouchPoint) = point.finite() &&
        point.x >= 0 && point.y >= 0 && point.x < target.width && point.y < target.height
}
