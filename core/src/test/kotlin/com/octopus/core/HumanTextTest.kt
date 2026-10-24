package com.octopus.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HumanTextTest {
    private class Port : HumanTextPort {
        val writes = mutableListOf<String>()
        val cleared = mutableListOf<String>()
        var captureGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        var result = TextWriteResult.VERIFIED
        var fields = listOf(HumanTextField("title", "标题", "旧标题", true))
        override suspend fun capture(session: SessionState, draftId: String): List<HumanTextField> {
            captureGate?.await();return fields
        }
        override suspend fun replace(session: SessionState, draftId: String, fieldId: String, value: String,
            stillCurrent: () -> Boolean): TextWriteResult {
            writeGate?.await()
            if(!stillCurrent()) throw ActionRejected("Stale final dispatch")
            writes+=value;return result
        }
        override fun clear(draftId: String) { cleared+=draftId }
    }
    private suspend fun human(broker: SessionBroker): TouchTarget {
        val agent=broker.create(2,"mail")
        val state=broker.requestTakeover(agent.token.sessionId)
        return TouchTarget(state.token,2,"mail",1,720,1280)
    }
    @Test fun chineseMultilineEmojiAndEmptyReplacementArePreserved() = runTest {
        val broker=SessionBroker();val target=human(broker);val port=Port()
        val text=HumanTextController(broker,port){true}
        for(value in listOf("中文面试 🐙\n悉尼 18:30", "")) {
            val draft=text.open(target)
            assertEquals("旧标题",draft.fields.single().value)
            assertEquals(TextWriteResult.VERIFIED,text.apply(draft,"title",value))
        }
        assertEquals(listOf("中文面试 🐙\n悉尼 18:30",""),port.writes)
        assertEquals(0,broker.state(target.token.sessionId).inFlight)
    }
    @Test fun invalidationWhileCaptureIsPendingCannotReopenEditor() = runTest {
        val broker=SessionBroker();val target=human(broker);val port=Port()
        port.captureGate=CompletableDeferred()
        val text=HumanTextController(broker,port){true}
        val pending=async { runCatching { text.open(target) } };runCurrent()
        text.invalidate();port.captureGate!!.complete(Unit);runCurrent()
        assertIs<ActionRejected>(pending.await().exceptionOrNull());assertEquals(1,port.cleared.size)
    }
    @Test fun hiddenOrResizedPreviewRejectsAnOldDraft() = runTest {
        val broker=SessionBroker();val target=human(broker);val port=Port();var current=true
        val text=HumanTextController(broker,port){current};val draft=text.open(target)
        current=false
        assertFailsWith<ActionRejected>{text.apply(draft,"title","new")}
        assertTrue(port.writes.isEmpty());assertTrue(draft.id in port.cleared)
    }
    @Test fun handbackRevokesDraftAndRequiresNewAgentObservation() = runTest {
        val broker=SessionBroker();val target=human(broker);val port=Port()
        val text=HumanTextController(broker,port){true};val draft=text.open(target)
        val returned=broker.returnControl(target.token)
        assertFailsWith<ActionRejected>{text.apply(draft,"title","new")}
        assertFailsWith<ActionRejected>{broker.agentAction(returned.token,"old-action","old-observation"){}}
        broker.registerObservation(returned.token,"fresh")
        broker.agentAction(returned.token,"new-action","fresh"){}
        assertTrue(port.writes.isEmpty())
    }
    @Test fun unknownWriteIsConsumedAndNeverReplayed() = runTest {
        val broker=SessionBroker();val target=human(broker);val port=Port().apply {result=TextWriteResult.UNKNOWN}
        val text=HumanTextController(broker,port){true};val draft=text.open(target)
        assertEquals(TextWriteResult.UNKNOWN,text.apply(draft,"title","new"))
        assertFailsWith<ActionRejected>{text.apply(draft,"title","new")}
        assertEquals(listOf("new"),port.writes)
    }
    @Test fun textHoldsLeaseUntilSettledButOtherSessionRemainsUsable() = runTest {
        val broker=SessionBroker();val target=human(broker);val other=broker.create(3,"calendar");val port=Port()
        val text=HumanTextController(broker,port){true};val draft=text.open(target)
        port.writeGate=CompletableDeferred()
        val pending=async {text.apply(draft,"title","new")};runCurrent()
        assertFailsWith<IllegalStateException>{broker.returnControl(target.token)}
        assertFailsWith<ActionRejected>{broker.humanAction(target.token,"touch"){}}
        broker.registerObservation(other.token,"obs")
        assertEquals("ok",broker.agentAction(other.token,"other","obs"){"ok"})
        port.writeGate!!.complete(Unit);pending.await()
        assertEquals(Owner.AGENT,broker.returnControl(target.token).owner)
    }
    @Test fun cancellationBeforeFinalDispatchPreventsWrite() = runTest {
        val broker=SessionBroker();val target=human(broker);val port=Port()
        val text=HumanTextController(broker,port){true};val draft=text.open(target)
        port.writeGate=CompletableDeferred()
        val pending=async {runCatching{text.apply(draft,"title","new")}};runCurrent()
        text.invalidate();port.writeGate!!.complete(Unit)
        assertIs<ActionRejected>(pending.await().exceptionOrNull());assertTrue(port.writes.isEmpty())
        assertEquals(0,broker.state(target.token.sessionId).inFlight)
    }
    @Test fun arbitraryFieldAndOversizeTextNeverReachPort() = runTest {
        val broker=SessionBroker();val target=human(broker);val port=Port();val text=HumanTextController(broker,port){true}
        val draft=text.open(target)
        assertFailsWith<ActionRejected>{text.apply(draft,"other","value")}
        val next=text.open(target)
        assertFailsWith<ActionRejected>{text.apply(next,"title","x".repeat(HumanTextController.MAX_LENGTH+1))}
        assertTrue(port.writes.isEmpty())
    }
    @Test fun readOnlyAndWrongPackageFailBeforeReadingText() = runTest {
        val broker=SessionBroker();val target=human(broker);val port=Port();val text=HumanTextController(broker,port){true}
        assertFailsWith<ActionRejected>{text.open(target.copy(packageName="other"))}
        broker.returnControl(target.token)
        assertFailsWith<ActionRejected>{text.open(target)}
    }
    @Test fun replacementDraftRevokesPreviousFieldsAndClearsSnapshots() = runTest {
        val broker=SessionBroker();val target=human(broker);val port=Port();val text=HumanTextController(broker,port){true}
        val old=text.open(target);val fresh=text.open(target)
        assertTrue(old.id in port.cleared)
        assertFailsWith<ActionRejected>{text.apply(old,"title","old")}
        text.apply(fresh,"title","fresh")
        assertEquals(listOf("fresh"),port.writes)
    }
}
