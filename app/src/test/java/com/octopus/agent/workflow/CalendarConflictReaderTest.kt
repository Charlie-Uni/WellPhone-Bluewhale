package com.octopus.agent.workflow

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import android.content.pm.ProviderInfo
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver
import com.octopus.core.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34,35],application=Application::class)
class CalendarConflictReaderTest {
    private val draft=CalendarDraft("key",TargetKind.EVENT,"Interview","me@example.test","Events","Australia/Sydney",
        "2026-10-28T12:30:00Z","2026-10-28T14:00:00Z",null,"2026-10-28T12:15:00Z","[octopus:key]")
    private class Provider:ContentProvider() {
        var calendars=listOf(arrayOf<Any>(1L,"me@example.test","Events",1,Calendars.CAL_ACCESS_READ))
        var events=emptyList<Array<Any>>()
        val queries=mutableListOf<Pair<Uri,List<String>>>()
        var fail=false
        override fun onCreate()=true
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor {
            queries+=uri to args.orEmpty().toList()
            if(fail)throw SecurityException("synthetic revoked read")
            return MatrixCursor(projection!!).apply {
                (if(uri==Calendars.CONTENT_URI)calendars else events).forEach {addRow(it)}
            }
        }
        override fun insert(uri:Uri,values:ContentValues?):Uri=error("No writes")
        override fun update(uri:Uri,values:ContentValues?,where:String?,args:Array<out String>?):Int=error("No writes")
        override fun delete(uri:Uri,where:String?,args:Array<out String>?):Int=error("No writes")
        override fun getType(uri:Uri):String?=null
    }
    private class Fixture {
        val provider=Provider();var allowed=true
        val resolver=RuntimeEnvironment.getApplication().contentResolver.also {
            provider.attachInfo(RuntimeEnvironment.getApplication(),ProviderInfo().apply {authority="com.android.calendar"})
            ShadowContentResolver.registerProviderInternal("com.android.calendar",provider)
        }
        val reader=CalendarConflictReader(resolver){allowed}
    }
    private fun event(id:Long,start:String,end:String,notes:String="",status:Int=Instances.STATUS_CONFIRMED,availability:Int=Instances.AVAILABILITY_BUSY,allDay:Int=0)=
        arrayOf<Any>(id,Instant.parse(start).toEpochMilli(),Instant.parse(end).toEpochMilli(),notes,status,availability,allDay)
    @Test fun newIntervalReadsAllVisibleCalendarsOfAuthorizedAccountAndExpandsInstances()=runTest {
        val f=Fixture();f.provider.calendars=f.provider.calendars+listOf(arrayOf<Any>(2L,"me@example.test","Other",1,Calendars.CAL_ACCESS_READ))
        f.provider.events=listOf(event(2,"2026-10-28T13:00:00Z","2026-10-28T15:00:00Z"),event(3,"2026-10-28T11:00:00Z","2026-10-28T12:30:00Z"))
        val result=f.reader.inspect(draft)
        assertTrue(result.complete);assertEquals(1,result.busy.size)
        assertEquals(listOf("me@example.test","1"),f.provider.queries[0].second)
        assertEquals(listOf("1","2"),f.provider.queries[1].second)
        assertTrue(f.provider.queries[1].first.path!!.contains("instances/when/"))
    }
    @Test fun permissionDenialAndRevocationAreIncompleteEvidence()=runTest {
        val f=Fixture();f.allowed=false
        assertFalse(f.reader.inspect(draft).complete);assertTrue(f.provider.queries.isEmpty())
        f.allowed=true;f.provider.fail=true;assertFalse(f.reader.inspect(draft).complete)
    }
    @Test fun missingDuplicateOrUnsyncedCalendarCannotClaimCompleteness()=runTest {
        for(rows in listOf(emptyList(),listOf(arrayOf<Any>(1L,draft.account,"Wrong",1,Calendars.CAL_ACCESS_READ)),
            listOf(arrayOf<Any>(1L,draft.account,"Events",0,Calendars.CAL_ACCESS_READ)),
            listOf(arrayOf<Any>(1L,draft.account,"Events",1,Calendars.CAL_ACCESS_FREEBUSY)),
            listOf(arrayOf<Any>(1L,draft.account,"Events",1,Calendars.CAL_ACCESS_READ),arrayOf<Any>(2L,draft.account,"Events",1,Calendars.CAL_ACCESS_READ)))) {
            val f=Fixture();f.provider.calendars=rows;assertFalse(f.reader.inspect(draft).complete);assertEquals(1,f.provider.queries.size)
        }
    }
    @Test fun ownMarkerExcludesItselfButDoesNotHideUnrelatedOrDuplicateRecords()=runTest {
        val f=Fixture()
        f.provider.events=listOf(event(1,draft.start!!,draft.end!!,draft.notes),event(2,draft.start!!,draft.end!!,"[octopus:other]"))
        assertEquals(1,f.reader.inspect(draft).busy.size)
        f.provider.events=f.provider.events+listOf(event(3,draft.start!!,draft.end!!,draft.notes))
        assertFalse(f.reader.inspect(draft).complete)
    }
    @Test fun cancelledAndFreeEventsDoNotBlockAndRecurringInstancesUseActualRange()=runTest {
        val f=Fixture();f.provider.events=listOf(event(1,draft.start!!,draft.end!!,status=Instances.STATUS_CANCELED),
            event(2,draft.start!!,draft.end!!,availability=Instances.AVAILABILITY_FREE),event(3,draft.start!!,draft.end!!))
        assertEquals(listOf(BusyInterval(Instant.parse(draft.start),Instant.parse(draft.end))),f.reader.inspect(draft).busy)
    }
    @Test fun allDayUtcDateIsComparedAsSydneyLocalDayAcrossMidnight()=runTest {
        val f=Fixture();f.provider.events=listOf(event(1,"2026-10-29T00:00:00Z","2026-10-30T00:00:00Z",allDay=1))
        val result=f.reader.inspect(draft)
        assertTrue(result.complete);assertEquals(Instant.parse("2026-10-28T13:00:00Z"),result.busy.single().start)
    }
    @Test fun invalidInstancesCannotSilentlyBecomeAnEmptyFreeSlot()=runTest {
        val f=Fixture();f.provider.events=listOf(event(1,draft.end!!,draft.start!!))
        assertFalse(f.reader.inspect(draft).complete)
    }
}
