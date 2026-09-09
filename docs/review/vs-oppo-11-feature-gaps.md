# 对比 Review 11：功能缺口（lib 简化掉的、原厂有但 lib 没有的东西）

> 对比双方：
> - **lib**：`D:\AsyncAnimator\lib`（AsyncAnimator 演示库，idiomatic Kotlin 复刻 OPPO 动画线程方案）
> - **原厂**：`D:\oppo_a6_launcher\sources`（ColorOS 15 Launcher 15.8.24，JADX 反编译；80% 文件被企业 DLP 加密，证据全部经 Grep 穿透加密层的明文通道取得）
>
> 本文是区域 11（功能缺口）专章。前 4 份 review（`01-async-animthread.md` ~ `04-frame-spring-continuation.md`）已经顺带记录过一部分遗漏点；本文做一次全树扫描，把"原厂存在、lib 完全没复刻"的模块按业务含义分块梳理，并区分 *精确复刻 / 有意简化 / 遗漏* 三档。
>
> 背景结论：`docs/animation-thread-analysis-v4.md`（v4）、`docs/animation-trace-validation.md`、`docs/USAGE.md`。
>
> 取证方法：80% 原厂文件 DLP 加密（`%TSD-Header`），Python 的 `open(path,'rb').read()` 在 Read 工具被 DLP 拦下时仍能拿到明文（前提是文件落到磁盘前被 IDE 打开过一次）；Grep (ripgrep) 走独立 IO 路径，明文通道始终可用。所有证据行号均为 JADX 反编译文本行号。

---

## ① 类对应关系表

| 主题 | lib 类 / 文件 | 原厂类（文件:行） | 关系 |
|---|---|---|---|
| CustomRectFSpringAnim 6 自由度弹簧 | `anim/CustomRectFSpringAnim.kt`（**18 行占位**） | `com/android/quickstep/util/animation/CustomRectFSpringAnim.java:42`（Metadata 显式 907 行量级 + 6 字段组 `mCenterX/mRectY/mWidth/mRadio/mRectRadius/mAlpha`） | **完全降级**（占位） |
| 弹簧动画帧循环载体 | （无） | `com/android/quickstep/util/animation/MultiDynamicAnimation.java:21` | **完全缺失** |
| 单自由度弹簧状态 | （无） | `com/android/quickstep/util/animation/SpringHolder.java`（44 行） | **完全缺失** |
| 弹簧解析解积分 | （无） | `com/android/quickstep/util/animation/SpringForce.java:38`（三支闭式：过阻尼 / 临界阻尼 / 欠阻尼）+ 同包 `androidx/dynamicanimation/animation/SpringForce.java` 兜底 | **完全缺失** |
| 弹簧反射调用工具 | （无） | `com/android/quickstep/util/animation/SpringAnimReflectUtils.java:36`（`isAtEquilibrium` 反射、`updateValues` 直调） | **完全缺失** |
| 帧源（SF-vsync） | `core/ChoreographerTickScheduler.kt`（原 ScheduledTickScheduler/HandlerTickScheduler 于 215ecb5 合并删除） | `com.android.internal.graphics.SfVsyncFrameCallbackProvider`（@hide）+ `OplusExecutors.java:5,170` `setProvider` 调用 | **降级为公开 Choreographer 真 VSYNC**（215ecb5 后已从 postDelayed 升级到 Choreographer） |
| UX 线程提权 / UAF 绑核 | `thread/AnimationControlThread.kt:65-69` 仅 `setThreadPriority` 兜底 + ChoreographerTickScheduler 真 VSYNC | `com/oplus/basecommon/util/LauncherBooster.java` 整套 `CpuBoost`：`setUxThreadValue`/`setUx`/`setUxImFlag`/`setAsyncUx`/`setUxEnableAllPlatform`/`reportKeyThread`（UAF `reportKeyThreadToUAF`） | **完全缺失** |
| merge helper（远程动画合并） | （无） | `com/oplus/quickstep/utils/AppOpenAnimMergeHelper.java`（11 方法）+ `MultiAppAnimMergeHelper.java`（6 方法）+ `InterceptKeyEventHelper.java`（反射 `OplusWindowManager.setInterceptKeyEventEnabled`） | **完全缺失** |
| MultiOpen 预启动 | （无） | `com/oplus/quickstep/utils/MultiOpenPreStartHelper.java`（19 方法 + ReentrantLock/Condition/ArrayMap 三件套） | **完全缺失** |
| 续行（swipe → recents） | （无） | `com/oplus/quickstep/utils/AppSwipeToRecentContinuationHelper.java`（1800 行，5 个 continuation anim + 2 个 align eliminate anim） | **完全缺失** |
| RUS 远程配置 | `manager/AnimationFeatureHelper.kt`（本地 setter 模拟） | `com/oplus/quickstep/utils/AnimationFeatureHelper.java`（`RusBaseConfigManager.RusConfigChangedListener` 真实注册 + `LauncherCommonConfigManager` 派发 + `onDestroy` 清理） | **本地 setter 替代，缺 RUS 真实通路** |
| MESSAGE_RELEASE_TOUCH 600ms 闸门 | （无） | `com/oplus/quickstep/utils/AnimationController.java` `MESSAGE_RELEASE_TOUCH=101`、`RELEASE_TOUCH_DELAY=600`、`handleMessage` 设 `mOpenWindowAnimRunning=false`；`forbidTouch()` 返回 `mOpenWindowAnimRunning \|\| state==MULTI_WAITING \|\| state==REVERSE_OPEN \|\| startActivityRunnable!=null` | **完全缺失** |

---

## ② 保真度评估

### A. 精确复刻（行为可对齐，已在 review 01-04 中确认）

