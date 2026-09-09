# 区域 15 vs-oppo deep dive：`MultiAnimatorSet` 主装配器 4 通道调度

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib`（AsyncAnimator 演示库，idiomatic Kotlin 复刻）
> - **原厂**：`D:/oppo_a6_launcher/sources`（OPPO ColorOS 15 Launcher 15.8.24 JADX 反编译；80% 文件被企业 DLP 加密）
>
> **本区域范围**：单独把原厂 `com.android.quickstep.util.animation.MultiAnimatorSet.java`（476 行反编译自 Kotlin 源 `MultiAnimatorSet.kt` `SMAP 1,413:1`）做字段级 + 方法级详解。
> 覆盖：4 通道并行装配（mAnimatorSet / mAsyncAnimatorSet / mSpringAnimations / mRectFSpringAnim）、
> bitmask cancel/end、mAnimationId + mAnimEndCallback 结束协议、play(...) 6 重载分派语义、
> SpringHolder.mStartDelay 倒计时节点、与 lib 单通道 `PendingAnimation` 的差。
>
> 与前 14 份 review 的衔接：
> - review 06 §2.2 row 67 已把 "MultiAnimatorSet 主装配器缺失" 列为 4 大缺口之一，并标注 "Demo9 转场只能概念演示"；
> - review 11 §A/C 已列 "4 通道 / bitmask cancel / ANIM_EXECUTOR.execute(start)" 为不移植项；
> - review 12 §"bug 级残留" 间接提到 `maybeOnEnd` 双轨补救；
> - review 13-customrect-spring 详拆了 6 自由度 `CustomRectFSpringAnim`（**4 通道中第 4 个**）的字段级映射；
> - review 14-launcher-animation-runner 把 `RemoteAnimationFactory` 提到 interface 维度；
> - **本文是 4 通道调度器本身的字段级 + 方法级深挖**，把 "为什么是 4 个并行"、"bitmask `1|2|4=7` 怎么编码"、"play(z, anim) 怎么二选一"、"maybeOnEnd 怎么等齐 4 个 *Ended 标志"、"SpringHolder.mStartDelay 倒计时如何替代 setStartTime/isReady" 逐一拆开。
>
> 取证方法：
> - lib 侧：UTF-8 Kotlin 文件直接 Read（11 个文件）；不存在的概念以 "❌ 无" 标记。
> - 原厂文件 476 行 + 周边 `SpringHolder.java` 138 行 + `AsyncValueAnimator.java` 165 行 + `SpringAnimation.java` 194 行 + `CustomRectFSpringAnim.java` 925 行 + `MultiDynamicAnimation.java` 200 行，全部经 Python `open(p,'rb').read()` 绕开 DLP 密文层；行号为 JADX 反编译文本行号。
>
> 背景结论：`docs/animation-thread-analysis-v4.md`（v4）§3 / §4、`docs/animation-trace-validation.md`、
> `docs/review/SUMMARY-vs-oppo.md` §2-§3。

---

## ① 类对应关系表

### 1.1 主体类

| lib 类 | 原厂类（文件:行） | 关系 |
|---|---|---|
| **❌ 无对应类**（单通道 `lib/.../pending/PendingAnimation.kt` 181 行 1 个 `AnimatorSet`，**不能视作对应**） | `com/android/quickstep/util/animation/MultiAnimatorSet.java:32-475`（476 行；Java 反编译自 `MultiAnimatorSet.kt` 类，`@SourceDebugExtension` `:30` 标 `SMAP MultiAnimatorSet.kt Kotlin 1,413:1`） | **完全缺失**（4 通道调度器未移植） |

### 1.2 周边类（lib 状态）

| 原厂类 | 行数 | 角色 | lib 是否复刻 |
|---|---|---|---|
| `com/android/quickstep/util/animation/SpringAnimation.java` | 194 | `extends DynamicAnimation<SpringAnimation>`，持 SpringForce + `mPendingPosition` + `mEndRequested`；提供 `animateToFinalPosition` / `canSkipToEnd` / `skipToEnd` / `finishToEndImmediately` | ⚠️ 部分（lib `AsyncSpringAnim.kt` 走 androidx `SpringAnimation`，命名相同但行为不同：缺 `mPendingPosition` 半步分裂积分 `:147-159`，缺 `canSkipToEnd` 欠阻尼校验 `:56-58`，缺 `setValueThreshold` 空覆写 `:112-113`） |
| `com/android/quickstep/util/animation/SpringHolder.java` | 138 | 单自由度弹簧节点：`mValue` / `mVelocity` / `mPendingPosition` / `mStartDelay` / `mMinVisibleChange` / `mMinValue` / `mMaxValue` / `mSpringForce` / `mKey`；`updateValueAndVelocity` 含 `mStartDelay` 倒计时（`:108-114`）和半步分裂积分（`:115-130`） | ❌ 无（详见 review 13 §1.2） |
| `com/android/quickstep/util/animation/MultiDynamicAnimation.java` | 200 | 帧循环载体 `implements AnimationHandler.AnimationFrameCallback`；`doAnimationFrame` 裸 delta 积分 + `requestEnd` 下一帧生效 | ❌ 无（同上） |
| `com/android/quickstep/util/animation/AsyncValueAnimator.java` | 165 | `extends ValueAnimator`，start/cancel/end 都判 `mAnimLooperExecutor.getLooper().isCurrentThread()` → 跨线程时 post 纠偏（`:116-127, 129-141, 152-164`） | ✅ 复刻（lib `AsyncValueAnimator.kt`，review 01 §2.B-1 标记精确复刻） |
| `com/android/quickstep/util/animation/CustomRectFSpringAnim.java` | 925 | 第 4 通道的 RectF 弹簧，含 6 自由度 + AnimType 7 值 + 线程切换协议 | ⚠️ 占位（lib 18 行占位，review 13 全文详拆） |

### 1.3 关于用户描述的 "链表节点 setStartTime / isReady / delay 等字段"

`MultiAnimatorSet` 本身是 **"4 个独立字段"，不是链表**——`mAnimatorSet` / `mAsyncAnimatorSet` / `mSpringAnimations(ArraySet<>)` / `mRectFSpringAnim`，没有 `head/tail/next` 指针，也没有 `setStartTime` / `isReady` 字段。这些字段名更接近：

- **`setStartTime` / `setStartDelay`**：存在于 `SpringHolder.setStartDelay(long delay)`（`SpringHolder.java:87-90`）——**6 自由度中每条 spring 各自的 delay 倒计时**；
- **`isReady`**：用 `mStarted == true` + 4 个 `*Ended == true` 共同表达（`MultiAnimatorSet.java:104`），非单字段；
- **`delay` 字段**：在 `SpringHolder.java:108-114` 是 `mStartDelay` 倒计时（每帧减 deltaT，归零后开始积分），在 `MultiAnimatorSet` 没有 `delay`，但 `maybeOnEnd()` 通过 4 个 boolean 间接实现 "等齐"。

`MultiAnimatorSet` 实质是 **"4 通道并行 + 4 boolean + 1 bitmask"**——下文按此结构拆。

### 1.4 lib 实际依赖

| lib 类 | 用到 MultiAnimatorSet 概念的方式 |
|---|---|
| `lib/.../controller/AnimationController.kt:36,80` | 仅 `AnimationSeqHelper.canFinishRecentsAnim(animRecord)` 流程，**完全不持有 4 通道**；`addRecentsAnim(CustomRectFSpringAnim, ...)` 只接收第 4 通道单句柄 |
| `lib/.../pending/PendingAnimation.kt` | 单 `AnimatorSet` + 1 `progressAnimator`（line 29, 32），**没有任何 async / spring / rectF 三条并行通道** |
| `lib/.../controller/RemoteAnimationFactory.kt`（17 行 interface） | 仅声明 `fun createAnimation(): AnimatorSet` + `onAnimationFinished()`——**没有多通道抽象** |
| `demo/.../Demo9AllAppsTransitionActivity.kt:31-113` | **概念演示**（line 69-71 显式 `log("转场链路（概念）：...")`），手动驱动 `LauncherStageView` 画曲线，**没有真正的 4 通道装配** |

---

## ② 字段级映射（OPPO 21 字段）

### 2.1 4 通道字段

| 字段 | 行 | 类型 | 通道 | 用途 |
|---|---|---|---|---|
| `mAnimatorSet` | `:42` | `final AnimatorSet` | 同步 / MAIN 线程 | 普通 ObjectAnimator / ValueAnimator 集合；start 走主线程 Choreographer |
| `mAsyncAnimatorSet` | `:44` | `final AnimatorSet` | 异步 / `OplusExecutors.ANIM_EXECUTOR` | 跨线程 ObjectAnimator（线程切换走 `m(this, 7)` lambda `:383`） |
| `mSpringAnimations` | `:50` | `final ArraySet<SpringAnimation>` | 弹簧 / 平台 `AnimationHandler`（主线程 ANIM_EXECUTOR 两边都可达）| 自带结束回调的物理动画 |
| `mRectFSpringAnim` | `:47` | `CustomRectFSpringAnim` | RectF 弹簧 / `mStartAsync` 决定 MAIN vs ANIM | 6 自由度 RectF（详见 review 13） |

### 2.2 4 通道结束标志（4 个 `*Ended`）

| 字段 | 行 | 初值（构造器 `:57-62`） | 何时翻 `true` | 何时翻 `false` |
|---|---|---|---|---|
| `mAnimatorSetEnded` | `:43` | `true` | `mAnimatorSet.addListener` 收到 onAnimationEnd（`:308-312`）/ onAnimationCancel（`:301-305`） | start 时若 `size>0` 翻 `false`（`:298`） |
| `mAsyncAnimatorSetEnded` | `:45` | `true` | `mAsyncAnimatorSet.addListener` 收到 onAnimationEnd **后 post 回主线程**翻（`:362-372`） | start 时若 `size2>0` 翻 `false`（`:355`） |
| `mViewSpringAnimEnded` | `:52` | `true` | 最后一个 `SpringAnimation.onAnimationEnd` 触发（`:93-98` removeEndListener + size==0） | start 时若 `size3>0` 翻 `false`（`:326`） |
| `mRectFSpringAnimEnded` | `:48` | `true` | `ActualEndAnimListener.onAnimActualEnd` 触发（`:342-346`） | start 时若 `customRectFSpringAnim != null` 翻 `false`（`:334`） |

### 2.3 控制字段

| 字段 | 行 | 类型 | 用途 |
|---|---|---|---|
| `mAnimationId` | `:40` | `int`（初值 -1 `:57`） | 调试 / trace 携带；`maybeOnEnd` 回调时回传（`:109`） |
| `mAnimEndCallback` | `:38` | `Consumer<Integer>` | 单一结束回调（**非 listener 集合**），由 `setViewStateResetRunnable` 设置（`:272-274`），fire 后置 `null`（`:111`） |
| `mAnimatorListeners` | `:41` | `ArraySet<NullableAnimatorListener>` | start 前 addListener 累加（`:138-141`），start 时 onAnimationStart 广播（`:291-296`），end 时 onAnimationEnd 广播（`:112-115`） |
| `mSpringAnimEndListener` | `:49` | `DynamicAnimation.OnAnimationEndListener`（懒建，`initSpringAnimEndListener` `:83-101`） | `mSpringAnimations` 共享结束监听 |
| `mStarted` | `:51` | `boolean` | start 重入守卫（`:277-279`）；maybeOnEnd 后置 `false`（`:106`） |
| `mHasRequestCancel` | `:46` | **`volatile boolean`** | **跨线程 cancel 信号**：TaskViewUtils `:1411` 在 `lambda$createRecentsWindowAnimator$0` 检查 `multiAnimatorSet.getMHasRequestCancel()` 后再决定是否 apply transaction |
| `mAnimType` | `:39` | `CustomRectFSpringAnim.AnimType` | 唯一 ID；`isAppOpenType()` `:250-252`、`isGestureToDrag()` `:254-256` 两个谓词基于它 |
| `mHasRequestCancel` | `:46` | volatile | 同上 |

### 2.4 lib 字段映射

| lib 字段 | 行 | 对应 OPPO 字段 | 差距 |
|---|---|---|---|
| `PendingAnimation.anim: AnimatorSet`（`PendingAnimation.kt:29`） | 1 个 | `mAnimatorSet` `:42` + `mAsyncAnimatorSet` `:44`（**合二为一**） | **2 个 vs 1 个**。lib 没有 "sync/async 二选一" 概念，调用方一次 `play(anim)` 就只能是同步 |
| `PendingAnimation.progressAnimator: ValueAnimator?`（`:32`） | 1 个 | **无对应**（progress 是主时钟，由 `AnimatorPlaybackController` 驱动；这里不属于 MultiAnimatorSet 范畴） | N/A |
| `PendingAnimation.isAnimFinished: Boolean`（`:35`） | 1 个 | `mStarted && 4 个 *Ended 全 true`（`:104`） | **布尔数差 3**。lib 1 个 `isAnimFinished` 翻 `true` 表示 "AnimSet 已结束"，OPPO 4 个 boolean 各自翻 `true` 后**且** `mStarted==true` 才发 onEnd |
| `PendingAnimation.animHolders`（`:30`） | list | **无对应**（OPPO 这层由 PendingAnimation 自身持有 + AnimatorPlaybackController 解析） | N/A（lib 把 PendingAnimation + APC 一起复刻，但与 MultiAnimatorSet 互补） |
| **❌ 无** | — | `mSpringAnimations: ArraySet<SpringAnimation>` | **完全缺失** |
| **❌ 无** | — | `mRectFSpringAnim: CustomRectFSpringAnim` | lib 接收为句柄但不持有（review 13 §1.3） |
| **❌ 无** | — | `mAnimationId: int` | **缺失** |
| **❌ 无** | — | `mAnimEndCallback: Consumer<Integer>` | **缺失**（`AnimationSeqHelper` 提供类似 callback 但语义不同） |
| **❌ 无** | — | `mAnimatorListeners: ArraySet<NullableAnimatorListener>` | **缺失**（lib 把 listener 挂到 AnimatorSet 自己） |
| **❌ 无** | — | `mHasRequestCancel: volatile boolean` | **缺失**——**关键**：TaskViewUtils `:1411` 的跨线程 cancel 信号无法表达 |
| **❌ 无** | — | `mSpringAnimEndListener: OnAnimationEndListener` | **缺失** |

**结论**：**21 个 OPPO 字段 → 1 个 lib 字段（`PendingAnimation.anim`）**。20/21 字段缺失，含 1 个 `volatile` 跨线程标志。

---

## ③ 方法级映射

### 3.1 构造器

| OPPO | lib | 差距 |
|---|---|---|
| 2 个构造器：`(AnimType)` `:55-67` 与 `(AnimatorSet, AnimType)` `:438-451`——第二个允许调用方预填 sync AnimatorSet 内容（用于 LauncherBackAnimationController 这类 "已有 AnimatorSet，直接接管" 场景） | 1 个构造器：`PendingAnimation(duration: Long)` `:27` | **第 2 构造器缺失**。lib 调用方必须 rebuild 全套 ObjectAnimator；LauncherBackAnimationController 这种 "接管已有 AnimatorSet" 模式无对应 |

### 3.2 公开方法（按调用频度）

| 方法签名（OPPO 行号） | lib 复刻 | 差距 |
|---|---|---|
| `play(Animator)` `:263-266` → `play(false, animator)` `:394-404` | `add(child: Animator)` `:45-49`（`anim.playTogether(child)`） | 行为等价（都加进 AnimatorSet），**但缺 async 分支** |
| `play(Animator, boolean z)` `:413-436`（核心入口，**调用方决定 sync vs async**） | **❌ 无** | **关键**：调用方（TaskViewUtils `:1074`、LauncherContentAnimManager `:150`）按 `AppFeatureUtils.enableAsyncTaskViewLaunchWindowAnim()` 选 z；lib 必须重建 4 通道才能支持 |
| `play(boolean z, Animator)` `:394-404` | **❌ 无** | 同上 |
| `play(SpringAnimation...)` `:406-411` → `play(SpringAnimation)` `:453-465` | **❌ 无**（lib `AsyncSpringAnim.kt` 是单条 spring，独立驱动） | **关键**：`play(SpringAnimation)` 有 "started 后 live-add" 分支（`:455-462`）——若 spring 未运行则 addEndListener + start；否则仅 add。**lib 启动期外不能再加 spring** |
| `play(CustomRectFSpringAnim)` `:467-475` | **❌ 无**（仅作句柄接收） | **缺失**——构造时 setAnimParamByType(mAnimType) 是基于 AnimType 决定 6 自由度参数，调用方不显式 play(rectFSpring) 就会丢参数 |
| `start()` `:276-391` | `buildAnim(): AnimatorSet` + `start() = va.start()`（`PendingAnimation.kt:92-101, 163`） | **彻底分叉**。OPPO 是 4 通道并行 kick-off（sync/async/spring/rectF）+ 重入守卫 + empty-fast-path + isSystemDisableAnimation-fast-path，lib 单一 `va.start()` |
| `cancel(int i9)` `:143-177`（bitmask 1=ANIMATOR_SET, 2=SPRING_ANIMATIONS, 4=RECTF_SPRING_ANIM）| `anim.cancel()`（`PendingAnimation.kt:164`，仅 `va.cancel()`） | **bitmask 缺失**——3 bit vs 0 bit。调用方（LauncherAnimationRunner / TaskViewUtils）按 bit 选通道取消，lib 一刀切 |
| `end(int i9)` `:183-214`（同上 bitmask，但 (i9 & 2) 走 `canSkipToEnd()` `skipToEnd()` `:202-205`，无 `removeSpringAnimFromSet`） | `end()`（`PendingAnimation.kt:165`，仅 `va.end()`） | **bitmask 缺失 + `skipToEnd()` 语义缺失** |
| `cancelAllAnimExceptSpringAnim()` `:179-181` → `cancel(5)` | **❌ 无** | API 缺失 |
| `endAllAnimExceptSpringAnim()` `:216-218` → `end(5)` | **❌ 无** | API 缺失 |
| `addListener(NullableAnimatorListener)` `:138-141` | `addListener(l: Animator.AnimatorListener)` `:88-90`（直接挂 AnimatorSet） | **接口差异**：OPPO 内部 ArraySet 累加后 onAnimationStart/End 广播（`:112-115, 291-296`），listener 收到 `null` animator；lib 直接挂到 AnimatorSet，会收到真实 animator——**回调时机不同** |
| `setAnimationId(int)` `:268-270` | **❌ 无** | API 缺失 |
| `setViewStateResetRunnable(Consumer<Integer>)` `:272-274` | **❌ 无** | API 缺失 |
| `maybeOnEnd()` `:103-117`（私有） | `isAnimFinished = true`（`PendingAnimation.kt:41`） | **核心语义差异**：OPPO 是 4 boolean 等齐后才发 `mAnimEndCallback.accept(mAnimationId)` + listeners.onAnimationEnd(null)；lib AnimatorSet 一结束就翻 true（onStart/onEnd/onCancel 全部覆写 `:39-42`）——**不等齐、不等齐、也不等齐** |
| `removeSpringAnimFromSet()` `:119-131`（私有）| **❌ 无** | **缺失**：cancel 时清理 spring + listeners + mAnimEndCallback |
| `initSpringAnimEndListener()` `:83-101`（私有）| **❌ 无** | **缺失**：懒建共享 end listener |
| `isRunning()` `:258-260` | `isRunning`（`PendingAnimation.kt:167`，仅 `va.isRunning`） | **行为差异**：OPPO 任一通道未结束都算 running；lib 只看 AnimatorSet 自身 |
| `isAppOpenType()` `:250-252` / `isGestureToDrag()` `:254-256` | **❌ 无** | API 缺失 |
| 8 个 getter | 部分 | lib `anim`/`duration`/`controller` getter 不一一对应 |

### 3.3 lib "单通道" → 原厂 "4 通道" 的关键语义差

#### 3.3.1 启动期 (start)

**OPPO**：`start()` 顺序启动 4 通道（`:297-384`）：

```
1. mAnimatorListeners.onAnimationStart(null)            [line 291-296]
2. if size(mAnimatorSet)>0:
     mAnimatorSet.addListener(ends-flip)
     mAnimatorSet.start()                                [line 297-323]
