# AsyncAnimator

一个用于学习 Launcher 动画调度、跨 Looper 调用和转场编排的 **Kotlin Android 多模块示例工程**。项目包含动画机制库 `lib` 和带 12 个交互页面的演示应用 `demo`，参考 OPPO Launcher 动画机制分析进行简化实现。

**项目不是完整 Launcher，也不是 OPPO 动画框架的等价移植。** Demo 中既有直接调用 `lib` 的实验，也有自绘场景演示；画面、日志中的原厂术语不代表已接入对应系统能力。下面按当前代码区分两者。

## 当前状态

- **39/39 份 review 已按顺序验收**：适用建议已实现并验证，其余逐项说明不采纳或保留的理由。完成的是本地建议验收，不是完整 OPPO 系统移植。
- **61 个测试类、540 个独立用例**：库 501 + Demo 6 + API 生成器 33；2026-09-10 库与 Demo 的 Debug/Release 四组及生成器测试通过。
- **12 个 Demo 页面**：区分真实库接线和 Canvas 概念演示，不把动画计算间隔当屏幕呈现 FPS。
- **未验证范围仍保留**：设备/Perfetto、干净 SDK 环境复现及 release 压缩/签名；Lint 因缺少离线依赖未完成；本次 Release AAR 验证同样受依赖下载阻塞。

[API 使用指南](docs/USAGE.md) · [注解与编译期 API 文档](docs/public-api.md) · [构建环境](docs/build-environment.md) · [39 份验收记录](docs/review/2026-09-09-ordered-review-progress.md)

## 项目结构

```text
AsyncAnimator/
├── lib/                              # Android Library，不是纯 JVM 库
│   ├── src/main/java/
│   │   ├── com/asyncanimator/
│   │   │   ├── api/                  # 对外 API 注解 PublicApi
│   │   │   ├── core/                 # 自有帧调度器、ThreadLocal AnimationHandler、Trace
│   │   │   ├── thread/               # launcher.anim、LooperExecutor、AsyncAnimWrapper
│   │   │   ├── anim/                 # 异步动画包装、续行动画、六轴 Rect driver 与句柄
│   │   │   ├── playback/             # PendingAnimation、统一进度播放控制
│   │   │   ├── control/              # 动画状态、超时与 Recents 编排
│   │   │   ├── seq/                  # 序列号、时间窗口与延迟去重
│   │   │   └── manager/              # 实现切换与本地配置模拟
│   │   └── com/android/launcher3/    # LauncherAnimationRunner 类型桩
│   └── src/test/java/                # JUnit 4 本地单元测试
├── api-doc-processor/                # 构建期 KSP 注解文档生成器（不进入运行时）
├── demo/
│   ├── src/test/java/                # Demo 日志转发器的纯 JVM JUnit 回归
│   ├── src/main/java/com/asyncanimator/
│   │   ├── LauncherEntryActivity.kt  # 入口文件；包名为 com.asyncanimator.demo
│   │   └── demo/
│   │       ├── Demo*Activity.kt       # 12 个页面及 DemoBaseActivity
│   │       ├── scene/                # 自绘桌面、SceneSpring、SceneClock、图形素材
│   │       └── widget/               # 曲线、线程泳道、状态图、帧间隔直方图
│   └── src/main/res/                 # 主题、字符串、图标等 Android 资源
├── docs/                             # 使用说明、构建环境、原厂分析与 review 记录
├── scripts/check-build-environment.ps1 # 只读 JDK/SDK 前置检查（Windows）
├── gradle/libs.versions.toml          # 依赖与插件版本
├── lib/build.gradle.kts              # 库模块配置（Kotlin DSL）
├── demo/build.gradle                 # 应用模块配置（Groovy DSL）
└── AGENTS.md                         # 贡献与代码修改约定
```

演示页面采用 View 系统，主要通过代码创建布局和自绘 Canvas 场景，依赖 AppCompat、Material Components 等组件；不是 Compose 工程。

## 对外 API 注解与自动文档

