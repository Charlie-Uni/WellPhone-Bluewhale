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
import com.octopus.core.*
import com.octopus.agent.model.*
import com.octopus.agent.storage.*
import com.octopus.agent.workflow.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZoneId
import android.os.SystemClock

data class ProbeSession(val label: String, val handle: String, val state: SessionState,
    val flags: Int, val frame: Bitmap? = null, val nodeCount: Int? = null)
data class WorkbenchState(
    val connected: Boolean = false, val busy: Boolean = false,
    val message: String = "连接 Shizuku 后开始平台预检",
    val sessions: List<ProbeSession> = emptyList(),
    val model: ModelSettings = ModelSettings(), val config: WorkflowConfig = WorkflowConfig("", "", "Recruitment", "", "Events"),
    val running: Boolean = false, val plan: TaskPlan? = null, val progress: WorkflowProgress? = null,
    val question: UserQuestion? = null, val results: List<ItemOutcome> = emptyList(),
    val capsule: Boolean = false, val keySaved: Boolean = false,
)

class WorkbenchViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OctopusApplication
    private val connector = ShizukuConnector(application)
    private val preferences = WorkflowSettings(application)
    private val secrets = SecretStore(application)
    private val capsule = StatusCapsule(application)
    private val mutableState = MutableStateFlow(WorkbenchState(model = preferences.model(), config = preferences.config(),
        keySaved = secrets.read() != null))
    val state = mutableState.asStateFlow()
    private var execution: Job? = null
    private val reviews = UserReviews(app.broker) { question ->
        mutableState.value = mutableState.value.copy(question = question)
        if (question != null) capsule.update("等待确认 · 点击查看", mutableState.value.capsule)
    }
    init {
        viewModelScope.launch {
            connector.service.collect { service ->
                if (service == null) {
                    execution?.cancel()
                    reviews.cancel()
                    mutableState.value.sessions.forEach { app.broker.stop(it.state.token.sessionId) }
                    mutableState.value = mutableState.value.copy(connected = false, sessions = emptyList(), running = false,
                        message = "未连接。shell 断开后旧会话停止，不自动恢复。")
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
                    val settledState = app.broker.state(probe.state.token.sessionId)
                    // Avoid publishing an old frame after the session has been removed.
                    mutableState.value = mutableState.value.copy(sessions = mutableState.value.sessions.map {
                        if (it.handle == probe.handle) it.copy(frame = bitmap ?: it.frame, state = settledState) else it
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
        withContext(Dispatchers.IO) {
            connector.service.value?.bindControl(probe.handle, state.token.sessionId, state.token.epoch, false)
        }
        app.store.record(AgentEvent("TAKEOVER_REQUESTED", state.token.sessionId, state.token.epoch))
        refreshSession(probe)
        mutableState.value = mutableState.value.copy(message =
            if (state.owner == Owner.HUMAN) "已撤销 Agent 权限；原地人工触摸通道尚待实现"
            else "正在等待已提交输入结束；暂不允许人工输入")
    }
    fun returnControl(probe: ProbeSession) = work {
        val returned = app.broker.returnControl(app.broker.state(probe.state.token.sessionId).token)
        withContext(Dispatchers.IO) {
            connector.service.value?.bindControl(probe.handle, returned.token.sessionId, returned.token.epoch, true)
        }
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
    fun configure(model: ModelSettings, config: WorkflowConfig, apiKey: String) {
        if (mutableState.value.running) return
        runCatching {
            val endpoint = java.net.URL(model.endpoint)
            require(endpoint.protocol == "https" && endpoint.userInfo == null && endpoint.query == null)
            require(model.model.isNotBlank())
            if (apiKey.isNotBlank()) secrets.save(apiKey.trim())
            preferences.save(model, config)
            mutableState.value = mutableState.value.copy(model = model, config = config,
                keySaved = secrets.read() != null, message = "配置已保存在手机；每轮需要重新勾选读取范围授权")
        }.onFailure(::showError)
    }
    fun setCapsule(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(capsule = enabled)
        capsule.update(mutableState.value.progress?.detail ?: "八爪鱼已就绪", enabled)
    }
    fun answer(question: UserQuestion, option: Int, fields: Map<String, String>) {
        viewModelScope.launch { reviews.answer(question.id, UserAnswer(option, fields)) }
    }
    fun start(config: WorkflowConfig) {
        if (mutableState.value.running) return
        execution = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(running = true, question = null, results = emptyList(),
                config = config, progress = WorkflowProgress("PLANNING", "正在生成执行计划"))
            try {
                config.validate()
                val service = requireNotNull(connector.service.value)
                val observer = requireNotNull(OctopusAccessibilityService.instance)
                val probes = mutableState.value.sessions
                val mail = probes.single { it.state.packageName == "com.google.android.gm" }
                val calendar = probes.single { it.state.packageName == "com.google.android.calendar" }
                val mailState = app.broker.state(mail.state.token.sessionId)
                val calendarState = app.broker.state(calendar.state.token.sessionId)
                require(mailState.owner == Owner.AGENT && calendarState.owner == Owner.AGENT)
                for (bound in listOf(mailState, calendarState)) {
                    app.store.record(AgentEvent("SESSION_BOUND", bound.token.sessionId, bound.token.epoch,
                        displayId = bound.displayId, packageName = bound.packageName))
                }
                val model = mutableState.value.model
                require(secrets.read()?.isNotBlank() == true)
                val transport = ChatTransport(model.endpoint, model.model) { requireNotNull(secrets.read()) }
                val workflowModel = HttpWorkflowModel(transport)
                val plan = Coordinator(workflowModel, app.broker).start(config.goal).plan
                mutableState.value = mutableState.value.copy(plan = plan)
                capsule.update(plan.summary, mutableState.value.capsule)
                withContext(Dispatchers.IO) {
                    for (probe in listOf(mail, calendar)) {
                        val token = if (probe.handle == mail.handle) mailState.token else calendarState.token
                        service.bindControl(probe.handle, token.sessionId, token.epoch, true)
                        service.launch(probe.handle, probe.state.packageName)
                    }
                }
                delay(500)
                val navigator = GuiNavigator(app.broker, observer, observer, HttpDecisionModel(transport),
                    SystemClock::elapsedRealtime, budget = GuiStepBudget(80), journal = app.store)
                val workflow = MailCalendarWorkflow(
                    GmailGuiPort(navigator, mailState.token, reviews), workflowModel,
                    CalendarGuiPort(navigator, app.broker, calendarState.token, calendar.handle, service),
                    WorkflowReviews(reviews, calendarState.token, ZoneId.of(config.localZone)),
                    app.store, WriteLedger(app.store), writer = app.calendarWriter,
                    onOutcome = { outcome ->
                        mutableState.value = mutableState.value.copy(results = mutableState.value.results
                            .filter { it.key != outcome.key } + outcome)
                    },
                ) { progress ->
                    mutableState.value = mutableState.value.copy(progress = progress)
                    capsule.update(progress.detail, mutableState.value.capsule)
                }
                val report = workflow.run(config)
                mutableState.value = mutableState.value.copy(results = report.outcomes,
                    message = "流程结束：已核实 ${report.verified} 项。未核实、等待与未知分别保留。")
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(message = "任务已暂停；重新开始会先检查持久化记录")
                throw cancelled
            } catch (failure: Exception) {
                val reason = (failure as? GuiNeedsUser)?.reasonCode ?: failure.javaClass.simpleName
                mutableState.value = mutableState.value.copy(message = "流程停止：$reason。未核实的写入不计为完成。")
                capsule.update("需要查看 · $reason", mutableState.value.capsule)
            } finally {
                mutableState.value = mutableState.value.copy(running = false, question = null,
                    config = config.copy(scopeAuthorized = false))
            }
        }
    }
    fun pauseWorkflow() {
        execution?.cancel(); reviews.cancel()
        viewModelScope.launch {
            for (probe in mutableState.value.sessions) {
                val state = app.broker.requestTakeover(probe.state.token.sessionId)
                withContext(Dispatchers.IO) {
                    connector.service.value?.bindControl(probe.handle, state.token.sessionId, state.token.epoch, false)
                }
                refreshSession(probe)
            }
            capsule.update("已暂停 · 显式交还后可继续", mutableState.value.capsule)
        }
    }
    override fun onCleared() { execution?.cancel(); reviews.cancel(); capsule.close(); connector.close(); super.onCleared() }
}
