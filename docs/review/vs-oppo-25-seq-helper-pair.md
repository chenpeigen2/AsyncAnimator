# vs-oppo-25 — AnimationSeqHelper 序号配对与时间窗复核

> **第 30/39 份：✅完成（含明确保留边界）。** 对照 OPPO 只读源码处理每项建议；不把旧状态或“默认恒 true”当成所有注入路径已正确。进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## 1. 范围与证据

- 本地 `lib/src/main/java/com/asyncanimator/seq/AnimationSeqHelper.kt`、`DefaultAnimationSeqHelper.kt`；不是原报告的 `launcher/seq` 路径。
- OPPO 根 `D:/oppo_a6_launcher/sources/` 下 `com/oplus/quickstep/utils/AnimationSeqHelper.java`：55-66 为 Bundle 写入门控，69-76 为两时间窗，78-100 为延迟/清理，103-129 为配对读取/重置/更新。另核对同目录 DefaultAnimationSeqHelper。
- `seqId` 属于**单个 helper 实例**；Bundle 写入与 controller 配对共用计数器。Bundle 仅是宿主可传递的数据，不是本类自动完成的跨进程同步或进程全局唯一 ID。
- 实例操作遵循主线程约定；Handler 懒建且在主 Looper 调用延迟 action，不承诺任意线程并发安全。

## 2. 本轮修复与完整门控表

已有 `interruptionSupported` provider 用于时间窗，却未用于两种序号写操作。调用方显式注入 false 时仍递增并修改 Bundle/pair，旧“恒 true、零影响”判断已经过期。

现 `addSeqId` 和 `updateNextFinishSeqIdIfNeed` 均在写入前检查同一 provider；关闭时记录门控 INFO 日志后返回，不递增计数、不改 Bundle、不替换 pair。

| 操作 | 门控/行为 |
|---|---|
| addSeqId(bundle) | null 直接返回；非 null 且 interruption 支持时才分配并写入。关闭时连已有 key 也保留。 |
| updateNextFinishSeqIdIfNeed(controller) | interruption 支持且 pair 为空或 controller 不相等时，分配一次并替换。 |
| getNextFinishSeqId(controller) | 不加 feature gate；与已有 pair 结构相等则返回旧值，否则 0。关闭写入不等于清 pair。 |
| canFinishRecent | 仅 startingSurface 和 interruption **都支持**且 finish gap <=500ms 时阻止。 |
| canInterceptGesture | 仅两个 feature **都支持**且 startApp gap <=300ms 时阻止。 |
| resetInterceptState | 只清 lastStartAppTime，不清其它时间戳、SeqId 或 controller pair。 |

startingSurface provider **不参与序号写入**；不复用时间窗谓词当写入权限。原报告“starting surface=false 后仍只比时间窗”逻辑错误：任一 feature 关闭时，时间窗查询直接放行。

Manager 的 `supportInterruption()` 默认仍恒 true；Demo `interruptionEnabled` 控制实例/no-op 返回并在关闭时清旧排队动作。独立创建 helper 可注入真实 provider，本轮修复的是这个实际入口，不声称已接入 OEM ROM/RUS，也不声称旧持有实例会被永久撤销。

## 3. 延迟执行与配对的既有行为

- 重复同一 controller 或 equals 相等对象不重分配；换 controller 才递增。addSeqId 在两次配对之间消耗同一个计数器，但不会改变已保存 pair 的 ID。
- `getNextFinishSeqId`/更新用 Kotlin 结构相等匹配 OEM Intrinsics.areEqual；不把一般 Any 的 equals 等价为引用比较。null controller 是本地放宽的有效配对值，OEM 参数非空。
- 延迟队列只保留最新请求；执行前先取出并清 action，再调用外部函数，允许函数提交后继。立即放行的请求也先取消旧排队项，防止旧任务晚到；这些是此前已落实的本地可靠性增强，OEM 原顺序没有同样保护。
- 清理 removeMessages 并释放 action；回调 Trace 成对结束，异常仍向上抛，不是吞掉失败或重新执行。
- `maxOf(0L, delay)` 不是死代码：条件检查与排队计算分别读时钟，即使时钟单调前进，也可能第一次读还在 500ms 内、第二次已越过截止点。本轮补测试，保证零延时仍排队而非构造栈内直接执行。
- Default helper 的 delayFinishRecents **立即调用 action 并返回 false**，两时间窗放行、序号查询 0，其它变更不操作；旧“8 方法都是空体”不准确。

