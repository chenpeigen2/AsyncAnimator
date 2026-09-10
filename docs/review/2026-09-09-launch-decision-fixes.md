# 2026-09-09 延迟启动决策树建议落地

## 范围

基线提交：`643d457`。对照只读目录 `D:/oppo_a6_launcher/sources`，处理 Review 21 §6.1 的 A1/A2/A3 及配套 B3 回归，同时修复停止续行与多场景回调归属的关联问题。上一轮的 58 个测试保留；不重复计为本轮新增工作。

## 已完成项

| 项目 | 本轮实现 | 验证 |
|---|---|---|
| 21 A1：运行态替代时间窗 | 删除 `overviewContinuationTimeOutMaxTime`；第三层读取可注入的 `isOverviewContinuationRunning`。默认由 `setAppToOverviewContinuationState` 显式维护，注册定时器不再等于运行中 | 未运行但已注册、运行中但定时器已到期尚未派发、同一 provider 先 true 后 false、100ms 兜底恰好一次 |
| 21 A2：搜索入口 | 匹配原厂两种 action 或 `source=drawer_search`；第二层仍执行一次 caller predicate，不因搜索命中而跳过 | 两种 action、drawer source、空/普通 Intent、predicate 次数、匹配事件放行 |
| 21 A3：平板限定 | 第一层使用 `(isLandScapeGesture && !isTablet(context))`；导航退出与分屏仍各自独立参与 OR | 手机/平板纯横屏分支、平板导航退出及分屏、默认 sw600dp Context 判断 |
| 21 B3：回归覆盖 | 新增 `AnimationLaunchDecisionTest` 14 个用例；原 `AnimationControllerRegressionTest` 的时间窗用例迁移为运行态契约，不删除旧状态矩阵 | 新增用例先红后绿；全量测试验证旧行为未误伤 |
| 停止续行清理 | `setAppToOverviewContinuationState(false)` dispose 并移除 overview listener，取消该场景持有的挂起启动；不执行该启动，也不清除其他场景的请求 | 停止后旧事件及定时器无效，后续 transition 不执行旧 action；停止 overview 不影响 transition 请求 |
| 挂起启动归属 | 保存 `startActivityWaitType`，事件/超时只消费所属场景的 action，先清空再执行，允许回调重入 | overview 超时不能提前执行等待横屏退出的请求；原有超时重入回归继续保留 |

Demo6 的 Overview 按钮改为显式标记运行并注册兜底，而不是单独注册一个定时器。

## 原厂取证与实现边界

- `com/oplus/quickstep/utils/AnimationController.java:598-680`：三层互斥优先级；`:620-622` 平板条件只约束 landscape 项；`:628-643` 先计算特殊入口及 Supplier，再合并 OR；`:645-650` 读取实际续行动画运行态。
- 同文件 `:283-290`：`isSpecialAppScene` 的两个 action 和 source 判断；`:843-854`：`setAppToOverviewContinuationState(false)` dispose 并移除 listener。
- `com/android/launcher3/search/IndicatorEntry.java:73-74`：`android.search.action.DOCK_SEARCH`、`com.oppo.quicksearchbox.action.Dispatch`。
- `com/android/launcher3/allapps/branch/BranchSearchHelper.java:83`：`drawer_search`。
- `com/android/common/util/ScreenUtils.java:167-169`：原厂 `isTablet()` 委托设备特性。本库默认 `Context.resources.configuration.smallestScreenWidthDp >= 600` 只是可移植判断（无 Android Context 时默认非平板）；接入 OEM/折叠屏场景应注入真实设备策略，不能声称已移植 OPPO 特性服务。
- 本库提供续行运行态函数注入，不创建假系统单例，不接入原厂完整 `AppSwipeToRecentContinuationHelper`。未注入时，Demo/调用方负责显式 true/false；注入时，provider 是运行态事实来源，定时器仅是等待上限兜底。
- **平板不等于一律不等待**：导航模式退出组合或分屏项为 true 时，原厂仍可能挂起。修正旧 review 中笼统“平板绝不等待”的推论。当前简化的 `setOnAppExit` 同时设置 landscape 和导航标志，尚未接入真实手势场景生产者；测试仅为隔离单个 OR 条件而反射设置这些私有标志，不声称 Demo 已具有完整平板手势流程。
- **挂起 action 的场景归属是库侧补强**：原厂三类 listener 共用一个 `startActivityRunnable`，不是原厂已有该保护。false 的基础注销行为对齐原厂，清除本场景 action 则避免闭包残留及串场。

