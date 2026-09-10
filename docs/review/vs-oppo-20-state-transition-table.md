# vs-oppo-20 — Controller 十二状态、清理与启动副作用复核

> **第 25/39 份：已完成本轮建议验收（含明确差异）。** 本份以当前源码重新核对四个状态分支和一个 cleanup 入口，不沿用旧“forbidTouch 恒 false / appLaunch 列表永远空”的判断。进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## 1. 范围与证据

- 只读 OPPO `D:\oppo_a6_launcher\sources\com\oplus\quickstep\utils\AnimationController.java` 当前 993 行：enum/WhenMappings 104–176、addRecents 471–499、launch 512–555、cleanup 583–594、forbidTouch 685–687、window query 745–747、reverse 825–835。
- 本库 [AnimationController](../../lib/src/main/java/com/asyncanimator/control/AnimationController.kt)、[AnimationState](../../lib/src/main/java/com/asyncanimator/control/AnimationState.kt)、[DefaultAnimationController](../../lib/src/main/java/com/asyncanimator/control/DefaultAnimationController.kt)、[AnimationSeqHelper](../../lib/src/main/java/com/asyncanimator/seq/AnimationSeqHelper.kt)。
- 原 [AnimationControllerRegressionTest](../../lib/src/test/java/com/asyncanimator/control/AnimationControllerRegressionTest.kt) 四个 12 状态矩阵保留。本轮新增 [ControllerStateMatrixTest](../../lib/src/test/java/com/asyncanimator/control/ControllerStateMatrixTest.kt)，补 cleanup/非手势 end、枚举角色与启动副作用；输入中未被生产入口生成的状态只在测试反射注入，再调用真实公共方法。

## 2. 十二状态转移表

`end（手势中）` 列要求本次结束后 launch 集合为空且 onceGestureProcessing=true；否则不能套用该列。`保持` 表示不发起新状态转换，不等于 UNKNOWN。

| 输入 | addRecents | launch start | end（手势中） | reverseRecents |
|---|---|---|---|---|
| NONE | CLOSE | OPEN | 保持 | UNKNOWN |
| OPEN | CLOSE | UNKNOWN | WAITING | UNKNOWN |
| REVERSE_OPEN | CLOSE | UNKNOWN | 保持 | UNKNOWN |
| CLOSE | UNKNOWN | MULTI_OPEN | 保持 | REVERSE_OPEN |
| MULTI_OPEN | MULTI_CLOSE | UNKNOWN | MULTI_WAITING | UNKNOWN |
| MULTI_REVERSE_OPEN | MULTI_CLOSE | UNKNOWN | 保持 | UNKNOWN |
| MULTI_CLOSE | UNKNOWN | MULTI_OPEN | 保持 | MULTI_REVERSE_OPEN |
| WAITING | CLOSE | UNKNOWN | 保持 | UNKNOWN |
| MULTI_WAITING | MULTI_CLOSE | UNKNOWN | 保持 | UNKNOWN |
| UNKNOWN | UNKNOWN | UNKNOWN | 保持 | UNKNOWN |
| SWIPE_UP_TO_CAPSULE | UNKNOWN | UNKNOWN | 保持 | UNKNOWN |
| SWIPE_UP_TO_SPLIT_OR_FLOATING | UNKNOWN | UNKNOWN | 保持 | UNKNOWN |

- 非手势 end：剩余 launch 非空则保持；launch 空但 recents 非空则等待；两集合都空才完成并归 NONE。新增完整 12 输入测试覆盖最后一种情况，原双集合测试覆盖等待。
- cleanup：总会清 recents/removeTasksMaps 和 onceGestureProcessing；若仍有 launch 返回 false、保持输入状态和完成回调。没有 launch 则执行完成检查、归 NONE、返回 true。两种条件各新增 12 输入测试。
- 枚举顺序与两个 taskbar 标志逐项对照。CAPSULE/SPLIT_OR_FLOATING 在当前 Controller 没有生产状态写入入口，但仍保留 fallback；不从单文件推断这些值全树永远不可能成为输入。
- 四个旧矩阵 + 三个新矩阵共 **84 个状态输入组合**，属于 7 个 JUnit 方法，不额外重复计算测试数。枚举角色和 window-query 矩阵另属各自测试。

## 3. 本轮实际修复

1. **新 Recents 清旧完成请求**：addRecentsAnim 先加入句柄，再清 recentsAnimFinishCallback，随后才发状态通知，对应 OEM 479–481。否则上一轮 callback 会随下一轮收尾误触发。状态观察者可以安全注册新 callback；宿主需要在 add 之后注册当前轮完成动作。
2. **新 launch 撤旧 Seq 延迟收尾**：start 分支在输入 gate/状态通知前调用现有 Manager Seq helper.clearFinishRecentsRunnable，对应 OEM 540。真实排队测试证明旧实现会在新 OPEN 期间交付旧收尾，修复后取消旧请求而不调用 action。
3. **isAppWindowAnimRunning 补 override**：按 OEM 定义为 state 既非 NONE 也非 UNKNOWN。它是状态分类，不是 600ms 输入闸门，也不是物理帧查询；即使触摸 timer 到期，OPEN 仍为 true。

