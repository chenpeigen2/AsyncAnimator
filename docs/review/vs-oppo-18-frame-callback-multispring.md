# vs-oppo-18 — 多弹簧帧循环、阈值与结束协议复核

> **第 23/39 份：已完成本轮建议验收（含明确差异）。** 旧文“无六轴引擎、三值枚举、Handler 定时器、ATRACE”等判断已失效。本轮核对原厂帧循环与当前 AndroidX 1.1.0 源码，补真实 native 行为测试，不重复创建隐藏内核。进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## 1. 范围与证据

- 只读 OPPO 根 `D:\oppo_a6_launcher\sources\com\android\quickstep\util\animation`：MultiDynamicAnimation.java（121–130 启动、148–179 帧与结束、185–199 请求）、SpringHolder.java（33–35 阈值、106–138 更新）、SpringForce.java、SpringAnimReflectUtils.java（29–63）。
- 本地依赖的 **androidx.dynamicanimation:dynamicanimation:1.1.0 sources.jar**：DynamicAnimation.java 672–690 首帧/时间缩放，SpringAnimation.java 221–242 skip、265–302 积分，SpringForce.java 80/326–328 阈值；从 Gradle 缓存提取只读，不把反编译猜测当 API 事实。
- 本库 [RectSpringDriver](../../lib/src/main/java/com/asyncanimator/anim/RectSpringDriver.kt)、[SpringProjection](../../lib/src/main/java/com/asyncanimator/anim/SpringProjection.kt)、[AsyncSpringAnim](../../lib/src/main/java/com/asyncanimator/anim/AsyncSpringAnim.kt)、[AnimationHandler](../../lib/src/main/java/com/asyncanimator/core/AnimationHandler.kt)、[TickScheduler](../../lib/src/main/java/com/asyncanimator/core/TickScheduler.kt)。

## 2. 类与字段/帧协议对应

| 原厂 | 当前实现 / 修正 |
|---|---|
| MultiDynamicAnimation + N 个 SpringHolder | RectSpringDriver.Run + 六个实际 AndroidX SpringAnimation，公开 FrameCallbackScheduler 聚合每轮 native Runnable；每 tick 只发布一个完整六轴快照。不是名称/二进制替换件。 |
| platform android.animation.AnimationHandler | 原厂依赖隐藏平台类。本库自有 core Handler、AndroidX core Handler、AndroidX dynamicanimation Handler 是不同对象；安装自有 ThreadLocal 不会替换平台对象。 |
| addAnimationFrameCallback(this, 0L) | 0 是该 callback 的延迟，不是刷新率/全局帧间隔。本库自有入口没有 delayed 参数，Rect alpha 延迟由 driver 明确实现。 |
| 四元 AndroidX end 与二元 OEM end | 类型不同；Rect 句柄提供不可变 Event，普通 AsyncSpringAnim 保留 native canceled/value/velocity 并可投递主线程。 |
| mRunning / lastFrameTime / endRequest | Run 身份和 stop 请求 + 六轴 done barrier。自然完成、cancel、skip、dispose 的清理不依赖 Boolean 返回值自动注销。 |
| 首帧不积分 | AndroidX 与 OEM 均有首帧 warm-up。Rect 延迟轴启动后也保留 warm-up；不是所有轴第一次收到 tick 都已积分。 |
| 裸 frame delta | OEM 使用传入 frameTime 差；AndroidX 1.1 使用其 handler uptime 时间差，额外除系统 duration scale。Choreographer 不保证每帧无跳跃，也不免除长帧间隔。 |
| SpringHolder bounds / minimumVisibleChange | Rect 配置作用于 native springs；尺寸/ratio/radius/alpha 有边界，普通 SpringAnimation 仍走原生约束。不是所有 OEM 非法输入都兼容。 |
| pendingPosition 半步 | AndroidX animateToFinalPosition 有两次 delta/2；Rect retarget 直接更新 force 目标以保留当前速度，不调用半步 handoff。不能把两种 retarget 都称逐帧等价。 |
| SpringForce 三种阻尼解 | 实际播放用 AndroidX；仅纯预测用 SpringProjection。250ms 长间隔的三阻尼分支与下一 native 帧比较，不随意加 maxDt 改变总时间。 |
| minimumVisibleChange×0.75 / velocity×62.5 | 已查 sources：AndroidX 同为 0.75 与 1000/16。不是 OPPO 独有魔数，不通过生产反射写私有 threshold。 |
| String key map | OEM 私有 HashMap + addSpringHolderItem；没有旧文举例的公开“按名 requestEnd”查询链。本库固定六轴、不可变 Values 字段，无通用命名 spring 容器需求。 |
| SpringAnimReflectUtils | isAtEquilibrium 用反射；updateValues/setValueThreshold 直接调用。其他五个反射字段在该文件没有使用；不能仅据此推断历史版本动机或“仅 metadata、不是真字段”。本库不移植。 |
| commitAnimationFrame | 原厂实现的是 AnimationHandler.AnimationFrameCallback，并非可 cast 的 DynamicAnimation 子类；commit 转调 doFrame。不新增无调用方平台隐藏接口。 |
| 调试/异步监听 | LogUtils/Trace 是门控 stderr，不是 ATRACE。业务 AsyncAnimCallbacks 不替代物理帧聚合；后台数值更新仍须主线程写 View。 |

