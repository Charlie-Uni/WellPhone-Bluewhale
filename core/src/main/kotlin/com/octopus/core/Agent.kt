package com.octopus.core

import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class UiElement(
    val id: Int, val role: String, val text: String, val description: String = "",
    val clickable: Boolean = false, val editable: Boolean = false, val scrollable: Boolean = false,
)
@Serializable data class Observation(
    val token: SessionToken, val observationId: String, val displayId: Int,
    val windowId: Int, val packageName: String, val capturedAtMillis: Long,
    val elements: List<UiElement>,
)
@Serializable sealed interface Action {
    @Serializable @SerialName("click") data class Click(val nodeId: Int) : Action
    @Serializable @SerialName("set_text") data class SetText(val nodeId: Int, val text: String) : Action
    @Serializable @SerialName("submit_search") data class SubmitSearch(val nodeId: Int) : Action
    @Serializable @SerialName("scroll") data class Scroll(val nodeId: Int, val forward: Boolean) : Action
    @Serializable @SerialName("wait") data class Wait(val millis: Long) : Action
    @Serializable @SerialName("ask_user") data class AskUser(val reason: String) : Action
    @Serializable @SerialName("finish") data class Finish(val boundary: String) : Action
}
@Serializable data class Decision(val action: Action, val rationale: String)
interface ObservationPort { suspend fun observe(session: SessionState): Observation }
class BindingLost : IllegalStateException("Target display/window/package binding was lost")
interface ActionPort { suspend fun perform(session: SessionState, observation: Observation, action: Action): Boolean }
interface DecisionModel { suspend fun decide(goal: String, safeObservation: Observation): Decision }
fun interface EgressPolicy { fun sanitize(observation: Observation): Observation }
fun interface EventJournal { fun record(event: AgentEvent) }
@Serializable data class AgentEvent(
    val kind: String, val sessionId: String, val epoch: Long,
    val actionId: String? = null, val elapsedMillis: Long? = null,
)
data class RunOutcome(val reason: String, val steps: Int, val requestedBoundary: String? = null)

/**
 * A model Finish is only a requested stopping boundary, never proof of persistent App results.
 * Callers must provide an egress policy; raw inbox observations have no default allow path.
 */
class AgentRunner(
    private val broker: SessionBroker,
    private val observer: ObservationPort,
    private val actions: ActionPort,
    private val model: DecisionModel,
    private val egress: EgressPolicy,
    private val journal: EventJournal,
    private val clock: () -> Long,
    private val maxSteps: Int = 40,
) {
    suspend fun run(goal: String, token: SessionToken): RunOutcome {
        require(goal.isNotBlank() && maxSteps > 0)
        var failures = 0
        var waited = 0L
        for (step in 1..maxSteps) {
            val state = broker.state(token.sessionId)
            if (state.token != token || state.owner != Owner.AGENT) return RunOutcome("CONTROL_CHANGED", step - 1)
            val observation = try { observer.observe(state) } catch (_: BindingLost) {
                broker.stop(token.sessionId)
                return RunOutcome("BINDING_LOST", step - 1)
            }
            if (observation.token != token || observation.displayId != state.displayId ||
                observation.packageName != state.packageName) {
                broker.stop(token.sessionId)
                return RunOutcome("BINDING_LOST", step - 1)
            }
            check(clock() - observation.capturedAtMillis in 0..5_000) { "Observation is stale" }
            broker.registerObservation(token, observation.observationId)
            val safe = egress.sanitize(observation)
            val decision = model.decide(goal, safe)
            // Check again after potentially slow model I/O, including AskUser / Finish.
            val current = broker.state(token.sessionId)
            if (current.token != token || current.owner != Owner.AGENT) return RunOutcome("CONTROL_CHANGED", step - 1)
            if (clock() - observation.capturedAtMillis > 5_000) continue
            val targetId = when (val action = decision.action) {
                is Action.Click -> action.nodeId
                is Action.SetText -> action.nodeId
                is Action.SubmitSearch -> action.nodeId
                is Action.Scroll -> action.nodeId
                else -> null
            }
            if (targetId != null) {
                val target = safe.elements.singleOrNull { it.id == targetId }
                    ?: return RunOutcome("MODEL_TARGET_NOT_ALLOWED", step)
                val allowed = when (val action = decision.action) {
                    is Action.Click -> target.clickable
                    is Action.SetText -> target.editable && action.text.length <= 8_000
                    is Action.SubmitSearch -> target.editable
                    is Action.Scroll -> target.scrollable
                    else -> false
                }
                if (!allowed) return RunOutcome("MODEL_TARGET_NOT_ALLOWED", step)
            }
            when (val action = decision.action) {
                is Action.AskUser -> return RunOutcome("AWAITING_USER", step, action.reason)
                is Action.Finish -> return RunOutcome("MODEL_STOPPED_UNVERIFIED", step, action.boundary)
                is Action.Wait -> {
                    val duration = action.millis.coerceIn(100, 2_000)
                    waited += duration
                    if (waited > 15_000) return RunOutcome("WAIT_BUDGET", step)
                    delay(duration)
                }
                else -> {
                    val actionId = UUID.randomUUID().toString()
                    val start = clock()
                    val ok = try {
                        broker.agentAction(token, actionId, observation.observationId) {
                            actions.perform(it, observation, action)
                        }
                    } catch (_: ActionRejected) { return RunOutcome("ACTION_REVOKED", step - 1) }
                    journal.record(AgentEvent(if (ok) "ACTION_SETTLED" else "ACTION_REJECTED",
                        token.sessionId, token.epoch, actionId, clock() - start))
                    failures = if (ok) 0 else failures + 1
                    if (failures >= 2) return RunOutcome("REPEATED_FAILURE", step)
                }
            }
        }
        return RunOutcome("STEP_BUDGET", maxSteps)
    }
}
