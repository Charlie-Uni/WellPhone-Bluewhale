package com.octopus.core

import java.util.UUID

/** Local-only text. Never serialize this draft into a model request or journal. */
data class HumanTextField(val id: String, val label: String, val value: String, val focused: Boolean)
data class HumanTextDraft(val id: String, val target: TouchTarget, val fields: List<HumanTextField>)
enum class TextWriteResult { VERIFIED, UNKNOWN }

interface HumanTextPort {
    suspend fun capture(session: SessionState, draftId: String): List<HumanTextField>
    /** Re-resolve the original node, fence immediately before dispatch, then read the value back. */
    suspend fun replace(session: SessionState, draftId: String, fieldId: String, value: String,
        stillCurrent: () -> Boolean): TextWriteResult
    fun clear(draftId: String)
}

/** Main-thread confined. The same broker serializes text and entire touch streams. */
class HumanTextController(private val broker: SessionBroker, private val port: HumanTextPort,
    private val isCurrent: (TouchTarget) -> Boolean) {
    companion object { const val MAX_LENGTH = 24_000 }
    private var generation = 0L
    private var active: HumanTextDraft? = null

    fun invalidate() {
        generation++
        active?.let { port.clear(it.id) }
        active = null
    }

    suspend fun open(target: TouchTarget): HumanTextDraft {
        invalidate()
        val expected = generation
        val id = UUID.randomUUID().toString()
        try {
            val fields = broker.humanAction(target.token, "text-read:$id") { session ->
                checkTarget(session, target, expected)
                port.capture(session, id)
            }
            checkCurrent(target, expected)
            if (fields.isEmpty() || fields.map { it.id }.distinct().size != fields.size ||
                fields.any { it.id.isBlank() || it.value.length > MAX_LENGTH }) throw ActionRejected("No editable field")
            return HumanTextDraft(id, target, fields.toList()).also { active = it }
        } catch (error: Throwable) { port.clear(id); throw error }
    }

    suspend fun apply(draft: HumanTextDraft, fieldId: String, value: String): TextWriteResult {
        if (active !== draft) throw ActionRejected("Text draft is stale or already consumed")
        active = null // One explicit confirmation permits at most one dispatch, including UNKNOWN.
        val expected = generation
        try {
            if (value.length > MAX_LENGTH || draft.fields.none { it.id == fieldId }) throw ActionRejected("Invalid text field")
            return broker.humanAction(draft.target.token, "text-write:${draft.id}") { session ->
                checkTarget(session, draft.target, expected)
                port.replace(session, draft.id, fieldId, value) {
                    generation == expected && isCurrent(draft.target)
                }
            }
        } finally { port.clear(draft.id) }
    }

    private fun checkCurrent(target: TouchTarget, expected: Long) {
        if (generation != expected || !isCurrent(target)) throw ActionRejected("Text preview changed")
    }
    private fun checkTarget(session: SessionState, target: TouchTarget, expected: Long) {
        checkCurrent(target, expected)
        if (session.token != target.token || session.owner != Owner.HUMAN ||
            session.displayId != target.displayId || session.packageName != target.packageName) {
            throw ActionRejected("Text control is revoked")
        }
    }
}
