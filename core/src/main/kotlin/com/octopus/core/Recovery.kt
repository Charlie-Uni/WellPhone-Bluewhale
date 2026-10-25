package com.octopus.core

/** In-memory cursor for one authorized run. No node ids, decisions or Save tickets survive handoff. */
enum class CalendarPhase { LOOKUP, INSPECT, PREPARE, READY_TO_SAVE, SAVE_PENDING, VERIFY }
data class CalendarCheckpoint(val source: String, val draft: CalendarDraft, val phase: CalendarPhase,
    val beforeHuman: Observation? = null)
class WorkflowCheckpoint {
    var documents: List<MailDocument>? = null
    val outcomes = linkedMapOf<String, ItemOutcome>()
    val completed = mutableSetOf<String>()
    var calendar: CalendarCheckpoint? = null
    var needsResume = false
        private set
    suspend fun pause(ledger: WriteLedger) {
        needsResume = true
        calendar?.takeIf { it.phase !in setOf(CalendarPhase.LOOKUP, CalendarPhase.INSPECT) }
            ?.let { ledger.holdForHuman(it.draft.key) }
    }
    fun resumed() { needsResume = false }
    fun record(outcome: ItemOutcome, settled: Boolean = true) {
        outcomes[outcome.key] = outcome
        if (settled) {
            completed += outcome.key
            if (calendar?.draft?.key == outcome.key) calendar = null
        }
    }
}
sealed interface CalendarRecovery {
    data object RetryRead : CalendarRecovery
    data class Ready(val draft: CalendarDraft, val prepared: PreparedWrite) : CalendarRecovery
    data class Verified(val draft: CalendarDraft, val proof: VerifiedRecord) : CalendarRecovery
    data class Blocked(val code: String) : CalendarRecovery
}

/** Only explicit field roles can be adopted; other content and structure remain sealed. */
object ResumeEvidence {
    private val titleIds = setOf("title", "event_title", "title_input", "task_title", "task_name")
    private val noteIds = setOf("description", "event_description", "description_input", "notes", "note")
    fun contentEditor(observation: Observation) = observation.elements.any { it.editable && !GuiPrivacy.searchField(it) } ||
        CalendarEvidence.saveControl(observation) != null
    fun checkScenes(mail: Observation, calendar: Observation, point: CalendarCheckpoint?, mailNeeded: Boolean = true) {
        if (mail.truncated || calendar.truncated) throw GuiNeedsUser("RESUME_TREE_INCOMPLETE")
        if (mailNeeded && mail.elements.any { it.editable && !GuiPrivacy.searchField(it) }) throw GuiNeedsUser("RESUME_MAIL_DRAFT_OPEN")
        if (point == null && contentEditor(calendar)) throw GuiNeedsUser("RESUME_UNRELATED_EDITOR")
    }
    fun bound(original: CalendarDraft, candidate: CalendarDraft): Boolean =
        ResumeTimes.valid(original, candidate) &&
            candidate.title.isNotBlank() && candidate.title.length <= 8_000 && candidate.notes.length <= 24_000 &&
            Regex("\\[octopus:[^\\]]+]").findAll(candidate.notes).map { it.value }.toList() == listOf(original.marker) &&
            runCatching { PrivacyGate.safeBody(MailMetadata("", "招聘面试"), true, candidate.title + "\n" + candidate.notes) }.isSuccess

    fun candidate(observation: Observation, original: CalendarDraft, editing: Boolean): CalendarDraft? {
        val timed = ResumeTimes.read(observation, original) ?: return null
        val title = observation.elements.filter { it.resourceId.substringAfterLast('/') in titleIds && it.editable == editing }
            .singleOrNull()?.text ?: return null
        val notes = observation.elements.filter { it.resourceId.substringAfterLast('/') in noteIds && it.editable == editing }
            .singleOrNull()?.text ?: return null
        val candidate = timed.copy(title = title, notes = notes)
        return candidate.takeIf { bound(original, it) && CalendarEvidence.matches(observation, it, editing) }
    }
    fun sameOtherFields(before: Observation, after: Observation, kind: TargetKind = TargetKind.EVENT): Boolean {
        if (before.truncated || after.truncated || before.windowId != after.windowId || before.displayId != after.displayId ||
            before.packageName != after.packageName) return false
        fun fixed(scene: Observation) = scene.elements.map {
            if (it.resourceId.substringAfterLast('/') in titleIds + noteIds + ResumeTimes.fieldIds(kind))
                it.copy(text = "", description = "") else it
        }
        // Values of supported roles may change; node identity, bounds and all other fields must match.
        return fixed(before) == fixed(after)
    }
}
