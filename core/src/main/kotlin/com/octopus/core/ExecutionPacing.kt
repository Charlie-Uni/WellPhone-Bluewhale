package com.octopus.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ExecutionPacer {
    suspend fun beforeObservation()
    suspend fun beforeAction()
}
object Unpaced : ExecutionPacer {
    override suspend fun beforeObservation() = Unit
    override suspend fun beforeAction() = Unit
}

/** One admission clock shared by both virtual sessions. No input lease is held while waiting. */
class AdaptivePacer(private val policy: () -> Pacing, private val clock: () -> Long,
    private val pause: suspend (Long) -> Unit = { delay(it) }) : ExecutionPacer {
    private val observations = Mutex(); private val actions = Mutex()
    private var lastObservation: Long? = null; private var lastAction: Long? = null
    private fun interval(observation: Boolean): Long {
        val pacing = policy()
        require(pacing.observationsPerSecond in 0.5..2.0 && pacing.actionGapMillis in 500..2_000)
        return if (observation) (1_000 / pacing.observationsPerSecond).toLong() else pacing.actionGapMillis
    }
    private suspend fun waitFor(last: Long?, observation: Boolean) {
        if (last == null) return
        while (true) {
            val remaining = interval(observation) - (clock() - last).coerceAtLeast(0)
            if (remaining <= 0) return
            pause(remaining) // Re-read policy after suspension; load may have increased.
        }
    }
    override suspend fun beforeObservation() = observations.withLock {
        waitFor(lastObservation, true); lastObservation = clock()
    }
    override suspend fun beforeAction() = actions.withLock {
        waitFor(lastAction, false); lastAction = clock()
    }
}

/** Short capture lease: producer images are always drained, JPEG work is demand driven. */
class CaptureBudget {
    private var interval = 2_000L
    private var until = -1L
    private var generation = 0L
    private var last: Long? = null
    @Synchronized fun configure(enabled: Boolean, intervalMillis: Long, now: Long) {
        require(intervalMillis in 500..2_000 && now >= 0)
        if (!enabled || now > until || interval != intervalMillis) { generation++; last = null }
        interval = intervalMillis
        until = if (enabled) now + 3_000 else -1
    }
    @Synchronized fun claim(now: Long): Long? {
        if (now > until || now < 0 || last?.let { now - it < interval } == true) return null
        last = now; return generation
    }
    @Synchronized fun mayPublish(ticket: Long, now: Long) = ticket == generation && now in 0..until
    @Synchronized fun current(ticket: Long, now: Long, capturedAt: Long) = ticket == generation &&
        now in 0..until && now - capturedAt in 0..interval + 500
}

/** Single capture-thread owner, at most one acquired producer buffer. Static frames remain available. */
class LatestResource<T : AutoCloseable> : AutoCloseable {
    private var current: T? = null
    private var closed = false
    fun replace(next: T) {
        if (closed) { next.close(); return }
        val old = current
        current = next
        if (old !== next) old?.close()
    }
    fun <R> useCurrent(block: (T) -> R): R? = current?.let(block)
    override fun close() { closed = true;val old = current;current = null;old?.close() }
}
