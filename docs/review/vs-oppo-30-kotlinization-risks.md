# 区域 30 对比 Review：Kotlin 化兼容性风险

> ✅第 35/39 份完成（兼容边界明确）。本文替代旧文错误的“vs-oppo-14”编号及 v2 快照。
> OPPO 只读树：`D:/oppo_a6_launcher/sources`。本文评估本库契约，不承诺 OEM 二进制替换。

## 1. 源码对照与纠错

| 本地 | OPPO 证据 | 当前结论 |
|---|---|---|
| `control/OnAnimStateChangeListener.kt` | `com/oplus/quickstep/utils/DefaultAnimationController.java` 内嵌接口 | 已为 fun interface，Java SAM 有实际编译测试。typealias 不会改变函数对象身份。 |
| `thread/LooperExecutor.kt` | `com/oplus/basecommon/thread/LooperExecutor.java` extends AbstractExecutorService | 本地不是 ExecutorService 子类型；只有五个生命周期兼容入口，不能说“实现了接口”。 |
| `manager/OplusAnimManager.kt` | `com/oplus/quickstep/utils/OplusAnimManager.java` 的 observable delegate | 本地 object 首访创建两 helper；volatile 字段、主线程 setter 和释放已实现。OEM delegate 非 lazy。 |
| `manager/AnimationFeatureHelper.kt` | 同路径 `AnimationFeatureHelper.java` 的 companion/lazy 与同步 setter | 本地共享锁保护整批提交/snapshot，列表防御复制，已有主线程订阅；不是仅七个裸字段。 |
| `anim/AsyncValueAnimator.kt` 等 | 原厂独立 Java/Kotlin ABI | 已有 Boolean 工厂 @JvmStatic、Rect @JvmOverloads、LogUtils 静态入口；“全树零注解”过期。 |

## 2. 十一项建议及对应风险处置

| 原建议 / 风险 | 验收 |
|---|---|
| 1 / 1 fun interface | ✅已有 Java SAM 保存/移除测试；保存同一个注册对象即可，不凭重复 lambda 的正文判相等，不新增不存在的必要注解。 |
| 2 / 2 shutdown | ✅本轮 Java 回归覆盖三种拒绝调用、两种状态 false 及拒绝后仍可派发；✔️不扩成 submit/invokeAll 执行服务。 |
| 3 / 3 ROM 四条件 | ✔️supportInterruption 固定 true 是能力占位；使用本地主线程 interruptionEnabled，不以恒 true 条件拼装假 ROM 查询。 |
| 4 / 4 工厂并发 | ✅已有 volatile、setter 同步及主线程检查、冷首访和生命周期测试；不声称两 getter 是原子 snapshot。 |
| 5 / 6 Supplier+Runnable | ✔️保留 Function0 API；本轮 Java 编译/执行测试及 USAGE 示例说明 Unit.INSTANCE/Boolean 适配，无真实需求不增加可能混淆的重载。 |
| 6 / 8 @JvmStatic | ✅实际工厂已有静态入口；✔️Manager 继续 INSTANCE，默认参数不等于 Java 重载。 |
| 7 / 9 removeListener | ✅AsyncAnimCallbacks 与 animator 对外删除入口已有，Java adapter 注册/移除回归已有。 |
| 8 / 7 Seq Runnable | ✔️保留 Function0；新增 Java 测试确认 Default Seq 当场 action、返回 false，null 安全；Default Controller 则不执行 action。 |
| 9 / 5 独立锁 | ✔️保留共享锁以保证批量提交与 snapshot 一致；旧文“吞吐变 1/7”无测量依据。拆锁反而破坏已提供契约。 |
| 10 / 10 postDelayed | ✔️已有 getHandler 可供拥有者管理延迟任务；无新增调用点，不增重复薄 API。 |
| 11 USAGE 契约 | ✅本轮补 Java Function0 示例、INSTANCE、默认参数、非 ExecutorService、ROM/local 开关和两种 Default 区别。 |

## 3. 六项简化与初始化边界

1. 保留 Feature object，不为模仿 companion/lazy 增加包装。二者构造触发不同，不称任何场景完全等价；无 RUS 构造副作用需搬运。
2. 保留两 owner 的本地工厂，不造 OEM 六 helper 或 observable 壳；此前关闭/重启的释放与重建已验证。
3. 保留 Feature 共享锁。写在同锁内，普通 getter 分别 volatile；一致整批读取应使用 snapshot，而非连续 getter。
4. 不继承 AbstractExecutorService；五个同名入口不是完整接口，shutdown 不是页面清理方式。
5. 函数类型保留，Java 可适配但并非 Runnable ABI 替换。现有 Java 测试不代表整个公共 API 已有 Java ABI 兼容性基线。
6. AnimationHandler/Pending/APC 保持 internal；HandlerTickScheduler 已删除。internal 是 Kotlin 模块约束，不是 Java 安全沙箱。

此外修正 DefaultAnimationController KDoc：“所有方法无副作用”错误，监听注册/移除/显式派发真实有效；delay false 不执行 action。Demo6 通过 onCleanup 中的 controller.destroy() 清除持有的监听，不要求对每个匿名 lambda 再单独 remove；不保留“只 add 不 remove 即泄漏”的旧断言。

## 4. 验证

- `JavaApiInteropTest` 3→5：新增 Function0/Default 行为及 never-quit 全契约测试，验收既有行为，不冒称两个新产品 bug。
- 定向 5 tests 通过；完整 Debug/Release 各 **333 tests / 44 类，0 failures/errors/skipped**，Demo Debug **82/82 tasks executed，3m 11s**（`.gradle/review-ordered-35-final.log`）。121 份源码/测试/模块配置 hash 不变，README/链接/whitespace/AGENTS 检查通过。
- 未做设备/Perfetto 及全面 Java ABI 工具基线，Lint 未完成；OPPO/AGENTS 未改，无提交/推送。
