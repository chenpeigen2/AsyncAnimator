# OPPO Launcher 动画线程执行动画的实现方案 — 技术分析（v3）

> 范围：`com.android.launcher 15.8.24`（OPPO / ColorOS 15 Android Launcher）JADX 反编译源码中的动画执行机制。
>
> 源码目录：`D:/oppo_a6_launcher/sources`。所有事实论断均带 `文件路径:行号` 引用，且引用位置已逐一读过明文源码（经 `read-with-python` dump 或 Grep 穿透 DLP 加密验证）。
>
> 本文是 **v3 重写版**：结构重新设计，旧版（2460 行）中的多处事实错误已修正，修正清单见文末「修订记录」。与旧版论断冲突处一律以本文为准。

---

## 0. TL;DR

动画执行链路自底向上分四层：

1. **帧调度层**：`Choreographer`（平台）→ `AnimationHandler`（包级单例，线程局部）。树中有三份 `AnimationHandler`：`androidx.core.animation`（驱动 ValueAnimator 系）、`androidx.dynamicanimation.animation`（驱动 SpringAnimation 系）、以及 `android.animation.AnimationHandler`（框架隐藏类，被 `MultiDynamicAnimation` 直接实现其 `AnimationFrameCallback` 接口挂载，**绕过 ValueAnimator**）。
2. **动画引擎层**：vendored `androidx.core.animation.ValueAnimator` 是所有时序动画的基座；其上有 `AnimatorPlaybackController`（进度驱动）、`OplusValueAnimator`（timeController 委托 + 续行动画）、`MultiDynamicAnimation`（多弹簧聚合，活跃使用）。`OplusSpringObjectAnimator`（双驱动装饰器）**在明文中没有任何调用方，是死代码**。
3. **状态机层**：`com.oplus.quickstep.utils.AnimationController`，12 状态枚举 `AnimationState`，跟踪"开 app / 回桌面 / 续行"的转场状态，由 `OplusAnimManager` 按 `supportInterruption()` 运行时条件工厂产出。
4. **协调层**：`AnimSeqTimeStamp`（systemui shared 单例，4 个 `@JvmStatic synchronized` 时间戳）+ `AnimationSeqHelper` 做跨模块时序防抖。

线程模型一句话：**帧回调、值计算、listener 派发全部在主线程**；`AsyncValueAnimator`/`LooperExecutor` 只解决"业务在子线程调用 start/cancel/end 合法化"，值最终回到主线程写 View（详见 `sub-thread-ui-update.md` 与本文 §7）。

---

## 1. 总览：分层架构图

```
┌────────────────────────────────────────────────────────────────────────┐
│ 业务/协调层                                                              │
│   OplusAnimManager (Kotlin object 单例)                                  │
│     └ supportInterruption() 运行时三条件开关                              │
│        com/oplus/quickstep/utils/OplusAnimManager.java:232-234           │
│   AnimationSeqHelper ── AnimSeqTimeStamp (systemui shared, 4 同步时间戳)  │
│        com/android/systemui/shared/system/AnimSeqTimeStamp.java:10       │
├────────────────────────────────────────────────────────────────────────┤
│ 状态机层                                                                 │
│   AnimationController extends DefaultAnimationController                │
│     12 状态 AnimationState 枚举                                           │
│        com/oplus/quickstep/utils/AnimationController.java:58,103-115     │
│     事件入口: addRecentsAnim / appLaunchAnimStartOrEnd /                  │
│               revertRecentsAnimation / reset                             │
├────────────────────────────────────────────────────────────────────────┤
│ 动画引擎层                                                               │
│   AnimatorPlaybackController   一个 mAnimationPlayer 驱动 N 个子动画       │
│        com/android/launcher3/anim/AnimatorPlaybackController.java:25     │
│   OplusValueAnimator           timeController 委托 + 续行动画工厂          │
│        com/oplus/quickstep/utils/OplusValueAnimator.java:32              │
│   MultiDynamicAnimation        多 SpringHolder 聚合，直挂帧回调★           │
│        com/android/quickstep/util/animation/MultiDynamicAnimation.java:21│
│        └ 真实调用方: CustomRectFSpringAnim (:76,:214,:395-401)           │
│   OplusSpringObjectAnimator    ValueAnimator+Spring 双驱动装饰器 ☠死代码   │
│        com/oplus/quickstep/anim/OplusSpringObjectAnimator.java:19        │
│   painteranimation.OplusSpringAnimation  弹簧外壳 + painter 录制挂钩      │
│        com/oplus/painteranimation/OplusSpringAnimation.java:16           │
│   SpringAnimationBuilder       AOSP 阻尼振荡公式弹簧（有真实调用方）       │
│        com/android/launcher3/anim/SpringAnimationBuilder.java:11         │
├────────────────────────────────────────────────────────────────────────┤
│ 帧调度层                                                                 │
│   androidx.core.animation.AnimationHandler        (ValueAnimator 路径)   │
│        androidx/core/animation/AnimationHandler.java:12                  │
│   androidx.dynamicanimation.animation.AnimationHandler (Spring 路径)     │
│        androidx/dynamicanimation/animation/AnimationHandler.java:12      │
│   android.animation.AnimationHandler（框架 @hide 类）                    │
│        ← MultiDynamicAnimation 直接实现其 AnimationFrameCallback :21     │
│   三者共同落点: Choreographer.postFrameCallback (主线程, 每 VSYNC)        │
│        androidx/core/animation/AnimationHandler.java:82,101-102          │
└────────────────────────────────────────────────────────────────────────┘
```

