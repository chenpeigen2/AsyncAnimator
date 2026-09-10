# 区域 31 对比 Review：构建配置与原厂差异

> ✅第 36/39 份完成（环境限制明确保留）。以当前 Gradle 文件及实际产物为准，替代旧版“十项 P0”推断。
> OPPO JADX 参考树只读，不等于拿到了 OEM 原始 Gradle 工程。

## 1. 已核对配置与证据

`lib/build.gradle.kts` / `demo/build.gradle`：compileSdk 37、minSdk 36、Java/Kotlin target 21、Build Tools 37.0.0；Demo targetSdk 37。catalog：AGP 8.9.0、Kotlin 2.0.21、DynamicAnimation 1.1.0；wrapper 9.4.1 含校验和。release 不压缩，Demo 无自定义 release 签名。

OPPO 同包 `androidx/dynamicanimation/animation/` 的 vendor 实现、`com/coui/appcompat/animation/` 及反编译 Metadata/BuildConfig 能证明包/元数据/产物字段差异，不能还原原始 Gradle 依赖图、确认源码 compiler 配置或自动推出运行时崩溃。

本机 SDK 两布局及 metadata 已读回，完整 JDK 工具已只读预检；详见[构建环境说明](../build-environment.md)。这不证明干净机器复现或官方工具组合支持。

## 2. B1–B10 建议逐项处置

| 建议 | 结论 |
|---|---|
| B1 降 compileSdk / 删除 SDK | ✔️不执行。compileSdk 不是安装最低版本；不删除用户 SDK 或擅自变更既定开发基线。新增本机兼容目录说明及只读预检；干净环境/工具链升级单独验证。 |
| B2 降 minSdk 31 | ✔️保留仓库明确的 36。API 35 不可安装是当前支持范围，不冒称可支持 ColorOS 所有版本；降级需目标设备和 API/运行回归，不以两行修改完成。 |
| B3 降 target 17 | ✔️保留 21。class version 65 是 JVM 编译产物，不是 APK 直接交 ART 加载该 class 的证据；不从 class header 直接推 Android VerifyError。 |
| B4 全部 exported=true | ❌拒绝扩大攻击面。入口已 true，其余无外部入口需求；下一份单独验收显式 false 加固，不伪称当前缺失导致编译错误。 |
| B5 COUI keep 防重复类 | ❌规则不能解决 duplicate class，不添加无效 keep。✅consumer 注释/环境文档说明由消费方依赖图选择兼容实现，不通配排除未知 OEM 类。 |
| B6 SDK/JDK 说明 | ✅新增 docs/build-environment.md、README 入口、只读 PowerShell 预检；纠正 auto-detect=false 等于强制 JVM 的注释。无下载、复制、删除 SDK。 |
| B7 stdlib/bundles | ✔️无具体冲突或重复声明收益，不只为风格添加 catalog 条目。 |
| B8 放宽仓库策略 | ✔️保留 settings.gradle 的 FAIL_ON_PROJECT_REPOS 和集中仓库，当前无新增 module 仓库需求。 |
| B9 放宽 lint | ❌不以 abortOnError=false 隐藏问题；重新运行并如实记录依赖/分析结果。 |
| B10 META-INF 排除 | ✔️无冲突证据不增通配 excludes，不以删除 metadata 当普通瘦身。 |

## 3. 十项风险与十项简化

- 风险 1：上游/vendor 行为有明确差异，不叫当前崩溃；2：minSdk 支持边界明确；3：元数据版本不同不是 bug，不能承诺任意旧 compiler 可读；4：class header 不等于 Android 校验失败。
- 风险 5/10：本机 SDK 布局和 JDK 可复现性有真实限制，已明示并加预检，不标“干净机器已解决”；6：Build Tools 三段版本合法，不从 auto-detect 推导不存在的包。
- 风险 7：manifest 单独验收，拒绝全部导出；8：库 AAR 不需要 APK 签名；9：无 native 源码不需要 externalNativeBuild。
- K1/K2/K9：不加 NDK/ABI/OPPO flavor；K3/K4：发布签名和版本由实际应用负责；K5：保留 lib+demo，不抄 OEM product matrix。
- K6/K7：保留上游 DynamicAnimation 与通用曲线接口；不造 vendor 共存已验证声明，也不声称 keep 能缓解重复类。
- K8：不降 compiler 模仿 Metadata；删除“默认快 30%”“元数据一律向后兼容”等无本项目证据断言。
- K10：不接 Launcher 私有调试 IPC。库是 Android Library，不是纯 JVM 库。

## 4. 本轮验证

- 环境预检真实路径通过；不存在的 JDK 路径被明确拒绝。脚本只检查文件/版本标识，不证明 SDK 来源可信。
- Lint 再次因离线缺 intellij-core / kotlin-compiler 31.9.0 在 extractDebugAnnotations 失败（`.gradle/review-ordered-36-lint.log`），没有放宽检查或声称通过。
- Debug/Release 各 **333 tests / 44 类，0 failures/errors/skipped**；Demo Debug **82/82 tasks executed，3m 1s**（`.gradle/review-ordered-36-final.log`）。128 份源码/测试/构建配置/脚本 hash 不变，README/链接/whitespace 检查通过。
- AGENTS/OPPO/SDK 均未修改，无设备/Perfetto 验证，无提交/推送。
