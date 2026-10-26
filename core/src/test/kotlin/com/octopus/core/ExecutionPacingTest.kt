package com.octopus.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionPacingTest {
    @Test fun observationAndActionAdmissionsUseTheirOwnSharedClocks()=runTest {
        var policy=Pacing(2.0,500,false)
        val pacer=AdaptivePacer({policy},{testScheduler.currentTime})
        pacer.beforeObservation();pacer.beforeObservation();assertEquals(500,currentTime)
        pacer.beforeAction();pacer.beforeAction();assertEquals(1_000,currentTime)
        policy=Pacing(0.5,1_000,true);pacer.beforeObservation();assertEquals(2_500,currentTime)
        pacer.beforeAction();assertEquals(2_500,currentTime)
    }
    @Test fun increasedLoadDuringWaitExtendsGapAndCancellationDoesNotReserveNextSlot()=runTest {
        var policy=Pacing(2.0,500,false)
        val pacer=AdaptivePacer({policy},{currentTime})
        pacer.beforeObservation()
        val pending=launch {pacer.beforeObservation()};runCurrent();advanceTimeBy(250)
        policy=Pacing(0.5,1_000,true);advanceTimeBy(250);runCurrent();assertTrue(pending.isActive)
        pending.cancelAndJoin();pacer.beforeObservation();assertEquals(2_000,currentTime)
    }
    @Test fun takeoverDuringPacingCannotDispatchOldAction()=runTest {
        val broker=SessionBroker();val state=broker.create(2,"mail");var dispatched=0
        val pacer=AdaptivePacer({Pacing(0.5,1_000,true)},{currentTime})
        val observer=object:ObservationPort {
            override suspend fun observe(session:SessionState)=Observation(session.token,"o:$currentTime",2,7,"mail",currentTime,emptyList())
        }
        val navigator=GuiNavigator(broker,observer,object:ActionPort {
            override suspend fun perform(session:SessionState,observation:Observation,action:Action):Boolean {dispatched++;return true}
        },object:DecisionModel {override suspend fun decide(goal:String,safeObservation:Observation)=error("No model")},
            {currentTime},pacer=pacer)
        val observation=navigator.observe(state.token)
        navigator.act(state.token,observation,Action.Click(1))
        val pending=async {runCatching {navigator.act(state.token,observation,Action.Click(1))}};runCurrent()
        broker.requestTakeover(state.token.sessionId);advanceUntilIdle()
        assertTrue(pending.await().exceptionOrNull() is ActionRejected);assertEquals(1,dispatched)
    }
    @Test fun twoSessionsCannotDoubleTheGlobalObservationRate()=runTest {
        val pacer=AdaptivePacer({Pacing(0.5,1_000,true)},{currentTime})
        val times=mutableListOf<Long>()
        coroutineScope {repeat(3){launch {pacer.beforeObservation();times+=currentTime}}}
        assertEquals(listOf(0L,2_000L,4_000L),times)
    }
    @Test fun hiddenCaptureHasNoEncodingAndDemandExpiresWithoutRenewal() {
        val budget=CaptureBudget();assertNull(budget.claim(0))
        budget.configure(true,500,0);val ticket=budget.claim(0)!!
        assertTrue(budget.mayPublish(ticket,20));assertNull(budget.claim(499));assertNotNull(budget.claim(500))
        assertNull(budget.claim(3_001));assertFalse(budget.current(ticket,3_001,500))
    }
    @Test fun hideOrPolicyChangeRejectsInFlightAndCachedFramesButRenewalDoesNot() {
        val budget=CaptureBudget();budget.configure(true,500,0);val first=budget.claim(0)!!
        budget.configure(true,500,1_000);assertTrue(budget.mayPublish(first,1_001))
        assertFalse(budget.current(first,1_001,0))
        budget.configure(false,500,1_002);assertFalse(budget.mayPublish(first,1_003))
        budget.configure(true,2_000,1_004);val second=budget.claim(1_004)!!
        assertNotEquals(first,second);assertFalse(budget.current(first,1_005,1_004));assertTrue(budget.current(second,1_005,1_004))
    }
    @Test fun expiredLeaseAndInvalidRatesCannotBeSilentlyReused() {
        val budget=CaptureBudget();budget.configure(true,500,0);val first=budget.claim(0)!!
        budget.configure(true,500,4_000);assertFalse(budget.mayPublish(first,4_001))
        assertFailsWith<IllegalArgumentException>{budget.configure(true,0,0)}
    }
    @Test fun expiredObservationAfterWaitRequestsRecoveryInsteadOfDroppingDraft()=runTest {
        val broker=SessionBroker();val session=broker.create(2,"mail")
        val navigator=GuiNavigator(broker,object:ObservationPort {
            override suspend fun observe(session:SessionState)=error("No observation requested")
        },object:ActionPort {
            override suspend fun perform(session:SessionState,observation:Observation,action:Action)=error("No dispatch")
        },object:DecisionModel {override suspend fun decide(goal:String,safeObservation:Observation)=error("No model")},
            {6_000},pacer=Unpaced)
        val observation=Observation(session.token,"old",2,7,"mail",0,emptyList())
        val error=assertFailsWith<GuiNeedsUser>{navigator.act(session.token,observation,Action.Click(1))}
        assertEquals("OBSERVATION_EXPIRED",error.reasonCode)
    }
    @Test fun staticProducerFrameCanBeReadAgainWithoutKeepingAdditionalBuffers() {
        class Frame:AutoCloseable {var closes=0;override fun close(){closes++}}
        val owner=LatestResource<Frame>();val first=Frame();val next=Frame()
        owner.replace(first);assertSame(first,owner.useCurrent {it});assertSame(first,owner.useCurrent {it})
        owner.replace(next);assertEquals(1,first.closes);assertSame(next,owner.useCurrent {it})
        owner.close();owner.close();assertEquals(1,next.closes);assertNull(owner.useCurrent {it})
    }
    @Test fun lateFrameAfterCloseIsDisposedInsteadOfResurrectingPreview() {
        class Frame:AutoCloseable {var closes=0;override fun close(){closes++}}
        val owner=LatestResource<Frame>();owner.close();val late=Frame()
        owner.replace(late);assertEquals(1,late.closes);assertNull(owner.useCurrent {it})
    }
}
