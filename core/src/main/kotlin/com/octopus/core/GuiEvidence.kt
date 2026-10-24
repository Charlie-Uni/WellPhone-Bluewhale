package com.octopus.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

data class MailRow(val nodeId: Int, val subject: String, val sender: String)

/** Gmail resource profiles are a compatibility adapter, never an authorization fallback. */
object GmailEvidence {
    private fun UiElement.idName() = resourceId.substringAfterLast('/')
    fun rows(observation: Observation): List<MailRow> {
        val elements = observation.elements
        val byId = elements.associateBy { it.id }
        fun ancestors(element: UiElement): List<UiElement> {
            val result = mutableListOf<UiElement>()
            var current: UiElement? = element
            repeat(25) {
                val node = current ?: return result
                result += node
                current = byId[node.parentId]
            }
            return result
        }
        return elements.filter { it.idName() in setOf("subject", "subject_and_folder_view") && it.text.isNotBlank() }
            .mapNotNull { subject ->
                val group = ancestors(subject).firstOrNull { it.clickable } ?: return@mapNotNull null
                val senders = elements.filter { node -> ancestors(node).any { it.id == group.id } &&
                    node.idName() in setOf("senders", "sender_name", "sender") }
                val sender = senders.joinToString(" ") { it.text }
                if (sender.isBlank()) null else MailRow(group.id, subject.text, sender)
            }
            .distinctBy { it.nodeId }
    }
    fun body(observation: Observation): String? {
        if (observation.truncated) return null
        val elements = observation.elements
        val byId = elements.associateBy { it.id }
        val roots = elements.filter { it.resourceId.substringAfterLast('/') in
            setOf("body", "message_body", "conversation_message_body", "message_content") ||
            it.role.contains("WebView") }.map { it.id }.toSet()
        fun isBody(node: UiElement): Boolean {
            var current: UiElement? = node
            repeat(25) {
                if (current?.id in roots) return true
                current = byId[current?.parentId] ?: return false
            }
            return false
        }
        val pieces = elements.filter { isBody(it) && it.text.isNotBlank() }.map { it.text }.distinct()
        val body = pieces.joinToString("\n").trim()
        return body.takeIf { it.isNotBlank() && it.length <= 24_000 }
    }
    fun headerText(observation: Observation, names: Set<String>): String =
        observation.elements.filter { it.resourceId.substringAfterLast('/') in names }
            .joinToString(" ") { it.text.ifBlank { it.description } }.trim()

    fun parseSentAt(raw: String, zone: ZoneId): String? {
        runCatching { return Instant.parse(raw.trim()).toString() }
        val formats = listOf("uuuu-MM-dd HH:mm", "uuuu/MM/dd HH:mm", "uuuu年M月d日 HH:mm",
            "MMM d, uuuu, h:mm a", "MMMM d, uuuu 'at' h:mm a")
        for (format in formats) {
            val parsed = runCatching { LocalDateTime.parse(raw.trim(),
                DateTimeFormatter.ofPattern(format, Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)) }.getOrNull() ?: continue
            return runCatching { TimeRules.toInstant(parsed, zone).toString() }.getOrNull()
        }
        return null
    }
}

