# 对比 Review 15：`android.animation.AnimationHandler` + `MultiDynamicAnimation` 路径

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/`（`core/anim/AnimationHandler.kt`、`launcher/async/AsyncSpringAnim.kt`、`launcher/async/CustomRectFSpringAnim.kt`）
> - **原厂**：`D:/oppo_a6_launcher/sources/com/android/quickstep/util/animation/{MultiDynamicAnimation.java, SpringHolder.java, SpringForce.java, SpringAnimReflectUtils.java, CustomRectFSpringAnim.java}`
>
> 本区域是前 14 份 review（特别是 `04-frame-scheduling.md` + `05-continuation-spring.md` + `11-feature-gaps.md`）的"弹簧帧循环栈"专项深挖。前文已点名 `MultiDynamicAnimation` / `SpringHolder` / `SpringForce` / `SpringAnimReflectUtils` 整组缺失，本区域下沉到**方法 / 字段 / 帧时序层面**——逐条核对原厂 `doAnimationFrame` (`:152-159`)、`requestEnd` (`:185-191`)、`startAnimationInternal` (`:121-130`)、`endAnimationInternal` (`:92-103`)、`commitAnimationFrame` (`:148-150`)、`SpringHolder.updateValueAndVelocity` (`:106-138`)、`SpringHolder.setValuesThreshold` (`:102-104`)、`SpringForce.updateValues` 三支闭式 (`:111-156`)、`SpringAnimReflectUtils.updateValues` 直调 (`:59-63`) 与 `isAtEquilibrium` 反射 (`:29-48`) 的语义差异，并量化 lib 的可移植性边界。
>
> 已知边界：lib 注释明示"实际动画逻辑由 androidx SpringAnimation 实现"（`CustomRectFSpringAnim.kt:8-10`）；review 04 §4.2-2 + review 11 §4.2-1/2 已建议保持简化。本区域不复述"建议"层面，专注**保真度差异的代码层面证据** + **若要回移需要的精确接口面**。
>
> 取证方法：lib 侧 Python `open(path, encoding='utf-8')` 读明文；原厂侧同样 Python 读明文（文件已 IDE 解密），行号为 JADX 反编译文本行号。80% DLP 加密层在 Python 路径下透明。

---

## 1. 类对应关系表

| lib 类 / 成员 | 原厂类 / 成员 | 关系与证据 |
|---|---|---|
| （无对应 lib 类） | `com/android/quickstep/util/animation/MultiDynamicAnimation.java:21` `public final class MultiDynamicAnimation implements AnimationHandler.AnimationFrameCallback` | **完全缺失**。lib 没有任何"多弹簧协调器"。原厂 201 行，把 N 个独立弹簧放在 `HashMap<String, SpringHolder> mSpringHolderMap` 里共享一个 AnimationHandler 帧回调，所有弹簧在同一帧内同步积分 |
| `core/anim/AnimationHandler.kt:25` `fun interface AnimationFrameCallback { fun doAnimationFrame(frameTimeMs: Long): Boolean }` | `android/animation/AnimationHandler.AnimationFrameCallback` (`MultiDynamicAnimation.java:21` `implements` 入口) + `androidx/core/animation/AnimationHandler.java:20` `public interface AnimationFrameCallback { boolean doAnimationFrame(long j8); }` | **签名一致，但 lib 模仿的是 AndroidX 而不是 AOSP**。OPPO `MultiDynamicAnimation` 用的是 platform `@hide android.animation.AnimationHandler`（同 AndroidX 有 1:1 镜像 API），lib `AnimationHandler.kt:17` 类注释写"对应 Android 平台 `androidx.core.animation.AnimationHandler`（简化版）"，是 AndroidX 副本。两条路径在 API 表面等价，但**底层的 `getInstance().addAnimationFrameCallback(this, 0L)` 是平台隐藏 API**（`MultiDynamicAnimation.java:127`），lib 的 `installThreadScheduler` + `HandlerTickScheduler` 走的是 `Handler.postDelayed` 帧环，不走 `AnimationHandler` 帧源 |
| `core/anim/AnimationHandler.kt:32-38` `addAnimationFrameCallback` + `removeCallback`（懒删除） | `androidx/core/animation/AnimationHandler.java:180-186` `addAnimationFrameCallback`（首注册时 `mProvider.postFrameCallback()`）+ `cleanUpList()` (`:117-132`) | **镜像**。两者都用"list 为空时首触发 + 懒 null 槽"模式。**关键偏差**：AndroidX `addAnimationFrameCallback(cb)` 不带第二个参数，OPPO `MultiDynamicAnimation.java:127` 调的是 `AnimationHandler.getInstance().addAnimationFrameCallback(this, 0L)`——**第二个参数 `0L` 是隐藏 API 的 frame delay**，lib 的 `addAnimationFrameCallback(callback: AnimationFrameCallback?)` 单参签名无法对应 |
| `core/anim/AnimationHandler.kt:85-92` `doAnimationFrame` 顺序遍历 + `runCatching` 异常隔离 | `androidx/core/animation/AnimationHandler.java:130-137` `doAnimationFrame` 顺序遍历 | **镜像**。两者都没异常隔离，但 OPPO 在 `MultiDynamicAnimation.doAnimationFrame`（`:152-159`）有自定义：见下方"裸 delta 积分" |
| （lib `AsyncSpringAnim.kt:38-43` 间接） `addEndListener { _, canceled, _, _ -> }` 4 参 lambda | `MultiDynamicAnimation.java:64` `public interface OnAnimationEndListener { void onAnimationEnd(MultiDynamicAnimation, boolean z8); }` 2 参 | **签名差异**。lib 用的是 androidx 的 4 参 `DynamicAnimation.OnAnimationEndListener`（OnAnimationEndListener, canceled, value, velocity），原厂 OPPO 自定义的是 2 参（this + canceled）；在框架层二者不可互换。`AsyncSpringAnim.addEndListener` 接受的是 androidx 类型，**无法被接入 `MultiDynamicAnimation.addAnimationEndListener`** |
| （lib 无对应） | `MultiDynamicAnimation.java:185-191` `public final void requestEnd(boolean z8)`：若 z8 则 `mCancelRequest = true`、否则 `mEndRequest = true` | **完全缺失**。"下一帧生效"语义：requestEnd 不立即停帧循环，只是置 flag，下一帧 `doAnimationFrame` 在 `applyToAllSpringHolder` 之后、清理 `mCancelRequest/mEndRequest` 标志后，再由"所有弹簧 at equilibrium"触发 `endAnimationInternal(z8)`。lib 的 `AsyncSpringAnim.cancel()` 直接调 `real.cancel()`——androidx `SpringAnimation.cancel()` 立即设 `mRunning = false` 并清回调列表，无"下一帧生效"窗口 |
| （lib 无对应） | `MultiDynamicAnimation.java:152-183` `doAnimationFrame(long frameTime)`：裸 delta 积分（`frameTime - mLastFrameTime`） + 首帧特殊处理（`mLastFrameTime == 0`） | **完全缺失**。lib 走 androidx SpringAnimation 内部的 Choreographer 帧回调——它**自己**维护 dt 累积（通过 `mLastFrameTime` 字段）但**不暴露**给 lib；lib 拿不到 dt，只能从 `addUpdateListener` 的 value/velocity 增量间接推断 |
| （lib 无对应） | `MultiDynamicAnimation.java:81-90` `doAnimationFrame$lambda$2`：每弹簧 `updateValueAndVelocity(deltaT, isEndRequest)` 返回 true 表示"可结束"（at equilibrium），false 表示"继续"。`isEndRequest \|\| zUpdateValueAndVelocity` 时不改变 `zArr[0]`，否则置 `zArr[0]=false` 保持动画继续 | **完全缺失**。lib 用 androidx 单一 SpringAnimation，无多弹簧聚合判定；每个弹簧独立判定 atRest，独立 stop。语义差异：原厂**所有弹簧都 atRest 才整体结束**；lib 任一弹簧 atRest 都会单独停帧 |
| （lib 无对应） | `MultiDynamicAnimation.java:121-128` `startAnimationInternal()`：`if (mRunning) return; mRunning = true; AnimationHandler.getInstance().addAnimationFrameCallback(this, 0L);` | **完全缺失**。lib 的 `AsyncSpringAnim.start()` 调 `real.start()`——androidx 路径会注册回调到 androidx.core.animation.AnimationHandler，但 MultiDynamicAnimation 注册的是 platform android.animation.AnimationHandler，二者**互不相通** |
| （lib 无对应） | `MultiDynamicAnimation.java:92-103` `endAnimationInternal(boolean z8)`：`mRunning = false; AnimationHandler.getInstance().removeCallback(this); mLastFrameTime = 0; for (listener : mEndListeners) listener.onAnimationEnd(this, z8);` | **完全缺失**。"先停帧循环、再 fire listener"的顺序在 lib 侧反向：androidx SpringAnimation 是在 `mRunning = false` 之前 fire listener，导致 listener 触发时动画仍在跑（rare 但可观察） |
| （lib 无对应） | `MultiDynamicAnimation.java:148-150` `public void commitAnimationFrame(long j8) { doAnimationFrame(j8); }` | **完全缺失**。这是一个 `DynamicAnimation` 兼容入口（公开 `commitAnimationFrame` 是 platform `DynamicAnimation.OnAnimationEndListener` 接口的扩展点）。lib 完全不走 DynamicAnimation 树 |
| `launcher/async/AsyncSpringAnim.kt:14-43` `AsyncSpringAnim` 包装 androidx `SpringAnimation` | （无对应原厂类，但 OPPO 有 `OplusAsyncSpringAnimWrapper.java`，未在本次扫描范围内） | **降级**。lib 用 androidx `SpringAnimation`（公开 API）；原厂 OPPO fork 了一份自己的 `SpringAnimation`（`com/android/quickstep/util/animation/SpringAnimation.java` 31 行，extends `DynamicAnimation<SpringAnimation>`），有自己的 `animateToFinalPosition` + `mEndRequested` + `finishToEndImmediately` + `skipToEnd` + `canSkipToEnd`（damping > 0 判定）。**lib 直接用了 androidx 等价 API，没复刻 OPPO fork 行为**——但 OPPO fork 在内部也是调 androidx 公开方法（除 `finishToEndImmediately` 等少数扩展点），业务可观察差异常常体现在"androidx vs OPPO fork"的细节不一致（如 `skipToEnd` 在 damping == 0 时 OPPO fork 抛 `UnsupportedOperationException` 而 androidx 是 no-op） |
| `launcher/async/CustomRectFSpringAnim.kt:14-18` **占位**：仅 18 行 + `AnimType` 枚举（3 值） | `com/android/quickstep/util/animation/CustomRectFSpringAnim.java:42` `public final class CustomRectFSpringAnim`：812 行 Kotlin 反编译产物（`@SourceDebugExtension` 标注），持有 6 组独立 SpringForce + SpringHolder 字段 + `mMultiDynamicAnimation`（`MultiDynamicAnimation` 实例）+ 7 值 `AnimType` 枚举（`:115-123`）+ 6 自由度矩形换算 `calculateFrameRectF` (`:266-328`) + `mAnimLooperExecutor` ANIM/MAIN 双路 + 4 路线程纠偏（start/cancel/skipToEnd/reverseToOpen） | **完全降级为占位**。原厂 812 行的 6 自由度矩形弹簧动画是 MultiDynamicAnimation 的**唯一生产消费者**（`initProperty` (`:339-369`) 把 6 个 SpringHolder 都塞进 `mMultiDynamicAnimation.addSpringHolderItem(...)`），lib 用 androidx 单一 SpringAnimation 完全替代。**AnimType 枚举不一致**：lib 3 值（SWIPE_TO_HOME / RECENTS_TRANSITION / APP_LAUNCH）vs 原厂 7 值（OPEN_FROM_HOME / REMOTE_CLOSE_TO_HOME / REMOTE_CLOSE_TO_HOME_ASSISTANT / GESTURE_TO_DRAG / SWIPE_TO_HOME / SWIPE_TO_HOME_ASSISTANT / REVERSE_TO_OPEN）。最常用的 `OPEN_FROM_HOME` 在 lib 枚举里没有 |
| （lib 无对应） | `com/android/quickstep/util/animation/SpringHolder.java:9` `public final class SpringHolder`：139 行，per-spring 状态持有者（`mValue/mVelocity/mPendingPosition/mMaxValue/mMinValue/mMinVisibleChange/mStartDelay/mSpringForce/mKey`），`updateValueAndVelocity(deltaT, endRequest)` 在 `mPendingPosition != UNSET` 时**按 deltaT/2 拆两半步积分**（`:115-130`），`setValuesThreshold` 把 `mMinVisibleChange * 0.75` 通过反射写入 `SpringForce.mValueThreshold`（`:102-104` + `SpringAnimReflectUtils.setValueThreshold`） | **完全缺失**。`mPendingPosition` 半步分裂积分是原厂的核心创新（中途改终点时不出现速度/位置阶跃），lib 用 androidx 单一 spring 不支持。`getValueThreshold = mMinVisibleChange * 0.75`（`:33-35`）也是原厂特定常量——androidx 公开 API 的 threshold 默认值是另一套 |
| （lib 无对应） | `com/android/quickstep/util/animation/SpringForce.java:6` `public class SpringForce implements Force`：167 行，**自实现三支闭式解析解**（`:111-156` `updateValues`）：<br>- dampingRatio > 1（过阻尼）：双指数 `e^(γ+·t)` + `e^(γ-·t)`<br>- dampingRatio == 1（临界）：单指数 `e^(-ωn·t)` + `t·e^(-ωn·t)`<br>- dampingRatio < 1（欠阻尼）：衰减振荡 `e^(-ζωn·t) · (cos(ωd·t) + sin(ωd·t))`<br>附 `getAcceleration(value, velocity)` 标准牛顿力学 `F = -k(x-xf) - cv` (`:58-62`)、`isAtEquilibrium` 双阈值判定 (`:78-82`)、`setValueThreshold(d)` 把 velocity threshold 设成 `value * 62.5` (`:105-109`) | **完全缺失**。OPPO 自定义 SpringForce 不复用 androidx 的 `SpringForce`（虽然同包名 `androidx/dynamicanimation/animation/SpringForce.java` 也存在），它有自己的 `mGammaPlus/mGammaMinus` 闭式根字段（`:20-21`）。lib 用 androidx 的 SpringForce，物理公式**应该等价**（都是标准弹簧解析解），但**velocityThresholdMultiplier = 62.5** 这个魔数是 OPPO 特有，androidx 用的是 `62.5 * 0.001 * 1000 = 62.5` 同值同语义 |
| （lib 无对应） | `com/android/quickstep/util/animation/SpringAnimReflectUtils.java:15` `public final class SpringAnimReflectUtils`：65 行，单例 `INSTANCE`，5 个静态反射字段 `sUpdateValuesMethod/sMassStateClass/sMassStateValueField/sMassStateVelocityField/sIsAtEquilibriumMethod/sSetValueThresholdMethod`（`:18-23`），实际**只 `isAtEquilibrium` 是反射**（`:29-48` 用 `getDeclaredMethod` + `setAccessible(true)`），`updateValues` 是**直调**（`:59-63` 直接调 `springForce.updateValues(values, velocity, deltaT)` 拿 `MassState.mValue/mVelocity`），`setValueThreshold` 是直调（`:53-56`）。`sUpdateValuesMethod/sMassStateClass/sMassStateValueField/sMassStateVelocityField/sSetValueThresholdMethod` 这 5 个字段**已被废弃**（@Metadata 残留，运行时不再使用） | **完全缺失**。这个类是历史包袱——之前 OPPO 的 `SpringForce` 是 fork 自旧版 androidx（反射是为了绕过 `@hide` 字段访问），后来 fork 自己的 `SpringForce` 后 `updateValues/setValueThreshold` 都不需要反射了。lib 完全不需要这个工具。**但是**：`isAtEquilibrium` 仍在用反射调 OPPO 自家 `SpringForce.isAtEquilibrium`（`:78-82`），原因是 `SpringForce.isAtEquilibrium` 在 Java bytecode 层是 public（`:78` `public boolean isAtEquilibrium(float, float)`），但反射调用可能与私有 KFunction 引用链上的某层访问控制相关——lib 无对应需求 |
| `launcher/async/AsyncAnimCallbacks.kt:30-37` `addListener` + `getListeners()` 快照 + `dispatch` 异步派发 | `MultiDynamicAnimation.java:28-29` `private ArrayList<OnAnimationEndListener> mEndListeners = new ArrayList<>();` + `:29` `private ArrayList<OnAnimationUpdateListener> mUpdateListeners = new ArrayList<>();` + `:152-167` `notifyAnimUpdate()` | **降级**。lib 的 AsyncAnimCallbacks 是**业务级** listener 派发器，对应原厂的 `NullableAnimatorListener` 链；MultiDynamicAnimation 的 listener 链是**帧循环级**，二者不在同一层。MultiDynamicAnimation 的 listener 是在每帧 `doAnimationFrame` 末尾 fire，与 frame time 同相位；AsyncAnimCallbacks 是在 ValueAnimator listener 路径上 marshal 回主线程 fire。两者不冲突但不可替换 |
| `util/Trace.kt` `traceBegin/traceEnd`（ATRACE 双色 trace） | `MultiDynamicAnimation.java:93, 122` `com.android.common.config.d.a("...running=", ..., TAG)` `LogUtils.i` + `@SourceDebugExtension` | **降级**。OPPO 用 `LogUtils.i` 关键生命周期；lib 用 `Trace.traceBegin/End`。两者都不影响语义，仅可观测性差异 |

---

## 2. 保真度评估

### 2.1 精确复刻（lib 精确还原原厂的语义）

1. **`AnimationHandler.kt` 是 `androidx.core.animation.AnimationHandler` 的 1:1 简化镜像**。`addAnimationFrameCallback` 首触发 + `removeCallback` 懒 null 槽 + `cleanUpList` 下一帧清理 + `onTick` 把 nanos 转 ms + `doAnimationFrame` 顺序遍历 + `runCatching` 异常隔离——结构、命名、行为模式完全镜像（`AnimationHandler.kt:32-92`）。  
2. **ThreadLocal 单例 + testHandler hook**（`AnimationHandler.kt:124-148`）。镜像 AndroidX 的 `sAnimationHandler` ThreadLocal（`androidx/core/animation/AnimationHandler.java:13`）+ `sTestHandler`（`:18`）。  
3. **`onAnimationFrame` → `doAnimationFrame` → `cleanUpList` → postFrameCallback` 自维持环路**（`AnimationHandler.kt:70-82`）。镜像 AndroidX `onAnimationFrame` (`:191-200`)。  
4. **`installThreadScheduler` / `replaceThreadScheduler` 装帧源**（`AnimationHandler.kt:134-158`）。对应原厂 `ANIM_EXECUTOR$lambda$0` 在 `onLooperPrepared` 时 `AnimationHandler.getInstance().setProvider(new SfVsyncFrameCallbackProvider())` 的等价位（详见 review 01 / 11）。  
5. **`AsyncSpringAnim` 线程 marshal 模式**（`AsyncSpringAnim.kt:30-43`）：`viewSupportAnimThread ? runOnAnimThread : current`。镜像原厂 `OplusAsyncSpringAnimWrapper.java:30-44` 模式（review 11 §①A-3 已确认 1:1）。  
6. **`AsyncAnimCallbacks` 的"懒删除 + 快照 + 异步派发"**（`AsyncAnimCallbacks.kt:30-37, 80-87, 96-105`）。镜像原厂 `AsyncAnimCallbacks.java:29-32, 81-97, 631-637` 的全链路 listener 派发协议（review 04 §2.3 已确认 1:1）。