使用 `@PublicApi` 显式标记对外声明，编译时由 KSP 收集签名及中文 KDoc。当前覆盖 **30 个源码文件、389 个公开声明**，不再限于最初的 10 项示例。全库扫描会拒绝新增公开 API 漏标，类型上的标记不自动替成员补标；误标非公开声明或缺少中文注释也会导致生成失败。

```powershell
.\gradlew.bat :lib:generatePublicApiDocs
```

文档输出到`lib/build/docs/public-api/debug/public-api.md`和`release/public-api.md`，正常的对应变体Kotlin编译也会自动生成。完整覆盖范围、内部实现排除边界与维护要求见[说明](docs/public-api.md)。

## 库实现与线程模型

| 模块 | 当前实现 |
|---|---|
| `thread` | `Executors` 提供主线程及 `launcher.anim` 执行器；`AnimationControlThread` 使用 `HandlerThread`，初始化时安装自有线程帧调度器，并尝试设置线程优先级。 |
| `core` | 自有 `AnimationHandler` 管理帧回调，`ChoreographerTickScheduler` 使用公开 `Choreographer` 订阅帧；不是 JVM 定时器模拟。 |
| `anim` | `AsyncValueAnimator` 将 `start/cancel/end` 转发到目标 Looper；`AsyncSpringAnim` 包装 AndroidX `SpringAnimation` 的生命周期调用；`OplusValueAnimator` 包含简化的进度续行逻辑；`MultiAnimatorSet` 聚合 main/async AnimatorSet、View springs 和 rect driver 的独立结束条件；`RectSpringDriver` 用公开 AndroidX scheduler 运行六轴数值弹簧。 |
| `playback` | `PendingAnimation` 组装属性动画，`AnimatorPlaybackController` 用主 `ValueAnimator` 的进度驱动各 `Holder`，支持正向、反向和手动设置进度。 |
| `control` / `seq` | `AnimationController` 维护动画状态与三类超时监听；`AnimationSeqHelper` 配合时间戳实现序列控制和延迟请求去重。 |
| `manager` | `OplusAnimManager.interruptionEnabled` 切换具体实现和 `Default*`；`AnimationFeatureHelper.simulateRemoteUpdate()` 仅模拟配置下发。 |

### 跨线程调用不等于所有回调都回主线程

`AsyncValueAnimator.executor` 默认是 `MAIN_EXECUTOR`，可显式改为 `ANIM_CONTROL_EXECUTOR`：

```text
调用方线程
  └─ start / cancel / end → executor 指定的 Looper
       ├─ addUpdateListener → 动画执行线程
       └─ asyncAnimCallbacks / addAnimatorListener → 主线程
```

只有通过 `asyncAnimCallbacks` 或 `addAnimatorListener()` 注册的这组生命周期监听走主线程派发；不能把继承的 `addListener()`、逐帧更新或所有属性设置都当作自动跨线程安全。动画更新涉及界面时，应明确数据共享和主线程更新边界。

`RectSpringDriver` 是显式接入例外：为它自己创建的六个 AndroidX `SpringAnimation` 设置公开 `FrameCallbackScheduler`，由自有帧源触发；不会改全局 AndroidX/平台内核。

自有 `core.AnimationHandler` 与平台 `android.animation`、AndroidX 动画内部的调度器是不同对象。给自有调度器安装帧源，**不等于替换平台或 AndroidX 的帧源**；本工程没有接入原厂 `SfVsyncFrameCallbackProvider`、UX 调度或 CPU boost。

### 生命周期与诊断

- 页面销毁时释放自己创建的动画、Controller、延迟任务及配置订阅；Demo 的释放入口是 `DemoBaseActivity.onCleanup()`，不要关闭进程共享的动画线程。
- `AsyncValueAnimator.dispose()` 是最终、幂等释放：立即使排队命令/回调失效，在 owner Looper 清除监听并取消平台动画；释放后不能重新 start 或注册监听。仅调用 `asyncAnimCallbacks.dispose()` 则只清监听代次/诊断身份，不会取消动画。
- `AsyncAnimCallbacks.setAnimType()` 是可选诊断上下文，默认 `UNSPECIFIED`。事件投递前捕获 id/type，后续改类型不会改写已排队事件；它不选择动画引擎。
- Demo 日志使用共享 `TraceLogRedirector` 和独立页面订阅，支持交错释放；最后一个订阅结束时仅恢复自己仍拥有的 stderr，不关闭原流，也不覆盖其他组件替换的新流。