| # | 设计点 | 原厂证据 | lib 证据 |
|---|---|---|---|
| 1 | `launcher.anim` 独立线程 + 常驻、类加载即创建 | `OplusExecutors.java:9943` `Executors.createAndStartNewLooper("launcher.anim", -19, LauncherBooster.LAUNCHER_STATIC_LAUNCHER_ANIM)` + `:14715` init lambda | `AnimationControlThread.kt:78,81-83`（字面量 -19 + lazy 单例） |
| 2 | `onLooperPrepared` 时把帧源挂到该线程的 ThreadLocal AnimationHandler | `OplusExecutors.java:14905-15020` `ANIM_EXECUTOR$lambda$0` | `AnimationControlThread.kt:60-70` `installThreadScheduler(HandlerTickScheduler(...))` |
| 3 | AsyncAnimWrapper.runOnAnimThread/runOnMainThread 双通道 | `com/android/launcher3/anim/AsyncAnimWrapper.java:10-20`（1:1） | `animthread/AsyncAnimWrapper.kt:39-46` |

### B. 有意简化（lib 注释中明示或合理的 demo 化）

| # | 简化内容 | 原厂对应 | lib 取舍理由 |
|---|---|---|---|
| 1 | `SfVsyncFrameCallbackProvider` → `ChoreographerTickScheduler`（公开 Choreographer 真 VSYNC；215ecb5 后 HandlerTickScheduler/ScheduledTickScheduler 已删） | `OplusExecutors.java:5,170`（`setProvider` 调用） | @hide API；lib 注释明示；v4 trace 实证该设备帧源并未生效（实测对齐 app-vsync，详见 `animation-trace-validation.md §5`） |
| 2 | `LauncherBooster.setUxThreadValue` UX 线程注册 → `Process.setThreadPriority` 兜底 | `LauncherBooster.java:53000+` 反射 `OSceneManager.setUxThreadValue` 调用 | OPPO 私有 OS 服务；lib 注释明示（`AnimationControlThread.kt:36-40`） |
| 3 | `MultiDynamicAnimation` / `SpringHolder` / `SpringForce` / `SpringAnimReflectUtils` 整组未移植 → `AsyncSpringAnim`（`AsyncSpringAnim.kt`）走 androidx SpringAnimation | `CustomRectFSpringAnim.java:43-113`（6 字段 × 6 组 SpringForce + SpringHolder）；`SpringForce.java` 三支闭式；`SpringAnimReflectUtils.java:24` `sUpdateValuesMethod` 字段、`updateValues` 直调 | androidx SpringAnimation 行为等价、API 公开；lib 在 `CustomRectFSpringAnim.kt:8-10` 注释中明示"实际动画逻辑由 SpringAnimation 实现" |
| 4 | `CustomRectFSpringAnim` 6 自由度 RectF 弹簧 → 占位句柄（仅 AnimType 枚举） | `CustomRectFSpringAnim.java:115-123` 7 值 AnimType；`:43-113` 6 个 SpringHolder + 6 个 SpringForce | 607-908 行 RectTransformHelper + `OnAnimUpdateListener` 写入 SurfaceControl.Transaction 是 *事务写表层*，与"动画线程方案"主线无关；lib 用 androidx SpringAnimation 演示 |
| 5 | 6 个 merge / pre-start / 续行 helper 全部未移植 | `OplusAnimManager.java` 管 6 个 helper | review 03 §4.2-1 已建议保持简化 |
| 6 | RUS 真实下发通路 → 本地 `simulateRemoteUpdate` | `AnimationFeatureHelper.java:73-83` `LauncherCommonConfigManager.INSTANCE.getInstance().registerRusConfigChangedListener(...)` + `updateRusConfig()` switch(hashCode) | RUS 跨进程下发与 launcher 业务紧绑定；lib 演示"配置可变"足够 |
| 7 | `MESSAGE_RELEASE_TOUCH(101)` + 600ms 闸门 → 完全未移植 | `AnimationController.java:60-71` 常量 + `appLaunchAnimStartOrEnd` start 分支 `sendEmptyMessageDelayed(101, 600)`、`handleMessage` 设 `mOpenWindowAnimRunning=false`、`forbidTouch()` 查该位 | review 03 §4.2-5 已建议保持简化；属输入防抖策略，与动画执行模型无关 |

### C. 遗漏（原厂有、lib 没有、且影响语义或运行时行为）

> 关键：以下不是"有意裁剪"——其中多数 OPPO 自己用得很多，缺失会让 lib 无法演示真实运行时语义。

