# -*- coding: utf-8 -*-
import sys

path = 'vs-oppo-01-async-thread.md'
raw = open(path, 'rb').read().decode('utf-8')
crlf = '\r\n' in raw
text = raw.replace('\r\n', '\n')

pairs = [
# ── header ──
("（Kotlin；含 `launcher/async`、`launcher/animthread`、`core/anim`、`core/scheduler`、`util/Trace`，外加 `launcher/continuation` 的续行入口）",
 "（Kotlin；e62dbff 包重组后为 `anim`、`thread`、`core`、`playback`、`seq`、`control`、`manager` 七个包）"),
# ── §0 table ──
("`-19` 字面量（与原厂一致） | `AnimationControlThread.kt:78` |",
 "`-19` 字面量（与原厂一致） | `thread/AnimationControlThread.kt:83` |"),
("新增 `ANIM_CONTROL_EXECUTOR`（绑定 launcher.anim） | `Executors.kt:17-19` |",
 "新增 `ANIM_CONTROL_EXECUTOR`（绑定 launcher.anim） | `thread/Executors.kt:20-23` |"),
("`postAsync`（`Message.setAsynchronous(true)`） | `LooperExecutor.kt:39-46`、`AsyncAnimCallbacks.kt:62-78` |",
 "`postAsync`（`Message.setAsynchronous(true)`） | `thread/LooperExecutor.kt:49-54`、`anim/AsyncAnimCallbacks.kt:97-100` |"),
("`getListeners()` 先 `removeAll{null}` 再 `filterNotNull` 快照 | `AsyncAnimCallbacks.kt:84-88` |",
 "`getListeners()` 先 `removeAll{null}` 再 `filterNotNull` 快照 | `anim/AsyncAnimCallbacks.kt:76-79` |"),
("已实现（`onAnimActualEnd` 仅对 ActualEndAnimListener 派发） | `ActualEndAnimListener.kt:23-27`、`AsyncAnimCallbacks.kt:65-77` |",
 "已实现（`onAnimActualEnd` 仅对 ActualEndAnimListener 派发） | `anim/ActualEndAnimListener.kt:23-26`、`anim/AsyncAnimCallbacks.kt:61-70` |"),
("对齐原厂 `OplusAsyncSpringAnimWrapper`） | `AsyncSpringAnim.kt:18-55` |",
 "对齐原厂 `OplusAsyncSpringAnimWrapper`） | `anim/AsyncSpringAnim.kt:21-50` |"),
# ── ① table global path fixes ──
("`launcher/async/AsyncValueAnimator.kt`", "`anim/AsyncValueAnimator.kt`"),
("`launcher/async/AsyncAnimCallbacks.kt`", "`anim/AsyncAnimCallbacks.kt`"),
("`launcher/async/ActualEndAnimListener.kt`", "`anim/ActualEndAnimListener.kt`"),
("`launcher/async/LooperExecutor.kt`", "`thread/LooperExecutor.kt`"),
("`launcher/async/Executors.kt`", "`thread/Executors.kt`"),
("`launcher/async/AsyncSpringAnim.kt`", "`anim/AsyncSpringAnim.kt`"),
("`launcher/async/CustomRectFSpringAnim.kt`", "`anim/CustomRectFSpringAnim.kt`"),
("`launcher/animthread/AsyncAnimWrapper.kt`", "`thread/AsyncAnimWrapper.kt`"),
("`launcher/animthread/AnimationControlThread.kt`", "`thread/AnimationControlThread.kt`"),
("`core/anim/AnimationHandler.kt`", "`core/AnimationHandler.kt`"),
("`util/Trace.kt`", "`core/Trace.kt`"),
("`launcher/continuation/OplusValueAnimator.kt`", "`anim/OplusValueAnimator.kt`"),
("`launcher/continuation/RecordInputInterpolator.kt`", "`anim/RecordInputInterpolator.kt`"),
# ① row 10/12/14 content
("| 10 | `launcher/animthread/HandlerTickScheduler.kt` | **无直接对应类**；替代 `core/AnimationHandler` 的 `FrameCallbackProvider14/16` + 框架 `AnimationHandler.setProvider(new SfVsyncFrameCallbackProvider())`（`OplusExecutors.java:170`） | 折中实现（详 §②-B） |",
 "| 10 | `core/ChoreographerTickScheduler.kt`（215ecb5 后唯一 TickScheduler 实现；`HandlerTickScheduler`/`ScheduledTickScheduler` 已删） | 原厂 (a) `FrameCallbackProvider16`（公开 Choreographer 真 VSYNC）语义对齐；框架 `AnimationHandler.setProvider(new SfVsyncFrameCallbackProvider())`（`OplusExecutors.java:170`，@hide）不移植 | 公开 API 等价替代（详 §②-B） |"),
("| 12 | `core/scheduler/TickScheduler.kt` + `ScheduledTickScheduler.kt` | `vendored AnimationFrameCallbackProvider` 接口 | 抽象合并 + JVM 实现（详 §②-B） |",
 "| 12 | `core/TickScheduler.kt` + `core/ChoreographerTickScheduler.kt`（`ScheduledTickScheduler` 已删） | `vendored AnimationFrameCallbackProvider` 接口 | 抽象合并 + 公开 Choreographer 实现（详 §②-B） |"),
("委托骨架对齐；timeController 接线是空实现（review 04 §2.3-3） |",
 "委托骨架对齐；timeController 接线已落地（`TimeControllerObjectAnimator.setTarget` → `CURRENT_FRACTION` → `setCurrentFraction`，`anim/OplusValueAnimator.kt:98-118, 158-167`） |"),
# ── ②-A refs ──
("`AsyncValueAnimator.kt:42-53`（`marshal{}` 内联或 `executor.execute`），结构对齐",
 "`anim/AsyncValueAnimator.kt:47-55`（`marshal{}` 内联或 `executor.execute`），结构对齐"),
("`AsyncValueAnimator.kt:17`（`var executor = Executors.MAIN_EXECUTOR`）",
 "`anim/AsyncValueAnimator.kt:21`（`var executor = Executors.MAIN_EXECUTOR`）"),
("`AsyncValueAnimator.kt:22`（`AtomicBoolean(false)`）、`:25-37`（同名 gate）",
 "`anim/AsyncValueAnimator.kt:26`（`AtomicBoolean(false)`）、`:28-42`（同名 gate）"),
("`AsyncAnimCallbacks.kt:29-32`（同名逻辑）", "`anim/AsyncAnimCallbacks.kt:41-44`（同名逻辑）"),
("`AsyncAnimCallbacks.kt:25-27`（同名逻辑）", "`anim/AsyncAnimCallbacks.kt:37-39`（同名逻辑）"),
("`AsyncAnimCallbacks.kt:107`（`animationId` 在 dispatch 内统一赋值给 adapter）",
 "`anim/AsyncAnimCallbacks.kt:65, 86`（`animationId` 在 dispatch 内统一赋值给 adapter）"),
("`AsyncAnimCallbacks.kt:84-88`（`getListeners = removeAll{null} + filterNotNull`），等价",
 "`anim/AsyncAnimCallbacks.kt:76-79`（`getListeners = removeAll{null} + filterNotNull`），等价"),
("`AsyncAnimCallbacks.kt:112-117`（`runOnMainThread`）+ `:69-72, 100-103`（两处调用）",
 "`anim/AsyncAnimCallbacks.kt:97-100`（`runOnMainThread`）+ `:62, 84`（两处调用）"),
("`AsyncAnimCallbacks.kt:114-117`（`exec.postAsync(action)`）→ `thread/LooperExecutor.kt:39-46`（`Message.obtain(h) { action() }.isAsynchronous = true`），行为一致",
 "`anim/AsyncAnimCallbacks.kt:97-100`（`exec.postAsync(action)`）→ `thread/LooperExecutor.kt:49-54`（`Message.obtain(h) { action() }.isAsynchronous = true`），行为一致"),
("`AsyncAnimCallbacks.kt:65-77`（同款 instanceof 判定），等价",
 "`anim/AsyncAnimCallbacks.kt:61-70`（同款 instanceof 判定），等价"),
("`thread/LooperExecutor.kt:25-29`（`isCurrentThread` → 直接 `action()`；否则 `post`），等价",
 "`thread/LooperExecutor.kt:28-34`（`isCurrentThread` → 直接 `action()`；否则 `post`），等价"),
("| 19 | **新增**：`postAsync`（`Message.setAsynchronous(true)`） | 无 | `thread/LooperExecutor.kt:39-46`，",
 "| 19 | **新增**：`postAsync`（`Message.setAsynchronous(true)`） | 无 | `thread/LooperExecutor.kt:49-54`,"),
("`thread/AsyncAnimWrapper.kt:21-23`（`Executors.ANIM_CONTROL_EXECUTOR.execute`），等价",
 "`thread/AsyncAnimWrapper.kt:20-22`（`Executors.ANIM_CONTROL_EXECUTOR.execute`），等价"),
("`thread/AsyncAnimWrapper.kt:27-29`（`Executors.MAIN_EXECUTOR.execute`），等价",
 "`thread/AsyncAnimWrapper.kt:25-27`（`Executors.MAIN_EXECUTOR.execute`），等价"),
("`thread/AnimationControlThread.kt:73`（`THREAD_NAME = \"launcher.anim\"`）",
 "`thread/AnimationControlThread.kt:76`（`THREAD_NAME = \"launcher.anim\"`）"),
("`thread/AnimationControlThread.kt:78`（`private const val PRIORITY = -19`）——**已修正 review 01 §②C-1 的 bug**",
 "`thread/AnimationControlThread.kt:83`（`private const val PRIORITY = -19`）——**已修正 review 01 §②C-1 的 bug**"),
("`thread/AnimationControlThread.kt:48-55`（`onLooperPrepared`：`AnimationHandler.installThreadScheduler(HandlerTickScheduler(Handler(looper)))` + `Process.setThreadPriority(myTid, PRIORITY)`），结构等价",
 "`thread/AnimationControlThread.kt:63-71`（`onLooperPrepared`：`AnimationHandler.installThreadScheduler(ChoreographerTickScheduler())` + `Process.setThreadPriority(myTid, PRIORITY)` 兜底），结构等价"),
("`thread/AnimationControlThread.kt:81-83`（`internal val instance: AnimationControlThread by lazy(SYNCHRONIZED) { AnimationControlThread() }`，`init { start() }` 在 companion lazy 里）——**结构等价；shutdown 契约丢失**（详 §②-C-3）",
 "`thread/AnimationControlThread.kt:85-88`（`internal val instance: AnimationControlThread by lazy(SYNCHRONIZED) { AnimationControlThread() }`，`init { start() }` 在 companion lazy 里）——**结构等价；shutdown 契约已由 `LooperExecutor` 恢复**（详 §②-C-3）"),
("`anim/AsyncSpringAnim.kt:18-19`（`supportAnimThread: Boolean`） + `:29-46`（五个生命周期方法内 `if (supportAnimThread) runOnAnimThread { action() } else action()`），等价",
 "`anim/AsyncSpringAnim.kt:21-23`（`supportAnimThread: Boolean`） + `:26-50`（五个生命周期方法内 `if (supportAnimThread) runOnAnimThread { action() } else action()`），等价"),
("`anim/AsyncSpringAnim.kt:33-35`（同名方法）", "`anim/AsyncSpringAnim.kt:32-34`（同名方法）"),
("`anim/AsyncSpringAnim.kt:38-44`（`addEndListener { anim, canceled, value, velocity -> runOnMainThread { listener.onAnimationEnd(...) } }`），**功能增强，非对齐**（详 §②-B-4）",
 "`anim/AsyncSpringAnim.kt:37-42`（`addEndListener { anim, canceled, value, velocity -> runOnMainThread { listener.onAnimationEnd(...) } }`），**功能增强，非对齐**（详 §②-B-4）"),
("`anim/AsyncSpringAnim.kt:18`（`real: SpringAnimation`），等价",
 "`anim/AsyncSpringAnim.kt:22`（`real: SpringAnimation`），等价"),
("`thread/Executors.kt:11`（`mainHandlerOrNull()` 包装 `Looper.getMainLooper()`），等价",
 "`thread/Executors.kt:20`（`mainHandlerOrNull()` 包装 `Looper.getMainLooper()`），等价"),
("`thread/Executors.kt:14`（`ANIM_CONTROL_EXECUTOR = LooperExecutor(Handler(AnimationControlThread.instance.looper))`），等价",
 "`thread/Executors.kt:23`（`ANIM_CONTROL_EXECUTOR = LooperExecutor(Handler(AnimationControlThread.instance.looper))`），等价"),
("`thread/Executors.kt:11-14`（仅 2 个）——**简化**（详 §②-B-5）",
 "`thread/Executors.kt:17-23`（仅 2 个）——**简化**（详 §②-B-5）"),
# ── ②-B ──
("→ `HandlerTickScheduler`（绑本线程 Looper 的 `postDelayed` 帧循环） | `OplusExecutors.java:5` import `com.android.internal.graphics.SfVsyncFrameCallbackProvider` + `:170` `setProvider(...)` | 框架 @hide API，AOSP 公开层无法直接调；lib 注释（`thread/AnimationControlThread.kt:34-39`）明示",
 "→ `ChoreographerTickScheduler`（公开 Choreographer，真 VSYNC；`core/ChoreographerTickScheduler.kt:22-46`） | `OplusExecutors.java:5` import `com.android.internal.graphics.SfVsyncFrameCallbackProvider` + `:170` `setProvider(...)` | 框架 @hide API，AOSP 公开层无法直接调；lib 注释（`thread/AnimationControlThread.kt:35-47`）明示"),
("lib 退化为在 `onLooperPrepared` 再设一次 `Process.setThreadPriority(tid, -19)` 兜底（`thread/AnimationControlThread.kt:53`）",
 "lib 退化为在 `onLooperPrepared` 再设一次 `Process.setThreadPriority(tid, -19)` 兜底（`thread/AnimationControlThread.kt:70`）"),
("lib 注释（`anim/CustomRectFSpringAnim.kt:3-9`）明示\"实际动画逻辑由 SpringAnimation 实现\"",
 "lib 注释（`anim/CustomRectFSpringAnim.kt:3-10`）明示\"实际动画逻辑由 SpringAnimation 实现\""),
("lib 把 async 消息做成 executor 的成员方法（`thread/LooperExecutor.kt:39-46`），调用点更短",
 "lib 把 async 消息做成 executor 的成员方法（`thread/LooperExecutor.kt:49-54`），调用点更短"),
("`thread/LooperExecutor.kt:17-20, 42-50` 注释明示\"测试便利\"；`thread/Executors.kt:9-10` 用 `runCatching` 容错",
 "`thread/LooperExecutor.kt:19-20, 37, 50` 注释明示\"测试便利\"；`thread/Executors.kt:25-26` 用 `runCatching` 容错"),
# ── ②-C ──
("调用方迁移成本：`AppLaunchAnimUtil` 等按原厂习惯用 `ofFloat(isAsync, …)` 选同步/异步实现的入口未移植，调用点全要改写",
 "调用方迁移成本：`AppLaunchAnimUtil` 等按原厂习惯用 `ofFloat(isAsync, …)` 选同步/异步实现的入口未移植，调用点全要改写。**注**：`anim/OplusValueAnimator.kt:122-127` 已新增 `ofFloat(isAsync, vararg values)` 工厂（`internal class` 上，模块外不可见），`AsyncValueAnimator` 公开 companion 仍缺"),
("(b) `shutdown()` 抛异常这一\"永不 quit\"契约也未保留——lib `LooperExecutor` 是普通 class，可被 GC，无 `shutdown()` 方法；(c)",
 "(b) `shutdown()` 抛异常的\"永不 quit\"契约**已恢复**（`thread/LooperExecutor.kt:57-69`：`shutdown/shutdownNow/awaitTermination` 抛 `UnsupportedOperationException`，`isShutdown/isTerminated` 恒 false）；(c)"),
("lib 传真实 animator（`anim/AsyncValueAnimator.kt:25-37`），listener 收到的是 `this`",
 "lib 传真实 animator（`anim/AsyncValueAnimator.kt:28-42`），listener 收到的是 `this`"),
("lib 把 init 逻辑折进 `onLooperPrepared`（`thread/AnimationControlThread.kt:48-55`）语义等价",
 "lib 把 init 逻辑折进 `onLooperPrepared`（`thread/AnimationControlThread.kt:63-71`）语义等价"),
("lib 退化为 androidx `SpringAnimation`（`anim/AsyncSpringAnim.kt:18`）",
 "lib 退化为 androidx `SpringAnimation`（`anim/AsyncSpringAnim.kt:22`）"),
# ── ③ ──
("lib 的 `HandlerTickScheduler`（`animthread/HandlerTickScheduler.kt:65-77`）复刻了\"空则停\"（`:74-77`），但**默认的 `ScheduledTickScheduler`（JVM 仿真路径）是常驻定频循环，无 callback 也继续空转**（review 04 §2.3-7）。",
 "lib 的 `HandlerTickScheduler`（已删）曾复刻\"空则停\"，但当时的默认 `ScheduledTickScheduler`（已删）是常驻定频循环，无 callback 也继续空转（review 04 §2.3-7）。**当前**：215ecb5 后只剩 `core/ChoreographerTickScheduler`，`core/ChoreographerTickScheduler.kt:41-45` 实现\"空则停\"。"),
("lib `AnimationHandler.addAnimationFrameCallback` 无 delay 形参（`AnimationHandler.kt:51` 注释明示\"无 delay\"）。",
 "lib `AnimationHandler.addAnimationFrameCallback` 无 delay 形参（`core/AnimationHandler.kt:49-59`，签名即无 delay）。"),
("lib `HandlerTickScheduler`（`animthread/HandlerTickScheduler.kt:67-80`）是 `postDelayed(16ms)` 自走时钟：固定 60Hz，无 vsync 对齐（90/120Hz 屏帧率错配）。",
 "lib 现用 `core/ChoreographerTickScheduler`（`:22-46`）：公开 `Choreographer` 真 VSYNC，帧间隔随系统 `ValueAnimator.getFrameDelay()`（`:53`，120Hz 屏自动 ~8ms），不再错配；仅剩 SF-vsync（@hide）未移植。"),
("lib 仅做 `Process.setThreadPriority(myTid, PRIORITY)` 兜底（`thread/AnimationControlThread.kt:53`），无 OPPO 私有调度增强。",
 "lib 仅做 `Process.setThreadPriority(myTid, PRIORITY)` 兜底（`thread/AnimationControlThread.kt:70`），无 OPPO 私有调度增强。"),
("`LooperExecutor.postAsync` 用 `Message.setAsynchronous(true)`（`thread/LooperExecutor.kt:39-46`），对齐",
 "`LooperExecutor.postAsync` 用 `Message.setAsynchronous(true)`（`thread/LooperExecutor.kt:49-54`），对齐"),
("JVM 单测下 `handler == null` 走\"就地执行\"（`thread/LooperExecutor.kt:42`），与真机\"async 消息\"语义不同",
 "JVM 单测下 `handler == null` 走\"就地执行\"（`thread/LooperExecutor.kt:50`），与真机\"async 消息\"语义不同"),
("lib 收到真实 animator（`anim/AsyncValueAnimator.kt:25-37`）。",
 "lib 收到真实 animator（`anim/AsyncValueAnimator.kt:28-42`）。"),
("lib 仅 `MAIN_EXECUTOR` + `ANIM_CONTROL_EXECUTOR`（`thread/Executors.kt:11-14`）。",
 "lib 仅 `MAIN_EXECUTOR` + `ANIM_CONTROL_EXECUTOR`（`thread/Executors.kt:17-23`）。"),
("> **⚠️未修复（~10 行；LooperExecutor 非 ExecutorService）**\n12. **`LooperExecutor` 不再 `extends AbstractExecutorService`、缺访问器、缺 `shutdown()` 契约**\n    - 详 §②-C-3。\n    - 影响：业务侧需要 ExecutorService 持有或 shutdown 调用时无法直接替换；本演示库用不到。",
 "> **⚠️部分修复（shutdown 永不 quit 契约已补 `thread/LooperExecutor.kt:57-69`；仍缺访问器与 ExecutorService 继承）**\n12. **`LooperExecutor` 不再 `extends AbstractExecutorService`、缺访问器；`shutdown()` 契约已恢复**\n    - 详 §②-C-3。\n    - 影响：业务侧需要 ExecutorService 类型持有或访问底层 Looper 时无法直接替换；本演示库用不到。"),
("lib 强制 marshal 回主线程（`anim/AsyncSpringAnim.kt:38-44`），业务代码不再需要自己判线程。",
 "lib 强制 marshal 回主线程（`anim/AsyncSpringAnim.kt:37-42`），业务代码不再需要自己判线程。"),
# ── ④ ──
("**统一两个 scheduler 的\"空则停\"语义**（`ScheduledTickScheduler.kt:56-61` 加空转保护：tick 时 callbacks 为空则自动 stop，下次 `postFrameCallback` 时 restart）",
 "**统一两个 scheduler 的\"空则停\"语义**（已落地为删库方案：215ecb5 删 `Scheduled/HandlerTickScheduler`，唯一实现 `core/ChoreographerTickScheduler.kt:41-45` 空则停）"),
("⚠️未修复（~5 行 ofFloat(isAsync) 工厂，demo 无调用点） — **`AsyncValueAnimator.Companion.ofFloat(isAsync, …)` 工厂**（`anim/AsyncValueAnimator.kt` 加 `companion object`） | 5 行，对齐 `AppLaunchAnimUtil.java:443` 等调用点迁移",
 "⚠️未修复（`anim/OplusValueAnimator.kt:122-127` 已有 internal `ofFloat(isAsync,…)`，`AsyncValueAnimator` 公开 companion 仍缺，demo 无调用点） — **`AsyncValueAnimator.Companion.ofFloat(isAsync, …)` 工厂**（`anim/AsyncValueAnimator.kt` 加 `companion object`） | 5 行，对齐 `AppLaunchAnimUtil.java:433` 等调用点迁移"),
("⚠️未修复（~10 行访问器/shutdown 契约） — **`LooperExecutor` 补 `getHandler()` / `getLooper()` / `getThread()` / `setThreadPriority(int)` 访问器**（对齐 `LooperExecutor.java:35-66`） | 业务需要访问底层 Looper 时必备；~10 行；",
 "⚠️部分修复（shutdown/shutdownNow/awaitTermination 契约已补 `thread/LooperExecutor.kt:57-69`；访问器仍缺） — **`LooperExecutor` 补 `getHandler()` / `getLooper()` / `getThread()` / `setThreadPriority(int)` 访问器**（对齐 `LooperExecutor.java:35-66`） | 业务需要访问底层 Looper 时必备；~10 行；"),
# ── ⑤ ──
("lib 已改为字面量 `-19`（`thread/AnimationControlThread.kt:78`）",
 "lib 已改为字面量 `-19`（`thread/AnimationControlThread.kt:83`）"),
("新增 `ANIM_CONTROL_EXECUTOR`（`thread/Executors.kt:14`）",
 "新增 `ANIM_CONTROL_EXECUTOR`（`thread/Executors.kt:23`）"),
("`postAsync`（`thread/LooperExecutor.kt:39-46`）", "`postAsync`（`thread/LooperExecutor.kt:49-54`）"),
]

