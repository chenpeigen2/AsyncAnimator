# OPPO Launcher 动画线程执行动画的实现方案 — 设计重分析（v4）

> 范围：`com.android.launcher 15.8.24`（OPPO / ColorOS 15）JADX 反编译源码，源码目录 `D:/oppo_a6_launcher/sources`。
>
> 本文是 **v4 版**，由 6 路独立源码取证 + 关键论断人工复核（Grep 穿透 DLP 加密直接核对明文）产出。**它推翻 v3（`animation-thread-analysis.md`）的核心线程模型结论**，分歧清单见 §1；与 v3 不冲突的部分（12 状态 AnimationController、AnimatorPlaybackController、OplusValueAnimator 续行、AnimSeqTimeStamp 防抖等）仍然有效，本文不重复展开。
>
> 所有论断带 `文件路径:行号`。行号来自 JADX 反编译文本。

---

## 0. TL;DR

v3 说"'动画线程'不是真实存在的执行线程，帧回调、值计算、listener 派发全部在主线程"——**这是错的**。

真实的线程模型是**双时钟域 + 输入驱动**的混合结构：

1. **存在一条真实的专用动画线程 `launcher.anim`**（HandlerThread，优先级 **-19** = URGENT_DISPLAY）。它在线程初始化时做了两件事：
   - `AnimationHandler.getInstance().setProvider(new SfVsyncFrameCallbackProvider())` —— 把该线程上框架 `android.animation.AnimationHandler` 的帧源从 app-vsync 换成 **SurfaceFlinger vsync**（`com/oplus/basecommon/thread/OplusExecutors.java:169-171`，import 见 `:3,:5`）；
   - `LauncherBooster.getCpu().setUxThreadValue(Process.myTid())` —— 把该线程注册为调度器眼中的 UX 线程（`:171`）。
2. **窗口弹簧动画（开/关应用、回桌面的 RectF 弹簧）默认就跑在这条线程上**：`CustomRectFSpringAnim` 默认 `mStartAsync = true`（`CustomRectFSpringAnim.java:252`），`start()` 里 `mAnimLooperExecutor = mStartAsync ? OplusExecutors.getANIM_EXECUTOR() : Executors.MAIN_EXECUTOR`（`:888`）。能这么做的技术地基是 `android.animation.AnimationHandler` 的 **ThreadLocal 语义**——动画在哪个线程 start，帧回调就在哪个线程 tick。（trace 实证：`launcher.anim` 逐帧执行 + 每帧 binder 直发 SurfaceFlinger 已在真机 trace 上确认，见 `animation-trace-validation.md`；但该 trace 上帧源相位为 app-vsync 而非 SF-vsync，差异讨论见该文 §5。）
3. **UI 线程仍是大多数动画的家**：View 属性动画、`AnimatorSet`、`PendingAnimation`、手势跟手的进度换算，全部由主线程 Choreographer（app-vsync）驱动。
4. **手势跟手阶段没有动画时钟**：每个 MotionEvent 到达即同步换算进度、同步写 SurfaceControl.Transaction，帧推进由输入事件驱动（输入接收器按 vsync 批量派发，节奏天然对齐帧率）。

---

## 1. 与 v3 的关键分歧（修正清单）

