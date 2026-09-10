# vs-oppo-28 — OplusAnimManager 工厂、清理与系统 helper 边界复核

> **第 33/39 份：✅完成（含明确系统边界）。** 逐项核对工厂两项风险、helper 十八项风险、十七项建议与八项简化。不因原厂有一个方法，就在本库创建返回成功的空系统实现。进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## 1. 实际工厂与原厂证据

本地 `lib/src/main/java/com/asyncanimator/manager/OplusAnimManager.kt` 管理 Controller/Seq 两个实现，并暴露共享 FeatureHelper。首次 object 初始化直接创建两实现，**不是默认 no-op，也不是逐 helper by lazy**；本轮修正了源码 KDoc/注释。

OPPO 只读根 `D:/oppo_a6_launcher/sources/`：`com/oplus/quickstep/utils/OplusAnimManager.java` 中 static 初始化/create 工厂、171-180 清理、198-224 ID/recreate/reset、230-269 两种 support 和 remote finish；并读取 `t4/a.java` 与 `t4/b.java` 确认：a 保存 value 并调用 beforeChange/afterChange，b 是 getValue/setValue 接口，**不是 lazy 类型**。原厂 DefaultAnimationController 也是普通 public class，不是旧文声称的 abstract+factory。

| 工厂/相关 helper | 本地处理 |
|---|---|
| AnimationController | 有真实状态机/完成/触摸/挂起启动/timeout 所有权，已有逐份测试；不是整个 OEM 远程转场实现。 |
| AnimationSeqHelper | 有配对、双时间窗、注入 gate、可重入延迟队列与清理；Default 仍立即执行 delay action。 |
| AppOpenAnimMergeHelper | 不创建不存在的远程 merge 会话。无 shell targets/controller/完成信号所有权，返回 false 或立即 finish 的桩可能提前结束宿主转场。 |
| MultiAppAnimMergeHelper | 不用 CAS 计数器假装已接多窗口 task/target 合并；本地 MultiAnimatorSet 聚合的是动画轨道，不是系统任务合并器。 |
| InterceptKeyEventHelper | 不调用权限受限的 InputManager 注入或尝试假造 OplusWindowManager；Demo 返回键由 Activity 生命周期处理，不声称系统按键拦截成功。 |
| MultiOpenPreStartHelper | 不为没有生产者的 Surface/Task callback 创建阻塞 Condition 和空事务；没有本地 pre-start 调用链，不存在该缺失类引起的本地 NPE。 |
| AppSwipeToRecentContinuationHelper（不属上述六工厂） | 不移植五条 OEM RecentsView 动画；所需“是否运行”已由 Controller 显式运行态/provider 提供，不再用 100ms 时间窗替代。 |

**3.1-2**：全树本地源码没有这四个系统工厂或远程 finish 的调用链。旧报告拿 OEM 调用方的 NPE 当成本地已可触发崩溃，证据不成立；这些是系统移植缺口，不作为已实现能力。

## 2. 初始化、开关与清理契约

- **3.1-1 / 建议1**：object 初始化不是两个并发首访各造一份的普通 if-null。新增独立 Robolectric sandbox 的八 worker 首访，全部拿到同一 Controller 和 Seq。检索对象不等于可以在 worker 调 Controller 主线程方法；此轮不添加重复 lazy/锁修复。
- **工厂开关 / 建议7/9**：supportInterruption() 固定 true 是能力占位；interruptionEnabled 才是本地开关。关闭先断开两旧引用，再 destroy Controller，finally 清旧 Seq 排队动作；再次启用创建新 owner，重复同值不替换已有实例。
- 旧已排队的 timeout/launch/Seq work 会被释放；这不是永久撤销所有外部持有引用，也不是全进程禁止用户自行 new Controller。
- 新查询的 no-op fallback 不提供稳定对象身份保证。Default Seq 的 delay 方法立即调用 action 并返回 false；不开配对/不写 Bundle，但保留其原有内容。新增测试验证，不称“全部方法空操作”。
- 两个字段分别 volatile，切换受主线程约束；没有提供跨 getter 的原子多 helper snapshot，不能凭某次读到 enabled 就保证另一个线程未来的查询仍属于同一代。
- FeatureHelper 是另一生命周期的共享数据/订阅源；本地字段下发不重建 Manager，关闭工厂不顺带全局注销页面配置监听。新增测试验证配置下发不会替换两个 owner。
- 本地 cleanUpRecentsAnimation 委派实际 Controller 清 Recents 并保留工厂对象，新增 once-only 完成回归；它不是 destroy，也不凭不存在的 merge helper 返回值声称清理系统 Surface。

## 3. 十八个 helper 风险逐项验收