### 2.2 有意简化（lib 注释中明示的合理 demo 化）

1. **`MultiDynamicAnimation` 整组未移植**——4 件套（`MultiDynamicAnimation` + `SpringHolder` + `SpringForce` + `SpringAnimReflectUtils`）**全部缺失**。lib `CustomRectFSpringAnim.kt:8-10` 注释明示："实际动画逻辑由 SpringAnimation 实现"。review 04 §4.2-2 + review 11 §4.2-2 已建议保持简化（理由：androidx `SpringAnimation` + `SpringForce` 行为等价；`requestEnd` 语义通过 `AsyncAnimCallbacks.onAnimActualEnd` 双轨已部分覆盖）。  
2. **`SpringForce` 用 androidx 替代 OPPO fork**。OPPO 自定义 `SpringForce.java`（167 行，附 `Force` 接口、三支闭式 + `mGammaPlus/mGammaMinus` 缓存 + `velocityThresholdMultiplier = 62.5`），lib 用 `androidx.dynamicanimation.animation.SpringForce`（公开 API）。物理公式等价，但**常量 `VELOCITY_THRESHOLD_MULTIPLIER = 62.5d`（`SpringForce.java:14`）**是 OPPO 特有魔数——androidx 用同样值（`62.5 * 0.001 * 1000 = 62.5`），所以"魔数一致"是巧合还是 OPPO 把这个数从 androidx 抄过来的？需要审 androidx 源码确认。  
3. **`SpringAnimReflectUtils` 整文件不需要**——它是 OPPO fork androidx 时的反射桥，**lib 完全没这层**。  
4. **`CustomRectFSpringAnim` 降级为占位**（18 行，仅 `AnimType` 3 值枚举）。原厂 812 行 Kotlin 反编译产物，6 自由度独立弹簧 + `mAnimLooperExecutor` ANIM/MAIN 双路 + 4 路线程纠偏 + `OnAnimUpdateListener` 写 SurfaceControl.Transaction——全部未移植。lib 用 androidx 单一 spring 演示。  
5. **`commitAnimationFrame(long)` 公开扩展点未移植**（`MultiDynamicAnimation.java:148-150`）。这是 `DynamicAnimation.OnAnimationEndListener` 接口的扩展签名（platform `DynamicAnimation` 公开 API），lib 走 androidx 路径，DynamicAnimation 树不接入。  
6. **`requestEnd` 双轨结束语义未移植**。`MultiDynamicAnimation.requestEnd(z)` 不立即停帧循环，仅置 `mCancelRequest/mEndRequest`，下一帧 `doAnimationFrame` 才真正停。原厂为此配套设计了 `endAnimationInternal(z)` 的"先停帧循环、再 fire listener"顺序（`:92-103`）。lib 用 androidx `cancel()` 立即清回调，listener fire 时序不一致。  
7. **`mPendingPosition` 半步分裂积分未移植**（`SpringHolder.java:115-130`）。中途改终点时，按 `deltaT/2` 拆两半步积分避免速度/位置阶跃。lib 用 androidx `SpringAnimation.animateToFinalPosition`——androidx 也有类似机制（`SpringAnimation.animateToFinalPosition` 内置），但**实现位置**不同：androidx 在 `SpringAnimation.animateToFinalPosition(f9)` 设置 `mPendingPosition` 后在 `updateValueAndVelocity` 内做半步分裂（`SpringAnimation.java:123-130` 反编译产物），原厂 SpringHolder 自己拆——结果等价，**代码路径完全独立**。  
8. **`getValueThreshold = mMinVisibleChange * 0.75`**（`SpringHolder.java:33-35`）未显式移植。androidx SpringAnimation 公开 `setMinimumVisibleChange` API（参数传递），**内部**计算阈值时是否也用 `* 0.75`？需要审 androidx 源码。lib 调用方传 `minimumVisibleChange` 时由 androidx 处理。

