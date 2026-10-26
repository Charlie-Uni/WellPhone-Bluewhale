package com.octopus.core

import kotlinx.coroutines.delay
import java.util.UUID

class GuiNeedsUser(val reasonCode: String) : IllegalStateException(reasonCode)
class GuiStepBudget(private val limit: Int = 40) {
    private var used = 0
    fun consume() { if (++used > limit) throw GuiNeedsUser("STEP_BUDGET") }
}

/** Each model decision is checked against a fresh, equal screen before dispatch. */
class GuiNavigator(
    private val broker: SessionBroker, private val observer: ObservationPort,
    private val actions: ActionPort, private val model: DecisionModel,
    private val clock: () -> Long, private val budget: GuiStepBudget = GuiStepBudget(),
    private val journal: EventJournal = EventJournal { },
    private val pacer: ExecutionPacer = Unpaced,
) {
    suspend fun observe(token: SessionToken): Observation {
        pacer.beforeObservation()
        val state = broker.state(token.sessionId)
        if (state.token != token || state.owner != Owner.AGENT) throw ActionRejected("Control changed")
        val observation = try { observer.observe(state) } catch (lost: BindingLost) {
            broker.stop(token.sessionId); throw lost
        }
        if (observation.token != token || observation.displayId != state.displayId ||
            observation.packageName != state.packageName) {
            broker.stop(token.sessionId); throw BindingLost()
        }
        return observation
    }
    suspend fun act(token: SessionToken, observation: Observation, action: Action): Boolean {
        awaitActionSlot(token, observation)
        broker.registerObservation(token, observation.observationId)
        val id = UUID.randomUUID().toString()
        val begin = clock()
        val result = broker.agentAction(token, id, observation.observationId) {
            actions.perform(it, observation, action)
        }
        journal.record(AgentEvent(if (result) "ACTION_SETTLED" else "ACTION_REJECTED", token.sessionId,
            token.epoch, id, clock() - begin, observation.displayId, observation.packageName))
        return result
    }
    suspend fun awaitActionSlot(token: SessionToken, observation: Observation) {
        pacer.beforeAction()
        if (clock() - observation.capturedAtMillis !in 0..5_000) throw GuiNeedsUser("OBSERVATION_EXPIRED")
        val state = broker.state(token.sessionId)
        if (state.token != token || state.owner != Owner.AGENT) throw ActionRejected("Control changed while pacing")
    }
    suspend fun reach(
        token: SessionToken, goal: String,
        project: (Observation) -> Observation,
        allowed: (Observation, Action) -> Boolean,
        complete: (Observation) -> Boolean,
        transform: (Action) -> Action = { it },
        settled: (Observation, Action) -> Unit = { _, _ -> },
    ): Observation {
        var failures = 0
        repeat(24) {
            val original = observe(token)
            if (complete(original)) return original
            budget.consume()
            val safe = project(original)
            require(safe.token == original.token && safe.observationId == original.observationId)
            val decision = model.decide(goal, safe)
            val fresh = observe(token)
            if (complete(fresh)) return fresh
            val currentProjection = project(fresh)
            if (original.windowId != fresh.windowId || safe.elements != currentProjection.elements) {
                delay(250)
                return@repeat
            }
            when (val action = decision.action) {
                is Action.AskUser -> throw GuiNeedsUser("GUI_REQUIRES_USER")
                is Action.Finish -> throw GuiNeedsUser("NO_VERIFIABLE_GUI_EVIDENCE")
                is Action.Wait -> delay(action.millis.coerceIn(200, 2_000))
                else -> {
                    val id = when (action) {
                        is Action.Click -> action.nodeId
                        is Action.SetText -> action.nodeId
                        is Action.SubmitSearch -> action.nodeId
                        is Action.Scroll -> action.nodeId
                        else -> error("Unsupported action")
                    }
                    if (safe.elements.none { it.id == id } || !allowed(fresh, action)) throw GuiNeedsUser("ACTION_OUTSIDE_PHASE")
                    val ok = act(token, fresh, transform(action))
                    if (ok) settled(fresh, action)
                    failures = if (ok) 0 else failures + 1
                    if (failures >= 2) throw GuiNeedsUser("REPEATED_GUI_FAILURE")
                    delay(350)
                }
            }
        }
        throw GuiNeedsUser("PHASE_STEP_LIMIT")
    }
}
