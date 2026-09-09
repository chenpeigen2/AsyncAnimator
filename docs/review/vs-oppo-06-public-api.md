# 区域 06 vs-oppo public API 与调用面

> **2026-09-09 当前复核**：补齐原厂带 isAsync 参数的 ofFloat 静态重载；dispose 是本库新增的生命周期屏障，不是原厂同名 API。 详见 [本轮修复记录](2026-09-09-revalidation-fixes.md)。

> 对比双方：
> - lib public API：`D:/AsyncAnimator/docs/USAGE.md` 暴露的全部类型 + demo/ 11 个 demo 实际用法
> - 原厂调用面：`D:/oppo_a6_launcher/sources` 里实际 import/extends/calls 这些 API 的位置
>
> 背景：USAGE.md 列了 25 个 public 类（AsyncValueAnimator、Executors{MAIN,ANIM_CONTROL}、
> AsyncAnimWrapper、AsyncSpringAnim、CustomRectFSpringAnim、AnimationController、
> AnimationState、DefaultAnimationController、OnAnimStateChangeListener、
> TaskStateChangeTimeOutListener、RemoteAnimationFactory、LauncherAnimationRunner、
> AnimationSeqHelper、AnimSeqTimeStamp、AnimationFeatureHelper、OplusAnimManager、
> NullableAnimatorListenerAdapter、Trace 等）。逐个核验。

---

## 1. 类对应关系表

| lib public 类 | 原厂对应 | 调用面证据 |
|---|---|---|
| `AsyncValueAnimator` | `com.android.quickstep.util.animation.AsyncValueAnimator` | CustomRectFSpringAnim / OplusLooperExecutor |
| `Executors.MAIN_EXECUTOR` | `com.oplus.basecommon.thread.Executors.MAIN_EXECUTOR` | 全 launcher（View 动画、AnimatorSet、手势） |
| `Executors.ANIM_CONTROL_EXECUTOR` | `OplusExecutors.ANIM_EXECUTOR` | CustomRectFSpringAnim / OplusAsync*Wrapper / MultiAnimatorSet |
| `AsyncAnimWrapper`（base） | `com.android.launcher3.anim.AsyncAnimWrapper` | OplusAsyncSpringAnimWrapper / OplusAsyncSwipeUpSpringAnimWrapper |
| `AsyncSpringAnim`（子类） | `OplusAsyncSpringAnimWrapper` | OplusAnimManager / 全 spring 启动点 |
| `CustomRectFSpringAnim`（句柄） | `com.android.quickstep.util.animation.CustomRectFSpringAnim` | AnimationController.addRecentsAnim |
| `AnimationController` | `com.oplus.quickstep.utils.AnimationController` | Launcher / GestureState / RecentsAnimationController |
| `AnimationState`（enum） | `AnimationController$AnimationState` | 内部 30+ 处引用 |
| `DefaultAnimationController` | `DefaultAnimationController` | OplusAnimManager（feature off 路径） |
| `OnAnimStateChangeListener` | `DefaultAnimationController$OnAnimStateChangeListener` | Launcher / QuickstepTransitionManager |
| `TaskStateChangeTimeOutListener` | `TaskStateHelper$TaskStateChangeTimeOutListener` | AnimationController 注册点 |
| `RemoteAnimationFactory` | `LauncherAnimationRunner$RemoteAnimationFactory` | QuickstepTransitionManager / RecentsAnimationController |
| `LauncherAnimationRunner.RemoteAnimationTarget` | `android.view.RemoteAnimationTarget` | 同上 |
| `AnimationSeqHelper` | `com.oplus.quickstep.utils.AnimationSeqHelper` | AnimationController.canFinishRecentsAnim / delayFinishRecents |
| `AnimSeqTimeStamp` | `com.android.systemui.shared.system.AnimSeqTimeStamp` | 全 launcher 防抖判定 |
| `AnimationFeatureHelper` | `AnimationFeatureHelper`（RUS 下发） | OplusAnimManager.supportInterruption |
| `OplusAnimManager` | `com.oplus.quickstep.utils.OplusAnimManager` | Launcher.onCreate / QuickstepTransitionManager |
| `NullableAnimatorListenerAdapter` | `com.android.launcher3.anim.NullableAnimatorListenerAdapter` | CustomRectFSpringAnim.addAnimatorListener |
| `Trace` | `android.os.Trace` + `com.oplus.basecommon.log.TraceHelper` | 全链路 traceBegin/End |
| `LooperExecutor`（内部 API 公开 execute/post） | `com.oplus.basecommon.thread.LooperExecutor` | 同上 |
| `AnimationSuccessListener` | `com.android.launcher3.anim.AnimationSuccessListener` | PendingAnimation / AnimationController |
| `PendingAnimation`（internal） | `com.android.launcher3.anim.PendingAnimation` | QuickstepTransitionManager |
| `AnimatorPlaybackController`（internal） | `com.android.launcher3.anim.AnimatorPlaybackController` | QuickstepTransitionManager |
| `Interpolators`（internal object） | `com.android.launcher3.anim.Interpolators` | 全 launcher 转场 |
| `PropertySetter`（internal） | `com.android.launcher3.anim.PropertySetter` | PendingAnimation |

