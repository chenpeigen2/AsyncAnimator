# vs-oppo-14 — ScheduledTickScheduler vs HandlerTickScheduler 双实现对照

> 范围：
> - lib 侧：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/core/scheduler/TickScheduler.kt`（接口）+
>   `D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/core/scheduler/ScheduledTickScheduler.kt`（JVM `scheduleAtFixedRate` 实现）+
>   `D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/animthread/HandlerTickScheduler.kt`（绑定 Looper 的 `postDelayed` 实现）。
> - 原厂侧：四套 AnimationFrameCallbackProvider 形态——vendored core `FrameCallbackProvider14/16`（`androidx/core/animation/AnimationHandler.java:33-108`）、
>   vendored dynamicanimation `FrameCallbackProvider14/16`（`androidx/dynamicanimation/animation/AnimationHandler.java:50-94`）、
>   OPPO 复制版 COUI `COUIAnimationHandler$FrameCallbackProvider14/16`（`com/coui/appcompat/animation/dynamicanimation/COUIAnimationHandler.java`，`ChoreographerSfVsync.java` 是 wm/shell 自家注解，跟 COUI 无关）、
>   框架 `@hide` `SfVsyncFrameCallbackProvider`（仅见 `com.android.internal.graphics` 包名，通过 `OplusExecutors.java:5` + `PipAnimationController.java:17` import 间接出现，反编译树里**无源**）。
>
> 不重复：vs-oppo-04 §3-②① 已经把"per-thread 帧语义被破坏"作为综述写过；本文只针对**两个 lib scheduler 实现之间的差异**与**它们各自对应的原厂形态**做单文件级的精确对照。
>
> 编号继续沿用：上一份是 `vs-oppo-13-anim-thread-init.md`，本份取 `14`。

---

## ① 类对应关系表（lib → 原厂 文件:行 证据）

| # | lib 元素 | 原厂对应 | 原厂证据 | 关系 |
|---|---|---|---|---|
| 1 | `core/scheduler/TickScheduler.kt:9-29`（接口 5 成员：`postFrameCallback`/`removeFrameCallback`/`start`/`stop` + `frameTimeNanos`/`frameCount`/`frameIntervalMs`/`FrameCallback`） | (a) `androidx.core.animation.AnimationHandler.AnimationFrameCallbackProvider` 接口 4 方法（`AnimationHandler.java:23-31`：getFrameDelay / onNewCallbackAdded / postFrameCallback / setFrameDelay）；(b) `androidx.dynamicanimation.animation.AnimationHandler.AnimationFrameCallbackProvider` 抽象类 1 方法（`AnimationHandler.java:39-47`：仅 `postFrameCallback`） | (a) `:23-31`；(b) `:39-47` | **三方接口成员差异较大**：(a) 4 方法（含 setFrameDelay 运行期调帧率），(b) 1 方法（帧率由内部 `FRAME_DELAY_MS = 10` 写死），lib 5 成员（`start/stop` 显式控生命周期）。lib 的 `start/stop` 是把 vendor 隐式的"provider 自己决定续帧"语义显式化 |
| 2 | `core/scheduler/ScheduledTickScheduler.kt:30-32` 用 `Executors.newSingleThreadScheduledExecutor { thread(name="AsyncAnimator-Tick", isDaemon=true) }` 建一个**进程级共享守护线程** | (a) `androidx.core.animation.AnimationHandler.FrameCallbackProvider14.sHandler` 是 **ThreadLocal**（`AnimationHandler.java:34, 47-55`：`new Handler(Looper.myLooper())`，按线程缓存）；(b) `FrameCallbackProvider14.mHandler = new Handler(Looper.myLooper())` 直接 new，**实例级不 ThreadLocal**（`AnimationHandler.java:60-66`），但 `AnimationHandler` 自身是 `ThreadLocal<AnimationHandler>`（`:13-14`），所以"每线程一个 AnimationHandler → 每线程一个 provider → 每线程一个 Handler" 间接成立 | (a) `:34, 47-55`；(b) `:60-66` + `:13-14` | **分歧**：lib `ScheduledTickScheduler` 是**进程级单例守护线程**（不是 ThreadLocal），所有不显式装 `HandlerTickScheduler` 的线程共用；vendor 三条路径（core/dynamicanimation/COUI）的帧源要么 ThreadLocal 要么实例级，但都被 ThreadLocal AnimationHandler 包了一层"按线程实例化"。详见 §3-① |
| 3 | `ScheduledTickScheduler.kt:62` `exec.scheduleAtFixedRate(::tick, 0, frameIntervalMs, TimeUnit.MILLISECONDS)` | (a) `FrameCallbackProvider14.postFrameCallback`（`AnimationHandler.java:60-67`）：`getHandler().postDelayed(this, max(mFrameDelay - (uptimeMillis - mLastFrameTime), 0L))`——**自走式**（Runnable.run() 在 Handler 上 fire，fire 完由 `mAnimationHandler.onAnimationFrame` 续派）；(b) `FrameCallbackProvider14.postFrameCallback`（`AnimationHandler.java:69-72`）：`mHandler.postDelayed(mRunnable, max(FRAME_DELAY_MS - drift, 0L))`，`FRAME_DELAY_MS = 10`；(c) 框架 `@hide` FrameCallbackProvider14 同 (a) | (a) `:60-67`；(b) `:14, 69-72` | **功能等价，时机模型不同**（详见 §2-A-2）：vendor 用的是 Handler `postDelayed` 链（每次 fire 后由 onAnimationFrame 自续），lib 用 `scheduleAtFixedRate`（一个 ScheduledFuture 周期任务）。两者在"掉帧时不堆积"这一性质上**正好相反**（§3-④） |
| 4 | `ScheduledTickScheduler.tick()`（`:70-89`）：`runCatching { cb.doFrame(t) }` 单 callback 异常隔离 + `if (callbacks.isEmpty()) stop()` 自停 | (a) `FrameCallbackProvider14.run`（`AnimationHandler.java:69-79`）：`mLastFrameTime = uptimeMillis; mAnimationHandler.onAnimationFrame(jUptimeMillis);` —— **无 try-catch、无空则停**（空则停逻辑在 `AnimationHandler.onAnimationFrame` `:197-202` 检查 `mAnimationCallbacks.size() > 0` 才续派 `mProvider.postFrameCallback()`）；(b) 同 (a) | (a) `:60-67, 197-202`；(b) `:69-72` + `:23-33` | **形状对齐，但异常语义不同**（§3-⑦）：vendor 异常沿 Runnable 链上抛到 Looper（→ uncaughtException），lib 用 `runCatching` 吞掉；自停行为 vendor 在 AnimationHandler 上，lib 下沉到 TickScheduler |
| 5 | `launcher/animthread/HandlerTickScheduler.kt:27` 构造参数 `handler: Handler?`（可为 null = JVM 单测退路） | (a) `FrameCallbackProvider14.sHandler` ThreadLocal（`:34, 47-55`）；(b) `FrameCallbackProvider14.mHandler` 实例字段（`:60-66`） | 同 #2 | **形式分歧**：vendor 是字段/ThreadLocal，lib 是构造参数；语义对齐——HandlerThread + Looper 天然保证"线程 = Looper 持有者"，不需要 ThreadLocal 缓存 |
| 6 | `HandlerTickScheduler.scheduleNextFrame()`（`:66-80`）：`handler.postDelayed({...tick...; if (!empty) scheduleNextFrame()}, frameIntervalMs)` 自走 + 空则停 | (a) `FrameCallbackProvider14.postFrameCallback`（`AnimationHandler.java:60-67`）同形态 Handler.postDelayed 续派；(b) `FrameCallbackProvider14.postFrameCallback`（`AnimationHandler.java:69-72`）同形，参数 `FRAME_DELAY_MS = 10`；(c) `FrameCallbackProvider16.postFrameCallback`（`AnimationHandler.java:75-94`）：**`Choreographer.getInstance().postFrameCallback(this)`**——挂 vsync，不挂 postDelayed 时钟 | (a) `:60-67`；(b) `:69-72`；(c) `:75-94` | **1:1 对齐 (a)(b)**；与 (c) 差一档（vsync 对齐，§3-⑤）。自停行为 vendor 在 AnimationHandler 上，lib 在 scheduler 内 |
| 7 | `HandlerTickScheduler.tick()`（`:82-91`）：`SystemClock.uptimeNanos()` 取时间 + `runCatching` 单 callback 隔离 + `if (count<0) reset` 长溢出保护 | (a) `FrameCallbackProvider14.run`（`AnimationHandler.java:69-79`）：`mLastFrameTime = SystemClock.uptimeMillis()`；(b) `FrameCallbackProvider16.doFrame`（`AnimationHandler.java:88`）：`onAnimationFrame(j8 / AnimationKt.MillisToNanos)`（nanos → ms）；(c) `FrameCallbackProvider16.doFrame`（`androidx/dynamicanimation/animation/AnimationHandler.java:75-94`）：`mDispatcher.dispatchAnimationFrame()` → 内部 `mCurrentFrameTime = SystemClock.uptimeMillis()` | (a) `:69-79`；(b) `:88`；(c) `:75-94` + `:23-27` | **时间源选型有分歧**（§2-B-2）：lib HandlerTickScheduler 用 `uptimeNanos()`（更细），ScheduledTickScheduler 用 `System.nanoTime()`（更粗）；vendor (a)(c) 用 `uptimeMillis()`，(b) Choreographer 给的 `frameTimeNanos`。两 lib 实现的 nanos → ms 换算在 `AnimationHandler.kt:85` 完成，**统一入口**与 (b) 对齐 |
| 8 | `launcher/animthread/HandlerTickScheduler.kt:29` `frameIntervalMs: Long = 16` 默认值 | (a) `FrameCallbackProvider14.mFrameDelay = 16`（`AnimationHandler.java:37`）默认 16；(b) `FrameCallbackProvider14.FRAME_DELAY_MS = 10`（`AnimationHandler.java:14`）默认 10；(c) `FrameCallbackProvider16` 不存帧率，调 `ValueAnimator.getFrameDelay()`（系统设置） | (a) `:37`；(b) `:14`；(c) `:89-91` | **1:1 对齐 (a)**；dynamicanimation (b) 更激进（10ms = 100Hz）；(c) 是 vsync 路径无需帧率字段 |
| 9 | `ScheduledTickScheduler.kt:60` `callbacks: ConcurrentLinkedQueue<TickScheduler.FrameCallback>()` 多线程 add/remove 安全 | (a) `mAnimationCallbacks: ArrayList<AnimationFrameCallback>`（`AnimationHandler.java:16`）——非线程安全，靠"所有访问在同一线程"的隐性契约保护；(b) `mAnimationCallbacks: ArrayList<AnimationFrameCallback>`（`AnimationHandler.java:18`）同形 | (a) `:16`；(b) `:18` | **结构分歧（lib 更安全）**：lib 用 `ConcurrentLinkedQueue`，vendor 用 `ArrayList`；这是 lib 的**主动安全化**（与 #2 守护线程组合后，跨线程 addFrameCallback 不会抛 `ConcurrentModificationException`），见 §2-B-3 |
| 10 | （lib 无对应实现） | 框架 `com.android.internal.graphics.SfVsyncFrameCallbackProvider`（@hide，反编译树**无源**，仅 `OplusExecutors.java:5` + `PipAnimationController.java:17` 两处 import）+ wm/shell `ChoreographerSfVsync.java`（注解） | `OplusExecutors.java:5`（`import com.android.internal.graphics.SfVsyncFrameCallbackProvider;`） + `:170`（`AnimationHandler.getInstance().setProvider(new SfVsyncFrameCallbackProvider())`）；`PipAnimationController.java:17`（import） + `:567`（`animationHandler.setProvider(new SfVsyncFrameCallbackProvider())`）+ `:50-55`（`mSfAnimationHandlerThreadLocal = ThreadLocal.withInitial(...)`） | **lib 缺**：没有 SF-vsync 替代品；`HandlerTickScheduler` 是 (a) `FrameCallbackProvider14` 的 Looper 绑定退化版（用 postDelayed 16ms 而非 vsync）。详见 §3-⑤、§4-B-1 |
| 11 | `TickScheduler.kt:21-24` `fun interface FrameCallback { fun doFrame(frameTimeNanos: Long) }` 单方法 SAM | (a) `AnimationFrameCallback { boolean doAnimationFrame(long j8) }`（`AnimationHandler.java:19-22`，boolean 返回"动画结束可摘除"语义）；(b) 同形 | (a) `:19-22`；(b) `:25-27` | **方法签名分歧**：lib 返回 `Unit`（不显式声明结束），vendor 返回 `Boolean`；这是 lib TickScheduler 与 AnimationHandler **职责切分**的副作用——"何时从 callbacks 移除"由 AnimationHandler 决策（lib `AnimationHandler.kt:onTick` 用 callbackSize 检查 + 移除置 null），不在 TickScheduler |
| 12 | `TickScheduler.kt:9-29` 接口未暴露 `setFrameDelay`/`getFrameDelay`/`onNewCallbackAdded` | (a) `AnimationFrameCallbackProvider` 接口含 setFrameDelay/getFrameDelay/onNewCallbackAdded/postFrameCallback 4 方法；(b) 仅 `postFrameCallback` 抽象 | (a) `:23-31`；(b) `:39-47` | **遗漏（vendor 接口位死方法）**：(a) `onNewCallbackAdded` 在两个 vendor 实现里都是空体（`AnimationHandler.java:57-59, 97-99`），vendor 也没用；详见 §2-C-1 |

> **路径总结**（与 vs-oppo-04 §3 末附表同源、深化）：
>
> - **路径 A**（vendored core）：`FrameCallbackProvider14` 是 `Handler(Looper.myLooper()).postDelayed(this, drift-adjusted 16ms)`，Handler 用 ThreadLocal 缓存——lib `HandlerTickScheduler` 1:1 对齐此形态（除 ThreadLocal 改为构造期注入）。
> - **路径 B**（vendored dynamicanimation）：`FrameCallbackProvider14` 同 A，但 `FRAME_DELAY_MS=10`；`FrameCallbackProvider16` 走 `Choreographer.getInstance().postFrameCallback`——lib 无 vsync 对齐形态。
> - **路径 C**（框架 @hide）：`SfVsyncFrameCallbackProvider` 挂 SF vsync——lib 无替代品，`HandlerTickScheduler` 是退化版。
> - **路径 D**（OPPO 复制的 DynamicAnimation）：用 `Choreographer.getSfInstance().getFrameIntervalNanos()` 对齐 delta——lib 也不复刻。

---

## ② 保真度评估

### A. 精确复刻（行为对齐原厂）

| # | 设计点 | 原厂证据（路径:行） | lib 证据 |
|---|---|---|---|
| 1 | "空 callbacks 即停帧"的自维持回路 | (a) `AnimationHandler.java:197-202` `onAnimationFrame` 末尾 `if (mAnimationCallbacks.size() > 0) mProvider.postFrameCallback()`——空则不再 post；(b) `AnimationHandler.java:23-33` `AnimationCallbackDispatcher.dispatchAnimationFrame` 末尾同形；(c) `COUIAnimationHandler.java:AnimationCallbackDispatcher.a()` 内联空判断 | `ScheduledTickScheduler.kt:85-87` `if (callbacks.isEmpty()) stop()`；`HandlerTickScheduler.kt:73-78` `if (!callbacks.isEmpty()) scheduleNextFrame() else running=false`——两 lib 实现都在 scheduler 内做空则停，对齐语义 |
| 2 | 自走式帧循环（provider fire → handler 续派） | (a) `FrameCallbackProvider14.run`（`:69-79`）：uptimeMillis → mAnimationHandler.onAnimationFrame → 由 onAnimationFrame 决定续派；(b) `FrameCallbackProvider14.mRunnable`（`:62-67`）：mLastFrameTime = uptimeMillis → mDispatcher.dispatchAnimationFrame | `HandlerTickScheduler.scheduleNextFrame()`（`:66-80`）内 lambda 续派自身；`ScheduledTickScheduler.tick()`（`:70-89`）由 `scheduleAtFixedRate` 自动续 |
| 3 | 帧时间取自"系统单调时钟"，单位 nanos（Choreographer 路径）或 ms（Handler.postDelayed 路径） | (a) `:69` `SystemClock.uptimeMillis()`；(b) `:88` Choreographer 给 `frameTimeNanos`，`:88` `/ AnimationKt.MillisToNanos` 转 ms；(c) `:25` `mCurrentFrameTime = SystemClock.uptimeMillis()` | `HandlerTickScheduler.kt:84` `SystemClock.uptimeNanos()`（对齐 Choreographer 路径 nanos）；`ScheduledTickScheduler.kt:73` `System.nanoTime()`（对齐 JVM 单测习惯，非 Android API）；`AnimationHandler.kt:85` 统一 `frameTimeNanos / 1_000_000L` nanos → ms（与 (b) 数值一致） |
| 4 | `ConcurrentLinkedQueue` / 等价多线程安全 add/remove（vendor 不安全，lib 主动安全化） | (a) `:16` `mAnimationCallbacks = new ArrayList<>()`（非线程安全）；(b) `:18` 同 (a) | `ScheduledTickScheduler.kt:32` `private val callbacks = ConcurrentLinkedQueue<TickScheduler.FrameCallback>()`；`HandlerTickScheduler.kt:30` 同——见 §B-3 |
| 5 | 长溢出保护（frameCount） | （vendor 无） | `ScheduledTickScheduler.kt:88-90` `if (count < 0) frameCountAtomic.set(0)`；`HandlerTickScheduler.kt:89-91` 同——lib 自加，vendor 未做（vendor frameCount 也不暴露） |
| 6 | 快照遍历 callback 列表 | （vendor 无显式快照，`for` 直接遍历 ArrayList） | `ScheduledTickScheduler.kt:78` `for (cb in callbacks.toTypedArray())`；`HandlerTickScheduler.kt:87` 同——lib 主动防"迭代中被外部 add/remove" |
| 7 | postFrameCallback 早退：null callback 不入列 | （vendor `AnimationHandler.addAnimationFrameCallback` 早退：if (animationFrameCallback == null) 内部逻辑走完才返回，但 `mAnimationCallbacks.contains(null) == false` 实际等价） | `ScheduledTickScheduler.kt:43` `if (callback == null) return`；`HandlerTickScheduler.kt:51` 同——显式早退 |

### B. 有意简化（lib 注释/文档中明示，或符合 v4 §8.1 / vs-oppo-04 §4 认可的设计取舍）

| # | 简化内容 | 原厂对应 | lib 取舍理由 |
|---|---|---|---|
| 1 | lib 统一一个 `TickScheduler` 接口取代 vendor 三套（core/dynamicanimation/COUI） | 三套不同形态（接口/抽象类）+ 两个隐藏 API（框架 SfVsyncFrameCallbackProvider / DynamicAnimation 私有路径） | `TickScheduler.kt:5-8` 注释明示：抽象出"每帧调用 callback"的本质。是"教学库聚焦"的合理归并 |
| 2 | `ScheduledTickScheduler` 用 `ScheduledExecutorService.scheduleAtFixedRate(0, 16ms)` 仿真 | (a) `FrameCallbackProvider14.postFrameCallback` 用 `Handler.postDelayed(this, drift-adjusted)`；(b) 同 (a)，10ms | `ScheduledTickScheduler.kt:11-17` 注释明示"对应 v3 文档 §2 中 FrameCallbackProvider14 的退化路径 —— 当 Choreographer 不可用时，用 Handler.postDelayed(this, 16) 定时驱动。本类是这种思路的纯 Java 实现"。纯 JVM 可测是 demo 目标 |
| 3 | 两个 TickScheduler 都不接 vsync（用 postDelayed 退化或 scheduleAtFixedRate 退化） | (c) `FrameCallbackProvider16.postFrameCallback`（`:104-108`）：`Choreographer.getInstance().postFrameCallback(this)`；(c) `FrameCallbackProvider16`（dynamicanimation `AnimationHandler.java:75-94`）同形；(框架 `SfVsyncFrameCallbackProvider` 挂 SF-vsync) | `HandlerTickScheduler.kt:14-17` 注释明示"真机上若把本类换成 per-thread Choreographer 适配（FrameCallbackProvider16 语义），即可获得 VSYNC 精度——本类保留同样接口，替换成本为零"——接口化预埋替换点 |
| 4 | `ScheduledTickScheduler` 用 `System.nanoTime()` 而非 `SystemClock.uptimeMillis()` | vendor 统一 `SystemClock.uptimeMillis()`（`AnimationHandler.java:69, 26`）；Choreographer 给 nanos | `ScheduledTickScheduler.kt:73` 取 `System.nanoTime()`——JVM 标准库可用，跨 Android/JVM 跑得通。Android 真机走 `HandlerTickScheduler` 用 `SystemClock.uptimeNanos()`（对齐 Choreographer 路径），统一入口在 `AnimationHandler.kt:85` 做 nanos→ms 换算 |
| 5 | `ConcurrentLinkedQueue` 替代 vendor `ArrayList` | (a) `:16` ArrayList；(b) `:18` ArrayList | lib 设计取舍：`ScheduledTickScheduler` 守护线程 tick + 任意线程 add 路径下，ArrayList 会抛 `ConcurrentModificationException`；CLQ 提供 lock-free 正确性。`HandlerTickScheduler` 同样用 CLQ——但其 callback add/remove 都在 Looper 线程，无并发场景；多写一层是冗余安全（无害） |
| 6 | 无 `setFrameDelay` / `getFrameDelay` | (a) `:212-215` `setFrameDelay(long)`；(a) `:193-195` `getFrameDelay()` 委托 `provider.getFrameDelay()`；(a) `:41-44` `setFrameDelay(long)` 默认 if ≤0 → 0 | lib 没运行期调帧率需求（demo 帧率由构造参数固定）；保持接口最小 |
| 7 | 无 `onNewCallbackAdded` 回调钩子 | (a) `:26` 接口方法 + (a) `:57-59, 97-99` 实现为空 | lib TickScheduler 不暴露此钩子——vendor 也没真用 |
| 8 | `frameCount` 字段暴露（vendor 仅 (b) `getFrameTime()` 暴露 mCurrentFrameTime） | (b) `AnimationHandler.java:107-113` `getFrameTime()` 暴露 mCurrentFrameTime | lib 加 `frameCount` 给单元测试用（`AnimationHandlerTest.kt:30-50` 验证 callbackSize 增减不依赖时间） |
| 9 | `runCatching` 异常隔离 | vendor (a) `:130-138` (b) `:147-156` 均无 try-catch | `ScheduledTickScheduler.kt:80`、`HandlerTickScheduler.kt:88` 单 callback 异常 swallow——lib 教学库倾向"单点失败不连累整帧"。注：**与原厂语义相反**，见 §3-⑦ |

### C. 遗漏（原厂有、lib 没有，且不一定是有意砍掉的）

1. **`setProvider` 运行时换帧源的真协议**
   - (a)(b) `AnimationFrameCallbackProvider` 接口均**不**暴露 `setProvider`；真正能换 provider 的是 vendor 框架 `(c)` `AnimationHandler.setProvider(...)`（被 `OplusExecutors.java:170` + `PipAnimationController.java:567` 调用），以及 (b) dynamicanimation `AnimationHandler.java:174-176` `public void setProvider(AnimationFrameCallbackProvider)`。
   - lib `AnimationHandler.replaceThreadScheduler`（`AnimationHandler.kt:162-169`）走的是**激进协议**：停旧 → 换新 → 重发 self-pulse。比 vendor setProvider 激进，可能丢一帧（vs-oppo-04 §3-②③ 已记录）。
   - **TickScheduler 接口层未暴露"换源协议"**——换源逻辑必须在 `AnimationHandler.swapScheduler`（`:114-119`）里手动实现，没有 provider 自描述能力。

2. **缺 `(c) FrameCallbackProvider16` vsync 对齐形态**
   - (c) core `FrameCallbackProvider16.postFrameCallback`（`AnimationHandler.java:104-108`）：`Choreographer.getInstance().postFrameCallback(this)`；
   - (c) dynamicanimation `FrameCallbackProvider16`（`AnimationHandler.java:75-94`）：同形 + `doFrame` 回调里 `mDispatcher.dispatchAnimationFrame()`；
   - (c) 框架 `SfVsyncFrameCallbackProvider`（@hide）：直挂 SF-vsync，`OplusExecutors.java:170` / `PipAnimationController.java:567` 是唯一已知用户。
   - lib `HandlerTickScheduler` 是 **(a)(b) FrameCallbackProvider14 的 Looper 绑定版**，**不**是 (c) vsync 版。
   - **后果**：demo 在 120Hz 屏上 HandlerTickScheduler 跑 60Hz 节奏（16ms 固定），掉帧场景无 vsync 兜底。

3. **`AnimationFrameCallbackProvider` 接口的 `onNewCallbackAdded` / `setFrameDelay` / `getFrameDelay` 钩子**
   - (a) 4 方法接口 `(a):23-31`；(b) 1 方法抽象类 `(b):39-47`。
   - lib `TickScheduler` 接口（`TickScheduler.kt:9-29`）只暴露 `postFrameCallback` / `removeFrameCallback` / `start` / `stop` + 3 字段——vendor 三个钩子全部没复刻。
   - **后果**：vendor `(a):175-178` 的 `onNewCallbackAdded` 是 vendor 也没用的死方法（`(a):57-59, 97-99` 空实现），缺它无影响；`setFrameDelay` / `getFrameDelay` 缺它则运行期调帧率无 API（demo 无此需求，可接受）。

4. **缺 `mDelayedCallbackStartTime` 延迟启动回调**
   - (b) `AnimationHandler.java:16` `SimpleArrayMap<AnimationFrameCallback, Long> mDelayedCallbackStartTime` + `:123-145` `isCallbackDue` + `addAnimationFrameCallback(cb, delayMs)`。
   - lib `AnimationHandler.kt:51` 无 delay 形参；TickScheduler 不暴露。
   - **后果**：复刻 dynamicanimation 系 `FlingSpringAnim`/`RectFSpringAnim` 的"启动后等 N ms 再 tick"语义无 API（当前 demo 无此调用方）。

5. **`PipAnimationController.lambda$new$0` 的"per-ThreadLocal 实例 + 自初始化时挂 SF-vsync"协议**
   - `PipAnimationController.java:50-55` `mSfAnimationHandlerThreadLocal = ThreadLocal.withInitial(() -> { AnimationHandler h = new AnimationHandler(); h.setProvider(new SfVsyncFrameCallbackProvider()); return h; })`。
   - lib 没有 `ThreadLocal.withInitial(Supplier<AnimationHandler>)` 的等价协议——`installThreadScheduler`（`AnimationHandler.kt:150-158`）要调用方手动管理"先 install 再用"的顺序（vs-oppo-04 §3-②② 已记为 bug 级）。
   - **后果**：lib 不能在"任何线程首次访问 instance 时自动装帧源"——必须显式 install，错序即静默 no-op。

---

## ③ 行为差异风险点

按严重度排序，**🟥 标 BUG-LEVEL**：

### 🟥 ① ScheduledTickScheduler 破坏 v4 §8.1 "per-thread 帧语义"（高 / BUG-LEVEL，**核心**）

**证据**：`ScheduledTickScheduler.kt:30-32`
```kotlin
private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
    thread(start = false, name = "AsyncAnimator-Tick", isDaemon = true) { r.run() }
}
```
进程级**单例**守护线程，**所有**调用 `AnimationHandler(ScheduledTickScheduler())` 的线程共享。

**对比**：
- v4 §8.1："TickScheduler 必须是线程局部的，且帧源可换"。
- vendor 三条 AnimationHandler 路径（core/dynamicanimation/COUI）全部靠 `AnimationHandler.getInstance()` 的 **ThreadLocal**（`AnimationHandler.java:13-14` 等）→ 每线程一个 AnimationHandler 实例 → 每线程一个 provider 实例 → 每线程一个 Handler / Choreographer 实例（`(a):34, 47-55` 的 `sHandler ThreadLocal`）。
- vendor 框架 `(c)` 的 `setProvider`（`OplusExecutors.java:170`、`PipAnimationController.java:567`）在 launcher.anim 线程上装 `SfVsyncFrameCallbackProvider`，让该线程 tick 拿 SF-vsync；主线程的 AnimationHandler 仍挂默认 Choreographer。两线程**帧源可不同**。
- OPPO `PipAnimationController.java:50-55` 用 `ThreadLocal.withInitial(Supplier)` 实现"每线程各自一个 AnimationHandler 实例 + 自初始化时挂 SF-vsync"，是 per-thread 帧源的"协议式"实现。

**影响**：
- demo 3（`AsyncValueAnimator 跨 Looper`）若 `executor = Executors.MAIN_EXECUTOR`，start 在主线程；按 v4 §8.1 要求回调应在主线程。但 lib 默认 `ScheduledTickScheduler` 在共享守护线程 fire，**回调发生在 `"AsyncAnimator-Tick"` 守护线程而非主线程**。
- demo 10（独立动画线程）若显式调 `AnimationControlThread.instance`，`AnimationControlThread.onLooperPrepared` 装的是 `HandlerTickScheduler`，回调在 launcher.anim —— **这条 OK**。
- 所有依赖"回调线程 == start 线程"的断言失败：例如 `AnimationController.animationCount` 在主线程读 vs 回调线程改，会读到陈旧值（`AtomicLong` 跨线程读没问题，但"帧推进发生在哪个线程"的语义就崩了）。
- **这是 vs-oppo-04 §3-②① + v4 §8.1 的同一个根本问题在 TickScheduler 层的具体落地。**

**修复成本**：🟢 低（30 行内）。两个方向：
- **方向 A**：`ScheduledTickScheduler` 加构造参数 `executor: ExecutorService?` 默认 null（保持守护线程）。调用方需要 per-thread 时显式注入。改动量 ~10 行（构造 + executor 取值），零兼容性破坏（默认行为不变）。
- **方向 B**：在 `ScheduledTickScheduler` 类注释里强制标注"该 scheduler 不满足 v4 §8.1 per-thread 帧语义；演示跨线程动画请用 `HandlerTickScheduler` + `AnimationControlThread`"。零代码改动。
- 推荐 **B 即可**（成本几乎为零，调用方警示到位）；A 是大改但 ROI 高。

---

### 🟥 ② ScheduledTickScheduler `scheduleAtFixedRate` 掉帧时不堆积 vs vendor Handler.postDelayed 会堆积（高 / 行为分歧）

**证据**：
- `ScheduledTickScheduler.kt:62` `exec.scheduleAtFixedRate(::tick, 0, frameIntervalMs, TimeUnit.MILLISECONDS)`：ScheduledFuture 是**固定速率**，即使上一次 tick 没执行完也会**叠加下一次**（默认 `ScheduledExecutorService.scheduleAtFixedRate` 是"上一任务结束立即 schedule 下一任务"，不是"固定时刻触发"）。如果 `tick` 跑超 16ms，下一 tick 会立刻 fire——**反而加速**，补偿丢帧。
- vendor `(a):60-67` `getHandler().postDelayed(this, max(mFrameDelay - (uptimeMillis - mLastFrameTime), 0L))`：基于上次 fire 的 wall-clock 算 drift，**补足到满 16ms 间隔**——如果某帧掉了，**下一帧**延后到 16ms 整间隔。

**对比**：
- (a) `AnimationHandler.java:60-67`：`Math.max(this.mFrameDelay - (SystemClock.uptimeMillis() - this.mLastFrameTime), 0L)`——补偿式。
- (b) `AnimationHandler.java:69-72`：同 (a) 形态，`FRAME_DELAY_MS = 10`。
- lib `ScheduledTickScheduler`：补偿式不实现；lib `HandlerTickScheduler.kt:73` `postDelayed({...}, frameIntervalMs)` —— **无 drift 补偿**，固定 16ms 间隔。
- lib 两个 scheduler **都不补偿**：掉一帧 = 下一帧严格按 16ms 后才到（HandlerTickScheduler）或**立刻**到（ScheduledTickScheduler，scheduleAtFixedRate 在上一任务结束时立刻 schedule 下一）。

**影响**：
- 真实场景下 `ScheduledTickScheduler` 的"立刻补"行为可能在主线程抖动时让守护线程追不上节奏，**堆积 tick 在守护线程上**，导致守护线程长期忙碌。
- `HandlerTickScheduler` 的"严格 16ms"行为丢帧即丢——**与 vendor (a)(b) 的 drift 补偿语义相反**。
- **后果**：两个 lib scheduler 的"掉帧后行为"与 vendor 不同；ScheduledTickScheduler 倾向"追时间"（v4 §3 提到的路径 C MultiDynamicAnimation 用裸 delta 也是这种语义），HandlerTickScheduler 倾向"等间隔"（路径 A 的默认行为）。

**修复成本**：🟢 低（~15 行）。`HandlerTickScheduler.scheduleNextFrame` 加 `delay = max(frameIntervalMs - (now - lastFireTime), 0L)`；`ScheduledTickScheduler` 把 `scheduleAtFixedRate` 换成 `scheduleWithFixedDelay`（语义匹配 vendor 补偿逻辑）或保留 `scheduleAtFixedRate` + tick 内做 drift 限制。改动 ~10 行。

---

### 🟥 ③ ScheduledTickScheduler 默认线程 `"AsyncAnimator-Tick"` 是 daemon（高 / 守护线程语义）

**证据**：`ScheduledTickScheduler.kt:31` `thread(name = "AsyncAnimator-Tick", isDaemon = true)`。

**对比**：vendor 的 (a) HandlerThread（`AnimationHandler.java:54`）、(b) Handler 实例（`(b):65`）、(c) 框架 ThreadLocal 都不是 daemon——它们依附于 Looper/进程的生命周期。

**影响**：
- lib daemon 线程 = 进程所有非 daemon 线程结束后自动退出。**后果**：demo 在 JVM 单测里 OK（主线程退出前 daemon 还在跑）；真机上 Launcher 进程永不退出，所以 daemon 与否无差别。
- **但**：daemon 线程异常（如 OOM、stack overflow）会被 JVM 直接终止，**不**走 uncaughtExceptionHandler；vendor 主线程/launcher.anim 线程走 `Looper` 兜底。lib `ScheduledTickScheduler.tick()` 用 `runCatching` 兜单个 callback（§3-⑦），但**不兜调度任务本身**——如果 `tick` 内某行抛出非 callback 异常（如 `AtomicLong` 包装类的内部错误），守护线程会**静默死亡**，整条 tick 链永久停摆，**业务永不察觉**。
- `ScheduledTickScheduler` 没有 "scheduler dead" 检测；`AnimationHandler.callbackSize > 0` 但 scheduler 死了 → animation 永远不动。

**修复成本**：🟢 低（~5 行）。加 `thread(isDaemon = false)` 或加 try-catch + 错误日志 + 自重启逻辑。建议 `isDaemon = false`（对齐 vendor）。

---

### 🟥 ④ HandlerTickScheduler 不做 drift 补偿（高 / 行为分歧，与 #2 互为表里）

**证据**：`HandlerTickScheduler.kt:73` `handler.postDelayed({...tick...}, frameIntervalMs)`——固定 16ms，无 drift 计算。

**对比**：(a) `AnimationHandler.java:60-67` 用 `max(mFrameDelay - (uptimeMillis - mLastFrameTime), 0L)` 做补偿。

**影响**：
- 上一帧 fire 用了 20ms（掉了 4ms），下一帧严格 16ms 后到 → 实际间隔 16ms（不是预期的 16+4=20ms）；累计起来**快于真实时间**。
- 短时间看不出来（demo 周期 1s 才掉 4ms），但**累积**会偏移：60s 后偏差 ~250ms。
- vendor 补偿逻辑让 frame_time 总和与 wall-clock 总和同步，**业务可以用 frameTime 做 dt 计算**；lib 不补偿 → frameTime 是"上一帧到现在"而不是"到现在累计多久"，dt 计算会系统性地**偏小**。

**修复成本**：🟢 低（~10 行）。加 `private var lastFireTimeMs: Long = 0L` + `postDelayed({...}, max(frameIntervalMs - (SystemClock.uptimeMillis() - lastFireTimeMs), 0L))`。

---

### 🟡 ⑤ 两个 scheduler 都不接 vsync（vsync 对齐缺失）（中 / 已知简化）

**证据**：
- `HandlerTickScheduler.kt:73` 走 `postDelayed(this, 16)`——无 vsync；
- `ScheduledTickScheduler.kt:62` 走 `scheduleAtFixedRate`——无 vsync；
- vendor `(c) FrameCallbackProvider16` 走 `Choreographer.getInstance().postFrameCallback`（`AnimationHandler.java:104-108` / `AnimationHandler.java:75-94`）；
- vendor 框架 `(c) SfVsyncFrameCallbackProvider` 走 SF-vsync（`OplusExecutors.java:170`）。

**对比**：vendor 三条路径至少有一条拿 vsync；lib 两条路径都不拿。

**影响**：
- 60Hz 屏：lib 16ms 节奏 ≈ vsync 节奏，看不出差；
- 120Hz 屏：lib 仍 60Hz，**真实帧率减半**；
- 90Hz 屏：lib 仍 ~60Hz（实际是 16ms ≈ 62.5Hz，接近 60Hz 但不对齐 90Hz vsync）；
- 真机 trace 显示 OPPO `launcher.anim` 跑 120Hz 8.26ms 中位间隔（`animation-trace-validation.md:19`），**lib HandlerTickScheduler 跑不到这个精度**。

**修复成本**：🟡 中（~30 行）。新增第三个 `TickScheduler` 实现 `ChoreographerTickScheduler(handler: Handler)` 走 `Choreographer.getInstance().postFrameCallback(::doFrame)`；`AnimationControlThread.onLooperPrepared` 装它替换 `HandlerTickScheduler`。改动：新增 1 个文件 + 1 行 `AnimationControlThread.kt:75` 替换。

---

### 🟡 ⑥ `ScheduledTickScheduler` 在异常情况下的"守护线程死亡 = 永久静默"（中 / 鲁棒性）

证据见 §3-③。

**修复成本**：🟢 低（同 §3-③，~5 行）。

---

### 🟡 ⑦ 两个 scheduler 的 `runCatching` 与原厂相反（低 / 行为分歧）

**证据**：
- `ScheduledTickScheduler.kt:80` `runCatching { cb.doFrame(t) }`；
- `HandlerTickScheduler.kt:88` `runCatching { cb.doFrame(t) }`；
- vendor (a) `AnimationHandler.java:130-138`、`(b):147-156` **均无 try-catch**，单 callback 异常会：
  1. 中断 `for` 循环，本帧剩余 callbacks 不派发；
  2. 异常沿 Runnable / Choreographer.FrameCallback 链上抛；
  3. 在 `Handler.postDelayed` 路径上 = `Handler.dispatchMessage` 收到未捕获异常 → 进程 crash 风险（除非 Looper 兜底）或被 Looper 的 uncaughtExceptionHandler 抓住；
  4. 在 Choreographer 路径上 = `Choreographer.doFrame` 抛 → 走 Looper 同上。

**对比**：vendor 异常即暴露；lib 异常 swallow。

**影响**：
- demo 场景更"鲁棒"，单点失败不连累整帧；
- **掩盖原厂"异常即暴露"的调试语义**，业务隐藏 bug；
- 注释 `ScheduledTickScheduler.kt:79` "runCatching 做异常隔离"、`HandlerTickScheduler.kt:87` 同——**注释与原厂行为相反**。
- `ScheduledTickScheduler.kt` 的 KDoc（`:23`）说"异常隔离：单个 callback 抛异常不影响其他 callback"——是 lib 主动设计（与 vendor 不同），但注释措辞应明示"原厂无此保护"。

**修复成本**：🟢 低（~10 行）。加构造参数 `isolateExceptions: Boolean = true`，false 时按 vendor 行为传播异常；注释加"原厂 (a)(b) 无此保护，单 callback 异常会沿 provider 上抛到 Looper"。

---

### 🟢 ⑧ HandlerTickScheduler `tick()` 用 `SystemClock.uptimeNanos()` 而 ScheduledTickScheduler 用 `System.nanoTime()`（低 / 一致性分歧）

**证据**：
- `HandlerTickScheduler.kt:84` `val t = SystemClock.uptimeNanos()`；
- `ScheduledTickScheduler.kt:73` `val t = System.nanoTime()`。

**对比**：
- vendor (a) `:69` `SystemClock.uptimeMillis()`；(c) `:88` Choreographer 给 nanos。
- lib 两个 scheduler 用两个时间源——`AnimationHandler.kt:85` 统一 `frameTimeNanos / 1_000_000L` nanos → ms。

**影响**：
- 数值上 `System.nanoTime()` 与 `SystemClock.uptimeNanos()` 都是单调时钟 ns 精度，差异仅在 `System.nanoTime()` 跨 sleep/wake 不连续（wall-clock 基准），`uptimeNanos()` 跨 sleep 暂停；
- 对 frame callback 业务（每帧 16ms 调用一次，delta 计算）几乎无差；
- **代码风格分歧**：混用两个时间源给后续维护者困惑。

**修复成本**：🟢 低（~3 行）。统一用 `System.nanoTime()`（JVM 标准库，跨 Android/JVM 一致）或统一用 `SystemClock.uptimeNanos()`（Android only，ScheduledTickScheduler 在 JVM 单测时不可用，需 runCatching 兜底）。

---

### 🟢 ⑨ `ScheduledTickScheduler.postFrameCallback` 早退条件 `running=false → start()`，但 `HandlerTickScheduler.postFrameCallback` 不调 `start()`（低 / 一致性分歧）

**证据**：
- `ScheduledTickScheduler.kt:42-46`：`callbacks.add(callback); if (!running) start()`——add 同时保证 scheduler 在跑；
- `HandlerTickScheduler.kt:50-53`：`callbacks.add(callback)`——只 add，不 start。

**对比**：vendor (a) `AnimationHandler.java:174-178` `if (mAnimationCallbacks.size() == 0) mProvider.postFrameCallback()`——首次 add 才 post，但**post 是隐式 start**（vendor provider 自身是 Runnable/FrameCallback，post 即 fire 路径）。

**影响**：
- `ScheduledTickScheduler` 用 `scheduleAtFixedRate` 必须显式 `start()` 来触发 ScheduledFuture；
- `HandlerTickScheduler` 用 `postDelayed` 自走式，第一次 `postFrameCallback` 不会自动 fire，需要外部调 `start()` 或第一次 `scheduleNextFrame`；
- `AnimationHandler.addAnimationFrameCallback`（`AnimationHandler.kt:53-57`）**两个 scheduler 都先调 `scheduler.start()`**——所以这个差异被上层抹平了；但单独使用 `HandlerTickScheduler` 时**易踩坑**（add 后没 start，callbacks 入列但永不 fire）。

**修复成本**：🟢 低（~5 行）。`HandlerTickScheduler.postFrameCallback` 加 `if (!running) start()`——对齐 `ScheduledTickScheduler` 行为。

---

### 🟢 ⑩ `ScheduledTickScheduler` 自维持回路"空则停"，但停的是守护线程的 ScheduledFuture，**不是退出守护线程本身**（低 / 资源）

**证据**：
- `ScheduledTickScheduler.tick()`（`:85-87`）`if (callbacks.isEmpty()) stop()`；
- `stop()`（`:65-70`）`task.cancel(false); task = null` —— 只 cancel 当前 ScheduledFuture；
- `Executors.newSingleThreadScheduledExecutor` 的线程**永驻**，daemon 也只是进程退出时死。

**对比**：(a) `AnimationHandler.onAnimationFrame` (`:197-202`) 空则不再 post——Handler.postDelayed 路径无显式 stop，Handler 永驻等待下次 post。

**影响**：
- lib `ScheduledTickScheduler.stop()` 只是"不再 fire tick"，守护线程依然在等下一次 ScheduledFuture；
- **资源占用**：`AsyncAnimator-Tick` 守护线程空转，无 callback 时也在等 16ms 唤醒检查 callbacks 是否空；
- vendor Handler 同样空转，无差别——所以这条是"vendor 也有但 lib 没意识到"。

**修复成本**：🟢 低（可选）。`stop()` 后 `exec.shutdown()` 把线程池也关了——下次 `start()` 重新 new。复杂度+5 行。

---

## ④ 回移建议

### A. 值得补进 lib 的（性价比高 / 必修）

| 优先级 | 项 | 理由 | 改动量 |
|---|---|---|---|
| 🔴 必修 | `ScheduledTickScheduler` 加 `isDaemon = false` 或 try-catch + 兜底重启（修 §3-③） | daemon 线程静默死亡 = 业务永远感知不到动画停了 | ~5 行 |
| 🔴 必修 | `HandlerTickScheduler.scheduleNextFrame` 加 drift 补偿（修 §3-④） | vendor (a)(b) 都有，lib 没有；dt 计算系统性偏小 | ~10 行 |
| 🔴 必修 | `ScheduledTickScheduler` 类 KDoc 加醒目警示"该 scheduler 不满足 v4 §8.1 per-thread 帧语义，仅用于 JVM 单元测试；演示跨线程动画请用 HandlerTickScheduler + AnimationControlThread"（修 §3-①） | 调用方警示到位即可，无需大改；`AnimationHandler.installThreadScheduler` KDoc 同步加 | ~10 行注释 |
| 🔴 推荐 | `ScheduledTickScheduler` 用 `scheduleWithFixedDelay` 替换 `scheduleAtFixedRate`（修 §3-②） | vendor 语义对齐；掉帧不堆积守护线程任务 | ~3 行 |
| 🟡 推荐 | 新增第三个 TickScheduler：`ChoreographerTickScheduler`（修 §3-⑤） | 真正 vsync 对齐，120Hz 屏 demo 不再减半帧率；接口化预埋的兑现 | ~30 行 + `AnimationControlThread.kt:75` 替换 |
| 🟢 推荐 | `runCatching` 加构造参数 `isolateExceptions: Boolean = true`，注释改措辞（修 §3-⑦） | 与原厂语义对齐（默认 swallow；false 时传播），注释说清"原厂不 swallow" | ~10 行 + KDoc |
| 🟢 推荐 | `HandlerTickScheduler.postFrameCallback` 加 `if (!running) start()`（修 §3-⑨） | 与 `ScheduledTickScheduler` 行为对齐；单独使用不踩坑 | ~5 行 |

### B. 建议保持简化的（成本 > 收益 / 复刻 ROI 低）

| # | 项 | 理由 |
|---|---|---|
| 1 | vsync 对齐（SF-vsync 替代品）（修 §3-⑤ 的 SF 路径） | 框架 `SfVsyncFrameCallbackProvider` 是 @hide API；反射接入在非 OPPO ROM 上行为不定；trace 实证 `launcher.anim` 在该设备上帧源相位与主线程一致（`animation-trace-validation.md:20`），说明 SF-vsync 在该版本上未生效；lib 保留 `HandlerTickScheduler` 接口可替换是正确取舍 |
| 2 | `addAnimationFrameCallback(cb, delayMs)` 延迟启动回调（修 §2-C-4） | 当前 demo 无 delay 需求；vs-oppo-04 §3-②⑤ 已记为"按情况分"，未来复刻 dynamicanimation 路径 B 再补 |
| 3 | `setFrameDelay` / `getFrameDelay` / `onNewCallbackAdded` provider 钩子（修 §2-C-3） | vendor `(a):26` `onNewCallbackAdded` 在两个 vendor 实现里都是空方法（`(a):57-59, 97-99`），vendor 也没用；运行期调帧率 vendor 暴露是为让 ValueAnimator 与系统帧率同步，lib demo 无此需求 |
| 4 | `PipAnimationController.lambda$new$0` 的 `ThreadLocal.withInitial` 协议（修 §2-C-5） | 已有 `installThreadScheduler`（`AnimationHandler.kt:150-158`）+ `replaceThreadScheduler`（`:162-169`）两条 API 覆盖"装帧源"和"换帧源"；`ThreadLocal.withInitial` 等价 Kotlin 写法是 `object : ThreadLocal<AnimationHandler>() { override fun initialValue() = AnimationHandler(scheduler) }`——值得做但不是必修 |
| 5 | 框架 `SfVsyncFrameCallbackProvider` 直挂（hidden API 反射） | 见 §B-1；保留接口可替换即可 |
| 6 | 时间源统一（`System.nanoTime()` vs `SystemClock.uptimeNanos()`）（修 §3-⑧） | 数值差异不影响业务；仅是代码风格——可放在 lint 里而不是必修 |
| 7 | `ScheduledTickScheduler.stop()` 后 `exec.shutdown()`（修 §3-⑩） | vendor Handler 同样空转；lib daemon 守护线程资源占用可忽略；非必修 |

### C. 可选的低优回移（按 ROI 排序）

| 优先级 | 项 | ROI |
|---|---|---|
| 低 | 路径 D 仿写：OPPO DynamicAnimation + SF 帧对齐（`Choreographer.getSfInstance().getFrameIntervalNanos()`） | 与"窗口弹簧"主线无关；v4 §3 已接受这条 lib 路径不需要对齐；若未来 lib 引入路径 D 复刻，对齐逻辑加在 DynamicAnimation 仿写件内部即可，不必上升到 TickScheduler 层 |
| 低 | 路径 A 的 `autoCancelBasedOn(ObjectAnimator)`（vs-oppo-04 §2-C-9） | ObjectAnimator 是 platform 类，lib 不重做 |
| 低 | `getFrameDelay()` / `setFrameDelay()` 暴露 | 见 §B-3 |
| 低 | `getInstance()` 静态命名对齐 vendor（vs-oppo-04 §2-C-11） | 仅 Kotlin 调用，跨语言场景可加 `@JvmStatic` |

---

## 附：关键证据速查

| 论断 | 证据 |
|---|---|
| lib `ScheduledTickScheduler` 用 `scheduleAtFixedRate` + 守护线程 | `D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/core/scheduler/ScheduledTickScheduler.kt:30-32`（`AsyncAnimator-Tick` daemon） + `:62`（scheduleAtFixedRate(0, 16ms)） + `:85-87`（空则 stop） + `:88-90`（long 溢出 reset） |
| lib `HandlerTickScheduler` 用 `Handler.postDelayed` 自走 | `D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/animthread/HandlerTickScheduler.kt:67-80`（scheduleNextFrame postDelayed） + `:73-78`（空则停） + `:84`（SystemClock.uptimeNanos） + `:29`（frameIntervalMs=16） |
| lib `TickScheduler` 接口 5 成员 | `D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/core/scheduler/TickScheduler.kt:9-29`（postFrameCallback/removeFrameCallback/start/stop + frameTimeNanos/frameCount/frameIntervalMs/FrameCallback） |
| vendored core AnimationHandler ThreadLocal + FrameCallbackProvider14/16 | `D:/oppo_a6_launcher/sources/androidx/core/animation/AnimationHandler.java:13-14`（sAnimationHandler ThreadLocal） + `:33-79`（FrameCallbackProvider14：sHandler ThreadLocal + postDelayed + drift 补偿） + `:82-108`（FrameCallbackProvider16：Choreographer.postFrameCallback） + `:197-202`（onAnimationFrame 末尾空则停） |
| vendored dynamicanimation AnimationHandler 延迟启动 + 10ms | `D:/oppo_a6_launcher/sources/androidx/dynamicanimation/animation/AnimationHandler.java:13`（sAnimatorHandler ThreadLocal） + `:14`（FRAME_DELAY_MS = 10） + `:16`（mDelayedCallbackStartTime SimpleArrayMap） + `:50-72`（FrameCallbackProvider14：mHandler.postDelayed + drift 补偿） + `:75-94`（FrameCallbackProvider16：Choreographer.postFrameCallback） + `:123-145`（isCallbackDue + addAnimationFrameCallback(cb, delay)） + `:174-176`（setProvider 运行时换源） |
| OPPO 复制版 COUI AnimationHandler | `D:/oppo_a6_launcher/sources/com/coui/appcompat/animation/dynamicanimation/COUIAnimationHandler.java`（FrameCallbackProvider14 类被 JADX 掏空，方法体 `throw null` 是反编译错误，见该文件第 65-79 行 AnonymousClass1；FrameCallbackProvider16 同 dynamicanimation 形态 + `Choreographer.getInstance().postFrameCallback`）；`D:/oppo_a6_launcher/sources/com/android/wm/shell/shared/annotations/ChoreographerSfVsync.java` 是 wm/shell 注解（与 COUI AnimationHandler **无关**，勿混） |
| 框架 `SfVsyncFrameCallbackProvider` 用法 | `D:/oppo_a6_launcher/sources/com/oplus/basecommon/thread/OplusExecutors.java:5`（import） + `:95`（ANIM_EXECUTOR 创 launcher.anim 线程 -19） + `:169-171`（ANIM_EXECUTOR$lambda$0：setProvider(SfVsync) + setUxThreadValue）；`D:/oppo_a6_launcher/sources/com/android/wm/shell/pip/PipAnimationController.java:17`（import） + `:50-55`（mSfAnimationHandlerThreadLocal = ThreadLocal.withInitial 装 SfVsync） + `:567`（setProvider） |
| v4 per-thread 帧语义核心要求 | `D:/AsyncAnimator/docs/animation-thread-analysis-v4.md:145` §8.1 "TickScheduler 必须是线程局部的，且帧源可换" |
| vs-oppo-04 已识别 per-thread 破坏 | `D:/AsyncAnimator/docs/review/vs-oppo-04-frame-scheduling.md:132-145` §3-②① |
| OPPO launcher.anim 真机 120Hz 帧节奏 | `D:/AsyncAnimator/docs/animation-trace-validation.md:19`（77 帧间隔中位 8.26ms = 120Hz） + `:20`（launcher.anim 与主线程帧相位一致 = VSYNC-app，SF-vsync 在该设备上未体现） |

---

## 附：两个 lib scheduler 之间的对照（横切）

| 维度 | ScheduledTickScheduler | HandlerTickScheduler | 关系 |
|---|---|---|---|
| 时间源 | `Executors.newSingleThreadScheduledExecutor` + daemon | `Handler.postDelayed` + Looper | 完全不同 |
| 线程归属 | 进程级共享守护线程（破坏 v4 §8.1） | 绑 Looper 线程（满足 v4 §8.1，需 install） | **分歧（per-thread 行为核心差异）** |
| 时间取样 | `System.nanoTime()` | `SystemClock.uptimeNanos()` | 不一致（建议统一） |
| drift 补偿 | 无（scheduleAtFixedRate "立刻补"） | 无（postDelayed "严格 16ms"） | 都不补偿（vendor 都有） |
| 空则停 | `tick()` 内 `callbacks.isEmpty()` 检 | `scheduleNextFrame` lambda 内 `callbacks.isEmpty()` 检 | 都在 scheduler 内（vendor 在 AnimationHandler） |
| 异常隔离 | `runCatching` 吞单 callback | `runCatching` 吞单 callback | 与 vendor 相反 |
| 默认 frameIntervalMs | 16 | 16 | 与 (a) vendor 一致 |
| 多线程 add 安全 | `ConcurrentLinkedQueue` lock-free | `ConcurrentLinkedQueue` lock-free（HandlerTickScheduler 无并发场景，冗余） | 都用 CLQ |
| 长溢出保护 | `if (count < 0) reset` | `if (count < 0) reset` | lib 自加（vendor 无） |
| 是否支持 JVM 单测 | ✅（默认实现） | ✅（handler 可为 null） | 都支持 |
| 是否接 vsync | ❌ | ❌ | 都不接（vendor 路径 C 接 vsync） |
| 是否接 SF-vsync | ❌ | ❌ | 都不接（vendor 框架 (c) 接） |
| 替换成本 | 默认实现，可被 `installThreadScheduler` 替换 | 默认实现，可被 `installThreadScheduler` 替换 | 都是 TickScheduler 接口实现，可互换 |


## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **2be173e** — HandlerTickScheduler 漂移补偿（当时）
- **215ecb5** — ScheduledTickScheduler + HandlerTickScheduler 已删除，本文档大量分析已过期

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。