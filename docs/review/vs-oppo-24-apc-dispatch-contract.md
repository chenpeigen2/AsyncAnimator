# vs-oppo-24 — AnimatorPlaybackController 派发与收尾契约复核

> **第 29/39 份：✅完成（含明确保留边界）。** 本轮对照实际源码、回调及测试，撤销旧报告“已全部等价”“只能一次性使用”“嵌套无触发面”的推断。进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## 1. 源码依据与对象关系

- 本地 `lib/src/main/java/com/asyncanimator/playback/AnimatorPlaybackController.kt`、`AnimationSuccessListener.kt`；测试位于相同包路径。
- OPPO 只读根 `D:/oppo_a6_launcher/sources/`，`com/android/launcher3/anim/AnimatorPlaybackController.java`：Holder 38-61；成功/取消 dispatcher 63-100；主时钟与根监听 128-158；递归 Holder/监听 160-203；派发/force finish 230-267；pause 315-320；reverse/start 348-380。
- 主 `animationPlayer` 驱动 Holder；`rootAnim` 保存实际目标树。成功 dispatcher 挂**主播放器**，目标取消状态监听挂**根**。两者不是同一个时钟，也不是同一取消标志。
- `dispatchOnStart/End/Cancel` 是手动调用目标树监听，不自动启动/停止主播放器或每个子动画。先序 DFS 与每个节点的监听快照此前已实现，保留现状。

## 2. 本轮修复

### 未运行的 forceFinishIfCloseToEnd 不应强制 end

原厂条件是 `!isRunning || animatedFraction <= 0.95f` 则返回。本地旧代码误写成 `isRunning && fraction <= 0.95f`，导致尚未启动或已暂停的播放器也调用 end，提前执行成功 action。

现只对**正在运行且主播放器 animatedFraction 严格大于 0.95** 的动画 end。95% 等号不结束，未启动/暂停/结束后无副作用；`forceFinishIfNeed` 仍只要求正在运行。判断的是主播放器播放比例，不是目标的正向 progressFraction。

### 成功回调先占用一次性闸门并取出 action 快照

旧实现与 OEM 一样在外部回调全部完成后才置 `dispatched=true`、清空 map；这并不能保证重入安全。本轮复现了根 end 再次派发、遍历中修改 map 抛 ConcurrentModificationException、回调启动下一轮后新 action 被旧清理抹掉、异常后旧动作重放。

新顺序为：检查闸门 → **置已派发** → action 快照并清旧 map → 目标树 end → 快照动作。

- 同轮重复/重入 success 不再重复派根或动作。
- 根监听/动作中新注册的项留给下一次成功运行，不加入已领取快照；同 key 更新不改变本轮已领取的动作。
- 回调启动下一轮会由 onAnimationStart 重置闸门；旧轮末尾不再覆盖新状态或清新 map。
- 异常仍原样、fail-fast 向上抛；旧快照已经消费，未执行的剩余项不会重试。未来注册保留。这里不声称所有监听在异常时仍能收到通知，也不吞异常。

这是有回归证明的本地可靠性增强，不虚报 OEM 自带快照或完整异常隔离。

### 零时长 Holder 不再产生 NaN

PendingAnimation 允许非正总时长归零；旧 Holder 的 `0/0` 和 mapper 的 `0/0` 会把 NaN 送给平台属性动画。现 totalDuration <= 0 映射相对终点 0；相对终点 <= 0 时直接使用完成比例 1。零时长子动画、零总时长集合在首次 `setPlayFraction(0)` 即写到终值。

普通正时长 mapper 不变；用户自定义 mapper 或 NaN 输入不在本项“零时长已处理”的保证范围。此项超出 OEM 原始除法行为，明确保留为本地边界修复。

## 3. 暂停、取消、重启的真实契约

| 操作 | 主播放器 cancelAction | 目标树监听 | endActions |
|---|---|---|---|
| 运行中 pause() | 调用 | 不自动派 cancel | 保留，后续成功可消费 |
| 主播放器成功 end | 不调用 | 先序派 end | 消费当轮快照 |
| dispatchOnCancel() | 不调用 | 先序派 cancel，阻止之后属性 seek 写入 | 不自动清空 |
| 直接 root.cancel() | 不等于取消主播放器 | 由根自身实际运行状态决定平台回调 | 不保证之后永远不执行 |
| 再次 start/reverse | 不因启动自动调用 | 不自动替宿主派 root start | 主监听清 cancelled/dispatched，可再次成功 |

**不在 pause 中清 endActions。** pause 本身使用 cancel 实现，再 start/reverse 是真实可达路径；把所有 pause 当作永久销毁会丢失预期完成动作。原报告没有证据证明 Controller 一次性使用，已撤销。宿主如果永久放弃，需明确清除 endActions/cancelAction 等捕获引用；如果曾手动派 root cancel，恢复属性更新也要配对目标树 start，而不是只重启时钟。

## 4. 原保真度条目逐项复核

