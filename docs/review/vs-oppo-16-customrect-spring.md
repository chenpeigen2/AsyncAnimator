# vs-oppo-16 — CustomRectFSpringAnim 六轴物理与生命周期复核

> **第 21/39 份：已完成本轮建议验收（含明确差异）。** 原标题“区域 13”及“lib 只有三个枚举/没有线程协议”的正文已过时。本份逐条核对当前句柄、原厂六轴实现，并实际补入可运行的公共 API 物理 driver，不以已有生命周期适配代替六轴功能。总进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## 1. 范围与证据

本库：
- [CustomRectFSpringAnim](../../lib/src/main/java/com/asyncanimator/anim/CustomRectFSpringAnim.kt) / [RectAnimationLifecycle](../../lib/src/main/java/com/asyncanimator/anim/RectAnimationLifecycle.kt)：七值 AnimType、固定 owner、双阶段结束、可选 Driver 能力。
- 新增 [RectSpringDriver](../../lib/src/main/java/com/asyncanimator/anim/RectSpringDriver.kt)、[RectSpringConfig](../../lib/src/main/java/com/asyncanimator/anim/RectSpringConfig.kt)、[SpringProjection](../../lib/src/main/java/com/asyncanimator/anim/SpringProjection.kt)：六个真实 AndroidX SpringAnimation、聚合快照、参数策略、纯预测和接续。
- 新增 [Demo12RectSpringActivity](../../demo/src/main/java/com/asyncanimator/demo/Demo12RectSpringActivity.kt)，入口/Manifest/字符串已接线；Canvas 消费真实六轴数据，非 SceneSpring 预制曲线。

只读参考根 `D:\oppo_a6_launcher\sources`：

| 原厂路径 | 本次重点证据 |
|---|---|
| `com/android/quickstep/util/animation/CustomRectFSpringAnim.java:47-112,115-137` | 六轴字段、七个 AnimType、六个 RectAnimType |
| 同文件 `:209-258,266-318,330-420` | 200/1 默认力学参数、六轴阈值、tracking 几何、倍率平方缩放、width/height 选择、alpha delay |
| 同文件 `:423-470,478-522` | maybeEnd、六轴更新、progress 与 reverse 重设目标 |
| 同文件 `:525-590,608-648,752-925` | 参数/设备阈值、cancel/start/thread protocol、copyNext、skip/reverse/updateTarget |
| `com/android/quickstep/util/animation/MultiDynamicAnimation.java` / `SpringHolder.java` | 原厂统一帧循环、六轴 holder、下一帧 requestEnd 与预测机制；本库不复制隐藏平台实现 |

另直接查阅本地 Gradle 缓存中 **AndroidX dynamicanimation 1.1.0 sources** 的 `DynamicAnimation.setScheduler`、`FrameCallbackScheduler`、`SpringAnimation.start`、`AnimationHandler` 和 `SpringForce`：
- 1.1.0 已有公开 scheduler 接口，无须反射/复制隐藏 AnimationHandler 才能让数值 SpringAnimation 在指定线程运行。
- native handler 以 uptime 计算 delta，自动应用系统 animator duration scale。
- cancel 只是置空 native callback；清理列表时才注销 duration-scale listener。因此最终 dispose 必须排空已提交的清理 Runnable，不能仅丢弃 scheduler 队列。

## 2. 字段/类型对位：不再是空句柄

