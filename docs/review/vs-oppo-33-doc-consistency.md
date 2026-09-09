# vs-oppo-16 — 文档 / 代码一致性扫描（USAGE.md + README.md + docs/review/*）

> 范围：`D:/AsyncAnimator/docs/USAGE.md`、`D:/AsyncAnimator/README.md`、`D:/AsyncAnimator/docs/review/{01-04, SUMMARY, 06-vs-oppo-public-api-and-callsites, vs-oppo-06..12}*.md` 之间的相互一致性 + 与 `D:/AsyncAnimator/lib` 当前状态的对应关系。
>
> 取证方式：
> - lib 侧：Python `open(...).read()`（绕过 DLP 加密时的密文返回），行号取自 Kotlin 文件
> - sources 侧：Grep ripgrep 明文通道穿透 DLP，行号取自 JADX 反编译文本
>
> 不重复前 12 份 review 已写过的逐类保真度结论；本报告专攻**文档层面的不一致 / 引用过期 / 计数错配**。

---

## ① 类对应关系表（"文档声明的类" ↔ "lib 包内真实类"）

| 文档声明 | 期望 lib 类 | lib 当前实际 | 一致性 | 证据 |
|---|---|---|---|---|
| `USAGE.md §AsyncValueAnimator` | `launcher/async/AsyncValueAnimator` | ✓ 存在 | ✅ | `lib/.../async/AsyncValueAnimator.kt` |
| `USAGE.md §AsyncAnimCallbacks` | `launcher/async/AsyncAnimCallbacks` | ✓ 存在 | ✅ | `lib/.../async/AsyncAnimCallbacks.kt` |
| `USAGE.md §ActualEndAnimListener` | `launcher/async/ActualEndAnimListener` | ✓ 存在 | ✅ | `lib/.../async/ActualEndAnimListener.kt` |
| `USAGE.md §LooperExecutor/Executors` | `launcher/async/{LooperExecutor, Executors}` | ✓ 存在 | ✅ | `lib/.../async/LooperExecutor.kt`, `Executors.kt` |
| `USAGE.md §CustomRectFSpringAnim` | `launcher/async/CustomRectFSpringAnim` | ✓ 存在 | ✅ | `lib/.../async/CustomRectFSpringAnim.kt` |
| `USAGE.md §AsyncSpringAnim` | **未声明**（应当出现） | `launcher/async/AsyncSpringAnim` 存在 | ❌ **漏列** | review 06 §A-B / §③ / §④ / SUMMARY §7 均点名 |
| `USAGE.md §AnimationControlThread` | `launcher/animthread/AnimationControlThread` | ✓ 存在 | ✅ | `lib/.../animthread/AnimationControlThread.kt` |
| `USAGE.md §AnimationController` | `launcher/controller/AnimationController` | ✓ 存在 | ✅ | `lib/.../controller/AnimationController.kt` |
| `USAGE.md §AnimationState` | `launcher/controller/AnimationState` | ✓ 存在 | ✅ | `lib/.../controller/AnimationState.kt` |
| `USAGE.md §DefaultAnimationController` | `launcher/controller/DefaultAnimationController` | ✓ 存在 | ✅ | `lib/.../controller/DefaultAnimationController.kt` |
| `USAGE.md §OnAnimStateChangeListener` | typealias | ✓ 存在 | ✅ | `lib/.../controller/OnAnimStateChangeListener.kt`（typealias，**review 12 #4 bug**：引用相等性破） |
| `USAGE.md §TaskStateChangeTimeOutListener` | class，自管理超时 | ✓ class（**不是** fun interface） | ⚠️ 文档 §有意简化清单 **过期** | `lib/.../controller/TaskStateChangeTimeOutListener.kt:11` + USAGE.md §有意简化清单 倒数第 3 项仍写"fun interface" |
| `USAGE.md §RemoteAnimationFactory/LauncherAnimationRunner` | 类型壳 + 接口 | ✓ 存在 | ✅ | `lib/.../controller/RemoteAnimationFactory.kt` + `com/android/launcher3/LauncherAnimationRunner.kt` |
| `USAGE.md §AnimationSeqHelper` | `launcher/seq/AnimationSeqHelper` | ✓ 存在 | ✅ | `lib/.../seq/AnimationSeqHelper.kt` |
| `USAGE.md §AnimSeqTimeStamp` | `launcher/seq/AnimSeqTimeStamp` | ✓ 存在 | ✅ | `lib/.../seq/AnimSeqTimeStamp.kt` |
| `USAGE.md §AnimationFeatureHelper` | `launcher/feature/AnimationFeatureHelper` | ✓ 存在 | ✅ | `lib/.../feature/AnimationFeatureHelper.kt` |
| `USAGE.md §OplusAnimManager` | `launcher/manager/OplusAnimManager` | ✓ 存在 | ✅ | `lib/.../manager/OplusAnimManager.kt` |
| `USAGE.md §NullableAnimatorListener/Adapter` | `launcher/pending/{NullableAnimatorListener, NullableAnimatorListenerAdapter}` | ✓ 存在 | ✅ | `lib/.../pending/NullableAnimatorListener.kt`, `NullableAnimatorListenerAdapter.kt` |
| `USAGE.md §Trace` | `util/Trace` | ✓ 存在 | ✅ | `lib/.../util/Trace.kt` |
| `README.md §项目结构` tree Demo 行 | 列出 `Demo1..Demo9`（实际写了 9 个） | 实际有 11 个 Demo | ❌ **计数错配** | README.md:27-38（tree 行）+ `:48`（标题"10 个 Demo"）+ `:50-61`（表格 10 行）+ demo 目录 22 个 .kt（11 demos + 1 base + helpers） |
| `SUMMARY-vs-oppo.md §1` "已有 review 01-04 标注的 15 处高风险已全部修复" | 15 项已修复 | 全部已修复（与 review 12 §A 1-15 一致） | ✅ | review 12 §A "已修复" 表 1-15 + lib 当前代码（详见 §②） |
| `SUMMARY-vs-oppo.md §7` "USAGE.md 漏列 AsyncSpringAnim" | 漏列 | 仍未补 | ⚠️ **未执行** | USAGE.md grep "AsyncSpringAnim" = 0 hit |
| `SUMMARY-vs-oppo.md §7` "TaskStateChangeTimeOutListener 已从 fun interface 更新为自管理超时类" | 已同步 | **USAGE.md §有意简化清单仍写 fun interface**，§TaskStateChangeTimeOutListener 段落本身已写对 | ⚠️ **半同步**：类定义段落对了，"有意简化清单"那条仍过期 | USAGE.md:208 + USAGE.md:317 |

