package com.octopus.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.*

class RulesTest {
    private val source = SourceEmail("account-1", "hr@example.test", "面试通知",
        "2026-10-23T01:00:00Z", digest("fixture"))
    private fun event() = RecruitmentItem(ItemType.INTERVIEW, "面试", source,
        "2026-10-27 10:00–10:30", "北京时间", "Asia/Shanghai",
        start = "2026-10-27T02:00:00Z", end = "2026-10-27T02:30:00Z", confidence = Confidence.HIGH)

    @Test fun beijingToSydneyUsesSummerOffset() {
        val utc = TimeRules.toInstant(LocalDateTime.parse("2026-10-27T10:00:00"), TimeRules.beijing)
        assertEquals("2026-10-27T13:00+11:00", TimeRules.local(utc.toString()))
    }
    @Test fun beijingToSydneyUsesWinterOffset() {
        val utc = TimeRules.toInstant(LocalDateTime.parse("2026-07-10T10:00:00"), TimeRules.beijing)
        assertEquals("2026-07-10T12:00+10:00", TimeRules.local(utc.toString()))
    }
    @Test fun nonexistentSydneyTimeRequiresConfirmation() {
        assertFailsWith<IllegalArgumentException> {
            TimeRules.toInstant(LocalDateTime.parse("2026-10-04T02:30:00"), TimeRules.sydney)
        }
    }
    @Test fun ambiguousSydneyTimeRequiresConfirmation() {
        assertFailsWith<IllegalArgumentException> {
            TimeRules.toInstant(LocalDateTime.parse("2026-04-05T02:30:00"), TimeRules.sydney)
        }
    }
    @Test fun adjacentEventsDoNotConflict() {
        val a = BusyInterval(Instant.parse("2026-10-27T02:00:00Z"), Instant.parse("2026-10-27T02:30:00Z"))
        assertFalse(a.overlaps(BusyInterval(a.end, a.end.plusSeconds(600))))
        assertTrue(a.overlaps(BusyInterval(a.start.plusSeconds(900), a.end.plusSeconds(600))))
    }
    @Test fun validEventHasNoConfirmationReasons() { assertTrue(event().confirmationReasons().isEmpty()) }
    @Test fun missingTimeAndZoneRequireConfirmation() {
        assertTrue(event().copy(sourceZone = null, end = null).confirmationReasons().size >= 2)
    }
    @Test fun lowConfidenceRequiresConfirmation() {
        assertTrue(event().copy(confidence = Confidence.LOW).confirmationReasons().isNotEmpty())
    }
    @Test fun modelCannotHideAmbiguityBehindHighConfidence() {
        assertTrue(event().copy(originalZoneText = "CST").confirmationReasons().isNotEmpty())
        assertTrue(event().copy(originalTimeText = "明天10:00").confirmationReasons().isNotEmpty())
    }
    @Test fun deadlineAndReminderAreSeparateAndOrdered() {
        val item = event().copy(type = ItemType.MATERIALS_DEADLINE, deadline = "2026-10-28T10:00:00Z",
            remindAt = "2026-10-28T09:00:00Z")
        assertTrue(item.confirmationReasons().isEmpty())
        assertTrue(item.copy(remindAt = item.deadline).confirmationReasons().isNotEmpty())
    }
    @Test fun stableSourceAndWriteIdentity() {
        assertEquals(source.fingerprint(), source.copy(subject = "  面试通知  ").fingerprint())
        assertEquals(event().writeKey(), event().writeKey())
        assertNotEquals(event().writeKey(), event().copy(start = "2026-10-28T02:00:00Z").writeKey())
    }
    @Test fun equivalentTimestampFormatsHaveOneWriteKey() {
        assertEquals(event().writeKey(), event().copy(start = "2026-10-27T02:00:00.000Z").writeKey())
    }
    @Test fun incompleteSourceCannotInventFingerprint() {
        assertFailsWith<IllegalArgumentException> { source.copy(sentAt = "").fingerprint() }
    }
    @Test fun sensitiveMetadataWinsOverRecruitmentKeywords() {
        assertEquals(MailAccess.EXCLUDED, PrivacyGate.classify(MailMetadata("hr@example.test", "面试登录验证码"), true))
    }
    @Test fun rawInboxDoesNotReceiveAutomaticConsent() {
        assertEquals(MailAccess.NEEDS_REVIEW, PrivacyGate.classify(MailMetadata("hr@example.test", "面试通知"), false))
    }
    @Test fun sensitiveBodyIsBlockedAndLinksAreRedacted() {
        val metadata = MailMetadata("hr@example.test", "面试通知")
        assertFailsWith<IllegalArgumentException> { PrivacyGate.safeBody(metadata, true, "验证码 123456") }
        assertEquals("会议 [LINK_1]", PrivacyGate.safeBody(metadata, true, "会议 https://meet.example.test/?token=secret"))
    }
    @Test fun unknownLoadIsConservativeAndRecoveryHasHysteresis() {
        val governor = LoadGovernor()
        assertTrue(governor.sample(LoadSample(false, false, false)).conservative)
        repeat(2) { assertTrue(governor.sample(LoadSample(false, false, true)).conservative) }
        assertFalse(governor.sample(LoadSample(false, false, true)).conservative)
        assertTrue(governor.sample(LoadSample(true, false, true)).conservative)
    }

