# 按交互元数据调节后台频率

代码使用公开 Android API，不需要 Root 或额外权限。主屏信号只取无障碍窗口 ID/类型、事件类型和系统温度档位；不读取主屏 root、event.source、文字、截图或包名，也不把这些元数据发给模型。

MainLoadReader 每秒读取 display 0 的窗口元数据，识别输入法窗口；只接受该窗口集合中的滚动、输入与触摸交互事件，维持 1.5 秒保守窗口。虚拟屏事件不会提高主屏交互计数。温度达到 MODERATE、信号缺失或无法读取也回退保守。连续三次稳定采样后回正常。它是交互与热状态代理，未测量其他 App 的帧时，不能据此声称降低了 jank。

依据：[AccessibilityEvent](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent)、[PowerManager](https://developer.android.com/reference/android/os/PowerManager)。

| 档位 | 观察准入上限 | 动作准入最小间隔 |
| --- | --- | --- |
| 保守 | 0.5 次/秒 | 1000ms |
| 正常 | 2 次/秒 | 500ms |

这是配置值，非实测吞吐。A/B 共用同一个 AdaptivePacer；等待不持有输入租约，交还也不重置旧准入时钟。等待结束再次检查 E、控制权和观察新鲜度，过期则保留检查点。无障碍动作、最终 Save 和定向日历 Intent 都经过该门；人工触摸/文字仍有独立的 Broker 通道。

预览按需求编码：工作台可见时请求 A/B，小窗仅请求当前选中屏；全部隐藏时不编码 JPEG。ImageReader 始终 acquireLatestImage，关闭被替换的旧 Image，最多保留最新一张；maxImages=3 给采集保留其余两个槽位，避免缓冲积压。重新打开预览会按需求编码保留帧，静态 App 无需先产生新画面。关闭屏幕时在采集线程释放该 Image，再释放显示/Reader，并等待结算。

AIDL 的 capture policy 有 3 秒短租约，客户端死亡或停止续期后自动停止编码；隐藏、换档与租约过期使旧 JPEG 失效，旧代次编码结果不会作为新帧读回。手动接管的可见预览采用 500ms 编码上限；其他可见预览跟随当前档位。保留原始帧不进行主屏截屏或模型出站。

工作台显示当前档位，日志只记录档位切换。主屏输入检测覆盖、OEM 窗口语义、实际编码/显示延迟、系统负载、前台帧时与功耗均待真机 AC08/AC10 验证。暂未使用 dumpsys/Perfetto 作为在线控制信号；它们用于独立评价。

默认自适应。仅在没有活动任务时可选择“固定频率（实验对照）”，使用 2 次/秒观察与 500ms 动作间隔，关闭交互触发的自动降频；该选择持久化到手机设置。恢复自适应会重置稳定计数。对照实验需要保持模型、镜像、主屏操作和输入材料一致。
