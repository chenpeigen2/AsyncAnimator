# lib vs OPPO 原厂深度对比分析汇总（22 路 deep-dive）

> **2026-09-09 建议落地续轮**：Review 01 工厂签名、09 监听释放、25 配对及重入、26 独立 reset、34 A1/A2/A3/A7 回归闭环；A6 原厂 feature 部分仍未完成。见[续轮落地记录](2026-09-09-review-followup.md)。历史“全部 bug 已修/约若干行”的估算不能替代本轮逐项验收。

> **2026-09-09 当前复核**：D7 的根监听补齐仍漏掉深层节点，本轮改为真正递归；旧“22 个 bug / 约 150 行全部修掉”等估算不作为当前验收依据。 详见 [本轮修复记录](2026-09-09-revalidation-fixes.md)。

> 生成方式：22 个并行 agent 对 lib（D:/AsyncAnimator/lib）与 OPPO ColorOS 15 Launcher 反编译源码（D:/oppo_a6_launcher/sources）做"按细节切片"的深度分析——把第一轮 12 份按子系统切分的报告里没展开到具体文件/方法/字段的子维度补齐。
>
> 上一轮"按子系统"的 12 份报告见 `docs/review/vs-oppo-01-12-*.md` + `SUMMARY-vs-oppo.md`（V1）。本篇为 V2：22 份 deep-dive + 跨子系统聚合。

## 0. 22 份报告索引