| 原项 | 当前结论 |
|---|---|
| A1/A2 | LINEAR 主 ValueAnimator；成功 dispatcher 挂主播放器，保留。 |
| A3/A4/A6 | 主 start 重置 cancelled/dispatched；主 cancel 先设 cancelled 再执行 cancelAction；cancel 后 end 不走 success。 |
| A5 | 本轮改为回调前闸门/快照消费；旧“末尾设标志天然幂等”不成立。 |
| A7 | 三种 dispatch 返回本 Controller，递归包含根/内层集合/叶子。 |
| A8 | 修正漏掉的 !isRunning；严格 >95% 保留。 |
| A9 | ValueAnimator 收 Holder、Set 递归、其他显式 RuntimeException；上一份已补父级设置传播。 |
| A10/A11/A12 | pause 先 reset Holder 再取消时钟；cancelAction 与成功 endActions 的入口分别保留，新增真实运行/暂停/重启测试。 |
| B1/B2/B3 | Map **有同 key 替换/去重语义**，不是“无去重”；不复制 hashCode 字符串默认 key。Kotlin 属性保留，执行改为值快照。 |
| B4 | 当前持有 rootAnim，不再丢根，也不用强制 cast 为 AnimatorSet 缩窄可支持类型。 |
| B5/B6/B7 | 普通高阶接收者函数 + DFS + listener.toList 快照；现在不是 inline，也不是只有 orEmpty 一层遍历。 |
| B8 | animationPlayer 为非空 val，不加无法触发的 null 判断；forceFinishIfNeed 保留运行守护。 |
| B9 | SpringProperty/startWithVelocity 未接入本 APC，null 占位不代表实现弹簧接续。 |
| B10 | pending 字段初始 false，dispatchStart 后 true，start/reverse/根三触点均 false；新增白盒边界测试，不为测试新增公开 getter。 |

## 5. 原 Bug 与建议验收

| 原编号 | 处置 |
|---|---|
| Bug-1 | ✅start/reverse 与根三触点此前已正确，新增回归保留五个 false 触点；不重复声称本轮才修。 |
| Bug-2 | ✔️取消保留 endActions 是可重启语义；保留行为、纠正“一次性/GC 必然回收”的无证据结论。本轮另修成功时重入/异常消费。 |
| Bug-3 | ✅根 listener 已正确，空集合不访问 anims[0]；不依旧报告再搬一次。 |
| Bug-4 | ✅真正 DFS 和监听自注销快照已实现，两条原有测试保留。 |
| Bug-5 | ✔️成功 listener 挂主时钟本来就正确，不搬到根。 |
| 4.1-1/2/3 | ✅保留 pending/根监听/DFS 既有修复，并增加本轮收尾回归。 |
| 4.1-4 | ✔️progressFraction 已有属性；pending getter 无消费者，不增加无用 API。 |
| 4.1-5 | ✔️不加不可能为 null 的判断；真正有差异的是另一个 forceClose 逻辑，已修。 |
| 4.2-1/2 | ✔️不移植 OEM SpringAnimationBuilder/网格 Recents 业务；独立 RectSpringDriver 不等于 APC 已支持 startWithVelocity。 |
| 4.2-3 | ✔️不补无调用方递归设置/遍历/durationScale API；配置应在 Holder 快照前完成。 |
| 4.2-4 | ✔️不机械补全 getter；progressFraction/animationPlayer 可读，**duration 仍 private**，旧“核心字段全已公开”不准确。 |
| 4.2-5 | ✔️保留 pause 后的 action，不套用销毁清理；相应测试断言保留/重启，而非强迫清空。 |
| 4.2-6 | ✔️保留高阶函数和快照表达；不把“inline”当语义或性能证明。 |

C 类列出的速度弹簧、业务筛选、额外遍历/设置/getter 均按上述明确保留。旧跨 review 关联与调用方数量不替代本地可达性证据，不声称支持 startDelay/顺序集合时间轴或完整系统 Launcher 转场。

## 6. 验证记录

- 新增 PlaybackCompletionTest **9 条**：旧实现 6 失败、3 通过（`.gradle/review-ordered-29-red.log`）。覆盖空闲 forceClose、95% 严格边界、暂停重启、action 快照修改、重入重复、回调启动下一轮、异常消费、零时长属性值、五个 pending 清除触点。
- 重复/重入完成测试直接触发实际注册的播放器 listener，避免平台 end 自带防重掩盖 Controller 问题；forceClose/暂停/重启测试实际调用 ValueAnimator 生命周期。
- 定向 playback + OplusValueAnimator 共 **41 条通过**（`.gradle/review-ordered-29-green.log`）；完整 **Debug/Release 各 311 tests、42 类，0 failures/errors/skipped**；Demo Debug 成功，82/82 tasks executed，2m 10s（`.gradle/review-ordered-29-final.log`）。119 份源码/测试/两模块配置 SHA-256 前后一致。
- README 测试表、Markdown 相对链接、`git diff --check` 及 AGENTS.md 未改检查通过。无设备/Perfetto 验证，Lint 未完成；既有构建警告保留，无提交/推送。
