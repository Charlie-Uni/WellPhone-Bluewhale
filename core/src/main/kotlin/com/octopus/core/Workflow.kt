package com.octopus.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Serializable data class WorkflowConfig(
    val goal: String, val mailAccount: String, val recruitmentLabel: String,
    val calendarAccount: String, val calendarName: String,
    val localZone: String = "Australia/Sydney", val maxMails: Int = 3,
    val scopeAuthorized: Boolean = false,
    val taskListName: String = "My Tasks",
) {
    fun validate() {
        require(goal.isNotBlank() && recruitmentLabel.isNotBlank())
        require(mailAccount.contains("@") && calendarAccount.contains("@") && calendarName.isNotBlank() && taskListName.isNotBlank())
        require(scopeAuthorized && maxMails in 1..5)
        ZoneId.of(localZone)
    }
}

data class MailDocument(val source: SourceEmail, val body: String, val links: Map<String, String> = emptyMap())
@Serializable data class ExtractedItem(
    val type: ItemType, val title: String, val originalTimeText: String,
    val originalZoneText: String, val sourceZone: String?,
    val start: String? = null, val end: String? = null, val deadline: String? = null,
    val remindAt: String? = null, val meetingLinkRef: String? = null,
    val confidence: Confidence, val evidence: List<String>, val ambiguities: List<String> = emptyList(),
) {
    fun attach(mail: MailDocument): RecruitmentItem {
        fun resolve(text: String): String = mail.links.entries.fold(text) { value, (key, url) ->
            value.replace("[$key]", url)
        }
        require(evidence.isNotEmpty() && evidence.all { it.isNotBlank() && mail.body.contains(resolve(it)) }) {
            "Extraction has no matching source evidence"
        }
        require(originalTimeText.isNotBlank() && mail.body.contains(originalTimeText))
        require(meetingLinkRef == null || meetingLinkRef in mail.links)
        return RecruitmentItem(type, title, mail.source, originalTimeText, originalZoneText, sourceZone,
            start, end, deadline, remindAt, meetingLinkRef, confidence, evidence, ambiguities)
    }
}
@Serializable data class Extraction(val items: List<ExtractedItem>)
fun interface ItemExtractor { suspend fun extract(config: WorkflowConfig, mail: MailDocument, safeBody: String): Extraction }
interface MailPort { suspend fun read(config: WorkflowConfig): List<MailDocument> }

@Serializable data class CanonicalItem(
    val id: String, val item: RecruitmentItem, val humanConfirmed: Boolean = false,
    val choice: ConflictChoice? = null, val choiceDigest: String? = null,
)
interface CanonicalStore {
    suspend fun load(sourceFingerprint: String): List<CanonicalItem>?
    suspend fun insert(sourceFingerprint: String, items: List<CanonicalItem>): List<CanonicalItem>
    suspend fun update(sourceFingerprint: String, item: CanonicalItem)
}

@Serializable enum class TargetKind { EVENT, TASK }
@Serializable data class CalendarDraft(
    val key: String, val kind: TargetKind, val title: String, val account: String,
    val calendarName: String, val localZone: String, val start: String?, val end: String?,
    val deadline: String?, val remindAt: String, val notes: String,
) {
    val marker: String get() = "[octopus:$key]"
    fun contentDigest(): String = digest(Json.encodeToString(this))
    companion object {
        fun from(canonical: CanonicalItem, config: WorkflowConfig, links: Map<String, String>): CalendarDraft {
            val item = canonical.item
            val reminderOnly = canonical.choice == ConflictChoice.REMINDER_ONLY
            val kind = if (item.type == ItemType.MATERIALS_DEADLINE || reminderOnly) TargetKind.TASK else TargetKind.EVENT
            val reminder = item.remindAt ?: item.start?.let { Instant.parse(it).minusSeconds(900).toString() }
                ?: error("Reminder must be explicit")
            val title = (if (canonical.choice == ConflictChoice.MARK_RESCHEDULE) "待改约 · " else "") + item.title
            val effectiveDeadline = item.deadline ?: if (reminderOnly) item.start else null
            val notes = listOf(
                "[octopus:${canonical.id}]", "来源：${item.source.subject}",
                "原文：${item.originalTimeText}（${item.originalZoneText}）",
                effectiveDeadline?.let { "截止：${TimeRules.local(it, ZoneId.of(config.localZone))}" }.orEmpty(),
                "确认时区：${item.sourceZone}",
                item.meetingLinkRef?.let { links[it] }.orEmpty(),
            ).filter { it.isNotBlank() }.joinToString("\n")
            return CalendarDraft(canonical.id, kind, title, config.calendarAccount,
                if (kind == TargetKind.TASK) config.taskListName else config.calendarName,
                config.localZone, if (kind == TargetKind.EVENT) item.start else null,
                if (kind == TargetKind.EVENT) item.end else null,
                effectiveDeadline, reminder, notes)
        }
    }
}