### 延迟启动决策的环境输入

`AnimationController.delayStartActivityIfNeed()` 按横屏/分屏退出、transition、overview 三层互斥判断。搜索入口支持原厂两种 action 和 `source=drawer_search`；平板条件只约束纯 landscape 项，不排除导航退出或分屏分支。

可通过构造参数 `isTablet` 和 `isOverviewContinuationRunning` 注入真实设备/动画状态。默认平板判定使用传入 Context 的 sw600dp 配置（无 Context 时视为非平板）；默认续行状态由 `setAppToOverviewContinuationState(true/false)` 维护。**单独注册 overview 定时器不代表动画正在运行**，100ms 只作等待兜底；false 注销并取消该场景的等待，不等于完成事件。真实场景信息和系统事件源仍需集成方接入，详见[使用说明](docs/USAGE.md)。

## 12 个 Demo：实际做了什么

入口页面注册了以下 12 个 Activity。表中的“场景演示”由 `LauncherStageView`、`SceneClock` 和自写的 `SceneSpring`/进度曲线驱动，不是相应 `lib` API 的集成测试。

| Demo / Activity | 操作与观察内容 | 与库的实际关系 |
|---|---|---|
| 1 · `Demo1MasterClockActivity` | 比较四个图标同步回弹与不同弹簧参数的错相效果 | 场景演示；实际共用舞台帧钟，没有创建 `AnimatorPlaybackController` 或四个独立 `ValueAnimator`。 |
| 2 · `Demo2HolderProgressActivity` | 比较线性、过冲、减速飞行，并绘制进度曲线 | 场景演示；未直接使用库中的 `Holder` / `ProgressMapper`。 |
| 3 · `Demo3AsyncCrossThreadActivity` | 从 worker 或主线程调用启动，观察线程泳道与回调日志 | 实际使用 `AsyncValueAnimator`，目标执行器为主线程；桌面开屏另由舞台演示。 |
| 4 · `Demo4SpringTransitionActivity` | 窗口先匀速移动，再由弹簧接管收敛 | 场景演示；使用 `SceneSpring`，不是原厂 `OplusSpringObjectAnimator`。 |
| 5 · `Demo5ContinuationActivity` | 上滑在约 40% 处暂停，等待 800ms 后继续进入 Recents | 场景演示；没有调用 `OplusValueAnimator.generateContinuationAnim()`，不能据此验证库的速度连续性。 |
| 6 · `Demo6StateMachineActivity` | 实际播放 OPEN→WAITING→CLOSE→REVERSE_OPEN，交付匹配事件或等待 timer | 状态图只跟随 controller 通知；公开事件桥和定时器一次性完成，不是系统任务状态联调。 |
| 7 · `Demo7SeqIdDedupActivity` | 连发五次回桌面请求，观察立即执行、拦截与延迟补发 | 实际调用 `AnimationSeqHelper` 和 `AnimSeqTimeStamp`，使用 500ms 时间窗口。 |
| 8 · `Demo8FeatureFlagActivity` | 切换实现，比较弹簧开屏与瞬间完成，查看模拟配置 | 实际切换 `OplusAnimManager`；后台模拟发布、主线程订阅刷新一致快照，退出注销；Adaptive 为显式本地策略，非真实 RUS。视觉差异仍由 Demo 分支实现。 |
| 9 · `Demo9AllAppsTransitionActivity` | 四通道开/关窗口、观察独立进度与聚合结束 | 实际调用 `MultiAnimatorSet`；Rect 使用 Canvas/Animator 适配，不是 OEM 六自由度弹簧或系统窗口。已接 Controller 600ms 触摸查询；Recents 辅助按钮仍是舞台示意，非系统多 app 合并。 |
| 10 · `Demo10IndependentThreadActivity` | 分别选择主线程或 `launcher.anim` 驱动，阻塞主线程 800ms，比较更新回调间隔 | 实际对比 `ValueAnimator` 和 `AsyncValueAnimator`；两路是切换运行，不是同时运行；后台仅计算/采样，View 提交在主线程并过滤停止前的旧回调。 |
| 11 · `Demo11ViewSpringAnimThreadActivity` | 卡片 translationY 弹簧，切换直接/包装调用、cancel、skipToEnd 和主线程加压 | 实际构造 AndroidX `SpringAnimation`；包装模式明确 `supportAnimThread=false`，两路均安全使用主线程，不声称后台 View 支持。 |
| 12 · `Demo12RectSpringActivity` | 六轴矩形、反向、改目标、预测接续、取消/结束，选择线程/锚点/额外倍率 | 实际调用 `RectSpringDriver` + `CustomRectFSpringAnim`；数值弹簧可在主/动画线程运行，Canvas 始终在主线程。不是系统窗口或 SF-VSYNC。 |