★ = 旧文档漏掉的第 4 层调度路径；☠ = 无调用方的死代码（旧文档误当作活跃机制大篇幅分析）。

---

## 2. 帧调度层：Choreographer 挂载点

### 2.1 ValueAnimator 路径：`androidx.core.animation.AnimationHandler`

文件 `androidx/core/animation/AnimationHandler.java:12`，包级可见（`class AnimationHandler`，非 public）。核心链路：

- `AnimationFrameCallback` 接口：`boolean doAnimationFrame(long frameTime)`（`:20`），返回值表示"动画是否已结束"。
- `FrameCallbackProvider16`（`:82`）实现 `Choreographer.FrameCallback`，其 `postFrameCallback()`（`:101-102`）直接调 `Choreographer.getInstance().postFrameCallback(this)`——这是整套时序动画挂到 VSYNC 的唯一桥梁。
- 帧循环：`doAnimationFrame(long)`（`:130`）遍历所有注册的 callback 逐个调 `doAnimationFrame`（`:134`）；之后若仍有未结束的动画则再次 `postFrameCallback()`（`:198-200`），形成自续帧循环。
- `getInstance()`（`:158`）按线程返回单例（ThreadLocal 语义），`addAnimationFrameCallback(...)`（`:174-176`）在首个 callback 注册时启动帧循环。

ValueAnimator 侧：vendored `androidx/core/animation/ValueAnimator.java:21` `implements AnimationHandler.AnimationFrameCallback`，帧入口 `doAnimationFrame(long)`（`:364`），每帧算 fraction 后调 `animateValue(float)`（`:321`）向 `AnimatorUpdateListener` 派发当前值。

### 2.2 SpringAnimation 路径：`androidx.dynamicanimation.animation.AnimationHandler`

文件 `androidx/dynamicanimation/animation/AnimationHandler.java:12`。与 core 版同构但有两点差异：

- 多一层 `AnimationCallbackDispatcher`（`:22`）中间转发（`:29` 调 `animationHandler.doAnimationFrame(mCurrentFrameTime)`）；
- `addAnimationFrameCallback(callback, delayedStartTime)`（`:135`）带启动延迟参数，供 spring 动画的延迟启动使用。
- 帧循环本体 `doAnimationFrame(long)`（`:147-152`）同样逐个回调并自续。

两条 AnimationHandler 路径**互相独立**（各自的 ThreadLocal 单例、各自的 callback 列表），但终点都是同一个主线程 `Choreographer`。

### 2.3 直挂路径：`MultiDynamicAnimation` 绕过 ValueAnimator

`com/android/quickstep/util/animation/MultiDynamicAnimation.java:21`：

```java
public final class MultiDynamicAnimation implements AnimationHandler.AnimationFrameCallback {
```

注意它 import 的是 **`android.animation.AnimationHandler`**（文件 `:3`），即框架的 @hide AnimationHandler，不是上面两个 androidx 版。挂载点：

- `startAnimationInternal()` → `AnimationHandler.getInstance().addAnimationFrameCallback(this, 0L)`（`:127`）；
- 帧回调 `doAnimationFrame(long frameTime)`（`:152`）；
- 结束时 `AnimationHandler.getInstance().removeCallback(this)`（`:95`，在 `endAnimationInternal` 内）。

这是**第三处** Choreographer 挂载路径：不经过 ValueAnimator/ObjectAnimator 包装，自己消费帧时间戳、自己决定何时摘回调。`.idea/workspace.xml:53` 中该类 `:26` 设有断点，说明它是调试时的运行时热点。

---

## 3. 动画引擎层：六个引擎类的职责与生死

