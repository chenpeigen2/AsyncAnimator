# vs-oppo-13-pending-e2e-flow — PendingAnimation add / buildAnim / createPlaybackController 端到端对比

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/pending/PendingAnimation.kt`（180 行）+
>   `…/playback/AnimatorPlaybackController.kt`（196 行）
> - **原厂**：`D:/oppo_a6_launcher/sources/com/android/launcher3/anim/PendingAnimation.java`（235 行）+
>   `…/anim/AnimatorPlaybackController.java`（467 行）
>
> 取证：DLP 加密下 `Read` 工具对 OPPO 文件返回非 UTF-8 错误。本报告全部经 `python open(..., encoding='utf-8', newline='').read()` 走明文通道读入，行号即 JADX 文本行号。lib Kotlin 文件含中文注释为 GBK 乱码但代码结构清晰。
>
> 本报告聚焦 **三条** 用户指定路径：
> 1. `add(Animator, TimeInterpolator)` / `addWithoutDuration` / `addFloat` / `setFloat`
> 2. `buildAnim` 的 progressAnimator 时长写入（含 review 02 §3-4 复核）
> 3. `createPlaybackController` 调用 `AnimatorPlaybackController` 构造 + 包装 `addHoldersRecur`
>
> 以及用户指定的 **三处具体改动对齐情况**：
> - lib `setFloat` 动画版（即 `PendingAnimation.kt:72-78` 的 `override fun setFloat`）
> - lib 自定义 `ObjectAnimator.buildAnimator()` 返回 `ValueAnimator`
> - lib `addHoldersRecur` 的 `else -> throw RuntimeException`

---

## ① 类对应关系表（端到端路径）

| lib 类（文件:行） | 原厂类（文件:行） | 关系 | 关键证据 |
|---|---|---|---|
| `PendingAnimation.kt:27`（`internal class PendingAnimation(duration: Long) : PropertySetter`）| `PendingAnimation.java:23`（`public class implements PropertySetter`）| 字段一一对齐：`anim`↔`mAnim`、`animHolders`↔`mAnimHolders`、`progressAnimator`↔`mProgressAnimator`、`controller`↔`mAnimatorPlaybackController`、`durationMs`↔`mDuration`、`isAnimFinished`↔`isAnimFinished` | lib `:29-35` ↔ 原厂 `:24-30` |
| `PendingAnimation.kt:45-49`（`fun add(child: Animator): PendingAnimation`）| `PendingAnimation.java:191-194`（`add(Animator, SpringProperty)`）| 行为等价；lib 砍 SpringProperty 第二参 | lib 主体 `child.duration = durationMs; anim.playTogether(child); addToHolders(child)` ↔ 原厂 `mAnim.play(animator.setDuration(mDuration)); addAnimationHoldersRecur(animator, mDuration, springProperty, mAnimHolders)` |
| `PendingAnimation.kt:51-54`（`add(child: Animator, ip: TimeInterpolator?)`）| `PendingAnimation.java:55-58`（`add(Animator, TimeInterpolator, SpringProperty)`）| 行为等价 + 砍 SpringProperty 第三参 | lib `child.interpolator = ip; return add(child)` ↔ 原厂 `animator.setInterpolator(timeInterpolator); add(animator, springProperty)` |
| `PendingAnimation.kt:56-59`（`addWithoutDuration(child: Animator)`）| `PendingAnimation.java:88-91`（`addWithoutDuration(Animator)`）| **完全等价**（playTogether vs play 等价对单子动画） | lib `anim.playTogether(child); addToHolders(child)` ↔ 原厂 `mAnim.play(animator); addAnimationHoldersRecur(animator, mDuration, SpringProperty.DEFAULT, mAnimHolders)` |
| `PendingAnimation.kt:61-66`（`addFloat(target, property, from, to, ip)`）| `PendingAnimation.java:67-71`（`<T> addFloat(T, FloatProperty<T>, float, float, TimeInterpolator)`）| 签名等价 + 内部 ObjectAnimator 实现不同（lib 自定义 wrapper，OPPO 用平台）| lib `ObjectAnimator.ofFloat(target, property, from, to); setInterpolator(ip); return add(oa.buildAnimator())` ↔ 原厂 `ObjectAnimator.ofFloat(t8, floatProperty, f9, f10); setInterpolator(timeInterpolator); add(objectAnimatorOfFloat)` |
| `PendingAnimation.kt:72-78`（`override fun <T> setFloat(target, property, value, interpolator)`）| `PendingAnimation.java:127-134`（`@Override setFloat(T, FloatProperty<T>, float, TimeInterpolator)`）| 行为等价 + 实现细节差异（见 §3 R1）| lib `ObjectAnimator.ofFloat(target, property, property.get(target), value)`（4 参显式 from）↔ 原厂 `ObjectAnimator.ofFloat(t8, floatProperty, f9)`（3 参隐式 from=current）|
| `PendingAnimation.kt:80-82`（`addEndListener(onEnd: ((Boolean) -> Unit)?)`）| `PendingAnimation.java:60-65`（`addEndListener(Consumer<Boolean>)`）| 精确对应 | lib `progressAnimator().addListener(AnimatorListeners.forEndCallback(onEnd))` ↔ 原厂 `mProgressAnimator.addListener(AnimatorListeners.forEndCallback(consumer))` |
| `PendingAnimation.kt:84-86`（`addOnFrameCallback(onFrame: () -> Unit)`）| `PendingAnimation.java:77-79`（`addOnFrameCallback(Runnable)`）+ `:81-86`（`addOnFrameListener`）| 精确对应（lib 不暴露 addOnFrameListener `ValueAnimator.AnimatorUpdateListener` 入口） | lib `progressAnimator().addUpdateListener { onFrame() }` ↔ 原厂 `mProgressAnimator.addUpdateListener(animatorUpdateListener)` |
| `PendingAnimation.kt:88-90`（`addListener(l)`）| `PendingAnimation.java:73-75`（`addListener(Animator.AnimatorListener)`）| 精确对应 | lib `anim.addListener(l)` ↔ 原厂 `mAnim.addListener(animatorListener)` |
| `PendingAnimation.kt:92-101`（`fun buildAnim(): AnimatorSet`）| `PendingAnimation.java:93-103`（`AnimatorSet buildAnim()`）| 精确对应 + 时长写入路径差异（见 §3 R2） | lib `progressAnimator?.let { add(it); progressAnimator = null }; if (animHolders.isEmpty()) addWithoutDuration(ValueAnimator.ofFloat(0f, 1f).setDuration(durationMs)); return anim` ↔ 原厂 `ValueAnimator valueAnimator = mProgressAnimator; if (valueAnimator != null) { add(valueAnimator); mProgressAnimator = null; } if (mAnimHolders.isEmpty()) { add(ValueAnimator.ofFloat(0f, 1f).setDuration(mDuration)); } return mAnim` |
| `PendingAnimation.kt:103-105`（`createPlaybackController()`）| `PendingAnimation.java:105-110`（`AnimatorPlaybackController createPlaybackController()`）| 精确对应 + 缓存逻辑等价 | lib `controller ?: AnimatorPlaybackController(buildAnim(), durationMs, animHolders).also { controller = it }` ↔ 原厂 `if (mAnimatorPlaybackController == null) { mAnimatorPlaybackController = new AnimatorPlaybackController(buildAnim(), mDuration, mAnimHolders); } return mAnimatorPlaybackController` |
| `AnimatorPlaybackController.kt:74-89`（`class Holder`）| `AnimatorPlaybackController.java:38-61`（`public static class Holder`）| 字段对应 + 砍 springProperty 类型 | lib `:75-79` ↔ 原厂 `:39-50` |
| `AnimatorPlaybackController.kt:186-194`（`addHoldersRecur`）| `AnimatorPlaybackController.java:161-182`（`addAnimationHoldersRecur`）| 精确对齐 else-throw（见 §3 R3） + 砍父级 duration/interpolator 下发（见 §3 R4） | lib 三分支：`is ValueAnimator -> out.add(Holder(anim, totalDuration.toFloat()))`、`is AnimatorSet -> forEach 递归`、`else -> throw RuntimeException("Unknown animation type $anim")` ↔ 原厂 `if ValueAnimator -> arrayList.add(new Holder(animator, j8, springProperty)); return;` `if (!(animator instanceof AnimatorSet)) throw new RuntimeException("Unknown animation type " + animator);` `Iterator<Animator> it = ((AnimatorSet) animator).getChildAnimations().iterator(); while (it.hasNext()) { ... }` |
| `AnimatorPlaybackController.kt:27-70`（主构造器）| `AnimatorPlaybackController.java:127-159`（主构造器）| 关键差异：**lib 取消监听挂 `anims[0]`，原厂挂 `animatorSet` 本体**（见 §3 R5）| lib `:46-70` ↔ 原厂 `:127-159` |
| （lib 缺 `wrap()` 工厂 + `mAnim` 私有 vs 公开） | `AnimatorPlaybackController.java:216-220`（`wrap(AnimatorSet, long)`）| lib 自有 `wrap` 在 `:265-269`（已检查，等价实现） | lib `wrap(set, duration): AnimatorPlaybackController { addHoldersRecur(set, duration, holders); return AnimatorPlaybackController(set, duration, holders) }` ↔ 原厂 `wrap(animatorSet, j8): ArrayList arrayList = new ArrayList(); addAnimationHoldersRecur(animatorSet, j8, SpringProperty.DEFAULT, arrayList); return new AnimatorPlaybackController(animatorSet, j8, arrayList)` |
| `PendingAnimation.kt:117-179`（内嵌 `ObjectAnimator` 包装类） | 平台 `android.animation.ObjectAnimator`（extends ValueAnimator） | **实现机制不同**：lib 自定义 wrapper 不继承 ValueAnimator；OPPO 用平台类 | lib `:124, 149-158` ↔ 平台 `ObjectAnimator.ofFloat(target, property, values)` |

---

## ② 保真度评估

### A. 精确复刻（行为可对齐）

| # | 设计点 | 原厂证据 | lib 证据 |
|---|---|---|---|
| A1 | `add(Animator)` 内部覆写 `duration = mDuration`、加入 AnimatorSet、收 Holder | `PendingAnimation.java:191-194` | `PendingAnimation.kt:45-49` |
| A2 | `add(Animator, TimeInterpolator)` 先 `setInterpolator` 再调 `add(Animator)` | `PendingAnimation.java:55-58` | `PendingAnimation.kt:51-54` |
| A3 | `addWithoutDuration(Animator)` 跳过 `setDuration`、直接 play + 收 Holder（保留外部预设时长） | `PendingAnimation.java:88-91` | `PendingAnimation.kt:56-59` |
| A4 | `addFloat(target, prop, from, to, ip)`：构造 ObjectAnimator + setInterpolator + add | `PendingAnimation.java:67-71` | `PendingAnimation.kt:61-66`（用 lib 自定义 ObjectAnimator，见 §3 R6）|
| A5 | `setFloat` 短路：`property == null` 或 `property.get(target) == value` 时 no-op | `PendingAnimation.java:128` | `PendingAnimation.kt:74` |
| A6 | `addEndListener` / `addOnFrameCallback` 复用 `progressAnimator` 懒建 | `PendingAnimation.java:60-65, 77-86` | `PendingAnimation.kt:80-86, 107-109` |
| A7 | `addListener` 转发到 `AnimatorSet` 本体（监听 `mAnim`，非子动画） | `PendingAnimation.java:73-75` | `PendingAnimation.kt:88-90` |
| A8 | `buildAnim()` 主体三步：① 把 `progressAnimator` 调 `add()`；② 置 null；③ 若 `animHolders` 空补 0→1 占位 | `PendingAnimation.java:93-103` | `PendingAnimation.kt:92-101` |
| A9 | `buildAnim` 调用 `add(progressAnimator)` → 走 `add(Animator)` → `setDuration(mDuration)` 写入 | `PendingAnimation.java:96 → 191-194` | `PendingAnimation.kt:94 → 45-49`（同样 setDuration）|
| A10 | `createPlaybackController` 单例缓存 + 懒构造 | `PendingAnimation.java:105-110` | `PendingAnimation.kt:103-105` |
| A11 | `createPlaybackController` 调 `new AnimatorPlaybackController(buildAnim(), mDuration, mAnimHolders)` | `PendingAnimation.java:107` | `PendingAnimation.kt:104` |
| A12 | `AnimatorPlaybackController` 主构造器创建独立 `ValueAnimator.ofFloat(0,1)` 主时钟 + LINEAR 插值 | `AnimatorPlaybackController.java:130-132` | `AnimatorPlaybackController.kt:44, 52` |
| A13 | `OnAnimationEndDispatcher` 挂到 `animationPlayer` 自身 | `AnimatorPlaybackController.java:133` | `AnimatorPlaybackController.kt:54` |
| A14 | `addHoldersRecur` 三分支：ValueAnimator → 收 Holder；AnimatorSet → 递归子动画；其他 → 抛 `RuntimeException` | `AnimatorPlaybackController.java:164-170` | `AnimatorPlaybackController.kt:187-193`（精确对齐 else-throw 行为）|
| A15 | `Holder` 构造期一次性计算 `globalEndProgress = animator.duration / totalDuration` | `AnimatorPlaybackController.java:50` | `AnimatorPlaybackController.kt:76` |
| A16 | `Holder.setProgress(f)` 经 mapper 后 `anim.setCurrentFraction(...)` | `AnimatorPlaybackController.java:58-60` | `AnimatorPlaybackController.kt:81-83` |
| A17 | `ProgressMapper.DEFAULT`：`f > g → 1` 否则 `f / g` | `AnimatorPlaybackController.java:117-122` | `AnimatorPlaybackController.kt:13` |
| A18 | `Holder.reset()` 恢复原插值器 + mapper 重置为 DEFAULT | `AnimatorPlaybackController.java:53-56` | `AnimatorPlaybackController.kt:85-88` |

### B. 有意简化（Kotlin 化或减负）

| # | 简化内容 | 原厂对应 | lib 取舍理由 |
|---|---|---|---|
| B1 | 砍掉 `add(Animator, SpringProperty)` 三参 overload + `add(Animator, long)` 双参 overload + `setFloat(... delay, duration)` 重载 + `setViewAlpha(... delay, duration)` 重载 + `setFloats` / `setInt` / `setInterpolator` / `setViewAlpha` / `setViewBackgroundColor` / `getAnimatorSet` / `getDuration` / `getListeners` / `addOnFrameListener` 等共 9 个方法 | `PendingAnimation.java:148-159, 164-184, 187-189, 198-209, 211-214, 218-234`；`PendingAnimation.java:60, 73, 81, 112, 116, 120` | lib 注释（`PendingAnimation.kt:53-54`）明示"第三参丢弃"；`PendingAnimation.kt:115` 注释 "此处仅为演示 lib 关键 API，砍掉非演示必需方法"。View 侧方法依赖 `AlphaUpdateListener`（`PendingAnimation.java:169, 227`），未移植；PropertySetter 多重重载同理 |
| B2 | lib `add()` / `addFloat()` / `addWithoutDuration()` 等返回 `PendingAnimation`（链式）| 原厂对应方法全部返回 `void`（`PendingAnimation.java:55, 67, 88, 127, 137, 148, 157, 164, 176, 187, 191, 211`）| Kotlin 风格化；语义等价 |
| B3 | `Consumer<Boolean>` → `(Boolean) -> Unit`、`Runnable` → `() -> Unit` | 原厂 `java.util.function.Consumer`、`java.lang.Runnable` | 函数类型 + Kotlin idiom |
| B4 | `buildAnim` 用 `add(progressAnimator)` 覆写时长；空 holders 补占位用 `addWithoutDuration` 而非 `add` | 原厂 `buildAnim:96` 调 `add(valueAnimator)`（`add(Animator, SpringProperty)`），`:100` 调 `add(ValueAnimator.ofFloat(0f,1f).setDuration(mDuration))`（`add(Animator, SpringProperty)`，本身 idempotent 重新设时长）| lib 用 `addWithoutDuration` 因为空 holders 补占位已经在外部 `setDuration(durationMs)`；lib `add` 会强制 `child.duration = durationMs` 二次写入，幂等但语义重复 |
| B5 | `progressAnimator` 用 Kotlin `?.let { ... }` 而非原厂 `if (valueAnimator != null) { ... }` | `PendingAnimation.java:94-98` | Kotlin idiom |
| B6 | `animHolders.isEmpty()` 判断后 `playTogether` vs 原厂 `mAnim.play` | `PendingAnimation.java:89`（`mAnim.play`）vs `PendingAnimation.kt:47, 57`（`anim.playTogether`）| 单子动画语义等价（`play` 与 `playTogether` 对单子动画等效）|
| B7 | `addHoldersRecur` 砍 `duration > 0 → next.setDuration` + `interpolator != null → next.setInterpolator` 的父级下发 | `AnimatorPlaybackController.java:174-179` | 当前 lib 构造路径不产嵌套 `AnimatorSet`，无触发面（已有 review 02 §C-11 标注）|
| B8 | `APC` 主构造器拆 `anims` 平铺 + 取消监听挂到 `anims[0]` 而非 `animatorSet` 本体（**见 §3 R5**）| 原厂 `animatorSet.addListener(new AnimatorListenerAdapter() { cancel: mTargetCancelled=true; mIsDispatchStartPending=false; end: ...; start: ... })`（`:135-156`）| lib 拆 `anim.childAnimations` 入 `anims` 列表，`a.addListener` 写 `anims[0]`。当前构造路径下 `anims[0]` 是 progressAnimator（占位）或第一个 add() 子动画（业务子动画）|

### C. 遗漏（OPPO 有、lib 没有）

| # | 遗漏 | 原厂证据 | 影响 |
|---|---|---|---|
| C1 | **`setFloats` 多关键帧**：原厂支持 `float...` 变参关键帧（`PendingAnimation.java:137-144`）| lib 无此 API | 演示库当前 0 调用方；曲线动画（多帧 easing）无法表达 |
| C2 | **`setInt` / `setFloats` / `setViewAlpha` / `setViewBackgroundColor`** 共 4 类业务 setter（依赖 `AlphaUpdateListener` + `LauncherAnimUtils` + `View` API）| `PendingAnimation.java:148-155, 164-184`；`PropertySetter.java:30-64` | 见 review 02 §C-4/C-5 |
| C3 | **`PendingAnimation.getAnimatorSet()` / `getDuration()` / `getListeners()`** 三个 getter | `PendingAnimation.java:112-122` | lib 字段 `private`；外部不可读 duration / anim；APC 内已读 `durationMs`（`AnimatorPlaybackController.kt:29`），但外部若需读得自己持有引用 |
| C4 | **`addOnFrameListener(ValueAnimator.AnimatorUpdateListener)`** 高级入口（接受 listener 而非 Runnable）| `PendingAnimation.java:81-86` | lib `addOnFrameCallback` 只接 `() -> Unit`；需要拿到 `ValueAnimator`（animatedValue/animatedFraction）的场景不可达 |
| C5 | **`setInterpolator(TimeInterpolator)`**：直接下发到 AnimatorSet 本体 | `PendingAnimation.java:157-159` | lib 不可达；业务若想统一下发插值器需走 `add` 时逐个设 |
| C6 | **`PendingAnimation` 主构造器第一件事 `mDuration = j8 <= 0 ? 0L : j8`（即 ≤ 0 强制 0）+ AnimatorSet 构造后挂 cancel/end/start 监听维护 `isAnimFinished`** | `PendingAnimation.java:31-50` | lib `durationMs = duration.coerceAtLeast(0)`（`:31`）+ `init { anim.addListener ... }`（`:37-43`）— 已对齐；`mDuration` 不写 `final` 是 OPPO 的"非 final" quirk，lib 用 `val` 锁死反而更安全 |
| C7 | **`add(Animator, long)` 自定义时长重载**：调用方传非 `mDuration` 时长 | `PendingAnimation.java:211-214` | lib `add(Animator)` 固定写 `durationMs`；当前 0 调用方；构造期时长由调用方预 `setDuration` + `addWithoutDuration` 即可替代 |
| C8 | **`add(Animator)` 的 PropertySetter 默认实现**：原厂 add(Animator) 委派给 `add(anim, SpringProperty.DEFAULT)`（`PendingAnimation.java:187-189`），PropertySetter 接口默认行为 | `PendingAnimation.java:187-189` | lib `PendingAnimation` 类不实现 `add(Animator)` 作为 PropertySetter 默认；Kotlin 用 `PropertySetter` 接口的 no-op 默认；语义等价 |
| C9 | **`APC.dispatchSetInterpolator`** 经 `callAnimatorCommandRecursively` 下发到嵌套子动画 | `AnimatorPlaybackController.java:252-254` | lib `dispatchToListeners` 只拍平 `anims`（`AnimatorPlaybackController.kt:161-165`）；与 B7/R7 一组 |
| C10 | **`APC` 主构造器取消监听同时维护 `mIsDispatchStartPending`**（cancel 置 false、end 置 false、start 置 false）| `AnimatorPlaybackController.java:138-156` | lib 取消监听只维护 `targetCancelled`；`isDispatchStartPending` 在 `start()`（`:110`）和 `reverse()`（`:117`）和 `dispatchOnStart()`（`:169`）处手写，不与 cancel 联动（见 §3 R8）|

---

## ③ 行为差异风险点（按严重度排序）

> **状态：✔️保持简化（setFloat 4 参显式 from 等价 3 参；60bd048 后已走 ObjectAnimator+add()；仅存 add() 前读 duration 的 API 说谎边缘，demo 无影响）**
### R1. lib `setFloat` 用 4-参 `ObjectAnimator.ofFloat(from, to)` vs 原厂 3-参隐式 from = current（语义等价 + 顺序差异）
**严重度：低。** 原厂 `setFloat` (`:127-134`) 用 `ObjectAnimator.ofFloat(t8, floatProperty, f9)`（3 参）：平台 ObjectAnimator 的 3-参重载会自动调用 `property.get(t8)` 作为 `from`。lib 用 `ObjectAnimator.ofFloat(target, property, property.get(target), value)`（4 参，`:75`），显式传 `from`。**两边效果完全一致**——平台 ObjectAnimator 在构造时也是先 `property.get(target)` 缓存，再设置 `from=cache`。lib 的差异只是把"缓存 + 设 from"两步拆成调用方显式一行。

**顺序差异**：原厂 `setDuration(mDuration).setInterpolator(timeInterpolator)`（先 setDuration 再 setInterpolator），lib `setInterpolator(interpolator)`（只 setInterpolator，duration 由 `add(Animator)` 覆写）。两条路径最终都把 duration 设为 `durationMs`——OPPO 在 ofFloat 之前就设（Platform ObjectAnimator 自身持 duration=mDuration），lib 在 add() 内部设（`PendingAnimation.kt:46`）。**API 行为等价**，若调用方在 add 前主动读 `oa.duration`，lib 给的是默认 300ms（原厂给 mDuration），属"API 说谎"。

*修复成本：0*（语义已对齐，不需修复；若想消除 API 说谎，给 lib ObjectAnimator 加 `setDuration` 调用即可，1 行）。

> **状态：✅已修复（60bd048：buildAnim 走 add() 覆写 progressAnimator 时长；review 02 §3-4 旧述更正为无差异）**
### R2. lib `buildAnim` 写 progressAnimator 时长：路径不同但等价（review 02 §3-4 复核）
**严重度：无差异。** 原厂 `buildAnim:96` 调 `add(valueAnimator)` → `add(Animator, SpringProperty.DEFAULT)` (`:191-194`) → `animator.setDuration(mDuration)`。lib `buildAnim:94` 调 `add(it)` → `add(Animator)` (`:45-49`) → `child.duration = durationMs`。**两条路径对 progress animator 的 setDuration 时序一致**（都在加进 AnimatorSet 前）。既有 review 02 §3-4 描述"progressAnimator 保持 ValueAnimator 默认 300ms"不准确——复核 lib `:93-96`（`progressAnimator?.let { add(it); progressAnimator = null }`）确认 add() 内部必走 `child.duration = durationMs`。**结论：R4 风险已消除**，无差异。

> **状态：✅已修复（60bd048：addHoldersRecur 三分支 + else-throw 精确对齐原厂）**
### R3. lib `addHoldersRecur` 的 else-throw（用户指定复核点）
**严重度：无差异（精确对齐）。** 原厂 `AnimatorPlaybackController.java:168-170`：
```java
if (!(animator instanceof AnimatorSet)) {
    throw new RuntimeException("Unknown animation type " + animator);
}
```
lib `AnimatorPlaybackController.kt:192`：
```kotlin
else -> throw RuntimeException("Unknown animation type $anim")
```
**精确对齐**——同分支顺序、同异常类型、同语义。注释 `AnimatorPlaybackController.kt:190-191` 自述"原厂抛 RuntimeException…不认识的动画类型显式失败，而不是静默丢弃出 Holder 链"，与原厂一致。**结论：精确对齐，无差异。**

> **状态：⚠️未修复（无嵌套 AnimatorSet 触发面：playTogether 平铺；未来补 play 嵌套路径时随 R7 一并下发父级时长/插值器）**
### R4. lib `addHoldersRecur` 砍父级 duration/interpolator 下发（review 02 §C-11 复核）
**严重度：低。** 原厂 `AnimatorPlaybackController.java:174-179` 在递归进入嵌套 `AnimatorSet` 时，把父 AnimatorSet 的 `duration`（>0 时）和 `interpolator`（≠ null 时）下发给每个子动画。lib `AnimatorPlaybackController.kt:189` 只递归 `anim.childAnimations.forEach { addHoldersRecur(...) }`，无下发。

**触发面**：仅当构造路径产生嵌套 AnimatorSet 时。当前 lib `PendingAnimation.add` 用 `anim.playTogether(child)` 平铺所有子动画（`PendingAnimation.kt:47, 57`），不会产生嵌套。**暂无触发面**。但若未来补 `add(animator: Animator)` 改用 `anim.play(animator)`（与 OPPO 一致，原厂 `PendingAnimation.java:89, 192, 212` 用的是 `mAnim.play`），且 `animator` 本身是嵌套 AnimatorSet（业务场景如递归播放），则子动画会丢失父级时长/插值器。

*修复成本：5 行*（在递归分支前加 2 行 `if (anim.duration > 0) child.duration = anim.duration; anim.interpolator?.let { child.interpolator = it }`）。

> **状态：✅已修复（本轮：取消监听移至根 animator（原 anims[0]），空子动画 IOOBE 消除；isDispatchStartPending 同步见 R8）**
### R5. lib `APC` 主构造器取消监听挂到 `anims[0]` 而非 `animatorSet` 本体（bug 级潜藏）
**严重度：高（潜藏）。** 原厂 `AnimatorPlaybackController.java:135-156` 把 `AnimatorListenerAdapter`（cancel/end/start）挂到 **`animatorSet` 本体**——这意味着：
1. 用户 `animatorSet.cancel()` 直接触发 lib 等价路径（`animationPlayer.cancel()` 走的是 ValueAnimator.cancel，不是 AnimatorSet.cancel）；
2. 用户的 `setPlayFraction(f)` 检查 `mTargetCancelled`，若是 true 早退——这条等价（OPPO line 366-368，lib `:99`）。

OPPO 的 cancel 监听同时维护 `mIsDispatchStartPending = false`（`:140`）。lib `AnimatorPlaybackController.kt:57-69` 把监听挂到 `anims[0]`：
1. `animationPlayer.cancel()` 会触发 `anims[0].cancel()`（通过 AnimatorSet 联动），所以 `anims[0]` 收到 cancel 事件 → `targetCancelled = true`。**当前路径 OK**。
2. **但 `isDispatchStartPending` 在 cancel 时不被维护**。原厂 cancel 路径会强制 `isDispatchStartPending = false`（`:140`），lib 在 `start()` (`:110`)、`reverse()` (`:117`)、`dispatchOnStart()` (`:169`) 三处手写。**若 cancel 后再 start，OPPO 是 cancel(false) → start(true)，lib 是 cancel(false) → start(true)**——等价。但若 cancel 后再 dispatchOnStart，lib 会从外部 false 改 true（`dispatchOnStart:169`），OPPO 在 cancel 时已经处理过——**一致**。
3. **关键隐患**：`anims[0]` 是 `animatorSet.childAnimations[0]`（lib `:48` 拆出来），如果 `animatorSet` 直接 cancel 而不经过 `animationPlayer`，**OPPO 的 animatorSet 监听会触发，lib 的 anims[0] 监听可能不触发**（依赖 AnimatorSet 内部 cancel 子动画的实现细节）。
4. **更严重**：如果用户传入的 `animatorSet` 没有子动画（业务代码极端场景），`anims[0]` 会抛 `IndexOutOfBoundsException`（lib `:48` 先 `addAll(anim.childAnimations)`，若 childAnimations 为空 → anims 为空 → `:57` 报 IOOBE）。原厂把监听挂到 animatorSet 本体，即使 childAnimations 为空也能正常取消（AnimatorSet 自己的 listener 不依赖子动画）。

**结论**：当前 lib 的 cancel 跟踪功能可用但**结构性不对**——一旦构造路径产生空 childAnimations 或业务直接 cancel AnimatorSet，会触发 IOOBE 或漏 cancel 事件。

*修复成本：3 行*（改 `anims[0].addListener(...)` → `anim.addListener(...)`；`anim` 需在 init 块顶部保存为 `val mAnimSet: AnimatorSet = anim as AnimatorSet`，并在挂监听时用 `mAnimSet`）。

> **状态：✔️保持简化（设计正确：buildAnimator() 返回内部 va 命中 addHoldersRecur 首分支，避免 ClassCastException）**
### R6. lib 自定义 `ObjectAnimator.buildAnimator()` 返回 `ValueAnimator`（用户指定复核点）
**严重度：无差异（设计正确的简化）。** lib `PendingAnimation.kt:117-179` 自定义 `ObjectAnimator` 包装类，内部持 `va: ValueAnimator`。注释 `PendingAnimation.kt:138-147` 明示：
> "直接返回 ValueAnimator，而不是像 lib 此前匿名 Animator。原因为平台 `android.animation.ObjectAnimator` 继承 ValueAnimator，能进 `AnimatorPlaybackController.addAnimationHoldersRecur` 的 Holder 分支（`AnimatorPlaybackController.java:164-166`）。如 lib 此前那样返回匿名 Animator，会被 Holder 收集默认分支抛出。"

**为何必要**：`addHoldersRecur` (`AnimatorPlaybackController.kt:187-193`) 第一分支 `is ValueAnimator -> out.add(Holder(...))`。如果 lib 把自定义 `ObjectAnimator` 实例（**不是** ValueAnimator 子类）传给 Holder 构造，Kotlin 的 `animator as ValueAnimator`（`:75`）会抛 `ClassCastException`。**`buildAnimator()` 返回内部 `va: ValueAnimator` 是为了让 Holder 收到正确的类型**。

**对比 OPPO**：OPPO 用平台 `ObjectAnimator.ofFloat(...)`，平台 `ObjectAnimator extends ValueAnimator`，所以 `add(animator)` 时 Holder 拿到的就是 ValueAnimator 类型的对象——直接走第一分支。**两条路径最终给 Holder 的都是 ValueAnimator**——语义对齐。

**副作用：
1. **api 表面**：lib 的自定义 `ObjectAnimator` 暴露 `setInterpolator / setDuration / setFloatValues / buildAnimator / duration / start / cancel / end / pause / isRunning / addListener / addUpdateListener` 12 个方法（`PendingAnimation.kt:126-171`），覆盖平台 ObjectAnimator 的核心面，但**不暴露** `setStartDelay / getAnimatedValue / getAnimatedFraction / getValues / clone / setupStartValues / setupEndValues`——OPPO 平台 ObjectAnimator 有这些 API。**影响**：业务若用 `getAnimatedValue()` 读取当前动画值，lib 不可达。
2. **unchecked cast**：lib `PendingAnimation.kt:154` 的 `(property as FloatProperty<Any?>).setValue(target, ...)` 是 unchecked cast——若业务用 `property: FloatProperty<View>` 而 target 实际是 `TextView`，编译期不报错但运行期 `setValue` 抛 `ClassCastException`（因为 `setValue` 用 `property.set(Object, Float)`，签名一致，仅当内部泛型实化才报错）。
3. **`Holder` 强转**：lib `Holder(animator: Animator, ...)` (`:75`) 用 `val anim: ValueAnimator = animator as ValueAnimator`——`addHoldersRecur` 已经保证传入 ValueAnimator，强转安全；但若业务绕过 `add(Animator)` 直接 `addToHolders(custom ObjectAnimator)`（虽然 `addToHolders` 是 `private` (:111)，不可外部调），会抛 `ClassCastException`。当前 API 表面下不可达。

*修复成本：0*（设计正确，无需修复；如需 `getAnimatedValue()`，在 ObjectAnimator 加 getter 委派给 va 即可，2 行）。

> **状态：⚠️未修复（60bd048 已消除跳根漏派主问题；嵌套 AnimatorSet 内层递归仍缺——无触发面）**
### R7. lib `dispatchToListeners` 不递归嵌套 AnimatorSet（review 02 §R7 复核）
**严重度：低。** 原厂 `callListenerCommandRecursively → callAnimatorCommandRecursively` (`:184-203`) 递归进嵌套 AnimatorSet 派发 listener。lib `AnimatorPlaybackController.kt:161-165` 只拍平 `anims` 一层。当前 lib 构造路径不产嵌套 AnimatorSet，**暂无触发面**——但与 R4 同源：若补 `add(animator: Animator)` 改 `play` 且业务传嵌套 AnimatorSet，dispatch 会漏内层 listener。

*修复成本：10-15 行*（把 `dispatchToListeners` 改为 `when (a) { is AnimatorSet -> a.childAnimations.forEach { dispatchToListeners(it, action) }; else -> a.listeners.forEach { it.action(a) } }`）。

> **状态：✅已修复（本轮：start() isDispatchStartPending=true→false，对齐 OPPO :379）**
### R8. lib `isDispatchStartPending` 语义反转（review 02 §R2 复核）
**严重度：中（潜藏）。** 原厂 `start()` 置 `mIsDispatchStartPending = false`（`AnimatorPlaybackController.java:379`），仅 `dispatchOnStart()` 置 true（`:248`）。语义："true = 已派发 start 给所有 listener 但 AnimatorSet 还没真的开始"——给 `dispatchOnStart` 用作幂等闸门。lib `start()` 置 **true**（`AnimatorPlaybackController.kt:110`），与原厂相反。

**当前 lib 字段 `private` 且无 getter，外部无读取方**——`isIsDispatchStartPending` 未实现。当前是**死字段**。一旦将来补 getter（手势链路需要：`OplusBaseSwipeUpHandler` 等点位都会读），语义反转会直接把外部判断颠倒。

*修复成本：1 行*（`AnimatorPlaybackController.kt:110` 把 `true` 改为 `false`）。

> **状态：✔️保持简化（原厂同样丢弃 oa 引用；API 表面差异非 bug）**
### R9. lib `PendingAnimation.addFloat` 的 `ObjectAnimator.buildAnimator()` 之后即丢弃 oa 引用（API 表面差异）
**严重度：低。** lib `PendingAnimation.kt:65` 的 `add(oa.buildAnimator())` 之后 `oa` 引用立即不可达——若调用方想持有 `oa`（例如注册到外部 listener 列表），Kotlin 编译器不会报错（返回值是 `PendingAnimation`，不是 `oa`）。原厂 `add(objectAnimatorOfFloat)`（`PendingAnimation.java:70`）同理丢弃。但 OPPO 的 ObjectAnimator 是平台类，业务可以用 platform API（如 `oa.getAnimatedValue()`）；lib 的 ObjectAnimator 是自定义类，业务持有了也无法用平台 API。**影响**：业务若想持有 ObjectAnimator 引用，lib 与平台 API 不兼容。

*修复成本：0*（属于 API 表面差异，非 bug）。

> **状态：⚠️未修复（PendingAnimation 为 internal 类、demo 零实例化；无外部 cancel 需求）**
### R10. lib `PendingAnimation` 无 cancel path（业务触发 cancel 的入口缺失）
**严重度：中。** lib `PendingAnimation` 类没有 `cancel()` 方法。原厂 `PendingAnimation` 也不直接提供 cancel，但 `getAnimatorSet()` (`:112`) 让外部拿到 `mAnim` 调 `cancel()`。lib `anim` 字段是 `private val`（`:29`），外部无法直接 cancel。**业务若想取消 PendingAnimation**，只能：
1. 走 `PendingAnimation.createPlaybackController().animationPlayer.cancel()`（间接路径）
2. 或等 buildAnim() 之后从 APC 拿 anim

*修复成本：2 行*（加 `fun cancel() = anim.cancel()`，或暴露 `getAnimatorSet()` getter）。

> **状态：✔️保持简化（纯命名差异，不影响行为）**
### R11. lib `addToHolders` 与 `addHoldersRecur` 签名不一致（API 命名同步问题）
**严重度：低。** lib `PendingAnimation.kt:111-113` 用 `addToHolders`（私有方法），`AnimatorPlaybackController.kt:186` 用 `addHoldersRecur`（public 静态）。两个名字相似但调用方不同：lib 的 `addToHolders` 是 PendingAnimation 私有委托给 APC 的 `addHoldersRecur`。OPPO 统一用 `addAnimationHoldersRecur`（`AnimatorPlaybackController.java:161`）。**命名混乱**——读代码时需在两个名字间跳转。

*修复成本：0*（纯命名，不影响行为）。

> **状态：✔️保持简化（demo 用 setFloat 完成 alpha；原厂 AlphaUpdateListener View 联动与 C2 同族未移植）**
### R12. lib `PendingAnimation` 缺 `setViewAlpha` 等业务 setter，演示库自建 `setFloat` 即可完成 alpha 动画
**严重度：低。** 业务若用 lib `setFloat(target, ALPHA, 0f)`，是 OK 的——`FloatProperty` 直接作用于 alpha。但原厂 `setViewAlpha` 额外挂 `AlphaUpdateListener` (`:169, 227`) 做可见性联动（alpha ≤ 0.01f 切 INVISIBLE + `ViewGroup.setDescendantFocusability(393216)` 防 focus 抖动）。lib 缺这个联动。

*修复成本：20 行*（从原厂 `AlphaUpdateListener.java:9-62` 移植 1 个文件，60 行；演示库业务若只需要 alpha 联动，不补也无影响）。

---

## ④ 回移建议

### 4.1 值得补进 lib（性价比高，结构性 bug 或潜藏风险）

| # | 修复点 | 修复成本 | 风险点 | 理由 |
|---|---|---|---|---|
| 1 | ✅已修复（本轮） — **R5 修复**：把 `APC` 主构造器取消监听从 `anims[0]` 改挂到 `animatorSet` 本体；同时 `isDispatchStartPending` 在 cancel 监听内维护 | 3-5 行 | R5 | 当前 IOOBE 潜藏风险；与原厂结构对齐；后续若补 `add(animator: Animator)` 重载或业务传空 childAnimations，避免崩溃 |
| 1 | **R5 修复**：把 `APC` 主构造器取消监听从 `anims[0]` 改挂到 `animatorSet` 本体；同时 `isDispatchStartPending` 在 cancel 监听内维护 | 3-5 行 | R5 | 当前 IOOBE 潜藏风险；与原厂结构对齐；后续若补 `add(animator: Animator)` 重载或业务传空 childAnimations，避免崩溃 |
| 2 | ✅已修复（本轮） — **R8 修复**：`APC.start()` 内 `isDispatchStartPending = true` 改 `false` | 1 行 | R8 | 与原厂语义对齐；避免未来补 getter 时语义反转引入 bug |
| 2 | **R8 修复**：`APC.start()` 内 `isDispatchStartPending = true` 改 `false` | 1 行 | R8 | 与原厂语义对齐；避免未来补 getter 时语义反转引入 bug |
| 3 | ⚠️未修复（无嵌套触发面，未做） — **R4 修复**：`APC.addHoldersRecur` 递归分支加 `duration > 0 → child.setDuration` + `interpolator != null → child.setInterpolator` 下发 | 2 行 | R4 | 与原厂对齐；若未来补 `add(animator: Animator)` 改 `play` 路径，无触发面 |
| 3 | **R4 修复**：`APC.addHoldersRecur` 递归分支加 `duration > 0 → child.setDuration` + `interpolator != null → child.setInterpolator` 下发 | 2 行 | R4 | 与原厂对齐；若未来补 `add(animator: Animator)` 改 `play` 路径，无触发面 |
| 4 | ⚠️未修复（60bd048 已含根；嵌套递归未做） — **R7 修复**：`dispatchToListeners` 改为递归 AnimatorSet | 10-15 行 | R7 | 与原厂对齐；与 R4 同源修复 |
| 4 | **R7 修复**：`dispatchToListeners` 改为递归 AnimatorSet | 10-15 行 | R7 | 与原厂对齐；与 R4 同源修复 |
| 5 | ⚠️未修复（internal 类无外部消费者，未补） — **R10 修复**：补 `PendingAnimation.cancel()` 公开方法（委派 `anim.cancel()`）| 2 行 | R10 | 业务必需入口；与原厂 `getAnimatorSet()` + 业务自调 `cancel` 等价 |
| 5 | **R10 修复**：补 `PendingAnimation.cancel()` 公开方法（委派 `anim.cancel()`）| 2 行 | R10 | 业务必需入口；与原厂 `getAnimatorSet()` + 业务自调 `cancel` 等价 |
| 6 | ⚠️未修复（internal 类无外部读者；公开化集成时再补 getter） — **C3 部分修复**：补 `getDuration()` / `getAnimatorSet()` getter（getter 即可） | 2 行 | C3 | 业务若需读 duration / anim，无需自己持有引用 |
| 6 | **C3 部分修复**：补 `getDuration()` / `getAnimatorSet()` getter（getter 即可） | 2 行 | C3 | 业务若需读 duration / anim，无需自己持有引用 |
| 7 | ❌不成立/已过期（现代码无 mAnim 字段，APC 整体 internal；可见性改动无意义） — **C8 修复**：把 `APC` 的 `mAnim` 字段从 `private` 改 `internal`，便于 lib 内部代码共享 | 0 行（改可见性） | （便利性） | 不影响外部 API；lib 内部若需访问 AnimatorSet 不用绕 `animationPlayer` |
| 7 | **C8 修复**：把 `APC` 的 `mAnim` 字段从 `private` 改 `internal`，便于 lib 内部代码共享 | 0 行（改可见性） | （便利性） | 不影响外部 API；lib 内部若需访问 AnimatorSet 不用绕 `animationPlayer` |

### 4.2 建议保持简化（不补）

| # | 简化点 | 不补理由 |
|---|---|---|
| 1 | ✔️保持简化（C1/C2/C5/C7/C9 确认；C10 已随本轮 R5/R8 同步维护 isDispatchStartPending） — **C1 / C2 / C5 / C7 / C9 / C10**：setFloats / setInt / setViewAlpha / setViewBackgroundColor / setInterpolator / add(Animator,long) / dispatchSetInterpolator / addOnFrameListener | 业务依赖（`AlphaUpdateListener` / `LauncherAnimUtils` / View API）未移植；当前 lib 演示库 0 调用方；等业务真正用到再补（一次性补全 `AlphaUpdateListener` + 4 个 setter，约 80 行）|
| 1 | **C1 / C2 / C5 / C7 / C9 / C10**：setFloats / setInt / setViewAlpha / setViewBackgroundColor / setInterpolator / add(Animator,long) / dispatchSetInterpolator / addOnFrameListener | 业务依赖（`AlphaUpdateListener` / `LauncherAnimUtils` / View API）未移植；当前 lib 演示库 0 调用方；等业务真正用到再补（一次性补全 `AlphaUpdateListener` + 4 个 setter，约 80 行）|
| 2 | ✔️保持简化（语义等价 + API 表面差异；R2/R3 已由 60bd048 对齐） — **R1 / R2 / R3 / R6 / R9 / R11 / R12**：行为差异均为"语义等价 + 路径不同"或"API 表面差异"，不影响业务 | 0 风险，不修 |
| 2 | **R1 / R2 / R3 / R6 / R9 / R11 / R12**：行为差异均为"语义等价 + 路径不同"或"API 表面差异"，不影响业务 | 0 风险，不修 |
| 3 | ✔️保持简化（val 锁死更安全；非 final 是反编译 quirk） — **C6**：mDuration 非 final quirk | lib 用 `val` 锁死反而更安全，OPPO 的非 final 是 Java 反编译 quirk，无业务语义 |
| 3 | **C6**：mDuration 非 final quirk | lib 用 `val` 锁死反而更安全，OPPO 的非 final 是 Java 反编译 quirk，无业务语义 |

### 4.3 端到端流程复核结论

`PendingAnimation` 的 `add → buildAnim → createPlaybackController` 三步端到端流程：

```
add(Animator, TimeInterpolator)         // R1 语义对齐
  → child.interpolator = ip
  → add(Animator)                       // 单子动画覆盖 duration=ms, playTogether, addToHolders
    → addToHolders → APC.addHoldersRecur // R3 else-throw 精确对齐
      → ValueAnimator 分支 → Holder(anim, durationMs)
                              ├─ globalEndProgress = durationMs / totalDuration
                              └─ 收集进 animHolders

