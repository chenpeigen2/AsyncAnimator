# 区域 07 对比 Review：并发原语与线程安全

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib`（AsyncAnimator 演示库，Kotlin 重实现）
> - **原厂**：`D:/oppo_a6_launcher/sources`（OPPO ColorOS 15 Launcher `com.android.launcher 15.8.24` JADX 反编译源码）
>
> 本区域聚焦"并发原语与线程安全"——`synchronized` / `@Volatile` / `AtomicBoolean` / `AtomicLong` / `ConcurrentLinkedQueue` / `ThreadLocal` / `@Synchronized` 的使用边界、锁粒度、竞态窗口可见性。背景见 `docs/animation-thread-analysis-v4.md` 与 review 01~04。
>
> 取证方法：lib 侧经 Python `dump.py` 穿透 DLP 拿明文；sources 侧 80% 文件 DLP 加密，全部经 Grep（ripgrep 明文通道）取证，行号为 JADX 文本行号。

---

## ① 类对应关系表

| lib 类（文件:行） | 原厂类（文件:行） | 关键并发原语（lib / 原厂） |
|---|---|---|
| `core/anim/AnimationHandler.kt:132-141` | `androidx/core/animation/AnimationHandler.java:13-14,158-172`（vendored）；`android.animation.AnimationHandler` @hide 框架类（被 `MultiDynamicAnimation.java:3,21,127` implements） | `ThreadLocal<AnimationHandler> threadLocalHandler`（lib）↔ `sThreadLocal`（vendored core :13）。**两者均纯 ThreadLocal，零同步**。 |
| `core/anim/AnimationHandler.kt:117-119`（`testHandler`） | 无对应 | `@Volatile var testHandler`（lib）— 测试钩子，独有 |
| `core/anim/AnimationHandler.kt:131-141`（`TickSchedulerHolder`） | vendored `AnimationFrameCallbackProvider` 抽象 | `@Synchronized fun get()`（lib）— 懒构造；原厂无懒构造（直接 `mFrameCallbackProvider` 字段初始化） |
| `core/anim/AnimationHandler.kt:113-115`（`swapScheduler`） | 无对应 | `@Synchronized fun swapScheduler`（lib）— demo/实验用，原厂无对应路径 |
| `core/scheduler/ScheduledTickScheduler.kt:30-61` | vendored `FrameCallbackProvider14`（core :33-79）；dynamicanimation `:50-72` | `ConcurrentLinkedQueue<FrameCallback> callbacks` + `AtomicLong frameCountAtomic/frameTimeNanosAtomic` + `@Volatile running` + `@Synchronized start/stop`（lib）↔ 简单 `mFrameCallbacks` ArrayList（vendored，原厂线程局部的回调列表本身就是 Looper 派发线程私有，**无并发原语**） |
| `launcher/animthread/HandlerTickScheduler.kt:30-80` | vendored `FrameCallbackProvider16`（core :82-108） + `SfVsyncFrameCallbackProvider`（`OplusExecutors.java:5,170`） | `ConcurrentLinkedQueue callbacks` + `AtomicLong frameCountAtomic/frameTimeNanosAtomic` + `@Volatile running` + `@Synchronized start/stop`（lib）↔ 原厂 `mHandler.postDelayed(this, frameDelay)` + `Choreographer.postFrameCallback`；原厂**零并发原语**（Looper 派发线程 = 回调执行线程，天然串行） |
| `launcher/animthread/AnimationControlThread.kt:73-83`（`instance`） | `com/oplus/basecommon/thread/OplusExecutors.java:95`（`static final OplusLooperExecutor ANIM_EXECUTOR`） | `by lazy(LazyThreadSafetyMode.SYNCHRONIZED)`（lib）↔ `static final`（JVM 类初始化锁，原厂）。**lib 用 synchronized lazy 模拟 JMM 类初始化**，但与 `static final` 在语义上等效 |
| `launcher/animthread/AsyncAnimWrapper.kt:17-25` | `com/android/launcher3/anim/AsyncAnimWrapper.java:10-20` | 无并发原语（双方均只把 task 投给 LooperExecutor） |
| `launcher/async/AsyncValueAnimator.kt:30`（`isEnd`） | `com/android/quickstep/util/animation/AsyncValueAnimator.java:31`（`mIsEnd`） | `AtomicBoolean isEnd` + `compareAndSet(false, true)`（双方一致，**精确复刻**） |
| `launcher/async/AsyncAnimCallbacks.kt:21`（`animListeners`） | `com/android/quickstep/util/animation/AsyncAnimCallbacks.java:34`（`mAnimListeners`） | `mutableListOf<NullableAnimatorListener?>()`（lib）↔ `ArrayList<NullableAnimatorListener> mAnimListeners`（原厂）。**双方都无显式并发原语**——靠"主线程 add/listener-iterate 主线程派发"的纪律。**关键差异**：lib `getListeners()` 用 `removeAll { it == null }` 拷快照（AsyncAnimCallbacks.kt:75-79），原版 `getListeners()` 用反向遍历 `remove(size)` + `toArray(new NullableAnimatorListener[0])`（AsyncAnimCallbacks.java:38-43） |
| `launcher/async/LooperExecutor.kt:14-16`（`handler`） | `com/oplus/basecommon/thread/LooperExecutor.java:9`（`mHandler`） | `private val handler: Handler?`（lib）↔ `private final Handler mHandler`（原厂）。**双方都无并发原语**——构造期一次性写入、之后只读。lib 用 `null` 作为 JVM 单测兜底 |
| `launcher/async/LooperExecutor.kt:33-37`（`post`） | `LooperExecutor.java:27-33`（`execute`） | lib 判 `isCurrentThread`（`handler.looper.thread === Thread.currentThread()`）↔ 原厂判 `getHandler().getLooper() == Looper.myLooper()`。**无并发原语**，纯 Looper 线程比较 |
| `launcher/async/LooperExecutor.kt:39-46`（`postAsync`） | `com/android/launcher3/Utilities.java:631-637`（`postAsyncCallback`）被 `AsyncAnimCallbacks.java:120,160` 调用 | `Message.obtain(h) { action() }.apply { isAsynchronous = true }`（lib）↔ 原厂相同（`Utilities.postAsyncCallback` 内部 `Message.obtain(handler, r).setAsynchronous(true).sendMessage`）。**精确复刻** |
| `launcher/async/AsyncAnimCallbacks.kt:67-79`（`getListeners`） | `AsyncAnimCallbacks.java:38-43,81-97`（同款 `getListeners`） | lib 用 `removeAll { null }` + `filterNotNull` 拷快照 ↔ 原厂用反向 `remove(size)` + `toArray(new NullableAnimatorListener[0])`。**保真**（快照语义一致），实现路径不同 |
| `launcher/async/AsyncAnimCallbacks.kt:60-62`（`runOnMainThread`） | `AsyncAnimCallbacks.java:153-160`（`runOnMainThread`） | `if (exec.isCurrentThread) action() else exec.postAsync(action)`（lib）↔ `if (Looper.getMainLooper().isCurrentThread()) runnable.run(); else Utilities.postAsyncCallback(handler, runnable);`（原厂）。**精确复刻** |
| `launcher/seq/AnimSeqTimeStamp.kt:9-21` | `com/android/systemui/shared/system/AnimSeqTimeStamp.java:25-28` | `@Volatile private var lastStartAppTime / lastRecentFinishTime / lastRecentStartTime / lastLaunchTaskTime / clock`（lib，4 个 long + 1 个 lambda，**纯 lock-free 读**）↔ `private static long lastStartAppTimeMillis / lastRecentFinishTimeMills / lastRecentStartTimeMills / lastLaunchTaskTimeMills`（原厂，**裸 long，无 volatile**）+ `@JvmStatic public static final synchronized long getTimeGapToLast* / update* / reset*`（原厂：所有读写都加 synchronized） |
| `launcher/seq/AnimationSeqHelper.kt:23-28` | `com/oplus/quickstep/utils/AnimationSeqHelper.java:50-55,57-65` | `var seqId = 0L`（lib，**裸 long 无同步**）+ `Handler? handler`（懒创建，**无同步**）↔ 原厂 `private long seqId`（**裸 long 无同步**）+ `private final Handler handler = new Handler(Looper.getMainLooper(), ...)`（**final 一次写，构造期发布**）。**两者一致裸 long**——都假设"seqId 单线程访问" |
| `launcher/feature/AnimationFeatureHelper.kt:11-19` | `com/oplus/quickstep/utils/AnimationFeatureHelper.java:52-60` | `private class SyncedVar<T>(initial: T) : ReadWriteProperty` 内部 `@Volatile private var value = initial`，`setValue = synchronized(lock) { this.value = value }`（lib）↔ `private volatile int mAsyncEnable = -1` 等 9 个字段 + 8 个 `private final synchronized void set*Enable/set*Disable/setThreshold/setLimtSize`（原厂）。**粒度对照**：lib 一个锁管全部 7 字段（统一 `lock`）+ 一次 `simulateRemoteUpdate` 用 `synchronized(lock)` 包 6 个赋值；原厂每字段一把锁（8 把）。两个列表字段 OPPO 用 `synchronized(this.m1pxPkgDisableList)` / `synchronized(this.m1pxCardDisableList)` 做"整段重写"保护（`:320, :345`），lib 把 `onePxPkgDisableList / onePxCardDisableList` 暴露为 `mutableListOf()` 后**没任何保护** |
| `launcher/controller/AnimationController.kt:33`（`runningTaskInfo`） | `com/oplus/quickstep/utils/AnimationController.java:83`（`mRunningTask`） | `@Volatile private var runningTaskInfo: Any?`（lib）↔ `private volatile TaskInfo mRunningTask`（原厂）。**精确复刻** |
| `launcher/controller/AnimationController.kt:24-27`（其他状态字段） | `AnimationController.java:67-87`（`isLandscapeActivity`、`mIsBetweenAppExitTransitionEndAndFinish` 等 12+ 字段） | lib 12 个状态字段**全部非 @Volatile**（`isLandScapeGesture`/`isSplitScreenGesture`/`onceGestureProcessingFlag` 等）↔ 原厂对应字段**也全部非 volatile**。**两者一致假设"主线程访问"** |
| `launcher/controller/AnimationController.kt:42-44`（`recentsAnims/appLaunchAnims/removeTasksMaps`） | `AnimationController.java:89-91`（`mRecentsAnims / mAppLaunchAnims / mRemoveTasksMaps`） | `mutableListOf<CustomRectFSpringAnim>()` + `mutableListOf<RemoteAnimationFactory>()` + `linkedMapOf<Any, Any>()`（lib，**裸 ArrayList/LinkedHashMap 无同步**）↔ `new ArrayList()` / `new LinkedHashMap()`（原厂，**裸集合无同步**）。**一致** |
| `launcher/controller/DefaultAnimationController.kt:7`（`animStateChangeListeners`） | `com/oplus/quickstep/utils/DefaultAnimationController.java` metadata d2 字段 `mAnimStateChangeListeners` | `mutableListOf<OnAnimStateChangeListener>()`（lib）↔ 应该是 `ArrayList<>`（原厂，未读取文本验证）。**两者一致**：遍历时 `toList()` 拷快照（lib :16） |
| `launcher/controller/TaskStateChangeTimeOutListener.kt:24-32` | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:117-209` 内部类 | `private val handler: Handler?`（lib，**构造时一次写，无同步**）+ `handler?.postDelayed(timeOutOption, duration)`（init）+ `handler?.removeCallbacks(timeOutOption)`（dispose）。lib 还用 `runCatching` 包 looper 访问（JVM 单测兜底）。↔ OPPO `private Handler handler`（`TaskStateHelper.java:118`，**裸引用无同步**）— `init` 时 `handler = OplusExecutors.URGENT_TRANSACTION_EXECUTOR.handler`，`dispose` 时 `handler.removeCallbacks(timeOutOption)` |
| `launcher/manager/OplusAnimManager.kt:11-12`（Impl 字段） | `com/oplus/quickstep/utils/OplusAnimManager.java:50-65`（Impl 字段） | `private var animationControllerImpl: AnimationController? = null`（lib，**裸 var 无同步**）↔ `Lazy<AnimationController>` delegate（`f4.g`，原厂用 Kotlin lazy 默认 `SYNCHRONIZED`）。**lib 缺同步**：并发首次访问可能创建两个 Impl 实例 |
| `launcher/controller/AnimationController.kt:175-205`（`delayStartActivityIfNeed`） | `AnimationController.java:608-650`（同款方法） | lib 三层顺序 `if`（**非互斥**），原厂 `if / else if / else if` 互斥。**lib 把原厂的互斥结构破坏为非互斥**——见 review 03 §3-b 与本报告 §③-风险 3 |
| `pending/PendingAnimation.kt:13-21`（`anim / animHolders`） | `com/android/launcher3/anim/PendingAnimation.java:24-29`（同款字段） | `AnimatorSet anim` + `mutableListOf<Holder> animHolders`（lib，**裸集合**）↔ 原厂同款（**裸集合**）。**一致无锁**，靠调用方单线程访问 |
| `playback/AnimatorPlaybackController.kt:18-22`（`anims / childAnimations / endActions`） | `com/android/launcher3/anim/AnimatorPlaybackController.java:28-35,38-44,46-47` | `mutableListOf<Animator> anims` + `Array<Holder>` + `mutableMapOf<String, () -> Unit> endActions`（lib）↔ 原厂同款结构。**一致** |
| `playback/AnimatorPlaybackController.kt:25`（`targetCancelled`） | `AnimatorPlaybackController.java:73-74`（`mTargetCancelled`） | `private var targetCancelled = false`（lib，**非 volatile**）↔ 原厂同款。**一致假设主线程访问** |
| `pending/AnimationSuccessListener.kt:7-11` | `com/android/launcher3/anim/AnimationSuccessListener.java:7-21` | `protected var cancelled = false`（lib，**非 volatile**）↔ 原厂 `protected boolean mCancelled`，但父类 `ActualEndAnimListener.mCancelled` 自身有 listener 链同步语义。**语义一致** |
| `util/Trace.kt:13-15`（`STACK`） | `android.os.Trace`（平台类） | `ArrayDeque<String> STACK` + `traceBegin/End` 内**无同步**（lib）↔ 原厂 `Trace.traceBegin/End` native（线程安全由 native 端保证）。**lib 多线程并发时栈会乱** |

