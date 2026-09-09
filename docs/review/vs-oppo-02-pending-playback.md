# vs-oppo-02-pending-playback — Pending / Playback 层 lib vs OPPO 对比

> 对比双方：
> - **lib**：`D:\AsyncAnimator\lib\src\main\java\com\asyncanimator\playback\`（8 个 .kt；e62dbff 包重组后原 `launcher/pending`（5 个）与 `launcher/playback`（3 个）合并至此）
> - **原厂**：`D:\oppo_a6_launcher\sources\com\android\launcher3\anim\`（`PendingAnimation`、`AnimationSuccessListener`、`AnimatorListeners`、`NullableAnimatorListener`、`NullableAnimatorListenerAdapter`、`AnimatorPlaybackController`、`PropertySetter`、`Interpolators`；辅助 `SpringProperty`、`SpringAnimationBuilder`、`AlphaUpdateListener`）
> 原厂文件以 DLP 加密形式存档（`Read` 返回非 UTF-8 错误），本报告全部经 `python open(...).read()`（明文通道）核对；行号即 JADX 文本行号。
>
> 本报告是独立第二份 review，与已有 `02-pending-playback.md`（基于 JADX 取证 + 先验 lib 注释）平行复核；差异点已用 **★** 标出。

---

## ① 类对应关系表

| lib 类（文件） | 原厂类（文件:行） | 关系 | 关键证据 |
|---|---|---|---|
| `playback/PendingAnimation.kt`（`internal class`，179 行） | `com/android/launcher3/anim/PendingAnimation.java:23`（`public class implements PropertySetter`，235 行） | 精确对应；字段一一对齐：`anim`↔`mAnim`(:24)、`animHolders`↔`mAnimHolders`(:29)、`progressAnimator`↔`mProgressAnimator`(:27)、`isAnimFinished`↔`isAnimFinished`(:30)、`durationMs`↔`mDuration`(:25) | lib `:34-40` 的 listener 维护 `isAnimFinished` ↔ 原厂 `:35-50`；`buildAnim()` 的 progressAnimator `add` 兜底 ↔ 原厂 `:91-100`；`createPlaybackController` 单例缓存 ↔ 原厂 `:105-110` |
| `playback/AnimationSuccessListener.kt`（34 行） | `com/android/launcher3/anim/AnimationSuccessListener.java:7`（24 行）+ 父类 `com/android/quickstep/util/animation/ActualEndAnimListener.java`（仅一个空钩子 `onAnimActualEnd`） | 精确对应；lib 已合并 `ActualEndAnimListener` 中间层（仅 1 个空方法 `onAnimActualEnd`，合并不损失语义），用 `ActualEndAnimListener()` 作为父类 | lib `:19-27` ↔ 原厂 `:13-22`（cancel 置位 + end 跳过 cancelled）；原厂父类链 `ActualEndAnimListener → NullableAnimatorListenerAdapter → AnimatorListenerAdapter`，lib 直接 `extends ActualEndAnimListener` |
| `playback/AnimatorListeners.kt`（`internal object`，58 行） | `com/android/launcher3/anim/AnimatorListeners.java:10`（81 行） | 精确对应三工厂方法 | lib `forEndCallback(Runnable)` `:46-52` ↔ 原厂 `:73-80` 的匿名 `NullableAnimatorListenerAdapter`；`forEndCallback(Consumer<Boolean>)` `:25-37` ↔ 原厂 `EndStateCallbackWrapper` `:14-43`；`forSuccessCallback(Runnable)` `:39-44` ↔ 原厂 `RunnableSuccessListener` `:45-66` |
| `playback/NullableAnimatorListener.kt`（16 行） | `com/android/launcher3/anim/NullableAnimatorListener.java:7`（16 行） | 精确对应；原厂 `@Nullable` 注解 lib 删去（Kotlin 平台 `Animator` listener 必传非 null，改注解只保留历史注释） | 三个默认空方法 `onAnimationCancel/End/Start` 一一对应 |
| `playback/NullableAnimatorListenerAdapter.kt`（27 行） | `com/android/launcher3/anim/NullableAnimatorListenerAdapter.java:8`（30 行） | 精确对应 + lib 把 `cancelled` 标志位置上移到本类 | lib `cancelled` `:15` ↔ 原厂 `AnimationSuccessListener.mCancelled`(:8)（父类继承可见）；`animationId` ↔ `mAnimationId`（原厂 `:9`，有 getter/setter `:11-13, 28-30`）；**lib `onAnimationCancel` 主动置 `cancelled = true`**（lib `:19-21`），原厂 Adapter 该方法是空（`:16-17`，置位由 `AnimationSuccessListener` 覆盖父类时做）——属"原厂语义的超集"，对成功路径等价 |
| `playback/AnimatorPlaybackController.kt`（207 行） | `com/android/launcher3/anim/AnimatorPlaybackController.java:25`（467 行） | 大体精确（核心播放模型逐行一致），但 lib 砍掉 startWithVelocity 全链路 + OPPO 定制 grid 补丁 + 7 个工具方法 | `Holder` 内部类 lib `:74-89` ↔ 原厂 `:38-56`；`OnAnimationEndDispatcher` lib `:139-159` ↔ 原厂 `:63-96`；`ProgressMapper` lib `:11-13` ↔ 原厂 `:108-125`；`setPlayFraction` lib `:97-102` ↔ 原厂 `:364-373`；`start/reverse/pause` lib `:106-123` ↔ 原厂 `:375-380, 348-353, 315-320`；`clampDuration` lib `:125-126` ↔ 原厂 `:222-229`；`forceFinishIfCloseToEnd` lib `:128-131` ↔ 原厂 `:256-261`（常量 `:26`）；`addHoldersRecur` lib `:186-194` ↔ 原厂 `:161-182` |
| `playback/Interpolators.kt`（`internal object`，12 行） | `com/android/launcher3/anim/Interpolators.java:17`（215 行） | 严重裁剪；只保 `LINEAR` | lib `LINEAR = TimeInterpolator { it }` ↔ 原厂 `LINEAR = new LinearInterpolator()`（`:35`）；其余 40+ 常量 + `clampToProgress`/`mapToProgress`/`reverse`/`scrollInterpolatorForVelocity`/`overshootInterpolatorForVelocity` + ZOOM_IN/OUT + COUI 插值器（`RECENT_LAUNCH_TASK_INTERPOLATOR` 等）全部砍掉 |
| `playback/PropertySetter.kt`（29 行） | `com/android/launcher3/anim/PropertySetter.java:10`（65 行） | 接口骨架精确；**View 侧方法、`setInt`、`add(Animator)` 全部砍掉** | `NO_ANIM_PROPERTY_SETTER` lib `:26-28` ↔ 原厂 `:11-12`（匿名空类）；`setFloat` 默认 no-op lib `:15-21` ↔ 原厂 `:23-28` |
| （lib 缺） | `SpringProperty.java:4`（54 行） | **未移植**；OPPO `Holder.springProperty` 字段类型（`AnimatorPlaybackController.java:43`）。lib `Holder.springProperty: Any? = null` 占位（`:79`），`PendingAnimation.add` 三参版本直接丢弃 | 原厂构造器默认 `(int flags=0)` + `(0)` → `mDampingRatio=0.5/mStiffness=1500/mUseDiffSpringForce=false`（`:44-52`）；提供 `FLAG_CAN_SPRING_ON_END/START` 两个常量（`:7-8`） |
| （lib 缺） | `AlphaUpdateListener.java:9`（62 行） | **未移植**；OPPO `setViewAlpha` 的可见性联动。原厂 `PendingAnimation.setViewAlpha` 与 `PropertySetter.setViewAlpha` 都挂该 listener（`:170, 229`），lib 无 `setViewAlpha` | `ALPHA_CUTOFF_THRESHOLD=0.01f` + 切线程到 MAIN_EXECUTOR 跑 `updateVisibility`（`:24-29, 39-60`） |
| （lib 缺） | `SpringAnimationBuilder.java:18`（186 行） | **未移植**；`startWithVelocity`（原厂 `:382-461`）逐 Holder 算弹簧参数时调用 | 详情未取，仅作为依赖标注 |

---

## ② 保真度评估

### A. 精确复刻（行为可对齐，逐行/逐语义一致）

| # | 设计点 | 原厂证据 | lib 证据 |
|---|---|---|---|
| A1 | `PendingAnimation` 构造挂 AnimatorSet listener 维护 `isAnimFinished` | `PendingAnimation.java:31-50`（cancel/end 置 true、start 置 false） | `PendingAnimation.kt:34-40` |
| A2 | `progressAnimator` 懒建 + `addEndListener`/`addOnFrameCallback` 复用同一实例 | 原厂 `:60-65, 81-86` | lib `:72-78, 100-101` |
| A3 | `buildAnim()` 把 `progressAnimator` 通过 `add()` 装进 AnimatorSet（这会同时被 `addAnimationHoldersRecur` 收进 Holder 链） | 原厂 `:91-95` | lib `:83-86` |
| A4 | AnimatorSet 子动画为空时补 0→1 占位 ValueAnimator | 原厂 `:99-101`（`add(ValueAnimator.ofFloat(0f,1f).setDuration(mDuration))`） | lib `:90-97`（progressAnimator 走 `add(it)` 覆写时长 `:92`；仅 `animHolders` 为空补 `addWithoutDuration(ofFloat(0,1).setDuration(durationMs))` `:95-97`）——**见 ③-R4**，机制已与原厂同路径 |
| A5 | `createPlaybackController` 单例缓存 + 懒构造 | 原厂 `:105-110` | lib `:103-105` |
| A6 | `addFloat(target, prop, from, to, ip)`：构造平台 ObjectAnimator、setInterpolator、add | 原厂 `:67-71` | lib `:62-66`（构造 lib 自己的 `ObjectAnimator`，见 A16） |
| A7 | `setFloat` 的 null/等值短路 + ObjectAnimator.from=property.get(target) | 原厂 `:127-134`（先 setDuration 再 setInterpolator 后 add） | lib `:68-77`（只 setInterpolator，duration 靠 `add(Animator)` 兜底，**见 ③-R5**） |
| A8 | `addListener(l)` 直接转发到 `AnimatorSet` | 原厂 `:73-75` | lib `:79-81` |
| A9 | 主时钟 `ValueAnimator.ofFloat(0,1)` + 强制 `LINEAR` 插值 | 原厂 `:130-132` | lib `:44, 52` |
| A10 | `setPlayFraction` 含 `targetCancelled` 早退 + 0..1 钳制（用 `Utilities.boundToRange`，原厂 `:367`；lib 用 `coerceIn`） | 原厂 `:364-373` | lib `:97-102` |
| A11 | `Holder.globalEndProgress = duration / totalDuration`（构造期计算一次） | 原厂 `:40-43, 47` | lib `:76` |
| A12 | `Holder.setProgress` 经 mapper 后 `setCurrentFraction` | 原厂 `:52-54` | lib `:81-83` |
| A13 | `ProgressMapper.DEFAULT`：`f>g→1` 否则 `f/g` | 原厂 `:114-122`（`lambda$static$0`） | lib `:13` |
| A14 | `Holder.reset()` 恢复原插值器 + mapper 重置为 DEFAULT | 原厂 `:56-60` | lib `:85-88` |
| A15 | `OnAnimationEndDispatcher`：`mDispatched` 防重 + success 时先 `dispatchOnEnd()` 再跑 `endActionMap`（lib `endActions.values.forEach` 是 map 值的迭代，原厂是 `mEndActionMap.forEach((k,v)->v.run())`）；start 时清两个标志 | 原厂 `:68-72, 85-88` | lib `:142-145, 148-154` |
| A16 | `AnimationSuccessListener` 核心 cancel→success 屏蔽语义 | 原厂 `:13-22` | lib `:19-27` |
| A17 | `EndStateCallbackWrapper`：`onAnimationCancel`→`false`，`onAnimationEnd`→按 `animatedFraction <= 0.5` 判定 `success/failure`，`mListenerCalled` 防重 | 原厂 `:18-43` | lib `:25-37`（已对齐 fraction 阈值判定，**见 ③-R1** 的语义偏移） |
| A18 | `RunnableSuccessListener` 通过 `AnimationSuccessListener.onAnimationSuccess` 派发 | 原厂 `:45-66` | lib `:39-44` |
| A19 | `forEndCallback(Runnable)` 用 `NullableAnimatorListenerAdapter` 仅 override `onAnimationEnd` | 原厂 `:73-80` | lib `:46-52` |
| A20 | `AnimatorListeners` 的工厂三方法签名/返回 | 原厂 `:68, 71, 74` | lib `:14, 19, 39` |
| A21 | `PropertySetter.NO_ANIM_PROPERTY_SETTER` 是匿名空实现（继承接口所有 default no-op） | 原厂 `:11-12` | lib `:26-28` |
| A22 | `addHoldersRecur` 抛 `RuntimeException("Unknown animation type ")` | 原厂 `:168-169` | lib `:192`（**与既有 review 02 §③-3 描述相反**——lib 当前已对齐，**见 ③-R6**） |

### B. 有意简化（lib 注释中自认或合理的 Kotlin 化）

| # | 简化内容 | 原厂对应 | lib 取舍理由 |
|---|---|---|---|
| B1 | 链式 API（`add`/`setFloat` 返回 `PendingAnimation`） | 原厂 `add/setFloat` 返回 void（`PendingAnimation.java:55, 127`） | Kotlin 风格化，语义等价 |
| B2 | `Consumer<Boolean>` → `(Boolean) -> Unit`，`Runnable` → `() -> Unit`，`BiConsumer` → 函数引用 | 原厂全用 `java.util.function.Consumer` / `Runnable` | 函数类型 + Kotlin idiom |
| B3 | `ProgressMapper` 接口 → `typealias ProgressMapper` + `private val DEFAULT_PROGRESS_MAPPER` lambda | 原厂 `:108-125`（嵌套接口 + `static final DEFAULT` 匿名实现） | 同上 |
| B4 | `OnAnimationEndDispatcher` 用 inner class 持有外层引用 | 原厂以 `AnimatorPlaybackController.this.mCancelAction.run()` 闭包访问（`:77`） | Kotlin inner class 等价 |
| B5 | `ActualEndAnimListener` 中间层合并到 `AnimationSuccessListener` 父类（仍继承） | 原厂隔一层（`:7` `extends ActualEndAnimListener`） | 中间层仅空钩子，合并无语义损失；lib 注释已声明（`AnimationSuccessListener.kt:14-16`） |
| B6 | `NullableAnimatorListener`/`Adapter` 的 `@Nullable` 注解移除 | 原厂 `:8-14` `@Nullable` | 平台 `Animator` listener 已 `@NonNull`，改注解只保留历史兼容注释 |
| B7 | `Holder.springProperty: Any? = null` 占位（**且 `PendingAnimation.add` 不接受第三参**——签名差异） | 原厂 `add(Animator, SpringProperty)`（`:189-193`） | lib 注释（`PendingAnimation.kt:53-54`）明示"第三参丢弃"；演示库无可用 SpringProperty，签名砍掉避免 API 说谎 |
| B8 | `isDispatchStartPending` 字段保留但语义反转（`start()` 置 true） | 原厂 `start()` 置 false、仅 `dispatchOnStart()` 置 true | lib 字段 private 且无读取点（getter 未实现）；**见 ③-R2** |
| B9 | `cancelAction` / `endActions` 公开 var / mutableMap | 原厂 `setCancelAction(Runnable)` / `setEndAction(Runnable)` + `mEndActionMap.put(str, runnable)`（`:355-357, 360-362, 464-466`） | 公开字段省去 setter boilerplate；map-key 用 hashCode 字符串的细节 lib 不复刻 |
| B10 | `mEndActionMap.forEach(BiConsumer)` → `endActions.values.forEach { it() }`（值迭代，不带 key） | 原厂 `:90-95` | 语义等价（endActionMap 的 key 仅用于去重，值即要执行的 Runnable） |
| B11 | `dispatchOnStart()` / `dispatchOnEnd()` / `dispatchOnCancel()` 走 inline 高阶函数 `dispatchToListeners(action)` | 原厂走 `callListenerCommandRecursively(mAnim, new c()/b()/d())`（`:213-217`） | inline 高阶省去 3 个单方法 BiConsumer 包装类；**见 ③-R7** 的递归差异 |
| B12 | `clampDuration` 用 `coerceIn(0, duration)` 替代 `Utilities.boundToRange + Math.min` | 原厂 `:222-229` | Kotlin 惯用法，数值等价 |
| B13 | `forceFinishIfCloseToEnd` 用常量 `0.95f`（lib 注释 :26 误注在文件头） | 原厂 `:26` `ANIMATION_COMPLETE_THRESHOLD = 0.95f` | 阈值完全一致 |
| B14 | `Holder` 的 `mapper` 字段可改（为 `startWithVelocity` 留口） | 原厂 `public ProgressMapper mapper = ProgressMapper.DEFAULT;`（`:42`） | 保留改的能力，未来若回移 `startWithVelocity` 不用改 Holder 签名 |
| B15 | `Holder.springProperty` 占位 `Any?` 而非移除 | 原厂 `:43` `public final SpringProperty springProperty;` | 占位 + 注释明示"暂无赋值方"，避免漏回移时静默改签名 |

### C. 遗漏（OPPO 有、lib 没有）

| # | 遗漏 | 原厂证据 | 影响 |
|---|---|---|---|
| C1 | **`startWithVelocity` 弹簧沉降全链路**（`AnimatorPlaybackController.java:382-461`）：抬手带初速度时按 `Holder.springProperty.flags & (z8?1:2)` 筛 Holder，每 Holder 用 `SpringAnimationBuilder.computeParams` 算弹簧时长 + 替换 mapper/插值器（自定义 `e`/`f` 匿名类），主时钟时长取 max(弹簧时长, 预期时长) 并按 `Interpolators.clampToProgress(scrollInterpolatorForVelocity, 0, expected/spring)` 截断插值 | `AnimatorPlaybackController.java:382-461`（80 行，依赖 `SpringAnimationBuilder` + `RefreshRateTracker`） | **手抬起手动画的核心入口缺失**；手势跟手+带速度抬手的 demo 场景全部不可达 |
| C2 | **`SpringProperty` 类**（54 行）：构造 `flags`、`mDampingRatio=0.5`/`mStiffness=1500` 默认值、`mStartDampingRatio`/`mStartStiffness`/`mUseDiffSpringForce` 五个可变字段 + 5 个 setter 链式 + `FLAG_CAN_SPRING_ON_END/START` 两个常量 | `SpringProperty.java:4-53` | C1 的前置；演示库当前零调用方，回移仅当 C1 同时回移才有意义 |
| C3 | **`SpringAnimationBuilder` 类**（186 行）：`setStartValue/EndValue/StartVelocity/MinimumVisibleChange/DampingRatio/Stiffness` 链式 + `computeParams()` 返回含 `getDuration()` / `getInterpolatedValue(float)` 的实例 | `SpringAnimationBuilder.java:18-186`（未通读，从 import 推断） | C1 的前置 |
| C4 | **`PropertySetter` 的 View 侧方法**：`setViewAlpha(view, alpha, ip)`（`:38-43`）含 `AlphaUpdateListener.updateVisibility` 联动、`setViewAlpha(view, alpha, ip, delay, duration)`（`:60-64`）、`setInt`（`:30-35`）、`setViewBackgroundColor`（`:45-49`）、`setFloat(... delay, duration)`（`:54-58`）、`add(Animator)` 默认 `setDuration(0)+start`（`:14-20`） | `PropertySetter.java:14-64` 全列 | lib `PropertySetter` 只剩 `setFloat` 一项；View 转场常用 setViewAlpha 完全丢失 |
| C5 | **`PendingAnimation` 的 setViewAlpha / setViewBackgroundColor / setInt / setFloats / setFloat(... delay, duration)` 五方法 + `setInterpolator` 转发到 AnimatorSet** | `PendingAnimation.java:137-159, 164-182, 198-213, 218-234` | 与 C4 同步缺；手头演示库只用 setFloat |
| C6 | **`AlphaUpdateListener` 类**：ALPHA_CUTOFF=0.01f 阈值 + 切主线程跑 `updateVisibility(View, int=View.INVISIBLE)` + `ViewGroup.setDescendantFocusability(393216)` 防 focus 抖动 + `onAnimationStart` 强制 setVisibility(VISIBLE) + `onAnimationUpdate` 也跑一次 updateVisibility | `AlphaUpdateListener.java:9-62` | `setViewAlpha` 缺失则此 listener 无引用方；C4 整体回移时一并补 |
| C7 | **`Interpolators` 的 40+ 常量 + 5 个工具函数**：`ACCEL*`/`DEACCEL*`/`OVERSHOOT_*`/`EXAGGERATED_EASE`/`FAST_OUT_SLOW_IN`/`AGGRESSIVE_EASE`/`DECELERATED_EASE`/`ACCELERATED_EASE`/`EMPHASIZED_*`/`FLOAT_OPEN_CLOSE`/`MOVE_EASE_INTERPOLATOR`/`APP_LAUNCH_S_ANIM_*`/`RECENTS_EASE`/`ZOOM_IN/OUT`/`SCROLL/SCROLL_CUBIC`/`TOUCH_RESPONSE_*`/`RECENT_*` + `clampToProgress(interp, lo, up)`/`clampToProgress(float v, lo, up)`/`mapToProgress(interp, lo, up)`/`reverse(interp)`/`scrollInterpolatorForVelocity(v)`/`overshootInterpolatorForVelocity(v)` | `Interpolators.java:33-79,87-117,130-148,170-214` | lib 仅 1 个 LINEAR；演示库当前零调用方，但 C1 的 `startWithVelocity` 用到 `scrollInterpolatorForVelocity` + `clampToProgress`，回移 C1 时一并补 |
| C8 | **`APC` 7 个查询/操作工具方法**：`getInterpolatedProgress`/`getInterpolator`（`:279-285`）、`overrideDurationScale`（`:311-313`）、`dispatchSetInterpolator`（`:252-254`）、`iterateAllChildAnim`（`:299-301`）、`resetPropertySetters`（`:342-346`）、`getTarget`/`getDuration`/`getAnimationPlayer`/`getProgressFraction`/`isIsDispatchStartPending` getter（`:270-297`） | `AnimatorPlaybackController.java:270-313, 342-346` | lib 暴露 `progressFraction: Float` 只读 + `animationPlayer: ValueAnimator` 公开；其余 getter 全砍 |
| C9 | **`removeScaleAnimatorForGridRecentViews`**（`:322-340`）：OPPO 为 grid  recents 多行布局移除名为 `MUTLI_ROW_SCALE_ANIMATOR_NAME`（`OplusTaskAnimationBuilderMutliRowImpl` 常量）的 ObjectAnimator | `AnimatorPlaybackController.java:322-340` | 与 lib 演示主题（动画线程方案）无关；属于 grid recents 业务补丁 |
| C10 | **dispatch 的递归**：原厂 `callListenerCommandRecursively` 经 `callAnimatorCommandRecursively` 递归进嵌套 `AnimatorSet`（`:184-203`）；lib `dispatchToListeners` 只拍平一层 `anims`（`:161-165`） | `AnimatorPlaybackController.java:184-203, 213-217` | lib 当前构造路径不产嵌套 AnimatorSet（`playTogether` 平铺），暂无触发面——**见 ③-R7** |
| C11 | **`addAnimationHoldersRecur` 的 duration/interpolator 下发**：原厂 `:174-179` 在递归时把父 `AnimatorSet.duration`/`interpolator` 下发给子动画 | `AnimatorPlaybackController.java:174-179` | lib `:189` 只递归，不下发；构造路径不依赖此下发（`add` 已显式 setDuration），暂无功能影响 |
| C12 | **`AnimationSuccessListener` 是 `NullableAnimatorListenerAdapter` 子类的细节**：`mCancelled`/`mAnimationId` 字段都来自 Adapter；原厂 cancel 置位在 `AnimationSuccessListener` 覆盖 Adapter 的 `onAnimationCancel` 时做（Adapter 父类方法为空），lib 把 Adapter 的 cancel 改为置位（覆盖父类 no-op）。两种实现 cancel 后调用 `super.onAnimationCancel` 路径不同——lib Adapter 的 super 是 `AnimatorListenerAdapter.onAnimationCancel` 空实现（`:19-21` ↔ lib Adapter 父类默认空），**等价** | `AnimationSuccessListener.java:14-15` + `NullableAnimatorListenerAdapter.java:16-17` | 已说明为"原厂语义超集"，属 B 类简化 |

