# vs-oppo-15 — TickScheduler 迁移与 Choreographer 实现复核

> **第 20/39 份：✅完成本轮建议验收。** 文件名保留历史双实现主题，正文按当前唯一生产实现重写。旧标题误写 vs-oppo-14，且把已删除定时器的代码/行号机械改名为 Choreographer，不能作为当前证据。总进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## 1. 当前实现与原厂证据

- [TickScheduler.kt](../../lib/src/main/java/com/asyncanimator/core/TickScheduler.kt)：持续订阅、取消、启动/暂停、纳秒时间戳与帧计数；没有 frameIntervalMs 属性。
- [ChoreographerTickScheduler.kt](../../lib/src/main/java/com/asyncanimator/core/ChoreographerTickScheduler.kt)：唯一生产实现，使用公开 app Choreographer VSYNC，既无线程池也无固定 16ms 定时器。
- [AnimationHandler.kt](../../lib/src/main/java/com/asyncanimator/core/AnimationHandler.kt) 默认与 [AnimationControlThread.kt](../../lib/src/main/java/com/asyncanimator/thread/AnimationControlThread.kt) 显式安装均用它。安装/换源安全协议见[上一份内核复核](vs-oppo-14-animation-handler-kernel.md)。

只读参考根 `D:\oppo_a6_launcher\sources`：

| 参考文件 | 本次确认的证据 |
|---|---|
| `androidx/core/animation/AnimationHandler.java:61-69` | Provider14 基于 lastFrameTime 计算 max(delay-elapsed, 0)，不是完成后固定等待一个周期。 |
| 同文件 `:82-109,130-137,174-202` | Provider16 向 Choreographer 单次 post，接收纳秒并转毫秒；handler 活取列表 size/逐帧续投，provider 的 onNewCallbackAdded 为空。 |
| `androidx/dynamicanimation/animation/AnimationHandler.java:14-38,75-94,123-145` | 固定 Choreographer 实例、uptime dispatcher、延迟 map；不是本库持续订阅接口。 |
| `com/oplus/basecommon/thread/OplusExecutors.java:95,169-171` | launcher.anim、-19、平台隐藏 SF provider 与 UX 调用。 |
| `com/android/wm/shell/pip/PipAnimationController.java:50-55,565-569,581` | ThreadLocal 初始化平台 handler、设置 SF provider，再交给 PiP animator；不是本库必须复制的公开 API。 |

另核对本地 SDK 36 `android/view/Choreographer.java` 的 getInstance、构造和帧入队路径：实例固定 Looper，跨线程 post 转交给其 Handler。JDK 21 `java/util/concurrent/ScheduledExecutorService.java` 说明同一周期任务不并发重叠、异常会抑制后续执行；这不等于 daemon 线程被异常“杀死”。

旧成员映射 #1/#11/#12 由当前接口说明替代；#2/#3/#5/#6/#7/#8 的定时线程、Handler 构造参数和 frameIntervalMs 均已不存在；#4/#9 的快照/异常/队列机制按现代码核对；#10 的 SF 私有能力未移植。不能写成“把旧类名替换后即 1:1 对位”。

## 2. 本轮发现并修复：重启迁移帧源

旧实现每次 scheduleNextFrame 都通过 getter 重新调用 Choreographer.getInstance。首次在动画线程启动、暂停后从主线程重启时，会选择主线程的 Choreographer，破坏同一 scheduler 的固定帧归属。

本轮修改：
1. 缓存**首次成功取得**的 Choreographer；构造 scheduler 不绑定线程，首次有效请求帧时才绑定，后续 restart 不更换 owner。
2. 入列/启动、pulsePosted 更新、空队列收尾使用同一短状态锁，避免重复订阅及收尾覆盖并发新增；业务回调仍在锁外执行，允许重入和其他线程注册。
3. 保留既有无 Looper/获取失败（含纯 JVM stub）的 no-op 兼容，不把失败缓存成永久 owner；stop/start 后允许重新获取。此降级不是自动重试服务或任何线程都能提供帧的保证。
4. stop 保留订阅、不终止线程；remove/stop 不能撤回已经开始派发的快照。调度器跨线程注册安全不等于上层 AnimationHandler 的列表可跨线程共享写入。

**证据**：ChoreographerOwnerTest 在真实 HandlerThread 首次取得帧源，主线程重启时核对实际 source getter 的实例身份，随后验证第二帧仍由原 owner 派发。恢复旧动态 getter 的负对照明确因 source 实例不同失败（`.gradle/review-ordered-20-owner-red.log`）。

