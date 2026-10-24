package com.octopus.core

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@Serializable enum class ItemType { INTERVIEW, EXAM, MATERIALS_DEADLINE }
@Serializable enum class Confidence { HIGH, MEDIUM, LOW }
@Serializable data class SourceEmail(
    val accountKey: String,
    val sender: String,
    val subject: String,
    val sentAt: String,
    val bodyDigest: String,
    val guiLocator: String? = null,
) {
    fun fingerprint(): String {
        require(listOf(accountKey, sender, subject, sentAt, bodyDigest).all { it.isNotBlank() }) {
            "Source identity is incomplete"
        }
        Instant.parse(sentAt)
        return digest(listOf(accountKey.trim(), sender.trim().lowercase(), normalize(subject),
            Instant.parse(sentAt).toString(), bodyDigest).joinToString("\u0000"))
    }
}

@Serializable data class RecruitmentItem(
    val type: ItemType,
    val title: String,
    val source: SourceEmail,
    val originalTimeText: String,
    val originalZoneText: String,
    val sourceZone: String?,
    val start: String? = null,
    val end: String? = null,
    val deadline: String? = null,
    val remindAt: String? = null,
    val meetingLinkRef: String? = null,
    val confidence: Confidence,
    val evidence: List<String> = emptyList(),
    val ambiguities: List<String> = emptyList(),
) {
    fun confirmationReasons(): List<String> = buildList {
        if (title.isBlank()) add("Missing title")
        if (originalTimeText.isBlank() || originalZoneText.isBlank()) add("Missing original time evidence")
        if (confidence != Confidence.HIGH) add("Confidence requires review")
        if (Regex("(^|[^A-Za-z])CST([^A-Za-z]|$)", RegexOption.IGNORE_CASE).containsMatchIn(originalZoneText)) {
            add("CST is ambiguous")
        }
        if (Regex("明天|后天|下周|今晚|tomorrow|tonight|next week", RegexOption.IGNORE_CASE)
                .containsMatchIn(originalTimeText)) add("Relative date requires confirmation")
        addAll(ambiguities)
        if (sourceZone == null || runCatching { ZoneId.of(sourceZone) }.isFailure) add("Unknown source zone")
        fun parse(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (type == ItemType.MATERIALS_DEADLINE) {
            val due = parse(deadline)
            val reminder = parse(remindAt)
            if (due == null || reminder == null || !reminder.isBefore(due)) add("Invalid deadline/reminder")
        } else {
            val from = parse(start)
            val to = parse(end)
            if (from == null || to == null || !from.isBefore(to)) add("Invalid start/end")
            if (remindAt != null && (parse(remindAt) == null || from == null || !parse(remindAt)!!.isBefore(from))) {
                add("Invalid event reminder")
            }
        }
    }

    // Capture before a human edits the target record. Canonical items must be persisted.
    fun writeKey(): String = digest(listOf(source.fingerprint(), type.name, normalize(title),
        canonicalInstant(start), canonicalInstant(end), canonicalInstant(deadline)).joinToString("\u0000"))
}

private fun canonicalInstant(value: String?): String = value?.let { Instant.parse(it).toString() }.orEmpty()

object TimeRules {
    val beijing: ZoneId = ZoneId.of("Asia/Shanghai")
    val sydney: ZoneId = ZoneId.of("Australia/Sydney")

    fun toInstant(local: LocalDateTime, zone: ZoneId): Instant {
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.size == 1) { "Ambiguous or nonexistent local time requires confirmation" }
        return local.toInstant(offsets.single())
    }
    fun local(instant: String, zone: ZoneId = sydney): String =
        Instant.parse(instant).atZone(zone).toOffsetDateTime().toString()
}

data class BusyInterval(val start: Instant, val end: Instant) {
    init { require(start < end) }
    fun overlaps(other: BusyInterval): Boolean = start < other.end && other.start < end
}

enum class ConflictChoice { KEEP_BOTH, MARK_RESCHEDULE, REMINDER_ONLY }

internal fun normalize(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).replace(Regex("\\s+"), " ")

fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
