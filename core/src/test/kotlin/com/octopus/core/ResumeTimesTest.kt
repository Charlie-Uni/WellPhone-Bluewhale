package com.octopus.core

import kotlin.test.*

class ResumeTimesTest {
    private val original = CalendarDraft("key", TargetKind.EVENT, "Interview", "me@example.test", "Events", "Australia/Sydney",
        "2026-10-27T02:00:00Z", "2026-10-27T03:00:00Z", null, "2026-10-27T01:45:00Z", "[octopus:key]\nOriginal time: Beijing")
    private fun scene(startDate: String = "2026-10-28", startTime: String = "23:30", endDate: String = "2026-10-29", endTime: String = "01:00"): Observation {
        val fields = linkedMapOf("title" to original.title, "description" to original.notes,
            "account_name" to original.account, "calendar_name" to original.calendarName,
            "start_date" to startDate, "start_time" to startTime, "end_date" to endDate, "end_time" to endTime,
            "notification" to "15 minutes before")
        return Observation(SessionToken("calendar",2),"fresh",3,7,"com.google.android.calendar",0,
            fields.entries.mapIndexed { i,(id,text) -> UiElement(i+1,"TextView",text,
                editable=id in setOf("title","description"),resourceId="calendar:id/$id") } + UiElement(20,"Button","Save",clickable=true))
    }
    @Test fun crossDayChangeUsesSydneyZoneAndKeepsRelativeReminderAndSourceNotes() {
        val new = ResumeEvidence.candidate(scene(),original,true)!!
        assertEquals("2026-10-28T12:30:00Z",new.start);assertEquals("2026-10-28T14:00:00Z",new.end)
        assertEquals("2026-10-28T12:15:00Z",new.remindAt);assertEquals(original.notes,new.notes)
        assertTrue(ResumeEvidence.bound(original,new));assertTrue(CalendarEvidence.recoveryTimes(scene(),new))
        assertFalse(CalendarEvidence.recoveryTimes(scene(),original))
    }
    @Test fun chineseAndEnglishCompleteDatesAndMeridiemAreSupported() {
        assertNotNull(ResumeTimes.read(scene("2026年10月28日","下午 11:30","Oct 29, 2026","1:00 AM"),original))
        assertNotNull(ResumeTimes.read(scene("2026/10/28","11:30 PM","29 Oct 2026","上午 1:00"),original))
    }
    @Test fun incompleteInvalidOrAmbiguousTextIsNeverGuessed() {
        for (date in listOf("10月28日", "2026-02-30", "10/28/2026", "2026-10-28 or 2026-10-29"))
            assertNull(ResumeTimes.read(scene(startDate=date),original))
        for (time in listOf("25:00", "13:00 PM", "下午 1:00 AM", "13:00 14:00"))
            assertNull(ResumeTimes.read(scene(startTime=time),original))
    }
    @Test fun dstGapAndOverlapRequireManualResolution() {
        assertNull(ResumeTimes.read(scene("2026-10-04","02:30","2026-10-04","04:00"),original))
        assertNull(ResumeTimes.read(scene("2027-04-04","02:30","2027-04-04","04:00"),original))
    }
    @Test fun endBeforeStartAndDuplicateRolesAreRejectedDespiteOldStringsElsewhere() {
        assertNull(ResumeTimes.read(scene("2026-10-27","14:00","2026-10-27","13:00"),original))
        val scene=scene()
        assertNull(ResumeTimes.read(scene.copy(elements=scene.elements+scene.elements.first {it.resourceId.endsWith("start_time")}.copy(id=50)),original))
        assertNull(ResumeTimes.read(scene.copy(elements=scene.elements.map {if(it.resourceId.endsWith("start_date"))it.copy(description="2026-10-30")else it}),original))
    }
    @Test fun accountZoneAndOtherFieldChangesCannotBeLaunderedAsTimeEdits() {
        val before=scene();val after=scene(startTime="23:45")
        assertTrue(ResumeEvidence.sameOtherFields(before,after))
        assertFalse(ResumeEvidence.sameOtherFields(before,after.copy(elements=after.elements.map {if(it.resourceId.endsWith("notification"))it.copy(resourceId="calendar:id/other")else it})))
        assertNull(ResumeTimes.read(after.copy(elements=after.elements.map {if(it.resourceId.endsWith("account_name"))it.copy(text="other@example.test")else it}),original))
        assertNull(ResumeTimes.read(after.copy(elements=after.elements+UiElement(51,"TextView","Asia/Shanghai",resourceId="calendar:id/timezone")),original))
        assertFalse(ResumeEvidence.bound(original,original.copy(remindAt=original.start!!)))
    }
    @Test fun taskNotificationCanMoveButOriginalDeadlineIsRetained() {
        val task=original.copy(kind=TargetKind.TASK,start=null,end=null,deadline="2026-10-29T10:00:00Z",notes=original.notes+"\n\u622a\u6b62\uff1a2026-10-29T21:00+11:00")
        fun taskScene(time:String)=scene("2026-10-29",time).let { it.copy(elements=it.elements.filterNot {
            node -> node.resourceId.endsWith("end_time") || node.resourceId.endsWith("end_date")
        }.map {node -> node.copy(text=if(node.resourceId.endsWith("description"))task.notes else node.text,resourceId=node.resourceId.replace("start_date","task_date").replace("start_time","task_time"))}) }
        val changed=ResumeTimes.read(taskScene("20:00"),task)!!
        assertEquals("2026-10-29T09:00:00Z",changed.remindAt);assertEquals(task.deadline,changed.deadline)
        assertNull(ResumeTimes.read(taskScene("22:00"),task))
        assertFalse(ResumeEvidence.bound(task,changed.copy(deadline=changed.remindAt)))
    }
    @Test fun oldStringsStillVisibleCannotHideAValidNewCrossDayInterval() {
        val old=original.copy(start="2026-10-28T12:30:00Z",end="2026-10-28T14:00:00Z",remindAt="2026-10-28T12:15:00Z")
        val screen=scene("2026-10-28","01:00","2026-10-29","23:30")
        assertTrue(CalendarEvidence.matches(screen,old,true)) // A bag of the old dates/times still matches.
        val changed=ResumeEvidence.candidate(screen,old,true)!!
        assertEquals("2026-10-27T14:00:00Z",changed.start);assertEquals("2026-10-29T12:30:00Z",changed.end)
    }
    @Test fun unchangedTitleAndTimeStillAdoptExactNewNotesRatherThanSubsetOfOldNotes() {
        val screen=scene("2026-10-27","13:00","2026-10-27","14:00").let { it.copy(elements=it.elements.map {node ->
            if(node.resourceId.endsWith("description"))node.copy(text=original.notes+"\nUser note")else node
        }) }
        assertTrue(CalendarEvidence.matches(screen,original,true))
        assertEquals(original.notes+"\nUser note",ResumeEvidence.candidate(screen,original,true)!!.notes)
        assertNull(ResumeEvidence.candidate(screen.copy(elements=screen.elements.filterNot {it.resourceId.endsWith("description")}),original,true))
    }
}
