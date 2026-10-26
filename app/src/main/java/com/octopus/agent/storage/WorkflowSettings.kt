package com.octopus.agent.storage

import android.content.Context
import com.octopus.core.WorkflowConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class ModelSettings(val endpoint: String = "", val model: String = "")
class WorkflowSettings(context: Context) {
    private val prefs = context.getSharedPreferences("workflow_settings", Context.MODE_PRIVATE)
    fun adaptivePacing() = prefs.getBoolean("adaptive_pacing",true)
    fun setAdaptivePacing(enabled: Boolean) { prefs.edit().putBoolean("adaptive_pacing",enabled).apply() }
    fun model(): ModelSettings = prefs.getString("model", null)?.let {
        runCatching { Json.decodeFromString<ModelSettings>(it) }.getOrNull()
    } ?: ModelSettings()
    fun config(): WorkflowConfig = prefs.getString("workflow", null)?.let {
        runCatching { Json.decodeFromString<WorkflowConfig>(it) }.getOrNull()
    }?.copy(scopeAuthorized = false) ?: WorkflowConfig(
        "整理招聘邮件中的面试、笔试和材料截止日期；冲突先问我", "", "Recruitment", "", "Events")
    fun save(model: ModelSettings, config: WorkflowConfig) {
        prefs.edit().putString("model", Json.encodeToString(model))
            .putString("workflow", Json.encodeToString(config.copy(scopeAuthorized = false))).apply()
    }
}