### 2.3 遗漏（lib 完全没有、原厂有且影响运行时行为）

1. **`MultiDynamicAnimation.doAnimationFrame` 的"裸 delta 积分 + 首帧特殊处理"**（`:152-159`）。  
   - **首帧**：`if (mLastFrameTime == 0)` → `mLastFrameTime = frameTime; notifyAnimUpdate(); return false;`。**只更新时间戳 + 通知 listener，不做积分**。这是为了避免首帧 dt 异常大（系统启动延迟）。lib 走 androidx SpringAnimation，androidx 内部也维护 `mLastFrameTime`（`SpringAnimation.java` 父类 `DynamicAnimation.java:onAnimationUpdate`）但首帧处理细节未审——lib 拿不到 dt。  
   - **次帧起**：`final long j9 = frameTime - mLastFrameTime; this.mLastFrameTime = frameTime;`——**裸 delta，无 maxDt 钳制**。如果帧时间突然跳跃（比如 Choreographer 丢帧后重新同步），dt 可能 50ms 甚至 100ms+，弹簧物理积分会用这个异常 dt 直接计算，导致**大幅度过冲或数值不稳定**。lib 走 androidx SpringAnimation 的 Choreographer 路径不会有这个问题（Choreographer 自身保证帧时间连续）。  
   - **`doAnimationFrame$lambda$2`（`:81-90`）的多弹簧聚合判定**：每个 SpringHolder 调 `updateValueAndVelocity(j9, isEndRequest)`，返回 `zUpdateValueAndVelocity`（**true 表示 at equilibrium，可结束**）。如果 isEndRequest 或 zUpdateValueAndVelocity → 直接 return（保持 `zArr[0]=true`）。否则（弹簧仍在运动）→ 置 `zArr[0]=false`（保持动画继续）。**整体逻辑**：所有弹簧都 atRest 才结束（`zArr[0]` 始终为 true 才会走到 `endAnimationInternal(z8)`）。lib 用 androidx 单一 spring，每个 spring 独立判定——lib 拿不到"多弹簧聚合"语义，Demo 11 用单 spring 演示。  