    @Test fun defaultDisplayIsRejected() = runTest {
        assertFailsWith<IllegalArgumentException> { SessionBroker().create(0, "mail.test") }
    }
    @Test fun staleEpochAndDuplicateActionAreRejected() = runTest {
        val broker = SessionBroker()
        val state = broker.create(2, "mail.test")
        broker.registerObservation(state.token, "o1")
        broker.agentAction(state.token, "a1", "o1") { }
        assertFailsWith<ActionRejected> { broker.agentAction(state.token, "a1", "o1") { fail() } }
        broker.requestTakeover(state.token.sessionId)
        assertFailsWith<ActionRejected> { broker.agentAction(state.token, "a2", "o1") { fail() } }
    }
    @Test fun takeoverDrainsBeforeHumanAndOnlyAffectsOneSession() = runTest {
        val broker = SessionBroker()
        val a = broker.create(2, "mail.test")
        val b = broker.create(3, "calendar.test")
        broker.registerObservation(a.token, "oa")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val input = async { broker.agentAction(a.token, "a1", "oa") { entered.complete(Unit); release.await() } }
        entered.await()
        val draining = broker.requestTakeover(a.token.sessionId)
        assertEquals(Owner.DRAINING, draining.owner)
        assertFailsWith<ActionRejected> { broker.humanAction(draining.token, "h1") { fail() } }
        assertEquals(Owner.AGENT, broker.state(b.token.sessionId).owner)
        release.complete(Unit)
        input.await()
        val human = broker.state(a.token.sessionId)
        assertEquals(Owner.HUMAN, human.owner)
        broker.humanAction(human.token, "h2") { }
        val returned = broker.returnControl(human.token)
        assertTrue(returned.token.epoch > human.token.epoch)
        assertFailsWith<ActionRejected> { broker.agentAction(returned.token, "a2", "oa") { fail() } }
    }
    @Test fun newObservationInvalidatesOldNodeIds() = runTest {
        val broker = SessionBroker()
        val session = broker.create(2, "mail.test")
        broker.registerObservation(session.token, "old")
        broker.registerObservation(session.token, "new")
        assertFailsWith<ActionRejected> { broker.agentAction(session.token, "a", "old") { fail() } }
    }
    @Test fun stoppedSessionRejectsAllInput() = runTest {
        val broker = SessionBroker()
        val session = broker.create(2, "mail.test")
        val stopped = broker.stop(session.token.sessionId)
        assertFailsWith<ActionRejected> { broker.humanAction(stopped.token, "h") { fail() } }
    }
    @Test fun stoppedDisplayCannotBeReusedUntilOldInputSettles() = runTest {
        val broker = SessionBroker()
        val session = broker.create(2, "mail.test")
        broker.registerObservation(session.token, "o")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = async {
            broker.agentAction(session.token, "a", "o") { entered.complete(Unit); release.await() }
        }
        entered.await()
        broker.stop(session.token.sessionId)
        assertFailsWith<IllegalArgumentException> { broker.create(2, "calendar.test") }
        release.complete(Unit)
        job.await()
        val fresh = broker.create(2, "calendar.test")
        assertNotEquals(session.token.sessionId, fresh.token.sessionId)
    }
}
