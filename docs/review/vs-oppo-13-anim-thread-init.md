# vs-oppo-13 — AnimationControlThread 初始化复核

> **第 18/39 份，✅完成本轮建议验收。** 本文替代旧版相互矛盾的完成标记和“精确复刻”结论；按当前代码逐项核对，不以历史 commit 描述代替证据。总进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## 1. 范围与证据

本库：
- [AnimationControlThread.kt](../../lib/src/main/java/com/asyncanimator/thread/AnimationControlThread.kt)：线程创建、onLooperPrepared、优先级与单例。
- [Executors.kt](../../lib/src/main/java/com/asyncanimator/thread/Executors.kt)、[LooperExecutor.kt](../../lib/src/main/java/com/asyncanimator/thread/LooperExecutor.kt)：公开执行器入口及 owner 转发。
- [AnimationHandler.kt](../../lib/src/main/java/com/asyncanimator/core/AnimationHandler.kt)、[ChoreographerTickScheduler.kt](../../lib/src/main/java/com/asyncanimator/core/ChoreographerTickScheduler.kt)：自有 ThreadLocal 与公开 app VSYNC。

只读参考根 `D:\oppo_a6_launcher\sources`：

| 文件（相对参考根） | 证据 |
|---|---|
| `com/oplus/basecommon/thread/OplusExecutors.java:95,169-176` | launcher.anim / -19、平台隐藏 AnimationHandler 的 SF provider 与 UX 注册、执行器访问器 |
| `com/oplus/basecommon/thread/Executors.java:94-99` | HandlerThread.start → UAF 报告 → getLooper |
| `com/oplus/basecommon/thread/OplusLooperExecutor.java:83-87` | 构造函数 execute 初始化 Runnable；这不是公开可变的全局 hook |
| `com/oplus/basecommon/util/LauncherBooster.java:100,221,604` | 私有事件 ID 2016、UAF 报告、UX 注册入口 |

另外直接核对本地 **Android SDK 36 sources/android-36/android/os/HandlerThread.java:76-85,103-111**：

```text
记录 tid → Looper.prepare → 发布 mLooper / notifyAll
→ Process.setThreadPriority(mPriority) → onLooperPrepared → Looper.loop
```

**更正旧分析**：getLooper 只等 Looper 被发布，不等待 onLooperPrepared 或 loop 启动。因此准备期间可以 post，但普通消息必须等准备返回才执行。取得执行器不是就绪通知；初始化任务执行的栈/时机可观察，不能写“差异不可观察”或估算未经测量的微秒/毫秒窗口。

## 2. 原对应项逐条结论（10 项）

| 原项 | 本轮结论 |
|---|---|
| A-1 构造/start | ✅保留线程名及 -19；库用 HandlerThread 子类，OPPO 用工厂和执行器包装。相同常量不代表同等调度效果。 |
| A-2 初始化时序 | ✅修正文档并补测试：本库在 loop 前安装，OPPO 经构造器 execute 排入初始化任务；不宣称队列位置/故障栈完全一致。 |
| A-3 常量/单例 | ✅保留 synchronized lazy；首次访问才启动，与原厂 eager static 初始化时机不同。MAIN 访问不启动动画线程已由上一轮修复。 |
| B-1 优先级/UX | ✔️保留差异。重复 setThreadPriority 只是尽力重申，不是 UX 注册或绑核替代。 |
| B-2 UAF | ✔️不移植私有注册；2016 仅记录为参考常量，不写入运行逻辑。 |
| B-3 执行器构造 | ✅旧“没有 LooperExecutor”已失效；本库已有包装器，但不补 OEM 阻塞等待/单任务 UX API。 |
| C-1 初始化可观察性 | ✅纠正“错误都被吞掉”：安装失败向外抛；本地 runCatching 不覆盖 HandlerThread.run 更早的优先级设置失败。 |
| C-2 daemon | ✔️不新增强制配置；Thread 的 daemon 状态继承创建线程，不是旧文所谓永远默认 false。测试也不依赖 daemon 状态。 |
| C-3 公开访问器 | ✅已有 Executors.ANIM_CONTROL_EXECUTOR，不需要复制 OplusExecutors 名称和类型。 |
| C-4 首跑 hook | ✔️不添加全局可变回调；普通 owner 任务通过既有执行器提交，未来特殊首跑需求另行定义注册/失败/生命周期契约。 |

## 3. 原风险逐条验收（R-1～R-6）