2. **`requestEnd` 下一帧生效语义**（`:185-191`）。  
   - 原厂：`requestEnd(true)` → `mCancelRequest = true`。下一帧 `doAnimationFrame` 走到 `applyToAllSpringHolder` 之后 `if (isEndRequest())` → 清理 `mCancelRequest/mEndRequest` 标志 → 检查 `zArr[0]` 决定是否 `endAnimationInternal(z8)`。  
   - **为什么不是立即停帧循环？** 因为业务可能 cancel 后还想 spring 跑到当前 value（不要中途定格），或者 cancel 信号到达时正在 `applyToAllSpringHolder` 中途不想撕裂。下一帧生效 = 帧边界安全。  
   - **lib**：`AsyncSpringAnim.cancel()` → `dispatch { real.cancel() }` → androidx `SpringAnimation.cancel()` 立即设 `mRunning = false` + 清回调列表 + fire listener。**无"下一帧生效"窗口**——业务 listener fire 时帧循环已停止。  
3. **`mCancelRequest` vs `mEndRequest` 的语义区分**（`:185-191`）。  
   - `mCancelRequest` = 业务主动取消（preserve 当前 value，不一定 stop at finalPosition）。  
   - `mEndRequest` = 自然结束（spring 跑完，但加速结束）。  
   - 二者在 `endAnimationInternal(z8)` 中**作为 `canceled` 参数传给 `OnAnimationEndListener`**——业务侧据此区分"是被取消还是自然结束"。  
   - **lib**：`AsyncSpringAnim.cancel()` 把 `canceled=true` 直接传给 androidx listener；`skipToEnd()` 走 androidx `skipToEnd()` 把 `mEndRequested=true` + `updateValueAndVelocity(0L)` 立即跳到 finalPosition。语义差异：lib 的"skipToEnd"是 1 帧强制结束，原厂的"skipToEnd"（通过 `requestEnd(false)` + 下一帧处理）允许 spring 在当前帧完成。  
4. **`SpringAnimReflectUtils` 的 `sMassStateClass/sMassStateValueField/sMassStateVelocityField` 静态字段残留**（`:19-21`）——**已被废弃但仍在编译产物中**。JADX 反编译保留是因为 OPPO 曾经用过这个反射路径（旧版 SpringForce 时反射拿 androidx 内部 `MassState`）。**当前代码**（`:59-63`）已经直调 `springForce.updateValues(values, velocity, deltaT).mValue` + `.mVelocity`。  
   - **业务影响**：0。反射字段不再使用，纯粹是死代码。  
   - **lib**：lib 没有这个问题——它从一开始就不需要反射路径。  
5. **`mPendingPosition` 半步分裂积分的"插值点"差异**（`SpringHolder.java:115-130` vs `SpringAnimation.java:123-130`）。  
   - 原厂：`SpringHolder.updateValueAndVelocity` 在 `mPendingPosition != UNSET` 时按 `deltaT/2` 拆两半步积分。  
   - lib 对应的（androidx `SpringAnimation.updateValueAndVelocity`）：同样的半步分裂逻辑。  
   - **结果等价但代码路径独立**——若想"原汁原味"复刻原厂，需要自己写 SpringHolder（不能直接用 androidx `SpringAnimation`）。  
6. **`CustomRectFSpringAnim.AnimType` 7 值 vs lib 3 值**（`CustomRectFSpringAnim.java:115-123` vs `CustomRectFSpringAnim.kt:14-18`）。  
   - 原厂 7 值：`OPEN_FROM_HOME / REMOTE_CLOSE_TO_HOME / REMOTE_CLOSE_TO_HOME_ASSISTANT / GESTURE_TO_DRAG / SWIPE_TO_HOME / SWIPE_TO_HOME_ASSISTANT / REVERSE_TO_OPEN`。  
   - lib 3 值：`SWIPE_TO_HOME / RECENTS_TRANSITION / APP_LAUNCH`。  
   - 命名空间不重合：lib 的 `RECENTS_TRANSITION` 不在原厂枚举里（原厂用 `OPEN_FROM_HOME` 表示从 home 启动）；lib 没有 `REVERSE_TO_OPEN`（reverse to open 是 OPEN_FROM_HOME 的反向，对应原厂的 `REVERSE_TO_OPEN`）。**`OPEN_FROM_HOME` 缺失**——这是最常用的值。  
