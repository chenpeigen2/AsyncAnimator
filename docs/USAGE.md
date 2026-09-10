# AsyncAnimator lib — 公开 API 使用文档

本库是 OPPO ColorOS 15 Launcher（`com.android.launcher` 15.8.24）"独立动画线程"方案的 Kotlin 重实现 / 演示库。
与原厂代码的逐类对比见 `docs/review/vs-oppo-01` ~ `34` + `SUMMARY-vs-oppo*.md`（类对应表、保真度评估、有意简化与已知差异）。

本文是常用 API 与生命周期指南，不是穷举的可见性/ABI 清单。以源码修饰符为准；Kotlin `internal` 不对 demo Kotlin 源码开放，也不是 Java 安全边界。帧调度/Pending/APC/续行内核保持 internal，公开 LogUtils 和 nullable listener 族不能因包名被归为内部。

## 整体分层

```
core (internal)    AnimationHandler / TickScheduler / ChoreographerTickScheduler ← 公开 Choreographer 帧源，非 SF-VSYNC
core               Trace（操作 internal）/ LogUtils（public）← stderr 诊断，不是 Perfetto
        ↑ installThreadScheduler
thread             AnimationControlThread                ← "launcher.anim" 独立线程
                   LooperExecutor / Executors            ← 跨 Looper 执行器（MAIN / ANIM_CONTROL）
anim               AsyncValueAnimator / AsyncAnimCallbacks ← 跨线程安全动画
                   AsyncSpringAnim                       ← View 属性弹簧（androidx 物理 + 线程 marshal）
                   ActualEndAnimListener                 ← "物理帧播完"回调基类（双轨结束）
                   CustomRectFSpringAnim                 ← 转场句柄 / 显式 Driver
                   MultiAnimatorSet                       ← 四通道聚合（主/异步/弹簧/Rect）
anim (internal)    OplusValueAnimator / RecordInputInterpolator ← 续行动画
playback (internal) PendingAnimation / AnimatorPlaybackController / PropertySetter / Interpolators
playback           NullableAnimatorListener / NullableAnimatorListenerAdapter ← public
control            AnimationController / AnimationState / … ← 转场状态机 + 超时 listener
seq                AnimationSeqHelper / AnimSeqTimeStamp ← SeqId 防抖
manager            OplusAnimManager（Ext 工厂）/ AnimationFeatureHelper（RUS 灰度容器）
com.android.launcher3  LauncherAnimationRunner          ← 类型壳（RemoteAnimationTarget）
```

---

## anim

### AsyncValueAnimator — 跨 Looper 安全的 ValueAnimator

对应原厂 `com.android.quickstep.util.animation.AsyncValueAnimator`（review 01）。

`start()`/`cancel()`/`end()` 自动判断"当前线程 vs 目标 Looper"，不一致时 marshal 到 [executor] 所在线程；
listener 始终回主线程 fire（见 AsyncAnimCallbacks）。

| 成员 | 签名 | 说明 |
|---|---|---|
| `executor` | `var executor: LooperExecutor` | 动画执行器，默认 `Executors.MAIN_EXECUTOR` |
| `asyncAnimCallbacks` | `val asyncAnimCallbacks: AsyncAnimCallbacks` | listener 容器（回主线程派发） |
| `start/cancel/end` | `override fun` | 线程安全版生命周期 |

最小示例（Demo3，主线程执行器 + 业务 listener）：

```kotlin
val anim = AsyncValueAnimator()
anim.setFloatValues(0f, 1f)
anim.executor = Executors.MAIN_EXECUTOR
anim.duration = 500
anim.asyncAnimCallbacks.addListener(object : NullableAnimatorListenerAdapter() {
    override fun onAnimationStart(animator: Animator) { /* 在主线程 */ }
    override fun onAnimationEnd(animator: Animator) { /* 在主线程 */ }
}
anim.start() // 任意线程调用都安全
```

### AsyncAnimCallbacks — listener 容器 + 跨线程派发器

对应原厂同名类（review 01）。公开注册/诊断/释放入口：

```kotlin
fun addListener(l: NullableAnimatorListener?)
fun removeListener(l: NullableAnimatorListener?)
fun setAnimType(type: CustomRectFSpringAnim.AnimType)
fun dispose()
```

setAnimType 只提供可选诊断类型，不选择引擎；未设置为 UNSPECIFIED。事件投递前捕获 generation/id/type，排队后更改上下文不改写旧事件名字；dispose 清注册、id/type 并丢弃旧代排队派发，不取消 animator 本身。

对齐原厂的派发语义（均为 internal 实现细节）：懒删除（remove 置 null 槽）、add 去重、
派发前把 `animationId` 同步进 adapter、**快照迭代**（派发前压缩 null 槽 + 拷贝，
消除跨线程并发 add/remove 的 CME 窗口）、**异步消息投递**（`Message.setAsynchronous(true)`，
sync-barrier 期间不阻塞）、**双轨结束**（`onAnimActualEnd` 只派发给 ActualEndAnimListener）。

### ActualEndAnimListener — "物理帧播完"回调基类

对应原厂 `com.android.quickstep.util.animation.ActualEndAnimListener`（review 01 §②-C2）。
双轨结束语义：`onAnimationEnd` 是逻辑结束（UI 线程即发），`onAnimActualEnd` 是物理结束
（动画线程帧循环真的停了，cancel/end 都会走到），用于资源清理：

```kotlin
object : ActualEndAnimListener() {
    override fun onAnimActualEnd(animator: Animator) { /* 帧循环已停，做清理 */ }
}
```

### LooperExecutor / Executors

对应原厂 `com.oplus.basecommon.thread.LooperExecutor` / `Executors` + `OplusExecutors.ANIM_EXECUTOR`（review 01）。

`LooperExecutor` 构造器为 internal；`execute`/`post`/`isCurrentThread` 公开，
`postAsync` 以异步消息投递（对齐原厂 `Utilities.postAsyncCallback`，可穿透 sync-barrier）。
`getHandler()` / `getLooper()` / `getThread()`（别名 `getTargetThread()`）返回底层绑定；null-Handler 兜底时均返回 null。
`setThreadPriority(priority)` 修改绑定的 HandlerThread，而非调用者线程；主线程或无绑定执行器明确拒绝。业务一般只使用预定义单例：

```kotlin
Executors.MAIN_EXECUTOR         // 绑定主 Looper；JVM 单测下退化为就地执行
Executors.ANIM_CONTROL_EXECUTOR // 绑定独立 launcher.anim 线程
```

### AsyncSpringAnim — 弹簧生命周期封装

对应原厂 `OplusAsyncSpringAnimWrapper` 的调度骨架。该封装仅转发 start/cancel/skipToEnd 等操作，并把结束通知送回主线程；**未配置 AndroidX 的后台帧调度器，也未统一接管 View 写入**。不要把 `supportAnimThread = true` 当作后台 View 弹簧可用的保证。

```kotlin
val spring = SpringAnimation(card, SpringAnimation.TRANSLATION_Y).apply { spring = SpringForce(0f) }
val anim = AsyncSpringAnim(spring, supportAnimThread = false)
anim.addEndListener { _, canceled, _, _ -> /* 主线程 */ }
anim.start() // View 弹簧在主线程启动；后台路径还需单独验证
```

`AsyncSpringAnim.cancel()` 在命令到达实际 AndroidX owner 后同步取消，保留当时数值并通知 canceled=true；跨线程投递本身不是同步操作。`skipToEnd()` 只请求终态，已过首帧后在下一物理帧结束且 canceled=false；若尚未首帧，AndroidX 会先执行一次不积分的 warm-up，再处理结束。零阻尼不能 skip，会抛 UnsupportedOperationException，但可 cancel。这里没有新建 EndReason/endImmediately API，skip 与自然结束都使用 native canceled=false。

