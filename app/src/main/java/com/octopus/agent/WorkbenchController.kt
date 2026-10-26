package com.octopus.agent

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import com.octopus.agent.accessibility.OctopusAccessibilityService
import com.octopus.agent.bridge.ShizukuConnector
import com.octopus.core.AgentEvent
import com.octopus.core.Owner
import com.octopus.core.SessionState
import com.octopus.core.*
import com.octopus.agent.model.*
import com.octopus.agent.storage.*
import com.octopus.agent.workflow.*
import com.octopus.agent.overlay.VirtualScreenOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.time.ZoneId
import android.os.SystemClock
import android.net.Uri

data class ProbeSession(val label: String, val handle: String, val state: SessionState,
    val flags: Int, val frame: Bitmap? = null, val nodeCount: Int? = null,
    val width: Int = 720, val height: Int = 1280, val frameAt: Long = 0)
data class WorkbenchState(
    val connected: Boolean = false, val busy: Boolean = false,
    val message: String = "连接 Shizuku 后开始平台预检",
    val sessions: List<ProbeSession> = emptyList(),
    val model: ModelSettings = ModelSettings(), val config: WorkflowConfig = WorkflowConfig("", "", "Recruitment", "", "Events"),
    val running: Boolean = false, val plan: TaskPlan? = null, val progress: WorkflowProgress? = null,
    val question: UserQuestion? = null, val results: List<ItemOutcome> = emptyList(),
    val capsule: Boolean = false, val keySaved: Boolean = false,
    val resumePending: Boolean = false,
    val pacingConservative: Boolean = true,
    val modelChecking: Boolean = false, val modelCheckMessage: String? = null,
    val adaptivePacing: Boolean = true,
)