7. **`MultiDynamicAnimation.mSpringHolderMap = HashMap<String, SpringHolder>`**（`:27`）——原厂用 String key 标识每个 spring（便于 `applyToAllSpringHolder` 按 key 遍历），lib 用单 spring 实例无 key。  
8. **`MultiDynamicAnimation` 的 `commitAnimationFrame` 公开扩展点**（`:148-150`）。这是 platform `DynamicAnimation` 兼容入口，**lib 接入 androidx SpringAnimation 时不会调到这个方法**——但若 lib 接到 OPPO 的 `MultiDynamicAnimation`，需要实现这个方法。  
9. **`LogUtils.i + TAG` 调试埋点**（`MultiDynamicAnimation.java:93, 122`）。OPPO 在 `endAnimationInternal` / `startAnimationInternal` 各打一行日志，lib 完全静默。  
10. **`com.oplus.fancyicon.command.AnimationProperty` / `CommandTrigger` / `CardAction` 引用残留**（`MultiDynamicAnimation.java:6-8` import）。这是 OPPO 内部库的 `Kotlin` metadata 残留——JADX 把 `@Metadata` 中的字符串常量反编译成 import 形式，但实际代码不用。**业务影响 0**。  
11. **`com.android.launcher.powersave.p` 和 `com.oplus.backup.sdk.common.utils.Constants`**（`MultiDynamicAnimation.java:5-6` import）。同样是 JADX 把 lambda 类错误归属到顶层包产生的 artifact——`p` 是某个内部类的 JADX 编号（因为 class 名 `p` 在某些 dex 里），`Constants` 是 OPLUS SDK 常量类。**业务影响 0**，仅源码可读性下降。

---

## 3. 行为差异风险点

按"可能导致语义不同"的严重度排序：

> **✔️保持简化（MultiDynamicAnimation 整组未移植为既定 demo 简化（review 04 §4.2-2 / review 11 §4.2-1 已建议保持）；androidx SpringAnimation 承担单弹簧语义，demo 无 requestEnd 消费方）**
1. **（高 / bug 级）`requestEnd` 下一帧生效语义缺失——`MultiDynamicAnimation` 整组未移植**（§2.3-1/2/3）。  
   - 原厂 `requestEnd(z)` → 下一帧 `doAnimationFrame` 才停帧循环 + fire listener，业务侧在 listener 中可以读到 spring 的最终 value/velocity（因为 spring 在这一帧完成了最后的积分）。  
   - lib `AsyncSpringAnim.cancel()` → 立即清回调，listener fire 时**spring 已停止**——业务侧读到的 value/velocity 是停止前的快照，不是最终值。  
   - **业务可观察后果**：任何依赖"cancel 后读 spring final value"的代码在 lib 上读到的是 `cancel()` 时刻的瞬时值，原厂读到的是下一帧 dt 积分后的"更准确 final 值"。Demo 11 演示 `cancel()` 后立即读 `real.translationY` 与原厂"cancel + 等一帧"读 `translationY` 有 1-2 像素差（取决于 dt）。  
   - **修复成本**：需要写完整的 `MultiDynamicAnimation` + `SpringHolder` + `SpringForce` + `SpringAnimReflectUtils` 4 件套，~500+ 行 Kotlin（详见 §4.1-1）。**性价比低**，建议保持缺失（review 04 §4.2-2 已建议）。  
   - **若必需要**：用 ~50 行写一个"最小可用的 MultiDynamicAnimation"（只保留 `mRunning/mEndRequest/mCancelRequest/mSpringHolderMap + addSpringHolderItem/start/requestEnd/doAnimationFrame`，不带 `applyToAllSpringHolder` 聚合、不带 SpringHolder pendingPosition 半步分裂）也能 cover 80% 业务。

> **✔️保持简化（CustomRectFSpringAnim 保持占位句柄（937dd23 后仍 18 行）；demo 无 6 自由度 RectF 场景；review 11 §4.2-1 已建议保持）**
2. **（高 / bug 级）`CustomRectFSpringAnim` 6 自由度独立 stiffness/damping 缺失**（§2.3-4）。  
   - 原厂 6 个独立 SpringHolder × 6 个独立 SpringForce，可以表达"centerX 欠阻尼 + width 临界阻尼 + alpha 延迟启动"等组合。  
   - lib 用 androidx 单一 SpringAnimation，**每个 spring 是独立的 SpringAnimation 实例**（不是共享帧回调），所以技术上也可以"6 个独立 stiffness/damping"——但**没有共享帧回调的原子性**（6 个独立 `start()` 调用的时间差 + 6 个独立 Choreographer 注册 → 不同 spring 在不同 vsync 上推进）。  
   - **业务可观察后果**：原厂 6 个 spring 在同一 vsync 上推进 → 6 个 value 在同一帧内更新 → RectF 在 1 帧内变形；lib 6 个 spring 在不同 vsync 上推进 → RectF 在 2-3 帧内变形 → **视觉上"抖动"或"分步变形"**。Demo 11 没有 6 自由度演示，但 Demo 4（如果扩展为 6 自由度）会立刻暴露。  
   - **修复成本**：必须写 `MultiDynamicAnimation`（共享帧回调）+ `CustomRectFSpringAnim` 6 字段组（812 行 Kotlin），~800+ 行。**性价比极低**，建议保持缺失（review 11 §4.2-1 已建议）。  
   - **替代方案**：用 `SceneSpring.kt`（demo 已有）的 6 实例在同一 `SceneClock` 帧回调里推进——但 `SceneSpring` 是单 spring，**没有 velocityThreshold/pendingPosition 半步分裂**，Demo 4 用的话精度差。

> **⚠️未修复（AsyncSpringAnim 未建模 EndReason 三态（doc §4.1-2 建议 ~20 行，未实施）；androidx OnAnimationEndListener 的 canceled 布尔已能区分 cancel/自然结束；skipToEnd 归入取消语义；demo 无按 reason 分支的消费方）**
3. **（中）`requestEnd` 与 `cancel` 的语义混淆**（§2.3-3）。  
   - 原厂区分 `requestEnd(true)`（cancel）与 `requestEnd(false)`（自然结束），通过 `OnAnimationEndListener.onAnimationEnd(this, canceled)` 的 `canceled` 参数告诉业务。  
   - lib `AsyncSpringAnim.cancel()` 总是 `canceled=true`；`skipToEnd()` 在 androidx 路径上等价于"立刻跳到 finalPosition"（不是"自然结束"）。  
   - **业务可观察后果**：依赖 `canceled` 标志做不同业务的代码（典型如"cancel 后恢复前一个动画 / 自然结束后清理 listener"）在 lib 上无法区分。  
   - **修复成本**：仅改 `AsyncSpringAnim`：增加 `end(reason: EndReason)` 方法 + 在 `addEndListener` 处根据 reason 注入 `canceled` 标志。~20 行。**性价比高**，建议回移。

> **❌不成立/已过期（grep demo/src：OPEN_FROM_HOME 仅 Demo9 banner 文案，无 AnimType.OPEN_FROM_HOME 调用点；"Demo9 编译失败"说法不成立；3 值枚举为 demo 有意定案（USAGE.md:118））**
4. **（中）`CustomRectFSpringAnim.AnimType` 枚举不闭合**（§2.3-6）。  
   - lib 3 值 vs 原厂 7 值，缺失 `OPEN_FROM_HOME / REMOTE_CLOSE_TO_HOME / REMOTE_CLOSE_TO_HOME_ASSISTANT / GESTURE_TO_DRAG / SWIPE_TO_HOME_ASSISTANT / REVERSE_TO_OPEN`。  
   - **业务可观察后果**：Demo 9（OPEN_FROM_HOME）调用 `CustomRectFSpringAnim(animType = OPEN_FROM_HOME)` 在 lib 上编译失败——Demo 9 必须改成 `SWIPE_TO_HOME`（语义偏差）。review 11 §②C-1 + §3-1 已点名。  
   - **修复成本**：5 行（枚举补齐），**性价比最高**，建议回移。