普通 wrapper 不聚合六轴、不安装后台 scheduler；需要一致六轴快照、下一 tick cancel/skip 协议时使用下述 RectSpringDriver。其自有 cancel 不再积分最后一步，skip 直接输出目标后清理；这些数值细节不同于 OPPO MultiDynamicAnimation 在请求结束的 tick 仍调用 SpringHolder 积分。

### CustomRectFSpringAnim — 线程归属与双阶段结束

保留 OPPO 七个 `AnimType` 名称：`OPEN_FROM_HOME`、`REMOTE_CLOSE_TO_HOME`、
`REMOTE_CLOSE_TO_HOME_ASSISTANT`、`GESTURE_TO_DRAG`、`SWIPE_TO_HOME`、
`SWIPE_TO_HOME_ASSISTANT`、`REVERSE_TO_OPEN`。已移除旧的库自创 `RECENTS_TRANSITION` / `APP_LAUNCH`。

- 单参数构造是状态机句柄：`CustomRectFSpringAnim(AnimType.SWIPE_TO_HOME)`。
- 用于聚合播放时必须提供 `Driver` 或 `Animator`：`CustomRectFSpringAnim(type, geometryAnimator)`；裸句柄传入 MultiAnimatorSet 会明确失败，不假装播放完成。
- `Driver.start(onActualEnd)` 必须在物理完成时回调；cancel 只是请求，不能提前释放结束等待。驱动需支持 cancel、skipToEnd 和 clearEndCallback。
- Driver 的 `supportsAnimationThread` 默认为 false；明确支持时默认动画线程，可在开始前 `setAsyncStart(false)`。Animator 适配器只允许主线程，不接受后台 opt-in。
- start/cancel/skipToEnd/reverseToOpen/dispose 先纠偏到主线程，driver 操作走该轮固定 owner。配置 animationId、线程选择及增删监听器须在主线程；实际结束前不可改 owner/id。
- `Listener` 提供 onStart/onCancel/onEnd/onActualEnd，参数为非空句柄及不可变 Event（animationId、runId、cancelled），均回主线程；跨线程回调使用异步消息。
- cancel/skip 发逻辑结束，`isRunning` 直到实际结束才变 false；`justNotifyEndCallback()` 只发逻辑 end，让 driver 继续。实际清理完成后可复用；dispose 是不可复用的最终销毁，撤销迟到事件。
- `reverseToOpen(target, endRadius, afterReverse)` 要求 `ReversibleDriver`；RectF 在投递前复制，由 driver 真正修改几何/弹簧目标，之后主线程执行 afterReverse。不支持的 adapter 抛异常，不静默跳过。
- 本库比 OEM 部分 maybeEnd 路径更严格：只有 driver 报实际结束才释放聚合等待。自定义 driver 必须履行该契约，否则聚合不会结束。
- Animator 重载只是外部几何适配；可选 `RectSpringDriver` 已提供六轴 AndroidX 数值/几何引擎。没有移植 RectTransformHelper、SurfaceControl 或隐藏 SF 帧源。
- 普通 Listener Exception 会按 LogUtils 门控记录并隔离，不能阻断其他监听器或 driver 的 start/stop/实际结束等待；不是 OEM 原始异常传播语义。DisposableDriver 可提供最终释放，避免 dispose 等待下一帧。

### RectSpringDriver — 六轴几何与预测接续

这是可运行的 `ReversibleDriver` / `DisposableDriver`，不是只保存字段的对象。六个 AndroidX 1.1 `SpringAnimation` 分别驱动 centerX、trackedY、size、height/width ratio、radius 和 alpha，再输出一份不可变 `RectSpringFrame`。每次 `frame.rect` 返回独立 RectF。

```kotlin
val type = CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME
val engine = RectSpringDriver(
    RectF(0f, 0f, 300f, 500f), RectF(400f, 500f, 460f, 560f),
    animType = type,
    config = RectSpringConfig(
        tracking = RectSpringConfig.Tracking.CENTER,
        centerX = RectSpringConfig.Spring(stiffness = 220f, dampingRatio = 0.75f),
        alphaStartDelayMillis = 180,
        limitAspectRatio = true
    ),
    startRadius = 24f, targetRadius = 8f,
    onUpdate = { frame -> /* 在 owner 上收到一致帧；渲染 View 必须回主线程 */ }
)
val animation = CustomRectFSpringAnim(type, engine)
animation.setAsyncStart(false) // 本例所有操作/回调在主线程；true 则数值引擎在 launcher.anim
animation.start()
animation.reverseToOpen(RectF(20f, 20f, 500f, 700f), 24f) { /* 主线程回执 */ }
// owner 上可更新目标，或建立尚未启动的下一帧接续引擎：
engine.updateEndTargetRectF(RectF(40f, 40f, 400f, 600f), 16f)
val predicted = engine.copyNextAnimState(16)
val nextEngine = engine.createContinuation(RectF(0f, 0f, 500f, 700f), 16) { frame -> /* 渲染 */ }
animation.dispose() // 释放旧轮，再把 nextEngine 包在新句柄中启动
```

- **调度**：仅为本 driver 的六个 native springs 配置公开 `FrameCallbackScheduler`；帧源负责触发，AndroidX 内核使用 uptime 计算 delta。不是 OPPO MultiDynamicAnimation 的隐藏平台时钟或 SF-VSYNC。
- **owner**：直接 driver 的 start/cancel/skip/reverse/update/predict/dispose 属于该轮启动线程；用句柄转发生命周期。`currentFrame` 可以跨线程读取；它不提供跨线程可变的 RectF。
- **结束**：cancel/skip 的实际结束在下一次 tick；alpha 延迟中的轴也计入等待。skip 可结束零阻尼轴。dispose 则立刻在 owner 上撤订阅、取消并排空 AndroidX 清理任务，释放 duration-scale 注册，不需要等 VSYNC；不会再发完成事件。
- **几何**：TOP/CENTER/BOTTOM 锚点；按 AnimType 和开始/终点 ratio 选择 width 或 height 作为 size。反向改变坐标时转换 size 及速度，防止把旧 width 数值误当 height。该轴通过 AndroidX 重启，保留一次 first-frame warm-up，不宣称逐帧与 OEM 一致。
- **配置**：六轴 stiffness/damping/minVisibleChange 独立；默认 tracking=TOP、ratio 区间限制关闭，minimumSize=0.1 防退化。radius/alpha 有显示安全边界。开窗及显式 tablet/fold flags 会应用原厂形式的精度策略；不是自动读取 ROM/RUS。
- **时长**：AndroidX 自己应用系统 animator duration scale。`durationMultiplier` 是额外宿主策略，stiffness 按倍率平方缩放（0 按 1 处理），light/evaluation 是显式输入，GESTURE_TO_DRAG 不做该额外缩放；不要把同一个系统倍率再次传入。
- **预测**：闭式预览只计算副本，不推进 native springs；假定下一 tick 的 uptime delta、目标和 duration scale 不变。延迟/首帧未启动的轴保持初值。createContinuation 带六轴速度并处理 size 坐标转换，不自动取消旧引擎。
- **边界**：不做 RectTransformHelper/远程窗口事务、OEM Adaptive 阈值启发式或系统手势业务；不表示 `AsyncSpringAnim` 的任意 View 属性已支持后台运行。完整接线示例是 Demo12。

### MultiAnimatorSet — 四通道聚合

主线程构造、配置和调用；四条独立完成条件为：主线程 AnimatorSet、`launcher.anim` AnimatorSet、主线程 AndroidX springs、rect driver actual-end。全部结束后才消费一次 reset callback 和发送聚合 end。