| 类 | 文件 | 模式 | 调用方 |
|---|---|---|---|
| `AnimatorPlaybackController` | `com/android/launcher3/anim/AnimatorPlaybackController.java:25` | 进度驱动 | 活跃（Quickstep 转场） |
| `OplusValueAnimator` | `com/oplus/quickstep/utils/OplusValueAnimator.java:32` | timeController 委托 + 续行 | 活跃（续行 helper） |
| `MultiDynamicAnimation` | `com/android/quickstep/util/animation/MultiDynamicAnimation.java:21` | 多弹簧聚合 | 活跃（CustomRectFSpringAnim） |
| `OplusSpringObjectAnimator` | `com/oplus/quickstep/anim/OplusSpringObjectAnimator.java:19` | SpringProperty 双驱动装饰器 | **无调用方，死代码** |
| `painteranimation.OplusSpringAnimation` | `com/oplus/painteranimation/OplusSpringAnimation.java:16` | 弹簧外壳 + 录制挂钩 | painter 子系统 |
| `SpringAnimationBuilder` | `com/android/launcher3/anim/SpringAnimationBuilder.java:11` | 公式法弹簧 → 普通 ValueAnimator | 活跃（多处） |

### 3.1 AnimatorPlaybackController —— 一套进度驱动 N 个子动画

`com/android/launcher3/anim/AnimatorPlaybackController.java:25`，`implements ValueAnimator.AnimatorUpdateListener`。

- 构造（`:127`）内部建一个 `ValueAnimator.ofFloat(0f, 1f)` 作为 `mAnimationPlayer`（`:28`，`:131` 赋值），它本身是唯一真正挂帧循环的动画。
- 子动画包成 `Holder`（`:38` 起）：记录原 interpolator、`globalEndProgress`；`Holder.setProgress(float)`（`:58`）= `anim.setCurrentFraction(mapper.getProgress(f, globalEndProgress))`——子动画**不跑自己的帧循环**，全靠被 `setCurrentFraction` 推进。
- 帧分发：`onAnimationUpdate`（`:304`）拿到全局 fraction 后逐个 `Holder.setProgress`。
- `start()`（`:375-378`）/ `reverse()`（`:348-351`）：把 `mAnimationPlayer` 的 floatValues 设为 `mCurrentFraction → 1.0 / 0.0`，duration 用 `clampDuration(...)`（`:222`）按剩余进度折算——这就是"手势中断后从当前进度继续/倒放"的实现。
- 结束派发：**内部类** `OnAnimationEndDispatcher extends AnimationSuccessListener`（`:63-107`）。`onAnimationSuccess`（`:91`）里先 `dispatchOnEnd()`（`:95`）递归触发每个子 Animator 的 end listener，再跑 `mEndActionMap` 里的 Runnable，`mDispatched` 防重。`dispatchOnEnd()` 本体在 `:236`。
- 阈值常量 `ANIMATION_COMPLETE_THRESHOLD = 0.95f`（`:26`）：`forceFinishIfCloseToEnd`（`:256`）在进度 ≥0.95 时直接 `end()`。

### 3.2 OplusValueAnimator —— timeController 委托 + 续行动画

`com/oplus/quickstep/utils/OplusValueAnimator.java:32`，Kotlin 写的 `final class OplusValueAnimator<T> extends ValueAnimator`（SMAP 显示源自 `AppToOverviewContinuationHelper.kt`）。

三个成员（`:33-35`）：`name`、`AnimParam<T> param`、`ObjectAnimator timeController`。

委托模式：当构造时传入了 `timeController`，`addListener`（`:194`）、`cancel`（`:209`）、`end`（`:225`）、`pause`（`:267`）、`start`（`:301`）全部转发给 `timeController`，`this` 自身不进帧循环；`isRunning`/`getDuration`/`getCurrentPlayTime` 也读 timeController。构造时注册的 update listener（`_init_$lambda$0`，`:169`）把 `getAnimatedValue()` 转交给 `param.getValueApplicator().applyValue(...)`。

续行工厂 `Companion.generateContinuationAnim(anim, continuationAnimDuration)`（`:89-119`，静态入口 `:181`）：

1. 从旧 anim 的 `param.getCurrentFraction()` 取已累积进度；若 interpolator 是 `RecordInputInterpolator`，先用 `getInputed()` 回填（`:94-98`）；
2. `AnimParam.INSTANCE.copy(anim.getParam())`（`:105`）复制全部配置；
3. 新建 `ObjectAnimator` 作为 timeController，`setTarget(newAnim)` + `setProperty(CURRENT_FRACTION)` + `setFloatValues(currentFraction, 1.0f)`（`:107-110`），线性插值器跑剩余进度。

`CURRENT_FRACTION` 是 Companion 上的 `FloatProperty<OplusValueAnimator<?>>`（`:38-56`），set 时走 `setCurrentFraction`（`:283-288`）——后者同时 `super.setCurrentFraction` 并回写 `param.setCurrentFraction`。