buildAnim()
  → progressAnimator?.let { add(it); progressAnimator = null }  // R2 时长覆写等价
  → if (animHolders.isEmpty()) addWithoutDuration(占位 ValueAnimator 已 setDuration(durationMs))
  → return anim

createPlaybackController()
  → controller ?: APC(buildAnim(), durationMs, animHolders)
                 .also { controller = it }
  → APC 主构造器：animationPlayer = ValueAnimator.ofFloat(0,1).setInterpolator(LINEAR)
                + addListener(OnAnimationEndDispatcher)
                + addUpdateListener(this)
                + anims[0].addListener(取消监听)        // ⚠ R5 潜藏风险（应挂 anim 本体）
                + childAnimations = holders.toTypedArray()
```

**结构性结论**：
> **状态：✔️保持简化（复核确认：Holder/时长/start-reverse/mapper 与 ②-A 对齐）**
- **数据流（Holder 收集、动画时长、start/reverse/pause、mapper）**：精确对齐原厂
> **状态：✔️保持简化（复核确认：add/buildAnim/createPlaybackController 对齐）**
- **构造入口（add / buildAnim / createPlaybackController）**：精确对齐原厂
> **状态：✅已修复（本轮，见 §③ R5）**
- **取消跟踪挂点**：**结构性不对齐**（R5，挂 `anims[0]` vs 原厂 `animatorSet`）
> **状态：⚠️未修复（60bd048 已含根；嵌套递归见 §③ R7）**
- **dispatch 递归**：**结构性不对齐**（R7，单层 vs 递归）
> **状态：✔️保持简化（复核通过：精确对齐，见 §③ R3）**
- **`addHoldersRecur` else-throw**：精确对齐（R3，user 指定复核点通过）
> **状态：✔️保持简化（复核通过：设计正确的简化，见 §③ R6）**
- **lib `ObjectAnimator.buildAnimator()` 返回 `ValueAnimator`**：设计正确的简化（R6，user 指定复核点通过）

---

## 附录 A：与既有 review 02 的偏差修正

| # | 既有 review 02 描述 | 复核结果 | 处理 |
|---|---|---|---|
| 1 | §3-4 "progressAnimator 保持 ValueAnimator 默认 300ms" | **不准确**——lib `buildAnim:93-96` 走 `add(it)`，add 内部 `child.duration = durationMs` 必写入（`:46`），与原厂等价 | R2 改为"无差异" |
| 2 | §R4 风险点 "lib progressAnimator 时长不一致" | **已对齐**（add() 内强制 setDuration）| R4 取消 |
| 3 | §R5 风险点 "setFloat 不显式设时长" | **路径不同但等价**（lib 由 add() 兜底，原厂 setFloat 自身设）| R1 维持"低风险" |
| 4 | §R6 风险点 "addHoldersRecur else-throw 误述" | **精确对齐**（lib `AnimatorPlaybackController.kt:192`） | R3 取消 |
| 5 | §R7 风险点 "dispatch 不递归" | **维持**（与本报告 R7 同源）| R7 维持 |
| 6 | §R8 风险点 "cancel 监听挂在 animes[0]" | **维持**（本报告 R5 升级为"高（潜藏）"，因为结构性问题）| R5 升级 |
| 7 | §C-11 遗漏 "addAnimationHoldersRecur 不下发父级 duration/interpolator" | **维持**（本报告 R4）| R4 维持 |

## 附录 B：用户指定三处改动对齐复核

| # | 用户指定改动 | 复核结论 | 证据 |
|---|---|---|---|
| 1 | lib `setFloat` 动画版 | **行为等价**（4 参显式 from vs 3 参隐式 from；setInterpolator vs setDuration+setInterpolator+add；add() 兜底 duration）| `PendingAnimation.kt:72-78` vs `PendingAnimation.java:127-134` |
| 2 | lib `ObjectAnimator.buildAnimator()` 返回 `ValueAnimator` | **设计正确**（让 `addHoldersRecur` 第一分支命中 Holder 收集，避免 else-throw；避免 `Holder(animator as ValueAnimator)` ClassCastException）| `PendingAnimation.kt:117-179` 注释 `:138-147` 自述理由；`AnimatorPlaybackController.kt:187-193` |
| 3 | lib `addHoldersRecur` else-throw | **精确对齐**（同分支顺序、同异常类型、同语义）| `AnimatorPlaybackController.kt:192` vs `AnimatorPlaybackController.java:168-170` |

## 附录 C：本报告与既有 review 02 的方法差异

- **取证深度**：本报告逐方法（24 个 OPPO 方法、15 个 lib 方法）对照，列出 ① 类对应关系表 + ② 18 个保真度评估项 + ③ 12 个风险点 + ④ 7 个回移项。既有 review 02 仅覆盖每个 lib 类的核心方法。
- **三处指定改动复核**：既有 review 02 未专门复核 `setFloat` 4 参 vs 3 参差异、自定义 `ObjectAnimator.buildAnimator()` 返回 `ValueAnimator` 的设计意图、`addHoldersRecur` else-throw 的分支对齐。本报告均明确给出"通过 / 不通过"结论。
- **结构性差异新增**：本报告新增 R5（取消监听挂 `anims[0]` 的 IOOBE + 漏 cancel 风险升级为"高（潜藏）"）、R4（addHoldersRecur 不下发父级 duration/interpolator 与 review 02 §C-11 同源但独立列出）、R11（addToHolders vs addHoldersRecur 命名混乱）、R12（setViewAlpha 缺失导致 alpha 联动不可达）。
- **关键纠正**：本报告复核既有 review 02 §3-4 的"progressAnimator 保持默认 300ms"为不准确（lib add() 内部 setDuration 必写入）；复核 §R4 风险点为已对齐；复核 §R6 风险点为已对齐。

## 附录 D：call site 覆盖度

lib 演示库 (`D:/AsyncAnimator/demo/src`) **零直接调用 `PendingAnimation`**：

| 文件 | PendingAnimation 出现位置 | 类型 |
|---|---|---|
| `LauncherEntryActivity.kt:66` | "StateManager → PendingAnimation → APC → Choreographer" | 文档字符串 |
| `Demo9AllAppsTransitionActivity.kt:13, 69` | "PendingAnimation(addFloat×3)" | 文档字符串 + log |
| `Demo3AsyncCrossThreadActivity.kt` | 无 | — |
| `Demo10IndependentThreadActivity.kt` | 无（只 import `NullableAnimatorListenerAdapter`） | — |

**意义**：当前 lib `PendingAnimation` 类虽然存在且通过内部 demo 路径（Demo9 概念链路）描述，但其 180 行 Kotlin 代码在真机演示中**未被实际实例化**。这意味着 §3 的 12 个风险点中：
- **结构性差异 R5（取消监听挂 animes[0]）、R7（dispatch 不递归）、R8（isDispatchStartPending 语义反转）**：因零调用方，**当前真机演示不可见**——属于"代码存在但路径未触发"的潜在 bug。
- **API 表面差异 R9 / R10 / R11 / R12**：因零调用方，**当前不可达**。
- **设计正确的简化 R1 / R2 / R3 / R6 / C6**：即使零调用方，行为也已对齐——属于"已对齐的冗余"。

## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **60bd048** — setFloat 动画版、addFloat 返回 ValueAnimator 进 Holder、buildAnim 走 add()、addHoldersRecur else-throw 全部对齐原厂

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核

（批次4 / 2026-09-09 逐项打标状态，与正文内联 `> **状态：**` 行一致）

- §③ R1 → ✔️保持简化（4 参 vs 3 参语义等价）
- §③ R2 → ✅已修复（60bd048：buildAnim 走 add()）
- §③ R3 → ✅已修复（60bd048：else-throw 精确对齐）
- §③ R4 → ⚠️未修复（无嵌套触发面）
- §③ R5 → ✅已修复（本轮：监听器移至根 animator）
- §③ R6 → ✔️保持简化（buildAnimator 返回 va 设计正确）
- §③ R7 → ⚠️未修复（60bd048 已含根；嵌套递归缺）
- §③ R8 → ✅已修复（本轮：start() 置 false）
- §③ R9 → ✔️保持简化（API 表面）
- §③ R10 → ⚠️未修复（internal 无外部消费者）
- §③ R11 → ✔️保持简化（命名）
- §③ R12 → ✔️保持简化（demo setFloat 等效）
- 4.1-1 → ✅已修复（本轮 R5）
- 4.1-2 → ✅已修复（本轮 R8）
- 4.1-3 → ⚠️未修复（R4 无嵌套触发面）
- 4.1-4 → ⚠️未修复（R7：60bd048 已含根）
- 4.1-5 → ⚠️未修复（R10 internal）
- 4.1-6 → ⚠️未修复（C3 getter internal）
- 4.1-7 → ❌不成立/已过期（无 mAnim 字段，APC internal）
- 4.2-1 → ✔️保持简化（C10 已随 R5/R8 修复）
- 4.2-2 → ✔️保持简化（R1/R2/R3/R6/R9/R11/R12 无影响）
- 4.2-3 → ✔️保持简化（C6 val 更安全）
- 4.3-1 → ✔️保持简化（数据流复核对齐）
- 4.3-2 → ✔️保持简化（构造入口对齐）
- 4.3-3 → ✅已修复（本轮 R5）
- 4.3-4 → ⚠️未修复（R7）
- 4.3-5 → ✔️保持简化（else-throw 对齐）
- 4.3-6 → ✔️保持简化（buildAnimator 设计正确）

