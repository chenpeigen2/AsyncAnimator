# 区域 03 对比 Review：Controller / Manager / Seq / Feature / Runner 层

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib`（`launcher/controller/`、`launcher/manager/`、`launcher/seq/`、`launcher/feature/`、`com.android.launcher3.LauncherAnimationRunner.kt`，区域 03 全部 11 个 .kt 文件 + 1 个 shell 桩）
> - **原厂**：`D:/oppo_a6_launcher/sources`（OPPO ColorOS 15 Launcher `com.android.launcher 15.8.24` JADX 反编译源码）
>
> 取证方法：lib 侧用 Python 读 UTF-8（部分 Kotlin 文件含中文但 Read 工具报 "not UTF-8"，因含 GBK 字符集走样）；sources 侧 100% 经 Grep（ripgrep 明文通道穿透企业 DLP 加密）取证，行号为 JADX 反编译文本行号。
>
> 背景见 `docs/animation-thread-analysis-v4.md`（状态机与线程模型）、`docs/animation-trace-validation.md`（真机 trace）、`docs/USAGE.md`（lib 公开 API 清单）。本区域是 review 01~04 的上层消费者：所有动画经过状态机登记、所有 SeqId 防抖经过 `AnimationSeqHelper` 中转、最终经 `LauncherAnimationRunner` 出 system_server。

---

## 1. 类对应关系表

| lib 类（文件:行） | 原厂类（文件:行） | 对应关系 |
|---|---|---|
| `launcher/controller/AnimationController.kt`（≈210 行） | `com/oplus/quickstep/utils/AnimationController.java:58`（`extends DefaultAnimationController`，≈990 行，Kotlin 反编译 `classes5.dex`） | 精确对应；lib 是 Impl、保留所有原厂关键 API |
| `launcher/controller/AnimationState.kt:9-22`（12 值） | `AnimationController.java:103-115` `enum AnimationState`（12 值，私有） | 12 状态 + `withTaskbarAlignment`/`taskbarAlignmentToLauncher` 双 boolean 取值逐一相同；lib 提升为顶层枚举 |
| `launcher/controller/DefaultAnimationController.kt:10`（open class，no-op 基类） | `com/oplus/quickstep/utils/DefaultAnimationController.java:30`（`public class DefaultAnimationController`） | 精确对应（no-op 基类），是 Ext 模式"feature off 时业务调用安全"语义本体 |
| `launcher/controller/OnAnimStateChangeListener.kt:8`（typealias） | `DefaultAnimationController.java:34-36` `interface OnAnimStateChangeListener` | 形状等价；lib 第三参 `Any?`、原厂为 `TaskInfo`（`DefaultAnimationController.java:35`） |
| `launcher/controller/TaskStateChangeTimeOutListener.kt:13-46`（fun interface + 自管理超时） | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:117-211` `static class TaskStateChangeTimeOutListener extends BaseTaskStateChangeListener` | **仅名字 + 意图对应，机制严重偏离**（见 §2.2-4、§3-d） |
| `launcher/controller/RemoteAnimationFactory.kt:11-17`（lib 自定义 2 方法 demo 接口） | `com/android/launcher3/LauncherAnimationRunner.java:242-275` `public interface RemoteAnimationFactory`（FunctionalInterface，10 个 default 方法 + 1 abstract） | lib 是 demo 重写，原厂含 `onCreateAnimation` + `appLaunchAnimStartOrEnd`/`getAnimation`/`handleAnimationMerged`/`isSameIcon`/`onAnimationCancelled`/`preLoadIcon`/`supportInterruption`/`tryFinishOpenRemote`/`getIconSurfaceRecordId` |
| `launcher/manager/OplusAnimManager.kt:13`（object 单例） | `com/oplus/quickstep/utils/OplusAnimManager.java:20`（`public final class OplusAnimManager`，Kotlin `object` 反编译） | 对应；原厂管 6 个 helper，lib 只出 2 个（`animController` + `animationSeqHelper`） |
| `launcher/seq/AnimationSeqHelper.kt:42` | `com/oplus/quickstep/utils/AnimationSeqHelper.java:17`（`extends DefaultAnimationSeqHelper`） | 精确对应 |
| `launcher/seq/DefaultAnimationSeqHelper.kt:8` | `com/oplus/quickstep/utils/DefaultAnimationSeqHelper.java:10` | 精确对应（no-op 基类） |
| `launcher/seq/AnimSeqTimeStamp.kt:13`（object，4 字段 volatile） | `com/android/systemui/shared/system/AnimSeqTimeStamp.java:10`（`public final class`，`@JvmStatic synchronized` 4 字段） | 对应；lib 用 Kotlin `object`+`@Volatile`，原厂用 Java 类 + 静态方法 |
| `launcher/feature/AnimationFeatureHelper.kt:14`（object + 7 @Volatile 字段 + 2 只读列表） | `com/oplus/quickstep/utils/AnimationFeatureHelper.java:25`（`public final class`，6 配置项 + 2 列表 + RUS 远程下发） | 对应，lib 是本地 setter 模拟 RUS |
| `com/android/launcher3/LauncherAnimationRunner.kt:13-20`（20 行桩，保留 `RemoteAnimationTarget` 壳） | `com/android/launcher3/LauncherAnimationRunner.java`（≈600 行，`extends RemoteAnimationRunnerCompat`） | 仅类型壳对应；lib 文件头注释明示 |

---

## 2. 保真度评估

### 2.1 精确复刻（行为可对齐）

