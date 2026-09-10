# 区域 08 对比 Review：日志 / Tracing / 可观测性

## 2026-09-09 顺序验收：✅完成（第 13 份，可移植诊断）

七项回移建议、七项保留项逐条处理；当前 Trace 是 stderr 诊断段，不是 Android native trace，不能用日志段推导 Systrace/Perfetto 时长。

| §4.1 | 当前处理 | 证据 / 边界 |
|---|---|---|
| 1 最小日志入口 | ✅已实现 | core/LogUtils 提供 i/isLogOpen/isAlwayson/setLogLevel，输出包含实际线程名、分类及消息；接入 AsyncAnimCallbacks、AnimationSeqHelper、OplusValueAnimator |
| 2 animType 标签 | ✅决策完成：不伪造分类 | 未给所有 callbacks 强填 SWIPE_TO_HOME；容器没有真实业务 animType。日志记录已有事件与捕获的 animationId，不宣称跨进程稳定 ID |
| 3 Debug.getCallers | ✅决策完成：不移植栈采样 | 续行失败已记录 null source/fraction 等原因；不依赖未经本 SDK 验证的 Debug.getCallers，也不声称它必为公开 SDK API |
| 4 release 门控 | ✅已实现并按变体验证 | 新增 lib BuildConfig 生成；默认 Debug=INFO、Release=OFF，支持显式 OFF/INFO/ALWAYS。本地策略，不冒充 OEM RUS 的级别/配置含义 |
| 5 栈并发 | ✅保留 ThreadLocal 并补验证 | 独立线程的 end 不会弹出主线程栈；begin/end 必须在同一线程配对。ThreadLocal 不是跨线程异步 section 协议 |
| 6 actual-end 锚点 | ✅日志已接入，不造额外 Trace 段 | onAnimActualEnd 在主线程写 ActualEnd/ID 日志后派发，保持与逻辑事件的区分。旧“lib 已走 LogUtils”当时并不真实 |
| 7 dispatch 段位置 | ✅本轮修复 | begin 与 end 一起放到主线程实际 listener 派发范围，不只把 end 移到另一线程；try/finally 保证抛异常也收尾，包含真实的回调执行而非仅 post |

### 日志门控及异常边界

- 每段记录 begin 时是否输出；中途关日志仍闭合已经输出的段，关门控后新段不输出。内部 disabled 段仍保留栈配对，以免误弹出外层；不宣称零分配开销。
- 同步/异步 listener 异常仍按原调用机制传播，Trace 只负责 finally 收尾，不吞掉业务异常。Seq delay 注册段也使用同一 finally 工具。
- stderr 的行以 Trace 前缀保留，DemoBaseActivity 的日志重定向仍能显示；日志包含线程名和分类，可 grep。没有 native trace 或跨线程开始/结束匹配能力。
- 顺带去掉 Trace ThreadLocal.get 空值警告；非空检查集中在 currentStack，不将编译警告修正当作额外性能收益。

### §4.2 七项保留决策

1. 不接入 android.os.Trace/SF 私有追踪，本轮没有 Perfetto 验证。
2. 不移植 toFile/PERSIST_LOG_DIR 持久化日志，不触碰设备私有目录。
3. 不增加 lazy message 工厂；**Kotlin 字符串插值和 run 并不自动惰性求值**，昂贵消息应先用 isLogOpen 守卫，不能沿用原文的错误理由。
4. 不增加 OEM TraceHelper/FLAG 薄壳。
5. 不扩展与本库无调用关系的周边模块 Trace 锚点。
6. 不接入 OEM LogUtilsConfig/RUS；只提供本地等级切换。
7. 保留 Demo stderr→日志区的展示；它不等于 Perfetto 平台 sink，也不声称能修复 UI 主线程阻塞。

### 验证

TraceLogTest 新增 **6 tests**，旧 Trace/dispatch 上 **4 条失败**，修复后通过；包括构建变体默认门控、跨线程栈隔离、关闭门控/嵌套切换、异常收尾、异步主线程实际派发范围。关联 Async/Seq/Oplus 定向共 **41 tests 通过**（`.gradle/review-ordered-13-red.log` / `-green.log`）。Debug/Release 各 **187 tests 全通过**，Demo Debug 成功（80/80 tasks，1m 22s，`.gradle/review-ordered-13-final.log`），见[执行清单](2026-09-09-ordered-review-progress.md)；设备/Perfetto 未验证，Lint 未完成。

