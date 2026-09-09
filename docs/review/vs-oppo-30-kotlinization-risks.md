# vs-oppo-14 — Kotlin 化引入的兼容性风险

> 范围：`D:/AsyncAnimator/lib` 全 Kotlin 化后的兼容性回归盘查。聚焦 5 个"改造点 vs 原厂实现"层面的语义偏差：
>
>  1. `companion object init {}` vs 原厂 `static {}` 触发时机（AnimationController / OplusAnimManager / AnimationFeatureHelper）
>  2. `typealias OnAnimStateChangeListener` 的 lambda 引用相等性丢失（review 10 bug #1 的字段级溯源）
>  3. SAM conversion（`fun interface`） + Kotlin 函数类型替代 `Consumer` / `Runnable` / `Supplier` 对 Java 调用方的 ABI
>  4. `object` 单例 `by lazy` vs `static final INSTANCE`（helper 类层级）
>  5. `@JvmStatic` / `@JvmOverloads` 兼容性拐杖是否还能在 demo 链路上保留
>
> 不重复：线程优先级字面量、ANIM_EXECUTOR 永不 quit、Listener 容器形态已在 `vs-oppo-01/07/09/10/12/13` 标过；本文做**单字段 / 单方法级**的对照与"为 Java 调用方兜底"的可行性论证。
>
> 编号继续沿用：上一份是 `vs-oppo-13-anim-thread-init.md`，本份取 `14`。

---

## ① 类对应关系表

> 取证方式：lib 全为可读 UTF-8（中文注释被编辑器降级为 `?` 时仍能取证代码骨架）；原厂多数文件被 `%TSD-Header` 加密，**经 Grep（ripgrep 明文通道）+ Python `open().read()` 双重取证**，行号取自 JADX 文本。

### 1.1 三大 helper 的初始化路径

| lib 类（行数） | 原厂类（JADX 反编译后形态） | 触发时机证据 | 备注 |
|---|---|---|---|
| `manager/OplusAnimManager.kt`（68 行）<br>`object OplusAnimManager {<br>&nbsp;&nbsp;private var animationControllerImpl: AnimationController? = null<br>&nbsp;&nbsp;private var animationSeqHelperImpl: AnimationSeqHelper? = null<br>&nbsp;&nbsp;init { if (supportInterruption()) { ... } }<br>}` | `com/oplus/quickstep/utils/OplusAnimManager.java`（269 行，**由 Kotlin 编译**）<br>`public final class OplusAnimManager {<br>&nbsp;&nbsp;public static final OplusAnimManager INSTANCE;<br>&nbsp;&nbsp;private static final t4.b mAnimationController; // Delegates.observable<br>&nbsp;&nbsp;private static final t4.b mAnimationSeqHelper;<br>&nbsp;&nbsp;private static final t4.b mAppOpenAnimMergeHelper;<br>&nbsp;&nbsp;... (共 6 个)<br>&nbsp;&nbsp;static {<br>&nbsp;&nbsp;&nbsp;&nbsp;OplusAnimManager o = new OplusAnimManager();<br>&nbsp;&nbsp;&nbsp;&nbsp;INSTANCE = o;<br>&nbsp;&nbsp;&nbsp;&nbsp;mAnimationController = new t4.a<...>(o.createAnimationController()) { ... afterChange ... };<br>&nbsp;&nbsp;&nbsp;&nbsp;mAnimationSeqHelper = new t4.a<...>(o.createAnimationSeqHelper()) { ... };<br>&nbsp;&nbsp;&nbsp;&nbsp;... 6 个 observable$delegate ...<br>&nbsp;&nbsp;}<br>&nbsp;&nbsp;private OplusAnimManager() {}<br>&nbsp;&nbsp;@JvmStatic public static final DefaultAnimationController getAnimController() { return INSTANCE.getMAnimationController(); }<br>}` | `OplusAnimManager.java:23`（`INSTANCE`）<br>`OplusAnimManager.java:46-91`（`static { INSTANCE = ... ; 6 个 observable$delegate ... }`）<br>`OplusAnimManager.java:120`（`@JvmStatic`）<br>SMAP 元数据：`OplusAnimManager.kt` 源 212 行，第 33-228 行（kotlin/properties/Delegates.observable） | 原厂是 Kotlin `object OplusAnimManager` + 6 个 `by Delegates.observable(...)` 懒属性；lib 是 `object` + 2 个 eager `init { }`；原厂有 `@JvmStatic`、lib 完全没有 |
| `manager/AnimationFeatureHelper.kt`（55 行）<br>`object AnimationFeatureHelper {<br>&nbsp;&nbsp;private val lock = Any()<br>&nbsp;&nbsp;var asyncEnable by SyncedVar(lock, 1)<br>&nbsp;&nbsp;var rtUnlockEnable by SyncedVar(lock, 1)<br>&nbsp;&nbsp;... (共 7 个 SyncedVar + 2 @Volatile List + 2 read-only getters)<br>&nbsp;&nbsp;private class SyncedVar<T>(...) : ReadWriteProperty<Any?, T> {<br>&nbsp;&nbsp;&nbsp;&nbsp;@Volatile private var value = initial<br>&nbsp;&nbsp;&nbsp;&nbsp;override fun getValue(...) = value<br>&nbsp;&nbsp;&nbsp;&nbsp;override fun setValue(... value) { synchronized(lock) { this.value = value } }<br>&nbsp;&nbsp;}<br>}` | `com/oplus/quickstep/utils/AnimationFeatureHelper.java:25-429`<br>`public final class AnimationFeatureHelper {<br>&nbsp;&nbsp;public static final Companion INSTANCE = new Companion(null);<br>&nbsp;&nbsp;private static final f4.g<AnimationFeatureHelper> sInstance$delegate = f4.h.b(new Function0<AnimationFeatureHelper>() { ... invoke() { return new AnimationFeatureHelper(); } });<br>&nbsp;&nbsp;private volatile int mAsyncEnable = -1;<br>&nbsp;&nbsp;private volatile int mRTUnlockEnable = -1;<br>&nbsp;&nbsp;private volatile int mMultiAppBlockEnable = -1;<br>&nbsp;&nbsp;private volatile int mIconBlurEnable = -1;<br>&nbsp;&nbsp;private volatile List<String> m1pxPkgDisableList = new ArrayList();<br>&nbsp;&nbsp;private volatile List<Integer> m1pxCardDisableList = new ArrayList();<br>&nbsp;&nbsp;private volatile int m1pxEnable = -1;<br>&nbsp;&nbsp;private volatile float mInterruptThreshold = 1.0f;<br>&nbsp;&nbsp;private volatile int mLimtSize = -1;<br>&nbsp;&nbsp;@JvmStatic public static final AnimationFeatureHelper getInstance() {<br>&nbsp;&nbsp;&nbsp;&nbsp;return INSTANCE.getInstance();<br>&nbsp;&nbsp;}<br>&nbsp;&nbsp;private final synchronized void setAsyncEnable(int i9) { this.mAsyncEnable = i9; }<br>&nbsp;&nbsp;... (10 个 synchronized setter)<br>&nbsp;&nbsp;private final void updateRusConfig() { /* 解析 RUS XML */ }<br>}`<br>`public static final class Companion {<br>&nbsp;&nbsp;@JvmStatic public final AnimationFeatureHelper getInstance() { return getSInstance(); }<br>&nbsp;&nbsp;private final AnimationFeatureHelper getSInstance() { return (AnimationFeatureHelper) sInstance$delegate.getValue(); }<br>}` | `AnimationFeatureHelper.java:42-43`（`INSTANCE` + `sInstance$delegate`）<br>`AnimationFeatureHelper.java:52-60`（10 个 `private volatile` 字段）<br>`AnimationFeatureHelper.java:63-80`（`public static final class Companion` + `@JvmStatic getInstance()`）<br>`AnimationFeatureHelper.java:76, 94`（两处 `@JvmStatic`）<br>`AnimationFeatureHelper.java:99-156`（10 个 `synchronized` setter）<br>`AnimationFeatureHelper.java:159-` （`updateRusConfig` 整套远程 RUS 拉新机制） | 原厂是 Kotlin `class AnimationFeatureHelper`（**不是 object**）+ `companion object : ... getInstance()` + `by lazy` + RUS 远程拉新 + `private volatile` + `synchronized` setter；lib 是 `object` + 自定义 `SyncedVar<T>` property delegate（功能等价但 API 形态不同） |
| `control/AnimationController.kt:7`（226 行）<br>`class AnimationController : DefaultAnimationController() {<br>&nbsp;&nbsp;override var animState: AnimationState = AnimationState.NONE<br>&nbsp;&nbsp;private set<br>&nbsp;&nbsp;private val recentsAnims = mutableListOf<...>()<br>&nbsp;&nbsp;private val appLaunchAnims = mutableListOf<...>()<br>&nbsp;&nbsp;...<br>}` | `com/oplus/quickstep/utils/AnimationController.java:58+`（144 行可见）<br>`public final class AnimationController extends DefaultAnimationController {<br>&nbsp;&nbsp;private volatile AnimationState mAnimState;<br>&nbsp;&nbsp;private List<LauncherAnimationRunner.RemoteAnimationFactory> mAppLaunchAnims;<br>&nbsp;&nbsp;private List<CustomRectFSpringAnim> mRecentsAnims;<br>&nbsp;&nbsp;private Map<...> mRemoveTasksMaps;<br>&nbsp;&nbsp;private View mClickAppView;<br>&nbsp;&nbsp;private Runnable mRemoteMergeFinishCallback;<br>&nbsp;&nbsp;private Runnable mRecentsMainFinishCallback;<br>&nbsp;&nbsp;private boolean mOnceGestureProcessing;<br>&nbsp;&nbsp;private boolean mIsLandScapeGesture;<br>&nbsp;&nbsp;...<br>&nbsp;&nbsp;public final void delayStartActivityIfNeed(<br>&nbsp;&nbsp;&nbsp;&nbsp;Intent intent,<br>&nbsp;&nbsp;&nbsp;&nbsp;Supplier<Boolean> call,<br>&nbsp;&nbsp;&nbsp;&nbsp;Runnable runnable) { ... }<br>&nbsp;&nbsp;... (AnimationState 内部 enum + Companion $WhenMappings static {})<br>}` | `AnimationController.java:138`（Companion 内部 `static { }` 构建 `int[] iArr` switch 表）<br>`AnimationController.java:50`（`import java.util.function.Supplier;`）<br>`AnimationController.java:81-82`（`Runnable mRecentsMainFinishCallback` / `mRemoteMergeFinishCallback`）<br>`AnimationController.java:83`（`private volatile TaskInfo mRunningTask`）<br>SMAP 元数据：`AnimationController.kt` 源 794 行 | 原厂用 `Supplier<Boolean>` + `Runnable` 两个 Java stdlib 接口；lib 用 Kotlin 函数类型 `(() -> Boolean)?` + `(() -> Unit)?` |