| # | 设计点 | lib 证据 | 原厂证据 |
|---|---|---|---|
| 1 | **12 状态枚举**逐一对齐（含 OPPO 新增的 `SWIPE_UP_TO_CAPSULE` / `SWIPE_UP_TO_SPLIT_OR_FLOATING`） | `AnimationState.kt:9-22`（12 状态、每态 `withTaskbarAlignment` / `taskbarAlignmentToLauncher` boolean 取值） | `AnimationController.java:103-115`（同名字、同顺序、同 boolean） |
| 2 | **状态查询 getter 集合** | `AnimationController.kt:101-122`（`isOpeningAnim` 4 态判定 / `isMultiOpen` / `isMultiClose` / `isClosingAnimAndAnimClosed` / `hasRecentsAnim` / `allRecentsAnimationEnd`） | `AnimationController.java:780-783, 764-770, 757-760, 740-741, 503-504`（逐一对应） |
| 3 | **appLaunchAnimStartOrEnd 的 start 分支转移** | `AnimationController.kt:88-95`（`NONE→OPEN` / `CLOSE/MULTI_CLOSE→MULTI_OPEN` / 其它 `UNKNOWN`） | `AnimationController.java:548-555`（同 `WhenMappings.$EnumSwitchMapping$0` switch） |
| 4 | **AnimationSeqHelper 常量与 Key** | `AnimationSeqHelper.kt:9, 11, 16, 17`（`MAX_DELAY_TIME=500` / `MAX_INTERCEPT_GESTURE_DELAY_TIME=300` / `MSG_EXC_RUNNABLE=1` / `KEY_INTERRUPT_TRANSITION_START_ACTIVITY_SEQ_ID="interrupt.transition.startActivity.seqId"`） | `AnimationSeqHelper.java:18-22`（同名同值） |
| 5 | **delayFinishRecents 协议** | `AnimationSeqHelper.kt:60-72`（`canFinishRecent → 同步执行返回 false`，否则 `clearFinishRecentsRunnable`→存 `delayAction`→`sendEmptyMessageDelayed(MSG, 500 - gap)` 返回 true，多了 `maxOf(0, delay)` 防御） | `AnimationSeqHelper.java:87-99`（同结构） |
| 6 | **DefaultAnimationSeqHelper / DefaultAnimationController 的 no-op 默认值** | `DefaultAnimationSeqHelper.kt:11-22` + `DefaultAnimationController.kt:33-117`（canFinishRecent/canInterceptGesture 恒 true、delayFinishRecents 立即执行、cleanUpRecentsAnim/canFinishRecentsAnim 恒 true 等） | `DefaultAnimationSeqHelper.java:11-42` + `DefaultAnimationController.java:49-204`（同） |
| 7 | **OplusAnimManager 的 feature 工厂形状** | `OplusAnimManager.kt:23-29`（`supportInterruption() → new Impl() : new Default*()`） | `OplusAnimManager.java:96-102, 104-117`（同） |
| 8 | **AnimSeqTimeStamp 时钟基准** | `AnimSeqTimeStamp.kt:31`（默认 `clock = { SystemClock.uptimeMillis() }`，`@Volatile` 可注入） | `AnimSeqTimeStamp.java:25, 37, 49, 61, 112, 122, 132, 142`（全用 `SystemClock.uptimeMillis()`） |
| 9 | **状态查询的"恒真基类"语义** | `DefaultAnimationController` open class 所有 getter 返默认（`false`/`null`/`-1`/`0f`），setter 为 no-op | `DefaultAnimationController.java:30, 38-204`（同 no-op 矩阵） |
| 10 | **AnimationFeatureHelper 同步 + volatile 双写语义** | `AnimationFeatureHelper.kt:64-74`（`@Volatile` + `synchronized(lock)` setter + 读 `value`） | `AnimationFeatureHelper.java:99-156, 99-156`（`synchronized setXxx` 写、`@Volatile` 读） |

### 2.2 有意简化（lib 注释/文档明示或合理 demo 化）

| # | 简化内容 | 原厂对应 | lib 取舍理由 |
|---|---|---|---|
| 1 | **`supportInterruption()` 恒 true** | `OplusAnimManager.java:232-234` `(!LauncherAnimConfig.isAppTransitionByLightAnim() || LauncherAnimConfig.isAdaptiveAnimation()) && TaskAnimationManager.ENABLE_SHELL_TRANSITIONS && AppFeatureUtils.isSupportBlockableAnimation()` 三条件 | lib `OplusAnimManager.kt:36` 注释已自认：依赖 `LauncherAnimConfig` / `TaskAnimationManager` 等多源条件，JVM demo 无法复现 |
| 2 | **AnimationFeatureHelper 用本地 setter 模拟 RUS 下发** | `AnimationFeatureHelper.java:82-92, 169-373`：`RusBaseConfigManager.RusConfigChangedListener` 回调 + `updateRusConfig()` 解析 7 配置项（`launcher_anim_prj_feature_async_enable` / `..._rt_unlock_enable` / `..._1px_enable` / `..._icon_blur_enable` / `..._multi_app_block_enable` / `launcher_anim_interrupt_threshold` / `launcher_trigger_pre_boot_limit_size`）+ 2 列表（pkg / card） | lib `AnimationFeatureHelper.kt:45-56` `simulateRemoteUpdate` 一函数覆盖"配置可变"语义；同步 + volatile 机制保留 |
| 3 | **LauncherAnimationRunner 砍成类型壳** | `LauncherAnimationRunner.java` 600+ 行含 `WeakReference<ActivityInitListener>`、三段式 `AnimationResult.finish()`、binder→UI marshal、IRemoteTransitionInputCallback.KeyEvent 拦截、TransitionInfo 创建 | lib `LauncherAnimationRunner.kt:1-20` 文件头注释明示"仅保留 `RemoteAnimationTarget` 类型壳" |
| 4 | **RemoteAnimationFactory 重写为 2 方法 demo 接口** | `LauncherAnimationRunner.java:242-275` `public interface RemoteAnimationFactory` 10 个 default + 1 abstract (`onCreateAnimation` lambda$ 改名方法) | lib `RemoteAnimationFactory.kt:10-17` 重写为 `createAnimation()` + `onAnimationFinished()`，demo 直接实现 |
| 5 | **OnAnimStateChangeListener 改 typealias + 快照迭代** | `DefaultAnimationController.java:155-162` 内部 `Iterator` 直接遍历 `animStateChangeListeners`（遍历中 add/remove 触发 CME） | lib `OnAnimStateChangeListener.kt:8` typealias + `DefaultAnimationController.kt:26` `for (l in animStateChangeListeners.toList())` 拷贝快照（lib 注释明示更安全） |
| 6 | **AnimSeqTimeStamp 的 reset 收进 `resetAllForTest`** | `AnimSeqTimeStamp.java:69-107` 4 个 `@JvmStatic synchronized` public `resetLast*Time()` + `Log.d` | lib `AnimSeqTimeStamp.kt:44-49` 全部 `internal resetLastStartAppTime` + `internal resetAllForTest`，主入口仅暴露 `updateLastRecentFinishTime()` |
| 7 | **`OplusAnimManager` 的 4 个 merge helper 砍掉** | `OplusAnimManager.java:104-118` 还管 `AppOpenAnimMergeHelper` / `MultiAppAnimMergeHelper` / `InterceptKeyEventHelper` / `MultiOpenPreStartHelper`（各对应 `*Impl` + `Default*`） | 与 multi-app merge、按键拦截、预启动等 launcher 业务强相关，与"动画线程方案"主线无关 |
| 8 | **`forbidTouch()` 基类 no-op** | `DefaultAnimationController.java:80-84` 默认实现：`ScreenUtils.hasLargeDisplayFeatures() && launcher.getAppTransitionManager().isReverseToOpenAnimRunning()` | lib `DefaultAnimationController.kt:97` 直接 `return false`（无 launcher 上下文） |
| 9 | **APP_TO_OVERVIEW_CONTINUATION_TIME_OUT_DURATION = 100ms** | `AnimationController.java:59` 同名常量 | lib `AnimationController.kt:9` 同样取 100ms（直接保留） |
| 10 | **super.kt 顶层 enum 提升** | `AnimationController.java:103-115` 内部 `enum` | lib 提为顶层 `AnimationState.kt`，方便 `import` |