```kotlin
val group = MultiAnimatorSet(CustomRectFSpringAnim.AnimType.OPEN_FROM_HOME)
group.play(fadeAnimator)                    // 主线程 AnimatorSet
// numericAnimator 的 update listener 不得写 View；只写可安全发布的数值
group.play(true, numericAnimator)           // launcher.anim AnimatorSet
group.play(viewSpring)                     // 主线程 AndroidX SpringAnimation
group.play(CustomRectFSpringAnim(CustomRectFSpringAnim.AnimType.OPEN_FROM_HOME, geometryAnimator))
group.setViewStateResetRunnable { animationId -> /* 四条通道全结束，主线程 */ }
group.start()
// 页面退出：group.destroy()，不销毁进程级 launcher.anim 线程
```

- `play(isAsync, animator)` 选择线程；**`play(animator, startImmediately)` 是 live-add 重载，不是线程选择**。live-add 动画随聚合结束被取消，不增加独立结束轨道。
- mask：AnimatorSets = `1`、springs = `2`、rect = `4`、全部 = `7`；`cancel()` / `end()` 默认全部。
- `cancelAllAnimExceptSpringAnim()` / `endAllAnimExceptSpringAnim()` 使用 mask 5：移除 spring 的聚合监听，让其独立继续；不会取消它。被摘除的 spring 后续生命周期归调用方负责。
- cancel 通知与物理 end 分离；async 完成回主线程后再更新聚合状态。库 listener 收到实际的主 AnimatorSet，而不是 OPPO 的 null 参数。
- `hasRequestCancel` 是 volatile 取消请求信号，可供后台数值消费者读取；其他集合查询/操作仍限主线程。新一轮 start 会复位，不能把它当永久销毁标记或当前帧回收保证。
- 聚合 start/cancel/end/reset 观察者的普通 Exception 按 LogUtils 策略记录后隔离，其他观察者与收尾继续；Error 和实际 Animator/driver 故障不在此恢复保证内。
- 完成前消费旧 callback/live 清理集合并捕获完成 ID；外部回调启动新轮后，旧 cancel/end 不得再停止新轮的 spring/Rect。旧轮不再向新轮发送聚合 end。
- Demo 9 的开/关窗口已走此聚合器；Canvas 几何与 Recents 辅助按钮仍是舞台示意，不是系统转场或 OPPO trace 复刻。
- 不支持 OEM multi-app merge、prestart/intercept helper、远程 TaskStateHelper 总线或私有调度增强。

---

### OplusValueAnimator — 内部续行实现

此类为 internal，不是 demo 跨模块 API。timeController 用 LINEAR 推进 CURRENT_FRACTION，值动画保留自己的业务插值器；property/target 重绑实际生效。ofFloat(isAsync, …) 只选择 wrapper，不切线程。

- setFloatValues/setIntValues 创建对应类型的值定义，支持 Float/Int 切换；替换整个定义时应在之后配置 custom evaluator，或直接 setValues 提供已配置的 holders。
- generateContinuationAnim 克隆参数和实际 value holders，避免源动画后续改值影响续行；整数值仍输出 Int。AnimParam 端点仍是 Float，不是泛型对象工厂。
- duration 参数 > 0 覆盖 controller 播放时长；<= 0 使用复制 param 的有效时长，否则使用平台默认值。currentPlayTime/isRunning/duration 查询委托实际 controller。
- 不移植四路 Recents 业务编排、六轴物理或通用对象 evaluator 工厂；Demo 5 不证明完整续行链或速度连续。

### 内部帧源安装与换源（lib 开发）

`AnimationHandler.installThreadScheduler` 仅限该线程首次获取 instance 前；null、重复安装、全局 testHandler 覆盖均明确报错。已有实例使用 replaceThreadScheduler：同源无操作，异源退订自身旧回调，以新代次注册新源；不停止旧源的其他订阅者，迟到旧脉冲失效。当前帧继续遍历，新帧相位由新源决定，不承诺无缝跨源刷新。所有操作遵循 owner 线程。

handler/scheduler 对 callback 异常保持隔离；这是库的明确扩展，不是原厂抛错传播语义。此帧核只服务使用它的本库调用，不会替换 Android 平台 Animator 内部帧源。

## thread

### AnimationControlThread — 独立动画线程（"launcher.anim"）

对应原厂 `OplusExecutors.ANIM_EXECUTOR` 的内联创建 + init lambda（review 01 §①）。

```kotlin
AnimationControlThread.THREAD_NAME   // = "launcher.anim"，systrace/logcat 对照用
```

单例 `instance` 为 internal：线程随 `Executors.ANIM_CONTROL_EXECUTOR` 首次加载拉起，
`onLooperPrepared` 内完成帧源安装（`AnimationHandler.installThreadScheduler`）+ 线程优先级兜底
（优先级字面量 -19，对齐原厂 `OplusExecutors.java:95`）。

最小示例（Demo10，动画跑在独立线程）：

```kotlin
val anim = AsyncValueAnimator().apply {
    executor = Executors.ANIM_CONTROL_EXECUTOR // start/帧推进都在 launcher.anim
    asyncAnimCallbacks.addListener(...)        // listener 仍回主线程
}
anim.start()
```

---

## control

### AnimationController — 转场状态机

对应原厂 `com.oplus.quickstep.utils.AnimationController`。状态矩阵已有回归守护；延迟启动的最新实现与环境适配见 [决策树修复记录](review/2026-09-09-launch-decision-fixes.md)，不沿用早期 review 中已修复的差异描述。

| 成员 | 签名 | 说明 |
|---|---|---|
| `animState` | `var animState: AnimationState`（private set） | 当前状态 |
| `addRecentsAnim` | `(anim: CustomRectFSpringAnim, controller: Any?, targets: Array<out Any?>?)` | recents 动画注册 + 状态转移 |
| `appLaunchAnimStartOrEnd` | `(isEnd: Boolean, factory: RemoteAnimationFactory?, targets: Array<LauncherAnimationRunner.RemoteAnimationTarget>?)` | app 启动动画事件 |
| `canFinishRecentsAnim` | `(anim, animationId): Boolean` | launch 非空、Seq 窗口、logical-only end 否决；单 Recents 校验 ID（-1 为未分配兼容值） |
| `setOnAppExit` | `(AppExitScene?)`（基类签名仍为 Any?） | 满足三键导航/横屏/低动画/非大屏展开条件才注册 1500ms；null 无事件 |
| `setOnceGestureProcessing` | `(GestureScene?)`（基类签名仍为 Any?） | 快照开始/更新手势；null 结束；自动注册 1500/2500ms 场景 timeout |
| `swipingUpActivityPkg` | `val: String?` | 优先 baseActivityPackage，回退 topActivityPackage；手势结束/reset 清空 |
| `reset` / `cleanUpRecentsAnim` | `fun` | 归位 NONE / 清理 |
| `isOpeningAnim` / `hasRecentsAnim` / … | `val: Boolean` | 状态查询（其余见 DefaultAnimationController） |
| `registerSpecialSceneExitTimeOutListener` | `(timeoutMs: Long)` | 三种超时 listener，独立运行 |
| `dispatchTaskStateChange` | `(type: TaskStateChangeTimeOutListener.Type)` | 主线程交付匹配的真实/模拟事件；和 timeout 共享一次性完成，Default 基类 no-op |
| `registerTransitionFinishTimeOutListener` | 同上 | |
| `registerOverviewContinuationTimeOutListener` | 同上 | 仅注册兜底定时器，不表示续行正在运行 |
| `setAppToOverviewContinuationState` | `(running: Boolean)` | true 标记运行并注册 100ms 兜底；false 注销且取消本场景挂起请求 |
| `setBetweenTransitionEndAndFinish` | `(v: Boolean)` | 设置 transition end/finish 之间的状态 |
| `setBetweenAppExitTransitionEndAndFinish` | `(v: Boolean)` | 仅 navigation landscape exit 状态下修改对应标志 |
| `isStartActivityBetweenTransitionEndAndFinish` | `val: Boolean` | transition 标志为 true 且确有挂起启动动作才为 true |
| `specialSceneExitTimeOutListener` 等三个 | `val: TaskStateChangeTimeOutListener?` | 未注册为 null（`null` = 当前不在该场景） |
| `addOnAnimStateChangeListener` | `(listener: OnAnimStateChangeListener?)` | 状态变更监听（fun interface） |