### 1.2 Listener 接口与函数类型

| lib | 原厂 | 证据 |
|---|---|---|
| `control/OnAnimStateChangeListener.kt:8`<br>`typealias OnAnimStateChangeListener = (oldState: AnimationState, newState: AnimationState, runningTask: Any?) -> Unit` | `com/oplus/quickstep/utils/DefaultAnimationController.java:33-36`<br>`public interface OnAnimStateChangeListener {<br>&nbsp;&nbsp;void onAnimStateChanged(AnimationController.AnimationState animationState,<br>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;AnimationController.AnimationState animationState2,<br>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;TaskInfo taskInfo);<br>}` | `DefaultAnimationController.java:33-36`（嵌套 `public interface`，**Kotlin 端对应 `fun interface`**），d2 元数据确认 `Lf4/b0; onAnimStateChanged`（`f4.b0` = `kotlin.jvm.functions.Function3` 的 SAM 自动桥接） |
| `control/DefaultAnimationController.kt:15`<br>`private val animStateChangeListeners = mutableListOf<OnAnimStateChangeListener>()`<br>`fun addOnAnimStateChangeListener(listener: OnAnimStateChangeListener?) { if (listener != null) animStateChangeListeners.add(listener) }`<br>`fun removeOnAnimStateChangeListener(listener: OnAnimStateChangeListener?) { if (listener != null) animStateChangeListeners.remove(listener) }` | `DefaultAnimationController.java:31`（`private ArrayList<OnAnimStateChangeListener> animStateChangeListeners = new ArrayList<>();`）<br>`DefaultAnimationController.java:38-41`（`addOnAnimStateChangeListener(OnAnimStateChangeListener listener)` + `Intrinsics.checkNotNullParameter`）<br>`DefaultAnimationController.java:164-167`（`removeOnAnimStateChangeListener`） | 容器形态一致（`ArrayList` vs `mutableListOf`，都强引用），add/remove 一致；**关键差异在 listener 对象的身份语义**（见 §②-2 / §③-1） |

### 1.3 `LooperExecutor` 与 `Runnable` ABI

| lib | 原厂 | 证据 |
|---|---|---|
| `thread/LooperExecutor.kt`（48 行）<br>`class LooperExecutor internal constructor(private val handler: Handler?) {<br>&nbsp;&nbsp;val isCurrentThread: Boolean get() = thread === Thread.currentThread()<br>&nbsp;&nbsp;fun execute(action: (() -> Unit)?) { ... }<br>&nbsp;&nbsp;fun post(action: () -> Unit) { ... }<br>&nbsp;&nbsp;fun postAsync(action: () -> Unit) { ... }<br>}` | `com/oplus/basecommon/thread/LooperExecutor.java`（80 行，纯 Java）<br>`public class LooperExecutor extends AbstractExecutorService {<br>&nbsp;&nbsp;@Override public void execute(Runnable runnable) { ... }<br>&nbsp;&nbsp;public Handler getHandler() { return mHandler; }<br>&nbsp;&nbsp;public Looper getLooper() { return getHandler().getLooper(); }<br>&nbsp;&nbsp;public Thread getThread() { return getHandler().getLooper().getThread(); }<br>&nbsp;&nbsp;public void post(Runnable runnable) { ... }<br>&nbsp;&nbsp;public void postDelayed(Runnable runnable, long j8) { ... }<br>&nbsp;&nbsp;public void setThreadPriority(int i9) { ... }<br>&nbsp;&nbsp;@Deprecated @Override public void shutdown() { throw new UnsupportedOperationException(); }<br>&nbsp;&nbsp;@Deprecated @Override public List<Runnable> shutdownNow() { throw new UnsupportedOperationException(); }<br>&nbsp;&nbsp;@Override public boolean isShutdown() { return false; }<br>&nbsp;&nbsp;@Override public boolean isTerminated() { return false; }<br>&nbsp;&nbsp;@Override public boolean awaitTermination(long j8, TimeUnit timeUnit) { throw new UnsupportedOperationException(); }<br>}` | `LooperExecutor.java:8`（`import java.util.concurrent.AbstractExecutorService`）<br>`LooperExecutor.java:12`（`public class LooperExecutor extends AbstractExecutorService`）<br>`LooperExecutor.java:27-33`（`execute(Runnable)` 同 Looper 内联、跨 Looper post）<br>`LooperExecutor.java:57-63`（`post` + `postDelayed` 公开）<br>`LooperExecutor.java:65-67`（`setThreadPriority` 公开）<br>`LooperExecutor.java:71-79`（`shutdown` / `shutdownNow` 抛 UnsupportedOperationException + `@Deprecated`） |