### 2.3 遗漏（lib 中没有、且不一定是有意砍掉）

| # | 遗漏 | 原厂证据 | 风险定性 |
|---|---|---|---|
| 1 | **`appLaunchAnimStartOrEnd` 的 end 分支 + start 分支的 `mAppLaunchAnims.add(factory)`** | `AnimationController.java:508-556` end 时 `mHandler.removeMessages(101)` → `sendEmptyMessage(101)` → `mAppLaunchAnims.remove(factory)`；空列表 + `!mOnceGestureProcessing` → `checkAllAnimationFinished()`；否则按 `OPEN`/`MULTI_OPEN` 转 `WAITING`/`MULTI_WAITING`。start 分支在 `:547` 显式 `mAppLaunchAnims.add(appLaunchAnimFactory)`。**注意 lib 注释自承这是"有意保留"偏差** | **bug 级**：OPEN→WAITING/MULTI_WAITING 不可达；`checkAllAnimationFinished` 永不自然进入；`MESSAGE_RELEASE_TOUCH` (101) 闸门与 `forbidTouch()` 的 `mOpenWindowAnimRunning` 输入防抖完全不存在（见 #5） |
| 2 | **`checkAllAnimationFinished` 收尾通路 + 两个 callback 执行** | `AnimationController.java:224-231, 237-249, 251-263, 309-320, 344-360, 382-395`：`checkAllAnimationFinished` 内联在 `cleanUpRecentsAnim` 路径调 `executeRemoteMergeFinishCallback` + `executeRecentMainFinishCallback` → `reset()`；非主线程时 `MAIN_EXECUTOR.execute`/`UI_HELPER_EXECUTOR.execute` 纠偏 | lib `AnimationController.kt:74-79, 101-103` 两个 callback 字段（`recentsAnimFinishCallback`/`appLaunchAnimFinishCallback`）**没有任何地方调用**，且基类 setter 是 no-op |
| 3 | **`revertRecentsAnimation`** | `AnimationController.java:825-837`：`mCurrentAnim = anim` + 按 `CLOSE`→`REVERSE_OPEN` / `MULTI_CLOSE`→`MULTI_REVERSE_OPEN` / 其它→`UNKNOWN` 转移 | lib `DefaultAnimationController.kt:107` 仅基类 no-op，**`AnimationController` 未 override**；`REVERSE_OPEN` / `MULTI_REVERSE_OPEN` 两个状态在 lib 里实际不可达 |
| 4 | **`canFinishRecentsAnim`** | `AnimationController.java:559-580`：appLaunchAnims 非空否决 + `seqHelper.canFinishRecent()` + `!getMJustNotifyEndCallback` + `recentsAnims.size <= 1 && matchAnimationId(animationId, curAnim.getMAnimationId())` | lib `DefaultAnimationController.kt:88` 恒 true 基类，**`AnimationController` 未 override**；demo 端无 `CustomRectFSpringAnim.mAnimationId` / `mJustNotifyEndCallback` 等字段可读 |
| 5 | **`MESSAGE_RELEASE_TOUCH(101)` + `RELEASE_TOUCH_DELAY=600ms` 闸门** | `AnimationController.java:61, 63, 271-281, 511-545`：`mHandler.sendEmptyMessageDelayed(101, 600L)` 控制 `mOpenWindowAnimRunning`；`forbidTouch()` 依赖 `mOpenWindowAnimRunning \|\| MULTI_WAITING \|\| REVERSE_OPEN \|\| startActivityRunnable != null`（`:685-687`） | lib `forbidTouch()` 恒 false，**无 600ms 闸门**；`mOpenWindowAnimRunning` 字段不存在；app launch 期间无防抖 |
| 6 | **`isStartActivityBetweenTransitionEndAndFinish` 判定不全** | `AnimationController.java:798-799`：`mIsBetweenTransitionEndAndFinish && startActivityRunnable != null`（双条件） | lib `AnimationController.kt:205-206` 只读 `isBetweenTransitionEndAndFinish` 字段，**且 `setBetweenTransitionEndAndFinish`/`setBetweenAppExitTransitionEndAndFinish` 走基类 no-op**（`DefaultAnimationController.kt:113, 115`），`isBetweenTransitionEndAndFinish` 永为 false；该方法在 lib 恒 false |
| 7 | **`setOnAppExit` 的场景判定体** | `AnimationController.java:881-895`：`NavigationMode == THREE_BUTTONS` && `!isLargeDisplayDeviceInLarge() && isDeviceInLandscape() && LauncherAnimConfig.isAdaptiveLowAnimation()` 三重门槛；满足才 `mIsNavModeLandScapeOnAppExit = true` + `registerSpecialSceneExitTimeOutListener(1500L)` | lib `AnimationController.kt:157-161` **无条件置三个 flag 为 true**（包括 `isNavModeLandScapeOnAppExit`） |
| 8 | **`setOnceGestureProcessing` 的完整判定体** | `AnimationController.java:898-952`：`mSwipingUpActivityPkg` 从 `OplusGestureState` 提取（baseActivity/topActivity）；`getIsLandScape` / `getIsContinuationHandling` / `TopTaskTracker.runningSplitTaskIds` 三源；按 `isSplitScreen\|\|isRecentContinuation` 分支 → `registerSpecialSceneExitTimeOutListener(1500L)` 或 `registerTransitionFinishListener(1500L)`；null 走 `mIsSplitScreenGesture && mIsLandScapeGesture && !isTablet && isHomeAndOverviewSame → registerSpecialSceneExitTimeOutListener(LAND_SPACE_RECENT_ANIM_TIME_OUT_DURATION=2500L)` | lib `AnimationController.kt:163-165` **只置 `onceGestureProcessingFlag = true`**，不读 `OplusGestureState`、不注册 listener |
| 9 | **`registerSpecialSceneExitTimeOutListener`/`registerTransitionFinishListener`/`registerOverviewContinuationTimeOutListener` 缺 `TaskStateHelper.addGlobalTaskStateChangeListener`** | `AnimationController.java:303-305, 339-341, 376-380` 三处 register 都先 dispose 旧 listener → `new TaskStateChangeTimeOutListener(...)` → **`TaskStateHelper.addGlobalTaskStateChangeListener(listener)`** → 存字段。`TaskStateHelper.java:18088-18096` 的 addGlobalTaskStateChangeListener 写入 `CopyOnWriteArrayList globalListeners`，由 `TaskStateHelper$taskListener$1`（即 `OplusTaskListener`，`TaskStateHelper$taskListener$1.java:26`）在 `onTaskAppeared`/`onTaskVanished`/`onTaskListenerReleased`/`onTransitionFinish`/`onLandScapeSceneExit`/`onBackPressedOnTaskRoot`/`onTaskInfoChanged` 七个回调里迭代派发 | lib `AnimationController.kt:148-155, 162-168, 171-177` 三个 register **只构造 `TaskStateChangeTimeOutListener` + 存字段**。由于 lib 的 `TaskStateChangeTimeOutListener` 也不是 `BaseTaskStateChangeListener`（fun interface + 自管理），**既不会注册到全局 list、`onLandScapeSceneExit(true)` 也不会自动触发 option**——**`delayStartActivityIfNeed` 挂起的 `startActivityRunnable` 在事件丢失时永远不会被放行**。仅 timeout 兜底能跑（§3-d） |
| 10 | **`canFinishRecents` 的双重门控** | `AnimationSeqHelper.java:70, 75`：`canFinishRecent`/`canInterceptGesture` 都用 `AppFeatureUtils.isSupportStartingSurface() && OplusAnimManager.supportInterruption() && AnimSeqTimeStamp.getTimeGap*Time() <= N` 三段与 | lib `AnimationSeqHelper.kt:54-60` **只比时间窗**，漏 `isSupportStartingSurface()` 和 `supportInterruption()` 两条 |
| 11 | **`resetInterceptState()` 的 override** | `AnimationSeqHelper.java:111-114`：调 `AnimSeqTimeStamp.resetLastStartAppTime()` | lib `DefaultAnimationSeqHelper.kt:14` 基类 no-op，**`AnimationSeqHelper` 未 override**（漏 `resetLastStartAppTime`） |
| 12 | **`mRemoveTasksMaps` / `removeTasks` / `removeTaskOnOpenAnimStart` / `removeTasksOnRealStart`** | `AnimationController.java:408-455, 803-808`：addRecentsAnim 后 `removeTasks(targets, recentsController)`（`activityType == 1 && taskId != mRunningTask.taskId && !mCurrentAnim.getMReverseToOpen()` → `recentsController.removeTaskTarget(target)`）；`LauncherAnimConfig.isAdaptiveAnimation()` 走 `mRemoveTasksMaps.put(...)` 延后 `removeTaskOnOpenAnimStart` | lib `AnimationController.kt:25, 78` `removeTasksMaps` 声明但**从未被写入**；`removeTasksOnRealStart` 走基类 no-op |
| 13 | **`OplusAnimManager` 的另 4 个 helper + `matchAnimationId` + `tryFinishOpenRemote` + `reset` + `recreateAnimHelper` + `supportInterruption(ItemInfo)` + `ANIM_TAG`/`WINDOW_ANIM_TAG` 常量 + `supportInterceptKeyEvent`** | `OplusAnimManager.java:104-118, 203-209, 211-217, 219-225, 236-246, 248-268, 22, 26, 227-229` | lib 只出 `animController` + `animationSeqHelper` + `cleanUpRecentsAnimation`（简化版，不调 `getMMultiAppAnimMergeHelper().setOnTaskAppearedTarget(null)`） |
| 14 | **`LauncherAnimationRunner.AnimationResult` / `Scenes` / 内部 `DEFAULT_FACTORY` / `mInputCallback` / `mIsFromRecents`** | `LauncherAnimationRunner.java:34-50, 80-150, 250-300` | lib `LauncherAnimationRunner.kt:1-20` 全部砍（已在 §2.2-3 列） |
| 15 | **`LauncherAnimationRunner.mFactory` 的 `WeakReference` 持有** | `LauncherAnimationRunner.java:65` + GC 时的 `finalized=` 日志（`LauncherAnimationRunner.java:305, 419-451`） | 全部砍 |
| 16 | **`LauncherAnimationRunner` 的 `setCurrentPlayTime` 首帧补偿** | `LauncherAnimationRunner.java:182-196` `setCurrentPlayTime(Math.min(RefreshRateTracker.getSingleFrameMs(context), this.mAnimator.getTotalDuration()))` | 砍 |
| 17 | **`AnimationFeatureHelper` 的 7 config 名常量（`CONFIG_NAME_LAUNCHER_ANIM_FEATURE_ASYNC_ENABLE` 等）+ `isSupportStartingSurface`/`isSupportBlockableAnimation` AppFeature 门控** | `AnimationFeatureHelper.java:26-37, 126-128`（`setInterruptThreshold` 内 `isAdaptiveAnimation → 强制 1.0f` 钳制） | lib 默认值直接 1/0，**无 -1 态（见 §3-g）**；`setInterruptThreshold` 无 `isAdaptiveAnimation` 钳制 |
| 18 | **`getOpeningProgress()`** 真实实现 | `AnimationController.java:712-719` `mCurrentAnim.getOpeningWindowProgress()` | lib 走基类 no-op（`DefaultAnimationController.kt:43` `0f`），无 `CustomRectFSpringAnim` 句柄支持 |
| 19 | **`mTransitionFinishTimeOutListener` register 路径在 `setOnceGestureProcessing` 的条件分支** | `AnimationController.java:937-940` `registerTransitionFinishListener(1500L)` | lib `AnimationController.kt:163-165` 整个 `setOnceGestureProcessing` 缩为一行 `onceGestureProcessingFlag = true`，**`transitionFinishTimeOutListener` 在 lib 里永远不会自动被注册** |

