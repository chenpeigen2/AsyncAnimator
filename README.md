# AsyncAnimator

一个用于学习 Launcher 动画调度、跨 Looper 调用和转场编排的 **Kotlin Android 多模块示例工程**。项目包含动画机制库 `lib` 和带 11 个交互页面的演示应用 `demo`，参考 OPPO Launcher 动画机制分析进行简化实现。

**项目不是完整 Launcher，也不是 OPPO 动画框架的等价移植。** Demo 中既有直接调用 `lib` 的实验，也有自绘场景演示；画面、日志中的原厂术语不代表已接入对应系统能力。下面按当前代码区分两者。

## 项目结构

```text
AsyncAnimator/
├── lib/                              # Android Library，不是纯 JVM 库
│   ├── src/main/java/
│   │   ├── com/asyncanimator/
│   │   │   ├── core/                 # 自有帧调度器、ThreadLocal AnimationHandler、Trace
│   │   │   ├── thread/               # launcher.anim、LooperExecutor、AsyncAnimWrapper
│   │   │   ├── anim/                 # 异步动画包装、续行动画、动画句柄
│   │   │   ├── playback/             # PendingAnimation、统一进度播放控制
│   │   │   ├── control/              # 动画状态、超时与 Recents 编排
│   │   │   ├── seq/                  # 序列号、时间窗口与延迟去重
│   │   │   └── manager/              # 实现切换与本地配置模拟
│   │   └── com/android/launcher3/    # LauncherAnimationRunner 类型桩
│   └── src/test/java/                # JUnit 4 本地单元测试
├── demo/
│   ├── src/main/java/com/asyncanimator/
│   │   ├── LauncherEntryActivity.kt  # 入口文件；包名为 com.asyncanimator.demo
│   │   └── demo/
│   │       ├── Demo*Activity.kt       # 11 个页面及 DemoBaseActivity
│   │       ├── scene/                # 自绘桌面、SceneSpring、SceneClock、图形素材
│   │       └── widget/               # 曲线、线程泳道、状态图、帧间隔直方图
│   └── src/main/res/                 # 主题、字符串、图标等 Android 资源
├── docs/                             # 使用说明、原厂分析、trace 与 review 记录
├── gradle/libs.versions.toml          # 依赖与插件版本
├── lib/build.gradle.kts              # 库模块配置（Kotlin DSL）
├── demo/build.gradle                 # 应用模块配置（Groovy DSL）
└── AGENTS.md                         # 贡献与代码修改约定
```

演示页面采用 View 系统，主要通过代码创建布局和自绘 Canvas 场景，依赖 AppCompat、Material Components 等组件；不是 Compose 工程。

## 库实现与线程模型

| 模块 | 当前实现 |
|---|---|
| `thread` | `Executors` 提供主线程及 `launcher.anim` 执行器；`AnimationControlThread` 使用 `HandlerThread`，初始化时安装自有线程帧调度器，并尝试设置线程优先级。 |
| `core` | 自有 `AnimationHandler` 管理帧回调，`ChoreographerTickScheduler` 使用公开 `Choreographer` 订阅帧；不是 JVM 定时器模拟。 |
| `anim` | `AsyncValueAnimator` 将 `start/cancel/end` 转发到目标 Looper；`AsyncSpringAnim` 包装 AndroidX `SpringAnimation` 的生命周期调用；`OplusValueAnimator` 包含简化的进度续行逻辑。 |
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

自有 `core.AnimationHandler` 与平台 `android.animation`、AndroidX 动画内部的调度器是不同对象。给自有调度器安装帧源，**不等于替换平台或 AndroidX 的帧源**；本工程没有接入原厂 `SfVsyncFrameCallbackProvider`、UX 调度或 CPU boost。

## 11 个 Demo：实际做了什么

入口页面注册了以下 11 个 Activity。表中的“场景演示”由 `LauncherStageView`、`SceneClock` 和自写的 `SceneSpring`/进度曲线驱动，不是相应 `lib` API 的集成测试。

