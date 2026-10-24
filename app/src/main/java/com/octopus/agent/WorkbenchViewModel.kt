package com.octopus.agent

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.octopus.agent.accessibility.OctopusAccessibilityService
import com.octopus.agent.bridge.ShizukuConnector
import com.octopus.core.AgentEvent
import com.octopus.core.Owner
import com.octopus.core.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ProbeSession(val label: String, val handle: String, val state: SessionState,
    val flags: Int, val frame: Bitmap? = null, val nodeCount: Int? = null)
data class WorkbenchState(
    val connected: Boolean = false, val busy: Boolean = false,
    val message: String = "连接 Shizuku 后开始平台预检",
    val sessions: List<ProbeSession> = emptyList(),
)

class WorkbenchViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OctopusApplication
    private val connector = ShizukuConnector(application)
    private val mutableState = MutableStateFlow(WorkbenchState())
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch {
            connector.service.collect { service ->
                if (service == null) {
                    mutableState.value.sessions.forEach { app.broker.stop(it.state.token.sessionId) }
                    mutableState.value = WorkbenchState(message = "未连接。shell 断开后旧会话停止，不自动恢复。")
                } else {
                    mutableState.value = mutableState.value.copy(connected = true,
                        message = "已连接 shell；创建虚拟屏后仍需验证主屏焦点与输入法")
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                val service = connector.service.value ?: continue
                for (probe in mutableState.value.sessions) {
                    val bitmap = withContext(Dispatchers.IO) {
                        runCatching {
                            service.capture(probe.handle)?.let { fd ->
                                ParcelFileDescriptor.AutoCloseInputStream(fd).use { BitmapFactory.decodeStream(it) }
                            }
                        }.getOrNull()
                    }
                    // Avoid publishing an old frame after the session has been removed.
                    mutableState.value = mutableState.value.copy(sessions = mutableState.value.sessions.map {
                        if (it.handle == probe.handle) it.copy(frame = bitmap ?: it.frame) else it
                    })
                }
            }
        }
    }
    fun connect() { runCatching { connector.connect() }.onFailure { showError(it) } }
    fun create(calendar: Boolean) = work {
        val service = requireNotNull(connector.service.value)
        val packageName = if (calendar) "com.google.android.calendar" else "com.google.android.gm"
        check(mutableState.value.sessions.none { it.state.packageName == packageName })
        val result = withContext(Dispatchers.IO) { JSONObject(service.createDisplay(720, 1280, 240)) }
        val handle = result.getString("handle")
        val session = try { app.broker.create(result.getInt("displayId"), packageName) } catch (failure: Throwable) {
            withContext(Dispatchers.IO) { service.closeDisplay(handle) }
            throw failure
        }
        mutableState.value = mutableState.value.copy(sessions = mutableState.value.sessions +
            ProbeSession(if (calendar) "B · 日历" else "A · 邮箱", handle, session, result.getInt("effectiveFlags")),
            message = "已创建 display ${session.displayId}；隔离行为待真机测试。不会自动打开 App。")
    }
    fun launch(probe: ProbeSession) = work {
        val service = requireNotNull(connector.service.value)
        withContext(Dispatchers.IO) { service.launch(probe.handle, probe.state.packageName) }
        mutableState.value = mutableState.value.copy(message = "启动请求已接受，请检查预览与窗口绑定")
    }
    fun observe(probe: ProbeSession) = work {
        val observer = requireNotNull(OctopusAccessibilityService.instance) { "请先手动开启无障碍服务" }
        val current = app.broker.state(probe.state.token.sessionId)
        val observation = try { observer.observe(current) } catch (failure: Throwable) {
            app.broker.stop(current.token.sessionId)
            refreshSession(probe)
            throw failure
        }
        if (current.owner == Owner.AGENT) app.broker.registerObservation(current.token, observation.observationId)
        mutableState.value = mutableState.value.copy(sessions = mutableState.value.sessions.map {
            if (it.handle == probe.handle) it.copy(nodeCount = observation.elements.size) else it
        }, message = "display/window/package 已匹配；仅本地读取 ${observation.elements.size} 个元素，未上传模型")
    }
    fun takeover(probe: ProbeSession) = work {
        val state = app.broker.requestTakeover(probe.state.token.sessionId)
        app.store.record(AgentEvent("TAKEOVER_REQUESTED", state.token.sessionId, state.token.epoch))
        refreshSession(probe)
        mutableState.value = mutableState.value.copy(message =
            if (state.owner == Owner.HUMAN) "已撤销 Agent 权限；原地人工触摸通道尚待实现"
            else "正在等待已提交输入结束；暂不允许人工输入")
    }
    fun returnControl(probe: ProbeSession) = work {
        app.broker.returnControl(app.broker.state(probe.state.token.sessionId).token)
        refreshSession(probe)
        mutableState.value = mutableState.value.copy(message = "控制权已交还；后续动作必须重新观察")
    }
    fun close(probe: ProbeSession) = work {
        val stopped = app.broker.stop(probe.state.token.sessionId)
        check(stopped.inFlight == 0) { "Wait for in-flight input before display release" }
        withContext(Dispatchers.IO) { connector.service.value?.closeDisplay(probe.handle) }
        mutableState.value = mutableState.value.copy(sessions = mutableState.value.sessions.filter { it.handle != probe.handle })
    }
    private suspend fun refreshSession(probe: ProbeSession) {
        val state = app.broker.state(probe.state.token.sessionId)
        mutableState.value = mutableState.value.copy(sessions = mutableState.value.sessions.map {
            if (it.handle == probe.handle) it.copy(state = state) else it
        })
    }
    private fun work(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true)
        viewModelScope.launch {
            try { block() } catch (failure: Exception) { showError(failure) }
            finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
    }
    private fun showError(error: Throwable) {
        // System exceptions can contain content; keep raw messages out of UI logs.
        mutableState.value = mutableState.value.copy(message =
            "操作未完成：${error.javaClass.simpleName}。检查 Shizuku / 无障碍 / App 安装与系统兼容性；未回退主屏。")
    }
    override fun onCleared() { connector.close(); super.onCleared() }
}