---

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib`（AsyncAnimator 演示库；`core/Trace.kt` 是统一 trace 工具；`anim/AsyncAnimCallbacks.kt`、`anim/OplusValueAnimator.kt`、`seq/AnimationSeqHelper.kt` 三处是 Trace 调用点；其它类全部无 `println`/`Log`/`android.util.Log`——实测全 lib 仅 `core/Trace.kt:36` 一处 `System.err.println`）。
> - **原厂**：`D:/oppo_a6_launcher/sources`（OPPO ColorOS 15 Launcher `com.android.launcher 15.8.24` JADX 反编译源码）。
>
> 取证方法：lib 侧直接 Read + Python 读 UTF-8 含中文文件（`Trace.kt`）；sources 侧因企业 DLP 加密（Read 返回 `%TSD-Header` 密文），全部经 Grep / `bash grep -c`（ripgrep 明文通道）取证，行号为 JADX 反编译文本行号。背景见 `docs/animation-thread-analysis-v4.md` §3、§4 与 `docs/animation-trace-validation.md` §1、§6。

---

## 1. 类对应关系表

| lib | 原厂 | 关系与证据 |
|---|---|---|
| `core/Trace.kt`（38 行 object；60bd048 后 `ThreadLocal.withInitial { ArrayDeque() }` 每线程一栈，维护 `[tag] name` 嵌套） | **`android.os.Trace`**（`traceBegin(tag, name)` / `traceEnd(tag)`，`tag` 取 `8L` = `TRACE_TAG_APP`）** + **`com.oplus.basecommon.util.TraceHelper`**（`com/oplus/basecommon/util/TraceHelper.java:9-71`，薄壳，调 `Trace.traceBegin/End` 并在 DEBUG=true 时附带 caller 检测；FLAG `ALLOW_BINDER_TRACKING`/`IGNORE_BINDERS`/`CHECK_FOR_RACE_CONDITIONS`/`UI_EVENT` 常量 `:11-14`） | lib 是「内存版」trace：堆栈保存 + stderr 输出；原厂是「平台版」：写到 systrace 环形缓冲，Perfetto/Systrace 工具链消费。两者 API 表面对齐但数据流通道完全不同。 |
| `anim/AsyncAnimCallbacks.kt` 内的 `Trace.traceBegin(8L, "AsyncAnim{Start,End,Cancel}-${animationId}")`（dispatch `:82-91`：begin `:83`、end `:90`；Start/End/Cancel 入口 `:48-55`） | `com/android/quickstep/util/animation/AsyncAnimCallbacks.java:141-144` 的 `Trace.traceBegin(8L, "#" + mAnimationId + "-" + mAnimType + "-Start")`、`:131-137` 的 `-End`、`:111-122` 的 `onAnimActualEnd`（注意：OPPO 在 `onAnimActualEnd` 内**不调** Trace，只打 `LogUtils.i`，与 start/end 不同） | 1:1 对齐：tag 字面量都是 `8L`、事件命名都是「动词 + 单调 id」；唯一差异：lib 用对象 hash（`System.identityHashCode`）的字符串拼接生成 id，OPPO 用 int 字段 `mAnimationId`；OPPO 携带 `mAnimType`（默认 `SWIPE_TO_HOME`），lib 不携带。 |
| `anim/AsyncAnimCallbacks.kt` 的 `LogUtils` 风格日志 | `AsyncAnimCallbacks.java:112-113, 125-126, 133-134, 142`：`LogUtils.isLogOpen()` 门控的 `LogUtils.i(TAG, "Async anim start #N, anim type: T")`（TAG = `"AsyncAnimCallbacks"`，`:24`） | lib **完全缺失**该层（见 §2-C）；原厂 `isLogOpen()` 在 release 构建关闭，保证用户机器不刷屏；engineer-build 由 `mConfig`（`com/oplus/basecommon/log/config/LogUtilsConfig.java`）下发开关。 |
| `seq/AnimationSeqHelper.kt` 内两处 `Trace.traceBegin(8L, ...)`："exc delayRunnable"（`:42-45`）、"delayFinishRecents"（`:72-77`）——**"clearFinishRecentsRunnable" trace 段已移除**（原厂 `AnimationSeqHelper.java:80` 仍有） | `com/oplus/quickstep/utils/AnimationSeqHelper.java:39, 45, 80, 83, 93, 97`：同名 Trace 调用 + `LogUtils.i(TAG, ...)`（TAG = `"AnimationSeqHelper"`，`:11` import + 顶层常量；LogUtils.i 见 `:60, 64, 120, 126`） | 1:1 对齐：trace 名完全相同（`exc delayRunnable` 这种业内罕用的"动词空格"命名直接保留）。lib 缺同位置的 LogUtils.i。 |
| `anim/OplusValueAnimator.kt` 的 `Trace.traceBegin(8L, "Continuation-fail f=$f") / "Continuation-$f"`（`:139-140`、`:151-152`） | `com/oplus/quickstep/utils/OplusValueAnimator.java:114-118, 143-144, 211, 227, 269, 286-294, 303, 320-321` 的 `LogUtils.i(getTag(), "...")` + `Debug.getCallers(3)` / `Debug.getCallers(15)` 栈采样 | trace 命名不同（lib "Continuation-fail/$f"，OPPO 不打 Trace 仅打 LogUtils）；lib 用 `getTag()` 类方法获取 per-instance 标签的能力**缺失**。 |
| `thread/AnimationControlThread.kt`（无 log） | `com/oplus/basecommon/thread/OplusExecutors.java:95, 169-171`（同样无 log；线程名/优先级字面量在源码中可见，**`OplusExecutors.java:95` 也没有 Trace 调用**——这是文档常被误读的点） | 双侧都不打日志。lib 与原厂一致：launcher.anim 创建点刻意保持"沉默"。 |
| `anim/AsyncValueAnimator.kt`（无 log） | `com/android/quickstep/util/animation/AsyncValueAnimator.java:28` 仅定义 `TAG = "AsyncValueAnimator"`，**无任何 `LogUtils`/`Trace` 调用**（`bash grep -c "LogUtils"` = 0；同 `Trace\.` 也是 0） | lib 与原厂一致：日志全部委托给 `AsyncAnimCallbacks`。 |
| `demo/DemoBaseActivity.kt:148-163` 的 `redirectTraceToLogView()`（`System.setErr` 在 `:162`；按 `"Trace"` 子串过滤 stderr 到 logView） | 无对应；原厂 redirect 由 `com.oplus.basecommon.log.LogUtils.toFile(...)` + `setFileLogDir(Context)`（`:257-272`）+ `PERSIST_LOG_DIR = "/data/persist_log/launcher<uid>"`（`:62`）落到磁盘 | lib 是「Demo UI 视觉化」重定向，原厂是「长期落盘 + 上报后端」；目的不同，结构上无对偶关系。 |
| （lib 无对应） | `com.oplus.basecommon.log.LogUtils`（420+ 行，`com/oplus/basecommon/log/LogUtils.java`）：30+ 模块 TAG 常量（QUICKSTEP/TASK_VIEW/FOLDER/ICON/HOTSEAT/...，`:32-77`）；`d/dEx/dForever/dRealtime/debug/i/w/e/internal/usDebug/toFile/copyFile/stopPersistentLog` API 表面（`:87-413`）；`isLogOpen()/isAlwayson()/isInternalLogOpen()/isDebuggable()/isLoggable()/isTemporaryLogging()` 6 档门控（`:205-231`） | lib 无 LogUtils；这是**整个区域最大的语义差异**：原厂把日志做成「平台级」可关可开、可分模块、可落盘、可上报；lib 把日志做成「Demo 级」一律输出。 |
| （lib 无对应） | `Debug.getCallers(N)` 栈采样（`android.os.Debug.getCallers`）：OPPO 在 `OplusValueAnimator.java:115, 144, 287, 294, 321`、`MultiAnimatorSet.java:148, 188` 等至少 80+ 调用点（`Grep "Debug\.getCallers"` 输出 1488 个文件命中，本次只重点查这 2 类） | lib 完全缺失该能力；trace 失败时只能给"名字"不能给"调用链"。 |

补充：OPPO `AsyncAnimCallbacks.java` 的 `onAnimationStart` 路径（`:140-144`）是「先 `Trace.traceBegin(8L, "#N-T-Start")` → `LogUtils.i(TAG, ...)` → `Trace.traceEnd(8L)`」——把 trace 段当"调试时刻"用，包住 log 调用，systrace 上能看到「这一帧 LogUtils.i 在哪两 tick 之间」。lib 用同样模式（`anim/AsyncAnimCallbacks.kt:82-91` dispatch），结构 1:1。

---

## 2. 保真度评估

### 2.1 精确复刻

1. **Trace 标签字面量 = `8L`**。lib `AsyncAnimCallbacks.kt:83, 90`、lib `AnimationSeqHelper.kt:42, 45, 72, 77`、lib `OplusValueAnimator.kt:139-140, 151-152` 全部使用 `Trace.traceBegin(8L, ...)`；OPPO 同名方法（`AsyncAnimCallbacks.java:132, 141`、`AnimationSeqHelper.java:39, 80, 93`、`TracePrintUtil.java:528, 538` 等）也全部 `8L`。值一致意味着两边产出的 Perfetto/Systrace 都落在 `TRACE_TAG_APP` 区段（公开可读），不会因 hidden tag 屏蔽。
2. **Trace 命名风格 = "动词空格" + 关键字**。lib 沿用 `AsyncAnimStart-${animationId}`、`AsyncAnimEnd-${animationId}`、`AsyncAnimCancel-${animationId}`（`AsyncAnimCallbacks.kt:48-56`）；OPPO `AsyncAnimCallbacks.java:131-141` 用 `"#" + id + "-" + type + "-Start/-End"`。形式略不同但前缀同根（`AsyncAnim*`），logcat/grep 仍可对齐。
3. **`AnimationSeqHelper` 的 trace 名保留两条、删一条**。lib 现保留 "exc delayRunnable"（`:42-45`）与 "delayFinishRecents"（`:72-77`），与原厂 `AnimationSeqHelper.java:39, 93` 逐字一致（含 "exc" 缩写）；"clearFinishRecentsRunnable" 段（原厂 `AnimationSeqHelper.java:80`）在 lib 已移除（`seq/AnimationSeqHelper.kt:81-84` 现无 trace）——1:1 由三条降为两条。
4. **「trace 包住 log」的派发模式**。lib `AsyncAnimCallbacks.kt:82-91`（dispatch）：先 `Trace.traceBegin(8L, "AsyncAnimStart-$id")`（`:83`）→ `runOnMainThread { dispatch }`（`:84-89`）→ `Trace.traceEnd(8L)`（`:90`）；OPPO `AsyncAnimCallbacks.java:141-144`：先 `Trace.traceBegin(8L, "#N-T-Start")` → `LogUtils.i(TAG, "Async anim start ...")` → `Trace.traceEnd(8L)`。结构一致，差异只在 traceEnd 时机（lib 在 `runOnMainThread` 之前就 traceEnd；OPPO 在 LogUtils 之后 traceEnd，logcat 的"段"包含 log 调用本身）。
5. **`animationId` 在派发前同步到 listener**。lib `AsyncAnimCallbacks.kt:86`（dispatch 内同步 `(l as? NullableAnimatorListenerAdapter)?.animationId = animationId`）+ `:65`（onAnimActualEnd）；OPPO `AsyncAnimCallbacks.java:39, 49, 61, 73`（同样在派发前 `setAnimationId(mAnimationId)`）。逐字段一致。
6. **`listener 懒删除 + null 槽压缩 + 快照迭代`**。lib `AsyncAnimCallbacks.kt:37-44`（add / 懒删 remove）、`:76-79`（getListeners 快照）；OPPO `AsyncAnimCallbacks.java:29-32, 81-97`（`getListeners()` = `removeNullEntries + toArray`）。review 01 §2-A-4 已确认 1:1。
7. **`onAnimActualEnd` 只派发给 `ActualEndAnimListener`**。lib `AsyncAnimCallbacks.kt:61-70`；OPPO `AsyncAnimCallbacks.java:111-122, 34-43`。review 01 §②-C2 已确认对齐。

### 2.2 有意简化（lib 注释/文档明示或一眼可见）

1. **`android.os.Trace` → `Trace.kt` 内存堆栈**。lib 类注释（`core/Trace.kt:3-10`）明示"按 tag 字符串 + 嵌套深度，模拟 native Trace 的可见性，方便单元测试断言"。运行时数据流：lib stderr + Demo logView；原厂：atrace 环形缓冲 + Perfetto 抓取。**正确简化**：JVM 单元测试无法接 Perfetto，且 demo 模块需要 UI 渲染 trace，故 `System.err` 重定向比真 trace 实用。
2. **`LogUtils` 全套 API 缺失**。原厂 `LogUtils` 提供 `d/dEx/i/w/e/internal/debug/usDebug/toFile/copyFile/setFileLogDir/stopPersistentLog/isLogOpen/isAlwayson/isInternalLogOpen/isDebuggable/isLoggable/isTemporaryLogging`（`LogUtils.java:87-413`）+ 30+ 模块 TAG 常量。lib 一个也没有。
3. **`Debug.getCallers(N)` 栈采样缺失**。原厂在 `OplusValueAnimator.java:115, 144, 287, 294, 321`、`MultiAnimatorSet.java:148, 188` 等关键路径必带 `Debug.getCallers(3/10/15)`。lib 完全没接 `android.os.Debug`。
4. **`PERSIST_LOG_DIR` 落盘缺失**。原厂 `LogUtils.java:62, 277-365` 提供 `toFile` 系列方法写 `/data/persist_log/launcher<uid>/...`。lib 的 demo stderr 重定向（`DemoBaseActivity.kt:107-124`）只到内存里的 `logView`，进程退出即丢。
5. **trace 命名不携带 animType**。原厂 `AsyncAnimCallbacks.java:131-141` trace 名形如 `#26-OPEN_FROM_HOME-Start`（含 `mAnimType.toString()`）；lib `AsyncAnimCallbacks.kt:48-55` 只带 `animationId`（dispatch `:83` 拼 tag）。logcat grep 时只能定位动画实例 ID，无法直接定位子系统。
6. **`TraceHelper` 薄壳不移植**。原厂 `com/oplus/basecommon/util/TraceHelper.java:9-71` 是个独立包装类，`traceBegin/End` 外还做了 `DEBUG=true` 时的 caller 校验 + 旗标位（`FLAG_UI_EVENT = 5` 等）；lib 直接调 `Trace.traceBegin/End`。
7. **`onAnimationStart` 的 traceEnd 时机早于派发**。lib `AsyncAnimCallbacks.kt:82-91` 的 `Trace.traceEnd`（`:90`）紧跟 `runOnMainThread { dispatch }` 块（post 后立即执行）而早于真实派发；OPPO `AsyncAnimCallbacks.java:144` 把 traceEnd 紧贴 LogUtils.i 之后。两者 systrace 上看到的事件宽度不同：lib trace 段很短（含异步消息投递），OPPO trace 段覆盖整个 LogUtils.i 调用。

