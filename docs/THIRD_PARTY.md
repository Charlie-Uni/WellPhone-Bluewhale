# 第三方依赖

| 组件 | 固定版本 | 上游 / 许可 |
| --- | --- | --- |
| Gradle wrapper | 8.11.1 | https://github.com/gradle/gradle · Apache-2.0 |
| Android Gradle Plugin | 8.9.2 | https://developer.android.com/build · Apache-2.0 |
| Kotlin | 2.1.20 | https://github.com/JetBrains/kotlin · Apache-2.0 |
| kotlinx.coroutines | 1.10.2 | https://github.com/Kotlin/kotlinx.coroutines · Apache-2.0 |
| kotlinx.serialization | 1.8.1 | https://github.com/Kotlin/kotlinx.serialization · Apache-2.0 |
| AndroidX Compose / Activity / Lifecycle | 见 app/build.gradle.kts | https://android.googlesource.com/platform/frameworks/support/ · Apache-2.0 |
| Shizuku API / provider | 13.1.5 | https://github.com/RikkaApps/Shizuku-API · Apache-2.0 |
| Robolectric（仅测试） | 4.16.1 | https://github.com/robolectric/robolectric · MIT |
| JUnit（Android 本地测试） | 4.13.2 | https://github.com/junit-team/junit4 · EPL-1.0 |

本仓库未复制 scrcpy server 源码。若后续引入，固定 commit、审查并保留其 Apache-2.0 NOTICE 和相关组件许可。Android 隐藏常量适配必须指向对应 AOSP 版本，不能用不明来源二进制。

Gradle wrapper 的版权与许可头保留在脚本中；Apache-2.0 全文见 LICENSES/Apache-2.0.txt。项目自身许可由仓库所有者决定，当前不擅自授权。
