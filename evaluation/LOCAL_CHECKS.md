# 本地检查记录

2026-10-25（悉尼）：**210 项测试通过（111 项 core + 99 项 Android 层）；APK 构建、签名与 lint 检查通过。** 机器结果见 [local-checks.json](local-checks.json)。

## 实际结果与范围

| 检查 | 结果 |
| --- | --- |
| core 行为测试 | 111 项，0 失败、0 错误、0 跳过；含 18 项触摸、10 项文字、16 项恢复 |
| Android 窗口/事件 | 31 项，SDK 35；生产 View、MotionEvent、Handler、文字编辑面板、权限/配置变化和本地绘制 |
| Android 输入适配器 | 4 个用例 × SDK 34/35 = 8 项；反射与参数契约 |
| Android 文字适配器 | 15 个用例 × SDK 34/35 = 30 项；生产 HumanTextAccess + 真实 Broker，模拟节点与 SET_TEXT 回执 |
| Android 恢复集成 | 15 个用例 × SDK 34/35 = 30 项；生产 CalendarGuiPort/Navigator/Broker/SQLite/确认端口，模拟 GUI/shell/模型/动作 |
| 合成画面 | compact.png / expanded.png / text-editor.png；像素/边界检查，并查看字段、按钮、黑边与布局 |
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
| HumanTextTest | 10 | 中文/多行/清空、草稿一次性、晚到读取、旧 E、文字/触摸名额与独立会话 |
| RecoveryTest | 16 | 真实工作流中断/续跑、修订持久化、剩余事项、去重、拒绝未知 Save 和伪证明、工作流/输入双屏障 |
| OverlayIntegrationTest | 31 | SDK 35 窗口、回调、手势、文字字段选择/确认/取消/Back、隐藏/切屏/配置与渲染 |
| DisplayTouchInjectorTest | 8 | SDK 34/35 下的 displayId、UID、模式、触点和终止压力 |
| HumanTextAccessTest | 30 | SDK 34/35 下的指定屏/窗口/节点/原值/布局/E/过期检查，密码过滤、SET_TEXT、读回与取消结算 |
| RecoveryIntegrationTest | 30 | SDK 34/35 下的新观察/票据、保留字段、未知编辑保护、日期/时间/身份角色、重开证明、SQLite 修订/人工持有状态与旧确认拒绝 |

Android 测试运行生产窗口 View、触摸/文字适配器以及 CalendarGuiPort、GuiNavigator、SessionBroker、LocalStore、WorkflowReviews。窗口、无障碍节点/回执、输入接收端及显示元数据使用 Shadow，恢复测试的 GUI/shell/模型决定/动作接收端使用模拟数据。真实 Broker 和 SQLite API 验证状态与持久化逻辑；没有连通真实 WorkbenchViewModel→Shizuku→App，也没有真实 InputDispatcher、主屏焦点、IME、声音、触摸延迟或成功率数据。

## 真实问题与处理

- 上轮拥塞取消测试抓到缓冲 MOVE 重放；逐包 ensureActive 的回归保留并通过。
- 本轮清理了本地生成目录中的重复 dataless class，恢复构建；不修改源码解决缓存问题。
- 新增 View ID 的接收者被 show(id) 参数遮蔽；编译失败后改为 this.id。
- 两个 SDK 的适配器测试读到 displayId=0；查证 Robolectric 默认未模拟该字段。新增显式元数据 Shadow 后参数断言通过，该项只作为契约证据，不作为真实注入成功。
- 文字写回后的取消审查发现初版读回可能提前释放人工名额；改为 NonCancellable 结算，新增取消回归通过。未知回执文案也改为“无法确认”，不声称未发生副作用。
- 关闭文字面板的提示会改变预览布局；测试若在布局遍历前点击，该映射会被安全取消。完成布局后新点击正常，旧映射失效约束保留。
- 恢复审查拦下旧预填/旧票据重用；改成检查点与全新页面核对。增加 HUMAN_HELD，普通重跑不能把人工删标记后的事项重新创建。
- 页面包含旧账号/日期或两个时间不足以核实；恢复绑定角色字段，覆盖时间互换、错日期、错账号以及查重阶段不能绕过未知详情的反例。
- 本轮构建再次卡在同步目录重复 XML；定位读取线程与文件后用 clean 重建。测试编译还修正了一处 RuntimeEnvironment 不适用的泛型调用。

## 复现与产物

JDK 17 + Android SDK 35；首次运行需下载测试依赖与 Android 框架运行时：

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
./gradlew :app:testDebugUnitTest --tests 'com.octopus.agent.overlay.OverlayIntegrationTest'
./gradlew :app:testDebugUnitTest --tests 'com.octopus.agent.bridge.DisplayTouchInjectorTest'
./gradlew :app:testDebugUnitTest --tests 'com.octopus.agent.accessibility.HumanTextAccessTest'
./gradlew :app:testDebugUnitTest --tests 'com.octopus.agent.workflow.RecoveryIntegrationTest'
apksigner verify app/build/outputs/apk/debug/app-debug.apk
```

XML/HTML：core/build/test-results/test/、app/build/test-results/testDebugUnitTest/ 与两模块 build/reports/tests/。渲染图：app/build/reports/overlay/compact.png、expanded.png、text-editor.png；采用合成背景，不是手机截图，也不是跨系统像素金标。GitHub CI 同步执行并保存报告、PNG 和 APK。

当前 APK 25,851,518 字节，SHA-256：

```text
d39a55fd3a8205544dd77e73285fa556b30df8f540a82f237cfdb1aef60918b1
```

lint 保留 3 条 PrivateApi、3 条依赖版本更新、1 条资源目录限定、6 条可选 KTX、1 条字符串资源提示。测试依赖不进入生产 APK。

设备成绩继续见 [AC01–AC12](README.md)，全部待验证。文字桥与同进程的安全续跑已实现；真实中文组词、焦点/键盘、指定 App 字段兼容和持久化结果待测。[恢复规则](../docs/RESUME.md) 只自动接纳可核对的标题/备注，未知页面保留现场。双指与进程退出后的自动恢复未实现。
