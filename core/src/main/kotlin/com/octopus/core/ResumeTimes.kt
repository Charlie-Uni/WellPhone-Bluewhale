package com.octopus.core

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** Role-bound, complete dates only. Never infer a year, meridiem or DST offset. */
object ResumeTimes {
    private val startDates = setOf("start_date", "event_start_date")
    private val endDates = setOf("end_date", "event_end_date")
    private val startTimes = setOf("start_time", "event_start_time")
    private val endTimes = setOf("end_time", "event_end_time")
    private val taskDates = setOf("task_date", "reminder_date", "notification_date")
    private val taskTimes = setOf("task_time", "reminder_time", "notification_time")
    private val deadlineDates = setOf("deadline_date", "task_deadline_date", "due_date")
    private val deadlineTimes = setOf("deadline_time", "task_deadline_time", "due_time")
    private val reminderOffsets = setOf("notification", "event_reminder", "reminder_offset", "reminder_minutes")
    private val noteIds = setOf("description", "event_description", "description_input", "notes", "note")
    fun fieldIds(kind: TargetKind) = if (kind == TargetKind.EVENT)
        startDates + endDates + startTimes + endTimes + reminderOffsets else taskDates + taskTimes + deadlineDates + deadlineTimes
    private class InvalidTime(val code: String) : IllegalArgumentException(code)
    private fun field(scene: Observation, ids: Set<String>): String? {
        val node = scene.elements.filter { it.resourceId.substringAfterLast('/') in ids }.singleOrNull() ?: return null
        return listOf(node.text.trim(), node.description.trim()).filter { it.isNotBlank() }.distinct().singleOrNull()
    }
    private fun date(text: String): LocalDate? {
        for (pattern in listOf("uuuu-MM-dd", "uuuu/M/d", "uuuu年M月d日", "MMM d, uuuu", "MMMM d, uuuu", "d MMM uuuu")) {
            runCatching { LocalDate.parse(text, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
                .withResolverStyle(ResolverStyle.STRICT)) }.getOrNull()?.let { return it }
        }
        return null
    }
    private fun time(text: String): LocalTime? {
        val match = Regex("^(?:(上午|下午)\\s*)?(\\d{1,2}):(\\d{2})(?:\\s*(AM|PM))?$", RegexOption.IGNORE_CASE)
            .matchEntire(text) ?: return null
        val (prefix, hour, minute, suffix) = match.destructured
        val period = if (prefix == "上午") "AM" else if (prefix == "下午") "PM" else suffix.uppercase()
        if (prefix.isNotBlank() && suffix.isNotBlank() && period != suffix.uppercase()) return null
        val h = hour.toInt()
        if (period.isNotBlank() && h !in 1..12) return null
        return runCatching { LocalTime.of(if (period.isBlank()) h else h % 12 + if (period == "PM") 12 else 0, minute.toInt()) }.getOrNull()
    }
    private fun minutes(scene: Observation): Long {
        val text = requireNotNull(field(scene, reminderOffsets))
        val english = Regex("^(\\d{1,6})\\s*(minutes?|mins?|hours?|days?)\\s+before$", RegexOption.IGNORE_CASE).matchEntire(text)
        val chinese = Regex("^(?:提前\\s*)?(\\d{1,6})\\s*(分钟|小时|天)(?:前)?$").matchEntire(text)
        val match = english ?: chinese
        if (match == null) {
            require(scene.elements.single { it.resourceId.substringAfterLast('/') in reminderOffsets }
                .resourceId.substringAfterLast('/') == "reminder_minutes")
            return text.toLong().also { require(it in 1..525_600) }
        }
        val unit = match.groupValues[2].lowercase()
        val factor = when { unit.startsWith("hour") || unit == "小时" -> 60L; unit.startsWith("day") || unit == "天" -> 1440L; else -> 1L }
        return (match.groupValues[1].toLong() * factor).also { require(it in 1..525_600) }
    }
    fun reminderMatches(scene: Observation, draft: CalendarDraft): Boolean = runCatching {
        Instant.parse(draft.start).minusSeconds(minutes(scene) * 60) == Instant.parse(draft.remindAt)
    }.getOrDefault(false)
    private fun parse(scene: Observation, original: CalendarDraft): Result<CalendarDraft> = runCatching {
        require(!scene.truncated)
        require(field(scene, setOf("account_name", "calendar_account"))?.equals(original.account, true) == true)
        require(field(scene, setOf("calendar_name", "list_name", "task_list_name"))?.equals(original.calendarName, true) == true)
        val zones = scene.elements.filter { it.resourceId.substringAfterLast('/') in setOf("timezone", "time_zone", "event_timezone") }
        require(zones.isEmpty() || field(scene, setOf("timezone", "time_zone", "event_timezone")) == original.localZone)
        val zone = ZoneId.of(original.localZone)
        fun instant(dates: Set<String>, times: Set<String>): Instant {
            val date = requireNotNull(date(requireNotNull(field(scene, dates))))
            val time = requireNotNull(time(requireNotNull(field(scene, times))))
            return TimeRules.toInstant(LocalDateTime.of(date, time), zone)
        }
        fun deadline(): String? {
            if (scene.elements.any { it.resourceId.substringAfterLast('/') in deadlineDates + deadlineTimes }) {
                // Never reuse due_* as notification fields. Partial native deadline fields block fallback.
                return instant(deadlineDates, deadlineTimes).toString()
            }
            val notes = requireNotNull(field(scene, noteIds))
            val lines = notes.lineSequence().map(String::trim).filter { it.startsWith("截止：") }.toList()
            if (lines.isEmpty()) { require(original.deadline == null); return null }
            val text = lines.single().removePrefix("截止：").trim()
            val offset = runCatching { OffsetDateTime.parse(text) }.getOrNull()
            val local = offset?.toLocalDateTime() ?: LocalDateTime.parse(text)
            val resolved = TimeRules.toInstant(local, zone)
            require(offset == null || offset.toInstant() == resolved) // No stale/wrong offset across DST.
            return resolved.toString()
        }
        if (original.kind == TargetKind.TASK) {
            original.copy(remindAt = instant(taskDates, taskTimes).toString(), deadline = deadline())
        } else {
            val start = instant(startDates, startTimes)
            val end = instant(endDates, endTimes)
            original.copy(start = start.toString(), end = end.toString(), remindAt = start.minusSeconds(minutes(scene) * 60).toString())
        }.also { validate(original, it) }
    }
    fun read(scene: Observation, original: CalendarDraft): CalendarDraft? = parse(scene, original).getOrNull()
    fun problem(scene: Observation, original: CalendarDraft): String? =
        (parse(scene, original).exceptionOrNull() as? InvalidTime)?.code

    private fun validate(original: CalendarDraft, candidate: CalendarDraft) {
        require(candidate.copy(start = original.start, end = original.end, remindAt = original.remindAt,
            deadline = if (original.kind == TargetKind.TASK) original.deadline else candidate.deadline,
            title = original.title, notes = original.notes) == original)
        val reminder = Instant.parse(candidate.remindAt)
        if (candidate.deadline != null && reminder >= Instant.parse(candidate.deadline)) {
            throw InvalidTime("RESUME_REMINDER_NOT_BEFORE_DEADLINE")
        }
        if (candidate.kind == TargetKind.EVENT) {
            val start = Instant.parse(candidate.start); val end = Instant.parse(candidate.end)
            require(start < end && reminder < start)
            val offset = Duration.between(reminder, start)
            require(offset.seconds in 60..31_536_000 && offset.seconds % 60 == 0L && offset.nano == 0)
        } else {
            require(candidate.start == original.start && candidate.end == original.end)
            require(original.deadline == null || candidate.deadline != null)
        }
    }
    fun valid(original: CalendarDraft, candidate: CalendarDraft): Boolean = runCatching { validate(original, candidate) }.isSuccess
}
