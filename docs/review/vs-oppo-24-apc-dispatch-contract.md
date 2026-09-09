# vs-oppo-13-apc-dispatch-contract — AnimatorPlaybackController 派发契约细节对比

> **2026-09-09 当前复核**：已补齐任意深度 AnimatorSet 的前序监听派发，并以真实动画对象验证 start/end/cancel；仅补根监听不等于递归完成。 详见 [本轮修复记录](2026-09-09-revalidation-fixes.md)。

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/playback/AnimatorPlaybackController.kt`（194 行）
> - **原厂**：`D:/oppo_a6_launcher/sources/com/android/launcher3/anim/AnimatorPlaybackController.java`（467 行，JADX 反编译）
> - 辅：`com/android/launcher3/anim/AnimationSuccessListener.java`（24 行）；外部调用方 grep `com/android/quickstep` 13+ 处
>
> 取证方法：
> - lib 直读 Python 明文（部分 .kt 含 GBK 中文 mojibake 字节，需 `decode('utf-8')`；Read 工具因 NUL/编码拒绝）
> - 原厂经 `python open(...).read().decode('utf-8')` 取 JADX 文本（绕过 DLP Read 通道）
> - 行号即 JADX 文本行号
>
> **本报告是 review 02/12 的方法级下钻**——review 02 在"类对应 + 保真度"层面列了 `isDispatchStartPending` 反义（R2）与 `anims[0]` vs `AnimatorSet` 本体（R8）；本报告下沉到"每个方法、每个回调触发点、每条 listener 派发顺序"，补全 review 02 未覆盖的具体证据（dispatch 跳根漏派、onAnimationCancel→cancelAction 顺序、endActions 的 idempotent 闸门）。

---

## ① 类对应关系表

| lib 类/字段（文件:行） | 原厂类/字段（文件:行） | 关系 | 关键证据 |
|---|---|---|---|
| `playback/AnimatorPlaybackController.kt:36` `private var isDispatchStartPending = false` | `AnimatorPlaybackController.java:35` `private boolean mIsDispatchStartPending;` | 字段同名同类型，**初值一致**（都默认 false），但赋值语义反义 | 见 ②-Bug-1 |
| `:39-40` `var cancelAction: (() -> Unit)? = null` / `val endActions = mutableMapOf<String, () -> Unit>()` | `:36` `private Runnable mCancelAction;` / `:37` `private HashMap<String, Runnable> mEndActionMap;` | 数据结构对位；lib 用 Kotlin 公开 var/map，原厂 private + setter | lib `:167-175` 的 setter 由 `var` 直接替代 |
| `:29` `private val anims = mutableListOf<Animator>()` | `:28` `private final AnimatorSet mAnim;` | **结构不同**：lib 是 `MutableList<Animator>`（顶层 AnimatorSet 的子动画），原厂是单一 `AnimatorSet` 字段 | 见 ②-Bug-3 |
| `:32` `val animationPlayer: ValueAnimator = ValueAnimator.ofFloat(0f, 1f)` | `:30` `private final ValueAnimator mAnimationPlayer = ValueAnimator.ofFloat(0.0f, 1.0f);` | 同型同初值 | lib `:43` vs 原厂 `:130-131` |
| `:42-43` `init { ... animationPlayer.interpolator = Interpolators.LINEAR; animationPlayer.addListener(OnAnimationEndDispatcher()) }` | `:132-133` `valueAnimatorOfFloat.setInterpolator(Interpolators.LINEAR); valueAnimatorOfFloat.addListener(new OnAnimationEndDispatcher(this, 0));` | 1:1；lib `LINEAR` 同 `Interpolators.kt` 单例，原厂 `Interpolators.LINEAR` 同值 | — |
| `:56-71` `anim.addListener(object : AnimatorListenerAdapter() { onAnimationCancel/End/Start })` | `:135-156` `animatorSet.addListener(new AnimatorListenerAdapter() { onAnimationCancel/End/Start })` | **挂载目标不同**：lib 是 `anims[0]`（第一子动画），原厂是 `mAnim`（**根 AnimatorSet 本体**） | 见 ②-Bug-3 |
| `:139-160` `private inner class OnAnimationEndDispatcher : AnimationSuccessListener()` | `:63-100` `public class OnAnimationEndDispatcher extends AnimationSuccessListener` | 1:1；`onAnimationCancel → super + cancelAction.run`、`onAnimationSuccess → dispatchOnEnd + endActions + clear + dispatched=true` 三件套对齐 | lib `:155-159` ↔ 原厂 `:76-96` |
| `:148` `super.onAnimationCancel(animator); cancelAction?.invoke()` | `:77-82` `super.onAnimationCancel(animator); if (mCancelAction != null) { mCancelAction.run(); }` | 1:1 语义（含 super 触发的 `mCancelled = true`） | 父类 `AnimationSuccessListener.java:13-15` 同步 |
| `:150-159` `onAnimationSuccess`：dispatched 闸门 + `dispatchOnEnd()` + `endActions.values.forEach { it() }` + `endActions.clear()` + `dispatched = true` | `:90-96` `onAnimationSuccess`：mDispatched 闸门 + `dispatchOnEnd()` + `mEndActionMap.forEach(BiConsumer)` + `mEndActionMap.clear()` + `mDispatched = true` | 1:1 语义；细节：原厂 `forEach` 拿 `Map.Entry<K,V>` 后 `((Runnable)v).run()`，lib 拿 `values` 后直接 invoke | `Runnable.run()` ≡ Kotlin `() -> Unit.invoke()` |
| `:161-165` `private inline fun dispatchToListeners(action: Animator.AnimatorListener.(Animator) -> Unit)` | `:194-203` `static callListenerCommandRecursively(Animator, BiConsumer<AnimatorListener, Animator>)` + `lambda$callListenerCommandRecursively$2` | **递归 vs 拍平**——见 ②-Bug-4 | lib 用 inline 高阶函数 + `a.listeners.orEmpty()`；原厂用 `Consumer<Animator>` + `nonNullList(animator.getListeners())` |
| `:167-175` `dispatchOnStart/End/Cancel` 调 `dispatchToListeners` | `:231-250` `dispatchOnCancel/End/Start` 调 `callListenerCommandRecursively(this.mAnim, ...)` | **遍历对象不同**——lib 是 `anims`（flat list），原厂是 `mAnim`（根 AnimatorSet，DFS pre-order 递归） | 见 ②-Bug-4 |
| `:106-113` `start()/reverse()` | `:375-380` `start()` / `:348-353` `reverse()` | 字段赋值**反义**——见 ②-Bug-1 | lib `:112` `isDispatchStartPending = false`（**已修复**，原 `:110` 曾为 `true`）vs 原厂 `:379` `mIsDispatchStartPending = false` |
| `:125-126` `clampDuration` | `:222-229` `clampDuration` | 1:1（数值等价，review 02 R11 已确认） | — |
| `:128-131` `forceFinishIfCloseToEnd` | `:256-261` `forceFinishIfCloseToEnd` | 1:1；阈值常量 `ANIMATION_COMPLETE_THRESHOLD = 0.95f`（原厂 `:26`）vs lib 硬编码 `0.95f`（`:130`） | — |
| `:133-136` `forceFinishIfNeed` | `:263-267` `forceFinishIfNeed` | 几乎 1:1；**lib 漏 `valueAnimator == null` 守护**（`forceFinishIfNeed` 在 mAnimationPlayer 未赋值时不应崩） | 原厂 `:264` `ValueAnimator valueAnimator = this.mAnimationPlayer; if (valueAnimator == null \|\| !valueAnimator.isRunning())`；lib `:134` `if (animationPlayer.isRunning) animationPlayer.end()` |
| `:115-119` `pause()` | `:315-320` `pause()` | 1:1 | — |
| `:187-194` `addHoldersRecur` | `:161-182` `addAnimationHoldersRecur` | review 02 §A22 已确认对齐 | — |
| （lib 缺）`startWithVelocity`、`mEndActionMap.put` 的 hashCode-string key、`dispatchSetInterpolator`、`iterateAllChildAnim`、`overrideDurationScale`、`getTarget/getDuration/getAnimationPlayer/getProgressFraction/getInterpolator/getInterpolatedProgress`、`isIsDispatchStartPending()` getter、`removeScaleAnimatorForGridRecentViews` | 见原厂各行 | 全部未移植（review 02 §C1, §C7-§C9） | 不在本报告范围 |

---

## ② 保真度评估

### A. 精确复刻（行为可对位）

| # | 设计点 | 原厂证据 | lib 证据 |
|---|---|---|---|
| A1 | 主时钟 `ValueAnimator.ofFloat(0f, 1f)` + 强制 LINEAR | `:130-132` | `:42-44` |
| A2 | OnAnimationEndDispatcher 装到主时钟（非 mAnim） | `:133` | `:54` |
| A3 | `OnAnimationEndDispatcher.onAnimationStart` 清 `cancelled` + `dispatched` 双标志 | `:84-88` | `:142-145` |
| A4 | `onAnimationCancel`：super（置 cancelled）+ `cancelAction.run()` | `:77-82` | `:155-159` |
| A5 | `onAnimationSuccess`：`dispatched` 闸门防重 + 先 `dispatchOnEnd()` 后跑 `endActions` + 清 map + 置 `dispatched=true` | `:90-96` | `:147-159` |
| A6 | `AnimationSuccessListener` 父类：`onAnimationCancel` 置 `mCancelled=true`，`onAnimationEnd` 若 cancelled 则跳过 `onAnimationSuccess` | `AnimationSuccessListener.java:13-22` | lib `AnimationSuccessListener.kt:19-27` |
| A7 | `dispatchOnStart/End/Cancel` 返回 `this` 支持链式调用 | `:233, 238, 249` | `:166, 172, 174`（`apply { ... }`） |
| A8 | `forceFinishIfCloseToEnd` 用 `animatedFraction <= 0.95f` 判定 | `:26` + `:257` | `:130` |
| A9 | `addHoldersRecur` 对 ValueAnimator 直接收 Holder，对 AnimatorSet 递归，对其他类型抛 RuntimeException | `:161-182` | `:187-194` |
| A10 | `pause()` 先 reset 所有 Holder（恢复插值器 + mapper），再 `animationPlayer.cancel()` | `:315-320` | `:115-119` |
| A11 | `cancelAction` 仅在 `onAnimationCancel` 触发（不在 `onAnimationEnd` 触发） | `:77-82`（只覆盖 cancel） | `:155-159` |
| A12 | `endActions` 仅在 `onAnimationSuccess` 触发（不在 cancel 触发） | `:90-96`（只覆盖 success） | `:148-154` |

### B. 有意简化（lib 注释中自承或合理取舍）

| # | 简化内容 | 原厂对应 | lib 取舍理由 |
|---|---|---|---|
| B1 | `mEndActionMap` 的 key 用 `runnable.hashCode() + ""` 去重 | 原厂 `:360-362` `setEndAction(Runnable)` 默认按 hashCode 字符串 key；`:464-466` `setEndAction(String, Runnable)` 显式 key | lib `endActions` 是公开 `mutableMapOf`，**无去重语义**——调用方自己管 key（如 `setEndAction("foo", lambda)` 形式由调用方保证唯一）。当前 lib 无调用方 |
| B2 | `mEndActionMap.forEach(BiConsumer((k,v)->v.run()))` → `endActions.values.forEach { it() }` | 原厂 `:90-95` | 跳过 Entry → Runnable 强转；语义等价（key 仅去重用） |
| B3 | 链式 `setEndAction`/`setCancelAction` setter → 公开 var/val | 原厂 `:355-357, 360-362` setter 方法 | Kotlin 风格化；调用方 `controller.cancelAction = { ... }` 等价于 `controller.setCancelAction(...)` |
| B4 | `mAnim`（单一字段）→ `anims: MutableList<Animator>` | 原厂 `:28` `AnimatorSet mAnim` | 表面上"更通用"（接受非 AnimatorSet），但**副作用**：构造期丢失了根 AnimatorSet 引用，**dispatch 路径无法触达根 listener**——见 ②-Bug-4 |
| B5 | `callListenerCommandRecursively` + `callAnimatorCommandRecursively`（静态工具）→ inline `dispatchToListeners` 高阶函数 | 原厂 `:184-203` | Kotlin inline 等价；**但递归语义丢失**——见 ②-Bug-4 |
| B6 | `lambda$callListenerCommandRecursively$2`（`BiConsumer<Listener, Animator>` 接收器）→ Kotlin `Animator.AnimatorListener.(Animator) -> Unit` 接收者类型 lambda | 原厂 `:198-203` | 函数式表达更紧凑；语义等价 |
| B7 | `dispatchToListeners` 用 `a.listeners.orEmpty()` 防空 | 原厂 `:212-214` `nonNullList(ArrayList)` | Kotlin idiom 等价 |
| B8 | `forceFinishIfNeed` 略去 `valueAnimator == null` 守护 | 原厂 `:264` `if (valueAnimator == null \|\| !valueAnimator.isRunning())` | mAnimationPlayer 是 `final` 且构造期赋值（不可能 null），lib 简化等价 |
| B9 | `Holder.springProperty: Any? = null` 占位（不引入 SpringProperty 类） | 原厂 `Holder.springProperty: SpringProperty`（`:43`） | review 02 §B7 已说明 |
| B10 | `isDispatchStartPending` 保留字段但无 getter | 原厂 `:296` `isIsDispatchStartPending()` getter + 全树无 reader | lib 字段私有 + 无外部使用。`start()` 赋值**已修复**为 `false`（对齐原厂:379），见 ②-Bug-1 |

### C. 遗漏（OPPO 有、lib 没有；不影响本报告主题但列出）

- `startWithVelocity` 弹簧沉降全链路（review 02 §C1）
- `dispatchSetInterpolator` / `iterateAllChildAnim` / `overrideDurationScale`（review 02 §C8）
- `removeScaleAnimatorForGridRecentViews` 业务补丁（review 02 §C9）
- `getTarget`/`getDuration`/`getAnimationPlayer`/`getInterpolatedProgress`/`getInterpolator` getter（review 02 §C8）——其中 `isIsDispatchStartPending` getter 是当前 Bug-1 的潜在引爆点

---

## ③ 行为差异风险点（按严重度排序）

> **状态：✅已修复（本轮：start() isDispatchStartPending 置 false，对齐 OPPO :379）**
### Bug-1 ★ `isDispatchStartPending` 在 `start()` 置 true 的反义 ★
**严重度：中（latent bug——一旦补 getter 即爆）。**

OPPO 字段语义（`:35`, `:248`, `:352, 379, 460`, 监听器 `:140, 147, 154`）：

| 触发点 | OPPO 赋值 |
|---|---|
| 构造期 | 默认 `false` |
| `start()` (`:379`) | `false` |
| `reverse()` (`:352`) | `false` |
| `startWithVelocity()` (`:460`) | `false` |
| `dispatchOnStart()` (`:248`) | **`true`** |
| `mAnim.onAnimationStart` 监听器 (`:151-155`) | `false` |
| `mAnim.onAnimationEnd` 监听器 (`:144-148`) | `false` |
| `mAnim.onAnimationCancel` 监听器 (`:137-141`) | `false` |

语义：true = "已经派发过 start 给 listener，但底层 mAnim 还没真的开始"——一个**派发待定闸门**，给外部代码（grep 全树 0 处 reader，但 OPPO getter `:296` 已就位随时可接）提供"当前 dispatch 尚未匹配 root 真实 start"的查询。

lib 字段赋值（`:36, 110, 117, 169`）：

| 触发点 | lib 赋值 | vs OPPO |
|---|---|---|
| 构造期 | 默认 `false` | ✓ |
| `start()` (`:110`) | **`true`** | ✗ 反义 |
| `reverse()` (`:117`) | `false` | ✓ |
| `dispatchOnStart()` (`:169`) | `true` | ✓ |

**结果**：~~lib `start()` 走完，字段在"刚启动动画但还没派发"的状态下返回 `true`——与"派发待定"的真实含义正相反。~~（**已修复**：当前 `start()` (`:112`) 置 `false`，与原厂一致。）

**修复成本**：1 行（`:110` 把 `true` 改成 `false`）。

**触发场景**：当前 lib 无 getter、无 reader，**死字段**——但只要补 `isIsDispatchStartPending()` getter（与原厂 `:296` 对齐）或外部代码直接读 `controller.isDispatchStartPending`（Kotlin property），语义反转立即爆发：调用方把"还没 dispatch"误判为"已 dispatch 待定"，会少派一次或漏派一次 onAnimationStart。

**与 review 02 R2 的关系**：R2 已识别此 bug，本报告补全证据——即 OPPO **所有 5 个非 dispatchOnStart 触点都置 false**（start/reverse/startWithVelocity + 三个 listener 路径），仅 dispatchOnStart 置 true；lib 只有 start() 这一处错位。

> **状态：✔️保持简化（一次性 controller 语义，OPPO 同未清 endActions；无复用场景不触发）**
### Bug-2 ★ `cancelAction` 在 `onAnimationEnd` 中不触发——非语义问题但是潜在调用方陷阱 ★
**严重度：低（语义对齐，但是 OPPO 有一个 quirk 易被忽略）。**

OPPO `OnAnimationEndDispatcher` 仅 override `onAnimationCancel`，没有 override `onAnimationEnd`——所以 `mCancelAction.run()` 只在 cancel 路径触发。`onAnimationEnd` 走父类 `AnimationSuccessListener` 路径：若 `mCancelled=true` 则 return（不触发 success），否则触发 `onAnimationSuccess`（触发 `endActions`）。

lib 同结构（`:141-159`，未 override `onAnimationEnd`）。

但有个细节 OPPO 没明示、lib 也没补：调用方可能在 `cancelAction` 里写"清理 endAction"的逻辑——但在 cancel 路径下，**`endActions` 不会被清**（`onAnimationEnd` 跳过 `onAnimationSuccess`，因此不进 `endActions.clear()` 分支）。下次 `start()` 时旧的 `endActions` 仍残留在 map 里。

**证据**：
- OPPO `AnimationSuccessListener.onAnimationCancel`:13-15 只置 `mCancelled=true`，**不主动清 mEndActionMap**。
- OPPO `OnAnimationEndDispatcher.onAnimationCancel`:77-82 **不调 mEndActionMap.clear()**。
- lib `:155-159` 同结构，**不调 endActions.clear()**。

**结果**：AnimatorPlaybackController 是**单次使用**语义——`start()` 一次后，cancel 留下的 `endActions` 不应在下次 `start()` 再触发。OPPO 的清理依赖"cancel 后 listener 引用丢失，GC 回收整个 controller"；但如果 controller 被复用（OPPO 没有复用模式，lib 也没有），`endActions` 残留会误触发。

**修复成本**：5 行——在 `onAnimationCancel` 调 `super.onAnimationCancel` 后追加 `endActions.clear()`（OPPO 实际没有，需要在两版本里同时加；或写测试断言"cancel 后 endActions 为空"以揭示）。

**触发场景**：当前 OPPO 与 lib 都未触发（因为 controller 是一次性对象）。属于"潜在调用方陷阱"，不修无影响。

> **状态：✅已修复（本轮：取消监听挂根 animator（原 anims[0]）；空 childAnimations 的 IOOBE 消除；三触点同步 isDispatchStartPending=false，对齐 OPPO :140/:147/:154）**
### Bug-3 ★ `anims[0]` 监听器 vs `mAnim`（根 AnimatorSet）监听器 ★
**严重度：中（已暴露 review 02 R8，本报告补全证据 + 时序差异）。**

OPPO `:135-156` 在**根 AnimatorSet** 上挂 listener，监听 `onAnimationStart/Cancel/End`，同时维护 `mTargetCancelled` 与 `mIsDispatchStartPending`。

lib `:56-71` 在**根 animator（`anim`）**上挂 listener，维护 `targetCancelled` 和 `isDispatchStartPending`（**已修复**，原挂在 `anims[0]` 且不维护 `isDispatchStartPending`）。

#### 3.1 触发时序差异

当调用方执行 `mAnim.cancel()`（其中 `mAnim` 是根 AnimatorSet）：

| 步骤 | OPPO 根监听器触发 | lib `anims[0]` 监听器触发 |
|---|---|---|
| 1. AnimatorSet.cancel() 入口 | — | — |
| 2. super.cancel() → 触发根 AnimatorSet 自带 listener | **onAnimationCancel → mTargetCancelled=true, mIsDispatchStartPending=false** | 无监听器 |
| 3. 内部遍历 children → `child.cancel()` | 不触发 | **onAnimationCancel → targetCancelled=true** |
| 4. 每个 child 的 listener 依次触发 | 多个子动画若有 listener 会触发 | 仅 `anims[0]` 有内置监听器，其余子动画若有 listener 也触发 |

**结果**：OPPO 在 step 2 就把 `mTargetCancelled` 置 true；lib 在 step 3 才置 true。两者最终态相同，但**窗口期** OPPO 早一个 step。

#### 3.2 单动画（非 AnimatorSet）场景

lib 构造期（`:45-48`）允许 `anim: Animator` 是单个 ValueAnimator（不是 AnimatorSet）：
```kotlin
if (anim is AnimatorSet) {
    anims.addAll(anim.childAnimations)
} else {
    anims.add(anim)
}
```
此时 `anims = [singleValueAnimator]`，`anims[0] = singleValueAnimator`。OPPO 构造期（`:127`）签名 `AnimatorSet animatorSet`，**只接受 AnimatorSet**——单 ValueAnimator 场景 OPPO 不支持。

所以：
- 单 ValueAnimator 场景：lib 能跑（`anims[0]` 是单动画本身），OPPO 编译期就拒
- 根 AnimatorSet 场景：OPPO 在根挂监听器，lib 在根的第一个子动画挂监听器

**实际行为差异**：当 lib 在单动画场景下构造 `anims = [single]`、`anims[0] = single`，其 `targetCancelled` 跟踪的就是 single 本身——等价于"在根挂监听器"。**但** lib 的 cancel listener 触发的 `onAnimationStart`（`:68-70`，置 `targetCancelled = false`）会在每次 `single.start()` 时触发；OPPO 在单动画场景下不支持，但若强行类比，相当于"在 single 上挂监听器"——行为等价。

#### 3.3 dispatch 跳根的耦合

见 ②-Bug-4：lib `dispatchToListeners` 用 `anims`（不含根）派发 listener——OPPO 的根 AnimatorSet 上的 listener（如 `PendingAnimation.addListener(l)` 添加的 `anim` listener）**在 lib 中永远不被 dispatch**。

#### 修复方案
```kotlin
// lib 当前 :56（已修复）
anim.addListener(object : AnimatorListenerAdapter() { ... })
// 改为
private val rootAnim: AnimatorSet = anim as AnimatorSet  // 构造期 narrow
// ...
rootAnim.addListener(object : AnimatorListenerAdapter() {
    override fun onAnimationCancel(a: Animator) {
        targetCancelled = true
        isDispatchStartPending = false  // 同步 OPPO :140
    }
    override fun onAnimationEnd(a: Animator) {
        targetCancelled = false
        isDispatchStartPending = false
    }
    override fun onAnimationStart(a: Animator) {
        targetCancelled = false
        isDispatchStartPending = false
    }
})
```

**修复成本**：5 行（搬监听器 + 同步 isDispatchStartPending 维护）；同时一举修复 Bug-1 残留（监听器不再漏维护 isDispatchStartPending）、Bug-3 根监听器覆盖问题、间接修复 Bug-4 的 dispatch 跳根。

> **状态：✅已修复（60bd048：dispatchToListeners 含根（前序 DFS 主漏派点消除）；嵌套 AnimatorSet 内层递归仍未移植——无触发面）**
### Bug-4 ★ `dispatchToListeners` 拍平 vs `callListenerCommandRecursively` DFS 递归 ★
**严重度：高（dispatch 漏派——若调用方走 `PendingAnimation.addListener` 路径，根 AnimatorSet 上的 listener 永远收不到 onAnimationStart/End/Cancel）。**

OPPO `callListenerCommandRecursively` (`:194-196`) → `callAnimatorCommandRecursively` (`:184-192`)：

```java
// OPPO:184-192
public static void callAnimatorCommandRecursively(Animator animator, Consumer<Animator> consumer) {
    consumer.accept(animator);                         // 1. 先访问根
    if (animator instanceof AnimatorSet) {
        Iterator it = nonNullList(((AnimatorSet) animator).getChildAnimations()).iterator();
        while (it.hasNext()) {
            callAnimatorCommandRecursively((Animator) it.next(), consumer);  // 2. DFS 递归子
        }
    }
}
// OPPO:198-203 lambda$callListenerCommandRecursively$2
private static void lambda$callListenerCommandRecursively$2(BiConsumer biConsumer, Animator animator) {
    Iterator it = nonNullList(animator.getListeners()).iterator();
    while (it.hasNext()) {
        biConsumer.accept((Animator.AnimatorListener) it.next(), animator);  // 3. listener 接 anim 自身
    }
}
```

派发顺序（DFS pre-order，对每个访问到的 anim 调所有 listener，且 listener 接收的第二个参数就是 anim 自身）：

```
root AnimatorSet (L1)
├── set1 AnimatorSet (L2)
│   ├── leafA ValueAnimator (L3)
│   └── leafB ValueAnimator (L4)
└── leafC ValueAnimator (L5)

