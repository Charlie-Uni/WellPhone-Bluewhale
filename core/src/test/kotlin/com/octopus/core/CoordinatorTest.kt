package com.octopus.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CoordinatorTest {
    private fun item() = RecruitmentItem(ItemType.INTERVIEW, "面试",
        SourceEmail("a", "hr@example.test", "面试", "2026-10-23T01:00:00Z", digest("fixture")),
        "10月27日10:00–10:30", "北京时间", "Asia/Shanghai",
        start = "2026-10-27T02:00:00Z", end = "2026-10-27T02:30:00Z", confidence = Confidence.HIGH)

    @Test fun planIsOneCallAndSummaryPreservesUnknown() = runTest {
        var calls = 0
        val coordinator = Coordinator(Planner { calls++; TaskPlan("整理日程", listOf("读取", "核实")) }, SessionBroker())
        coordinator.start("整理招聘邮件")
        assertEquals(1, calls)
        assertEquals(TaskSummary(1, 1, 1, 1), coordinator.summary(ItemResult.entries.map {
            ItemOutcome(it.name, it, "fixture")
        }))
    }
    @Test fun changedItemAndRevokedEpochCannotUseOldCard() = runTest {
        val broker = SessionBroker()
        val session = broker.create(2, "calendar.test")
        val coordinator = Coordinator(Planner { error("Not needed") }, broker)
        val item = item()
        val card = coordinator.requestConflict(item, session.token)
        assertFailsWith<IllegalStateException> {
            coordinator.confirm(card.id, item.copy(title = "changed"), session.token, ConflictChoice.KEEP_BOTH)
        }
        broker.requestTakeover(session.token.sessionId)
        assertFailsWith<IllegalStateException> {
            coordinator.confirm(card.id, item, session.token, ConflictChoice.KEEP_BOTH)
        }
    }
    @Test fun confirmationIsConsumedExactlyOnce() = runTest {
        val broker = SessionBroker()
        val session = broker.create(2, "calendar.test")
        val coordinator = Coordinator(Planner { error("Not needed") }, broker)
        val card = coordinator.requestConflict(item(), session.token)
        assertEquals(ConflictChoice.MARK_RESCHEDULE,
            coordinator.confirm(card.id, item(), session.token, ConflictChoice.MARK_RESCHEDULE))
        assertFailsWith<IllegalArgumentException> {
            coordinator.confirm(card.id, item(), session.token, ConflictChoice.KEEP_BOTH)
        }
    }
}