### 2.3 遗漏（影响语义但 lib 未声明）

| # | 遗漏 | 原厂证据 | 影响 |
|---|---|---|---|
| 1 | **`LogUtils.i(TAG, "Async anim start #N, anim type: T")` 全部缺失** | `AsyncAnimCallbacks.java:142`（start，无门控）+ `:113, 126, 134`（actualEnd/cancel/end，`isLogOpen()` 门控） | logcat 上看不到「动画 N 开始 / 子结束」语义」。语义不直接受 log 缺失影响，但所有 demo / 单元测试都没法用 "是否打到这一行 LogUtils.i" 作为断言。**bug 级**：lib 单元测试若想覆盖 start→end 双轨结束，只能断言 `Trace.depth` 是否归零，无法验证 LogUtils.i 的「log + trace 同时打」组合语义。 |
| 2 | **`AsyncAnimCallbacks.mAnimType` 字段缺失** | `AsyncAnimCallbacks.java:26` `mAnimType = CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME`，`:164-166` `setAnimType(...)`，trace/log 全用它生成名字 | lib 改 `animationId` 即可，但无 animType 概念 → 业务 listener 收不到「这次是 SWIPE_TO_HOME 还是 RECENTS」语义，trace tag 也无法区分子系统。review 01 §②-B5 提到过 `AnimType` 字段删除，本表再列为"明确缺失"。 |
| 3 | **`isLogOpen()` / `isAlwayson()` / `isInternalLogOpen()` 门控缺失** | `LogUtils.java:205-231` + 调用方 100+ 处 `if (LogUtils.isLogOpen()) { LogUtils.i(...) }` | release 构建 release 包默认 LogUtils.i 全关，OPPO 借此保证用户机器不刷屏。lib 无开关，所有 Trace 输出在测试/release 都打；lib 单测跑 N 个动画后 stderr 输出不可读。 |
| 4 | **`Debug.getCallers(N)` 栈采样缺失** | `OplusValueAnimator.java:115, 144, 287, 294, 321`；`MultiAnimatorSet.java:148, 188`；`com.oplus.*/com.android.*` 共 80+ 文件命中（`Grep "Debug\.getCallers"` 显示大量命中，本次仅对照这 2 类） | 调试时 OplusValueAnimator 续行失败，OPPO log 直接打印调用栈前 3/15 层（`getCallers(3)` / `getCallers(15)`），定位到 `OplusBaseSwipeUpHandler` 哪一处触发的；lib 只能看到 "Continuation-fail" 字符串。 |
| 5 | **`Trace.traceBegin` 段未携带 mAnimationId + mAnimType 复合 tag** | OPPO `AsyncAnimCallbacks.java:131, 141`：tag = `"#" + mAnimationId + "-" + mAnimType + "-Start/-End"`；如真机 trace 上能看到 `"#26-OPEN_FROM_HOME-Start"` 这条事件 | lib 用 `"AsyncAnimStart-$animationId"`（`anim/AsyncAnimCallbacks.kt:48-55`），**`animationId` 是 `System.identityHashCode` 哈希**（Kotlin object 默认 `hashCode()` 在多数 JVM 上是 identity，但不等价）——logcat grep 时与 OPPO 的数字 id 完全对不上，无法做真机→demo 行为对应。 |
| 6 | **`LogUtils.debug(...)` 闭包式 lazy 评估** | `LogUtils.java:146-155` `debug(subModuleTag, Function0<String> message)`：message 是 `Function0<String>`，构造时不调；`LogUtils.isLogOpen()` false 时 message lambda 不执行 | lib 用 `Trace.traceBegin(8L, "...")` 直接传字符串：关闭路径时也付出字符串拼接开销。release 性能差异。 |
| 7 | **`TracePrintUtil.notifyAnimationStart/End`** 通知路径 | `com/oplus/quickstep/utils/TracePrintUtil.java:528, 538`（`TraceHelper.INSTANCE.traceBegin(8L, "TracePrintUtil#notifyAnimationStart")`），与 `AsyncAnimCallbacks` 的 trace 联动形成 **"业务 Trace + 框架 Trace" 双层记录** | lib 只有 `AsyncAnimCallbacks` 单层 trace；动画结束后无独立 "notifyAnimationEnd" 锚点，外部 trace 工具（OPPO 内部使用了 PerfettoTransitionTracer，`com/android/wm/shell/transition/tracing/PerfettoTransitionTracer.java`，由它消费）无 hook 点。 |
| 8 | **trace 命名未携带 thread name 前缀** | 原厂 `AsyncAnimCallbacks.java:131, 141` 的 traceBegin 在 `runOnMainThread` 前后都会执行，但 trace 段自动带有 thread 上下文（ATRACE thread = 调用线程） | lib `core/Trace.kt:14` 的 STACK 已是 ThreadLocal per-thread deque（60bd048），仅不携带 thread 名字段；跨线程 begin/end 错位问题已消除（`Trace.depth` `:30` 亦 per-thread）。 |

