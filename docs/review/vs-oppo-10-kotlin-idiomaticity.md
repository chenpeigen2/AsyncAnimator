# 区域 10 对比 Review：API 风格与 Kotlin 化程度

## 2026-09-09 顺序验收：✅完成（第 15 份，语言与互操作边界）

核对当前 Kotlin 源码、真实 Java 调用编译和只读 OPPO 反编译树。原文把 typealias、浅拷贝、JVM 初始化和只读 List 多处误判为行为 bug；本篇以现有实现替换过期结论，不把语法风格当成性能或线程安全证据。总进度见[顺序执行清单](2026-09-09-ordered-review-progress.md)。

## ① 本轮落点与原厂证据

| 主题 | 当前代码 / 原厂对照 |
|---|---|
| Java/Kotlin Boolean 工厂 | `anim/AsyncValueAnimator.kt`；OPPO `com/android/quickstep/util/animation/AsyncValueAnimator.java:43-49,99-100`，声明返回 ValueAnimator，true 才返回异步子类 |
| 兼容 listener 入口 | 本库 addAnimatorListener/removeAnimatorListener 已有；OPPO 同文件 `:108-110,143-145`；不要求调用方全局替换为容器入口 |
| 主/动画执行器初始化 | 本库 `thread/Executors.kt`；OPPO 分属 `com/oplus/basecommon/thread/Executors.java:54` 和 `OplusExecutors.java:95`，不能把两个类的初始化当成一个 object |
| 参数复制 | 本库 `anim/OplusValueAnimator.kt` AnimParam；OPPO `com/oplus/quickstep/utils/OplusValueAnimator.java:353-359` 新建参数对象、复制标量、共享 interpolator/applicator 引用 |
| 监听者身份 | `control/OnAnimStateChangeListener.kt` 为 fun interface；基类使用列表 remove 和快照派发，不是自动变成严格引用相等比较 |

### 实际发现

Executors 原本将 MAIN_EXECUTOR 与 ANIM_CONTROL_EXECUTOR 两个 eager val 放在同一个 object；访问 MAIN 也会求值后台执行器并拉起线程，与“首次访问动画执行器才启动”的注释不符。本轮改为显式 synchronized lazy，保留公开 getter 和进程级单例，增加冷初始化 / 8 路并发首次访问回归。不把这一优化归因于 Kotlin object 自动逐字段懒加载。

增加真实 `.java` 测试：静态 Boolean 工厂、属性 getter/setter、线程访问器、Java SAM 保存/移除、Adapter 增删和 start/end。另补参数复制测试及 KDoc，验证新容器的标量独立而回调引用共享。没有给无法通用复制的闭包实现伪“深拷贝”。

## ② 原 §③ 十五项风险逐条更正