missing = []
for old, new in pairs:
    n = text.count(old)
    if n != 1:
        missing.append((n, old[:80]))
    else:
        text = text.replace(old, new)

if missing:
    for n, s in missing:
        print(f'MISS x{n}: {s}')
    sys.exit(1)

# append v2 record
v2 = """
## 复核记录 v2（2026-09-09，独立逐条复核）
- 复核条目总数：约 60（§0 表 6 行 + §① 表 15 行 + §②-A 32 行 + §②-B 10 行 + §②-C 9 行 + §③ 15 条 + §④ 17 条，含交叉引用去重）；结论不变：54 条；修正：6 条（另批量更正 e62dbff 包重组后失效的 lib 路径/行号约 50 处）
- 修正明细：
  - §②-C-3 / §③-12 / §④-1.6（LooperExecutor API 子集）：⚠️未修复 → ⚠️部分修复，证据：`thread/LooperExecutor.kt:57-69` 已补 `shutdown/shutdownNow/awaitTermination`（抛 UnsupportedOperationException）+ `isShutdown/isTerminated`，"永不 quit"契约恢复；`getHandler/getLooper/getThread/postDelayed/setThreadPriority` 与 `extends AbstractExecutorService` 仍缺
  - §②-C-1 / §④-1.2（ofFloat 工厂）：⚠️未修复（维持，描述修正），证据：`anim/AsyncValueAnimator.kt` 仍无 companion；但 `anim/OplusValueAnimator.kt:122-127` 已新增 internal `ofFloat(isAsync,…)`，条目补充该事实
  - §①-10/12、§②-B-1、§③-1/3、§④-1.1（帧调度器三处）：`Handler/ScheduledTickScheduler` 已删（215ecb5），唯一实现 `core/ChoreographerTickScheduler.kt:22-46` 走公开 Choreographer 真 VSYNC + 空则停（`:41-45`）；§③-1 ❌已过期 维持、§③-3 ✔️保持简化 维持，但正文"postDelayed 自走时钟 60Hz 错配"描述已过时，改写为现状
  - §①-14（OplusValueAnimator timeController 空实现）：结论过期 → 修正，证据：`anim/OplusValueAnimator.kt:98-118, 158-167` `TimeControllerObjectAnimator.setTarget` → `CURRENT_FRACTION` → `setCurrentFraction` 接线已落地
  - §②-A-5-25（shutdown 契约丢失）：结论过期 → 修正为"契约已由 LooperExecutor 恢复"
  - §④-1.2 原厂调用点行号修正：`AppLaunchAnimUtil.java:443` → `:433`（`AsyncValueAnimator.INSTANCE.ofFloat(zIsAdaptiveAnimation, …)`）
- 其余条目（§③-2/4~11/13/15、§②-B/C 其余、§④-2 全部）经逐条对照当前代码，结论与标记均与代码一致，未改动。
"""
text = text.rstrip('\n') + '\n' + v2

out = text.replace('\n', '\r\n') if crlf else text
open(path, 'wb').write(out.encode('utf-8'))
print('OK doc01')