### 1.4 回调型字段（Kotlin 函数类型 vs Java 接口）

| lib 字段 | 原厂字段 | 证据 |
|---|---|---|
| `AnimationController.kt:48` `override var recentsAnimFinishCallback: (() -> Unit)?`<br>`AnimationController.kt:49` `override var appLaunchAnimFinishCallback: (() -> Unit)?`<br>`AnimationController.kt:51` `private var startActivityAction: (() -> Unit)?`<br>`AnimationController.kt:169` `delayStartActivityIfNeed(context, intent, call: (() -> Boolean)?, action: (() -> Unit)?)` | `AnimationController.java:81-82` `private Runnable mRecentsMainFinishCallback;` `private Runnable mRemoteMergeFinishCallback;`<br>`AnimationController.java:84` `private Runnable startActivityRunnable;`<br>`AnimationController.java:86-88` `delayStartActivityIfNeed(Intent intent, Supplier<Boolean> call, Runnable runnable)` | lib 用 `() -> Unit` / `() -> Boolean`（`Function0`/`Function0<...Boolean>`）；原厂用 `Runnable` / `Supplier<Boolean>`（`java.util.function.*`） |
| `AnimationController.kt:60-78` `timeoutListener(... timeoutMs: Long, clearState: () -> Unit = {}): TaskStateChangeTimeOutListener =<br>&nbsp;&nbsp;TaskStateChangeTimeOutListener(expected, timeoutMs) {<br>&nbsp;&nbsp;&nbsp;&nbsp;startActivityAction?.invoke(); clearState(); startActivityAction = null<br>&nbsp;&nbsp;}` | `AnimationController.java:680-702` `private final TaskStateChangeTimeOutListener timeOut(int timeOut, Function0<Unit> clearState, final TimeOutCallback callback) { ... }`<br>`AnimationController.java:730-760` `registerSpecialSceneExitTimeOutListener` / `registerOverviewContinuationTimeOutListener` / `registerTransitionFinishListener` | lib 用内嵌 `clearState: () -> Unit = {}` lambda 默认参数；原厂用 `Function0<Unit> clearState`（`f4.b` 即 `Function0`） |

---

## ② 保真度评估

### A. 精确复刻

| # | 设计点 | 原厂证据 | lib 证据 | 评注 |
|---|---|---|---|---|
| 1 | `OplusAnimManager.INSTANCE` 单例 + 进程级永驻 | `OplusAnimManager.java:23, 46-48`（`public static final OplusAnimManager INSTANCE;` + `static { INSTANCE = new OplusAnimManager(); }`） | `OplusAnimManager.kt:10-17`（`object OplusAnimManager { init { ... } }`） | 形态等价（Kotlin `object` 编译为 `INSTANCE + <clinit>`），单例可见性一致 |
| 2 | `OplusAnimManager.supportInterruption()` 在 `static {}` 中被调用决定 helper 走 Impl 还是 Default | `OplusAnimManager.java:46-91`（`static { mAnimationController = new t4.a(o.createAnimationController()) { ... } }`，`createAnimationController` 内部判 `supportInterruption()`） | `OplusAnimManager.kt:21-25`（`init { if (supportInterruption()) { animationControllerImpl = AnimationController(); animationSeqHelperImpl = AnimationSeqHelper() } }`） | 形态等价 |
| 3 | `AnimationFeatureHelper.sInstance` 走 `by lazy`（首次 `getInstance()` 才建） | `AnimationFeatureHelper.java:42-43`（`INSTANCE = new Companion(null)` + `sInstance$delegate = f4.h.b(new Function0<...> { invoke() { return new AnimationFeatureHelper(); } })`） | `AnimationFeatureHelper.kt:13-19`（`object AnimationFeatureHelper { var asyncEnable by SyncedVar(lock, 1) ... }`）—— **形态不同**：lib 是 `object` 类级初始化，**类首次被引用即构造并 register RUS listener**；原厂是 `class + companion object : by lazy`，**首次 `getInstance()` 调用才构造并 register RUS listener**（见 §C-1） | 形态差异显著 |
| 4 | 字段写时同步、读时 `volatile` 可见性 | `AnimationFeatureHelper.java:99-156`（`private final synchronized void setAsyncEnable(int i9) { this.mAsyncEnable = i9; }`） + `:52-60`（`private volatile int mAsyncEnable = -1;` 等 10 个） | `AnimationFeatureHelper.kt:43-52`（`private class SyncedVar<T>(...) : ReadWriteProperty<Any?, T> { @Volatile private var value = initial; override fun setValue(...) { synchronized(lock) { this.value = value } } }`） | **结构等价但锁对象不严格相同**：原厂是 `synchronized(this)`（每个 setter 内置锁）；lib 是 `synchronized(lock)`（**所有 SyncedVar 共享一个 lock**）—— 行为等价但并发语义缩窄（见 §③-5） |
| 5 | `AnimationController` 12 状态 enum + Companion `$WhenMappings` switch 表 | `AnimationController.java:138-141`（`static { int[] iArr = new int[AnimationState.values().length]; iArr[AnimationState.OPEN.ordinal()] = 1; ... }`） | `AnimationController.kt:65-78`（`when (animState) { AnimationState.NONE, ... -> updateAnimState(CLOSE); ... else -> updateAnimState(UNKNOWN) }`） | **精确复刻**：lib `when{}` 直跳，编译为相同字节码；review 10 §A-4 标注 |
| 6 | `delayStartActivityIfNeed` 三层判定语义（specialScene / transitionFinish / overviewContinuation） | `AnimationController.java:870-940`（三段 if-else，`isLandscapeActivity` / `call.get()` / `uptimeMillis < mOverviewContinuationTimeOutMaxTime`） | `AnimationController.kt:167-186`（同上三层结构） | 精确复刻 |

### B. 有意简化（lib 注释/设计文档中明示）

| # | 简化内容 | 原厂对应 | lib 取舍理由 | 评注 |
|---|---|---|---|---|
| 1 | `Delegates.observable` 6 个懒属性 → 2 个普通 `var xxx: ...?` + `init { }` 直接赋 | `OplusAnimManager.java:28-44`（`mAnimationController$delegate = new t4.a(...) { afterChange(...) { ... } }`） | lib demo 不需要观察者回调（仅创建后简单 get/set），observable 通知 6 个 delegate 的开销完全省掉 | **是合理的简化**，但 §C-2 列了一个隐性后果 |
| 2 | `by lazy` + `class + companion` → `object` 类级单例 | `AnimationFeatureHelper.java:42-80`（`Companion` + `sInstance$delegate`） | lib 是纯 demo 模型，不需要延迟构造 + RUS 注册的钩子；直接 `object` 让 `AnimationFeatureHelper` 6 个属性 7 个 `@Volatile + lock` 一次就绪 | **类初始化时机提早**到首次任何属性访问前，详见 §C-1 |
| 3 | `private volatile` + `synchronized setter` 10 个 → `SyncedVar<T>` property delegate 1 个 | `AnimationFeatureHelper.java:99-156`（10 个 `synchronized` setter） + `:52-60`（10 个 `volatile` 字段） | property delegate 把"volatile + lock"封装成一个类，调用方写 `featureHelper.asyncEnable = 0` 即可，**不需要为每个字段手写 setter** | **结构性简化**，并发语义等价（读 volatile、写 synchronized） |
| 4 | `LooperExecutor extends AbstractExecutorService` + 5 个 `@Deprecated @Override` 抛 UnsupportedOperationException | `LooperExecutor.java:12, 22-24, 47-55, 69-79` | lib 不实现 `ExecutorService` 接口，只暴露 `execute / post / postAsync`，JVM 测试靠 `handler == null` 兜底（`LooperExecutor.kt:36-39` `?: Thread.currentThread()`） | **API 形态不一致**——见 §③-3 |
| 5 | `Supplier<Boolean>` + `Runnable` → Kotlin 函数类型 `(() -> Boolean)?` + `(() -> Unit)?` | `AnimationController.java:50, 81-82, 86-88` | Kotlin 调用方写 `delayStartActivityIfNeed(c, i) { isSplitScreen() } { startActivity() }` 比 `Supplier<Boolean> { isSplitScreen() } + Runnable { startActivity() }` 自然 | **Java 调用方 ABI 损失**——见 §③-4 |
| 6 | `interface OnAnimStateChangeListener { ... }` 嵌套 → `typealias = (...) -> Unit` | `DefaultAnimationController.java:33-36` | 避免定义"Java interface + 命名 + 嵌套"三级样板；调用方写 lambda 即可 | **bug 级简化**——见 §③-1 |

