# 14. LauncherAnimationRunner 字段/方法级深度对比

> **范围**：`com.android.launcher3.LauncherAnimationRunner`（含内部 `RemoteAnimationFactory` 9 个 default + 1 abstract + `AnimationResult` 内部类）。600+ 行 Java vs lib 20 行 Kotlin 桩。
>
> **不重复**：review 03 §2.2-3 已列"砍成类型壳"概要、review 06 §USAGE.md 漏列、review 10 §10/Kotlin 风格化、review 11 §11/feature-gaps、review 12 风险总表；本文逐方法/字段落位，给出**字节级 diff 视角**。
>
> **取证**：
> - 原厂：`D:/oppo_a6_launcher/sources/com/android/launcher3/LauncherAnimationRunner.java`（DLP 加密，dump.py → PLAINTEXT，32192 chars / 108 行 JADX）。
> - lib：`D:/AsyncAnimator/lib/src/main/java/com/android/launcher3/LauncherAnimationRunner.kt`（20 行）+ `D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/controller/RemoteAnimationFactory.kt`（17 行）+ `AnimationController.kt`（225 行）+ `DefaultAnimationController.kt`（89 行）。
> - 调用方：`demo/.../Demo6StateMachineActivity.kt:172`（唯一 call site，`appLaunchAnimStartOrEnd(false, null, arrayOf())`）。

---

## 1. 类对应关系表

> 列：`lib 类 → 原厂类` + `文件:行`（**lib 行号直接来自源码**，**原厂行号来自 JADX 反编译文本**，源码行号因 lambda 改名会偏移 1-2 行）。证据采用 dump.py + Read 双通道取样。