**AnimParam** 是 `OplusValueAnimator` 的内部 Kotlin data class：`public static final /* data */ class AnimParam<E>`（`:330`），`copy` 工厂在 `:355` 与 `:415` 附近，`setCurrentFraction` 在 `:466`。

真实调用方：`AppSwipeToRecentContinuationHelper.java:213-220`、`VirtualBtnToRecentContinuationHelper.java:268`（均为"抬手后续行"场景）。

### 3.3 OplusSpringObjectAnimator —— 精巧但无人使用的死代码 ☠

`com/oplus/quickstep/anim/OplusSpringObjectAnimator.java:19`，`public class OplusSpringObjectAnimator<T> extends ValueAnimator`。

**最重要的事实：全树 Grep `OplusSpringObjectAnimator`，命中只有它自己的文件**——没有任何 `new OplusSpringObjectAnimator(...)` 或 import。它是死代码。分析它的价值仅在于理解"ValueAnimator→Spring 渐进切换"这一设计思想，不要在排障时把它当作活跃路径。

机制（仅作设计参考）：

- **SpringProperty 是它的 public static 内部类**（`:31-70`，无独立文件），`extends FloatProperty<T>`：装饰原始 `FloatProperty`，`useSpring=false` 时 `setValue` 直写原 property；`switchToSpring()`（`:61`）后改为 `mSpring.animateToFinalPosition(f)`——同一对象内切换驱动源。
- 构造（`:73-117`）同时建 `SpringAnimation`（androidx dynamicanimation）和一个跑在 `SpringProperty` 上的 `ObjectAnimator`。
- 结束判定 `tryEnding()`（`:146-156`）**只检查 `mAnimatorEnded`**：

```java
private void tryEnding() {
    if (!this.mAnimatorEnded || this.mEnded) {
        return;
    }
    ... 逐个回调 onAnimationEnd ...
    this.mEnded = true;
}
```

`mSpringEnded` 字段在 `:27` 初始化、`:133`（spring update lambda）置 false、`:138`（spring end lambda）置 true，**写而不读**——`tryEnding` 根本不看它。这是遗留逻辑：理论上应等 ObjectAnimator 和 Spring 都结束才发 end，实际只等 ObjectAnimator。
- `startSpring(...)`（`:290-307`）：切 `switchToSpring()` 后用 `Handler(Looper.getMainLooper()).postDelayed(..., getStartDelay())` 调 `spring.animateToFinalPosition(target)`。

### 3.4 MultiDynamicAnimation —— 多弹簧聚合引擎（活跃）

`com/android/quickstep/util/animation/MultiDynamicAnimation.java:21`（Kotlin，`MultiDynamicAnimation.kt`）。

- 持有 `HashMap<String, SpringHolder> mSpringHolderMap`（`:27`），一个实例聚合任意多个命名弹簧。
- `start()`（`:193`）→ 先给所有 SpringHolder `setValuesThreshold`，再 `startAnimationInternal()`（`:121`）挂帧回调（§2.3）。
- 帧体 `doAnimationFrame(long frameTime)`（`:152-176`）：
  1. 首帧只记 `mLastFrameTime` 并 `notifyAnimUpdate()`；
  2. 后续帧算 `deltaT = frameTime - mLastFrameTime`，遍历所有 `SpringHolder.updateValueAndVelocity(deltaT, isEndRequest)`（`:81-89` 的 lambda）；
  3. 全部弹簧收敛（或收到 end/cancel 请求）→ `endAnimationInternal(cancel)`（`:92`）：摘回调、清零帧时间、回调 `OnAnimationEndListener`。
- `requestEnd(boolean cancel)`（`:185`）：置 `mCancelRequest`/`mEndRequest`，下一帧生效——延迟一帧的优雅结束。
- 单弹簧推进由 `SpringHolder.updateValueAndVelocity` 完成，数值积分走 `SpringAnimReflectUtils.updateValues(SpringForce, value, velocity, deltaT, pendingPosition)`（`com/android/quickstep/util/animation/SpringAnimReflectUtils.java:59-62`，反射/复刻 SpringForce 的 RK4 积分，返回 `MultiDynamicAnimation.MessState` 值+速度对）。

**真实调用方**：`CustomRectFSpringAnim`——recents 窗口弹簧动画。它持有 `private MultiDynamicAnimation mMultiDynamicAnimation`（`CustomRectFSpringAnim.java:76`），`:214` 创建，`:395-401` 一次注册 6 个 SpringHolder（centerX / rectY / width / rectRadius / radio / alpha），并挂 update/end listener。这是 Launcher 转场里圆角矩形窗口弹簧的核心执行引擎。

### 3.5 com.oplus.painteranimation.OplusSpringAnimation —— 弹簧外壳 + painter 录制挂钩