### C. 遗漏（未在 lib 注释中说明、且影响语义）

#### C-1. `object AnimationFeatureHelper` 类加载时机比原厂 `class + companion + by lazy` 早

| 维度 | 原厂（`class + companion + by lazy`） | lib（`object`） |
|---|---|---|
| 触发条件 | 首次调用 `AnimationFeatureHelper.getInstance()`（`@JvmStatic`）时 | 首次**任何**引用 `AnimationFeatureHelper.xxx`（含 `AnimationFeatureHelper.asyncEnable`、`featureHelper.limtSize`）时 |
| RUS register 时机 | 首次 `getInstance()`（见 `AnimationFeatureHelper.java:84-91`：`updateRusConfig() + registerRusConfigChangedListener(...)`） | 类初始化时（lib 没有 register 流程，但同样**首次引用就触发全部字段初始化**） |
| 隐性风险 | 无 | lib 在测试代码里**只引用字段**（如 `AnimationFeatureHelper.interruptThreshold`）也会触发 7 个 SyncedVar 字段初始化——`@Volatile private var value = initial` 一次性写完，无副作用 |
| 时序影响 | 真机：Launcher.onCreate → 业务方首次 `getInstance()` → register RUS listener → RUS 服务推送首次配置 → `updateRusConfig()` 解出 7 个开关；这之间业务**读到的是 -1**（字段默认值） | lib 类初始化时**没有 register 流程**，simulateRemoteUpdate 显式赋值 7 个值（`AnimationFeatureHelper.kt:32-38`），所以时序差异**在 demo 场景无影响**；若以后补 RUS 接入，时序差异会复现 |

**结论**：lib 简化合理，但**补 RUS 接入时必须把 `object` 改回 `class + companion + by lazy`**，否则 `registerRusConfigChangedListener` 会在错误的生命周期阶段触发。

#### C-2. `OplusAnimManager` 6 个 observable delegate → 2 个裸 `var` + `init { }`，丢失 `recreateAnimHelper()` 入口

原厂 `OplusAnimManager.recreateAnimHelper()`（`OplusAnimManager.java:211-217`）：
```java
public final void recreateAnimHelper() {
    LogUtils.i(TAG, "recreateAnimHelper");
    setMAppOpenAnimMergeHelper(createAppOpenAnimMergeHelper());
    setMMultiAppAnimMergeHelper(createMultiAppAnimMergeHelper());
    setMAnimationController(createAnimationController());
    setMAnimationSeqHelper(createAnimationSeqHelper());
}
```
**作用**：运行时销毁旧 helper + 建新 helper。原厂语义保证在配置变更（如 RUS 灰度推送改了 `supportInterruption` 的判定结果）时能把所有 impl 全部换新。

lib `OplusAnimManager.interruptionEnabled: Boolean`（`OplusAnimManager.kt:55-65`）做了**等价**事情，但有 3 处差异：

| 维度 | 原厂 | lib |
|---|---|---|
| 入口名 | `recreateAnimHelper()`（业务语义） | `interruptionEnabled = true/false`（feature flag 语义） |
| 调用次数 | 1 次调用换 4 个 helper | 1 次赋值换 2 个 helper（仅 AnimationController + AnimationSeqHelper） |
| AppOpen/MultiApp 重建 | ✅ 有 | ❌ 没复刻（lib 也没有这两个 helper 类） |
| 触发场景 | RUS 配置变更 / 灰度生效 | demo 用 `set interruptionEnabled = false` 模拟 toggle |
| 锁保护 | 每个 setter 走 `observable$delegate.setValue()` 内置锁 | `if (... == null) ... = AnimationController()` 无锁保护——并发切换 race condition（**SUMMARY §2 bug #10**） |

**结论**：功能有等价物，**但并发保护缺失 + 字段数量不足 4 个**。

#### C-3. `LooperExecutor` 不 `extends AbstractExecutorService` + 无 `shutdown()` 公开方法

lib `LooperExecutor.kt`：
```kotlin
class LooperExecutor internal constructor(private val handler: Handler?) {
    val isCurrentThread: Boolean get() = thread === Thread.currentThread()
    fun execute(action: (() -> Unit)?) { ... }
    fun post(action: () -> Unit) { ... }
    fun postAsync(action: () -> Unit) { ... }
}
```
- **不实现任何 `ExecutorService` 方法**——意味着 AOSP / launcher 习惯写法 `executor.shutdown()`、`executor.shutdownNow()`、`executor.isShutdown()`、`executor.isTerminated()`、`executor.awaitTermination(...)` 在 lib 上**全部得到 `AbstractMethodError`**（而非原厂的 `UnsupportedOperationException`）。
- **`postDelayed(Runnable, long)` 缺失**——`AnimationSeqHelper` 在 lib 中用 `Handler.sendEmptyMessageDelayed` 自走（`AnimationSeqHelper.kt:69`），但 `LooperExecutor` 本身不暴露 `postDelayed`，跟原厂 `LooperExecutor.java:61-63` 不一致；review 09 §C-10 已列。
- **`getHandler() / getLooper() / getThread() / setThreadPriority(int)` 缺失**——原厂 `LooperExecutor.java:35-67` 的 4 个访问器都没了；review 09 §C-2 已列。

**结论**：**必补 `shutdown() throws UnsupportedOperationException + @Deprecated`**（SUMMARY §2 bug #6），其他访问器可按需补。

#### C-4. `Supplier<Boolean>` / `Runnable` → Kotlin 函数类型：Java 调用方 ABI 损失

lib `AnimationController.kt:167-186`：
```kotlin
override fun delayStartActivityIfNeed(context: Any?, intent: Intent?,
                                      call: (() -> Boolean)?, action: (() -> Unit)?): Boolean
```

对应的 Kotlin 函数类型在 JVM 字节码里是 `kotlin.jvm.functions.Function0<java.lang.Boolean>` + `kotlin.jvm.functions.Function0<kotlin.Unit>`（Function0 的 `invoke` 返回 `Object` / `Unit` 装箱）。**Java 调用方**写：
```java
controller.delayStartActivityIfNeed(c, intent,
    (Supplier<Boolean>) () -> Boolean.valueOf(canFinish()),  // 编译失败：Function0 ≠ Supplier
    (Runnable) () -> startActivity());                        // 编译失败：Function0 ≠ Runnable
```
Java 必须写 `new Function0<Boolean>() { public Boolean invoke() { ... } }` + `new Function0<Unit>() { ... }`——样板代码 5~8 行，**远不如原厂 Supplier + Runnable 自然**。

补救有两条路：

