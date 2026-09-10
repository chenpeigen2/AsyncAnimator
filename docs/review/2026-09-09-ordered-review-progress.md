# Review 顺序执行清单（2026-09-09）

按文件顺序核对建议、当前代码和 OPPO 参考树；旧版文档先核对迁移状态，不依据过期结论回退实现。
“完成”表示建议已经实现并验证，或逐项写明不移植的理由；不代表 SF-VSYNC、ROM 私有功能或真机性能已经对齐。
历史修复记录只作索引，不代替本轮核查。不自动提交、不修改 OPPO 参考树。

## 文件队列

| 顺序 | Review | 本轮状态 | 证据 / 剩余工作 |
|---|---|---|---|
| 01 | [01-async-animthread.md](01-async-animthread.md) | ✅完成 | 六项建议逐条处理；新增帧源/线程/结束契约测试，定向 11 tests 通过 |
| 02 | [02-pending-playback.md](02-pending-playback.md) | ✅完成 | 六项建议已处理；四个插值工具补齐，定向 14 tests 通过 |
| 03 | [03-controller-manager-seq.md](03-controller-manager-seq.md) | ✅完成 | 七项建议逐条处理；Between 6 tests 红→绿，关联定向 40 tests 通过 |
| 04 | [04-frame-spring-continuation.md](04-frame-spring-continuation.md) | ✅完成（含保留简化） | 修复续行值输出/property 重绑/duration 参数；4 tests 红→绿，定向 15 tests 通过 |
| 05 | [06-vs-oppo-public-api-and-callsites.md](06-vs-oppo-public-api-and-callsites.md) | ✅完成（可移植适配） | 六项建议落实；Multi 18 + timeout ownership 2 tests，Debug/Release 重跑验证见下；非 OEM 几何引擎 |
| 06 | [vs-oppo-01-async-thread.md](vs-oppo-01-async-thread.md) | ✅完成（可移植协议） | 七项建议逐条决策；Rect 16 + executor 3 新 tests；Debug/Release 各 141 全通过 |
| 07 | [vs-oppo-02-pending-playback.md](vs-oppo-02-pending-playback.md) | ✅完成（含保留简化） | 六项建议及六项简化逐条核对；14 条相关回归通过，纠正 getter/PropertySetter/ActualEnd 旧描述 |
| 08 | [vs-oppo-03-controller.md](vs-oppo-03-controller.md) | ✅完成（含保留简化） | 十项建议/八项简化已核对；Scene 9、Recents 5、Seq feature 4 新 tests；全量各 159 通过 |
| 09 | [vs-oppo-04-frame-scheduling.md](vs-oppo-04-frame-scheduling.md) | ✅完成（含保留简化） | 六项建议/七项简化/三项可选已处理；handoff 5 红→绿、隔离 1 新 test；全量各 165 通过 |
| 10 | [vs-oppo-05-continuation-spring.md](vs-oppo-05-continuation-spring.md) | ✅完成（含保留简化） | 六项建议/七项简化已处理；Int/Float holder 与复制时长修复，3 红→绿，新增 4 tests；全量各 169 通过 |
| 11 | [vs-oppo-06-public-api.md](vs-oppo-06-public-api.md) | ✅完成（可移植公开面） | 五项建议/四项简化逐条决策；事件桥 + Demo6 实际状态链，3 新 tests；Debug/Release 各 172 通过 |
| 12 | [vs-oppo-07-concurrency.md](vs-oppo-07-concurrency.md) | ✅完成（含保留简化） | 六项建议/十项简化已核对；9 新 tests（6 红→绿），Debug/Release 各 181 通过 |
| 13 | [vs-oppo-08-trace-observability.md](vs-oppo-08-trace-observability.md) | ✅完成（可移植诊断） | 七项建议/七项简化已处理；6 新 tests（4 红→绿）；两构建变体各 187 通过，非 Perfetto |
| 14 | [vs-oppo-09-lifecycle-cleanup.md](vs-oppo-09-lifecycle-cleanup.md) | ✅完成 | 最终 animator.dispose、manager 关闭清理、Demo3/10/11 接线；修正真实 VSYNC sandbox 隔离，新增 12 tests |
| 15 | [vs-oppo-10-kotlin-idiomaticity.md](vs-oppo-10-kotlin-idiomaticity.md) | ✅完成 | 动画执行器独立 lazy；Java 互操作与参数复制 5 新 tests；纠正语言语义，双变体各 204 通过 |
| 16 | [vs-oppo-11-feature-gaps.md](vs-oppo-11-feature-gaps.md) | ✅完成 | 主线程 touch gate 接 Demo9；配置订阅/快照/Adaptive 接 Demo8；13 新 tests，双变体各 217 通过 |
| 17 | [vs-oppo-12-runtime-risks.md](vs-oppo-12-runtime-risks.md) | ✅完成 | Controller 完成先归位/消费，防重入/异常清新轮；补非法状态日志与 idle 保留清理；7 新 tests，双变体各 224 通过 |
| 18 | [vs-oppo-13-anim-thread-init.md](vs-oppo-13-anim-thread-init.md) | ✅完成 | 首条 owner 任务前安装、Looper 提前发布时序；纠正 UX/帧源/执行器说明，拒绝无需求全局 hook；2 新 tests，双变体各 226 通过 |
| 19 | [vs-oppo-14-animation-handler-kernel.md](vs-oppo-14-animation-handler-kernel.md) | ✅完成 | 保留 fail-loud/代次换源；补时间戳、同帧移除/计数、清理前 re-add 和全局测试覆盖隔离；4 新 tests，双变体各 230 通过 |
| 20 | [vs-oppo-15-tickscheduler-dual.md](vs-oppo-15-tickscheduler-dual.md) | ✅完成 | 固定首次成功 Choreographer 帧源、防跨 Looper 重启迁移；状态短锁/锁外快照回调；6 新 tests，双变体各 236 通过 |
| 21 | [vs-oppo-16-customrect-spring.md](vs-oppo-16-customrect-spring.md) | ✅完成（含明确差异） | 六轴 AndroidX driver、预测/速度/progress 接续、Demo12；260 tests/37 类及 Demo 验证通过 |
| 22 | [vs-oppo-17-multianimset.md](vs-oppo-17-multianimset.md) | ✅完成（含明确差异） | volatile 取消信号、观察者隔离、新旧轮停止/ID 隔离；266 tests/37 类及 Demo 验证通过 |
| 23 | [vs-oppo-18-frame-callback-multispring.md](vs-oppo-18-frame-callback-multispring.md) | ✅完成（含明确差异） | native cancel/skip/首帧、长帧/阈值取证及回归；272 tests/38 类及 Demo 验证通过 |
| 24 | [vs-oppo-19-launcher-animation-runner.md](vs-oppo-19-launcher-animation-runner.md) | ✅完成（系统层保留） | 明确 factory/target 所有权、纠正 NPE/空 hook 假设；275 tests/39 类及 Demo 验证通过 |
| 25 | [vs-oppo-20-state-transition-table.md](vs-oppo-20-state-transition-table.md) | ✅完成（含明确差异） | 旧 Recents callback/Seq 清理、window 查询补齐；282 tests/40 类及 Demo 验证通过 |
| 26 | [vs-oppo-21-delay-start-activity-decision-tree.md](vs-oppo-21-delay-start-activity-decision-tree.md) | ✅完成（含明确差异） | 旧决策代次/监听身份校验、门控诊断；289 tests/41 类及 Demo 验证通过 |
| 27 | [vs-oppo-22-task-state-helper.md](vs-oppo-22-task-state-helper.md) | ✅完成（含明确边界） | 主/清理双异常保留、生命周期诊断、并发释放边界；295 tests/41 类及 Demo 验证通过 |
| 28 | [vs-oppo-23-pending-e2e-flow.md](vs-oppo-23-pending-e2e-flow.md) | ✅完成（含明确边界） | 平台属性起点、嵌套时长/曲线继承、wrapper build 幂等；302 tests/41 类及 Demo 验证通过 |
| 29 | [vs-oppo-24-apc-dispatch-contract.md](vs-oppo-24-apc-dispatch-contract.md) | ✅完成（含明确边界） | 空闲 forceClose、完成回调快照/重入、零时长 NaN；311 tests/42 类及 Demo 验证通过 |
| 30 | [vs-oppo-25-seq-helper-pair.md](vs-oppo-25-seq-helper-pair.md) | ✅完成（含明确边界） | 序号两写入口补 interruption gate，共享计数器/跨截止点回归；315 tests/42 类及 Demo 验证通过 |
| 31 | [vs-oppo-26-anim-seq-timestamp.md](vs-oppo-26-anim-seq-timestamp.md) | ✅完成（含明确边界） | 零时刻/未记录分离、clock 内部化及恢复、十二处门控诊断；321 tests/43 类及 Demo 验证通过 |
| 32 | [vs-oppo-27-feature-helper.md](vs-oppo-27-feature-helper.md) | ✅完成（含明确边界） | 列表准备失败不留半批配置，Demo8 明示数据/工厂开关边界；324 tests/43 类及 Demo 验证通过 |
| 33 | [vs-oppo-28-oplusanim-manager-helpers.md](vs-oppo-28-oplusanim-manager-helpers.md) | ✅完成（系统层保留） | 冷首访/配置工厂边界/清理/no-op 验证，纠正 lazy/默认实现/缺类 NPE 误报；328 tests/44 类及 Demo 通过 |
| 34 | [vs-oppo-29-trace-tag-consistency.md](vs-oppo-29-trace-tag-consistency.md) | ✅完成（诊断边界明确） | 排队 id/type 快照、dispose 清类型、具名 VIEW tag；331 tests/44 类及 Demo 通过 |
| 35 | [vs-oppo-30-kotlinization-risks.md](vs-oppo-30-kotlinization-risks.md) | ✅完成（兼容边界明确） | Java Function0/Default/never-quit 回归，修正文档接口与初始化误报；333 tests/44 类及 Demo 通过 |
| 36 | [vs-oppo-31-build-gradle-drift.md](vs-oppo-31-build-gradle-drift.md) | ✅完成（环境限制保留） | SDK/JDK 只读预检及集成边界，拒绝无效 keep/全导出/降支持线；333 tests/44 类及 Demo 通过，Lint 依赖仍缺 |
| 37 | [vs-oppo-32-demo-manifest-export.md](vs-oppo-32-demo-manifest-export.md) | ✅完成（系统入口保留） | 共享 stderr 订阅/交错释放、12 内页显式不导出；库333+Demo6 用例双变体及 APK/manifest 通过 |
| 38 | [vs-oppo-33-doc-consistency.md](vs-oppo-33-doc-consistency.md) | ✅完成（仅文档） | USAGE 清过期清单/可见性/身份误述；两 SUMMARY 重建当前索引，源码 hash 不变复用37份验证 |
| 39 | [vs-oppo-34-test-coverage-gaps.md](vs-oppo-34-test-coverage-gaps.md) | ✅完成（测试边界明确） | A1–A8逐项核对，补1000轮/4000次并发读取回归；库334+Demo6，四组单测及APK全绿 |