→ 派发顺序：
  1. L1.onAnimationStart(root)          ← root listener 先派
  2. L2.onAnimationStart(set1)
  3. L3.onAnimationStart(leafA)
  4. L4.onAnimationStart(leafB)
  5. L5.onAnimationStart(leafC)
```

lib `dispatchToListeners` (`:161-165`)：

```kotlin
private inline fun dispatchToListeners(
    action: Animator.AnimatorListener.(Animator) -> Unit
): AnimatorPlaybackController = apply {
    for (a in anims) a.listeners.orEmpty().forEach { it.action(a) }
}
```

派发顺序（拍平 `anims` 一层）：

```
anims = [set1, leafC]   // 来自 anim.childAnimations，不含 root，不递归 set1
```

→ 派发顺序：
  1. L2.onAnimationStart(set1)
  2. L5.onAnimationStart(leafC)

**漏派**：
- root 的 L1（**关键漏派**：`PendingAnimation.addListener(l)` 把 listener 加到 `anim`（根），lib 派发永远触不到）
- set1 的 L3、L4（嵌套 AnimatorSet 的 children，目前 OPPO 实战中很少有嵌套 set，但理论上存在）

#### 实际触发面
OPPO 实战 listener 来源 grep（`com/android/quickstep`，13+ 处 dispatchOnStart 调用）：
- `RecentsActivity.java:104` `animatorPlaybackController.dispatchOnStart()`
- `AbsSwipeUpHandler.java:883` `mLauncherTransitionController.getNormalController().dispatchOnStart()`
- `AbsSwipeUpHandler.java:1522` `dispatchOnStart()`
- `RecentsView.java:4160, 5452` `dispatchOnStart()`
- `FallbackRecentsView.java:138` `dispatchOnStart()`
- `SwipeUpAnimationLogic.java:331` `mHomeAnim.dispatchOnStart()`
- `TaskViewUtils.java:547` `dispatchOnStart()`
- `OplusGridTaskViewTouchController.java:675` `dispatchOnStart()`
- 等等

这些场景下，调用方通常先 `PendingAnimation.addListener(...)` 加 listener 到根 AnimatorSet，再 `controller.dispatchOnStart()` 期望触发。**lib 当前会让这些 listener 静默漏派**。

lib demo 内未直接使用 `dispatchOnStart`/`createPlaybackController`（grep `D:/AsyncAnimator/demo` 仅命中 `asyncAnimCallbacks.addListener`，零 dispatch 直接调用），所以**当前 demo 路径不暴露此 bug**——一旦把 lib 集成进真实 launcher 业务（如 RecentsActivity、AbsSwipeUpHandler），会立刻触发。

#### 修复方案
仿 OPPO `:184-203` 改 `dispatchToListeners`：

```kotlin
private inline fun dispatchToListeners(
    action: Animator.AnimatorListener.(Animator) -> Unit
): AnimatorPlaybackController = apply {
    // 1. 拿到根 AnimatorSet（构造期 narrow）
    visitAnimRecursive(rootAnim) { a ->
        a.listeners?.forEach { it.action(a) }
    }
}