`com/oplus/painteranimation/OplusSpringAnimation.java:16`：`public class OplusSpringAnimation extends ValueAnimator implements VeriableModeInterface, Cloneable`。

与 §3.3、§3.4 都不同的**第三个 Spring 类**：

- 内部包一个 androidx `SpringAnimation mAnimation`（`:17`），`start()`（`:338-339`）直接 `mAnimation.start()`——自身 extends ValueAnimator 只是为了让调用方能把它当 Animator 传递，帧驱动完全交给内部 SpringAnimation（即 §2.2 路径）。
- painter 录制挂钩：`mNeedPaintAnim`（`:29`）+ `tryPaintAnimation()`（`:152-156`）在 `mInitialized && mNeedPaintAnim` 时调 `SimulationInteractor.tryPaintAnimation(mSceneName, mPropertyName, getInternalAnimation())`——把弹簧参数录制给 painter 仿真系统；`setUniquePaintingName`（`:326-330`）置位后触发。
- 支持 `cloneWithPaintingName()`（`:198-201`）克隆弹簧参数 + 监听器到新实例。

### 3.6 SpringAnimationBuilder —— 公式法弹簧，真实存在且有调用方

`com/android/launcher3/anim/SpringAnimationBuilder.java:11`（AOSP Launcher3 原生类，非 OPPO 新增）。不做物理积分，而是预先 `computeParams()`（`:88`）解出阻尼振荡方程参数（beta/gamma/va/vb），`build(target, property)`（`:69`）返回一个**普通 ValueAnimator**，每帧用 `getInterpolatedValue(fraction)` 按公式 `exponentialComponent * cosSin` 算值写到 `FloatProperty`。`isAtEquilibrium`（`:60`）判定收敛。

真实调用方（Grep 实证）：`com/android/quickstep/util/StaggeredWorkspaceAnim.java:89`、`com/android/quickstep/util/RecentsAtomicAnimationFactory.java:35`、`com/android/quickstep/util/OplusRecentsAtomicAnimationFactoryImpl.java:33`、`com/android/quickstep/FallbackSwipeHandler.java:214`。

---

## 4. 状态机层：AnimationController 的 12 状态

### 4.1 枚举与产出

`AnimationController` 位于 **`com/oplus/quickstep/utils/AnimationController.java`**（注意：不是旧文档写的 `com/android/quickstep/util/animation/` 路径）。类声明 `:58`：`public final class AnimationController extends DefaultAnimationController`。

`AnimationState` 枚举（`:103-115`），**共 12 个状态**（旧文档错写成 11 个，漏了末尾两个）。每个枚举值带两个布尔：`withTaskbarAlignment`、`taskbarAlignmentToLauncher`：

```java
public enum AnimationState {
    NONE(false, false),
    OPEN(false, false),
    REVERSE_OPEN(true, false),
    CLOSE(true, true),
    MULTI_OPEN(true, false),
    MULTI_REVERSE_OPEN(true, false),
    MULTI_CLOSE(true, true),
    WAITING(false, false),
    MULTI_WAITING(false, false),
    UNKNOWN(false, false),
    SWIPE_UP_TO_CAPSULE(true, true),
    SWIPE_UP_TO_SPLIT_OR_FLOATING(true, true);
}
```

当前状态字段 `mAnimState`（`:88`，初值 `NONE`）。产出工厂：`OplusAnimManager` 的 `getAnimController()`（`com/oplus/quickstep/utils/OplusAnimManager.java:97`）——`supportInterruption() ? new AnimationController() : new DefaultAnimationController()`，即 feature 关闭时整个状态机降级为空实现的默认控制器。同文件 `:101-117` 还有 AnimationSeqHelper / AppOpenAnimMergeHelper / InterceptKeyEventHelper / MultiAppAnimMergeHelper / MultiOpenPreStartHelper 五个同模式工厂。

### 4.2 状态迁移（全部经 `updateAnimState`，`:457-468`）

`updateAnimState` 统一入口：打日志 → 赋 `mAnimState` → 回调 `onAnimStateChanged(old, new, runningTask)`（`:462`）→ 更新 `mForbidSwipeUpWhileStartingLandApp` → 进入 `UNKNOWN` 时打 `Error animation state` 警告。

四条迁移触发路径：

