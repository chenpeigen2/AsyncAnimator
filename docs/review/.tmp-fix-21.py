import sys
p = r'D:\AsyncAnimator\docs\review\vs-oppo-21-delay-start-activity-decision-tree.md'
t = open(p, encoding='utf-8').read()

def rep(old, new, cnt=1):
    global t
    n = t.count(old)
    assert n == cnt, 'count %d!=%d for: %r' % (n, cnt, old[:90])
    t = t.replace(old, new)

# 1. header
rep('- **lib**：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/launcher/controller/AnimationController.kt:175-201` `override fun delayStartActivityIfNeed(...)`',
    '- **lib**：`D:/AsyncAnimator/lib/src/main/java/com/asyncanimator/control/AnimationController.kt:220-251` `override fun delayStartActivityIfNeed(...)`（e62dbff 包重组后路径）')

# 2. 取证方法
rep('OPPO `AnimationController.java` 实际是明文，63.7 KB，285 行 JADX 反编译文本 + Kotlin metadata 头',
    'OPPO `AnimationController.java` 实际是明文，62.7 KB，994 行 JADX 反编译文本 + Kotlin metadata 头')

# 3. §0 总行数
rep('行号 = `AnimationController.java` 的 JADX 文本行号，285 行反编译总行',
    '行号 = `AnimationController.java` 的 JADX 文本行号，994 行反编译总行')

# 4. §0 lib 区段表
rep('lib 对应区段（`AnimationController.kt:175-201`）：', 'lib 对应区段（`AnimationController.kt:220-251`）：')
rep('| `startActivityAction = null` 重置 | `:176` |', '| `startActivityAction = null` 重置 | `:222` |')
rep('| **第一层** `specialSceneExitTimeOutListener != null` | `:178-183` |', '| **第一层** `specialSceneExitTimeOutListener != null` | `:224-230` |')
rep('| **第二层** `else if transitionFinishTimeOutListener != null` | `:184-188` |', '| **第二层** `else if transitionFinishTimeOutListener != null` | `:231-235` |')
rep('| **第三层** `else if overviewContinuationTimeOutListener != null` | `:189-196` |', '| **第三层** `else if overviewContinuationTimeOutListener != null` | `:236-240` |')
rep('| **清理段** | `:197-201` |', '| **清理段** | `:242-250` |')

# 5. §1 table
rep('`AnimationController.delayStartActivityIfNeed(...)` 声明 `AnimationController.kt:175`', '`AnimationController.delayStartActivityIfNeed(...)` 声明 `AnimationController.kt:220`')
rep('| `AnimationController.kt:177-201` 27 行方法体 |', '| `AnimationController.kt:222-251` 30 行方法体 |')
rep('| `AnimationController.kt:43-47` `specialSceneExitTimeOutMaxTime` 字段 |', '| `AnimationController.kt:47` `specialSceneExitTimeOutMaxTime` 字段 |')
rep('| `AnimationController.kt:46` `overviewContinuationTimeOutMaxTime` 字段 |', '| `AnimationController.kt:48` `overviewContinuationTimeOutMaxTime` 字段 |')
rep('| `AnimationController.kt:30` `isLandScapeGesture` |', '| `AnimationController.kt:33` `isLandScapeGesture` |')
rep('| `AnimationController.kt:31` `isSplitScreenGesture` |', '| `AnimationController.kt:34` `isSplitScreenGesture` |')
rep('| `AnimationController.kt:32` `isNavModeLandScapeOnAppExit` |', '| `AnimationController.kt:35` `isNavModeLandScapeOnAppExit` |')
rep('| `AnimationController.kt:33` `isBetweenAppExitTransitionEndAndFinish` |', '| `AnimationController.kt:36` `isBetweenAppExitTransitionEndAndFinish` |')
rep('| `AnimationController.kt:34` `isBetweenTransitionEndAndFinish` |', '| `AnimationController.kt:37` `isBetweenTransitionEndAndFinish` |')