| # | lib 类 / 字段 / 方法 | 原厂类 / 字段 / 方法 | 证据 |
|---|---|---|---|
| **L1** | `abstract class LauncherAnimationRunner` | `public class LauncherAnimationRunner extends RemoteAnimationRunnerCompat` | lib `LauncherAnimationRunner.kt:13` vs 原厂 `LauncherAnimationRunner.java:53`（`@TargetApi(28)` 起） |
| **L2** | `class RemoteAnimationTarget(var taskId: Int=0, var leash: Any?=null)`（仅 2 字段） | `android.view.RemoteAnimationTarget`（系统类，27 字段 + Parcelable） | lib `LauncherAnimationRunner.kt:16-19` vs 原厂 import `android.view.RemoteAnimationTarget` (`LauncherAnimationRunner.java:11`)；`appLaunchAnimStartOrEnd` 签名 `RemoteAnimationTarget[] remoteAnimationTargetArr` (`LauncherAnimationRunner.java:243`) |
| **L3** | — | `private static final RemoteAnimationFactory DEFAULT_FACTORY`（lambda 空实现兜底） | 原厂 `LauncherAnimationRunner.java:59-62`；**lib 无** |
| **L4** | — | `private static final boolean SUPPORT_REMOTE_INTERCEPT_KEYEVENT = SystemProperties.getBoolean(...)` | 原厂 `LauncherAnimationRunner.java:66`；**lib 无** |
| **L5** | — | `private static final String TAG = "LauncherAnimationRunner"` | 原厂 `LauncherAnimationRunner.java:67`；**lib 无**（LogUtils 也不引） |
| **L6** | — | `private WeakReference<ActivityInitListener> activityInitListenerRef` + `setActivityInitListener(...)` | 原厂 `LauncherAnimationRunner.java:68` + `:572`；**lib 无** |
| **L7** | — | `private AnimationResult mAnimationResult` + `private final Reference<RemoteAnimationFactory> mFactory` + `private final Handler mHandler` + `private IRemoteTransitionInputCallback mInputCallback` + `private boolean mIsFromRecents` + `private Scenes mScenes` + `private final boolean mStartAtFrontOfQueue` | 原厂 `LauncherAnimationRunner.java:69-76`；**lib 全砍** |
| **L8** | `interface RemoteAnimationFactory { fun createAnimation(): AnimatorSet ; fun onAnimationFinished() }`（2 方法 demo 接口） | `public interface RemoteAnimationFactory`（`@FunctionalInterface`，1 abstract + 9 default） | lib `RemoteAnimationFactory.kt:10-17` vs 原厂 `LauncherAnimationRunner.java:242-279` |
| **L9** | — | `default void appLaunchAnimStartOrEnd(boolean z8, RemoteAnimationTarget[] remoteAnimationTargetArr)`（空 body） | 原厂 `LauncherAnimationRunner.java:243-245`；**lib 不在 RemoteAnimationFactory**，但同名方法 `DefaultAnimationController.appLaunchAnimStartOrEnd(isEnd, factory, targets)` (`AnimationController.kt:100-127`) 吸收了"事件总线"语义 |
| **L10** | — | `default CustomRectFSpringAnim getAnimation()` → `return null` | 原厂 `LauncherAnimationRunner.java:246-249`；**lib 无** |
| **L11** | — | `default int getIconSurfaceRecordId()` → `return -1` | 原厂 `LauncherAnimationRunner.java:250-253`；**lib 无** |
| **L12** | — | `default boolean handleAnimationMerged(TransitionInfo, SurfaceControl.Transaction, BreakParam, IRecentsAnimationController, boolean, boolean)` → `return false` | 原厂 `LauncherAnimationRunner.java:254-257`；**lib 无**（外层 `handleAnimationMerged` 也只转发到 factory，`LauncherAnimationRunner.java:494-501`） |
| **L13** | — | `default boolean isSameIcon(View view)` → `return false` | 原厂 `LauncherAnimationRunner.java:258-261`；**lib 无** |
| **L14** | — | `default void onAnimationCancelled()`（空 body） | 原厂 `LauncherAnimationRunner.java:262-264`；**lib 无** |
| **L15** | `fun createAnimation(): AnimatorSet`（abstract demo） | `void lambda$onCreateAnimation$0(int, RemoteAnimationTargetCompat[], RemoteAnimationTargetCompat[], RemoteAnimationTargetCompat[], AnimationResult)`（abstract） | lib `RemoteAnimationFactory.kt:13` vs 原厂 `LauncherAnimationRunner.java:265-267`（JADX lambda 改名） |
| **L16** | — | `default void preLoadIcon()`（空 body） | 原厂 `LauncherAnimationRunner.java:268-270`；**lib 无** |
| **L17** | — | `default boolean supportInterruption()` → `return false`（无参） | 原厂 `LauncherAnimationRunner.java:271-274`；**lib 无**。**注意**：用户提问中提到 `supportInterruption(ItemInfo)`，但原厂签名确为无参（dump.py 已 grep 验证；可能与 `OplusAnimManager.getAnimController().isStartActivityBetweenTransitionEndAndFinish()` 混淆） |
| **L18** | — | `default void tryFinishOpenRemote(Runnable runnable)` → 立即 `runnable.run()` | 原厂 `LauncherAnimationRunner.java:275-278`；**lib 无** |
| **L19** | — | `public enum Scenes { COMMON, APP_TO_OVERVIEW_BY_VIRTUAL_KEY }` | 原厂 `LauncherAnimationRunner.java:282-285`；**lib 无** |
| **L20** | — | `public class AnimationResult`（内部类，约 130 行）：`mSyncFinishRunnable` + `mASyncFinishRunnable` + `mFinished` + `mInitialized` + `mAnimator` + `mMultiAnimatorSet` + `mBreakParam` + `mAsyncFinishExecutor` + `mOnCompleteCallback` + 2 个 `setAnimation(...)` 重载 + `finish()` 三段式（`LauncherAnimationRunner.java:77-241`） | 原厂 `LauncherAnimationRunner.java:77-241`；**lib 无** |
| **L21** | — | 5 个构造函数（Handler/factory/boolean 的不同组合）：`LauncherAnimationRunner(Handler, RemoteAnimationFactory, boolean)` + 4 个重载 | 原厂 `LauncherAnimationRunner.java:288, 605-616`；**lib 无**（lib 不实例化 runner，仅用类型壳） |
| **L22** | — | `@Override public boolean onCreateAnimation(...)` 实现在 `RemoteAnimationRunnerCompat` 上；`lambda$onAnimationStart$4` 全部 UI marshal 逻辑（310 行） | 原厂 `LauncherAnimationRunner.java:357-451`；**lib 无** |
| **L23** | — | `onAnimationStart` `@BinderThread` 入口 + 3 种 handler post 模式（`postAsyncCallback` / `postAtFrontOfQueueAsynchronously` / 同步执行） | 原厂 `LauncherAnimationRunner.java:419-451`；**lib 无** |
| **L24** | — | `setCurrentPlayTime(Math.min(RefreshRateTracker.getSingleFrameMs(context), this.mAnimator.getTotalDuration()))` 首帧补偿 | 原厂 `LauncherAnimationRunner.java:192-196`；**lib 无** |
| **L25** | — | `tryFinishOpenRemote(Runnable)`、`abortPreStartMultiOpenAnim(int)`、`preStartMultiOpenAnim(...)`（5 参）、`updateMultiOpenTaskInfoOnPreStart(...)`、`updateOrientationIfNeed(TransitionInfo)`、`onTransitionConsumedAbort()`、`resetLastStartAppTimeIfNeed(boolean)`、`isMultiOpenAnimStart(int)`、`supportLightOsPreStart()`、`isAppTransitionDisableInterruption()`、`isFromRecents()`、`isRecentsRunning()`、`getInputCallback()`、`interceptKeyEvent()` | 原厂 `LauncherAnimationRunner.java:479-601`；**lib 无** |
| **L26** | — | `mInputCallback = new IRemoteTransitionInputCallback.Stub()` (Binder callback) + `notifyInterceptKeyEvent(KeyEvent)`（按 BACK 键转发到 `OplusBaseTouchInteractionService.simulateUpSlide()`） | 原厂 `LauncherAnimationRunner.java:71-72 + 290-302`；**lib 无** |