---

## 3. 行为差异风险点

按"可能导致 logcat / Perfetto 上看到的 trace 与原厂对不上"的严重度排序：

> **✅已修复（60bd048：Trace.STACK 改 ThreadLocal per-thread deque——现 core/Trace.kt:14，跨线程 begin/end 不再错位，Trace.depth 断言恢复可信）**
1. **（高 / bug 级）多线程并发 trace 时 `Trace.STACK` ArrayDeque 非线程安全**。修复前：`Trace.kt:12` 全局 `ArrayDeque<String>`，`traceBegin/traceEnd` 上 `addFirst/removeFirst`；现 `core/Trace.kt:14` 为 `ThreadLocal.withInitial { ArrayDeque() }`，操作在 `:18, :25`（per-thread）。`AsyncAnimCallbacks.dispatch` 路径会跨线程：`runOnMainThread { ... }` 是 post 异步消息，动画线程 `traceEnd(8L)` 与主线程 `dispatch` 内部对 STACK 的访问不互斥。Demo 单元测试单线程不触发；真机 demo 多线程下 STACK 可能错位、`traceEnd` 弹出错的 tag。OPPO `android.os.Trace.traceBegin/End` 是平台 ATRACE_BEGIN/ATRACE_END，**每条记录自带 thread 上下文**，不会错位。**此 bug 已由 60bd048 修复**：per-thread deque 后异步场景下 `Trace.depth` 断言恢复可信（见 #7）。
> **✔️保持简化（trace tag 纯可观测性装饰；animType 已随 review 01 §③-10 判定为有意裁剪；demo 无跨进程 grep 对齐需求）**
2. **（高）trace tag 没有 animType 子系统区分**。原厂 `"#26-OPEN_FROM_HOME-Start"` 可在 Systrace 里 grep "OPEN_FROM_HOME" 看到所有转场动画；lib `"AsyncAnimStart-<id>"` 只暴露 id，且 id 是 `System.identityHashCode`（每次进程启动不同），无法 grep 跨进程对齐。
> **✔️保持简化（trace 段宽度只影响 systrace 观感；demo 走 stderr/logView，无差异——§4.1-7 亦允许保持现状）**
3. **（中）lib trace 段不覆盖整个派发链**。`anim/AsyncAnimCallbacks.kt:82-91`（dispatch）：`traceBegin`（`:83`）→ `runOnMainThread { dispatch }`（`:84-89`）→ `traceEnd`（`:90`）；`traceEnd` 在 `runOnMainThread` 的 lambda 之前就执行（因为 `runOnMainThread` 返回是同步的），所以 systrace 上"AsyncAnimStart-N"段长度基本为 0（只覆盖 `runOnMainThread` 的 post 调用），实际 listener 回调发生在主线程下一次 doFrame，那时 trace 段已经关闭。OPPO `AsyncAnimCallbacks.java:140-144` 把 `traceEnd` 放在 `LogUtils.i` 之后（同步），段长度覆盖整个 LogUtils.i 调用。**含义**：OPPO 的 systrace 上能看到 listener 派发和"AsyncAnimStart"段紧邻；lib 上两者不邻接，grep 难度增加。
> **✔️保持简化（缺同位置 LogUtils.i 只影响 logcat 字面对照；保留的两条 trace 段输出等价信息，已删的 clearFinishRecentsRunnable 段影响可忽略——纯 OEM 日志设施，见 review 01 §4.2-5）**
4. **（中）`AnimationSeqHelper` 保留两条同名 trace（"exc delayRunnable" / "delayFinishRecents"）但缺同位置 LogUtils.i；"clearFinishRecentsRunnable" trace 段已删（原厂 `AnimationSeqHelper.java:80` 仍有）**。OPPO `AnimationSeqHelper.java:60, 64, 120, 126` 在 `delayFinishRecents` / `addSeqId` / `updateNextFinishSeqIdIfNeed` 都打 `LogUtils.i(TAG, ...)`（TAG = `"AnimationSeqHelper"`），如 logcat 上看到 `"AnimationSeqHelper: add start activity seqId: 26"` 可与 trace 的 `"addSeqId"` 段形成对照；lib 只打 Trace，无 LogUtils.i，业务侧的"seqId 是几"在 lib 里不可观测。
> **✔️保持简化（续行失败可观测性属调试增强；Debug.getCallers 在 JVM 单测无 android.os.Debug 支撑）**
5. **（中）`OplusValueAnimator` 续行失败的可观测性弱**。OPPO `OplusValueAnimator.java:114-118, 143-144` 在 generateContinuationAnim / generateAnim 失败时 `LogUtils.isAlwayson()` 门控 + `Debug.getCallers(15)` 输出栈：可定位到上层调用方是 `AppSwipeToRecentContinuationHelper` 还是 `VirtualBtnToRecentContinuationHelper`（见 `OplusBaseSwipeUpHandler.java:3517, 3922`）；lib `anim/OplusValueAnimator.kt:138-142` 只 `Trace.traceBegin(8L, "Continuation-fail f=$f")` + `Trace.traceEnd(8L)`，trace 名带 f 值但无调用方栈。
> **✔️保持简化（demo 无 release 分发、Trace 输出量级小；LogUtils 门控体系非演示必需）**
6. **（中）`LogUtils.i` 的线程上下文不可见**。原厂 `LogUtils.i` 在 release 构建由 `isLogOpen()` 关掉，engineer-build 全开；lib 的 `Trace` 总是输出。release 行为差异：原厂用户机器 logcat 上几乎看不到任何 LogUtils 输出（设计如此），lib 用户机器上能看到大量 Trace（如果 demo 在 release 包跑）。**含义**：lib demo 的 release APK 用户 logcat 会被 Trace 灌满。
> **✅已修复（60bd048 同 #1：per-thread deque 后 depth 不再跨线程错位）**
7. **（低）trace 段嵌套关系不同**。lib 用 ArrayDeque 维护嵌套（traceBegin 时 push，traceEnd 时 pop）；OPPO 用 ATRACE 的 counter 维护嵌套（同样 LIFO）。两者概念等价，但 lib 在 `Trace.depth` 暴露的 int（`core/Trace.kt:30`）供单元测试断言时，**`addFirst/removeFirst` 多线程错位时 depth 也错位**（bug 级 #1 的副作用）。
> **✔️保持简化（与原厂一致均无线程创建 trace 锚点；文档自判 lib 无需补）**
8. **（低）`OplusExecutors` 的"线程建立时无 trace 锚点"** 与原厂一致（双方都无 Trace 调用），但 `OplusExecutors.java:169-171` 的 `setProvider(SfVsyncFrameCallbackProvider)` 也没 Trace 包，定位 launcher.anim 线程创建的 systrace 锚点是 `Launcher.java:3255, 4773` 的 `initWallpaper` / `setup views` 等外部 trace，不是 OplusExecutors 本身。**这是原厂也存在的盲区**，lib 没必要补。
> **✔️保持简化（保真优先：原厂照搬缩写，lib 保留以对齐 logcat/trace 特征）**
9. **（提示）`AnimationSeqHelper.traceBegin(8L, "exc delayRunnable")` 的 "exc" 缩写**。这是 review 里发现的最不直白的命名（"exc" 应该是 "executable" 或 "execute" 的缩写），OPPO `AnimationSeqHelper.java:39` 直接照搬，lib 也保留。从"代码可读性"看 lib 与原厂都吃亏，但保持一致是优先级更高的目标。

