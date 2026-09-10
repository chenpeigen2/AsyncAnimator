# 区域 09 对比 Review：生命周期与资源回收

## 2026-09-09 顺序验收：✅完成（第 14 份，可移植生命周期）

对照只读 `D:/oppo_a6_launcher/sources` 与当前 `lib/src/main/java/com/asyncanimator/`、Demo 调用点，逐项处理原 §③ 十项风险、§4.1 八项建议、§4.2 七项保留决策。本文替换已过时的“无 destroy / 无 onDestroy / option 未释放 / 无访问器”结论；旧版记录中的 2026-09-11 不是本轮验证日期。

“决策完成”不等于 OEM 引擎全量移植；后台弹簧、Binder 任务总线、SurfaceControl 和设备时序仍不在本次实现范围。总进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## ① 原厂证据与本库边界

路径均相对 OPPO `sources/`；参考树只读，不把反编译代码直接覆盖到本库。

| OPPO 证据 | 当前落点 / 差异 |
|---|---|
| `com/oplus/basecommon/thread/LooperExecutor.java:71-78`，shutdown/shutdownNow 抛异常 | `thread/LooperExecutor.kt` 保留进程执行器 never-quit 契约；不由页面关闭线程 |
| `com/android/quickstep/util/animation/AsyncValueAnimator.java:116-127`，cancel 按 executor 派发 | `anim/AsyncValueAnimator.kt` 固定首次生命周期命令的 owner；新增最终 dispose，不伪装成原厂已有接口 |
| `com/android/quickstep/util/animation/AsyncAnimCallbacks.java:29-32,107-109,147-162`，快照、clear、null 槽、主线程派发 | 本库加锁快照与代次失效；dispose 清监听/ID；容器释放与 animator 最终释放区分 |
| `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:147-154,270-277`，dispose 撤回 timer / removeAllListener | 本库用 Controller 三个注册槽集中 destroy，没有复制全局 Binder 注册；动作与 owner 回调消费后释放 |
| `com/android/quickstep/util/animation/MultiDynamicAnimation.java:92-101,168-176`，requestEnd / 帧末结束 | 本库没有该 OEM 物理引擎；Rect Driver 区分逻辑/实际结束，不声称 cancel 时序完全一致 |

## ② 本轮实际修改

### 最终动画释放

`AsyncValueAnimator.dispose()` 立即标记最终销毁、失效排队生命周期命令和异步通知；在固定 owner Looper 上清除原生 update/listener 并 cancel。首次 start/cancel/end（即使仍在队列）即绑定 executor，禁止生命周期中跨 Looper 迁移。dispose 幂等；之后 start、executor 修改、包装器 listener 注册被拒绝，cancel/end 不再操作。

onStart 内重入 dispose 后，start 的 finally 再收尾，避免平台启动尾部重新挂帧。不能撤回已开始执行的回调，也不等待后台取消完成。原生属性/监听在启动前配置；销毁后不得绕过包装器再次注册。保留 owner 用于正确线程清理，并非新增线程退出协议。

### Manager 与超时引用

`OplusAnimManager.interruptionEnabled` 在主线程切换。关闭先摘掉全局实例引用，再 destroy 旧 Controller，并在 finally 撤销旧 Seq 延迟 finish；重新开启创建新实例。旧实例被外部持有时不赋予“撤销所有未来调用”的保证。

超时已通过 AtomicReference 消费/清空 option 与 onDisposed；本轮将 Controller 的 bound `::checkMainThread` 改成不捕获实例的函数引用，避免已 dispose 的外部 timer 句柄继续通过线程检查函数保留 Controller。枚举 type 不持有 Activity，无须强行置 null。

### 调用方生命周期

- Demo3 使用统一 animator.dispose，去掉依赖“排队 cancel 恰好位于 start 之后”的补丁。
- Demo10 在 onCleanup 移除初始 post Runnable，停止时递增代次、移除监听/释放动画。旧 UI 工作被过滤；每轮统计对象独立，后台只计算/采样，View 更新回主线程。没有“主线程阻塞但 View 仍流畅”的保证。
- Demo11 原先把未配置后台 scheduler 的 AndroidX View 弹簧直接送到后台；现在两种调用模式都明确运行于主线程，包装模式使用 supportAnimThread=false。onCleanup 移除原生 update/end listener，并沿启动时使用的包装器取消；不宣称移植 OEM 后台物理。
- Demo6/7/9 已走各自 onCleanup；公共场景 View detach 停止时钟。基类只提供钩子与日志恢复，不擅自销毁其他页面共享的全局 Controller。

## ③ 十项风险的当前结论

