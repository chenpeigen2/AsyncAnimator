package com.asyncanimator.docs;

import com.google.devtools.ksp.symbol.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static com.asyncanimator.docs.TestSymbols.*;
import static org.junit.Assert.*;

/** 覆盖检查从全部源码入口出发，不依赖已有注解列表或某个固定 API 数量。 */
public class PublicApiCoverageTest {
    /** 将声明装入真实源码根内的文件替身，供递归检查使用。 */
    private List<KSDeclaration> missing(KSDeclaration... declarations) {
        KSFile source = proxy(KSFile.class, Map.of("getFilePath", ROOT.resolve("NewApi.kt").toString(),
            "getDeclarations", sequence(List.of(declarations))));
        return PublicApiCoverage.missingAnnotations(List.of(source), ROOT);
    }

    /** 使用解析后的完全限定名识别标记，别名导入不影响身份判定。 */
    private KSAnnotation marker(String qualifiedName) {
        return proxy(KSAnnotation.class, Map.of("getAnnotationType", type(qualifiedName, false)));
    }

    /** 一个全新文件中的公开类型漏标也必须被发现，不只检查旧 API 所属类型。 */
    @Test public void detectsEntireUnannotatedType() {
        KSClassDeclaration declaration = proxy(KSClassDeclaration.class, declaration("NewApi"));
        assertEquals(List.of(declaration), missing(declaration));
    }

    /** 类型上的标记不会替成员补标，新增方法必须显式声明为对外契约。 */
    @Test public void detectsUnannotatedMemberInsideAnnotatedType() {
        KSFunctionDeclaration member = function("newMethod");
        Map<String, Object> values = declaration("Api");
        values.put("getAnnotations", sequence(List.of(marker(PublicApiProcessor.ANNOTATION))));
        values.put("getDeclarations", sequence(List.of(member)));
        assertEquals(List.of(member), missing(proxy(KSClassDeclaration.class, values)));
    }

    /** 公开构造器及构造参数生成的公开属性都需要覆盖，不能只记录类型和普通方法。 */
    @Test public void detectsPrimaryConstructorAndProperty() {
        KSFunctionDeclaration constructor = function("<init>");
        KSPropertyDeclaration property = proxy(KSPropertyDeclaration.class, declaration("value"));
        Map<String, Object> values = declaration("Options");
        values.put("getAnnotations", sequence(List.of(marker(PublicApiProcessor.ANNOTATION))));
        values.put("getPrimaryConstructor", constructor);
        values.put("getDeclarations", sequence(List.of(property)));
        assertEquals(List.of(constructor, property), missing(proxy(KSClassDeclaration.class, values)));
    }

    /** 不把 internal/private/protected 声明及其外层下的成员提升为公开接口。 */
    @Test public void excludesInaccessibleTypesAndTheirMembers() {
        for (Modifier visibility : List.of(Modifier.INTERNAL, Modifier.PRIVATE, Modifier.PROTECTED)) {
            Map<String, Object> values = declaration("Hidden");
            values.put("getModifiers", Set.of(visibility));
            values.put("getDeclarations", sequence(List.of(function("publicLookingMember"))));
            assertTrue(missing(proxy(KSClassDeclaration.class, values)).isEmpty());
        }
    }

    /** 数据类 copy、隐式构造器等合成声明不需要伪造源码注解。 */
    @Test public void excludesSyntheticDeclarations() {
        Map<String, Object> values = declaration("copy");
        values.put("getOrigin", Origin.SYNTHETIC);
        assertTrue(missing(proxy(KSFunctionDeclaration.class, values)).isEmpty());
    }

    /** 相同简单名称的其他注解不能冒充本库标记。 */
    @Test public void rejectsUnrelatedAnnotationWithSameSimpleName() {
        Map<String, Object> values = declaration("Api");
        values.put("getAnnotations", sequence(List.of(marker("other.PublicApi"))));
        assertEquals(1, missing(proxy(KSClassDeclaration.class, values)).size());
        values.put("getAnnotations", sequence(List.of(marker(PublicApiProcessor.ANNOTATION))));
        assertTrue(missing(proxy(KSClassDeclaration.class, values)).isEmpty());
    }

    /** BuildConfig 等生成目录不在手写源码根内，不要求修改生成文件。 */
    @Test public void excludesGeneratedSourceRoots() {
        KSFile generated = proxy(KSFile.class, Map.of("getFilePath", ROOT.resolveSibling("generated/BuildConfig.kt").toString(),
            "getDeclarations", sequence(List.of(function("generated")))));
        assertTrue(PublicApiCoverage.missingAnnotations(List.of(generated), ROOT).isEmpty());
    }
}