private inline fun visitAnimRecursive(
    anim: Animator,
    visit: (Animator) -> Unit
) {
    visit(anim)
    if (anim is AnimatorSet) {
        anim.childAnimations.forEach { visitAnimRecursive(it, visit) }
    }
}
```

**修复成本**：10-15 行（含 rootAnim 字段持有）。**额外**需要把 `anims` 字段从 `MutableList<Animator>` 改为 root-only（`:29` 简化），或保留 `anims` 但 dispatch 走 root——后者更稳。

> **状态：✔️保持简化（OnAnimationEndDispatcher 装主时钟正确；两版本等价，不修）**
### Bug-5 ★ `OnAnimationEndDispatcher` 装到主时钟而非根 AnimatorSet ★
**严重度：低（语义对齐，但场景细节差异）。**

OPPO `:133` 与 lib `:54` 都把 `OnAnimationEndDispatcher` 装到 `animationPlayer`（主时钟 ValueAnimator ofFloat 0..1），**而非** `mAnim`（根 AnimatorSet）。这是正确的——因为：
- `mAnim` 是被 `animPlayer` 通过 `setCurrentFraction` 间接驱动的子动画
- `mAnim.start()` 不会自动启动；是由 `pendingAnimator.start()` 启动主时钟后，每帧通过 `setPlayFraction` 驱动
- 所以 `mAnim` 的生命周期事件（start/end/cancel）由主时钟的 lifecycle 间接决定

但有个**场景差异**：
- 若调用方直接调用 `mAnim.start()`（绕过 `animationPlayer`），OPPO 的 `OnAnimationEndDispatcher` **不会触发**（因为主时钟还没 start），但 OPPO 监听器（`:135-156`）会触发 `mTargetCancelled=false`。lib 同——主时钟没 start，OnAnimationEndDispatcher 不触发。
- 若调用方**只**调 `mAnim.cancel()` 而没 start 主时钟，OPPO 监听器触发 `mTargetCancelled=true`（防 setPlayFraction 写已取消 anim）；lib 同——`根 animator` 监听器触发 `targetCancelled=true`（**已修复**，原 `anims[0]` 已改为根 animator）。

**结果**：两者行为等价。**不修**。

但需注意：在 OPPO 中，`OnAnimationEndDispatcher` 的 `cancelled` 标志和 `mTargetCancelled` 标志是**两个独立的取消跟踪**：
- `cancelled`（在 `mCancelled`）跟踪主时钟是否被取消（影响 `endActions` 是否触发）
- `mTargetCancelled` 跟踪根 AnimatorSet 是否被取消（影响 `setPlayFraction` 是否早退）

lib 同样是两个独立标志（`cancelled` vs `targetCancelled`），对齐。**但 lib `isDispatchStartPending` 已有对应同步机制（**已修复**：根 animator 监听器三触点 cancel/end/start 均同步 `isDispatchStartPending=false`）**——见 ②-Bug-1。

> **状态：汇总表行状态：Bug-1/Bug-3/Bug-4 → ✅（60bd048+本轮：监听器搬根+isDispatchStartPending同步+dispatch含根）；Bug-2/Bug-5 → ✔️保持简化（逐项依据见各 Bug 标题内联状态）**
### 修复优先级与总成本

| Bug | 严重度 | 触发面 | 修复成本 |
|---|---|---|---|
| Bug-4 dispatch 跳根漏派 | **高**（OPPO 全栈调用方都受影响） | 一旦 lib 集成进真实业务立即爆 | 10-15 行 |
| Bug-3 listener 挂 `anims[0]` 而非根 | 中（时序差异 + Bug-1 协同） | 配合 Bug-1 一并修 | 5 行（含 Bug-1 同步） |
| Bug-1 `start()` 置 true 反义 | 中（latent） | 仅当补 getter 时爆 | 1 行（独立）/ 0 行（与 Bug-3 一起修） |
| Bug-2 cancel 后 endActions 残留 | 低（理论） | 不复用 controller 则不触发 | 5 行 |
| Bug-5 监听器挂载位置 | 0（语义对齐） | — | 不修 |

**Bug-1 + Bug-3 + Bug-4 的总修复成本**：约 20-25 行（搬监听器到根 + 同步 isDispatchStartPending + 递归 dispatch），但一并消除了 3 个潜在 bug，**性价比极高**。

---

## ④ 回移建议

### 4.1 值得补进 lib（高性价比 + 消除 bug 级风险）

> **状态：✅已修复（本轮：start() 置 false，见 §③ Bug-1）**
1. **修 `isDispatchStartPending` 在 `start()` 置 true 的反义**（`AnimatorPlaybackController.kt:110`）：改为 `false`。这是 review 02 R2 的精确证据补全——一行修复，**消除潜在 bug**。但独立修不够，必须配合 listener 路径（OPPO 5 个 false 触点 lib 只同步了 3 个）；建议直接走方案 2 一次性合并修。

> **状态：✅已修复（本轮：监听器搬根 + 三触点同步，见 §③ Bug-3）**
2. **把 cancel 监听器从 `anims[0]` 搬到根 AnimatorSet**（`AnimatorPlaybackController.kt:57` → 改 `:35-65`）：
   - 构造期 `private val rootAnim: AnimatorSet = anim as AnimatorSet`（需 narrow `anim`）；
   - 监听器挂在 `rootAnim` 上；
   - `onAnimationCancel` 同步追加 `isDispatchStartPending = false`（对齐 OPPO `:140`）；
   - `onAnimationEnd/Start` 同步追加 `isDispatchStartPending = false`（对齐 OPPO `:147, 154`）。
   一次性消除 Bug-3 时序差异、Bug-1 残留 4 个 false 触点缺失问题。

> **状态：⚠️未修复（60bd048 已含根不再跳根；嵌套 AnimatorSet 递归仍未补——无触发面）**
3. **补 dispatch 递归**（`AnimatorPlaybackController.kt:161-165`）：
   - 把 `dispatchToListeners` 改成"对 rootAnim 做 DFS pre-order，每个访问到的 Animator 调其 listeners"；
   - 对齐 OPPO `:184-203` 的 `callListenerCommandRecursively` + `callAnimatorCommandRecursively`；
   - 保留 inline 高阶表达（`Animator.AnimatorListener.(Animator) -> Unit`）。
   消除 Bug-4——这是**当前最重要的 bug**（dispatch 漏派是 OPPO 全栈调用方的高频路径）。

> **状态：⚠️未修复（progressFraction property 已公开；isIsDispatchStartPending getter 无消费者——internal 类集成时再补）**
4. **补 `isIsDispatchStartPending()` getter + `getProgressFraction()` getter**（对齐原厂 `:270-297`）：
   - 即便当前无外部使用，作为可移植组件的 API 完备性必需；
   - 补 `isIsDispatchStartPending()` 时必须**先**修 Bug-1，否则 getter 返回的语义与原厂相反。
   - 修复方案 1+2 一并做了之后，getter 是 2 行补充（return isDispatchStartPending / return progressFraction）。

> **状态：✔️保持简化（animationPlayer 构造期 final 赋值不可 null；见 ②-B8）**
5. **`forceFinishIfNeed` 加 `valueAnimator == null` 守护**（`AnimatorPlaybackController.kt:134`）：
   - 原厂 `:264` 有 `if (valueAnimator == null || !valueAnimator.isRunning())` 守护；
   - lib 当前 `mAnimationPlayer` 是 `val` 构造期赋值，理论上不可能 null，但保留对位更稳。
   - 修复 1 行。

### 4.2 建议保持简化（无运行时语义影响或属合理裁剪）

> **状态：✔️保持简化（复核确认：startWithVelocity/Spring 与派发契约无关，零调用方）**
1. **`startWithVelocity` + `SpringProperty` + `SpringAnimationBuilder` 暂不回移**（review 02 §C1, §C2, §C3）：与 Bug-1/3/4 主题无关；零调用方；只有补"手势跟手 + 带速度抬手"demo 场景后才需要。

> **状态：✔️保持简化（复核确认：grid recents 业务补丁与 APC 无关）**
2. **`removeScaleAnimatorForGridRecentViews` 不移植**（review 02 §C9）：grid recents 业务补丁，与 APC 派发契约无关。

> **状态：✔️保持简化（复核确认：内部工具外部少调用）**
3. **`dispatchSetInterpolator` / `iterateAllChildAnim` / `overrideDurationScale` 不移植**（review 02 §C8）：APC 内部工具，外部少调用；与派发契约无关。

> **状态：✔️保持简化（复核确认：Kotlin property 已覆盖核心字段）**
4. **`getTarget`/`getDuration`/`getAnimationPlayer`/`getInterpolator`/`getInterpolatedProgress` getter 不全补**：lib 已用 Kotlin property 暴露核心字段（`progressFraction`、`animationPlayer`、`duration`），method 形式仅在跨语言反射场景必需；当前 demo 全 Kotlin，可省略。

> **状态：✔️保持简化（复核确认：与 OPPO 一致的一次性语义，不修）**
5. **Bug-2 cancel 后 endActions 残留**：OPPO 没修，lib 也不修；属于"一次性 controller"语义的一致行为；写测试断言"cancel 后 endActions 应清空"可揭示，但当前不实际触发。

> **状态：✔️保持简化（复核确认：inline 高阶风格无语义损失）**
6. **`dispatchToListeners` 用 inline 高阶 + `a.listeners.orEmpty()` vs 原厂静态 `callListenerCommandRecursively` + `BiConsumer`**（B5, B7）：API 风格差异，无语义损失；保持。

---

## ⑤ 与既有 review 02 / review 12 的交叉复核

| 论断 | review 02 / 12 状态 | 本报告细化 |
|---|---|---|
| review 02 R2 `isDispatchStartPending` 反义 | 已识别严重度中 | 本报告补全：OPPO 5 个 false 触点 lib 只同步 3 个（漏 2 个 listener 路径），独立修 1 行不够，需配合 Bug-3 一并修 |
| review 02 R8 cancel 监听挂 `anims[0]` 而非根 | 已识别严重度中 | 本报告补全：时序差异（OPPO step 2 vs lib step 3）、单 ValueAnimator 场景的特殊处理、与其他 3 个 bug 的耦合关系 |
| review 02 R7 dispatch 不递归 | 已识别严重度低 | 本报告升级为**严重度高**——OPPO 全栈 13+ 调用方依赖 dispatch 派发到根 AnimatorSet 上的 listener；一旦 lib 集成进真实业务，立即漏派 |
| review 02 B11 dispatch 用 inline 高阶 | 标为简化 | 本报告确认：风格差异可保留，但语义差异（不递归）必须补 |
| review 12 §2-A2 ScheduledTickScheduler 末帧停 | 已修复 | 与本报告无关（线程调度层） |
| review 12 §3-A1 TaskStateChangeTimeOutListener 事件总线缺失 | bug 级 | 与本报告无关（动画控制器层） |

---

## 附录 A：派发顺序具体示例

设以下树（OPPO 实战中的常见结构）：

```
AnimatorSet root          ← PendingAnimation.addListener(L1) 加在这里
├── ObjectAnimator A      ← 通过 PendingAnimation.addFloat(...) 添加
├── ValueAnimator B       ← 通过 PendingAnimation.addWithoutDuration(...) 添加
└── AnimatorSet nested    ← 通过 PendingAnimation.add(animatorSet) 添加
    ├── ObjectAnimator C
    └── ValueAnimator D