| 风险 | 状态、证据与边界 |
|---|---|
| R-1 优先级失败 | ✔️保留平台限制。局部重申已有 Log.w；没有证据证明任意 ROM 都接受 -19，更没有设备数据证明调度收益。构造阶段失败不会被局部兜底捕获。 |
| R-2 并发首次访问 | ✅已回归。ExecutorInitializationTest 验证 MAIN 冷启动不求值动画单例、8 路首次请求同一个真实执行器；不再把 lazy 访问写成类加载。 |
| R-3 安装与消息竞争 | ✅补真实业务首条消息回归，并用受控 HandlerThread 证明 Looper 先发布、业务后执行。反射先查 ThreadLocal 已安装，避免 instance 的默认构造掩盖遗漏。 |
| R-4 重复优先级 | ✅明确保留 runCatching + onFailure 日志，不再同时写“删除”“改 try/catch”“仍保留”。后续执行器允许修改线程优先级，不保证永远为 -19。 |
| R-5 名称/事件 ID | ✔️保留 launcher.anim / -19；没有 UAF 注册时，同名不产生私有系统策略。2016 不作为通用 Android 能力。 |
| R-6 ExecutorService | ✅纠正“包装器缺失”；本库 LooperExecutor 提供 execute/post/访问器/优先级与拒绝 shutdown，但不实现完整 ExecutorService 类型契约。保持 API 子集，不冒称二进制兼容。 |

旧文重复的 R-2 合并为一项；风险总数为 6，不沿用重复标题计数。

## 4. 原建议逐项处置（9 项）

| 建议 | 本轮处理 |
|---|---|
| 4-A-1 删除重复优先级或记录失败 | ✅选择保留且记录失败；现有实现已满足，不做无收益的语法改写。修正 KDoc 中没有 -19 公开常量、已注册 UX 等错误。 |
| 4-A-2 全局 onThreadReady | ✔️明确不采纳。无业务调用方；可变进程全局 lambda 有注册竞态、异常与捕获对象滞留风险，不应为勾选待办新增。执行器首条任务测试覆盖现有接入方式。 |
| 4-B-1 不补 setUxThreadValue | ✔️保留；私有 UX 服务没有在本库接入。 |
| 4-B-2 不补 reportKeyThreadToUAF | ✔️保留；不硬编码 OEM 调度服务。 |
| 4-B-3 不补执行器/阻塞/UX | ✅拆分结论：执行器已有且有测试；executeBlockWait / executeWithUx 不补，避免业务主线程等待及无效 UX 模拟。 |
| 4-B-4 不补访问器 | ✅用现有 Executors.ANIM_CONTROL_EXECUTOR，删除“只能自己包 Handler”的过时描述。 |
| 4-B-5 删除优先级兜底 | ✔️统一到 4-A-1 的保留+告警方案，不再给出互斥建议。 |
| 4-C-1 名称/优先级监控 | ✅源码保留常量，新测试在首条 owner 任务断言名称与模拟优先级 -19；真实设备仍需另验。 |
| 4-C-2 synchronized lazy | ✅保留并解释 start 副作用不能用 PUBLICATION/NONE；与既有 8 路并发回归共同守护。 |

## 5. 本轮修改与验证

- 修正 AnimationControlThread / Executors 的初始化、帧源和线程边界 KDoc；不新增运行时全局 hook，不改变优先级处理逻辑。
- 删除“CustomRectFSpringAnim 仍占位”的失实描述：已有生命周期桥，但不是 OEM 六轴几何/SurfaceControl 引擎。
- 新增 [AnimationThreadBootstrapTest](../../lib/src/test/java/com/asyncanimator/thread/AnimationThreadBootstrapTest.kt) **2 tests**：冷启动首条消息、可提前入队但不可提前执行。
- **负对照**：临时移除 scheduler 安装时，2 条中首条任务测试失败（安装断言），时序测试通过；恢复源码后再跑。此为回归敏感性验证，不算新发现的产品故障。日志 `.gradle/review-ordered-18-negative-control.log`。
- 定向 **4 类/13 tests 全通过**（`.gradle/review-ordered-18-green.log`）。
- 全量 **Debug/Release 各 226 tests，35 类，0 failures/errors/skipped**；Demo Debug 成功，**82/82 tasks executed，1m 42s**（`.gradle/review-ordered-18-final.log`）。108 份源码/测试/两模块配置 SHA-256 前后一致。
- `git diff --check`、相对文档链接检查通过。Lint 未完成，无设备/Perfetto 验证；AGENTS.md 与 OPPO 源码未改，未提交/推送。

## 6. 完成定义与未实现边界

本份完成指 10 个对应项、6 个风险和 9 个建议全部经过核对，采用/不采用有明确理由；不等于每项私有能力均已移植。库的 scheduler 安装只影响自有 AnimationHandler，Choreographer 要在首次请求帧时才获取。没有连接 SF-VSYNC、UX/UAF；没有设备压力测试/Perfetto 证据。原历史复核中互相矛盾的 commit 叙述和字面量 `\r` 已由本次证据表替代，不再作为完成依据。
