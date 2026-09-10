# vs-oppo-14 — AnimationHandler 帧调度内核复核

> **第 19/39 份：✅完成本轮建议验收。** 逐条对照当前源码与 OPPO 参考树，替代旧版的过时行号、silent-return/固定 16ms 结论和互相冲突的成员计数。总体进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## 1. 范围与源码证据

本库：[AnimationHandler.kt](../../lib/src/main/java/com/asyncanimator/core/AnimationHandler.kt)、[TickScheduler.kt](../../lib/src/main/java/com/asyncanimator/core/TickScheduler.kt)、[ChoreographerTickScheduler.kt](../../lib/src/main/java/com/asyncanimator/core/ChoreographerTickScheduler.kt)。

参考根 `D:\oppo_a6_launcher\sources`，本次仅只读：

| 路径简称 | 实际参考文件及关键位置 |
|---|---|
| A：vendored core | `androidx/core/animation/AnimationHandler.java`：19-30 回调/provider，82-109 Choreographer，119-156 遍历/清理/计数，158-182 实例/注册，184-215 auto-cancel/派发/移除/frameDelay |
| B：vendored dynamicanimation | `androidx/dynamicanimation/animation/AnimationHandler.java`：14-38 ThreadLocal/delay/dispatcher，96-133 清理/时间/due，135-176 注册/遍历/provider/移除 |
| C：平台隐藏内核 | 参考树没有 `android.animation.AnimationHandler` 实现；`com/oplus/basecommon/thread/OplusExecutors.java:169-171` 仅证明 setProvider(SfVsyncFrameCallbackProvider) 和 UX 调用。不能由此断言其换源/构造实现与 B 相同。 |

**当前定位**：自有内核主要参考 A；不是 A/B/C 的完整合并，也不替代 platform ValueAnimator 或 AndroidX 的内部 handler。使用公开 app VSYNC，不是旧文所写的 postDelayed/固定帧率，更不等于 SF-VSYNC。

## 2. 成员对位复核

保留旧表编号便于追溯；原 #21 重复 #2，旧“15/21 个成员”不是可靠统计。当前新增的 generation/tickCallback 也不能遗漏。

| 旧编号 | 当前成员与处置 |
|---|---|
| #1 | internal 构造器接受可选 scheduler；默认/缺省创建 ChoreographerTickScheduler。与 A 构造注入、B lazy provider 只是部分对应。 |
| #2 / #21 | AnimationFrameCallback Boolean 返回值保留；内核忽略返回值，结束必须显式移除。 |
| #3 / #6 / #14 | schedulerHolder / scheduler / TickSchedulerHolder：局部同步 lazy getter；不使整个 handler 线程安全。 |
| #4 / #5 | 可空 callback 列表与 listDirty；Java ArrayList 也允许 null，不存在旧表所谓原厂元素强制非空的语义差异。 |
| #7 / #20 | callbackSize 排除 null；companion animationCount 查询当前 instance，不是进程总计。testHandler 生效时查询该覆盖实例。 |
| #8 / #9 | 注册幂等、null no-op、删除置 null；没有 B 的 delay map，不应单独添加一个不存在的 map 清理。 |
| #10 / #11 / #12 | onTick(nanos) → doAnimationFrame(ms) → cleanUpList → 空时退订；活取 size，同帧增删可见。单 callback 异常隔离是本库行为。 |
| #13 / #19 | 换源仅退订自己的旧 tick，递增代次，按需在新源注册；保留旧源其他订阅者和正在遍历的当前帧。 |
| #15 / #16 / #17 | ThreadLocal 默认实例；进程级 volatile testHandler 优先。不通过双检锁构造全局实例。 |
| #18 | install 的 null 参数抛 IllegalArgumentException；已有实例/全局测试覆盖抛 IllegalStateException。不再 silent return。 |
| 本库新增机制 | schedulerGeneration、tickCallbackFor、tickCallback：过滤旧源已复制/排队的迟到脉冲，不能省成“只换 holder”。 |

## 3. 原保真度/遗漏表逐项修正（32 项）

### A：原 12 个“精确复刻”判断

