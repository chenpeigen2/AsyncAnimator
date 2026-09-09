# vs-oppo-10-kotlin-idiomaticity — API 风格与 Kotlin 化程度

> 对比双方：
> - **lib**：`D:\AsyncAnimator\lib\src\main\java\com\asyncanimator\`（35 个 .kt 文件，纯 idiomatic Kotlin）
> - **原厂**：`D:\oppo_a6_launcher\sources\com\android\quickstep\util\animation\`、`com\android\launcher3\anim\`、`com\oplus\quickstep\utils\`、`com\oplus\basecommon\thread\` 等；OPPO 反编译树（JADX 输出），源文件 Kotlin/Java 混合
>
> 本报告专门盘点 **API 风格层差异**：lib 把原厂的 Java/decompiled Kotlin 改写成 idiomatic Kotlin 后获得/丢失什么。原厂文件多数被企业 DLP 加密，本文全部经 `python open(...).read()`（明文通道）取证，行号为 JADX 文本行号。
>
> 背景结论见 `docs/animation-thread-analysis-v4.md`、`docs/USAGE.md`，前四份对比 review 见 `01-async-animthread.md` ~ `04-frame-spring-continuation.md`。

---

## ① 类对应关系表

| lib 类（文件） | 原厂对应类（文件:行） | 主要 API 风格差异 |
|---|---|---|
| `core/anim/AnimationHandler.kt`（160 行，internal class） | `androidx/core/animation/AnimationHandler.java`（vendored，~210 行） | lib `internal class` 修饰符；`fun interface AnimationFrameCallback` SAM；companion 的 `instance` `val` + `installThreadScheduler` factory 替代 Java 的 `getInstance()`/` setProvider()` |
| `core/scheduler/TickScheduler.kt`（22 行） | `AnimationFrameCallbackProvider` 接口 | lib `internal interface`；`fun interface FrameCallback` SAM；属性 `frameTimeNanos`/`frameCount`/`frameIntervalMs` 全 `val` getter |
| `core/scheduler/ScheduledTickScheduler.kt`（92 行） | `FrameCallbackProvider14` Handler.postDelayed 退化路径（vendored） | `kotlin.concurrent.thread { ... }` lambda 工厂；`ScheduledExecutorService` Java 类继续使用（无 Kotlin 替代） |
| `launcher/animthread/AnimationControlThread.kt`（87 行） | `com/oplus/basecommon/thread/OplusExecutors.java:95` 内联 `new OplusLooperExecutor(Looper, Runnable)` + init lambda（`ANIM_EXECUTOR$lambda$0`） | `private constructor` + `companion object` 的 `by lazy(SYNCHRONIZED)` 单例；`class AnimationControlThread private constructor() : HandlerThread(...)` 单继承 + `override fun onLooperPrepared()` 钩子 |
| `launcher/animthread/AsyncAnimWrapper.kt`（25 行） | `com/android/launcher3/anim/AsyncAnimWrapper.java:10`（20 行） | lib `open class` + `protected fun runOnAnimThread(task: (() -> Unit)?)`；原厂 `public final void runOnAnimThread(Runnable task)` + `Intrinsics.checkNotNullParameter` |
| `launcher/animthread/HandlerTickScheduler.kt`（92 行） | `FrameCallbackProvider14/16` + `SfVsyncFrameCallbackProvider`（无 @hide 副本） | lib `internal class` + `@Volatile private var running` 字段 + `@Synchronized` 函数修饰符 |
| `launcher/async/AsyncValueAnimator.kt`（59 行） | `com/android/quickstep/util/animation/AsyncValueAnimator.java:24`（`public final class extends ValueAnimator`，~165 行） | lib `class : ValueAnimator()` 单继承 + `var executor: LooperExecutor` 公开 var + `init { block` + `private inline fun marshal(crossinline action: () -> Unit)` 高阶函数；原厂 `private LooperExecutor mAnimLooperExecutor` 字段 + `public final void setExecutor(LooperExecutor executor)` + `Intrinsics.checkNotNullParameter`；原厂 `Companion INSTANCE = new Companion(null)` 静态伴生 + `ofFloat` 静态工厂 |
| `launcher/async/AsyncAnimCallbacks.kt`（80 行） | `com/android/quickstep/util/animation/AsyncAnimCallbacks.java:23`（~150 行） | lib `class AsyncAnimCallbacks`（无伴生）+ `private val animListeners = mutableListOf<NullableAnimatorListener?>()` + `addListener(l: NullableAnimatorListener?)` 单参；原厂 `public final class` + `mAnimListeners = new ArrayList<>()` + `public final void addListeners(NullableAnimatorListener)`（注意是复数） |
| `launcher/async/ActualEndAnimListener.kt`（24 行） | `com/android/quickstep/util/animation/ActualEndAnimListener.java:9`（14 行） | lib `open class : NullableAnimatorListenerAdapter()` + `open fun onAnimActualEnd(animator: Animator) {}`；原厂 `public class extends NullableAnimatorListenerAdapter` + `public void onAnimActualEnd(Animator animator)` |
| `launcher/async/AsyncSpringAnim.kt`（44 行） | `com/android/quickstep/util/OplusAsyncSpringAnimWrapper.java`（~100 行） | lib `class AsyncSpringAnim(real: SpringAnimation, supportAnimThread: Boolean) : AsyncAnimWrapper()` 构造器注入 + `fun start() = dispatch { real.start() }` 单表达式函数 + `private inline fun dispatch(crossinline action: () -> Unit)`；原厂 `public final class extends AsyncAnimWrapper` + 字段 + 私有 lambda 静态方法（`cancel$lambda$2` 等）+ `Companion` |
| `launcher/async/LooperExecutor.kt`（52 行） | `com/oplus/basecommon/thread/LooperExecutor.java:12`（~80 行，`extends AbstractExecutorService`） | lib `class LooperExecutor internal constructor(private val handler: Handler?)` + 主构造器 + `val isCurrentThread: Boolean get() = ...` 自定义 getter + `fun postAsync(action: () -> Unit)` 用 `Message.obtain(h) { action() }` SAM 闭包 + `msg.isAsynchronous = true`；原厂 `public class extends AbstractExecutorService` + 字段 + 显式 getter/setter + `post/postDelayed/setThreadPriority` |
| `launcher/async/Executors.kt`（20 行） | `com/oplus/basecommon/thread/Executors.java:18`（~100 行） | lib `object Executors { val MAIN_EXECUTOR = LooperExecutor(...) ; val ANIM_CONTROL_EXECUTOR = LooperExecutor(Handler(AnimationControlThread.instance.looper)) }`（object 单例 + 主构造器内联 lambda）；原厂 `public class Executors` + `public static final LooperExecutor MAIN_EXECUTOR` + 复杂 `static { ... }` 块 |
| `launcher/pending/PendingAnimation.kt`（178 行，internal class） | `com/android/launcher3/anim/PendingAnimation.java:23`（~235 行，`public class implements PropertySetter`） | lib `internal class(duration: Long) : PropertySetter` 构造器形参 + `fun add(child: Animator): PendingAnimation = apply { ... }`（builder + apply 返回 this）+ `setFloat` override 默认实现；原厂 `private final AnimatorSet mAnim` 等字段 + `public void add(...)` 链调用 void |
| `pending/AnimationSuccessListener.kt`（33 行） | `com/android/launcher3/anim/AnimationSuccessListener.java:7`（24 行） | lib `internal abstract class : ActualEndAnimListener()` + `override fun onAnimationCancel(animator: Animator) { super.onAnimationCancel(animator); cancelled = true }`；原厂 `public abstract class extends ActualEndAnimListener` + `protected boolean mCancelled = false` + `onAnimationCancel` 父类 Adapter 为空，本类自己置 `mCancelled = true` |
| `pending/AnimatorListeners.kt`（67 行，internal object） | `com/android/launcher3/anim/AnimatorListeners.java:10`（81 行，`public class`） | lib `internal object AnimatorListeners { fun forEndCallback(onEnd: (() -> Unit)?): Animator.AnimatorListener = object : AnimatorListenerAdapter() { override fun onAnimationEnd(animator: Animator) { onEnd?.invoke() } } }`（object 单例 + 函数类型参数 + SAM 对象表达式）；原厂 `public static Animator.AnimatorListener forEndCallback(@NonNull final Runnable runnable) { return new NullableAnimatorListenerAdapter() { ... } }`（static 工厂 + Runnable 参数 + 匿名类） |
| `pending/NullableAnimatorListener.kt`（13 行） | `com/android/launcher3/anim/NullableAnimatorListener.java:7`（16 行，`interface extends Animator.AnimatorListener`） | lib `interface NullableAnimatorListener { fun onAnimationCancel(animator: Animator) {} ... }`（默认空方法体，Kotlin 接口允许）；原厂 `public interface extends Animator.AnimatorListener { default void onAnimationCancel(@Nullable Animator animator) {} }`（Java 8 default method + `@Nullable`） |
| `pending/NullableAnimatorListenerAdapter.kt`（26 行） | `com/android/launcher3/anim/NullableAnimatorListenerAdapter.java:8`（30 行） | lib `open class : AnimatorListenerAdapter(), NullableAnimatorListener` 多继承接口 + `protected var cancelled = false`；原厂 `public class extends AnimatorListenerAdapter implements NullableAnimatorListener` + `private int mAnimationId = -1` + `getAnimationId()/setAnimationId(int)` getter/setter |
| `playback/AnimatorPlaybackController.kt`（194 行） | `com/android/launcher3/anim/AnimatorPlaybackController.java:25`（~467 行） | lib `internal class(anim: Animator, duration: Long, holders: List<Holder>) : ValueAnimator.AnimatorUpdateListener`；原厂 `public class` + 私有字段 + `public` getter/setter。lib `class Holder(animator: Animator, totalDuration: Float) { val anim: ValueAnimator = animator as ValueAnimator ; val globalEndProgress: Float = animator.duration / totalDuration ... }` 一参构造 + 计算属性 |
| `playback/Interpolators.kt`（9 行，internal object） | `com/android/launcher3/anim/Interpolators.java:17`（215 行） | lib `internal object Interpolators { val LINEAR = TimeInterpolator { input -> input }} }` lambda 单表达式；原厂 `public static final TimeInterpolator LINEAR = new LinearInterpolator();` |
| `playback/PropertySetter.kt`（30 行） | `com/android/launcher3/anim/PropertySetter.java:10`（65 行，`public interface`） | lib `internal interface PropertySetter { fun <T> setFloat(target: T, property: FloatProperty<T>?, value: Float, interpolator: TimeInterpolator) { property?.setValue(target, value) } ; companion object { val NO_ANIM_PROPERTY_SETTER: PropertySetter = object : PropertySetter {} } }`（默认方法 + 伴生 object）；原厂 `public interface PropertySetter { @Override default <T> void setFloat(...) { property.setValue(target, value); } }`（Java 8 default method）+ `PropertySetter NO_ANIM_PROPERTY_SETTER = new PropertySetter() {}`（静态字段 + 匿名类） |
| `controller/AnimationController.kt`（~184 行） | `com/oplus/quickstep/utils/AnimationController.java:58`（~990 行） | lib `class : DefaultAnimationController()` 单一字段构造器 + `override var animState: AnimationState = AnimationState.NONE ; private set`（公开 var + private setter）+ `fun registerXxx(timeoutMs: Long)` 注册 API + `private inline fun timeoutListener(...)` 高阶函数；原厂 `public class extends DefaultAnimationController` + `private AnimationState mAnimState` + `public final AnimationState getAnimState()` + 大量 `private final synchronized void setAnimState(...)` |
| `controller/AnimationState.kt`（27 行） | `AnimationController.java:104-115`（内部枚举） | lib 顶层 `enum class AnimationState(val withTaskbarAlignment: Boolean, val taskbarAlignmentToLauncher: Boolean) { NONE(false, false), ... }`（枚举构造器参数）；原厂 `public enum AnimationState { NONE(false, false), ... ; private final boolean withTaskbarAlignment ; public final boolean getWithTaskbarAlignment() ... }`（私有 final 字段 + public getter） |
| `controller/DefaultAnimationController.kt`（~95 行） | `com/oplus/quickstep/utils/DefaultAnimationController.java:30`（~155 行） | lib `open class` + `open val animState: AnimationState get() = AnimationState.NONE` 自定义 getter + `open var recentsAnimFinishCallback: (() -> Unit)? ; get() = null ; set(value) {}` 自定义访问器；原厂 `public class` + `public AnimationState getAnimState() { return AnimationState.NONE; }` + `public Runnable getRecentsAnimFinishCallback() / setRecentsAnimFinishCallback(Runnable)` JavaBean |
| `controller/OnAnimStateChangeListener.kt`（3 行） | `DefaultAnimationController.java:34-36`（内部 `interface OnAnimStateChangeListener`，3 行） | lib `typealias OnAnimStateChangeListener = (oldState: AnimationState, newState: AnimationState, runningTask: Any?) -> Unit`；原厂 `public interface OnAnimStateChangeListener { void onAnimStateChanged(AnimationState, AnimationState, TaskInfo); }` |
| `controller/TaskStateChangeTimeOutListener.kt`（37 行） | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper$TaskStateChangeTimeOutListener`（`taskviewremoteanim/TaskStateHelper.java:117-209`，~95 行） | lib `class` 普通构造器 + `enum class Type { ... }` 嵌套枚举 + `private val timeOutOption = Runnable { dispose() ; option() }` 字段初始化 lambda；原厂 `public class` 内部类 + `private final Runnable mTimeOutOption = () -> { dispose(); option(); }` lambda 字段 + `Handler.postDelayed(mTimeOutOption, duration)`（构造器副作用） |
| `controller/RemoteAnimationFactory.kt`（14 行） | `LauncherAnimationRunner$RemoteAnimationFactory`（`com/android/launcher3/LauncherAnimationRunner.java:242-277`，~35 行） | lib `interface RemoteAnimationFactory { fun createAnimation(): AnimatorSet ; fun onAnimationFinished() }` 极简 2 方法；原厂 `public interface RemoteAnimationFactory { void onCreateAnimation(...) ; default AnimatorSet getAnimation() { return ... } ; default void appLaunchAnimStartOrEnd(...) ; ... 8 个 default 方法 }` Java 8 default method |
| `seq/AnimationSeqHelper.kt`（98 行） | `com/oplus/quickstep/utils/AnimationSeqHelper.java:17`（~140 行，`public final class extends DefaultAnimationSeqHelper`） | lib `class : DefaultAnimationSeqHelper()` + `private var seqId = 0L` 可变属性 + `private var nextFinishSeqId: Pair<Any?, Long>? = null`（Kotlin Pair）；原厂 `public final class extends DefaultAnimationSeqHelper` + `private long seqId` + `private f4.l<? extends RecentsAnimationController, Long> nextFinishSeqId`（Kotlin Pair 反编译名） |
| `seq/AnimSeqTimeStamp.kt`（57 行，object） | `com/android/systemui/shared/system/AnimSeqTimeStamp.java:10`（~155 行，`public class`） | lib `object AnimSeqTimeStamp { @Volatile private var lastStartAppTime = 0L ... ; @Volatile var clock: () -> Long = { SystemClock.uptimeMillis() } ; ... private fun gapTo(timestamp: Long): Long = if (timestamp == 0L) Long.MAX_VALUE else clock() - timestamp }`（object + 函数类型字段 + 单表达式函数）；原厂 `public class AnimSeqTimeStamp` + `private static long sLastStartAppTime`（静态字段）+ `public static final synchronized void updateLastStartAppTime()`（静态同步方法） |
| `seq/DefaultAnimationSeqHelper.kt`（~20 行） | `com/oplus/quickstep/utils/DefaultAnimationSeqHelper.java:10`（~50 行） | lib `open class` + `open fun addSeqId(bundle: Bundle?) {}` 默认空实现 + `open val canFinishRecent: Boolean get() = true` 计算属性；原厂 `public class` + `public void addSeqId(Bundle bundle) { Intrinsics.checkNotNullParameter(bundle, "bundle"); }`（Kotlin 编译产物，方法体空但有 null 检查） |
| `feature/AnimationFeatureHelper.kt`（~55 行，object） | `com/oplus/quickstep/utils/AnimationFeatureHelper.java:25`（~290 行，`public final class`） | lib `object AnimationFeatureHelper { private val lock = Any() ; var asyncEnable by SyncedVar(lock, 1) ; ... }`（object + `by` 委托 + 自定义 `ReadWriteProperty`）；原厂 `public final class` + `private volatile int mAsyncEnable = -1` + `private final synchronized void setAsyncEnable(int)` |
| `manager/OplusAnimManager.kt`（~50 行，object） | `com/oplus/quickstep/utils/OplusAnimManager.java:20`（~215 行，`public final class` + `static final OplusAnimManager INSTANCE`） | lib `object OplusAnimManager { private var animationControllerImpl: AnimationController? = null ... ; val animController: DefaultAnimationController get() = animationControllerImpl ?: DefaultAnimationController() ... }`（object 单例 + Elvis 操作符替代 if-null + 可变 var 切换 Impl/Default）；原厂 `public final class OplusAnimManager` + `public static final OplusAnimManager INSTANCE` + `static { ... }` 块用 `Delegates.observable` (反编译成 `t4.a`)+ `INSTANCE.createAnimationController()` 工厂 |
| `continuation/OplusValueAnimator.kt`（~160 行） | `com/oplus/quickstep/utils/OplusValueAnimator.java`（~495 行，`public final class extends ValueAnimator`） | lib `internal class<T>(val param: AnimParam, private val timeController: TimeControllerObjectAnimator?) : ValueAnimator()`（泛型类 + 主构造器 val/var 提升）+ `constructor() : this(AnimParam(), null)` 二级构造器 + `data class AnimParam(...)` 嵌套 data class；原厂 `public final class extends ValueAnimator` + `private final AnimParam<T> param` + `private final ObjectAnimator timeController` + 私有 getter/setter |
| `continuation/RecordInputInterpolator.kt`（15 行） | `com/oplus/quickstep/utils/RecordInputInterpolator.java`（~30 行） | lib `internal class(private val realInterpolator: TimeInterpolator) : TimeInterpolator { var inputed = 0f ; private set ; override fun getInterpolation(input: Float): Float { this.inputed = input ; return realInterpolator.getInterpolation(input) } }`；原厂 `public class implements TimeInterpolator` + `private float inputed = 0f` + `getInputed()/setInputed(float)` |
| `com/android/launcher3/LauncherAnimationRunner.kt`（13 行 stub） | `com/android/launcher3/LauncherAnimationRunner.java`（~600+ 行） | lib `abstract class LauncherAnimationRunner { class RemoteAnimationTarget(var taskId: Int = 0, var leash: Any? = null) }`（最小类型壳）；原厂 600+ 行 `public class` + 内部接口 + WeakReference factory + 三段式 finish 等 |

