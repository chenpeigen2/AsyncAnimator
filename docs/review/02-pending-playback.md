> **⚠️ 已废弃（superseded）**：本文档是第一轮（4 路）对比的旧版本，已被 [vs-oppo-02-pending-playback.md](vs-oppo-02-pending-playback.md) 取代。内容仅供参考，不要按本文档的结论修改代码。

# 区域 2 对比 Review：Pending / Playback 层

> 对比双方：
> - **lib**：`D:\AsyncAnimator\lib\src\main\java\com\asyncanimator\launcher\pending\` + `...\playback\`（Kotlin 重实现）
> - **原厂**：`D:\oppo_a6_launcher\sources\com\android\launcher3\anim\`（OPPO ColorOS 15 Launcher，JADX 反编译，AOSP Launcher3 同源类）
>
> 原厂文件全部被 DLP 加密（`%TSD-Header`），本文行号证据经 Grep 明文通道取得。
> 结论先行：**整体结构保真度高（8/8 类均有对应），核心播放模型（主时钟 + Holder 进度映射）逐行一致；但有 3 处会造成语义不同的实质性差异（end 回调 success 判定、progressAnimator 时长、addFloat 产物进不了 Holder 链），外加 startWithVelocity 弹簧沉降整条链路未移植。**

---

## ① 类对应关系表

| lib 类 | 原厂类 | 证据（lib → 原厂） |
|---|---|---|
| `pending/PendingAnimation.kt` (internal class) | `com/android/launcher3/anim/PendingAnimation.java:23` | `PendingAnimation.kt:24` → `PendingAnimation.java:23`；均 `implements PropertySetter`，字段一一对应：`anim`↔`mAnim`(:24)、`animHolders`↔`mAnimHolders`(:28)、`progressAnimator`↔`mProgressAnimator`(:27)、`isAnimFinished`↔`isAnimFinished`(:29) |
| `pending/AnimationSuccessListener.kt` | `com/android/launcher3/anim/AnimationSuccessListener.java:7` | `AnimationSuccessListener.kt:17` → `AnimationSuccessListener.java:7`；cancel 置位 + end 判定逻辑一致（lib `:19-27` ↔ 原厂 `:11-21`） |
| `pending/AnimatorListeners.kt` (object) | `com/android/launcher3/anim/AnimatorListeners.java:10` | `AnimatorListeners.kt:15` → `AnimatorListeners.java:10`；三工厂方法对应 `forEndCallback(Consumer)`(`:64`)、`forEndCallback(Runnable)`(`:73`)、`forSuccessCallback`(`:68`) |
| `pending/NullableAnimatorListener.kt` | `com/android/launcher3/anim/NullableAnimatorListener.java:7` | `NullableAnimatorListener.kt:11` → `NullableAnimatorListener.java:7`；三个默认空方法对应 |
| `pending/NullableAnimatorListenerAdapter.kt` | `com/android/launcher3/anim/NullableAnimatorListenerAdapter.java:8` | `NullableAnimatorListenerAdapter.kt:12` → `NullableAnimatorListenerAdapter.java:8`；`animationId`↔`mAnimationId`（`:17`↔`:9`） |
| `playback/AnimatorPlaybackController.kt` | `com/android/launcher3/anim/AnimatorPlaybackController.java:25` | `AnimatorPlaybackController.kt:27` → `AnimatorPlaybackController.java:25`；内部类 `Holder`（`:74`↔`:38`）、`OnAnimationEndDispatcher`（`:139`↔`:63`）、`ProgressMapper`（`:11`↔`:109`）全部对应 |
| `playback/PropertySetter.kt` | `com/android/launcher3/anim/PropertySetter.java:10` | `PropertySetter.kt:13` → `PropertySetter.java:10`；`NO_ANIM_PROPERTY_SETTER`（`:26`↔`:11`） |
| `playback/Interpolators.kt` (object) | `com/android/launcher3/anim/Interpolators.java:17` | `Interpolators.kt:9` → `Interpolators.java:17`；lib 仅保留 `LINEAR`（↔`:33`），`DECELERATE` 为 lib 自创（见 ②） |
| （合并进 AnimationSuccessListener） | `com/android/quickstep/util/animation/ActualEndAnimListener.java:9` | 原厂 `AnimationSuccessListener` 的父类，仅一个空钩子 `onAnimActualEnd`（`ActualEndAnimListener.java:10`）；lib 将该钩子合并进 `AnimationSuccessListener.kt:32` |
| （未移植） | `com/android/launcher3/anim/SpringProperty.java:4` | 原厂 `Holder.springProperty` 字段类型（`AnimatorPlaybackController.java:43`）；lib 以 `springProperty: Any? = null` 占位（`AnimatorPlaybackController.kt:79`） |
| （未移植） | `com/android/launcher3/anim/AlphaUpdateListener` | 原厂 `setViewAlpha` 的可见性联动（`PendingAnimation.java:169`、`PropertySetter.java:41`）；lib 无 `setViewAlpha` |

---

## ② 保真度评估

### 精确复刻（逐行/逐语义一致）

1. **主时钟播放模型**：lib `AnimatorPlaybackController` 内部 `ValueAnimator.ofFloat(0,1)` + 强制 `LINEAR` 插值（`AnimatorPlaybackController.kt:44,52`）↔ 原厂 `:130-132`。帧回调 `onAnimationUpdate → setPlayFraction`（lib `:93-102` ↔ 原厂 `:304-309, :364-373`），含 `mTargetCancelled` 早退与 0..1 钳制，逐行一致。
2. **Holder 进度映射**：`globalEndProgress = duration / totalDuration`（lib `:76` ↔ 原厂 `:50`）；`setProgress` 经 `mapper` 后 `setCurrentFraction`（lib `:81-83` ↔ 原厂 `:58-60`）；`ProgressMapper.DEFAULT` 的 `f>g→1 否则 f/g`（lib `:13` ↔ 原厂 `:117-122`）；`reset()` 恢复插值器+mapper（lib `:85-88` ↔ 原厂 `:53-56`）。
3. **start/reverse/pause/clampDuration/forceFinish 族**：`start` 从当前进度续播（lib `:106-111` ↔ 原厂 `:375-380`）、`reverse`（lib `:113-118` ↔ 原厂 `:348-353`）、`pause` 先 reset 全部 Holder 再 cancel 主时钟（lib `:120-123` ↔ 原厂 `:315-320`）、`clampDuration`（lib `:125-126` ↔ 原厂 `:222-229`）、`forceFinishIfCloseToEnd` 的 0.95 阈值（lib `:128-131` ↔ 原厂 `:256-261`，常量定义 `:26`）、`forceFinishIfNeed`（lib `:133-135` ↔ 原厂 `:263-269`）。
4. **OnAnimationEndDispatcher**：`mDispatched` 防重 + success 时先 `dispatchOnEnd()` 再跑 `endActionMap` 并清空 + cancel 时跑 `cancelAction`（lib `:139-159` ↔ 原厂 `:63-107`），含 onAnimationStart 重置双标志（lib `:142-145` ↔ 原厂 `:85-88`）。
5. **AnimationSuccessListener 核心语义**：cancel 置 `mCancelled`、end 时 cancelled 则不回调 success（lib `:19-27` ↔ 原厂 `:11-21`）。父类链差异（原厂隔一层 `ActualEndAnimListener`）因该类仅是空钩子，合并后语义等价。
6. **AnimatorListeners 两工厂**：`forEndCallback(Consumer<Boolean>)` 的 fire-once（lib `:25-37` 的 `listenerCalled` ↔ 原厂 `:14,22-35` 的 `mListenerCalled`）、`forSuccessCallback` 包 AnimationSuccessListener（lib `:39-44` ↔ 原厂 `:44-61`）。
7. **PendingAnimation 骨架**：构造器给 AnimatorSet 挂 listener 维护 `isAnimFinished`（lib `:34-40` ↔ 原厂 `:35-50`）；`buildAnim` 的"无子动画则补 0→1 占位 ValueAnimator"兜底（lib `:89-91` ↔ 原厂 `:99-101`）；`createPlaybackController` 单例缓存（lib `:95-97` ↔ 原厂 `:105-110`）；`addEndListener`/`addOnFrameCallback` 懒建 progressAnimator（lib `:72-78,100-101` ↔ 原厂 `:60-65,81-86`）。
8. **PropertySetter.NO_ANIM_PROPERTY_SETTER 语义**：直接 `setValue` 不动画（lib `:26-30` ↔ 原厂默认方法 `:23-28` + 空匿名类 `:11-12`）。

### 有意简化（行为等价或影响可控）

1. **链式 API**：原厂 `add`/`setFloat` 返回 void（`PendingAnimation.java:55,127`），lib 返回 `PendingAnimation` 支持链式（`PendingAnimation.kt:42,53`）——纯 Kotlin 风格化。
2. **`Consumer<Boolean>` → 函数类型**：lib `addEndListener(onEnd: ((success: Boolean) -> Unit)?)`（`PendingAnimation.kt:72`）↔ 原厂 `Consumer<Boolean>`（`PendingAnimation.java:60`）。
3. **`ProgressMapper` 接口 → typealias**（lib `:11` ↔ 原厂 `:109-125`）：Kotlin 惯用法，语义等价。
4. **ActualEndAnimListener 中间层合并**：见 ① 末行，lib 侧注释已声明（`AnimationSuccessListener.kt:14-16,31-32`）。
5. **NullableAnimatorListener 的 @Nullable → @NonNull**：原厂参数标注 `@Nullable`（`NullableAnimatorListener.java:8-14`），lib 换平台 Animator 后派发真实对象，可空容忍仅作历史注释（`NullableAnimatorListener.kt:6-10`）——lib 侧已说明。
6. **cancelled 标志位置上移**：原厂 `mCancelled` 在 `AnimationSuccessListener`（`:8`），lib 提到 `NullableAnimatorListenerAdapter`（`NullableAnimatorListenerAdapter.kt:15`）。原厂 Adapter 的 cancel 是空实现（`NullableAnimatorListenerAdapter.java:16-17`），lib Adapter cancel 直接置位（`:19-21`）——lib 是原厂语义的超集，对 SuccessListener 路径无影响。

### 遗漏（原厂有、lib 没有）

1. **`startWithVelocity` 弹簧沉降全链路**（原厂 `AnimatorPlaybackController.java:382-461`）：抬手带初速度时，按 `Holder.springProperty.flags`（`SpringProperty.java:6-7` 的 `FLAG_CAN_SPRING_ON_END/START`）逐 Holder 用 `SpringAnimationBuilder` 算弹簧参数、替换 Holder 的 mapper/插值器，主时钟时长取弹簧时长与预期时长的 max 并用 `clampToProgress` 截断（`:452-458`）。配套 `SpringProperty` 类（`SpringProperty.java:4-53`，阻尼 0.5/刚度 1500 默认值）整体未移植；lib `Holder.springProperty` 恒为 null（`AnimatorPlaybackController.kt:79`），`PendingAnimation.add(child, ip, springProperty)` 第三参直接丢弃（`PendingAnimation.kt:53-54`）。
2. **PropertySetter 的 View 侧方法**：`setViewAlpha`（带 `AlphaUpdateListener.updateVisibility` 可见性联动，原厂 `PropertySetter.java:38-43` / `PendingAnimation.java:164-172,218-234`）、`setInt`（`:30-35`）、`setViewBackgroundColor`（`:45-49`）、`add(Animator)` 默认实现（setDuration(0)+start，`:14-20`）。lib 只有 `setFloat`。
3. **PendingAnimation 的 startDelay/时长覆盖变体**：`setFloat(..., delay, duration)`（`PendingAnimation.java:198-209`）、`setViewAlpha(..., delay, duration)`（`:218-234`）、`add(animator, duration)`（`:211-214`）、`setFloats`（多值，`:137-144`）。
4. **APC 的辅助方法**：`getInterpolatedProgress`/`getInterpolator`（原厂 `:279-285`）、`overrideDurationScale`（`:311-313`）、`dispatchSetInterpolator`（`:252-254`）、`iterateAllChildAnim`（`:299-301`）、`resetPropertySetters`（`:342-346`）、`getTarget`/`getDuration`/`isIsDispatchStartPending`（`:275-297`）、OPPO 定制 `removeScaleAnimatorForGridRecentViews`（`:322-340`）。
5. **Interpolators 几乎全部常量与工具函数**：原厂约 40 个插值器（`Interpolators.java:33-79,87-130`）+ `clampToProgress`（`:134-148,196-214`）、`mapToProgress`（`:170-177`）、`reverse`（`:183-190`）、`scrollInterpolatorForVelocity`（`:192-194`）、`overshootInterpolatorForVelocity`（`:179-181`）、ZOOM_IN/OUT 焦长模型（`:100-117`）、OPPO 的 COUI 插值器（`:69,90,97`）。lib 只有 `LINEAR` 和一个公式与原厂任何常量都对不上的 `DECELERATE`（`Interpolators.kt:13-16`；原厂只有 `DEACCEL*`=DecelerateInterpolator 系，`Interpolators.java:40-50`）。
6. **`addAnimationHoldersRecur` 的两个行为**（原厂 `:161-182`）：递归时把 AnimatorSet 的 duration/interpolator 下发给子动画（`:174-179`）；遇到非 ValueAnimator/非 AnimatorSet 类型抛 `RuntimeException`（`:168-169`）。lib `addHoldersRecur` 两者皆无，静默跳过（`AnimatorPlaybackController.kt:186-191`）。
7. **dispatch 递归**：原厂 `callListenerCommandRecursively` 递归进嵌套 AnimatorSet（`:184-203`）；lib `dispatchToListeners` 只拍平一层（`AnimatorPlaybackController.kt:161-165`）。

---

## ③ 行为差异风险点（按严重度排序）

1. **`forEndCallback(success)` 的 success 判定不同（高风险）**。原厂 `EndStateCallbackWrapper.onAnimationEnd` 会检查 `animatedFraction <= 0.5f` → 报 `false`（`AnimatorListeners.java:34-40`）：手势抬手后动画"自然播完但停在后半程之前"被视为**未成功**。lib `AnimatorListeners.kt:29` 的 `onAnimationEnd = fire(true)` 无条件报成功。凡依赖 success 决定"落到目标态还是回退"的业务（手势转场收尾是典型场景），lib 会得到相反的结论。
2. **`PendingAnimation.setFloat` 不动画（高风险）**。原厂 `PendingAnimation.setFloat` 重写的是**构造 ObjectAnimator** 的动画版（`PendingAnimation.java:127-134`，含 null/等值短路）；lib 的 override 是直接 `property.setValue`（`PendingAnimation.kt:68-70`）——即原厂 `NO_ANIM_PROPERTY_SETTER` 的 no-op 语义。lib 文档注释自称"实现 PropertySetter 让代码在动画 vs no-op 间无缝切换"，但实际两条路径行为相同（都是瞬时值），`PendingAnimation` 与 no-op setter 不可互换。如果调用方按原厂习惯用 `setFloat` 声明动画，lib 里属性会瞬跳。
3. **lib 自制 ObjectAnimator 的产物进不了 Holder 链（高风险，潜伏）**。`addFloat` 走 lib 内嵌 `ObjectAnimator.buildAnimator()`，返回的是匿名 `Animator`（**非 ValueAnimator**，`PendingAnimation.kt:135-154`）；`addHoldersRecur` 的 `when` 只认 ValueAnimator/AnimatorSet（`AnimatorPlaybackController.kt:186-191`），该动画被**静默丢弃**，不进 `animHolders`。后果：`createPlaybackController()` 得到的 APC 不会驱动这些属性动画——`setPlayFraction`/跟手进度对 addFloat 添加的属性完全无效。原厂此时会抛 RuntimeException 暴露问题（`AnimatorPlaybackController.java:168-169`），lib 静默通过，属于"失败不可见"。（注：若该动画真进了 Holder，`:75` 的 `as ValueAnimator` 强转会直接 ClassCastException。）
4. **progressAnimator 时长不一致（中风险）**。原厂 `buildAnim` 用 `add(valueAnimator)` 把 progressAnimator 时长覆写为 `mDuration`（`PendingAnimation.java:96 → :191-193`）；lib 用 `addWithoutDuration`（`PendingAnimation.kt:86`），progressAnimator 保持 ValueAnimator 默认 300ms。转场时长 ≠300ms 时：短转场中 AnimatorSet 被拖到 300ms（end/frame 回调晚发），长转场中 onFrame 回调提前停止。
5. **cancel 跟踪挂在错误对象上（中风险）**。原厂 listener 挂 **AnimatorSet 本体**（`AnimatorPlaybackController.java:135`），且同时维护 `mIsDispatchStartPending`；lib 挂在 `anims[0]`（**第一个子动画**，`AnimatorPlaybackController.kt:57`）。直接 cancel AnimatorSet（而非子动画）时，原厂能置 `mTargetCancelled` 阻断后续 `setPlayFraction`，lib 不能——`setPlayFraction` 会继续往已取消的子动画写进度。
6. **`isDispatchStartPending` 语义反转（低风险，lib 内为死字段）**。原厂 `start()` 置 **false**（`:379`），仅 `dispatchOnStart()` 置 true（`:248`）；lib `start()` 置 **true**（`AnimatorPlaybackController.kt:110`）。lib 侧该字段 private 且无任何读取点，当前无实际影响；但一旦补 `isIsDispatchStartPending()` getter（手势链路需要），语义就是反的。
7. **dispatch 不递归嵌套 AnimatorSet（低风险）**：见 ②-遗漏 7。lib 当前构造路径不产嵌套 set，暂无触发面。
8. **`DECELERATE` 公式无原厂对应（低风险）**：`Interpolators.kt:13-16` 的公式（`t+1` 三次多项式/2）既不等于 `DecelerateInterpolator` 也不等于 `OvershootInterpolator`，名字还容易和原厂 `DEACCEL` 混淆。当前仅 lib 自用。

---

## ④ 回移建议

### 值得补进 lib 的

1. **修 `forEndCallback` 的 success 判定**（对应风险 1）：end 时按 `animatedFraction <= 0.5f` 报 false。一行改动，直接消除手势收尾语义分歧；这是原厂"播完≠成功"的关键设计。
2. **修 `PendingAnimation.setFloat` 为动画版**（对应风险 2）：照原厂 `:127-134` 构造 ObjectAnimator 并 add，含 null/等值短路。否则 PropertySetter 抽象名存实亡。
3. **让 `addFloat` 产物可进 Holder 链 + 恢复类型异常**（对应风险 3）：两个方向任选——(a) 内嵌 ObjectAnimator 改为继承/返回 `ValueAnimator`（用 `ValueAnimator.ofFloat` + update listener 直接即可，`buildAnimator` 的匿名 Animator 没必要存在）；(b) 至少把 `addHoldersRecur` 的 else 分支改成抛异常，把静默失败变成显式失败。推荐 (a)，同时消除 `Holder` 里的强转隐患。
4. **progressAnimator 时长对齐 mDuration**（对应风险 4）：`buildAnim` 里 progressAnimator 走带时长覆写的 `add` 路径，对齐原厂 `:96`。
5. **cancel 跟踪挂到顶层 AnimatorSet 并同步维护 isDispatchStartPending**（对应风险 5、6）：照原厂 `:135-156` 把 listener 挂到 set 本体，并把 `start()` 里的标志赋值改为 `false`。
6. **补 `Interpolators.scrollInterpolatorForVelocity` / `clampToProgress` / `mapToProgress` / `reverse`**：这四个是纯数学工具函数（原厂 `:134-214`），无依赖、被手势/弹簧链路广泛引用，是后续移植 `startWithVelocity` 的前置。常量插值器按需补（`FAST_OUT_SLOW_IN` 等几个 PathInterpolator 一行一个）。

### 建议保持简化的

1. **`startWithVelocity` + `SpringProperty` 暂不回移**：它是手势抬手弹簧沉降入口，依赖 `SpringAnimationBuilder`（同属未移植区域）和 `RefreshRateTracker`，且只有在 lib 引入"手势跟手 + 带速度抬手"场景后才有调用方。但建议**现在就把 `springProperty` 参数从 `PendingAnimation.add` 签名里删掉或落实**——留着又丢弃是 API 说谎（`PendingAnimation.kt:53-54`）。
2. **`removeScaleAnimatorForGridRecentViews`**（原厂 `:322-340`）：OPPO 为 grid  recents 多行布局打的业务补丁，引用 `OplusTaskAnimationBuilderMutliRowImpl`，与演示库定位无关。
3. **View 侧 PropertySetter 方法（setViewAlpha/setViewBackgroundColor/setInt）**：依赖 `AlphaUpdateListener` 的可见性联动逻辑，lib 当前演示场景用不到；需要时连同 AlphaUpdateListener 一起移植（它们是成套语义：alpha=0 时同步 INVISIBLE）。
4. **ActualEndAnimListener 独立分层**：原厂该层只有空钩子（`ActualEndAnimListener.java:10`），lib 合并进 AnimationSuccessListener 的处理合理，无需为形式对齐再拆一层。
5. **40 个插值器常量全量回移**：大多是各业务页的配置值（COUI 插值器甚至依赖 `com.coui` 库），演示库用到哪个补哪个即可。
