# 区域 09 对比 Review：生命周期与资源回收

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/`（async、animthread、controller 子包 + 顶层监听器）
> - **原厂**：`D:/oppo_a6_launcher/sources`（OPPO ColorOS 15 Launcher 15.8.24 JADX 反编译）
>
> 原厂文件经 DLP 加密（`%TSD-Header`），全部证据通过 Grep（ripgrep 明文通道）取得，行号为 JADX 反编译文本行号。
>
> 区域范围：动画 cancel 路径、线程关闭 / Executor shutdown、listener 引用泄漏、Activity onDestroy 安全、ANIM_EXECUTOR 永不 quit 的进程级单例语义、Handler.postDelayed 残留清理、ThreadLocal 帧源随线程退出清理。
>
> 背景结论见 `docs/animation-thread-analysis-v4.md`（v4）；前置 4 份 review 见 `docs/review/01~04`。

---

## ① 类对应关系表

| lib 类 | 原厂类 | 关键证据（原厂 文件:行） |
|---|---|---|
| `async/AsyncValueAnimator.kt` | `com.android.quickstep.util.animation.AsyncValueAnimator` | `com/android/quickstep/util/animation/AsyncValueAnimator.java:24-165`（Kotlin，`classes3.dex`）；cancel 路径 `:116-127`，`mIsEnd` 门控 `:57,61,69,80` |
| `async/AsyncAnimCallbacks.kt` | `com.android.quickstep.util.animation.AsyncAnimCallbacks` | `AsyncAnimCallbacks.java:23-172`；`getListeners()` 快照模式 `:29-32`，`removeListener` 置 null 槽 `:147-152`，`clearListeners` `:107-109`，`runOnMainThread` 走 `Utilities.postAsyncCallback` `:154-162` |
| `async/LooperExecutor.kt`（含 `postAsync`） | `com.oplus.basecommon.thread.LooperExecutor`（+ `OplusLooperExecutor`） | `com/oplus/basecommon/thread/LooperExecutor.java:12-80`；`shutdown()` 抛 `UnsupportedOperationException` `:71-73`；`OplusLooperExecutor.java:46-71` 的 `executeBlockWait`（v4 §9.3 点名的 ANR 风险） |
| `async/Executors.kt` | `com.oplus.basecommon.thread.Executors` + `OplusExecutors` | `Executors.java:54`（MAIN_EXECUTOR）、`:95-99`（createAndStartNewLooper）；`OplusExecutors.java:95`（ANIM_EXECUTOR static final）、`:169-171`（线程 init 回调） |
| `animthread/AnimationControlThread.kt` | **无同名类**；对应 `OplusExecutors.ANIM_EXECUTOR` 内联创建 + init lambda | `OplusExecutors.java:95`（`createAndStartNewLooper("launcher.anim", -19, …)`）、`:169-171`（`setProvider(SfVsyncFrameCallbackProvider)` + `setUxThreadValue`）；`Executors.java:94-99` |
| `core/anim/AnimationHandler.kt` | `androidx.core.animation.AnimationHandler`（vendored） + 框架 `android.animation.AnimationHandler`（@hide，import-only） | 详见 review 04 §1；框架版 `AnimationHandler.getInstance()` 为 ThreadLocal；vendored 版 `core/animation/AnimationHandler.java:13,158-168,204-210` |
| `controller/AnimationController.kt`（`reset()`） | `com.oplus.quickstep.utils.AnimationController` | `com/oplus/quickstep/utils/AnimationController.java:58`（extends DefaultAnimationController）、`reset` `:811-822`、`cleanUpRecentsAnim` `:733-755` |
| `controller/TaskStateChangeTimeOutListener.kt`（自管理超时） | `TaskStateHelper$TaskStateChangeTimeOutListener` | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:117-209`；构造 postDelayed `:130,136`、dispose removeCallbacks `:147-154`、全局 listener 注册 `:225-231` |
| `com/android/launcher3/LauncherAnimationRunner.kt`（类型壳） | `com.android.launcher3.LauncherAnimationRunner` | `com/android/launcher3/LauncherAnimationRunner.java`（600+ 行）；lib 仅 20 行类型壳，无 finish 三段式收尾 |
| `core/scheduler/HandlerTickScheduler.kt`（`stop()` / 懒清空） | 框架 `android.animation.AnimationHandler` + `FrameCallbackProvider16` | `MultiDynamicAnimation.java:127,185-191` 的 `addAnimationFrameCallback` / `requestEnd`；`com/android/quickstep/util/animation/MultiDynamicAnimation.java:92-101` 的 `endAnimationInternal`（`AnimationHandler.getInstance().removeCallback(this)`） |
| （lib 无对应） | `TaskStateHelper.removeAllListener()` | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:270-277`；**Activity onDestroy 集中清理的唯一显式钩子** |
| （lib 无对应） | `AnimationRecord.sAnimationId = -1` 复位 | `com/android/launcher/Launcher.java:3832`（onDestroy 时复位进程级 animationId 单调计数） |
| （lib 无对应） | `Launcher.onStop` 的多类资源回收 | `com/android/launcher/Launcher.java:4397-4460`；folder/stack `cancelRunningAnimations()`（`:4410, :4417`）、`RecentsViewAnimUtil.updateRecentsOrRemoteAnimationRunningFlags(3, false)`（`:4454`）、`AnimSeqTimeStamp.resetLastLaunchTaskTime()`（`:4457`） |

---

## ② 保真度评估

### A. 精确复刻

| # | 设计点 | 原厂证据 | lib 证据 |
|---|---|---|---|
| 1 | **ANIM_EXECUTOR 进程级单例、never-quit**：static final 在类加载时实例化 HandlerThread | `OplusExecutors.java:95`（`private static final OplusLooperExecutor ANIM_EXECUTOR = new OplusLooperExecutor(...)`）；`LooperExecutor.java:71-73` 的 `shutdown()` 抛 `UnsupportedOperationException`（API 形式契约） | `AnimationControlThread.kt:81-83`（`internal val instance: AnimationControlThread by lazy(LazyThreadSafetyMode.SYNCHRONIZED)`）+ `LooperExecutor.kt` 不实现 `ExecutorService`、无 `shutdown`——隐式契约（API 形式与原厂不一致，见 §C-1） |
| 2 | **`mIsEnd` AtomicBoolean 门控：cancel/start 遇 end 即丢弃，end 用 CAS 保证只发一次** | `AsyncValueAnimator.java:61,69,80` | `AsyncValueAnimator.kt:29,33,37` |
| 3 | **listener 懒删除**：remove 置 null 槽而非立即移除（`set(idx, null)`） | `AsyncAnimCallbacks.java:147-151` | `AsyncAnimCallbacks.kt:29-32`（`animListeners[idx] = null`） |
| 4 | **addListener 去重** | `AsyncAnimCallbacks.java:101-103` | `AsyncAnimCallbacks.kt:26` |
| 5 | **派发前把 `animationId` 同步进 `NullableAnimatorListenerAdapter`** | `AsyncAnimCallbacks.java:49,61,73` | `AsyncAnimCallbacks.kt:51` |
| 6 | **listener 始终回主线程 fire**（`MAIN_EXECUTOR` + isCurrentThread 内联） | `AsyncAnimCallbacks.java:154-162`（`runOnMainThread` → `Utilities.postAsyncCallback`） | `AsyncAnimCallbacks.kt:59-62, 74-79`（`runOnMainThread` → `exec.postAsync`，已对齐 async message 派发） |
| 7 | **`LooperExecutor.execute`：同 Looper 内联执行，否则 Handler.post** | `LooperExecutor.java:27-33` | `LooperExecutor.kt:30-33` |
| 8 | **线程名 `"launcher.anim"`** | `OplusExecutors.java:95` | `AnimationControlThread.kt:75`（`THREAD_NAME = "launcher.anim"`） |
| 9 | **线程 init 回调在 looper 准备好时设置帧源**（原厂换 `SfVsyncFrameCallbackProvider`，lib 装 `HandlerTickScheduler`） | `OplusExecutors.java:170`（`AnimationHandler.getInstance().setProvider(...)`） | `AnimationControlThread.kt:60-62`（`override fun onLooperPrepared()` → `AnimationHandler.installThreadScheduler(HandlerTickScheduler(...))`） |
| 10 | **`dispatch` 派发前** `getListeners()` 快照迭代（`removeNullEntries` + `toArray`） | `AsyncAnimCallbacks.java:29-32, 81-97` | `AsyncAnimCallbacks.kt:56-59`（`removeAll { it == null }` + `filterNotNull`） |
| 11 | **`TaskStateChangeTimeOutListener` 构造即 `postDelayed` 超时兜底、`dispose` 时 `removeCallbacks`** | `TaskStateHelper.java:134-137`（handler.postDelayed）、`:149-154`（handler.removeCallbacks + handler=null） | `TaskStateChangeTimeOutListener.kt:30-35`（init postDelayed）、`:37-39`（dispose removeCallbacks） |
| 12 | **`MultiDynamicAnimation.endAnimationInternal` 摘 AnimationHandler 回调** | `MultiDynamicAnimation.java:94-96`（`AnimationHandler.getInstance().removeCallback(this)`） | `AnimationHandler.kt:69-76`（`removeCallback` → 置 null 槽 + listDirty）+ `:104-108`（`cleanUpList` 压缩） |
| 13 | **`MultiDynamicAnimation` 帧末 stop-self**（回调列表空则自维持回路停） | v4 §3 路径 C：原厂框架版 AnimationHandler 通过 `onAnimationFrame` 的返回值（`return zArr[0]` = 是否还活着）让框架自停 | `HandlerTickScheduler.kt:74-78`（空则 `running = false`） |

### B. 有意简化（lib 注释/文档中明示）

| # | 简化内容 | 原厂对应 | lib 取舍理由 |
|---|---|---|---|
| 1 | `SfVsyncFrameCallbackProvider` → `HandlerTickScheduler`（Looper postDelayed 自走帧循环） | `OplusExecutors.java:169-171` | 框架 @hide API；review 01 §B-1 已声明 |
| 2 | `LauncherBooster.getCpu().setUxThreadValue(...)` UX 线程注册未移植 | `OplusExecutors.java:171` | OPPO 私有；退化为 `Process.setThreadPriority` 兜底（`AnimationControlThread.kt:65-66`） |
| 3 | `OplusLooperExecutor` 四扩展（`executeAtFront` / `executeWithUx` / `executeBlockWait` / `executeDelay`）未复刻 | `OplusLooperExecutor.java:38-103` | `executeBlockWait` 是 v4 §9.3 点名的主线程 5s 硬等 ANR 风险；其他三个依赖 LauncherBooster 私有 API；review 01 §B-4 |
| 4 | `Executors` 只保留 `MAIN_EXECUTOR` + `ANIM_CONTROL_EXECUTOR`；事务/加载/线程池/affinity 全砍 | `Executors.java:18-99`、UAF 绑核 2001/2003/2005/2007 | 与动画线程主题无关；review 01 §B-3 |
| 5 | `LauncherAnimationRunner` 砍成 `RemoteAnimationTarget` 类型壳 | `com/android/launcher3/LauncherAnimationRunner.java` 600+ 行 | review 03 §2.2；类型壳用于 `appLaunchAnimStartOrEnd` 签名 |
| 6 | `CustomRectFSpringAnim` 降级为句柄占位 | `com/android/quickstep/util/animation/CustomRectFSpringAnim.java` 907 行 + MultiDynamicAnimation/SpringHolder/SpringForce 整体 | review 04 §2.2-3 |
| 7 | `AnimationRecord`（animationId 单调计数、AnimationRecordViewInfo copy/equals）整套未移植 | `com/android/quickstep/util/animation/AnimationRecord.java:33,86-128` | 复用动画判定与 IconLayer 复用配套；本区域不展开 |
| 8 | **TaskStateChangeTimeOutListener 退化为"被动回调 fun interface"**：构造 postDelayed + dispose removeCallbacks 保留，但**未注册全局 listener**（无 `addGlobalTaskStateChangeListener`），无 ON_TRANSITION_FINISH / ON_TASK_LISTENER_RELEASED 事件分发 | `TaskStateHelper.java:117-209`（注册到 globalListeners 的 CopyOnWriteArrayList、`:225-231`；事件分发 `:177-204`） | review 03 §2.2-5；demo 场景无 system_server task 事件源 |

### C. 遗漏（未在 lib 注释中说明、且影响语义）

| # | 遗漏点 | 原厂证据 | 影响 |
|---|---|---|---|
| 1 | **`LooperExecutor` 不再 `extends AbstractExecutorService`，且无 `shutdown()` 契约** | `LooperExecutor.java:12`（`extends AbstractExecutorService`）、`:71-73`（`shutdown() throws UnsupportedOperationException`）、`:77-79`（`shutdownNow() throws UnsupportedOperationException`） | lib `LooperExecutor.kt` 不实现任何 `ExecutorService` 方法，因此 `OplusLooperExecutor` 的"永不 quit"语义被弱化为"未暴露 quit 入口"。demo 若按 AOSP 习惯调用 `ANIM_CONTROL_EXECUTOR.shutdown()`，将得到 `AbstractMethodError` 而非 `UnsupportedOperationException`——错误信息更难诊断，但实际效果相同（仍是永不 quit） |
| 2 | **`LooperExecutor` 缺 `getLooper()` / `getHandler()` / `getThread()` / `setThreadPriority()` 公开访问器** | `LooperExecutor.java:35-45, 65-67` | 原厂 `AsyncValueAnimator.cancel()` 用 `mAnimLooperExecutor.getLooper().isCurrentThread()`（`AsyncValueAnimator.java:117-127`），等价于"判线程 + post 纠偏"。lib 改为 `executor.isCurrentThread`（`AsyncValueAnimator.kt:43`），行为一致但**调用方若要从 executor 拿 Looper 或调 setThreadPriority 则无入口** |
| 3 | **`AsyncAnimCallbacks` listener 容器是 `ArrayList<NullableAnimatorListener>`，add/remove 走 synchronized / 强引用** | `AsyncAnimCallbacks.java:27`（`private final ArrayList<NullableAnimatorListener> mAnimListeners = new ArrayList<>();`） | lib `mutableListOf<NullableAnimatorListener?>()` 同等语义——业务 listener 强引用业务对象，**业务 listener 强引用 View 即漏 View**。原厂 v4 §9.5 指出 WeakReference/SoftReference 持有 factory 会造成动画瞬结、窗口跳变（`LauncherAnimationRunner.java:305,419-451`）；lib 与原厂**面临同一泄漏形态**——listener 自己泄漏 View |
| 4 | **`TaskStateChangeTimeOutListener` 的全局注册、事件驱动分发、超时-事件 OR 语义均缺失** | `TaskStateHelper.java:117-209`（extends `BaseTaskStateChangeListener` implements `TaskStateChangeListener`，由 `TaskStateHelper.addGlobalTaskStateChangeListener` 注册到 `globalListeners` CopyOnWriteArrayList `:31`）；事件通过 taskListener 的 binder 回调驱动；事件/超时任一先到都会 `dispose() + option.run()`（`:177-204`） | lib `TaskStateChangeTimeOutListener.kt` 只是被动回调类，构造 postDelayed + dispose removeCallbacks 语义保留，但**没人调 onTimeOut**（无 system_server task 事件源）——demo 演示的就是"超时兜底"，回调方主动触发（`DemoBaseActivity` 通过 `log("timeout")`），与原厂的"系统事件先到先发、超时兜底"是**字面同形不同语义**。Activity onDestroy 时 lib 侧**没有任何机制通知**这些 listener 提前 dispose（除 `delayStartActivityIfNeed` 收尾段 `:218-231` 的 dispose） |
| 5 | **`TaskStateHelper.removeAllListener()` 全局清理钩子缺失** | `TaskStateHelper.java:270-277`（遍历 globalListeners，对每个调 `onTaskListenerReleased()` + `globalListeners.clear()`）；由 `Launcher.onDestroy` 调（`Launcher.java:3846`） | lib 无对应集中清理。`AnimationController.reset()`（`:155-167`）只清 controller 自身字段，**不 dispose 三种 TaskStateChangeTimeOutListener**（仅 `delayStartActivityIfNeed` 收尾段 `:218-231` 在条件不命中时 dispose）。Demo 反复进出场次时 listener 残留，靠 GC 回收 |
| 6 | **`Launcher.onDestroy` 的多类清理缺失**（folder/stack `cancelRunningAnimations()`、`RecentsViewAnimUtil.updateRecentsOrRemoteAnimationRunningFlags`、`AnimSeqTimeStamp.resetLastLaunchTaskTime()`、`AnimationRecord.sAnimationId = -1`、`TaskStateHelper.removeAllListener()`） | `Launcher.java:3829-3847, 4397-4457` | lib `DemoBaseActivity` 无任何 `onDestroy`；demo 进出场次依赖 framework 默认行为（View detach + GC）。**demo 自身的 animator / listener 没有任何集中收尾路径**——`AsyncAnimCallbacks` 实例随 Activity 一起 GC，`AnimationControlThread.instance` 是进程级单例永远存在 |
| 7 | **`AnimationRecord.sAnimationId = -1` 复位缺失** | `Launcher.java:3832`（onDestroy 时复位进程级 animationId 单调计数）；`AnimationRecord.java:63-67`（sAnimationId 是 AtomicInteger，单调 ++） | lib 完全没复刻 `AnimationRecord`。demo 反复进出场次不会污染 animationId 计数（因为根本没这个机制），但同时**也失去了"复用上次动画的 IconLayer"语义**——属行为分歧但 demo 场景无关 |
| 8 | **`AsyncAnimCallbacks.clearListeners()` 公开但无调用方** | `AsyncAnimCallbacks.java:107-109`（`mAnimListeners.clear()`，公开方法） | lib `AsyncAnimCallbacks.kt:34` 同样提供 `clearListeners()` 内部方法——demo 无调用方，纯粹是公共 API 对齐 |
| 9 | **`TaskStateHelper.removeListenerAllOfList` / `removePendingLaunchCookieListener` 等局部摘除** | `TaskStateHelper.java:286-307` | lib 无对应机制；`TaskStateChangeTimeOutListener.dispose` 只摘自己的 callback |
| 10 | **`LooperExecutor.postDelayed`（非 postAsync）的全局可用入口缺失** | `LooperExecutor.java:61-63`（`postDelayed` 公开方法） | lib `LooperExecutor` 没暴露 `postDelayed`；仅 `execute / post / postAsync` 三个动词。`AnimationSeqHelper` 类比场景无延迟任务需求——属遗漏但 demo 不可见 |
| 11 | **`TaskStateChangeTimeOutListener.dispose` 不 dispose `option` 自身引用** | 原厂 `TaskStateHelper.java:147-154` 的 dispose 把 `handler = null` 但**未清 option 引用**——option 是 runnable 闭包可能持 View | lib `TaskStateChangeTimeOutListener.kt:37-39` 同样未清 option 引用——`option` 闭包被 class 字段持有直到 GC——与原厂一致 |
| 12 | **`MultiDynamicAnimation`/`CustomRectFSpringAnim` 摘回调的两段式时序**：cancel 是"置标志、下一帧生效"（`requestEnd(true)` → 下一次 `doAnimationFrame` 才 `endAnimationInternal`） | `MultiDynamicAnimation.java:185-191`（`requestEnd(true)`）；`:152-178`（`doAnimationFrame` 在帧末检查 `isEndRequest()` 才 `endAnimationInternal`） | lib `AnimationHandler.kt:69-76`（`removeCallback` 置 null 槽，下一次 `doAnimationFrame` 之前分发时会跳过；下一帧 `cleanUpList` 压缩）——**单回调摘除语义一致**，但**没有"cancel 是下一帧生效"的标注**。demo 期望"cancel 后立即不再 tick"在 lib 上可能多跑一帧 |

---

## ③ 行为差异风险点

按风险从高到低：

1. **【bug 级】ANIM_EXECUTOR never-quit 契约丢失（API 形式）**（C-1）。原厂 `LooperExecutor extends AbstractExecutorService` 且 `shutdown()` 抛 `UnsupportedOperationException`（`LooperExecutor.java:12,71-73`），调用方写 `ANIM_EXECUTOR.shutdown()` 会得到明确的"不支持"异常；lib 的 `LooperExecutor` 不实现任何 `ExecutorService` 方法，按 AOSP 习惯调用 `shutdown()`` 得到 `AbstractMethodError`——异常类型错误诊断更困难。实际行为都是"永不 quit"，但若未来 lib 增加 `shutdown()` 实现（且错误地让它实际生效），原厂的契约会抛异常挡掉，lib 则会**静默销毁 launcher.anim HandlerThread**，后续 `start()` 调用将 NPE。**建议：补 `shutdown()` 抛 `UnsupportedOperationException` + 标 `@Deprecated`，硬保契约**。

