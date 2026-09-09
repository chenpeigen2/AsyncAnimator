# 2026-09-09 当前代码复核与修复

## 范围与判定依据

本轮处理项目初读后确认的监听派发、并发访问、生命周期及测试有效性问题，**不是 34 份历史 review 全量清零**。以当前实现、指定 OPPO 源码和可执行回归为依据；旧汇总中的修复行数估算和“全部 bug 已修复”不能作为验收结论。

对照源：`D:/oppo_a6_launcher/sources`（只读）。

- `com/android/launcher3/anim/AnimatorPlaybackController.java:184-195`：前序递归访问根及全部后代；`:231-248`：start/end/cancel 显式派发。
- `com/android/quickstep/util/animation/AsyncAnimCallbacks.java`：主线程派发、懒删除及监听快照。本库在此基础上增加锁保护，不复制原厂裸集合的并发风险。
- `androidx/core/animation/AnimationHandler.java:130-137,204-209`：忽略回调布尔返回值；结束通过显式 removeCallback。
- `com/oplus/basecommon/thread/OplusExecutors.java:95,169-171`：原厂线程、平台 AnimationHandler provider 和 UX 注册；不等于本库自有调度器。

## 修复项

| 项目 | 本轮变更 | 回归依据 |
|---|---|---|
| APC 深层监听漏派 | 从“根 + 第一层”改为整棵树前序 DFS；每个节点的监听快照允许回调自行注销 | 三层树 start/cancel/end 顺序与恰好一次派发；监听自注销不漏掉后续监听 |
| AsyncAnimCallbacks 并发 | 注册、懒删除、清空及快照生成共用私有锁；锁外调用业务监听；animationId 可见性 | 注册幂等、快照稳定、并发增删及快照压力测试 |
| 自有帧调度空转 | 持有稳定 self-pulse 引用；清理最后一个动画后退订；替换调度器时移除旧订阅 | 实际推进手动时钟，验证移除后无订阅、重启只派发一次、同帧增删 |
| 超时生命周期 | 事件与定时器共享一次性消费；dispose 清除待执行闭包；同类重新注册先 dispose 旧监听；触发后清空对应场景监听 | 重复事件、dispose 后事件、旧监听不能消费新请求 |
| Controller 销毁 | 清空状态观察者后取消三类超时并 reset，避免销毁期间通知页面或继续持有页面 | destroy 后三个监听为空、状态 NONE、旧观察者不再回调 |
| Demo 清理接线 | Demo3 管理动画和 UI 延迟任务，处理 worker 已排队 start 与销毁 cancel 的顺序；Demo6 调 destroy；Demo7 清除延迟 finish | 编译检查；页面退出场景尚需设备回归 |
| 公共 Demo 资源 | 舞台 detach 停止 SceneClock；日志流弱引用 Activity，销毁恢复自己安装的 stderr；销毁后日志不更新 View | 编译检查；真实 VSYNC 和 Activity 生命周期仍需设备验证 |

注意：dispose 不能撤回已开始执行的业务回调；Controller 的注册、状态变更和销毁仍按主线程归属使用。本次未宣称整个 Controller 支持任意线程并发。

## 追加回归与并发工作区合并

最终复跑期间，工作区新增 `AsyncAnimatorContractTest`、`AnimationSeqRegressionTest`、`AnimationControllerRegressionTest`。这些新增测试被保留并纳入验证，未通过删除/忽略用例规避失败。双方同时修改监听容器产生的重复声明已合并。状态矩阵、时间边界和原厂方法取证的补充明细见 [续轮落地记录](2026-09-09-review-followup.md)，最终验证口径统一如下表。

追加修复：

- `AsyncValueAnimator.ofFloat(isAsync, vararg values)` 补齐 `@JvmStatic` Java 入口，与 OPPO `AsyncValueAnimator.java:43-48,98-100` 对齐；保留现有无布尔参数的 Kotlin 便捷重载。
- `AsyncAnimCallbacks.dispose()` 清空监听与 animationId，并递增事件代次，阻止销毁前已排队的事件误投递给后注册的监听。此代次屏障是本库的生命周期增强，不声称原厂同名 API 已存在。普通事件和 actual-end 共用屏障，业务回调仍在锁外执行。
- `AnimationSeqHelper` 的延迟消息先取走并清空旧 action，再调用业务代码，防止回调中安排的后续 finish 请求被旧回调收尾清掉；trace 用 `runCatching { ... }.also { traceEnd() }.getOrThrow()` 配对并保留异常传播。