## 4. 原风险与建议逐项验收

| 原项 | 当前处置 |
|---|---|
| 3-a / 4.1-5 | ✅Bundle 写入补 interruption provider；关闭不写、不分配，重新开启首个序号仍为 1。 |
| 3-b / 4.1-4 | ✅双 feature 时间窗此前已实现；四组合和 300/500 等号边界测试保留，不添加无接收方的 startingSurface 全局 setter。 |
| 3-c / 4.1-3 | ✅结构相等已正确，既有回归验证相等的不同实例，不改回 ===。 |
| 3-d / 4.1-1 | ✅resetInterceptState override 已有，独立时间戳重置测试保留；更深时钟边界由下一份继续核对。 |
| 3-e / 4.1-2 | ✅条件更新此前已有；本轮补此前漏掉的 interruption gate，关闭时也不替换旧 pair。 |
| 4.1-6 | ✅AnimationSeqRegressionTest 的 testSameAndEqualControllersKeepSequenceUntilControllerChanges 已覆盖三种情况；不是只有旧 AnimationSeqHelperTest 的不同对象查询。新增共享计数器交错写入测试。 |
| 3-f / 4.1-7 | ✔️不添加无本地调用方的 MAX_GO_NORMAL_DELAY_TIME=200；也不补无使用的 USE_SEQ。常量存在不自动构成当前 Kotlin 库的 OEM 二进制兼容承诺。 |

原 2.1 八项结构：计数器、消息号/key、Bundle putLong、removeMessages 保留；Handler 消费顺序和立即放行清理按本地补强说明；Default 的立即 action 非空操作。原 2.2/2.3 的 API/容器/可空差异不再概称“字节码相同/完全等价”。

### 原 4.2 六项简化

1. **日志**：当前已有序号、延迟日志，本轮补两种 gate skip；由 LogUtils 级别控制，不再声称全部省略，不冒充 Perfetto。
2. **非空检查**：本地 Bundle?/Any?/可空函数确实允许 null，是主动放宽；Java 也可能传 null，不能拿“Java 一定不传”作理由。
3. **懒 Handler**：保留少触碰 Android owner 的构造方式；当前 Robolectric 测试有主 Looper，不继续把旧 stub 环境当现状。
4. **类型简化**：保留 Any?/Kotlin 函数以解耦系统类型；不等价于 Runnable/RecentsAnimationController 的 JVM 方法签名，不承诺 OEM ABI。
5. **延时钳制**：保留，新增单调时钟跨截止点回归；无需系统时钟回退即可触发。
6. **TAG/USE_SEQ**：不为字面一致增加未使用常量；日志 tag 用现有类名，USE_SEQ 无本地读取方。

## 5. 验证记录

- AnimationSeqFeatureGateTest 新增 4 条（4→8）：关闭 Bundle 写入、关闭 pair 创建/替换、只用 interruption 的共享序号流、两次时钟读取跨过截止点。
- 前三条加入后，旧 7 条中 **2 失败**（`.gradle/review-ordered-30-red.log`）；单调跨截止点是已有行为的补充回归，不虚报新修复。
- 定向 Seq/TraceLog/ControllerStateMatrix 共 **34 条通过**（`.gradle/review-ordered-30-green.log`）；完整 **Debug/Release 各 315 tests、42 类，0 failures/errors/skipped**；Demo Debug 成功，82/82 tasks executed，2m 16s（`.gradle/review-ordered-30-final.log`）。119 份源码/测试/两模块配置 SHA-256 前后一致。
- README 测试表、Markdown 相对链接、`git diff --check` 及 AGENTS.md 未改检查通过。无设备/Perfetto 验证，Lint 未完成；既有构建警告保留，无提交/推送。