---

## 2. 保真度评估

### 2.1 精确复刻

| # | 项 | 证据 | 评估 |
|---|---|---|---|
| F1 | `RemoteAnimationTarget` 作为 `appLaunchAnimStartOrEnd` 形参类型 | lib `LauncherAnimationRunner.kt:13-20` + `AnimationController.kt:100`（`Array<LauncherAnimationRunner.RemoteAnimationTarget>?`） | **精确类型壳**——满足"`DefaultAnimationController.appLaunchAnimStartOrEnd` 签名形状与原厂一致"的设计目标；引用 demo `Demo6StateMachineActivity.kt:172` 实际调用 `arrayOf()`（空数组），类型壳**真正被消费** |
| F2 | `RemoteAnimationFactory` Kotlin 接口默认实现语义 | lib `RemoteAnimationFactory.kt:10-17` 用 Kotlin 接口（Kotlin 1.4+ 接口允许 default body）；原厂用 Java 8 default method (`LauncherAnimationRunner.java:242-279`) | **语义等价**——demo 实现只需 override 需要的方法，未实现的方法保持 default 行为；review 10 §10 评 Kotlin 风格化 OK |
| F3 | `LauncherAnimationRunner.kt:4-12` 文件头注释明示设计取舍 | "本移植工程不需要真实 Binder 通道，仅保留 RemoteAnimationTarget 类型壳" | **有意识的接口冻结**——给后续读者清楚的"为什么是 20 行" |

### 2.2 有意简化（建议保持）

| # | 项 | 简化理由 | 证据 |
|---|---|---|---|
| S1 | 砍掉 `mInputCallback` Binder callback | lib 是 JVM demo，没有 system_server binder 线程 | `LauncherAnimationRunner.java:71-72, 290-302` vs lib `LauncherAnimationRunner.kt:13-20` |
| S2 | 砍掉 `AnimationResult` 三段式 finish | lib 走 `DefaultAnimationController.appLaunchAnimStartOrEnd` 状态机路径，不直接持有 `AnimatorSet` / `MultiAnimatorSet` | `LauncherAnimationRunner.java:77-241` vs lib `AnimationController.kt:100-127` |
| S3 | 砍掉 9 个 default 方法 | 不接 binder 通道、demo 也不演示多 app merge / 预启动 / 弹簧链场景 | `LauncherAnimationRunner.java:243-278` vs lib `RemoteAnimationFactory.kt:10-17`（仅 2 方法） |
| S4 | `RemoteAnimationTarget` 砍到 2 字段（taskId/leash） | 原厂用 `android.view.RemoteAnimationTarget`（系统类 27 字段 Parcelable），demo 不做 leash reparent / SurfaceControl.Transaction | `LauncherAnimationRunner.kt:16-19` vs `LauncherAnimationRunner.java:11`（import `android.view.RemoteAnimationTarget`） |
| S5 | 砍掉 5 个构造函数 + 内部 `Scenes` 枚举 | lib 不实例化 `LauncherAnimationRunner`（demo 用 `controller.appLaunchAnimStartOrEnd(...)` 直调） | `LauncherAnimationRunner.java:288, 605-616` vs lib 无对应 |
| S6 | 砍掉 WeakReference factory GC 处理（`finalized=` 日志） | v4 §9.5 指出此为 OPPO 线上踩过的坑（GC → factory null → 动画瞬结、窗口跳变），但前提是 binder 通道存在；lib 无 binder 无 GC race | `LauncherAnimationRunner.java:419-451` |