## 验证记录

- 本轮开始前：上一轮 Debug / Release 各 72 tests 通过，demo Debug 可构建；不计为本轮新增验证。
- Lint 之前因离线缺少 lint 依赖未完成；无设备回归 / Perfetto 数据。

## 当前交付范围

- **39 / 39 份完成本轮建议验收**：旧版 01、02、03、04、06 及详细版 01～34；“完成”中保留了有明确理由的简化项。
- **0 份待顺序验收**：所有建议逐项实施或写明保留/不采纳理由。设备、系统集成、Lint及干净SDK等限制仍见各份边界，不能把队列完成等同这些能力已经验证。
- 顺序阶段库累计新增 **262 tests**（72 → 334），共 **44 个库测试类**；第 37 份另增 Demo 1 类/6 用例，仓库合计 45 类/340 独立用例。前四份新增 30；第五份新增 Multi 18 + TimeoutOwnership 2；第六份新增 Rect 16 + executor 3；第八份新增 18；第九份新增 6；第十份新增 4；第十一份新增 3；第十二份新增 9；第十三份新增 6；第十四份新增 12；第十五份新增 5；第十六份新增 13；第十七份新增 7；第十八份新增 2；第十九份新增 4；第二十份新增 6；第二十一份新增 24；第二十二份新增 6；第二十三份新增 6；第二十四份新增 3；第二十五份新增 7；第二十六份新增 7；第二十七份新增 6；第二十八份新增 7；第二十九份新增 9；第三十份新增 4；第三十一份新增 6；第三十二份新增 3；第三十三份新增 4；第三十四份新增 3；第三十五份新增 2；第三十九份新增 1。已包含四轨聚合和 Demo 9 实际接线，不包含 OEM 物理/窗口系统能力。
- 既有 AGENTS.md、OPPO 参考树和无关日志/图片未修改；未提交或推送。

## 前四份验证记录（102 tests，保留历史证据）

```powershell
.\gradlew.bat :lib:testDebugUnitTest :lib:testReleaseUnitTest :demo:assembleDebug `
  --rerun-tasks --offline --no-daemon --no-build-cache `
  --no-configuration-cache --max-workers=1 "-Pkotlin.incremental=false" --console=plain
```