已有 600ms touch gate、重启替换截止、end/reset/destroy 清除及 Demo6/9 输入消费继续保留。end 在本库主线程立即释放 gate，不复制 OEM sendEmptyMessage(101) 的下一消息时序；该差异防止旧消息迟到清掉新轮 gate，已有回归覆盖。

## 4. 原成员、简化与遗漏处置

| 原组 | 当前结论 |
|---|---|
| 成员 enum / add / start / end / reverse / cleanup / callback / updateState | 均有实际实现；表中分支对照，callback 完成前消费/reset 的重入防护保留，不等于 OEM 全部副作用已实现。 |
| 简化①触摸 flag | ✅之前已补，不再保持恒 false；本轮补另一条 window state query。 |
| 简化②merge helpers | ✔️未接系统多 app merge，不用本地列表冒充窗口 merge 状态。 |
| 简化③remote target/running task | ✔️本地 Any 描述，当前 target 字段未消费；typed platform adapter 仍未移植。 |
| 简化④AnimationSuccessListener | ✔️系统 removeTask/remote finish/Seq 监听链未接，不能说业务 AsyncAnimCallbacks 自动替代这些语义。当前宿主显式登记结束/cleanup。 |
| 简化⑤canFinish 恒 true | ✅过时：实际 Controller 有 launch/Seq/logical-only/数量/ID 门控；基类仅 feature-off fallback。 |
| 简化⑥完成线程 | ✔️本地两个 callback 在主线程完成，先 reset 旧轮、再交付捕获回调；不移植 OEM UI_HELPER 异步远程 merge 收尾。异常/重入规则见 USAGE。 |
| 简化⑦多源设备谓词 | ✅AppExitScene/GestureScene 显式快照和 timeout 已实现；自动 ROM/nav/display 查询未接，不再说 setOnAppExit 无条件置 true。 |
| 遗漏①reverse / ②cleanup check | ✅既有修复仍有效；不重复回退或改其正确 switch。 |
| 遗漏③release-touch | ✅已有本地主线程即时释放，明确与 OEM Handler 消息时序差异。 |
| 遗漏④系统成功监听 | ✔️不造 removeTask/recents controller 空实现；上述本地 stale callback 清理是独立且已落实的副作用。 |
| 遗漏⑤RemoteAnimInterrupter reset | ✔️本库没有该系统中断器，不向虚构 singleton 发调用。 |
| 遗漏⑥reset 字段 | ✔️currentAnim/clickAppView 无实际持有；swipingUpActivityPkg 现在确有字段，reset/destroy 会清，旧“全都不存在”撤销。 |

## 5. 原风险与建议逐项验收

- 风险 #1 reverse、#2 cleanup：已实现并有全状态/双集合/重入测试。
- 风险 #3 touch：已有 600ms gate 和全部条件，不把 timeout 视为一定准时发生的物理完成。
- 风险 #4 window 查询：本轮实际补齐，测试先在 OPEN 期望 true 处失败；与 touch gate 分离。
- 风险 #5 外部 taskbar 状态：保留两枚举、角色和 fallback 测试，不声称已实现胶囊/分屏系统业务。
- 原 P0 两建议（cleanup/reverse）、P1 触摸、P2 end 释放：均已落实并验证，P2 主线程即时释放的差异明确保留。
- 原六个“不补”项：merge helpers、系统 success listener、typed remote task map、自动显示/导航守卫、远程异步 finish executor 仍不移植；reset 中实际已持有的包名则必须清理，现有代码已满足。
- 补入旧表列出但未落地的 stale recents callback 清理和 Seq 延迟取消，不能只因四个 switch 已通过就把整份副作用审查标为完成。

## 6. 验证

- 新增 7 tests；首批 6 条中旧代码 2 条失败（旧 callback、排队 Seq），另 window query 定向 1 条失败。日志 `.gradle/review-ordered-25-red.log` / `.gradle/review-ordered-25-window-red.log`。
- 修复后定向 43 tests 通过；最终 **Debug/Release 各 282 tests、40 类、0 failures/errors/skipped**，Demo Debug 成功，82/82 tasks executed、2m 14s（`.gradle/review-ordered-25-final.log`）。117 份源码/测试/两模块配置 SHA-256 前后一致。
- `git diff --check` 与相对文档链接通过。设备/Perfetto 未验证，Lint 未完成；既有告警保留，OPPO/AGENTS.md/无关文件未改，无提交/推送。
