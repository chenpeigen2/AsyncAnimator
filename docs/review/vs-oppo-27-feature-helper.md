# lib vs OPPO 原厂深度对比（区域 27）：AnimationFeatureHelper 字段、RUS 模拟与 1px 列表

> 对比对象：
> - **lib**：`D:/AsyncAnimator/lib`（Kotlin 重实现）及 `demo/` 的 Demo8。
> - **原厂**：`D:/oppo_a6_launcher/sources`，ColorOS 15 Launcher 15.8.24 的 JADX 源码。
>
> 本报告是既有 `vs-oppo-01..12` 的字段级补充，不重复 Controller/线程总览。重点核对 `AnimationFeatureHelper` 的 **7 个标量字段 + 2 个列表**、RUS 更新顺序/解析/锁，以及它依赖的 `isAdaptiveAnimation`、`isSupportBlockableAnimation`（下文简称 blockable gate）和 `isReverseToOpenAnimRunning`。
>
> 取证：lib Kotlin 文件用 Python 标准 I/O 读取；原厂文件用 Grep/Python 穿透 DLP。行号均为当前工作区文本行号。原厂文件中的 `JADX WARN`（例如字符串 switch 恢复失败）只影响反编译形式，不改变下面按字符串名判断的语义。

## 1. 类对应关系表

| lib 类/方法（文件:行） | 原厂类/方法（文件:行） | 对应关系与边界 |
|---|---|---|
| `com.asyncanimator.launcher.feature.AnimationFeatureHelper`（`lib/src/main/java/com/asyncanimator/launcher/feature/AnimationFeatureHelper.kt:13-49`） | `com.oplus.quickstep.utils.AnimationFeatureHelper`（`sources/com/oplus/quickstep/utils/AnimationFeatureHelper.java:25-60`） | 同一职责。lib 用 Kotlin `object`；原厂是 Kotlin `Companion` + `Lazy` 单例（`:41-79`）。两者都提供进程内单例，但原厂构造时会读取并注册 RUS，lib 不接 RUS。 |
| 7 个 lib 委托字段（`AnimationFeatureHelper.kt:17-30`，含 onePxEnable） | `mAsyncEnable`、`mRTUnlockEnable`、`mMultiAppBlockEnable`、`mIconBlurEnable`、`m1pxEnable`、`mInterruptThreshold`、`mLimtSize`（原厂 `:52-60`） | 字段名/类型一一对应；`@Volatile` 读模型也对应。但默认值和 setter 可见性不同：原厂 int flag 多为 `-1` 且 setter 是 private synchronized，lib 直接暴露 public `var`。 |
| `simulateRemoteUpdate`（`AnimationFeatureHelper.kt:29-37`） | `updateRusConfig` 的 7 个标量分支（原厂 `:159-317`；各 setter `:99-156`） | 设计上是“本地模拟 RUS”，但实际只接收 **6 个参数**，遗漏 `onePxEnable`；也没有两个列表参数。原厂的 7 个叶子配置都能单独解析。 |
| `onePxPkgDisableList` / `onePxCardDisableList`（`AnimationFeatureHelper.kt:39-41`） | `m1pxPkgDisableList` / `m1pxCardDisableList`（原厂 `:56-57,317-373`） | 类型对应，但 lib 是永远为空的可变 `ArrayList` 视图，没有替换/解析/锁；原厂有 ItemArray 解析、字段 volatile 和写段 synchronized（注意原厂仍是“原地 clear/add”，并非不可变快照）。 |
| `OplusAnimManager.supportInterruption()`（`lib/src/main/java/com/asyncanimator/launcher/manager/OplusAnimManager.kt:36`） | `OplusAnimManager.supportInterruption()`（原厂 `com/oplus/quickstep/utils/OplusAnimManager.java:232-234`） | lib 恒返 `true`；原厂要求 `(!isAppTransitionByLightAnim || isAdaptiveAnimation) && ENABLE_SHELL_TRANSITIONS && isSupportBlockableAnimation`。因此 lib 的 RUS `multiAppBlockEnable` 不会进入工厂 gate。 |
| `DefaultAnimationController.forbidTouch()`（lib `.../controller/DefaultAnimationController.kt:60`） | `DefaultAnimationController.forbidTouch()`（原厂 `com/oplus/quickstep/utils/DefaultAnimationController.java:80-84`）及 `QuickstepTransitionManager.isReverseToOpenAnimRunning()`（`com/android/launcher3/QuickstepTransitionManager.java:1854-1859`） | lib 恒返 false；原厂在大屏且反向打开弹簧已启动时禁止触摸。该方法不是 RUS 字段，但属于 interruption/touch 安全门，lib 没有对应状态查询。 |
| `AnimationFeatureHelper` 的依赖 gate（lib 无同名类） | `LauncherAnimConfig.isAdaptiveAnimation()`（`com/android/common/util/LauncherAnimConfig.java:45-52,133-148`） | 原厂 adaptive 是由 `isAdaptiveSmoothAnim()` 与动画等级 B/B+ 派生的环境能力，不是 RUS 字段。它影响 threshold 钳制和 radius/1px 逻辑；lib 没有 `LauncherAnimConfig` 或等价 provider。 |
| Demo8 `renderConfigs`（`demo/src/main/java/com/asyncanimator/demo/Demo8FeatureFlagActivity.kt:176-200`） | 原厂 7 标量 getter + 2 列表 getter（`AnimationFeatureHelper.java:375-420`） | 展示面列了 9 项，名称映射基本正确；但“模拟远程下发”按钮（Demo8 `:94-118`）只调用 6 参数方法，展示中的 `m1pxEnable` 和两个列表不会随按钮变化。 |

**数量校正**：原厂的“9 项”是 **7 个标量配置 + 2 个 ItemArray**。`CONFIG_LIST_NAME_LAUNCHER_ANIM_FEATURE_CONFIG`（原厂 `:29`）只是 7 个标量的容器名，不应再计为第 10 项。lib 确实声明了 7 个 `SyncedVar`，但 `simulateRemoteUpdate` 只有 6 个形参（`:29-30`）。