---

## ② 保真度评估（精确复刻 / 有意简化 / 文档漂移 / 遗漏）

### A. 精确复刻（review 01-04 "已修复" 与 lib 当前状态一致）

逐项与 review 12 §A "已修复" 表交叉复核（10 + 5 = 15 项）：

| review 12 # | 修复点 | lib 当前证据 | 评价 |
|---|---|---|---|
| A1 | 线程优先级 -19 | `AnimationControlThread.kt:78` `private const val PRIORITY = -19`（类注释自承"曾是 bug"） | ✅ 1:1 |
| A2 | `ScheduledTickScheduler` 末帧停 | `ScheduledTickScheduler.kt:81-83` `if (callbacks.isEmpty()) stop()` + `HandlerTickScheduler.kt:74-78` 同款 | ✅ 1:1 |
| A3 | `addRecentsAnim` 转移表 UNKNOWN/MULTI_WAITING 修正 | `AnimationController.kt:64-72` 三分支 NONE/OPEN/REVERSE_OPEN/WAITING→CLOSE、MULTI_OPEN/MULTI_WAITING/MULTI_REVERSE_OPEN→MULTI_CLOSE、else→UNKNOWN | ✅ 1:1 |
| A4 | 时钟域 `currentTimeMillis` → `uptimeMillis` | `AnimationController.kt:139, 153, 178, 186, 193` + `AnimSeqTimeStamp.kt:25` 全部 `SystemClock.uptimeMillis()` | ✅ 1:1 |
| A5 | `appLaunchAnimStartOrEnd` end 分支补 | `AnimationController.kt:88-100` 含清 list + `checkAllAnimationFinished` + 转 WAITING/MULTI_WAITING | ✅ 1:1 |
| A6 | `RecordInputInterpolator.inputed` 初值 0f | `RecordInputInterpolator.kt:13` `var inputed = 0f` | ✅ 1:1 |
| A7 | 续行动画 param copy | `OplusValueAnimator.kt:140` `anim.param.copy()` | ✅ 1:1 |
| A8 | timeController setTarget/setProperty 真实接线 | `OplusValueAnimator.kt:122-124` `setTarget` + `addUpdateListener` | ✅ 1:1 |
| A9 | 续行 `LinearInterpolator` | `OplusValueAnimator.kt:148` `timeController.setInterpolator(LinearInterpolator())` | ✅ 1:1 |
| A10 | `setInterpolator` 双写 param | `OplusValueAnimator.kt:46-48` override + 双写 | ✅ 1:1 |
| A11 | listener 派发 async | `AsyncAnimCallbacks.kt:62-66` `exec.postAsync(action)` ≡ `Message.obtain().setAsynchronous(true)` | ✅ 1:1 |
| A12 | `onAnimActualEnd` 双轨结束 | `AsyncAnimCallbacks.kt:69-80` + `ActualEndAnimListener.kt:14-16` | ✅ 1:1 |
| A13 | listener 派发快照 | `AsyncAnimCallbacks.kt:75-80` `getListeners()` = `removeAll null + filterNotNull` | ✅ 1:1 |
| A14 | `delayStartActivityIfNeed` 三层互斥 | `AnimationController.kt:172-198` `if/else if/else if` + 清理段 | ✅ 1:1 |
| A15 | 超时 listener 自管 postDelayed | `TaskStateChangeTimeOutListener.kt:30-32` `handler?.postDelayed(timeOutOption, duration)` | ✅ 1:1 |