> **✔️保持简化（androidx 内部已实现半步分裂，结果等价；doc §3-5 自判修复成本 0 保持现状）**
5. **（中）`mPendingPosition` 半步分裂积分实现路径不同**（§2.3-5）。  
   - 原厂在 `SpringHolder.updateValueAndVelocity` 拆 deltaT/2；lib 用的 androidx `SpringAnimation.updateValueAndVelocity` 也在内部拆。  
   - **结果等价但行为细节可能不一致**：androidx `SpringAnimation.animateToFinalPosition` 设 `mPendingPosition` 后会在**下一个** `updateValueAndVelocity` 调时触发分裂；原厂 OPPO fork 的 `SpringAnimation.java:64-71` 也是同样行为。**两种路径应该等价**——但若 androidx 版本升级时改了实现（曾经 androidx 在 1.0 → 1.1 时改过 `mPendingPosition` 的处理），lib 与原厂的同步性可能漂移。  
   - **修复成本**：0（保持现状即可），无需回移。

> **✔️保持简化（androidx setMinimumVisibleChange 内部同公式；doc §4.2-7 建议保持；未审计出差异）**
6. **（中）`getValueThreshold = mMinVisibleChange * 0.75` 在 lib 端未显式控制**（§2.2-8）。  
   - 原厂 `SpringHolder.getValueThreshold` 显式把 threshold 设成 `minimumVisibleChange * 0.75`，通过 `SpringAnimReflectUtils.setValueThreshold` 反射写入 `SpringForce.mValueThreshold`（因为 `setValueThreshold` 是 public 但 `mValueThreshold` 是 `protected`）。  
   - androidx `SpringAnimation.setMinimumVisibleChange`（公开 API）也用类似公式，但**公开 API 文档**未明确乘 0.75——审 androidx 源码确认。  
   - **业务可观察后果**：若原厂与 androidx 在 threshold 上有微小差异，**atRest 判定时机不同**——同一弹簧可能在原厂"恰好 atRest"但在 lib"仍在运动"，导致取消/结束时机偏移 1 帧。  
   - **修复成本**：0（保持现状）。若要精确对齐，需要审计 androidx `SpringAnimation` 源码并显式调用内部 setter。

> **✔️保持简化（依赖 MultiDynamicAnimation 本体（不存在）；doc §4.2-3 建议保持）**
7. **（中）`commitAnimationFrame` 公开扩展点缺失**（§2.3-8）。  
   - 原厂 `MultiDynamicAnimation.commitAnimationFrame(long j8)` 是 platform `DynamicAnimation` 兼容入口，允许外部以"提交一帧"的方式驱动动画。lib 完全没有这个入口。  
   - **业务可观察后果**：调用方若想用 `MultiDynamicAnimation` 但又用 platform `DynamicAnimation` 的接口（典型如测试桩或自定义 vsync 源），需要 cast 到 `DynamicAnimation` 才能调 `commitAnimationFrame`。lib 没有这条路径。  
   - **修复成本**：~5 行（在 `MultiDynamicAnimation` 加一个 `public fun commitAnimationFrame(frameTimeMs: Long) = doAnimationFrame(frameTimeMs)`），但前提是先把 `MultiDynamicAnimation` 写出来。**性价比低**，建议保持缺失。

> **✔️保持简化（依赖 MultiDynamicAnimation（不存在）；纯 API 风格差异；doc §4.2-6 建议保持）**
8. **（低）`MultiDynamicAnimation.mSpringHolderMap` 改用 `HashMap<String, SpringHolder>` 而非 `List<SpringHolder>`**（§2.3-7）。  
   - 原厂用 String key（每个 spring 自带 `mKey`）；lib 用单 spring 实例。  
   - **业务可观察后果**：若业务要按 name 查询 spring（典型如"暂停名为 'centerX' 的 spring"），原厂可以 `mSpringHolderMap.get("centerX").requestEnd()`；lib 必须持有所有 spring 实例的引用。**无语义差异，仅 API 风格**。  
   - **修复成本**：若要回移 SpringHolder，需要它持有 `mKey` + 提供 getter。~5 行。

> **✔️保持简化（doc §4.2-4 建议保持：Trace.traceBegin/End 已覆盖 trace 维度）**
9. **（低）`LogUtils.i + TAG` 调试埋点缺失**（§2.3-9）。OPPO 在每个生命周期方法打日志，lib 完全静默。  
   - **业务可观察后果**：生产环境排查问题时，调用方在原厂可以 `adb logcat -s MultiDynamicAnimation` 看动画生命周期；lib 无 log 可看。  
   - **修复成本**：~10 行（每方法加一行 `Log.i`）。**性价比低**，lib 用 `Trace.traceBegin/End` 已覆盖 trace 维度。

> **✔️保持简化（androidx SpringForce 同值 62.5，无漂移；doc 自判无可观察差异）**
10. **（提示 / 文档一致性）`SpringForce.VELOCITY_THRESHOLD_MULTIPLIER = 62.5d`**（`SpringForce.java:14`）是 OPPO fork 特定魔数。  
    - androidx `SpringForce` 用同样值（巧合还是 OPPO 抄过来的？需要审 androidx 源码）。  
    - **若 OPPO 升级这个魔数（如改成 100.0），lib 与原厂会立刻漂移**。当前值一致，无可观察差异。  
    - **修复成本**：0（保持现状）。若写自己的 `SpringForce`，沿用 62.5d 即可。

> **✔️保持简化（OPPO 侧死代码残留；lib 无反射层需求，无需回移）**
11. **（提示 / 已知无影响）`@Metadata` 中残留的废弃反射字段**（§2.3-4）。  
    - `sUpdateValuesMethod / sMassStateClass / sMassStateValueField / sMassStateVelocityField / sSetValueThresholdMethod`（`SpringAnimReflectUtils.java:18-23`）已不再使用，仅作为 Kotlin metadata 残留。  
    - **业务影响 0**。lib 完全不需要这个工具类，无需"回移"。

---

## 4. 回移建议

### 4.1 值得补进 lib 的（按性价比排序）

> **❌不成立/已过期（无 7 值调用点（见 §3-4 证据）；CustomRectFSpringAnim 为 internal 句柄，demo 仅用 SWIPE_TO_HOME；补齐属未来真实 spring 回移附带）**
1. **补 `CustomRectFSpringAnim.AnimType` 7 值枚举**（§3-4）。~5 行（加 4 个 enum 值：`OPEN_FROM_HOME / REMOTE_CLOSE_TO_HOME / REMOTE_CLOSE_TO_HOME_ASSISTANT / GESTURE_TO_DRAG / SWIPE_TO_HOME_ASSISTANT / REVERSE_TO_OPEN`）。**性价比最高**——review 11 §3-1 + 本区域 §3-4 都点名；Demo 9（OPEN_FROM_HOME）必须改枚举才能编译。

> **⚠️未修复（未实施（无 EndReason/enum endImmediately）；androidx canceled 布尔已覆盖主要两态；demo 无区分消费方）**
2. **改 `AsyncSpringAnim.cancel/skipToEnd` 的语义区分**（§3-3）。~20 行：
   - 加 `enum class EndReason { CANCELLED, SKIPPED, NATURAL }`
   - `cancel()` → 派发到 anim thread 调 `real.cancel()`，`addEndListener` 处把 `canceled=true`
   - 新增 `endImmediately()` 方法 → 调 `real.skipToEnd()`，`addEndListener` 处根据是否主动 skip 注入 `canceled=false`
   - 业务侧根据 `canceled` 标志区分行为（典型如"cancel 后恢复 / 自然结束清理 listener"）