首版测试未先排空跨线程 VSYNC 调度消息，旧/新实现都可能报“resumed frame must arrive”，属夹具问题，**不作为产品失败证据**。现先处理排队请求再推进时钟；源身份断言还避免把 Robolectric 多 receiver 共享 next-vsync 的干扰误判为真实帧源迁移。背景 receiver 使用独立非冗余 sandbox，未改原主线程 VSYNC 测试 SDK/节奏断言。

## 3. 原保真度判断逐项修正

### A：原 7 项“精确复刻”

| 原项 | 当前结论 |
|---|---|
| A1 空则停 | ✅最后订阅移除后回路最终停止；可能先消耗已排队的一帧，不是立刻终止线程。 |
| A2 自维持回路 | ✅持续订阅自动续帧；原厂 handler 逐帧 post，责任划分不同。 |
| A3 时间来源 | ✅直接使用 Choreographer 回调的 nanos，不再自行取 nanoTime/uptime 造帧时间。 |
| A4 并发队列 | ✅队列安全不等于状态协议自动安全；本轮把状态转移与注册放入短锁，回调锁外执行。 |
| A5 长溢出保护 | ✔️纠正旧描述：AtomicLong.incrementAndGet 没有溢出重置，Long 到上限仍可回绕；不增加无现实需求的 epoch 协议。 |
| A6 快照遍历 | ✔️本库 scheduler 按快照；原厂 handler 是活取 size，不是相同机制。回调内增删对下一快照生效，本轮回归明确区分。 |
| A7 null 回调 | ✅post/remove(null) 是 no-op；不把 OEM 未显式检查 null 的方法说成完全同形。 |

### B：原 9 项简化

| 原项 | 当前结论 |
|---|---|
| B1 TickScheduler 抽象 | ✔️保留持续订阅抽象，不声称合并全部 OEM provider。 |
| B2 固定速率线程池 | ✅旧实现已删除，不恢复。 |
| B3 不接 VSYNC | ✅过时；当前是 app VSYNC，但不是 SF-VSYNC。 |
| B4 nanoTime 选择 | ✅旧自走时钟代码已删除，不进行跨实现“统一时钟”改写。 |
| B5 ConcurrentLinkedQueue | ✔️保留；快照语义与上层 handler 的活列表语义分开说明。 |
| B6 frameDelay | ✔️不增加显示器刷新周期 API；ValueAnimator frameDelay 不等于刷新率。 |
| B7 空 provider hook | ✔️不补无行为接口位。 |
| B8 frameCount | ✔️保留诊断计数及 nanos；不等价于 B 路径的 getFrameTime/uptime dispatcher，也不是一组原子快照。 |
| B9 异常隔离 | ✔️保留单 consumer runCatching，与 OEM 原始异常传播不同；新增失败消费者不阻止健康订阅者/后续帧的回归。 |

### C：原 5 项遗漏

| 原项 | 当前结论 |
|---|---|
| C1 getFrameTime/getFrameDelay | ✔️已有 scheduler 时间和计数；不复制两个语义不同的 getter，更不提供假刷新率。 |
| C2 Provider16/VSYNC | ✅公开 Choreographer 已落地，本轮进一步固定 scheduler 的帧源身份。SF provider 仍未接入。 |
| C3 三个 provider hook | ✔️空 hook 不补；frameDelay 配置不是动态刷新率控制。 |
| C4 delay callback map | ✔️不新增无调用方的 B 内核延迟 API；若将来实现需同步处理 due 边界、移除与时钟。 |
| C5 ThreadLocal 自动初始化 | ✅本库 instance 默认已自动创建每线程 handler/source；显式 install 是额外初始化边界，且错误使用已 fail-loud。不必重写为 PiP 的 withInitial。 |

## 4. 原 10 项风险验收

| 原风险 | 本轮处置 |
|---|---|
| ① 共享线程破坏 per-thread | ✅原共享定时器已删；追查现代码又发现 restart 重新取 ThreadLocal 帧源，已固定首次成功 source 并回归。 |
| ② fixedRate / fixedDelay / 堆积 | ✅删除实现后不适用。纠正旧解释：同一周期任务不并行执行，fixedDelay 不是 OEM max(delay-elapsed, 0) 的等价替代。 |
| ③ daemon | ✅没有此定时线程；改 daemon=false 既不能防任务异常，也不是复活机制，不采用。 |
| ④ drift 补偿 | ✅Choreographer 不使用自写定时循环，不补旧 postDelayed 漂移算法。 |
| ⑤ VSYNC | ✅公开 app VSYNC 已接；保留不同受控周期测试，不把模拟 cadence 当作设备 90/120Hz 性能数据。 |
| ⑥ 周期任务异常停止 | ✅旧 executor 不存在；仍不承诺任意框架异常后自动恢复，no-op 获取失败边界已说明。 |
| ⑦ runCatching | ✔️保留本库异常隔离，新增健康回调继续派发测试；不伪称 OEM 相同。 |
| ⑧ 时钟混用 | ✅旧分叉消失；删除把 nanoTime 称 wall-clock 及未经验证 sleep/wake 基准的错误推断，当前直接传平台 frameTimeNanos。 |
| ⑨ post 自动 start | ✅当前非空 post 已自动启动；暂停保留订阅，后续 start 不重复排帧。 |
| ⑩ stop 不退出线程池 | ✅无共享线程池；stop 的职责是暂停而非进程/线程销毁。 |