# 6. §2.1
rep('`isLandScapeGesture` （`AnimationController.kt:30, 180`）', '`isLandScapeGesture` （`AnimationController.kt:33, 226`）')
rep('`isSplitScreenGesture` （`AnimationController.kt:31, 180`）', '`isSplitScreenGesture` （`AnimationController.kt:34, 226`）')
rep('`isNavModeLandScapeOnAppExit` （`AnimationController.kt:32, 181`）', '`isNavModeLandScapeOnAppExit` （`AnimationController.kt:35, 227`）')
rep('`isBetweenAppExitTransitionEndAndFinish` （`AnimationController.kt:33, 181`）', '`isBetweenAppExitTransitionEndAndFinish` （`AnimationController.kt:36, 227`）')
rep('`SystemClock.uptimeMillis() > specialSceneExitTimeOutMaxTime` 反向 （`:179`）', '`SystemClock.uptimeMillis() > specialSceneExitTimeOutMaxTime` 反向 （`:225`）')

# 7. §2.2
rep('`transitionFinishTimeOutListener != null`（`:184`）', '`transitionFinishTimeOutListener != null`（`:231`）')
rep('`isSplitScreenGesture`（`:185`）', '`isSplitScreenGesture`（`:232`）')
rep('`call?.invoke() == true`（`:185`）', '`call?.invoke() == true`（`:232`）')

# 8. §2.3
rep('`overviewContinuationTimeOutListener != null`（`:189`）', '`overviewContinuationTimeOutListener != null`（`:236`）')
rep('`SystemClock.uptimeMillis() < overviewContinuationTimeOutMaxTime`（`:190-191`）', '`SystemClock.uptimeMillis() < overviewContinuationTimeOutMaxTime`（`:237`）')

# 9. §2.4 清理段
rep('| `:653` | `AnimationController.kt:197` |', '| `:653` | `AnimationController.kt:242` |')
rep('| `:654` | `AnimationController.kt:198` |', '| `:654` | `AnimationController.kt:243` |')
rep('| `:655-669` | `AnimationController.kt:199-201`（用 `?.dispose()` 一行一个 + null） |', '| `:655-669` | `AnimationController.kt:244-249`（用 `?.dispose()` 一行一个 + null） |')

# 10. A1
rep('**lib**：`AnimationController.kt:189-196` 用 `if (SystemClock.uptimeMillis() < overviewContinuationTimeOutMaxTime)`',
    '**lib**：`AnimationController.kt:236-240` 用 `if (SystemClock.uptimeMillis() < overviewContinuationTimeOutMaxTime)`')
rep('（`AnimationController.kt:147-152`）', '（`AnimationController.kt:214-216`）')

# 11. A2
rep('（`AnimationController.kt:175, 184-188`）', '（`AnimationController.kt:220, 231-235`）')

# 12. A3
rep('**lib**：`AnimationController.kt:180` `isLandScapeGesture` 单独参与第一层 OR', '**lib**：`AnimationController.kt:226` `isLandScapeGesture` 单独参与第一层 OR')

# 13. B3
rep('`AnimationControllerTest.kt:1-94` 测试覆盖了', '`AnimationControllerTest.kt:1-110` 测试覆盖了')
rep('三个 listener 注册独立性 —— **但完全没有 `delayStartActivityIfNeed` 的测试**',
    '三个 listener 注册独立性、`cleanUpRecentsAnim` —— **但完全没有 `delayStartActivityIfNeed` 的测试**（文件头注释 `AnimationControllerTest.kt:21` 虽把三层决策树列入验证目标，实际无用例）')

# 14. §4-D + 复核记录
rep('`TaskStateChangeTimeOutListener.kt:36-38` 仅 `handler.removeCallbacks`', '`TaskStateChangeTimeOutListener.kt:41-43` 仅 `handler.removeCallbacks`', 2)

# 15. §6.1 markers
rep('第三层仍时间窗（AnimationController.kt:189-196）', '第三层仍时间窗（AnimationController.kt:236-240）')
rep('第二层仍不读 intent（AnimationController.kt:184-188）', '第二层仍不读 intent（AnimationController.kt:231-235）')
rep('第一层 isLandScapeGesture 仍无 !isTablet conj（AnimationController.kt:180）', '第一层 isLandScapeGesture 仍无 !isTablet conj（AnimationController.kt:226）')

