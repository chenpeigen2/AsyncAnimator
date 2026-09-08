# AsyncAnimator — OPPO Launcher 动画线程执行动画的实现方案 Demo

> 基于 [docs/animation-thread-analysis.md](docs/animation-thread-analysis.md)（2460 行分析）的**可运行 + 可测试** Android Gradle 多模块项目。

## 项目结构

```
D:\AsyncAnimator\
├── docs/
│   └── animation-thread-analysis.md   ← 完整分析文档（OPPO Launcher 反编译源码分析）
├── lib/                                ← 框架：OPPO 定制层 + 自有帧调度内核（Java）
│   ├── src/main/java/com/asyncanimator/
│   │   ├── core/                       ← 自有 AnimationHandler（ThreadLocal 帧调度 + 可换 TickScheduler）
│   │   ├── launcher/                   ← OPPO 自定义层（8 个子包）
│   │   │   ├── playback/               ← AnimatorPlaybackController
│   │   │   ├── pending/                ← PendingAnimation + listener 三层抽象
│   │   │   ├── async/                  ← AsyncValueAnimator + AsyncAnimCallbacks
│   │   │   ├── continuation/           ← OplusValueAnimator + RecordInputInterpolator
│   │   │   ├── controller/             ← AnimationController 状态机
│   │   │   ├── seq/                    ← AnimationSeqHelper SeqId 防抖
│   │   │   ├── manager/                ← OplusAnimManager feature flag 工厂
│   │   │   └── feature/                ← AnimationFeatureHelper 远程灰度
│   │   └── util/                       ← Trace
│   └── src/test/                        ← JUnit 4 单元测试（3 个测试类，21 用例）
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

## 10 个 Demo 对应分析文档章节

| Demo | 标题 | 对应章节 |
|---|---|---|
| 1 | MasterClock 主时钟驱动 | §6.1 |
| 2 | Holder 进度 + ProgressMapper | §6.1.4 |
| 3 | AsyncValueAnimator 跨 Looper | §6.3 |
| 4 | Spring 渐进切换（OplusSpringObjectAnimator 概念） | §6.4 |
| 5 | OplusValueAnimator 续行动画 | §6.5 |
| 6 | AnimationController 状态机 + 3 超时 | §6.8 |
| 7 | AnimationSeqHelper SeqId 防抖 | §6.9 |
| 8 | OplusAnimManager + AnimationFeatureHelper | §6.10 / §6.11 |
| 9 | 完整 OPEN_FROM_HOME 转场 | §7.1 |
| 10 | 独立动画线程（launcher.anim） | OplusExecutors.ANIM_EXECUTOR |

## 关键技术决策

1. **动画类用平台/androidx 自带，不自己仿写**：`android.animation.*`、`android.util.FloatProperty`。
   lib 只保留两类自有代码——OPPO 定制层（async/pending/playback/controller/seq/continuation/manager/feature）
   和帧调度内核 `core/anim/AnimationHandler`（ThreadLocal + 可换 `TickScheduler`，演示"帧源可换"机制本体）。
   （历史版本曾整包仿写 androidx core-animation/dynamicanimation，已删除：platform 类在 launcher.anim
   线程 start 时经框架 ThreadLocal AnimationHandler 就地 tick，行为与仿写件一致。）
2. **Java + Kotlin 混合**：lib 纯 Java（统一框架代码），demo 纯 Kotlin（贴近原 OPPO 项目）
3. **No-op 默认行为**：`Default*` 基类让 feature off 时所有调用安全返回

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

- **最新版（推荐）**：[docs/animation-thread-analysis-v4.md](docs/animation-thread-analysis-v4.md) — v4 设计重分析（6 路独立取证 + 人工复核），**修正了 v3 的核心线程模型结论**：存在真实的 `launcher.anim` 专用动画线程（SF-vsync 帧源），窗口弹簧动画默认跑在该线程。
- **trace 实证**：[docs/animation-trace-validation.md](docs/animation-trace-validation.md) — 用真机 Perfetto trace（OPEN_FROM_HOME 启动）验证 v4：launcher.anim 77 帧逐帧 `animation` + binder 直发 SurfaceFlinger、主线程并行自由均被证实；但 SF-vsync 帧源在该 trace 设备上未体现（两线程同对齐 VSYNC-app），差异原因见该文 §5。
- 旧版：[docs/animation-thread-analysis.md](docs/animation-thread-analysis.md)（v3）—— 其 §7/TL;DR 的"全部在主线程"结论已被 v4 证伪，其余章节（状态机/续行/防抖）仍有效。
- 跨线程细节：[docs/sub-thread-ui-update.md](docs/sub-thread-ui-update.md)。

## 已知限制 / 简化

由于时间和上下文，本项目做了以下简化：

1. **不集成真实 Android Choreographer** — lib 用 JVM 仿真调度器。框架层提供 `TickScheduler` 接口可替换
2. **不接 AOSP Launcher 真实代码** — 接口签名（`RemoteAnimationFactory` 等）按分析文档重新定义
3. **不写 instrumented test** — lib 单元测试覆盖核心逻辑
4. **Demo UI 极简** — 用 `LinearLayout` + `Button` + 自绘 `View` 展示原理，无 Material Design 复杂组件
5. **部分功能做桩** — 一些 listener / callback 的回调路径用最简日志打印代替完整 UI 反馈

## License

仅作 OPPO Launcher 动画机制学习与教学用途。