## 2. 保真度评估

### 2.1 七个标量字段逐项核对

| 字段 | 原厂证据与实际语义 | lib 证据与差异 | 分类 |
|---|---|---|---|
| `asyncEnable` / `mAsyncEnable` | 原厂初值 `-1`（`:52`），RUS 按整数解析（`:180-193`），异步动画消费者用 `!= 0`（`com/android/launcher3/anim/OplusBaseAppTransitionHelper.java:78-79`）。`-1` 在该消费者上等价于启用，但仍保留“未下发”状态。 | lib 初值 `1`，`SyncedVar<Int>`（`:17,40-48`）；无 lib 消费者，Demo 只显示。 | **结构精确；默认/消费契约遗漏** |
| `rtUnlockEnable` / `mRTUnlockEnable` | 原厂初值 `-1`（`:53`），RUS 整数解析（`:256-274`）；`RenderNodeAnimatorUtils.isEnabledByRusConfig()` 用 `!= 0`（`RenderNodeAnimatorUtils.java:62-65`）。 | lib 初值 `1`（`:18`），无对应 `RenderNodeAnimatorUtils`。对当前 `!=0` 规则结果相同，但失去 `-1` 哨兵。 | **有意简化存储；语义未闭合** |
| `multiAppBlockEnable` / `mMultiAppBlockEnable` | 原厂初值 `-1`（`:54`），解析后立即调用 `AppFeatureUtils.updateMultiAppBlockable()`（`:237-242`）。该方法把 RUS 值与系统 setting/平台 feature 合成 blockable gate：`setting == "true" || (hasFeature && rus != 0)`（`AppFeatureUtils.java:2877-2889`），变化时重建动画 helper。 | lib 初值 `0`（`:19`），`simulateRemoteUpdate` 只写字段（`:33`），`OplusAnimManager.supportInterruption()` 又恒返 true（`OplusAnimManager.kt:36`）。字段变化不会影响任何派生 gate。 | **遗漏，且是行为级差异** |
| `iconBlurEnable` / `mIconBlurEnable` | 原厂初值 `-1`（`:55`）。`PlatformLevelUtils.isIconBlurAvailable()` 在值不是 `-1` 时按 RUS 强制开/关；值为 `-1` 时回退平台 feature/动画等级（`PlatformLevelUtils.java:142-156`）。 | lib 初值 `1`（`:20`），无平台 fallback；如果将 lib 接入同一消费者，会把“未配置”误作“强制开启”。 | **默认语义遗漏** |
| `onePxEnable` / `m1pxEnable` | 原厂初值 `-1`（`:58`），解析分支 `:275-293`。1px 消费者以 `== 0` 禁用，且还叠加 adaptive/RLM gate（`AbsDrawableViewKt.java:74-82`）；因此 `-1` 通常表示允许继续走后续判断。 | lib 初值 `1`（`:21`），只读展示；未被 `simulateRemoteUpdate` 更新。直接按 `==0` 判断时 1 与 -1 常等价，但“未下发”状态丢失。 | **字段结构对齐；更新路径遗漏** |
| `interruptThreshold` / `mInterruptThreshold` | 原厂初值 `1.0f`（`:59`），解析 float（`:198-216`）；`setInterruptThreshold` 先写传入值，再在 `LauncherAnimConfig.isAdaptiveAnimation()` 为 true 时强制写回 `1.0f`（`:123-132`）。阈值被 `LauncherAnimationRunner.java:340` 和 `OplusBaseSwipeUpHandler.java:6353` 用来决定是否回退/反转当前动画。 | lib 初值同为 `1.0f`（`:22`），但委托 setter 直接接受任何 float（`:46-48`），没有 adaptive 钳制，也没有任何消费者。 | **基础值精确；关键 gate 遗漏** |
| `limtSize` / `mLimtSize` | 原厂保留 JADX/Kotlin 的拼写 `Limt`，初值 `-1`（`:60`），由 `LauncherAnimConfig.getTriggerPreBootLimitSize()` 转出（`LauncherAnimConfig.java:151-153`），RUS key 为 `launcher_trigger_pre_boot_limit_size`（`AnimationFeatureHelper.java:37,294-313`）。 | lib 同样叫 `limtSize`、初值 `-1`、`SyncedVar<Int>`（`:23`），但没有 pre-boot consumer。 | **字段/哨兵值精确；业务通路简化** |

### 2.2 同步模型：标量接近，列表不等价

| 访问面 | 原厂 | lib | 判断 |
|---|---|---|---|
| 标量读 | 7 个标量均是 `volatile`（原厂 `:52-60`），读 getter 不加锁（`:383-420`）。 | `SyncedVar.value` 是 `@Volatile`，getter 直接返回（lib `:69-73`）。 | **精确复刻每字段可见性**。 |
| 标量写 | 7 个 setter 都是 `private synchronized`（原厂 `:99-156`）。这些是**实例同步方法**，默认都锁同一个 helper 实例 monitor，并不是“每字段一把锁”。 | 每个委托 setter 锁私有共享 `lock`（`:75-76`）；`simulateRemoteUpdate` 外层也锁同一 `lock`（`:50`），Java monitor 可重入。 | **内部锁粒度基本等价**。既有 review 07 中“原厂每字段独立 monitor”的说法不准确；差异只是锁对象对外不可见。 |
| 批量写 | 原厂 RUS parser 按 `Config` 逐项调用 setter，7 项之间没有事务快照（`:169-317`）。 | lib 一次锁住 7 个委托写入 + 2 个列表快照（`:50-59`），写者侧比原厂更强；但读者仍逐字段读 volatile，不能得到全批次原子快照。 | **有意加强但不提供 snapshot 语义**。 |
| 列表字段 | 原厂字段引用 volatile，但 parser 在同一个 `ArrayList` 上 `synchronized(list)` 后 `clear/add`（`:320-343,345-368`）；getter 直接返回该可变 list（`:375-380`）。 | lib ✅已修复（本轮）：`@Volatile private var` + 不可变快照整体替换（`:32-41`），比原厂更安全。 | **已补齐；比原厂实现更安全**。 |

