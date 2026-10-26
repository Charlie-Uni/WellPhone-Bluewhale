package com.octopus.agent.storage

import android.app.Application
import com.octopus.core.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class JournalExportTest {
    @Test fun exportIncludesStructuredEventsButNotCanonicalMailOrLocators()=runTest {
        val context=RuntimeEnvironment.getApplication()
        val source=SourceEmail("PRIVATE_ACCOUNT","PRIVATE_SENDER","PRIVATE_SUBJECT","2026-10-24T01:00:00Z","hash")
        LocalStore(context).use { store ->
            store.insert(source.fingerprint(),listOf(CanonicalItem("key",RecruitmentItem(ItemType.INTERVIEW,"PRIVATE_TITLE",
                source,"PRIVATE_BODY","北京时间","Asia/Shanghai",confidence=Confidence.HIGH))))
            store.insertIfAbsent(WriteRecord("key",WriteStatus.UNKNOWN,"https://private.example.test/token"))
            store.record(AgentEvent("RESULT_UNKNOWN","session",2,actionId="key"))
            val exported=store.journalSnapshot().toString(Charsets.UTF_8)
            assertTrue(exported.contains("RESULT_UNKNOWN"));assertTrue(exported.contains("monotonicMillis"))
            assertFalse(exported.contains("PRIVATE_"));assertFalse(exported.contains("private.example"))
        }
    }
    @Test fun experimentModePersistsIndependentlyOfPerRunAuthorization() {
        val context=RuntimeEnvironment.getApplication();val settings=WorkflowSettings(context)
        assertTrue(settings.adaptivePacing());settings.setAdaptivePacing(false)
        assertFalse(WorkflowSettings(context).adaptivePacing());assertFalse(settings.config().scopeAuthorized)
    }
}