- **BUILD SUCCESSFUL，2m 40s，76/76 tasks executed**。
- Debug、Release 分别 **102 tests，0 failures / errors / skipped**；两变体共用同一批用例，不计作 204 个独立测试。
- `AnimationBetweenStateTest`：6/6 在旧代码失败、修复后通过（`.gradle/review-ordered-03-red.log` / `-green.log`）。
- `OplusValueAnimatorTest`：4/6 在旧代码失败、修复后 6/6 通过（`.gradle/review-ordered-04-red.log` / `-green.log`）。
- 第一份帧源测试最初一次性推进 paused clock 的方式不正确，改为逐帧推进后通过；不将该测试夹具失败计作产品 bug。
- 最终构建日志：`.gradle/review-ordered-01-04-final.log`（忽略目录，不入 Git）。
- 单测 XML：`lib/build/test-results/testDebugUnitTest/`、`testReleaseUnitTest/`。
- APK：`demo/build/outputs/apk/debug/demo-debug.apk`。
- `git diff --check`、本轮新增/验收文档相对链接检查通过。
- **未验证**：设备安装/回归、真实同步屏障/刷新率/Perfetto、OEM SF-VSYNC/UX/UAF。Lint 未完成（此前离线缺 `intellij-core-31.9.0.jar` / `kotlin-compiler-31.9.0.jar`）。既有 Trace nullability、SDK XML 与 Gradle 警告仍在。

## 第五份验证记录（122 tests）

- 六项建议逐条结果见 [public API / callsites 验收](06-vs-oppo-public-api-and-callsites.md)。三条标量注册槽按实例身份摘除，没有额外制造全局 TaskStateHelper 总线；Rect 为显式 Driver/Animator 适配，并未移植物理引擎。
- `TimeoutOwnershipTest.testExplicitDisposeRemovesAllThreeOwnerSlotsAndPendingActions` 在修复前失败，修复后通过；两条测试覆盖三种注册及重入替换。日志 `.gradle/review-ordered-05-red.log`。
- 新增 `MultiAnimatorSetTest` 18 tests，含真实 HandlerThread start/end 与结束回主线程的检查。8 个 mask 的组合已包含在测试计数内，不再重复累计。
- 首轮两个 Multi 断言把 child Animator.end 当作整个 AnimatorSet.end；测试改为结束真实轨道根对象，不把夹具错误计作产品 bug。
- 全量曾出现原帧源 3 tests 不收帧，关联筛选/不同类初始化顺序下可通过。Robolectric `ShadowDisplayEventReceiver` 使用共享 next-VSYNC 时钟，`ShadowPausedSystemClock.staticListeners` 跨测试保留；为帧源测试设置独立 sandbox 配置，避免其他 Looper 的接收器干扰。未修改帧断言、未跳过用例、未删真实后台 Looper 测试，所有临时诊断输出已移除。
- 独立 sandbox 后首次强制 Debug + Demo 构建：**122 tests 全通过，61/61 tasks executed，1m 4s**（`.gradle/review-ordered-05-sandbox-check.log`）。
- 再次执行上方同一全量命令：**Debug / Release 各 122 tests，0 failures/errors/skipped；BUILD SUCCESSFUL，76/76 tasks executed，1m 40s**（`.gradle/review-ordered-05-final.log`）。两种变体不是 244 个独立测试。
- Kotlin 源码/测试及模块构建配置在最终验证前后 SHA-256 一致；`git diff --check` 与本轮文档相对链接检查通过。
- APK：`demo/build/outputs/apk/debug/demo-debug.apk`。未安装或进行真机回归/Perfetto，Lint 未完成，不声称后台 View 弹簧或 SF-VSYNC 已对齐。

## 第六份验证记录（141 tests）

- 七项建议与适配边界见 [async/thread 验收](vs-oppo-01-async-thread.md)。Rect 双阶段结束不等于 OEM 六轴几何引擎；动画线程是 driver 显式能力，不是后台 View 写入许可。
- 新增 Rect 16 与 executor 3 tests；新监听器收到前一轮 actual-end 的回归先红后绿。定向 Rect + Multi + executor 39 tests 全通过；全量命令同上。
- **Debug / Release 各 141 tests，19 类，0 failures/errors/skipped；BUILD SUCCESSFUL，76/76 tasks executed，1m 19s**（`.gradle/review-ordered-06-final.log`）。共 141 个独立测试，不重复累计两变体。
- 源码/测试及 lib 构建配置验证前后 SHA-256 一致（`.gradle/review-ordered-06-before-validation.json`）；Demo 配置文件实际为 `demo/build.gradle`，单独记录哈希，不误报不存在的 build.gradle.kts 已参与前置快照。
- `git diff --check` 通过；APK 路径不变。没有设备/Perfetto 验证；Lint 未完成，既有构建警告保留。不自动 commit。

## 第七份验收记录（无新增源码）

- 六项回移建议、六项简化逐项核对；递归派发和四个数学工具已有实现，不重复改代码。
- 明确 PropertySetter 默认直接写属性且没有 add、APC 两项 private 查询不暴露、ActualEnd 已独立分层；不是所有接口都和 OEM 等价。
- 相关 Pending/APC/Interpolators 14 tests 已在第六份 Debug/Release 全量各 141 tests 中通过，未新增/重复累计用例。

## 第八份验证记录（159 tests）

- Controller 十项建议/八项简化逐条处理，详见 [当前验收](vs-oppo-03-controller.md)。场景信息由 AppExitScene/GestureScene 显式提供；未伪造 OEM 系统读取/TaskState 总线。
- Scene 9 tests 中 7 条修复前失败后通过；原 controller/Between/launch/ownership 断言保留，显式补充场景输入后合计 56 tests 定向通过。
- RecentsFinishGateTest 5 + AnimationSeqFeatureGateTest 4；Seq 立即完成替换旧排队任务先失败 `[new, old]`，修复后只执行 `[new]`。本份新增 18 tests，两个 16 组合矩阵包含在单测内部，不重复累计。
- **Debug/Release 各 159 tests，22 类，0 failures/errors/skipped；Demo Debug 构建成功，76/76 tasks executed，1m 23s**。日志 `.gradle/review-ordered-08-final.log`。
- 源码/测试及两个模块实际构建配置在验证前后 SHA-256 一致（`.gradle/review-ordered-08-before-validation.json`）；`git diff --check` 通过。无设备/Perfetto 验证，Lint 未完成，无提交/推送。

## 第九份验证记录（165 tests）

- 帧源安装 fail-loud 与 owner 订阅换源协议已落地；Handoff 5 tests 旧代码全部失败，修复后通过。handler 异常隔离增加 1 条回归；定向 15 tests 全通过。
- **Debug/Release 各 165 tests，23 类，0 failures/errors/skipped；Demo Debug 成功，76/76 tasks executed，1m 22s**（`.gradle/review-ordered-09-final.log`）。源码/测试及两模块配置前后 SHA-256 一致。
- `git diff --check` 通过。Markdown 检查发现旧汇总指向不存在的 vs-oppo-13-customrect-spring，已修正为实际 vs-oppo-16-customrect-spring；此链接修复不代表汇总或第 16 区域整篇验收。
- 换源不保证不同 provider 真机相位无缝衔接；不使用隐藏 SF API，不宣称替换平台 Animator 的帧核。设备/Perfetto 未验证，Lint 未完成。

## 第十份验证记录（169 tests）