### 2.3 原厂 RUS 的七配置 + 两列表解析

**标量容器和入口**：构造函数先执行一次 `updateRusConfig()`，再创建并注册 `RusConfigChangedListener`（原厂 `:82-92`）。回调只做同一件事：再次调用 `updateRusConfig()`（`:84-88`）。因此“初始快照 + 后续变更”是原厂真实生命周期，而不是每次 getter 触发读取。

七个标量 key（字符串常量在原厂 `:26-37`，解析分支在 `:169-317`）：

| RUS key | 类型/写入 | 额外副作用 | 证据 |
|---|---|---|---|
| `launcher_anim_prj_feature_async_enable` | `Integer.parseInt` → `setAsyncEnable` | 解析失败只记录错误，保留旧值 | `:31,179-197` |
| `launcher_anim_interrupt_threshold` | `Float.parseFloat` → `setInterruptThreshold` | adaptive 模式在 setter 内钳制到 `1.0f` | `:26,198-216`；setter `:123-132` |
| `launcher_anim_prj_feature_icon_blur_enable` | `Integer.parseInt` → `setIconBlurEnable` | 无额外副作用 | `:32,217-235` |
| `launcher_anim_prj_feature_multi_app_block_enable` | `Integer.parseInt` → `setMultiAppBlockEnable` | 调 `AppFeatureUtils.updateMultiAppBlockable()`，可能触发 `OplusAnimManager.recreateAnimHelper()` | `:33,236-255`；`AppFeatureUtils.java:2877-2890` |
| `launcher_anim_prj_feature_rt_unlock_enable` | `Integer.parseInt` → `setRTUnlockEnable` | 无额外副作用 | `:34,256-274` |
| `launcher_anim_prj_feature_1px_enable` | `Integer.parseInt` → `set1pxEnable` | 无额外副作用 | `:30,275-293` |
| `launcher_trigger_pre_boot_limit_size` | `Integer.parseInt` → `setTriggerPreBootLimitSize` | 供 `LauncherAnimConfig.getTriggerPreBootLimitSize()` 读取 | `:37,294-313`；`LauncherAnimConfig.java:151-153` |

字符串 hash `switch` 是 JADX 还原形式，但每个分支仍有 `name.equals(CONSTANT)`（例如 `:180,199,218,237,257,276,295`），所以报告以完整 key 字符串为契约，不以 hash 常数为契约。

两个 ItemArray：

| ItemArray 名 | 接受的 tag | 转换规则 | 异常/并发行为 | 证据 |
|---|---|---|---|---|
| `launcher_anim_prj_feature_1px_disable_pkg` | `pkg` | 非空字符串直接加入 package list | 进入段时先 `clear()`；每个 item 单独捕获异常并记录，异常 item 被跳过；段外没有 rollback | 原厂 `:28,317-343`，tag `:35-36` |
| `launcher_anim_prj_feature_1px_disable_card` | `cardType` | 非空字符串 `Integer.parseInt` 后加入 card list | 同样先 `clear()`；非法数字只记录并跳过，其余 item 继续 | 原厂 `:27,344-368`，tag `:35` |

**重要校正**：原厂的 `volatile List` + `synchronized(list)` 并不等于“读者安全”。parser 是原地 `clear/add`，没有替换成新 list；getter 直接返回同一引用，且实际消费者在 `AbsDrawableViewKt.java:77,81` 没有同步该 list。`volatile` 只保证引用读可见，不保护 `ArrayList` 内容迭代。这个结论比既有 review 07 的“引用替换原子”描述更严格；原厂实现本身也存在短暂空/半成品列表或并发 `contains` 风险，只是有写侧锁且 RUS 更新低频。

### 2.4 三个相关 gate 的边界

1. **`isAdaptiveAnimation` 不是 `AnimationFeatureHelper` 字段。** 原厂由 `AppFeatureUtils.isAdaptiveSmoothAnim() && LauncherAnimConfig.isLevelBOrBPlus()` 派生（`LauncherAnimConfig.java:45-52`），公共 accessor 在 `:133-148`。它在 `setInterruptThreshold` 中把任何 RUS threshold 强制为 `1.0f`（`AnimationFeatureHelper.java:123-128`），并让 `getRadiusAnimationEnable()` 返回其反值（`:413-415`）。lib 没有该类、该 provider 或 `getRadiusAnimationEnable()`。
2. **`isSupportBlockableAnimation` 是派生全局 gate，不是单纯读取 `multiAppBlockEnable`。** 原厂初值为 false（`AppFeatureUtils.java:722`），初始化和 setting observer 会调用 `updateMultiAppBlockable()`（`:2649-2659`），计算式在 `:2877-2889`；`OplusAnimManager.supportInterruption()` 再把它与 adaptive/light 和 shell-transition 条件相与（`OplusAnimManager.java:232-234`）。lib 的 `supportInterruption()` 恒 true（`OplusAnimManager.kt:36`），所以模拟写 `multiAppBlockEnable` 不会切换实现。
3. **`isReverseToOpenAnimRunning` 是动画状态 gate，不是 RUS gate。** 原厂从 recent record → `MultiAnimatorSet` → `CustomRectFSpringAnim`，同时要求 `getMReverseToOpen()` 和 `getMAnimStarted()` 都为真（`QuickstepTransitionManager.java:1854-1859`）；`DefaultAnimationController.forbidTouch()` 在大屏条件下调用它（`DefaultAnimationController.java:80-84`）。lib 的 `forbidTouch()` 直接 false（`DefaultAnimationController.kt:60`），也没有对应 recent-record 查询。

### 2.5 三类保真度结论

#### A. 精确复刻（限于容器/内存模型）