最小示例（Demo6）：

```kotlin
val controller = AnimationController()
controller.addOnAnimStateChangeListener { old, new, _ -> log("$old -> $new") }
controller.registerSpecialSceneExitTimeOutListener(1500L)
controller.addRecentsAnim(
    CustomRectFSpringAnim(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME), null, arrayOf())
// 交付匹配事件；不交付时才由 Handler 定时器自然兜底：
controller.dispatchTaskStateChange(TaskStateChangeTimeOutListener.Type.ON_LAND_SCAPE_SCENE_EXIT)
controller.reset()
```

#### 设备/手势快照（不依赖 OPPO 类型）

```kotlin
// 这些值必须由集成方从设备/手势系统读取；不要用常量冒充实际设备信息。
controller.setOnAppExit(AppExitScene(threeButtonNavigation, landscape, adaptiveLowAnimation,
    largeDisplayInLargeMode))
controller.setBetweenAppExitTransitionEndAndFinish(true) // 真实进入该窗口时才设置
controller.setOnceGestureProcessing(GestureScene(landscape = landscape, splitScreen = splitScreen,
    recentContinuation = continuation, tablet = tablet, homeAndOverviewSame = sameHome,
    baseActivityPackage = basePackage, topActivityPackage = topPackage))
controller.setOnceGestureProcessing(null) // 手势结束，不再表示开始
```

- 非 null 参数必须是对应快照类型；传入原始 Context/OEM GestureState 会明确拒绝。基类保留 Any? 只是兼容接口形状，不代表会自动解析 OEM 对象；feature-off 基类仍为 no-op。
- AppExit 仅注册场景，不自动设置 between 标志，也不伪造 landscape gesture。横屏 split/continuation 注册 special 1500ms，竖屏及普通平板分支注册 transition 1500ms。
- 普通横屏手机手势开始不抢先注册；结束且 home/overview 相同时才注册 special 2500ms。设备快照由调用方保证及时，库不读取私有系统服务。
- `canFinishRecent: () -> Boolean` 可注入实际序列策略；默认读取 manager 的 Seq helper，每次 Recents 完成查询都会重新评估。controller 状态操作、finish callback setter、observer 操作和事件入口先检查主线程；直接回调不自动 marshal，timer 兜底在主线程。

#### 延迟启动环境与完成/取消

构造器仍可无参调用；可按需注入 `isTablet: (Any?) -> Boolean` 和 `isOverviewContinuationRunning: (() -> Boolean)?`。默认 `isTablet` 使用传入 Android Context 的 `smallestScreenWidthDp >= 600`（没有 Context 时 false），不是原厂设备特性服务。注入的运行态函数每次第三层决策都会读取；未注入时由 `setAppToOverviewContinuationState` 维护本地状态。

```kotlin
val controller = AnimationController()
controller.setAppToOverviewContinuationState(true) // 开始模拟续行 + 100ms 兜底
val waiting = controller.delayStartActivityIfNeed(context, intent, null) {
    // 所选场景完成事件或超时到达时执行启动
}
// waiting == false：由调用方自行启动，controller 不会调用 action。
// 若放弃等待：
controller.setAppToOverviewContinuationState(false)
```

- 搜索场景识别 `android.search.action.DOCK_SEARCH`、`com.oppo.quicksearchbox.action.Dispatch` 或 `source=drawer_search`。第二层中的 caller predicate 每次执行一次，即使搜索/分屏项已为 true。
- 纯横屏手势只在非平板时等待；导航模式退出及分屏条件仍独立参与 OR，不能对平板整体提前返回。
- 真实动画完成时需交付匹配的 `ON_APP_TO_OVERVIEW_CONTINUATION` 事件；集成方在主线程调用 `controller.dispatchTaskStateChange(type)`；公开 listener 的 `onTimeOut(type, duration)` 仍保留为兼容入口。该事件会清理当前场景并执行其持有的挂起 action。
- `setAppToOverviewContinuationState(false)` 是取消/注销，不会执行等待的 action，也不会清除其他场景的请求。匹配事件已经执行并可能重入新动画后，不要再无条件注销它。
- 低优先级场景超时只清自己的状态，不会消费高优先级场景的挂起请求。上述状态操作和事件交付在修改前检查主线程；owned listener 的 event/dispose 同样检查，错误调用不会消费挂起动作。独立三参 timeout 保留调用线程回调语义；没有新增全局系统事件总线。

### AnimationState — 12 状态枚举

对应原厂 `AnimationController$AnimationState` 内部枚举（review 03；lib 提升为顶层）。
每个状态携带 `withTaskbarAlignment` / `taskbarAlignmentToLauncher` 两个标志（taskbar 对齐语义）。

### DefaultAnimationController — no-op 基类

对应原厂同名类（review 03）。本地工厂关闭后，新查询返回 Default，转场写入为空实现、查询返回默认值；监听注册/移除/显式通知仍有效。delayStartActivityIfNeed 返回 false 且不执行 action，调用方负责立即启动；不能把它与 Default Seq 当场执行 action 的契约混用。

### OnAnimStateChangeListener（fun interface）

```kotlin
fun interface OnAnimStateChangeListener {
    fun onAnimStateChanged(oldState: AnimationState, newState: AnimationState, runningTask: Any?)
}
```

fun interface 提供明确 Kotlin/Java SAM 类型。函数类型同样是对象；保存注册的 listener 并传回 remove，重复创建相似 lambda 不保证相等。容器使用 equals 语义，不应把身份问题归因于 typealias。

### TaskStateChangeTimeOutListener（class，自管理超时）

对应原厂 `TaskStateHelper$TaskStateChangeTimeOutListener`（review 03）。构造即向主线程
Handler `postDelayed` 一个超时兜底：`onTimeOut(type, duration)`（事件）或超时任一先到，
都会执行一次 option 并 `dispose()`，防止 startActivity 永久挂起。

```kotlin
class TaskStateChangeTimeOutListener(
    private val type: Type,
    duration: Long,
    private val option: () -> Unit
) {
    enum class Type { ON_LAND_SCAPE_SCENE_EXIT, ON_TRANSITION_FINISH, ON_APP_TO_OVERVIEW_CONTINUATION }
    fun onTimeOut(type: Type, duration: Long)
    fun dispose()
}
```

### RemoteAnimationFactory / LauncherAnimationRunner

`RemoteAnimationFactory` 是本地动画工厂/生命周期身份，不兼容 OEM 九个 default + 创建回调的接口。`createAnimation()` 与 `onAnimationFinished()` 由宿主自行调用；Controller 只记录 factory 实例和状态，不自动创建、取消或调用工厂完成方法。Demo6/9 使用真实 factory 实例登记 start/end，实际播放另由页面/聚合器负责。