- OplusValueAnimatorTest 由 6 增至 10：controller 时间委托、整数续行、Int→Float 切换、非正 continuation duration 使用复制时长。
- 旧代码 3 条失败（ClassCastException/整数 50 实得 Float 0.5、期望 700ms 实得 300ms），修复后 10/10 通过：`.gradle/review-ordered-10-red.log` / `-green.log`。
- **Debug/Release 各 169 tests，23 类，0 failures/errors/skipped；Demo Debug 成功，76/76 tasks executed，1m 34s**（`.gradle/review-ordered-10-final.log`）。两个变体不计作 338 个独立测试。
- 源码/测试及两模块配置 SHA-256 前后一致（`.gradle/review-ordered-10-before-validation.json`）；AGENTS.md 无 diff，参考 OPPO 代码只读。
- APK：`demo/build/outputs/apk/debug/demo-debug.apk`。未安装/进行设备回归或 Perfetto；Lint 未完成。四路 OEM 续行业务、通用 Any/evaluator 工厂仍保留简化，不混作已移植。

## 第十一份验证记录（172 tests）

- 新增 controller.dispatchTaskStateChange 按注册槽交付事件；Demo6 不再把事件调用标成 timer，并用公开方法实际到达 WAITING/REVERSE_OPEN，状态图不再伪造这两个状态。
- TaskStateEventTest 3 tests 覆盖三场景匹配/不匹配/重复事件与 timer、dispose/feature-off、Demo6 完整状态链；组合包含在用例内。
- **Debug/Release 各 172 tests，24 类，0 failures/errors/skipped；Demo Debug 成功，76/76 tasks executed，1m 21s**（`.gradle/review-ordered-11-final.log`）。源码/测试/模块配置验证前后 SHA-256 一致。
- 未实现全局 TaskStateHelper、系统 multi-app merge 或后台 View 帧调度；修正 AsyncSpring 与 listener 身份的错误 KDoc。无设备/Perfetto 验证，Lint 未完成。

## 第十二份验证记录（181 tests）

- ControllerThreadContractTest 6 + FeatureSnapshotTest 3 新增测试，旧代码 6 条失败：后台状态/observer/callback/owned timeout 修改，外部可变列表注入，部分更新覆盖较新 onePx 配置。
- 修复后定向 19 tests 全通过；包含 17 个入口的矩阵仍只计一个测试。Standalone timeout 的原子调用线程语义、feature-off 无状态 no-op 均保留。
- **Debug/Release 各 181 tests，26 类，0 failures/errors/skipped；Demo Debug 成功，76/76 tasks executed，1m 37s**（`.gradle/review-ordered-12-final.log`）。源码/测试/模块配置前后 SHA-256 一致。
- 不声称多字段无锁读是原子快照，不提供未经测量的性能提升百分比。无设备/Perfetto 压力验证，Lint 未完成。

## 第十三份验证记录（187 tests）

- TraceLogTest 新增 6 tests；旧 Trace/dispatch 4 条失败，修复后含 Async/Seq/Oplus 的 41 条定向测试通过。证明同线程配对、实际主线程 listener 范围、异常收尾、门控切换与 Debug/Release 默认策略，不是 Perfetto 数据。
- **Debug/Release 各 187 tests，27 类，0 failures/errors/skipped；Demo Debug 成功，80/80 tasks executed，1m 22s**（`.gradle/review-ordered-13-final.log`）。因启用 lib BuildConfig 生成，任务数量与此前 76 不同。
- 源码/测试及两模块构建配置验证前后 SHA-256 一致（`.gradle/review-ordered-13-before-validation.json`）；`git diff --check` 通过，AGENTS.md 无改动。
- APK：`demo/build/outputs/apk/debug/demo-debug.apk`。无设备/Perfetto 验证，Lint 未完成；Trace 原 ThreadLocal nullability 警告已修正，SDK/Gradle/私有注解相关既有构建警告仍存在。

## 第十四份验证记录（199 tests）

- 新增 AsyncValueAnimatorLifecycleTest 7 + ManagerLifecycleTest 4 + TimeoutOwnershipTest 1；覆盖排队 start 失效、重入释放、固定 owner、真实 HandlerThread cancel、feature-off 撤回三个 timer/挂起启动/Seq 与无实例绑定的线程 guard。
- Demo3/10 统一 animator.dispose；Demo10 移除初始启动/过滤旧 UI 代次、每轮独立统计，后台只计算；Demo11 明确主线程回退并移除原生监听/沿启动包装器取消，不伪称后台 AndroidX View 引擎。
- **此前第 13 份 sandbox 解释更正**：4.16 会归并冗余 android.view 配置，原配置未真正隔离。全量两次出现 3 条 VSYNC 失败，缩小组合可通过；源码确认静态 receiver/next-vsync 的共享窗口。现用非冗余 com.asyncanimator.core 配置隔离，原 3 条断言/SDK/真实线程测试均保持；先完整 Debug 199 条通过，再双变体重跑通过。
- **Debug/Release 各 199 tests，29 类，0 failures/errors/skipped；Demo Debug 成功，80/80 tasks executed，1m 57s**（`.gradle/review-ordered-14-final.log`）。源码/测试及 lib/build.gradle.kts、demo/build.gradle 验证前后 SHA-256 一致。
- `git diff --check` 通过。AGENTS.md/OPPO 参考树未改；无设备/Perfetto 验证，Lint 未完成，无提交/推送。

## 第十五份验证记录（204 tests）

- 修复 MAIN 访问顺带创建动画线程：ANIM_CONTROL_EXECUTOR 独立 synchronized lazy。原线程初始化状态测试先因 main lookup must leave the animation thread uninitialized 失败，修复后与 Java/copy 的 15 条定向回归通过。
- 新增 JavaApiInteropTest 3 + ExecutorInitializationTest 1 + OplusValueAnimatorTest 1，共 5 tests；Java 源实际编译，不以 Kotlin 反射替代 Java API 兼容验证。8 路并发首次访问包含在单一测试内。
- 原 Review 的 15 项语言风险、10 项建议、14 项简化与 10 项语法说明重新核对；不引入无调用方/取消契约的协程 API，不伪造 lambda 深拷贝或 OEM 二进制兼容。
- **Debug/Release 各 204 tests，31 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，2m 14s**（`.gradle/review-ordered-15-final.log`）。新增 Java 测试使两个变体的 javac task 实际执行。源码/测试/两模块配置 SHA-256 前后一致。
- `git diff --check` 与相对链接检查通过。无设备/Perfetto 验证；Lint 未完成，Java API deprecation 和既有构建警告保留；AGENTS.md/OPPO 树未改，无提交/推送。

## 第十六份验证记录（217 tests）