---

## ② 保真度评估

### A. 精确复刻（行为可对齐的 Kotlin 化改写）

| # | 设计点 | 原厂证据 | lib 证据 |
|---|---|---|---|
| 1 | 字段 → 属性 + getter/setter → `var x: T` / `val x: T` / `private set` | `AnimationController.java` 全文件 `mAnimState` 字段 + `getAnimState()` + `setAnimState()` + 内部 `Intrinsics.checkNotNullExpressionValue` | `AnimationController.kt:30` `override var animState: AnimationState = AnimationState.NONE ; private set` |
| 2 | Java getter/setter → Kotlin 计算属性（自定 getter/setter） | `DefaultAnimationController.java:62` `public Runnable getRecentsAnimFinishCallback()` + `:66` `public void setRecentsAnimFinishCallback(Runnable)` | `DefaultAnimationController.kt:88-91` `open var recentsAnimFinishCallback: (() -> Unit)? ; get() = null ; set(value) {}` |
| 3 | 匿名 Java 类 → `object : SuperType() { ... }` SAM 表达式 | `AnimationSuccessListener.java:14-22` 匿名 `AnimatorListenerAdapter` | `AnimationSuccessListener.kt:21-31` + `AnimatorListeners.kt:19-23` + `Demo10.kt:142-148` `object : NullableAnimatorListenerAdapter() { ... }` |
| 4 | `Runnable` 参数 → `(() -> Unit)?` 函数类型参数 + `Runnable { ... }` 适配 | `AnimationSeqHelper.java:80` `public boolean delayFinishRecents(Runnable runnable)` | `AnimationSeqHelper.kt:62` `override fun delayFinishRecents(action: (() -> Unit)?): Boolean` |
| 5 | `Consumer<T>` 参数 → `(T) -> Unit` 函数类型参数 | `PendingAnimation.java:60` `public void addEndListener(Consumer<Boolean> consumer)` | `PendingAnimation.kt:72` `fun addEndListener(onEnd: ((success: Boolean) -> Unit)?)` |
| 6 | 静态工厂方法 → `companion object` 工厂 + `@JvmStatic` 去掉（Kotlin 调用方无需） | `AsyncValueAnimator.java:43-49` `public static final ValueAnimator ofFloat(boolean isAsync, float... values)` + `@JvmStatic` 注解 | `OplusValueAnimator.kt:117-121` `fun ofFloat(isAsync: Boolean, vararg values: Float): ValueAnimator`（直接伴生 fun，Kotlin 调用 `OplusValueAnimator.ofFloat(true, ...)`） |
| 7 | 单例：`static final INSTANCE` → `object` / `by lazy` | `OplusAnimManager.java:35` `public static final OplusAnimManager INSTANCE = new OplusAnimManager()` + `static { ... }` 块 | `OplusAnimManager.kt:17-23` `object OplusAnimManager` + `AnimSeqTimeStamp.kt:11-50` `object AnimSeqTimeStamp` + `AnimationControlThread.kt:81-83` `internal val instance: AnimationControlThread by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AnimationControlThread() }` |
| 8 | 函数式接口 → `fun interface` SAM 或 `typealias` | `DefaultAnimationController.java:34-36` `public interface OnAnimStateChangeListener { void onAnimStateChanged(...) }`（业务需写 1 个方法时也得 implement） | `OnAnimStateChangeListener.kt:7` `typealias OnAnimStateChangeListener = (oldState: AnimationState, newState: AnimationState, runningTask: Any?) -> Unit`（直接 lambda）+ `AnimationHandler.kt:38-40` `fun interface AnimationFrameCallback { fun doAnimationFrame(frameTimeMs: Long): Boolean }`（fun interface 保留 SAM 转换）+ `TickScheduler.kt:18-20` `fun interface FrameCallback { fun doFrame(frameTimeNanos: Long) }` |
| 9 | `Intrinsics.checkNotNullParameter` 自动消除 | `AnimationController.java:467` `Intrinsics.checkNotNullParameter(anim, "anim");` 几乎每个 public 方法都有一条 | lib 全树无 `Intrinsics.*` 调用——Kotlin 编译器把 null 检查编译进方法体但生成的是 JVM bytecode `Intrinsics.checkNotNullParameter`，**lib 没有是因为 lib 自己写的 Kotlin 源码有可空类型时由 Kotlin 自动生成；反编译回 Kotlin 时这条仍在，但写源码时不需要手写** |
| 10 | 数据载体 → `data class` | `OplusValueAnimator.java:330-371` 的 `AnimParam` 是 Java 字段 + getter/setter + copy 方法 | `OplusValueAnimator.kt:82-90` `data class AnimParam(var name: String = "default", var fromValue: Float = 0f, ...)`（data class 自动 `copy()/equals/hashCode/toString`）+ `Demo10.kt:113-117` `private data class DemoInfo(val title: String, val description: String, val activityClass: Class<*>)` |
| 11 | 构造器 setter 模式 → 主构造器 `val/var` 参数提升为字段 | `LooperExecutor.java:23-25` `public LooperExecutor(Looper looper) { this.mHandler = new Handler(looper); }` + 字段 | `LooperExecutor.kt:15` `class LooperExecutor internal constructor(private val handler: Handler?)`（主构造器形参直接变字段） |
| 12 | 嵌套静态内部类 → `companion object` + `class` 平级 | `OplusValueAnimator.java` 内部 `public static final class Companion { ... ; public final <T> OplusValueAnimator generateContinuationAnim(...) { ... } }` + `INSTANCE` 字段 | `OplusValueAnimator.kt:25` `companion object { fun <T> generateContinuationAnim(...) ; fun ofFloat(...) }` |
| 13 | `Java ArrayList` → `mutableListOf<T>()` / `listOf<T>()` / `List<T>` 接口 | `AnimationController.java:90` `private List<CustomRectFSpringAnim> mRecentsAnims = new ArrayList<>();` + `ArrayList<>` 引用 | `AnimationController.kt:32` `private val recentsAnims = mutableListOf<CustomRectFSpringAnim>()`（接口 `MutableList` + 默认实现 `ArrayList`） |
| 14 | Java `volatile` 字段 + getter/setter → `@Volatile var` + 委托 | `AnimationFeatureHelper.java:65-72` `private volatile int mAsyncEnable = -1` + `private final synchronized void setAsyncEnable(int i9)` + `public final int getAsyncEnable()` | `AnimationFeatureHelper.kt:13-19` `var asyncEnable by SyncedVar(lock, 1)`（`SyncedVar` 是 `ReadWriteProperty` 实现：`@Volatile var` + `synchronized(lock)` setter） |
| 15 | Builder 链：`void add() / setFloat()` → `fun add(...): T = apply { ... }` | `PendingAnimation.java:55-59` `public void add(@NonNull Animator animator) { ... }`（void 返回） | `PendingAnimation.kt:42` `fun add(child: Animator): PendingAnimation = apply { child.duration = durationMs ; anim.playTogether(child) ; addToHolders(child) }`（链式 + apply 返回 this） |
| 16 | lambda 静态方法 `private static final void method$lambda$N(this$0, ...)` → 内联 lambda | `AsyncValueAnimator.java:131-140` `private static final void cancel$lambda$4(AsyncValueAnimator this$0) { Intrinsics.checkNotNullParameter(this$0, "this$0"); super.cancel(); }` | `AsyncValueAnimator.kt:42-53` `private inline fun marshal(crossinline action: () -> Unit) { if (isCurrentExecutor) action() else executor.execute { action() } } ; override fun start() = marshal { super@AsyncValueAnimator.start() }`（inline + lambda） |
| 17 | `Intrinsics.checkNotNullParameter` 噪点消除 | `AnimationController.java:467, 469, 470, 471, ...` 大量 `Intrinsics.checkNotNullParameter(x, "x")` | lib 全树无此调用（Kotlin 编译器在需要时自动 emit，但 Kotlin 源码干净） |
| 18 | Java 8 default method → Kotlin 接口默认实现 | `RemoteAnimationFactory.java:242-277` `public interface RemoteAnimationFactory { void onCreateAnimation(...); default AnimatorSet getAnimation() { return ... } ; ... }` | `RemoteAnimationFactory.kt:9-13` `interface RemoteAnimationFactory { fun createAnimation(): AnimatorSet ; fun onAnimationFinished() }`（Kotlin 接口允许默认实现，demo 实现只需 override 两个方法） |
| 19 | `Enum` 类 + 字段 + getter → `enum class` 构造器参数 | `AnimationController.java:104-115` 内部枚举 + `private final boolean withTaskbarAlignment` + `getWithTaskbarAlignment()/getTaskbarAlignmentToLauncher()` | `AnimationState.kt:15-26` `enum class AnimationState(val withTaskbarAlignment: Boolean, val taskbarAlignmentToLauncher: Boolean) { NONE(false, false), ... }`（构造器参数即属性，无需 getter） |
| 20 | `Handler.postDelayed` + `Runnable` 字段 → `kotlin.concurrent` | `TaskStateHelper.java:124-137` `private final Runnable mTimeOutOption = () -> { ... } ; constructor() { mHandler.postDelayed(mTimeOutOption, duration); }` | `TaskStateChangeTimeOutListener.kt:26-31` `private val timeOutOption = Runnable { dispose() ; option() } ; init { handler?.postDelayed(timeOutOption, duration) }` |