| Demo / Activity | 操作与观察内容 | 与库的实际关系 |
|---|---|---|
| 1 · `Demo1MasterClockActivity` | 比较四个图标同步回弹与不同弹簧参数的错相效果 | 场景演示；实际共用舞台帧钟，没有创建 `AnimatorPlaybackController` 或四个独立 `ValueAnimator`。 |
| 2 · `Demo2HolderProgressActivity` | 比较线性、过冲、减速飞行，并绘制进度曲线 | 场景演示；未直接使用库中的 `Holder` / `ProgressMapper`。 |
| 3 · `Demo3AsyncCrossThreadActivity` | 从 worker 或主线程调用启动，观察线程泳道与回调日志 | 实际使用 `AsyncValueAnimator`，目标执行器为主线程；桌面开屏另由舞台演示。 |
| 4 · `Demo4SpringTransitionActivity` | 窗口先匀速移动，再由弹簧接管收敛 | 场景演示；使用 `SceneSpring`，不是原厂 `OplusSpringObjectAnimator`。 |
| 5 · `Demo5ContinuationActivity` | 上滑在约 40% 处暂停，等待 800ms 后继续进入 Recents | 场景演示；没有调用 `OplusValueAnimator.generateContinuationAnim()`，不能据此验证库的速度连续性。 |
| 6 · `Demo6StateMachineActivity` | 播放状态序列，注册或模拟触发三类超时 | 实际调用 `AnimationController`，配合状态图与舞台反馈；不是系统任务状态联调。 |
| 7 · `Demo7SeqIdDedupActivity` | 连发五次回桌面请求，观察立即执行、拦截与延迟补发 | 实际调用 `AnimationSeqHelper` 和 `AnimSeqTimeStamp`，使用 500ms 时间窗口。 |
| 8 · `Demo8FeatureFlagActivity` | 切换实现，比较弹簧开屏与瞬间完成，查看模拟配置 | 实际切换 `OplusAnimManager` 并写入配置容器；视觉差异由 Demo 自行分支实现。 |
| 9 · `Demo9AllAppsTransitionActivity` | 图标展开为窗口、收回、进入 Recents，并绘制进度 | 自绘应用开合场景；虽页面标题仍写 AllApps ↔ Workspace，但没有真实应用抽屉、系统应用启动或完整远程转场链。 |
| 10 · `Demo10IndependentThreadActivity` | 分别选择主线程或 `launcher.anim` 驱动，阻塞主线程 800ms，比较更新回调间隔 | 实际对比 `ValueAnimator` 和 `AsyncValueAnimator`；两路是切换运行，不是同时运行。 |
| 11 · `Demo11ViewSpringAnimThreadActivity` | 卡片 translationY 弹簧，切换线程、cancel、skipToEnd 和主线程加压 | 实际构造 AndroidX `SpringAnimation` 并可经 `AsyncSpringAnim` 转发；后台启动及 View 属性更新的运行时行为仍需设备验证。 |

建议先看 Demo 3 和 10 理解线程转发，再看 6/7/8 理解编排与开关，最后用其余场景辅助理解视觉概念。

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

### 常用命令（仓库根目录，PowerShell）

```powershell
# 确认 Gradle 与使用的 JVM
.\gradlew.bat --version

# 库的 Debug 单元测试；:lib:test 可运行各测试变体
.\gradlew.bat :lib:testDebugUnitTest

# 构建 Demo Debug APK / 库 Debug AAR
.\gradlew.bat :demo:assembleDebug
.\gradlew.bat :lib:assembleDebug

# Android 静态检查
.\gradlew.bat :lib:lint :demo:lint

# 安装并打开 Demo（需连接 API 36+ 设备或模拟器，adb 加入 PATH）
.\gradlew.bat :demo:installDebug
adb shell am start -n com.asyncanimator.demo/.LauncherEntryActivity
```

macOS/Linux 使用 `./gradlew` 替代 `.\gradlew.bat`。输出位置：

- Debug APK：`demo/build/outputs/apk/debug/demo-debug.apk`
- Debug AAR：`lib/build/outputs/aar/lib-debug.aar`
- 单测报告：`lib/build/reports/tests/testDebugUnitTest/index.html`

当前两个模块的 release 配置均关闭代码混淆/压缩；仓库未配置自定义 release 签名，`assembleRelease` 不代表产出可发布的已签名 APK。

## 测试与验证范围

库使用 JUnit 4、Robolectric 4.16 / API 36 测试运行时（也声明了 AssertJ 依赖）。`unitTests.isReturnDefaultValues = true` 仍保留，但 Android 行为用例已由 Robolectric 执行，Bundle 用例不再跳过。测试依赖不打入 APK。

当前共有 **9 个测试类、58 个用例**：

