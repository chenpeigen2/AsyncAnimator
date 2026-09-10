# 区域 12 对比 Review：语义差异与运行时风险

## 顺序验收：✅完成（第 17 份，运行时契约）

以当前源码和只读 OPPO `sources/` 为准，重新核对原文 10 项遗漏、11 项风险、7 项回移建议及 7 项保留决策。历史“已完成”“bug 级”标签和旧设备 trace 均不替代本轮证据。总进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## ① 本轮新增发现与修复

### Controller 完成回调的重入与异常

旧 `checkAllAnimationFinished()` 先调用外部 completion，再 reset：

- 第一个回调若调用 cleanUpRecentsAnim，会再次进入同一组尚未消费的回调。
- 回调若启动下一轮，旧轮尾部 reset 会清空新轮状态、列表、回调和触摸闸门。
- 第一个回调抛异常时，第二个不执行且 reset 不发生，旧回调保留，重复清理可能再次交付。

本轮先捕获本轮两个回调，再 reset 旧轮内部状态/触摸标志并消费回调字段，然后依次执行捕获的 recents / launch 回调。NONE 状态通知因此先于完成回调；回调开始时上一轮已经归位，之后没有再次清理新一轮的 reset。状态已为 NONE 且没有待完成回调时仍静默清理手势/挂起启动等临时字段，但不重复发送 NONE，避免清理重入重复通知。

reset 的状态观察者或任一完成回调抛异常时，仍尝试交付两个已捕获完成回调；最后重抛第一个异常，其他异常加入 suppressed，重复同一 Throwable 不做 self-suppression。**这不是吞异常，也不保证普通状态观察者彼此隔离**；只保证本轮完成流程不会因一个外部回调留下旧完成字段或跳过另一个完成动作。

这是本库的异常安全补强及可观察顺序调整，不冒称 OEM 一致：`com/oplus/quickstep/utils/AnimationController.java:224-230,237-260` 仍是执行 OEM 两类 finish 回调后 reset。集成方需按本库新的“内部归位 → NONE 通知 → 本轮完成回调”契约接线；新轮可由观察者/完成回调启动，库不会在旧回调结束后抹掉它。

### 非法 Recents 状态诊断

addRecentsAnim 非法分支先用 LogUtils 记录旧状态与操作，再保持原有 UNKNOWN 状态转移，不额外抛错。合法 NONE→CLOSE 不输出该错误。对应 OPPO `AnimationController.java:465-467`；日志使用现有 Debug/Release 门控，不代表 Perfetto 锚点。

## ② 原十项遗漏与十一项风险

| 原 C# / 风险# | 当前结论与证据 |
|---|---|
| C1 / 风险6：touch gate | ✅第 16 份已实现 600ms 定时、end/reset/destroy 清理、MULTI_WAITING/REVERSE_OPEN/挂起 action 判据，Demo9 实际调用；TouchGateTest 6 条。不是硬实时期限或系统输入拦截 |
| C2 / 风险4：平板限定 | ✅isTablet 注入 + 默认 Context sw600dp 判据已实现；不把可移植默认判据冒称 ROM 设备分类 |
| C3 / 风险5：搜索入口 | ✅两个 action 与 source 的实际匹配已实现；AnimationLaunchDecisionTest / AnimationBetweenStateTest 覆盖 |
| C4 / 风险3：overview 运行态 | ✅provider 或显式 overview 状态决定是否等待，不再以 100ms 时间窗推断是否在运行；100ms timer 仍只是注册的兜底期限 |
| C5 / 风险2：urgent timeout | ✅决策完成：保留本库主线程状态所有权，不增加只检测超时却仍须等待主线程执行动作的假隔离线程；不承诺忙主线程下与 OEM timeout 同时交付 |
| C6、C7 / 风险1：全局事件注册与触发 | ✅Controller 三槽是本地注册表，公开 dispatchTaskStateChange 已交付匹配事件并与 timer 一次性竞争；dispose/destroy 摘槽及清动作。没有全局 Binder bus，不能再写“事件永远是死路”或“漏删不存在的全局列表” |
| C8 / 风险7：默认值与 Adaptive | ✅六个 int 默认 -1，threshold 默认 1.0f；第 16 份补 host Adaptive 标志、radius getter 和强制 threshold=1.0f。不是“threshold 从 -1 改到 1.0f”的特殊分支 |
| C9 / 风险10：Seq 配对 | ✅仅 pair 为空或 controller 不相等时更新；same/equal controller 保持 ID，测试含独立但 equals 相等的实例，不改成 === 比较 |
| C10 / 风险11：非法状态无日志 | ✅本轮补 LogUtils 诊断并保留 UNKNOWN 行为；ControllerCompletionTest 验证合法/非法路径 |
| 无 C 对应 / 风险8：固定线程 Tick | ✅ScheduledTickScheduler/HandlerTickScheduler 已删除；Choreographer 帧源、固定 owner、换源回归已存在。第 14 份已修复测试 sandbox 真隔离，不复活旧调度器 |
| 无 C 对应 / 风险9：SF 与 app VSYNC | ✅保留公开 app Choreographer；SF 私有帧源未移植。没有本轮设备相位或抖动实测，旧 ±0.4ms/MTK trace 不能推广为当前实现性能结论 |