2. **【bug 级】`TaskStateChangeTimeOutListener` 是无主孤儿**：原厂该 listener 通过 `addGlobalTaskStateChangeListener` 注册到 `TaskStateHelper.globalListeners`（`CopyOnWriteArrayList`，`:31`），由 system_server 任务事件回调驱动 `onTimeOut`，并由 `Launcher.onDestroy → TaskStateHelper.removeAllListener()` 集中 dispose。lib 的 listener 是裸 `class`，构造 postDelayed + dispose 配对但**无人调用构造**——demo 演示的全是手工 `registerSpecialSceneExitTimeOutListener(1500L)` + 手工 `.onTimeOut(...)`。原厂的语义是"事件/超时 OR 触发，Activity onDestroy 兜底 dispose"；lib 是"构造即挂超时、谁 dispose 谁负责"。**两种语义的鸿沟在于"事件先到先发"——lib 完全没有事件源**，demo 演示价值有限。

3. **【高】`TaskStateHelper.removeAllListener()` 集中清理缺失**（C-5）。原厂 `Launcher.onDestroy()` 第 3846 行显式调 `TaskStateHelper.removeAllListener()`：遍历全局监听器，逐个调 `onTaskListenerReleased()`（`TaskStateHelper.java:270-277`）——`TaskStateChangeTimeOutListener.onTaskListenerReleased()` 内部 dispose + 移除全局注册 + removeCallbacks（`:186-193`）。这一行保证进程级所有待清理的 listener 在 Activity 销毁时被强摘。lib `DemoBaseActivity` 不重写 `onDestroy`，`AnimationController` 也不实现集中清理——demo 进出场次时 listener 残留，靠 GC 回收（GC 时机不可预测，且 callback 持有的 Handler 引用链不破坏之前不会 GC `TaskStateChangeTimeOutListener` 本身）。**demo 测试反复进出同一 Activity 时，超时兜底可能被延迟触发**（不影响功能正确性，但断言"超时即触发"会偶发失败）。

