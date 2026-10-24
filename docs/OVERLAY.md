# 悬浮小窗与原地接管

状态：代码已接入，85 项 core 行为测试、27 项 Robolectric Android 层测试与 Android 构建验证；真实系统触摸、焦点、IME 和 ROM 兼容性按用户要求暂缓。没有新建设备演示或流畅度数据。

## 用户路径

胶囊点击 → 展示 A/B 中选定的现有虚拟屏 → 默认只读 → 点击接管 → 撤销 Agent E → 等待 inFlight 归零 → HUMAN → 单指触摸。用户双击画面或点击放大按钮调整预览；拖动标题栏移动小窗。“隐藏”只关闭观察窗口，保留 HUMAN；“交还”先终止并排空触摸，再增加 E，后续 Agent 必须重新观察。

当前单执行工作流在会话被撤销后停止，交还不自动恢复该轮；再次开始通过规范事项和 Ledger 查询恢复。会话隔离仍允许另一会话接收合法动作，不代表已经实现两个执行 Agent。

## 模块

| 模块 | 实现与边界 |
| --- | --- |
| StatusCapsule | 非焦点悬浮；用户点击回调打开小窗，不自动拉起 Activity |
| VirtualScreenOverlay | 原生 WindowManager 窗口，非焦点；A/B 切换、拖动、放大/缩小、隐藏、接管/交还按钮 |
| PreviewTransform | fit-center 映射，黑边不接受输入；版本随会话/尺寸/控制权变化失效 |
| PreviewGestures | 点击延后约 300ms，双击只缩放；拖动和按住形成连续单指流；重置删除待派发点击 |
| HumanTouchController | 同一 Broker 人工名额覆盖整段输入与 CANCEL 清理；32 个样本队列，拥塞取消，不补发旧动作 |
| Accessibility hit check | 仅目标显示中当前触点命中的最上层窗口；包与窗口 ID 变化时停止，无默认屏回退 |
| Shizuku typed bridge | bindHuman + injectTouch，绑定 E/预览版本/尺寸/包；shell UID 2000，精确 displayId + App UID 注入 |
| TouchStreamGuard | 坐标、序列、手势 ID、顺序与时间检查；防止 MOVE 无 DOWN、旧包和重复手势 |

## 输入与窗口生命周期

观察者与 VD 生命周期分离。隐藏和放大不调用 createDisplay/launch，也不改变 displayId。每次窗口输入重置都提升预览版本，取消本地待派发点击和正在转发的手势；跨尺寸/跨会话的旧坐标不会在新预览重放。整段触摸结束前 Broker 阻止交还。

动作在 shell 中使用 AOSP 的 InputManagerGlobal 定向注入接口；保留 WAIT_FOR_FINISHED 回执。只有手机 UID 2000 的服务执行隐藏接口，主 App 不申请 INJECT_EVENTS。三参数的目标 UID 接口缺失就停止，不降级为不限定 UID 的注入；事件始终写入真实虚拟 displayId。native 确认表示系统注入回执，不能证明画面已渲染或操作结果正确。[AOSP Android 14 实现](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/core/java/android/hardware/input/InputManagerGlobal.java)

注入前核对预览帧采样时间、E、可见窗口、目标包和坐标。系统已接受的在途动作无法撤回；取消后停止排队样本，再向原会话发 CANCEL，等清理结算后释放人工名额。shell 重新绑定控制权或销毁屏幕时也尝试清理旧触摸。服务死亡会停止会话，不重放手势。

小窗是 TYPE_APPLICATION_OVERLAY，设置 NOT_FOCUSABLE 与 NOT_TOUCH_MODAL；只在窗口区域内接触摸，用户点击“确认/设置”才启动工作台。权限由用户手动打开。代码约束不代替前台连续中文输入与音频/弹窗实测。

## 本版限制

- 单指点击、拖动和按住；双指触发取消。双击预留给窗口，不能把双击原样送给 App。
- 单击有约 300ms 识别等待，长按包含目标 App 的识别延迟；最长一段 15 秒，超时清理。
- 虚拟屏要求固定尺寸、rotation=0；尺寸或旋转不匹配停止。放大的是预览窗口。
- 虚拟屏 IME 继续隐藏；没有键盘输入/文字桥接。用户可操作按钮、列表等，不能据此宣称支持任意文字编辑。
- 预览采样和镜像延迟未实测；不声明低延迟或稳定帧率。
- 不引入前台常驻服务，系统回收后不自动恢复；保存状态仍由 Ledger 决定。

## 本地检查与待验证

纯 Kotlin 测试检查 fit 映射、黑边、双击不点击 App、隐藏丢弃待点击、拖动/按住、序列校验、整段租约、取消清理、队列拥塞不重放、旧 E 拒绝和另一会话独立动作。Android 端新增两层检查：

| 层次 | 范围与结果 |
| --- | --- |
| SDK 35 View/窗口集成 | 19 项；运行生产 StatusCapsule/VirtualScreenOverlay，直接派发 MotionEvent、执行 Handler、按钮回调、窗口增删和配置变化；Native Graphics 绘制合成预览 |
| SDK 34/35 适配器契约 | 4 个用例 × 2 个 SDK = 8 项；执行生产 DisplayTouchInjector 反射方法与事件构造，记录 displayId、目标 UID、模式、触点与按压状态 |

窗口测试通过测试数据驱动控制权状态并记录回调；未把真实 WorkbenchViewModel、Shizuku 和目标 App 连起来。WindowManager/InputManager 系统接收端由 Shadow 模拟。Robolectric 默认 MotionEvent 未实现 displayId 存取，本仓库为该元数据添加了明确的 Shadow；因此参数测试验证的是适配器契约，系统接收、权限和焦点仍待实测。

渲染图在 app/build/reports/overlay/compact.png 与 expanded.png，背景为带文字标记的合成虚拟屏；仅检查布局和绘制，不是设备或 App 执行证据。测试有中心像素断言，并查看了输出图中的按钮与黑边。GitHub CI 保存同一套 HTML 报告与 PNG。

复现：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.octopus.agent.overlay.OverlayIntegrationTest'
./gradlew :app:testDebugUnitTest --tests 'com.octopus.agent.bridge.DisplayTouchInjectorTest'
```

采用固定的 Robolectric 4.16.1、JDK 17，窗口测试 SDK 35，适配器覆盖 SDK 34/35。首次运行需下载 Maven Central 的框架运行时。[Robolectric 官方设置](https://robolectric.org/getting-started/)、[兼容说明](https://robolectric.org/compatibility_table/) 支持该测试路线。

恢复设备测试后沿用 AC01/AC08/AC11/AC12：记录接管前后的 displayId、E、窗口和日志；主屏连续中文输入；A/B 单独接管、双击放大、触点与黑边；拖动中隐藏、切屏与交还；App/System 弹窗、旋转、服务死亡。核对无主屏注入、无点击补发、无自动恢复；未测项保留待验证。
