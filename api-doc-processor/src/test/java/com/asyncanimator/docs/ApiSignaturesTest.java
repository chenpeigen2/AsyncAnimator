package com.asyncanimator.docs;

import com.google.devtools.ksp.symbol.*;
import java.util.*;
import org.junit.Test;
import static com.asyncanimator.docs.TestSymbols.*;
import static org.junit.Assert.*;

/** 校验公开性和签名渲染，防止内部实现混入外部契约或重载被合并。 */
public class ApiSignaturesTest {
    /** 显式非公开修饰符始终拒绝。 */
    @Test public void rejectsNonPublicDeclarations() {
        for (Modifier modifier : List.of(Modifier.PRIVATE, Modifier.INTERNAL, Modifier.PROTECTED)) {
            Map<String, Object> values = declaration("Hidden");
            values.put("getModifiers", Set.of(modifier));
            assertFalse(ApiSignatures.externallyVisible(proxy(KSClassDeclaration.class, values)));
        }
    }

    /** 外部 public 不会穿透内部外层类型。 */
    @Test public void rejectsPublicMemberInsideInternalClass() {
        Map<String, Object> outer = declaration("Outer");
        outer.put("getModifiers", Set.of(Modifier.INTERNAL));
        Map<String, Object> member = declaration("member");
        member.put("getParentDeclaration", proxy(KSClassDeclaration.class, outer));
        assertFalse(ApiSignatures.externallyVisible(proxy(KSFunctionDeclaration.class, member)));
    }

    /** 局部函数不是可承诺给库使用者的 API。 */
    @Test public void rejectsLocalDeclaration() {
        Map<String, Object> values = declaration("local");
        values.put("getQualifiedName", null);
        assertFalse(ApiSignatures.externallyVisible(proxy(KSFunctionDeclaration.class, values)));
    }

    /** 可空类型与不同参数类型保留在签名中，形成不同重载键。 */
    @Test public void preservesOverloadsAndNullability() {
        String first = ApiSignatures.render(function("load", parameter("value", type("kotlin.String", true), false)));
        String second = ApiSignatures.render(function("load", parameter("value", type("kotlin.Int", false), false)));
        assertTrue(first.contains("kotlin.String?"));
        assertNotEquals(first, second);
    }

    /** 泛型投影不会被直接擦除为原始类型。 */
    @Test public void preservesTypeArgumentsAndStarProjection() {
        KSTypeArgument star = proxy(KSTypeArgument.class, Map.of("getVariance", Variance.STAR));
        KSTypeArgument out = proxy(KSTypeArgument.class, Map.of("getVariance", Variance.COVARIANT,
            "getType", type("kotlin.String", true)));
        String text = ApiSignatures.render(function("load",
            parameter("value", type("sample.Pair", false, star, out), false)));
        assertTrue(text.contains("sample.Pair<*, out kotlin.String?>"));
    }

    /** 默认表达式不由 KSP 提供，文档明确用占位符而不是猜测。 */
    @Test public void distinguishesOptionalAndVarargParameters() {
        KSValueParameter values = proxy(KSValueParameter.class, Map.of("getName", name("values"),
            "getType", type("kotlin.Float", false), "isVararg", true));
        String text = ApiSignatures.render(function("create", values,
            parameter("duration", type("kotlin.Long", false), true)));
        assertTrue(text.contains("vararg values: kotlin.Float"));
        assertTrue(text.contains("duration: kotlin.Long = …"));
    }

    /** 扩展函数与普通函数同名也能通过接收者区分。 */
    @Test public void preservesExtensionReceiver() {
        Map<String, Object> values = declaration("reset");
        values.put("getExtensionReceiver", type("sample.Target", false));
        values.put("getReturnType", type("kotlin.Unit", false));
        assertTrue(ApiSignatures.render(proxy(KSFunctionDeclaration.class, values)).contains("sample.Target.reset()"));
    }

    /** 私有 setter 不应展示成外部可写属性。 */
    @Test public void preservesPrivateSetter() {
        Map<String, Object> values = declaration("running");
        values.put("isMutable", true);
        values.put("getType", type("kotlin.Boolean", false));
        values.put("getSetter", proxy(KSPropertySetter.class, Map.of("getModifiers", Set.of(Modifier.PRIVATE))));
        assertTrue(ApiSignatures.render(proxy(KSPropertyDeclaration.class, values)).endsWith("private set"));
    }

    /** 构造器使用 constructor 关键字而不是把特殊 JVM 名称伪装成函数。 */
    @Test public void rendersConstructor() {
        assertEquals("public constructor()", ApiSignatures.render(function("<init>")));
    }

    /** 错误类型必须延后解析，不进入生成文档。 */
    @Test public void rejectsUnresolvedType() {
        KSTypeReference unresolved = proxy(KSTypeReference.class,
            Map.of("resolve", proxy(KSType.class, Map.of("isError", true))));
        assertThrows(ApiSignatures.UnresolvedType.class,
            () -> ApiSignatures.render(function("load", parameter("value", unresolved, false))));
    }

    /** 类型别名也属于可独立标记的声明，目标类型需解析输出。 */
    @Test public void rendersTypeAlias() {
        Map<String, Object> values = declaration("Count");
        values.put("getType", type("kotlin.Int", false));
        assertEquals("public typealias Count = kotlin.Int", ApiSignatures.render(proxy(KSTypeAlias.class, values)));
    }
}
