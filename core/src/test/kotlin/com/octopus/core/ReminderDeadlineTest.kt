package com.octopus.core

import java.time.Instant
import kotlin.test.*

class ReminderDeadlineTest {
    private val event = CalendarDraft("key",TargetKind.EVENT,"面试","me@example.test","Events","Australia/Sydney",
        "2026-10-27T02:00:00Z","2026-10-27T03:00:00Z",null,"2026-10-27T01:45:00Z","[octopus:key]\n原文：北京时间11:00")
    private val task = event.copy(kind=TargetKind.TASK,start=null,end=null,calendarName="My Tasks",
        remindAt="2026-10-28T09:00:00Z",deadline="2026-10-28T10:00:00Z",
        notes=event.notes+"\n截止：2026-10-28T21:00+11:00")
    private fun screen(draft:CalendarDraft,changes:Map<String,String> = emptyMap(),omit:Set<String> = emptySet(),editing:Boolean=true):Observation {
        val fields=linkedMapOf("title" to draft.title,"description" to draft.notes,"account_name" to draft.account,"calendar_name" to draft.calendarName)
        if(draft.kind==TargetKind.EVENT) fields.putAll(mapOf("start_date" to "2026-10-27","start_time" to "13:00",
            "end_date" to "2026-10-27","end_time" to "14:00","notification" to "15 minutes before"))
        else fields.putAll(mapOf("notification_date" to "2026-10-28","notification_time" to "20:00",
            "deadline_date" to "2026-10-28","deadline_time" to "21:00"))
        fields.putAll(changes)
        return Observation(SessionToken("calendar",2),"fresh",3,7,"com.google.android.calendar",0,
            fields.filterKeys {it !in omit}.entries.mapIndexed {i,(id,text)->UiElement(i+1,"TextView",text,
                editable=editing && id in setOf("title","description"),resourceId="calendar:id/$id")}+
                if(editing)listOf(UiElement(50,"Button","Save",clickable=true))else emptyList())
    }
    @Test fun changedEventReminderUsesExactNewMinuteHourAndDaySetting() {
        for((text,minutes) in listOf("30 minutes before" to 30L,"1 hour before" to 60L,"1 day before" to 1440L,"提前 2 小时" to 120L,"30分钟前" to 30L)) {
            val scene=screen(event,mapOf("notification" to text))
            val candidate=ResumeEvidence.candidate(scene,event,true)!!
            assertEquals(Instant.parse(event.start).minusSeconds(minutes*60).toString(),candidate.remindAt)
            assertTrue(CalendarEvidence.matches(scene,candidate,true));assertTrue(ResumeEvidence.bound(event,candidate))
        }
    }
    @Test fun unknownDisabledDuplicateOrZeroReminderDoesNotReuseOldValue() {
        for(text in listOf("None","At time of event","0 minutes before","-5 minutes before","30 minutes after","30"))
            assertNull(ResumeTimes.read(screen(event,mapOf("notification" to text)),event))
        val scene=screen(event)
        assertNull(ResumeTimes.read(scene.copy(elements=scene.elements+UiElement(60,"TextView","30 minutes before",resourceId="calendar:id/event_reminder")),event))
        assertNull(ResumeTimes.read(screen(event,omit=setOf("notification")),event))
    }
    @Test fun oldReminderElsewhereCannotVerifyWrongCurrentSetting() {
        val scene=screen(event,mapOf("notification" to "30 minutes before"))
            .let {it.copy(elements=it.elements+UiElement(60,"TextView","15 minutes before"))}
        assertFalse(CalendarEvidence.matches(scene,event,true))
        assertEquals("2026-10-27T01:30:00Z",ResumeEvidence.candidate(scene,event,true)!!.remindAt)
    }
    @Test fun independentTaskNotificationAndDeadlineAreBothAdopted() {
        val scene=screen(task,mapOf("notification_date" to "2026-10-30","notification_time" to "18:00",
            "deadline_date" to "2026-10-31","deadline_time" to "21:00"))
        val candidate=ResumeEvidence.candidate(scene,task,true)!!
        assertEquals("2026-10-30T07:00:00Z",candidate.remindAt);assertEquals("2026-10-31T10:00:00Z",candidate.deadline)
        assertEquals(task.notes,candidate.notes);assertTrue(ResumeEvidence.sameOtherFields(screen(task),scene,TargetKind.TASK))
        assertTrue(CalendarEvidence.recoveryTimes(scene,candidate));assertFalse(CalendarEvidence.recoveryTimes(scene,task))
    }
    @Test fun changingOnlyDeadlineDateRetainsExplicitTimeOfDay() {
        val candidate=ResumeEvidence.candidate(screen(task,mapOf("deadline_date" to "2026-10-30")),task,true)!!
        assertEquals(task.remindAt,candidate.remindAt);assertEquals("2026-10-30T10:00:00Z",candidate.deadline)
    }
    @Test fun managedNoteDeadlineCanChangeWithoutRewritingAnyUserText() {
        val notes=task.notes.replace("2026-10-28T21:00+11:00","2026-10-30T22:00+11:00")+"\n用户备注"
        val scene=screen(task,mapOf("description" to notes),omit=setOf("deadline_date","deadline_time"))
        val candidate=ResumeEvidence.candidate(scene,task,true)!!
        assertEquals("2026-10-30T11:00:00Z",candidate.deadline);assertEquals(notes,candidate.notes)
        assertTrue(CalendarEvidence.matches(scene,candidate,true));assertFalse(CalendarEvidence.matches(scene,task,true))
    }
    @Test fun reminderEqualToOrAfterNewDeadlineBlocksWithSpecificReason() {
        for(time in listOf("20:00","19:00")) {
            val scene=screen(task,mapOf("deadline_time" to time))
            assertNull(ResumeEvidence.candidate(scene,task,true));assertEquals("RESUME_REMINDER_NOT_BEFORE_DEADLINE",ResumeTimes.problem(scene,task))
        }
        assertFalse(ResumeEvidence.bound(task,task.copy(deadline=null)))
        assertFalse(ResumeEvidence.bound(task,task.copy(deadline=task.remindAt)))
    }
    @Test fun incompleteOrDuplicatedNativeDeadlineCannotBorrowTheNoteValue() {
        assertNull(ResumeTimes.read(screen(task,omit=setOf("deadline_time")),task))
        val scene=screen(task)
        assertNull(ResumeTimes.read(scene.copy(elements=scene.elements+UiElement(60,"TextView","2026-10-29",resourceId="calendar:id/due_date")),task))
    }
    @Test fun dueFieldsAreNeverReinterpretedAsNotificationFields() {
        val scene=screen(task,mapOf("due_date" to "2026-10-28","due_time" to "21:00"),
            omit=setOf("notification_date","notification_time","deadline_date","deadline_time"))
        assertNull(ResumeTimes.read(scene,task))
        assertFalse(CalendarEvidence.matches(scene,task,true))
    }
    @Test fun ambiguousMissingOrDuplicateDeadlineNotesRemainOnScreen() {
        for(notes in listOf(event.notes,task.notes+"\n截止：2026-10-30T21:00+11:00",event.notes+"\n截止：10月30日",
            event.notes+"\n截止：2026-10-30T21:00+10:00",event.notes+"\n截止：2027-04-04T02:30")) {
            assertNull(ResumeTimes.read(screen(task,mapOf("description" to notes),omit=setOf("deadline_date","deadline_time")),task))
        }
    }
    @Test fun deadlineNativeRoleIsAuthoritativeAndReopenMustKeepIt() {
        val candidate=ResumeEvidence.candidate(screen(task,mapOf("deadline_date" to "2026-10-30")),task,true)!!
        assertTrue(CalendarEvidence.matches(screen(candidate,mapOf("deadline_date" to "2026-10-30"),editing=false),candidate,false))
        // The expected new deadline in notes cannot hide a stale native deadline field.
        val withNewNote=candidate.copy(notes=candidate.notes.replace("2026-10-28T21:00+11:00","2026-10-30T21:00+11:00"))
        assertFalse(CalendarEvidence.matches(screen(withNewNote,editing=false),withNewNote,false))
    }
    @Test fun temporalWhitelistIsScopedByTargetKind() {
        val before=screen(task);val after=screen(task,mapOf("deadline_time" to "22:00"))
        assertTrue(ResumeEvidence.sameOtherFields(before,after,TargetKind.TASK))
        assertFalse(ResumeEvidence.sameOtherFields(before,after,TargetKind.EVENT))
        val eventBefore=screen(event);val eventAfter=screen(event,mapOf("notification" to "30 minutes before"))
        assertTrue(ResumeEvidence.sameOtherFields(eventBefore,eventAfter,TargetKind.EVENT))
        assertFalse(ResumeEvidence.sameOtherFields(eventBefore,eventAfter,TargetKind.TASK))
    }
}
