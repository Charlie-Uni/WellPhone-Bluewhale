# 本地检查记录

2026-10-25（悉尼）：**112 项测试通过（85 项 core + 27 项 Android 层）；APK 构建、签名与 lint 检查通过。** 机器结果见 [local-checks.json](local-checks.json)。

## 实际结果与范围

| 检查 | 结果 |
| --- | --- |
| core 行为测试 | 85 项，0 失败、0 错误、0 跳过；含 18 项触摸逻辑 |
| Android 窗口/事件 | 19 项，SDK 35；生产 View、MotionEvent、Handler、权限/配置变化和本地绘制 |
| Android 输入适配器 | 4 个用例 × SDK 34/35 = 8 项；反射与参数契约 |
| 合成画面 | compact.png / expanded.png；中心像素检查，并查看按钮、黑边与布局 |
| 邮件→日历 | 模拟目标中 2 事件、1 任务；冲突、去重与回执丢失恢复通过 |
| Android 构建/签名 | assembleDebug 与 apksigner verify 通过，minSdk 34 / targetSdk 35 |
| lint | 0 错误 / 14 条提示 |
| 真机 / 模拟器 / 真实模型 | 未运行；真机按用户要求暂缓 |

## 测试分层

| 套件 | 项数 | 覆盖 |
| --- | --- | --- |
| RulesTest | 23 | 时区、权限代次、排空、幂等、隐私与负载策略 |
| AgentTest | 5 | 单步循环、搜索与停止边界 |
| CoordinatorTest | 3 | 计划、确认与汇总 |
| WorkflowTest | 16 | 生产工作流 + 模拟邮件/日历/模型 |
| GuiEvidenceTest | 16 | 合成控件树的核实与隐私投影 |
| GuiNavigatorTest | 4 | 慢模型、新观察、迟到决策与 Finish |
| PreviewTouchTest | 18 | 手势、映射、序列、租约、取消与超时 |
| OverlayIntegrationTest | 19 | SDK 35 窗口、回调、点击/拖动/双击、隐藏/切屏/配置与渲染 |
| DisplayTouchInjectorTest | 8 | SDK 34/35 下的 displayId、UID、模式、触点和终止压力 |

Android 测试运行的是生产 StatusCapsule、VirtualScreenOverlay、DisplayTouchInjector。窗口服务、输入接收端及 MotionEvent 的 displayId 元数据使用 Shadow；控制权变化由测试数据驱动。没有连通真实 WorkbenchViewModel→Shizuku→目标 App，也没有真实 InputDispatcher、主屏焦点、IME、声音、触摸延迟或成功率数据。

## 真实问题与处理

- 上轮拥塞取消测试抓到缓冲 MOVE 重放；逐包 ensureActive 的回归保留并通过。
- 本轮清理了本地生成目录中的重复 dataless class，恢复构建；不修改源码解决缓存问题。
- 新增 View ID 的接收者被 show(id) 参数遮蔽；编译失败后改为 this.id。
- 两个 SDK 的适配器测试读到 displayId=0；查证 Robolectric 默认未模拟该字段。新增显式元数据 Shadow 后参数断言通过，该项只作为契约证据，不作为真实注入成功。

## 复现与产物

JDK 17 + Android SDK 35；首次运行需下载测试依赖与 Android 框架运行时：

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
./gradlew :app:testDebugUnitTest --tests 'com.octopus.agent.overlay.OverlayIntegrationTest'
./gradlew :app:testDebugUnitTest --tests 'com.octopus.agent.bridge.DisplayTouchInjectorTest'
apksigner verify app/build/outputs/apk/debug/app-debug.apk
```

XML/HTML：core/build/test-results/test/、app/build/test-results/testDebugUnitTest/ 与两模块 build/reports/tests/。渲染图：app/build/reports/overlay/compact.png、expanded.png；采用合成背景，不是手机截图，也不是跨系统像素金标。GitHub CI 同步执行并保存报告、PNG 和 APK。

当前 APK 25,050,239 字节，SHA-256：

```text
f955f6fbf13cada4812af820cce541037c5ec1a294c916670bb28483ee74afb0
```

lint 保留 3 条 PrivateApi、3 条依赖版本更新、1 条资源目录限定、6 条可选 KTX、1 条字符串资源提示。测试依赖不进入生产 APK。

设备成绩继续见 [AC01–AC12](README.md)，全部待验证。先做双屏/焦点预检，再验收真实 [小窗场景](../docs/OVERLAY.md)。本版文字输入、双指手势和交还后自动恢复均未实现。