---

## 3. 行为差异风险点

按"可能导致语义不同"的严重度排序：

**a.（高 / bug 级）`appLaunchAnimStartOrEnd` end 分支 + `mAppLaunchAnims.add` 链路完全缺失**（§2.3-1）。`AnimationController.kt:88-95` 的 start 分支**只置位、不 add factory**，导致 `appLaunchAnims` 恒空 → `cleanUpRecentsAnim` 的 `hasOpeningAnim` 恒 false（`AnimationController.kt:78`）→ `checkAllAnimationFinished` 逻辑可触发但只走"recentsAnims 刚空 + appLaunchAnims 刚空"的平凡路径；原厂的 OPEN→WAITING、MULTI_OPEN→MULTI_WAITING、`mOpenWindowAnimRunning` 闸门、`MESSAGE_RELEASE_TOUCH(101)` 600ms 防抖全部不进入。在 demo 端表现为"按原厂顺序 OPEN→CLOSE→WAITING→NONE 的转场在 lib 里永远停在 OPEN/CLOSE/NONE 三态中"。

**b.（高）`addRecentsAnim` 转移表两处偏差**（lib `AnimationController.kt:64-72` vs 原厂 `:482-499`）：
- lib 把 `UNKNOWN` 归入 →CLOSE 组，原厂 `UNKNOWN` 落入 default → 保持 `UNKNOWN` 并打 `"Error animation state"` 日志（`:465-467`）；
- lib 把 `MULTI_WAITING` 落入 else → `UNKNOWN`，原厂 `MULTI_WAITING` → `MULTI_CLOSE`。

