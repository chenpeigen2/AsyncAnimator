# lib 逐类测试补充清单（2026-09-10）

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
| `com/asyncanimator/anim/AsyncAnimCallbacks.kt` | `AsyncAnimCallbacks` | 待逐类核对/补充 |
| `com/asyncanimator/anim/AsyncSpringAnim.kt` | `AsyncSpringAnim` | 待逐类核对/补充 |
| `com/asyncanimator/anim/AsyncValueAnimator.kt` | `AsyncValueAnimator` | 待逐类核对/补充 |
| `com/asyncanimator/anim/CustomRectFSpringAnim.kt` | `CustomRectFSpringAnim`, `Event`, `Listener`, `AnimType`, `Driver`, `DisposableDriver`, `ReversibleDriver`, `AnimatorDriver` | 待逐类核对/补充 |
| `com/asyncanimator/anim/MultiAnimatorSet.kt` | `MultiAnimatorSet`, `Track` | 待逐类核对/补充 |
| `com/asyncanimator/anim/OplusValueAnimator.kt` | `OplusValueAnimator`, `AnimParam`, `TimeControllerObjectAnimator` | 待逐类核对/补充 |
| `com/asyncanimator/anim/RecordInputInterpolator.kt` | `RecordInputInterpolator` | 补充完成，全量验证通过 |
| `com/asyncanimator/anim/RectAnimationLifecycle.kt` | `RectAnimationLifecycle`, `Stop`, `Run` | 待逐类核对/补充 |
| `com/asyncanimator/anim/RectSpringConfig.kt` | `RectSpringConfig`, `Tracking`, `Spring`, `RectSpringValues`, `RectSpringFrame` | 补充完成，全量验证通过 |
| `com/asyncanimator/anim/RectSpringDriver.kt` | `RectSpringDriver`, `Stop`, `Run`, `Axis` | 待逐类核对/补充 |
| `com/asyncanimator/anim/SpringProjection.kt` | `SpringProjection` | 补充完成，全量验证通过 |
| `com/asyncanimator/control/AnimationController.kt` | `AnimationController` | 待逐类核对/补充 |
| `com/asyncanimator/control/AnimationScene.kt` | `AppExitScene`, `GestureScene` | 补充完成，全量验证通过 |
| `com/asyncanimator/control/AnimationState.kt` | `AnimationState` | 补充完成，全量验证通过 |
| `com/asyncanimator/control/DefaultAnimationController.kt` | `DefaultAnimationController` | 补充完成，全量验证通过 |
| `com/asyncanimator/control/OnAnimStateChangeListener.kt` | `OnAnimStateChangeListener` | 补充完成，全量验证通过 |
| `com/asyncanimator/control/RemoteAnimationFactory.kt` | `RemoteAnimationFactory` | 补充完成，全量验证通过 |
| `com/asyncanimator/control/TaskStateChangeTimeOutListener.kt` | `TaskStateChangeTimeOutListener`, `Type` | 待逐类核对/补充 |
| `com/asyncanimator/core/AnimationHandler.kt` | `AnimationHandler`, `AnimationFrameCallback`, `TickSchedulerHolder` | 补充完成，全量验证通过 |
| `com/asyncanimator/core/ChoreographerTickScheduler.kt` | `ChoreographerTickScheduler` | 补充完成，全量验证通过 |
| `com/asyncanimator/core/LogUtils.kt` | `LogUtils` | 补充完成，全量验证通过 |
| `com/asyncanimator/core/TickScheduler.kt` | `TickScheduler`, `FrameCallback` | 补充完成，全量验证通过 |
| `com/asyncanimator/core/Trace.kt` | `Trace`, `Section` | 补充完成，全量验证通过 |
| `com/asyncanimator/manager/AnimationFeatureHelper.kt` | `AnimationFeatureHelper`, `Registration`, `Snapshot`, `SyncedVar` | 待逐类核对/补充 |
| `com/asyncanimator/manager/OplusAnimManager.kt` | `OplusAnimManager` | 待逐类核对/补充 |
| `com/asyncanimator/playback/AnimationSuccessListener.kt` | `AnimationSuccessListener` | 补充完成，全量验证通过 |
| `com/asyncanimator/playback/AnimatorListeners.kt` | `AnimatorListeners` | 补充完成，全量验证通过 |
| `com/asyncanimator/playback/AnimatorPlaybackController.kt` | `AnimatorPlaybackController`, `Holder`, `OnAnimationEndDispatcher` | 补充完成，全量验证通过 |
| `com/asyncanimator/playback/Interpolators.kt` | `Interpolators` | 补充完成，全量验证通过 |
| `com/asyncanimator/playback/NullableAnimatorListener.kt` | `NullableAnimatorListener` | 补充完成，全量验证通过 |
| `com/asyncanimator/playback/NullableAnimatorListenerAdapter.kt` | `NullableAnimatorListenerAdapter` | 补充完成，全量验证通过 |
| `com/asyncanimator/playback/PendingAnimation.kt` | `PendingAnimation`, `ObjectAnimator` | 补充完成，全量验证通过 |
| `com/asyncanimator/playback/PropertySetter.kt` | `PropertySetter` | 补充完成，全量验证通过 |
| `com/asyncanimator/seq/AnimationSeqHelper.kt` | `AnimationSeqHelper` | 待逐类核对/补充 |
| `com/asyncanimator/seq/AnimSeqTimeStamp.kt` | `AnimSeqTimeStamp` | 待逐类核对/补充 |
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

## 当前验证与下一步

- 累计新增92个case、11个测试类；2026-09-10全量验证通过：lib Debug/Release各55个测试类、426 tests，Demo Debug/Release各1个测试类、6 tests，均零失败/错误/跳过；Demo Debug构建成功。生产源码未修改。
- 已逐类补充28/42个文件中的41/74个命名类型，其余14文件/33类型待继续。下一批：AsyncAnimCallbacks / AsyncValueAnimator / AsyncSpringAnim，再处理Rect生命周期、RectDriver/内部Run/Axis、Multi/Oplus、Controller/TaskState、Manager/Feature、Seq/时间戳。
- 总目标仍是全部lib类的详细测试，未标完成。本次提交仅记录前四批补测成果；未执行lint、设备或Perfetto验证，JVM结果不代表真实VSYNC验证。
- 全量命令：`./gradlew.bat :lib:testDebugUnitTest :lib:testReleaseUnitTest :demo:testDebugUnitTest :demo:testReleaseUnitTest :demo:assembleDebug --rerun-tasks --offline --no-daemon --no-build-cache --no-configuration-cache --max-workers=1 "-Pkotlin.incremental=false" --console=plain`。
- 本地日志：`.gradle/lib-class-tests-01-04-final.log`（不入库），BUILD SUCCESSFUL，3m21s；验证前后141个源码/测试/配置文件SHA-256一致，XML汇总确认上述结果。