| 原 3.2 编号 | 当前结论 |
|---|---|
| 1/2 | ✔️两个系统 merge helper 按上表保留边界；不添加存 Any target 却无真实完成来源的伪会话。 |
| 3 | ✅overview 显式运行态/provider 已接决策第三层，不补重复全局可变 running 桩；与[决策专项](vs-oppo-21-delay-start-activity-decision-tree.md)一致。 |
| 4/5 | ✔️系统按键拦截/pre-start 不接，不能把缺类等同本地正在抛 NPE。 |
| 6 | ✅主线程 600ms touch gate 已接，连同状态/挂起 action 查询和 Demo9；原建议 urgent Handler 的线程证据错误。 |
| 7 | ✅Feature 的 -1、Adaptive/radius、完整配置、订阅和 snapshot 已验收，见[上一份](vs-oppo-27-feature-helper.md)。 |
| 8 | ✅同/equal controller 保留 ID，变化时分配；序号两写入口现也受 interruption provider 门控。 |
| 9 | ✅非法 addRecents 状态的门控日志已有，不再标省略。 |
| 10 | ✔️Task timeout 保留主 Looper，匹配 Controller/View owner；不因原厂 urgent executor 就把主线程 API 回调搬到后台。 |
| 11 | ✅本地三槽事件桥和 Demo6 已实现；✔️未接 Binder/global task 总线，见[TaskState 专项](vs-oppo-22-task-state-helper.md)。 |
| 12/13 | ✅平板 landscape 限定、搜索特殊场景和 provider/决策重入保护已有，不补假查询。 |
| 14 | ✔️本地 toggle 提供两 owner 重建/释放；它**不等价** OEM recreate 四 helper，不添加重复同名伪 API。 |
| 15 | ✔️Controller reset 和 Manager 生命周期已有；不补缺三种系统 owner 的复合 reset。 |
| 16 | ✅唯一实际消费处 canFinishRecentsAnim 已支持 -1 宽松匹配与真实 ID 比较；无跨 owner ID 服务，不额外暴露 Manager.matchAnimationId。 |
| 17 | ✔️无 ItemInfo/zoom package/组合分屏集成，不把Any 参数和恒布尔当真实启动资格判定。 |
| 18 | ✅本地 Recents 委派清理有新回归；✔️不清不存在的 merge 存储，不承诺系统三 helper 清理完整性。 |

## 4. 十七项建议逐项处置

1. 不重复改 lazy；改正 init/KDoc/委托类型并补真实冷启动并发测试。
2. 使用已有 Controller 运行态/provider，不补全局 continuation 单例。
3. AppOpen merge 建议的八方法桩不添加：缺目标/完成/取消 owner，会产生虚假能力。
4. MultiApp merge 建议的六方法计数器不添加：轨道聚合已有，系统 task 合并未接。
5. 不注入系统按键，不用反射异常掩盖不支持；Demo 自身返回路径保留。
6. 不添加十三个 pre-start 方法、无生产者 Condition 或空 SurfaceControl.Transaction；不默认 supportPreStart=true。
7. 保留本地 toggle/Controller reset/实际 ID 消费路径；明确不是 OEM 三个全局方法的等价替代。
8. 不实现 tryFinishOpenRemote 的立即成功桩；实际宿主 completion 的一次性所有权由已有 Controller/Launcher runner 契约承担。
9. 无 ItemInfo 入口不添加伪 overload，系统启动资格仍由宿主负责。
10. 600ms 主线程 gate 已有，保持 owner 和 end/reset/destroy 清理。
11. 新测试核对当前 Recents 清理委派与 once-only callback；不存在的系统 helper 不作为清理对象。
12. 默认值/Adaptive/radius 已实现，按上一份保持数据与系统能力边界。
13. Seq 配对/gate/重入已实现，既有测试保留。
14. 错误状态日志已有，仍受 LogUtils 门控。
15. 平板限定已实现，不再标缺失。
16. 搜索场景分支已实现，不再标缺失。
17. timeout 仍在主线程：可能受主线程负载影响，未做设备延迟/Perfetto 验证，不虚报后台更早触发。

## 5. 八项简化与历史汇总纠正

1. 没有本地 DefalutInterceptKeyEventHelper 类，不存在必须保留拼写以兼容本地 import 的理由；不新建它。
2. 不移植 JADX 标记不完整的远程 merge 主体，也不因此新增假 false 接口。
3. continuation 所需状态已有入口，不把未移植其余动画称为已完成物理续接。
4. 不伪造 pre-start 事务与 SystemUiProxy 回调。
5. 本地无 OplusWindowManager 反射 try-catch；旧“已兜底 NoClassDefFoundError”不是现有代码事实。
6. OEM 三条件能力表达式保持系统边界，本地恒 true 与工厂开关区分。
7. Default 类保留普通继承结构；原厂也非旧报告所说的 abstract 工厂模式，不以语言名推导设计模式。
8. 本地没有 pre-start 工厂，不能称其第二层 gate 恒 true 已实现。

原“六 helper 已等价”“五个缺失工厂导致本地 NPE”“observable 就是 lazy”“recreate 重建全部六个”均撤销。OEM recreate 实际更新 AppOpen/MultiApp/Controller/Seq 四个，不包含按键/pre-start；本地只有两个被管理实现，Feature 共享对象另列。

## 6. 验证记录

- 新增 ManagerBootstrapTest 1 条（八 worker 包含在该单测内），ManagerLifecycleTest 新增 3 条（4→7）：配置不重建工厂、Recents 委派清理、no-op 序号/立即 action 契约。
- 这是对真实行为和旧误报的验证，不宣称四条测试对应四个新修复产品 bug；本轮生产代码仅修正 Manager KDoc/注释。
- 定向 manager / ControllerStateMatrix / RemoteAnimationBoundary **31 tests 通过**（`.gradle/review-ordered-33-audit.log`）；完整 **Debug/Release 各 328 tests，44 类，0 failures/errors/skipped**；Demo Debug 成功，82/82 tasks executed，3m 11s（`.gradle/review-ordered-33-final.log`）。121 份源码/测试/两模块配置 SHA-256 前后一致。
- README 测试表、Markdown 相对链接、`git diff --check` 与 AGENTS.md 未改检查通过。无设备/Perfetto 验证，Lint 未完成；既有构建警告保留，无提交/推送。
