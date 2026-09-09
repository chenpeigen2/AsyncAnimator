# Review 12：Bug 级语义差异与运行时风险

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/`（Kotlin 重实现，11 个 demo 见 `D:/AsyncAnimator/demo/`）
> - **原厂**：`D:/oppo_a6_launcher/sources`（ColorOS 15 Launcher 15.8.24，JADX 反编译；`com.android.launcher3` / `com.android.quickstep` / `com.oplus.quickstep` / `com.oplus.basecommon` 四大包为主）
>
> 取证方法：lib 侧 `dump.py` 直读明文（原磁盘态亦为密文）；原厂经 Grep（ripgrep 明文通道）穿透 DLP 加密取行号。
>
> **背景**：本 review 是对 review 01-04 各自点名的"高严重度风险点"做一次**整合 + 跨文件交叉复核 + 标注剩余 bug 级硬伤**。review 01-04 已修但 lib 仍残留、或新发现的运行时风险点集中列在 §3。

---

## ① 类对应关系表

| lib 类/字段 | 原厂类/字段 | 关键证据 |
|---|---|---|
| `AnimationControlThread.kt:78` `PRIORITY = -19` | `OplusExecutors.java:95` `createAndStartNewLooper("launcher.anim", -19, …)` | lib 修复后对齐原厂字面量（见 §2-A1） |
| `AnimationControlThread.kt:55` `THREAD_NAME = "launcher.anim"` | `OplusExecutors.java:95` 线程名 | 1:1 |
| `AnimationControlThread.kt:60-69` `onLooperPrepared`：装 HandlerTickScheduler + `Process.setThreadPriority` 兜底 | `OplusExecutors.java:169-171`：`AnimationHandler.getInstance().setProvider(new SfVsyncFrameCallbackProvider())` + `LauncherBooster.getCpu().setUxThreadValue(Process.myTid())` | lib 退化为 postDelayed 帧源 + 兜底优先级（见 §2-B1/B2） |
| `Executors.kt:ANIM_CONTROL_EXECUTOR` 绑 `AnimationControlThread.instance.looper` 的 Handler | `OplusExecutors.ANIM_EXECUTOR`（`OplusExecutors.java:95`） | 形状等价；原厂 init lambda 未复刻 |
| `core/anim/AnimationHandler.kt:101-110` `TickSchedulerHolder` 懒构造 + `swapScheduler` | 框架 `android.animation.AnimationHandler`（@hide）+ `setProvider(...)`（`OplusExecutors.java:170`） | 单 API 接口（`replaceThreadScheduler`）覆盖替换，但隐藏原厂 `setProvider` 路径 |
| `core/scheduler/ScheduledTickScheduler.kt:78-81` `tick` 末 `if (callbacks.isEmpty()) stop()` | vendored `androidx.core.animation.AnimationHandler.java:197-202` "无 callback 即停帧" | lib 修复对齐（见 §2-A2） |
| `core/scheduler/ScheduledTickScheduler.kt:26` 守护线程名 `"AsyncAnimator-Tick"`（非 start 线程） | 框架 `AnimationHandler.getInstance()` 是 ThreadLocal，每个 start 线程各自的 AnimationHandler 在该线程 tick | lib 默认 scheduler **不满足 per-thread 帧语义**（见 §2-B3 / §3-C） |
| `launcher/animthread/HandlerTickScheduler.kt:55-83` `Handler(looper).postDelayed` 自走帧循环 | `FrameCallbackProvider14` 退化路径 + 真机走 `FrameCallbackProvider16`/`SfVsyncFrameCallbackProvider` | lib 替代正确但精度差一档（见 §2-B1 / §3-D） |
| `launcher/async/AsyncValueAnimator.kt:48-53` `marshal { ... }` 判线程 + execute | `AsyncValueAnimator.java:116-127, 130-141, 153-164` `looper.isCurrentThread() ? super.xxx() : executor.execute(...)` | 1:1 |
| `async/AsyncAnimCallbacks.kt:33-37, 49-52` `addListener` 去重 / `removeListener` 置 null 槽 | `AsyncAnimCallbacks.java:99-105, 147-151` | 1:1 |
| `async/AsyncAnimCallbacks.kt:62-66` `runOnMainThread` → `LooperExecutor.postAsync` (`setAsynchronous(true)`) | `AsyncAnimCallbacks.java:120, 160` → `Utilities.postAsyncCallback` → `Utilities.java:631-637` `setAsynchronous(true)` | 1:1（已修复 review 01 §②C-3） |
| `async/AsyncAnimCallbacks.kt:69-80` `onAnimActualEnd` 只派给 `ActualEndAnimListener` | `AsyncAnimCallbacks.java:34-43, 111-122` | 1:1（已修复 review 01 §②C-2） |
| `async/ActualEndAnimListener.kt:14-16` 双轨时序注释 | `ActualEndAnimListener.java:10` 空钩子 + `CustomRectFSpringAnim.java:423-441` `maybeEnd` | lib 补全文档注释 |
| `async/AsyncSpringAnim.kt:22-35` `dispatch` → `runOnAnimThread { ... }`（条件式） | `OplusAsyncSpringAnimWrapper.java:69-78, 91-100` 同模式 | 1:1 |
| `controller/AnimationController.kt:64-72` `addRecentsAnim` 转 NONE/OPEN/REVERSE_OPEN/WAITING→CLOSE， MULTI_OPEN/MULTI_WAITING/MULTI_REVERSE_OPEN→MULTI_CLOSE， else→UNKNOWN | `AnimationController.java:471-499` `WhenMappings.$EnumSwitchMapping$0` case 1/3/8/9→CLOSE， case 2/6/7→MULTI_CLOSE， default→UNKNOWN（log "Error animation state"） | **1:1 ✓**（已修复 review 03 §3-a，详见 §2-A3） |
| `controller/AnimationController.kt:84-100` `appLaunchAnimStartOrEnd` 含 end 分支（清 list + checkAllAnimationFinished 或转 WAITING/MULTI_WAITING） | `AnimationController.java:512-555` 端：含 `mHandler.removeMessages/sendEmptyMessage(101)`、清 list、转 WAITING/MULTI_WAITING | lib 修了状态机分支但**漏 `MESSAGE_RELEASE_TOUCH(101)` + 600ms 闸门**（§2-C1） |
| `controller/AnimationController.kt:172-198` `delayStartActivityIfNeed` 改为 `if/else if/else if` 互斥结构 + 最终清理段 | `AnimationController.java:597-669` 互斥三层 + 清理段 | 形状等价，**漏 `!isTablet()`、`isSpecialAppScene(intent)`、`isAppSwipeToRecentContinuationRunning()`**（§2-C2、§3-F） |
| `controller/TaskStateChangeTimeOutListener.kt:26-32, 41-44` 构造 postDelayed 到 **MainLooper Handler** | `TaskStateHelper.java:124-137` postDelayed 到 **`URGENT_TRANSACTION_EXECUTOR` Handler** | **线程选错**（§2-C3、§3-G） |
| `controller/TaskStateChangeTimeOutListener.kt:36-38` `dispose()` 仅 `handler.removeCallbacks` | `TaskStateHelper.java:140-143` `dispose()` = `TaskStateHelper.removeGlobalTaskStateChangeListener(this)` + removeCallbacks + `this.handler = null` | **漏全局事件总线反注册**（§2-C3、§3-G） |
| `seq/AnimSeqTimeStamp.kt:23-25` `clock: () -> Long = { SystemClock.uptimeMillis() }`（可注入） | `AnimSeqTimeStamp.java:25,37,49,61,112,122,132,142` 全部 `SystemClock.uptimeMillis()` | 1:1（已修复 review 03 §3-c） |
| `controller/AnimationController.kt:139, 153, 178, 186, 193` 全用 `SystemClock.uptimeMillis()` | `AnimationController.java:298, 334, 609, 617-618` 全用 `SystemClock.uptimeMillis()` | 1:1（已修复） |
| `seq/AnimationSeqHelper.kt:43-49` `addSeqId` 单调 `++seqId` | `AnimationSeqHelper.java` `updateSeqId` 单调 `++seqId` | 1:1 |
| `seq/AnimationSeqHelper.kt:74-82` `updateNextFinishSeqIdIfNeed` 无条件覆盖 | `AnimationSeqHelper.java:123-127` 仅当 pair 为空或 controller 变更才覆盖 | review 03 §3-e 仍未修复（保留 review 标注） |
| `continuation/RecordInputInterpolator.kt:13` `inputed = 0f` | `RecordInputInterpolator.java:10` 字段默认 `0f`（Java 默认值） | **1:1 ✓**（已修复 review 04 §2.3-1） |
| `continuation/OplusValueAnimator.kt:46-48` `setInterpolator` 双写 `param.interpolator` | `OplusValueAnimator.java:292-298` 双写 `param.interpolator` | **1:1 ✓**（已修复 review 04 §2.3-5） |
| `continuation/OplusValueAnimator.kt:140` `anim.param.copy()` 入新 anim | `OplusValueAnimator.java:105` `anim.param.INSTANCE.copy(...)` | **1:1 ✓**（已修复 review 04 §2.3-2） |
| `continuation/OplusValueAnimator.kt:148` `timeController.setInterpolator(LinearInterpolator())` + `setTarget(newAnim)` + `setProperty(CURRENT_FRACTION)` | `OplusValueAnimator.java:104, 109-117` `setTarget(newAnim)` + `setProperty(CURRENT_FRACTION)` + `setInterpolator(LinearInterpolator())` | **1:1 ✓**（已修复 review 04 §2.3-3/4） |

---

## ② 保真度评估

### A. 已修复（lib 相对 review 01-04 期间的状态已对齐原厂）

| # | 修复点 | 现状证据 | 对应 review 编号 |
|---|---|---|---|
| 1 | 线程优先级 -8 → **-19** | `AnimationControlThread.kt:78` `PRIORITY = -19`（注释自承"曾是 bug"） | review 01 §②C-1 |
| 2 | `ScheduledTickScheduler` 常驻空转 → 末帧停 | `ScheduledTickScheduler.kt:81-83` `if (callbacks.isEmpty()) stop()` + `HandlerTickScheduler.kt:74-78` 同款 | review 04 §2.3-7 |
| 3 | `addRecentsAnim` 转移表 UNKNOWN/MULTI_WAITING 偏差 | `AnimationController.kt:64-72` 三分支精确对齐 `WhenMappings.$EnumSwitchMapping$0` case 1/3/8/9→CLOSE，case 2/6/7→MULTI_CLOSE，else→UNKNOWN | review 03 §3-a |
| 4 | 时钟域 `currentTimeMillis` → `uptimeMillis` | `AnimationController.kt:139,153,178,186,193` + `AnimSeqTimeStamp.kt:25` 全部 `SystemClock.uptimeMillis()` | review 03 §3-c |
| 5 | `appLaunchAnimStartOrEnd` end 分支缺失 | `AnimationController.kt:88-100` 现含清 list + `checkAllAnimationFinished` + 转 WAITING/MULTI_WAITING | review 03 §2.3 |
| 6 | `RecordInputInterpolator.inputed` 初值 -1f → **0f** | `RecordInputInterpolator.kt:13` `var inputed = 0f` | review 04 §2.3-1 |
| 7 | 续行动画 param 共享 → **copy** | `OplusValueAnimator.kt:140` `anim.param.copy()` | review 04 §2.3-2 |
| 8 | timeController `setTarget`/`setProperty` 空实现 → **真实接线** | `OplusValueAnimator.kt:122-124` `setTarget` 持有 target + `addUpdateListener { target?.setCurrentFraction(it) }` | review 04 §2.3-3 |
| 9 | 续行 timeController 缺 `LinearInterpolator` | `OplusValueAnimator.kt:148` `timeController.setInterpolator(LinearInterpolator())` | review 04 §2.3-4 |
| 10 | `setInterpolator` 未 override 双写 param | `OplusValueAnimator.kt:46-48` override 并双写 | review 04 §2.3-5 |
| 11 | listener 派发同步消息 → **async** | `AsyncAnimCallbacks.kt:62-66` `exec.postAsync(action)` ≡ `Message.obtain().setAsynchronous(true)` | review 01 §②C-3 |
| 12 | 缺 `onAnimActualEnd` 双轨结束 | `AsyncAnimCallbacks.kt:69-80` + `ActualEndAnimListener.kt:14-16` | review 01 §②C-2 |
| 13 | listener 派发无快照（潜在 CME） | `AsyncAnimCallbacks.kt:75-80` `getListeners()` = `removeAll null` + `filterNotNull` | review 01 §②C-4 |
| 14 | `delayStartActivityIfNeed` 三层穿透 | `AnimationController.kt:172-198` `if/else if/else if` 互斥 + 清理段 | review 03 §3-b（结构层） |
| 15 | 超时 listener 无兜底定时器 | `TaskStateChangeTimeOutListener.kt:30-32` `handler?.postDelayed(timeOutOption, duration)` | review 03 §3-d（机制层） |

### B. 有意简化（lib 注释/文档中明示，且属于合理设计取舍）

| # | 简化点 | 原厂对应 | lib 取舍 |
|---|---|---|---|
| 1 | `SfVsyncFrameCallbackProvider` → `HandlerTickScheduler` | `OplusExecutors.java:169-171` + `Choreographer.getSfInstance()` 等价路径 | 框架 @hide API；lib 用 `Handler(looper).postDelayed` 替代，**真机 trace 已证明 SF-vsync 在该 MTK 设备上不生效**（`animation-trace-validation.md` §5）——降级为可选，注释明示 |
| 2 | `LauncherBooster.setUxThreadValue`（UIFirst 私有 API） | `OplusExecutors.java:171` | 退化为 `Process.setThreadPriority(myTid(), -19)` 兜底（`AnimationControlThread.kt:64-69`） |
| 3 | `ScheduledTickScheduler` 守护线程（非 start 线程 tick） | 框架 `AnimationHandler` 是 ThreadLocal，每线程一份 | lib 用独立 JVM `ScheduledExecutorService`（`ScheduledTickScheduler.kt:25-32`），**与 per-thread 帧语义不等价**——但 `HandlerTickScheduler` 路径满足；demo 通过显式 install 保证动画线程 tick 在自己线程 |
| 4 | `OplusLooperExecutor` 四扩展（executeAtFront/WithUx/BlockWait/Delay） | `OplusLooperExecutor.java:16-104` | `executeBlockWait` 本身是 v4 §9.3 点名 ANR 风险；其余依赖 LauncherBooster |
| 5 | `sf-vsync vs app-vsync` 帧相位 | `SfVsyncFrameCallbackProvider` 在 launcher.anim 装 SF-vsync（`OplusExecutors.java:170`） | lib **完全不用 SF-vsync**——`Handler.postDelayed` 是 wall-clock 自走，无 vsync 对齐；真机 trace 证实相位差异在该 MTK 设备**实测未体现**（trace 上两线程同样对齐 VSYNC-app，`animation-trace-validation.md` §5）——降级为可选 |
| 6 | `removeScaleAnimatorForGridRecentViews` 等 OPPO 业务定制 | `AnimatorPlaybackController.java:322-340` | review 02 §2.2 已说明，保持裁剪 |
| 7 | 9 个 `Executors` 配套 executor | `Executors.java:55-99` 14 个事务/加载线程 | 与动画线程演示主题无关 |

### C. 遗漏（lib 中没有、且影响运行时语义——**重点是 bug 级**）

| # | 遗漏点 | 原厂证据 | 影响 |
|---|---|---|---|
| 1 | **`appLaunchAnimStartOrEnd` 缺 `MESSAGE_RELEASE_TOUCH(101)` + 600ms 闸门** | `AnimationController.java:513-514, 540-545, 271-281, 685-687`：`end` 时 `mHandler.removeMessages(101); sendEmptyMessage(101)`，`start` 时 `sendEmptyMessageDelayed(101, 600L)`；`forbidTouch()` 读 `mOpenWindowAnimRunning`（:687） | lib 的 `appLaunchAnimStartOrEnd` 已修 end 分支（`AnimationController.kt:88-100`）但**完全没碰 101 消息**，对应字段 `mOpenWindowAnimRunning` 不存在；`forbidTouch()` 恒 false（`DefaultAnimationController.kt` no-op + lib 未 override）。后果：**手势输入层不知道"打开动画期间禁止上滑"的窗口，库内 demo 无影响，但作为可移植组件的话语义丢了一半** |
| 2 | **`delayStartActivityIfNeed` 第一层缺 `!ScreenUtils.isTablet()` 限定** | `AnimationController.java:620` `boolean z13 = this.mIsLandScapeGesture && !ScreenUtils.isTablet()` | lib `:175` `isLandScapeGesture` 不与 `!isTablet()` 复合。**平板场景（landscape activity 也算横屏手势）会被错误挂起 `startActivityAction`**——原厂意图：仅手机横屏才挂起 |
| 3 | **`delayStartActivityIfNeed` 第二层缺 `isSpecialAppScene(intent)`** | `AnimationController.java:628, 640, 283-290`：`isSpecialAppScene(intent)` → 走 BranchSearchHelper 判定搜索入口场景 | lib `:185` 直接判 `isSplitScreenGesture \|\| call?.invoke() == true`，**搜索入口场景的 startActivity 不会被挂起等待**，原厂该场景需要等 transition finish（防搜索框闪一下再启动 app） |
| 4 | **`delayStartActivityIfNeed` 第三层用 `uptimeMillis < maxTime` 时间窗代替 `isAppSwipeToRecentContinuationRunning()` 运行态** | `AnimationController.java:646-650` `AppSwipeToRecentContinuationHelper.INSTANCE.isAppSwipeToRecentContinuationRunning()` | 时间窗（100ms）与运行态判定**不等价**：续行已结束但仍在窗口内时 lib 仍会挂起；续行未启动但时长到了时 lib 不会挂起。review 03 §3-b 已标注，**仍存在** |
| 5 | **`TaskStateChangeTimeOutListener` 监听器绑到 `URGENT_TRANSACTION_EXECUTOR` Handler** | `TaskStateHelper.java:128` `this.handler = OplusExecutors.INSTANCE.getURGENT_TRANSACTION_EXECUTOR().getHandler()` | lib `:25` `mainLooper()?.let(::Handler)` 用 MainLooper。**超时实际在主线程而非事务专用线程触发**——主线程满载时 timeout 推迟执行；事务线程有独立 -8 优先级，与 transaction 提交并发不抢主线程。后果：**真机主线程满载时原厂的兜底更早发生** |
| 6 | **`TaskStateChangeTimeOutListener.dispose()` 漏 `removeGlobalTaskStateChangeListener(this)`** | `TaskStateHelper.java:140-141`：`dispose()` 同时 `TaskStateHelper.removeGlobalTaskStateChangeListener(this)` + `removeCallbacks` + `handler = null` | 原厂 `TaskStateHelper` 是进程级**全局事件总线**（监听三种 type 事件），每个 listener 在构造时注册到全局表。原厂 dispose 必须摘全局表否则内存泄漏 + GC 时仍会触发回调。lib **完全没有全局事件总线模型**，`dispose()` 只做本地摘消息——意味着 `delayStartActivityIfNeed` 路径上"事件触发"那条路（`onLandScapeSceneExit`/`onTransitionFinish`/`onTaskListenerReleased`）在 lib 里**永远是死路**，只有超时兜底会执行。**这是 bug 级差异**：原厂该机制存在的核心价值是"事件抛来时立刻放行 startActivity"——lib 直接废掉 |
| 7 | **`TaskStateChangeTimeOutListener.onTimeOut` 与原厂三个 callback 语义错配** | `TaskStateHelper.java:160-209` 有三个独立 callback：`onLandScapeSceneExit(boolean)` / `onTransitionFinish(boolean)` / `onTaskListenerReleased()`，分别按 type 触发 | lib 把三个合并成 `onTimeOut(type, duration)`，且**调用方无人调用它**（`AnimationController.delayStartActivityIfNeed` 已注释自承"清理段会 dispose"）。原厂"事件型触发"全无 lib 对应物 |
| 8 | **`AnimationFeatureHelper` int flag 默认值 1/0 而非 -1** | `AnimationFeatureHelper.java:52-60` 所有 int flag 初始化为 -1 表示"RUS 未下发态" | lib `:14-22` 全部初始化为 1/0 生效值。业务侧（如 `isAdaptiveAnimation`）对 -1 应有特殊路径（见原厂 `setInterruptThreshold` 的 -1 → 强制 1.0f 钳制，`AnimationFeatureHelper.java:126-128`），lib 跳过该路径。**下游 flag 语义走错** |
| 9 | **`updateNextFinishSeqIdIfNeed` 无条件覆盖 pair** | `AnimationSeqHelper.java:123-127` 仅当 pair 为空或 controller 变更才更新；同 controller 重复调用 seqId 不变 | lib `AnimationSeqHelper.kt:79-82` 每次 `++seqId` 并覆盖。原 review 03 §3-e 已标注，仍存在。**seqId 单调性语义与原厂不一致**，消费方若依赖"同 controller seqId 稳定"会误判为新一轮 |
| 10 | **`addRecentsAnim` 状态转移无原厂 "Error animation state" 日志** | `AnimationController.java:465-467` `LogUtils.i("AnimationController", "Error animation state: " + this.mAnimState + ", add recents anim.")` | lib `AnimationController.kt:72` else → UNKNOWN 无日志。**静默的状态机异常路径，难以调试** |

---

## ③ 行为差异风险点

按"bug 级严重度"排序（可能造成语义不同、且不易在 demo 中暴露）：

### A. 极严重（语义丢失或语义反转）

1. **（bug）`TaskStateChangeTimeOutListener` 缺全局事件总线 + type-specific callback**（§2-C6/C7）。原厂 `TaskStateHelper` 是全局单例，**`dispose()` 必须 `removeGlobalTaskStateChangeListener(this)`**，否则进程级 listener 列表堆积；同时原厂有 3 个独立 callback（`onLandScapeSceneExit`/`onTransitionFinish`/`onTaskListenerReleased`）按 type 触发，lib 合并成单一 `onTimeOut(type, duration)` 且无人调用。**事件型放行整条路径在 lib 里是死路**——`delayStartActivityIfNeed` 期望"事件来时立即放行"的语义消失，只剩超时兜底。真机表现：转场收尾时手势快速触发 startActivity，lib 会等到 listener 内部的 timeout（1500ms / 100ms / 2500ms）才放行，**至少多等一个超时窗口**。
2. **（bug）`TaskStateChangeTimeOutListener` 监听器绑到主线程而非 `URGENT_TRANSACTION_EXECUTOR`**（§2-C5）。原厂 `TaskStateHelper.java:128` 用 `URGENT_TRANSACTION_EXECUTOR`（-8 优先级，独立线程）；lib `TaskStateChangeTimeOutListener.kt:25` 用 `Looper.getMainLooper()`。**主线程满载时 timeout 推迟**——而且事务线程选择不是随意的：与 transaction 提交并发（避免 transaction 完成前超时触发），主线程被业务任务挤占是常态。原厂测过这个时序，lib 直接换成主线程等于放弃了原厂的隔离设计。
3. **（bug）`delayStartActivityIfNeed` 第三层用时间窗代替运行态**（§2-C4）。`AppSwipeToRecentContinuationHelper.isAppSwipeToRecentContinuationRunning()` 是**运行态查询**（续行动画在跑），lib 用 `uptimeMillis < maxTime`（100ms 内窗口）。两者不等价：① 续行提前结束但仍在窗口内 → lib 错误挂起；② 续行启动延迟超过 100ms → lib 不会挂起。原厂意图："续行期间 startActivity 推迟"——lib 改成"续行 100ms 窗口内推迟"。**可能 race-condition 触发窗口外的 startActivity**。

### B. 严重（语义弱化但仍可能触发）

4. **`delayStartActivityIfNeed` 第一层漏 `!isTablet()`**（§2-C2）。平板 landscape 场景下原厂不挂起，lib 会挂起——**平板用户体验分支行为反转**。D6（StateMachineActivity）若在平板模拟器跑会得到错误轨迹。
5. **`delayStartActivityIfNeed` 第二层漏 `isSpecialAppScene(intent)`**（§2-C3）。搜索入口场景原厂等 transition finish，lib 直接放行——**搜索框可能闪一下再启动 app**。D6/D9 模拟时若用搜索入口 intent 会触发。
6. **`appLaunchAnimStartOrEnd` 缺 600ms `MESSAGE_RELEASE_TOUCH` 闸门**（§2-C1）。原厂 `forbidTouch()` 通过 `mOpenWindowAnimRunning` 读这个状态——动画期间禁止输入。lib 完全没有 `forbidTouch()` 的 override（`DefaultAnimationController.forbidTouch` 走 no-op，`DefaultAnimationController.kt:33-49`）。**作为可移植组件时该保护缺失，但库内 demo 因手势链路也是 stub 不触发**。
7. **`AnimationFeatureHelper` 默认值 1/0 而非 -1**（§2-C8）。`-1` 是 RUS 未下发态，1/0 是已下发态。lib 把"未配置"和"配置为 1/0"混在一起，**消费方按 -1 走独立分支的代码路径全失效**。`setInterruptThreshold` 在 isAdaptiveAnimation 时的 -1 → 1.0f 钳制（`AnimationFeatureHelper.java:126-128`）也无对应。

### C. 中等（行为差异但有边界）

8. **`ScheduledTickScheduler` 不满足 per-thread 帧语义**（§2-B3）。v4 §8.1 的核心要求是"动画在哪个线程 start，帧回调就在哪个线程 tick"。lib 默认 `ScheduledTickScheduler` 跑在守护线程 `AsyncAnimator-Tick`，与 start 线程无关；只有显式 `installThreadScheduler(HandlerTickScheduler)` 的路径（`AnimationControlThread.onLooperPrepared`）满足。**任何依赖"帧回调线程 == start 线程"的 demo 断言在默认配置下失败**——D3（AsyncCrossThread）若不显式装 scheduler，会观察到帧回调在 `AsyncAnimator-Tick` 而非启动线程。
9. **`sf-vsync vs app-vsync` 帧相位差异**（§2-B5）。原厂 launcher.anim 装 `SfVsyncFrameCallbackProvider`，代码推断帧相位对齐 SurfaceFlinger 合成时刻（`animation-thread-analysis-v4.md` §3 路径 C）；真机 trace 在该 MTK 设备上**实测**两线程都同相位（`animation-trace-validation.md` §5），SF-vsync 在该设备未体现。lib `HandlerTickScheduler` 用 `postDelayed(16ms)` wall-clock 自走，**无 vsync 对齐**——精度差一档（实测帧间隔抖动 ±0.4ms，60/90/120Hz 屏帧率硬编码 16ms 不自适应）。**性能演示可量化对比但不可与原厂互推**。D10 直方图会观察到主线程路径（Choreographer）与 launcher.anim 路径（postDelayed）帧间隔抖动特征不同。

### D. 轻（演示场景下不触发）

10. **`updateNextFinishSeqIdIfNeed` 无条件覆盖**（§2-C9）。D7（SeqIdDedupActivity）若重复调用 `updateNextFinishSeqIdIfNeed(sameController)`，lib 每次 seqId +1，原厂同 controller 不会变。**只影响消费方按"seqId 单调递增代表新轮"做去重的场景**——D7 当前 demo 未做这类断言。
11. **`addRecentsAnim` 状态转移无 "Error animation state" 日志**（§2-C10）。D6 的状态转移断言会观察到 else 分支命中但无日志。**纯可观测性差异**。

---

## ④ 回移建议

### 值得补进 lib 的（性价比高、且直接消除 bug 级硬伤）

1. **`TaskStateChangeTimeOutListener` 三件套重构**（§3-A1/A2，对应 §2-C5/C6/C7）：
   - 改用 `URGENT_TRANSACTION_EXECUTOR` Handler（或 demo 自己造一个独立 HandlerThread handler，命名 `anim-timeout-listener`）；
   - 在 `lib` 内部构造一个 demo 级的"全局事件总线"（`TaskStateHelper` 的简化版：单例 + 三个 type 的 listener 注册表 + 三种事件触发方法）；
   - 把现 `onTimeOut(type, duration)` 改成 `onLandScapeSceneExit/onTransitionFinish/onTaskListenerReleased` 三个独立方法（或保留单 API 但事件来时主动调），并在 `delayStartActivityIfNeed` 的三层里：事件匹配 → 调 listener 触发 option + dispose；超时兜底 → 走 `postDelayed`。这是 review 03 §4.1-7 的延伸，但要把"事件触发"那条路修通而非只留超时。
2. **补 `isSpecialAppScene(intent)` + `isTablet()` + `isAppSwipeToRecentContinuationRunning()` 三条件**（对应 §3-A3/B4/B5）。需要：
   - `isTablet()` → 注入 `DisplayController` 或读 `Configuration.smallestScreenWidthDp >= 600`；
   - `isSpecialAppScene(intent)` → demo 化判定（intent 是否带搜索 schema、是否含 `BranchSearchHelper` 等），保留 stub 接口供真实业务实现注入；
   - `isAppSwipeToRecentContinuationRunning()` → demo 化单例（一个 `@Volatile var isRunning: Boolean` + 内部状态机），`setAppToOverviewContinuationState(true)` 时设 true、动画结束或 100ms timeout 时设 false。这样 `delayStartActivityIfNeed` 才会按"运行态"而非"时间窗"决策。
3. **`AnimationFeatureHelper` int flag 默认值改回 -1**（对应 §3-B7）。零成本；保留"未下发"三态语义，业务侧按 -1 走独立分支的代码路径恢复。
4. **补 `appLaunchAnimStartOrEnd` 的 101 消息闸门**（对应 §3-B6）。补一个 `mOpenWindowAnimRunning` 字段 + 内部 `mHandler` + 101 消息的 `removeMessages/sendEmptyMessage/sendEmptyMessageDelayed(101, 600L)`，并 override `forbidTouch()` 返回 `mOpenWindowAnimRunning`。让组件可移植时输入层能感知"打开动画期间禁止上滑"窗口。
5. **修 `updateNextFinishSeqIdIfNeed` 语义**（对应 §3-D10）：仅当 pair 为空或 controller 变更时更新。对齐原厂 `AnimationSeqHelper.java:123-127`，10 行内。
6. **补 `addRecentsAnim` 的 "Error animation state" 日志**（对应 §3-D11）：1 行，让 demo 状态机异常路径可观测。
7. **`ScheduledTickScheduler` 加 typealias 警告注释**（对应 §3-C8）：在 `ScheduledTickScheduler` 类注释里明确写"本类不满足 v4 §8.1 per-thread 帧语义，仅用于 JVM 单元测试；演示场景请用 `AnimationControlThread` + `HandlerTickScheduler`"——把"默认不安全"变成显式契约。

### 建议保持简化（无运行时语义影响或属于合理裁剪）

1. **`SfVsyncFrameCallbackProvider` 仍不移植**（§2-B1）：框架 @hide API，且真机 trace 证明 SF-vsync 在该 MTK 设备**实测未生效**（`animation-trace-validation.md` §5）——保留 `HandlerTickScheduler` + `TickScheduler` 接口可替换设计是正确取舍。
2. **`LauncherBooster.setUxThreadValue` 不移植**（§2-B2）：纯 OPPO 私有调度增强，无公开等价物；`Process.setThreadPriority(-19)` 兜底已对齐优先级。
3. **`OplusLooperExecutor` 四扩展（特别是 `executeBlockWait`）不移植**（§2-B4）：`executeBlockWait` 是 v4 §9.3 点名 ANR 风险；其余依赖私有 API。
4. **14 个事务/加载 executor 不全量复刻**（§2-B7）：与"动画线程方案"演示主题无关。
5. **`AppSwipeToRecentContinuationHelper` 不引入运行态判定**（§3-A3 提到的运行态查询）——若该 helper 业务复杂，lib 内只做 demo 化单例即可，不复刻真实业务逻辑（review 03 §4.2-3 已有此意）。
6. **9 个 OPPO 私有定制 helper 不复刻**（review 02 §2.2 / review 03 §2.2）：multi-app merge、按键拦截、预启动等 launcher 专属业务，与演示库定位无关。
7. **Choreographer / `SfVsyncFrameCallbackProvider` 不做反射硬挂**（review 04 §4.2-1 已说明）。

---

## 附：剩余风险概览（按修复优先级）

| 优先级 | 项 | bug 级 | 修复成本 |
|---|---|---|---|
| P0 | `TaskStateChangeTimeOutListener` 事件总线 + handler 线程 + 三 callback（§3-A1/A2） | 是 | 中（约 60-100 行：单例总线 + handler 重选 + 三 callback 拆分） |
| P0 | `delayStartActivityIfNeed` 第三层运行态判定（§3-A3） | 是 | 中（依赖 demo 化运行态单例） |
| P1 | `delayStartActivityIfNeed` 第二层 `isSpecialAppScene`（§3-B5） | 否（语义弱化） | 小（约 10 行 + 注入接口） |
| P1 | `delayStartActivityIfNeed` 第一层 `!isTablet()`（§3-B4） | 否（平板反转） | 1 行 |
| P1 | `appLaunchAnimStartOrEnd` 101 消息闸门（§3-B6） | 否（可移植性） | 中（约 30 行） |
| P2 | `AnimationFeatureHelper` 默认值 -1（§3-B7） | 否 | 7 行 |
| P2 | `updateNextFinishSeqIdIfNeed` 语义（§3-D10） | 否 | 5 行 |
| P2 | `addRecentsAnim` else 日志（§3-D11） | 否 | 1 行 |
| P3 | `ScheduledTickScheduler` 注释契约（§3-C8） | 否 | 文档 |

**说明**：
- "P0 bug 级"指"在原厂设计意图中起关键作用、缺失会导致语义消失或反转"的差异；修复后能消除潜在的难以调试的运行时错误。
- "P1 语义弱化"指"原厂有但 lib 没有，行为分支范围缩小但当前 demo 不触发"。
- "P2/P3"为可观测性 / 边界差异，修复成本极低但优先级靠后。