| 触发方法 | 位置 | 迁移规则 |
|---|---|---|
| `appLaunchAnimStartOrEnd(isEnd=false)` | `:508-545` | NONE→OPEN；CLOSE/MULTI_CLOSE→MULTI_OPEN；其他→UNKNOWN |
| `appLaunchAnimStartOrEnd(isEnd=true)` | `:511-530` | 无开启动画残留时 `checkAllAnimationFinished()`；若 `mOnceGestureProcessing`：OPEN→WAITING / MULTI_OPEN→MULTI_WAITING |
| `addRecentsAnim(...)` | `:471-505` | OPEN/NONE/REVERSE_OPEN/WAITING→CLOSE；MULTI_OPEN/MULTI_WAITING/MULTI_REVERSE_OPEN→MULTI_CLOSE；其他→UNKNOWN |
| `revertRecentsAnimation(...)` | `:825-837` | CLOSE→REVERSE_OPEN；MULTI_CLOSE→MULTI_REVERSE_OPEN；其他→UNKNOWN |
| `reset()` | `:811-822` | →NONE，并清空全部 anim 列表与回调 |

事件来源：`appLaunchAnimStartOrEnd` 由 `LauncherAnimationRunner`（Binder 回调线程切 UI 线程后）经 factory 接口上报（`com/android/launcher3/LauncherAnimationRunner.java:367,376`；接口默认方法 `:243`；QuickstepTransitionManager 的实现转发在 `com/android/launcher3/QuickstepTransitionManager.java:2131-2133`）；`addRecentsAnim` 由手势处理器上报（`com/oplus/quickstep/gesture/OplusBaseSwipeUpHandler.java:3776`）。

### 4.3 "doFrame 链路"的真实位置

AnimationController **自己不进帧循环**——它是纯事件驱动状态机，挂在动画 listener 上。帧链路与状态机的衔接点：

```
Choreographer (主线程 VSYNC)
  └ AnimationHandler.doAnimationFrame           androidx/core/animation/AnimationHandler.java:130
      └ ValueAnimator.doAnimationFrame → animateValue(fraction)
          androidx/core/animation/ValueAnimator.java:364, :321
          └ AnimatorPlaybackController.onAnimationUpdate → Holder.setProgress
              com/android/launcher3/anim/AnimatorPlaybackController.java:304, :58
              └ 子动画 listener.onAnimationEnd
                  └ AnimationController$addRecentsAnim$1.onAnimationEnd
                      com/oplus/quickstep/utils/AnimationController.java:182,:203
                      └ removeTasks / 状态收尾 / executeRecentMainFinishCallback (:237)
```

并行地，`MultiDynamicAnimation.doAnimationFrame`（`MultiDynamicAnimation.java:152`）走自己的直挂路径驱动 `CustomRectFSpringAnim` 的 6 个弹簧，其 end listener（`CustomRectFSpringAnim.java:176-212` 的 `AnonymousClass2`）同样回流到状态机的 recents anim 收尾逻辑（`mRecentsAnims.remove(...)` 在 `AnimationController.java:206`）。

结束聚合：`checkAllAnimationFinished()`（`:224-232`）在 `mAppLaunchAnims` 与 `mRecentsAnims` 均空时执行 `executeRemoteMergeFinishCallback()` + `executeRecentMainFinishCallback()` + `reset()`；`canFinishRecentsAnim(...)`（`:559-578`）决定某个 recents 动画是否允许 finish（开启动画未清空、seq 防抖、`mJustNotifyEndCallback` 任一不满足则拒绝）。

### 4.4 超时兜底（防状态机卡死）

状态机为"等不到系统回调"准备了三类 `TaskStateHelper.TaskStateChangeTimeOutListener`：

- `registerTransitionFinishListener(1500)`（`:372`）：等 transition finish；
- `registerSpecialSceneExitTimeOutListener(1500/2500)`（`:333`）：横屏/分屏特殊场景退出；
- `registerOverviewContinuationTimeOutListener(100)`（`:297`）：app→overview 续行。

超时回调统一把暂存的 `startActivityRunnable` 丢到主线程执行并清场。配合 `delayStartActivityIfNeed(...)`（`:598-670`）在特殊场景/续行进行中延迟 `startActivity`。

---

## 5. 中断与接续

### 5.1 supportInterruption() 的真实条件（运行时，非编译期常量）

`com/oplus/quickstep/utils/OplusAnimManager.java:232-234`：

```java
public final boolean supportInterruption() {
    return (!LauncherAnimConfig.INSTANCE.isAppTransitionByLightAnim()
            || LauncherAnimConfig.isAdaptiveAnimation())
        && TaskAnimationManager.ENABLE_SHELL_TRANSITIONS
        && AppFeatureUtils.INSTANCE.isSupportBlockableAnimation();
}
```

三个**运行时求值**的条件相与：① 非轻量转场动画（或自适应动画豁免）；② Shell Transition 开关；③ 可阻断动画 feature 开关。旧文档把它写成编译期常量是错误的。另有重载 `supportInterruption(ItemInfo)`（`:248-269`）在此基础上排除 zoom 窗口包名与分屏组合图标。

### 5.2 中断时的动画接续机制

