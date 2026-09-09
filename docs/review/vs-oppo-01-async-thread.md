# vs OPPO — 区域 01：异步/线程层（lib vs ColorOS 15 Launcher 15.8.24）

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/`（Kotlin；2026-09-09 包重组 e62dbff/b39f130 后：`anim/`＝async+continuation、`thread/`＝animthread+async 线程类、`core/`＝anim+scheduler+Trace。下文 lib 证据路径均已按此校正；`ScheduledTickScheduler`/`HandlerTickScheduler` 已于 215ecb5 删除）
> - **原厂**：`D:/oppo_a6_launcher/sources`（OPPO ColorOS 15 Launcher 15.8.24，JADX 反编译；80% 文件 DLP 加密，全部证据经 Grep ripgrep 明文通道取得，行号为 JADX 反编译文本行号）
>
> 上一轮对比见 `docs/review/01-async-animthread.md`（已过时：lib 当时 `Executors` 只剩 `MAIN_EXECUTOR`、`AsyncAnimCallbacks` 无快照派发、`AnimationControlThread` 优先级错写 `-8`/`URGENT_DISPLAY`）。本报告基于  **当前 lib 状态**重做，反映 4 处已修复与若干新增差异。
>
> 背景结论见 `docs/animation-thread-analysis-v4.md`（v4）+ `docs/animation-trace-validation.md`（真机 Perfetto trace 验证）。

---

## 0. 本轮 lib vs 上一轮 review 01 的差异（先列，方便对齐）

| 项 | 上一轮 review 01 状态 | 当前 lib 状态（HEAD 086844e） | 来源 |
|---|---|---|---|
| 线程优先级 | `-8` / `THREAD_PRIORITY_URGENT_DISPLAY`（bug） | `-19` 字面量（与原厂一致） | `thread/AnimationControlThread.kt:83` |
| `Executors` 单例 | 仅 `MAIN_EXECUTOR` | 新增 `ANIM_CONTROL_EXECUTOR`（绑定 launcher.anim） | `thread/Executors.kt:20,23` |
| `AsyncAnimCallbacks` 派发 | 同步 `post`，可能阻塞 sync-barrier | `postAsync`（`Message.setAsynchronous(true)`） | `thread/LooperExecutor.kt:49-54`、`anim/AsyncAnimCallbacks.kt:97-100` |
| `AsyncAnimCallbacks` 快照派发 | 直接迭代 `mutableListOf`，有 CME 风险 | `getListeners()` 先 `removeAll{null}` 再 `filterNotNull` 快照 | `anim/AsyncAnimCallbacks.kt:76-79` |
| `ActualEndAnimListener` 缺失 | 缺失 | 已实现（`onAnimActualEnd` 仅对 ActualEndAnimListener 派发） | `anim/ActualEndAnimListener.kt:23-27`、`anim/AsyncAnimCallbacks.kt:61-70` |
| `AsyncSpringAnim` 类 | 未提及 | 新增（`extends AsyncAnimWrapper`、对齐原厂 `OplusAsyncSpringAnimWrapper`） | `anim/AsyncSpringAnim.kt:21-51` |

---

## ① 类对应关系表

> **lib 文件路径**全部以 `D:/AsyncAnimator/` 为根。**原厂证据**文件路径以 `D:/oppo_a6_launcher/sources/` 为根，行号经 Grep 取得。

| # | lib 类（文件） | 原厂类（文件:行） | 关系 |
|---|---|---|---|
| 1 | `anim/AsyncValueAnimator.kt` | `com/android/quickstep/util/animation/AsyncValueAnimator.java:24`（`extends ValueAnimator`，`classes3.dex`） | 精确复刻（详 §②） |
| 2 | `anim/AsyncAnimCallbacks.kt` | `com/android/quickstep/util/animation/AsyncAnimCallbacks.java:23`（`final class）` | 精确复刻（详 §②） |
| 3 | `anim/ActualEndAnimListener.kt` | `com/android/quickstep/util/animation/ActualEndAnimListener.java:9`（`open class extends NullableAnimatorListenerAdapter`，`:10` 空钩子） | 精确复刻 |
| 4 | `thread/LooperExecutor.kt` | `com/oplus/basecommon/thread/LooperExecutor.java:12`（`extends AbstractExecutorService`，无 `postAsync`） | 精确复刻主语义，`postAsync` 是新增扩展（详 §②） |
| 5 | `thread/Executors.kt` | `com/oplus/basecommon/thread/Executors.java:20`（`MAIN_EXECUTOR`）+ `OplusExecutors.java:95`（`ANIM_EXECUTOR`） | 精确复刻（仅 2 个单例，详 §②-B） |
| 6 | `anim/AsyncSpringAnim.kt` | `com/android/quickstep/util/OplusAsyncSpringAnimWrapper.java:17`（`final class extends AsyncAnimWrapper`，持 `SpringAnimation` + `viewSupportAnimThread` 标志，`:19`） | 精确复刻主骨架（详 §②-A） |
| 7 | `anim/CustomRectFSpringAnim.kt` | `com/android/quickstep/util/animation/CustomRectFSpringAnim.java`（907 行；`mStartAsync = true` @ `:252`；`mAnimType = SWIPE_TO_HOME` @ `:248`；`AnimType` 7 值 `@ `:115-123`） | 仅 18 行句柄占位，原厂 6 自由度弹簧/线程切换协议全部不移植（详 §②-B） |
| 8 | `thread/AsyncAnimWrapper.kt` | `com/android/launcher3/anim/AsyncAnimWrapper.java:10`（20 行；`runOnAnimThread → OplusExecutors.ANIM_EXECUTOR` @ `:11-13`；`runOnMainThread → MAIN_EXECUTOR` @ `:16-18`） | 精确复刻（1:1） |
| 9 | `thread/AnimationControlThread.kt` | `com/oplus/basecommon/thread/OplusExecutors.java:95`（`new OplusLooperExecutor(createAndStartNewLooper("launcher.anim", -19, …), new f(1))`）；`:169-171`（`ANIM_EXECUTOR$lambda$0`：`setProvider(new SfVsyncFrameCallbackProvider())` + `LauncherBooster.getCpu().setUxThreadValue(Process.myTid())`） | 结构对齐，但隐式复刻而非显式（详 §②-B、§③） |
| 10 | ~~launcher/animthread/HandlerTickScheduler.kt~~（215ecb5 已删） | **无直接对应类**；替代 `core/AnimationHandler` 的 `FrameCallbackProvider14/16` + 框架 `AnimationHandler.setProvider(new SfVsyncFrameCallbackProvider())`（`OplusExecutors.java:170`） | 已删：职责并入 `core/ChoreographerTickScheduler`（真 Choreographer VSYNC，dbde195/215ecb5），见 §②-B-1 |
| 11 | `core/AnimationHandler.kt` | 框架 `android.animation.AnimationHandler`（@hide，**不在 sources 树**，仅被 import）；`MultiDynamicAnimation.java:21` `implements AnimationHandler.AnimationFrameCallback`，`:127` `getInstance().addAnimationFrameCallback(this, 0L)`；`vendored androidx/core/animation/AnimationHandler` 与 `androidx/dynamicanimation/animation/AnimationHandler`（均在 `classes2.dex`，非 sources 树） | ThreadLocal/懒删除/续帧/快照分发等结构镜像原厂（详 §②-A 与 review 04） |
| 12 | `core/TickScheduler.kt`（`ScheduledTickScheduler.kt` 215ecb5 已删） | `vendored AnimationFrameCallbackProvider` 接口 | 抽象合并；JVM 实现已删，唯一实现 `core/ChoreographerTickScheduler`（详 §②-B） |
| 13 | `core/Trace.kt` | `android.os.Trace`（@hide）+ 各处 `Trace.traceBegin/traceEnd` | 简化 stderr 重定向（详 §②-B） |
| 14 | `anim/OplusValueAnimator.kt`（原 launcher/continuation） | `com/oplus/quickstep/utils/OplusValueAnimator.java:39`（`extends ValueAnimator`，持 `AnimParam<T> param` + `ObjectAnimator timeController`） | 委托骨架对齐；timeController 接线已实现（`anim/OplusValueAnimator.kt:96-118` `TimeControllerObjectAnimator`、`:130-154` `generateContinuationAnim`）——修正 review 04 §2.3-3 “空实现”旧结论 |
| 15 | `anim/RecordInputInterpolator.kt` | `com/oplus/quickstep/utils/RecordInputInterpolator.java:10` | 逐行一致（review 04 §2.1） |

