# vs-oppo-26 — AnimSeqTimeStamp 时间原点、时钟与并发边界复核

> **第 31/39 份：✅完成（含明确边界）。** 按当前代码核对八项风险、六项建议与十项简化，不沿用“无同步/缺三个 reset”的旧快照。进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## 1. 源码依据

- 本地 `lib/src/main/java/com/asyncanimator/seq/AnimSeqTimeStamp.kt`；消费方 AnimationSeqHelper；Demo7 实际调用公开的 updateLastRecentFinishTime。
- OPPO 只读 `D:/oppo_a6_launcher/sources/com/android/systemui/shared/system/AnimSeqTimeStamp.java:21-67`：四个 getter；69-107：四个 reset；109-147：四个 update。十二个方法均为静态 synchronized，裸 long 初始化/重置为 0，getter 直接 uptimeMillis 减字段。
- 本地此前已有四个独立 reset，八个 update/reset 和 resetAllForTest 已带 @Synchronized，字段 @Volatile，getter 不取 monitor。不会仅因旧报告再次添加相同修饰。
- 本地 object 是进程/类加载器内共享，不自动与另一个进程的 SystemUI 同步；字段命名映射只是去掉 OEM 的 Millis/Mills 后缀。

## 2. 本轮落地

### 区分未记录与有效的零毫秒时间

旧实现把 `timestamp == 0` 视为未记录，返回 Long.MAX_VALUE。注入从 0 开始的单调时钟后，刚发生的 start/finish 被当成没有事件，300/500ms 时间窗立刻放行；不是仅未来算术调用的假设风险。

四个字段改为 nullable Long：**null 是未记录，0 是有效事件**。更新零时刻后 gap=0，推进 10ms 后 gap=10；独立 reset 改为 null，仍不触碰另外三个字段。未记录查询不调用时钟。

保留未记录 gap=Long.MAX_VALUE 作为“不阻塞窗口”的本地契约，不伪装 OEM 原始 uptime-0：开机/测试时间小于 300/500ms 时两者本就不同。没有改成 MAX/2，因为乘更多倍或加足够偏移仍会溢出；没有把“半个 MAX”当通用算术安全。

### 时钟入口收窄并修复测试泄漏

`clock` 从 public 改为 **internal 测试入口**，默认仍是 uptimeMillis。库外没有 clock 消费方；Demo 不受影响。测试用同一原点的单调毫秒，换原点前 reset、结束时恢复，不能在工作线程运行时交换。

同时补 AnimationSeqHelperTest 的 @After，恢复原 clock 并清时间戳，避免固定 10000ms 假时钟泄漏到后续测试。没有仅加 VisibleForTesting 注解就声称禁止生产访问；internal 是 Kotlin 模块边界，非 JVM 安全机制，也不是旧公开 clock setter 的二进制兼容承诺。

### 十二处门控诊断

四个 update、四个独立 reset、四个 gap 查询都使用既有 LogUtils，字段标签为 startApp/recentFinish/recentStart/launchTask，输出 timestampMs 或 gapMs。未记录显示 unset；OFF 不输出且不构造动态消息；查询只采样一次时钟，不为日志重读。resetAllForTest 保持静默。

这些是 stderr 诊断，不是 Android Perfetto 事件，也没有复制 OEM 的无条件 Log.d。

## 3. 同步与 API 的明确边界

- 写/reset 在 object monitor 下串行化；nullable Long 的单字段引用通过 volatile 发布，避免把多步骤“是否设置+数值”分拆成两个不同步字段。
- getter 读取一个字段快照，再取当前 clock；不声称与 OEM 持锁的整个 getter 具有完全相同时序。当前窗口比较不要求四字段统一事务，不新增无调用方的聚合 snapshot API。
- resetAllForTest 的写入彼此串行化，但不取相同锁的 getter 仍可能在其间读取；即使每个 getter 加锁，连续四次调用也不是原子四字段快照。旧“只给 reset 加锁就解决多字段读取”说法错误。
- 并发测试使用真实四个 worker 更新/重置，等待各任务完成后检查结果；这证明已完成工作的读取结果，不是压力跑过就证明所有内存时序。
- updateLastRecentFinishTime 保持 public，因为 Demo7 跨模块实际调用；另三个 update/reset/gap 保持 internal，无必要公开所有内部写入口。
- 默认同原点单调时钟是调用契约；不支持活跃事件期间换时间域，也不对任意恶意/溢出注入值做全域算术校正。clock 抛异常时原样传播，赋值未发生，旧时间戳仍保留。

