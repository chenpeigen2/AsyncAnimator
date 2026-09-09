# 区域 13 对比 Review：AnimSeqTimeStamp 4 字段并发语义

> 对比双方：
> - **lib**：`D:/AsyncAnimator/lib`（AsyncAnimator 演示库，Kotlin 重实现）
> - **原厂**：`D:/oppo_a6_launcher/sources`（OPPO ColorOS 15 Launcher `com.android.launcher 15.8.24` JADX 反编译源码）
>
> 本区域聚焦 `com.android.systemui.shared.system.AnimSeqTimeStamp` ——全局 4 个 `last*Time` 时间戳字段的并发读写契约与 API 形态差异。background 引用 review 07 §③-风险 1 / bug #2；本报告不再展开整体并发原语对比，仅深挖该单类的字段语义、reset 形态、clock 抽象。
>
> 取证方法：lib 侧经 Python `open(...).read()` 穿透 DLP 拿明文；sources 侧 80% 文件 DLP 加密，原始 `.java` 经 Python UTF-8 解码、行号为 JADX 文本行号。

---

## ① 类对应关系表

| lib 成员（文件:行） | 原厂成员（文件:行） | 关键语义 |
|---|---|---|
| `object AnimSeqTimeStamp`（`:11`） | `public final class AnimSeqTimeStamp` + `INSTANCE = new AnimSeqTimeStamp()` + `private AnimSeqTimeStamp()`（`AnimSeqTimeStamp.java:10,11,18`） | 双方都是 singleton：Kotlin `object` ↔ JADX 还原的 Java singleton INSTANCE |
| `@Volatile private var lastStartAppTime = 0L`（`:9-11`） | `private static long lastStartAppTimeMillis`（`:16`） | **裸 long 在 synchronized 方法内访问** vs **@Volatile 字段 + 无方法同步** |
| `@Volatile private var lastRecentFinishTime = 0L`（`:13-15`） | `private static long lastRecentFinishTimeMills`（`:14`，原厂笔误 "Mills"） | 同上 |
| `@Volatile private var lastRecentStartTime = 0L`（`:17-19`） | `private static long lastRecentStartTimeMills`（`:15`） | 同上 |
| `@Volatile private var lastLaunchTaskTime = 0L`（`:21-23`） | `private static long lastLaunchTaskTimeMills`（`:13`） | 同上 |
| `@Volatile var clock: () -> Long = { SystemClock.uptimeMillis() }`（`:25-27`） | **无对应**：原厂每个方法内硬编码 `SystemClock.uptimeMillis()`（`:25,37,49,61,112,122,132,142`） | **lib 独有增强**：可注入时钟（test 用 `nanoTime/1_000_000`） |
| `internal fun updateLastStartAppTime() { lastStartAppTime = clock() }`（`:31-33`） | `@JvmStatic public static final synchronized void updateLastStartAppTime() { lastStartAppTimeMillis = SystemClock.uptimeMillis(); Log.d(TAG, …); }`（`:139-147`） | **裸赋值无锁** vs **全方法 synchronized + Log.d**；可见性：lib volatile 写可见 vs 原厂 monitor exit 可见 |
| `fun updateLastRecentFinishTime() { lastRecentFinishTime = clock() }`（`:35-37`） | `@JvmStatic public static final synchronized void updateLastRecentFinishTime()`（`:119-127`） | lib **public**（其余 update 全 internal） vs 原厂 public；裸赋值 vs synchronized |
| `internal fun updateLastRecentStartTime() { lastRecentStartTime = clock() }`（`:39-41`） | `@JvmStatic public static final synchronized void updateLastRecentStartTime()`（`:129-137`） | 同上 |
| `internal fun updateLastLaunchTaskTime() { lastLaunchTaskTime = clock() }`（`:43-45`） | `@JvmStatic public static final synchronized void updateLastLaunchTaskTime()`（`:109-117`） | 同上 |
| `internal fun resetLastStartAppTime() { lastStartAppTime = 0 }`（`:47-49`） | `@JvmStatic public static final synchronized void resetLastStartAppTime() { lastStartAppTimeMillis = 0L; Log.d(TAG, …); }`（`:99-107`） | **API 形态对齐但 lib 缺 3 个对应 reset** |
| **无对应**（lib 缺失） | `@JvmStatic public static final synchronized void resetLastRecentFinishTime()`（`:79-87`） | **lib 缺失** —— 原厂在 `OplusOverviewCommandHelperImpl.java:626`、`RemoteAnimationRunnerCompat.java:625`、`OplusBaseSwipeUpHandler.java:10759` 等处调用 |
| **无对应**（lib 缺失） | `@JvmStatic public static final synchronized void resetLastRecentStartTime()`（`:89-97`） | **lib 缺失** —— 原厂在 `AppLauncher.java:108`、`RecentsViewAnimUtil.java:2803` 等处调用 |
| **无对应**（lib 缺失） | `@JvmStatic public static final synchronized void resetLastLaunchTaskTime()`（`:69-77`） | **lib 缺失** —— 原厂在 `RemoteAnimationRunnerCompat.java:445`、`OplusOverviewCommandHelperImpl.java:455`、`OplusBaseSwipeUpHandler.java:10758`、`Launcher.java:4211,4457`、`LauncherAnimationRunner.java:569`、`DockIconView.java:235` 等 7+ 处调用 |
| `internal fun resetAllForTest() { lastStartAppTime = 0; lastRecentFinishTime = 0; lastRecentStartTime = 0; lastLaunchTaskTime = 0 }`（`:51-54`） | **无对应**：原厂没有"一次性重置所有 4 字段"方法 —— 只有 4 个独立 `resetLast*Time()` | **lib 独有**：demo 化整合，但**不是原厂替换** |
| `private fun gapTo(timestamp: Long): Long = if (timestamp == 0L) Long.MAX_VALUE else clock() - timestamp`（`:56-57`） | 内联到 4 个 getter（`:22-31, 34-43, 46-55, 58-67`）：`jUptimeMillis = SystemClock.uptimeMillis() - lastXxxTimeMills;`（**无 0L 短路**） | **语义差异**：lib 显式 `Long.MAX_VALUE` 哨兵 vs 原厂隐式返回 `uptimeMillis` |
| `internal val timeGapToLastStartAppTime: Long get() = gapTo(lastStartAppTime)`（`:59-60`） | `@JvmStatic public static final synchronized long getTimeGapToLastStartAppTime()`（`:57-67`） | **API 形态**：lib Kotlin property（internal） vs 原厂 Java getter（public） |
| `internal val timeGapToLastRecentFinishTime: Long get() = gapTo(lastRecentFinishTime)`（`:61-62`） | `@JvmStatic public static final synchronized long getTimeGapToLastRecentFinishTime()`（`:33-43`） | 同上 |
| `internal val timeGapToLastRecentStartTime: Long get() = gapTo(lastRecentStartTime)`（`:63-64`） | `@JvmStatic public static final synchronized long getTimeGapToLastRecentStartTime()`（`:45-55`） | 同上 |
| `internal val timeGapToLastLaunchTaskTime: Long get() = gapTo(lastLaunchTaskTime)`（`:65-66`） | `@JvmStatic public static final synchronized long getTimeGapToLastLaunchTaskTime()`（`:21-31`） | 同上 |
| **无对应**（lib 缺失） | `private static final String TAG = "AnimSeqTimeStamp"` + 每方法内 `Log.d(TAG, ...)`（`:12` + 12 处 `Log.d`） | **lib 缺失**：每个 update/reset/get 都带 Log.d，便于调试与外部观测 |
| `clock` getter/setter（`:25-27` 整段 `@Volatile var clock`） | **无对应**：原厂 `SystemClock.uptimeMillis()` 硬编码调用 | **lib 独有**：可注入时钟，JVM 单测 `clock = { System.nanoTime() / 1_000_000 }`（`AnimationSeqHelperTest.kt:28`） |

