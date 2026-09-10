# 区域 07 对比 Review：并发原语与线程安全

## 2026-09-09 顺序验收：✅完成（第 12 份，含保留简化）

以下为六项回移建议及十项简化的当前结论。旧文中的“5% 调度开销”、字段初始化位置改变 final 语义等推断没有可复现证据，不作为本轮结论。

| §4.1 | 处理 | 当前证据 / 边界 |
|---|---|---|
| 1 timestamp 写锁 | ✅已完成 | update/reset 写方法已有 @Synchronized，字段 volatile；单字段 gap 读取不是多字段一致快照。不把写锁误称为所有读写组合原子化 |
| 2 feature 列表发布 | ✅本轮修复真正不可变快照 | 原 toList() 只提供 Kotlin 只读接口，2+ 元素仍可能向下转为可变 ArrayList。现复制后用 unmodifiableList 包装；外部 add/clear 被拒绝，旧快照不随更新改变 |
| 3 controller 线程约束 | ✅本轮补齐防御检查 | 17 个状态操作/完成查询入口、finish callback setter、基类 observer 修改/派发先检查主线程。owned timeout 的 event/dispose 同样在消费前检查；误调用不丢失 pending action |
| 4 manager 初始化 | ✅保留已同步的可切换实例 | object 初始化锁、volatile 引用、同步 toggle 已消除并发双建；不换成无法销毁重建的永久 by lazy。实例注销时清理资源属于生命周期 review，不能据此宣称已解决 |
| 5 timeout handler final | ✅已有构造期 final 字段；纠正论据 | 字段声明初始化和 init 块赋值均属于构造流程；并不是挪位置才获得 final 语义。不得在构造完成前逃逸 this；当前内部注册 closure 不向外发布未完成实例 |
| 6 feature 锁粒度 | ✅保留共享写锁并修复部分更新竞态 | 六参数 update 原在锁外读 onePxEnable，再等待写锁会把过期值覆盖回来；现读保留字段与更新在同一锁内。不为未经测量的吞吐收益拆分锁 |

### 线程边界与可观察回归

- 原厂 AnimationController.checkMainThread（Java:233-235）**只返回 boolean**，部分回调据此 marshal，并非原文所称所有 mutator 都抛异常。本库此次 fail-fast 是明确的防御契约，不冒充逐行 OEM 语义。
- Controller-owned listener 的线程检查先于 AtomicReference 消费和注册槽注销；Standalone 三参 timeout 保持原子一次性消费及调用线程 callback 语义，未强制所有用途迁移到 UI。
- Feature-off 基类无状态 no-op 仍可从后台安全调用；实际 observer 集合操作要求主线程。状态读取仍应遵守主线程契约，不能把这些检查理解为任意线程整体快照 API。
- FeatureSnapshotTest 通过受控锁竞争重现六参更新覆盖较新 onePx 值；只用反射控制阻塞点，断言的是公开配置结果。共享写锁不保证多个无锁 getter 之间是同一批次快照。

### §4.2 十项逐项结论

1. 旧两个 scheduler 已删除，但 ChoreographerTickScheduler 仍有并发容器/atomic/volatile；这些原语不证明可从任意线程操作 Choreographer，遵守 owner 线程。
2. 保留 handler/holder 同步辅助；换源还使用 generation，已在第 9 份回归，不能靠 synchronized 自动获得跨 Looper 所有权。
3. AsyncAnimCallbacks 已统一 listenerLock 保护增删/快照，锁外回调；不再按“裸集合保持”描述。
4. 时间戳 volatile 单字段读与同步写保留；没有同一时刻多字段一致读取保证。
5. Pending/APC 裸集合保留主线程使用，不承诺任意线程安全。
6. AnimationSuccessListener.cancelled 按所属动画线程访问，不额外加 volatile。
7. UX/阻塞 executor 四扩展不移植；不引入等待主线程的同步桥。
8. Trace 已 ThreadLocal，仅隔离各线程栈，不等于跨线程 begin/end 自动配对（下一份可观测性 review 核对）。
9. null Handler 的 JVM stub 兜底保留；controller 检查在真实 Main Looper 存在时生效。该兜底不算真机线程验证。
10. 三层 launch 决策已互斥并清理，按现有回归保留；时间/场景结论不从并发锁推导。

### 验证

新增 **ControllerThreadContractTest 6 + FeatureSnapshotTest 3 = 9 tests**，旧代码 **6 条失败**，修复后与 TaskState/ownership 共 **19 条定向通过**（`.gradle/review-ordered-12-red.log` / `-green.log`）。17 个入口在一条矩阵用例内，不按 17 条重复计数。Debug/Release 各 **181 tests 全通过**，Demo Debug 成功（76/76 tasks，1m 37s，`.gradle/review-ordered-12-final.log`），见[执行清单](2026-09-09-ordered-review-progress.md)；未做设备压力测试、Perfetto 或吞吐基准，Lint 未完成。

---