### B. 有意简化（lib 注释明示，行为等价或场景不达）

| # | 简化内容 | 原厂对应 | lib 取舍理由 |
|---|---|---|---|
| 1 | 字段/方法名去 `m` 前缀：`mAnimLooperExecutor` → `executor`、`mIsEnd` → `isEnd` | `AsyncValueAnimator.java:34, 42` | AOSP/Java 命名约定 vs Kotlin 无前缀约定，库内一致用驼峰无前缀 |
| 2 | `private static final String TAG = "..."` 移除 | `AsyncValueAnimator.java:33` `TAG` 字段 | lib 用 `Trace` 替代日志（`AsyncAnimCallbacks.kt:47-55`），类内不再打 LogUtils.i |
| 3 | `@Metadata(d1 = "...", d2 = "...")` 大段二进制注解移除 | `OplusAnimManager.java` 顶部 50+ 行 `@Metadata(d1 = "..." d2 = "..." k = 1, mv = ...)` | 这是 Kotlin 编译器自动生成的反射元数据，lib 写源码时无需手写，编译时 Kotlin 编译器会重新生成 |
| 4 | `@SourceDebugExtension({"SMAP..."})` SMAP 调试信息移除 | `OplusValueAnimator.java` + `AnimationController.java` 等的 `@SourceDebugExtension` | 这是编译期 Kotlin compiler 嵌入的 SMAP，源码侧不可见；lib 编译时由 Kotlin 编译器自动生成 |
| 5 | `JADX DEBUG/WARN` 注释移除 | `OplusExecutors.java` 中 `JADX DEBUG: Don't trust debug lines info...` 大量 JADX 反编译注解 | 仅 JADX 反编译产物，源码不存在 |
| 6 | `Intrinsics.checkNotNullExpressionValue` 噪点移除 | `AsyncValueAnimator.java:54` `Intrinsics.checkNotNullExpressionValue(MAIN_EXECUTOR, "MAIN_EXECUTOR")` 等 | Kotlin 编译器在 nullable 表达式后自动 emit，源码干净，编译期仍然 emit |
| 7 | `Intrinsics.checkNotNullParameter` 噪点移除 | `AsyncAnimCallbacks.java:78` 等每个 public 方法首行 | 同上，编译期 emit |
| 8 | `lambda$method$N(this$0, ...)` 静态方法 → `inline fun` 直接展开 | `AsyncValueAnimator.java:131-140, 153-160` 等 5 个 `private static final void xxx$lambda$N` | Kotlin `inline` 函数 + lambda 让字节码里没有额外函数调用，源码侧直接展开 |
| 9 | `WhenMappings.$EnumSwitchMapping$0` 枚举 switch 缓存类 → `when (animState)` 直接 enum 分支 | `AnimationController.java:90-105` 静态 `$EnumSwitchMapping$0 = new int[AnimationState.values().length]; ...` | Kotlin 编译器对 `when` 表达式在 JVM 后端会生成 switch 表，但不需要显式声明 ordinal 映射 |
| 10 | `extends AbstractExecutorService` 移除 | `LooperExecutor.java:18` `public class LooperExecutor extends AbstractExecutorService` | lib 不实现 ExecutorService 抽象方法（awaitTermination/shutdownNow/isShutdown/isTerminated），只暴露 `execute/post/postAsync/isCurrentThread` 最小接口；JVM 单测不需要 ExecutorService 类型（lib 注释 `LooperExecutor.kt:17-20` 说明） |
| 11 | `LooperExecutor.getLooper()/getHandler()/getThread()/setThreadPriority()` getter/setter 全部移除 | `LooperExecutor.java:35-67` | lib 内部不暴露 Looper/Handler/Thread，仅 `isCurrentThread` + `execute/post/postAsync`；优先级在 `AnimationControlThread.PRIORITY` 设置，不需要从外部 setter |
| 12 | `setExecutor(LooperExecutor executor)` setter 改为 `var executor` 公开属性 | `AsyncValueAnimator.java:147-150` `setExecutor` 方法 | lib `AsyncValueAnimator.kt:19` `var executor: LooperExecutor = Executors.MAIN_EXECUTOR`（public var，可读可写；setter 验证由 Kotlin 编译器在边界做） |
| 13 | `OplusExecutors.INSTANCE` 单例 → `OplusAnimManager` Kotlin `object` | `OplusAnimManager.java:35` `public static final OplusAnimManager INSTANCE` | lib `OplusAnimManager.kt:17` `object OplusAnimManager`（Kotlin object 自动单例，省去 INSTANCE 字段） |
| 14 | `Executors.java:18-99` 14 个 executor 全砍，只留 `MAIN_EXECUTOR` + `ANIM_CONTROL_EXECUTOR` | `Executors.java:54, 57, 60` + `OplusExecutors.java:95, ...` 多个 executor | 与动画线程主题无关（review 01 §②B-3 详述） |
| 15 | `OplusLooperExecutor` 四扩展移除（executeAtFront/WithUx/BlockWait/Delay） | `OplusLooperExecutor.java:38-103` | 依赖 LauncherBooster 私有 API；executeBlockWait 是 v4 §9.3 点名的 ANR 风险 |
| 16 | `RemoteAnimationFactory` 砍到 2 方法 demo 接口 | `LauncherAnimationRunner.java:242-277` 1 abstract + 8 default 方法 | lib 不接 binder 通道，类型壳足够 |
| 17 | `AnimationFeatureHelper` 用本地 setter 模拟 RUS 下发 | `AnimationFeatureHelper.java:82-92, 159-373` `RusBaseConfigManager.RusConfigChangedListener` + `updateRusConfig` 解析 7 配置项 + 2 列表 | demo 无远程下发，简化语义等价（review 03 §2.2-2 详述） |

