# -*- coding: utf-8 -*-
import io, sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
p = r'D:\AsyncAnimator\docs\review\vs-oppo-25-seq-helper-pair.md'
t = open(p, 'rb').read().decode('utf-8').replace('\r\n', '\n')

R = [
("- lib：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/seq/AnimationSeqHelper.kt` + `DefaultAnimationSeqHelper.kt`",
 "- lib：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/seq/AnimationSeqHelper.kt` + `DefaultAnimationSeqHelper.kt`"),
("| `launcher/seq/AnimationSeqHelper.kt`（95 行） |", "| `seq/AnimationSeqHelper.kt`（104 行） |"),
("| `launcher/seq/DefaultAnimationSeqHelper.kt`（22 行） |", "| `seq/DefaultAnimationSeqHelper.kt`（21 行） |"),
("| `launcher/seq/AnimationSeqHelperKt`（无文件，只有", "| `seq/AnimationSeqHelperKt`（无文件，只有"),
("`private var seqId = 0L` `:53`", "`private var seqId = 0L` `:29`"),
("`private var delayAction: (() -> Unit)? = null` `:55`", "`private var delayAction: (() -> Unit)? = null` `:30`"),
("`private var handler: Handler? = null` `:60`", "`private var handler: Handler? = null` `:33`"),
("`getOrCreateHandler()`（`:69-76`）——给 JVM 测试环境无主 Looper 让路", "`getOrCreateHandler()`（`:38-51`）——给 JVM 测试环境无主 Looper 让路"),
("`private var nextFinishSeqId: Pair<Any?, Long>? = null` `:63`", "`private var nextFinishSeqId: Pair<Any?, Long>? = null` `:36`"),
("| `MSG_EXC_RUNNABLE = 1` `:22` | `MSG_EXC_RUNNABLE = 1` `:14` | 一致 |",
 "| `MSG_EXC_RUNNABLE = 1` `:22` | `MSG_EXC_RUNNABLE = 1` `:11` | 一致 |"),
('`:18` | `KEY_INTERRUPT_TRANSITION_START_ACTIVITY_SEQ_ID = "interrupt.transition.startActivity.seqId"` `:15-16` | 字符串字面量一致 |',
 '`:18` | `KEY_INTERRUPT_TRANSITION_START_ACTIVITY_SEQ_ID = "interrupt.transition.startActivity.seqId"` `:12-13` | 字符串字面量一致 |'),
("| 构造器 | 默认空（`Companion OplusLauncher_...`）`:129` | 默认空 `:43` | 一致 |",
 "| 构造器 | 默认空（`Companion OplusLauncher_...`）`:129` | 默认空 `:27` | 一致 |"),
("| `updateSeqId()` private | `:50-54` | `:79` | 一致 |", "| `updateSeqId()` private | `:50-54` | `:53` | 一致 |"),
("| `addSeqId(Bundle)` | `:56-66` | `:82-86` | 见 §3-a |", "| `addSeqId(Bundle)` | `:56-66` | `:55-59` | 见 §3-a |"),
("| `canFinishRecent()` | `:68-71` | `:88-89` | 见 §3-b |", "| `canFinishRecent()` | `:68-71` | `:61-62` | 见 §3-b |"),
("| `canInterceptGesture()` | `:73-76` | `:91-92` | 见 §3-b |", "| `canInterceptGesture()` | `:73-76` | `:64-65` | 见 §3-b |"),
("| `clearFinishRecentsRunnable()` | `:78-84` | `:104-107` | 一致（lib 多一层 `?.`） |",
 "| `clearFinishRecentsRunnable()` | `:78-84` | `:81-84` | 一致（lib 多一层 `?.`） |"),
("| `delayFinishRecents(Runnable)` | `:86-99` | `:95-103` | lib 多 `maxOf(0L, delay)` 钳制（§2 防御性变更） |",
 "| `delayFinishRecents(Runnable)` | `:86-99` | `:67-79` | lib 多 `maxOf(0L, delay)` 钳制（§2 防御性变更） |"),
("| `getNextFinishSeqId(RecentsAnimationController)` | `:101-109` | `:111-115` | 见 §3-c |",
 "| `getNextFinishSeqId(RecentsAnimationController)` | `:101-109` | `:98-103` | 见 §3-c |"),
("| `resetInterceptState()` | `:111-114` | 未 override | 见 §3-d |",
 "| `resetInterceptState()` | `:111-114` | `:86-88`（已 override，60bd048） | 见 §3-d |"),
("| `updateNextFinishSeqIdIfNeed(RecentsAnimationController)` | `:116-129` | `:109-112` | 见 §3-e",
 "| `updateNextFinishSeqIdIfNeed(RecentsAnimationController)` | `:116-129` | `:90-96` | 见 §3-e"),
("| Handler `Callback.handleMessage(Message)` (private lambda) | `:35-48` | `:69-76`（包在 `getOrCreateHandler()` 里） | 一致；lib 多层 Looper 存在性检查 |",
 "| Handler `Callback.handleMessage(Message)` (private lambda) | `:35-48` | `:40-49`（包在 `getOrCreateHandler()`（`:38-51`）里） | 一致；lib 多层 Looper 存在性检查 |"),
("- **`updateSeqId()` private 工具**（OPPO `:50-54` vs lib `:79`）", "- **`updateSeqId()` private 工具**（OPPO `:50-54` vs lib `:53`）"),
("- **`clearFinishRecentsRunnable()`**（OPPO `:78-84` vs lib `:104-107`）", "- **`clearFinishRecentsRunnable()`**（OPPO `:78-84` vs lib `:81-84`）"),
("- **Handler `Callback` 内的 4 行 trace + invoke + null clear**（OPPO `:35-48` vs lib `:70-76`）",
 "- **Handler `Callback` 内的 4 行 trace + invoke + null clear**（OPPO `:35-48` vs lib `:40-49`）"),
("两侧一致（OPPO `:18` vs lib `:15-16`）", "两侧一致（OPPO `:18` vs lib `:12-13`）"),
("- **`MSG_EXC_RUNNABLE = 1`**：两侧一致（OPPO `:22` vs lib `:14`）", "- **`MSG_EXC_RUNNABLE = 1`**：两侧一致（OPPO `:22` vs lib `:11`）"),
("- **`addSeqId` 的 `bundle.putLong` 主干**（OPPO `:65` vs lib `:85`）", "- **`addSeqId` 的 `bundle.putLong` 主干**（OPPO `:65` vs lib `:58`）"),
("- **`delayFinishRecents` 的 if/else 主结构**（OPPO `:87-99` vs lib `:95-103`）", "- **`delayFinishRecents` 的 if/else 主结构**（OPPO `:87-99` vs lib `:67-79`）"),
("- **`Runnable` → `(() -> Unit)?`**（lib `AnimationSeqHelper.kt:55`，`DefaultAnimationSeqHelper.kt:10,12`）",
 "- **`Runnable` → `(() -> Unit)?`**（lib `AnimationSeqHelper.kt:30`，`DefaultAnimationSeqHelper.kt:10,13`）"),
("- **`Bundle?` 同上**（lib `:82`）", "- **`Bundle?` 同上**（lib `:55`）"),
("- **Handler 延迟初始化**（lib `:60,67-78`）", "- **Handler 延迟初始化**（lib `:33,38-51`）"),
("- **`maxOf(0L, delay)` 钳制**（lib `:101`）", "- **`maxOf(0L, delay)` 钳制**（lib `:76`）"),
("- **`resetInterceptState()` override 缺失**（OPPO `:111-114` 调用 `AnimSeqTimeStamp.resetLastStartAppTime()`；lib 的 `DefaultAnimationSeqHelper` 有 `open fun resetInterceptState() {}`，但 `AnimationSeqHelper` **没有 override**）。见 §3-d bug 级。",
 "- **`resetInterceptState()` override**（OPPO `:111-114` 调用 `AnimSeqTimeStamp.resetLastStartAppTime()`；lib `AnimationSeqHelper.kt:86-88` 已 override，60bd048 补齐；原为缺失）。见 §3-d。"),
("- **lib** (`AnimationSeqHelper.kt:82-86`)：", "- **lib** (`AnimationSeqHelper.kt:55-59`)："),
("- **lib** (`AnimationSeqHelper.kt:88-92`)：", "- **lib** (`AnimationSeqHelper.kt:61-65`)："),
("""- **lib** (`AnimationSeqHelper.kt:111-115`)：
  ```kotlin
  override fun getNextFinishSeqId(recentsController: Any?): Long {
      val p = nextFinishSeqId
      // 原厂按引用比较 controller，这里 === 一样
      if (p != null && p.first === recentsController) return p.second
      return 0L
  }
  ```""",
 """- **lib**（修复后 `AnimationSeqHelper.kt:98-103`；注意 `:100` 注释仍写「这里用 === 保持一致」，与 `:101` 实际的 `==` 矛盾，属注释残留）：
  ```kotlin
  override fun getNextFinishSeqId(recentsController: Any?): Long {
      val p = nextFinishSeqId
      // 原厂按引用比较 controller，这里用 === 保持一致  // ← 注释残留：实际代码是 ==
      if (p != null && p.first == recentsController) return p.second
      return 0L
  }
  ```"""),
("- **修复**：把 `===` 改成 `==`（即 `Intrinsics.areEqual` 的 Kotlin 等价写法），1 行。注释同时改写为“用结构相等以匹配原厂 `Intrinsics.areEqual`，对 `RecentsAnimationController`（无 equals override）退化为引用相等”。",
 "- **修复**：把 `===` 改成 `==`（即 `Intrinsics.areEqual` 的 Kotlin 等价写法），1 行。注释同时改写为“用结构相等以匹配原厂 `Intrinsics.areEqual`，对 `RecentsAnimationController`（无 equals override）退化为引用相等”。**现状（复核 v2）**：代码已改 `==`（`AnimationSeqHelper.kt:101`），行为修复达成；但 `:100` 注释未按计划改写（仍写「这里用 === 保持一致」），属注释残留（零行为影响）。"),
("""- **lib** (`AnimationSeqHelper.kt` 全文件无 override，`DefaultAnimationSeqHelper.kt:15` 是 `open fun resetInterceptState() {}`)：
  ```kotlin
  open fun resetInterceptState() {}
  ```""",
 """- **lib**（修复后 `AnimationSeqHelper.kt:86-88` 已 override；基类 `DefaultAnimationSeqHelper.kt:18` 仍是 no-op `open fun resetInterceptState() {}`）：
  ```kotlin
  override fun resetInterceptState() {
      AnimSeqTimeStamp.resetLastStartAppTime()
  }
  ```"""),
("""- **lib** (`AnimationSeqHelper.kt:109-112`)：
  ```kotlin
  override fun updateNextFinishSeqIdIfNeed(recentsController: Any?) {
      val id = updateSeqId()
      nextFinishSeqId = recentsController to id
  }
  ```""",
 """- **lib**（修复后 `AnimationSeqHelper.kt:90-96`，已改为条件更新）：
  ```kotlin
  override fun updateNextFinishSeqIdIfNeed(recentsController: Any?) {
      val p = nextFinishSeqId
      // 原厂仅在 pair 为空或 controller 变更时才更新（AnimationSeqHelper.java:123-127）
      if (p == null || p.first != recentsController) {
          nextFinishSeqId = recentsController to updateSeqId()
      }
  }
  ```"""),
("现有测试 `AnimationSeqHelperTest.kt:testUpdateNextFinishSeqIdIfNeed`（`:78-90`）",
 "现有测试 `AnimationSeqHelperTest.kt:testUpdateNextFinishSeqIdIfNeed`（`:71-81`）"),
("| 6 | ⚠️待复核（未确认测试是否补）— 补 `testRepeatedUpdateSameControllerReturnsSameSeqId` 单测（§3-e 验证） | 8 行 | 锁定 §3-e 修复后的不变式 | 中 |",
 "| 6 | ⚠️未修复（复核 v2 确认：`AnimationSeqHelperTest.kt` 未补该测试；现有 `testUpdateNextFinishSeqIdIfNeed`（`:71-81`）只覆盖“不同 controller 返回 0”）— 补 `testRepeatedUpdateSameControllerReturnsSameSeqId` 单测（§3-e 验证） | 8 行 | 锁定 §3-e 修复后的不变式 | 中 |"),
("| 常量 KEY/MAX/MSG | `:18-24` | `:14-16,91-92`（MAX_GO_NORMAL_DELAY_TIME 缺失） |",
 "| 常量 KEY/MAX/MSG | `:18-24` | `:8-9,11-13`（MAX_GO_NORMAL_DELAY_TIME 缺失） |"),
("| 字段（seqId/delayRunnable/handler/nextFinishSeqId） | `:25-34` | `:53-63` |",
 "| 字段（seqId/delayRunnable/handler/nextFinishSeqId） | `:25-34` | `:29-36` |"),
("| `updateSeqId` | `:50-54` | `:79` |", "| `updateSeqId` | `:50-54` | `:53` |"),
("| `addSeqId` | `:56-66` | `:82-86` |", "| `addSeqId` | `:56-66` | `:55-59` |"),
("| `canFinishRecent` | `:68-71` | `:88-89` |", "| `canFinishRecent` | `:68-71` | `:61-62` |"),
("| `canInterceptGesture` | `:73-76` | `:91-92` |", "| `canInterceptGesture` | `:73-76` | `:64-65` |"),
("| `clearFinishRecentsRunnable` | `:78-84` | `:104-107` |", "| `clearFinishRecentsRunnable` | `:78-84` | `:81-84` |"),
("| `delayFinishRecents` | `:86-99` | `:95-103` |", "| `delayFinishRecents` | `:86-99` | `:67-79` |"),
("| `getNextFinishSeqId` | `:101-109` | `:111-115` |", "| `getNextFinishSeqId` | `:101-109` | `:98-103` |"),
("| `resetInterceptState` | `:111-114` | **缺失 override**（基类 `:15`） |",
 "| `resetInterceptState` | `:111-114` | `:86-88`（已 override，60bd048；基类 `:18` no-op） |"),
("| `updateNextFinishSeqIdIfNeed` | `:116-129` | `:109-112` |", "| `updateNextFinishSeqIdIfNeed` | `:116-129` | `:90-96` |"),
("- 4.1-6 → ⚠️待复核（单测是否补未确认）",
 "- 4.1-6 → ⚠️未修复（复核 v2 确认：`AnimationSeqHelperTest.kt` 未补该单测）"),
("`lib/.../seq/AnimationSeqHelper.kt:55-58` 仍无 feature gate；`OplusAnimManager.supportInterruption()` 恒 true（`manager/OplusAnimManager.kt:27`），实际零影响；OPPO `:59-62` 逻辑保留",
 "`lib/.../seq/AnimationSeqHelper.kt:55-59` 仍无 feature gate；`OplusAnimManager.supportInterruption()` 恒 true（`manager/OplusAnimManager.kt:37`），实际零影响；OPPO `:59-62` 逻辑保留"),
("`AnimationSeqHelper.kt:60-63` 仍只比时间窗", "`AnimationSeqHelper.kt:61-65` 仍只比时间窗"),
("`AnimationSeqHelper.kt:91-94` 已改为 `==`；OPPO `AnimationSeqHelper.java:105` 用 `Intrinsics.areEqual` 等价结构相等",
 "`AnimationSeqHelper.kt:98-103`（`:101`）已改为 `==`（`:100` 注释仍写「===」属残留）；OPPO `AnimationSeqHelper.java:105` 用 `Intrinsics.areEqual` 等价结构相等"),
("`AnimationSeqHelper.kt:81-83` override 调 `AnimSeqTimeStamp.resetLastStartAppTime()`，与 OPPO `:112-114` 等价",
 "`AnimationSeqHelper.kt:86-88` override 调 `AnimSeqTimeStamp.resetLastStartAppTime()`，与 OPPO `:112-114` 等价"),
("`AnimationSeqHelper.kt:85-90` 改为「pair 为空或 controller 不同才 ++」；OPPO `AnimationSeqHelper.java:124-128` 同结构",
 "`AnimationSeqHelper.kt:90-96` 改为「pair 为空或 controller 不同才 ++」；OPPO `AnimationSeqHelper.java:124-128` 同结构"),
("`AnimationSeqHelper.kt:6-7` 仅声明 `MAX_DELAY_TIME`/`MAX_INTERCEPT_GESTURE_DELAY_TIME`",
 "`AnimationSeqHelper.kt:8-9` 仅声明 `MAX_DELAY_TIME`/`MAX_INTERCEPT_GESTURE_DELAY_TIME`"),
("| 6 | `testRepeatedUpdateSameControllerReturnsSameSeqId` 单测 | ⚠️待复核（grep 未确认测试是否补） |",
 "| 6 | `testRepeatedUpdateSameControllerReturnsSameSeqId` 单测 | ⚠️未修复（复核 v2 确认：`AnimationSeqHelperTest.kt` 无该测试，现有 `testUpdateNextFinishSeqIdIfNeed`（`:71-81`）未覆盖同 controller 重复调用） |"),
]