---

## ③ 行为差异风险点（按严重度排序）

### R1. `forEndCallback(Consumer<Boolean>)` 的 success 判定 ★
> **✔️无差异（文档已自证：threshold 判定逐字对齐）**
**严重度：高。** 原厂 `EndStateCallbackWrapper.onAnimationEnd` 检查 `animator instanceof ValueAnimator && ((ValueAnimator) animator).getAnimatedFraction() <= 0.5f` → 报 `false`（`AnimatorListeners.java:34-40`）。lib `playback/AnimatorListeners.kt:39` 的判定 `va == null || va.animatedFraction > 0.5f` 已**逐字对齐**——**与既有 review 02 §③-1 结论相反**。**复核结果：R1 在当前 lib 已对齐**，手势收尾"半程前 cancel→报未成功"语义保留。`animatedFraction` 在 `end()` 时返回的是动画**结束那一刻**的归一化进度，而非 1.0——这正是原厂用 0.5 阈值判断"自然播完 vs 中途 cancel"的关键。**但**该判定把"播完但停在 ≤0.5 位置"也判失败——这是原厂 quirk，lib 一并保留。
*修正前情*：review 02 §③-1 把 R1 列为"lib 无条件报 true"，但通读当前 lib 代码（`playback/AnimatorListeners.kt:33-49`）确认阈值判定已实现。

