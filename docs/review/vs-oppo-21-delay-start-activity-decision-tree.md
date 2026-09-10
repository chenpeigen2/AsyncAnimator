# vs-oppo-21 — delayStartActivityIfNeed 决策与挂起所有权复核

> **第 26/39 份：已完成本轮建议验收（含明确差异）。** 本份独立验收此前关联修复，继续检查 provider/Supplier 副作用与清理，不能因已有 14 条测试就沿用旧“全部 11 谓词 100%”结论。进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## 1. 范围与证据

- 只读 OPPO `D:\oppo_a6_launcher\sources\com\oplus\quickstep\utils\AnimationController.java` **当前 993 行**，598–670 为决策方法；旧“285 行文本”不是当前读取结果。搜索谓词为同文件 283–290。
- 本库 [AnimationController](../../lib/src/main/java/com/asyncanimator/control/AnimationController.kt)、[AnimationScene](../../lib/src/main/java/com/asyncanimator/control/AnimationScene.kt)、[TaskStateChangeTimeOutListener](../../lib/src/main/java/com/asyncanimator/control/TaskStateChangeTimeOutListener.kt)、[OplusAnimManager](../../lib/src/main/java/com/asyncanimator/manager/OplusAnimManager.kt)。
- [AnimationLaunchDecisionTest](../../lib/src/test/java/com/asyncanimator/control/AnimationLaunchDecisionTest.kt) 14 条历史回归保留；本轮新 [LaunchDecisionReentrancyTest](../../lib/src/test/java/com/asyncanimator/control/LaunchDecisionReentrancyTest.kt)，另核对 Regression/TimeoutOwnership/TaskStateEvent/TraceLog 测试。
- [先前决策树修复记录](2026-09-09-launch-decision-fixes.md) 保留为历史证据，不用旧行号/样本数代表本轮全部验收。

## 2. 当前决策顺序

1. 主线程检查，清旧 pending action/type 并递增决策代次。
2. 优先选择 special；只有其不存在才选 transition；两者都不存在才选 overview。高优先级分支不匹配也不能向低优先级 fall-through。
3. special 超时条件为 `uptimeMillis > deadline`：等于截止仍可挂起；已经过期则直接 false，不执行末尾整组 dispose，与 OEM 此 early return 一致。旧 pending 已在入口清除。
4. special 未过期用 `(landscape && !tablet) || split || (navLandscape && betweenAppExit)`；transition 用 `search || split || supplierResult`，Supplier 即使 search 已 true 仍只执行一次；overview 查询真实 provider 或显式 running 状态。
5. 任一外部 provider 返回后核对决策代次与原所选 listener 身份。不再接受已经 reset、被嵌套决策取代、注销或换 listener 的旧结果；返回 false 且不清掉后来的状态/动作。
6. 接受时保存 action 与所选 scenario type。普通不匹配则清两个 Between flag、dispose 三个 listener；任务事件/timeout 只消费自己的场景，并在执行 action 前清除旧持有。

## 3. 原 11 个条目逐项映射

旧“11 谓词”混入日志值、复合条件和重复因子。以下保留原编号便于核对，不再用 55%/64%/100% 混合统计冒充覆盖证明。

| 原编号 | 当前实现 / 验收 |
|---|---|
| L1.1 landscape | ✅GestureScene 的显式 landscape，只有非平板项成立才单独挂起。 |
| L1.2 split | ✅第一层独立 OR；平板不会抹掉 split 项。 |
| L1.3 navLandscape | ✅AppExitScene 推导，与 betweenAppExit 合取。未自动读取 ROM 导航服务。 |
| L1.4 betweenAppExit | ✅显式状态 setter 与 timeout/reset 清理。 |
| L1.5 !tablet | ✅注入 isTablet；默认读取传入 Context 的 sw600dp，不把它称 OEM 设备产品分类完全等价。 |
| L1.6 未超时 | ✅严格 `>` early false，边界/不下落/不 dispose 由本轮测试覆盖。 |
| L2.2 search | ✅两种 action（DOCK_SEARCH、Dispatch）或 source=drawer_search；Intent 不再是死参数。 |
| L2.3 betweenTransition | ✅**仅诊断值，不是 deferral 谓词**；本轮日志包含 between/defer，测试其 true 不单独导致挂起。 |
| L2.4 split | ✅第二层也读取；不能因为第一层统计过就认为该分支不消费。 |
| L2.5 Supplier | ✅可空函数，`call?.invoke() == true`，进入第二层时求值一次；异常正常传播。Kotlin 函数并非 Java Supplier 二进制兼容件。 |
| L3.2 running | ✅provider/显式 overviewContinuationRunning；注册 timeout 不代表正在运行，不使用时间窗代替实时查询。 |