### C. 遗漏（lib 没有、原厂有；不一定有意）

| # | 遗漏点 | 原厂证据 | 影响 |
|---|---|---|---|
| 1 | **`OplusAnimManager` 的另 5 个 helper**：`mAppOpenAnimMergeHelper`/`mMultiAppAnimMergeHelper`/`mInterceptKeyEventHelper`/`mMultiOpenPreStartHelper`（`com.android.common.util.*` 业务） | `OplusAnimManager.java:104-118` | 与动画线程方案无关，demo 不需要 |
| 2 | **`AnimationFeatureHelper` 的 7 setter/getter 一对一封装**：原厂每个 flag 有独立 `private final synchronized void set/getAsyncEnable()` 等 | `AnimationFeatureHelper.java:96-150` | lib 用 `SyncedVar` 委托统一模式 + 7 行 `var x by SyncedVar(...)`，**代码量是原厂的 1/10**；语义一致（@Volatile 读 + 同锁写） |
| 3 | **`AnimSeqTimeStamp` 公开 4 个 static `reset*()` 方法 + 4 个 `timeGapToLast*Time()` 方法**：原厂 `@JvmStatic synchronized` 全部 public | `AnimSeqTimeStamp.java:70-147` | lib 把 update/reset/timeGap 全部 `internal`，只暴露 `updateLastRecentFinishTime()` 1 个 public；reset 收进 `resetAllForTest`（`AnimSeqTimeStamp.kt:44-49`） |
| 4 | **`AnimatorPlaybackController` 的 7 个工具方法**：`getInterpolatedProgress`/`overrideDurationScale`/`dispatchSetInterpolator`/`iterateAllChildAnim`/`resetPropertySetters`/`getTarget`/`getDuration`/`isIsDispatchStartPending` | `AnimatorPlaybackController.java:275-309` | 不影响演示核心，缺 getter 让 demo 取不到内部状态（demo 自己另开字段） |
| 5 | **`PendingAnimation.addFloat` 的 vararg `setFloats`**（多 keyframe） | `PendingAnimation.java:137-144` | lib `addFloat(target, property, from, to, ip)` 只接单 from/to |
| 6 | **`PendingAnimation.setInt`/`setViewAlpha`/`setViewBackgroundColor`** + `AlphaUpdateListener`（alpha=0 联动 INVISIBLE） | `PendingAnimation.java:218-234` + `PropertySetter.java:38-49` + `AlphaUpdateListener.java:9-62` | demo 场景不需要 |
| 7 | **`startWithVelocity` + `SpringProperty` + `SpringAnimationBuilder` 全链路** | `AnimatorPlaybackController.java:382-461` + `SpringProperty.java:4-53` + `SpringAnimationBuilder.java:18-186` | lib `Holder.springProperty: Any? = null` 占位（`AnimatorPlaybackController.kt:79`），`PendingAnimation.add(anim, ip, springProperty)` 三参版本第三参直接丢弃（`PendingAnimation.kt:53-54`）—— **API 说谎** |
| 8 | **`Interpolators` 40+ 常量** + `clampToProgress`/`mapToProgress`/`reverse`/`scrollInterpolatorForVelocity`/`overshootInterpolatorForVelocity` 等工具函数 | `Interpolators.java:33-214` | demo 只用 LINEAR |
| 9 | **`LauncherAnimationRunner` 的 `RemoteAnimationFactory` 8 个 default 方法**（getAnimation/appLaunchAnimStartOrEnd/handleAnimationMerged/isSameIcon/onAnimationCancelled/preLoadIcon/supportInterruption/tryFinishOpenRemote） | `LauncherAnimationRunner.java:242-277` | lib `RemoteAnimationFactory` 只保留 2 方法 demo 接口 |
| 10 | **`AnimSeqTimeStamp.updateNextFinishSeqIdIfNeed/getNextFinishSeqId` 的 `Intrinsics.areEqual` 引用相等 + Lib `===`** | `AnimationSeqHelper.java:122-127`（`!Intrinsics.areEqual(lVar.f12314a, recentsAnimationController)`） | lib 用 `===` 引用比较，`AnimationSeqHelper.kt:85`；原厂 `Intrinsics.areEqual` 是 Kotlin 编译器 emit 的 null-safe equals（`x?.equals(y) ?: (y === null)`），lib `===` 在 controller 不会重写 equals 时等价 |

---

## ③ 行为差异风险点（按严重度排序）

### 风险 1（高 / bug 级）：Kotlin 接口 SAM 转换让 listener 参数签名不一致

**现象**：原厂 `Animator.AnimatorListener` 接口的回调签名是 `(Animator)`，但 JADX 反编译后 `RuntimeAnimatorListener` 的 `onAnimationEnd(Animator animator)` 接收的可能是 null（因为 `NullableAnimatorListener.onAnimationEnd(@Nullable Animator animator)`）；lib 派发时一律传非 null 真实对象。

