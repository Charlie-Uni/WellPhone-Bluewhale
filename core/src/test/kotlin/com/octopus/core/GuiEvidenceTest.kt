package com.octopus.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*

class GuiEvidenceTest {
    private val token = SessionToken("test-session", 0)
    private fun screen(nodes: List<UiElement>, mail: Boolean = false) = Observation(token, "o", 2, 3,
        if (mail) "com.google.android.gm" else "com.google.android.calendar", 0, nodes)
    private val draft = CalendarDraft("key", TargetKind.EVENT, "面试", "me@example.test", "Events",
        "Australia/Sydney", "2026-10-27T02:00:00Z", "2026-10-27T02:30:00Z", null,
        "2026-10-27T01:45:00Z", "[octopus:key]\n原文：北京时间10:00–10:30")
    private fun detail(editing: Boolean = false): Observation = screen(listOf(
        UiElement(1, "Text", "面试", editable = editing),
        UiElement(2, "Text", draft.notes, resourceId = "calendar:id/description"),
        UiElement(3, "Text", "me@example.test"),
        UiElement(4, "Text", "Events"),
        UiElement(5, "Text", "2026-10-27"),
        UiElement(6, "Text", "13:00 – 13:30"),
        UiElement(7, "Text", "15 minutes before", resourceId = "calendar:id/notification"),
    ) + if (editing) listOf(UiElement(8, "Button", "Save", clickable = true)) else emptyList())

