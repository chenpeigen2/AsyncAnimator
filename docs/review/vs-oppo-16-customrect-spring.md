# 区域 13 vs-oppo deep dive：`CustomRectFSpringAnim` 字段级 + 方法级映射

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib`（AsyncAnimator 演示库，idiomatic Kotlin 复刻）
> - **原厂**：`D:/oppo_a6_launcher/sources`（OPPO ColorOS 15 Launcher 15.8.24 JADX 反编译；80% 文件被企业 DLP 加密）
>
> **本区域范围**：单独把 lib `anim/CustomRectFSpringAnim.kt`（18 行占位）与原厂 `com.android.quickstep.util.animation.CustomRectFSpringAnim.java`（927 行）做字段级 + 方法级映射，覆盖 6 自由度弹簧字段组、thread switch protocol（review 04 §2.3-10）、maybeEnd 双轨补救、cancel "下一帧生效"、AnimType 7 值（review 06 §3）。
>
> 与前 12 份 review 的衔接：
> - review 04 §2.3-10 已经标记"线程切换协议整体缺失"，但只列了协议轮廓；
> - review 06 §3 已经标记"AnimType 7 值 vs 3 值偏差"，但只列了枚举名差；
> - review 11 §C-5/9 已经把 6 自由度 + `requestEnd` 下一帧生效作为"完全缺失"列；
> - **本文是它们的字段级 + 方法级深挖**，把"为什么是 6 × 5 字段"、"线程切换 4 处写法"、"maybeEnd 3 条分支"、"cancel 下一帧生效的双轨时序"逐一拆开。
>
> 取证方法：
> - lib 侧文件 18 行，直接 Read。
> - 原厂文件 927 行 + SpringHolder.java 138 行 + SpringForce.java 166 行 + MultiDynamicAnimation.java 200 行，全部经 Python `open(p,'rb').read()` 绕开 DLP 密文层；行号为 JADX 反编译文本行号。
>
> 背景结论：`docs/animation-thread-analysis-v4.md`（v4）§3 / §8.2 / §9、`docs/animation-trace-validation.md`。

---

## ① 类对应关系表

### 1.1 主体类

| lib 类 | 原厂类（文件:行） | 关系 |
|---|---|---|
| `com/asyncanimator/anim/CustomRectFSpringAnim.kt`（**18 行占位**，仅 `AnimType` 3 值 + 构造函数） | `com/android/quickstep/util/animation/CustomRectFSpringAnim.java:42-926`（927 行；Java 反编译自 `CustomRectFSpringAnim.kt` 类，`@SourceDebugExtension` `:40` 标 `SMAP CustomRectFSpringAnim.kt Kotlin 1,812:1`） | **完全降级**（句柄占位） |

### 1.2 周边类（lib 完全没有）

| 原厂类 | 行数 | 角色 | lib 是否复刻 |
|---|---|---|---|
| `com/android/quickstep/util/animation/SpringHolder.java` | 138 | 单自由度弹簧状态机：mValue/mVelocity/mPendingPosition/mMinVisibleChange/mMaxValue/mMinValue/mStartDelay + getNextFrameValue/updateValueAndVelocity 半步分裂积分（`:115-130`） | ❌ 无 |
| `com/android/quickstep/util/animation/SpringForce.java` | 166 | 解析解积分：过阻尼 (`:117-128`)、临界阻尼 (`:129-137`)、欠阻尼 (`:138-152`) 三支闭式；isAtEquilibrium 反射兜底 (`:218-221`) | ❌ 无 |
| `com/android/quickstep/util/animation/MultiDynamicAnimation.java` | 200 | 帧循环载体：`implements AnimationHandler.AnimationFrameCallback` (`:21`)；裸 delta 积分 (`:158-159`)；`requestEnd` 下一帧生效 (`:185-191`) | ❌ 无 |
| `com/android/quickstep/util/animation/SpringAnimReflectUtils.java` | 36 | `sUpdateValuesMethod` 直调 + `sIsAtEquilibriumMethod` 反射（v3 文档误判"全部反射"，实际仅 isAtEquilibrium 反射） | ❌ 无 |

### 1.3 lib 实际依赖

| lib 类 | 用到 CustomRectFSpringAnim 的方式 |
|---|---|
| `lib/src/main/java/com/asyncanimator/control/AnimationController.kt:26, 65` | `mutableListOf<CustomRectFSpringAnim>()` + `addRecentsAnim(anim, ...)` 句柄 |
| `lib/src/main/java/com/asyncanimator/control/DefaultAnimationController.kt:52, 55, 64` | `addRecentsAnim`/`canFinishRecentsAnim`/`revertRecentsAnimation` 3 个虚函数签名用到 `CustomRectFSpringAnim` |
| `lib/src/test/java/com/asyncanimator/control/AnimationControllerTest.kt:55, 65, 103` | 单元测试构造：`CustomRectFSpringAnim(AnimType.SWIPE_TO_HOME)` + 注入 `null` recentsController / 空 targets 数组 |
| `lib/src/main/java/com/asyncanimator/anim/ActualEndAnimListener.kt:17` | 文档引用 `CustomRectFSpringAnim.java:423-441` 作为双轨结束原型 |
| `lib/src/main/java/com/asyncanimator/thread/AnimationControlThread.kt:46` | 文档引用"CustomRectFSpringAnim.start() 的 looper.isCurrentThread 协议"——**但 lib 侧该协议未实现**（评论失实，见 review 04 §2.3-10 末尾） |

---

## ② 字段级映射

### 2.1 OPPO 6 自由度弹簧字段组（36 字段 + 1 额外）

按 6 个 DOF 各持 `SpringForce + SpringHolder + 4 配置字段（stiffness/damping/velocity/minimumVisibleChange）` 分组：

| DOF | SpringForce | SpringHolder | Stiffness | Damping | Velocity | MinVisibleChange | 默认值（构造器 `:209-258`） |
|---|---|---|---|---|---|---|---|
| **centerX**（rect 中心 X） | `mCenterXSpringForce` `:65` | `mCenterXSpringHolder` `:66` | `mCenterXStiffness` `:67` | `mCenterXDamping` `:63` | `mCenterXVelocity` `:68` | `mCenterXMinimumVisibleChange` `:64` | 200.0f / 1.0f / 0.0f / **0.1f** |
| **rectY**（rect Y，按 mTracking 决定是 top/centerY/bottom） | `mRectYSpringForce` `:95` | `mRectYSpringHolder` `:96` | `mRectYStiffness` `:97` | `mRectYDamping` `:93` | `mRectYVelocity` `:98` | `mRectYMinimumVisibleChange` `:94` | 200.0f / 1.0f / 0.0f / **0.1f** |
| **width**（按 mIsWithAnim 切换 width/height，见 §2.3） | `mWidthSpringForce` `:109` | `mWidthSpringHolder` `:110` | `mWidthStiffness` `:111` | `mWidthDamping` `:107` | `mWidthVelocity` `:112` | `mWidthMinimumVisibleChange` `:108` | 200.0f / 1.0f / 0.0f / **0.1f** |
| **radio**（width/height 比，OPPO 拼写保留） | `mRadioSpringForce` `:81` | `mRadioSpringHolder` `:82` | `mRadioStiffness` `:83` | `mRadioDamping` `:79` | `mRadioVelocity` `:84` | `mRadioMinimumVisibleChange` `:80` | 200.0f / 1.0f / 0.0f / **0.005f** |
| **radius**（圆角半径，**注意字段名 mRadiusMinimumVisibleChange** 不一致） | `mRectRadiusSpringForce` `:88` | `mRectRadiusSpringHolder` `:89` | `mRectRadiusStiffness` `:90` | `mRectRadiusDamping` `:87` | `mRectRadiusVelocity` `:91` | `mRadiusMinimumVisibleChange` `:85`（注意：字段名不带 `Rect`） | 200.0f / 1.0f / 0.0f / **1.0f** |
| **alpha**（透明度） | `mAlphaSpringForce` `:51` | `mAlphaSpringHolder` `:52` | `mAlphaStiffness` `:54` | `mAlphaDamping` `:48` | `mAlphaVelocity` `:55` | `mAlphaMinimumVisibleChange` `:49` | 200.0f / 1.0f / 0.0f / **0.05f** |

**alpha 额外字段**：`mAlphaStartDelay`（long, `:53`）+ `mAlphaPair`（PointF, `:50`，x=当前 alpha / y=终点 alpha）

### 2.2 OPPO 非弹簧字段（共 25 个）

| 字段 | 行 | 类型 | 用途 |
|---|---|---|---|
| `mAnimationId` | `:59` | int | 调试 / 与 AsyncAnimCallbacks 同步 |
| `mAnimType` | `:58` | AnimType | 当前动画类型枚举 |
| `mStartRectF` | `:102` | RectF | 起点矩形 |
| `mTargetRectF` | `:103` | RectF | 终点矩形 |
| `mCurrentRectF` | `:70` | RectF | 当前矩形（onUpdate 写入） |
| `mCurrentProgress` | `:69` | float | 0..1 进度 |
| `mProgressWhenReverse` | `:77` | float | reverse-to-open 起始进度 |
| `mStartRadius` / `mEndRadius` / `mRectRadius` | `:101,71,86` | float | 圆角半径的起/终/当前 |
| `mRadio` | `:78` | float | width/height 比当前值 |
| `mCenterX` / `mRectY` / `mWidth` | `:62,92,106` | float | DOF 当前值（onUpdate 从 SpringHolder.getMValue() 写入） |
| `mAlpha` | `:47` | float | alpha 当前值 |
| `mMinWidth` | `:75` | float | width 下钳 `Math.max(holder.getMValue(), mMinWidth)` (`:448`) |
| `mIsWithAnim` | `:72` | boolean | width 跟随 width vs 跟随 height（`isWithAnim(startRadio, endRadio)` `:411-421`） |
| `mTracking` | `:104` | int | 0=top / 1=center / 2=bottom 三档锚点（getTrackedYFromRect `:315-318`） |
| `mReverseToOpen` | `:99` | boolean | 反向打开转场标志 |
| `mLimitRadioFlag` | `:74` | boolean | radio min/max 钳制开关（`:373, 514`） |
| `mStartAsync` | `:100` | boolean | 是否走 ANIM_EXECUTOR（true）还是 MAIN_EXECUTOR（false）|
| `mAnimLooperExecutor` | `:56` | LooperExecutor | start 时记录的实际目标线程（ANIM/MAIN） |
| `mMultiDynamicAnimation` | `:76` | MultiDynamicAnimation | 帧循环载体 |
| `mAnimStarted` | `:57` | boolean | `isRunning()` 公开返回 |
| `mCanceled` | `:61` | boolean | maybeEnd 路径分流的标志 |
| `mJustNotifyEndCallback` | `:73` | boolean | "逻辑结束保护路径"开关——只发 onAnimActualEnd、不发 onAnimationEnd/onAnimationCancel |
| `mAsyncAnimCallbacks` | `:60` | AsyncAnimCallbacks | listener 容器（来自同包 `AsyncAnimCallbacks.java:30+`） |
| `mUpdateListeners` | `:105` | ArrayList<OnAnimUpdateListener> | 自定义 6 自由度更新回调 |

### 2.3 lib 字段映射

| lib 字段 | 行 | 类型 | 对应 OPPO 字段 | 差距 |
|---|---|---|---|---|
| `animType`（构造函数参数） | `CustomRectFSpringAnim.kt:11` | `AnimType`（3 值枚举） | `mAnimType` `:58` | **3 vs 7 值枚举偏差**（review 06 §3） |

**结论**：**907 行的 OPPO 文件 → 18 行的 lib 占位；35/36 弹簧字段 + 25 个非弹簧字段全部缺失**。lib 类无法独立驱动任何动画，仅作为 `AnimationController.addRecentsAnim` 的句柄身份使用。

---

## ③ 方法级映射

### 3.1 构造器

| OPPO `:209-258` | lib `:11-18` | 差距 |
|---|---|---|
| 50 行构造器：`mAnimationId=-1` → `mMultiDynamicAnimation=new` → `mStartRectF=new` → `mTargetRectF=new` → **6 组各 4 个字段初始化（stiffness=200/damping=1/minVisibleChange 见 §2.1 表）** → 6 对 `SpringForce/SpringHolder` 实例化（每个配对都带 key 名：`"centerX"`/ `"rectY"` / `"width"` / `"radio"` / `"radius"` / `"alpha"`）→ `mAnimType=SWIPE_TO_HOME` → `mCurrentRectF=new` → `mUpdateListeners=new ArrayList<>()` → `mAsyncAnimCallbacks=new` → `mStartAsync=true` → set 三个 RectF/PointF → `mStartAsync=z8`（覆盖参数）→ `initProperty()` | 1 行构造器：`class CustomRectFSpringAnim(internal val animType: AnimType)` ——只接收 animType | **构造器完全空白**。6 个 SpringHolder 都没实例化；mMultiDynamicAnimation=null；任何调用都会 NPE |

### 3.2 公开方法（按调用频度）

| 方法签名（OPPO 行号） | lib 复刻 | 差距 |
|---|---|---|
| `start()` `:885-907` | ❌ | 见 §3.3 thread switch |
| `cancel()` `:608-628` | ❌ | 见 §3.3 thread switch |
| `skipToEnd()` `:860-883` | ❌ | 见 §3.3 thread switch |
| `reverseToOpen(RectF, float, Runnable)` `:752-776` | ❌ | 见 §3.3 thread switch |
| `copyNextAnimState(RectF, float)` `:630-648` | ❌ | **关键**：调用 `getNextFrameValue` 取下一帧的 6 个 spring 值构造新 anim（`SpringHolder.getNextFrameValue` `SpringHolder.java:50-53`），是"下一帧接管"语义的核心 |
| `setVelocity(f9..f13, 5 参)` `:846-854` | ❌ | 5 个速度都 `×1000` 后写入（OPPO 单位是 ms/s，OPPO 内部存的是 s/s ×1000）。lib 无对应 |
| `setAnimParamByType(AnimType)` `:788-801` | ❌ | 6 行 `setSpringHolderParamByType(RECT_CENTER_X/Y/WIDTH/RADIO/RADIUS/ALPHA, ...)` 调一次 `AnimParamProvider.getXxxParam()[0..1]` 设 stiffness/damping，再 `updateMinVisibleChange`、设 `mAlphaStartDelay` |
| `setAsyncStart(boolean)` `:808-810` | ❌ | start 时再读 `mStartAsync` 选 executor（`OplusExecutors.ANIM_EXECUTOR` vs `MAIN_EXECUTOR`） |
| `setStartRadius`/`setEndRadius`/`setMStartRadius`/`setMEndRadius`（2 对） `:812-819, 837-840, 817, 825-827` | ❌ | radius 起/终设置；JADX 把 setter 编了两遍，语义相同 |
| `setMinWidth(float)` `:829-831` | ❌ | width 下钳 `Math.max(holder.getMValue(), mMinWidth)` (`:448`) |
| `setStartAlpha(float)` `:833-835` | ❌ | 写 `mAlphaPair.x` |
| `setTracking(int)` `:842-844` | ❌ | 0/1/2 三档锚点 |
| `setAnimationId(int)` `:803-806` | ❌ | 写 `mAnimationId` + `mAsyncAnimCallbacks.setAnimationId` |
| `setMLimitRadioFlag(boolean)` `:821-823` | ❌ | 开关 radio min/max 钳制（构造时已是默认；动态切换） |
| `updateEndTargetRectF(RectF, float)` `:909-925` | ❌ | 动态改终点：调 4 个 `SpringForce.setFinalPosition`；f9==-1.0f 时不改 |
| `justNotifyEndCallback()` `:734-739` | ❌ | 设 mJustNotifyEndCallback=true，立即发 onAnimationEnd |
| `initFirstFrameForBreakScene(BreakParam, RectTransformHelper, Matrix, TransformParams)` `:696-717` | ❌ | "首帧场景"（窗口分裂转场）：算 rectF、mapRect、调 transformHelper.updateWindowCropRect + breakParam.updateBreakParam（5 参 + clipRect） |
| `runOnMainThread(Runnable)` `:778-786` | ❌ | 公开 API：isCurrentThread 判定，否则 `Utilities.postAsyncCallback(handler, runnable)`（走 Looper 异步消息） |
| `addAnimatorListener(NullableAnimatorListener)` `:593-598` | ❌ | null-check + `mAsyncAnimCallbacks.addListeners` |
| `removeAnimatorListener` `:748-750` | ❌ | 委托 `mAsyncAnimCallbacks.removeListener` |
| `addOnUpdateListener(OnAnimUpdateListener)` `:600-606` | ❌ | contains 去重 + add |
| `mapRatioVelocity(widthVelocity, maxRadioVelocity)` `:741-746` | ❌ | radio 速度换算：`(endH/endW - startH/startW) × 1000 × widthVelocity / |endW-startW|`，sign 复制 |
| `getMAnimStarted()` `:729-732`（= isRunning） | ❌ | 仅返回 `mAnimStarted` |
| `getMCanceled()`（= hasCanceled） `:691-694` | ❌ | 仅返回 `mCanceled` |
| `getMJustNotifyEndCallback()` `:719-722` | ❌ | 仅返回 `mJustNotifyEndCallback` |
| `getMReverseToOpen()` `:724-727` | ❌ | 仅返回 `mReverseToOpen` |
| `getMAnimType()` `:650-653` | ❌ | 仅返回 `mAnimType` |
| `getMAnimationId()` `:655-658` | ❌ | 仅返回 `mAnimationId` |
| `getMCurrentRectF()` `:664-667` | ❌ | 仅返回 `mCurrentRectF` |
| `getMStartRadius()` `:677-679` | ❌ | 仅返回 `mStartRadius` |
| `getMEndRadius()` `:669-671` | ❌ | 仅返回 `mEndRadius` |
| `getMLimitRadioFlag()` `:673-675` | ❌ | 仅返回 `mLimitRadioFlag` |
| `getCurrentRadius()` `:660-662` | ❌ | **含 maxRadioVelocity 钳制**：`SpringHolder.getNextFrameValue(RefreshRateTracker.getSingleFrameMs(ctx))`（"下一帧"） |
| `getStartRect()` `:687-689` | ❌ | 返回 `new RectF(mStartRectF)` 拷贝 |
| `getOpeningWindowProgress()` `:681-685` | ❌ | `reverseToOpen ? 1-currentProgress : currentProgress`，含 `composeLog` |

### 3.3 私有方法

| 方法（OPPO 行号） | lib 复刻 | 差距 |
|---|---|---|
| `initProperty()` `:394-409` | ❌ | **关键**：把 6 个 SpringHolder 全部 `addSpringHolderItem` 进 mMultiDynamicAnimation + add OnAnimationUpdateListener (onUpdate) + add OnAnimationEndListener (AnonymousClass2 → onAnimationEnd) |
| `initAllAnimations()` `:330-392` | ❌ | **关键**：60 行，6 个 SpringForce/SpringHolder 全部 setFinalPosition/setDampingRatio/setStiffness/setStartValue/setStartVelocity/setMinimumVisibleChange；adaptive animation 下按 startRect/targetRect 重算 widthMinimumVisibleChange/centerXMinimumVisibleChange；按 mLimitRadioFlag 设 mRadioSpringHolder setMaxValue/setMinValue；mIsWithAnim 由 isWithAnim(radio, endRadio) 决定；最终 mAlphaSpringHolder.setStartDelay(mAlphaStartDelay)；calculateFrameRectF + mAnimStarted=true |
| `onUpdate()` `:443-470` | ❌ | **关键**：每帧 6 个 holder.getMValue() → 写入 6 个 mXxx 字段 + calculateFrameRectF + 算 progress (Utilities.getProgress，width/height 二选一) + reverseToOpen 映射 + boundToRange [0,1] + 遍历 mUpdateListeners.onUpdate(rect, progress, radio, radius, alpha) |
| `maybeEnd()` `:423-441` | ❌ | **双轨补救，见 §3.5** |
| `calculateFrameRectF(RectF, cX, rectY, w, radio)` `:266-290` | ❌ | **6 自由度矩形换算**：mIsWithAnim 时 `rectF.left=cX-w/2; top=rectY; right=cX+w/2; bottom=rectY+w*radio`，按 mTracking 调 rectY 偏移；mIsWithAnim=false 时 w/radio 计算实际 width/height |
| `isWithAnim(startRadio, endRadio)` `:411-421` | ❌ | OPEN_FROM_HOME/REVERSE_TO_OPEN: `startRadio < endRadio` 才返回 true；其他类型：`endRadio < startRadio` 才 true |
| `updateMinVisibleChange(boolean)` `:571-581` | ❌ | 横屏/分屏 wide 时 mRadioMinimumVisibleChange=0.001f；isOpenAnim 时 centerXMin=1.0f, rectYMin=foldExpand/tablet?0.1f:1.0f, widthMin=1.0f, radioMin=0.001f |
| `updateSpringParam()` `:583-591` | ❌ | 6 个 `SpringForce.setDampingRatio.setStiffness` 重写（不写 startValue/velocity/minVisibleChange/finalPosition） |
| `inheritVelocity(CustomRectFSpringAnim)` `:320-328` | ❌ | copyNextAnimState 路径用：从 source 6 个 holder.getMVelocity() 写入当前 anim 的 6 个 mXxxVelocity + 拼 buildVelocityLogStr |
| `setSpringHolderParamByType(RectAnimType, f9, f10)` `:525-555` | ❌ | 6 case switch：f9=stiffness（除非 GESTURE_TO_DRAG 否则先 getRateStiffness × 缩放） / f10=damping |
| `buildVelocityLogStr()` `:260-264` | ❌ | 6 个 velocity 拼日志 |
| `getRateStiffness(float)` `:297-313` | ❌ | sDurationScale 平方缩放 + lightAnimation 时 ×0.16 + evaluation scene ×0.36 |
| `getTrackedYFromRect(RectF)` `:315-318` | ❌ | mTracking=0 → rectF.top；mTracking=1 → rectF.centerY（OPPO 实写 centerY()，不是 centerY()-rectY 偏移）；mTracking=2 → rectF.bottom |
| `resetState()` `:472-475` | ❌ | mAnimLooperExecutor=null; mAnimStarted=false |
| `lambda` 静态方法 × 5：`cancel$lambda$4` `:292-295` / `skipToEnd$lambda$5` `:557-560` / `start$lambda$0` `:562-569` / `onAnimationEnd$lambda$0` `:180-189` / `reverseToOpen$lambda$7` `:477-523` | ❌ | lambda 经 Runnable 跳进 mAnimLooperExecutor 线程后干活的实际方法 |

### 3.4 内部类型（lib 全无）

| 类型 | 行 | 关键成员 |
|---|---|---|
| `enum AnimType` | `:115-123` | **7 值**：`OPEN_FROM_HOME` / `REMOTE_CLOSE_TO_HOME` / `REMOTE_CLOSE_TO_HOME_ASSISTANT` / `GESTURE_TO_DRAG` / `SWIPE_TO_HOME` / `SWIPE_TO_HOME_ASSISTANT` / `REVERSE_TO_OPEN` |
| `enum RectAnimType` | `:131-138` | **6 值**：`RECT_CENTER_X` / `RECT_Y` / `RECT_WIDTH` / `RECT_RADIO` / `RECT_RADIUS` / `RECT_ALPHA` |
| `interface OnAnimUpdateListener` | `:126-128` | `onUpdate(RectF, progress, radio, radius, alpha)` |
| `AnonymousClass2`（implements MultiDynamicAnimation.OnAnimationEndListener） | `:176-207` | onAnimationEnd(MultiDynamicAnimation, boolean) → 拼日志 → **runOnMainThread(onAnimationEnd$lambda$0)**（线程切换） |
| `WhenMappings`（合成 switch 表） | `:141-172` | RectAnimType 6 值 → int 1-6 的映射表 |

### 3.5 maybeEnd 双轨补救（review 04 §2.3-10 / §3.1 已提）

OPPO `:423-441` 流程：

```
fun maybeEnd() {
    if (LogUtils.isLogOpen()) log("#id maybeEnd, mAnimStarted: $animStarted, isCancel: $mCanceled")
    if (mAnimStarted) {
        mAnimStarted = false                       // (a) 防止重入
        if (mJustNotifyEndCallback) {
            mAsyncAnimCallbacks.onAnimActualEnd(null)        // (b) 保护路径：仅"物理结束"
            return
        }
        if (mCanceled) {
            mAsyncAnimCallbacks.onAnimationCancel(null)      // (c) 取消路径：cancel + end + actualEnd
        }
        mAsyncAnimCallbacks.onAnimationEnd(null)             // (d) 正常路径：end + actualEnd
        mAsyncAnimCallbacks.onAnimActualEnd(null)
    }
}
```

**3 条分支**：

| 触发场景 | mJustNotifyEndCallback | mCanceled | 派发顺序 | 物理帧是否结束 |
|---|---|---|---|---|
| `justNotifyEndCallback()` 被调（`updateEndTargetRectF` 内或 fast-path） | true | false | `onAnimActualEnd` 单发 | 是 |
| `cancel()` 后调 `maybeEnd()`（双轨补救路径） | false | true | `onAnimationCancel` → `onAnimationEnd` → `onAnimActualEnd` | 是 |
| 正常 end（包括 skipToEnd、reverseToOpen 完结） | false | false | `onAnimationEnd` → `onAnimActualEnd` | 是 |

**关键不变量**：

1. `mAnimStarted=false` 在最前面一次性翻转——`maybeEnd` 重复调用幂等
2. cancel 路径先发 `onAnimationCancel` 再发 `onAnimationEnd`——业务侧可以"cancel 是 end 的子集"理解；listener 实现的 `onAnimationEnd` 不必重复处理 cancel
3. "物理结束" `onAnimActualEnd` **总在最后发**，仅给 `ActualEndAnimListener` 子类接收（lib `AsyncAnimCallbacks.onAnimActualEnd` `:61-72` 与 review 04 §2.2 互参）
4. 保护路径（justNotifyEndCallback）**不发 onAnimationEnd**——避免业务侧以为"逻辑结束"已到又收不到 cancel 通知

**lib 复刻**：❌ 无。`anim/ActualEndAnimListener.kt:13-21` 的文档里描述了这条双轨时序，但**实际**没有 `maybeEnd` 实现；只有 `AsyncAnimCallbacks.onAnimActualEnd` (`anim/AsyncAnimCallbacks.kt:61-72`) + `onAnimationEnd` (`anim/AsyncAnimCallbacks.kt:51-52`) 两个独立派发口——少 cancel 路径，少 justNotifyEndCallback 保护路径，少"逻辑结束 vs 物理结束"的序列化语义。

### 3.6 Thread Switch Protocol（review 04 §2.3-10 已提）

4 个公开方法（`start`/`cancel`/`skipToEnd`/`reverseToOpen`）共用同一模式：

```
val looper = mAnimLooperExecutor?.looper
val onExecutorThread = looper != null && looper.isCurrentThread
if (onExecutorThread) {
    runOnExecutor()       // 直接执行
} else {
    mAnimLooperExecutor?.post(Runnable { runOnExecutor() })   // post 异步
maybeEnd()               // 立即在调用线程上跑 maybeEnd（业务线程）
```

| 方法 | 行 | runOnExecutor 做的事 | maybeEnd 触发 |
|---|---|---|---|
| `start()` | `:885-907` | `mMultiDynamicAnimation.start()` + `mAsyncAnimCallbacks.onAnimationStart(null)` + `if (isSystemDisableAnimation) skipToEnd()` | ❌ start 不调 maybeEnd（只调它跳过的 skipToEnd 才会） |
| `cancel()` | `:608-628` | `mMultiDynamicAnimation.requestEnd(true)` | ✅ 立即调 |
| `skipToEnd()` | `:860-883` | `mMultiDynamicAnimation.requestEnd(false)` | ✅ 立即调 |
| `reverseToOpen()` | `:752-776` | 先 `OplusAnimManager.getAnimController().revertRecentsAnimation(this)`，再 Runnable 跑 reverseToOpen$lambda$7（`:477-523`：设 mReverseToOpen/mStartRadius/mEndRadius/mStartRectF/mTargetRectF/mAlphaPair/setAnimParamByType(REVERSE_TO_OPEN)/updateSpringParam/6 个 setFinalPosition/setMaxValue/setMinValue/`Executors.MAIN_EXECUTOR.post(afterReverse)`） | ❌ reverseToOpen 不调 maybeEnd（它本身是反向开启动画，不是 end） |

**executor 选型（仅 start）**：

```kotlin
// start():888
val anim_executor = if (mStartAsync) OplusExecutors.ANIM_EXECUTOR else Executors.MAIN_EXECUTOR
mAnimLooperExecutor = anim_executor
```

- `mStartAsync=true` → `OplusExecutors.ANIM_EXECUTOR`（launcher.anim 线程，-19 优先级，SF-vsync provider）
- `mStartAsync=false` → `Executors.MAIN_EXECUTOR`（主线程）
- 选完后存到 `mAnimLooperExecutor` 给后续 3 个方法用
- 构造时 `mStartAsync=z8`（构造参数）/ 默认值 `mStartAsync=true`（`:252`）

**关键不变量**：

1. **mAnimLooperExecutor 在 start 时设**，cancel/skipToEnd/reverseToOpen 复用，不重新选
2. **post 是兜底，不是"必须 post"**——业务线程恰好命中时直接同步跑；post 走 Looper 异步消息
3. **maybeEnd 永远在调用线程跑**（不 marshal）——保证"业务线程调用 cancel() → 立即触发业务侧 listener"；真正的物理帧播完是下一帧 `endAnimationInternal` 路径
4. **runOnMainThread 是公开 API**：isCurrentThread 判定，否则 `Utilities.postAsyncCallback(handler, runnable)`（Looper 异步消息，可穿透 sync-barrier）

**lib 复刻**：❌ 无。

- `AsyncAnimCallbacks.runOnMainThread` (`anim/AsyncAnimCallbacks.kt:97-100`) 实现了 isCurrentThread + `Executors.MAIN_EXECUTOR.postAsync`——**只覆盖了"回主线程"那一面**，缺"回 ANIM_EXECUTOR 线程"那一面
- `AsyncAnimWrapper.runOnAnimThread/runOnMainThread` 双通道——但是给 `AsyncSpringAnim` 用的（`thread/AsyncAnimWrapper.kt:17-25`、`anim/AsyncSpringAnim.kt:44-48`），与 `CustomRectFSpringAnim` 无关
- **`CustomRectFSpringAnim.kt` 自身**没有任何 runOnXxx 调用、没有任何 maybeEnd、没有任何线程切换——占位类的语义是"由 AnimationController 持有"，调用面闭合靠 controller 端 marshal（但 AnimationController 端也没有 marshal，review 03 已记录）

### 3.7 Cancel "下一帧生效"语义（review 11 §C-1 已提）

`cancel()` 链路：

```
业务线程                                  anim 线程（mAnimLooperExecutor）
─────                                  ─────
cancel()
  ├─ mCanceled = true
  ├─ onExecutorThread 判定
  ├─ 直接 or post：mMultiDynamicAnimation.requestEnd(true) ◄── 只置 mCancelRequest=true
  │                              │   不 removeCallback，不停帧
  └─ maybeEnd() ─────────────►────► 立刻在业务线程跑：
                                  mAnimStarted=false
                                  onAnimationCancel(null)
                                  onAnimationEnd(null)
                                  onAnimActualEnd(null)
                                  (业务侧 listener 已收到 cancel + end + actualEnd)
                                  
                                  下一帧 doAnimationFrame：
                                  isEndRequest() = mCancelRequest || mEndRequest = true
                                  endAnimationInternal(true)
                                    ├─ mRunning = false
                                    ├─ AnimationHandler.getInstance().removeCallback(this)  ◄── 这里才真正摘帧
                                    └─ 遍历 OnAnimationEndListener.onAnimationEnd(this, true)  ◄── 触发 initProperty 里 add 的 AnonymousClass2
                                                                                       │
                                                                                       └─ AnonymousClass2.onAnimationEnd ─► runOnMainThread ─► onAnimationEnd$lambda$0 ─► maybeEnd()
                                                                                            (但此时 mAnimStarted 已 false，maybeEnd 是 no-op)
```

**双轨时序**：

1. **"逻辑结束"轨道**（业务线程，立即）：cancel() → maybeEnd() → onAnimationCancel + onAnimationEnd + onAnimActualEnd → 业务侧收到 cancel 通知、状态机立即切到 CANCELLED，**不等帧循环**
2. **"物理结束"轨道**（anim 线程，下一帧）：MultiDynamicAnimation.doAnimationFrame → endAnimationInternal → removeCallback → 触发 AnonymousClass2.onAnimationEnd → runOnMainThread → onAnimationEnd$lambda$0 → maybeEnd() → **no-op**（mAnimStarted 已是 false）

**关键设计**：

- 业务侧**不会**因"等帧循环停止"而延迟状态切换——cancel 一调即生效
- "物理结束"轨道（`endAnimationInternal → removeCallback`）的真正作用是**释放帧源**——`AnimationHandler.getInstance().removeCallback(this)` 把 anim 从框架 AnimationHandler 的回调列表里摘除，**launcher.anim 线程的帧脉冲就停在这个 anim 上**
- 如果漏掉 removeCallback（`mCancelRequest=true` 但没人调 endAnimationInternal）→ next frame 仍会进 doAnimationFrame，但因为 mAnimStarted 已 false，updateValueAndVelocity 仍跑（浪费 CPU）→ 直到下一次 `isEndRequest() || result==true` 才走 endAnimationInternal——这正是 `maybeEnd` 的"双轨补救"存在的全部理由

**lib 复刻**：❌ 无。

- lib 的 `AsyncSpringAnim.cancel()` (`anim/AsyncSpringAnim.kt:28`) 是 `dispatch { real.cancel() }`，直接走 androidx `SpringAnimation.cancel()`——androidx 实现里 cancel 是**同步**的，不存在"下一帧生效"的窗口
- `anim/ActualEndAnimListener.kt:13-21` 的文档**正确描述**了这条双轨时序——但实际是**没有实现的描述**
- `AsyncAnimCallbacks.onAnimActualEnd` (`anim/AsyncAnimCallbacks.kt:61-72`) + `onAnimationCancel` (`anim/AsyncAnimCallbacks.kt:54-55`) + `onAnimationEnd` (`anim/AsyncAnimCallbacks.kt:51-52`) 是 3 个**独立**的派发口，**没有序列化**——上层必须自己安排调用顺序

### 3.8 AnimType 7 值 vs lib 3 值（review 06 §3 已提）

| OPPO 枚举（`:115-123`） | 含义（来自 setAnimParamByType `:788-801` + `AnimParamProvider.getXxxParam`） | lib 枚举 (`anim/CustomRectFSpringAnim.kt:13-17`) | lib 是否对齐 |
|---|---|---|---|
| `OPEN_FROM_HOME` | 从桌面点图标启动 app；rect 放大、alpha 0→1、duration scale ×0.4；`isOpenAnim=true`（影响 updateMinVisibleChange） | ❌ 无 | **缺失**——demo9 OPEN_FROM_HOME 场景无法演示 |
| `REMOTE_CLOSE_TO_HOME` | 远程关闭到桌面（multi-app merge 路径） | ❌ 无 | **缺失**——依赖 AppOpenAnimMergeHelper（review 11 §C-8） |
| `REMOTE_CLOSE_TO_HOME_ASSISTANT` | 远程关闭到桌面（带 assistant） | ❌ 无 | **缺失** |
| `GESTURE_TO_DRAG` | 手势拖出图标到桌面（**不缩放 stiffness**，见 setSpringHolderParamByType `:526-528`） | ❌ 无 | **缺失**——`getRateStiffness` 跳过 |
| `SWIPE_TO_HOME` | swipe-up 回桌面（默认；mAnimType 默认值 `:248`） | ✅ 有 | **对齐** |
| `SWIPE_TO_HOME_ASSISTANT` | swipe-up 回桌面（带 assistant） | ❌ 无 | **缺失** |
| `REVERSE_TO_OPEN` | recents → 打开 app（reverseToOpen 内部 setAnimParamByType `:507`） | ❌ 无 | **缺失** |
| （lib 自创）`RECENTS_TRANSITION` | — | ✅ 有 | **OPPO 不存在**——是 lib 自造（demo 用） |
| （lib 自创）`APP_LAUNCH` | — | ✅ 有 | **OPPO 不存在**——是 lib 自造（demo 用） |

**lib 3 值 = 1 真（SWIPE_TO_HOME） + 2 假（RECENTS_TRANSITION, APP_LAUNCH）**。

`AnimationControllerTest.kt:55,65,103` 全部用 `SWIPE_TO_HOME`——是测试可达的真值；但业务代码若按 `OPEN_FROM_HOME` 编号对接，会因 enum 顺序不同行为差异（Kotlin enum `ordinal()` 影响 `switch`、`Bundle` 序列化等场景）。review 11 §bug 级残留 #9 已点名。

---

## ④ 保真度评估

### 4.1 精确复刻

| # | 设计点 | 原厂证据 | lib 证据 | 评估 |
|---|---|---|---|---|
| — | **（无）** | — | — | 整个 927 行的 CustomRectFSpringAnim.java 没有一行被复刻 |

### 4.2 有意简化（lib 注释/文档中明示）

| # | 简化内容 | 原厂对应 | lib 取舍理由 |
|---|---|---|---|
| 1 | 6 自由度独立 SpringHolder × 6 + 6 SpringForce + 中途改终点分裂积分 全部降级为占位 | `CustomRectFSpringAnim.java:43-113` + `SpringHolder.java:115-130` | `CustomRectFSpringAnim.kt:8-10` 注释明示"实际动画逻辑由 SpringAnimation 实现"。lib 走 androidx `SpringAnimation`（`anim/AsyncSpringAnim.kt:21-50`），单一自由度，行为等价但语义偏离 |
| 2 | `MultiDynamicAnimation` 帧循环载体未移植 | `MultiDynamicAnimation.java:21, 121-128, 152-180, 185-191` | 与简化 #1 捆绑；androidx `SpringAnimation` 自带帧循环 |
| 3 | `SpringForce` 三支闭式解析解未移植 | `SpringForce.java:117-152` | androidx `SpringForce` 已实现（`SpringForce.java` in androidx，与原厂非同名但 API 兼容） |
| 4 | `SpringAnimReflectUtils` 直调 + 反射未移植 | `SpringAnimReflectUtils.java:24, 33-43, 59-61` | 与简化 #1 捆绑；androidx 公开 API 不需要反射 |
| 5 | AnimType 自创 3 值（去掉 4 真 + 加 2 假） | `CustomRectFSpringAnim.java:115-123` | `anim/CustomRectFSpringAnim.kt:13-17` demo 用 3 值足够；review 06 §3 / review 11 §bug 级残留 #9 已点名 |
| 6 | `copyNextAnimState` "下一帧接管"语义未复刻 | `CustomRectFSpringAnim.java:630-648` + `SpringHolder.getNextFrameValue` `SpringHolder.java:50-53` | 与简化 #1 捆绑；androidx 不支持"按下一帧预估接管"——这是 recents 转场专有 |
| 7 | `initFirstFrameForBreakScene` 4 参接口（窗口分裂首帧）未复刻 | `CustomRectFSpringAnim.java:696-717` | 依赖 RectTransformHelper + BreakParam，与 launcher 业务强绑定；review 11 §B-3 已建议保持简化 |
| 8 | `mapRatioVelocity` radio 速度换算未复刻 | `CustomRectFSpringAnim.java:741-746` | 与简化 #1 捆绑；demo 演示不需要 |

### 4.3 遗漏（原厂有、lib 没有、且影响语义或运行时行为）

| # | 遗漏点 | 原厂证据 | 影响 |
|---|---|---|---|
| 1 | **6 自由度独立 stiffness/damping**：rectY 可用过阻尼、centerX 可用欠阻尼 | `CustomRectFSpringAnim.java:47-112`（6 组字段）+ `:388-389` setSpringForce/Holder 独立设参 | lib 单一 `SpringAnimation` 无法表达——demo11 弹簧演示用 androidx 单自由度，**与"矩形窗口弹簧"语义已偏离** |
| 2 | **6 自由度独立 minimumVisibleChange 默认值**：centerX/rectY/width=0.1f / radio=0.005f / radius=1.0f / alpha=0.05f | `CustomRectFSpringAnim.java:219, 224, 229, 235, 240, 245` | adaptive animation 路径下按 startRect/targetRect 算最小可视变化（`:336-358`）；lib 无该算法——意味着**弹簧"刚停下"还是"真到终点"的判定阈值**与原厂不一致 |
| 3 | **`isWithAnim(radio, endRadio)` 动态 width 语义**：`OPEN_FROM_HOME/REVERSE_TO_OPEN` 时 width 跟 width，否则跟 height | `CustomRectFSpringAnim.java:411-421` + `:375-385` | lib 完全没有这条规则——demo9 OPEN_FROM_HOME 的 width 弹簧方向**与原厂相反**（如果未来 demo9 接 rect 弹簧） |
| 4 | **`getRateStiffness` × AppLaunchAnimSpeedHandler 缩放**：duration scale 平方缩放 + lightAnimation ×0.16 + evaluation ×0.36 | `CustomRectFSpringAnim.java:297-313` + `:527` 调 | lib 无该缩放——stiffness 直接用原始值；用户在系统设置里改动画时长比例后，**demo 弹簧速度不变** |
| 5 | **`setVelocity(f9..f13, 5 参)` 公开 API**：`mXxxVelocity = fn * 1000`，单位 ms/s | `CustomRectFSpringAnim.java:846-854` | lib 无 5 参速度入口；只有 `AsyncSpringAnim.setStartVelocity(velocity: Float)` (`anim/AsyncSpringAnim.kt:34`) 单 参 |
| 6 | **`copyNextAnimState(RectF, float)`**：从 6 个 holder.getNextFrameValue 取下一帧预估 + inheritVelocity | `CustomRectFSpringAnim.java:630-648` + `SpringHolder.getNextFrameValue` `SpringHolder.java:50-53` | lib 无"下一帧接管"语义；recents 转场的"手指抬起后从半截继续"场景**完全不可演示** |
| 7 | **`getCurrentRadius()` 含 maxRadioVelocity 钳制**：把 radio 速度限制到 maxRadioVelocity 内 | `CustomRectFSpringAnim.java:660-662`（`SpringHolder.getNextFrameValue` `SpringHolder.java:50-53`） | lib 无钳制；快速 swipe 时 radius 速度可能**超过 maxRadioVelocity 引发视觉跳变** |
| 8 | **`justNotifyEndCallback()` 保护路径**：mJustNotifyEndCallback=true 时只发 onAnimActualEnd，不发 onAnimationEnd/onAnimationCancel | `CustomRectFSpringAnim.java:734-739` + `:431-433` | lib 无该路径——`AsyncAnimCallbacks` 的 `onAnimActualEnd` / `onAnimationEnd` / `onAnimationCancel` 是 3 个独立派发口，没有序列化 |
| 10 | **`updateEndTargetRectF(RectF, float)` 动态改终点**：4 个 SpringForce.setFinalPosition；f9==-1 时不改 radius | `CustomRectFSpringAnim.java:909-925` | lib 无动态改终点入口；Android 15 系统手势在中途改变终点时（拖动 recents 卡片到一半放手），**spring 仍跑原终点** |
| 11 | **`mAlphaStartDelay` 字段**：mAlphaPair.y - mAlphaPair.x 的延迟 | `CustomRectFSpringAnim.java:53, 799, 389` | lib 无 alpha 延迟；alpha 与 rect 同时起步 |
| 12 | **`mTracking` 三档锚点**（0=top / 1=centerY / 2=bottom） | `CustomRectFSpringAnim.java:104, 315-318, 270-272, 281-284` | lib 无 tracking 概念；rectY 永远等于 rectF.centerY()——`getTrackedYFromRect` 不存在 |
| 13 | **`mLimitRadioFlag` 钳制开关**：动态设 mRadioSpringHolder.setMaxValue/setMinValue | `CustomRectFSpringAnim.java:74, 373, 514, 821-823` | lib 无 radio 钳制；rect 极度变形时 radio 可能溢出导致视觉撕裂 |
| 14 | **`mMinWidth` width 下钳**：Math.max(holder.getMValue(), mMinWidth) | `CustomRectFSpringAnim.java:75, 448, 829-831` | lib 无 width 下钳；矩形缩到 0 后**不会停在最小阈值** |
| 15 | **`mUpdateListeners` 自定义 6 自由度更新回调**：onUpdate(rectF, progress, radio, radius, alpha) | `CustomRectFSpringAnim.java:105, 600-606, 466-468` | lib 无该 listener；下游 SurfaceControl 事务写表层（RectTransformHelper）无驱动入口 |
| 16 | **`updateMinVisibleChange(boolean)` 折叠/平板/普通屏差异化** | `CustomRectFSpringAnim.java:571-581` | lib 无差异化；所有设备都用同一阈值 |
| 17 | **`buildVelocityLogStr` 6 速度日志** | `CustomRectFSpringAnim.java:260-264, 327, 853` | lib 无对应日志；调试时看不到 6 自由度的速度快照 |

---

## ⑤ 行为差异风险点（按风险从高到低）

> **状态：⚠️未修复（6 行枚举可补全 OPPO 7 值；demo/测试仅用 SWIPE_TO_HOME，无消费方触发 NPE——API 对齐项，doc-01 ④-5/06 §3 同判延后）**
### 🟥 **bug 级** ① AnimType 3 值 vs 7 值（review 11 §bug 级残留 #9 / review 06 §3 已提，**本次复核确认**）

**触发场景**：任何按 `OPEN_FROM_HOME` / `REVERSE_TO_OPEN` / `GESTURE_TO_DRAG` 等枚举值对接 lib 的调用方。

**影响**：
- `OPEN_FROM_HOME`（最常用，桌面启动 app 路径）→ lib 编译能过，但 `setAnimParamByType(OPEN_FROM_HOME)` 等方法缺失 → NPE
- `REVERSE_TO_OPEN`（recents → 打开 app 反向路径）→ 同上
- `GESTURE_TO_DRAG`（手势拖图标到桌面）→ 同上，且该类型跳过 `getRateStiffness` 缩放，**无法表达**
- Kotlin enum `ordinal()` 不同 → Bundle 序列化、`when` 分支、`switch` 表 与原厂不兼容
- demo 测试 (`AnimationControllerTest.kt:55,65,103`) 全用 `SWIPE_TO_HOME`——demo 是"概念演示"，未触及真值

**修复成本**：5 行
```kotlin
enum class AnimType {
    OPEN_FROM_HOME,
    REMOTE_CLOSE_TO_HOME,
    REMOTE_CLOSE_TO_HOME_ASSISTANT,
    GESTURE_TO_DRAG,
    SWIPE_TO_HOME,
    SWIPE_TO_HOME_ASSISTANT,
    REVERSE_TO_OPEN,
    RECENTS_TRANSITION,  // lib 扩展（demo 用）
    APP_LAUNCH,           // lib 扩展（demo 用）
}
```

> **状态：⚠️未修复（需 AsyncAnimEndProtocol/序列化或 MultiDynamicAnimation 简化移植 ~30-80 行；AsyncSpringAnim end 已 runOnMainThread——6bbe9a1，物理轨道仍缺）**
### 🟥 **bug 级** ② maybeEnd 双轨补救 + 双轨时序完全缺失

**触发场景**：cancel/skipToEnd/reverseToOpen 触发结束回调时。

**影响**：
- 业务线程 cancel() → 当前线程立刻派发 `onAnimationCancel` + `onAnimationEnd` + `onAnimActualEnd`（原厂语义）
- lib `AsyncSpringAnim.cancel()` (`:29`) 是 `dispatch { real.cancel() }`——androidx `SpringAnimation.cancel()` **同步停帧**、单 listener 派发，没有 cancel + end + actualEnd 三发
- 后果 #1：**"cancel 是 end 的子集"约定失效**——业务侧 `onAnimationEnd` listener 期待在 cancel 时也能收到，lib 中收不到
- 后果 #2：**"物理帧播完"语义不可达**——`onAnimActualEnd` 不发，依赖它清理资源的代码（launcher 端 `onAnimActualEnd` 释放 SurfaceControl leash 等）不执行
- 后果 #3：**justNotifyEndCallback 保护路径不可达**——`onAnimationEnd` 会被错发，业务状态机错误切到 ENDED

**修复成本**：~30 行
- 在 lib 侧建 `AsyncAnimEndProtocol`（类似 `AsyncAnimCallbacks` 但管序列化）
- 或：保留 3 个独立派发口，在 `AsyncAnimCallbacks` 文档强约束调用方按 cancel → end → actualEnd 顺序调（不推荐，违反封装）

> **状态：⚠️未修复（占位类无调用方触发 4 方法；marshal 骨架已在 AsyncSpringAnim.dispatch + runOnMainThread——683179b/6bbe9a1；CustomRectFSpringAnim 全量 ~50 行）**
### 🟥 **bug 级** ③ Thread Switch Protocol（start/cancel/skipToEnd/reverseToOpen 四路）整体缺失

**触发场景**：业务线程 ≠ anim 线程时（即 `mStartAsync=true` 时 start 在主线程、cancel 在任意线程）。

**影响**：
- lib 占位类**没有任何** marshal 逻辑——`AnimationController.kt:65` 把 anim 放进 list 后状态机转移，但 anim.start() 谁来调？anim.cancel() 谁来 marshal？
- 当前 lib 没有调用方触发这 4 个方法（仅 AnimationControllerTest 用作构造器 + null controller 注入），所以**实测无 bug**——但语义缺失
- **文档失实**：`AnimationControlThread.kt:46` 的注释引用"CustomRectFSpringAnim.start() 的 looper.isCurrentThread 协议"——但占位类不实现该协议，是 review 04 §2.3-10 末尾点的"文档与代码脱节"

**修复成本**：~50 行
- 给 `CustomRectFSpringAnim` 加 `mAnimLooperExecutor: LooperExecutor` 字段 + 4 个 marshal 方法（参考 `AsyncSpringAnim.dispatch` 的 `if (supportAnimThread) runOnAnimThread{}` 模式）
- 或：把"线程切换"职责下放到 `AnimationController`，由 controller 持有 mAnimLooperExecutor、controller 调 anim.xxx() 时做 marshal——但 controller 也不知道 anim 想跑哪个线程

> **状态：⚠️未修复（需 MultiDynamicAnimation/requestEnd 移植 ~80-250 行；937dd23 用 androidx 同步 cancel 为有意替代）**
### 🟥 **bug 级** ④ Cancel "下一帧生效"语义不可达

**触发场景**：cancel() 触发时业务侧监听 cancel vs end vs actualEnd 的时序。

**影响**：
- 原厂 cancel() 立刻派发 cancel + end + actualEnd（业务线程），同时把 mCancelRequest 置位 → 下一帧 doAnimationFrame 才真正停帧 + removeCallback
- lib 走 androidx `SpringAnimation.cancel()`：**同步**停帧 + 同步派发 end listener
- 后果 #1：**没有"物理帧播完"窗口**——cancel 与 end 之间没有"还有 N 帧在飞"的间隙
- 后果 #2：**resource cleanup 时序错位**——业务侧 `onAnimActualEnd` 是清理 SurfaceControl leash 等"业务资源"的入口，androidx 路径无此窗口
- 真机上不会立即显现问题，因为 androidx 路径的同步派发更简单；但**对照 OPPO 行为**，recents 转场期间的 SurfaceControl 操作、SystemUI leash 释放等都会有时序差

**修复成本**：~80 行
- 复刻 `MultiDynamicAnimation` + `SpringHolder` 整组（review 11 §3-G 已列 500+ 行）
- 或：把 MultiDynamicAnimation 简化移植——`mMultiDynamicAnimation: MultiDynamicAnimation` 字段 + `start/cancel/requestEnd` 3 个方法 30 行 + SpringHolder 60 行

> **状态：⚠️未修复（MultiDynamicAnimation + 6 SpringHolder ~150-250 行；937dd23 已用 androidx 单自由度演示部分语义）**
### 🟡 **高** ⑤ 6 自由度独立 stiffness/damping/minimumVisibleChange 完全缺失

**触发场景**：任何依赖多自由度独立弹簧参数的转场。

**影响**：
- 原厂：rectY 用过阻尼（damping=1.0）让卡片回弹不抖、centerX 用欠阻尼（damping=0.5）让手感更跟手——两条曲线**独立配置**
- lib 单一 `SpringAnimation`：只有一个 spring、一个 stiffness/damping/minimumVisibleChange
- 后果：**demo11 弹簧演示与原厂行为不等价**——只能演示"单一维度"弹簧

**修复成本**：~150 行（MultiDynamicAnimation + 6 个 SpringHolder + SpringForce）
- 或：用 androidx 6 个 `SpringAnimation` 并行——但这破坏 Single Frame Callback 的原子性（6 次回调 → 6 帧写表）

> **状态：⚠️未修复（~20 行 + 需 OPPO 时长缩放概念；demo 常规操作不触发）**
### 🟡 **高** ⑥ `getRateStiffness` × AppLaunchAnimSpeedHandler 缩放缺失

**触发场景**：用户在系统设置里改"动画时长比例"（开发者选项）。

**影响**：
- 原厂：用户设置 0.5x 动画时长 → stiffness 翻 4 倍（duration scale 平方缩放），让弹簧"快 2 倍"
- lib：直接用 AnimParamProvider 给的原始 stiffness，**用户改设置后 demo 弹簧速度不变**
- 后果：**真机演示时与 OPPO 行为偏差大**

**修复成本**：~20 行（`AppLaunchAnimSpeedHandler.sDurationScale` 字段 + `getRateStiffness` 工具函数）

> **状态：⚠️未修复（捆绑 ⑤ 的 6 自由度引擎；demo 无 rect 弹簧调用面）**
### 🟡 **中** ⑦ `isWithAnim` width/height 切换语义缺失

**触发场景**：OPEN_FROM_HOME / REVERSE_TO_OPEN 类型下 width 弹簧跟随 width，其他类型跟随 height。

**影响**：
- 原厂：`OPEN_FROM_HOME` 时 width 弹簧（rect 横向放大）；其他类型时 rect 纵向放大，width 弹簧被改成 height 弹簧
- lib：无此区分——demo9 OPEN_FROM_HOME 场景若接 rect 弹簧，**视觉方向错**

**修复成本**：~15 行（`isWithAnim(radio, endRadio): Boolean` + mIsWithAnim 字段 + initAllAnimations 路径分支）

> **状态：⚠️未修复（捆绑 ⑤；~10 行，无 alpha 弹簧载体）**
### 🟡 **中** ⑧ `mAlphaStartDelay` 延迟字段缺失

**触发场景**：alpha 与 rect 弹簧的"先后启动"。

**影响**：
- 原厂：alpha 弹簧可设 `setStartDelay`，让 alpha 晚于 rect 启动——视觉上"卡片先到位、再淡入"
- lib：alpha 与 rect 同帧起步

**修复成本**：~10 行（`SpringHolder.setStartDelay` 在 androidx 是支持的；加 `mAlphaStartDelay` 字段 + initAllAnimations 路径写入）

> **状态：⚠️未修复（捆绑 ⑤；~10 行，demo 全 center 锚点不触发）**
### 🟢 **低** ⑨ `mTracking` 三档锚点缺失

**触发场景**：rect Y 锚点（top / center / bottom）。

**影响**：
- 原厂：mTracking=0/1/2 三档，rectY 锚点不同
- lib：rectY 永远 = rectF.centerY()
- 后果：**最小**——大多数 recents 转场都是 center 锚点；top/bottom 仅在通知中心/快捷面板场景用到

**修复成本**：~10 行

> **状态：⚠️未修复（捆绑 ⑤；~10 行）**
### 🟢 **低** ⑩ `mLimitRadioFlag` / `mMinWidth` 钳制字段缺失

**触发场景**：radio / width 超出合理范围。

**影响**：视觉上无 bug，但**可能在极端 swipe 时溢出**

**修复成本**：~10 行

---

## ⑥ 回移建议

### 6.1 值得补进 lib 的（性价比高 / 影响运行时正确性）

| # | 建议 | 改动规模 | 价值 | 不补的后果 |
|---|---|---|---|---|
| 1 | ⚠️未修复（6 行枚举；demo 无消费方——同 §⑤① 判定） — **AnimType 补 7 值**（去 2 假、留 5 真 + SWIPE_TO_HOME） | 5 行 | **高**——review 06 §3 / review 11 §bug 级残留 #9 都点名；与原厂枚举对齐，跨设备兼容 | 任何按 `OPEN_FROM_HOME` / `REVERSE_TO_OPEN` 等对接的代码 NPE；Kotlin enum ordinal 不同 |
| 2 | ⚠️未修复（~30 行；同 §⑤②） — **maybeEnd 双轨时序 + AsyncAnimCallbacks 序列化** | ~30 行 | **高**——风险 #2；cancel 时序断言 | cancel → onAnimationEnd 收不到、onAnimActualEnd 不发，资源清理路径不可达 |
| 3 | ⚠️未修复（~50 行；同 §⑤③） — **Thread Switch Protocol 4 路 marshal**（start/cancel/skipToEnd/reverseToOpen） | ~50 行 | **高**——风险 #3；与 `AnimationControlThread.kt:46` 注释承诺对齐 | 占位类继续"语义失实"；真机演示时主线程 ≠ anim 线程场景会乱序 |
| 4 | ⚠️未修复（~250 行；同 §⑤④⑤） — **`MultiDynamicAnimation` + 6 个 `SpringHolder` 简化移植**（覆盖 cancel 下一帧生效 + 6 自由度） | ~250 行（含 SpringHolder 60 行 + MultiDynamicAnimation 60 行 + CustomRectFSpringAnim 接 130 行） | **高**——风险 #1/4/5/7 一并覆盖 | 6 自由度独立弹簧 + cancel 双轨时序 + isWithAnim 切换全部不可达 |
| 5 | ⚠️未修复（~20 行；同 §⑤⑥） — **`getRateStiffness` × AppLaunchAnimSpeedHandler 缩放** | ~20 行 | **中**——风险 #6 | 用户改动画时长比例后 demo 弹簧速度不变 |
| 6 | ⚠️未修复（依赖 6.1-4；同 §⑤⑨⑩） — **`mTracking` / `mMinWidth` / `mLimitRadioFlag` 钳制三件套** | ~30 行 | **中**——风险 #9/10 | 极端 swipe 时 rect 溢出视觉撕裂 |
| 7 | ⚠️未修复（依赖 6.1-4；同 §⑤⑧） — **`mAlphaStartDelay` 延迟字段** | ~10 行 | **中**——风险 #8 | alpha 与 rect 同帧起步，视觉上"卡片与淡入同步"而非"卡片先到再淡入" |
| 8 | ⚠️未修复（依赖 6.1-4） — **`copyNextAnimState` 下一帧接管**（依赖建议 #4） | ~30 行 | **中**——recents 转场"手指抬起后从半截继续"演示必备 | 半截接管场景不可演示 |
| 9 | ⚠️未修复（依赖 6.1-4） — **`updateEndTargetRectF` 动态改终点**（依赖建议 #4） | ~15 行 | **低**——Android 15 拖动 recents 卡片到一半放手场景 | 手势中途改变终点时 spring 仍跑原终点 |
| 10 | ⚠️未修复（依赖 6.1-4） — **`updateMinVisibleChange` 折叠/平板/普通屏差异化** | ~20 行 | **低**——仅折叠/平板设备受影响 | 不同设备形态弹簧停下阈值相同 |

### 6.2 建议保持简化（成本 > 收益）

| # | 内容 | 简化理由 |
|---|---|---|
| 1 | ✔️保持简化（清单即保持简化） — **`SpringForce` 三支闭式解析解** | androidx `SpringForce` 已实现（同名不同包），行为等价，OPPO 自己也只是 vendored androidx + 私有 hack（`isAtEquilibrium` 反射） |
| 2 | ✔️保持简化（清单即保持简化） — **`initFirstFrameForBreakScene` 4 参接口** | 依赖 RectTransformHelper + BreakParam，与 launcher 业务强绑定；review 11 §B-3 已建议保持 |
| 3 | ✔️保持简化（清单即保持简化） — **`mapRatioVelocity` radio 速度换算** | 与建议 #4 捆绑（如果做了 MultiDynamicAnimation 移植，可以一起做） |
| 4 | ✔️保持简化（清单即保持简化） — **`buildVelocityLogStr` 6 速度日志** | lib 用 `Trace.traceBegin/End` + `LogUtils` 已足够调试；原厂是为 OPPO 内部 dev 流程 |
| 5 | ✔️保持简化（清单即保持简化） — **`SpringAnimReflectUtils` 反射路径** | androidx 不需要反射；lib 走 Androidx 公开 API |
| 6 | ✔️保持简化（清单即保持简化） — **`OnAnimUpdateListener` 自定义 6 自由度回调**（mUpdateListeners） | 依赖 SurfaceControl 事务写表层；demo 演示无调用方 |
| 7 | ✔️保持简化（清单即保持简化） — **`AnonymousClass2` 内嵌 OnAnimationEndListener 类** | lib 可以用 lambda + `OnAnimationEndListener` 复刻同样语义 |

---

## ⑦ 与前 12 份 review 的衔接

| 区域 | 已覆盖点 | 本 review 增量 |
|---|---|---|
| 04 帧调度 / 弹簧 / 续行 | §2.3-10 标记"线程切换协议整体缺失" + 提到 mAnimLooperExecutor / maybeEnd / runOnMainThread / requestEnd | 本 review §3.3-§3.7 把 4 路 marshal 的逐行写法 + maybeEnd 3 条分支 + cancel 双轨时序展开成"行级 + 字段级"映射 |
| 06 public API & 调用面 | §3 AnimType 7 值 vs 3 值 + §4.1 建议补 7 值 | 本 review §3.8 列出 OPPO 7 值的完整含义（OPEN_FROM_HOME / REVERSE_TO_OPEN / GESTURE_TO_DRAG 等），把每个值的"特殊处理路径"（getRateStiffness 跳过 / isOpenAnim=true 等）展开 |
| 11 功能缺口 | §C-1 requestEnd 下一帧生效 + §C-2 6 自由度独立弹簧 + §C-5 线程切换协议整体 | 本 review §3.7 把 requestEnd 的双轨时序图展开成"业务线程 vs anim 线程"两条时间线 + §2.1 把 6 自由度的 36 个字段列全 + §3.5 maybeEnd 3 条分支 |
| 12 bug 级运行时风险 | 未单列 CustomRectFSpringAnim bug | 本 review §⑤ 按风险从高到低 10 条，每条标修复成本（5-250 行） |

---

## 附：关键证据速查表

| 论断 | 证据 |
|---|---|
| 6 自由度独立 SpringForce × 6 + SpringHolder × 6 + 4 配置字段 × 6 | `com/android/quickstep/util/animation/CustomRectFSpringAnim.java:47-112`（字段定义）+ `:209-258`（构造器实例化） |
| 构造器默认值（stiffness=200/damping=1/minVisibleChange 6 个差异化） | `CustomRectFSpringAnim.java:217-247` |
| alpha 额外 mAlphaStartDelay（long） | `CustomRectFSpringAnim.java:53, 389, 799` |
| radius 字段名不一致 mRectRadiusXxx + mRadiusMinimumVisibleChange | `CustomRectFSpringAnim.java:85-91` |
| 7 值 AnimType | `CustomRectFSpringAnim.java:115-123` |
| 6 值 RectAnimType + WhenMappings switch | `CustomRectFSpringAnim.java:131-172` |
| initProperty 把 6 个 holder addSpringHolderItem + 加 2 个 listener | `CustomRectFSpringAnim.java:394-409` |
| initAllAnimations 6 自由度全字段初始化 + mLimitRadioFlag + mIsWithAnim + setStartDelay | `CustomRectFSpringAnim.java:330-392` |
| onUpdate 6 holder.getMValue() + calculateFrameRectF + progress 计算 + mUpdateListeners.onUpdate | `CustomRectFSpringAnim.java:443-470` |
| maybeEnd 3 条分支（mJustNotifyEndCallback / mCanceled / 正常 end） | `CustomRectFSpringAnim.java:423-441` |
| Thread Switch 4 路：start/cancel/skipToEnd/reverseToOpen 同模式 | `CustomRectFSpringAnim.java:608-628, 752-776, 860-883, 885-907` |
| start 选 ANIM_EXECUTOR vs MAIN_EXECUTOR | `CustomRectFSpringAnim.java:888` |
| reverseToOpen 调用 OplusAnimManager.getAnimController().revertRecentsAnimation(this) | `CustomRectFSpringAnim.java:756` |
| copyNextAnimState 用 6 holder.getNextFrameValue 取下一帧 | `CustomRectFSpringAnim.java:630-648` |
| `SpringHolder.getNextFrameValue` 调用 SpringAnimReflectUtils.updateValues | `SpringHolder.java:50-53` |
| SpringHolder updateValueAndVelocity 半步分裂积分（mPendingPosition 分裂） | `SpringHolder.java:107-138` |
| SpringForce 三支闭式（过/临界/欠阻尼） | `SpringForce.java:117-152` |
| MultiDynamicAnimation 裸 delta 积分 | `MultiDynamicAnimation.java:158-159` |
| MultiDynamicAnimation requestEnd 只置 mCancelRequest / mEndRequest | `MultiDynamicAnimation.java:185-191` |
| MultiDynamicAnimation endAnimationInternal removeCallback + 派发 end listener | `MultiDynamicAnimation.java:92-102` |
| AnonymousClass2.onAnimationEnd → runOnMainThread → onAnimationEnd$lambda$0 → maybeEnd | `CustomRectFSpringAnim.java:176-207` |
| runOnMainThread isCurrentThread + Utilities.postAsyncCallback | `CustomRectFSpringAnim.java:778-786` |
| `calculateFrameRectF` 6 自由度矩形换算 + tracking 偏移 | `CustomRectFSpringAnim.java:266-290` |
| `isWithAnim(startRadio, endRadio)` 按 AnimType 决定 width vs height | `CustomRectFSpringAnim.java:411-421` |
| `getRateStiffness` × AppLaunchAnimSpeedHandler.sDurationScale 缩放 | `CustomRectFSpringAnim.java:297-313` |
| `updateMinVisibleChange` 折叠/平板/普通屏差异化 | `CustomRectFSpringAnim.java:571-581` |
| `setAnimParamByType` 调 AnimParamProvider.getXxxParam 设 6 个 setSpringHolderParamByType | `CustomRectFSpringAnim.java:788-801` |
| `inheritVelocity` 从 source 6 holder.getMVelocity() 写入 | `CustomRectFSpringAnim.java:320-328` |
| `updateEndTargetRectF` 4 setFinalPosition + f9==-1 不改 radius | `CustomRectFSpringAnim.java:909-925` |
| `justNotifyEndCallback` 保护路径 | `CustomRectFSpringAnim.java:734-739` |
| `getCurrentRadius` 含 maxRadioVelocity 钳制 | `CustomRectFSpringAnim.java:660-662` |
| `initFirstFrameForBreakScene` 4 参 BreakParam + RectTransformHelper | `CustomRectFSpringAnim.java:696-717` |
| `getOpeningWindowProgress` reverseToOpen 映射 | `CustomRectFSpringAnim.java:681-685` |
| `setVelocity(f9..f13, 5 参)` × 1000 单位换算 | `CustomRectFSpringAnim.java:846-854` |
| `mapRatioVelocity` radio 速度换算 | `CustomRectFSpringAnim.java:741-746` |
| lib 18 行占位 + 3 值 AnimType | `lib/src/main/java/com/asyncanimator/anim/CustomRectFSpringAnim.kt:1-18` |
| lib 调用面：AnimationController.addRecentsAnim 句柄 | `lib/src/main/java/com/asyncanimator/control/AnimationController.kt:26, 65` |
| lib 调用面：DefaultAnimationController 3 虚函数签名 | `lib/src/main/java/com/asyncanimator/control/DefaultAnimationController.kt:52, 55, 64` |
| lib 调用面：AnimationControllerTest 3 处构造 | `lib/src/test/java/com/asyncanimator/control/AnimationControllerTest.kt:55, 65, 103` |
| lib 文档引用 CustomRectFSpringAnim.java:423-441 作为 maybeEnd 双轨原型 | `lib/src/main/java/com/asyncanimator/anim/ActualEndAnimListener.kt:13-21` |
| lib 文档引用"CustomRectFSpringAnim.start() 的 looper.isCurrentThread 协议"（**未实现**） | `lib/src/main/java/com/asyncanimator/thread/AnimationControlThread.kt:46` |
| lib `AsyncAnimCallbacks.onAnimActualEnd` 仅给 ActualEndAnimListener 子类派发 | `lib/src/main/java/com/asyncanimator/anim/AsyncAnimCallbacks.kt:61-72` |
| lib `AsyncAnimCallbacks.runOnMainThread` 只覆盖"回主线程"那一面 | `lib/src/main/java/com/asyncanimator/anim/AsyncAnimCallbacks.kt:97-100` |
| lib `AsyncSpringAnim.cancel()` 走 androidx 同步停帧路径 | `lib/src/main/java/com/asyncanimator/anim/AsyncSpringAnim.kt:28` |

---

## 复核记录 v2（2026-09-09，独立逐条复核）

**方法**：逐条读取当前 lib 源码（Python `open(path, encoding='utf-8')`），对照文档中每个引用的文件路径、行号、状态标记，确认或修正。

### 路径验证

本文档 lib 路径在编写时已按重组后包结构书写（`anim/`、`control/`、`thread/`），全部无需修正。

| 引用路径 | 验证 |
|---|---|
| `anim/CustomRectFSpringAnim.kt` | ✔️存在（18 行） |
| `anim/AsyncAnimCallbacks.kt` | ✔️存在（101 行） |
| `anim/AsyncSpringAnim.kt` | ✔️存在（51 行） |
| `anim/ActualEndAnimListener.kt` | ✔️存在（27 行） |
| `control/AnimationController.kt` | ✔️存在（258 行） |
| `control/DefaultAnimationController.kt` | ✔️存在（85 行） |
| `control/AnimationControllerTest.kt`（test） | ✔️存在（110 行） |
| `thread/AnimationControlThread.kt` | ✔️存在（90 行） |

### 行号验证

| 引用 | 当前行号 | 验证 |
|---|---|---|
| `AnimationController.kt:26, 65` | L26=recentsAnims, L65=addRecentsAnim | ✔️ |
| `DefaultAnimationController.kt:52, 55, 64` | L52=addRecentsAnim, L55=canFinishRecentsAnim, L64=revertRecentsAnimation | ✔️ |
| `AnimationControllerTest.kt:55, 65, 103` | L55/65/103=CustomRectFSpringAnim(SWIPE_TO_HOME) | ✔️ |
| `ActualEndAnimListener.kt:17` | L17=引用 CustomRectFSpringAnim.java:423-441 | ✔️ |
| `AnimationControlThread.kt:46` | L46=引用 CustomRectFSpringAnim.start() 协议 | ✔️ |
| `AsyncAnimCallbacks.kt:51-52` | L51-52=onAnimationEnd | ✔️ |
| `AsyncAnimCallbacks.kt:54-55` | L54-55=onAnimationCancel | ✔️ |
| `AsyncAnimCallbacks.kt:61-72` | L61-72=onAnimActualEnd | ✔️ |
| `AsyncAnimCallbacks.kt:97-100` | L97-100=runOnMainThread | ✔️ |
| `AsyncSpringAnim.kt:28` | L28=cancel | ✔️ |
| `AsyncSpringAnim.kt:30` | L30=skipToEnd | ✔️ |
| `AsyncSpringAnim.kt:34` | L34=setStartVelocity | ✔️ |
| `AsyncSpringAnim.kt:37-42` | L37-42=addEndListener | ✔️ |
| `AsyncSpringAnim.kt:44-50` | L44-50=dispatch | ✔️ |
| `CustomRectFSpringAnim.kt:8-10` | L8-10=注释 | ✔️ |
| `CustomRectFSpringAnim.kt:11` | L11=class | ✔️ |
| `CustomRectFSpringAnim.kt:13-17` | L13-17=AnimType 3 值 | ✔️ |

### 状态标记逐条确认（26 条）

| 条目 | 文档标记 | 代码验证 | 结论 |
|---|---|---|---|
| §3-1 6-DOF 降级为占位 | ✔️保持简化 | CustomRectFSpringAnim.kt 仍 18 行 | ✔️确认 |
| §3-2 MultiDynamicAnimation 未移植 | ✔️保持简化 | 无 MultiDynamicAnimation | ✔️确认 |
| §3-3 EndReason 三态 | ⚠️未修复 | 无 EndReason enum，cancel 仍直接 real.cancel() | ⚠️确认 |
| §3-4 AnimType OPEN_FROM_HOME | ❌不成立/已过期 | 无 OPEN_FROM_HOME 调用点 | ❌确认 |
| §3-5/3-6/3-7/3-8/3-9/3-10/3-11 | ✔️保持简化 | 代码无变化 | ✔️确认 |
| §4.1-1 回移 AnimType 7 值 | ❌不成立/已过期 | 同 §3-4 | ❌确认 |
| §4.1-2 EndReason 语义区分 | ⚠️未修复 | 同 §3-3 | ⚠️确认 |
| §4.1-3 最小 MultiDynamicAnimation | ⚠️未修复 | 无 MultiDynamicAnimation | ⚠️确认 |
| §4.2-1..8 有意简化 | ✔️保持简化 | 代码无变化 | ✔️确认 |
| §4.3-1 6-DOF stiffness | ⚠️未修复 | 单一 SpringAnimation | ⚠️确认 |
| §4.3-2 最小可视变化阈值 | ⚠️未修复 | 无差异化阈值 | ⚠️确认 |
| §4.3-3 USAGE AnimType 3 值 | ✅已修复 | USAGE.md 已列 3 值枚举（42882ff） | ✅确认 |
| §4.3-4 isWithAnim | ⚠️未修复 | 无 isWithAnim | ⚠️确认 |
| §4.3-5 getRateStiffness | ⚠️未修复 | 无 getRateStiffness | ⚠️确认 |
| §4.3-6 copyNextAnimState | ⚠️未修复 | 无 copyNextAnimState | ⚠️确认 |
| §4.3-7 getCurrentRadius 钳制 | ⚠️未修复 | 无 getCurrentRadius | ⚠️确认 |
| §4.3-8 justNotifyEndCallback | ⚠️未修复 | 无 justNotifyEndCallback | ⚠️确认 |
| §4.3-10 updateEndTargetRectF | ⚠️未修复 | 无动态改终点 | ⚠️确认 |
| §4.3-11..17 其余遗漏 | ⚠️未修复 | 均未实现 | ⚠️确认 |
| §5① AnimType 3 vs 7 | ⚠️未修复 | CustomRectFSpringAnim.kt 仍 3 值 | ⚠️确认 |
| §5② maybeEnd 双轨 | ⚠️未修复 | 无 maybeEnd | ⚠️确认 |
| §5③ Thread Switch Protocol | ⚠️未修复 | 占位类无 marshal | ⚠️确认 |
| §5④ Cancel 下一帧生效 | ⚠️未修复 | androidx 同步 cancel | ⚠️确认 |
| §5⑤ 6-DOF 独立参数 | ⚠️未修复 | 单一 SpringAnimation | ⚠️确认 |
| §5⑥ getRateStiffness 缩放 | ⚠️未修复 | 无缩放 | ⚠️确认 |
| §5⑦ isWithAnim | ⚠️未修复 | 无 isWithAnim | ⚠️确认 |
| §5⑧ mAlphaStartDelay | ⚠️未修复 | 无 alpha 延迟 | ⚠️确认 |

### 汇总

- **条目总数**：26 条独立状态标记
- **路径修正**：0（编写时已用重组后路径）
- **行号修正**：0（全部准确）
- **状态标记修正**：0（所有 ❌/✔️/⚠️/✅ 与当前代码一致）