**c.（高 / bug 级）`delayStartActivityIfNeed` 三层判定非互斥 + 缺最终清理段**（lib `AnimationController.kt:178-198` vs 原厂 `:598-671`）：
- lib 三个 `if` 顺序，第一个 listener 存在但条件不满足会**继续试第二、三层并可能返回 true**；原厂 `if / else if / else if` 互斥，第一层不满足直接穿透到清理段返回 false；
- 第一层 lib 漏 `!ScreenUtils.isTablet()` 限定（原厂 `:620` `mIsLandScapeGesture && !ScreenUtils.isTablet()`）；
- 第二层 lib 漏 `isSpecialAppScene(intent)`（原厂 `:628, 640, 283-290`：`IndicatorEntry.ACTION_EXP_SEARCH_APP` / `ACTION_DOMESTIC_SEARCH_APP` / `BranchSearchHelper.SEARCH_INTENT_EXTRAS_SOURCE_VALUE` 三种 source 判定）；
- 第三层语义替换：lib 用"100ms 时间窗内"判定（`:194` `SystemClock.uptimeMillis() < overviewContinuationTimeOutMaxTime`），原厂用 `AppSwipeToRecentContinuationHelper.isAppSwipeToRecentContinuationRunning()` 运行态判定（`:646-650`）——时间窗与运行态不等价；
- 原厂最终落点 dispose 三个 listener + 清两个 Between 标志（`:653-669`），lib 三层全不命中时**什么都不清**，listener 与标志残留污染下一次调用。

**d.（高 / bug 级）`TaskStateChangeTimeOutListener` 的"事件触发"路径完全失效**（§2.3-9）。原厂 `TaskStateHelper$TaskStateChangeTimeOutListener` 是 `BaseTaskStateChangeListener`（TaskStateHelper.java:117）——构造时把自己加入 `TaskStateHelper.globalListeners`（`OplusAnimManager` 入口的 `TaskStateHelper.addGlobalTaskStateChangeListener(listener2)`），由 `TaskStateHelper$taskListener$1 extends OplusTaskListener`（`TaskStateHelper$taskListener$1.java:26`）在 `onLandScapeSceneExit(boolean z8)`/`onTransitionFinish(boolean z8)`/`onTaskListenerReleased()`/`onBackPressedOnTaskRoot`/`onTaskAppeared`/`onTaskVanished`/`onTaskInfoChanged` 七个回调里迭代派发（TaskStateHelper$taskListener$1.java:113-180）。lib `TaskStateChangeTimeOutListener.kt:13-46` 是 fun interface + 自管理 Handler timeout，**既不是 `BaseTaskStateChangeListener` 也不加入任何全局 list**；`onTimeOut(type, duration)` 只能被动由外部调用。**实际后果**：
- 1) `registerSpecialSceneExitTimeOutListener(1500L)`/`registerTransitionFinishListener(1500L)` 在原厂会**自动**通过 `OplusTaskListener` 在事件到达时触发 option；lib 里**只有 timeout 兜底**能跑（构造时 `handler.postDelayed(timeOutOption, duration)`），且 timeout handler 跑在主线程（lib `TaskStateChangeTimeOutListener.kt:31-32` `Looper.getMainLooper()`），原厂跑在 `URGENT_TRANSACTION_EXECUTOR`（TaskStateHelper.java:130）。
- 2) `delayStartActivityIfNeed` 挂起的 `startActivityRunnable`（lib `AnimationController.kt:178-198`）**只有 timeout 一条放行路径**；原厂有"事件即时放行" + "timeout 兜底"两条。任务状态变化快于 timeout 时，原厂几乎立即放行，lib 至少等 100/1500ms。

**e.（高）`isStartActivityBetweenTransitionEndAndFinish` 恒 false**（§2.3-6）。lib `AnimationController.kt:205-206` 只读 `isBetweenTransitionEndAndFinish` 字段；该字段**从未被赋 true**——`setBetweenTransitionEndAndFinish`/`setBetweenAppExitTransitionEndAndFinish` 走基类 no-op（`DefaultAnimationController.kt:113, 115`）。原厂条件是 `mIsBetweenTransitionEndAndFinish && startActivityRunnable != null`（双条件，`:798-799`），lib 缺 `startActivityRunnable != null` 半数条件 + flag 永 false。

**f.（高）`forbidTouch()` 无 600ms 闸门**（§2.3-5）。原厂 `mOpenWindowAnimRunning || MULTI_WAITING || REVERSE_OPEN || startActivityRunnable != null`（`:685-687`）；lib `DefaultAnimationController.kt:97` 恒 false。**含义**：demo 端无法演示"app launch 期间 600ms 内禁止触摸"的关键体验防抖。

**g.（中）`AnimationFeatureHelper` 默认值 -1 缺失**（§2.3-17）。原厂 6 个 int flag 默认 **-1** 表示"RUS 未下发"（`AnimationFeatureHelper.java:52-60`），业务侧可对 -1 走独立分支（如 `if (mAsyncEnable == -1 || mAsyncEnable == 1) ...`）；lib 直接给 1/0 生效值（`AnimationFeatureHelper.kt:14-19`）。**含义**：lib 丢失了"未配置"三态语义，业务侧"灰度前是否启用"判断会误判为"已启用"。`setInterruptThreshold` 内原厂 `isAdaptiveAnimation → 强制 1.0f` 钳制（`:126-128`）也无。

**h.（中）`canFinishRecent`/`canInterceptGesture` 漏双重门控**（§2.3-10）。原厂三段与（`isSupportStartingSurface() && supportInterruption() && timeGap <= N`，`AnimationSeqHelper.java:70, 75`），lib 只比时间窗。在支持 startup surface 但 RUS 未下发 async enable 的过渡设备上，lib 仍会触发 500/300ms 防抖路径，原厂则不进入防抖。

**i.（中）`setOnAppExit` 无条件置位**（§2.3-7）。原厂三键导航 + 横屏 + 低档机动画模式三重门槛（`:885-892`）；lib `AnimationController.kt:157-161` 无条件 `isLandScapeGesture = true`、`isNavModeLandScapeOnAppExit = true`、`isBetweenAppExitTransitionEndAndFinish = true`。第一层决策树（`delayStartActivityIfNeed` 第一分支）的命中条件 `isLandScapeGesture || isSplitScreenGesture || (isNavModeLandScapeOnAppExit && isBetweenAppExitTransitionEndAndFinish)` 在 lib 里**永远命中**——任何进入该方法的调用都会进入"横屏退出"挂起路径，行为偏离原厂。