| # | v3 论断 | v4 修正 | 证据 |
|---|---|---|---|
| 1 | "动画线程不是真实存在的执行线程"（§7、TL;DR） | **存在**：`launcher.anim` HandlerThread，-19 优先级，SF-vsync 帧源，UX 调度标记 | `com/oplus/basecommon/thread/OplusExecutors.java:95,169-171` |
| 2 | "帧回调、值计算全部在主线程" | 窗口弹簧链路（CustomRectFSpringAnim 的 6 自由度弹簧积分 + 每帧 RectF 计算）默认在 `launcher.anim` 上逐帧执行 | `CustomRectFSpringAnim.java:252,888-891` |
| 3 | AsyncValueAnimator "只解决子线程调 start/cancel/end 合法化，值最终回到主线程" | 不完整：`setExecutor(ANIM_EXECUTOR)` 后 start 被 execute 到 launcher.anim 线程，ValueAnimator 的帧回调**就在该线程 tick**（ThreadLocal AnimationHandler）；只有 listener 回调经 `AsyncAnimCallbacks` marshal 回主线程。用例：`AppLaunchAnimUtil.java:443` | `AsyncValueAnimator.java:55,117-162`（isCurrentThread 判线程 + execute 纠偏） |
| 4 | MultiDynamicAnimation 是唯一直挂 `android.animation.AnimationHandler` 的路径，跑在主线程 | 挂载机制描述正确，但它服务的 CustomRectFSpringAnim 默认在 `launcher.anim` 线程 start，因此帧循环跑在 launcher.anim 的 **SF-vsync** 上，不是主线程 app-vsync | `MultiDynamicAnimation.java:21,127,152` + `OplusExecutors.java:170` |
| 5 | 弹簧积分是"反射/复刻 SpringForce 的 RK4 积分"（§3.4） | 两点都错：`SpringAnimReflectUtils.updateValues` 是**直接调用** `springForce.updateValues(...)`（非反射）；且 `SpringForce` 是 androidx 的逐字拷贝，用**解析解**（欠/临界/过阻尼三支闭式公式），不是 RK4。反射只残留在 `isAtEquilibrium`（`:33-43` 的 `method.invoke`），而 SpringHolder 实际直接调 `mSpringForce.isAtEquilibrium`，该反射路径基本无调用方 | `SpringAnimReflectUtils.java:59-61` vs `:33-43`；`SpringHolder.java:47,117,130` |
| 6 | v3 未提及事务与线程配套的卸载层 | 补：Surface 事务有专用线程族（URGENT/TASK_VIEW/WALLPAPER Transaction executor），Recents 收尾卸载到 `UX_TASK_EXECUTOR`（onlineUXThread，-19） | `OplusExecutors.java`（Kotlin metadata 字段清单）；`RecentsAnimationController.java:129-157` |

v3 中**仍然成立**、本文沿用的结论：12 状态 `AnimationController`（`com/oplus/quickstep/utils/AnimationController.java:103-115`）、`AnimatorPlaybackController` 进度驱动模型、`OplusValueAnimator` 续行工厂、`AnimSeqTimeStamp`/`AnimationSeqHelper` 防抖、`OplusSpringObjectAnimator` 死代码判定。

---

## 2. 线程地图

集中式线程注册表：`com/oplus/basecommon/thread/Executors.java` + `OplusExecutors.java`（进程级单例，永不 quit）。

| 线程名 | Executor 常量 | 优先级 | 动画相关职责 |
|---|---|---|---|
| main | `Executors.MAIN_EXECUTOR` | — | View 动画、AnimatorSet、手势输入消费、远程动画编排、进度换算 |
| **`launcher.anim`** | `OplusExecutors.ANIM_EXECUTOR` | **-19** | 异步动画主轨道：CustomRectFSpringAnim 弹簧积分、MultiAnimatorSet.async、AsyncValueAnimator(async)、IconLayerUpdater leash 操作。帧源 = **SF-vsync** |
| onlineUXThread | `UX_TASK_EXECUTOR` | **-19** | 远程动画收尾（release leash 等）、Recents 状态收尾、UX 关键任务 |
| UrgentTransactionHelper | `URGENT_TRANSACTION_EXECUTOR` | -8 | IconSurface / RecentsAnimController 的紧急 Surface 事务 |
| TaskViewTransactionHelper | `TASK_VIEW_TRANSACTION_EXECUTOR` | -8 | TaskView 远程动画事务 |
| WallpaperTransactionHelper | `WALLPAPER_TRANSACTION_EXECUTOR` | -4 | 壁纸模糊/位移事务（OplusDepthController） |
| launcher-loader | `MODEL_EXECUTOR` | -4 | LauncherModel 数据加载（动画间接相关：FloatingIconView 取 drawable） |
| PreCloseThread | `PRECLOSE_EXECUTOR` | -8 | 手势结束时的应用预关闭 |
| UiThreadHelper | `UI_HELPER_EXECUTOR` | -4 | AOSP UiThreadHelper 命令的后台化 |

另有独立的 `OplusZoomAnimationControlThread`（"Zoom Animation Control"，`com/oplus/zoom/draganimation/`）服务小窗拖拽动画。

关键基础设施：

- `LooperExecutor`（`LooperExecutor.java:12`）：包一层 Handler 的 Executor，同线程调用内联执行。
- `OplusLooperExecutor`（`OplusLooperExecutor.java:16`）：增加 `executeAtFront`（postAtFrontOfQueue）、`executeWithUx`（执行期间打 UX 标记）、`executeBlockWait`（主线程硬等最多 5s，见 §9 风险）。
- `AsyncAnimWrapper`（`com/android/launcher3/anim/AsyncAnimWrapper.java:10`）：`runOnAnimThread()` → ANIM_EXECUTOR、`runOnMainThread()` → MAIN_EXECUTOR，是上层的线程切换约定入口。