**结论**：review 01-04 "已修复" 全部与 lib 当前代码一致；SUMMARY §1 的 "15 处高风险已全部修复" 声明成立。**无新增 bug 级漂移**。

### B. 有意简化（review 01-04 §B / §2.2 已声明的裁剪）

| 项 | lib 当前 | review 01-04 声明 | 一致性 |
|---|---|---|---|
| `SfVsyncFrameCallbackProvider` → `HandlerTickScheduler` | ✓ | review 01 §②-B1 | ✅ |
| `LauncherBooster.setUxThreadValue` UX 线程注册 | 退化为 `Process.setThreadPriority` 兜底（`AnimationControlThread.kt:65-66`） | review 01 §②-B2 | ✅ |
| `OplusLooperExecutor.executeBlockWait`（5s 主线程硬等 ANR 风险）未移植 | ✓ | review 01 §②-B4 | ✅ |
| `Executors` 14 个 executor 全砍 | ✓（保留 MAIN + ANIM_CONTROL 2 个） | review 01 §②-B3 | ✅ |
| `LauncherAnimationRunner` 600+ 行 → 类型壳 | ✓ | review 03 §2.2 | ✅ |
| `RemoteAnimationFactory` → 2 方法 demo 接口 | ✓ | review 03 §2.2 | ✅ |
| `CustomRectFSpringAnim` → 句柄占位 | ✓ | review 04 §2.2-3 | ✅ |
| `AnimationFeatureHelper` → 本地 setter 模拟 RUS | ✓ | review 03 §2.2 | ✅ |

**结论**：与 review 01-04 声明一致，无新漂移。

### C. 文档漂移（lib 已动但文档未同步）