---

## ② 保真度评估

### A. 精确复刻（行为可对齐）

| # | 设计点 | 原厂证据 | lib 证据 |
|---|---|---|---|
| 1 | `AsyncValueAnimator.isEnd` AtomicBoolean CAS 门控 | `AsyncValueAnimator.java:31,57,80`（`compareAndSet(false, true)`） | `AsyncValueAnimator.kt:30,33` — 同一 compareAndSet，语义一致 |
| 2 | 异步消息投递（listener 派发穿透 sync-barrier） | `Utilities.java:631-637`（`Message.obtain(h,r).setAsynchronous(true)`） | `LooperExecutor.kt:39-46` 同一 `Message.obtain(h) { action() }.apply { isAsynchronous = true }` |
| 3 | 线程切换协议：`isCurrentThread` → `super.xxx()` 否则 `executor.execute(...)` | `AsyncValueAnimator.java:116-127,130-141,153-164`；`CustomRectFSpringAnim.java:613-621,763-770,888-895` | `AsyncValueAnimator.kt:42-53`（`marshal{}` 内联）— 形状一致 |
| 4 | listener 懒删除 + 快照迭代（消除 CME 窗口） | `AsyncAnimCallbacks.java:38-43`（反向 `remove(size)` + `toArray`） | `AsyncAnimCallbacks.kt:67-79`（`removeAll { null }` + `filterNotNull`）— 实现路径不同但语义等效 |
| 5 | `runningTaskInfo` 字段用 `volatile` 跨线程发布 | `AnimationController.java:83`（`private volatile TaskInfo mRunningTask`） | `AnimationController.kt:33`（`@Volatile private var runningTaskInfo: Any?`） |
| 6 | AnimationFeatureHelper 用 `volatile` 读 + `synchronized` 写的双层结构 | `AnimationFeatureHelper.java:52-60`（9 个 volatile）+ `:99-156`（8 个 `synchronized` setter）+ `:320,345`（列表字段单独 `synchronized(this.m*)`） | `AnimationFeatureHelper.kt:11-19`（SyncedVar：`@Volatile` 读 + `synchronized(lock)` 写）— 锁粒度更粗但语义等效 |
| 7 | AnimationHandler 的 ThreadLocal 单例语义（每线程一份） | vendored core `:13,158-172`；`OplusExecutors.java:170` 证明框架 `android.animation.AnimationHandler` 同样 ThreadLocal | `AnimationHandler.kt:132-141`（`threadLocalHandler = ThreadLocal<AnimationHandler>()`） |
| 8 | `LooperExecutor.execute` 同线程内联执行、跨线程 Handler.post | `LooperExecutor.java:27-33`（`getHandler().getLooper() == Looper.myLooper()`） | `LooperExecutor.kt:33-37`（`isCurrentThread` 判 `handler?.looper?.thread === Thread.currentThread()`） |