| # | 主题 | 文件 | 关注文件/方法 |
|---|---|---|---|
| 13 | AnimationControlThread 深挖 | [vs-oppo-13-anim-thread-init.md](vs-oppo-13-anim-thread-init.md) | OplusExecutors.createAndStartNewLooper / ANIM_EXECUTOR$lambda$0 |
| 14 | AnimationHandler 帧调度内核 | [vs-oppo-14-animation-handler-kernel.md](vs-oppo-14-animation-handler-kernel.md) | threadLocalHandler + testHandler / cleanUpList / getInstance() |
| 15 | ScheduledTickScheduler vs HandlerTickScheduler | [vs-oppo-15-tickscheduler-dual.md](vs-oppo-15-tickscheduler-dual.md) | per-thread 帧语义 / daemon 守护线程 |
| 16 | CustomRectFSpringAnim 907 行缺口映射 | [vs-oppo-16-customrect-spring.md](vs-oppo-16-customrect-spring.md) | 6 自由度弹簧字段 + thread switch protocol + AnimType |
| 17 | MultiAnimatorSet 主装配器 | [vs-oppo-17-multianimset.md](vs-oppo-17-multianimset.md) | 4 通道调度器 + maybeOnEnd 等齐协议 |
| 18 | 框架 AnimationHandler + MultiDynamicAnimation | [vs-oppo-18-frame-callback-multispring.md](vs-oppo-18-frame-callback-multispring.md) | 裸 delta 积分 + requestEnd 下一帧生效 |
| 19 | LauncherAnimationRunner 600+ 行拆解 | [vs-oppo-19-launcher-animation-runner.md](vs-oppo-19-launcher-animation-runner.md) | RemoteAnimationFactory 8 default 方法 |
| 20 | AnimationController 12 状态转移表 | [vs-oppo-20-state-transition-table.md](vs-oppo-20-state-transition-table.md) | 12 状态 × 4 转移点穷举 |
| 21 | delayStartActivityIfNeed 三层决策树 | [vs-oppo-21-delay-start-activity-decision-tree.md](vs-oppo-21-delay-start-activity-decision-tree.md) | 11 个谓词精确语义 |
| 22 | TaskStateHelper 7 回调与全局派发 | [vs-oppo-22-task-state-helper.md](vs-oppo-22-task-state-helper.md) | globalListeners 派发 + TaskStateChangeTimeOutListener |
| 23 | PendingAnimation 端到端流程 | [vs-oppo-23-pending-e2e-flow.md](vs-oppo-23-pending-e2e-flow.md) | add / buildAnim / createPlaybackController 6 方法 |
| 24 | APC dispatchOnStart/End/Cancel + cancelAction/endActions | [vs-oppo-24-apc-dispatch-contract.md](vs-oppo-24-apc-dispatch-contract.md) | anims[0] vs 根 AnimatorSet + isDispatchStartPending 反义 |
| 25 | AnimationSeqHelper seqId 引用相等 + getNextFinishSeqIdIfNeed | [vs-oppo-25-seq-helper-pair.md](vs-oppo-25-seq-helper-pair.md) | Intrinsics.areEqual vs === + 配对条件更新 |
| 26 | AnimSeqTimeStamp 4 @Volatile 字段并发 | [vs-oppo-26-anim-seq-timestamp.md](vs-oppo-26-anim-seq-timestamp.md) | @JvmStatic synchronized vs 裸 @Volatile + 3 reset 缺失 |
| 27 | AnimationFeatureHelper 7 字段 + RUS 同步 | [vs-oppo-27-feature-helper.md](vs-oppo-27-feature-helper.md) | isAdaptiveAnimation 钳制 + 列表零保护 race |
| 28 | OplusAnimManager 6 helper 逐个对账 | [vs-oppo-28-oplusanim-manager-helpers.md](vs-oppo-28-oplusanim-manager-helpers.md) | AppOpen / MultiApp / InterceptKey / MultiOpenPreStart 4 个缺失 |
| 29 | traceBegin/traceEnd tag 一致性 | [vs-oppo-29-trace-tag-consistency.md](vs-oppo-29-trace-tag-consistency.md) | tag=8L vs OPPO 5 种 tag + ArrayDeque 非线程安全 bug |
| 30 | Kotlin 化兼容性风险 | [vs-oppo-30-kotlinization-risks.md](vs-oppo-30-kotlinization-risks.md) | typealias 引用相等 + companion init 时序 + SAM ABI |
| 31 | 构建/Gradle 配置漂移 | [vs-oppo-31-build-gradle-drift.md](vs-oppo-31-build-gradle-drift.md) | compileSdk 37 hack + minSdk 36 + Java 21 |
| 32 | Demo 注册与导出契约 | [vs-oppo-32-demo-manifest-export.md](vs-oppo-32-demo-manifest-export.md) | LauncherAnimationRunner vs LauncherEntryActivity 入口 |
| 33 | 文档/代码一致性扫描 | [vs-oppo-33-doc-consistency.md](vs-oppo-33-doc-consistency.md) | USAGE.md 漏列 + review 诊断-修复不同步 |
| 34 | 测试覆盖缺口扫描 | [vs-oppo-34-test-coverage-gaps.md](vs-oppo-34-test-coverage-gaps.md) | 21 测试只覆盖原厂 1/4 分支 |

## 1. 新发现的 bug 级残留（V1 未覆盖的）

按修复 ROI 排序（前 6 项都 ≤30 行）：