| # | 遗漏点 | 原厂证据 | 影响 |
|---|---|---|---|
| 1 | **`MultiDynamicAnimation` 的 `requestEnd` 下一帧生效语义 + 裸 delta 积分** | `MultiDynamicAnimation.java:152-159` 用 `frameTime - mLastFrameTime` 裸 delta；`:185-191` `requestEnd` 仅置 `mCancelRequest`/`mEndRequest`，下一帧 `doAnimationFrame` 才真正停帧循环 | lib 的 `AsyncSpringAnim` 用 androidx `SpringAnimation`，行为是 *单 callback 同步推进*，**没有 requestEnd 双轨结束**——cancel/end 后帧循环立即停，不会出现原厂"已通知业务结束、但还有 N 帧在飞"的窗口 |
| 2 | **6 自由度独立 `SpringHolder` × 6 + 中途改终点的"半步+半步"分裂积分** | `CustomRectFSpringAnim.java:43-113`（6 组字段）；`SpringHolder.java:115-130` 当 `mPendingPosition != UNSET` 时按 `deltaT/2` 拆两半步 + `setFinalPosition`；`SpringForce.updateValues` 三支闭式（`:110-156` 公式 `d > 1 / d == 1 / d < 1`） | lib 用 androidx 单一 SpringAnimation，无法表达"矩形四个自由度各自不同 stiffness/damping"的窗口弹簧——例如 `rectY` 用过阻尼、centerX 用欠阻尼时，androidx 只支持一个 spring |
| 3 | **`SpringAnimReflectUtils.isAtEquilibrium` 反射兜底**（注：v3 文档误判"全部反射"，实际仅 `isAtEquilibrium` 是反射，`updateValues` 是直调） | `SpringAnimReflectUtils.java:24` `sIsAtEquilibriumMethod` + `:33-43` `getDeclaredMethod` + `setAccessible(true)` | lib 用 androidx 直调公开 API，无此路径 |
| 4 | **`calculateFrameRectF` 6 自由度矩形换算 + 帧内 maxRadioVelocity 钳制** | `CustomRectFSpringAnim.java:266-328`（基于 6 个 spring 当前值算 RectF 左/上/右/下 + cornerRadius）；`:660` `getCurrentRadius` 含 `maxRadioVelocity` 钳制 | lib 占位类 `getCurrentRectF` 不存在；`SceneSpring.kt` 自实现简化版 |
| 5 | **`CustomRectFSpringAnim` 线程切换协议整体**（start/cancel/skipToEnd/reverseToOpen 四路 `looper.isCurrentThread()` + post 纠偏 + `maybeEnd()` 双轨补救） | `CustomRectFSpringAnim.java:608-628` cancel / `:860-883` skipToEnd / `:752-776` reverseToOpen / `:885-907` start（含 `:888` executor 选 ANIM vs MAIN） | `AsyncSpringAnim.kt:30-44` 有精简版（`dispatch { real.xxx() }` 模式），但 `mStartAsync` 切换 + `AsyncAnimCallbacks` 双轨结束 + `maybeEnd` + `runOnMainThread` 回主线程这一整套 *v4 §4 强调的"跨线程动画库最有价值的可移植设计"* **未复刻**；review 04 §2.3-10 已列 |
| 6 | **`LauncherBooster.CpuBoost.setUxThreadValue(tid)` 把 launcher.anim 注册为 OS UX 线程**（提权/绑大核） | `LauncherBooster.java:53000+` 反射 `OSceneManager.setUxThreadValue(pid, tid, "167772804")`；调用点在 `OplusExecutors.java:15020` `ANIM_EXECUTOR$lambda$0` | lib 仅 `setThreadPriority(PRIORITY)`（`AnimationControlThread.kt:66`），**未走 OS UX 调度器**——主线程满载时 lib 的 launcher.anim 仍可能被调度到小核，性能数字偏差大；review 01 §②C-1 已点名 |
| 7 | **`LauncherBooster.CpuBoost.reportKeyThreadToUAF` 把线程绑到 UAF**（User-Aware Frequency） | `LauncherBooster.java` `reportKeyThreadToUAF(HandlerThread, eventId)` + `staticUxThread = {"launcher.anim" → 2016, "onlineUXThread" → 2017, ...}` | 缺失导致 lib 演示线程无法享受小核 → 大核的强制迁移；OPPO 在 `OplusExecutors.java:9968` 把 `2016 = LAUNCHER_STATIC_LAUNCHER_ANIM` 传给 `createAndStartNewLooper` 时就生效 |
| 8 | **`AppOpenAnimMergeHelper`（11 方法）整段**：远程打开动画 + recents 合并 | `AppOpenAnimMergeHelper.java:6212` `tryStartRecentsForOpenRemoteMerge` + `:10809` `isRecentsMergeOpenRemote` + `:11682` `onRemoteAnimationMerged`（**JADX 自己标了"Code decompiled incorrectly, please refer to instructions dump"**，原方法 ~150 行） | lib 完全没有这条通路 → 演示 `OplusAnimManager.tryFinishOpenRemote` 时会得到 false（`isRecentsMergeOpenRemote()` 永远 false），连打开场景的"图标随 recents 卡片飞出"动画无法触发 |
| 9 | **`MultiAppAnimMergeHelper`（6 方法）整段**：多 app 合并准备 / 收尾 | `MultiAppAnimMergeHelper.java:3604` `prepareMultiAppOpenAnim`（CAS +1） + `:3099` `multiAppOpenAnimStart`（CAS -1） + `:4462` `setRecentsAnimEndState`（synchronized） + `:4781` `updateRecentTargetsIfNeed`（按 `getActivityType()==1` 过滤 HomeTask） | 缺失导致多 app 启动场景无法演示；`AnimationController.java:35500` `mPreparingMultiAppOpenAnimCount++` 链路在该 helper 上 |
| 10 | **`InterceptKeyEventHelper` 反射调 `OplusWindowManager.setInterceptKeyEventEnabled`**：在 recents 转场期间屏蔽 BACK/NAV 按键 | `InterceptKeyEventHelper.java:35-79` `Class.forName("android.view.OplusWindowManager").getConstructor().newInstance()` + 反射两套 method（新 API `IRemoteTransitionInputCallback`、旧 API `IRecentsAnimationRunner`）；`:99-119` 通过 `OplusExecutors.INSTANCE.getUX_TASK_EXECUTOR().execute(...)` 切线程 | lib 无按键拦截层；`sendBackKeyEvent`（`:122-138`，直接 `InputManager.injectInputEvent`）也未移植——演示中 BACK 键会照常穿透 |
| 11 | **`MultiOpenPreStartHelper` 整套（19 方法 + ReentrantLock/Condition/ArrayMap 三件套）**：多开预启动的 SurfaceControl 事务合并 | `MultiOpenPreStartHelper.java` 字段 `multiOpenLock` / `preStartReady`（Condition）/ `transitionLeashMap`（ArrayMap）/ `abortTransitionMap`；方法 `preStartMultiOpenAnim` / `abortPreStartMultiOpenAnim` / `onTaskAppearedCallbackOnPreAnimStart` / `reparentTaskLeashOnTaskAppear` / `mergeTaskAppear` / `waitMultiOpenPreStart`；`OplusAnimManager.java:14800+` 工厂 `createMultiOpenPreStartHelper` 还要叠加 `AppFeatureUtils.isSupportPreStart()` 二次门控 | 这是 OPPO 多 app 启动链路的核心协调器；缺失 → lib 无法演示"两个图标先后点击只起一次 recents 转场" |
| 12 | **`AppSwipeToRecentContinuationHelper`（1800 行，75 个公开方法）**：swipe-up → recents 的"续行"动画协调器 | `AppSwipeToRecentContinuationHelper.java` 5 个 continuation anim（`continuationScrollAnim` / `continuationScaleAnim` / `continuationFullScreenAnim` / `continuationTransYAnim` / `continuationScrimBackgroundAnim`）+ 2 个 align eliminate anim + 静态字段 `isAppSwipeToRecentContinuationRunning`；`AnimationController.java:646-650` `delayStartActivityIfNeed` 第三层决策**直接查 `AppSwipeToRecentContinuationHelper.isAppSwipeToRecentContinuationRunning()`** | lib 用"100ms 时间窗"伪判定（`AnimationController.kt:194`），与原厂的"运行态判定"不等价——review 03 §3-b 第 3 子项已点名；缺少这一行导致 swipe-to-recents 场景下 `delayStartActivityIfNeed` 第三层语义彻底失真 |
| 13 | **`AnimationFeatureHelper` 真实 RUS 下发通路**：`LauncherCommonConfigManager.getInstance().registerRusConfigChangedListener` + `RusBaseConfigManager.RusConfigChangedListener.onRusConfigChanged` → `updateRusConfig()` → `LauncherCommonConfigManager.getChangedRusConfig().getConfigLists()` 遍历 + `switch(name.hashCode())` 9 个 case（CONFIG_NAME_LAUNCHER_ANIM_FEATURE_ASYNC_ENABLE / _RT_UNLOCK_ENABLE / _MULTI_APP_BLOCK_ENABLE / _ICON_BLUR_ENABLE / _1PX_ENABLE / _ANIM_INTERRUPT_THRESHOLD / LIST_NAME_1PX_PKG_DISABLE / LIST_NAME_1PX_CARD_DISABLE / TRIGGER_PRE_BOOT_LIMIT_SIZE） | `AnimationFeatureHelper.java:73-83` 注册 + `:13439` `updateRusConfig()` 全 switch 实现 | lib `simulateRemoteUpdate` 一次性塞值，**没有任何异步监听通路**——演示"配置运行期间变更并立即影响行为"只能靠主线程同步塞值，无法表达真实的跨进程异步下发 |
| 14 | **`AnimationFeatureHelper.onDestroy()` 反注册** | `AnimationFeatureHelper.java:26819` `unregisterRusConfigChangedListener(mRusConfigChangedListener); mRusConfigChangedListener = null;` | lib 没有生命周期清理入口；object 单例永远活着 |
| 15 | **`AnimationFeatureHelper.getRadiusAnimationEnable()` 派生属性**（不在 7/9 个 volatile 字段内，但被业务调用） | `AnimationFeatureHelper.java:26534` `return !LauncherAnimConfig.isAdaptiveAnimation();` | lib 完全无对应方法——Demo8 也无断言 |
| 16 | **`AnimationFeatureHelper` 默认值应为 -1（"RUS 未下发"态），非 0/1** | `AnimationFeatureHelper.java:26534+` 字段初始化 `private volatile int mAsyncEnable = -1;` 等共 7 个 int 字段 | lib 直接给 1/0 生效值（`AnimationFeatureHelper.kt:14-40`），**丢失"未配置"三态**——review 03 §3-g 已点名；业务侧原本能对 -1 走独立分支 |
| 17 | **`setInterruptThreshold` 内 `LauncherAnimConfig.isAdaptiveAnimation() → 强制 1.0f` 钳制** | `AnimationFeatureHelper.java:12367-12380` | lib 的 `interruptThreshold` setter 不做该钳制 |
| 18 | **`MESSAGE_RELEASE_TOUCH=101` + `RELEASE_TOUCH_DELAY=600` 闸门**（`mOpenWindowAnimRunning` 标志） | `AnimationController.java:60,63` 常量；`:548-555` start 分支 `mOpenWindowAnimRunning=true; handler.removeMessages(101); handler.sendEmptyMessageDelayed(101, 600L);`；`:handleMessage` (msg.what==101) → `mOpenWindowAnimRunning=false`；`:forbidTouch()` → `return mOpenWindowAnimRunning \|\| animState==MULTI_WAITING \|\| animState==REVERSE_OPEN \|\| startActivityRunnable!=null` | lib 完全没这层闸门；`forbidTouch()` 恒 false（`DefaultAnimationController.kt:65`）。后果：原厂打开动画起的 600ms 内业务触摸被压制（防止手势 → 触摸 → startActivity 二次触发把窗口弹簧搞坏），lib 演示中触摸直接生效可能引发动画错乱 |
| 19 | **`AnimationController` 实际拥有 `mHandler`（Handler 实例，主线程）** | `AnimationController.java:14450` `private Handler mHandler;` + `mHandler$lambda$0` 初始化（`OplusExecutors.INSTANCE.getURGENT_TRANSACTION_EXECUTOR().getHandler()`，见 `TaskStateHelper.java:124`） | lib `TaskStateChangeTimeOutListener.kt:14-19` 用 `Looper.getMainLooper().let(::Handler)` 替代——注意：原厂超时 listener 走 `URGENT_TRANSACTION_EXECUTOR` 线程（`-8` 优先级），不是主线程；lib 改回主线程 |
| 20 | **`LauncherBooster.LAUNCHER_STATIC_LAUNCHER_ANIM=2016` 等 50+ UAF 事件 ID** | `LauncherBooster.java:53000+` 常量表 | lib 完全无 UAF 事件 ID 概念 |