---

## ② 保真度评估

### A. 精确复刻（行为可对齐）

| # | 设计点 | 原厂证据 | lib 证据 |
|---|---|---|---|
| 1 | 单例形态（跨模块全局访问） | `INSTANCE = new AnimSeqTimeStamp()` + private constructor（`:10-19`） | `object AnimSeqTimeStamp`（`:11`）—— Kotlin `object` 编译产物与原厂 INSTANCE 等价 |
| 2 | 4 个时间戳字段名语义对齐（"上次 XX 时间"） | `lastStartAppTimeMillis / lastRecentFinishTimeMills / lastRecentStartTimeMills / lastLaunchTaskTimeMills`（`:13-16`） | `lastStartAppTime / lastRecentFinishTime / lastRecentStartTime / lastLaunchTaskTime`（`:9-23`） |
| 3 | 单字段 update/reset/get 操作原子性 | 每个方法 `@JvmStatic synchronized` 在类对象 monitor 上（`:21-147`） | 每个方法读/写单一 `@Volatile` long（`:31-66`）—— **JMM 单字段读写语义等效**（happens-before via volatile vs monitor） |
| 4 | update 把字段设为当前时钟 | `lastXxxTimeMills = SystemClock.uptimeMillis();`（`:112,122,132,142`） | `lastXxxTime = clock();`（`:32,36,40,44`） |
| 5 | reset 把字段设为 0L | `lastXxxTimeMills = 0L;`（`:72,82,92,102`） | `lastXxxTime = 0;`（`:48,52-54`） |
| 6 | getter 计算"当前时钟 - 字段" | `jUptimeMillis = SystemClock.uptimeMillis() - lastXxxTimeMills;`（`:25,37,49,61`） | `clock() - timestamp`（`:57`） |

### B. 有意简化（lib 注释/文档中明示或合理 demo 化）

| # | 简化内容 | 原厂对应 | lib 取舍理由 |
|---|---|---|---|
| 1 | **API 可见性收窄到 internal** | 原厂 12 个方法全 `public static final`（`AnimSeqTimeStamp.java:22,34,46,58,70,80,90,100,110,120,130,140`） | lib 用 Kotlin `internal` 限定调用域；只有 `updateLastRecentFinishTime` 例外为 `public`（`:35`，与 review 03 §2.2 调用点对齐） |
| 2 | **Kotlin property 替代 Java getter** | `getTimeGapToLastStartAppTime()` 4 个 Java 方法 | lib 4 个 `internal val timeGapToLast*Time: Long get() = ...`（`:59-66`）—— 同一语义，更符合 Kotlin 风格 |
| 3 | **抽取私有 `gapTo(timestamp)`** | 原厂 4 个 getter 各内联 `SystemClock.uptimeMillis() - lastXxxTimeMills`（`:25,37,49,61`） | lib 统一走 `gapTo(timestamp)`（`:56-57`）—— 单一变化点，便于加日志/限流 |
| 4 | **clock 字段抽象为可注入 lambda** | 原厂硬编码 `SystemClock.uptimeMillis()`（8 处调用） | lib `@Volatile var clock: () -> Long = { SystemClock.uptimeMillis() }`（`:25-27`）—— **lib 独有增强**，JVM 单测可注入 `nanoTime` 而非依赖 `android.os.SystemClock` stub |
| 5 | **`gapTo(0L)` 显式哨兵 `Long.MAX_VALUE`** | 原厂 getter 无短路：`0L → uptimeMillis - 0 = uptimeMillis`（huge positive） | lib `if (timestamp == 0L) Long.MAX_VALUE else clock() - timestamp`（`:57`）—— 显式哨兵比隐式 huge number 更清晰；下游阈值比较（`> 500ms`）行为一致 |
| 6 | **缺失 3 个独立 reset 方法** | 原厂 `resetLastRecentFinishTime / resetLastRecentStartTime / resetLastLaunchTaskTime`（`:79-87, 89-97, 69-77`） | lib **完全缺失**这 3 个方法 —— 只有 `resetLastStartAppTime`（`:47-49`，internal）和合并的 `resetAllForTest`（`:51-54`，internal）。**调用方场景不存在**（lib 无对应 `Launcher / OplusBaseSwipeUpHandler / RemoteAnimationRunnerCompat`），简化合理 |