---

## 3. 帧调度层：四条 AnimationHandler 路径（修正版）

| 路径 | AnimationHandler 实现 | 帧源 | 线程 | 使用者 |
|---|---|---|---|---|
| A | vendored `androidx.core.animation.AnimationHandler` | 主线程 Choreographer (app-vsync) | main | vendored ValueAnimator 系、AnimatorPlaybackController |
| B | `androidx.dynamicanimation.animation.AnimationHandler` | 调用线程 Choreographer | main（实际） | FlingSpringAnim、RectFSpringAnim（AOSP 残留路径） |
| C | **框架 `android.animation.AnimationHandler`（@hide）** | **所在线程的 provider：main=app-vsync；launcher.anim=SF-vsync** | **start() 所在线程** | 框架 ValueAnimator 系、MultiDynamicAnimation、AsyncValueAnimator |
| D | OPPO 复制版 `quickstep/util/animation/DynamicAnimation`（androidx 改造，挂框架 AnimationHandler） | 同 C + SF 帧间隔对齐 | start() 所在线程 | 桌面 View 属性 spring（AbsAnimation/AnimationImpl） |

v3 只数出三条路径且默认全部落主线程。修正点：

- **C 路径的帧源是可换的**。`AnimationHandler.getInstance()` 是 ThreadLocal；`OplusExecutors.java:170` 在 launcher.anim 线程上把 provider 换成 `SfVsyncFrameCallbackProvider`，于是该线程上所有走框架 AnimationHandler 的动画**不经过主线程 Choreographer，直挂 SurfaceFlinger vsync**。这就是"动画脱离 UI 线程"的完整机制。
- D 路径（OPPO 复制的 DynamicAnimation）额外做了一步帧内修正：`doAnimationFrame`（`DynamicAnimation.java:461-490`）用 `Choreographer.getSfInstance().getFrameIntervalNanos()` 把本帧 delta 对齐到整数帧间隔（偏差 ≤ 半帧时），保证弹簧积分步长稳定。注意 **MultiDynamicAnimation 没做这层对齐**（`:159` 用裸 delta）——窗口弹簧掉帧时宁可追时间也不拖长，应是有意取舍。
- 弹簧积分全部是**解析解**（`SpringForce.java:111-156`，androidx 逐字拷贝；中途改终点用"半步+半步"分裂积分，见 `SpringHolder.java:117-125`）。`LightSpringForce` 在其上加过冲钳制。

## 4. 异步动画栈设计（launcher.anim 的用户）

都在 `com/android/quickstep/util/animation/`（Kotlin，`classes3.dex`）：

```
CustomRectFSpringAnim          ← 窗口矩形弹簧（6 自由度：centerX/rectY/width/radio/radius/alpha）
  ├─ MultiDynamicAnimation     ← 帧循环载体，implements 框架 AnimationHandler.AnimationFrameCallback (:21)
  │    └─ SpringHolder × 6     ← 单自由度状态；updateValueAndVelocity → SpringAnimReflectUtils.updateValues → SpringForce（解析解）
  ├─ OnAnimUpdateListener      ← 每帧回调：calculateFrameRectF → RectTransformHelper/IconLayerUpdater 写 SurfaceControl.Transaction
  └─ AsyncAnimCallbacks        ← end/cancel 事件 marshal 回主线程分发
```

**线程切换协议**（这套设计的核心纪律）：

- `start()`（`CustomRectFSpringAnim.java:885-907`）：按 `mStartAsync` 选 executor → 若当前不在目标 Looper 线程则 post 过去再 start。
- `cancel()`/`skipToEnd()`/`reverseToOpen()`：同样先 `looper.isCurrentThread()` 判断、跨线程自动 post 纠偏（`:608-628, :752-776, :860-883`）。
- 结束回调：`runOnMainThread()`（`:778-786`）→ `Utilities.postAsyncCallback(MAIN_EXECUTOR.handler)` 回 UI 线程。
- `ActualEndAnimListener` 区分"逻辑结束（UI 线程即发）"与"物理帧播完（动画线程）"——因为 cancel 是"置标志、下一帧生效"语义（`MultiDynamicAnimation.requestEnd` :185-191），存在 UI 线程先发回调、动画线程后清帧的双轨时序。

