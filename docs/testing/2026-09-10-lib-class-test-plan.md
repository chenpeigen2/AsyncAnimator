# lib 逐类测试补充清单（2026-09-10，已完成）

## 范围与完成标准

- 覆盖 lib/src/main/java 下全部手写命名类型，包括 object、enum、interface、嵌套类及 private 实现；自动生成 BuildConfig/R 不作为手写业务类型。
- 每个类检查构造/默认值、正常/边界、异常传播、回调顺序、线程归属、重入和释放中适用的契约。不为接口或数据类虚构生命周期；私有类型通过真实外层消费者验证，不单测伪实现代替生产逻辑。
- 现有引用仅为定位线索，不等于已经详细覆盖。逐项新增或补强具体 case、执行定向及最终 Debug/Release 测试后才标完成。
- 继承平台未改写的实现不穷举 Android 本身；本库的适配/重载/线程边界需验证。无设备仪器/真实VSYNC保证，也不声称测试数量等于覆盖率。
- 仅在用户明确要求时提交；不改 AGENTS 或 OPPO 参考代码。若测试发现真实缺陷，先保留失败证据再单独说明修复，不降低断言迁就实现。

## 起点

库44个测试类，Debug/Release现存XML各334 tests且零失败/错误/跳过；这是既有结果，不是本轮新增执行。Demo另6 tests，不混入lib计数。

## 全量类型队列

共 **42 个源码文件、74 个命名类型**。初次扫描漏计3个private inner类（OnAnimationEndDispatcher、RectSpringDriver.Run/Axis），已补入，未缩小范围。匿名监听器/无名companion通过外层实际路径覆盖。

| 源码（相对lib/src/main/java） | 所有命名类型 | 本轮状态 |
|---|---|---|
| `com/android/launcher3/LauncherAnimationRunner.kt` | `LauncherAnimationRunner`, `RemoteAnimationTarget` | 补充完成，全量验证通过 |
| `com/asyncanimator/anim/ActualEndAnimListener.kt` | `ActualEndAnimListener` | 补充完成，全量验证通过 |
| `com/asyncanimator/anim/AsyncAnimCallbacks.kt` | `AsyncAnimCallbacks` | 补充完成，全量验证通过 |
| `com/asyncanimator/anim/AsyncSpringAnim.kt` | `AsyncSpringAnim` | 补充完成，全量验证通过 |
| `com/asyncanimator/anim/AsyncValueAnimator.kt` | `AsyncValueAnimator` | 补充完成，全量验证通过 |
| `com/asyncanimator/anim/CustomRectFSpringAnim.kt` | `CustomRectFSpringAnim`, `Event`, `Listener`, `AnimType`, `Driver`, `DisposableDriver`, `ReversibleDriver`, `AnimatorDriver` | 补充完成，全量验证通过 |
| `com/asyncanimator/anim/MultiAnimatorSet.kt` | `MultiAnimatorSet`, `Track` | 补充完成，全量验证通过 |
| `com/asyncanimator/anim/OplusValueAnimator.kt` | `OplusValueAnimator`, `AnimParam`, `TimeControllerObjectAnimator` | 补充完成，全量验证通过 |
| `com/asyncanimator/anim/RecordInputInterpolator.kt` | `RecordInputInterpolator` | 补充完成，全量验证通过 |
| `com/asyncanimator/anim/RectAnimationLifecycle.kt` | `RectAnimationLifecycle`, `Stop`, `Run` | 补充完成，全量验证通过 |
| `com/asyncanimator/anim/RectSpringConfig.kt` | `RectSpringConfig`, `Tracking`, `Spring`, `RectSpringValues`, `RectSpringFrame` | 补充完成，全量验证通过 |
| `com/asyncanimator/anim/RectSpringDriver.kt` | `RectSpringDriver`, `Stop`, `Run`, `Axis` | 补充完成，全量验证通过 |
| `com/asyncanimator/anim/SpringProjection.kt` | `SpringProjection` | 补充完成，全量验证通过 |
| `com/asyncanimator/control/AnimationController.kt` | `AnimationController` | 补充完成，全量验证通过 |
| `com/asyncanimator/control/AnimationScene.kt` | `AppExitScene`, `GestureScene` | 补充完成，全量验证通过 |
| `com/asyncanimator/control/AnimationState.kt` | `AnimationState` | 补充完成，全量验证通过 |
| `com/asyncanimator/control/DefaultAnimationController.kt` | `DefaultAnimationController` | 补充完成，全量验证通过 |
| `com/asyncanimator/control/OnAnimStateChangeListener.kt` | `OnAnimStateChangeListener` | 补充完成，全量验证通过 |
| `com/asyncanimator/control/RemoteAnimationFactory.kt` | `RemoteAnimationFactory` | 补充完成，全量验证通过 |
| `com/asyncanimator/control/TaskStateChangeTimeOutListener.kt` | `TaskStateChangeTimeOutListener`, `Type` | 补充完成，全量验证通过 |
| `com/asyncanimator/core/AnimationHandler.kt` | `AnimationHandler`, `AnimationFrameCallback`, `TickSchedulerHolder` | 补充完成，全量验证通过 |
| `com/asyncanimator/core/ChoreographerTickScheduler.kt` | `ChoreographerTickScheduler` | 补充完成，全量验证通过 |
| `com/asyncanimator/core/LogUtils.kt` | `LogUtils` | 补充完成，全量验证通过 |
| `com/asyncanimator/core/TickScheduler.kt` | `TickScheduler`, `FrameCallback` | 补充完成，全量验证通过 |
| `com/asyncanimator/core/Trace.kt` | `Trace`, `Section` | 补充完成，全量验证通过 |
| `com/asyncanimator/manager/AnimationFeatureHelper.kt` | `AnimationFeatureHelper`, `Registration`, `Snapshot`, `SyncedVar` | 补充完成，全量验证通过 |
| `com/asyncanimator/manager/OplusAnimManager.kt` | `OplusAnimManager` | 补充完成，全量验证通过 |
| `com/asyncanimator/playback/AnimationSuccessListener.kt` | `AnimationSuccessListener` | 补充完成，全量验证通过 |
| `com/asyncanimator/playback/AnimatorListeners.kt` | `AnimatorListeners` | 补充完成，全量验证通过 |
| `com/asyncanimator/playback/AnimatorPlaybackController.kt` | `AnimatorPlaybackController`, `Holder`, `OnAnimationEndDispatcher` | 补充完成，全量验证通过 |
| `com/asyncanimator/playback/Interpolators.kt` | `Interpolators` | 补充完成，全量验证通过 |
| `com/asyncanimator/playback/NullableAnimatorListener.kt` | `NullableAnimatorListener` | 补充完成，全量验证通过 |
| `com/asyncanimator/playback/NullableAnimatorListenerAdapter.kt` | `NullableAnimatorListenerAdapter` | 补充完成，全量验证通过 |
| `com/asyncanimator/playback/PendingAnimation.kt` | `PendingAnimation`, `ObjectAnimator` | 补充完成，全量验证通过 |
| `com/asyncanimator/playback/PropertySetter.kt` | `PropertySetter` | 补充完成，全量验证通过 |
| `com/asyncanimator/seq/AnimationSeqHelper.kt` | `AnimationSeqHelper` | 补充完成，全量验证通过 |
| `com/asyncanimator/seq/AnimSeqTimeStamp.kt` | `AnimSeqTimeStamp` | 补充完成，全量验证通过 |
| `com/asyncanimator/seq/DefaultAnimationSeqHelper.kt` | `DefaultAnimationSeqHelper` | 补充完成，全量验证通过 |
| `com/asyncanimator/thread/AnimationControlThread.kt` | `AnimationControlThread` | 补充完成，全量验证通过 |
| `com/asyncanimator/thread/AsyncAnimWrapper.kt` | `AsyncAnimWrapper` | 补充完成，全量验证通过 |
| `com/asyncanimator/thread/Executors.kt` | `Executors` | 补充完成，全量验证通过 |
| `com/asyncanimator/thread/LooperExecutor.kt` | `LooperExecutor` | 补充完成，全量验证通过 |