## 3. 结束协议的准确差异

| 操作 | AsyncSpringAnim / AndroidX | RectSpringDriver | 原厂 MultiDynamicAnimation |
|---|---|---|---|
| cancel | 命令在实际 owner 执行时立即 cancel，保留当时值，canceled=true；跨线程投递不保证同步到达。 | 下一 tick 物理结束，不再推进最后一步；句柄先给逻辑结束。 | requestEnd(true) 后下一积分 tick 调每个 Holder 更新，再以 canceled=true 结束；若尚无首帧，先 warm-up。 |
| skip | 只设 end request；正常下一帧到目标、速度归零、canceled=false。首帧前请求仍先 warm-up；零阻尼抛异常。 | 下一 tick 直接输出全部目标/零速度，可结束 delayed/零阻尼轴。 | requestEnd(false) 仍调用 Holder 积分，不保证该 tick 强制到 finalPosition；不能按名字臆断等同 AndroidX skip。 |
| natural | native equilibrium，canceled=false。 | 六轴均完成才清物理 barrier。 | 全部 Holder atRest 才结束。 |
| dispose | wrapper 没有统一最终释放 API，调用方持有并清理真实 spring；Demo11 自行清 listener/cancel。 | 即时撤帧并排空 native handler 清理 Runnable，无须再来 VSYNC。 | 不在此类提供同名最终销毁契约。 |

原厂 `SpringHolder.updateValueAndVelocity(deltaT, endRequest)` 的 endRequest 只影响结束返回：延迟未到可直接返回请求；否则仍积分并 clamp，只有 equilibrium 才强制目标/零速度。因此“原厂 cancel 会得到更准确 final 值”“skip 一定立即到目标”“相差 1–2px”等旧断言无依据。

## 4. 原 11 项风险逐项处置