## 测试有效性与环境

旧 `testCallbackReturnsTrueEndsAnimation` 只检查注册数量，没有推进帧，且名称与原厂语义相反。本轮替换为真实推进手动时钟的测试，保留“返回值忽略、显式移除”的行为。

本机 SDK 37 的部分签名桩在 JVM 加载时出现 `ClassFormatError: Absent Code attribute`（AnimatorSet / Application）。新增仅用于测试的 Robolectric 4.16 与 API 36 android-all 运行时；不改变 compileSdk/minSdk，不给 APK 增加运行时依赖。SDK jar 由 Gradle 统一解析并复制到 `lib/build/robolectric-sdk/`，测试使用本地解析器，避免 runner 再下载一份；首次仍需联网获取 Gradle 依赖。原 Bundle 测试改为 Robolectric 执行，不再因 stub 环境被忽略。

### 验证结果（2026-09-09）

| 验证 | 结果 |
|---|---|
| `./gradlew.bat :lib:test :demo:assembleDebug --offline --console=plain` | **通过**；最终一轮 1m11s，Debug/Release 各 58 个用例，0 失败、0 跳过 |
| 源码稳定性 | 最终命令前后对 lib/demo 源码及构建配置做 SHA-256 比较，无文件变化 |
| 旧实现反向回归 | 暂时使用 HEAD 中旧 APC/AsyncAnimCallbacks，4 项测试中 3 项按预期失败：嵌套监听漏派、自注销 CME、并发快照；已恢复修复代码 |
| Demo 运行时依赖 | `:demo:dependencies --configuration debugRuntimeClasspath --offline` 通过，未包含 Robolectric / android-all / Mockito |
| `./gradlew.bat :lib:lint :demo:lint --offline` | **未完成**：lint 工具依赖 `intellij-core-31.9.0.jar`、`kotlin-compiler-31.9.0.jar` 未缓存；配置缓存序列化错误是依赖解析失败的外层表现，不是 lint 诊断结果 |
| `git diff --check` | 通过 |
| 设备 / Perfetto | 未安装、未执行设备回归、未重新采集 trace |

58 个独立用例在两种构建变体分别执行，不能称为 116 个独立用例。早先 34 项通过记录对应追加回归前的版本，以本表为准。

构建仍存在已有 Kotlin/Gradle 警告；并发构建期间出现 Kotlin 增量缓存异常，最终命令自动回退到非 Kotlin daemon 编译并成功。未修改全局 Gradle 设置或删除共享缓存来隐藏问题。

产物：`demo/build/outputs/apk/debug/demo-debug.apk`。
测试报告：`lib/build/reports/tests/testDebugUnitTest/index.html`、`lib/build/reports/tests/testReleaseUnitTest/index.html`。
本地过程日志位于忽略目录 `.gradle/review-stable-validation.log`、`.gradle/review-regression-red.log`、`.gradle/review-final-lint.log`。补齐依赖后需重新执行 lint，不能将本轮结果写成全部检查通过。

## 明确保留的范围边界

- **未实现原厂 SF-VSYNC provider、UX 调度及 CPU boost**。公开 Choreographer 是可移植帧源，不是上述能力的等价实现。
- **未实现完整 CustomRectFSpringAnim / MultiAnimatorSet / SurfaceControl 转场链路**，不能把 Demo 自绘画面当作迁移验收。
- 异步动画实例复用、运行中切换 executor、全局 feature 关闭时的在途任务，以及所有 Demo 的完整生命周期仍需专项审查；本轮不将其标成已修复。
- 未重新采集/分析原始 Perfetto trace，未进行设备验证；源码和既有 trace 记录分别作为证据。

## 历史记录校正

`64d3bab` 的提交日期是 2026-09-09。引用该提交但标成 2026-09-11 的 v3 复核标题已更正。历史文档保留当时分析内容，但涉及上述范围时以本记录和各分项头部的复核说明为准。