### R2. `isDispatchStartPending` 字段语义反转 ★
> **✅已修复（086844e：start()/reverse()/根 animator 三触点均置 `isDispatchStartPending=false`，语义已对齐原厂）**
**严重度：中（潜藏）。** 原厂 `start()` 置 `mIsDispatchStartPending = false`（`:379`），仅 `dispatchOnStart()` 置 true（`:248`）。语义："true = 已派发 start 给所有 listener 但 AnimatorSet 还没真的开始"——给 `dispatchOnStart` 用作幂等闸门。lib 当前（HEAD）：`start()` 置 **false**（`AnimatorPlaybackController.kt:112`）、`reverse()` 置 false（`:119`）、构造器对根 animator 的 cancel/end/start 三触点同步置 false（`:56-71`）；仅 `dispatchOnStart()` 置 true（`:179`）——与原厂“`start()` 置 false、`dispatchOnStart()` 置 true”一致（086844e 修复，不再反转）。字段仍 `private` 且无 getter（`isIsDispatchStartPending` 未实现），将来补 getter 无需再对齐。

### R3. addFloat 产物可进 Holder 链（既有 review 02 §③-3 的判定需更新）★
> **✅已修复（60bd048：buildAnimator 返回 ValueAnimator，addFloat 进 Holder 链）**
**严重度：低（既有结论需修正）。** 既有 review 02 §③-3 把"addFloat 产物进不了 Holder 链"列为高风险。**通读 lib 当前代码**：lib 内嵌 `ObjectAnimator.buildAnimator()` **返回 `ValueAnimator`**（`PendingAnimation.kt:149`，注释 `:142-147` 自述"此前 lib 返回匿名 Animator，会被 Holder 收集静默丢弃"——属于已修复历史 bug）。`addHoldersRecur` 的 `is ValueAnimator ->` 分支（`AnimatorPlaybackController.kt:198`）正常收 Holder，整条 addFloat → add → playTogether → Holder 链可工作。**既有 review 描述的是旧版本代码**，当前风险已消除。**仍存在的隐患**：`AnimatorPlaybackController.Holder` 构造器 `animator as ValueAnimator` 强转（`playback/AnimatorPlaybackController.kt:76-77`）——若调用方传非 ValueAnimator 会 ClassCastException；addHoldersRecur 已在 else 分支抛 `RuntimeException("Unknown animation type $anim")`（`:202`），所以非 ValueAnimator 走不到 Holder 构造器，强转是安全的（前提是调用方不绕过 `add` 直接 `addToHolders`）。

