package com.asyncanimator.docs;

import com.google.devtools.ksp.symbol.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import static com.asyncanimator.docs.TestSymbols.*;
import static org.junit.Assert.*;

/** 分层校验从包层序、显式标注与全限定引用方向三个维度强制依赖图，不依赖某个固定类型清单。 */
public class LayeringRulesTest {
    private Path root;

    /** 每个用例使用独立临时源码根，引用校验读取真实文件内容。 */
    @Before
    public void createRoot() throws Exception {
        root = Files.createTempDirectory("layering-rules");
    }

    /** 包层序自底向上编号，api 包与库外包不参与分层。 */
    @Test
    public void mapsPackageToLayerRank() {
        assertEquals(Integer.valueOf(0), LayeringRules.rankOfPackage("com.asyncanimator.core"));
        assertEquals(Integer.valueOf(0), LayeringRules.rankOfPackage("com.asyncanimator.thread"));
        assertEquals(Integer.valueOf(0), LayeringRules.rankOfPackage("com.android.launcher3"));
        assertEquals(Integer.valueOf(1), LayeringRules.rankOfPackage("com.asyncanimator.playback"));
        assertEquals(Integer.valueOf(2), LayeringRules.rankOfPackage("com.asyncanimator.anim"));
        assertEquals(Integer.valueOf(3), LayeringRules.rankOfPackage("com.asyncanimator.seq"));
        assertEquals(Integer.valueOf(4), LayeringRules.rankOfPackage("com.asyncanimator.control"));
        assertEquals(Integer.valueOf(5), LayeringRules.rankOfPackage("com.asyncanimator.manager"));
        assertNull(LayeringRules.rankOfPackage("com.asyncanimator.api"));
        assertNull(LayeringRules.rankOfPackage("com.asyncanimator.api.internal"));
        assertNull(LayeringRules.rankOfPackage("androidx.core.os"));
        assertNull(LayeringRules.rankOfPackage("com.asyncanimator.animx"));
    }

    /** 写入带引用语句的真实源码文件，并构造对应包与顶层声明的文件替身。 */
    private KSFile source(String packageName, String fileName, String text, KSDeclaration... declarations)
        throws Exception {
        Path path = root.resolve(fileName);
        Files.writeString(path, text);
        return proxy(KSFile.class, Map.of(
            "getFilePath", path.toString(),
            "getPackageName", name(packageName),
            "getDeclarations", sequence(List.of(declarations))));
    }

    /** 构造顶层类替身，可带 @Layer 标记并覆盖限定名与所在文件。 */
    private KSClassDeclaration clazz(String packageName, String simpleName, boolean annotated) {
        Map<String, Object> values = declaration(simpleName);
        values.put("getQualifiedName", name(packageName + "." + simpleName));
        if (annotated) {
            values.put("getAnnotations", sequence(List.of(marker(LayeringRules.ANNOTATION))));
        }
        return proxy(KSClassDeclaration.class, values);
    }

    /** 使用解析后的注解限定名识别标记，与覆盖检查保持一致。 */
    private KSAnnotation marker(String qualifiedName) {
        return proxy(KSAnnotation.class, Map.of("getAnnotationType", type(qualifiedName, false)));
    }

    /** 完整标注且只向下依赖的源码不产生违规。 */
    @Test
    public void acceptsAnnotatedTypesWithDownwardImports() throws Exception {
        KSFile file = source("com.asyncanimator.anim", "Anim.kt",
            "package com.asyncanimator.anim\n"
                + "\n"
                + "import com.asyncanimator.playback.NullableAnimatorListener\n"
                + "import com.asyncanimator.core.LogUtils\n"
                + "import com.asyncanimator.api.PublicApi\n",
            clazz("com.asyncanimator.anim", "AsyncValueAnimator", true));
        assertTrue(LayeringRules.check(List.of(file), root).isEmpty());
    }

