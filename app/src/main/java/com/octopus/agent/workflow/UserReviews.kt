package com.octopus.agent.workflow

import com.octopus.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

data class QuestionField(val key: String, val label: String, val value: String)
data class UserQuestion(
    val id: String = UUID.randomUUID().toString(), val token: SessionToken,
    val title: String, val detail: String, val fields: List<QuestionField> = emptyList(),
    val options: List<String>,
)
data class UserAnswer(val option: Int, val fields: Map<String, String>)

class UserReviews(private val broker: SessionBroker, private val present: (UserQuestion?) -> Unit) {
    private var pending: Pair<UserQuestion, CompletableDeferred<UserAnswer>>? = null
    suspend fun ask(question: UserQuestion): UserAnswer {
        check(pending == null)
        checkAuthority(question.token)
        val deferred = CompletableDeferred<UserAnswer>()
        pending = question to deferred
        present(question)
        return try { deferred.await().also { checkAuthority(question.token) } } finally {
            pending = null; present(null)
        }
    }
    suspend fun answer(id: String, answer: UserAnswer) {
        val (question, deferred) = pending ?: return
        if (question.id != id || answer.option !in question.options.indices) return
        try {
            checkAuthority(question.token)
            deferred.complete(answer)
        } catch (revoked: ActionRejected) { deferred.completeExceptionally(revoked) }
    }
    fun cancel() { pending?.second?.completeExceptionally(CancellationException("Review cancelled")) }
    private suspend fun checkAuthority(token: SessionToken) {
        val state = broker.state(token.sessionId)
        if (state.token != token || state.owner != Owner.AGENT) throw ActionRejected("Confirmation is stale")
    }
}

class WorkflowReviews(
    private val questions: UserReviews, private val calendarToken: SessionToken, private val zone: ZoneId,
) : ReviewPort {
    override suspend fun confirmResume(draft: CalendarDraft, inspection: CalendarInspection?): Boolean {
        val start = draft.start ?: draft.remindAt
        val conflict = inspection?.let { checked ->
            val busy = checked.busy.joinToString("\n") { "${TimeRules.local(it.start.toString(), zone)} – ${TimeRules.local(it.end.toString(), zone)}" }
            (if (!checked.complete) "冲突检查范围不完整，需你人工核对。" else if (checked.busy.isEmpty()) "检查范围内未发现冲突。" else "新时间与以下日程冲突：") +
                "\n$busy\n${checked.detail}"
        }.orEmpty()
        val answer = questions.ask(UserQuestion(token = calendarToken, title = "沿用当前修改继续",
            detail = "${draft.title}\n${TimeRules.local(start, zone)}" +
                (draft.end?.let { " – ${TimeRules.local(it, zone)}" }.orEmpty()) +
                "\n提醒：${TimeRules.local(draft.remindAt, zone)}" +
                (draft.deadline?.let { "\n截止设置：${TimeRules.local(it, zone)}（已校验提醒早于截止）" }.orEmpty()) +
                "\n备注（原样保留）：\n${draft.notes}\n\n$conflict\n" +
                "保留你修改后的时间和当前表单。继续表示接受上述检查边界/冲突；已有日程不会修改。已保存的事项只核实，未保存的事项保存后重开核实。要改约或改成提醒，请先保留页面并接管调整。",
            options = listOf("保留新时间并继续", "先保留页面")))
        return answer.option == 0
    }
    override suspend fun reviewItem(item: RecruitmentItem, reasons: List<String>): RecruitmentItem? {
        fun local(value: String?) = value?.let {
            runCatching { Instant.parse(it).atZone(zone).toLocalDateTime().toString() }.getOrDefault(it)
        }.orEmpty()
        val fields = mutableListOf(QuestionField("title", "标题", item.title),
            QuestionField("zone", "原文时区（IANA）", item.sourceZone.orEmpty()))
        if (item.type == ItemType.MATERIALS_DEADLINE) {
            fields += QuestionField("deadline", "截止时间（${zone.id}，例 2026-10-28T21:00）", local(item.deadline))
        } else {
            fields += QuestionField("start", "开始时间（${zone.id}）", local(item.start))
            fields += QuestionField("end", "结束时间（${zone.id}）", local(item.end))
        }
        fields += QuestionField("remindAt", "提醒时间（${zone.id}）", local(item.remindAt))
        val answer = questions.ask(UserQuestion(token = calendarToken, title = "确认招聘事项",
            detail = "原文：${item.originalTimeText}（${item.originalZoneText}）\n${reasons.joinToString("；")}",
            fields = fields, options = listOf("确认这些时间", "暂不处理")))
        if (answer.option != 0) return null
        fun instant(key: String): String? = answer.fields[key]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            TimeRules.toInstant(LocalDateTime.parse(it), zone).toString()
        }
        return item.copy(title = answer.fields["title"].orEmpty(), sourceZone = answer.fields["zone"],
            start = instant("start") ?: item.start, end = instant("end") ?: item.end,
            deadline = instant("deadline") ?: item.deadline, remindAt = instant("remindAt") ?: item.remindAt)
    }
    override suspend fun resolveConflict(item: RecruitmentItem, inspection: CalendarInspection): ConflictChoice? {
        val busy = inspection.busy.joinToString("\n") {
            "${it.start.atZone(zone).toLocalDateTime()} – ${it.end.atZone(zone).toLocalTime()}"
        }
        val answer = questions.ask(UserQuestion(token = calendarToken, title = "选择日程处理方式",
            detail = (if (inspection.complete) "已有时间冲突。" else "界面未能证明日程范围完整，请检查后选择。") +
                "\n${item.title}\n$busy\n现有日程不会修改，也不会发送邀请或回信。",
            options = listOf("两个都保留", "新事项标为待改约", "只设提醒", "暂不处理")))
        return when (answer.option) {
            0 -> ConflictChoice.KEEP_BOTH
            1 -> ConflictChoice.MARK_RESCHEDULE
            2 -> ConflictChoice.REMINDER_ONLY
            else -> null
        }
    }
}