**配套线程族**（原厂全量、lib 0 移植，仅 §②-B 列出取舍理由）：
- `URGENT_TRANSACTION_EXECUTOR`（`OplusExecutors.java:31-37`，"UrgentTransactionHelper"，-8）
- `WALLPAPER_TRANSACTION_EXECUTOR`（`:42-48`，"WallpaperTransactionHelper"，-4）
- `TASK_VIEW_TRANSACTION_EXECUTOR`（`:64-70`，"TaskViewTransactionHelper"，-8）
- `FETCH_ICON_EXECUTOR`（`:75-81`，"FetchIcon"，-4）
- `RECENT_TASKS_EXECUTOR`（`:86-92`，"RecentTasksList"，-4）
- `UX_TASK_EXECUTOR`（`:97-98`，"onlineUXThread"，-19）
- `MODEL_EXECUTOR_HELPER`（`:101-107`，"launcher-loader-helper"，-4）
- `PRECLOSE_EXECUTOR`（`:156-162`，"PreCloseThread"，-8）
- `MODEL_EXECUTOR`（`Executors.java:56`，"launcher-loader"，-4）
- `UI_HELPER_EXECUTOR`（`Executors.java:55`，"UiThreadHelper"，-4）

---

## ② 保真度评估

### A. 精确复刻（行为可对齐）

#### A.1 `AsyncValueAnimator` 的"线程 marshal + AtomicBoolean 门控"核心

| # | 设计点 | 原厂证据 | lib 证据 |
|---|---|---|---|
| 1 | `start/cancel/end` 按当前线程判 Looper，跨线程自动 marshal | `AsyncValueAnimator.java:117-127`（start）、`:130-141`（cancel）、`:154-164`（end）均用 `mAnimLooperExecutor.getLooper().isCurrentThread()` → 否则 `mAnimLooperExecutor.execute(...)` | `AsyncValueAnimator.kt:42-53`（`marshal{}` 内联或 `executor.execute`），结构对齐 |
| 2 | 默认 executor = `MAIN_EXECUTOR` | `AsyncValueAnimator.java:55`（`this.mAnimLooperExecutor = MAIN_EXECUTOR`） | `AsyncValueAnimator.kt:17`（`var executor = Executors.MAIN_EXECUTOR`） |
| 3 | `mIsEnd` AtomicBoolean 门控：cancel/start 遇 end 丢弃，end 用 CAS 保证只发一次 | `AsyncValueAnimator.java:57`（`new AtomicBoolean(false)`）、`:61/69/80`（cancel/end/start 内 gate） | `AsyncValueAnimator.kt:22`（`AtomicBoolean(false)`）、`:25-37`（同名 gate） |
| 4 | `Companion` + `ofFloat(isAsync, …)` 工厂（Kotlin object 模式） | `AsyncValueAnimator.java:34-55`（`Companion` 内 `ofFloat(isAsync: Boolean, values: Float…)`） | **缺失**（详 §②-C-1） |

#### A.2 `AsyncAnimCallbacks` 的"快照派发 + async 消息 + 双轨结束"

| # | 设计点 | 原厂证据 | lib 证据 |
|---|---|---|---|
| 5 | listener 懒删除：remove 置 null 槽而非立即移除 | `AsyncAnimCallbacks.java:147-151`（`removeListener`） | `AsyncAnimCallbacks.kt:29-32`（同名逻辑） |
| 6 | addListener 去重（`contains` 判定） | `AsyncAnimCallbacks.java:99-105`（`addListeners`） | `AsyncAnimCallbacks.kt:25-27`（同名逻辑） |
| 7 | 派发前把 `animationId` 同步进 `NullableAnimatorListenerAdapter` | `AsyncAnimCallbacks.java:48-49, 60-61, 72-73`（在三个 `for(getListeners())` 循环内统一 `setAnimationId`） | `AsyncAnimCallbacks.kt:107`（`animationId` 在 dispatch 内统一赋值给 adapter） |
| 8 | 派发前压缩 null 槽 + 返回快照（`getListeners()` + `removeNullEntries`） | `AsyncAnimCallbacks.java:29-32`（`getListeners = removeNullEntries + toArray`）、`:81-97`（`removeNullEntries` 实现） | `AsyncAnimCallbacks.kt:84-88`（`getListeners = removeAll{null} + filterNotNull`），等价 |
| 9 | listener 始终回主线程 fire（`runOnMainThread`） | `AsyncAnimCallbacks.java:154-162`（`runOnMainThread(Runnable)`） + `:120, 128, 136, 143`（四处调用） | `AsyncAnimCallbacks.kt:112-117`（`runOnMainThread`）+ `:69-72, 100-103`（两处调用） |
| 10 | listener 派发用 **async 消息**（穿透 sync-barrier） | `AsyncAnimCallbacks.java:154-162` → `Utilities.postAsyncCallback(MAIN_EXECUTOR.getHandler(), …)`；`Utilities.java:631-637`：`Message.obtain(handler, runnable).setAsynchronous(true).sendMessage()` | `AsyncAnimCallbacks.kt:114-117`（`exec.postAsync(action)`）→ `LooperExecutor.kt:39-46`（`Message.obtain(h) { action() }.isAsynchronous = true`），行为一致 |
| 11 | 双轨结束：`onAnimActualEnd` 只对 `ActualEndAnimListener` 派发 | `AsyncAnimCallbacks.java:34-43`（`onAnimActualEnd$lambda$7` 仅 `instanceof ActualEndAnimListener` 时调 `onAnimActualEnd(animator)`） | `AsyncAnimCallbacks.kt:65-77`（同款 instanceof 判定），等价 |
| 12 | `mAnimType` / `setAnimType` 与 `AnimationController` 解耦（Kotlin metadata 暴露为公开 API） | `AsyncAnimCallbacks.java:5` import `CustomRectFSpringAnim$AnimType`；metadata d2 表含 `mAnimType`/`setAnimType`/`type` | **lib 砍掉**（详 §②-B-4） |

#### A.3 `LooperExecutor` 的"同 Looper 内联 + Handler.post"骨架

