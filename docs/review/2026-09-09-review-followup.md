# 2026-09-09 Review 建议落地（续轮）

## 范围与验收口径

对照 `D:/oppo_a6_launcher/sources`，在已有未提交修复上继续处理明确建议。本记录只覆盖下表，不将 34 份 review 全部标为完成，也不把此前已有改动算成本轮新增代码。

## 实现与回归

验收结论：下表 8 项已完成，A6 为部分完成；完成均指所列本库实现/测试范围。

| Review 项 | 变更 | 验收入口 |
|---|---|---|
| 01 §4.1-2 工厂开关 | 补 `AsyncValueAnimator.ofFloat(isAsync, vararg values): ValueAnimator` 和 Java 静态入口；保留原异步快捷重载。纠正历史“已有 companion 即已完成”的误判 | `AsyncAnimatorContractTest.testBooleanFactorySelectsAnimatorAndPreservesValues` |
| 09 §4.1-5 监听释放 | `AsyncAnimCallbacks.dispose()` 清监听、复位 ID、使旧代次排队事件失效；逻辑/物理结束共用派发协议；Demo3 页面清理接入 | `AsyncAnimatorContractTest` 的释放、重新注册、物理结束及监听内重入释放测试 |
| 25 §4.1-6 controller 配对 | 覆盖重复 controller、不同实例但 equals 相等、切换 controller 后递增及旧 controller 返回 0；修正“引用比较”的过时注释，保留 equals 行为 | `AnimationSeqRegressionTest.testSameAndEqualControllersKeepSequenceUntilControllerChanges` |
| 25 延迟回调重入 | 先取出并清空当前延迟任务，再调用业务；回调中提交的后继任务不会被旧回调尾部清除；trace 由 `runCatching { ... }.also { traceEnd() }.getOrThrow()` 配对并保留异常传播 | `AnimationSeqRegressionTest.testDelayedCallbackCanScheduleItsSuccessor` |
| 34 A1 状态矩阵 | 遍历 12 个输入状态，验证 addRecents、launch-start、revert、gesture-processing 下 launch-end，共 48 个状态/操作组合 | `AnimationControllerRegressionTest` 前四个测试；无公开生产入口的状态只在测试中反射设置 |
| 34 A2 收尾 | 两种结束顺序下，必须等两个动画集合都为空才执行双 callback；完成后清空 callback 并回到 NONE | `testFinishWaitsForBothCollectionsAndConsumesBothCallbacks` / `testFinishWaitsForRecentsAfterLastLaunchEnds` |
| 34 A3 决策树 | 横屏优先、transition predicate、overview 时间窗、互斥不下落、无场景清理、过期不保留操作及精确截止边界 | `AnimationControllerRegressionTest` 中 7 个决策树测试；验证本库简化决策，不等同原厂系统事件联调 |
| 34 A6 时间边界（部分） | 补 300/500ms 的 0、阈值前、等于阈值、阈值后；补延迟请求只执行最后一个和 clear 撤销 | `AnimationSeqRegressionTest`；原厂 starting-surface / interruption 闸门未接入，完整 A6 不标完成 |
| 34 A7 / 26 时间戳独立复位 | 四个 reset 分别验证：自身恢复未初始化哨兵，其他三个字段保持原值；测试恢复注入时钟避免全局污染 | `testEveryTimestampResetLeavesOtherThreeUntouched` |

## 原厂证据及有意差异

- `com/android/quickstep/util/animation/AsyncValueAnimator.java:43-49,98-100`：Boolean 工厂在异步包装与平台 ValueAnimator 之间选择。原厂本身也保留一次性 `mIsEnd` 门控；本轮没有改成可重复生命周期协议。
- `com/android/quickstep/util/animation/AsyncAnimCallbacks.java`：主线程派发、懒删除、监听快照与物理结束。`dispose()` 和代次失效是库侧生命周期补强，不冒充原厂已存在的 API；容器可重新注册不代表 animator 可复用。
- `com/oplus/quickstep/utils/AnimationSeqHelper.java:35-48`：原厂同样在 `Runnable.run()` 后清空字段。本轮改为调用前消费，是针对回调重入丢请求的库侧改进，不是机械照抄。
- 同文件 `:69-75,86-99,102-128`：300/500ms 窗口、延迟替换及 `Intrinsics.areEqual` 配对。库仍保留主线程归属及 feature 闸门简化，不宣称任意线程安全。
- `com/oplus/quickstep/utils/AnimationController.java:138-176,471-552,825-838`：WhenMappings 和四处状态转移；`:224-232` 双集合收尾；`:598-680` 互斥的延迟启动分支。原厂 overview 检查实际 continuation 状态，库用超时截止替代，测试仅守护现有简化。
- `com/android/systemui/shared/system/AnimSeqTimeStamp.java`：四个独立 update/get/reset。库将未初始化的 0 时间戳解释为 `Long.MAX_VALUE` 间隔，测试保留这一既有差异。

## 验证记录

- 红灯：定向执行新增用例，9 个中 4 个失败；分别是 Boolean 工厂缺失、两条 dispose 合同缺失、延迟回调重入后第二个任务丢失。
- 首轮绿灯：合入修复后，Debug 55 个测试通过，Demo Debug APK 构建成功。
- 追加物理结束、精确截止边界和监听内 dispose 回归后，检测到同一源文件被外部并行改写，出现重复字段/方法及派发名称不一致；未将这一中间状态视为验收通过。
- **共享工作区最终验证（2026-09-09）**：`:lib:test :demo:assembleDebug --offline` 成功，Debug/Release 各 58 通过、0 失败、0 跳过；命令前后源码/构建配置 SHA-256 一致。Lint 已尝试但因两项工具依赖未缓存而未完成，设备回归未执行。完整日志、边界及验证表见[合并复核记录](2026-09-09-revalidation-fixes.md#验证结果2026-09-09)。

## 未完成 / 保持简化

SF-VSYNC provider、UX/CPU boost、完整 CustomRectFSpringAnim/MultiDynamicAnimation、系统任务事件总线、原厂 feature 闸门、后台 View 弹簧设备验证、动画实例复用/运行中改 executor、全局 feature 关闭时的在途任务均不在本轮完成范围。

Review 34 A4（真实推进帧）和 A5（超时、dispose）在本轮开始时已有实现，最终全量验证会一并确认，但不是本轮新增。没有测得的覆盖率百分比不作为结论；48 个矩阵组合不另计成 48 个 JUnit 用例。


## 本续轮独立强制重跑记录

在上述共享验证之外，本续轮再次禁用构建/配置缓存和 Kotlin 增量编译，强制重跑两种测试变体：

```powershell
.\gradlew.bat :lib:testDebugUnitTest :lib:testReleaseUnitTest --rerun-tasks --no-daemon --no-build-cache --no-configuration-cache --max-workers=1 "-Pkotlin.incremental=false"
```

结果：**成功，96 秒，31 个 Gradle task 全部实际执行**；Debug/Release 各 **58 个测试、0 失败、0 错误、0 跳过**。新增 3 个测试类、24 个用例，其余 34 个用例在本轮开始时已存在。验证前后监听容器源码 SHA-256 未变化。Debug APK 另经同样的无缓存/串行构建参数验证成功。

日志：`.gradle/review-followup-red.log`（预期失败）、`.gradle/review-followup-build.log`（构建通过）、`.gradle/review-followup-rerun.log`（强制重跑通过）。README 链接及 `git diff --check` 通过；原厂源码未修改，未提交 commit。Lint 未完成、设备未验证的限制与上方合并记录一致。