**j.（中）`setOnceGestureProcessing` 缩为一行**（§2.3-8）。原厂 50+ 行判定体（含 `mSwipingUpActivityPkg` 提取、横屏/分屏/continuation 三源分流、1500/2500ms listener 嵌套注册），lib 只置 flag。三种 listener（special / transition / overview）在 lib 里**全部依赖外部显式 `register*` 调用**才存在。Demo 端"按原厂顺序触发手势"的场景下，`mTransitionFinishTimeOutListener`/`mSpecialSceneExitTimeOutListener` 不会被自动注册，后续 `delayStartActivityIfNeed` 不会进入对应分支。

**k.（中）`OplusExecutors` 时钟域**。lib 全部用 `SystemClock.uptimeMillis()`（`AnimationController.kt:109, 127, 178, 192` / `AnimationSeqHelper.kt:32, 58, 67, 69` / `AnimSeqTimeStamp.kt:31`）；原厂同样 `SystemClock.uptimeMillis()`（`AnimationController.java:298, 334, 609` / `AnimationSeqHelper.java:96`）。**此项 lib 与原厂对齐**，但要注意 100/300/500/1500ms 窗口在"主线程被 binder 阻塞 / `UX_TASK_EXECUTOR` 离线事务饿死"时两边的"窗口计时"会不同——原厂超时跑在 `URGENT_TRANSACTION_EXECUTOR`（独立线程），lib 跑在主线程（`TaskStateChangeTimeOutListener.kt:31-32`）。

**l.（中）`reset()` 字段清理不全**。原厂 `AnimationController.java:811-822` 清 8 项（callbacks ×2、`mSwipingUpActivityPkg`、appLaunchAnims、recentsAnims、removeTasksMaps、mClickAppView、mCurrentAnim + `updateAnimState(NONE)`）。lib `AnimationController.kt:101-114` 清 11 项但**多清**几个 lib 自加的 boolean（`isLandScapeGesture`/`isSplitScreenGesture`/`isNavModeLandScapeOnAppExit`/`isBetweenAppExitTransitionEndAndFinish`/`isBetweenTransitionEndAndFinish`），**少清** `mSwipingUpActivityPkg`（lib 无此字段）和 `mCurrentAnim`（无），并漏原厂 call 顺序里"先 updateAnimState 再清 callback"的次序（lib 是先清 callback 再 updateAnimState，对 listener 通知而言**先清 callback 再发 NONE 转移**，原厂**先发 NONE 转移再清 callback**——lib listener 收到 NONE 时 callback 已被清，业务侧 `setRecentsAnimFinishCallback` 之后再 setState(NONE) 仍能正常接收，行为接近但不等价）。

**m.（中）`OplusAnimManager.cleanUpRecentsAnimation` 简化**。原厂 `OplusAnimManager.java:173-181` 在 `getMAnimationController().cleanUpRecentsAnim() == true` 时调 `getMMultiAppAnimMergeHelper().setOnTaskAppearedTarget(null)` + `getMAppOpenAnimMergeHelper().cleanUpRecentsAnim()`；lib `OplusAnimManager.kt:50-52` 只调 `animationControllerImpl?.cleanUpRecentsAnim()`。multi-app merge helper 在 lib 整体缺失。

**n.（低）`executeRemoteMergeFinishCallback` 主线程判定反向**。原厂 `AnimationController.java:251-263` `if (checkMainThread())` → `UI_HELPER_EXECUTOR.execute(runnable)`，else → `runnable.run()`。**含义**：原厂期望从非主线程调用，callback 跑在 `UI_HELPER_EXECUTOR`；主线程调用则走 `UI_HELPER_EXECUTOR` 异步派发（卸载到后台）。lib 完全没有这层卸载；任意线程调用直接同步跑回调（lib 注释 §2.2-5 已声明回调在调用方线程），**对状态机调用方的线程纪律要求被抬高**。

**o.（低）`OnAnimStateChangeListener` 第三参类型弱化**。原厂 `DefaultAnimationController.java:35` 第三参 `TaskInfo`；lib `OnAnimStateChangeListener.kt:8` 第三参 `Any?`。**含义**：原厂 listener 可直接读 `taskInfo.taskId` / `taskInfo.baseActivity`，lib listener 要自己 `as? TaskInfo` 强转。对 demo 不影响。

**p.（低）`getNextFinishSeqId` 的相等判定**。原厂 `AnimationSeqHelper.java:104-108` 用 `Intrinsics.areEqual(lVar.f12314a, recentsAnimationController)`（**结构相等**），lib `AnimationSeqHelper.kt:82-84` 用 `p.first === recentsController`（**引用相等**）。lib 注释自承"原厂按引用比较"是错的——原厂是结构相等。**含义**：当 recents controller 重建但内容相等时，原厂仍命中 pair、seqId 稳定；lib 引用不同返回 0。

**q.（低）`revertRecentsAnimation` 在 lib 不可达**（§2.3-3）。`REVERSE_OPEN` / `MULTI_REVERSE_OPEN` 状态由 `revertRecentsAnimation(anim)` 转移产生（`AnimationController.java:825-837`），lib 未 override，**这两个状态实际进入不了**。任何 `isOpeningAnim`/`isMultiClose`/`isClosingAnimAndAnimClosed` 等依赖 `REVERSE_OPEN` 分支的查询在 lib 里结果与原厂偏差。

---

## 4. 回移建议

### 4.1 值得补进 lib 的（按收益/成本比排序）

1. **补 `appLaunchAnimStartOrEnd` 的 end 分支 + start 分支的 `mAppLaunchAnims.add(factory)` + `MESSAGE_RELEASE_TOUCH(101)` 600ms 闸门**（对应 §2.3-1、§2.3-5、§3-a、§3-f）。约 30 行；这是 OPEN→WAITING/MULTI_WAITING 生命周期闭环的另一半，状态机"能转到底"的最小必要条件。`mHandler = Handler(Looper.getMainLooper())` + `mOpenWindowAnimRunning: Boolean` 字段 + `forbidTouch()` override 按 `mOpenWindowAnimRunning || animState == MULTI_WAITING || animState == REVERSE_OPEN || startActivityRunnable != null` 写齐。