| # | 设计点 | 原厂证据 | lib 证据 |
|---|---|---|---|
| 13 | `execute`：同 Looper 内联、否则 Handler.post | `LooperExecutor.java:27-33`（`getHandler().getLooper() == Looper.myLooper()` → 直接 `run`；否则 `getHandler().post(runnable)`） | `LooperExecutor.kt:25-29`（`isCurrentThread` → 直接 `action()`；否则 `post`），等价 |
| 14 | 提供 `getHandler()` / `getLooper()` / `getThread()` 访问器 | `LooperExecutor.java:35-45`（三 getter） | **缺失**（详 §②-C-3） |
| 15 | `postDelayed(long)` 直接走 Handler | `LooperExecutor.java:61-62` | **缺失**（详 §②-C-3） |
| 16 | `setThreadPriority(int)` | `LooperExecutor.java:65-66`（`Process.setThreadPriority(((HandlerThread) getThread()).getThreadId(), i9)`） | **缺失**（详 §②-C-3） |
| 17 | `shutdown()` 抛 `UnsupportedOperationException`（"永不 quit"契约） | `LooperExecutor.java:71-79`（仅 `UnsupportedOperationException` 分支） | **已补**（086844e：`thread/LooperExecutor.kt:57-69` 的 `shutdown()/shutdownNow()/awaitTermination()` 抛 `UnsupportedOperationException`，`isShutdown/isTerminated` 恒 false） |
| 18 | extends `AbstractExecutorService`（业务方可作 ExecutorService 类型持有） | `LooperExecutor.java:8` import + `:12` extends | **缺失**（详 §②-C-3） |
| 19 | **新增**：`postAsync`（`Message.setAsynchronous(true)`） | 无 | `LooperExecutor.kt:39-46`，对齐原厂 `Utilities.postAsyncCallback` 派发语义（详 §②-A.2-10） |

#### A.4 `AsyncAnimWrapper` 的"双通道调度骨架"

| # | 设计点 | 原厂证据 | lib 证据 |
|---|---|---|---|
| 20 | `runOnAnimThread(task)` → `OplusExecutors.ANIM_EXECUTOR.execute(task)` | `AsyncAnimWrapper.java:11-13` | `AsyncAnimWrapper.kt:21-23`（`Executors.ANIM_CONTROL_EXECUTOR.execute`），等价 |
| 21 | `runOnMainThread(task)` → `Executors.MAIN_EXECUTOR.execute(task)` | `AsyncAnimWrapper.java:16-18` | `AsyncAnimWrapper.kt:27-29`（`Executors.MAIN_EXECUTOR.execute`），等价 |

#### A.5 `AnimationControlThread` 的"线程初始化时挂帧源"骨架

| # | 设计点 | 原厂证据 | lib 证据 |
|---|---|---|---|
| 22 | 线程名 `"launcher.anim"` | `OplusExecutors.java:95`（`createAndStartNewLooper("launcher.anim", -19, …)`） | `AnimationControlThread.kt:73`（`THREAD_NAME = "launcher.anim"`） |
| 23 | 线程优先级字面量 `-19`（URGENT_AUDIO 档） | `OplusExecutors.java:95`（字面量 -19，绑核键 `LauncherBooster.LAUNCHER_STATIC_LAUNCHER_ANIM`） | `AnimationControlThread.kt:78`（`private const val PRIORITY = -19`）——**已修正 review 01 §②C-1 的 bug** |
| 24 | `onLooperPrepared()` 内安装帧源（关键：装"本线程的 AnimationHandler"而非全局单例） | `OplusExecutors.java:169-171`（`ANIM_EXECUTOR$lambda$0`：先 `getInstance().setProvider(new SfVsyncFrameCallbackProvider())`，再 `LauncherBooster.getCpu().setUxThreadValue(Process.myTid())`） | `thread/AnimationControlThread.kt:63-71`（`onLooperPrepared`：`AnimationHandler.installThreadScheduler(ChoreographerTickScheduler())` + `runCatching { Process.setThreadPriority(myTid, PRIORITY) }`），结构等价——帧源已由 HandlerTickScheduler（已删）换成 ChoreographerTickScheduler（真 VSYNC，dbde195/215ecb5） |
| 25 | 线程随类加载创建，进程级单例，永不 quit | `OplusExecutors.java:95`（`static final`，类初始化即建）+ `LooperExecutor.java:71-79`（shutdown 抛异常） | `thread/AnimationControlThread.kt:85-88`（`internal val instance: AnimationControlThread by lazy(SYNCHRONIZED) { AnimationControlThread() }`；`init { start() }` 在 :51-53）——**结构等价；线程永不 quit，shutdown 契约由 LooperExecutor 承载（086844e，见 §②-C-3 更新）** |

#### A.6 `AsyncSpringAnim` / `OplusAsyncSpringAnimWrapper` 的"按 viewSupportAnimThread 分发"骨架

| # | 设计点 | 原厂证据 | lib 证据 |
|---|---|---|---|
| 26 | 持有 `viewSupportAnimThread: Boolean` 决定 start/cancel/skipToEnd 是否 marshal | `OplusAsyncSpringAnimWrapper.java:19`（字段） + `:61, 77-78, 96-97, 108-109, 116-117`（五个生命周期方法内 `if (viewSupportAnimThread) runOnAnimThread(...)`） | `AsyncSpringAnim.kt:18-19`（`supportAnimThread: Boolean`） + `:29-46`（五个生命周期方法内 `if (supportAnimThread) runOnAnimThread { action() } else action()`），等价 |
| 27 | `animateToFinalPosition` / `setStartVelocity` 同款分发 | `OplusAsyncSpringAnimWrapper.java:88-103`（两个方法） | `AsyncSpringAnim.kt:33-35`（同名方法） |
| 28 | **新增**：`addEndListener` 把 end 回调 marshal 回主线程 | 原厂 `OplusAsyncSpringAnimWrapper` 只暴露 `addOnUpdateListener`，end 回调未封装在 wrapper 内 | `AsyncSpringAnim.kt:38-44`（`addEndListener { anim, canceled, value, velocity -> runOnMainThread { listener.onAnimationEnd(...) } }`），**功能增强，非对齐**（详 §②-B-4） |
| 29 | 持有 `real: SpringAnimation` | `OplusAsyncSpringAnimWrapper.java:24-30`（构造器） | `AsyncSpringAnim.kt:18`（`real: SpringAnimation`），等价 |

#### A.7 `Executors` 单例

| # | 设计点 | 原厂证据 | lib 证据 |
|---|---|---|---|
| 30 | `MAIN_EXECUTOR` 绑定 `Looper.getMainLooper()` | `Executors.java:54`（`MAIN_EXECUTOR = new LooperExecutor(Looper.getMainLooper())`） | `Executors.kt:11`（`mainHandlerOrNull()` 包装 `Looper.getMainLooper()`），等价 |
| 31 | `ANIM_CONTROL_EXECUTOR` 绑 `launcher.anim` 线程（类加载即建） | `OplusExecutors.java:95`（`ANIM_EXECUTOR = new OplusLooperExecutor(createAndStartNewLooper("launcher.anim", -19, …), new f(1))`，static final） | `Executors.kt:14`（`ANIM_CONTROL_EXECUTOR = LooperExecutor(Handler(AnimationControlThread.instance.looper))`），等价 |
| 32 | 仅暴露 `MAIN_EXECUTOR` / `ANIM_CONTROL_EXECUTOR`，其余 executor 全部砍掉 | `Executors.java:21-26`（13 个 executor）+ `OplusExecutors.java:31-162`（14 个 executor） | `Executors.kt:11-14`（仅 2 个）——**简化**（详 §②-B-5） |

---

### B. 有意简化（lib 注释中自承或合理 demo 化）