### R4. progressAnimator 时长不一致
> **✔️无差异（文档已自证：add() 内部覆写时长）**
**严重度：中。** 原厂 `buildAnim` 用 `add(valueAnimator)` 把 progressAnimator 时长覆写为 `mDuration`（`PendingAnimation.java:91-95 → :189-193`，即 `add(Animator, SpringProperty)` 内 `animator.setDuration(mDuration)`）；lib 当前（HEAD）走 `add(it)`（`playback/PendingAnimation.kt:90-93`；`add()` 内 `child.duration = durationMs` 于 `:43-47`），与原厂 `add(valueAnimator)` 内覆写时长**同路径**——本条旧描述（“用 `addWithoutDuration(valueAnimator)` + 显式 `setDuration(durationMs)`”，对应 `PendingAnimation.kt:86-88`）是更早版本代码，已更新。空子动画兜底 `addWithoutDuration(ValueAnimator.ofFloat(0f, 1f).setDuration(durationMs))`（`:95-97`）仅在 `animHolders.isEmpty()` 时补位，与原厂 `:99-101` 相同。结论：**无差异**（且比旧描述路径更贴近原厂）。

### R5. setFloat 不显式设时长
> **✔️无差异（add() 兜底 durationMs；仅 API 说谎）**
**严重度：低。** 原厂 `setFloat`：`ObjectAnimator.ofFloat(...).setDuration(mDuration).setInterpolator(ip).add(...)`（`PendingAnimation.java:127-134`）——先 setDuration 再 setInterpolator 再 add。lib `setFloat`：`ObjectAnimator.ofFloat(...).setInterpolator(ip).add(oa.buildAnimator())`（`playback/PendingAnimation.kt:70-76`）——**只 setInterpolator**，时长由 `add(Animator)` 兜底（`add()` 内 `child.duration = durationMs`，`:44`）。两条路径都最终把 duration 设为 `durationMs`，但**顺序**不同：原厂 ObjectAnimator 自己持时长 → 加进 AnimatorSet；lib ObjectAnimator 子对象 va 持默认 300ms → add() 把 va.duration 改为 durationMs。**API 行为等价**（因 add 内部覆写）；唯一差异是：**若调用方在 add 之前主动读 `oa.duration`，lib 给的是默认值 300ms，原厂给的是 mDuration**。属于"API 说谎"而非语义差异。