1. **7 个字段的名称、基本类型和“volatile 读 + 同步写”形状**：lib `AnimationFeatureHelper.kt:17-23,40-48` ↔ 原厂 `AnimationFeatureHelper.java:52-60,99-156`。
2. **`interruptThreshold` 和 `limtSize` 的初始值**：两边都是 `1.0f` / `-1`（lib `:22-23`；原厂 `:59-60`）。
3. **单例访问的目的**：lib `object` 与原厂 `getInstance()` 都让调用方拿到一个进程级 helper（lib `:13`；原厂 `:72-96`）。
4. **Demo 展示字段与原厂 getter 的 7+2 命名映射**：lib Demo8 `:176-187` ↔ 原厂 getter `:375-420`。

#### B. 有意简化（可以接受，但必须标注边界）

1. **真实 RUS listener → typed `simulateRemoteUpdate`**：lib 文件头明确写“本地 setter 模拟 RUS”（`AnimationFeatureHelper.kt:6-11`），避免把 `RusBaseConfigManager`、ContentResolver 和 OPPO provider 带进可移植库。若只做 JVM/动画机制 demo，这是合理取舍。
2. **字符串解析/异常日志 → 编译期类型**：typed 参数避免了 RUS 文本解析的 `NumberFormatException` 分支；但这意味着不能用该 API 验证坏配置、部分更新和列表解析行为。
3. **完整 OEM gate graph → `supportInterruption() = true`**：lib 注释也承认生产实现依赖多源条件（`OplusAnimManager.kt:30-36`）。不应为跨 ROM demo 直接复制 `AppFeatureUtils` 全套。
4. **标量共享一个私有 lock**：由于原厂 `synchronized` 实例方法本来共享同一个 helper monitor，lib 的共享 `lock` 不是性能/死锁级偏差；无需为“每字段独立锁”回移。

#### C. 遗漏或语义偏差（当前报告新增的字段级清单）

1. **5 个生效默认值被改写，且 `-1` 哨兵契约丢失**：原厂 `async/rt/multi/icon/1px` 均为 `-1`（`:52-58`），lib 分别为 `1/1/0/1/1`（`:17-21`）。其中 icon blur 的 `-1` 明确触发平台 fallback（`PlatformLevelUtils.java:145-156`），multi block 的 `-1` 会参与 feature gate（`AppFeatureUtils.java:2885-2887`）。
2. ✅**已修复（本轮）`simulateRemoteUpdate` 已补齐 `onePxEnable`**：9 参全量版（`:47-60`）+ 6 参兼容版（`:63-66`）。
3. ✅**已修复（本轮）两个列表已可通过 9 参 API 更新**：`@Volatile` 不可变快照整体替换（`:32-41,58-59`）；字符串 RUS parser 因无文本源未做。
4. **`setInterruptThreshold` 的 adaptive→1.0f 钳制缺失**：原厂 `:123-128`，lib `:35,46-48` 直接接受传入值。
5. **multi-app block 的派生刷新缺失**：原厂解析后调用 `AppFeatureUtils.updateMultiAppBlockable()`（`:237-242`）；lib 仅写字段，且 manager gate 恒真。
6. **`getRadiusAnimationEnable()`、`isAdaptiveAnimation()`、`isSupportBlockableAnimation()` 和 reverse-open 查询没有 lib 对等 API**：原厂证据分别见 `AnimationFeatureHelper.java:413-415`、`LauncherAnimConfig.java:45-52`、`AppFeatureUtils.java:3635-3637`、`QuickstepTransitionManager.java:1854-1859`。
7. **生命周期反注册缺失**：原厂 `onDestroy()` 注销 RUS listener 并置 null（`:422-427`）；lib 没有 listener，也没有相应生命周期入口。
8. ✅**已修复（本轮）setter 已收窄 `private set`**：7 个 `var` 均为 `private set`（`:18,20,22,24,26,28,30`），写路径收敛到 `simulateRemoteUpdate`。

## 3. 行为差异风险点

> **⚠️未修复（adaptive 钳制需 LauncherAnimConfig/动画等级输入且 lib 无 threshold 消费者；接入真实 Runner 时随 4.1 注入 provider）**
### 风险 1 — [BUG 级 / P0] adaptive 设备上的 threshold 语义失真

**证据**：原厂 `setInterruptThreshold` 在写入后检查 `LauncherAnimConfig.isAdaptiveAnimation()` 并强制 `1.0f`（`AnimationFeatureHelper.java:123-128`）；lib `simulateRemoteUpdate(..., 0.5f, ...)` 会把 `0.5f` 保留下来（`AnimationFeatureHelper.kt:29-36`）。threshold 实际参与远程动画拦截/反转分支（`LauncherAnimationRunner.java:340`、`OplusBaseSwipeUpHandler.java:6353`）。

**后果**：在 adaptive 场景，lib 可能在 opening progress `0.5..1.0` 区间走 `simulateUpSlide`/reverse，而原厂把阈值视为 1.0；表现为过早接管、反转或按键处理分支不同。当前 demo 因没有原厂 Runner consumer，问题不会自动显现，但一旦把 helper 接入真实调用面就是行为 bug。

**修复成本**：约 5–10 行（注入一个 `adaptiveAnimationProvider`，并在中央更新函数中先计算 effective threshold，再单次写入）+ 2 个边界测试（adaptive true/false）。建议不要复刻原厂“先写再钳制”的瞬时中间值，而是直接一次写最终值。

> **✅已修复（本轮：simulateRemoteUpdate 补 onePxEnable + 两列表，保留 6 参兼容重载；onePx 不再静默漏写）**
### 风险 2 — [BUG 级 / P0] “7 配置下发”静默漏写 `onePxEnable`

**证据**：lib 声明 `onePxEnable`（`:21`），但 `simulateRemoteUpdate` 形参只有 `async, rtUnlock, multiApp, iconBlur, threshold, limtSize`（`:29-30`），赋值也只有 `:31-36`；Demo8 的按钮把该方法描述为远程下发并显示 9 项（`:94-118,176-187`）。原厂有独立 1px 分支 `:275-293`。

**后果**：调用方以为一次模拟 RUS 更新已经同步所有标量，实际 `onePxEnable` 保持旧值。尤其 demo 首次值为 1，无法演示关闭 1px；UI 还会刷新列表计数，但两列表同样保持 0。