| 原项 | 结论 |
|---|---|
| 1 requestEnd 下一帧 | ✅Rect 已有下一 tick 物理结束；上表明确首帧/最后一步差异。普通 wrapper 不偷偷改成另一协议。 |
| 2 六轴独立 stiffness | ✅第 21 份已实际实现并由 Demo12 消费，参数/几何/延迟/barrier 有回归。 |
| 3 cancel/skip 混淆 | ✅本轮补 4 条实际 AndroidX wrapper 测试与 KDoc/USAGE；保留 canceled 原语，不加入名为 endImmediately 却等下一帧的误导 API。 |
| 4 七值 AnimType | ✅七值与 Demo9/12 已接线；旧“无调用方/三值可用”撤销。 |
| 5 pendingPosition | ✔️原生半步路径保留；Rect 明确直接 retarget，不复制不可达的私有 setter。 |
| 6 threshold×0.75 | ✅源码核对 + 实际 force equilibrium 边界回归，不再写“应该一致/尚未审计”。 |
| 7 commit callback | ✔️不接平台隐藏接口；自有 TickScheduler 足以注入帧，不重复公开手动积分入口。 |
| 8 String key | ✔️六轴命名值快照足够；不虚构原厂公开单轴 requestEnd。 |
| 9 日志 | ✔️保留本库门控诊断；没有 Perfetto 证据、不照搬 Debug.getCallers 开销。 |
| 10 速度阈值 62.5 | ✅依赖源与实际 equilibrium 速度边界一致；不宣称未来 AndroidX/OEM 版本必然一致。 |
| 11 反射残留字段 | ✔️本文件未使用，不移植，也不从反编译产物推断旧版迁移史。 |

## 5. 原回移/简化/文档建议（3 + 7 + 3）

1. **七值枚举**：已落地，不添加旧伪枚举或重复 API。
2. **EndReason/endImmediately**：有理由不加。现有 canceled 能区分取消与非取消，native 不区分 skip/natural；如未来业务必须区分需另定取消竞态/复用协议，不能仅加 enum 声称功能完成。
3. **最小共享帧循环**：Rect driver 已实现真实六轴 native scheduler、coherent sink、完成聚合；不能只在每帧调 cancel/skip 就称共同积分引擎。
4. 原简化①四件套：不复制隐藏平台/反射播放内核；六轴可移植功能已补，不再以“无关动画线程/没有消费者”拒绝。
5. 原简化②六轴/SurfaceControl：两者拆开；六轴数值/几何和 Canvas Demo12 已实现，系统窗口事务仍明确未接。
6. 原简化③commit：无平台接口调用方，保持不接。
7. 原简化④日志：已有 LogUtils/Trace，明确不是 native trace；不照搬 OEM 日志堆栈。
8. 原简化⑤反射工具：公开 force/scheduler 与独立预测足够，不移植。
9. 原简化⑥String key：固定六轴命名快照代替通用容器，不扩不需要的动态查询。
10. 原简化⑦threshold：已源码核对和实际测试，不直接调用 AndroidX package-private setter。
11. 文档①共享帧限制：USAGE 已说明 Rect driver 聚合而普通 wrapper 不聚合；六条同 owner 的 native springs 不必然“各走不同 VSYNC”，旧因果论断撤销。
12. 文档②cancel：本轮写清命令到 owner 才同步停止、skip/首帧/零阻尼契约及 Rect 区别。
13. 文档③枚举：按当前七值公开 API 更新，旧三值“已修复”不再保留为有效说明。

## 6. 验证

- 新增 [AsyncSpringAnimTest](../../lib/src/test/java/com/asyncanimator/anim/AsyncSpringAnimTest.kt) 4 条，真实 native cancel、skip、首帧 warm-up、零阻尼；不是修改真实引擎来迎合旧文。
- [RectSpringDriverTest](../../lib/src/test/java/com/asyncanimator/anim/RectSpringDriverTest.kt) 补 250ms 长帧三阻尼/纯预测比较和 0.75×62.5 的实际阈值边界。反射仅用于测试读取实际 force，不进入生产代码。
- 定向 29 tests 通过；最终 **Debug/Release 各 272 tests、38 类、0 failures/errors/skipped**，Demo Debug 成功，82/82 tasks executed、2m 15s（`.gradle/review-ordered-23-final.log`）。115 份源码/测试/两模块配置 SHA-256 前后一致。
- `git diff --check` 与相对文档链接通过；无设备/Perfetto 验证，Lint 未完成。既有告警保留；OPPO、AGENTS.md 和无关文件未改，无提交/推送。