> **2026-09-09 当前复核**：AsyncAnimCallbacks 原厂裸集合不作为本库的线程安全保证：本轮统一加锁保护增删和快照，业务监听在锁外执行。历史“合理行为、不是 bug”不适用于本库的跨线程注册承诺。 详见 [本轮修复记录](2026-09-09-revalidation-fixes.md)。

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
| `core/AnimationHandler.kt:133, 140-142` | `androidx/core/animation/AnimationHandler.java:13-14,158-172`（vendored）；`android.animation.AnimationHandler` @hide 框架类（被 `MultiDynamicAnimation.java:3,21,127` implements） | `ThreadLocal<AnimationHandler> threadLocalHandler`（lib）↔ `sThreadLocal`（vendored core :13）。**两者均纯 ThreadLocal，零同步**。 |
| `core/AnimationHandler.kt:136-137`（`testHandler`） | 无对应 | `@Volatile var testHandler`（lib）— 测试钩子，独有 |
| `core/AnimationHandler.kt:122-127`（`TickSchedulerHolder`，`@Synchronized get` `:124-126`） | vendored `AnimationFrameCallbackProvider` 抽象 | `@Synchronized fun get()`（lib）— 懒构造；原厂无懒构造（直接 `mFrameCallbackProvider` 字段初始化） |
| `core/AnimationHandler.kt:111-119`（`swapScheduler`） | 无对应 | `@Synchronized fun swapScheduler`（lib）— demo/实验用，原厂无对应路径 |
| `ScheduledTickScheduler`（已删 215ecb5）· 现 `core/ChoreographerTickScheduler.kt:24-31, 66-76` | vendored `FrameCallbackProvider14`（core :33-79）；dynamicanimation `:50-72` | `ConcurrentLinkedQueue<FrameCallback> callbacks` + `AtomicLong frameCountAtomic/frameTimeNanosAtomic` + `@Volatile running` + `@Synchronized start/stop`（lib）↔ 简单 `mFrameCallbacks` ArrayList（vendored，原厂线程局部的回调列表本身就是 Looper 派发线程私有，**无并发原语**） |
| `HandlerTickScheduler`（已删 215ecb5）· 现 `core/ChoreographerTickScheduler.kt:24-31, 66-76` | vendored `FrameCallbackProvider16`（core :82-108） + `SfVsyncFrameCallbackProvider`（`OplusExecutors.java:5,170`） | `ConcurrentLinkedQueue callbacks` + `AtomicLong frameCountAtomic/frameTimeNanosAtomic` + `@Volatile running` + `@Synchronized start/stop`（lib）↔ 原厂 `mHandler.postDelayed(this, frameDelay)` + `Choreographer.postFrameCallback`；原厂**零并发原语**（Looper 派发线程 = 回调执行线程，天然串行） |
| `thread/AnimationControlThread.kt:86-88`（`instance`） | `com/oplus/basecommon/thread/OplusExecutors.java:95`（`static final OplusLooperExecutor ANIM_EXECUTOR`） | `by lazy(LazyThreadSafetyMode.SYNCHRONIZED)`（lib）↔ `static final`（JVM 类初始化锁，原厂）。**lib 用 synchronized lazy 模拟 JMM 类初始化**，但与 `static final` 在语义上等效 |
| `thread/AsyncAnimWrapper.kt:17-27` | `com/android/launcher3/anim/AsyncAnimWrapper.java:10-20` | 无并发原语（双方均只把 task 投给 LooperExecutor） |
| `anim/AsyncValueAnimator.kt:26`（`isEnd`） | `com/android/quickstep/util/animation/AsyncValueAnimator.java:31`（`mIsEnd`） | `AtomicBoolean isEnd` + `compareAndSet(false, true)`（双方一致，**精确复刻**） |
| `anim/AsyncAnimCallbacks.kt:33`（`animListeners`） | `com/android/quickstep/util/animation/AsyncAnimCallbacks.java:34`（`mAnimListeners`） | `mutableListOf<NullableAnimatorListener?>()`（lib）↔ `ArrayList<NullableAnimatorListener> mAnimListeners`（原厂）。**双方都无显式并发原语**——靠"主线程 add/listener-iterate 主线程派发"的纪律。**关键差异**：lib `getListeners()` 用 `removeAll { it == null }` 拷快照（`anim/AsyncAnimCallbacks.kt:76-79`），原版 `getListeners()` 用反向遍历 `remove(size)` + `toArray(new NullableAnimatorListener[0])`（AsyncAnimCallbacks.java:38-43） |
| `thread/LooperExecutor.kt:22`（`handler`） | `com/oplus/basecommon/thread/LooperExecutor.java:9`（`mHandler`） | `private val handler: Handler?`（lib）↔ `private final Handler mHandler`（原厂）。**双方都无并发原语**——构造期一次性写入、之后只读。lib 用 `null` 作为 JVM 单测兜底 |
| `thread/LooperExecutor.kt:31-38`（`execute`/`post`） | `LooperExecutor.java:27-33`（`execute`） | lib 判 `isCurrentThread`（`handler.looper.thread === Thread.currentThread()`）↔ 原厂判 `getHandler().getLooper() == Looper.myLooper()`。**无并发原语**，纯 Looper 线程比较 |
| `thread/LooperExecutor.kt:49-54`（`postAsync`） | `com/android/launcher3/Utilities.java:631-637`（`postAsyncCallback`）被 `AsyncAnimCallbacks.java:120,160` 调用 | `Message.obtain(h) { action() }.apply { isAsynchronous = true }`（lib）↔ 原厂相同（`Utilities.postAsyncCallback` 内部 `Message.obtain(handler, r).setAsynchronous(true).sendMessage`）。**精确复刻** |
| `anim/AsyncAnimCallbacks.kt:76-79`（`getListeners`） | `AsyncAnimCallbacks.java:38-43,81-97`（同款 `getListeners`） | lib 用 `removeAll { null }` + `filterNotNull` 拷快照 ↔ 原厂用反向 `remove(size)` + `toArray(new NullableAnimatorListener[0])`。**保真**（快照语义一致），实现路径不同 |
| `anim/AsyncAnimCallbacks.kt:97-100`（`runOnMainThread`） | `AsyncAnimCallbacks.java:153-160`（`runOnMainThread`） | `if (exec.isCurrentThread) action() else exec.postAsync(action)`（lib）↔ `if (Looper.getMainLooper().isCurrentThread()) runnable.run(); else Utilities.postAsyncCallback(handler, runnable);`（原厂）。**精确复刻** |
| `seq/AnimSeqTimeStamp.kt:13-27` | `com/android/systemui/shared/system/AnimSeqTimeStamp.java:25-28` | `@Volatile private var lastStartAppTime / lastRecentFinishTime / lastRecentStartTime / lastLaunchTaskTime / clock`（lib，4 个 long + 1 个 lambda，**纯 lock-free 读**）↔ `private static long lastStartAppTimeMillis / lastRecentFinishTimeMills / lastRecentStartTimeMills / lastLaunchTaskTimeMills`（原厂，**裸 long，无 volatile**）+ `@JvmStatic public static final synchronized long getTimeGapToLast* / update* / reset*`（原厂：所有读写都加 synchronized） |
| `seq/AnimationSeqHelper.kt:29-36` | `com/oplus/quickstep/utils/AnimationSeqHelper.java:50-55,57-65` | `var seqId = 0L`（lib，**裸 long 无同步**）+ `Handler? handler`（懒创建，**无同步**）↔ 原厂 `private long seqId`（**裸 long 无同步**）+ `private final Handler handler = new Handler(Looper.getMainLooper(), ...)`（**final 一次写，构造期发布**）。**两者一致裸 long**——都假设"seqId 单线程访问" |
| `manager/AnimationFeatureHelper.kt:13-78` | `com/oplus/quickstep/utils/AnimationFeatureHelper.java:52-60` | `private class SyncedVar<T>(initial: T) : ReadWriteProperty` 内部 `@Volatile private var value = initial`，`setValue = synchronized(lock) { this.value = value }`（lib）↔ `private volatile int mAsyncEnable = -1` 等 9 个字段 + 8 个 `private final synchronized void set*Enable/set*Disable/setThreshold/setLimtSize`（原厂）。**粒度对照**：lib 一个锁管全部 7 字段（统一 `lock`）+ 一次 `simulateRemoteUpdate` 用 `synchronized(lock)` 包 6 个赋值；原厂每字段一把锁（8 把）。两个列表字段 OPPO 用 `synchronized(this.m1pxPkgDisableList)` / `synchronized(this.m1pxCardDisableList)` 做"整段重写"保护（`:320, :345`），lib 的 `onePxPkgDisableList / onePxCardDisableList` 现为 `@Volatile` 不可变快照（`:32-36`，60bd048 后），public getter 只读（`:39-41`），`simulateRemoteUpdate` 9 参版（`:47-60`）在 `synchronized(lock)` 内整段替换（`.toList()`）——与原厂 `volatile List<>` + `synchronized(this.m*)` 重写段等效 |
| `control/AnimationController.kt:30-31`（`runningTaskInfo`） | `com/oplus/quickstep/utils/AnimationController.java:83`（`mRunningTask`） | `@Volatile private var runningTaskInfo: Any?`（lib）↔ `private volatile TaskInfo mRunningTask`（原厂）。**精确复刻** |
| `control/AnimationController.kt:33-53`（其他状态字段） | `AnimationController.java:67-87`（`isLandscapeActivity`、`mIsBetweenAppExitTransitionEndAndFinish` 等 12+ 字段） | lib 状态字段（bool 6 个 `:33-38` + listener/maxTime 等 `:40-53`）**全部非 @Volatile**（`isLandScapeGesture`/`isSplitScreenGesture`/`onceGestureProcessingFlag` 等）↔ 原厂对应字段**也全部非 volatile**。**两者一致假设"主线程访问"** |
| `control/AnimationController.kt:26-28`（`recentsAnims/appLaunchAnims/removeTasksMaps`） | `AnimationController.java:89-91`（`mRecentsAnims / mAppLaunchAnims / mRemoveTasksMaps`） | `mutableListOf<CustomRectFSpringAnim>()` + `mutableListOf<RemoteAnimationFactory>()` + `linkedMapOf<Any, Any>()`（lib，**裸 ArrayList/LinkedHashMap 无同步**）↔ `new ArrayList()` / `new LinkedHashMap()`（原厂，**裸集合无同步**）。**一致** |
| `control/DefaultAnimationController.kt:15`（`animStateChangeListeners`） | `com/oplus/quickstep/utils/DefaultAnimationController.java` metadata d2 字段 `mAnimStateChangeListeners` | `mutableListOf<OnAnimStateChangeListener>()`（lib）↔ 应该是 `ArrayList<>`（原厂，未读取文本验证）。**两者一致**：遍历时 `toList()` 拷快照（lib `:27`） |
| `control/TaskStateChangeTimeOutListener.kt:12-46` | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:117-209` 内部类 | `private val handler: Handler?`（lib，**构造时一次写，无同步**）+ `handler?.postDelayed(timeOutOption, duration)`（init）+ `handler?.removeCallbacks(timeOutOption)`（dispose）。lib 还用 `runCatching` 包 looper 访问（JVM 单测兜底）。↔ OPPO `private Handler handler`（`TaskStateHelper.java:118`，**裸引用无同步**）— `init` 时 `handler = OplusExecutors.URGENT_TRANSACTION_EXECUTOR.handler`，`dispose` 时 `handler.removeCallbacks(timeOutOption)` |
| `manager/OplusAnimManager.kt:20-23`（Impl 字段） | `com/oplus/quickstep/utils/OplusAnimManager.java:50-65`（Impl 字段） | `@Volatile private var animationControllerImpl: AnimationController? = null`（lib，60bd048 后 @Volatile + setter @Synchronized `:53-64`）↔ 原厂 Kotlin `Lazy<T>` delegate（`f4.g`，默认 `SYNCHRONIZED`）。并发首次访问双建已消除（见 §③-风险5） |
| `control/AnimationController.kt:220-251`（`delayStartActivityIfNeed`） | `AnimationController.java:608-650`（同款方法） | lib 现为 `if / else if / else if` 互斥 + 末段 dispose/清标志（cdd125e，见 §③-风险7）——旧文「破坏为非互斥」描述已过时 |
| `playback/PendingAnimation.kt:25-31`（`anim / animHolders`） | `com/android/launcher3/anim/PendingAnimation.java:24-29`（同款字段） | `AnimatorSet anim` + `mutableListOf<Holder> animHolders`（lib，**裸集合**）↔ 原厂同款（**裸集合**）。**一致无锁**，靠调用方单线程访问 |
| `playback/AnimatorPlaybackController.kt:32-38`（`anims / childAnimations / endActions`） | `com/android/launcher3/anim/AnimatorPlaybackController.java:28-35,38-44,46-47` | `mutableListOf<Animator> anims` + `Array<Holder>` + `mutableMapOf<String, () -> Unit> endActions`（lib）↔ 原厂同款结构。**一致** |
| `playback/AnimatorPlaybackController.kt:34`（`targetCancelled`） | `AnimatorPlaybackController.java:73-74`（`mTargetCancelled`） | `private var targetCancelled = false`（lib，**非 volatile**）↔ 原厂同款。**一致假设主线程访问** |
| `playback/AnimationSuccessListener.kt:20-33` + `playback/NullableAnimatorListenerAdapter.kt:15`（`cancelled` 实现在父类） | `com/android/launcher3/anim/AnimationSuccessListener.java:7-21` | `protected var cancelled = false`（lib，**非 volatile**）↔ 原厂 `protected boolean mCancelled`，但父类 `ActualEndAnimListener.mCancelled` 自身有 listener 链同步语义。**语义一致** |
| `core/Trace.kt:13-14`（`STACK`，60bd048 后 ThreadLocal per-thread） | `android.os.Trace`（平台类） | `ArrayDeque<String> STACK` + `traceBegin/End` 内**无同步**（lib）↔ 原厂 `Trace.traceBegin/End` native（线程安全由 native 端保证）。**lib 已按线程隔离（60bd048），不再乱** |

---

## ② 保真度评估

### A. 精确复刻（行为可对齐）

| # | 设计点 | 原厂证据 | lib 证据 |
|---|---|---|---|
| 1 | `AsyncValueAnimator.isEnd` AtomicBoolean CAS 门控 | `AsyncValueAnimator.java:31,57,80`（`compareAndSet(false, true)`） | `anim/AsyncValueAnimator.kt:26,35` — 同一 compareAndSet，语义一致 |
| 2 | 异步消息投递（listener 派发穿透 sync-barrier） | `Utilities.java:631-637`（`Message.obtain(h,r).setAsynchronous(true)`） | `thread/LooperExecutor.kt:49-54` 同一 `Message.obtain(h) { action() }.apply { isAsynchronous = true }` |
| 3 | 线程切换协议：`isCurrentThread` → `super.xxx()` 否则 `executor.execute(...)` | `AsyncValueAnimator.java:116-127,130-141,153-164`；`CustomRectFSpringAnim.java:613-621,763-770,888-895` | `anim/AsyncValueAnimator.kt:44-49`（`marshal` 内联 `:47-49`）— 形状一致 |
| 4 | listener 懒删除 + 快照迭代（消除 CME 窗口） | `AsyncAnimCallbacks.java:38-43`（反向 `remove(size)` + `toArray`） | `anim/AsyncAnimCallbacks.kt:76-79`（`removeAll { null }` + `filterNotNull`）— 实现路径不同但语义等效 |
| 5 | `runningTaskInfo` 字段用 `volatile` 跨线程发布 | `AnimationController.java:83`（`private volatile TaskInfo mRunningTask`） | `control/AnimationController.kt:30-31`（`@Volatile private var runningTaskInfo: Any?`） |
| 6 | AnimationFeatureHelper 用 `volatile` 读 + `synchronized` 写的双层结构 | `AnimationFeatureHelper.java:52-60`（9 个 volatile）+ `:99-156`（8 个 `synchronized` setter）+ `:320,345`（列表字段单独 `synchronized(this.m*)`） | `manager/AnimationFeatureHelper.kt:69-78`（SyncedVar：`@Volatile` 读 + `synchronized(lock)` 写）+ 列表快照 `:32-36,47-60`— 锁粒度更粗但语义等效 |
| 7 | AnimationHandler 的 ThreadLocal 单例语义（每线程一份） | vendored core `:13,158-172`；`OplusExecutors.java:170` 证明框架 `android.animation.AnimationHandler` 同样 ThreadLocal | `core/AnimationHandler.kt:133, 140-142`（`threadLocalHandler = ThreadLocal<AnimationHandler>()`） |
| 8 | `LooperExecutor.execute` 同线程内联执行、跨线程 Handler.post | `LooperExecutor.java:27-33`（`getHandler().getLooper() == Looper.myLooper()`） | `thread/LooperExecutor.kt:28-29`（`isCurrentThread` 判 `thread === Thread.currentThread()`） |

### B. 有意简化（lib 注释/文档中明示或合理 demo 化）

| # | 简化内容 | 原厂对应 | lib 取舍理由 |
|---|---|---|---|
| 1 | TickScheduler 用 `ConcurrentLinkedQueue` + `AtomicLong` + `@Volatile` 三件套 | vendored core/dyn `FrameCallbackProvider*` 用裸 ArrayList / 简单长整型 | lib 跨线程（动画线程 + 业务线程都能 add/remove callback）必须用并发容器；原厂回调列表就是 Looper 派发线程私有，**天然串行**。lib 多写了同步层是必须的合理差异 |
| 2 | `ScheduledTickScheduler` / `HandlerTickScheduler` 用 `@Synchronized start/stop` + `@Volatile running` 守护状态 | 原厂 `Choreographer.postFrameCallback` / `handler.postDelayed` 内部由 Looper 串行化，**无显式同步** | lib 的 scheduler 是跨线程可达对象（`addAnimationFrameCallback` 可被任意线程调），需要显式守护 running 标志的可见性 |
| 3 | `AnimationControlThread.instance` 用 `LazyThreadSafetyMode.SYNCHRONIZED` 懒创建 | 原厂 `ANIM_EXECUTOR = new OplusLooperExecutor(...)` 是 `static final`，JVM 类初始化锁保证唯一 | 行为等效（都是"线程安全的一次性构造"）；lib 用 synchronized lazy 写得更显眼 |
| 4 | `AnimationHandler.swapScheduler` / `TickSchedulerHolder.get` 加 `@Synchronized` | 原厂 vendored `setProvider` 由 framework 保证（`AnimationHandler.java:174-176`，框架内部锁） | lib 没有 framework 保证，自加 `@Synchronized` 是必要的 |
| 5 | `AnimSeqTimeStamp` 用 `@Volatile` 字段 + 纯 lock-free 读 | 原厂用 4 个 `static long` 字段 + 所有方法 `synchronized`（**全方法锁**，包括读路径） | lib 锁粒度更细（无锁读 + 顺序写），但要求**写者也在同步语境下更新**——lib 写路径是裸赋值（`seq/AnimSeqTimeStamp.kt:29-43`），**漏锁！** 见 §③-风险 1 |
| 6 | `TaskStateChangeTimeOutListener.handler` 用裸 `Handler?` 引用 | 原厂 `handler` 字段**也无同步**（构造期一次写、之后只读，构造期可见性由 final 字段保证） | lib 的 `handler` **不是 val**（构造时 `mainLooper()?.let(::Handler)`，懒解析，**有 race**：构造器未完成就被另一线程 `dispose()` 可能 NPE）。见 §③-风险 4 |
| 7 | `OplusAnimManager` 的 Impl 字段用裸 `var` 而非 lazy | 原厂用 Kotlin `f4.g` 委托（即 `Lazy<T>`，默认 SYNCHRONIZED） | lib 注释（`manager/OplusAnimManager.kt:19`）自承"简化版应是 t4.b 类型懒加载"，并发首次访问可能创建两个实例 |
| 8 | `Trace.STACK` per-thread deque（60bd048，已修） | 原厂 `android.os.Trace` 是 native 实现，线程安全 | lib `core/Trace.kt:14` 为 `ThreadLocal`——跨线程并发不乱 |
| 9 | `PendingAnimation` / `AnimatorPlaybackController` 的列表 / 数组全部裸集合 | 原厂同样裸集合 | **两者一致**——靠"主线程构造 / 主线程 start / 主线程回调"的纪律 |
| 10 | `AnimationSuccessListener.cancelled` 用 `var`（非 volatile） | 原厂 `mCancelled` 在 `AnimationSuccessListener.mCancelled`，父类 `NullableAnimatorListenerAdapter.mCancelled`，也非 volatile | **一致** |

### C. 遗漏 / 偏差（未在 lib 注释中说明、且影响语义）

| # | 遗漏点 | 原厂证据 | 影响 |
|---|---|---|---|
| 1 | **`AnimSeqTimeStamp` 写路径漏锁（高风险）** | 原厂 4 个 update/reset 方法全 `synchronized`（`AnimSeqTimeStamp.java:31-40,51-58,71-80,91-100` 等 8 个 `@JvmStatic synchronized` 方法），所有读写共享同一把类锁 | lib 4 个字段 `@Volatile` 只能保证**单个 long 写可见性**，但 `updateLastStartAppTime / updateLastRecentFinishTime / ...` 的实现仍是裸 `lastXxxTime = clock()`（`seq/AnimSeqTimeStamp.kt:29-43`），**没在 lock 内**。后果：写者 A 写 `lastStartAppTime` 同时写者 B 写 `lastRecentFinishTime` 没问题（不同字段），但同一字段并发写可能丢更新；读端 `gapTo()` 是 `clock() - timestamp` 多字段读，**可能读到撕裂的快照**——500/300ms 防抖窗口在并发触发下可能误判 |
| 2 | **`AnimSeqTimeStamp` 整方法 synchronized vs 仅字段 @Volatile：粒度反向了** | 原厂：`@JvmStatic synchronized` 方法（类对象作 monitor）— 读路径也加锁，**简单粗暴但绝对正确** | lib：无锁读路径 + 无锁写路径（`seq/AnimSeqTimeStamp.kt:13-27, 29-43`）— 性能更好但正确性降级。原厂能扛住 14 路并发触发（多模块共享时间戳），lib 在并发密集场景下可能产生"时间戳回退"或"双触发都判 300ms 内" |
| 3 | **`AnimationController.delayStartActivityIfNeed` 把互斥结构破坏为顺序 if**（高风险，跨线程时序错位） | 原厂 `if / else if / else if` 三层互斥（`:608, :627, :645`），第一层 listener 存在但条件不满足时**穿透到清理段返回 false** | lib 三个顺序 `if`（`:177, :186, :193`），第一层不满足会继续试第二、三层；叠加 review 03 §3-b 子项（漏 `isTablet()` / 漏 `isSpecialAppScene(intent)` / 时间窗 vs 运行态判定差异），最终落点也不会 dispose listener、清 Between 标志——状态残留可能影响下一次进出场。**这是 review 03 已记录的非并发原语相关偏差** |
| 4 | **`TaskStateChangeTimeOutListener.handler` 字段不是 `val`、构造期 lazy 解析 + 无可见性同步（中风险）** | 原厂 `private Handler handler`（裸引用但**构造期一次性赋值 + 之后只读**，final 字段语义保证可见性） | lib `private val handler: Handler?`（`control/TaskStateChangeTimeOutListener.kt:24`）现已在**字段声明处**默认初始化（0e8a472）——本条风险已修，正文描述为修复前状态——`val` 保证"该字段只赋一次"但不保证"其他线程看得到已构造的对象"。并发场景下：构造器还在跑 `init { handler?.postDelayed(...) }` 时，另一线程若持有引用并调 `dispose()`，可能 NPE。**真实场景下主线程构造 + UI 线程 dispose 无问题**（Handler 同 Looper 内可见），JVM 单测时构造期更短，**有微小 race window** |
| 5 | **`OplusAnimManager.animationControllerImpl/SeqHelperImpl` 用裸 `var` 无同步（中风险）** | 原厂用 Kotlin `Lazy<T>` 委托（默认 `LazyThreadSafetyMode.SYNCHRONIZED`）— 多个线程同时首次访问只会创建一个实例 | lib 字段已 @Volatile（`manager/OplusAnimManager.kt:20-23`）且 setter @Synchronized（`:53-64`，60bd048）— 本条风险已修，正文描述为修复前状态。生产代码多线程访问少见，但**理论 race window 存在** |
| 6 | **`AnimationFeatureHelper` 两个列表字段无任何保护（中风险）** | 原厂 `m1pxPkgDisableList` / `m1pxCardDisableList` 是 `volatile List<>`，**且 `updateRusConfig` 写入时 `synchronized(this.m1pxPkgDisableList)` / `synchronized(this.m1pxCardDisableList)`**（`:320, :345`）。**读路径直接返回 volatile 引用**（`:376, :380`），靠"调用方只读不写"约定 | lib 现为 `@Volatile` 不可变快照（`manager/AnimationFeatureHelper.kt:32-36`）+ `synchronized(lock)` 内整段 `.toList()` 替换（`:47-60`），public getter 只读——已对齐原厂"引用替换原子 + 段内互斥"（60bd048），本条风险已修 |
| 7 | **`AnimationFeatureHelper.simulateRemoteUpdate` 写锁粒度**（低风险，但与原厂对比粒度反向） | 原厂每字段一个 `synchronized` setter，**两字段之间不需要互斥** | lib 7 字段共享同一 `lock`，**全部写入必须串行**。功能正确（更粗的锁 = 更安全），但 demo 场景下没必要 |
| 8 | **`AsyncAnimCallbacks.animListeners` 与 OPPO 同款裸 ArrayList 无锁，但 lib 的 remove 是 `mutableListOf` 而非 ArrayList**（低风险） | OPPO `ArrayList<NullableAnimatorListener> mAnimListeners`（`AsyncAnimCallbacks.java:34`）— `addListeners` / `removeListener` / `getListeners` 全部裸 ArrayList 操作，**靠"主线程 add + 迭代在主线程派发"的纪律**。**原厂自己也有 race 隐患**，不是 bug | lib `mutableListOf<NullableAnimatorListener?>()`（`anim/AsyncAnimCallbacks.kt:33`）— 同款裸集合 + 同款纪律。lib 比原厂**多一层快照保护**（`removeAll { null }` 后 `filterNotNull` 拷快照），但**写者并发仍可能 CME**——这是**与原厂对齐的合理行为**，不是 lib 的 bug |
| 9 | **`Trace.STACK` 用裸 ArrayDeque 多线程 race（低风险）** | 原厂 `android.os.Trace` 是 native，线程安全 | lib `core/Trace.kt:14` 的 STACK 已是 ThreadLocal（60bd048），此条已修 |
| 10 | **`TaskStateHelper.TaskStateChangeTimeOutListener` 用 `URGENT_TRANSACTION_EXECUTOR` 而非 Main**（已知但非并发原语差异） | `TaskStateHelper.java:130` — 超时回调跑在 `-8` 优先级线程 | lib 用 Main Looper — 超时回调跑主线程，**与原厂线程模型不一致**（review 03 §2.2-5 已记） |
| 11 | **`AnimationController` 其他 12+ 状态字段（`isLandScapeGesture` 等）全裸字段、无 @Volatile** | 原厂同样裸字段，**两者一致假设主线程访问**——但 OPPO 还有 `checkMainThread()`（`:233`）做纪律校验，lib 无此检查（`control/AnimationController.kt`） | **与原厂行为对齐**（都靠纪律），但**lib 比原厂少一层兜底**——业务在非主线程调 `setOnAppExit` 时原厂会 throw，lib 静默改状态 |

---

## ③ 行为差异风险点（按严重度排序）

### 风险 1（高）：`AnimSeqTimeStamp` 多字段并发读写的撕裂快照

> ⚠️未修复（撕裂快照仅在真实多线程写场景下出现；60bd048 仅补 4 个 reset 方法 + clock 注入；写路径仍裸赋值，未与原厂"全方法 synchronized"对齐。JVM 单测下不触发）

**位置**：`seq/AnimSeqTimeStamp.kt:13-27`（4 个 @Volatile 字段 + clock `:26-27`）、`:29-43`（4 个 update 裸赋值）、`:45-67`（4 个 reset + resetAllForTest）

**问题**：
- 原厂：4 个时间戳字段 + 8 个 `static synchronized` 方法（`@JvmStatic synchronized`），读写共享类对象 monitor，**绝对原子**。
- lib：4 个字段标 `@Volatile`（仅单字段写可见性），但 `updateLastStartAppTime / updateLastRecentFinishTime` 等 4 个写方法**裸赋值无锁**。
- `gapTo(timestamp)` 读路径（`seq/AnimSeqTimeStamp.kt:69-70`）**读两个 volatile 字段**（`timestamp` + `clock()`）—— 实际只读一个字段，`clock()` 是当前时间无 race；但**两次连续读不同字段**（如 `canFinishRecent` 同时查 `lastRecentFinishTime`，`canInterceptGesture` 查 `lastStartAppTime`）如果两个写线程并发写，**可能读到"刚跨过 500ms 阈值又回退"** 的撕裂状态。

**实际触发场景**：
- 启动 app 后立即上滑（`updateLastStartAppTime` 在 `onAppStart`，`canInterceptGesture` 在 gesture 输入回调查时间窗）—— 两个线程并发更新。
- recents 关闭期间同时上滑手势 — `updateLastRecentFinishTime` + gesture 处理并发。

**影响**：
- 500/300ms 防抖窗口误判：可能"刚结束过 recents（500ms 内）"和"刚启动过 app（300ms 内）"两个判定在并发写窗口下被撕裂，导致 `delayFinishRecents` 该延迟的不延迟、或不该延迟的延迟。
- 不导致功能崩溃，但**可能导致手势/启动时序错位**。

**修复**：把 `updateLastXxxTime` 4 个方法包进 `synchronized(this)`，与读路径共享 monitor——与原厂"全方法 synchronized"对齐。代价：每个 update 多一次 monitor enter/exit（纳秒级，可忽略）。

---

### 风险 2（已修复）：`AnimationFeatureHelper` 两个禁用列表已获 volatile 快照保护

> **✅已修复（60bd048 后：两列表改为 `@Volatile` 不可变快照 + `simulateRemoteUpdate` 在 `synchronized(lock)` 内整段 `.toList()` 替换；public getter 只读不可外改——原「裸 mutableList 无保护」已不存在；v1 的「✔️保持简化/恒空」判定前提亦已过时）**

**位置**：`manager/AnimationFeatureHelper.kt:32-36`（`@Volatile` 快照字段）、`:39-41`（只读 getter）、`:47-60`（9 参 `simulateRemoteUpdate` 整段替换）

**现状（v2 独立复核）**：
- lib 现为 `@Volatile private var onePxPkgDisableSnapshot: List<String> = emptyList()` / `onePxCardDisableSnapshot: List<Int> = emptyList()`（`:32-36`）；公开 getter（`:39-41`）返回不可变 List——业务拿不到 mutable 引用，`.add()/clear()` 外改面不存在；
- `simulateRemoteUpdate`（9 参版 `:47-60`）在 `synchronized(lock)` 内写 7 个标量 + 对两个列表 `.toList()` **整体替换引用**（null 入参保持旧值）——与原厂 `volatile List<>` + `synchronized(this.m1pxPkgDisableList)`/`synchronized(this.m1pxCardDisableList)` 重写段（`:320, :345`）语义一致（引用替换原子、段内互斥）；
- 原厂证据不变：`AnimationFeatureHelper.java:52-60`（volatile 字段群）、`:320, :345`（列表 synchronized 段重写）、`:376, :380`（读路径返回 volatile 引用）。

**残余差异**：仅锁粒度——lib 一个 `lock` 管标量 + 列表，原厂分字段锁；功能正确，无新增并发源（锁粒度判定维持 §③-风险6）。

---

### 风险 3（中）：`AnimationController` 状态字段全部非 volatile、无 `checkMainThread` 兜底

> **✔️保持简化（demo 全主线程纪律调用、无触发；lib 定位演示库非真实 launcher；checkMainThread 约 20 行仅防御性断言）**

**位置**：`control/AnimationController.kt:33-53`（bool 状态字段 + listener/maxTime/callback 字段，均非 @Volatile）+ 全方法（无 `checkMainThread`）

**问题**：
- 原厂：12 个字段（`mIsBetweenAppExitTransitionEndAndFinish / mIsLandScapeGesture / mIsSplitScreenGesture / mOnceGestureProcessing` 等）**裸字段无 volatile**，但 `private final boolean checkMainThread()`（`:233`）在每个 mutator / 关键 accessor 前调用——非主线程直接抛异常。**靠"业务方必须主线程调用"的纪律 + 显式断言**。
- lib：字段同样裸无 volatile（bool 现 6 个 `:33-38`，另有 listener/maxTime/callback 等 `:40-53`），**且无任何 checkMainThread 检查**。`setOnAppExit / setOnceGestureProcessing / setAppToOverviewContinuationState` 等 mutator（现 `control/AnimationController.kt:204-216`）无线程约束。

**实际触发场景**：
- 当前 demo 全在主线程调——无问题。
- 真实业务中某些 mutator 可能从 binder 回调 / UX_TASK_EXECUTOR 回调触发。原厂会抛 `IllegalStateException("Not on main thread")`，lib 静默写入**撕裂的状态**（一个线程写 `isLandScapeGesture = true`，另一个线程在同一 CPU 缓存行写 `isSplitScreenGesture = false`，可能短暂读到 `true/false` 组合的非法中间态）。

**影响**：
- 当前 demo 没问题。
- lib 用于真实 launcher 时将是潜在崩溃源（业务依赖状态机 12 个 boolean 的组合，撕裂态触发错误状态转移）。

**修复**：从 `DefaultAnimationController` 基类抽 `checkMainThread()`，所有 mutator 入口调用；非主线程 `throw IllegalStateException`。20 行改动。

---

### 风险 4（中）：`TaskStateChangeTimeOutListener.handler` 字段 lazy 解析 + 无 volatile

> **✅已修复（handler 已改为字段声明处默认初始化——final 字段语义生效；mainLooper() 访问由 runCatching 兜底（0e8a472））**

**位置**：`control/TaskStateChangeTimeOutListener.kt:24`（handler 字段声明处默认初始化，0e8a472）+ `:30-32`（init）+ `:41-43`（dispose）

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

> **✅已修复（60bd048：interruptionEnabled setter @Synchronized 后并发首访双建消除；Impl 字段仅经 init（类初始化锁）与同步 setter 写；toggle-disable 语义本就不适用 by lazy）**

**位置**：`manager/OplusAnimManager.kt:20-23`（@Volatile 字段）

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

> **✔️保持简化（共享一把锁功能正确，仅并发写吞吐略降；demo 单线程写，细粒度锁无收益）**

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

> **✅已修复（cdd125e：已改 if/else-if/else-if 互斥 + 末段 dispose/清标志清理段——当前 AnimationController.kt 即该形态；并发放大点消除）**

**位置**：`control/AnimationController.kt:220-251`

**问题**：lib 三个顺序 `if`，原厂 `if / else if / else if` 互斥。review 03 §3-b 已记录，**非并发原语问题**但**并发触发下放大**：
- 第一层 listener 存在但条件不满足时，原厂直接穿透到清理段返回 false，**dispose listener + 清 Between 标志**。
- lib 顺序 if 会继续试第二、三层——可能返回 true 把 action 挂起，**业务方以为进入特殊场景但实际进入第二层超时窗口**。并发触发下第一层 dispose 没执行、listener 残留。

**修复**：改回 `if / else if / else if` + 最终清理段。已在 review 03 §4.1 第 4 条列。

---

### 风险 8（低）：`AsyncAnimCallbacks.animListeners` 写者并发 CME

> **✔️保持简化（与原厂裸 ArrayList + 主线程纪律对齐；lib 已多一层快照保护；CopyOnWriteArrayList 属超原厂增强，demo 无触发）**

**位置**：`anim/AsyncAnimCallbacks.kt:33`（声明）+ `:37-39`（addListener）+ `:41-44`（removeListener 懒删除）+ `:76-79`（`getListeners` 快照迭代）

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

> **❌已过期（215ecb5：ScheduledTickScheduler/HandlerTickScheduler 已删，只剩 ChoreographerTickScheduler——本条讨论的并发原语组合已随类删除）**

**位置**：（两实现已删 215ecb5）现 `core/ChoreographerTickScheduler.kt:24-31`（并发容器 + @Volatile）、`:66-76`（@Synchronized start/stop）

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

> **✔️保持简化（0e8a472 已统一 runCatching.getOrNull 兜底；文档自身修复建议即"保留"——JVM 单测便利 > 设备 NPE 风险）**

**位置**：`thread/LooperExecutor.kt:22, 31-38`（handler null → 就地执行）、`thread/Executors.kt:20, 25-26`（mainHandlerOrNull runCatching）、`thread/AnimationControlThread.kt:70`（runCatching setThreadPriority）、`control/TaskStateChangeTimeOutListener.kt:45-46`（mainLooper runCatching）

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
| 1 | ⚠️未修复（同 §③-风险1：写路径仍裸赋值，未对齐原厂"全方法 synchronized"——约 8 行；60bd048 仅补 4 reset + clock 注入；demo 单线程写不触发） — **修 `AnimSeqTimeStamp` 写路径加 `synchronized(this)`**（`seq/AnimSeqTimeStamp.kt:29-43`） | 与原厂 `@JvmStatic synchronized` 模式对齐；消除多字段并发写撕裂快照；500/300ms 防抖窗口误判修复 | 4 行（每个方法体外包 `synchronized(this)`） |
| 2 | ✅已修复（60bd048 后：`@Volatile` 快照 + `synchronized(lock)` 整段替换落地，见 §③-风险2） — ~~列表字段加 volatile + 写时拷新 ArrayList~~（现 `manager/AnimationFeatureHelper.kt:32-36, 47-60`） | 与原厂 `volatile List<>` + `synchronized(this.m*)` 段模式对齐；为未来扩展留安全基础 | 10 行 |
| 3 | ✔️保持简化（同风险3：demo 主线程纪律；20 行防御断言非必需） — **`AnimationController` 加 `checkMainThread()` 兜底**（基类或 Impl 入口） | 与原厂 `private final boolean checkMainThread()`（`:233`）对齐；非主线程访问状态机字段会 throw，避免撕裂态 | 20 行 |
| 4 | ✅已修复（60bd048：setter @Synchronized 后并发 race 消除；by lazy 与 disable-toggle 语义冲突） — **`OplusAnimManager.Impl` 字段改 `by lazy(SYNCHRONIZED)`**（`:11-12`） | 与原厂 Kotlin `Lazy<T>` 委托对齐；消除 demo 8 / 多线程切换 race | 5 行 |
| 5 | ✅已修复（0e8a472：handler 已字段声明处默认初始化） — **`TaskStateChangeTimeOutListener.handler` 字段默认初始化而非 init 块内赋值**（`:24-26`） | 让 Kotlin `val` 的 final 字段语义真正生效；构造期 lazy 解析变字段默认初始 | 5 行 |
| 6 | ✔️保持简化（同风险6：共享锁功能正确） — **`AnimationFeatureHelper.SyncedVar` 每字段独立 lock**（`:46-55`） | 与原厂"每字段独立 monitor"对齐；保留读多写少场景的并发吞吐 | 10 行（构造器传 `Any()` 而非共享 lock） |

### 4.2 建议保持简化的

| # | 保留简化 | 理由 |
|---|---|---|
| 1 | ❌已过期（215ecb5：类已删，三件套不再存在） — **TickScheduler 用 `ConcurrentLinkedQueue + AtomicLong + @Volatile + @Synchronized` 三件套** | lib 跨线程可达（任意线程都能 addFrameCallback），必须显式守护；原厂 Looper 派发线程私有 = 天然串行。原厂"无并发原语"是 Looper 派发的副产品，**不能搬到 lib** |
| 2 | ✔️保持简化（scheduler 可替换性仍需 @Synchronized 守护；现仅剩 ChoreographerTickScheduler 实现） — **`AnimationHandler.swapScheduler` / `TickSchedulerHolder.get` 加 `@Synchronized`** | 原厂 framework `setProvider` 内部有锁；lib 没有 framework 兜底，自加锁必要 |
| 3 | ✔️保持简化（同风险8：与原厂纪律对齐） — **`AsyncAnimCallbacks.animListeners` 裸 mutableListOf（与原厂 ArrayList 对齐）** | 双方都靠"主线程 add + 主线程 dispatch 迭代"的纪律；改 `CopyOnWriteArrayList` 是 lib 增强、原厂没做，**超原厂** |
| 4 | ⚠️未修复（前提是 4.1-1 写锁落地才成立；当前读写均无锁——见风险1） — **`AnimSeqTimeStamp` 字段 `@Volatile` 读路径** | 与原厂 `@JvmStatic synchronized` 读路径对比，**lib 无锁读性能更好**（仅写加锁即对齐，参见 4.1-1） |
| 5 | ✔️保持简化（与原厂一致，主线程构造+访问） — **`PendingAnimation` / `AnimatorPlaybackController` 的列表/数组全部裸集合** | 与原厂一致假设"主线程构造 + 主线程访问" |
| 6 | ✔️保持简化（cancel/end 同线程调用，无 race） — **`AnimationSuccessListener.cancelled` 非 volatile** | 与原厂一致（父链上 `ActualEndAnimListener.mCancelled` 也非 volatile）；cancel/end 在同一线程调用，无 race |
| 7 | ✔️保持简化（executeBlockWait 是原厂 ANR 风险，不回移正确） — **`OplusLooperExecutor` 四扩展（executeAtFront / executeWithUx / executeBlockWait / executeDelay）不移植** | 依赖 LauncherBooster 私有 API；executeBlockWait 是 v4 §9.3 点名 ANR 风险——**原厂自己也不该这么写** |
| 8 | ✅已修复（60bd048：Trace.STACK 改 ThreadLocal per-thread deque——本条"裸 ArrayDeque 保留"已无对象） — **`Trace.STACK` 裸 ArrayDeque 多线程 race** | demo 日志用，线程安全无价值 |
| 9 | ✔️保持简化（0e8a472 runCatching 收口） — **JVM 单测兜底（handler null → 就地执行）** | 单测便利 > 真实设备 NPE 风险 |
| 10 | ✅已修复（cdd125e：else-if 互斥 + 清理段已落地——对应 review 03 §4.1-4 项执行完毕） — **`AnimationController.delayStartActivityIfNeed` 三层非互斥的修复归到 review 03 §4.1-4** | 已在之前 review 列；本区域专注并发原语，行为差异归原 review |

---

## 附：关键证据速查

| 论断 | 证据（lib 路径 / 原厂 文件:行） |
|---|---|
| lib `AsyncValueAnimator.isEnd` AtomicBoolean CAS | `anim/AsyncValueAnimator.kt:26,35,30-40` ↔ `com/android/quickstep/util/animation/AsyncValueAnimator.java:31,57,80` |
| lib `asyncAnimCallbacks.animListeners` 裸 mutableList | `anim/AsyncAnimCallbacks.kt:33` ↔ `AsyncAnimCallbacks.java:34`（`ArrayList mAnimListeners`） |
| lib `AsyncAnimCallbacks.getListeners` 快照迭代 | `anim/AsyncAnimCallbacks.kt:76-79` ↔ `AsyncAnimCallbacks.java:38-43` |
| lib 异步消息投递 `Message.setAsynchronous(true)` | `thread/LooperExecutor.kt:49-54` ↔ `Utilities.java:631-637`（被 `AsyncAnimCallbacks.java:120,160` 调用） |
| lib `AnimSeqTimeStamp` @Volatile 字段 + 无锁读写 | `seq/AnimSeqTimeStamp.kt:13-27,29-43` ↔ `AnimSeqTimeStamp.java:25-28, 31-138`（8 个 `@JvmStatic synchronized` 方法） |
| lib `AnimationFeatureHelper.SyncedVar` 共享 lock | `manager/AnimationFeatureHelper.kt:15,47-66,69-78` ↔ `AnimationFeatureHelper.java:52-60, 99-156`（每字段独立 synchronized） |
| lib `AnimationFeatureHelper.onePxPkgDisableList` @Volatile 快照（60bd048） | `manager/AnimationFeatureHelper.kt:32-36,39-41` ↔ `AnimationFeatureHelper.java:56-57`（`volatile List<>`）+ `:320,345`（`synchronized(this.m*)` 写段） |
| lib `AnimationController.runningTaskInfo` @Volatile | `control/AnimationController.kt:30-31` ↔ `AnimationController.java:83`（`private volatile TaskInfo mRunningTask`） |
| lib `AnimationController` 状态字段全裸 | `control/AnimationController.kt:33-38` ↔ `AnimationController.java:67-87`（同款裸字段，原厂另有 `checkMainThread()` `:233`） |
| lib `OplusAnimManager.Impl` @Volatile + @Synchronized setter（60bd048） | `manager/OplusAnimManager.kt:20-23,53-64` ↔ `OplusAnimManager.java`（`f4.g` Kotlin `Lazy<T>` 委托） |
| lib `TaskStateChangeTimeOutListener.handler` 字段声明处默认初始化（0e8a472） | `control/TaskStateChangeTimeOutListener.kt:24` ↔ `TaskStateHelper.java:118,130`（构造期一次写 final） |
| lib `AnimationControlThread.instance` lazy SYNCHRONIZED | `thread/AnimationControlThread.kt:86-88` ↔ `OplusExecutors.java:95`（`static final` JVM 类初始化锁） |
| （215ecb5 已删）lib 现仅 `ChoreographerTickScheduler`：`@Synchronized start/stop` + `@Volatile running` | `core/ChoreographerTickScheduler.kt:24-31, 66-76` ↔ vendored `FrameCallbackProvider14/16`（Looper 派发线程串行，**无对应原语**） |
| lib `AnimationHandler.swapScheduler` / `TickSchedulerHolder.get` `@Synchronized` | `core/AnimationHandler.kt:111-119, 122-127` ↔ vendored `AnimationHandler.setProvider`（framework 内部锁） |
| lib `AnimationHandler` ThreadLocal 单例 | `core/AnimationHandler.kt:133, 140-142` ↔ vendored core `:13,158-172`；`OplusExecutors.java:170`（框架 `android.animation.AnimationHandler` 同样 ThreadLocal） |
| 原厂"裸 ArrayList 无锁 + 主线程纪律"模式 | `AsyncAnimCallbacks.java:34-43`（mAnimListeners）、`AnimationController.java:67-87`（状态字段）、`AnimationSeqHelper.java:50-55`（seqId）—— **整套设计哲学是"靠纪律而非同步"** |
| 原厂"全方法 synchronized"模式（与 lib 粒度反向） | `AnimSeqTimeStamp.java:31-138`（8 个 `@JvmStatic synchronized` 方法）—— **写少读多也要加锁，性能保守** |
| 原厂"synchronized 段内重写 List"模式 | `AnimationFeatureHelper.java:320,345`（`synchronized(this.m1pxPkgDisableList) { clear(); add(); }`）—— **写时拷贝语义，但 OPPO 偷懒直接重写** |


## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **0e8a472** — TaskStateChangeTimeOutListener.mainLooper、Executors.mainHandlerOrNull 用 runCatching
- **60bd048** — OplusAnimManager.interruptionEnabled @Synchronized 守 setter 并发切换；AnimSeqTimeStamp 4 个 @Volatile 字段仍是裸写但本批不降级



逐条判定（批次 1 逐项状态，标注位置见正文）：
- **§③-风险1（AnimSeqTimeStamp 撕裂）** — ⚠️未修复（沿用既有标注：写路径裸赋值；60bd048 补 4 reset + clock，未对齐全方法 synchronized）
- **§③-风险2（feature helper 列表）** — ✔️保持简化（恒空、只读 List、无写路径）
- **§③-风险3（checkMainThread）** — ✔️保持简化（demo 主线程纪律）
- **§③-风险4（handler 字段）** — ✅已修复（0e8a472 字段默认初始化）
- **§③-风险5（OplusAnimManager Impl）** — ✅已修复（60bd048 @Synchronized setter）
- **§③-风险6（SyncedVar 锁粒度）** — ✔️保持简化
- **§③-风险7（delayStartActivityIfNeed 互斥）** — ✅已修复（cdd125e else-if + 清理段）
- **§③-风险8（animListeners CME）** — ✔️保持简化（与原厂对齐）
- **§③-风险9（scheduler 复合原语）** — ❌已过期（215ecb5 类已删）
- **§③-风险10（JVM 兜底）** — ✔️保持简化（0e8a472 runCatching 收口）
- **§④-4.1 表** — 1/4/5 ✅（60bd048、cdd125e、0e8a472），2/3/6 ✔️，见正文标注
- **§④-4.2 表** — 8/10 ✅（60bd048/cdd125e），1 ❌已过期（215ecb5），4 ⚠️未修复（依赖 4.1-1），其余 ✔️
其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。

## 复核记录 v2（2026-09-09，独立逐条复核）

本批不信任既有 ✅/✔️/⚠️/❌ 标记，逐条对照当前 lib 代码亲自复核（包重组后：core/anim→core、core/scheduler→core（scheduler 两实现已删）、launcher/animthread→thread、launcher/async→anim（LooperExecutor→thread）、launcher/controller→control、launcher/manager→manager、launcher/seq→seq、launcher/feature→manager、pending→playback、util→core）。仅改本文档。

- **复核条目总数**：26（§③ 风险 10 + §④-4.1 表 6 + §④-4.2 表 10）
- **结论不变**：24
- **修正**：2
  1. §③-风险2：✔️保持简化（列表恒空、无写入路径）→ ✅已修复（`manager/AnimationFeatureHelper.kt:32-36` 两个列表现为 `@Volatile` 不可变快照，`:39-41` 只读 getter，9 参 `simulateRemoteUpdate` `:47-60` 在 `synchronized(lock)` 内整段 `.toList()` 替换——原「裸 mutableList 无保护 / 业务可 .add() 并发 CME」已不存在，等效原厂 `volatile List<>` + synchronized 段重写）
  2. §④-4.1 表行2：✔️保持简化 → ✅已修复（同风险2，随 60bd048 列表快照化落地）
- **描述 / 证据刷新（结论不变）**：
  - §① 表 / §②A / §②C / 附证据表全部 lib 路径按新包结构刷新并更新行号（代表性：`core/AnimationHandler.kt:133,140-142` ThreadLocal、`thread/AnimationControlThread.kt:86-88` lazy instance、`anim/AsyncValueAnimator.kt:26,35` isEnd CAS、`anim/AsyncAnimCallbacks.kt:33,76-79,97-100`、`thread/LooperExecutor.kt:22,28-29,49-54` postAsync、`seq/AnimSeqTimeStamp.kt:13-27,29-43`、`control/AnimationController.kt:30-31,33-53,220-251`、`control/DefaultAnimationController.kt:15,27`、`control/TaskStateChangeTimeOutListener.kt:24`、`manager/OplusAnimManager.kt:20-23,53-64`、`playback/PendingAnimation.kt:25-31`、`playback/AnimationSuccessListener.kt:20-33`+`NullableAnimatorListenerAdapter.kt:15`、`core/Trace.kt:13-14`）
  - §① 行21/22 与 §③-风险9：ScheduledTickScheduler / HandlerTickScheduler 标注「已删（215ecb5）」，现状为 `core/ChoreographerTickScheduler.kt:24-31,66-76`
  - §③ 风险 1/3/4/5/7/8/10 的位置行号刷新；风险 7（delayStartActivityIfNeed）实证 if/else-if/else-if + 末段清理（`control/AnimationController.kt:220-251`，cdd125e）；风险 4 handler 实证字段声明处默认初始化（0e8a472）
  - 批次 1 复核记录中「§③-风险2 — ✔️保持简化」旧判定以本节为准（已翻转 ✅已修复）
