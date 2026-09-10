# vs-oppo-23 — PendingAnimation 组装与播放端到端复核

> **第 28/39 份：✅完成（含明确保留边界）。** 按本地实际代码与 OPPO 只读源码逐项复核，替换旧报告重复建议和失效状态。进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## 1. 范围与源码依据

本地：`lib/src/main/java/com/asyncanimator/playback/` 下 PendingAnimation、AnimatorPlaybackController、PropertySetter、AnimatorListeners；兼容调用方为 `anim/OplusValueAnimator.kt` 中 TimeControllerObjectAnimator。

OPPO 根目录：`D:/oppo_a6_launcher/sources/`，对应 `com/android/launcher3/anim/`：

- `PendingAnimation.java:31-50`：非正时长归零、根生命周期；60-110：属性组装、progress 合并、build 与 controller 缓存；127-144：单值/多值 ObjectAnimator；187-214：时长与 Holder 收集。
- `AnimatorPlaybackController.java:128-158`：主播放器、根监听、Holder 数组快照；160-182：递归前下发父 duration/interpolator。
- `PropertySetter.java:14-28`：默认 add 将动画时长设零后启动；默认 setFloat 立即写属性。原报告说本地继承默认 add，不符合事实：本地接口没有 add。

`PendingAnimation` 和 APC 仍为 **internal**；Demo9 使用公开 MultiAnimatorSet，不把字符串/日志中的 PendingAnimation 当真实调用。此轮测试实际创建 Pending/APC，不用场景模拟替代库测试。

## 2. 本轮修复

### R1：隐式起点不是组装时显式读取的等价写法

旧代码短路检查后再次读取属性，固定 from，再包装 ValueAnimator。如果组装时值为 2、首次 seek 前变成 6、终点为 10，半程应为 8，旧实现却为 6。

- `setFloat` 改用平台单终值 `ObjectAnimator.ofFloat(target, property, value)`；短路只读一次，平台初始化/首次 seek 捕获起点。首次初始化后不会每帧重新取起点。
- `addFloat` 改用平台显式 from/to 重载；仍保留调用方明确指定的起点，不跟随随后 target 变化。
- 平台 ObjectAnimator 本身属于 ValueAnimator，可直接加入 Holder，不再通过自定义映射器写属性。

### R4：嵌套 AnimatorSet 有可达入口，不能因 Demo 平铺就忽略

`add`、`addWithoutDuration` 和 `APC.wrap` 均可接收嵌套集合。手动 seek 不会先执行 `AnimatorSet.start()` 的初始化，因此收集 Holder 前必须递归继承设置：

1. 父 duration **严格大于 0** 才写入子动画；-1/0 不覆写子时长，忠实保留 OEM 规则。
2. 父 interpolator 非 null 才下发；正时长和曲线从外向内覆盖，再构造叶子 Holder。
3. Holder 捕获的是最终继承后的 duration/curve，`pause()` 的 reset 也恢复该曲线。

这是 seek 的 Holder 收集规则，不承诺零时长根集合直接 start 与手动 seek 完全等价。startDelay、顺序集合时间轴及零总时长 mapper 边界不由本项修复自动获得支持，后续 APC 专项继续核对。

### R6：保留 TimeController 适配器，但 build 不重复注册

自定义 `PendingAnimation.ObjectAnimator` 有真实子类 TimeControllerObjectAnimator，不能直接删除。属性 setter 不再使用它；其稳定 backing ValueAnimator 与时间控制委托保留。映射监听移到初始化时仅注册一次，重复 `buildAnimator()` 不再导致同一帧多次属性写入；null property 的 TimeController 不添加空映射监听。

## 3. 原 A1–A18 / B1–B8 复核

| 原编号 | 当前结论 |
|---|---|
| A1/A2/A3 | add 强制总时长，带曲线重载先写曲线，addWithoutDuration 保留 child 自有时长；嵌套传播已补。 |
| A4/A5 | 显式 addFloat 与隐式 setFloat 均用平台属性动画；null/当前值相等仍短路。 |
| A6/A7 | frame/end 共用懒建 progress animator；普通 listener 挂根。 |
| A8/A9 | build 合并 progress 时走 add 写时长，并清 progress 引用；空集合补有时长的 ValueAnimator。 |
| A10/A11 | build 返回同一根、controller 懒建缓存；Controller 拷贝 Holder 数组。必须先完成组装再创建 controller，后加 child/callback 不会更新旧快照。 |
| A12/A13 | LINEAR 主 ValueAnimator + OnAnimationEndDispatcher 仍挂主播放器，不改到首个 child。 |
| A14 | ValueAnimator 收集、AnimatorSet 递归、未知 Animator 显式 RuntimeException；不静默漏动画，也不声称异常回滚组装。 |
| A15/A16/A17/A18 | 相对终点、mapper/setCurrentFraction、默认截断、reset 机制保留；继承值在快照前生效。完整 mapper/播放器收尾另见下一份。 |
| B1/B2 | SpringProperty 及业务重载/访问器按 C 表保留精简；方法返回 PendingAnimation 便于链式调用，不改变实际组装。 |
| B3 | Consumer/Runnable 以 Kotlin 函数类型表达，语言层适配保留。 |
| B4/B5/B6 | 空占位已显式设时长，let 是语法差异，逐个 playTogether 添加节点；不等于把传入的嵌套集合拍平。 |
| B7/B8 | 父级传播本轮修复；取消/开始/结束监听此前已挂根，保留现状。 |

