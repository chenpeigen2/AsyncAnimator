# 区域 15 对比 Review：AnimationController 12 状态完整转移表

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/controller/`（`AnimationController.kt` + `DefaultAnimationController.kt` + `AnimationState.kt`，Kotlin 重实现）
> - **原厂**：`D:/oppo_a6_launcher/sources/com/oplus/quickstep/utils/AnimationController.java`（≈990 行，Kotlin 反编译 `classes5.dex`，含 `public enum AnimationState` + `extends DefaultAnimationController`）
>
> 取证方法：lib 侧经 Python `open(...).read()` 穿透 DLP 拿明文；sources 侧 80% DLP 加密，原始 `.java` 经 Python UTF-8 解码、行号为 JADX 文本行号。
>
> 本区域专门聚焦 4 个**状态转移入口**（`addRecentsAnim` / `appLaunchAnimStartOrEnd start` / `appLaunchAnimStartOrEnd end` / `revertRecentsAnimation` / `cleanUpRecentsAnim` —— 实际为 5 个 switch 点）对 12 个 `AnimationState` 的去向穷举，及 `cleanUpRecentsAnim` 不调 `checkAllAnimationFinished()` 这一最致命遗漏。背景见 review 03（Controller 层整体）和 review 12 §3-b（回调执行线程）。
>
> 编号：现有 vs-oppo-01-14 已被各专题占用，本报告编号 15。

---

## ① 类对应关系表

| lib 成员（文件:行） | 原厂成员（文件:行） | 关键语义 |
|---|---|---|
| `AnimationState.kt:9-22` 顶层 enum，12 值 | `AnimationController.java:104-116` `public enum AnimationState`（嵌套类） | **形状精确对齐**：12 个状态（NONE/OPEN/REVERSE_OPEN/CLOSE/MULTI_OPEN/MULTI_REVERSE_OPEN/MULTI_CLOSE/WAITING/MULTI_WAITING/UNKNOWN/SWIPE_UP_TO_CAPSULE/SWIPE_UP_TO_SPLIT_OR_FLOATING）逐一对应、`(withTaskbarAlignment, taskbarAlignmentToLauncher)` 双 boolean 取值逐一相同；lib 提升为顶层 enum，原厂嵌套在 `AnimationController` 内 |
| `AnimationController.kt:65-77` `addRecentsAnim` 的 `when (animState)` switch | `AnimationController.java:471-501` `addRecentsAnim` 的 `WhenMappings.$EnumSwitchMapping$0` switch | **9 出口精确对齐**（见 §②-表 A）；原厂还附 6 行副作用（mCurrentAnim=null / updateRunningRemoteTarget / MultiAppAnimMergeHelper.setRecentsAnimEndState / `recentAnim.addAnimatorListener(new AnonymousClass1)` / `mRecentsMainFinishCallback = null`），lib 仅 `recentsAnims.add(anim)` |
| `AnimationController.kt:90-114` `appLaunchAnimStartOrEnd` `isEnd=true` 分支 | `AnimationController.java:513-536` `appLaunchAnimStartOrEnd` `isEnd=true` 分支 | **3 出口精确对齐**（OPEN→WAITING / MULTI_OPEN→MULTI_WAITING / 其它 Unit）；但原厂每次 end 必 `mHandler.sendEmptyMessage(101)` 标 `mOpenWindowAnimRunning=false`，lib 完全没这套 |
| `AnimationController.kt:90-114` `appLaunchAnimStartOrEnd` `isEnd=false`（start）分支 | `AnimationController.java:538-556` `appLaunchAnimStartOrEnd` `isEnd=false` 分支 | **3 出口精确对齐**（NONE→OPEN / CLOSE→MULTI_OPEN / MULTI_CLOSE→MULTI_OPEN / 其它 UNKNOWN）；但原厂 start 必 `mOpenWindowAnimRunning=true` + `mHandler.sendEmptyMessageDelayed(101, 600L)` + `MultiAppAnimMergeHelper.multiAppOpenAnimStart()` + `mAppLaunchAnims.add` 之后才 switch，lib 顺序颠倒（先 add 再 switch），且完全缺 side-effect |
| **未实现**：`AnimationController` 无 `revertRecentsAnimation` override | `AnimationController.java:826-838` `revertRecentsAnimation` 的 switch | **【遗漏 #1 - bug 级】** —— `DefaultAnimationController.kt:64` 仅 `open fun revertRecentsAnimation(anim: CustomRectFSpringAnim) {}`（no-op），`AnimationController` 没 override。原厂此方法负责把 CLOSE→REVERSE_OPEN、MULTI_CLOSE→MULTI_REVERSE_OPEN、其它→UNKNOWN，是 swipe-cancel 的核心状态转移 |
| `AnimationController.kt:82-88` `cleanUpRecentsAnim` | `AnimationController.java:583-596` `cleanUpRecentsAnim` | **返回值 + 内部 clear 精确对齐**（`return !hasOpeningAnim`），但**漏调** `checkAllAnimationFinished()`（见 §③-bug #2） |
| `AnimationController.kt:50-51` 两个 callback 字段（`(() -> Unit)?`） | `AnimationController.java:82-83` `mRecentsMainFinishCallback` + `mRemoteMergeFinishCallback`（`Runnable`） | 字段形状对齐（lib 用 Kotlin lambda，原厂用 Java Runnable）；调用方 `checkAllAnimationFinished`/`reset` 内对应也一致；**唯一差异**：原厂 `mRemoteMergeFinishCallback` 通过 `Executors.UI_HELPER_EXECUTOR` 异步派发，lib 直接同步 `invoke()` |
| `AnimationController.kt:55-57` `private fun updateAnimState` | `AnimationController.java:458-469` `private final void updateAnimState` | 形状对齐：赋值 → 调 `onAnimStateChanged(old, new, runningTask)`；原厂多一行 `mForbidSwipeUpWhileStartingLandApp` 联动 + UNKNOWN 日志 `Log.w` |

---

## ② 保真度评估

### ②-A. 精确复刻（行为可对齐）

#### 表 A-1：`addRecentsAnim` 的 12 状态去向穷举

| 当前状态 (lib `when` / 原厂 `WhenMappings`) | lib 去向 (`AnimationController.kt:67-76`) | 原厂去向 (`AnimationController.java:483-500`) | 对齐 |
|---|---|---|---|
| NONE | CLOSE | CLOSE | ✓ |
| OPEN | CLOSE | CLOSE | ✓ |
| REVERSE_OPEN | CLOSE | CLOSE | ✓ |
| CLOSE | **UNKNOWN** | **UNKNOWN** | ✓ |
| MULTI_OPEN | MULTI_CLOSE | MULTI_CLOSE | ✓ |
| MULTI_REVERSE_OPEN | MULTI_CLOSE | MULTI_CLOSE | ✓ |
| MULTI_CLOSE | **UNKNOWN** | **UNKNOWN** | ✓ |
| WAITING | CLOSE | CLOSE | ✓ |
| MULTI_WAITING | MULTI_CLOSE | MULTI_CLOSE | ✓ |
| UNKNOWN | UNKNOWN | UNKNOWN | ✓ |
| SWIPE_UP_TO_CAPSULE | UNKNOWN | UNKNOWN | ✓ |
| SWIPE_UP_TO_SPLIT_OR_FLOATING | UNKNOWN | UNKNOWN | ✓ |

> 12/12 出口对齐。注意 `SWIPE_UP_TO_*` 两个 OPPO 新状态在**两个实现里都无显式 case**，都是 fall-through（Kotlin `else` / Java `default`）。Grep 全树发现这两个状态只被 `OplusBaseSwipeUpHandler.java:1987, 1998, 8882, 8891` 通过 `taskbarLauncherStateController.onAnimStateChanged(...)` **主动发射**给外部 listener，**本类内永远不当 switch 输入处理**。所以"对齐"是真对齐，不是巧合。

#### 表 A-2：`appLaunchAnimStartOrEnd start` 的 12 状态去向穷举（`isEnd=false`）

| 当前状态 | lib 去向 (kt:107-112) | 原厂去向 (java:549-556) | 对齐 |
|---|---|---|---|
| NONE | OPEN | OPEN | ✓ |
| OPEN | **UNKNOWN** | **UNKNOWN** | ✓ |
| REVERSE_OPEN | **UNKNOWN** | **UNKNOWN** | ✓ |
| CLOSE | MULTI_OPEN | MULTI_OPEN | ✓ |
| MULTI_OPEN | **UNKNOWN** | **UNKNOWN** | ✓ |
| MULTI_REVERSE_OPEN | **UNKNOWN** | **UNKNOWN** | ✓ |
| MULTI_CLOSE | MULTI_OPEN | MULTI_OPEN | ✓ |
| WAITING | **UNKNOWN** | **UNKNOWN** | ✓ |
| MULTI_WAITING | **UNKNOWN** | **UNKNOWN** | ✓ |
| UNKNOWN | UNKNOWN | UNKNOWN | ✓ |
| SWIPE_UP_TO_CAPSULE | UNKNOWN | UNKNOWN | ✓ |
| SWIPE_UP_TO_SPLIT_OR_FLOATING | UNKNOWN | UNKNOWN | ✓ |

> 12/12 对齐。

#### 表 A-3：`appLaunchAnimStartOrEnd end` 的 12 状态去向穷举（`isEnd=true`）

> 注意：原厂此分支并非无条件调 `updateAnimState` —— 仅当 `mAppLaunchAnims.isEmpty() && mOnceGestureProcessing` 时才进 switch，否则调 `checkAllAnimationFinished()`。**两个未触发状态转移的状态**：NONE / WAITING / MULTI_WAITING / SWIPE_UP_TO_*（else 不变）；lib 的 `else -> Unit` 完全等价于原厂的"return（不变）"。

| 当前状态 | lib 去向 (kt:95-100) | 原厂去向 (java:524-535) | 对齐 |
|---|---|---|---|
| NONE | **不变 (Unit)** | **不变 (return)** | ✓ |
| OPEN | WAITING | WAITING | ✓ |
| REVERSE_OPEN | **不变 (Unit)** | **不变 (return)** | ✓ |
| CLOSE | **不变 (Unit)** | **不变 (return)** | ✓ |
| MULTI_OPEN | MULTI_WAITING | MULTI_WAITING | ✓ |
| MULTI_REVERSE_OPEN | **不变 (Unit)** | **不变 (return)** | ✓ |
| MULTI_CLOSE | **不变 (Unit)** | **不变 (return)** | ✓ |
| WAITING | **不变 (Unit)** | **不变 (return)** | ✓ |
| MULTI_WAITING | **不变 (Unit)** | **不变 (return)** | ✓ |
| UNKNOWN | **不变 (Unit)** | **不变 (return)** | ✓ |
| SWIPE_UP_TO_CAPSULE | **不变 (Unit)** | **不变 (return)** | ✓ |
| SWIPE_UP_TO_SPLIT_OR_FLOATING | **不变 (Unit)** | **不变 (return)** | ✓ |

> 12/12 对齐。

#### 表 A-4：`revertRecentsAnimation` 的 12 状态去向穷举

| 当前状态 | lib 去向 | 原厂去向 (java:830-837) |
|---|---|---|
| NONE | （no-op） | UNKNOWN |
| OPEN | （no-op） | UNKNOWN |
| REVERSE_OPEN | （no-op） | UNKNOWN |
| CLOSE | **（no-op）** | **REVERSE_OPEN** |
| MULTI_OPEN | （no-op） | UNKNOWN |
| MULTI_REVERSE_OPEN | （no-op） | UNKNOWN |
| MULTI_CLOSE | **（no-op）** | **MULTI_REVERSE_OPEN** |
| WAITING | （no-op） | UNKNOWN |
| MULTI_WAITING | （no-op） | UNKNOWN |
| UNKNOWN | （no-op） | UNKNOWN |
| SWIPE_UP_TO_CAPSULE | （no-op） | UNKNOWN |
| SWIPE_UP_TO_SPLIT_OR_FLOATING | （no-op） | UNKNOWN |

> **0/12 对齐** —— lib 完全不实现这个 switch，是本次最严重的"遗漏"。

#### 表 A-5：`cleanUpRecentsAnim` —— 无状态转移，但有"是否触发回调/reset"的副作用

| 行为 | lib (kt:82-88) | 原厂 (java:583-596) |
|---|---|---|
| `mRecentsAnims.clear()` | ✓ | ✓ |
| `mRemoveTasksMaps.clear()` | ✓ | ✓ |
| `mOnceGestureProcessing = false` | ✓ | ✓ |
| `RemoteAnimInterrupter.INSTANCE.get().resetRecentsAnimProgress()` | **✗ 漏** | ✓ |
| 若 `!mAppLaunchAnims.isEmpty()` 返回 `false` | ✓（return !hasOpeningAnim） | ✓（return false） |
| 若 `mAppLaunchAnims.isEmpty()` **调 `checkAllAnimationFinished()`** | **✗ 漏（最致命）** | ✓ |
| 返回值 | `!hasOpeningAnim` | `!hasOpeningAnim`（true 分支） |

> 形状对齐，**两个关键副作用丢失**。

### ②-B. 有意简化（lib 注释/架构明示）

| # | 简化内容 | 原厂对应 | lib 取舍理由 |
|---|---|---|---|
| 1 | `mOpenWindowAnimRunning` flag 整套缺失（start 时设 true、Handler 收到 msg 后设 false；`forbidTouch()` 据此返回 true） | `AnimationController.java:80` 字段、`:280-281` handler、`:542,546` 写、`:687-688` 读 | lib 不接 `OplusAnimManager.getMultiAppAnimMergeHelper()`，`forbidTouch()` 走 `DefaultAnimationController.kt:98` no-op 恒 false；不引 mHandler 整套 release-touch 计时 |
| 2 | `MultiAppAnimMergeHelper.setRecentsAnimEndState(false/true, ...)` / `multiAppOpenAnimStart()` 整套缺失 | `AnimationController.java:198, 479, 227` | lib 简化 `OplusAnimManager` 只出 2 个 helper（`animController` + `animationSeqHelper`），砍掉 4 个 merge helper（见 review 03 §2.2-#7） |
| 3 | `updateRunningRemoteTarget` / `mRunningTask` 维护缺失 | `AnimationController.java:478, 967-975, 986-993` | lib 用 `Any?` 抽象 runningTaskInfo，没有 RemoteAnimationTargetCompat 强类型；`mRemoveTasksMaps` 留空壳 |
| 4 | `AnimationSuccessListener`（`AnonymousClass1` 内 `removeTask*` + `mRecentsAnimFinishCallback` + `OplusAnimManager.getAnimationSeqHelper().delayFinishRecents`）整套缺失 | `AnimationController.java:181-223` | lib 把"remote anim 完成后回调"路径让 demo 自己用 `AsyncAnimCallbacks` 触发；不引 `AnimationSuccessListener` 类型 |
| 5 | `canFinishRecentsAnim` 始终 `true`（基类 no-op） | `AnimationController.java:559-581` 真实实现（含 `getAnimationSeqHelper().canFinishRecent()` + `matchAnimationId` + size==1 判定） | lib `DefaultAnimationController.kt:54` 直接 `return true`；`mAppLaunchAnims` 永远空所以原厂那些 `!isEmpty` / size==1 / matchAnimationId 守卫无意义 |
| 6 | `mRemoteMergeFinishCallback` 同步 invoke | `AnimationController.java:252-270` 经 `Executors.UI_HELPER_EXECUTOR` 异步派发，且仅 `if (checkMainThread())` 异步、否则直接 run | demo 不需要 UI_HELPER_EXECUTOR 线程池 |
| 7 | `mForbidSwipeUpWhileStartingLandApp` 联动 + `setBetweenAppExitTransitionEndAndFinish` 的 nav-mode 三按钮守卫缺失 | `AnimationController.java:465, 859-863` | lib `setOnAppExit` 简化成无条件三个 boolean=true（kt:165-168），不引 `LauncherAnimConfig` / `DisplayController.getNavigationMode()` / `ScreenUtils.isLargeDisplayDeviceInLarge()` / `isAdaptiveLowAnimation()` 多源配置 |

### ②-C. 遗漏（lib 无对应实现）

| # | 遗漏内容 | 原厂位置 | 严重度 |
|---|---|---|---|
| 1 | **`revertRecentsAnimation` switch 完全未实现** | `AnimationController.java:826-838` | **BUG** —— 见 §③-#1 |
| 2 | **`cleanUpRecentsAnim` 不调 `checkAllAnimationFinished()`** | `AnimationController.java:594` | **BUG** —— 见 §③-#2 |
| 3 | `appLaunchAnimStartOrEnd` `isEnd=true` 路径必发的 `mHandler.sendEmptyMessage(101)` 缺失 | `AnimationController.java:514-517` | 中 —— 联动 `mOpenWindowAnimRunning`，lib 整套没这套所以无单独影响 |
| 4 | `addRecentsAnim` 没注册 `AnimationSuccessListener`（AnonymousClass1） | `AnimationController.java:481` | 中 —— 影响"recents anim end 后续路径"（删除 task / 触发 recentsAnimFinishCallback），demo 自行触发 |
| 5 | `cleanUpRecentsAnim` 不调 `RemoteAnimInterrupter.INSTANCE.get().resetRecentsAnimProgress()` | `AnimationController.java:590` | 低 —— RemoteAnimInterrupter 不在 lib 域 |
| 6 | `reset()` 不清 `mCurrentAnim` / `mClickAppView` / `mSwipingUpActivityPkg` | `AnimationController.java:821-822, 816-817` | 低 —— lib 没追踪这些字段 |

---

## ③ 行为差异风险点

### 🐞 #1 【BUG 级】`revertRecentsAnimation` 未 override → swipe-cancel 状态机断裂

**lib 证据**：`AnimationController.kt` 全文件 249 行无 `revertRecentsAnimation`；基类 `DefaultAnimationController.kt:64` 仅 `open fun revertRecentsAnimation(anim: CustomRectFSpringAnim) {}`。

**原厂证据**：`AnimationController.java:826-838` —— `CLOSE→REVERSE_OPEN`、`MULTI_CLOSE→MULTI_REVERSE_OPEN`、其它→`UNKNOWN`，并 `this.mCurrentAnim = anim`。

**call site**：`com/android/quickstep/util/animation/CustomRectFSpringAnim.java:756` —— `OplusAnimManager.getAnimController().revertRecentsAnimation(this);`（每次 recents anim 内部检测到反向手势时调用）。

**风险传导链**：
1. `revertRecentsAnimation` 不动状态 → `isOpeningAnim()` 始终 false（`AnimationController.kt:138-142` 仅 REVERSE_OPEN/OPEN/MULTI_OPEN/MULTI_REVERSE_OPEN 返 true）；
2. `forbidTouch()` 走基类 no-op 永远 false（漏掉 REVERSE_OPEN 那一支原厂的 `AnimationController.java:688`）；
3. **关键**：`isClosingAnimAndAnimClosed` 在 `CLOSE` 状态下返 true，但 cancel 后状态应该变 `REVERSE_OPEN`，让业务以为动画已结束 → 实际 recents anim 还在反向跑 → **触摸拦截与"动画已收尾"语义错位**；
4. `mCurrentAnim` 永远是 null → `removeTaskOnOpenAnimStart` / `removeTasks` 在 `getMReverseToOpen()` 检查时永远拿到 null → 任务移除逻辑可能错分支（原厂 `AnimationController.java:417-418, 439-440`）。

**修复成本**：**5 行**（在 `AnimationController.kt` 新增 override）：

```kotlin
override fun revertRecentsAnimation(anim: CustomRectFSpringAnim) {
    when (animState) {
        AnimationState.CLOSE -> updateAnimState(AnimationState.REVERSE_OPEN)
        AnimationState.MULTI_CLOSE -> updateAnimState(AnimationState.MULTI_REVERSE_OPEN)
        else -> updateAnimState(AnimationState.UNKNOWN)
    }
}
```

注：lib 没有 `mCurrentAnim` 字段，需不需要补可单独决策；若 demo 不调 `removeTask*` 可省。

### 🐞 #2 【BUG 级】`cleanUpRecentsAnim` 不调 `checkAllAnimationFinished()` → 状态卡死 + 回调永不发

**lib 证据**：`AnimationController.kt:82-88`：
```kotlin
override fun cleanUpRecentsAnim(): Boolean {
    val hasOpeningAnim = appLaunchAnims.isNotEmpty()
    recentsAnims.clear()
    removeTasksMaps.clear()
    onceGestureProcessingFlag = false
    return !hasOpeningAnim   // ← 直接 return，没调 checkAllAnimationFinished
}
```

**原厂证据**：`AnimationController.java:583-596`：
```java
public boolean cleanUpRecentsAnim() {
    boolean z8 = !this.mAppLaunchAnims.isEmpty();
    ...
    this.mRecentsAnims.clear();
    this.mRemoveTasksMaps.clear();
    this.mOnceGestureProcessing = false;
    RemoteAnimInterrupter.INSTANCE.get().resetRecentsAnimProgress();
    if (z8) return false;
    checkAllAnimationFinished();   // ← 关键：触发回调 + reset
    return true;
}
```

**风险传导链**：
1. 在 `appLaunchAnims` 为空的清理路径上，**finish 回调（`recentsAnimFinishCallback` + `appLaunchAnimFinishCallback`）永不发** → 任何依赖"动画结束"信号的业务（`LauncherTaskbarController` 等）会一直等；
2. 状态不会回 `NONE`（`reset()` 不被触发）→ `isAppWindowAnimRunning()` 持续返 true → 后续 `addRecentsAnim` 进入非 NONE/OPEN 路径走错；
3. demo 复现路径：`appLaunchAnimStartOrEnd(false)` 进 OPEN → `appLaunchAnimStartOrEnd(true)` 队列空 + `onceGestureProcessingFlag=true` 进 WAITING → 后续无回调把 recentsAnims 清空，外部调 `cleanUpRecentsAnim()` 想"擦屁股"，却发现回调不 fire、状态不归零。

**修复成本**：**1 行** —— 在 lib `cleanUpRecentsAnim` 返回前补：
```kotlin
override fun cleanUpRecentsAnim(): Boolean {
    val hasOpeningAnim = appLaunchAnims.isNotEmpty()
    recentsAnims.clear()
    removeTasksMaps.clear()
    onceGestureProcessingFlag = false
    if (!hasOpeningAnim) checkAllAnimationFinished()    // ← 新增
    return !hasOpeningAnim
}
```

### ⚠️ #3 【语义级】`appLaunchAnimStartOrEnd` start 路径副作用丢失 → `forbidTouch()` 长期 false

**lib 证据**：`AnimationController.kt:90-114` start 分支只做 `appLaunchAnims.add` + switch；无 `mOpenWindowAnimRunning=true`。

**原厂证据**：`AnimationController.java:538-547` start 分支先 `mOpenWindowAnimRunning=true` + `mHandler.sendEmptyMessageDelayed(101, 600L)` + `multiAppOpenAnimStart()` 才 switch。

**风险**：原厂 `forbidTouch()` = `mOpenWindowAnimRunning || animState==MULTI_WAITING || animState==REVERSE_OPEN || startActivityRunnable!=null`（`AnimationController.java:686-688`），由 mOpenWindowAnimRunning 在 start 后 600ms 内挡住所有 touch。lib 这条防线消失，**在 swipe-up 打开 app 的 600ms 窗口内，触摸可穿透**，可能造成"动画进行中误触"。

**严重度**：中。lib `forbidTouch()` 走基类恒 false（`DefaultAnimationController.kt:98`），即使补上 mOpenWindowAnimRunning 也只在 override 后才生效。

**修复成本**：**2-3 行** —— 加 `var openWindowAnimRunning: Boolean = false` 字段 + start/end 路径置位 + `forbidTouch()` override 把这个字段读进来。

### ⚠️ #4 【语义级】`isAppWindowAnimRunning` 与 `forbidTouch` 基类 no-op 联动丢失

**lib 证据**：`DefaultAnimationController.kt:41, 98` 全恒 false。

**原厂证据**：
- `isAppWindowAnimRunning` = `animState != NONE && animState != UNKNOWN`（`AnimationController.java:746-748`），lib override 实现这个但不接 mOpenWindowAnimRunning；
- `forbidTouch()` 基类有 large-display 兜底（`DefaultAnimationController.java:80-84` 用 `ScreenUtils.hasLargeDisplayFeatures()` + `QuickstepTransitionManager.isReverseToOpenAnimRunning()`），lib 直接 no-op。

**风险**：业务方对 `isAppWindowAnimRunning()` 的预期是"app 启动动画进行中"，但 lib 在 REVERSE_OPEN 时**不会**返回 true（因 `revertRecentsAnimation` 未实现）。与 #1 耦合。

**严重度**：中（单独存在不算 bug，与 #1 叠加放大）。

**修复成本**：与 #1 合并修。

### ℹ️ #5 【观察】SWIPE_UP_TO_CAPSULE / SWIPE_UP_TO_SPLIT_OR_FLOATING 永远不进 switch 输入

两个状态在 lib 和原厂都仅当"外部主动发射给 listener"的载体存在（`OplusBaseSwipeUpHandler.java:1987/1998/8882/8891`），Controller 的 5 个 switch 都不识别它们。这是有意设计 —— "taskbar 提示性状态" vs "controller 真正转移的状态"。lib 完整保留这两个枚举值 + 双 boolean 取值（`AnimationState.kt:18-19`），**对齐无误**。

---

## ④ 回移建议

### ✅ 值得补的（行为差异 > 实现成本）

| 优先级 | 修复项 | 成本 | 收益 | 依赖 |
|---|---|---|---|---|
| **P0** | #2 `cleanUpRecentsAnim` 补 `checkAllAnimationFinished()` | 1 行 | 修"回调永不发 + 状态卡死"组合 bug | 无 |
| **P0** | #1 `revertRecentsAnimation` 补 3-way switch | 5 行 + 文档 | 修 swipe-cancel 状态机断裂 | 无 |
| P1 | #3 `mOpenWindowAnimRunning` 字段 + start/end 置位 + `forbidTouch()` 引入 | 8-12 行 | 恢复 600ms 触摸拦截窗口 | 仅当 demo 关心触摸拦截 |
| P2 | `appLaunchAnimStartOrEnd` end 路径补 `mHandler.sendEmptyMessage(101)` 的"标记释放"语义 | 3-5 行 | 完整对齐 RELEASE_TOUCH 计时 | 需先实现 #3 |

### 🟡 建议保持简化的（行为差异 < 实现成本）

| # | 简化项 | 理由 |
|---|---|---|
| 1 | `MultiAppAnimMergeHelper` 全套（`setRecentsAnimEndState` / `multiAppOpenAnimStart` / `checkMainThread` / `UI_HELPER_EXECUTOR`） | lib `OplusAnimManager.kt` 已自砍这 4 个 helper（review 03 §2.2-#7），无 merge 上下文 |
| 2 | `AnimationSuccessListener`（AnonymousClass1）整套 | demo 用 `AsyncAnimCallbacks` 触发回调路径，不依赖 `removeTask*` 守卫 |
| 3 | `mRemoveTasksMaps` 的 typed `RemoteAnimationTargetCompat` / `RecentsAnimationController` | lib 用 `Any` 抽象，类型壳对齐即可 |
| 4 | `mForbidSwipeUpWhileStartingLandApp` + `DisplayController.getNavigationMode()` 三按钮守卫 | 缺 `LauncherAnimConfig` / `ScreenUtils.isLargeDisplayDeviceInLarge()` 多源配置，demo 用不到 |
| 5 | `mRemoteMergeFinishCallback` 异步派发 | demo 不需要 UI_HELPER_EXECUTOR 线程池 |
| 6 | `reset()` 不清 `mCurrentAnim` / `mClickAppView` / `mSwipingUpActivityPkg` | lib 根本没追踪这些字段，无残留可清 |

---

## 附录：状态转移覆盖矩阵总览

| 入口方法 | 出口数（lib） | 出口数（原厂） | 对齐度 | 漏调副作用 |
|---|---|---|---|---|
| `addRecentsAnim` | 9（else 兜底 UNKNOWN） | 9（default 兜底 UNKNOWN） | **100%（12/12 状态去向）** | `mCurrentAnim=null` / `updateRunningRemoteTarget` / `MultiAppAnimMergeHelper.setRecentsAnimEndState(false)` / `AnimationSuccessListener` 注册 / `mRecentsMainFinishCallback=null` |
| `appLaunchAnimStartOrEnd` start | 3 改 + 9 不变 | 3 改 + 9 不变 | **100%（12/12 状态去向）** | `mOpenWindowAnimRunning=true` / `mHandler.sendEmptyMessageDelayed(101,600)` / `multiAppOpenAnimStart()` |
| `appLaunchAnimStartOrEnd` end | 2 改 + 10 不变 | 2 改 + 10 不变 | **100%（12/12 状态去向）** | `mHandler.sendEmptyMessage(101)` |
| `revertRecentsAnimation` | **0（全 no-op）** | 2 改 + 10 改（兜底 UNKNOWN） | **0%（0/12）** | `mCurrentAnim=anim` |
| `cleanUpRecentsAnim` | 无 switch | 无 switch | 形状对齐 | **`checkAllAnimationFinished()` 未调** + `RemoteAnimInterrupter.resetRecentsAnimProgress()` 未调 |

> 综合：**4 个有 switch 的入口里 3 个 100% 对齐、1 个 0% 实现**（#1 revert）；**1 个无 switch 的入口有 1 个致命漏调**（#2 cleanup）。

---

**取证命令**（reproducible）：
- `python -c "open('D:/oppo_a6_launcher/sources/com/oplus/quickstep/utils/AnimationController.java','rb').read().decode('utf-8')"` 拿原厂完整文本
- `python -c "open('D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/controller/AnimationController.kt','rb').read().decode('utf-8')"` 拿 lib 完整文本
- `grep -nE "AnimationState\.(NONE|OPEN|CLOSE|MULTI_OPEN|MULTI_CLOSE|WAITING|MULTI_WAITING|REVERSE_OPEN|MULTI_REVERSE_OPEN|UNKNOWN|SWIPE_UP_TO_CAPSULE|SWIPE_UP_TO_SPLIT_OR_FLOATING)" /tmp/orig_animctl.txt` 锁 12 状态出现位置
- `grep -nE "SWIPE_UP_TO_CAPSULE|SWIPE_UP_TO_SPLIT_OR_FLOATING" D:/oppo_a6_launcher/sources -r` 确认这 2 个状态仅由 `OplusBaseSwipeUpHandler` 外部发射
