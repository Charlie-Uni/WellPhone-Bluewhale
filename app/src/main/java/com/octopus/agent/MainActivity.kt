package com.octopus.agent

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octopus.core.Owner
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF255D50), background = Color(0xFFF5F3EE), surface = Color.White)) {
                Workbench(onAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            }
        }
    }
}

@Composable private fun Workbench(onAccessibility: () -> Unit, vm: WorkbenchViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var goal by remember { mutableStateOf("整理招聘标签里的面试、笔试和材料截止日期；冲突先问我") }
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
            Text("先证明后台运行，再接入自动执行。", style = MaterialTheme.typography.titleMedium)
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("平台预检", style = MaterialTheme.typography.titleMedium)
                    Text(state.message)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::connect, enabled = !state.busy) { Text("连接 Shizuku") }
                        OutlinedButton(onClick = onAccessibility) { Text("开启无障碍") }
                    }
                    Text("请使用无线调试启动的 shell UID 2000。当前不申请悬浮窗，不会自动写入邮件或日历。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(goal, { goal = it }, label = { Text("任务目标") }, modifier = Modifier.fillMaxWidth())
            Text("当前目标仅在本页编辑；模型执行入口将在隐私筛选和真机隔离通过后接通。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.create(false) }, enabled = state.connected && !state.busy &&
                    state.sessions.none { it.state.packageName == "com.google.android.gm" }) { Text("创建 A · 邮箱") }
                OutlinedButton({ vm.create(true) }, enabled = state.connected && !state.busy &&
                    state.sessions.none { it.state.packageName == "com.google.android.calendar" }) { Text("创建 B · 日历") }
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
                        Text("只读预览；当前不转发触摸", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton({ vm.launch(probe) }, enabled = !state.busy && probe.state.owner != Owner.STOPPED) { Text("打开 App") }
                            OutlinedButton({ vm.observe(probe) }, enabled = !state.busy && probe.state.owner != Owner.STOPPED) { Text("读控件") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (probe.state.owner == Owner.AGENT) {
                                Button({ vm.takeover(probe) }, enabled = !state.busy) { Text("测试撤销控制权") }
                            } else if (probe.state.owner == Owner.HUMAN) {
                                Button({ vm.returnControl(probe) }, enabled = !state.busy) { Text("交还 Agent") }
                            }
                            TextButton({ vm.close(probe) }, enabled = !state.busy) { Text("释放屏幕") }
                        }
                    }
                }
            }
            Text("真正的胶囊 / 悬浮小窗、原地触摸与邮件闭环正在后续任务中实现。这里用于收集平台证据。",
                style = MaterialTheme.typography.bodySmall)
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
