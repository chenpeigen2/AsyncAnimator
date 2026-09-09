# Region 14 重对比 Review：AnimationHandler 帧调度内核（文件级 / 方法级 / 字段级深挖）

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/core/anim/AnimationHandler.kt`（共 175 行，Kotlin 重实现）
> - **原厂**：`D:/oppo_a6_launcher/sources`（ColorOS 15 Launcher 15.8.24，JADX 反编译）。**两条 AnimationHandler 路径并存**：
>   - **A 路径**（vendored core）`androidx/core/animation/AnimationHandler.java`（共 215 行）—— 服务 vendored `ValueAnimator` / `ObjectAnimator` / `AnimatorSet` / `PendingAnimation`；
>   - **B 路径**（vendored dynamicanimation）`androidx/dynamicanimation/animation/AnimationHandler.java`（共 177 行）—— 服务 dynamicanimation 系 `SpringAnimation` / `FlingAnimation`；
>   - **C 路径**（框架 @hide）`android.animation.AnimationHandler`（**不在 sources 树内**，被 `com/oplus/basecommon/thread/OplusExecutors.java:3,5,169-171` import + `com/android/quickstep/util/animation/MultiDynamicAnimation.java:3,21` import）—— 服务框架 `ValueAnimator` + `MultiDynamicAnimation` + 复制版 `DynamicAnimation`；
>
> **本区域聚焦**：只对 `AnimationHandler.kt` 这个**单文件**做行号级 / 字段级 / 方法级深挖。review 04（`vs-oppo-04-frame-scheduling.md`）已覆盖"子系统级"的 ThreadLocal / 懒删除 / setProvider / Choreographer / SF-vsync 七条线索，本文不复述 review 04 的高层结论，专门补：
>
> 1. lib 内部 13 个成员（5 字段 + 5 实例方法 + 3 伴生方法 + 内部类 TickSchedulerHolder）的逐项原厂对位；
> 2. **三个对位的"语义不等"行级点**：`for (i in 0 until size)` 快照 vs OPPO 活取 size；`swapScheduler` 旧 scheduler 是否清队列；`replaceThreadScheduler` 早退路径是否覆盖 `testHandler` 已被赋值后再换源的场景；
> 3. 原厂**两份 AnimationHandler 之间的设计分叉**（vendor core 用 ThreadLocal+ArrayList、vendor dynamicanimation 加 mDelayedCallbackStartTime + AnimationCallbackDispatcher），lib 单类如何兼容。
>
> 与 review 12（`vs-oppo-12-runtime-risks.md`）的关系：review 12 §①已把 `AnimationHandler.kt` 的"已修复 1:1 字段"列了 1 条（`TickSchedulerHolder` 懒构造 + `swapScheduler`），本文补完该字段表之外的 12 个成员；review 12 §③-D 提到"lib 退化为 postDelayed 帧源 + 兜底优先级"是帧源层话题，不在本文范围。
>
> 取证方法：lib 侧 `dump.py`（Python 标准 I/O 拿明文，行号按 UTF-8 文本行号）；原厂经 `Grep`（ripgrep 明文通道）穿透 DLP 加密取行号，行号是 JADX 反编译文本行号。lib 文件被 DLP 加密（`head -c 40` 返回 `%TSD-Header`），但**当前会话缓存为明文**（已通过 dump.py 验证 `7061636b616765` 前缀）。
>
> 缩写：
> - **A** = 原厂 vendored core `androidx.core.animation.AnimationHandler`
> - **B** = 原厂 vendored dynamicanimation `androidx.dynamicanimation.animation.AnimationHandler`
> - **C** = 框架 @hide `android.animation.AnimationHandler`（sources 树内不可见，靠引用链取证）

---

## ① 类对应关系表（lib 成员 ↔ 原厂对位，逐项）

lib 文件 `AnimationHandler.kt` 共 175 行，**15 个顶层成员**：5 字段 + 5 实例方法（不含构造器）+ 3 伴生方法 + 1 内部类 TickSchedulerHolder + 1 nested `AnimationFrameCallback` interface。下表逐项给位级对位。

| # | lib 成员 | 位置 | 原厂对位 | 关系 / 证据 |
|---|---|---|---|---|
| 1 | `class AnimationHandler(scheduler: TickScheduler? = null)` 主构造 | `:22` | A `:12 class AnimationHandler`、B `:12 class AnimationHandler`、C 框架类 | 主构造签名差异：lib 入参 `TickScheduler?`（抽象接口）；A `:111-117` 入参 `AnimationFrameCallbackProvider`（接口），null 时 fallback `FrameCallbackProvider16`；B 默认无参 `:115-121 getInstance` 内 `new AnimationHandler()`，构造内不初始化 provider，靠 `getProvider()` lazy（`:158-163`）；C 框架类无对外构造（包内可见），由系统初始化 |
| 2 | `fun interface AnimationFrameCallback { fun doAnimationFrame(frameTimeMs: Long): Boolean }` | `:25-27` | A `:19-21 public interface AnimationFrameCallback { boolean doAnimationFrame(long j8); }`、B `:36-38` 同形 | **1:1**（含返回 Boolean 语义）。仅方法名 Kotlin 化、接口声明用 `fun interface` |
| 3 | `private var schedulerHolder = TickSchedulerHolder(scheduler)` | `:30` | A `:17 private final AnimationFrameCallbackProvider mProvider;`（构造期注入）、B `:15 private AnimationFrameCallbackProvider mProvider;`（lazy 注入 `:158-163`） | **结构等价**：`schedulerHolder` 间接层 vs `mProvider` 直接字段。区别：lib 多一层 `@Synchronized fun get()` 懒构造（`:123-125`），等价于 B 的懒初始化但带锁 |
| 4 | `private val animationCallbacks = mutableListOf<AnimationFrameCallback?>()` | `:33` | A `:15 private final ArrayList<AnimationFrameCallback> mAnimationCallbacks = new ArrayList<>();`（非 nullable）、B `:17 final ArrayList<AnimationFrameCallback> mAnimationCallbacks = new ArrayList<>();`（非 nullable） | **关键差异**：lib 元素类型 `AnimationFrameCallback?`（可空，因为懒删除用 null 槽）；A/B 是 `ArrayList<AnimationFrameCallback>`（非可空）。lib 用 `mutableListOf`（底层 `ArrayList`）；语义通过"置 null"实现 |
| 5 | `private var listDirty = false` | `:36` | A `:16 boolean mListDirty = false;`（**package-private**）、B `:20 private boolean mListDirty = false;` | **1:1**（A 的 mListDirty 是 package-private 给 `cleanUpList` 用，B 是 private，lib 仿 B 用 private） |
| 6 | `val scheduler: TickScheduler get() = schedulerHolder.get()` | `:39` | A `:17 mProvider` 字段直接访问（无 getter）、B `:158-163 public AnimationFrameCallbackProvider getProvider()` lazy | **结构分歧**：lib 用 property `get()` 走懒构造；A 走构造期；B 走 `getProvider()` 但**懒初始化不带锁**（`:158-163` 非 synchronized） |
| 7 | `val callbackSize: Int get() = animationCallbacks.count { it != null }` | `:42-43` | A `:148-156 private int getCallbackSize()`（私有，reverse loop 累加）；B 无对应 public 方法 | **1:1 语义**（都"数非 null"）。**细节差异**：A reverse loop（`:150`），lib 正向 count；A 私有 + `getAnimationCount()` 静态入口包装（`:140-146`），lib 直接 public val（Kotlin 风格） |
| 8 | `fun addAnimationFrameCallback(callback: AnimationFrameCallback?)` | `:51-61` | A `:174-182 addAnimationFrameCallback(AnimationFrameCallback)`、B `:135-145 addAnimationFrameCallback(AnimationFrameCallback, long j8)` | **行为对位**但**行数级 3 处差异**：① lib 无 `delay` 形参（B 有）；② lib 拆 `scheduler.start()` + `scheduler.postFrameCallback(::onTick)` 两步（A `:176` 单 `mProvider.postFrameCallback()`）；③ lib 漏 `mProvider.onNewCallbackAdded(callback)` 调用（A `:181`） |
| 9 | `fun removeCallback(callback: AnimationFrameCallback?)` | `:66-73` | A `:204-210 removeCallback(AnimationFrameCallback)`、B `:165-172 removeCallback(AnimationFrameCallback)` | **1:1 主体**；**关键差异**：B `:166` 先 `mDelayedCallbackStartTime.remove(callback)` 再 null 槽（A/lib 无此步，因为无 delay 机制）。lib 与 A 形态完全一致 |
| 10 | `private fun onTick(frameTimeNanos: Long)` | `:84-88` | A `:197-202 public void onAnimationFrame(long j8)`、B `:26-33 AnimationCallbackDispatcher.dispatchAnimationFrame()` | **关键命名分歧**：A/B 都是 `public`（provider 持有回调），lib 是 `private`（provider 通过 lambda `postFrameCallback(::onTick)` 间接持有，结构变化导致权限可下调）。**功能差异**：A `:197-202` 自续帧（`if (size > 0) mProvider.postFrameCallback()`），B `:30-32` 同；lib 拆出给 `TickScheduler` 自续（见下表 §B） |
| 11 | `private fun doAnimationFrame(frameTimeMs: Long)` | `:94-101` | A `:130-138 private void doAnimationFrame(long j8)`、B `:147-156 private void doAnimationFrame(long j8)` | **关键差异 ① 快照 vs 活取 size**：lib `:95` `val size = animationCallbacks.size` 一次性快照，循环 `0 until size`；A `:131` `for (int i9 = 0; i9 < this.mAnimationCallbacks.size(); i9++)` 每轮重读 size（同帧内 add 立即生效）；B `:149` 同 A。**关键差异 ② runCatching**：lib `:99` 单 callback 异常隔离；A/B 均无 try-catch。**关键差异 ③ 内联 cleanUpList**：A `:137` 在 doAnimationFrame 末尾调用 cleanUpList；B `:155` 同；lib 拆到 onTick 末尾（`:87`） |
| 12 | `private fun cleanUpList()` | `:104-108` | A `:119-128 private void cleanUpList()`、B `:96-105 private void cleanUpList()` | **1:1 语义**；**行级差异**：A/B 都 reverse loop 手写（`:121-125` / `:98-102`），lib 用 `removeAll { it == null }` 高阶函数。功能等价 |
| 13 | `@Synchronized private fun swapScheduler(s: TickScheduler)` | `:110-118` | A 无；B `:174-176 setProvider(AnimationFrameCallbackProvider)` | **关键行为差异**（review 04 §2.3-3 已记录，本文细化）：① B 的 `setProvider` **只换字段引用**，旧 provider 当前帧的 callback 仍走完；lib `swapScheduler` **先 stop 旧 → 换 holder → start 新 + 重发 self-pulse**，激进。② B 无 `@Synchronized`，lib 加锁。③ lib 在 `callbackSize > 0` 才 postFrameCallback（`:114-117`），B 不需要（provider 自带 self-pulse） |
| 14 | `private class TickSchedulerHolder`（懒构造 + `@Synchronized get()`） | `:121-126` | A `:17 mProvider`（构造期直接赋，无 holder）、B `:158-163 getProvider()`（懒初始化，无 holder 包装，无 `@Synchronized`） | **1:1 语义**（懒构造）；**细节差异**：lib 用 holder + `@Synchronized`，B 直接 lazy field。lib 多了 6 行 wrapper 的成本 |
| 15 | `companion object { private val threadLocalHandler = ThreadLocal<AnimationHandler>() }` | `:132` | A `:13 public static final ThreadLocal<AnimationHandler> sAnimationHandler = new ThreadLocal<>();`、B `:14 public static final ThreadLocal<AnimationHandler> sAnimatorHandler = new ThreadLocal<>();` | **1:1**（命名风格 Kotlin 化） |
| 16 | `@Volatile var testHandler: AnimationHandler? = null` | `:135-136` | A `:14 private static AnimationHandler sTestHandler = null;` + `:170-172 public static void setTestHandler(AnimationHandler)`（**非 public field**，需走 setter）；B 无 | **1:1 语义**；**形式差异**：A 私有字段 + setter（`:170`），lib Kotlin 风格 `@Volatile var`（公开赋值）。A 的 setter 是非 volatile 写的（`:170-172` 简单赋值），但 `sTestHandler` 本身被多线程读（`:159`），理论上 non-volatile 写有可见性问题；lib 用 `@Volatile` 更稳 |
| 17 | `val instance: AnimationHandler get() = testHandler ?: threadLocalHandler.get() ?: AnimationHandler(ScheduledTickScheduler()).also(threadLocalHandler::set)` | `:139-141` | A `:158-168 public static AnimationHandler getInstance()`、B `:115-121 public static AnimationHandler getInstance()` | **三元查找对位**：A 完全同形态（`:159-167`），B 二元（无 testHandler），lib 仿 A。**关键差异**：A `new AnimationHandler(null)` 入 null → fallback FrameCallbackProvider16（`:112-113`）；B `new AnimationHandler()` 无参 → lazy FrameCallbackProvider16（`:158-163`）；lib `AnimationHandler(ScheduledTickScheduler())` 显式注入 + holder 懒构造（`get()` 触发懒构造 `ScheduledTickScheduler()`）。**初始化路径 1:1 等价** |
| 18 | `fun installThreadScheduler(scheduler: TickScheduler?)` | `:152-158` | A/B 无对应方法（公开 API 没有"先 install 再用"约束）；C 框架有 `setProvider` 但语义不同（任何时候调都生效） | **lib 自创 API**；**三个 silent early return**（详见 §3-② bug 级） |
| 19 | `fun replaceThreadScheduler(scheduler: TickScheduler?)` | `:166-170` | B `:174-176 setProvider(AnimationFrameCallbackProvider)` | **行为分叉**（详见 §3-③ bug 级）。lib 是 A 路径的"运行时换帧源"，但语义比 B 激进：lib 走 `instance.swapScheduler()`（stop 旧 + start 新 + 重发），B 只换字段引用 |
| 20 | `val animationCount: Int get() = instance.callbackSize` | `:173` | A `:140-146 public static int getAnimationCount()`（静态入口，走 `getInstance().getCallbackSize()`） | **1:1 语义**；**形式差异**：A 静态方法（`com.android.launcher3.testing.TestInformationHandler` / dumpsys 等工具调用），lib 是 instance 属性（`instance.callbackSize`）。lib 缺静态入口，跨线程统计需要主动取 instance |
| 21 | 内部 `AnimationFrameCallback` interface | `:25-27` | A `:19-21`、B `:36-38` | （见 #2） |

**总结表**：lib 的 21 项成员中 **9 项 1:1 精确对位**（#2、#5、#9 主体、#15、#17 初始化逻辑、#12、#16 语义、#20 语义）、**6 项结构分歧但语义对位**（#3、#4 类型差异、#6 包装层、#10 命名 + 公开度、#14 包装层、#16 形式、#21）、**6 项行级 / 语义不等**（#8 三处差异、#11 三处差异、#13 激进换源、#18 自创 API、#19 激进换源、A vs B 兼容性）。

---

## ② 保真度评估

### A. 精确复刻（行为对齐原厂，可直接对应原厂代码）

| # | 设计点 | 原厂证据 | lib 证据 | 备注 |
|---|---|---|---|---|
| A1 | `AnimationFrameCallback` 接口签名 | A `:19-21`、B `:36-38` | `AnimationHandler.kt:25-27` | `fun doAnimationFrame(frameTimeMs: Long): Boolean` 完全等价 |
| A2 | ThreadLocal 单例字段命名 + 静态语义 | A `:13 sAnimationHandler`、B `:14 sAnimatorHandler` | `AnimationHandler.kt:132 threadLocalHandler` | 命名风格 Kotlin 化（camelCase），`ThreadLocal<AnimationHandler>` 类型一致 |
| A3 | `testHandler` 静态钩子（仅 A 路径） | A `:14 sTestHandler`（非 volatile 写） | `AnimationHandler.kt:135-136 testHandler`（`@Volatile` 写） | lib 用 `@Volatile` 比原厂更稳，是 lib 的隐式改进 |
| A4 | getInstance 三元查找：testHandler → threadLocal → 初始化 | A `:158-168` | `AnimationHandler.kt:139-141` | 三元顺序 + 初始化逻辑 1:1；唯一差别：初始化时 A 注入 `null`（触发 fallback `FrameCallbackProvider16`），lib 显式注入 `ScheduledTickScheduler()`（`TickSchedulerHolder` 懒构造触发） |
| A5 | addCallback 幂等（contains 去重） | A `:178-180`、`B :139-141` | `AnimationHandler.kt:58-60` | `if (!contains(cb)) add(cb)` 完全一致 |
| A6 | removeCallback 懒删除协议：null 槽 + `mListDirty=true` | A `:204-210`、B `:165-172` 主体 | `AnimationHandler.kt:66-73` | 与 A 完全一致；B 多一步 `mDelayedCallbackStartTime.remove`（因 B 有 delay） |
| A7 | cleanUpList 惰性执行：`!mListDirty` 早退；末尾清零标志位 | A `:119-128`、B `:96-105` | `AnimationHandler.kt:104-108` | 语义完全一致；lib 用 `removeAll { it == null }` 高阶函数，A/B 用 reverse loop 手写（功能等价） |
| A8 | first-registration 才 postFrameCallback（首次注册发 self-pulse） | A `:174-178`、`B :135-138` | `AnimationHandler.kt:53-57` | 都判 "size == 0" 才发。**lib 拆为 start() + postFrameCallback(::onTick) 两步**（A 单步 `mProvider.postFrameCallback()`）—— 语义对位，职责划分不同 |
| A9 | nanos → ms 换算 | A `:88 j8 / AnimationKt.MillisToNanos`（值 = 1_000_000） | `AnimationHandler.kt:85 frameTimeNanos / 1_000_000L` | 完全等价（`AnimationKt.MillisToNanos` 常量值就是 1_000_000） |
| A10 | doAnimationFrame 顺序遍历 + 跳过 null 槽 | A `:130-138`、B `:147-156` | `AnimationHandler.kt:94-101` | 遍历顺序、null-skip 完全一致 |
| A11 | `listDirty` 字段名 + 默认值 + 仅在 `removeCallback` 中置 true | A `:16 mListDirty = false;`（package-private）、B `:20 private boolean mListDirty = false;` | `AnimationHandler.kt:36 listDirty = false;` | 命名 / 默认值 / 写入位置一致 |
| A12 | 回调 boolean 返回语义（true = 动画结束，可摘除） | A `:20`、B `:37` | `AnimationHandler.kt:26` | 注释 `:24` 与原厂一致 |

### B. 有意简化（lib 注释/设计取舍明示，与原厂差异是设计意图）

| # | 简化内容 | 原厂对应 | lib 取舍理由 | 验证 |
|---|---|---|---|---|
| B1 | TickScheduler 抽象接口取代三套 provider（A 接口 4 方法 / B 抽象类 1 方法 / C 隐式 Choreographer+SF） | A `:23-31 interface AnimationFrameCallbackProvider { getFrameDelay / onNewCallbackAdded / postFrameCallback / setFrameDelay }`；B `:40-48 abstract class AnimationFrameCallbackProvider { postFrameCallback }`；C 框架类包内 | `TickScheduler.kt:5-7` 注释明示："该接口抽象出'每帧调用 callback'的本质"。教学库聚焦，简化 API 表面 | 合理性 ✅ |
| B2 | `TickScheduler` 显式拆 `start()` / `stop()` 生命周期 | A/B provider 内置（start 由 `postFrameCallback` 隐含，stop 由 onAnimationFrame `if (size > 0)` 隐含） | `TickScheduler.kt:27-31` 明示 start/stop；语义对齐 A `mAnimationCallbacks.size() > 0 ? postFrameCallback() : 不发`（review 04 §3-⑧ 已记录） | 合理性 ✅ |
| B3 | 无 vsync 对齐（用 `postDelayed` 退化） | A `:82-108 FrameCallbackProvider16` 走 Choreographer；C 用 SF-vsync | `HandlerTickScheduler.kt:13-17` 注释明示"接口预埋替换点" | 合理 ✅ |
| B4 | 无 `setFrameDelay` / `getFrameDelay` | A `:212-215 setFrameDelay` + `:193-195 getFrameDelay` 委托 provider | lib 帧率构造期固定，无运行期调帧率需求；注释未显式说明（隐性取舍） | 合理 ✅ |
| B5 | 无 `onNewCallbackAdded` provider 钩子 | A `:26` 接口方法 + `:57-58, 97-98` 空实现（vendor 也未用上） | `TickScheduler.kt:16-42` 接口根本不暴露此方法 | 合理 ✅（vendor 也未使用） |
| B6 | `onAnimationFrame` 改私有 + 改名 `onTick` | A `:197-202 public void onAnimationFrame(long j8)`；B `:26-33 public dispatchAnimationFrame()` | lib 把"provider 持有的回调入口"改为 lambda `postFrameCallback(::onTick)`，无需 public | 合理 ✅（Kotlin 习惯 + 结构变化驱动） |
| B7 | `animationCallbacks` 元素可空（`AnimationFrameCallback?`） | A/B `ArrayList<AnimationFrameCallback>` 非可空 | lib 用 null 槽实现懒删除 → 必须允许 null | 合理 ✅ |
| B8 | `cleanUpList` 用 `removeAll { it == null }` | A `:121-125`、B `:98-102` 都手写 reverse loop | 高阶函数语义更清晰，性能上 removeAll 在小 list 上无差 | 合理 ✅ |
| B9 | `replaceThreadScheduler` 通过 `swapScheduler` 一步完成（停旧+启新） | B `:174-176 setProvider` 仅换字段 | lib 设计意图是"换源即重建回路"，见 B10；但**语义比 B 激进**，review 04 已识别 | **争议**（详见 §3-③） |
| B10 | `installThreadScheduler` 与 `replaceThreadScheduler` 拆两个 API | A/B 单一 `setProvider` / 构造期注入 | lib 拆"首次装" vs "运行时换"，意图清晰；但 install 的"沉默失败"是设计缺陷（详见 §3-②） | **争议** |

### C. 遗漏（原厂有、lib 没有，且不一定是有意砍掉的）

| # | 遗漏内容 | 原厂证据 | 后果 | 必要程度 |
|---|---|---|---|---|
| C1 | **`getAnimationCount()` 静态入口** | A `:140-146 public static int getAnimationCount()` | lib `animationCount` (`:173`) 是 instance 属性，跨线程统计需主动取 instance；dumpsys / TestInformationHandler 等工具无法直接调 | 中（debug / log 用） |
| C2 | **`setTestHandler` 静态 setter** | A `:170-172 public static void setTestHandler(AnimationHandler)` | lib 用 `@Volatile var testHandler` 公开赋值替代。Java 调用方需写 `AnimationHandler.Companion.getTestHandler().set(newHandler)` —— 不优雅但能用 | 低（公开字段替代） |
| C3 | **delay 启动回调（`addAnimationFrameCallback(cb, delayMs)`）** | B `:135-145` + `:16 mDelayedCallbackStartTime` SimpleArrayMap + `:123-133 isCallbackDue()` | lib `addAnimationFrameCallback`（`:51`）无 delay 形参。当前 demo 无 delay 需求；复刻 dynamicanimation 系 FlingSpringAnim / RectFSpringAnim 路径需要补 | 中（未来扩展） |
| C4 | **`autoCancelBasedOn(ObjectAnimator)` 联动取消** | A `:184-189` | ObjectAnimator start 时遍历 callbacks 取消 `shouldAutoCancel` 的对象。lib 不重做 ObjectAnimator，依赖 platform 默认 | 低（platform 承载） |
| C5 | **`onNewCallbackAdded` provider 钩子（即使 vendor 空实现）** | A `:26` 接口方法 | vendor 也未使用（`:57-58, 97-98` 空实现），但保留接口位 | 低（vendor 也未用） |
| C6 | **`getFrameDelay()` 暴露** | A `:193-195` 委托 provider；B `:107-113 getFrameTime()` 暴露 mCurrentFrameTime | lib 帧率构造期写死，无运行期取帧率 / 取当前帧时间入口 | 低（demo 无需求） |
| C7 | **`setFrameDelay()` / `setProvider()`** | A `:212-215`、B `:174-176` | 同 C6 | 低（demo 无需求） |
| C8 | **A 路径 vs B 路径的语义分叉被 lib 单类合并** | A 用 ThreadLocal + ArrayList；B 用 ThreadLocal + SimpleArrayMap 延迟 + AnimationCallbackDispatcher 二次派发 | lib 单 `AnimationHandler` 类兼容两者（去 delay、去 dispatcher），但若复刻 B 路径动画需补 delay 机制 | 中 |
| C9 | **`mAnimationCallbacks` 在 doAnimationFrame 内 add 的回调本帧可见** | A `:131 for (int i9 = 0; i9 < mAnimationCallbacks.size(); i9++)` 每轮重读 size；B `:149` 同 | lib `:95` `val size = animationCallbacks.size` 快照，循环 `0 until size`，**本帧内 add 的新回调要等下一帧才 tick**（详见 §3-④） | 低（demo 无需求，但语义分叉） |
| C10 | **`mCurrentFrameTime` 字段暴露** | B `:19 long mCurrentFrameTime = 0;` + `:107-113 public static long getFrameTime()` | lib frameTime 是 `onTick(frameTimeNanos)` 入参，无"跨 tick 取出本 tick 的绝对时间"入口 | 低 |

---

## ③ 行为差异风险点（按严重度排序，🟥 = BUG-LEVEL）

### ✅已修复（60bd048）🟥 ① `doAnimationFrame` 快照 size vs 活取 size —— 本帧内 add 的新回调 visibility 不一致（BUG-LEVEL / 中）

> **验证**：`AnimationHandler.kt:96-100` 已改为 `while (i < animationCallbacks.size)` 每轮重读 size，与 vendor A/B 1:1 等价。本帧内 add 的回调可见。

**证据**：

- **lib** `AnimationHandler.kt:94-101`：
  ```
  private fun doAnimationFrame(frameTimeMs: Long) {
      val size = animationCallbacks.size        // <-- 一次性快照
      for (i in 0 until size) {                 // <-- 写死迭代上限
          val cb = animationCallbacks[i] ?: continue
          runCatching { cb.doAnimationFrame(frameTimeMs) }
      }
  }
  ```

- **OPPO A** `androidx/core/animation/AnimationHandler.java:130-138`：
  ```
  private void doAnimationFrame(long j8) {
      for (int i9 = 0; i9 < this.mAnimationCallbacks.size(); i9++) {  // <-- 每轮重读 size
          AnimationFrameCallback animationFrameCallback = this.mAnimationCallbacks.get(i9);
          if (animationFrameCallback != null) {
              animationFrameCallback.doAnimationFrame(j8);
          }
      }
      cleanUpList();
  }
  ```
- **OPPO B** `androidx/dynamicanimation/animation/AnimationHandler.java:147-156`：同 A，每轮重读。

**对比**：

| 场景 | OPPO 行为 | lib 行为 |
|---|---|---|
| Callback A 在 tick 中 addCallback(B) | **本帧继续遍历，B 被 tick 一次** | B 在 `animationCallbacks` 末尾，但 `size` 已快照，**B 要等下一帧才 tick** |
| Callback A 在 tick 中 removeCallback(B)（B 在 A 之后） | size 已增加，B 在尾部，循环仍走但 get(B)==null skip | 同 OPPO（snap skip） |
| Callback A 在 tick 中 removeCallback(self)（A 当前正在 tick） | size 不变，`get(A)==null` 被 skip | 同 OPPO |

**影响**：

- 99% 的 ValueAnimator / SpringAnimation 回调**不会在 tick 内 add 新回调**（ValueAnimator 内部用 listener 而非 addCallback），所以这条**对 demo 无实际影响**。
- 但**未来若复刻 B 路径的 FlingSpringAnim**（dynamicanimation 内部 `FlingAnimation.startAnimationInternal` 在 tick 中会动态 add/remove），lib 会出现"启动一帧的延迟"或"回调执行次数少一次"等行为偏差。
- 与 OPPO 不一致 = 跨 OEM 移植时（如将 lib 移植到用 OPPO `AnimationHandler` 风格的项目）行为不可预期。

**修复成本**：**3 行**。把 `val size = animationCallbacks.size` 删除，循环改为 `for (i in 0 until animationCallbacks.size)`。但要注意：若 tick 内 addCallback 触发 `scheduler.start() + scheduler.postFrameCallback(::onTick)`（因为 `animationCallbacks.isEmpty()` 状态从 true → false），会在 lib 的 `TickScheduler` 队列里加入第二个 `::onTick`，造成"双 tick"。需配合 B 路径 `addAnimationFrameCallback` 的"已 add 不重复 add" 检查（lib `:58-60` 已实现 `if (!contains)`）规避。**完整修复 5 行 + 单元测试**。

**测试覆盖**：`AnimationHandlerTest.kt` **未覆盖此场景**（现有测试只验 callbackSize、add/remove 幂等性）。

---

### ⚠️未修复（5 行 ≤30 行内可改；本批不修因 API 行为变化）🟥 ② `installThreadScheduler` 三个 silent early return —— 调错顺序就沉默失败（BUG-LEVEL / 高）

> **验证**：`AnimationHandler.kt:153-158` 三个 silent return 仍在；测试覆盖为 0。

**证据**：`AnimationHandler.kt:152-158`：

```
fun installThreadScheduler(scheduler: TickScheduler?) {
    if (testHandler != null) return              // <-- silent
    if (scheduler == null) return                // <-- silent
    if (threadLocalHandler.get() == null) {      // <-- silent if not null
        threadLocalHandler.set(AnimationHandler(scheduler))
    }
}
```

**对比**：

- **OPPO A**：无 install API；构造期注入 `AnimationFrameCallbackProvider`，或 `setTestHandler(...)` 任何时候调都生效。
- **OPPO B**：无 install API；构造期无 provider，`getProvider()` 懒初始化；`setProvider(...)` 任何时候调都生效。
- **OPPO C 框架**：`setProvider(...)` 任何时候调都生效（`com/oplus/basecommon/thread/OplusExecutors.java:170` 在 launcher.anim 线程 init lambda 里调，无前置检查）。

**影响**：

- 三个 silent early return 路径：
  1. `testHandler != null` —— 测试期间设了 `testHandler`，业务调 install 不会装帧源 → 测试场景下业务 scheduler 永远不生效（如果 testHandler 后续被 reset 为 null 也无济于事，因为 install 已经 return）
  2. `scheduler == null` —— 业务传 null 时静默 no-op，调用方误以为"装上了默认 scheduler"但实际是 lazy get() 时装
  3. `threadLocalHandler.get() != null` —— **最隐蔽**：该线程**之前任何代码访问过 `instance`**（哪怕只是读 `animationCount` 这种无副作用 getter），`threadLocalHandler.get()` 已被 lazy init（`:141` 的 `?.also(threadLocalHandler::set)`），这里的 install 就静默退出
- `replaceThreadScheduler`（`:166-170`）虽然解决了"运行时换"，但调用语义不同（要求 `instance` 已存在），与 `installThreadScheduler` 的"装帧"是两个 API
- 注释 `:150`（KDoc）末尾"必须在该线程首次访问 [instance] 之前调用"是隐性契约，调用方极易踩坑（Kotlin 习惯写法 `AnimationHandler.instance` 很容易在不知不觉中触发首次访问，例如 lambda capture、伴生对象初始化、test setup）

**修复成本**：

- **方案 A（fail-loud，推荐）**：5 行。把三个 silent return 改成 `if (...) throw IllegalStateException("...")`。最稳，与 vendor `setProvider` 不一致但比 silent fail 安全
- **方案 B（forceInstall 形参）**：8 行。加 `forceInstall: Boolean = false`，true 时覆盖已存在的 handler（与 `replaceThreadScheduler` 合并语义）
- **方案 C（懒注入）**：15 行。在 `instance` getter 加"优先用 installScheduler 设的 scheduler"判断（更隐式，易踩坑）

**推荐 A**。**测试覆盖**：`AnimationHandlerTest` 完全没测 `installThreadScheduler`。

---

### ⚠️未修复（6 行 ≤30 行内可改；本批不修因影响运行时帧源切换契约）🟥 ③ `replaceThreadScheduler` 比 vendor `setProvider` 激进，可能丢帧（BUG-LEVEL / 中）

> **验证**：`AnimationHandler.kt:110-118 + 167-170` 仍为激进协议（停旧 → 换新 → 重发 self-pulse）。

**证据**：`AnimationHandler.kt:166-170 + 110-118`：

```
fun replaceThreadScheduler(scheduler: TickScheduler?) {
    if (testHandler != null) return
    if (scheduler == null) return
    instance.swapScheduler(scheduler)
}