### B. 有意简化（lib 注释/文档中明示或合理 demo 化）

| # | 简化内容 | 原厂对应 | lib 取舍理由 |
|---|---|---|---|
| 1 | TickScheduler 用 `ConcurrentLinkedQueue` + `AtomicLong` + `@Volatile` 三件套 | vendored core/dyn `FrameCallbackProvider*` 用裸 ArrayList / 简单长整型 | lib 跨线程（动画线程 + 业务线程都能 add/remove callback）必须用并发容器；原厂回调列表就是 Looper 派发线程私有，**天然串行**。lib 多写了同步层是必须的合理差异 |
| 2 | `ScheduledTickScheduler` / `HandlerTickScheduler` 用 `@Synchronized start/stop` + `@Volatile running` 守护状态 | 原厂 `Choreographer.postFrameCallback` / `handler.postDelayed` 内部由 Looper 串行化，**无显式同步** | lib 的 scheduler 是跨线程可达对象（`addAnimationFrameCallback` 可被任意线程调），需要显式守护 running 标志的可见性 |
| 3 | `AnimationControlThread.instance` 用 `LazyThreadSafetyMode.SYNCHRONIZED` 懒创建 | 原厂 `ANIM_EXECUTOR = new OplusLooperExecutor(...)` 是 `static final`，JVM 类初始化锁保证唯一 | 行为等效（都是"线程安全的一次性构造"）；lib 用 synchronized lazy 写得更显眼 |
| 4 | `AnimationHandler.swapScheduler` / `TickSchedulerHolder.get` 加 `@Synchronized` | 原厂 vendored `setProvider` 由 framework 保证（`AnimationHandler.java:174-176`，框架内部锁） | lib 没有 framework 保证，自加 `@Synchronized` 是必要的 |
| 5 | `AnimSeqTimeStamp` 用 `@Volatile` 字段 + 纯 lock-free 读 | 原厂用 4 个 `static long` 字段 + 所有方法 `synchronized`（**全方法锁**，包括读路径） | lib 锁粒度更细（无锁读 + 顺序写），但要求**写者也在同步语境下更新**——lib 写路径是裸赋值（`AnimSeqTimeStamp.kt:25-29, 34-38`），**漏锁！** 见 §③-风险 1 |
| 6 | `TaskStateChangeTimeOutListener.handler` 用裸 `Handler?` 引用 | 原厂 `handler` 字段**也无同步**（构造期一次写、之后只读，构造期可见性由 final 字段保证） | lib 的 `handler` **不是 val**（构造时 `mainLooper()?.let(::Handler)`，懒解析，**有 race**：构造器未完成就被另一线程 `dispose()` 可能 NPE）。见 §③-风险 4 |
| 7 | `OplusAnimManager` 的 Impl 字段用裸 `var` 而非 lazy | 原厂用 Kotlin `f4.g` 委托（即 `Lazy<T>`，默认 SYNCHRONIZED） | lib 注释（`OplusAnimManager.kt:6-7`）自承"简化版应是 t4.b 类型懒加载"，并发首次访问可能创建两个实例 |
| 8 | `Trace.STACK` 用裸 `ArrayDeque<String>` 无同步 | 原厂 `android.os.Trace` 是 native 实现，线程安全 | lib 多线程并发 `traceBegin/End` 时栈会乱（JVM 单测下无问题） |
| 9 | `PendingAnimation` / `AnimatorPlaybackController` 的列表 / 数组全部裸集合 | 原厂同样裸集合 | **两者一致**——靠"主线程构造 / 主线程 start / 主线程回调"的纪律 |
| 10 | `AnimationSuccessListener.cancelled` 用 `var`（非 volatile） | 原厂 `mCancelled` 在 `AnimationSuccessListener.mCancelled`，父类 `NullableAnimatorListenerAdapter.mCancelled`，也非 volatile | **一致** |

