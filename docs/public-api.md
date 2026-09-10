# 对外 API 标记与编译期文档

## 标记规则

`com.asyncanimator.api.PublicApi` 是显式对外 API 标记。仅带该注解的声明进入生成文档，**类型上的注解不会自动开放全部成员**。声明仍须具有 Kotlin `public` 可见性；注解不改变访问控制、不替代二进制兼容性检查，也不自动添加 R8 保留规则。

```kotlin
import com.asyncanimator.api.PublicApi

/** 应用显式提交的动画请求；不自行切换线程。 */
@PublicApi
class AnimationRequest {
    /** 返回请求的描述，调用方可在任意线程读取返回字符串。 */
    @PublicApi
    fun describe(): String = "animation"
}
```

支持类型（类、接口、对象）、函数、构造器、属性和类型别名。函数重载须分别标记；不包含继承方法、数据类生成方法或 `@JvmOverloads` 生成的额外 JVM 重载。属性使用 `@PublicApi`，而不是 `@get:` / `@set:`；文档记录属性类型及非公开 setter。

本次先标记 `AsyncValueAnimator` 的类型、执行器属性、生命周期与监听入口、两个浮点工厂，共10个声明。其他现有 `public` 声明没有被批量升级为已标记 API。

## 编译与输出

```powershell
# 单独生成两个变体的文档（运行 KSP，不要求打包 APK）
.\gradlew.bat :lib:generatePublicApiDocs

# 普通 Kotlin 编译也会自动生成对应变体文档
.\gradlew.bat :lib:compileDebugKotlin
.\gradlew.bat :lib:compileReleaseKotlin

# 生成器测试 + lib 回归/文档产物测试；lib:test 已依赖生成器测试
.\gradlew.bat :lib:test
```

可阅读的输出：

- `lib/build/docs/public-api/debug/public-api.md`
- `lib/build/docs/public-api/release/public-api.md`

KSP原始资源位于`lib/build/generated/ksp/<variant>/resources/public-api.md`。这些文件是构建产物，不提交Git；`clean`后重新生成。文档在库 Java 资源处理阶段排除，并配置最终打包排除项；使用方无须额外设置。处理器仅在构建期依赖，不进入库运行时。**只使用成功构建后的文档**，失败构建可能保留上一次成功产物。

文档包含限定名、签名、中文KDoc和源码相对路径/行号；按限定名和签名稳定排序，保留重载、可空性、泛型、扩展接收者、变长参数和默认值存在性。KSP不提供默认表达式，因此`= …`仅表示“有默认值”，不能作为可执行的默认代码。KDoc正文（含示例和标签）原样保留，不解析其链接为网页跳转。时间戳与机器绝对路径不写入文档。

## 编译期校验

- 标记的声明或其外层类型为`private` / `internal` / `protected`时，生成任务失败。
- 局部声明、无法支持的声明类型或没有源码位置的声明不能作为文档API。
- 每条API必须有中文KDoc；构造器可复用所属类型的构造契约。其他成员不自动继承类注释。
- 无法解析的类型延后至下一轮；最终仍未解析则失败，不生成不完整签名。
- 删除标记或修改注释会重新生成聚合文档；删除全部标记时输出“API声明数：0”，而不是保留旧目录。

## 构建实现与维护

`api-doc-processor/`为独立Java/JVM KSP处理器模块：Java实现避免为构建工具另行引入Kotlin编译插件，业务库仍使用Kotlin。`META-INF/services`注册处理器，KSP负责解析真正的注解符号（包括别名导入），不使用正则表达式猜测源码声明。

项目固定Kotlin `2.0.21`，配套KSP `2.0.21-1.0.28`，没有为此功能升级AGP、SDK或库运行时依赖。版本在`gradle/libs.versions.toml`集中管理；升级Kotlin时需同步确认KSP匹配并重跑生成/回归测试。首次构建需要解析新增KSP依赖，缓存准备好后可加`--offline`。

新增API时同时更新实现、中文KDoc和对应测试；在`PublicApiDocumentationTest`中更新显式API范围断言。生成器测试覆盖签名、可见性、重载、缺失注释、多轮处理和稳定输出；这不替代设备帧时序或系统转场验证。

## 本次验证（2026-09-10）

- 生成器 3 个测试类、23 项测试通过；库 Debug/Release 各 498 项、Demo Debug/Release 各 6 项通过，均无失败、错误或跳过。
- 实际 KSP 编译验证了注解别名导入、泛型/构造器/属性/类型别名/扩展重载；修改 KDoc 后文档刷新，删除全部标记后输出 0 项，恢复后输出 10 项。
- 非公开外层类型中的标记和缺少中文 KDoc 均触发预期编译失败；Debug/Release 生成文档内容一致。
- 完整重跑 127 个 Gradle 任务并构建 Demo Debug APK；解包确认不含文档或生成器，库编译 JAR 保留 `PublicApi` 注解。新增库资源排除回归测试，避免向使用方泄漏 Markdown。
- 默认配置缓存模式下文档命令连续执行成功，第二次复用配置缓存。
- 构建与测试期间核对源码及构建配置哈希，避免把验证中的源码变更当作已验证结果。未执行真机、Perfetto 或本功能的完整 Lint 验证。

额外的 Release AAR 打包验证未完成：离线环境缺少 `intellij-core` / `kotlin-compiler` 31.9.0，联网补充依赖时下载反复重试，等待超过 12 分钟后主动停止。未关闭注解提取或 Lint 检查来绕过问题；不能据此宣称 AAR 验证通过。
