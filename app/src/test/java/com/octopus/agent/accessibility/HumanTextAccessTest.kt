package com.octopus.agent.accessibility

import android.app.Application
import android.graphics.Rect
import android.text.InputType
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.octopus.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real adapter and Android nodes/actions; window/tree and action delivery are simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34,35],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
class HumanTextAccessTest {
    private lateinit var context:Application
    private lateinit var broker:SessionBroker
    private lateinit var session:SessionState
    private lateinit var root:AccessibilityNodeInfo
    private lateinit var field:AccessibilityNodeInfo
    private lateinit var window:AccessibilityWindowInfo
    private lateinit var access:HumanTextAccess
    private var windows=emptyList<AccessibilityWindowInfo>()
    private var now=1_000L
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication();broker=SessionBroker()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        root=node("root").apply { isEditable=false }
        field=node("title");shadowOf(root).addChild(field)
        window=window(root,10,2,1);windows=listOf(window)
        access=HumanTextAccess(broker,{display -> if(display==2) windows else emptyList()},{now})
    }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun node(id:String)=AccessibilityNodeInfo.obtain(View(context)).apply {
        packageName="mail";className="android.widget.EditText";viewIdResourceName="mail:id/$id"
        text="旧标题";hintText=id;isEditable=true;isVisibleToUser=true;isEnabled=true
        setBoundsInScreen(Rect(10,20,600,100));addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
        shadowOf(this).setRefreshReturnValue(true)
    }
    private fun window(root:AccessibilityNodeInfo,id:Int,display:Int,layer:Int)=AccessibilityWindowInfo.obtain().apply {
        shadowOf(this).apply {setRoot(root);setId(id);setDisplayId(display);setLayer(layer);setType(AccessibilityWindowInfo.TYPE_APPLICATION)}
    }
    private suspend fun capture():List<HumanTextField> {
        val agent=broker.create(2,"mail");session=broker.requestTakeover(agent.token.sessionId)
        return broker.humanAction(session.token,"read"){access.capture(it,"draft")}
    }
    private suspend fun write(value:String,valid:()->Boolean={true})=broker.humanAction(session.token,"write:$now") {
        access.replace(it,"draft","1",value,valid)
    }
    private fun acceptAndUpdate() { shadowOf(field).setOnPerformActionListener { action,args ->
        if(action==AccessibilityNodeInfo.ACTION_SET_TEXT) {
            field.text=args.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE);true
        } else false
    } }
    private suspend fun rejected(block:suspend ()->Unit) {
        try { block();fail("Expected binding rejection") } catch (_:BindingLost) { } catch (_:ActionRejected) { }
        assertTrue(shadowOf(field).performedActions.isEmpty())
    }
    @Test fun capturesOnlySupportedVisibleNonSecretFields()=runTest {
        val password=node("secret").apply {isPassword=true}
        shadowOf(password).addChild(node("nested-secret"))
        listOf(password,node("numeric-pin").apply {inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD},
            node("disabled").apply {isEnabled=false},node("hidden").apply {isVisibleToUser=false},
            node("no-action").apply {removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)})
            .forEach {shadowOf(root).addChild(it)}
        field.isFocused=true
        val fields=capture()
        assertEquals(1,fields.size);assertEquals("旧标题",fields.single().value);assertTrue(fields.single().focused)
    }
    @Test fun unicodeReplacementUsesOnlySetTextAndReadsBack()=runTest {
        capture();acceptAndUpdate()
        val value="中文面试 🐙\n悉尼 18:30"
        assertEquals(TextWriteResult.VERIFIED,write(value))
        assertEquals(value,field.text.toString())
        val actions=shadowOf(field).performedActionsWithArgs
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_SET_TEXT),actions.map {it.first})
        assertEquals(value,actions.single().second.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE).toString())
    }
    @Test fun emptyStringClearsTheField()=runTest {
        capture();acceptAndUpdate();assertEquals(TextWriteResult.VERIFIED,write(""));assertEquals("",field.text.toString())
    }
    @Test fun mainDisplayWindowCannotBecomeFallback()=runTest {
        windows=listOf(window(root,10,0,1));rejected {capture()}
    }
    @Test fun systemDialogAboveTargetBlocksTextDispatch()=runTest {
        capture()
        windows=windows+window(node("dialog").apply {packageName="android"},11,2,2)
        rejected {write("new")}
    }
    @Test fun userOrAppChangingOriginalValueRevokesDraft()=runTest {
        capture();field.text="用户已修改";rejected {write("new")}
    }
    @Test fun replacementNodeAtSamePathAndResourceIdIsRejected()=runTest {
        capture()
        val newRoot=node("root").apply {isEditable=false};shadowOf(newRoot).addChild(node("title"))
        shadowOf(window).setRoot(newRoot)
        rejected {write("new")}
    }
    @Test fun passwordChangeBeforeApplyIsRejected()=runTest {
        capture();field.isPassword=true;rejected {write("new")}
    }
    @Test fun layoutChangeBeforeApplyIsRejected()=runTest {
        capture();field.setBoundsInScreen(Rect(10,200,600,300));rejected {write("new")}
    }
    @Test fun oldEpochCannotDispatchAfterHandback()=runTest {
        capture();broker.returnControl(session.token);rejected {write("new")}
    }
    @Test fun expiredDraftCannotDispatch()=runTest {
        capture();now+=120_001;rejected {write("new")}
    }
    @Test fun acknowledgementWithoutValueChangeIsUnknownAndSingleUse()=runTest {
        capture();shadowOf(field).setOnPerformActionListener {_,_->true}
        assertEquals(TextWriteResult.UNKNOWN,write("new"))
        now++
        try {write("new");fail("Consumed snapshot reused")} catch (_:ActionRejected) {}
        assertEquals(1,shadowOf(field).performedActions.size)
    }
    @Test fun finalPreviewFenceStopsDispatch()=runTest {
        capture();rejected {write("new"){false}}
    }
    @Test fun rejectedActionIsUnknownWithoutRetry()=runTest {
        capture();shadowOf(field).setOnPerformActionListener {_,_->false}
        assertEquals(TextWriteResult.UNKNOWN,write("new"))
        assertEquals(1,shadowOf(field).performedActions.size)
    }
    @Test fun cancelledCallerWaitsForSubmittedActionToSettleBeforeReleasingLease()=runTest {
        capture();shadowOf(field).setOnPerformActionListener {_,_->true}
        val pending=launch {write("new")};runCurrent()
        pending.cancel();runCurrent()
        assertEquals(1,broker.state(session.token.sessionId).inFlight)
        advanceUntilIdle();pending.join()
        assertEquals(0,broker.state(session.token.sessionId).inFlight)
        assertEquals(1,shadowOf(field).performedActions.size)
    }
}
