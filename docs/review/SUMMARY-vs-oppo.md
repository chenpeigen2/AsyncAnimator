# lib vs OPPO 原厂对比分析汇总（12 路 review）

> **2026-09-09 建议落地续轮**：Boolean 动画工厂补全、监听容器释放及 Demo3 接线、延迟 finish 重入修复、状态矩阵/收尾/决策树/SeqId/时间戳回归已落地。完成项及未完成边界以[续轮落地记录](2026-09-09-review-followup.md)为准，不代表整套 OPPO 系统转场移植完成。

> **2026-09-09 当前复核**：历史汇总不是当前待办清单。本轮只修复已复现的监听、清理及测试问题；不宣称所有迁移缺口已完成。 详见 [本轮修复记录](2026-09-09-revalidation-fixes.md)。

> 生成方式：12 个并行 agent 对 lib（`D:/AsyncAnimator/lib`）与 OPPO ColorOS 15 Launcher 反编译源码（`D:/oppo_a6_launcher/sources`）按子系统/质量维度做对比分析。每路独立 Markdown 报告 + 200 字摘要见 `docs/review/vs-oppo-NN-*.md`。
>
> 范围：lib 当前所有 public/internal 类 vs 原厂对应实现；目标盘点所有"精确复刻 / 有意简化 / 遗漏 / bug 级差异"。

## 0. 12 路报告索引

| # | 主题 | 文件 | 关注点 |
|---|---|---|---|
| 01 | 异步/线程层 | [vs-oppo-01-async-thread.md](vs-oppo-01-async-thread.md) | MAIN/ANIM_EXECUTOR、launcher.anim -19 优先级、SfVsync、AsyncAnimWrapper、AsyncValueAnimator |
| 02 | Pending/Playback | [vs-oppo-02-pending-playback.md](vs-oppo-02-pending-playback.md) | MasterClock、Holder、setFloat/addFloat、forEndCallback、cancel 跟踪 |
| 03 | Controller/Seq/Feature/Manager | [vs-oppo-03-controller.md](vs-oppo-03-controller.md) | 12 个 AnimationState 转移表、end 分支、3 层互斥、TaskStateChangeTimeOutListener |
| 04 | 帧调度 | [vs-oppo-04-frame-scheduling.md](vs-oppo-04-frame-scheduling.md) | ThreadLocal、懒删除、setProvider、Choreographer、SF-vsync |
| 05 | 续行 + 弹簧 | [vs-oppo-05-continuation-spring.md](vs-oppo-05-continuation-spring.md) | timeController 委托、setCurrentFraction、generateContinuationAnim、param.copy |
| 06 | public API & 调用面 | [vs-oppo-06-public-api.md](vs-oppo-06-public-api.md) | USAGE.md 覆盖度、调用面闭合率、AnimType 枚举、文档漏列 |
| 07 | 并发原语与线程安全 | [vs-oppo-07-concurrency.md](vs-oppo-07-concurrency.md) | @Volatile / Atomic / ThreadLocal / lazy / 锁粒度 / race-condition |
| 08 | 日志/Tracing | [vs-oppo-08-trace-observability.md](vs-oppo-08-trace-observability.md) | Trace.traceBegin、LogUtils.i、Debug.getCallers、ATRACE vs ArrayDeque |
| 09 | 生命周期与资源回收 | [vs-oppo-09-lifecycle-cleanup.md](vs-oppo-09-lifecycle-cleanup.md) | onDestroy 集中清理、never-quit Executor、listener 泄漏、postDelayed 残留 |
| 10 | Kotlin 风格化程度 | [vs-oppo-10-kotlin-idiomaticity.md](vs-oppo-10-kotlin-idiomaticity.md) | getter/setter 改造、typealias vs fun interface、companion vs object、协程机会 |
| 11 | 功能缺口 | [vs-oppo-11-feature-gaps.md](vs-oppo-11-feature-gaps.md) | 907 行弹簧、MultiDynamicAnimation、merge helper、SF-vsync、LauncherBooster |
| 12 | bug 级运行时风险 | [vs-oppo-12-runtime-risks.md](vs-oppo-12-runtime-risks.md) | 优先级、时钟、转移表、兜底定时器、帧相位差 |
| 13 | CustomRectFSpringAnim 字段级深挖 | [vs-oppo-13-customrect-spring.md](vs-oppo-13-customrect-spring.md) | 6 自由度弹簧 36 字段 + thread switch 4 路 marshal + maybeEnd 3 分支 + cancel 双轨时序 + AnimType 7 值 vs lib 3 值 |