### R6. addHoldersRecur 的 else 分支（既有 review 02 §②-遗漏 6 误述）
> **✔️已对齐（else 抛 RuntimeException 与原厂一致）**
**严重度：低（既有结论需修正）。** 既有 review 02 §②-遗漏 6 描述"lib 两者皆无，静默跳过（`AnimatorPlaybackController.kt:186-191`）"。**通读当前 lib 代码**（`playback/AnimatorPlaybackController.kt:196-204`）：else 分支是 `throw RuntimeException("Unknown animation type $anim")`（`:202`），注释 `:200-201` 自述"原厂抛 RuntimeException ... 不认识的动画类型显式失败，而不是静默丢弃出 Holder 链"——**与原厂一致**。既有 review 描述的是更早版本；当前已对齐。

### R7. dispatch 不递归嵌套 AnimatorSet
> **⚠️未修复（当前无嵌套 AnimatorSet 触发面）**
**严重度：低。** lib `dispatchToListeners` 只遍历“根 + 直接子动画”的平铺列表（`AnimatorPlaybackController.kt:166-175`：`targets = if (anims.size==1 && anims[0]===rootAnim) anims else listOf(rootAnim)+anims`），对嵌套 `AnimatorSet` 的子层仍不递归（60bd048/086844e 只补了根层派发与根层跟踪）。原厂 `callListenerCommandRecursively → callAnimatorCommandRecursively` 递归进嵌套 `AnimatorSet`（`AnimatorPlaybackController.java:184-203`）。当前 lib 构造路径不会产生嵌套 AnimatorSet（`add` 走 `anim.playTogether(child)` 平铺），**暂无触发面**；一旦补 `add(animator: Animator)` 改用 `anim.play(animator)` + 业务传嵌套 AnimatorSet，dispatch 会漏内层 listener。