4. **【高】`Launcher.onStop` / `onDestroy` 多类动画 cancel 缺失**（C-6）。原厂在 `onStop` 里 folder/stack `cancelRunningAnimations()`（`Launcher.java:4410, :4417`），`onDestroy` 里复位 `AnimationRecord.sAnimationId = -1`、调 `removeAllListener`、`RecentsViewAnimUtil.updateRecentsOrRemoteAnimationRunningFlags(3, false)`（`:4454`）、`AnimSeqTimeStamp.resetLastLaunchTaskTime()`（`:4457`）。lib 无对应路径——demo 退场时动画继续 tick 直到 `cancel` 显式调用或自然结束。**演示场景下若 `AsyncValueAnimator.start()` 后用户立即退出 Activity，未 cancel 的 animator 仍在 ANIM 线程跑直到 end**，本身无害（ANIM_EXECUTOR 永远活），但若业务 listener 持有 Activity View，会形成 1~2 帧的"已 detach View 仍收到回调"的幽灵引用窗口——**这是 View 泄漏的间接路径**。

5. **【中】listener 强引用 View 的泄漏形态未变**（C-3）。原厂 AsyncAnimCallbacks 用 `ArrayList` 强持 `NullableAnimatorListener`，lib 用 `mutableListOf`——本质相同。`AsyncAnimCallbacks.clearListeners` 是公开 API（`AsyncAnimCallbacks.java:107-109`、lib `AsyncAnimCallbacks.kt:34`），但**调用方** `IconLayerUpdater` / `CustomRectFSpringAnim` 都没有显式调它。动画自然结束后 listener 不被清——`ArrayList` 里持续持有（null 槽会增长；lib 的 `removeAll { it == null }` 在 `getListeners` 调用时才压缩，原厂 `removeNullEntries` 同样惰性压缩）。**长生命周期进程 + 短生命周期 listener**的场景下，list 大小线性增长（最坏 O(n)，n = 历次 addListener 累计）。这点上原厂与 lib 都一样，**这是个**：

   - 原厂已知设计取舍：v4 §9.5 提到 WeakReference 持有 factory 会造成动画瞬结、窗口跳变（`LauncherAnimationRunner.java:305,419-451`），是 OPPO 自己在线上踩过的坑。所以**保留 null 槽 + 快照派发是正确的**，但调用方需要在合理时机 `clearListeners`——这点两边都缺失。

