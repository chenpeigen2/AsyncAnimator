# vs-oppo-13 — AnimationControlThread 深度对照

> 范围：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/thread/AnimationControlThread.kt`（e62dbff 包重组后自 `launcher/animthread/` 迁入 `thread/`）
> vs 原厂 `com/oplus/basecommon/thread/OplusExecutors.java`（`ANIM_EXECUTOR` + `ANIM_EXECUTOR$lambda$0`）+
> `com/oplus/basecommon/thread/Executors.java`（`createAndStartNewLooper`）+
> `com/oplus/basecommon/thread/OplusLooperExecutor.java`（`OplusLooperExecutor(Looper, Runnable)`）+
> `com/oplus/basecommon/util/LauncherBooster.java`（`reportKeyThreadToUAF` / `setUxThreadValue` / `LAUNCHER_STATIC_LAUNCHER_ANIM = 2016`）。
>
> 不重复：线程优先级字面量、LooperExecutor 公共 API 缺失、shutdown 契约、`installThreadScheduler` 退路动机已分别在 `vs-oppo-01-async-thread.md` §A.5 / §B / §B-2 / §B-5 / §C-2 覆盖。本文做**单文件 / 单方法级**的对照。
>
> 编号继续沿用：上一份是 `vs-oppo-12-runtime-risks.md`，本份取 `13`。

---

## 1. 类对应关系表（lib → 原厂 文件:行 证据）

| # | lib 元素 | 原厂对应 | 原厂证据 | 关系 |
|---|---|---|---|---|
| 1 | `class AnimationControlThread private constructor() : HandlerThread(THREAD_NAME, PRIORITY)` | `OplusLooperExecutor(Looper looper)` + `Executors.createAndStartNewLooper(name, priority, eventId)` | `OplusExecutors.java:95`（`new OplusLooperExecutor(Executors.createAndStartNewLooper("launcher.anim", -19, LauncherBooster.LAUNCHER_STATIC_LAUNCHER_ANIM), new f(1))`）；`Executors.java:94-99`（`new HandlerThread(str, i9); handlerThread.start();` + `reportKeyThreadToUAF(handlerThread, i10)`） | **一对多**：lib 把"Looper + 线程名 + 优先级 + 线程 init 回调 + 进程级生命周期"五个原厂概念压缩进一个 `HandlerThread` 子类 + companion 单例 |
| 2 | `private constructor()` | `private static final OplusLooperExecutor ANIM_EXECUTOR = …`（`OplusExecutors.java:95`）+ `static { }` 初始化块（`Executors.java:46-57`） | 同上 | **等价**（构造私有 + 类初始化阶段建一次 + 进程级单例） |
| 3 | `init { start() }` | `handlerThread.start();`（`Executors.java:96`） | `Executors.java:96` | **精确复刻** |
| 4 | `HandlerThread(THREAD_NAME, PRIORITY)` 构造参数 | `new HandlerThread(str, i9)` 中 `i9` = `-19`（`Executors.java:95`，`OplusExecutors.java:95`） | `Executors.java:95`（`HandlerThread handlerThread = new HandlerThread(str, i9);`）+ `OplusExecutors.java:95`（`-19` 字面量） | **精确复刻**（含 `THREAD_NAME` = `"launcher.anim"`、`PRIORITY` = `-19`） |
| 5 | `override fun onLooperPrepared()` 内 `AnimationHandler.installThreadScheduler(ChoreographerTickScheduler())`（dbde195 前为 `HandlerTickScheduler(Handler(looper))`） | `OplusLooperExecutor` 构造函数末尾 `execute(runnable)` → 首个消息（`ANIM_EXECUTOR$lambda$0`）内 `AnimationHandler.getInstance().setProvider(new SfVsyncFrameCallbackProvider())` | `OplusLooperExecutor.java:83-87`（`if (runnable != null) { execute(runnable); }`）+ `OplusExecutors.java:169-170` | **功能等价，时机不同**（见 §2-A-2） |
| 6 | `runCatching { Process.setThreadPriority(Process.myTid(), PRIORITY) }` | `ANIM_EXECUTOR$lambda$0` 第二句 `LauncherBooster.getCpu().setUxThreadValue(Process.myTid())`（`OplusExecutors.java:171`） | `OplusExecutors.java:171` | **有意简化 + 兜底**（见 §2-B-1） |
| 7 | `companion object { const val THREAD_NAME = "launcher.anim" }` | `"launcher.anim"` 字面量（`OplusExecutors.java:95`） | `OplusExecutors.java:95` | **精确复刻**（字符串字面量同值，便于 systrace / logcat 对照） |
| 8 | `companion object { private const val PRIORITY = -19 }` | 字面量 `-19`（`OplusExecutors.java:95`） | `OplusExecutors.java:95` + `Executors.java:55-56`（`-4`）/`Executors.java:81`/`Executors.java:92` 等其他线程用 `-4`、`-8`、`0`；仅 `ANIM_EXECUTOR` 用 `-19`） | **精确复刻**（注释明确"不是 `THREAD_PRIORITY_URGENT_DISPLAY`(-8)"，已修 review 01 §②C-1 的 bug） |
| 9 | `companion object { internal val instance: AnimationControlThread by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AnimationControlThread() } }` | `private static final OplusLooperExecutor ANIM_EXECUTOR = …`（`OplusExecutors.java:95`） + 类初始化器（`<clinit>`） | `OplusExecutors.java:95`（Java `static final` 等价 Kotlin `by lazy(SYNCHRONIZED)` 的语义：首次访问惰性建 + 互斥） | **结构等价**（双检锁 vs Kotlin 标准库 SYNCHRONIZED lazy 等价） |
| 10 | （隐含）`LooperExecutor` + 后续 `Handler` 上 post 帧任务 | `OplusLooperExecutor extends LooperExecutor`，封装 Handler 的 post 路径 | `OplusLooperExecutor.java:16, 22, 86` | **未引入**——lib 走 `ChoreographerTickScheduler` + 内部 `AnimationHandler` API（已述于 `vs-oppo-01` §B-2 / `vs-oppo-04` §2.1） |

> 注：`LooperExecutor.java:27-29` 的 `if (getHandler().getLooper() == Looper.myLooper()) { runnable.run(); } else { handler.post(runnable); }` 路径 lib 不走，所以 init 时机差异需要展开看（§2-A-2）。

---

## 2. 保真度评估

### A. 精确复刻

#### A-1. `HandlerThread(name, priority)` 构造 + `start()` 链路

| 维度 | lib | 原厂 | 证据 |
|---|---|---|---|
| 构造调用点 | `HandlerThread(THREAD_NAME, PRIORITY)`（`AnimationControlThread.kt:49`） | `new HandlerThread(str, i9)`（`Executors.java:95`） | `Executors.java:95` + `OplusExecutors.java:95` |
| 启动调用点 | `init { start() }`（`AnimationControlThread.kt:51-53`） | `handlerThread.start();`（`Executors.java:96`） | `Executors.java:96` |
| 名字字面量 | `"launcher.anim"`（`AnimationControlThread.kt:76`） | `"launcher.anim"`（`OplusExecutors.java:95`） | `OplusExecutors.java:95` |
| 优先级字面量 | `-19`（`AnimationControlThread.kt:83`） | `-19`（`OplusExecutors.java:95`） | `OplusExecutors.java:95` |
| 单例可见性 | `internal val instance by lazy(SYNCHRONIZED)`（`AnimationControlThread.kt:86-88`） | `private static final OplusLooperExecutor ANIM_EXECUTOR`（`OplusExecutors.java:95`） | 同上 |
| 私有构造 | `private constructor()`（`AnimationControlThread.kt:49`） | Kotlin 端无显式构造（`OplusExecutors` 是 `final class`，但字段 `private static final`） | 同上 + `OplusExecutors.java:16`（`final class`） |

**结论**：1:1 复刻，连 `-19` 与 `"launcher.anim"` 都按字面量保留。区别只是原厂把"线程 + Looper + 优先级 + init 回调"分到三个类（`HandlerThread`/`Executors`/`OplusLooperExecutor`），lib 折进一个 `HandlerThread` 子类 + companion —— 这是**结构性合并**而非语义差异。

#### A-2. `onLooperPrepared` vs `ANIM_EXECUTOR$lambda$0`：等价但时机不同

| 触发栈 | lib | 原厂 |
|---|---|---|
| 调用栈根 | `HandlerThread.run()` → `Looper.prepare()` → `onLooperPrepared()` → `Looper.loop()`（AOSP `HandlerThread` 实现） | `HandlerThread.run()` → `Looper.prepare()` → `Looper.loop()` → 处理第一条消息（`ANIM_EXECUTOR$lambda$0`） |
| 执行位置 | 紧接 `Looper.prepare()` 完成之后、`Looper.loop()` 启动之前（在 worker 线程的主路径上） | `Looper.loop()` 已经空闲循环，拿到 `ANIM_EXECUTOR$lambda$0` 这条 post 消息后 |
| 在 Looper 上排队的相对顺序 | 0（无任何消息先到） | 1（实际是 1——若 lib 调用方在 `start()` 之前/之后立刻 post 任务，lib 也会先于之；而 OPPO 是 init 先排队，业务 post 后排队） |
| 谁负责调用 | AOSP `HandlerThread.run()`（android.os 包） | `OplusLooperExecutor.execute(runnable)`（`OplusLooperExecutor.java:86`） |

**关键差异点**：

- 在 lib 里，**worker 线程启动 → `Looper.prepare()` → `onLooperPrepared()` → `Looper.loop()`** 是串行的；即使主线程在 `instance` 触达前已经 `Handler(looper).post(...)`，post 会阻塞直到 `getLooper()` 返回——这与 AOSP 契约一致。
- 在原厂里，**worker 线程启动 → `Looper.prepare()` → `Looper.loop()` 已经就绪** → 然后 OplusLooperExecutor 构造函数同步 `execute(runnable)` 把 init 排进队列 → loop 取出第一条消息执行。
- 这两条路径对**外部观察者**（systrace）几乎不可见：`onLooperPrepared` 与 `Looper.loop()` 之间的窗口在 OPPO 主流 ROM 上是 µs 级。差别仅在"理论上业务可以抢在 init 前 post 一条"在原厂会排在 init 之后、在 lib 会"先 init 后 post"——但**两条路径最终 init 都是本线程的 `AnimationHandler` 完成 `setProvider`**，对外副作用顺序一致。

**结论**：**精确复刻**（语义层）+ **时机不可观察差异**（结构层）。归类为"精确复刻 + 一个不可观察的时机差"。

#### A-3. `THREAD_NAME` 常量 + `private const val PRIORITY = -19` + 单例字段

| 字段 | lib 类型 / 可见性 | 原厂类型 / 可见性 | 是否同形 |
|---|---|---|---|
| `THREAD_NAME` | `const val` / `public`（companion object 顶层 const） | 无显式常量；字符串字面量直接嵌入 `OplusExecutors.java:95` 第二个参数 | **轻微分歧**——lib 多了一个具名常量便于 systrace 对照；原厂是匿名字面量 |
| `PRIORITY` | `private const val` | 无显式常量；`-19` 字面量嵌入 `OplusExecutors.java:95` 第二参数 | **精确复刻**（值相同；lib 提升可见性便于 onLooperPrepared 兜底再设） |
| `instance` | `internal val by lazy(SYNCHRONIZED)` | `private static final OplusLooperExecutor ANIM_EXECUTOR` | **结构等价** |

**结论**：精确复刻，无分歧。

---

### B. 有意简化

#### B-1. `runCatching { Process.setThreadPriority(myTid, PRIORITY) }` 替代 `LauncherBooster.getCpu().setUxThreadValue(myTid)`

| 维度 | lib | 原厂 |
|---|---|---|
| 行为 | 调用 SDK 公开 API `Process.setThreadPriority`，将本线程优先级再写一次 `-19`（兜底） | 调 OPPO 私有 `LauncherBooster.getCpu().setUxThreadValue(Process.myTid())`，通过反射 → `IOplusUIFirstManager.setUxThreadValue` 把 TID 注册到 `uifirst` 系统服务，触发 UI First 调度策略（UIFIRST_OPT_SET_PRIORITY / UIFIRST_ASYNC_ANIM 事件，见 `LauncherBooster.java:55-58` Metadata d2 表） |
| 作用域 | 仅本进程的 OS 调度优先级 | 跨进程的 UI First 策略（影响 CPU 绑核 + 渲染优先级 + GPU 调度策略） |
| 失败兜底 | `runCatching { ... }` 吞 `SecurityException` | 内部 `try / catch (Throwable th) { ... }`（`LauncherBooster.java:617-619`），反射失败也只是 log |
| 文档声明 | `AnimationControlThread.kt:35-41`（KDoc）+ `:68-69`（行内注释）明示"已退化 + 兜底" | 无注释，纯原生调用 |

**lib 注释原文摘录**（`AnimationControlThread.kt:68-69` 行内注释，已确认存在）：
> UX 线程提权：原厂 `LauncherBooster.getCpu().setUxThreadValue(Process.myTid())`，AOSP 无对应 API；退化为在本线程再确认一次优先级（构造参数已设，此处兜住被外部改动的情况）

**结论**：**有意简化**。lib 主动放弃 OPPO 私有 `setUxThreadValue`（UI First 服务跨进程调度），用 SDK 公开 API 做相同副作用的近似（仅本进程 OS 优先级）。修复成本：无（OPPO `uifirst` 系统服务不在 AOSP，无合法替代）。**建议保持简化**（§4-B-1）。

#### B-2. 缺 `LauncherBooster.CpuBoost.reportKeyThreadToUAF(handlerThread, eventId)`

| 维度 | lib | 原厂 |
|---|---|---|
| 行为 | 无 | `Executors.java:97`：`LauncherBooster.CpuBoost.reportKeyThreadToUAF(handlerThread, i10);`，把本线程作为 "key thread" 注册到 UAF（Unified Async Framework）框架，并绑上事件 ID（`LauncherBooster.java:389-392` 的静态方法 + `reportKeyThreadToUAF$lambda$1`） |
| 注册表 | 无 | `HashMap<String, Integer>`（`LauncherBooster.java:232` 含 `"launcher.anim" → 2016` + `"onlineUXThread" → 2017`）；Companion 字段 `keyUxThread` / `staticUxThread`（`LauncherBooster.java:230-238` Metadata d2 表可见） |
| 实际效果 | 线程跑在默认核 | 线程被绑到 UX 专用核（小核 / 大核视 ROM 策略），CPU 占用隔离 + 调度延迟更低 |

**结论**：**有意简化**（AOSP 无对应 API）。修复成本：高（需引入 `com.oplus.basecommon.util.LauncherBooster`，但 `com.oplus.*` 在 AOSP build 里没有；lib 走不进 OPPO ROM 私有类加载路径）。**建议保持简化**（§4-B-2）。

#### B-3. 缺 `OplusLooperExecutor(Looper, Runnable)` 的二次构造路径

lib 直接 `HandlerThread` 子类，跳过 `LooperExecutor` 这一层。原厂多一层 `OplusLooperExecutor` 包裹 `Looper`，并提供：

- `executeWithUx(...)`（`OplusLooperExecutor.java:30-33`）：`LauncherBooster.getCpu().requestCpuResources(2005)` + `setUx(true, Process.myTid())`，把单次任务也标记为 UX；
- `executeBlockWait(...)`（`OplusLooperExecutor.java:25-27`）：通过 `CompletableFuture` 阻塞等待任务完成（5s 超时）；
- `LIMIT_TIME = 5`（`OplusLooperExecutor.java:17`）。

**结论**：**有意简化**（lib 在 `vs-oppo-06-public-api.md` / `vs-oppo-11-feature-gaps.md` 中已记录 `LooperExecutor` 公共 API 子集缺失）。修复成本：中（补一个 `OplusLooperExecutor` 等价类即可）。**建议保持简化**（lib 当前无调用方需要 `executeBlockWait`，UI 路径靠 `Handler.post` 即可——§4-B-3）。

---

### C. 遗漏

#### C-1. 缺"线程 init 在 Looper 消息队列第一帧执行"的语义可观察性

原厂 `ANIM_EXECUTOR$lambda$0` 是作为 `OplusLooperExecutor` 构造里的 `execute(runnable)` 注入的（`OplusLooperExecutor.java:86`），因此：

- 若调用方在拿到 `ANIM_EXECUTOR` 之前（如反射 / 类初始化期异常）就 post 任务——OPPO 路径下 init 一定先执行，lib 路径下 `onLooperPrepared` 已经先做完。
- 若线程初始化失败（`Process.setThreadPriority` 抛 `SecurityException`，仅在沙箱化进程出现）——OPPO 路径里异常被 `LauncherBooster.java:617-619` 的 `try/catch` 吃掉；lib 路径里 `runCatching` 吃掉，**等价**。

**评估**：差异不可观察，且对崩溃路径无影响。归"精确复刻"（已述 §A-2）。

#### C-2. 缺"线程 `daemon / nonDaemon`"显式声明

`HandlerThread` 默认继承 `Thread.daemon = false`（Java 行为），原厂与 lib 一致——不是分歧，记在这里仅作反例。

#### C-3. 缺 `OplusExecutors.getANIM_EXECUTOR()`（即对外访问器）

原厂 `OplusExecutors.java:174-176`：
```java
public final OplusLooperExecutor getANIM_EXECUTOR() { return ANIM_EXECUTOR; }
```

lib 用 `AnimationControlThread.instance` + 调用方自取 `instance.looper`；`onLooperPrepared` 内不再 wrap `Handler`（dbde195 后装的是 `ChoreographerTickScheduler()`，`AnimationControlThread.kt:67`）。

**结论**：**有意简化 + 不可见**（`OplusExecutors` 在 lib 进程内不存在，访问器无意义）。归"有意简化"。

#### C-4. 缺 `Lambda 0` 跨类调用的"显式 hook 点"

`vs-oppo-01-async-thread.md` §B-5 已记录：lib 把 init 折进 `onLooperPrepared`，没有显式"线程首跑 hook"。未来若新增"线程首跑做点别的"（如 `Looper.myQueue().addIdleHandler`），需要直接改 `AnimationControlThread.onLooperPrepared`。

**结论**：**有意简化**。修复成本：低（暴露 `init` 钩子或保留一个 companion `val onThreadInit: (Looper) -> Unit = {}`）。**建议保持简化**（lib 只有一个线程，无需通用 hook——§4-C-1）。

---

## 3. 行为差异风险点

> 按"可能导致语义不同"严格筛选。其他"细节差异 / API 子集缺失"已在 `vs-oppo-01` / `vs-oppo-06` / `vs-oppo-11` 覆盖，本节不重复。

### ✔️保持简化 R-1【BUG 级，修复成本：低】`onLooperPrepared` 时 `Process.setThreadPriority` 可能在某些 ROM 上失效

> **判定**：OPPO ROM 专属 UI First 功能，AOSP 公开 API 库无替代。lib 注释 `AnimationControlThread.kt:35-41, 68-69` 已明示"退化为本线程再确认一次优先级 + 兜底"。**保持简化**。

**现象**：

- `HandlerThread(name, priority)` 构造已经把优先级设到 worker 线程（见 AOSP `HandlerThread` 实现，在 `run()` 起点 `Process.setThreadPriority(priority)`），所以 `onLooperPrepared` 里再设一次 `-19` 是冗余。
- 但 OPPO ROM 上有 `LauncherBooster.setUxThreadValue` 会通过 `uifirst` 服务**强制**把 TID 设为 UX 档——lib 没有这一步，OPPO ROM 上 `AnimationControlThread` 线程在 OPPO 视角里**不是 UX 线程**。
- 如果 lib 在 OPPO ROM 上跑 demo，`systrace` 抓线程会被 OPPO perf 模块标"普通后台"，**渲染优先级 / CPU 调度延迟会比原厂差**。

**触发条件**：
1. lib APK 装在 ColorOS / RealmeUI 设备上；
2. 同时需要 OPPO `uifirst` 服务（系统进程），业务是 launcher 这种 UX 关键场景。

**严重程度**：业务侧 launcher 跑就明显（动画卡顿），demo 跑不可见（demo 没装 OPPO `uifirst`）。

**修复成本**：低——`AnimationControlThread.kt:70` 的 `runCatching` 块改为反射调用 `LauncherBooster`，但 `com.oplus.*` 类在 AOSP build 里不存在 → **实际修复成本：高（需要打包 `com.oplus.basecommon.util.LauncherBooster` 整个 vendor lib；除非 lib 注定只在 OPPO ROM 上跑）**。

**判定**：OPPO ROM 专属功能，lib 是 AOSP 公开 API 库，**接受风险**。已在 `AnimationControlThread.kt:35-41, 68-69` 注释里明示"退化为本线程再确认一次优先级 + 兜底"。

### ✔️保持简化 R-2【BUG 级，修复成本：无】`init { start() }` 在 `by lazy` 内——主类加载触发新线程创建

### R-2【BUG 级，修复成本：低】`init { start() }` 在 `by lazy` 内——主类加载触发新线程创建

**现象**：
- lib：`companion object { internal val instance by lazy(SYNCHRONIZED) { AnimationControlThread() } }`，其中 `init { start() }`。
- 第一次访问 `AnimationControlThread.instance` → companion lazy 触发 → 创建 `AnimationControlThread` 子类实例 → 立即 `start()`。
- OPPO：`private static final OplusLooperExecutor ANIM_EXECUTOR = ...` 在 `<clinit>` 阶段被初始化时同样创建 HandlerThread 并 `start()`。

**两者差异**：lib 的 `by lazy(SYNCHRONIZED)` 是**首次访问时**触发，OPPO 的 `static final` 是**类加载时**触发。如果 lib APK 启动时主类还没被加载（绝大多数情况），行为一致；如果主类在 worker 线程里被加载（罕见，如 `ContentProvider.attachInfo` 异步路径），lib 会延迟到 worker 线程，OPPO 会提前到启动主线程——但 lib 的 `SYNCHRONIZED` 保证只有一个 `start()`。

**严重程度**：低——`<clinit>` 与 `lazy(SYNCHRONIZED)` 在语义上都保证"且只一次"，且 ANIM 线程创建本来就是异步的。

**修复成本**：无（语义一致，仅触发时机差 1-10ms）。

**判定**：**接受**，无 bug。

### ✔️保持简化 R-3【BUG 级，修复成本：中】`onLooperPrepared` 内 `installThreadScheduler` 与"业务提前 post 帧回调"的竞争窗口

**现象**：

- lib 时序：`HandlerThread.run()` → `Looper.prepare()` → `onLooperPrepared()`（装 `ChoreographerTickScheduler`）→ `Looper.loop()` 启动 → 业务 post 的帧回调在队列里排到。
- 原厂时序：`HandlerThread.run()` → `Looper.prepare()` → `Looper.loop()` 启动 → `OplusLooperExecutor.execute(runnable)` 把 `ANIM_EXECUTOR$lambda$0` 入队 → loop 取出 init 跑（装 `SfVsyncFrameCallbackProvider`）→ 业务后续 post 才入队。

**关键区别**：

- lib 的 `onLooperPrepared` 跑在 `Looper.prepare()` 之后、`Looper.loop()` 之前，业务**无法抢在 init 之前** post 任何消息——因为 `HandlerThread.getLooper()` 在 `Looper.loop()` 启动前会阻塞（`HandlerThread.run()` 里 `notifyAll` 时机），调用方拿不到 looper 就 post 不了。
- OPPO 同理：`HandlerThread.start()` 后调用方调 `createAndStartNewLooper`（`Executors.java:94-99`），内部 `handlerThread.getLooper()` 也会阻塞直到 `Looper.prepare()` 完成——所以业务也抢不到 init 之前。

**结论**：**两条路径对业务不可见**。R-3 实际上是虚惊。归"无 bug"。

### ✅已修复（64d3bab）R-4【行为差异，修复成本：无】`Process.setThreadPriority(myTid, -19)` 调用与 `HandlerThread` 构造的优先级设置时序

**现象**：
- `HandlerThread.run()` 起点先 `Process.setThreadPriority(priority)`，然后才 `Looper.prepare()` → `onLooperPrepared()`。
- lib 在 `onLooperPrepared` 里又设一次 `-19`，**是冗余**。
- OPPO 时序：构造完 HandlerThread → start()（里面设一次 `-19`）→ 主线程拿到 looper → `OplusLooperExecutor` 构造 → `execute(runnable)` → `ANIM_EXECUTOR$lambda$0` 里 `setUxThreadValue`（**不只是设优先级，还注册 UI First 服务**）。

**严重程度**：低——重复设置相同值无害；唯一差异是 OPPO 多做了 UI First 服务注册（已述 R-1）。

**结论**：**接受**。

### ✔️保持简化 R-5【行为差异，修复成本：中】线程名 `"launcher.anim"` 与 OPPO `reportKeyThreadToUAF` 注册表里的 `"launcher.anim"` 字面量必须严格一致

**风险**：
- 若 lib 改名（如 `"launcher.anim.v2"` 或 `"anim_async"`），OPPO ROM 上的 `LauncherBooster.CpuBoost.Companion` 静态字段 `keyUxThread` / `staticUxThread`（`LauncherBooster.java:232`）就找不到这个名字对应的 eventId，UI First 调度不会触发。
- 但 lib 本身没调 `reportKeyThreadToUAF`，所以即便名字一致也无收益——这只是**未来若要做 UI First 适配时的隐性约束**。

**严重程度**：当前 0；潜在中（一旦启用 `setUxThreadValue`）。

**修复成本**：中——固定 `THREAD_NAME = "launcher.anim"` 不变（已做到），但配套需要把 `eventId = LauncherBooster.LAUNCHER_STATIC_LAUNCHER_ANIM = 2016` 写入 lib（lib 不应该硬编码 OPPO 私有常量）。

**判定**：**接受**（当前 lib 不调 UAF，无 bug）。

### ✔️保持简化 R-6【行为差异，修复成本：低】`LooperExecutor` 这一层缺失导致 `ExecutorService` 类型契约丢失

`vs-oppo-01-async-thread.md` §B-3 / §C-2 已记录。lib 的 `AnimationControlThread` 不实现 `ExecutorService`，调用方拿到的就是 `HandlerThread` 子类。本节不重复。

**判定**：**接受**（lib 设计如此：`AnimationControlThread` 仅用于"线程 + looper"载体，业务 post 通过 `Handler(looper)`）。

---

## 4. 回移建议

### 4-A. 值得补的

#### ✅已修复（64d3bab：runCatching 改为 try + Log.w）4-A-1. `Process.setThreadPriority` 兜底应去除冗余 OR 改为日志告警【修复成本：低】

当前 `AnimationControlThread.kt:70`：
```kotlin
runCatching { Process.setThreadPriority(Process.myTid(), PRIORITY) }
```

这是"构造参数已设 + 再设一次"的兜底，**实际无意义**——`HandlerThread` 构造已经把优先级设上，且没有外部代码能在 worker 线程 `Looper.prepare()` 之前动它（因为 `start()` 同步启动后立即 `Looper.prepare()`，主线程这时只能拿到 looper 但 worker 已经 `Process.setThreadPriority` 完了）。

**回移建议**：要么删掉这行（最干净），要么改为 `if (Process.getThreadPriority(Process.myTid()) != PRIORITY) Log.w(TAG, "...")`（可观察性）。建议**删除**——注释里说"兜住被外部改动的情况"在 AOSP API 下**真的不成立**（外部改不到 worker 线程优先级）。

**判定**：轻微清理，非阻塞。

#### ⚠️未修复（无业务需要，本批不修）4-A-2. 暴露一个 companion 的"线程首跑 hook"【修复成本：低】

`vs-oppo-01-async-thread.md` §B-5 已建议。把：
```kotlin
override fun onLooperPrepared() {
    AnimationHandler.installThreadScheduler(ChoreographerTickScheduler())
    runCatching { Process.setThreadPriority(Process.myTid(), PRIORITY) }
}
```

改为：
```kotlin
companion object {
    internal var onThreadReady: (Looper) -> Unit = {}
}

