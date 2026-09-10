# vs-oppo-19 — LauncherAnimationRunner 系统边界与工厂契约复核

> **第 24/39 份：已完成本轮建议验收（系统功能明确未移植）。** 本轮不把本地 lifecycle 身份接口包装成远程转场 runner，不用 Any? 字段/空 default 方法假装完成 Binder、merge 或 ready 协议。原风险逐项核对实际调用面。进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## 1. 当前证据与旧文纠错

- 当前只读 `D:\oppo_a6_launcher\sources\com\android\launcher3\LauncherAnimationRunner.java` 实测 **642 行 / 31,550 字符**。本轮直接读取当前文本，不沿用旧“108 行/32,192 字符”的抽取结果；后者无法作为当前行号依据。九个 factory default 位于 243–278，AnimationResult 77–239，外层启动 529–547。
- 本库 [LauncherAnimationRunner](../../lib/src/main/java/com/android/launcher3/LauncherAnimationRunner.kt) 只有嵌套的本地 RemoteAnimationTarget；[RemoteAnimationFactory](../../lib/src/main/java/com/asyncanimator/control/RemoteAnimationFactory.kt) 只有 createAnimation/onAnimationFinished 两个宿主方法。不是原厂接口的二进制、源码或系统行为替换件。
- [AnimationController](../../lib/src/main/java/com/asyncanimator/control/AnimationController.kt) 记录 factory 身份和状态；没有读取 targets 字段，也不自动调用 factory 的两个方法。Demo6、Demo9 都有真实非空 factory 身份，不再是旧“唯一调用 null factory”。
- 不存在 mode/getTaskInfo 的代码会编译失败，不是必然运行时 NPE；平台 RemoteAnimationTarget[] 也不能直接传给嵌套本地类型数组。补 Any? 默认 null 不能解决平台兼容或空值问题。

## 2. 原 L1–L26 映射逐项复核

| 原项 | 当前结论 |
|---|---|
| L1 runner 类型 | 抽象类型壳，不继承 RemoteAnimationRunnerCompat，无注册/调用入口。 |
| L2 target | 本地 taskId/Any? leash；非平台 RemoteAnimationTarget、非 Parcelable，无窗口事务能力。 |
| L3 DEFAULT_FACTORY | OEM fallback 调 AnimationResult.setAnimation(null, null) 走完成；本库无 result/runner，不能加空 factory 等同兜底。 |
| L4 系统属性 | 不读取 support_remote_intercept_keyevent 私有配置；无系统键拦截链。 |
| L5 TAG | 有库级 LogUtils，但本壳无生命周期可记录；不添加空日志。 |
| L6 ActivityInitListener weak ref | 无远程 Activity 初始化监听注册，不移植这个弱引用。 |
| L7 result/factory reference/handler/input/scene/queue fields | 本库不持有；Controller 实例列表不是这些字段的替代。 |
| L8 factory 整体 | 本地两个抽象方法，不是 OEM 一个 abstract + 九个 defaults。 |
| L9 appLaunchAnimStartOrEnd | OEM factory default 空；本地同名 Controller 方法是显式状态入口，二者不可互换。 |
| L10 getAnimation | OEM default null；本库需要 Rect 时直接持有句柄/driver，不增加无消费 getter。 |
| L11 icon ID | OEM default -1；本库没有图标 surface record registry。 |
| L12 handleAnimationMerged | OEM 六参 default false，不是完整 merge 实现；本库没有 TransitionInfo/事务/recents controller 接入。 |
| L13 isSameIcon | OEM default false；本库没有这条图标身份路由。 |
| L14 onAnimationCancelled | OEM default 空；实际外层取消先结束已有动画再通知 factory。只加空 hook 不会替代它。 |
| L15 创建动画 | OEM 接收三组 targets/result 的回调；本库 createAnimation 返回 AnimatorSet，宿主自行调用。 |
| L16 preLoadIcon | OEM default 空；无预加载服务，不新增空函数。 |
| L17 supportInterruption | OEM 无参 default false，outer 转发；本库 factory 没该方法，不是“永远返回 false”。Manager 的同名方法是另一层。 |
| L18 tryFinishOpenRemote | OEM default 同步 runnable.run；outer 转发。不能仅凭该 body 声称已发送 system_server ready。 |
| L19 Scenes | COMMON/APP_TO_OVERVIEW_BY_VIRTUAL_KEY；当前本地页面不切远程收尾 executor，无需新增。 |
| L20 AnimationResult | OEM 初始化一次、完成守卫、AnimatorSet/MultiAnimatorSet 接线、同步/异步/主线程完成链；本库 Multi barrier 只负责本地轨道，不等同远程 result。 |
| L21 构造器 | 当前参考多种 Handler/Reference/factory/scenes 重载；本库未实例化 runner，不复制无功能构造器。 |
| L22 UI 创建与 target 转换 | 本库无 platform→compat 转换和 factory onCreateAnimation 调用；Canvas 演示不是这条链。 |
| L23 入口线程/队列 | OEM front-of-queue + 同 owner 可立即执行，否则异步投递；本库主线程 Controller 入口并不实现 Binder 调度。 |
| L24 首帧补偿 | OEM 条件 setCurrentPlayTime(min(singleFrameMs,totalDuration))；本库不据未知刷新率强加补偿，也不声称无此 race 的设备证明。 |
| L25 prestart/merge/orientation/查询 | 依赖 Launcher/SystemUI 私有服务和对象，明确不移植；不是 Controller 查询逐一等价。 |
| L26 input Binder | IRemoteTransitionInputCallback/键事件→interceptKeyEvent；本库无该 Binder，不处理系统远程输入拦截。 |