2. **`TaskStateChangeTimeOutListener` 改造成原厂 `BaseTaskStateChangeListener` 形状**（对应 §2.3-9、§3-d）。至少三件事：
   - 在 `OplusAnimManager` 三个 register helper 里补 `TaskStateHelper.addGlobalTaskStateChangeListener(listener)`（lib 没有 `TaskStateHelper` 概念，先在 lib 内部建一个等价的 `TaskStateChangeListener` 全局 list + `OplusTaskListener` stub）；
   - 把 `TaskStateChangeTimeOutListener` 改回 `class extends BaseTaskStateChangeListener`（而不是 fun interface），并提供 7 个 default callback 的 `onLandScapeSceneExit(z)`/`onTransitionFinish(z)`/`onTaskListenerReleased()` 等在 `type` 匹配时执行 option + `dispose()`；
   - timeout handler 从 `Looper.getMainLooper()` 改回 `URGENT_TRANSACTION_EXECUTOR`-like（lib 内部起一个 `Executors.ANIM_CONTROL_EXECUTOR` 即可，原厂用 `OplusExecutors.getURGENT_TRANSACTION_EXECUTOR().getHandler()`，lib 可以用同等的内部线程）。

   这一项是 lib 整个区域最大的语义缺口——**"事件触发 + timeout 兜底"是原厂 `TaskStateChangeTimeOutListener` 存在的核心理由**。

3. **时钟基准统一为单调时钟 + `delayStartActivityIfNeed` 改回 `else if` 互斥结构 + 最终清理段**（对应 §2.3-10、§3-c、§3-h）。lib 已经是 JVM 库，`SystemClock.uptimeMillis()` 在 Android 上等价于 `System.nanoTime()/1_000_000`；demo 端可以注入 `Clock` 接口。互斥结构 + 清理段是判定逻辑正确的最小结构：`if (mSpecialSceneExitTimeOutListener != null) {...} else if (mTransitionFinishTimeOutListener != null) {...} else if (mOverviewContinuationTimeOutListener != null) {...} [clearAllAndReturn false]`。第三层把"100ms 时间窗"换回 `AppSwipeToRecentContinuationHelper.isAppSwipeToRecentContinuationRunning()` 风格（lib 内部 helper），第二层补 `isSpecialAppScene(intent)`（三 source 字符串相等判定，可硬编码），第一层补 `!ScreenUtils.isTablet()`（demo 端 tablet 模式开关可暴露）。

4. **override `setBetweenTransitionEndAndFinish` / `setBetweenAppExitTransitionEndAndFinish` + `isStartActivityBetweenTransitionEndAndFinish` 补 `&& startActivityRunnable != null`**（对应 §2.3-6、§3-e）。`AnimationController` 里两个 setter override 写 `isBetweenTransitionEndAndFinish = value`（含 `setBetweenAppExitTransitionEndAndFinish` 的 `mIsNavModeLandScapeOnAppExit` 守卫，`:858-861`），`isStartActivityBetweenTransitionEndAndFinish` 改 `isBetweenTransitionEndAndFinish && startActivityAction != null`（原厂用 `startActivityRunnable`）。约 10 行。

5. **override `canFinishRecentsAnim` / `revertRecentsAnimation` / `removeTasksOnRealStart`**（对应 §2.3-3、§2.3-4、§2.3-12）。`canFinishRecentsAnim` 在 demo 端可以简化为"appLaunchAnims 非空否决 + recentsAnims 数量判定"两行（不动 `seqHelper.canFinishRecent` ——见 #6）。`revertRecentsAnimation` 加 CLOSE→REVERSE_OPEN / MULTI_CLOSE→MULTI_REVERSE_OPEN 的两行转移。`removeTasksOnRealStart` 与 `mRemoveTasksMaps` 一并补上 `removeTaskOnOpenAnimStart(target, controller)` 的最小骨架（demo 端 `recentsController.removeTaskTarget(target)` 用 mock 即可）。约 25 行总。

6. **canFinishRecent / canInterceptGesture 补双重门控**（对应 §3-h）。三段与：`isSupportStartingSurface() && supportInterruption() && timeGap <= N`。`isSupportStartingSurface` / `supportInterruption` 在 lib 内部都恒 true（demo 配置），所以这一项实际等价于"恢复 `OplusAnimManager.supportInterruption()` 调用"——不增加代码量。**注意**：要补 `AnimationSeqHelper.resetInterceptState()` 调 `AnimSeqTimeStamp.resetLastStartAppTime()`（`AnimationSeqHelper.java:111-114`），2 行。

7. **`reset()` 字段清理补 `mSwipingUpActivityPkg` / `mCurrentAnim`**（对应 §3-l）。原厂先 `updateAnimState(NONE)` 再清 callback，lib 改次序。

8. **`setOnAppExit` 补场景判定体**（对应 §3-i）。三键导航 + 横屏 + 低档机动画模式三个 boolean 门控，lib 内部用 `BuildConfig` / 编译时常量代替 `LauncherAnimConfig.isAdaptiveLowAnimation()` / `ScreenUtils.isDeviceInLandscape()`，约 10 行。

9. **`setOnceGestureProcessing` 补 register listener 自动注册**（对应 §3-j）。即使不做 `mSwipingUpActivityPkg` 提取，至少 `isLandScape` + `isSplitScreen` + `isRecentContinuation` 三源判定 + 1500/2500ms 三个 register 分支要落地，**否则 `transitionFinishTimeOutListener` 永远不会被自动注册**（只在 #2 改造完之后才有效）。

10. **`AnimationFeatureHelper` 默认值改回 -1**（对应 §3-g）。6 个 int flag 一行改完，零成本。`setInterruptThreshold` 内补 `isAdaptiveAnimation → 强制 1.0f` 钳制（`AnimationFeatureHelper.java:126-128`），3 行。

### 4.2 建议保持简化

1. **`OplusAnimManager` 的 merge helper 三件套**（`AppOpenAnimMergeHelper` / `MultiAppAnimMergeHelper` / `MultiOpenPreStartHelper`）。原厂服务于 multi-app merge、预启动、按键拦截等 launcher 专属业务，与"动画线程方案"主线无关。回移会把 lib 拖进 launcher 业务建模。

2. **`AnimSeqTimeStamp` 的同步 `synchronized` 方法**。原厂 4 个 `getTimeGapTo*` 都是 `@JvmStatic synchronized`（`AnimSeqTimeStamp.java:22, 34, 45, 57`），lib 用 `@Volatile` 字段 + 读端无锁（`AnimSeqTimeStamp.kt:33, 35, 37, 39`）。**lib 的"读到可能略陈旧"语义在 demo 场景下无害**，原厂的同步是为了多线程下 1ms 量级精度，demo 单测不需要。

3. **主线程 marshal / `checkMainThread` + `MAIN_EXECUTOR.execute` 纠偏**。原厂在 `executeRemoteMergeFinishCallback`/`executeRecentMainFinishCallback`/`registerSpecialSceneExitTimeOutListener` 的 option 路径上判主线程、非主线程纠偏（`AnimationController.java:243-263, 310-319, 350-359, 384-395`）。lib 是演示库，回调线程纪律由 lib 自己的 executor 体系（`LooperExecutor`/`AsyncAnimWrapper`，review 01 已复刻）统一承载更合适。**建议在 `AnimationController` 类注释里显式声明"回调在调用方线程"**，避免使用者按原厂语义假设主线程。