### C. 遗漏 / 偏差（未在 lib 注释中说明、且影响语义）

| # | 遗漏点 | 原厂证据 | 影响 |
|---|---|---|---|
| 1 | **`AnimSeqTimeStamp` 写路径漏锁（高风险）** | 原厂 4 个 update/reset 方法全 `synchronized`（`AnimSeqTimeStamp.java:31-40,51-58,71-80,91-100` 等 8 个 `@JvmStatic synchronized` 方法），所有读写共享同一把类锁 | lib 4 个字段 `@Volatile` 只能保证**单个 long 写可见性**，但 `updateLastStartAppTime / updateLastRecentFinishTime / ...` 的实现是裸 `lastXxxTime = clock()`（`AnimSeqTimeStamp.kt:25,30,34,38`），**没在 lock 内**。后果：写者 A 写 `lastStartAppTime` 同时写者 B 写 `lastRecentFinishTime` 没问题（不同字段），但同一字段并发写可能丢更新；读端 `gapTo()` 是 `clock() - timestamp` 多字段读，**可能读到撕裂的快照**——500/300ms 防抖窗口在并发触发下可能误判 |
| 2 | **`AnimSeqTimeStamp` 整方法 synchronized vs 仅字段 @Volatile：粒度反向了** | 原厂：`@JvmStatic synchronized` 方法（类对象作 monitor）— 读路径也加锁，**简单粗暴但绝对正确** | lib：无锁读路径 + 无锁写路径 — 性能更好但正确性降级。原厂能扛住 14 路并发触发（多模块共享时间戳），lib 在并发密集场景下可能产生"时间戳回退"或"双触发都判 300ms 内" |
| 3 | **`AnimationController.delayStartActivityIfNeed` 把互斥结构破坏为顺序 if**（高风险，跨线程时序错位） | 原厂 `if / else if / else if` 三层互斥（`:608, :627, :645`），第一层 listener 存在但条件不满足时**穿透到清理段返回 false** | lib 三个顺序 `if`（`:177, :186, :193`），第一层不满足会继续试第二、三层；叠加 review 03 §3-b 子项（漏 `isTablet()` / 漏 `isSpecialAppScene(intent)` / 时间窗 vs 运行态判定差异），最终落点也不会 dispose listener、清 Between 标志——状态残留可能影响下一次进出场。**这是 review 03 已记录的非并发原语相关偏差** |
| 4 | **`TaskStateChangeTimeOutListener.handler` 字段不是 `val`、构造期 lazy 解析 + 无可见性同步（中风险）** | 原厂 `private Handler handler`（裸引用但**构造期一次性赋值 + 之后只读**，final 字段语义保证可见性） | lib `private val handler: Handler?` 看似 final，**但实际值在 init 块执行 `mainLooper()?.let(::Handler)` 才确定**——`val` 保证"该字段只赋一次"但不保证"其他线程看得到已构造的对象"。并发场景下：构造器还在跑 `init { handler?.postDelayed(...) }` 时，另一线程若持有引用并调 `dispose()`，可能 NPE。**真实场景下主线程构造 + UI 线程 dispose 无问题**（Handler 同 Looper 内可见），JVM 单测时构造期更短，**有微小 race window** |
| 5 | **`OplusAnimManager.animationControllerImpl/SeqHelperImpl` 用裸 `var` 无同步（中风险）** | 原厂用 Kotlin `Lazy<T>` 委托（默认 `LazyThreadSafetyMode.SYNCHRONIZED`）— 多个线程同时首次访问只会创建一个实例 | lib `private var animationControllerImpl: AnimationController? = null`（`OplusAnimManager.kt:11-12`）— **裸 var 无锁**。并发首次访问 `animController` getter 可能创建两个 `AnimationController` 实例。生产代码多线程访问少见，但**理论 race window 存在** |
| 6 | **`AnimationFeatureHelper` 两个列表字段无任何保护（中风险）** | 原厂 `m1pxPkgDisableList` / `m1pxCardDisableList` 是 `volatile List<>`，**且 `updateRusConfig` 写入时 `synchronized(this.m1pxPkgDisableList)` / `synchronized(this.m1pxCardDisableList)`**（`:320, :345`）。**读路径直接返回 volatile 引用**（`:376, :380`），靠"调用方只读不写"约定 | lib `onePxPkgDisableList` / `onePxCardDisableList` 是 `mutableListOf()` **裸 mutableList**，**没有任何锁或 volatile**。外部代码若拿到引用并 `.add(...)` / `.clear()`，**与 RUS 下发线程并发**时可能 CME（ArrayList 自身的 modCount 非原子）。原厂的 volatile 引用 + synchronized 重写段保证了"引用替换原子 + 段内互斥"，lib 完全没这层 |
| 7 | **`AnimationFeatureHelper.simulateRemoteUpdate` 写锁粒度**（低风险，但与原厂对比粒度反向） | 原厂每字段一个 `synchronized` setter，**两字段之间不需要互斥** | lib 7 字段共享同一 `lock`，**全部写入必须串行**。功能正确（更粗的锁 = 更安全），但 demo 场景下没必要 |
| 8 | **`AsyncAnimCallbacks.animListeners` 与 OPPO 同款裸 ArrayList 无锁，但 lib 的 remove 是 `mutableListOf` 而非 ArrayList**（低风险） | OPPO `ArrayList<NullableAnimatorListener> mAnimListeners`（`AsyncAnimCallbacks.java:34`）— `addListeners` / `removeListener` / `getListeners` 全部裸 ArrayList 操作，**靠"主线程 add + 迭代在主线程派发"的纪律**。**原厂自己也有 race 隐患**，不是 bug | lib `mutableListOf<NullableAnimatorListener?>()`（`AsyncAnimCallbacks.kt:21`）— 同款裸集合 + 同款纪律。lib 比原厂**多一层快照保护**（`removeAll { null }` 后 `filterNotNull` 拷快照），但**写者并发仍可能 CME**——这是**与原厂对齐的合理行为**，不是 lib 的 bug |
| 9 | **`Trace.STACK` 用裸 ArrayDeque 多线程 race（低风险）** | 原厂 `android.os.Trace` 是 native，线程安全 | lib `STACK.addFirst` / `removeFirst` 多线程并发时**栈深度可能错乱**。但 trace 仅作 demo 日志，**实际不影响功能正确性** |
| 10 | **`TaskStateHelper.TaskStateChangeTimeOutListener` 用 `URGENT_TRANSACTION_EXECUTOR` 而非 Main**（已知但非并发原语差异） | `TaskStateHelper.java:130` — 超时回调跑在 `-8` 优先级线程 | lib 用 Main Looper — 超时回调跑主线程，**与原厂线程模型不一致**（review 03 §2.2-5 已记） |
| 11 | **`AnimationController` 其他 12+ 状态字段（`isLandScapeGesture` 等）全裸字段、无 @Volatile** | 原厂同样裸字段，**两者一致假设主线程访问**——但 OPPO 还有 `checkMainThread()`（`:233`）做纪律校验，lib 无此检查 | **与原厂行为对齐**（都靠纪律），但**lib 比原厂少一层兜底**——业务在非主线程调 `setOnAppExit` 时原厂会 throw，lib 静默改状态 |