fails = []
for old, new in R:
    c = t.count(old)
    if c != 1:
        fails.append((c, old[:70]))
    else:
        t = t.replace(old, new)
if fails:
    for c, s in fails:
        print('FAIL count=%d: %s' % (c, s))
    sys.exit(1)

v2 = """
## 复核记录 v2（2026-09-09，独立逐条复核）

- 复核条目总数：20（§3-a~f 共 6 条 + §4.1 共 7 条 + §4.2 共 6 条 + 批次4 汇总口径 1 条）；结论不变：19 条；修正：1 条（另 3-c 补注释残留说明，不改状态）
- 修正明细：
  - 条目 4.1-6（`testRepeatedUpdateSameControllerReturnsSameSeqId` 单测）：⚠️待复核 → ⚠️未修复，证据：`lib/src/test/java/com/asyncanimator/seq/AnimationSeqHelperTest.kt` 全文无该测试，现有 `testUpdateNextFinishSeqIdIfNeed`（`:71-81`）只验证“不同 controller 返回 0L”，未覆盖“同 controller 重复调用应返回相同 seqId”不变式。
- 状态复核确认（证据）：
  - 3-c ✅已修复：`AnimationSeqHelper.kt:101` 已用 `==`；但 `:100` 注释仍写「这里用 === 保持一致」与代码矛盾，已在条目内注明（注释残留，零行为影响）。
  - 3-d ✅已修复：`AnimationSeqHelper.kt:86-88` override 调 `AnimSeqTimeStamp.resetLastStartAppTime()`。
  - 3-e ✅已修复：`AnimationSeqHelper.kt:90-96` 已改为“pair 为空或 controller 不同才 ++”；`supportInterruption` 闸门仍未补（4.1-2 维持 ⚠️半修）。
  - 3-a / 3-b / 3-f / 4.1-4 / 4.1-5 / 4.1-7 维持 ⚠️未修复：`addSeqId`（`:55-59`）无 feature 闸门；`canFinishRecent/canInterceptGesture`（`:61-65`）仍只比时间窗；`MAX_GO_NORMAL_DELAY_TIME` 未补（`:8-9` 仅两个常量）；`OplusAnimManager.supportInterruption()` 恒 true（`manager/OplusAnimManager.kt:37`）。
  - 4.1-1 / 4.1-3 / 4.2-1..6 维持原判定。
- 顺手更正：lib 包路径 `com/asyncanimator/launcher/seq/` → `com/asyncanimator/seq/`；`OplusAnimManager` 引用路径 `launcher/manager` → `manager`；正文与批次5 表格中全部 lib 行号按当前文件（`AnimationSeqHelper.kt` 104 行、`DefaultAnimationSeqHelper.kt` 21 行）重校准；§1 方法表、§5 速查表中 “resetInterceptState 未 override” 的过期描述已改为 `:86-88` 已 override。
"""
t = t.rstrip('\n') + '\n' + v2
open(p, 'wb').write(t.replace('\n', '\r\n').encode('utf-8'))
print('doc25 OK, replacements:', len(R))