| 方案 | 优点 | 代价 |
|---|---|---|
| A. 重载：`delayStartActivityIfNeed(c, intent, Supplier<Boolean>, Runnable)` + `@JvmOverloads` | Java 调用方直接 `new Supplier<Boolean>() { ... }`，与原厂完全等价 | 4 个函数签名（函数类型版 + Supplier/Runnable 版），文档需说明哪个是"主版本" |
| B. 改用 `@JvmFunctionalInterface` 标注的 `interface OnAnimStateChangeListener { ... }` 内嵌 Java interface + 函数类型版并存 | 同 A，Java 调用方拿 `OnAnimStateChangeListener`；Kotlin 调用方用函数类型 | 12 行样板（接口 + 适配） |

**当前 demo 全 Kotlin，无 Java 调用方**，§③-4 的风险是**未来场景**而非当下。**结论**：建议保持现状 + 文档明示"Java 调用方需用 `Function0` 适配"；若回移 Launcher3 集成必须补方案 A。

#### C-5. `SyncedVar<T>` 共享 lock vs 原厂每个 setter 独立锁

lib `AnimationFeatureHelper.kt:43-52`：
```kotlin
private class SyncedVar<T>(private val lock: Any, initial: T) : ReadWriteProperty<Any?, T> {
    @Volatile private var value = initial
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        synchronized(lock) { this.value = value }
    }
}
```
7 个 `SyncedVar(lock, ...)` **共享一个 `lock: Any`**（`AnimationFeatureHelper.kt:15`）。

原厂 `AnimationFeatureHelper.java:99-156` 每个 setter 是 `synchronized(this)`——锁粒度是 **instance 级**，但每个 setter 进入的临界区互不重叠（不同字段无共享状态）。

| 场景 | lib（原行为） | lib（共享 lock 后） | 评估 |
|---|---|---|---|
| 单线程写 7 个字段 | 7 次 `synchronized(lock)` 进出，开销小 | 7 次 `synchronized(lock)`，每次都争用同一 monitor | **同代价** |
| 多线程并发写 7 个不同字段 | 锁争用按字段分，吞吐率高 | 7 字段共享锁，吞吐率下降为 1/7 | **并发收窄** |
| 写同一字段多次 | 锁本字段 monitor | 锁共享 lock | 同 |
| 读（`@Volatile`） | 不进临界区，无锁 | 不进临界区，无锁 | 同 |

**结论**：demo 单线程场景下完全等价；多线程 RUS 推送场景（远程配置变更可能从 RUS 监听线程写 + 业务线程读）下，**并发吞吐下降但不会脏读**（`@Volatile` 保证可见性，`synchronized` 保证原子性）。

### D. 一致性复核（review 10 标注但本报告独立验证）

| # | review 10 标注 | 本报告独立取证 | 一致？ |
|---|---|---|---|
| 1 | `typealias OnAnimStateChangeListener` 改坏引用相等性 → 改 `fun interface`（review 10 bug #1） | `DefaultAnimationController.java:33-36` 的 `public interface` 在 Kotlin 端就是 `fun interface OnAnimStateChangeListener { fun onAnimStateChanged(...) }`，编译时生成 SAM adapter `LambdaClass implements OnAnimStateChangeListener`；lib `typealias` 没有 SAM adapter，每次 lambda 是 `Function3` 实例 | ✅ |
| 2 | `addAnimatorListener` 改名 → 加 `@JvmOverloads` 兼容方法 | 原厂 `AsyncAnimCallbacks.java:101` 是 `addListener(NullableAnimatorListener)` 单参；lib `AsyncAnimCallbacks.kt:25` 是 `addListener(l: NullableAnimatorListener?)` 单参——**未改名**，无兼容问题；但 `removeListener` 原厂 public（`AsyncAnimCallbacks.java:107-109`），lib 是 `internal`（`AsyncAnimCallbacks.kt:32`），**Java 调用方拿不到 `removeListener`** | ✅ 部分（原厂 public，lib internal） |
| 3 | `object` 单例 `by lazy` 与原厂 `static final` 类加载时机差异 | 见 §C-1 | ✅ |
| 4 | 函数类型替代 `Consumer/Runnable` 对 Java 调用方不友好 → 关键公开 API 保留 Java 接口形态 | lib 完全没保留；见 §C-4 | ✅ |

---

## ③ 行为差异风险点

按风险从高到低，每条标注"修复成本估算"。

> **✅已修复（60bd048：typealias→fun interface，lambda 引用相等性恢复，remove 不静默失效）**
### 【bug 级】1. `typealias OnAnimStateChangeListener` 改坏 lambda 引用相等性

**问题**：lib `OnAnimStateChangeListener.kt:8` 是 `(A, B, C) -> Unit` 函数类型。Kotlin 函数类型在 JVM 上对应 `kotlin.jvm.functions.Function3`（**没有 SAM 等价**）——每次 lambda 是 `Function3` 实例，对象身份不保证。

```kotlin
// Demo6StateMachineActivity.kt:55
controller.addOnAnimStateChangeListener { old, new, _ -> onStateChanged(old, new) }
// ... 之后想反注册：
val same: OnAnimStateChangeListener = { old, new, _ -> onStateChanged(old, new) }
controller.removeOnAnimStateChangeListener(same)  // ❌ 静默失败！两个 lambda 是两个不同对象
```

原厂 `DefaultAnimationController.java:34-36` 是 `fun interface OnAnimStateChangeListener`（SAM interface），每个 lambda **编译期生成唯一 SAM adapter**（`LambdaClass implements OnAnimStateChangeListener`），adapter 的 `equals()` 是对象身份——所以**两次相同的 lambda 表达式也是不同 adapter**，但**同一 lambda 变量传递两次就是同一 adapter**，remove 可生效。

实际场景：
- demo 不写 remove，所以 demo 不感知
- 真机 launcher 业务方若照 `addOnAnimStateChangeListener { ... }` 模式反注册，**静默失效，listener 永远累积**——最终 OOM

**修复方案**（**必补**，约 10 行）：

```kotlin
// OnAnimStateChangeListener.kt
fun interface OnAnimStateChangeListener {
    fun onAnimStateChanged(oldState: AnimationState, newState: AnimationState, runningTask: Any?)
}
```

加 `@JvmFunctionalInterface` 也可——给 Java 调用方一个 SAM 入口。改完后**所有 demo 与测试需把 lambda 显式包成对象**才能 remove？不需要：Kotlin 函数类型对 `fun interface` 仍然走 SAM conversion，**lambda 写法不变**。

**修复成本**：1 文件、10 行（含 `@JvmFunctionalInterface` + 文档注释）。

> **✅已修复（本轮：LooperExecutor 补 shutdown/shutdownNow/isShutdown/isTerminated/awaitTermination，均按原厂抛 UnsupportedOperationException + @Deprecated）**
### 【bug 级】2. `LooperExecutor.shutdown()` 未抛 `UnsupportedOperationException`

**问题**：原厂 `LooperExecutor.java:71-73` 的 `shutdown()` 抛 `UnsupportedOperationException`（带 `@Deprecated`）。lib `LooperExecutor.kt` 已实现 `shutdown()`/`shutdownNow()`/`isShutdown`/`isTerminated`/`awaitTermination`（均 @Deprecated + throw UOE）。Java 调用方按 AOSP 习惯写 `executor.shutdown()` → **得到 `AbstractMethodError`**（而非 `UnsupportedOperationException`）——异常信息难诊断，但实际效果都是"永不 quit"。

更隐蔽的风险：未来如果补 `shutdown()` 实现（且错误地让它实际生效），**原厂契约会抛异常挡掉**，lib 则会**静默销毁 launcher.anim HandlerThread**，后续 `start()` / `post()` 全部 NPE。

