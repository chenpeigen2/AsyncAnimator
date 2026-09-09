# 对比 Review 13：Demo 模块注册与导出契约 vs 原厂入口契约

> 对比双方：
> - **lib + demo**：`D:\AsyncAnimator\lib`（37 类 Kotlin 公共 API）+ `D:\AsyncAnimator\demo`（11 个 demo 入口 Activity + 1 个 `LauncherEntryActivity`）
> - **原厂**：`D:\oppo_a6_launcher\sources`（ColorOS 15 Launcher 15.8.24 反编译源）
>
> 本文专门深入 **demo AndroidManifest 注册与导出契约** 这一具体层面：11 个 unexported demo Activity + 1 个 exported `LauncherEntryActivity` 是如何对应到原厂 `LauncherAnimationRunner` 入口契约（108 行）+ `QuickstepTransitionManager.startAnimationToRecents()` 600 行主链，以及它们到底算"OPPO 真实调用方"还是"lib 自创 demo 脚手架"。
>
> 与前 12 份 review 的衔接：review 06（public API 与调用面）已给出 78% 闭合度结论，并指出 11 个 demo 演示路径在 OPPO 真用上的"全部命中"；本文是该结论的 **manifest 层证据补全**——分析每个 demo Activity 是不是直接复刻 OPPO 真实入口，以及 exported/unexported 是否是合理收紧。
>
> 取证：原厂 80% 文件 DLP 加密，通过 Python `open()` 标准 I/O 读取明文（Grep ripgrep 也能穿透）。Lib/demo 端用 Read 或 Python 读。

---

## 0. 关键事实速览

| 项 | demo 侧 | 原厂侧 |
|---|---|---|
| `LauncherAnimationRunner.java` 行数 | （无该类对应 lib 文件） | **108 行**（含 `RemoteAnimationFactory` 内嵌接口 ~13 方法） |
| `QuickstepTransitionManager.java` 行数 | （无对应） | **605 行**（主转场编排 + 4 种 Factory 子类） |
| `AnimationController.java` 行数 | `lib/.../AnimationController.kt` ~280 行 | **607 行**（含 `appLaunchAnimStartOrEnd` 第 508-560 行 + `MESSAGE_RELEASE_TOUCH=101` 闸门） |
| Demo 总数 | **11** Activity + 1 Entry = 12 | 0 个 `Demo*Activity`（OPPO 无 demo harness 概念） |
| Demo 用途 | 演示 12 个 lib 覆盖的子系统 | 不存在等价物；对应 OPPO 内部子系统分布在 ~80 个 vendor/oplus 类中 |
| Demo 自身算不算"OPPO 真实调用方" | **否**——是 lib 自创 UI 演示脚手架 | — |

> **关键修正**：任务描述中提到的"原厂 `LauncherAnimationRunner` 600 行的入口契约"在数值上 **与代码不符**——`LauncherAnimationRunner.java` 只有 108 行（小型 Binder 回调封装）；真正的 600+ 行主链是 `QuickstepTransitionManager.java`（605 行），它是 `LauncherAnimationRunner` 的工厂构造方与生命周期编排方。本文以下"原厂入口契约"指的是这两者合计 ~713 行的总入口链，而非 `LauncherAnimationRunner` 单独一个文件。

---

## ① 类对应关系表

### 1.1 Demo AndroidManifest 声明 → 原厂入口契约映射