## 已补充类型与具体证据

每个命名类型单独列出；同文件的类型可通过同一实际消费者验证，不要求机械拆成同名Test类。已有用例只有在源码/断言实际匹配时才复用。

| 类型（文件内限定） | 测试来源 | 已核对的适用契约 |
|---|---|---|
| `LauncherAnimationRunner / LauncherAnimationRunner` | [RemoteAnimationBoundaryTest](../../lib/src/test/java/com/asyncanimator/control/RemoteAnimationBoundaryTest.kt) | 类型容器无执行入口；描述符默认/变更/null/非空targets经Controller消费，reset/destroy不调用宿主factory |
| `LauncherAnimationRunner / RemoteAnimationTarget` | [RemoteAnimationBoundaryTest](../../lib/src/test/java/com/asyncanimator/control/RemoteAnimationBoundaryTest.kt) | 类型容器无执行入口；描述符默认/变更/null/非空targets经Controller消费，reset/destroy不调用宿主factory |
| `ActualEndAnimListener / ActualEndAnimListener` | [ListenerBaseContractTest](../../lib/src/test/java/com/asyncanimator/playback/ListenerBaseContractTest.kt) | 默认物理结束hook不合成逻辑事件；真实AsyncAnimCallbacks派发animator/id与cancel/success分轨 |
| `RecordInputInterpolator / RecordInputInterpolator` | [RecordInputInterpolatorTest](../../lib/src/test/java/com/asyncanimator/anim/RecordInputInterpolatorTest.kt) | 默认不求值、非线性input记录、越界/非有限透传、抛错前记录、实例隔离 |
| `RectSpringConfig / RectSpringConfig` | [RectSpringConfigTest](../../lib/src/test/java/com/asyncanimator/anim/RectSpringConfigTest.kt) | 六轴参数顺序/非法值、零/极端倍率、全部AnimType、fold/tablet阈值；Tracking枚举、Spring校验、Values数组隔离、Frame RectF防御副本 |
| `RectSpringConfig / Tracking` | [RectSpringConfigTest](../../lib/src/test/java/com/asyncanimator/anim/RectSpringConfigTest.kt) | 六轴参数顺序/非法值、零/极端倍率、全部AnimType、fold/tablet阈值；Tracking枚举、Spring校验、Values数组隔离、Frame RectF防御副本 |
| `RectSpringConfig / Spring` | [RectSpringConfigTest](../../lib/src/test/java/com/asyncanimator/anim/RectSpringConfigTest.kt) | 六轴参数顺序/非法值、零/极端倍率、全部AnimType、fold/tablet阈值；Tracking枚举、Spring校验、Values数组隔离、Frame RectF防御副本 |
| `RectSpringConfig / RectSpringValues` | [RectSpringConfigTest](../../lib/src/test/java/com/asyncanimator/anim/RectSpringConfigTest.kt) | 六轴参数顺序/非法值、零/极端倍率、全部AnimType、fold/tablet阈值；Tracking枚举、Spring校验、Values数组隔离、Frame RectF防御副本 |
| `RectSpringConfig / RectSpringFrame` | [RectSpringConfigTest](../../lib/src/test/java/com/asyncanimator/anim/RectSpringConfigTest.kt) | 六轴参数顺序/非法值、零/极端倍率、全部AnimType、fold/tablet阈值；Tracking枚举、Spring校验、Values数组隔离、Frame RectF防御副本 |
| `SpringProjection / SpringProjection` | [SpringProjectionTest](../../lib/src/test/java/com/asyncanimator/anim/SpringProjectionTest.kt) | 三种阻尼+无阻尼分支、零时间/平衡、独立数值积分、组合一致性、平移/force不变、长时间收敛 |
| `AnimationScene / AppExitScene` | [AnimationSceneTest](../../lib/src/test/java/com/asyncanimator/control/AnimationSceneTest.kt) | AppExitScene默认/复制与真实门控；GestureScene全默认/复制与包名选择；既有导航/平板/分屏矩阵保留 |
| `AnimationScene / GestureScene` | [AnimationSceneTest](../../lib/src/test/java/com/asyncanimator/control/AnimationSceneTest.kt) | AppExitScene默认/复制与真实门控；GestureScene全默认/复制与包名选择；既有导航/平板/分屏矩阵保留 |
| `AnimationState / AnimationState` | [DefaultAnimationControllerTest](../../lib/src/test/java/com/asyncanimator/control/DefaultAnimationControllerTest.kt) | 全部12×12状态对通过真实通知保持值/顺序/task；ControllerStateMatrixTest已有全枚举名称/次序/taskbar标志 |
| `DefaultAnimationController / DefaultAnimationController` | [DefaultAnimationControllerTest](../../lib/src/test/java/com/asyncanimator/control/DefaultAnimationControllerTest.kt) | 全部默认query与写入口、factory/Rect不接管、false不action、重复/null监听、快照重入、异常与主线程守卫 |
| `OnAnimStateChangeListener / OnAnimStateChangeListener` | [DefaultAnimationControllerTest](../../lib/src/test/java/com/asyncanimator/control/DefaultAnimationControllerTest.kt) | 全部状态对和task透传，重复注册单项移除、null、快照增删/重入、异常；保留Java SAM消费者回归 |
| `RemoteAnimationFactory / RemoteAnimationFactory` | [RemoteAnimationBoundaryTest](../../lib/src/test/java/com/asyncanimator/control/RemoteAnimationBoundaryTest.kt) | 启动/结束/null/empty/target变更/reset/destroy不自动create/finish，接口实现由宿主所有 |
| `AnimationHandler / AnimationHandler` | [AnimationHandlerTest](../../lib/src/test/java/com/asyncanimator/core/AnimationHandlerTest.kt) | FrameCallback真pulse/忽略返回、100轮增删、同源多handler隔离；TickSchedulerHolder首次get缓存但不启动；换源代次原回归保留 |
| `AnimationHandler / AnimationFrameCallback` | [AnimationHandlerTest](../../lib/src/test/java/com/asyncanimator/core/AnimationHandlerTest.kt) | FrameCallback真pulse/忽略返回、100轮增删、同源多handler隔离；TickSchedulerHolder首次get缓存但不启动；换源代次原回归保留 |
| `AnimationHandler / TickSchedulerHolder` | [AnimationHandlerTest](../../lib/src/test/java/com/asyncanimator/core/AnimationHandlerTest.kt) | FrameCallback真pulse/忽略返回、100轮增删、同源多handler隔离；TickSchedulerHolder首次get缓存但不启动；换源代次原回归保留 |
| `ChoreographerTickScheduler / ChoreographerTickScheduler` | [ChoreographerTickSchedulerTest](../../lib/src/test/java/com/asyncanimator/core/ChoreographerTickSchedulerTest.kt) | null/未知remove不启动，帧时钟先发布，stop当前快照完成而后帧停止；既有换源/异常/重入/owner回归保留 |
| `LogUtils / LogUtils` | [LogUtilsTest](../../lib/src/test/java/com/asyncanimator/core/LogUtilsTest.kt) | 完整等级/非法值不改策略、线程名/UTF8格式、OFF抑制、emit旁路、worker观察已发布策略 |
| `TickScheduler / TickScheduler` | [ChoreographerTickSchedulerTest](../../lib/src/test/java/com/asyncanimator/core/ChoreographerTickSchedulerTest.kt) | 通过真实实现验接口/FrameCallback持久订阅、纳秒参数、count可见、null/stop/快照；不用手写fake证明接口实际语义 |
| `TickScheduler / FrameCallback` | [ChoreographerTickSchedulerTest](../../lib/src/test/java/com/asyncanimator/core/ChoreographerTickSchedulerTest.kt) | 通过真实实现验接口/FrameCallback持久订阅、纳秒参数、count可见、null/stop/快照；不用手写fake证明接口实际语义 |
| `Trace / Trace` | [TraceLogTest](../../lib/src/test/java/com/asyncanimator/core/TraceLogTest.kt) | Section捕获begin策略、关→开不造end、线程独立clear、nullable返回及Error unwind；原异常/事件身份回归保留 |
| `Trace / Section` | [TraceLogTest](../../lib/src/test/java/com/asyncanimator/core/TraceLogTest.kt) | Section捕获begin策略、关→开不造end、线程独立clear、nullable返回及Error unwind；原异常/事件身份回归保留 |
| `AnimationSuccessListener / AnimationSuccessListener` | [ListenerBaseContractTest](../../lib/src/test/java/com/asyncanimator/playback/ListenerBaseContractTest.kt) | 取消sticky、物理结束不等于success、成功真实animator及业务异常传播 |
| `AnimatorListeners / AnimatorListeners` | [AnimatorListenersTest](../../lib/src/test/java/com/asyncanimator/playback/AnimatorListenersTest.kt) | 三种工厂、严格半程边界/非ValueAnimator、一次消费/重入/抛错、nullable、普通end非once与success取消语义 |
| `AnimatorPlaybackController / AnimatorPlaybackController` | [PlaybackProgressContractTest](../../lib/src/test/java/com/asyncanimator/playback/PlaybackProgressContractTest.kt) | Holder相对时长/mapper/reset、raw与clamp/holders防御拷贝、cancel→end恢复、start/reverse/pause、float update；OnAnimationEndDispatcher沿用PlaybackCompletionTest的真实消费者重入/异常/once回归 |
| `AnimatorPlaybackController / Holder` | [PlaybackProgressContractTest](../../lib/src/test/java/com/asyncanimator/playback/PlaybackProgressContractTest.kt) | Holder相对时长/mapper/reset、raw与clamp/holders防御拷贝、cancel→end恢复、start/reverse/pause、float update；OnAnimationEndDispatcher沿用PlaybackCompletionTest的真实消费者重入/异常/once回归 |
| `AnimatorPlaybackController / OnAnimationEndDispatcher` | [PlaybackProgressContractTest](../../lib/src/test/java/com/asyncanimator/playback/PlaybackProgressContractTest.kt) | Holder相对时长/mapper/reset、raw与clamp/holders防御拷贝、cancel→end恢复、start/reverse/pause、float update；OnAnimationEndDispatcher沿用PlaybackCompletionTest的真实消费者重入/异常/once回归 |
| `Interpolators / Interpolators` | [InterpolatorsTest](../../lib/src/test/java/com/asyncanimator/playback/InterpolatorsTest.kt) | clamp不求值窗口外/零宽step、NaN界拒绝、double-reverse、常数map、三曲线单调/端点；原velocity严格边界保留 |
| `NullableAnimatorListener / NullableAnimatorListener` | [ListenerBaseContractTest](../../lib/src/test/java/com/asyncanimator/playback/ListenerBaseContractTest.kt) | 不实现默认hook可经真实容器派发，其他接口consumer验证快照和身份，不制造null Animator承诺 |
| `NullableAnimatorListenerAdapter / NullableAnimatorListenerAdapter` | [ListenerBaseContractTest](../../lib/src/test/java/com/asyncanimator/playback/ListenerBaseContractTest.kt) | 默认id=-1、cancel标志sticky/end不设置、真实派发传id；与ActualEnd/Success继承链结合 |
| `PendingAnimation / PendingAnimation` | [PendingAnimationTest](../../lib/src/test/java/com/asyncanimator/playback/PendingAnimationTest.kt) | 负duration归零/末值、根事件finished位、addWithoutDuration和add差异；ObjectAnimator链式配置/稳定backing/start/end；原嵌套时长/曲线/属性捕获保留 |
| `PendingAnimation / ObjectAnimator` | [PendingAnimationTest](../../lib/src/test/java/com/asyncanimator/playback/PendingAnimationTest.kt) | 负duration归零/末值、根事件finished位、addWithoutDuration和add差异；ObjectAnimator链式配置/稳定backing/start/end；原嵌套时长/曲线/属性捕获保留 |
| `PropertySetter / PropertySetter` | [PropertySetterTest](../../lib/src/test/java/com/asyncanimator/playback/PropertySetterTest.kt) | NO_ANIM与默认接口即时写、不读/不插值/null property、异常传播/非有限不clamp |
| `DefaultAnimationSeqHelper / DefaultAnimationSeqHelper` | [DefaultAnimationSeqHelperTest](../../lib/src/test/java/com/asyncanimator/seq/DefaultAnimationSeqHelperTest.kt) | 全部fallback查询/序号方法、Bundle保留、同步action重入/无延后、抛错后恢复/null |
| `AnimationControlThread / AnimationControlThread` | [AsyncAnimWrapperTest](../../lib/src/test/java/com/asyncanimator/thread/AsyncAnimWrapperTest.kt) | 实际线程bootstrap后帧源可用且与executor owner一致/嵌套inline；既有独立sandbox冷启动/优先级/早发布验证保留 |
| `AsyncAnimWrapper / AsyncAnimWrapper` | [AsyncAnimWrapperTest](../../lib/src/test/java/com/asyncanimator/thread/AsyncAnimWrapperTest.kt) | null no-op、main inline/worker deferred、anim owner/嵌套inline、业务异常不吞 |
| `Executors / Executors` | [AsyncAnimWrapperTest](../../lib/src/test/java/com/asyncanimator/thread/AsyncAnimWrapperTest.kt) | MAIN/ANIM真实调用路径、线程身份/初始化/嵌套；ExecutorInitializationTest既有MAIN不拉起ANIM及8worker单例回归保留 |
| `LooperExecutor / LooperExecutor` | [LooperExecutorTest](../../lib/src/test/java/com/asyncanimator/thread/LooperExecutorTest.kt) | owner post仍排队而execute inline、null handler三入口fallback、异常后可继续；原异步message/priority/Java never-quit保留 |