阅读路径：Demo 3/10 看线程转发 → 6/7/8 看状态编排与配置 → 9/12 看实际四轨聚合与六轴弹簧；1/2/4/5 作为视觉概念辅助。入口卡片中的原厂术语不是 API 接入证明，以本表和对应 Activity 源码为准。

**Demo 10/11 的帧间隔来自动画更新回调采样，不是屏幕呈现 FPS。** 主线程阻塞时，即使后台计算继续，舞台绘制、控件刷新仍会受阻；本工程不能证明“主线程卡住时屏幕仍持续流畅刷新”。

## 构建与运行

### 环境要求

以下为仓库当前配置值，并非推荐升级组合：

| 项目 | 配置 |
|---|---|
| JDK / Java / Kotlin JVM target | 21；`JAVA_HOME` 应指向完整 JDK（包含 `jlink`） |
| Gradle Wrapper | 9.4.1 |
| Android Gradle Plugin / Kotlin 插件 | 8.9.0 / 2.0.21 |
| Android SDK / Build Tools | compileSdk 37 / 37.0.0 |
| 安装要求 / 应用 targetSdk | minSdk 36 / targetSdk 37 |
| AndroidX DynamicAnimation | 1.1.0 |

在根目录创建不提交的 `local.properties`，填写自己的 SDK 路径，例如：

```properties
sdk.dir=C:/Users/your-name/AppData/Local/Android/Sdk
```

`gradle.properties` 关闭了 Java 工具链自动探测，并配置了最高 6GB 的 Gradle JVM 堆；请使用完整 JDK，并预留构建内存。

本机 SDK 兼容目录、已验证工具链的限制、依赖冲突和只读预检见[构建环境说明](docs/build-environment.md)。构建成功不等于干净机器可复现或工具版本获得官方兼容保证。

### 常用命令（仓库根目录，PowerShell）

```powershell
# 只读检查完整 JDK 21、SDK 和 Build Tools 文件；不会安装或改写 SDK
.\scripts\check-build-environment.ps1

# 确认 Gradle 与实际使用的 JVM
.\gradlew.bat --version

# 库的 Debug 单元测试；:lib:test 可运行各测试变体
.\gradlew.bat :lib:testDebugUnitTest

# Demo 纯 JVM 单元测试（不启动 Activity）
.\gradlew.bat :demo:testDebugUnitTest :demo:testReleaseUnitTest

# 构建 Demo Debug APK / 库 Debug AAR
.\gradlew.bat :demo:assembleDebug
.\gradlew.bat :lib:assembleDebug

# Android 静态检查
.\gradlew.bat :lib:lint :demo:lint

# 安装并打开 Demo（需连接 API 36+ 设备或模拟器，adb 加入 PATH）
.\gradlew.bat :demo:installDebug
adb shell am start -n com.asyncanimator.demo/.LauncherEntryActivity
```

入口 `LauncherEntryActivity` 是唯一导出的 Activity，Demo1–12 均显式 `exported=false`。安装后通过入口选择页面，不要为了外部直跳把内部页面全部导出。

Windows 预检使用 PowerShell；macOS/Linux 的 Gradle 命令使用 `./gradlew` 替代 `.\gradlew.bat`。输出位置：