**修复方案**（**必补**，约 5 行）：
```kotlin
class LooperExecutor internal constructor(...) {
    // ... 现有 ...
    @Deprecated("ANIM_CONTROL_EXECUTOR 永不 quit；调用将永远抛异常")
    @Throws(UnsupportedOperationException::class)
    fun shutdown() { throw UnsupportedOperationException("ANIM_EXECUTOR never quits") }
    @Deprecated("同上") fun shutdownNow(): List<Runnable> = throw UnsupportedOperationException()
    val isShutdown: Boolean get() = false
    val isTerminated: Boolean get() = false
    @Deprecated("返回 false；永远不 terminate")
    fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = false
}
```

**修复成本**：1 文件、6 行。

> **✔️保持简化（supportInterruption 恒 true：3 条件依赖 LauncherAnimConfig/TaskAnimationManager/AppFeatureUtils，demo 无 launcher 上下文——doc ②-1/④4.2-6 同判）**
### 【bug 级】3. `OplusAnimManager.supportInterruption()` 硬编码 `true`，丢失 4 条件组合

**问题**：原厂 `OplusAnimManager.java:232-234`：
```java
public final boolean supportInterruption() {
    return (!LauncherAnimConfig.INSTANCE.isAppTransitionByLightAnim()
            || LauncherAnimConfig.isAdaptiveAnimation())
        && TaskAnimationManager.ENABLE_SHELL_TRANSITIONS
        && AppFeatureUtils.INSTANCE.isSupportBlockableAnimation();
}
```
是 **4 条件 AND 组合**：
1. `!isAppTransitionByLightAnim() || isAdaptiveAnimation()`——轻量动画或自适应动画开启
2. `TaskAnimationManager.ENABLE_SHELL_TRANSITIONS`——Shell Transitions 系统开关
3. `AppFeatureUtils.isSupportBlockableAnimation()`——系统级支持开关

lib `OplusAnimManager.kt:27-29`：
```kotlin
fun supportInterruption(): Boolean = true
```

**后果**：
- demo 始终走 Impl 路径——没问题（demo 永远支持）
- 若未来集成到 launcher 业务，**4 个条件任何一个为 false，lib 仍走 Impl 路径**——会绕过系统级开关，可能导致：
  - 非 Shell Transitions ROM 上 lib 仍尝试调用 Shell transition API → `NoSuchMethodError`
  - 不支持 blockable animation 的设备上 lib 仍起手 → 动画卡死或无 cancel 兜底

**修复方案**（**必补**，约 15 行）：
```kotlin
fun supportInterruption(): Boolean {
    // 1) 简化版：返回 true（当前 demo 场景）
    return true
    // 2) 接入真实配置后：
    // return (!isAppTransitionByLightAnim() || isAdaptiveAnimation())
    //     && ENABLE_SHELL_TRANSITIONS && isSupportBlockableAnimation()
}
```
**修复成本**：1 文件、5~15 行（含三种条件的 facade 接口）。

> **✅已修复（60bd048：@set:Synchronized 防并发双建；本轮补两字段 @Volatile 读可见性）**
### 【bug 级】4. `OplusAnimManager.interruptionEnabled` setter 无锁保护，并发切换 race

**问题**：lib `OplusAnimManager.kt:55-65`：
```kotlin
var interruptionEnabled: Boolean
    get() = animationControllerImpl != null
    set(enabled) {
        if (enabled) {
            if (animationControllerImpl == null) animationControllerImpl = AnimationController()
            if (animationSeqHelperImpl == null) animationSeqHelperImpl = AnimationSeqHelper()
        } else {
            animationControllerImpl = null
            animationSeqHelperImpl = null
        }
    }
```
两个线程同时 `set interruptionEnabled = true` 会有 race：
- T1 读 `animationControllerImpl == null` → true
- T2 读 `animationControllerImpl == null` → true
- T1 赋值 new AnimationController()
- T2 赋值 new AnimationController()（**GC 掉 T1 的实例**）

字段本身也没 `@Volatile`——`@Volatile private var animationControllerImpl` 没有，可见性无保证。

**修复方案**（**必补**，约 8 行）：
```kotlin
@Volatile private var animationControllerImpl: AnimationController? = null
@Volatile private var animationSeqHelperImpl: AnimationSeqHelper? = null

private val toggleLock = Any()

var interruptionEnabled: Boolean
    get() = animationControllerImpl != null
    set(enabled) = synchronized(toggleLock) {
        if (enabled) {
            if (animationControllerImpl == null) animationControllerImpl = AnimationController()
            if (animationSeqHelperImpl == null) animationSeqHelperImpl = AnimationSeqHelper()
        } else {
            animationControllerImpl = null
            animationSeqHelperImpl = null
        }
    }
```
**修复成本**：1 文件、6 行（2 字段 @Volatile + lock 包裹 setter）。

> **✔️保持简化（doc 自判"建议保持简化"：demo 单线程，7 字段共享锁吞吐非问题）**
### 【中】5. `AnimationFeatureHelper.SyncedVar` 共享 lock → 并发吞吐收窄

见 §C-5。**当前 demo 单线程无影响**，但若接入 RUS 多线程推送场景（1 个 RUS 监听线程写 + 业务线程读），7 个字段共享锁会使并发写吞吐下降为 1/7。

**修复方案**（可选，每个字段独立锁，约 12 行）：
```kotlin
private class SyncedVar<T>(initial: T) : ReadWriteProperty<Any?, T> {
    @Volatile private var value = initial
    override fun getValue(...) = value
    override fun setValue(..., value: T) { @Synchronized setField(value) }
    private fun setField(value: T) { this.value = value }
}
// 调用处：
var asyncEnable by SyncedVar(1)  // 每个 SyncedVar 实例自带锁
```
**修复成本**：1 文件、12 行。**建议保持简化**（demo 单线程，吞吐不是问题），文档明示"7 字段共享锁"即可。

> **✔️保持简化（demo 全 Kotlin 无 Java 调用方；Java ABI 为未来集成场景——doc ④4.2-5 同判）**
### 【中】6. `OnAnimStateChangeListener` / 回调字段用 Kotlin 函数类型 → Java 调用方 ABI 损失

见 §C-4。

**当前 demo 全 Kotlin，无 Java 调用方，零影响**。

**未来风险**（Launcher3 集成时）：
- `controller.addOnAnimStateChangeListener { ... }` 在 Java 端要写 `new OnAnimStateChangeListener() { public void onAnimStateChanged(...) { ... } }` 7 行
- `controller.delayStartActivityIfNeed(c, i, call, action)` 在 Java 端要写 `new Function0<Boolean>() {...} + new Function0<Unit>() {...}` 16 行

**修复方案**（**建议补**，约 25 行）：
```kotlin
// 保留函数类型版本 + 加 Java 友好重载
@Suppress("FunctionName")
fun delayStartActivityIfNeed(context: Any?, intent: Intent?,
                             call: Supplier<Boolean>?, action: Runnable?): Boolean =
    delayStartActivityIfNeed(context, intent,
        call?.let { { it.get() } },
        action?.let { { it.run() } })

// 改 OnAnimStateChangeListener 为 fun interface（见 bug #1 修复）
```

**修复成本**：1 文件、25 行（2 个函数 × 2 个签名）。**建议保持简化 + 文档明示**。

> **✔️保持简化（同 ③-6；Runnable 重载引入 Kotlin 尾 lambda SAM 歧义，收益低）**
### 【低】7. `AnimationSeqHelper.delayFinishRecents(action: (() -> Unit)?)` 用函数类型

见 §1.4。原厂用 `Runnable runnable`（`AnimationSeqHelper.java:87`）。lib `AnimationSeqHelper.kt:64-74`。

**影响**：Java 调用方需要 `Function0<Unit>` 而非 `Runnable`——同 §③-6。demo 全 Kotlin，零影响。

**修复成本**：若按 §③-6 一起补，重载 + 适配 5 行。

