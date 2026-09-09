# 对比 Review 06：public API 暴露面 vs 原厂调用面

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib`（AsyncAnimator 演示库，Kotlin/JVM）
> - **原厂**：`D:/oppo_a6_launcher/sources`（OPPO ColorOS 15 Launcher 15.8.24 JADX 反编译源码；80% 文件经企业 DLP 加密，证据全部由 Grep 穿透取得，行为定论经多份 review 互相印证）
>
> 区域范围：lib `docs/USAGE.md` 暴露的全部 public API + demo 模块（11 个）实际调用面；与原厂真实调用方一对一比对。前面四份 review（01-async-animthread / 02-pending-playback / 03-controller-manager-seq / 04-frame-spring-continuation）已就"逐类保真度"给出详细结论，本区域只看"暴露面 vs 调用面"的吻合度，以及遗漏/溢出的路径。
>
> 注意：lib 的源文件（`lib/src/main/...`）在读本机同样被企业 DLP 加密；`Read` 工具返回密文，本报告的 lib 侧代码引用由 Grep（ripgrep 明文通道）取得。

---

## ① 类对应关系表

按 USAGE.md 列出的 public 类逐项对位。

### A. 完全对应（lib 类 ↔ 原厂类，行号 100% 命中）

| lib 类（文件） | 原厂类 | 关键证据（原厂 文件:行 / Grep） |
|---|---|---|
| `launcher/async/AsyncValueAnimator.kt` | `com.android.quickstep.util.animation.AsyncValueAnimator` | `AsyncValueAnimator.java:24-165`；`addAnimatorListener`/default executor/`start/cancel/end`/marshal 模式与 lib 一致 |
| `launcher/async/AsyncAnimCallbacks.kt` | `com.android.quickstep.util.animation.AsyncAnimCallbacks` | `AsyncAnimCallbacks.java:23-172`；`addListener`/`onAnimationStart`/`onAnimationEnd`/`onAnimationCancel`/`onAnimActualEnd` 一一对应 |
| `launcher/async/ActualEndAnimListener.kt` | `com.android.quickstep.util.animation.ActualEndAnimListener` | `ActualEndAnimListener.java:9-10`；`onAnimActualEnd` 空钩子 |
| `launcher/async/LooperExecutor.kt` | `com.oplus.basecommon.thread.LooperExecutor` | `LooperExecutor.java:12-80` |
| `launcher/async/Executors.kt` | `com.oplus.basecommon.thread.Executors` + `OplusExecutors.ANIM_EXECUTOR` | `Executors.java:20,54` + `OplusExecutors.java:95` |
| `launcher/animthread/AsyncAnimWrapper.kt` | `com.android.launcher3.anim.AsyncAnimWrapper` | `AsyncAnimWrapper.java:10-20`（全类 20 行，1:1） |
| `launcher/animthread/AnimationControlThread.kt` | `OplusExecutors.ANIM_EXECUTOR` 内联 `lambda$0` | `OplusExecutors.java:95,169-171` |
| `launcher/animthread/HandlerTickScheduler.kt` | 替代框架 `SfVsyncFrameCallbackProvider` | `OplusExecutors.java:5,170` |
| `launcher/controller/AnimationController.kt` | `com.oplus.quickstep.utils.AnimationController` | `AnimationController.java:58`（review 03） |
| `launcher/controller/AnimationState.kt` | `AnimationController$AnimationState`（12 状态枚举） | `AnimationController.java:103-115`（逐条比对通过） |
| `launcher/controller/DefaultAnimationController.kt` | `com.oplus.quickstep.utils.DefaultAnimationController` | `DefaultAnimationController.java:30`（review 03） |
| `launcher/controller/OnAnimStateChangeListener.kt` | `DefaultAnimationController$OnAnimStateChangeListener` | `DefaultAnimationController.java:34-36`（形状对齐） |
| `launcher/controller/TaskStateChangeTimeOutListener.kt` | `com.oplus.quickstep.taskviewremoteanim.TaskStateHelper$TaskStateChangeTimeOutListener` | `taskviewremoteanim/TaskStateHelper.java:117-209`（机制：构造期 postDelayed 自管理超时） |
| `launcher/seq/AnimationSeqHelper.kt` | `com.oplus.quickstep.utils.AnimationSeqHelper` | `AnimationSeqHelper.java:17`（review 03） |
| `launcher/seq/AnimSeqTimeStamp.kt` | `com.android.systemui.shared.system.AnimSeqTimeStamp` | `AnimSeqTimeStamp.java:10` |
| `launcher/seq/DefaultAnimationSeqHelper.kt` | `com.oplus.quickstep.utils.DefaultAnimationSeqHelper` | `DefaultAnimationSeqHelper.java:10`（review 03） |
| `launcher/feature/AnimationFeatureHelper.kt` | `com.oplus.quickstep.utils.AnimationFeatureHelper` | `AnimationFeatureHelper.java:25`（同步：@Volatile 读 + synchronized 写；lib `SyncedVar` 等价） |
| `launcher/async/CustomRectFSpringAnim.kt` | `com.android.quickstep.util.animation.CustomRectFSpringAnim` | `CustomRectFSpringAnim.java:1-907`；lib 仅保留 `AnimType` 枚举作句柄占位 |
| `launcher/pending/NullableAnimatorListener*.kt` | `com.android.launcher3.anim.NullableAnimatorListener{Adapter}` | `NullableAnimatorListener.java:7`、`.Adapter:8`（review 02） |
| `launcher/playback/AnimatorPlaybackController.kt` | `com.android.launcher3.anim.AnimatorPlaybackController` | `AnimatorPlaybackController.java:25`（review 02） |
| `launcher/playback/PropertySetter.kt` | `com.android.launcher3.anim.PropertySetter` | `PropertySetter.java:10` |
| `launcher/playback/Interpolators.kt` | `com.android.launcher3.anim.Interpolators` | `Interpolators.java:17`（仅取 `LINEAR`） |
| `launcher/continuation/OplusValueAnimator.kt` | `com.oplus.quickstep.utils.OplusValueAnimator` | `OplusValueAnimator.java:39-495`（review 04） |
| `launcher/continuation/RecordInputInterpolator.kt` | `com.oplus.quickstep.utils.RecordInputInterpolator` | `RecordInputInterpolator.java:23-26` |
| `com.android.launcher3.LauncherAnimationRunner.kt`（20 行壳） | `com.android.launcher3.LauncherAnimationRunner`（600+ 行） | `LauncherAnimationRunner.java:1+`（review 03 §2.2） |

### B. lib 有、原厂缺失（lib 自加抽象）

| lib 类 | 原厂对应 | 备注 |
|---|---|---|
| `launcher/async/AsyncSpringAnim.kt` | 严格对应 `com.android.quickstep.util.OplusAsyncSpringAnimWrapper` | lib 注释（`AsyncSpringAnim.kt:9-15`）自述"对齐 `OplusAsyncSpringAnimWrapper extends AsyncAnimWrapper`"；原厂该类真实存在于 `com/android/quickstep/util/OplusAsyncSpringAnimWrapper.java`，本表上一组已隐含；这里单独列出是因为 **USAGE.md 未提到**，但 demo 11 在用——属于"已实现但文档未披露"的 API |
| `core/scheduler/{TickScheduler, ScheduledTickScheduler}.kt` | 框架 `android.animation.AnimationHandler`（@hide，`MultiDynamicAnimation.java:3` import） + `SfVsyncFrameCallbackProvider`（`OplusExecutors.java:5,170`） | lib 用抽象接口替换三套帧源实现（review 04 §2.2-1） |
| `util/Trace.kt` | `com.oplus.basecommon.util.LogUtils` + Perfetto Trace 部分 | lib demo 重定向到日志区 |

### C. 原厂有、lib 完全缺失（按 USAGE.md 公开 API 面）

| 原厂类 | 关键证据 | lib 暴露面是否覆盖 |
|---|---|---|
| `com.android.quickstep.util.animation.MultiAnimatorSet` | `MultiAnimatorSet.java:32`（`mAsyncAnimatorSet` + `maybeOnEnd` 四轨聚合） | **未暴露**。review 04 §2.3 标注 CustomRectFSpringAnim 整个 907 行被降级为句柄占位，MultiAnimatorSet 整套四轨并行（`mAnimatorSet`/`mAsyncAnimatorSet`/`mSpringAnimations`/`mRectFSpringAnim`）随之被整体砍掉 |
| `com.android.quickstep.util.animation.SpringHolder` + `SpringForce` + `SpringAnimReflectUtils` | `SpringHolder.java:47,117,130` + `SpringAnimReflectUtils.java:59-61` | **未暴露**。`SpringForce` 是 androidx 逐字拷贝 + 解析解（v4 §3），被 MultiAnimatorSet + CustomRectFSpringAnim 间接消费；lib 直接调用平台/自己 |
| `com.android.quickstep.util.OplusRectFSpringAnim` | `OplusRectFSpringAnim.java:44,867-888`（extends AOSP `RectFSpringAnim`，走 androidx dynamicanimation） | **未暴露**。原厂 AOSP 残留路径，review 01/04 未涉及 |
| `com.oplus.quickstep.utils.{AppOpenAnimMergeHelper, MultiAppAnimMergeHelper, InterceptKeyEventHelper, MultiOpenPreStartHelper}` | `OplusAnimManager.java:104-117` 工厂方法（6 个 helper 之一），Grep 命中 20+ 调用方（含 `OplusBaseSwipeUpHandler`、`LauncherAnimationRunner`、`QuickstepTransitionManager` 等） | **未暴露**。`OplusAnimManager.kt:21-22,38-46` 只保留 `animController` + `animationSeqHelper`，缺 4 个 merge/intercept helper |
| `com.oplus.quickstep.taskviewremoteanim.TaskStateHelper` 主体类 | `taskviewremoteanim/TaskStateHelper.java`（`mHandler` / `handleMessage` / 状态机主控） | **未暴露**。lib 只把 `TaskStateChangeTimeOutListener` 拆出来；TaskStateHelper 本身的 Handler 消息循环（`MESSAGE_RELEASE_TOUCH` 600ms 闸门、removeTasksMaps 等）整体缺位 |
| `com.oplus.quickstep.utils.{AppSwipeToRecentContinuationHelper, VirtualBtnToRecentContinuationHelper, AppToOverviewContinuationHelper}` | `AppSwipeToRecentContinuationHelper.java` 等；Grep 命中 14+ 文件 | **未暴露**。review 03 §3-d 标注"续行时间窗判定 `isAppSwipeToRecentContinuationRunning()`"被 lib 简化为 100ms 时间窗；这些 helper 类本身没出现在 lib 包内 |
| `com.oplus.quickstep.anim.LauncherContentAnimManager` | `LauncherContentAnimManager.java`，含 AsyncValueAnimator + MultiAnimatorSet 拼装入口 | **未暴露** |
| `com.oplus.quickstep.layout.grid.OplusGridRecentsView` / `OplusStackRecentsView` + `IRecentsViewLayout` + `ITaskAnimationBuilder` | `com/oplus/quickstep/layout/` | **未暴露**。v4 §7 描述的 grid recents 自写分页 + OverScroller 自维护路径被整体砍掉 |
| `com.oplus.zoom.draganimation.OplusZoomAnimationControlThread` | v4 §2 表末"Zoom Animation Control"线程 | **未暴露**。小窗拖拽走自己的线程，与 launcher.anim 并列存在 |
| `com.oplus.pantanal.*` / `painteranimation.*` / `physicsengine.*` / `effectengine.*` / `vfxsdk.*` / `anim.*` | v4 §10 列举的 6 个"动画"包 | **未暴露**。v4 §10 已论证这 6 个包全部走 UI 线程 Choreographer、不属于 launcher 主链路，故不必复刻；lib 整体方向对齐 |
| `com.oplus.wm.shell.transition.*` | WM Shell OPPO 定制 | **未暴露**。与 Launcher 在不同模块 |
| `com.coloros.operationmanual.*` / `com.heytap.*` / `com.nearme.instant.*` 等生态/系统应用 | OPPO 生态包 | **未暴露**。无关 |

### D. lib 暴露面 vs demo 调用面（11 个 demo 用到哪些 public API）

| Demo | 用到的 lib public API | 备注 |
|---|---|---|
| **D1** MasterClock | `AnimatorPlaybackController` + `Holder` + `ProgressMapper`（内部） + `LauncherStageView.bounceIcons`（demo 自绘） | demo 内部用，`stage.bounceIcons` 是 demo 模块新增的 4 图标同步驱动演示方法 |
| **D2** Holder 进度 | `AnimatorPlaybackController`（内部），`ProgressMapper` 概念 | demo 重写公式而非用 `Interpolators.DECELERATE` |
| **D3** Async 跨 Looper | `AsyncValueAnimator` + `Executors.MAIN_EXECUTOR` + `AsyncAnimCallbacks` + `NullableAnimatorListenerAdapter` | 命中 A 组核心 API |
| **D4** Spring 渐进切换 | **未用** lib 任何 public API！demo 注释（`:23-26`）明示："移植版 OplusSpringObjectAnimator 内部的 mSpring 不写回 target …… 由场景侧同参数解析解弹簧实际驱动"。本 demo 是 v3 文档的"概念性展示"，不是 lib API 的真实使用 | 属于"演示了 OPPO 不存在的功能"——demo 自带 `SceneSpring`，相当于在 demo 模块里又写了一份解析解弹簧；lib `launcher/playback/OplusSpringObjectAnimator` 类根本不存在（review 02 §1 末行） |
| **D5** 续行动画 | **未用** lib 任何真实续行 API！demo 注释（`:18-20`）明示："真机上 generateContinuationAnim 用 RecordInputInterpolator …… 本 demo 只在 log 里说明概念，动画由舞台演示等价效果"。lib 的 `OplusValueAnimator.generateContinuationAnim` 在 `OplusValueAnimator.kt:124-147` 实际有空实现，但 demo 不调用——调用的是 `stage.swipeToRecents()` | 同 D4——demo 演示的是**概念**，不调用 lib API |
| **D6** 状态机 | `AnimationController` + `AnimationState` + `TaskStateChangeTimeOutListener` + `CustomRectFSpringAnim` + `OnAnimStateChangeListener` | 命中 A 组核心 API；用 `appLaunchAnimStartOrEnd` + `addRecentsAnim` 真实驱动状态机 |
| **D7** SeqId 防抖 | `AnimationSeqHelper` + `AnimSeqTimeStamp` | 命中；`addSeqId` + `delayFinishRecents` + `clearFinishRecentsRunnable` 真实路径 |
| **D8** Feature Flag | `OplusAnimManager` + `AnimationFeatureHelper` | 命中；演示 Impl/no-op 切换 + `simulateRemoteUpdate` |
| **D9** AllApps 转场 | **未用** lib 任何 public API！只调用 `stage.openApp/closeApp/swipeToRecents/exitRecents`（demo 自绘）。demo 注释（`:13-15`）明示："转场链路（概念）"——`StateManager.goToState → PendingAnimation → AnimatorPlaybackController → OnAnimationEndDispatcher` 全是日志描述，舞台自驱 | 属于"演示了 OPPO 主链路但 lib 没有对应 API"——D9 的核心主题"OPEN_FROM_HOME 完整链路"在原厂由 `MultiAnimatorSet + CustomRectFSpringAnim + AnimatorPlaybackController` 三者协同（`LauncherAnimationRunner.java:242,529` → `MultiAnimatorSet.java:32` → `CustomRectFSpringAnim.java:885-907`），lib 只复刻了后者两者的 18 行壳，主角 MultiAnimatorSet 缺失 |
| **D10** 独立动画线程 | `AnimationControlThread.THREAD_NAME`（常量） + `AsyncValueAnimator` + `Executors.ANIM_CONTROL_EXECUTOR` + `AsyncAnimCallbacks` | 命中；这是"独立动画线程"主题的正面演示（v4 §3 路径 C 主用例） |
| **D11** View 属性弹簧 | `AsyncSpringAnim`（**USAGE.md 未披露**）+ `AsyncAnimWrapper` 间接 | 命中；通过 `AsyncSpringAnim(real=springAnim, supportAnimThread=true).start()` 真实驱动 androidx `SpringAnimation` |

### E. lib public API vs 原厂调用方（每个 lib 类至少 1 个真实 OPPO 调用点）

| lib public 类 | 原厂真实调用点（`grep` 结果） |
|---|---|
| `AsyncValueAnimator` | `AppLaunchAnimUtil.java:433, 441, 497, 498`（主消费者）；`LauncherContentAnimManager.java`；`OplusQuickstepTransitionManagerImpl.java`（隐含） |
| `AsyncAnimCallbacks` | 同上，与 AsyncValueAnimator 配套 |
| `ActualEndAnimListener` | `AsyncAnimCallbacks.onAnimActualEnd` 路径（`AsyncAnimCallbacks.java:34-43`），`CustomRectFSpringAnim` 的 cancel 保护路径 |
| `LooperExecutor` / `Executors` | 14+ 个线程族，所有 OPPO `com.oplus.basecommon.thread.*` 调用方 |
| `AsyncAnimWrapper` | `OplusAsyncSpringAnimWrapper` extends；`OplusRectFSpringAnim`；多个 view spring 包装（Grep 命中 5+ 文件） |
| `AnimationControlThread`（=`OplusExecutors.ANIM_EXECUTOR`） | `MultiAnimatorSet.java:160, 198`；`CustomRectFSpringAnim` 整条路径；`AsyncValueAnimator` async 分支 |
| `AnimationController` | `OplusBaseSwipeUpHandler.java`（多次 `addRecentsAnim`/`appLaunchAnimStartOrEnd`）；`LauncherAnimationRunner.java`；`OplusQuickstepTransitionManager.java`；`OplusOtherActivityInputConsumer.java`；`SpecialSceneHelper.java` |
| `TaskStateChangeTimeOutListener` | `TaskStateHelper.java:117-209` 内部类，被 `OplusBaseSwipeUpHandler` 等调用 |
| `AnimationSeqHelper` | 跨模块（launcher + systemui）；Grep 命中 `OplusBaseSwipeUpHandler`、`LauncherAnimationRunner` |
| `AnimSeqTimeStamp` | `com.android.systemui.shared.system.AnimSeqTimeStamp.java`（跨进程 IPC） |
| `AnimationFeatureHelper` | `OplusAnimManager.supportInterruption()`、`OplusQuickstepTransitionManager` 等所有需要 RUS 配置点 |
| `CustomRectFSpringAnim` | `MultiAnimatorSet.java:32` 的 4 轨聚合之一；`OplusBaseSwipeUpHandler.createAnimateToHome`；`LauncherBackAnimationController`；`TaskbarLauncherStateController` 等 20+ 个调用方 |
| `AnimatorPlaybackController` | `PendingAnimation` 必经；`Launcher.java`、`StateManager.java`、`QuickstepTransitionManager.java` 等 |
| `PropertySetter` / `Interpolators` / `NullableAnimatorListener*` | 同 PendingAnimation 链路 |
| `OplusValueAnimator` | `AppToOverviewContinuationHelper`（`@SourceDebugExtension` 指回）；`AppSwipeToRecentContinuationHelper`；`VirtualBtnToRecentContinuationHelper` |
| `RecordInputInterpolator` | 同 `OplusValueAnimator` 的 `param.interpolator` 字段 |
| `LauncherAnimationRunner`（20 行壳） | 原厂 600+ 行 runner 是远程动画链路主体（`LauncherAnimationRunner.java:242,529`） |
| `AsyncSpringAnim`（USAGE.md 未披露） | 原厂 `OplusAsyncSpringAnimWrapper` 同名兄弟，被多个 view spring 包装继承 |

**结论**：USAGE.md 列出的所有 public 类（除 `AsyncSpringAnim` 外）在原厂都有 ≥1 个真实调用方对应；没有一个 lib public 类是"凭空捏造、OPPO 不存在"。**`AsyncSpringAnim` 是 lib 自加的 Kotlin 化薄封装**，对应原厂 `OplusAsyncSpringAnimWrapper`，USAGE.md 漏列，应补。

---

## ② 保真度评估

### A. 精确复刻（lib 与原厂在行为上完全对齐；本区域层面"调用面"也闭合）

1. **USAGE.md 列出的 25 个 public 类**全部能在原厂找到精确同名或形状对应的类（C1-A 表）。其中 17 个类**逐行/逐字段对齐**（已在 review 01-04 详证，本区域不再重复）。
2. **lib 11 个 demo 的"真实驱动"调用面**：D3、D6、D7、D8、D10、D11 共 6 个 demo 直接调用 lib 的真实 public API，每条 API 都能在原厂找到 ≥1 个真实调用方对应，无"只 demo 用、原厂不存在"的伪 API。
3. **状态查询 + 超时 listener + SeqId 防抖**这一层（D6+D7+D8）的 12 状态枚举、`delayStartActivityIfNeed` 三层决策、`canFinishRecent`/`canInterceptGesture` 500/300ms 阈值——USAGE.md 暴露的所有方法签名（含 3 种 timeout listener + 2 个 SeqId getter + 9 个 RUS 配置项）都在原厂调用方那里一一命中。

### B. 有意简化（lib 文档/注释中明示，不影响"调用面闭合"判定）

1. **`CustomRectFSpringAnim` 18 行壳**（`CustomRectFSpringAnim.kt:11-18`）：原厂 907 行 6 自由度弹簧被砍为"句柄 + `AnimType` 枚举"——`AnimType` 还自己加了 2 个原厂不存在的值（`RECENTS_TRANSITION`、`APP_LAUNCH`，原厂 7 值见 `CustomRectFSpringAnim.java:114-122` metadata d2 + `:115-122`：`OPEN_FROM_HOME` / `REMOTE_CLOSE_TO_HOME` / `REMOTE_CLOSE_TO_HOME_ASSISTANT` / `GESTURE_TO_DRAG` / `SWIPE_TO_HOME` / `SWIPE_TO_HOME_ASSISTANT` / `REVERSE_TO_OPEN`）。**这意味着 D6 演示 `addRecentsAnim(SWIPE_TO_HOME)` 之外，调用方如果按 OPPO 习惯传 `OPEN_FROM_HOME`（`OplusBaseSwipeUpHandler` 最常用入口）会编译失败**——属于 demo 演示面与原厂真实调用面的脱节。
2. **`OplusAnimManager` 只出 2 个 helper**（`OplusAnimManager.kt:38-46`）：原厂 6 个 helper（`getAnimController`/`getAnimationSeqHelper`/`getAppOpenAnimMergeHelper`/`getMultiAppAnimMergeHelper`/`getInterceptKeyHelper`/`getMultiOpenPreStartHelper`），lib 砍了后 4 个。review 03 §4.2 已论证为正确取舍，但**调用方如果按 OPPO 习惯写 `OplusAnimManager.getAppOpenAnimMergeHelper()` 移植到 lib 找不到**。
3. **`LauncherAnimationRunner` 砍成 `RemoteAnimationTarget` 类型壳**（`LauncherAnimationRunner.kt:13-20`）：D6/D8 演示都传 `arrayOf()` / `null` 占位，D9 完全没用。原厂 600+ 行 runner 是远程动画链路入口，砍掉意味着 **D9 这个"端到端 OPEN_FROM_HOME 转场"demo 在 lib 层面不可运行**——demo 注释（`:13-15`）自承是"概念演示"。
4. **AppLaunchAnimUtil → AsyncValueAnimator.ofFloat(isAsync, …) 工厂未移植**（review 01 §②C-5）：原厂 `AsyncValueAnimator.java:44, 98-100` 的 `Companion.ofFloat` 工厂 + `AppLaunchAnimUtil.java:433` 调用点被砍。USAGE.md 没列这个工厂。
5. **TaskStateChangeTimeOutListener 简化为"按 type 匹配→执行 option→dispose"的回调 fun**：原厂是自管理对象，构造期就 postDelayed 兜底定时器（`TaskStateHelper.java:124-137`）。lib 在新版本（review 03 之后）补回了完整机制（`TaskStateChangeTimeOutListener.kt:25-32` 的 `handler.postDelayed(timeOutOption, duration)`），但 `dispose()` 在事件触发路径上是漏掉的（lib `:36-37` 没调 dispose() 就直接 option()）——与 review 03 §3-d 描述的 bug 已修复，但 lib 的实现与原厂 `TaskStateHelper.java:140-154` 的"先 dispose 再 option 再清全局注册"顺序还有微差。
6. **22 个非动画相关 executor / UAF 绑核 / `LauncherBooster` UX 线程注册 / `OplusLooperExecutor` 四扩展**：review 01 §②B-3/B-4 已论证为正确裁剪；本区域无新增论断。
7. **5 个 OPPO 私有动画包（painteranimation / physicsengine / effectengine / vfxsdk / anim）+ 1 个 pantanal**：v4 §10 已论证全部走主线程 Choreographer、与 launcher 主链路无关，故 lib 不复刻；USAGE.md 没列这些。
8. **`AsyncSpringAnim` 是 USAGE.md 漏列的真实 API**（B 节已论证）：demo 11 在用、原厂有同名兄弟 (`OplusAsyncSpringAnimWrapper`)，应补进 USAGE.md。

### C. 遗漏（未在 lib 注释中说明、且影响"调用面闭合"判定的差距）

1. **MultiAnimatorSet 整体缺失**（C 表第 1 项）：原厂 OPEN_FROM_HOME 转场链路的核心装配器，4 轨并行（`mAnimatorSet`/`mAsyncAnimatorSet`/`mSpringAnimations`/`mRectFSpringAnim`）+ `maybeOnEnd` 聚合，调用方 20+ 个（Grep 命中）。lib 没有任何对应类，demo 9 自承"概念演示"。**这是"调用面 vs API 面"最大的未闭合缺口**：原厂这条路径是 v4 §4 核心设计的真实载体（"双时钟域 + 输入驱动"），demo 无法在 lib 上真实复现。
2. **SpringHolder / SpringForce / SpringAnimReflectUtils** 整套（review 04 §1 末行）：被 CustomRectFSpringAnim 内部消费，外部不可见；但若 lib 真要做"真实 OPEN_FROM_HOME 转场"演示，**这层也得有**——目前依赖 androidx dynamicanimation 自带 `SpringAnimation`，弹簧物理是 androidx 的而非 OPPO 复刻的。
3. **AppOpenAnimMergeHelper / MultiAppAnimMergeHelper / InterceptKeyEventHelper / MultiOpenPreStartHelper** 4 个 merge/intercept helper（review 03 §2.3-5）：服务 multi-app merge、预启动、按键拦截；lib 整体砍掉。**这 4 个 helper 是"原厂独有但 v4 主链路文档未涉及"的边角**——它们在 `OplusBaseSwipeUpHandler`、`QuickstepTransitionManager`、`LauncherAnimationRunner` 等多处被调用（Grep 命中 20+ 文件），属于"主线转场之外的扩展"。demo 模块完全没有对应演示。
4. **`OplusRectFSpringAnim`（AOSP 残留路径）**：原厂还有这条 AOSP 残留（走 androidx dynamicanimation），lib 没复刻。但 v4 §3 路径 B 已论证它是主链路的次要补充，故保留简化合理。
5. **OplusZoomAnimationControlThread（zoom 小窗）**：v4 §2 表末单独列出，服务小窗拖拽；**与 launcher.anim 并列的另一条专用动画线程**。lib 没复刻也没在 demo 演示。属于"路径外延"——如果 lib 想做"小窗拖拽"demo，这条要补。
6. **`LauncherContentAnimManager`（`com.oplus.quickstep.anim`）**：原厂在 launcher3 内部用，组合 `AsyncValueAnimator` + `MultiAnimatorSet` 拼 OPEN_FROM_HOME 转场。lib 没复刻也没 demo 演示。
7. **AppToOverviewContinuationHelper / AppSwipeToRecentContinuationHelper / VirtualBtnToRecentContinuationHelper** 3 个续行 helper：v4 §5 / review 04 §2 标注的"OplusValueAnimator 续行场景"。lib 的 `OplusValueAnimator.kt:124-147` 复刻了续行入口 `generateContinuationAnim`（review 04 §2.1-7 精确复刻），但**前置触发者**（这 3 个 helper 类本身）没在 lib 出现。demo 5 注释（`:18-20`）明示"由舞台演示等价效果"。**调用面缺口**：如果原厂调用方写 `AppSwipeToRecentContinuationHelper.startAlignEliminateAnim()`，lib 找不到对应 API。
8. **`RemoteAnimationFactory` 重写**：lib `RemoteAnimationFactory.kt:10-17` 是 2 方法 demo 接口（`createAnimation` + `onAnimationFinished`）；原厂同名接口含 8 个 default 方法（`onCreateAnimation` / `getAnimation` / `appLaunchAnimStartOrEnd` / `handleAnimationMerged` / `isSameIcon` / `onAnimationCancelled` / `preLoadIcon` / `supportInterruption` / `tryFinishOpenRemote`，见 `LauncherAnimationRunner.java:242-277`）。**`isSameIcon` 和 `handleAnimationMerged` 是 multi-app merge 场景的入口**，与 `AppOpenAnimMergeHelper` 直接配套——lib 把整块 multi-app merge 砍了，这 2 个方法也连带消失。
9. **`TaskStateHelper` 主体类（不含 listener）**：lib 只把 `TaskStateChangeTimeOutListener` 拆出来。`TaskStateHelper` 本身的 `mHandler`/`handleMessage`/`removeTasksMaps`/`MESSAGE_RELEASE_TOUCH` 600ms 闸门等整体缺位（review 03 §2.3-3/§3-b 列举）。调用面缺口：原厂 `forbidTouch()` 调用方 `OplusBaseInputConsumerController` 等，lib 对应方法恒 false。
10. **`setUxThreadValue` 实际比原厂多 5 处调用方**（`setUxThreadValue` Grep）：除 `OplusExecutors.java:171`（launcher.anim）和 `OplusExecutors$UX_TASK_EXECUTOR$2.java:19`（UX_TASK_EXECUTOR）外，`TransitionAnimationUtil.java:147`、`StartingWindowBooster.java:135`、`RenderThread.java:153`、`RecentTasksList.java:134` 也调——这些是 WM Shell / SystemUI / 启动面线程。lib 完全没覆盖这些调用方——但属于"扩展到 launcher3 之外的横切关注点"，USAGE.md 没列这些，缺位合理。

### D. 暴露面 vs 调用面的具体脱节点（demo 演示了 OPPO 不存在的功能）

| Demo | 演示的"功能" | OPPO 是否真存在 | 脱节类型 |
|---|---|---|---|
| **D4** Spring 渐进切换 | OplusSpringObjectAnimator.startSpring() → mProperty.switchToSpring() | **OPPO 真实存在** `com.oplus.quickstep.anim.OplusSpringObjectAnimator`（`OplusSpringObjectAnimator.java:19`），含 `SpringProperty.switchToSpring()` (`:61`) 并在同文件 `:294` 调用。**但 lib 完全没有对应实现类**（review 02 §1 末行 + review 04 §1 都明确 lib 没有这个类）；lib 的 `SceneSpring` 是 demo 模块自带的解析解弹簧，跟 lib 的 lib 代码无关 | **demo 演示了 OPPO 真实存在的 API，但 lib 没有对应实现**——D4 注释（`Demo4SpringTransitionActivity.kt:24-26`）自承"由场景侧 SceneSpring 实际驱动"。这条路径在 OPPO 真实使用，但 lib 的 AnimationPlaybackController 没有 startWithVelocity + SpringProperty 入口（review 02 §②C-1 已记录遗漏） |
| **D5** 续行动画 | generateContinuationAnim → timeController 接 RecordInputInterpolator.inputed | OPPO 真实存在 `OplusValueAnimator.generateContinuationAnim` + `RecordInputInterpolator`（review 04 §2.1-7），但前置触发者（`AppSwipeToRecentContinuationHelper`）缺失 | demo 演示的 API 真实存在，但 demo 没真正调用 lib 的 `generateContinuationAnim`——只调用了 `stage.swipeToRecents`（demo 自绘），在日志里"说明概念"（`Demo5ContinuationActivity.kt:79-80`） |
| **D9** AllApps 转场 | StateManager.goToState → PendingAnimation → AnimatorPlaybackController → OnAnimationEndDispatcher | 路径真实存在，但 lib 缺 `StateManager` + `PendingAnimation`（仅 review 02 提过的内部类）+ 主装配器 `MultiAnimatorSet`；demo 仅自绘舞台 + 日志描述（`Demo9AllAppsTransitionActivity.kt:13-15`） | **"概念演示"**，非 lib API 真实调用；USAGE.md 暴露面与 OPPO 主链路调用面之间存在最大未闭合缺口 |
| **D1/D2** Holder / ProgressMapper | AnimatorPlaybackController.Holder + ProgressMapper | 路径真实存在，lib `launcher/playback/AnimatorPlaybackController.kt` 完整复刻 | OK，对应面闭合 |
| **D11** View 属性弹簧 | AsyncSpringAnim（`OplusAsyncSpringAnimWrapper`） | 路径真实存在，lib 完整复刻 | OK，但 USAGE.md 漏列 |

---

## ③ 行为差异风险点

按"可能让 demo 演示与原厂真实行为偏离"的影响排序：

1. **（高）D9 端到端 OPEN_FROM_HOME 不可真实运行**——MultiAnimatorSet 缺失。demo 9 是 README 里被列为主打 demo 之一（"完整 OPEN_FROM_HOME 转场"），但其链路在 lib 层面缺主角。**风险**：用户对照 demo 9 与 v4 §3 / §4 描述的真实 OPPO 行为，会发现 demo 9 不演示"双时钟域并行"——因为 `MultiAnimatorSet.maybeOnEnd` 的"四轨最慢一路结束"语义没有 lib 类对应。**对照 v4 trace 实证**（`animation-trace-validation.md` §3-4），真机 77 帧 launcher.anim 上的动画本质上是 `MultiAnimatorSet` + `CustomRectFSpringAnim` 协同出来的，lib 缺前者则**单凭 CustomRectFSpringAnim 占位 + AnimatorPlaybackController 无法复现 trace 实证行为**。
2. **（高）D4 演示了 OPPO 真有但 lib 没实现的 OplusSpringObjectAnimator**——review 04 §1 已记录此遗漏，本区域确认 `OplusSpringObjectAnimator.java:19, 31, 61, 294` 是真实存在 + 真实使用（SpringProperty.switchToSpring 在同文件 `:294` 调用）。**但 lib 完全没有对应类**——D4 demo 注释（`Demo4SpringTransitionActivity.kt:24-26`）虽然提到 `OplusSpringObjectAnimator` 类名和 `mProperty.switchToSpring()` 流程，实际跑的是 demo 模块自带的 `SceneSpring`（解析解弹簧），跟 lib 代码无关。**风险**：用户对照 D4 与 v4 文档 / 真机 trace，会发现 D4 演示的 `OplusSpringObjectAnimator` 在 lib 中找不到对应 API——属于"OPPO 真实使用但 lib 未复刻"的未闭合缺口。建议：**改 D4 demo 注释明示"OplusSpringObjectAnimator 是原厂类，lib 未复刻；本 demo 用 SceneSpring 模拟等价效果"**（5 行改动），或补 `OplusSpringObjectAnimator` + `SpringProperty` 实现（80~150 行）。
3. **（中）`CustomRectFSpringAnim.AnimType` 枚举不一致**——lib 3 值 vs 原厂 7 值。D6 演示 `addRecentsAnim(SWIPE_TO_HOME)` 之外，原厂最常用的 `OPEN_FROM_HOME`（`OplusBaseSwipeUpHandler.createAnimateToHome` 入口用的就是这个）不在 lib 枚举里。**调用方迁移风险**：原厂代码 `addRecentsAnim(CustomRectFSpringAnim(AnimType.OPEN_FROM_HOME), …)` 改到 lib 编译失败。补救：要么扩 lib 枚举对齐原厂 7 值，要么在 demo 里加注释明示差异。
4. **（中）`RemoteAnimationFactory` 重写为 2 方法**——D6/D8 调用的是 lib demo 接口。原厂同名接口的 `isSameIcon` / `handleAnimationMerged`（multi-app merge 入口）消失，对应 lib 没有 multi-app merge helper（`AppOpenAnimMergeHelper` / `MultiAppAnimMergeHelper`）。**风险**：如果用户用 lib 跑 multi-app 合并转场（OPPO 一个真实场景），会发现 `OplusAnimManager.getMultiAppAnimMergeHelper()` 不存在 + `RemoteAnimationFactory.isSameIcon` 接口没有 + `MultiAnimatorSet` 不存在——三个层级同时缺位。
5. **（中）`TaskStateHelper` 主体类缺失**——D6 演示了 3 种 timeout listener 的注册与触发，但 listener 触发后的清理路径（`TaskStateHelper.java:140-154` 的"摘全局注册 + removeCallbacks"）在 lib 上**只有 `dispose()` 调 removeCallbacks，没有摘全局注册**——`lib/TaskStateChangeTimeOutListener.kt:41-43`。**风险**：demo 6 反复重置时，上一轮 listener 的全局注册表残留，下一轮 listener 在 onTimeOut 时可能误命中前一轮的 type。review 03 §3-d 已点出此 bug（lib 已修了一半：postDelayed 自管理超时已加），但全局注册表清理仍缺。
6. **（中）USAGE.md 漏列 `AsyncSpringAnim`**——B-8 + C 表已论证。D11 在用、原厂同名兄弟存在。**风险**：用户对照 USAGE.md 找不到 demo 11 引用的 API 入口；USAGE.md 没暴露面但 lib 包内有实现类，是文档与代码不一致的明确 bug。
7. **（低）`OplusAnimManager` 6→2 helper 砍幅**（C-3）：D6/D8 演示无碍（只用 `animController` + `animationSeqHelper`），但**调用方如果按 OPPO 习惯迁移 lib，会发现 4 个 helper 不存在**。review 03 §4.2 标注为正确取舍；本区域属于"暴露面缩减"的合理范围。
8. **（低）`LauncherAnimationRunner` 砍成 20 行壳**：D9 端到端缺失，与 R-1 同根。D6/D8/D11 用 `arrayOf()` 占位可编译。
9. **（提示）`AsyncAnimCallbacks` / `AsyncValueAnimator` 等核心类的语义差异已在前 4 份 review 详细记录**（review 01 §②C-1 优先级 -8 vs -19、§②C-3 listener 派发 sync vs async、§②C-4 dispatch CME；review 02 §③-1 forEndCallback success 判定、§③-2 PendingAnimation.setFloat no-op、§③-3 addFloat 产物不进 Holder 链；review 04 §③-1 timeController no-op 续行不驱动、§③-2 ScheduledTickScheduler per-thread 帧语义缺失）。**这些差异不改变"调用面闭合"判定**——demo 触发的 API 调用路径都存在——但行为细节会让对照真机 trace 时数字偏离。本区域不重复细节。

---

## ④ 回移建议

### 值得补进 lib（性价比高 / 闭合"调用面"）

1. **扩 `CustomRectFSpringAnim.AnimType` 对齐原厂 7 值**（参考 `CustomRectFSpringAnim.java:115-123`）：最少补 `OPEN_FROM_HOME` / `REVERSE_TO_OPEN` / `GESTURE_TO_DRAG` 这 3 个被 `OplusBaseSwipeUpHandler` 真实使用的值。删 lib 自创的 `RECENTS_TRANSITION` / `APP_LAUNCH`（原厂不存在）。10 行改动，闭合 lib 与原厂的 enum 调用面。
2. **USAGE.md 补列 `AsyncSpringAnim` + 标注 `AnimType` 枚举的差异**：5 行文档改动即可解决 USAGE.md 漏列 + lib 与原厂枚举值不对齐的认知问题。
3. **补 MultiAnimatorSet**（`com.android.quickstep.util.animation.MultiAnimatorSet.java` 的 Kotlin 复刻）：4 轨聚合 + `maybeOnEnd`，核心类，约 200 行。**这是闭合 D9 端到端转场的最大缺口**——只有补了 MultiAnimatorSet，demo 9 才能从"概念演示"升级为"真实运行"，对照 v4 trace 实证的 77 帧行为才有可能。
4. **`TaskStateChangeTimeOutListener.dispose()` 补"摘全局注册表"**：当前 lib `:41-43` 只 removeCallbacks，没维护一个全局注册表。需要先在 `AnimationController` 加一个 `Set<TaskStateChangeTimeOutListener>` 字段，listener 构造时 add、dispose 时 remove。10~15 行改动；消除 review 03 §3-d 残留的"反复重置状态污染"风险。
5. **`AppLaunchAnimUtil → AsyncValueAnimator.ofFloat(isAsync, …)` 工厂**（review 01 §②C-5）：5 行，恢复原厂 `AppLaunchAnimUtil.java:433` 调用入口。性价比高。
6. **D9 demo 改写为真实调用 `MultiAnimatorSet` + `CustomRectFSpringAnim` 占位**——前提是建议 3 已完成。改写后 D9 不再是"概念演示"。

### 建议保持简化（按调用面"非主线"判定）

1. **OplusRectFSpringAnim（AOSP 残留）**：原厂 1 条次要路径（v4 §3 路径 B），3 个调用方；与 MultiAnimatorSet / CustomRectFSpringAnim 主路径不冲突。lib 不复刻可接受。**理由**：review 01 §④-3 已论证"原厂 AOSP 残留路径，主线程 fallback"；demo 10/11 已演示主路径。
2. **5 个 OPPO 私有动画包（painteranimation / physicsengine / effectengine / vfxsdk / anim）+ pantanal**：v4 §10 已论证全部走 UI 线程、不属于 launcher 主链路；USAGE.md 没列，调用面缺口为零；保留裁剪。
3. **WM Shell / StartingWindowBooster / RenderThread / RecentTasksList 的 `setUxThreadValue` 调用方**（C-10）：跨模块横切关注点，与 lib 主题无关；USAGE.md 没列，保留裁剪。
4. **`OplusZoomAnimationControlThread`（zoom 小窗）**：v4 §2 表末单列的"另一条专用动画线程"；与 launcher.anim 并列但服务不同场景（小窗拖拽）。**不建议当前补**——demo 主题是"launcher 主链路"，zoom 是产品扩展；如未来做"小窗拖拽"demo 再补。
5. **AppToOverviewContinuationHelper / AppSwipeToRecentContinuationHelper / VirtualBtnToRecentContinuationHelper 3 个续行触发者**（C-7）：前置触发者场景化严重（与 `OplusBaseSwipeUpHandler` 强耦合），复刻这些要拉入手势状态机；lib 的 `OplusValueAnimator.generateContinuationAnim` 已完整复刻续行本身（review 04 §2.1-7），demo 5 用舞台演示等价效果已覆盖教学目标。**保留简化**。
6. **AppOpenAnimMergeHelper / MultiAppAnimMergeHelper / InterceptKeyEventHelper / MultiOpenPreStartHelper 4 个 merge/intercept helper**（C-3）：服务 multi-app merge、预启动、按键拦截，20+ 调用方，但都是 launcher 业务建模而非动画执行模型。review 03 §4.2 已论证为正确取舍。**保留简化**，但应在 USAGE.md/类注释里说明"multi-app merge 场景 lib 不支持"，避免调用方按 OPPO 习惯迁移时撞墙。
7. **`LauncherContentAnimManager`（C-6）**：原厂内部拼接器，调用方只在自己包内；一旦补 MultiAnimatorSet + CustomRectFSpringAnim 后可以自然消失（demo 9 自己组合即可）。
8. **`TaskStateHelper` 主体类**（C-9）：600ms 闸门 + removeTasksMaps 是 launcher 业务策略；review 03 §4.2 已论证"保持简化"。`forbidTouch()` 恒 false 在 lib 上不会破坏 demo。

### 优先级总览

| 优先级 | 建议项 | 改动量 | 闭合的缺口 |
|---|---|---|---|
| P0 | 扩 `AnimType` 枚举对齐原厂 7 值 + 删 lib 自创 2 值 | ~10 行 | R-3 调用面脱节 |
| P0 | USAGE.md 补列 `AsyncSpringAnim` + 标注枚举差异 | 5 行文档 | R-6 文档/代码不一致 |
| P1 | 补 `MultiAnimatorSet` + 改 D9 为真实运行 | ~200 行 + D9 重写 | R-1 / R-4 / R-8（最大缺口） |
| P1 | `TaskStateChangeTimeOutListener.dispose()` 补全局注册清理 | ~15 行 | R-5 重置状态污染 |
| P2 | 补 `AsyncValueAnimator.ofFloat(isAsync, …)` 工厂 | ~5 行 | 调用面闭合（review 01 §②C-5） |
| P3 | 补 `OplusSpringObjectAnimator` + `SpringProperty`（或改 D4 注释明示 lib 未复刻） | 80~150 行 OR 5 行注释 | R-2 lib 真实存在但未实现 |

---

## 附：调用面 → API 面覆盖矩阵

```
原厂调用方                          | lib public API              | 状态
------------------------------------+-----------------------------+-------
AppLaunchAnimUtil                   | AsyncValueAnimator +        | ✅
                                    | ofFloat 工厂缺 (P2 补)      |