| # | 简化项 | 原厂对应 | lib 取舍理由 | 风险等级 |
|---|---|---|---|---|
| 1 | `SfVsyncFrameCallbackProvider`（@hide，框架 `android.animation.AnimationHandler`）→ `core/ChoreographerTickScheduler`（ThreadLocal 本线程 Choreographer 真 VSYNC；原 `HandlerTickScheduler` postDelayed 自走时钟已删 215ecb5） | `OplusExecutors.java:5` import `com.android.internal.graphics.SfVsyncFrameCallbackProvider` + `:170` `setProvider(...)` | 框架 @hide API，AOSP 公开层无法直接调；lib 注释（`thread/AnimationControlThread.kt:35-41`）明示 | 中（详 §③-2/3） |
| 2 | `LauncherBooster.getCpu().setUxThreadValue(Process.myTid())` UX 线程注册未移植 | `OplusExecutors.java:171` | OPPO 私有；lib 退化为在 `onLooperPrepared` 再设一次 `runCatching { Process.setThreadPriority(myTid, -19) }` 兜底（`thread/AnimationControlThread.kt:70`） | 中（详 §③-3） |
| 3 | `CustomRectFSpringAnim` 砍成 19 行句柄占位（仅 AnimType 枚举 + 类名，`anim/CustomRectFSpringAnim.kt:11-18`） | `CustomRectFSpringAnim.java` 907 行（`mCenterX`/`mRectY`/`mWidth`/`mRadio`/`mRectRadius`/`mAlpha` 六个 SpringHolder；`mAnimType`/`mStartAsync`/`mJustNotifyEndCallback`/… 标志；start/cancel/skipToEnd/reverseToOpen 全部含 `isCurrentThread` + post 纠偏；`mMultiDynamicAnimation`） | lib 注释（`anim/CustomRectFSpringAnim.kt:3-9`）明示"实际动画逻辑由 SpringAnimation 实现" | 高（详 §③-4/§③-5） |
| 4 | `AsyncAnimCallbacks.mAnimType` + `setAnimType(AnimType)` 砍掉 | `AsyncAnimCallbacks.java:5`（import `CustomRectFSpringAnim$AnimType`）+ metadata d2 表含 `mAnimType`/`setAnimType`/`type` | 原厂此字段用于 CustomRectFSpringAnim 反查 animType 写 log（`CustomRectFSpringAnim.java:791` `mAsyncAnimCallbacks.setAnimType(animType)`）；lib CustomRectFSpringAnim 是占位，不需要 | 低（仅去日志装饰） |
| 5 | `OplusLooperExecutor` 四扩展未移植（`executeAtFront`/`executeWithUx`/`executeBlockWait`/`executeDelay`） | `OplusLooperExecutor.java:38, 78-103, 46-71, 73-75` | 全部依赖 `LauncherBooster` 私有 API；`executeBlockWait` 是 v4 §9.3 点名的主线程 5s 硬等 ANR 风险，原厂自己也不该这么写 | 低（详细 §③-5） |
| 6 | `Executors` / `OplusExecutors` 其余 26 个 executor 全部砍掉（`MODEL_EXECUTOR`/`UI_HELPER_EXECUTOR`/`THREAD_POOL_EXECUTOR`/`URGENT_TRANSACTION_EXECUTOR`/`WALLPAPER_TRANSACTION_EXECUTOR`/`TASK_VIEW_TRANSACTION_EXECUTOR`/`FETCH_ICON_EXECUTOR`/`RECENT_TASKS_EXECUTOR`/`UX_TASK_EXECUTOR`/`PRECLOSE_EXECUTOR`/…） | `Executors.java:18-99` + `OplusExecutors.java:30-162`（共 27 个 executor + 14 个 `createAndStartNewLooper` 调用） | 14 个事务/加载线程与异步动画演示主题无关，全量复刻稀释 lib 焦点 | 低 |
| 7 | `LogUtils.i/Debug.getCallers` 装饰性日志删除 | 各处 30+ 处调用（如 `CustomRectFSpringAnim.java:332, 427, 610, 863` 等） | 纯 OEM 调试；lib `core/Trace.kt` 已覆盖可观测性需求 | 低 |
| 8 | `Utilities.postAsyncCallback` 抽象 → `LooperExecutor.postAsync` 折叠进 executor 层 | `Utilities.java:631-637`（独立工具方法，被 12 个调用点使用） | lib 把 async 消息做成 executor 的成员方法（`LooperExecutor.kt:39-46`），调用点更短 | 低（接口位置变了，但语义等价） |
| 9 | `Executors.createAndStartNewLooper(name, priority, launcherBoostKey)` 折叠成 `HandlerThread(name, priority)` + lib 私有单例 | `Executors.java:94-99`（带 UAF 绑核） | UAF（`LauncherBooster.CpuBoost.reportKeyThreadToUAF`）是 OPPO 私有调度增强；lib 退化为只设优先级，不绑核 | 低 |
| 10 | JVM 单测兜底：`LooperExecutor.handler == null` 时就地执行 / 起 sleep 线程 | 原厂 handler 永不 null | `LooperExecutor.kt:17-20, 42-50` 注释明示"测试便利"；`Executors.kt:9-10` 用 `runCatching` 容错 | 低（详 §③-9） |

---

### C. 遗漏（lib 中没有、且不一定是有意砍掉）

