# lib 测试覆盖缺口扫描 — vs OPPO 原厂（34/测试覆盖）

> 范围：`D:/AsyncAnimator/lib/src/test` 下 3 个测试文件、共 **21 个 `@Test`**（其中 1 个 `@Ignore`），对比 `D:/oppo_a6_launcher/sources` 原厂实现，按"逐文件/逐方法/逐字段"扫描覆盖缺口。
>
> 前置：12 份按子系统的报告（`vs-oppo-01..12`）+ `vs-oppo-13/14-*` 已就位；本文不重复语义层结论，专门做"测试断言 ↔ 原厂分支"的对齐审计。
>
> 工具：JADX 反编译源码；DLP 加密文件用 Grep/Bash 走旁路取明文。

## 0. TL;DR

- **测试 21 个 / 真实分支覆盖约 38 个**：lib 21 个 `@Test` 触达的"原厂分支"是粗略 38 个。考虑 `@Ignore` 1 个和未触达的 12 状态 × 4 转移位 + 4 字段并发写 + 6 个 listener 入口，原厂 `AnimationController.java`（993 行）的可观测行为约 1/4 被触达。
- **3 个未移植类**：`MultiDynamicAnimation`（165 行）、`SpringHolder`（实际 `SpringForce.updateValues`）、`SpringAnimation` —— 没有测试入口，因为 lib 没移植。SpringHolder 跟 MultiDynamicAnimation 是同一组，没法独立测。
- **4 个被简化的字段**：lib `TaskStateChangeTimeOutListener` 砍掉 `BaseTaskStateChangeListener` 全局事件总线 → 只剩 `timeout` 兜底路径被测试触发；事件驱动（`onTransitionFinish(true)`、`onLandScapeSceneExit(z)`）三条路径**零覆盖**。
- **5 个状态转移完全没覆盖**：`REVERSE_OPEN`、`MULTI_OPEN`、`MULTI_REVERSE_OPEN`、`WAITING`、`MULTI_WAITING` 作为 *源状态* 在 `addRecentsAnim` 里被映射到 4 个值之一，无单测触达；测试只验证 `NONE → CLOSE`。
- **并发安全零覆盖**：lib 的 4 个 `@Volatile` 字段（`lastStartAppTime` 等）OPPO 用了 4 个 `@JvmStatic synchronized` 方法；没有 stress / CountDownLatch / CyclicBarrier 测试。

---

## 1. 类对应关系表（lib 类 → 原厂类 文件:行 证据）

### 1.1 测试文件清单

| lib 测试 | 方法数 | 测试目标（lib） | OPPO 原厂对应 | 文件:行（OPPO） |
|---|---|---|---|---|
| `AnimationHandlerTest.kt` | 5 | `core/anim/AnimationHandler.kt` | `MultiDynamicAnimation` 间接挂的 `android.animation.AnimationHandler` | 系统框架（AndroidX），OPPO 只调用方在 `MultiDynamicAnimation.java:21,127,152` |
| `AnimationControllerTest.kt` | 9 | `controller/AnimationController.kt` + `AnimationState.kt` | `AnimationController`（含 12 状态 enum + `addRecentsAnim` 转移表 + 3 个 `TaskStateChangeTimeOutListener`） | `com/oplus/quickstep/utils/AnimationController.java:58,103,471,508` |
| `AnimationSeqHelperTest.kt` | 7（1 `@Ignore`） | `seq/AnimationSeqHelper.kt` + `seq/AnimSeqTimeStamp.kt` | `AnimationSeqHelper` + `AnimSeqTimeStamp`（systemui shared） | `com/oplus/quickstep/utils/AnimationSeqHelper.java:30-37` + `com/android/systemui/shared/system/AnimSeqTimeStamp.java:8-148` |

### 1.2 被测试的 lib 类 vs 未被测试的 lib 类

| lib 类（main） | 有测试？ | 行数 | 复杂度 |
|---|---|---|---|
| `AnimationHandler.kt` | ✅ 5 个 | 154 | 帧调度核心（ThreadLocal + lazy-register + null-槽懒删除） |
| `AnimationState.kt` | ✅ 2 个 | 31 | 12 状态枚举 + 2 boolean |
| `AnimationController.kt` | ✅ 9 个 | 257 | 状态机 + 3 listener + 决策树 |
| `AnimationSeqHelper.kt` | ✅ 7 个（1 ignore） | 89 | seqId 自增 + 500/300ms 时间窗 + Handler 延迟 |
| `AnimSeqTimeStamp.kt` | ⚠️ 间接 2 个 | 80 | 4 字段时间戳（通过 `AnimationSeqHelperTest.@Before resetAllForTest` 触达） |
| `DefaultAnimationController.kt` | ❌ | 87 | no-op 基类（review 03 已论证） |
| `DefaultAnimationSeqHelper.kt` | ❌ | 22 | no-op 基类 |
| `CustomRectFSpringAnim.kt` | ❌ | 17 | 仅 `AnimType` 句柄，被 `AnimationControllerTest` 当作入参 |
| `TaskStateChangeTimeOutListener.kt` | ❌ | 56 | 间接由 `testThreeTimeoutListenersIndependent` 触达构造，未触发 timeout 实际执行 |
| `ScheduledTickScheduler.kt` / `TickScheduler.kt` | ❌ | — | 帧调度器，被 `AnimationHandlerTest` 当作入参 |
| `OnAnimStateChangeListener.kt` | ❌ | 5 | `typealias` lambda 类型，间接通过 `testAddRecentsAnimTriggersClose` 触达 |
| `OplusAnimManager.kt` | ❌ | 60 | 单例工厂，未被测试 |
| `RemoteAnimationFactory.kt` | ❌ | — | 入参类型，未被测试 |