| 原字段组或类型 | 当前对应与边界 |
|---|---|
| centerX / rectY / width / radio / radius / alpha 的 force、holder、值、速度、stiffness、damping、minVisibleChange | RectSpringDriver 每轮六个 Axis，各自有真实 SpringAnimation/SpringForce、value/velocity、独立 RectSpringConfig.Spring；不是 36 个空字段或六个同一进度映射。 |
| mIsWithAnim | widthMode，按 AnimType 和起止 ratio 选择 width 或 height；反向切换时同步转换 size 及其速度，避免旧 width 被当成 height。 |
| mTracking / mMinWidth / mLimitRadioFlag | TOP/CENTER/BOTTOM、minimumSize、limitAspectRatio。默认 tracking=TOP、ratio 区间限制关闭；最小尺寸 0.1、ratio 正下限、radius/alpha 显示边界是本库防退化策略，并非 OEM 默认值完全相同。 |
| mStartRectF / mTargetRectF / mCurrentRectF | 输入 RectF 防御复制；currentFrame 是不可变快照，rect getter 返回副本；非跨线程共享可写 RectF。 |
| mAlphaPair / mAlphaStartDelay | 独立起止 alpha 与 startDelay；等待中的 alpha 仍占六轴完成条件，不被其他五轴提前释放。 |
| mCurrentProgress / mProgressWhenReverse | 按 size 剩余行程计算并限幅；反向终点为 0，普通终点为 1。接续保留预测 progress 基值/方向，不回到零。 |
| mAnimLooperExecutor / mStartAsync / animationId | 生命周期句柄已有每轮 owner 和不可变 Event；driver 本身在 owner 上运行，裸句柄仍只可用于状态登记。 |
| mCanceled / mAnimStarted / logical/actual flags | 句柄负责逻辑通知；driver Run 负责真实六轴完成/下一 tick stop，旧 tick 用 Run 身份过滤。 |
| mUpdateListeners | 单个构造期 onUpdate sink 输出包含 rect/六轴值/速度/progress 的不可变帧；没有额外可变全局监听列表。 |
| AnimType / RectAnimType | 七个 AnimType 与原厂名称/顺序一致；六轴以内部索引及强类型配置/值对象表达，不为形式一致再暴露第二个 ordinal API。 |
| BreakParam / RectTransformHelper / SurfaceControl | 未移植系统坐标转换、事务或远程窗口业务，不因新增数值物理引擎就标成拥有这些能力。 |

旧枚举表“去两假、留五真+一个”为错误计数；实际是七个值。旧“不同设备 enum 会 NPE”的推断也不成立，不能把源码不兼容直接等同设备运行时 NPE。

## 3. 方法与时序

- **start**：真实构造六个 SpringAnimation，给每个配置公开 FrameCallbackScheduler；所有 native 单次 Runnable 由同一 owner 的自有帧源触发，六轴更新后只发布一个一致 Frame。
- **cancel / skipToEnd**：句柄先在主线程通知逻辑结束；driver 在下一次 tick 消费 stop。cancel 保留最后画面；skip 输出目标并结束全部轴（包括等待 alpha/零阻尼轴）；actual-end 只报一次。
- **dispose**：新增可选 DisposableDriver 能力，旧 Driver 仍用 clearEndCallback+cancel 默认路径。六轴 driver 在 owner 上立即撤订阅、取消 native springs、执行必要清理，不依赖下一 VSYNC；不再发完成回调。
- **reverseToOpen**：真正改变六轴目标和开窗参数/精度策略；保存原 progress，更新 radius/alpha、ratio bounds。若 size 坐标变换，转换 velocity 并重启该轴，避免几何跳变。
- **updateEndTargetRectF**：更新 center/Y/size/ratio 和可选 radius，保留当前六轴速度。这里同时更新 ratio/一致目标，范围比原厂该方法的局部赋值更完整，不冒称逐行一致。
- **copyNextAnimState**：按给定 deltaMillis 用闭式方程生成不修改 live state 的预览；常规播放仍由 AndroidX 完成。三种阻尼解与 native 下一帧数值/速度对比，保留 delay/first-frame 行为。
- **createContinuation**：将预测位置、radius/alpha、六轴速度及 progress 基值/方向交给新 driver；必要时转换 width/height 速度。不会自动停止旧 driver，调用方须先 dispose 旧句柄再启动新句柄。
- **普通观察者异常**：本轮补 Rect Listener 的 Exception 隔离与 LogUtils 诊断，防止第一个 onStart/onCancel/onEnd 观察者抛错使 driver 未启动/停止或物理等待不释放；这比 OEM 直接传播更强，不吞 Error。

