package com.octopus.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryTest {
    private class Store : WriteStore, CanonicalStore {
        val writes = mutableMapOf<String,WriteRecord>()
        val items = mutableMapOf<String,List<CanonicalItem>>()
        override suspend fun insertIfAbsent(record:WriteRecord)=writes.getOrPut(record.key){record}
        override suspend fun read(key:String)=writes[key]
        override suspend fun compareAndSet(expected:WriteRecord,update:WriteRecord):Boolean {
            if(writes[expected.key]!=expected) return false
            writes[expected.key]=update;return true
        }
        override suspend fun load(sourceFingerprint:String)=items[sourceFingerprint]
        override suspend fun insert(sourceFingerprint:String,items:List<CanonicalItem>)=this.items.getOrPut(sourceFingerprint){items}
        override suspend fun update(sourceFingerprint:String,item:CanonicalItem) {
            items[sourceFingerprint]=items.getValue(sourceFingerprint).map {if(it.id==item.id)item else it}
        }
    }
    private class Fixture(private val pauseAt:Int=0) {
        val config=WorkflowConfig("整理招聘邮件","me@example.test","Recruitment","me@example.test","Events",scopeAuthorized=true)
        val store=Store();val ledger=WriteLedger(store);val checkpoint=WorkflowCheckpoint()
        val docs=(1..2).map { index ->
            val body="补充材料截止：2026年10月28日北京时间18:00"
            MailDocument(SourceEmail(config.mailAccount,"hr@example.test","招聘材料 $index","2026-10-24T01:00:00Z",digest(body)),body)
        }
        init { docs.forEachIndexed { i,doc -> store.items[doc.source.fingerprint()]=listOf(CanonicalItem("item$i",
            RecruitmentItem(ItemType.MATERIALS_DEADLINE,doc.source.subject,doc.source,doc.body,"北京时间","Asia/Shanghai",
                deadline="2026-10-28T10:00:00Z",remindAt="2026-10-28T09:00:00Z",confidence=Confidence.HIGH,
                evidence=listOf(doc.body)),humanConfirmed=true)) } }
        var reads=0;var prepares=0;var saves=0;var confirms=0
        var approve=true;var blockPrepare=true;var loseAck=false;var fakeProof=false
        var recoveryOverride:CalendarRecovery?=null
        val entered=CompletableDeferred<Unit>()
        var editor:CalendarDraft?=null
        val records=mutableMapOf<String,CalendarDraft>()
        var token=SessionToken("calendar",0)
        val calendar=object:CalendarPort {
            override suspend fun inspect(config:WorkflowConfig,item:RecruitmentItem)=CalendarInspection(emptyList(),true)
            override suspend fun find(draft:CalendarDraft):FindRecord = records[draft.key]?.let {
                if(it==draft) CalendarRecovery.Verified(it,proof(it)).let { found -> FindRecord.Found(found.proof) } else FindRecord.Unknown
            } ?: FindRecord.Absent
            override suspend fun prepare(draft:CalendarDraft):PreparedWrite {
                prepares++;editor=draft
                if(blockPrepare && draft.key=="item$pauseAt") { entered.complete(Unit);awaitCancellation() }
                return PreparedWrite("new:$prepares",draft.contentDigest(),token)
            }
            override suspend fun save(prepared:PreparedWrite) {
                assertEquals(token,prepared.token)
                val current=requireNotNull(editor)
                assertEquals(current.contentDigest(),prepared.draftDigest)
                assertEquals(WriteStatus.WRITE_PENDING,ledger.status(current.key))
                records[current.key]=current;editor=null;saves++
                if(loseAck) error("Synthetic lost save receipt")
            }
            override suspend fun reopenAndVerify(draft:CalendarDraft)=records[draft.key]?.takeIf {it==draft}?.let(::proof)
            override suspend fun recover(point:CalendarCheckpoint,status:WriteStatus):CalendarRecovery {
                recoveryOverride?.let {return it}
                editor?.let {return CalendarRecovery.Ready(it,PreparedWrite("resume",it.contentDigest(),token))}
                return records[point.draft.key]?.let {CalendarRecovery.Verified(it,proof(it))}
                    ?: CalendarRecovery.Blocked("RESUME_RESULT_UNKNOWN")
            }
        }
        fun proof(draft:CalendarDraft)=VerifiedRecord("synthetic:${draft.key}",draft.contentDigest(),!fakeProof)
        val review=object:ReviewPort {
            override suspend fun reviewItem(item:RecruitmentItem,reasons:List<String>)=item
            override suspend fun resolveConflict(item:RecruitmentItem,inspection:CalendarInspection)=ConflictChoice.KEEP_BOTH
            override suspend fun confirmResume(draft:CalendarDraft):Boolean { confirms++;return approve }
        }
        fun workflow(memory:WorkflowCheckpoint=checkpoint)=MailCalendarWorkflow(object:MailPort {
            override suspend fun read(config:WorkflowConfig):List<MailDocument>{reads++;return docs}
        },ItemExtractor {_,_,_->error("Canonical items must be reused")},calendar,review,store,ledger,checkpoint=memory)
        suspend fun pause(scope:CoroutineScope) {
            val job=scope.launch {workflow().run(config)}
            entered.await();job.cancelAndJoin();checkpoint.pause(ledger)
            token=token.copy(epoch=2);blockPrepare=false
        }
    }
    @Test fun handbackUsesEditedDraftAndContinuesRemainingItemsWithoutReadingMailAgain()=runTest {
        val f=Fixture();f.pause(this)
        assertEquals(WriteStatus.HUMAN_HELD,f.ledger.status("item0"))
        f.editor=f.editor!!.copy(title="用户修改的标题",notes=f.editor!!.notes+"\n用户增加的备注")
        val changed=f.editor!!
        val report=f.workflow().run(f.config)
        assertEquals(2,report.verified);assertEquals(changed,f.records["item0"])
        assertEquals(2,f.saves);assertEquals(2,f.prepares);assertEquals(1,f.reads);assertEquals(1,f.confirms)
        assertEquals(changed,f.store.items.values.flatten().single {it.id=="item0"}.userDraft)
        assertFalse(f.checkpoint.needsResume)
    }
    @Test fun revisionSurvivesCanonicalSerializationAndNewRunDoesNotDuplicate()=runTest {
        val f=Fixture();f.pause(this);f.editor=f.editor!!.copy(title="修改后标题")
        f.workflow().run(f.config)
        f.store.items.replaceAll {_,items->Json.decodeFromString<List<CanonicalItem>>(Json.encodeToString(items))}
        assertEquals(2,f.workflow(WorkflowCheckpoint()).run(f.config).verified)
        assertEquals(2,f.saves);assertEquals(2,f.prepares)
    }
    @Test fun completedItemsKeepTheirResultsWhileALaterItemIsResumed()=runTest {
        val f=Fixture(pauseAt=1);f.pause(this)
        assertEquals(setOf("item0"),f.checkpoint.completed);assertEquals(1,f.saves)
        f.editor=f.editor!!.copy(title="只修改第二项")
        val report=f.workflow().run(f.config)
        assertEquals(2,report.verified);assertEquals(2,f.saves);assertEquals(2,f.prepares);assertEquals(1,f.reads)
        assertEquals("只修改第二项",f.records["item1"]!!.title)
    }
    @Test fun manuallySavedRevisionIsReopenedWithoutAnotherSave()=runTest {
        val f=Fixture();f.pause(this)
        val changed=f.editor!!.copy(title="人工已保存")
        f.records[changed.key]=changed;f.editor=null
        assertEquals(2,f.workflow().run(f.config).verified)
        assertEquals(changed,f.records["item0"]);assertEquals(1,f.saves);assertEquals(0,f.confirms)
    }
    @Test fun decliningResumeKeepsPageAndDoesNotStartNextItem()=runTest {
        val f=Fixture();f.pause(this);f.approve=false
        assertEquals("RESUME_CONFIRMATION_REQUIRED",assertFailsWith<GuiNeedsUser>{f.workflow().run(f.config)}.reasonCode)
        assertEquals(0,f.saves);assertEquals(1,f.prepares);assertNotNull(f.editor)
        assertTrue(f.checkpoint.needsResume);assertEquals(WriteStatus.HUMAN_HELD,f.ledger.status("item0"))
    }
    @Test fun unrecognizedPagePreservesCheckpointAndDoesNotNavigateToNextItem()=runTest {
        val f=Fixture();f.pause(this);f.recoveryOverride=CalendarRecovery.Blocked("RESUME_EDITOR_NEEDS_USER")
        assertFailsWith<GuiNeedsUser>{f.workflow().run(f.config)}
        assertEquals(1,f.reads);assertEquals(1,f.prepares);assertEquals(0,f.saves)
        assertTrue(f.checkpoint.needsResume)
    }
    @Test fun uncertainResumedSaveIsOnlyQueriedOnNextHandback()=runTest {
        val f=Fixture();f.pause(this);f.editor=f.editor!!.copy(title="保留修改");f.loseAck=true
        assertEquals("RESUME_SAVE_UNCERTAIN",assertFailsWith<GuiNeedsUser>{f.workflow().run(f.config)}.reasonCode)
        assertEquals(1,f.saves);assertEquals(WriteStatus.UNKNOWN,f.ledger.status("item0"))
        f.loseAck=false
        assertEquals(2,f.workflow().run(f.config).verified)
        assertEquals(2,f.saves);assertEquals(1,f.confirms)
    }
    @Test fun unknownWriteCannotBeReauthorizedAsAPreparedForm()=runTest {
        val f=Fixture();f.pause(this)
        f.ledger.authorizeResume("item0");f.ledger.beforeSave("item0");f.ledger.unknown("item0")
        assertFailsWith<IllegalStateException>{f.workflow().run(f.config)}
        assertEquals(0,f.saves);assertEquals(0,f.confirms)
    }
    @Test fun changedTimeOrRemovedMarkerCannotBeAdoptedByTheAdapter()=runTest {
        for(change in listOf<(CalendarDraft)->CalendarDraft>({it.copy(remindAt="2026-10-28T08:00:00Z")},{it.copy(notes="标记被删除")})) {
            val f=Fixture();f.pause(this);f.editor=change(f.editor!!)
            assertFailsWith<IllegalStateException>{f.workflow().run(f.config)}
            assertEquals(0,f.saves);assertNull(f.store.items.values.flatten().first {it.id=="item0"}.userDraft)
        }
    }
    @Test fun unreopenedProofCannotMarkHumanRevisionVerified()=runTest {
        val f=Fixture();f.pause(this);f.records["item0"]=f.editor!!;f.editor=null;f.fakeProof=true
        assertFailsWith<IllegalStateException>{f.workflow().run(f.config)}
        assertEquals(WriteStatus.HUMAN_HELD,f.ledger.status("item0"));assertEquals(0,f.saves)
    }
    @Test fun restartingWithoutInMemoryCursorCannotCreateOverAHumanHeldItem()=runTest {
        val f=Fixture();f.pause(this);f.editor=null
        val report=f.workflow(WorkflowCheckpoint()).run(f.config)
        assertEquals(ItemResult.UNKNOWN,report.outcomes.first {it.key=="item0"}.result)
        assertEquals(1,f.saves);assertFalse(f.records.containsKey("item0"))
    }
    @Test fun revisedDraftCannotBeReusedForAnotherAccount()=runTest {
        val f=Fixture();f.pause(this);f.workflow().run(f.config)
        assertFailsWith<IllegalArgumentException>{f.workflow(WorkflowCheckpoint()).run(f.config.copy(calendarAccount="other@example.test"))}
        assertEquals(2,f.saves)
    }
    @Test fun humanAdmissionWaitsForWorkflowCheckpointEvenWithoutInFlightInput()=runTest {
        val broker=SessionBroker();val agent=broker.create(2,"mail")
        val held=broker.requestTakeover(agent.token.sessionId,holdForWorkflow=true)
        assertEquals(Owner.DRAINING,held.owner)
        assertFailsWith<ActionRejected>{broker.humanAction(held.token,"early") {}}
        assertEquals(Owner.HUMAN,broker.completeTakeover(held.token).owner)
        broker.humanAction(held.token,"human"){}
        val returned=broker.returnControl(held.token)
        assertFailsWith<ActionRejected>{broker.completeTakeover(held.token)}
        assertFailsWith<ActionRejected>{broker.agentAction(returned.token,"old","old-observation") {}}
    }
    @Test fun workflowAndInFlightBarriersMustBothSettle()=runTest {
        val broker=SessionBroker();val agent=broker.create(2,"mail");val gate=CompletableDeferred<Unit>()
        broker.registerObservation(agent.token,"obs")
        val action=launch {broker.agentAction(agent.token,"action","obs"){gate.await()}};runCurrent()
        val held=broker.requestTakeover(agent.token.sessionId,true)
        assertEquals(Owner.DRAINING,broker.completeTakeover(held.token).owner)
        gate.complete(Unit);action.join();assertEquals(Owner.HUMAN,broker.state(agent.token.sessionId).owner)
    }
    @Test fun unsavedUnrelatedEditorBlocksResumeButCachedMailNeverTouchesItsDraft() {
        fun scene(pkg:String)=Observation(SessionToken(pkg,2),"fresh",if(pkg=="mail")2 else 3,7,pkg,0,
            listOf(UiElement(1,"EditText","人工草稿",editable=true,resourceId="$pkg:id/body")))
        assertFailsWith<GuiNeedsUser>{ResumeEvidence.checkScenes(scene("mail"),scene("calendar"),null)}
        val calendar=scene("calendar").copy(elements=emptyList())
        assertFailsWith<GuiNeedsUser>{ResumeEvidence.checkScenes(scene("mail"),calendar,null,mailNeeded=true)}
        ResumeEvidence.checkScenes(scene("mail"),calendar,null,mailNeeded=false)
    }
    @Test fun unclassifiedTimeSwapCannotPassJustBecauseOldTimeStringsStillExist() {
        val token=SessionToken("calendar",1)
        val before=Observation(token,"before",3,7,"com.google.android.calendar",0,listOf(
            UiElement(1,"EditText","原标题",editable=true,resourceId="calendar:id/title"),
            UiElement(2,"TextView","10:00",resourceId="calendar:id/start_time"),
            UiElement(3,"TextView","11:00",resourceId="calendar:id/end_time")))
        val renamed=before.copy(token=token.copy(epoch=2),observationId="after",elements=before.elements.map {if(it.id==1)it.copy(text="新标题")else it})
        assertTrue(ResumeEvidence.sameOtherFields(before,renamed))
        val swapped=renamed.copy(elements=renamed.elements.map {if(it.id==2)it.copy(text="11:00")else if(it.id==3)it.copy(text="10:00")else it})
        assertFalse(ResumeEvidence.sameOtherFields(before,swapped))
        assertFalse(ResumeEvidence.sameOtherFields(before,renamed.copy(windowId=99)))
    }
}