| `AsyncAnimCallbacks / AsyncAnimCallbacks` | [AsyncAnimCallbacksTest](../../lib/src/test/java/com/asyncanimator/anim/AsyncAnimCallbacksTest.kt)、[AsyncAnimatorContractTest](../../lib/src/test/java/com/asyncanimator/anim/AsyncAnimatorContractTest.kt)、[TraceLogTest](../../lib/src/test/java/com/asyncanimator/core/TraceLogTest.kt) | 四类事件捕获ID/类型而在delivery取监听、clear与dispose代次区别、重入快照/释放/新轮、锁外回调、异常后恢复；并发2000轮/worker-main/physical-only原回归保留 |
| `AsyncValueAnimator / AsyncValueAnimator` | [AsyncValueAnimatorLifecycleTest](../../lib/src/test/java/com/asyncanimator/anim/AsyncValueAnimatorLifecycleTest.kt)、[AsyncAnimatorContractTest](../../lib/src/test/java/com/asyncanimator/anim/AsyncAnimatorContractTest.kt) | 工厂数值/Java入口、首次生命周期绑定executor、排队及重入dispose、native与业务监听隔离、one-shot业务不重置、dispose后抛错仍清理、真实HandlerThread归属 |
| `AsyncSpringAnim / AsyncSpringAnim` | [AsyncSpringAnimTest](../../lib/src/test/java/com/asyncanimator/anim/AsyncSpringAnimTest.kt) | 真实AndroidX start/cancel/skip/retarget/velocity、零阻尼/首帧warmup/空闲命令/重复start、结束payload/异常；公开scheduler显式绑定真实launcher.anim，异步命令全入口及main结束通知 |
| `CustomRectFSpringAnim / CustomRectFSpringAnim` | [RectAnimationLifecycleTest](../../lib/src/test/java/com/asyncanimator/anim/RectAnimationLifecycleTest.kt)、[RectSpringDriverTest](../../lib/src/test/java/com/asyncanimator/anim/RectSpringDriverTest.kt) | 全部委托入口：ID/owner冻结、start/停止/逻辑结束/反向/释放，bare拒绝播放；真实Animator和六轴驱动均验证 |
| `CustomRectFSpringAnim / Event` | [RectAnimationLifecycleTest](../../lib/src/test/java/com/asyncanimator/anim/RectAnimationLifecycleTest.kt) | 通过真实start/cancel/actualEnd采集：ID/runId/cancelled不可变、重复start不增serial、copy不改旧事件 |
| `CustomRectFSpringAnim / Listener` | [RectAnimationLifecycleTest](../../lib/src/test/java/com/asyncanimator/anim/RectAnimationLifecycleTest.kt) | 默认hook无副作用、重复注册去重、当前观众成员资格、移除/重入/抛错与逻辑-物理分轨 |
| `CustomRectFSpringAnim / AnimType` | [MultiAnimatorSetTest](../../lib/src/test/java/com/asyncanimator/anim/MultiAnimatorSetTest.kt)、[RectSpringConfigTest](../../lib/src/test/java/com/asyncanimator/anim/RectSpringConfigTest.kt) | 七项精确名称/顺序、全分类、六轴参数的opening/drag分支、reverse实际更新类型 |
| `CustomRectFSpringAnim / Driver` | [RectAnimationLifecycleTest](../../lib/src/test/java/com/asyncanimator/anim/RectAnimationLifecycleTest.kt)、[RectSpringDriverTest](../../lib/src/test/java/com/asyncanimator/anim/RectSpringDriverTest.kt) | 真实handle调用start/stop/clear与physical-end屏障、默认不允许后台；具体Animator/RectSpring适配器验证非伪实现 |
| `CustomRectFSpringAnim / DisposableDriver` | [RectAnimationLifecycleTest](../../lib/src/test/java/com/asyncanimator/anim/RectAnimationLifecycleTest.kt)、[RectSpringDriverTest](../../lib/src/test/java/com/asyncanimator/anim/RectSpringDriverTest.kt) | dispose能力分派只走最终teardown而非重复cancel/clear；真实六轴释放native duration listener和帧订阅 |
| `CustomRectFSpringAnim / ReversibleDriver` | [RectAnimationLifecycleTest](../../lib/src/test/java/com/asyncanimator/anim/RectAnimationLifecycleTest.kt)、[RectSpringDriverTest](../../lib/src/test/java/com/asyncanimator/anim/RectSpringDriverTest.kt) | 目标防御副本、owner retarget/main通知、停止后无反向；真实六轴动量与尺寸坐标转换 |
| `CustomRectFSpringAnim / AnimatorDriver` | [RectAnimationLifecycleTest](../../lib/src/test/java/com/asyncanimator/anim/RectAnimationLifecycleTest.kt)、[MultiAnimatorSetTest](../../lib/src/test/java/com/asyncanimator/anim/MultiAnimatorSetTest.kt) | 真实ValueAnimator驱动值/完成、重复run不累加监听、仅移除自有监听、dispose保留宿主listener；拒绝不支持的后台/反向 |
| `RectAnimationLifecycle / RectAnimationLifecycle` | [RectAnimationLifecycleTest](../../lib/src/test/java/com/asyncanimator/anim/RectAnimationLifecycleTest.kt)、[RectSpringDriverTest](../../lib/src/test/java/com/asyncanimator/anim/RectSpringDriverTest.kt) | 自然/逻辑/物理结束次序，首个stop生效，排队start/cancel竞态，clear只去owner hook；dispose/重入/后台归属及实际Driver集成 |
| `RectAnimationLifecycle / Stop` | [RectAnimationLifecycleTest](../../lib/src/test/java/com/asyncanimator/anim/RectAnimationLifecycleTest.kt) | CANCEL→END及END→CANCEL均first-wins，不重复native stop；logical-only后cancel不补发旧逻辑事件 |
| `RectAnimationLifecycle / Run` | [RectAnimationLifecycleTest](../../lib/src/test/java/com/asyncanimator/anim/RectAnimationLifecycleTest.kt) | serial/ID固定、stopDispatched/physicalReported once-only、旧回调不影响新轮、末尾观众快照及owner清理先于允许复用 |
| `RectSpringDriver / RectSpringDriver` | [RectSpringDriverTest](../../lib/src/test/java/com/asyncanimator/anim/RectSpringDriverTest.kt) | 真实六轴/防御几何/参数非法值、更新/反向/预测/续行、scheduler失败回滚可重试、首停胜出、异常/重入释放与后台owner |
| `RectSpringDriver / Stop` | [RectSpringDriverTest](../../lib/src/test/java/com/asyncanimator/anim/RectSpringDriverTest.kt) | 两种命令首停胜出；cancel不发布终帧，end下一tick发布精确目标；preview不触发update或完成 |
| `RectSpringDriver / Run` | [RectSpringDriverTest](../../lib/src/test/java/com/asyncanimator/anim/RectSpringDriverTest.kt) | 共享tick且coherent frame、显式owner、延迟/queued/订阅清理、旧tick代次隔离、失败回滚、完成重启 |
| `RectSpringDriver / Axis` | [RectSpringDriverTest](../../lib/src/test/java/com/asyncanimator/anim/RectSpringDriverTest.kt) | 六分量独立参数与初速度校验、bounds/等待alpha/首帧/平衡、zero duration scale、retarget保留速度/坐标转换与原生阈值，预测对照真实AndroidX更新 |
| `MultiAnimatorSet / MultiAnimatorSet` | [MultiAnimatorSetTest](../../lib/src/test/java/com/asyncanimator/anim/MultiAnimatorSetTest.kt) | 全四轨/全部有效mask及非法mask、live-add与忽略路径、监听去重/快照/异常、start失败及重入destroy、cancel once-only与physical barrier |
| `MultiAnimatorSet / Track` | [MultiAnimatorSetTest](../../lib/src/test/java/com/asyncanimator/anim/MultiAnimatorSetTest.kt) | MAIN/ASYNC/SPRINGS/RECT预占屏障、零时长同步end不提前合并、async返回main、排除spring为detach、旧run完成失效 |
| `OplusValueAnimator / OplusValueAnimator` | [OplusValueAnimatorTest](../../lib/src/test/java/com/asyncanimator/anim/OplusValueAnimatorTest.kt) | 工厂/default、typed keyframes空参数/类型切换、duration异常保持状态、null插值、start/pause/cancel/end和查询委派、深拷贝holders、续行零/非有限边界 |
| `OplusValueAnimator / AnimParam` | [OplusValueAnimatorTest](../../lib/src/test/java/com/asyncanimator/anim/OplusValueAnimatorTest.kt) | 全部默认字段、name读取、scalar副本独立而显式插值器/回调引用共享，真实fraction/duration/interpolator写入 |
| `OplusValueAnimator / TimeControllerObjectAnimator` | [OplusValueAnimatorTest](../../lib/src/test/java/com/asyncanimator/anim/OplusValueAnimatorTest.kt) | 实际player+FloatProperty驱动、fluent返回identity、目标/属性重绑和null、异常传播后解除绑定恢复、native监听源为player |
| `AnimationController / AnimationController` | [ControllerStateMatrixTest](../../lib/src/test/java/com/asyncanimator/control/ControllerStateMatrixTest.kt)、[AnimationControllerRegressionTest](../../lib/src/test/java/com/asyncanimator/control/AnimationControllerRegressionTest.kt)、[ControllerCompletionTest](../../lib/src/test/java/com/asyncanimator/control/ControllerCompletionTest.kt)、[LaunchDecisionReentrancyTest](../../lib/src/test/java/com/asyncanimator/control/LaunchDecisionReentrancyTest.kt)、[ControllerThreadContractTest](../../lib/src/test/java/com/asyncanimator/control/ControllerThreadContractTest.kt) | 12状态转移/全部分类、双集合finish与duplicate factory、非法scene不改state、真实sw599/600/800边界、predicate抛错不遗留action、reset与destroy；其余场景/时间窗/finish gate/事件/触摸/所有权专组全部全量复跑 |
| `TaskStateChangeTimeOutListener / TaskStateChangeTimeOutListener` | [TaskStateChangeTimeOutListenerTest](../../lib/src/test/java/com/asyncanimator/control/TaskStateChangeTimeOutListenerTest.kt)、[TimeoutOwnershipTest](../../lib/src/test/java/com/asyncanimator/control/TimeoutOwnershipTest.kt)、[ControllerThreadContractTest](../../lib/src/test/java/com/asyncanimator/control/ControllerThreadContractTest.kt) | 精确deadline/非正延迟仍排队、全type匹配而忽略event duration、action/dispose异常聚合/同一异常不自抑制、重入/并发once-only、构造access拒绝不排timer |
| `TaskStateChangeTimeOutListener / Type` | [TaskStateChangeTimeOutListenerTest](../../lib/src/test/java/com/asyncanimator/control/TaskStateChangeTimeOutListenerTest.kt)、[TaskStateEventTest](../../lib/src/test/java/com/asyncanimator/control/TaskStateEventTest.kt) | 三项名称/次序及全部不匹配组合、Controller三slot公开事件桥与超时互斥 |
| `AnimationFeatureHelper / AnimationFeatureHelper` | [FeatureNotificationTest](../../lib/src/test/java/com/asyncanimator/manager/FeatureNotificationTest.kt)、[FeatureSnapshotTest](../../lib/src/test/java/com/asyncanimator/manager/FeatureSnapshotTest.kt) | 7标量/2列表、null保留/empty清空、兼容更新、复制失败原子性、异步通知非历史payload、Adaptive半径/阈值策略、异常与锁外回调 |
| `AnimationFeatureHelper / Registration` | [FeatureNotificationTest](../../lib/src/test/java/com/asyncanimator/manager/FeatureNotificationTest.kt) | 同callback多handle独立、close幂等/remove按identity全部清除、已排队事件失效、迟注册无重播、destroy保留配置与新owner |
| `AnimationFeatureHelper / Snapshot` | [FeatureNotificationTest](../../lib/src/test/java/com/asyncanimator/manager/FeatureNotificationTest.kt)、[FeatureSnapshotTest](../../lib/src/test/java/com/asyncanimator/manager/FeatureSnapshotTest.kt) | 一致快照、历史列表不变/不可修改、copy scalar独立、所有字段并发不混批、radius由adaptive推导 |
| `AnimationFeatureHelper / SyncedVar` | [FeatureNotificationTest](../../lib/src/test/java/com/asyncanimator/manager/FeatureNotificationTest.kt)、[FeatureSnapshotTest](../../lib/src/test/java/com/asyncanimator/manager/FeatureSnapshotTest.kt) | 通过真实标量setter/getter与全量publish验证volatile发布、同锁串行化/并发快照、6参数更新不恢复旧onePx数据 |
| `OplusAnimManager / OplusAnimManager` | [ManagerLifecycleTest](../../lib/src/test/java/com/asyncanimator/manager/ManagerLifecycleTest.kt)、[ManagerBootstrapTest](../../lib/src/test/java/com/asyncanimator/manager/ManagerBootstrapTest.kt) | 冷启动8路同owner、启用幂等/重建、禁用新fallback不共享监听且capability仍true、旧timer/action释放、主线程守卫、feature订阅/配置不被toggle销毁 |
| `AnimationSeqHelper / AnimationSeqHelper` | [AnimationSeqRegressionTest](../../lib/src/test/java/com/asyncanimator/seq/AnimationSeqRegressionTest.kt)、[AnimationSeqFeatureGateTest](../../lib/src/test/java/com/asyncanimator/seq/AnimationSeqFeatureGateTest.kt)、[AnimationSeqHelperTest](../../lib/src/test/java/com/asyncanimator/seq/AnimationSeqHelperTest.kt) | 300/500ms边界/feature gates、共享seqId/controller equals/null、两helper队列/计数独立、null替换、延后/即时异常消费旧任务且保留重入后继、reset仅解gesture |
| `AnimSeqTimeStamp / AnimSeqTimeStamp` | [AnimSeqTimeStampTest](../../lib/src/test/java/com/asyncanimator/seq/AnimSeqTimeStampTest.kt)、[AnimationSeqRegressionTest](../../lib/src/test/java/com/asyncanimator/seq/AnimationSeqRegressionTest.kt) | 全部4字段零/未记录/reset隔离、每次update/read仅取一次clock、重复写仅替换自己、Long高值精度、clock失败保留值、并发发布与诊断门控 |

