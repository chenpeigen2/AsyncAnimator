# vs-oppo-17 — MultiAnimatorSet 四通道聚合复核

> **第 22/39 份：已完成本轮建议验收（含明确差异）。** 本文取代旧版“没有 MultiAnimatorSet / 保真度 14%”结论。逐项对照当前实现及 OPPO 源码；旧版 PendingAnimation 不是本组件的等价物。进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## 1. 范围与证据

- 只读参考：`D:\oppo_a6_launcher\sources\com\android\quickstep\util\animation\MultiAnimatorSet.java`，476 行；重点为字段 34–52、完成/解绑 103–132、取消/结束 143–218、启动 277–391、play 重载 395–475。
- 本库 [MultiAnimatorSet](../../lib/src/main/java/com/asyncanimator/anim/MultiAnimatorSet.kt)、[CustomRectFSpringAnim](../../lib/src/main/java/com/asyncanimator/anim/CustomRectFSpringAnim.kt)、[RectSpringDriver](../../lib/src/main/java/com/asyncanimator/anim/RectSpringDriver.kt)。
- [Demo9](../../demo/src/main/java/com/asyncanimator/demo/Demo9AllAppsTransitionActivity.kt) 已实际使用四通道和 Canvas/Animator Rect adapter；[Demo12](../../demo/src/main/java/com/asyncanimator/demo/Demo12RectSpringActivity.kt) 使用真正的六轴数值/几何 driver。二者都不是 SurfaceControl 系统转场。
- [MultiAnimatorSetTest](../../lib/src/test/java/com/asyncanimator/anim/MultiAnimatorSetTest.kt)、[RectSpringDriverTest](../../lib/src/test/java/com/asyncanimator/anim/RectSpringDriverTest.kt) 覆盖聚合及真实六轴实际结束。

## 2. 字段与方法映射

旧“21 字段”表重复列出 mHasRequestCancel，混入 TYPE 常量与 PendingAnimation 自有字段；不再用重复项计算保真度。

| 原厂成员/协议 | 当前实现与边界 |
|---|---|
| mAnimatorSet / mAsyncAnimatorSet | 两个独立 AnimatorSet；支持接管已有 main set 的构造器。异步 set 的运行操作在 launcher.anim，结束回主线程。 |
| mSpringAnimations / mRectFSpringAnim | 主线程 AndroidX spring 集合 + 一个有实际 Driver 的 Rect 句柄。拒绝裸句柄假装播放。 |
| 四个 *Ended | `pending` 四轨集合；启动前先预留全部轨道，防同步/零时长完成提前释放；任何未结束轨道使 isRunning 为 true。 |
| mStarted / mHasRequestCancel | 启动重入守卫 + 每轮复位的 volatile 取消信号。本轮补 volatile；不意味着后台可以读取/修改集合。 |
| mAnimationId / mAnimEndCallback | animationId 和一次性 reset callback；先消费回调再调用外部代码，保留重入新轮回调。不是 AnimationSeqHelper 的替代。 |
| mAnimatorListeners | 稳定快照、去重、可移除；通知传 main AnimatorSet，不传原厂 null。Adapter 同步 animationId。 |
| mSpringAnimEndListener | 每个 spring 独立的自有 listener，终止/解绑/销毁时移除；不复制共享 listener 分配策略。 |
| mAnimType / 分类谓词 | 七值 AnimType；isAppOpenType 只匹配 OPEN_FROM_HOME，isGestureToDrag 只匹配 GESTURE_TO_DRAG。 |
| TYPE 1 / 2 / 4 / 7 | 1 同时控制 sync+async set，2 控制 spring，4 控制 Rect，7 全部；拒绝未知 bit。 |
| play(Animator) / play(Boolean, Animator) | 普通加入 / 配置线程；启动后不重新配置线程。 |
| play(Animator, Boolean) | **live-add**，不是异步选择；额外 Animator 在聚合 cancel/end 时取消。 |
| play(SpringAnimation...) / play(SpringAnimation) | 去重、vararg、运行中加入；已运行 spring 也挂完成监听，不重启。 |
| play(Rect) | 只接受首个、未启动时设置分类。句柄 animType 不会重建独立 driver 的物理参数；构造 driver 时须传正确类型。 |
| start / maybeOnEnd | 四轨 barrier；async 队列携带代次；Rect 等 actual end，而非 logical end。空集合同步 start→end。 |
| cancel / end / 两个 ExceptSpring helper | mask 路由；cancel 发一次聚合取消，end 不发取消；排除 spring 则解绑但不停止独立 spring，并清 reset callback。 |
| destroy / clear | 本库额外最终释放：失效旧任务、移除自有监听、取消所有仍拥有的轨道、dispose Rect。已解绑 spring 由调用方管理。 |

## 3. 关键运行契约与本轮修复

1. 所有聚合操作/通知属于主线程。`hasRequestCancel` 可供后台读取；它是取消请求信号，不承诺撤回当前帧或停止所有轨道。`isRunning` 等集合查询仍限主线程。
2. `cancel(0..7)` 遵循 mask，并非每个非零 mask 都停止全部内容。排除 spring 时直接从 barrier 摘除；独立 spring 继续运行，调用方必须自行取消/释放。
3. `end(2)` 仅对 `canSkipToEnd()` 为真的 spring 请求结束。零阻尼不能 skip，仍等待自然结束或 cancel；旧文把欠阻尼/过阻尼当异常条件是错误的。Rect driver 的 skip 有独立下一帧终态协议。
4. 系统关闭动画仍只请求 `end(3)`，不抢占 Rect actual-end 语义。后台计算不允许直接修改 View。
5. 本轮新增观察者异常隔离：start/cancel/end/reset 的普通 Exception 记录后继续，不再让一个观察者阻止其他通知或卡住物理收尾；不吞 Error，不承诺恢复任意 Animator/driver 内部异常。这是本库安全策略，非原厂异常传播顺序的复制。