## 5. 原回移建议逐项处置（7 + 7 + 4 项）

| 原建议 | 状态与理由 |
|---|---|
| A1 daemon=false/自重启 | ✅因删除失效，不为不存在的定时器补代码。 |
| A2 drift 补偿 | ✅因删除失效；无需在真实 VSYNC 上另造定时器。 |
| A3 共享 scheduler 警示 | ✅旧类不再存在；本轮改成当前固定 owner/无 Looper/暂停/快照的真实契约说明。 |
| A4 fixedDelay 替 fixedRate | ✅因删除失效，且两者不等价 OEM drift 补偿，不采纳旧替换方案。 |
| A5 新增 Choreographer 实现 | ✅已有实现且本轮补固定 source；不再新增“第三个”同类。 |
| A6 isolateExceptions 参数 | ✔️不采纳无使用方的模式开关；当前 scheduler 仍有异常隔离，不能仅因旧类删除就把此建议写成没有对象。测试和 KDoc 明示差异。 |
| A7 Handler post 自动启动 | ✅旧类已删，当前实现已有自动启动且有暂停/重启回归。 |
| B1 SF 相位/能力 | ✔️不接隐藏 SF API。旧 trace 相位一致不足以证明 SF 未生效，本轮无新设备数据，不沿用该因果结论。 |
| B2 delayed callback | ✔️无需求，不补；保持上层 native 动画自己的延迟行为。 |
| B3 frameDelay/空 hook | ✔️不增加配置刷新率假接口或空回调位。 |
| B4 PiP withInitial | ✅本库 ThreadLocal 默认初始化已存在，不复制私有 PiP 集成。 |
| B5 反射 SF provider | ✔️不反射隐藏 API。 |
| B6 两个时间源统一 | ✅旧双实现不存在，当前只收 Choreographer 时间戳。 |
| B7 stop 后 shutdown | ✅旧 executor 不存在；当前 stop 保留订阅便于恢复，不增加线程退出。 |
| C1 OEM DynamicAnimation/SF 间隔 | ✔️不在本 scheduler 实现，不能把它说成与 OEM 窗口动画“必然无关”；若未来移植完整 engine 需独立核对。 |
| C2 ObjectAnimator autoCancel | ✔️平台 ObjectAnimator 的内部职责，不搬进 TickScheduler。 |
| C3 frameDelay 暴露 | ✔️同 B3，未新增。 |
| C4 @JvmStatic getInstance | ✔️内核 internal、无 Java 消费者，沿用第 19 份的明确不扩展决定。 |

## 6. 验证与完成边界

- 新增 **6 tests**：ChoreographerOwnerTest 1 条（首次 source/重启 owner），ChoreographerTickSchedulerTest 5 条（异常隔离、快照增删、重入暂停/恢复、锁外等待并发注册、失败 source 不永久缓存）。后者共 8 条，原 3 条 cadence/暂停/空订阅回归不变。
- 定向 **5 类/26 tests 全通过**（`.gradle/review-ordered-20-final-green.log`）；随后新增的无 Looper 兼容回归纳入最终全量验证。
- 全量 **Debug/Release 各 236 tests，36 类，0 failures/errors/skipped**；Demo Debug 成功，**82/82 tasks executed，1m 50s**（`.gradle/review-ordered-20-final.log`）。109 份源码/测试/两模块配置 SHA-256 前后一致。
- `git diff --check` 和相对 Markdown 链接检查通过；未将初版夹具的消息推进失败计为产品故障。
- 测试源：[ChoreographerOwnerTest](../../lib/src/test/java/com/asyncanimator/core/ChoreographerOwnerTest.kt)、[ChoreographerTickSchedulerTest](../../lib/src/test/java/com/asyncanimator/core/ChoreographerTickSchedulerTest.kt)。
- “完成”表示原建议逐项实现、判定失效或写明不采纳理由；不代表 SF-VSYNC、UX/UAF、OEM 物理内核已移植。Robolectric 不是设备性能/刷新率验证，Lint/设备/Perfetto 未完成；OPPO 参考树和 AGENTS.md 不修改，不自动提交。