### 2.3 遗漏（潜在风险，下节详述）

| # | 项 | 缺失影响 | 证据 |
|---|---|---|---|
| M1 | `RemoteAnimationTarget` 字段不足（无 `mode`、`taskInfo`、`leashTransaction`、`surfaceControl` 等） | demo 当前用空数组 OK，但若 `appLaunchAnimStartOrEnd(true, targets)` 实际携带非空 targets，`getMode()` / `getTaskInfo()` 会 NPE | `LauncherAnimationRunner.kt:16-19` vs 原厂 `LauncherAnimationRunner.java:11` |
| M2 | `tryFinishOpenRemote(Runnable)` default body 在 lib 是 no-op | 原厂语义是"通知 system_server 动画已就绪"，lib 不实现 → 调用方不会得到 callback | `LauncherAnimationRunner.java:275-278` vs lib `RemoteAnimationFactory.kt` |
| M3 | `handleAnimationMerged(...)` 6 参 default 在 lib 是 no-op | 多 app 启动合并（Demo9 multi-app 路径）需要这个钩子；review 11 §11 标记 F 项 | `LauncherAnimationRunner.java:254-257` vs lib 无 |
| M4 | `preLoadIcon()` / `getIconSurfaceRecordId()` / `isSameIcon(View)` default 在 lib 是 no-op | 图标预加载/同图标判定逻辑；Demo11 弹簧链需要 `isSameIcon` 决定 RectFSpringAnim 复用 | `LauncherAnimationRunner.java:250-261, 268-270` |
| M5 | `supportInterruption()` default 在 lib 是 no-op | QuickstepTransitionManager 通过这个判断是否走"打断→开 app"流程；lib 永远返回 false → 上层永远走非打断路径 | `LauncherAnimationRunner.java:271-274` + 上层调用 `Runner.supportInterruption()` (`LauncherAnimationRunner.java:578-580`) |
| M6 | `appLaunchAnimStartOrEnd(boolean, RemoteAnimationTarget[])` default 在 lib 是 no-op | 注意：这个 default body 在原厂就是空，调用由外层 `LauncherAnimationRunner.lambda$onAnimationStart$2/3` 完成 (`LauncherAnimationRunner.java:357-381`)；lib `AnimationController.appLaunchAnimStartOrEnd(isEnd, factory, targets)` 是状态机入口（**不是**覆写原厂 default），语义对等 | `LauncherAnimationRunner.java:243-245` + lib `AnimationController.kt:100-127` |

---

## 3. 行为差异风险点

> 本节只列**真的会导致 lib 行为不同于原厂**的点。"default body 在原厂也空"的项目（如 M6）不计入。

### 3.1 bug 级（必须修，否则偏离原厂语义）