## 3. 原遗漏/风险逐项处置

| 原项 | 结论与理由 |
|---|---|
| M1 / B1 target 字段与 NPE | ✔️边界保留，旧确定性 NPE 判断撤销。新增非空本地 targets 回归：Controller 不读 platform 字段，不改描述符；不伪造 25 个 Any? 字段。 |
| M2 / B2 ready 不回调 | ✔️没有本地调用方/runner，属于未支持协议而非已注册回调挂死；不加无宿主的立即执行方法。 |
| M3 / B5 merge | ✔️明确未支持。六参返回 false 壳不能变成 multi-app 合并；Demo9 不是系统 multi-app merge。 |
| M4 图标 hooks | ✔️当前没有预加载/同 surface record 场景，保持不加。 |
| M5 / B3 interruption | ✔️不存在的方法不是 no-op/false；目前通过 Controller/Manager 的显式场景与 feature 路径演示，不承诺 Quickstep 系统打断。 |
| M6 app-launch 事件 | ✔️Controller 记录本地身份；不称为覆写或完整吸收 OEM 总线。 |
| B4 cancel 回调 | ✔️无 Binder runner 回调面，宿主负责停止并登记 end。Demo9 destroy 旧 group + finishLaunch，Demo6 通过公开事件进 WAITING；空 onCancelled 不增加这些行为。 |
| N1 AnimationResult 分层 | ✔️不移植系统完成层；本地 Multi/Rect/Controller 既有实际结束与重入测试仍有效。 |
| N2 弱引用 factory | ✔️本库不保留远程弱引用 runner；不说“GC 在 JVM demo 无法发生”，只是不属于当前持有模型。 |
| N3 input Binder | ✔️不接。Android Demo 不是纯 JVM 程序，但缺少远程转场注册/系统输入 Binder 通道。 |
| N4 系统查询 | ✔️不等价于本地 hasRecentsAnim/isOpeningAnim；保留缺失及系统集成边界。 |

## 4. 原回移建议 R1–R5 与简化 K1–K6

- **R1 补目标字段**：不采纳 Any? 伪兼容方案；当前无字段消费，保留最小描述符并明确类型差异。真正接入时需独立、类型化的 platform adapter 和生命周期所有权。
- **R2 supportInterruption**：不添加无调用方 false default；需要 runner、业务 factory 与中断交接一并设计，一行方法不能打通 Quickstep。
- **R3 tryFinishOpenRemote**：不添加无 remote owner 的 runnable 直调；将来接入必须明确哪个回调表示就绪、线程、至多一次以及取消竞态。
- **R4 onAnimationCancelled**：不添加空 hook 充数；已有 Demo 实际取消/Controller end 接线；新增回归约束 Controller 不暗中取得播放/完成所有权。
- **R5 handleAnimationMerged**：不复制六个 Any 参数和 false body；无窗口事务/远程任务输入，不能标为 merge 功能完成。
- **K1 AnimationResult**：保留系统层未接；本地 barrier 不宣称完成 system_server 的 finish。
- **K2 Binder input**：保持不接，不伪造键拦截能力。
- **K3 weak factory/GC 日志**：不移植不存在的持有链，不沿用“GC 不可能触发”理由。
- **K4 首帧补偿**：没有真实远程首帧时序/设备证据，不随意增加固定一帧 seek。
- **K5 Scenes/构造器**：无 runner 实例调用方，保持不加。
- **K6 四个图标 hooks**：当前缺少 registry/预加载/同图标消费，不追加空方法。

“本份完成”表示当前建议逐项已核查并落实文档/测试边界，不表示 R1–R5 所设想的远程系统功能已经实现。撤销旧“约 32 行即可变为 Quickstep 可消费”的结论。

## 5. 本轮实际改动与验证

- 更新两个公开类型 KDoc 和 USAGE：宿主负责创建/播放/取消/完成，Controller 不调用工厂方法；平台 targets 不兼容，本地 targets 当前仅传递。
- 新增 [RemoteAnimationBoundaryTest](../../lib/src/test/java/com/asyncanimator/control/RemoteAnimationBoundaryTest.kt) 3 条：非空 opaque target 生命周期、null/empty 输入、feature-off 不取得工厂所有权。
- 当前参考行数与方法位置已重新测量，不修改 OPPO 文件、不复用旧 dump 行号。定向 13 条通过；最终 **Debug/Release 各 275 tests、39 类、0 failures/errors/skipped**，Demo Debug 成功，82/82 tasks executed、2m 9s（`.gradle/review-ordered-24-final.log`）。
- 116 份源码/测试/两模块配置 SHA-256 前后一致；`git diff --check` 与相对文档链接通过。无设备/Perfetto 验证，Lint 未完成；既有告警保留，AGENTS.md/OPPO/无关文件未改，无提交/推送。