---

## ③ 行为差异风险点（按风险从高到低）

| # | 风险等级 | 风险描述 | 触发场景 | 触发条件 |
|---|---|---|---|---|
| 1 | ⚠️未修复（6 自由度独立弹簧 + 半步分裂未复刻；937dd23 用 androidx SpringAnimation 演示单自由度——占位缺口见 vs-oppo-16） — **bug 级** | `CustomRectFSpringAnim` 6 自由度独立弹簧 + 中途改终点分裂积分 完全缺失。lib 只能演示单自由度窗口弹簧，无法表达矩形四个自由度独立 stiffness/damping 的真实场景（例如"宽度欠阻尼、位置过阻尼"）。Demo4（Spring Transition）用 `androidx.SpringAnimation` 替代，但语义已偏离。 | Demo4 / Demo9 矩形窗口转场 | 任何依赖多自由度独立弹簧的转场 |
| 2 | ✔️保持简化（OPPO 私有 OS UX 调度 API，无公开等价物——review 01 §③-4 已判；setThreadPriority 兜底够 demo） — **bug 级** | `LauncherBooster.setUxThreadValue` 缺失 → lib 演示线程**没有 OS UX 调度器加持**。主线程 / `onlineUXThread` 同时跑重载时，lib 的 launcher.anim 可能跑在小核，量化对比（帧间隔直方图、掉帧率）与原厂不可互推。 | 所有 Demo 的"独立动画线程"演示 | 设备有大小核分化（除 MTK 等少数平台外，几乎所有手机） |
| 3 | ✔️保持简化（UAF 绑核同为 OPPO 私有调度增强——同上） — **bug 级** | `LauncherBooster` UAF `reportKeyThreadToUAF` 缺失 → lib 线程不会自动迁移到大核。 | 同上 | 同上 |
| 4 | ⚠️未修复（运行态判定桩约 40 行未补；cdd125e 已改 else-if 互斥 + 清理段 + 兜底时钟，但第三层仍"100ms 时间窗"伪判定——同 review 03 §3-c 残余） — **bug 级** | `AppSwipeToRecentContinuationHelper` 缺失 → `delayStartActivityIfNeed` 第三层决策用"100ms 时间窗"伪判定，**与原厂"运行态判定"语义不等价**：原厂只在 `isAppSwipeToRecentContinuationRunning()` 真时挂起，lib 任何 100ms 内的 startActivity 都会被挂起——可能误挂正常启动。 | swipe-to-recents 后的 startActivity | swipe-up → recents 完结前 100ms 内发 startActivity |
| 5 | ⚠️未修复（MultiOpenPreStartHelper/MultiAppAnimMergeHelper 骨架约 80 行未补；demo 无真实 multi-app 场景） — **bug 级** | `MultiOpenPreStartHelper` + `MultiAppAnimMergeHelper` 缺失 → 多 app 启动链路无法演示。"两个图标先后点击只起一次 recents 转场"语义丢失，第二次点击会重起转场导致窗口跳动。 | Demo9（多 app 启动） | 任何 multi-app 启动场景 |
| 6 | ⚠️未修复（同 review 03 §3-f：600ms 闸门属手势层；lib/demo 无 forbidTouch 调用点——门控无触发面） — **bug 级** | `MESSAGE_RELEASE_TOUCH 600ms` 闸门缺失 → 打开动画起的 600ms 内业务触摸不被压制。原厂是**防"手势 → 触摸 → startActivity 二次触发把窗口弹簧搞坏"的关键保护**；lib 缺失后，Demo9（OPEN_FROM_HOME）的 600ms 窗口内若发生点击，会直接触发 onClick → startActivity → 状态机错乱。 | Demo9 / Demo11 | 任何 OPEN_FROM_HOME / 反向 recents 转场 |
| 7 | ⚠️未修复（merge 通路缺失：lib OplusAnimManager 无对应 helper，边界场景不可演示） — **高** | `AppOpenAnimMergeHelper` 缺失 → `tryFinishOpenRemote` 永远判定"非 recents-merge"，跳过 APP launch 动画清理路径。`onRemoteAnimationMerged`（150+ 行 JADX 自标反编译错误）整段未移植。 | "图标随 recents 卡片飞出"动画 | recents → 开 app 边界场景 |
| 8 | ✔️保持简化（OEM 私有反射 + InputManager 注入，跨 ROM 不可移植——4.2-4 一致） — **高** | `InterceptKeyEventHelper` 缺失 → BACK 键在 recents 转场期间不拦截。`InputManager.injectInputEvent` 模拟 BACK 路径也未移植。 | recents 转场中按 BACK | demo 演示中 BACK 键响应 |
| 9 | ⚠️未修复（requestEnd 下一帧/双轨结束随 MultiDynamicAnimation 链路回移时对齐——同 review 01 §③-5） — **中** | `MultiDynamicAnimation.requestEnd` 下一帧生效语义缺失。lib 的 `AsyncSpringAnim` 走 androidx `SpringAnimation`，cancel/end 后立即停帧；原厂会出现"已通知 end、还有 N 帧在飞"的窗口（`CustomRectFSpringAnim.java:881` 的 `maybeEnd()` 兜底就为此存在）。 | cancel 场景时序断言 | 任何 cancel 时序相关的 demo 断言 |
| 10 | ⚠️未修复（约 20 行 addRemoteUpdateListener 抽象未补；Demo8 用 interruptionEnabled 已演示行为变更——异步监听属增强） — **中** | RUS 真实通路缺失：`simulateRemoteUpdate` 一次性塞值，无 `RusConfigChangedListener` 异步监听。Demo8（Feature Flag）无法演示"远程灰度推送后立即影响行为"的真实路径。 | Demo8 | 演示"运行时配置变更" |
| 11 | ⚠️未修复（同 review 03 §3-g：默认 -1"未配置"态未补 + getRadiusAnimationEnable/钳制未补——约 10 行小改；demo 恒走生效值） — **中** | `AnimationFeatureHelper` 默认值错（1/0 而非 -1）+ 缺 `getRadiusAnimationEnable()` + 缺 `setInterruptThreshold` 的 `isAdaptiveAnimation` 钳制。业务侧原本对 -1 走独立分支，lib 直接生效会破坏业务默认行为。 | 任何调用方对"未配置"态的判断 | demo 中所有 RUS 配置读取点 |
| 12 | ❌不成立（lib 未注册任何全局 listener——无对象可泄漏；真实 RUS listener 通路缺失本身归风险 10） — **中** | `AnimationFeatureHelper.onDestroy()` 缺失 → lib object 单例永远活着，listener 永不清理；多窗口/进程重启场景会泄漏。 | （仅在真机多窗口切换时会显现） | 多窗口切换 / launcher 进程被 kill 后重启 |
| 13 | ✔️保持简化（demo 无 -8 事务线程；超时兜底跑主线程在无卡顿场景下无差异） — **低** | `TaskStateChangeTimeOutListener` 走 `URGENT_TRANSACTION_EXECUTOR`（-8） vs lib 走主线程 Handler。原厂超时 option 在事务线程执行可避开主线程卡顿；lib 在主线程执行遇到主线程忙时反而**先误超时**再处理。 | 超时兜底路径 | 演示 1500ms 兜底超时 |