中断接续有三套并存的实现，按场景选用：

1. **进度续播（同对象）**：`AnimatorPlaybackController.reverse()`（`:348-351`）——`mAnimationPlayer` 从 `mCurrentFraction` 设值回放到 0，duration 按剩余进度 `clampDuration` 折算。手势 reversal 用这套。
2. **对象接力（跨对象）**：`OplusValueAnimator.generateContinuationAnim(...)`（`OplusValueAnimator.java:89-119`）——从旧动画的 `currentFraction` 复制 `AnimParam`，新建动画用 timeController 线性跑 `currentFraction → 1.0`。抬手后续行到 overview/桌面用这套（`AppSwipeToRecentContinuationHelper.java:213-220`）。
3. **物理接管（弹簧聚合）**：`MultiDynamicAnimation` 的 SpringHolder 带着当前 value+velocity 继续积分（`SpringAnimReflectUtils.java:59-62`），`requestEnd` 延迟一帧优雅结束（`MultiDynamicAnimation.java:184-190`）。recents 窗口弹簧用这套。

状态机层面的中断保护：`setOnceGestureProcessing(OplusGestureState)`（`AnimationController.java:898`）记录一次手势的上下文（横屏/分屏/续行场景），并按场景注册 §4.4 的超时 listener；`forbidTouch()`（`:685-688`）在开窗口动画运行、`MULTI_WAITING`、`REVERSE_OPEN` 或存在暂存 startActivity 时禁止新触摸输入。

---

## 6. 时间戳同步：AnimSeqTimeStamp

`com/android/systemui/shared/system/AnimSeqTimeStamp.java:10`，Kotlin `object` 单例（`INSTANCE` 在 `:12`），属于 systemui shared 库（`SystemUISharedLib_release`），launcher 与 systemui 共享同一份。

4 个 `private static long` 字段（`:13-16`）：`lastLaunchTaskTimeMills`、`lastRecentFinishTimeMills`、`lastRecentStartTimeMills`、`lastStartAppTimeMillis`。全部 12 个方法（4 组 update/reset/getTimeGap）都是 **`@JvmStatic static final synchronized`**——类级锁，跨线程读写安全。时间基准统一为 `SystemClock.uptimeMillis()`。

消费方是 `AnimationSeqHelper`（`com/oplus/quickstep/utils/AnimationSeqHelper.java`）：

- `:70` `canFinishRecent()`：`isSupportStartingSurface && supportInterruption && getTimeGapToLastRecentFinishTime() <= 500` 时拒绝 finish——500ms 防抖窗口；
- `:75`：同理用 `getTimeGapToLastStartAppTime() <= 300` 做 300ms 窗口。

设计要点：launcher 的 recents 动画与 systemui 的 starting surface 是两个进程内模块，共享 `AnimSeqTimeStamp` 这个进程级单例做"上一次同类事件距今多久"的判定，避免各自记时间戳对不上。

---

## 7. 与"子线程更新 UI"的关系

结论与 `sub-thread-ui-update.md` 一致，这里只挂接本文的类：

- **所有帧推进都在主线程**：三条 AnimationHandler 路径（§2）最终都落到主线程 `Choreographer`；`doAnimationFrame` / `animateValue` / listener 派发同步发生在主线程。业务在 `onAnimationUpdate` 里写 View 是合法的。
- **子线程能做的只是触发生命周期**：`AsyncValueAnimator`（`com/android/quickstep/util/animation/AsyncValueAnimator.java:24`）+ `AsyncAnimCallbacks`（同目录 `:23`）+ `LooperExecutor`（`com/oplus/basecommon/thread/LooperExecutor.java:12`）把子线程的 `start/cancel/end` marshal 到主线程 Looper；listener 再兜底 marshal 一次。
- 因此"动画线程"在这套架构里不是一个真实存在的执行线程，而是"主线程上的帧回调域 + 允许任意线程发起的入口包装"。

---

## 8. 修订记录（v3，相对旧版 2460 行文档）