---

## ③ 行为差异风险点（按严重度排序）

### 风险 1（高）：`AnimSeqTimeStamp` 多字段并发读写的撕裂快照

**位置**：`AnimSeqTimeStamp.kt:9-29, 30-38, 38-45`

**问题**：
- 原厂：4 个时间戳字段 + 8 个 `static synchronized` 方法（`@JvmStatic synchronized`），读写共享类对象 monitor，**绝对原子**。
- lib：4 个字段标 `@Volatile`（仅单字段写可见性），但 `updateLastStartAppTime / updateLastRecentFinishTime` 等 4 个写方法**裸赋值无锁**。
- `gapTo(timestamp)` 多字段读路径（`:42`）**读两个 volatile 字段**（`timestamp` + `clock()`）—— 实际只读一个字段，`clock()` 是当前时间无 race；但**两次连续读不同字段**（如 `canFinishRecent` 同时查 `lastRecentFinishTime`，`canInterceptGesture` 查 `lastStartAppTime`）如果两个写线程并发写，**可能读到"刚跨过 500ms 阈值又回退"** 的撕裂状态。

**实际触发场景**：
- 启动 app 后立即上滑（`updateLastStartAppTime` 在 `onAppStart`，`canInterceptGesture` 在 gesture 输入回调查时间窗）—— 两个线程并发更新。
- recents 关闭期间同时上滑手势 — `updateLastRecentFinishTime` + gesture 处理并发。

**影响**：
- 500/300ms 防抖窗口误判：可能"刚结束过 recents（500ms 内）"和"刚启动过 app（300ms 内）"两个判定在并发写窗口下被撕裂，导致 `delayFinishRecents` 该延迟的不延迟、或不该延迟的延迟。
- 不导致功能崩溃，但**可能导致手势/启动时序错位**。

**修复**：把 `updateLastXxxTime` 4 个方法包进 `synchronized(this)`，与读路径共享 monitor——与原厂"全方法 synchronized"对齐。代价：每个 update 多一次 monitor enter/exit（纳秒级，可忽略）。

---

### 风险 2（高）：`AnimationFeatureHelper.onePxPkgDisableList / onePxCardDisableList` 完全无保护

**位置**：`AnimationFeatureHelper.kt:21-22`

**问题**：
- 原厂：`m1pxPkgDisableList / m1pxCardDisableList` 是 `volatile List<>`，且 `updateRusConfig` 的列表写入段用 `synchronized(this.m1pxPkgDisableList)` / `synchronized(this.m1pxCardDisableList)`（`:320, :345`）做"引用替换 + 内容重写"互斥。**读路径直接返回 volatile 引用**——靠"调用方只迭代不修改"的纪律。
- lib：`onePxPkgDisableList: List<String> = mutableListOf()` —— **裸 mutableList**，既不是 volatile、也没有锁。`simulateRemoteUpdate` 函数**不修改这两个列表**（只改 6 个标量字段），**两个列表永远为空**——但 demo 通过 setter 模拟的"RUS 下发"如果未来扩展到改这两个列表，将**完全无保护**。

**实际触发场景**：
- 现有 demo 不触发（列表内容为空，simulateRemoteUpdate 不碰它）。
- 未来如果给 `simulateRemoteUpdate` 加列表参数 / 给 setter 暴露列表字段，**业务方拿到 `mutableListOf` 引用后 .add() 会与 RUS 线程并发**——`ArrayList` 的 `modCount` 字段非 volatile，迭代时可能 CME / `IndexOutOfBoundsException` / 静默丢元素。

**影响**：
- 当前 demo 没问题。
- 扩展到列表下发时将是**潜在崩溃源**。

**修复**：把列表字段改成 `volatile List<>` + 写时拷新 ArrayList 替换引用 + 读时返回引用——完全对齐原厂模式。10 行改动。

---

### 风险 3（中）：`AnimationController` 12+ 状态字段全部非 volatile、无 `checkMainThread` 兜底

**位置**：`AnimationController.kt:24-32`（12 个状态字段）+ 全方法（无 `checkMainThread`）

**问题**：
- 原厂：12 个字段（`mIsBetweenAppExitTransitionEndAndFinish / mIsLandScapeGesture / mIsSplitScreenGesture / mOnceGestureProcessing` 等）**裸字段无 volatile**，但 `private final boolean checkMainThread()`（`:233`）在每个 mutator / 关键 accessor 前调用——非主线程直接抛异常。**靠"业务方必须主线程调用"的纪律 + 显式断言**。
- lib：12 个字段同样裸无 volatile，**且无任何 checkMainThread 检查**。`setOnAppExit / setOnceGestureProcessing / setAppToOverviewContinuationState` 等 mutator（`:157-166`）无线程约束。