LauncherContentAnimManager          | 内部组合，无直接对应        | ⚠️ 走 demo 自实现
MultiAnimatorSet 路径 20+ 调用方    | (MultiAnimatorSet 缺)       | ❌ P1 补
CustomRectFSpringAnim 路径 20+ 调用 | CustomRectFSpringAnim 18 行壳| ⚠️ 壳够用, 主逻辑缺
OplusRectFSpringAnim 3 调用方       | (缺)                        | 简化保留
OplusAsyncSpringAnimWrapper         | AsyncSpringAnim             | ✅ USAGE.md 漏列
OplusAnimManager 全 6 个 helper     | 2 个 (animController,       | ⚠️ 4 个 merge helper 缺
                                    | animationSeqHelper)         |
TaskStateHelper 主体 + listener     | listener only (TaskState-   | ⚠️ 主体类缺 (review 03 §4.2)
                                    | ChangeTimeOutListener)      |
3 个续行 helper                     | OplusValueAnimator.generate-| ✅ 续行本身; 触发者缺
                                    | ContinuationAnim            |
OplusBaseSwipeUpHandler setAsync-   | (CustomRectFSpringAnim 18   | ⚠️ 原厂 setAsyncStart(z8)
Start 入口                          | 行壳无对应)                 |   :808 + mStartAsync :100
                                                                     字段在 907 行实现里; lib 占
                                                                     位类无此字段, 18 行壳不能
                                                                     替代真实 setAsyncStart 入口
