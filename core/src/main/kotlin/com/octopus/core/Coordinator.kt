package com.octopus.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable data class TaskPlan(val summary: String, val steps: List<String>)
fun interface Planner { suspend fun plan(goal: String): TaskPlan }
data class TaskRequest(val id: String, val goal: String, val plan: TaskPlan)
enum class ItemResult { VERIFIED, AWAITING_USER, UNKNOWN, NOT_DONE }
data class ItemOutcome(val key: String, val result: ItemResult, val boundary: String)
data class TaskSummary(val verified: Int, val awaitingUser: Int, val unknown: Int, val notDone: Int)
data class Confirmation(
    val id: String, val token: SessionToken, val itemDigest: String, val options: Set<ConflictChoice>,
)

/**
 * Ordinary orchestration, no planner agent or tool privileges of its own.
 * Mutable state is serialized by the caller's task coroutine.
 */
class Coordinator(private val planner: Planner, private val broker: SessionBroker) {
    private val confirmations = mutableMapOf<String, Confirmation>()
    suspend fun start(goal: String): TaskRequest {
        require(goal.isNotBlank())
        val plan = planner.plan(goal)
        require(plan.summary.isNotBlank() && plan.steps.isNotEmpty() && plan.steps.size <= 6)
        return TaskRequest(UUID.randomUUID().toString(), goal, plan)
    }
    suspend fun requestConflict(item: RecruitmentItem, token: SessionToken): Confirmation {
        val state = broker.state(token.sessionId)
        check(state.token == token && state.owner == Owner.AGENT)
        require(item.confirmationReasons().isEmpty()) { "Resolve date ambiguity before conflict choice" }
        return Confirmation(UUID.randomUUID().toString(), token, itemDigest(item),
            ConflictChoice.entries.toSet()).also { confirmations[it.id] = it }
    }
    suspend fun confirm(id: String, item: RecruitmentItem, token: SessionToken, choice: ConflictChoice): ConflictChoice {
        val card = requireNotNull(confirmations[id]) { "Confirmation is absent or already consumed" }
        val state = broker.state(token.sessionId)
        check(card.token == token && state.token == token && state.owner == Owner.AGENT)
        check(card.itemDigest == itemDigest(item)) { "Item changed; request a new confirmation" }
        check(choice in card.options)
        confirmations.remove(id)
        return choice
    }
    fun summary(outcomes: List<ItemOutcome>): TaskSummary {
        require(outcomes.map { it.key }.distinct().size == outcomes.size)
        return TaskSummary(outcomes.count { it.result == ItemResult.VERIFIED },
            outcomes.count { it.result == ItemResult.AWAITING_USER },
            outcomes.count { it.result == ItemResult.UNKNOWN },
            outcomes.count { it.result == ItemResult.NOT_DONE })
    }
    private fun itemDigest(item: RecruitmentItem) = digest(Json.encodeToString(item))
}