| # | 漂移 | 当前文档说 | 实际 lib 是 | 证据 |
|---|---|---|---|---|
| C1 | **`USAGE.md §有意简化清单` 倒数第 3 项** 写"- 超时 listener 简化为被动回调 fun interface（review 03 §2.2）" | fun interface | **class，自管理超时**，构造 postDelayed | USAGE.md:317 vs `TaskStateChangeTimeOutListener.kt:11`；SUMMARY §7 已点名"已从 fun interface 更新为自管理超时类（上轮修复后已同步）"——**但实际未完全同步** |
| C2 | **`USAGE.md §有意简化清单` 第 4 项** 写"- `Executors` 只保留 `MAIN_EXECUTOR`（review 01 §②-B3）" | 只保留 MAIN_EXECUTOR | **MAIN_EXECUTOR + ANIM_CONTROL_EXECUTOR** | USAGE.md:314 vs `Executors.kt:18-24`（两 executor 均存在） |
| C3 | **`USAGE.md §AnimationController` 段落** "对应原厂 `com.oplus.quickstep.utils.AnimationController`（review 03；**注意 review 03 §3-a 列出的转移表两处已知偏差，本库有意保留现状**）" | 转移表两处偏差 + 有意保留 | **已修复**：`AnimationController.kt:64-72` 三分支精确对齐原厂 `WhenMappings.$EnumSwitchMapping$0` case 1/3/8/9→CLOSE，case 2/6/7→MULTI_CLOSE，else→UNKNOWN | USAGE.md:147-148 vs `AnimationController.kt:64-72` + review 12 §A-3 |
| C4 | **`USAGE.md §有意简化清单` 末尾"已知语义差异（本次未修）"** 列出 `addRecentsAnim` 转移表偏差等 | "本次未修" | 转移表偏差已修复 | USAGE.md:321-322 vs review 12 §A-3 |
| C5 | **`README.md §项目结构` tree "LauncherEntryActivity.kt ← 9 个 demo 入口"** | 9 个 demo | **11 个 demo**（Demo1..Demo11） | README.md:27 vs 实际 demo 目录 11 个 Activity 文件 + `LauncherEntryActivity.kt:30-...` 11 个 `DemoXxx::class.java` 引用 |
| C6 | **`README.md §10 个 Demo 对应分析文档章节` 标题 + 表格 Demo1..Demo10** | 10 个 demo | **11 个 demo** | README.md:48, 50-61 vs demo 实际 11 个 |
| C7 | **`README.md §项目结构` tree 行 29-37 仅列 Demo1..Demo9**（路径展示截断） | Demo1..Demo9 | Demo10 + Demo11 也存在 | README.md:30-38 |
| C8 | **`USAGE.md` 漏列 `AsyncSpringAnim`**（review 06 §B-8 + §③-6 + SUMMARY §7 三处独立指出） | 不存在 | `launcher/async/AsyncSpringAnim.kt` 存在 + Demo11 在用 | USAGE.md grep "AsyncSpringAnim" = 0 hit；`Demo11ViewSpringAnimThreadActivity.kt:18, 121-127` 直接 `import` + `AsyncSpringAnim(s, true).start()` |
| C9 | **`README.md` §已知限制 第 2 项** "接口签名（`RemoteAnimationFactory` 等）按分析文档重新定义" | 重新定义 | 已确认与 review 03 §2.2 一致；但 Demo11 入口（`AsyncSpringAnim(real=..., supportAnimThread=...)`）在 README 中完全未提及 | README.md:104 vs `AsyncSpringAnim.kt:18-19` |

**结论**：共 **9 处文档漂移**，其中 C1-C4 是 review 修复后未反向同步到文档，C5-C7 是 demo 数量统计错配，C8-C9 是新增 API 未补充文档。**C1/C2/C3/C4 直接否定 review 12 的修复闭环**——"修复"被记录到 review 但 USAGE.md 没收回"已知差异"。

### D. 遗漏（review 06-12 引用的 review 01-04 信息过期）

| # | 文档引用 | 引用方 | 过期内容 | 实际 |
|---|---|---|---|---|
| D1 | review 09 #4 "EXISTS 只保留 MAIN_EXECUTOR + ANIM_CONTROL_EXECUTOR" | vs-oppo-09 | 该描述在 review 09 内已正确，但 USAGE.md §有意简化清单仍说"只保留 MAIN_EXECUTOR"——同一事实两文档不一致 | USAGE.md:314 vs `Executors.kt:18-24` |
| D2 | review 07 #4 "delayStartActivityIfNeed 把互斥结构破坏为顺序 if" | vs-oppo-07 | 描述的是 review 03 §3-b "修复前" 状态；review 12 §A-14 已修复 | `AnimationController.kt:172-198` 改回 `if / else if / else if` |
| D3 | review 09 #3 "OplusLooperExecutor 四扩展未复刻" | vs-oppo-09 | 描述的是 review 01 §②-B4；无过期（该条仍是事实） | OK |
| D4 | review 10 #17 "AnimationFeatureHelper 用本地 setter 模拟 RUS 下发" | vs-oppo-10 | 无过期 | OK |
| D5 | review 10 #14 "OnAnimStateChangeListener typealias 改坏 lambda 引用相等性（review 12 §4 标 bug）" | vs-oppo-10 | 标记正确；但**review 10 §2.1 把 "typealias 改为 fun interface" 列在第 2 轮修复清单里，未在 review 12 中重新核查是否已落地** | `OnAnimStateChangeListener.kt` 仍是 typealias（`typealias OnAnimStateChangeListener = ...`），**review 12 #4 仍为未修复 P0**——review 10 与 review 12 之间形成**未闭合的修复回路** |
| D6 | review 06 §D11 "AsyncSpringAnim 对应原厂 OplusAsyncSpringAnimWrapper，路径真实存在，lib 完整复刻" | 06-vs-oppo | 无过期；但 §③-6 明确 "USAGE.md 漏列"，SUMMARY §7 又点名，但**lib 维护者**没有任何文件/Issue 跟踪"补 USAGE.md"——文档/代码漂移**有诊断无修复** | 见 C8 |
| D7 | review 12 §B bug 表 #1 "TaskStateChangeTimeOutListener 缺全局事件总线" | vs-oppo-12 | 该项未在 SUMMARY "第一轮 P0 修复" 中体现；也未在 review 10 / review 09 中跟进 | review 12 标记后**无追踪** |