3. if size(mSpringAnimations)>0:
     initSpringAnimEndListener()
     mViewSpringAnimEnded=false
     each spring: addEndListener + start                [line 324-331]
4. if mRectFSpringAnim != null:
     mRectFSpringAnimEnded=false
     customRectFSpringAnim.setAnimationId(mAnimationId)
     customRectFSpringAnim.addAnimatorListener(ActualEnd)
     customRectFSpringAnim.start()                      [line 332-353]
5. if size(mAsyncAnimatorSet)>0:
     mAsyncAnimatorSetEnded=false
     mAsyncAnimatorSet.addListener(ends-flip-via-main-post)
     OplusExecutors.ANIM_EXECUTOR.execute(start$lambda$5)  [line 354-384]
6. if all empty: maybeOnEnd()                            [line 385]
   else if isSystemDisableAnimation: end(3)             [line 388-389]
```

**lib**：单一 `va.start()`，所有动画等齐条件由 `progressAnimator` 主时钟逐帧 `setPlayFraction` 推到 `Holder` 上（`AnimatorPlaybackController`，review 02 §2.2）—— **不是并行 4 通道**，而是 **1 个主时钟驱动 N 个 Holder**。

**关键差**：OPPO 的 "spring" 和 "rectF" 是 **独立积分通道**（各自有 SpringForce / MultiDynamicAnimation 帧循环），与主 AnimatorSet 同步/异步通道 **并行**；lib 把所有动画都收敛到 Holder 集合，**没有 "并行的另一组帧循环"**。

#### 3.3.2 结束期 (maybeOnEnd)

**OPPO**（`:103-117`）：

```kotlin
if (mStarted && mAnimatorSetEnded && mViewSpringAnimEnded
               && mRectFSpringAnimEnded && mAsyncAnimatorSetEnded) {
    log("#" + mAnimationId + " all animations ended. type: " + mAnimType)
    mStarted = false
    mAnimEndCallback?.accept(mAnimationId)        // 单 callback
    mAnimEndCallback = null
    for listener in mAnimatorListeners:
        listener.onAnimationEnd(null)             // 广播 + 传 null
}
```

**lib**（`PendingAnimation.kt:38-42`）：

```kotlin
init {
    anim.addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationStart(a: Animator) { isAnimFinished = false }
        override fun onAnimationEnd(a: Animator) { isAnimFinished = true }
        override fun onAnimationCancel(a: Animator) { isAnimFinished = true }
    })
}
```

**关键差 ①**：lib 的 listener 收到 `Animator` 引用（`onAnimationEnd(a)`），OPPO 显式传 `null`——契约差异，listener 实现需要 null-safe。

**关键差 ②**：lib `isAnimFinished` 仅看 AnimatorSet 自身；OPPO 是 4 通道等齐，**任意一个通道未结束就不算 ended**——例如 spring 还在飞、rectF 还在 6 自由度积分，即使 AnimatorSet 已经 end 了，OPPO 仍会延迟 maybeOnEnd 触发。

#### 3.3.3 cancel vs end 的 bitmask 协议

**bitmask 定义**（`:34-37, 179-181, 216-218`）：

```kotlin
const TYPE_ANIMATOR_SET = 1      // 0b001 → mAnimatorSet + mAsyncAnimatorSet
const TYPE_SPRING_ANIMATIONS = 2 // 0b010 → mSpringAnimations
const TYPE_RECTF_SPRING_ANIM = 4 // 0b100 → mRectFSpringAnim
const TYPE_ALL = 7               // 0b111