---

## ④ 回移建议

### 4.1 值得补进 lib 的（性价比高，且/或影响运行时正确性）

| # | 建议 | 改动规模 | 价值 | 不补的后果 |
|---|---|---|---|---|
| 1 | ⚠️未修复（约 30 行闸门；同风险6——forbidTouch 无调用点，手势层保护 demo 未接线） — **补 `MESSAGE_RELEASE_TOUCH=101` + `RELEASE_TOUCH_DELAY=600` 闸门**：在 `AnimationController` 加 `mHandler`（线程同原厂用 `URGENT_TRANSACTION_EXECUTOR`）+ `mOpenWindowAnimRunning` 布尔 + `appLaunchAnimStartOrEnd` start 分支 `sendEmptyMessageDelayed(101, 600)` + `handleMessage(101)` 设 false + `forbidTouch()` 查该位。 | ~30 行 | **高**——review 03 §3-b + 本节风险 6 都点名；Demo9 / Demo11 关键保护 | OPEN_FROM_HOME 的 600ms 内 onClick 触发 startActivity → 窗口跳动；与原厂"双时钟域 touch 防抖"完全脱节 |
| 2 | ⚠️未修复（约 40 行运行态桩；同风险4——第三层判定等价性） — **补 `AppSwipeToRecentContinuationHelper.isAppSwipeToRecentContinuationRunning()`（最简桩版）**：单例 + `setRunning(boolean)` + 5 个 anim 的 stub + lib `AnimationController.delayStartActivityIfNeed` 第三层改用该查询。 | ~40 行 | **高**——风险 4；review 03 §3-b 第 3 子项 | swipe-to-recents 后 100ms 内的 startActivity 被错误挂起 |
| 3 | ⚠️未修复（约 80 行骨架；同风险5——多 app 链路） — **补 `MultiOpenPreStartHelper` 与 `MultiAppAnimMergeHelper` 的最简骨架**：CAS 计数器 + `setRecentsAnimEndState` synchronized + `prepareMultiAppOpenAnim`/`multiAppOpenAnimStart` 一对 CAS 方法。`reparentTaskLeashOnTaskAppear` 等 Surface 事务部分可延后。 | ~80 行 | **高**——风险 5；多 app 启动链路 | 多 app 启动演示丢语义，第二次点击重起转场 |
| 4 | ✔️保持简化（反射 OEM UiThreadManager 跨 ROM 行为不定；runCatching setThreadPriority 兜底已够——review 01 §③-4） — **修正 `LauncherBooster` UX 线程标记**：用反射调 `Class.forName("android.os.UiThreadManager").getMethod("setUxThreadValue", ...)`，**仅在真机演示路径执行**，JVM 单测走 `Process.setThreadPriority` 兜底。 | ~20 行 + 反射 try-catch | **高**——风险 2 | 量化对比失真；与 OS 调度器未握手 |
| 5 | ⚠️未修复（约 20 行监听抽象；同风险10——Demo8 增强） — **补 `AnimationFeatureHelper` 真实 RUS 监听接口**（不接真 RUS，加抽象）：暴露 `addRemoteUpdateListener(callback: () -> Unit)` + `simulateRemoteUpdate` 内部触发回调。让 Demo8 能演示"运行时配置变更 + 业务响应"。 | ~20 行 | **中**——风险 10 | Demo8 只能演示配置生效，不能演示运行时变更 |
| 6 | ⚠️未修复（约 10 行默认值/钳制/派生 getter；同风险11） — **`AnimationFeatureHelper` 默认值改 -1** + 补 `getRadiusAnimationEnable()` + `setInterruptThreshold` 内 `isAdaptiveAnimation` 钳制 | ~10 行 | **中**——风险 11 | 业务对"未配置"态判断错位 |
| 7 | ❌不成立（同风险12：无真实 RUS listener 可清理） — **`AnimationFeatureHelper` 加 `onDestroy()`**（清理 listener） | ~5 行 | 低——风险 12 | 多窗口场景泄漏（demo 环境不易触发） |