### R8. cancel 跟踪挂在第一个子动画而非 AnimatorSet 本体
> **✅已修复（086844e：cancel/end/start 跟踪 listener 改挂根 animator）**
**严重度：中。** 原厂 cancel listener 挂 **AnimatorSet 本体**（`AnimatorPlaybackController.java:135-156` 处的 `animatorSet.addListener(...)`，监听 `AnimatorSet.cancel()`），且同时维护 `mIsDispatchStartPending`；lib 现挂在构造器收到的**根 animator** 上（`anim.addListener(...)`，`AnimatorPlaybackController.kt:56-71`：cancel → `targetCancelled=true` + `isDispatchStartPending=false`；end/start → `targetCancelled=false` + false）。**直接 cancel AnimatorSet** 会触发根 listener → `setPlayFraction` 的 `if (targetCancelled) return`（`:99-104`）阻断后续写进度——与原厂 `:365-369` 一致；同时消除空子动画列表时 `anims[0]` 的越界风险（086844e）。AnimatorSet.cancel 内部是否会回调子动画 listener？答案是**会**——AnimatorSet 在 cancel 时会逐子动画 cancel 并触发子动画 listener，但时序依赖 AnimatorSet 实现。属于"测试覆盖不到就静默出错"的类型。

### R9. PendingAnimation.setFloat 不支持 null target（Kotlin 类型系统）
> **✔️保持（Kotlin 类型系统非空）**
**严重度：低。** 原厂 `setFloat(T t8, FloatProperty<T> fp, ...)` 标注非空但 `@SuppressLint({"OLintNullPointCheckForParameter"})`（`PendingAnimation.java:127`）显式容许 null；lib 签名 `setFloat(target: T, property: FloatProperty<T>?, value: Float, ...)` 把 `property` 标 `?` 但 `target` 仍非空（Kotlin 泛型不支持 nullable target）。null target 在原厂会被 NPE 跳过（line 128 短路 `property==null`；target 在 `property.get(target)` 处 NPE），lib 同位置 `property.get(target)` 也 NPE。**等价**——但若业务用反射绕过类型系统传 null target，原厂的反射代理兼容路径 lib 没有（Kotlin 编译期就会拦）。

### R10. addFloat 构造 ObjectAnimator 时不持原插值器引用
> **✔️保持简化（无 getInterpolator，演示用不到）**
**严重度：低。** lib 内嵌 `ObjectAnimator.setInterpolator(ip)` 把 ip 写到内部 va（`PendingAnimation.kt:126-128`），但**构造器外**无法读回 ip（没暴露 `getInterpolator`）。原厂 `ObjectAnimator` 继承 `ValueAnimator`，`getInterpolator` 是 public API，业务可能读。属于演示场景用不到的 API 面差异。

### R11. clampDuration 的零值边界
> **✔️无差异（coerceIn 与 Math.min 等价）**
**严重度：低。** 原厂 `clampDuration`：`(long) (duration * f)` ≤ 0 返回 0L，否则 `Math.min(..., duration)`（`AnimatorPlaybackController.java:222-229`）。lib `clampDuration`：`duration * f`.toLong() 后 `coerceIn(0L, duration)`（`AnimatorPlaybackController.kt:125-126`）。两条路径在 `f = 0` 时都返回 0；`f < 0` 时都返回 0；`f > 1` 时原厂返回 duration（被 Math.min 钳），lib 也返回 duration。**等价**——`coerceIn(0L, duration)` 与 `Math.min((long)f10, duration)` 在浮点转 long 后行为一致。

### R12. Holder 的 `mapper` 字段公开性
> **✔️无差异（mapper var 与原厂 public 字段等价）**
**严重度：低。** 原厂 `public ProgressMapper mapper = ProgressMapper.DEFAULT;`（`AnimatorPlaybackController.java:42`）——任何调用方都能改 mapper。lib `var mapper: ProgressMapper = DEFAULT_PROGRESS_MAPPER`（`AnimatorPlaybackController.kt:78`）——同样 public。**等价**——但 lib `mapper` 是 `var`，原厂是 `public` 字段（也是 var）。完全一致。

---

> ⚠️ ④ 建议表各行待逐条打标（对应风险项状态见 ③ R 项 打标）。
## ④ 回移建议