- Debug APK：`demo/build/outputs/apk/debug/demo-debug.apk`
- Debug AAR：`lib/build/outputs/aar/lib-debug.aar`
- 单测报告：`lib/build/reports/tests/testDebugUnitTest/index.html`

当前两个模块的 release 配置均关闭代码混淆/压缩；仓库未配置自定义 release 签名，`assembleRelease` 不代表产出可发布的已签名 APK。

## 测试与验证范围

库使用 JUnit 4、Robolectric 4.16 / API 36 测试运行时（也声明了 AssertJ 依赖）。`unitTests.isReturnDefaultValues = true` 仍保留，但 Android 行为用例已由 Robolectric 执行，Bundle 用例不再跳过。测试依赖不打入 APK。

### 全量验证结果

以下来自 2026-09-10 对外 API 文档功能接入后的完整测试重跑及 XML 核对。此前逐类补测覆盖 42 个手写源码文件、74 个命名类型；本轮已将公开源码 API 文档补全为 389 项，增加全库漏标编译检查与跨模块文档回归。接口与私有嵌套类型通过真实消费者验证，不机械要求一类对应一个 Test 文件。

| 范围 | 测试类 | 独立用例 | 验证结果 |
|---|---:|---:|---|
| lib | 56 | 501 | Debug/Release 各 501 通过，0 失败/错误/跳过 |
| demo | 1 | 6 | 各 6 通过，0 失败/错误/跳过 |
| api-doc-processor | 4 | 33 | JVM 测试全部通过，0 失败/错误/跳过 |
| 合计 | 61 | 540 | 同一批用例，不按构建变体翻倍 |

本次 Demo Debug APK 构建成功，**127/127 个 Gradle 任务执行，耗时 15 分 43 秒**；已解包确认不包含 API Markdown 或 KSP 处理器。文档生成命令另经两次执行验证，第二次复用配置缓存。API 功能验证见[说明](docs/public-api.md)，本地忽略日志为 `.gradle/api-coverage-final-build.log`，不随仓库分发。此前[逐类补测清单](docs/testing/2026-09-10-lib-class-test-plan.md)与 39 份 review 保留为独立历史证据。

复跑完整验证（依赖已缓存时）：

```powershell
.\gradlew.bat :lib:testDebugUnitTest :lib:testReleaseUnitTest `
  :demo:testDebugUnitTest :demo:testReleaseUnitTest :demo:assembleDebug `
  --rerun-tasks --offline --no-daemon --no-build-cache --no-configuration-cache `
  --max-workers=1 "-Pkotlin.incremental=false" --console=plain
