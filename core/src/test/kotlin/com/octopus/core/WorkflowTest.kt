package com.octopus.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.*

/** Simulated ports exercise production orchestration. These are not Gmail/Calendar success metrics. */
class WorkflowTest {
    private class Store : WriteStore, CanonicalStore {
        val writes = mutableMapOf<String, WriteRecord>()
        val canonical = mutableMapOf<String, List<CanonicalItem>>()
        override suspend fun insertIfAbsent(record: WriteRecord) = writes.getOrPut(record.key) { record }
        override suspend fun read(key: String) = writes[key]
        override suspend fun compareAndSet(expected: WriteRecord, update: WriteRecord): Boolean {
            if (writes[expected.key] != expected) return false
            writes[expected.key] = update; return true
        }
        override suspend fun load(sourceFingerprint: String) = canonical[sourceFingerprint]
        override suspend fun insert(sourceFingerprint: String, items: List<CanonicalItem>) =
            canonical.getOrPut(sourceFingerprint) { items }
        override suspend fun update(sourceFingerprint: String, item: CanonicalItem) {
            canonical[sourceFingerprint] = canonical.getValue(sourceFingerprint).map { if (it.id == item.id) item else it }
        }
    }
    private class Calendar(private val store: Store) : CalendarPort {
        val records = mutableMapOf<String, CalendarDraft>()
        val prepared = mutableMapOf<String, CalendarDraft>()
        var saves = 0
        var inspections = 0
        var unknownLookup = false
        var loseSaveAck = false
        var dishonestProof = false
        var allBusy = false
        var changedConflict = false
        override suspend fun inspect(config: WorkflowConfig, item: RecruitmentItem): CalendarInspection {
            inspections++
            val existing = BusyInterval(Instant.parse(if (changedConflict && inspections > 1)
                "2026-10-27T02:20:00Z" else "2026-10-27T02:15:00Z"), Instant.parse("2026-10-27T02:45:00Z"))
            return CalendarInspection(if (allBusy) listOf(
                BusyInterval(Instant.parse(item.start), Instant.parse(item.end))) else listOf(existing), true)
        }
        override suspend fun find(draft: CalendarDraft): FindRecord {
            if (unknownLookup) return FindRecord.Unknown
            val record = records[draft.key] ?: return FindRecord.Absent
            if (record != draft) return FindRecord.Unknown
            return FindRecord.Found(VerifiedRecord("simulated:${draft.key}", draft.contentDigest(), !dishonestProof))
        }
        override suspend fun prepare(draft: CalendarDraft): PreparedWrite {
            prepared[draft.key] = draft
            return PreparedWrite(draft.key, draft.contentDigest(), SessionToken("simulated", 0))
        }
        override suspend fun save(prepared: PreparedWrite) {
            assertEquals(WriteStatus.WRITE_PENDING, store.writes[prepared.id]?.status)
            val draft = requireNotNull(this.prepared.remove(prepared.id))
            records[draft.key] = draft; saves++
            if (loseSaveAck) error("Simulated ack loss after target persistence")
        }
        override suspend fun reopenAndVerify(draft: CalendarDraft) =
            (find(draft) as? FindRecord.Found)?.proof
    }
    private class Reviews : ReviewPort {
        var choice: ConflictChoice? = ConflictChoice.MARK_RESCHEDULE
        var conflicts = 0
        var itemReviews = 0
        var skipItem = false
        var corrected: ((RecruitmentItem) -> RecruitmentItem)? = null
        override suspend fun reviewItem(item: RecruitmentItem, reasons: List<String>): RecruitmentItem? {
            itemReviews++
            return if (skipItem) null else corrected?.invoke(item) ?: item
        }
        override suspend fun resolveConflict(item: RecruitmentItem, inspection: CalendarInspection): ConflictChoice? {
            conflicts++; return choice
        }
    }
    private class Fixture {
        val config = WorkflowConfig("整理招聘邮件", "me@example.test", "Recruitment", "me@example.test", "Events",
            scopeAuthorized = true)
        val store = Store()
        val calendar = Calendar(store)
        val reviews = Reviews()
        val raw = Json.parseToJsonElement(requireNotNull(javaClass.classLoader.getResourceAsStream("mail-scenarios.json"))
            .bufferedReader().use { it.readText() }).jsonObject.getValue("mails").jsonArray.map { it.jsonObject }
        var documents = raw.map { row ->
            val body = row.getValue("body").jsonPrimitive.content
            MailDocument(SourceEmail(config.mailAccount, row.getValue("sender").jsonPrimitive.content,
                row.getValue("subject").jsonPrimitive.content, row.getValue("sentAt").jsonPrimitive.content, digest(body)),
                body, Regex("https?://[^\\s<>]+").findAll(body).mapIndexed { index, match -> "LINK_${index+1}" to match.value }.toMap())
        }
        var extractionCalls = 0
        var alter: ((ExtractedItem) -> ExtractedItem)? = null
        fun utc(value: String) = OffsetDateTime.parse(value).toInstant().toString()
        val extractor = ItemExtractor { _, mail, safeBody ->
            extractionCalls++
            assertFalse(safeBody.contains("https://"))
            val row = raw.single { it.getValue("subject").jsonPrimitive.content == mail.source.subject }
            fun value(key: String) = row[key]?.jsonPrimitive?.content?.let(::utc)
            val deadline = value("expectedDeadline")
            val start = value("expectedStart")
            val item = ExtractedItem(
                if (deadline != null) ItemType.MATERIALS_DEADLINE else if (mail.source.subject.contains("笔试")) ItemType.EXAM else ItemType.INTERVIEW,
                mail.source.subject, mail.body, "北京时间", "Asia/Shanghai",
                start = start, end = value("expectedEnd"), deadline = deadline,
                remindAt = value("expectedReminder") ?: start?.let { Instant.parse(it).minusSeconds(900).toString() },
                confidence = Confidence.HIGH, evidence = listOf(mail.body))
            Extraction(listOf(alter?.invoke(item) ?: item))
        }
        fun workflow() = MailCalendarWorkflow(object : MailPort {
            override suspend fun read(config: WorkflowConfig) = documents
        }, extractor, calendar, reviews, store, WriteLedger(store))
    }
    @Test fun threeMailLoopCreatesTwoEventsAndOneTaskWithOneConflict() = runTest {
        val fixture = Fixture()
        val report = fixture.workflow().run(fixture.config)
        assertEquals(3, report.verified)
        assertEquals(3, fixture.calendar.saves)
        assertEquals(1, fixture.reviews.conflicts)
        val drafts = fixture.calendar.records.values
        assertEquals(2, drafts.count { it.kind == TargetKind.EVENT })
        assertEquals(1, drafts.count { it.kind == TargetKind.TASK })
        assertTrue(drafts.any { it.title.startsWith("待改约 · ") })
        val task = drafts.single { it.kind == TargetKind.TASK }
        assertEquals("2026-10-28T09:00:00Z", task.remindAt)
        assertEquals("2026-10-28T10:00:00Z", task.deadline)
        assertTrue(task.notes.contains("21:00+11:00"))
        assertTrue(fixture.store.writes.values.all { it.status == WriteStatus.VERIFIED })
    }
    @Test fun rerunUsesCanonicalItemsAndNeverConflictsWithItselfOrDuplicates() = runTest {
        val f = Fixture()
        f.workflow().run(f.config)
        val inspections = f.calendar.inspections
        val report = f.workflow().run(f.config)
        assertEquals(3, report.verified)
        assertEquals(3, f.extractionCalls)
        assertEquals(3, f.calendar.saves)
        assertEquals(inspections, f.calendar.inspections)
    }
    @Test fun lostSaveAckRecoversByQueryAfterWorkflowRestart() = runTest {
        val f = Fixture(); f.documents = f.documents.take(1); f.calendar.loseSaveAck = true
        assertEquals(ItemResult.UNKNOWN, f.workflow().run(f.config).outcomes.single().result)
        assertEquals(WriteStatus.UNKNOWN, f.store.writes.values.single().status)
        val recovered = f.workflow().run(f.config)
        assertEquals(1, recovered.verified)
        assertEquals(1, f.calendar.saves)
        assertEquals(1, f.extractionCalls)
    }
    @Test fun unknownAbsenceCannotAuthorizeCreatingAnything() = runTest {
        val f = Fixture(); f.calendar.unknownLookup = true
        assertTrue(f.workflow().run(f.config).outcomes.all { it.result == ItemResult.UNKNOWN })
        assertEquals(0, f.calendar.saves)
    }
    @Test fun aProofWithoutReopeningNeverCountsAsVerified() = runTest {
        val f = Fixture(); f.calendar.dishonestProof = true
        assertEquals(0, f.workflow().run(f.config).verified)
        assertTrue(f.store.writes.values.all { it.status == WriteStatus.UNKNOWN })
    }
    @Test fun deletedPreviouslyVerifiedRecordIsNotBlindlyRecreated() = runTest {
        val f = Fixture(); f.workflow().run(f.config); f.calendar.records.clear()
        assertEquals(0, f.workflow().run(f.config).verified)
        assertEquals(3, f.calendar.saves)
    }
    @Test fun allConflictChoicesPreserveExistingEvents() = runTest {
        for (choice in ConflictChoice.entries) {
            val f = Fixture(); f.documents = f.documents.take(1); f.reviews.choice = choice
            val report = f.workflow().run(f.config)
            assertEquals(1, report.verified)
            val created = f.calendar.records.values.single()
            assertEquals(if (choice == ConflictChoice.REMINDER_ONLY) TargetKind.TASK else TargetKind.EVENT, created.kind)
            assertEquals(choice == ConflictChoice.MARK_RESCHEDULE, created.title.startsWith("待改约"))
        }
    }
    @Test fun unresolvedConflictDoesNotBlockOtherIndependentItems() = runTest {
        val f = Fixture(); f.reviews.choice = null
        val report = f.workflow().run(f.config)
        assertEquals(2, report.verified)
        assertEquals(1, report.outcomes.count { it.result == ItemResult.AWAITING_USER })
    }
    @Test fun lowConfidenceWaitsForExplicitReviewAndPersistsApproval() = runTest {
        val f = Fixture(); f.alter = { it.copy(confidence = Confidence.LOW) }
        assertEquals(3, f.workflow().run(f.config).verified)
        assertEquals(3, f.reviews.itemReviews)
        f.workflow().run(f.config)
        assertEquals(3, f.reviews.itemReviews)
    }
    @Test fun skippingAmbiguousItemsNeverCreatesThem() = runTest {
        val f = Fixture(); f.alter = { it.copy(confidence = Confidence.LOW) }; f.reviews.skipItem = true
        assertEquals(0, f.workflow().run(f.config).verified)
        assertEquals(0, f.calendar.saves)
    }
    @Test fun fabricatedSourceEvidenceIsRejectedBeforeWriting() = runTest {
        val f = Fixture(); f.alter = { it.copy(evidence = listOf("not in email")) }
        assertFailsWith<IllegalArgumentException> { f.workflow().run(f.config) }
        assertEquals(0, f.calendar.saves)
    }
    @Test fun scopeAuthorizationIsRequiredBeforeMailRead() = runTest {
        val f = Fixture()
        assertFailsWith<IllegalArgumentException> { f.workflow().run(f.config.copy(scopeAuthorized = false)) }
        assertEquals(0, f.extractionCalls)
    }
    @Test fun sensitiveMailBodyNeverReachesExtraction() = runTest {
        for (body in listOf("面试登录验证码 123456", "Interview temporary password: private", "面试系统 API key: private")) {
            val f = Fixture(); f.documents = f.documents.take(1).map { it.copy(body = body) }
            assertEquals(0, f.workflow().run(f.config).verified)
            assertEquals(0, f.extractionCalls)
        }
    }
    @Test fun highConfidenceWrongNormalizedTimeStillRequiresHumanReview() = runTest {
        val f = Fixture(); f.documents = f.documents.take(1)
        f.alter = { it.copy(start = "2026-10-27T02:05:00Z") }
        f.reviews.corrected = { it.copy(start = "2026-10-27T02:00:00Z") }
        assertEquals(1, f.workflow().run(f.config).verified)
        assertEquals(1, f.reviews.itemReviews)
        assertEquals("2026-10-27T02:00:00Z", f.calendar.records.values.single().start)
    }
    @Test fun wrongSourceZoneCannotBypassReviewWithCopiedEvidence() = runTest {
        val f = Fixture(); f.documents = f.documents.take(1)
        f.alter = { it.copy(sourceZone = "America/New_York") }
        f.reviews.corrected = { it.copy(sourceZone = "Asia/Shanghai") }
        assertEquals(1, f.workflow().run(f.config).verified)
        assertEquals(1, f.reviews.itemReviews)
    }
    @Test fun aChangedConflictRequiresNewApprovalBeforeSave() = runTest {
        val f = Fixture(); f.documents = f.documents.take(1); f.calendar.changedConflict = true
        assertEquals(1, f.workflow().run(f.config).verified)
        assertEquals(2, f.reviews.conflicts)
        assertEquals(1, f.calendar.saves)
    }
}