| # | 原主题 | 当前结论 |
|---|---|---|
| 1 | listener 空值 / SAM | ✅有意传真实非空 Animator；与 OEM null 事件参数的差别不是 SAM 转换造成 |
| 2 | fun interface / typealias 身份 | ✅函数值也有对象身份，typealias 不创建新运行时类型。保存并传回同一 listener；重新写相似 lambda 不保证是同一对象，fun interface 不会自动解决这一点 |
| 3 | var 绕过 setter | ✅Kotlin var 可有自定义 setter，不等于无校验；AsyncValueAnimator 的 executor 已固定 owner，Controller 状态/回调写入受主线程检查；兼容 listener 方法已经存在 |
| 4 | companion 静态工厂 | ✅Boolean 重载有 @JvmStatic，返回声明是 ValueAnimator，不是直接可设 executor 的异步类型；Java 编译回归验证 |
| 5 | object 与静态初始化 | ✅JVM 类加载不等于类初始化；object 首次使用初始化自身，而其 eager 属性一起求值。本轮对动画执行器单独 lazy，Manager 不伪装 OEM RUS 注册 |
| 6 | private set 的线程可见性 | ✅限制正常写入口，不提供同步或线程切换；保留既有实际主线程守卫 |
| 7 | apply builder | ✅apply 返回接收者，不改变被调用方法的返回类型、调用顺序或时序契约 |
| 8 | inline/crossinline 捕获 | ✅crossinline 禁止非局部 return，不是变量快照或线程安全保证；是否分配/内联取决于编译与调用位置，无未经测量的性能结论 |
| 9 | Function / Runnable / Consumer | ✅函数类型 JVM 签名与 Java SAM 是不同公开 ABI；不保证任意旧 Java 调用无修改可编译，本轮只验证明确支持的公开入口 |
| 10 | data class copy | ✅产生新的参数容器，标量 currentFraction 修改不会同步写旧容器；interpolator/applicator 引用共享，不是“同一 param 实例” |
| 11 | 匿名对象与 lambda | ✅分配和缓存策略不是跨语言的固定保证；不据此声称 Kotlin 每次新增类或一定更快 |
| 12 | internal | ✅模块级 Kotlin 可见性，不是 Java package-private，也不是运行时安全边界；内部实现不因此变成受支持 Java API |
| 13 | Java 使用 fun interface | ✅单抽象方法可由 Java lambda 实现，新增 Java 编译/运行回归；并非天然“不友好” |
| 14 | typealias 的字节码类型 | ✅别名在使用处展开，不生成同名 JVM 类；FunctionN 来自被别名的函数类型，而非一个 typealias 运行时实体 |
| 15 | 缺少 suspend/Flow | ✅可选新能力，不是 OEM 回移缺陷；当前无协程调用方和取消/实际结束的协程契约，不添加未定义语义的 await 包装 |

## ③ 原 §④A 十项回移建议

| # | 建议 | 验收结论 |
|---|---|---|
| 1 | OnAnimStateChangeListener 改 fun interface | ✅已经实现；保留明确跨语言接口，修正“lambda 无身份”的错误理由 |
| 2 | 列表移除用引用比较 | ✅保持标准 List.remove 的 equals 语义，与原厂列表操作兼容；Kotlin == 不是 ===。不引入破坏自定义 equals 的身份过滤；示例保存原 listener |
| 3 | @JvmOverloads / 兼容 listener 方法 | ✅兼容增删入口已有；没有默认参数需要额外 Java 重载，不机械添加 @JvmOverloads |
| 4 | Boolean ofFloat | ✅已有 @JvmStatic，新增真实 Java 调用测试；Kotlin 的纯 Float 快捷工厂与 Java Boolean 入口明确区分 |
| 5 | executor 访问器 | ✅getHandler/getLooper/getThread/getTargetThread/setThreadPriority 已有；主线程 Handler 无可设置的 HandlerThread priority，仍按既有检查失败 |
| 6 | AnimParam 深复制 lambda | ✅不采纳通用深复制；显式说明新容器/共享引用，新增回归证明标量互不污染 |
| 7 | PendingAnimation public | ✅不采纳；保持 internal，Demo9 实际使用公开 MultiAnimatorSet，不再以“仅概念日志”描述当前 Demo9 |
| 8 | AnimationHandler public | ✅不采纳；当前帧实验不需要公开内部调度器；测试在模块内部进行，USAGE 列明内部边界 |
| 9 | awaitCompletion / waitForState | ✅决策完成：本轮不引入协程依赖及新 API。以后需要先明确线程、取消、立即完成/已达状态、监听释放和逻辑/实际结束语义 |
| 10 | executeBlockWait 警告 | ✅Executors 注释已有，保留“不做阻塞等待”的解释，不复制 OEM 5 秒等待路径 |

## ④ 原 §④B 十四项简化决策