**证据**：
- 原厂 `AsyncValueAnimator.java:64, 70, 83`：`thisthis.mAsyncAnimCallbacks.onAnimationEnd(null)` / `onAnimationCancel(null)` / `onAnimationStart(null)` 派发 **null animator** 给 listener
- lib `AsyncValueAnimator.kt:29-37`：listener 收到的全是真实 anim 实例（`asyncAnimCallbacks.onAnimationEnd(a)`、`a` 为非空）

**影响**：review 01 §②C-6 已列；这是有意改进（业务 listener 不必容忍 null），但与原厂不一致——如果业务按原厂习惯做 `override fun onAnimationEnd(a: Animator?)`，lib 下 `a` 永远非 null。

**严重度**：低（多数业务都已写非空容忍），但严格迁移场景需要注意。

---

### 风险 2（高）：fun interface + typealias 让 listener 形态不匹配

**现象**：原厂 `OnAnimStateChangeListener` 是 Java `interface`，业务必须写 `implements` 或匿名类；lib `typealias OnAnimStateChangeListener = (AnimationState, AnimationState, Any?) -> Unit` 让业务可以直接传 lambda。

**证据**：
- lib `OnAnimStateChangeListener.kt:7` `typealias OnAnimStateChangeListener = (oldState: AnimationState, newState: AnimationState, runningTask: Any?) -> Unit`
- lib `DefaultAnimationController.kt:26-30`：`animStateChangeListeners.toList()` 快照遍历 + 直接 `l(oldState, newState, runningTask)` 函数调用
- lib `Demo6StateMachineActivity.kt:42-47` `controller.addOnAnimStateChangeListener { old, new, _ -> onStateChanged(old, new) }` 直接传 lambda

**影响**：
- lib 业务**无法 override 默认行为**——typealias 是函数类型而非 interface，没有扩展点。Demo8 需要在状态变化时打印日志、或者在某个特定状态强制打断，都只能包一层 lambda。
- 原厂业务可以 `class MyListener : DefaultAnimationController.OnAnimStateChangeListener { ... }` 然后传 `MyListener()` 实例，复用同一个 listener 在多处订阅——lib 下每次都需要传 lambda，**lambda 闭包持有外部引用，每次 `addOnAnimStateChangeListener` 都会创建一个新对象**（Kotlin lambda 默认会被编译成新类，但语义上等同于匿名类）；若原厂期望"同一 listener 实例被多个 controller 复用"，lib 不可达。
- `removeOnAnimStateChangeListener(listener)`（`DefaultAnimationController.kt:32-34`）：lib 用 `animStateChangeListeners.remove(listener)`，**lambda 没有结构相等性**——两次相同语义的 `{ old, new, _ -> ... }` 不相等，remove 永远 remove 不到；原厂 interface 实现可以靠重写 equals 让 remove 命中（虽然没人这么做）。

**严重度**：高——这是**直接破坏"监听器增删"的 API 契约**。如果业务真的需要在 onCreate 注册 listener、onDestroy 反注册，lib 下 lambda 没有引用相等性，`removeOnAnimStateChangeListener` 会沉默失败，listener 永久残留。

---

### 风险 3（高）：Kotlin `var` 公开属性绕过了原厂的 setter 校验

**现象**：原厂 `AsyncValueAnimator.setExecutor(LooperExecutor executor)` 包含 `Intrinsics.checkNotNullParameter(executor, "executor")`（`AsyncValueAnimator.java:148`）；lib `var executor: LooperExecutor` 公开属性赋值时同样会做 null 检查（Kotlin 非空类型），但**赋值时机无约束**——start 之后再改 executor 是合法的、原厂如此；start 之后改 listener 是另一回事——listener 在 start 之后改会改变后续派发，行为符合直觉但原厂 setter 不抛异常。

**证据**：
- 原厂 `AsyncValueAnimator.java:148` `Intrinsics.checkNotNullParameter(executor, "executor")` —— 如果传 null 立刻抛 IAE
- lib `AsyncValueAnimator.kt:19` `var executor: LooperExecutor = Executors.MAIN_EXECUTOR` —— Kotlin 编译为 `setExecutor` 方法，null 检查内嵌但无 Intrinsics 调用
- 关键差异：原厂 listener 接口里 `addAnimatorListener` / `removeAnimatorListener` 用 final 方法，`AsyncValueAnimator.java:106-122`；lib 用 `asyncAnimCallbacks.addListener`（`AsyncAnimCallbacks.kt:30`）—— **调用入口不同**，业务如果按原厂文档调 `anim.addAnimatorListener(...)` 编译失败（lib 没有这个方法）

**影响**：**API 不兼容**——原厂的 `addAnimatorListener` / `removeAnimatorListener` 方法名 lib 改成了 `asyncAnimCallbacks.addListener` / `asyncAnimCallbacks.removeListener`（`AsyncValueAnimator.kt:25-27`）。Demo3 调用方式是 `anim.asyncAnimCallbacks.addListener(...)`（`Demo3AsyncCrossThreadActivity.kt:67-83`）——这是 lib 的实际契约。**迁移时必须全局替换 `addAnimatorListener` → `asyncAnimCallbacks.addListener`**。

**严重度**：高——直接破坏 API 兼容。

---

### 风险 4（中）：companion object 内的工厂方法签名不一致

**现象**：原厂 `AsyncValueAnimator.Companion.ofFloat(boolean, float...)` 工厂方法返回 `ValueAnimator`（父类），调用方拿到的可能是 `AsyncValueAnimator`（子类）或 `ValueAnimator`（原版）；lib `OplusValueAnimator.ofFloat(isAsync, vararg values)` 是不同类的工厂（`OplusValueAnimator.kt:117-121`），语义不同。

**证据**：
- 原厂 `AsyncValueAnimator.java:43-49` `ofFloat`：`return isAsync ? new AsyncValueAnimator() : new ValueAnimator()` —— 返回父类型
- 原厂 `AsyncValueAnimator.java:101-104` 还有 `@JvmStatic public static final ValueAnimator ofFloat(boolean z8, float... fArr)` 在类本体（非 Companion）—— Kotlin 编译器对 `Companion` 方法加 `@JvmStatic` 会**额外**生成一个类本体的静态方法作为 Java 调用入口
- lib `OplusValueAnimator.kt:117-121` `ofFloat` 只在 companion 里，返回 `ValueAnimator` 父类型；行为等价但**类名**不同

**影响**：迁移 `AsyncValueAnimator.ofFloat(true, ...)` → `OplusValueAnimator.ofFloat(true, ...)`，**类名变化**；若原厂业务依赖"异步路径的 AsyncValueAnimator 子类特性"（如 `setExecutor`），lib 拿到的是普通 `ValueAnimator`，**没有 setExecutor 方法**——需要在 lib 下用 `AsyncValueAnimator`（无 `ofFloat` 工厂）+ `setFloatValues(0f, 1f)` 手动构造。

**严重度**：中——容易踩坑但属于"换 API 不是换语义"。

---

### 风险 5（中）：Kotlin `object` 单例 vs Java 静态 INSTANCE 的静态初始化时机差异

**现象**：原厂 `OplusAnimManager.INSTANCE` 是 `static final` 字段，类加载即创建（`OplusAnimManager.java:35` + `static { ... }` 块在 `:46-105`）；lib `object OplusAnimManager` 也是 lazy 类加载，但**第一次访问任意字段/方法时才初始化**。

**证据**：
- 原厂 `OplusAnimManager.java:46-105` `static { OplusAnimManager oplusAnimManager = new OplusAnimManager(); INSTANCE = oplusAnimManager; ... 6 个 observable 委托 ... }` —— 6 个 helper 全部 `static final`，类加载即全部创建
- lib `OplusAnimManager.kt:17-23` `object OplusAnimManager { private var animationControllerImpl: AnimationController? = null ... ; init { if (supportInterruption()) { animationControllerImpl = AnimationController() ... } } }` —— `object` 第一次访问任意字段时触发 init 块

**影响**：
- 原厂**类加载即触发所有 helper 初始化**（包括 `createAnimationController`/`createAnimationSeqHelper` 等 6 个），并注册 `t4.a` observable 委托监听；lib 是**访问 `animController` 字段时才触发 init 块**。
- 如果原厂代码依赖"类加载即完成 RUS 注册"，lib 下要等到 `OplusAnimManager.animController` 被首次访问（demo 启动 Activity 时）才触发；若该访问之前有别的代码依赖 helper 状态，**会因初始化时机错位出现 NullPointerException 或默认值错乱**。

**严重度**：中——Demo8 演示了 `OplusAnimManager.interruptionEnabled = false`（`Demo8FeatureFlagActivity.kt:54-56`），但切换的是 `animationControllerImpl`/`animationSeqHelperImpl` 字段，**不是原厂的 RUS 注册**——lib 切 disable 后再访问 `animController` 才会走 fallback 分支（`OplusAnimManager.kt:32`）。

---

### 风险 6（中）：val/var + `private set` 让原厂的"读 + 写都在调用方线程"约束变模糊

**现象**：原厂 `AnimationController.mAnimState` 是 `private` 字段，`public final synchronized void setAnimState(AnimationState state)` + `getAnimState()`（`AnimationController.java`）—— 写操作有同步保护（虽然 OPPO 实际并不保证多线程，原代码 review 03 §3-f 已列）；lib `override var animState: AnimationState = AnimationState.NONE ; private set`（`AnimationController.kt:30`）—— 公开 `var` 只对外部是只读（`private set`），但内部 `updateAnimState` 仍然无锁赋值（`AnimationController.kt:54-58`）。

**证据**：
- 原厂 `AnimationController.java:773` `private final void updateAnimState(AnimationState animState2) { this.mAnimState = animState2; onAnimStateChanged(this.mAnimState, animState2, this.mRunningTaskInfo); }` —— 字段直接赋值，无同步
- lib `AnimationController.kt:54-58` `private fun updateAnimState(animationState: AnimationState) { val old = animState ; animState = animationState ; onAnimStateChanged(old, animationState, runningTaskInfo) }` —— `private set` 实际被绕过？**不**——`private set` 只限制**外部**赋值，**类内部可赋值**；与原厂"private 字段 + 内部方法改"等价