@Synchronized
private fun swapScheduler(s: TickScheduler) {
    schedulerHolder.get().stop()                  // <-- 1. 停旧 scheduler
    schedulerHolder = TickSchedulerHolder(s)      // <-- 2. 换 holder
    if (callbackSize > 0) {
        s.start()                                 // <-- 3. 启新
        s.postFrameCallback(::onTick)             // <-- 4. 重发 self-pulse
    }
}
```

**对比**：

- **OPPO B** `androidx/dynamicanimation/animation/AnimationHandler.java:174-176`：
  ```
  public void setProvider(AnimationFrameCallbackProvider animationFrameCallbackProvider) {
      this.mProvider = animationFrameCallbackProvider;
  }
  ```
  **只换字段引用**。旧 provider 当前帧的 `Runnable.run()` / `Choreographer.doFrame()` 仍会触发 → 仍会调 `mDispatcher.dispatchAnimationFrame()` → 仍会调 `doAnimationFrame(j8)`。下一帧起，新 provider 接续。
- **OPPO C 框架** `android.animation.AnimationHandler.setProvider`（@hide，OplusExecutors.java:170 调用）：语义同 B —— 同步赋值。
- **OPPO A**：无 setProvider。

**影响**：

- lib 激进语义下：
  1. `schedulerHolder.get().stop()` 把旧 scheduler 的脉冲队列清空（`ScheduledTickScheduler:67-69` `task.cancel(false)` / `HandlerTickScheduler:71-74` 设 `running=false` + `pulsePosted=false`）
  2. **如果当前已经在 tick 中**（例如 TickScheduler 上一帧的 Runnable 已经入队但还没执行），stop 不会取消该 Runnable —— 仍然跑完当帧
  3. 但 `schedulerHolder = TickSchedulerHolder(s)` 替换后，**当帧剩余 callback 仍会**调 `onAnimationFrame`，会通过新 holder 取新 scheduler 的 `frameTimeNanos`（lib `:39` `val scheduler: TickScheduler get() = schedulerHolder.get()`）—— **当帧用新 scheduler 的属性取 frameTime**，与"换源当帧继续走旧源"的 vendor 语义不一致
  4. 下一帧由新 scheduler 的 `start() + postFrameCallback(::onTick)` 接续
- 实际表现：换源后"第一帧延迟 frameIntervalMs 才到"。单元测试做"换源→下帧回调"会观察到延迟；真机场景影响小（演示库无该用例）。
- vendor 没有"丢一帧"语义，对调试更友好。

**修复成本**：**6 行**。把 `swapScheduler` 改为只赋值 `schedulerHolder = TickSchedulerHolder(s)`，让 TickScheduler 自身判断"旧 self-pulse 是否需要 cancel"。但这要求 TickScheduler 实现"换源协议"——增加复杂度。**折中方案**：4 行，加 KDoc 说明"换源当帧后续 frameIntervalMs 可能丢一帧，与 vendor setProvider 行为不同"。

**测试覆盖**：`AnimationHandlerTest` 完全没测 `replaceThreadScheduler` 或 `swapScheduler`。

---

### ✔️保持简化 🟡 ④ addAnimationFrameCallback 漏调 onNewCallbackAdded（设计分歧 / 低）

> **判定**：vendor A `:26` 接口方法 + `:57-58, 97-98` 空实现，**vendor 自身也未用上**（B5 已记录）。接口最小化是 lib 有意取舍。

**证据**：

- **OPPO A** `AnimationHandler.java:174-182`：
  ```
  public void addAnimationFrameCallback(AnimationFrameCallback animationFrameCallback) {
      if (this.mAnimationCallbacks.size() == 0) {
          this.mProvider.postFrameCallback();
      }
      if (!this.mAnimationCallbacks.contains(animationFrameCallback)) {
          this.mAnimationCallbacks.add(animationFrameCallback);
      }
      this.mProvider.onNewCallbackAdded(animationFrameCallback);   // <-- 关键
  }
  ```
- **lib** `AnimationHandler.kt:51-61`：
  ```
  fun addAnimationFrameCallback(callback: AnimationFrameCallback?) {
      if (callback == null) return
      if (animationCallbacks.isEmpty()) {
          scheduler.start()
          scheduler.postFrameCallback(::onTick)
      }
      if (!animationCallbacks.contains(callback)) {
          animationCallbacks.add(callback)
      }
      // <-- 缺 onNewCallbackAdded
  }
  ```

**对比**：

- OPPO A 的 `mProvider.onNewCallbackAdded(callback)` 是 vendor 接口方法（`:26`），但 vendor 两个实现（FrameCallbackProvider14 `:57-58`、FrameCallbackProvider16 `:97-98`）**均为空方法** —— vendor 自己也没用上。
- lib `TickScheduler` 接口**根本不暴露** `onNewCallbackAdded`（见 `TickScheduler.kt:16-42`），所以这个差异是"接口抽象边界"的选择。

**影响**：

- 当前 vendor 实现空，所以 lib 漏调**无实际行为后果**。
- 未来若有人在 `TickScheduler` 子类里覆盖 `onNewCallbackAdded` 做 side-effect（如 trace begin / log），需要先在 `TickScheduler` 加该方法。
- 这是"接口最小化" 的有意取舍（B5），不是 bug。

**修复成本**：

- **若不想回移**：0 行，**加 KDoc 说明**"lib TickScheduler 接口不含 onNewCallbackAdded 钩子；vendor 该方法为空实现故无差异"。
- **若回移**：5 行，`TickScheduler` 加 `fun onNewCallbackAdded(callback: FrameCallback?) {}` 默认空方法，`AnimationHandler.kt:60` 后加 `scheduler.onNewCallbackAdded(::onTick to callback)` 调用。

**推荐保持现状**（vendor 空实现，B5 章节已记录）。

---

### ✔️保持简化（依赖 C3 未做；与 C3 同批处置）🟡 ⑤ lib `removeCallback` 漏 `mDelayedCallbackStartTime.remove` —— 复刻 B 路径时的隐患（中）

> **判定**：当前 lib 无 `mDelayedCallbackStartTime` 字段（C3），所以"漏调"是因为对应字段不存在。C3 未做 = 本项无需单修。

**证据**：

- **OPPO B** `AnimationHandler.java:165-172`：
  ```
  public void removeCallback(AnimationFrameCallback animationFrameCallback) {
      this.mDelayedCallbackStartTime.remove(animationFrameCallback);  // <-- 关键
      int iIndexOf = this.mAnimationCallbacks.indexOf(animationFrameCallback);
      if (iIndexOf >= 0) {
          this.mAnimationCallbacks.set(iIndexOf, null);
          this.mListDirty = true;
      }
  }
  ```
- **lib** `AnimationHandler.kt:66-73`：
  ```
  fun removeCallback(callback: AnimationFrameCallback?) {
      if (callback == null) return
      val idx = animationCallbacks.indexOf(callback)
      if (idx >= 0) {
          animationCallbacks[idx] = null
          listDirty = true
      }
      // <-- 缺 mDelayedCallbackStartTime.remove
  }
  ```

**对比**：

- lib 没有 `mDelayedCallbackStartTime` 字段（C3 已记录），所以"漏调"是因为**对应字段不存在**——并非真漏调。
- 但如果未来回移 C3（加 `addAnimationFrameCallback(cb, delayMs)`），必须配套在 `removeCallback` 加 `mDelayedCallbackStartTime.remove(cb)`，否则会产生"已 remove 的 callback 仍在 delay map 里"的内存泄漏（虽然不大，但 B 路径 demo 多次 add/remove 会累积）。

**影响**：

- 当前无 delay 机制，无影响。
- 未来若回移 C3 必同步改本项。

**修复成本**：与 C3 同批修复（**5 行** SimpleArrayMap + 5 行 remove）。

---

### ✔️保持简化 🟡 ⑥ `addAnimationFrameCallback` 把 `scheduler.start()` + `postFrameCallback(::onTick)` 拆为两步 —— 隐式 start/stop 边界模糊（中）

> **判定**：lib `TickScheduler` 接口注释明示 start/stop 显式化（vs-oppo-15 §B-1），新写 `TickScheduler` 实现者只要按接口注释落实"空则停"即可。

**证据**：

- **lib** `AnimationHandler.kt:53-57`：
  ```
  if (animationCallbacks.isEmpty()) {
      scheduler.start()
      scheduler.postFrameCallback(::onTick)
  }
  ```
- **OPPO A** `AnimationHandler.java:174-178`：单步 `this.mProvider.postFrameCallback()`（provider 内部隐含 start）。
- **OPPO B** `AnimationHandler.java:135-138`：单步 `getProvider().postFrameCallback()`（provider 内部隐含 start）。

**对比**：

- vendor 的 `AnimationFrameCallbackProvider` 把"调度回路启动"和"注册一个 callback"绑在一起（provider 自身就是 `Runnable` / `Choreographer.FrameCallback`），`postFrameCallback()` 隐含"启动/续帧"。
- lib 的 `TickScheduler` 把两个职责拆开：`start()` 启调度、`postFrameCallback()` 仅注册一个 callback。**`start()` 是空操作**当 scheduler 已在 running 状态（`ScheduledTickScheduler:58-63` / `HandlerTickScheduler:55-59` 都有 `if (running) return`）。

**影响**：

- 语义对齐（`start()` 在已 running 时是 no-op），但"什么时候 stop"语义变模糊：vendor 由 provider 自动判断（`mAnimationCallbacks.size() > 0` 才续帧），lib 由 scheduler 自身判断（`ScheduledTickScheduler.kt:85-87` / `HandlerTickScheduler.kt:73-78` 都检查 `callbacks.isEmpty()`）—— 实际结果等价，但 lib 多了一个"空则停"的隐性约束，需要在每个 TickScheduler 实现里手动遵守。
- **隐患**：若新写一个 `TickScheduler` 实现忘了加"空则停"逻辑，会出现"无 callback 时仍 tick" 的 CPU 浪费。

**修复成本**：0 行（保持现状），但**应在 `TickScheduler` 接口注释加 KDoc 说明**"实现者必须在 tick 末尾判空，停调度"。

---

### ✔️保持简化 🟢 ⑦ `cleanUpList` 用 `removeAll { it == null }` 高阶函数 —— reverse loop 行为消失（低 / 形式）

> **判定**：lib 单线程使用，list 不会被并发修改，无 CME 风险；语义等价 + 性能在 demo 规模无差。

**证据**：

- **OPPO A** `AnimationHandler.java:119-128`：
  ```
  private void cleanUpList() {
      if (this.mListDirty) {
          for (int size = this.mAnimationCallbacks.size() - 1; size >= 0; size--) {
              if (this.mAnimationCallbacks.get(size) == null) {
                  this.mAnimationCallbacks.remove(size);
              }
          }
          this.mListDirty = false;
      }
  }
  ```
- **lib** `AnimationHandler.kt:104-108`：
  ```
  private fun cleanUpList() {
      if (!listDirty) return
      animationCallbacks.removeAll { it == null }
      listDirty = false
  }
  ```

**对比**：

- OPPO 用 reverse loop 手动 `remove(size)`，lib 用 `removeAll { ... }` 高阶函数。
- `removeAll` 内部对 `ArrayList` 是 forward iteration + `System.arraycopy` 移位；reverse loop 是反向遍历 + 尾段前移。

**影响**：

- 两者**最终 list 状态等价**（所有 null 槽被压缩到末尾并移除）。
- 性能上：reverse loop 在 null 槽占比高时稍优（不需要每次都 arraycopy 前面所有元素），但 `mutableListOf` 在 demo 规模下无差。
- 唯一语义差别：reverse loop **不会触发 ConcurrentModificationException** 即使 list 在迭代中被修改（因为是反向 + size-bound）；`removeAll` 走 `Iterator`，**若在调用前 list 被并发修改会抛 CME**。但 lib 是 per-thread 使用（AnimationHandler 自身的 add/remove 都从同一线程调），实际无并发。
- 这条只是实现细节，**不是真 bug**。

**修复成本**：0 行（保持现状）。如要 1:1 对齐 OPPO：4 行 reverse loop 重写。

---

### ✔️保持简化 🟢 ⑧ `doAnimationFrame` 不内联 cleanUpList —— 调用顺序依赖 onTick 正确实现（低 / 形式）

> **判定**：lib 当前所有调用方都是 `onTick → doAnimationFrame → cleanUpList`，无直接调 `doAnimationFrame` 的调用面。

**证据**：

- **OPPO A** `AnimationHandler.java:130-138`：cleanUpList 在 doAnimationFrame 末尾内联（`:137`）
- **OPPO B** `AnimationHandler.java:147-156`：同（`:155`）
- **lib** `AnimationHandler.kt:84-88`：cleanUpList 在 onTick 末尾调（`:87`），doAnimationFrame 不调

**影响**：

- lib 把 cleanUpList 从 doAnimationFrame 拆出到 onTick：若有人将来直接调 `handler.doAnimationFrame(t)`（比如单元测试 mock），会**忘记 cleanUpList**，listDirty 累积。
- 当前所有调用方都是 `onTick → doAnimationFrame → cleanUpList`，无实际影响。
- 但 `doAnimationFrame` 命名暗示"一帧的所有动作"——实际它只做 dispatch，不做 cleanup，对调用方不直观。

**修复成本**：3 行（把 cleanUpList 移回 doAnimationFrame 末尾）。**或保持现状但加 KDoc 警告**。

---

### ⚠️未修复（3 行 @JvmStatic；本批不修因无 Java 调用面）🟢 ⑨ `animationCount` 实例属性 vs `getAnimationCount` 静态方法 —— Java 调用面差异（低 / 形式）

> **判定**：lib 全部 Kotlin 调用，`AnimationHandler.CominstanceAnimation.getAnimationCount()` 已可用；dumpsys / log 工具无 lib 用户。

**证据**：

- **OPPO A** `AnimationHandler.java:140-146`：
  ```
  public static int getAnimationCount() {
      AnimationHandler animationHandler = getInstance();
      if (animationHandler == null) {
          return 0;
      }
      return animationHandler.getCallbackSize();
  }
  ```
- **lib** `AnimationHandler.kt:173`：
  ```
  val animationCount: Int get() = instance.callbackSize
  ```

**影响**：

- lib 是实例属性（Kotlin 风格）；OPPO A 是静态方法（Java 风格）。
- Java 调用方调 lib 需要 `AnimationHandler.Companion.getInstance().getCallbackSize()` —— 多一跳。
- dumpsys / log 工具如果硬编码调 `AnimationHandler.getAnimationCount()`，lib 不直接支持。

**修复成本**：3 行，加 `@JvmStatic val animationCount: Int get() = instance.callbackSize` 暴露给 Java。

---

### ✔️保持简化 🟢 ⑩ `getInstance` lazy init 不带锁 vs lib `instance` getter 不带锁（低 / 形式）

> **判定**：ThreadLocal 自身保证 per-thread 单例 + atomic set，行为与 vendor A/B 1:1。

**证据**：

- **OPPO A** `AnimationHandler.java:158-168`：getInstance 内 `threadLocal.set(new AnimationHandler(null))` —— **无锁**，依赖 ThreadLocal 自身的 `initialValue()` 默认 null + `set` 操作 atomic。
- **OPPO B** `AnimationHandler.java:115-121`：同。
- **lib** `AnimationHandler.kt:139-141`：同。

**对比**：lib `instance` getter 内 `AnimationHandler(ScheduledTickScheduler()).also(threadLocalHandler::set)` —— 无锁。三者**行为一致**（依赖 ThreadLocal 自身语义），但理论上**首次访问的 `new AnimationHandler(...)` 内部**——lib 在构造期内调 `TickSchedulerHolder(scheduler)`（`:30`）走 `@Synchronized get()`（`:123-125`），**第一次构造时会触发锁开销**（如果其他线程同时首次访问）。

**影响**：实际无影响（首次构造代价小，ThreadLocal 保证 per-thread 单例）。

**修复成本**：0 行。

---

### ✔️保持简化 🟢 ⑪ A 路径 vs B 路径的语义分叉被 lib 单类合并 —— 复刻 B 路径时的隐患（低）

> **判定**：lib 单 `AnimationHandler` 偏向 A 路径（无 delay / 无 dispatcher / 有 testHandler / 三元 instance），B 路径专属仅 future dynamicanimation 复刻时需要；review 11 §B-1 已建议保持。

**证据**：

- **OPPO A** 与 **OPPO B** 在 `addAnimationFrameCallback` / `doAnimationFrame` / `removeCallback` 三个方法上**都有差异**（B 多 delay 机制），lib 单类同时承载两种语义。
- lib 选择**只承载 A 路径**（无 delay），所以"复刻 B 路径动画"需要补 delay 机制（C3、⑤）。

**影响**：当前 demo 都走 A 路径或 C 路径，无影响。

**修复成本**：见 C3。

---

## ④ 回移建议

### A. 值得补进 lib 的（按 ROI 排序）

| # | 项 | 成本 | ROI | 说明 |
|---|---|---|---|---|
| 1 | ⚠️未修复 🟥 修 `installThreadScheduler` silent early return → fail-loud | 5 行 + 单测 | 高 | 三处静默失败是隐藏陷阱；测试覆盖率为 0。改为 `throw IllegalStateException` 比 silent 安全 |
| 2 | ✅已修复（60bd048）🟥 修 `doAnimationFrame` 快照 size → 活取 size | 3 行 + 5 行单测 | 高 | 与 vendor A/B 一致；本帧内 addCallback 的回调 visibility 修复；修复后无副作用（contains 已 dedupe） |
| 3 | ⚠️未修复（6 行 ≤30 行内可改；本批不修）🟥 修 `replaceThreadScheduler` 激进换源 → 非破坏式 | 6 行 | 中 | 与 vendor B `setProvider` 语义对齐；单元测试可直接验证"换源后下帧立即到" |
| 4 | ⚠️未修复（依赖 C3；保持简化）🟡 回移 `addAnimationFrameCallback(cb, delayMs)` 重载 | 15 行 + 5 行单测 | 中 | 为未来复刻 B 路径铺路；同步修 `removeCallback` 加 `mDelayedCallbackStartTime.remove`（⑤） |
| 5 | ⚠️未修复（3 行；本批不修因无 Java 调用面）🟢 加 `getAnimationCount` / `animationCount` `@JvmStatic` 暴露 | 3 行 | 中 | dumpsys / log 工具调用面闭合 |
| 6 | ✔️保持简化 🟢 修 `cleanUpList` reverse loop 对齐 vendor（高阶函数 → 手写） | 4 行 | 低 | 形式问题，性能无差；可作为"教学"意义保留 |
| 7 | ✔️保持简化 🟢 加 `TickScheduler.onNewCallbackAdded` 默认空方法 + AnimationHandler 调用点 | 5 行 | 低 | vendor 也是空实现，但是接口最小化原则 |

### B. ✔️已保持简化（成本 > 收益 / 复刻 ROI 低）

| # | 项 | 保留理由 |
|---|---|---|
| 1 | B 路径的 `AnimationCallbackDispatcher`（B `:22-34`）双时钟域 | lib 单 `onTick(frameTimeNanos)` 入参统一处理，不需要 dispatcher 包装；mCurrentFrameTime 字段（C10）demo 无需求 |
| 2 | `autoCancelBasedOn(ObjectAnimator)`（A `:184-189`） | ObjectAnimator 是 platform 类，lib 复刻 layer 不重做，依赖 platform 默认 |
| 3 | `setFrameDelay` / `getFrameDelay`（A `:193-195, 212-215`） | lib 帧率构造期固定；运行期调帧率需求 demo 不存在 |
| 4 | `onNewCallbackAdded` 钩子（A `:26` 接口位） | vendor 也是空实现（`:57-58, 97-98`） |
| 5 | 公开 `onAnimationFrame` 命名（A `:197-202` public） | lib 用 lambda `postFrameCallback(::onTick)` 替代 public 入口，是结构变化驱动的合理简化 |
| 6 | 路径 D 的 SF 帧间隔对齐 | review 04 §2.3-9 已记录 lib 当前不服务路径 D；未来若复刻再补 |
| 7 | 公开 `mAnimationCallbacks` 字段访问（A `:15` package-private） | lib 用 private，封装更严 |
| 8 | 公开 `mListDirty` 字段访问（A `:16` package-private） | 同上 |
| 9 | A 路径 vs B 路径拆两个 AnimationHandler 类 | lib 单类简化；两个原厂类的语义分叉（B 有 delay）在 lib 当前 demo 不需要 |
| 10 | `getInstance` 加锁 / 双重检查锁 | lib 走 ThreadLocal 默认实现，无 race condition；与 vendor 行为一致 |

### C. 不在 lib 范围 / 不建议回移

- 框架 @hide `android.animation.AnimationHandler`（C 路径）源码不可见（不在 sources 树内），仅通过 import 链（`OplusExecutors.java:3,5,170`、`MultiDynamicAnimation.java:3,21`、`DynamicAnimation.java:3,18`）推断。lib 无 SfVsyncFrameCallbackProvider 替代品（review 04 §2.3-7 已识别），是合理的 hidden-API 降级。
- vendor A 路径的 `FrameCallbackProvider14.sHandler` ThreadLocal 缓存（A `:34, 47-55`）：lib `HandlerTickScheduler` 接受构造期注入 Handler，天然 per-Looper，不需要 ThreadLocal 缓存。
- vendor B 路径的 `AnimationCallbackDispatcher` 二次派发：lib 单层派发更简洁。

---

## 附 A：行级对位表（lib : 行号 ↔ 原厂 : 行号）

| lib `AnimationHandler.kt` | OPPO A `AnimationHandler.java` | OPPO B `AnimationHandler.java` | 备注 |
|---|---|---|---|
| `:22 class AnimationHandler(scheduler: TickScheduler? = null)` | `:12 class AnimationHandler` + `:111-117 ctor(AnimationFrameCallbackProvider)` | `:12 class AnimationHandler` + `:115-121 getInstance 内 new AnimationHandler()` | 构造签名不同 |
| `:25-27 fun interface AnimationFrameCallback` | `:19-21` | `:36-38` | 1:1 |
| `:30 private var schedulerHolder` | `:17 mProvider`（构造期注入） | `:15 mProvider`（lazy `:158-163`） | lib 间接层 |
| `:33 private val animationCallbacks` | `:15 mAnimationCallbacks`（非可空） | `:17 mAnimationCallbacks`（非可空） | lib 元素可空 |
| `:36 listDirty = false` | `:16 mListDirty = false` | `:20 mListDirty = false` | 1:1 |
| `:39 val scheduler get()` | `:17 mProvider` 直访 | `:158-163 getProvider()` | 包装层 |
| `:42-43 callbackSize` | `:148-156 getCallbackSize()` private | 无 | 1:1 语义 |
| `:51-61 addAnimationFrameCallback` | `:174-182` | `:135-145`（含 delay 形参） | lib 漏 onNewCallbackAdded |
| `:66-73 removeCallback` | `:204-210` | `:165-172`（含 mDelayedCallbackStartTime.remove） | lib 无 delay map |
| `:84-88 onTick` (private) | `:197-202 onAnimationFrame` (public) | `:26-33 dispatchAnimationFrame` (public) | lib 私有 + 命名简化 |
| `:94-101 doAnimationFrame` | `:130-138` | `:147-156` | lib 快照 size + runCatching |
| `:104-108 cleanUpList` | `:119-128` | `:96-105` | lib 用 removeAll 高阶 |
| `:110-118 swapScheduler` | 无 | `:174-176 setProvider`（仅换字段） | lib 激进 |
| `:121-126 TickSchedulerHolder` | 无（构造期注入） | 无（懒 field） | lib 自创 |
| `:132 threadLocalHandler` | `:13 sAnimationHandler` | `:14 sAnimatorHandler` | 1:1 |
| `:135-136 testHandler (@Volatile)` | `:14 sTestHandler`（非 volatile） + `:170-172 setTestHandler` | 无 | lib `@Volatile` 更稳 |
| `:139-141 instance` 三元 | `:158-168 getInstance` 三元 | `:115-121 getInstance` 二元 | 1:1（A 形态） |
| `:152-158 installThreadScheduler` | 无 | 无 | lib 自创 + 3 silent |
| `:166-170 replaceThreadScheduler` | 无 | 无（语义对齐 `:174-176 setProvider`） | lib 激进版本 |
| `:173 animationCount` | `:140-146 getAnimationCount` 静态 | 无 | 1:1 语义 |

---

## 附 B：原厂 A 与 B 的语义分叉（lib 单类合并的取舍）

| 维度 | A `androidx/core/animation/AnimationHandler` | B `androidx/dynamicanimation/animation/AnimationHandler` | lib 兼容策略 |
|---|---|---|---|
| 延迟启动 | 无 `delay` 形参；`addAnimationFrameCallback(cb)` | 有 `delay`；`addAnimationFrameCallback(cb, j8)` + `mDelayedCallbackStartTime` SimpleArrayMap + `isCallbackDue(cb, now)` | lib 无 delay（C3 遗漏） |
| Provider 接口 | 4 方法（`getFrameDelay / onNewCallbackAdded / postFrameCallback / setFrameDelay`） | 1 方法（`postFrameCallback`）抽象类 | lib 抽象 `TickScheduler` 6 方法，比 A 多 2 个（start/stop/frameTimeNanos/frameCount），比 B 多 5 个 |
| Provider 实现 | `FrameCallbackProvider14` (Handler.postDelayed 16ms) + `FrameCallbackProvider16` (Choreographer 16ms) | `FrameCallbackProvider14` (Handler.postDelayed **10ms**) + `FrameCallbackProvider16` (Choreographer) | lib `ScheduledTickScheduler` 16ms + `HandlerTickScheduler` 16ms |
| testHandler | 有（`:14, 170-172`） | 无 | lib 仿 A 有 |
| setProvider | 无 | 有（`:174-176`） | lib `replaceThreadScheduler`（激进版） |
| getInstance | 三元（testHandler + ThreadLocal） | 二元（仅 ThreadLocal） | lib 仿 A 三元 |
| getAnimationCount | 静态（`:140-146`） | 无 | lib 实例 val |
| getFrameTime | 无 | 静态（`:107-113` 暴露 mCurrentFrameTime） | lib 无 |
| 自续帧位置 | `onAnimationFrame` 末尾（A `:197-202`） | `dispatchAnimationFrame` 末尾（B `:26-33`） | lib 委托给 TickScheduler |
| mCurrentFrameTime | 无 | `long mCurrentFrameTime = 0` (`:19`) + 在 dispatcher 内赋值（`:27`） | lib 无（C10 遗漏） |
| autoCancel | `autoCancelBasedOn(ObjectAnimator)` (`:184-189`) | 无 | lib 无 |
| AnimationCallbackDispatcher | 无 | 有（`:22-34`） | lib 无 |

**取舍评估**：

- lib 单类**偏向 A 路径**（无 delay、无 dispatcher、有 testHandler、getInstance 三元），辅以 TickScheduler 抽象接口（B 的最小集）。
- B 路径专属（delay / dispatcher）若未来需要复刻 dynamicanimation 系 FlingAnimation / SpringAnimation，需补 `mDelayedCallbackStartTime` + `isCallbackDue` + dispatcher。
- **不建议拆 lib AnimationHandler 为 A/B 两个类**：保持单类简单，B 路径 demo 当前为零。

---

## 附 C：lib 单元测试覆盖率分析

文件：`lib/src/test/java/com/asyncanimator/core/anim/AnimationHandlerTest.kt`（共 74 行，4 个测试）。

| 测试 | 覆盖的字段/方法 | 缺失覆盖 |
|---|---|---|
| `testThreadLocalInstance:22-26` | `instance` ThreadLocal 单例 | ❌ testHandler 优先级未测；❌ lazy init 触发顺序未测 |
| `testAddAndRemoveCallback:29-43` | `addAnimationFrameCallback` + `callbackSize` + `removeCallback` | ❌ null 槽保留未测；❌ listDirty 状态未测 |
| `testCallbackReturnsTrueEndsAnimation:46-56` | `callbackSize` after add | ❌ doAnimationFrame 的 boolean 返回语义未真跑（只验 size） |
| `testAddSameCallbackTwice:59-65` | `addAnimationFrameCallback` 幂等 | ❌ first-registration 触发 start + postFrameCallback 未测 |
| `testRemoveNonExistentCallback:68-74` | `removeCallback` 不存在的 callback | ❌ cleanUpList 实际执行未测（removeNonExistent 不走 idx>=0 分支） |
| （缺）| `installThreadScheduler` 三 silent 路径 | ❌ 完全没测 |
| （缺）| `replaceThreadScheduler` / `swapScheduler` | ❌ 完全没测 |
| （缺）| `TickSchedulerHolder` 懒构造 | ❌ 完全没测 |
| （缺）| `runCatching` 异常隔离（doAnimationFrame 抛异常的 callback） | ❌ 完全没测 |
| （缺）| `cleanUpList` 实际压缩 null 槽（多次 remove 后下一帧再读 callbackSize） | ❌ 间接覆盖，缺独立断言 |
| （缺）| `doAnimationFrame` 本帧内 add 新 callback 的 visibility（C-1） | ❌ 完全没测 |

**覆盖率评估**：5 个测试覆盖了"基础单例 + add/remove 幂等性"，但**未覆盖 11 个关键行为点**（包括 review 04 §2.3-3 标识的 setProvider 激进换源、review 04 §2.3-8 标识的 runCatching 异常隔离、§2.1-3 标识的 first-registration 触发 start）。

---

## 附 D：关键证据速查

| 论断 | 证据 |
|---|---|
| lib AnimationHandler 主类 175 行 | `D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/core/anim/AnimationHandler.kt`（dump.py 读明文） |
| lib ThreadLocal 单例 + testHandler 钩子 + 三元查找 | `AnimationHandler.kt:132 threadLocalHandler`、`:135-136 testHandler`、`:139-141 instance` |
| lib first-registration 触发 start + postFrameCallback | `AnimationHandler.kt:53-57` |
| lib removeCallback 懒删除（null + listDirty） | `AnimationHandler.kt:66-73` |
| lib cleanUpList 惰性压缩 | `AnimationHandler.kt:104-108` |
| lib doAnimationFrame 快照 size + runCatching | `AnimationHandler.kt:94-101` |
| lib onTick nanos→ms 换算 | `AnimationHandler.kt:85 frameTimeNanos / 1_000_000L` |
| lib swapScheduler 激进换源 + replaceThreadScheduler | `AnimationHandler.kt:110-118, 166-170` |
| lib installThreadScheduler 三 silent 早退 | `AnimationHandler.kt:152-158` |
| lib animationCount 实例属性 | `AnimationHandler.kt:173` |
| lib unit test 覆盖率 | `AnimationHandlerTest.kt:22-26, 29-43, 46-56, 59-65, 68-74` |
| OPPO A ThreadLocal + sTestHandler + 三元查找 | `androidx/core/animation/AnimationHandler.java:13-14, 158-172` |
| OPPO A first-registration postFrameCallback | `androidx/core/animation/AnimationHandler.java:174-178` |
| OPPO A 懒删除 + cleanUpList | `androidx/core/animation/AnimationHandler.java:119-128, 204-210` |
| OPPO A onAnimationFrame 自续帧 | `androidx/core/animation/AnimationHandler.java:197-202` |
| OPPO A nanos→ms 换算 | `androidx/core/animation/AnimationHandler.java:88 j8 / AnimationKt.MillisToNanos` |
| OPPO A doAnimationFrame 活取 size + 无 try-catch | `androidx/core/animation/AnimationHandler.java:130-138` |
| OPPO A addCallback 调用 onNewCallbackAdded | `androidx/core/animation/AnimationHandler.java:181` |
| OPPO A getAnimationCount 静态 | `androidx/core/animation/AnimationHandler.java:140-146` |
| OPPO A autoCancelBasedOn | `androidx/core/animation/AnimationHandler.java:184-189` |
| OPPO A setFrameDelay / getFrameDelay | `androidx/core/animation/AnimationHandler.java:193-195, 212-215` |
| OPPO B ThreadLocal + mDelayedCallbackStartTime | `androidx/dynamicanimation/animation/AnimationHandler.java:14, 16` |
| OPPO B AnimationCallbackDispatcher 二次派发 | `androidx/dynamicanimation/animation/AnimationHandler.java:22-34` |
| OPPO B isCallbackDue 延迟判定 | `androidx/dynamicanimation/animation/AnimationHandler.java:123-133` |
| OPPO B addAnimationFrameCallback 含 delay 形参 | `androidx/dynamicanimation/animation/AnimationHandler.java:135-145` |
| OPPO B setProvider 非破坏式 | `androidx/dynamicanimation/animation/AnimationHandler.java:174-176` |
| OPPO B removeCallback 先清 delay map | `androidx/dynamicanimation/animation/AnimationHandler.java:165-172` |
| OPPO B getFrameTime 静态 | `androidx/dynamicanimation/animation/AnimationHandler.java:107-113` |
| OPPO B FrameCallbackProvider14 10ms | `androidx/dynamicanimation/animation/AnimationHandler.java:13 FRAME_DELAY_MS = 10` + `:69-71 postDelayed(FRAME_DELAY_MS - …)` |
| OPPO C 框架 setProvider 调用点 | `com/oplus/basecommon/thread/OplusExecutors.java:170 setProvider(new SfVsyncFrameCallbackProvider())` |
| OPPO C 框架 import | `com/oplus/basecommon/thread/OplusExecutors.java:3,5`（import `AnimationHandler` + `SfVsyncFrameCallbackProvider`） |
| OPPO C 框架 MultiDynamicAnimation 挂载 | `com/android/quickstep/util/animation/MultiDynamicAnimation.java:3,21`（import + implements AnimationFrameCallback） |

---

## 附 E：与 review 04 / 12 的边界

| 本 review 与 review 04 / 12 的关系 |
|---|
| review 04 §2.1-3 "first-registration 才 postFrameCallback" → 本文 §①-8 + §③-⑥ 行级细化 |
| review 04 §2.3-3 "replaceThreadScheduler vs vendor setProvider 语义差" → 本文 §①-13 + §③-③ 行级细化（含 swapScheduler 4 步序列对照） |
| review 04 §2.3-8 "runCatching 异常隔离" → 本文 §②-A 保留为精确复刻 + §③ 注释"vendor 实测不 swallow，故严格说是行为分叉"的细化（review 04 标注 B5"有意简化"，本文标注"形式简化 + 实测非分叉"） |
| review 12 §①-6 "TickSchedulerHolder 懒构造 + swapScheduler" 1:1 ✓ → 本文 §①-14 + §①-19 细化 holder 与 swapScheduler 的对位关系 |
| review 12 §③-D "lib 退化为 postDelayed 帧源" → 本文不在范围（帧源层话题，在 §①-1 TickScheduler 对位表已隐含） |
| 本文新增（review 04/12 未覆盖）：①-11 快照 vs 活取 size、①-18 installThreadScheduler 三 silent、②-C 遗漏清单 10 项、③-① 快照 size bug、③-② install silent bug、③-⑤ removeCallback 漏 delay map、③-⑨ animationCount 静态入口、附 A 行级对位表、附 B A vs B 语义分叉、附 C 单测覆盖率分析 |


## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **60bd048** — doAnimationFrame 每轮重读 size 对齐 vendored core：添加回调当帧可见；installThreadScheduler 的隐性时序契约保留
- **dbde195** — ChoreographerTickScheduler 替代 HandlerTickScheduler 成为 launcher.anim 主帧源

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。