| # | 风险 | 处理与边界 |
|---|---|---|
| 1 | ANIM_EXECUTOR 关闭 | ✅已实现 never-quit，shutdown 系列拒绝调用 |
| 2 | 无主超时 / 事件源 | ✅本地 ownership 与 dispatchTaskStateChange 桥已实现；系统 Binder 总线保留不移植 |
| 3 | 集中清理缺失 | ✅Controller.destroy 清观察者、三类 timer、挂起启动和状态；manager 关闭也调用 |
| 4 | Activity 清理不完整 | ✅补齐 Demo3/10/11，其他既有清理保持；设备销毁回归未执行 |
| 5 | listener 强引用 View | ✅以显式释放修复本地持有链，不改成易丢事件的弱引用；业务仍须遵守所有权 |
| 6 | OEM cancel 下一帧语义 | ✅决策完成：不移植缺失引擎；不承诺平台 Animator/Driver 与 OEM requestEnd 时序等价 |
| 7 | ThreadLocal 生命周期 | ✅保持进程单例；线程退出与 Activity 销毁不是同一层级，不增加 quit 入口 |
| 8 | AnimationRecord/IconLayer | ✅场景外，不引入 launcher 业务对象 |
| 9 | option / 线程 guard 保留引用 | ✅消费和 dispose 清动作/owner 回调；guard 改为无实例绑定；枚举 type 保留 |
| 10 | MultiDynamicAnimation end 后监听清理 | ✅不移植不存在的链路；本次原生 Animator 与 Demo Spring 由实际 owner 显式清理 |

## ④ 回移建议逐条验收

### 4.1 八项建议

| # | 原建议 | 本轮结论 |
|---|---|---|
| 1 | Controller.destroy/release | ✅已有实现，补 manager 实际关闭调用与回归 |
| 2 | LooperExecutor.shutdown | ✅已实现并保持拒绝关闭契约 |
| 3 | DemoBaseActivity.onDestroy | ✅已有基类钩子；本轮补具体实例与迟到工作清理，不用全局 reset 冒充页面清理 |
| 4 | 暴露 quitSafely | ✅不采纳：进程单例不归 Activity 所有，公开退出会破坏其他场景 |
| 5 | AsyncAnimCallbacks.dispose | ✅已有代次失效；与最终 animator.dispose 分开记录 |
| 6 | option/type 置 null | ✅动作及 owner 闭包已释放，补不捕获 Controller 的 guard；type 枚举无泄漏对象，不强改可空 |
| 7 | 生命周期文档 | ✅更新 USAGE、README、Demo 入口描述，明确固定 owner、最终释放和安全回退 |
| 8 | AsyncValueAnimator.dispose | ✅新增并由 Demo3/10 实际调用，不再以 GC 足够为理由忽略排队启动 |

### 4.2 七项保留决策

1. **全局任务事件总线**：不移植 Binder 注册；公开本地事件桥与三场景 timer OR 语义已经可用，不能再写“无人调用 onTimeOut”。
2. **AnimationRecord/IconLayer 复用**：不引入，属于 launcher 窗口业务。
3. **Launcher.onStop 业务回调**：不复制 folder/stack/RecentsView 专用清理；只处理本 Demo 真正拥有的资源。
4. **LooperExecutor 访问器**：旧“缺少 getLooper/getHandler/getThread/setThreadPriority”已过时；四个访问器已有实现/测试，本轮保持，不算未移植项。
5. **executeBlockWait**：不引入主线程阻塞等待；本次 dispose 不等待 owner 线程完成。
6. **MultiDynamicAnimation listener 清理**：缺失引擎不造空壳；Driver/Animator 的真实 owner 自行释放。
7. **clearListeners 额外压缩**：clear 已移除所有元素，额外 removeNullEntries 无意义；派发前快照压缩与同步保持。

## 验证与限制

新增 AsyncValueAnimatorLifecycleTest 7 条、ManagerLifecycleTest 4 条、TimeoutOwnershipTest 1 条（共 12 条），覆盖排队失效、最终释放、重入、固定 owner、真实 HandlerThread 取消、功能关闭撤回任务、外部 timer 引用释放。

- 全量两次重跑均在 ChoreographerTickSchedulerTest 的 3 条失败；与新生命周期 / Multi 用例的缩小组合通过。实际原因是 Robolectric 4.16 的 InstrumentationConfiguration 会把 android.view 归并入 android.，此前“独立 sandbox”说明不成立；ShadowPausedSystemClock 又保留静态 receiver listener，共享 next-vsync 可被其他 Looper 消费。
- 改为非冗余 `com.asyncanimator.core` instrumentation 配置形成独立 sandbox；保持 SDK 36、全部帧数/间隔断言与真实 HandlerThread 测试。修复后先完整 Debug 199/199 通过，再强制重跑两变体和 Demo。证据：`.gradle/review-ordered-14-vsync-full-repro.log` / `-vsync-isolation.log` / `-final.log`。
- **Debug / Release 各 199 tests，29 类，0 failures/errors/skipped；Demo Debug 成功，80/80 tasks executed，1m 57s**。两变体不重复累计为 398 个独立用例。
- 源码、测试和两个模块实际构建配置 SHA-256 验证前后一致（`.gradle/review-ordered-14-before-validation.json`）；`git diff --check` 通过。APK：`demo/build/outputs/apk/debug/demo-debug.apk`。
- **未进行设备销毁/切换回归或 Perfetto 测量；Lint 未完成**。线程 guard 检查的是引用结构和操作契约，不是设备 GC/泄漏检测结果。既有 SDK/Gradle/私有注解构建警告仍在；无提交/推送。