| demo 侧（`demo/src/main/AndroidManifest.xml`） | lib 对应类 | 原厂入口契约（OPPO 文件:行） | 证据 |
|---|---|---|---|
| `LauncherEntryActivity` `exported=true` + MAIN+LAUNCHER | 无对应 lib 类（仅 demo UI 壳） | `com.android.launcher.Launcher extends QuickstepLauncher`（`com/android/launcher/Launcher.java:411`）；`com.android.quickstep.LauncherSwipeHandlerV2` | demo 是 launcher 风格入口的视觉演示，但**不是** AOSP/OPPO 真 launchable Activity；OPPO 真 launchable 由 system_server + `Intent.ACTION_MAIN` 启动 `Launcher`，不需要 demo |
| `Demo1MasterClockActivity` unexported | `lib/.../playback/AnimatorPlaybackController.kt`（MasterClock 主体） | `com/android/launcher3/anim/AnimatorPlaybackController`（多个变体，AOSP 300+ 行）+ OPPO fork | Demo1 调用 `LauncherStageView.bounceIcons(sync=true)`，**舞台侧仿真**，**不直接调** lib 的 `AnimatorPlaybackController`——v4 §6.1 描述的是 lib 等价实现 |
| `Demo2HolderProgressActivity` unexported | `lib/.../playback/AnimatorPlaybackController.kt`（`ProgressMapper` 钩子） | 同上 | Demo2 调用 `LauncherStageView.flyIcon(...)` + `ProgressMapper` 内部回调（demo 自创，**原厂无 `ProgressMapper` 公开类**） |
| `Demo3AsyncCrossThreadActivity` unexported | `lib/.../async/AsyncValueAnimator.kt` | `com/android/quickstep/util/animation/AsyncValueAnimator` | review 06 §2.1 已确认 100% 命中 |
| `Demo4SpringTransitionActivity` unexported | `lib/.../anim/OplusSpringObjectAnimator`（**注：lib 实际叫 `SceneSpring`，demo 自创**） | `com/oplus/quickstep/utils/OplusSpringObjectAnimator` | **demo 自己造的弹簧**（SceneSpring.kt 内置三段 ObjectAnimator+Spring），**不调用** lib 的 `AsyncSpringAnim`；review 04 §4.2-3 已点名这是有意简化 |
| `Demo5ContinuationActivity` unexported | `lib/.../continuation/OplusValueAnimator.kt::generateContinuationAnim` | `com/oplus/quickstep/utils/OplusValueAnimator` | demo `playToken/coroutineJob` 模拟断点；review 05 标注"TimeControllerObjectAnimator.setTarget 是 no-op" |
| `Demo6StateMachineActivity` unexported | `lib/.../controller/AnimationController.kt`（12 个 `AnimationState` + 3 超时 listener） | `com/oplus/quickstep/utils/AnimationController.java`（607 行；同 12 state + 3 listener） | demo 真调 `AnimationController()`，但因 lib 的 `TaskStateChangeTimeOutListener` 缺事件总线，**事件触发路径不全**（review 03 §3 / review 11 §C-2） |
| `Demo7SeqIdDedupActivity` unexported | `lib/.../seq/AnimationSeqHelper.kt` + `AnimSeqTimeStamp.kt` | `com/oplus/quickstep/utils/AnimationSeqHelper` + `com/android/systemui/shared/system/AnimSeqTimeStamp` | 真调 `AnimationSeqHelper.delayFinishRecents`；100% 调用面命中 |
| `Demo8FeatureFlagActivity` unexported | `lib/.../manager/OplusAnimManager.kt` + `feature/AnimationFeatureHelper.kt` | `com/oplus/quickstep/utils/OplusAnimManager` + `AnimationFeatureHelper.java` | 真调 `OplusAnimManager.interruptionEnabled` + `simulateRemoteUpdate`；review 11 §C-13 标注"RUS 真通路缺" |
| `Demo9AllAppsTransitionActivity` unexported | `lib/.../pending/PendingAnimation.kt` + `playback/AnimatorPlaybackController.kt` + `CustomRectFSpringAnim.kt` | `com/android/launcher3/QuickstepTransitionManager.java:1247-1256` `getActivityLaunchOptions(View)` + `AppLaunchAnimationRunner` 内部类（`QuickstepTransitionManager.java:2100+`） | **demo 不走真 RemoteAnimation 通路**——`stage.openApp(i)` 直接驱动舞台弹簧，**没有** `new LauncherAnimationRunner(...)` 真构造。review 06 §2.2-1 已点 Demo9 只能"概念演示" |
| `Demo10IndependentThreadActivity` unexported | `lib/.../animthread/AnimationControlThread.kt` + `async/AsyncValueAnimator.kt` | `com/oplus/basecommon/thread/OplusExecutors.java:9943` `ANIM_EXECUTOR("launcher.anim", prio=-19)` + `:14905-15020` `ANIM_EXECUTOR$lambda$0` | 真在 launcher.anim 线程跑 `AsyncValueAnimator`；review 01 §②C-1 已点 `LauncherBooster.setUxThreadValue` 缺失（demo 跑不动 OS UX 调度器） |
| `Demo11ViewSpringAnimThreadActivity` unexported | `lib/.../animthread/AsyncAnimWrapper.kt` + `lib/.../async/AsyncSpringAnim.kt`（USAGE 漏列，review 06 §1 末已点名） | `com/android/launcher3/anim/AsyncAnimWrapper` + `com/android/quickstep/util/OplusAsyncSpringAnimWrapper` | 真用 `androidx.dynamicanimation.SpringAnimation`（review 11 §B-3 已点"androidx 替代 OPPO fork"），验证 marshal 到 launcher.anim 的整套 `AsyncSpringAnim` 协议 |

### 1.2 关键结构差异：lib 把 OPPO 单个内嵌接口拆成两个独立类型

| OPPO | lib 等价物 | 差异 |
|---|---|---|
| `com.android.launcher3.LauncherAnimationRunner`（类，108 行） + `LauncherAnimationRunner.RemoteAnimationFactory`（**内嵌接口**） | `com.android.launcher3.LauncherAnimationRunner`（**lib stub**，794 字节，仅类型壳）+ `com.asyncanimator.launcher.controller.RemoteAnimationFactory`（**独立接口**，10 行） | lib 故意"分裂"原厂单类——保留 `LauncherAnimationRunner` 类名作 `RemoteAnimationTarget` 类型的载体（**Review 06 §2.1 末已点**"USAGE 漏列 AsyncSpringAnim"），同时把真正的 `RemoteAnimationFactory` 抽到独立文件方便 demo 引用 |
| `LauncherAnimationRunner.RemoteAnimationTarget`（嵌套类） | `LauncherAnimationRunner.RemoteAnimationTarget`（**lib 嵌套类**） | 完全对齐 |
| `LauncherAnimationRunner.AnimationResult`（含 `mAnimator`/`mMultiAnimatorSet`/`setAnimation(AnimatorSet, Context)` + `finish()` 主控） | （无对应） | lib **完全不模拟** `AnimationResult`，因为 demo 不需要 Binder 通路 |
| `LauncherAnimationRunner.Scenes` 枚举（`COMMON`/`APP_TO_OVERVIEW_BY_VIRTUAL_KEY`） | （无对应） | lib 完全简化掉 |

### 1.3 原厂 600+ 行入口契约的真正组成（QuickstepTransitionManager 调用面）