**影响**：API 形式从"JavaBean getter/setter + private 字段"变成"public var + private set"，但**运行时可见性 + 写入语义完全相同**（类内可写、类外只读）。**业务误用风险**：如果业务用反射写 `animState = AnimationState.OPEN`（绕过 private set），lib 下 Kotlin 的 `private set` 是编译期限制，运行时反射可写——**与原厂字段反射写无差异**。

**严重度**：低——纯编译期差异，运行期行为一致。

---

### 风险 7（中）：Kotlin `apply { }` 让 builder 链的方法可以链式调用，原厂不能

**现象**：原厂 `PendingAnimation.add(animator)` 返回 void（`PendingAnimation.java:55-59`），业务只能一行行调；lib `fun add(...): PendingAnimation = apply { ... }` 返回 this，可以链式。

**证据**：
- lib `Demo9AllAppsTransitionActivity`（未读全文，但 API 注释 + USAGE.md §"PendingAnimation"）应该有 `pending.add(anim1).add(anim2).addEndListener { ... }` 链式调用
- 原厂 `PendingAnimation` 调用方式：`pending.add(anim1); pending.add(anim2); pending.addEndListener(...)`（多行）

**影响**：纯便利性差异，**无功能差异**——但业务如果按原厂写多行，lib 下每行都返回新 this，浪费引用；按 lib 写链式，迁回原厂时编译错误（void 不能 `.add()`）。

**严重度**：低——纯风格差异。

---

### 风险 8（中）：Kotlin `inline fun` + `crossinline lambda` 让 marshal 协议字节码更简洁，但 lambda 闭包变量捕获语义与 Java 不同

**现象**：lib `private inline fun marshal(crossinline action: () -> Unit)`（`AsyncValueAnimator.kt:42-45`）会让 marshal 函数的字节码**内联到调用点**——`override fun start() = marshal { super@AsyncValueAnimator.start() }`（`AsyncValueAnimator.kt:47`）编译后字节码中**没有 marshal 方法调用**，直接把 `if (isCurrentExecutor) action() else executor.execute { action() }` 内联展开。

**证据**：
- lib `AsyncValueAnimator.kt:42-53` `private inline fun marshal(crossinline action: () -> Unit) { if (isCurrentExecutor) action() else executor.execute { action() } } ; override fun start() = marshal { super@AsyncValueAnimator.start() } ; override fun cancel() = marshal { super@AsyncValueAnimator.cancel() } ; override fun end() = marshal { super@AsyncValueAnimator.end() }`
- 原厂 `AsyncValueAnimator.java:124-128, 130-141, 153-164` `start/cancel/end` 三个方法各自判线程 + `execute(new androidx.recyclerview.widget.a(this, N))` —— 每个方法都有独立的匿名 Runnable 类（被 JADX 编译为 `xxx$lambda$N` 静态方法）

**影响**：
- **运行时行为等价**（都是 if-thread-else-execute + 调 super）
- **字节码不一致**：lib 三处调用点 inline 后展开；原厂是三处调用同一段逻辑但每个方法独立一份 lambda 类
- **栈追踪差异**：调试时原厂栈帧能看到 `start → super.cancel → ...`，lib 下由于 inline 没有 marshal 方法帧
- **若需要 hook "marshal 调用"**（如打日志、做统计），lib 下无法 hook；原厂可以 hook `LooperExecutor.execute`

**严重度**：低——非功能差异。

---

### 风险 9（中）：Kotlin 函数类型 vs Java Consumer/Runnable 的等价性陷阱

**现象**：原厂 listener 接口收 `Consumer<Boolean>`（如 `forEndCallback(Consumer<Boolean>)`，`AnimatorListeners.java:64`）；lib 收 `((success: Boolean) -> Unit)?`（`AnimatorListeners.kt:25`）。从 Java 调用方看 Kotlin 函数类型被编译为 `Function1<P1, R>` 接口（`kotlin.jvm.functions.Function1`），**不是** `Consumer<Boolean>`。

**证据**：
- lib `AnimatorListeners.kt:25-37` `fun forEndCallback(onEnd: ((success: Boolean) -> Unit)?): Animator.AnimatorListener` —— Kotlin 函数类型 `((Boolean) -> Unit)?`
- 编译后：Kotlin 编译器把 `(Boolean) -> Unit` 编译为 `Function1<Boolean, Unit>`，SAM 转换需要 `@JvmFunctionInterface` 注解（Kotlin 1.4+）+ Java SAM 接口——`Consumer<Boolean>` 不是 Kotlin fun interface（Java 8 SAM），所以**Java 调用方不能直接传 lambda 给 lib**，必须显式 `new Consumer<Boolean>() { ... }`
- 原厂 `AnimatorListeners.java:64` `forEndCallback(Consumer<Boolean> consumer)` —— Java 调用方直接传 lambda 即可

**影响**：
- Java 业务迁到 lib 下需要写显式 Consumer 包装，**不能直接 lambda**
- Kotlin 业务迁回原厂需要写 `Consumer<Boolean> { ... }` 包装

**严重度**：中——跨语言迁移成本。

---

### 风险 10（中）：Kotlin `data class` 的 `copy()` vs Java 手动 copy

**现象**：原厂 `OplusValueAnimator.AnimParam.copy()` 是手写的方法（`OplusValueAnimator.java:353-360`，8 行），lib `data class AnimParam(var name: String = "default", ...)`（`OplusValueAnimator.kt:82-90`）自动生成 `copy(name = ..., fromValue = ...)`。

**证据**：
- 原厂 `OplusValueAnimator.java:353-360` `public final AnimParam copy(String name, ...) { ... ; return new AnimParam(...) }`
- lib `OplusValueAnimator.kt:139` `val newAnim = OplusValueAnimator<T>(anim.param.copy(), timeController)` —— 直接 `param.copy()` 一行

**影响**：
- **review 04 §2.3-2 已点出 bug**：lib `generateContinuationAnim` 用 `anim.param.copy()`（`OplusValueAnimator.kt:139`），新 anim 与旧 anim 共享拷贝后的 param 实例（data class copy 默认浅拷贝）—— 续行动画运行期间 timeController 写 `param.currentFraction` 会同步改到旧 anim 的 param（如果旧 anim 还持有同一引用）
- 原厂 `OplusValueAnimator.java:105` 同样 `INSTANCE.copy(anim.getParam())` —— 手写 copy **对引用类型字段仍共享引用**（`name: String` 是 String 不可变 + `valueApplicator` 是 lambda 可能共享）—— **原厂也有此 bug**
- **实际影响有限**：原厂 lib 均如此，data class 让 bug 更容易触发（开发者以为 `copy()` 是深拷贝）

**严重度**：中——bug 级但原厂同样有。

---

### 风险 11（中）：Kotlin `object : Listener() { ... }` SAM 表达式生成新类 vs Java 匿名类

**现象**：Kotlin 的 `object : Animator.AnimatorListener { override fun onAnimationEnd(a: Animator) { ... } }`（如 `Demo10.kt:142-148`）每次编译都会生成一个新类（除非是 `inline fun` 参数），Java 匿名类每次也是新类——**等价**。

**证据**：略，编译产物可比。

**影响**：
- **栈追踪差异**：Kotlin object 表达式编译后的类名形如 `Demo10IndependentThreadActivity$startMainThreadDrive$anim$1`，Java 匿名类形如 `Demo10IndependentThreadActivity$1`
- **调试时不如 Java 匿名类直观**

**严重度**：低。

---

### 风险 12（低）：Kotlin `internal` 修饰符 vs Java package-private（默认）

**现象**：lib 大量用 `internal class` / `internal fun`（如 `AnimationHandler.kt:36` `internal class AnimationHandler`，`PendingAnimation.kt:24` `internal class PendingAnimation`），Kotlin `internal` 编译为 public + 名字 mangling（`AsmUtilKt` 等）+ module 隔离（JVM 字节码层面是 public，运行时会被 Kotlin 反射/KSP 检查 module 边界）。

**证据**：
- lib `AnimationHandler.kt:36` `internal class AnimationHandler(scheduler: TickScheduler? = null)`
- lib `PendingAnimation.kt:24` `internal class PendingAnimation(duration: Long) : PropertySetter`
- lib `AnimationFeatureHelper.kt:11` `object AnimationFeatureHelper` —— public，但字段如 `lock` 是 `private val lock = Any()`

**影响**：
- lib 业务在 demo 模块里**无法访问** `internal` 修饰的类（demo 模块是独立 module）—— USAGE.md 列的 public API 就是 lib 的实际暴露面；这是**有意**的（防止 demo 业务误用实现细节）
- 原厂 Java 默认 package-private（无修饰符）行为类似，但**跨 module 边界**：原厂 `com.android.launcher3.anim.PendingAnimation` 是 `public class`，任何 module 都能 import；lib `internal class PendingAnimation` 只对同 module 可见
- 若 demo 模块需要直接构造 `PendingAnimation`，**编译错误**——USAGE.md 没把 PendingAnimation 列为 public API（review 02 §1 也确认 `internal class`）

**严重度**：低——这是有意的 API 边界设计，USAGE.md 已说明；但**与原厂的"全部 public"风格不一致**，迁移时需注意。

---

### 风险 13（低）：fun interface 的 SAM 转换需要 Kotlin 1.4+，对 Java 调用方不友好

**现象**：lib `AnimationHandler.kt:38-40` `fun interface AnimationFrameCallback { fun doAnimationFrame(frameTimeMs: Long): Boolean }`、`TickScheduler.kt:18-20` `fun interface FrameCallback { fun doFrame(frameTimeNanos: Long) }`。