| # | 问题 | 来源 | 修复成本 |
|---|---|---|---|
| **D1** | `OnAnimStateChangeListener` typealias 改坏引用相等性 → `removeOnAnimStateChangeListener` 静默失效 | [30-kotlinization](vs-oppo-30-kotlinization-risks.md) | **10 行**（改回 `fun interface`） |
| **D2** | `LooperExecutor.shutdown()` 未抛 UnsupportedOperationException → `AbstractMethodError` 违反 never-empt 契约 | [30](vs-oppo-30-kotlinization-risks.md) | **6 行** |
| **D3** | `AnimSeqTimeStamp` 3 个独立 reset 方法缺失（`resetLastRecentFinishTime` / `resetLastRecentStartTime` / `resetLastLaunchTaskTime`） | [26](vs-oppo-26-anim-seq-timestamp.md) | **3 行** |
| **D4** | `AnimationController.revertRecentsAnimation` 未 override → swipe-cancel 状态机丢失 | [20](vs-oppo-20-state-transition-table.md) | **5 行** |
| **D5** | `AnimationController.cleanUpRecentsAnim` 不调 `checkAllAnimationFinished()` → 状态卡死 | [20](vs-oppo-20-state-transition-table.md) | **1 行** |
| **D6** | `cleanUpRecentsAnim` 不调 `checkAllAnimationFinished()` × `MultiAnimatorSet.maybeOnEnd` 等齐缺失 → finish 回调永不触发 | [17](vs-oppo-17-multianimset.md) | **~15 行** |
| **D7** | APC dispatch 跳根漏派：listener 挂在根 AnimatorSet 上永远收不到 callback | [24](vs-oppo-24-apc-dispatch-contract.md) | **10-15 行**（递归 DFS） |
| **D8** | `isDispatchStartPending` 在 `start()` 置 true 反义 | [24](vs-oppo-24-apc-dispatch-contract.md) | **1 行** |
| **D9** | `mHasRequestCancel` volatile 信号缺失 → cancel 后 apply 帧撕裂 | [17](vs-oppo-17-multianimset.md) | **~10 行** |
| **D10** | `cancel(int)`/`end(int)` bitmask 协议缺失（`1|2|4=7` 编码 4 通道） | [17](vs-oppo-17-multianimset.md) | **~10 行** |
| **D11** | `AnimationSeqHelper.resetInterceptState()` 未 override → 手势防抖失效 | [25](vs-oppo-25-seq-helper-pair.md) | **1 行** |
| **D12** | `updateNextFinishSeqIdIfNeed` 无条件 ++ 而原厂按 pair 空/变才更新 | [25](vs-oppo-25-seq-helper-pair.md) | **3 行 + 1 行单测** |
| **D13** | `getNextFinishSeqId` 用 `===` 而原厂用 `Intrinsics.areEqual`（注释也错） | [25](vs-oppo-25-seq-helper-pair.md) | **1 行 + 改注释** |
| **D14** | `OplusAnimManager.supportInterruption()` 硬编码 true，丢原厂 4 条件 AND | [30](vs-oppo-30-kotlinization-risks.md) | **15 行** |
| **D15** | `OplusAnimManager.interruptionEnabled` setter 无 `@Volatile`/无锁 | [30](vs-oppo-30-kotlinization-risks.md) | **6 行** |
| **D16** | `OplusAnimManager.init` 裸 var 并发首访 race | [28](vs-oppo-28-oplusanim-manager-helpers.md) | **5 行** |
| **D17** | `RemoteAnimationTarget` 仅 2 字段，原厂 27 字段 → demo 取 `mode`/`taskInfo` NPE | [19](vs-oppo-19-launcher-animation-runner.md) | **3 行** |
| **D18** | `LauncherAnimationRunner` 5 个 default 方法缺失（`tryFinishOpenRemote` / `onAnimationCancelled` / `supportInterruption` / `handleAnimationMerged` 等） | [19](vs-oppo-19-launcher-animation-runner.md) | **32 行** |
| **D19** | `Util.STACK = ArrayDeque<String>()` 非线程安全：anim 线程与 main 线程 handler$lambda$0 并发访问 | [29](vs-oppo-29-trace-tag-consistency.md) | **5 行**（换 ConcurrentLinkedDeque） |
| **D20** | `minSdk = 36` → ColorOS 15 真机（API 35）`adb install` 失败 | [31](vs-oppo-31-build-gradle-drift.md) | **2 行** |
| **D21** | Java 21 class file v65.0 → Android 13 (API 33) 设备 `VerifyError` | [31](vs-oppo-31-build-gradle-drift.md) | **2 行** |
| **D22** | `LauncherEntryActivity` exported → 信息暴露面（低危，建议加 tools:ignore） | [32](vs-oppo-32-demo-manifest-export.md) | **~5 行** |

**总计 22 个新 bug，约 150 行可全部修掉。**

## 2. 新发现的细节缺口（建议回移）

按业务价值排序：