data class CalendarInspection(val busy: List<BusyInterval>, val complete: Boolean)
data class VerifiedRecord(val locator: String, val draftDigest: String, val reopened: Boolean)
sealed interface FindRecord {
    data class Found(val proof: VerifiedRecord) : FindRecord
    data object Absent : FindRecord
    data object Unknown : FindRecord
}
data class PreparedWrite(val id: String, val draftDigest: String, val token: SessionToken)
interface CalendarPort {
    suspend fun inspect(config: WorkflowConfig, item: RecruitmentItem): CalendarInspection
    suspend fun find(draft: CalendarDraft): FindRecord
    suspend fun prepare(draft: CalendarDraft): PreparedWrite
    suspend fun save(prepared: PreparedWrite)
    suspend fun reopenAndVerify(draft: CalendarDraft): VerifiedRecord?
}
interface ReviewPort {
    suspend fun reviewItem(item: RecruitmentItem, reasons: List<String>): RecruitmentItem?
    suspend fun resolveConflict(item: RecruitmentItem, inspection: CalendarInspection): ConflictChoice?
}
@Serializable data class WorkflowProgress(val stage: String, val detail: String, val completed: Int = 0)
data class WorkflowReport(val outcomes: List<ItemOutcome>, val stoppedReason: String? = null) {
    val verified get() = outcomes.count { it.result == ItemResult.VERIFIED }
}

/**
 * One writer for the entire calendar workflow. Source checkpoints survive new runs and
 * re-extraction is avoided after a canonical snapshot exists. Only CalendarPort evidence verifies.
 */