1. 不手写 Metadata/SourceDebugExtension/JADX 提示：编译元数据和反编译产物不是业务实现。
2. 不手写 Intrinsics：遵守实际 Kotlin nullability；不能说“所有 nullable 表达式后自动检查”。
3. 不手写 lambda$ 静态方法：编译器决定 lambda 降低形式，不要求全部 inline。
4. 不手写 WhenMappings：enum/when 降低策略由编译器决定。
5. 不继承 AbstractExecutorService：LooperExecutor 是本库的显式派发门面。
6. **更正：访问器并未移除**，保持已有 getter/priority API。
7. 不恢复 m 前缀：使用项目 lowerCamelCase。
8. **更正：并非只用 Trace 取代 LogUtils**，当前 core/LogUtils 已有分类/线程/等级输出。
9. executor/isEnd 命名保留；非空类型不是并发或生命周期保证。
10. 不继承 Java 抽象服务，但 shutdown/shutdownNow/awaitTermination/isShutdown/isTerminated 契约方法已有，不再写“未实现”。
11. Handler=null 只用于 JVM stub 回退；真实 Android 使用实际 Looper，测试不冒充设备帧验证。
12. internal 边界保留且列入使用文档。
13. object 保留；此次只对 ANIM_CONTROL_EXECUTOR 作显式延迟初始化，不笼统改所有全局状态。
14. 当前状态 listener 为 fun interface；快照防本次遍历结构修改，线程限制另由主线程检查保证。

## ⑤ 原 §④C 十项 Kotlin 语法/扩展说明

1. apply/also 保留，二者分别返回接收者；不会改变底层方法声明。
2. data class 保留，copy 是新实例且对引用字段浅拷贝。
3. companion 工厂保留，只有明确 @JvmStatic 入口承诺 Java 静态调用。
4. inline/crossinline 仅描述具体使用处；当前 AsyncValueAnimator.marshal **不是 inline**，不能笼统承诺栈里看不到 marshal。
5. SyncedVar 保留，但写锁/volatile 不等于跨字段原子快照。
6. synchronized lazy 保留并用于动画执行器；首次获取才求值，8 路并发仍返回同一实例。
7. List 是只读接口，不天然深度不可变；FeatureHelper 已实际防御复制并包 unmodifiableList，保障来自实现而非类型名。
8. init/主构造器保留，注意初始化顺序，不推导线程切换。
9. DemoInfo 为 data class 数据载体，**data class 是 final，不能直接被子类化**；删除原文错误示例描述。
10. private set 保留，表示外部正常 Kotlin 调用只读，不是反射隔离或同步机制。

这些语法多数是写法而非新增 API。公开工厂、最终 dispose 和 Driver 等本库扩展与 OEM 能力边界见 [USAGE](../USAGE.md)，不得声称 OEM 二进制兼容。

## 验证与限制

- 新增 JavaApiInteropTest 3、ExecutorInitializationTest 1、OplusValueAnimatorTest 1（共 5 条）；8 个并发调用包含在单一初始化测试内，不重复计数。
- 冷初始化测试在旧 eager 代码上失败：`main lookup must leave the animation thread uninitialized`（`.gradle/review-ordered-15-init-red.log`）；改为独立 lazy 后相关 15 tests 全通过（`-green.log`）。测试读取原有线程 lazy 的初始化状态，而非仅检查新增 executor 字段是否存在。
- **Debug/Release 各 204 tests，31 类，0 failures/errors/skipped；Demo Debug 成功，82/82 tasks executed，2m 14s**（`.gradle/review-ordered-15-final.log`）。新增 Java 测试使两个变体的 Java 编译 task 从 NO-SOURCE 变成实际执行，故任务数由 80 增至 82。
- 源码、测试和两个模块构建配置 SHA-256 验证前后一致（`.gradle/review-ordered-15-before-validation.json`）；`git diff --check` 与修改文档相对链接检查通过。
- 不测量 Kotlin/Java 性能；无设备/Perfetto 回归，Lint 未完成。Java 测试编译有 API deprecation 提示，既有 SDK/Gradle/私有注解警告仍在；不将编译告警描述为已清零。无提交/推送。
