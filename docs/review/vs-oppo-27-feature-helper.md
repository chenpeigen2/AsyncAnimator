# vs-oppo-27 — AnimationFeatureHelper 配置发布、策略与生命周期复核

> **第 32/39 份：✅完成（含明确边界）。** 十项风险、七项建议、六项简化与四步落地顺序均按当前代码重新核对；不把已存在的 snapshot/监听/Adaptive 当缺失。进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## 1. 源码依据与当前功能

- 本地 `lib/src/main/java/com/asyncanimator/manager/AnimationFeatureHelper.kt`；Demo8FeatureFlagActivity；FeatureSnapshotTest / FeatureNotificationTest / ManagerLifecycleTest。
- OPPO 只读根 `D:/oppo_a6_launcher/sources/`：`com/oplus/quickstep/utils/AnimationFeatureHelper.java:52-60` 默认值，82-92 注册，99-156 私有同步 setter，237-242 multi-app 副作用，322/347 起的列表原地更新，413-427 radius/注销；`com/android/common/util/AppFeatureUtils.java:2879-2890` 派生 blockable 及重建 helper。
- 六个整数默认 -1、threshold 默认 1.0f；本地已有完整 7 标量+2列表更新、6 标量兼容重载、private set、不可修改列表副本、同锁 snapshot、主线程可注销通知及显式 Adaptive 策略。
- Demo8 已用 snapshot 和订阅刷新；它不是 ROM RUS 接入，也不是所有配置字段都有动画引擎消费者。

## 2. 本轮新增修复：先准备两个列表，再提交配置

旧全量更新先写七标量，再复制 package/card 列表。任一调用方 List 的复制抛异常会留下部分新配置；第二个列表失败时，第一个列表也已被替换。List.get/iterator 的自定义代码还能在锁重入中观察到半批状态。

现顺序：**复制 package + card 到不可修改副本 → 取得配置锁 → 更新标量和可选列表 → 取得通知注册快照 → 主线程通知**。

- 任一复制失败原样抛异常，本次调用尚未修改配置，也没有排队通知。
- 调用方集合遍历不发生在配置锁内；两个副本都成功后才提交。
- null 表示提交时保留当前列表；空列表表示替换为空。6 标量重载仍保留 onePxEnable 和两列表，不预读旧值再回写。
- 调用方仍必须避免复制期间并发修改其输入集合；本库不保证可变输入的无锁一致读取。两个并发更新按实际提交顺序生效，不承诺请求开始顺序。
- 如果自定义集合主动调用其它更新 API，那是单独的外部操作；“本次复制失败不修改”不回滚外部操作。

本轮三条测试先全部失败：package 失败、card 失败、复制期间重入读取；修复后分别验证完整旧 snapshot 保持/列表遍历时只能看到旧完整状态。

## 3. 策略与 Demo 的真实边界

### Adaptive

已有 setAdaptiveAnimationEnabled：启用时 threshold=1，后续两种更新重载均保持该值；radius 查询返回 false。关闭时不神奇恢复历史 threshold，下一次下发才改变。它是宿主显式策略与查询结果，不代表已连接 LauncherAnimConfig 或半径渲染引擎。

### multi-app 字段不是顶部工厂开关

OEM 的派生公式包含 Settings 字符串、硬件 feature 和 RUS 值；派生值改变才 recreateAnimHelper。不能仅用 `multiAppBlockEnable != 0` 冒充完整公式，也不能增加总返回 true 的 provider 就宣称接通。

本地保留配置数据，不自动重建 Manager。**Demo8 本轮新增页面文字**明确：multi-app 等字段仅展示配置；Impl/no-op 由顶部 Interruption 开关控制。这样不再让字段变化冒充系统能力切换。

### Touch guard

Default controller 的 forbidTouch 仍 false；实际 AnimationController 已覆盖主线程 600ms 开窗、MULTI_WAITING、REVERSE_OPEN、挂起 action 判据，Demo9 已接查询。旧“库始终没有 touch guard”过期；但这不等于 OEM 大屏+Quickstep/Surface 的 reverse-open 物理状态，不额外接假的系统 provider。

### 通知与释放