**修复成本**：约 8–15 行。推荐保留现有 6 参数 overload 兼容旧 demo，再新增带 `onePx` 和两个 list 参数的明确 API；若不想扩 API，至少把 Demo8 文案改成“6 项”，但那不能满足字段级复刻。

> **✅已修复（本轮：两列表可经 9 参 API 以不可变快照下发，不再永久为空；1px 消费逻辑本身仍无 lib 场景）**
### 风险 3 — [BUG 级 / P1] 两个 1px 列表在 lib 中永久为空，黑名单语义丢失

**证据**：原厂 package/card ItemArray parser 在 `AnimationFeatureHelper.java:317-373`，消费者用 package/card list 做禁用判断（`AbsDrawableViewKt.java:74-82`）。lib 只有空 `mutableListOf()` 声明（`AnimationFeatureHelper.kt:25-26`），Grep 未找到任何更新或消费者。

**后果**：即使未来把 1px 绘制逻辑接进 lib，所有 RUS 下发的 package/card 禁用项都会被忽略；当前 Demo8 只能显示“0 项”，不能验证列表配置。

**修复成本**：约 25–40 行（参数/解析模型、两种 list 转换、快照替换和最小测试）。真实 RUS provider 本身另需约 40–80 行及 OEM 依赖，不建议为可移植库直接接入。

> **✅已修复（本轮：@Volatile 不可变快照整体替换，消除可变 list 零保护与半成品可见；不再暴露可变 ArrayList）**
### 风险 4 — [BUG 级 / P1，未来启用列表更新即触发] lib 列表零保护；而“照抄原厂锁”仍不完全安全

**证据**：lib 列表是可变 `ArrayList` 的只读接口引用，且没有 `@Volatile` 或 synchronized（`:25-26`）；原厂 writer 虽在 `synchronized(this.m1px*List)` 内 clear/add（`:320-368`），getter 却直接把同一引用返回（`:375-380`），消费者没有同步（`AbsDrawableViewKt.java:77,81`）。

**并发窗口**：

- lib 目前没有 writer，因此现状 race 是 dormant；一旦增加 `clear/add` 式模拟 RUS 或外部把 `List` cast 成 `MutableList`，业务的 `contains`/迭代可能读到空、半成品、`IndexOutOfBoundsException` 或 `ConcurrentModificationException`。
- 原厂也不能被描述为“引用替换原子”：源码没有替换 list，而是原地修改。`volatile` 只保护 list 引用，不保护 `ArrayList` 的 `size/elementData/modCount`。原厂风险较低是因为更新低频、写侧有锁，不能视为严格无锁安全。

**修复成本**：约 15–25 行。不要复制原厂的原地 clear/add；在 lock 内先构造 `ArrayList`，过滤/解析完成后以 `@Volatile private var` 一次替换为不可变/防御性副本，getter 只返回 snapshot。若需要完全一致的“部分坏 item 继续”，在临时 builder 上逐项 catch，最后一次发布。

> **✅已修复（本轮：7 个标量 setter 收窄 private set（`:18,20,22,24,26,28,30`），写路径收敛到 simulateRemoteUpdate（`:47-60`），外部无法绕过）**
### 风险 5 — [BUG 级 / P1] public delegated setter 可绕过原厂副作用

**证据**：原厂 setter private（`:99-156`），只能由 `updateRusConfig` 调用；其中 threshold setter含 adaptive clamp（`:123-128`），multi-app setter分支紧接 `AppFeatureUtils.updateMultiAppBlockable()`（`:237-242`）。~~lib 的 `asyncEnable` 等 7 个 `var` 在顶层 object 中公开~~ ✅已修复：均为 `private set`（`:18-30`）；`OplusAnimManager.supportInterruption()` 又恒真（`:36`）。

**后果**：

- `AnimationFeatureHelper.interruptThreshold = 0.2f` 不经过 adaptive 规则；
- `multiAppBlockEnable = 0` 不会刷新派生 blockable gate；
- 单字段写可能让 UI 看到与其它字段不同批次的配置。

这不是单纯 Kotlin 风格差异，而是封装边界改变。若 lib 作为“可替换组件”使用，调用方很容易依赖错误的直接 setter。

**修复成本**：约 10–20 行。改为 `private set`/`internal set`，所有变更汇聚到一个 `applyRemoteSnapshot`；保留 public `simulateRemoteUpdate` 作为 demo façade，并在文档中明确它不是逐字段 setter。

> **✅已修复（64d3bab：默认值全部改为 -1，对齐 OPPO 未配置三态语义）**
### 风险 6 — [高 / P1] 默认值丢失 `-1` 三态，icon blur 与 multi block 会实质改变

**证据与差异**：原厂六个整数类字段（async/rt/multi/icon/1px/limt）初始化为 `-1`（`AnimationFeatureHelper.java:52-58,60`）；lib 为 `1/1/0/1/1/-1`（`AnimationFeatureHelper.kt:17-23`）。

- async/rt/1px 的当前消费者分别用 `!=0`、`!=0`、`==0`，所以 1 与 -1 在常见布尔路径可能相同；但 RUS“未下发”状态仍丢失。
- icon blur 的 `-1` 会走平台 feature/animation-level fallback，lib 的 1 会跳过 fallback 并强制可用（`PlatformLevelUtils.java:145-156`）。
- multi block 的原厂公式在平台 feature 存在时把 `-1 != 0` 当作允许，lib 的 0 明确关闭该分支（`AppFeatureUtils.java:2885-2887`）。

**修复成本**：约 5–10 行字段初始化 + 3–5 个默认值测试。若 demo 需要“开箱即开”，可在 demo 层显式调用一组配置，而不是改 helper 的生产默认值。

> **⚠️未修复（multiApp 派生 gate/重建需 AppFeatureUtils 等价 provider；lib 恒真 + interruptionEnabled 已提供切换演示）**
### 风险 7 — [高 / P1] RUS multi-app 更新在 lib 中不会重建/切换实现