- TouchGateTest 6 条旧代码全失败，补 Controller 600ms 主线程闸门、end/reset/destroy 清理、状态/挂起 action 判据后通过；Demo9 实际接触摸查询与聚合开窗生命周期，返回键/收尾不冒充 OEM 输入拦截。
- FeatureNotificationTest 7 新 tests：后台发布→主线程通知，精准关闭/旧注册失效，全局释放，重入/异常隔离，显式 Adaptive 策略和跨字段一致快照。Demo8 由订阅刷新，退出仅关闭自有句柄；没有连接真实 RUS。
- 原厂证据更正：AnimationController.java:95-100 的 touch Handler 是主线程；TaskStateHelper.java:130 的 timeout 才使用 urgent transaction。整数配置默认值为六个 -1，threshold 为 1.0f；不再沿用原报告错行号/错误计数。
- **Debug/Release 各 217 tests，33 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，1m 53s**（`.gradle/review-ordered-16-final.log`）。源码/测试/两模块配置 SHA-256 前后一致，`git diff --check` 通过。
- 无设备/Perfetto 验证，Lint 未完成；AGENTS.md 和 OPPO 源码未改，无提交/推送。真 RUS、远程 merge/prestart、六轴物理与 UX/UAF 明确未移植，不算实现完成。

## 第十七份验证记录（224 tests）

- 运行时交叉检查新增 ControllerCompletionTest 7 条：初始 5 条旧代码全失败；修复前置归位/回调消费与非法状态日志后，含 Controller/触摸/Manager 的 34 条定向回归通过。另覆盖旧回调启动新轮后抛异常、新轮仍存活。
- 补测发现初版 idle 直接 return 会跳过临时手势/挂起启动清理（7 条中 1 条失败）；改为静默 reset，既保持清理又不重复发送 NONE。最终全量验证覆盖全部 7 条，没有停留在首次 223 条通过的中间状态。
- 本库完成顺序明确调整为旧轮内部 reset/NONE 通知→捕获的 recents/launch 完成回调；全部完成动作尝试执行后重抛首个异常，其他异常作为 suppressed，不抹掉回调启动的新轮。此补强不冒称 OEM 原有顺序。
- **Debug/Release 各 224 tests，34 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，2m 23s**（`.gradle/review-ordered-17-final.log`）。源码/测试/两模块构建配置 SHA-256 前后一致，`git diff --check` 通过。
- 无设备压力/Perfetto 验证；Lint 未完成，既有构建告警保留。原 10 项遗漏/11 项风险/7 项建议/7 项简化逐项核对；未修改 OPPO/AGENTS.md，未提交或推送。

## 第十八份验证记录（226 tests）

- 对照 OPPO OplusExecutors/OplusLooperExecutor/Executors 与本地 SDK 36 HandlerThread 源码，纠正 Looper 发布、优先级设置与 onLooperPrepared 的时序；保留带日志的优先级重申，不虚构 UX/UAF 或 SF-VSYNC 能力。
- 新增 AnimationThreadBootstrapTest 2 条，检查实际冷启动首条业务消息前已有自有 ThreadLocal scheduler、owner/name/-19，以及受控准备期间可取得 Looper/post 但任务不能提前执行。负对照临时移除安装后 1/2 失败，恢复后定向 13 条全通过。
- **Debug/Release 各 226 tests，35 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，1m 42s**（`.gradle/review-ordered-18-final.log`）。108 份源码/测试/两模块配置 SHA-256 前后一致。
- 原 10 个对应项、6 个风险、9 个建议逐条处置；不加无调用方/注册契约的全局 onThreadReady hook。修正 daemon 继承、-19 常量、已有 Rect 桥及执行器描述。
- `git diff --check` 和文档链接通过；无设备/Perfetto 验证，Lint 未完成；AGENTS.md、OPPO 与无关文件未改，无提交/推送。

## 第十九份验证记录（230 tests）

- AnimationHandler 原成员表、32 个保真度/遗漏项、11 个风险、20 个建议全部核对。安装 fail-loud、同帧活取 size、旧源仅撤自身订阅/代次过滤此前已修复，不重复改运行算法；拒绝旧“只替换 holder”的不完整换源方案。
- 补 AnimationHandlerTest 3 条（时间戳截断、同帧移除/压缩顺序与计数、null 和清理前 re-add 单订阅）及 AnimationSchedulerHandoffTest 1 条（全局 testHandler 覆盖拒绝 install/replace、计数与 finally 恢复、ThreadLocal 未污染）。定向 16 条通过。
- **Debug/Release 各 230 tests，35 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，1m 38s**（`.gradle/review-ordered-19-final.log`）。108 份源码/测试/两模块配置 SHA-256 前后一致，`git diff --check` 和文档链接检查通过。
- KDoc 明确 owner 串行、volatile 不保护列表、Boolean 返回不自动取消、companion 计数/全局测试覆盖边界。无需求的 delayed callback、Java facade、空 provider hook 不扩展；不伪称 A/B/C 内核完整合并。
- 无设备/Perfetto 验证；Lint 未完成、既有构建警告保留。AGENTS.md、OPPO 和无关文件未改，无提交/推送。

## 第二十份验证记录（236 tests）

- 旧双定时器实现已不存在，未按过时 review 恢复 fixedRate/fixedDelay/drift/daemon 代码。重写原 12 个映射、7+9+5 个判断、10 个风险和 7+7+4 个建议；明确 app VSYNC/SF、frameDelay/刷新率及快照/活列表的边界。
- 追查现实现发现每次 restart 重新取调用线程 Choreographer，可迁移帧源。改为固定首次成功 source，状态/入列/空队列收尾使用短锁，业务回调在锁外。获取失败仍兼容 no-op 且不永久缓存，stop/start 可重新尝试。
- 新增 Owner 1 + scheduler 5，共 6 tests。旧 getter 负对照因 source 身份不同失败；修复后验证真实 HandlerThread 两帧归属不变。首版夹具未先排空跨线程调度消息，旧/新均可能缺恢复帧，已修夹具且不算产品失败。定向 26 条通过；最后补的无 Looper 兼容回归纳入最终全量。
- **Debug/Release 各 236 tests，36 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，1m 50s**（`.gradle/review-ordered-20-final.log`）。109 份源码/测试/两模块配置 SHA-256 前后一致，`git diff --check` 和文档链接通过。
- 无设备/Perfetto 验证，Lint 未完成；既有警告保留。OPPO、AGENTS.md 和无关文件未改，无提交/推送。

## 第二十一份验证记录（260 tests）

- 新增 RectSpringDriver/Config/Frame 与纯 SpringProjection：六个真实 AndroidX springs、显式公开 scheduler、三种 tracking、size/ratio 坐标转换、独立参数/alpha 延迟、retarget/reverse、预测和六速度/progress 接续。Demo12 已接入入口/Manifest，View 写入统一主线程。
- 句柄可选 DisposableDriver 立即撤销帧订阅并排空 AndroidX native 清理任务；观察者异常隔离避免卡住物理 barrier。真实 Multi 取消等待六轴实际完成；后台 owner、错误线程及 native duration-scale 监听器回收均有测试。
- 观察者回归先失败；3 条进度回归先失败，再修复续行 base/方向与预测终态。最终定向 58 条通过，全量 **Debug/Release 各 260 tests，37 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，2m**（`.gradle/review-ordered-21-final.log`）。114 份源码/测试/两模块配置 SHA-256 前后一致。
- 原 10 个风险与 10+7 个建议逐项处置；保留公开 AndroidX uptime 内核、额外 host 倍率、坐标变换单轴 warm-up 和 16ms 预测假设的差异，不冒称 OEM SurfaceControl/SF/Adaptive 联动实现。
- `git diff --check` 与文档链接通过；无设备/Perfetto 验证，Lint 未完成，既有告警保留。AGENTS.md、OPPO 和无关文件未改，无提交/推送。