| 测试类 | 用例数 | 覆盖内容 |
|---|---:|---|
| `AnimationHandlerTest` | 6 | 手动推进帧、显式移除、空回路退订、同帧增删、ThreadLocal |
| `AnimationControllerTest` | 12 | 状态、Recents、超时替换及销毁清理 |
| `AnimationControllerRegressionTest` | 13 | 四组完整状态矩阵、双集合收尾、三层决策及截止边界 |
| `TaskStateChangeTimeOutListenerTest` | 5 | 匹配事件/定时器一次性消费、dispose 撤销 |
| `AnimatorPlaybackControllerTest` | 2 | 嵌套动画树递归派发、监听自注销 |
| `AsyncAnimCallbacksTest` | 2 | 稳定快照、注册幂等、并发增删 |
| `AsyncAnimatorContractTest` | 5 | Boolean 工厂、释放、旧代次逻辑/物理结束与重入清理 |
| `AnimationSeqHelperTest` | 7 | Bundle 序列号、时间窗口和基础清理 |
| `AnimationSeqRegressionTest` | 6 | SeqId 配对、300/500ms 边界、独立 reset、延迟去重及重入 |

**2026-09-09 本地验证**：Debug 和 Release 单测各 58 个通过、0 跳过、0 失败，Demo Debug APK 构建成功。遇到并行修改和 Kotlin 缓存打包问题后，使用无缓存、串行模式复验；详见 [Review 续轮记录](docs/review/2026-09-09-review-followup.md)。构建仍有已有的 Kotlin 空安全、SDK 工具/实验性选项及 Gradle 弃用警告，不是零警告构建。Lint 尚未完成：并行复核的离线检查缺少 lint 工具依赖，详见[合并复核记录](docs/review/2026-09-09-revalidation-fixes.md)。未安装或进行设备回归。

Robolectric 测试不等于设备验证；手动帧钟和模拟 Looper 不验证真实 VSYNC、跨线程 View 绘制或系统转场。仓库未提供 `src/androidTest` 仪器测试。改动线程、回调或动画生命周期后，应在设备上检查重复启动/取消、离开页面后的清理、线程名与主线程加压行为。四个状态矩阵测试合计 48 个组合，已经包含在上述 58 个用例中，不额外累计。

## 当前边界与注意事项

- **系统能力未接入**：`LauncherAnimationRunner.RemoteAnimationTarget` 只有 `taskId` 和 `Any? leash` 类型壳，没有 RemoteAnimation Binder 通道或 `SurfaceControl.Transaction` 链路。舞台中的“窗口/leash”是 Canvas 绘制对象。
- **部分类型只是占位或内部实现**：`CustomRectFSpringAnim` 只保存动画类型，不实现矩形弹簧；`PendingAnimation`、`AnimatorPlaybackController`、`OplusValueAnimator` 等标记为 `internal`，不是供 `demo` 跨模块直接调用的公开 API。
- **续行动画仍是简化实现**：`TimeControllerObjectAnimator.setTarget()` 已通过更新监听驱动目标 fraction，不再是空实现；`setProperty()` 仍为空操作。不要沿用 Demo 5 日志中“setTarget 为 no-op”的旧描述，也不要把它视为完整的原厂速度接力协议。
- **开关不是生产灰度系统**：`supportInterruption()` 固定返回 `true`，初始化默认创建具体实现；实际演示切换使用 `interruptionEnabled`。`simulateRemoteUpdate()` 不连接服务，也不自动切换动画线程或管理器实现。
- **后台弹簧不是已验证能力保证**：`AsyncSpringAnim` 主要转发生命周期方法，未配置 AndroidX 自定义调度器，也没有统一接管 View 属性写入。Demo 11 的运行时线程约束、取消路径和生命周期清理需单独验证，不能仅凭编译成功判断安全。

## 文档导航

- [使用说明](docs/USAGE.md)：API 与使用示例；涉及可见性、签名和实现行为时，以当前源码为准。
- [原厂线程机制分析 v4](docs/animation-thread-analysis-v4.md)：优先阅读的原厂分析，不代表本工程已实现全部能力。
- [原厂 trace 分析](docs/animation-trace-validation.md)：原厂行为的分析证据，不是当前 Demo 的性能测试报告。
- [早期分析](docs/animation-thread-analysis.md)：保留的历史分析，线程结论请结合 v4 阅读。
- [子线程 UI 更新讨论](docs/sub-thread-ui-update.md)：相关机制与限制讨论。
- [Review 记录](docs/review/)：差异、修正和保留简化项；部分记录或源码注释可能早于当前实现。
- [贡献指南](AGENTS.md)：构建、风格、测试和提交约定。

本项目用于动画机制学习与实验；仓库目前没有独立的 `LICENSE` 文件，学习用途说明不等同于开源授权条款。
