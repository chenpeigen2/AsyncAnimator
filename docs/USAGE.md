# AsyncAnimator lib — 公开 API 使用文档

本库是 OPPO ColorOS 15 Launcher（`com.android.launcher` 15.8.24）"独立动画线程"方案的 Kotlin 重实现 / 演示库。
与原厂代码的逐类对比见 `docs/review/01` ~ `04`（类对应表、保真度评估、有意简化与已知差异）。

可见性约定：只有本文列出的类是 **public API**；`core/`（帧调度）、`pending/`、`playback/`、`continuation/`
以及未列出的成员均为 `internal`，属于实现细节，外部（demo 模块）不可见。

## 整体分层

```
core/scheduler (internal)        TickScheduler / ScheduledTickScheduler   ← 帧源抽象（可替换）
core/anim (internal)             AnimationHandler                         ← ThreadLocal 调度中枢
        ↑ installThreadScheduler
launcher/animthread              AnimationControlThread                 ← "launcher.anim" 独立线程
launcher/async                   LooperExecutor / Executors               ← 跨 Looper 执行器
                                 AsyncValueAnimator / AsyncAnimCallbacks  ← 跨线程安全动画
                                 ActualEndAnimListener                    ← "物理帧播完"回调基类（双轨结束）
                                 CustomRectFSpringAnim                    ← 转场动画句柄
launcher/pending (internal)      PendingAnimation / NullableAnimatorListener* / AnimatorListeners
launcher/playback (internal)     AnimatorPlaybackController / Interpolators / PropertySetter
launcher/continuation (internal) OplusValueAnimator / RecordInputInterpolator
launcher/controller              AnimationController / AnimationState / … ← 转场状态机
launcher/seq                     AnimationSeqHelper / AnimSeqTimeStamp    ← SeqId 防抖
launcher/feature                 AnimationFeatureHelper                   ← 灰度配置容器
launcher/manager                 OplusAnimManager                         ← feature 驱动的工厂单例
com.android.launcher3            LauncherAnimationRunner                  ← 类型壳（RemoteAnimationTarget）
util                             Trace                                    ← trace 输出（demo 重定向到日志区）
```

依赖方向自上而下：controller/manager/seq 依赖 async/pending 的动画设施，async 依赖 animthread 的执行器，
animthread 在 `onLooperPrepared` 时向 core/anim 注册绑本线程 Looper 的帧源。

---

## launcher/async

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

对应原厂同名类（review 01）。公开成员只有：

```kotlin
fun addListener(l: NullableAnimatorListener?)
```

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
业务一般只使用预定义单例：

```kotlin
Executors.MAIN_EXECUTOR         // 绑定主 Looper；JVM 单测下退化为就地执行
Executors.ANIM_CONTROL_EXECUTOR // 绑定独立 launcher.anim 线程
```

### CustomRectFSpringAnim — 转场动画句柄

对应原厂 `com.android.quickstep.util.animation.CustomRectFSpringAnim`（review 04；原厂 907 行弹簧实现未复刻，
lib 只保留句柄 + 枚举，对比见 review 04 §1）。

```kotlin
class CustomRectFSpringAnim(animType: AnimType)   // animType 为 internal
enum class AnimType { SWIPE_TO_HOME, RECENTS_TRANSITION, APP_LAUNCH }
```

用法：`AnimationController.addRecentsAnim(CustomRectFSpringAnim(AnimType.SWIPE_TO_HOME), null, arrayOf())`。

---

## launcher/animthread

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

## launcher/controller

### AnimationController — 转场状态机

对应原厂 `com.oplus.quickstep.utils.AnimationController`（review 03；注意 review 03 §3-a 列出的
转移表两处已知偏差，本库有意保留现状）。

| 成员 | 签名 | 说明 |
|---|---|---|
| `animState` | `var animState: AnimationState`（private set） | 当前状态 |
| `addRecentsAnim` | `(anim: CustomRectFSpringAnim, controller: Any?, targets: Array<out Any?>?)` | recents 动画注册 + 状态转移 |
| `appLaunchAnimStartOrEnd` | `(isEnd: Boolean, factory: RemoteAnimationFactory?, targets: Array<LauncherAnimationRunner.RemoteAnimationTarget>?)` | app 启动动画事件 |
| `reset` / `cleanUpRecentsAnim` | `fun` | 归位 NONE / 清理 |
| `isOpeningAnim` / `hasRecentsAnim` / … | `val: Boolean` | 状态查询（其余见 DefaultAnimationController） |
| `registerSpecialSceneExitTimeOutListener` | `(timeoutMs: Long)` | 三种超时 listener，独立运行 |
| `registerTransitionFinishTimeOutListener` | 同上 | |
| `registerOverviewContinuationTimeOutListener` | 同上 | |
| `specialSceneExitTimeOutListener` 等三个 | `val: TaskStateChangeTimeOutListener?` | 未注册为 null（`null` = 当前不在该场景） |
| `addOnAnimStateChangeListener` | `(listener: OnAnimStateChangeListener?)` | 状态变更监听（typealias 函数类型） |