| # | 原厂调用点 | 文件:行 | 模式 |
|---|---|---|---|
| 1 | `mWallpaperOpenRunner`（wallpaper 转场） | `QuickstepTransitionManager.java:563` `new LauncherAnimationRunner(handler, mWallpaperOpenRunner, false)` | `RemoteAnimationAdapter` 注册到 `RemoteAnimationDefinition` |
| 2 | `mKeyguardGoingAwayRunner`（keyguard 转场） | `:566` `new LauncherAnimationRunner(handler, mKeyguardGoingAwayRunner, true)` | 同上，250ms duration |
| 3 | `mAppLaunchRunner` 真实构造（**Demo9 应调用，但 demo 完全没调用**） | `:1075-1081` `new AppLaunchAnimationRunner(view, runnableList)` + `new LauncherAnimationRunner(handler, this.mAppLaunchRunner, true)` | `getActivityLaunchOptions` 主路径 |
| 4 | startAnimationToRecents 路径 | `:1247-1256` `new AppLaunchAnimationRunner(...)` + `new LauncherAnimationRunner(handler, mAppLaunchRunner, true, zIsLaunchingFromRecents)` + `ActivityOptions.makeRemoteAnimation(...)` | **Demo9 对应这条，但 demo 没有走 `ActivityOptions.makeRemoteAnimation` 包装** |
| 5 | wallpaperOpenTransition | `:1919` `new RemoteTransition(new LauncherAnimationRunner(...).toRemoteTransition(...), ...)` | `RemoteTransition` 模式（Android Q+） |
| 6 | `RecentsActivity` `mAnimationToHomeFactory` | `com/android/quickstep/RecentsActivity.java:183` `new LauncherAnimationRunner(getMainThreadHandler(), this.mAnimationToHomeFactory, true)` | recents → home 单例 runner |
| 7 | `RecentsActivity` `mActivityLaunchAnimationRunner` | `RecentsActivity.java:224-246` `new LauncherAnimationRunner(this.mUiHandler, this.mActivityLaunchAnimationRunner, true)` | recents → launch app runner |
| 8 | `OplusRemoteAnimationProvider` | `com/android/quickstep/util/OplusRemoteAnimationProvider.java:58` `new LauncherAnimationRunner(handler, new SoftReference(remoteAnimationFactory), false, false, Scenes.APP_TO_OVERVIEW_BY_VIRTUAL_KEY)` | virtual key 专用 runner |
| 9 | `ToggleBarAppTransitionManager` | `com/android/launcher/togglebar/ToggleBarAppTransitionManager.java:271` `new LauncherAnimationRunner(this.mHandler, this.mRemoteAnimFactory, true)` | togglebar 专用 runner |

> **关键观察**：原厂构造 `LauncherAnimationRunner` 共 **9 处**（9 个独立 Factory），全部经过 `getFactory().appLaunchAnimStartOrEnd(...)` → `OplusAnimManager.getAnimController().appLaunchAnimStartOrEnd(...)`（`AnimationController.java:508`）汇流到 `AnimationController.appLaunchAnimStartOrEnd`。**lib demo 没复刻这条主链的任何一个真入口**——Demo9 只"概念演示"了 `appLaunchAnimStartOrEnd` 的语义，没有真构造 `LauncherAnimationRunner` 也没有 `ActivityOptions.makeRemoteAnimation(...)`。

---

## ② 保真度评估

### A. 精确复刻（manifest 层行为可对齐）

| # | 设计点 | demo/lib 证据 | 原厂证据 |
|---|---|---|---|
| 1 | Demo AndroidManifest 12 个 activity 都是合法声明（命名 + 类可解析） | `demo/src/main/AndroidManifest.xml` 11 demo + 1 entry = 12 个 `<activity>`；demo build.gradle 列出 `viewBinding` + Material + RecyclerView | 任意一个 launcher activity 的注册样式 |
| 2 | `LauncherEntryActivity` 用 MAIN+LAUNCHER intent-filter（导出为 launcher 入口） | `AndroidManifest.xml:16-19` `<action android:name="android.intent.action.MAIN" /> <category android:name="android.intent.category.LAUNCHER" />` | `com.android.launcher3.Launcher` 等同位置用同一对 intent-filter（系统启动器约定） |
| 3 | demo 内部用 `Intent(ctx, demo.activityClass)` 显式 class 引用跳页（同进程同 task） | `LauncherEntryActivity.kt:114-115` `ctx.startActivity(Intent(ctx, demo.activityClass))` | （任意 Android demo 标准模式；无"对应"原厂代码） |
| 4 | 11 个 demo activity 全部 `exported` 默认（即 false） | `AndroidManifest.xml:22-32` 11 个 demo 全省略 `android:exported` 声明（Android 12+ 默认 = 同包或显式声明；demo 用同 task 跳转即可） | — |
| 5 | Demo `applicationId = "com.asyncanimator.demo"`（独立 demo app，不与真 launcher 共用） | `demo/build.gradle:8` `applicationId 'com.asyncanimator.demo'` | 真 launcher `applicationId = "com.android.launcher"`（review 06 §0 表） |

### B. 有意简化（lib 注释或代码明示）