## 4. 原风险与建议逐项验收

| 原风险 / 建议 | 当前处置 |
|---|---|
| 风险1 / 4.1-1 | ✅四独立 reset 此前已存在，原有独立性测试保留；本轮内部未记录表示改为 null。 |
| 风险2 / 4.1-3 | ✅修复零时刻事件混淆；✔️保留 MAX 比较哨兵，拒绝无消费者的任意算术和 MAX/2 假安全修复，明确与 OEM 早启动差异。 |
| 风险3 / 4.1-4 | ✅clock 收为 internal 测试入口，补恢复/原点约束；不是公共运行时配置。 |
| 风险4 / 4.1-5 | ✅resetAllForTest 已同步，保留；更正其不能让多个无锁 getter 构成事务的错误推论。 |
| 风险5 / 4.1-6 | ✅本轮补十二处 LogUtils 诊断及 INFO/OFF 回归；没有真实设备 trace 交付。 |
| 风险6 | ✔️保留 writer monitor + volatile 单字段读取，不概称“纯无锁读写”或“比 OEM 更快”；未做性能测量。 |
| 风险7 / 4.1-2 | ✔️保留按真实调用方设计的可见性，不因表面对称而扩张三个写 API；不补 OEM @JvmStatic/@JvmName ABI。 |
| 风险8 | ✔️保留简洁字段名，映射在源码/日志/本文中清楚说明。 |

### 原 4.2 十个简化项

1. 同步不是“全无锁”，按上一节修正，保留当前实际策略。
2. 私有 gapTo 复用保留，加入标签与 null 分支。
3. 保留可注入能力但只在模块内测试使用；当前 Robolectric 有模拟时钟，不沿用“JVM 永远只能读 0”的旧环境结论。
4. Kotlin object 保留，不由语法等价推导 JVM 静态方法签名完全一致。
5. 字段名称不恢复 OEM Mills 拼写。
6. 保留“未记录立即放行”的比较哨兵，0 事件不再走该分支。
7. 旧“省略全部日志”已撤销，实际补齐门控诊断。
8. 公开/内部 API 依实际 Demo7 调用保留，不称其无外部消费者。
9. resetAllForTest 实际已有同步；测试仍需无活跃工作线程并恢复状态。
10. KDoc 更新为当前进程边界、null/0、比较哨兵和读写策略，而不是只保留笼统 demo 简化版。

## 5. 验证记录

- 新增 AnimSeqTimeStampTest **6 条**：四字段零时间与 reset、原点事件两时间窗、未记录查询不读 clock、clock 异常不覆盖旧值、四 worker 更新/reset、十二条日志与 OFF。
- 旧实现 6 条中 **3 失败**（`.gradle/review-ordered-31-red.log`）；并发/异常/未记录不采样是已有行为验证，不虚报为本轮新修复。
- 定向 Seq/TraceLog/ManagerLifecycle **37 tests 通过**（`.gradle/review-ordered-31-green.log`）；完整 **Debug/Release 各 321 tests，43 类，0 failures/errors/skipped**；Demo Debug 成功，82/82 tasks executed，2m 35s（`.gradle/review-ordered-31-final.log`）。120 份源码/测试/两模块配置 SHA-256 前后一致。
- README 测试表、Markdown 相对链接、`git diff --check` 与 AGENTS.md 未改检查通过。无设备/Perfetto 验证，Lint 未完成；既有构建警告保留，无提交/推送。