## 4. 原 C1–C10 / R1–R12 处置

| 原项 | 状态与边界 |
|---|---|
| C1/C2、R12 | ✔️保留少量 Float API，不凭无调用方补 setFloats/setInt/背景色/AlphaUpdateListener。FloatProperty 可表达数值 alpha，但不包含可见性更新等 OEM 业务副作用。TextView 是 View 子类，旧“不能强转 View”示例错误，删除。 |
| C3 | ✔️不增加冗余 getter：buildAnim 已返回可操作的实际 AnimatorSet；duration 由构造者持有。 |
| C4 | ✔️frame callback 保留无参入口；无消费者需要带 ValueAnimator 的单独重载。 |
| C5/C9 | ✔️不增加整体 setter/dispatchSetInterpolator API；配置应在 add/Holder 快照前完成。本轮递归传播不等于支持事后修改 root 后刷新所有 Holder。 |
| C6 | ✅非正时长归零、isAnimFinished 根生命周期已实现。OEM mDuration 实际是 **final**，旧报告“非 final 反编译 quirk”错误。 |
| C7 | ✔️child.setDuration + addWithoutDuration 可保留子时长，不补无调用方 add(anim,long)。 |
| C8 | ✔️本地接口只有 setFloat，没有默认 add；Pending 自有 add 是有效组装方法。没有需要改为 internal 的 mAnim 字段，不扩散可变根引用。 |
| C10、R5/R8 | ✅根三种回调与 start/reverse 已维护 dispatch pending；不是本轮新修复。 |
| R1/R4 | ✅本轮修复起点捕获和父级传播。 |
| R2/R3 | ✅progress 时长/未知 Animator 显式失败此前已实现，保留。 |
| R6/R9 | ✅平台属性动画直接加入 Holder，兼容 wrapper build 幂等；链式返回不妨碍根/Holder 持有实际 animator。 |
| R7 | ✅已有真正递归 DFS 与监听快照，嵌套 start/cancel/end、自注销测试保留；旧“只有根+一层”结论过期。 |
| R10 | ✔️无独立 cancel 方法不等于无取消路径：buildAnim().cancel() 操作实际根；APC 手动驱动则 pause/dispatchOnCancel 按其生命周期配合，不说 root.cancel 自动停止主播放器。 |
| R11 | ✔️addToHolders 是私有辅助命名，不机械照搬 OEM 方法名。 |

## 5. 原建议逐项验收

- **4.1-1/2**：根监听与 start pending 已有正确实现，不重复修改。
- **4.1-3**：本轮补父级传播，Pending 和 wrap 两个入口都有回归。
- **4.1-4**：保留已有递归监听派发及两条测试。
- **4.1-5/6/7**：cancel/getter/放开根字段不新增；理由分别见 R10/C3/C8。
- **4.2-1**：按 C1–C10 明确缺省能力，不把缺接口称为实现完成；**4.2-2**：旧 R1“零风险等价”已推翻，R6 补幂等；**4.2-3**：纠正 OEM duration 为 final。
- **4.3-1/2**：组装→Holder 快照→主时钟→子属性路径已有真实测试；**4.3-3/4**：根监听/递归派发保留；**4.3-5**：未知类型仍显式失败；**4.3-6**：平台属性动画与兼容 wrapper 分清，不删除实际子类。

原重复表合并，64 个旧编号（18 A + 8 B + 10 C + 12 R + 7/3/6 建议）按上述分组处理；这不是新增 64 个测试或全部 OEM API 已移植。`NO_ANIM_PROPERTY_SETTER` 指“不创建动画、立即写入”，不是“不做任何事”。

## 6. 验证记录

- PendingAnimationTest 新增 7 条（6→13）：隐式起点/读取次数、显式起点、两种嵌套继承入口、-1/0/null 保留、wrapper build 幂等、build/controller/progress 缓存。
- 旧实现 13 条中 4 条失败（`.gradle/review-ordered-28-red.log`），修复后 playback 全部测试及 OplusValueAnimator 兼容测试共 32 条通过（`.gradle/review-ordered-28-green.log`）。
- **Debug/Release 各 302 tests、41 类，0 failures/errors/skipped**；Demo Debug 成功，82/82 tasks executed，2m 8s（`.gradle/review-ordered-28-final.log`）；118 份源码/测试/两模块配置 SHA-256 前后一致。
- README 测试表、Markdown 相对链接、`git diff --check` 及 AGENTS.md 未改检查通过。无设备/Perfetto 验证，Lint 未完成；既有构建警告保留，无提交/推送。
