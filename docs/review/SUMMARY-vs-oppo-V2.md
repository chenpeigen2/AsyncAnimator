# lib vs OPPO 对比汇总：深挖专项 13–34

> **按文件顺序执行**：旧版 01–04、06 与详细版 01–34 已按顺序验收，共 **39/39 份**，含明确保留简化项；本轮顺序队列已完成。进度、证据和验证以[顺序执行清单](2026-09-09-ordered-review-progress.md)为准。

## 当前验收口径

- “完成”指建议已实现并验证，或逐项明确拒绝/保留的理由，不等于 OEM 完整移植或设备性能对齐。
- 当前库 44 类/334 独立用例；Demo 另 1 类/6 用例，合计 45 类/340 用例。Debug/Release 均通过，不按变体翻倍。源码/构建验证见顺序记录，最新结果也见 [README](../../README.md)。
- Trace 是 stderr 诊断；本地四轨/六轴及公开 Choreographer 不等于系统 Surface/SF-VSYNC。无 Binder 远程窗口入口、ROM UX/UAF、系统 merge/按键权限集成。
- Lint 依赖缺失、干净 SDK 复现、设备/Perfetto/返回手势回归尚未完成，不用旧的“保真度78%”“所有bug已修”替代证据。详见[构建边界](../build-environment.md)。
- 历史 dated 修复记录仅保留事件证据，不再把当时缺口、成本估算或测试数作为当前清单。

## 专项索引

| 编号 | 文档 | 当前状态 |
|---|---|---|
| 13 | [vs-oppo-13 — AnimationControlThread 初始化复核](vs-oppo-13-anim-thread-init.md) | 已验收；保留项见正文 |
| 14 | [vs-oppo-14 — AnimationHandler 帧调度内核复核](vs-oppo-14-animation-handler-kernel.md) | 已验收；保留项见正文 |
| 15 | [vs-oppo-15 — TickScheduler 迁移与 Choreographer 实现复核](vs-oppo-15-tickscheduler-dual.md) | 已验收；保留项见正文 |
| 16 | [vs-oppo-16 — CustomRectFSpringAnim 六轴物理与生命周期复核](vs-oppo-16-customrect-spring.md) | 已验收；保留项见正文 |
| 17 | [vs-oppo-17 — MultiAnimatorSet 四通道聚合复核](vs-oppo-17-multianimset.md) | 已验收；保留项见正文 |
| 18 | [vs-oppo-18 — 多弹簧帧循环、阈值与结束协议复核](vs-oppo-18-frame-callback-multispring.md) | 已验收；保留项见正文 |
| 19 | [vs-oppo-19 — LauncherAnimationRunner 系统边界与工厂契约复核](vs-oppo-19-launcher-animation-runner.md) | 已验收；保留项见正文 |
| 20 | [vs-oppo-20 — Controller 十二状态、清理与启动副作用复核](vs-oppo-20-state-transition-table.md) | 已验收；保留项见正文 |
| 21 | [vs-oppo-21 — delayStartActivityIfNeed 决策与挂起所有权复核](vs-oppo-21-delay-start-activity-decision-tree.md) | 已验收；保留项见正文 |
| 22 | [vs-oppo-22 — TaskState 超时、事件桥与释放复核](vs-oppo-22-task-state-helper.md) | 已验收；保留项见正文 |
| 23 | [vs-oppo-23 — PendingAnimation 组装与播放端到端复核](vs-oppo-23-pending-e2e-flow.md) | 已验收；保留项见正文 |
| 24 | [vs-oppo-24 — AnimatorPlaybackController 派发与收尾契约复核](vs-oppo-24-apc-dispatch-contract.md) | 已验收；保留项见正文 |
| 25 | [vs-oppo-25 — AnimationSeqHelper 序号配对与时间窗复核](vs-oppo-25-seq-helper-pair.md) | 已验收；保留项见正文 |
| 26 | [vs-oppo-26 — AnimSeqTimeStamp 时间原点、时钟与并发边界复核](vs-oppo-26-anim-seq-timestamp.md) | 已验收；保留项见正文 |
| 27 | [vs-oppo-27 — AnimationFeatureHelper 配置发布、策略与生命周期复核](vs-oppo-27-feature-helper.md) | 已验收；保留项见正文 |
| 28 | [vs-oppo-28 — OplusAnimManager 工厂、清理与系统 helper 边界复核](vs-oppo-28-oplusanim-manager-helpers.md) | 已验收；保留项见正文 |
| 29 | [区域 29 对比 Review：Trace 标签一致性（本轮验收）](vs-oppo-29-trace-tag-consistency.md) | 已验收；保留项见正文 |
| 30 | [区域 30 对比 Review：Kotlin 化兼容性风险](vs-oppo-30-kotlinization-risks.md) | 已验收；保留项见正文 |
| 31 | [区域 31 对比 Review：构建配置与原厂差异](vs-oppo-31-build-gradle-drift.md) | 已验收；保留项见正文 |
| 32 | [区域 32 对比 Review：Demo 入口、导出与日志生命周期](vs-oppo-32-demo-manifest-export.md) | 已验收；保留项见正文 |
| 33 | [vs-oppo-16 — 文档 / 代码一致性扫描（USAGE.md + README.md + docs/review/*）](vs-oppo-33-doc-consistency.md) | 已验收；保留项见正文 |
| 34 | [lib 测试覆盖缺口扫描 — vs OPPO 原厂（34/测试覆盖）](vs-oppo-34-test-coverage-gaps.md) | 已验收；保留项见正文 |

另见[另一组专项汇总](SUMMARY-vs-oppo.md)和[使用指南](../USAGE.md)。