6. **【中】cancel 是"下一帧生效"的语义缺失标注**（C-12）。`MultiDynamicAnimation.requestEnd(true)` 是置 `mCancelRequest` 标志，下一次 `doAnimationFrame` 帧末才 `endAnimationInternal`（`MultiDynamicAnimation.java:168-176`）。`endAnimationInternal` 摘 AnimationHandler 回调（`:95`）、跑 `mEndListeners` 派发（`:97-101`）。lib 的 `AnimationHandler.removeCallback` 是置 null 槽 + listDirty=true，**派发循环下一次 doAnimationFrame 时跳过 null**（`AnimationHandler.kt:94-101`），帧末 `cleanUpList` 压缩（`:104-108`）——单回调摘除语义一致。但**原厂多帧时序**（cancel → 帧末 → end 派发 → AsyncAnimCallbacks 走 marshal → 主线程回调 → 业务继续）在 lib 上**单帧即生效**（cancel → listDirty → 下一帧不再分发 → 下一帧清理），回调时机更激进。**业务如果在 cancel 后立刻假设"已结束"做副作用**，lib 比原厂更早触发；反之若业务等下一帧验证 "end 已发"，lib 比原厂更晚可见。**两个方向都潜在偏差，需逐用例确认**。

7. **【低】`AnimationHandler` ThreadLocal 的隐式生命周期**：原厂框架版 `AnimationHandler.getInstance()` 是 ThreadLocal，由线程 exit 时 ThreadLocalMap 随 Thread 实例回收。launcher.anim 永不 quit，**该 ThreadLocal 永不清理**。`setProvider(SfVsyncFrameCallbackProvider)` 只在 `ANIM_EXECUTOR$lambda$0()` 调一次（`OplusExecutors.java:170`），线程整个生命周期都是 SF provider——**没有 `resetProvider` 路径**。lib `AnimationHandler.installThreadScheduler`（`AnimationHandler.kt:151-157`）也只装不拆，`replaceThreadScheduler` 提供运行时换 scheduler（`:168-172`）。**两端都没有"清理"语义**——这是设计正确（进程级单例线程），但若 lib 在测试场景用 `ScheduledTickScheduler` 后调 `AnimationControlThread.instance.quitSafely()`，会**泄漏 ThreadLocal**。`HandlerThread.quitSafely` 是公开 API；lib 没暴露这个入口。**demo 安全，但调用方要主动意识到"线程一旦启动永不退出"**。

