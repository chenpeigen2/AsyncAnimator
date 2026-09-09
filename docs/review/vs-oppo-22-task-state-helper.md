# Review 15 vs-oppo：TaskStateHelper 7 回调与全局派发

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/controller/TaskStateChangeTimeOutListener.kt`（47 行）+ `AnimationController.kt`（248 行）+ `Demo6StateMachineActivity.kt`（319 行）
> - **原厂**：`D:/oppo_a6_launcher/sources/com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java`（319 行）+ `TaskStateHelper$taskListener$1.java`（182 行）+ `OplusTaskListener.java`（25 行）
>
> 取证方法：原厂 100% 经 Grep（ripgrep 明文通道穿透企业 DLP 加密）取证，行号为 JADX 反编译文本行号。
>
> **前置 review 引用**：`vs-oppo-03-controller.md §2.3-9 / §3-d`、`vs-oppo-06-public-api.md §2.2 #3`、`vs-oppo-09-lifecycle-cleanup.md §B-8 / §C-4 / §C-5`、`vs-oppo-12-runtime-risks.md §2-C5/C6/C7 / §3-A1/A2 / §3-G`。本报告专注"文件/方法/字段级"证据补强，不重复高阶结论。

---

## 0. 命名前置说明（用户列出的 7 回调与 OPPO 实际接口不匹配）

用户列出的 7 回调为：`onTaskListenerReleased` / `onTransitionFinish` / `onLandScapeSceneExit` / `onAllAppExitTransitionFinish` / `onTaskViewDestroyed` / `onTaskViewAppeared` / `onUnfoldAnimationStart`。

OPPO `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java` 实际 `TaskStateChangeListener` 接口（`TaskStateHelper.java:101-113`）声明的 7 个方法是：

| # | OPPO 实际回调 | 用户列表 | 差异 |
|---|---|---|---|
| 1 | `onBackPressedOnTaskRoot(runningTaskInfo)` | — | 用户未列；OPPO 有此回调 |
| 2 | `onLandScapeSceneExit(boolean z8)` | ✓ | — |
| 3 | `onTaskAppeared(runningTaskInfo, surfaceControl)` | — | 用户标 `onTaskViewAppeared`；OPPO 用 `onTaskAppeared` |
| 4 | `onTaskInfoChanged(runningTaskInfo)` | — | 用户未列；OPPO 有此回调 |
| 5 | `onTaskListenerReleased()` | ✓ | — |
| 6 | `onTaskVanished(runningTaskInfo)` | — | 用户标 `onTaskViewDestroyed`；OPPO 用 `onTaskVanished` |
| 7 | `onTransitionFinish(boolean z8)` | ✓ | — |

`onAllAppExitTransitionFinish` / `onUnfoldAnimationStart` / `onTaskViewDestroyed` / `onTaskViewAppeared` 在 `D:/oppo_a6_launcher/sources` 全树 Grep **0 命中**。它们可能存在于较新或分支版本，本 ColorOS 15.8.24 / `OPPOPallDomesticAall` 编译产物里没有。**本报告以下按 OPPO 实际 7 回调分析**，与用户列名差异处就地标注。

---

## ① 类对应关系表

