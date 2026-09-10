# vs-oppo-22 — TaskState 超时、事件桥与释放复核

> **第 27/39 份：✅完成（含明确保留边界）。** 本份核对实际七回调、三场景宿主事件桥及一次性消费，不把“有本地事件入口”包装成完整系统任务总线。进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## 1. 范围与原厂接口

只读 `D:\oppo_a6_launcher\sources\com\oplus\quickstep\taskviewremoteanim`：TaskStateHelper.java 当前 **319 行**、TaskStateHelper$taskListener$1.java **182 行**。前者 75–114 为接口、117–208 为 timeout、212–216 为枚举、223–318 为注册/清理；后者把六种系统任务事件转到 MAIN_EXECUTOR。

原七回调为：onBackPressedOnTaskRoot、onLandScapeSceneExit、onTaskAppeared、onTaskInfoChanged、onTaskListenerReleased、onTaskVanished、onTransitionFinish。旧文提到的 onTaskViewAppeared/Destroyed、onAllAppExitTransitionFinish、onUnfoldAnimationStart 不在**该接口**；本轮不复用旧“全树零命中”的未经重查结论。

本库证据：[TaskStateChangeTimeOutListener](../../lib/src/main/java/com/asyncanimator/control/TaskStateChangeTimeOutListener.kt)、[AnimationController](../../lib/src/main/java/com/asyncanimator/control/AnimationController.kt)、[Demo6](../../demo/src/main/java/com/asyncanimator/demo/Demo6StateMachineActivity.kt)。已有测试：[TimeoutOwnershipTest](../../lib/src/test/java/com/asyncanimator/control/TimeoutOwnershipTest.kt)、[TaskStateEventTest](../../lib/src/test/java/com/asyncanimator/control/TaskStateEventTest.kt)、[ControllerThreadContractTest](../../lib/src/test/java/com/asyncanimator/control/ControllerThreadContractTest.kt)；本轮扩展 [TaskStateChangeTimeOutListenerTest](../../lib/src/test/java/com/asyncanimator/control/TaskStateChangeTimeOutListenerTest.kt)。

## 2. 成员与事件映射

| 原厂成员/行为 | 本库实际能力及差异 |
|---|---|
| 全局 COW listener 列表 | Controller 三个 timeout slot 是本地注册表；每次注册先 dispose 旧实例，身份检查保护重入 replacement。不是进程级广播给任意监听器。 |
| Cookie ArrayMap / taskId SparseArray | 未接 Binder cookie、taskId 任务路由；没有对应容器，不虚构泄漏或按名注销调用。 |
| SystemUiProxy init/release/register | 未接系统 TaskListener Binder；宿主必须主动送已判定的事件，不能只初始化本地 Controller 就期待系统自动回调。 |
| public onTimeOut(type,duration) | 按 type 匹配后原子抢占一次 action；duration 是兼容事件参数，不重设/比较构造时的 timer。直接事件不是“只模拟时间”。 |
| 三种 Controller 场景 | LAND_SCAPE、TRANSITION、APP_TO_OVERVIEW；匹配事件与 Handler timer 任一先到只执行一次，其他场景不能消费其 action。 |
| 原厂第四枚举 TO_HOME | 专属 task-listener release 或 transition(true)；当前 Controller 没有该场景/slot，不加入一个无人调度的枚举来伪称功能齐全。 |
| 原厂 landscape(boolean) | 只检查 LAND_SCAPE type，**不要求 boolean=true**。宿主映射不能机械统一成所有 false 都忽略。 |
| 原厂 transition(boolean) | TRANSITION 或 TO_HOME 且 true 才执行；false 不算完成。本库 type bridge 接收已判定的完成事件，调用方先过滤 raw boolean。 |
| 原厂 listenerReleased | 仅 TO_HOME 触发 option；不能自动把 release 映射为 APP_TO_OVERVIEW。当前此类三个原厂 callback 不匹配 overview，后者仍有 timer 兜底；本库额外提供明确 overview 宿主事件。 |
| Handler / 调用线程 | 本库 timer 固定 main；Controller-owned event 注册/消费/dispose 在修改前检查 main。独立 listener 的 raw event action 在获胜调用线程执行，原子一次性不等于 UI 可跨线程写。 |
| dispose 与 action | 本库先原子取走 action、撤 timer，action 后通常通知 owner 释放；显式 dispose 可先取消尚未领取的 action。原厂多处先 dispose 再 option，且 option 是 final，不保证和本库并发一次性语义相同。 |
| getters / setHandler | 不暴露可重放 action/Runnable 或任意可变 handler；无迁移剩余时间/撤旧任务契约时仅加 setter 会留下旧 timer。 |
| removeAllListener | 本地 destroy/Manager disable 释放三个 slot、撤 timer/动作，不广播系统 listenerReleased；没有 cookie/task map 的统一系统清理能力。 |

## 3. 本轮实际修复与验证要点

### 保留主异常，仍完成释放

原实现 `runCatching(action).also { notifyDisposed() }.getOrThrow()` 会在 action 与 dispose callback 同时抛错时被后者覆盖。本轮保持 action 为主异常、cleanup 作为 suppressed；只有 cleanup 失败则直接抛它；不吞 Error/Exception。两个 AtomicReference 在调用前清空，后续 event/dispose 不重放任何一个回调。

### 一次性不是跨线程完成屏障