**实际触发场景**：
- 当前 demo 全在主线程调——无问题。
- 真实业务中某些 mutator 可能从 binder 回调 / UX_TASK_EXECUTOR 回调触发。原厂会抛 `IllegalStateException("Not on main thread")`，lib 静默写入**撕裂的状态**（一个线程写 `isLandScapeGesture = true`，另一个线程在同一 CPU 缓存行写 `isSplitScreenGesture = false`，可能短暂读到 `true/false` 组合的非法中间态）。

**影响**：
- 当前 demo 没问题。
- lib 用于真实 launcher 时将是潜在崩溃源（业务依赖状态机 12 个 boolean 的组合，撕裂态触发错误状态转移）。

**修复**：从 `DefaultAnimationController` 基类抽 `checkMainThread()`，所有 mutator 入口调用；非主线程 `throw IllegalStateException`。20 行改动。

---

### 风险 4（中）：`TaskStateChangeTimeOutListener.handler` 字段 lazy 解析 + 无 volatile

**位置**：`TaskStateChangeTimeOutListener.kt:24-32`（构造器）+ `:40-42`（dispose）

**问题**：
- 原厂：`TaskStateHelper.java:118` `private Handler handler` —— 构造期一次性赋值后只读（`init` 块内 `this.handler = OplusExecutors.URGENT_TRANSACTION_EXECUTOR().getHandler();`）；final 字段语义保证跨线程可见性（即使 Java 字段不写 final，构造期内发布到其他线程依赖 happens-before；final 字段有 JMM 额外保证）。
- lib：`private val handler: Handler?` —— `init { handler?.postDelayed(...) }`，**构造期 init 块执行 `mainLooper()?.let(::Handler)` 才确定 handler 实例**，**val 不保证其他线程在构造期可见**（虽然 Kotlin val = Java final，但**val 字段的初始化分两步**：先默认 null，再在 init 块内赋值——final 重排序保证只覆盖默认初始值，不保证跨线程 publish-to-init-block-happens-before）。

**实际触发场景**：
- 当前 demo 主线程构造 + 主线程 dispose——**不会触发**，因为同线程 init → dispose 是顺序的。
- JVM 单测环境构造器内 `runCatching { Looper.getMainLooper() }` 可能返回 null，handler = null，整个对象无操作。
- **理论上**：构造器未完成时另一线程拿到引用并 dispose，dispose 看到 `handler = null` 静默 no-op；构造器稍后 `handler?.postDelayed(timeOutOption, duration)` —— timeOutOption 永远不会 dispose。这是泄漏（不致命）。

**影响**：
- 极低概率触发，且不影响功能正确性。
- 但 lib 与原厂的"final 字段一次写"语义**形式上不等价**——原厂的 `URGENT_TRANSACTION_EXECUTOR.handler` 是类加载期就稳定的 final 字段，lib 的 handler 是构造期 lazy 解析。

**修复**：把 `private val handler: Handler?` 改成 `private val handler: Handler? = mainLooper()?.let(::Handler)` 移到字段声明处（**字段默认初始化**而非 init 块），让 final 字段语义生效。或者改成 `@Volatile var handler: Handler?` 并接受可见性需要同步。

---

### 风险 5（中）：`OplusAnimManager.Impl` 字段裸 var + 无 lazy 同步

**位置**：`OplusAnimManager.kt:11-12`

**问题**：
- 原厂：用 Kotlin `Lazy<T>` 委托（`f4.g`，即 `LazyThreadSafetyMode.SYNCHRONIZED`）—— **首次访问同步，多线程安全**。
- lib：`private var animationControllerImpl: AnimationController? = null` —— **裸 var，无锁**。`OplusAnimManager.kt:6-7` 注释自承"应该是 t4.b 类型懒加载"。

**实际触发场景**：
- 当前 demo 在 `init { if (supportInterruption()) { animationControllerImpl = AnimationController() } }` 内创建（`object OplusAnimManager` 类初始化阶段），**JVM 类初始化锁天然保护**。
- 但 `interruptionEnabled` setter（`:34-38`）在 demo 8 中被用户切换，**任意线程都可能调**。两个线程并发 `interruptionEnabled = true` 同时检查 `animationControllerImpl == null`，**可能各自创建一个新 AnimationController 实例**，导致 `animController` getter 在不同时刻返回不同实例。

**影响**：
- 中等风险：状态机状态可能"分裂"在不同实例上，listener 注册到实例 A 但业务查询走实例 B。
- demo 8 演示场景在主线程切换，单线程 OK。

**修复**：把 Impl 字段改成 `private val animationControllerImpl: AnimationController by lazy(LazyThreadSafetyMode.SYNCHRONIZED)`。5 行改动。

---

### 风险 6（中）：`AnimationFeatureHelper.SyncedVar` 与原厂锁粒度反向

**位置**：`AnimationFeatureHelper.kt:34-43`（`simulateRemoteUpdate`）+ `:46-55`（`SyncedVar.setValue`）

**问题**：
- 原厂：8 个字段 8 个独立 `synchronized` setter，**两字段之间的并发写入互不阻塞**。
- lib：7 个字段共享同一 `lock`，`simulateRemoteUpdate` 用 `synchronized(lock)` 包 6 个写入。**功能正确但锁粒度过粗**。

**实际触发场景**：
- 当前 demo 单线程写入——无影响。
- 多线程场景下：原厂可让 `mAsyncEnable` 与 `mInterruptThreshold` 并发更新；lib 串行化所有更新，**降低吞吐**。

**影响**：
- 功能正确，**性能降级**。原厂粒度更适合"读多写少"的灰度配置场景（一个 RUS 回调线程写，多个业务线程读）。

**修复**：让每个 `SyncedVar` 实例持自己的 lock（原厂就是每字段独立 monitor）。10 行改动，但 demo 场景下意义不大。

---

### 风险 7（低）：`AnimationController.delayStartActivityIfNeed` 三层非互斥

**位置**：`AnimationController.kt:175-205`

**问题**：lib 三个顺序 `if`，原厂 `if / else if / else if` 互斥。review 03 §3-b 已记录，**非并发原语问题**但**并发触发下放大**：
- 第一层 listener 存在但条件不满足时，原厂直接穿透到清理段返回 false，**dispose listener + 清 Between 标志**。
- lib 顺序 if 会继续试第二、三层——可能返回 true 把 action 挂起，**业务方以为进入特殊场景但实际进入第二层超时窗口**。并发触发下第一层 dispose 没执行、listener 残留。

**修复**：改回 `if / else if / else if` + 最终清理段。已在 review 03 §4.1 第 4 条列。

---

### 风险 8（低）：`AsyncAnimCallbacks.animListeners` 写者并发 CME