**MultiAnimatorSet**（`MultiAnimatorSet.java`）：一次转场拆四路并行轨道——

1. `mAnimatorSet`：UI 线程 ValueAnimator 集（view 属性）；
2. `mAsyncAnimatorSet`：在 ANIM_EXECUTOR 上 start/cancel/end（`:135,160,198`）；
3. View spring 集合；
4. `CustomRectFSpringAnim`。

四个 ended 标志聚合，`maybeOnEnd()`（`:103-117`）凑齐才统一回调——**双时钟域的结束时机以最慢一路为准**。

**AsyncValueAnimator**（`AsyncValueAnimator.java`）：ValueAnimator 子类，默认 executor = MAIN_EXECUTOR（`:55`）；`setExecutor(ANIM_EXECUTOR)` 后 `start/cancel/end` 全部重定向到 launcher.anim 线程（`:117-162` 的 isCurrentThread + execute 模式），帧回调随之在该线程 tick；`AtomicBoolean mIsEnd` 保证 end 只发一次；业务 listener 由 `AsyncAnimCallbacks` 统一 marshal 回主线程。即：**动画时钟在 launcher.anim，业务回调在 main**。

**打断接管的线程决策**：手势打断正在跑的打开动画时，`TryConnectExistingAnim.setAsyncStart(isIconSupportAsync())`（`OplusBaseSwipeUpHandler.java:3517`）——只有图标 surface 支持异步才继续用 anim 线程；`createBreakAppOpenAnim`（`:3922`）强制 `setAsyncStart(false)` 回主线程。

## 5. 远程动画链路（Binder → UI 编排 → 双轨执行）

四层，线程归属各不相同：

1. **触发（UI 线程）**：`QuickstepTransitionManager.getActivityLaunchOptions`（`:1247`）创建 runner（`new LauncherAnimationRunner(mHandler, factory, ...)`，`:1251`；`mHandler = new Handler(Looper.getMainLooper())`，`:489`），包装成 RemoteAnimationAdapter 交 system_server。OPPO 接管点：`BaseQuickstepLauncher.java:507` 直接实例化 `OplusQuickstepTransitionManagerImpl`（此路径**没走** ExtRegistry 模式，是个例外）。
2. **Binder 回调（system_server binder 线程）**：`LauncherAnimationRunner.onAnimationStart`（`LauncherAnimationRunner.java:529`，`@BinderThread`）→ post 到 mHandler（UI 线程）。注意 `RemoteAnimationRunnerCompat$1.startAnimation`（`RemoteAnimationRunnerCompat.java:543`）**直接在 binder 线程**做 leash reparent 和初始 `transaction.apply()`（`:714`），不经过 UI 线程。
3. **动画创建与执行（UI 线程 + launcher.anim）**：UI 线程调 `RemoteAnimationFactory.onCreateAnimation`（`LauncherAnimationRunner.java:242` 的接口，IDE 断点 `:265` 就在其 JADX 改名方法上）→ 构建 `MultiAnimatorSet` → 同步轨道留 UI 线程、弹簧轨道下沉 launcher.anim。
4. **三段式收尾**：`AnimationResult.finish()`（`LauncherAnimationRunner.java:98-113`）——同步收尾 UI 线程内联（通知 AMS 动画结束）→ 异步收尾（release leash 等）卸载到 `UX_TASK_EXECUTOR` → onComplete 回 `MAIN_EXECUTOR`。

另一个细节：动画启动前用 `RefreshRateTracker.getSingleFrameMs()` 做 `setCurrentPlayTime` 首帧补偿（`:190-196`）。

## 6. 手势链路：输入驱动 + 时钟驱动两段式