## 第二十二份验证记录（266 tests）

- 四通道/两种 Boolean play 重载/mask/ID/spring 解绑/Demo9 均按当前代码重新核对；六轴能力承接第 21 份，不重复创建隐藏 MultiDynamicAnimation。原 6+7+3 风险、5 项建议/5 项简化和三阶段计划逐条处置。
- 实际修复 hasRequestCancel 的 volatile 发布；聚合 start/cancel/end/reset 观察者 Exception 隔离；同步完成重入后旧 cancel/end 不再停止新轮 spring；完成前捕获旧 ID、消费 live 清理集合，防外部 cancel 观察者重写 ID/新轮清理集合。
- 新增 6 tests：首批 4 条旧代码全失败，随后旧轮停止新 spring、旧 ID 变成新 ID 各 1 条先失败。修复后定向 64 条通过。**Debug/Release 各 266 tests，37 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，2m 3s**（`.gradle/review-ordered-22-final.log`）。114 份源码/测试/两模块配置 SHA-256 前后一致。
- 保留非空 Animator listener、独立 spring listener、主线程普通 spring、无 SF/窗口事务等差异；纠正旧欠阻尼/过阻尼与 skip 异常、重复字段及保真度百分比说法。
- `git diff --check` 与相对链接通过；无设备/Perfetto 验证，Lint 未完成，既有告警保留。OPPO、AGENTS.md 和无关文件未改，无提交/推送。

## 第二十三份验证记录（272 tests）

- 逐项核对原厂 MultiDynamicAnimation/SpringHolder/SpringForce/反射工具和本地 AndroidX 1.1.0 sources；纠正裸 delta/首帧/skip/cancel/commit 类型、0.75×62.5 阈值、String key 私有性及 ATRACE 的过时判断。保留公开 API 六轴聚合，不复制隐藏内核。
- 新增 AsyncSpringAnimTest 4 条实际 native 用例（owner 立即 cancel、skip 延后、首帧前 skip 预热、零阻尼）；RectSpringDriverTest 新增 2 条（250ms 三阻尼预测对照、实际 force 阈值边界）。更新 KDoc/USAGE，不用 endImmediately/EndReason 空 API 伪造完成。
- 原 11 项风险及 3+7+3 项建议全部处置。原厂请求结束 tick 仍积分且未必到目标，明确不同于本库 Rect cancel 保持当前/skip 强制目标；不声称最后一步“更准确”或固定像素差。
- 定向 29 条通过；**Debug/Release 各 272 tests，38 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，2m 15s**（`.gradle/review-ordered-23-final.log`）。115 份源码/测试/两模块配置 SHA-256 前后一致。
- `git diff --check` 与相对链接通过；无设备/Perfetto 验证，Lint 未完成，既有告警保留。OPPO、AGENTS.md 和无关文件未改，无提交/推送。

## 第二十四份验证记录（275 tests）

- 当前 OPPO LauncherAnimationRunner 直接读取为 642 行/31,550 字符，纠正旧 108 行抽取证据。L1–L26、M1–M6、B1–B5/N1–N4、R1–R5/K1–K6 与文档建议逐条处置。
- 本库嵌套 target 非平台类型、Controller 不读取 target 字段，缺字段不等于运行时 NPE；不存在的方法不是 false/no-op。拒绝 Any? 字段/空 hook 假兼容；Binder、ready、merge、input、图标 registry 明确不移植。
- 更新两个公开类型 KDoc/USAGE，明确 factory 只作本地生命周期身份，宿主负责创建/播放/取消/完成。新增 RemoteAnimationBoundaryTest 3 条，非空/null/empty targets 与 feature-off 实际执行均不取得 factory 播放所有权。
- 定向 13 条通过；**Debug/Release 各 275 tests，39 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，2m 9s**（`.gradle/review-ordered-24-final.log`）。116 份源码/测试/两模块配置 SHA-256 前后一致。
- `git diff --check` 与相对链接通过；无设备/Perfetto 验证，Lint 未完成，既有告警保留。OPPO、AGENTS.md 和无关文件未改，无提交/推送。

## 第二十五份验证记录（282 tests）

- 993 行 OPPO Controller 的四种 switch、cleanup、枚举角色/状态查询与启动副作用逐项对照。旧四个矩阵保留，新增 cleanup 有/无 launch、非手势 end 三个矩阵，合计 84 个状态输入组合，不当成 84 个独立 JUnit 用例。
- 实际补齐新 Recents 清旧完成 callback（状态通知前）、新 launch 撤销 Seq 排队旧 finish，以及 isAppWindowAnimRunning 按 NONE/UNKNOWN 之外返回 true。该查询不是 600ms touch flag，也不保证物理窗口正在绘制。
- 新增 ControllerStateMatrixTest 7 条。旧 callback/Seq 两条先红，window 查询单条先红；修复后定向 43 tests 通过。原风险 5 项、P0/P1/P2 四项建议、7 项简化/6 项遗漏及六项保留逐条验收。
- **Debug/Release 各 282 tests，40 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，2m 14s**（`.gradle/review-ordered-25-final.log`）。117 份源码/测试/两模块配置 SHA-256 前后一致。
- `git diff --check` 与相对链接通过；无设备/Perfetto 验证，Lint 未完成，既有告警保留。OPPO、AGENTS.md 和无关文件未改，无提交/推送。

## 第二十六份验证记录（289 tests）

- 原 11 条目、三个 entry/清理、A/B/C/D 风险、成本表和 4+4 建议逐项核对。betweenTransition 仅日志值，不算挂起谓词；不重复旧 55%/100% 混合统计。global guard/设备事实/宿主事件桥与 OEM 系统能力差异明确保留。
- 实际修复 Supplier/provider 重入 reset/停场景/换 listener、嵌套新决策后旧结果继续 defer/清场的问题。新增决策代次 + 所选 slot 身份校验，失效旧决策返回 false 且不覆写/注销新请求；新增门控分支诊断，不额外求值 provider。
- 新增 LaunchDecisionReentrancyTest 7 条，旧代码 6 失败/截止边界 1 通过。修复后定向 46 tests 通过；**Debug/Release 各 289 tests，41 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，2m 13s**（`.gradle/review-ordered-26-final.log`）。118 份源码/测试/两模块配置 SHA-256 前后一致。
- `git diff --check` 与相对链接通过；无设备/Perfetto 验证，Lint 未完成，既有告警保留。OPPO、AGENTS.md 和无关文件未改，无提交/推送。

