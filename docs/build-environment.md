# 构建环境与集成边界

## 当前仓库配置

| 配置 | 当前值 / 文件 |
|---|---|
| Gradle wrapper | 9.4.1；`gradle/wrapper/gradle-wrapper.properties` 含 SHA-256 |
| AGP / Kotlin | 8.9.0 / 2.0.21；`gradle/libs.versions.toml` |
| Java / Kotlin target | 21；两个模块统一，运行 Gradle 使用完整 JDK 21 |
| SDK / Build Tools | compileSdk 37、Build Tools 37.0.0；Demo targetSdk 37 |
| 最低设备版本 | 两模块 minSdk 36；不是 Android 15/API 35 可安装承诺 |
| 测试运行时 | Robolectric 4.16 + android-all 16-robolectric-13921718，测试选择 API 36 |

这些是仓库配置和本机验证记录，不代表该 Gradle/AGP/SDK 组合已获官方兼容认证。既有 unsupported SDK 抑制、SDK XML、私有注解、Gradle deprecation 提示仍存在；不要把 warning 抑制当升级验证。

## 只读预检与构建

设置 JAVA_HOME 指向含 java/javac/jlink 的完整 JDK 21。在不提交的 local.properties 填 sdk.dir；不要提交用户绝对路径或 SDK 文件。

```powershell
.\scripts\check-build-environment.ps1
.\gradlew.bat --version
.\gradlew.bat :lib:testDebugUnitTest :lib:testReleaseUnitTest `
  :demo:testDebugUnitTest :demo:testReleaseUnitTest :demo:assembleDebug `
  --offline --no-daemon --no-configuration-cache --max-workers=1 "-Pkotlin.incremental=false"
.\gradlew.bat :lib:lint :demo:lint --offline --no-daemon
```

预检只读 JDK/SDK 文件，不自动下载或改环境；也不证明 SDK 来源可信或干净机器复现。首次机器未缓存依赖时，offline 会失败；在网络/仓库策略允许时去掉 offline 下载依赖后再验证，不放宽 lint 错误策略。

## 本机 SDK 的已知兼容目录

本次实际读取到 platforms/android-37 与 android-37.0 两目录。其 source.properties 分别声明 ApiLevel=37 与 37.0，package.xml 也分别是 platforms;android-37 与 platforms;android-37.0。本机构建选择前者；后者是此前 SDK 安装布局。

**未验证全新 SDK 安装可直接复现。** 不把旧的本机目录复制/改 metadata 操作写成推荐安装方案，不删除或改写用户 SDK。本仓库仍保留既定 compile/min SDK；若要消除兼容目录依赖，应单独协调 AGP/Gradle/SDK 升级并在干净环境验证，不能靠改两行 minSdk/compileSdk 宣称完成。

org.gradle.java.installations.auto-detect=false 关闭自动发现，不是强制 JVM 版本。以 gradlew --version 的实际 JVM 为准；不要把 VS Code 自带运行时当完整开发 JDK。

## OEM fork、产物与发布

- 当前依赖上游 AndroidX DynamicAnimation 1.1.0；它不等价 OPPO 同包 vendor fork。集成方应先用 dependencies/dependencyInsight 查重复类的来源，选择一个 API/行为兼容的实现。没有验证过的 OEM 集成构建，不提供通用 exclude 配方。
- ProGuard/R8 keep 仅影响压缩保留，不能合并两个同名类；不添加 COUI keep 或 META-INF 通配排除去假装解决 DEX duplicate class。
- lib 是 Android Library（AAR），不是纯 JVM 库；无 native 源码，不配置 NDK/ABI/flavor/OPPO 私有 IPC。AAR 本身不需要 APK signingConfig。
- 两模块 release 均关闭 minify；Demo 无自定义 release 签名。Debug 构建不等于已签可发布 release，也未验证 R8 后 API 保留完整性。
- Kotlin 元数据版本差异本身不是运行时 bug 证据；不承诺新 compiler 输出对任何旧 compiler 可读，消费方需要实际编译验证。