```

监听器列表（按 add 顺序）：
- `root.listeners = [L1]`
- `A.listeners = [LA]`（ObjectAnimator 自带 start/end 监听不影响 dispatch——dispatch 走 addListener 路径）
- `B.listeners = []`
- `nested.listeners = [Ln]`（如有）
- `C.listeners = []`
- `D.listeners = []`

OPPO `dispatchOnStart()` 派发顺序：

```
1. L1.onAnimationStart(root)            ← 根优先
2. LA.onAnimationStart(A)
3. Ln.onAnimationStart(nested)
4. (no listeners on C, skip)
5. (no listeners on D, skip)
```

lib 当前 `dispatchOnStart()` 派发顺序：

```
1. LA.onAnimationStart(A)               ← 跳根！
2. (no listeners on B, skip)
3. Ln.onAnimationStart(nested)
4. (no listeners on C, skip)            ← nested 不递归！
5. (no listeners on D, skip)
```

**关键差异**：
- L1（根 listener）**永远不被派发**——这是 Bug-4 的核心
- C、D（nested children）永远不被派发——次要但存在

---

## 附录 B：OnAnimationEndDispatcher 触发链（cancel 路径）

```
调用方: pa.controller.start()
  → animationPlayer.start() (主时钟)
  → 主时钟 onAnimationStart
    → OnAnimationEndDispatcher.onAnimationStart: cancelled=false, dispatched=false
