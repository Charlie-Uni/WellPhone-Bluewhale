package com.octopus.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Narrow deterministic grounding; unsupported prose asks for review rather than guessing. */
object TimeGrounding {
    fun issues(item: RecruitmentItem, body: String): List<String> {
        val issues = mutableListOf<String>()
        val zone = when {
            body.contains("北京时间") || body.contains("中国标准时间") -> TimeRules.beijing
            else -> return listOf("原文时区无法由本地规则独立核对")
        }
        if (item.sourceZone != zone.id) issues += "模型时区与原文时区不一致"
        val dates = Regex("(\\d{4})[年/-](\\d{1,2})[月/-](\\d{1,2})日?").findAll(body)
            .mapNotNull { match -> runCatching {
                LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
            }.getOrNull() }.distinct().toList()
        if (dates.size != 1) return issues + "原文日期需要人工确认"
        val day = dates.single()
        val reminders = Regex("提前\\s*(\\d+)\\s*(分钟|小时)").findAll(body).toList()
        val requestedMinutes = reminders.singleOrNull()?.let {
            it.groupValues[1].toLong() * if (it.groupValues[2] == "小时") 60 else 1
        }
        fun same(actual: String?, expected: Instant) = runCatching { Instant.parse(actual) == expected }.getOrDefault(false)
        if (item.type == ItemType.MATERIALS_DEADLINE) {
            val times = Regex("(?<![0-9])(\\d{1,2}):(\\d{2})(?![0-9])").findAll(body).toList()
            if (times.size != 1) return issues + "截止时刻需要人工确认"
            val time = times.single()
            val due = runCatching { TimeRules.toInstant(day.atTime(time.groupValues[1].toInt(),
                time.groupValues[2].toInt()), zone) }.getOrNull() ?: return issues + "截止日期无效"
            if (!same(item.deadline, due)) issues += "截止时刻与原文不一致"
            if (requestedMinutes == null || !same(item.remindAt, due.minusSeconds(requestedMinutes * 60))) {
                issues += "提醒时刻需要确认"
            }
        } else {
            val ranges = Regex("(\\d{1,2}):(\\d{2})\\s*[–—−-]\\s*(\\d{1,2}):(\\d{2})").findAll(body).toList()
            if (ranges.size != 1) return issues + "起止时间无法由本地规则独立核对"
            val parts = ranges.single().groupValues.drop(1).map { it.toInt() }
            val start = runCatching { TimeRules.toInstant(day.atTime(parts[0], parts[1]), zone) }.getOrNull()
                ?: return issues + "开始时刻无效"
            val end = runCatching { TimeRules.toInstant(day.atTime(parts[2], parts[3]), zone) }.getOrNull()
                ?: return issues + "结束时刻无效"
            if (!start.isBefore(end)) issues += "跨日或逆序时间需要确认"
            if (!same(item.start, start) || !same(item.end, end)) issues += "模型起止时刻与原文不一致"
            val alert = requestedMinutes ?: 15
            if (item.remindAt != null && !same(item.remindAt, start.minusSeconds(alert * 60))) issues += "事件提醒与原文不一致"
        }
        return issues
    }
}