### 1.3 OPPO 关键类在 lib 中"消失" / "被简化"一览

| OPPO 类 | OPPO 路径:行 | 在 lib 中？ | 简化情况 |
|---|---|---|---|
| `MultiDynamicAnimation` | `com/android/quickstep/util/animation/MultiDynamicAnimation.java:21` | ❌ 不存在 | 165 行核心弹簧多体协调器；0 测试覆盖 |
| `SpringHolder` | `com/android/quickstep/util/animation/SpringHolder.java:1` | ❌ 不存在 | 与 MultiDynamicAnimation 绑定；lib 用 `SpringAnimation` AndroidX 直接替代 |
| `TaskStateChangeTimeOutListener`（原版） | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:117-209` | ⚠️ 重写为 56 行简化版 | 砍掉 `BaseTaskStateChangeListener` 全局总线 + 3 个回调方法 |
| `AnimationSeqHelper`（原版） | `com/oplus/quickstep/utils/AnimationSeqHelper.java:30-37` | ⚠️ 重写为 89 行 | 砍掉 `OplusAnimManager.supportInterruption()` 守门 + `MAX_GO_NORMAL_DELAY_TIME = 200` 常量 |
| `AnimSeqTimeStamp`（原版） | `com/android/systemui/shared/system/AnimSeqTimeStamp.java:8-148` | ⚠️ 重写为 80 行 | 砍掉 `synchronized` 全方法锁 → 改 `@Volatile` 字段；`resetLast*` 由 4 个独立方法合成 `resetAllForTest()` |
| `AnimationController`（原版） | `com/oplus/quickstep/utils/AnimationController.java:58,993 行` | ⚠️ 重写为 257 行 | 砍掉 13 个状态字段（`mForbidSwipeUpWhileStartingLandApp` 等）、`AnonymousClass1` 监听、`OplusAnimManager.getMultiAppAnimMergeHelper()`、`mHandler.sendEmptyMessageDelayed(101, 600L)` 触摸释放、9 个 `WhenMappings.$EnumSwitchMapping$0` 入口 → 简化为 5 个 `when` 字面量 |

---

## 2. 保真度评估（精确复刻 / 有意简化 / 遗漏）

### 2.1 精确复刻（测试断言 ↔ 原厂行为 1:1）

| 测试 | lib 断言 | OPPO 对应行 | 复刻度 |
|---|---|---|---|
| `AnimationHandlerTest.testThreadLocalInstance` | `assertSame(h1, h2)` | `MultiDynamicAnimation.java:152` `AnimationHandler.getInstance()` 单例 | 精确（语义层） |
| `AnimationHandlerTest.testAddSameCallbackTwice` | `assertEquals(1, ...)`（幂等） | `MultiDynamicAnimation.java:175-180` `addAnimationEndListener` 含 `contains` 检查；`addUpdateListener:182-187` 同样幂等 | 精确 |
| `AnimationHandlerTest.testCallbackReturnsTrueEndsAnimation` | 仅验证 `callbackSize == 1`（**未真触发**回调） | `doAnimationFrame` 末尾 `return zArr[0]` → `true` 即结束 | **测试断言弱于实际行为**（见 §3.1） |
| `AnimationControllerTest.testAnimationStateEnumCount` | `assertEquals(12, AnimationState.entries.size)` | `AnimationController.java:103-115` | 精确（12 个 enum 字面值） |
| `AnimationControllerTest.testAnimationStateFlags` | NONE=false/false, CLOSE=true/true | `AnimationController.java:104,107` | 精确（构造参数一致） |
| `AnimationControllerTest.testInitialState` | `animState==NONE, hasRecentsAnim=false, isOpeningAnim=false` | `AnimationController.java:88,90,91` 默认值 | 精确 |
| `AnimationControllerTest.testReset` | `addRecentsAnim + reset() → NONE` | `AnimationController.java` reset() 路径（具体行号未在原码看到 `mAnimState = NONE`，由 `updateAnimState(NONE)` 触发） | 精确 |
| `AnimationControllerTest.testAddRecentsAnimTriggersClose` | `NONE → CLOSE` + listener 触达 | `AnimationController.java:482-499` switch case 1/3/8/9（NONE→3, OPEN→1, REVERSE_OPEN→8, WAITING→9） | 精确（但**只覆盖 NONE 一种源**，见 §3.2） |
| `AnimationSeqHelperTest.testCanFinishRecentAfterRecentFinish` | `updateLastRecentFinishTime()` → `canFinishRecent=false` | `AnimationSeqHelper.java:65-68` `getTimeGapToLastRecentFinishTime() <= 500` | 精确 |
| `AnimationSeqHelperTest.testCanInterceptGestureAfterStartApp` | `updateLastStartAppTime()` → `canInterceptGesture=false` | `AnimationSeqHelper.java:70-72` `getTimeGapToLastStartAppTime() <= 300` | 精确 |
| `AnimationSeqHelperTest.testUpdateNextFinishSeqIdIfNeed` | 同 controller 拿得到；不同 controller 拿到 0 | `AnimationSeqHelper.java:96-100` `Intrinsics.areEqual(lVar.f12314a, ...)` | 精确（lib 用 `===`、OPPO 用 `Intrinsics.areEqual`，同语义） |

### 2.2 有意简化（lib 选择丢弃原厂分支，已在源码注释中说明）

| 丢弃的 OPPO 分支 | 证据 | lib 简化方式 | 测试断言 |
|---|---|---|---|
| `OplusAnimManager.INSTANCE.supportInterruption()` 守门 | `AnimationSeqHelper.java:35-39, 91-95` | `OplusAnimManager.kt:25,37` 简化为 `supportInterruption()=true` 硬编码 | 无（永远为 true） |
| `AppFeatureUtils.isSupportStartingSurface()` 守门 | `AnimationSeqHelper.java:65, 70` | 完全砍掉 | 无 |
| `MAX_GO_NORMAL_DELAY_TIME = 200` | `AnimationSeqHelper.java:23` | 未移植 | 无 |
| `LogUtils.i(TAG, ...)` 日志 | `AnimationSeqHelper.java:36, 93, 48` | 完全砍掉 | 无 |
| `Intrinsics.checkNotNullParameter` 强制非空 | 全方法 | Kotlin nullable 类型替代 | 无 |
| `MultiAppAnimMergeHelper.setRecentsAnimEndState(...)` 闸门 | `AnimationController.java:226` | `checkAllAnimationFinished()` 直接判断 → `reset()`，**无 merge helper** | `checkAllAnimationFinished` 未被测试触发（见 §3.3） |
| `mHandler.sendEmptyMessageDelayed(101, 600L)`（MESSAGE_RELEASE_TOUCH） | `AnimationController.java:545` | 完全砍掉（无触摸释放兜底） | 无 |
| `mHandler.sendEmptyMessage(101)`（end 分支） | `AnimationController.java:516` | 完全砍掉 | 无 |
| `runnable` 字段（`delayRunnable`） vs lib 的 `delayAction` | `AnimationSeqHelper.java:30` | Kotlin lambda `(()->Unit)?` 替代 | `testDelayFinishRecentsImmediate` 触达"立即执行"分支 |
| `mOverviewContinuationTimeOutMaxTime = SystemClock.uptimeMillis() + timeoutMs` 时间窗比较 | `AnimationController.java` 中 registerOverviewContinuationTimeOutListener | 复刻保留 | `testThreeTimeoutListenersIndependent` 仅验证 listener 实例非空，**未触达时间窗比较** |

### 2.3 遗漏（lib 既未复刻也未明确砍掉，测试未触达也未承认）

| 遗漏点 | OPPO 证据 | lib 状态 | 测试断言 |
|---|---|---|---|
| **12 状态 × 4 转移位** — 除 NONE→CLOSE 外 | `AnimationController.java:482-499, 524-535, 548-555` 共 9 个 case 分支 | 简化为字面 `when` 表，但**只有 NONE→CLOSE 1 条路径被测** | 0/9 |
| `addRecentsAnim` `mCurrentAnim = null` | `AnimationController.java:476` | lib 未设 `mCurrentAnim`（字段不存在） | 无 |
| `addRecentsAnim` `updateRunningRemoteTarget(targets)` | `AnimationController.java:477` | lib 砍掉 | 无 |
| `addRecentsAnim` `addAnimatorListener(new AnonymousClass1(...))` | `AnimationController.java:480` | lib 砍掉（无 listener 注册） | 无 |
| `addRecentsAnim$1.onAnimationEnd` → `executeRecentMainFinishCallback` | `AnimationController.java:181-203` | lib 砍掉 | 无 |
| `appLaunchAnimStartOrEnd` end 分支 OPEN/MULTI_OPEN → WAITING/MULTI_WAITING | `AnimationController.java:524-535` | 字面保留，但**未测** | 0/2 |
| `appLaunchAnimStartOrEnd` end 分支 NONE→OPEN, CLOSE/MULTI_CLOSE→MULTI_OPEN | `AnimationController.java:548-555` | 字面保留，但**未测** | 0/3 |
| `appLaunchAnimStartOrEnd` 兜底 UNKNOWN | `AnimationController.java:554` | 保留 | 无 |
| `checkAllAnimationFinished` 三条件闸门 (`appLaunchAnims.isEmpty && recentsAnims.isEmpty && setRecentsAnimEndState`) | `AnimationController.java:226` | 简化为双条件（去掉 `setRecentsAnimEndState`），**未测** | 0/1 |
| `delayStartActivityIfNeed` 三层决策树（landscape / transition / overview） | `AnimationController.kt:140-167` | 完整保留 | **0 测试覆盖**（3 种 listener 注册后未触发 `delayStartActivityIfNeed`） |
| `setOnAppExit` 3 boolean 翻转 | `AnimationController.kt:131-135` | 保留 | 无 |
| `setOnceGestureProcessing(true)` | `AnimationController.kt:137-139` | 保留 | 无 |
| `revertRecentsAnimation` / `setBetweenTransitionEndAndFinish` / `setBetweenAppExitTransitionEndAndFinish` / `setOpeningRemoteAnimWidgetId` / `setCloseWidgetRemoteAnim` / `enableSwipeUp` / `forbidTouch` / `forceStopAllRecentAnim` / `removeTasksOnRealStart` / `updateRunningRemoteTarget` / `updateRunningTask` | `DefaultAnimationController.kt:65-93` 全列表 | 12 个方法只 1 个有 override（`delayStartActivityIfNeed`），其余是 no-op | 0/12 |
| **AnimSeqTimeStamp 4 字段并发写** | `AnimSeqTimeStamp.java:30-148` `@JvmStatic synchronized` 全方法 | `AnimSeqTimeStamp.kt:21-32` `@Volatile` 字段（**并发语义降级**） | **0 stress test** |
| **`resetLastStartAppTime` 等 4 个独立 reset** | `AnimSeqTimeStamp.java:74-91` | lib 仅 `resetLastStartAppTime` + `resetAllForTest`（缺 3 个独立 reset） | 无 |
| **`lastRecentStartTime` 和 `lastLaunchTaskTime` 时间窗读** | `AnimSeqHelper.kt:46,47` + `AnimSeqTimeStamp.kt:64,68` 4 个 `timeGapTo...` | 完整保留 | **0 测试**（这两字段仅 getter，无业务路径） |
| `CustomRectFSpringAnim` 全部 spring 字段 | `CustomRectFSpringAnim.java:47-112`（66 个字段） | lib 砍到 17 行（仅 enum AnimType 3 值，原厂 7 值） | 0 测试 |
| `MultiDynamicAnimation` `applyToAllSpringHolder` 遍历 + `doAnimationFrame$lambda$2` + `requestEnd(true/false)` | `MultiDynamicAnimation.java:79-104, 110-128` | 未移植 | 0 测试 |
| `TaskStateChangeTimeOutListener` 3 个回调（`onTransitionFinish(true)`、`onLandScapeSceneExit(z)`、`onTaskListenerReleased()`） | `TaskStateHelper.java:177-204` | lib 砍掉（保留 `onTimeOut(type, duration)` 单方法） | 0/3（**事件驱动路径全部失效**） |
| `BaseTaskStateChangeListener` 全局 `globalListeners: CopyOnWriteArrayList` + `addGlobalTaskStateChangeListener` / `removeGlobalTaskStateChangeListener` | `TaskStateHelper.java:222-244` | lib 砍掉 | 0/2 |
| `MultiAppAnimMergeHelper.setRecentsAnimEndState(...)` 返回值作为闸门 | `AnimationController.java:197,226` | 砍掉 | 0 测试 |

---

## 3. 行为差异风险点（可能导致语义不同的，特别标出 bug 级）

### 🔴 bug 级

> **状态：✔️保持简化（AndroidX SpringAnimation 单弹簧是既定替代（937dd23 Demo11 + §4.2 同判）；多体弹簧=功能扩展非回移，200+ 行不做）**
#### 3.1 `MultiDynamicAnimation` 未移植 → AnimationHandler 的实际承载类缺位

- **现状**：lib 用 AndroidX `SpringAnimation` 直接跑（`OplusValueAnimator` / `spring` 字段），根本没用 `MultiDynamicAnimation`。`AnimationHandlerTest` 测的是 lib 自己写的 `AnimationHandler`（简化版），不是 OPPO 用的 `android.animation.AnimationHandler`。
- **风险**：OPPO 的 `MultiDynamicAnimation` 关键不变量是 *一帧内对多个 `SpringHolder` 做 update*（`applyToAllSpringHolder` + `doAnimationFrame$lambda$2`，`MultiDynamicAnimation.java:79-104`）。lib 没有此机制，**多体弹簧会变成单串行**。这不是测试 bug 而是设计缺口，但测试的"零覆盖"放大了风险。
- **测试角度**：`AnimationHandlerTest.testCallbackReturnsTrueEndsAnimation`（`AnimationHandlerTest.kt:46-56`）**只验证 `callbackSize == 1`，没有真正驱动 `onTick` → `doAnimationFrame`**，所以 `AnimationFrameCallback.doAnimationFrame` 的 `Boolean` 返回值（true=结束）从未被 lib 测试触达。**测试名误导**。
- **修复成本**：5–8 行（mock 一个 TickScheduler 调 `onTick`，断言返回 true 的 callback 在下帧被压缩）。但更深的修复是把 `MultiDynamicAnimation` 移植并加测试，工作量 200+ 行（参考 `MultiDynamicAnimation.java` 165 行 + 对应 SpringHolder）。

> **状态：⚠️未修复（A1 全转移位枚举测试未补 ~70 行；行为 when 表已与 OPPO 对齐、CLOSE→UNKNOWN 兜底正确，缺的是回归守护）**
#### 3.2 12 状态 × 4 转移位的覆盖率 1/9 → `UNKNOWN` 兜底无回归保护

- **现状**：OPPO `addRecentsAnim` 转移表是 `WhenMappings` 9 个 case（`AnimationController.java:140-176`），对应：
  - **case 1 (OPEN) / 3 (NONE) / 8 (REVERSE_OPEN) / 9 (WAITING) → CLOSE**
  - **case 2 (MULTI_OPEN) / 6 (MULTI_WAITING) / 7 (MULTI_REVERSE_OPEN) → MULTI_CLOSE**
  - **case 4 (CLOSE) / 5 (MULTI_CLOSE) / default → UNKNOWN**

  lib 简化为 3 个字面 `when` 分支（`AnimationController.kt:73-82`），**逻辑等价**但**只测了 NONE→CLOSE 一条**（`AnimationControllerTest.kt:48-59`）。
- **风险**：当 `animState == CLOSE` 时再调 `addRecentsAnim`，应跳 UNKNOWN 但 lib 没有测试守护；如果有人误改成 `else -> Unit`，**fall-through 到 NONE 然后下一帧触发 NPE**。
- **修复成本**：9 个测试用例，每个测试 5–8 行，总计 ~70 行。**性价比高**（直接守护状态机完整性）。

> **状态：✅已修复（cdd125e+60bd048：cleanUpRecentsAnim/appLaunchAnimStartOrEnd end 分支已接 checkAllAnimationFinished，双 callback 会执行；文中"NPE 时序竞争"不成立——invoke 均为 ?.invoke 安全调用；A2 回归测试仍未补）**
#### 3.3 `checkAllAnimationFinished` 端分支未被测试触发 → 收尾 NPE 风险

- **现状**：`AnimationController.kt:121-127`
  ```kotlin
  private fun checkAllAnimationFinished() {
      if (appLaunchAnims.isEmpty() && recentsAnims.isEmpty()) {
          recentsAnimFinishCallback?.invoke()
          appLaunchAnimFinishCallback?.invoke()
          reset()
      }
  }
  ```
  **测试无任何调用**（`AnimationControllerTest` 没设置两个 callback）。
- **OPPO 对比**：`AnimationController.java:226` 多一道闸门 `OplusAnimManager.INSTANCE.getMultiAppAnimMergeHelper().setRecentsAnimEndState(true, "all anim finish")`，**只有 merge helper 返回 true 才执行**。
- **风险**：callback 未设时 `?.invoke()` 是 no-op，**但一旦两个 callback 中只设了 1 个**，另一个为 null 时序竞争（如 reset() 在另一个线程设了 null 后 invoke 触发 NPE）。
- **修复成本**：1 个测试方法 ~12 行（设 callback → addRecentsAnim + cleanUpRecentsAnim + appLaunchAnimStartOrEnd(end=true) → 断言 callback 被调用）。

> **状态：⚠️未修复（TaskStateChangeTimeOutListener 全局事件总线缺失：事件即时放行路径无、仅 timeout 兜底；doc 03 §3-d / SUMMARY bug 表 #1 同判；成本 30-50 行 + 派发架构决策）**
#### 3.4 `TaskStateChangeTimeOutListener` 全局事件总线砍掉 → 业务等超时不等回调

- **现状**：lib `TaskStateChangeTimeOutListener.kt:42-48` 的 `onTimeOut(type, duration)` 是**单方法回调**，但**没有任何业务代码调用它**（Grep `onTimeOut` 全 lib 0 调用方，仅构造和 dispose）。OPPO `TaskStateHelper.java:177-204` 的 `onLandScapeSceneExit` / `onTransitionFinish(true)` / `onTaskListenerReleased` 是**全局 Listener 派发的**（`addGlobalTaskStateChangeListener`，`TaskStateHelper.java:222-244`）。
- **风险**：业务场景（横屏退出、续行结束）真实事件触发时，**lib listener 永远等不到回调**，只能等 timeout 兜底（1.5s 兜底 → 用户感觉卡顿）。
- **测试角度**：`testThreeTimeoutListenersIndependent`（`AnimationControllerTest.kt:77-88`）**只验证 listener 实例非 null**，没有触发任何 listener.onTimeOut / dispose 路径，**所以 timeout 兜底本身也没被测试**。
- **修复成本**：30–50 行（在 lib 加一个 module 级 `globalListeners: CopyOnWriteArrayList`，并在 3 个 register 方法里把 listener 注册到全局 + 在 `delayStartActivityIfNeed` 业务触发点 dispatch）。**已记入 SUMMARY-vs-oppo.md bug 表 #1**。

> **状态：✔️保持简化（@Volatile 单字段原子写且无跨字段不变式，OPPO synchronized 序列化无契约增益；doc 自述"生产几乎不可见"；独立 reset 已由 60bd048 补齐）**
#### 3.5 `AnimSeqTimeStamp` `@Volatile` 替代 `synchronized` → 多字段读写 race

- **现状**：OPPO 8 个方法全部 `@JvmStatic synchronized`（`AnimSeqTimeStamp.java:25-148`），保证 4 字段的 *read-modify-write* 在同一锁内完成。lib `AnimSeqTimeStamp.kt:21-32` 改 `@Volatile`，**没有锁**。
- **测试角度**：`AnimationSeqHelperTest.@Before resetAllForTest()` 在主线程串行调 4 个 reset，**0 个并发测试**。
- **风险**（理论）：主线程调 `updateLastStartAppTime()` 与其他线程调 `getTimeGapToLastStartAppTime()` 实际不会出问题（`@Volatile long` 在 JVM 上 64-bit 读写分两步但 `@Volatile` 保证原子），**但** `canFinishRecent` 这种 `timeGap > 500ms` 的复合判断，在两个线程同时读 + 写时，**可能差一帧**。生产几乎不可见，但 trace 排错时容易误诊。
- **修复成本**：把 4 个 `update*` + 4 个 `getTimeGap*` 各加 `synchronized(AnimSeqTimeStamp)`（8 行），**或**保留 `@Volatile` 但加 1 个 CountDownLatch + 4-thread stress 测试（30 行）。**生产建议前者**。

### 🟡 中风险（行为不一致但不致命）

> **状态：⚠️未修复（测试名与断言不符未改：testCallbackReturnsTrueEndsAnimation 仅断 callbackSize==1，A4 ~6 行真驱动未补；doAnimationFrame 行为代码已由 60bd048 修正为每轮重读 size）**
#### 3.6 `AnimationHandler` 测试名 `testCallbackReturnsTrueEndsAnimation` 与断言不符

- `AnimationHandlerTest.kt:46-56`：方法名暗示 "返回 true 触发结束"，但断言只是 `assertEquals(1, handler.callbackSize)`，**没让 callback 真正跑起来**。如果有人把 `if (cb.doAnimationFrame(frameTimeMs)) animationCallbacks[idx] = null` 改成永远 false，**这个测试仍然通过**。
- **修复成本**：3 行（在 test 内部 `scheduler.postFrameCallback(...)` 同步驱动一帧，断言下一帧 size=0）。

> **状态：⚠️未修复（feature 闸门 isSupportStartingSurface/supportInterruption 有意砍（§4.2 + doc 25 §3-b 同判）；A6 边界测试未补）**
#### 3.7 `AppFeatureUtils.isSupportStartingSurface()` 守门砍掉 → canFinishRecent 永远 true 在不支持的设备上

- **OPPO `AnimationSeqHelper.java:65-68`**：`(isSupportStartingSurface && supportInterruption && gap <= 500) ? false : true`
- **lib `AnimationSeqHelper.kt:36-37`**：`gap > 500`，**无条件**
- **风险**：若未来加 `supportInterruption` 守门时漏改 `canFinishRecent`，测试 **不会失败**（测试覆盖这条 true 分支用的就是 `gap=0`，根本没经过守门）。
- **修复成本**：1 个测试用例（mock 4 个 boolean 组合 × 2 个 gap 边界 = 8 行）。

> **状态：⚠️未修复（A3 决策树 3 测试未补 ~40 行；决策树本身 cdd125e 已改互斥 else-if+清理段（doc 03 §3-c），缺回归守护）**
#### 3.8 `delayStartActivityIfNeed` 3 层决策树 0 覆盖

- **现状**：`AnimationController.kt:140-167` 是整个 `AnimationController` 最复杂的逻辑（横屏/分屏/swipe 续行三种 timeout listener 的互斥决策），**0 个测试**。
- **风险**：任何 refactor 都不会被测到，包括"`specialSceneExitTimeOutMaxTime` 过期"（`uptimeMillis() > maxTime`）这条**关键兜底**分支。
- **修复成本**：3 个测试方法（landscape、overview、transition 各一）共 ~40 行。

> **状态：⚠️未修复（A7 reset 独立性测试未补；lastRecentStartTime/lastLaunchTaskTime 仍无业务消费者，"删或补测"决策未做）**
#### 3.9 `AnimSeqTimeStamp.lastRecentStartTime` 和 `lastLaunchTaskTime` 字段 0 覆盖

- **现状**：这两个字段在 lib 里有 `@Volatile` 字段、`update*` setter、`timeGapTo*` getter（`AnimSeqTimeStamp.kt:21-32, 64-68`），**没有任何业务或测试使用**。
- **风险**：未来业务接入时会忘掉 reset 逻辑或写错单位。**死代码风险**。
- **修复成本**：2 个测试用例 ~10 行；或干脆删掉这 2 个字段（如果业务不打算用）。

### 🟢 低风险（已记入 review，已修或可接受）

| # | 差异 | 现状 | 修复决策 |
|---|---|---|---|
| 3.10 | **状态：✔️保持简化（lib 无 canGoNormalRecent API，常量无引用；§4.2 同判）** `MAX_GO_NORMAL_DELAY_TIME = 200` 未移植 | `AnimationSeqHelper.java:23` 有，lib 没 | 不需要（OPPO 用在 `canGoNormalRecent`，lib 没此 API） |
| 3.11 | **状态：✔️保持简化（demo 用 Trace；review 08）** `LogUtils.i` 全砍 | `AnimationSeqHelper.java:36, 93` 有 | 不需要（demo 模块用 `Trace`） |
| 3.12 | **状态：✔️保持简化（Kotlin 非空类型替代 checkNotNullParameter）** `Intrinsics.checkNotNullParameter` 砍掉 | 全方法 | 不需要（Kotlin nullable 类型更 idiom） |
| 3.13 | **状态：✔️保持简化（doc 03 §3-f 已判"手势层有意简化"，已入 SUMMARY risk list）** `MESSAGE_RELEASE_TOUCH` (101) 触摸释放定时器砍掉 | `AnimationController.java:516, 545` 有 | 已记入 SUMMARY-vs-oppo.md risk list（demo 不需要） |

---

## 4. 回移建议

### 4.1 值得补（高 ROI，能直接守护已知风险）

| # | 建议补的测试 | 工作量 | 守护的风险点 |
|---|---|---|---|
| **A1** | **状态：⚠️未修复（未补 ~70 行；守护 §3.2）** `addRecentsAnim` 9 个状态转移位的全枚举测试 | ~70 行（9 case × 8 行） | §3.2 UNKNOWN 兜底无回归保护 |
| **A2** | **状态：⚠️未修复（未补 ~12 行；守护 §3.3——底层 end 通路已 ✅，测试降为可选守护）** `checkAllAnimationFinished` 端分支单测（双 callback 已设 + 双列表空） | ~12 行 | §3.3 收尾 NPE 风险 |
| **A3** | **状态：⚠️未修复（未补 ~40 行；守护 §3.8）** `delayStartActivityIfNeed` 三层决策树测试（landscape / transition / overview + max-time 过期） | ~40 行 | §3.8 横屏/分屏兜底决策 |
| **A4** | **状态：⚠️未修复（未补 ~6 行；守护 §3.6）** `AnimationHandler.testCallbackReturnsTrueEndsAnimation` 真正驱动一帧（mock scheduler 调 onTick） | ~6 行 | §3.6 测试名误导 |
| **A5** | **状态：⚠️未修复（未补 ~25 行；P0——timeout 兜底本身仍 0 测试，守护 §3.4）** `TaskStateChangeTimeOutListener` 单元测试（构造 + 触发 timeout + dispose 验证 callback 被清） | ~25 行 | §3.4 全局事件总线缺位 + timeout 兜底本身无测试 |
| **A6** | **状态：⚠️未修复（未补 ~20 行；守护 §3.7）** `AnimationSeqHelper.canFinishRecent / canInterceptGesture` 4 boolean × 2 gap = 8 边界测试（含 `isSupportStartingSurface` 守门） | ~20 行 | §3.7 守门砍掉后无回归保护 |
| **A7** | **状态：⚠️未修复（未补 ~15 行；守护 §3.9）** `AnimSeqTimeStamp` 4 字段 reset 独立性（`resetLastStartAppTime` 不影响其他字段） | ~15 行 | §3.9 死代码 |
| **A8** | **状态：⚠️未修复（未补 ~30 行 stress；守护 §3.5——若维持 @Volatile 简化则该测试价值有限）** `AnimSeqTimeStamp` 多线程并发写（2 线程 × 1000 次 update vs read） | ~30 行 | §3.5 `@Volatile` vs `synchronized` 降级 |
| **小计** | — | **~220 行** | — |

### 4.2 建议保持简化（不值得补）

| 简化项 | 理由 |
|---|---|
| **状态：✔️保持简化（AndroidX 单弹簧既定替代；多体=功能扩展）**  `MultiDynamicAnimation` 未移植 | lib 用 AndroidX `SpringAnimation` 直接做单 spring；如未来要支持多体弹簧（6 自由度 RectF），属于 *功能扩展* 而非 *回移*。需要时再补 ~200 行（含 SpringHolder 移植）+ 测试。**当前 demo 不需要**。 |
| **状态：✔️保持简化（Kotlin idiom 替代）**  `Intrinsics.checkNotNullParameter` 砍掉 | Kotlin nullable 类型已经是更 idiomatic 的版本；强制非空检查在 lib 由 `requireNotNull` / `checkNotNull` Kotlin idiom 替代。 |
| **状态：✔️保持简化（无 API 依赖）**  `MAX_GO_NORMAL_DELAY_TIME = 200` 未移植 | lib 的 `AnimationSeqHelper` 不暴露 `canGoNormalRecent` API，OPPO 用此方法判断"能否走 normal recent 路径"（`AnimationSeqHelper.java:30-37` 隐含的 200ms），**lib 业务不依赖**，删了正确。 |
| **状态：✔️保持简化（demo 用 Trace）**  `LogUtils.i` 全砍 | demo 模块用 `Trace.traceBegin/End` 即可（review 08 已论证）；不暴露 `LogUtils` 接口。 |
| **状态：✔️保持简化（不建议补 CopyOnWriteArrayList 总线；需要时改 SharedFlow 30+20 行——与 §3.4 行为缺口并存）**  `BaseTaskStateChangeListener` 全局总线 | **不建议补**——OPPO 的全局总线是 module-singleton `CopyOnWriteArrayList`，lib 可以改用 `SharedFlow` 或保留 `TaskStateChangeTimeOutListener.onTimeOut` 单方法；补全总线工作量大且风险高（多线程 listener 派发）。如果业务需要可触发，**改用 `SharedFlow` 而不是 `CopyOnWriteArrayList`**（30 行 + 测试 20 行）。 |
| **状态：✔️保持简化（demo 不涉及多 app 并发动画）**  `MultiAppAnimMergeHelper` 砍掉 | 是 `OplusAnimManager.getMultiAppAnimMergeHelper()` 的多 app 合并动画协调器，**demo 业务场景不涉及多 app 并发动画**。保持简化。 |

### 4.3 总览：测试缺口补全优先级

| 优先级 | 测试 | 守护点 | 工作量 | 阻塞上线？ |
|---|---|---|---|---|
| 🔴 P0 | A1（9 转移位） | 状态机兜底 | 70 行 | 否（已知 design choice） |
| 🔴 P0 | A5（timeout listener 单元） | timeout 兜底本身无测试 | 25 行 | **是**（review 12 已列） |
| 🟡 P1 | A3（决策树） | 横屏/分屏/scene-exit 决策 | 40 行 | 否 |
| 🟡 P1 | A2（end 分支） | 收尾 NPE | 12 行 | 否 |
| 🟢 P2 | A4 / A6 / A7 / A8 | 命名误导、边界、死代码、并发 | ~70 行 | 否 |

**总补全工作量 ~220 行**（折合约 5–6 个工作小时）；最小可发布集合 = A1 + A5 = **~95 行**。

## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及项 **未在本批落地任何修复**（保持原样/保持简化/属更大重构范围）。

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。
## 批次 6 逐条复核（2026-09-09 / 子代理逐项）

| 条目 | 判定 |
|---|---|
| 3.1 MultiDynamicAnimation 未移植 | ✔️保持简化（AndroidX 单弹簧既定替代） |
| 3.2 12 状态 × 4 转移位覆盖 1/9 | ⚠️未修复（A1 未补） |
| 3.3 checkAllAnimationFinished 端分支 | ✅已修复（cdd125e+60bd048；NPE 论述不成立；A2 测试未补） |
| 3.4 TaskStateChangeTimeOutListener 全局事件总线 | ⚠️未修复（doc 03 §3-d 同判） |
| 3.5 AnimSeqTimeStamp @Volatile vs synchronized | ✔️保持简化（无跨字段不变式） |
| 3.6 testCallbackReturnsTrueEndsAnimation 名不符 | ⚠️未修复（A4 未补） |
| 3.7 canFinishRecent 缺 feature 闸门测试 | ⚠️未修复（A6 未补） |
| 3.8 delayStartActivityIfNeed 决策树 0 覆盖 | ⚠️未修复（A3 未补） |
| 3.9 lastRecentStartTime/lastLaunchTaskTime 0 覆盖 | ⚠️未修复（A7 未补） |
| 3.10 MAX_GO_NORMAL_DELAY_TIME 未移植 | ✔️保持简化 |
| 3.11 LogUtils.i 全砍 | ✔️保持简化 |
| 3.12 Intrinsics.checkNotNullParameter 砍掉 | ✔️保持简化 |
| 3.13 MESSAGE_RELEASE_TOUCH 定时器砍掉 | ✔️保持简化 |
| A1 addRecentsAnim 9 转移位全枚举测试 | ⚠️未修复（未补 ~70 行） |
| A2 checkAllAnimationFinished end 单测 | ⚠️未修复（未补 ~12 行；底层已 ✅） |
| A3 delayStartActivityIfNeed 决策树测试 | ⚠️未修复（未补 ~40 行） |
| A4 AnimationHandler 真驱动一帧 | ⚠️未修复（未补 ~6 行） |
| A5 TaskStateChangeTimeOutListener 单测 | ⚠️未修复（未补 ~25 行；P0） |
| A6 canFinishRecent/canInterceptGesture 边界测试 | ⚠️未修复（未补 ~20 行） |
| A7 AnimSeqTimeStamp reset 独立性 | ⚠️未修复（未补 ~15 行） |
| A8 AnimSeqTimeStamp 并发 stress | ⚠️未修复（未补 ~30 行） |
| 4.2-1..6 保持简化 6 项 | ✔️保持简化 |