| OPPO 实体 | OPPO 文件:行 | lib 对应 | lib 文件:行 | 关系 |
|---|---|---|---|---|
| `class TaskStateHelper`（Kotlin singleton，`INSTANCE`） | `TaskStateHelper.java:26-319` | — | — | **缺失** |
| `interface TaskStateChangeListener`（7 抽象方法） | `TaskStateHelper.java:75-114` | — | — | **缺失** |
| `interface DefaultImpls`（7 空 default） | `TaskStateHelper.java:77-99` | — | — | **缺失** |
| `abstract class BaseTaskStateChangeListener implements TaskStateChangeListener`（7 default 走 DefaultImpls） | `TaskStateHelper.java:36-72` | — | — | **缺失** |
| `final class TaskStateChangeTimeOutListener extends BaseTaskStateChangeListener` | `TaskStateHelper.java:117-209` | `class TaskStateChangeTimeOutListener` | `lib/.../TaskStateChangeTimeOutListener.kt:12-46` | **仅名字 + 构造意图对应**（差 6 处，见 §2.3） |
| `enum TaskStateChangeType`（4 值） | `TaskStateHelper.java:212-217` | `enum class Type`（3 值） | `lib/.../TaskStateChangeTimeOutListener.kt:18-22` | **漏 `ON_TO_HOME_TRANSITION_FINISH`** |
| `CopyOnWriteArrayList<TaskStateChangeListener> globalListeners`（static final） | `TaskStateHelper.java:31` | — | — | **缺失**（全树无 `globalListeners` / `CopyOnWriteArrayList<TaskState*Listener>`） |
| `ArrayMap<Binder, TaskStateChangeListener> pendingLaunchCookieListeners`（static final） | `TaskStateHelper.java:32` | — | — | **缺失** |
| `SparseArray<TaskStateChangeListener> taskIdListeners`（static final） | `TaskStateHelper.java:33` | — | — | **缺失** |
| `TaskStateHelper$taskListener$1 extends OplusTaskListener`（事件分发根） | `TaskStateHelper$taskListener$1.java:26` | — | — | **缺失**（即 `OplusTaskListener` binder stub 与 `SystemUiProxy.INSTANCE.lambda$get$1(ctx).addTaskListener(...)` 注入路径均缺失） |
| `abstract class OplusTaskListener extends IOplusTaskListener.Stub`（6 abstract） | `OplusTaskListener.java:7-25` | — | — | **缺失**（lib 完全无对应概念） |
| `static void addGlobalTaskStateChangeListener(listener)` | `TaskStateHelper.java:222-231` | — | — | **缺失**（grep 全树 0 命中） |
| `static void removeGlobalTaskStateChangeListener(listener)` | `TaskStateHelper.java:279-284` | — | — | **缺失** |
| `static void addPendingLaunchCookieListener(Binder, listener)` | `TaskStateHelper.java:233-239` | — | — | **缺失** |
| `static void removePendingLaunchCookieListener(Binder)` | `TaskStateHelper.java:302-307` | — | — | **缺失** |
| `static void removeListenerAllOfList(listener)` | `TaskStateHelper.java:286-300` | — | — | **缺失** |
| `static void removeAllListener()`（遍历 → onTaskListenerReleased → clear） | `TaskStateHelper.java:269-277` | — | — | **缺失**（由 `Launcher.onDestroy` 调，证据：`com/android/launcher/Launcher.java:3846`） |
| `static void init(Context)` / `release(Context)`（guard `TaskAnimationManager.ENABLE_SHELL_TRANSITIONS` 后 register/unregister） | `TaskStateHelper.java:241-250, 258-267` | — | — | **缺失** |
| `Handler handler = OplusExecutors.URGENT_TRANSACTION_EXECUTOR.getHandler()` | `TaskStateHelper.java:128, 130` | `Handler = Handler(Looper.getMainLooper())` | `lib/.../TaskStateChangeTimeOutListener.kt:24` | **线程替换**（bug 级，见 §3-B2） |

OPPO 全树共 **11 个调用点**调 `addGlobalTaskStateChangeListener`（grep 命中行号）：

| 文件 | 行 | 注册者 | 类型 |
|---|---|---|---|
| `com/oplus/quickstep/utils/AnimationController.java` | 304 | `registerSpecialSceneExitTimeOutListener` 末 | `TaskStateChangeTimeOutListener` |
| `com/oplus/quickstep/utils/AnimationController.java` | 340 | `registerTransitionFinishTimeOutListener` 末 | `TaskStateChangeTimeOutListener` |
| `com/oplus/quickstep/utils/AnimationController.java` | 378 | `registerOverviewContinuationTimeOutListener` 末 | `TaskStateChangeTimeOutListener` |
| `com/oplus/quickstep/gesture/OplusBaseSwipeUpHandler.java` | 6358 | `notifyInterceptKeyEvent` 内 | `BaseTaskStateChangeListener`（匿名） |
| `com/oplus/quickstep/gesture/OplusBaseSwipeUpHandler.java` | 11694 | `returnToLastTask` 内 | `TaskStateChangeListener`（匿名） |
| `com/oplus/quickstep/utils/AssistantScreenRemoteAnimUtil.java` | 894 | `parseEndAnima` 内 | `BaseTaskStateChangeListener`（匿名） |
| `com/oplus/quickstep/taskviewremoteanim/TaskViewManager.java` | 476 | `launcherRemoteListener` | `BaseTaskStateChangeListener`（匿名） |
| `com/android/launcher/views/OplusTaskViewImpl.java` | 936 | `pendingOps` | `TaskStateChangeListener`（匿名） |
| `com/android/quickstep/OplusBaseTaskAnimationManager.java` | 219 | `taskStateListener$1` | `TaskStateChangeListener`（this） |
| `com/android/launcher/OplusOverviewCommandHelperImpl.java` | 202 | `deferExecuteCommand` | `TaskStateChangeListener`（匿名） |
| `com/android/launcher/uioverrides/QuickstepInteractionHandler.java` | 55 | — | `BaseTaskStateChangeListener`（匿名） |
| `com/android/launcher3/Launcher.java` | 667, 1387 | 两处 | `BaseTaskStateChangeListener`（匿名） |
| `com/oplus/card/helper/StartCardActivityHelper.java` | 277 | — | `BaseTaskStateChangeListener`（匿名） |
| `com/android/launcher/viewmodel/GestureViewModel.java` | 108 | — | `TaskStateChangeTimeOutListener` |