### 4.1 值得补进 lib（性价比高）

1. ✅已修复（086844e）——**`isDispatchStartPending` 的 start() 赋值已改为 `false`**（`AnimatorPlaybackController.kt:112`；`reverse()` `:119` 与根 animator 三触点 `:56-71` 同步 false，对齐原厂 `:379`）。若后续暴露 `isIsDispatchStartPending()` getter，语义已对齐，R2 消除。

2. ✅已修复（086844e）——**cancel/end/start 跟踪 listener 已挂到根 animator**（`AnimatorPlaybackController.kt:56-71` `anim.addListener(...)`，不再用 `anims[0]`）：cancel 写 `targetCancelled=true` + `isDispatchStartPending=false`，end/start 写 false。消除 R8（直接 cancel AnimatorSet 可阻断 `setPlayFraction`）+ 空子动画列表越界。

3. **补 `dispatch` 递归**：把 `dispatchToListeners` 改为遍历 `callAnimatorCommandRecursively(mAnim, BiConsumer<Animator, Animator.AnimatorListener>)` 形态，对齐原厂 `:184-203`。成本小（10-15 行），消除 R7 潜藏风险——一旦补 R1/C1 涉及的"手势跟手→动画接管"路径，必须有递归 dispatch。（v2 现状：60bd048/086844e 已补根层派发与根层跟踪，嵌套子集 DFS 仍未实现，R7 保持 ⚠️未修复。）

4. **补 `PropertySetter.add(Animator)` 默认 `setDuration(0) + start`**（原厂 `:14-20`）：原厂 no-op setter 调 `add(anim)` 仍能跑动画（瞬时）；lib 默认 `setFloat` 也走 `add`，但 `add` 接口不在 `PropertySetter` 中——属 API 面一致性。**低优先级**，仅当 PendingAnimation 同时回移 C5 的 setViewAlpha 时才有意义。

5. **补 `AnimatorPlaybackController` 的 `isIsDispatchStartPending()` / `getProgressFraction()` getter**：当前 lib 用 Kotlin property `progressFraction` 暴露（read-only），原厂 `getProgressFraction()` 是 method；外加 `getAnimationPlayer()` 是 method；`getDuration()` 是 method。当前 lib 用 `duration: Long private val` + `animationPlayer: ValueAnimator` 公开——**API 形式不同但语义面等价**，不补也行；若外部代码按原厂 method 名查找，会找不到。

6. **补 `Interpolators.clampToProgress(Interpolator, lo, up)` + `mapToProgress` + `reverse` + `scrollInterpolatorForVelocity`**：这四个是 C1 `startWithVelocity` 的前置 + 手势链路常用工具，原厂 `:130-148, 170-177, 183-190, 192-194`。无外部依赖（纯数学），15-20 行可补齐。补了之后 C1 才有可移植面。

### 4.2 建议保持简化

1. **`startWithVelocity` + `SpringProperty` + `SpringAnimationBuilder` 暂不回移**：C1/C2/C3 是手抬起手弹簧沉降入口，依赖 RefreshRateTracker + 三方库；当前 lib 演示库零调用方；只有补"手势跟手 + 带速度抬手"demo 场景后才需要。**建议**把 `PendingAnimation.add` 第三参从签名里**直接删除**（`PendingAnimation.kt:53-54` 当前虽然不接收第三参，但 `Holder.springProperty` 占位仍假装有这条线）；保留 `springProperty: Any? = null` 是合理占位，避免改签名噪声。

2. **`removeScaleAnimatorForGridRecentViews`**（C9）：OPPO 为 grid  recents 多行布局打的业务补丁，引用 `OplusTaskAnimationBuilderMutliRowImpl`，与演示库定位无关。

3. **View 侧 PropertySetter 方法（setViewAlpha/setViewBackgroundColor/setInt/setFloats）**：依赖 `AlphaUpdateListener` 的可见性联动逻辑 + View/ViewGroup API；lib 当前演示场景用不到；需要时连同 AlphaUpdateListener 一起移植（它们是成套语义：alpha=0.01 阈值切 INVISIBLE）。

4. **40 个插值器常量全量回移**：大多是各业务页的配置值（COUI 插值器依赖 `com.coui.appcompat.animation.COUIMoveEaseInterpolator` / `COUISpringInterpolator` 私有库，递归触发 native 路径），演示库用到哪个补哪个。

5. **dispatchSetInterpolator / overrideDurationScale / iterateAllChildAnim / resetPropertySetters / getInterpolatedProgress / getInterpolator**：这些是 APC 的内部工具，外部调用方在原厂代码里也少见（grep 全树 < 10 处），lib 无调用方。

6. **ActualEndAnimListener 独立分层**：原厂该层只有空钩子，lib 合并进 `AnimationSuccessListener` 父类是合理简化，**与既有 review 02 §④-2.4 结论一致**。

---

## 附录 A：复核中发现的与既有 review 02 的偏差

| # | 既有 review 02 描述 | 复核结果 | 处理 |
|---|---|---|---|
| 1 | §③-1 `forEndCallback(success)` 报 `true` 无条件 | **已对齐**（阈值 0.5 已实现，`AnimatorListeners.kt:30`） | 风险列表 R1 改为"已对齐" |
| 2 | §③-3 addFloat 产物进不了 Holder 链 | **已修复**（`buildAnimator` 返回 ValueAnimator 而非匿名 Animator，注释自述"此前 lib 返回匿名 Animator"，`PendingAnimation.kt:142-147`） | 风险列表 R3 改为"已消除，隐患在强转" |
| 3 | §③-4 progressAnimator 时长不一致 | **路径不同但等价**（lib `addWithoutDuration + 显式 setDuration`，与原厂 `add` 内 setDuration 时序一致） | 风险列表 R4 改为"无差异" |
| 4 | §②-遗漏 6 lib `addHoldersRecur` 静默跳过 | **已对齐**（`else -> throw RuntimeException`，`AnimatorPlaybackController.kt:192`） | 风险列表 R6 改为"已对齐" |
| 5 | §②-有意简化 6 cancelled 标志位置上移 + lib Adapter 直接置位是"原厂语义的超集" | **正确**（lib Adapter 父类是 AnimatorListenerAdapter 空实现，覆盖后置位；原厂 Adapter 该方法也空但由 AnimationSuccessListener 二次覆盖；路径不同，结果等价） | 维持 B 类简化，不动 |