嵌套的 `LauncherAnimationRunner.RemoteAnimationTarget(taskId, leash)` 是非 Parcelable 本地描述符，`Any? leash` 不是可直接操作的 SurfaceControl。它与平台 `android.view.RemoteAnimationTarget` 不同，真实平台数组不能直接传入。本地 null、空和非空描述符数组均可传给 Controller，当前实现不读取 target 字段。

没有 Binder runner、AnimationResult 系统完成链、remote-ready/merge/prestart/input/icon registry 协议；不存在的方法不代表运行时返回 false/no-op。不要为接平台对象只补 Any? 字段或空 hook；需独立类型化 adapter、线程与所有权契约。

---

## seq

### AnimationSeqHelper — Recents 动画 SeqId 防抖

可注入 `startingSurfaceSupported` / `interruptionSupported` 查询：只有两者均启用才应用 300/500ms 防抖，关闭任一即放行。默认 starting-surface=true，interruption 取 manager 的本地策略（不是自动读取 OEM feature 服务）。新请求直接放行时也会撤销旧排队 finish，避免关 feature 后旧回调迟到。


对应原厂 `com.oplus.quickstep.utils.AnimationSeqHelper`（review 03）。

| 成员 | 签名 | 说明 |
|---|---|---|
| `addSeqId` | `(bundle: Bundle?)` | 全局单调 seqId 写入 Bundle（跨进程同步） |
| `canFinishRecent` | `val: Boolean` | 500ms 内是否刚结束过 |
| `canInterceptGesture` | `val: Boolean` | 300ms 内是否刚启动过 app |
| `delayFinishRecents` | `(action: (() -> Unit)?): Boolean` | 返回 true = 已挂起延迟执行 |
| `clearFinishRecentsRunnable` | `()` | 取消挂起 |
| `updateNextFinishSeqIdIfNeed` / `getNextFinishSeqId` | | (controller, seqId) 对 |

`DefaultAnimationSeqHelper` 为 no-op 基类（Ext 模式语义本体，同 DefaultAnimationController）。

最小示例（Demo7）：

```kotlin
val seqHelper = AnimationSeqHelper()
AnimSeqTimeStamp.updateLastRecentFinishTime()  // 模拟"刚结束过 recents"
val delayed = seqHelper.delayFinishRecents { /* 500ms 窗口内被延迟执行 */ }
seqHelper.clearFinishRecentsRunnable()
```

### AnimSeqTimeStamp — 全局时间戳协调

对应原厂 `com.android.systemui.shared.system.AnimSeqTimeStamp`（review 03）。
公开成员只有 `updateLastRecentFinishTime()`；其余 update/reset/时间差查询均 internal
（由 AnimationSeqHelper 内部消费）。

---

## manager（Ext 工厂 + feature 灰度）

### AnimationFeatureHelper — 远程灰度配置容器

对应原厂同名类（review 03；原厂是 RUS 远程下发，lib 用 setter 模拟）。

```kotlin
AnimationFeatureHelper.asyncEnable            // 等 7 个配置项（只读，统一通过更新方法写入）
AnimationFeatureHelper.onePxPkgDisableList    // 2 个只读列表
AnimationFeatureHelper.simulateRemoteUpdate(async, rtUnlock, multiApp, iconBlur, threshold, limtSize)
```

### 本地配置通知与 Adaptive 策略

```kotlin
val subscription = AnimationFeatureHelper.addRemoteUpdateListener {
    val current = AnimationFeatureHelper.snapshot() // 同锁一致快照；回调在 Android 主线程
    renderConfig(current)
}
// 可从后台调用 simulateRemoteUpdate；通知只表示读取最新状态，不是历史配置 payload。
// 页面 onDestroy / Demo onCleanup：
subscription.close()
```

- 每次 add 创建一个独立注册；close 幂等，撤销排队的旧通知。按 callback 移除须传回同一实例；移除再注册不会接到旧注册事件。回调不持配置锁，可重入更新/注销；普通 consumer Exception 隔离记录，执行中的回调不可撤回。
- `onDestroy()` 清全局注册但保留配置，只供拥有整个配置服务的集成方使用；页面不能用它替代自己的 subscription.close。
- 单个 getter 仍是独立 volatile 读；跨字段一致性请使用 `snapshot()`，包含防御复制后的不可修改列表。
- `setAdaptiveAnimationEnabled(true)` 将当前及后续 interruptThreshold 强制为 1.0f，并令 `getRadiusAnimationEnable()` 为 false。false 不恢复历史阈值，下一次更新可指定新值；host 策略默认 false，不自动读取 OPPO LauncherAnimConfig。
- Demo8 后台发布后由订阅刷新配置快照，退出注销自己；这是本地模拟，不是已连接 ROM RUS，也不表示所有动画引擎已按配置重建。

### OplusAnimManager — feature 驱动的工厂单例

对应原厂同名类（review 03；原厂管 6 个 helper，lib 只出 2 个）。

| 成员 | 说明 |
|---|---|
| `animController: DefaultAnimationController` | feature on → `AnimationController`，off → no-op 基类实例 |
| `supportInterruption(): Boolean` | feature 开关（demo 恒 true） |
| `var interruptionEnabled: Boolean` | 切换 Impl / Default(no-op)，演示降级 |

最小示例（Demo8）：

```kotlin
OplusAnimManager.interruptionEnabled = false
val implActive = OplusAnimManager.animController is AnimationController // false → no-op 生效
```

---

## playback（部分 public）

### NullableAnimatorListener / NullableAnimatorListenerAdapter

对应原厂 `com.android.launcher3.anim` 同名类（review 02）。
`NullableAnimatorListenerAdapter` 是业务 listener 的推荐基类（demo 直接子类化）：

```kotlin
object : NullableAnimatorListenerAdapter() {
    override fun onAnimationStart(animator: Animator) { ... }
}
```

（父类 `AnimatorListenerAdapter` 的全部回调可 override；`cancelled` 标志位供 cancel/success 区分。）

## core/Trace 与 LogUtils

Trace 的操作是 lib internal 的线程局部诊断段，输出到 stderr，**不接入 android.os.Trace/Perfetto**。DemoBaseActivity 重定向 System.err 到日志区。AsyncAnimCallbacks 的段覆盖主线程实际 listener 派发，begin/end 同线程且 finally 收尾；ThreadLocal 不支持跨线程 end 关闭另一线程的 begin。

公开 `LogUtils` 提供 i/isLogOpen/isAlwayson/setLogLevel：默认 Debug=INFO、Release=OFF；OFF/INFO/ALWAYS 为本库本地等级，不是 OEM RUS 配置。输出携带实际线程名与分类。Trace 使用开始时的门控值，关日志后会闭合已经开始输出的段，但不输出新的段。需要更详细诊断时显式 `LogUtils.setLogLevel(LogUtils.ALWAYS)`，结束后恢复等级；昂贵字符串应先检查 isLogOpen，因为 Kotlin 字符串插值并非自动惰性执行。

---

## 有意简化清单（相对原厂）

以下是**有意为之**的裁剪，细节与证据见对应 review：

- 帧源：`SfVsyncFrameCallbackProvider`（@hide）→ `core/scheduler` 的 `ChoreographerTickScheduler`（VSYNC）帧循环（review 01 §②-B1、review 04 §4.2-1）
- `LauncherBooster` UX 线程注册 / UAF 绑核：OPPO 私有，退化为 `Process.setThreadPriority` 兜底（review 01 §②-B2）
- `OplusLooperExecutor` 四扩展（executeAtFront/WithUx/BlockWait/Delay）未复刻（review 01 §②-B4）
- `Executors` 保留 MAIN_EXECUTOR 与惰性 ANIM_CONTROL_EXECUTOR；没有 OEM urgent/UI_HELPER/多种后台池，也不由页面 quit。
- `LauncherAnimationRunner` 砍成 `RemoteAnimationTarget` 类型壳（review 03 §2.2）
- `RemoteAnimationFactory` 重写为 2 方法 demo 接口（review 03 §2.2）
- 超时 listener 是 class，主 Looper 定时器与 Controller 三槽事件共用一次性完成；未接 Binder/global task 总线。
- `CustomRectFSpringAnim` 已有线程/双轨结束协议，RectSpringDriver 提供六轴 AndroidX 几何；仍非 OEM 私有窗口引擎（review vs-oppo-16）
- `AnimationFeatureHelper` 用本地 setter 模拟 RUS 下发（review 03 §2.2）