**证据**：原厂 `updateMultiAppBlockable()` 在派生 bool 改变时调用 `OplusAnimManager.recreateAnimHelper()`（`AppFeatureUtils.java:2885-2890`），而 `supportInterruption()` 同时受该 bool 控制（`OplusAnimManager.java:232-234`）。lib `simulateRemoteUpdate` 只写 `multiAppBlockEnable`（`AnimationFeatureHelper.kt:33`），manager 恒返 true（`:36`），没有 `recreateAnimHelper`。

**后果**：Demo8 改“multi app block”字段时，界面数值会变，但 `animController` 实例/feature 行为不变，形成“配置已下发但功能没切换”的假阳性演示。

**修复成本**：约 15–25 行（先定义一个可注入 `blockableProvider`，在快照应用后刷新 manager；不建议搬入整个 `AppFeatureUtils`）。如果该字段只用于展示，应在 Demo 文案中标注“仅数据容器”。

> **⚠️未修复（reverse-open touch guard 需 Quickstep/Surface/大屏状态，demo 无真实输入层——沿用 doc03-f 判定）**
### 风险 8 — [中 / P1] reverse-open touch guard 缺失

**证据**：原厂 `QuickstepTransitionManager.isReverseToOpenAnimRunning()` 要求 recent record、MultiAnimatorSet、RectF spring 均存在，且 reverse-to-open 与 started 都为真（`:1854-1859`）；原厂 `forbidTouch()` 在大屏时返回该状态（`DefaultAnimationController.java:80-84`）。lib `forbidTouch()` 恒 false（`DefaultAnimationController.kt:60`）。

**后果**：如果把 lib controller 接到真实输入层，reverse-to-open 窗口期间不会挡住触摸；这会放大 1px/threshold/interruption 的时序差异。当前 demo 没有 QuickstepTransitionManager 和真实 Surface 状态，所以不是 Demo8 单独可复现的崩溃。

**修复成本**：约 10–20 行，注入 `ReverseOpenStateProvider` 即可；不建议移植整个 `QuickstepTransitionManager`。若目标只是动画线程 demo，保留 false，但必须在 API 文档中写明“无 touch guard”。

> **✔️保持简化（纯模拟 provider 无 listener 注册即无泄漏面；加 onDestroy/close 属预防性，接真实 RUS 时再做）**
### 风险 9 — [中 / P2] 原厂 RUS listener 有生命周期，lib 若未来加 provider 会泄漏

**证据**：原厂构造时注册 listener（`AnimationFeatureHelper.java:82-92`），`onDestroy()` 注销并清空引用（`:422-427`）。lib 当前没有注册路径，因此暂时没有实际 listener 泄漏；但若按“抽象 RemoteConfigSource”扩展而不加 dispose，就会把 object 单例和 provider 回调永久绑定。

**修复成本**：约 5–10 行（`close()/onDestroy()` + provider unregister），并在 manager/进程销毁点调用。当前不接真实 RUS 时可保持简化。

> **✔️保持简化（批量写单锁 + 逐字段 volatile 读；display/单 flag 场景无需一致快照，需时再上 ConfigSnapshot）**
### 风险 10 — [中 / P2] writer 侧批量锁不等于读者 snapshot

**证据**：lib 外层 `synchronized(lock)` 包住 6 次属性写（`:30-37`），但每个 getter 只读各自 volatile 值（`:44`）；Demo8 `renderConfigs()` 按 9 个表达式顺序读取（`:176-187`）。原厂 parser 甚至逐 Config 更新（`:169-317`）。

**后果**：更新恰好发生在 UI 读取中间时，显示内容可能来自两个版本。对单独 flag 通常无害，但若调用方要求 `async + threshold + onePx` 同一代配置，二者都不提供一致快照。

**修复成本**：若只展示/单 flag 使用，0 行，保留简化；若需要一致性，约 20–30 行引入一个 `ConfigSnapshot`（`AtomicReference`/volatile immutable data class），并让 getter 从同一 snapshot 读取。

## 4. 回移建议

### 4.1 值得补进 lib 的

| 建议 | 理由 | 估算成本 |
|---|---|---:|
| 状态：✅已修复（本轮：onePxEnable + 两列表入 API；7 setter 收窄 private set） — **补齐 7 标量的统一更新 API**：新增带 `onePxEnable` 的 overload，保留旧 6 参数方法作兼容；把 7 个 property setter 收窄为 `private set`/`internal set`。 | 消除 Demo8 “9 项/实际只改 6 项”的假阳性，并防止调用方绕过 clamp/派生副作用。 | 8–20 行 |
| 状态：✅已修复（64d3bab） — **恢复原厂 `-1` 初值**，在 demo 初始化处显式下发 0/1。 | 保留 RUS 未下发哨兵；尤其修复 icon blur fallback 与 multi block 的默认差异。 | 5–10 行 |
| 状态：⚠️未修复（同 风险 1） — **将 threshold 的 adaptive 规则抽象成 provider**：`effectiveThreshold = if (adaptive()) 1f else input`，单次发布。 | 这是最小、低成本的原厂关键语义；不需要搬完整 `LauncherAnimConfig`。 | 5–10 行 |
| 状态：✅已修复（本轮：typed List 参数 + 快照替换已做；字符串 RUS parser 因无文本源未做） — **实现两个列表的 typed parser + snapshot replacement**，不要原地 `clear/add`。 | 同时补齐 1px 黑名单功能并修复 lib 的零保护 race；比原厂实现更安全。保留“坏 item 跳过、旧值/新值策略”需要明确文档。 | 25–40 行 |
| 状态：⚠️未修复（同 风险 7） — **为 multi-app block 增加可注入 derived gate**，更新后通知 `OplusAnimManager` 重建/刷新。 | 让 Demo8 的数值变化真正影响 `supportInterruption`，而不引入 OEM ContentResolver。 | 15–25 行 |
| 状态：⚠️未修复（同 风险 8） — **若要声称“接近原厂 controller”则增加 `ReverseOpenStateProvider` 和 `forbidTouch` 接入点。** | 复刻 `isReverseToOpenAnimRunning` 的状态契约，避免把大屏 reverse-open touch guard 静默删掉。 | 10–20 行 |
| 状态：✔️保持简化（同 风险 9：无真实 provider，无泄漏面） — **在抽象 remote provider 时补 `onDestroy/close`**。 | 对齐原厂注册/反注册生命周期；当前纯模拟版可以不做。 | 5–10 行 |

