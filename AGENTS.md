# Repository Guidelines

## Project Structure & Module Organization
- `lib/` is the Kotlin Android animation library. Sources live in `lib/src/main/java/com/asyncanimator/`, grouped into `core`, `thread`, `anim`, `playback`, `seq`, `control`, and `manager`; launcher integration also lives under `com/android/launcher3/`.
- `lib/src/test/java/` contains JVM unit tests mirroring library packages.
- `api-doc-processor/` is the build-only Java/KSP processor; its tests validate public API selection, signatures, and Markdown generation.
- `demo/` is the Android demo app: activities are under `demo/src/main/java/com/asyncanimator/demo/`, with layouts, drawables, and strings in `demo/src/main/res/`.
- `docs/` contains usage, architecture, trace validation, and review records. Prefer `animation-thread-analysis-v4.md` over older architectural conclusions.
- Dependency versions and aliases are centralized in `gradle/libs.versions.toml`.

## Build, Test, and Development Commands
Use a full JDK 21 via `JAVA_HOME`, Android SDK 37, and Build Tools 37.0.0. Configure your SDK path in untracked `local.properties`. Run from the repository root:

```powershell
.\gradlew.bat :lib:test                 # Library JVM tests + API processor tests
.\gradlew.bat :lib:generatePublicApiDocs # Annotated API Markdown for both variants
.\gradlew.bat :demo:assembleDebug       # Build the debug APK
.\gradlew.bat :lib:lint :demo:lint       # Android lint checks
.\gradlew.bat :demo:installDebug        # Install on a connected device
adb shell am start -n com.asyncanimator.demo/.LauncherEntryActivity
```

Devices/emulators must support API 36 or newer. On Unix, use `./gradlew`. The debug APK is produced under `demo/build/outputs/apk/debug/`.

## Coding Style & Naming Conventions
Follow the configured official Kotlin style: four-space indentation, `UpperCamelCase` types, and `lowerCamelCase` functions/properties. Match filenames to their primary types and use `snake_case` Android resource names. Prefer idiomatic Kotlin and existing platform/AndroidX animation APIs rather than duplicating them. Preserve thread ownership, callback cleanup, and safe feature-disabled behavior. No standalone formatter is configured; use IDE Kotlin formatting and Android lint.

## Kotlin 编码规范强制规则
所有新增或修改的 `.kt` 代码（包括 `lib`、`demo` 和测试）必须遵循修改时 Kotlin 官方最新的 **Coding conventions**，不得仅沿用不符合规范的历史写法。
- 以 Kotlin 官方文档的 Coding conventions 为准；规范有更新或规则不明确时，先核对官方文档，再执行修改。
- 使用四个空格缩进，不使用 Tab；按官方要求处理命名、换行、空格、修饰符顺序和声明组织，优先采用清晰、惯用的 Kotlin 写法。
- Kotlin 代码禁止使用非空断言操作符 `!!`；必须使用安全调用、提前返回、`requireNotNull`、`checkNotNull` 或其他明确的空值处理方式，并在必要时保留清晰的失败信息。
- 在项目当前 Kotlin 编译器、语言/API 版本支持范围内落实规范；不得为追求新语法擅自升级依赖、启用实验特性或改变公开接口及运行语义。
- 交付前使用 IDE 的 Kotlin style guide 格式化受影响代码并检查相关警告；避免无关文件的批量格式化。修改 `lib` 时仍须同时遵守测试更新和中文注释规则。

## Testing Guidelines
Use JUnit 4; AssertJ is also available. Name classes `*Test` and methods descriptively, following existing `testBehaviorName` examples. Add regression tests for frame callbacks, state transitions, and sequence handling. No coverage threshold is configured. JVM tests return default Android stub values, so validate real Looper/VSYNC behavior on a device; report the demo exercised and any testing skipped.

## lib 修改强制规则
每次修改 `lib/`，必须在同一批改动中同步完成以下事项，不得只修改库代码：
- 在 `lib/src/test/java/` 新增或更新对应测试，覆盖本次修改涉及的行为、边界条件及异常路径；修复缺陷时添加回归用例，不得以无关测试改动代替。
- 新增或修改公开源码 API 时，显式添加 `@PublicApi`（包括公开构造器、属性及枚举项）和中文 KDoc；运行 `:lib:generatePublicApiDocs`。全库完整性校验禁止漏标，不得为绕过校验随意降低可见性。
- 为新增或修改的方法补充、更新详细中文 KDoc，说明功能、参数、返回值，以及适用的线程、生命周期和异常约束；私有方法、构造器及属性访问器同样适用，注释须与实现一致。
- 运行 `.\gradlew.bat :lib:test` 验证测试，并在交付说明中列出测试改动与验证结果；无法运行时明确说明原因及未验证范围，不得宣称通过。

## Commit & Pull Request Guidelines
History mixes imperative summaries with prefixes such as `lib:`, `docs:`, and `chore:`; use a concise, scoped summary. Keep changes focused. PR descriptions should explain behavior, link relevant issues/review records, list validation commands/results, and include screenshots or traces for visual/threading changes. Update affected documentation. Do not commit SDK paths, build outputs, temporary scripts, or unrelated local changes.
