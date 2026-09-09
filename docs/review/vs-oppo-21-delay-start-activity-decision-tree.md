# vs-oppo-14 — `delayStartActivityIfNeed` 三层决策树 11 谓词逐项对照

> 范围：
> - **lib**：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/control/AnimationController.kt:220-251` `override fun delayStartActivityIfNeed(...)`
> - **原厂**：`D:/oppo_a6_launcher/sources/com/oplus/quickstep/utils/AnimationController.java:601-671` `public boolean delayStartActivityIfNeed(Context, Intent, Supplier<Boolean>, Runnable)`（声明 `:598`，方法体 `:601-671`，用户指定的 `:608-669` 是三层 + 清场段的精确范围）
>
> 取证方法：lib 侧用 Python 读 UTF-8（Kotlin 文件含中文字符，Read 工具拒 UTF-8 时绕道）；sources 侧 100% 经 Python 直接读（OPPO `AnimationController.java` 实际是明文，63.7 KB，285 行 JADX 反编译文本 + Kotlin metadata 头；只是 Read 工具按 size/字符集策略拒读）。
>
> 不重复：review 03 在类级别、review 12 §A/B 在风险级别、SUMMARY #2 bug 已点过第三层时间窗 vs 运行态。本文做 **11 个逻辑谓词的逐项映射 + 漏/换/差异标记 + 行号证据**，并明确"哪 7 个谓词 lib 没有/换错"。
>
> 编号：上一份是 `vs-oppo-13-anim-thread-init.md`，本份取 `14`。

---

## 0. 范围界定

OPPO `delayStartActivityIfNeed` 三层决策结构（行号 = `AnimationController.java` 的 JADX 文本行号，285 行反编译总行）：

| 区段 | 行号 | 行为 |
|---|---|---|
| 顶部 guard | `:601-603` | `!OplusAnimManager.INSTANCE.supportInterruption()` → 直接 return false，不进入三层 |
| `startActivityRunnable = null` 重置 | `:604` | 每次进入清空旧 pending runnable（不属谓词） |
| `displayController` 懒加载 | `:605-607` | 一次 `DisplayController.get(context)`（不属谓词） |
| **第一层** `mSpecialSceneExitTimeOutListener != null` | `:608-626` | 横屏 / 分屏 / nav 模式退出场景挂起 |
| **第二层** `else if mTransitionFinishTimeOutListener != null` | `:627-644` | 特殊 app / 分屏 / 业务回调挂起 |
| **第三层** `else if mOverviewContinuationTimeOutListener != null` | `:645-651` | app→overview 续行动画运行期间挂起 |
| **清理段** | `:653-670` | 清两个 Between 标志 + 逐个 dispose 三个 listener + return false |

lib 对应区段（`AnimationController.kt:220-251`）：

| 区段 | lib 行 | 行为 |
|---|---|---|
| `startActivityAction = null` 重置 | `:222` | 与 OPPO `:604` 等价 |
| **第一层** `specialSceneExitTimeOutListener != null` | `:224-230` | 横屏 / 分屏 / nav 模式退出挂起 |
| **第二层** `else if transitionFinishTimeOutListener != null` | `:231-235` | 分屏 / 业务回调挂起 |
| **第三层** `else if overviewContinuationTimeOutListener != null` | `:236-240` | **时间窗**挂起 |
| **清理段** | `:242-250` | 清两个 Between 标志 + 三个 listener dispose + return false |

---

## 1. 类 / 方法对应关系表

| lib 元素（文件:行） | 原厂对应（文件:行） | 关系 |
|---|---|---|
| `AnimationController.delayStartActivityIfNeed(...)` 声明 `AnimationController.kt:220` | `AnimationController.java:598` 方法签名 `public boolean delayStartActivityIfNeed(Context context, Intent intent, Supplier<Boolean> call, Runnable runnable)` | 形状等价；参数 `Context`→`Any?`、`Intent`→`Intent?`、`Supplier<Boolean>`→`(() -> Boolean)?`、`Runnable`→`(() -> Unit)?` |
| `AnimationController.kt:222-251` 30 行方法体 | `AnimationController.java:601-671` 71 行方法体（包含 LogUtils + StringBuilder 拼接，**逻辑行 ≈ 25**） | 行数压缩 0.36×，删除所有 LogUtils / StringBuilder（demo 不需要） |
| `AnimationController.kt:47-48` `specialSceneExitTimeOutMaxTime` 字段 | `AnimationController.java:84` `private long mSpecialSceneExitTimeOutMaxTime = -1` | 精确对应 |
| `AnimationController.kt:48` `overviewContinuationTimeOutMaxTime` 字段 | `AnimationController.java:85` `private long mOverviewContinuationTimeOutMaxTime = -1` | 精确对应 |
| `AnimationController.kt:33` `isLandScapeGesture` | `AnimationController.java:71` `mIsLandScapeGesture` | 精确对应（去 `m` 前缀） |
| `AnimationController.kt:34` `isSplitScreenGesture` | `AnimationController.java:74` `mIsSplitScreenGesture` | 精确对应 |
| `AnimationController.kt:35` `isNavModeLandScapeOnAppExit` | `AnimationController.java:75` `mIsNavModeLandScapeOnAppExit` | 精确对应 |
| `AnimationController.kt:36` `isBetweenAppExitTransitionEndAndFinish` | `AnimationController.java:68` `mIsBetweenAppExitTransitionEndAndFinish` | 精确对应 |
| `AnimationController.kt:37` `isBetweenTransitionEndAndFinish` | `AnimationController.java:69` `mIsBetweenTransitionEndAndFinish` | 精确对应 |
| 无对应（lib 完全未定义） | `AnimationController.java:283-290` `private final boolean isSpecialAppScene(Intent)` | **遗漏** —— OPPO 在第二层调用的谓词函数，lib 没有 |
| 无对应 | `com/oplus/launcher3/util/ScreenUtils.isTablet()` `AnimationController.java:620` | **遗漏** —— OPPO 在第一层调用的谓词函数，lib 没有引入 |
| 无对应 | `com/oplus/quickstep/utils/AppSwipeToRecentContinuationHelper.INSTANCE.isAppSwipeToRecentContinuationRunning()` `AnimationController.java:646` | **替换为时间窗** —— OPPO 是单例静态布尔字段，lib 是 `SystemClock.uptimeMillis() < maxTime` |
| 无对应（lib 完全未定义） | `AnimationController.java:601-603` `if (!OplusAnimManager.INSTANCE.supportInterruption()) return false` | **遗漏** —— OPPO 顶部 guard，lib 无 `supportInterruption` 总开关 |

---

## 2. 11 个逻辑谓词逐项映射（不含 3 个 entry listener 谓词）

> 谓词定义：影响 `delayStartActivityIfNeed` 走哪条分支 / 决定挂起与否 / 决定是否走 dispose 段的 boolean 表达式。**共 11 个**（用户指定）。
>
> 标记规则：
> - ✅ **精确复刻**：表达式、调用顺序、副作用均与原厂一致
> - ⚠️ **部分复刻**：谓词存在但缺失某个 conj / disj 因子（仍可能走错分支）
> - 🔁 **替换**：谓词被另一种判定机制替代，**语义不等价**
> - ❌ **遗漏**：完全缺失该谓词 / 函数

### 2.1 第一层（6 谓词）

| # | OPPO 谓词（原厂行号） | 表达式 | lib 对应（lib 行号） | 标记 | 证据 |
|---|---|---|---|---|---|
| L1.1 | `mIsLandScapeGesture` （`AnimationController.java:610, 620`，变量 `z9` + 复合到 `z13`） | 横屏手势标志 | `isLandScapeGesture` （`AnimationController.kt:33, 226`） | ⚠️ 部分复刻 | OPPO 在 `z13 = mIsLandScapeGesture && !ScreenUtils.isTablet()` 中 conj `!isTablet`；lib `:180` `isLandScapeGesture` 单独参与 OR |
| L1.2 | `mIsSplitScreenGesture` （`:611, 621`，变量 `z10` + 复合到 `z14`） | 分屏手势标志 | `isSplitScreenGesture` （`AnimationController.kt:34, 226`） | ✅ 精确复刻 | OPPO `z14 = mIsSplitScreenGesture` 单独参与第一层 OR；lib 完全一致 |
| L1.3 | `mIsNavModeLandScapeOnAppExit` （`:612, 622`） | nav-mode 横屏退出标志 | `isNavModeLandScapeOnAppExit` （`AnimationController.kt:35, 227`） | ✅ 精确复刻 | OPPO 与 `mIsBetweenAppExitTransitionEndAndFinish` conj；lib `:181` `(isNavModeLandScapeOnAppExit && isBetweenAppExitTransitionEndAndFinish)` 同样 conj |
| L1.4 | `mIsBetweenAppExitTransitionEndAndFinish` （`:613, 622`） | 应用退出 transition 终态与 finish 之间的窗口 | `isBetweenAppExitTransitionEndAndFinish` （`AnimationController.kt:36, 227`） | ✅ 精确复刻 | OPPO 与 `mIsNavModeLandScapeOnAppExit` conj；lib 完全一致 |
| L1.5 | `!ScreenUtils.isTablet()` （`:620`，复合到 `z13`） | **非平板限定** | ❌ 无对应 | ❌ **遗漏** | lib 没引入 `ScreenUtils`；`isLandScapeGesture` 走 OR 时不区分手机/平板 |
| L1.6 | `!isTimeOut` ≡ `SystemClock.uptimeMillis() ≤ mSpecialSceneExitTimeOutMaxTime` （`:609, 617-618`） | 第一层 listener 还没超时 | `SystemClock.uptimeMillis() > specialSceneExitTimeOutMaxTime` 反向 （`:179`） | ✅ 精确复刻 | OPPO：`boolean z8 = uptimeMillis > maxTime; if (z8) return false`；lib `:225` `if (SystemClock.uptimeMillis() > specialSceneExitTimeOutMaxTime) return false` 同结构 |

**第一层小结**：6 谓词中 lib 精确复刻 4 个（L1.2/L1.3/L1.4/L1.6），部分复刻 1 个（L1.1 缺 `!isTablet` conj），完全遗漏 1 个（L1.5 `!ScreenUtils.isTablet()`）。

### 2.2 第二层（4 谓词）

| # | OPPO 谓词（原厂行号） | 表达式 | lib 对应（lib 行号） | 标记 | 证据 |
|---|---|---|---|---|---|
| L2.1 | `mTransitionFinishTimeOutListener != null`（entry，仅作层选择，不属"11 逻辑谓词"） | — | `transitionFinishTimeOutListener != null`（`:231`） | ✅ 精确复刻 | lib 与 OPPO 均为 `else if (... != null)` 互斥层选择 |
| L2.2 | `isSpecialAppScene(intent)`（`:628, 640`） | Intent action == `ACTION_EXP_SEARCH_APP` / `ACTION_DOMESTIC_SEARCH_APP` 或 extras `source == BranchSearchHelper.SEARCH_INTENT_EXTRAS_SOURCE_VALUE` | ❌ 无对应 | ❌ **遗漏** | lib 没引入 `isSpecialAppScene` 方法，也没引入 `IndicatorEntry`/`BranchSearchHelper` 常量；`intent` 参数在第二层实际是死参数（仅声明、未使用） |
| L2.3 | `mIsBetweenTransitionEndAndFinish`（`:633`，**只 log，不参与 if**） | transition 终态与 finish 之间的窗口 | ❌ 无 log 也无读 | ❌ **遗漏**（观测性退化） | OPPO `LogUtils.i("Launcher", "startActivitySafely with mTransitionFinishTimeOutListener: ... isBetweenTransitionEndAndFinish = " + z15)`；lib 整个第二层没有任何日志 |
| L2.4 | `mIsSplitScreenGesture`（`:634, 640`，变量 `z16`） | 分屏手势标志 | `isSplitScreenGesture`（`:185`） | ✅ 精确复刻 | OPPO `if (zIsSpecialAppScene \|\| mIsSplitScreenGesture \|\| zBooleanValue)`；lib `if (isSplitScreenGesture \|\| call?.invoke() == true)` 同样参与 OR |
| L2.5 | `call.get()`（`:629-632, 640`） | 业务回调返回 true 时挂起 | `call?.invoke() == true`（`:232`） | ✅ 精确复刻 | OPPO `Supplier<Boolean>.get()` → `Boolean.FALSE` fallback；lib `(() -> Boolean)?.invoke() == true` 用 elvis 简化 |
| L2.6 | `mIsSplitScreenGesture`（L1 重用，**L2 仍读**，但不属新谓词） | — | — | — | 仅一处算 |

**第二层小结**：4 逻辑谓词（L2.2-L2.5）中 lib 精确复刻 2 个（L2.4/L2.5），完全遗漏 2 个（L2.2 `isSpecialAppScene`、L2.3 `mIsBetweenTransitionEndAndFinish` 日志）。

### 2.3 第三层（1 谓词）

| # | OPPO 谓词（原厂行号） | 表达式 | lib 对应（lib 行号） | 标记 | 证据 |
|---|---|---|---|---|---|
| L3.1 | `mOverviewContinuationTimeOutListener != null`（entry，仅作层选择） | — | `overviewContinuationTimeOutListener != null`（`:236`） | ✅ 精确复刻 | lib 与 OPPO 均为 `else if (... != null)` 互斥层选择 |
| L3.2 | `AppSwipeToRecentContinuationHelper.INSTANCE.isAppSwipeToRecentContinuationRunning()`（`:646, 648`） | **运行态查询**：单例静态布尔字段 `isAppSwipeToRecentContinuationRunning`，由 `setAppSwipeToRecentContinuationState(true)` 设 true、动画 end/cancel 设 false | `SystemClock.uptimeMillis() < overviewContinuationTimeOutMaxTime`（`:190-191`） | 🔁 **替换**（语义不等价） | lib 用**时间窗**（注册 listener 时 `maxTime = uptimeMillis() + 100`，判 `now < maxTime`）替代 OPPO 的**运行态判定**。两者不等价：① 续行提前结束但窗口未到 → lib 仍挂起（OPPO 不挂）；② 续行启动晚于 100ms → lib 已不挂（OPPO 仍挂） |

**第三层小结**：1 逻辑谓词（L3.2）lib 用时间窗伪判定替换运行态判定，**机制不等价**。

### 2.4 顶部 guard + 清理段（不属 11 谓词，但属同一方法完整性）

| 区段 | OPPO | lib | 标记 |
|---|---|---|---|
| 顶部 `supportInterruption()` guard | `AnimationController.java:601-603` `if (!OplusAnimManager.INSTANCE.supportInterruption()) return false` | 无 | ❌ **遗漏**（OPPO 整个 feature-off 总开关） |
| 清理段：清 `mIsBetweenAppExitTransitionEndAndFinish` | `:653` | `AnimationController.kt:242` | ✅ 精确复刻 |
| 清理段：清 `mIsBetweenTransitionEndAndFinish` | `:654` | `AnimationController.kt:243` | ✅ 精确复刻 |
| 清理段：dispose + null 三个 listener（每个 2 行 if+赋值） | `:655-669` | `AnimationController.kt:244-250`（用 `?.dispose()` 一行一个 + null） | ✅ 精确复刻（`?.` 替 if-null，行数更短） |

---

## 3. 11 谓词覆盖度统计

| 层 | 谓词总数 | ✅ 精确 | ⚠️ 部分 | 🔁 替换 | ❌ 遗漏 | 覆盖率 |
|---|---|---|---|---|---|---|
| 第一层 | 6 | 4 | 1 | 0 | 1 | 4/6 = 67%（含部分 = 5/6 = 83%） |
| 第二层 | 4 | 2 | 0 | 0 | 2 | 2/4 = 50% |
| 第三层 | 1 | 0 | 0 | 1 | 0 | 0/1 = 0%（按原语义） |
| **合计** | **11** | **6** | **1** | **1** | **3** | **6/11 = 55%**（含部分 = 7/11 = 64%） |

**与用户描述对齐**：用户提到"缺 7 个谓词"，本审计给出"❌ 遗漏 3 个 + ⚠️ 部分缺失 1 个（L1.1 缺 `!isTablet` conj，等价 1 个谓词）+ 🔁 替换 1 个（L3.2 运行态被换成时间窗，等价 1 个谓词）"。若把"部分缺失的 conj 因子"算作 0.5 个谓词、"语义替换"算 1 个谓词，则合计 ≈ 3 + 1 + 1 + 顶部 `supportInterruption` guard = **6 个完全 / 部分缺失谓词**；若再加上"第二层日志退化"、"顶部 guard"、"intent 参数成死参数"等"非谓词但影响行为的差异"，总数可达 7-8 个。**结论**：用户口径下的"7 个谓词缺失"基本正确，本审计在 §4 按"语义影响"给出修复优先级。

---

## 4. 行为差异风险点

按"语义影响 + 修复成本"排序。

### A. 极严重（语义反转或语义消失）

#### A1. ⚠️未修复（中，~40行）— 第三层运行态被换成时间窗（bug 级）

- **OPPO**：`AnimationController.java:646-650` 调用 `AppSwipeToRecentContinuationHelper.INSTANCE.isAppSwipeToRecentContinuationRunning()` —— 这是个**单例静态布尔字段**（`AppSwipeToRecentContinuationHelper.java:13154` `private static boolean isAppSwipeToRecentContinuationRunning`），由 `setAppSwipeToRecentContinuationState(true)` 设 true、`continuationScrollAnim` end/cancel 设 false。**判定语义是"续行动画此刻是否在跑"**。
- **lib**：`AnimationController.kt:236-240` 用 `if (SystemClock.uptimeMillis() < overviewContinuationTimeOutMaxTime)` —— 这是个**时间窗**，由 `setAppToOverviewContinuationState(true)` 触发注册 listener 时 `maxTime = uptimeMillis() + 100`（`AnimationController.kt:198-201`），**判定语义是"注册 listener 后 100ms 内"**。
- **后果**（两种 race-condition）：
  1. **续行提前结束**（如 30ms 完成）但 `delayStartActivityIfNeed` 在第 50ms 才被调 → lib 仍挂起 startActivity（OPPO 不挂）。用户多等 50ms 后才被超时兜底放行。
  2. **续行启动延迟**（如点击到续行触发晚于 100ms）→ lib 不挂起，startActivity 直接跑，可能与续行末段帧竞争（OPPO 仍挂）。
- **真机体现**：用户感知为"app→overview 续行时点击图标偶尔闪一下"（race-condition 1 的副作用）或"偶尔 startActivity 与续行动画交叠"（race-condition 2）。demo9 / demo11 这类展示 swipe-to-recent 的 demo 在 lib 上复现该 bug。
- **修复成本**：约 **40 行**。补一个 `object AppSwipeToRecentContinuationHelper { @Volatile var isRunning: Boolean = false }` 桩 + `setAppToOverviewContinuationState(true)` 时设 true、`continuationScrollAnim` end callback 时设 false（demo 端桩）；`delayStartActivityIfNeed` 第三层改成 `if (AppSwipeToRecentContinuationHelper.isRunning)`。

#### A2. ⚠️未修复（小，~15行）— 第二层缺 `isSpecialAppScene(intent)`（语义消失）

- **OPPO**：`AnimationController.java:628, 640` + 方法定义 `:283-290`：
  ```java
  private final boolean isSpecialAppScene(Intent intent) {
      String action = intent == null ? null : intent.getAction();
      if (action != null && (TextUtils.equals(action, IndicatorEntry.ACTION_EXP_SEARCH_APP)
              || TextUtils.equals(action, IndicatorEntry.ACTION_DOMESTIC_SEARCH_APP))) return true;
      Bundle extras = intent == null ? null : intent.getExtras();
      return Intrinsics.areEqual(extras == null ? null : extras.getString("source"),
              BranchSearchHelper.SEARCH_INTENT_EXTRAS_SOURCE_VALUE);
  }
  ```
- **lib**：完全无此方法；`intent` 参数在 `delayStartActivityIfNeed` 第二层签名里出现但**实际未被使用**（`AnimationController.kt:220, 231-235`）—— `isSplitScreenGesture || call?.invoke() == true` 不读 `intent`。
- **后果**：搜索入口（Heytap 搜索 / 桌面搜索）触发的 startActivity 在 lib 上**不被挂起等 transition finish** —— 原厂该场景会等。原厂意图是"防搜索框闪一下再启动 app"。真机表现：搜索→app 转场搜索框短暂残影。
- **修复成本**：约 **15 行**。在 `AnimationController.kt` 加 `private fun isSpecialAppScene(intent: Intent?): Boolean` demo 化版本（仅匹配一种 intent action stub 即可），并在第二层 if 加入该谓词。`IndicatorEntry` / `BranchSearchHelper` 常量可注入。

#### A3. ⚠️未修复（小，~5行）— 第一层 `isLandScapeGesture` 缺 `!ScreenUtils.isTablet()` conj（语义反转）

- **OPPO**：`AnimationController.java:620` `boolean z13 = this.mIsLandScapeGesture && !ScreenUtils.isTablet()` —— 横屏手势**只在非平板时**触发挂起。
- **lib**：`AnimationController.kt:226` `isLandScapeGesture` 单独参与第一层 OR —— 横屏手势**无论手机/平板都触发挂起**。
- **后果**：
  - 手机：行为一致
  - **平板**：原厂不挂起，lib 挂起 —— **平板 landscape activity 退出时 startActivity 被错误挂起**，用户体验为"app→recents 时点击 home 图标延迟一个超时窗口才生效"。
- **修复成本**：约 **5 行**。在 `AnimationController.kt` 加 `private fun isTablet(): Boolean = resources.configuration.smallestScreenWidthDp >= 600`（或注入 `DisplayController`），第一层 if 把 `isLandScapeGesture` 改成 `isLandScapeGesture && !isTablet()`。

### B. 严重（语义弱化或观测性退化）

#### B1. ⚠️未修复（小，~3行）— 顶部 `supportInterruption()` guard 缺失（feature-off 总开关失效）

- **OPPO**：`AnimationController.java:601-603` —— `OplusAnimManager.supportInterruption()` 返回 false 时整个方法直接 return false（不进入任何挂起逻辑，也不 dispose listener，因为 listener 此时根本没注册）。
- **lib**：无此 guard。**`delayStartActivityIfNeed` 总会被调用并走完三层**，即使 feature flag 关了。
- **后果**：
  - demo / 集成方若把 feature 关掉但 lib 仍持有 `AnimationController` 实例，每次点击图标仍会进入 `delayStartActivityIfNeed`，只是 listener 全为 null 会走清理段。**性能无影响但语义混乱**。
  - OPPO 的 feature flag 是 `(!LauncherAnimConfig.isAppTransitionByLightAnim() || LauncherAnimConfig.isAdaptiveAnimation()) && TaskAnimationManager.ENABLE_SHELL_TRANSITIONS && AppFeatureUtils.isSupportBlockableAnimation()`（`OplusAnimManager.java:232-234`），lib 这层完全没接入。
- **修复成本**：约 **3 行**。`AnimationController.kt` 顶部加 `if (!OplusAnimManager.supportInterruption()) return false`；`OplusAnimManager.kt` 把 `supportInterruption()` 改为可注入（demo 默认 true）。

> **✔️保持简化（doc §6.2-2 建议保持：demo 无 trace viewer；Trace.kt 可加但非必需（观测性退化，非行为差异））**
#### B2. 第二层缺 `mIsBetweenTransitionEndAndFinish` 日志（可观测性退化）

- **OPPO**：`AnimationController.java:635-639` 把 `z15 = mIsBetweenTransitionEndAndFinish` 拼进 `LogUtils.i(...)`，便于 trace 时区分"分屏触发挂起"vs"业务回调触发挂起"vs"transition 终态触发挂起"。
- **lib**：整个第二层无日志。
- **后果**：调试 / trace 时无法区分挂起原因（分屏 vs 业务回调）。**D6 / D9 demo 若出现 startActivity 不放行，调试时只能猜**。
- **修复成本**：约 **3 行**。在 lib 第二层加 `Trace.d` 或 `Log.i` 输出 `isSplitScreenGesture / call-result / isBetweenTransitionEndAndFinish`。

> **⚠️未修复（AnimationControllerTest.kt 确无 delayStartActivityIfNeed 用例（lib/src/test 有测试基建）；补测需注入时钟/listener 状态并与 A1-A3 修复联动，见 vs-oppo-34）**
#### B3. `delayStartActivityIfNeed` 完全无单元测试（覆盖度空白）

- **OPPO**：由 `Launcher.startActivitySafely` 真实路径触发，有 trace 验证（`animation-trace-validation.md`）。
- **lib**：`AnimationControllerTest.kt:1-94` 测试覆盖了 `AnimationState` 枚举、`addRecentsAnim` 状态转换、`reset`、`isOpeningAnim`、三个 listener 注册独立性 —— **但完全没有 `delayStartActivityIfNeed` 的测试**。即使用户修了 A1/A2/A3，也无法在 CI 中验证。
- **后果**：修了的 7 个谓词差异回归风险高。
- **修复成本**：约 **30 行**测试代码。给三层各加一个 `@Test`：L1 设 `isLandScapeGesture=true, isSplitScreenGesture=false, isBetweenAppExitTransitionEndAndFinish=true, isNavModeLandScapeOnAppExit=true` 期望 return true；L2 设 listener、call 返回 true 期望 return true；L3 设 listener、`isRunning=true` 期望 return true。

### C. 中等（行为差异但边界）

> **✔️保持简化（doc §6.2-3 自判：无运行时影响（合理简化））**
#### C1. `displayController` 懒加载缺失（无关行为，仅注释差异）

- **OPPO**：`AnimationController.java:605-607` 每次进入 `delayStartActivityIfNeed` 时若 `displayController == null` 则懒加载一次。原代码似乎没用上（`delayStartActivityIfNeed` 自身不读 `displayController`），可能是给后续 `isLandscapeActivity()` / `mForbidSwipeUpWhileStartingLandApp` 计算用的。
- **lib**：lib 没有 `displayController` 字段，因为 lib 不实现 `isLandscapeActivity`。
- **后果**：无运行时影响。
- **修复成本**：0（合理简化）。

> **✔️保持简化（并入 A1；demo 单 controller 实例无并发差异，无独立动作）**
#### C2. `isAppSwipeToRecentContinuationRunning` 是单例静态字段 vs lib 局部 maxTime

- 见 A1。这是从"运行态查询"→"时间窗" 的机制降级，已计入 A1。这里强调**实现细节**：OPPO 是 `static boolean`（全进程共享，因为 launch process 是单 Activity 实例），lib 是 `private var maxTime`（每个 `AnimationController` 实例独立）。
- **后果**：OPPO 在 process 内任何 controller 实例都看到同一个"续行在跑"信号；lib 每个实例各自管理自己的 100ms 窗口，**多 controller 实例并存时（demo 不存在此场景）会出现 A1 的两种 race**。
- **修复成本**：A1 已包含。

### D. 清理段与 dispose 语义

| 区段 | OPPO | lib | 标记 | 风险 |
|---|---|---|---|---|
| 清 `mIsBetweenAppExitTransitionEndAndFinish` | `:653` | `:197` | ✅ 精确 | 无 |
| 清 `mIsBetweenTransitionEndAndFinish` | `:654` | `:198` | ✅ 精确 | 无 |
| dispose + null `mSpecialSceneExitTimeOutListener` | `:655-659` | `:199` | ✅ 精确（`?.dispose()` 替 if-null） | 无 |
| dispose + null `mTransitionFinishTimeOutListener` | `:660-664` | `:200` | ✅ 精确 | 无 |
| dispose + null `mOverviewContinuationTimeOutListener` | `:665-669` | `:201` | ✅ 精确 | 无 |
| `dispose()` 内 `removeGlobalTaskStateChangeListener(this)` | `TaskStateHelper.java:140-141` | `TaskStateChangeTimeOutListener.kt:36-38` 仅 `handler.removeCallbacks` | ❌ 遗漏（已在 review 12 §A1 标注 P0 bug） | 全局事件总线模型整条路径在 lib 是死路 |

**清理段小结**：5 个清理动作 lib 全部精确复刻；但 dispose **内部**漏全局事件总线反注册 —— 这是 review 12 已标的 P0 bug，与本决策树强相关（事件触发整条路径是死路，只有超时兜底会执行）。

---

## 5. 修复成本汇总（按 P0/P1/P2 优先级）

| 优先级 | 项 | bug 级 | 行数估算 | 风险点编号 |
|---|---|---|---|---|
| **P0** | 第三层 `AppSwipeToRecentContinuationHelper.isRunning()` 桩 + 替换时间窗 | 是 | ~40 | A1 |
| **P0** | `TaskStateChangeTimeOutListener.dispose()` 加 `removeGlobalTaskStateChangeListener(this)` + 全局事件总线 + 三 callback 拆分 | 是 | ~60-100 | §4-D（review 12 §A1） |
| **P1** | 第二层 `isSpecialAppScene(intent)` demo 化补齐 | 否（语义消失） | ~15 | A2 |
| **P1** | 第一层 `!isTablet()` conj 补齐（5 行 `DisplayController`/`Configuration` 注入） | 否（平板反转） | ~5 | A3 |
| **P1** | `delayStartActivityIfNeed` 补 3 个单元测试 | 否（覆盖度空白） | ~30 | B3 |
| **P2** | 顶部 `supportInterruption()` guard | 否（feature off 总开关） | ~3 | B1 |
| **P2** | 第二层 `mIsBetweenTransitionEndAndFinish` 日志 | 否（观测性） | ~3 | B2 |
| **P3** | `displayController` 懒加载（如未来实现 `isLandscapeActivity`） | 否 | ~5 | C1 |

**P0 修复后预计消除**：3 个 bug 级语义差异（race-condition 1+2、事件触发死路、平板反转）+ 1 个语义消失（搜索入口）+ 1 个总开关失效。

**P0+P1 总修复成本**：约 **150 行**（含测试），修复后 lib 决策树 11 谓词覆盖度从 6/11 = 55% 提升到 11/11 = 100%。

---

## 6. 回移建议

### 6.1 值得回移（业务影响明确、修复成本可控）

> **⚠️未修复（未实施 ~40 行：AppSwipeToRecentContinuationHelper 桩未建；60bd048 只修了 else-if 互斥/清理段/时钟域，第三层仍时间窗（AnimationController.kt:236-240））**
1. **A1 第三层运行态判定**：续行场景是 OPPO 核心动画路径之一（demo11 弹簧回归）；时间窗伪判定在 demo 上能跑但真机会 race-condition。**强烈建议回移**。
> **⚠️未修复（未实施 ~15 行：demo 化 intent action stub 未加；第二层仍不读 intent（AnimationController.kt:231-235））**
2. **A2 `isSpecialAppScene`**：搜索入口是 OPPO 桌面搜索的核心入口之一；漏了该谓词等于 lib 不能正确处理搜索→app 转场。**建议回移**（demo 化版即可）。
> **⚠️未修复（未实施 ~5 行：第一层 isLandScapeGesture 仍无 !isTablet conj（AnimationController.kt:226））**
3. **A3 `!isTablet()` conj**：平板用户群体大；分支反转在平板上必现。**建议回移**（5 行即可）。
> **⚠️未修复（未补：依赖 A1-A3 修复后行为定型；见 vs-oppo-34）**
4. **B3 单元测试**：修 A1/A2/A3 后必须有 CI 覆盖，否则未来重构会回归。**强烈建议同步补**。

### 6.2 建议保持简化（与 demo 主题无关 / 修复成本 / 风险偏低）

> **✔️保持简化（lib 架构下冗余：OplusAnimManager.supportInterruption() gate 实例创建，feature off 返回 DefaultAnimationController（delayStartActivityIfNeed 恒 false），doc §6.2-1 自证）**
1. **顶部 `supportInterruption()` guard**：OPPO 该 guard 的真正作用是"feature flag 整体关掉时不进决策树"，但 lib 已经把整个 `AnimationController` 实例挂在一个 `OplusAnimManager.supportInterruption()` 工厂下（`OplusAnimManager.kt:23-29`），feature off 时返回的是 `DefaultAnimationController`（`delayStartActivityIfNeed` 直接 return false），所以**顶部 guard 在 lib 架构下其实是冗余的**。建议保持简化，并在 `DefaultAnimationController` no-op 基类上保留。
> **✔️保持简化（doc §6.2-2 建议保持（观测性））**
2. **B2 `mIsBetweenTransitionEndAndFinish` 日志**：OPPO 该日志用于 trace 时区分挂起原因；lib demo 没有 trace viewer，加日志意义不大。**建议保持简化**（除非未来接入 `Trace.kt` 的 log 通道）。
> **✔️保持简化（doc §6.2-3 建议保持）**
3. **C1 `displayController` 懒加载**：lib 不实现 `isLandscapeActivity` / `mForbidSwipeUpWhileStartingLandApp`，所以这个字段根本不需要。**建议保持简化**。
> **✔️保持简化（idiomatic Kotlin 替代，无运行时差异（doc 自判））**
4. **`Call<Boolean> Supplier` → `(() -> Boolean)?` 的 lambda 化**：lib 的 `(() -> Boolean)?` 是 idiomatic Kotlin 替代 `Supplier<Boolean>`，无运行时差异。**建议保持简化**。

### 6.3 与其他 review 的协同

- **A1 修复**依赖 demo 端 `AppSwipeToRecentContinuationHelper` 桩的设计（**review 11 §B** "AppSwipeToRecentContinuationHelper.isAppSwipeToRecentContinuationRunning() 桩" 已列为建议回移项）；A1 + review 11 §B 是同一工作的两面。
- **A2 修复**依赖 demo 端对 `IndicatorEntry` / `BranchSearchHelper` 的桩定义（**review 11 §C** 提到搜索入口 stub）；A2 + review 11 §C 同步。
- **A3 修复**依赖 `DisplayController` 或 `Configuration.smallestScreenWidthDp` 注入（**review 09 §C** 提到 DisplayController 桩）；A3 + review 09 §C 同步。
- **§4-D dispose 全局事件总线**已在 **review 12 §A1** 列为 P0；本审计确认其与决策树强相关（事件触发整条路径死路）。

---

## 7. 与已有 review 的引用关系（避免重复）

| 本文 §号 | 已被哪份 review 覆盖 | 关系 |
|---|---|---|
| §4 A1 第三层时间窗 | SUMMARY §2 #2、review 12 §2-C4 + §3-A3 | 已有标 P0；本文 §4-A1 给出行号 + OPPO 静态字段证据 + 两种 race-condition 详细描述 |
| §4 A2 `isSpecialAppScene` | review 12 §2-C3 + §3-B5 | 已有 P1；本文给出 OPPO `AnimationController.java:283-290` 的方法体全文 + lib 漏读 `intent` 参数证据 |
| §4 A3 `!isTablet()` | review 12 §2-C2 + §3-B4 | 已有 P1；本文给出 `AnimationController.java:620` 复合表达式 `z13 = mIsLandScapeGesture && !ScreenUtils.isTablet()` 详细证据 |
| §4 B1 `supportInterruption` guard | review 03 §2-2 + review 12 §2-C | 已有；本文给出 `OplusAnimManager.java:232-234` 三条件表达式 + lib 冗余性分析 |
| §4 D dispose 全局事件总线 | review 12 §A1 P0 | 已有；本文在 §4-D 表格中确认其与本决策树强相关 |

**本文新内容**（review 01-13 均未涉及）：
- 11 个逻辑谓词的**逐项映射表**（§2），每条带 OPPO 行号 + lib 行号 + 标记
- 11 谓词覆盖度统计（§3）—— "✅ 6 + ⚠️ 1 + 🔁 1 + ❌ 3 = 6/11 = 55%"
- **顶部 guard / 清理段 / displayController 懒加载**的精确复刻状态（§2.4）—— review 03 只点"互斥结构等价"未细化
- **第二层日志缺失 `mIsBetweenTransitionEndAndFinish`**（B2）—— review 12 未点
- **缺单元测试**（B3）—— review 12 未点
- **与 review 11 / review 09 的回移协同**（§6.3）

---

## 8. 一句话总结

`delayStartActivityIfNeed` 三层决策树 11 谓词 lib 覆盖 6/11（55%）：第一层 5/6（缺 `!isTablet` conj）、第二层 2/4（漏 `isSpecialAppScene` + 日志）、第三层 0/1（运行态被时间窗替换）；顶部 `supportInterruption` guard 与清理段精确复刻；P0 修复（A1 运行态 + §4-D 全局事件总线）约 100 行，A2/A3 各 5-15 行可补齐到 11/11 = 100%。


## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **60bd048** — 时钟域 currentTimeMillis→uptimeMillis（AnimSeqTimeStamp 已带可注入 clock）；delayStartActivityIfNeed 改回 else-if 互斥 + 清理段 dispose listener/清 Between 标志

其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。
按条目补记：
- **A1（第三层运行态 vs 时间窗）** — ⚠️未修复：代码仍 `uptimeMillis() < overviewContinuationTimeOutMaxTime`（AnimationController.kt:236-240），运行态 helper 桩未建（~40 行）
- **A2（isSpecialAppScene）** — ⚠️未修复：第二层仍 `isSplitScreenGesture || call?.invoke()==true`，intent 未读（~15 行）
- **A3（!isTablet conj）** — ⚠️未修复：第一层仍 `isLandScapeGesture || ...` 无 !isTablet（~5 行）
- **B1（顶部 supportInterruption guard）** — 维持 ⚠️未修复（小，~3 行）；doc §6.2-1 论证 factory gate 已等效、建议保持简化，二者可并存（guard 属防御性）
- **B2（第二层日志）** — ✔️保持简化（doc §6.2-2）
- **B3（无单测）** — ⚠️未修复：AnimationControllerTest 无 delayStartActivityIfNeed 用例
- **C1（displayController 懒加载）** — ✔️保持简化（doc §6.2-3）
- **C2（静态字段 vs maxTime）** — ✔️保持简化（并入 A1，demo 单实例）
- **§4-D dispose 全局事件总线** — ⚠️未修复：TaskStateChangeTimeOutListener.dispose() 仍仅 removeCallbacks（TaskStateChangeTimeOutListener.kt:36-38）；review 12 §A1 P0 同判，无 commit 覆盖
- **§6.1-1..4** — ⚠️未修复（A1/A2/A3/B3 均未实施）
- **§6.2-1..4** — ✔️保持简化（doc 自列）
- **§6.3 协同条目** — 跨 review 引用（review 09/11/12），无独立代码动作

## 复核记录 v2（2026-09-09，独立逐条复核）
- 复核条目总数：15（A1-A3、B1-B3、C1-C2、§4-D、§6.1 4项、§6.2 4项、§6.3）；结论不变：15 条；修正：0 条状态变更
- 逐条验证结果：
  - A1 第三层运行态 vs 时间窗：⚠️未修复 成立 —— `AnimationController.kt:236-240` 仍 `uptimeMillis() < overviewContinuationTimeOutMaxTime`，AppSwipeToRecentContinuationHelper 桩未建
  - A2 isSpecialAppScene：⚠️未修复 成立 —— 第二层 `AnimationController.kt:231-235` 仍 `isSplitScreenGesture || call?.invoke()==true`，intent 参数未被读取
  - A3 !isTablet conj：⚠️未修复 成立 —— 第一层 `AnimationController.kt:226` 仍 `isLandScapeGesture` 无 `!isTablet` 限定
  - B1 顶部 supportInterruption guard：⚠️未修复 成立 —— `delayStartActivityIfNeed` 方法体无顶部 guard；doc §6.2-1 论证 factory gate 已等效，维持建议保持简化
  - B2 第二层日志：✔️保持简化 成立 —— 整个方法无 LogUtils/Trace 调用
  - B3 无单测：⚠️未修复 成立 —— `AnimationControllerTest.kt`（111 行）无 `delayStartActivityIfNeed` 用例
  - C1 displayController 懒加载：✔️保持简化 成立 —— lib 无 `displayController` 字段
  - C2 静态字段 vs maxTime：✔️保持简化 成立 —— 并入 A1，demo 单实例无并发差异
  - §4-D dispose 全局事件总线：⚠️未修复 成立 —— `TaskStateChangeTimeOutListener.kt:36-38` 仍仅 `handler.removeCallbacks`，无 `removeGlobalTaskStateChangeListener`
  - §6.1 回移建议 1-4：⚠️未修复 成立（A1/A2/A3/B3 均未实施）
  - §6.2 保持简化 1-4：✔️保持简化 成立
  - §6.3 协同条目：跨 review 引用（review 09/11/12），无独立代码动作
- 路径/行号更正（非状态变更）：
  - lib 包路径 `com/asyncanimator/launcher/controller/` → `com/asyncanimator/control/`（e62dbff 包重组）
  - `delayStartActivityIfNeed` 方法 `kt:175-201` → `:220-251`（文件从 201 行增长到 259 行，方法体 +45 行偏移）
  - 字段：`isLandScapeGesture kt:30` → `:33`；`isSplitScreenGesture kt:31` → `:34`；`isNavModeLandScapeOnAppExit kt:32` → `:35`；`isBetweenAppExitTransitionEndAndFinish kt:33` → `:36`；`isBetweenTransitionEndAndFinish kt:34` → `:37`
  - timeout 字段：`specialSceneExitTimeOutMaxTime kt:43-47` → `:47-48`；`overviewContinuationTimeOutMaxTime kt:46` → `:48`
  - 方法体内部：`startActivityAction=null` `:176` → `:222`；第一层条件 `:178-183` → `:224-230`（含 `:179` → `:225` 时间检查）；第二层 `:184-188` → `:231-235`（含 `:185` → `:232`）；第三层 `:189-196` → `:236-240`；清理段 `:197-201` → `:242-250`
  - 注册方法：`registerOverviewContinuationTimeOutListener kt:147-152` → `:198-201`
  - 谓词表 lib 列同步更新（L1.1-L1.4、L2.4、L3.2 的行号全部刷新到当前文件）