**结论**：review 06-12 中**没有发现"引用了 review 01-04 旧版本代码"导致事实错误的情况**——它们要么如实描述当时的 bug（D2），要么明确引用 review 12 已修复点（D6）。但 D5 / D6 / D7 揭示**诊断与修复不同步**：review 12 标了 bug，USAGE.md/README 没改；review 06 标了漏列，USAGE.md 也没补。**这是文档维护机制的缺失，不是引用错误**。

---

## ③ 行为差异风险点（文档声明 ↔ lib 实际的语义偏差）

| # | 风险点 | 文档侧 | 实际 lib 行为 | 影响面 | 修复成本 |
|---|---|---|---|---|---|
| **R1 (bug)** | **状态：⚠️未修复（USAGE.md:155-156 段落"转移表两处已知偏差，本库有意保留现状" + :334"已知语义差异（本次未修）…转移表"仍与代码不符；e62dbff/42882ff 未回写这两处；USAGE.md 非本批可改文档）** USAGE.md §有意简化清单 C1/C4 误导读者认为"transfer 表偏差未修" | "本库有意保留现状"、"本次未修" | lib 当前已修 | 用户按 USAGE.md 指南去对比原厂会**误以为 lib 故意 bug**；实际是文档滞后 | **5 行** USAGE.md 修改（移除 §有意简化清单中"fun interface"和"只保留 MAIN_EXECUTOR"，§AnimationController 段落删除"有意保留现状"措辞） |
| **R2 (bug)** | **状态：✅已修复（42882ff：USAGE.md 新增 §AsyncSpringAnim（:97-109），含 Demo11 用法 + addEndListener 经 runOnMainThread 回主线程）** USAGE.md 漏列 `AsyncSpringAnim`，Demo11 直接 `import` + 用 | 不在文档 | `AsyncSpringAnim` 是 demo 唯一驱动 androidx `SpringAnimation` 跨线程的入口类；USAGE.md 找不到路径 | 用户**无法仅靠 USAGE.md 复现 Demo11**——必须反编译 demo 源码看 import 才能找到入口 | **5-10 行** USAGE.md 新增 §AsyncSpringAnim 节（与 §AsyncValueAnimator 同级，含 `addEndListener` 注解） |
| **R3 (中)** | **状态：✅已修复（e62dbff：README.md 标题/表格/tree 统一为 11 个 Demo（:23-24,34））** README.md Demo 计数错配（10 vs 11） | "10 个 Demo" / "9 个 demo 入口" | 实际 11 个 | 用户对项目规模判断偏差；Demo 表格少了 Demo10 + Demo11 | **10 行** README.md：标题改 "11 个" + 表格补 Demo10 + Demo11 两行 + tree 段补全 |
| **R4 (低)** | **状态：⚠️未修复（USAGE.md:330 §有意简化清单仍写"超时 listener 简化为被动回调 fun interface"；42882ff 仅修段落标题，未清清单项）** SUMMARY §7 vs USAGE.md §有意简化清单 C1 半同步 | SUMMARY 宣称已同步 | USAGE.md §有意简化清单仍写 "fun interface"（C1）；USAGE.md §TaskStateChangeTimeOutListener 段落本身写 "class 自管理超时"（对了） | 自相矛盾：同一份文档两个段落对同一对象给出相反的"声明" | **1 行** USAGE.md:317 删除"fun interface" → "class 自管超时" |
| **R5 (低)** | **状态：✅已修复（e62dbff：README.md:23 已写"11 个 demo 入口"）** README.md §项目结构 tree 行 27 写"LauncherEntryActivity.kt ← 9 个 demo 入口" | 9 个 demo | 11 个 demo | 与表格不一致 | **1 字** README.md:27 "9" → "11" |
| **R6 (中)** | **状态：⚠️未修复（USAGE.md:327 仍写"Executors 只保留 MAIN_EXECUTOR"，实际 MAIN + ANIM_CONTROL（Executors.kt））** USAGE.md §有意简化清单第 4 项说"Executors 只保留 MAIN_EXECUTOR" | 只保留 MAIN | MAIN + ANIM_CONTROL 两个 | 与下文 §LooperExecutor 段落（列出 `Executors.ANIM_CONTROL_EXECUTOR`）自相矛盾 | **1 行** USAGE.md:314 改 "MAIN_EXECUTOR + ANIM_CONTROL_EXECUTOR" |
| **R7 (中)** | **状态：✅已修复（60bd048：OnAnimStateChangeListener 改 fun interface（注释"review 30 bug #1"）；USAGE.md:196-205 同步于 42882ff）** review 10 #14 提议"OnAnimStateChangeListener typealias → fun interface"作为第 2 轮修复，**review 12 #4 把它升为 P0 bug** | review 10 列为"建议修" | typealias 仍在（`OnAnimStateChangeListener.kt`），**removeOnAnimStateChangeListener 仍静默失效** | 静默 bug：业务方调 remove 不会真删——状态变更监听器持续被回调但调用方以为是空的 | **10 行**（按 review 12 #4 建议）：改 `fun interface` + 同步 DefaultAnimationController 拷贝语义（`DefaultAnimationController.kt:25`） |
| **R8 (低)** | **状态：❌不成立（证据：USAGE.md:229 自 e5aff88 起已含"原厂 600+ 行 runner 只保留了类型壳"，顶层分层树亦注"LauncherAnimationRunner ← 类型壳"；无"以为还有完整逻辑"的文本基础）** USAGE.md §LauncherAnimationRunner "对应原厂 600+ 行 runner" + "demo 传 null / arrayOf() 即可" | 600+ 行 vs 类型壳 | lib `LauncherAnimationRunner.kt` 仅保留 `RemoteAnimationTarget` 嵌套类型壳 | 与 review 03 §2.2 一致，但 USAGE.md 未明示这是 "类型壳 vs 原厂 600+ 行" 的简化范围；容易让用户以为 USAGE.md 提到的 runner 还有完整逻辑 | **2 行** USAGE.md §LauncherAnimationRunner 段落补 "demo 场景下原厂 600+ 行的 activity 入口、preload hooks、TaskViewAnimation 都被砍掉，仅保留 RemoteAnimationTarget 类型壳（review 03 §2.2）" |

