# 区域 29 对比 Review：Trace 标签一致性（本轮验收）

> ✅第 34/39 份完成（明确保留诊断边界）。源码修复、定向及完整双变体验证通过。
> 本文替代旧版及 v2 的过期结论；OPPO 参考树 `D:/oppo_a6_launcher/sources` 只读。

## 1. 当前实现与原厂证据

| 范围 | 本地实际实现 | OPPO 对照 |
|---|---|---|
| Trace | `core/Trace.kt`，ThreadLocal 栈、begin 时记录日志策略、finally 配对 | `com/oplus/basecommon/util/TraceHelper.java` 转发平台 Trace |
| Async 回调 | `anim/AsyncAnimCallbacks.kt`，主线程实际派发中 section；actual-end 仅日志 | `com/android/quickstep/util/animation/AsyncAnimCallbacks.java`，id/type 拼名，actual-end 仅 LogUtils |
| 序列 | `seq/AnimationSeqHelper.kt`，timer begin/end + delay section | `com/oplus/quickstep/utils/AnimationSeqHelper.java` |
| 续接 | `anim/OplusValueAnimator.kt`，continuation-fail 含 f、continuation begin/end | `com/oplus/quickstep/utils/OplusValueAnimator.java` 原厂为日志，不是这些本地 trace 名 |

本地三个调用文件、五个逻辑位置（3 对显式 begin/end + 2 个 section），不是旧文的四处。start/cancel/end 共用一次 section 调用表达式，不重复计数。八处调用表达式统一使用 `Trace.TAG_VIEW`。

核对 AOSP `frameworks/base/core/java/android/os/Trace.java`：VIEW=8，WINDOW_MANAGER=32，ACTIVITY_MANAGER=64，CAMERA=1024，APP=4096。旧文把 8 当 APP、32 当 NOT_NEEDED、1024 当 VIEW 均错误；不能从 OEM 调用文件名反推 tag 含义。参考源码地址：`https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/os/Trace.java`。

## 2. 本次实施

- 新增可选 `setAnimType(AnimType)` 诊断上下文；与事件 generation/id 一起在投递前捕获。随后改 id/type 不会改写已排队事件的名字；`dispose()` 清空 type，旧代事件仍被丢弃。
- start/cancel/end 的 trace 和日志、actual-end 的日志均使用捕获值，例如 `AsyncAnimStart-41 type=OPEN_FROM_HOME`。
- 未设置时输出 `UNSPECIFIED`，不沿用原厂默认 SWIPE_TO_HOME 假装通用 ValueAnimator 属于该业务。setter 不选择动画引擎，也不把独立 Rect 生命周期接到此容器。
- 新增内部具名常量 `TAG_VIEW = 8L`，不改变 stderr 后端、不扩大平台 API 依赖。
- 新增三项回归：排队四事件保留 id/type、dispose 清诊断类型、嵌套 section 返回值/异常/空栈结束。前两项在仅有 setter 的旧实现失败，第三项验收既有 finally 行为。

## 3. 原七项建议逐项处置

| 原建议 | 结果 |
|---|---|
| 1 栈并发保护 | ✅已有 per-thread ThreadLocal，保留；全局 concurrent deque 或 synchronized 并不能提供线程配对语义。 |
| 2 增加动画类型 | ✅本轮实现诊断 setter、排队快照及 dispose 清理；不声称泛型 Demo 已有真实 OPEN 场景来源。 |
| 3 end tag 校验 | ✔️保留调用方同线程/同 tag 配对契约，不引入与平台无关的抛错或跳栈规则。AOSP Java 按 tag 过滤并转 native，本地不是同一实现；具名常量降低笔误。 |
| 4 四事件 LogUtils | ✅已有门控日志，本轮补 type；纠正“库没有 LogUtils”旧说法。 |
| 5 fail 带 f / 调用栈 | ✅f 已有；✔️不接隐藏 Debug.getCallers，也不默认在失败路径采昂贵堆栈。 |
| 6 main 二次锚点 | ✅实际 main listener 派发已有 section 和回归；✔️不创建空 TracePrintUtil 桩或把 stderr 叫 Perfetto slice。 |
| 7 end 移至 main | ✅begin/end 整段已在 main 的 section 内，finally 关闭。不是只移动 end；原“保持外层等价”结论过期。 |

## 4. 原风险、十项简化及不确定项

- 风险 1/9 的共享栈和 depth 并发已由 ThreadLocal 解决；2 类型缺失本轮修复；3 tag 不模拟平台过滤；5 日志缺失过期；6 fail 名空过期；7 trace 未覆盖派发过期。原编号无 4。
- 风险 8：LogUtils 输出线程名但没有内置时间戳，保持轻量日志，不声称能直接测量帧时序；10：Kotlin 字符串插值及 `run { ... }` 是立即求值，并非默认 lazy；11：不增栈深硬上限去吞合法嵌套；12：空 listener 快照仍可留下生命周期诊断。
- 简化 1/2：保持 stderr；未接平台 Trace、async trace 或 JankTracker，async slice 不等于 counter，不宣称跨线程 Perfetto 配对已验收。
- 简化 3/4：不复制 WM/Camera 系统调用；先纠正 32/1024 常量名称，不能把模块缺失直接定成 bug。
- 简化 5/6/8：无 TaskSnapshotWindow 动态 tag、PostEffect 或 TraceHelper FLAG 消费者，不造无调用点包装。
- 简化 7：当前小字符串不增加 lazy API；不以错误的“已 lazy”为理由。
- 简化 9：actual-end 保留 log-only，与原厂一致；简化 10：不移植 TracePrintUtil 六类子系统分类或 BASIC_ID。
- 两个不确定项均已静态判清：派发 begin/end 同在 main；栈按线程分层。设备 trace 仍未验证，但不阻碍以上源码契约验收。

## 5. 验证记录

- 定向 `TraceLogTest`：9 tests 通过；red 阶段 2 项失败，green 全绿。日志 `.gradle/review-ordered-34-red.log` / `-green.log`。
- Debug/Release 各 **331 tests / 44 类，0 failures/errors/skipped**；Demo Debug **82/82 tasks executed，2m 59s**。日志 `.gradle/review-ordered-34-final.log`。121 份源码/测试/模块配置 hash 前后一致，README/链接/whitespace 检查通过。
- 无设备/Perfetto 验证，Lint 未完成；未修改 AGENTS.md / OPPO，未提交或推送。
