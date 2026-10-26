# 后台会话生命周期

执行控制器由 OctopusApplication 持有，不依赖 Activity/ViewModel。页面离开只影响预览取帧，不取消工作流；重新进入仍使用同一个控制器。前台服务维护用户主动开启的虚拟 App 会话，避免仅依靠缓存页面存活。

## 启动与停止

- 用户在可见工作台点击创建 A/B，先启动 AgentSessionService，最多等待五秒确认前台服务就绪，再调用 shell 创建显示。未就绪就停止，不创建显示，也不回退主屏。
- Manifest 声明 FOREGROUND_SERVICE、FOREGROUND_SERVICE_SPECIAL_USE 和 specialUse 用途说明。只从用户主动可见页面启动，不依赖悬浮权限的后台启动豁免；该类型及用途选自 [Android 服务类型文档](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)，启动限制见 [官方说明](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)。
- 通知通道低优先级、无声音/振动/角标，只显示通用会话状态，隐藏锁屏内容，不含邮件、账号或任务正文。点击通知才打开工作台；停止动作结束全部会话。
- 工作台“停止后台会话”、关闭最后一个虚拟屏、shell 或前台服务丢失都会停止任务。先撤销 E、取消待提交动作并等待已提交输入结算，检查点写入人工持有/未知边界，再解绑销毁 shell 显示。重新连接可以启动新会话，旧权限和计划不重放。
- 服务返回 START_NOT_STICKY，不注册开机重启。进程被系统直接杀死时清理回调可能不发生；下次运行依靠已持久化 WRITE_PENDING/UNKNOWN 先查真实结果，再申请本轮授权，不恢复旧节点或 Save 票据。

当前不申请 POST_NOTIFICATIONS，避免自动弹权限框。Android 13+ 无此授权仍须提交前台通知，系统可能只在任务管理器显示活动服务，通知栏停止按钮未必可见；工作台始终保留停止入口。依据 [官方通知权限说明](https://developer.android.com/develop/ui/compose/notifications/notification-permission)。

## 本地证据与设备边界

Robolectric 在 API 34/35 检查 Manifest、前台状态、通用安静通知、显式停止、空重启 Intent 和启动失败。框架服务通过 Shadow 模拟，没有证明 OEM 后台保活、系统通知权限行为或真实 shell 清理时延。前台服务不是永久存活承诺，系统和用户仍可终止进程；需要在 AC08/AC12 加入切页面、系统停止与重新授权实验。

此配置用于 take-home 调试 APK。若未来上架 Google Play，specialUse 的用途声明还需经过平台审核；本轮没有发布或审核结论。
