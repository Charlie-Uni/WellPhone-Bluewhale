package com.octopus.core

object GuiPrivacy {
    private val unsafe = Regex("delete|删除|发送|send|邀请|invite|permission|授权|sign in|登录|discard|丢弃|编辑|\\bedit\\b", RegexOption.IGNORE_CASE)
    private val navigation = Regex("^(search( mail| calendar)?|搜索(邮件|日历)?|navigate up|返回|back|show details|hide details|显示详细信息|隐藏详细信息|展开|more options|更多选项|日程|schedule|今天|today|day|日视图|next|previous|下一[天月年页]?|上一[天月年页]?|task|任务|event|活动|create|创建|add( notification| description)?|添加(通知|说明|提醒)?|remind|提醒|notification|通知|date|日期|time|时间|done|完成|ok|确定|cancel|取消|custom|自定义|loading|正在加载|dismiss|关闭)$", RegexOption.IGNORE_CASE)
    fun label(node: UiElement) = node.text.ifBlank { node.description }.trim()
    fun saveNode(node: UiElement) = label(node).lowercase() in setOf("save", "保存", "done", "完成")
    fun searchField(node: UiElement) = node.resourceId.contains("search", true) ||
        node.role.contains("SearchView", true) || Regex("^search( mail| calendar)?$|^搜索(邮件|日历)?$",
            RegexOption.IGNORE_CASE).matches(node.description)
    private fun toggle(node: UiElement) = node.checkable ||
        Regex("CheckBox|Switch|ToggleButton|RadioButton", RegexOption.IGNORE_CASE).containsMatchIn(node.role)
    fun redactUrls(text: String) = Regex("https?://[^\\s<>]+").replace(text, "[LINK]")
    private fun calendarText(text: String): Boolean {
        if (text.isBlank() || text.length > 100) return false
        return Regex("^[0-9: /.,–—+年月日周星期一二三四五六天上午下午时分()（）-]+$").matches(text) ||
            Regex("^(Mon|Tue|Wed|Thu|Fri|Sat|Sun|Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec|January|February|March|April|June|July|August|September|October|November|December|AM|PM|[0-9: ,./–—-])+$",
                RegexOption.IGNORE_CASE).matches(text.replace(" ", ""))
    }
    fun project(observation: Observation, values: Set<String>): Observation {
        val groups = mutableMapOf<Int, MutableSet<String>>()
        val byId = observation.elements.associateBy { it.id }
        val forbiddenSave = CalendarEvidence.saveControl(observation)
        if (observation.packageName == "com.google.android.calendar") {
            observation.elements.filter { label(it) in values }.forEach { leaf ->
                var node = byId[leaf.parentId]
                repeat(20) {
                    val current = node ?: return@repeat
                    if (current.clickable) { groups.getOrPut(current.id) { mutableSetOf() }.add(label(leaf)); node = null }
                    else node = byId[current.parentId]
                }
            }
        }
        return observation.copy(elements = observation.elements.mapNotNull { node ->
            val text = label(node)
            if (toggle(node) || unsafe.containsMatchIn(text) || saveNode(node) || node.id == forbiddenSave ||
                PrivacyGate.classify(MailMetadata("", text), false) == MailAccess.EXCLUDED) return@mapNotNull null
            val mail = observation.packageName == "com.google.android.gm"
            if (mail && node.resourceId.substringAfterLast('/') in setOf("subject", "subject_and_folder_view",
                    "senders", "sender_name", "sender_email", "body", "message_body", "conversation_message_body")) return@mapNotNull null
            val groupLabel = groups[node.id]?.sorted()?.joinToString(" · ")
            val known = values.any { it.isNotBlank() && text.equals(it, true) } || groupLabel != null
            val nav = text.length < 80 && navigation.matches(text)
            if (mail && !known && !nav && !node.scrollable && !(node.editable &&
                    (node.resourceId.contains("search", true) || navigation.matches(node.description)))) return@mapNotNull null
            val date = calendarText(text)
            val allowed = node.editable || node.scrollable || known || nav || date ||
                node.resourceId.contains(Regex("date|time|remind|notification|search", RegexOption.IGNORE_CASE))
            if (!allowed) return@mapNotNull null
            node.copy(text = redactUrls(groupLabel ?: if (known || nav || date) node.text else if (node.editable) "[EDITABLE]" else "[BUSY]"),
                description = redactUrls(if (groupLabel == null && (known || nav || date)) node.description else ""))
        })
    }
    fun permits(observation: Observation, action: Action, values: Set<String>, allowedIds: Set<Int>): Boolean {
        val id = when (action) {
            is Action.Click -> action.nodeId
            is Action.SetText -> action.nodeId
            is Action.SubmitSearch -> action.nodeId
            is Action.Scroll -> action.nodeId
            else -> return false
        }
        val node = observation.elements.singleOrNull { it.id == id } ?: return false
        if (id !in allowedIds || toggle(node) || unsafe.containsMatchIn(label(node)) || saveNode(node)) return false
        return when (action) {
            is Action.Click -> node.clickable
            is Action.SetText -> node.editable && action.text in values
            is Action.SubmitSearch -> node.editable && searchField(node) && node.text in values
            is Action.Scroll -> node.scrollable
            else -> false
        }
    }
}