三个 listener entry 判定、入口清旧 action、两个 Between 清理和三个 dispose 属独立结构/副作用，均在当前代码及已有所有权回归覆盖。已删除 overviewContinuationTimeOutMaxTime；不保留无消费者的伪时间字段。

## 4. 本轮新问题与实际修复

### 外部回调重入会破坏挂起所有权

旧方法在 Supplier/provider 返回后直接 defer 或清场，存在以下已复现路径：
- Supplier 注销 transition listener 后返回 true：写入没有 timeout/event owner 的 pending action，forbidTouch 永久受它阻塞。
- isTablet 内 reset 或 overview provider 内停场景：旧求值仍按已失效状态接受动作。
- Supplier 嵌套发起新决策：外层 true 覆盖新 action，外层 false dispose 新 listener/动作。
- Supplier 替换 listener：旧动作错误附着到新场景。

修复增加主线程 `startDecisionVersion`，clear/reset/新决策递增；选中 listener 用对象身份复核。无锁跨线程容器或第二份全局注册表。过期决策的 false 只表示本次 action 未被本库接管，宿主若主动重入启动新请求，需自行避免又执行被替代的旧启动。

### 决策日志

使用现有 LogUtils 门控，记录 special/transition/overview 的实际布尔输入和 defer 结果，补 between 诊断。不开日志时不构造动态消息、不额外求值 provider；不记录包名/完整 Intent，不称 Perfetto/系统 trace。

## 5. 原风险与清理结论

| 原项 | 本轮结论 |
|---|---|
| A1 运行态→时间窗 | ✅先前已修，provider/显式 running 与 timeout 分离，本轮补 provider 停场景后的旧结果丢弃。 |
| A2 搜索入口 | ✅已修，真实 Intent tests；不新增假的 IndicatorEntry 服务。 |
| A3 平板条件 | ✅已修，只约束 landscape 项，split/nav 条件保持。 |
| B1 全局 feature guard | ✔️Manager `interruptionEnabled=false` 会释放旧 Controller/Seq，后续 accessor 返回 no-op 基类。`supportInterruption()` 本库仍恒 true，不伪称真实 ROM gate 已接；直接创建/复用 Controller 是显式独立模式，不受 Manager toggle 自动禁用，不能把多加恒真 guard 当功能修复。 |
| B2 between 日志 | ✅本轮实际补，INFO 有诊断/OFF 无输出；不会添加隐藏挂起条件。 |
| B3 无单测 | ✅过时：原 14 条、Regression/场景/事件所有权回归仍保留，本轮再补 7 条。 |
| C1 DisplayController lazy | ✔️无持有/旋转查询消费者，当前 Context 配置/显式设备事实足够；不生成空 singleton。 |
| C2 provider vs singleton | ✅实际 running 查询代替时间窗；本库不移植 OEM continuation 业务 helper。 |
| D dispose/总线 | ✅已有三个 slot 是真实注册表，dispose 摘槽+撤 timer；公开 dispatchTaskStateChange 和匹配 type 事件已接线。不向不存在的全局总线假注销，也不把宿主事件桥称完整 OEM TaskStateHelper。 |

## 6. 原建议逐项处置

- 原成本表两项 P0（running 与 dispose/事件）：已有实际能力，新增重入安全；不复制 ~40 行假单例或重复 60–100 行总线。
- 三项 P1（search/tablet/tests）：保留已实现，重跑回归；不修改旧成功测试来掩盖缺口。
- 两项 P2（global guard/日志）：guard 按上表明确模式差异，不接恒真空判断；日志本轮补齐。
- P3 display lazy：无业务 consumer，不移植；依赖 sw600dp/注入事实的限制已写清。
- 6.1 四项回移（A1/A2/A3/B3）：已实际落实且验收，额外补 provider 重入后再决策的所有权检查。
- 6.2 四项保持简化：global guard 保留明确差异；日志旧“不补”撤销并实现；display 不持有；Kotlin lambda 保留但不声称 Java ABI 相同。
- 6.3/7 跨文档引用：已有独立场景 DTO、运行态输入、搜索常量和事件桥，不强制建原厂同名单例。历史修复记录与当前顺序清单分别保留，不重复计算测试。

## 7. 验证

- 新增 7 tests：旧代码 **6 条失败、截止边界 1 条通过**（`.gradle/review-ordered-26-red.log`）；失败覆盖 5 种重入及缺日志，非人为修改 native 行为制造失败。
- 修复后定向 46 tests 通过；最终 **Debug/Release 各 289 tests、41 类、0 failures/errors/skipped**，Demo Debug 成功，82/82 tasks executed、2m 13s（`.gradle/review-ordered-26-final.log`）。118 份源码/测试/两模块配置 SHA-256 前后一致。
- `git diff --check` 与相对文档链接通过。无设备/Perfetto 验证，Lint 未完成；既有告警保留，OPPO/AGENTS.md/无关文件未改，无提交/推送。