6. 本轮补强同步重入边界：原 cancel/end 在主轨完成回调开启新轮后，会继续遍历并停止新轮 spring。每次外部/native 回调后核对代次；完成时先摘除旧 live 集合并捕获完成 ID，避免 live cancel 观察者重入把旧 ID 改成新 ID。

## 4. 原风险逐项处置（6 + 7 + 3）

| 原项 | 本轮结论 |
|---|---|
| B1 四通道 maybeOnEnd | ✅已实现，四轨结束与真实 Rect 六轴等待均有测试。 |
| B2 cancel 信号 | ✅本轮补 volatile 发布；回归检查 JVM 字段修饰与下一轮复位。无需给 PendingAnimation 添加无消费者信号。 |
| B3 mask | ✅8 个 mask 组合覆盖；明确 spring 排除意味着解绑、reset 丢弃，不是继续等它。 |
| B4 play 参数顺序 | ✅两个 Boolean 重载语义独立，旧表把 live-add 当 async 的结论撤销。 |
| B5 cancel(1) | ✅同步 + 异步 set 都停止；异步结束须回主线程清 barrier。 |
| B6 skip 校验 | ✅实际 AndroidX canSkipToEnd；零阻尼回归保留，不给无此契约的 AsyncSpringAnim 硬加 end。 |
| M1 SpringHolder 延迟 | ✅RectSpringDriver 的 alpha 延迟已实际实现；不是 Multi 普通 AndroidX spring 通道的通用 startDelay API。按六轴真实需求落地，不 fork 私有内核。 |
| M2 spring 解绑 | ✅remove listener / clear 集合 / 清 pending / 消费 reset 路径已实现；独立 spring 仍归调用方。 |
| M3 vararg/live-add | ✅已实现，并覆盖新 spring 加入正在运行的组。 |
| M4 ID+完成 callback | ✅独立 ID 和一次性 callback；不是 trace ID 或 Seq 回调的替代。异常回归本轮补齐。 |
| M5 isRunning | ✅任意剩余轨道均为 true；逻辑 Rect cancel 不等于全部结束。 |
| M6 共享 listener | ✔️保留每 spring 一监听，避免跨代次/实例混淆；不声称分配数量或 OEM 性能一致。 |
| M7 null listener 参数 | ✔️公开 Kotlin API 使用非空 Animator，明确差异；不把 null 与真实 source 称为行为等价。 |
| L1 分类谓词 | ✅已实现，七值分类矩阵已覆盖。 |
| L2 调试 ID | ✅animationId 已实现；不复制反编译 getter 命名。 |
| L3 ExceptSpring helper | ✅两个 helper 已实现，相当于 mask 5。 |

## 5. 原建议与三阶段计划处置

| 原建议 | 结论 |
|---|---|
| R1 四通道主装配器 | ✅已经实际实现并被 Demo9 消费；不重复新建第二份，也不把所有 Launcher 私有业务宣称打通。 |
| R2 SpringHolder-style node | ✅第 21 份的 Rect 六轴 Axis/Run、alpha delay 和预测满足当前可移植场景；不复刻私有 SpringHolder 类型或移植给普通 spring 通道。 |
| R3 MultiDynamicAnimation 帧循环 | ✅六轴 driver 使用公开 AndroidX FrameCallbackScheduler 聚合、后台 owner 与物理结束；播放积分由 AndroidX 负责，不复制隐藏内核。 |
| R4 canSkipToEnd | ✅四通道 spring 的实际 end 路径已覆盖；纠正旧欠阻尼/过阻尼判断。 |
| R5 volatile flag | ✅本轮实际补齐，与原厂跨线程信号用途一致；本库尚无窗口 transaction 消费链。 |
| 原“不补”分类谓词 / ID / helper / vararg+live-add | ✅均已有实现，旧保持简化标记失效。 |
| 原“不补”共享 listener | ✔️保持独立 listener 的可追踪清理，不为名字/行数复制内部优化。 |
| 第一阶段 bug / 第二阶段主调度 / 第三阶段节点帧循环 | ✅按当前功能范围已覆盖，含明确公开 API/系统边界；不再采用旧 60/250/350 行估算或保真度百分比。 |

## 6. 验证

- 新增 4 条回归在修复前全部失败：volatile 字段、取消观察者、启动/结束观察者、reset 异常（`.gradle/review-ordered-22-observers-red.log`）。
- 另有 2 条重入回归先失败：旧 cancel 停掉新 spring（随后扩展覆盖 end）、旧完成 ID 被 live 清理回调改成新轮 ID（`.gradle/review-ordered-22-reentrant-red.log` / `.gradle/review-ordered-22-id-red.log`）。
- 修复后 64 条定向回归通过；最终 **Debug/Release 各 266 tests、37 类、0 failures/errors/skipped**，Demo Debug 成功，82/82 tasks executed、2m 3s（`.gradle/review-ordered-22-final.log`）。114 份源码/测试/两模块配置 SHA-256 前后一致。
- `git diff --check` 与相对文档链接检查通过；AGENTS.md、OPPO 与无关文件未改，无提交/推送。
- 无设备或 Perfetto 数据；Lint 未完成，不将 Robolectric/构建通过写成系统转场性能验收。