8. **【低】`AnimationRecord` 缺失导致"复用上次动画 IconLayer"语义丢失**（C-7）。原厂 `AnimationRecord` 提供 `sAnimationId` 单调计数 + `tryConnectExistingAnim`（`AnimationRecord.java:405-431`）+ `canReuseIconLayer`（`:178`），用于快速切换应用时复用上次动画的 icon leash。**demo 场景不涉及该机制，无功能影响**——属行为分歧的"场景外"项。

9. **【低】`TaskStateChangeTimeOutListener.option` 引用未在 dispose 时清零**（C-11）。原厂与 lib 都是 dispose 时只 `handler = null`，option 引用保持到 GC。**短生命周期 option**（如 lambda 捕获 Activity this）会延迟 Activity GC。**原厂通过 `TaskStateHelper.removeAllListener()` 在 onDestroy 集中调 `dispose()` 缓解**——这恰好是 C-5 的另一面。

10. **【低】`MultiDynamicAnimation` 框架版 `endAnimationInternal` 只摘回调不清 listeners**。原厂 `MultiDynamicAnimation.java:97-101` 的 `mEndListeners` 不在 `endAnimationInternal` 内 clear；lib 无对应实现（因未移植 MultiDynamicAnimation）。如未来回移 MultiDynamicAnimation 链路，需在 endAnimationInternal 末尾加 `mEndListeners.clear() + mUpdateListeners.clear()` 防泄漏。