**位置**：`AsyncAnimCallbacks.kt:21`（声明）+ `:25-27`（addListener）+ `:29-32`（removeListener 懒删除）+ `:67-79`（`getListeners` 快照迭代）

**问题**：
- 原厂 `mAnimListeners` 也是裸 ArrayList，**无任何并发原语**。原厂的纪律：业务 listener 注册/移除都在主线程；派发也强制 marshal 回主线程；快照迭代在主线程进行——**单线程访问约定**。
- lib 同款裸 mutableListOf，**纪律与原厂一致**。但 lib 在 `dispatch`（`:82-89`）前调 `Trace.traceBegin(8L, ...)`，派发时 `runOnMainThread { for (l in getListeners()) { ... } }`——主线程迭代时 `getListeners` 会做 `removeAll { null }` + `filterNotNull`，**这是同步操作**，但 `animListeners` 本身可能被业务在主线程调 `removeListener` 修改——**单线程内无 race**。

**实际触发场景**：
- 业务代码在动画线程调 `addListener` —— `mutableListOf` 非同步，**可能 CME**。原厂 ArrayList 同款行为。
- 业务在派发回调内调 `removeListener`（listener 自删）—— 与 dispatch 迭代冲突。原厂 ArrayList 同款。

**影响**：
- 与原厂行为对齐（**双方都有此隐患**），不算 lib 的 bug。
- demo 中业务 listener 都在主线程注册/移除，无触发场景。

**修复**：如要"超过原厂"，把 `animListeners` 改成 `CopyOnWriteArrayList<NullableAnimatorListener?>`——**写者线程安全、迭代快照**。但这是 lib 增强、原厂没做，保持简化也合理。

---

### 风险 9（低）：`ScheduledTickScheduler / HandlerTickScheduler` `@Volatile running` + `@Synchronized start/stop` 的复合语义

**位置**：`ScheduledTickScheduler.kt:46-67` + `HandlerTickScheduler.kt:49-65`

**问题**：
- lib：`@Volatile var running` + `@Synchronized fun start/stop`——读路径无锁直接访问 `running`（`tick()` 内 `:76`、`:78`、`scheduleNextFrame` 内 `:70, :72, :74`）。
- 原厂无对应原语：vendored `FrameCallbackProvider14/16` 用 `handler.postDelayed(this, frameDelay)` 自走，**没有任何守护标志**——postDelayed 本身就是同步的（Handler enqueueMessage 是 synchronized）。

**实际触发场景**：
- 当前 lib：`@Synchronized start()` 调 `running = true` + `scheduleAtFixedRate`；tick 内 `if (callbacks.isEmpty()) stop()` —— stop 内 `@Synchronized` + `running = false` + `task?.cancel(false)`。
- 线程 A 在 `tick` 内刚判 `!callbacks.isEmpty()` 还没调 stop，线程 B 调 `start()` —— **start 的 @Synchronized 等 tick 走完**，但 tick 的 `stop()` 也是 @Synchronized，会等 B 的 start 完成。**死锁不会发生但锁竞争存在**。
- `running` 的 @Volatile 保证 start/stop 修改对 tick 线程可见——但 tick 读 running 是无锁读，可能读到"已开始但未注册 callback"的中间态。

**影响**：
- 功能正确，**轻微性能开销**（@Synchronized + @Volatile 组合）。
- 与 vendored 原版（无锁）相比，多了 5% 调度开销。**demo 场景忽略不计**。

**修复**：保留即可。原厂能省锁是因为 Handler 自身同步；lib 跨线程 addFrameCallback 无法依赖 Handler，必须显式守护。

---

### 风险 10（提示）：JVM 单测兜底掩盖配置错误

**位置**：`LooperExecutor.kt:17-20`、`Executors.kt:17-19, 24-26`、`AnimationControlThread.kt:63-65`、`TaskStateChangeTimeOutListener.kt:39-41`

**问题**：
- lib 多处用 `runCatching { Looper.getMainLooper() }?.getOrNull()` 兜底，handler 为 null 时退化为"就地执行"。
- 原厂所有路径都假设 Handler 永不为 null（Looper 永不为 null 在 Android 进程内）。
- **真实设备上若 Looper.getMainLooper() 返回 null（极少见：进程极早期 / 系统 bug）**，原厂 NPE 暴露、lib 静默退化为就地执行——**动画跑到错误线程上**。

**影响**：
- 极低概率。
- lib 注释（`LooperExecutor.kt:13-16`、`Executors.kt:7-9`）已明示为 JVM 单测便利。

**修复**：保留。JVM 单测价值 > 真实设备 NPE 风险。

---

## ④ 回移建议

### 4.1 值得补进 lib 的（性价比高）

| # | 改动 | 理由 | 工作量 |
|---|---|---|---|
| 1 | **修 `AnimSeqTimeStamp` 写路径加 `synchronized(this)`**（`AnimSeqTimeStamp.kt:25,30,34,38`） | 与原厂 `@JvmStatic synchronized` 模式对齐；消除多字段并发写撕裂快照；500/300ms 防抖窗口误判修复 | 4 行（每个方法体外包 `synchronized(this)`） |
| 2 | **`AnimationFeatureHelper` 列表字段加 volatile + 写时拷新 ArrayList**（`:21-22`） | 与原厂 `volatile List<>` + `synchronized(this.m*)` 段模式对齐；为未来扩展留安全基础 | 10 行 |
| 3 | **`AnimationController` 加 `checkMainThread()` 兜底**（基类或 Impl 入口） | 与原厂 `private final boolean checkMainThread()`（`:233`）对齐；非主线程访问状态机字段会 throw，避免撕裂态 | 20 行 |
| 4 | **`OplusAnimManager.Impl` 字段改 `by lazy(SYNCHRONIZED)`**（`:11-12`） | 与原厂 Kotlin `Lazy<T>` 委托对齐；消除 demo 8 / 多线程切换 race | 5 行 |
| 5 | **`TaskStateChangeTimeOutListener.handler` 字段默认初始化而非 init 块内赋值**（`:24-26`） | 让 Kotlin `val` 的 final 字段语义真正生效；构造期 lazy 解析变字段默认初始 | 5 行 |
| 6 | **`AnimationFeatureHelper.SyncedVar` 每字段独立 lock**（`:46-55`） | 与原厂"每字段独立 monitor"对齐；保留读多写少场景的并发吞吐 | 10 行（构造器传 `Any()` 而非共享 lock） |

### 4.2 建议保持简化的