| 原项 | 当前结论 |
|---|---|
| A1 回调签名 | ✅保留 long/Long→Boolean 契约；只对这个签名作对应，不推断自动取消。 |
| A2 ThreadLocal | ✅默认每线程实例；不是全局共享单例。 |
| A3 testHandler | ✅已有 volatile 覆盖；仅引用发布，不保护共享实例的 callback 列表，测试必须串行并 finally 恢复。 |
| A4 查找顺序 | ✅test override → ThreadLocal → 构造；默认源已是 ChoreographerTickScheduler，删除 ScheduledTickScheduler 引用。 |
| A5 注册幂等 | ✅contains 去重；新增 remove/re-add 在清理前仍只有一个 self-pulse 的回归。 |
| A6 懒删除 | ✅删除立刻使槽无效；未轮到的回调同帧跳过。 |
| A7 清理 | ✅removeAll 保持存活项顺序；不做无测量依据的性能等价宣称。 |
| A8 首次订阅 | ✅保留 start + post，provider 持续订阅；原厂 handler 逐帧 post，责任划分不同。 |
| A9 时间单位 | ✅纳秒截断为毫秒，新测 1,999,999 / 17,123,456 / 1,234,567,890 ns，验证非固定节拍且不取 wall clock。 |
| A10 顺序/null | ✅while 活取长度；移除后存活回调顺序经两帧验证。 |
| A11 listDirty | ✅仅删除置脏、帧尾清理恢复；getter 计数不等清理完成。 |
| A12 Boolean 含义 | ✅纠正“true 可自动摘除”：A/B 遍历均忽略返回值；既有显式移除测试保持。 |

### B：原 10 个简化项

| 原项 | 当前结论 |
|---|---|
| B1 TickScheduler 抽象 | ✔️保留可测试的自有持续订阅接口，不声称替代隐藏 provider。 |
| B2 start/stop | ✔️保留；自定义实现应按后续 tick 派发，不能把内核当作支持同步重入的任意 executor。 |
| B3 没有 VSYNC | ✅旧结论失效。当前 Choreographer app VSYNC；没有设备测量不能写与 SF 等价。 |
| B4 frameDelay | ✔️不增加名义帧间隔 API；ValueAnimator frameDelay 配置不是显示器刷新周期。 |
| B5 onNewCallbackAdded | ✔️不补空 hook；A 的两个 provider 实现为空。 |
| B6 onTick 私有 | ✔️保留私有入口/闭包，避免外部绕过帧源协议。 |
| B7 nullable 元素 | ✅Kotlin 明确表达 null 槽；Java 原代码同样能存 null。 |
| B8 removeAll | ✔️保持可读实现，新增清理顺序/计数回归，不为形式一致换 reverse loop。 |
| B9 换源 | ✅此前已修复“停止整个旧 scheduler”；当前只撤自身订阅，旧 pulse 有代次过滤。 |
| B10 install/replace 分离 | ✅保留明确首次/运行时接口，错误使用明确抛出。 |

### C：原 10 个遗漏项

| 原项 | 当前结论 |
|---|---|
| C1 静态计数入口 | ✔️不为 internal 内核加 @JvmStatic/公开 dumpsys API；companion animationCount 已存在，并非旧文“实例属性”。 |
| C2 测试 setter | ✅已有 companion 属性 setter，Java 形式是 Companion.setTestHandler(handler)，不是 getTestHandler().set(handler)。无 Java 内核调用方，不扩展 API。 |
| C3 delayed callback | ✔️无当前调用方，保持不实现。未来若引入，需同时定义 due 边界/重复注册/移除与时钟，不只是加 15 行 map。 |
| C4 autoCancel | ✔️平台 ObjectAnimator 自身负责其能力，本库不扫描/取消平台内部回调。 |
| C5 provider 空 hook | ✔️同 B5，不补空接口位。 |
| C6 frameDelay/frameTime | ✅两者不是同一概念；scheduler 已可读取 frameTimeNanos/frameCount，但不是 B 的 uptime dispatcher/getFrameTime 完整契约。 |
| C7 frameDelay/setProvider | ✅换源入口已有；不补 frameDelay，也不直接照搬 B 的裸 provider 字段赋值。 |
| C8 A/B 完整合并 | ✔️明确没有合并；B 的延迟与 uptime dispatcher 仍不支持。 |
| C9 同帧新增 | ✅while 活取 size 早已实现，既有回调内新增测试保持；contains 不能保证恶意无限新增/自移除重加会终止，调用方不能无界重入。 |
| C10 当前帧时间 | ✅scheduler 时间可读；不另造 B 的 mCurrentFrameTime/静态 getter，不把刷新率与帧时间混为一谈。 |

## 4. 原 11 个风险逐项验收