## 批次记录

### 01：纯计算、配置、诊断、监听（定向通过）

- 新增7个测试类/44 cases；RecordInputInterpolator、SpringProjection、RectSpringConfig/Values/Frame、LogUtils、PropertySetter、AnimatorListeners、Nullable/ActualEnd/Success基类。
- `.gradle/lib-class-tests-01-targeted.log`：Debug定向44 tests，BUILD SUCCESSFUL，51s。测试以当前契约验收，没有为了凑red伪报新产品缺陷。

### 02：调度内核与线程转发（定向通过）

- 新增AsyncAnimWrapperTest 4项；AnimationHandler +4、Choreographer +3、Trace +3、LooperExecutor +3，共17新case。
- `.gradle/lib-class-tests-02-targeted.log`：core/thread全组Debug成功，1m15s；测试活跃线程由fixture负责join/停止自己的订阅，不quit进程singleton。

### 03：Default、场景和远程接口边界（定向通过）

- 新增DefaultAnimationController 8 + DefaultAnimationSeqHelper 4，RemoteAnimationBoundary +2、AnimationScene +2，共16新case。
- `.gradle/lib-class-tests-03-targeted.log`：四类定向Debug成功，29s。Default不是全无副作用：监听仍生效，Seq同步action，而Controller false不action；均有断言。