**当前边界**：`addRecentsAnim` 的 UNKNOWN/MULTI_WAITING 转移、Between setter/query，以及内部续行动画的 target/property 和值输出已经修复；旧 review 的风险列表是历史快照，不能当作当前未修清单。可选六轴 RectSpringDriver 已实现；完整 Launcher 手势、SF-VSYNC、OEM 私有窗口内核及系统续行业务 helper 仍未移植。逐项状态见 `docs/review/2026-09-09-ordered-review-progress.md`；Demo 5 的舞台演示不直接调用 internal 续行实现。

---

## Activity 生命周期契约

使用 AsyncAnimator 的 Activity/Fragment 需要在销毁时正确释放资源，避免内存泄漏和状态机错乱。

### 最低要求

```kotlin
// 页面自己拥有的实例；不要在页面退出时销毁其他页面共享的全局 controller。
private val controller = AnimationController()

override fun onDestroy() {
    controller.destroy() // 清除观察者、三类超时和挂起操作
    seqHelper.clearFinishRecentsRunnable()
    myAnimator.dispose() // 最终释放：撤销排队启动/监听通知，原生取消在固定 owner Looper 执行
    super.onDestroy()
}
```

### 框架自动清理

- `DemoBaseActivity.onDestroy()` 提供 `onCleanup()` 钩子供子类覆写
- `AnimationController.destroy()` 清空状态观察者、dispose 全部超时 listener，并 reset 到 NONE（不在销毁时通知旧观察者）
- 自有 `AnimationHandler` 在最后一个 callback 被显式移除并完成当帧清理后退订 self-pulse；不能依赖 GC 停止帧循环
- Demo3/6/7/9/10/11 已接入对应清理；`LauncherStageView` detach 停止场景时钟；基类恢复自己安装的日志流
- Controller 注册/销毁按主线程归属；同类超时重复注册会取消旧监听，事件和定时器只消费一次操作

### 注意事项

- `LooperExecutor` 遵循永不关闭契约（`shutdown()` 始终抛 `UnsupportedOperationException`），无需在 onDestroy 中关闭
- `AnimSeqTimeStamp` 是全局静态时间戳单例，生命周期跟随进程，不需要 Activity 级别清理
- 超时 listener（`TaskStateChangeTimeOutListener`）由 `AnimationController` 持有，`destroy()` 统一释放

### 最终释放与功能关闭

- `AsyncValueAnimator.dispose()` 是最终、幂等释放：立即失效尚未执行的 start/cancel/end 和异步监听事件，在原生 owner 上清空 update/listener 并 cancel。不能撤回已经执行中的业务回调；不阻塞等待后台完成。
- 首次生命周期命令（包括尚在队列中的 cancel/end）固定 executor；之后换另一个执行器会抛 `IllegalStateException`。dispose 后 start、executor 配置和包装器 addAnimatorListener 被拒绝，cancel/end/dispose 无操作。原生参数/监听应在启动前配置，销毁后不得绕过包装器再次注册监听或操作平台方法。
- 该方法不赋予 animator 重复生命周期能力，也不关闭进程线程；仅需复用监听容器时使用 `asyncAnimCallbacks.dispose()`，两者不是同一个 API。
- `OplusAnimManager.interruptionEnabled` 必须在主线程切换；关闭先摘掉全局实现引用，再 destroy 旧 controller、撤销旧 Seq 延迟 finish。重新开启创建新实例；外部已保存的旧引用不是可撤销句柄，不应继续操作。
- Demo10 移除延迟自动启动，停止时使旧 UI 回调代次失效；后台仅计算/采样，View 更新交回主线程。Demo11 未配置 AndroidX 后台调度器，明确使用主线程安全回退并清除原生监听，不再把编译通过当作后台 View 支持。

### 监听容器复用与工厂兼容

- `AsyncValueAnimator.ofFloat(isAsync, *values)` 对齐原厂布尔开关工厂，也提供 Java 静态入口；`ofFloat(*values)` 保留现有 Kotlin 调用方式。
- `asyncAnimCallbacks.dispose()` 清空监听及 animationId，并使销毁前排队的事件失效。之后可重新注册监听复用容器；它不取消 animator，也不能撤回已经开始执行的业务回调。
- `AnimationSeqHelper` 在执行延迟 finish 前先消费旧 action，允许回调中再安排下一次 finish；页面销毁仍须显式 `clearFinishRecentsRunnable()`。


### Review 续轮补充：工厂与释放边界

- `AsyncValueAnimator.ofFloat(true, 0f, 1f)` 返回异步包装；`false` 返回平台 `ValueAnimator`。保留 `ofFloat(0f, 1f)` 异步快捷调用，Boolean 重载也可从 Java 静态调用。
- `asyncAnimCallbacks.dispose()` 释放当前监听/ID，并失效已排队的旧代次事件；重新注册后旧事件不会落到新监听。监听中调用 dispose 会停止本次剩余监听的派发，但无法撤回已经执行的回调。
- **容器** `asyncAnimCallbacks.dispose()` 不取消 animator。只释放自己拥有的容器；多个业务共享时仍用 `removeAnimatorListener()` 精确注销。容器重新注册不等于 `AsyncValueAnimator` 实例支持重复生命周期。
- `AnimationSeqHelper` 由主线程调用。延迟 finish 先消费当前任务再调用业务，允许业务回调再次提交后继请求；此改进不是任意线程并发保证。

### 配置发布的并发边界

AnimationFeatureHelper 发布防御复制且不可修改的列表快照，Java 或 Kotlin MutableList 强转均不能修改；持有的旧快照不随后续更新变化。六参数 simulateRemoteUpdate 保留 onePxEnable，读取和更新同锁，避免与九参数更新竞争时写回旧值。多个 scalar getter 是独立 volatile 读，不保证一次跨字段读取来自同一批配置；新增 snapshot() 在同锁下读整批字段，需一致性时使用它；没有吞吐改善百分比的实测承诺。


## Kotlin 写法与 Java 调用边界

本库提供下述已编译测试的 Java 调用面，不承诺 OPPO 同名类的二进制替换兼容。

- Kotlin `object` 通常通过 `INSTANCE` 获取（例如 `OplusAnimManager.INSTANCE.getAnimController()`）；没有 `@JvmStatic` 的方法不能写成 Java 静态调用。默认参数也不自动生成 Java 重载。
- `() -> Unit` 在 Java 中对应 `Function0<Unit>`，lambda 需要返回 `Unit.INSTANCE`；`() -> Boolean` 可返回 Boolean。没有额外 Supplier/Runnable 重载，不通过复制同签名 lambda 重载制造选择歧义。

```java
kotlin.jvm.functions.Function0<kotlin.Unit> action = () -> {
    // 执行业务动作。
    return kotlin.Unit.INSTANCE;
};
com.asyncanimator.thread.Executors.INSTANCE.getMAIN_EXECUTOR().execute(action);
boolean delayed = new com.asyncanimator.seq.DefaultAnimationSeqHelper()
        .delayFinishRecents(action); // 当场执行 action，返回 false。
```

