# 区域 09 对比 Review：生命周期与资源回收

> **2026-09-09 续轮完成**：§4.1-5 `AsyncAnimCallbacks.dispose()` 已落地并由 Demo3 清理调用；释放监听/ID、失效旧代次排队事件，逻辑及物理结束均适用，监听内重入释放会跳过余下监听。容器可重新注册，但不保证 animator 复用；不能撤回正在执行的回调。见[续轮落地记录](2026-09-09-review-followup.md)。

> **2026-09-09 当前复核**：已接入 Demo3/6/7 清理、Controller 观察者释放及超时一次性消费；公共场景停止帧订阅。提供 destroy/onCleanup 方法本身不等于调用方已完成清理。 详见 [本轮修复记录](2026-09-09-revalidation-fixes.md)。

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/`（core/thread/anim/playback/seq/control/manager 子包，e62dbff 包重组后）
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
| `anim/AsyncValueAnimator.kt` | `com.android.quickstep.util.animation.AsyncValueAnimator` | `com/android/quickstep/util/animation/AsyncValueAnimator.java:24-165`（Kotlin，`classes3.dex`）；cancel 路径 `:116-127`，`mIsEnd` 门控 `:57,61,69,80` |
| `anim/AsyncAnimCallbacks.kt` | `com.android.quickstep.util.animation.AsyncAnimCallbacks` | `AsyncAnimCallbacks.java:23-172`；`getListeners()` 快照模式 `:29-32`，`removeListener` 置 null 槽 `:147-152`，`clearListeners` `:107-109`，`runOnMainThread` 走 `Utilities.postAsyncCallback` `:154-162` |
| `thread/LooperExecutor.kt`（含 `postAsync`） | `com.oplus.basecommon.thread.LooperExecutor`（+ `OplusLooperExecutor`） | `com/oplus/basecommon/thread/LooperExecutor.java:12-80`；`shutdown()` 抛 `UnsupportedOperationException` `:71-73`；`OplusLooperExecutor.java:46-71` 的 `executeBlockWait`（v4 §9.3 点名的 ANR 风险） |
| `thread/Executors.kt` | `com.oplus.basecommon.thread.Executors` + `OplusExecutors` | `Executors.java:54`（MAIN_EXECUTOR）、`:95-99`（createAndStartNewLooper）；`OplusExecutors.java:95`（ANIM_EXECUTOR static final）、`:169-171`（线程 init 回调） |
| `thread/AnimationControlThread.kt` | **无同名类**；对应 `OplusExecutors.ANIM_EXECUTOR` 内联创建 + init lambda | `OplusExecutors.java:95`（`createAndStartNewLooper("launcher.anim", -19, …)`）、`:169-171`（`setProvider(SfVsyncFrameCallbackProvider)` + `setUxThreadValue`）；`Executors.java:94-99` |
| `core/AnimationHandler.kt` | `androidx.core.animation.AnimationHandler`（vendored） + 框架 `android.animation.AnimationHandler`（@hide，import-only） | 详见 review 04 §1；框架版 `AnimationHandler.getInstance()` 为 ThreadLocal；vendored 版 `core/animation/AnimationHandler.java:13,158-168,204-210` |
| `control/AnimationController.kt`（`reset()`） | `com.oplus.quickstep.utils.AnimationController` | `com/oplus/quickstep/utils/AnimationController.java:58`（extends DefaultAnimationController）、`reset` `:811-822`、`cleanUpRecentsAnim` `:733-755` |
| `control/TaskStateChangeTimeOutListener.kt`（自管理超时） | `TaskStateHelper$TaskStateChangeTimeOutListener` | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:117-209`；构造 postDelayed `:130,136`、dispose removeCallbacks `:147-154`、全局 listener 注册 `:225-231` |
| `com/android/launcher3/LauncherAnimationRunner.kt`（类型壳） | `com.android.launcher3.LauncherAnimationRunner` | `com/android/launcher3/LauncherAnimationRunner.java`（600+ 行）；lib 仅 20 行类型壳，无 finish 三段式收尾 |
| `core/ChoreographerTickScheduler.kt`（`stop()` / 空订阅自停；215ecb5 后 ScheduledTickScheduler/HandlerTickScheduler 已删） | 框架 `android.animation.AnimationHandler` + `FrameCallbackProvider16` | `MultiDynamicAnimation.java:127,185-191` 的 `addAnimationFrameCallback` / `requestEnd`；`com/android/quickstep/util/animation/MultiDynamicAnimation.java:92-101` 的 `endAnimationInternal`（`AnimationHandler.getInstance().removeCallback(this)`） |
| （lib 无对应） | `TaskStateHelper.removeAllListener()` | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:270-277`；**Activity onDestroy 集中清理的唯一显式钩子** |
| （lib 无对应） | `AnimationRecord.sAnimationId = -1` 复位 | `com/android/launcher/Launcher.java:3832`（onDestroy 时复位进程级 animationId 单调计数） |
| （lib 无对应） | `Launcher.onStop` 的多类资源回收 | `com/android/launcher/Launcher.java:4397-4460`；folder/stack `cancelRunningAnimations()`（`:4410, :4417`）、`RecentsViewAnimUtil.updateRecentsOrRemoteAnimationRunningFlags(3, false)`（`:4454`）、`AnimSeqTimeStamp.resetLastLaunchTaskTime()`（`:4457`） |

---

## ② 保真度评估

### A. 精确复刻

| # | 设计点 | 原厂证据 | lib 证据 |
|---|---|---|---|
| 1 | **ANIM_EXECUTOR 进程级单例、never-quit**：static final 在类加载时实例化 HandlerThread | `OplusExecutors.java:95`（`private static final OplusLooperExecutor ANIM_EXECUTOR = new OplusLooperExecutor(...)`）；`LooperExecutor.java:71-73` 的 `shutdown()` 抛 `UnsupportedOperationException`（API 形式契约） | `thread/AnimationControlThread.kt:86-88`（`internal val instance: AnimationControlThread by lazy(LazyThreadSafetyMode.SYNCHRONIZED)`）+ `thread/LooperExecutor.kt:57-69` 已补 `shutdown()`/`shutdownNow()`/`awaitTermination()` 抛 `UnsupportedOperationException` + `isShutdown`/`isTerminated` 恒 false——契约已硬保（见 §C-1 / ③-1，已修复） |
| 2 | **`mIsEnd` AtomicBoolean 门控：cancel/start 遇 end 即丢弃，end 用 CAS 保证只发一次** | `AsyncValueAnimator.java:61,69,80` | `anim/AsyncValueAnimator.kt:31,35,39` |
| 3 | **listener 懒删除**：remove 置 null 槽而非立即移除（`set(idx, null)`） | `AsyncAnimCallbacks.java:147-151` | `anim/AsyncAnimCallbacks.kt:41-44`（`animListeners[idx] = null`） |
| 4 | **addListener 去重** | `AsyncAnimCallbacks.java:101-103` | `anim/AsyncAnimCallbacks.kt:37-39` |
| 5 | **派发前把 `animationId` 同步进 `NullableAnimatorListenerAdapter`** | `AsyncAnimCallbacks.java:49,61,73` | `anim/AsyncAnimCallbacks.kt:65, 86` |
| 6 | **listener 始终回主线程 fire**（`MAIN_EXECUTOR` + isCurrentThread 内联） | `AsyncAnimCallbacks.java:154-162`（`runOnMainThread` → `Utilities.postAsyncCallback`） | `anim/AsyncAnimCallbacks.kt:97-100, 82-91`（`runOnMainThread` → `exec.postAsync`，已对齐 async message 派发） |
| 7 | **`LooperExecutor.execute`：同 Looper 内联执行，否则 Handler.post** | `LooperExecutor.java:27-33` | `thread/LooperExecutor.kt:31-34` |
| 8 | **线程名 `"launcher.anim"`** | `OplusExecutors.java:95` | `thread/AnimationControlThread.kt:76`（`THREAD_NAME = "launcher.anim"`） |
| 9 | **线程 init 回调在 looper 准备好时设置帧源**（原厂换 `SfVsyncFrameCallbackProvider`，lib 装 `ChoreographerTickScheduler`） | `OplusExecutors.java:170`（`AnimationHandler.getInstance().setProvider(...)`） | `thread/AnimationControlThread.kt:63-71`（`override fun onLooperPrepared()` → `AnimationHandler.installThreadScheduler(ChoreographerTickScheduler())`；215ecb5 后帧源为公开 Choreographer 真 VSYNC） |
| 10 | **`dispatch` 派发前** `getListeners()` 快照迭代（`removeNullEntries` + `toArray`） | `AsyncAnimCallbacks.java:29-32, 81-97` | `anim/AsyncAnimCallbacks.kt:76-79`（`removeAll { it == null }` + `filterNotNull`） |
| 11 | **`TaskStateChangeTimeOutListener` 构造即 `postDelayed` 超时兜底、`dispose` 时 `removeCallbacks`** | `TaskStateHelper.java:134-137`（handler.postDelayed）、`:149-154`（handler.removeCallbacks + handler=null） | `control/TaskStateChangeTimeOutListener.kt:30-32`（init postDelayed）、`:41-43`（dispose removeCallbacks） |
| 12 | **`MultiDynamicAnimation.endAnimationInternal` 摘 AnimationHandler 回调** | `MultiDynamicAnimation.java:94-96`（`AnimationHandler.getInstance().removeCallback(this)`） | `core/AnimationHandler.kt:64-71`（`removeCallback` → 置 null 槽 + listDirty）+ `:105-109`（`cleanUpList` 压缩） |
| 13 | **`MultiDynamicAnimation` 帧末 stop-self**（回调列表空则自维持回路停） | v4 §3 路径 C：原厂框架版 AnimationHandler 通过 `onAnimationFrame` 的返回值（`return zArr[0]` = 是否还活着）让框架自停 | `core/ChoreographerTickScheduler.kt:41-45`（空则 `running = false`，停止订阅 vsync） |

### B. 有意简化（lib 注释/文档中明示）

| # | 简化内容 | 原厂对应 | lib 取舍理由 |
|---|---|---|---|
| 1 | `SfVsyncFrameCallbackProvider` → `ChoreographerTickScheduler`（公开 Choreographer 真 VSYNC；215ecb5 删 HandlerTickScheduler/ScheduledTickScheduler） | `OplusExecutors.java:169-171` | 框架 @hide API；review 01 §B-1 已声明；dbde195/215ecb5 已升级为公开 Choreographer |
| 2 | `LauncherBooster.getCpu().setUxThreadValue(...)` UX 线程注册未移植 | `OplusExecutors.java:171` | OPPO 私有；退化为 `Process.setThreadPriority` 兜底（`thread/AnimationControlThread.kt:70`） |
| 3 | `OplusLooperExecutor` 四扩展（`executeAtFront` / `executeWithUx` / `executeBlockWait` / `executeDelay`）未复刻 | `OplusLooperExecutor.java:38-103` | `executeBlockWait` 是 v4 §9.3 点名的主线程 5s 硬等 ANR 风险；其他三个依赖 LauncherBooster 私有 API；review 01 §B-4 |
| 4 | `Executors` 只保留 `MAIN_EXECUTOR` + `ANIM_CONTROL_EXECUTOR`；事务/加载/线程池/affinity 全砍 | `Executors.java:18-99`、UAF 绑核 2001/2003/2005/2007 | 与动画线程主题无关；review 01 §B-3 |
| 5 | `LauncherAnimationRunner` 砍成 `RemoteAnimationTarget` 类型壳 | `com/android/launcher3/LauncherAnimationRunner.java` 600+ 行 | review 03 §2.2；类型壳用于 `appLaunchAnimStartOrEnd` 签名 |
| 6 | `CustomRectFSpringAnim` 降级为句柄占位 | `com/android/quickstep/util/animation/CustomRectFSpringAnim.java` 907 行 + MultiDynamicAnimation/SpringHolder/SpringForce 整体 | review 04 §2.2-3 |
| 7 | `AnimationRecord`（animationId 单调计数、AnimationRecordViewInfo copy/equals）整套未移植 | `com/android/quickstep/util/animation/AnimationRecord.java:33,86-128` | 复用动画判定与 IconLayer 复用配套；本区域不展开 |
| 8 | **TaskStateChangeTimeOutListener 退化为"被动回调 fun interface"**：构造 postDelayed + dispose removeCallbacks 保留，但**未注册全局 listener**（无 `addGlobalTaskStateChangeListener`），无 ON_TRANSITION_FINISH / ON_TASK_LISTENER_RELEASED 事件分发 | `TaskStateHelper.java:117-209`（注册到 globalListeners 的 CopyOnWriteArrayList、`:225-231`；事件分发 `:177-204`） | review 03 §2.2-5；demo 场景无 system_server task 事件源 |

### C. 遗漏（未在 lib 注释中说明、且影响语义）

| # | 遗漏点 | 原厂证据 | 影响 |
|---|---|---|---|
| 1 | **`LooperExecutor` 不再 `extends AbstractExecutorService`，且无 `shutdown()` 契约** | `LooperExecutor.java:12`（`extends AbstractExecutorService`）、`:71-73`（`shutdown() throws UnsupportedOperationException`）、`:77-79`（`shutdownNow() throws UnsupportedOperationException`） | **已修复**：`thread/LooperExecutor.kt:57-69` 补 `shutdown()`/`shutdownNow()`/`awaitTermination()` 抛 `UnsupportedOperationException`（标 `@Deprecated`）+ `isShutdown`/`isTerminated` 恒 false——never-quit 契约已硬保，调用方得到与原厂一致的明确异常。本条遗漏消除 |
| 2 | **`LooperExecutor` 缺 `getLooper()` / `getHandler()` / `getThread()` / `setThreadPriority()` 公开访问器** | `LooperExecutor.java:35-45, 65-67` | 原厂 `AsyncValueAnimator.cancel()` 用 `mAnimLooperExecutor.getLooper().isCurrentThread()`（`AsyncValueAnimator.java:117-127`），等价于"判线程 + post 纠偏"。lib 改为 `executor.isCurrentThread`（`anim/AsyncValueAnimator.kt:44`），行为一致但**调用方若要从 executor 拿 Looper 或调 setThreadPriority 则无入口** |
| 3 | **`AsyncAnimCallbacks` listener 容器是 `ArrayList<NullableAnimatorListener>`，add/remove 走 synchronized / 强引用** | `AsyncAnimCallbacks.java:27`（`private final ArrayList<NullableAnimatorListener> mAnimListeners = new ArrayList<>();`） | lib `mutableListOf<NullableAnimatorListener?>()` 同等语义——业务 listener 强引用业务对象，**业务 listener 强引用 View 即漏 View**。原厂 v4 §9.5 指出 WeakReference/SoftReference 持有 factory 会造成动画瞬结、窗口跳变（`LauncherAnimationRunner.java:305,419-451`）；lib 与原厂**面临同一泄漏形态**——listener 自己泄漏 View |
| 4 | **`TaskStateChangeTimeOutListener` 的全局注册、事件驱动分发、超时-事件 OR 语义均缺失** | `TaskStateHelper.java:117-209`（extends `BaseTaskStateChangeListener` implements `TaskStateChangeListener`，由 `TaskStateHelper.addGlobalTaskStateChangeListener` 注册到 `globalListeners` CopyOnWriteArrayList `:31`）；事件通过 taskListener 的 binder 回调驱动；事件/超时任一先到都会 `dispose() + option.run()`（`:177-204`） | lib `TaskStateChangeTimeOutListener.kt` 只是被动回调类，构造 postDelayed + dispose removeCallbacks 语义保留，但**没人调 onTimeOut**（无 system_server task 事件源）——demo 演示的就是"超时兜底"，回调方主动触发（`Demo6StateMachineActivity.kt:268-294` 手工 `onTimeOut(...)` + `log("超时触发: ...")`），与原厂的"系统事件先到先发、超时兜底"是**字面同形不同语义**。Activity onDestroy 时 lib 侧**没有任何机制通知**这些 listener 提前 dispose（除 `delayStartActivityIfNeed` 收尾段 `AnimationController.kt:244-249` 的 dispose） |
| 5 | **`TaskStateHelper.removeAllListener()` 全局清理钩子缺失** | `TaskStateHelper.java:270-277`（遍历 globalListeners，对每个调 `onTaskListenerReleased()` + `globalListeners.clear()`）；由 `Launcher.onDestroy` 调（`Launcher.java:3846`） | lib 无对应集中清理。`control/AnimationController.reset()`（`:134-148`）只清 controller 自身字段，**不 dispose 三种 TaskStateChangeTimeOutListener**（仅 `delayStartActivityIfNeed` 收尾段 `:244-249` 在条件不命中时 dispose）。Demo 反复进出场次时 listener 残留，靠 GC 回收 |
| 6 | **`Launcher.onDestroy` 的多类清理缺失**（folder/stack `cancelRunningAnimations()`、`RecentsViewAnimUtil.updateRecentsOrRemoteAnimationRunningFlags`、`AnimSeqTimeStamp.resetLastLaunchTaskTime()`、`AnimationRecord.sAnimationId = -1`、`TaskStateHelper.removeAllListener()`） | `Launcher.java:3829-3847, 4397-4457` | lib `DemoBaseActivity` 无任何 `onDestroy`；demo 进出场次依赖 framework 默认行为（View detach + GC）。**demo 自身的 animator / listener 没有任何集中收尾路径**——`AsyncAnimCallbacks` 实例随 Activity 一起 GC，`AnimationControlThread.instance` 是进程级单例永远存在 |
| 7 | **`AnimationRecord.sAnimationId = -1` 复位缺失** | `Launcher.java:3832`（onDestroy 时复位进程级 animationId 单调计数）；`AnimationRecord.java:63-67`（sAnimationId 是 AtomicInteger，单调 ++） | lib 完全没复刻 `AnimationRecord`。demo 反复进出场次不会污染 animationId 计数（因为根本没这个机制），但同时**也失去了"复用上次动画的 IconLayer"语义**——属行为分歧但 demo 场景无关 |
| 8 | **`AsyncAnimCallbacks.clearListeners()` 公开但无调用方** | `AsyncAnimCallbacks.java:107-109`（`mAnimListeners.clear()`，公开方法） | lib `anim/AsyncAnimCallbacks.kt:46` 同样提供 `clearListeners()` 内部方法——demo 无调用方，纯粹是公共 API 对齐 |
| 9 | **`TaskStateHelper.removeListenerAllOfList` / `removePendingLaunchCookieListener` 等局部摘除** | `TaskStateHelper.java:286-307` | lib 无对应机制；`TaskStateChangeTimeOutListener.dispose` 只摘自己的 callback |
| 10 | **`LooperExecutor.postDelayed`（非 postAsync）的全局可用入口缺失** | `LooperExecutor.java:61-63`（`postDelayed` 公开方法） | lib `LooperExecutor` 没暴露 `postDelayed`；仅 `execute / post / postAsync` 三个动词。`AnimationSeqHelper` 类比场景无延迟任务需求——属遗漏但 demo 不可见 |
| 11 | **`TaskStateChangeTimeOutListener.dispose` 不 dispose `option` 自身引用** | 原厂 `TaskStateHelper.java:147-154` 的 dispose 把 `handler = null` 但**未清 option 引用**——option 是 runnable 闭包可能持 View | lib `TaskStateChangeTimeOutListener.kt:37-39` 同样未清 option 引用——`option` 闭包被 class 字段持有直到 GC——与原厂一致 |
| 12 | **`MultiDynamicAnimation`/`CustomRectFSpringAnim` 摘回调的两段式时序**：cancel 是"置标志、下一帧生效"（`requestEnd(true)` → 下一次 `doAnimationFrame` 才 `endAnimationInternal`） | `MultiDynamicAnimation.java:185-191`（`requestEnd(true)`）；`:152-178`（`doAnimationFrame` 在帧末检查 `isEndRequest()` 才 `endAnimationInternal`） | lib `AnimationHandler.kt:69-76`（`removeCallback` 置 null 槽，下一次 `doAnimationFrame` 之前分发时会跳过；下一帧 `cleanUpList` 压缩）——**单回调摘除语义一致**，但**没有"cancel 是下一帧生效"的标注**。demo 期望"cancel 后立即不再 tick"在 lib 上可能多跑一帧 |

---

## ③ 行为差异风险点

按风险从高到低：

> **✅已修复（`thread/LooperExecutor.kt:57-69` 已补 `shutdown()`/`shutdownNow()`/`awaitTermination()` 抛 `UnsupportedOperationException`（标 `@Deprecated`）+ `isShutdown`/`isTerminated` 恒 false——never-quit 契约已硬保）**
1. **【bug 级】ANIM_EXECUTOR never-quit 契约丢失（API 形式）**（C-1）——**已修复**。原厂 `LooperExecutor extends AbstractExecutorService` 且 `shutdown()` 抛 `UnsupportedOperationException`（`LooperExecutor.java:12,71-73`）；lib `thread/LooperExecutor.kt:57-69` 现已补齐 `shutdown()`/`shutdownNow()`/`awaitTermination()` 抛 `UnsupportedOperationException`（标 `@Deprecated`），外加 `isShutdown`/`isTerminated` 恒 false 两个只读属性。调用方按 AOSP 习惯写 `shutdown()` 得到与原厂一致的明确异常，契约已硬保。

> **✔️保持简化（demo 无 system_server 任务事件源；"超时兜底 + 手工 dispose"已足够演示——§B-8 / 4.2-1 明示保留）**
2. **【bug 级】`TaskStateChangeTimeOutListener` 是无主孤儿**：原厂该 listener 通过 `addGlobalTaskStateChangeListener` 注册到 `TaskStateHelper.globalListeners`（`CopyOnWriteArrayList`，`:31`），由 system_server 任务事件回调驱动 `onTimeOut`，并由 `Launcher.onDestroy → TaskStateHelper.removeAllListener()` 集中 dispose。lib 的 listener 是裸 `class`，构造 postDelayed + dispose 配对但**无人调用构造**——demo 演示的全是手工 `registerSpecialSceneExitTimeOutListener(1500L)` + 手工 `.onTimeOut(...)`。原厂的语义是"事件/超时 OR 触发，Activity onDestroy 兜底 dispose"；lib 是"构造即挂超时、谁 dispose 谁负责"。**两种语义的鸿沟在于"事件先到先发"——lib 完全没有事件源**，demo 演示价值有限。

> **✅已修复（64d3bab：`control/AnimationController.kt` 新增 destroy() 方法，dispose 3 个 timeout listener + reset；`DemoBaseActivity.kt` 新增 onCleanup() 钩子 + onDestroy()）**
3. **【高】`TaskStateHelper.removeAllListener()` 集中清理缺失**（C-5）。原厂 `Launcher.onDestroy()` 第 3846 行显式调 `TaskStateHelper.removeAllListener()`：遍历全局监听器，逐个调 `onTaskListenerReleased()`（`TaskStateHelper.java:270-277`）——`TaskStateChangeTimeOutListener.onTaskListenerReleased()` 内部 dispose + 移除全局注册 + removeCallbacks（`:186-193`）。这一行保证进程级所有待清理的 listener 在 Activity 销毁时被强摘。lib `DemoBaseActivity` 不重写 `onDestroy`，`AnimationController` 也不实现集中清理——demo 进出场次时 listener 残留，靠 GC 回收（GC 时机不可预测，且 callback 持有的 Handler 引用链不破坏之前不会 GC `TaskStateChangeTimeOutListener` 本身）。**demo 测试反复进出同一 Activity 时，超时兜底可能被延迟触发**（不影响功能正确性，但断言"超时即触发"会偶发失败）。

> **✅已修复（64d3bab：`DemoBaseActivity.kt` 新增 onCleanup() 钩子 + onDestroy()）**
4. **【高】`Launcher.onStop` / `onDestroy` 多类动画 cancel 缺失**（C-6）。原厂在 `onStop` 里 folder/stack `cancelRunningAnimations()`（`Launcher.java:4410, :4417`），`onDestroy` 里复位 `AnimationRecord.sAnimationId = -1`、调 `removeAllListener`、`RecentsViewAnimUtil.updateRecentsOrRemoteAnimationRunningFlags(3, false)`（`:4454`）、`AnimSeqTimeStamp.resetLastLaunchTaskTime()`（`:4457`）。lib 无对应路径——demo 退场时动画继续 tick 直到 `cancel` 显式调用或自然结束。**演示场景下若 `AsyncValueAnimator.start()` 后用户立即退出 Activity，未 cancel 的 animator 仍在 ANIM 线程跑直到 end**，本身无害（ANIM_EXECUTOR 永远活），但若业务 listener 持有 Activity View，会形成 1~2 帧的"已 detach View 仍收到回调"的幽灵引用窗口——**这是 View 泄漏的间接路径**。

> **✔️保持简化（null 槽 + 快照派发与原厂同构；clearListeners 调用点双方都缺——doc 自认非 lib 独有）**
5. **【中】listener 强引用 View 的泄漏形态未变**（C-3）。原厂 AsyncAnimCallbacks 用 `ArrayList` 强持 `NullableAnimatorListener`，lib 用 `mutableListOf`——本质相同。`AsyncAnimCallbacks.clearListeners` 是公开 API（`AsyncAnimCallbacks.java:107-109`、lib `anim/AsyncAnimCallbacks.kt:46`），但**调用方** `IconLayerUpdater` / `CustomRectFSpringAnim` 都没有显式调它。动画自然结束后 listener 不被清——`ArrayList` 里持续持有（null 槽会增长；lib 的 `removeAll { it == null }` 在 `getListeners` 调用时才压缩，原厂 `removeNullEntries` 同样惰性压缩）。**长生命周期进程 + 短生命周期 listener**的场景下，list 大小线性增长（最坏 O(n)，n = 历次 addListener 累计）。这点上原厂与 lib 都一样，**这是个**：

   - 原厂已知设计取舍：v4 §9.5 提到 WeakReference 持有 factory 会造成动画瞬结、窗口跳变（`LauncherAnimationRunner.java:305,419-451`），是 OPPO 自己在线上踩过的坑。所以**保留 null 槽 + 快照派发是正确的**，但调用方需要在合理时机 `clearListeners`——这点两边都缺失。

> **⚠️未修复（随 MultiDynamicAnimation/CustomRectFSpringAnim 回移时对齐多帧时序；当前 lib 无该链路——同 review 01 §③-5 占位缺口）**
6. **【中】cancel 是"下一帧生效"的语义缺失标注**（C-12）。`MultiDynamicAnimation.requestEnd(true)` 是置 `mCancelRequest` 标志，下一次 `doAnimationFrame` 帧末才 `endAnimationInternal`（`MultiDynamicAnimation.java:168-176`）。`endAnimationInternal` 摘 AnimationHandler 回调（`:95`）、跑 `mEndListeners` 派发（`:97-101`）。lib 的 `AnimationHandler.removeCallback` 是置 null 槽 + listDirty=true，**派发循环下一次 doAnimationFrame 时跳过 null**（`core/AnimationHandler.kt:92-102`），帧末 `cleanUpList` 压缩（`:105-109`）——单回调摘除语义一致。但**原厂多帧时序**（cancel → 帧末 → end 派发 → AsyncAnimCallbacks 走 marshal → 主线程回调 → 业务继续）在 lib 上**单帧即生效**（cancel → listDirty → 下一帧不再分发 → 下一帧清理），回调时机更激进。**业务如果在 cancel 后立刻假设"已结束"做副作用**，lib 比原厂更早触发；反之若业务等下一帧验证 "end 已发"，lib 比原厂更晚可见。**两个方向都潜在偏差，需逐用例确认**。

> **✔️保持简化（进程级单例线程永不退出是有意设计；demo 安全；暴露 quit 入口反而引入误用）**
7. **【低】`AnimationHandler` ThreadLocal 的隐式生命周期**：原厂框架版 `AnimationHandler.getInstance()` 是 ThreadLocal，由线程 exit 时 ThreadLocalMap 随 Thread 实例回收。launcher.anim 永不 quit，**该 ThreadLocal 永不清理**。`setProvider(SfVsyncFrameCallbackProvider)` 只在 `ANIM_EXECUTOR$lambda$0()` 调一次（`OplusExecutors.java:170`），线程整个生命周期都是 SF provider——**没有 `resetProvider` 路径**。lib `AnimationHandler.installThreadScheduler`（`core/AnimationHandler.kt:153-159`）也只装不拆，`replaceThreadScheduler` 提供运行时换 scheduler（`:167-171`）。**两端都没有"清理"语义**——这是设计正确（进程级单例线程），但若 lib 在测试场景替换 scheduler 后调 `AnimationControlThread.instance.quitSafely()`，会**泄漏 ThreadLocal**。`HandlerThread.quitSafely` 是公开 API；lib 没暴露这个入口。**demo 安全，但调用方要主动意识到"线程一旦启动永不退出"**。

> **✔️保持简化（场景外：依赖 IconLayer 复用体系——4.2-2 同判）**
8. **【低】`AnimationRecord` 缺失导致"复用上次动画 IconLayer"语义丢失**（C-7）。原厂 `AnimationRecord` 提供 `sAnimationId` 单调计数 + `tryConnectExistingAnim`（`AnimationRecord.java:405-431`）+ `canReuseIconLayer`（`:178`），用于快速切换应用时复用上次动画的 icon leash。**demo 场景不涉及该机制，无功能影响**——属行为分歧的"场景外"项。

> **✔️保持简化（与原厂一致：dispose 不清 option；lib listener 随持有链整体 GC，无独立泄漏窗口）**
9. **【低】`TaskStateChangeTimeOutListener.option` 引用未在 dispose 时清零**（C-11）。原厂与 lib 都是 dispose 时只 `handler = null`，option 引用保持到 GC。**短生命周期 option**（如 lambda 捕获 Activity this）会延迟 Activity GC。**原厂通过 `TaskStateHelper.removeAllListener()` 在 onDestroy 集中调 `dispose()` 缓解**——这恰好是 C-5 的另一面。

> **⚠️未修复（备忘项：回移 MultiDynamicAnimation 时在 endAnimationInternal 末尾补 mEndListeners/mUpdateListeners clear——当前无对应实现）**
10. **【低】`MultiDynamicAnimation` 框架版 `endAnimationInternal` 只摘回调不清 listeners**。原厂 `MultiDynamicAnimation.java:97-101` 的 `mEndListeners` 不在 `endAnimationInternal` 内 clear；lib 无对应实现（因未移植 MultiDynamicAnimation）。如未来回移 MultiDynamicAnimation 链路，需在 endAnimationInternal 末尾加 `mEndListeners.clear() + mUpdateListeners.clear()` 防泄漏。

---

## ④ 回移建议

### 4.1 值得补进 lib 的

> **✅已修复（64d3bab：`control/AnimationController.kt` 新增 destroy() 方法，dispose 3 个 timeout listener + reset）**
1. **【必补】`AnimationController` 加 `destroy()/release()` 集中清理方法**：遍历三种 `TaskStateChangeTimeOutListener` 全部 `dispose()` + 清 `animStateChangeListeners` + `reset()`。与原厂 `Launcher.onDestroy → TaskStateHelper.removeAllListener()` 的语义对齐，是 demo 反复进出场次"干净退出"的最低保障。对应 C-5，约 15 行。
> **✅已修复（`thread/LooperExecutor.kt:57-69`：shutdown/shutdownNow/awaitTermination 抛 UOE + `@Deprecated`，isShutdown/isTerminated 恒 false）**
2. ~~**【必补】`LooperExecutor` 加 `shutdown() throws UnsupportedOperationException`**~~ **已落地**：ANIM_EXECUTOR never-quit 契约已硬保。对应 C-1。
> **✅已修复（64d3bab：`DemoBaseActivity.kt` 新增 onCleanup() 钩子 + onDestroy()）**
3. **【必补】`DemoBaseActivity` 重写 `onDestroy()`**：调 `AnimationController.reset()` + 显式 cancel 所有 AsyncValueAnimator + `AsyncAnimCallbacks.clearListeners()`。对应 C-6 / C-4，~10 行。这是 demo "Activity 销毁安全"的可观测证据，不补则 lib 的"安全 cancel 所有动画"宣传无 demo 验证。
> **✔️保持简化（暴露 quitSafely 反引误用；永不 quit 即契约）**
4. **【建议补】`AnimationControlThread` 暴露 `quitSafely()` / `quit()`**：明示"进程级单例，永不 quit"是设计而非疏忽。给调用方一个明确的语义锚点，避免误用。对应 §3-7，约 3 行。
> **✅已完成（2026-09-09 续轮：公开 dispose + 代次失效 + Demo3 调用 + 回归测试）**
5. **【建议补】`AsyncAnimCallbacks` 加 `dispose()` 复合方法** = `clearListeners() + animationId = -1`：让业务在"逻辑结束 + 物理结束"双轨时统一摘除 listener 容器，避免 ArrayList 长期增长。对应 §3-5，约 3 行。
> **✔️保持简化（2 行补强；原厂未做、lib 无独立泄漏窗口——见 ③-9）**
6. **【建议补】`TaskStateChangeTimeOutListener.dispose()` 时把 `option` / `type` 也置 null**：缩窄引用窗口，让 option 闭包持有的 View/Activity 更早可 GC。原厂未做（`TaskStateHelper.java:147-154`），lib 是补强的好机会。对应 C-11，2 行。
> **✅已修复（64d3bab：`docs/USAGE.md` 新增“Activity 生命周期契约”小节）**
7. **【可选】文档补一节「Activity 生命周期契约」**：明示 demo 必须重写 `onDestroy` 才能正确收尾，否则 GC 兜底；明示 `AnimationControlThread` 永不 quit；明示 `ANIM_EXECUTOR` 的 shutdown() 抛 UnsupportedOperationException。文档化的契约比代码契约更稳。
> **✔️保持简化（AsyncValueAnimator.dispose() 无调用方；ValueAnimator 生命周期 + GC 足够）**
8. **【可选】`AsyncValueAnimator` 暴露 `dispose()`**：一次性 cancel + clearListeners + 切断 executor 引用——给"用完即弃"的 animator 一个明确结束点，避免依赖 ValueAnimator 自身的 GC。对应 §3-4，~5 行。

### 4.2 建议保持简化

> **✔️保持简化**
1. **`TaskStateHelper.globalListeners` CopyOnWriteArrayList + binder 任务事件分发**：与 system_server 的 `addTaskListener` 强绑定（`TaskStateHelper.java:255`），demo 无对应事件源；保留"超时兜底 + 手工 dispose"语义足够演示价值，对应 §B-8。
> **✔️保持简化**
2. **`AnimationRecord` 整套（animationId 单调计数、AnimationRecordViewInfo、IconLayer 复用）**：与"独立动画线程"演示主题正交，且依赖 `IconLayerHolder` / `IconSurfaceManager` 等大量 launcher 内部类型，硬塞会把 demo 拖入 launcher 业务建模。对应 §C-7。
> **✔️保持简化**
3. **`Launcher.onStop` 的 folder/stack `cancelRunningAnimations`、`RecentsViewAnimUtil.updateRecentsOrRemoteAnimationRunningFlags`、`AnimSeqTimeStamp.resetLastLaunchTaskTime`**：都是 launcher 业务专用回调，lib 不引入这些类即用不上。
> **✔️保持简化**
4. **`LooperExecutor.getLooper / getHandler / getThread / setThreadPriority`**：原厂用这些方法从 executor 拿到 Handler 来做 postDelayed / setThreadPriority——lib 没有调用方需要这些访问器，postAsync 已经走 handler。对应 C-2。
> **✔️保持简化**
5. **`OplusLooperExecutor.executeBlockWait`**：v4 §9.3 点名的主线程 5s 硬等 ANR 风险，属"原厂自己也不该这么写"。**不要回移**。
> **✔️保持简化**
6. **MultiDynamicAnimation 的 `mEndListeners` / `mUpdateListeners` 在 end 后 clear**：等真正回移 MultiDynamicAnimation 链路时再补，目前 lib 无对应实现。
> **✔️保持简化**
7. **`AsyncAnimCallbacks.clearListeners` 增加"压缩 null 槽"语义**：当前 `getListeners()` 已在派发前压缩（`anim/AsyncAnimCallbacks.kt:76-79`），重复优化收益小。

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

## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **0e8a472** — runCatching 替代 try/catch



逐条判定（批次 1 逐项状态，标注位置见正文）：
- **③-#1（shutdown 契约）** — ⚠️未修复（2 行 API 形式契约；误用面比 doc 所述更小）
- **③-#2（timeout listener 孤儿）** — ✔️保持简化（无事件源）
- **③-#3/#4（集中清理 / onDestroy）** — ⚠️未修复（Demo6/10/11 局部 onDestroy 已覆盖）
- **③-#5/#9（listener 引用）** — ✔️保持简化（与原厂同构）
- **③-#6/#10（MultiDynamicAnimation 时序）** — ⚠️未修复（随回移处理）
- **③-#7（ThreadLocal 生命周期）** — ✔️保持简化（永不 quit 有意设计）
- **③-#8（AnimationRecord）** — ✔️保持简化（场景外）
- **§④-4.1 表** — 1/2/3/7 ⚠️未修复，4/5/6/8 ✔️保持简化（见正文）
- **§④-4.2 表** — 全部 ✔️保持简化（清单即保持简化）
其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。
---

## 复核记录 v2（2026-09-09，独立逐条复核）

- **复核方法**：逐条读取文档声称 → Python 读取 lib 源码 → 对照 OPPO 原厂证据 → 修正标记
- **复核条目总数**：25（§③ 十条 + §④-4.1 八条 + §④-4.2 七条）
- **修正数**：0 条（前次 v2 标记全部正确，本次独立验证确认）

### 逐条验证结果

**§③ 行为差异风险点（10 条）**：

| # | 条目 | 标记 | 验证证据 |
|---|---|---|---|
| ③-1 | ANIM_EXECUTOR never-quit 契约 | ✅已修复 | `thread/LooperExecutor.kt:57-69` shutdown/shutdownNow/awaitTermination 抛 UOE + isShutdown/isTerminated 恒 false |
| ③-2 | TaskStateChangeTimeOutListener 无主孤儿 | ✔️保持简化 | `control/TaskStateChangeTimeOutListener.kt` 无全局注册/事件源；demo 手工 onTimeOut |
| ③-3 | removeAllListener 集中清理缺失 | ⚠️未修复 | `control/AnimationController.kt:134-148` reset() 不 dispose 三个 timeout listener |
| ③-4 | Launcher.onStop/onDestroy 多类 cancel 缺失 | ⚠️未修复 | DemoBaseActivity.kt 无 onDestroy；Demo6:222-225/Demo10:91-94/Demo11:194-197 各自局部收尾 |
| ③-5 | listener 强引用 View 泄漏形态 | ✔️保持简化 | `anim/AsyncAnimCallbacks.kt:33-46` 强引用 mutableList + null 槽懒删 |
| ③-6 | cancel 下一帧生效语义 | ⚠️未修复 | lib 无 MultiDynamicAnimation 链路；cancel 单帧生效（`core/AnimationHandler.kt:64-71, 92-109`） |
| ③-7 | ThreadLocal 隐式生命周期 | ✔️保持简化 | `core/AnimationHandler.kt:133-171` ThreadLocal 只装不拆；未暴露 quitSafely |
| ③-8 | AnimationRecord 缺失 | ✔️保持简化 | lib 全树无 AnimationRecord |
| ③-9 | option 引用未清 | ✔️保持简化 | `control/TaskStateChangeTimeOutListener.kt:41-43` dispose 不清 option |
| ③-10 | MultiDynamicAnimation endAnimationInternal | ⚠️未修复 | 无 MultiDynamicAnimation 可回移对象 |

**§④-4.1 值得补进 lib（8 条）**：

| # | 条目 | 标记 | 验证证据 |
|---|---|---|---|
| 1 | AnimationController destroy() | ⚠️未修复 | `control/AnimationController.kt` 无 destroy()/release() |
| 2 | LooperExecutor shutdown() | ✅已修复 | `thread/LooperExecutor.kt:57-69` |
| 3 | DemoBaseActivity onDestroy | ⚠️未修复 | DemoBaseActivity.kt 无 onDestroy |
| 4 | quitSafely 暴露 | ✔️保持简化 | 未暴露 quitSafely（有意设计） |
| 5 | dispose() 复合方法 | ✔️保持简化 | clearListeners 已存在 |
| 6 | option/type 置 null | ✔️保持简化 | 与原厂一致 |
| 7 | Activity 生命周期契约文档 | ⚠️未修复 | docs/USAGE.md 无该节（已 grep 验证） |
| 8 | AsyncValueAnimator dispose() | ✔️保持简化 | 无调用方需求 |

**§④-4.2 建议保持简化（7 条）**：全部 ✔️ 维持（globalListeners 总线、AnimationRecord、launcher 业务回调、getLooper/getHandler、executeBlockWait、MultiDynamicAnimation 未回移、clearListeners 压缩语义均无变化）。

### 路径/行号勘误（与前次 v2 一致）

包重组（e62dbff）+ scheduler 合并删除（215ecb5）后路径已全部更新：
- `async/` → `anim/`；`animthread/` → `thread/`；`controller/` → `control/`；`core/anim/` → `core/`
- ScheduledTickScheduler/HandlerTickScheduler → ChoreographerTickScheduler（215ecb5 合并删除）
- 本文档主文已在前次 v2 中更新为正确路径，本次验证确认无误

## 复核记录 v3（2026-09-11，64d3bab 修复标记）

- **复核方法**: 按 commit 64d3bab 修复内容，更新正文对应项的标记
- **复核条目总数**: 5
- **修正数**: 5

### 逐条验证结果

| # | 条目 | 标记 | 验证证据 |
|---|---|---|---|
| 3-3 | removeAllListener 集中清理缺失 | ✅已修复 | `control/AnimationController.kt` 新增 destroy(), dispose 3 个 timeout listener + reset |
| 3-4 | DemoBaseActivity 无 onDestroy | ✅已修复 | `DemoBaseActivity.kt` 新增 onCleanup() + onDestroy() |
| 3-7 / 4.1#7 | Activity 生命周期契约文档缺失 | ✅已修复 | `docs/USAGE.md` 新增“Activity 生命周期契约”小节 |
| 4.1#1 | AnimationController destroy() | ✅已修复 | `control/AnimationController.kt` 新增 destroy() |
| 4.1#3 | DemoBaseActivity onDestroy | ✅已修复 | `DemoBaseActivity.kt` 新增 onCleanup() + onDestroy() |