---

## ④ 回移建议

### 4.1 值得补进 lib 的

1. **【必补】`AnimationController` 加 `destroy()/release()` 集中清理方法**：遍历三种 `TaskStateChangeTimeOutListener` 全部 `dispose()` + 清 `animStateChangeListeners` + `reset()`。与原厂 `Launcher.onDestroy → TaskStateHelper.removeAllListener()` 的语义对齐，是 demo 反复进出场次"干净退出"的最低保障。对应 C-5，约 15 行。
2. **【必补】`LooperExecutor` 加 `shutdown() throws UnsupportedOperationException`**（+ `@Deprecated`）：硬保 ANIM_EXECUTOR never-quit 契约，调用方按 AOSP 习惯写 `shutdown()` 时得到明确异常而非 `AbstractMethodError`。对应 C-1，2 行。
3. **【必补】`DemoBaseActivity` 重写 `onDestroy()`**：调 `AnimationController.reset()` + 显式 cancel 所有 AsyncValueAnimator + `AsyncAnimCallbacks.clearListeners()`。对应 C-6 / C-4，~10 行。这是 demo "Activity 销毁安全"的可观测证据，不补则 lib 的"安全 cancel 所有动画"宣传无 demo 验证。
4. **【建议补】`AnimationControlThread` 暴露 `quitSafely()` / `quit()`**：明示"进程级单例，永不 quit"是设计而非疏忽。给调用方一个明确的语义锚点，避免误用。对应 §3-7，约 3 行。
5. **【建议补】`AsyncAnimCallbacks` 加 `dispose()` 复合方法** = `clearListeners() + animationId = -1`：让业务在"逻辑结束 + 物理结束"双轨时统一摘除 listener 容器，避免 ArrayList 长期增长。对应 §3-5，约 3 行。
6. **【建议补】`TaskStateChangeTimeOutListener.dispose()` 时把 `option` / `type` 也置 null**：缩窄引用窗口，让 option 闭包持有的 View/Activity 更早可 GC。原厂未做（`TaskStateHelper.java:147-154`），lib 是补强的好机会。对应 C-11，2 行。
7. **【可选】文档补一节「Activity 生命周期契约」**：明示 demo 必须重写 `onDestroy` 才能正确收尾，否则 GC 兜底；明示 `AnimationControlThread` 永不 quit；明示 `ANIM_EXECUTOR` 的 shutdown() 抛 UnsupportedOperationException。文档化的契约比代码契约更稳。
8. **【可选】`AsyncValueAnimator` 暴露 `dispose()`**：一次性 cancel + clearListeners + 切断 executor 引用——给"用完即弃"的 animator 一个明确结束点，避免依赖 ValueAnimator 自身的 GC。对应 §3-4，~5 行。