> **⚠️未修复（未实施 ~80 行；demo 无多弹簧共享帧场景；937dd23 已用单 androidx spring 覆盖 Demo11）**
3. **若要演示"多弹簧共享帧回调"语义**（§3-2），写最小可用版 `MultiDynamicAnimation`（§3-1 提到 ~50 行）。仅保留：
   - 字段：`mRunning: Boolean`、`mEndRequest: Boolean`、`mCancelRequest: Boolean`、`mLastFrameTime: Long`、`mSpringHolderMap: HashMap<String, SpringHolder>`
   - 方法：`addSpringHolderItem(holder)`、`start()`、`requestEnd(cancel: Boolean)`、`doAnimationFrame(frameTimeMs: Long): Boolean`、`endAnimationInternal(canceled: Boolean)`
   - 不实现：`SpringHolder` 内部细节（pendingPosition 半步分裂）、`SpringForce` 三支闭式（用 androidx 的 `SpringForce`）、`SpringAnimReflectUtils` 反射（不需要）
   - 配合 6 个 androidx `SpringAnimation` 实例，每个实例挂到 `MultiDynamicAnimation` 的 SpringHolder 上，用 `applyToAllSpringHolder` 在共享帧回调里调 `springAnimation.skipToEnd()` / `springAnimation.cancel()` 完成"所有弹簧 atRest 才结束"的聚合语义。
   - **成本**：~80 行（比完整移植节省 90%），但能 cover 80% 业务。

### 4.2 建议保持简化的（明确不补）

> **✔️保持简化（MultiDynamicAnimation+SpringHolder+SpringForce fork+SpringAnimReflectUtils ~1000+ 行，与动画线程主线无关；review 04/11 已建议）**
1. **不补完整 4 件套**（§3-1, §3-2）——`MultiDynamicAnimation` + `SpringHolder` + `SpringForce` + `SpringAnimReflectUtils` 共 ~1000+ 行 Kotlin，**与"动画线程方案"主线无关**（属于"事务写表层"）。androidx `SpringAnimation` + `SpringForce` 已能 cover 90% 弹簧物理；androidx 的 `mPendingPosition` 半步分裂、`getValueThreshold * 0.75` 阈值等细节虽然实现路径不同但结果等价。review 04 §4.2-2 + review 11 §4.2-1/2 已建议保持简化。

> **✔️保持简化（launcher 专属 UI 层，demo 无 SurfaceControl 路径）**
2. **不补 `CustomRectFSpringAnim` 6 自由度 RectF 弹簧的 SurfaceControl 事务写层**（812 行 Kotlin）——`OnAnimUpdateListener.onUpdate(rectF, progress, radio, radius, alpha)` → `SurfaceControl.Transaction` 操作属于 launcher 专属 UI 层，与"动画线程方案"主线无关。review 11 §4.2-1 已建议。

> **✔️保持简化（lib 不在 platform DynamicAnimation 树内）**
3. **不补 `commitAnimationFrame` 公开扩展点**（§3-7）——仅当外部需要用 platform `DynamicAnimation` 接口驱动时才有用，lib 不在 platform `DynamicAnimation` 树内。

> **✔️保持简化（Trace.traceBegin/End 已够）**
4. **不补 `LogUtils.i + Debug.getCallers(N)` 调试埋点**（§3-9）——OEM 调试设施，lib 用 `Trace.traceBegin/End` 已足够。

> **✔️保持简化（历史包袱，lib 不需要反射路径）**
5. **不补 `SpringAnimReflectUtils` 整文件**（§2.3-4 / §2.3-11）——历史包袱，lib 不需要反射路径。

> **✔️保持简化（纯 API 风格差异，无语义影响）**
6. **不补 `mSpringHolderMap` 的 String key 查询 API**（§3-8）——纯 API 风格差异，无语义影响；lib 用 List 索引已够。

> **✔️保持简化（androidx setMinimumVisibleChange 内部同式；未审计出差异）**
7. **不显式控制 `getValueThreshold = mMinVisibleChange * 0.75` 常量**（§3-6）——androidx 公开 API 的 `setMinimumVisibleChange` 内部应该用同样的乘 0.75 逻辑（需审计）。若审计后确认一致，则无需显式控制；若不一致，**单独修复**：~3 行（在 lib `AsyncSpringAnim` 中显式调用 `real.spring.setValueThreshold(real.minimumVisibleChange * 0.75)`）。

### 4.3 文档一致性建议

> **⚠️未修复（USAGE.md 仍有点名缺口（已有"句柄占位/907 行未复刻"间接说明，未明示多弹簧共享帧限制；1-2 行文档缺口，低优先））**
- `docs/USAGE.md` 应该明示"lib 不支持 MultiDynamicAnimation 多弹簧共享帧回调"——目前没有这层文档；调用方在写 6 自由度弹簧动画时会被坑（6 个独立 SpringAnimation 在不同 vsync 上推进 → 视觉抖动）。
> **⚠️未修复（USAGE §AsyncSpringAnim（97-109 行）描述 marshal 但未写 cancel 立即停帧语义（1-2 行文档缺口，低优先））**
- `docs/USAGE.md` §AsyncSpringAnim 应该明示 `cancel()` 立即停帧循环而非"下一帧生效"——调用方按原厂语义写"cancel + 等一帧读 final value"的代码会失效。
> **✅已修复（USAGE.md §CustomRectFSpringAnim 现列出 3 值枚举：SWIPE_TO_HOME/RECENTS_TRANSITION/APP_LAUNCH（:117-118），该文档缺口已不存在；42882ff/e62dbff 后 USAGE 已含 §AsyncSpringAnim 与句柄说明）**
- `docs/USAGE.md` §CustomRectFSpringAnim 应该明示 AnimType 枚举仅 3 值，需要 7 值的业务需要 fork 自己的枚举。

---

## 附：关键证据速查表