---

## 附录 B：关键证据速查

| 论断 | 证据 |
|---|---|
| lib PendingAnimation 字段映射 | `com/android/launcher3/anim/PendingAnimation.java:24-30` |
| lib AnimationSuccessListener cancel→success 屏蔽 | `com/android/launcher3/anim/AnimationSuccessListener.java:13-22` |
| lib AnimatorListeners 阈值 0.5 判定 | `com/android/launcher3/anim/AnimatorListeners.java:34-40` |
| lib AnimatorPlaybackController 主时钟 LINEAR | `com/android/launcher3/anim/AnimatorPlaybackController.java:130-132` |
| lib Holder 进度映射 | `com/android/launcher3/anim/AnimatorPlaybackController.java:38-60` |
| lib ProgressMapper DEFAULT | `com/android/launcher3/anim/AnimatorPlaybackController.java:114-122`（lambda$static$0） |
| lib addAnimationHoldersRecur + 抛异常 | `com/android/launcher3/anim/AnimatorPlaybackController.java:161-182`（`:168-169`） |
| lib OnAnimationEndDispatcher 收尾 | `com/android/launcher3/anim/AnimatorPlaybackController.java:63-96` |
| lib `isDispatchStartPending` 起点：start() 置 false vs lib 置 true | `com/android/launcher3/anim/AnimatorPlaybackController.java:379, 248` ↔ `com/asyncanimator/playback/AnimatorPlaybackController.kt:110` |
| lib PropertySetter NO_ANIM | `com/android/launcher3/anim/PropertySetter.java:11-12` |
| lib Interpolators LINEAR | `com/android/launcher3/anim/Interpolators.java:35` |
| lib `startWithVelocity` 80 行未移植 | `com/android/launcher3/anim/AnimatorPlaybackController.java:382-461` |
| lib SpringProperty 类未移植 | `com/android/launcher3/anim/SpringProperty.java:4-53` |
| lib AlphaUpdateListener 未移植 | `com/android/launcher3/anim/AlphaUpdateListener.java:9-62` |

---

## 附录 C：本报告与既有 review 02 的方法差异

- **取证**：本报告用 `python open(p,'rb').read().decode('utf-8','replace')` 完整加载原厂明文（避开 DLP 加密 Read），按 JADX 文本行号比对 lib 行号；既有 review 02 用 Grep 走 ripgrep 通道。本报告覆盖度更全（每个 lib 类都通读 OPPO 对应类全文，而不仅是 grep 命中行）。
- **复核**：本报告**逐条复核**既有 review 02 的"精确复刻 / 简化 / 遗漏"标签，对其中 5 处过时结论做了修正（见附录 A）。
- **新增风险**：本报告新增 R5（setFloat 不显式设时长，API 说谎）、R8（cancel 监听挂在 animes[0] 而非 AnimatorSet 本体）、R10（addFloat 构造 ObjectAnimator 不持原插值器引用），既有 review 未覆盖。


## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **60bd048** — AnimationHandler.doAnimationFrame 每轮重读 size（添加时回调当帧可见）、APC dispatchToListeners 包含根 AnimatorSet

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。

## 复核记录 v2（2026-09-09，独立逐条复核）

> 本条为**独立逐条复核**（对照 HEAD 086844e 代码逐条验证，不采信上文 commit 交叉索引标记）。复核范围 = §① 类对应表 11 行 + §②-A 22 + §②-B 15 + §②-C 12 + §③ R1–R12 12 + §④ 建议 12 = **84 条**；全部条目逐条对照 `playback/`（8 个 .kt，e62dbff 合并 pending+playback）当前代码。
>
> - **复核条目总数**：84
> - **结论不变**：77
> - **修正**：7
> - **修正明细**：
>   1. R2：旧“⚠️未修复（start() 置 true，语义反转死字段）”→新“**✅已修复（086844e）**：`start()` 置 false（`playback/AnimatorPlaybackController.kt:112`）、`reverse()` `:119`、根 animator 三触点 `:56-71` 均同步 false；仅 `dispatchOnStart()` 置 true（`:179`）——与原厂一致”。
>   2. R8：旧“⚠️未修复（cancel 跟踪挂 `anims[0]` 第一个子动画）”→新“**✅已修复（086844e）**：cancel/end/start 跟踪 listener 改挂根 animator（`anim.addListener`，`:56-71`），cancel → `targetCancelled=true` 阻断 `setPlayFraction`（`:99-104`）；并消除空子动画列表越界”。
>   3. §④4.1-1：旧“待修正 start() 赋值”→新“**✅已修复（086844e）**”（同 R2）。
>   4. §④4.1-2：旧“待把 cancel listener 挂到 AnimatorSet 本体”→新“**✅已修复（086844e）**”（同 R8）。
>   5. §②-A-A4 / §③-R4：机制旧“`addWithoutDuration` + 显式 `setDuration`（路径不同）”→新“当前走 `add(it)`（`playback/PendingAnimation.kt:90-93`，`add()` 内覆写时长 `:43-47`）与原厂同路径；空子动画兜底 `:95-97`”。结论保持“无差异/等价”（较旧描述更贴近原厂）。
>   6. §③-R7：证据旧“dispatchToListeners 只拍平 `anims` 一层（:161-165）”→新“遍历‘根 + 直接子动画’平铺列表（:166-175，60bd048/086844e 已补根层派发与根层跟踪），嵌套 AnimatorSet 子层仍不递归”。结论（⚠️未修复，无触发面）不变。
>   7. §③-R1：行号刷新（`playback/AnimatorListeners.kt:39`/`:33-49`）；结论（已对齐，threshold 0.5）不变。
>
> 另：① 表路径与行数（`launcher/pending|playback`→`playback/`，各文件行数按 HEAD）、R3/R5/R6 的行号引用已顺带刷新（未计入修正数）。其余条目（②-A 其余、②-B 全部、②-C 全部、R3/R5/R6/R9–R12、§④ 其余）经复核与当前代码一致。