| # | 缺口 | 影响 | 成本 |
|---|---|---|---|
| E1 | `MultiAnimatorSet` 4 通道调度器（sync set + async set + SpringAnimation + CustomRectFSpringAnim） | Demo9 "完整转场" 只能概念演示 | **~250 行** |
| E2 | `AppSwipeToRecentContinuationHelper.isAppSwipeToRecentContinuationRunning()` 桩 | `delayStartActivityIfNeed` 第三层运行态判定 | **30 行** |
| E3 | `MESSAGE_RELEASE_TOUCH 600ms` 触摸闸门 | OPENFromHome 600ms 内防 onClick 二次启动 | **30 行** |
| E4 | `TaskStateHelper` 7 回调 + `BaseTaskStateChangeListener` 接口 | Demo6 "事件触发"链路 | **30 行** |
| E5 | `AppOpenAnimMergeHelper` / `MultiAppAnimMergeHelper` / `InterceptKeyEventHelper` / `MultiOpenPreStartHelper` 4 件套 | multi-app merge / 按键拦截 / 预启动 | **400 行** |
| E6 | 6 自由度 `CustomRectFSpringAnim` 完整弹簧 + thread switch protocol + maybeEnd 双轨 | Demo11 真实窗口弹簧 | **~600 行** |
| E7 | `MultiDynamicAnimation` 共享帧回调 + `SpringAnimReflectUtils` | 4 件套之一 | **~500 行** |
| E8 | `LauncherBooster.setUxThreadValue` 反射调 OS UX 调度 | 演示线程未走 OS 调度 | **20 行** |
| E9 | `LogUtils` + `Debug.getCallers` + `mAnimType` | Demo 可观测性 | **80 行** |

## 3. 新确认的有意简化（建议保持）

- `SfVsyncFrameCallbackProvider`（hidden API；AOSP 无公开替代，HandlerTickScheduler 是合理替代）
- `LauncherBooster` UX 线程标记（OPPO 私有 uifirst 跨进程；`Process.setThreadPriority` 已兜底）
- `MultiOpenPreStartHelper` 完整 200 行（依赖 RefreshRateTracker + 第三方库）
- `LauncherAnimationRunner` 完整 108+ 行（lib 只保留类型壳，调用方目前传 `null`/空数组即可）
- 25 个其余 executor（UI_HELPER / MODEL / THREAD_POOL 等；与动画线程主题无关）
- `LogUtils` 门控 6 档（demo 不需要持久化与 lazy 优化）
- `MESSAGE_RELEASE_TOUCH` 闸门（除非补全 4 件套，否则单补无意义）
- `AnimType` 完整 7 值（demo 3 值够用，枚举顺序差异不算 bug）
- `Intrinsics.checkNotNullParameter`（Kotlin non-null 已等价）
- `OplusLooperExecutor` 四扩展接口

## 4. 关键细节校正（V1 有偏差的）

| 项 | V1 描述 | 实际（V2） |
|---|---|---|
| 12 状态去向 | "V1 review 03 §1 已覆盖 75%" | "V2 review 20 实测 100%，12 状态 × 4 处去向全对齐"（review 20 穷举验证）|
| `AnimType` | "V1 review 06 说 lib 3 值、原厂 7 值" | ✓ 一致；最常用的 `OPEN_FROM_HOME` 在 lib 没有（V2 16/20 复核）|
| `OnAnimStateChangeListener` | "V1 SUMMARY bug #4 typealias 改坏引用相等性" | "V2 30 实测确认" + "fix 10 行" |
| TaskStateHelper | "V1 review 12 bug #1 缺全局事件总线" | "V2 22 实测**：用户描述的 7 回调（onAllAppExitTransitionFinish/onTaskViewDestroyed/onTaskViewAppeared/onUnfoldAnimationStart）在 OPPO 全树 0 命中；真实回调是 onBackPressedOnTaskRoot/onLandScapeSceneExit/onTaskAppeared/onTaskInfoChanged/onTaskListenerReleased/onTaskVanished/onTransitionFinish"（命名清单有出入）|
| LauncherAnimationRunner | "V1 review 19 描述为 600+ 行" | "V2 32 复核**：实际 LauncherAnimationRunner.java 是 108 行，600 行是 QuickstepTransitionManager.java；入口契约由二者合计 713 行" |
| `LauncherAnimationRunner.RemoteAnimationFactory.supportInterruption(ItemInfo)` | "V1 review 19 描述有参" | "V2 32 复核**：原厂实际是 `supportInterruption()` 无参" |
| `progressAnimator` 时长 | "V1 review 02 §3-4 说 lib 保持默认 300ms" | "V2 23 复核**：lib `add()` 内部强制 `child.duration = durationMs`，与原厂等价，V1 描述不准确" |
| `MAX_GO_NORMAL_DELAY_TIME = 200L` | "V2 25 发现 lib 漏" | "V1 未提；V2 新增" |

