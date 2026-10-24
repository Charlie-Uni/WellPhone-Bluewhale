# 本地检查记录

2026-10-25（悉尼）：**85 项行为测试通过；APK 构建、签名校验与 lint 检查通过。** 机器结果见 [local-checks.json](local-checks.json)。前两轮分别为 31 项框架测试与 67 项工作流/GUI 规则测试，本轮增加 18 项触摸行为测试。

## 实际结果与边界

| 检查 | 结果 |
| --- | --- |
| JVM | 85 项，0 失败、0 错误、0 跳过 |
| 邮件→日历生产编排 | 模拟目标中 2 事件、1 任务，1 次预置冲突；去重与回执丢失恢复通过 |
| 触摸映射/识别 | fit、黑边、放大后坐标、双击不点击 App、单击等待、重置丢弃待点击 |
| 人工控制权 | 整段租约、终止清理、过期 E、另一会话独立动作、拥塞取消不重放 |
| 生命周期/预算 | 已取消的界面作用域拒绝开始；15 秒手势超时 CANCEL 清理 |
| Android | assembleDebug 通过，minSdk 34 / targetSdk 35，签名校验通过 |
| lint | 0 错误 / 14 条提示 |
| 真实模型、WindowManager、触摸注入、焦点/IME | 未运行；真机按用户要求暂缓 |

| 套件 | 项数 | 范围 |
| --- | --- | --- |
| RulesTest | 23 | 时区、权限代次、排空、幂等、隐私、负载策略 |
| AgentTest | 5 | 基础单步循环、搜索与停止边界 |
| CoordinatorTest | 3 | 计划、确认与汇总 |
| WorkflowTest | 16 | 生产工作流 + 模拟 Mail/Calendar/Model |
| GuiEvidenceTest | 16 | 合成控件树的核实与隐私投影 |
| GuiNavigatorTest | 4 | 慢模型、新观察、迟到决策与 Finish |
| PreviewTouchTest | 18 | 触摸协议、坐标、手势、租约与取消 |

## 真实失败与修复

拥塞取消测试最初失败：DOWN 阻塞时队列满而取消，恢复后仍消费了 32 个缓冲 MOVE。逐次派发前增加 ensureActive，取消后仅清理 CANCEL；原失败条件的回归测试通过。Android 编译另发现回调互相引用导致 Kotlin 推断递归，改为显式类型后构建通过。

这些是模拟端口或纯 Kotlin 行为结果。没有在真实窗口点击、放大、滑动或输入；也没有手机成功率、帧率、触摸延迟、主屏 jank 或焦点隔离成绩。Native 注入、App UID 定向和 OEM 隐藏 API 均待实测。

## 复现

JDK 17 + 配置好的 Android SDK：

```bash
./gradlew :core:test :app:assembleDebug :app:lintDebug --console=plain
./gradlew :core:test --tests 'com.octopus.core.PreviewTouchTest'
apksigner verify app/build/outputs/apk/debug/app-debug.apk
```

测试不读取真实邮箱、不调用模型。XML 报告在 core/build/test-results/test/，lint 在 app/build/reports/。本次调试 APK 为 25,225,310 字节，SHA-256：

```text
fa1a0c30efcb52ae59f19cd3318027fa413e6d59948fefbb92501a9e63380939
```

lint 提示：3 条 PrivateApi、3 条依赖版本更新、1 条资源目录限定、6 条可选 KTX、1 条字符串资源建议。绘制分配与拖动标题栏的 performClick 提示已修复，没有使用全局 suppress。

设备评估继续采用 [AC01–AC12](README.md)，全部保留待验证。优先运行双屏/焦点预检，再检查 [小窗测试场景](../docs/OVERLAY.md)。本版没有文字输入桥接、双指手势或自动恢复工作流；隐藏保持 HUMAN，交还仍需显式操作。
