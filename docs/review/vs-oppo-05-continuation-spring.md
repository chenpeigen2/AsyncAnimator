# 区域 5 对比 Review：续行动画层（continuation / spring 接力）

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/continuation/`（`OplusValueAnimator.kt`、`RecordInputInterpolator.kt`）
> - **原厂**：`D:/oppo_a6_launcher/sources`（OPPO ColorOS 15 Launcher `com.android.launcher 15.8.24` JADX 反编译源码）
>
> 背景结论见 `docs/animation-thread-analysis-v4.md` 与 `docs/animation-trace-validation.md`。本区域覆盖原厂 `com/oplus/quickstep/utils/OplusValueAnimator.java`（495 行 Kotlin 反编译产物，`@SourceDebugExtension` 指向 `AppToOverviewContinuationHelper.kt`）+ `RecordInputInterpolator.java` + 三处续行调用方（`AppSwipeToRecentContinuationHelper.java`、`RecentsViewAnimUtil.java`、`BaseActivityInterface.java`、`VirtualBtnToRecentContinuationHelper.java`、`TaskViewUtils.java`）。
>
> 取证方法：lib 侧直接用 python 读 UTF-8 原文；原厂侧因 DLP 加密采用相同方法读 UTF-8（明文已解锁），行号为 JADX 反编译文本行号。本区域是 4 份前置 review（`01`~`04`）未单独立项的"续行+弹簧"交集补全——核心目的是把 review 04 末尾列出的"续行不工作"风险独立量化。

---

## 1. 类对应关系表

| lib 类 / 成员 | 原厂类 / 成员 | 关系与证据 |
|---|---|---|
| `continuation/RecordInputInterpolator.kt:11-18` | `com/oplus/quickstep/utils/RecordInputInterpolator.java:25-40` | 1:1 镜像。lib `var inputed = 0f` (`:13`) ↔ 原厂 `private float inputed;`（Java 字段默认 0f，`RecordInputInterpolator.java:26`）；lib `getInterpolation` (`:16-18`) ↔ 原厂 `:32-35`（写 inputed 后委托 realInterpolator）。**注意**：review 04 §2.3-1 声称"lib inputed 初值 -1f、原厂 0f"是**事实错误**——本轮重新核证两边都是 0f。 |
| `continuation/OplusValueAnimator.kt:30-79`（委托骨架 + double-write） | `com/oplus/quickstep/utils/OplusValueAnimator.java:160-330`（主类） | 委托模式镜像但有结构性偏差：lib `setCurrentFraction` (`:42-45`) ↔ 原厂 `:283-289`；lib `setInterpolator` (`:47-50`) ↔ 原厂 `:292-298`；lib `setDuration` **未 override**（缺）↔ 原厂 `:319-327`；lib 委托 6 方法 `start/cancel/end/pause/isRunning/getDuration/addListener` (`:55-78`) ↔ 原厂 `:194-208, :209-222, :225-239, :267-281, :261-264, :247-250, :241-244`（原厂还多了 `getCurrentPlayTime` 委托 `:241-244`、lib 缺）。 |
| `continuation/OplusValueAnimator.kt:151-160`（`CURRENT_FRACTION` FloatProperty） | `com/oplus/quickstep/utils/OplusValueAnimator.java:39-56`（`Companion$CURRENT_FRACTION$1`） | 镜像但 get 分支不同：lib get 直接读 `param.currentFraction`（`:156`）；原厂 get 在 anim == null 或 param == null 时返回 `-1.0f`、否则读 `param.getCurrentFraction()`（`:43-50`）。setValue 都走 `setCurrentFraction`。 |
| `continuation/OplusValueAnimator.kt:124-147`（`generateContinuationAnim`） | `com/oplus/quickstep/utils/OplusValueAnimator.java:89-120`（`Companion.generateContinuationAnim`） | 主流程镜像：`RecordInputInterpolator` inputed 回填 param → 边界检查 → 新 timeController + AnimParam copy → 接 LinearInterpolator → 起点→1.0。**关键差异**：lib 共享 `anim.param`（`:139` 用 `anim.param.copy()`，**但 data class copy 实际是浅拷贝独立对象**——这点 review 04 误判了），原厂用 `AnimParam.INSTANCE.copy(anim.getParam())`（`OplusValueAnimator.java:105`）也是新对象；二者结果等价。 |
| `continuation/OplusValueAnimator.kt:117-121`（`ofFloat(isAsync, values)` 工厂） | `com/oplus/quickstep/utils/OplusValueAnimator.java:127-150`（`Companion.generateAnim(name, param, timeController)` 私有 3 参 + `:176-180` 公开 2 参静态） | lib 是简化工厂（仅 `setFloatValues` 后返回），原厂是真构造器（含 `setObjectValues`、evaluator 自动选择 `IntEvaluator`/`FloatEvaluator`、`setDuration`、`setInterpolator` 一条龙）。同名"generateAnim"在原厂还有 `RecentsViewAnimUtil.java:1873, 1890` 等多处调用，承担真实的"包装成 OplusValueAnimator"工作。 |
| `continuation/OplusValueAnimator.kt:11-15`（`typealias ValueApplicator`） | `com/oplus/quickstep/utils/OplusValueAnimator.java:154-157`（`ValueApplicator` Java interface） | 类型壳 1:1。 |
| `continuation/OplusValueAnimator.kt:82-90`（`AnimParam` data class, 7 字段） | `com/oplus/quickstep/utils/OplusValueAnimator.java:330-485`（`AnimParam` Kotlin data class, 4 final 字段 + 3 var 字段） | 字段数与语义一致：startValue / endValue / typeEvaluator / valueApplicator（final）+ currentFraction / duration / interpolator（var）。**差异**：原厂有 `TypeEvaluator` 字段 + `setEvaluator` 入口；lib 砍掉。原厂有 3 final 字段 + 可变 setter；lib 7 字段全 data class 可变。 |
| `continuation/OplusValueAnimator.kt:95-111`（`TimeControllerObjectAnimator`） | 原厂无对应类——timeController 是平台 `android.animation.ObjectAnimator`（`OplusValueAnimator.java:104`，`objectAnimator = new ObjectAnimator()`） | **lib 复刻/移植件**，用 `PendingAnimation.ObjectAnimator(null, null, 0f, 1f)` 当骨架。setTarget 持有 anim 引用并 addUpdateListener 写 `setCurrentFraction` (`:107-111`)；setProperty 是 **no-op** (`:99-101`)。原厂由 `objectAnimator.setProperty(CURRENT_FRACTION)` (`:110`) 真实接线。 |
| （lib 无对应） | `com/oplus/quickstep/utils/AppSwipeToRecentContinuationHelper.java`（主续行业务方，含 4 个 `OplusValueAnimator<?>` 静态字段：continuationScaleAnim / continuationFullScreenAnim / continuationTransYAnim / continuationScrimBackgroundAnim） | 原厂用 `generateContinuationAnim` 在 `bind$lambda$1`/`:2`（`:213-231`）按 name (`RECENT_SCALE_IN_SWIPE_TO_RECENT` / `RECENT_TRANS_Y_IN_SWIPE_TO_RECENT` / `RECENT_FULL_SCREEN_IN_SWIPE_TO_RECENT` / `SCRIM_BACKGROUND_IN_SWIPE_TO_RECENT`) 把 4 个 OplusValueAnimator 串成续行链——业务层在 `startAlignEliminateAnim` 的 `bind()` 末尾（`:36536-`）把这些 anim 注入 `AnimatorSet` 后 start，onEnd 时各 listener 反向调用 `generateContinuationAnim` 各自接力。 |
| （lib 无对应） | `com/oplus/quickstep/utils/RecentsViewAnimUtil.java:1851-1900`（`createAppToOverview`） + `com/android/quickstep/BaseActivityInterface.java:456-463`（构造 4 个原 OplusValueAnimator 入口） | 原厂的"第一棒"——用 `AnimParam` + `setInterpolator(LINEAR)` + `generateAnim(name, animParam)` 构造续行链的 4 个原 OplusValueAnimator 实例。`BaseActivityInterface.java:463` 构造 `SCRIM_BACKGROUND_IN_SWIPE_TO_RECENT` 时显式带 `ArgbEvaluator.getInstance()`——证明原厂 `AnimParam.typeEvaluator` 字段是真实用到的，不是 dead field。 |

---

## 2. 保真度评估

### 2.1 精确复刻

1. **`RecordInputInterpolator` 1:1 镜像**。字段、构造器、`getInterpolation` 副作用、命名全部一致。
2. **timeController 委托的 6 生命周期方法结构**。`if (timeController != null) tc.x() else super.x()` 模式与原厂（`:194-208, :209-222, :225-239, :267-281, :261-264, :247-250`）逐句对应。
3. **`setCurrentFraction` 的 super + param 双写**。lib `OplusValueAnimator.kt:42-45` ↔ 原厂 `:283-285`。
4. **`setInterpolator` 的 super + param 双写**。lib `OplusValueAnimator.kt:47-50` ↔ 原厂 `:296-297`。
5. **`CURRENT_FRACTION` FloatProperty 的 setValue → setCurrentFraction**。lib `:160` ↔ 原厂 `:55`。
6. **`generateContinuationAnim` 主流程**。inputed 回填 → 边界检查 `[0,1)` 越界返 null → `setFloatValues(f, 1.0f)` → 条件 setDuration → `setInterpolator(LinearInterpolator())` 顺序与原厂 `:89-118` 一致。
7. **`AnimParam` 的 7 字段结构**。原厂 final/var 划分虽不同（4 final + 3 var vs lib 7 全 data class），但字段集合一致：`startValue/endValue/typeEvaluator/valueApplicator` + `currentFraction/duration/interpolator`。
8. **`addUpdateListener` 在构造时挂载**。lib init `OplusValueAnimator.kt:24-26` ↔ 原厂 `:166`（`addUpdateListener(new com.android.launcher3.taskbar.e(this, 2))`）。两者都用 `addUpdateListener` 在构造期把 `valueApplicator.applyValue(a.animatedValue)` 接进去——是"this 是 wrapper，valueApplicator 是真正落点"模型的核心。
9. **`typealias ValueApplicator` ↔ 原厂 `ValueApplicator` interface**。类型壳一致。

### 2.2 有意简化

1. **砍掉 `LogUtils.i` + `Debug.getCallers(15)` 调试埋点**。原厂每个生命周期方法（`:194-208, :209-222, :225-239, :267-281, :301-318, :292-300, :319-327, :283-289`）都带 `LogUtils.i(getTag(), …)` + 可选 `Debug.getCallers(N)` 堆栈；lib 用 `Trace.traceBegin/End`（`:133-145`）做续行事件的双色 trace，已够 demo/教学。
2. **`TypeEvaluator` 字段保留但缺自动选择**。原厂 `generateAnim` 私有 3 参（`:127-150`）在 `typeEvaluator==null` 时按 start/end 是否 `Integer`/`Float` 选 `IntEvaluator`/`FloatEvaluator`——是平台 `ValueAnimator` 不带 evaluator 就会强转崩溃的必备保护。lib 的 `AnimParam` 留了字段但没复刻这条"evaluator 自动选"逻辑，等于在 `ofFloat` 路径上隐形 trust 调用方已设好。
3. **`getName`/`getTag`/`getParam` 公开方法精简**。原厂 `getTag`（`:189-191`）用 `ObjectUtilsKt.uniquelyIdentifies(this) + "-" + name` 拼出唯一 tag 用于日志；lib 直接 `param.name` 替代，调试信息弱化但语义可读。
4. **`isHummingEnabledAndEnhance` / `startAlignEliminateAnim` / `bind` 整套续行业务编排未复刻**。lib `OplusValueAnimator` 只保留类本体 + `generateContinuationAnim` 工厂；原厂 `AppSwipeToRecentContinuationHelper.bind`（`:36536-`）负责从 `AnimatorPlaybackController.getAnimationPlayer().getDuration() - getCurrentPlayTime()` 算 `continuationAnimDuration`（默认回退 400ms），`startAlignEliminateAnim`（`:61322-`）把 4 个 OplusValueAnimator 注入 `AnimatorSet.alignEliminateAnim`，`endAlignEliminateAnimRunnable`（`:53591-` 附近内部类）做 `Executors.MAIN_EXECUTOR.getHandler().post` 收尾。lib 的"续行"概念只在 `generateContinuationAnim` 出口自洽，业务编排（4 anim 选谁接力、duration 怎么算、alignment 校正）由 demo 5 的舞台动画等价演示。已在 `Demo5ContinuationActivity.kt:11-12` 注释里自承。
5. **`VirtualBtnToRecentContinuationHelper` 未复刻**。原厂 `com/oplus/quickstep/utils/VirtualBtnToRecentContinuationHelper.java:268` 用同一套 `OplusValueAnimator.INSTANCE.generateContinuationAnim(...)` 路径，但入口常量 `RECENT_SCALE_IN_BUTTON_TO_RECENT` / `RECENT_OFFSET_IN_STATE_SWITCH` 不同（`VirtualBtnToRecentContinuationHelper.RECENT_*`），与 swipetoRecent 链路平行。lib 未对应。
6. **`setEvaluator` 入口缺失**。原厂由私有 `generateAnim` 内部统一设置；lib 既不 override 也不在 `ofFloat` 工厂里设置——`ofFloat` 路径上 `ObjectAnimator` 的 evaluator 链是默认（IntEvaluator），跟原厂的"按类型自动 FloatEvaluator"不一致。
7. **公开 API 裁剪到 demo 必需**。原厂 `OplusValueAnimator.java:176-180` 的 `JvmStatic` 静态 `generateAnim` / `generateContinuationAnim` / `getCURRENT_FRACTION` 在 lib 中以 `companion object` 形式保留，但 `INSTANCE` 单例的可见性降到 internal。

### 2.3 遗漏 / 偏差

1. **`TimeControllerObjectAnimator.setProperty` 是 no-op**（`OplusValueAnimator.kt:99-101`，`fun setProperty(prop: FloatProperty<*>?): TimeControllerObjectAnimator = this`）。原厂 `objectAnimator.setProperty(OplusValueAnimator.INSTANCE.getCURRENT_FRACTION())`（`:110`）是真实把 CURRENT_FRACTION FloatProperty 挂给 ObjectAnimator，让 ObjectAnimator 在 0..1 → f..1.0 推进时把每帧 setValue 写到 anim 的 `setCurrentFraction`。lib 缺这一步，所以 timeController 即使 start 起来，每帧 setValue 不会到 anim——见 §3-1。
2. **`TimeControllerObjectAnimator.setTarget` 实际写入靠 addUpdateListener 而非 ObjectAnimator 机制**。lib `:107-111` 在 setTarget 时挂 `addUpdateListener`，把 `animatedValue` 转 `setCurrentFraction`——这条路径**碰巧**能让续行段"动起来"（animatedValue 是 va 的 0..1 进度），但与原厂的 ObjectAnimator FloatProperty 机制不同语义：原版 timeController 的 `setFloatValues(currentFraction, 1.0f)` 决定 va 自身推进的 fraction 范围是 `[currentFraction, 1.0]`，**va 自身推进的就是 fraction**；lib 版本的 va 推进的是 0..1，要乘一个内部映射才能得到 fraction——而 setTarget 处的 update listener 直接把 `it` 当 fraction 写，于是 fraction 跑成 0..1，**等于从 0 重启而非从 currentFraction 续行**。
3. **`getCurrentPlayTime` 未委托给 timeController**。lib `OplusValueAnimator.kt:54-78` 缺 `override fun getCurrentPlayTime()`。原厂 `:241-244` 返回 `timeController.getCurrentPlayTime()`。影响：调用方在 timeController 模式下读 `getCurrentPlayTime` 在 lib 中读到 super（this 自身的 ValueAnimator 时间，它根本没被驱动——`setCurrentFraction` 之外没人在 tick 它），拿到的是默认值/初始值。
4. **`setDuration` 未 override**。lib 无 `override fun setDuration(...)`；原厂 `:319-327` 是 super + param.setDuration(duration) 双写 + 返回 super 调用的链（`setDuration` 是 `ValueAnimator` 的重载，返回 `ValueAnimator` 不是 `Unit`，签名差异。**这是编译期就报错的风险点**——Kotlin 的 `override` 签名必须严格匹配，`fun setDuration(duration: Long)` 与 `ValueAnimator.setDuration(long): ValueAnimator` 在 JVM 字节码层面是桥接，但 Kotlin 编译器对 `override` 仍要求返回类型兼容 `ValueAnimator`）。
5. **续行 `generateContinuationAnim` 缺 `setObjectValues`**。lib `:138` 只 `setFloatValues(f, 1.0f)`；原厂由 `Companion.generateAnim` 私有 3 参在内部 `setObjectValues(animParam.getStartValue(), animParam.getEndValue())`（`:129`）。lib 的 timeController 既然用 `PendingAnimation.ObjectAnimator(null, null, 0f, 1f)` 起步，已经硬编码 0f→1f 范围，不需要 start/end——但这也意味着 **timeController 的 `setFloatValues(f, 1.0f)` 在 lib 实际是"覆盖掉硬编码的 0f→1f 范围"**，如果 setTarget 内的 update listener 真的按 `animatedValue` 驱动新 anim，新 anim 看到的 fraction 是 0..1，不是 f..1.0——**再次坐实了偏差 2**。
6. **未 override `setEvaluator`**。lib 缺；原厂 `setEvaluator` 是 `ValueAnimator` 的 platform 方法，原厂并未 override，但私有 `generateAnim`（`:127-150`）在内部调用。原厂的 platform 行为是"按 start/end 类型默认 evaluator"，lib 因为用 `PendingAnimation.ObjectAnimator(null, null, 0f, 1f)` 起步且自己管理 `va`，evaluator 链断在 `va` 内部而非 `ObjectAnimator.ofFloat` 的默认上。
7. **`getTag` 简化带来的日志可读性差异**。原厂 `getTag`（`:189-191`）=`"<uniquelyIdentifies>-<name>"`，日志行形如 `"abc123-recentsScale: native start"`；lib 无此 tag，调试时无 anchor id。
8. **`LogUtils.isAlwayson` 失败回调日志缺失**。原厂 `generateContinuationAnim` 当 `continuationAnimDuration <= 0` 时若 `LogUtils.isAlwayson()` 仍会 `LogUtils.i(..., Debug.getCallers(15))`（`:114-115`）——一种"防御性日志"；lib 直接吞异常（`Trace.traceBegin/End` 也只是 trace 桩）。
9. **`AnimParam` 字段可见性差异**。原厂 4 final 字段（start/end/evaluator/applicator）通过构造器一次性写入，setter 只对 3 var 字段开放；lib data class 7 字段全 `var`，构造器只是设默认值，业务可随意 `param.evaluator = ...`。这影响"参数不可变"的设计意图——原厂隐式合约是"构造后只动 currentFraction/duration/interpolator"，lib 没守住。

---

## 3. 行为差异风险点

按"可能导致语义不同"的严重度排序：

> **⚠️部分修复（60bd048：setTarget 直写 setCurrentFraction 已接线；setProperty(CURRENT_FRACTION) 仍 no-op，平台 Property 写路径未建立）**
1. **（高 / bug 级）续行动画在 lib 里实际"从 0 重启"而非"从 currentFraction 续行"**。组合 §2.3-1/2/5：
   - `setProperty(CURRENT_FRACTION)` 是 no-op（偏差 1）→ timeController 的 ObjectAnimator 机制 FloatProperty 链路未建立。
   - `setTarget` 走 addUpdateListener（偏差 2）→ 把 va 的 0..1 直接当 fraction 写。
   - `setFloatValues(f, 1.0f)` 设定 va 自身推进范围为 [f, 1.0]（与偏差 2 的 update listener 处理逻辑冲突）→ 看 lib `:104-105` 的 `setFloatValues` 是直接透传到 `super.setFloatValues(*values)`（lib `OplusValueAnimator.kt:104-105` 继承自 `PendingAnimation.ObjectAnimator.setFloatValues` 是 `va.setFloatValues(*values)`）——所以 va 实际跑 0..1 还是 f..1.0 取决于 `super.setFloatValues` 是否被调用过。
   - `super.setFloatValues(f, 1.0f)` 在 lib `:138` 中确实被调用，所以 va 自身动画范围是 f..1.0，但 update listener 处 `it.animatedValue` 拿到的还是 va 的"绝对时间进度"经过 float range 映射后的值（f..1.0），**这部分对了**。但偏差 1 的 setProperty 是 no-op，所以"`setProperty(CURRENT_FRACTION)` 触发 va 写 anim.setCurrentFraction"的链路断。
   - 实际跑通链路：va tick → addUpdateListener (setTarget 装的) → 把 `it.animatedValue` 写 `anim.setCurrentFraction`。va 推进是 f..1.0，所以 `animatedValue` 在 [f, 1.0] 间走，写入 anim 的 fraction 也是 [f, 1.0]——**结果在 demo 5 上"碰巧"对**（demo 不计较细节，只看 banner），但**与原厂"FloatProperty 触发 setValue → setCurrentFraction"的契约不同**。任何依赖 `CURRENT_FRACTION` 公开访问 / 多 listener 链路的真实业务在 lib 上都会失效。
   - **业务可观察后果**：调用方在续行动画上对 `OplusValueAnimator.CURRENT_FRACTION.setValue(otherAnim, f)` 不会写 otherAnim（CURRENT_FRACTION 写路径走的是 platform `Property.setValue`，但它依赖 platform ObjectAnimator 的 `mPropertyMap`——lib 的 timeController 不是 platform ObjectAnimator，setProperty 是 no-op，这整条路径完全未建立）。

> **⚠️未修复（getCurrentPlayTime 未委托 timeController）**
2. **（高）`getCurrentPlayTime` 在 timeController 模式下读到错误值**（§2.3-3）。原厂委托到 timeController，业务在续行动画上读 `getCurrentPlayTime` 拿到"在 timeController 时间轴上的当前位置"；lib 读 super（`this` 自身 ValueAnimator，**从未被 tick**——它的全部"tick"来自 `setCurrentFraction(f)` 外部写入），拿到 0 / 未定义。
   - **业务可观察后果**：`AppSwipeToRecentContinuationHelper.startAlignEliminateAnim`（`:66030` 附近）依赖 `continuationScaleAnim.getDuration() - getCurrentPlayTime()` 算剩余时长。在 lib 上这个差值始终是 `getDuration()` 自身（永远没播过），于是 `jLongValue` 走 `continuationAnimDuration / 2` 兜底分支（`:66350`），**整段时序错位**。

> **⚠️未修复（setDuration 未 override 双写 param）**
3. **（中）`setDuration` 未 override + 签名不匹配**（§2.3-4）。Kotlin `override fun setDuration(duration: Long)` 与 platform `ValueAnimator.setDuration(long): ValueAnimator` 在 `override` 关键字下是允许的（Kotlin 编译器对 Java 父类方法返回类型有协变容忍），但在 lib 中压根没写——所以调用方 `anim.setDuration(200L)` 在 timeController != null 模式下**不会写到 timeController**，原厂会。原厂的 3 步：super.setDuration → return animator → param.setDuration（`:323-326`）。lib 中 super.setDuration 会调到这个 OplusValueAnimator 自身的 ValueAnimator（**它未被 tick 也没用**——任何对它的 setDuration 都不影响 timeController 推进），param.setDuration 直接缺。`AnimParam.duration` 字段永久是构造时 `generateAnim` 私有 3 参（lib 没复刻）的初值 `0L`。
   - **业务可观察后果**：原厂"传 `durationMs = 0` → 跳过 setDuration（`:112`），由 param 自带的 duration 兜底"——这个分支在 lib 上跑得通（因为 lib 也不会调 setDuration）；但传 `durationMs > 0` 在 lib 上**无效**，timeController 用 0L 默认时长。

> **⚠️未修复（Int/Float evaluator 自动选择缺失，演示用 Float 不触发）**
4. **（中）`TypeEvaluator` 缺自动选择，Float/Int 不匹配时崩溃**（§2.2-2）。原厂 `generateAnim` 私有 3 参（`:127-150`）有 IntEvaluator/FloatEvaluator 自动选择，缺 evaluator 时会 LogUtils.i 然后 return null（`LogUtils.i("OplusValueAnimator", "generateAnim fail")` `:136`）。lib 完全没有这条防线——`PendingAnimation.ObjectAnimator` 内部 va 硬编码 `setFloatValues(0f, 1f)`，animator 的 `animatedValue` 永远是 Float；如果业务传 `AnimParam(startValue=0, endValue=100, applicator={ it -> view.setBackgroundColor(it as Int) })`，va 推进给 applicator 的将是 0f/1f（Float），强转 Int = 0，**视觉上动画不播、applicator 始终收到 0**。原厂会走 IntEvaluator 分支正确产出 0/100。

> **⚠️待复核（lambda 类型细节，正文后半未精读）**
5. **（中）`addUpdateListener` lambda 类型问题**。lib `OplusValueAnimator.kt:24-26` 用 `addUpdateListener { a -> param.applicator?.invoke(a.animatedValue) }`，applicator typealias 是 `(value: Any?) -> Unit`。原厂 `OplusValueAnimator.java:166` 用 `addUpdateListener(new com.android.launcher3.taskbar.e(this, 2))`（一个 JADX 无法展开的 SAM 桥），其内部转 `_init_$lambda$0`（`:169-173`）调 `this$0.param.getValueApplicator().applyValue(it.getAnimatedValue())`——`applyValue` 接受 `Object`。两边的 applicator 入口签名都是 `Any?`/`Object`，签名层一致。
   - 但**原厂 listener 是 `com.android.launcher3.taskbar.e` 内部类的 KFunction 引用**（被 JADX 用 `e(this, 2)` 编号），与 `init { ... }` 时的 lambda 等价，**没有捕获 this$0**（用构造参数传）；lib 直接 lambda 捕获 this。两者都正常，唯一差别是 lib 的 lambda 是 anonymous class，JADX 反编译友好度不同——不算语义差异。

> **⚠️未修复（setTarget 持 strong ref）**
6. **（中）`setTarget` 持有 strong reference**。lib `OplusValueAnimator.kt:108` `this.target = target` 是强引用，timeController 与 anim 互相强引用形成 GC 根。原厂 `objectAnimator.setTarget(oplusValueAnimatorGenerateAnim)`（`:109`）也强引用，**无差**——记录下来只是确认 lib 没引入额外 leak。
> **✔️保持简化（Trace 替代 LogUtils 已够）**
7. **（低）`LogUtils.i` 失败回调日志缺失**（§2.3-8）。生产排查时，调用方传 `durationMs <= 0` 在原厂会进 `LogUtils.isAlwayson()` 分支打 `Debug.getCallers(15)` 堆栈；lib 完全静默。`Trace.traceBegin/End` 是 trace marker 不带调用栈。
> **✔️保持（getTag 简化）**
8. **（低）`getTag` 简化**（§2.3-7）。`LogUtils.i(getTag(), ...)` 在原厂所有委托方法都打 tag，lib 完全没这套——日志可读性下降。
> **✔️保持（AnimParam 全 var 教学简化）**
9. **（提示）`AnimParam` 字段全 var**（§2.3-9）。原厂 "构造后只动 3 var 字段" 的隐式合约在 lib 不成立。lib 的 `param.evaluator = ...`、`param.applicator = ...` 在生成后仍可改——不是 bug，但**与原厂"param 不可变"的设计意图不一致**。

> **⚠️未修复（AppSwipeToRecentContinuationHelper 未移植）**
10. **（高 / 跨类联动）`AppSwipeToRecentContinuationHelper` / `RecentsViewAnimUtil` / `BaseActivityInterface` 整套续行业务未复刻**（§2.2-4）。Lib 即使把 `OplusValueAnimator` 修得与原厂 1:1，仍缺：
    - 原 4 个 OplusValueAnimator 实例的构造入口（`RecentsViewAnimUtil.createAppToOverview` 注入 `LINEAR` interpolator + `pa.getDuration()` 时长）；
    - `AppSwipeToRecentContinuationHelper.bind` 从 `AnimatorPlaybackController` 算 `continuationAnimDuration`；
    - `startAlignEliminateAnim` 把 4 anim 装进 `alignEliminateAnim: AnimatorSet`；
    - 4 个 OplusValueAnimator 的 `addListener` 在 onEnd 时反向调 `generateContinuationAnim` 形成接力链（`bind$lambda$1`/`:2`）。
    
    这些不在 `continuation` 包自身，是 `controller` 区域。Lib 没有 `AppSwipeToRecentContinuationHelper` 也没有 `RecentsViewAnimUtil`，意味着 **"4 个 OplusValueAnimator 互相接力的完整业务流"在 lib 中无处跑**——Demo 5 的舞台动画是单 anim 演示，不是 4 路并行接力。

---

> ⚠️ ④ 建议表各行待逐条打标（风险项状态见 ③ 打标）。
## 4. 回移建议

### 4.1 值得补进 lib 的

1. **修 §3-1 的"续行从 0 重启"风险**——核心是把 `TimeControllerObjectAnimator.setProperty(CURRENT_FRACTION)` 真正接线。当前 lib 用 `PendingAnimation.ObjectAnimator(null, null, 0f, 1f)` 起步，该类缺 setProperty 入口实现，setProperty 是 no-op。最直接的修法是放弃 `PendingAnimation.ObjectAnimator` 复刻，让 `TimeControllerObjectAnimator` 直接 `extends android.animation.ObjectAnimator`——然后 `setTarget(newAnim)` + `setProperty(CURRENT_FRACTION)` + `setFloatValues(f, 1.0f)` 全部走 platform 路径。这与 review 04 §4.1-2 的建议一致，本区域只是把"bug 级"标号打上去。
2. **补 `getCurrentPlayTime` 委托**（§3-2）。5 行，与 isRunning/getDuration 同一模式：`override fun getCurrentPlayTime(): Long = timeController?.currentPlayTime ?: super.getCurrentPlayTime`（需要查 `TimeControllerObjectAnimator` 是否暴露 `currentPlayTime` getter；如未暴露，给 `PendingAnimation.ObjectAnimator` 加 `fun currentPlayTime(): Long` 转发到 va）。
3. **补 `setDuration` override + 双写**（§3-3）。原厂 `:319-327` 是 super + param 双写。lib 加：
    ```kotlin
    override fun setDuration(duration: Long): ValueAnimator {
        val a = super.setDuration(duration)
        param.duration = duration
        return a
    }
    ```
    返回 `ValueAnimator` 与 platform 签名一致。这与 review 04 §2.3-5 的"override setInterpolator"是同一族（都是"super + param 双写"），回移可打包成一批"参数双写一致性"修复。
4. **补 `setInterpolator` 在 timeController != null 模式下同步传给 timeController**（隐含的偏差）。原厂 `setInterpolator`（`:292-298`）只写 super + param，不写 timeController——timeController 自己的 interpolator 在 `generateContinuationAnim` 末尾被设为 `LinearInterpolator`（`:117`）。所以 timeController 的 interpolator 跟 this 的 interpolator **是两套**：this 用 param.interpolator（业务设置的 RecordInputInterpolator），timeController 用 LinearInterpolator。Lib 也是这样（`generateContinuationAnim` 末 `timeController.setInterpolator(LinearInterpolator())`），无差。**回移时无需改这块**——记录下来确认"两套插值器各管各的"是设计意图。
5. **补 IntEvaluator/FloatEvaluator 自动选择**（§3-4）。在 `ofFloat`/`generateAnim` 工厂里加：
    ```kotlin
    if (startValue is Int && endValue is Int) va.setEvaluator(IntEvaluator())
    // else: FloatEvaluator 是 platform ofFloat 的默认，无需设置
    ```
    防 `Int → Float` 静默丢精度。
6. **补 `getTag`**（§3-8）。3 行，`"<identityHashCode>-<name>"` 形式。给 `LogUtils.i`（如保留）提供 anchor。

### 4.2 建议保持简化的

1. **不补 `AppSwipeToRecentContinuationHelper` / `RecentsViewAnimUtil` 业务编排**（§3-10）。这是 4 路 OplusValueAnimator 拼成 AnimatorSet 的续行业务层，不在 continuation 包自身，落在区域 6（待规划）的"业务编排"范畴。lib 的 Demo 5 用舞台动画单路演示续行概念，对教学目标够用；真实复刻 4 路 900+ 行 `startAlignEliminateAnim`（JADX 标注 `Code decompiled incorrectly`）成本不低，且依赖尚未复刻的 `RecentsView`、`AnimatorPlaybackController`、`TaskView` 等类型——留待区域 6 接力。
2. **不补 `VirtualBtnToRecentContinuationHelper`**。与 swipetoRecent 平行但不交叉，单独业务线。
3. **不补 `LogUtils.i` + `Debug.getCallers(15)` 调试埋点**（§2.2-1）。OEM 调试设施，lib 用 `Trace` 替代已够。
4. **不补 `TypeEvaluator` 完整 evaluator 链**。只在 `ofFloat` 上补 IntEvaluator 自动选择（§4.1-5）即可；ArgbEvaluator / PointFEvaluator 等业务特定 evaluator 由调用方按需设置，与原厂一致。
5. **保留 `AnimParam` 7 字段全 var**。这是 Kotlin data class 习惯；与原厂 "final/var 划分" 不一致但不影响语义。改回 final 需要拆成主构造 + var 复制——成本高、收益低。
6. **保留 `addUpdateListener` lambda 捕获 this**（§3-5）。与原厂 SAM 桥的差异仅在反编译产物层面，运行时等价。
7. **保留 `setTarget` 强引用**（§3-6）。原厂也强引用，无差。

---

## 附：关键证据速查

| 论断 | 证据 |
|---|---|
| 续行 4 个原 OplusValueAnimator 入口 | `com/oplus/quickstep/utils/RecentsViewAnimUtil.java:1873, 1890`；`com/android/quickstep/BaseActivityInterface.java:456-463`（显式带 `ArgbEvaluator.getInstance()`） |
| 4 个续行动画的 name 常量 | `com/oplus/quickstep/utils/AppSwipeToRecentContinuationHelper.java:11770, 11982, 11882, 12086`（`RECENT_FULL_SCREEN_IN_SWIPE_TO_RECENT` / `RECENT_SCALE_IN_SWIPE_TO_RECENT` / `RECENT_TRANS_Y_IN_SWIPE_TO_RECENT` / `SCRIM_BACKGROUND_IN_SWIPE_TO_RECENT`） |
| 续行 onEnd 触发 `generateContinuationAnim` | `AppSwipeToRecentContinuationHelper.java:21673, 21939, 22188, 22761`（4 个 `bind$lambda` 内 name 分发调 `OplusValueAnimator.INSTANCE.generateContinuationAnim(...)`） |
| `bind` 算 `continuationAnimDuration` | `AppSwipeToRecentContinuationHelper.java:36536-`（`animationPlayer.getDuration() - getCurrentPlayTime()`，默认回退 400ms，:36600-36610） |
| 4 个 OplusValueAnimator 装入 alignEliminateAnim: AnimatorSet | `AppSwipeToRecentContinuationHelper.java:61322-`（`startAlignEliminateAnim`） |
| 业务用 `getDuration() - getCurrentPlayTime()` 算续行剩余 | `AppSwipeToRecentContinuationHelper.java:66030-66350`（`lValueOf` 算剩余时长，决定 jLongValue 兜底） |
| 原厂 timeController 是平台 `android.animation.ObjectAnimator` | `OplusValueAnimator.java:104, 110`（`objectAnimator = new ObjectAnimator()` + `setProperty(CURRENT_FRACTION)`） |
| `setCurrentFraction` super+param 双写 | `OplusValueAnimator.java:283-289` |
| `setInterpolator` super+param 双写 | `OplusValueAnimator.java:292-298` |
| `setDuration` super+param 双写（lib 缺） | `OplusValueAnimator.java:319-327` |
| `getCurrentPlayTime` 委托（lib 缺） | `OplusValueAnimator.java:241-244` |
| `generateContinuationAnim` 完整流程 | `OplusValueAnimator.java:89-120`（inputed 回填 → 边界检查 → copy → setTarget → setProperty → setFloatValues → 条件 setDuration → setInterpolator(LinearInterpolator())） |
| `AnimParam.copy` 是真浅拷贝 | `OplusValueAnimator.java:353-360`（`new AnimParam<>(...)` + setDuration/setInterpolator/setCurrentFraction） |
| `AnimParam` 默认 `currentFraction = -1.0f` | `OplusValueAnimator.java:370`（构造器 `this.currentFraction = -1.0f;`） |
| `RecordInputInterpolator.inputed` Java 字段默认 0f | `RecordInputInterpolator.java:26`（`private float inputed;` 无显式初始化） |
| `LogUtils.i` + `Debug.getCallers(N)` 调试埋点 | `OplusValueAnimator.java:211, 227, 269, 287, 294, 303, 321` |
| `getTag` 用 `uniquelyIdentifies` 拼 anchor | `OplusValueAnimator.java:189-191` |
| `AppToOverviewContinuationHelper.kt` 是原 Kotlin 源 | `OplusValueAnimator.java` 顶部 `@SourceDebugExtension({"SMAP\nAppToOverviewContinuationHelper.kt\nKotlin\n*F\n+ 1 AppToOverviewContinuationHelper.kt\ncom/oplus/quickstep/utils/OplusValueAnimator...` |
| `startAlignEliminateAnim` JADX 反编译失败 | `AppSwipeToRecentContinuationHelper.java:61322`（`Code decompiled incorrectly, please refer to instructions dump.`） |
| Demo 5 自承 TimeControllerObjectAnimator 是 no-op | `D:/AsyncAnimator/demo/src/main/java/com/asyncanimator/demo/Demo5ContinuationActivity.kt:11-12` 注释 |


## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **60bd048** — RecordInputInterpolator.inputed -1f→0f、generateContinuationAnim param.copy + LinearInterpolator、setInterpolator 双写 param、TimeControllerObjectAnimator.setTarget 真实接线

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。