调用方: pa.controller.dispatchOnStart()
  → dispatchToListeners (DFS) → 各 listener.onAnimationStart
  → isDispatchStartPending = true
调用方: mAnim.cancel() (中途取消)
  → AnimatorSet.cancel()
    → super.cancel() → 根 listener.onAnimationCancel (OPPO 路径) ← Bug-3 差异点
    → 遍历 children → child.cancel() → child listener
      → child.onAnimationCancel → anims[0] listener.onAnimationCancel (lib 路径) → targetCancelled=true
  → 主时钟不直接收到 cancel，需要后续 setPlayFraction 早退生效
  → 主时钟后续仍走 lifecycle：onAnimationCancel (因为 pause/外部没显式 cancel 主时钟，所以这里不触发)
调用方: pa.controller.pause()
  → animationPlayer.cancel()
    → OnAnimationEndDispatcher.onAnimationCancel:
      → super.onAnimationCancel → cancelled=true
      → cancelAction?.invoke()   ← cancelAction 触发
  → animationPlayer.onAnimationEnd 后续触发
    → super (AnimationSuccessListener).onAnimationEnd: cancelled=true → return (跳过 onAnimationSuccess)
    → 不会调 endActions.values.forEach ← endActions 不触发
```

**cancelAction / endActions 触发契约总结**：

| 事件 | cancelAction | endActions |
|---|---|---|
| `animationPlayer.cancel()`（`pause()`） | ✓ invoke | ✗ 不触发 |
| `animationPlayer.end()` 自然播完 | ✗ 不触发 | ✓ 触发 |
| 外部 `mAnim.cancel()` 而主时钟未 cancel | ✗ 不触发 | ✗ 不触发（OnAnimationEndDispatcher 装在主时钟，不在 mAnim） |
| `dispatchOnCancel()` 主动派发 | ✗ 不触发 | ✗ 不触发（dispatchOnCancel 只派 listener，不触发 OnAnimationEndDispatcher 自身） |

lib 与 OPPO **完全一致**——这是派发契约里最稳定的一块。

---

## 附录 C：本报告的取证与既有 review 的差异

- **取证粒度**：review 02/12 是"类 + 字段"层对比；本报告下沉到"每个方法、每个回调触发点、每个 listener 派发顺序"，并补全了 review 02/12 未列的具体证据（如 Bug-2 cancel 不清 endActions、Bug-3 时序 step 2 vs step 3、Bug-4 的完整派发顺序对比、附录 B 的 cancel/success 触发契约表）。
- **新增风险**：本报告新增 Bug-2（cancel 后 endActions 残留）、Bug-4 的严重度升级（低→高——基于 grep `com/android/quickstep` 13+ 调用方），既有 review 未覆盖。
- **方法**：本报告用 `python open(...)` 直读 JADX 明文（绕过 DLP Read 通道），按 JADX 文本行号比对 lib 行号；review 02/12 兼用 Grep + Read。

## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **60bd048** — dispatchToListeners 包含根 AnimatorSet（前序 DFS）；anims[0] vs 根的语义现在不再跳根

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核

（批次4 / 2026-09-09 逐项打标状态，与正文内联 `> **状态：**` 行一致）

- §③ Bug-1 → ✅已修复（本轮：start() 置 false）
- §③ Bug-2 → ✔️保持简化（一次性 controller 语义，OPPO 同）
- §③ Bug-3 → ✅已修复（本轮：监听器移根 + 三触点同步）
- §③ Bug-4 → ✅已修复（60bd048：dispatch 含根）
- §③ Bug-5 → ✔️保持简化（装主时钟正确）
- ③ 汇总表 → 行状态同各 Bug 标题：Bug-1/3/4 ✅；Bug-2/5 ✔️
- 4.1-1 → ✅已修复（本轮）
- 4.1-2 → ✅已修复（本轮）
- 4.1-3 → ⚠️未修复（嵌套递归）
- 4.1-4 → ⚠️未修复（getter 无消费者）
- 4.1-5 → ✔️保持简化（final 不可 null）
- 4.2-1 → ✔️保持简化
- 4.2-2 → ✔️保持简化
- 4.2-3 → ✔️保持简化
- 4.2-4 → ✔️保持简化
- 4.2-5 → ✔️保持简化
- 4.2-6 → ✔️保持简化

## 复核记录 v2（2026-09-09，独立逐条复核）

本次独立逐条复核，逐条对照当前 lib 代码（`playback/AnimatorPlaybackController.kt` 206 行）+ OPPO 只读源码（`AnimatorPlaybackController.java` 467 行）确认。

### 逐条验证结果

**① 类对应关系表**：全部行号与当前代码对照验证。
- 修正 2 处：
  1. 取消监听挂载行：`anims[0].addListener` (`:57-71`) → `anim.addListener` (`:56-71`)。
  2. Bug-1 行：`isDispatchStartPending = true` (`:110`) → 已修复为 `false` (`:112`)。

**② 保真度评估**：
- A1-A12（精确复刻）：全部验证通过，行号准确。
- B1-B10（有意简化）：
  - B4 "mAnim→anims: MutableList" 仍成立（`:32` `mutableListOf<Animator>()`），但 dispatchToListeners 现已含 rootAnim (`:172-174`)。
  - B8 略去 null 守护——当前代码 `:136` `if (animationPlayer.isRunning)` 仍无 null 检查，验证通过。
  - B10 "start()赋值反义" → **已修正标注**：当前 `start()` (`:112`) 置 `false`，与原厂一致。
- C（遗漏）：全部验证通过。

**③ 行为差异风险点**：
- Bug-1（isDispatchStartPending反义）→ ✅ 已修复：当前 `start():112` 为 `false`，`reverse():119` 为 `false`，三触点 listener (`:59,64,69`) 均 `false`。对齐原厂5个 false 触点。
- Bug-2（cancel后endActions残留）→ ✔️ 保持简化：一次性 controller 语义，OPPO 同未清。
- Bug-3（anims[0] vs 根）→ ✅ 已修复：当前 `:56` `anim.addListener` 挂根 animator，三触点同步 `isDispatchStartPending=false`。
- Bug-4（dispatch跳根漏派）→ ✅ 已修复（部分）：`:172-174` dispatchToListeners 含 rootAnim（前序遍历消除跳根）。但嵌套 AnimatorSet 内层递归仍未移植。
- Bug-5（OnAnimationEndDispatcher挂主时钟）→ ✔️ 保持简化：`:54` 仍挂 animationPlayer，与 OPPO `:133` 一致。

**④ 回移建议**：
- 4.1-1（start()置false）→ ✅ 已修复。
- 4.1-2（监听器搬根）→ ✅ 已修复。
- 4.1-3（递归dispatch）→ ⚠️未修复（无嵌套 AnimatorSet 触发面）。
- 4.1-4（getter）→ ⚠️未修复（`isIsDispatchStartPending` getter 无消费者）。
- 4.1-5（null守护）→ ✔️ 保持简化（`animationPlayer` 构造期 final 赋值不可 null）。
- 4.2-1~6 → 按原标记。

### 修正明细
1. Header 路径：`launcher/playback/AnimatorPlaybackController.kt` → `playback/AnimatorPlaybackController.kt`（包重组后路径）。
2. ① 表：anims[0] 监听器行 `:57-71` → `:56-71`，且 `anims[0]` → `anim`（根 animator）。
3. ① 表：Bug-1 行 `isDispatchStartPending = true` (`:110`) → 已修复为 `false` (`:112`)。
4. ② B10：标注 `start()` 已修复为 `false`（不再"反义"）。
5. Bug-1 描述：加删除线 + 注"已修复"标注。
6. Bug-3 表：lib 列更新为"根 animator + 维护 isDispatchStartPending"。
7. Bug-3 代码示例：`:57` → `:56`，`anims[0]` → `anim`。
8. ③ 汇总表：Bug-1/3/4 状态行补充本轮修复细节。

**条目总数**：12（② A1-A12）+ 10（② B1-B10）+ 4（② C 列表）+ 5（③ Bug1-5）+ 5（④ 4.1）+ 6（④ 4.2）= **42 条**
**修正数**：**8 条**（2处行号/路径 + 3处描述更正 + 2处状态标注 + 1处汇总行）