## 第二十七份验证记录（295 tests）

- TaskState timeout 同时出现 action/cleanup 异常时保留 action 为主异常、cleanup 为 suppressed；补注册/触发来源/释放日志门控，不重放已消费 action。
- 新增 6 tests：双异常及诊断先红后绿；并发一次性抢占、已领取 action 的 dispose 非屏障语义和非正 timeout 队列行为也有覆盖。定向 36 tests 通过。
- 核对 OEM 七个回调、四个类型与 urgent executor；本地保留主线程三槽事件桥，不伪造系统任务总线、cookie/taskId 或无调用方 TO_HOME。
- **Debug/Release 各 295 tests，41 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，2m 16s**（`.gradle/review-ordered-27-final.log`）。118 份源码/测试/两模块配置 SHA-256 前后一致。
- README 表/Markdown 相对链接、`git diff --check`、AGENTS.md 未改检查通过；无设备/Perfetto 验证，Lint 未完成；无提交/推送。

## 第二十八份验证记录（302 tests）

- setFloat 使用平台单终值 ObjectAnimator，初始化/首次 seek 捕获起点；addFloat 保留显式 from/to。纠正旧“4 参与 3 参始终等价”。
- Pending/addWithoutDuration/wrap 嵌套集合先传播正时长/非 null 曲线再创建 Holder；补 pause reset 恢复继承值。TimeController 兼容 wrapper 保留，build 不再叠加映射监听。
- 新增 7 tests，原 Pending 13 条中 4 失败；修复后 playback + OplusValueAnimator 共 32 条通过。原 64 个编号逐组复核，纠正无取消入口、非 final duration、接口默认 add 等错误，不扩张无调用方 API。
- **Debug/Release 各 302 tests，41 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，2m 8s**（`.gradle/review-ordered-28-final.log`）；118 份源码/测试/两模块配置 SHA-256 前后一致。
- README 表/Markdown 相对链接、`git diff --check`、AGENTS.md 未改检查通过；无设备/Perfetto 验证，Lint 未完成；无提交/推送。

## 第二十九份验证记录（311 tests）

- forceFinishIfCloseToEnd 修正 !isRunning / >95% 条件，避免空闲/暂停时提前成功；完成前置闸门和 action 快照消费，回调重入/修改/异常不再重放旧项或清掉新运行的项。
- 零子时长/零总时长 Holder 在 seek(0) 写终值，修复 0/0 NaN；正时长规则保留。取消后动作保留以支持 pause/start，不采纳无证据的“一次性 Controller”结论。
- 新增 PlaybackCompletionTest 9 条，旧实现 6 失败；修复后定向 playback + OplusValueAnimator 41 条通过；保留既有根监听、DFS、自注销及 pending 五个触点。
- **Debug/Release 各 311 tests，42 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，2m 10s**（`.gradle/review-ordered-29-final.log`）；119 份源码/测试/两模块配置 SHA-256 前后一致。
- README 表/Markdown 相对链接、`git diff --check`、AGENTS.md 未改检查通过；无设备/Perfetto 验证，Lint 未完成；无提交/推送。

## 第三十份验证记录（315 tests）

- SeqHelper 两个序号写入口补现有 interruption provider；关闭时不改 Bundle/pair、不消耗计数器；读取旧 pair 不加 gate，startingSurface 仍只参与时间窗。
- 新增 FeatureGate 4 tests（4→8）：先加三条时旧 7 条中 2 失败；补共享序号流和两次单调读时钟跨 deadline 的队列回归，修复后 Seq/TraceLog/ControllerStateMatrix 定向 34 条通过。
- 原 3-a～f、七个建议及六项简化已处理；更正全局序号、feature-off 判定、默认 action 空操作、钳位死代码、全部无日志和 JVM 签名等错误。不接虚假 RUS/ROM 能力。
- **Debug/Release 各 315 tests，42 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，2m 16s**（`.gradle/review-ordered-30-final.log`）；119 份源码/测试/两模块配置 SHA-256 前后一致。
- README 表/Markdown 相对链接、`git diff --check`、AGENTS.md 未改检查通过；无设备/Perfetto 验证，Lint 未完成；无提交/推送。

## 第三十一份验证记录（321 tests）

- 时间戳未记录改为 null，允许有效零毫秒事件，避免 300/500ms 窗口立刻放行；保留未记录 MAX 比较哨兵，明确不用于任意算术及与 OEM uptime-0 的差异。
- clock 收窄为 internal 测试入口；AnimationSeqHelperTest 补 @After 恢复。四 update/reset/gap 共十二处门控日志，现有 writer monitor/volatile 读取策略不重复改锁，不虚报四字段原子快照。
- 新增 AnimSeqTimeStampTest 6 条，旧代码 3 失败；定向 Seq/TraceLog/ManagerLifecycle 37 条通过，包含四个真实 worker 的完成后发布检查。
- **Debug/Release 各 321 tests，43 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，2m 35s**（`.gradle/review-ordered-31-final.log`）。120 份源码/测试/两模块配置 SHA-256 前后一致。
- README 表/Markdown 相对链接、`git diff --check`、AGENTS.md 未改检查通过；无设备/Perfetto 验证，Lint 未完成；无提交/推送。

## 第三十二份验证记录（324 tests）

- Feature 完整下发先复制两个列表再提交；复制异常不会留下新标量/首个列表，调用方集合遍历在配置锁外。本轮三个回归旧代码全失败，修复后通过。
- FeatureSnapshotTest 3→6；manager + TouchGate 定向 23 tests 通过。保留私有 setter、-1/Adaptive、主线程订阅、同锁 snapshot；Demo8 新增数据字段不自动切换工厂的可见说明，不伪造 ROM derived gate。
- 原十项风险、七项建议、六项简化及四步顺序均核对，纠正无监听/无 snapshot/恒无 touch guard 等旧说法。
- **Debug/Release 各 324 tests，43 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，3m 10s**（`.gradle/review-ordered-32-final.log`）。120 份源码/测试/两模块配置 SHA-256 前后一致。
- README 表/Markdown 相对链接、`git diff --check`、AGENTS.md 未改检查通过；无设备/Perfetto 验证，Lint 未完成；无提交/推送。

## 第三十三份验证记录（328 tests）

