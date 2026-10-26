# MVP 交付与后续验收

当前交付范围：单执行 Agent，手机直连 HTTPS 模型，双会话后台 App，招聘邮件到事件/任务，确认与去重，重开核实，小窗原地接管/输入/放大，修改后安全恢复，以及基于交互元数据的调速。多执行 Agent、多指转发、截图出站属于后续扩展。

## 可复现的本地交付

    ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
    python3 tools/local_evaluation.py --include-tools --output evaluation/runs/local.json \
      --apksigner /your/android-sdk/build-tools/35.0.0/apksigner
    python3 tools/prepare_demo.py --output evaluation/runs/demo

调试 APK：app/build/outputs/apk/debug/app-debug.apk。GitHub 每次提交也运行上述检查，保留 APK、HTML/JUnit 结果、合成窗口渲染和本地 JSON 摘要；正式设备结果另存。APK 使用调试签名，仅作为 take-home 演示构建。

模型连接检查已经接入工作台，发送合成任务，不读邮箱、不操作 App。测试通过只代表 HTTPS、密钥/模型和计划协议可用。传输限制 HTTPS、禁重定向和自动重试，校验限长响应、拒绝截断/拒答/工具调用，错误不包含服务端正文；取消会断开连接并等待 I/O 清理。真实供应商尚未在线验证，可能不支持 json_object 或指定参数。

## 真机验收顺序（已恢复授权，等待设备）

1. 先运行只读预检，记录 APK/API、Shizuku UID、显示 flags 和已装 App 版本。Mate 60 Pro 的具体系统能力尚未确认；能安装普通 APK 以及支持所需 Android API 是前提。
2. 在可见工作台重新绑定当前 APK 的 shell 服务并开启无障碍，确认 A/B 图像与输入绑定。模型仍不接触主屏内容。先验收主屏连续中文输入、焦点和音频，失败就停止写入实验。
3. 用户在手机保存自己的模型配置，主动点“测试已保存的模型连接”。再在真实测试账号/标签/日历完成登录与范围授权。
4. 导入生成的三封合成邮件和 conflict.ics。准备器只写本地文件，不发送邮件或访问账号；测试域名链接需替换成自己控制的测试链接。
5. 完成 [AC01–AC12](../evaluation/README.md)，保存原始录像与逐项重开证据；后台日志由工作台“导出脱敏评估日志”按钮选择保存位置。
6. 运行 python3 tools/summarize_events.py events.jsonl，区分动作耗时、模型耗时和工作流记录的核实结果。日志不是独立的真实 App 成功证明。
7. 按实际速度录制 90–120 秒演示，附完整未加速录像和 30 分钟答辩材料，补充 README 的设备结果。

日期需要推迟时，prepare_demo.py --start-date YYYY-MM-DD 会把首封面试的北京时间日期移到指定日，重新按 IANA 规则转换悉尼时间，并同步移动冲突事件。不要只改屏幕上的月份而保留旧 UTC 偏移。

后台会话使用用户启动的 specialUse 前台服务，切走/重建页面不结束任务。服务终止后撤销输入、关闭显示，不自动重启或恢复授权；停止入口与通知权限边界见 [LIFECYCLE.md](LIFECYCLE.md)。

## 完成边界

本地检查不要求用户账号或连接手机，因而只能证明代码路径与模拟契约。用户已授权恢复真机和在线验证；本次连接检查尚未发现 ADB 设备。真实 App、在线模型、通知触发、性能对照和实际录屏仍待验证。题目要求的“用户持续操作 + 同时可见后台实际执行 + 最终真实结果”必须由同一真机运行证明；当前没有这种证据，不能标记 take-home 全部验收完成。

继续这些验证需要连接可用 Android 环境，并在手机本地配置模型。无需在聊天或仓库提供 API Key。取得设备证据前，MVP 代码交付与题目最终验收分别报告。