| 风险 | 结论 |
|---|---|
| ① 快照 size | ✅已用 while 活取；同帧新增、未来槽移除、存活项顺序有回归。 |
| ② 安装 silent return | ✅已 fail-loud；本轮补 testHandler 覆盖时 install/replace 拒绝、解除覆盖后 ThreadLocal 仍可首次安装的缺口。 |
| ③ 激进换源/丢帧 | ✅自身退订+代次过滤，保留其他订阅者与当前遍历；不保证下帧立刻到达或两个源相位无缝。帧时间来自 onTick 参数，不是旧文所说的中途读取新 holder。 |
| ④ 空 provider hook | ✔️不补，没有业务行为可移植。 |
| ⑤ delay map 清理 | ✔️无 delay map，不单独补删除；与未来 delayed API 一并设计。 |
| ⑥ start/post 拆分 | ✔️保留当前持续订阅协议，不混用 OEM 单次 post 协议。 |
| ⑦ removeAll/reverse loop | ✔️形式差异，新回归确认压缩后的顺序/计数；不虚构性能基准。 |
| ⑧ 清理不内联 | ✅调用链仍先遍历后清理再判断退订；无其他公开派发入口。 |
| ⑨ 静态计数 | ✔️已有 companion 计数，但不加无消费者 Java facade；计数不是跨线程总数。 |
| ⑩ instance 无锁 | ✔️默认 ThreadLocal 下合理；test override 明确只用于受控测试，不宣称共享实例安全。 |
| ⑪ A/B 分叉 | ✔️当前不移植 B 的延迟/双时钟协议，不能因此宣称所有弹簧内核已还原。 |

## 5. 原建议处置（7 + 10 + 3 项）

| 原建议 | 处置 |
|---|---|
| A1 安装 fail-loud | ✅现有实现保留，本轮补全测试覆盖分支。 |
| A2 活取 size | ✅现有实现保留，新补同帧未来槽删除与顺序验证；无界追加风险明示。 |
| A3 非破坏换源 | ✅保留当前安全协议；**不采用旧建议“只替换 holder”**，因为旧源仍持有本库持续 tick 订阅，可能重复派发/滞留。 |
| A4 delay 重载 | ✔️无需求，不做 speculative API；原厂 B 语义作为未来独立需求。 |
| A5 @JvmStatic count | ✔️内核 internal 且无 Java/dumpsys 消费者，不增加公开面。 |
| A6 reverse loop | ✔️不做纯形式改写，使用结果/顺序回归约束。 |
| A7 空 onNewCallbackAdded | ✔️不新增无行为 hook。 |
| B1 dispatcher/双时钟 | ✔️不移植，以 tick 入参时间为准。 |
| B2 autoCancel | ✔️不重做平台 ObjectAnimator。 |
| B3 frameDelay | ✔️不补；纠正“构造期帧率固定”。 |
| B4 空 hook | ✔️同 A7。 |
| B5 public onAnimationFrame | ✔️保持私有，禁止旁路派发。 |
| B6 SF 帧间隔 | ✔️不在本库公开 app VSYNC 的能力范围内。 |
| B7 callback 列表开放 | ✔️保持 private，通过注册/删除/计数访问。 |
| B8 listDirty 开放 | ✔️保持 private，不暴露清理状态。 |
| B9 拆两个内核 | ✔️不为未接入的 B 动画复制第二套内核。 |
| B10 instance 锁/DCL | ✔️不增加；线程归属与测试覆盖约束已写明。 |
| C1 隐藏平台源码 | ✔️只记录实际可见的调用链，不推断内部实现。 |
| C2 Provider14 ThreadLocal | ✔️不补旧 Handler fallback；当前是 Choreographer，实现不再引用已删除 HandlerTickScheduler。 |
| C3 B dispatcher | ✔️同 B1，不重复移植。 |

## 6. 本轮修改与验证

- 更新内核 KDoc，纠正“Android 平台 androidx”“与原版完全一致”“安装默认之外帧源”等过度结论，明确 owner 串行、测试全局覆盖和计数边界。**本轮未改内核运行算法**，已有修复不重复改写。
- AnimationHandlerTest 新增 **3 tests**：时间戳纳秒截断、未来回调即时移除/压缩顺序、null/no-op 与清理前 re-add 单订阅。
- AnimationSchedulerHandoffTest 新增 **1 test**：全局测试覆盖拒绝 install/replace、覆盖计数、finally 恢复及解除后首次安装无污染。
- 定向 **2 类/16 tests 全通过**（`.gradle/review-ordered-19-green.log`）。
- 全量 **Debug/Release 各 230 tests，35 类，0 failures/errors/skipped**；Demo Debug 成功，**82/82 tasks executed，1m 38s**（`.gradle/review-ordered-19-final.log`）。108 份源码/测试/两模块配置 SHA-256 前后一致。
- `git diff --check` 和相对 Markdown 链接检查通过；Lint 未完成，无设备/Perfetto 验证；AGENTS.md 与 OPPO 树未改，无提交/推送。

测试源：[AnimationHandlerTest](../../lib/src/test/java/com/asyncanimator/core/AnimationHandlerTest.kt)、[AnimationSchedulerHandoffTest](../../lib/src/test/java/com/asyncanimator/core/AnimationSchedulerHandoffTest.kt)。旧文“只有 6 个空测试/无换源覆盖”的附录不再适用。完成表示所有建议已处置，**不表示 delayed/SF/UX/平台内核已实现**；未做设备 VSYNC/Perfetto 测量。
