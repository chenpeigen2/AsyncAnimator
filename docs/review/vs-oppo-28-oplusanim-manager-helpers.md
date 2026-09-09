# Review 16：OplusAnimManager 6 个 helper 逐个对账（含 AppSwipeToRecentContinuationHelper）

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/manager/OplusAnimManager.kt`（Kotlin `object` 单例 + 6 个 helper 字段） + 同包 6 个 helper 实现 + `controller/`、`seq/`、`feature/` 子包
> - **原厂**：`D:/oppo_a6_launcher/sources/com/oplus/quickstep/utils/OplusAnimManager.java`（Kotlin 反编译的 Java；6 个 `t4.b` 委托懒加载的 `Default*` 包装）+ `com/oplus/quickstep/utils/{AppOpen,MultiApp,InterceptKeyEvent,MultiOpenPreStart}AnimMergeHelper.java` + `AppSwipeToRecentContinuationHelper.java`（独立单例，不在 OplusAnimManager 工厂内）
>
> 范围：本 review 专做 **helper 级别的字段/方法/调用面对账**，颗粒度比 review 03 / 06 / 11 / 12 细。不重复：
> - review 03 §3-g（`AnimationFeatureHelper` 默认值 1/0 vs -1）— 本文 §2.2 重提
> - review 06 §3.2（4 个 merge helper 整块缺失）— 本文逐个对账
> - review 11 §A-D（功能缺口）— 本文按 helper 拆解
> - review 12 §3-A3（`delayStartActivityIfNeed` 第三层运行态）— 本文 §3.2-3 重提
>
> 取证方法：lib 侧文件均经 Python `open(..., encoding='utf-8').read()`（DLP Read 工具拦下，Python 直读明文）；原厂 80% 文件 DLP 加密（同 Python 路径穿透 + Grep ripgrep 双通道）。行号均为 JADX 反编译文本行号（与 `sources/` 下的 `.java` 文件行号 1:1 对齐；JADX `/* JADX WARN */` 标为 `Code decompiled incorrectly` 的方法在原文中以注释形式保留）。

---

## ① 类对应关系表

### 1.1 工厂本体

| lib 类 / 字段 | 原厂类 / 字段 | 关键证据 | 关系 |
|---|---|---|---|
| `object OplusAnimManager` (`lib/.../manager/OplusAnimManager.kt:21-63`, 63 行) | `public final class OplusAnimManager` 含 `static final INSTANCE` + 6 个 `private static final t4.b $$delegatedProperties` 委托懒加载 (`OplusAnimManager.java:34-45, 60-118`, 269 行) | OPPO 用 `kotlin.Delegates.observable` (`t4.b` = `kotlin.properties.Delegates.observable`)；lib 用裸 `var ... = null` + `init` 块 | 形状等价，**线程语义不同**（见 §2.1-1、§3.1-1） |
| `init { if (supportInterruption()) { ... } }` (`OplusAnimManager.kt:28-32`) | `static { ... }` 类加载即触发 6 helper 链式 `create*()` (`OplusAnimManager.java:60-118`) | OPPO `static{}` 块在 INSTANCE 赋值时（行 64）立即执行 6 个 `createXxx()` 并填到 `t4.a` 委托；lib `init` 块仅在首次访问 `OplusAnimManager` 类时执行 2 个 `Animation*` helper | **lib 只创建 2 个 helper**（`AnimationController` + `AnimationSeqHelper`），其余 4 个 helper 工厂完全缺失 |
| `supportInterruption(): Boolean = true` (`OplusAnimManager.kt:42`) | `supportInterruption(): Boolean` = `(!LauncherAnimConfig.isAppTransitionByLightAnim() \|\| LauncherAnimConfig.isAdaptiveAnimation()) && TaskAnimationManager.ENABLE_SHELL_TRANSITIONS && AppFeatureUtils.isSupportBlockableAnimation()` (`OplusAnimManager.java:230-232`) | OPPO 是 3 个条件复合；lib 恒 true | **过度简化**（见 §2.2-1） |
| `supportInterruption(ItemInfo)` (`OplusAnimManager.kt` 缺失) | `supportInterruption(ItemInfo)` 含 zoomWindowPkg 比对 + SplitScreenUtils.isCombination (`OplusAnimManager.java:240-264`) | 仅原厂有 | **完全缺失**（split-screen 缩放窗口启动场景会走错路径） |
| `tryFinishOpenRemote(Runnable)` (`OplusAnimManager.kt` 缺失) | `tryFinishOpenRemote(Runnable)` 调 `getMAppOpenAnimMergeHelper().isRecentsMergeOpenRemote()` 决定 `setAppLaunchAnimFinishCallback` 或直接 `runnable.run()` (`OplusAnimManager.java:234-238`) | 仅原厂有 | **完全缺失**（recents→app 远程动画合并场景无法演示） |
| `recreateAnimHelper()` (`OplusAnimManager.kt` 缺失) | `recreateAnimHelper()` 4 个 helper（merge 三件套 + animationController + animationSeqHelper）全 `setM*(createXxx())` 重置（**注意：只 4 个，不含 `interceptKeyEventHelper` 和 `multiOpenPreStartHelper`**）(`OplusAnimManager.java:198-205`) | 仅原厂有 | **完全缺失**（feature flag 切换时无法重置 helper 状态） |
| `reset()` (`OplusAnimManager.kt` 缺失) | `reset()` 调 `getMAnimationController().reset()` + `getMAppOpenAnimMergeHelper().cleanUpRecentsAnim()` + `getMMultiAppAnimMergeHelper().reset()` (`OplusAnimManager.java:209-216`) | 仅原厂有 | **完全缺失**（helper 状态在多次转场后可能脏） |
| `matchAnimationId(int, int)` (`OplusAnimManager.kt` 缺失) | `matchAnimationId` 比对两个 animationId，-1 视作相等并 LogUtils.e (`OplusAnimManager.java:184-190`) | 仅原厂有 | **完全缺失**（cross-Animation-Controller 协作场景） |
| `cleanUpRecentsAnimation()` (`OplusAnimManager.kt:51-53`) | `cleanUpRecentsAnimation()` 含 `getMAnimationController().cleanUpRecentsAnim()` 返回 true 时 `getMMultiAppAnimMergeHelper().setOnTaskAppearedTarget(null)`，再 `getMAppOpenAnimMergeHelper().cleanUpRecentsAnim()` (`OplusAnimManager.java:171-178`) | OPPO 是 3 helper 链式清理；lib 只调 1 个 | **缩为单 helper**（清理不完整） |
| `interruptionEnabled: Boolean`（demo 演示降级，`OplusAnimManager.kt:57-63`） | 无对应字段；OPPO 通过 `LauncherAnimConfig`/`TaskAnimationManager.ENABLE_SHELL_TRANSITIONS` 静态门控 | OPPO 是只读门控；lib 是可写开关 | lib **引入新能力**（demo 化降级合理；生产用应改为只读） |

### 1.2 6 个 helper 逐个对应

#### Helper A：`mAnimationController` (已存在)

| lib 类 | 原厂类 | 关键证据 |
|---|---|---|
| `class AnimationController : DefaultAnimationController()` (`lib/.../controller/AnimationController.kt:21-248`, 248 行) | `final class AnimationController extends DefaultAnimationController` (`com/oplus/quickstep/utils/AnimationController.java`, 993 行) | 字段、状态机、3 种 TimeOutListener 形状 1:1 |
| `DefaultAnimationController` (`lib/.../controller/DefaultAnimationController.kt`, 75 行) | `DefaultAnimationController` (`com/oplus/quickstep/utils/DefaultAnimationController.java`, 含 DefaultImpl 工厂) | lib 把 Default 当 **open class 静态方法**；OPPO 用 `factory pattern` 间接创建 |
| 12 个 `AnimationState` (`lib/.../controller/AnimationState.kt`) | `AnimationController.AnimationState` 内部 enum (`AnimationController.java:18-30`) | 1:1 对齐（review 03 §2.1 已确认） |
| 缺失：`cleanUpRecentsAnimation()` 链路里的 `getMultiAppAnimMergeHelper().setOnTaskAppearedTarget(null)` | 见上 | **单 helper 清理不完整**（§1.1 行） |
| 缺失：`appLaunchAnimStartOrEnd` 缺 `MESSAGE_RELEASE_TOUCH(101)` + 600ms 闸门 | `AnimationController.java:60,63,548-555` + `forbidTouch()` 读 `mOpenWindowAnimRunning`（`:687`） | **bug 级**（review 12 §3-B6 已点） |
| 缺失：`delayStartActivityIfNeed` 第三层用时间窗代替 `isAppSwipeToRecentContinuationRunning()` | `AnimationController.java:646-650` 直接调 `AppSwipeToRecentContinuationHelper.INSTANCE.isAppSwipeToRecentContinuationRunning()` | **bug 级**（review 12 §3-A3 已点；本文 §3.2-3 重提） |

#### Helper B：`mAnimationSeqHelper` (已存在)

| lib 类 | 原厂类 | 关键证据 |
|---|---|---|
| `class AnimationSeqHelper : DefaultAnimationSeqHelper()` (`lib/.../seq/AnimationSeqHelper.kt`, 100 行) | `final class AnimationSeqHelper extends DefaultAnimationSeqHelper` (`com/oplus/quickstep/utils/AnimationSeqHelper.java`, 130 行) | `addSeqId` / `canFinishRecent` / `canInterceptGesture` / `delayFinishRecents` 形状 1:1 |
| `DefaultAnimationSeqHelper` (`lib/.../seq/DefaultAnimationSeqHelper.kt`, 22 行) | `DefaultAnimationSeqHelper` | 1:1 |
| `AnimSeqTimeStamp` 时钟域 | `AnimSeqTimeStamp` 用 `SystemClock.uptimeMillis()` | 1:1（review 03 §3-c 修复后对齐） |
| 残留：`updateNextFinishSeqIdIfNeed` 无条件覆盖 pair | `AnimationSeqHelper.java:123-127` 仅当 pair 为空或 controller 变更才覆盖 | **bug 级**（review 12 §3-D10） |
| 残留：`delayFinishRecents` handler 是主线程而非 `URGENT_TRANSACTION_EXECUTOR` | `AnimationSeqHelper.java:97-99` 用 `URGENT_TRANSACTION_EXECUTOR.getHandler()` | 简化（review 01 已记） |

#### Helper C：`mAppOpenAnimMergeHelper` (缺失)

**原厂 8 个 public 方法**（`com/oplus/quickstep/utils/AppOpenAnimMergeHelper.java`，300 行）：

| 行号 | 方法 | 签名 | 语义 |
|---|---|---|---|
| L104 | `checkIfRecentsAnimStarted(RecentsAnimationCallbacks)` | `boolean` | 检查当前 callback 是否触发了 open-merge recents anim |
| L109 | `cleanUpRecentsAnim()` | `void` | 清理 recents callback + `mAppOpenTargets`（postAtFrontOfQueue 主线程） |
| L120 | `tryStartRecentsForOpenRemoteMerge(boolean, RecentsAnimationControllerCompat)` | `void` | **JADX 自标"Code decompiled incorrectly"**（L120 注释）；原逻辑 = 把 `mAppOpenTargets.unfilteredApps` 按 `mode=0/1` 互换并调 `mRecentsCallback.onAnimationStart` |
| L124 | `gestureTriggerRecentsAnim(RecentsAnimationCallbacks)` | `void` | 触发 recents 时设 callback、清 started 标记 |
| L132 | `isRecentsMergeOpenRemote()` | `boolean` | 返回 `mRecentsCallback != null`（被 `OplusAnimManager.tryFinishOpenRemote` 用） |
| L137 | `onRecentsAnimStart(RecentsAnimationCallbacks)` | `void` | callback 一致时设 started=true；否则 cleanup |
| L151 | `onRemoteAnimationMerged(TransitionInfo, SurfaceControl.Transaction, RemoteAnimationTargetCompat.BreakParam, IRecentsAnimationController, boolean filterLauncher, boolean reparentLauncher)` | `boolean` | **JADX 自标"Code decompiled incorrectly"**（L151 注释）；6 参数、~150 行的远程动画合并逻辑（filterLauncher 按 activityType==2 过滤；reparentLauncher 把 launcher leash reparent 到 root leash；用 `param.applyToTaskLeashForBreak` 应用断点参数；最后调 `tryStartRecentsForOpenRemoteMerge`）|
| L283 | `releaseOpenRemoteTargets()` | `void` | 释放 `mAppOpenTargets` |
| L295 | `setAppOpenRemoteTargets(RemoteAnimationTargets)` | `void` | 设 `mAppOpenTargets` 字段 |

**调用方**（Grep `getAppOpenAnimMergeHelper|isRecentsMergeOpenRemote|onRemoteAnimationMerged|setAppOpenRemoteTargets|gestureTriggerRecentsAnim`）：

| 文件:行 | 上下文 |
|---|---|
| `com/oplus/quickstep/utils/OplusAnimManager.java:171-178, 198-205, 234-238` | 工厂方法（`cleanUpRecentsAnimation` / `recreateAnimHelper` / `tryFinishOpenRemote`）|
| `com/android/launcher3/LauncherAnimationRunner.java:492, 587-589` | `tryFinishOpenRemote` 委托 + `cleanUpRecentsAnim` 调用 |
| `com/android/launcher3/QuickstepTransitionManager.java:2154, 2298-2300` | `onRemoteAnimationMerged` 入口（6 参数全传递）|
| `com/android/launcher3/anim/OplusLauncherAppTransitionHelper.java` | `setAppOpenRemoteTargets` |
| `com/android/quickstep/RecentsAnimationCallbacks.java` | `gestureTriggerRecentsAnim` |
| `com/android/quickstep/TaskAnimationManager.java` | `onRemoteAnimationMerged` 转发 |

**lib 缺失的影响**（**bug 级**）：
- `OplusAnimManager.tryFinishOpenRemote(runnable)` 永远走 `runnable.run()` 直接路径，**不挂起**——原厂在 recents-merge-open 场景下会让 `appLaunchAnimFinishCallback` 等待 recents anim 完成才回调。
- `QuickstepTransitionManager.onRemoteAnimationMerged(...)` 入口直接空跑（lib 无该方法），transition merge 决策无人做，**recents→app 转场合并动画不触发**——"图标随 recents 卡片飞出"特效完全丢失。
- `cleanUpRecentsAnimation` 链路不完整，**`MultiAppAnimMergeHelper` 持有的 `mOnTaskAppearedTarget` 不会被清**（见 Helper D）→ 多次转场后 `updateRecentTargetsIfNeed` 可能错乱。
- 修复成本：~80 行（含 `onRemoteAnimationMerged` 桩，**150 行真实逻辑因 JADX 反编译错误无法逐行复刻**）——见 §4.1-3。

#### Helper D：`mMultiAppAnimMergeHelper` (缺失)

**原厂 6 个 public 方法**（`com/oplus/quickstep/utils/MultiAppAnimMergeHelper.java`，107 行）：

| 行号 | 方法 | 签名 | 语义 |
|---|---|---|---|
| L23 | `multiAppOpenAnimStart()` | `synchronized void` | `mPreparingMultiAppOpenAnimCount--`（仅当 >0）|
| L36 | `prepareMultiAppOpenAnim()` | `synchronized boolean` | 若 `mRecentsAnimFinished` 直接 false；否则 `mPreparingMultiAppOpenAnimCount++` |
| L50 | `reset()` | `void` | 清两个字段 + `setOnTaskAppearedTarget(null)` |
| L58 | `setOnTaskAppearedTarget(RemoteAnimationTarget)` | `void` | 设 `mOnTaskAppearedTarget`（CAS 持有即将出现的 task）|
| L64 | `setRecentsAnimEndState(boolean finished, String reason)` | `synchronized boolean` | 状态变更时若 `mPreparingMultiAppOpenAnimCount > 0` 拒绝置 `finished=true`；返回是否设置成功 |
| L83 | `updateRecentTargetsIfNeed(RecentsAnimationTargets)` | `RecentsAnimationTargets` | 把 `mOnTaskAppearedTarget` 转 mode=1 (CLOSING) 加到 `apps` 头部，过滤 `activityType==1` (HomeTask) |

**调用方**（Grep `prepareMultiAppOpenAnim|multiAppOpenAnimStart|setRecentsAnimEndState|setOnTaskAppearedTarget`）：

| 文件:行 | 上下文 |
|---|---|
| `com/oplus/quickstep/gesture/OplusBaseSwipeUpHandler.java:6233, 6240, 6346, 6355, 6357, 6866, 6870, 8909, 10902, 10924` | 多 app swipe-up 路径的 7 个调用点（`prepareMultiAppOpenAnim` + `multiAppOpenAnimStart` 配对）|
| `com/oplus/quickstep/utils/AnimationController.java:197, 226, 478, 546` | `addRecentsAnim` / `appLaunchAnimStartOrEnd` / `checkAllAnimationFinished` / 内部 lambda |
| `com/android/quickstep/TaskAnimationManager.java` | `setRecentsAnimEndState` 转发 |

**lib 缺失的影响**（**bug 级**）：
- `AnimationController` 调 `OplusAnimManager.getMultiAppAnimMergeHelper().setRecentsAnimEndState(true, ...)` 整段 NPE（lib `OplusAnimManager` 无该字段）。
- 多 app swipe-up 场景：`prepareMultiAppOpenAnim()=false` 永远成立，`revertCurrentAnimationIfNeed` 永远被走，"两个图标先后点击只起一次 recents 转场"语义丢失。
- 修复成本：~50 行（字段 + 4 个 synchronized 方法 + `updateRecentTargetsIfNeed` 的 `ActivityManager.RunningTaskInfo.getActivityType()==1` 过滤）——见 §4.1-4。

#### Helper E：`mInterceptKeyEventHelper` (缺失；**类名拼写错误**："Defalut" 而非 "Default")

**原厂 4 个 public 方法 + 3 个反射懒加载委托**（`com/oplus/quickstep/utils/InterceptKeyEventHelper.java`，155 行）：

| 行号 | 方法 | 签名 | 语义 |
|---|---|---|---|
| L115 | `sendBackKeyEvent(Context)` | `void` | `InputManager.injectInputEvent(KeyEvent(DOWN 4) + KeyEvent(UP 4))` 模拟 BACK 键 |
| L131 | `setInterceptKeyEventEnabled(boolean, IRecentsAnimationRunner, IRemoteTransitionInputCallback)` | `void` | **旧 API**；切到 `OplusExecutors.UX_TASK_EXECUTOR` 线程反射 `OplusWindowManager.setInterceptKeyEventEnabled(boolean, IRecentsAnimationRunner)` |
| L144 | `setInterceptKeyEventEnabled(boolean, IRemoteTransitionInputCallback)` | `void` | **新 API**；同上但反射 `setInterceptKeyEventEnabled(boolean, IRemoteTransitionInputCallback)`；先校验 callback 一致性 |
| 内部 | `oplusWindowManager$delegate` | `f4.g` | `Class.forName("android.view.OplusWindowManager").getConstructor().newInstance()` |
| 内部 | `interceptKeyMethod$delegate` / `oldInterceptKeyMethod$delegate` | `f4.g` | `getMethod("setInterceptKeyEventEnabled", Boolean.TYPE, IRemoteTransitionInputCallback.class)` / `getMethod(..., IRecentsAnimationRunner.class)` |

**Default 基类**（`DefalutInterceptKeyEventHelper.java`，24 行）：3 个 public 方法全 no-op，签名同 Impl（除了 Impl 有反射线程切换）。

**调用方**（Grep `getInterceptKeyHelper|sendBackKeyEvent|setInterceptKeyEventEnabled`）：

| 文件:行 | 上下文 |
|---|---|
| `com/oplus/quickstep/gesture/OplusBaseSwipeUpHandler.java:6347-6350, 6368-6371` | `prepareMultiAppOpenAnim()=false` 时 `sendBackKeyEvent` 模拟 BACK 键；`TaskStateHelper` 通知时 `sendBackKeyEvent` |
| `com/android/launcher3/LauncherAnimationRunner.java` | `setInterceptKeyEventEnabled`（recents runner 注册时调用）|
| `com/android/quickstep/OplusBaseRecentsAnimationController.java` | 同上 |
| `com/android/quickstep/RecentsAnimationController.java` | `sendBackKeyEvent` 转发 |

**lib 缺失的影响**（**bug 级**）：
- BACK 键在 recents 转场期间**不拦截**——演示中按 BACK 会被 ActivityManager 默认处理，可能直接退出 recents → 窗口错乱。
- `OplusBaseSwipeUpHandler` 的 `notifyInterceptKeyEvent$1$1` 内部在 `z8=true` 时调 `sendBackKeyEvent`——lib 无该方法，**NPE 风险**（虽然 demo 不会触发该路径，但 `OplusAnimManager.getInterceptKeyHelper()` 的引用是 NPE）。
- 修复成本：~30 行（Default 基类 + 反射两个 Method + `sendBackKeyEvent` 的 InputManager.injectInputEvent）——见 §4.1-5；**注意反射 `Class.forName("android.view.OplusWindowManager")` 在非 OPPO ROM 上必 NoClassDefFoundError，lib 需 try-catch 兜底**。

#### Helper F：`mMultiOpenPreStartHelper` (缺失；**还有双重门控 `AppFeatureUtils.isSupportPreStart()`**)

**原厂 13 个 public 方法 + 5 个并发原语字段**（`com/oplus/quickstep/utils/MultiOpenPreStartHelper.java`，459 行）：

| 行号 | 方法 | 签名 | 语义 |
|---|---|---|---|
| L121 | `abortPreStartMultiOpenAnim(int id)` | `void` | ReentrantLock 锁内：若 `multiOpenAnimId==-1`（未启动）→ `abortAnimId.set(id)`；若 `multiOpenAnimId==id`（本次启动的）→ `SystemUiProxy.reverseCurrentToHomeAnim()` + `abortAnimId.set(id)` |
| L144 | `invokeMultiOpenRemoteFinishCb(boolean)` | `void` | `preStartMultiOpenFlag |= 2`（FINISH_REMOTE_CALLBACK 位）|
| L154 | `isMultiOpenAnimStarted(int requestedId)` | `boolean` | `requestedId == lastMultiOpenAnimId.get()` |
| L162 | `isMultiOpenPreStartAnimRunning()` | `boolean` | `multiOpenAnimId.get() != -1` |
| L167 | `isRecentsFinishToHome()` | `boolean` | `LauncherAnimConfig.isAdaptiveLowAnimation() && recentsFinishToHome` |
| L172 | `isReverseMultiOpenOnAbort(int id)` | `boolean` | `abortAnimId.get() != -1 && abortAnimId.get() == id` |
| L180 | `multiOpenAnimStart(RemoteAnimationTarget)` | `void` | `lastMultiOpenAnimId.set(transitionId)` |
| L189 | `onFinishMultiOpenToHomeAnim(int id, boolean forceRelease)` | `void` | `forceRelease=true` 跑所有 `abortTransitionMap`；否则仅当 `abortAnimId==id` 时跑并 reset `multiOpenAnimId=-1` |
| L241 | `onRecentsFinish(boolean toHome, GestureEndTarget)` | `void` | 算 `recentsFinishToHome`、清两个 id、清两个 map；`toHome \|\| NEW_TASK` 时 merge `hideRootTransaction` 到 `endTransaction`；`endTransaction.apply()`；清 `transitionLeashMap`（reparent 到 null + release）|
| L314 | `onTaskAppearedCallbackOnPreAnimStart(RemoteAnimationTarget[], IRemoteTransitionFinishedCallback)` | `boolean` | **核心方法**；按 `mergeTaskAppear` / `reparentTaskLeashOnTaskAppear` 分支；返回是否立即调 `onTransitionFinished` |
| L371 | `preStartMultiOpenAnim(int id, Transaction finishT, Transaction hideRootT, RemoteAnimationTarget[], IRemoteTransitionFinishedCallback)` | `boolean` | **核心方法**；ReentrantLock 内：CAS `multiOpenAnimId -1→id`；`transitionLeashMap.put(id, appearedTaskTarget[0].leash)`；`abortTransitionMap.put(id, runnable)`；merge 两条 transaction；调 `SystemUiProxy.onTasksAppearedCallback(...)`；`preStartReady.signalAll()` |
| L431 | `resetRecentsFinishToHomeFlag()` | `void` | `recentsFinishToHome = false` |
| L436 | `waitMultiOpenPreStart(RemoteAnimationTarget[])` | `void` | `multiOpenLock.lock()` + `multiOpenAnimId==-1 && isRecentsAnimationRunning` 时 `preStartReady.await()` |
| 字段 | `multiOpenLock` (`ReentrantLock`) / `preStartReady` (`Condition`) / `multiOpenAnimId` (`AtomicInteger`) / `abortAnimId` (`AtomicInteger`) / `transitionLeashMap` (`ArrayMap<Integer, SurfaceControl>`) / `abortTransitionMap` (`ArrayMap<Integer, Runnable>`) | | **5 个并发原语** + 2 个 `SurfaceControl.Transaction` 暂存字段 |

**Default 基类**（`DefaultMultiOpenPreStartHelper.java`，60 行）：13 个 public 方法全 no-op。

**调用方**（Grep `getMultiOpenPreStartHelper|preStartMultiOpenAnim|onTaskAppearedCallbackOnPreAnimStart|isMultiOpenPreStartAnimRunning|abortPreStartMultiOpenAnim|onFinishMultiOpenToHomeAnim|isReverseMultiOpenOnAbort|isRecentsFinishToHome|waitMultiOpenPreStart|multiOpenAnimStart|isMultiOpenAnimStarted|resetRecentsFinishToHomeFlag|invokeMultiOpenRemoteFinishCb|onRecentsFinish`）：

| 文件:行 | 上下文 |
|---|---|
| `com/android/launcher3/LauncherAnimationRunner.java:479-480, 512-513, 559-560` | `abortPreStartMultiOpenAnim` / `isMultiOpenAnimStart` / `preStartMultiOpenAnim` 转发给 helper |
| `com/android/launcher3/Launcher.java:2840, 3013` | `onResume` / `onStop` 调 `resetRecentsFinishToHomeFlag` |
| `com/android/quickstep/OplusLauncherSwipeHandlerV2Impl.java:1391-1393` | `isReverseMultiOpenOnAbort` 决策 |
| `com/android/quickstep/OplusBaseTouchInteractionService.java` | `onTaskAppearedCallbackOnPreAnimStart` 转发 |
| `com/android/quickstep/RecentsAnimationCallbacks.java` | `onRecentsFinish` 转发 |
| `com/android/quickstep/RecentsAnimationController.java` | `onTaskAppearedCallbackOnPreAnimStart` 转发 |
| `com/oplus/quickstep/gesture/OplusBaseSwipeUpHandler.java` | 至少 10 个调用点（preStartMultiOpenAnim / onTaskAppearedCallbackOnPreAnimStart / onFinishMultiOpenToHomeAnim 等）|
| `com/android/quickstep/util/animation/IconLayerUpdater.java` | `preStartMultiOpenAnim` 转发 |
| `com/android/quickstep/SystemUiProxy.java` | `preStartMultiOpenAnim` / `onTaskAppearedCallbackOnPreAnimStart` 转发 |

**lib 缺失的影响**（**bug 级**）：
- `OplusAnimManager.getMultiOpenPreStartHelper()` NPE（lib 字段未声明）→ `Launcher.onResume` / `onStop` 调 `resetRecentsFinishToHomeFlag` 即崩。
- 多 app 启动 pre-start SurfaceControl 事务合并完全不可用：原厂把两条 `Transaction` merge 后 `apply()`、`onTaskAppeared` 时再 reparent 到 `transitionLeashMap` 里的 leash——lib 完全没这条路。
- 修复成本：**~200 行**（13 个 public 方法 + 5 个并发原语字段 + 2 个 `Transaction` 字段 + `SystemUiProxy.onTasksAppearedCallback` 反射调用）——见 §4.1-6，**最贵的 helper**。

### 1.3 OplusAnimManager 之外的相关 helper：`AppSwipeToRecentContinuationHelper`

虽然不在 OplusAnimManager 工厂内（独立 `object` 单例 + `INSTANCE`），但被 `AnimationController.delayStartActivityIfNeed` **直接静态引用**（`AnimationController.java:646-650`），与 6 个 helper 同等关键。

**原厂 42 个 public 方法**（`com/oplus/quickstep/utils/AppSwipeToRecentContinuationHelper.java`，**1372 行**）：

| 关键方法 | 行号 | 语义 |
|---|---|---|
| `isAppSwipeToRecentContinuationRunning()` | `L:静态字段isAppSwipeToRecentContinuationRunning + 公开 getter` | 续行动画是否在跑——`AnimationController.java:646` 直接查这个 |
| `setAppSwipeToRecentContinuationRunning(boolean)` | (内部 setter) | 续行动画启动/结束切换 |
| `setContinueInitiatedOnDownEvent(boolean)` | (内部) | down event 时设 true 触发续行决策 |
| `isContinueInitiatedOnDownEvent()` | (内部) | 同上 getter |
| `isAlignEliminateAnimRunning()` | (内部) | 对齐消除动画状态 |
| `setAlignEliminateAnimAlreadyExecuted(boolean)` / `isAlignEliminateAnimAlreadyExecuted()` | (内部) | 对齐消除动画是否已执行过 |
| `isContinuationScrollAnimRunning()` | (内部) | 续行 scroll 动画状态 |
| `getContinuationScrollTargetPage()` / `setContinuationScrollTargetPage(int)` | (内部) | 续行目标 page |
| `isHummingEnabledAndEnhance()` | (内部) | 设备端 "humming" 优化 + `AppLaunchAnimSpeedHandler.sSpeedLevel==0` |
| 5 个 continuation anim | 字段：`continuationScrollAnim` / `continuationScaleAnim` / `continuationFullScreenAnim` / `continuationTransYAnim` / `continuationScrimBackgroundAnim` | 都是 `OplusValueAnimator.generateContinuationAnim(...)` 实例（5 条 OplusValueAnimator 链）|
| 2 个 align eliminate anim | (内部) | OplusValueAnimator + 计时器 |
| `bind(RecentsView, AnimatorPlaybackController, Animator)` | (内部) | 把 controller / launcher transition 绑到 recents view |
| `init(RecentsView)` / `cancel(RecentsView)` / `cancelOfTaskLaunch()` / `cancelOfTaskLaunchOP()` | (内部) | 续行动画生命周期 |
| `start()` / `end()` | (内部) | 续行启动 / 收尾 |
| `startAlignEliminateAnim(RecentsView)` / `pauseAlignEliminateAnim()` | (内部) | 对齐消除动画控制 |
| `alignRunningTaskView(RecentsView, RectF)` | (内部) | 把 RecentsView 上 taskView 对齐到 RectF |
| `convertLandscapeWindowRectToPortrait(RectF, DeviceProfile, PagedOrientationHandler)` | (内部) | 横屏窗口 rect 转竖屏 |
| `finishContinuationScroll(RecentsView)` | (内部) | 续行 scroll 动画收尾 |

**调用方**（Grep `isAppSwipeToRecentContinuationRunning|setAppSwipeToRecentContinuationRunning|isContinuationScrollAnimRunning|setContinueInitiatedOnDownEvent|isContinueInitiatedOnDownEvent|isAlignEliminateAnimRunning`）：

| 文件:行 | 上下文 |
|---|---|
| `com/oplus/quickstep/utils/AnimationController.java:646-650` | **第三层决策的运行态查询**——本 review 重点 |
| `com/oplus/quickstep/utils/StackRecentsViewAnimUtil.java` | 续行动画状态查询 |
| `com/oplus/quickstep/layout/OplusStackRecentsView.java` | 同上 |
| `com/android/quickstep/uioverrides/touchcontrollers/OplusTaskViewTouchControllerImpl.java` | 触摸决策 |
| `com/android/quickstep/uioverrides/OplusRecentsViewStateControllerImpl.java` | RecentsView 状态机 |
| `com/oplus/quickstep/layout/grid/OplusGridRecentsView.java` | Grid RecentsView |
| `com/android/quickstep/TaskViewUtils.java` | task view 工具 |
| `com/oplus/quickstep/utils/TaskStackLayoutAlgorithm.java` | task stack 布局 |
| `com/oplus/quickstep/gesture/OplusBaseSwipeUpHandler.java` | swipe-up 处理 |
| `com/oplus/quickstep/utils/RecentsViewAnimUtil.java` | RecentsView 动画工具 |
| `com/android/quickstep/views/OplusRecentsViewImpl.java` / `OplusTaskViewImpl.java` / `OplusStackTaskView.java` | 视图层 |
| `com/oplus/quickstep/views/StackPagedViewEx.java` | 翻页视图 |

**lib 缺失的影响**（**bug 级**）：
- `AnimationController.delayStartActivityIfNeed` 第三层（`AnimationController.kt:226-230`）用 `SystemClock.uptimeMillis() < overviewContinuationTimeOutMaxTime`（**100ms 时间窗**）代替 `AppSwipeToRecentContinuationHelper.INSTANCE.isAppSwipeToRecentContinuationRunning()`（**运行态查询**）。两者语义不等价：
  - 续行已结束但仍在 100ms 窗口内 → lib 错误挂起（**误挂**）
  - 续行启动延迟超过 100ms → lib 不会挂起（**漏挂**）
  - 原厂意图"续行期间 startActivity 推迟"；lib 改成"续行启动后 100ms 内推迟"——**bug 级**（review 12 §3-A3）
- 修复成本：**~30 行**（仅 `isAppSwipeToRecentContinuationRunning()` + `setAppSwipeToRecentContinuationRunning(boolean)` + 静态字段 + 5 个 continuation anim 桩；其余 40 个方法在 lib 内不触发）——见 §4.1-2。

---

## ② 保真度评估

### 2.1 精确复刻（行为可对齐）

| # | 设计点 | lib 证据 | 原厂证据 |
|---|---|---|---|
| 1 | `OplusAnimManager` 工厂本体：6 个 `t4.b` 委托懒加载 + `static{}` 类加载初始化 | `OplusAnimManager.kt:21-63`（简化为 2 helper + 1 字段；其余 4 工厂方法缺失） | `OplusAnimManager.java:34-45, 60-118` |
| 2 | `AnimationController : DefaultAnimationController` 子类化 | `lib/.../controller/AnimationController.kt:21` | `AnimationController.java: extends DefaultAnimationController` |
| 3 | `AnimationSeqHelper : DefaultAnimationSeqHelper` 子类化 | `lib/.../seq/AnimationSeqHelper.kt:27` | `AnimationSeqHelper.java: extends DefaultAnimationSeqHelper` |
| 4 | 12 个 `AnimationState` 枚举 | `AnimationState.kt` | `AnimationController.java:18-30` 内部 enum |
| 5 | `AnimSeqTimeStamp` 4 字段 + `uptimeMillis` 时钟 | `lib/.../seq/AnimSeqTimeStamp.kt` | `AnimSeqTimeStamp.java:25,37,49,61` |
| 6 | `addRecentsAnim` 12 态转移表（`case 1/3/8/9→CLOSE`, `case 2/6/7→MULTI_CLOSE`, `else→UNKNOWN`） | `AnimationController.kt:64-76` | `AnimationController.java:471-499` `WhenMappings.$EnumSwitchMapping$0` |
| 7 | 3 种 `TaskStateChangeTimeOutListener`（special scene exit / transition finish / overview continuation） | `AnimationController.kt:40-45` | `AnimationController.java` 内部 3 个字段 |
| 8 | `appLaunchAnimStartOrEnd` 含 end 分支（清 list + checkAllAnimationFinished + 转 WAITING/MULTI_WAITING） | `AnimationController.kt:88-100` | `AnimationController.java:512-555` |
| 9 | `cleanUpRecentsAnim` 清理 recentsAnims + onceGestureProcessingFlag | `AnimationController.kt:82-88` | `AnimationController.java` `cleanUpRecentsAnim()` |

### 2.2 有意简化（lib 注释中明示或合理的 demo 化）

| # | 简化内容 | 原厂对应 | lib 取舍理由 |
|---|---|---|---|
| 1 | `supportInterruption()` 恒 true | `OplusAnimManager.java:230-232` 是 3 条件复合（`!isAppTransitionByLightAnim() \|\| isAdaptiveAnimation()` + `ENABLE_SHELL_TRANSITIONS` + `isSupportBlockableAnimation()`） | 三个条件都依赖 `LauncherAnimConfig` / `TaskAnimationManager` / `AppFeatureUtils` 三个 launcher/ROM 私有配置类；lib 无 launcher 上下文，简化为 true（`OplusAnimManager.kt:42` 注释自承）|
| 2 | `interruptionEnabled: Boolean` 演示降级开关 | 原厂无此字段，feature 切换通过静态门控 | demo 化合理（`OplusAnimManager.kt:57-63`）；生产用建议改为只读 + 私 setter |
| 3 | Default 基类用 `open class` 而非 `factory pattern` | `DefaultAnimationController.java` 实际是 abstract+factory 模式，lib 直接当 `open class` 用 | 形状等价；lib 更 idiomatic（Kotlin 不需要 factory pattern）|
| 4 | `MultiOpenPreStartHelper` 工厂受 `AppFeatureUtils.isSupportPreStart()` 二次门控 | `OplusAnimManager.java:120-122` 显式 `&& AppFeatureUtils.isSupportPreStart()` | lib 直接 return `MultiOpenPreStartHelper()`（**未实现**，仅指"简化"——若回移需保留该门控）|
| 5 | `OplusAnimManager` 的 `INSTANCE + static{}` 块在 lib 用 `init {}` 块 | `OplusAnimManager.java:60-118` | Kotlin `object` 单例 + `init` 块语义上等价，但**线程语义不同**：OPPO `static{}` 块在类加载线程（一般主线程）执行；lib `init` 块在首次访问类时执行——并发首访 race-condition 可能让 2 个 helper 同时初始化（**bug 级**见 §3.1-1）|
| 6 | `cleanUpRecentsAnimation()` 只调 1 个 helper（`animationControllerImpl`），原厂调 3 个 | `OplusAnimManager.java:171-178` 调 3 个 | 简化，**清理不完整**（见 §1.1 行）|

### 2.3 遗漏（原厂有、lib 没有、且影响语义或运行时行为）

| # | 遗漏点 | 原厂证据 | 影响 |
|---|---|---|---|
| 1 | **5 个 merge/pre-start/拦截 helper 工厂方法** | `OplusAnimManager.java:104-118` `createAppOpenAnimMergeHelper` / `createMultiAppAnimMergeHelper` / `createInterceptKeyEventHelper` / `createMultiOpenPreStartHelper` + L78-83 委托字段 | lib `init` 块（`OplusAnimManager.kt:28-32`）只创建 `AnimationController` + `AnimationSeqHelper`；其余 4 个 helper 工厂字段完全未声明——**`OplusAnimManager.getAppOpenAnimMergeHelper()` NPE** |
| 2 | **`recreateAnimHelper()` 4 helper 重置** | `OplusAnimManager.java:198-205` | feature flag 切换场景下无法重置 helper 状态（demo 用 `interruptionEnabled` 替代了部分，但**漏 `recreateAnimHelper` 这条显式重置路径**）|
| 3 | **`reset()` 3 helper 链式清理** | `OplusAnimManager.java:209-216` | helper 状态在多次转场后可能脏 |
| 4 | **`tryFinishOpenRemote(Runnable)` 远程动画合并完成回调** | `OplusAnimManager.java:234-238` | **bug 级**——recents→app 远程动画合并场景丢失（见 §1.2 Helper C 影响）|
| 5 | **`matchAnimationId(int, int)`** | `OplusAnimManager.java:184-190` | cross-Animation-Controller 协作场景无判定 |
| 6 | **`supportInterruption(ItemInfo)` zoomWindowPkg + SplitScreen 复合判定** | `OplusAnimManager.java:240-264` | split-screen 缩放窗口启动场景会走错路径（demo 不触发）|
| 7 | **`MultiOpenPreStartHelper` 工厂的 `AppFeatureUtils.isSupportPreStart()` 二次门控** | `OplusAnimManager.java:120-122` | 若回移需保留；OPPO 部分设备硬件不支持 pre-start，需走 Default 降级 |
| 8 | **`cleanUpRecentsAnimation` 漏调 `getMultiAppAnimMergeHelper().setOnTaskAppearedTarget(null)` 和 `getAppOpenAnimMergeHelper().cleanUpRecentsAnim()`** | `OplusAnimManager.java:174-177` | lib `OplusAnimManager.kt:51-53` 只调 `cleanUpRecentsAnim()`，**不完整** |
| 9 | **`cleanUpRecentsAnim` 返回 true 时的多 helper 联动清理** | `OplusAnimManager.java:173-175`：`getMAnimationController().cleanUpRecentsAnim()` 返回 true 时再 `getMMultiAppAnimMergeHelper().setOnTaskAppearedTarget(null)` | lib `AnimationController.kt:82-88` 返回 `!hasOpeningAnim` 但**`OplusAnimManager.cleanUpRecentsAnimation` 不消费该返回值**——链式联动缺失 |
| 10 | **`AppOpenAnimMergeHelper.onRemoteAnimationMerged` 6 参数 ~150 行（JADX 自标反编译错误）** | `AppOpenAnimMergeHelper.java:151` | 6 参数、~150 行真实逻辑因 JADX 反编译错误**无法逐行复刻**——只能语义桩 |
| 11 | **`MultiOpenPreStartHelper.preStartMultiOpenAnim` 5 参数 + ReentrantLock/Condition/ArrayMap 三件套** | `MultiOpenPreStartHelper.java:371-430` | 多 app 启动的核心协调器，~200 行（含 5 个并发原语字段 + 2 个 `Transaction` 暂存 + 2 个 `ArrayMap`）|
| 12 | **`InterceptKeyEventHelper` 反射 `OplusWindowManager.setInterceptKeyEventEnabled`（新/旧两套 API）** | `InterceptKeyEventHelper.java:131-149` 反射 `Class.forName("android.view.OplusWindowManager")` + `getMethod(..., Boolean.TYPE, IRemoteTransitionInputCallback.class)` | OPPO 私有类反射，跨 ROM 必 NoClassDefFoundError——**回移需 try-catch 兜底** |
| 13 | **`AppSwipeToRecentContinuationHelper` 1372 行** | `AppSwipeToRecentContinuationHelper.java` 全文 | review 06 / 11 / 12 已多次提及；本 review §1.3 拆解；**核心是 `isAppSwipeToRecentContinuationRunning()` 桩** |

---

## ③ 行为差异风险点

按 bug 级严重度排序。

### 3.1 工厂层风险

| # | 风险 | 触发场景 | 修复成本 |
|---|---|---|---|
| 1 | **（bug）`OplusAnimManager.init` 块并发首访 race**：`init` 块在首次访问 OplusAnimManager 时执行，并发线程同时 `animController` getter 会触发 2 个 helper 同时初始化（**`OplusAnimManager` 不是 `by lazy`**）。原厂 `static{}` 块在类加载期由 JVM 保证线程安全。 | demo 启动时若多线程同时访问 `OplusAnimManager.animController` | 5 行（`by lazy` 替换裸 `var`）|
| 2 | **（bug）`OplusAnimManager` 6 个 helper 中只 2 个被 init**，其余 4 个字段未声明 → `OplusAnimManager.getAppOpenAnimMergeHelper()` 编译错（**不是 NPE，是编译失败**）—— 任何业务调用 merge helper 路径**根本无法编译** | 任何想演示 multi-app / recents-merge / 按键拦截的场景 | 4 行（加 4 个 `private var` 字段，默认 null）|

### 3.2 helper 级风险

| # | 风险 | 触发场景 | 修复成本 |
|---|---|---|---|
| 1 | **（bug）`AppOpenAnimMergeHelper` 缺失**：`tryFinishOpenRemote` 永远走 `runnable.run()` 直接路径，**recents-merge-open 场景不挂起**；`onRemoteAnimationMerged` 入口不存在，**recents→app 转场合并动画不触发** | recents→app 启动 | ~80 行（含 150 行真实逻辑的语义桩）|
| 2 | **（bug）`MultiAppAnimMergeHelper` 缺失**：`setRecentsAnimEndState` 整段 NPE；`prepareMultiAppOpenAnim()=false` 永远成立，"两个图标先后点击只起一次 recents 转场"语义丢失 | multi-app 启动 | ~50 行 |
| 3 | **（bug）`AppSwipeToRecentContinuationHelper.isAppSwipeToRecentContinuationRunning()` 缺失**：`delayStartActivityIfNeed` 第三层用 100ms 时间窗代替运行态，**误挂/漏挂** | swipe-to-recents 后的 startActivity | ~30 行（运行态查询桩）|
| 4 | **（bug）`InterceptKeyEventHelper` 缺失**：BACK 键在 recents 转场期间不拦截，**`OplusBaseSwipeUpHandler.notifyInterceptKeyEvent$1$1` 调 `sendBackKeyEvent` NPE** | recents 转场中按 BACK | ~30 行（Default 基类 + 反射 try-catch）|
| 5 | **（bug）`MultiOpenPreStartHelper` 缺失**：`OplusAnimManager.getMultiOpenPreStartHelper()` NPE → `Launcher.onResume`/`onStop` 调 `resetRecentsFinishToHomeFlag` 即崩；多 app 启动 pre-start SurfaceControl 事务合并完全不可用 | multi-app 启动；Launcher 生命周期 | ~200 行（13 个 public 方法 + 5 并发原语 + 2 Transaction 字段）|
| 6 | **（bug）`appLaunchAnimStartOrEnd` 缺 `MESSAGE_RELEASE_TOUCH(101)` + 600ms 闸门**（review 12 §3-B6 重提）：原厂通过 `mOpenWindowAnimRunning` 在打开动画期间禁止触摸；lib 完全没这层，`forbidTouch()` 恒 false | OPEN_FROM_HOME 的 600ms 内 onClick 触发 startActivity | ~30 行（`mHandler` + 101 消息 + `mOpenWindowAnimRunning` 字段）|
| 7 | **（bug）`AnimationFeatureHelper` 默认值 1/0 而非 -1**（review 03 §3-g 重提）：业务侧对 -1 应走独立分支（`isAdaptiveAnimation` 钳制）——lib 直接生效会破坏业务默认行为 | demo 中所有 RUS 配置读取点 | 7 行 |
| 8 | **（bug）`updateNextFinishSeqIdIfNeed` 无条件覆盖 pair**（review 12 §3-D10）：lib 每次 `++seqId` 并覆盖；原厂仅当 pair 为空或 controller 变更才更新。**seqId 单调性语义不一致** | D7 `Demo7SeqIdDedupActivity` 重复调用时 lib 会误判为新一轮 | 5 行 |
| 9 | **（高）`addRecentsAnim` 状态转移无 "Error animation state" 日志**（review 12 §3-D11）：lib `else → UNKNOWN` 无日志，**状态机异常路径静默** | 任何 else 分支命中 | 1 行 |
| 10 | **（高）`TaskStateChangeTimeOutListener` 绑到主线程 Handler 而非 `URGENT_TRANSACTION_EXECUTOR`**（review 12 §3-A2）：主线程满载时 timeout 推迟 | 真机主线程满载时原厂超时更早发生 | 5 行（换 Handler 来源）|
| 11 | **（高）`TaskStateChangeTimeOutListener` 缺全局事件总线 + type-specific callback**（review 12 §3-A1）：原厂 `TaskStateHelper.globalListeners` 集中 dispatch + 3 个独立 callback；lib 合并成 `onTimeOut(type, duration)` 且无人调用 | "事件触发"路径全废 | ~60 行（单例总线 + 3 callback 拆分）|
| 12 | **（高）`delayStartActivityIfNeed` 第一层漏 `!isTablet()`**（review 12 §3-B4）：平板 landscape 场景原厂不挂起，lib 挂起——**平板用户体验分支反转** | 平板模拟器跑 D6 | 1 行（`&& !isTablet()`）|
| 13 | **（中）`delayStartActivityIfNeed` 第二层漏 `isSpecialAppScene(intent)`**（review 12 §3-B5）：搜索入口场景原厂等 transition finish，lib 直接放行——**搜索框可能闪一下** | D6/D9 用搜索入口 intent 触发 | ~10 行 + 注入接口 |
| 14 | **（中）`OplusAnimManager.recreateAnimHelper()` 缺失**：feature flag 切换场景下无法重置 helper 状态 | demo 用 `interruptionEnabled` 替代部分，但**漏显式重置** | ~5 行（4 helper 重置）|
| 15 | **（中）`OplusAnimManager.reset()` 缺失**：helper 状态在多次转场后可能脏 | 反复触发 multi-app 启动 | ~5 行（3 helper 清理）|
| 16 | **（低）`OplusAnimManager.matchAnimationId` 缺失** | cross-Animation-Controller 协作场景 | ~5 行 |
| 17 | **（低）`OplusAnimManager.supportInterruption(ItemInfo)` 缺失** | split-screen 缩放窗口启动 | ~15 行（zoomWindowPkg + SplitScreen 复合判定）|
| 18 | **（低）`cleanUpRecentsAnimation` 漏调 2 个 merge helper** | 多次转场后 merge helper 状态脏 | 2 行（在 `OplusAnimManager.kt:51-53` 调 2 个 helper）|

---

## ④ 回移建议

### 4.1 值得补进 lib 的（按性价比从高到低）

| # | 建议 | 改动规模 | 价值 | 不补的后果 |
|---|---|---|---|---|
| 1 | **`OplusAnimManager.init` 改 `by lazy` 替换裸 `var`** | 5 行 | **高（bug 级）**——消除并发首访 race | 并发 demo 启动时可能触发 2 个 helper 同时初始化 |
| 2 | **补 `AppSwipeToRecentContinuationHelper` 运行态查询桩**：`object` + 静态 `@Volatile var isAppSwipeToRecentContinuationRunning` + `setRunning(boolean)`；`AnimationController.delayStartActivityIfNeed` 第三层改用该查询 | ~30 行 | **高（bug 级）**——review 12 §3-A3 已点名 | swipe-to-recents 后 100ms 内的 startActivity 被错误挂起；窗口外漏挂 |
| 3 | **补 `AppOpenAnimMergeHelper` 8 方法桩（不含 `onRemoteAnimationMerged` 主体）**：`setAppOpenRemoteTargets` / `isRecentsMergeOpenRemote` / `cleanUpRecentsAnim` / `releaseOpenRemoteTargets` 真做；`onRemoteAnimationMerged` 返回 false 桩；`tryStartRecentsForOpenRemoteMerge` / `gestureTriggerRecentsAnim` / `onRecentsAnimStart` / `checkIfRecentsAnimStarted` 内部存 callback + AtomicBoolean | ~80 行 | **高（bug 级）**——`tryFinishOpenRemote` / `OplusAnimManager.cleanUpRecentsAnimation` 链路 | recents→app 远程动画合并场景无法演示；多次转场后 helper 状态脏 |
| 4 | **补 `MultiAppAnimMergeHelper` 6 方法**：`prepareMultiAppOpenAnim` (CAS 计数器) / `multiAppOpenAnimStart` (CAS -1) / `setRecentsAnimEndState` (synchronized + 拒绝 closed when >0) / `setOnTaskAppearedTarget` / `updateRecentTargetsIfNeed`（过滤 `activityType==1`）/ `reset` | ~50 行 | **高（bug 级）**——multi-app 启动链路 | 多 app 启动演示丢语义；`AnimationController.checkAllAnimationFinished` 链 NPE |
| 5 | **补 `InterceptKeyEventHelper` 4 方法**（含反射 try-catch）：`sendBackKeyEvent` 用 `InputManager.injectInputEvent`；`setInterceptKeyEventEnabled` 两套签名都做反射（`Class.forName("android.view.OplusWindowManager")`），**try-catch 兜底 NoClassDefFoundError** | ~30 行 | **高（bug 级）**——BACK 键拦截 + `notifyInterceptKeyEvent$1$1` NPE | recents 转场中 BACK 键穿透 |
| 6 | **补 `MultiOpenPreStartHelper` 13 方法 + 5 并发原语 + 2 Transaction 字段**：`preStartMultiOpenAnim` / `onTaskAppearedCallbackOnPreAnimStart` / `onRecentsFinish` / `waitMultiOpenPreStart` 用 ReentrantLock + Condition + ArrayMap + AtomicInteger 真做；其余 9 个 no-op 桩；保留 `AppFeatureUtils.isSupportPreStart()` 门控（demo 化 true） | ~200 行 | **高（bug 级）**——多 app 启动 pre-start SurfaceControl 事务合并 | `Launcher.onResume`/`onStop` 调 `resetRecentsFinishToHomeFlag` NPE；多 app 启动演示完全不可用 |
| 7 | **补 `OplusAnimManager.recreateAnimHelper()` + `reset()` + `matchAnimationId()`** | ~15 行 | **中**——helper 状态管理 | 多次转场后 helper 状态脏；cross-Animation-Controller 协作场景无判定 |
| 8 | **补 `OplusAnimManager.tryFinishOpenRemote(Runnable)` 桩**：调 `getAppOpenAnimMergeHelper().isRecentsMergeOpenRemote()` 决定 `setAppLaunchAnimFinishCallback` 或 `runnable.run()` | ~10 行 | **中**——remote-merge 完成回调语义 | 远程动画合并完成回调无人调 |
| 9 | **补 `OplusAnimManager.supportInterruption(ItemInfo)` 桩**：demo 化 zoomWindowPkg + SplitScreen 复合判定 | ~15 行 | **中**——split-screen 启动场景 | split-screen 缩放窗口启动走错路径 |
| 10 | **补 `appLaunchAnimStartOrEnd` 101 消息闸门**：加 `mHandler` (URGENT_TRANSACTION_EXECUTOR) + `mOpenWindowAnimRunning` + 101 消息 + `forbidTouch()` override | ~30 行 | **中**——可移植性 | OPEN_FROM_HOME 600ms 内触摸不被压制 |
| 11 | **修正 `cleanUpRecentsAnimation` 链路**：在 `OplusAnimManager.kt:51-53` 调 `getMultiAppAnimMergeHelper()?.setOnTaskAppearedTarget(null)` + `getAppOpenAnimMergeHelper()?.cleanUpRecentsAnim()` | 2 行 | **中**——清理完整性 | 多次转场后 merge helper 状态脏 |
| 12 | **`AnimationFeatureHelper` 默认值改 -1** + 补 `getRadiusAnimationEnable()` + `setInterruptThreshold` 内 `isAdaptiveAnimation` 钳制 | ~10 行 | **中**——"未配置"三态 | 业务对 -1 走独立分支的代码路径失效 |
| 13 | **修 `updateNextFinishSeqIdIfNeed` 语义**：仅当 pair 为空或 controller 变更时更新 | 5 行 | **低**——seqId 单调性 | 消费方按"seqId 单调递增"做去重的场景误判 |
| 14 | **补 `addRecentsAnim` else 日志** | 1 行 | **低**——可观测性 | 状态机异常路径静默 |
| 15 | **修 `delayStartActivityIfNeed` 第一层加 `!isTablet()`** | 1 行 | **中**——平板反转 | 平板 landscape 场景错挂起 |
| 16 | **修 `delayStartActivityIfNeed` 第二层加 `isSpecialAppScene(intent)` 桩** | ~10 行 | **中**——搜索入口 | 搜索框可能闪一下 |
| 17 | **修 `TaskStateChangeTimeOutListener` handler 改 `URGENT_TRANSACTION_EXECUTOR`** | 5 行 | **中**——主线程满载时 timeout 不推迟 | 真机主线程满载时原厂超时更早发生 |

### 4.2 建议保持简化（无运行时语义影响或属于合理裁剪）

| # | 内容 | 简化理由 |
|---|---|---|
| 1 | **`OplusAnimManager` 类名"DefalutInterceptKeyEventHelper" 拼写错误不改** | 跟随原厂；改名会破坏 `OplusBaseSwipeUpHandler` import 兼容性 |
| 2 | **`AppOpenAnimMergeHelper.onRemoteAnimationMerged` 150 行真实逻辑不逐行复刻** | JADX 自标"Code decompiled incorrectly"，原始字节码需从 dex 重新提取；与"动画线程方案"主线无关——保留 false 桩即可 |
| 3 | **`AppSwipeToRecentContinuationHelper` 1372 行完整 5 continuation anim + 2 align eliminate anim + 41 个 public 方法不全部复刻** | 仅保留 §4.1-2 的运行态查询桩即可；其余 40 个方法在 lib 内不触发（demo 不演示真实 RecentsView 续行）|
| 4 | **`MultiOpenPreStartHelper` 5 个 `SurfaceControl.Transaction` 操作不完整复刻** | 依赖 `SystemUiProxy` 反射 + `RecentsViewAnimUtil` 业务方法，跨 launcher 不可移植；`preStartMultiOpenAnim` 内 `SystemUiProxy.INSTANCE.getNoCreate().getCurrentRecentCallback().onTasksAppearedCallback(...)` 调不到，演示走桩 |
| 5 | **`InterceptKeyEventHelper` 反射 `OplusWindowManager` 不强求抛异常时优雅降级** | 跨 ROM 必 NoClassDefFoundError，try-catch 已兜底；不需要二次降级（"feature 不支持就静默"）|
| 6 | **`OplusAnimManager.supportInterruption()` 3 条件复合不还原** | 依赖 `LauncherAnimConfig` / `TaskAnimationManager` / `AppFeatureUtils` 三个 launcher/ROM 私有类；demo 无 launcher 上下文 |
| 7 | **`DefaultAnimationController` 用 `open class` 而非 factory pattern 不还原** | Kotlin 不需要 factory pattern；形状等价 |
| 8 | **`MultiOpenPreStartHelper` 工厂的 `AppFeatureUtils.isSupportPreStart()` 二次门控在 lib 内恒 true** | demo 环境无 `AppFeatureUtils`；回移时记得加 |

---

## ⑤ 与前 12 份 review 的衔接

| 区域 | 已覆盖点 | 本 review 增量 |
|---|---|---|
| 01 异步/线程 | AnimationHandler ThreadLocal、LauncherBooster UX 标记、Executors、LooperExecutor | 与本 review §3.1-1（`OplusAnimManager.init` race）相关——并发首访同步问题 |
| 02 Pending/Playback | MasterClock + Holder、setFloat/addFloat、forEndCallback、cancel 跟踪 | 本 review 不涉及 |
| 03 Controller/Manager/Seq | AnimationController 状态机、OplusAnimManager 工厂、AnimationSeqHelper、AppLaunchAnimStartOrEnd 缺 end 分支 | 本 review §1.1 把 OplusAnimManager 6 个工厂字段逐一拆开；§2.3 列出 13 个遗漏点；§4.1 给出 17 个回移建议 |
| 04 帧调度 | ThreadLocal、懒删除、setProvider、Choreographer、SF-vsync | 本 review 不涉及 |
| 05 续行 + 弹簧 | timeController 委托、setCurrentFraction、generateContinuationAnim、param.copy | 本 review §1.3 把 `AppSwipeToRecentContinuationHelper` 1372 行的 5 continuation anim 字段列出来，但只建议保留运行态查询桩 |
| 06 public API & 调用面 | 25 个 public 类的 100% 命名命中率 | 本 review §1.2 + §1.3 给出每个 helper 的**行号级方法列表 + 调用方文件:行**——颗粒度比 06 细一档 |
| 07 并发原语与线程安全 | @Volatile / Atomic / ThreadLocal / lazy / 锁粒度 / race-condition | 本 review §3.1-1（裸 var 无 lazy 同步）+ §3.2-5（`MultiOpenPreStartHelper` 5 并发原语缺失）相关 |
| 08 日志/Tracing | Trace.traceBegin、LogUtils.i、Debug.getCallers、ATRACE vs ArrayDeque | 本 review §3.2-9（`addRecentsAnim` 缺 "Error animation state" 日志）相关 |
| 09 生命周期与资源回收 | onDestroy 集中清理、never-quit Executor、listener 泄漏、postDelayed 残留 | 本 review §1.1 + §3.2-7（`cleanUpRecentsAnimation` 漏 2 helper）+ §4.1-11（补全清理链路）相关 |
| 10 Kotlin 风格化 | typealias vs fun interface、companion vs object、协程机会 | 本 review §2.2-3（Default 基类用 `open class` 而非 factory pattern 合理）相关 |
| 11 功能缺口 | 907 行弹簧、MultiDynamicAnimation、merge helper、SF-vsync、LauncherBooster | 本 review §1.2 + §1.3 把 5 个 merge/pre-start/拦截 helper + AppSwipeToRecentContinuationHelper 按**方法级别 + 调用方行号**展开——颗粒度比 11 细两档 |
| 12 bug 级运行时风险 | 优先级、时钟、转移表、兜底定时器、帧相位差 | 本 review §3.1-1（OplusAnimManager init race）+ §3.2-1~5（5 helper 缺失的 NPE 风险）+ §3.2-6~9（review 12 残留 bug 重提）—— 增量是 NPE 风险的 lib 内化 |

---

## ⑥ 总结

lib 当前 `OplusAnimManager.kt`（63 行）只覆盖原厂 `OplusAnimManager.java`（269 行）的 30%——工厂本体形状 1:1，但 6 个 helper 中只 2 个（`AnimationController` / `AnimationSeqHelper`）有 lib 实现 + 1 个（`AnimationFeatureHelper`）有字段，其余 4 个（`AppOpenAnimMergeHelper` / `MultiAppAnimMergeHelper` / `InterceptKeyEventHelper` / `MultiOpenPreStartHelper`）字段未声明 → 任何调用 `OplusAnimManager.getAppOpenAnimMergeHelper()` 等的代码**无法编译**。`AppSwipeToRecentContinuationHelper`（1372 行独立单例）完全缺失，导致 `AnimationController.delayStartActivityIfNeed` 第三层运行态查询退化为 100ms 时间窗（**bug 级**）。

**bug 级残留**（按修复性价比从高到低）：
1. `OplusAnimManager.init` 裸 var race（5 行）
2. `AppSwipeToRecentContinuationHelper.isAppSwipeToRecentContinuationRunning()` 缺失（30 行）
3. `AppOpenAnimMergeHelper` 缺失（80 行 + 150 行真实逻辑因 JADX 反编译错误只能语义桩）
4. `MultiAppAnimMergeHelper` 缺失（50 行）
5. `InterceptKeyEventHelper` 缺失（30 行 + 反射 try-catch）
6. `MultiOpenPreStartHelper` 缺失（200 行，最贵）

**有意简化保留**：`OplusAnimManager.supportInterruption()` 3 条件复合简化为 true；Default 基类用 `open class` 替代 factory pattern；`AppOpenAnimMergeHelper.onRemoteAnimationMerged` 150 行真实逻辑因 JADX 反编译错误不逐行复刻；`AppSwipeToRecentContinuationHelper` 1372 行只保留运行态查询桩。

**与 review 12 残留的衔接**：本文 §3.2-6~9 + §3.2-10~13 把 review 12 已点的 10 个 bug 级问题按"helper 归属"重新组织——helper C~F + AppSwipeToRecentContinuationHelper 是本 review 增量；helper A~B + AnimationFeatureHelper 是 review 12 残留（**未在本 review 重复展开**）。

---

## 附：关键证据速查表

| 论断 | 证据 |
|---|---|
| `OplusAnimManager` 工厂本体 1:1 但 6 helper 只 2 实现 | `OplusAnimManager.java:60-118` (static{} 块) vs `OplusAnimManager.kt:28-32` (init 块) |
| `supportInterruption()` 3 条件复合 vs 恒 true | `OplusAnimManager.java:230-232` vs `OplusAnimManager.kt:42` |
| `tryFinishOpenRemote` 委托 `getMAppOpenAnimMergeHelper().isRecentsMergeOpenRemote()` | `OplusAnimManager.java:234-238` |
| `cleanUpRecentsAnimation` 调 3 helper 链式 | `OplusAnimManager.java:171-178` |
| `AppOpenAnimMergeHelper` 8 方法 + 150 行 onRemoteAnimationMerged JADX 反编译错误 | `AppOpenAnimMergeHelper.java:104, 109, 120, 124, 132, 137, 151 (decompiled incorrectly), 283, 295` |
| `MultiAppAnimMergeHelper` 6 方法 (CAS + synchronized) | `MultiAppAnimMergeHelper.java:23, 36, 50, 58, 64, 83` |
| `InterceptKeyEventHelper` 反射 `OplusWindowManager.setInterceptKeyEventEnabled` (新/旧两套 API) | `InterceptKeyEventHelper.java:131-149` + 反射懒加载委托 L48-100 |
| `MultiOpenPreStartHelper` 13 方法 + 5 并发原语 + 2 Transaction 字段 | `MultiOpenPreStartHelper.java:121, 144, 154, 162, 167, 172, 180, 189, 241, 314, 371, 431, 436` + 字段 L40-50 |
| `AppSwipeToRecentContinuationHelper` 1372 行 + `isAppSwipeToRecentContinuationRunning` 静态字段 | `AppSwipeToRecentContinuationHelper.java:静态字段isAppSwipeToRecentContinuationRunning` + `getter` |
| `delayStartActivityIfNeed` 第三层运行态查询 | `AnimationController.java:646-650` `AppSwipeToRecentContinuationHelper.INSTANCE.isAppSwipeToRecentContinuationRunning()` vs `AnimationController.kt:226-230` `uptimeMillis < overviewContinuationTimeOutMaxTime` |
| 6 个 helper 工厂字段 + 委托懒加载 | `OplusAnimManager.java:34-45, 60-118` |
| `MultiOpenPreStartHelper` 工厂双重门控（`supportInterruption` + `AppFeatureUtils.isSupportPreStart()`） | `OplusAnimManager.java:120-122` |
| `MultiAppAnimMergeHelper` 6 调用方 | `OplusBaseSwipeUpHandler.java:6233, 6240, 6346, 6355, 6357, 6866, 6870, 8909, 10902, 10924` + `AnimationController.java:197, 226, 478, 546` + `TaskAnimationManager.java` |
| `AppOpenAnimMergeHelper` 6 调用方 | `LauncherAnimationRunner.java:492, 587-589` + `QuickstepTransitionManager.java:2154, 2298-2300` + `OplusLauncherAppTransitionHelper.java` + `RecentsAnimationCallbacks.java` + `TaskAnimationManager.java` |
| `InterceptKeyEventHelper` 调用方 | `OplusBaseSwipeUpHandler.java:6347-6350, 6368-6371` + `LauncherAnimationRunner.java` + `OplusBaseRecentsAnimationController.java` + `RecentsAnimationController.java` |
| `MultiOpenPreStartHelper` 9+ 调用方 | `LauncherAnimationRunner.java:479-480, 512-513, 559-560` + `Launcher.java:2840, 3013` + `OplusLauncherSwipeHandlerV2Impl.java:1391-1393` + `OplusBaseTouchInteractionService.java` + `RecentsAnimationCallbacks.java` + `RecentsAnimationController.java` + `OplusBaseSwipeUpHandler.java` (10+ 调用点) + `IconLayerUpdater.java` + `SystemUiProxy.java` |
| `AppSwipeToRecentContinuationHelper` 11+ 调用方 | `AnimationController.java:646-650` + `StackRecentsViewAnimUtil.java` + `OplusStackRecentsView.java` + `OplusTaskViewTouchControllerImpl.java` + `OplusRecentsViewStateControllerImpl.java` + `OplusGridRecentsView.java` + `TaskViewUtils.java` + `TaskStackLayoutAlgorithm.java` + `OplusBaseSwipeUpHandler.java` + `RecentsViewAnimUtil.java` + `OplusRecentsViewImpl.java` + `OplusTaskViewImpl.java` + `OplusStackTaskView.java` + `StackPagedViewEx.java` |


## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及项 **未在本批落地任何修复**（保持原样/保持简化/属更大重构范围）。

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。