## 调用契约

所有 controller 状态变更、provider 查询和事件派发按主线程归属使用。构造器保留无参调用，可按实际环境注入：

```kotlin
var continuationRunning = false
val controller = AnimationController(
    isTablet = { context -> /* 从自己的设备配置读取 */ false },
    isOverviewContinuationRunning = { continuationRunning }
)

// 真实动画开始时更新状态，再注册 100ms 兜底。
continuationRunning = true
controller.setAppToOverviewContinuationState(true)

// 取消/放弃等待时注销；不会执行挂起的启动。
continuationRunning = false
controller.setAppToOverviewContinuationState(false)
```

如果需要在真实动画完成时立即放行挂起请求，应由集成方交付匹配的 `ON_APP_TO_OVERVIEW_CONTINUATION` 事件（当前公开 listener 的 `onTimeOut(type, duration)` 同时承担事件入口），而不是把 `setAppToOverviewContinuationState(false)` 当作成功回调。事件会自动清理当前场景；不要在重入的新动画已经开始后再次注销它。

`delayStartActivityIfNeed()` 返回 false 表示调用方可以自行启动，不会替调用方执行传入 action。第三层不再承诺“截止时间相等即 false”；是否等待由运行态及 listener 是否仍存在共同决定。

## 验证记录

- 红灯：先仅增加依赖注入构造入口（不改变旧决策），运行新增 14 个用例，**10 个按预期失败**。
- 首轮绿灯：修复后 Debug **72 个用例通过**，新增 14 个，无删除旧用例。
- **最终强制重跑通过**：Debug/Release 各 **72 个用例、0 失败、0 错误、0 跳过**；Demo Debug APK 构建成功。全程 185 秒，76 个 Gradle task 全部实际执行，两种变体不计为 144 个独立用例。
- 构建前后对 lib/demo Kotlin 源码及构建配置做 SHA-256 比较，无变化；`git diff --check` 通过。
- **Lint 未完成**：离线执行 `:lib:lint :demo:lint`，在 `:lib:extractDebugAnnotations` 获取 lint 工具依赖时失败，缺少 `intellij-core-31.9.0.jar` / `kotlin-compiler-31.9.0.jar` 的缓存。不是 lint 诊断通过；日志 `.gradle/review-round2-lint.log`。
- 构建仍有已有 Kotlin 空安全、内部注解缺失/SDK 工具及 Gradle 弃用警告；本轮未修改工具链来隐藏问题。

复验命令：

```powershell
.\gradlew.bat :lib:testDebugUnitTest :lib:testReleaseUnitTest :demo:assembleDebug --rerun-tasks --offline --no-daemon --no-build-cache --no-configuration-cache --max-workers=1 "-Pkotlin.incremental=false"
```

APK：`demo/build/outputs/apk/debug/demo-debug.apk`。报告：`lib/build/reports/tests/testDebugUnitTest/index.html`、`lib/build/reports/tests/testReleaseUnitTest/index.html`。原厂源码未修改，未提交 commit。
- 日志：`.gradle/review-round2-red.log`、`.gradle/review-round2-green.log`、`.gradle/review-round2-final.log`。

## 不标完成的范围

Review 21 顶部 feature guard、原厂任务事件总线、手势/分屏运行信息自动接线、SF-VSYNC/UX 调度、完整矩形弹簧及系统远程转场均未实现。Demo9/11 没有因此自动接入 controller，不把自绘页面或 JVM 回归当作真实系统转场验收。未做设备回归或 Perfetto 采集，不承诺生产接入已经完成。