    @Test fun completeReadOnlyDetailMatchesWithoutModelClaims() {
        assertTrue(CalendarEvidence.matches(detail(), draft, false))
        assertTrue(CalendarEvidence.matches(detail(true), draft, true))
        assertFalse(CalendarEvidence.matches(detail(true), draft, false))
    }
    @Test fun originalTimesInNotesCannotHideWrongCalendarFields() {
        val wrong = detail().copy(elements = detail().elements.map {
            when (it.id) {
                2 -> it.copy(text = draft.notes + "\n2026-10-27 13:00 – 13:30")
                6 -> it.copy(text = "14:00 – 14:30")
                else -> it
            }
        })
        assertFalse(CalendarEvidence.matches(wrong, draft, false))
    }
    @Test fun wrongAccountAndTruncatedTreeCannotVerify() {
        assertFalse(CalendarEvidence.matches(detail().copy(elements = detail().elements.filter { it.id != 3 }), draft, false))
        assertFalse(CalendarEvidence.matches(detail().copy(truncated = true), draft, false))
    }
    @Test fun descendantNoteTextCannotVerifyAnIncorrectTime() {
        val wrong = detail().copy(elements = detail().elements.map {
            if (it.id == 6) it.copy(text = "14:00 – 14:30") else it
        } + UiElement(9, "Text", "13:00 – 13:30", parentId = 2))
        assertFalse(CalendarEvidence.matches(wrong, draft, false))
    }
    @Test fun onlyExplicitEmptyResultsWithMatchingQueryProveAbsence() {
        val query = UiElement(1, "EditText", draft.marker, editable = true)
        val empty = UiElement(2, "Text", "No results", resourceId = "calendar:id/empty_view")
        assertTrue(CalendarEvidence.absent(screen(listOf(query, empty)), draft.marker))
        assertFalse(CalendarEvidence.absent(screen(listOf(query, empty.copy(resourceId = "calendar:id/event_title"))), draft.marker))
        assertFalse(CalendarEvidence.absent(screen(listOf(query.copy(text = "other"), empty)), draft.marker))
    }
    @Test fun incompleteDayCoverageRemainsUnknownEvenIfSomeTimesAreVisible() {
        val inspection = CalendarEvidence.inspect(detail(), LocalDate.parse("2026-10-27"), TimeRules.sydney)
        assertFalse(inspection.complete)
        assertEquals("2026-10-27T02:00:00Z", inspection.busy.single().start.toString())
    }
    @Test fun gmailRowsAreGroupedLocallyWithoutUploadingInbox() {
        val observation = screen(listOf(
            UiElement(1, "RecyclerView", "", scrollable = true),
            UiElement(2, "Row", "", clickable = true, parentId = 1),
            UiElement(3, "Text", "面试通知", resourceId = "gmail:id/subject", parentId = 2),
            UiElement(4, "Text", "HR", resourceId = "gmail:id/senders", parentId = 2),
        ), true)
        assertEquals(MailRow(2, "面试通知", "HR"), GmailEvidence.rows(observation).single())
        val safe = GuiPrivacy.project(observation, setOf("label:Recruitment"))
        assertFalse(safe.elements.any { it.text.contains("面试通知") || it.text.contains("HR") })
    }
    @Test fun truncatedMailBodyIsNotPresentedAsCompleteSource() {
        val observation = screen(listOf(UiElement(1, "WebView", "正文", resourceId = "gmail:id/body")), true)
        assertEquals("正文", GmailEvidence.body(observation))
        assertNull(GmailEvidence.body(observation.copy(truncated = true)))
    }
    @Test fun absoluteHeaderTimeConvertsByZoneAndInvalidTimeRequiresReview() {
        assertEquals("2026-10-27T02:00:00Z", GmailEvidence.parseSentAt("2026-10-27 13:00", TimeRules.sydney))
        assertNull(GmailEvidence.parseSentAt("昨天", TimeRules.sydney))
        assertNull(GmailEvidence.parseSentAt("2026-10-04 02:30", TimeRules.sydney))
    }
    @Test fun saveAndDestructiveControlsAreNeverModelNavigationTools() {
        val observation = screen(listOf(
            UiElement(1, "Button", "Save", clickable = true),
            UiElement(2, "Button", "Delete", clickable = true),
            UiElement(3, "EditText", "", editable = true, resourceId = "calendar:id/search"),
        ))
        val safe = GuiPrivacy.project(observation, setOf(draft.marker))
        assertEquals(listOf(3), safe.elements.map { it.id })
        assertFalse(GuiPrivacy.permits(observation, Action.Click(1), setOf(draft.marker), setOf(1,2,3)))
        assertFalse(GuiPrivacy.permits(observation, Action.SetText(3, "unapproved"), setOf(draft.marker), setOf(3)))
        assertTrue(GuiPrivacy.permits(observation, Action.SetText(3, draft.marker), setOf(draft.marker), setOf(3)))
    }
    @Test fun compositeContainersAndCredentialSubjectsDoNotLeakToModel() {
        val observation = screen(listOf(
            UiElement(1, "RecyclerView", "Search mail\n验证码 123456", scrollable = true),
            UiElement(2, "EditText", "secret subject", editable = true, resourceId = "gmail:id/search"),
            UiElement(3, "Text", "普通私人邮件", resourceId = "gmail:id/subject"),
        ), true)
        val text = GuiPrivacy.project(observation, setOf("Recruitment")).elements.joinToString { it.text + it.description }
        assertFalse(text.contains("123456") || text.contains("secret subject") || text.contains("私人"))
    }
    @Test fun calendarPrivateTitlesAreMaskedAndLinksAlwaysRedacted() {
        val observation = screen(listOf(
            UiElement(1, "Button", "私人诊疗预约", resourceId = "calendar:id/event_title"),
            UiElement(2, "EditText", "https://meet.example.test/?token=secret", editable = true),
        ))
        val safe = GuiPrivacy.project(observation, setOf("https://meet.example.test/?token=secret"))
        assertFalse(safe.elements.joinToString().contains("secret"))
        assertFalse(safe.elements.joinToString().contains("诊疗"))
    }
    @Test fun taskCompletionTogglesAndEditorImeCannotBypassTheSaveGate() {
        val observation = screen(listOf(
            UiElement(1, "CheckBox", "面试", clickable = true, checkable = true),
            UiElement(2, "EditText", "面试", editable = true, resourceId = "calendar:id/title"),
        ))
        val values = setOf("面试")
        assertFalse(GuiPrivacy.project(observation, values).elements.any { it.id == 1 })
        assertFalse(GuiPrivacy.permits(observation, Action.Click(1), values, setOf(1)))
        assertFalse(GuiPrivacy.permits(observation, Action.SubmitSearch(2), values, setOf(2)))
    }
    @Test fun aMarkerAloneCannotVerifyMissingNotesOrMeetingLink() {
        val missing = detail().copy(elements = detail().elements.map {
            if (it.id == 2) it.copy(text = draft.marker) else it
        })
        assertFalse(CalendarEvidence.matches(missing, draft, false))
        val withLink = draft.copy(notes = draft.notes + "\nhttps://meet.example.test/?token=local")
        assertFalse(CalendarEvidence.matches(detail(), withLink, false))
    }
    @Test fun datePrefixesAndSimilarAccountNamesCannotVerifyDifferentFields() {
        val wrongDay = draft.copy(start = "2026-10-02T03:00:00Z", end = "2026-10-02T03:30:00Z",
            remindAt = "2026-10-02T02:45:00Z")
        assertFalse(CalendarEvidence.matches(detail(), wrongDay, false))
        val wrongAccount = detail().copy(elements = detail().elements.map {
            if (it.id == 3) it.copy(text = "notme@example.test") else if (it.id == 4) it.copy(text = "OtherEvents") else it
        })
        assertFalse(CalendarEvidence.matches(wrongAccount, draft, false))
    }
    @Test fun twelveHourTimesKeepTheirMeridiemWhenCompared() {
        val afternoon = detail().copy(elements = detail().elements.map {
            if (it.id == 6) it.copy(text = "1:00 PM – 1:30 PM") else it
        })
        assertTrue(CalendarEvidence.matches(afternoon, draft, false))
        val morningDraft = draft.copy(start = "2026-10-26T14:00:00Z", end = "2026-10-26T14:30:00Z",
            remindAt = "2026-10-26T13:45:00Z")
        assertFalse(CalendarEvidence.matches(afternoon, morningDraft, false))
    }
}