- Default Controller 的 `delayStartActivityIfNeed` 则返回 false 且不执行 action，调用方走立即启动路径；不要把两种 Default 的 fallback 契约混用。
- `LooperExecutor` **不是 ExecutorService 的子类型**，没有 submit/invokeAll 契约；shutdown、shutdownNow、awaitTermination 抛 UnsupportedOperationException，isShutdown/isTerminated 恒 false。方法存在不代表实现了该接口，也不是安全终止页面任务的方法；页面应释放自己的动画/回调。
- Manager 首次 object 访问创建两个 helper；`supportInterruption()` 固定 true 是能力占位，不检测 ROM/Shell，`interruptionEnabled` 是主线程本地开关。字段 volatile 与 setter 锁不保证两个独立 getter 的原子 snapshot。


- `OnAnimStateChangeListener` 是 `fun interface`，Java/Kotlin 均可用 SAM lambda。保存注册的 listener 再传给 remove；当前列表使用 equals 语义，不是 `===` 身份集合。重新写一个看似相同的 lambda 不保证能删除旧监听；这与 typealias 是否存在无关。
- `AsyncValueAnimator.ofFloat(isAsync, *values)` 的声明返回 `ValueAnimator`；true 时才可转为 AsyncValueAnimator 使用 executor/dispose。Boolean 重载有 Java 静态入口，纯 Float 快捷工厂面向 Kotlin；Java 不要依赖继承的同名平台静态工厂推断异步类型。

```java
ValueAnimator selected = AsyncValueAnimator.ofFloat(true, 0f, 1f);
AsyncValueAnimator async = (AsyncValueAnimator) selected;
async.setExecutor(com.asyncanimator.thread.Executors.INSTANCE.getMAIN_EXECUTOR());
// 在启动前配置 listener / updateListener；由页面在销毁时执行 async.dispose()。
```

- `Executors.MAIN_EXECUTOR` 绑定主 Looper；只访问 MAIN 不拉起动画线程。ANIM_CONTROL_EXECUTOR 单独使用 synchronized lazy，首次请求才创建，多个线程并发获取同一进程实例。不应由页面 quit。
- 内部 `OplusValueAnimator.AnimParam.copy()` 新建参数容器；from/to/currentFraction/duration 等标量独立，interpolator/applicator 引用共享，闭包捕获状态不会自动复制。需要不同接收目标时应显式提供不同 applicator，不能把“浅拷贝”误写成同一个参数对象。
- `internal` 是 Kotlin 模块边界，不是 Java package-private 或安全沙箱；不承诺 Java 绕过 Kotlin 可见性后调用的内部符号稳定。PendingAnimation/AnimatorPlaybackController/AnimationHandler 仍是内部实现，Demo9 使用公开 MultiAnimatorSet。
- apply/also、data class、companion、init 是语言写法，不自动改变线程、生命周期或分配成本。private set 不提供同步，crossinline 不冻结捕获变量，List 只读接口不等于深度不可变；FeatureHelper 的不可修改列表由防御复制 + unmodifiableList 实际保证。
- 当前没有 awaitCompletion/waitForState 协程 API。没有协程调用方时不增加依赖；后续适配须先明确取消/立即完成/主线程注册与逻辑或实际结束语义，并测试监听释放，不能只用 Deferred 包一层就声称完整支持。


## 开窗触摸保护

`AnimationController.forbidTouch()` 是必须由调用方接入的主线程查询，不会自动拦截系统输入。launch-start 开启 600ms 定时闸门，重复 start 更新截止任务；launch-end/reset/destroy 撤回并释放。MULTI_WAITING、REVERSE_OPEN 或真实挂起启动 action 也会阻止触摸；只注册 timer 或传 null action 不会。

```kotlin
if (!controller.forbidTouch()) {
    requestWindowOpen()
}
```

Demo9 已在图标/窗口/转场按钮接入，并把聚合开窗 start/实际结束交给页面自己的 Controller。返回键和生命周期清理不走触摸闸门。定时任务与 Controller 一样在主线程，繁忙时可延后；600ms 不是硬实时上限，亦不等于物理动画结束。no-op Controller 返回 false。OEM Controller 的 touch Handler 本身也在主线程，勿与其 TaskStateHelper 的 urgent transaction timeout 混淆。


## Controller 完成回调的重入与异常

当 app-launch 与 recents 两个集合都为空时，Controller 先捕获本轮两个完成回调，reset 旧轮内部状态/触摸闸门并清空完成字段，再依次交付捕获的 recents / launch 回调。NONE 状态通知先于完成回调；不要依赖 OEM“回调后才 reset”的旧顺序。

- 完成回调可以启动下一轮并注册新的完成回调，旧轮结束后不会再 reset 新轮。
- 回调重入 cleanUpRecentsAnim 不会重放已消费的回调；已为 NONE 且无待完成回调时仍清理临时手势/挂起启动字段，但不重复通知 NONE。
- reset 的观察者或完成回调抛异常时，仍尝试交付本轮两个完成回调，最后重抛第一个错误，其他错误作为 suppressed 保留。新一轮不会因为旧回调抛错被清掉；这不是吞异常，也不承诺普通状态观察者之间互相隔离。
- 非法 addRecentsAnim 输入仍进入 UNKNOWN，同时通过现有 LogUtils 门控输出旧状态/操作，便于定位；未增加新的硬异常或 Perfetto 事件。

## Controller 新轮启动与完成请求归属

- `addRecentsAnim` 会清除上一轮 `recentsAnimFinishCallback`，然后才发送状态通知。先 add，再注册当前轮 callback；状态观察者内注册也会保留。不能把完成请求无限期复用给下一轮。
- `appLaunchAnimStartOrEnd(false, ...)` 在发布新状态前取消 Manager Seq helper 里排队的旧 Recents finish，不会执行被替代的 action。
- `isAppWindowAnimRunning` 是十二状态分类（除 NONE/UNKNOWN 都为 true），不是 `forbidTouch` 的 600ms flag，也不保证系统窗口正在绘制。主线程读取；基类 feature-off 仍为 false。
- 最后一个 launch 结束且非手势中时仍要等 recents 集合清空；cleanup 有 launch 时保持状态/回调并返回 false，没有 launch 才完成/reset。

## 启动决策 provider 的重入与诊断

`delayStartActivityIfNeed` 的 isTablet、业务 Supplier、overview-running provider 在主线程调用，应尽量无副作用。如果其内部 reset、替换/注销所选 listener 或发起新决策，外层旧决策会返回 false，不接管旧 action，也不会清除新的 pending/场景。宿主主动重入时应放弃已被取代的旧启动，不能又按旧返回值重复执行它。

- 入口清旧请求，匹配事件/超时在调用 action 前清旧持有；query 的异常按原样传播，不吞掉业务错误。
- special 的截止判断是严格 `>`；过期 early false 不 dispose 仍在排队的 timer，也不落入第二/三层。等于截止可以暂挂，之后由事件或 timer 兜底。
- LogUtils INFO 可查看分支输入和 defer 结果；betweenTransition 是诊断值，不是额外挂起谓词。OFF 不输出，不为日志再次调用 provider。
- Manager 关闭功能后重新获取的是 no-op Controller；自行构造/持有的独立 Controller 不等于全局 ROM feature guard。

## TaskState listener 的事件与并发边界