```

首次环境未缓存依赖时不能使用 `--offline`；先按[构建环境说明](docs/build-environment.md)准备依赖。SDK/Gradle/私有注解和 Java API deprecation 提示仍存在；Lint 在 `extractDebugAnnotations` 因缺少 `intellij-core` / `kotlin-compiler` 31.9.0 离线依赖失败，未通过关闭检查绕过。

### 逐类测试覆盖明细

库当前共有 **56 个测试类、501 个用例**（同时修正此前 `MultiAnimatorSetTest` 少计 1 项的记录）：

<details>
<summary>展开库测试类、用例数与覆盖内容</summary>

| 测试类 | 用例数 | 覆盖内容 |
|---|---:|---|
| `AnimationHandlerTest` | 14 | 手动帧钟/时间戳、快照增删、100轮注册清理、同源多handler隔离与惰性帧源 |
| `AnimationControllerTest` | 12 | 状态、Recents、超时替换及销毁清理 |
| `AnimationControllerRegressionTest` | 13 | 四组完整状态矩阵、双集合收尾、三层决策及运行态 |
| `LaunchDecisionReentrancyTest` | 7 | Supplier/provider 重入、reset/换槽/注销、嵌套请求、截止边界与日志 |
| `AnimationLaunchDecisionTest` | 14 | 搜索入口、平板 OR 条件、实际续行状态、停止清理及场景归属 |
| `TaskStateChangeTimeOutListenerTest` | 16 | 全type匹配、精确deadline、重入/并发消费、构造访问守卫与双异常/同一异常处理 |
| `AnimatorPlaybackControllerTest` | 2 | 嵌套动画树递归派发、监听自注销 |
| `PlaybackCompletionTest` | 9 | force finish、暂停重启、完成重入/异常、零时长和 pending 标志 |
| `AsyncSpringAnimTest` | 9 | 真实AndroidX cancel/skip/retarget/velocity、首帧/零阻尼、实际动画owner与main结束通知 |
| `AsyncAnimCallbacksTest` | 8 | 稳定/交付时快照、捕获ID、并发增删、重入代次失效、锁外回调及异常恢复 |
| `AsyncAnimatorContractTest` | 6 | Boolean 工厂、释放、旧代次/重入清理、独立逻辑与物理结束 |
| `AnimationSeqHelperTest` | 7 | Bundle 序列号、时间窗口和基础清理 |
| `AnimationSeqRegressionTest` | 11 | SeqId配对/独立owner、300/500ms、null替换、即时/延后异常、重入后继与reset隔离 |
| `AnimationSchedulerHandoffTest` | 6 | 安装失败显式报错、旧脉冲失效、回调内换源/同源幂等、其他订阅者及测试覆盖隔离 |
| `ChoreographerTickSchedulerTest` | 11 | 受控节奏/暂停重启、异常/快照/锁外增删、null操作、先发布clock与callback内stop |
| `ChoreographerOwnerTest` | 1 | 首次帧源绑定、跨 Looper 重启仍由原 HandlerThread 派发 |
| `LooperExecutorTest` | 8 | 异步消息、owner execute内联但post排队、null-handler三入口、异常恢复及目标优先级 |
| `PendingAnimationTest` | 17 | 起点/时长继承、build幂等、seek/取消、负时长/无时长添加、完成标志与真实wrapper生命周期 |
| `InterpolatorsTest` | 10 | clamp/map/reverse/velocity、NaN区间拒绝、越界不求值、内置曲线端点及单调性 |
| `AnimationSceneTest` | 11 | 设备/手势组合、1500/2500ms、null结束/包名、场景默认与copy隔离 |
| `RecentsFinishGateTest` | 5 | launch/Seq/logical-only 否决、ID 匹配及 feature-off |
| `AnimSeqTimeStampTest` | 10 | 四字段零/未设置、单次clock采样、更新/reset隔离、Long精度、时钟异常及并发发布 |
| `AnimationSeqFeatureGateTest` | 8 | 时间窗/序号写入门控、共享计数器、跨截止点排队及旧任务撤销 |
| `AnimationBetweenStateTest` | 6 | Between setter、待启动查询、超时/reset 清理 |
| `OplusValueAnimatorTest` | 17 | 续行值/独立holders、NaN等非有限值拒绝、默认参数、typed空值、property异常与生命周期委派 |
| `RectAnimationLifecycleTest` | 24 | 固定owner/ID/runId、双轨结束、first-stop、逻辑-only、默认/去重监听、真实Animator适配与teardown |
| `RectSpringDriverTest` | 33 | 真实六轴、边界/retarget/预测/续行、first-stop、zero scale、调度失败回滚、异常与native资源清理 |
| `MultiAnimatorSetTest` | 31 | 四轨/全部mask/live-add、无效输入、监听快照/重入destroy、start失败清理、旧轮隔离与physical barrier |
| `TimeoutOwnershipTest` | 3 | 三场景 dispose 摘槽、重入 replacement 保留、timer 清理后不保留 Controller |
| `TaskStateEventTest` | 3 | 公开事件桥、timer 去重、dispose/no-op、Demo6 真实状态链 |
| `ControllerThreadContractTest` | 6 | 主线程入口、callback/observer、owned timeout 与 standalone/no-op 边界 |
| `FeatureSnapshotTest` | 6 | 不可变列表、旧快照隔离、兼容更新竞态、复制失败不留半批配置 |
| `TraceLogTest` | 12 | 变体门控、ThreadLocal栈、实际交付与ID/type、嵌套/Error收尾及中途切换日志策略 |
| `AsyncValueAnimatorLifecycleTest` | 11 | 最终释放、排队/重入异常清理、executor绑定、native/业务监听隔离与one-shot |
| `ManagerBootstrapTest` | 1 | 八路冷首访共享同一 Controller/Seq 实现 |
| `ManagerLifecycleTest` | 9 | 切换清理/重建、独立fallback、capability、配置/订阅边界与Recents委派 |
| `JavaApiInteropTest` | 5 | 真实 Java 编译/执行：Boolean 工厂、getter/setter、SAM 与 Adapter 增删 |
| `ExecutorInitializationTest` | 1 | MAIN 不启动动画线程、8 路并发首次访问得到同一执行器 |
| `TouchGateTest` | 6 | 600ms/重新启动截止、end/reset/destroy、状态/挂起操作门控及线程归属 |
| `FeatureNotificationTest` | 11 | 主线程排队、重复callback独立handle、迟订阅、最新快照/空列表、异常边界及并发一致性 |
| `RemoteAnimationBoundaryTest` | 5 | opaque targets默认/变更/null/empty、factory宿主所有权、reset/destroy及feature-off |
| `ControllerStateMatrixTest` | 13 | 全状态转移/分类、重复factory、非法scene、真实sw600边界、predicate异常及reset/destroy区别 |
| `ControllerCompletionTest` | 7 | 完成回调重入/异常与新轮隔离、idle 清理、非法 Recents 状态诊断 |
| `AnimationThreadBootstrapTest` | 2 | 冷启动首条任务前安装帧源、owner/优先级与 Looper 提前发布时序 |

| `AnimatorListenersTest` | 8 | 每次end与once-only、严格半程阈值、非ValueAnimator、sticky cancel、重入/异常消费 |
| `AsyncAnimWrapperTest` | 4 | 主线程内联/worker投递、null、实际ANIM初始化/嵌套与业务异常 |
| `DefaultAnimationControllerTest` | 8 | 全部默认query/no-op、fallback所有权、12×12通知、重复/null监听、重入/异常与线程约束 |
| `DefaultAnimationSeqHelperTest` | 4 | fallback无ID分配、Bundle不变、同步action/null/重入/异常且不排队 |
| `ListenerBaseContractTest` | 6 | nullable默认、adapter ID/取消、实际结束与逻辑/success分轨、真实dispatcher payload/异常 |
| `LogUtilsTest` | 5 | 等级/flags、非法配置不变、精确线程/tag/UTF8输出、emit门控及跨线程发布 |
| `PlaybackProgressContractTest` | 7 | Holder时长/mapper重置、raw与clamp、cancel/end更新门控、start/reverse/pause与Float update |
| `PropertySetterTest` | 5 | 即时写入不读取起点、NO_ANIM/default、null/非有限透传及异常 |
| `RecordInputInterpolatorTest` | 5 | 输入而非输出记录、默认/重复/非有限透传、抛错前记录及实例隔离 |
| `RectSpringConfigTest` | 9 | 六轴参数/非法值、类型/设备/倍率策略、Tracking及Values/Frame防御副本 |
| `PublicApiDocumentationTest` | 7 | 全模块/公开类型与成员、构造参数属性/枚举、内部实现排除、契约/重载、二进制保留与资源隔离 |
| `SpringProjectionTest` | 6 | 独立数值积分对照四种阻尼、零时间/平衡、组合一致性/平移/收敛与force不变 |

</details>

Demo 模块另有 1 个纯 JVM 测试类，`TraceLogRedirectorTest`：6 个用例，覆盖日志订阅/交错释放/其他流 owner/异常及重入；不等于 Activity 生命周期或旋转仪器测试。

API 生成器另有 `ApiSignaturesTest`（12 项）、`ApiMarkdownTest`（5 项）、`PublicApiProcessorTest`（9 项）、`PublicApiCoverageTest`（7 项），报告位于 `api-doc-processor/build/reports/tests/test/`。

测试数不是行覆盖率或分支覆盖率；仓库没有配置覆盖率阈值，本轮也未运行覆盖率工具。库/Demo 的 XML 与 HTML 报告分别位于各模块的 `build/test-results/test{Debug,Release}UnitTest/` 和 `build/reports/tests/test{Debug,Release}UnitTest/`。

Robolectric 测试不等于设备验证；手动帧钟和模拟 Looper 不验证真实 VSYNC、跨线程 View 绘制或系统转场。仓库未提供 `src/androidTest` 仪器测试。改动线程、回调或动画生命周期后，应在设备上检查重复启动/取消、离开页面后的清理、线程名与主线程加压行为。七个状态转移矩阵测试合计 84 个组合，已经包含在上述 501 个用例中，不额外累计。

## 当前边界与注意事项

- **系统能力未接入**：`LauncherAnimationRunner.RemoteAnimationTarget` 只有 `taskId` 和 `Any? leash` 类型壳，没有 RemoteAnimation Binder 通道或 `SurfaceControl.Transaction` 链路。舞台中的“窗口/leash”是 Canvas 绘制对象。
- **诊断日志不是 Perfetto**：`LogUtils` 默认 Debug 开启、Release 关闭，可显式切换等级；Trace 输出线程名和分类到 stderr／Demo 日志区，不接入平台追踪。
- **公开适配与内部实现应区分**：`CustomRectFSpringAnim` 已有固定线程归属、逻辑/实际结束和可选重定向协议；可选 `RectSpringDriver` 已提供六轴 AndroidX 物理、几何、预测和速度接续；Animator adapter 仍是外部几何适配。未复制 OEM 隐藏物理/窗口引擎；`PendingAnimation`、`AnimatorPlaybackController`、`OplusValueAnimator` 等标记为 `internal`，不是供 `demo` 跨模块直接调用的公开 API。
- **续行动画仍是简化实现**：`TimeControllerObjectAnimator` 的 target/property 已真实接线，续行复制独立值快照并能输出到 applicator；不等于完整的原厂速度接力协议。Demo 5 仍走舞台自己的动画，没有调用该 internal 实现，也不验证速度连续性。
- **开关不是生产灰度系统**：`supportInterruption()` 固定返回 `true`，初始化默认创建具体实现；实际演示切换使用 `interruptionEnabled`。`simulateRemoteUpdate()` 不连接服务，也不自动切换动画线程或管理器实现。
- **后台 View 不是已验证能力保证**：`AsyncSpringAnim` 主要转发生命周期方法，未配置 AndroidX 自定义调度器，也没有统一接管 View 属性写入。Demo 11 因此明确回退主线程并在 onCleanup 清除监听/取消；Demo12 的 `RectSpringDriver` 独立配置了公开 AndroidX scheduler，后台数值更新有真实 HandlerThread 单测；不推广为任意 View 弹簧后台安全，也不等于设备独立渲染。

## 文档导航

- [使用说明](docs/USAGE.md)：API 与使用示例；涉及可见性、签名和实现行为时，以当前源码为准。
- [原厂线程机制分析 v4](docs/animation-thread-analysis-v4.md)：优先阅读的原厂分析，不代表本工程已实现全部能力。
- [原厂 trace 分析](docs/animation-trace-validation.md)：原厂行为的分析证据，不是当前 Demo 的性能测试报告。
- [早期分析](docs/animation-thread-analysis.md)：保留的历史分析，线程结论请结合 v4 阅读。
- [子线程 UI 更新讨论](docs/sub-thread-ui-update.md)：相关机制与限制讨论。
- [lib 逐类测试清单](docs/testing/2026-09-10-lib-class-test-plan.md)：42 个源码文件、74 个命名类型的契约与测试证据、批次结果和缺陷回归。
- [顺序验收清单](docs/review/2026-09-09-ordered-review-progress.md)：39/39 份建议处置、各轮代码与验证记录。
- [基础专项 01–12](docs/review/SUMMARY-vs-oppo.md) / [深挖专项 13–34](docs/review/SUMMARY-vs-oppo-V2.md)：当前专项索引；旧版和 dated 记录作为历史证据，不当作现存缺陷列表。
- [构建环境说明](docs/build-environment.md)：本机 SDK 兼容目录、依赖冲突、工具链与发布限制。
- [贡献指南](AGENTS.md)：构建、风格、测试和提交约定。

本项目用于动画机制学习与实验；仓库目前没有独立的 `LICENSE` 文件，学习用途说明不等同于开源授权条款。