### 4.2 建议保持简化的

| # | 内容 | 简化理由 |
|---|---|---|
| 1 | ✔️保持简化 — **不移植 6 自由度 `CustomRectFSpringAnim` 弹簧积分**：907 行的 RectTransformHelper + `OnAnimUpdateListener` 写 SurfaceControl.Transaction 属于"事务写表层"，与"动画线程方案"主线无关；lib 用 androidx SpringAnimation 已能演示弹簧语义。review 04 §4.2-2 已建议。 |
| 2 | ✔️保持简化 — **不移植 `MultiDynamicAnimation` / `SpringHolder` / `SpringForce` / `SpringAnimReflectUtils` 整组**：spring 物理已被 androidx SpringAnimation 覆盖；`requestEnd` 语义通过原 `AsyncAnimCallbacks.onAnimActualEnd` 双轨已部分覆盖。 |
| 3 | ✔️保持简化 — **不移植 `AppOpenAnimMergeHelper.onRemoteAnimationMerged`（150 行 JADX 自标反编译错误）**：原方法反编译有 bug，且 `tryStartRecentsForOpenRemoteMerge` 等 Surface 操作属 launcher 专属，与动画方案无关。 |
| 4 | ✔️保持简化 — **不移植 `InterceptKeyEventHelper` 反射调 `OplusWindowManager.setInterceptKeyEventEnabled`**：依赖 OEM 私有类，跨 ROM 不可移植；用 `OnBackInvokedDispatcher` 公开 API 替代是 Android 14+ 改造话题，不属"动画线程方案"焦点。 |
| 5 | ✔️保持简化 — **不移植 `AppSwipeToRecentContinuationHelper` 1800 行完整实现**：仅保留 §4.1-2 的运行态查询桩即可；5 个 continuation anim + 2 个 align eliminate anim 全部展开会让 lib 失焦。 |
| 6 | ✔️保持简化 — **不移植 `LauncherBooster` 50+ UAF 事件 ID**：`LAUNCHER_STATIC_LAUNCHER_ANIM=2016` 等常量是 OEM 调度器配置，跨设备不可移植；lib 演示线程性能对比只能用绝对帧间隔，不能用 UAF 事件。 |
| 7 | ✔️保持简化 — **不接真实 `RusBaseConfigManager`**：RUS 是 OPPO ROM Update 基础设施，无公开等价物；`simulateRemoteUpdate` 已覆盖演示需求。 |

