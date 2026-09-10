# 区域 34 对比 Review：测试覆盖缺口验收

> ✅第 39/39 份完成（全队列验收完成，验证边界保留）。以实际测试源码/执行 XML 为准，不沿用旧版的文件数、零覆盖或优先级估算。
> OPPO 树只读；本库与系统权限/物理引擎的明确差异不会被测试数量掩盖。

## 1. A1–A8 建议逐项验收

| 建议 | 当前测试与边界 |
|---|---|
| A1 状态转移 | ✅AnimationControllerRegressionTest 四个操作各遍历12态，ControllerStateMatrixTest 另有三个完整转移矩阵；7测试/84组合已计入总数。夹具可反射播种输入态，转移调用真实生产 API，不声称每态都有宿主入口。 |
| A2 双集合收尾 | ✅AnimationControllerRegressionTest、ControllerCompletionTest、RecentsFinishGateTest 等覆盖两种结束次序、一次性消费、异常/重入以及旧 owner 不误清新轮。 |
| A3 三层启动决策 | ✅AnimationLaunchDecisionTest、AnimationControllerRegressionTest、LaunchDecisionReentrancyTest 覆盖场景/互斥优先级/精确截止、running provider/搜索平板、替换与注销；不是 Binder 联调。 |
| A4 帧回调 | ✅AnimationHandlerTest 用 ManualScheduler 真正 pulse；返回 true 不替代显式 remove，实际移除/重启均有断言。旧误导测试名已不存在。 |
| A5 timeout/事件释放 | ✅TaskStateChangeTimeOutListenerTest、TaskStateEventTest、TimeoutOwnershipTest 覆盖三事件槽、timer/event 一次性竞争、异常、dispose/重入替换；不假造全局任务总线。 |
| A6 feature + 300/500ms | ✅AnimationSeqFeatureGateTest 8测试覆盖四种 gate 组合、精确截止、关闭后动作替换/不排队、interruption 两写入口及共享序号；AnimSeqTimeStampTest 验证零时刻仍门控。默认能力占位不是 ROM 自动探测，不再标本地 gate 未实现。 |
| A7 四 reset 独立性 | ✅AnimationSeqRegressionTest 独立 reset；AnimSeqTimeStampTest 覆盖四字段零/未记录、clock异常保值及真实并发完成后的发布。 |
| A8 重复并发读写 | ✅本轮新增：两worker、1000个 barrier 限界轮次、4000次单字段读取；写入/reset 与读在每轮内无固定先后，值只能0或未记录哨兵。共享不可变时钟，不交换活跃线程的时钟，finally终止worker/恢复日志，测试后恢复时钟。 |

A8 是对既有同步写/volatile读取契约的压力回归，不是新运行时 bug 修复，也不证明完整 JMM 正确性、所有调度组合或四字段原子 snapshot。原厂 `com/android/systemui/shared/system/AnimSeqTimeStamp.java` 的各方法 synchronized 同样不能使调用方连续四次 getter 自动变成同一事务。

## 2. 原十三风险当前状态

- 3.1：本地 MultiDynamicAnimation、RectSpringDriver 六轴和 MultiAnimatorSet 四轨已有实现与回归；不是仅单 Spring 包装。AndroidX 与 OEM vendor 物理/窗口行为差异明确保留。
- 3.2/3.3：状态矩阵和双集合/双callback收尾已有，不继续写1/9覆盖或无测试NPE风险。
- 3.4：Controller 三槽事件桥已有、Demo6可触发；未接系统task总线不是本地只能等超时。
- 3.5：同步写/reset+volatile单字段读取，不是裸写降级；A8补重复并发回归，不发明跨字段一致性承诺。
- 3.6：帧由手动scheduler实际推进，boolean返回值忽略有专门测试。
- 3.7：两个可注入gate已有；ROM配置读取未移植。Seq 默认 startingSurface=true、interruption调用固定能力占位；Manager本地开关通过工厂owner变更，不等于自动探测ROM。
- 3.8/3.9：三层决策及recentStart/launchTask更新/reset/gap均被触达，不继续标死代码。
- 3.10：无canGoNormalRecent消费方，不添加200ms常量占位。
- 3.11：门控LogUtils及Trace回归已实现，旧“全砍”过期。
- 3.12：不手写反编译Intrinsics壳；nullable/non-null契约各自保留，不能说nullable等于自动非空校验。
- 3.13：600ms触摸门控、替换截止、end/reset/destroy、实际挂起动作与Demo9接线已有，TouchGateTest覆盖；不是MESSAGE_RELEASE_TOUCH能力全缺。

## 3. 六项简化的准确边界

1. MultiDynamicAnimation“未移植”过期：已有本地实现，不承诺私有六轴调参/Surface物理完全一致。
2. Kotlin编译器的非空检查不手抄JADX产物；允许null的API另按源码契约测试。
3. canGoNormalRecent无调用方，保持省略而非未完成待办。
4. LogUtils不再属于省略项；本地诊断不是OEM RUS/平台trace后端。
5. 不增无真实事件源的全局总线或只为名称匹配引入SharedFlow依赖；宿主通过现有事件入口集成。
6. 不伪造多应用系统target merge，当前本地四轨聚合与系统多应用合并不同。

## 4. 测试口径与剩余验证边界

- 新增1个库压力测试，AnimSeqTimeStampTest 6→7；seq定向四类共28用例通过（`.gradle/review-ordered-39-green.log`）。没有虚构red阶段产品bug。
- 库主要为JUnit4/Robolectric API36，Demo日志转发器为纯JVM JUnit4。没有配置覆盖率阈值，也未运行覆盖率工具；测试个数/矩阵组合不等于行/分支覆盖率百分比。
- 无src/androidTest设备仪器测试；真实VSYNC/同步屏障、View跨线程绘制、旋转/返回手势、OEM窗口/权限及Perfetto仍需设备验证。Lint因离线依赖缺失未完成；干净SDK复现及release压缩/签名不在已验证范围。
- 最终库Debug/Release各 **334 tests/44类**，Demo各 **6 tests/1类**，均零失败/错误/跳过；45类340独立用例。Demo Debug APK **118/118 tasks executed，3m10s**（`.gradle/review-ordered-39-final.log`）。
- 130份源码/测试/构建配置/脚本hash不变，四组XML/两变体manifest/README/相对链接/whitespace/AGENTS检查通过；39份队列均已验收，34专项索引不重不漏。未修改OPPO，无提交/推送。