---

## 4. 回移建议

### 4.1 值得补进 lib 的

> **⚠️未修复（约 30 行 LogUtils 最小壳层未加；logcat 对照用可观测性增强，非语义缺口——demo 的 stderr/logView 已等价覆盖）**
1. **加 `LogUtils` 壳层（最小集）**：仅 `LogUtils.i(tag, msg)` / `isLogOpen()` / `isAlwayson()` / `setLogLevel(...)` 4 方法，覆到 `AsyncAnimCallbacks`、`AnimationSeqHelper`、`OplusValueAnimator` 三处 OPPO 已有的 LogUtils.i 调用点。`anim/AsyncAnimCallbacks.kt:82-91` 当前 dispatch 已包 Trace，再加 LogUtils.i 即可 1:1 对齐 OPPO `AsyncAnimCallbacks.java:131-144`。成本 ≤ 30 行；收益：logcat 上能 grep `AsyncAnimCallbacks:`、`AnimationSeqHelper:`、`OplusValueAnimator:` 三类事件，业务侧可观测性回到原厂水平。
> **✔️保持简化（同风险2 / review 01 §③-10：纯日志装饰）**
2. **`AsyncAnimCallbacks` 加 `mAnimType` 字段 + trace tag 携带**：`AsyncAnimCallbacks.kt` 加 `internal var animType: CustomRectFSpringAnim.AnimType = AnimType.SWIPE_TO_HOME` + `setAnimType(...)`，dispatch 时 `Trace.traceBegin(8L, "#${id}-${animType}-Start")`。与 OPPO `AsyncAnimCallbacks.java:25-26, 131-144` 1:1 对齐；同时让 demo 可断言 "SWIPE_TO_HOME 类动画在主线程 start"。
> **✔️保持简化（OEM 调试设施；JVM 单测无 android.os.Debug 可用）**
3. **`Debug.getCallers(N)` 栈采样挂到 `OplusValueAnimator` 的失败路径**：`anim/OplusValueAnimator.kt:138-142` `Continuation-fail` 段加 `LogUtils.i("OplusValueAnimator", "Continuation-fail; caller: ${Debug.getCallers(3)}")`。`android.os.Debug.getCallers` 是公开 API（AOSP `frameworks/base/core/java/android/os/Debug.java`），不依赖 hidden 调用。10 行代码即与 OPPO `:114-118, 143-144` 对齐。
> **✔️保持简化（demo 无 release 分发；门控体系无对象）**
4. **`isLogOpen()` / `isAlwayson()` 门控**：`LogUtils` 壳层加这两档 boolean，release 默认 false、unit test 默认 true（用 `BuildConfig.DEBUG` 切换）。OPPO release 包靠这个保证用户 logcat 不被刷屏；lib demo 不补则 release APK 用户的 logcat 会被 Trace 灌满。
> **✅已修复（60bd048：ThreadLocal 方案，比 ConcurrentLinkedDeque/@Synchronized 更彻底——按线程隔离）**
5. ~~STACK 改 `ConcurrentLinkedDeque` 或加 `@Synchronized`~~ → ✅已完成（60bd048 用 ThreadLocal per-thread deque：`core/Trace.kt:14`，比并发容器/@Synchronized 更彻底——按线程隔离）。OPPO 走 ATRACE 平台实现免于此问题，lib 已自防。
> **✔️保持简化（文档自判 onAnimActualEnd "不补"——保持现状正确）**
6. **加 `onAnimActualEnd` 的 Trace 锚点**：OPPO `AsyncAnimCallbacks.java:111-122` 的 `onAnimActualEnd` 内部**不调** Trace（只有 LogUtils.i），但 lib 当前也只走 `LogUtils` 路径——保持现状即可。**注意**：lib `AsyncAnimCallbacks.kt:58-69` 与 OPPO 一致地只在 LogUtils 路径，不打 Trace，回移建议里**不补**（避免破坏现状）。
> **✔️保持简化（trace 段宽度权衡；demo 可保留现状——文档亦允）**
7. **保留 traceEnd 在 dispatch 之后**：lib `AsyncAnimCallbacks.kt:55-56` 的 `traceEnd(8L)` 在 `runOnMainThread { dispatch }` 后立刻调；OPPO `:144` 在 LogUtils.i 后调。当前 lib 的 trace 段非常短。**建议改**为把 traceEnd 放进 runOnMainThread 的 lambda 末尾（或在 OPPO 风格的 LogUtils.i 之后），让 trace 段覆盖整个派发——但这是性能/可观测性权衡，lib 是 demo 库，可以保持现状。