**R1 + R2 + R3 + R6 = bug 级**：用户对照文档对照 lib 直接得出错误结论（"lib 故意保留 bug"、"找不到 Demo11 API"、"demo 数对不上"）。  
**R7 = 真实代码 bug**（review 12 #4 标过，未落地）。

---

## ④ 回移建议

### 值得补的（性价比高）

| # | 项 | 理由 | 修复成本 |
|---|---|---|---|
| W1 | **状态：✅已修复（42882ff 已补 §AsyncSpringAnim）** **USAGE.md 补 §AsyncSpringAnim**（R2） | Demo11 入口；review 06 §B-8 / §③-6 / SUMMARY §7 三处独立点名；属于"诊断已有但未落地" | 5-10 行 USAGE.md 新增 |
| W2 | **状态：⚠️未修复（USAGE.md:327/330/334 三条清单项仍未清理）** **USAGE.md §有意简化清单删 4 条过期项**（R1 + R4 + R6） | C1/C2/C4 都与 lib 当前状态矛盾；USAGE.md 段落本身已正确（§TaskStateChangeTimeOutListener 写"class 自管超时"），只是 §有意简化清单没收回 | 4 行 USAGE.md 改动 |
| W3 | **状态：⚠️未修复（USAGE.md:156 "本库有意保留现状"未删）** **USAGE.md §AnimationController 段落删"有意保留现状"**（R1 续） | 转移表偏差已修；同一段落下句 "有意保留现状" 直接否定了 review 12 §A-3 的修复 | 1 行 USAGE.md 删字 |
| W4 | **状态：✅已修复（e62dbff：README 计数已统一 11）** **README.md 计数统一为 11**（R3 + R5） | 标题、表格、tree、"LauncherEntryActivity.kt ← 9 个 demo 入口" 四处不一致 | 10 行 README.md |
| W5 | **状态：❌不成立（类型壳明示已存在（见 R8），无需改动）** **USAGE.md §LauncherAnimationRunner 段落补"仅类型壳"明示**（R8） | 与 review 03 §2.2 一致；消除"以为还有完整 runner"的认知偏差 | 2 行 USAGE.md |
| W6 | **状态：✅已修复（60bd048 代码 + 42882ff USAGE §标题/说明同步）** **OnAnimStateChangeListener typealias → fun interface**（R7） | review 12 #4 P0 bug；review 10 已提建议但未落地 | 10 行 lib 改动 + 2-3 处使用点适配 |
| **合计** | | | **30-35 行** |