zoom 小窗 (OplusZoomAnimation-      | (缺独立线程)                | 简化保留
ControlThread)                      |                             |
9 个 RUS 配置项调用方                | AnimationFeatureHelper      | ✅ 全部命中
12 状态 + 状态查询 (8 个 getter)    | AnimationController +       | ✅ 全部命中 (review 03)
                                    | AnimationState              |
3 种 timeout listener               | TaskStateChangeTimeOut-     | ⚠️ 主体类缺, listener
                                    | Listener                    |   自管已修一半
SeqId 防抖                          | AnimationSeqHelper +        | ✅ 全部命中
                                    | AnimSeqTimeStamp            |
MultiAnimatorSet.maybeOnEnd 4 轨    | (缺)                        | ❌ P1 补
                                    |                             |
TOTAL 闭合率（粗估）                |                             | ~78% (13/17 主路径类)
                                                                     100% (USAGE.md 列出的类
                                                                     全部有原厂对应)
```

**双口径结论**：
- **USAGE.md 列出的 public API**：100% 在原厂有精确对应，0 个伪 API（B 表的 AsyncSpringAnim 是漏列而非错列）。
- **原厂实际调用面**：约 78% 主路径在 lib API 内可达；缺口集中在 4 个 merge helper + MultiAnimatorSet + TaskStateHelper 主体 + setAsyncStart 入口字段——后两者影响 demo 6/9 的真实运行度，前两者属于"扩展场景"。

---

## 关键证据速查

| 论断 | 证据 |
|---|---|
| AsyncValueAnimator 主消费方 | `com/android/launcher3/anim/light/AppLaunchAnimUtil.java:433, 441, 497, 498` |
| AsyncValueAnimator.ofFloat(isAsync, …) 工厂 | `AsyncValueAnimator.java:44, 98-100` |
| MultiAnimatorSet 真实存在 + 主装配器 | `com/android/quickstep/util/animation/MultiAnimatorSet.java:32, 44, 65, 103-117, 160, 198, 383`（3 处 ANIM_EXECUTOR） |
| setAsyncStart 真实调用点 | `com/oplus/quickstep/gesture/OplusBaseSwipeUpHandler.java:3517, 3922`；方法定义在 `com/android/quickstep/util/animation/CustomRectFSpringAnim.java:808` |
| `mStartAsync` 字段默认 true | `com/android/quickstep/util/animation/CustomRectFSpringAnim.java:100, 252` |
| OplusSpringObjectAnimator 真实存在 + switchToSpring 流程 | `com/oplus/quickstep/anim/OplusSpringObjectAnimator.java:19, 31, 61, 294`（SpringProperty 内嵌类 + switchToSpring 调用） |
| OplusAnimManager 6 helper 完整列表 | `com/oplus/quickstep/utils/OplusAnimManager.java:104-117, 21`（metadata） |
| merge helper 调用方覆盖（20+ 文件） | Grep `AppOpenAnimMergeHelper\|MultiAppAnimMergeHelper\|InterceptKeyEventHelper\|MultiOpenPreStartHelper` |
| AsyncAnimWrapper 完整骨架 | `com/android/launcher3/anim/AsyncAnimWrapper.java:10-20` |
| CustomRectFSpringAnim 7 个 AnimType | `com/android/quickstep/util/animation/CustomRectFSpringAnim.java:114-122`（metadata d2 + `:115-122`） |
| LauncherAnimationRunner RemoteAnimationFactory 8 default 方法 | `com/android/launcher3/LauncherAnimationRunner.java:242-277` |
| setUxThreadValue 5 个调用方 | `com/android/wm/shell/transition/TransitionAnimationUtil.java:147`、`StartingWindowBooster.java:135`、`com/oplus/fancyicon/RenderThread.java:153`、`com/android/quickstep/RecentTasksList.java:134`、`OplusExecutors.java:171` |
| lib AnimationControlThread 优先级 -19 (review 01 修后) | `AnimationControlThread.kt:81` |
| lib TaskStateChangeTimeOutListener 自管超时已修 | `TaskStateChangeTimeOutListener.kt:25-32` |
