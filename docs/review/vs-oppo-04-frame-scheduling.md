# 区域 04 重对比 Review：帧调度层（AnimationHandler + TickScheduler）

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib`（AsyncAnimator 演示库）
> - **原厂**：`D:/oppo_a6_launcher/sources`（OPPO ColorOS 15 Launcher `com.android.launcher 15.8.24` JADX 反编译源码）
>
> **本区域聚焦**：lib 的 `core/anim/AnimationHandler` + `core/scheduler/{TickScheduler,ScheduledTickScheduler}` + `launcher/animthread/HandlerTickScheduler`；原厂的 vendored `androidx.core.animation.AnimationHandler` + `androidx.dynamicanimation.animation.AnimationHandler` + 框架 `android.animation.AnimationHandler`（@hide，仅 import）+ `MultiDynamicAnimation`（挂框架 AnimationHandler 的回调载体）+ OPPO 复制版 `DynamicAnimation`（挂框架 AnimationHandler 的另一条路径 D）+ `OplusExecutors.ANIM_EXECUTOR` 初始化回调。
>
> 与 review 04（`04-frame-spring-continuation.md`）的关系：review 04 是覆盖帧调度+弹簧+续行的"区域级"总览；本文是 region-04 的**纵向深挖**，专门把"帧源 + ThreadLocal + 懒注册/懒删除 + 帧时间换算 + setProvider + Choreographer on non-main thread + 三条路径"七条线索抽出来，对照原厂三条 AnimationHandler 路径逐一比对。
>
> 取证方法：lib 侧直接 Read（DLP 间歇态时 dump.py 走 Python 标准 I/O 拿明文）；sources 侧因企业 DLP 加密，全部经 dump.py 取得明文，行号为 JADX 反编译文本行号。背景结论见 `docs/animation-thread-analysis-v4.md` §3 / §8.1 / §9。

---

## ① 类对应关系表

| lib 类 | 原厂类（路径:行） | 关系 / 证据 |
|---|---|---|
| `core/anim/AnimationHandler.kt` | (a) `androidx/core/animation/AnimationHandler.java:12-215`；(b) `androidx/dynamicanimation/animation/AnimationHandler.java:11-176`；(c) **框架 `android.animation.AnimationHandler`**（@hide，**不在 sources 树内**，被 `OplusExecutors.java:3`/`MultiDynamicAnimation.java:3`/`DynamicAnimation.java:3` import） | 三套 AnimationHandler 路径并存。ThreadLocal 单例 + 懒注册 + 懒删除结构镜像。lib `AnimationHandler.kt:132-141`（threadLocalHandler） ↔ (a) `:13, 158-172`、↔ (b) `:14, 115-119`；lib 懒删除 `:66-73, 104-108` ↔ (a) `:204-210, 119-128`、↔ (b) `:165-172, 97-105`；lib 首注册启动 `:53-57` ↔ (a) `:174-178`、↔ (b) `:135-145`（delay>0 还写 mDelayedCallbackStartTime）。(c) 通过 `MultiDynamicAnimation.java:127` `addAnimationFrameCallback(this, 0L)` 与 `OplusExecutors.java:170` `setProvider(SfVsyncFrameCallbackProvider)` 间接使用 |
| `core/scheduler/TickScheduler.kt` | (a) `AnimationFrameCallbackProvider`（接口，`AnimationHandler.java:23-31`）；(b) `AnimationFrameCallbackProvider`（抽象类，`AnimationHandler.java:39-47`） | 帧源抽象。lib 统一为 `postFrameCallback/removeFrameCallback/start/stop/frameTimeNanos/frameCount/frameIntervalMs`（`TickScheduler.kt:9-29`）；原厂 (a) 接口 4 方法（`getFrameDelay/postFrameCallback/onNewCallbackAdded/setFrameDelay`），(b) 抽象类 1 方法（`postFrameCallback`）。**三方接口成员差异较大** |
| `core/scheduler/ScheduledTickScheduler.kt` | (a) `FrameCallbackProvider14`（`AnimationHandler.java:33-79`）；(b) `FrameCallbackProvider14`（`AnimationHandler.java:50-72`，**FRAME_DELAY_MS=10**） | Handler.postDelayed 自走帧循环的 JVM 等价实现。lib 用 `ScheduledExecutorService.scheduleAtFixedRate(0, 16ms)`（`ScheduledTickScheduler.kt:62`）；原厂 (a) `mFrameDelay=16` `(a):37`、Handler ThreadLocal `(a):34,49`；原厂 (b) `mHandler.postDelayed(mRunnable, 10ms)` `(b):69-72` |
| `launcher/animthread/HandlerTickScheduler.kt` | (a) `FrameCallbackProvider16`（`AnimationHandler.java:82-108`，挂 `Choreographer.getInstance()` + nanos→ms `/ AnimationKt.MillisToNanos` `:88`）；(c) `SfVsyncFrameCallbackProvider`（框架 @hide，`OplusExecutors.java:5` import，`OplusExecutors.java:169-171` 装到 launcher.anim 线程的 (c) AnimationHandler） | Looper 绑定的帧源。lib 用 `Handler.postDelayed`（`HandlerTickScheduler.kt:67-80`），与原厂 (a) `FrameCallbackProvider14` 同形态；与 (a) `FrameCallbackProvider16` 差一个"真 vsync 对齐"。launcher.anim 线程上原厂换 (c) 的 SF-vsync —— **lib 没有 SF-vsync 替代品**，只能算 (a) FrameCallbackProvider14 的等价退化 |
| `launcher/animthread/AnimationControlThread.kt` | `OplusExecutors.ANIM_EXECUTOR` 内联创建 + `ANIM_EXECUTOR$lambda$0` init 回调 | 原厂 (c) `android.animation.AnimationHandler.setProvider(SfVsyncFrameCallbackProvider)` 落点。`AnimationControlThread.kt:75-77` ↔ `OplusExecutors.java:95`（`createAndStartNewLooper("launcher.anim", -19, …)`）+ `OplusExecutors.java:169-171`（setProvider + setUxThreadValue） |
| （lib 无对应） | `com/android/quickstep/util/animation/MultiDynamicAnimation.java:21-200` | 路径 C 的回调载体：`implements AnimationHandler.AnimationFrameCallback`（`:21`，import `android.animation.AnimationHandler` `@:3`），`startAnimationInternal()` `:121-128` `AnimationHandler.getInstance().addAnimationFrameCallback(this, 0L)`，`doAnimationFrame` `:152-180` 用**裸 delta**（无 SF 帧对齐），`requestEnd` `:185-191` 下一帧生效 |
| （lib 无对应） | `com/android/quickstep/util/animation/DynamicAnimation.java:18-…`（OPPO 改造版，**路径 D**） | 与 MultiDynamicAnimation 同样挂 (c)，但 `doAnimationFrame` `:461-480` 用 **`Choreographer.getSfInstance().getFrameIntervalNanos()` 对齐 delta**（`:473-476`，偏差 ≤ 半帧时规整为整数帧间隔）；并提供 `mAnimationHandler` 字段 `:34, 508-510` 允许每实例 override |

**关键结论**：lib 的 `core/anim/AnimationHandler` 同时替代了原厂三条路径（核心 vendored / 动态 vendored / 框架 @hide）的核心数据结构，但**未替代任何一条的 provider 形态**。这造成 lib 没法承载路径 D 的 SF 帧间隔对齐、也没法承载框架 @hide 的 SF-vsync 直挂。

---

## ② 保真度评估

### A. 精确复刻（行为对齐原厂）

| # | 设计点 | 原厂证据（路径:行） | lib 证据 |
|---|---|---|---|
| 1 | ThreadLocal 单例 + `sTestHandler` 钩子 | (a) `AnimationHandler.java:13-14, 158-172`（`sAnimationHandler` ThreadLocal、`sTestHandler` 静态、`getInstance()` 三元优先 sTestHandler）；(b) `AnimationHandler.java:14, 115-119`（`sAnimatorHandler` ThreadLocal） | `AnimationHandler.kt:132-141`（`threadLocalHandler` + `testHandler` + `instance` 三元）——两路相同，lib 复刻 |
| 2 | 懒注册：列表从空→非空才向 provider 发脉冲 | (a) `AnimationHandler.java:174-178` `if (mAnimationCallbacks.size()==0) mProvider.postFrameCallback();`；(b) `:135-145` `if (mAnimationCallbacks.size()==0) getProvider().postFrameCallback();` | `AnimationHandler.kt:53-57` 同样判空后调 `scheduler.start()` + `scheduler.postFrameCallback(::onTick)`（见 §3-设计 1） |
| 3 | addCallback 幂等（contains 去重） | (a) `AnimationHandler.java:178-180` `if (!mAnimationCallbacks.contains(...))`；(b) `:140-142` 同形 | `AnimationHandler.kt:59` `if (!animationCallbacks.contains(callback))` 一致 |
| 4 | 懒删除协议：removeCallback 置 null 槽 + listDirty=true | (a) `AnimationHandler.java:204-210` `set(idx, null); mListDirty=true`；(b) `:165-172` 同形 | `AnimationHandler.kt:66-73, 104-108` 逐行一致 |
| 5 | cleanUpList 仅当 listDirty 时执行 | (a) `AnimationHandler.java:119-128` `if (mListDirty) {... reverse loop remove ...; mListDirty=false}`；(b) `:97-105` 同形 | `AnimationHandler.kt:104-108` `if (!listDirty) return; removeAll { it == null }; listDirty = false` —— 同样惰性 |
| 6 | 自维持回路：每帧末尾检查"还有 callback 才续帧" | (a) `AnimationHandler.java:197-202` `if (mAnimationCallbacks.size() > 0) mProvider.postFrameCallback();`；(b) `AnimationCallbackDispatcher.dispatchAnimationFrame` `:23-33` 同形 | `AnimationHandler.kt:96-101` `for (...) doAnimationFrame; cleanUpList` 后由 TickScheduler 自续——但 lib 把"是否续帧"决策下放到 TickScheduler（`HandlerTickScheduler.kt:73-78` 空则停；`ScheduledTickScheduler.kt:85-87` 空则停），语义对齐 |
| 7 | doAnimationFrame 顺序遍历 + 跳过 null 槽 | (a) `AnimationHandler.java:130-138` `for (size...) get(i) ; if (cb != null) cb.doAnimationFrame(j8);` | `AnimationHandler.kt:94-101` 同样顺序遍历、跳 null 槽 |
| 8 | 帧时间单位换算 nanos → ms | (a) `FrameCallbackProvider16.doFrame` `AnimationHandler.java:88` `onAnimationFrame(j8 / AnimationKt.MillisToNanos)`；(b) `mCurrentFrameTime = SystemClock.uptimeMillis()` `:27` 直接 ms；MultiDynamicAnimation `frameTime` 入参已是 ms `:152` | `AnimationHandler.kt:85` `frameTimeNanos / 1_000_000L` —— 与 (a) 同形态（数值上等价；`AnimationKt.MillisToNanos` = 1_000_000） |
| 9 | removeCallback 同时清空延迟回调表 | (b) `AnimationHandler.java:165-170` 先 `mDelayedCallbackStartTime.remove(callback)` 再做 null 槽置位 | lib **没有 mDelayedCallbackStartTime 对应物**（见 §C-3）——因为没有 addCallback 带 delay 的入参，故也不需要这步。结构等价 |
| 10 | 测试 hook：`setTestHandler` / `instance` 优先返回 | (a) `AnimationHandler.java:170-172`、`:158-168`；(b) `:115-119`（无 testHandler） | `AnimationHandler.kt:134-135, 140` —— lib 同时兼容 (a)(b) 两种风格（`(b)` 没有 testHandler，lib 用 `testHandler ?: threadLocalHandler.get()` 等价兼容） |
| 11 | ThreadLocal Handler（per-thread Handler 实例） | (a) `FrameCallbackProvider14.sHandler` `:34, 47-55` —— 每线程缓存一个 Handler 实例 | lib `HandlerTickScheduler` 接受构造期传入的 Handler，未做 ThreadLocal 缓存（`HandlerTickScheduler.kt:27`）；等价（缓存由 HandlerThread + Looper 天然保证） |
| 12 | 帧回调 boolean 返回语义（true = 动画结束，可摘除） | (a) `AnimationFrameCallback.doAnimationFrame` 接口 `:20`；(b) `:37` 同形 | `AnimationHandler.kt:15-17` `AnimationFrameCallback { fun doAnimationFrame(frameTimeMs: Long): Boolean }` 一致 |
| 13 | 私有 `doAnimationFrame` / 公开 `onAnimationFrame`（一个入口对外） | (a) `:130-138`(private) + `:197-202`(public)；(b) `:26-33` dispatchAnimationFrame(public) + `:147-156`(private) | `AnimationHandler.kt:85`(onTick 私有) + `:94-101`(doAnimationFrame 私有) —— 都私有化，命名也简化为 onTick，但对外只暴露 `addAnimationFrameCallback/removeCallback/callbackSize`，与原厂公开面相当 |

### B. 有意简化（lib 注释/文档中明示，或符合 v4 §8.1 认可的设计取舍）

| # | 简化内容 | 原厂对应 | lib 取舍理由 |
|---|---|---|---|
| 1 | TickScheduler 统一接口取代三套帧源 + 两套 provider 接口 | 原厂并存 (a)/(b)/(c) 三套 AnimationHandler，加 (a) 接口 4 方法 / (b) 抽象类 1 方法 | `TickScheduler.kt:5-7` 注释明示：抽象出"每帧调用 callback"的本质。属于"教学库聚焦"的合理归并 |
| 2 | `ScheduledTickScheduler` 用 JVM `ScheduledExecutorService.scheduleAtFixedRate(0, 16ms)` 仿真 | 原厂 (a) `FrameCallbackProvider14` 用 `Handler.postDelayed(this, 16)`；(b) `FrameCallbackProvider14` 用 `Handler.postDelayed(mRunnable, 10)` | `ScheduledTickScheduler.kt:14-17` 注释明示；纯 JVM 可测是 demo 目标 |
| 3 | 无 vsync 对齐（`HandlerTickScheduler` 用 `postDelayed` 退化路径） | 原厂 (a) `FrameCallbackProvider16` 走 `Choreographer.getInstance().postFrameCallback` 拿 vsync；(c) `SfVsyncFrameCallbackProvider` 拿 SF vsync | `HandlerTickScheduler.kt:13-17` 注释明示"真机上若把本类换成 per-thread Choreographer 适配（FrameCallbackProvider16 语义），即可获得 VSYNC 精度——本类保留同样接口，替换成本为零"——接口化预埋替换点 |
| 4 | 无 `setFrameDelay` / `getFrameDelay` | (a) `AnimationHandler.java:212-215` setFrameDelay + `:193-195` getFrameDelay，委托 `provider.setFrameDelay/getFrameDelay`（`(a):24, 30`） | lib 没有运行期调节帧率的需求（demo 帧率由构造参数固定）；保持接口最小 |
| 5 | 无 `onNewCallbackAdded` 回调钩子 | (a) `AnimationHandler.java:26` 接口方法 + `:175, 178` 实现为空（vendor 也未真正使用） | lib TickScheduler 不暴露此钩子 |
| 6 | 无 `autoCancelBasedOn(ObjectAnimator)` | (a) `AnimationHandler.java:184-189` —— 当 ObjectAnimator 的 start 触发时遍历 callbacks 取消应被取消的对象 | ObjectAnimator 是 platform 类，lib 复刻 layer 不需要自家实现；行为被 platform 默认实现承载 |
| 7 | `onAnimationFrame` 命名为 `onTick`（私有） | (a) `:197-202` `public void onAnimationFrame(long j8)` | lib 改私有并改名，纯命名；callers 都从 `addAnimationFrameCallback` 入口走，外部不可见 |
| 8 | `runCatching` 异常隔离（每 callback 单独 try-catch） | 原厂 (a) `:130-138`、(b) `:147-156` **均无 try-catch**，单 callback 抛异常会中断整帧并沿 provider 上抛 | `AnimationHandler.kt:99` 注释"异常隔离：一个动画出错不影响其他（类似原版 swallow）"——与原厂实际不符（见 §C-7），但 lib 教学库更倾向于"单点失败不影响其他 demo 跑通" |
| 9 | `TickSchedulerHolder` 间接层替代"构造期注入 provider" | (a) `AnimationHandler.java:111-114` 构造器 `public AnimationHandler(AnimationFrameCallbackProvider)` 注入（null → FrameCallbackProvider16）；(b) `:39-48` `getProvider()` 懒构造 | `AnimationHandler.kt:39, 119-129` 用 `TickSchedulerHolder` 懒构造 + 可替换；既保留构造期注入语义，又提供 `replaceThreadScheduler` 运行时换源 |
| 10 | `HandlerTickScheduler.frameIntervalMs` 写死 16ms 默认值 | 原厂 (a) `FrameCallbackProvider14.mFrameDelay=16` `:37` 默认值（构造时可改） | `HandlerTickScheduler.kt:29` 默认 16 但构造期可改，行为等价 |

### C. 遗漏（原厂有、lib 没有，且不一定是有意砍掉的）

1. **没有"运行时换帧源"的真正等价 API**（框架版 (c) 的 `setProvider`、vendored (b) 的 `setProvider`）
   - 框架 (c) `android.animation.AnimationHandler.setProvider(...)` 被 `OplusExecutors.java:170` 在 launcher.anim 线程上调用，把帧源从默认 Choreographer 换成 SF-vsync —— **这是异步动画方案的物理基础**。
   - Vendored (b) `AnimationHandler.java:174-176` `public void setProvider(AnimationFrameCallbackProvider)` 也是"运行时换源"，但只能用于自身实例。
   - lib `AnimationHandler.replaceThreadScheduler`（`AnimationHandler.kt:165-169`）**比 vendor setProvider 更激进**：停旧 start 新 + 重建回路；而 vendor 的 `setProvider` 只让**下一次 frame 从新 provider 来**，旧 provider 的脉冲继续走完当帧。lib 的激进策略可能让"换源中"那一帧的 callback 全数丢失。
   - **后果**：lib 无法在已运行的 AnimationHandler 上做"瞬间切帧源"（如调试时把默认 provider 换成 SF-vsync 模拟），只有"停掉所有 callback 再换"的语义。

2. **没有 `getAnimationCount` 静态方法**
   - (a) `AnimationHandler.java:140-146` 提供 `public static int getAnimationCount()` 静态入口，跨 thread 统计当前活跃动画数（用于 log/dump）。
   - lib `AnimationHandler.kt:170` 只有 `val animationCount: Int get() = instance.callbackSize`，语义等价但**只在当前线程**——跨线程统计无对应物。

3. **没有延迟启动回调机制（mDelayedCallbackStartTime / isCallbackDue）**
   - (b) `AnimationHandler.java:16, 123-133, 135-145` 提供"addCallback(cb, delayMs)"：delay>0 时把 `uptimeMillis + delayMs` 写进 SimpleArrayMap，每帧 `isCallbackDue` 判定是否到期；到期的才派发。
   - lib `AnimationHandler.kt:51` `addAnimationFrameCallback(callback: AnimationFrameCallback?)` **无 delay 形参**。
   - **后果**：复刻 `MultiDynamicAnimation.startAnimationInternal` (`:127` 调用时 `0L`) 没问题，但若复刻 dynamicanimation 系动画（如 `FlingSpringAnim`/`RectFSpringAnim`，AOSP 残留路径 B）会缺这层语义。

4. **没有 `getFrameDelay()` 暴露**
   - (a) `AnimationHandler.java:193-195` 委托 `provider.getFrameDelay()`；(b) dynamicanimation `getFrameTime()` `:107-113` 暴露 `mCurrentFrameTime`。
   - lib `TickScheduler.frameIntervalMs` 是**构造期**写死的，`TickScheduler.frameTimeNanos` 是**当前 tick 的快照**，但没有"跨 tick 取出本 tick 的绝对时间用于外部对时"的入口。
   - **后果**：业务侧需要"上一帧的 frameTime"做计算（如 dt 修正）时无现成入口，得自己保留快照。

5. **没有 Choreographer 帧对齐（路径 D 特有的 SF 帧间隔对齐）**
   - OPPO 复制版 `DynamicAnimation.doAnimationFrame` `DynamicAnimation.java:473-476` 用 `Choreographer.getSfInstance().getFrameIntervalNanos()` 把 delta 对齐到整数帧间隔（偏差 ≤ 半帧时规整），保证弹簧积分步长稳定。
   - lib 没有 `Choreographer.getSfInstance()` 的等价物；`HandlerTickScheduler` 用 `SystemClock.uptimeNanos()` 抓时间但不做对齐。
   - **后果**：若 lib 复刻"路径 D 的 View 属性弹簧动画"，弹簧 dt 在掉帧场景会出现 1.5×帧间隔、0.5×帧间隔的原始抖动；MultiDynamicAnimation 用裸 delta（vendor 也不做对齐，`MultiDynamicAnimation.java:158-159`）所以 lib 复刻这条路径没问题。
   - **状态**：review 04 §2.3-9 已记录为"按情况分"。

6. **`installThreadScheduler` 沉默 no-op 路径**（**bug 级**，见 §3-②）
   - `AnimationHandler.kt:150-158` 三个早退：`testHandler != null` / `scheduler == null` / `threadLocalHandler.get() != null`。
   - vendor 没有"先 install 才能用"的约束：`setProvider` 任何时候调都生效。
   - **后果**：调用顺序顺序错（先访问 `instance` 再 install）会**沉默失败**，整个异步动画方案退化为默认 scheduler。注释 `:150-158` 末尾"必须在该线程首次访问 [instance] 之前调用"是隐性契约，调用方不易察觉。

7. **`runCatching` 异常隔离与原厂语义不符**
   - lib `AnimationHandler.kt:99` `runCatching { cb.doAnimationFrame(frameTimeMs) }`，单 callback 异常被吞；后续 callback 正常派发。
   - 原厂 (a) `:130-138` `(b) :147-156` **无 try-catch**，单 callback 异常会：
     - 中断 `for` 循环，本帧剩余 callbacks 不派发；
     - 异常传播到 provider 的 Runnable / Choreographer.FrameCallback；
     - 在 Handler.postDelayed 路径上 = `Handler.dispatchMessage` 收到未捕获异常 = 进程 crash 风险（除非 Looper 有兜底）或被 Looper 自身的 uncaughtExceptionHandler 抓住。
   - **后果**：lib 在 demo 场景更鲁棒，但**掩盖原厂"异常即暴露"的调试语义**。若 lib 后续要把 demo 接真实设备，异常吞没会隐藏 bug。注释里"类似原版 swallow"是错标（原版不 swallow）。

8. **`ScheduledTickScheduler` 默认线程 `"AsyncAnimator-Tick"` 不满足 v4 §8.1 "帧回调线程 == start 线程"**
   - `ScheduledTickScheduler.kt:32` `thread(start = false, name = "AsyncAnimator-Tick", isDaemon = true)` 是**所有线程共享的**守护线程，帧回调在它身上跑。
   - v4 §8.1 的核心要求："动画在哪个线程 start，帧回调就在哪个线程 tick"。
   - 只有当业务用 `AnimationControlThread` 显式装 `HandlerTickScheduler`（`AnimationControlThread.kt:75`）时该语义才成立。
   - **后果**：demo 若用默认 `ScheduledTickScheduler` + 任意线程 start，断言"frame 回调在 start 线程"会失败；这是**该子层最大的"不忠实"**——直接破坏了 v4 强调的 per-thread 帧语义。

9. **`AutoCancelBasedOn` 不复刻**
   - (a) `AnimationHandler.java:184-189` `autoCancelBasedOn(ObjectAnimator)`：ObjectAnimator start 时遍历自身 callbacks 取消应被取消的对象（`ObjectAnimator.shouldAutoCancel`）。
   - lib 复刻 layer 不重做 ObjectAnimator，依赖 platform 默认。

10. **`AutoCancel` 接口 `onNewCallbackAdded` 空实现**
    - (a) `FrameCallbackProvider14.onNewCallbackAdded` `:57-59` 与 (a) `FrameCallbackProvider16.onNewCallbackAdded` `:97-99` 均为空方法（vendor 也没用上）。
    - lib TickScheduler 接口根本不暴露这方法——略过。

11. **没有 `getInstance()` 静态公开返回类型**
    - lib `AnimationHandler.instance`（`:140`）是 `companion object` 的 `val`，Java 调用方要拿它得写 `AnimationHandler.Companion.getInstance()`——不够干净。
    - vendor (a) `AnimationHandler.getInstance()` 是顶层 static，Kotlin 写 `AnimationHandler.getInstance()` 自然得到。
    - 这是 lib 的 Kotlin 习惯用法，**调用面其实兼容**（Kotlin 侧 `AnimationHandler.instance`），但跨语言/反射场景要注意。

---

## ③ 行为差异风险点

按"可能导致语义不同"的严重度排序，**🟥 标 BUG-LEVEL**：

### 🟥 ① ScheduledTickScheduler 破坏 v4 §8.1 per-thread 帧语义（高 / BUG-LEVEL）

**证据**：`ScheduledTickScheduler.kt:30-32` 用 `Executors.newSingleThreadScheduledExecutor { thread(name = "AsyncAnimator-Tick", …) }` —— 这是**进程级单例的共享守护线程**，所有未显式装 `HandlerTickScheduler` 的线程共用。

**对比**：
- v4 §8.1："TickScheduler 必须是线程局部的，且帧源可换"。
- vendor 框架版 (c) `android.animation.AnimationHandler.getInstance()` 是 **ThreadLocal**（`:13` / `(b):14`），start 在哪个线程、回调在哪个线程。
- vendor vendored (a) `FrameCallbackProvider14` 的 Handler 也用 **ThreadLocal**（`(a):34, 47-55`）。

**影响**：
- demo 3（`AsyncValueAnimator 跨 Looper`）若 `executor = Executors.MAIN_EXECUTOR`，start 在主线程；按 v4 §8.1 要求回调应在主线程。但 lib 默认 scheduler 在共享线程 fire，**回调发生在 `"AsyncAnimator-Tick"` 守护线程而非主线程**。所有依赖"回调线程 == start 线程"的断言失败。
- demo 10（独立动画线程）若显式调 `AnimationControlThread.instance`，`AnimationControlThread.onLooperPrepared` 装的是 `HandlerTickScheduler`，回调在 launcher.anim —— 这条 OK。
- **隐藏陷阱**：review 04 §3-2 已经识别此风险，但当前 lib 代码（与 review 04 时间同步）**未做修补**。

**建议修补**：见 §4-A-1。

---

### 🟥 ② installThreadScheduler 沉默 no-op 路径（高 / BUG-LEVEL）

**证据**：`AnimationHandler.kt:150-158` 三个早退：
```
fun installThreadScheduler(scheduler: TickScheduler?) {
    if (testHandler != null) return
    if (scheduler == null) return
    if (threadLocalHandler.get() == null) {       // <-- 关键
        threadLocalHandler.set(AnimationHandler(scheduler))
    }
}
```
**只有** `threadLocalHandler.get() == null` 才真正装；只要该线程之前任何代码访问过 `instance`（哪怕只是 `AnimationHandler.animationCount` 这种无副作用读取），这里的 `installThreadScheduler` 就静默退出，scheduler **没被装上**。

**对比**：
- vendor 框架 (c) `setProvider(...)` 任何时候调都生效，是同步赋值。
- vendor vendored (b) `setProvider(...)` `:174-176` 同样无前置约束。

**影响**：
- 调用顺序错（先碰 `instance` 再 install）→ 整个"为该线程装独立帧源"语义**消失**，业务继续用默认 scheduler。
- 注释 `:150-158` 末尾"必须在该线程首次访问 [instance] 之前调用"是隐性契约，但 Kotlin 习惯写法（`AnimationHandler.instance`）很容易在不知不觉中触发首次访问（例如 lambda capture、伴生对象初始化、test setup）。
- `replaceThreadScheduler`（`AnimationHandler.kt:165-169`）虽然解决了"运行时换"，但调用语义不同（要求 `instance` 已存在），与 installThreadScheduler 的"装帧"是两个 API。

**建议修补**：见 §4-A-2。

---

### 🟥 ③ lib `replaceThreadScheduler` 比 vendor `setProvider` 激进，可能丢帧（高 / BUG-LEVEL）

**证据**：`AnimationHandler.kt:114-117, 162-169`：
```
@Synchronized
private fun swapScheduler(s: TickScheduler) {
    schedulerHolder.get().stop()
    schedulerHolder = TickSchedulerHolder(s)
    if (callbackSize > 0) {
        s.start()
        s.postFrameCallback(::onTick)         // <-- 重发 self-pulse
    }
}
```
`stop()` 旧 scheduler → 换新 → `start()` 新 → `postFrameCallback` 自续。

**对比**：
- vendor 框架 (c) `android.animation.AnimationHandler.setProvider(...)` 是**同步替换 provider 字段引用**，旧 provider 仍持有当前帧回调，`doAnimationFrame` 走完后由新 provider 接续下一帧的脉冲。**没有"丢一帧"**。
- vendor vendored (b) `AnimationHandler.java:174-176` `setProvider(...)` 同样只换字段。

**影响**：
- lib 激进语义下：
  - `stop()` 把旧 scheduler 的脉冲队列清空（`ScheduledTickScheduler:67-69` `task.cancel(false)` / `HandlerTickScheduler:71-74` 不会续帧）；
  - 换源当帧**可能已经在 tick 中**——已经在旧 provider 上跑的 `for cb in callbacks.toTypedArray() { cb.doFrame(t) }` 会正常完成，但**下一帧的脉冲由新 provider 重发**，旧脉冲被丢；
  - 实际表现为"换源后第一帧可能延迟 frameIntervalMs 才到"。
- 后果：单元测试做"换源→下帧回调"会观察到延迟；真机场景影响小（演示库无该用例）。
- vendor 没有"丢一帧"语义，对调试更友好。

**建议修补**：见 §4-A-3。

---

### 🟡 ④ 帧率写死 16ms 默认值 = 强制 60Hz，与高刷新率屏错配（中）

**证据**：`HandlerTickScheduler.kt:29` `frameIntervalMs: Long = 16`；`ScheduledTickScheduler.kt:28` 同样默认 16。

**对比**：
- vendor (a) `FrameCallbackProvider14.mFrameDelay = 16` `:37` 也是默认 16，但 `(a):92-93` `getFrameDelay` 委托 `ValueAnimator.getFrameDelay()`（读自系统设置）；(b) dynamicanimation `FRAME_DELAY_MS = 10` `:14` 更激进。
- vendor (a) `FrameCallbackProvider16.getFrameDelay` 同样读 `ValueAnimator.getFrameDelay()`。
- vendor 框架 (c) 自身不存帧率，由 provider 决定。

**影响**：
- 120Hz 屏上 lib 的 HandlerTickScheduler tick 是 60Hz → 真实回调 1/2 帧率；
- 90Hz 屏上 lib 仍是 ~60Hz；
- vendor 实际拿 vsync，tick 节奏对齐屏幕。
- demo 上看不出（视觉走样有限），但真机演示时会有可观察的"不够跟手"。

**建议修补**：见 §4-A-4。

---

### 🟡 ⑤ 缺延迟启动回调（mDelayedCallbackStartTime / isCallbackDue），未来扩展受限（中）

**证据**：lib `AnimationHandler.kt:51` `addAnimationFrameCallback(callback: AnimationFrameCallback?)` 无 `delay` 形参；vendor (b) `AnimationHandler.java:135-145` 有 `(cb, delay)` 双参版。

**影响**：
- 当前 lib demo（3/10）只用 `addAnimationFrameCallback(cb)`，无 delay 需求；
- 复刻 dynamicanimation 路径 B 的 `FlingSpringAnim`/`RectFSpringAnim`（v4 §3 路径 B）需要 delay 机制（让"动画 start 后等 100ms 再开始 tick"）；
- 复刻 `MultiDynamicAnimation.startAnimationInternal`（vendor (c) 调用 `addAnimationFrameCallback(this, 0L)`，`MultiDynamicAnimation.java:127`）无 delay 需求，OK。

**建议修补**：见 §4-B-2（建议保持简化）。

---

### 🟡 ⑥ 无 SF-vsync 帧源替代品 —— 路径 C/D 差异无法消除（中）

**证据**：
- vendor 框架 (c) `SfVsyncFrameCallbackProvider`（`OplusExecutors.java:5, 170`）从 SurfaceFlinger 拿 vsync，回调与合成时刻对齐。
- OPPO 复制版 `DynamicAnimation.doAnimationFrame`（`DynamicAnimation.java:473-476`）用 `Choreographer.getSfInstance().getFrameIntervalNanos()` 对齐 delta。
- lib `HandlerTickScheduler` 用 `postDelayed(16ms)`（`HandlerTickScheduler.kt:67-80`）+ `SystemClock.uptimeNanos()`，无任何对齐。

**影响**：
- MultiDynamicAnimation 路径：vendor 也用裸 delta（`MultiDynamicAnimation.java:158-159`），lib 复刻该路径无差。
- 路径 D（OPPO DynamicAnimation 系 View 属性弹簧）：vendor 用 SF 帧对齐稳定弹簧 dt；lib 若复刻该路径，弹簧 dt 会暴露原始抖动。v4 §3 表注"MultiDynamicAnimation 没做这层对齐（`:159` 用裸 delta）——窗口弹簧掉帧时宁可追时间也不拖长，应是有意取舍"已经接受这条 lib 路径不需要对齐。

**建议修补**：见 §4-A-5（可选低优）。

---

### 🟢 ⑦ runCatching 异常隔离与原厂语义不符（低 / 行为分歧）

**证据**：
- lib `AnimationHandler.kt:99` `runCatching { cb.doAnimationFrame(frameTimeMs) }` 单 callback 异常被吞。
- lib `ScheduledTickScheduler.kt:84` `runCatching { cb.doFrame(t) }` 同。
- lib `HandlerTickScheduler.kt:84-86` `runCatching { cb.doFrame(t) }` 同。
- vendor (a) `AnimationHandler.java:130-138` `(b):147-156` **无 try-catch**。

**对比**：
- vendor：单 callback 异常会中断本帧剩余派发，异常沿 provider 上抛到 Handler.postDelayed 的 Runnable（或 Choreographer.FrameCallback）→ Looper 默认会传给 `Thread.UncaughtExceptionHandler`（launcher 默认会 crash 进程或被 RemoteExceptionHandler 接走）。
- lib：异常被 swallow，本帧继续派发剩余 callback，下帧照常。

**影响**：
- demo 场景更"鲁棒"，单点失败不连累整帧；
- 掩盖原厂"异常即暴露"的调试语义，业务隐藏 bug。
- 注释 `AnimationHandler.kt:99` "类似原版 swallow"是错的（vendor 不 swallow），建议改措辞（review 04 §4.2-3 已识别，未实施）。

**建议修补**：见 §4-A-6。

---

### 🟢 ⑧ `addAnimationFrameCallback` 同时调 `start()` + `postFrameCallback(::onTick)` 与 vendor "单步 postFrameCallback" 形态不同（低 / 设计分歧）

**证据**：
- lib `AnimationHandler.kt:53-57`：
  ```
  if (animationCallbacks.isEmpty()) {
      scheduler.start()
      scheduler.postFrameCallback(::onTick)
  }
  ```
- vendor (a) `AnimationHandler.java:174-178`：
  ```
  if (this.mAnimationCallbacks.size() == 0) {
      this.mProvider.postFrameCallback();
  }
  ```

**对比**：
- vendor 的 `AnimationFrameCallbackProvider` 把"调度回路"和"帧回调"绑在一起：provider 本身是个 `Runnable`（`(a):60-71`）/`Choreographer.FrameCallback`（`(a):82-108`），`postFrameCallback()` 隐含"启动/续帧"。
- lib 的 `TickScheduler` 把两个职责拆开：`start()` 启调度、`postFrameCallback()` 仅注册一个 callback。
- **语义对齐**但**职责划分不同**。

**影响**：
- 对 lib 使用者而言，`TickScheduler` 比 `AnimationFrameCallbackProvider` 接口友好（start/stop 显式可控）。
- 但"什么时候 stop"语义变模糊：vendor 由 provider 自动判断（"mAnimationCallbacks.size() > 0 才续帧"），lib 由 scheduler 自身判断（`HandlerTickScheduler.kt:73-78`、`ScheduledTickScheduler.kt:85-87` 检查 callbacks.isEmpty）。
- 实际结果等价，但 lib 多了一个"空则停"的隐性约束，需要在每个 scheduler 实现里手动遵守。

**建议修补**：保持现状（设计分歧，非 bug）。

---

### 🟢 ⑨ 私有 vs 公开命名分歧（低 / 形式）

**证据**：
- lib `AnimationHandler.kt:79` `private fun onTick(...)`、`94` `private fun doAnimationFrame(...)` —— 全私有。
- vendor (a) `AnimationHandler.java:130`(private) + `:197`(public)；(b) `:26`(public, `dispatchAnimationFrame`) + `:147`(private, `doAnimationFrame`)。

**对比**：vendor 的 `onAnimationFrame`/`dispatchAnimationFrame` 是 provider 的回调入口，**必须** public 给 provider 调用。

**影响**：lib 的 onTick 私有——因为 TickScheduler 不持有对它的"Runnable/FrameCallback"引用，而是通过 `postFrameCallback(::onTick)` 注册一个 lambda。结构等价但命名不同，不影响外部行为。

**建议修补**：保持现状。

---

> ⚠️ ④ 建议表各行待逐条打标（风险项状态见 ③ ①-⑨ 打标）。
## ④ 回移建议

### A. 值得补进 lib 的（性价比高 / 必修）

1. **🔴 必修**：让 `ScheduledTickScheduler` 不再破坏 per-thread 语义（修 ③-①）
   - 方案 A：给 `ScheduledTickScheduler` 加构造参数 `executor: ExecutorService`，允许 per-instance 指定线程；默认行为不变（共享 AsyncAnimator-Tick 守护线程）。
   - 方案 B：在类注释 / KDoc 里**强制标注**"该 scheduler 不满足 v4 §8.1 per-thread 帧语义，仅用于 JVM 单元测试；演示跨线程动画请用 HandlerTickScheduler + AnimationControlThread"。在 `AnimationHandler.installThreadScheduler` 的 KDoc 里也强调。
   - 推荐方案 B 即可（成本几乎为零），A 是大改。
   - 不修的后果：所有 demo 3 / 10 的"帧回调在指定线程"断言不稳。

2. **🔴 必修**：`installThreadScheduler` 改 fail-loud 或 fallback（修 ③-②）
   - 当前三个 early return 全部 silent。
   - 方案 A：增加 `forceInstall: Boolean = false` 形参，`true` 时覆盖已存在的 handler（与 `replaceThreadScheduler` 合并语义）。
   - 方案 B：在 `instance` getter 加 `threadLocalHandler.get() == null` 检查时**优先用** installScheduler 设的 scheduler（懒注入），而不是直接 new `AnimationHandler(ScheduledTickScheduler())`。
   - 方案 C：抛 `IllegalStateException("AnimationHandler already instantiated on this thread; use replaceThreadScheduler instead")`（fail-loud）。
   - 推荐方案 C —— 与 vendor `setProvider` 不一致（vendor 无 install 约束），但 lib 设计本身就有这个隐性契约，fail-loud 比 silent fail 安全。

3. **🔴 必修/可选**：`replaceThreadScheduler` 改非破坏式（修 ③-③）
   - 不调用 `scheduler.stop()`，只换字段；让当前帧派发走完，下一帧由新 provider 接续。
   - 对齐 vendor `setProvider` 语义。
   - 实现：把 `swapScheduler` 改为只赋值 `schedulerHolder = TickSchedulerHolder(s)`，让 TickScheduler 自身判断"旧 self-pulse 是否需要 cancel"。
   - 但这要求 TickScheduler 自己实现"换源协议"——增加复杂度。
   - 折中：在 `replaceThreadScheduler` 加注释"换源当帧后续 frameIntervalMs 可能丢一帧，与 vendor setProvider 行为不同；如需对齐请改用内部 stop/start 调用约定"。

4. **🟡 推荐**：`frameIntervalMs` 从屏幕刷新率取初值（修 ③-④）
   - `HandlerTickScheduler` 构造期若能从 `Display.getRefreshRate()` 取实际刷新率，可显著改善真机 demo 体验。
   - 公开 API，可达。
   - `ScheduledTickScheduler` 同理。

5. **🟢 可选**：`addAnimationFrameCallback(cb, delayMs)` 重载（修 ③-⑤）
   - 对齐 vendor (b) `AnimationHandler.java:135-145` 签名。
   - 实现：加 `mDelayedCallbackStartTime: Map<AnimationFrameCallback, Long>` + `isCallbackDue` 判定。
   - 约 15 行，为将来复刻 dynamicanimation 系动画铺路。

6. **🟢 推荐**：`runCatching` 注释改措辞 + 提供开关（修 ③-⑦）
   - 把 `AnimationHandler.kt:99` 注释从"类似原版 swallow"改为"`runCatching` 是 lib 新增的异常隔离，原厂无此保护（单 callback 异常会沿 provider 上抛）"——与事实对齐。
   - 加构造参数 `isolateExceptions: Boolean = true`，false 时按 vendor 行为传播异常。
   - 单元测试可注入 false 验证"异常传播路径"。

### B. 建议保持简化的（成本 > 收益 / 复刻 ROI 低）

1. **`getAnimationCount` 跨线程统计**：vendor `(a):140-146` 提供静态入口，但仅用于 log/dump 调试；lib 没有跨线程活跃动画统计需求，instance.callbackSize 已够 demo 使用。
2. **`addAnimationFrameCallback(cb, delayMs)`**（若 ④-A-5 未实施）：当前 demo 无 delay 需求，先不做。
3. **`onNewCallbackAdded` provider 钩子**：vendor 也未真正使用 `(a):57-59, 97-99`，接口位死方法。
4. **`autoCancelBasedOn(ObjectAnimator)`**：ObjectAnimator 是 platform 类，lib 不重做。
5. **`getFrameDelay()` / `setFrameDelay()`**：lib 不暴露运行期调帧率，构造期固定即可；vendor 暴露是为了让 ValueAnimator 可与系统帧率同步。
6. **公开 `onAnimationFrame` 命名**：vendor 必须 public 是因为 provider 直接持有引用；lib 的 `postFrameCallback(::onTick)` 是 lambda 闭包，无需 public。
7. **路径 D 的 SF 帧间隔对齐**：review 04 §2.3-9 + v4 §3 都明确这条对齐只服务 View 属性弹簧（路径 D），lib 复刻时只覆盖路径 C 的 MultiDynamicAnimation（用裸 delta），无对齐需求；若未来 lib 引入路径 D 复刻，对齐逻辑加在 DynamicAnimation 仿写件内部即可，不必上升到 AnimationHandler。

### C. 可选的低优回移（按 ROI 排序）

| 优先级 | 项 | ROI |
|---|---|---|
| 低 | 路径 D 仿写：OPPO DynamicAnimation + SF 帧对齐（`Choreographer.getSfInstance().getFrameIntervalNanos()`） | 与"窗口弹簧"主线无关，demo 当前无调用方 |
| 低 | 框架 (c) 的 SfVsyncFrameCallbackProvider 直挂（hidden API，反射） | AOSP wm/shell 也在用（如 `com/android/wm/shell/pip/PipAnimationController.java:567`），但非 OPPO ROM 上行为不定；保留 `HandlerTickScheduler` 接口可替换是正确取舍 |
| 低 | `getInstance()` 静态命名对齐 vendor | 仅 Kotlin 调用，跨语言场景可加 `@JvmStatic` |

---

## 附：关键证据速查

| 论断 | 证据 |
|---|---|
| 框架 `android.animation.AnimationHandler` 在 launcher.anim 线程换 SF-vsync | `com/oplus/basecommon/thread/OplusExecutors.java:3`（import） + `:5`（import `SfVsyncFrameCallbackProvider`） + `:95`（`createAndStartNewLooper("launcher.anim", -19, …)`） + `:169-171`（`ANIM_EXECUTOR$lambda$0()`: setProvider + setUxThreadValue） |
| MultiDynamicAnimation 挂框架 AnimationHandler、用裸 delta、requestEnd 下一帧生效 | `com/android/quickstep/util/animation/MultiDynamicAnimation.java:3`（import `android.animation.AnimationHandler`） + `:21`（implements `AnimationHandler.AnimationFrameCallback`） + `:121-128`（startAnimationInternal → addAnimationFrameCallback(this, 0L)） + `:152-180`（doAnimationFrame 裸 delta `frameTime - mLastFrameTime`） + `:185-191`（requestEnd 置标志位） |
| OPPO 复制版 DynamicAnimation 用 SF 帧间隔对齐 | `com/android/quickstep/util/animation/DynamicAnimation.java:3`（import `android.animation.AnimationHandler`） + `:18`（implements `AnimationHandler.AnimationFrameCallback`） + `:473-476`（`Choreographer.getSfInstance().getFrameIntervalNanos() / AnimationKt.MillisToNanos` 判定） |
| vendored core AnimationHandler 的 ThreadLocal / 懒删除 / 续帧 | `androidx/core/animation/AnimationHandler.java:13`（`sAnimationHandler` ThreadLocal） + `:119-128`（cleanUpList reverse loop） + `:174-178`（addCallback first-time postFrameCallback） + `:197-202`（onAnimationFrame auto-reschedule） + `:212-215`（setFrameDelay 委托 provider） + `:184-189`（autoCancelBasedOn） |
| vendored dynamicanimation AnimationHandler 的 setProvider + 延迟启动 | `androidx/dynamicanimation/animation/AnimationHandler.java:14`（`sAnimatorHandler` ThreadLocal） + `:16`（`mDelayedCallbackStartTime` SimpleArrayMap） + `:39-48`（抽象 provider） + `:50-72`（FrameCallbackProvider14 `mHandler.postDelayed(mRunnable, 10)`） + `:75-94`（FrameCallbackProvider16 Choreographer hook） + `:123-145`（isCallbackDue + addAnimationFrameCallback(cb, delay)） + `:174-176`（setProvider） |
| lib AnimationHandler 的 ThreadLocal + 懒删除 + 懒注册 | `com/asyncanimator/core/anim/AnimationHandler.kt:53-57`（addAnimationFrameCallback first-time） + `:66-73`（removeCallback null slot + listDirty） + `:104-108`（cleanUpList lazy） + `:132-141`（ThreadLocal + testHandler） + `:150-158`（installThreadScheduler 三 silent early return） |
| lib ScheduledTickScheduler 的共享守护线程 | `com/asyncanimator/core/scheduler/ScheduledTickScheduler.kt:30-32`（`AsyncAnimator-Tick` daemon thread factory） + `:62`（scheduleAtFixedRate(0, 16ms)） + `:85-87`（callbacks.isEmpty() → stop） |
| lib HandlerTickScheduler 的 Looper postDelayed 帧循环 | `com/asyncanimator/launcher/animthread/HandlerTickScheduler.kt:67-80`（scheduleNextFrame postDelayed） + `:73-78`（callbacks.isEmpty() → stop） + `:29`（frameIntervalMs=16） |
| lib AnimationControlThread 的 -19 优先级 + HandlerTickScheduler 注入 | `com/asyncanimator/launcher/animthread/AnimationControlThread.kt:75-77`（installThreadScheduler(HandlerTickScheduler(Handler(looper)))） + `:78-80`（setThreadPriority 兜底） + `:107-109`（`PRIORITY = -19`） |

---

## 附：lib vs 原厂 帧调度层总览对比表

| 维度 | lib | 原厂（三套） | 一致 / 分歧 |
|---|---|---|---|
| ThreadLocal 单例 | ✅ `threadLocalHandler`（`AnimationHandler.kt:134-141`） | ✅ (a) `sAnimationHandler`、`sTestHandler`（`:13-14, 158-172`）；(b) `sAnimatorHandler`（`:14, 115-119`）；(c) 框架 ThreadLocal | 一致 |
| testHandler 钩子 | ✅ `:135, 140` | ✅ (a) `:14, 159-162`；(b) 无 | 一致（兼容） |
| 懒注册 | ✅ `:53-57` | ✅ (a) `:174-178`；(b) `:135-145` | 一致 |
| 懒删除（null + listDirty） | ✅ `:66-73, 104-108` | ✅ (a) `:204-210, 119-128`；(b) `:165-172, 97-105` | 一致 |
| cleanUpList lazy | ✅ `:104-108` | ✅ (a) `:119-128`；(b) `:97-105` | 一致 |
| 自维持回路（空则停） | ✅ `HandlerTickScheduler.kt:73-78` / `ScheduledTickScheduler.kt:85-87` | ✅ (a) `onAnimationFrame` `:197-202`；(b) `dispatchAnimationFrame` `:23-33` | 一致 |
| 帧时间换算 nanos→ms | ✅ `:85` `/ 1_000_000L` | ✅ (a) `:88` `/ AnimationKt.MillisToNanos` | 一致 |
| `setProvider` 运行时换源 | ⚠️ `replaceThreadScheduler`（激进版，丢一帧） | ✅ (b) `:174-176`；(c) 框架 @hide | **分歧** |
| 延迟启动（delay） | ❌ 无 | ✅ (b) `:135-145` + `isCallbackDue` | **缺失** |
| vsync 对齐（Choreographer） | ❌ 仅 postDelayed 退化 | ✅ (a) `FrameCallbackProvider16` `:82-108`；(c) `SfVsyncFrameCallbackProvider` | **缺失** |
| SF 帧间隔对齐（路径 D） | ❌ | ✅ `DynamicAnimation.java:473-476` | **缺失（按情况分）** |
| 异常隔离（per-callback try-catch） | ✅ `runCatching`（lib 自加） | ❌ vendor 异常传播 | **分歧（与原厂相反）** |
| `getAnimationCount` 静态 | ⚠️ 实例 `animationCount`（`:170`） | ✅ (a) `:140-146` 静态 | **形式分歧（语义等价）** |
| `autoCancelBasedOn` | ❌ 无 | ✅ (a) `:184-189` | **缺失（platform 承载）** |
| `onNewCallbackAdded` provider 钩子 | ❌ 无（接口未暴露） | ✅ (a) `:26` 接口 + 空实现 | **缺失（vendor 也未用）** |
| Per-thread Handler 缓存 | ⚠️ 构造期注入（`HandlerTickScheduler.kt:27`） | ✅ (a) `FrameCallbackProvider14.sHandler` `:34, 47-55` | 等价（HandlerThread 天然保证） |
| 公开 `onAnimationFrame` / `dispatchAnimationFrame` | ⚠️ 私有 `onTick` | ✅ (a) `:197` public；(b) `:26` public | 形式分歧（语义对齐） |
| **Per-thread 帧源可换**（v4 §8.1 核心） | ✅ via `installThreadScheduler` + `replaceThreadScheduler`（Kotlin 路径） | ✅ (c) `setProvider(SfVsyncFrameCallbackProvider)` | **语义等价**（vendor 走 hidden API，lib 走抽象接口） |
| **per-thread 默认行为满足** | ❌ `ScheduledTickScheduler` 默认共享线程（破坏）；✅ `HandlerTickScheduler`（需 install） | ✅（vendor 默认即 per-thread，ThreadLocal 根上保证） | **分歧（默认行为）** |

---

## 附：三条原厂路径在 lib 中的对应

| 原厂路径 | 帧源 | 线程 | 用者 | lib 是否能复刻 |
|---|---|---|---|---|
| **A**（vendored core） | 主线程 Choreographer（app-vsync） | main | vendored ValueAnimator 系、AnimatorPlaybackController | ✅ 走默认 lib `ScheduledTickScheduler` 即可（lib 不区分 main/non-main，回调都在守护线程——**与 vendor per-thread 默认行为不一致**，见 §3-①） |
| **B**（vendored dynamicanimation） | 调用线程 Choreographer | main（实际） | FlingSpringAnim、RectFSpringAnim（AOSP 残留） | ⚠️ 可走 `HandlerTickScheduler(handler)`，但缺少 `addAnimationFrameCallback(cb, delay)` 延迟启动机制（见 §3-⑤） |
| **C**（框架 @hide） | 线程级 provider：main=app-vsync；launcher.anim=**SF-vsync** | **start() 所在线程** | 框架 ValueAnimator 系、MultiDynamicAnimation、AsyncValueAnimator | ✅✅ 完整复刻：`AnimationControlThread.kt:75-77` 装 HandlerTickScheduler + 线程级 AnimationHandler，**lib 的"独立动画线程"方案本体**。但**不能挂 SF-vsync**（无 hidden API 替代品），只能退化到 Looper.postDelayed |
| **D**（OPPO 复制的 DynamicAnimation） | 同 C + SF 帧间隔对齐 | start() 所在线程 | 桌面 View 属性 spring（AbsAnimation/AnimationImpl） | ⚠️ 若复刻该路径，需要：(1) SF 帧对齐逻辑（`Choreographer.getSfInstance().getFrameIntervalNanos()`）；(2) `mAnimationHandler` per-instance override 字段（vendor `DynamicAnimation.java:34, 508-510`）；(3) View 属性更新走 Property setter |

---

## 附：trace 验证结论的对应

依据 `docs/animation-trace-validation.md`：
- ✅ "独立动画线程 + 每帧直发 SurfaceFlinger"在真机 trace 上证实（tid 3342 launcher.anim 跑 77 帧 + 每帧 binder→SF）。
- ⚠️ "launcher.anim 帧源 = SF-vsync"在该 trace 设备上**未体现**（两线程帧相位均为 VSYNC-app 而非 VSYNC-sf）——这意味着即便原厂 `setProvider(SfVsyncFrameCallbackProvider)` 真的生效，效果也不可见于相位（SF-vsync 与 app-vsync 同频 120Hz，相位差仅 5ms）。
- 对 lib 的启示：**SF-vsync 帧源降级为可选**（v4 §8.1 已经认识），lib 用 `HandlerTickScheduler` 退化到 Looper.postDelayed 是**对真机行为足够忠实的简化**。

## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **60bd048** — AnimationHandler.doAnimationFrame 行为对齐 vendored core
- **2be173e** — HandlerTickScheduler 漂移补偿（已删，合入 ChoreographerTickScheduler）
- **dbde195** — ChoreographerTickScheduler 公开 Choreographer 替代 postDelayed 退化路径
- **215ecb5** — ScheduledTickScheduler/HandlerTickScheduler 删，只留 ChoreographerTickScheduler（满足 §3-② 收拢意图）

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。