- `dispatchTaskStateChange(type)` 是宿主已判定完成的主线程事件。接 OPPO raw callbacks 时，transition(false) 不应映射为完成；landscape 的原厂处理不检查该布尔；taskListenerReleased 属 TO_HOME，不自动等价 overview。
- 本库支持三个 Controller 场景，没有 TO_HOME slot、Binder cookie/taskId 路由或 SystemUiProxy 注册；创建 Controller 不会自动收到系统任务事件。
- standalone listener 多线程匹配事件原子领取 action 至多一次，在获胜调用线程执行；dispose 只能阻止尚未领取的动作，不能撤回/等待已经开始的业务代码。Controller-owned listener 仍限主线程。
- action 与释放同时失败时保留 action 为主异常、释放错误为 suppressed；仅释放失败时抛该错误，不重放任何回调。门控日志记录 registered/firing/disposed，不是 Perfetto。


### PendingAnimation 内部组装约定

- `setFloat` 仅指定终值，由平台 ObjectAnimator 在初始化/首次 seek 读取起点；组装时只做 null/当前值相等短路。`addFloat(from, to)` 保留显式起点。无动画 PropertySetter 则立即写属性，并不是空操作。
- `add` 强制总时长，`addWithoutDuration` 保留 child 时长。嵌套集合在收集 Holder 前递归下发父级正时长和非 null 曲线；父时长 -1/0 不覆写。手动 seek 不等于支持 AnimatorSet 顺序编排或 startDelay 时间轴。
- 先完成所有 child/frame/end 的组装，再 `createPlaybackController()`；它缓存 Controller 和 Holder 快照，不支持在创建后追加动画并自动同步。
- `buildAnim()` 返回实际根集合；直接根动画可取消该根。APC 自有主播放器，手动驱动的取消还需按 APC 协议停止播放器/派发事件，不把根 cancel 当作全部时钟停止。
- 自定义 ObjectAnimator wrapper 保留给 TimeController 兼容，重复 build 返回同一 ValueAnimator 且不叠加映射监听。上述类型仍为 internal，Demo9 走公开 MultiAnimatorSet，而非跨模块直接使用 Pending/APC。


### APC 内部派发与完成动作

- 主播放器和目标根是两个对象：`dispatchOnStart/End/Cancel` 只递归调用目标树监听，不自动停止主播放器；`pause()` 则 reset Holder 后取消主播放器，不自动派目标树 cancel。
- `endActions` 按 key 替换。暂停后保留动作，后续 start/reverse 的成功完成可消费；永久放弃时由 owner 清除动作/取消回调的捕获引用，不将 pause 等同 destroy。
- 成功时先设一次性闸门、领取并清除旧 action 快照，再派根 end/执行快照。外部回调新注册的动作属于下一轮，旧轮结束不会清它们；异常原样 fail-fast，已领取旧快照不重试，剩余项可能不执行。
- `forceFinishIfCloseToEnd` 仅在主播放器正在运行且 animatedFraction > 0.95 时结束；0.95 等号和未启动/已暂停/已结束状态均不强制 end。
- 零时长子动画或总时长为零时，首次 seek(0) 即写终值，避免 0/0 产生 NaN。正时长映射不变，不包含任意无效自定义 mapper 的校正。


### SeqHelper 序号写入与 feature provider

- `addSeqId` 与 `updateNextFinishSeqIdIfNeed` 使用注入的 `interruptionSupported`；关闭时不递增、不改 Bundle/已有 pair。配对读取不门控，仍可读取旧 pair 的值。
- `startingSurfaceSupported` 只与 interruption 一起控制 500ms finish / 300ms gesture 时间窗，不控制序号写入。任一 feature 关闭，时间窗直接放行。
- 序号是单 helper 内共享计数器，Bundle 写入与换 controller 都会消耗；同/equal controller 不重分配，Bundle 写入也不重编号已有 pair。它不是跨实例全局 ID。
- 条件检查与剩余时间计算分两次读时钟，单调前进也可能跨过 deadline；剩余负值钳为零再入队。默认 no-op helper 的 delay 方法立即调用 action，不能当成不执行。
- Manager 默认能力方法仍恒 true，Demo 开关通过实例/no-op 切换；独立 helper 的 provider 不代表已接 ROM/RUS。实例操作由主线程负责。


### AnimSeqTimeStamp 时钟与未记录状态

- 零毫秒是有效事件时间；内部 null 才表示未记录。未记录 gap=Long.MAX_VALUE，仅用于时间窗比较，不可当普通时长任意相加/相乘。
- clock 已收为 internal 测试入口，不是公开运行时配置。供给同原点单调毫秒，换原点前清记录、测试后恢复，禁止工作线程活跃时交换时钟。
- 四个写/独立 reset 与测试 reset-all 同步，单字段读取通过 volatile 发布；连续多个 getter 不构成原子四字段快照。对象仅进程/类加载器内共享，不自动同步另一进程。
- INFO 输出四字段的 update/reset/gap，OFF 关闭；不是设备 Perfetto。Demo7 仍可调用公开 updateLastRecentFinishTime，其余写入口维持内部使用。


### FeatureHelper 整批准备与提交

- 完整更新先复制两个调用方列表，全部成功后才在同一锁内提交标量/列表。复制抛异常时本次调用不修改配置、不排队通知；自定义集合自身造成的外部副作用不属于回滚范围。
- null 列表保留提交时现值，空列表清空；输入集合不能在复制期间并发修改。使用 snapshot() 读取同批配置，不连续拼接多个独立 getter。
- multi-app 等字段是配置数据，不自动切换 Manager；Demo8 页面已有说明，顶部 Interruption 开关才控制 Impl/no-op。未接 OEM 设置/硬件/RUS 的派生组合逻辑。


### Manager 首访与本地工厂边界

- 首次访问 object 即创建 Controller/Seq 两个实现，不是每个 helper 独立 lazy；supportInterruption() 固定 true，不用于读取 Demo 开关。interruptionEnabled 才控制新查询返回实现或 Default。
- 关闭会先断开旧 owner，再释放旧 Controller 的工作并清 Seq 队列；重新开启创建新实例，不承诺永久撤销用户持有引用。切换限主线程，多个独立 getter 不构成同一代原子快照。
- Default Seq 仍立即执行 delay action，不写 Bundle、不分配 ID；不要把 no-op 理解为所有方法不执行，也不要依赖 fallback 对象身份。
- Manager Recents 清理只委派本地 Controller；未接 AppOpen/MultiApp 系统 target merge、按键注入或 pre-start Surface 事务。配置字段下发不自动重建工厂。

## Demo 模块入口与日志契约

- 应用包名 com.asyncanimator.demo；LauncherEntryActivity 是唯一 MAIN/LAUNCHER 导出入口。Demo1–Demo12 均显式 exported=false，从入口页的同应用显式 Intent 打开；不为了 adb 直跳而全部导出。
- Demo9 已通过公开 MultiAnimatorSet 驱动四轨聚合，并接本地 Controller 生命周期；Canvas 舞台不是 Binder/Surface 系统窗口，没有构造 RemoteAnimationAdapter/ActivityOptions 真 Launcher 启动链。不能用它替代 OEM 端到端性能回归。
- Demo6 图形只消费 Controller 真实状态通知；六个代表节点归并十二种状态，完整状态名看标签/日志。WAITING/REVERSE_OPEN 通过实际事件到达，不提供任意写 animState 的“双向绑定”。
- DemoBaseActivity 的共享 TraceLogRedirector 只包一层 stderr，页面独立订阅；交错销毁/重复 close 不恢复旧页面包装，最后一个订阅释放时仅恢复自己仍拥有的 stderr。不关闭原流、不覆盖其他组件新装的流。
- UI 订阅弱引用 Activity，onDestroy 的 finally 注销并清回调；已排入 UI 的日志仍检查 cleanedUp。只转发包含 Trace 的 String println，不是全 stderr 捕获或 Perfetto 后端。订阅异常与其他订阅隔离；正在执行的回调不能追溯取消。