- **输入**：`OplusBaseInputConsumerController.java:127` 注册 `UserBatchedInputEventReceiver`（looper = 主线程），事件按 vsync 批量派发到主线程；`TouchInteractionService.onInputEvent`（`:652`）消费，生产环境 handler 工厂在 `OplusBaseTouchInteractionService.java:709`（`OplusLauncherSwipeHandlerV2Impl`）。**`TouchInteractionService.java:492` 的 `new LauncherSwipeHandlerV2` 是死代码兜底；`AbsSwipeUpHandler.onMotionEvent`（`:2164`）已被掏空**——对照 AOSP 上游读代码会被这两处误导。
- **跟手（无动画时钟）**：每个 MOVE 同步执行 `updateDisplacement → mCurrentShift.updateValue → updateFinalShift → applyScrollAndTransform → TaskViewSimulator.apply → SurfaceTransactionApplier.scheduleApply`。
- **事务落地**：`SurfaceTransactionApplier.scheduleApply`（`:122`）→ `ViewRootImpl.registerRtFrameCallback` + `mergeWithNextTransaction`——**与 RenderThread 画帧时刻严格对齐**，是全系统唯一与 RenderThread 直接打交道的点。OPPO 在其上加了 `mAsyncAnimStarted` 判定：异步动画接管期间**丢弃迟到的同步事务**（`:89-94`），防止双轨写同一 surface 回跳。
- **抬手（时钟驱动接管）**：HOME → RectF 弹簧（默认 async 上 launcher.anim）；RECENTS/NEW_TASK/LAST_TASK → UI 线程 `AnimatorSet`。
- **状态回流**：SystemUI 的 `RecentsAnimationCallbacks`（binder 线程进入）全部 `postAsyncCallback(MAIN_EXECUTOR)` 回主线程（`:197-380`，onRecentsAnimationStart 用 postAtFrontOfQueue 抢跑）。
- 启动时机规避：`deferStartAnimation`（`OplusBaseSwipeUpHandler.java:5597-5605`）用 `Choreographer.postFrameCallback` 延迟一帧再启动动画，透传帧时间戳保证跟手→动画时钟连续。

## 7. Recents 视图侧

- 分页容器被整体替换：`RecentsView` 不再继承 AOSP `PagedView`，改为 OPPO 自写 `StackPagedViewEx`（ViewGroup 直子类）+ 自研 `OverScroller`（SPLINE/CUBIC/BALLISTIC/SPRING 四模式）。卡片 fling/snap 由 View invalidate 逐帧拉取，时钟在 OverScroller 内部按时间差积分。
- 布局策略化：`IRecentsViewLayout` + grid/stack 实现（`OplusGridRecentsView`/`OplusStackRecentsView`），动画构造委托 `ITaskAnimationBuilder`（grid 用 androidx SpringAnimation 补位，stack 用 PendingAnimation+SpringProperty）。
- 帧率控制：`RemoteAnimFrameRateManager.setFrameRateTarget` 按手势速度调刷新率（ioExecutor 上反射调系统私有接口）；`AdfrV32FrameRateUtils` 做 surface 级 `Transaction.setFrameRate`。
- 性能护航：`RecentsBooster`（CPU/GPU/SF boost，主线程 Handler 2000/5500ms 超时兜底）。

## 8. 对本项目（AsyncAnimator lib）重实现的设计启示

对照 `lib/src/main/java/com/asyncanimator/` 现有实现，v4 结论意味着几处设计需要校准：

1. **`TickScheduler` 必须是线程局部的，且帧源可换**。OPPO 方案的全部魔力在 `AnimationHandler`（ThreadLocal）+ `setProvider(SfVsyncFrameCallbackProvider)`：动画在哪个线程 start，就在哪个线程 tick；帧源由线程级 provider 决定。lib 里 `AnimationHandler`/`TickSchedulerHolder` 若实现为全局单例，就复现不了"同一份代码跑在 launcher.anim 还是 main 取决于 start 线程"这一核心语义。Demo 3（AsyncValueAnimator 跨 Looper）应断言：**帧回调发生在 executor 线程，listener 回调发生在 main**。
2. **CustomRectFSpringAnim 的线程切换协议值得完整复刻**：start/cancel/skipToEnd/reverseToOpen 全部 `isCurrentThread()` 判线程 + post 纠偏；结束事件经 AsyncAnimCallbacks 回主线程；`ActualEndAnimListener` 区分逻辑结束与物理帧播完。这是跨线程动画库最有价值的可移植设计。
3. **MultiAnimatorSet 四轨聚合**是"同步 UI 动画 + 异步窗口弹簧"的标准组装方式；`maybeOnEnd` 以最慢一路为准的语义要在测试里覆盖（Demo 中可注入不同速度的四路）。
4. **弹簧积分用解析解而非 RK4**（v3 文档此处有误）：`SpringForce` 三支闭式公式 + 中途改终点的半步分裂积分（`SpringHolder.updateValueAndVelocity`）。lib 的 `SpringForce` 若按此实现则与原厂行为一致。
5. **双时钟漂移是原厂就存在的固有问题**（main app-vsync vs launcher.anim SF-vsync），OPPO 用 `SurfaceTransactionApplier` 的 `mAsyncAnimStarted` 丢事务策略压制竞态。lib 层若允许双轨，需要同等的"异步接管后丢弃迟到同步写"保护。