### 4.2 建议保持简化

1. **`TaskStateHelper.globalListeners` CopyOnWriteArrayList + binder 任务事件分发**：与 system_server 的 `addTaskListener` 强绑定（`TaskStateHelper.java:255`），demo 无对应事件源；保留"超时兜底 + 手工 dispose"语义足够演示价值，对应 §B-8。
2. **`AnimationRecord` 整套（animationId 单调计数、AnimationRecordViewInfo、IconLayer 复用）**：与"独立动画线程"演示主题正交，且依赖 `IconLayerHolder` / `IconSurfaceManager` 等大量 launcher 内部类型，硬塞会把 demo 拖入 launcher 业务建模。对应 §C-7。
3. **`Launcher.onStop` 的 folder/stack `cancelRunningAnimations`、`RecentsViewAnimUtil.updateRecentsOrRemoteAnimationRunningFlags`、`AnimSeqTimeStamp.resetLastLaunchTaskTime`**：都是 launcher 业务专用回调，lib 不引入这些类即用不上。
4. **`LooperExecutor.getLooper / getHandler / getThread / setThreadPriority`**：原厂用这些方法从 executor 拿到 Handler 来做 postDelayed / setThreadPriority——lib 没有调用方需要这些访问器，postAsync 已经走 handler。对应 C-2。
5. **`OplusLooperExecutor.executeBlockWait`**：v4 §9.3 点名的主线程 5s 硬等 ANR 风险，属"原厂自己也不该这么写"。**不要回移**。
6. **MultiDynamicAnimation 的 `mEndListeners` / `mUpdateListeners` 在 end 后 clear**：等真正回移 MultiDynamicAnimation 链路时再补，目前 lib 无对应实现。
7. **`AsyncAnimCallbacks.clearListeners` 增加"压缩 null 槽"语义**：当前 `getListeners()` 已在派发前压缩（`AsyncAnimCallbacks.kt:56-59`），重复优化收益小。