### C. 遗漏 / 偏差（未在 lib 注释中说明、且影响语义）

| # | 遗漏点 | 原厂证据 | 影响 |
|---|---|---|---|
| 1 | **缺失 12 处 `Log.d(TAG, ...)` 调试日志**（高可见性回归） | 原厂每个方法含 `Log.d(TAG, "updateLastStartAppTime: " + lastStartAppTimeMillis)` 等（`:113,123,133,143,71,81,91,101,23,35,47,59`）—— 12 处 Log 调用 | lib 完全无日志。原厂 log 表明这是**正式可观测 API**（不是 demo 内部辅助）；lib 拿掉后**生产侧无法 trace 时间戳事件**，debug 时只能翻 trace |
| 2 | **3 个独立 reset 方法彻底缺失**（API 不兼容） | `resetLastRecentFinishTime / resetLastRecentStartTime / resetLastLaunchTaskTime`（`:69-77, 79-87, 89-97`），原厂在 7+ 个 callsite 调用 | lib `resetAllForTest`（`:51-54`）**是 blast reset**，不是原厂单字段 reset 的等效物。若 lib 真被集成到 launcher 替代原 `AnimSeqTimeStamp`，**RemoteAnimationRunnerCompat / OplusBaseSwipeUpHandler 等模块的 callsite 无法编译** —— 必须为这 3 个方法加 `@JvmStatic` 形式 |
| 3 | **`clock` 字段是 public `var` 且无 setter 约束**（中风险：生产可被任意线程改写） | 原厂**无对应字段**：`SystemClock.uptimeMillis()` 在 8 处方法体内调用，**无法被外部覆盖** | lib `@Volatile var clock: () -> Long`（`:25-27`）—— `@Volatile` 保证单字段可见性，但**没有锁定"哪些线程可改"**。当前用法：`AnimationSeqHelperTest.kt:28` 仅在 `@Before` 单线程赋值；生产代码无 reassignment，OK。但**若未来给 `updateLastStartAppTime` 加 lambda 闭包捕获非 volatile 状态**，可见性将丢失（JMM volatile 只保证 field read/write，不保证 lambda 内部 happens-before） |
| 4 | **`resetAllForTest` 4 字段连写无锁**（低风险但语义有差） | 原厂没有此方法 —— 4 个独立 `resetLast*Time()`，每个独立 synchronized | lib `resetAllForTest`（`:51-54`）顺序 4 个裸赋值 —— **观察者可能看到撕裂的"半重置"状态**（field A 已 0L、field B 仍旧值）。当前仅 `@Before` 单线程调用，无 race；但若未来用作生产"全局重置"接口则需加锁 |
| 5 | **`gapTo(0L)` 哨兵值 vs 原厂隐式 `uptimeMillis`**（低风险，下游阈值比较时无差） | 原厂 `getTimeGapToLastStartAppTime()` 在 `lastStartAppTimeMillis=0L` 时返回 `SystemClock.uptimeMillis() - 0 = uptimeMillis`（开机以来毫秒数，通常很大） | lib 显式返回 `Long.MAX_VALUE`（`:57`）—— 调用方 `> 500ms` / `< 300ms` 比较结果**与原厂一致**；但若调用方做算术 `500 - gapTo()`（`AnimationSeqHelper.kt:75`），lib 返回 `500 - Long.MAX_VALUE = 负巨大值`，被 `maxOf(0L, delay)` 钳到 0；原厂返回 `500 - uptimeMillis`（同样负巨大值），同样钳到 0。**行为对齐** |
| 6 | **lib 无 `default INSTANCE` 字段访问形式**（Kotlin ↔ Java 互操作） | 原厂 `AnimSeqTimeStamp.INSTANCE` 暴露给 Java 调用方（`AnimSeqTimeStamp.INSTANCE` 是 Kotlin `object` 编译产物） | lib `object AnimSeqTimeStamp` 自动生成 `INSTANCE`，但 lib 模块内**没有任何 Java caller**（整个 lib 是 Kotlin）—— `INSTANCE` 实际未使用。**纯 demo 化** |
| 7 | **`updateLastRecentFinishTime` 是 public 其余 3 个 update 全 internal**（API 表面不对称） | 原厂 4 个 update 全 public | lib 唯一例外的 `updateLastRecentFinishTime`（`:35`，public）—— 对应 review 03 §2.2 调用点（`AppLauncher.java:107` + `AnimationSeqHelper` 间接）。其余 3 个 `internal`（`:31,39,43`）—— 对应原厂 callsite 都不在 lib 内，**简化合理**但**未来集成时需要 4 个全 public**（与原厂对齐） |
| 8 | **字段命名简化掉了时间单位后缀** | 原厂 `lastStartAppTimeMillis` / `lastRecentFinishTimeMills`（笔误）/ `lastRecentStartTimeMills` / `lastLaunchTaskTimeMills` | lib `lastStartAppTime / lastRecentFinishTime / lastRecentStartTime / lastLaunchTaskTime`（`:9-23`）—— 命名更干净，但**与原厂 JADX 还原字段名不一一对应**，debug 时与 OPPO 工程师对话需解释字段映射 |