4. **LauncherAnimationRunner / AnimationResult / RemoteAnimationFactory 全套 600+ 行**。三段式 `AnimationResult.finish()`、binder→UI marshal、`setCurrentPlayTime` 首帧补偿、`WeakReference<factory>` GC 处理全部砍。理由：原厂 600+ 行服务于"system_server binder 线程 + launcher.anim 线程 + UI 线程"三线程桥接，lib 是 JVM demo 无此场景；类型壳（`RemoteAnimationTarget`）已保留供 `appLaunchAnimStartOrEnd` 签名使用。

5. **`mRemoveTasksMaps` 的 `LauncherAnimConfig.isAdaptiveAnimation` 分支**。依赖 adaptive animation feature flag，lib 内部无对应物。

6. **`LogUtils` 6 档门控**（review 05 §2.2-2 已列）。

7. **`setOpeningRemoteAnimWidgetId`/`getOpeningRemoteAnimWidgetId`/`isCloseWidgetRemoteAnim`/`setCloseWidgetRemoteAnim`/`isAppWindowAnimRunning`/`openingProgress` 等 widget/状态查询**（`DefaultAnimationController.java:42, 102-105, 121-128`）。服务于 widget 远程动画分支，lib demo 暂未涉及。

8. **`ANIM_TAG`/`WINDOW_ANIM_TAG` 常量**（`OplusAnimManager.java:22, 26`）。仅为 LogUtils tag 字符串，lib 不复刻 LogUtils 即不需要。

---

## 附：关键证据速查

| 论断 | 证据 |
|---|---|
| lib `AnimationController` 12 状态 + 字段 | `D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/controller/AnimationController.kt:25-49` + `AnimationState.kt:9-22` |
| lib `addRecentsAnim` 转移表 | `AnimationController.kt:64-72` |
| lib `appLaunchAnimStartOrEnd` 缺 end 分支 + `mAppLaunchAnims.add` | `AnimationController.kt:88-95`（start 分支只 `updateAnimState`，无 add） |
| lib `delayStartActivityIfNeed` 三层非互斥 | `AnimationController.kt:178-198`（三个顺序 `if`，无 `else if`） |
| lib `TaskStateChangeTimeOutListener` 是 fun interface | `D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/controller/TaskStateChangeTimeOutListener.kt:13-46` |
| lib `isStartActivityBetweenTransitionEndAndFinish` 恒 false | `AnimationController.kt:205-206` + `DefaultAnimationController.kt:113, 115`（基类 no-op） |
| lib `setOnAppExit` 无条件置位 | `AnimationController.kt:157-161` |
| lib `setOnceGestureProcessing` 缩为一行 | `AnimationController.kt:163-165` |
| lib `AnimationSeqHelper` 三段与漏两段 | `AnimationSeqHelper.kt:54-60` |
| lib `AnimationFeatureHelper` 默认 1/0 非 -1 | `AnimationFeatureHelper.kt:14-19` |
| lib `OplusAnimManager` 只出 2 个 helper | `OplusAnimManager.kt:21-29` |
| 原厂 `AnimationController` 12 状态枚举 | `D:/oppo_a6_launcher/sources/com/oplus/quickstep/utils/AnimationController.java:103-115` |
| 原厂 `addRecentsAnim` 转移表 + `WhenMappings` switch | `AnimationController.java:471-500` |
| 原厂 `appLaunchAnimStartOrEnd` 完整 + `MESSAGE_RELEASE_TOUCH(101)` 600ms | `AnimationController.java:508-556, 61, 63, 271-281, 685-687` |
| 原厂 `delayStartActivityIfNeed` 互斥 + 清理 | `AnimationController.java:598-671` |
| 原厂 `isSpecialAppScene` 三 source 判定 | `AnimationController.java:283-290` |
| 原厂 `canFinishRecentsAnim` 多门控 | `AnimationController.java:559-580` |
| 原厂 `revertRecentsAnimation` | `AnimationController.java:825-837` |
| 原厂 `forbidTouch` 5 条件 | `AnimationController.java:685-687` |
| 原厂 `setBetweenTransitionEndAndFinish` guard | `AnimationController.java:858-867` |
| 原厂 `isStartActivityBetweenTransitionEndAndFinish` 双条件 | `AnimationController.java:798-799` |
| 原厂 `setOnAppExit` 三门槛 | `AnimationController.java:881-895` |
| 原厂 `setOnceGestureProcessing` 50+ 行判定 | `AnimationController.java:898-952` |
| 原厂 `TaskStateChangeTimeOutListener` 全局注册 + 7 回调 | `TaskStateHelper.java:117-211, 18085-18096`（addGlobalTaskStateChangeListener） + `TaskStateHelper$taskListener$1.java:113-180`（迭代派发） |
| 原厂 `TaskStateChangeType` 4 值 | `TaskStateHelper.java:17844-17850` |
| 原厂 `AnimationSeqHelper` 双重门控 | `AnimationSeqHelper.java:70, 75` |
| 原厂 `AnimationSeqHelper.resetInterceptState` 调 `resetLastStartAppTime` | `AnimationSeqHelper.java:111-114` |
| 原厂 `AnimationFeatureHelper` 默认 -1 + 7 config 名常量 | `AnimationFeatureHelper.java:52-60, 26-37` |
| 原厂 `setInterruptThreshold` `isAdaptiveAnimation` 钳制 | `AnimationFeatureHelper.java:123-132` |
| 原厂 `OplusAnimManager` 6 helper + 4 配套方法 | `OplusAnimManager.java:96-118, 203-209, 211-217, 219-225, 236-246, 248-268` |
| 原厂 `OplusAnimManager.supportInterruption` 三条件 | `OplusAnimManager.java:232-234` |
| 原厂 `LauncherAnimationRunner` 600+ 行 | `D:/oppo_a6_launcher/sources/com/android/launcher3/LauncherAnimationRunner.java:1-300` |
| 原厂 `RemoteAnimationFactory` 10 default + 1 abstract | `LauncherAnimationRunner.java:242-277` |
| 原厂 `LauncherAnimationRunner.AnimationResult` 三段式 finish | `LauncherAnimationRunner.java:80-150` |
| 原厂 `AnimSeqTimeStamp` 4 字段 + `@JvmStatic synchronized` | `D:/oppo_a6_launcher/sources/com/android/systemui/shared/system/AnimSeqTimeStamp.java:10-148` |


## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **0e8a472** — Executors.mainHandlerOrNull→runCatching.getOrNull
- **60bd048** — AnimationController.revertRecentsAnimation、cleanUpRecentsAnim→checkAllAnimationFinished、interruptionEnabled @Synchronized、TaskStateChangeTimeOutListener 自管超时 + runCatching

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。