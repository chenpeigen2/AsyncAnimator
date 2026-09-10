# 区域 33 对比 Review：文档与代码一致性

> ✅第 38/39 份完成（文档专项）。本份不改源码或构建配置，不冒称新修运行时 bug。
> OPPO 只读证据与逐项实现详见已验收专项；本文消除当前使用指南/汇总的过期结论。

## 1. 八项风险与六项建议验收

| 原风险 / 建议 | 当前结果 |
|---|---|
| R1 / W2/W3 转移表“有意保留偏差” | ✅使用指南已有当前修复边界，本轮复查未残留该旧句；不回退 UNKNOWN/MULTI 或 Between 实现。 |
| R2 / W1 AsyncSpringAnim 未列 | ✅已有完整生命周期/线程说明及 Demo11，不重复创建章节。 |
| R3/R5 / W4 Demo 计数 | ✅README、入口、manifest 现为 12 个 Demo；旧建议固定改成 11 已过期。总共 13 activities，不把入口算成演示页面。 |
| R4 / W2 被动超时接口 | ✅USAGE 清单改 class + 主线程超时/Controller 三槽事件；未接系统总线仍明示。 |
| R6 / W2 只保留 MAIN | ✅USAGE 改 MAIN + 惰性 ANIM_CONTROL；未移植的是 OEM 其他池。 |
| R7 / W6 typealias 移除失效 | ✅已有 fun interface 与 Java 回归；本轮删指南残留的“函数对象没有引用相等性”，说明保存注册对象/equals。 |
| R8 / W5 runner 类型壳 | ✅已有类型壳和宿主责任，Demo9 本地四轨与系统入口分别说明；不假造完整 runner。 |

## 2. 六项保留与新增纠错

1. USAGE 不穷举每个 internal 类，但删除“未列出的成员均 internal”伪规则；可见性以源码为准，core/playback 也有公开类型。
2. 保留 LauncherAnimationRunner 类型容器，不因文档专项添加无 owner 的系统入口。
3. AsyncAnimWrapper 线程语义在上层 API 说明；无重复专章需求。
4. Seq 条件更新/equals 配对与 interruption 写门控已有代码/回归，不再说留待未来修复。
5. 时间戳同步写 + volatile 单字段读、null/零时刻边界已有说明；不承诺四字段原子 snapshot。
6. AnimType 当前七值，不沿用“本地三值是设计取舍”。Rect 六轴与 Multi 四轨已有，仍非完整 OEM 物理/窗口引擎。

本轮还同步 AsyncAnimCallbacks 公开 remove/setAnimType/dispose 与事件快照；纠正 Default Controller 所有方法无副作用、Trace 整个类型 internal、listener 族都 internal 等误述。

## 3. 汇总与交叉引用

两份 SUMMARY 改为当前基础 01–12 / 深挖 13–34 索引，全部 34 个真实文件各出现一次；移除无来源的保真度百分比、行数成本、全部 bug 已修和过期缺失表。旧版五份保持 superseded/顶部验收口径，不修改历史证据冒充新成果。

README 库测试表与 XML 一致，Demo 单独列 1 类/6 用例；库333+Demo6=339 个独立用例。七项状态矩阵的 84 组合包含在测试中，不另累加。目录/相对链接检查通过。

## 4. 验证口径

- 本份只改 Markdown，复用刚完成的第37份构建：库 Debug/Release 各333/44类、Demo各6/1类，零失败/错误/跳过；APK 118/118 tasks，3m27s。
- 130 份源码/测试/构建配置/脚本 hash 与第37份一致；README/XML、两变体合并 manifest、所有变更 Markdown 相对链接、git diff --check、AGENTS 未改均再次检查。
- 没有伪造第38份 Gradle执行日志。设备/Perfetto/Lint/干净 SDK 限制仍保留，未提交或推送。