> **✔️保持简化（lib 全 Kotlin 调用；@JvmStatic 仅在 Java 调用方集成时需要）**
### 【低】8. `@JvmStatic` / `@JvmOverloads` 兼容性拐杖全无

lib **完全无** `@JvmStatic` / `@JvmOverloads` / `@JvmField` / `@JvmName`（grep 验证：lib 全树 0 命中）。原厂 OPPO 大量使用 `@JvmStatic`（`OplusAnimManager.java:120`、`AnimationFeatureHelper.java:76, 94`），目的是让 Java 调用方写 `OplusAnimManager.getAnimController()` 而非 `OplusAnimManager.INSTANCE.getAnimController()`。

**当前 demo 全 Kotlin，可直接写 `OplusAnimManager.animController`**，拐杖不需要。

**未来风险**（Launcher3 集成时，Java 调用方占多数）：
- 写 `OplusAnimManager.INSTANCE.getAnimController()` 多 7 个字符 / 调用点
- 多 `getInstance` / `getAnimController` / `getFeatureHelper` 都要 `INSTANCE.xxx`

**修复方案**（**建议补**，约 5 行）：
```kotlin
object OplusAnimManager {
    @JvmStatic val animController: DefaultAnimationController get() = animationControllerImpl ?: DefaultAnimationController()
    // ... 其他 getter ...
}
```
**修复成本**：1 文件、5 行。**建议保持简化** + 文档明示。

> **✅已修复（本轮：AsyncAnimCallbacks.removeListener internal→public，与 public addListener 对称）**
### 【低】9. `AsyncAnimCallbacks.removeListener` 改成 `internal`

原厂 `AsyncAnimCallbacks.java:147-151` 的 `removeListener` 是 `public final`；lib `AsyncAnimCallbacks.kt:31` 改成 `internal fun removeListener`。Java 调用方拿不到。

**当前 demo 全 Kotlin**，`addListener` + 不 remove（snapshot dispatch 模式下也不需要手动 remove），零影响。

**修复成本**（**建议补**，1 行）：把 `internal` 改 `fun`（公开）。

> **✔️保持简化（demo 直用 Handler.sendEmptyMessageDelayed；postDelayed 按需 4 行可补）**
### 【低】10. `LooperExecutor` 缺 `postDelayed(Runnable, long)` 公开方法

原厂 `LooperExecutor.java:61-63` 提供 `postDelayed`，lib 不暴露。**当前 demo 全用 `Handler.sendEmptyMessageDelayed`**（`AnimationSeqHelper.kt:69`），零影响。

**修复成本**（按需）：1 文件、4 行。

---

## ④ 回移建议

### 4.1 值得补进 lib 的（按性价比排序）

| # | 修补内容 | 文件 | 成本 | 对应风险 |
|---|---|---|---|---|
| 1 | 状态：✅已修复（60bd048：OnAnimStateChangeListener.kt 已改 fun interface） — **【必补】`OnAnimStateChangeListener` 改 `fun interface` + `@JvmFunctionalInterface`** | `control/OnAnimStateChangeListener.kt` | 10 行 | §③-1 bug #1 |
| 2 | 状态：✅已修复（本轮：LooperExecutor 补 ExecutorService 契约壳，抛 UOE） — **【必补】`LooperExecutor.shutdown()` / `shutdownNow()` / `isShutdown` / `isTerminated` / `awaitTermination` 全补 + `@Deprecated` + `throws UnsupportedOperationException`** | `thread/LooperExecutor.kt` | 6 行 | §③-2 bug #2 |
| 3 | 状态：✔️保持简化（supportInterruption 保持 true——见 ③-3） — **【必补】`OplusAnimManager.supportInterruption()` 改成 4 条件 AND 组合（注释 TODO + 默认 true 兜底）** | `manager/OplusAnimManager.kt` | 15 行 | §③-3 bug #3 |
| 4 | 状态：✅已修复（60bd048 @set:Synchronized + 本轮 @Volatile） — **【必补】`OplusAnimManager.animationControllerImpl` / `animationSeqHelperImpl` 加 `@Volatile`，`interruptionEnabled` setter 加 `@Synchronized` 锁** | `manager/OplusAnimManager.kt` | 6 行 | §③-4 bug #4 |
| 5 | 状态：✔️保持简化（demo 全 Kotlin；重载会引入 SAM 歧义） — **【建议补】`delayStartActivityIfNeed` 加 `Supplier<Boolean> + Runnable` 重载版本（适配 Java）** | `control/AnimationController.kt` | 25 行 | §③-6 中 #6 |
| 6 | 状态：✔️保持简化（lib 全 Kotlin，无 Java 调用方） — **【建议补】`OplusAnimManager.animController` / `animationSeqHelper` / `featureHelper` 加 `@JvmStatic`** | `manager/OplusAnimManager.kt` + `manager/AnimationFeatureHelper.kt` | 5 行 | §③-8 低 #8 |
| 7 | 状态：✅已修复（本轮：AsyncAnimCallbacks.removeListener 公开） — **【建议补】`AsyncAnimCallbacks.removeListener` 改 `fun`（public）** | `async/AsyncAnimCallbacks.kt` | 1 行 | §③-9 低 #9 |
| 8 | 状态：✔️保持简化（同 ③-7） — **【建议补】`AnimationSeqHelper.delayFinishRecents` 加 `Runnable` 重载** | `seq/AnimationSeqHelper.kt` | 5 行 | §③-7 低 #7 |
| 9 | 状态：✔️保持简化（同 ③-5：共享锁吞吐 demo 无感） — **【建议补】`AnimationFeatureHelper.SyncedVar` 改成每个字段自带锁（独立 SyncedVar 实例）** | `manager/AnimationFeatureHelper.kt` | 12 行 | §③-5 中 #5 |
| 10 | 状态：✔️保持简化（同 ③-10） — **【可选】`LooperExecutor.postDelayed(Runnable, long)` 公开方法** | `thread/LooperExecutor.kt` | 4 行 | §③-10 低 #10 |
| 11 | 状态：⚠️未修复（docs/USAGE.md 非本批文档未改；如需"Kotlin 化兼容性契约"小节由主线程补） — **【可选】`USAGE.md` 补一节"Kotlin 化兼容性契约"**：明示 lambda 引用相等性、Java 调用方需用 `Function0` 适配、`OplusAnimManager.supportInterruption` 当前为 true（demo 简化）、`shutdown()` 永不 quit | `docs/USAGE.md` | 30 行 | 文档兜底 |

**总成本**：bug 级 4 项 ≈ **40 行** + 建议 6 项 ≈ 50 行 + 文档 30 行 ≈ **120 行**。

### 4.2 建议保持简化（不建议回移）

| # | 简化 | 理由 |
|---|---|---|
| 1 | 状态：✔️保持简化 — `object AnimationFeatureHelper`（不还原为 `class + companion + by lazy`） | 当前类初始化时机在 demo 单线程场景下与 `by lazy` 等价（都只是"何时构造"的差异，无副作用）；demo 无 RUS 接入；改 `class + companion + by lazy` 会引入 30+ 行样板，无收益 |
| 2 | 状态：✔️保持简化 — `OplusAnimManager` 6 个 observable delegate → 2 个裸 var | demo 不需要 `afterChange` 回调；observable delegate 在 JVM 上每个字段多一个 `t4.a` 实例 + `afterChange` 闭包，省掉 6 套是合理简化 |
| 3 | 状态：✔️保持简化 — `AnimationFeatureHelper` 7 个 SyncedVar 共享 lock | demo 单线程；多线程 RUS 接入场景吞吐下降为 1/7，但不会脏读——按需补独立锁即可，无需现在就回移 |
| 4 | 状态：✔️保持简化（本轮已补抛 UOE 契约，不 extends AbstractExecutorService 保持） — `LooperExecutor` 不 `extends AbstractExecutorService` | 已通过 `shutdown() throws UnsupportedOperationException` 兜底契约（§4.1 #2）；不需要补全 5 个 `ExecutorService` 方法 |
| 5 | 状态：✔️保持简化 — `Supplier<Boolean>` / `Runnable` → Kotlin 函数类型 | demo 全 Kotlin；Java 调用方场景按 §4.1 #5 加重载即可，不需要把函数类型改回 Java 接口形态（会损失 Kotlin 调用方的简洁性） |
| 6 | 状态：❌不成立/已过期（215ecb5 已删 HandlerTickScheduler；AnimationHandler internal 保持成立） — `internal class AnimationHandler` / `internal class HandlerTickScheduler` | lib 模块化设计：内部类不暴露给 demo / Java 调用方，符合"lib 是实现细节"定位 |