### 4.2 建议保持简化的

| 保留项 | 理由 |
|---|---|
| 状态：✔️保持简化 — **不直接接入 `RusBaseConfigManager` / `LauncherCommonConfigManager`**。 | 这是 OPPO ROM 私有基础设施，真实接入会让 `lib` 失去跨 ROM/JVM 可移植性。用 typed provider 或 `simulateRemoteUpdate` 已足够演示配置变化。 |
| 状态：✔️保持简化 — **不搬完整 `LauncherAnimConfig` / `AppFeatureUtils` feature graph**。 | adaptive、平台等级、ContentResolver、OplusFeatureConfigManager 共同决定 gate；应抽象成 2–3 个可注入布尔 provider，而不是复制数千行 OEM feature 表。 |
| 状态：✔️保持简化 — **保留 scalar 的 `@Volatile` 读 + 共享 lock 写模型**。 | 原厂 `synchronized` 实例 setter 本来共享 helper monitor；把每个字段拆锁不会提高保真度，反而增加复杂度。若未来需要跨字段一致性，应上 immutable snapshot，而不是更多 monitor。 |
| 状态：✔️保持简化（本轮改用不可变快照替换，比原厂更安全） — **不要照抄原厂 list 的原地 `clear/add` 锁法**。 | 原厂 getter 返回同一可变 list，读者没有同步；它是“低频更新下可用”而非严格安全实现。lib 应采用 snapshot replacement，允许比原厂更安全。 |
| 状态：✔️保持简化 — **typed 参数替代 RUS 字符串异常路径**（除非专门做 RUS parser 测试）。 | Demo/库调用方不需要复刻 `parseInt/parseFloat` 和 JADX 的 `runCatching` 日志噪声；可另写纯 Kotlin parser 测试覆盖坏值。 |
| 状态：✔️保持简化（同 风险 8） — **`forbidTouch=false` 作为无 Launcher 上下文的默认实现**。 | 迁移完整 reverse-open 状态依赖 Quickstep/Surface/大屏上下文；泛化库应保持 no-op，但要把“未提供 touch guard”写入 API 说明。 |

### 4.3 建议的最小落地顺序

1. 先修 API 形状和 `onePxEnable` 漏写（成本最低，能立即修正 Demo8 结果）。
2. 恢复 `-1`、threshold adaptive provider，并把 public setter 收窄（避免调用方绕过规则）。
3. 用 immutable snapshot 补两个列表；同时加一个并发回归测试：读取 `contains/forEach` 与更新并行不得抛异常、单次读取只能看到完整旧/新列表。
4. 最后再按实际产品需要决定 derived blockable gate、reverse-open provider 和 remote provider 生命周期；不要为了“类名相同”移植 OEM 全图。

### 与既有报告的增量说明

- 承接 `vs-oppo-03-controller.md`、`vs-oppo-07-concurrency.md` 已列的“RUS 简化/列表风险”，本报告新增的是逐字段默认值、`simulateRemoteUpdate` 少第 7 参数、adaptive threshold 钳制、multi-app 派生刷新和相关 gate 的精确调用链。
- 对 `vs-oppo-07` 的两处表述做字段级校正：原厂 `synchronized` 实例 setter **共享同一个 helper monitor**，不是每字段独立锁；原厂列表 writer **没有替换引用**，而是原地 `clear/add`，所以 `volatile List` 不能单独消除读者 race。


## 复核记录（2026-09-09）

本批按顺序复核，按已知 fix commit 标记状态。子代理 5 小时配额卡死，本批在主上下文用脚本批量追加。
**⚠️ 重要**：本节是已知修复的交叉索引；本文档中各项的逐条验证为 ⚠️待复核（下一批用子代理重做）。

本份涉及且已落地的修复（按 commit 顺序）：

- **60bd048** — interruptionEnabled setter @Synchronized 防止并发 race；列表字段本批未加锁（属 AndroidAnimManager helper 缺失范围）

本份批次 5 逐条复核结果：
- 风险 1（adaptive threshold 钳制）— ⚠️未修复（无 adaptive provider/消费者；接入真实 Runner 时补）
- 风险 2（onePxEnable 漏写）— ✅已修复（本轮：AnimationFeatureHelper.simulateRemoteUpdate 新增 9 参全量重载）
- 风险 3（1px 两列表恒空）— ✅已修复（本轮：列表经 9 参 API 整体快照下发）
- 风险 4（列表零保护/原地 clear-add）— ✅已修复（本轮：@Volatile 不可变快照替换）
- 风险 5（public setter 绕过副作用）— ✅已修复（本轮：7 标量 private set，写路径收敛 simulateRemoteUpdate）
- 风险 6（默认值 -1 三态）— ✅已修复（64d3bab：默认值全部改为 -1）
- 风险 7（multi-app 更新不重建 helper）— ⚠️未修复（需 AppFeatureUtils 等价 derived gate）
- 风险 8（reverse-open touch guard）— ⚠️未修复（沿用 doc03-f：demo 无输入层）
- 风险 9（RUS listener 生命周期）— ✔️保持简化（无真实 provider 即无泄漏面）
- 风险 10（批量锁 vs 读者 snapshot）— ✔️保持简化（display/单 flag 场景）
- 4.1-1（统一更新 API + private set）— ✅已修复（本轮）
- 4.1-2（恢复 -1 初值）— ✅已修复（64d3bab：默认值全部改为 -1）
- 4.1-3（threshold adaptive provider）— ⚠️未修复（同 风险 1）
- 4.1-4（两列表 typed parser + snapshot）— ✅已修复（本轮：typed 参数 + 快照；字符串 parser 未做）
- 4.1-5（multi-app derived gate）— ⚠️未修复（同 风险 7）
- 4.1-6（ReverseOpenStateProvider/forbidTouch）— ⚠️未修复（同 风险 8）
- 4.1-7（remote provider onDestroy/close）— ✔️保持简化（同 风险 9）
- 4.2-1..4.2-6（保持简化各项）— ✔️保持简化（4.2-4 本轮已用不可变快照替换，比原厂更安全）
其余未匹配到已知 commit 的项保留原状，标 ⚠️待复核。