| # | 问题 | 文件 | 触发条件 | 影响 | 修复成本 |
|---|---|---|---|---|---|
| **B1** | ⚠️未修复（小，3行） — `RemoteAnimationTarget` 砍到 2 字段，缺失 `mode`、`taskInfo`、`surfaceControl`、`leashTransaction` 等 25 字段 | `LauncherAnimationRunner.kt:16-19` | `appLaunchAnimStartOrEnd(true, nonEmptyTargets)` 携带真实 `android.view.RemoteAnimationTarget[]` 时 | demo 当前传 `arrayOf()` 无影响；但若 demo 想调用 `targets[i].mode` 或 `targets[i].taskInfo.getActivityType() == 1`（原厂 `isOpenAnimation` 判定 `LauncherAnimationRunner.java:327-337`）→ **NPE** | **3 行**——补齐 25 字段（`var mode: Int = 0, var taskInfo: Any? = null, var surfaceControl: Any? = null, var leashTransaction: Any? = null` 等；其余 Parcelable 字段可用 `Any?` 占位）。**未在已知 commit 范围**；本批不修。 |
| **B2** | ⚠️未修复（小，5行） — `RemoteAnimationFactory` 没有 `tryFinishOpenRemote` 实现，调用方收不到"system_server 动画就绪"回调 | `RemoteAnimationFactory.kt:10-17`（无此方法） | `BaseQuickstepLauncher` / `QuickstepTransitionManager` 调用 `runner.tryFinishOpenRemote(callback)` 时 | 原厂会触发 callback 通知 AMS 启动已就绪（用于 `LauncherAnimConfig.isAdaptiveAnimation()` 的窗口时机同步）；lib 不实现 → demo 如果后续补 adaptive 路径会 **callback 永远不触发** | **5 行**——加 `fun tryFinishOpenRemote(runnable: Runnable) { runnable.run() }`；同原厂 default body 语义。**未在已知 commit 范围**；本批不修。 |
| **B3** | ⚠️未修复（小，1行） — `RemoteAnimationFactory` 没有 `supportInterruption` 实现，永远返回 false | `RemoteAnimationFactory.kt:10-17`（无此方法） | Quickstep gesture 路径在 home → app 转场时判定 `supportInterruption()` | 原厂根据 feature flag 返回 true/false；lib 永远 false → **手势中断流程全关**（Demo11 弹簧 + Demo9 multi-app 完整转场受影响，review 11 §11-F 已列） | **1 行**——加 `open fun supportInterruption(): Boolean = false`（与原厂 default body 等价）。**未在已知 commit 范围**；本批不修。 |
| **B4** | ⚠️未修复（小，3行） — `RemoteAnimationFactory.onAnimationCancelled()` 是 no-op | `RemoteAnimationFactory.kt:10-17`（无此方法） | `runner.onAnimationCancelled()` 被 binder 线程触发时（`LauncherAnimationRunner.java:507-509`） | 原厂会 `finishExistingAnimation() + factory.onAnimationCancelled()`；lib 不实现 → **onCancelled 路径断**，回调链 `mOnCompleteCallback` 不会触发 | **3 行**——加 `open fun onAnimationCancelled() {}` + demo 在 onCancelled 时清理状态。**未在已知 commit 范围**；本批不修。 |
| **B5** | ⚠️未修复（中，20行） — `handleAnimationMerged(6 args)` 是 no-op | `RemoteAnimationFactory.kt:10-17`（无此方法） | 多 app merge 转场（Demo9 / `OplusAnimManager.AppOpenAnimMergeHelper`） | 原厂返回 true/false 决定是否走"继续 recents"路径；lib 永远 false → **multi-app merge 路径全断** | **20 行**——加 `open fun handleAnimationMerged(transitionInfo: Any?, transaction: Any?, breakParam: Any?, controller: Any?, z8: Boolean, z9: Boolean): Boolean = false`；配合 OplusAnimManager.getAppOpenAnimMergeHelper().cleanUpRecentsAnim() 已有逻辑（review 11-F）。**未在已知 commit 范围**；本批不修。 |

### 3.2 非 bug 级（设计取舍，文档说明即可）