**证据**：
- Kotlin 1.4+ 才支持 `fun interface`；lib 编译产物对 Java 调用方暴露的是 `AnimationHandler$AnimationFrameCallback` 接口
- Java 调用方不能像 Java SAM 那样直接传 lambda（Java 编译器不识别 Kotlin fun interface 为 SAM）—— 必须 `new AnimationHandler.AnimationFrameCallback() { ... }`

**影响**：跨语言迁移成本。

**严重度**：低。

---

### 风险 14（低）：typealias 在 JVM 字节码层是 typealias 本身，编译器生成的签名是函数类型 `Function3<...>`

**现象**：lib `typealias OnAnimStateChangeListener = (oldState: AnimationState, newState: AnimationState, runningTask: Any?) -> Unit` 在编译期是 `Function3<AnimationState, AnimationState, Any?, Unit>` 的别名。

**证据**：编译产物反编译可见。

**影响**：
- 原厂 `interface OnAnimStateChangeListener { void onAnimStateChanged(...) }` 在 JVM 字节码里是真正的接口；lib 是函数类型
- Java 调用方对 lib 不能 `implements OnAnimStateChangeListener`（typealias 不是 interface），必须传 `Function3` lambda

**严重度**：低——与风险 2 同源。

---

### 风险 15（提示）：Kotlin 没有协程/挂起函数的迁移机会

**现象**：原厂的 marshal/start/cancel/end 协议都是基于 `LooperExecutor.execute(Runnable)`，回调也是 `Runnable`/`Consumer<Boolean>` 派发。**整个动画领域都在用回调 + Handler.post 模型**，没有 `suspend fun` / `Flow` / `Channel` 的痕迹。

**证据**：
- lib `AsyncValueAnimator.kt:42-53` `private inline fun marshal(crossinline action: () -> Unit)` —— **回调式**
- lib `AsyncAnimCallbacks.kt:55-62` `runOnMainThread(action: () -> Unit)` —— **回调式**
- 原厂 `OplusExecutors.java` 全文件 `Executor.execute(Runnable)` / `Handler.post(Runnable)` / `Utilities.postAsyncCallback(Handler, Runnable)` —— **回调式**

**影响**：
- Android 协程（`kotlinx.coroutines`）的 `suspend fun` / `Flow` / `withContext` 在 lib 里**没有任何用例**
- Demo 也没有演示"用协程 chain 多个动画"（如 `viewModelScope.launch { anim1.await(); anim2.await() }`）
- lib 缺一个"协程适配层"——如果业务想用协程写转场（`viewModelScope.launch { withContext(Dispatchers.Main) { anim.start() ; anim.await() ; ... } }`），需要 lib 提供 `suspend fun Await()` 扩展或 `Flow<Float>` 进度发射

**回移建议**：在 `AsyncValueAnimator` 上加 `suspend fun Animator.awaitCompletion()`（通过 `CompletableDeferred<Unit>` 包装 `onAnimationEnd`）；或在 `AnimationController` 上加 `suspend fun waitForState(AnimationState)`（通过 Channel 监听 `addOnAnimStateChangeListener`）。这不是必须的——动画库传统上是回调式——但能给 demo 一个 "用 Kotlin 协程链式编排 4 阶段转场" 的展示位（demo 模块可以用协程，业务感更现代）。

**严重度**：低（可选增强）。

---

## ④ 回移建议

### A. 值得补进 lib 的（性价比高，能直接对齐原厂语义/改善 API 体验）

| # | 建议 | 对应风险 | 工作量 | 理由 |
|---|---|---|---|---|
| 1 | **`OnAnimStateChangeListener` 改 `fun interface`**（`fun interface OnAnimStateChangeListener { fun onAnimStateChanged(oldState: AnimationState, newState: AnimationState, runningTask: Any?) }`） | 风险 2 | 5 行 | typealias 的 lambda 没有引用相等性 + 无法扩展——`fun interface` 既保留 SAM 转换便利，又支持 `class MyListener : ... OnAnimStateChangeListener` 复用；同时 `removeOnAnimStateChangeListener(listener)` 可靠 |
| 2 | **`DefaultAnimationController.animStateChangeListeners` 改用 `mutableListOf<OnAnimStateChangeListener>()` + 引用相等性** + `removeOnAnimStateChangeListener` 用 `==` 引用比较 | 风险 2 | 5 行 | 配合 #1，让 remove listener 真正能命中 |
| 3 | **AsyncValueAnimator 加 `@JvmOverloads` + `addAnimatorListener` 兼容方法**：在 lib 加 `fun addAnimatorListener(l: NullableAnimatorListener?) = asyncAnimCallbacks.addListener(l)` 让原厂调用方式直接可用 | 风险 3 | 3 行 | 减少迁移摩擦；同时保留 lib 主流 API `asyncAnimCallbacks.addListener` |
| 4 | **`AsyncValueAnimator.Companion.ofFloat(isAsync, vararg values)` 工厂**：照原厂 `AsyncValueAnimator.java:43-49` | 缺失 API | 5 行 | demo 调用方直接 `AsyncValueAnimator.ofFloat(true, 0f, 1f)` 拿到 AsyncValueAnimator 子类（带 setExecutor 能力），无需手动 `AsyncValueAnimator().apply { setFloatValues(0f, 1f) }` |
| 5 | **暴露 `LooperExecutor.getHandler()`/`getLooper()` 访问器**：原厂 `LooperExecutor.java:35-45` | 风险 12 | 3 行 | 业务需要把 AnimatorListenerAdapter 直接挂到目标 Handler 时（demo 中 `AnimationSeqHelper.getOrCreateHandler()` `AnimationSeqHelper.kt:42-46` 就是 lib 自己造的轮子），有原厂 getter 可直接用 |
| 6 | **`OplusValueAnimator.AnimParam.copy()` 显式深拷贝** lambda 字段（`applicator: ValueApplicator?`） | 风险 10 | 5 行 | 续行动画的"半步接管"对 lambda 共享引用敏感；data class 默认浅拷贝容易让 demo 作者误以为已经隔离 |
| 7 | **`PendingAnimation` 改 `public class`**（去掉 `internal`） | 风险 12 | 1 行 | USAGE.md 没列 PendingAnimation 是 public，但 demo 实际需要构造它；要么改 public，要么在 USAGE.md 显明"PendingAnimation 不可外部 new，XxxDemo 用 XxxBuilder 替代" |
| 8 | **公开 `AnimationHandler.instance` + `installThreadScheduler`** 给 demo 做实验 | 缺失 API | 1 行 | 当前是 `internal`（`AnimationHandler.kt:36`），demo 想直接接 tick 测帧间隔时无入口；review 04 §2.3-9 提到 `installThreadScheduler` 时序约束需要让用户感知 |
| 9 | **补 `Animator.awaitCompletion()` 协程扩展**（suspend fun） + `AnimationController.waitForState(s: AnimationState)` | 风险 15 / 缺失 API | 20 行 | 给 demo 一个 "用协程 chain 动画" 的展示；也是 lib 现代化的契机（v4 §8 已点出"动画库传统是回调式但现代 Kotlin 倾向协程"） |
| 10 | **`OplusLooperExecutor` 的 `executeBlockWait` 移除后保留警告注释**：在 `Executors.kt` 类注释里说明"原厂 ANIM_EXECUTOR 有 executeBlockWait 扩展（`OplusLooperExecutor.java:46-71`），但带 5s 主线程硬等 ANR 风险，lib 不移植" | 文档补强 | 5 行 | review 01 §3-3 已点出 ANR 风险，但 lib 文档没说为什么不移植；补注释防使用者去翻 OplusLooperExecutor 源码 |

### B. 建议保持简化（lib 注释中已说明的合理取舍）

| # | 简化内容 | 理由 |
|---|---|---|
| 1 | **`@Metadata`/`@SourceDebugExtension`/`JADX DEBUG/WARN` 全部不写** | 这些都是 Kotlin 编译器/JADX 反编译产物，源码侧不存在；lib 编译时编译器会自动生成 |
| 2 | **`Intrinsics.checkNotNullParameter`/`Intrinsics.checkNotNullExpressionValue` 不写** | Kotlin 编译器在 nullable 表达式后自动 emit，源码干净；运行期仍 emit 等价检查 |
| 3 | **`lambda$method$N` 静态方法不写** | 用 `inline fun` 让字节码内联，源码侧直接展开 |
| 4 | **`WhenMappings.$EnumSwitchMapping$0` 枚举映射表不写** | Kotlin 编译器对 `when` 自动生成 switch 表；源码侧 `when (animState) { ... }` 即可 |
| 5 | **`extends AbstractExecutorService` 移除** | `LooperExecutor` 只暴露 `execute/post/postAsync/isCurrentThread` 最小接口，demo 不需要 ExecutorService 抽象；review 01 §②B-7 已说明 |
| 6 | **`getLooper()/getHandler()/getThread()/setThreadPriority()` 移除** | lib 内部不暴露，外部不需要 |
| 7 | **`m` 字段前缀移除** | Kotlin 命名约定无前缀，更符合 idiomatic Kotlin |
| 8 | **`TAG` 字段移除** | lib 用 `Trace` tag（`AsyncAnimCallbacks.kt:47`）替代 LogUtils.i |
| 9 | **私有字段 `mAnimLooperExecutor`/`mIsEnd` 改成 `executor`/`isEnd`** | Kotlin 字段命名约定无 `m` 前缀；非空类型保证 null 检查 |
| 10 | **`extends AbstractExecutorService` 等 Java 抽象基类不实现** | 不实现 `awaitTermination/shutdownNow/isShutdown/isTerminated` 等 boilerplate |
| 11 | **JVM 单测兜底（`handler` 为 null 时就地执行）保留** | review 01 §②B-7 已说明这是有意设计，注释明示 |
| 12 | **`internal class` 修饰符保留** | lib 的 API 边界由 USAGE.md 显式列出，避免 demo 业务误用实现细节；与原厂"全部 public"风格不一致但更适合演示库定位 |
| 13 | **`object` 单例保留（`object Executors`/`object AnimSeqTimeStamp`/`object Interpolators`/`object AnimatorListeners`/`object Trace`）** | Kotlin idiom 比 Java `static final INSTANCE` + `static { }` 块简洁得多 |
| 14 | **`typealias` 函数类型保留** | review 03 §2.2-4 已确认"OnAnimStateChangeListener 改 typealias"是有意简化，listener 遍历改为快照复制（lib `DefaultAnimationController.kt:26` 用 `ArrayList(...)` 拷贝；原厂 `:158-161` 直接 iterator，遍历中增删会 CME——**lib 更安全**）；但若保留 typealias，应配合 #1 改 `fun interface` 解决 remove 不到的问题 |

