package com.octopus.agent

import android.content.Intent
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.octopus.agent.workflow.UserQuestion
import com.octopus.agent.storage.ModelSettings
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octopus.core.Owner
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF255D50), background = Color(0xFFF5F3EE), surface = Color.White)) {
                Workbench(onCalendarRead = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) },
                    onAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onOverlay = { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"))) })
            }
        }
    }
}

@Composable private fun Workbench(onCalendarRead: () -> Unit, onAccessibility: () -> Unit, onOverlay: () -> Unit, vm: WorkbenchViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var config by remember { mutableStateOf(state.config) }
    var endpoint by remember { mutableStateOf(state.model.endpoint) }
    var model by remember { mutableStateOf(state.model.model) }
    var apiKey by remember { mutableStateOf("") }
    var authorized by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OctopusMark()
                Column {
                    Text("八爪鱼", style = MaterialTheme.typography.headlineMedium)
                    Text("邮件 → 日程 · 开发工作台", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("招聘邮件，整理成可核实的日程。", style = MaterialTheme.typography.titleMedium)
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("平台预检", style = MaterialTheme.typography.titleMedium)
                    Text(state.message)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::connect, enabled = !state.busy) { Text("连接 Shizuku") }
                        OutlinedButton(onClick = onAccessibility) { Text("开启无障碍") }
                    }
                    Text("使用无线调试启动的 Shizuku。先创建 A/B 并准备已登录的邮箱与日历；设备兼容性尚未真机验证。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onCalendarRead) { Text("授权只读日历冲突检查") }
                    Text("接管改时间后读取本机已同步日程，保留未保存页面；只检查本轮日历账号，不读取到模型。未授权则请你人工确认。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("模型与读取范围", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS Chat Completions 完整地址") },
                        enabled = !state.running && !state.resumePending, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(model, { model = it }, label = { Text("模型名称") }, enabled = !state.running && !state.resumePending)
                    OutlinedTextField(apiKey, { apiKey = it }, label = { Text(if (state.keySaved) "更新 API Key（留空保留）" else "API Key") },
                        visualTransformation = PasswordVisualTransformation(), enabled = !state.running && !state.resumePending)
                    OutlinedTextField(config.mailAccount, { config = config.copy(mailAccount = it) },
                        label = { Text("邮箱账号") }, enabled = !state.running && !state.resumePending)
                    OutlinedTextField(config.recruitmentLabel, { config = config.copy(recruitmentLabel = it) },
                        label = { Text("Gmail 招聘标签名") }, enabled = !state.running && !state.resumePending)
                    OutlinedTextField(config.calendarAccount, { config = config.copy(calendarAccount = it) },
                        label = { Text("日历账号") }, enabled = !state.running && !state.resumePending)
                    OutlinedTextField(config.calendarName, { config = config.copy(calendarName = it) },
                        label = { Text("目标日历名称（与界面一致）") }, enabled = !state.running && !state.resumePending)
                    OutlinedTextField(config.taskListName, { config = config.copy(taskListName = it) },
                        label = { Text("目标任务列表名称（与界面一致）") }, enabled = !state.running && !state.resumePending)
                    OutlinedTextField(config.goal, { config = config.copy(goal = it) },
                        label = { Text("自然语言任务") }, enabled = !state.running && !state.resumePending, modifier = Modifier.fillMaxWidth())
                    Button({
                        vm.configure(ModelSettings(endpoint, model), config, apiKey); apiKey = ""
                    }, enabled = !state.running && !state.resumePending) { Text("保存配置到手机") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(authorized, { authorized = it }, enabled = !state.running && !state.resumePending)
                        Text("本轮允许读取该标签中的招聘邮件，并按确认结果创建日程或任务。", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({
                            vm.start(config.copy(scopeAuthorized = authorized)); authorized = false
                        }, enabled = !state.running && !state.resumePending && state.connected && state.sessions.size == 2 &&
                            authorized && state.keySaved && endpoint == state.model.endpoint && model == state.model.model) {
                            Text("开始整理")
                        }
                        if (state.running) OutlinedButton(vm::pauseWorkflow) { Text("暂停任务") }
                        if (state.running || state.resumePending) OutlinedButton(vm::cancelWorkflow, enabled = !state.busy) { Text("结束本轮") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(state.capsule, vm::setCapsule)
                        Text("显示小状态胶囊")
                        TextButton(onOverlay) { Text("授予悬浮权限") }
                    }
                    OutlinedButton({ vm.openPreview() }) { Text("打开悬浮小窗") }
                    Text("点击胶囊打开小窗，默认只读；明确接管后可单指操作，双击画面放大。确认/设置由小窗按钮打开。",
                        style = MaterialTheme.typography.bodySmall)
                    Text("模型接收经过筛选的文字控件；当前不上传原始截图。",
                        style = MaterialTheme.typography.bodySmall)
                    Text("未指定提醒时，确定时间的事件默认提前 15 分钟提醒；截止型事项需明确提醒时间。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            state.plan?.let { plan ->
                Text(plan.summary, style = MaterialTheme.typography.titleMedium)
                plan.steps.forEach { Text("· $it") }
            }
            state.progress?.let { Text(it.detail, style = MaterialTheme.typography.bodyMedium) }
            state.question?.let { question -> QuestionCard(question) { option, fields -> vm.answer(question, option, fields) } }
            state.results.forEach { result -> Text("${result.result} · ${result.boundary}") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.create(false) }, enabled = state.connected && !state.busy &&
                    !state.running && !state.resumePending && state.sessions.none { it.state.packageName == "com.google.android.gm" }) { Text("创建 A · 邮箱") }
                OutlinedButton({ vm.create(true) }, enabled = state.connected && !state.busy &&
                    !state.running && !state.resumePending && state.sessions.none { it.state.packageName == "com.google.android.calendar" }) { Text("创建 B · 日历") }
            }
            if (state.sessions.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(180.dp).background(Color.White, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center) { Text("尚无虚拟屏 · 此处将显示真实画面") }
            }
            state.sessions.forEach { probe ->
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(probe.label, style = MaterialTheme.typography.titleMedium)
                        Text("display ${probe.state.displayId} · E ${probe.state.token.epoch} · ${probe.state.owner}")
                        Text("flags 0x${probe.flags.toString(16)} · 控件 ${probe.nodeCount ?: "待读取"}",
                            style = MaterialTheme.typography.bodySmall)
                        val frame = probe.frame
                        if (frame == null) Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                            Text("等待虚拟屏帧")
                        } else Image(frame.asImageBitmap(), "${probe.label}真实预览",
                            Modifier.fillMaxWidth().height(320.dp), contentScale = ContentScale.Fit)
                        Text("工作台预览只读；悬浮小窗支持接管后的单指触摸", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton({ vm.openPreview(probe.state.token.sessionId) }) { Text("在小窗查看此任务") }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton({ vm.launch(probe) }, enabled = !state.running && !state.resumePending && !state.busy && probe.state.owner != Owner.STOPPED) { Text("打开 App") }
                            OutlinedButton({ vm.observe(probe) }, enabled = !state.running && !state.resumePending && !state.busy && probe.state.owner != Owner.STOPPED) { Text("读控件") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (probe.state.owner == Owner.AGENT) {
                                Button({ vm.takeover(probe) }, enabled = !state.busy) { Text("原地接管") }
                            } else if (probe.state.owner == Owner.HUMAN) {
                                Button({ vm.returnControl(probe) }, enabled = !state.busy) { Text("交还 Agent") }
                            }
                            TextButton({ vm.close(probe) }, enabled = !state.running && !state.resumePending && !state.busy) { Text("释放屏幕") }
                        }
                    }
                }
            }
            Text("隐藏保留接管；交还全部会话后重新观察，保留可核对的修改再继续。模糊页面需人工处理。虚拟屏输入法保持隐藏，文字使用本地面板；双指不转发。设备兼容性待真机验证。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun QuestionCard(question: UserQuestion, submit: (Int, Map<String, String>) -> Unit) {
    var values by remember(question.id) { mutableStateOf(question.fields.associate { it.key to it.value }) }
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4DE))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(question.title, style = MaterialTheme.typography.titleMedium)
            Text(question.detail)
            question.fields.forEach { field ->
                OutlinedTextField(values[field.key].orEmpty(), { value -> values = values + (field.key to value) },
                    label = { Text(field.label) }, modifier = Modifier.fillMaxWidth())
            }
            question.options.forEachIndexed { index, option ->
                OutlinedButton({ submit(index, values) }, modifier = Modifier.fillMaxWidth()) { Text(option) }
            }
        }
    }
}

@Composable private fun OctopusMark() {
    Canvas(Modifier.size(42.dp)) {
        val radius = size.minDimension * 0.38f
        repeat(8) { arm ->
            val angle = arm * Math.PI / 4
            val start = Offset(center.x + cos(angle).toFloat() * radius * .25f,
                center.y + sin(angle).toFloat() * radius * .25f)
            val end = Offset(center.x + cos(angle + .15).toFloat() * radius,
                center.y + sin(angle + .15).toFloat() * radius)
            drawLine(Color(0xFF202C29), start, end, strokeWidth = radius * .3f, cap = StrokeCap.Round)
        }
    }
}