class WorkbenchController(private val app: OctopusApplication) {
    private val application = app
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var operation: Job? = null
    private var shutdownJob: Job? = null
    private val connector = ShizukuConnector(application)
    private val preferences = WorkflowSettings(application)
    private val secrets = SecretStore(application)
    private val mutableState = MutableStateFlow(WorkbenchState(model = preferences.model(), config = preferences.config(),
        keySaved = secrets.read() != null,adaptivePacing = preferences.adaptivePacing()))
    val state = mutableState.asStateFlow()
    private var forwardingTarget: TouchTarget? = null
    private var textWork: Job? = null
    private val texts by lazy {
        HumanTextController(app.broker, object : HumanTextPort {
            override suspend fun capture(session: SessionState, draftId: String) =
                requireNotNull(OctopusAccessibilityService.instance).humanText.capture(session,draftId)
            override suspend fun replace(session: SessionState, draftId: String, fieldId: String, value: String,
                stillCurrent: () -> Boolean) = requireNotNull(OctopusAccessibilityService.instance)
                .humanText.replace(session,draftId,fieldId,value,stillCurrent)
            override fun clear(draftId: String) { OctopusAccessibilityService.instance?.humanText?.clear(draftId) }
        },preview::isTextCurrent)
    }
    private val humanWindows = mutableMapOf<String, Int>()
    private val touches: HumanTouchController = HumanTouchController(app.broker, scope, HumanTouchSink(::injectHumanTouch),
        SystemClock::uptimeMillis) { reason ->
        mutableState.value = mutableState.value.copy(message = "$reason · 触摸已停止，请检查当前预览")
        preview.showNotice("$reason · 触摸已停止，请重新检查画面")
    }
    private val preview: VirtualScreenOverlay = VirtualScreenOverlay(application, object : VirtualScreenOverlay.Callbacks {
        override fun takeover(id: String) { probe(id)?.let(this@WorkbenchController::takeover) }
        override fun handBack(id: String) { probe(id)?.let(::returnControl) }
        override fun touch(id: String, transform: PreviewTransform, effect: PreviewEffect) { previewTouch(id, transform, effect) }
        override fun cancelTouch() { forwardingTarget = null; touches.cancel() }
        override fun editText(target: TouchTarget) { openTextEditor(target) }
        override fun applyText(draft: HumanTextDraft, fieldId: String, value: String) { writeHumanText(draft,fieldId,value) }
        override fun cancelText() { texts.invalidate() }
        override fun openWorkbench() {
            application.startActivity(Intent(application, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    })
    private val capsule = StatusCapsule(application) { openPreview() }
    private var execution: Job? = null
    private var modelCheckJob: Job? = null
    private val governor = LoadGovernor()
    private var pacing = Pacing(0.5,1_000,true)
    private var workbenchVisible = false
    fun setWorkbenchVisible(visible: Boolean) { workbenchVisible = visible }
    private class RunContext(val config: WorkflowConfig, val model: ModelSettings, val mailId: String, val calendarId: String,
        val pacer: ExecutionPacer) {
        val checkpoint = WorkflowCheckpoint()
        val budget = GuiStepBudget(80)
        var pausing = false
    }
    private var activeRun: RunContext? = null
    private val reviews = UserReviews(app.broker) { question ->
        mutableState.value = mutableState.value.copy(question = question)
        if (question != null) capsule.update("等待确认 · 点击查看", mutableState.value.capsule)
    }
    init {
        scope.launch {
            connector.service.collect { service ->
                if (service == null) {
                    if(mutableState.value.sessions.isNotEmpty() || activeRun != null) stopSessions()
                    else mutableState.value = mutableState.value.copy(connected = false,
                        message = "未连接。shell 断开后旧会话停止，不自动恢复。")
                } else {
                    mutableState.value = mutableState.value.copy(connected = true,
                        message = "已连接 shell；创建虚拟屏后仍需验证主屏焦点与输入法")
                }
            }
        }
        scope.launch {
            while (true) {
                val load = OctopusAccessibilityService.instance?.mainLoad?.sample() ?: LoadSample(false,true,false)
                val next = if(mutableState.value.adaptivePacing)governor.sample(load)else Pacing(2.0,500,false)
                if (next.conservative != pacing.conservative) {
                    mutableState.value.sessions.forEach { item -> app.store.record(AgentEvent(
                        if(next.conservative)"PACING_CONSERVATIVE"else"PACING_NORMAL",item.state.token.sessionId,item.state.token.epoch)) }
                }
                pacing = next
                mutableState.value = mutableState.value.copy(pacingConservative = next.conservative)
                delay(1_000)
            }
        }
        scope.launch {
            val capturePolicies = mutableMapOf<String,Pair<Long,Boolean>>()
            val lastRequest = mutableMapOf<String,Long>()
            while (true) {
                delay(500)
                val service = connector.service.value ?: continue
                for (probe in mutableState.value.sessions) {
                    val current = app.broker.state(probe.state.token.sessionId)
                    val enabled = workbenchVisible || preview.visibleSessionId() == current.token.sessionId
                    val interval = if(current.owner == Owner.HUMAN)500L else (1_000 / pacing.observationsPerSecond).toLong()
                    val policy = interval to enabled
                    if (enabled || capturePolicies[probe.handle] != policy) {
                        val accepted = withContext(Dispatchers.IO) { runCatching { service.setCapturePolicy(probe.handle,interval,enabled) }.isSuccess }
                        if (!accepted) continue
                        capturePolicies[probe.handle] = policy
                    }
                    if (!enabled || SystemClock.elapsedRealtime() - (lastRequest[probe.handle] ?: -10_000) < interval) continue
                    lastRequest[probe.handle] = SystemClock.elapsedRealtime()
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
                        if (it.handle == probe.handle) it.copy(frame = bitmap ?: it.frame, state = settledState,
                            frameAt = if (bitmap != null) SystemClock.uptimeMillis() else it.frameAt) else it
                    })
                    publishPreview()
                }
            }
        }
    }
    fun connect() { if(shutdownJob?.isActive != true) runCatching { connector.connect() }.onFailure { showError(it) } }
    fun create(calendar: Boolean) = work {
        check(activeRun == null)
        val service = requireNotNull(connector.service.value)
        val packageName = if (calendar) "com.google.android.calendar" else "com.google.android.gm"
        check(mutableState.value.sessions.none { it.state.packageName == packageName })
        ensureForegroundSession()
        val result = try { withContext(Dispatchers.IO) { JSONObject(service.createDisplay(720, 1280, 240)) } }
        catch(failure: Exception) {
            if(mutableState.value.sessions.isEmpty()) app.stopService(Intent(app, AgentSessionService::class.java))
            throw failure
        }
        val handle = result.getString("handle")
        val session = try { app.broker.create(result.getInt("displayId"), packageName) } catch (failure: Throwable) {
            withContext(Dispatchers.IO) { service.closeDisplay(handle) }
            throw failure
        }
        mutableState.value = mutableState.value.copy(sessions = mutableState.value.sessions +
            ProbeSession(if (calendar) "B · 日历" else "A · 邮箱", handle, session, result.getInt("effectiveFlags"),
                width = result.getInt("width"), height = result.getInt("height")),
            message = "已创建 display ${session.displayId}；隔离行为待真机测试。不会自动打开 App。")
        publishPreview()
    }
    fun launch(probe: ProbeSession) = work {
        check(activeRun == null) { "Finish the current run before relaunching an App" }
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
        pauseSessions(listOf(probe))
    }
    private suspend fun pauseSessions(targets: List<ProbeSession>) {
        val run = activeRun
        run?.pausing = true
        val states = targets.map { app.broker.requestTakeover(it.state.token.sessionId, holdForWorkflow = run != null) }
        targets.forEach { refreshSession(it) }
        var bindingFailure: Exception? = null
        try {
            targets.zip(states).forEach { (probe,state) ->
                withContext(Dispatchers.IO) { connector.service.value?.bindControl(probe.handle,state.token.sessionId,state.token.epoch,false) }
                app.store.record(AgentEvent("TAKEOVER_REQUESTED",state.token.sessionId,state.token.epoch))
            }
        } catch (error: Exception) { bindingFailure = error } finally {
            execution?.cancel();reviews.cancel();execution?.join()
        }
        run?.checkpoint?.pause(WriteLedger(app.store))
        run?.checkpoint?.calendar?.takeIf { it.beforeHuman == null }?.let { point ->
            val bound = app.broker.state(run.calendarId)
            val before = requireNotNull(OctopusAccessibilityService.instance).observe(bound)
            check(before.token == bound.token && before.displayId == bound.displayId && before.packageName == bound.packageName)
            run.checkpoint.calendar = point.copy(beforeHuman = before)
        }
        bindingFailure?.let { throw it }
        run?.pausing = false
        states.filter { it.owner in setOf(Owner.HUMAN,Owner.DRAINING) }.forEach { app.broker.completeTakeover(it.token) }
        targets.forEach { refreshSession(it) }
        mutableState.value = mutableState.value.copy(resumePending = run != null,
            message = "已接管；交还全部会话后将重新观察，核对修改再继续")
        capsule.update("已暂停 · 交还后核对修改",mutableState.value.capsule)
    }
    fun returnControl(probe: ProbeSession) = work {
        preview.cancelTextInput()
        forwardingTarget = null
        touches.cancelAndJoin()
        textWork?.join()
        val returned = app.broker.returnControl(app.broker.state(probe.state.token.sessionId).token)
        withContext(Dispatchers.IO) {
            connector.service.value?.bindControl(probe.handle, returned.token.sessionId, returned.token.epoch, true)
        }
        refreshSession(probe)
        mutableState.value = mutableState.value.copy(message = "控制权已交还；后续动作必须重新观察")
        val run = activeRun
        if (run != null && !run.pausing && listOf(run.mailId,run.calendarId).all { id ->
                app.broker.state(id).owner == Owner.AGENT }) execute(run, resumed = true)
    }
    fun close(probe: ProbeSession) = work {
        check(activeRun == null) { "Finish the current run before closing a display" }
        val stopped = app.broker.stop(probe.state.token.sessionId)
        check(stopped.inFlight == 0) { "Wait for in-flight input before display release" }
        withContext(Dispatchers.IO) { connector.service.value?.closeDisplay(probe.handle) }
        mutableState.value = mutableState.value.copy(sessions = mutableState.value.sessions.filter { it.handle != probe.handle })
        publishPreview()
        if(mutableState.value.sessions.isEmpty()) app.stopService(Intent(app, AgentSessionService::class.java))
    }
    private suspend fun refreshSession(probe: ProbeSession) {
        val state = app.broker.state(probe.state.token.sessionId)
        mutableState.value = mutableState.value.copy(sessions = mutableState.value.sessions.map {
            if (it.handle == probe.handle) it.copy(state = state) else it
        })
        publishPreview()
    }
    private fun probe(id: String) = mutableState.value.sessions.firstOrNull { it.state.token.sessionId == id }
    private fun publishPreview() {
        preview.update(mutableState.value.sessions.map {
            VirtualScreenOverlay.Item(it.state.token.sessionId, it.label, it.state, it.frame, it.frameAt, it.width, it.height)
        })
    }
    fun openPreview(id: String? = null) {
        publishPreview()
        if (!preview.show(id)) mutableState.value = mutableState.value.copy(message = "请先授予悬浮权限，再点击胶囊或打开小窗")
    }
    private fun openTextEditor(target: TouchTarget) {
        // Reset already revoked the previous draft. Do not overlap a settling text action.
        val previous = textWork
        textWork = scope.launch {
            try {
                previous?.join()
                touches.cancelAndJoin()
                if(!preview.isTextCurrent(target)) return@launch
                preview.showTextEditor(texts.open(target))
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { preview.finishTextEdit(target,"未读取到可编辑字段，请先打开目标编辑页面并重试") }
        }
    }
    private fun writeHumanText(draft: HumanTextDraft, fieldId: String, value: String) {
        val previous = textWork
        textWork = scope.launch {
            try {
                previous?.join()
                val result=texts.apply(draft,fieldId,value)
                app.store.record(AgentEvent("HUMAN_TEXT_${result.name}",draft.target.token.sessionId,
                    draft.target.token.epoch,displayId=draft.target.displayId,packageName=draft.target.packageName))
                preview.finishTextEdit(draft.target,if(result==TextWriteResult.VERIFIED)
                    "文字已写回并读回核对；请自行保存/发送，再交还 Agent"
                    else "写回结果未知，未自动重试；请检查虚拟屏中的内容")
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { preview.finishTextEdit(draft.target,"无法确认写回结果，请检查当前页面后重新选择") }
        }
    }
    private fun previewTouch(id: String, transform: PreviewTransform, effect: PreviewEffect) {
        val probe = probe(id) ?: return
        val target = TouchTarget(probe.state.token, probe.state.displayId, probe.state.packageName,
            transform.version, transform.displayWidth, transform.displayHeight)
        if (effect == PreviewEffect.Cancel) { forwardingTarget = null; touches.cancel(); return }
        if (!preview.isCurrent(target)) { forwardingTarget = null; touches.cancel(); return }
        fun map(point: TouchPoint) = transform.map(point)
        when (effect) {
            is PreviewEffect.Tap -> map(effect.point)?.let {
                if (touches.begin(target, it)) touches.end(it)
            }
            is PreviewEffect.Begin -> map(effect.point)?.let {
                forwardingTarget = if (touches.begin(target, it)) target else null
            }
            is PreviewEffect.Move -> if (forwardingTarget == target) map(effect.point)?.let(touches::move)
            is PreviewEffect.End -> if (forwardingTarget == target) {
                map(effect.point)?.let(touches::end); forwardingTarget = null
            }
            else -> Unit
        }
    }
    private suspend fun injectHumanTouch(target: TouchTarget, packet: TouchPacket): Boolean {
        val probe = probe(target.token.sessionId) ?: return false
        val service = connector.service.value ?: return false
        if (packet.phase != TouchPhase.CANCEL) {
            val authority = app.broker.state(target.token.sessionId)
            check(authority.token == target.token && authority.owner == Owner.HUMAN && preview.isCurrent(target))
            val window = requireNotNull(OctopusAccessibilityService.instance).touchWindowId(authority, packet.point)
            if (packet.phase == TouchPhase.DOWN) humanWindows[packet.gestureId] = window
            check(humanWindows[packet.gestureId] == window)
        }
        return try {
            withContext(NonCancellable + Dispatchers.IO) {
                val binding = Json.encodeToString(target)
                if (packet.phase == TouchPhase.DOWN) service.bindHuman(probe.handle, binding)
                service.injectTouch(probe.handle, binding, Json.encodeToString(packet))
            }.also { accepted ->
                if (accepted && packet.phase in setOf(TouchPhase.DOWN, TouchPhase.UP, TouchPhase.CANCEL)) {
                    app.store.record(AgentEvent("HUMAN_${packet.phase}", target.token.sessionId, target.token.epoch,
                        packet.gestureId, displayId = target.displayId, packageName = target.packageName))
                }
            }
        } finally {
            if (packet.phase == TouchPhase.UP || packet.phase == TouchPhase.CANCEL) humanWindows.remove(packet.gestureId)
        }
    }
    private fun work(block: suspend () -> Unit) {
        if (mutableState.value.busy || shutdownJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(busy = true)
        operation = scope.launch {
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { showError(failure) }
            finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
    }
    private fun showError(error: Throwable) {
        // System exceptions can contain content; keep raw messages out of UI logs.
        mutableState.value = mutableState.value.copy(message =
            "操作未完成：${error.javaClass.simpleName}。检查 Shizuku / 无障碍 / App 安装与系统兼容性；未回退主屏。")
    }
    fun configure(model: ModelSettings, config: WorkflowConfig, apiKey: String) {
        if (activeRun != null || mutableState.value.modelChecking) return
        runCatching {
            val endpoint = java.net.URL(model.endpoint)
            require(endpoint.protocol == "https" && endpoint.host.isNotBlank() && endpoint.userInfo == null && endpoint.query == null && endpoint.ref == null)
            require(model.model.isNotBlank())
            if (apiKey.isNotBlank()) secrets.save(apiKey.trim())
            preferences.save(model, config)
            mutableState.value = mutableState.value.copy(model = model, config = config,
                keySaved = secrets.read() != null, message = "配置已保存在手机；每轮需要重新勾选读取范围授权")
        }.onFailure(::showError)
    }
    fun setAdaptivePacing(enabled: Boolean) {
        if(activeRun != null || mutableState.value.modelChecking) return
        preferences.setAdaptivePacing(enabled)
        governor.reset()
        pacing = if(enabled)Pacing(0.5,1_000,true)else Pacing(2.0,500,false)
        mutableState.value = mutableState.value.copy(adaptivePacing = enabled,pacingConservative = pacing.conservative)
    }
    fun testModelConnection() {
        if (activeRun != null || mutableState.value.modelChecking) return
        val model = mutableState.value.model
        mutableState.value = mutableState.value.copy(modelChecking = true,modelCheckMessage = "正在测试模型连接，仅发送合成测试任务")
        modelCheckJob = scope.launch {
            try {
                val transport = ChatTransport(model.endpoint,model.model,apiKey = { requireNotNull(secrets.read()) })
                Coordinator(HttpWorkflowModel(transport),app.broker).start("连接测试：规划整理合成招聘邮件到日历，不操作任何 App。")
                mutableState.value = mutableState.value.copy(modelCheckMessage = "模型连接与计划格式通过；尚未验证真实 App 任务。")
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(modelCheckMessage = "模型测试已取消。")
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(modelCheckMessage = "模型测试未通过：${(error as? ModelFailure)?.code ?: "MODEL_CHECK_FAILED"}。请检查地址、模型、密钥与 JSON 格式支持。")
            } finally { modelCheckJob = null;mutableState.value = mutableState.value.copy(modelChecking = false) }
        }
    }
    fun cancelModelCheck() { modelCheckJob?.cancel() }
    fun exportJournal(uri: Uri) = work {
        require(uri.scheme == "content")
        withContext(Dispatchers.IO) {
            val snapshot = app.store.journalSnapshot()
            requireNotNull(app.contentResolver.openOutputStream(uri,"wt")).use { it.write(snapshot) }
        }
        mutableState.value = mutableState.value.copy(message = "已导出脱敏评估日志；动作回执与已核实结果分开记录。")
    }
    fun setCapsule(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(capsule = enabled)
        capsule.update(mutableState.value.progress?.detail ?: "八爪鱼已就绪", enabled)
    }
    fun answer(question: UserQuestion, option: Int, fields: Map<String, String>) {
        scope.launch { reviews.answer(question.id, UserAnswer(option, fields)) }
    }
    fun start(config: WorkflowConfig) {
        if (activeRun != null || mutableState.value.running || mutableState.value.modelChecking || shutdownJob?.isActive == true) return
        val mail = mutableState.value.sessions.singleOrNull { it.state.packageName == "com.google.android.gm" } ?: return
        val calendar = mutableState.value.sessions.singleOrNull { it.state.packageName == "com.google.android.calendar" } ?: return
        val run = RunContext(config, mutableState.value.model, mail.state.token.sessionId, calendar.state.token.sessionId,
            AdaptivePacer({pacing},SystemClock::elapsedRealtime))
        activeRun = run
        execute(run, resumed = false)
    }
    private fun execute(run: RunContext, resumed: Boolean) {
        if (activeRun !== run || execution?.isActive == true) return
        val config = run.config
        execution = scope.launch {
            mutableState.value = mutableState.value.copy(running = true, resumePending = false, question = null,
                results = if (resumed) run.checkpoint.outcomes.values.toList() else emptyList(),
                config = config, progress = if (resumed) WorkflowProgress("RECONCILING", "正在重新观察当前页面")
                    else WorkflowProgress("PLANNING", "正在生成执行计划"))
            try {
                config.validate()
                check(app.sessionServiceState.value == SessionServiceState.RUNNING) { "Background session is unavailable" }
                val service = requireNotNull(connector.service.value)
                val observer = requireNotNull(OctopusAccessibilityService.instance)
                val probes = mutableState.value.sessions
                val mail = probes.single { it.state.token.sessionId == run.mailId }
                val calendar = probes.single { it.state.token.sessionId == run.calendarId }
                val mailState = app.broker.state(mail.state.token.sessionId)
                val calendarState = app.broker.state(calendar.state.token.sessionId)
                require(mailState.owner == Owner.AGENT && calendarState.owner == Owner.AGENT)
                for (bound in listOf(mailState, calendarState)) {
                    app.store.record(AgentEvent("SESSION_BOUND", bound.token.sessionId, bound.token.epoch,
                        displayId = bound.displayId, packageName = bound.packageName))
                }
                app.store.record(AgentEvent(if(resumed)"RUN_RESUMED"else"RUN_STARTED",mailState.token.sessionId,mailState.token.epoch))
                val model = run.model
                require(secrets.read()?.isNotBlank() == true)
                val transport = ChatTransport(model.endpoint, model.model, apiKey = { requireNotNull(secrets.read()) }, metric = {
                    app.store.record(AgentEvent("MODEL_${it.status}",mailState.token.sessionId,mailState.token.epoch,elapsedMillis = it.elapsedMillis))
                })
                val workflowModel = HttpWorkflowModel(transport)
                if (!resumed) {
                    val plan = Coordinator(workflowModel, app.broker).start(config.goal).plan
                    mutableState.value = mutableState.value.copy(plan = plan)
                    capsule.update(plan.summary, mutableState.value.capsule)
                }
                withContext(Dispatchers.IO) {
                    for (probe in listOf(mail, calendar)) {
                        val token = if (probe.handle == mail.handle) mailState.token else calendarState.token
                        service.bindControl(probe.handle, token.sessionId, token.epoch, true)
                        if (!resumed) service.launch(probe.handle, probe.state.packageName)
                    }
                }
                if (!resumed) delay(500)
                val navigator = GuiNavigator(app.broker, observer, observer, HttpDecisionModel(transport),
                    SystemClock::elapsedRealtime, budget = run.budget, journal = app.store, pacer = run.pacer)
                if (resumed) {
                    val mailScene = navigator.observe(mailState.token)
                    val calendarScene = navigator.observe(calendarState.token)
                    listOf(mailState,calendarState).forEach { state -> app.store.record(AgentEvent("RESUME_OBSERVED",
                        state.token.sessionId,state.token.epoch,displayId = state.displayId,packageName = state.packageName)) }
                    ResumeEvidence.checkScenes(mailScene,calendarScene,run.checkpoint.calendar,run.checkpoint.documents == null)
                }
                val workflow = MailCalendarWorkflow(
                    GmailGuiPort(navigator, mailState.token, reviews), workflowModel,
                    CalendarGuiPort(navigator, app.broker, calendarState.token, calendar.handle, service,
                        com.octopus.agent.workflow.CalendarConflictReader(app)::inspect),
                    WorkflowReviews(reviews, calendarState.token, ZoneId.of(config.localZone)),
                    app.store, WriteLedger(app.store), writer = app.calendarWriter, checkpoint = run.checkpoint,
                    onOutcome = { outcome ->
                        app.store.record(AgentEvent("RESULT_${outcome.result}",calendarState.token.sessionId,calendarState.token.epoch,actionId = outcome.key))
                        if (activeRun === run) mutableState.value = mutableState.value.copy(results = mutableState.value.results
                            .filter { it.key != outcome.key } + outcome)
                    },
                ) { progress ->
                    app.store.record(AgentEvent("WORKFLOW_${progress.stage}",calendarState.token.sessionId,calendarState.token.epoch))
                    if (activeRun === run) {
                        mutableState.value = mutableState.value.copy(progress = progress)
                        capsule.update(progress.detail, mutableState.value.capsule)
                    }
                }
                val report = workflow.run(config)
                app.store.record(AgentEvent("RUN_FINISHED",mailState.token.sessionId,mailState.token.epoch))
                mutableState.value = mutableState.value.copy(results = report.outcomes,
                    message = "流程结束：已核实 ${report.verified} 项。未核实、等待与未知分别保留。")
            } catch (cancelled: CancellationException) {
                if (activeRun === run) mutableState.value = mutableState.value.copy(message = "任务已暂停；交还后重新观察并核对修改")
                throw cancelled
            } catch (failure: Exception) {
                if (activeRun !== run) return@launch
                if (failure is GuiNeedsUser && failure.reasonCode != "MAIL_ACCOUNT_NOT_CONFIRMED" && !run.pausing) {
                    run.checkpoint.pause(WriteLedger(app.store))
                }
                val reason = (failure as? GuiNeedsUser)?.reasonCode ?: failure.javaClass.simpleName
                if (run.checkpoint.needsResume) {
                    val message = if (reason == "RESUME_REMINDER_NOT_BEFORE_DEADLINE")
                        "提醒必须早于截止时间。当前页面和新设置已保留，请接管调整提醒或截止时间，再交还；也可结束本轮。"
                    else "恢复已暂停，当前页面和修改已保留。请接管后核对/保存，再交还；也可结束本轮。未核实的写入不计完成。"
                    mutableState.value = mutableState.value.copy(message = message,progress = WorkflowProgress("RESUME_BLOCKED",message))
                    preview.showNotice(message)
                    capsule.update("恢复需处理 · 点击查看",mutableState.value.capsule)
                    val current = app.broker.state(run.calendarId)
                    app.store.record(AgentEvent("RESUME_BLOCKED",current.token.sessionId,current.token.epoch,
                        displayId = current.displayId,packageName = current.packageName))
                } else {
                    mutableState.value = mutableState.value.copy(message = "流程停止：$reason。未核实的写入不计为完成。")
                    capsule.update("需要查看 · $reason", mutableState.value.capsule)
                }
            } finally {
                if (activeRun === run) {
                    val keep = run.pausing || run.checkpoint.needsResume
                    if (!keep) activeRun = null
                    mutableState.value = mutableState.value.copy(running = false, resumePending = keep, question = null,
                        config = config.copy(scopeAuthorized = false))
                }
            }
        }
    }
    fun pauseWorkflow() = work {
        forwardingTarget = null; touches.cancel()
        pauseSessions(mutableState.value.sessions)
    }
    fun cancelWorkflow() = work {
        try { pauseSessions(mutableState.value.sessions) } finally {
            activeRun = null
            execution = null
            mutableState.value = mutableState.value.copy(running = false,resumePending = false,question = null,
                config = mutableState.value.config.copy(scopeAuthorized = false),message = "本轮已结束；重新开始需要重新授权，人工持有/未知写入仍先查询")
        }
    }
    private suspend fun ensureForegroundSession() {
        check(workbenchVisible) { "Create sessions from the visible workbench" }
        if(app.sessionServiceState.value == SessionServiceState.RUNNING) return
        app.sessionServiceState.value = SessionServiceState.STARTING
        try {
            app.startForegroundService(Intent(app, AgentSessionService::class.java))
            val ready = withTimeout(5_000) { app.sessionServiceState.first {
                it == SessionServiceState.RUNNING || it == SessionServiceState.FAILED
            } }
            check(ready == SessionServiceState.RUNNING)
        } catch(failure: Exception) {
            app.stopService(Intent(app, AgentSessionService::class.java))
            throw failure
        }
    }

    /** Revocation precedes cleanup; no work resumes after a service/process restart. */
    fun stopSessions() {
        if(shutdownJob?.isActive == true) return
        shutdownJob = scope.launch {
            mutableState.value = mutableState.value.copy(busy = true)
            val run = activeRun
            activeRun = null
            preview.hide();capsule.close();forwardingTarget = null
            reviews.cancel();execution?.cancel();operation?.cancel();textWork?.cancel();modelCheckJob?.cancel()
            val sessions = mutableState.value.sessions
            try {
                sessions.forEach {
                    val stopped = app.broker.stop(it.state.token.sessionId)
                    app.store.record(AgentEvent("SESSION_STOP_REQUESTED",stopped.token.sessionId,stopped.token.epoch))
                }
                touches.cancelAndJoin()
                operation?.join();textWork?.join();execution?.join();modelCheckJob?.join()
                run?.checkpoint?.pause(WriteLedger(app.store))
            } catch (_: Exception) {
                // Already submitted writes may be unknown; release the shell even if local cleanup failed.
            } finally {
                connector.disconnect()
                execution = null
                mutableState.value = mutableState.value.copy(connected = false,busy = false,sessions = emptyList(),
                    running = false,resumePending = false,question = null,capsule = false,
                    config = mutableState.value.config.copy(scopeAuthorized = false),
                    message = "后台会话已停止；未知写入先查询，重新连接后需要重新授权。")
                publishPreview()
                app.stopService(Intent(app, AgentSessionService::class.java))
            }
        }
    }
}
