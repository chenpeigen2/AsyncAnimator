# 区域 11 对比 Review：功能缺口与可移植边界

## 2026-09-09 顺序验收：✅完成（第 16 份，可移植功能）

对照当前库、Demo 与只读 `D:/oppo_a6_launcher/sources`，逐项处理原文 20 项遗漏、13 项风险、7 项回移建议和 7 项保留决策。旧文“Rect 18 行占位、overview 靠 100ms 判运行、forbidTouch 恒 false、没有配置通知”等结论不再代表当前代码。总进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## ① 本轮实现与证据

### 触摸保护：实现并接实际入口

- `AnimationController` 的 launch-start 设置开窗保护并更新 600ms 截止任务；重复 start 撤回旧任务。launch-end、reset、destroy 撤回任务并清保护，旧任务不能提前释放下一轮保护。
- `forbidTouch()` 由主线程读取：开窗保护、MULTI_WAITING、REVERSE_OPEN 或真实挂起启动 action 任一存在即阻止触摸。仅注册 timer / null action 不算挂起启动；no-op 基类仍返回 false。
- Demo9 的图标、窗口和转场按钮实际调用此查询；聚合开窗 start / 全部通道结束驱动 Controller 生命周期，销毁时清理。返回键和 onCleanup 不冒充 OEM 按键拦截，不被触摸保护阻止退出收尾。
- **原厂证据更正**：`com/oplus/quickstep/utils/AnimationController.java:95-100` 的触摸 Handler 是主线程，不是 URGENT_TRANSACTION_EXECUTOR。`:513-516` 在 end 排队释放，`:541-545` 在 start 更新 600ms 消息，`:685-688` 为触摸判据。本库采用主线程 Runnable，end/reset 直接释放，不承诺 OEM Message 编号或同一消息队列轮次。
- 主线程繁忙会延迟定时释放；600ms 是调度延时，不是绝对最长屏蔽时间，也不是物理动画结束时间。

### 配置通知、快照与 Adaptive 策略

- `AnimationFeatureHelper.addRemoteUpdateListener` 返回独立 AutoCloseable；Android 上统一 post 到主线程，回调不持配置锁。close/remove/onDestroy 使排队的旧注册失效；移除再注册不会接收旧注册的通知。每次 add 是独立注册，按 callback 移除时使用同一对象。
- 通知表示“读取最新状态”，不是每批历史配置的 payload；后台连续更新可令多个通知读到同一最新值。普通 consumer Exception 被记录并隔离，不阻止其余监听；不能撤回已执行中的回调。
- 新增 `snapshot()`：同锁读取七个标量、两个不可修改列表及 host Adaptive 标志。独立 getter 仍是独立读，不能推导跨字段原子性。
- `setAdaptiveAnimationEnabled(true)` 强制当前及后续阈值为 1.0f；`getRadiusAnimationEnable()` 返回 !adaptive。关闭策略不自动恢复某个历史阈值，后续配置可重新指定。该策略由集成方显式提供，不猜测 ROM 配置。
- OPPO `AnimationFeatureHelper.java:52-60`：六个整数默认 -1，threshold 默认 1.0f；`:123-128` 是 Adaptive 强制 1.0f，非一般区间 clamp；`:413-415` 是 radius getter，`:422-427` 注销 ROM listener。
- Demo8 后台模拟下发，页面由订阅回调刷新一致快照；新增 Adaptive 按钮。onCleanup 只 close 自己的订阅，**不调用全局 onDestroy 清掉别的页面**。功能开关初始值读取实际 Manager 状态，不固定显示 true。

## ② 原二十项遗漏逐条处理

| 原 C# | 模块 | 当前结论 |
|---|---|---|
| 1 | MultiDynamicAnimation requestEnd / 裸 delta | ✅决策完成：不移植 OEM 引擎；Rect 已有逻辑/实际结束协议，不承诺相同积分/帧末时序 |
| 2 | 六轴 SpringHolder 与半步重定向 | ✅保留不移植；单轴 AndroidX Spring 不等同六轴，不能写“物理已全部覆盖” |
| 3 | SpringAnimReflectUtils | ✅不引入隐藏/反射弹簧方法，现有 Driver 负责自身物理 |
| 4 | 六轴 calculateFrameRectF / 速度钳制 | ✅不移植几何求解器，明确 Canvas/Animator 适配边界 |
| 5 | Rect 跨线程协议 | ✅已有固定 owner、四类生命周期派发、重定向与双轨结束；不是 18 行占位 |
| 6 | UX 线程标记 | ✅不反射跨 ROM 私有调度器；只保留公开 priority，不推导大小核或帧率收益 |
| 7 | UAF 线程报告 | ✅不移植私有服务；不虚构性能提升数据 |
| 8 | AppOpenAnimMergeHelper | ✅不创建空 merge 壳：没有 Task/Surface/Binder 交接，无法据空方法声称合并成功 |
| 9 | MultiAppAnimMergeHelper | ✅不以 CAS 计数器替代远程多任务合并；Demo9 是四通道聚合，不是多 app 启动集成 |
| 10 | InterceptKeyEventHelper | ✅不移植 OplusWindowManager/InputManager 注入；Demo 正常返回与 touch gate 分开 |
| 11 | MultiOpenPreStartHelper | ✅不复制无 task/leash owner 的预启动骨架；不承诺第二次图标点击共享系统转场 |
| 12 | Swipe-to-recents helper | ✅已由可注入运行态或显式 overview 状态判定，不再用 100ms 时间窗猜测；不加五个假动画桩 |
| 13 | RUS 下发 | ✅本轮补本地跨线程通知与快照；真实 LauncherCommonConfigManager/RUS 服务未接入 |
| 14 | 配置 listener 注销 | ✅新增全局 owner onDestroy 和页面独立订阅 close；不声称注销不存在的 OEM 服务 |
| 15 | radius getter | ✅新增，由显式 Adaptive 策略派生 |
| 16 | 默认值 | ✅保留与源代码匹配的 -1 / 1.0f；更正“七个 int 全部 -1”的错误计数 |
| 17 | Adaptive threshold | ✅本轮实现 true 时强制 1.0f，六/九参数配置入口一致 |
| 18 | 600ms touch gate | ✅本轮实现、Demo9 接入并补主线程/重入时序回归 |
| 19 | Controller / timeout Handler | ✅区分两者：OEM Controller touch Handler 在主线程，TaskStateHelper.java:130 的 timeout 才在 urgent transaction 线程；本库两者主线程以维持状态所有权 |
| 20 | UAF 事件 ID | ✅不引入没有对应接收服务的常量表 |