# 16. §6.2-1
rep('（`OplusAnimManager.kt:23-29`），feature off 时返回的是 `DefaultAnimationController`（`delayStartActivityIfNeed` 直接 return false）',
    '（`OplusAnimManager.kt:25-30, 39-40`），feature off 时返回的是 `DefaultAnimationController`（`delayStartActivityIfNeed` 直接 return false，`DefaultAnimationController.kt:57-58`）')

# 17. 复核记录 v1
rep('代码仍 `uptimeMillis() < overviewContinuationTimeOutMaxTime`（AnimationController.kt:189-196）', '代码仍 `uptimeMillis() < overviewContinuationTimeOutMaxTime`（AnimationController.kt:236-240）')

v2 = '''
## 复核记录 v2（2026-09-09，独立逐条复核）
- 复核条目总数：17（A1-A3、B1-B3、C1-C2、§4-D dispose 行、§6.1-1..4、§6.2-1..4）；结论不变：17 条；修正：0 条状态变更
- 逐条验证结果（lib 现码 `control/AnimationController.kt:220-251` 逐行比对，原厂 `AnimationController.java:598-671/825-837` 抽查比对）：
  - A1 第三层时间窗 vs 运行态：⚠️未修复 成立 —— lib 仍 `AnimationController.kt:237` `uptimeMillis() < overviewContinuationTimeOutMaxTime`；lib/demo 全树 Grep 无 `AppSwipeToRecentContinuationHelper`；原厂 `java:646` 确为运行态查询
  - A2 isSpecialAppScene：⚠️未修复 成立 —— lib 第二层 `kt:231-235` 仍 `isSplitScreenGesture || call?.invoke() == true` 不读 intent；lib 无 `isSpecialAppScene`（原厂定义 `java:283-290`）
  - A3 !isTablet conj：⚠️未修复 成立 —— lib `kt:226` `isLandScapeGesture` 仍单独参与 OR；原厂 `java:620` `z13 = mIsLandScapeGesture && !ScreenUtils.isTablet()` 已抽查确认
  - B1 顶部 supportInterruption guard：⚠️未修复 成立 —— lib `kt:220-251` 方法体无 guard（原厂 `java:601-603` 已抽查确认）；§6.2-1 的"factory gate 等效"论证亦成立（`OplusAnimManager.kt:39-40` + `DefaultAnimationController.kt:57-58`），两口径并存保持原状
  - B2 第二层日志：✔️保持简化 成立 —— lib 第二层仍无日志
  - B3 无单测：⚠️未修复 成立 —— `AnimationControllerTest.kt`（现 110 行）仍无 `delayStartActivityIfNeed` 用例（头注释列出该主题但无对应 @Test）
  - C1 displayController 懒加载 / C2 并入 A1：✔️保持简化 成立
  - §4-D dispose 全局事件总线：❌遗漏 成立 —— `TaskStateChangeTimeOutListener.kt:41-43` dispose 仍仅 `handler.removeCallbacks`
  - §6.1-1..4 / §6.2-1..4 回移建议标记：均与上述验证一致
- 事实性更正（非条目状态）：取证段与 §0 称原厂文件"63.7 KB，285 行"有误，实测 62.7 KB / 994 行（doc 内全部行号引用 598-671/825-837 与 994 行文件一致，仅"总行数"描述失准）
- 路径/行号更正（非状态变更）：lib 包路径 `launcher/controller/` → `control/`（e62dbff）；方法体 `kt:175-201` → `:220-251`；第一层 `:178-183` → `:224-230`；第二层 `:184-188` → `:231-235`；第三层 `:189-196` → `:236-240`；清理段 `:197-201` → `:242-250`；字段 `:30-34/:43-47` → `:33-37/:47-48`；`setAppToOverviewContinuationState kt:147-152` → `:214-216`；`TaskStateChangeTimeOutListener.kt:36-38` → `:41-43`；`AnimationControllerTest.kt:1-94` → `:1-110`；复核记录 v1 中失准行号同步更正
'''
t = t.rstrip('\n') + '\n' + v2
open(p, 'w', encoding='utf-8', newline='').write(t)
print('doc21 OK')
