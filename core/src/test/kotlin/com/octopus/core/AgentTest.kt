package com.octopus.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AgentTest {
    private class MemoryStore : WriteStore {
        private val rows = mutableMapOf<String, WriteRecord>()
        override suspend fun insertIfAbsent(record: WriteRecord) = rows.getOrPut(record.key) { record }
        override suspend fun read(key: String) = rows[key]
        override suspend fun compareAndSet(expected: WriteRecord, update: WriteRecord): Boolean {
            if (rows[expected.key] != expected) return false
            rows[expected.key] = update
            return true
        }
    }
    @Test fun saveUnknownRequiresQueryAndVerifiedNeverReplays() = runTest {
        val store = MemoryStore()
        val ledger = WriteLedger(store)
        assertEquals(WriteDecision.MAY_PREPARE, ledger.prepare("mail1:item1"))
        ledger.beforeSave("mail1:item1")
        val restarted = WriteLedger(store)
        assertEquals(WriteDecision.VERIFY_EXISTING, restarted.prepare("mail1:item1"))
        assertFailsWith<IllegalStateException> { restarted.beforeSave("mail1:item1") }
        restarted.unknown("mail1:item1")
        assertEquals(WriteDecision.VERIFY_EXISTING, restarted.prepare("mail1:item1"))
        restarted.verified("mail1:item1", "calendar:reopened-detail")
        assertEquals(WriteDecision.ALREADY_VERIFIED, restarted.prepare("mail1:item1"))
    }

    @Test fun everyActionUsesNewObservationAndSearchIsExplicit() = runTest {
        val broker = SessionBroker()
        val session = broker.create(2, "mail.test")
        val actions = mutableListOf<Action>()
        var observations = 0
        val observer = object : ObservationPort {
            override suspend fun observe(session: SessionState): Observation = Observation(
                session.token, "o${++observations}", 2, 3, "mail.test", 0,
                listOf(UiElement(1, "EditText", "recruitment", editable = true)))
        }
        val model = object : DecisionModel {
            override suspend fun decide(goal: String, safeObservation: Observation) = Decision(
                when (observations) {
                    1 -> Action.SetText(1, "面试")
                    2 -> Action.SubmitSearch(1)
                    else -> Action.Finish("search results visible")
                }, "fixture")
        }
        val port = object : ActionPort {
            override suspend fun perform(session: SessionState, observation: Observation, action: Action): Boolean {
                actions += action
                return true
            }
        }
        val result = AgentRunner(broker, observer, port, model, EgressPolicy { it }, EventJournal { }, { 0 })
            .run("fixture", session.token)
        assertEquals(3, observations)
        assertEquals(2, actions.size)
        assertIs<Action.SubmitSearch>(actions[1])
        assertEquals("MODEL_STOPPED_UNVERIFIED", result.reason)
    }

    @Test fun lateModelResponseCannotActAfterTakeover() = runTest {
        val broker = SessionBroker()
        val session = broker.create(2, "mail.test")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val observer = object : ObservationPort {
            override suspend fun observe(session: SessionState) =
                Observation(session.token, "o", 2, 3, "mail.test", 0, emptyList())
        }
        val model = object : DecisionModel {
            override suspend fun decide(goal: String, safeObservation: Observation): Decision {
                entered.complete(Unit); release.await()
                return Decision(Action.Click(1), "late")
            }
        }
        val port = object : ActionPort {
            override suspend fun perform(session: SessionState, observation: Observation, action: Action): Boolean =
                error("A revoked model response reached input")
        }
        val job = async {
            AgentRunner(broker, observer, port, model, EgressPolicy { it }, EventJournal { }, { 0 })
                .run("fixture", session.token)
        }
        entered.await()
        broker.requestTakeover(session.token.sessionId)
        release.complete(Unit)
        assertEquals("CONTROL_CHANGED", job.await().reason)
    }

    @Test fun modelCannotTargetElementsRemovedByPrivacyPolicy() = runTest {
        val broker = SessionBroker()
        val session = broker.create(2, "mail.test")
        val observer = object : ObservationPort {
            override suspend fun observe(session: SessionState) = Observation(session.token,
                "o", 2, 3, "mail.test", 0, listOf(UiElement(99, "Button", "private", clickable = true)))
        }
        val port = object : ActionPort {
            override suspend fun perform(session: SessionState, observation: Observation, action: Action): Boolean =
                error("A hidden node reached the input channel")
        }
        val model = object : DecisionModel {
            override suspend fun decide(goal: String, safeObservation: Observation) = Decision(Action.Click(99), "guess")
        }
        val runner = AgentRunner(broker, observer, port, model, EgressPolicy { it.copy(elements = emptyList()) },
            EventJournal { }, { 0 })
        assertEquals("MODEL_TARGET_NOT_ALLOWED", runner.run("fixture", session.token).reason)
    }
    @Test fun wrongDisplayStopsSessionBeforeModelOrInput() = runTest {
        val broker = SessionBroker()
        val session = broker.create(2, "mail.test")
        val observer = object : ObservationPort {
            override suspend fun observe(session: SessionState) =
                Observation(session.token, "o", 0, 3, "mail.test", 0, emptyList())
        }
        val port = object : ActionPort {
            override suspend fun perform(session: SessionState, observation: Observation, action: Action): Boolean =
                error("Wrong display reached input")
        }
        val model = object : DecisionModel {
            override suspend fun decide(goal: String, safeObservation: Observation): Decision =
                error("Wrong display reached model")
        }
        val runner = AgentRunner(broker, observer, port, model, EgressPolicy { it }, EventJournal { }, { 0 })
        assertEquals("BINDING_LOST", runner.run("fixture", session.token).reason)
        assertEquals(Owner.STOPPED, broker.state(session.token.sessionId).owner)
    }
}
