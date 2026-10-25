package com.octopus.agent.workflow

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import com.octopus.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Read-only local evidence: never launches the calendar, writes data, or sends busy details to a model. */
class CalendarConflictReader(private val resolver: ContentResolver, private val allowed: () -> Boolean) {
    constructor(context: Context) : this(context.contentResolver, {
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    })

    suspend fun inspect(draft: CalendarDraft): CalendarInspection = withContext(Dispatchers.IO) {
        fun unknown(reason: String) = CalendarInspection(emptyList(), false, reason)
        if (!allowed()) return@withContext unknown("未授权读取本机日历；请在工作台主动授权，或人工检查后选择保留")
        try {
            val start = Instant.parse(draft.start); val end = Instant.parse(draft.end)
            if (start >= end || Duration.between(start, end) > Duration.ofDays(366)) return@withContext unknown("检查时间范围不受支持")
            val ids = mutableListOf<Long>(); var targetCount = 0
            resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.ACCOUNT_NAME,
                Calendars.CALENDAR_DISPLAY_NAME, Calendars.SYNC_EVENTS, Calendars.CALENDAR_ACCESS_LEVEL),
                "${Calendars.ACCOUNT_NAME} = ? COLLATE NOCASE AND ${Calendars.VISIBLE} = ?",
                arrayOf(draft.account, "1"), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (ids.size >= 100 || !cursor.getString(1).equals(draft.account, true) || cursor.getInt(3) != 1 ||
                        cursor.getInt(4) < Calendars.CAL_ACCESS_READ) return@withContext unknown("可见日历存在未同步或不可读范围")
                    ids += cursor.getLong(0)
                    if (cursor.getString(2) == draft.calendarName) targetCount++
                }
            } ?: return@withContext unknown("本机日历列表不可读")
            if (targetCount != 1 || ids.isEmpty()) return@withContext unknown("目标账号/日历未能唯一匹配")
            val uri = Instances.CONTENT_URI.buildUpon().also {
                // Pad for UTC-encoded all-day dates, then apply local half-open overlap below.
                ContentUris.appendId(it, start.minus(Duration.ofDays(2)).toEpochMilli())
                ContentUris.appendId(it, end.plus(Duration.ofDays(2)).toEpochMilli())
            }.build()
            val busy = mutableListOf<BusyInterval>(); val ownIds = mutableSetOf<Long>(); var rows = 0
            resolver.query(uri, arrayOf(Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.DESCRIPTION,
                Instances.STATUS, Instances.AVAILABILITY, Instances.ALL_DAY),
                "${Instances.CALENDAR_ID} IN (${ids.joinToString { "?" }})", ids.map { it.toString() }.toTypedArray(), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (++rows > 10_000) return@withContext unknown("日历实例范围过大")
                    if (cursor.isNull(1) || cursor.isNull(2)) return@withContext unknown("日历实例缺少时间字段")
                    val markers = Regex("\\[octopus:[^\\]]+]").findAll(cursor.getString(3).orEmpty()).map { it.value }.toList()
                    if (markers == listOf(draft.marker)) { ownIds += cursor.getLong(0); continue }
                    if (cursor.getInt(4) == Instances.STATUS_CANCELED || cursor.getInt(5) == Instances.AVAILABILITY_FREE) continue
                    fun instant(column: Int): Instant {
                        val value = Instant.ofEpochMilli(cursor.getLong(column))
                        return if (cursor.getInt(6) == 0) value else value.atZone(ZoneOffset.UTC).toLocalDate()
                            .atStartOfDay(ZoneId.of(draft.localZone)).toInstant()
                    }
                    val interval = BusyInterval(instant(1), instant(2))
                    if (interval.overlaps(BusyInterval(start, end))) busy += interval
                }
            } ?: return@withContext unknown("本机日历实例不可读")
            if (ownIds.size > 1) return@withContext unknown("发现多个相同来源标记，需人工核对")
            CalendarInspection(busy.distinct().sortedWith(compareBy({ it.start }, { it.end })), true,
                "本机已同步、该账号全部可见日历；云端尚未同步和其他账号不在检查范围内")
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { unknown("读取本机日历失败；未离开当前表单") }
    }
}