---

## ③ 行为差异风险点（按严重度排序）

### ✅已修复（60bd048）— 风险 1（高，Bug 级）：`resetLastRecentFinishTime / resetLastRecentStartTime / resetLastLaunchTaskTime` 三个 reset 方法彻底缺失

**位置**：lib `AnimSeqTimeStamp.kt:31-66` 整文件

**问题**：
- 原厂 4 个独立 `synchronized` reset 方法（`AnimSeqTimeStamp.java:69-107`），全部 `public static final`，被原厂 7+ 处 callsite 调用：
  - `resetLastLaunchTaskTime`：`RemoteAnimationRunnerCompat.java:445`、`OplusOverviewCommandHelperImpl.java:455`、`OplusBaseSwipeUpHandler.java:10758`、`Launcher.java:4211,4457`、`LauncherAnimationRunner.java:569`、`DockIconView.java:235`
  - `resetLastRecentFinishTime`：`OplusOverviewCommandHelperImpl.java:626`、`RemoteAnimationRunnerCompat.java:625`、`OplusBaseSwipeUpHandler.java:10759`
  - `resetLastRecentStartTime`：`AppLauncher.java:108`、`RecentsViewAnimUtil.java:2803`
- lib **完全缺失**这 3 个方法，仅有：
  - `resetLastStartAppTime()`（`AnimSeqTimeStamp.kt:47-49`，internal）
  - `resetAllForTest()`（`:51-54`，internal，blast 重置 4 字段）
- **若 lib 被用作 `AnimSeqTimeStamp` 的替换实现**，原厂的 callsite 全部编译失败 —— 这是**集成性阻断**而非行为差异，但语义等价性必须以"完整 API surface"为前提。

**实际触发场景**：
- 当前 demo 模块：单进程单 AnimationSeqHelper 调用方，**不触发**（无 RemoteAnimationRunnerCompat / OplusBaseSwipeUpHandler / Launcher 等集成层）。
- 真实集成：迁移任何 callsite 都需要新方法。

**影响**：
- 当前 demo 无影响。
- **集成阻断** —— 真实 launcher 迁移时无法编译。

**修复**：补 3 个 `@JvmStatic @JvmName("resetLastRecentFinishTime")` 等价的 `internal fun resetLastRecentFinishTime() { lastRecentFinishTime = 0 }` / `resetLastRecentStartTime` / `resetLastLaunchTaskTime`，与原 `resetLastStartAppTime` 对齐（`:47-49`）。**修复成本：3 行**（每方法 1 行）+ 显式 `@JvmStatic @JvmName("resetLastXxxTime")` 注解（Java 互操作所需）= 共 ~12 行。

---

### ⚠️未修复（成本 1 行 + 注释 / 当前调用方行为一致）— 风险 2（高，Bug 级）：`gapTo(0L) → Long.MAX_VALUE` vs 原厂 `SystemClock.uptimeMillis()` 的隐式哨兵差异（生产侧 math 可能溢出）

**位置**：`AnimSeqTimeStamp.kt:56-57`

**问题**：
- 原厂：`getTimeGapToLastStartAppTime()` 在 `lastStartAppTimeMillis=0L` 时返回 `SystemClock.uptimeMillis() - 0L = uptimeMillis`（开机以来毫秒数，如 5 天 = 432000000ms）
- lib：`gapTo(0L) = Long.MAX_VALUE`（约 9.2 × 10¹⁸ms = 292 万年）
- 对**阈值比较**调用方（`> 500` / `< 300`）行为**完全一致**（两者都 > 阈值）。
- 对**算术运算**调用方（`AnimationSeqHelper.kt:75` `val delay = MAX_DELAY_TIME - AnimSeqTimeStamp.timeGapToLastRecentFinishTime`）：
  - 原厂：`500 - 432000000 = -431999500`（负数）
  - lib：`500 - Long.MAX_VALUE = -9223372036854775108`（极大负数）
  - **下游 `maxOf(0L, delay)` 都钳到 0** —— 行为一致。
- 但**若未来有调用方做 `gapTo() * N` 或 `gapTo() + offset`**，lib 的 `Long.MAX_VALUE` 会**立刻溢出**到负数（`Long.MAX_VALUE * 2 = -2`），原厂的 `uptimeMillis` 不会溢出（5 天 × 2 = 10 天，远小于 Long.MAX_VALUE）。

**实际触发场景**：
- 当前调用方只有 `AnimationSeqHelper.kt:62,65,75` 三处，都做阈值比较或被 `maxOf` 钳位 —— **不触发**。
- 未来若加入算术运算（`gapTo() * 2` / `gapTo() + someOffset`）会触发。

**影响**：
- 当前 demo 无影响。
- **未来集成时是潜在溢出源**。

**修复**：把 `gapTo(0L)` 哨兵改成 `Long.MAX_VALUE / 2`（约 4.6 × 10¹⁸），或直接返回 `Long.MAX_VALUE` 但在调用方显式 guard `(gapTo() == Long.MAX_VALUE) ? MAX : gapTo()`。**修复成本：1 行 + 文档注释**。

---

### ⚠️未修复（成本 5 行 / demo 化合理保留）— 风险 3（中）：`clock` 字段是 public `@Volatile var`，无 setter 约束

**位置**：`AnimSeqTimeStamp.kt:25-27`