| # | 遗漏点 | 原厂证据 | 影响 |
|---|---|---|---|
| 1 | **`AsyncValueAnimator.Companion.ofFloat(isAsync: Boolean, values: Float…)` 工厂** | `AsyncValueAnimator.java:34-55`（`Companion.ofFloat` 返回 `ValueAnimator`，按 `isAsync` 选择同步或异步实现的入口；调用点 `AppLaunchAnimUtil.java:443`） | 调用方迁移成本：`AppLaunchAnimUtil` 等按原厂习惯用 `ofFloat(isAsync, …)` 选同步/异步实现的入口未移植，调用点全要改写 |
| 2 | **`CustomRectFSpringAnim` 线程切换协议整体缺失**——start/cancel/skipToEnd/reverseToOpen 全部 `isCurrentThread` + post 纠偏（`CustomRectFSpringAnim.java:608-628, 752-776, 860-883`）；cancel/skipToEnd 末尾 `maybeEnd()` 双轨补救（`:626, :881`）；结束回调 `runOnMainThread`（`:778-786`）；`mJustNotifyEndCallback` 提前通知机制（`:73, 431, 736-737`） | `CustomRectFSpringAnim.java:608-907` | lib 占位类无任何一项；review 04 §4.2-2 注释明示"未复刻"。**v4 §4 强调的核心设计**——start/cancel 跨线程自动 marshal、cancel 是"置标志下一帧生效"语义、双轨结束——在 lib 完全不可演示。**注意：`thread/AnimationControlThread.kt:43-47` 注释仍引用"CustomRectFSpringAnim.start() 的 looper.isCurrentThread 协议"作为线程安全模型依据（并指向 `docs/review/04-frame-spring-continuation.md` §4.2-2），但 lib 侧的该类并未实现它**——注释与代码脱节（文档级问题，不改码） |
| 3 | **`LooperExecutor` 公共 API 子集**——`getHandler()`/`getLooper()`/`getThread()`/`postDelayed(long)`/`setThreadPriority(int)`/`shutdown()`；`extends AbstractExecutorService` | `LooperExecutor.java:8` (import), `:12` (extends), `:35-45` (三 getter), `:57-66` (post/postDelayed/setThreadPriority), `:71-79` (shutdown 抛异常) | (a) 调用方需要 ExecutorService 类型持有或访问底层 Looper 时无法直接替换；(b) `shutdown()` 抛异常这一"永不 quit"契约已于 086844e 补齐（`thread/LooperExecutor.kt:57-69`，`shutdown()/shutdownNow()/awaitTermination()` 均抛 `UnsupportedOperationException`）——lib `LooperExecutor` 是普通 class，仍不 extends `AbstractExecutorService`、可被 GC；(c) `setThreadPriority` 缺失意味着 lib 的 ANIM_CONTROL_EXECUTOR 没办法在运行时把优先级从 -19 改到别的值（虽然本项目用不到） |
| 4 | **`AsyncAnimCallbacks.onAnimCancel` / `onAnimationEnd` listener 收 `null` animator**（`onAnimationEnd(null)` 等） | `AsyncValueAnimator.java:64`（cancel listener 调 `mAsyncAnimCallbacks.onAnimationCancel(null)`）、`:70`（end → `onAnimationEnd(null)`）、`:83`（start → `onAnimationStart(null)`） | lib 传真实 animator（`AsyncValueAnimator.kt:25-37`），listener 收到的是 `this`。`NullableAnimatorListener` 类的可空容忍形同虚设，命名误导。属行为改进但确为分歧点 |
| 5 | **`AnimationControlThread` 缺失**（OplusExecutors 静态 init lambda）显式声明的语义 | `OplusExecutors.java:95`（`new f(1)` 是 `Function0<Unit>`，在 `:169` 体现为 `private static final void ANIM_EXECUTOR$lambda$0()`） | lib 把 init 逻辑折进 `onLooperPrepared`（`AnimationControlThread.kt:48-55`）语义等价；但若以后要新增"线程首跑时做点别的"（如 `Looper.myQueue().addIdleHandler`），没有显式 hook 点 |
| 6 | `LauncherBooster.CpuBoost.reportKeyThreadToUAF` UAF 绑核（`Executors.java:97`） | 同上 | lib 没绑核，进程级 ANIM 线程跑在默认核上（OPPO ROM 上 2001/2003/2005/2007 是预留 key key）；长跑下 CPU 占用可能落小核，调度延迟比原厂大 |
| 7 | `MultiDynamicAnimation` 直挂框架 `AnimationHandler` 的细节（`getInstance().addAnimationFrameCallback(this, 0L)` 在 start 线程） | `MultiDynamicAnimation.java:21`（`implements AnimationHandler.AnimationFrameCallback`）、`:127`（`AnimationHandler.getInstance().addAnimationFrameCallback(this, 0L)`）、`:152-159`（裸 delta 积分）、`:185-191`（`requestEnd` 下一帧生效） | lib 自有 `AnimationHandler` 是 vendored AndroidX 简化版，结构镜像但**不挂原厂框架 `AnimationHandler`**；review 04 §2.1 已述 |
| 8 | `SpringForce` / `SpringHolder` / `SpringAnimReflectUtils` 三件套（解析解积分、终点切换半步分裂、`isAtEquilibrium` 反射调用） | `SpringHolder.java:47, 117, 130`（`updateValueAndVelocity` → `SpringAnimReflectUtils.updateValues` → `SpringForce`）；`SpringAnimReflectUtils.java:59-61`（直接调用 `updateValues`） vs `:33-43`（仅 `isAtEquilibrium` 走反射） | lib 全树无对应；原厂 6 自由度弹簧积分由 androidx `SpringForce` 解析解承担，lib 退化为 androidx `SpringAnimation`（`AsyncSpringAnim.kt:18`）——**这是路径 D（review 04 §3 表 path D）而非路径 C**；与原厂路径选择不同 |
| 9 | `OplusLooperExecutor` 的 `setUx` 期间保护（`LauncherBooster.getCpu().setUx(true, …)` + 任务 + `setUx(false, …)`） | `OplusLooperExecutor.java:30-35, 78-103`（lambda `$executeWithUx$1`） | lib 无对应 API；业务要"UX 关键事务"标记时只能裸跑 |

---

## ③ 行为差异风险点（按严重度排序）

### 🔴 BUG 级

> **❌已过期（215ecb5 删 Scheduled/HandlerTickScheduler，只剩 ChoreographerTickScheduler）**
1. ✅已修复（dbde195/215ecb5） — **【BUG】`HandlerTickScheduler` 与 `AnimationHandler` 的"空则停"语义不一致**（ScheduledTickScheduler/HandlerTickScheduler 已删，只留 ChoreographerTickScheduler 走真 Choreographer VSYNC；本条 §③-14 也合并消除）
   - 原厂（`vendored androidx AnimationHandler`）：每帧结束检查"还有 callback 才续帧"（`@hide` 路径同样如此），列表清空即自然停帧。
   - lib 的 `HandlerTickScheduler`（`animthread/HandlerTickScheduler.kt:65-77`）复刻了"空则停"（`:74-77`），但**默认的 `ScheduledTickScheduler`（JVM 仿真路径）是常驻定频循环，无 callback 也继续空转**（review 04 §2.3-7）。
   - 影响：JVM 单测场景下，`ScheduledTickScheduler` 持续按 16ms tick，无回调也空转；同时 `HandlerTickScheduler` 在 demo 场景下与默认 scheduler 行为不一致会让同一测试在两种 scheduler 下观察到不同的 `frameCount` 增长曲线。
   - 触发：所有未显式调用 `AnimationHandler.installThreadScheduler(HandlerTickScheduler(…))` 的 lib 调用点；即默认 JUnit 测试全部命中。

> **✔️保持简化（当前 demo 无 delay 调用方，复刻 MultiDynamicAnimation 时才需要）**
2. ⚠️未修复（小，~15 行 `AnimationHandler` 加 `mDelayedCallbackStartTime` + `isCallbackDue`；当前 demo 无 delay 需求） — **【BUG】`AnimationHandler` 缺延迟启动机制**
   - 原厂 vendored `dynamicanimation` 版有 `mDelayedCallbackStartTime` + `addAnimationFrameCallback(cb, delay)` + `isCallbackDue`（dyn `:16, 123-133, 135-145`）；框架版同样带 delay 参数（`MultiDynamicAnimation.java:127` 以 `0L` 调用）。
   - lib `AnimationHandler.addAnimationFrameCallback` 无 delay 形参（`AnimationHandler.kt:51` 注释明示"无 delay"）。
   - 影响：将来复刻 `MultiDynamicAnimation.startAnimationInternal` 路径时缺签名；当前 demo 不触发。

### 🟠 高

> **✔️保持简化（hidden API）+ dbde195 已用公开 Choreographer 对齐 VSYNC**
3. ✔️保持简化 — **`SfVsyncFrameCallbackProvider` 不可达 + UX 线程提权丢失 → 帧源与原厂不同**（choreographer vs SF-vsync 实测同相位，`ChoreographerTickScheduler` 已够用）
   - lib `HandlerTickScheduler`（`animthread/HandlerTickScheduler.kt:67-80`）是 `postDelayed(16ms)` 自走时钟：固定 60Hz，无 vsync 对齐（90/120Hz 屏帧率错配）。
   - 实际真机 trace（`docs/animation-trace-validation.md` §5）显示原厂 launcher.anim 与主线程**同对齐 VSYNC-app**——SF-vsync 帧源在该设备未体现。两线程帧相位一致的运行时收益不依赖 SF-vsync。
   - 综合：lib 仿真场景无影响；真机性能数字不可与原厂互推。

> **✔️保持简化（OPPO 私有 LauncherBooster/UAF）**
4. ✔️保持简化 — **`LauncherBooster.getCpu().setUxThreadValue` 缺失**（OPPO 私有 API；`runCatching { Process.setThreadPriority(Process.myTid(), PRIORITY) }` 兜底已够 demo）
   - 原厂 `OplusExecutors.java:171`：注册为 UX 线程后，调度器把该线程视为 UI 关键线程（提权 / 绑大核 / 限小核）。
   - lib 仅做 `Process.setThreadPriority(myTid, PRIORITY)` 兜底（`AnimationControlThread.kt:53`），无 OPPO 私有调度增强。
   - 影响：原厂在 CPU 满载场景下仍能保持 8ms 帧间隔；lib 在同场景下帧间隔抖动更大（具体数值取决于 ROM 默认调度策略）。

