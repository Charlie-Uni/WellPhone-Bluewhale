package com.octopus.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewTouchTest {
    private val point = TouchPoint(100f, 200f)
    @Test fun fitMappingRejectsLetterboxingAndEdges() {
        val transform = PreviewTransform(1, 400, 400, 720, 1280)
        assertNull(transform.map(TouchPoint(10f, 100f)))
        assertNull(transform.map(TouchPoint(200f, 400f)))
        assertNull(transform.map(TouchPoint(Float.NaN, 10f)))
        assertEquals(TouchPoint(360f, 640f), transform.map(TouchPoint(200f, 200f)))
    }
    @Test fun resizingPreservesDisplayCoordinatesWithoutRecreatingDisplay() {
        val compact = PreviewTransform(1, 280, 360, 720, 1280)
        val large = PreviewTransform(2, 600, 900, 720, 1280)
        assertEquals(compact.map(TouchPoint(140f, 180f)), large.map(TouchPoint(300f, 450f)))
        assertNotEquals(compact.version, large.version)
    }
    @Test fun doubleTapOnlyResizesAndNeverTouchesApp() {
        val gestures = PreviewGestures(10f, 40f)
        assertTrue(gestures.down(point, 0).isEmpty())
        assertTrue(gestures.up(point, 50).isEmpty())
        assertTrue(gestures.down(point, 150).isEmpty())
        assertEquals(listOf(PreviewEffect.ToggleSize), gestures.up(point, 180))
        assertTrue(gestures.tick(1_000).isEmpty())
    }
    @Test fun singleTapWaitsForDoubleTapWindow() {
        val gestures = PreviewGestures(10f, 40f)
        gestures.down(point, 0); gestures.up(point, 50)
        assertTrue(gestures.tick(349).isEmpty())
        assertEquals(listOf(PreviewEffect.Tap(point)), gestures.tick(350))
    }
    @Test fun hideOrResizeDropsUnconfirmedTap() {
        val gestures = PreviewGestures(10f, 40f)
        gestures.down(point, 0); gestures.up(point, 20); gestures.cancel()
        assertTrue(gestures.tick(1_000).isEmpty())
    }
    @Test fun dragStreamsFromInitialDownAndCancelsCleanly() {
        val gestures = PreviewGestures(10f, 40f)
        gestures.down(point, 0)
        val moved = TouchPoint(100f, 240f)
        assertEquals(listOf(PreviewEffect.Begin(point), PreviewEffect.Move(moved)), gestures.move(moved, 100))
        assertEquals(listOf(PreviewEffect.Cancel), gestures.cancel())
        assertTrue(gestures.up(moved, 200).isEmpty())
    }
    @Test fun holdStartsAStreamWithoutAResize() {
        val gestures = PreviewGestures(10f, 40f)
        gestures.down(point, 0)
        assertEquals(listOf(PreviewEffect.Begin(point)), gestures.tick(500))
        assertEquals(listOf(PreviewEffect.End(point)), gestures.up(point, 900))
    }
    @Test fun movingDuringSecondTapDoesNotReplayFirstTap() {
        val gestures = PreviewGestures(10f, 40f)
        gestures.down(point, 0); gestures.up(point, 20); gestures.down(point, 50)
        assertTrue(gestures.move(TouchPoint(200f, 200f), 100).isEmpty())
        assertTrue(gestures.up(point, 200).isEmpty())
        assertTrue(gestures.tick(1_000).isEmpty())
    }
    private fun packet(phase: TouchPhase, sequence: Long, time: Long = 100, id: String = "gesture") =
        TouchPacket(id, sequence, phase, point, time)
    @Test fun shellGuardRequiresDownAndOrderedMatchingPackets() {
        val guard = TouchStreamGuard(720, 1280)
        assertFailsWith<IllegalArgumentException> { guard.accept(packet(TouchPhase.MOVE, 1), 100) }
        assertEquals(100, guard.accept(packet(TouchPhase.DOWN, 0), 100))
        assertFailsWith<IllegalArgumentException> { guard.accept(packet(TouchPhase.MOVE, 2), 100) }
        assertFailsWith<IllegalArgumentException> { guard.accept(packet(TouchPhase.MOVE, 1, id = "other"), 100) }
        guard.accept(packet(TouchPhase.MOVE, 1), 100)
        guard.accept(packet(TouchPhase.UP, 2), 100)
        assertNull(guard.cancellation(100))
        assertFailsWith<IllegalArgumentException> { guard.accept(packet(TouchPhase.DOWN, 0), 100) }
    }
    @Test fun shellGuardRejectsStaleFutureAndOutsideCoordinates() {
        for (sample in listOf(packet(TouchPhase.DOWN, 0, time = 0), packet(TouchPhase.DOWN, 0, time = 6_001),
            packet(TouchPhase.DOWN, 0).copy(point = TouchPoint(720f, 20f)))) {
            assertFailsWith<IllegalArgumentException> { TouchStreamGuard(720,1280).accept(sample, 6_000) }
        }
    }
    private suspend fun human(broker: SessionBroker): TouchTarget {
        val session = broker.create(2, "com.google.android.gm")
        val state = broker.requestTakeover(session.token.sessionId)
        return TouchTarget(state.token, 2, state.packageName, 1, 720, 1280)
    }
    @Test fun anEntireGestureRetainsOneHumanLeaseUntilUpSettles() = runTest {
        val broker = SessionBroker(); val target = human(broker)
        val seen = mutableListOf<TouchPacket>(); val upAck = CompletableDeferred<Unit>()
        val controller = HumanTouchController(broker, backgroundScope, HumanTouchSink { _, sample ->
            seen += sample; if (sample.phase == TouchPhase.UP) upAck.await(); true
        }, { testScheduler.currentTime })
        assertTrue(controller.begin(target, point)); controller.move(TouchPoint(110f,210f)); controller.end(point)
        runCurrent()
        assertEquals(listOf(TouchPhase.DOWN,TouchPhase.MOVE,TouchPhase.UP), seen.map { it.phase })
        assertEquals(1, broker.state(target.token.sessionId).inFlight)
        assertFailsWith<IllegalStateException> { broker.returnControl(target.token) }
        upAck.complete(Unit); runCurrent()
        assertEquals(0, broker.state(target.token.sessionId).inFlight)
        assertEquals(Owner.AGENT, broker.returnControl(target.token).owner)
    }
    @Test fun cancelInjectsCancelBeforeReleasingLease() = runTest {
        val broker = SessionBroker(); val target = human(broker); val seen = mutableListOf<TouchPhase>()
        val cancelAck = CompletableDeferred<Unit>()
        val controller = HumanTouchController(broker, backgroundScope, HumanTouchSink { _, sample ->
            seen += sample.phase
            if (sample.phase == TouchPhase.CANCEL) cancelAck.await()
            true
        }, { testScheduler.currentTime })
        controller.begin(target,point); controller.cancel(); runCurrent()
        assertEquals(listOf(TouchPhase.DOWN,TouchPhase.CANCEL), seen)
        assertEquals(1,broker.state(target.token.sessionId).inFlight)
        cancelAck.complete(Unit);runCurrent()
        assertEquals(0,broker.state(target.token.sessionId).inFlight)
    }
    @Test fun readOnlyAndStaleEpochNeverInject() = runTest {
        val broker = SessionBroker();val target = human(broker);broker.returnControl(target.token)
        var injections = 0
        val controller = HumanTouchController(broker,backgroundScope,HumanTouchSink { _, _ -> injections++; true },{0})
        controller.begin(target,point);runCurrent()
        assertEquals(0,injections)
    }
    @Test fun revocationPreventsFurtherMovesButCleansUpOldStroke() = runTest {
        val broker = SessionBroker();val target = human(broker);val seen = mutableListOf<TouchPhase>()
        val controller = HumanTouchController(broker,backgroundScope,HumanTouchSink { _, sample -> seen += sample.phase;true },{0})
        controller.begin(target,point);broker.stop(target.token.sessionId);controller.move(point);runCurrent()
        assertEquals(listOf(TouchPhase.DOWN,TouchPhase.CANCEL),seen)
        assertEquals(Owner.STOPPED,broker.state(target.token.sessionId).owner)
    }
    @Test fun queueOverflowCancelsInsteadOfReplayingOldMoves() = runTest {
        val broker=SessionBroker();val target=human(broker);val downAck=CompletableDeferred<Unit>();val seen=mutableListOf<TouchPhase>()
        val controller=HumanTouchController(broker,backgroundScope,HumanTouchSink { _, sample ->
            seen += sample.phase
            if(sample.phase==TouchPhase.DOWN) withContext(NonCancellable){downAck.await()}
            true
        },{0})
        controller.begin(target,point);repeat(40){controller.move(point)};downAck.complete(Unit);runCurrent()
        assertEquals(listOf(TouchPhase.DOWN,TouchPhase.CANCEL),seen)
        assertEquals(0,broker.state(target.token.sessionId).inFlight)
    }
    @Test fun anotherSessionRemainsIndependentDuringHumanStroke() = runTest {
        val broker=SessionBroker();val target=human(broker);val other=broker.create(3,"com.google.android.calendar")
        val controller=HumanTouchController(broker,backgroundScope,HumanTouchSink { _, _ ->true },{0})
        controller.begin(target,point)
        broker.registerObservation(other.token,"fresh")
        assertEquals("ok",broker.agentAction(other.token,"action","fresh"){"ok"})
        controller.cancelAndJoin()
    }
    @Test fun stoppedUiScopeCannotStartAHiddenTouch() = runTest {
        val broker=SessionBroker();val target=human(broker)
        val stopped=CoroutineScope(backgroundScope.coroutineContext+Job().apply { cancel() })
        var injections=0
        val controller=HumanTouchController(broker,stopped,HumanTouchSink { _, _ -> injections++;true },{0})
        assertFalse(controller.begin(target,point));runCurrent()
        assertEquals(0,injections)
    }
    @Test fun stalledFingerTimesOutAndCleansUp() = runTest {
        val broker=SessionBroker();val target=human(broker);val seen=mutableListOf<TouchPhase>();val failures=mutableListOf<String>()
        val controller=HumanTouchController(broker,backgroundScope,HumanTouchSink { _, sample -> seen+=sample.phase;true },
            {testScheduler.currentTime},failures::add)
        controller.begin(target,point);advanceTimeBy(15_001);runCurrent()
        assertEquals(listOf(TouchPhase.DOWN,TouchPhase.CANCEL),seen)
        assertEquals(listOf("HUMAN_TOUCH_TIMEOUT"),failures)
        assertEquals(0,broker.state(target.token.sessionId).inFlight)
    }
}