/** Deterministic final checks exclude notes so quoted original times cannot verify wrong fields. */
object CalendarEvidence {
    private fun content(node: UiElement) = (node.text + " " + node.description).trim()
    fun saveControl(observation: Observation): Int? {
        val byId = observation.elements.associateBy { it.id }
        val controls = observation.elements.filter { GuiPrivacy.saveNode(it) }.mapNotNull { leaf ->
            var node: UiElement? = leaf
            repeat(20) {
                val current = node ?: return@mapNotNull null
                if (current.clickable) return@mapNotNull current.id
                node = byId[current.parentId]
            }
            null
        }.distinct()
        return controls.singleOrNull()
    }
    private fun fieldNodes(observation: Observation): List<UiElement> {
        val byId = observation.elements.associateBy { it.id }
        val excluded = observation.elements.filter {
            it.resourceId.contains(Regex("description|note|location", RegexOption.IGNORE_CASE)) ||
                content(it).contains("[octopus:") || content(it).contains("原文：") || content(it).contains("来源：")
        }.map { it.id }.toSet()
        return observation.elements.filter { node ->
            var current: UiElement? = node
            var blocked = false
            repeat(30) {
                if (current?.id in excluded) blocked = true
                current = byId[current?.parentId]
            }
            !blocked
        }
    }
    fun queryIs(observation: Observation, query: String) =
        observation.elements.any { it.editable && it.text == query }
    fun absent(observation: Observation, query: String): Boolean =
        queryIs(observation, query) && observation.elements.any {
            it.resourceId.contains(Regex("empty|no_results|noresults", RegexOption.IGNORE_CASE)) &&
            Regex("^(no results|no matching events|没有结果|无结果|未找到.*|没有找到.*)$",
                RegexOption.IGNORE_CASE).matches(it.text.trim())
        }
    private fun datePresent(text: String, date: LocalDate): Boolean {
        val alternatives = listOf(date.toString(), "${date.year}年${date.monthValue}月${date.dayOfMonth}日",
            "${date.monthValue}月${date.dayOfMonth}日",
            date.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)),
            date.format(DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH)),
            date.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)))
        // Year must be shown somewhere on the page or in a complete date.
        return alternatives.any {
            Regex("(?<![0-9])${Regex.escape(it)}(?![0-9])", RegexOption.IGNORE_CASE).containsMatchIn(text)
        } && Regex("(?<![0-9])${date.year}(?![0-9])").containsMatchIn(text)
    }
    private fun timePresent(text: String, instant: String, zone: ZoneId): Boolean {
        val local = Instant.parse(instant).atZone(zone)
        val time = Regex("(?<![0-9])(上午|下午)?\\s*(\\d{1,2}):(\\d{2})(?:\\s*(AM|PM))?(?![0-9])",
            RegexOption.IGNORE_CASE)
        return time.findAll(text).any { match ->
            val (prefix, rawHour, rawMinute, suffix) = match.destructured
            val period = when { prefix == "上午" -> "AM"; prefix == "下午" -> "PM"; else -> suffix.uppercase() }
            if (prefix.isNotBlank() && suffix.isNotBlank() && period != suffix.uppercase()) return@any false
            val hour = rawHour.toInt()
            if (period.isNotEmpty() && hour !in 1..12) return@any false
            val resolvedHour = if (period.isEmpty()) hour else hour % 12 + if (period == "PM") 12 else 0
            runCatching { LocalTime.of(resolvedHour, rawMinute.toInt()) == local.toLocalTime() }.getOrDefault(false)
        }
    }
    fun matches(observation: Observation, draft: CalendarDraft, editing: Boolean): Boolean {
        if (observation.truncated || observation.packageName != "com.google.android.calendar") return false
        val nodes = fieldNodes(observation)
        if (nodes.none { it.text.trim() == draft.title.trim() }) return false
        if (observation.elements.none { content(it).contains(draft.marker) && (!editing && !it.editable || editing) }) return false
        fun normalized(value: String) = value.replace(Regex("\\s+"), " ").trim()
        val observedText = normalized(observation.elements.joinToString("\n", transform = ::content))
        if (draft.notes.lineSequence().filter { it.isNotBlank() }.any { !observedText.contains(normalized(it)) }) return false
        val allFields = nodes.joinToString("\n", transform = ::content)
        fun selected(value: String) = Regex("(?<![\\p{L}\\p{N}_@.\\-])${Regex.escape(value)}(?![\\p{L}\\p{N}_@.\\-])",
            RegexOption.IGNORE_CASE).containsMatchIn(allFields)
        if (!selected(draft.account) || !selected(draft.calendarName)) return false
        val text = nodes.filter { it.text.trim() != draft.title.trim() }.joinToString("\n", transform = ::content)
        val zone = ZoneId.of(draft.localZone)
        val reference = draft.start ?: draft.remindAt
        if (!datePresent(text, Instant.parse(reference).atZone(zone).toLocalDate())) return false
        if (!timePresent(text, reference, zone)) return false
        if (draft.kind == TargetKind.EVENT && !timePresent(text, requireNotNull(draft.end), zone)) return false
        if (draft.kind == TargetKind.EVENT &&
            !datePresent(text, Instant.parse(draft.end).atZone(zone).toLocalDate())) return false
        val reminder = if (draft.kind == TargetKind.EVENT) {
            val minutes = java.time.Duration.between(Instant.parse(draft.remindAt), Instant.parse(draft.start)).toMinutes()
            Regex("(?<![0-9])$minutes\\s*(minutes?|mins?|分钟|分)(\\s*(before|前))?", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)
        } else timePresent(text, draft.remindAt, zone)
        if (!reminder) return false
        val saveVisible = nodes.any { GuiPrivacy.saveNode(it) }
        return if (editing) saveVisible else !saveVisible && nodes.none { it.editable }
    }

    fun inspect(observation: Observation, day: LocalDate, zone: ZoneId): CalendarInspection {
        // Complete coverage is only claimed with an explicit day heading and no scrollable list.
        val all = observation.elements.joinToString("\n", transform = ::content)
        val dateKnown = datePresent(all, day)
        val busy = mutableListOf<BusyInterval>()
        val timeRange = Regex("(?<![0-9])(\\d{1,2}):(\\d{2})\\s*[–—−-]\\s*(\\d{1,2}):(\\d{2})(?![0-9])")
        for (node in fieldNodes(observation)) {
            if (node.editable) continue
            if (Regex("\\bAM\\b|\\bPM\\b|上午|下午", RegexOption.IGNORE_CASE).containsMatchIn(content(node))) continue
            for (match in timeRange.findAll(content(node))) {
                val (h1, m1, h2, m2) = match.destructured
                runCatching {
                    val start = day.atTime(h1.toInt(), m1.toInt())
                    val end = day.atTime(h2.toInt(), m2.toInt())
                    busy += BusyInterval(TimeRules.toInstant(start, zone), TimeRules.toInstant(end, zone))
                }
            }
        }
        val explicitEmpty = observation.elements.any {
            it.resourceId.contains("empty", true) &&
                Regex("no events|没有日程|暂无日程", RegexOption.IGNORE_CASE).containsMatchIn(content(it))
        }
        // Third-party Apps do not normally expose a coverage guarantee; unknown asks the user.
        return CalendarInspection(busy, dateKnown && !observation.truncated &&
            observation.elements.none { it.scrollable } && explicitEmpty)
    }
}
