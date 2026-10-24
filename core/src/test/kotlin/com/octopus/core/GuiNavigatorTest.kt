package com.octopus.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class GuiNavigatorTest {
    @Test fun slowDecisionIsRevalidatedAgainstFreshObservationBeforeDispatch() = runTest {
        val broker = SessionBroker()
        val session = broker.create(2, "calendar.test")
        var done = false
        var reads = 0
        val observer = object : ObservationPort {
            override suspend fun observe(session: SessionState) = Observation(session.token,
                "o${++reads}", 2, 3, "calendar.test", testScheduler.currentTime,
                listOf(UiElement(1, "Button", "Open", clickable = true)))
        }
        val model = object : DecisionModel {
            override suspend fun decide(goal: String, safeObservation: Observation): Decision {
                delay(6_000)
                return Decision(Action.Click(1), "fixture")
            }
        }
        val port = object : ActionPort {
            override suspend fun perform(session: SessionState, observation: Observation, action: Action): Boolean {
                assertEquals(testScheduler.currentTime, observation.capturedAtMillis)
                done = true; return true
            }
        }
        GuiNavigator(broker, observer, port, model, { testScheduler.currentTime })
            .reach(session.token, "open", { it }, { _, _ -> true }, { done })
        assertTrue(reads >= 3)
    }
    @Test fun changedScreenDiscardsTheOldModelDecision() = runTest {
        val broker = SessionBroker()
        val session = broker.create(2, "calendar.test")
        var read = 0
        var calls = 0
        var done = false
        val observer = object : ObservationPort {
            override suspend fun observe(session: SessionState): Observation {
                read++
                val node = if (read == 1) 1 else 2
                return Observation(session.token, "o$read", 2, 3, "calendar.test", testScheduler.currentTime,
                    listOf(UiElement(node, "Button", "Open $node", clickable = true)))
            }
        }
        val model = object : DecisionModel {
            override suspend fun decide(goal: String, safeObservation: Observation): Decision {
                calls++; return Decision(Action.Click(safeObservation.elements.single().id), "fixture")
            }
        }
        val actions = object : ActionPort {
            override suspend fun perform(session: SessionState, observation: Observation, action: Action): Boolean {
                assertEquals(2, (action as Action.Click).nodeId); done = true; return true
            }
        }
        GuiNavigator(broker, observer, actions, model, { testScheduler.currentTime })
            .reach(session.token, "open", { it }, { _, _ -> true }, { done })
        assertEquals(2, calls)
    }
    @Test fun finishWithoutLocalEvidenceCannotCompleteAPhase() = runTest {
        val broker = SessionBroker()
        val session = broker.create(2, "calendar.test")
        val observer = object : ObservationPort {
            override suspend fun observe(session: SessionState) = Observation(session.token,
                "o", 2, 3, "calendar.test", 0, emptyList())
        }
        val model = object : DecisionModel {
            override suspend fun decide(goal: String, safeObservation: Observation) =
                Decision(Action.Finish("I saved it"), "untrusted claim")
        }
        val actions = object : ActionPort {
            override suspend fun perform(session: SessionState, observation: Observation, action: Action): Boolean = error("No input")
        }
        assertEquals("NO_VERIFIABLE_GUI_EVIDENCE", assertFailsWith<GuiNeedsUser> {
            GuiNavigator(broker, observer, actions, model, { 0 }).reach(session.token, "test", { it }, { _, _ -> false }, { false })
        }.reasonCode)
    }
    @Test fun lateNavigationResponseAfterTakeoverCannotDispatch() = runTest {
        val broker = SessionBroker()
        val session = broker.create(2, "calendar.test")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val observer = object : ObservationPort {
            override suspend fun observe(session: SessionState) = Observation(session.token, "o", 2, 3,
                "calendar.test", 0, listOf(UiElement(1,"Button","Open",clickable = true)))
        }
        val model = object : DecisionModel {
            override suspend fun decide(goal: String, safeObservation: Observation): Decision {
                entered.complete(Unit); release.await(); return Decision(Action.Click(1), "late")
            }
        }
        val actions = object : ActionPort {
            override suspend fun perform(session: SessionState, observation: Observation, action: Action): Boolean = error("Revoked input")
        }
        val job = async {
            assertFailsWith<ActionRejected> {
                GuiNavigator(broker, observer, actions, model, { 0 }).reach(session.token, "test", { it }, { _, _ -> true }, { false })
            }
        }
        entered.await(); broker.requestTakeover(session.token.sessionId); release.complete(Unit); job.await()
    }
}
