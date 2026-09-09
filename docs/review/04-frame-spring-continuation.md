> **⚠️ 已废弃（superseded）**：本文档是第一轮（4 路）对比的旧版本，已被 [vs-oppo-04-frame-scheduling.md / vs-oppo-05-continuation-spring.md](vs-oppo-04-frame-scheduling.md) 取代。内容仅供参考，不要按本文档的结论修改代码。

# 区域 4 对比 Review：帧调度 / 弹簧 / 续行层

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib`（AsyncAnimator 演示库，idiomatic Kotlin 重写的 OPPO 动画线程方案复刻）
> - **原厂**：`D:/oppo_a6_launcher/sources`（OPPO ColorOS 15 Launcher `com.android.launcher 15.8.24` JADX 反编译源码）
>
> 背景结论见 `docs/animation-thread-analysis-v4.md`（v4）。本区域覆盖：lib 的 `core/anim/AnimationHandler`、`core/scheduler`（`TickScheduler`、`ScheduledTickScheduler`）、`launcher/async/CustomRectFSpringAnim`、`launcher/continuation`（`OplusValueAnimator`、`RecordInputInterpolator`），并连带读取 `launcher/animthread`（`HandlerTickScheduler`、`AnimationControlThread`）作为帧源替换的落点。
>
> 取证方法：lib 侧直接 Read；sources 侧因企业 DLP 加密（Read 返回 `%TSD-Header` 密文），全部经 Grep（ripgrep 明文通道）取证，行号为 JADX 反编译文本行号。

---

## 1. 类对应关系表

| lib 类 | 原厂类 | 关系与证据 |
|---|---|---|
| `core/anim/AnimationHandler.kt` | `androidx/core/animation/AnimationHandler.java`（vendored，classes2.dex）；`androidx/dynamicanimation/animation/AnimationHandler.java`（classes2.dex）；框架 `android.animation.AnimationHandler`（@hide，**不在 sources 树内**，仅被 import 使用） | ThreadLocal 单例 + 懒注册 + 懒删除的结构镜像。lib `AnimationHandler.kt:132-141`（threadLocalHandler + testHandler）↔ vendored core `:13-14, 158-172`；lib 懒删除 `:66-73, 104-108` ↔ core `:204-210, 119-128`；lib 首注册启动帧源 `:53-57` ↔ core `:175-177` / dynamicanimation `:136-138`。框架版被 `MultiDynamicAnimation.java:3` import、`:21` implements 其 `AnimationFrameCallback`、`:127` `getInstance().addAnimationFrameCallback(this, 0L)`，且被 `OplusExecutors.java:170` `setProvider(SfVsyncFrameCallbackProvider())` 换帧源 |
| `core/scheduler/TickScheduler.kt` | vendored `AnimationFrameCallbackProvider` 接口 | 帧源抽象。core 版接口 `AnimationHandler.java:23-31`（postFrameCallback/getFrameDelay/setFrameDelay/onNewCallbackAdded）；dynamicanimation 版 `:40-48`（抽象类 + postFrameCallback）。lib 统一为 `postFrameCallback/removeFrameCallback/start/stop/frameTimeNanos` |
| `core/scheduler/ScheduledTickScheduler.kt` | `FrameCallbackProvider14`（Handler.postDelayed 退化路径）的 JVM 移植 | core `:33-79`（`mFrameDelay = 16`，`:37`）、dynamicanimation `:50-72`；lib 用 `ScheduledExecutorService.scheduleAtFixedRate(0, 16ms)`（`ScheduledTickScheduler.kt:56-61`） |
| `launcher/animthread/HandlerTickScheduler.kt` | `FrameCallbackProvider14/16` + `SfVsyncFrameCallbackProvider` 的移植替代 | 原厂 `FrameCallbackProvider16` 挂 `Choreographer`（core `:82-108`、dyn `:75-94`）；launcher.anim 线程上被换成 SF-vsync（`OplusExecutors.java:169-171`）。lib 因 hidden API 不可达，用绑本线程 Looper 的 `postDelayed` 帧循环（`HandlerTickScheduler.kt:67-80`），由 `AnimationControlThread.onLooperPrepared()` 装入 ThreadLocal（`AnimationControlThread.kt:60-70` ↔ `OplusExecutors.ANIM_EXECUTOR$lambda$0` 的 `:169-171`） |
| `launcher/async/CustomRectFSpringAnim.kt`（18 行占位） | `com/android/quickstep/util/animation/CustomRectFSpringAnim.java`（907 行）；姊妹类 `com/android/quickstep/util/OplusRectFSpringAnim.java`（extends AOSP `RectFSpringAnim`，走 androidx dynamicanimation，`:44, 867-888`） | lib 仅保留类名 + `AnimType` 枚举作 AnimationController 的句柄占位（`CustomRectFSpringAnim.kt:11-18`）。原厂枚举 7 值（`CustomRectFSpringAnim.java:115-123`：OPEN_FROM_HOME / REMOTE_CLOSE_TO_HOME / …_ASSISTANT / GESTURE_TO_DRAG / SWIPE_TO_HOME / SWIPE_TO_HOME_ASSISTANT / REVERSE_TO_OPEN），lib 3 值（SWIPE_TO_HOME / RECENTS_TRANSITION / APP_LAUNCH，其中后两个原厂不存在） |
| （lib 无对应） | `com/android/quickstep/util/animation/MultiDynamicAnimation.java` + `SpringHolder.java` + `SpringForce.java` + `SpringAnimReflectUtils.java` | 帧循环载体 + 弹簧积分层。MultiDynamicAnimation 直挂**框架** AnimationHandler（`:3, 21, 127`），裸 delta 积分（`:152-159`），`requestEnd` 下一帧生效（`:185-191`）。lib 全树无 MultiDynamicAnimation/SpringForce/SpringHolder |
| `launcher/continuation/OplusValueAnimator.kt` | `com/oplus/quickstep/utils/OplusValueAnimator.java`（495 行，Kotlin 反编译，`@SourceDebugExtension` 指向 `AppToOverviewContinuationHelper.kt`） | timeController 委托模式复刻：lib `:49-78` ↔ 原厂 `:193-314`（start/cancel/end/pause/isRunning/getDuration/addListener 委托 timeController，null 则 super）；lib `setCurrentFraction` 双写 `:42-45` ↔ 原厂 `:283-289`；lib `CURRENT_FRACTION` FloatProperty `:151-160` ↔ 原厂 `:39-56`；lib `generateContinuationAnim` `:124-147` ↔ 原厂 `:89-120` |
| `launcher/continuation/RecordInputInterpolator.kt` | `com/oplus/quickstep/utils/RecordInputInterpolator.java` | 逐行一致：lib `:16-19` ↔ 原厂 `:23-26`（getInterpolation 记录 input 后委托 realInterpolator） |

另注：`com/oplus/painteranimation/OplusValueAnimator.java` 是同名但无关的画板封装类，非对照目标。

---

## 2. 保真度评估

### 2.1 精确复刻

1. **AnimationHandler 的 ThreadLocal + testHandler 语义**。lib `AnimationHandler.kt:132-141`（`testHandler ?: threadLocalHandler.get() ?: …also(set)`）与 vendored core `:158-168`（`sTestHandler` 优先、ThreadLocal 懒构造）逐句对应；`animationCount` ↔ `getAnimationCount()`（core `:140-146`）。
2. **懒删除协议**。removeCallback 置 null 槽 + dirty 标志，帧末 cleanUpList 压缩：lib `:66-73, 104-108` ↔ core `:204-210, 119-128` / dyn `:165-172, 96-105`。顺序遍历、跳过 null 槽的分发循环也一致（lib `:94-101` ↔ core `:130-138`）。
3. **首注册才向帧源发脉冲**。列表空→非空时 postFrameCallback：lib `:53-57` ↔ core `:175-177`。
4. **帧时间单位换算**。Choreographer nanos → ms：lib `:85`（`/1_000_000`）↔ core `:88`（`/ AnimationKt.MillisToNanos`）。
5. **RecordInputInterpolator**。记录副作用 + 委托真实插值器，逐行一致（lib `RecordInputInterpolator.kt:16-19` ↔ 原厂 `:23-26`）。唯一偏差是 `inputed` 初值（见 2.3-1）。
6. **OplusValueAnimator 委托骨架**。六个生命周期方法的"有 timeController 则委托、否则走 super"分支与原厂一一对应；`setCurrentFraction` 的 super + param 双写一致；`CURRENT_FRACTION` 的 get 读 `param.currentFraction`（anim 为 null 时原厂返回 -1.0f，lib 因 Kotlin 非空签名略去该分支）、setValue 走 `setCurrentFraction` 一致。
7. **generateContinuationAnim 主流程**。从 `RecordInputInterpolator.inputed` 回填 `param.currentFraction` → 边界检查 `[0, 1)` 越界返回 null → 新 timeController 从 f 跑到 1.0、`duration > 0` 才 setDuration：lib `:124-147` ↔ 原厂 `:89-120`，判定与分支结构一致。

### 2.2 有意简化

1. **TickScheduler 统一抽象取代三套帧源**。原厂同进程存在 vendored core（主线程 Choreographer）、dynamicanimation（调用线程 Choreographer + `setProvider`，dyn `:174-176`）、框架 @hide（ThreadLocal provider 可换 SF-vsync，`OplusExecutors.java:170`）三条路径；lib 合并为单一 `TickScheduler` 接口 + 两个实现。属文档（v4 §8.1）认可的移植方式。
2. **ScheduledTickScheduler 用 JVM 定时器仿真 vsync**。`scheduleAtFixedRate(16ms)`（`:60`）对应 `FrameCallbackProvider14` 的 `postDelayed(this, 16)` 退化路径（core `:61-70`），纯 JVM 可测是显式设计目标（`ScheduledTickScheduler.kt:11-25` 类注释）。
3. **CustomRectFSpringAnim 降级为句柄占位**。原厂 907 行的 6 自由度弹簧（centerX/rectY/width/radio/radius/alpha 各持 SpringHolder，字段见 `CustomRectFSpringAnim.java:43-113`）、线程切换协议、RectTransformHelper 全部未复刻；lib 注释明确"实际动画逻辑由 SpringAnimation 实现"（`CustomRectFSpringAnim.kt:3-9`）。
4. **OplusValueAnimator 去日志化**。原厂 start/cancel/end/pause/setDuration/setInterpolator 全带 `LogUtils.i` + `Debug.getCallers`（如 `:211, 227, 269, 294, 303, 320-321`），lib 全部省略；lib 的 `Trace.traceBegin/traceEnd`（`:133-145`）替代原厂的 `LogUtils.i("generateContinuationAnim(...)")`（`:118`）。
5. **AnimParam 简化**。原厂是 4 参 data class（startValue/endValue/typeEvaluator/valueApplicator）+ 可变 currentFraction/duration/interpolator + 完整 copy 语义（`:330-371, 380-394, 413-416`）；lib 是 7 字段扁平 data class（`OplusValueAnimator.kt:82-90`），去掉 typeEvaluator 与 generateAnim 工厂的 evaluator 自动选择（原厂 `:130-140` 的 IntEvaluator/FloatEvaluator 分支）。
6. **`ofFloat(isAsync)` 为 lib 自加 API**（`:117-121`），原厂无对应（原厂入口是 `generateAnim(name, param)`，`:82-86, 127-151`）。

### 2.3 遗漏 / 偏差

1. **`RecordInputInterpolator.inputed` 初值**：lib 为 `-1f`（`RecordInputInterpolator.kt:13`），原厂为 Java 字段默认值 `0f`（`RecordInputInterpolator.java:10` 无显式初始化）。这不是无害差异——它直接改变续行门槛（见 §3-1）。
2. **续行动画共享 param 引用**。lib `generateContinuationAnim` 把 `anim.param` 原样传给新 anim（`OplusValueAnimator.kt:139`），原厂用 `AnimParam.INSTANCE.copy(anim.getParam())` 做拷贝（原厂 `:105`，copy 实现 `:353-360`）。lib 中新旧动画共用同一个 param 对象。
3. **timeController 接线是空实现**。lib `TimeControllerObjectAnimator.setTarget()`/`setProperty()` 方法体为空或仅注释（`OplusValueAnimator.kt:96-111`），构造时 `ObjectAnimator(null, null, 0f, 1f)` target 为 null；原厂是真实 `ObjectAnimator` + `setTarget(新anim)` + `setProperty(CURRENT_FRACTION)`（`:104, 109-111`），靠它每帧写 `setCurrentFraction` 驱动新 anim。
4. **续行 timeController 缺 LinearInterpolator**。原厂 `:117` `objectAnimator.setInterpolator(new LinearInterpolator())`——续行段 fraction 按时间线性推进；lib 未设置，继承 ValueAnimator 默认的 AccelerateDecelerateInterpolator。
5. **lib 未 override `setInterpolator`**。原厂 `:292-298` 在 super 之外同步写 `param.setInterpolator(...)`，这正是 `generateContinuationAnim` 能从 `param.getInterpolator()` 拿到 RecordInputInterpolator 的保证（`:94`）；lib 只在 init 时从 param 读一次（`:33`），运行时调用方 `setInterpolator(recordInput)` 后 param 里仍是旧值，续行会静默丢失 inputed 回填。
6. **lib AnimationHandler 缺延迟启动机制**。dynamicanimation 版有 `mDelayedCallbackStartTime` + `addAnimationFrameCallback(cb, delay)` + `isCallbackDue`（dyn `:16, 123-133, 135-145`）；框架版同样带 delay 参数（`MultiDynamicAnimation.java:127` 以 `0L` 调用）。lib `addAnimationFrameCallback` 无 delay 形参（`AnimationHandler.kt:51`）。
7. **自维持回路的停止条件不一致**。原厂每帧结束检查"还有 callback 才续帧"（core `:197-202`、dyn `:30-32`），列表清空即自然停帧。lib 的 `HandlerTickScheduler` 复刻了这一点（`:74-78` 空则 `running = false`），但默认的 `ScheduledTickScheduler` 是常驻定频循环，**无 callback 也继续空转**（`:56-61` 无 stop-on-empty），两个实现语义不统一。
8. **异常隔离是 lib 新增语义**。lib 对每个 callback `runCatching`（`AnimationHandler.kt:99`、`ScheduledTickScheduler.kt:81`）；原厂 core/dyn 无任何 try-catch（core `:131-136`），单 callback 抛异常会中断整帧剩余分发并沿 provider 上抛。
9. **帧内 delta 对齐未复刻（且分情况）**。原厂 OPPO 复制版 `DynamicAnimation.doAnimationFrame` 用 `Choreographer.getSfInstance().getFrameIntervalNanos()` 把 delta 对齐到整数帧间隔（偏差 ≤ 半帧时，`DynamicAnimation.java:473-476`），而 MultiDynamicAnimation 用裸 delta（`:159`）。lib 两个 scheduler 都无对齐逻辑——对照 CustomRectFSpringAnim 链路（走 MultiDynamicAnimation）这恰好忠实，但 lib 若复刻 View spring（v4 §3 路径 D）则缺这层稳定化。
10. **CustomRectFSpringAnim 线程切换协议整体缺失**。原厂 start 按 `mStartAsync` 选 ANIM_EXECUTOR/MAIN_EXECUTOR 再 `isCurrentThread` 判线程纠偏（`:885-907`，尤其 `:888, 891`）；cancel/skipToEnd/reverseToOpen 同款纠偏 + `maybeEnd()` 双轨补救（`:608-628, 752-776, 860-883`）；结束回调 `runOnMainThread` 回 UI 线程（`:778-785`）。lib 占位类没有任何一项。注意：lib `AnimationControlThread.kt:44` 的类注释引用了"CustomRectFSpringAnim.start() 的 looper.isCurrentThread 协议"作为线程安全模型依据，但 lib 侧的该类并未实现它——文档与代码脱节。

---

## 3. 行为差异风险点

按"可能导致语义不同"的严重度排序：

1. **（高）续行动画在 lib 里实际不驱动目标**。timeController 的 setTarget/setProperty 是空实现（2.3-3），`generateContinuationAnim` 返回的新 anim 即使 start，timeController 也不会把 fraction 写进新 anim 的 `setCurrentFraction`——续行链路的"半步接管"效果在 lib 中名存实亡。这是功能级差异，不是保真度折损。
2. **（高）ScheduledTickScheduler 破坏 per-thread 帧语义**。v4 §8.1 的核心要求是"动画在哪个线程 start，帧回调就在哪个线程 tick"。lib 默认 scheduler 的 tick 跑在共享的 `AsyncAnimator-Tick` 守护线程（`ScheduledTickScheduler.kt:30-32`），与 start 线程无关；只有显式安装 `HandlerTickScheduler`（`AnimationControlThread.kt:62`）的路径满足该语义。任何依赖"帧回调线程 == start 线程"的 demo 断言在默认配置下会失败。
3. **（中）`inputed` 初值 0f vs -1f 改变续行门槛**。原厂：动画已 start 但插值器从未被调用（例如首帧前 cancel）时 `inputed = 0` → `currentFraction = 0` → 通过 `[0,1)` 检查 → 生成从头再来的续行；lib：`inputed = -1` → 回填后 `-1 < 0` → 返回 null。两边对"0 帧取消"场景的产出不同（原厂给续行、lib 给 null）。
4. **（中）param 共享引用造成双向污染**（2.3-2）。续行动画运行期间 timeController 写 `param.currentFraction` 会同步改到旧 anim 的 param；若之后对旧 anim 再次 `generateContinuationAnim`，取到的是新 anim 的进度而非旧 anim 被打断时的进度。原厂的 copy 语义天然隔离。
5. **（中）缺 LinearInterpolator 改变续行曲线**（2.3-4）。原厂续行段线性推进 fraction（视觉上是匀速收尾）；lib 默认加减速曲线，尾部更缓。时长相同的情况下动效可感知不同。
6. **（中）未 override setInterpolator 使续行起点静默失效**（2.3-5）。调用方按原厂习惯 `anim.setInterpolator(RecordInputInterpolator(...))` 时，lib 的 param.interpolator 不更新，`generateContinuationAnim` 拿不到 RecordInputInterpolator → 不回填 inputed → 用 param 里的旧 currentFraction（默认 -1）→ 直接返回 null。
7. **（低）异常隔离改变失败传播**（2.3-8）。原厂单动画抛异常会炸掉整帧（后续 callback 本帧不执行，异常暴露到 Looper）；lib 吞掉继续。demo 场景更安全，但掩盖了原厂"异常即暴露"的调试语义。
8. **（低）ScheduledTickScheduler 常驻空转**。无 callback 时仍按 16ms tick（2.3-7），原厂此刻完全停帧。无功能错误，但 CPU/功耗语义不同，且与 `HandlerTickScheduler` 行为不一致会让同一测试在两种 scheduler 下观察到不同的 frameCount 增长。
9. **（提示）`installThreadScheduler` 的时序约束**。`AnimationHandler.kt:150-158` 要求"必须在线程首次访问 instance 之前调用"，原厂的 `setProvider`（dyn `:174-176` / 框架版）随时可换。`AnimationControlThread.onLooperPrepared` 的调用点满足该约束，但这是 lib 自加的隐性契约，误用（先碰 instance 再 install）会静默不生效。

---

## 4. 回移建议

### 4.1 值得补进 lib 的

1. **`RecordInputInterpolator.inputed` 初值改 `0f`**。一行对齐原厂（`RecordInputInterpolator.java:10`），消除风险点 3。成本为零；若刻意保留 -1f 作为"从未采样"哨兵，则应在 `generateContinuationAnim` 里区分"从未采样（用 param 原值）"与"采样到 -1"，并文档化这是对原厂的有意修正而非复刻。
2. **续行三件套：param 拷贝 + LinearInterpolator + setTarget/setProperty 真实接线**（对应 2.3-2/3/4）。这是续行层存在的意义本身；当前 lib 实现让 `generateContinuationAnim` 返回一个"结构正确但不动"的对象。建议 `TimeControllerObjectAnimator` 的 `setTarget` 真正持有目标 anim、`setProperty(CURRENT_FRACTION)` 后在帧回调里 `target.setCurrentFraction(value)`，并把 `anim.param.copy()` 传入新 anim。
3. **override `setInterpolator` 同步写 param**（对应 2.3-5，原厂 `:292-298`）。四行代码，否则 RecordInputInterpolator 机制在"运行时才装插值器"的调用顺序下整体失效。
4. **统一两个 scheduler 的"空则停"语义**，并在文档/注释中明确 ScheduledTickScheduler 不满足 per-thread 帧语义、仅限 JVM 单元测试。建议给 ScheduledTickScheduler 加空转保护（tick 时 callbacks 为空则自动 stop，下次 postFrameCallback 时 restart），与 `HandlerTickScheduler.kt:74-78` 对齐，同时消除风险点 8。
5. **（可选，低优先）`addAnimationFrameCallback(cb, delayMs)` 重载**。对齐 dynamicanimation/框架版签名（dyn `:135-145`），为将来复刻 `MultiDynamicAnimation.startAnimationInternal`（`:127`）铺路。当前 lib 无调用方，可缓。

### 4.2 建议保持简化的

1. **不移植 Choreographer / `SfVsyncFrameCallbackProvider`**。两者是平台 hidden API（`OplusExecutors.java:5, 170`），AOSP 无公开替代；`HandlerTickScheduler` 的 Looper postDelayed 方案 + `installThreadScheduler` 注入点已是正确折中，且接口与原厂 `AnimationFrameCallbackProvider` 一一对应，将来换真 vsync 源成本为零。保持。
2. **不复刻 CustomRectFSpringAnim 的 6 自由度弹簧与线程切换协议**。该协议的价值由 v4 §8.2 认可，但 907 行的窗口几何/RectTransformHelper/AsyncAnimCallbacks 属于"弹簧积分层 + 事务写表层"的职责，硬塞进占位类会让 lib 的 demo 目标失焦。建议反向处理：**改掉 `AnimationControlThread.kt:44` 注释里对 CustomRectFSpringAnim 协议的引用**，避免文档承诺不存在的复刻；协议本身留待该区域（async 层）真正复刻时落地。
3. **保留 `runCatching` 异常隔离**。demo/教学库中单个动画失败不应拉垮整个 tick 循环；但需在类注释里注明"原厂无此保护，异常会中断整帧"（当前注释只说"类似原版 swallow"，与原厂事实不符，建议修正措辞）。
4. **不移植 LogUtils / `Debug.getCallers` 调试日志**。纯 OEM 调试设施，lib 用 `Trace` 替代已够。
5. **`ofFloat(isAsync)` 保留但标注为 lib 扩展**。它把"是否异步"这一原厂散落在 `CustomRectFSpringAnim.mStartAsync`/`AsyncValueAnimator.setExecutor` 里的决策收敛成工厂参数，是符合 lib 教学目标的 API 收敛，不必为追求对齐而删除。

---

## 附：关键证据速查

| 论断 | 证据 |
|---|---|
| launcher.anim 线程 + SF-vsync provider | `com/oplus/basecommon/thread/OplusExecutors.java:95, 169-171`（import `android.animation.AnimationHandler` @ `:3`、`SfVsyncFrameCallbackProvider` @ `:5`） |
| MultiDynamicAnimation 挂框架 AnimationHandler | `com/android/quickstep/util/animation/MultiDynamicAnimation.java:3, 21, 127` |
| MultiDynamicAnimation 裸 delta / requestEnd 下一帧生效 | `MultiDynamicAnimation.java:152-159, 185-191` |
| OPPO 复制版 DynamicAnimation 的 SF 帧间隔对齐 | `com/android/quickstep/util/animation/DynamicAnimation.java:473-476` |
| CustomRectFSpringAnim 默认异步 + 线程纠偏 | `CustomRectFSpringAnim.java:252, 256, 888, 891, 778-785, 860-883` |
| CustomRectFSpringAnim AnimType 7 值 | `CustomRectFSpringAnim.java:115-123` |
| 原厂续行：param copy + LinearInterpolator | `com/oplus/quickstep/utils/OplusValueAnimator.java:105, 117, 353-360` |
| 原厂 setInterpolator 双写 param | `OplusValueAnimator.java:292-298` |
| RecordInputInterpolator inputed 默认 0f | `com/oplus/quickstep/utils/RecordInputInterpolator.java:10` |
| vendored core AnimationHandler ThreadLocal/懒删除/续帧 | `androidx/core/animation/AnimationHandler.java:13, 158-168, 204-210, 197-202` |
| dynamicanimation AnimationHandler setProvider/延迟启动 | `androidx/dynamicanimation/animation/AnimationHandler.java:174-176, 135-145` |