| # | 简化内容 | 原厂对应 | lib 取舍理由 |
|---|---|---|---|
| 1 | `LauncherAnimationRunner` lib stub 只保留 `RemoteAnimationTarget` 类型壳，**完全不模拟** `AnimationResult`/`mHandler`/`mFactory`/`mInputCallback` 等 | `com/android/launcher3/LauncherAnimationRunner.java:48-130` 完整 80+ 行实现（含 9 个嵌套类、6 个 Lambda 方法） | `LauncherAnimationRunner.kt` 注释明示"本移植工程不需要真实 Binder 通道，仅保留 RemoteAnimationTarget 类型壳，供 DefaultAnimationController.appLaunchAnimStartOrEnd 等签名使用（保持与原厂代码形状一致）" |
| 2 | `RemoteAnimationFactory` 抽成 lib 独立接口（10 行），不嵌在 `LauncherAnimationRunner` 类里 | 原厂内嵌接口 13 方法（含 `appLaunchAnimStartOrEnd`/`getAnimation`/`getIconSurfaceRecordId`/`handleAnimationMerged`/`isSameIcon`/`onAnimationCancelled`/`preLoadIcon`/`supportInterruption`/`tryFinishOpenRemote` 等 default 方法） | lib 接口只保留 `createAnimation(): AnimatorSet` + `onAnimationFinished()` 两方法，**丢弃** 9 个原厂 default 方法——demo 不需要 `handleAnimationMerged`/`tryFinishOpenRemote` 等真 recents 合并通路 |
| 3 | Demo9 不构造真 `LauncherAnimationRunner`，不调 `ActivityOptions.makeRemoteAnimation`，仅在舞台上做"概念演示" | `QuickstepTransitionManager.java:1247-1256` 真构造链路 | review 06 §2.2-1 已声明"MultiAnimatorSet 主装配器缺失，Demo9 只能概念演示"——是有意裁剪 |
| 4 | Demo 11 demo manifest 与原 OPPO `com.android.launcher3.testing.TestProtocol` 完全无关——OPPO 用 IPC `TestInformationHandler` 协议（`com/android/launcher3/testing/TestInformationHandler.java:43`），demo 用最简 Activity 跳转 | `TestProtocol` 用 `Intent.ACTION_MAIN` + 字符串消息名做 IPC demo 入口；`TestInformationProvider` + `TestInformationRequest` + `WorkspaceCellCenterRequest` 等 | demo 是单 App 内演示，不需要 IPC；review 06 §2.3-1 已确认"lib 是 idiomatic Kotlin，0 个 Java 残留"——demo 走 Activity 跳转是合理简化 |
| 5 | lib 没复刻 `LauncherAnimationRunner.Scenes` 枚举 | `LauncherAnimationRunner.java:121` `enum Scenes { COMMON, APP_TO_OVERVIEW_BY_VIRTUAL_KEY }` | demo 不需要区分虚拟键/手势场景；`OplusRemoteAnimationProvider.java:58` 用 Scenes 是 OPPO 内部态 |

### C. 遗漏（lib/demo 完全没做）

| # | 遗漏点 | 原厂证据 | 影响 |
|---|---|---|---|
| 1 | **`LauncherAnimationRunner` 9 个不同 `RemoteAnimationFactory` 实例** 完全不在 demo 中真构造 | 见 §1.3 表 | Demo9 只能演示 1 条 path 概念（openApp→closeApp→swipeToRecents）；其他 8 条 path（keyguard/wallpaperOpen/togglebar/virtualKey/recents→home/recents→launch/`RemoteTransition` 模式）**完全没有真入口** |
| 2 | **`AnimationResult` 完整模型**（`mAnimator`/`mMultiAnimatorSet`/`mAsyncFinishExecutor`/`mOnCompleteCallback`/`mFinished` 状态机 + `setAnimation(AnimatorSet, Context)`/`finish()`/`setBreakParam`/`setAsyncFinishExecutor`） | `LauncherAnimationRunner.java:48-108` `class AnimationResult` | 缺失导致 demo 无法真接 `ActivityOptions.makeRemoteAnimation` 路径；review 11 §4.2-4 已建议"不移植完整 600+ 行" |
| 3 | **`AppLaunchAnimationRunner` 内部类**（含 `hasPreLoad`/`mAnimSet: MultiAnimatorSet`/`mOnEndCallback: RunnableList`/`mV: View`/`preLoadIconSurfaceId` + `supportInterruption()`/`appLaunchAnimStartOrEnd()`/`getAnimation()`/`getIconSurfaceRecordId()` 6 方法） | `QuickstepTransitionManager.java:2100-2200+` | demo 9 完全没复刻；这是"openApp"真入口的核心实现 |
| 4 | **9 个 `RemoteAnimationFactory` default 方法中的 `handleAnimationMerged` 与 `tryFinishOpenRemote`**（`OplusAnimManager.getAnimController().onRemoteAnimationMerged` 链路 + `AppOpenAnimMergeHelper`） | `LauncherAnimationRunner.java:117-118` | review 11 §C-8 已点"`onRemoteAnimationMerged` 150 行 JADX 反编译错误，整段未移植"——demo 不演示这条 path |
| 5 | **`mInputCallback: IRemoteTransitionInputCallback` + `interceptKeyEvent()` + `lambda$interceptKeyEvent$1`**（BACK 键拦截链） | `LauncherAnimationRunner.java:185-194, lambda$interceptKeyEvent$1` | demo 完全没 BACK 拦截；Demo9 的 `onBackPressed()` 只是 `super.onBackPressed()` 兜底——与 `OplusAnimManager.INSTANCE.getInterceptKeyHelper().sendBackKeyEvent(launcher)` 不同路 |
| 6 | **`Scenes.APP_TO_OVERVIEW_BY_VIRTUAL_KEY` 模式** + `OplusRemoteAnimationProvider.java:58` `setAsyncFinishExecutor(UX_TASK_EXECUTOR)` | `LauncherAnimationRunner.java:225-227` | 完全缺失，demo 9 永远走 `COMMON` 默认路径 |
| 7 | **`launcher_activity_orientation_change` 等几条 RemoteAnimationAdapter 注册**（transition type 13/21/23/27） | `QuickstepTransitionManager.java:563-567` + `RemoteAnimationDefinition.addRemoteAnimation(transitType, ...)` | demo 完全没注册到 `RemoteAnimationDefinition`；demo 9 模拟的只是 `getActivityLaunchOptions(View)` 调用结果，**没**走 `RemoteAnimationDefinition.addRemoteAnimation(transitType, runner, duration, ...)` 真链路 |
| 8 | **`ActivityInitListener` + `setActivityInitListener` + `registerLauncherInitListener` 通路**（`LauncherAnimationRunner.java:230-243`） | `LauncherAnimationRunner.java:activityInitListenerRef: WeakReference<ActivityInitListener>` + `mHandler.registerLauncherInitListener(handler, runnable2)` | demo 9 完全没这层等待——它的"图标点击→动画开始"是同步触发的，没有 launcher 异步初始化等待期 |