## 9. 风险与疑点（取证中发现，供设计避雷）

1. **跨线程共享状态无锁**：动画线程与 UI 线程共享 `mSpringHolderMap` 等状态，靠"外层手工判线程"的纪律而非同步保护；`DynamicAnimation.isCurrentThread()`（`DynamicAnimation.java:531-533`）是恒真式（`Thread.currentThread() == Looper.myLooper().getThread()`），线程检查实际失效。
2. **`requestEnd` 下一帧生效**：帧源若停（SF vsync 不来），end 回调延迟；`CustomRectFSpringAnim.cancel()` 用先行 `maybeEnd()` 补救，形成双轨时序。
3. **`executeBlockWait`**（`OplusLooperExecutor.java:46-71`）：主线程硬等 executor 最多 5s——executor 堵死即 ANR 风险。
4. **binder / UI / UX_TASK 三线程共享容器**：`RemoteAnimationRunnerCompat$1` 内的 ArrayMap 只有一处 synchronized，其余裸奔。
5. **WeakReference/SoftReference 持有 factory**：被 GC 时静默回落 DEFAULT_FACTORY，动画瞬结、窗口跳变（`LauncherAnimationRunner.java:305,419-451` 的 `finalized=` 日志说明线上真发生过）。
6. **反编译噪声警示**：`LauncherAnimationRunner.java:429-430` 的三元表达式自相矛盾（`== null ? get() : null` 形态），是 JADX 反编译错误而非源码 bug；`Executors.java:84-87` 的 ThreadFactory `return null` 同理。读这份代码遇到逻辑矛盾先怀疑 JADX。

## 10. OPPO 引擎包的真实定位（防止再次误判）

`com/oplus/` 下六个"动画"包**没有一个自建动画线程**，全部挂 UI 线程 Choreographer，且大多与桌面转场主链路无关：

| 包 | 本质 | 线程 |
|---|---|---|
| `physicsengine` | 2D 弹簧物理（zoom 小窗拖拽、COUI 控件用；桌面图标没用） | UI 线程，固定步长（掉帧失真，刷新率只初始化时取一次） |
| `painteranimation` | ValueAnimator/SpringAnimation 薄封装 + "画板"设计工具反射钩子 | UI 线程 |
| `effectengine` | 一次性离屏 GPU 模糊（EGL），非动画循环 | 调用方线程（checkThread 强约束；壁纸模糊路径无线程切换，主线程触发即主线程 GL 阻塞） |
| `vfxsdk` | RuntimeShader(AGSL) 特效（搜索框光环） | UI 线程 Choreographer 自续约 |
| `anim` | Lottie 改名 fork（EffectiveAnimation） | UI 线程 |
| `animation` | 纯数学插值器 | — |

---

## 附录 A. v4 核心证据清单（人工复核过明文）

| 论断 | 证据 |
|---|---|
| launcher.anim 线程创建（-19） | `com/oplus/basecommon/thread/OplusExecutors.java:95` |
| SF-vsync provider + UX 线程标记 | `OplusExecutors.java:169-171`（import `android.animation.AnimationHandler` @ `:3`，`SfVsyncFrameCallbackProvider` @ `:5`） |
| 窗口弹簧默认异步 | `CustomRectFSpringAnim.java:252`（`mStartAsync = true`）、`:888`（executor 选择）、`:891`（isCurrentThread） |
| AsyncValueAnimator 线程重定向 | `AsyncValueAnimator.java:55,117-162`；用例 `AppLaunchAnimUtil.java:443` |
| 弹簧积分=直接调用+解析解 | `SpringHolder.java:47,117,130`；`SpringAnimReflectUtils.java:59-61`（直接调用）vs `:33-43`（仅 isAtEquilibrium 反射） |
| 远程动画 UI 编排 + 三段式收尾 | `LauncherAnimationRunner.java:529,242,98-113`；`QuickstepTransitionManager.java:489,1251` |
| 手势输入驱动模型 | `OplusBaseInputConsumerController.java:127`；`TouchInteractionService.java:652`；`SurfaceTransactionApplier.java:122,136` |
| 异步接管丢事务保护 | `SurfaceTransactionApplier.java:89-94` |

（调查方法：6 路独立 agent 分课题取证 + 载荷性论断人工 Grep 复核；全部内容经 ripgrep 明文通道穿透 DLP 加密获得，未做运行时验证。）