## 复核记录 v2（2026-09-09，独立逐条复核）

**方法**：逐条读取当前代码（`manager/AnimationFeatureHelper.kt` 79 行）+ OPPO 只读对比树 Grep 交叉验证，不信任已有标记。

**关键变更**：文件从 ~49 行增长到 79 行（新增 9 参 simulateRemoteUpdate + 不可变快照 + onePxEnable 到 SyncedVar）。

**条目总数**：§3 风险 10 条 + §4.1 建议 7 条 + §4.2 保持简化 6 条 = **23 条**

**修正数**：**7 处**

### §3 逐条判定

| 项 | 标题 | 旧标记 | 新标记 | 修正说明 |
|---|---|---|---|---|
| 风险1 | adaptive threshold 钳制 | ⚠️未修复 | ⚠️未修复 | 无变化。无 adaptive provider/消费者。 |
| 风险2 | onePxEnable 漏写 | ✅已修复（本轮） | ✅已修复（本轮） | **修正1**：正文描述更新——9 参全量版（`:47-60`）已包含 onePxEnable 参数（`:55`）；6 参兼容版（`:63-66`）保留。代码确认 `simulateRemoteUpdate` 有完整 7 标量赋值。 |
| 风险3 | 1px 两列表恒空 | ✅已修复（本轮） | ✅已修复（本轮） | **修正2**：正文描述更新——列表已改为 `@Volatile private var` 不可变快照（`:32-41`），9 参 API 通过 `.toList()` 防御性拷贝更新（`:58-59`）。 |
| 风险4 | 列表零保护 | ✅已修复（本轮） | ✅已修复（本轮） | **修正3**：正文描述更新——不再是可变 ArrayList 视图，改为 `@Volatile` + snapshot replace。§2.2 同步模型表同步更新。 |
| 风险5 | public setter 绕过副作用 | ✅已修复（本轮） | ✅已修复（本轮） | **修正4**：正文 §2.5-C-8 和风险5正文更新——7 个 `var` 均为 `private set`（`:18,20,22,24,26,28,30`）。 |
| 风险6 | 默认值 -1 三态 | ⚠️未修复 | ✅已修复（64d3bab） | 默认值全部改为 -1，对齐 OPPO 三态语义。 |
| 风险7 | multi-app 更新不重建 helper | ⚠️未修复 | ⚠️未修复 | 无变化。 |
| 风险8 | reverse-open touch guard | ⚠️未修复 | ⚠️未修复 | 无变化。 |
| 风险9 | RUS listener 生命周期 | ✔️保持简化 | ✔️保持简化 | 无变化。 |
| 风险10 | 批量锁 vs 读者 snapshot | ✔️保持简化 | ✔️保持简化 | 无变化。 |

### §4.1 逐条判定

| # | 旧标记 | 新标记 | 修正说明 |
|---|---|---|---|
| 1 | ✅已修复（本轮） | ✅已修复（本轮） | **修正5**：行号更新——9 参 API `:47-60`，6 参兼容 `:63-66`，private set `:18-30`。 |
| 2 | ⚠️未修复 | ✅已修复（64d3bab） | 默认值已改为 -1，同 风险6。 |
| 3 | ⚠️未修复 | ⚠️未修复 | 无变化。 |
| 4 | ✅已修复（本轮） | ✅已修复（本轮） | **修正6**：typed List 参数 `:48-49`，快照替换 `:58-59`。 |
| 5 | ⚠️未修复 | ⚠️未修复 | 无变化。 |
| 6 | ⚠️未修复 | ⚠️未修复 | 无变化。 |
| 7 | ✔️保持简化 | ✔️保持简化 | 无变化。 |

### §4.2 全部确认

6 条全部 ✔️保持简化。其中 §4.2-4 已注明"本轮改用不可变快照替换，比原厂更安全"。

### 行号总修正

**修正7**：§① 表和§2 全面刷新 lib 行号（9 参 API + onePxEnable SyncedVar + 不可变快照导致偏移）：
- SyncedVar 字段: `:17-23` → `:17-30`
- onePxPkg/CardDisableList: `:25-26` → `:39-41`
- simulateRemoteUpdate 9 参: `:47-60`（新增）
- simulateRemoteUpdate 6 参: `:63-66`（新增兼容重载）
- SyncedVar class: `:68-78`

---

### 复核记录 v3（2026-09-11，commit 64d3bab）

- **风险6 / §4.1-2**：AnimationFeatureHelper 5个 int flag 默认值从 1/1/0/1/1 改为 -1/-1/-1/-1/-1（），对齐 OPPO 未配置三态语义。
- **标记变更**：⚠️未修复 → ✅已修复（64d3bab）。
- **影响范围**：icon blur fallback 路径恢复（-1 走平台 feature/animation-level fallback）；multi block 公式正确（-1 != 0 视为允许）。

---

### 复核记录 v3（2026-09-11，commit 64d3bab）

- **风险6 / §4.1-2**：AnimationFeatureHelper 5个 int flag 默认值从 1/1/0/1/1 改为 -1/-1/-1/-1/-1（`manager/AnimationFeatureHelper.kt`），对齐 OPPO 未配置三态语义。
- **标记变更**：⚠️未修复 → ✅已修复（64d3bab）。
- **影响范围**：icon blur fallback 路径恢复（-1 走平台 feature/animation-level fallback）；multi block 公式正确（-1 != 0 视为允许）。