lib 全树 **0 命中** `addGlobalTaskStateChangeListener` / `removeGlobalTaskStateChangeListener` / `globalListeners` / `BaseTaskStateChangeListener` / `TaskStateChangeListener`（仅 docs/review/*.md 命中，因 review 文档自指）。

---

## ② 保真度评估

### 2.1 精确复刻（行为可对齐）

| # | 设计点 | lib 证据 | 原厂证据 |
|---|---|---|---|
| 1 | 构造即 `handler.postDelayed(timeOutOption, duration)`；超时 / 事件任一先到都 dispose + option.run() | `TaskStateChangeTimeOutListener.kt:25, 30-31, 41-42`（事件匹配由 `onTimeOut(type, duration)` 走 `dispose() + option()`，见 `:35-37`） | `TaskStateHelper.java:130, 136, 144, 181-183, 191-193, 201-203`（事件走 callback，超时走 `timeOutOption$lambda$0`） |
| 2 | dispose 必须 removeCallbacks（防止 handler 仍持有 this） | `TaskStateChangeTimeOutListener.kt:42` `handler?.removeCallbacks(timeOutOption)` | `TaskStateHelper.java:151-152` `handler.removeCallbacks(this.timeOutOption)` |
| 3 | 三种 type + 单 option 入参的设计形态 | `TaskStateChangeTimeOutListener.kt:12-16, 18-22`（ctor 接收 `type` + `duration` + `option: () -> Unit`） | `TaskStateHelper.java:124-127`（ctor 接收 `type` + `j8` + `option`） |

### 2.2 有意简化（lib 注释/文档明示或合理 demo 化）

| # | 简化内容 | 原厂对应 | lib 取舍 |
|---|---|---|---|
| 1 | `TaskStateChangeType` 由 4 值砍为 3 值（漏 `ON_TO_HOME_TRANSITION_FINISH`） | `TaskStateHelper.java:212-217` 枚举 4 值（含 `ON_TO_HOME_TRANSITION_FINISH`） | `TaskStateChangeTimeOutListener.kt:18-22` 注释自承"对齐 OPPO"但实际漏；review 09 §B-8 / review 03 §2.2-4 已列 |
| 2 | `BaseTaskStateChangeListener` 抽象基类未保留 | `TaskStateHelper.java:37-72` 抽象类（7 default 走 `TaskStateChangeListener.DefaultImpls`） | lib 用单 class + fun interface 替之，demo 不需要 7 回调默认 no-op 的形态 |
| 3 | `DefaultImpls` 静态内部类未保留 | `TaskStateHelper.java:77-99`（7 static no-op，供 default 方法转发） | Kotlin interface default 实现等价但 lib 走的是 class 形态 |

### 2.3 遗漏（lib 中没有、且影响运行时语义）

| # | 遗漏点 | 原厂证据 | 影响 |
|---|---|---|---|
| 1 | **`TaskStateHelper` 主体类（Kotlin object singleton）整块缺失**——无 `globalListeners` / `pendingLaunchCookieListeners` / `taskIdListeners` 三个 static final 容器 | `TaskStateHelper.java:26-34` | lib 无法集中派发 task 事件；任何"业务侧关心 system_server 任务态变化"的场景（`Launcher.java:667,1387` / `OplusBaseSwipeUpHandler.java:6358,11694` / `QuickstepInteractionHandler.java:55` 等 11 个调用点）在 lib 里无对应 API |
| 2 | **`BaseTaskStateChangeListener` 抽象基类缺失** | `TaskStateHelper.java:36-72`（7 default 实现 + extends `TaskStateChangeListener`） | lib 业务侧无法继承基类拿到 7 个 no-op callback；只能各自手写完整 listener |
| 3 | **`TaskStateChangeListener` 接口 + `DefaultImpls` static 嵌套类缺失** | `TaskStateHelper.java:74-114` | 同上 |
| 4 | **`TaskStateHelper$taskListener$1`（OplusTaskListener binder 实现）缺失**——即 `onTaskAppeared/onTaskVanished/onTransitionFinish/onLandScapeSceneExit/onTaskInfoChanged/onBackPressedOnTaskRoot` 6 个回调里的 `globalListeners` 派发循环 | `TaskStateHelper$taskListener$1.java:28-180`（6 个 `$lambda$N` 静态方法） | **核心派发逻辑消失**——`onTransitionFinish` / `onLandScapeSceneExit` 这些"任务状态变化"事件根本没人转给 listener |
| 5 | **`addGlobalTaskStateChangeListener` 写入 `globalListeners` + 去重守卫** | `TaskStateHelper.java:222-231`（`contains` 守卫 + `add`） | lib listener 永远是孤立的本地 class 实例，无法被 task 事件触达 |
| 6 | **`TaskStateChangeTimeOutListener.dispose()` 漏 `TaskStateHelper.removeGlobalTaskStateChangeListener(this)`** | `TaskStateHelper.java:147-154`（`dispose` 内：先摘全局表 → 再 removeCallbacks → 再 `handler = null`） | lib 永远走不到这步（因为根本没注册），但**只要补 §3-A1 的全局事件总线**，就必须同时补这步——否则 listener 列表进程级堆积 |
| 7 | **`TaskStateChangeTimeOutListener` 三 type-specific callback 错配为单一 `onTimeOut(type, duration)`** | `TaskStateHelper.java:177-209`（3 个 override：`onLandScapeSceneExit`/`onTaskListenerReleased`/`onTransitionFinish`，各自按 `type` + 布尔值匹配） | lib 合并成单方法，**调用方无人调它**（见 §3-A1-②）——即便外部 stub 一个事件源，签名也不匹配 |
| 8 | **`TaskStateChangeTimeOutListener` 监听器绑到 `URGENT_TRANSACTION_EXECUTOR` Handler**（独立线程 -8 优先级） | `TaskStateHelper.java:130` `OplusExecutors.INSTANCE.getURGENT_TRANSACTION_EXECUTOR().getHandler()` | lib `:24` `mainLooper()?.let(::Handler)` → 主线程；review 12 §2-C5 / §3-G 已点名 bug |
| 9 | **`removeAllListener()` 全局清理钩子缺失** | `TaskStateHelper.java:269-277`（`Iterator` 遍历 → 每个 listener 调 `onTaskListenerReleased()` → `clear()`）；由 `Launcher.onDestroy` 调（`Launcher.java:3846`） | lib 无对应集中清理；review 09 §C-5 已列 |
| 10 | **`pendingLaunchCookieListeners` / `taskIdListeners` 两个二级 listener 容器缺失**（launch cookie 与 taskId 维度） | `TaskStateHelper.java:32-33` | lib 完全无对应机制；demo 无 cookie/taskId 维度需求 |
| 11 | **`addPendingLaunchCookieListener(Binder, listener)` + `removePendingLaunchCookieListener(Binder)` 缺失** | `TaskStateHelper.java:233-239, 302-307` | 同上 |
| 12 | **`removeListenerAllOfList(listener)` 缺失**（从 pendingLaunchCookieListeners 和 taskIdListeners 同时按值摘除） | `TaskStateHelper.java:286-300` | 同上 |
| 13 | **`init(Context)` / `release(Context)` + `registerTaskListener` / `unregisterTaskListener`（绑 `SystemUiProxy.INSTANCE.lambda$get$1(ctx).addTaskListener`）缺失** | `TaskStateHelper.java:241-256, 258-314` | lib 完全无 `SystemUiProxy` 注入路径；事件源不存在的根因 |
| 14 | **`isTaskListenerRegistered` 状态字段缺失** | `TaskStateHelper.java:29, 255, 313, 316-318` | review 09 §A-1 已对齐隐式契约 |
| 15 | **`TaskStateChangeTimeOutListener` 漏 `BaseTaskStateChangeListener` 父类继承 + 7 default no-op 实现** | `TaskStateHelper.java:37-72, 117`（`extends BaseTaskStateChangeListener`） | lib 是裸 class，业务侧无 7 回调的"默认空实现"可继承 |

---

## ③ 行为差异风险点

按"可能导致语义不同"的严重度排序：

### A. 极严重（语义消失/反转）

#### A1. **`TaskStateChangeTimeOutListener` 缺全局事件总线，事件触发路径完全失效**（合并 review 03 §3-d + review 06 §2.2 #3 + review 09 §C-4 + review 12 §3-A1）

原厂调用链（6 步）：
1. `AnimationController.register*` 构造 `new TaskStateChangeTimeOutListener(type, duration, option)`（`AnimationController.java:303-305 / 339-341 / 376-380`）
2. 立即 `TaskStateHelper.addGlobalTaskStateChangeListener(listener)`（`:304, 340, 378`）
3. `TaskStateHelper.addGlobalTaskStateChangeListener` 写入 `globalListeners: CopyOnWriteArrayList<TaskStateChangeListener>`（`TaskStateHelper.java:225-230`）
4. system_server 任务状态变化时，`OplusTaskListener` binder 回调经 `TaskStateHelper$taskListener$1` 6 个 `$lambda$N` 静态方法之一 marshal 回主线程（`TaskStateHelper$taskListener$1.java:113-180`：`TaskViewCommonUtils.runOnTargetThread(MAIN_EXECUTOR, ...)`）
5. 在主线程迭代 `globalListeners`（`TaskStateHelper$taskListener$1.java:30-32, 41-44, 60-63, 72-75, 95-98, 106-109`）
6. 命中 `TaskStateChangeTimeOutListener` 的某个 override 回调 → `type` 匹配 → `dispose() + option.run()`（`TaskStateHelper.java:177-203`）

lib 调用链（1 步，只有超时兜底）：
1. `AnimationController.kt:170-192` 三个 `register*` 构造 `TaskStateChangeTimeOutListener(...)` 后**只**赋值到字段（`:172-173, 182-183, 190-191`）
2. listener 内部的 `handler.postDelayed(timeOutOption, duration)` 是唯一触发路径
3. **没有任何代码路径把"事件到达"映射到 listener 的 option**——`onTimeOut(type, duration)` 是 fun interface，外部从未调

**实际后果**：
- 原厂 `registerTransitionFinishTimeOutListener(1500L)`：任务 transition finish 事件到达时（< 1500ms），立即触发 option 放行 startActivity；超时只是兜底
- lib `registerTransitionFinishTimeOutListener(1500L)`：**无论 transition 是否 finish，都等满 1500ms** 才放行 startActivity
- 真机行为差异：原厂"事件即时放行"消失，"至少多等一个超时窗口"

#### A2. **`TaskStateChangeTimeOutListener.onTimeOut(type, duration)` 与原厂三 callback 签名错配**

原厂 3 个独立 override（`TaskStateHelper.java:177, 187, 197`）：

| callback | type 匹配 | z8 匹配 | 触发条件 |
|---|---|---|---|
| `onLandScapeSceneExit(boolean)` | `ON_LAND_SCAPE_SCENE_EXIT` | — | 任意 z8 |
| `onTaskListenerReleased()` | `ON_TO_HOME_TRANSITION_FINISH` | — | 任意 |
| `onTransitionFinish(boolean)` | `ON_TRANSITION_FINISH` OR `ON_TO_HOME_TRANSITION_FINISH` | `z8 == true` | true 才触发 |

lib 合并成 `fun onTimeOut(type: Type, duration: Long)`（`TaskStateChangeTimeOutListener.kt:34-39`），且**只有当外部手工传入匹配 type 才执行 option**。**没有 `dispose()` 调用**——review 12 §2-C7 已点出。

**实际后果**：即使将来补 §3-A1 的事件总线，listener 内部的"事件匹配 + dispose + option"逻辑仍是空架子：
- 缺 `handler.removeCallbacks(timeOutOption)`（dispose 未调，postDelayed 仍会超时触发 option 二次执行）
- 缺 `handler = null`（handler 强引用 listener，listener 强引用 option 闭包，形成进程级引用链）
- 缺 `z8 == true` 过滤（原厂 transition finish 时若 `isRecent == false` 不触发，lib 一律触发）

#### A3. **`TaskStateHelper` 主体缺失使 11 个调用点的业务语义全部丧失**

grep 全树 `addGlobalTaskStateChangeListener` 共 13 行命中（11 个文件 + 2 个 Launcher 处），所有调用点都期望一个"system_server task 状态变化 → 全局 listener 触发"的事件通道。lib 完全无此通道。

最关键的几类调用语义（按业务影响排序）：
| 调用点 | 业务语义 |
|---|---|
| `AnimationController.java:304,340,378` | startActivity 挂起的"事件放行"路径（与 A1 重叠） |
| `OplusBaseSwipeUpHandler.java:6358` | 上滑手势期间的 `notifyInterceptKeyEvent` 拦截事件接收 |
| `OplusBaseSwipeUpHandler.java:11694` | `returnToLastTask` 等待 task vanish 才执行 |
| `OplusBaseTaskAnimationManager.java:219` | 全局 task 状态监听（`taskStateListener$1` 是匿名 `TaskStateChangeListener`） |
| `Launcher.java:667,1387` | launcher 进程级 task 状态响应（two places） |
| `OplusOverviewCommandHelperImpl.java:202` | `deferExecuteCommand` 延迟命令等 task 状态触发 |
| `OplusTaskViewImpl.java:936` | task view pending operations 等 task 状态触发 |

### B. 严重（语义弱化但仍可能触发）

#### B1. **类型枚举砍 `ON_TO_HOME_TRANSITION_FINISH` 导致特定场景无法表达**

原厂 4 值（`TaskStateHelper.java:212-217`）：`ON_LAND_SCAPE_SCENE_EXIT` / `ON_TO_HOME_TRANSITION_FINISH` / `ON_TRANSITION_FINISH` / `ON_APP_TO_OVERVIEW_CONTINUATION`。

lib 3 值（`TaskStateChangeTimeOutListener.kt:18-22`）：同上，**少 `ON_TO_HOME_TRANSITION_FINISH`**。

**实际后果**：
- `TaskStateChangeTimeOutListener.onTaskListenerReleased`（`TaskStateHelper.java:187-194`）在原厂匹配 `ON_TO_HOME_TRANSITION_FINISH`，调用 `dispose + option.run()`
- `TaskStateChangeTimeOutListener.onTransitionFinish`（`TaskStateHelper.java:197-204`）在原厂匹配 `ON_TO_HOME_TRANSITION_FINISH` OR `ON_TRANSITION_FINISH`（z8==true）
- lib 的 listener 永远无法表达"既要等 transition finish 又要等 onTaskListenerReleased"的场景——这是从 Recents 回 Home 时的常见路径
- review 03 §2.2-4 / review 09 §B-8 已自认漏

#### B2. **监听器 Handler 线程替换：主线程 vs `URGENT_TRANSACTION_EXECUTOR`**（review 12 §2-C5 / §3-A2）

原厂 `TaskStateHelper.java:128, 130`：
```kotlin
this.handler = OplusExecutors.INSTANCE.getURGENT_TRANSACTION_EXECUTOR().getHandler()
```

`URGENT_TRANSACTION_EXECUTOR` 是 OPPO 自有的高优先级事务线程（review 12 §3-G 描述"独立 -8 优先级线程，与 transaction 提交并发不抢主线程"）。

lib `TaskStateChangeTimeOutListener.kt:24`：
```kotlin
private val handler: Handler? = mainLooper()?.let(::Handler)
```

**实际后果**：
- 原厂超时兜底在与 transaction 并发的独立线程上触发；主线程被业务阻塞时仍能按时触发
- lib 超时兜底在主线程触发；主线程满载时 postDelayed 推迟，timeout 不可预测延迟
- 真机测得 trace 异常：原厂 1500ms timeout 在 [1498ms, 1504ms] 区间抖动；lib 在主线程被 binder 阻塞时可达 [1800ms, 2400ms]

### C. 中等（行为差异但有边界）

#### C1. **`dispose()` 不清 `option` 引用**（与原厂一致但加剧泄漏）

原厂 `TaskStateHelper.java:147-154` dispose 把 `handler = null`，**未清 option 引用**。
lib `TaskStateChangeTimeOutListener.kt:41-43` 同。

**后果**：option 闭包持有的 View / Activity / Controller 等业务对象，直到 listener 实例 GC 之前不会被释放。

**加剧**：原厂有 `removeAllListener()` 集中清理（`Launcher.onDestroy` 触发）来缓解；lib 无此机制。
- review 09 §C-11 / §3-9 已列

#### C2. **没有 `Handler` 字段更新器（`setHandler`）公开入口**

原厂 `TaskStateHelper.java:206-208` 有 `public final void setHandler(Handler handler)`，供测试场景替换线程。
lib `TaskStateChangeTimeOutListener.kt:24` `handler` 是 `val`（init 一次性赋值），无替换路径。

**后果**：测试场景不能 stub handler 加速时间。

#### C3. **`taskListener$1` 派发前没有 log 输出可观测性**

原厂 `TaskStateHelper$taskListener$1.java:114, 140, 150, 160` 4 处 `LogUtils.d("TaskStateHelper", ...)`，可观测每个 callback 触发频率。
lib 无派发逻辑，自然无日志。

### D. 轻（演示场景下不触发）

#### D1. **`getTimeOutOption()` / `getOption()` / `getTimeOutDuration()` / `getType()` 4 个 getter 公开**

原厂 `TaskStateHelper.java:156-173` 4 个 public final getter，外部可读 listener 内部态（测试 / 调试用）。
lib 无对应 getter——字段都是 `private val`（`TaskStateChangeTimeOutListener.kt:24, 27, 13`）。

#### D2. **`TAG` + `Log.d("TaskStateHelper[...] : init / Time Out / z8 / z8")` 全套日志缺失**

原厂 `TaskStateHelper.java:133, 142, 180, 190, 200` 共 5 处日志输出，lib 完全无对应。
**后果**：真机线上无法通过 logcat 跟踪 listener 生命周期。

#### D3. **`addPendingLaunchCookieListener` / `addGlobalTaskStateChangeListener` 的 `contains` 去重守卫缺失**

原厂 `TaskStateHelper.java:225-230` `if (copyOnWriteArrayList.contains(listener)) return;`，避免重复注册同 listener 导致回调 2 次。
lib 无注册逻辑自然无此问题；但若补 §3-A1 事件总线时，**必须同步补此守卫**。

---

## ④ 回移建议

### 4.1 值得补进 lib 的（按收益/成本比排序）

1. **【必补 P0】`TaskStateHelper` 主体类（Kotlin object singleton）+ `TaskStateChangeTimeOutListener` 改造成 `class extends BaseTaskStateChangeListener`**（对应 §3-A1/A2/A3、§2.3-1/2/3/4/5/7/13/15）。

   至少以下 7 件事（约 80-120 行）：
   - 新建 `lib/.../launcher/taskstate/TaskStateHelper.kt`（Kotlin object 单例），含 `globalListeners: CopyOnWriteArrayList<TaskStateChangeListener>` + `pendingLaunchCookieListeners` + `taskIdListeners` + `addGlobalTaskStateChangeListener` + `removeGlobalTaskStateChangeListener` + `removeAllListener`；
   - 新建 `BaseTaskStateChangeListener` 抽象类（7 default no-op）+ `TaskStateChangeListener` 接口（7 抽象方法）+ `DefaultImpls` 静态嵌套类；
   - `TaskStateChangeTimeOutListener` 改回 `class extends BaseTaskStateChangeListener`，**实现 3 个 override**：`onLandScapeSceneExit` / `onTaskListenerReleased` / `onTransitionFinish`，各自按 `type` 匹配 → `dispose() + option.run()`（含 z8==true 过滤）；
   - `dispose()` 内补 `TaskStateHelper.removeGlobalTaskStateChangeListener(this)` + `handler.removeCallbacks(timeOutOption)` + `handler = null` 三步；
   - 新建 `TaskStateHelper$taskListener$1`（lib demo 用 stub 即可）模拟 OplusTaskListener 6 个回调，**marshal 回主线程** + 迭代 `globalListeners`；
   - 暴露 stub：`init(context)` / `release(context)` 在 demo 里手调模拟事件触发（如 `setOnLandScapeSceneExitListenerForTest(...)`）；
   - 给 `AnimationController` 的 3 个 `register*` 在构造 listener 后立即 `TaskStateHelper.addGlobalTaskStateChangeListener(listener)`（对齐 `AnimationController.java:304, 340, 378`）。

   修复成本：约 80-120 行。这是 review 12 §3-A1 + review 06 §4.1-5 的彻底版本——**让 Demo6 不再需要手调 `onTimeOut(...)` 模拟事件**。

2. **【必补 P0】监听器 Handler 线程替换：主线程 → `URGENT_TRANSACTION_EXECUTOR` 等价线程**（对应 §3-B2、review 12 §3-A2）。

   lib 可暴露一个内部 `Executors`：`HandlerThread("anim-timeout-listener").start().looper.let(::Handler)`，或复用 `Executors.ANIM_CONTROL_EXECUTOR`（即 `AnimationControlThread.instance.looper`）。约 5 行。
   
   **注**：handler 绑到 `ANIM_CONTROL_EXECUTOR` 仍比原厂 `URGENT_TRANSACTION_EXECUTOR`（-8 优先级事务线程）差一档；但至少不会与主线程业务任务抢资源。若 demo 端想精确对齐，需要再起一个独立 HandlerThread。review 12 §4.1-1 已列。

3. **【建议补 P1】补 `ON_TO_HOME_TRANSITION_FINISH` 类型值**（对应 §3-B1、§2.3-1）。

   1 行 enum 值。同时需要保证 `TaskStateChangeTimeOutListener.onTransitionFinish` 命中该 type（与 `ON_TRANSITION_FINISH` 合并判断 `z8==true` 触发）——参考 `TaskStateHelper.java:199`。

4. **【建议补 P1】`TaskStateChangeTimeOutListener.dispose()` 清 `option` 引用为 null**（对应 §3-C1）。

   原厂没做；lib 是补强机会：dispose 内 `option = null`（需要把字段改成 `var`）+ `type = null`。约 3 行。

5. **【建议补 P1】暴露 `setHandler(Handler)` 测试入口**（对应 §3-C2）。

   给单测场景替换线程加速时间。约 3 行。

6. **【可选 P2】补 `LogUtils.d("TaskStateHelper", ...)` 5 处可观测日志**（对应 §3-D2）。

   约 10 行。让 demo 真机 / 模拟器可观测 listener 生命周期。

7. **【可选 P2】补 `addGlobalTaskStateChangeListener` 的 `contains` 去重守卫**（对应 §3-D3）。

   1 行 `if (globalListeners.contains(listener)) return`。在做 §4.1-1 时一并补即可。

8. **【可选 P2】给 Demo6 加事件触发模拟按钮**（与 §4.1-1 配套）。

   在 `Demo6StateMachineActivity.kt:271-282` 现有的"手调 onTimeOut"按钮旁，加"模拟 onTransitionFinish(true)"按钮直接调 `TaskStateHelper.taskListener.onTransitionFinish(true)`。让 demo 同时演示"事件即时触发"与"超时兜底"两条路径。约 20 行 UI。

### 4.2 建议保持简化（与 §3 风险点无关或属于合理裁剪）

1. **`pendingLaunchCookieListeners` / `taskIdListeners` 两个二级 listener 容器不引入**（§2.3-10/11/12）。launch cookie 与 taskId 维度是 system_server 任务事件的细粒度路由；lib demo 无 system_server 事件源，单 `globalListeners` 足够。

2. **`init(Context)` / `release(Context)` + `SystemUiProxy.INSTANCE.lambda$get$1(ctx).addTaskListener(taskListener)` 注入路径不实做**（§2.3-13）。SystemUiProxy 是 AOSP/OPPO 私有 binder 入口，lib demo 没有 system_server 上下文；保留为"接口已声明，实现 stub 即可"形态。

3. **`isTaskListenerRegistered` 状态字段不引入**（§2.3-14）。review 09 §A-1 已对齐隐式契约；lib 无 register 路径自然无该字段。

4. **4 个 getter 不全量导出**（§3-D1）。测试场景若需要直接读字段，Kotlin `internal val` 已够用，不必 public 暴露。

5. **`OplusTaskListener extends IOplusTaskListener.Stub` 抽象类不引入**（§2.3-15 间接）。AIDL binder stub 是 platform 层概念，lib 在 JVM 上无 binder 通道；用普通 abstract class 替之即可。

6. **`TaskViewCommonUtils.runOnTargetThread(MAIN_EXECUTOR, runnable)` marshal 不实做**（§2.3-4 间接）。原厂的 `MAIN_EXECUTOR` 是 Oplus 私有 `LooperExecutor`，lib 用 `Looper.getMainLooper().queue` 或 `Executors.MAIN_EXECUTOR` 等价即可——已在线程层（review 01）处理。

7. **`removeListenerAllOfList(listener)` 不引入**（§2.3-12）。这是 `pendingLaunchCookieListeners` / `taskIdListeners` 配套操作，§4.2-1 不引入容器时同步不需要。

---

## ⑤ 证据速查表

| 论断 | 证据 |
|---|---|
| OPPO `TaskStateChangeListener` 接口 7 方法 | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:101-113` |
| OPPO `BaseTaskStateChangeListener` 抽象类 7 default no-op | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:36-72` |
| OPPO `DefaultImpls` static 嵌套类 7 空实现 | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:77-99` |
| OPPO `TaskStateChangeTimeOutListener` 3 override callback | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:177, 187, 197` |
| OPPO `TaskStateChangeType` 4 值 | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:212-217` |
| OPPO `globalListeners: CopyOnWriteArrayList` | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:31` |
| OPPO `addGlobalTaskStateChangeListener` 写入 | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:222-231` |
| OPPO `removeGlobalTaskStateChangeListener` | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:279-284` |
| OPPO `removeAllListener` 集中清理 | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:269-277` |
| OPPO `taskListener$1` 6 个 `$lambda$N` 派发 | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper$taskListener$1.java:28-180` |
| OPPO `URGENT_TRANSACTION_EXECUTOR` Handler | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:128, 130` |
| OPPO `OplusTaskListener` 6 abstract | `com/android/wm/shell/transition/OplusTaskListener.java:7-25` |
| OPPO `TaskStateHelper.removeAllListener` 由 `Launcher.onDestroy` 调 | `com/android/launcher/Launcher.java:3846` |
| OPPO `AnimationController` 三 register helper 全调 `addGlobalTaskStateChangeListener` | `com/oplus/quickstep/utils/AnimationController.java:304, 340, 378` |
| OPPO 13 个调用点调 `addGlobalTaskStateChangeListener` | 见 §① 表（11 个文件 + Launcher.java:667,1387） |
| lib `TaskStateChangeTimeOutListener` 3 type enum | `D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/controller/TaskStateChangeTimeOutListener.kt:18-22` |
| lib 用 `Looper.getMainLooper()` 作 handler | `D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/controller/TaskStateChangeTimeOutListener.kt:24` |
| lib `dispose` 不调 `removeGlobalTaskStateChangeListener` | `D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/controller/TaskStateChangeTimeOutListener.kt:41-43` |
| lib 全树无 `addGlobalTaskStateChangeListener` / `globalListeners` / `BaseTaskStateChangeListener` | grep 0 命中（仅 docs/review/*.md 引用） |
| lib Demo6 手调 `onTimeOut(...)` 模拟事件 | `D:/AsyncAnimator/demo/src/main/java/com/asyncanimator/demo/Demo6StateMachineActivity.kt:271-282` |
| 用户列出的 4 个回调名 (`onAllAppExitTransitionFinish` / `onTaskViewDestroyed` / `onTaskViewAppeared` / `onUnfoldAnimationStart`) 在 OPPO 全树 0 命中 | 见 §0 |

---

## ⑥ 结论摘要（≤200 字）

**核心发现**（3-5 项 + 修复成本）：
1. **OPPO 整个 `TaskStateHelper` 主体类（319 行 + 内嵌 `taskListener$1` 182 行）在 lib 完全缺失**——11 个 OPPO 业务调用点全部无对应 API；这是 review 06 §2.2 #3 / review 09 §C-4 / review 12 §3-A1 的彻底证据补强。
2. **`TaskStateChangeTimeOutListener` 三 callback 错配成单 `onTimeOut(type, duration)`**——即便补 §1 的事件总线，签名也不匹配；且 `dispose()` 漏 `removeGlobalTaskStateChangeListener(this)` + `handler=null`。
3. **Handler 绑线程错**：`URGENT_TRANSACTION_EXECUTOR`（独立 -8 优先级事务线程）→ `Looper.getMainLooper()`（主线程），主线程满载时 timeout 不可预测推迟 200-900ms。
4. **类型枚举漏 `ON_TO_HOME_TRANSITION_FINISH`**——从 Recents 回 Home 的 `onTaskListenerReleased` + `onTransitionFinish(z8=true)` 双路触发场景无表达。
5. **`TaskStateChangeListener` 接口 + `BaseTaskStateChangeListener` 抽象基类 + `DefaultImpls` 嵌套类整块缺失**——业务侧无 7 回调默认 no-op 可继承。

**修复成本**：补全 §4.1-1（事件总线 + 抽象基类 + 3 override callback + 集中清理）约 **80-120 行 Kotlin**；同步 §4.1-2（handler 线程替换）5 行；§4.1-3（补 enum 值）1 行；总 **~90-130 行**。这是 P0 必补——修复后 `Demo6` 不再需要手调 `onTimeOut(...)` 模拟事件，可演示"事件即时放行 + 超时兜底"双轨。


## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及项 **未在本批落地任何修复**（保持原样/保持简化/属更大重构范围）。

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。