| 论断 | 证据 |
|---|---|
| `MultiDynamicAnimation implements AnimationHandler.AnimationFrameCallback`（platform @hide API） | `com/android/quickstep/util/animation/MultiDynamicAnimation.java:21` |
| `AnimationHandler.getInstance().addAnimationFrameCallback(this, 0L)` 注册帧回调（第二个参数 0L 是 frame delay） | `MultiDynamicAnimation.java:127` |
| `doAnimationFrame` 裸 delta 积分（`frameTime - mLastFrameTime`） | `MultiDynamicAnimation.java:152-159` |
| `doAnimationFrame` 首帧特殊处理（`mLastFrameTime == 0` 时只更新 + notify + return false） | `MultiDynamicAnimation.java:153-158` |
| `doAnimationFrame$lambda$2` 多弹簧聚合（所有弹簧 atRest 才结束） | `MultiDynamicAnimation.java:81-90` |
| `requestEnd` 下一帧生效（仅置 `mCancelRequest/mEndRequest`，下一帧 `doAnimationFrame` 才真正停） | `MultiDynamicAnimation.java:185-191` |
| `requestEnd(z8=true)` 设 `mCancelRequest`，`z8=false` 设 `mEndRequest` | `MultiDynamicAnimation.java:185-191` |
| `mCancelRequest` 与 `mEndRequest` 在 `doAnimationFrame` 末尾清理（`if (isEndRequest()) { mEndRequest = false; mCancelRequest = false; }`） | `MultiDynamicAnimation.java:169-172` |
| `endAnimationInternal(z8)` "先停帧循环、再 fire listener" 顺序 | `MultiDynamicAnimation.java:92-103` |
| `startAnimationInternal` 幂等（`if (mRunning) return`） | `MultiDynamicAnimation.java:121-128` |
| `commitAnimationFrame(long)` 公开扩展点 | `MultiDynamicAnimation.java:148-150` |
| `mSpringHolderMap = new HashMap<>()`（String key） | `MultiDynamicAnimation.java:27` |
| `mRunning / mEndRequest / mCancelRequest / mLastFrameTime` 字段定义 | `MultiDynamicAnimation.java:23-26` |
| `OnAnimationEndListener` 2 参签名（this + canceled） | `MultiDynamicAnimation.java:63-65` |
| `OnAnimationUpdateListener` 1 参签名（this） | `MultiDynamicAnimation.java:68-70` |
| `SpringHolder.mPendingPosition` 半步分裂积分（`deltaT/2` 拆两半步） | `SpringHolder.java:106-138`（`mPendingPosition != UNSET` 分支） |
| `SpringHolder.getValueThreshold = mMinVisibleChange * 0.75` | `SpringHolder.java:33-35` |
| `SpringHolder.setValuesThreshold` 反射写入 `SpringForce.mValueThreshold` | `SpringHolder.java:102-104` + `SpringAnimReflectUtils.setValueThreshold` (`:53-56`) |
| `SpringHolder.updateValueAndVelocity(deltaT, endRequest)` 返回 true = at equilibrium | `SpringHolder.java:106-138` |
| `SpringForce.VELOCITY_THRESHOLD_MULTIPLIER = 62.5d` | `SpringForce.java:14` |
| `SpringForce.updateValues` 三支闭式（dampingRatio > 1 / == 1 / < 1） | `SpringForce.java:111-156` |
| `SpringForce.getAcceleration` 标准牛顿力学（`F = -k(x-xf) - cv`） | `SpringForce.java:58-62` |
| `SpringForce.isAtEquilibrium` 双阈值判定（`|velocity| < mVelocityThreshold && |value - final| < mValueThreshold`） | `SpringForce.java:78-82` |
| `SpringForce.setValueThreshold(d)` 把 velocity threshold 设成 `value * 62.5` | `SpringForce.java:105-109` |
| `SpringForce.mGammaPlus / mGammaMinus` 缓存过阻尼闭式根 | `SpringForce.java:20-21` |
| `SpringAnimReflectUtils.isAtEquilibrium` 反射（`getDeclaredMethod` + `setAccessible(true)`） | `SpringAnimReflectUtils.java:29-48` |
| `SpringAnimReflectUtils.updateValues` 直调（不反射） | `SpringAnimReflectUtils.java:59-63` |
| `SpringAnimReflectUtils.setValueThreshold` 直调 | `SpringAnimReflectUtils.java:53-56` |
| `sUpdateValuesMethod / sMassStateClass / sMassStateValueField / sMassStateVelocityField / sSetValueThresholdMethod` 已废弃反射字段 | `SpringAnimReflectUtils.java:18-23` |
| `CustomRectFSpringAnim.AnimType` 7 值枚举 | `CustomRectFSpringAnim.java:115-123` |
| `CustomRectFSpringAnim` 6 自由度字段组（centerX/rectY/width/radio/rectRadius/alpha） | `CustomRectFSpringAnim.java:43-113` |
| `CustomRectFSpringAnim.initProperty` 把 6 个 SpringHolder 塞进 `mMultiDynamicAnimation.addSpringHolderItem(...)` | `CustomRectFSpringAnim.java:339-369` |
| `CustomRectFSpringAnim.calculateFrameRectF` 6 自由度矩形换算 | `CustomRectFSpringAnim.java:266-328` |
| `CustomRectFSpringAnim.start()` 选 `mAnimLooperExecutor = ANIM_EXECUTOR` 或 `MAIN_EXECUTOR` | `CustomRectFSpringAnim.java:885-907` |
| `CustomRectFSpringAnim.cancel/skipToEnd/reverseToStart` 4 路线程纠偏 | `CustomRectFSpringAnim.java:608-628, 752-776, 860-883` |
| `CustomRectFSpringAnim.maybeEnd` 双轨结束（先 `onAnimationEnd` 再 `onAnimActualEnd`） | `CustomRectFSpringAnim.java:423-441` |
| AndroidX `AnimationHandler.sAnimationHandler` ThreadLocal + `sTestHandler` hook | `androidx/core/animation/AnimationHandler.java:13, 18` |
| AndroidX `AnimationHandler.addAnimationFrameCallback` 单参签名（不带 frame delay） | `androidx/core/animation/AnimationHandler.java:180-186` |
| AndroidX `AnimationHandler.cleanUpList` 懒 null 槽清理 | `androidx/core/animation/AnimationHandler.java:117-132` |
| AndroidX `AnimationHandler.doAnimationFrame` 顺序遍历 + 无异常隔离 | `androidx/core/animation/AnimationHandler.java:130-137` |
| AndroidX `AnimationHandler.onAnimationFrame` → `doAnimationFrame` → `postFrameCallback` 自维持环路 | `androidx/core/animation/AnimationHandler.java:191-200` |
| lib `AnimationHandler.kt` 是 AndroidX 镜像（注释明示） | `lib/src/main/java/com/asyncanimator/core/anim/AnimationHandler.kt:11-16` |
| lib `AnimationHandler.kt:25` `AnimationFrameCallback` 单参签名 | `lib/src/main/java/com/asyncanimator/core/anim/AnimationHandler.kt:25` |
| lib `AnimationHandler.kt:32-38` `addAnimationFrameCallback` + `removeCallback` 懒删除 | `lib/src/main/java/com/asyncanimator/core/anim/AnimationHandler.kt:32-38` |
| lib `AnimationHandler.kt:85-92` `doAnimationFrame` 顺序遍历 + `runCatching` 异常隔离 | `lib/src/main/java/com/asyncanimator/core/anim/AnimationHandler.kt:85-92` |
| lib `AsyncSpringAnim.kt:30-43` 线程 marshal 模式（`viewSupportAnimThread ? runOnAnimThread : current`） | `lib/src/main/java/com/asyncanimator/launcher/async/AsyncSpringAnim.kt:30-43` |
| lib `AsyncSpringAnim.addEndListener` 接受 androidx 4 参 lambda（与 OPPO 2 参签名不兼容） | `lib/src/main/java/com/asyncanimator/launcher/async/AsyncSpringAnim.kt:38-43` |
| lib `CustomRectFSpringAnim.kt:14-18` 占位类（仅 3 值 AnimType） | `lib/src/main/java/com/asyncanimator/launcher/async/CustomRectFSpringAnim.kt:14-18` |
| lib 用 androidx `SpringAnimation` + `SpringForce`（公开 API）替代 OPPO fork | `demo/src/main/java/com/asyncanimator/demo/Demo11ViewSpringAnimThreadActivity.kt:13, 132-134` |
| `demo/scene/SceneSpring.kt` 自实现 3 分支弹簧物理（与 OPPO SpringForce 三支闭式一致） | `demo/src/main/java/com/asyncanimator/demo/scene/SceneSpring.kt:23-63` |

## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **937dd23** — androidx SpringAnimation 替代 MultiDynamicAnimation 缺失部分

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。
按条目补记：
- **§3-1 / §3-2** — ✔️保持简化（MultiDynamicAnimation/CustomRectFSpringAnim 6-DOF 未移植，review 04/11 已建议）
- **§3-3** — ⚠️未修复（EndReason 三态未建模，~20 行，无消费方）
- **§3-4** — ❌不成立/已过期（无 AnimType.OPEN_FROM_HOME 调用点，Demo9 编译失败说法不成立）
- **§3-5 / §3-6 / §3-7 / §3-8 / §3-9 / §3-10 / §3-11** — ✔️保持简化（androidx 等价或 OPPO 死代码/doc 自列）
- **§4.1-1** — ❌不成立/已过期（见 §3-4）；**§4.1-2 / §4.1-3** — ⚠️未修复（未实施）
- **§4.2-1..7** — ✔️保持简化（doc 自列）
- **§4.3-1 / §4.3-2** — ⚠️未修复（USAGE 文档缺口 1-2 行）；**§4.3-3** — ✅已修复（42882ff 已列 3 值枚举）