本地已经有真实订阅：返回句柄关闭、按回调身份移除、onDestroy 全局注销。页面只关闭自有句柄。关闭会使排队投递失效，不能召回已领取/执行中的回调；onDestroy 保留配置且允许后续新注册。普通 Exception 隔离并门控记录，Error 不在恢复承诺中。

通知始终投递主线程，监听读到最新 snapshot，不承诺每个历史下发 payload；读多个独立 volatile 属性仍可能混批，需要一致性就使用 snapshot()。

## 4. 十项风险与七项建议处置

| 原风险 / 建议 | 当前处置 |
|---|---|
| 风险1 / 4.1-3 | ✅显式 Adaptive 钳制/radius 已实现并有两重载回归；不声称自动 ROM 发现。 |
| 风险2 / 4.1-1 | ✅完整 API 包含 onePxEnable，七 setter 私有，兼容六参不误覆盖 1px 配置。 |
| 风险3/4 / 4.1-4 | ✅typed 列表+不可修改防御副本此前已实现；本轮补转换失败的整批原子性及不在锁内执行调用方集合代码。 |
| 风险5 / 4.1-1 | ✅写路径已收敛，禁止直接 property setter 绕过 Adaptive/通知；内部只有同锁更新。 |
| 风险6 / 4.1-2 | ✅六整数 -1/threshold 1 默认值已有，不替换为 UI 演示用 0/1。 |
| 风险7 / 4.1-5 | ✔️保留数据层，未移植系统 derived gate；按原建议的替代方案补 Demo 明示，不伪造工厂重建。 |
| 风险8 / 4.1-6 | ✅本地 touch gate 已有；✔️OEM 大屏/物理 reverse provider 保留系统边界，不混同二者。 |
| 风险9 / 4.1-7 | ✅本地 listener 生命周期已落实，旧“没有注册所以无释放面”失效；真实 RUS provider 仍不接。 |
| 风险10 | ✅snapshot() 已提供同批读取，Demo 已使用；本轮补有异常/集合重入时的准备与提交边界。 |

## 5. 六项简化与四步落地顺序

1. 不直接接 RusBaseConfigManager / LauncherCommonConfigManager，没有 ROM 身份/服务上下文不创建空 facade。
2. 不复制整套 LauncherAnimConfig/AppFeatureUtils；显式 Adaptive 和独立工厂开关的边界如上。
3. 保留 scalar volatile + 同锁写；同批读取用 snapshot，不再以“展示不需要一致性”忽略已有能力。
4. 不照抄 OEM 列表 clear/add；发布副本且旧读者不受随后更新影响，本轮还确保复制失败不残留半批。
5. 保留 typed 参数，不提供 RUS 字符串 parser、坏 item 跳过或通用数值校验的假实现；这些输入策略需真实数据格式后再定义。
6. Default 的 false 只描述缺宿主状态的 no-op 实现；实际 Controller 的 touch 查询与系统 reverse-open 契约区分说明。

原 4.3 顺序：① API/1px 已完成；②默认值/private setter/Adaptive 已完成；③不可修改快照和并发完整读取已有，新增三条异常/重入回归；④系统 derived gate 明确保留，本地订阅释放/实际 touch gate 已完成。不把“全部建议验收”解释为全量 OEM ROM 功能移植。

## 6. 验证记录

- FeatureSnapshotTest **3→6**，新增三条旧代码全失败（`.gradle/review-ordered-32-red.log`）；修复后验证列表准备不产生半批配置。保留旧不可修改/防御副本/兼容重载及七条通知回归。
- 定向 manager + TouchGate **23 tests 通过**（`.gradle/review-ordered-32-green.log`）；完整 **Debug/Release 各 324 tests，43 类，0 failures/errors/skipped**；Demo Debug 成功，82/82 tasks executed，3m 10s（`.gradle/review-ordered-32-final.log`）。120 份源码/测试/两模块配置 SHA-256 前后一致。
- README 测试表、Markdown 相对链接、`git diff --check` 与 AGENTS.md 未改检查通过。无设备/Perfetto 验证，Lint 未完成；既有构建警告保留，无提交/推送。
