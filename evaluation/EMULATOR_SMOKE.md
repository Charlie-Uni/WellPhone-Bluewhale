# Android 15 模拟器系统层验证

2026-10-26（悉尼）：手机暂时无法连接，补做了一轮实际 Android 框架探针。结果为**部分系统路径通过，真机 AC01–AC12 保持待验证**。机器摘要见 [emulator-smoke.json](emulator-smoke.json)；这轮不加入原 321 项 JUnit/Robolectric/工具测试计数。

## 环境与范围

- 独立 ARM64 AOSP API 35 模拟器，system-images;android-35;default;arm64-v8a，revision 2。系统 build、APK 指纹、PID、实际 UID 均记在 JSON。
- 使用提交 418f8a1 的 APK，通过正式工作台连接官方 Shizuku 13.6.0。Shizuku 和触摸进程为 shell UID 2000，SELinux Enforcing，全程没有 adb root。
- 此 userdebug 镜像自身可 Root，不能据此推断普通手机权限和 OEM 兼容性。模拟器启动方法参考 [Android 官方文档](https://developer.android.com/studio/run/emulator-commandline)；Shizuku 使用 [官方发布包](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0) 与其界面显示的 ADB 启动命令。
- 不登录邮箱、日历或模型。生产入口创建两块屏，测试准备通过 ADB 将系统 Contacts/DeskClock 分别放入；生产 Google App 白名单没有修改。

## 实际观察

| 检查 | 结果与证据 |
| --- | --- |
| 创建与权限 | display 2/3，720×1280、240dpi，owner UID 2000；有效 flags 0x1880，含 TRUSTED / OWN_FOCUS / STEAL_TOP_FOCUS_DISABLED |
| 双屏与主屏 | Contacts 与 DeskClock 各有自己的窗口；主屏始终保持 top focused display 0；切到桌面后主屏窗口为 Launcher |
| 权限弹窗 | Contacts 通知授权界面落在 display 2；本轮未穷举 Toast、ANR、音频或其他 App 的弹窗 |
| 实际预览 | ImageReader/JPEG/工作台路径展示 Contacts、权限框及关闭后的列表；未测量编码/显示时延 |
| UID 定向输入 | 独立探针从已装 APK 加载生产 DisplayTouchInjector；正确 Contacts UID 的 DOWN/UP 返回 true，实际打开 ContactEditorActivity；错误 DeskClock UID 被 InputDispatcher 拒绝 |
| 单屏控制权 | 无活动工作流时，A 接管后按钮变为“交还 Agent”，交还后 A=E2/AGENT，B=E0/AGENT；生产 Controller/Broker 与真实 bindControl AIDL 路径生效 |
| 页面与后台 | CLEAR_TOP 重建 MainActivity，ActivityRecord 改变、应用 PID 仍为 2417；A/B ID 与控制权保留。HOME 到桌面后，显示和 specialUse 前台服务仍在 |
| 停止 | 工作台“停止后台会话”后，两块 Octopus 显示与前台服务均消失，主屏焦点仍为 0；随后关闭本次模拟器，结束 CPU 占用 |

一次输入法快照为 token display 0、inputShown=false。没有进行中文组词、键盘切换或持续输入实验，不作为中文输入隔离证明。只有一轮系统探针，没有成功率、延迟分位数、jank 或功耗结论。

## 触摸探针的边界与复现

[DisplayTouchProbe.java](../tools/android/DisplayTouchProbe.java) 只允许 ro.kernel.qemu=1 且 UID=2000；禁止默认屏和系统 UID。它在 Broker/TouchStreamGuard 之外调用生产适配器，验证真实隐藏 API、参数与 UID 路由。小窗→Broker→ShellService→目标 Google App 的完整手势/文字链，以及正在执行工作流时的排空恢复，仍需另验。

探针遇到拒绝/未知 DOWN 时也尝试 CANCEL。最初版本缺少这一步，系统 verifier 在后续测试准备中报告 pointers already down；修正后重新编译，连续执行错误 UID → 正确 UID 通过。生产 ShellService 原已在失败时清理 CANCEL，本轮没有更改生产 APK。

使用 JDK 17、SDK 35，先创建新的输出路径：

    python3 tools/build_touch_probe.py --sdk /your/android-sdk --output evaluation/runs/touch-probe/probe.jar
    adb -s emulator-5554 push evaluation/runs/touch-probe/probe.jar /data/local/tmp/octopus-touch-probe.jar
    adb -s emulator-5554 shell pm path com.octopus.agent

从上一行取得真实 base.apk 路径，读取本轮显示 ID 和实际目标 App UID，观察界面后选坐标；下列大写词均需替换，不能照搬本轮 UID/坐标：

    adb -s emulator-5554 shell 'CLASSPATH=/data/local/tmp/octopus-touch-probe.jar:APK_PATH app_process /system/bin DisplayTouchProbe DISPLAY_ID UID X Y'

只在专用空白模拟器操作。编译器或 D8 失败会立即退出，不发布旧产物；输出已存在时拒绝覆盖。CI 编译并归档探针，不在 CI 声称跑过模拟器。遇到同步目录的 SDK 读取问题，可用 --android-jar 指定非同步目录中的官方 API 35 stub。

## 未完成的验收

真实 Gmail/Google Calendar 多步任务、在线模型、用户手机上的中文输入/音频/全局弹窗、性能对照、实际提醒触发、云同步与演示视频均未运行。系统 App 的人工 ADB 启动只是实验准备，没有计入 Agent 任务完成。当前仍需连接兼容手机并在手机本地配置模型，再执行 [统一 AC 表](README.md)。