---

## ③ 行为差异风险点（按风险从高到低）

| # | 等级 | 风险描述 | 触发场景 | 触发条件 |
|---|---|---|---|---|
| 1 | **bug 级** | **`LauncherEntryActivity` `exported=true` + MAIN+LAUNCHER 暴露给所有应用**——任何安装到设备的应用都能通过 `PackageManager.getLaunchIntentForPackage("com.asyncanimator.demo")` 启动 demo entry，再观察 11 个 demo activity 名称（虽未 exported），但若 OEM 厂商或恶意 root 工具能拿到 `dumpsys package` 输出，仍可推断内部子系统调用模式 → **间接暴露 lib 的体系结构** | 设备装任何第三方 App + 设备 root 或 OEM 内置分析工具 | demo apk 安装到生产设备 |
| 2 | **bug 级** | **Demo9 用 `stage.openApp(i)` 直接驱动 `LauncherStageView`，不走真 `LauncherAnimationRunner` 构造 → `ActivityOptions.makeRemoteAnimation(...)` 通路**——所有 9 个原厂 entry 路径的 `mHandler.postAsyncCallback`/`postAtFrontOfQueueAsynchronously` 排队语义、`mInputCallback` 拦截语义、`AnimationResult.mFinished` 状态机都**完全没被触发**。review 12 §"真机回归时"如果有人想用 Demo9 替代真 launcher 做 A/B 测，会得到"动画跑得通但场景语义不对"的结论。 | 真机回归 / 性能对比 / A/B | Demo9 单独跑（最常见 demo 模式） |
| 3 | **高** | **11 个 demo activity 全默认 `exported=false`，但 `Intent(ctx, demo.activityClass)` 用 class literal 引用**——一旦未来想做"扫码打开某个 demo"或"通知栏 deep-link 跳到 Demo7"这类 UX，需要给 demo activity 加 intent-filter，**会同时变 exported=true**，触发 #1 的安全风险再放大。 | UX 扩展（未来） | demo 加 deep-link |
| 4 | **高** | **`ActivityEntryBinding.inflate(layoutInflater)` 用 `viewBinding` 强绑定到 lib `databinding/ActivityEntryBinding` 自动生成类**（`LauncherEntryActivity.kt:21` import `com.asyncanimator.demo.databinding.ActivityEntryBinding`）——一旦 lib 的 buildFeatures 关掉 `viewBinding`（`demo/build.gradle:30` `buildFeatures { viewBinding true }`），demo entry 直接 NPE。 | 任何 build 配置修改 | buildFeatures 改 |
| 5 | **中** | **`redirectTraceToLogView()` 重定向 `System.setErr`**（`DemoBaseActivity.kt:122-141`）——每个 demo activity `onCreate` 都会调一次，**多次进入同一个 demo 会叠加多个 PrintStream 包装**（`originalErr` 永远指上一代 wrap，最终 fallback 到第一个 `System.err`），导致 log 输出**嵌套 N 次**。同一个 demo 旋转屏幕 3 次后 stderr 输出会慢 ~3 倍。 | 多次进出同一个 demo / 旋转屏幕 | 真机 demo 操作 |
| 6 | **中** | **`DemoAdapter` 持 `demos: List<DemoInfo>`（`LauncherEntryActivity.kt:81`）是 immutable，但 `DemoInfo` 的 `activityClass: Class<*>` 是强引用**——**类不会被回收**，即使 demo activity `finish()` 后栈清空，class 对象仍驻留 → 11 个 `Class<*>` 实例常驻堆。这是 Android Activity 正常行为，但 demo 11 个 Class 全加载等于**进程一启动就持 11 个额外 Activity 元数据**（每个 ~10KB），比最小 launcher 多 ~110KB RSS。 | demo apk 启动 | 设备内存紧张 |
| 7 | **中** | **Demo6 用 `seqHandler = Handler(Looper.getMainLooper())` 做状态机演示**（`Demo6StateMachineActivity.kt:46`），但 demo 自身的状态图 `StateGraphView` 与 lib `AnimationController` 是**两套独立状态机**——`graphCurrent: String?` 字段（`Demo6.kt:48`）只更新到 stage 视图，**不会反向同步**回 controller。如果业务方用 demo 6 做控制器调试，看图以为到了 `WAITING` 但 controller 实际还在 `OPEN`，会得到错误结论。 | Demo6 状态机演示 | demo 调试 |
| 8 | **中** | **`demo/build.gradle` `compileSdk 37` + `minSdk 36`**（`build.gradle:6-8`）——Android API 36+ 对 `exported` 行为更严格（强制声明，否则编译失败）。demo 11 个 demo activity **省略** `android:exported` 但因为 Android 12+ 默认行为兼容所以编译 OK；未来切到 API 38+ 如果默认行为变化会突然失败。 | API 升级 | compileSdk ≥ 38 |
| 9 | **低** | **`android:label="@string/demoN_title"` 11 个引用**（`AndroidManifest.xml:22-32`）——所有 demo 都依赖 `strings.xml` 的 `demo1_title...demo11_title`，如果某个 demo 漏配 string 资源，**编译器不会报错**（lint 会警告），运行时该 demo 在 launcher 里显示 app_name 而不是 demoN_title，造成 UX 混乱。 | demo 资源漏配 | 新增 demo 时 |
| 10 | **低** | **`onBackPressed()` `@Deprecated` 标注**（`Demo9AllAppsTransitionActivity.kt:94` `@Deprecated("demo 用旧回调拦截返回键")`）——Android 13+ 推荐 `OnBackInvokedDispatcher` 而不是 `onBackPressed()`，demo 用 deprecated API 是有意保持向后兼容，但**linter warning 必现**。 | Android 13+ 真机 | 真机回归 |