**问题**：
- lib `@Volatile var clock: () -> Long = { SystemClock.uptimeMillis() }` —— `var`（非 `val`）且 public（Kotlin `object` 默认可见性），**任意线程、任意模块**都能 `AnimSeqTimeStamp.clock = { ... }`。
- `@Volatile` 保证"读 clock lambda ref"与"写 clock lambda ref"的 happens-before，**但不保证 lambda 内部闭包状态的可见性**。
- 当前用法：`AnimationSeqHelperTest.kt:28` 仅在 `@Before` 单线程赋值一次 → 安全。
- **未来风险**：若生产代码某处 `AnimSeqTimeStamp.clock = { someStatefulFn() }`，且 `someStatefulFn` 内部读非 volatile 状态，跨线程调用 `updateLast*Time` / `timeGapToLast*Time` 时可能读到不一致的闭包状态。

**实际触发场景**：
- 当前 demo 无 trigger（生产代码无 reassign）。
- 未来若给 clock 注入带状态的 lambda 会 trigger。

**影响**：
- 与原厂"硬编码时钟、不可注入"对比，lib 多一层灵活度也**多一层风险**。
- **demo 价值**：JVM 单测确实需要 clock 注入 —— 这是合理简化，不是 bug。

**修复**：把 `clock` 改成 `@Volatile private var _clock: () -> Long` + 显式 `@VisibleForTesting fun setClockForTest(fn: () -> Long)` setter，仅在测试模块可用。**修复成本：5 行**。

---

### ⚠️未修复（成本 3 行 / 当前 @Before 单线程调用无 race）— 风险 4（中）：`resetAllForTest` 4 字段连写无锁 —— 多字段撕裂快照窗口

**位置**：`AnimSeqTimeStamp.kt:51-54`

**问题**：
```kotlin
internal fun resetAllForTest() {
    lastStartAppTime = 0
    lastRecentFinishTime = 0
    lastRecentStartTime = 0
    lastLaunchTaskTime = 0
}
```
- 4 次 volatile 写**逐字段独立**（不同字段无 race），但**观察者**可能在写过程中读到"半重置"状态：field A=0、field B=旧值、field C=旧值、field D=旧值。
- 原厂没有对应方法（4 个独立 reset，每个原子）。
- 当前用法：`AnimationSeqHelperTest.kt:26` `@Before` 单线程调用 → 安全（没有 reader 在 race）。
- **未来风险**：若 `resetAllForTest` 被生产代码调用作"全局 reset"入口（如切换场景前后清状态），同时其他线程读 `timeGapToLastRecentFinishTime` 等 getter，将读到不一致状态 → 阈值比较可能误判（"刚 reset 过就判定 500ms 内"）。

**实际触发场景**：
- 当前 demo 无 trigger。
- 未来若给生产代码添加"resetAll"入口会 trigger。

**影响**：
- 当前 demo 无影响。
- 与 review 07 §③-风险 1 一致：500/300ms 防抖窗口误判。

**修复**：用 `synchronized(this)` 包整段，或拆为 4 个独立 `resetLast*Time()` 与原厂对齐（避免 blast reset 需求）。**修复成本：3 行**（synchronized 包装）或 9 行（拆 3 个独立 reset）。

---

### ⚠️未修复（成本 12 行 / demo 阶段不需要，集成时再补）— 风险 5（中）：缺失 12 处 `Log.d(TAG, ...)` 调试日志 —— 生产侧无 trace 能力

**位置**：`AnimSeqTimeStamp.kt` 整文件无 Log 调用

**问题**：
- 原厂 12 个方法**每个**都含 `Log.d(TAG, "updateLastStartAppTime: " + lastStartAppTimeMillis)` 等（`:113,123,133,143,71,81,91,101,23,35,47,59`），便于生产侧 trace 时间戳事件。
- lib **完全无 Log** —— 即使把 lib 集成到生产，也看不到任何时间戳事件记录。
- 原厂 Log 同时承担**调用频次观测**（多少次 update / reset 触发）和**事件时序关联**（最后一次 update 在哪个时间点）。

**实际触发场景**：
- 当前 demo 无影响（demo 通过 `Trace.kt` 输出 trace 而非 Log）。
- 真实集成后**无法 trace** AnimSeqTimeStamp 事件，必须依赖 `AnimationSeqHelper` 间接日志。

**影响**：
- 可观测性降级。
- 与 review 08 §③-风险 1 一致（trace 维度的可观测性差异）。

**修复**：在 4 个 update / 4 个 reset / 4 个 getter 各加 `android.util.Log.d("AnimSeqTimeStamp", "...")`。**修复成本：12 行**。

---

### ✔️保持简化（与原厂等价 happens-before）— 风险 6（低）：单字段 update / reset 的并发原子性在 OPPO 真实 callsite 下与 lib 表现不同

**位置**：`AnimSeqTimeStamp.kt:31-66`

**问题**：
- 原厂 callsite 跨线程分析（Grep 全部 callsite + 推断执行线程）：
  - `RemoteAnimationRunnerCompat.java:441-445,625-626` —— **animation thread**（RemoteAnimationRunner 在 ANIM_EXECUTOR）
  - `RecentsViewAnimUtil.java:2800-2803` —— **animation thread**（位于 RecentsView anim util）
  - `AppLauncher.java:106-108` —— **main thread**
  - `Launcher.java:2675` —— **main thread**
  - `OplusOverviewCommandHelperImpl.java:414,451-455,678` —— **main thread**
  - `QuickstepInteractionHandler.java:157` —— **main thread**
  - `DockIconView.java:234-326` —— **main thread**
  - `OplusOtherActivityInputConsumer.java:963-964` —— **gesture thread**（InputConsumer）
  - `StartCardActivityHelper.java:757,831,866` —— **main thread**
  - `IndicatorEntry.java:452` —— **main thread**
  - `OplusTaskViewImpl.java:1610-1611` —— **main thread**
  - `OplusBaseSwipeUpHandler.java:10757-10759` —— **animation thread**（位于 SwipeUpHandler）

  → **真实跨线程触发场景**：animation thread 的 `updateLastRecentFinishTime` / `updateLastRecentStartTime` 与 main thread 的 `getTimeGapToLastStartAppTime` 并发。

