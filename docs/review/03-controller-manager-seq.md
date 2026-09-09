> **⚠️ 已废弃（superseded）**：本文档是第一轮（4 路）对比的旧版本，已被 [vs-oppo-03-controller.md](vs-oppo-03-controller.md) 取代。内容仅供参考，不要按本文档的结论修改代码。

# Review 03：Controller / Manager / Seq / Feature 层对比

> 对比双方：
> - lib：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/{controller,manager,seq,feature}` + `com/android/launcher3/LauncherAnimationRunner.kt`
> - 原厂：`D:/oppo_a6_launcher/sources`（ColorOS 15 Launcher 15.8.24，JADX 反编译；证据行号为 Grep 穿透 DLP 拿到的明文行号）
>
> 原厂这一层全部位于 `com/oplus/quickstep/utils/`（Kotlin 源码，classes5.dex），不是 `com/android/quickstep/`。`TaskStateChangeTimeOutListener` 不是独立类，是 `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper` 的内部类。

---

## 1. 类对应关系表

| lib 类（文件） | 原厂类（文件：行） | 对应关系 |
|---|---|---|
| `launcher/controller/AnimationController.kt` | `com/oplus/quickstep/utils/AnimationController.java:58`（`extends DefaultAnimationController`） | 精确对应 |
| `launcher/controller/AnimationState.kt` | `AnimationController$AnimationState`（原厂为内部枚举，`AnimationController.java:103-115`） | 12 状态 + 双 boolean 标志逐一对齐；lib 提升为顶层枚举 |
| `launcher/controller/DefaultAnimationController.kt` | `com/oplus/quickstep/utils/DefaultAnimationController.java:30` | 精确对应（no-op 基类） |
| `launcher/controller/OnAnimStateChangeListener.kt`（typealias） | `DefaultAnimationController$OnAnimStateChangeListener`（`DefaultAnimationController.java:34-36`） | 形状等价；lib 第三参用 `Any?`，原厂为 `TaskInfo` |
| `launcher/controller/TaskStateChangeTimeOutListener.kt`（fun interface） | `TaskStateHelper$TaskStateChangeTimeOutListener`（`taskviewremoteanim/TaskStateHelper.java:117-209`） | **仅名字/意图对应，机制完全不同**（见 §2） |
| `launcher/controller/RemoteAnimationFactory.kt`（lib 自定义接口） | `LauncherAnimationRunner$RemoteAnimationFactory`（`com/android/launcher3/LauncherAnimationRunner.java:242-277`） | lib 是重写的 demo 接口，方法集完全不同 |
| `launcher/manager/OplusAnimManager.kt` | `com/oplus/quickstep/utils/OplusAnimManager.java:20` | 对应，但原厂管 6 个 helper，lib 只 2 个 |
| `launcher/seq/AnimSeqTimeStamp.kt` | `com/android/systemui/shared/system/AnimSeqTimeStamp.java:10` | 对应（lib 注释里包路径写对了） |
| `launcher/seq/AnimationSeqHelper.kt` | `com/oplus/quickstep/utils/AnimationSeqHelper.java:17` | 精确对应 |
| `launcher/seq/DefaultAnimationSeqHelper.kt` | `com/oplus/quickstep/utils/DefaultAnimationSeqHelper.java:10` | 精确对应 |
| `launcher/feature/AnimationFeatureHelper.kt` | `com/oplus/quickstep/utils/AnimationFeatureHelper.java:25` | 对应，lib 为本地 setter 模拟 RUS |
| `com/android/launcher3/LauncherAnimationRunner.kt`（20 行 stub） | `com/android/launcher3/LauncherAnimationRunner.java`（600+ 行） | lib 自认"最小移植桩"，仅保留 `RemoteAnimationTarget` 类型壳 |

---

## 2. 保真度评估

### 2.1 精确复刻（行为可对齐）

- **AnimationState 12 状态枚举**：lib `AnimationState.kt:15-26` vs 原厂 `AnimationController.java:104-115`——12 个状态、每状态 `withTaskbarAlignment`/`taskbarAlignmentToLauncher` 两个 boolean 的取值逐一相同（含 SWIPE_UP_TO_CAPSULE/SWIPE_UP_TO_SPLIT_OR_FLOATING 两个 OPPO 新增态）。
- **状态查询 getter 集合**：`isOpeningAnim`（4 态判定，lib `AnimationController.kt:102-106` vs 原厂 `:780-783`）、`isMultiOpen`/`isMultiClose`（`:764-770`）、`isClosingAnimAndAnimClosed`（`:757-760`）、`hasRecentsAnim`（`:740-741`）、`allRecentsAnimationEnd`（`:503-504`）——逐条同构。
- **addRecentsAnim 主状态转移表**：NONE/REVERSE_OPEN/WAITING→CLOSE、OPEN/MULTI_OPEN/MULTI_REVERSE_OPEN→MULTI_CLOSE、其余→UNKNOWN（lib `AnimationController.kt:64-72` vs 原厂 `:482-499`）。*注意两处偏差见 §3-a。*
- **appLaunchAnimStartOrEnd 的 start 分支转移**：NONE→OPEN、CLOSE/MULTI_CLOSE→MULTI_OPEN、其余→UNKNOWN（lib `:88-95` vs 原厂 `:548-555`）。
- **Delay 决策第一层超时判空**：`uptimeMillis > maxTime → return false`（lib `:178` vs 原厂 `:609,617-618`）。
- **AnimationSeqHelper 常量与 KEY**：500/200/300ms、`interrupt.transition.startActivity.seqId`（lib `AnimationSeqHelper.kt:92-98` vs 原厂 `:18-22`）。
- **delayFinishRecents 协议**：可 finish 则同步执行返回 false，否则 clear→存 runnable→`sendEmptyMessageDelayed(MSG, 500 - gap)` 返回 true（lib `:60-72` vs 原厂 `:87-99`）；lib 多了 `maxOf(0, delay)` 防御。
- **DefaultAnimationSeqHelper / DefaultAnimationController 的 no-op 默认值**：canFinishRecent/canInterceptGesture 恒 true、delayFinishRecents 立即执行、`cleanUpRecentsAnim` 恒 true、`canFinishRecentsAnim` 恒 true 等（lib `DefaultAnimationSeqHelper.kt` 全文 vs 原厂 `DefaultAnimationSeqHelper.java:11-42`；lib `DefaultAnimationController.kt:33-49` vs 原厂 `:49-153`）。
- **OplusAnimManager 的 feature 工厂形状**：`supportInterruption() ? new Impl() : new Default*()`（lib `OplusAnimManager.kt:24-29` vs 原厂 `:96-102`）。

### 2.2 有意简化（lib 注释中自认或合理的 demo 化）

- **`supportInterruption()` 恒 true**（lib `OplusAnimManager.kt:36`）：原厂为 `(!isAppTransitionByLightAnim || isAdaptiveAnimation) && ENABLE_SHELL_TRANSITIONS && isSupportBlockableAnimation` 三条件与（原厂 `:233`）。lib 注释已注明。
- **AnimationFeatureHelper 用本地 setter 模拟 RUS 下发**（lib `AnimationFeatureHelper.kt:45-56`）：原厂是 `RusBaseConfigManager.RusConfigChangedListener` 回调 + `updateRusConfig()` 解析 7 个配置项 + 2 个列表（原厂 `AnimationFeatureHelper.java:82-92,159-373`）。同步机制（volatile + synchronized setter）保留了。
- **LauncherAnimationRunner 砍成类型壳**（lib `LauncherAnimationRunner.kt:13-20`）：原厂的 WeakReference factory、三段式 finish、binder→UI 线程 marshal 全部省略，lib 文件头注释已声明。
- **RemoteAnimationFactory 重写为 2 方法 demo 接口**（lib `RemoteAnimationFactory.kt:10-17`）：原厂接口含 `onCreateAnimation(transitionInfo, apps, wallpapers, nonApps, AnimationResult)` + 8 个 default 方法（`getAnimation()`/`appLaunchAnimStartOrEnd`/`handleAnimationMerged`/`isSameIcon`/`onAnimationCancelled`/`preLoadIcon`/`supportInterruption`/`tryFinishOpenRemote`，原厂 `:242-277`）。
- **OnAnimStateChangeListener 改 typealias**、listener 遍历改为快照复制（lib `DefaultAnimationController.kt:26` 用 `ArrayList(...)` 拷贝；原厂 `:158-161` 直接 iterator，遍历中增删会 CME——lib 更安全）。
- **超时 listener 简化为回调 fun interface**（lib `TaskStateChangeTimeOutListener.kt:12-21`）：原厂是自管理对象——构造即向 `URGENT_TRANSACTION_EXECUTOR` 的 Handler `postDelayed` 超时兜底（`TaskStateHelper.java:124-137`）、事件/超时双触发、触发后 `dispose()`（摘全局注册 + removeCallbacks，`:140-154`）。lib 只保留"type 匹配→执行 option"的分发语义。
- **AnimSeqTimeStamp 省掉 Log.d 与 `resetLastRecentFinishTime`/`resetLastRecentStartTime`/`resetLastLaunchTaskTime` 的 public 形态**（lib 收进 `resetAllForTest`，`AnimSeqTimeStamp.kt:44-49`；原厂 `:70-147` 全部是 `@JvmStatic synchronized` public）。

### 2.3 遗漏（lib 中没有、且不一定是有意砍掉）

- **`appLaunchAnimStartOrEnd` 的 end 分支整块缺失**：lib `AnimationController.kt:88` 只处理 `!isEnd`；原厂 `:512-535` 在 end 时做 `mHandler.removeMessages/sendEmptyMessage(101)`、`mAppLaunchAnims.remove(factory)`、空列表时 `checkAllAnimationFinished()` 或按 onceGesture 转 WAITING/MULTI_WAITING。**且 lib 的 start 分支从不执行 `mAppLaunchAnims.add(factory)`**（原厂 `:547`），导致 lib 的 `appLaunchAnims` 恒空、`cleanUpRecentsAnim` 的 `hasOpeningAnim` 恒 false（lib `:79`）——这一链路在 lib 里是死状态。
- **状态机收尾通路 `checkAllAnimationFinished`**（原厂 `:224-231`：双列表空 + merge 状态 → 执行两个 finish callback + `reset()`）及 `executeRemoteMergeFinishCallback`/`executeRecentMainFinishCallback` 的主线程判定 + `UI_HELPER_EXECUTOR` 卸载（`:237-263`）——lib 的两个 callback 字段（`AnimationController.kt:47-48`）**没有任何地方调用它们**。
- **`revertRecentsAnimation`**（CLOSE→REVERSE_OPEN、MULTI_CLOSE→MULTI_REVERSE_OPEN，原厂 `:825-837`）——lib 基类有 no-op 声明但 `AnimationController` 未 override，REVERSE_OPEN/MULTI_REVERSE_OPEN 两个状态在 lib 里实际不可达。
- **`canFinishRecentsAnim`**（appLaunch 非空否决 + seqHelper.canFinishRecent + animationId 匹配，原厂 `:559-580`）——lib 只有基类恒 true。
- **`removeTasks`/`removeTaskOnOpenAnimStart`/`removeTasksOnRealStart`**（原厂 `:408-455,803-808`）、`mRemoveTasksMaps` 在 lib 里是声明了但从未被写入的死字段（lib `:25`）。
- **`MESSAGE_RELEASE_TOUCH(101)` + `RELEASE_TOUCH_DELAY=600ms` 的 openWindowAnimRunning 闸门**（原厂 `:61,63,513-545,271-281` 与 `forbidTouch()` `:685-687`）——lib 的 `forbidTouch()` 只恒 false。
- **`isStartActivityBetweenTransitionEndAndFinish`**：lib `AnimationController.kt:205-206` 只读 flag，但 `isBetweenTransitionEndAndFinish`（`:34`）**从未被赋 true**（`setBetweenTransitionEndAndFinish`/`setBetweenAppExitTransitionEndAndFinish` 没有 override，走基类 no-op）；且原厂语义是 `flag && startActivityRunnable != null`（`:798-799`），lib 少一半条件。该方法在 lib 恒 false。
- **`setOnceGestureProcessing` 的场景判定体**（横屏/分屏/continuation、swipingUpActivityPkg 提取、1500ms listener 注册，原厂 `:898-952`）——lib 只置 flag（`:163-165`）。`setOnAppExit` 同理（原厂 `:881-895` 有 THREE_BUTTONS 导航 + 横屏 + isAdaptiveLowAnimation 三重门槛，lib `:157-161` 无条件置 true）。
- **`OplusAnimManager` 的另外 4 个 helper**：`AppOpenAnimMergeHelper`、`MultiAppAnimMergeHelper`、`InterceptKeyEventHelper`、`MultiOpenPreStartHelper`（原厂 `:104-118`），以及 `matchAnimationId`（`:203-209`）、`tryFinishOpenRemote`（`:236-246`）、`reset`（`:219-225`）、`recreateAnimHelper`（`:211-217`）、`supportInterruption(ItemInfo)`（zoom 小窗/分屏组合排除，`:248-268`）、`ANIM_TAG`/`WINDOW_ANIM_TAG` 常量（`:22,26`）。
- **`AnimationSeqHelper.resetInterceptState()`**（原厂 `:112-114` 调 `AnimSeqTimeStamp.resetLastStartAppTime()`）——lib 基类有 no-op，Impl 未 override。
- **`isInAppToOverviewContinuation`**：原厂 `DefaultAnimationController` 接口面有此方法（`DefaultAnimationController.java` metadata d2 表），lib 两个 controller 都没有。
- **`AnimationFeatureHelper` 的 -1 默认值语义**：原厂所有 int flag 默认 **-1**（未下发态，`AnimationFeatureHelper.java:52-60`），lib 直接给 1/0 生效值（`AnimationFeatureHelper.kt:14-40`）；原厂 `setInterruptThreshold` 内还有 `isAdaptiveAnimation → 强制 1.0f` 钳制（`:126-128`），lib 无。
- **`DefaultAnimationController.forbidTouch()` 非纯 no-op**：原厂默认实现会查 `hasLargeDisplayFeatures && launcher.appTransitionManager.isReverseToOpenAnimRunning()`（`:80-84`），lib 恒 false。

---

## 3. 行为差异风险点（可能导致语义不同的）

**a. addRecentsAnim 转移表两处偏差**（lib `AnimationController.kt:64-72` vs 原厂 `:482-499`）：
- lib 把 `UNKNOWN` 归入 →CLOSE 组，原厂 `UNKNOWN` 落入 default → 保持 UNKNOWN（并打 "Error animation state" 日志，`:465-467`）；
- lib 把 `MULTI_WAITING` 落入 else → UNKNOWN，原厂 MULTI_WAITING → MULTI_CLOSE。
→ 连开/连关打断场景下 lib 与原厂状态轨迹不同。

**b. delayStartActivityIfNeed 三层非互斥**：原厂是 `if / else if / else if`（`:608,627,645`），第一层 listener 存在但条件不满足时**直接穿透到清理段返回 false**；lib 是三个顺序 `if`（`:177,186,193`），第一层不满足会继续试第二、三层并可能返回 true。叠加以下子项后偏差更大：
- 第一层 lib 漏 `!ScreenUtils.isTablet()` 限定（原厂 `:620`）；
- 第二层 lib 漏 `isSpecialAppScene(intent)`（搜索入口场景，原厂 `:628,640`，判定体在 `:283-290`）；
- 第三层语义替换：lib 用"100ms 时间窗内"（`:194`），原厂用 `AppSwipeToRecentContinuationHelper.isAppSwipeToRecentContinuationRunning()` 运行态判定（`:646-650`）——时间窗与运行态不等价；
- 原厂最终落点会 dispose 三个 listener 并清两个 Between 标志（`:653-669`），lib 三层全不命中时什么都不清，listener 与标志残留。

**c. 时钟域不一致**：lib 全部用 `System.currentTimeMillis()`（`AnimationController.kt:134,152,178,194`、`AnimSeqTimeStamp.kt:24` 等），原厂全部用 `SystemClock.uptimeMillis()`（`AnimationController.java:298,334,609`、`AnimSeqTimeStamp.java:25,37,49,61`）。currentTimeMillis 受墙钟回拨/NTP 影响，且休眠时继续走字；uptimeMillis 休眠停走。100/300/500/1500ms 级防抖在待机唤醒后两边结论可能相反。

**d. 超时 listener 的"兜底"语义丢失**：原厂 `TaskStateChangeTimeOutListener` 构造即 `postDelayed(timeOutOption, duration)`（`TaskStateHelper.java:134-137`）——**状态事件永远不来也会超时执行 option**；lib 的 fun interface 只是被动回调，谁触发、何时触发完全依赖调用方。lib 的 `delayStartActivityIfNeed` 挂起的 `startActivityRunnable` 在事件丢失时永远不会被放行（原厂有超时兜底）。

**e. updateNextFinishSeqIdIfNeed 语义不同**：lib `AnimationSeqHelper.kt:79-82` 每次调用无条件 `++seqId` 并覆盖 pair；原厂 `:123-127` 仅当 pair 为空或 controller 变更时才更新（同一 controller 重复调用 seqId 不变）。消费方若依赖"同 controller 的 seqId 稳定"，lib 会误判为新一轮。另外 lib 注释"原厂按引用比较"不准确——原厂是 `Intrinsics.areEqual`（结构相等），只是 `RecentsAnimationController` 未重写 equals 时恰好等价。

**f. 回调线程纪律缺失**：原厂超时 option 执行前先 `checkMainThread()`、非主线程 `MAIN_EXECUTOR.execute` 纠偏（`AnimationController.java:310-319,350-359,384-395`）；finish callback 同理（`:243-247,257-261`）。lib 所有回调在调用方线程直接跑。在"动画线程/binder 线程触发"的场景 lib 会把状态变更带到非主线程。

**g. AnimationFeatureHelper 默认值**：原厂 -1 表示"RUS 未下发"，业务侧可对 -1 走独立分支；lib 初始化为 1/0 直接生效，丢失了"未配置"这一态。

**h. setOnAppExit 无条件置位**（lib `:157-161`）：原厂要同时满足三键导航 + 横屏 + 低档机动画模式（`:885-892`）。lib 在任意场景调用都会进入"横屏退出"判定路径，第一层决策树行为不同。

---

## 4. 回移建议

### 4.1 值得补进 lib 的

1. **`appLaunchAnimStartOrEnd` 的 end 分支 + start 分支的 `mAppLaunchAnims.add(factory)`**（原厂 `:512-555`）。这是状态机闭环的另一半，没有它 WAITING/MULTI_WAITING 不可达、`checkAllAnimationFinished` 类收尾无从谈起。成本小（约 30 行），直接决定 OPEN→WAITING→NONE 的生命周期演示是否成立。
2. **时钟基准统一为单调时钟**。lib 已是 JVM 库，建议把 `System.currentTimeMillis()` 全部换成 `System.nanoTime()` 换算或注入 `Clock`，至少在 `AnimSeqTimeStamp`/`AnimationController` 两处防抖路径上对齐原厂 uptimeMillis 的"单调、休眠停走"语义（§3-c）。这是所有时间窗结论正确性的前提。
3. **addRecentsAnim 转移表修正**：UNKNOWN→UNKNOWN、MULTI_WAITING→MULTI_CLOSE（§3-a）。两行改动消除明确的状态轨迹偏差。
4. **delayStartActivityIfNeed 改回 else-if 互斥结构 + 最终清理段**（dispose listener、清 Between 标志，原厂 `:653-669`）。即使保留时间窗判定，互斥与清理决定了"listener 存在但场景不命中"时的行为（§3-b）。
5. **`setBetweenTransitionEndAndFinish`/`setBetweenAppExitTransitionEndAndFinish` 在 `AnimationController` 中的 override + `isStartActivityBetweenTransitionEndAndFinish` 补上 `&& startActivityRunnable != null`**（原厂 `:798-799,858-867`）。现状是恒 false 的死接口。
6. **`reset()` 补齐字段清理**（callbacks、两个 list、removeTasksMaps、clickAppView，原厂 `:811-822`）。demo 反复进出场次时残留状态会污染下一次。
7. **超时兜底定时器**：给 `TaskStateChangeTimeOutListener` 增加"注册后 duration 到期自动触发"（哪怕用 lib 现有 Handler）。这是原厂该机制存在的核心理由——防 startActivity 永久挂起（§3-d），也是 demo 演示价值最高的部分。

### 4.2 建议保持简化的

1. **merge helper 三件套（AppOpen/MultiApp/InterceptKey/MultiOpenPreStart）**：它们服务于 multi-app merge、预启动、按键拦截等 launcher 专属业务，与"动画线程方案"主线无关，回移会把 lib 拖进 launcher 业务建模。保持 OplusAnimManager 只出 controller + seqHelper 即可。
2. **RUS 远程配置解析体**（`updateRusConfig` 的 7 配置项 + 2 列表解析）：`simulateRemoteUpdate` 已等价覆盖"配置可变"这一演示点。但建议**把 int flag 默认值改回 -1**（零成本），保留"未下发"三态语义（§3-g）。
3. **`isSpecialAppScene`（搜索入口）与 `isTablet()` 形态判定**：依赖 BranchSearchHelper/ScreenUtils 等 launcher 环境，lib 无对应物；在文档中注明这两个条件被裁剪即可。
4. **主线程 marshal（`checkMainThread` + MAIN_EXECUTOR）**：lib 是演示库，回调线程纪律由 lib 自己的 executor 体系（`LooperExecutor`/`AsyncAnimWrapper`，区域 2 已复刻）统一承载更合适，不必照抄这一层的零散判线程。但建议在 `AnimationController` 类注释里显式声明"回调在调用方线程"，避免使用者按原厂语义假设主线程。
5. **`MESSAGE_RELEASE_TOUCH(101)` 600ms 闸门**：它是 `forbidTouch` 的输入防抖，属于手势消费层策略，与动画执行模型无关，保持裁剪。