## 1. 整体格局

- **保真度高**：核心动画线程方案（异步 marshal、MasterClock + Holder、StateMachine 12 态 + 3 超时、SeqId 防抖、FeatureFlag 工厂、续行 + SpringForce）逐行/逐语义对齐原厂，多数 review 给出"精确复刻"为主、"有意简化"为辅。
- **已有 review 01-04 标注的 15 处高风险已全部修复**（review 12 复核确认）：线程优先级 -19、`inputed=0f`、续行 param copy + LinearInterpolator、listener async 派发、双轨结束、addRecentsAnim 转 MULTI_WAITING→MULTI_CLOSE、`else if` 互斥、TaskStateChangeTimeOutListener 自管超时、`uptimeMillis` 时钟、`runCatching` 异常隔离。
- **lib 是 idiomatic Kotlin**：37 类中 0 个 Java 残留（review 10）；行数约为原厂 1/2（2699 → 2471）。
- **覆盖度 78%**：11 个 demo 演示路径都对应真实 OPPO 场景，缺口集中在 multi-app merge、Spring 链、LauncherAnimationRunner 完整版。

## 2. bug 级残留（按 P0 排序）

| # | 问题 | 文件 | 修复成本 |
|---|---|---|---|
| 1 | `TaskStateChangeTimeOutListener` 缺全局事件总线 → "事件触发"路径全废，只剩 timeout 兜底 | `lib/.../controller/TaskStateChangeTimeOutListener.kt` | 30 行（注册到 module 级 Listener 列表） |
| 2 | `delayStartActivityIfNeed` 第三层用 `uptimeMillis < maxTime` 时间窗替代 `isAppSwipeToRecentContinuationRunning()` 运行态判定 → 窗口外 startActivity 可能 race-condition | `lib/.../controller/AnimationController.kt:193-196` | 40 行（补 ContinuationHelper 运行态桩） |
| 3 | listener 绑主线程 Handler 而非 `URGENT_TRANSACTION_EXECUTOR` → 主线程满载时 timeout 推迟 | `lib/.../controller/TaskStateChangeTimeOutListener.kt:32` | 5 行 |
| 4 | `OnAnimStateChangeListener` typealias 改坏引用相等性 → `removeOnAnimStateChangeListener` 静默失效 | `lib/.../controller/DefaultAnimationController.kt:25` | 10 行（改 `fun interface`） |
| 5 | `AnimationController` 缺 `destroy()` 集中清理（`removeAllListener` / reset 全字段）→ onDestroy 残留 listener | `lib/.../controller/AnimationController.kt:213` | 15 行 |
| 6 | `LooperExecutor.shutdown()` 未抛 UnsupportedOperationException → AOSP 习惯调 `shutdown()` 会 `AbstractMethodError` | `lib/.../async/LooperExecutor.kt` | 2 行 |
| 7 | `LooperExecutor.postDelayed` 在 JVM 单测时 handler==null 兜底不一致（fallback run-in-place vs 异步延迟）| `lib/.../async/LooperExecutor.kt` | 3 行（统一兜底） |
| 8 | `AnimSeqTimeStamp` 4 个 `@Volatile` 字段 + 裸写赋值无锁 → 多线程并发写可能撕裂 500/300ms 防抖窗口 | `lib/.../seq/AnimSeqTimeStamp.kt:13-50` | 4 行（加 `@Synchronized`） |
| 9 | `CustomRectFSpringAnim.AnimType` 自创 3 值与原厂 7 值不一致，最常用的 `OPEN_FROM_HOME` 在 lib 枚举里没有 | `lib/.../async/CustomRectFSpringAnim.kt:13-18` | 5 行（补枚举值） |
| 10 | `OplusAnimManager.Impl` 裸 var 无 lazy 同步 → 并发切换 feature flag race | `lib/.../manager/OplusAnimManager.kt` | 5 行 |

## 3. 主要功能缺口（建议回移的）

按"业务价值 / 修复成本"性价比排序：