- 原厂：12 个方法全 `@JvmStatic synchronized`，**所有 update / reset / get 共享同一 monitor**（AnimSeqTimeStamp.class）。**绝对 happens-before**。
- lib：每个字段 `@Volatile`，每个方法**无锁**。**单字段 update / read 的 happens-before 通过 volatile 保证**（等价于原厂），但：
  1. **多字段序列**（如 reader 先调 `getTimeGapToLastStartAppTime` 再调 `getTimeGapToLastRecentFinishTime`）**没有原子性** —— 但原厂也没有（每个 getter 独立 synchronized block），所以**两者行为一致**。
  2. **同一字段的 update + read**：原厂与 lib 都有 happens-before（前者 via monitor，后者 via volatile），**行为一致**。
  3. **`gapTo` 的 `clock()` 与字段读的 race**：原厂 `SystemClock.uptimeMillis() - lastXxxTimeMills` 都在 synchronized 内（顺序固定）；lib `gapTo(t)` 先读字段（volatile）后调 `clock()`（lambda invoke）。**两个调用都是单字段读，JMM 不保证读字段与读 clock 的 happens-before** —— 但因为 `clock` 默认实现是 `SystemClock.uptimeMillis()`（platform 内部 volatile long），跨线程 clock() 本身有可见性保证，**实际等价**。

**实际触发场景**：
- animation thread 与 main thread 并发触发 —— review 07 §③-风险 1 已识别"500/300ms 防抖窗口误判"窗口。
- 但**该窗口在原厂也存在**（原厂每个方法独立 synchronized，无 multi-field atomicity）。

**影响**：
- **行为与原厂一致**（单字段 update / read 等价 happens-before）。
- review 07 §③-风险 1 的"撕裂快照" 在原厂不严格成立 —— 原厂**同样没有 multi-field atomicity**。
- **这不是 lib 的独立 bug**。

**修复**：无需修复。若担心 race 加 synchronized（与原厂对齐）：**修复成本：4 行**（每个 update 加 `synchronized(this)`，4 个方法）。

---

### ⚠️未修复（成本 3 个关键字替换 / 集成时再补）— 风险 7（低）：`updateLastRecentFinishTime` 是 public，其余 3 个 update 是 internal —— API 不对称

**位置**：`AnimSeqTimeStamp.kt:31-45`

**问题**：
- lib 中只有 `updateLastRecentFinishTime`（`:35`，public）对外暴露，其余 `updateLastStartAppTime / updateLastRecentStartTime / updateLastLaunchTaskTime` 全 `internal`（`:31,39,43`）。
- 原厂 4 个 update 全 `public static final`。
- 公开 API 不对称可能让外部使用者误以为"只能 update lastRecentFinishTime"。

**实际触发场景**：
- 当前 demo 无外部 caller（lib 内部使用）。
- 集成时若需要外部触发其余 3 个 update，需先提升 visibility。

**影响**：
- demo 场景无影响。
- **集成时 API 不兼容** —— 但**当前不在集成路径上**。

**修复**：把 `updateLastStartAppTime / updateLastRecentStartTime / updateLastLaunchTaskTime` 改成 public，或统一为 `internal`（含 `updateLastRecentFinishTime`）。**修复成本：3 个关键字替换**。

---

### ✔️保持简化（命名更干净，避开原厂笔误 `Mills`）— 风险 8（提示）：字段命名简化（去 `Millis`/`Mills` 后缀）影响与 OPPO 工程师对话的字段映射

**位置**：`AnimSeqTimeStamp.kt:9-23`

**问题**：
- 原厂 `lastStartAppTimeMillis / lastRecentFinishTimeMills / lastRecentStartTimeMills / lastLaunchTaskTimeMills` —— 4 个字段名
- lib `lastStartAppTime / lastRecentFinishTime / lastRecentStartTime / lastLaunchTaskTime` —— 4 个字段名（去后缀）
- 字段类型都是 `Long`（表示 uptimeMillis），命名差异**不影响语义**，但**debug 时与 OPPO 工程师对话需解释字段映射**（特别是 `lastRecentFinishTimeMills` 这个原厂笔误字段）。

**实际触发场景**：
- 当前无影响。
- 集成时若要对接 OPPO 工具/日志，需建立命名映射表。

**影响**：
- 命名更干净是 lib 的优势（与原厂笔误 `Mills` 对比）。
- 与原厂的偏差**形式而非语义**。

**修复**：无需修复 —— 可作为文档注释保留（`@JvmField` + 与原厂字段名对应注释）。

---

## ④ 回移建议

### 4.1 值得补进 lib 的（性价比高）