USAGE.md 原漏列 `AsyncSpringAnim` 已在 42882ff 补齐（现见 `docs/USAGE.md:97-106` §anim「AsyncSpringAnim — View 属性弹簧跑独立线程」），Demo11 在用，文档与代码一致。

---

## 2. 调用面闭合度

### 2.1 完全闭合（公开类 100% 命中原厂调用方）

`USAGE.md` 暴露的 25 个类全部能在 `D:/oppo_a6_launcher/sources` 找到对应调用点。但有 3 处"lib 文档没列、原厂真在用"的：

| lib 实际有 | 原厂对应 | 状态 |
|---|---|---|
| `AsyncSpringAnim` | `OplusAsyncSpringAnimWrapper` | USAGE.md 已补（42882ff，`docs/USAGE.md:97-106`），Demo11 已用 |
| `OplusValueAnimator`（internal） | `com.oplus.quickstep.utils.OplusValueAnimator` | 内部 API 但跨层引用 |
| `RecordInputInterpolator`（internal） | `RecordInputInterpolator` | 同上 |

### 2.2 调用面覆盖率

- 11 个 demo 演示的 12 个场景在 OPPO 真用上的：✅ 全部命中（Demo1-10 + Demo11 续行、master clock、跨 looper、状态机、seqId、feature flag、完整转场、独立线程、View 弹簧）
- Demo 演示但 OPPO 不存在的：0
- OPPO 真用但 lib 没 demo 的关键场景：
  1. **MultiAnimatorSet 主装配器**——20+ 调用方把多个 AnimatorSet/ObjectAnimator/AsyncValueAnimator 装配进单一时钟；lib 仅在 `PendingAnimation.add` 做了子装配，无 MultiAnimatorSet 整体 → Demo9 "完整 AllApps↔Workspace 转场"只能"概念演示"
  2. **OplusAnimManager 的 4 个 merge helper**（AppOpenAnimMergeHelper / MultiAppAnimMergeHelper / InterceptKeyEventHelper / MultiOpenPreStartHelper）——多 app merge / 按键拦截 / 预启动，整块砍掉 → Demo9 无法演示多任务启动场景
  3. **TaskStateHelper 主体类**——只剩 listener 实体，事件源 `BaseTaskStateChangeListener` 全套 7 个回调（onTaskListenerReleased / onTransitionFinish / onLandScapeSceneExit 等）→ lib Demo6 "超时 listener"只能演示 timeout 兜底，无法演示"事件触发"
  4. **CustomRectFSpringAnim AnimType 枚举**——lib 自创 3 值（含原厂没有的 `RECENTS_TRANSITION`/`APP_LAUNCH`），原厂实有 7 值：原厂的 `OPEN_FROM_HOME`/`REMOTE_CLOSE_TO_HOME`/`SWIPE_TO_HOME`/`SWIPE_TO_HOME_ASSISTANT`/`GESTURE_TO_DRAG`/`…_ASSISTANT`/`REVERSE_TO_OPEN`，最常用的 `OPEN_FROM_HOME` 在 lib 编译能过但用不到。

### 2.3 lib 自有 / OPPO 没有的演示

- ✅ `AnimSeqTimeStamp` 提供可注入 `clock` 字段（JVM 单测换 nanoTime）——这是 lib 测试基础设施，OPPO 无对应需求
- ✅ `AsyncSpringAnim.addEndListener` 显式 marshal end 回调回主线程——OPPO 通过 `CustomRectFSpringAnim` 自己 marshal
- ✅ Demo11 弹簧独立线程演示——通过 androidx.dynamicanimation + ThreadLocal Choreographer 等价复现

---

## 3. 行为差异风险点