| # | 修正 | 证据 |
|---|---|---|
| 1 | `AnimationState` 是 **12** 个状态（旧版写 11），且文件在 `com/oplus/quickstep/utils/` 而非旧版写的 quickstep util/animation 路径 | `com/oplus/quickstep/utils/AnimationController.java:103-115` |
| 2 | `SpringProperty` 是 `OplusSpringObjectAnimator` 的内部类，无独立文件 | `com/oplus/quickstep/anim/OplusSpringObjectAnimator.java:31-70` |
| 3 | ~~`SpringAnimationBuilder.java` 不存在~~ — **此条修正本身有误**：该类真实存在于 `com/android/launcher3/anim/SpringAnimationBuilder.java:11` 且有 4 处真实调用方，见 §3.6 | 同左 + `StaggeredWorkspaceAnim.java:89` 等 |
| 4 | `AnimParam` 是 `OplusValueAnimator` 的内部 data class | `com/oplus/quickstep/utils/OplusValueAnimator.java:330` |
| 5 | `OnAnimationEndDispatcher` 是 `AnimatorPlaybackController` 的内部类 | `com/android/launcher3/anim/AnimatorPlaybackController.java:63-107` |
| 6 | `ActualEndAnimListener` 在 `quickstep/util/animation/`，不在 `launcher3/anim/` | `com/android/quickstep/util/animation/ActualEndAnimListener.java:9` |
| 7 | `OplusSpringObjectAnimator` **无任何调用方（死代码）**，旧版当作活跃机制分析是误导 | 全树 Grep 仅自身文件命中，§3.3 |
| 8 | `tryEnding()` 只查 `mAnimatorEnded`，`mSpringEnded` 写而不读（遗留逻辑） | `OplusSpringObjectAnimator.java:146-156`，字段写点 `:27,:133,:138` |
| 9 | `AnimSeqTimeStamp` 是 Kotlin object 单例，4 个 static long，方法全部 `@JvmStatic synchronized` | `com/android/systemui/shared/system/AnimSeqTimeStamp.java:10-16` |
| 10 | `supportInterruption()` 是 3 个运行时条件相与，非编译期常量 | `OplusAnimManager.java:232-234` |
| 11 | 补充第 4 层调度：`MultiDynamicAnimation` 直挂 `android.animation.AnimationHandler`，绕过 ValueAnimator | `MultiDynamicAnimation.java:21,127,152` |
| 12 | 补充第三个 Spring 类 `com.oplus.painteranimation.OplusSpringAnimation`（弹簧外壳 + painter 录制挂钩） | `com/oplus/painteranimation/OplusSpringAnimation.java:16,152-156` |

---

## 附录 A. 关键文件清单

| 文件 | 行号锚点 | 内容 |
|---|---|---|
| `com/oplus/quickstep/utils/AnimationController.java` | 58 / 88 / 103-115 / 457 / 471 / 508 / 811 / 825 | 12 状态转场状态机 |
| `com/oplus/quickstep/utils/OplusAnimManager.java` | 97-117 / 232-234 | 工厂 + supportInterruption |
| `com/oplus/quickstep/utils/OplusValueAnimator.java` | 32 / 89-119 / 194 / 283-288 / 330 | timeController 委托 + 续行 |
| `com/oplus/quickstep/anim/OplusSpringObjectAnimator.java` | 19 / 31-70 / 146-156 / 290-307 | 双驱动装饰器（死代码） |
| `com/android/quickstep/util/animation/MultiDynamicAnimation.java` | 21 / 92 / 127 / 152 / 185 / 193 | 多弹簧聚合引擎 |
| `com/android/quickstep/util/animation/CustomRectFSpringAnim.java` | 76 / 214 / 395-401 | MultiDynamicAnimation 的真实调用方 |
| `com/android/quickstep/util/animation/SpringAnimReflectUtils.java` | 59-62 | 弹簧数值积分 |
| `com/android/quickstep/util/animation/ActualEndAnimListener.java` | 9 | 实际结束回调基类 |
| `com/android/launcher3/anim/AnimatorPlaybackController.java` | 25 / 58 / 63-107 / 127 / 236 / 304 / 348 / 375 | 进度驱动控制器 |
| `com/android/launcher3/anim/SpringAnimationBuilder.java` | 11 / 60 / 69 / 88 | 公式法弹簧 |
| `com/oplus/painteranimation/OplusSpringAnimation.java` | 16 / 152-156 / 338 | painter 弹簧外壳 |
| `com/android/systemui/shared/system/AnimSeqTimeStamp.java` | 10-16 | 跨模块时间戳单例 |
| `com/oplus/quickstep/utils/AnimationSeqHelper.java` | 70 / 75 | 时间戳消费方（防抖窗口） |
| `androidx/core/animation/AnimationHandler.java` | 20 / 82 / 101-102 / 130 / 158 / 174 | ValueAnimator 帧调度 |
| `androidx/core/animation/ValueAnimator.java` | 21 / 321 / 364 / 770 | vendored ValueAnimator |
| `androidx/dynamicanimation/animation/AnimationHandler.java` | 22 / 135 / 147 | Spring 帧调度 |
| `com/android/launcher3/LauncherAnimationRunner.java` | 243 / 367 / 376 | Binder→UI 转场事件入口 |
| `com/android/quickstep/util/animation/AsyncValueAnimator.java` | 24 | 跨线程入口包装 |
| `com/oplus/basecommon/thread/LooperExecutor.java` | 12 | 跨 Looper Executor |