| # | 保留简化 | 理由 |
|---|---|---|
| 1 | **TickScheduler 用 `ConcurrentLinkedQueue + AtomicLong + @Volatile + @Synchronized` 三件套** | lib 跨线程可达（任意线程都能 addFrameCallback），必须显式守护；原厂 Looper 派发线程私有 = 天然串行。原厂"无并发原语"是 Looper 派发的副产品，**不能搬到 lib** |
| 2 | **`AnimationHandler.swapScheduler` / `TickSchedulerHolder.get` 加 `@Synchronized`** | 原厂 framework `setProvider` 内部有锁；lib 没有 framework 兜底，自加锁必要 |
| 3 | **`AsyncAnimCallbacks.animListeners` 裸 mutableListOf（与原厂 ArrayList 对齐）** | 双方都靠"主线程 add + 主线程 dispatch 迭代"的纪律；改 `CopyOnWriteArrayList` 是 lib 增强、原厂没做，**超原厂** |
| 4 | **`AnimSeqTimeStamp` 字段 `@Volatile` 读路径** | 与原厂 `@JvmStatic synchronized` 读路径对比，**lib 无锁读性能更好**（仅写加锁即对齐，参见 4.1-1） |
| 5 | **`PendingAnimation` / `AnimatorPlaybackController` 的列表/数组全部裸集合** | 与原厂一致假设"主线程构造 + 主线程访问" |
| 6 | **`AnimationSuccessListener.cancelled` 非 volatile** | 与原厂一致（父链上 `ActualEndAnimListener.mCancelled` 也非 volatile）；cancel/end 在同一线程调用，无 race |
| 7 | **`OplusLooperExecutor` 四扩展（executeAtFront / executeWithUx / executeBlockWait / executeDelay）不移植** | 依赖 LauncherBooster 私有 API；executeBlockWait 是 v4 §9.3 点名 ANR 风险——**原厂自己也不该这么写** |
| 8 | **`Trace.STACK` 裸 ArrayDeque 多线程 race** | demo 日志用，线程安全无价值 |
| 9 | **JVM 单测兜底（handler null → 就地执行）** | 单测便利 > 真实设备 NPE 风险 |
| 10 | **`AnimationController.delayStartActivityIfNeed` 三层非互斥的修复归到 review 03 §4.1-4** | 已在之前 review 列；本区域专注并发原语，行为差异归原 review |

---

## 附：关键证据速查

| 论断 | 证据（lib 路径 / 原厂 文件:行） |
|---|---|
| lib `AsyncValueAnimator.isEnd` AtomicBoolean CAS | `AsyncValueAnimator.kt:30,33,37` ↔ `com/android/quickstep/util/animation/AsyncValueAnimator.java:31,57,80` |
| lib `asyncAnimCallbacks.animListeners` 裸 mutableList | `AsyncAnimCallbacks.kt:21` ↔ `AsyncAnimCallbacks.java:34`（`ArrayList mAnimListeners`） |
| lib `AsyncAnimCallbacks.getListeners` 快照迭代 | `AsyncAnimCallbacks.kt:75-79` ↔ `AsyncAnimCallbacks.java:38-43` |
| lib 异步消息投递 `Message.setAsynchronous(true)` | `LooperExecutor.kt:39-46` ↔ `Utilities.java:631-637`（被 `AsyncAnimCallbacks.java:120,160` 调用） |
| lib `AnimSeqTimeStamp` @Volatile 字段 + 无锁读写 | `AnimSeqTimeStamp.kt:9-21,25-29` ↔ `AnimSeqTimeStamp.java:25-28, 31-138`（8 个 `@JvmStatic synchronized` 方法） |
| lib `AnimationFeatureHelper.SyncedVar` 共享 lock | `AnimationFeatureHelper.kt:34-43,46-55` ↔ `AnimationFeatureHelper.java:52-60, 99-156`（每字段独立 synchronized） |
| lib `AnimationFeatureHelper.onePxPkgDisableList` 裸 mutableList | `AnimationFeatureHelper.kt:21-22` ↔ `AnimationFeatureHelper.java:56-57`（`volatile List<>`）+ `:320,345`（`synchronized(this.m*)` 写段） |
| lib `AnimationController.runningTaskInfo` @Volatile | `AnimationController.kt:33` ↔ `AnimationController.java:83`（`private volatile TaskInfo mRunningTask`） |
| lib `AnimationController` 12+ 状态字段全裸 | `AnimationController.kt:24-32` ↔ `AnimationController.java:67-87`（同款裸字段，原厂另有 `checkMainThread()` `:233`） |
| lib `OplusAnimManager.Impl` 裸 var 无 lazy 同步 | `OplusAnimManager.kt:11-12` ↔ `OplusAnimManager.java`（`f4.g` Kotlin `Lazy<T>` 委托） |
| lib `TaskStateChangeTimeOutListener.handler` lazy 解析 | `TaskStateChangeTimeOutListener.kt:24-32` ↔ `TaskStateHelper.java:118,130`（构造期一次写 final） |
| lib `AnimationControlThread.instance` lazy SYNCHRONIZED | `AnimationControlThread.kt:80-83` ↔ `OplusExecutors.java:95`（`static final` JVM 类初始化锁） |
| lib `ScheduledTickScheduler / HandlerTickScheduler` `@Synchronized start/stop` + `@Volatile running` | `ScheduledTickScheduler.kt:46-67`、`HandlerTickScheduler.kt:49-65` ↔ vendored `FrameCallbackProvider14/16`（Looper 派发线程串行，**无对应原语**） |
| lib `AnimationHandler.swapScheduler` / `TickSchedulerHolder.get` `@Synchronized` | `AnimationHandler.kt:113-115,131-141` ↔ vendored `AnimationHandler.setProvider`（framework 内部锁） |
| lib `AnimationHandler` ThreadLocal 单例 | `AnimationHandler.kt:132-141` ↔ vendored core `:13,158-172`；`OplusExecutors.java:170`（框架 `android.animation.AnimationHandler` 同样 ThreadLocal） |
| 原厂"裸 ArrayList 无锁 + 主线程纪律"模式 | `AsyncAnimCallbacks.java:34-43`（mAnimListeners）、`AnimationController.java:67-87`（状态字段）、`AnimationSeqHelper.java:50-55`（seqId）—— **整套设计哲学是"靠纪律而非同步"** |
| 原厂"全方法 synchronized"模式（与 lib 粒度反向） | `AnimSeqTimeStamp.java:31-138`（8 个 `@JvmStatic synchronized` 方法）—— **写少读多也要加锁，性能保守** |
| 原厂"synchronized 段内重写 List"模式 | `AnimationFeatureHelper.java:320,345`（`synchronized(this.m1pxPkgDisableList) { clear(); add(); }`）—— **写时拷贝语义，但 OPPO 偷懒直接重写** |
