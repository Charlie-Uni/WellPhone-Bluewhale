# 本地检查记录

本轮在 2026-10-25（悉尼）完成。**67 项行为测试通过；APK 构建、签名校验和 lint 检查通过。** 机器可读结果见 [local-checks.json](local-checks.json)。第一轮为 31 项框架测试；本轮新增工作流和 GUI 证据测试。

## 范围与结果

| 检查 | 实际结果 |
| --- | --- |
| JVM 测试 | 67 项，0 失败、0 错误、0 跳过 |
| 三封邮件生产编排 | 模拟目标中核实 2 个事件、1 个任务；1 次预置冲突 |
| 重跑与回执丢失 | 复用规范快照，不多点保存；UNKNOWN 后查询恢复 |
| GUI 负面证据 | 拦截备注时间冒充字段、缺备注、错日期/AM-PM/账户、截断界面 |
| 权限与隐私 | 旧 E / 变动界面拒绝；OTP 不提取；搜索与保存门独立 |
| Android | assembleDebug 通过，minSdk 34 / targetSdk 35，签名校验通过 |
| lint | 0 错误 / 13 条提示 |
| 真实设备 / 真实模型 | 未运行，真机按用户要求暂缓 |

测试套件：

| 套件 | 项数 | 覆盖 |
| --- | --- | --- |
| RulesTest | 23 | 时区、权限代次、排空、幂等、隐私、负载策略 |
| AgentTest | 5 | 基础单步循环、搜索提交和停止边界 |
| CoordinatorTest | 3 | 计划、确认和汇总 |
| WorkflowTest | 16 | 生产工作流通过模拟 Mail/Calendar/Model 端口运行 |
| GuiEvidenceTest | 16 | 合成真实结构的控件树、本地核实和隐私投影 |
| GuiNavigatorTest | 4 | 慢模型后的新观察、界面改变、迟到决策与 Finish 边界 |

这些结果证明普通代码的编排、状态约束和拒绝规则，没有证明真实 Gmail/Calendar 版本能被完整操作。模拟日历的 verified 只表示测试端口返回了所要求的证明。没有模型时延、设备成功率、前台 jank、焦点或通知触发成绩。

## 复现

使用 JDK 17 和配置好的 Android SDK：

```bash
./gradlew :core:test :app:assembleDebug :app:lintDebug --console=plain
# 仅工作流：
./gradlew :core:test --tests 'com.octopus.core.WorkflowTest'
# 签名工具位于 Android SDK build-tools 目录
apksigner verify app/build/outputs/apk/debug/app-debug.apk
```

测试数据是仓库内的纯文本固定样本，不读取真实邮箱、不联网调用模型。XML 报告位于 core/build/test-results/test/，lint 位于 app/build/reports/；这些构建产物由 Git 忽略。

当前 APK：25,206,014 字节，SHA-256：

```text
46acb1b9cca07e3c2778f7c1f0187087d11098bb2dae17650e3ed75df9e5d4c2
```

lint 保留提示：2 条 PrivateApi、3 条依赖版本更新、1 条资源目录限定、6 条可选 KTX 风格建议、1 条字符串资源建议。没有用全局 suppress 隐藏它们；反射接口的兼容性仍须设备验证。

## 下一轮设备评估

继续使用 [AC01–AC12](README.md)，所有设备项仍为待验证。优先验证 Shizuku/有效 flags、双屏窗口绑定、中文焦点，再运行测试标签写入；之后核对提醒、重跑去重与中断恢复。悬浮原地触摸、放大小窗和负载信号接入尚未实现。
