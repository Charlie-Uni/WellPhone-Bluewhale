package com.octopus.core

enum class MailAccess { CANDIDATE, EXCLUDED, NEEDS_REVIEW }
data class MailMetadata(val sender: String, val subject: String)

object PrivacyGate {
    // Conservative prefilter, not a universal secret detector. User-selected scope is required.
    private val sensitive = Regex("验证码|动态口令|一次性密码|重置密码|登录验证|校验码|临时密码|初始密码|登录密码|账号密码|账户密码|访问令牌|密钥|verification code|one.time (password|code)|password reset|sign.in code|temporary password|initial password|password\\s*[:：=]|access[_ -]?token|api[_ -]?key|client[_ -]?secret|\\botp\\b", RegexOption.IGNORE_CASE)
    private val recruitment = Regex("面试|笔试|补充材料|招聘|interview|assessment|recruit|application.*(material|document)", RegexOption.IGNORE_CASE)
    fun classify(metadata: MailMetadata, userSelectedScope: Boolean): MailAccess {
        val text = metadata.sender + " " + metadata.subject
        if (sensitive.containsMatchIn(text)) return MailAccess.EXCLUDED
        if (!userSelectedScope) return MailAccess.NEEDS_REVIEW
        return if (recruitment.containsMatchIn(text)) MailAccess.CANDIDATE else MailAccess.NEEDS_REVIEW
    }
    fun safeBody(metadata: MailMetadata, userSelectedScope: Boolean, body: String): String {
        require(classify(metadata, userSelectedScope) == MailAccess.CANDIDATE) { "Mail is not allowed for model egress" }
        require(!sensitive.containsMatchIn(body)) { "Sensitive body requires local user review" }
        // Full meeting links remain on device; model receives only stable references.
        var index = 0
        return Regex("https?://[^\\s<>]+").replace(body) { "[LINK_${++index}]" }
    }
}
