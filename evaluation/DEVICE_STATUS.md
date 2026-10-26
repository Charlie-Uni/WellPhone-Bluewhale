# 真机与在线验证状态

2026-10-26：用户已授权恢复真机与在线验证。代码基准 a6e7b41，本地 321 项测试、APK/签名检查及 [GitHub CI](https://github.com/Charlie-Uni/WellPhone-Bluewhale/actions/runs/40256808455) 通过。

本次 ADB 设备列表为空，电脑 USB 清单也未发现华为产品。只读预检结果是 BLOCKED，原因是没有一台已授权在线的 Android 手机；没有安装 APK、访问手机账号或发出在线模型请求。原始本地预检保存于 evaluation/runs/preflight-2026-10-26-resumed.json（忽略目录）。

## 继续条件与顺序

- 用数据线连接手机，开启开发者选项中的 USB 调试，并在手机确认允许电脑调试。仅开启 HDB/华为助手不足以提供 ADB。
- 预检读取实际 Android API、系统 build 与 App 版本。当前 APK 最低 API 34，且需要普通 Android APK、Shizuku shell UID 2000 和所需虚拟屏 flags。HarmonyOS 版本名不能证明这些能力；不满足时先记录具体失败，再决定换兼容设备或另行适配。
- 安装 APK，在可见工作台开启无障碍、配对/启动 Shizuku，逐项检查 A/B、App 登录、控件、焦点/中文输入与后台服务。失败时停止写入实验，不使用主屏回退。
- 模型地址、名称与 API Key 由用户在手机本地填写；不发送到聊天或仓库。先运行内置合成连接检查，再以三封合成招聘邮件和测试日历执行真实闭环。
- 按 AC01–AC12 记录结果，完成持续主屏操作与后台执行的同钟录屏、结果重开及性能对照，最后更新 README 成绩。

[local-checks.json](local-checks.json) 只采集本地测试，设备字段固定标明 NOT_RUN_LOCAL_EVIDENCE_ONLY，不推断用户当前授权。a6e7b41 的 CI 产物保留其生成时的历史跳过状态。当前设备/在线流程以本页和 AC 表为准，没有成功率或兼容性结论。