### 两类 Handler 不得混淆

- OPPO `AnimationController.java:95-100` 的触摸闸门 Handler 绑定 **主线程**。
- OPPO `com/oplus/quickstep/taskviewremoteanim/TaskStateHelper.java:124-136` 的 timeout 绑定 **URGENT_TRANSACTION_EXECUTOR**。
- 本库 Controller-owned timer、状态操作、事件桥在主线程；独立三参 timeout 的事件动作仍可在调用线程执行，使用原子一次性消费。线程忙会延后交付，不能以增加某个线程就保证系统事务正确排序。

## ③ 七项回移建议验收

| # | 建议 | 当前决策 |
|---|---|---|
| 1 | TaskState 注册表 / 类型事件 | ✅已有真实可调用的 controller 桥及三槽 ownership，不增加没有 Binder 生产者的全局假服务；事件/超时/销毁回归保持 |
| 2 | search / tablet / continuation 三条件 | ✅已有可移植实参/输入接缝；不新增与之重复的假单例动画 |
| 3 | flags 默认 -1 | ✅保留当前六个整数的未配置态，浮点 threshold 仍 1.0f；Adaptive 行为已补 |
| 4 | 101/600ms 闸门 | ✅已实现功能与 Demo 调用；消息编号不是公共 API，使用可撤回 Runnable；forbidTouch 包含所有四类判据，不只窗口位 |
| 5 | Seq 条件更新 | ✅已有实现及 same/equal/changed controller 回归，不重复造修复 |
| 6 | addRecentsAnim 错误日志 | ✅本轮实际补入，不再以“纯装饰”推迟可观察诊断 |
| 7 | 给旧 ScheduledTickScheduler 加警告 | ✅不适用：类型已删除；保留当前 Choreographer 接口和真实生命周期测试，不新建旧类型/别名 |

额外修复的完成回调异常/重入是交叉检查所得真实缺陷，不以原列表没有列出为由忽略。

## ④ 七项保留决策

1. 不移植隐藏 SfVsyncFrameCallbackProvider；公开 Choreographer 不是 SF 同相位保证。
2. 不移植 LauncherBooster UX 注册；priority=-19 不代表获得 OEM UX/UAF 权限或大核运行保证。
3. 不复制整个 OplusLooperExecutor。`executeBlockWait` 可阻塞，`executeWithUx` 依赖 OEM；**更正：executeAtFront/executeDelay 本身只是公开 Handler 操作**（原文件 :38-43/:73-75），不是全部依赖私有 API。当前无新增调用方，保留 getHandler 等现有门面即可。
4. 不创建 14 个无调用场景的事务/加载执行器；新增线程不能替代实际任务及所有权协议。
5. 不复制完整 continuation helper，但保留并使用其可移植运行态输入，不能写成“不引入运行态判定”。
6. 不引入远程 merge、按键注入、预启动等 OEM 业务 helper 空壳；没有声称完成系统多任务转场。
7. 不反射硬挂 SF 或平台隐藏 AnimationHandler；自有帧源仅管自己的订阅。

## 验证与限制

ControllerCompletionTest 初始 5 条在旧代码全部失败，修复后含 Controller/触摸/Manager 的 34 条定向测试通过；另补“旧回调启动新轮后抛错，新轮仍保留”的组合回归，本类再补 idle 清理保留项，共 7 条。首次实现的 idle 直接返回被该回归检出会保留 pending/gesture 标志，已改为静默 reset；对应 `.gradle/review-ordered-17-idle-red.log` 为 7 条中 1 条失败。

- **Debug/Release 各 224 tests，34 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，2m 23s**（`.gradle/review-ordered-17-final.log`）。两变体不算 448 个独立测试。
- 源码/测试及两模块构建配置 SHA-256 验证前后一致（`.gradle/review-ordered-17-before-validation.json`）；原五条失败、定向回归与 idle 保留项日志分别是 `-red.log`、`-green.log`、`-idle-red.log`。
- `git diff --check`、修改文档相对链接检查通过；README 用例表与实际 XML 逐类计数一致。
- 无设备触摸/回调压力/Perfetto 验证；Lint 未完成，既有构建/Java deprecation 提示保留。未修改 OPPO/AGENTS.md，未提交或推送。