| # | 改动 | 理由 | 工作量 |
|---|---|---|---|
| 1 | ✅已修复（60bd048）— **补 3 个独立 reset 方法**（`resetLastRecentFinishTime / resetLastRecentStartTime / resetLastLaunchTaskTime`，与 `resetLastStartAppTime` 对齐） | **集成阻断修复** —— 原厂 7+ 处 callsite 依赖；`resetAllForTest` 不是等效物（blast reset vs 字段 reset） | **3 行 × 3 方法 = 9 行** + 显式 `@JvmStatic @JvmName("resetLastXxxTime")` Java 互操作注解（每方法 ~4 行）= 共 ~21 行 |
| 2 | ⚠️未修复（成本 3 个关键字替换 / 集成时再补）— **统一 4 个 update 为 public**（与原厂对齐） | 当前 lib `updateLastRecentFinishTime` public、其余 3 个 internal —— 与原厂 4 个全 public 不一致。集成时 API 不兼容 | **3 个关键字替换** |
| 3 | ⚠️未修复（成本 1 行 + 注释 / 当前调用方都做阈值比较或 maxOf 钳位，行为一致）— **`gapTo(0L)` 哨兵改成 `Long.MAX_VALUE / 2` 或在调用方显式 guard** | 防止未来算术运算溢出（`gapTo() * 2` 会从 `Long.MAX_VALUE` 翻成 -2） | **1 行 + 注释** |
| 4 | ⚠️未修复（成本 5 行 / demo 化合理保留）— **`clock` 字段封装为 `@VisibleForTesting` setter** | 当前 `clock` 是 public `var`，无 setter 约束；可注入性是 JVM 单测需要，但生产侧不应能改 | **5 行**（私有 `_clock` + `@VisibleForTesting fun setClockForTest`） |
| 5 | ⚠️未修复（成本 3 行 / 当前 @Before 单线程调用，无 race）— **`resetAllForTest` 加 `synchronized(this)`** | 防止未来"全局 reset"生产入口下读者看到撕裂的 4 字段快照 | **3 行**（synchronized 包装） |
| 6 | ⚠️未修复（成本 12 行 / demo 通过 Trace.kt 足够，集成时再补）— **每个方法加 `Log.d("AnimSeqTimeStamp", ...)`**（12 处） | 生产侧 trace 能力补齐；与 review 08 §③-风险 1 一致 | **12 行** |

### 4.2 建议保持简化的

| # | 保留简化 | 理由 |
|---|---|---|
| 1 | ✔️保持简化 — **`@Volatile` 字段 + 无锁读写**（相对原厂 `@JvmStatic synchronized`） | 单字段 update/read 的 JMM happens-before via volatile 与 monitor 等价；lib 无锁读性能更好；review 07 §②-B-5 已确认 |
| 2 | ✔️保持简化 — **`gapTo` 私有抽取** | 4 个 getter 走同一函数，未来加日志/限流只需改一处。原厂内联 4 次 |
| 3 | ✔️保持简化 — **`clock` 字段可注入**（封装为 setter 后） | JVM 单测必备 —— `SystemClock.uptimeMillis()` 在 JVM stub 下永远返回 0，无法做时间窗测试。`AnimationSeqHelperTest.kt:28` 注入 `nanoTime` 是合理增强 |
| 4 | ✔️保持简化 — **Kotlin `object` 替代 Java `INSTANCE` + private constructor** | 编译产物等价；Kotlin 风格更地道 |
| 5 | ✔️保持简化 — **字段命名去 `Millis/Mills` 后缀**（保留与原厂偏差） | 命名更干净 + 避开原厂 `lastRecentFinishTimeMills` 笔误 |
| 6 | ✔️保持简化 — **`gapTo(0L) → Long.MAX_VALUE` 哨兵** | 当前调用方全做阈值比较（`> 500` / `< 300`），行为与原厂一致 |
| 7 | ✔️保持简化 — **缺失 12 处 `Log.d` 调试日志**（若不补 §4.1-6） | demo 场景通过 `Trace.kt` 输出 trace 已足够；**仅在集成到生产侧时补** |
| 8 | ✔️保持简化 — **`updateLastRecentFinishTime` public、其余 3 个 update internal**（若不补 §4.1-2） | 当前 demo 模块内已够用；lib 内部不暴露的字段对齐"internal by default" 风格 |
| 9 | ✔️保持简化 — **`resetAllForTest` 4 字段连写无锁**（若不补 §4.1-5） | 当前 `@Before` 单线程调用，无 race |
| 10 | ✔️保持简化 — **`AnimSeqTimeStamp.kt:8-10` 文档注释保留** | 已明示"demo 简化版" + 对应原厂 `com.android.systemui.shared.system.AnimSeqTimeStamp`，便于 navigation |

---

## 附：关键证据速查