### 04：播放进度与属性组装（定向通过）

- 新增PlaybackProgressContractTest 7，Interpolators +4、PendingAnimation +4，共15新case。
- `.gradle/lib-class-tests-04-targeted.log`：playback全组Debug成功，36s；覆盖Holder和mapper、pause重置、raw与clamp、负duration、wrapper真实生命周期，保留原完成功能回归。

### 05：异步回调与动画封装（定向通过）

- AsyncAnimCallbacks +6、AsyncValueAnimatorLifecycle +4、AsyncSpringAnim +5，共15新case；含真实动画线程+公开AndroidX scheduler，不把生命周期转发当后台View保证。
- `.gradle/lib-class-tests-05-targeted.log`：四类定向Debug成功，42s。

### 06：序列、时间戳与配置工厂（定向通过）

- SeqRegression +5、TimeStamp +3、FeatureNotification +4、ManagerLifecycle +2，共14新case。
- `.gradle/lib-class-tests-06-targeted.log`：seq/manager全组Debug成功，49s；队列异常/重入、独立owner、监听registration、配置快照及fallback均通过真实消费者验证。

### 07：组合动画与值续行（定向通过，发现并修复1项缺陷）

- MultiAnimatorSet +6、OplusValueAnimator +6，共12新case。
- 首轮 `.gradle/lib-class-tests-07-first.log`：47 tests中1失败，`testContinuationAcceptsZeroAndRejectsEveryNonFiniteFraction` 在NaN处失败；原XML保留于 `.gradle/lib-class-tests-07-nan-red.xml`（本地忽略）。
- 原 `f < 0 || f >= 1` 无法排除NaN，实际创建了无效续行动画。生产代码仅新增 `!f.isFinite()` 门控；保留零进度与原[0,1)契约，原失败断言未放宽。
- `.gradle/lib-class-tests-07-targeted.log`：同两类47 tests全部通过，43s。