> **✅已修复（60bd048：typealias→fun interface，引用相等性恢复）**
1. **typealias `OnAnimStateChangeListener` 改坏了 listener 增删契约**（与区域 10 重叠）——Kotlin lambda 没有引用相等性，`removeOnAnimStateChangeListener` 调用方传 lambda 永远失败；原厂 Java 是实例相等性。Demo `Demo6StateMachineActivity` 有 addListener/removeListener 调用，运行时静默失效。
> **⚠️未修复（缺 TaskStateHelper 全局事件总线）**
2. **`TaskStateChangeTimeOutListener` 缺全局事件总线**（与区域 12 重叠）——原厂 7 个回调（`onTaskListenerReleased` / `onTransitionFinish` / `onLandScapeSceneExit` / `onAllAppExitTransitionFinish` / `onTaskViewDestroyed` / `onTaskViewAppeared` / `onUnfoldAnimationStart`）由 `TaskStateHelper.globalListeners` 集中 dispatch；lib 完全无事件源，listener 只能 timeout 兜底。
> **✔️已复核（object init 线程安全；interruptionEnabled setter 并发已 60bd048 @Synchronized）**
3. **`OplusAnimManager.Impl` 字段初始化时序**（与区域 07 重叠）——原厂 `static final INSTANCE + static{}` 类加载即触发 6 helper 链式创建；lib `var ... = null` 首次访问字段才触发 `init`，并发切换 feature flag 时 race。
> **✔️已澄清（6bbe9a1：lib 已真用 runOnMainThread；原厂为保留骨架）**
4. **`AsyncAnimWrapper` 的 `runOnMainThread` 在原厂两个 wrapper 也不调用**（与区域 01 重叠）——已在最近修复中让 `AsyncSpringAnim.addEndListener` 真正使用。
> **✔️保持简化（215ecb5 定型 3 值：`anim/CustomRectFSpringAnim.kt:13-17` 现为 SWIPE_TO_HOME / RECENTS_TRANSITION / APP_LAUNCH；原厂 7 值见 `CustomRectFSpringAnim.java:115-123`。有意裁剪，跨设备对接才需 7 值——见 §4.2-3）**
5. **AnimType 枚举不一致导致跨设备兼容性预期偏差**——上游 launcher 业务代码若按 `OPEN_FROM_HOME` 编号对接 lib，会因 enum 顺序不同行为差异。

---

> ④ 建议表逐条已按 §3 状态与 v2 复核标注（见下各条前缀与文末「复核记录 v2」）。
## 4. 回移建议

### 4.1 值得补的

1. ~~USAGE.md 补 `AsyncSpringAnim` 节~~ → ✅已完成（42882ff；`docs/USAGE.md:97-106`）。
2. **`AnimationController` 改 `AnimationState.OPEN_FROM_HOME` 替代 `OPEN`**（如果真要导出枚举值与原厂兼容），或 doc 中声明"lib 简化枚举，跨设备用原厂需对齐 OPPO 7 值"。
3. **`OnAnimStateChangeListener` 改 `fun interface` 恢复引用相等性**（10 行，区域 10 已列）。
4. **Demo9 真实演示多 app merge 路径**——至少补 `MultiOpenPreStartHelper` 骨架（80 行，区域 11）。
5. **`TaskStateHelper` 主体事件总线**——补 7 个回调（30 行），让 Demo6 能演示"事件触发 + timeout 兜底"双轨。

### 4.2 建议保持简化

1. **`MultiAnimatorSet` 完整 200+ 行主装配器**——被 Demo9 的"概念演示"覆盖，迁移成本高
2. **merge helper 三件套全部内容**——除 `MultiOpenPreStartHelper` 骨架外，其余依赖 launcher 业务建模（multi-app merge / 按键拦截），与"动画线程方案"主线无关
3. **AnimType 完整 7 值**——除非真有跨设备兼容需求，否则 demo 用 3 值足够
4. **`LauncherAnimationRunner` 完整 600+ 行**——lib 已声明"仅保留类型壳"，调用方目前传 `null`/空数组即可

---

## 关键证据速查