---

## 附：关键证据速查

| 论断 | 证据 |
|---|---|
| OPPO `OplusAnimManager` 是 Kotlin `object` + 6 个 `by Delegates.observable` + `static {}` + `@JvmStatic` | `com/oplus/quickstep/utils/OplusAnimManager.java:17-23, 46-91, 120` |
| OPPO `OplusAnimManager.supportInterruption()` 是 4 条件 AND 组合 | `com/oplus/quickstep/utils/OplusAnimManager.java:232-234` |
| OPPO `AnimationFeatureHelper` 是 Kotlin `class + companion + by lazy` + 10 个 `private volatile` 字段 + 10 个 `synchronized` setter + RUS 注册 | `com/oplus/quickstep/utils/AnimationFeatureHelper.java:42-43, 52-60, 63-80, 82-92, 99-156, 159-` |
| 原厂 `DefaultAnimationController` 内嵌 `public interface OnAnimStateChangeListener`（SAM） | `com/oplus/quickstep/utils/DefaultAnimationController.java:33-36` |
| 原厂 `LooperExecutor extends AbstractExecutorService` + `shutdown() throws UnsupportedOperationException` | `com/oplus/basecommon/thread/LooperExecutor.java:12, 71-73` |
| 原厂 `AnimationController.delayStartActivityIfNeed(Intent, Supplier<Boolean>, Runnable)` 用 Java stdlib 接口 | `com/oplus/quickstep/utils/AnimationController.java:50, 81-82, 86-88` |
| 原厂 `AnimationSeqHelper.delayFinishRecents(Runnable)` | `com/oplus/quickstep/utils/AnimationSeqHelper.java:87` |
| lib `OnAnimStateChangeListener` 是 `typealias = (A, B, C) -> Unit`（无 SAM） | `control/OnAnimStateChangeListener.kt:8` |
| lib `LooperExecutor` 不实现 `ExecutorService` + 无 `shutdown()` 公开方法 | `thread/LooperExecutor.kt:5-47` |
| lib `OplusAnimManager.supportInterruption()` 硬编码 true | `manager/OplusAnimManager.kt:27-29` |
| lib `AnimationFeatureHelper` 用 `object` + `SyncedVar<T>` property delegate | `manager/AnimationFeatureHelper.kt:13-52` |
| lib 全树 0 个 `@JvmStatic` / `@JvmOverloads` / `@JvmField` / `@JvmName` | grep `lib/` 全树无命中 |
| lib `Demo6StateMachineActivity` 只 add 不 remove OnAnimStateChangeListener | `demo/src/main/java/com/asyncanimator/demo/Demo6StateMachineActivity.kt:55` |

## 复核记录 v2（2026-09-09，独立逐条复核）

**复核方法**：逐条读取当前 `lib/src/main/java/com/asyncanimator/` 源码，不信任已有标记。

### 路径/行号修正汇总
| 修正项 | 旧值 | 新值 |
|---|---|---|
| async/LooperExecutor.kt | `async/` | `thread/`（48 行→71 行，含 shutdown 契约） |
| controller/AnimationController.kt | `controller/` | `control/` |
| controller/DefaultAnimationController.kt | `controller/` | `control/` |
| controller/OnAnimStateChangeListener.kt | `controller/` + `typealias` | `control/` + `fun interface` |
| feature/AnimationFeatureHelper.kt | `feature/` | `manager/` |
| OplusAnimManager interruptionEnabled 行号 | 48-58 | 55-65 |
| §D-5 "仍是 typealias" | typealias | **已改 fun interface**（60bd048） |
| §③-2 "未实现 ExecutorService" | 未实现 | **已实现 5 个方法**（shutdown/UOE） |

### 逐条状态复核（10 条风险 + 11 条建议）

| 条目 | 原标记 | 复核 | 修正 |
|---|---|---|---|
| §③-1 typealias → fun interface | ✅已修复（60bd048） | ✅ OnAnimStateChangeListener.kt:12 `fun interface` | 无 |
| §③-2 LooperExecutor shutdown | ✅已修复 | ✅ thread/LooperExecutor.kt:60-70 五方法 @Deprecated + UOE | 无 |
| §③-3 supportInterruption 4 条件 | ✔️保持简化 | ✔️ `fun supportInterruption(): Boolean = true`（manager/OplusAnimManager.kt:32） | 无 |
| §③-4 interruptionEnabled race | ✅已修复 | ✅ @Volatile(18-20) + @set:Synchronized(58) | 无 |
| §③-5 SyncedVar 共享锁 | ✔️保持简化 | ✔️ 7 SyncedVar 共享 lock（manager/AnimationFeatureHelper.kt:13） | 无 |
| §③-6 Java ABI/函数类型 | ✔️保持简化 | ✔️ demo 全 Kotlin | 无 |
| §③-7 delayFinishRecents 函数类型 | ✔️保持简化 | ✔️ 确认 | 无 |
| §③-8 @JvmStatic 缺失 | ✔️保持简化 | ✔️ lib 全树 0 个 @JvmStatic | 无 |
| §③-9 removeListener internal→public | ✅已修复 | ✅ anim/AsyncAnimCallbacks.kt:35 `fun removeListener`（public） | 无 |
| §③-10 postDelayed 缺失 | ✔️保持简化 | ✔️ demo 用 Handler.sendEmptyMessageDelayed | 无 |
| §④4.1-1 fun interface | ✅（60bd048） | ✅ | 无 |
| §④4.1-2 shutdown | ✅ | ✅ | 无 |
| §④4.1-3 supportInterruption | ✔️保持简化 | ✔️ | 无 |
| §④4.1-4 @Volatile + @Synchronized | ✅ | ✅ | 无 |
| §④4.1-5 Supplier 重载 | ✔️保持简化 | ✔️ | 无 |
| §④4.1-6 @JvmStatic | ✔️保持简化 | ✔️ | 无 |
| §④4.1-7 removeListener public | ✅ | ✅ | 无 |
| §④4.1-8 Runnable 重载 | ✔️保持简化 | ✔️ | 无 |
| §④4.1-9 SyncedVar 独立锁 | ✔️保持简化 | ✔️ | 无 |
| §④4.1-10 postDelayed | ✔️保持简化 | ✔️ | 无 |
| §④4.1-11 USAGE.md 兼容性契约 | ⚠️未修复 | ⚠️ 确认（USAGE.md 非本批范围） | 无 |
| §④4.2-1..5 保持简化 | ✔️保持简化 | ✔️ 全部确认 | 无 |
| §④4.2-6 HandlerTickScheduler | ❌不成立 | ❌ 215ecb5 已删 HandlerTickScheduler | 无 |

**总结**：23 条目原标记全部正确。路径/行号已更新。关键修正：§D-5 "仍是 typealias" → "已改 fun interface"；§③-2 "未实现 ExecutorService" → "已实现 5 个方法"。