最小示例（Demo6）：

```kotlin
val controller = AnimationController()
controller.addOnAnimStateChangeListener { old, new, _ -> log("$old -> $new") }
controller.registerSpecialSceneExitTimeOutListener(1500L)
controller.addRecentsAnim(
    CustomRectFSpringAnim(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME), null, arrayOf())
// 模拟超时兜底路径：
controller.specialSceneExitTimeOutListener
    ?.onTimeOut(TaskStateChangeTimeOutListener.Type.ON_LAND_SCAPE_SCENE_EXIT, 1500L)
controller.reset()
```

### AnimationState — 12 状态枚举

对应原厂 `AnimationController$AnimationState` 内部枚举（review 03；lib 提升为顶层）。
每个状态携带 `withTaskbarAlignment` / `taskbarAlignmentToLauncher` 两个标志（taskbar 对齐语义）。

### DefaultAnimationController — no-op 基类

对应原厂同名类（review 03）。**这是原厂 Ext 模式的语义本体**：feature off 时
`OplusAnimManager.animController` 返回此基类实例，所有方法 no-op、查询返回默认值，
业务代码不需要判空。结构刻意保留，不要合并。

### OnAnimStateChangeListener（typealias）

`(oldState: AnimationState, newState: AnimationState, runningTask: Any?) -> Unit`

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

`RemoteAnimationFactory` 是 `AnimationController.appLaunchAnimStartOrEnd` 签名的一部分；
`com.android.launcher3.LauncherAnimationRunner.RemoteAnimationTarget` 为其参数类型壳
（review 03；原厂 600+ 行 runner 只保留了类型壳）。demo 传 `null` / `arrayOf()` 即可。

---

## launcher/seq

### AnimationSeqHelper — Recents 动画 SeqId 防抖

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

## launcher/feature · launcher/manager

### AnimationFeatureHelper — 远程灰度配置容器

对应原厂同名类（review 03；原厂是 RUS 远程下发，lib 用 setter 模拟）。

```kotlin
AnimationFeatureHelper.asyncEnable            // 等 7 个 @Volatile 配置项（可读写）
AnimationFeatureHelper.onePxPkgDisableList    // 2 个只读列表
AnimationFeatureHelper.simulateRemoteUpdate(async, rtUnlock, multiApp, iconBlur, threshold, limtSize)
```

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

## launcher/pending（部分 public）

### NullableAnimatorListener / NullableAnimatorListenerAdapter

对应原厂 `com.android.launcher3.anim` 同名类（review 02）。
`NullableAnimatorListenerAdapter` 是业务 listener 的推荐基类（demo 直接子类化）：

```kotlin
object : NullableAnimatorListenerAdapter() {
    override fun onAnimationStart(animator: Animator) { ... }
}
```

（父类 `AnimatorListenerAdapter` 的全部回调可 override；`cancelled` 标志位供 cancel/success 区分。）

## util

### Trace

lib 内部的动效溯源 trace（输出到 stderr）。demo 不直接调用其成员——DemoBaseActivity 通过
重定向 `System.err` 把 trace 输出捕获到日志区。

---

## 有意简化清单（相对原厂）

以下是**有意为之**的裁剪，细节与证据见对应 review：

- 帧源：`SfVsyncFrameCallbackProvider`（@hide）→ `HandlerTickScheduler` 的 Looper 帧循环（review 01 §②-B1、review 04 §4.2-1）
- `LauncherBooster` UX 线程注册 / UAF 绑核：OPPO 私有，退化为 `Process.setThreadPriority` 兜底（review 01 §②-B2）
- `OplusLooperExecutor` 四扩展（executeAtFront/WithUx/BlockWait/Delay）未复刻（review 01 §②-B4）
- `Executors` 只保留 `MAIN_EXECUTOR`（review 01 §②-B3）
- `LauncherAnimationRunner` 砍成 `RemoteAnimationTarget` 类型壳（review 03 §2.2）
- `RemoteAnimationFactory` 重写为 2 方法 demo 接口（review 03 §2.2）
- 超时 listener 简化为被动回调 fun interface（review 03 §2.2）
- `CustomRectFSpringAnim` 降级为句柄占位（review 04 §2.2-3）
- `AnimationFeatureHelper` 用本地 setter 模拟 RUS 下发（review 03 §2.2）

**已知语义差异（本次未修，属于后续回移项）**：`addRecentsAnim` 转移表两处偏差、续行动画的 timeController 接线等 —— 完整清单见
`docs/review/01` ~ `04` 各自的「行为差异风险点」一节。
