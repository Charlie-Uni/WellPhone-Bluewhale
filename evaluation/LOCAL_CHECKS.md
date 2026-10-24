# 第一阶段本地检查

2026-10-24，macOS arm64 / JDK 17.0.11 / Gradle 8.11.1。机器可读记录见 [local-checks.json](local-checks.json)。

| 检查 | 结果 |
| --- | --- |
| core 行为测试 | 31 通过，0 失败，0 错误，0 跳过 |
| Android assembleDebug | 通过，生成 0.1.0-dev 调试 APK |
| APK 签名验证 | 通过；minSdk 34，targetSdk 35 |
| Android lintDebug | 0 错误，9 条提示 |
| Python 工具 | 语法检查通过；日志统计用合成输入做过分位数与非法负耗时检查 |
| 邮件到日历闭环 | 尚未接通 |
| 模型在线调用 | 未运行 |
| 真机 / 双屏 / 焦点 / 输入法 / 负载 / 音频 | 按用户要求暂缓，全部待验证 |
| GitHub Actions | 工作流已定义，尚未在 GitHub 运行 |

测试覆盖：夏冬时和夏令时切换歧义、截止/提醒顺序、等价时间去重键、敏感邮件筛选、旧 E 与旧观察拒绝、在途输入排空、会话独立接管、停止后显示复用、过期确认、保存未知状态、搜索显式提交、过滤节点不可操作、窗口绑定丢失即停止。测试端口使用合成数据，不代表真实 Gmail/Calendar 成功率。

保留的 lint 提示：2 条 shell 隐藏系统接口兼容性、3 条固定依赖版本的更新建议、3 条可选 KTX 风格建议、1 条资源目录版本限定可精简。隐藏接口的行为仍须在选定系统验证；没有使用全局 suppress 使结果看起来全绿。

最新检查命令：

```bash
./gradlew :core:test :app:assembleDebug :app:lintDebug --console=plain
# 仅修改备份与图标资源后：
./gradlew :app:assembleDebug :app:lintDebug --console=plain
apksigner verify app/build/outputs/apk/debug/app-debug.apk
```

APK 与构建报告位于忽略的 build/ 目录，不把二进制和个人运行日志直接提交到 Git。
