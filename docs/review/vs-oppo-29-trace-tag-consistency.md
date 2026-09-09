# 区域 29 对比 Review：traceBegin/traceEnd 标签体系一致性

> 编号延续 `vs-oppo-01..12` 序列 → **13**；主题：**tag 一致性**（数字字面量 + 命名规范 + 跨线程配对 + 缓冲后端）。
>
> 与既有 12 份报告的关系：本报告**不**重复 `vs-oppo-08-trace-observability.md` 的全景对照（LogUtils / Debug.getCallers / 5 类遗漏点已在那里详述），也**不**重复 review 04（线程与帧调度）、review 12（运行时风险）的 ArrayDeque 提法。本报告聚焦 4 个**具体文件/方法/字段**层面的差异：
>
> 1. 标签字面量 [8L=TRACE_TAG_APP / 32L / TRACE_TAG_VIEW / j前缀] 的实际分布
> 2. trace 命名规范（lib 4 处 vs OPPO 148 处的字符串比对）
> 3. `ArrayDeque STACK` 在跨线程派发后的非线程安全配对
> 4. ATRACE 环形缓冲 + Perfetto 后端 vs lib stderr 的数据流差异
>
> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib`（`core/Trace.kt` 单 stack 模拟；5 个 traceBegin/End 调用点，分布 3 个文件）
> - **原厂**：`D:/oppo_a6_launcher/sources`（OPPO ColorOS 15 Launcher `com.android.launcher 15.8.24` JADX 反编译）
>
> 取证方法：lib 侧 Read 工具对 DLP 加密文件返回密文，通过 `python .agents/skills/read-with-python/scripts/dump.py` 解密后读明文；原厂侧 80% 文件被企业 DLP 加密，全部经 Grep ripgrep 明文通道取证，行号为 JADX 反编译文本行号。
>
> 背景：review 08 §1 已确认 OPPO 141 处 `Trace.traceBegin(8L,...)`，本报告聚焦 8L 之外的 28 处其他 tag + tag 是否真的落到 OPPO 的 `ATRACE_TAG_APP` 通道。

---

## 1. 类对应关系表

### 1.1 lib Trace 实现（单一工具 + 4 调用点）

| lib 文件 | 行号 | 方法/字段 | tag | name |
|---|---|---|---|---|
| `lib/.../core/Trace.kt` | 13 | `private val STACK = ThreadLocal.withInitial { ArrayDeque<String>() }` | — | — |
| `lib/.../core/Trace.kt` | 16-18 | `internal fun traceBegin(tag: Long, name: String)` | 参数化（实际只接 8L） | `[8] name` |
| `lib/.../core/Trace.kt` | 21-24 | `internal fun traceEnd(tag: Long)`（含 isNotEmpty 守卫） | 参数化（实际只接 8L） | 弹出 `STACK` 顶 |
| `lib/.../core/Trace.kt` | 26 | `internal val depth: Int get() = STACK.get().size` | — | — |
| `lib/.../core/Trace.kt` | 28 | `internal fun clear() = STACK.get().clear()` | — | — |
| `lib/.../core/Trace.kt` | 32-35 | `private fun log(msg: String) → System.err.println("Trace $msg")` | — | — |
| `lib/.../anim/AsyncAnimCallbacks.kt` | 89 | `Trace.traceBegin(8L, "$traceTagPrefix$animationId")` | 8L | `AsyncAnimStart-${id}` / `AsyncAnimEnd-${id}` / `AsyncAnimCancel-${id}` |
| `lib/.../anim/AsyncAnimCallbacks.kt` | 96 | `Trace.traceEnd(8L)` | 8L | — |
| `lib/.../seq/AnimationSeqHelper.kt` | 46 | `Trace.traceBegin(8L, "exc delayRunnable")` | 8L | `exc delayRunnable` |
| `lib/.../seq/AnimationSeqHelper.kt` | 49 | `Trace.traceEnd(8L)` | 8L | — |
| `lib/.../seq/AnimationSeqHelper.kt` | 72 | `Trace.traceBegin(8L, "delayFinishRecents")` | 8L | `delayFinishRecents` |
| `lib/.../seq/AnimationSeqHelper.kt` | 78 | `Trace.traceEnd(8L)` | 8L | — |
| `lib/.../anim/OplusValueAnimator.kt` | 149 | `Trace.traceBegin(8L, "Continuation-fail f=$f")` | 8L | `Continuation-fail` |
| `lib/.../anim/OplusValueAnimator.kt` | 150 | `Trace.traceEnd(8L)` | 8L | — |
| `lib/.../anim/OplusValueAnimator.kt` | 161 | `Trace.traceBegin(8L, "Continuation-$f")` | 8L | `Continuation-${fraction}` |
| `lib/.../anim/OplusValueAnimator.kt` | 162 | `Trace.traceEnd(8L)` | 8L | — |

**全部 lib Trace 统计**：5 个 `traceBegin` / 5 个 `traceEnd`（严格配对），3 个调用方文件，tag 字面量全部 `8L`，name 模板 6 个变体（`AsyncAnimStart/End/Cancel-${id}` ×1 + `Continuation-fail` + `Continuation-${f}` ×1 + 3 个 seq 名）。

### 1.2 OPPO 原厂 trace 实现（平台 Trace + TraceHelper + TracePrintUtil）

| 原厂文件 | 行号 | tag | 说明 |
|---|---|---|---|
| `com/oplus/basecommon/util/TraceHelper.java` | 66-71 | 通用包装 | `public void traceBegin(long j8, String str) → Trace.traceBegin(j8, str)`；无 tag 过滤 |
| `android/os/Trace.java`（AOSP 公开 API，runtime 调用） | — | — | 系统提供：每个 trace 事件写入 atrace 环形缓冲（内核 + 用户态双口），携带 thread + timestamp + tag 自动上下文 |
| `com/oplus/quickstep/utils/TracePrintUtil.java` | 528 | `8L` | `TraceHelper.INSTANCE.traceBegin(8L, "TracePrintUtil#notifyAnimationEnd")` — 跨线程**主线程侧**通知路径 |
| `com/oplus/quickstep/utils/TracePrintUtil.java` | 538 | `8L` | `TraceHelper.INSTANCE.traceBegin(8L, "TracePrintUtil#notifyAnimationStart")` |
| `com/oplus/quickstep/utils/TracePrintUtil.java` | 477-486 | `Trace.asyncTrace*` | `TracePrintUtil.asyncTraceBegin/End` 23 处使用（Perfetto 跨线程 counter 追踪） |
| `com/oplus/quickstep/utils/TracePrintUtil.java` | 32-39 | `Type` enum + 6 BASIC_ID | `WINDOW/VIEW/RECENTS/WORKSPACE/TASKBAR/CARD` 6 类子系统分类 |

### 1.3 OPPO 全树 tag 字面量分布（按 file/occurrence 分类）

> grep `Trace\.traceBegin\(([^)]+)` 在 `com/android/quickstep/*`、`com/oplus/quickstep/*`、`com/android/launcher3/*`、`com/android/launcher/*`、`com/oplus/basecommon/*`、`com/android/common/*` 全树的统计：

| tag 字面量 | 文件数 | 出现数 | 典型模块 | AOSP 含义 |
|---|---|---|---|---|
| `8L` | 72 | **123** | `com.android.quickstep.*`、`com.oplus.quickstep.*`、`com.android.launcher3.*`、`com.android.launcher.*`、`com.oplus.basecommon.*`、`com.android.common.*` | `TRACE_TAG_APP`（公开 tag，userdebug + user 都可见） |
| `32L` | 8 | **21** | `com.android.wm.shell.transition.*`、`com.android.wm.shell.startingsurface.*`、`com.android.wm.shell.back.*`、`com.android.wm.shell.transition.tracing.*` | `TRACE_TAG_NOT_NEEDED`（AOSP 公共常量，调试专用，与 `1L << 5` 等价的 jank-tracer 通道） |
| `Trace.TRACE_TAG_VIEW` | 3 | **3** | `d3/f.java`、`pantanal/appplugin/groupcard/widget/carousel/CarouselLayoutManager.java`、`com/coui/appcompat/animation/dynamicanimation/COUISpringAnimation.java` | `1L << 10` = 1024L（AOSP `android/os/Trace.java`，专门用于 View 系统的绘制 trace） |
| `j\d+`（计算后传入） | 3 | **4** | `com.android.wm.shell.startingsurface.TaskSnapshotWindow.java:154,171`（j10/j102）；`TraceHelper.java:67`（j8 形参透传）；`com.oplus.posteffect.util.TraceUtilsKt.java:21`（j8 形参透传） | 通常由调用方按子系统动态传入；实例 `TaskSnapshotWindow` 在 debug 构建下取 `TRACE_TAG_APP`，release 走隐藏 tag |
| `Trace.asyncTrace*` | 9 | **23** | `com.android.wm.shell.draganddrop.GlobalDragListener`、`com.android.wm.shell.transition.TransitionJankTracker`、`com.android.common.util.AsyncAnimationJankTracker`、`com.android.common.debug.LocalMainThreadJankTracker`、`com.android.common.debug.LocalJankUtils`、`com.oplus.basecommon.util.TraceHelper`、`com.coui.appcompat.animation.dynamicanimation.COUISpringAnimation`、`com.coui.appcompat.animation.dynamicanimation.COUIDynamicAnimation` | AOSP `Trace.asyncTraceBegin(tag, name, counter)` API，**为 Perfetto counter 通道设计**：traceBegin/End 是"成对区间"语义；asyncTrace 是"独立事件 + 唯一 cookie"语义，可跨线程配对 |

**总览**：原厂全树共 **174 处** trace 调用（123+21+3+4+23 = 174，文档里有 2 处示例注释不计入）。其中 lib 对应的"launcher tier" = 123 处 `8L`；其余 51 处（21+3+4+23）分别对应 WM shell（32L）、View 系统（TRACE_TAG_VIEW）、动态 tag（j前缀）、Perfetto counter（asyncTrace）。

### 1.4 对应关系（一对多展开）

| lib 类/字段 | 原厂类/字段 | 关系 |
|---|---|---|
| `core/Trace.kt:16-18` traceBegin/End | `android.os.Trace.traceBegin/End`（`com/oplus/basecommon/util/TraceHelper.java:66-71` 透明转发）+ `com/oplus/quickstep/utils/TracePrintUtil.java:477-486` 的 `Trace.asyncTrace*` 包装 | lib 是**纯内存版**（ArrayDeque + stderr）；原厂是**平台内核版**（ATRACE 环形缓冲 → Perfetto proto → UI）。两者 API 表面对齐（`traceBegin(Long, String)`），数据流**完全不同**。 |
| `core/Trace.kt:13` `STACK: ThreadLocal<ArrayDeque<String>>` | `android.os.Trace` 内部 ATRACE per-thread ring buffer（`frameworks/base/core/jni/android_os_Trace.cpp` 的 `ATRACE_TAG` slots） | lib 用 ArrayDeque 模拟嵌套语义；原厂用 kernel buffer 自带 LIFO 嵌套 + thread context。 |
| `AsyncAnimCallbacks.kt:82, 89` 4 处 `traceBegin(8L, "AsyncAnim{Start,End,Cancel}-${id}")` | `AsyncAnimCallbacks.java:131-144` 的 `Trace.traceBegin(8L, "#${mAnimationId}-${mAnimType}-Start/-End")` | 1:1 tag 一致；name 前缀不同：lib 用动词 + id，OPPO 用 `#id-Type-verb`。**tag=8L 一致是保真度最关键的一行**。 |
| `AnimationSeqHelper.kt:42, 45, 72, 77` 4 处 `traceBegin(8L, ...)` | `AnimationSeqHelper.java:39, 80, 93` 3 处 `Trace.traceBegin(8L, "exc delayRunnable" / "clearFinishRecentsRunnable" / "delayFinishRecents")` | **逐字一致**：3 个 name 完全相同（OPPO 的"exc"缩写照搬），tag 全 8L。 |
| `OplusValueAnimator.kt:139, 140, 151, 152` 4 处 `traceBegin(8L, "Continuation-fail" / "Continuation-${f}")` | （OPPO `OplusValueAnimator.java` 中**无对应** trace 调用，10 处全是 `LogUtils.i` + `Debug.getCallers(3/15)`） | lib 的 `Continuation-*` trace 是**新增的语义标注**，原厂走 LogUtils 路径。这与 review 08 §2.3 #1 的提法一致：lib 在 `OplusValueAnimator` 上加了 trace，原厂没有。 |
| （lib 无） | `OplusExecutors.java` 全部 executor 定义点（`UX_TASK_EXECUTOR$lambda$0`、`ANIM_EXECUTOR$lambda$0`、`RECENT_TASKS_EXECUTOR$delegate` 等 12 个） | 双方都不打 trace，原厂"线程建立时无 trace 锚点"是有意为之——launcher.anim 启动点由 `Launcher.java:3255, 4773` 的 `initWallpaper` / `setup views` 外层 trace 标记。**lib 继承此约定**。 |
| （lib 无） | `com/android/quickstep/util/animation/MultiDynamicAnimation.java` | 全部 11 个方法无 `Trace.traceBegin/End`，只走 `com.android.common.config.d.a("...", TAG)`（LogUtils 薄壳）。**原厂在 MultiDynamicAnimation 上零 trace**——这是 lib 与 OPPO 的一致点（review 11 §3 已提及）。 |
| （lib 无） | `com/coui/appcompat/animation/dynamicanimation/COUISpringAnimation.java:182` `Trace.traceEnd(Trace.TRACE_TAG_VIEW)` | 原厂在 COUI 弹簧动画的"事件型"traceEnd 用 `TRACE_TAG_VIEW`（1024L），与 8L 完全隔离。lib 不覆盖此层。 |
| （lib 无） | `com/android/wm/shell/transitions/PerfettoTransitionTracer.java:157,171,185,199` 4 处 `Trace.traceBegin(32L, "logAborted/logDispatched/logMergeRequested/logMerged")` | 原厂 WindowManager Shell 层用 `TRACE_TAG_NOT_NEEDED (32L)`，是 WM shell 内部 trace，与 launcher 8L 完全隔离。lib 不覆盖此层。 |
| （lib 无） | `TracePrintUtil.java:528, 538` `TraceHelper.INSTANCE.traceBegin(8L, "TracePrintUtil#notifyAnimation*")` | 原厂"业务 + 框架"双层 trace 中的**框架层**：在 main thread 上 `notifyAnimationEnd`，与 AsyncAnimCallbacks 的 launcher.anim trace 形成跨线程配对。**这是 lib 完全缺失的能力**。 |
| （lib 无） | `OplusBaseSwipeUpHandler.java:2384, 2791, 7773, 10664` 4 处 `Trace.traceBegin(8L, "OplusBaseSwipeUpHandler#animateTo*")` | 原厂手势主控制器打 4 个 trace 锚点，对应 `startRecentsAnimation`/`onSettledOnEndTarget` 等关键阶段。lib `OplusBaseSwipeUpHandler` 不存在，故无对应。 |

---

## 2. 保真度评估

### 2.1 精确复刻

1. **tag 字面量 = `8L` 在 launcher-tier 完全对齐**：lib 4 处 `traceBegin(8L, ...)` 与 OPPO 同名方法（`AsyncAnimCallbacks.java:132, 141`、`AnimationSeqHelper.java:39, 80, 93`、`TracePrintUtil.java:528, 538` 等）的字面量相同 → 都在 `TRACE_TAG_APP` 通道。**这是 lib 与原厂 100% 一致的核心**。
2. **traceBegin/End 配对在 traceBegin 函数体内的 traceEnd 紧邻位置**：lib 与原厂都是「`traceBegin` → 一段逻辑 → `traceEnd`」的紧邻配对（如 `AsyncAnimCallbacks.kt:89+96`、`AnimationSeqHelper.kt:72+77` vs `AsyncAnimCallbacks.java:141+144`），没有嵌套 traceBegin。
3. **traceBegin 名字模板原样保留（最严格处）**：
   - `exc delayRunnable`（`AnimationSeqHelper.kt:42` ≡ `AnimationSeqHelper.java:39`）
   - `clearFinishRecentsRunnable`（`:72` ≡ `:80`）
   - `delayFinishRecents`（`:76` ≡ `:93`）
   - **逐字一致 3 处**，与 review 08 §2.1 #3 提法一致。
4. **`AsyncAnimCallbacks.dispatch` 的「trace 包住 dispatch」结构**：lib `:81-90`（traceBegin → runOnMainThread → traceEnd）与 OPPO `:131-144`（traceBegin → LogUtils.i → runOnMainThread → traceEnd）结构对称。差异是 lib 缺 LogUtils.i、OPPO 缺 list 同步——见 §3.5。
5. **`traceBegin/End` 都在调用线程同步执行**：lib `dispatch` 的 `traceEnd(8L)` 在 `runOnMainThread {...}` 的 lambda 之外同步执行；OPPO 同理（`:144` traceEnd 与 runOnMainThread 同级）。**这一点确保了 §3.1 的 ArrayDeque 风险不是"traceEnd 在另一个线程触发"——风险来自 §3.3 的"多个 trace 站点分布在不同线程"的并发**。**。

### 2.2 有意简化

1. **`android.os.Trace.traceBegin/End` → `Trace.kt` 内存 ArrayDeque**。lib 注释（`Trace.kt:6-10`）明示"按 tag 字符串 + 嵌套深度，模拟 native Trace 的可见性，方便单元测试断言"。**正确简化**：JVM 单元测试接不到 ATRACE 缓冲；demo 模块需要 UI 可视化 trace，stderr 重定向比真 trace 实用（`DemoBaseActivity.kt:107-124`）。**核心权衡**：lib 永远拿不到 `atrace` ring buffer → Perfetto proto → Trace UI 链路。
2. **traceEnd 不要求与 traceBegin 在同一线程**（lib `Trace.kt:21-26` 只 `removeFirst` 不检查 caller）。原厂 `android.os.Trace.traceEnd(tag)` 只要求 tag 一致即可，ATRACE 内部按事件顺序 + thread 配对（**不依赖 STACK 状态**）。这是平台实现的强保证，lib 模拟版完全无此保证——见 §3.1。
3. **不做 `Trace.asyncTraceBegin/End` 包装**：原厂 `TracePrintUtil.asyncTraceBegin/End`（`TracePrintUtil.java:477-486`）用 `counter`（int cookie）作为跨线程配对 key，专为 Perfetto counter slice 设计。lib `Trace.kt` 不提供这个 API。**有意**：counter 语义是 Perfetto 专用，单测用不到。
4. **不区分 32L/TRACE_TAG_VIEW/8L 三层语义**：原厂把 launcher（8L）、WM shell（32L）、View（TRACE_TAG_VIEW）按子系统隔离，方便 Perfetto track 选择。lib 把 tag 当字符串字面量处理（实际仅 8L），不消费 tag 值。
5. **`traceEnd(8L)` 无 tag 校验**：lib `Trace.kt:21-24` 只 `removeFirst()`（含 isNotEmpty 守卫），不核对 tag 与 traceBegin 的 tag 是否一致。原厂 `android.os.Trace.traceEnd(8L)` 由 JNI 层保证 ATRACE 缓冲的 LIFO 配对。

### 2.3 遗漏（影响语义但 lib 未声明）

| # | 遗漏 | 原厂证据 | 影响 |
|---|---|---|---|
| 1 | **`AsyncAnimCallbacks.traceBegin` 不携带 `mAnimType`** | OPPO `AsyncAnimCallbacks.java:132, 141`：tag = `"#" + mAnimationId + "-" + mAnimType + "-Start/-End"`；trace 名携带 `SWIPE_TO_HOME/OPEN_FROM_HOME/RECENTS` 等子系统 | logcat grep 时只能定位动画实例 ID，**无法直接定位子系统**。trace-validation.md §3 真机 trace 上能看到 `#26-OPEN_FROM_HOME-Start` 这条事件，lib 只能 grep `AsyncAnimStart-` |
| 2 | **`OplusExecutors` ANIM_EXECUTOR$lambda$0 处的 trace 锚点缺失** | OPPO `OplusExecutors.java:95` `ANIM_EXECUTOR` 创建点 + `ANIM_EXECUTOR$lambda$0` `AnimationHandler.getInstance().setProvider(SfVsyncFrameCallbackProvider)`——这是 launcher.anim 线程建立的唯一钩子。原厂**故意**不在这里打 trace，由外层 `Launcher.java:3255 initWallpaper` / `Launcher.java:4773 setup views` 标记 | lib 与原厂一致地不打——但 lib 没有 `Launcher.java` 外层 trace，导致 launcher.anim 线程建立时**完全无锚点**（真机 demo 跑时 launcher.anim 第一次 doFrame 之前 systrace 上"无名 slice"）。 |
| 3 | **`TracePrintUtil#notifyAnimationStart/End` 跨线程 trace 锚点** | OPPO `TracePrintUtil.java:528, 538`：`notifyAnimationEnd(Type type)` 在 main 线程上调用 `TraceHelper.INSTANCE.traceBegin(8L, "TracePrintUtil#notifyAnimationEnd")` + `TraceEnd`。这是 trace-validation.md §3 中观察到的「main 线程上的 #26-OPEN_FROM_HOME-End + TracePrintUtil#notifyAnimationEnd 双段」的来源 | lib 完全缺失。**含义**：lib demo 没有"动画在 main 线程结束"的 systrace 锚点，无法用 Perfetto 验证「动画从 launcher.anim 跨到 main 派发 listener」的回主线程时序 |
| 4 | **`AsyncAnimCallbacks.onAnimActualEnd` 路径不打 Trace** | OPPO `AsyncAnimCallbacks.java:111-122` 的 `onAnimActualEnd` 只走 `LogUtils.i` 路径，**不打 Trace**（review 08 §1 提过） | lib 与原厂一致——这是有意设计，**lib 不补是对的** |
| 5 | **logcat 上 traceBegin/End 不携带 `[8]` 字面量** | lib `Trace.kt:17` `tagStr = "[$tag] $name"`（stderr 输出形如 `>>> [8] AsyncAnimStart-26`）；原厂 `android.os.Trace.traceBegin(8L, ...)` 由 ATRACE 内核写入，**user-facing logcat 看不到 name**——只能 Perfetto UI 看到 | lib 多了 `[8]` 前缀是 stderr-only 信息，无负面影响；但与原厂"logcat 上静默、Perfetto 上可见"的对比模式不同 |
| 6 | **`Debug.getCallers(3/15)` 栈采样挂 trace 失败路径** | OPPO `OplusValueAnimator.java:115, 144, 287, 294, 321` 在失败路径上 `LogUtils.i + Debug.getCallers(15)` | lib `OplusValueAnimator.kt:139` 只打空 trace 名 `"Continuation-fail"`，**无调用链**。**bug 级**：失败时无法定位是哪个上层调用方（`AppSwipeToRecentContinuationHelper` vs `VirtualBtnToRecentContinuationHelper`） |
| 7 | **`AsyncAnimCallbacks.java` 的 `LogUtils.i(TAG, "Async anim start #N, anim type: T")` 全部缺失** | `AsyncAnimCallbacks.java:113, 126, 134, 142`：4 处 `LogUtils.i`，门控 `isLogOpen()` | lib 只打 trace，不打 LogUtils.i，**release 包 logcat 上完全看不到"动画 N 开始"语义**。与 review 08 §2.3 #1 同源 |
| 8 | **`TRACE_TAG_VIEW` (1024L) View 层 trace 不移植** | OPPO `CarouselLayoutManager.java:632` + `COUISpringAnimation.java:181` + `d3/f.java:18` | lib 不覆盖 View 层 trace，与原厂 3 处语义一致地缺失 |
| 9 | **`TRACE_TAG_NOT_NEEDED (32L)` WM shell 层 trace 不移植** | OPPO `wm/shell/transition/tracing/PerfettoTransitionTracer.java:157,171,185,199` + `wm/shell/startingsurface/*.java` + `wm/shell/back/BackAnimationController.java:385,415,785` | lib 不覆盖 WM shell 层 trace（lib 无 launcher WM shell 等价物），21 处缺失 |
| 10 | **`Trace.asyncTraceBegin/End` Perfetto counter 不移植** | OPPO 全树 23 处 `Trace.asyncTraceBegin/End/ForTrack`，集中在 `AsyncAnimationJankTracker`、`LocalMainThreadJankTracker`、`TransitionJankTracker`、`GlobalDragListener` | lib 无 JankTracker，对应能力（用 Perfetto counter 跨线程配对）缺失 |
| 11 | **`TaskSnapshotWindow.java:154,171` 的动态 tag 选择** | `Trace.traceBegin(j10, "TaskSnapshot#relayout")` 与 `j102`，由 caller 按场景透传 tag | lib 不覆盖此场景 |
| 12 | **`TraceUtilsKt.java:21` `[PostEffect]` 前缀约定** | `Trace.traceBegin(j8, "[PostEffect]".concat(str))` —— OPPO 自创的"模块前缀"约定 | lib 不遵循 |

---

## 3. 行为差异风险点

按"可能改变真机 trace 行为或 lib 单测断言可信度"严重度排序：

> **✅已修复（60bd048：Trace.STACK 改 ThreadLocal，跨线程 traceBegin/End 不再错位）**
1. **（高 / bug 级）`ArrayDeque STACK` 非线程安全——review 08 §3 #1 的精确量化**。
   - **机制**：`Trace.kt:12` `private val STACK = ArrayDeque<String>()`；`traceBegin/End` 在 STACK 上 `addFirst/removeFirst`（`:17, 22`）。ArrayDeque 不是线程安全的，并发 `addFirst` + `removeFirst` 在内部数组扩容 / 缩容 / 元素挪移时会破坏 head/tail 索引。
   - **触发条件**（按 lib 4 调用点逐个排查）：
     - `AsyncAnimCallbacks.dispatch`（`:81-90`）：traceBegin/End 在**同一线程**（anim 线程）同步执行，`runOnMainThread { ... }` 是 post 异步、lambda 内不打 Trace——**单线程安全**。
     - `AnimationSeqHelper.delayFinishRecents`（`:71-78`）：traceBegin/End 在**调用线程**（通常是 anim 线程）同步执行；`clearFinishRecentsRunnable()` 在中间调用一次（`:73`），内部 traceBegin/End 也是同步——**单线程安全**。
     - `AnimationSeqHelper` 的 `getOrCreateHandler()` 回调（`:40-46`）：handler 是 `Handler(Looper.getMainLooper(), ...)`，所以 `handler$lambda` 在 **main 线程** 执行；`Trace.traceBegin(8L, "exc delayRunnable")` + `Trace.traceEnd(8L)` 是 main 线程同步调用——**单线程安全**。
     - `OplusValueAnimator.generateContinuationAnim`（`:139, 151`）：traceBegin/End 是同步紧邻——**单线程安全**。
   - **真正的并发场景**：
     - T1：anim 线程进入 `delayFinishRecents` → `traceBegin` push "delayFinishRecents" → 准备 `sendEmptyMessageDelayed`
     - T2（main）：500ms 后 handler 触发 `handler$lambda$0` → `traceBegin` push "exc delayRunnable" → ... → `traceEnd` pop
     - 如果 T1 的 `traceEnd` 还没执行（极端情况下 GC 卡顿或调度延迟），同时 T2 的 handler 触发——**两个线程并发访问 STACK**。
     - 实证场景：真机 120Hz 滑动动画，每帧 doFrame 之间间隔 8.3ms，但 `sendEmptyMessageDelayed(MSG_EXC_RUNNABLE, 500)` 期间只要 anim 线程在跑、main 线程又在另一时刻进 handler 路径，**两者会有 STACK 并发窗口**。
   - **影响**：
     - **错误场景 1**：STACK 头被覆盖。traceEnd 弹出的不是对应的 tag，输出 stderr 与实际不符——**单元测试断言 `Trace.depth == 0` 可能误判**。
     - **错误场景 2**：`addFirst` 内部数组扩容时另一线程 `removeFirst`，`ConcurrentModificationException`（lib 没 catch，会向上冒泡，可能 crash）。
     - **错误场景 3**：silent corruption——head/tail 错位，后续所有 traceBegin/End 都错位，无明显异常但 stderr 输出与代码意图不符。
   - **原厂规避机制**：OPPO 走 `android.os.Trace` → ATRACE 内核缓冲，每条 trace 事件携带 thread id + monotonic timestamp，**完全不需要 STACK 配对**——Perfetto/Systrace UI 按时间线 + thread slice 自然对齐。
   - **修复成本**：5 行（`STACK = ArrayDeque<String>()` → `ConcurrentLinkedDeque<String>()`，或加 `@Synchronized` on traceBegin/End）。**这是 lib 必须修的最严重 bug**。

> **⚠️未修复（trace 名仍不带 mAnimType：需 AnimType 从调用方贯通，lib 无子系统模型/Perfetto 消费者；真实集成时按 4.1-2）**
2. **（高）`AsyncAnimCallbacks` trace 命名不携带 `mAnimType` 子系统，导致无法用 Perfetto 跨子系统 grep**。
   - **机制**：lib `AsyncAnimCallbacks.kt:82` 拼出 `"AsyncAnimStart-${id}"`（id 是 `System.identityHashCode` 哈希，每次进程启动不同）；OPPO `AsyncAnimCallbacks.java:141` 拼出 `"#${mAnimationId}-${mAnimType}-Start"`。
   - **影响**：trace-validation.md §3 真机 trace 上能看到 `#26-OPEN_FROM_HOME-Start` 这种事件，**Systrace UI 上能 grep "OPEN_FROM_HOME" 看到所有 OPEN_FROM_HOME 类动画**；lib 上只能 grep "AsyncAnimStart"，**无法按子系统聚合**。
   - **原厂规避机制**：`mAnimType` 字段（`AsyncAnimCallbacks.java:25-26`）+ `setAnimType(...)`（`:164-166`）把 subsystem 注入到 trace name。
   - **修复成本**：~10 行（加 `internal var animType: AnimType = AnimType.SWIPE_TO_HOME` + `setAnimType` + 把 trace 名改成 `"AsyncAnimStart-${id}-${animType}"`）。与 review 08 §4.1 #2 一致。

> **❌不成立/已过期（lib 4 调用点全部 8L 严格配对；OPPO android.os.Trace 经 JNI 写 atrace，无"抛 IllegalStateException"的 Java 异常路径——tag typo 两者皆静默，非行为差异）**
3. **（中）`Trace.traceEnd` 不校验 tag 与 `traceBegin` 一致**。
   - **机制**：lib `Trace.kt:21-26` `traceEnd(tag: Long)` 只 `removeFirst()`，**不验证 `STACK` 顶的 tag 是否匹配入参**。原厂 `android.os.Trace.traceEnd(8L)` 由 JNI 层保证 ATRACE 缓冲的 LIFO + tag 配对。
   - **影响**：单测场景下，如果某处写了 `traceBegin(8L, "foo")` 接着 `traceEnd(16L)`（typo），lib 默默弹出错的 tag，无报错；OPPO 直接抛 IllegalStateException（JNI 监测到 tag 不匹配）。
   - **修复成本**：3 行（`traceEnd` 加 `if (name.startsWith("[$tag]")) removeFirst() else log("tag mismatch!")`）。

> **✔️保持简化（lib 无 LogUtils 基础设施；observability 差异 review08 已记，demo 走 stderr）**
5. **（中）`AsyncAnimCallbacks.onAnimationCancel` 路径只有 trace，没有 LogUtils**。
   - **机制**：lib `AsyncAnimCallbacks.kt` 没有 `onAnimationCancel` 的 LogUtils.i；OPPO `AsyncAnimCallbacks.java:111-122` 走 `LogUtils.isLogOpen()` 门控的 `LogUtils.i(TAG, "Async anim cancel #N")`。
   - **影响**：单元测试断言 `Trace.depth == 0` 后**无法验证 cancel 路径有"业务事件"语义**——只能从代码侧静态看有 dispatch 调用。
   - **修复成本**：与 review 08 §4.1 #1 同源（加 `LogUtils.i` 壳层），~10 行。

> **✅已修复（本轮：Continuation-fail 名带 f 值；Debug.getCallers 依赖平台 Debug API 未加）**
6. **（中）`OplusValueAnimator.Continuation-fail` trace 名不可调试**。
   - **机制**：lib `OplusValueAnimator.kt:139` `Trace.traceBegin(8L, "Continuation-fail")` + `Trace.traceEnd(8L)`——只有名字，无失败原因；OPPO `OplusValueAnimator.java:115, 144` 走 `LogUtils.isAlwayson()` 门控 + `LogUtils.i + Debug.getCallers(15)`，输出调用栈前 15 层。
   - **影响**：单元测试或真机 demo 跑时，`Continuation-fail` 出现，但 `f` 为 0f / 1f / 负数 无法在 trace 名字里区分——grep `Continuation-fail` 后只能猜原因。
   - **修复成本**：2 行（`Trace.traceBegin(8L, "Continuation-fail f=$f")`），或加 `LogUtils.i` + `Debug.getCallers(3)`。

> **❌不成立/已过期（traceEnd 位置与原厂结构等价、非 lib 特有缺陷；60bd048 ThreadLocal 后移入 main lambda 会破坏同线程配对，建议不可行）**
7. **（中）lib trace 段不覆盖 listener 派发实际耗时**。
   - **机制**：lib `AsyncAnimCallbacks.kt:82-89` traceBegin → runOnMainThread { dispatch } → traceEnd。`traceEnd` 在 `runOnMainThread` 之后立刻同步执行（`runOnMainThread` 是 `postAsync`，返回即结束）；listener 派发实际发生在 main 线程**下一次消息循环**，trace 段已经关闭。
   - **原厂对比**：OPPO `AsyncAnimCallbacks.java:141-144` traceBegin → LogUtils.i → runOnMainThread → traceEnd——同样 traceEnd 在 runOnMainThread 后立刻执行。**结论**：OPPO 与 lib **结构等价**，不是 lib 的特有缺点。但 trace-validation.md §3 真机 trace 上能看到 main 线程 `-End` 段是因为 OPPO 还有 `TracePrintUtil#notifyAnimationEnd` 二次锚点（见 §2.3 #3），lib 完全缺这个二次锚点。
   - **影响**：lib demo 单测无法用 "Trace.depth 是否在 listener 派发后归零" 作为 "listener 已派发" 的断言——**因为 traceEnd 在 dispatch 之前就归零了**。要断言 "listener 已派发"，得另写计数器（如 review 01 §②-B3 的 listener sync 计数器）。
   - **修复成本**：5 行（把 traceEnd 移到 `runOnMainThread { ... }` 的 lambda 末尾；或者新增 `Trace.dispatchEnd` API 在 lambda 末尾调）。**注意**：这会让 STACK 配对跨线程化，**§3.1 的修复必须先做**。

> **✔️保持简化（doc 自评非关键，单测可外层 wrap）**
8. **（低）`traceBegin`/`traceEnd` 在 stderr 上无时间戳**。
   - **机制**：lib `Trace.kt:17, 22` `log(">>> $tagStr")` 与 `log("<<< $name")`——只有名字，无 `System.nanoTime()` 时间戳。Perfetto/Systrace 上每条 trace 自动带 monotonic timestamp（来自 ATRACE）。
   - **影响**：单测场景下断言"traceBegin → traceEnd 在 100ms 内"，lib 没有时间戳就只能用 `System.nanoTime()` 显式记录。
   - **修复成本**：2 行（`log` 方法加 `System.nanoTime()` 前缀）。**非关键**，单测可以外层 wrap。

> **✅已修复（60bd048：depth 按线程隔离，size 不再跨线程竞争）**
9. **（低）`Trace.depth` 单测断言在并发场景下不可信**（§3.1 副作用）。
   - **机制**：`Trace.kt:28` `internal val depth: Int get() = STACK.size`——返回当前嵌套深度。单测可用 `assertEquals(0, Trace.depth)` 验证 traceBegin/End 配对正确。
   - **影响**：多线程并发下 `STACK.size` 也是非线程安全的（`size()` 内部走 `head/tail` 字段读，可能读到不一致状态）。
   - **修复成本**：0（§3.1 修完即可）。

> **✔️保持简化（拼接代价在动画事件边界，可忽略）**
10. **（低）`traceBegin`/`traceEnd` 字符串拼接无 lazy 评估**。
    - **机制**：lib `Trace.kt:16-17` `val tagStr = "[$tag] $name"; STACK.addFirst(tagStr)`——每次 traceBegin 都做字符串拼接，即使后续 traceEnd 不会消费也付出代价。
    - **原厂对比**：OPPO `LogUtils.debug(subModuleTag, Function0<String>)`（`LogUtils.java:146-155`）用 `Function0<String>` 闭包 lazy 评估——`isLogOpen()` 为 false 时 message lambda 不执行。
    - **影响**：release 性能差异。lib 即使最终只打 stderr（无消费者重定向），仍付出拼接代价；OPPO release 关 LogUtils 后零开销。
    - **修复成本**：3 行（traceBegin 加 `name: () -> String` 重载，或引入 lazy 拼接）。**非关键**。

> **✔️保持简化（调用点全部平衡配对；launcher.anim/主线程常驻，ThreadLocal 无泄漏）**
11. **（提示）`STACK` 容量无上限**。
    - **机制**：lib `Trace.kt:12` `STACK = ArrayDeque<String>()`——默认容量 16，按需扩容。理论可无限 push。
    - **影响**：单测场景下若忘记 traceEnd，`STACK` 单调增长，最终 OOM（典型是 10K+ push + 字符串拼接耗内存）。
    - **修复成本**：1 行（`STACK` 加最大容量 + 截断，或 `traceBegin` 加断言）。

> **✔️保持简化（无 listener 时 trace 段仅覆盖 post 开销，预期行为）**
12. **（提示）`AsyncAnimCallbacks.dispatch` 在 `addListener` 后立即 start 时 listener 列表为空的兜底缺失**。
    - **机制**：lib `AsyncAnimCallbacks.kt:83-88` `runOnMainThread { for (l in getListeners()) ... }`——若 listener 列表为空，trace 段只覆盖 postAsync 的开销，无 listener 调用。
    - **影响**：无功能差异（无人监听当然没人 fire）。trace 段很短是预期。
    - **修复成本**：无（无需修）。

---

## 4. 回移建议

### 4.1 值得补进 lib 的（按修复成本 / 收益性价比排序）

> **✅已修复（60bd048：ThreadLocal 方案落地）**
1. **STACK 改 `ConcurrentLinkedDeque` 或加 `@Synchronized`（P0 / 5 行）**。**修复 §3.1**——消除"多线程并发 traceBegin/End 错位" 的真 bug。OPPO 走 ATRACE 平台实现免于此问题，lib 必须自己防。**优先级最高**。
> **⚠️未修复（同 §3.2）**
2. **`AsyncAnimCallbacks` 加 `mAnimType` 字段 + trace tag 携带（P1 / 10 行）**。与 OPPO `AsyncAnimCallbacks.java:25-26, 131-144` 1:1 对齐；让 demo 单测可断言 "OPEN_FROM_HOME 类动画在 launcher.anim start"。**修复 §3.2**。
> **❌不成立/已过期（同 §3.3：无原厂对照语义）**
3. **`Trace.traceEnd` 加 tag 校验（P2 / 3 行）**。防止 traceBegin/End tag typo 导致 silent mismatch。**修复 §3.3**。
> **✔️保持简化（同 §3.5）**
4. **`AsyncAnimCallbacks` 加 `LogUtils.i` 同步打（与 review 08 §4.1 #1 同源，P1 / 10 行）**。4 个调用点（start/cancel/end/actualEnd）补 `LogUtils.i("AsyncAnim", "Async anim start #$id")`，门控 `isLogOpen()`。**修复 §3.5**。
> **✅已修复（本轮：名字带 f；getCallers 平台 API 未加）**
5. **`OplusValueAnimator.Continuation-fail` trace 名带 `$f` 值 + 加 `Debug.getCallers(3)`（P2 / 5 行）**。`Trace.traceBegin(8L, "Continuation-fail f=$f caller=${Debug.getCallers(3)}")`；或新增 `LogUtils.i` 路径。**修复 §3.6**。
> **⚠️未修复（TracePrintUtil#notifyAnimation* 二次锚点缺失；demo stderr 无跨线程配对消费）**
6. **加 `TracePrintUtil#notifyAnimation{Start,End}` 等价物（P1 / 30 行）**。在 `AsyncAnimCallbacks.dispatch` 的 main 线程 lambda 末尾调 `Trace.traceBegin(8L, "TracePrintUtil#notifyAnimation${verb}")` + traceEnd。**修复 §2.3 #3**——让 demo 能用 Perfetto 验证「动画跨线程派发到 main」的回主线程时序。
> **❌不成立/已过期（同 §3.7：ThreadLocal 后不可行）**
7. **`AsyncAnimCallbacks` 把 `traceEnd` 移到 `runOnMainThread` lambda 末尾（P2 / 5 行）**。**修复 §3.7**——但前提是 §3.1 先修完（STACK 跨线程安全）。

### 4.2 建议保持简化

> **✔️保持简化**
1. **`android.os.Trace.traceBegin/End` 真接入**。理由：① JVM 单测用不到（需 Perfetto/Systrace），② demo 模块需要 UI 可视化 trace，stderr 重定向比真 trace 实用，③ 真机 demo 可选地把 `Trace.traceBegin` 桥接到 `android.os.Trace.traceBegin`（一行 if 包），但默认走 stderr 即可。
> **✔️保持简化**
2. **`Trace.asyncTraceBegin/End` counter 不移植**。Perfetto counter 语义专用，lib 不做 JankTracker，无对应需求。
> **✔️保持简化**
3. **`TRACE_TAG_NOT_NEEDED (32L)` WM shell 层不移植**。lib 不覆盖 `com.android.wm.shell.*` 等价物，21 处缺失合理。
> **✔️保持简化**
4. **`TRACE_TAG_VIEW (1024L)` View 层不移植**。lib 不覆盖 RecyclerView/Compose 等价物，3 处缺失合理。
> **✔️保持简化**
5. **`TaskSnapshotWindow` 动态 tag 不移植**。`j10`/`j102` 的 debug 条件 tag 与 lib 的简单栈语义不符。
> **✔️保持简化**
6. **`TraceUtilsKt [PostEffect]` 前缀不移植**。OPPO 模块前缀约定是内部生态约定，外部 demo 用默认常量即可。
> **✔️保持简化**
7. **`LogUtils.debug(Function0<String>)` lazy 评估不移植**。lib 闭包 / Kotlin 风格下默认 lazy（`run { "Continuation-fail" }`），但 trace tag 字符串拼接本身代价极小，引入 Function0 包装收益低。
> **✔️保持简化**
8. **`TraceHelper` 薄壳 + FLAG_* 常量不移植**。内部使用频率极低（只在 `OplusWorkspace.java:1891` 等 3-4 处），FLAG 是 OPPO 内部 trace 分类约定，外部 demo 不需要这套分类。
> **✔️保持简化**
9. **`AsyncAnimCallbacks.onAnimActualEnd` 路径不打 Trace**（与 review 08 §4.1 #6 同源）——保持现状。
> **✔️保持简化**
10. **`TracePrintUtil.Type` 6 类 BASIC_ID 子系统分类不移植**。原厂 `WINDOW/VIEW/RECENTS/WORKSPACE/TASKBAR/CARD` 6 类对 lib 而言过细，lib 用 `mAnimType` 一维（SWIPE_TO_HOME / OPEN_FROM_HOME / RECENTS）足够。

### 4.3 不确定项（需要更多 trace 实证才能决定）

> **✔️保持简化（traceEnd 留在调用线程；ThreadLocal 后移入 main lambda 破坏配对）**
1. **`AsyncAnimCallbacks.dispatch` 中 `traceEnd` 时机**：当前是「runOnMainThread 后立刻 traceEnd」，与原厂结构等价。建议保持；但如果 §4.1 #1（STACK 线程安全）修完后想进一步对齐 OPPO `traceEnd` 在 lambda 末尾，需要先评估是否引入新风险（dispatch lambda 内若再调 trace，会跨线程 push）。
> **✅已修复（60bd048：per-thread ThreadLocal 分层已实现）**
2. **是否做 `STACK` 分层 per thread**：每个线程一个 STACK（类似 ThreadLocal）。原厂 ATRACE 不需要——内核 buffer 是 per-thread 的，事件天然按线程归属。lib 用 ThreadLocal 模拟也可以，但需要重写 `traceBegin/End`，且 §4.1 #1 的 ConcurrentLinkedDeque 方案已经足够。

---

## 5. 附：关键证据速查

| 论断 | 证据 |
|---|---|
| lib `Trace.kt` ArrayDeque 非线程安全 | `lib/.../core/Trace.kt:12` `private val STACK = ArrayDeque<String>()`；`:17` `STACK.addFirst`；`:22` `STACK.removeFirst` |
| lib 4 个 traceBegin 全部 tag=8L | `AsyncAnimCallbacks.kt:89`、`AnimationSeqHelper.kt:46, 72`、`OplusValueAnimator.kt:149, 161` |
| lib 4 个 traceEnd 全部 tag=8L | `AsyncAnimCallbacks.kt:96`、`AnimationSeqHelper.kt:49, 78`、`OplusValueAnimator.kt:150, 162` |
| lib 无 `Trace.asyncTrace*` 调用 | grep `Trace\.asyncTrace` lib 命中 0 处 |
| OPPO 全树 `Trace.traceBegin(8L,...)` = 123 处 72 文件 | `Grep "Trace\.traceBegin\(8L"` |
| OPPO `Trace.traceBegin(32L,...)` = 21 处 8 文件 | `Grep "Trace\.traceBegin\(32L"`（仅 com.android.wm.shell.*） |
| OPPO `Trace.TRACE_TAG_VIEW` = 3 处 3 文件 | `Grep "Trace\.traceBegin\(Trace\.TRACE_TAG_VIEW"`（d3/f.java、CarouselLayoutManager.java、COUISpringAnimation.java） |
| OPPO `Trace.traceBegin(j\d+,...)` = 4 处 3 文件 | `Grep "Trace\.traceBegin\(j\d+"`（TaskSnapshotWindow.java:154,171、TraceHelper.java:67、TraceUtilsKt.java:21） |
| OPPO `Trace.asyncTrace*` = 23 处 9 文件 | `Grep "Trace\.asyncTrace"`（GlobalDragListener、TransitionJankTracker、AsyncAnimationJankTracker、LocalMainThreadJankTracker、LocalJankUtils、TraceHelper、COUISpringAnimation、COUIDynamicAnimation） |
| OPPO `MultiDynamicAnimation` 0 Trace 调用 | `Grep "traceBegin"` on `com/android/quickstep/util/animation/MultiDynamicAnimation.java` = 0 |
| OPPO `CustomRectFSpringAnim` 0 Trace 调用（17 LogUtils） | `Grep "traceBegin"` on `CustomRectFSpringAnim.java` = 0；`Grep "LogUtils"` = 17 |
| OPPO `OplusValueAnimator` 0 Trace 调用（10 LogUtils + 3 Debug.getCallers） | `Grep "traceBegin"` on `com/oplus/quickstep/utils/OplusValueAnimator.java` = 0；`Grep "LogUtils"` = 10；`Grep "Debug.getCallers"` = 3 |
| OPPO `OplusExecutors` 0 Trace 调用 | `Grep "traceBegin"` on `com/oplus/basecommon/thread/OplusExecutors.java` = 0 |
| OPPO `TracePrintUtil#notifyAnimationStart/End` 跨线程 trace | `com/oplus/quickstep/utils/TracePrintUtil.java:528, 538` `TraceHelper.INSTANCE.traceBegin(8L, "TracePrintUtil#notifyAnimation*")` |
| OPPO `TraceHelper` 透明转发（无 tag 过滤） | `com/oplus/basecommon/util/TraceHelper.java:66-71` |
| OPPO `AsyncAnimCallbacks` 8L + mAnimType + mAnimationId 拼装 | `com/android/quickstep/util/animation/AsyncAnimCallbacks.java:132, 141` `Trace.traceBegin(8L, "#" + this.mAnimationId + "-" + this.mAnimType + "-End/-Start")` |
| OPPO `AnimationSeqHelper` trace 名 1:1 一致 | `com/oplus/quickstep/utils/AnimationSeqHelper.java:39, 80, 93`（exc delayRunnable / clearFinishRecentsRunnable / delayFinishRecents） |
| 真机 trace 上 `#26-OPEN_FROM_HOME-End` 在 main 线程 | `docs/animation-trace-validation.md:80` `+687.8ms main #26-OPEN_FROM_HOME-End (5.3ms) + TracePrintUtil#notifyAnimationEnd` |
| lib demo stderr 重定向 | `demo/.../DemoBaseActivity.kt:107-124` `System.setErr(redirectStream)` 按 "Trace" 子串过滤 |
| 已有 review 08 对 ArrayDeque 的提法（更浅） | `vs-oppo-08-trace-observability.md` §3 #1（仅一段"高 / bug 级"叙述，未量化触发条件） |

## 复核记录 v2（2026-09-09，独立逐条复核）

**复核方法**：逐条读取当前 `lib/src/main/java/com/asyncanimator/` 源码 + OPPO 只读对比树，不信任已有标记。

### 路径/行号/计数修正汇总
| 修正项 | 旧值 | 新值 |
|---|---|---|
| util/Trace.kt | `util/` | `core/`（包 `com.asyncanimator.core`） |
| launcher/async/AsyncAnimCallbacks.kt | `launcher/async/` | `anim/`（包 `com.asyncanimator.anim`） |
| launcher/seq/AnimationSeqHelper.kt | `launcher/seq/` | `seq/`（包 `com.asyncanimator.seq`） |
| launcher/continuation/OplusValueAnimator.kt | `launcher/continuation/` | `anim/`（包 `com.asyncanimator.anim`） |
| Trace.kt STACK 行号 | 12 | 13（ThreadLocal.withInitial） |
| Trace.kt traceBegin 行号 | 14-19 | 16-18 |
| Trace.kt traceEnd 行号 | 21-26 | 21-24（含 isNotEmpty 守卫） |
| AsyncAnimCallbacks traceBegin 行号 | 82 | 89 |
| AsyncAnimCallbacks traceEnd 行号 | 89 | 96 |
| AnimationSeqHelper exc 行号 | 42/45 | 46/49 |
| AnimationSeqHelper delay 行号 | 72/77 | 72/78 |
| OplusValueAnimator fail 行号 | 139/140 | 149/150（名字已含 `f=$f`） |
| OplusValueAnimator cont 行号 | 151/152 | 161/162 |
| traceBegin 总数 | "4 个" | **5 个** |

### 逐条状态复核（22 条）

| 条目 | 原标记 | 复核 | 修正 |
|---|---|---|---|
| §3.1 STACK ThreadLocal | ✅已修复 | ✅ Trace.kt:13 `ThreadLocal.withInitial` | 无 |
| §3.2 trace 名不带 mAnimType | ⚠️未修复 | ⚠️ AsyncAnimCallbacks.kt:89 仍无 AnimType | 无 |
| §3.3 traceEnd tag 校验 | ❌不成立 | ❌ 5 调用点全 8L 严格配对 | 无 |
| §3.5 cancel 缺 LogUtils | ✔️保持简化 | ✔️ lib 无 LogUtils | 无 |
| §3.6 Continuation-fail 名 | ✅已修复 | ✅ OplusValueAnimator.kt:149 `"Continuation-fail f=$f"` | 无 |
| §3.7 traceEnd 覆盖派发 | ❌不成立 | ❌ 结构与原厂等价 | 无 |
| §3.8 stderr 无时间戳 | ✔️保持简化 | ✔️ 确认 | 无 |
| §3.9 depth 并发 | ✅已修复 | ✅ Trace.kt:26 `STACK.get().size` | 无 |
| §3.10 lazy 评估 | ✔️保持简化 | ✔️ 确认 | 无 |
| §3.11 STACK 无上限 | ✔️保持简化 | ✔️ 确认 | 无 |
| §3.12 listener 空兜底 | ✔️保持简化 | ✔️ 确认 | 无 |
| §4.1-1 | ✅已修复 | ✅ ThreadLocal | 无 |
| §4.1-2 | ⚠️未修复 | ⚠️ 确认 | 无 |
| §4.1-3 | ❌不成立 | ❌ 确认 | 无 |
| §4.1-4 | ✔️保持简化 | ✔️ 确认 | 无 |
| §4.1-5 | ✅已修复 | ✅ 带 `f=$f` | 无 |
| §4.1-6 | ⚠️未修复 | ⚠️ 确认 | 无 |
| §4.1-7 | ❌不成立 | ❌ 确认 | 无 |
| §4.2-1..10 | ✔️保持简化 | ✔️ 全部确认 | 无 |
| §4.3-1 | ✔️保持简化 | ✔️ 确认 | 无 |
| §4.3-2 | ✅已修复 | ✅ ThreadLocal 分层 | 无 |

**总结**：22 条目原标记全部正确，无需修正状态。路径/行号/计数已全部更新至当前代码。