> **⚠️未修复（907 行缺口见 vs-oppo-16；937dd23 用 androidx 弹簧演示了部分语义）**
5. ⚠️未修复（大，~200 行 start/cancel/skipToEnd/reverseToOpen + 6 自由度弹簧 + `mMultiDynamicAnimation`；非本批目标，留待后续轮次） — **`CustomRectFSpringAnim` 占位 + 线程切换协议整体缺失**
   - 原厂 907 行的窗口矩形弹簧 + `mMultiDynamicAnimation` + 线程纠偏协议全部不移植（详 §②-C-2 + §②-B-3）。
   - 影响：lib 无法演示"异步启动动画 + cancel 下一帧生效 + 双轨结束"这一 v4 §4 强调的核心语义；Demo 6 / Demo 9 / Demo 10 在 `addRecentsAnim(CustomRectFSpringAnim(…), null, arrayOf())` 后看不到任何动画行为。
   - 复现条件：所有依赖 `CustomRectFSpringAnim` 句柄的代码路径。

> **✅已修复（LooperExecutor.postAsync + Message.setAsynchronous）**
6. ✅已修复（0e8a472 + review 01 §②C-3 旧修） — **`AsyncAnimCallbacks` 派发用异步消息而非 sync 消息**（`LooperExecutor.postAsync` 走 `Message.setAsynchronous(true)`；JVM 兜底走就地执行）
   - 验证：`LooperExecutor.postAsync` 用 `Message.setAsynchronous(true)`（`thread/LooperExecutor.kt:49-54`），对齐 `Utilities.postAsyncCallback`（`Utilities.java:631-637`）。
   - 残余风险：JVM 单测下 `handler == null` 走"就地执行"（`thread/LooperExecutor.kt:50`），与真机"async 消息"语义不同；demo 跑真机时与单测行为可能不一致。

### 🟡 中

> **⚠️未修复（3 vs 7 值，demo 有意简化；迁移需映射表）**
7. **`CustomRectFSpringAnim.AnimType` 枚举值不一致**
   - 原厂 7 值（`CustomRectFSpringAnim.java:115-123`）：`OPEN_FROM_HOME` / `REMOTE_CLOSE_TO_HOME` / `REMOTE_CLOSE_TO_HOME_ASSISTANT` / `GESTURE_TO_DRAG` / `SWIPE_TO_HOME` / `SWIPE_TO_HOME_ASSISTANT` / `REVERSE_TO_OPEN`。
   - lib 3 值（`CustomRectFSpringAnim.kt:13-17`）：`SWIPE_TO_HOME` / `RECENTS_TRANSITION` / `APP_LAUNCH`——其中后两个原厂不存在。
   - 影响：调用方迁移需做映射表；`REVERSE_TO_OPEN` 等核心转场类型缺失，演示回桌面反向动画的入口需要 stub。

> **✔️有意保留（Kotlin 非空，行为改进非分歧）**
8. **`AsyncValueAnimator` 收到非 null animator**（详 §②-C-4）
   - 原厂 listener 收到 `null`（`AsyncValueAnimator.java:64, 70, 83`），`NullableAnimatorListener` 类的可空容忍因此得名。
   - lib 收到真实 animator（`AsyncValueAnimator.kt:25-37`）。
   - 影响：依赖"animator 为 null 时分支"的业务代码迁移时需调整；属行为改进但确为分歧点。

> **✔️保持简化（27 executor 只留 2 个）**
9. **`Executors` 只暴露 2 个单例，14 个事务/加载线程砍掉**
   - 原厂 27 个 executor 含事务族（URGENT/WALLPAPER/TASK_VIEW，-8/-4）、加载族（MODEL/UI_HELPER，-4）、预关闭（PRECLOSE，-8）、UX 任务（UX_TASK，-19）、Recents（RECENTS_ICON/THUMBNAIL，-4）。
   - lib 仅 `MAIN_EXECUTOR` + `ANIM_CONTROL_EXECUTOR`（`Executors.kt:11-14`）。
   - 影响：demo 无法演示"事务与动画线程的解耦"——v4 §5 描述的三段式收尾（同步收尾 UI 线程 / 异步收尾 UX_TASK_EXECUTOR / onComplete MAIN_EXECUTOR）只能复刻第一段。

> **✔️保持简化（纯日志装饰）**
10. **`AsyncAnimCallbacks.mAnimType` / `setAnimType(AnimType)` 砍掉**
    - 原厂用于 CustomRectFSpringAnim 反查 animType 写 log（`CustomRectFSpringAnim.java:791` `mAsyncAnimCallbacks.setAnimType(animType)`）。
    - lib 砍掉后 CustomRectFSpringAnim 占位类用不上，纯日志装饰丢失。
    - 影响：演示侧无可观测性损失。

### 🟢 低

> **✔️不回移（executeBlockWait 是原厂 ANR 风险）**
11. **`OplusLooperExecutor` 四扩展（`executeAtFront`/`executeWithUx`/`executeBlockWait`/`executeDelay`）未复刻**
    - `executeBlockWait` 是 v4 §9.3 点名的主线程 5s 硬等 ANR 风险，原厂自己也不该这么写；不回移是正确决定。
    - 其余三个是增强能力，业务用不到。

> **⚠️部分修复（086844e：shutdown 契约已补；仍非 ExecutorService、缺访问器）**
12. **`LooperExecutor` 不 `extends AbstractExecutorService`、缺访问器；`shutdown()` 永不-quit 契约已补**
    - 详 §②-C-3（更新）。
    - 影响：业务侧需要 ExecutorService 类型持有或访问底层 Looper/getThread 时仍无法直接替换；shutdown 调用已与原厂一致抛 `UnsupportedOperationException`（`thread/LooperExecutor.kt:57-69`）。

> **⚠️未修复（低风险启动窗口）**
13. **JVM 单测兜底掩盖配置错误**
    - 真机上若 `Looper.getMainLooper()` 意外为 null（进程早期），原厂直接 NPE 暴露；lib 退化为就地执行、动画跑在错误线程上。
    - 触发条件：极少（仅在进程启动窗口期内调用）。

> **❌已过期（同 #1，类已删）**
14. **`ScheduledTickScheduler` 常驻空转 + `HandlerTickScheduler` 空则停**
    - 同 §③-1，但单测可见的语义差异。

> **✔️有意增强（6bbe9a1 把 end 回调 marshal 回主线程）**
15. **trace 桥接的 `AsyncSpringAnim.addEndListener` 把 end 回调 marshal 回主线程**
    - 原厂 `OplusAsyncSpringAnimWrapper` 不封装 end（只有 `addOnUpdateListener`，`OplusAsyncSpringAnimWrapper.java:24`），end 在 SpringAnimation 自带 callback 里 fire 回调线程（与 SpringAnimation.start 线程一致）。
    - lib 强制 marshal 回主线程（`AsyncSpringAnim.kt:38-44`），业务代码不再需要自己判线程。
    - 属功能增强，非行为分歧；调用方迁移要确认"end 在主线程"这一约定不变。

---

## ④ 回移建议

### 4.1 值得补进 lib 的（性价比高）

