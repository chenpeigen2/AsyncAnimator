# 区域 32 对比 Review：Demo 入口、导出与日志生命周期

> ✅第 37/39 份完成（系统入口明确保留）。替代旧版/v2 对 11 页面、全局 stderr 和概念演示的过期断言。
> OPPO 参考树只读；不把独立 Demo 当完整 Launcher。

## 1. 当前映射

| 本地文件 | 实际范围 / 原厂对照 |
|---|---|
| demo/src/main/AndroidManifest.xml | 入口 + 12 个 Demo，共 13 activities；应用包 com.asyncanimator.demo |
| DemoBaseActivity / TraceLogRedirector | 本地 UI 日志消费，原厂平台 trace/JankTracker 无等价页面包装 |
| Demo6StateMachineActivity | 真实 Controller 通知驱动六个代表图节点，完整十二态看文本 |
| Demo9AllAppsTransitionActivity | 公开 MultiAnimatorSet 四轨聚合 + 本地 Controller；Canvas 非系统 Surface |
| lib/.../com/android/launcher3/LauncherAnimationRunner.kt | 类型容器；对照 OPPO 同路径 LauncherAnimationRunner.java 的完整 runner |
| lib/.../control/RemoteAnimationFactory.kt | 两方法本地宿主接口，不是 OPPO runner 内嵌 Factory 的 ABI 替代 |

## 2. 本轮实施

- Demo1–11 补显式 exported=false，Demo12 原已 false；入口 true 保留。不从“无 intent-filter 时省略声明”杜撰当前编译错误，也不预言 API38 默认行为。
- 抽取纯 JVM 可测的共享 TraceLogRedirector。旧实现已能在普通销毁时条件恢复，但 A/B 页面交错释放仍会留下失效包装；不是所有旋转必然 N 倍变慢。
- 一个全局包装、独立订阅及身份释放；最后订阅结束才恢复原 stderr，其他 owner 替换过则不覆盖。注销清 callback，异常隔离，不关闭原流；DemoBase 用弱 Activity + cleanedUp 守卫。
- 新增 demo 模块 JUnit 依赖和六项纯 JVM 测试。用原有每页面包装策略的提取复现件跑红，三项失败；不是声称原 Activity 已执行仪器测试。
- USAGE 增入口/实际 Demo6/9 链路、日志释放与系统集成边界说明。

## 3. 原建议逐项验收

| 原建议 | 处置 |
|---|---|
| 4.1-1 AnimationResult 壳 | ✔️无 Binder finish/ready owner，不造只有名义完成的桩；现有本地完成由 Multi/Controller 真实协议承担。 |
| 4.1-2 五个 Factory default | ✔️无 merge/leash/preload 消费者，不扩不兼容接口假装 OEM ABI；宿主拥有创建、播放和完成通知。 |
| 4.1-3 Demo9 真 RemoteAnimation | ✔️保留系统权限/窗口协议边界；✅Demo9 已非“仅舞台直接播放”，实际四轨接线明确，不能因此声称系统 E2E。 |
| 4.1-4 stderr 叠加 | ✅本轮共享 router + 生命周期接线 + 六项回归；处理交错释放而非只加一个包装标志。 |
| 4.1-5 Demo6 双向同步 | ✅真实订阅及公开事件早已接；✔️不开放任意状态 setter，图节点归并不等于另一套状态机。destroy 清监听/定时器。 |
| 4.1-6 exported=false | ✅本轮 11 声明显式化，全部 12 内页 false。原文所谓七建议实际只有这六个编号，不虚增第七项。 |
| 4.3-1/2/3/5 文档 | ✅入口契约及 Demo9 警示已补；类型壳/宿主责任已有，不伪造 README 不存在的集成章节，纠正 Factory 包路径。 |

## 4. 十项风险与七项保留

- 风险1：入口被启动/类名可知不是已证实安全漏洞；3/8：没有 deep-link 需求，显式 false 加固，不自动推导未来导出或编译规则。
- 风险2：系统 E2E 未实现但有真实本地多轨；4：viewBinding 属 demo，缺生成类是编译问题，不是 lib 导致运行 NPE；9：缺 label 资源在资源链接阶段报错，不声称静默降级。
- 风险5：共享日志修复；6：Class 引用保留，删除无测量的 110KB RSS 估算；7：Demo6 已真实通知驱动。
- 风险10：Demo9 旧 onBackPressed 仍保留，不能凭编译通过声称新系统返回手势无影响；设备返回/旋转/后台回归仍需实际验证。
- 保留项1/2/4/5/6：不抄系统 runner、ActivityInit、虚拟键 provider、窗口输入权限。保留项3 纠正：MultiAnimatorSet 本地四轨已实现，未实现的是系统 AppLaunch 装配，不再一起标缺失。
- 保留项7：入口保持 exported=true 供外部 launcher 启动；不把导出状态与 APK 能否安装混为一谈。

## 5. 验证

- TraceLogRedirectorTest 六项：共享一层/只写一次、交错释放与新一代、其他 owner 替换、订阅抛错、重入注销、非 Trace 透传和重复 close。日志 `.gradle/review-ordered-37-red.log` / `-green.log`。
- 定向新策略 6 tests 通过；库 Debug/Release 各 **333 tests/44 类**，Demo 各 **6 tests/1 类**，均零失败/错误/跳过。Demo APK 构建 **118/118 tasks executed，3m 27s**（`.gradle/review-ordered-37-final.log`）。130 份源码/测试/配置/脚本 hash 不变。
- 两变体合并 manifest 各 13 activities，仅入口导出、12 内页显式 false；README/XML/链接/whitespace 检查通过。无设备/Perfetto 验证，Lint 依赖仍缺；AGENTS/OPPO 未改，无提交/推送。