---

## 附：关键证据速查

| 论断 | 证据 |
|---|---|
| ANIM_EXECUTOR 进程级单例 | `com/oplus/basecommon/thread/OplusExecutors.java:95`（`private static final OplusLooperExecutor ANIM_EXECUTOR = new OplusLooperExecutor(...)`） |
| `LooperExecutor.shutdown()` 抛 UnsupportedOperationException | `com/oplus/basecommon/thread/LooperExecutor.java:71-73` |
| `TaskStateChangeTimeOutListener` 构造 postDelayed + dispose removeCallbacks | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:124-138, 147-154` |
| 全局监听器注册到 `globalListeners` CopyOnWriteArrayList | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:31, 223-231` |
| `removeAllListener` 集中清理 | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:270-277`；由 `Launcher.onDestroy` 调（`com/android/launcher/Launcher.java:3846`） |
| `AnimationRecord.sAnimationId = -1` 复位 | `com/android/launcher/Launcher.java:3832`（onDestroy） |
| `Launcher.onStop` 多类 cancel | `com/android/launcher/Launcher.java:4407-4420, 4454-4457` |
| `AsyncValueAnimator` cancel 跨线程纠偏 + `mIsEnd` CAS | `com/android/quickstep/util/animation/AsyncValueAnimator.java:116-127, 57-85` |
| `AsyncAnimCallbacks` 快照派发 + null 槽压缩 + async 消息 | `com/android/quickstep/util/animation/AsyncAnimCallbacks.java:29-32, 81-97, 107-109, 154-162` |
| `Utilities.postAsyncCallback` 设置 `setAsynchronous(true)` | `com/android/launcher3/Utilities.java:631-637` |
| `MultiDynamicAnimation.requestEnd` 下一帧生效 + `endAnimationInternal` 摘 AnimationHandler | `com/android/quickstep/util/animation/MultiDynamicAnimation.java:94-101, 152-178, 185-191` |
| `CustomRectFSpringAnim.cancel` → `maybeEnd` → onAnimationCancel + onAnimationEnd + onAnimActualEnd | `com/android/quickstep/util/animation/CustomRectFSpringAnim.java:608-628, 423-441` |
| `AnimationSeqHelper.delayFinishRecents` 500ms postDelayed + `clearFinishRecentsRunnable` | `com/oplus/quickstep/utils/AnimationSeqHelper.java:26, 79-99` |
| `LooperExecutor.shutdown()` 不支持语义 | `com/oplus/basecommon/thread/LooperExecutor.java:71-79` |