| 论断 | 证据（lib 路径 / 原厂 文件:行） |
|---|---|
| lib 4 字段 `@Volatile` 单字段读写 | `AnimSeqTimeStamp.kt:9-23` ↔ `AnimSeqTimeStamp.java:13-16`（裸 long，无 volatile） |
| lib 写路径裸赋值无锁 | `AnimSeqTimeStamp.kt:31-45`（4 个 `updateLast*Time`） ↔ `AnimSeqTimeStamp.java:109-147`（4 个 `@JvmStatic synchronized`） |
| lib 读路径无锁 | `AnimSeqTimeStamp.kt:59-66`（4 个 `internal val timeGapToLast*Time`） ↔ `AnimSeqTimeStamp.java:21-67`（4 个 `@JvmStatic synchronized` getter） |
| lib 缺 3 个独立 reset 方法 | `AnimSeqTimeStamp.kt:31-66`（仅 `resetLastStartAppTime :47-49` + `resetAllForTest :51-54`） ↔ `AnimSeqTimeStamp.java:69-107`（4 个 `resetLast*Time`） |
| lib `resetAllForTest` 是 blast reset，原厂无对应 | `AnimSeqTimeStamp.kt:51-54` ↔ 原厂无对应方法 |
| 原厂 4 个 update 全 public | `AnimSeqTimeStamp.java:110,120,130,140`（`@JvmStatic public static final synchronized`） |
| lib `updateLastRecentFinishTime` public、其余 internal | `AnimSeqTimeStamp.kt:31,35,39,43` |
| 原厂 12 处 `Log.d(TAG, ...)` | `AnimSeqTimeStamp.java:23,35,47,59,71,81,91,101,113,123,133,143` |
| lib 0 处 Log | `AnimSeqTimeStamp.kt` 全文 |
| 原厂硬编码 `SystemClock.uptimeMillis()` 8 处 | `AnimSeqTimeStamp.java:25,37,49,61,112,122,132,142` |
| lib `clock` 字段可注入 | `AnimSeqTimeStamp.kt:25-27`（`@Volatile var clock: () -> Long`） |
| lib `gapTo(0L) → Long.MAX_VALUE` 哨兵 | `AnimSeqTimeStamp.kt:56-57` |
| 原厂 `getTimeGapTo*` 无 0L 短路 | `AnimSeqTimeStamp.java:25,37,49,61`（`SystemClock.uptimeMillis() - lastXxxTimeMills` 直接减） |
| 原厂 callsite 用例（验证真实跨线程触发） | `RemoteAnimationRunnerCompat.java:441-445,625-626`（anim thread）；`RecentsViewAnimUtil.java:2800-2803`（anim thread）；`OplusBaseSwipeUpHandler.java:10757-10759`（anim thread） ↔ `AppLauncher.java:106-108`、`Launcher.java:2675`、`OplusOverviewCommandHelperImpl.java:414` 等（main thread） |
| 原厂 12 处 callsite 调用 resetLast*Time | `Launcher.java:4211,4457`、`LauncherAnimationRunner.java:568-569`、`OplusBaseSwipeUpHandler.java:10757-10759`、`RemoteAnimationRunnerCompat.java:441-445,625-626`、`OplusOverviewCommandHelperImpl.java:454-455,626`、`AppLauncher.java:108`、`RecentsViewAnimUtil.java:2803`、`DockIconView.java:234-235`、`OplusOtherActivityInputConsumer.java:964` |
| lib resetAllForTest 调用 | `AnimationSeqHelperTest.kt:26`（`@Before` 单线程） |
| lib clock 注入用例 | `AnimationSeqHelperTest.kt:28`（`AnimSeqTimeStamp.clock = { System.nanoTime() / 1_000_000 }`） |
| 原厂字段名笔误 | `AnimSeqTimeStamp.java:14`（`lastRecentFinishTimeMills` 少一个 `l`） |
| lib 时钟下游阈值比较调用 | `AnimationSeqHelper.kt:62,65`（`> MAX_DELAY_TIME` / `> MAX_INTERCEPT_GESTURE_DELAY_TIME`）；`AnimationSeqHelper.kt:75`（`MAX_DELAY_TIME - gapTo(...)`） |

---

## 与已有 review 的交叉引用

- **review 07 §①-类对应表 row 9**：AnimSeqTimeStamp 的并发原语对比总览（`@Volatile` vs `@JvmStatic synchronized`）—— 本报告**专注此单类的字段级契约**，不重复展开整体并发原语对比。
- **review 07 §②-B-5**：保真度评估中"AnimSeqTimeStamp 用 @Volatile 字段 + 纯 lock-free 读"的简化归类 —— 本报告**深挖该简化的具体字段语义差异**。
- **review 07 §③-风险 1**：行为差异风险点中"AnimSeqTimeStamp 多字段并发读写的撕裂快照"—— 本报告 §③-风险 6 重新审视该风险，**结论**：原厂**同样没有 multi-field atomicity**（每个 getter 独立 synchronized block），因此"撕裂快照"在原厂也存在，**不是 lib 的独立 bug**。
- **review 03 §2.2-5**：AnimationSeqHelper 的 `canFinishRecent / canInterceptGesture` 阈值比较 —— 本报告**补全**这些阈值的来源（`getTimeGapToLastRecentFinishTime` / `getTimeGapToLastStartAppTime`）以及哨兵语义差异（`Long.MAX_VALUE` vs `uptimeMillis`）。
- **review 08 §③-风险 1**：trace 维度的可观测性 —— 本报告 §③-风险 5 在日志维度独立列出 12 处 Log.d 的缺失。

## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **60bd048** — 3 个 reset 方法补齐：resetLastRecentFinishTime / resetLastRecentStartTime / resetLastLaunchTaskTime

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核

（批次4 / 2026-09-09 逐项打标状态，与正文内联标记一致）

- §③ 风险1 → ✅已修复（60bd048：3 reset 补齐）
- §③ 风险2 → ⚠️未修复（gapTo(0L) 哨兵溢出）
- §③ 风险3 → ⚠️未修复（clock public var）
- §③ 风险4 → ⚠️未修复（resetAllForTest 无锁）
- §③ 风险5 → ⚠️未修复（12 处 Log.d 缺失）
- §③ 风险6 → ✔️保持简化（volatile 等价）
- §③ 风险7 → ⚠️未修复（update 可见性不对称）
- §③ 风险8 → ✔️保持简化（命名）
- 4.1-1 → ✅已修复（60bd048）
- 4.1-2 → ⚠️未修复（3 关键字 public）
- 4.1-3 → ⚠️未修复（gapTo 哨兵防溢）
- 4.1-4 → ⚠️未修复（clock setter 封装）
- 4.1-5 → ⚠️未修复（synchronized）
- 4.1-6 → ⚠️未修复（Log.d 12 处）
- 4.2-1 → ✔️保持简化
- 4.2-2 → ✔️保持简化
- 4.2-3 → ✔️保持简化
- 4.2-4 → ✔️保持简化
- 4.2-5 → ✔️保持简化
- 4.2-6 → ✔️保持简化
- 4.2-7 → ✔️保持简化
- 4.2-8 → ✔️保持简化
- 4.2-9 → ✔️保持简化
- 4.2-10 → ✔️保持简化