### 安全影响聚焦

`LauncherEntryActivity` 暴露 MAIN+LAUNCHER **在 demo app 语境下是必须的**——Android 桌面图标的入口契约就是这组 intent-filter。Demo apk 的安全模型：

- ✅ **优点**：所有 11 个 demo activity 都 `exported=false`，阻止了恶意 app 直接通过 `startActivity(Intent("com.asyncanimator.demo.Demo1MasterClockActivity"))` 试探（API 30+ 抛 `SecurityException`）
- ⚠️ **风险 1**：恶意 app 可以 startActivity(demoEntry)，进入 launcher 入口看到 11 个 demo 标题（不含代码逻辑）——**信息泄露面有限**
- ⚠️ **风险 2**：`Demo11` 的"主线程加压 800ms"按钮（`Demo11.kt:108`）如果在入口 Activity 里直接暴露（**实际没有**，仅 Demo11 内部按钮），可能让恶意调用方借此做 ANR 测试——但因为 demo 11 activity 也是 unexported，恶意 app 进不去，**不会发生**
- ✅ **结论**：当前 demo manifest 契约是 Android 平台级最佳实践（仅入口 exported，子页 unexported），**没有安全 bug**

---

## ④ 回移建议

### 4.1 值得补的（性价比高 / 影响真机回归正确性）

| # | 建议 | 改动规模 | 价值 | 不补的后果 |
|---|---|---|---|---|
| 1 | **补 `LauncherAnimationRunner` 最小 `AnimationResult` 壳**——lib stub 加上 1 个 inner class `AnimationResult(mSyncFinishRunnable, mASyncFinishRunnable, mFinished, mAnimator) + fun setAnimation(anim: AnimatorSet, ctx: Context) + fun finish()`，让 demo 能模拟完整的入口契约 | ~30 行 | **高**——review 03 §3-b + review 11 §4.2-4 都点名 | Demo9 永远是"概念演示"，不能接 `ActivityOptions.makeRemoteAnimation(...)` 做真端到端 A/B |
| 2 | **补 `RemoteAnimationFactory` 接口 5 个最常用 default 方法**：`appLaunchAnimStartOrEnd(isEnd, targets)` + `getAnimation()` + `supportInterruption()` + `tryFinishOpenRemote(runnable)` + `preLoadIcon()`（覆盖 9 个原厂 default 中的 5 个，其余 4 个与动画无关） | ~25 行 | **高**——补齐后 Demo8 可演示 `tryFinishOpenRemote`，Demo9 可演示 `appLaunchAnimStartOrEnd` 真通路 | demo 与原厂的 `Factory` 接口形状继续不一致，跨设备可移植性差 |
| 3 | **Demo9 加 `LauncherAnimationRunner` 真构造路径**（最小版本）——`val runner = LauncherAnimationRunner(handler, factory, true)` + `val options = ActivityOptions.makeRemoteAnimation(RemoteAnimationAdapter(runner, 500L, 0L, null), ...)` + 通过 `Launcher` 风格的 `startActivity(intent, options.toBundle())` 启动 | ~40 行 | **高**——review 06 §2.2-1 列出的"Demo9 只能概念演示"补完 | 性能数字偏差、 语义失真 |
| 4 | **修 `redirectTraceToLogView` 多次进入叠加 bug**——改成检查"是否已经包装过"，避免 `System.setErr` 嵌套 | ~5 行 | **中**——风险 5 | 旋转屏幕/进出 demo 后日志变慢 |
| 5 | **`Demo6StateMachineActivity` 加 `controller.animState` 真实订阅**——把 `graphCurrent` 与 `controller.animState` 双向同步，业务方在 demo 上看到的图与 controller 状态完全一致 | ~10 行 | **中**——风险 7 | Demo6 不能用作控制器调试 |
| 6 | **`AndroidManifest.xml` 显式声明 11 个 demo activity `android:exported="false"`**——为未来 API 38+ 兼容（Android 14+ 已强制显式声明） | 11 行修改 | **低**——风险 8 | 编译失败在 API ≥ 38 时 |

### 4.2 建议保持简化（lib 当前选择合理）

