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
    private val taskDates = setOf("task_date", "reminder_date", "due_date")
    private val taskTimes = setOf("task_time", "reminder_time", "due_time")
    val fieldIds = startDates + endDates + startTimes + endTimes + taskDates + taskTimes
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
    fun read(scene: Observation, original: CalendarDraft): CalendarDraft? = runCatching {
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
        if (original.kind == TargetKind.TASK) {
            original.copy(remindAt = instant(taskDates, taskTimes).toString())
        } else {
            val start = instant(startDates, startTimes)
            val end = instant(endDates, endTimes)
            val offset = Duration.between(Instant.parse(original.remindAt), Instant.parse(original.start))
            require(!offset.isNegative && offset.seconds % 60 == 0L && offset.nano == 0)
            original.copy(start = start.toString(), end = end.toString(), remindAt = start.minus(offset).toString())
        }.also { require(valid(original, it)) }
    }.getOrNull()

    fun valid(original: CalendarDraft, candidate: CalendarDraft): Boolean = runCatching {
        require(candidate.copy(start = original.start, end = original.end, remindAt = original.remindAt,
            title = original.title, notes = original.notes) == original)
        val reminder = Instant.parse(candidate.remindAt)
        if (candidate.kind == TargetKind.EVENT) {
            val start = Instant.parse(candidate.start); val end = Instant.parse(candidate.end)
            require(start < end && reminder <= start)
            require(Duration.between(reminder, start) == Duration.between(Instant.parse(original.remindAt), Instant.parse(original.start)))
        } else candidate.deadline?.let { require(reminder < Instant.parse(it)) }
        true
    }.getOrDefault(false)
}