## ③ 原十三项风险的结论

| # | 处理 |
|---|---|
| 1 | 六自由度物理未移植，明确功能边界，不把缺少场景外引擎直接等同 Demo 崩溃 |
| 2 | UX 调度无公开等价物，不宣称系统优先调度已对齐 |
| 3 | UAF 绑核不移植；不按设备品牌猜测大小核调度行为 |
| 4 | overview 运行态判据已修复，现有 launch-decision / scene 测试覆盖 |
| 5 | 远程多 app 场景未实现；Demo9 实为 MultiAnimatorSet，旧触发场景描述错误 |
| 6 | touch gate 已实现并接 Demo9；Demo11 不是 launcher 开窗流程，不强塞无关 Controller |
| 7 | 远程 merge 链未移植，不让空 helper 假装成功 |
| 8 | OEM BACK/NAV 注入不移植，普通页面返回不受本次 touch gate 限制 |
| 9 | Rect 双轨协议已有；AsyncSpringAnim/平台 cancel 不承诺 OEM requestEnd 帧时序 |
| 10 | 本地通知已有，真 RUS 仍未接；UI 展示政策不是所有物理引擎自动按配置重建 |
| 11 | 默认值、radius getter、Adaptive 强制值均落实；host 标志显式输入 |
| 12 | 现在有本地注册，故显式补其生命周期；旧“无对象可清理”不再适用，也不宣称进程 kill 后保留旧 listener |
| 13 | 主线程忙会推迟执行，不会使 postDelayed 在自身延迟之前触发；与 OEM urgent timeout 的时序不同，事件/timeout 排序不能仅凭理论保证 |

## ④ 七项回移建议验收

| # | 建议 | 决策 |
|---|---|---|
| 1 | 600ms 闸门 | ✅实现；纠正原建议把 Controller 的主线程 Handler 误写为 urgent 线程 |
| 2 | overview running helper 桩 | ✅已有真实输入接缝，不新增五个不可运行假 anim；保留现有 provider/显式状态 |
| 3 | multi-open / merge 骨架 | ✅不采纳空 CAS 骨架：不能处理真实 task/leash 所有权、系统回调和清理，无可验收合并场景 |
| 4 | 反射 UX 标记 | ✅不采纳，ROM 私有接口不保证存在/权限/行为；不因此量化 OEM 等价性能 |
| 5 | 配置更新监听 | ✅实现可注销、主线程派发的本地订阅，并接 Demo8 |
| 6 | 默认值 / radius / threshold | ✅默认值已保留；本轮补显式 Adaptive 策略、derived getter 和强制值 |
| 7 | onDestroy | ✅补本地注册清理；页面只关闭自有句柄，不能误清共享单例全部订阅 |

## ⑤ 七项保留决策

1. 不移植六轴 Rect 求解器及 SurfaceControl 几何写入；现有 Driver 是接缝，不是等价求解器。
2. 不整包复制 MultiDynamicAnimation/SpringHolder/SpringForce/ReflectUtils；AndroidX 单轴和 Rect 双轨仅覆盖各自明确范围。
3. 不复制反编译不完整的 onRemoteAnimationMerged，不造空分支冒充 merge 成功。
4. 不移植 OEM 按键反射/注入；不把触摸 gate 当作系统输入安全边界。
5. 不复制整套 swipe continuation 协调器；可移植运行态查询与状态机已工作。
6. 不加 UX/UAF 事件表，保留无设备性能证据的明确限制。
7. 不接真实 RUS；本轮增加的监听只构成本地配置发布机制。

## 验证与限制

新增 TouchGateTest 6 + FeatureNotificationTest 7（共 13 条），ControllerThreadContractTest 的入口矩阵增加 forbidTouch（矩阵不额外累计）。触摸 6 条旧代码全失败，修复后含配置/Manager 的 26 条定向测试通过。

- **Debug/Release 各 217 tests，33 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，1m 53s**（`.gradle/review-ordered-16-final.log`）。两个变体不计为 434 个独立测试。
- 源码/测试及两模块构建配置在验证前后 SHA-256 一致（`.gradle/review-ordered-16-before-validation.json`）；`git diff --check` 通过。红/定向日志为 `.gradle/review-ordered-16-touch-red.log` / `-targeted.log`。
- 无设备触摸/页面退出回归，无 Perfetto；Lint 未完成，既有构建和 Java deprecation 提示保留。OPPO 树、AGENTS.md 未修改，未提交或推送。