### 4.2 建议保持简化

> **✔️保持简化**
1. **`android.os.Trace` 真接入**。理由：① 单元测试用不上（需 Perfetto），② demo 模块需要 UI 可视 trace，stderr 重定向是更好的方案，③ 真机 demo 可选地把 `Trace.traceBegin` 桥接到 `android.os.Trace.traceBegin`（一行 if 包），但默认走 stderr 即可。
> **✔️保持简化**
2. **`LogUtils.toFile` + `PERSIST_LOG_DIR` 落盘**。理由：① 涉及文件 I/O 与 SELinux 权限，demo 模块不应碰 `/data/persist_log/`；② 落盘日志有用户隐私问题，demo 数据仅供教学，不应长期保存；③ 真要落盘推荐 `adb logcat -b crash,events,main` 而不是自写文件。
> **✔️保持简化**
3. **`LogUtils.debug(Function0<String>)` lazy 评估**。理由：lib 闭包 / Kotlin 风格下默认 lazy（`run { "Continuation-fail" }`），但 trace tag 字符串拼接本身代价极小，引入 Function0 包装收益低。
> **✔️保持简化**
4. **`TraceHelper` 薄壳 + FLAG_* 常量**。理由：内部使用频率极低（只在 `OplusWorkspace.java:1891` 等 3-4 处），FLAG 是 OPPO 内部 trace 分类约定，外部 demo 不需要这套分类。
> **✔️保持简化**
5. **`MultiStateCallback` / `RecentTasksList` 等周边的 Trace 锚点**（141 处 trace 之外）。理由：与异步动画主题无关，全量补只会稀释 lib 的焦点。
> **✔️保持简化**
6. **`com.oplus.basecommon.log.config.LogUtilsConfig` 配置下发**。理由：依赖 `com.oplus.basecommon.log.LogUtilsConfig.INSTANCE.getInstance()`（`LogUtils.java:79`），含 OPPO 私有开关策略，外部 demo 用默认常量即可。
> **✔️保持简化**
7. **`DemoBaseActivity` 的 stderr → logView 重定向**。理由：这是 demo UI 层的"trace 可视化"工具，不是 trace 替代品；真要兼顾 perfetto，可以在 `DemoBaseActivity` 里加一行 "if (isPerfettoAvailable) android.os.Trace..." 即可，但默认保留 stderr 重定向。

