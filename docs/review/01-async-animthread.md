# 对比 Review 01：异步/线程层（async / animthread）

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/async/`（AsyncValueAnimator、AsyncAnimCallbacks、LooperExecutor、Executors）+ `.../animthread/`（AnimExecutors、AnimationControlThread、AsyncAnimWrapper、HandlerTickScheduler）
> - **原厂**：`D:/oppo_a6_launcher/sources`（OPPO ColorOS 15 Launcher 15.8.24 JADX 反编译源码）
>
> 原厂文件经 DLP 加密，全部证据通过 Grep 明文通道取得，行号为 JADX 文本行号。背景结论见 `docs/animation-thread-analysis-v4.md`。

---

## ① 类对应关系表

| lib 类 | 原厂类 | 关键证据（原厂 文件:行） |
|---|---|---|
| `async/AsyncValueAnimator.kt` | `com.android.quickstep.util.animation.AsyncValueAnimator` | `com/android/quickstep/util/animation/AsyncValueAnimator.java:24-165`（Kotlin 类，`classes3.dex`） |
| `async/AsyncAnimCallbacks.kt` | `com.android.quickstep.util.animation.AsyncAnimCallbacks` | `AsyncAnimCallbacks.java:23-172` |
| `async/LooperExecutor.kt` | `com.oplus.basecommon.thread.LooperExecutor`（+ 子类 `OplusLooperExecutor`） | `com/oplus/basecommon/thread/LooperExecutor.java:12-80`；`OplusLooperExecutor.java:16-104` |
| `async/Executors.kt` | `com.oplus.basecommon.thread.Executors`（仅 `MAIN_EXECUTOR` 部分） | `com/oplus/basecommon/thread/Executors.java:20,54` |
| `animthread/AnimationControlThread.kt` | 无同名类；对应 `OplusExecutors.ANIM_EXECUTOR` 的内联创建 + init lambda | `com/oplus/basecommon/thread/OplusExecutors.java:95`（`createAndStartNewLooper("launcher.anim", -19, …)` + `new f(1)` init 回调）、`:169-171`（`setProvider(SfVsyncFrameCallbackProvider)` + `setUxThreadValue`）、`Executors.java:94-99`（`createAndStartNewLooper`） |
| `animthread/AnimExecutors.kt` | `OplusExecutors.getANIM_EXECUTOR()` | `OplusExecutors.java:95,174-176` |
| `animthread/AsyncAnimWrapper.kt` | `com.android.launcher3.anim.AsyncAnimWrapper` | `com/android/launcher3/anim/AsyncAnimWrapper.java:10-20`（全类 20 行，1:1） |
| `animthread/HandlerTickScheduler.kt` | **无直接对应类**；替代的是框架 `android.animation.AnimationHandler` 的 `SfVsyncFrameCallbackProvider` 帧源 | `OplusExecutors.java:5,170`（`com.android.internal.graphics.SfVsyncFrameCallbackProvider`，框架 @hide 类，APK 内无实现体） |

补充定位：原厂 `AsyncAnimWrapper` 在 `com/android/launcher3/anim/AsyncAnimWrapper.java`（v4 文档 §2 已载），lib 注释中引用的原厂片段逐字准确。

---

## ② 保真度评估

### A. 精确复刻

| # | 设计点 | 原厂证据 | lib 证据 |
|---|---|---|---|
| 1 | `start/cancel/end` 判线程 + post 纠偏 | `AsyncValueAnimator.java:116-127, 130-141, 153-164`（`getLooper().isCurrentThread()` → `super.xxx()` 否则 `mAnimLooperExecutor.execute(...)`） | `AsyncValueAnimator.kt:42-53`（`marshal{}` 内联或 `executor.execute`） |
| 2 | 默认 executor = `MAIN_EXECUTOR` | `AsyncValueAnimator.java:53-55` | `AsyncValueAnimator.kt:19` |
| 3 | `mIsEnd` AtomicBoolean 门控：cancel/start 遇 end 即丢弃，end 用 CAS 保证只发一次 | `AsyncValueAnimator.java:61, 69, 80` | `AsyncValueAnimator.kt:29, 33, 37` |
| 4 | listener 懒删除：remove 置 null 槽而非立即移除 | `AsyncAnimCallbacks.java:147-151` | `AsyncAnimCallbacks.kt:29-32` |
| 5 | addListener 去重（contains 判定） | `AsyncAnimCallbacks.java:99-105` | `AsyncAnimCallbacks.kt:25-27` |
| 6 | 派发前把 `animationId` 同步进 `NullableAnimatorListenerAdapter` | `AsyncAnimCallbacks.java:48-49, 60-61, 72-73` | `AsyncAnimCallbacks.kt:51` |
| 7 | listener 始终回主线程 fire（`MAIN_EXECUTOR` + isCurrentThread 内联） | `AsyncAnimCallbacks.java:154-162`（`runOnMainThread`） | `AsyncAnimCallbacks.kt:59-62` |
| 8 | `LooperExecutor.execute`：同 Looper 内联执行，否则 Handler.post | `LooperExecutor.java:27-33` | `LooperExecutor.kt:30-33` |
| 9 | 线程名 `"launcher.anim"` | `OplusExecutors.java:95` | `AnimationControlThread.kt:75` |
| 10 | 线程常驻、类加载即创建（进程级单例，永不 quit） | `OplusExecutors.java:95`（static final）+ `LooperExecutor.java:71-79`（shutdown 抛异常） | `AnimationControlThread.kt:81-83`（lazy 单例） |
| 11 | `AsyncAnimWrapper` 双通道调度骨架 | `AsyncAnimWrapper.java:11-19` | `animthread/AsyncAnimWrapper.kt:39-46`（1:1，仅改为可空 task + internal） |
| 12 | 「帧循环跑在 start 所在线程」的 ThreadLocal 语义 | `OplusExecutors.java:170`（框架 `AnimationHandler.getInstance()` 为 ThreadLocal） | `core/anim/AnimationHandler.kt:132-141`（ThreadLocal 单例）+ `AnimationControlThread.kt:60-62`（`onLooperPrepared` 内装 scheduler） |

### B. 有意简化（lib 注释/文档中明示）

| # | 简化内容 | 原厂对应 | lib 取舍理由 |
|---|---|---|---|
| 1 | `SfVsyncFrameCallbackProvider` → `HandlerTickScheduler`（Handler.postDelayed 自走帧循环） | `OplusExecutors.java:169-171` | 框架 @hide API，AOSP 公开层无法直接调；lib 注释（`AnimationControlThread.kt:34-39, 61-62`）明示 |
| 2 | `LauncherBooster.getCpu().setUxThreadValue(...)` UX 线程注册未移植 | `OplusExecutors.java:171` | OPPO 私有；lib 退化为 `Process.setThreadPriority` 兜底（`AnimationControlThread.kt:63-69`） |
| 3 | `Executors` 只留 `MAIN_EXECUTOR`；`UI_HELPER_EXECUTOR`（:55）、`MODEL_EXECUTOR`（:56）、`THREAD_POOL_EXECUTOR`（:53）、`getPackageExecutor`（:80-81）、`createAutoRecycleExecutor`（:64-74）、UAF 绑核（`Executors.java:97`）全部砍掉 | `Executors.java:18-99` | 与动画线程主题无关；affinity（2001/2003）为 UIFirst 私有，lib 注释明示（`AnimExecutors.kt:20-21`） |
| 4 | `OplusLooperExecutor` 扩展（`executeAtFront` :38-44、`executeWithUx` :78-103、`executeBlockWait` :46-71、`executeDelay` :73-75）未复刻；ANIM 线程降级为普通 `LooperExecutor` | `OplusLooperExecutor.java:16-104` | 均依赖 LauncherBooster 私有 API；`executeBlockWait` 本身是 v4 §9.3 点名的 ANR 风险 |
| 5 | `AnimType` 字段、LogUtils 日志删除 | `AsyncAnimCallbacks.java:26, 112-113, 125-126, 133-134, 142` | 纯日志装饰；lib 保留 Trace tag（`AsyncAnimCallbacks.kt:47`） |
| 6 | `executor` 改 public var（原厂为私有字段 + `setExecutor`） | `AsyncValueAnimator.java:29, 147-150` | Kotlin 习惯用法，语义等价 |
| 7 | JVM 单测兜底：handler 为 null 时就地执行 / 起 sleep 线程 | 原厂无此路径（Handler 永不 null） | lib 明示为测试便利（`LooperExecutor.kt:17-20, 42-50`） |

### C. 遗漏（未在 lib 注释中说明、且影响语义）

| # | 遗漏点 | 原厂证据 | 影响 |
|---|---|---|---|
| 1 | **线程优先级实际是 -8 而非 -19**：lib 用 `Process.THREAD_PRIORITY_URGENT_DISPLAY`（SDK 常量值 **-8**），原厂字面量 **-19**（对应 `THREAD_PRIORITY_URGENT_AUDIO` 档位） | `OplusExecutors.java:95`（`-19`） | lib `AnimationControlThread.kt:78` 代码与 :77 注释自相矛盾（注释说 -19，常量是 -8；:28-29 的 `THREAD_PRIORITY_DISPLAY - 17` 又等于 -21）——**这是 bug 级差异**，动画线程调度优先级比原厂低 11 级 |
| 2 | **`onAnimActualEnd` / `ActualEndAnimListener` 双轨结束语义缺失** | `AsyncAnimCallbacks.java:34-43, 111-122`（metadata 中亦有 `onAnimActualEnd`） | v4 §4 强调的核心设计：cancel 是「下一帧生效」，「逻辑结束（UI 线程即发）」与「物理帧播完（动画线程）」需要两个回调区分；lib 只有单一 onAnimationEnd |
| 3 | **listener 派发用同步消息而非 async 消息**：原厂 `Utilities.postAsyncCallback` 会 `setAsynchronous(true)` | `AsyncAnimCallbacks.java:120, 160` → `Utilities.java:631-637` | lib `AsyncAnimCallbacks.kt:61` 用普通 `post`；主线程 sync-barrier（traversal）期间回调会被阻塞，原厂可穿透 |
| 4 | **dispatch 无快照、null 槽永不压缩**：原厂每次派发前 `removeNullEntries` + `toArray` 快照 | `AsyncAnimCallbacks.java:29-32, 81-97` | lib `AsyncAnimCallbacks.kt:48-53` 直接迭代 live list；add/remove 发生在动画线程、迭代发生在主线程时存在 CME 窗口；null 槽只增不减 |
| 5 | `Companion.ofFloat(isAsync, values)` 工厂 | `AsyncValueAnimator.java:43-49, 98-100` | 原厂调用方（如 `AppLaunchAnimUtil`）按布尔选同步/异步实现的入口未移植 |
| 6 | 原厂 listener 收到的是 **null animator**（`onAnimationEnd(null)`） | `AsyncValueAnimator.java:64, 70, 83` | lib 传真实 animator（`AsyncValueAnimator.kt:29-37`）； NullableAnimatorListener 系本就按可空设计，属行为改进但确为分歧点 |
| 7 | `LooperExecutor` 不再 `extends AbstractExecutorService`，且无 `getLooper()/getHandler()/getThread()/setThreadPriority()` | `LooperExecutor.java:12, 35-45, 65-67` | 需要 `ExecutorService` 类型或 Looper 访问器的调用点无法直接替换；原厂 `shutdown()` 抛异常这一「永不 quit」契约也未保留 |

---

## ③ 行为差异风险点

按风险从高到低：

1. **动画线程优先级 -8 vs 原厂 -19（C-1）**。-19 与 SurfaceFlinger/音频同级，-8 仅 URGENT_DISPLAY 档。主线程满载时 lib 动画线程更容易被调度挤压，帧间隔抖动显著大于原厂。且 lib 注释三处互相矛盾（-19 / URGENT_DISPLAY / DISPLAY-17=-21），后续维护者无法判断意图。
2. **帧源语义不同（B-1）**。`HandlerTickScheduler` 是 `postDelayed(16ms)` 自走时钟：无 vsync 对齐、`frameIntervalMs` 硬编码 16（90/120Hz 屏帧率错配）、与主线程渲染无相位关系。原厂 launcher.anim 吃 SF-vsync，帧相位与 SurfaceFlinger 合成对齐。演示 OK，量化对比（掉帧率、帧间隔直方图）不可与原厂互推。
3. **listener 回调可被 sync-barrier 阻塞（C-3）**。主线程 measure/layout 期间 lib 的 end/cancel 回调会排队等 barrier 解除，原厂 async 消息按时到达。结束回调驱动的后续动作（如窗口释放、下一个动画启动）在 lib 上可能晚一帧以上。
4. **双轨结束无法表达（C-2）**。缺少 `ActualEndAnimListener` 意味着 lib 无法在 cancel 场景区分「已通知业务结束」与「动画线程帧循环真的停了」；原厂用前者立即驱动 UI 状态、后者做资源清理，时序错乱时（先 end 后还有帧）lib 没有对应的保护挂点。
5. **并发迭代 CME（C-4）**。原厂快照派发；lib 若业务在动画进行中（动画线程）add/remove listener，主线程迭代中的 `mutableListOf` 可能抛 `ConcurrentModificationException`。
6. **JVM 兜底掩盖配置错误（B-7 的副作用）**。真机上若 `Looper.getMainLooper()` 意外为 null（如进程早期），原厂直接 NPE 暴露，lib 静默退化为就地执行、动画跑在错误线程上。

---

## ④ 回移建议

### 值得补进 lib（性价比高）

1. **修正优先级为字面量 -19**（`AnimationControlThread.kt:78`）：一行改动直接对齐原厂 `OplusExecutors.java:95`；同时修正 :28-29、:77 两处自相矛盾的注释。不改则所有性能演示结论都带着系统偏差。
2. **AsyncAnimCallbacks dispatch 改为「压缩 null 槽 + 快照迭代」**（对齐 `AsyncAnimCallbacks.java:29-32` 的 `getListeners()` 模式）：消除 CME 窗口与 null 槽泄漏，改动约 10 行。
3. **listener 派发改用 async 消息**：`Message.obtain(handler, r).apply { isAsynchronous = true }` 是公开 API（原厂 `Utilities.java:631-637` 即如此），无需 hidden API 即可对齐 C-3。
4. **补 `onAnimActualEnd` / `ActualEndAnimListener`**：这是跨线程动画正确性的关键语义（v4 §4 双轨时序），且实现只需在 `AsyncAnimCallbacks` 加一个分支 + 一个接口；不补则 lib 无法复刻原厂 cancel 场景的真实行为。
5. **补 `ofFloat(isAsync, …)` 工厂**（`AsyncValueAnimator.java:43-49`）：5 行，补齐原厂公开 API 面，调用方迁移时不用改写站点。
6. **`frameIntervalMs` 跟刷新率**：至少从 `Display.getRefreshRate()` 取初值；条件允许时优先用 `Choreographer.getInstance().postFrameCallback`（公开 API）替换 `postDelayed`，把 B-1 的帧源差距从「固定 16ms 自走」缩到「app-vsync 对齐」（SF-vsync 仍不可得，但比现状接近原厂一个量级）。

### 建议保持简化

1. **`SfVsyncFrameCallbackProvider` 不做反射硬挂**：虽是框架类（AOSP wm/shell 也在用，如 `com/android/wm/shell/pip/PipAnimationController.java:567`），但属 @hide API，反射在非 OPPO  ROM 上行为不定；保留 `HandlerTickScheduler` + 接口化 `TickScheduler` 的可替换设计是正确取舍。
2. **`LauncherBooster` UX 标记 / UAF 绑核不移植**：纯 OPPO 私有调度增强，无公开等价物，且不影响动画正确性只影响调度优先级。
3. **`OplusLooperExecutor` 四扩展不移植**：`executeWithUx` 依赖私有 API；`executeBlockWait` 是 v4 §9.3 点名的主线程 5s 硬等 ANR 风险，属于「原厂自己也不该这么写」的范畴。
4. **`Executors` 其余 executor / 线程池不移植**：14 个事务/加载线程与异步动画演示主题无关，全量复刻只会稀释库的焦点。
5. **`AnimType` + LogUtils 不移植**：纯日志装饰；lib 的 Trace tag（`AsyncAnimStart-<id>`）已覆盖可观测性需求。

---

## 附：小勘误

- lib `HandlerTickScheduler.kt:12` 注释引用「分析文档 §2.3 FrameCallbackProvider14 退化路径」对不上 v4 文档（v4 无此节；`FrameCallbackProvider16` 的描述在 v3 `animation-thread-analysis.md:80`）。建议注释改为引用具体文档名+章节，避免 v3/v4 结论已被推翻（v4 §1）后误导读者。