## 4. 原 10 项风险逐条验收

| 风险 | 当前状态与证据 |
|---|---|
| ① AnimType 3/7 | ✅此前已补七值；这次核对源码，并用实际类型分支选择 size/倍率策略。 |
| ② maybeEnd 双轨 | ✅句柄已有逻辑/物理分离；本轮原生六轴验证 cancel 先 logical、下一 tick actual，MultiAnimatorSet 等 actual 才结束。 |
| ③ 四路 marshal | ✅start/cancel/skip/reverse 均由句柄分发至固定 owner；新 driver 用公开 AndroidX scheduler，真实 HandlerThread 回归证明数值帧可在后台运行。 |
| ④ cancel 下一帧 | ✅不再只依赖模拟 Driver；真实六轴 requestStop 下一 tick 执行，另提供 dispose 即时最终清理。 |
| ⑤ 六自由度参数 | ✅六个独立 native springs；改变 centerX stiffness 不改变 Y/alpha 响应，覆盖独立参数、六轴终值/速度和一致帧。 |
| ⑥ rate stiffness | ✅显式宿主 duration/light/evaluation 输入，对齐倍率平方及 0→1、evaluation×0.36、GESTURE_TO_DRAG 跳过策略；系统 scale 由 AndroidX 自行应用，不重复注入同一倍率。 |
| ⑦ width/height | ✅两种 size 坐标与三档 tracking 共六组合，反向切换保位置/转换速度。高度模式 bottom=trackedY-height，未机械复制原厂反编译代码用 width 偏移的可疑表达式。 |
| ⑧ alpha delay | ✅延迟独立起步且参与完成等待；skip/dispose 可以结束等待，不会永久卡在 delayed 轴。 |
| ⑨ tracking | ✅TOP/CENTER/BOTTOM 的起点/目标锚点与输出几何均覆盖。 |
| ⑩ minWidth/ratio bounds | ✅minimumSize 和可选 ratio 区间钳制，弹性过冲不产生负尺寸/非正 ratio；输入/派生参数先校验。 |

## 5. 原回移建议逐项处置（10 + 7 项）

| 原建议 | 本轮处置 |
|---|---|
| 6.1-1 七值 AnimType | ✅保留已实现的七值，不再改 ordinal 或添加伪枚举。 |
| 6.1-2 maybeEnd/序列化 | ✅既有线程/事件协议保留；加真实六轴结束等待、观察者异常隔离、最终 native 注册清理。 |
| 6.1-3 四路线程切换 | ✅句柄转发 + driver 显式 native scheduler；不把普通 AsyncSpringAnim 自动当成后台可用。 |
| 6.1-4 MultiDynamic+六 SpringHolder | ✅以六个 AndroidX SpringAnimation 和自有聚合 Run 实现对应可移植物理功能，不复制隐藏平台类名；Demo12 已真实消费。 |
| 6.1-5 getRateStiffness | ✅显式策略有数值回归；未接 AppLaunchAnimSpeedHandler/ROM 服务。 |
| 6.1-6 tracking/minWidth/ratio | ✅几何与钳制已落地，不是只增加参数字段。 |
| 6.1-7 alpha delay | ✅真实延迟及所有结束路径已覆盖。 |
| 6.1-8 copyNextAnimState | ✅纯预测 + createContinuation 位置/六轴速度/progress 接续；正常/反向方向保持。 |
| 6.1-9 updateEndTargetRectF | ✅活动目标更新并收敛到新 rect/radius，更新时不清零速度。 |
| 6.1-10 updateMinVisibleChange | ✅开窗及显式 tablet/fold profile 作用于实际 native springs；反向也刷新策略。不自动猜测设备形态、不移植 OEM Adaptive 联动启发式。 |
| 6.2-1 SpringForce 三支解 | ✔️播放使用 AndroidX 既有实现；仅纯预测使用独立数学解，不用反射推进或拷贝 native 私有 MassState。 |
| 6.2-2 initFirstFrameForBreakScene | ✔️不接 RectTransformHelper/BreakParam；预测接续不等于完整 Launcher 中断业务。 |
| 6.2-3 mapRatioVelocity | ✅本轮实际需要 size 坐标转换，补乘积/商求导；并保留 ratio 速度。没有照搬未知表面坐标映射。 |
| 6.2-4 六速度日志构造器 | ✔️Frame 已暴露六轴速度，Demo12 显示/记录接续；不添加 OEM 字符串日志构造 API。 |
| 6.2-5 SpringAnimReflectUtils | ✔️不反射；公开 setScheduler、SpringForce 配置与 equilibrium API 足够。 |
| 6.2-6 六轴更新监听 | ✅旧“依赖 SurfaceControl、无消费者”结论失效；新增 immutable Frame sink，Canvas Demo12 消费，View 操作始终回主线程。 |
| 6.2-7 匿名结束监听器 | ✔️用 Kotlin 回调/Run 身份实现一次性结束，不复制反编译匿名类。 |

