package com.asyncanimator.docs;

import com.google.devtools.ksp.processing.*;
import com.google.devtools.ksp.symbol.*;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import kotlin.KotlinVersion;
import org.junit.Test;
import static com.asyncanimator.docs.TestSymbols.*;
import static org.junit.Assert.*;

/** 验证处理器轮次、诊断及聚合依赖，不仅检查渲染函数。 */
public class PublicApiProcessorTest {
    private final List<String> errors = new ArrayList<>();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private int writes;

    /** 构建服务替身会记录文件名和聚合依赖，模拟每次编译独立的输出目录。 */
    private PublicApiProcessor processor() {
        KSPLogger logger = (KSPLogger) Proxy.newProxyInstance(KSPLogger.class.getClassLoader(),
            new Class<?>[]{KSPLogger.class}, (self, method, args) -> {
                if (method.getName().equals("error")) errors.add((String) args[0]);
                return null;
            });
        CodeGenerator generator = (CodeGenerator) Proxy.newProxyInstance(CodeGenerator.class.getClassLoader(),
            new Class<?>[]{CodeGenerator.class}, (self, method, args) -> {
                if (method.getName().equals("createNewFile")) {
                    assertTrue(((Dependencies) args[0]).getAggregating());
                    assertEquals(1, ((Dependencies) args[0]).getOriginatingFiles().size());
                    assertEquals("public-api", args[2]);
                    assertEquals("md", args[3]);
                    writes++;
                    return output;
                }
                return null;
            });
        return new PublicApiProcessor(new SymbolProcessorEnvironment(
            Map.of("publicApi.sourceRoot", ROOT.toString()), new KotlinVersion(2, 0, 21), generator, logger));
    }

    /** 返回当前轮已解析的显式标记，并确认处理器使用注解限定名而非简单名字匹配。 */
    private Resolver resolver(KSAnnotated... symbols) {
        return (Resolver) Proxy.newProxyInstance(Resolver.class.getClassLoader(), new Class<?>[]{Resolver.class},
            (self, method, args) -> switch (method.getName()) {
                case "getAllFiles" -> sequence(List.of(file()));
                case "getSymbolsWithAnnotation" -> {
                    assertEquals(PublicApiProcessor.ANNOTATION, args[0]);
                    yield sequence(List.of(symbols));
                }
                default -> null;
            });
    }

    /** 跨轮重复符号不会重复输出，重载与中文说明都保留。 */
    @Test public void deduplicatesRoundsAndWritesOnlyOnFinish() {
        PublicApiProcessor processor = processor();
        KSFunctionDeclaration first = function("load", parameter("value", type("kotlin.Int", false), false));
        KSFunctionDeclaration second = function("load", parameter("value", type("kotlin.String", false), false));
        processor.process(resolver(first));
        processor.process(resolver(first, second));
        assertEquals(0, writes);
        processor.finish();
        assertEquals(1, writes);
        assertTrue(errors.isEmpty());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("API 声明数：2"));
    }

    /** 非公开 API 导致编译诊断，并禁止成功文档输出。 */
    @Test public void failsNonPublicApiWithoutOutput() {
        PublicApiProcessor processor = processor();
        Map<String, Object> values = declaration("Hidden");
        values.put("getModifiers", Set.of(Modifier.INTERNAL));
        processor.process(resolver(proxy(KSClassDeclaration.class, values)));
        processor.finish();
        assertEquals(0, writes);
        assertTrue(errors.get(0).contains("public declaration"));
    }

    /** 缺失注释以及只有英文的说明都不能绕过项目的中文契约要求。 */
    @Test public void rejectsMissingOrNonChineseKDoc() {
        for (String documentation : Arrays.asList(null, "", "Only English documentation")) {
            PublicApiProcessor processor = processor();
            Map<String, Object> values = declaration("MissingDocs");
            values.put("getDocString", documentation);
            processor.process(resolver(proxy(KSClassDeclaration.class, values)));
            processor.finish();
        }
        assertEquals(3, errors.size());
        assertEquals(0, writes);
    }

    /** 构造器缺少独立注释时，可复用其类型的中文构造契约。 */
    @Test public void constructorCanUseClassContract() {
        PublicApiProcessor processor = processor();
        Map<String, Object> values = declaration("<init>");
        values.put("getQualifiedName", null);
        values.put("getDocString", null);
        values.put("getParentDeclaration", proxy(KSClassDeclaration.class, declaration("Owner")));
        processor.process(resolver(proxy(KSFunctionDeclaration.class, values)));
        processor.finish();
        assertTrue(errors.isEmpty());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("public constructor()"));
    }

    /** 尚未生成的类型返回延后列表，最终仍未解决则产生错误而非输出残缺文档。 */
    @Test public void unresolvedSignatureIsDeferredAndFailsAtFinish() {
        PublicApiProcessor processor = processor();
        KSTypeReference unresolved = proxy(KSTypeReference.class,
            Map.of("resolve", proxy(KSType.class, Map.of("isError", true))));
        KSFunctionDeclaration declaration = function("load", parameter("value", unresolved, false));
        assertEquals(List.of(declaration), processor.process(resolver(declaration)));
        processor.finish();
        assertEquals(0, writes);
        assertTrue(errors.get(0).contains("Unresolved"));
    }

    /** 零标记仍写空文档；这是删除最后一个注解后的正确输出。 */
    @Test public void emptySelectionIsGenerated() {
        PublicApiProcessor processor = processor();
        processor.process(resolver());
        processor.finish();
        assertEquals(1, writes);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("API 声明数：0"));
    }

    /** KSP 报错后的生命周期结束不能再写看似成功的文档。 */
    @Test public void onErrorPreventsOutput() {
        PublicApiProcessor processor = processor();
        processor.process(resolver(function("load")));
        processor.onError();
        processor.finish();
        assertEquals(0, writes);
    }

    /** 源码根之外的符号明确拒绝，防止把机器路径写进可分发文档。 */
    @Test public void rejectsSourcesOutsideConfiguredRoot() {
        PublicApiProcessor processor = processor();
        Map<String, Object> values = declaration("Outside");
        values.put("getContainingFile", proxy(KSFile.class,
            Map.of("getFilePath", ROOT.getParent().resolve("outside.kt").toString())));
        processor.process(resolver(proxy(KSClassDeclaration.class, values)));
        processor.finish();
        assertEquals(0, writes);
        assertTrue(errors.get(0).contains("sourceRoot"));
    }
}
