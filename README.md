# AsyncAnimator — OPPO Launcher 动画线程执行动画的实现方案 Demo

> 基于 [docs/animation-thread-analysis.md](docs/animation-thread-analysis.md)（2460 行分析）的**可运行 + 可测试** Android Gradle 多模块项目。

## 项目结构

```
D:\AsyncAnimator\
├── docs/
│   └── animation-thread-analysis.md   ← 完整分析文档（OPPO Launcher 反编译源码分析）
├── lib/                                ← 框架：重新实现的核心动画体系（Java）
│   ├── src/main/java/com/asyncanimator/
│   │   ├── core/                       ← 仿真 Choreographer + ValueAnimator 体系
│   │   ├── dyn/                        ← DynamicAnimation + Spring 物理引擎
│   │   ├── launcher/                   ← OPPO 自定义层（9 个子包）
│   │   │   ├── playback/               ← AnimatorPlaybackController
│   │   │   ├── pending/                ← PendingAnimation + listener 三层抽象
│   │   │   ├── async/                  ← AsyncValueAnimator + AsyncAnimCallbacks
│   │   │   ├── spring/                 ← OplusSpringObjectAnimator
│   │   │   ├── continuation/           ← OplusValueAnimator + RecordInputInterpolator
│   │   │   ├── controller/             ← AnimationController 状态机
│   │   │   ├── seq/                    ← AnimationSeqHelper SeqId 防抖
│   │   │   ├── manager/                ← OplusAnimManager feature flag 工厂
│   │   │   └── feature/                ← AnimationFeatureHelper 远程灰度
│   │   └── util/                       ← FloatProperty + Trace
│   └── src/test/                        ← JUnit 4 单元测试（8 个测试类）
├── demo/                               ← Android app demo（Kotlin）
│   ├── src/main/java/com/asyncanimator/
│   │   ├── LauncherEntryActivity.kt   ← 9 个 demo 入口
│   │   └── demo/
│   │       ├── DemoBaseActivity.kt     ← 统一 demo 框架
│   │       ├── Demo1MasterClockActivity.kt
│   │       ├── Demo2HolderProgressActivity.kt
│   │       ├── Demo3AsyncCrossThreadActivity.kt
│   │       ├── Demo4SpringTransitionActivity.kt
│   │       ├── Demo5ContinuationActivity.kt
│   │       ├── Demo6StateMachineActivity.kt
│   │       ├── Demo7SeqIdDedupActivity.kt
│   │       ├── Demo8FeatureFlagActivity.kt
│   │       └── Demo9AllAppsTransitionActivity.kt
│   └── src/main/res/                    ← 资源文件
├── settings.gradle                     ← Gradle 多模块配置
├── build.gradle                         ← 根 build（plugin classpath）
├── gradle.properties
├── local.properties                    ← SDK 路径（不提交）
├── .gitignore
└── README.md                           ← 本文件
```

## 9 个 Demo 对应分析文档章节

| Demo | 标题 | 对应章节 |
|---|---|---|
| 1 | MasterClock 主时钟驱动 | §6.1 |
| 2 | Holder 进度 + ProgressMapper | §6.1.4 |
| 3 | AsyncValueAnimator 跨 Looper | §6.3 |
| 4 | OplusSpringObjectAnimator Spring 切换 | §6.4 |
| 5 | OplusValueAnimator 续行动画 | §6.5 |
| 6 | AnimationController 11 状态 + 3 超时 | §6.8 |
| 7 | AnimationSeqHelper SeqId 防抖 | §6.9 |
| 8 | OplusAnimManager + AnimationFeatureHelper | §6.10 / §6.11 |
| 9 | 完整 AllApps ↔ Workspace 转场 | §7.1 |

## 关键技术决策

1. **仿真 Choreographer**：lib 用 `ScheduledTickScheduler`（JVM 内 `ScheduledExecutorService`）仿真 Choreographer。
   - 优点：lib 模块可在 PC 端跑 JUnit 测试，无需 Android 设备
   - 框架层有 `TickScheduler` 抽象，方便将来切到真实 Choreographer
2. **Java + Kotlin 混合**：lib 纯 Java（统一框架代码），demo 纯 Kotlin（贴近原 OPPO 项目）
3. **简化版 Spring 物理引擎**：`SpringForce` 实现完整三种 damping regime 的闭式解；`SpringAnimation` 实现 2 阶段过渡
4. **No-op 默认行为**：`Default*` 基类让 feature off 时所有调用安全返回

## Build

需要 Java 17+ 和 Android SDK（compileSdk 34 / minSdk 24）。

```bash
# 1. 同步 gradle wrapper（首次）
gradle wrapper --gradle-version 8.7

# 2. 跑 lib 单元测试
./gradlew :lib:test

# 3. 编译 demo APK
./gradlew :demo:assembleDebug

# 4. 跑 demo（需要 emulator 或真机）
./gradlew :demo:installDebug
adb shell am start -n com.asyncanimator.demo/.LauncherEntryActivity
```

## 文档

完整 OPPO Launcher 动画线程分析：[docs/animation-thread-analysis.md](docs/animation-thread-analysis.md)（2460 行）。

## 已知限制 / 简化

由于时间和上下文，本项目做了以下简化：

1. **不集成真实 Android Choreographer** — lib 用 JVM 仿真调度器。框架层提供 `TickScheduler` 接口可替换
2. **不接 AOSP Launcher 真实代码** — 接口签名（`RemoteAnimationFactory` 等）按分析文档重新定义
3. **不写 instrumented test** — lib 单元测试覆盖核心逻辑
4. **Demo UI 极简** — 用 `LinearLayout` + `Button` + 自绘 `View` 展示原理，无 Material Design 复杂组件
5. **部分功能做桩** — 一些 listener / callback 的回调路径用最简日志打印代替完整 UI 反馈

## License

仅作 OPPO Launcher 动画机制学习与教学用途。