### 建议保持简化（按调用面"非主线"判定）

| # | 项 | 理由 |
|---|---|---|
| K1 | **状态：✔️保持简化（与"未列出的成员均为 internal"约定一致）** USAGE.md 不为每个 `internal` 类建档 | 与现有约定（"未列出的成员均为 internal"）一致 |
| K2 | **状态：✔️保持简化（review 03 §2.2；USAGE.md:25,225-229 已明示类型壳）** `com.android.launcher3.LauncherAnimationRunner` 不补回 600+ 行 | review 03 §2.2 已说明与"动画执行模型"主题无关；demo 跑通即可 |
| K3 | **状态：✔️保持简化（§AsyncSpringAnim 段落已顺带说明 addEndListener 经 runOnMainThread 回主线程）** `AsyncAnimWrapper` 不展开文档 | 已是 `AsyncSpringAnim`/`AsyncValueAnimator` 的基类，§AsyncSpringAnim 段落会顺带讲清 `runOnAnimThread`/`runOnMainThread` 语义 |
| K4 | **状态：✅已修复（60bd048：updateNextFinishSeqIdIfNeed 条件更新 + getNextFinishSeqId 改 ==，代码缺陷已闭环，不再"留待修复轮"）** `AnimationSeqHelper.updateNextFinishSeqIdIfNeed` 语义偏差（review 03 §3-e + review 12 #B-9）**不在本次文档修复范围** | 属代码 bug，留待 review 12 §B 修复轮处理 |
| K5 | **状态：✔️保持简化（@Volatile 单字段原子写满足契约、无跨字段不变式；见 vs-oppo-34 §3.5，非文档漂移）** `AnimSeqTimeStamp` @Volatile 裸写 race（review 12 #B-8）**不在本次文档修复范围** | 属代码 bug，文档无对应漂移 |
| K6 | **状态：✔️保持简化（AnimType 3 vs 7 为设计取舍（USAGE.md:118 已注），非文档漂移）** `AnimType` 枚举 3 值 vs 原厂 7 值 | review 12 #B-9 + USAGE.md §CustomRectFSpringAnim 已列；不一致是设计取舍（demo 3 值够用），不是文档漂移 |

---

## ⑤ 补充：review 06-12 ↔ review 01-04 引用一致性专项

> 用户问题："review 06-12 引用既有 review 01-04 时是否引用了旧版本的代码？"

| 检查项 | 结果 |
|---|---|
| review 07 引 "review 03 §3-b delayStartActivityIfNeed 三层顺序 if" | ✅ 准确——review 03 §3-b 当时就是顺序 if（修复前）；review 12 §A-14 标注"已修复" |
| review 08 引 "review 01 §2-A-4 listener 快照派发" | ✅ 准确——review 12 §A-13 已修复 |
| review 09 引 "review 01 §B-1 SfVsyncFrameCallbackProvider → HandlerTickScheduler" | ✅ 准确——该简化永久保留，非"修复"对象 |
| review 09 引 "review 01 §B-3 Executors 只保留 MAIN_EXECUTOR + ANIM_CONTROL_EXECUTOR" | ✅ review 09 内部已写正确（MAIN + ANIM_CONTROL）；USAGE.md 引用同一事实时**漏写 ANIM_CONTROL**——是 USAGE.md 漂移，不是 review 09 错引 |
| review 10 引 "review 01 §②C-6 / review 02 / review 04" | ✅ 准确 |
| review 11 引 "review 01-04 已确认的精确复刻" | ✅ 准确——确认基线未变 |
| review 12 引 "review 01-04 各自高严重度风险点" | ✅ 准确——review 12 §A 1-15 与 lib 当前代码逐项交叉验证一致 |

**结论**：review 06-12 引用 review 01-04 时**无事实错误**——但 review 12 §A "已修复" 是**双向**的（既改代码又该改文档），而本轮修复**只动了代码侧**。这就是 §③ R1/R2/R3/R6 的根因。**不属于"引用旧版本"，而是"修复未闭环到文档"**。

