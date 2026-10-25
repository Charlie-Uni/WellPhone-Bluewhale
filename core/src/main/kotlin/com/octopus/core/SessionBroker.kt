package com.octopus.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable enum class Owner { AGENT, DRAINING, HUMAN, STOPPED }
@Serializable data class SessionToken(val sessionId: String, val epoch: Long)
data class SessionState(
    val token: SessionToken, val displayId: Int, val packageName: String,
    val owner: Owner, val inFlight: Int,
)
class ActionRejected(message: String) : IllegalStateException(message)

/**
 * Single admission point for every real input channel. Epoch revocation is atomic.
 * Android ports must not return until their submitted input has settled, even on cancellation.
 */
class SessionBroker {
    private data class Entry(
        var state: SessionState, val usedActionIds: MutableSet<String> = mutableSetOf(),
        var latestObservation: String? = null,
        var takeoverHeld: Boolean = false,
    )
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    suspend fun create(displayId: Int, packageName: String): SessionState = mutex.withLock {
        require(displayId > 0 && packageName.isNotBlank()) { "Default display is not an execution target" }
        require(entries.values.none { it.state.displayId == displayId &&
            (it.state.owner != Owner.STOPPED || it.state.inFlight != 0) })
        val state = SessionState(SessionToken(UUID.randomUUID().toString(), 0), displayId, packageName, Owner.AGENT, 0)
        entries[state.token.sessionId] = Entry(state)
        state
    }
    suspend fun state(id: String): SessionState = mutex.withLock { entry(id).state }

    suspend fun registerObservation(token: SessionToken, observationId: String) = mutex.withLock {
        val entry = checked(token, Owner.AGENT)
        require(observationId.isNotBlank())
        entry.latestObservation = observationId
    }

    suspend fun <T> agentAction(
        token: SessionToken, actionId: String, observationId: String, block: suspend (SessionState) -> T,
    ): T = admitted(token, Owner.AGENT, actionId, observationId, block)

    suspend fun <T> humanAction(
        token: SessionToken, actionId: String, block: suspend (SessionState) -> T,
    ): T = admitted(token, Owner.HUMAN, actionId, null, block)

    private suspend fun <T> admitted(
        token: SessionToken, owner: Owner, actionId: String, observationId: String?,
        block: suspend (SessionState) -> T,
    ): T {
        val state = mutex.withLock {
            val entry = checked(token, owner)
            if (owner == Owner.AGENT && (observationId == null || observationId != entry.latestObservation)) {
                throw ActionRejected("Observation is stale")
            }
            if (actionId.isBlank() || !entry.usedActionIds.add(actionId)) throw ActionRejected("Duplicate action")
            // No second concurrent writer, including two human or agent channels.
            if (entry.state.inFlight != 0) throw ActionRejected("Input is already in flight")
            entry.state = entry.state.copy(inFlight = 1)
            entry.state
        }
        return try { block(state) } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    val entry = entry(token.sessionId)
                    entry.state = entry.state.copy(inFlight = 0,
                        owner = if (entry.state.owner == Owner.DRAINING && !entry.takeoverHeld) Owner.HUMAN else entry.state.owner)
                }
            }
        }
    }

    suspend fun requestTakeover(id: String, holdForWorkflow: Boolean = false): SessionState = mutex.withLock {
        val entry = entry(id)
        if (entry.state.owner != Owner.AGENT) return@withLock entry.state
        entry.latestObservation = null
        entry.takeoverHeld = holdForWorkflow
        entry.state = entry.state.copy(token = next(entry.state.token),
            owner = if (entry.state.inFlight == 0 && !holdForWorkflow) Owner.HUMAN else Owner.DRAINING)
        entry.state
    }

    /** Only release after the old runner settled and its possible writes were persisted as held/unknown. */
    suspend fun completeTakeover(token: SessionToken): SessionState = mutex.withLock {
        val entry = entry(token.sessionId)
        if (entry.state.token != token || entry.state.owner !in setOf(Owner.DRAINING, Owner.HUMAN)) throw ActionRejected("Takeover is stale")
        entry.takeoverHeld = false
        if (entry.state.inFlight == 0) entry.state = entry.state.copy(owner = Owner.HUMAN)
        entry.state
    }

    suspend fun returnControl(token: SessionToken): SessionState = mutex.withLock {
        val entry = checked(token, Owner.HUMAN)
        check(entry.state.inFlight == 0)
        entry.latestObservation = null
        entry.state = entry.state.copy(token = next(token), owner = Owner.AGENT)
        entry.state
    }

    suspend fun stop(id: String): SessionState = mutex.withLock {
        val entry = entry(id)
        entry.latestObservation = null
        entry.state = entry.state.copy(token = next(entry.state.token), owner = Owner.STOPPED)
        entry.state
    }

    private fun entry(id: String): Entry = entries[id] ?: throw ActionRejected("Unknown session")
    private fun checked(token: SessionToken, owner: Owner): Entry = entry(token.sessionId).also {
        if (it.state.token != token || it.state.owner != owner) throw ActionRejected("Control is revoked")
    }
    private fun next(token: SessionToken) = token.copy(epoch = Math.addExact(token.epoch, 1))
}