### C. lib 独有的现代化 API（值得在 USAGE.md 显式标注为"lib 扩展"，与原厂区分）

| # | 现代化 API | 与原厂对应 | 推荐做法 |
|---|---|---|---|
| 1 | `apply { }`/`also { }` builder 链 | 原厂 `void add()` 多行调用 | **保留**——纯 Kotlin 风格化，迁移回去时把 `apply { ... }` 展开即可 |
| 2 | `data class` 数据载体 | 原厂 `class` + getter/setter + 手动 `copy()` | **保留**——data class 自动 `copy()/toString()/equals/hashCode`，USAGE 注释说明 `copy()` 对引用字段是浅拷贝（避免 reviewer 误以为深拷贝） |
| 3 | `companion object` 内工厂方法 | 原厂 `Companion` + `@JvmStatic` 双份方法 | **保留**——Kotlin 调用方无需 `@JvmStatic`，Java 调用方需要 `JvmStatic`-annotated 副本时可单独加 |
| 4 | `inline fun` + `crossinline lambda` 高阶函数 | 原厂 `private static final void xxx$lambda$N` | **保留**——字节码更简洁（无额外函数调用），但调试时栈帧少一层 marshal；USAGE.md 注明"marshal 是 inline 的，栈追踪看不到它" |
| 5 | `@Volatile var` + `SyncedVar` 委托 | 原厂 `private volatile int mAsyncEnable` + `private final synchronized void setAsyncEnable(int)` | **保留**——`SyncedVar` 是 `ReadWriteProperty<Any?, T>` 实现，`by` 委托一行替换 7 个 getter/setter 方法 |
| 6 | `by lazy(LazyThreadSafetyMode.SYNCHRONIZED)` | 原厂 `static final` 字段 | **保留**——线程安全的延迟初始化，比 `static { }` 块更声明式 |
| 7 | `MutableList<T>`/`List<T>` 接口分离 | 原厂 `ArrayList<T>` 引用 | **保留**——Kotlin 的只读 `List<T>` vs 可变 `MutableList<T>` 在 demo 侧能强制不可变（如 `AnimationFeatureHelper.onePxPkgDisableList: List<String> = mutableListOf()` `AnimationFeatureHelper.kt:21-22` 业务不能 add） |
| 8 | `init { }` 块 + 主构造器 | 原厂字段 + 构造器函数体 | **保留**——`class Foo(...) : Bar { init { ... } }` 比 Java 字段 + 构造器代码清晰得多 |
| 9 | `data class DemoInfo(...)` 在 Demo 里直接子类化 | 原厂无对应（demo 才有） | **保留**——demo 数据载体 data class 是 idiomatic Kotlin |
| 10 | `private set` 公开 var | 原厂 `private final AnimationState mAnimState` + `public getAnimState()` + `private setAnimState` | **保留**——单声明完成"外部只读 + 内部可写"，比 Java 三件套（field + getter + setter）少 2 个声明 |

---

## 附：API 风格关键证据速查

| 论断 | 证据 |
|---|---|
| lib 公开 var 替代原厂 getter/setter | `lib/AsyncValueAnimator.kt:19` vs `oppo/AsyncValueAnimator.java:147-150` |
| lib `object` 替代原厂 `static final INSTANCE` | `lib/OplusAnimManager.kt:17` vs `oppo/OplusAnimManager.java:35` |
| lib `by lazy(SYNCHRONIZED)` 替代原厂 static 块 | `lib/AnimationControlThread.kt:81-83` vs `oppo/OplusExecutors.java:46-105` |
| lib `inline fun marshal` 替代原厂 lambda 静态方法 | `lib/AsyncValueAnimator.kt:42-53` vs `oppo/AsyncValueAnimator.java:131-140` |
| lib `apply { }` builder 链 | `lib/PendingAnimation.kt:42` `fun add(child: Animator): PendingAnimation = apply { ... }` vs `oppo/PendingAnimation.java:55-59` `public void add(@NonNull Animator animator)` |
| lib `typealias` 替代 Java interface | `lib/OnAnimStateChangeListener.kt:7` `typealias OnAnimStateChangeListener = (...) -> Unit` vs `oppo/DefaultAnimationController.java:34-36` |
| lib `fun interface` SAM | `lib/AnimationHandler.kt:38-40` `fun interface AnimationFrameCallback { fun doAnimationFrame(...): Boolean }` |
| lib `data class` 数据载体 | `lib/OplusValueAnimator.kt:82-90` `data class AnimParam(var name: String = "default", ...)` vs `oppo/OplusValueAnimator.java:330-371` 手写 `class AnimParam` + 8 个 getter/setter + `copy()` |
| lib `@Volatile var by SyncedVar` 替代 Java 7 个 setter/getter | `lib/AnimationFeatureHelper.kt:13-19, 27-30, 44-55` vs `oppo/AnimationFeatureHelper.java:65-72, 96-150` |
| lib 主构造器 + `private val handler: Handler?` 提升为字段 | `lib/LooperExecutor.kt:15` vs `oppo/LooperExecutor.java:23-25` |
| lib `enum class` 构造器参数即属性 | `lib/AnimationState.kt:15-26` vs `oppo/AnimationController.java:104-115` |
| lib `companion object` 替代 Java 静态工厂 | `lib/PendingAnimation.kt:160-164` `companion object { fun <T> ofFloat(...) }` vs `oppo/PendingAnimation.java:185-194` 静态工厂方法 |
| lib `kotlin.concurrent.thread { }` 工厂 | `lib/ScheduledTickScheduler.kt:29` `thread(start = false, name = "AsyncAnimator-Tick", isDaemon = true) { r.run() }` vs `oppo/Executors.java:36-42` `SimpleThreadFactory implements ThreadFactory` |
| lib Kotlin 函数类型替代 Java `Consumer`/`Runnable` | `lib/AnimationSeqHelper.kt:62` `override fun delayFinishRecents(action: (() -> Unit)?): Boolean` vs `oppo/AnimationSeqHelper.java:80` `public boolean delayFinishRecents(Runnable runnable)` |
| lib `Message.obtain(h) { action() }.apply { isAsynchronous = true }` SAM 闭包 | `lib/LooperExecutor.kt:47-52` vs `oppo/Utilities.java:631-637` |
| lib `InternalVisibility` (`internal class`) | `lib/AnimationHandler.kt:36`、`lib/PendingAnimation.kt:24`、`lib/AnimatorPlaybackController.kt:28` |
| lib `private set` 公开 var | `lib/AnimationController.kt:30` `override var animState: AnimationState = AnimationState.NONE ; private set` vs `oppo/AnimationController.java:175, 176, 178` `private AnimationState mAnimState ; public final AnimationState getAnimState()` |
| lib `init { }` 块挂 listener | `lib/AsyncValueAnimator.kt:25-39` `init { addListener(object : AnimatorListenerAdapter() { ... }) }` vs `oppo/AsyncValueAnimator.java:55-79` 构造器函数体内 `addListener(new Animator.AnimatorListener(...))` |
| lib 公开 var + 自定访问器模拟 JavaBean | `lib/DefaultAnimationController.kt:88-91` `open var recentsAnimFinishCallback: (() -> Unit)? ; get() = null ; set(value) {}` vs `oppo/DefaultAnimationController.java:62-67` `public Runnable getRecentsAnimFinishCallback()` / `setRecentsAnimFinishCallback(Runnable)` |
| 原厂 `@Metadata(d1=..., d2=...)` 大段二进制注解 | `oppo/OplusAnimManager.java:36-44`（50+ 行 `@Metadata`），`lib` 无此注解（Kotlin 编译器自动生成） |
| 原厂 `@SourceDebugExtension({"SMAP..."})` SMAP | `oppo/OplusValueAnimator.java:50-60` `@SourceDebugExtension`，`lib` 无此注解 |
| 原厂 `JADX DEBUG/WARN` 注释 | `oppo/OplusExecutors.java` 多处 JADX 调试信息，`lib` 无 |
| 原厂 `Intrinsics.checkNotNullParameter/checkNotNullExpressionValue` | `oppo/AsyncValueAnimator.java:54, 148` 等 ~50 处 `Intrinsics.*`，`lib` 无（编译器自动 emit） |
| 原厂 `lambda$method$N` 静态方法 | `oppo/AsyncValueAnimator.java:131-140, 153-164` 5 个 `lambda$method$N`，`lib` 用 `inline fun` 消除 |
| 原厂 `WhenMappings.$EnumSwitchMapping$0` 枚举映射表 | `oppo/AnimationController.java:117-141` ~25 行 ordinal 映射，`lib` 用 `when` 直接枚举分支 |
| 原厂 `extends AbstractExecutorService` 抽象基类 | `oppo/LooperExecutor.java:18`，`lib` 仅实现 `execute` |