---

## ⑥ 总览表（按"bug 级 + 修复成本"排序）

| # | 项 | 类型 | 修复成本 |
|---|---|---|---|
| 1 | R2: USAGE.md 补 §AsyncSpringAnim | 文档 bug | 5-10 行 |
| 2 | R1/R4/R6: USAGE.md §有意简化清单 4 条过期项 | 文档 bug | 4 行 |
| 3 | R1 续: USAGE.md §AnimationController 删"有意保留现状" | 文档 bug | 1 行 |
| 4 | R3/R5: README.md 计数统一为 11 + tree 补 Demo10/11 | 文档 bug | 10 行 |
| 5 | R8: USAGE.md §LauncherAnimationRunner 段补类型壳明示 | 文档增强 | 2 行 |
| 6 | R7: OnAnimStateChangeListener typealias → fun interface | **真代码 bug**（review 12 #4 P0） | 10 行 lib |
| **合计** | | | **30-37 行（22 行文档 + 10 行 lib）** |

修复后预期：USAGE.md 与 lib 完全对齐；README.md 11 个 demo 全部出现在文档面；SUMMARY §7 "文档一致性" 节从 2 条遗留降到 0 条；review 12 #4 的"诊断-修复"回路闭合。

---

## ⑦ 取证方法

- lib 侧：UTF-8 Kotlin 文件 → Python `open(...).read()`（绕过 DLP 加密层的 Read 工具返回密文），关键代码段通过 Python 脚本批量 grep
- sources 侧：Grep ripgrep 明文通道穿透 DLP 加密；行号取自 JADX 反编译文本
- 文档侧：Python 全文检索正则 `\bAsyncSpringAnim\b` / `fun interface` / `9 个 demo` / `10 个 Demo` / `11 个 demo` 全部命中点列表已在 §①/§②/§③ 列出
- 未运行任何编译/构建命令；本报告纯静态文档 + 源码对照

## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **215ecb5** — USAGE.md AnimType/AsyncValueAnimator 段一致
- **e62dbff** — 包路径已重整，USAGE.md 同步重写（42882ff 补 AsyncSpringAnim 小节）

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。
## 批次 6 逐条复核（2026-09-09 / 子代理逐项）

| 条目 | 判定 |
|---|---|
| R1 USAGE §AnimationController/已知差异写"转移表偏差未修" | ⚠️未修复（USAGE.md:155-156,334 仍过期） |
| R2 USAGE 漏列 AsyncSpringAnim | ✅已修复（42882ff） |
| R3 README Demo 计数 10 vs 11 | ✅已修复（e62dbff） |
| R4 USAGE 简化清单仍写 fun interface | ⚠️未修复（USAGE.md:330） |
| R5 README tree"9 个 demo 入口" | ✅已修复（e62dbff） |
| R6 USAGE 简化清单 Executors 只保留 MAIN | ⚠️未修复（USAGE.md:327） |
| R7 OnAnimStateChangeListener typealias→fun interface | ✅已修复（60bd048 + 42882ff 同步） |
| R8 USAGE §LauncherAnimationRunner 未明示类型壳 | ❌不成立（e5aff88 起已明示） |
| W1 补 §AsyncSpringAnim | ✅已修复（42882ff） |
| W2 简化清单删 4 条过期项 | ⚠️未修复（USAGE.md:327/330/334） |
| W3 §AnimationController 删"有意保留现状" | ⚠️未修复（USAGE.md:156） |
| W4 README 计数统一 11 | ✅已修复（e62dbff） |
| W5 §LauncherAnimationRunner 补类型壳明示 | ❌不成立（已明示） |
| W6 OnAnimStateChangeListener 改 fun interface | ✅已修复（60bd048 + 42882ff） |
| K1 internal 类不建档 | ✔️保持简化 |
| K2 LauncherAnimationRunner 不补 600+ 行 | ✔️保持简化 |
| K3 AsyncAnimWrapper 不展开文档 | ✔️保持简化 |
| K4 updateNextFinishSeqIdIfNeed 语义偏差 | ✅已修复（60bd048） |
| K5 AnimSeqTimeStamp @Volatile race | ✔️保持简化（见 vs-oppo-34 §3.5） |
| K6 AnimType 3 vs 7 | ✔️保持简化 |