- Manager KDoc 修正为 object 首访创建两实现，supportInterruption 固定 true 与本地 toggle 分开；核对 OEM t4.a/b 是可观察属性而非 lazy，Default 原厂类非 abstract。
- 新增 ManagerBootstrap 1 + ManagerLifecycle 3，共 4 tests：八 worker 冷首访共享 owner、配置不重建工厂、Recents 委派完成一次、Default Seq 立即 action/不分配 ID。定向 31 tests 通过；这些是既有行为验收，不冒充四个新产品 bug。
- 工厂两项/helper 十八项风险、十七建议和八简化均核对；保留系统 target merge/按键权限/pre-start 事务边界，不造无调用方成功桩。此前 touch/任务事件/overview运行态/搜索平板/Feature/Seq 修复实际存在。
- **Debug/Release 各 328 tests，44 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，3m 11s**（`.gradle/review-ordered-33-final.log`）。121 份源码/测试/两模块配置 SHA-256 前后一致。
- README 表/Markdown 相对链接、`git diff --check`、AGENTS.md 未改检查通过；无设备/Perfetto 验证，Lint 未完成；无提交/推送。

## 第三十四份验证记录（331 tests）

- Async 回调增加可选类型上下文，投递前捕获 id/type/generation，dispose 清类型；start/cancel/end trace 与四事件日志共用捕获名字。默认 UNSPECIFIED 不冒充业务分类。
- TraceLogTest 6→9，类型快照/释放两项红→绿；嵌套异常/返回值验证既有 finally。三个调用文件统一 TAG_VIEW，纠正 8/32/1024 常量名称与 eager 字符串等旧结论。
- 原七建议/十简化/两不确定项逐条验收；保持 stderr、actual-end log-only，不假造平台过滤、TracePrintUtil 或 Perfetto 成果。
- **Debug/Release 各 331 tests，44 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，2m 59s**（`.gradle/review-ordered-34-final.log`）。121 份源码/测试/两模块配置 SHA-256 前后一致。
- README 表/Markdown 相对链接、`git diff --check`、AGENTS.md 未改检查通过；无设备/Perfetto 验证，Lint 未完成；无提交/推送。

## 第三十五份验证记录（333 tests）

- 十风险/十一建议/六简化逐条核对；纠正 typealias 身份、零 JVM 注解、ExecutorService 子类型、工厂 lazy、共享锁 1/7 吞吐等误报，不回退已实现的主线程/生命周期契约。
- JavaApiInteropTest 3→5，Function0<Unit>/Boolean、两种 Default fallback、三种 shutdown 拒绝及后续派发实测；定向 5 tests 通过，验收既有行为。USAGE 补 Java 适配与实例访问，Default Controller KDoc 修正监听有实际效果。
- **Debug/Release 各 333 tests，44 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，3m 11s**（`.gradle/review-ordered-35-final.log`）。121 份源码/测试/两模块配置 SHA-256 前后一致。
- README 表/Markdown 相对链接、`git diff --check`、AGENTS.md 未改检查通过；无设备/Perfetto 验证，Lint 未完成；无提交/推送。

## 第三十六份验证记录（333 tests）

- B1–B10/十风险/K1–K10 均核对：保留既定 minSdk36/target21，不删 SDK、不全部导出、不用 COUI keep 或 META-INF 排除伪解 duplicate class，不放宽 lint。
- 新增构建环境说明、只读 JDK/SDK 预检及 README 链接，修正 auto-detect/consumer 注释；预检正路径通过、缺 JDK 负例明确拒绝。干净 SDK 复现与工具链升级未声称完成。
- Lint 重新执行仍在 extractDebugAnnotations 缺离线 intellij-core / kotlin-compiler 31.9.0；不是 lint 零错误（`.gradle/review-ordered-36-lint.log`）。
- **Debug/Release 各 333 tests，44 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，3m 1s**（`.gradle/review-ordered-36-final.log`）。128 份源码/测试/构建配置/脚本 SHA-256 前后一致。
- README 表/Markdown 相对链接、`git diff --check`、AGENTS.md 未改检查通过；无设备/Perfetto 验证；无提交/推送。

## 第三十七份验证记录（库333 + Demo6 tests）

- Demo1–11 补显式 exported=false；12 已为 false。共享 TraceLogRedirector 取代每页面包装，订阅独立释放/重入跳过/异常隔离/其他 stderr owner 不覆盖；DemoBase 弱 Activity + finally 清理。
- 新增 Demo 模块 6 个纯 JVM tests：提取旧包装策略复现阶段 3 项失败，新策略 6 通过，不冒称执行过 Activity 旋转仪器测试。入口/实际 Demo6/9 链路与系统权限边界写入 USAGE。
- **库 Debug/Release 各 333 tests/44 类；Demo Debug/Release 各 6 tests/1 类，均 0 failures/errors/skipped。Demo Debug APK 成功，118/118 tasks executed，3m 27s**（`.gradle/review-ordered-37-final.log`）。130 份源码/测试/构建配置/脚本 hash 前后一致。
- 两变体合并 manifest 均为 13 activities：仅 LauncherEntry 导出、12 Demo 显式 false。README/XML/链接/whitespace/AGENTS 检查通过。无设备/Perfetto 验证，Lint 依赖仍缺，无提交/推送。

## 第三十八份验证记录（文档专项，复用37份构建）

- 八风险/六建议/六保留均核对；USAGE 清“仅MAIN/被动接口/函数无身份/未列均internal/Default全部no-op”及补 callback 诊断公开入口。README 当前12Demo/库333+Demo6一致。
- 两 SUMMARY 重建34真实专项索引，旧五份superseded与当前验收说明保留；历史保真度/成本/全修/过期缺口不再充当当前状态。
- 不改源码/配置，130份hash与37份一致，复用该份库/Demo双变体及APK构建结果；未伪造新的Gradle运行。README/XML/合并manifest/Markdown链接/whitespace/AGENTS再次检查通过。
- 无设备/Perfetto验证，Lint及干净SDK复现限制保留；无提交/推送。

## 第三十九份与最终验收（库334 + Demo6 tests）

- A1–A8/十三风险/六简化逐条核对，旧“多体未实现/无日志/无事件/gate未补/触摸释放全缺”均按真实实现改正，系统权限与物理差异明确保留。
- AnimSeqTimeStampTest 6→7；新增两worker/1000轮/4000单字段读回归，immutable clock、合法值域与终止恢复有断言，不声称跨字段snapshot或穷举JMM。seq四类28 tests定向通过。
- **库 Debug/Release 各334 tests/44类，Demo Debug/Release 各6 tests/1类，均0 failures/errors/skipped；合计45类340独立用例（不按变体翻倍）。Demo Debug APK成功，118/118 tasks executed，3m10s**（`.gradle/review-ordered-39-final.log`）。
- 130份源码/测试/构建配置/脚本hash前后一致；README库表与XML相等、Demo6用例另列，两变体manifest各13activity且仅入口导出，Markdown相对链接/whitespace/AGENTS未改再次通过。
- 最终队列39行全部完成，两个SUMMARY索引覆盖全部34专项且不重号。顺序阶段库从72到334（+262），Demo新增6，合计新增268独立用例。
- **仍未验证**：设备/Perfetto/系统远程窗口/真实返回手势、干净SDK复现、Lint（离线缺intellij-core/kotlin-compiler31.9.0）、release压缩/签名发布。未修改OPPO参考树、AGENTS及无关日志/图片，未提交/推送。
