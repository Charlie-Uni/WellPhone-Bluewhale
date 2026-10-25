package com.octopus.agent.workflow

import android.app.Application
import com.octopus.agent.bridge.IShellService
import com.octopus.agent.storage.LocalStore
import com.octopus.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.ZoneId

/** Real navigator/GUI port/broker/store; shell, screen trees, model and action receiver are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34,35],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryIntegrationTest {
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class Fixture(private val scope:TestScope) {
        val broker=SessionBroker()
        lateinit var state:SessionState
        lateinit var port:CalendarGuiPort
        lateinit var before:Observation
        @Volatile lateinit var screen:Observation
        var reads=0;var inserts=0;var launches=0;var views=0;var modelCalls=0
        var accepted=true
        var swappedSavedTimes=false
        var wrongSavedIdentity=false
        var wrongSavedDate=false
        val actions=mutableListOf<Pair<SessionToken,Action>>()
        val original=CalendarDraft("key",TargetKind.EVENT,"原标题","me@example.test","Events","Australia/Sydney",
            "2026-10-27T02:00:00Z","2026-10-27T03:00:00Z",null,"2026-10-27T01:45:00Z","[octopus:key]\n来源：招聘邮件")
        var saved=original
        fun scene(draft:CalendarDraft,editing:Boolean)=Observation(state.token,"template",3,7,"com.google.android.calendar",0,listOf(
            UiElement(1,"EditText",draft.title,editable=editing,resourceId="calendar:id/title"),
            UiElement(2,"EditText",draft.notes,editable=editing,resourceId="calendar:id/description"),
            UiElement(3,"TextView",if(!editing && wrongSavedIdentity)"other@example.test"else draft.account,resourceId="calendar:id/account_name"),
            UiElement(4,"TextView",draft.calendarName,resourceId="calendar:id/calendar_name"),
            UiElement(5,"TextView",if(!editing && wrongSavedDate)"2026-10-28"else"2026-10-27",resourceId="calendar:id/start_date"),
            UiElement(6,"TextView",if(!editing && swappedSavedTimes)"14:00"else"13:00",resourceId="calendar:id/start_time"),
            UiElement(7,"TextView",if(!editing && swappedSavedTimes)"13:00"else"14:00",resourceId="calendar:id/end_time"),
            UiElement(8,"TextView","15 minutes before"),UiElement(11,"TextView","2026-10-27",resourceId="calendar:id/end_date"),
            UiElement(30,"TextView",draft.account))+
            if(editing)listOf(UiElement(9,"Button","Save",clickable=true))else emptyList())
        suspend fun initialize() {
            val agent=broker.create(3,"com.google.android.calendar")
            val human=broker.requestTakeover(agent.token.sessionId,true)
            state=human;before=scene(original,true)
            broker.completeTakeover(human.token)
            state=broker.returnControl(human.token)
            screen=scene(original.copy(title="用户修改",notes=original.notes+"\n本地备注"),true)
            saved=original.copy(title="用户修改",notes=original.notes+"\n本地备注")
            val shell=object:IShellService.Default() {
                override fun launch(handle:String,packageName:String):String {launches++;error("Recovery cannot relaunch")}
                override fun calendarInsert(handle:String,sessionId:String,epoch:Long,draftJson:String) {inserts++;error("Recovery cannot create")}
                override fun calendarView(handle:String,sessionId:String,epoch:Long,millis:Long) {
                    views++
                    screen=scene(saved,false).copy(elements=listOf(
                        UiElement(10,"SearchView",saved.marker,editable=true,resourceId="calendar:id/search"),
                        UiElement(20,"TextView",saved.title,clickable=true)))
                }
            }
            val navigator=GuiNavigator(broker,object:ObservationPort {
                override suspend fun observe(session:SessionState):Observation {
                    reads++;return screen.copy(token=session.token,observationId="fresh:$reads",capturedAtMillis=scope.testScheduler.currentTime)
                }
            },object:ActionPort {
                override suspend fun perform(session:SessionState,observation:Observation,action:Action):Boolean {
                    actions+=session.token to action
                    if(!accepted)return false
                    if(action is Action.Click && action.nodeId==9)screen=scene(saved,false)
                    if(action is Action.Click && action.nodeId==20)screen=scene(saved,false)
                    return true
                }
            },object:DecisionModel {
                override suspend fun decide(goal:String,safeObservation:Observation):Decision {
                    modelCalls++
                    return Decision(if(goal.startsWith("Open the matching"))Action.Click(20)else Action.SubmitSearch(10),"synthetic navigation")
                }
            },{scope.testScheduler.currentTime})
            port=CalendarGuiPort(navigator,broker,state.token,"virtual-B",shell)
        }
        fun point()=CalendarCheckpoint("source",original,CalendarPhase.PREPARE,before)
        suspend fun ready()=port.recover(point(),WriteStatus.HUMAN_HELD) as CalendarRecovery.Ready
    }
    private suspend fun TestScope.fixture()=Fixture(this).apply {initialize()}
    private suspend inline fun <reified T:Throwable> fails(noinline action:suspend ()->Unit):T {
        try {action();fail("Expected ${T::class.java.simpleName}")}catch(error:Throwable) {if(error is T)return error;throw error}
        error("unreachable")
    }
    @Test fun editedFormIsObservedWithoutAnyModelOrMutatingAction()=runTest {
        val f=fixture();val ready=f.ready()
        assertEquals(f.saved,ready.draft);assertEquals(f.state.token,ready.prepared.token)
        assertEquals(2L,f.state.token.epoch);assertEquals(1,f.reads)
        assertEquals(0,f.modelCalls);assertEquals(0,f.inserts);assertEquals(0,f.launches);assertTrue(f.actions.isEmpty())
    }
    @Test fun resumedSaveDoesNotRefillFieldsAndReopensBeforeProof()=runTest {
        val f=fixture();val ready=f.ready()
        f.port.save(ready.prepared)
        val proof=f.port.reopenAndVerify(ready.draft)
        assertNotNull(proof);assertTrue(proof!!.reopened);assertEquals(ready.draft.contentDigest(),proof.draftDigest)
        assertEquals(1,f.actions.count {it.second==Action.Click(9)})
        assertTrue(f.actions.none {it.second is Action.SetText});assertTrue(f.actions.all {it.first==f.state.token})
        assertEquals(0,f.inserts);assertEquals(0,f.launches);assertTrue(f.views>0)
    }
    @Test fun changedPageAfterConfirmationRejectsTicketBeforeSave()=runTest {
        val f=fixture();val ready=f.ready()
        f.screen=f.screen.copy(elements=f.screen.elements.map {if(it.id==1)it.copy(text="页面再次改变")else it})
        assertEquals("RESUME_PAGE_CHANGED",fails<GuiNeedsUser>{f.port.save(ready.prepared)}.reasonCode)
        assertTrue(f.actions.isEmpty());assertEquals(0,f.views)
        fails<ActionRejected>{f.port.save(ready.prepared)}
    }
    @Test fun changedTimesAreBlockedEvenWhenBothOldStringsRemain()=runTest {
        val f=fixture()
        f.screen=f.screen.copy(elements=f.screen.elements.map {if(it.id==6)it.copy(text="14:00")else if(it.id==7)it.copy(text="13:00")else it})
        val result=f.port.recover(f.point(),WriteStatus.HUMAN_HELD) as CalendarRecovery.Blocked
        assertEquals("RESUME_UNCLASSIFIED_CHANGE",result.code);assertTrue(f.actions.isEmpty());assertEquals(0,f.views)
    }
    @Test fun missingMarkerKeepsEditorAndDoesNotNavigate()=runTest {
        val f=fixture();f.screen=f.screen.copy(elements=f.screen.elements.map {if(it.id==2)it.copy(text="人工删除标记")else it})
        assertTrue(f.port.recover(f.point(),WriteStatus.HUMAN_HELD) is CalendarRecovery.Blocked)
        assertTrue(f.actions.isEmpty());assertEquals(0,f.views)
    }
    @Test fun unknownSaveReceiptCannotReceiveAnotherSaveTicket()=runTest {
        val f=fixture();val result=f.port.recover(f.point(),WriteStatus.UNKNOWN) as CalendarRecovery.Blocked
        assertEquals("RESUME_SAVE_STATE_UNKNOWN",result.code);assertTrue(f.actions.isEmpty())
    }
    @Test fun manuallySavedChangedDetailIsReopenedWithoutSavingAgain()=runTest {
        val f=fixture();f.screen=f.scene(f.saved,false)
        val result=f.port.recover(f.point(),WriteStatus.UNKNOWN) as CalendarRecovery.Verified
        assertEquals(f.saved,result.draft);assertTrue(result.proof.reopened)
        assertTrue(f.actions.none {it.second==Action.Click(9) || it.second is Action.SetText})
        assertEquals(0,f.inserts);assertEquals(0,f.launches);assertTrue(f.views>0)
    }
    @Test fun swappedTimesInPersistedDetailCannotBeVerifiedByPresenceAlone()=runTest {
        for(phase in listOf(CalendarPhase.PREPARE,CalendarPhase.LOOKUP)) {
            val f=fixture();f.saved=f.original;f.swappedSavedTimes=true;f.screen=f.scene(f.saved,false)
            assertTrue(f.port.recover(f.point().copy(phase=phase),WriteStatus.UNKNOWN) is CalendarRecovery.Blocked)
            assertTrue(f.actions.isEmpty());assertEquals(0,f.views);assertEquals(0,f.inserts)
        }
    }
    @Test fun savedDateAndSelectedAccountMustMatchTheirOwnFields()=runTest {
        for(wrongAccount in listOf(true,false)) {
            val f=fixture();f.saved=f.original;f.wrongSavedIdentity=wrongAccount;f.wrongSavedDate=!wrongAccount
            f.screen=f.scene(f.saved,false)
            assertTrue(f.port.recover(f.point(),WriteStatus.UNKNOWN) is CalendarRecovery.Blocked)
            assertTrue(f.actions.none {it.second==Action.Click(9) || it.second is Action.SetText})
        }
    }
    @Test fun taskReminderProfileCanRetainEditedTitleWithoutRefilling()=runTest {
        val f=fixture();val original=f.original.copy(kind=TargetKind.TASK,start=null,end=null,calendarName="My Tasks")
        fun taskScene(draft:CalendarDraft)=f.scene(draft,true).let {scene -> scene.copy(elements=scene.elements.filterNot {it.id==7 || it.id==11}.map {
            when(it.id) {
                5 -> it.copy(resourceId="calendar:id/task_date")
                6 -> it.copy(text="12:45",resourceId="calendar:id/task_time")
                else -> it
            }
        })}
        val changed=original.copy(title="修改后的截止任务")
        f.screen=taskScene(changed)
        val result=f.port.recover(f.point().copy(draft=original,beforeHuman=taskScene(original)),WriteStatus.HUMAN_HELD) as CalendarRecovery.Ready
        assertEquals(changed,result.draft);assertTrue(f.actions.isEmpty());assertEquals(0,f.inserts)
    }
    @Test fun rejectedSaveNeverLeavesAnUnsavedHumanEditor()=runTest {
        val f=fixture();val ready=f.ready();f.accepted=false
        fails<GuiNeedsUser>{f.port.save(ready.prepared)}
        assertEquals("RESUME_SAVE_UNSETTLED",fails<GuiNeedsUser>{f.port.reopenAndVerify(ready.draft)}.reasonCode)
        assertEquals(0,f.views);assertTrue(ResumeEvidence.contentEditor(f.screen));assertEquals(1,f.actions.size)
    }
    @Test fun oldTicketCannotBeUsedByNewPortAfterHandoff()=runTest {
        val f=fixture();fails<ActionRejected>{f.port.save(PreparedWrite("old-ticket",f.original.contentDigest(),f.state.token.copy(epoch=0)))}
        assertTrue(f.actions.isEmpty());assertEquals(0,f.reads)
    }
    @Test fun retakeoverRevokesFreshResumeTicketAndCannotReplayIt()=runTest {
        val f=fixture();val ready=f.ready();f.broker.requestTakeover(f.state.token.sessionId)
        fails<ActionRejected>{f.port.save(ready.prepared)};fails<ActionRejected>{f.port.save(ready.prepared)}
        assertTrue(f.actions.isEmpty())
    }
    @Test fun sqliteReopenPreservesHumanHoldAndRevisionAndReadsLegacyPayload()=runTest {
        val context=RuntimeEnvironment.getApplication();val f=fixture()
        val source=SourceEmail("me@example.test","hr@example.test","招聘邮件","2026-10-24T01:00:00Z","body")
        val item=CanonicalItem("key",RecruitmentItem(ItemType.INTERVIEW,"原标题",source,"原文","北京时间","Asia/Shanghai",confidence=Confidence.HIGH),userDraft=f.saved)
        LocalStore(context).use { store ->store.insert(source.fingerprint(),listOf(item));WriteLedger(store).prepare("key");WriteLedger(store).holdForHuman("key")}
        LocalStore(context).use { store ->
            assertEquals(item,store.load(source.fingerprint())!!.single());assertEquals(WriteDecision.VERIFY_EXISTING,WriteLedger(store).prepare("key"))
            val legacy=Json.encodeToString(item.copy(userDraft=null))
            assertFalse(legacy.contains("userDraft"));assertNull(Json.decodeFromString<CanonicalItem>(legacy).userDraft)
        }
    }
    @Test fun resumeConfirmationUsesCurrentContentAndRejectsAnswerAfterRetakeover()=runTest {
        val f=fixture();var shown:UserQuestion?=null
        val questions=UserReviews(f.broker){shown=it}
        val reviews=WorkflowReviews(questions,f.state.token,ZoneId.of("Australia/Sydney"))
        val pending=async {runCatching {reviews.confirmResume(f.saved)}};runCurrent()
        val question=requireNotNull(shown);assertTrue(question.detail.contains(f.saved.title));assertFalse(question.detail.contains(f.original.title))
        f.broker.requestTakeover(f.state.token.sessionId);questions.answer(question.id,UserAnswer(0,emptyMap()))
        assertTrue(pending.await().exceptionOrNull() is ActionRejected)
    }
}