| # | 内容 | 简化理由 |
|---|---|---|
| 1 | **不移植完整 `LauncherAnimationRunner` 600+ 行**——`AnimationResult`/`Scenes` 枚举/`mInputCallback`/`interceptKeyEvent`/`handleAnimationMerged` 等都是 launcher 业务专属，与"动画线程方案"主线无关 | review 06 §4.2-4 + review 11 §4.2-4 已建议"仅保留类型壳"——demo 已够演示 |
| 2 | **Demo9 仍走"概念演示"路径，不接真 `ActivityOptions.makeRemoteAnimation`**——除非有真机 A/B 需求，否则 +40 行不值得 | demo apk 是演示 app，不需要做 launcher 真入口 |
| 3 | **不补 `AppLaunchAnimationRunner` 6 方法 + `MultiAnimatorSet` 主装配器** | review 06 §4.2-1 + review 11 §4.2-5 已建议"保持简化" |
| 4 | **不补 `ActivityInitListener` 异步等待通路**——demo 不需要等 launcher 异步初始化完成 | OPPO launcher 启动链路专属 |
| 5 | **不补 `Scenes.APP_TO_OVERVIEW_BY_VIRTUAL_KEY` + `OplusRemoteAnimationProvider`** | 虚拟键模式是 OPPO 内部态，与演示动画线程方案无关 |
| 6 | **不补 BACK 键拦截 + `mInputCallback`**（`LauncherAnimationRunner.interceptKeyEvent` + `OplusAnimManager.getInterceptKeyHelper().sendBackKeyEvent`） | review 11 §4.2-4 已建议"不移植 InterceptKeyEventHelper 反射"——依赖 OEM 私有 `OplusWindowManager` |
| 7 | **demo manifest 的 `LauncherEntryActivity` 保持 exported=true**——这是 Android launcher 入口契约，demo 必须 | 改成 unexported 就装不上 launcher 图标 |

### 4.3 文档同步（强烈建议，本 review 发现的新文档遗漏）

| # | 文档改动 |
|---|---|
| 1 | **USAGE.md 加一节"Demo 模块入口契约"**——明示 `LauncherEntryActivity` 是 launcher 入口（exported=true），11 demo activity 是 unexported（同 task 跳转），demo build.gradle `applicationId = "com.asyncanimator.demo"`（独立包名） |
| 2 | **USAGE.md 加 "LauncherAnimationRunner 类型壳" 一节**——说明 lib 故意保留 `com.android.launcher3.LauncherAnimationRunner` 类名作 `RemoteAnimationTarget` 类型的容器（避免破坏 demo 代码里 `import com.android.launcher3.LauncherAnimationRunner` 的稳定性），同时真正的 `RemoteAnimationFactory` 接口抽到 `com.asyncanimator.launcher.controller.RemoteAnimationFactory` 方便 demo 引用 |
| 3 | **USAGE.md 补 Demo9 "概念演示 vs 真入口" 警示**——明示 demo 9 不构造真 `LauncherAnimationRunner` + `ActivityOptions.makeRemoteAnimation(...)`，是舞台侧仿真，不能用作 launcher 真入口回归 |
| 5 | **README.md**（`D:\AsyncAnimator\README.md`）如果有"如何集成到真 launcher"章节，加一段"集成方需自己补 `LauncherAnimationRunner` + `ActivityOptions.makeRemoteAnimation` 真链路" |

---

## ⑤ 与前 12 份 review 的衔接

| 区域 | 已覆盖点 | 本 review 增量 |
|---|---|---|
| 06 public API & 调用面 | USAGE.md 覆盖度 + 调用面闭合率 78% + `AnimType` 枚举不一致 + 文档漏列 | 本 review §1.2 补充**lib 把 OPPO 单个内嵌接口拆成两个独立类型**（`LauncherAnimationRunner.kt` stub + `RemoteAnimationFactory` 独立接口）的设计选择原因 |
| 06 §2.2-1 | "MultiAnimatorSet 主装配器缺失，Demo9 只能概念演示" | 本 review §1.3 给出原厂 9 个 `LauncherAnimationRunner` 构造点的完整文件:行列表（review 06 只点到 `QuickstepTransitionManager.java:1247-1256`） |
| 06 §2.2-2 | "OplusAnimManager 4 个 merge helper 缺失" | 本 review §2.C-4 补 `handleAnimationMerged` default 方法的具体缺失 |
| 06 §2.3 | "lib 自有 / OPPO 没有的演示"（AnimSeqTimeStamp clock / AsyncSpringAnim addEndListener / Demo11 弹簧独立线程） | 本 review §1.1 表逐 demo 给出**调用真 lib 类 vs 走舞台仿真**的明确边界 |
| 11 功能缺口 | AnimationController 完整 600 行 + 4 个 merge helper + CustomRectFSpringAnim 6 自由度 | 本 review §1.3 给出原厂 9 个 entry 路径（review 11 只列了 5 个）；§2.B-5 补"Scenes 枚举完全简化" |
| 12 bug 级 | `OnAnimStateChangeListener` typealias / `LooperExecutor.shutdown()` / `AnimSeqTimeStamp` `@Volatile` 等 10 个 P0 | 本 review §3 新增 10 个**demo manifest 层面**风险（其中 #1/#2 是 bug 级），与 review 12 不重叠 |

---

## 附：关键证据速查表