| # | 改动 | 理由 | 风险消除 |
|---|---|---|---|
| 1 | ✅已修复（215ecb5：删两个 scheduler，ChoreographerTickScheduler 唯一且空则停） — **统一两个 scheduler 的"空则停"语义**（`ScheduledTickScheduler.kt:56-61` 加空转保护：tick 时 callbacks 为空则自动 stop，下次 `postFrameCallback` 时 restart） | 让两种 scheduler 行为一致；消除 demo 在两种配置下 `frameCount` 增长曲线不一致的问题 | §③-1, §③-14 |
| 2 | ✅已修复（64d3bab：`anim/AsyncValueAnimator.kt` companion object ofFloat 工厂） — **`AsyncValueAnimator.Companion.ofFloat(isAsync, …)` 工厂**（`AsyncValueAnimator.kt` 加 `companion object`） | 5 行，对齐 `AppLaunchAnimUtil.java:443` 等调用点迁移 | §③-（轻） |
| 3 | ✔️不修（Kotlin 非空安全有意改进） — **`AsyncAnimCallbacks` 派发恢复"传 null animator"语义**（`AsyncValueAnimator.kt:25-37` 改为 `asyncAnimCallbacks.onAnimationEnd(null)`） | 对齐原厂 `NullableAnimatorListener` 命名的本意 | §③-8 |
| 4 | ⚠️未修复（线程切换协议大改，见 vs-oppo-16） — **`CustomRectFSpringAnim` 至少补线程切换协议**（start/cancel/skipToEnd/reverseToOpen 全部 `isCurrentThread` + post 纠偏 + `maybeEnd()` 双轨补救 + `runOnMainThread` 结束回调） | v4 §4 强调的核心设计，是跨线程动画正确性的关键；占位类有 `AnimType` 但无线程切换协议是 review 04 §4.2 一直标记的"文档与代码脱节"问题 | §③-5 |
| 5 | ⚠️未修复（~5 行枚举补 7 值） — **`CustomRectFSpringAnim.AnimType` 枚举补齐 7 值**（加 `OPEN_FROM_HOME`/`REMOTE_CLOSE_TO_HOME`/`REMOTE_CLOSE_TO_HOME_ASSISTANT`/`GESTURE_TO_DRAG`/`SWIPE_TO_HOME_ASSISTANT`/`REVERSE_TO_OPEN`） | 一行枚举值；让 AnimationController 的 transfer table 能覆盖完整路径 | §③-7 |
| 6 | ✅已修复（64d3bab：`thread/LooperExecutor.kt` getHandler/getLooper/getTargetThread 访问器） — **`LooperExecutor` 补 `getHandler()` / `getLooper()` / `getThread()` / `setThreadPriority(int)` 访问器**（对齐 `LooperExecutor.java:35-66`） | 业务需要访问底层 Looper 时必备；~10 行； | §②-C-3 |
| 7 | ⚠️未修复（demo 无调用方，复刻 MultiDynamicAnimation 时补） — **`AnimationHandler.addAnimationFrameCallback(cb, delayMs)` 重载**（对齐 dynamicanimation/框架版 `dyn :135-145`） | 为将来复刻 `MultiDynamicAnimation.startAnimationInternal`（`MultiDynamicAnimation.java:127`）铺路；当前 demo 无调用点 | §③-2 |

### 4.2 建议保持简化

| # | 简化项 | 理由 |
|---|---|---|
| 1 | ✔️保持简化（清单即保持简化） — `SfVsyncFrameCallbackProvider` 不做反射硬挂 | 框架 @hide API，反射在非 OPPO ROM 上行为不定；真机 trace 显示两线程同相位也能获得全部收益（`docs/animation-trace-validation.md` §5） |
| 2 | ✔️保持简化（清单即保持简化） — `LauncherBooster` UX 标记 / UAF 绑核不移植 | OPPO 私有调度增强，无公开等价物；不影响动画正确性只影响调度优先级 |
| 3 | ✔️保持简化（清单即保持简化） — `OplusLooperExecutor` 四扩展不移植 | `executeWithUx` 依赖私有 API；`executeBlockWait` 是 v4 §9.3 点名 ANR 风险，原厂自己也不该这么写 |
| 4 | ✔️保持简化（清单即保持简化） — 其余 25 个 executor 不移植 | 14 个事务/加载线程与异步动画演示主题无关，全量复刻只会稀释库的焦点 |
| 5 | ✔️保持简化（清单即保持简化） — `LogUtils.i` / `Debug.getCallers` 调试日志不移植 | 纯 OEM 调试设施，lib `Trace` 替代已够 |
| 6 | ✔️保持简化（清单即保持简化） — `Utilities.postAsyncCallback` 抽象不照搬 | lib 把 async 消息做成 `LooperExecutor.postAsync` 成员方法，调用点更短、行为等价 |
| 7 | ✔️保持简化（清单即保持简化） — `MultiDynamicAnimation` / `SpringHolder` / `SpringForce` / `SpringAnimReflectUtils` 暂不回移 | 弹簧积分层 + 事务写表层职责，硬塞进占位类会让 demo 目标失焦；待 v4 §8.2 续行/弹簧整体回移时一起处理（review 04 §4.2） |
| 8 | ✔️保持简化（清单即保持简化） — `CustomRectFSpringAnim` 的 6 自由度弹簧、RectTransformHelper、IconLayerUpdater 等不移植 | 同上，"弹簧积分层 + 事务写表层"职责在 demo 库定位之外 |
| 9 | ✔️保持简化（清单即保持简化） — `Companion.mAnimType` 字段不补 | 纯日志装饰；lib 的 Trace tag 已覆盖可观测性需求 |
| 10 | JVM 单测兜底（`handler == null` 退化）保留 | lib 自加契约，对单测友好；建议在 `LooperExecutor.kt` 类注释里明示"原厂无此路径，handler 永不 null，JVM 单测兜底是 lib 扩展" |

---

## ⑤ 与上一轮 review 01 的差异（便于回溯）

| 维度 | review 01 当时的判断 | 当前实际 | 状态 |
|---|---|---|---|
| 优先级 | lib 用 `THREAD_PRIORITY_URGENT_DISPLAY` (-8)，**bug 级差异** | lib 已改为字面量 `-19`（`thread/AnimationControlThread.kt:83`） | ✅ 已修 |
| `Executors` | 只保留 `MAIN_EXECUTOR` | 新增 `ANIM_CONTROL_EXECUTOR`（`thread/Executors.kt:23`） | ✅ 已加 |
| `AsyncAnimCallbacks` 派发 | 同步 `post`，可被 sync-barrier 阻塞 | `postAsync`（`LooperExecutor.kt:39-46`） | ✅ 已修 |
| `AsyncAnimCallbacks` 快照 | 直接迭代 `mutableListOf`，有 CME 风险 | `getListeners()` 先 `removeAll{null}` + `filterNotNull` | ✅ 已修 |
| `ActualEndAnimListener` | 缺失 | 已实现 + `onAnimActualEnd` 仅对 ActualEndAnimListener 派发 | ✅ 已修 |
| `OplusLooperExecutor` 四扩展 | 全部未复刻 | 仍未复刻（理由保留） | ✅ 保持 |
| `CustomRectFSpringAnim` 占位 | 18 行占位 | 仍是 18 行占位（review 04 §4.2 持续标记） | ⚠️ 未修（线程切换协议仍缺失） |
| `AsyncAnimCallbacks.mAnimType` | 未提及 | 砍掉（不在原 4 章节中） | 🆕 新增遗漏 |

---

## 附：证据速查