- 8 个并发 standalone event 只允许一个领取 action 和一个 disposal callback；获胜 action 在其中一个 event 线程运行。
- dispose 若先赢得清空，后续 action 不执行；若 event 已领取 action，dispose 不能撤回正在执行的业务代码，也不等待它结束。
- 非正 timeout 使用 Handler 立即到期的排队任务，构造器不 inline 执行 option。主线程忙时 timer 可以延后，未声明真实时限保证。

### 生命周期诊断

新增 LogUtils 门控的 registered(type/delay)、firing(type/source=timer或event)、disposed(type)。disposal 只有一次日志；OFF 无输出。无 cookie/task 包名/业务对象内容，不等于原厂 Logcat 分类或 native Perfetto。

## 4. 原 15 项遗漏与风险处置

| 原遗漏 | 本轮结论 |
|---|---|
| 1 TO_HOME 枚举 | ✔️系统场景未接，保持不加空值；三场景边界明确。 |
| 2 Base listener / 3 七回调接口 | ✔️不声明无消费的系统接口。公共 type bridge 的签名不同，不称 API 等价。 |
| 4 Binder 派发 | ✔️系统事件源未接；宿主 bridge + Demo6 实际调用存在，旧“事件路径完全死路”撤销。 |
| 5 全局注册 / 6 全局注销 | ✅本地 slot 注册/替换/dispose 释放已经实际实现；不新增另一份全局表来制造双重所有权。 |
| 7 三 callback 合并 | ✅按已合格 type 匹配的显式接口，宿主需正确映射 boolean/release；本轮 KDoc 补清约束。 |
| 8 URGENT executor | ✔️保持 main owner，不把 Controller/View 动作移到后台制造数据竞争；原厂 urgent transaction 完整链未移植。 |
| 9 removeAll | ✅本地 destroy/feature-off 清理有回归；不宣称系统广播 release。 |
| 10 cookie/taskId / 11 cookie 注册 / 12 两表移除 | ✔️没有这些业务对象/容器，均不移植。原厂 removeListenerAllOfList 按两个容器各 indexOfValue 摘除，不是全局 timer 释放方法。 |
| 13 SystemUiProxy / 14 registered flag | ✔️未接 Binder，不用空 init/set true 伪造注册成功。 |
| 15 继承 BaseTaskStateChangeListener | ✔️类型不同，独立 listener 不是原厂 seven-default adapter。 |

风险逐项：A1（事件）/A2（签名）按上表已澄清；A3 系统 11 个调用点仍不能运行于本库，不称已迁移。B1 TO_HOME 保留未接；B2 main 是明确 owner 合约而非“少一档优先级 bug”。C1 option 引用此前已原子清除，本轮补异常/并发证据；C2 setHandler 不增；C3/D2 日志本轮实现本地诊断。D1 不导出可重放 getter。D3 原厂 global 注册 contains 去重，但 cookie 注册是 map.put 覆盖，不是 contains；本地单 slot 替换天然只有一个当前 owner。

## 5. 原八项回移与七项简化建议

1. **最小全局总线 + 三 callback**：已有更明确的 owner-scoped 事件桥与 Demo6 消费；保留七回调/Binder 不兼容，不把 bridge 称完整 TaskStateHelper。不要新增重复注册表。
2. **timer 换 ANIM/URGENT Handler**：不采纳仅换线程的建议；Controller 动作/View 更新限 main，动画 executor 不是事务线程；真机 main 阻塞风险仍需设备验证。
3. **TO_HOME 类型**：无对应场景/系统release调用方，不补无效枚举；未来接入必须同时设计其事件/timeout/cleanup。
4. **dispose 清 option/type**：option/disposalCallback 实际清空，type 无宿主对象不必设 null；本轮双异常仍可靠释放。
5. **setHandler 测试口**：不增加会遗留旧排队任务的可变 owner；现有 paused Looper 能验证时间与事件并发。
6. **五处日志建议**：按本地三个真实生命周期节点落实，不复制 OEM 分类/Debug 堆栈。
7. **全局 contains 去重**：不用未接的全局列表；slot 替换 + action 原子抢占已有回归。
8. **Demo6 模拟事件按钮**：已实际调用 dispatchTaskStateChange，与 timer fallback 并存；不直接调用不存在的 TaskStateHelper.taskListener。

原七个保持简化项继续逐项保留：cookie/taskId 容器、SystemUiProxy 注入、registered 状态、四类可重放 getter、OplusTaskListener Binder 基类、OEM 私有 runOnTargetThread facade、两表 removeListenerAllOfList。线程 marshaling 本地由既有 main executor/owner 检查负责，不新增空系统 facade。

## 6. 验证记录

- 本轮新增 6 tests：首轮原 10 条中双异常 1 失败；加入日志回归后 11 条中 2 失败（`.gradle/review-ordered-27-red.log` / `.gradle/review-ordered-27-final-red.log`）。并发抢占、已领取 action 的 dispose 边界及非正 timeout 在旧实现也通过，不虚报为修复的产品 bug。
- 定向 36 tests 通过；Debug/Release 各 **295 tests、41 类，0 failures/errors/skipped**；Demo Debug 成功，82/82 tasks executed，2m 16s（`.gradle/review-ordered-27-final.log`）。118 份源码/测试/构建配置 SHA-256 前后一致。
- README 测试表、Markdown 相对链接、`git diff --check` 及 AGENTS.md 未改检查通过。无设备/Perfetto 验证，Lint 未完成；既有构建警告保留。