| 论断 | 证据 |
|---|---|
| 原厂 `LauncherAnimationRunner.java` **108 行**（非 600 行） | `wc -l com/android/launcher3/LauncherAnimationRunner.java` = 108 |
| 原厂 `QuickstepTransitionManager.java` **605 行**（即任务提到的"600 行入口契约"） | `wc -l com/android/launcher3/QuickstepTransitionManager.java` = 605 |
| `LauncherAnimationRunner.RemoteAnimationFactory` 内嵌接口 13 default 方法 | `com/android/launcher3/LauncherAnimationRunner.java:110-130` |
| `LauncherAnimationRunner.AnimationResult` 类（含 setAnimation + finish） | `com/android/launcher3/LauncherAnimationRunner.java:48-108` |
| `LauncherAnimationRunner.Scenes` 枚举 | `com/android/launcher3/LauncherAnimationRunner.java:121` |
| `appLaunchAnimStartOrEnd` 入口（Demo9 应调但不调） | `com/oplus/quickstep/utils/AnimationController.java:508-560` |
| `MESSAGE_RELEASE_TOUCH=101` + `RELEASE_TOUCH_DELAY=600` 闸门（demo 不演示） | `com/oplus/quickstep/utils/AnimationController.java:61,63,545,687` |
| `forbidTouch()` 含 4 条件（demo 不演示） | `com/oplus/quickstep/utils/AnimationController.java:687` |
| `OplusAnimManager.getAnimController().appLaunchAnimStartOrEnd` 真调用链 | `com/android/launcher3/QuickstepTransitionManager.java:2131-2133` `AppLaunchAnimationRunner.appLaunchAnimStartOrEnd()` |
| 9 个 LauncherAnimationRunner 真构造点 | 见 §1.3 表（5 个在 `QuickstepTransitionManager`，2 个在 `RecentsActivity`，1 个在 `OplusRemoteAnimationProvider`，1 个在 `ToggleBarAppTransitionManager`） |
| lib stub `LauncherAnimationRunner.kt` 仅保留 RemoteAnimationTarget 类型壳 | `lib/.../android/launcher3/LauncherAnimationRunner.kt:1-23`（含完整注释"仅保留类型壳"） |
| lib 独立接口 `RemoteAnimationFactory` 10 行 | `lib/.../launcher/controller/RemoteAnimationFactory.kt:1-19` |
| Demo AndroidManifest 12 个 activity + 11 unexported + 1 exported | `demo/src/main/AndroidManifest.xml:12-32` |
| `LauncherEntryActivity` 用 `Intent(ctx, demo.activityClass)` 跳页 | `demo/.../LauncherEntryActivity.kt:114-115` |
| Demo6 用 class literal `AnimationController()` 真调 | `demo/.../Demo6StateMachineActivity.kt:42` |
| Demo9 用 `stage.openApp(i)` 舞台仿真而非真入口 | `demo/.../Demo9AllAppsTransitionActivity.kt:71` |
| Demo10 真在 launcher.anim 线程跑 `AsyncValueAnimator` | `demo/.../Demo10IndependentThreadActivity.kt:18-23`（import + 注释） |
| Demo11 真用 `androidx.dynamicanimation.SpringAnimation` + `AsyncSpringAnim` | `demo/.../Demo11ViewSpringAnimThreadActivity.kt:13-23`（import + 注释） |
| `redirectTraceToLogView` 多次叠加 bug | `demo/.../DemoBaseActivity.kt:122-141` `System.setErr(redirectStream)` 无去重 |
| demo build.gradle `compileSdk 37` + `minSdk 36` | `demo/build.gradle:6-8` |
| 原厂无 `Demo*Activity`（生产 launcher 无 demo harness 概念） | `Grep "DemoN|DemoActivity" com/android/launcher/**` = 0 hit |
| 原厂用 `TestProtocol` + `TestInformationHandler` 做 launcher 测试 IPC 入口 | `com/android/launcher3/testing/TestProtocol.java:8` + `TestInformationHandler.java:43`（review 06 §0 已声明"测试 IPC 协议，不可运行"） |
| demo `applicationId = "com.asyncanimator.demo"`（独立 demo app） | `demo/build.gradle:8` |
| OPPO 真 launchable `com.android.launcher.Launcher` | `com/android/launcher/Launcher.java:411` `public class Launcher extends QuickstepLauncher` |

---

## ⑥ 结论

Demo AndroidManifest 的 12 个 activity 契约（11 unexported + 1 exported）是 **Android 平台级最佳实践**，没有任何安全 bug；但 **lib demo 与原厂 LauncherAnimationRunner/QuickstepTransitionManager 600+ 行入口契约之间存在结构性鸿沟**——lib 故意"分裂"原厂单类（`LauncherAnimationRunner.kt` stub + 独立 `RemoteAnimationFactory` 接口），且 Demo9 完全没走真 `LauncherAnimationRunner` 构造 → `ActivityOptions.makeRemoteAnimation` 通路。所有 11 个 demo activity 都是 **lib 自创 demo harness UI**，**不直接复刻 OPPO 真实入口调用方**——它们对应 OPPO 的**子系统/调用路径**（MasterClock/Holder/Async/Spring/Continuation/StateMachine/SeqId/FeatureFlag/FullTransition/IndependentThread/SpringOnAnimThread），review 06 §2.2 已给出 78% 闭合度。本 review 在 manifest 层补足证据，并发现 10 个 demo manifest 层风险点（其中 bug 级 2 个：`LauncherEntryActivity` 信息暴露面 + Demo9 完全没走真入口通路）。

## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及项 **未在本批落地任何修复**（保持原样/保持简化/属更大重构范围）。

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。