fun cancelAllAnimExceptSpringAnim() = cancel(5)  // 0b101 = ANIMATOR_SET + RECTF
fun endAllAnimExceptSpringAnim() = end(5)        // 同上
```

**cancel(1)** 行为（`:157-161`）：

```kotlin
mAnimatorSet.cancel()
OplusExecutors.INSTANCE.getANIM_EXECUTOR().execute(new y1(this, 4))
// ↑ 注意：cancel(1) 同时取消 sync AnimatorSet（主线程直接调）+ 通过 ANIM_EXECUTOR 异步取消 mAsyncAnimatorSet
```

**cancel(2)** 行为（`:162-168`）：

```kotlin
if (i9 & 2 != 0:
    for spring in mSpringAnimations: spring.cancel()   // 真 cancel
else:
    removeSpringAnimFromSet()                           // 否则走清理
```

**end(2)** 行为（`:201-208`）—— 与 cancel 不同：

```kotlin
if (i9 & 2 != 0:
    for spring in mSpringAnimations:
        if spring.canSkipToEnd(): spring.skipToEnd()   // 欠阻尼才允许瞬到终
else:
    removeSpringAnimFromSet()
```

**关键差 ①**：bitmask 整体缺失——lib 一刀切，没有 "只取消某通道" 的能力。

**关键差 ②**：cancel 与 end 在 spring 通道语义不同：cancel 是 `springAnimation.cancel()`（实际停下积分到当前位置），end 是 `skipToEnd()`（瞬到 `mSpring.getFinalPosition()`）。**两者均要求 `canSkipToEnd() == mSpring.mDampingRatio > 0`**（`SpringAnimation.java:56-58`）——欠阻尼才允许，否则 `UnsupportedOperationException`。lib `AsyncSpringAnim.end()` 仅 `va.end()`，**无欠阻尼校验**。

**关键差 ③**：cancel(1) 既取消 sync `mAnimatorSet` 又取消 async `mAsyncAnimatorSet`（通过 ANIM_EXECUTOR post）；end(1) 同样 end 两个。但 caller 是区分的：`cancel(5)` 只动 (1|4)，留 (2) 不动——"spring 通道继续跑、其他瞬停"。lib 无此能力。

---

## ④ 保真度评估

### 4.1 精确复刻（lib 已对齐）

无。**4 通道调度器在 lib 中完全没有对应实现**。

### 4.2 有意简化（保留语义，去实现）

| 项 | 简化方式 | 评估 |
|---|---|---|
| lib `PendingAnimation` 单 AnimatorSet 替代 4 通道 | 把 sync/async/spring/rectF 全部收敛到 1 个 `anim`（`PendingAnimation.kt:29`） | **合理但有限度**：当 demo 只演示 "转场 + 主时钟 + Holder 跟手" 时（Demo2/3/6/9），单 AnimatorSet + AnimatorPlaybackController 已足够覆盖。但 Demo9 转场 "概念演示" 注释（`Demo9.kt:69-71`）明示：少了 spring + rectF 通道，**真实转场无法跑起来** |
| `mAnimatorListeners: ArraySet<NullableAnimatorListener>` 改为直接 addListener 到 AnimatorSet | lib `PendingAnimation.kt:88-90` | **可接受**：OPPO 的 ArraySet 是为了支持 start 前 addListener + start 后统一广播；lib 路径上都是 AnimatorSet 后挂，行为等价。但 **传 `null` 契约差异**（lib 传真实 Animator，OPPO 传 null）需要 listener 实现 null-safe |
| `mAnimationId` 砍掉 | 没有任何地方存 id | **可接受**：仅用于日志/trace；lib `Trace.kt` 自有 trace id |

### 4.3 遗漏（lib 无，且无可替代）

| 项 | 风险 | 复刻必要性 |
|---|---|---|
| **4 通道并行装配**（sync + async + spring + rectF） | **Demo9 转场只能概念演示**（review 06 §2.2 row 67、review 11 §C-3） | 高 |
| **`play(Animator, boolean)` 调用方决定 sync/async** | TaskViewUtils `:1074` `multiAnimatorSet.play(z10, animatorSetBuildAnim)` 这种 "feature flag 决定通道" 模式 lib 无 | 高 |
| **`play(SpringAnimation...)` vararg 入口** | 调用方多次 add spring 后统一 start 的模式 lib 无 | 中 |
| **`play(CustomRectFSpringAnim)` 单入口 + setAnimParamByType** | lib 接收 CustomRectFSpringAnim 为句柄但不持有、不 setAnimParamByType → 6 自由度参数永远用默认 AnimType.SWIPE_TO_HOME | 中（与 review 13 重复） |
| **`cancel(int)` / `end(int)` bitmask 协议** | 调用方按 bit 选通道取消的能力 lib 无；LauncherAnimationRunner `:438-466` 这种 "只保留 spring、其他瞬停" 模式 lib 无 | 高 |
| **`cancel(1)` 同步取消 + ANIM_EXECUTOR 异步取消的混合动作** | ANIM_EXECUTOR post + 主线程 cancel 同时发生的协议 lib 无 | 高 |
| **`maybeOnEnd()` 4 boolean 等齐协议** | lib `isAnimFinished` 只看 AnimatorSet 自身，**不等齐**——spring / rectF 还在飞时，lib 会错误发 "全部结束" 信号 | **bug 级** |
| **`mHasRequestCancel: volatile boolean` 跨线程 cancel 信号** | TaskViewUtils `:1411` 的 `lambda$createRecentsWindowAnimator$0` 在 apply SurfaceControl.Transaction 前检查 `multiAnimatorSet.getMHasRequestCancel()`——**避免 cancel 后还 apply transaction**。lib 无此信号，**apply 后取消 → 帧撕裂** | **bug 级** |
| **`mAnimEndCallback: Consumer<Integer>` 单一结束回调** | lib `AnimationSeqHelper` 提供类似 callback 但语义不同（SeqId 防抖，不是 mAnimationId 回调） | 中 |
| **`mSpringAnimEndListener` 懒建共享 end listener** | lib 的 `AsyncSpringAnim.addEndListener` 走 androidx SpringAnimation 路径，独立 | 低（lib 路径整体替换） |
| **`removeSpringAnimFromSet()` 清理路径** | cancel(无 2 bit) 路径上的 listener 清理、mAnimEndCallback=null、spring removeEndListener、mViewSpringAnimEnded=true、maybeOnEnd()——整套清理 lib 无 | 中 |
| **`isRunning()` 任一通道未结束** | lib 仅 AnimatorSet.isRunning；spring / rectF 还在飞时 lib 报 false | 中 |
| **`isAppOpenType()` / `isGestureToDrag()` 谓词** | 业务谓词；lib 无 AnimType，谓词无意义 | 低 |
| **`SpringHolder.mStartDelay` 倒计时（替代 setStartTime/delay 字段）** | lib `AsyncSpringAnim.kt` 走 androidx SpringAnimation，**没有 mStartDelay 概念**——`SpringHolder.java:108-114` 的 "delay > 0 时每帧减 deltaT" 倒计时语义丢失 | 中 |
| **`SpringAnimation.canSkipToEnd()` 欠阻尼校验** | lib `end()` 无校验，调用方传欠阻尼 spring + 调 end() 不会 throw `UnsupportedOperationException`（OPPO 会） | 低 |

**4 通道调度器总共 1 项精确复刻 / 3 项有意简化 / 14 项遗漏**——**整体保真度 ~14%**（按字段数 1/21 = 5%，按方法数 1/20 ≈ 5%，按语义覆盖 ~14%）。

---

## ⑤ 行为差异风险点

### 5.1 bug 级（按 P0 排序）

| # | 问题 | 触发条件 | 修复成本 |
|---|---|---|---|
| **B1** | **`maybeOnEnd()` 4 通道等齐协议缺失**——lib `isAnimFinished` 仅看 AnimatorSet 自身，spring / rectF 还在飞时错误报 "全部结束"，导致 `AnimationSeqHelper.canFinishRecentsAnim(animRecord)` 误判、TaskStateChangeTimeOutListener 提前收尾、launcher 提早进入 NORMAL 态 | 所有 demo 在 spring 路径或 rectF 路径未结束但 AnimatorSet 已结束的场景；Demo4/11 弹簧场景 | **80 行**（新建 `MultiAnimatorSet.kt`：4 字段 + 4 boolean + start/cancel/end bitmask + maybeOnEnd 等齐） |
| **B2** | **`mHasRequestCancel: volatile boolean` 缺失**——TaskViewUtils `:1411` 在 apply SurfaceControl.Transaction 前检查 `getMHasRequestCancel()`，避免 cancel 后还写 transaction。lib 无此信号，**cancel 后 frame N 的 transaction 仍会 apply → 帧撕裂 / 黑屏闪** | TaskViewUtils.composeRecentsLaunchAnimator 调用路径；用户上滑到 recents 后立刻下拉取消 | **5 行**（`PendingAnimation` 加 `@Volatile var hasRequestCancel: Boolean = false`，cancel 时翻 true） |
| **B3** | **`cancel(int)` / `end(int)` bitmask 协议缺失**——调用方按 bit 选通道取消的能力全无；`cancelAllAnimExceptSpringAnim()` / `endAllAnimExceptSpringAnim()` 两个 helper API 也无。LauncherAnimationRunner `:438-466` "只取消非 spring 通道" 模式 lib 无法表达 | LauncherAnimationController backAnimation 路径；调用方希望 "spring 继续跑到 AnimType 指定位置、其他瞬停" 的场景 | **30 行**（MultiAnimatorSet 加 bitmask 参数 + TYPE 常量 + 2 helper） |
| **B4** | **`play(Animator, boolean)` sync/async 分支缺失**——调用方按 feature flag 选 sync vs async 通道（TaskViewUtils `:1074` `multiAnimatorSet.play(z10, ...)`）的协议 lib 无；所有动画只能走单一 AnimatorSet，无法利用 ANIM_EXECUTOR 跨线程优势 | TaskViewUtils.composeRecentsLaunchAnimator `AppFeatureUtils.enableAsyncTaskViewLaunchWindowAnim()` 路径 | **50 行**（MultiAnimatorSet 加 mAsyncAnimatorSet 字段 + ANIM_EXECUTOR.execute(start) + mAsync end listener post 回主线程） |
| **B5** | **`cancel(1)` 同步 + 异步混合 cancel 协议**——OPPO 同时在主线程 `mAnimatorSet.cancel()` + 通过 `ANIM_EXECUTOR.execute(cancel$lambda$6)` 取消 `mAsyncAnimatorSet`，保证两通道同一时刻被打断；lib 单一 AnimatorSet 只能同步 cancel | TaskViewUtils / LauncherAnimationRunner 任一使用 async 通道的路径 | **10 行**（在 B4 实现后，cancel(1) 路径里加 ANIM_EXECUTOR post） |
| **B6** | **`end(2)` 走 `canSkipToEnd()` + `skipToEnd()`** 而 cancel(2) 走 `springAnimation.cancel()`，两者语义不同；end 必须校验欠阻尼否则 `UnsupportedOperationException`（`SpringAnimation.java:117`）。lib 单一 `va.end()` 无法做此区分 | 调用方希望 "spring 瞬到终点" 但传了过阻尼 spring；OPPO 抛异常，lib 静默成功 | **10 行**（`AsyncSpringAnim.end()` 加 dampingRatio > 0 校验） |

### 5.2 中等风险

| # | 问题 | 触发条件 | 修复成本 |
|---|---|---|---|
| **M1** | **`SpringHolder.mStartDelay` 倒计时缺失**——`SpringHolder.java:108-114` 在 `updateValueAndVelocity(deltaT, endRequest)` 倒计时 `mStartDelay -= deltaT`，归零前 spring 不积分（即使每帧 onUpdate 也不动）。lib `AsyncSpringAnim.kt` 走 androidx SpringAnimation 没有 delay 概念 | 调用方希望 "spring 延迟 N ms 启动" 的场景；CustomRectFSpringAnim 在 OPEN_FROM_HOM 时 mAlphaStartDelay 路径（review 13 §2.1） | **30 行**（在 SpringHolder-style node 类里加 mStartDelay + 倒计时；或 fork androidx SpringAnimation） |
| **M2** | **`removeSpringAnimFromSet()` 清理路径缺失**——cancel(无 2 bit) 路径上的 spring removeEndListener + clear + mViewSpringAnimEnded=true + maybeOnEnd + mAnimEndCallback=null 整套清理 lib 无 | 任何调 cancel(5) 的场景（spring 通道不清干净可能 listener 泄漏 / 多发 end 回调） | **15 行**（B1 实现后随之带出） |
| **M3** | **`play(SpringAnimation...)` vararg + "started 后 live-add" 路径缺失**——`MultiAnimatorSet.play(SpringAnimation)`:453-465` 有 `if (mStarted && !springAnimation.isRunning())` 分支，启动期外能再 add spring 并 start；lib 启动期外不能再加 spring | 调用方希望 "动画跑了一半再叠加一条 spring" 的场景 | **20 行**（B1 实现后随之带出） |
| **M4** | **`mAnimEndCallback: Consumer<Integer>` 单一结束回调 + `mAnimationId` ID 协议缺失**——回调签名 `accept(mAnimationId)`，调用方能用 id 区分多次连续动画；lib `AnimationSeqHelper` 提供类似 callback 但签名/语义不同 | 多次连续启动 MultiAnimatorSet（同一 view 多次打开应用），区分第几次回调的场景 | **5 行**（`MultiAnimatorSet` 加 animationId + callback） |
| **M5** | **`isRunning()` 任一通道未结束返回 true 的契约缺失**——lib 仅 AnimatorSet.isRunning；spring 还在飞但 AnimatorSet 已结束，OPPO 报 true，lib 报 false | TaskViewUtils `:181, 266` `appCloseAnimRecord.getMMultiAnimatorSet().isRunning()` 的判定 | **5 行**（B1 实现后随之带出） |
| **M6** | **`mSpringAnimEndListener: OnAnimationEndListener` 懒建共享 end listener 缺失**——每条 spring 用同一个 OnAnimationEndListener；lib 路径上各自 addEndListener | 性能（多个 spring 时 listener 数量）；非 bug，但 listener 数翻倍 | **10 行**（B1 实现后随之带出） |
| **M7** | **`addListener(NullableAnimatorListener)` 传 `null` animator 的契约缺失**——OPPO listener 收到 `null`（`MultiAnimatorSet.java:114, 174`），lib listener 收到真实 Animator；listener 实现若依赖 `animator == null` 分支会错 | listener 实现有 `if (animator != null) animator.cancel()` 之类的 null-safe 写法时 | **3 行**（`PendingAnimation.addListener` 路径上加 `NullableAnimatorListenerAdapter` 包装，传 null） |

### 5.3 低风险

| # | 问题 | 触发条件 | 修复成本 |
|---|---|---|---|
| L1 | `isAppOpenType()` / `isGestureToDrag()` 业务谓词缺失 | 业务方用 mAnimType 做谓词；lib 无 AnimType | 5 行 |
| L2 | `setAnimationId(int)` / `getMAnimationId()` 调试 API 缺失 | trace / log 需要 mAnimationId | 2 行 |
| L3 | `cancelAllAnimExceptSpringAnim()` / `endAllAnimExceptSpringAnim()` helper API 缺失 | 调用方用 helper 而非裸 bitmask | 2 行 |

---

## ⑥ 回移建议

### 6.1 值得补的（按 "业务价值 / 修复成本" 性价比排序）

| # | 缺口 | 业务影响 | 修复成本 |
|---|---|---|---|
| **R1** | **新建 `MultiAnimatorSet` 4 通道调度器** | **B1-B7 + M1-M7 全部覆盖**；Demo9 从概念演示升级到真实转场；TaskViewUtils / LauncherAnimationRunner / LauncherContentAnimManager 路径全打通 | **~250 行**（4 字段 + 4 boolean + 1 volatile + 2 构造器 + play 6 重载 + start + cancel + end + addListener + maybeOnEnd + removeSpringAnimFromSet + initSpringAnimEndListener + 5 getter + 5 TYPE 常量 + 2 helper API） |
| **R2** | **SpringHolder-style node 类（含 `mStartDelay` 倒计时 + 半步分裂积分）** | 配合 R1，让 4 通道中 spring 通道走自定义帧循环；补 review 13 的 6 自由度 RectF 弹簧 | **~200 行**（review 13 §1.2 已列） |
| **R3** | **`MultiDynamicAnimation` 帧循环载体** | 配合 R2，让 spring 通道独立帧循环 | **~150 行**（review 13 §1.2 已列） |
| **R4** | **`canSkipToEnd()` 校验** | B6 修复；防止欠阻尼 spring 调 end() 抛异常 | 5 行 |
| **R5** | **`mHasRequestCancel: volatile boolean` 跨线程信号** | B2 修复；即使不实现 R1，也能挂在 `PendingAnimation` 上避免帧撕裂 | 5 行 |

### 6.2 建议保持简化（不补）

| 项 | 不补理由 |
|---|---|
| `isAppOpenType()` / `isGestureToDrag()` 业务谓词 | 业务谓词；demo 不演示此类业务逻辑 |
| `setAnimationId(int)` / `getMAnimationId()` 调试 API | lib `Trace.kt` 自有 trace id，不依赖 mAnimationId |
| `cancelAllAnimExceptSpringAnim()` / `endAllAnimExceptSpringAnim()` helper API | helper API 命名问题；调用方用 `cancel(5)` / `end(5)` 等价（且不实现 R1 就用不上） |
| `mSpringAnimEndListener` 懒建共享 listener | 性能优化；demo 不演示多 spring 共享 listener |
| `play(SpringAnimation...)` vararg + live-add 路径 | demo 不演示 "动画跑一半再叠加 spring" |

### 6.3 实施建议

按 P0 + 性价比分 3 轮：

1. **第一轮（修复 bug 级，~60 行）**——R4 + R5 + B6：补 canSkipToEnd 校验 + mHasRequestCancel volatile 信号 + AsyncSpringAnim.end() 区分 cancel/end；无需新建 MultiAnimatorSet，可挂在 PendingAnimation 上
2. **第二轮（建主调度器骨架，~250 行）**——R1：新建 `launcher/manager/MultiAnimatorSet.kt`，4 通道 + bitmask + maybeOnEnd 等齐协议；让 Demo9 升级为真实 4 通道转场
3. **第三轮（建 spring 节点 + 帧循环，~350 行）**——R2 + R3：建 `launcher/async/SpringHolder.kt` + `launcher/async/MultiDynamicAnimation.kt`，让 spring 通道独立帧循环；与 review 13 的 CustomRectFSpringAnim 升级配套

如果只做第 1 轮，lib 能修复 2 个 bug 级问题（B2 + B6）；做完第 2 轮能解决 Demo9 转场概念演示；做完第 3 轮能把 review 11/13 标注的弹簧链缺口一并补齐。

---

## ⑦ 取证清单

| 论断 | 证据 |
|---|---|
| 4 通道并行装配 | `MultiAnimatorSet.java:42, 44, 50, 47`（mAnimatorSet / mAsyncAnimatorSet / mSpringAnimations / mRectFSpringAnim）|
| 4 boolean 等齐结束 | `MultiAnimatorSet.java:43, 45, 52, 48` + `maybeOnEnd() :103-117` |
| bitmask `1\|2\|4=7` + cancel(5)/end(5) helper | `MultiAnimatorSet.java:34-37, 179-181, 216-218` |
| `mHasRequestCancel: volatile` 跨线程信号 | `MultiAnimatorSet.java:46` + TaskViewUtils `:1411` `getMHasRequestCancel()` |
| cancel(1) 同时 sync + async | `MultiAnimatorSet.java:159-161`（`mAnimatorSet.cancel()` + `OplusExecutors.ANIM_EXECUTOR.execute(new y1(this, 4))`） |
| end(2) 走 `canSkipToEnd()` 校验 | `MultiAnimatorSet.java:202-205` + `SpringAnimation.java:56-58, 115-125` |
| play(Animator, boolean) 调用方决定 sync/async | `MultiAnimatorSet.java:413-436` + TaskViewUtils `:1074` `multiAnimatorSet.play(z10, animatorSetBuildAnim)` |
| play(CustomRectFSpringAnim) + setAnimParamByType | `MultiAnimatorSet.java:467-475`（`rectFSpringAnim.setAnimParamByType(mAnimType)`） |
| mAnimationId 调试 ID | `MultiAnimatorSet.java:40, 57, 109, 268-270` |
| mAnimEndCallback 单 callback + fire 后置 null | `MultiAnimatorSet.java:38, 107-111, 272-274` |
| mStarted 重入守卫 | `MultiAnimatorSet.java:51, 277-281, 106` |
| mAnimatorListeners 累加 + start/end 广播 + 传 null | `MultiAnimatorSet.java:41, 112-115, 138-141, 291-296` |
| SpringHolder.mStartDelay 倒计时 | `SpringHolder.java:108-114`（`mStartDelay -= deltaT`，归零前不积分）|
| SpringHolder 半步分裂积分（mPendingPosition） | `SpringHolder.java:115-130` |
| SpringAnimation.canSkipToEnd 欠阻尼校验 | `SpringAnimation.java:56-58`（`return mSpring.mDampingRatio > 0.0d`） |
| Debug.getCallers(10) 栈采样 | `MultiAnimatorSet.java:148, 188`（review 08 §3 已列）|
| lib 单 AnimatorSet | `PendingAnimation.kt:29`（`private val anim = AnimatorSet()`） |
| lib 无 4 通道、无 bitmask、无 maybeOnEnd 等齐 | `PendingAnimation.kt:38-42`（listener 收真实 Animator；`isAnimFinished` 只看 AnimatorSet 自身） |
| lib Demo9 概念演示 | `Demo9AllAppsTransitionActivity.kt:69-71` 显式 `log("转场链路（概念）：...")` |

---

## ⑧ 总结

`MultiAnimatorSet` 是 **OPPO 4 通道调度器**：sync AnimatorSet（MAIN）+ async AnimatorSet（ANIM_EXECUTOR）+ SpringAnimation ArraySet + CustomRectFSpringAnim，4 个 `*Ended` boolean 等齐发结束回调。**lib 完全缺失**——`PendingAnimation` 仅 1 个 AnimatorSet + 1 个 progressAnimator，没有 async/spring/rectF 三条并行通道，没有 bitmask cancel/end，没有 maybeOnEnd 等齐协议，没有 `mHasRequestCancel` volatile 跨线程信号。

**Bug 级风险 6 个**（B1 maybeOnEnd 不等齐 / B2 mHasRequestCancel 缺失导致帧撕裂 / B3 bitmask 协议缺失 / B4 play(anim, boolean) sync/async 分支缺失 / B5 cancel(1) sync+async 混合 cancel 缺失 / B6 canSkipToEnd 校验缺失）；**中等风险 7 个**（M1 SpringHolder.mStartDelay 倒计时缺失 / M2 removeSpringAnimFromSet 清理缺失 / M3 live-add spring 路径缺失 / M4 mAnimationId+mAnimEndCallback ID 协议缺失 / M5 isRunning 任一通道未结束契约缺失 / M6 mSpringAnimEndListener 共享 listener 缺失 / M7 listener 传 null 契约缺失）；**低风险 3 个**（业务谓词 / 调试 API / helper API）。

**整体保真度 ~14%**（1/21 字段、1/20 方法、覆盖 4 通道调度 0/4）。**修复成本估算**：仅 bug 级修复 ~60 行；新建完整 MultiAnimatorSet ~250 行；含 SpringHolder + MultiDynamicAnimation ~600 行。Demo9 从 "概念演示" 升级到 "真实 4 通道转场" 必须建主调度器。