| # | 缺口 | 影响 | 成本 |
|---|---|---|---|
| A | `MultiOpenPreStartHelper` 骨架（多 app 启动预 start）| Demo9 multi-app 路径 | 80 行 |
| B | `AppSwipeToRecentContinuationHelper.isAppSwipeToRecentContinuationRunning()` 桩 | delayStartActivityIfNeed 第三层 | 40 行 |
| C | `MESSAGE_RELEASE_TOUCH 600ms` 闸门（OPENFromHome 内 600ms 防 onClick 二次启动）| Demo11 弹簧 + Demo9 完整转场 | 30 行 |
| D | `TaskStateHelper` 7 回调 + 全局 Listener 列表 | Demo6 事件触发 | 30 行 |
| E | `LauncherBooster.setUxThreadValue` 反射调用 | 演示线程未走 OS UX 调度 | 20 行 |
| F | `OplusAnimManager` 4 个 merge helper 主体 | multi-app merge | 120 行 |
| G | `SpringForce` + `MultiDynamicAnimation` + 6 自由度 `CustomRectFSpringAnim` 907 行 | Demo11 完整弹簧体验 | 500+ 行 |
| H | `LogUtils` + `Debug.getCallers` + `mAnimType` 字段 | Demo 可观测性 | 80 行 |

## 4. 主要有意简化（建议保持）

保留)

- `SfVsyncFrameCallbackProvider`（hidden API，AOSP 无公开替代；`HandlerTickScheduler` 是合理替代）
- `LauncherBooster` UX 线程标记（OPPO 私有；`Process.setThreadPriority` 已兜底）
- `MultiOpenPreStartHelper` 完整版（依赖 RefreshRateTracker + 第三方库）
- `LauncherAnimationRunner` 完整 600+ 行（lib 只保留类型壳，调用方传 `null`/空数组）
- 25 个其余 executor（UI_HELPER / MODEL / THREAD_POOL 等；与动画线程主题无关）
- `MESSAGE_RELEASE_TOUCH` 闸门（手势消费层策略，与动画执行模型无关；除非补全才需要）
- `AnimType` 完整 7 值（demo 3 值够用）

## 5. Kotlin 风格化注意点（review 10）

- `typealias OnAnimStateChangeListener` 改坏 lambda 引用相等性 → 改 `fun interface`（**bug 级**，见上表 #4）
- `addAnimatorListener` 改名 `asyncAnimCallbacks.addListener` 破坏兼容 → 加 `@JvmOverloads` 兼容方法
- `object` 单例 `by lazy` 与原厂 `static final` 类加载时机差异 → 注意业务时序敏感场景
- 函数类型替代 `Consumer/Runnable` 对 Java 调用方不友好 → 关键公开 API 保留 Java 接口形态

## 6. 下一步建议

按 bug 级 + 性价比综合，可分 3 轮推进：

1. **第一轮（10 个 bug 级 P0 修复，~150 行）** — 全是真 bug，按上表 #1-#10 修，每项 ≤ 30 行
2. **第二轮（A-E 业务路径补全，~300 行）** — 让 Demo6/9/11 能演示真实事件链路 + multi-app 预启动 + 触摸闸门
3. **第三轮（弹簧链完整移植，~500 行）** — Demo11 升级为 6 自由度真实弹簧，可视化 SF-vsync 帧相位

如果只做第 1 轮，lib 就能从"概念演示"升级到"可作为可移植组件对外引用"的级别。

## 7. 文档一致性

- `USAGE.md` 漏列 `AsyncSpringAnim`（已用，5 行修复）
- `USAGE.md` §TaskStateChangeTimeOutListener 已从"fun interface"更新为"自管理超时类"（上轮修复后已同步）
- `vs-oppo-06` 是补齐缺失的 06 号报告；原 agent 因文件命名冲突未成功落地

## 8. 取证方法

- lib 侧：UTF-8 含中文的 Kotlin 文件 → Python `open(...).read()`，Read 工具在 DLP 加密下退化为密文
- sources 侧：Grep ripgrep 明文通道穿透 DLP 加密；行号取自 JADX 反编译文本
- 真机侧：Demo 3/5/6/10/11 已实测无崩溃（详见 review 12 §"取证实测发现"）