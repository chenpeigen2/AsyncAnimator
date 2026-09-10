# 对外 API 标记与编译期文档

## 范围与完整性

`com.asyncanimator.api.PublicApi` 标记本库可公开访问的源码 API，编译时由 KSP 生成 Markdown。此前仅标记 `AsyncValueAnimator` 的 10 项是不完整的示例范围，现已完成整个 `lib/src/main/java` 的公开声明扫描。

当前收录 **30 个源码文件、389 个声明**：47 个类型（含伴生对象）、18 个显式公开构造器、154 个函数、145 个属性和 25 个枚举项。它们不是 389 个互不相关的功能，也不是 JVM 方法数量；重载、属性与类型分别计数。

| 包 | 声明数 | 覆盖内容 |
|---|---:|---|
| `com.asyncanimator.anim` | 162 | 动画包装、监听、聚合器、矩形驱动接口、配置及帧快照 |
| `com.asyncanimator.control` | 124 | 控制器及默认实现、场景、状态枚举、远程工厂接口、超时监听 |
| `com.asyncanimator.manager` | 36 | 本地配置、订阅、快照与管理器公开开关 |
| `com.asyncanimator.thread` | 23 | 执行器、共享线程入口及公开类型 |
| `com.asyncanimator.seq` | 21 | 序列助手、默认实现与时间戳 |
| `com.asyncanimator.core` | 9 | `LogUtils` 与 `Trace` 类型；不包含 internal Trace 操作 |
| `com.asyncanimator.playback` | 8 | nullable 监听接口及适配器；不包含 internal 播放内核 |
| `com.android.launcher3` | 5 | 控制器签名引用的本地目标描述符；不是平台远程动画实现 |
| `com.asyncanimator.api` | 1 | `PublicApi` 注解自身 |

统计包含**源码显式声明**的公开成员，即使它们是覆盖父类的方法；不展开未重写的继承成员、隐式默认构造器、数据类 `copy` / `componentN` 或 `@JvmOverloads` 额外生成的 JVM 重载。生成成员由所属类型的语言契约与 KDoc 说明，不伪造独立源码标记。

`internal`、`private`、`protected`、局部声明和不可公开访问的外层类型下的声明不收录。`AnimationHandler`、`TickScheduler`、`PendingAnimation`、`AnimatorPlaybackController` 等仍为内部实现；本次没有改变可见性或扩大运行时访问权限。

## 标记方式

```kotlin
import com.asyncanimator.api.PublicApi

/** 请求参数快照；构造不启动动画，属性可跨线程读取。 */
@PublicApi
data class AnimationRequest @PublicApi constructor(
    /** 请求的诊断名称；调用方负责保持业务含义一致。 */
    @property:PublicApi
    val name: String
) {
    /** 返回该请求的诊断名称，不修改状态。 */
    @PublicApi
    fun describe(): String = name
}
```

- 类型、函数重载、显式公开构造器、属性、类型别名、枚举项分别标记；类型上的标记不会自动替所有成员补标。
- 构造参数中的公开属性推荐使用 `@property:PublicApi`，枚举项直接使用 `@PublicApi`；不使用 `@get:` / `@set:` 标记替代属性契约。
- 每项提供中文 KDoc；构造器可复用所属类型的构造契约，其他成员有各自的中文说明。
- 注解采用 `BINARY` 保留策略，不是运行时反射注册表，不提供 R8 保留规则或二进制兼容性保证。

## 防漏机制

库的 KSP 配置启用 `publicApi.requireComplete=true`。处理器独立遍历**全部手写源码**，而不只查询已有注解：新增公开类型、函数、属性、构造器或枚举项漏标，会产生 `Public API is missing @PublicApi` 编译错误。公开属性不会因为主构造器为 `internal` 而被漏掉。

已有标记被删除但声明仍公开，也会失败。只有删除声明本身或按实际设计调整可见性后，才会从文档移除；不能为绕过文档校验随意把接口改为 `internal`。生成的 `BuildConfig`、外部依赖与合成成员不在该校验范围内。

误标非公开声明、缺少中文 KDoc 或最终无法解析类型时同样失败。**仅使用成功构建的文档**，失败构建可能留下上一次成功产物。

处理器未开启完整性选项时仍支持显式选择模式和零项输出，但本库没有使用该模式，不能据此声称漏标是正常行为。

## 编译与输出

```powershell
# 单独生成 Debug 与 Release 文档
.\gradlew.bat :lib:generatePublicApiDocs

# 正常 Kotlin 编译也自动生成对应变体文档
.\gradlew.bat :lib:compileDebugKotlin
.\gradlew.bat :lib:compileReleaseKotlin

# 生成器测试 + 库回归及实际文档产物测试
.\gradlew.bat :lib:test
```

输出文件：

- `lib/build/docs/public-api/debug/public-api.md`
- `lib/build/docs/public-api/release/public-api.md`

文档首先汇总各源码目录的声明数，再列出限定名、签名、中文 KDoc 和源码相对路径/行号。重载、泛型、可空性、扩展接收者、变长参数及非公开 setter 均保留；按名称和签名稳定排序，不写入机器绝对路径或时间戳。

KSP 不提供参数默认表达式，`= …` 仅表示存在默认值；枚举项记录名称和契约，不把其构造实参伪造成可调用签名。KDoc 正文和标签保留，不将其中的链接转换为导航网页。输出是 Markdown，不是 Dokka HTML 站点。

KSP 原始文件位于 `lib/build/generated/ksp/<variant>/resources/public-api.md`，通过 Sync 任务复制到文档目录。生成文档不提交 Git，`clean` 后重新生成；库 Java 资源处理阶段排除 Markdown，使用方不需要额外配置打包排除项。

## 维护与验证

`api-doc-processor/` 是独立的构建期 Java/KSP 模块，不作为运行时依赖。维持项目 Kotlin `2.0.21` 与 KSP `2.0.21-1.0.28`，没有升级 AGP 或 SDK。首次构建需准备依赖缓存，再使用 `--offline`。

新增 API 时更新源码、中文 KDoc 及对应测试。完整性检查负责发现未标记声明；`PublicApiDocumentationTest` 另外检查所有模块和公开顶层类型、代表性成员、构造参数属性、枚举、重载及内部实现排除，不能再仅断言“固定 10 项”。

本轮完整性扩充（2026-09-10）已完成生成器 33 项、库 Debug/Release 各 501 项、Demo Debug/Release 各 6 项测试，均无失败、错误或跳过。实际编译验证了新文件/新成员/公开构造参数属性/枚举项漏标，以及已有标记删除的失败路径，补标并恢复后文档为 389 项。42 个原库实现文件经过 Kotlin PSI 语法树词法对比，排除注解、注释、导入排序和可选构造器关键字后实现一致。完整构建 127 个任务执行成功；文档与打包隔离的最终核对见 README。源码注解和文档测试不替代真机帧时序、Perfetto、系统转场或完整 Lint 验证。

Release AAR 打包此前因离线缺少 `intellij-core` / `kotlin-compiler` 31.9.0、联网下载反复重试而未完成；没有关闭注解提取或 Lint 来绕过。本轮不将该历史阻塞状态改写为已通过。