    /** 顶层类漏标 @Layer 必须被发现，消息包含限定名。 */
    @Test
    public void detectsTopLevelTypeMissingLayerAnnotation() throws Exception {
        KSFile file = source("com.asyncanimator.core", "Hidden.kt",
            "package com.asyncanimator.core\n",
            clazz("com.asyncanimator.core", "HiddenHandler", false));
        List<LayeringRules.Violation> violations = LayeringRules.check(List.of(file), root);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).message.contains("com.asyncanimator.core.HiddenHandler"));
        assertNotNull(violations.get(0).node);
    }

    /** 对象、接口与枚举同样属于顶层声明，漏标一并报告。 */
    @Test
    public void detectsMissingAnnotationAcrossClassKinds() throws Exception {
        KSFile file = source("com.asyncanimator.seq", "Seq.kt",
            "package com.asyncanimator.seq\n",
            clazz("com.asyncanimator.seq", "SeqObject", false),
            clazz("com.asyncanimator.seq", "SeqInterface", false),
            clazz("com.asyncanimator.seq", "SeqEnum", false));
        assertEquals(3, LayeringRules.check(List.of(file), root).size());
    }

    /** 顶层函数、属性与类型别名不适用类目标规则，不应要求标注。 */
    @Test
    public void exemptsNonClassTopLevelDeclarations() throws Exception {
        KSFile file = source("com.asyncanimator.anim", "TopLevel.kt",
            "package com.asyncanimator.anim\n"
                + "\n"
                + "internal typealias Applicator = (Any?) -> Unit\n",
            TestSymbols.function("helper"));
        assertTrue(LayeringRules.check(List.of(file), root).isEmpty());
    }

    /** import 指向更高层即违规，消息带目标限定名与行号。 */
    @Test
    public void detectsImportOfHigherLayer() throws Exception {
        KSFile file = source("com.asyncanimator.playback", "Playback.kt",
            "package com.asyncanimator.playback\n"
                + "\n"
                + "import com.asyncanimator.anim.AsyncValueAnimator\n",
            clazz("com.asyncanimator.playback", "PlaybackController", true));
        List<LayeringRules.Violation> violations = LayeringRules.check(List.of(file), root);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).message.contains("com.asyncanimator.anim.AsyncValueAnimator"));
        assertTrue(violations.get(0).message.contains(":3"));
    }

    /** 不带 import 的全限定引用与普通代码引用同一规则，上探更高层同样违规。 */
    @Test
    public void detectsFullyQualifiedReferenceWithoutImport() throws Exception {
        KSFile file = source("com.asyncanimator.anim", "Inline.kt",
            "package com.asyncanimator.anim\n"
                + "\n"
                + "internal fun probe(): Any = com.asyncanimator.manager.OplusAnimManager\n",
            clazz("com.asyncanimator.anim", "InlineAnim", true));
        List<LayeringRules.Violation> violations = LayeringRules.check(List.of(file), root);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).message.contains("com.asyncanimator.manager.OplusAnimManager"));
        assertTrue(violations.get(0).message.contains(":3"));
    }

    /** 行注释、块注释、KDoc 与原始字符串中的库包名只是文本，不得误判为依赖。 */
    @Test
    public void ignoresCommentsAndRawStrings() throws Exception {
        KSFile file = source("com.asyncanimator.anim", "Docs.kt",
            "package com.asyncanimator.anim\n"
                + "\n"
                + "/** 参见 com.asyncanimator.manager.OplusAnimManager 的工厂语义。\n"
                + " * @see com.asyncanimator.manager.OplusAnimManager\n"
                + " */\n"
                + "// com.asyncanimator.manager.OplusAnimManager 仅供文档提及\n"
                + "/* 块注释 com.asyncanimator.manager.OplusAnimManager */\n"
                + "internal val doc = \"\"\"\n"
                + "    原始字符串 com.asyncanimator.manager.OplusAnimManager 不构成分层依赖\n"
                + "\"\"\".trimIndent()\n",
            clazz("com.asyncanimator.anim", "DocAnim", true));
        assertTrue(LayeringRules.check(List.of(file), root).isEmpty());
    }

    /** 库内未登记分层的包必须显式补登，引用它时报未登记违规而不是静默放行。 */
    @Test
    public void detectsUnregisteredLibraryPackage() throws Exception {
        KSFile file = source("com.asyncanimator.anim", "Unknown.kt",
            "package com.asyncanimator.anim\n"
                + "\n"
                + "internal val helper = com.asyncanimator.util.Helpers\n",
            clazz("com.asyncanimator.anim", "UnknownAnim", true));
        List<LayeringRules.Violation> violations = LayeringRules.check(List.of(file), root);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).message.contains("未登记"));
        assertTrue(violations.get(0).message.contains("com.asyncanimator.util.Helpers"));
    }

    /** 工厂层引用控制层、序列层属于向下依赖，不产生违规。 */
    @Test
    public void acceptsManagerDependingOnControlAndSeq() throws Exception {
        KSFile file = source("com.asyncanimator.manager", "Manager.kt",
            "package com.asyncanimator.manager\n"
                + "\n"
                + "import com.asyncanimator.control.AnimationController\n"
                + "import com.asyncanimator.seq.AnimationSeqHelper\n",
            clazz("com.asyncanimator.manager", "OplusAnimManager", true));
        assertTrue(LayeringRules.check(List.of(file), root).isEmpty());
    }

    /** 同层互引允许，例如基础层内 thread 引用 core。 */
    @Test
    public void acceptsSameLayerImports() throws Exception {
        KSFile file = source("com.asyncanimator.thread", "Thread.kt",
            "package com.asyncanimator.thread\n"
                + "\n"
                + "import com.asyncanimator.core.AnimationHandler\n",
            clazz("com.asyncanimator.thread", "AnimationControlThread", true));
        assertTrue(LayeringRules.check(List.of(file), root).isEmpty());
    }

    /** 通配符与别名导入都归一到目标包，更高层通配符同样违规，api 别名始终放行。 */
    @Test
    public void normalizesWildcardAndAliasImports() throws Exception {
        KSFile wildcard = source("com.asyncanimator.seq", "Wildcard.kt",
            "package com.asyncanimator.seq\n"
                + "\n"
                + "import com.asyncanimator.control.*\n",
            clazz("com.asyncanimator.seq", "WildcardSeq", true));
        assertEquals(1, LayeringRules.check(List.of(wildcard), root).size());
        KSFile alias = source("com.asyncanimator.anim", "Alias.kt",
            "package com.asyncanimator.anim\n"
                + "\n"
                + "import com.asyncanimator.api.PublicApi as Api\n"
                + "import com.asyncanimator.manager.OplusAnimManager as Manager\n",
            clazz("com.asyncanimator.anim", "AliasAnim", true));
        List<LayeringRules.Violation> violations = LayeringRules.check(List.of(alias), root);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).message.contains("com.asyncanimator.manager.OplusAnimManager"));
    }

    /** api 包与库外包不参与分层：既不强制标注也不检查引用。 */
    @Test
    public void exemptsAnnotationCarrierAndForeignPackages() throws Exception {
        KSFile api = source("com.asyncanimator.api", "Api.kt",
            "package com.asyncanimator.api\n"
                + "\n"
                + "import com.asyncanimator.manager.OplusAnimManager\n",
            clazz("com.asyncanimator.api", "Layer", false));
        assertTrue(LayeringRules.check(List.of(api), root).isEmpty());
    }

    /** 源码根之外的文件不参与分层校验。 */
    @Test
    public void ignoresFilesOutsideSourceRoot() throws Exception {
        KSFile file = proxy(KSFile.class, Map.of(
            "getFilePath", root.resolve("..").toAbsolutePath().normalize().resolve("Outside.kt").toString(),
            "getPackageName", name("com.asyncanimator.anim"),
            "getDeclarations", sequence(List.of(clazz("com.asyncanimator.anim", "Outside", false)))));
        assertTrue(LayeringRules.check(List.of(file), root).isEmpty());
    }

    /** 读取到透明加密内容时失败关闭，而不是把违规检查静默跳过。 */
    @Test
    public void failsClosedOnEncryptedContent() throws Exception {
        Path path = root.resolve("Encrypted.kt");
        Files.writeString(path, "%TSD-Header%##\npackage com.asyncanimator.core\n");
        KSFile file = proxy(KSFile.class, Map.of(
            "getFilePath", path.toString(),
            "getPackageName", name("com.asyncanimator.core"),
            "getDeclarations", sequence(List.of())));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> LayeringRules.check(List.of(file), root));
        assertTrue(failure.getMessage().contains("加密源码内容"));
    }
}