| 论断 | 证据 |
|---|---|
| `launcher.anim` 线程 + `-19` 优先级 | `com/oplus/basecommon/thread/OplusExecutors.java:95` |
| `ANIM_EXECUTOR$lambda$0` 装 SF-vsync + UX 标记 | `OplusExecutors.java:169-171`（import `android.animation.AnimationHandler` @ `:3`、`SfVsyncFrameCallbackProvider` @ `:5`） |
| `AsyncValueAnimator` 线程 marshal + AtomicBoolean 门控 | `AsyncValueAnimator.java:117-164`（三对 marshal），`:55, 57, 61, 69, 80`（init/门控），`:43-49, 98-100`（`Companion.ofFloat`） |
| `AsyncAnimCallbacks` 快照派发 + async 消息 + 双轨结束 | `AsyncAnimCallbacks.java:29-32, 81-97`（快照）、`:34-43, 111-122`（双轨）、`:154-162`（runOnMainThread） → `Utilities.java:631-637`（async 消息） |
| `AsyncAnimWrapper` 双通道调度 | `AsyncAnimWrapper.java:11-19`（`runOnAnimThread`/`runOnMainThread`） |
| `LooperExecutor` execute + getHandler/getLooper + shutdown 契约 | `LooperExecutor.java:12, 27-33, 35-45, 65-66, 71-79` |
| `OplusLooperExecutor` 四扩展 | `OplusLooperExecutor.java:38-44, 46-71, 73-75, 78-103` |
| `Executors` 全量 executor 注册 | `Executors.java:20-26, 54-56, 94-99` |
| `OplusExecutors` 全量 executor 注册 | `OplusExecutors.java:30-162`（含 `ANIM_EXECUTOR` @ `:95`） |
| `OplusAsyncSpringAnimWrapper` 骨架 + viewSupportAnimThread 分发 | `OplusAsyncSpringAnimWrapper.java:17, 19, 61-118` |
| `CustomRectFSpringAnim` 默认异步 + 线程纠偏 + AnimType 7 值 | `CustomRectFSpringAnim.java:252`（mStartAsync=true）、`:608-628, 752-776, 860-883`（三组 marshal）、`:778-786`（runOnMainThread）、`:115-123`（AnimType） |
| `MultiDynamicAnimation` 挂框架 AnimationHandler + 裸 delta + requestEnd | `MultiDynamicAnimation.java:21, 127, 152-159, 185-191` |
| `SpringHolder` 终点切换半步分裂 | `SpringHolder.java:117, 122, 125, 130` |
| `SpringAnimReflectUtils` 直接调 updateValues + isAtEquilibrium 反射 | `SpringAnimReflectUtils.java:33-43`（反射）, `:59-61`（直接） |
| `Utilities.postAsyncCallback` setAsynchronous(true) | `Utilities.java:631-637` |
| `OplusValueAnimator` 续行 + timeController + LinearInterpolator + param.copy | `OplusValueAnimator.java:89-120`（generateContinuationAnim）、`:104, 109-110, 117`（timeController/Linear）、`:105, 353-360`（param.copy） |
| `OplusValueAnimator.setInterpolator` 双写 param | `OplusValueAnimator.java:292-298` |

（全部证据经 ripgrep 明文通道穿透 DLP 加密获得，行号为 JADX 反编译文本行号；lib 源码经 git show 取得明文。）

## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **0e8a472** — runCatching 替换 try/catch（Trace/Executors/TaskStateChangeTimeOutListener）
- **60bd048** — Trace.STACK→ThreadLocal、AnimSeqTimeStamp reset 3 件
- **6bbe9a1** — AsyncAnimWrapper.runOnMainThread 被真用
- **2be173e** — HandlerTickScheduler 漂移补偿（该类后删，修正合入 ChoreographerTickScheduler 的 VSYNC 路径）
- **dbde195** — ChoreographerTickScheduler：launcher.anim 帧源对齐平台 Choreographer
- **215ecb5** — 删 Scheduled/HandlerTickScheduler，只留 ChoreographerTickScheduler（Android 平台 only）

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。

## 复核记录 v2（2026-09-09，独立逐条复核）

> 本条为**独立逐条复核**（对照 HEAD 086844e 代码逐条验证，不采信上文 commit 交叉索引标记）。复核范围 = §① 类对应表 15 行 + §②-A 32 + §②-B 10 + §②-C 9 + §③ 风险 15 + §④ 建议 17 + §⑤ 差异表 8 行 = **106 条**；全部条目均逐条读过当前代码（`anim/`、`thread/`、`core/`、`playback/`），需要时 Grep 对照 OPPO 源码。
>
> - **复核条目总数**：106
> - **结论不变**：93
> - **修正**：13
> - **修正明细**：
>   1. §①-10：旧“折中实现”→新“已删（215ecb5），职责并入 `core/ChoreographerTickScheduler`”。证据：`core/ChoreographerTickScheduler.kt:22-46`（lib 侧现唯一帧调度器）。
>   2. §①-12：旧“TickScheduler + ScheduledTickScheduler（JVM 实现）”→新“`ScheduledTickScheduler` 已删（215ecb5），唯一实现 `ChoreographerTickScheduler`”。证据：`core/TickScheduler.kt:15`、`core/ChoreographerTickScheduler.kt:22`。
>   3. §①-14：旧“timeController 接线是空实现”→新“已实现”。证据：`anim/OplusValueAnimator.kt:96-118`（`TimeControllerObjectAnimator`）、`:130-154`（`generateContinuationAnim`）。
>   4. §②-A.1-4：交叉引用修正 C-4→C-1（`ofFloat` 工厂遗漏项实为 §②-C-1）。
>   5. §②-A.2-12：交叉引用修正 B-3→B-4（`mAnimType` 砍掉实为 §②-B-4）。
>   6. §②-A.3-17：旧“`shutdown()` 契约缺失”→新“已补（086844e）”。证据：`thread/LooperExecutor.kt:57-69`（`shutdown()/shutdownNow()/awaitTermination()` 抛 `UnsupportedOperationException`）。
>   7. §②-A.5-24：帧源安装描述旧“`HandlerTickScheduler(Handler(looper))`”→新“`ChoreographerTickScheduler()`”（215ecb5/dbde195 换真 Choreographer VSYNC）。证据：`thread/AnimationControlThread.kt:63-71`。
>   8. §②-A.5-25：旧“shutdown 契约丢失”→新“线程永不 quit，shutdown 契约由 LooperExecutor 承载（086844e）”。
>   9. §②-B-1：简化项帧源旧“`HandlerTickScheduler` postDelayed”→新“`ChoreographerTickScheduler` 真 VSYNC（HandlerTickScheduler 已删）”。证据：`core/ChoreographerTickScheduler.kt:9-21`、`thread/AnimationControlThread.kt:35-41`。
>   10. §②-C-2：注释引用行号 `AnimationControlThread.kt:44`→`:43-47`（指向 `04-frame-spring-continuation.md` §4.2-2），并注明属注释/代码脱节、不改码。
>   11. §②-C-3：(b) 旧“`shutdown()` 契约未保留”→新“086844e 已补（`LooperExecutor.kt:57-69`）”；仍缺 `AbstractExecutorService` 继承与 `getHandler/getLooper/getThread/setThreadPriority` 访问器。
>   12. §③-12：旧“⚠️未修复（缺 shutdown 契约）”→新“⚠️部分修复（086844e：shutdown 永不-quit 契约已补；仍非 ExecutorService、缺访问器）”。
>   13. §④4.1-6：旧“⚠️未修复（~10 行访问器/shutdown 契约）”→新“⚠️部分未修复（shutdown 契约已补，访问器仍缺）”。→新“✅已修复（64d3bab：3 个访问器已补）”。
>
> 另：§0、§⑤、§②-B-2、§③-6 等处的失效路径/行号已按当前包结构（`anim/` `thread/` `core/` `playback/`）与 HEAD 行号顺带刷新（不计入修正数）。其余条目（§②-A 全部设计点、§③-1/2/3/4/5/6/7/8/9/10/11/13/14/15、§④ 其余行）经复核与当前代码一致，无状态变化。

> **64d3bab 修复标记**：
>   14. §④4.1-2（ofFloat 工厂）：⚠️未修复 → ✅已修复（64d3bab：`anim/AsyncValueAnimator.kt` companion object ofFloat 工厂）
>   15. §④4.1-6（LooperExecutor 访问器）：⚠️部分未修复 → ✅已修复（64d3bab：`thread/LooperExecutor.kt` getHandler/getLooper/getTargetThread 访问器）