## 6. 与 OEM 的明确差异

1. 自有 app VSYNC 触发 + AndroidX uptime/duration-scale 内核，不是平台隐藏 MultiDynamicAnimation/SF 帧源；没有窗口事务或 launcher 私有性能策略。
2. 公共 Driver/Config/Frame 是本库 API，不是 OEM Java 构造器/方法/二进制替换件。裸 CustomRectFSpringAnim 不自动创建物理引擎。
3. width/height 坐标切换采用公开 API 重启该轴，保留一次 first-frame warm-up；预测必须指定假设的下一 tick delta。Demo 使用 16ms 示例，不是探测真实显示器下一 VSYNC 或刷新率。
4. 构造输入、minimumSize/ratio/radius/alpha 边界、观察者异常隔离是本库防护；未保证所有原厂过冲、Adaptive 联动、业务参数表逐位一致。
5. 可在动画线程计算数值，不意味着 View 可以在后台写，也不证明主线程受阻时 Canvas 仍能流畅呈现。设备/Perfetto 回归尚未进行。

## 7. 本轮验证

- 原 Rect 观察者错误的定向回归先因 `start observer` 异常失败（`.gradle/review-ordered-21-observer-red.log`），隔离后其他观察者/driver/actual barrier 均继续。
- 接续进度、反向终态及同尺寸位移预测先有 3 条失败（`.gradle/review-ordered-21-progress-red.log`）；修复 progress base/方向与预测 settlement 后，58 条定向测试及 Demo 构建通过。
- 最终强制重跑 **Debug/Release 各 260 tests、37 类、0 failures/errors/skipped**；Demo Debug 成功，82/82 tasks executed、2m（`.gradle/review-ordered-21-final.log`）。114 份源码/测试/两模块配置 SHA-256 前后一致；`git diff --check` 与相对文档链接检查通过。
- 未进行设备/Perfetto 验证；Lint 未完成，既有 SDK/Gradle/私有注解/deprecation 警告保留。OPPO、AGENTS.md 和无关文件未改；无提交/推送。
- [RectSpringDriverTest](../../lib/src/test/java/com/asyncanimator/anim/RectSpringDriverTest.kt) 覆盖真实 AndroidX 物理、六轴参数/几何、预测/接续、线程与资源清理；[RectAnimationLifecycleTest](../../lib/src/test/java/com/asyncanimator/anim/RectAnimationLifecycleTest.kt) 验证句柄与观察者。

完成定义：逐条落实或注明不移植理由，且新物理 driver、句柄、聚合与 Demo 接线经验证；不把本库数值/几何能力包装成完整 OPPO 系统窗口转场。