### 08：Rect handle、生命周期与六轴引擎（定向通过）

- RectAnimationLifecycle +7、RectSpringDriver +8，共15新case。
- `.gradle/lib-class-tests-08-targeted.log`：两类57 tests全部通过，49s；覆盖Event/Listener/Driver能力、私有AnimatorDriver、两层Run/Stop及Axis的正常/边界/失败清理；真实六轴回归保留。

### 09：Controller全状态与超时（定向通过）

- ControllerStateMatrix +6、TaskStateChangeTimeOutListener +5，共11新case。
- `.gradle/lib-class-tests-09-targeted.log`：control全组Debug成功，42s；分类矩阵、真实Context sw600边界、非法输入/回调异常/集合身份与精确timer边界。

## 当前验证与完成审计

- 九批累计新增159个case、11个测试类（前四批92 + 后五批67）；42/42源码文件、74/74命名类型均有逐类证据，最终全量验证通过，逐类补测目标已完成。
- 全量命令：`./gradlew.bat :lib:testDebugUnitTest :lib:testReleaseUnitTest :demo:testDebugUnitTest :demo:testReleaseUnitTest :demo:assembleDebug --rerun-tasks --offline --no-daemon --no-build-cache --no-configuration-cache --max-workers=1 "-Pkotlin.incremental=false" --console=plain`。
- 最终全量：lib Debug/Release各55类、493 tests；Demo Debug/Release各1类、6 tests，四组均零失败/错误/跳过，两个变体的类/用例数量一致。组合56类/499独立用例，不按变体翻倍。
- `.gradle/lib-class-tests-01-09-final.log`：BUILD SUCCESSFUL，118/118任务实际执行，4m23s，Demo Debug APK重新构建成功；141个执行输入文件hash核对一致，未在构建期间修改源码/配置。
- 静态审计：重新扫描42文件/74类型，与队列和74条逐类证据一一匹配；所有测试链接有效，README全部55条库测试计数与本次XML一致，Git whitespace检查通过。
- 前四批历史全量：lib Debug/Release各426 tests、Demo各6，零失败/错误/跳过，Demo Debug构建成功，118任务/3m21s。日志 `.gradle/lib-class-tests-01-04-final.log`；不是本轮最终结果。
- 生产代码仅修改OplusValueAnimator的NaN校验；AGENTS、OPPO参考源码与构建配置不修改。没有自动提交本轮改动。
- 未执行lint、覆盖率工具、设备或Perfetto验证；测试数量不是行/分支覆盖率，真实Looper/AndroidX JVM集成不代表真实VSYNC/后台View/系统窗口验收。