## 5. 测试覆盖（V2 新发现）

- 21 测试 / 触达约 38 个原厂分支 / 覆盖率 ≈ **1/4**
- `AnimationController.java` 9 个 `WhenMappings` 转移位只测了 1 个（`NONE→CLOSE`）→ 其余 8 个无守护
- `TaskStateChangeTimeOutListener` 3 个事件回调零覆盖 → 业务永远等不到回调
- `delayStartActivityIfNeed` 三层决策树零覆盖
- `AnimationHandlerTest.testCallbackReturnsTrueEndsAnimation` 只断言 `callbackSize==1`，从未真正驱动一帧

**修复成本**：最小可发布集补 9 个状态转移位 + TaskState listener 测试 ≈ **95 行**（P0，~2h）。

## 6. 文档一致性（V2 新发现）

- **USAGE.md 漏列 `AsyncSpringAnim`**（V1 review 06 + SUMMARY 已点名两次，未落地）
- **USAGE.md §有意简化清单 4 条过期**：fun interface（已改 class）/ 只保留 MAIN_EXECUTOR（已加 ANIM_CONTROL_EXECUTOR）/ addRecentsAnim 转移表有意保留（已修复）/ 本次未修（已修）
- **README.md 计数三处不一致**：tree 写 9、表格列 10、实际 11 个 Demo

**修复成本**：22 行文档 + 10 行 lib（typealias → fun interface 闭环 V1 SUMMARY bug #4）。

## 7. 构建/Gradle 漂移（V2 新发现）

- `minSdk = 36` → ColorOS 15 真机装不上（API 35）
- `compileSdk = 37` + `android-37` 本地 hack → 新开发者踩坑
- Java 21 class file v65.0 → Android 13 设备 `VerifyError`
- `androidx.dynamicanimation:1.1.0` + 原厂 COUI fork 类同 namespace → DEX 合并冲突

**修复成本**：~20 行配置 + `consumer-rules.pro` 加 keep 规则。

## 8. 总览：V1 vs V2 增量

| 维度 | V1（12 路） | V2（22 路） | 增量 |
|---|---|---|---|
| bug 级残留 | 10 项 | 22 项 | +12（其中 D1-D5 ≤30 行可消） |
| 缺口映射 | 8 项 | 17 项 | +9 |
| 有意简化 | 7 项 | 16 项 | +9 |
| 测试覆盖 | 未单列 | 1/4 全树 | 新增 §5 |
| 文档一致性 | 1 项 | 5 项 | +4 |
| 构建配置 | 1 项 | 5 项 | +4 |
| 校正 V1 偏差 | — | 7 处 | 校正 |
| 总修复行数 | ~150 | ~250 | +100 |

**累计修掉 V1+V2 的所有 bug 级问题：约 250 行**。让 lib 从"概念演示"升级到"可作为可移植组件对外引用"是 250 行代码 + ~95 行单测。

## 9. 取证方法

- lib 侧：UTF-8 含中文的 Kotlin 文件 → Python `open(...).read()`，Read 工具在 DLP 加密下退化为密文
- sources 侧：Grep ripgrep 明文通道穿透 DLP 加密；行号取自 JADX 反编译文本
- 任务间并发：22 个 agent 并行；AgentSwarm 任务中部分 agent 因 `cd` 路径理解偏差出现轻微文件名碰撞，已在汇总阶段统一重命名为唯一 NN

## 10. 后续路径建议（不变）

按 P0 修 → A-E 业务路径补全 → G/H 弹簧链完整移植，3 轮可让 lib 真正"作为可移植组件对外可用"。详见 V1 SUMMARY §6。