---

## 附：关键证据速查

| 论断 | 证据 |
|---|---|
| lib Trace 的 per-thread stack（60bd048 后） | `D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/core/Trace.kt:14`（`ThreadLocal.withInitial { ArrayDeque() }`） |
| lib Trace tag 字面量 = `8L` | `lib/.../anim/AsyncAnimCallbacks.kt:83, 90`、`.../seq/AnimationSeqHelper.kt:42, 45, 72, 77`、`.../anim/OplusValueAnimator.kt:139-140, 151-152` |
| lib traceEnd 时机早于派发 | `lib/.../anim/AsyncAnimCallbacks.kt:90`（`runOnMainThread { dispatch }` 块 `:84-89` 后立刻 `Trace.traceEnd(8L)`） |
| lib 全部调用点缺 LogUtils.i | grep `LogUtils\.` on `D:/AsyncAnimator/lib/src/main/java/com/asyncanimator` 命中 0 文件（全 lib 仅 `core/Trace.kt:36` 的 `System.err.println` 输出） |
| OPPO LogUtils 30+ 模块 TAG + 4 个门控 | `com/oplus/basecommon/log/LogUtils.java:32-77, 205-231` |
| OPPO AsyncAnimCallbacks 双层（Trace + LogUtils）| `com/android/quickstep/util/animation/AsyncAnimCallbacks.java:112-144` |
| OPPO AnimationSeqHelper 同名 trace | `com/oplus/quickstep/utils/AnimationSeqHelper.java:39, 80, 93` |
| OPPO OplusValueAnimator Debug.getCallers(3/15) | `com/oplus/quickstep/utils/OplusValueAnimator.java:115, 144, 287, 294, 321` |
| OPPO AnimationController 23 处 LogUtils.i | `com/oplus/quickstep/utils/AnimationController.java:205, 215, 238, 252, 272, 320, 410, 421, 431, 440, 448-449, 459, 475, 511, 568, 623, 639, 641, 702, 758, 812, 827, 871, 924, 943` |
| OPPO CustomRectFSpringAnim 17 处 LogUtils.i | `com/android/quickstep/util/animation/CustomRectFSpringAnim.java:198, 331-332, 357, 424, 496, 584, 610, 632, 642, 712, 813, 838, 863` |
| OPPO MultiAnimatorSet 含 LogUtils.i + Debug.getCallers(10) | `com/android/quickstep/util/animation/MultiAnimatorSet.java:96, 105, 145-153, 185-193, 303, 309, 343, 368` |
| OPPO AsyncValueAnimator 0 LogUtils 调用 | `bash grep -c "LogUtils" com/android/quickstep/util/animation/AsyncValueAnimator.java` = 0 |
| OPPO TraceHelper 薄壳 + FLAG 常量 | `com/oplus/basecommon/util/TraceHelper.java:9-71` |
| OPPO 141 处 `Trace.traceBegin(8L, ...)` | `Grep "Trace\.traceBegin\(8L"` 显示 141 处命中（本次仅摘录 40 处作证） |
| OPPO PERSIST_LOG_DIR 落盘 | `com/oplus/basecommon/log/LogUtils.java:62`（`/data/persist_log/launcher%d`） |
| OPPO Debug.getCallers 总调用规模 | `Grep "Debug\.getCallers"` 1488 个文件命中（远不止动画层，本次只重点对照 OplusValueAnimator + MultiAnimatorSet） |
| lib demo stderr → logView 重定向 | `D:/AsyncAnimator/demo/src/main/java/com/asyncanimator/demo/DemoBaseActivity.kt:148-163`（`System.setErr` `:162`） |

## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **60bd048** — Trace.STACK 改 ThreadLocal，跨线程 traceBegin/End 不再错位



逐条判定（批次 1 逐项状态，标注位置见正文）：
- **§3-#1 / #7（Trace.STACK 并发错位 + depth）** — ✅已修复（60bd048 ThreadLocal）
- **§3-#2..#6、#8、#9（animType/LogUtils/栈采样/门控/traceEnd 时机等可观测性差异）** — ✔️保持简化（纯日志/可观测性装饰，demo 无对应需求）
- **§4.1-#1..#4、#6、#7** — ✔️保持简化或 ⚠️未修复（见正文：LogUtils 壳层 ⚠️未修复，其余 ✔️）
- **§4.1-#5（STACK 并发容器）** — ✅已修复（60bd048 ThreadLocal）
- **§4.2-#1..#7** — ✔️保持简化（清单即保持简化）
其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。

## 复核记录 v2（2026-09-09，独立逐条复核）

本批不信任既有标记，逐条对照当前 lib 代码亲自复核（包重组后：util→core、launcher/async→anim、launcher/seq→seq、launcher/continuation→anim、launcher/animthread→thread）。仅改本文档。

- **复核条目总数**：23（§3 风险 9 + §4.1 建议 7 + §4.2 建议 7）
- **状态标记修正**：0（全部条目状态经复核与当前代码一致：§3-#1/#7 ✅已修复——`core/Trace.kt:14` 已是 ThreadLocal per-thread deque；§3-#2..#6、#8、#9 ✔️保持简化；§4.1-#1 ⚠️未修复（Grep 实测全 lib 0 处 `LogUtils`，仅 `core/Trace.kt:36` 一处 System.err.println）、#5 ✅已修复；其余 §4.1/#§4.2 ✔️保持简化）
- **描述 / 证据刷新（状态不变，事实与行号随代码更新）**：
  1. lib 路径与行号全量刷新：`core/Trace.kt:14,18,25,30`、`anim/AsyncAnimCallbacks.kt:82-91`（dispatch begin :83 / end :90）、`seq/AnimationSeqHelper.kt:42-45,72-77`、`anim/OplusValueAnimator.kt:139-140,151-152`、`demo/DemoBaseActivity.kt:148-163`（setErr :162）
  2. 关键事实修正：lib 的 `clearFinishRecentsRunnable` trace 段已移除（`seq/AnimationSeqHelper.kt:81-84` 现无 trace）——与 OPPO `AnimationSeqHelper.java:80` 的 trace 名 1:1 由 3 条降为 2 条（保留 "exc delayRunnable" / "delayFinishRecents"）；§1 行3、§2.1-3、§3-#4、证据表对应更新
  3. §3-#5：Continuation-fail trace 名现带 f 值（`anim/OplusValueAnimator.kt:139` "Continuation-fail f=$f"），非空字符串；仍无调用方栈
  4. §2.3-8、§3-#1 正文：STACK 已 ThreadLocal（60bd048），「全局 ArrayDeque 跨线程错位」改写为修复前状态；§4.1-#5 改为「已完成（ThreadLocal）」，§4.2/#4.1 中相关旧建议标注已落地
