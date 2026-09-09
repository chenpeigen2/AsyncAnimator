# vs-oppo-13：AnimationSeqHelper seqId 配对与时间窗判定细节

> 对比双方：
> - lib：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/seq/AnimationSeqHelper.kt` + `DefaultAnimationSeqHelper.kt`
> - 原厂：`D:/oppo_a6_launcher/sources/com/oplus/quickstep/utils/AnimationSeqHelper.java`（130 行，classes5.dex，Kotlin 反编译）+ `DefaultAnimationSeqHelper.java`（43 行）
>
> 范围严格限定在 AnimationSeqHelper / DefaultAnimationSeqHelper 内的字段、方法、常量、调用方上下文。
> 不重复 review 03 §3-e 已经标过的那两条（seqId 无条件 ++、Intrinsics.areEqual 注释），但会在 §2/§3 给出**逐字段级别的行号证据与影响面**。
> 涉及到的上游文件 `com/android/quickstep/RecentsAnimationController.java:184,304`、`com/oplus/quickstep/utils/AnimationController.java:212,220,559-580`、`com/android/quickstep/OplusBaseTouchInteractionService.java:2036-2043`、`com/android/quickstep/inputconsumers/OtherActivityInputConsumer.java:244,257` 给出调用上下文证明。

---

## 1. 类对应关系表

| lib 类（文件） | 原厂类（文件：行） | 对应关系 |
|---|---|---|
| `launcher/seq/AnimationSeqHelper.kt`（95 行） | `com/oplus/quickstep/utils/AnimationSeqHelper.java:17` `public final class AnimationSeqHelper extends DefaultAnimationSeqHelper` | 精确对应，结构同构 |
| `launcher/seq/DefaultAnimationSeqHelper.kt`（22 行） | `com/oplus/quickstep/utils/DefaultAnimationSeqHelper.java:10` `public class DefaultAnimationSeqHelper` | 精确对应；lib 把 `Runnable` 改 `(() -> Unit)?`、`RecentsAnimationController` 改 `Any?`，其余签名同构 |
| `launcher/seq/AnimationSeqHelperKt`（无文件，只有 `MAX_DELAY_TIME` / `MAX_INTERCEPT_GESTURE_DELAY_TIME` 两个 internal const） | 原厂同名常量在 `AnimationSeqHelper.java:19,21` 作为 `public static final long`，包内 `f4.l` Pair 字段也直接挂在类上（`:32`） | lib 把常量提到文件顶层、降低可见性为 `internal`，文件不再以 `Kt` 命名（即不存在 `AnimationSeqHelperKt.java` 反编译产物） |

### 字段对应（OPPO：`AnimationSeqHelper.java` 行号 / lib：`AnimationSeqHelper.kt` 行号）

| 语义 | OPPO 字段 / 类型 / 行号 | lib 字段 / 类型 / 行号 | 备注 |
|---|---|---|---|
| 全局递增计数器 | `private long seqId` `:33` | `private var seqId = 0L` `:53` | 完全对应 |
| 缓存的延迟任务 | `private Runnable delayRunnable` `:25` | `private var delayAction: (() -> Unit)? = null` `:55` | 类型变体（见 §2 防御性变更） |
| 主线程 Handler | `private final Handler handler = new Handler(Looper.getMainLooper(), ...)` `:26` | `private var handler: Handler? = null` `:60` | lib **延迟初始化**：`getOrCreateHandler()`（`:69-76`）——给 JVM 测试环境无主 Looper 让路；OPPO 在字段处即时构造 |
| (controller, seqId) 配对 | `private f4.l<? extends RecentsAnimationController, Long> nextFinishSeqId` `:32`（`f4.l` 即 `kotlin.Pair`，field `f12314a` = first, `f12315b` = second） | `private var nextFinishSeqId: Pair<Any?, Long>? = null` `:63` | lib 用 `Pair<Any?, Long>`，与 `kotlin.Pair<RecentsAnimationController, Long>` 结构等价；类型从 `RecentsAnimationController` 泛化到 `Any?` |
| 私有常量 | `MSG_EXC_RUNNABLE = 1` `:22` | `MSG_EXC_RUNNABLE = 1` `:14` | 一致 |
| 私有常量 | `KEY_INTERRUPT_TRANSITION_START_ACTIVITY_SEQ_ID = "interrupt.transition.startActivity.seqId"` `:18` | `KEY_INTERRUPT_TRANSITION_START_ACTIVITY_SEQ_ID = "interrupt.transition.startActivity.seqId"` `:15-16` | 字符串字面量一致 |
| 私有常量 | `TAG = "AnimationSeqHelper"` `:23` | 未声明（lib 无 Log） | lib 不打 log |
| 私有常量 | `USE_SEQ = true` `:24` | 未声明 | 原厂声明但类内不引用（grep 全文 1 处出现，line 24），属于"声明备而不用"型常量，遗漏无影响 |

### 方法对应（行号粒度）

| 方法 | OPPO `AnimationSeqHelper.java:行号` | lib `AnimationSeqHelper.kt:行号` | 备注 |
|---|---|---|---|
| 构造器 | 默认空（`Companion OplusLauncher_...`）`:129` | 默认空 `:43` | 一致 |
| `updateSeqId()` private | `:50-54` | `:79` | 一致 |
| `addSeqId(Bundle)` | `:56-66` | `:82-86` | 见 §3-a |
| `canFinishRecent()` | `:68-71` | `:88-89` | 见 §3-b |
| `canInterceptGesture()` | `:73-76` | `:91-92` | 见 §3-b |
| `clearFinishRecentsRunnable()` | `:78-84` | `:104-107` | 一致（lib 多一层 `?.`） |
| `delayFinishRecents(Runnable)` | `:86-99` | `:95-103` | lib 多 `maxOf(0L, delay)` 钳制（§2 防御性变更） |
| `getNextFinishSeqId(RecentsAnimationController)` | `:101-109` | `:111-115` | 见 §3-c |
| `resetInterceptState()` | `:111-114` | 未 override | 见 §3-d |
| `updateNextFinishSeqIdIfNeed(RecentsAnimationController)` | `:116-129` | `:109-112` | 见 §3-e（review 03 §3-e 已标，本报告给完整行号证据 + 调用方影响面） |
| Handler `Callback.handleMessage(Message)` (private lambda) | `:35-48` | `:69-76`（包在 `getOrCreateHandler()` 里） | 一致；lib 多层 Looper 存在性检查 |

### 上游调用关系（OPPO 端，证明修改影响面）

| 调用方 | 行号 | 调用 | 影响 |
|---|---|---|---|
| `com/android/quickstep/RecentsAnimationController.finishController()` | `:184` | `oplusAnimManager.getAnimationSeqHelper().updateNextFinishSeqIdIfNeed(this)` | 每次 finish controller 入口；§3-e 的无差别 ++ 直接放大 seqId |
| `com/android/quickstep/RecentsAnimationController.finish(boolean,boolean)` | `:304` | `this.mController.finishBySeqId(z8, z9, getNextFinishSeqId(this))` | binder 反射调 AOSP `mController`，**seqId 是 finish 对齐 bundle 内 start seqId 的凭据**；§3-e 的无差别 ++ 直接破坏该对齐 |
| `com/oplus/quickstep/utils/AnimationController$AnimSuccessListener.onAnimationSuccess()` | `:220` | `OplusAnimManager.INSTANCE.getAnimationSeqHelper().updateNextFinishSeqIdIfNeed(this.$recentsController)` | recents anim 成功回调；同一 controller 实例每次成功都会再触发一次 |
| `com/oplus/quickstep/utils/AnimationController.canFinishRecentsAnim()` | `:573` | `!oplusAnimManager.getAnimationSeqHelper().canFinishRecent()` | §3-b 的缺闸门直接绕过"recent 收尾防抖" |
| `com/oplus/quickstep/utils/AnimationController$RemoveTasks$run()` | `:212` | `OplusAnimManager.INSTANCE.getAnimationSeqHelper().delayFinishRecents(new ...)` | 防抖或立即 finish recents；§3-b 改变"立即"分支的命中条件 |
| `com/android/quickstep/OplusBaseTouchInteractionService.onInjectDispatchInputEvent()` | `:2036,2043` | `canInterceptGesture()` + `resetInterceptState()`（ACTION_UP/CANCEL 路径） | §3-d 的 no-op reset 让手指抬起后下一帧手势仍被吞 |
| `com/android/quickstep/inputconsumers/OtherActivityInputConsumer` | `:244,257` | `oplusAnimManager.supportInterruption() && !oplusAnimManager.getAnimationSeqHelper().canFinishRecent()` | 双闸门结构，§3-b 缺一闸门 |

---

## 2. 保真度评估

### 2.1 精确复刻（行为可对齐）

- **`updateSeqId()` private 工具**（OPPO `:50-54` vs lib `:79`）：自增 + 写入 + 返回，两侧同构。
- **`clearFinishRecentsRunnable()`**（OPPO `:78-84` vs lib `:104-107`）：`removeMessages(MSG) + delayRunnable = null`；lib 多一层 `?.` 但语义等价。
- **Handler `Callback` 内的 4 行 trace + invoke + null clear**（OPPO `:35-48` vs lib `:70-76`）：traceBegin(8L, "exc delayRunnable") → `delayRunnable.run()` → null → traceEnd；lib 用 `delayAction?.invoke()` 包了一层空安全，行为等价。
- **`KEY_INTERRUPT_TRANSITION_START_ACTIVITY_SEQ_ID`** 字符串字面量：两侧一致（OPPO `:18` vs lib `:15-16`）。
- **`MSG_EXC_RUNNABLE = 1`**：两侧一致（OPPO `:22` vs lib `:14`）。
- **`addSeqId` 的 `bundle.putLong` 主干**（OPPO `:65` vs lib `:85`）：key/value 一致；两侧都依赖"调用方已分配 bundle"的契约。
- **`DefaultAnimationSeqHelper` 全部 no-op 默认**（OPPO `DefaultAnimationSeqHelper.java:11-42` vs lib `DefaultAnimationSeqHelper.kt` 全文件）：8 个方法都是空体 / true / 0L；签名侧 lib 用 `Any?` / `(() -> Unit)?` 替换 `RecentsAnimationController` / `Runnable`，形变但语义同。
- **`delayFinishRecents` 的 if/else 主结构**（OPPO `:87-99` vs lib `:95-103`）：`if (canFinishRecent()) { runnable.run(); return false; }` + trace + `clearFinishRecentsRunnable()` + `sendEmptyMessageDelayed(MSG, MAX - gap)` + return true，两侧同构。

### 2.2 有意简化（lib 注释中自认或合理的 demo 化）

- **`Runnable` → `(() -> Unit)?`**（lib `AnimationSeqHelper.kt:55`，`DefaultAnimationSeqHelper.kt:10,12`）：OPPO 接收 `Runnable`，非 null 强制（`:88,118,128,41` 都有 `Intrinsics.checkNotNullParameter`）；lib 接收可空 `(() -> Unit)?` 并用 `action?.invoke()` / 早 return。**这是 lib 的"调用方更宽容"选择**，把"传 null 必崩"放宽成"传 null 不做事"。调用方如果是 Java/SAM 路径，lambda 不会真的为 null，但若 demo/单元测试 stub 出 null 也不会 NPE——属于"为 demo 友好"的防御性变更。
- **`Bundle?` 同上**（lib `:82`）：OPPO `addSeqId(Bundle)` 在 `:58` 做 `checkNotNullParameter`；lib `Bundle?` + 早 return。
- **`RecentsAnimationController` → `Any?`**：lib 把 controller 类型参数从 `RecentsAnimationController` 泛化掉，方便 mock/单元测试；这是 JVM 库去 AOSP 平台依赖的标准做法。
- **Handler 延迟初始化**（lib `:60,67-78`）：注释自认"JVM 测试环境没有主 Looper"；OPPO 在字段处即时 `new Handler(Looper.getMainLooper(), ...)`（`:26`）。**代价**：`delayFinishRecents` 第一次进入才会触发 Handler 构造；语义与即时构造等价。
- **`maxOf(0L, delay)` 钳制**（lib `:101`）：OPPO `:96` 直接 `500 - AnimSeqTimeStamp.getTimeGapToLastRecentFinishTime()`，无钳制。**这条钳制逻辑上不可达**——因为进入这一行时 `canFinishRecent()` 已判定为 false，意味着 `gap <= 500`，故 `500 - gap >= 0`。属于零风险零收益的防御。  
  备注：唯一的理论可达路径是 `AnimSeqTimeStamp` 时间回退（系统时钟回调），但 lib 已统一改用单调时钟（review 12 §3-c），所以这条钳制确实是死代码。
- **`TAG` 常量省略**（lib 无 Log，相应 `LogUtils.i` 调用一并省掉）：OPPO `:64,70,75,120,126` 5 处 `LogUtils.i`；lib 全部静默。

### 2.3 遗漏（lib 中没有、且不一定是有意砍掉）

- **`MAX_GO_NORMAL_DELAY_TIME = 200L` 常量缺失**（OPPO `:20` `public static final long MAX_GO_NORMAL_DELAY_TIME = 200`）。grep 全树，`AnimationSeqHelper` 类内无任何 `MAX_GO_NORMAL_DELAY_TIME` 引用（`animation-thread-analysis-v4.md:1696` 是文档副本），属"声明备而不用"型常量。**对功能无影响**，但作为公共契约存在，调用方（其他子系统的 if-else 兜底）可能在 build-time 通过反射 / javadoc 拿这个值。成本：1 行 const。
- **`USE_SEQ = true` 常量缺失**（OPPO `:24`）：声明但类内 0 引用（仅 `@Metadata d2` 出现 1 次），属纯遗留常量。**完全可省**。
- **`resetInterceptState()` override 缺失**（OPPO `:111-114` 调用 `AnimSeqTimeStamp.resetLastStartAppTime()`；lib 的 `DefaultAnimationSeqHelper` 有 `open fun resetInterceptState() {}`，但 `AnimationSeqHelper` **没有 override**）。见 §3-d bug 级。
- **`supportInterruption()` 早返回**（OPPO `:59-62` 在 `addSeqId`、`:119-122` 在 `updateNextFinishSeqIdIfNeed`）：两侧检查 `OplusAnimManager.INSTANCE.supportInterruption()`，false 时打 log 后直接 return，**不增加 seqId、不写入 Bundle**。lib 的 `OplusAnimManager.supportInterruption()` 已硬编码 `true`（review 03 §2.2），所以现状是"lib 永远通过该闸门"。这是 review 03 已记的简化，**不重复列为本报告 bug**。
- **`Intrinsics.checkNotNullParameter` 全套**：lib 用 Kotlin null-safety 替代，签名层面允许 null。对应的"传 null 行为差异"已在 §2.2 记为有意简化。
- **`f4.l`/`kotlin.Pair` 类型精度**：OPPO 是 `Pair<? extends RecentsAnimationController, Long>`，lib 是 `Pair<Any?, Long>`。JVM 字节码层面相同（都是 `Pair`），但静态分析层面 lib 失去 controller 类型约束。

---

## 3. 行为差异风险点

下列按"风险等级 + 修复成本"组织。**Bug 级**指语义在原厂下与 lib 下会以可观察方式偏离。

### 3-a. ⚠️未修复（成本 2 行 / 当前不可观察）— `addSeqId` 缺 `supportInterruption()` 闸门  **[B级 / 修复 3 行]**

- **OPPO** (`AnimationSeqHelper.java:59-62`)：
  ```java
  if (!OplusAnimManager.INSTANCE.supportInterruption()) {
      LogUtils.i(TAG, "addSeqId, return directly due to fail to support multi apps interruption");
      return;
  }
  ```
  即"feature off 时，不写 seqId 到 bundle，下游 `mController.finishBySeqId(z8, z9, getNextFinishSeqId(this))` 拿到 0L，走非 seqId 路径"。
- **lib** (`AnimationSeqHelper.kt:82-86`)：
  ```kotlin
  override fun addSeqId(bundle: Bundle?) {
      if (bundle == null) return
      val id = updateSeqId()
      bundle.putLong(KEY_INTERRUPT_TRANSITION_START_ACTIVITY_SEQ_ID, id)
  }
  ```
  无 feature 闸门。
- **影响**：当前 lib 的 `OplusAnimManager.supportInterruption()` 恒 true（review 03 §2.2），所以这条 bug **当前不可观察**。一旦未来 lib 把 feature flag 改成可配置，差异立刻放大——OPPO 下 `seqId` 不会递增，lib 下会。
- **修复**：在 `addSeqId` 第一行加 `if (!OplusAnimManager.supportInterruption()) return`（不写 log 也可），3 行内。

### 3-b. ⚠️未修复（成本 4 行 + flag 注入 / 当前不可观察）— `canFinishRecent` / `canInterceptGesture` 缺 feature 闸门  **[B级 / 修复 4 行 + 需注入 flag]**

- **OPPO** (`AnimationSeqHelper.java:69-71, 74-76`)：
  ```java
  public boolean canFinishRecent() {
      return (AppFeatureUtils.INSTANCE.isSupportStartingSurface() && OplusAnimManager.INSTANCE.supportInterruption()
              && AnimSeqTimeStamp.getTimeGapToLastRecentFinishTime() <= 500) ? false : true;
  }
  public boolean canInterceptGesture() {
      return (AppFeatureUtils.INSTANCE.isSupportStartingSurface() && OplusAnimManager.INSTANCE.supportInterruption()
              && AnimSeqTimeStamp.getTimeGapToLastStartAppTime() <= 300) ? false : true;
  }
  ```
  即"feature on + 时间窗未过 = 阻塞（return false）；其余都放行（return true）"。
- **lib** (`AnimationSeqHelper.kt:88-92`)：
  ```kotlin
  override val canFinishRecent: Boolean
      get() = AnimSeqTimeStamp.timeGapToLastRecentFinishTime > MAX_DELAY_TIME
  override val canInterceptGesture: Boolean
      get() = AnimSeqTimeStamp.timeGapToLastStartAppTime > MAX_INTERCEPT_GESTURE_DELAY_TIME
  ```
  完全不看 feature flag。
- **影响**（按调用点列）：
  - `AnimationController.canFinishRecentsAnim` (`:573`)：OPPO 下当 `isSupportStartingSurface=true && supportInterruption=true && gap<=500` 时直接返回 false，禁止 finish。lib 下完全跳过 feature 闸门，只要 gap>500 就放行 finish——**会让 demo 里的 recents finish 早 500ms 触发**，与原厂"feature on 设备至少 500ms 防抖"语义不符。
  - `OplusBaseTouchInteractionService.onInjectDispatchInputEvent` (`:2036`)：OPPO 下当 feature on 且 `gap<=300` 时把 MotionEvent 塞进 `mPendingInput` 排队延迟消费；lib 下只要 `gap>300` 就直接放行，丢失"启动 app 后 300ms 内的手势拦截"演示。
  - `OtherActivityInputConsumer` (`:244,257`)：OPPO 下 `supportInterruption && !canFinishRecent` 双闸门；lib 下单闸门。
- **当前可观察性**：lib 的 `isSupportStartingSurface` 不存在（无 `AppFeatureUtils`）；如果把 `AppFeatureUtils.INSTANCE.isSupportStartingSurface()` 看作恒 false（OPPO 默认值在 `com/android/common/util/AppFeatureUtils.java:776-777, 3711-3712` 是 RUS 配置下发的，可真可假），则 `canFinishRecent/canInterceptGesture` 整体退化为只比时间窗——**与 lib 现状等价**。但**这个等价是"feature off"等价，不是"feature on"等价**。
- **修复**：在 lib 中把 `supportInterruption` 闸门加上（`OplusAnimManager.supportInterruption()` 已恒 true，加这条等价于现状，零成本）。`isSupportStartingSurface` 在 lib 中没有，建议在 `AnimationFeatureHelper.kt` 里加一个布尔 setter（默认 `false`），与原厂对齐"feature flag 由 RUS 下发"语义。约 4 行 + 文档注释。

### 3-c. ✅已修复（60bd048）— `getNextFinishSeqId` 用 `===`（引用相等） vs OPPO `Intrinsics.areEqual`（结构相等） **[B级 / 修复 1 行 + 改注释]**

- **OPPO** (`AnimationSeqHelper.java:104-108`)：
  ```java
  f4.l<? extends RecentsAnimationController, Long> lVar = this.nextFinishSeqId;
  if (lVar == null || !Intrinsics.areEqual(lVar.f12314a, recentsAnimationController)) {
      return 0L;
  }
  return lVar.f12315b.longValue();
  ```
- **lib** (`AnimationSeqHelper.kt:111-115`)：
  ```kotlin
  override fun getNextFinishSeqId(recentsController: Any?): Long {
      val p = nextFinishSeqId
      // 原厂按引用比较 controller，这里 === 一样
      if (p != null && p.first === recentsController) return p.second
      return 0L
  }
  ```
- **当前可观察性**：`RecentsAnimationController` 在 `com/android/quickstep/RecentsAnimationController.java` 是从 `OplusBaseRecentsAnimationController` 继承来的，未重写 `equals(Object)`——`Object.equals` 默认就是引用相等。**所以 `Intrinsics.areEqual(controller, controller)` 等价于 `controller === controller`**。当下两者一致。
- **但 lib 的 KDoc 注释本身错误**：review 03 §3-e 已经指出"原厂是 `Intrinsics.areEqual`（结构相等），只是 `RecentsAnimationController` 未重写 equals 时恰好等价"。注释"原厂按引用比较"语义错误。
- **未来风险点**：lib 把 controller 类型泛化到 `Any?`——如果 lib 调用方传入一个重写了 `equals` 的对象（例如 future RecentsAnimationController subclass 实现了 contentEquals 用于日志调试），OPPO 的 `==` 会命中（结构相等 → 同 controller 视为同），lib 的 `===` 会落空（引用不同 → return 0L）。这是一个**面向未来的隐藏 footgun**。
- **修复**：把 `===` 改成 `==`（即 `Intrinsics.areEqual` 的 Kotlin 等价写法），1 行。注释同时改写为"用结构相等以匹配原厂 `Intrinsics.areEqual`，对 `RecentsAnimationController`（无 equals override）退化为引用相等"。

### 3-d. ✅已修复（60bd048）— `resetInterceptState()` no-op 残留  **[Bug 级 / 修复 1 行]**

- **OPPO** (`AnimationSeqHelper.java:111-114`)：
  ```java
  public void resetInterceptState() {
      AnimSeqTimeStamp.resetLastStartAppTime();
  }
  ```
- **lib** (`AnimationSeqHelper.kt` 全文件无 override，`DefaultAnimationSeqHelper.kt:15` 是 `open fun resetInterceptState() {}`)：
  ```kotlin
  open fun resetInterceptState() {}
  ```
- **影响**：调用方 `OplusBaseTouchInteractionService.onInjectDispatchInputEvent()` (`OplusBaseTouchInteractionService.java:2043`) 在 `ACTION_UP/CANCEL` 时调 `oplusAnimManager.getAnimationSeqHelper().resetInterceptState()`——目的是**让用户抬起手指后立刻放行下一帧手势**（清掉 lastStartAppTime 时间戳，让 `canInterceptGesture` 立刻返回 true）。  
  lib 下 no-op，下次手势的 `canInterceptGesture` 仍按 stale 时间戳判定，可能继续返回 false 拦截下一帧。**Bug 级**：在 demo 演示"启动 app 后 300ms 内手势拦截"场景时，手指抬起后下一帧手势应放行但 lib 会继续吞事件。
- **修复**：`AnimationSeqHelper.kt` 加 `override fun resetInterceptState() { AnimSeqTimeStamp.resetLastStartAppTime() }`，1 行（`AnimSeqTimeStamp.resetLastStartAppTime` 在 review 03 §2.2 已记为 lib 中存在的公共方法）。

### 3-e. ✅已修复（60bd048）— `updateNextFinishSeqIdIfNeed` 无条件 `++seqId` 与覆写 pair  **[Bug 级 / 修复 3 行]**

review 03 §3-e 已标，本报告给完整证据：

- **OPPO** (`AnimationSeqHelper.java:117-129`)：
  ```java
  if (!OplusAnimManager.INSTANCE.supportInterruption()) {
      LogUtils.i(TAG, "update finish recents return directly due to fail to support multi apps interruption");
      return;
  }
  f4.l<? extends RecentsAnimationController, Long> lVar = this.nextFinishSeqId;
  if (lVar == null || !Intrinsics.areEqual(recentsAnimationController, lVar.f12314a)) {
      long jUpdateSeqId = updateSeqId();
      LogUtils.i(TAG, "update finish recents seqId: " + jUpdateSeqId);
      this.nextFinishSeqId = new f4.l<>(recentsAnimationController, Long.valueOf(jUpdateSeqId));
  }
  ```
  即"pair 为 null **或** controller 不同时"才递增 + 覆写。**同 controller 重复调用 = no-op**。
- **lib** (`AnimationSeqHelper.kt:109-112`)：
  ```kotlin
  override fun updateNextFinishSeqIdIfNeed(recentsController: Any?) {
      val id = updateSeqId()
      nextFinishSeqId = recentsController to id
  }
  ```
  无条件 `++` + 覆写。
- **调用点放大**：
  - `RecentsAnimationController.finishController()` (`:184`)：每次 finish 入口必调。同 controller 多次 finish（罕见但存在：appLaunchAnimFinishCallback 重复触发）会反复递增。
  - `AnimationController$AnimSuccessListener.onAnimationSuccess()` (`:220`)：每次 recents anim 成功回调都调。如果 controller 生命周期内多次 recents anim 成功（如 Overview → recents → Overview → recents），lib 下 seqId 跟着 +N；OPPO 下保持稳定。
- **下游影响**：`mController.finishBySeqId(z8, z9, getNextFinishSeqId(this))` (`RecentsAnimationController.java:304`) 把 `nextFinishSeqId` 透传给 AOSP `mController` 的 binder 反射调用。该 binder 用 seqId 做"start bundle 序列号"对齐：start 侧 `addSeqId` 写入 bundle 的 seqId 与 finish 侧 `getNextFinishSeqId` 返回的 seqId 必须**配对**。  
  - 假设：controller 在 start 侧 `addSeqId(bundle1) → bundle1.seqId = 5`，`addSeqId(bundle2) → bundle2.seqId = 6`；finish 侧 `updateNextFinishSeqIdIfNeed(this)` 在第一次进入时把 `nextFinishSeqId` 设为 `(this, 5)`（OPPO，因为 pair 为 null 时会 ++）。后续 `finishBySeqId(z, z, 5)` 把 5 透传过去，下游据此关联到 bundle1。  
  - lib 场景：start 同上；finish 侧第一次进入 `nextFinishSeqId = (this, 5)`；**若中间又来一次 `updateNextFinishSeqIdIfNeed`（比如其他路径），`nextFinishSeqId = (this, 6)`**；后续 `finishBySeqId(z, z, 6)` 关联到 bundle2，但用户实际期望的可能是 bundle1。
  - 这是**配对错位的 footgun**，但触发频率取决于 controller 生命周期内的 `updateNextFinishSeqIdIfNeed` 调用次数——单一 finishController 路径下 OPPO 和 lib 第一次进入行为一致，差异只在"重复进入"。
- **修复**：lib 加 `if (nextFinishSeqId == null || nextFinishSeqId.first != recentsController) { ... }` 包裹，共 3 行。同时按 §3-a 把 `supportInterruption` 闸门补上（OPPO `:119-122`）。
- **测试覆盖**：现有测试 `AnimationSeqHelperTest.kt:testUpdateNextFinishSeqIdIfNeed`（`:78-90`）只验证"不同 controller 返回 0L"，**未覆盖"同 controller 重复调用应返回相同 seqId"**这一关键不变式。补一个 `testRepeatedUpdateSameControllerReturnsSameSeqId` 测试即可。

### 3-f. ⚠️未修复（成本 1 行 / 纯文档级零影响）— `MAX_GO_NORMAL_DELAY_TIME = 200L` 缺失  **[D级（纯文档级）/ 修复 1 行]**

- 原厂 `AnimationSeqHelper.java:20` `public static final long MAX_GO_NORMAL_DELAY_TIME = 200`，作为**公共契约**对外开放，但类内 0 引用。
- lib 缺失。
- **影响**：第三方 / 单元测试可能按 `AnimationSeqHelper.MAX_GO_NORMAL_DELAY_TIME` 反射读这个常量（例如 build-time 生成 `delay-narrowing` 阈值表）。lib 缺失会让外部使用者编译期报错或反射拿到 `IllegalArgumentException`。
- **修复**：在 `AnimationSeqHelper.kt` 文件顶部加 `internal const val MAX_GO_NORMAL_DELAY_TIME = 200L`，1 行。注意可见性应保持 `public`（OPPO 是 public）以匹配契约，但 lib 风格用 `internal` 也可——取决于 lib 设计意图。

---

## 4. 回移建议

### 4.1 值得补进 lib 的（按 ROI 排序）

| # | 内容 | 修复成本 | 影响面 | ROI |
|---|---|---|---|---|
| 1 | ✅已修复（60bd048）— `resetInterceptState()` override 调 `AnimSeqTimeStamp.resetLastStartAppTime()`（§3-d） | 1 行 | Bug 级：手指抬起后下一帧手势放行；演示价值高 | 极高 |
| 2 | ⚠️半修（60bd048 仅修了条件更新；`supportInterruption` 闸门未补）— `updateNextFinishSeqIdIfNeed` 改成"pair 为空或 controller 不同才更新"（§3-e） | 3 行 + 1 行 `supportInterruption` 早 return | Bug 级：seqId-finish 配对错位（罕见但可观察） | 高 |
| 3 | ✅已修复（60bd048）— `getNextFinishSeqId` 把 `===` 改 `==`，注释改为"用结构相等匹配原厂 `Intrinsics.areEqual`"（§3-c） | 1 行 + 注释改写 | 当前可观察性低，未来 controller 类型自定义 equals 时落空；footgun 预防 | 高 |
| 4 | ⚠️未修复（成本 4 行 + flag 注入 / feature flag 在 lib 不存在）— `canFinishRecent` / `canInterceptGesture` 加 `supportInterruption() && isSupportStartingSurface()` 闸门（§3-b） | 4 行 + `AnimationFeatureHelper.kt` 加 `isSupportStartingSurface` setter（约 3 行） | B 级：feature on 设备下 500/300ms 防抖真的生效 | 中（需要 feature flag 注入） |
| 5 | ⚠️未修复（成本 2 行 / 当前 supportInterruption 恒 true，零影响）— `addSeqId` 加 `supportInterruption()` 早 return（§3-a） | 3 行 | 当前 lib 下不可观察（`supportInterruption` 恒 true），未来若改 flag 则放大 | 中（防御性） |
| 6 | ⚠️待复核（未确认测试是否补）— 补 `testRepeatedUpdateSameControllerReturnsSameSeqId` 单测（§3-e 验证） | 8 行 | 锁定 §3-e 修复后的不变式 | 中 |
| 7 | ⚠️未修复（成本 1 行 / 纯遗留常量，类内零引用）— 补 `MAX_GO_NORMAL_DELAY_TIME = 200L` 常量（§3-f） | 1 行 | 公共契约补齐 | 低 |

合计：约 **15-20 行** Kotlin 改动 + 8 行单测。**性价比集中在前 3 条（5 行内堵 3 个 bug 级语义差）**。

### 4.2 建议保持简化的

| # | 内容 | 简化理由 |
|---|---|---|
| 1 | ✔️保持简化 — `LogUtils.i` 5 处日志全部省略 | lib 是演示库，log 噪声会污染 demo 输出；OPPO log 是 trace 排查用，demo 阶段无需 |
| 2 | ✔️保持简化 — `Intrinsics.checkNotNullParameter` 全部省略（用 Kotlin null-safety） | Java/SAM 路径不会传 null；demo 中调用方都是 lib 自身，可空签名反而更友好 |
| 3 | ✔️保持简化 — Handler 字段即时初始化 → 延迟初始化 | JVM 测试环境无主 Looper 是硬约束（`Handler(Looper.getMainLooper())` 会抛 `NullPointerException`）。保持 `getOrCreateHandler()` 延迟构造 |
| 4 | ✔️保持简化 — `Runnable` → `(() -> Unit)?` + `Bundle?` + `Any?` 泛化 controller 类型 | lib 去 AOSP 平台依赖的标准做法；mock/单测友好；类型擦除后字节码一致 |
| 5 | ✔️保持简化 — `maxOf(0L, delay)` 钳制 | 当前不可达（`canFinishRecent` 已保证 `gap<=500`）；保留零风险，可作为未来单调时钟回退的护栏，**建议保留**而非裁剪 |
| 6 | ✔️保持简化 — `TAG` 常量 + `USE_SEQ = true` 常量 | 纯遗留，lib 无 log 无 USE_SEQ 引用，保持不引入 |

---

## 5. 行号速查表（便于回溯）

| 主题 | OPPO 行号 | lib 行号 |
|---|---|---|
| 常量 KEY/MAX/MSG | `:18-24` | `:14-16,91-92`（MAX_GO_NORMAL_DELAY_TIME 缺失） |
| 字段（seqId/delayRunnable/handler/nextFinishSeqId） | `:25-34` | `:53-63` |
| `updateSeqId` | `:50-54` | `:79` |
| `addSeqId` | `:56-66` | `:82-86` |
| `canFinishRecent` | `:68-71` | `:88-89` |
| `canInterceptGesture` | `:73-76` | `:91-92` |
| `clearFinishRecentsRunnable` | `:78-84` | `:104-107` |
| `delayFinishRecents` | `:86-99` | `:95-103` |
| `getNextFinishSeqId` | `:101-109` | `:111-115` |
| `resetInterceptState` | `:111-114` | **缺失 override**（基类 `:15`） |
| `updateNextFinishSeqIdIfNeed` | `:116-129` | `:109-112` |
| 上游调用 `RecentsAnimationController.finishController/updateNext` | `:184,304` | （lib mock 测试覆盖） |
| 上游调用 `AnimationController` 三处 | `:212,220,559-580` | 同上 |
| 上游调用 `OplusBaseTouchInteractionService` | `:2036,2043` | 同上 |
| 上游调用 `OtherActivityInputConsumer` | `:244,257` | 同上 |

## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **60bd048** — resetInterceptState override、updateNextFinishSeqIdIfNeed 条件更新（pair 空/变才 ++）、getNextFinishSeqId 改 == + 注释修正

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。

---

## 复核记录（批次 5 / 2026-09-09 / 子代理逐项）

### §3 行为差异风险点逐项判定

| 项 | 标题 | 状态 | 证据 |
|---|---|---|---|
| 3-a | `addSeqId` 缺 `supportInterruption()` 闸门 | ⚠️未修复（成本 2 行 / 当前不可观察） | `lib/.../seq/AnimationSeqHelper.kt:55-58` 仍无 feature gate；`OplusAnimManager.supportInterruption()` 恒 true（`manager/OplusAnimManager.kt:27`），实际零影响；OPPO `:59-62` 逻辑保留 |
| 3-b | `canFinishRecent` / `canInterceptGesture` 缺 feature 闸门 | ⚠️未修复（成本 4 行 + flag 注入） | `AnimationSeqHelper.kt:60-63` 仍只比时间窗；OPPO `:70,75` 的 `AppFeatureUtils.isSupportStartingSurface()` + `supportInterruption()` 双闸门未移植（lib 无 `AppFeatureUtils`） |
| 3-c | `getNextFinishSeqId` 用 `===` vs OPPO `Intrinsics.areEqual` | ✅已修复（60bd048） | `AnimationSeqHelper.kt:91-94` 已改为 `==`；OPPO `AnimationSeqHelper.java:105` 用 `Intrinsics.areEqual` 等价结构相等 |
| 3-d | `resetInterceptState()` no-op 残留 | ✅已修复（60bd048） | `AnimationSeqHelper.kt:81-83` override 调 `AnimSeqTimeStamp.resetLastStartAppTime()`，与 OPPO `:112-114` 等价 |
| 3-e | `updateNextFinishSeqIdIfNeed` 无条件 `++seqId` 与覆写 pair | ✅已修复（60bd048） | `AnimationSeqHelper.kt:85-90` 改为「pair 为空或 controller 不同才 ++」；OPPO `AnimationSeqHelper.java:124-128` 同结构 |
| 3-f | `MAX_GO_NORMAL_DELAY_TIME = 200L` 缺失 | ⚠️未修复（成本 1 行 / 纯遗留常量） | `AnimationSeqHelper.kt:6-7` 仅声明 `MAX_DELAY_TIME`/`MAX_INTERCEPT_GESTURE_DELAY_TIME`，OPPO `:20` 的 `MAX_GO_NORMAL_DELAY_TIME` 未补（类内 0 引用，零影响） |

### §4.1 值得补进 lib 的（逐项判定）

| # | 内容 | 状态 |
|---|---|---|
| 1 | `resetInterceptState()` override | ✅已修复（60bd048） |
| 2 | `updateNextFinishSeqIdIfNeed` 条件更新 | ⚠️半修（60bd048 仅修条件更新；`supportInterruption` 闸门未补，因 lib 恒 true 零影响） |
| 3 | `getNextFinishSeqId` 改 `==` | ✅已修复（60bd048） |
| 4 | `canFinishRecent`/`canInterceptGesture` feature 闸门 | ⚠️未修复（feature flag 不存在，零影响） |
| 5 | `addSeqId` `supportInterruption()` 早 return | ⚠️未修复（恒 true，零影响） |
| 6 | `testRepeatedUpdateSameControllerReturnsSameSeqId` 单测 | ⚠️待复核（grep 未确认测试是否补） |
| 7 | `MAX_GO_NORMAL_DELAY_TIME` 常量 | ⚠️未修复（纯遗留，零影响） |

### §4.2 建议保持简化（全部确认合理）

6 条全部标 `✔️保持简化`；理由与原文档一致。