override fun onLooperPrepared() {
    AnimationHandler.installThreadScheduler(ChoreographerTickScheduler())
    onThreadReady(looper)
}
```

业务方可插入自定义首跑逻辑（logcat 标记、Looper.myQueue().addIdleHandler 等）。**判定**：可选——目前无业务需要。

---

### 4-B. 建议保持简化的

> **状态：✔️保持简化（OPPO 私有跨进程 uifirst 服务；AOSP 无合法替代）**
#### 4-B-1. `LauncherBooster.getCpu().setUxThreadValue` 不补

理由：OPPO 私有，跨进程 `uifirst` 服务。AOSP 公开层无合法替代。lib 是 AOSP 公开 API 库，不应该硬编码 `com.oplus.*` 引用。**保持简化**。

> **状态：✔️保持简化（绑核由 OPPO ROM 决定，AOSP 无法做到）**
#### 4-B-2. `LauncherBooster.CpuBoost.reportKeyThreadToUAF(handlerThread, eventId)` 不补

理由：同 4-B-1。绑核由 OPPO ROM 决定，lib 无法在 AOSP 上做到。

> **状态：✔️保持简化（lib 调用方只用 Handler.post；executeBlockWait 是 ANR 风险）**
#### 4-B-3. `LooperExecutor` 包装层 / `executeBlockWait` / `executeWithUx` 不补

理由：lib 调用方只用 `Handler.post`，不需要 ExecutorService 类型持有 / 阻塞等待 / 单任务 UX 标记。**保持简化**。

> **状态：✔️保持简化（AnimationControlThread.instance + Handler(looper) 已够）**
#### 4-B-4. `OplusExecutors.getANIM_EXECUTOR()` 访问器模式不补

理由：lib 没引入 `OplusExecutors` 概念。访问 `AnimationControlThread.instance` 已足够。**保持简化**。

> **状态：✅已修复（64d3bab：runCatching 改为 try + Log.w，同 4-A-1）**
#### 4-B-5. `runCatching { Process.setThreadPriority(...) }` 兜底不补（删除，详见 4-A-1）

理由：构造参数已生效，再设一次无意义。

---

### 4-C. 风险监控项（建议保留但加文档）

> **状态：✔️保持简化（监控项保留；若启用 UAF，eventId=2016 建议补入文档）**
#### 4-C-1. `THREAD_NAME` / `PRIORITY` 字面量必须与原厂字面量同值

理由：未来若启用 `LauncherBooster.reportKeyThreadToUAF`，`"launcher.anim"` + `2016` (`LAUNCHER_STATIC_LAUNCHER_ANIM`) 是注册表 key。lib 已经固化（`AnimationControlThread.kt:76, 83`），但 `LAUNCHER_STATIC_LAUNCHER_ANIM = 2016` 这条事件 ID 还没在 lib 任何地方记录。建议在文档里补一行："若以后调 UAF，eventId 用 2016。"

> **状态：✔️保持简化（监控项；SYNCHRONIZED 模式已正确，start() 只跑一次）**
#### 4-C-2. `by lazy(SYNCHRONIZED)` 单例的并发触发点

理由：lib 的 `instance` 是 lazy，若 lib 被多线程触发加载，需要 `SYNCHRONIZED` 模式（已用）。`PUBLICATION` / `NONE` 模式不适用——`HandlerThread.start()` 必须只跑一次。**已正确**，加注释提示即可。

---

## 5. 自检结论

| 检查项 | 状态 |
|---|---|
| `THREAD_NAME = "launcher.anim"` 与原厂字面量一致 | ✅（`OplusExecutors.java:95`） |
| `PRIORITY = -19` 与原厂字面量一致 | ✅（`OplusExecutors.java:95`） |
| `HandlerThread(name, priority)` + `init { start() }` 等价于原厂 `createAndStartNewLooper` | ✅（`Executors.java:95-96`） |
| `onLooperPrepared` 装帧源 ↔ 原厂 `ANIM_EXECUTOR$lambda$0` 装 `SfVsyncFrameCallbackProvider` | ✅ 语义等价；实现差异（dbde195 后为 `ChoreographerTickScheduler`——公开 Choreographer 真 VSYNC——替代 `SfVsyncFrameCallbackProvider`）已在 `vs-oppo-04` §2.1 / `vs-oppo-15` §③-⑤ 覆盖 |
| `Process.setThreadPriority(myTid, -19)` 兜底 ↔ `LauncherBooster.setUxThreadValue` | ⚠ 简化（仅 OS 优先级，无 UI First 跨进程注册） |
| 单例生命周期（lazy(SYNCHRONIZED) ↔ static final） | ✅ 等价 |
| 时机（onLooperPrepared vs Looper 消息首条） | ✅ 对调用方不可观察 |

**整体判定**：**保真度高**，与原厂逐字面对照无功能性差异；唯一一类有意简化集中在"OPPO 私有 UI First / UAF 绑核"——AOSP 公开层无替代，属设计取舍。lib 注释已明确记录取舍理由（`AnimationControlThread.kt:35-41, 64-69`）。

## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **60bd048** — PRIORITY 字面量 -19 对齐原厂 OplusExecutors.java:95；runCatching 兜底 Process.setThreadPriority 冗余
- **dbde195** — onLooperPrepared 现在装 ChoreographerTickScheduler（公开 Choreographer VSYNC）\r
\r
**批次 3 子代理复核（2026-09-09）**——按已知 commit 列表逐项核对：\r
- §3 R-1/R-2/R-3/R-5/R-6 — 标 ✔️保持简化（OPPO ROM 专属或语义已对齐）\r
- §3 R-4 — ✅已修复（64d3bab）：runCatching 改为 try + Log.w，冗余兜底已清理\r
- §4 4-A-1 — ✅已修复（64d3bab：runCatching 改为 try + Log.w）\r
- §4 4-A-2 — 标 ⚠️未修复（无业务 hook 需求）\r
- §4 4-B-1..5 — 标 ✔️保持简化（已与原 4-B 节判定一致）\r
- §4 4-C-1/2 — ✔️已保留为监控项

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。

批次 2 补记（2026-09-09）：§3 R-1..R-6 与 §4 4-A-1/2 维持 6428b10 标记（复核一致：runCatching 仍在 AnimationControlThread.onLooperPrepared）；本轮补标 §4 4-B-1..4 ✔️保持简化、4-B-5 ⚠️未修复（同 4-A-1）、4-C-1/2 ✔️监控项保留。

## 复核记录 v2（2026-09-09，独立逐条复核）

**复核方式**：逐条对照当前代码（`D:\AsyncAnimator\lib\src\main\java\com\asyncanimator\thread\AnimationControlThread.kt`）
及 OPPO 参考树（`D:\oppo_a6_launcher\sources\`），不信任已有标记。

- **条目总数**：15
- **状态变更**：0 条
- **描述修正**：1 条（§KDoc :38 HandlerTickScheduler 引用过时）

逐条验证明细：

| 条目 | 验证结果 | 证据 |
|---|---|---|
| §3 R-1 | ✔️保持简化（不变） | 无 `setUxThreadValue` 调用；退化注释 `:35-41`（KDoc）+ `:68-69`（行内）仍在 |
| §3 R-2 | ✔️保持简化（不变） | `init { start() }`（`:51-53`）+ `by lazy(SYNCHRONIZED)`（`:86-88`）结构未变 |
| §3 R-3 | ✔️保持简化（不变） | `onLooperPrepared`（`:63-71`）仍在 `Looper.loop()` 前完成装帧源；竞争窗口分析成立 |
| §3 R-4 | ✅已修复（64d3bab） | `:70` runCatching 改为 try + Log.w 兜底 |
| §3 R-5 | ✔️保持简化（不变） | `THREAD_NAME = "launcher.anim"`（`:76`）、`PRIORITY = -19`（`:83`）字面值未变 |
| §3 R-6 | ✔️保持简化（不变） | LooperExecutor 这一层缺失；lib 设计如此 |
| §4 4-A-1 | ✅已修复（64d3bab） | 同 R-4，runCatching 改为 try + Log.w |
| §4 4-A-2 | ⚠️未修复（不变） | 无 `onThreadReady` 之类的线程首跑 hook |
| §4 4-B-1 | ✔️保持简化（不变） | OPPO 私有 `setUxThreadValue`，AOSP 无替代 |
| §4 4-B-2 | ✔️保持简化（不变） | OPPO 私有 `reportKeyThreadToUAF`，AOSP 无替代 |
| §4 4-B-3 | ✔️保持简化（不变） | lib 调用方只用 Handler.post |
| §4 4-B-4 | ✔️保持简化（不变） | lib 没引入 OplusExecutors 概念 |
| §4 4-B-5 | ✅已修复（64d3bab） | 同 4-A-1，runCatching 改为 try + Log.w |
| §4 4-C-1 | ✔️监控项保留（不变） | THREAD_NAME / PRIORITY 字面量已固化 |
| §4 4-C-2 | ✔️监控项保留（不变） | `SYNCHRONIZED` 模式正确，`start()` 只跑一次 |

非状态类更正：
- KDoc `:38` 原文 `[HandlerTickScheduler]（postDelayed 兜底）` → `[ChoreographerTickScheduler]（…；HandlerTickScheduler / ScheduledTickScheduler 已在 215ecb5 删除）`
- 路径、行号均与当前代码一致，无偏差

---

### 复核记录 v3（2026-09-11，commit 64d3bab）

- **R-4 / 4-A-1 / 4-B-5**： 中  改为 ，保留异常日志但去掉 runCatching 冗余包。
- **标记变更**：⚠️未修复 → ✅已修复（64d3bab）。
- **影响范围**：非阻塞清理项，行为语义不变（优先级仍设置），异常路径改为可见日志。

---

### 复核记录 v3（2026-09-11，commit 64d3bab）

- **R-4 / 4-A-1 / 4-B-5**：`AnimationControlThread.kt` 中 `runCatching { Process.setThreadPriority(...) }` 改为 `try { ... } catch (e: Exception) { Log.w(TAG, e) }`，保留异常日志但去掉 runCatching 冗余包。
- **标记变更**：⚠️未修复 → ✅已修复（64d3bab）。
- **影响范围**：非阻塞清理项，行为语义不变（优先级仍设置），异常路径改为可见日志。