class MailCalendarWorkflow(
    private val mail: MailPort, private val extractor: ItemExtractor,
    private val calendar: CalendarPort, private val review: ReviewPort,
    private val canonicalStore: CanonicalStore, private val ledger: WriteLedger,
    private val writer: Mutex = Mutex(),
    private val onOutcome: (ItemOutcome) -> Unit = {},
    private val progress: (WorkflowProgress) -> Unit = {},
) {
    suspend fun run(config: WorkflowConfig): WorkflowReport = writer.withLock {
        config.validate()
        val outcomes = mutableListOf<ItemOutcome>()
        fun record(outcome: ItemOutcome) { outcomes += outcome; onOutcome(outcome) }
        progress(WorkflowProgress("READING", "正在读取授权招聘范围"))
        val documents = mail.read(config).take(config.maxMails)
        for (document in documents) {
            val sourceKey = document.source.fingerprint()
            if (document.source.accountKey != config.mailAccount) error("Mail account differs from authorization")
            if (PrivacyGate.classify(MailMetadata(document.source.sender, document.source.subject), true) != MailAccess.CANDIDATE) continue
            val safeBody = runCatching {
                PrivacyGate.safeBody(MailMetadata(document.source.sender, document.source.subject), true, document.body)
            }.getOrNull() ?: continue
            val saved = canonicalStore.load(sourceKey) ?: run {
                progress(WorkflowProgress("EXTRACTING", "提取招聘事项"))
                val extraction = extractor.extract(config, document, safeBody)
                require(extraction.items.size <= 8)
                val attached = extraction.items.mapIndexed { index, candidate ->
                    CanonicalItem(digest("$sourceKey:$index"), candidate.attach(document))
                }
                canonicalStore.insert(sourceKey, attached)
            }
            for (initial in saved) {
                var current = initial
                val reasons = current.item.confirmationReasons() + TimeGrounding.issues(current.item, document.body)
                if (reasons.isNotEmpty() && !current.humanConfirmed) {
                    progress(WorkflowProgress("AWAITING_REVIEW", "请确认时间或提取结果", outcomes.size))
                    val corrected = review.reviewItem(current.item, reasons)
                    if (corrected == null) {
                        record(ItemOutcome(current.id, ItemResult.AWAITING_USER, "用户暂未确认"))
                        continue
                    }
                    require(corrected.source == document.source) { "Source cannot be edited" }
                    validateTimes(corrected)
                    current = current.copy(item = corrected, humanConfirmed = true, choice = null, choiceDigest = null)
                    canonicalStore.update(sourceKey, current)
                }
                validateTimes(current.item)
                // Deduplicate before conflict inspection, so an existing item never conflicts with itself.
                val originalDraft = CalendarDraft.from(current, config, document.links)
                val decision = ledger.prepare(originalDraft.key)
                val existing = calendar.find(originalDraft)
                if (existing is FindRecord.Found && valid(existing.proof, originalDraft)) {
                    if (decision == WriteDecision.MAY_PREPARE) ledger.beforeSave(originalDraft.key)
                    if (decision != WriteDecision.ALREADY_VERIFIED) ledger.verified(originalDraft.key, existing.proof.locator)
                    record(ItemOutcome(originalDraft.key, ItemResult.VERIFIED, "重开已有记录并核实；未重复创建"))
                    continue
                }
                if (existing != FindRecord.Absent || decision != WriteDecision.MAY_PREPARE) {
                    record(ItemOutcome(originalDraft.key, ItemResult.UNKNOWN, "无法证实不存在；未重放保存"))
                    continue
                }
                val contentDigest = digest(Json.encodeToString(current.item))
                if (current.item.type != ItemType.MATERIALS_DEADLINE) {
                    progress(WorkflowProgress("CHECKING_CONFLICTS", "检查已有日程", outcomes.size))
                    val inspection = calendar.inspect(config, current.item)
                    val interval = BusyInterval(Instant.parse(current.item.start), Instant.parse(current.item.end))
                    fun approvalHash(value: CalendarInspection) = digest(contentDigest + value.complete +
                        value.busy.sortedBy { it.start }.joinToString { "${it.start}/${it.end}" })
                    if (!inspection.complete || inspection.busy.any { it.overlaps(interval) }) {
                        if (current.choice == null || current.choiceDigest != approvalHash(inspection)) {
                            progress(WorkflowProgress("AWAITING_CONFLICT", "发现冲突或日程范围不完整", outcomes.size))
                            val choice = review.resolveConflict(current.item, inspection)
                            if (choice == null) {
                                record(ItemOutcome(current.id, ItemResult.AWAITING_USER, "等待冲突选择"))
                                continue
                            }
                            current = current.copy(choice = choice, choiceDigest = approvalHash(inspection))
                            canonicalStore.update(sourceKey, current)
                        }
                    }
                    val checked = calendar.inspect(config, current.item)
                    if (approvalHash(checked) != approvalHash(inspection) &&
                        (!checked.complete || checked.busy.any { it.overlaps(interval) })) {
                        val choice = review.resolveConflict(current.item, checked)
                        if (choice == null) {
                            record(ItemOutcome(current.id, ItemResult.AWAITING_USER, "日程改变，等待重新确认"))
                            continue
                        }
                        current = current.copy(choice = choice, choiceDigest = approvalHash(checked))
                        canonicalStore.update(sourceKey, current)
                    }
                }
                val draft = CalendarDraft.from(current, config, document.links)
                progress(WorkflowProgress("PREPARING", "填写并核对新事项", outcomes.size))
                val prepared = calendar.prepare(draft)
                check(prepared.draftDigest == draft.contentDigest())
                ledger.beforeSave(draft.key)
                try {
                    progress(WorkflowProgress("SAVING", "保存后将离开并重新打开核实", outcomes.size))
                    calendar.save(prepared)
                    val proof = calendar.reopenAndVerify(draft)
                    if (proof != null && valid(proof, draft)) {
                        ledger.verified(draft.key, proof.locator)
                        record(ItemOutcome(draft.key, ItemResult.VERIFIED, "已离开并重开核实"))
                    } else {
                        ledger.unknown(draft.key)
                        record(ItemOutcome(draft.key, ItemResult.UNKNOWN, "保存结果尚未核实"))
                    }
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { ledger.unknown(draft.key) }
                    record(ItemOutcome(draft.key, ItemResult.UNKNOWN, "保存期间被暂停；恢复时先查询"))
                    throw cancelled
                } catch (_: Exception) {
                    ledger.unknown(draft.key)
                    record(ItemOutcome(draft.key, ItemResult.UNKNOWN, "保存回执未知；需要查询恢复"))
                }
            }
        }
        progress(WorkflowProgress("FINISHED", "已核实 ${outcomes.count { it.result == ItemResult.VERIFIED }} 项；其余保留各自状态", outcomes.size))
        WorkflowReport(outcomes)
    }
    private fun valid(proof: VerifiedRecord, draft: CalendarDraft) =
        proof.reopened && proof.locator.isNotBlank() && proof.draftDigest == draft.contentDigest()
}

fun validateTimes(item: RecruitmentItem) {
    require(item.title.isNotBlank() && item.sourceZone != null)
    ZoneId.of(item.sourceZone)
    if (item.type == ItemType.MATERIALS_DEADLINE) {
        require(Instant.parse(item.remindAt).isBefore(Instant.parse(item.deadline)))
    } else {
        val start = Instant.parse(item.start)
        require(start.isBefore(Instant.parse(item.end)))
        item.remindAt?.let { require(Instant.parse(it).isBefore(start)) }
    }
}
