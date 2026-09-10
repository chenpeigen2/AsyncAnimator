# lib vs OPPO 对比汇总：基础专项 01–12

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
| 01 | [vs OPPO — 区域 01：异步/线程层（lib vs ColorOS 15 Launcher 15.8.24）](vs-oppo-01-async-thread.md) | 已验收；保留项见正文 |
| 02 | [vs-oppo-02-pending-playback — Pending / Playback 层 lib vs OPPO 对比](vs-oppo-02-pending-playback.md) | 已验收；保留项见正文 |
| 03 | [区域 03 对比 Review：Controller / Manager / Seq / Feature / Runner 层](vs-oppo-03-controller.md) | 已验收；保留项见正文 |
| 04 | [区域 04 重对比 Review：帧调度层（AnimationHandler + TickScheduler）](vs-oppo-04-frame-scheduling.md) | 已验收；保留项见正文 |
| 05 | [区域 5 对比 Review：续行动画层（continuation / spring 接力）](vs-oppo-05-continuation-spring.md) | 已验收；保留项见正文 |
| 06 | [区域 06 vs-oppo public API 与调用面](vs-oppo-06-public-api.md) | 已验收；保留项见正文 |
| 07 | [区域 07 对比 Review：并发原语与线程安全](vs-oppo-07-concurrency.md) | 已验收；保留项见正文 |
| 08 | [区域 08 对比 Review：日志 / Tracing / 可观测性](vs-oppo-08-trace-observability.md) | 已验收；保留项见正文 |
| 09 | [区域 09 对比 Review：生命周期与资源回收](vs-oppo-09-lifecycle-cleanup.md) | 已验收；保留项见正文 |
| 10 | [区域 10 对比 Review：API 风格与 Kotlin 化程度](vs-oppo-10-kotlin-idiomaticity.md) | 已验收；保留项见正文 |
| 11 | [区域 11 对比 Review：功能缺口与可移植边界](vs-oppo-11-feature-gaps.md) | 已验收；保留项见正文 |
| 12 | [区域 12 对比 Review：语义差异与运行时风险](vs-oppo-12-runtime-risks.md) | 已验收；保留项见正文 |

另见[另一组专项汇总](SUMMARY-vs-oppo-V2.md)和[使用指南](../USAGE.md)。

## 旧版五份的使用方式

- [01-async-animthread.md](01-async-animthread.md)：已顺序验收，保留历史正文；当前实现以对应 vs-oppo 专项及顶部验收结论为准。
- [02-pending-playback.md](02-pending-playback.md)：已顺序验收，保留历史正文；当前实现以对应 vs-oppo 专项及顶部验收结论为准。
- [03-controller-manager-seq.md](03-controller-manager-seq.md)：已顺序验收，保留历史正文；当前实现以对应 vs-oppo 专项及顶部验收结论为准。
- [04-frame-spring-continuation.md](04-frame-spring-continuation.md)：已顺序验收，保留历史正文；当前实现以对应 vs-oppo 专项及顶部验收结论为准。
- [06-vs-oppo-public-api-and-callsites.md](06-vs-oppo-public-api-and-callsites.md)：已顺序验收，保留历史正文；当前实现以对应 vs-oppo 专项及顶部验收结论为准。