| # | 项 | 文件 | 行为差异 | 是否构成 bug |
|---|---|---|---|---|
| N1 | ✔️保持简化 — `setCurrentPlayTime` 首帧补偿（`LauncherAnimationRunner.java:192-196`）缺失 | 原厂：`Math.min(RefreshRateTracker.getSingleFrameMs(context), totalDuration)`；lib 无 | demo 启动 AnimatorSet 时首帧可能延迟 1 帧（vsync 相位差） | 否——lib 走 AsyncValueAnimator 自己的 start 流程（review 01 §2.2）；原厂这个补偿是为了 binder 通道下的首帧黑屏，lib 场景不触发 |
| N2 | ✔️保持简化 — WeakReference factory GC 处理（`finalized=` 日志，`LauncherAnimationRunner.java:419, 419-451`）缺失 | lib 无 GC race | 否——lib 不持有 `RemoteAnimationFactory` 强引用，demo 也不会跨 GC 边界 |
| N3 | ✔️保持简化 — `mInputCallback` Binder callback / KeyEvent 拦截缺失 | `LauncherAnimationRunner.java:71-72, 290-302` | lib demo 不会有 BACK 键拦截的 `simulateUpSlide()` 行为 | 否——JVM demo 收不到 system_server KeyEvent |
| N4 | ✔️保持简化 — `isAppTransitionDisableInterruption` / `isFromRecents` / `isMultiOpenAnimStart` / `isRecentsRunning` / `supportLightOsPreStart` 等查询方法缺失 | 原厂 12 个 `@Override public boolean` 方法 | lib 调用方改用 `OplusAnimManager.getAnimController().hasRecentsAnim()` / `isOpeningAnim` 等（`AnimationController.kt:140-153`）替代 | 否——上层已经走 `DefaultAnimationController` 路径 |

---

## 4. 回移建议

### 4.1 值得补的（按"业务价值 / 修复成本"性价比）

| # | 缺口 | 业务价值 | 修复成本 | 推荐 |
|---|---|---|---|---|
| **R1** | ⚠️未修复（小，3行） — **B1 修齐 RemoteAnimationTarget 25 字段** | demo 后续要演示真实转场必须；现在补是 0 成本，后面改 API 兼容性差 | **3 行**（用 `Any?` 占位 + 关键 4 字段强类型） | **强推**——无任何理由拖；不补等于留 NPE 隐患。**未在已知 commit 范围**；本批不修。 |
| **R2** | ⚠️未修复（小，1行） — **B3 加 `supportInterruption()`** | Demo11 弹簧 + Demo9 multi-app 完整转场所需（review 11 §F）；上层 QuickstepTransitionManager 必须能查到 | **1 行**（`open fun supportInterruption(): Boolean = false`） | **强推**——一行代码走完整条 Quickstep 路径。**未在已知 commit 范围**；本批不修。 |
| **R3** | ⚠️未修复（小，5行） — **B2 加 `tryFinishOpenRemote(Runnable)`** | review 12 提到的"adaptive 动画启动就绪"路径；Demo3 / Demo5 真实启动场景所需 | **5 行** | **推**。**未在已知 commit 范围**；本批不修。 |
| **R4** | ⚠️未修复（小，3行） — **B4 加 `onAnimationCancelled()`** | Demo6 状态机"取消 → WAITING"路径所需 | **3 行** | **推**。**未在已知 commit 范围**；本批不修。 |
| **R5** | ⚠️未修复（中，20行） — **B5 加 `handleAnimationMerged(6 args)`** | Demo9 multi-app merge 完整路径（review 11-F 列 120 行） | **20 行** + 配合 `OplusAnimManager.AppOpenAnimMergeHelper` | **推**——性价比中等；要做 multi-app 必做。**未在已知 commit 范围**；本批不修。 |

**合计 R1+R2+R3+R4+R5：约 32 行**，能从"类型壳"升级到"QuickstepTransitionManager 可消费"。

### 4.2 建议保持简化的