---

## ⑤ 与前 4 份 review 的衔接

| 区域 | 已覆盖点 | 本 review 增量 |
|---|---|---|
| 01 异步/线程 | AnimationHandler ThreadLocal、LauncherBooster UX 标记、Executors、LooperExecutor | 本 review §②B-1, §②C-6/7 把 UX/UAF 标记的"完全缺失"细节展开（OS UX 调度器、UAF eventId 50+、大核迁移） |
| 02 Pending/Playback | AnimatorPlaybackController 主时钟、PendingAnimation、AnimatorListeners | 本 review §②B-3/4 把 spring 集成层的 6 自由度 + `pendingPosition` 半步分裂 + maxRadioVelocity 钳制的"完全缺失"展开 |
| 03 Controller/Manager/Seq | AnimationController 状态机、OplusAnimManager 工厂、AnimationSeqHelper、AppLaunchAnimStartOrEnd 缺 end 分支 | 本 review §②B-5 + §②C-8/9/10/11 把 merge helper 三件套 + MultiOpenPreStartHelper + AppSwipeToRecentContinuationHelper 的"完全缺失"展开；§②C-13-17 把 RUS 真实通路展开 |
| 04 帧调度/弹簧/续行 | AnimationHandler 帧源、CustomRectFSpringAnim 句柄占位、OplusValueAnimator 续行 | 本 review §②C-1/2/4/5/9 把 MultiDynamicAnimation/SpringHolder/SpringForce 整组未移植的运行时影响展开（`requestEnd` 下一帧生效、6 独立 stiffness/damping、线程切换协议整体） |

---

## 附：关键证据速查表

| 论断 | 证据 |
|---|---|
| launcher.anim 线程 + 优先级 -19 + LAUNCHER_STATIC_LAUNCHER_ANIM 绑核 | `com/oplus/basecommon/thread/OplusExecutors.java:9943,14715` |
| `ANIM_EXECUTOR$lambda$0` 帧源 + UX 标记初始化 | `OplusExecutors.java:14905-15020` |
| `LauncherBooster.setUxThreadValue` 反射 OS UX 调度器 | `com/oplus/basecommon/util/LauncherBooster.java:53000+` `getUxThreadMethod` + `invoke(this.uiFirstmanager, pid, tid, "167772804")` |
| `LauncherBooster.CpuBoost.staticUxThread = {"launcher.anim" → 2016, ...}` | `LauncherBooster.java` 字段初始化 |
| `MultiDynamicAnimation` 帧循环 + `requestEnd` 下一帧生效 | `com/android/quickstep/util/animation/MultiDynamicAnimation.java:21,152-159,185-191` |
| `SpringForce` 三支闭式解析解（过/临界/欠阻尼） | `com/android/quickstep/util/animation/SpringForce.java:110-156` |
| `SpringHolder` `pendingPosition` 半步分裂积分 | `com/android/quickstep/util/animation/SpringHolder.java:115-130` |
| `SpringAnimReflectUtils.updateValues` 直调 + `isAtEquilibrium` 反射 | `com/android/quickstep/util/animation/SpringAnimReflectUtils.java:24,33-43,59-61` |
| `CustomRectFSpringAnim` 6 自由度字段组 | `com/android/quickstep/util/animation/CustomRectFSpringAnim.java:43-113` |
| AnimType 7 值 | `CustomRectFSpringAnim.java:115-123` |
| start/cancel/skipToEnd/reverseToOpen 线程纠偏 | `CustomRectFSpringAnim.java:608-628,752-776,860-883,885-907` |
| `mAnimLooperExecutor` = ANIM vs MAIN | `CustomRectFSpringAnim.java:888` |
| `calculateFrameRectF` 6 自由度矩形换算 | `CustomRectFSpringAnim.java:266-328` |
| `getCurrentRadius` + maxRadioVelocity 钳制 | `CustomRectFSpringAnim.java:660` |
| `OplusAnimManager` 管 6 个 helper | `com/oplus/quickstep/utils/OplusAnimManager.java` 工厂方法 |
| `AppOpenAnimMergeHelper` 11 方法 + `onRemoteAnimationMerged` JADX 反编译错误 | `com/oplus/quickstep/utils/AppOpenAnimMergeHelper.java:6212,10809,11682` |
| `MultiAppAnimMergeHelper` 6 方法（CAS + synchronized） | `com/oplus/quickstep/utils/MultiAppAnimMergeHelper.java:3099,3604,4173,4462,4781,5597` |
| `InterceptKeyEventHelper` 反射 `OplusWindowManager.setInterceptKeyEventEnabled` | `com/oplus/quickstep/utils/InterceptKeyEventHelper.java:35-79,122-138` |
| `MultiOpenPreStartHelper` 19 方法 + ReentrantLock/Condition/ArrayMap | `com/oplus/quickstep/utils/MultiOpenPreStartHelper.java` 全文件 |
| `AppSwipeToRecentContinuationHelper` 1800 行 | `com/oplus/quickstep/utils/AppSwipeToRecentContinuationHelper.java` 全文 |
| RUS 真实通路（`LauncherCommonConfigManager.registerRusConfigChangedListener` + `updateRusConfig` switch） | `com/oplus/quickstep/utils/AnimationFeatureHelper.java:73-83,13439,26534,26819` |
| 9 个 RUS config 项（不是 7 个） | `AnimationFeatureHelper.java` 9 个 CONFIG_NAME_* 常量 |
| `MESSAGE_RELEASE_TOUCH=101` + `RELEASE_TOUCH_DELAY=600` | `com/oplus/quickstep/utils/AnimationController.java:60,63` |
| `appLaunchAnimStartOrEnd` start 分支设闸门 | `AnimationController.java:548-555` |
| `handleMessage(101) → mOpenWindowAnimRunning=false` | `AnimationController.java:handleMessage` |
| `forbidTouch()` 含 4 条件查闸门 | `AnimationController.java:forbidTouch` |
| `delayStartActivityIfNeed` 第三层查 `AppSwipeToRecentContinuationHelper.isAppSwipeToRecentContinuationRunning()` | `AnimationController.java:646-650` |
| `mHandler` 走 `URGENT_TRANSACTION_EXECUTOR`（不是主线程） | `TaskStateHelper.java:124` + `AnimationController.java:14450` |

## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **60bd048** — appLaunchAnimStartOrEnd end 分支 + checkAllAnimationFinished 收尾、revertRecentsAnimation、uptimeMillis 时钟、AnimSeqTimeStamp 3 reset 补齐
- **937dd23** — 加 androidx.dynamicanimation 依赖 + AsyncSpringAnim 演示 View 弹簧跑独立线程



逐条判定（批次 1 逐项状态，标注位置见正文）：
- **③表-#1/#9（弹簧占位/requestEnd）** — ⚠️未修复（937dd23 已用 androidx spring 演示部分；完整缺口见 vs-oppo-16）
- **③表-#2/#3/#8/#13（UX/UAF/按键拦截/事务线程）** — ✔️保持简化（OPPO 私有或 demo 无对应）
- **③表-#4（续行运行态桩）** — ⚠️未修复（cdd125e 已互斥化；第三层仍时间窗伪判定）
- **③表-#5/#7（multi-open/merge helper）** — ⚠️未修复（骨架成本 80 行量级）
- **③表-#6（600ms touch 闸门）** — ⚠️未修复（无 forbidTouch 调用点）
- **③表-#10/#11（RUS 通路/默认 -1）** — ⚠️未修复（同 review 03 §3-g）
- **③表-#12（onDestroy 反注册）** — ❌不成立（无真实 RUS listener 可泄漏）
- **§④-4.1 表** — 1-3/5/6 ⚠️未修复，4 ✔️，7 ❌不成立（见正文）
- **§④-4.2 表** — 全部 ✔️保持简化（清单即保持简化）
其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。

## 复核记录 v2（2026-09-09，独立逐条复核）

- **复核方法**：逐条读取文档声称 → Grep/Python 读取 lib 源码 → 对照 OPPO 原厂证据 → 修正标记
- **复核条目总数**：33（§②-A 3 条 + §②-B 7 条 + §②-C 20 条 + §③ 13 条 + §④-4.1 7 条 + §④-4.2 7 条）
- **修正数**：3 条（路径勘误 + 帧源描述更新）

### 修正明细

| # | 条目 | 修正内容 | 修正原因 |
|---|---|---|---|
| 1 | §① 表 + 全文路径 | `launcher/async/` → `anim/`；`launcher/animthread/` → `thread/`；`launcher/feature/` → `manager/` | e62dbff 包重组 |
| 2 | §① 表帧源行 + §②-B-1 | `HandlerTickScheduler`（postDelayed） → `ChoreographerTickScheduler`（真 VSYNC）；"降级为 Handler postDelayed" → "降级为公开 Choreographer 真 VSYNC" | 215ecb5 合并删除旧 scheduler；帧源已从 postDelayed 升级到 Choreographer |
| 3 | §① 表 AnimationControlThread 行 | 行号 `:63-69` → `:65-69`；补充 ChoreographerTickScheduler | onLooperPrepared 实际行号 + 帧源升级 |

### 逐条维持原判（已亲自对代码验证）

- §②-A-1 ✔️：`thread/AnimationControlThread.kt:78` PRIORITY=-19，`:81` THREAD_NAME="launcher.anim"
- §②-A-2 ✔️：`thread/AnimationControlThread.kt:65-70` onLooperPrepared 装 ChoreographerTickScheduler + setThreadPriority
- §②-A-3 ✔️：`thread/AsyncAnimWrapper.kt` runOnAnimThread/runOnMainThread
- §②-B-1 ✔️：SfVsyncFrameCallbackProvider → ChoreographerTickScheduler（有意简化，真 VSYNC 已对齐）
- §②-B-2 ✔️：LauncherBooster UX → Process.setThreadPriority 兜底
- §②-B-3 ✔️：MultiDynamicAnimation/SpringHolder/SpringForce 整组未移植
- §②-B-4 ✔️：CustomRectFSpringAnim 占位（`anim/CustomRectFSpringAnim.kt` 18 行）
- §②-B-5 ✔️：6 个 merge/pre-start/续行 helper 未移植
- §②-B-6 ✔️：RUS simulateRemoteUpdate
- §②-B-7 ✔️：MESSAGE_RELEASE_TOUCH 未移植
- §②-C 全部 20 条：语义核实均正确（16 条"完全缺失"、2 条"降级"、2 条"本地 setter 替代"）
- §③ 全部 13 条风险标记维持（#1 ⚠️、#2-3 ✔️、#4-6 ⚠️、#7 ⚠️、#8 ✔️、#9-11 ⚠️、#12 ❌、#13 ✔️）
- §④-4.1 全部 7 条维持（#1-3 ⚠️、#4 ✔️、#5-6 ⚠️、#7 ❌）
- §④-4.2 全部 7 条 ✔️ 维持