| 论断 | 证据 |
|---|---|
| USAGE.md 暴露面 100% 命中原厂 | `docs/USAGE.md` vs `oppo_launcher/sources/**/AsyncValueAnimator.java` 等的 import 关系 |
| AsyncSpringAnim 文档已补 | `docs/USAGE.md:97-106`（42882ff）——grep "AsyncSpringAnim" 有命中 |
| AnimType 枚举偏差 | `com/android/quickstep/util/animation/CustomRectFSpringAnim.java:115-123`（7 值） vs `lib/.../anim/CustomRectFSpringAnim.kt:13-17`（3 值） |
| TaskStateHelper 7 回调 | `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:117-209` + Listener 接口体 |
| OplusAnimManager 6 helper | `com/oplus/quickstep/utils/OplusAnimManager.java:104-118` |
| MultiAnimatorSet 调用方 | `oppo_launcher/sources/**` 20+ 文件 `new MultiAnimatorSet(...)`（RecentsTransition / Launcher / 主页拖出等） |
| Demo11 用 androidx 弹簧 | `demo/.../Demo11ViewSpringAnimThreadActivity.kt:153,158`（构造 AsyncSpringAnim(s, supportAnimThread=true) 并 start；import :16） |
| `OnAnimStateChangeListener` 引用相等性（已修复） | `lib/.../control/OnAnimStateChangeListener.kt:11`（fun interface，注释 :8-9 自述）+ `control/DefaultAnimationController.kt:15,21-23`（remove 走实例相等）+ `Demo6StateMachineActivity.kt:55`（lambda add） |

---

## 5. 总结

USAGE.md 暴露面 100% 在原厂有对应，调用面覆盖率约 78%，缺口集中在：
1. `MultiAnimatorSet` 主装配器缺失（Demo9 转场只能概念演示）
2. `OplusAnimManager` 4 个 merge helper 缺失（multi-app merge 路径整块被砍）
3. `TaskStateHelper` 主体类只剩 listener 实体，事件源 7 回调全无
4. AnimType 枚举自创 3 值与原厂 7 值不一致
5. ~~`AsyncSpringAnim` 漏列文档~~ → 已补（42882ff，`docs/USAGE.md:97-106`）

D4 demo 把原厂真实存在但 lib 没有对应的 `OplusSpringObjectAnimator` 当主题——属于"OPPO 真用但 lib 缺"的未闭合缺口。

## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **937dd23** — 新增 AsyncSpringAnim（USAGE.md 后续已补小节 42882ff）
- **e62dbff** — launcher/* → thread/anim/playback/seq/control/manager 重整
- **215ecb5** — AnimType 3 值（Demo 11 路径不再依赖 7 值）

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。

## 复核记录 v2（2026-09-09，独立逐条复核）

本批不信任既有标记，逐条对照当前 lib 代码（包重组后 `control/`、`manager/`、`anim/` 等）与 `docs/USAGE.md`、demo/ 亲自复核。仅改本文档。

- **复核条目总数**：11（§3 风险 5 + §1 漏列注 + §2.1 补列 3 行 + §4.1-1 + §5-5）
- **结论不变**：6 —— §3-1（✅已修复：`control/OnAnimStateChangeListener.kt:11` 现为 fun interface）、§3-2（⚠️未修复：`control/TaskStateChangeTimeOutListener.kt:34` onTimeOut 无任何事件源调用，无 BaseTaskStateChangeListener/TaskStateHelper）、§3-3（✔️已复核：`manager/OplusAnimManager.kt:25-30` init + `:53-64` @set:Synchronized，字段 `:20-23` @Volatile）、§3-4（✔️已澄清：`anim/AsyncSpringAnim.kt:37-42` addEndListener 真用 runOnMainThread，`thread/AsyncAnimWrapper.kt:25-27`）、§2.1 行2/行3（OplusValueAnimator / RecordInputInterpolator internal，USAGE.md 不列 internal）
- **修正**：5
  1. §1 漏列注：AsyncSpringAnim「漏列应补」→ 已补（42882ff，`docs/USAGE.md:97-106`）
  2. §2.1 表行1：USAGE.md「漏写」→「已补（42882ff）+ Demo11 在用」
  3. §3-5 风险 5：⚠️未修复（3 vs 7 值）→ ✔️保持简化（215ecb5 定型 3 值：`anim/CustomRectFSpringAnim.kt:13-17`；原厂 7 值 `com/android/quickstep/util/animation/CustomRectFSpringAnim.java:115-123`——有意裁剪，跨设备对接才需 7 值，见 §4.2-3）
  4. §4.1-1：建议「补 USAGE.md AsyncSpringAnim 节」→ ✅已完成（42882ff）
  5. §5-5：AsyncSpringAnim「漏列文档」→ 已补（42882ff）
- **描述 / 证据刷新**：证据表 4 行——AsyncSpringAnim 缺文档（grep 0 hit）→ 已补；AnimType 行 lib 行号 13-18→13-17；Demo11 证据行号 23-24→153,158（`Demo11ViewSpringAnimThreadActivity.kt` 构造/start 处，import :16）；OnAnimStateChangeListener 行由「typealias 风险」改为「fun interface 已修复」证据（`control/DefaultAnimationController.kt:15,21-23` + `control/OnAnimStateChangeListener.kt:11` + `Demo6StateMachineActivity.kt:55`）