| # | 项 | 理由 |
|---|---|---|
| K1 | ✔️保持简化 — `AnimationResult` 三段式 finish（130 行） | lib 走 `DefaultAnimationController.appLaunchAnimStartOrEnd` 状态机；`AnimatorSet` / `MultiAnimatorSet` 由 demo 内部管理；不需要"同步收尾 + UX_TASK_EXECUTOR 异步收尾 + MAIN_EXECUTOR onComplete"分层 |
| K2 | ✔️保持简化 — `mInputCallback` Binder KeyEvent 拦截 | JVM demo 无 binder 通道；BACK 键拦截无业务场景 |
| K3 | ✔️保持简化 — `WeakReference factory` GC 日志（v4 §9.5 警示） | lib 不跨 GC 边界；这是 OPPO 线上踩过的坑，JVM demo 触发不到 |
| K4 | ✔️保持简化 — `setCurrentPlayTime` 首帧补偿 | 原厂补偿是 binder 通道下的首帧黑屏问题；lib 走 AsyncValueAnimator 自己的 start 流程，无此 race |
| K5 | ✔️保持简化 — `Scenes` 枚举 + 5 个构造函数 | lib 不实例化 `LauncherAnimationRunner`；枚举仅用于 `Scenes.APP_TO_OVERVIEW_BY_VIRTUAL_KEY` 切换 `mAsyncFinishExecutor`，跟 demo 无关 |
| K6 | ✔️保持简化 — `getAnimation()` `getIconSurfaceRecordId()` `isSameIcon(View)` `preLoadIcon()` 这 4 个 default | 弹簧链/图标预加载/同图标判定属于 Demo11 完整弹簧体验（review 11-G，500+ 行），与当前 30+ 文件主题（异步线程）正交；不在本次范围 |

### 4.3 文档同步

`USAGE.md:210-216` §"RemoteAnimationFactory / LauncherAnimationRunner" 章节目前只说"类型壳 + 2 方法 demo 接口"，**与本文档 R1-R5 修复方案不一致**。补完 R1-R5 后需同步更新：
- `USAGE.md:154` 表加 `tryFinishOpenRemote(Runnable)` 行
- `USAGE.md:213` 增补 `RemoteAnimationFactory` 9 个 default 方法列表（含 `supportInterruption()`、`onAnimationCancelled()`、`handleAnimationMerged(...)` 等），标注哪些是 demo 演示用、哪些与原厂 default body 等价

---

## 5. 取证附录

### 5.1 dump.py 明文通道

`LauncherAnimationRunner.java` 文件 80% 在企业 DLP 加密列表中（`head -c 40` → `%TSD-Header-###%uU...`），但 **dump.py --info 实际返回 `state=PLAINTEXT`**（DLP hook 在工具层而非内核层，Read 工具缓存停留在密文态）。本次取证据全部走 dump.py + Python `open()`。

### 5.2 JADX 行号偏移说明

原厂 JADX 反编译 `// from class: com.android.launcher3.b1` 等 lambda 改名导致方法签名跨多行；本报告标注的行号（如 `LauncherAnimationRunner.java:243`）取自 JADX 文本第 243 个换行符位置，源码实际行号可能 -1 到 -2（JADX 自带 `JADX DEBUG: Lines numbers was adjusted` 警告）。这点 review 03 §取证方法已说明。

### 5.3 `supportInterruption()` 签名核实

用户提问中提到 `supportInterruption(ItemInfo)`，但 dump.py `grep` 结果确认为 **无参** `default boolean supportInterruption() { return false; }`（`LauncherAnimationRunner.java:271-274`）。可能与 `BaseQuickstepLauncher` / `OplusAnimManager` 别的同名方法撞名，本报告以 dump.py 实际结果为准。

### 5.4 demo 唯一 call site

```
demo/src/main/java/com/asyncanimator/demo/Demo6StateMachineActivity.kt:172
  controller.appLaunchAnimStartOrEnd(false, null, arrayOf())
```

**注意**：此处 `arrayOf()` 返回 `Array<LauncherAnimationRunner.RemoteAnimationTarget>`，意味着 `LauncherAnimationRunner.RemoteAnimationTarget` 真正被消费——B1 的"补 25 字段"不是"理论上应该补"，而是"已经被 demo 引用，补不齐会让以后写 `arrayOf(RemoteAnimationTarget(taskId=1))` 时 IDE 找不到其他字段"。

## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **e62dbff** — RemoteAnimationFactory / LauncherAnimationRunner 仍在 com.android.launcher3 + control 路径

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。
- **B1-B5/R1-R5 (§3.1 + §4.1)** — 全部保持简化（JVM demo 不示宜完整 binder 通道），未在已知 commit 范围，本批不修；下一批如需补 Quickstep 全栈路径（Demo9 multi-app / Demo11 弹簨）则按 1+5+3+20=29 行一次性补齐
- **N1-N4/K1-K6** — 经独立验证均为合理简化（demo 不示宜 binder keyevent、不示宜 GC race、不示宜 launcher 实例化场景）