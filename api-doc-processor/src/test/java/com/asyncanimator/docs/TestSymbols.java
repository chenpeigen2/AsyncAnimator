package com.asyncanimator.docs;

import com.google.devtools.ksp.symbol.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import kotlin.sequences.Sequence;

/** 构造最小 KSP 符号契约替身；真实解析另由 lib 变体编译和生成产物测试覆盖。 */
final class TestSymbols {
    static final Path ROOT = Path.of("fixture-sources").toAbsolutePath();
    private TestSymbols() { }

    /** 提供可重复遍历的序列，模拟 KSP 的声明与源文件枚举。 */
    static <T> Sequence<T> sequence(List<T> values) {
        return values::iterator;
    }

    /** 以方法返回值表构造接口替身，未配置集合和布尔查询采用空/false。 */
    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, Map<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getName().equals("equals")) return self == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            if (method.getName().equals("toString")) return type.getSimpleName() + values;
            if (values.containsKey(method.getName())) return values.get(method.getName());
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == Set.class) return Set.of();
            if (method.getReturnType() == List.class) return List.of();
            if (method.getReturnType() == Sequence.class) return sequence(List.of());
            return null;
        });
    }

    /** 名称替身只提供签名和诊断使用的原始字符串。 */
    static KSName name(String name) {
        return proxy(KSName.class, Map.of("asString", name));
    }

    /** 创建带中文契约和相对源码位置的默认公开声明，可逐项覆盖。 */
    static Map<String, Object> declaration(String simpleName) {
        Map<String, Object> values = new HashMap<>();
        values.put("getSimpleName", name(simpleName));
        values.put("getQualifiedName", name("sample." + simpleName));
        values.put("getDocString", "中文契约说明，调用方需遵守线程约束。");
        values.put("getContainingFile", file());
        values.put("getLocation", new FileLocation(ROOT.resolve("Api.kt").toString(), 12));
        values.put("getClassKind", ClassKind.CLASS);
        return values;
    }

    /** 文件替身仅用于聚合依赖与路径稳定性测试。 */
    static KSFile file() {
        return proxy(KSFile.class, Map.of("getFilePath", ROOT.resolve("Api.kt").toString()));
    }

    /** 生成简单或带泛型投影的已解析类型引用，不依赖字符串解析正则。 */
    static KSTypeReference type(String qualifiedName, boolean nullable, KSTypeArgument... arguments) {
        Map<String, Object> declaration = declaration(qualifiedName);
        declaration.put("getQualifiedName", name(qualifiedName));
        KSType resolved = proxy(KSType.class, Map.of(
            "getDeclaration", proxy(KSClassDeclaration.class, declaration),
            "getNullability", nullable ? Nullability.NULLABLE : Nullability.NOT_NULL,
            "getArguments", List.of(arguments)
        ));
        return proxy(KSTypeReference.class, Map.of("resolve", resolved));
    }

    /** 生成带明确名字和类型的函数参数，其他参数标志默认关闭。 */
    static KSValueParameter parameter(String name, KSTypeReference type, boolean hasDefault) {
        return proxy(KSValueParameter.class, Map.of("getName", name(name), "getType", type, "getHasDefault", hasDefault));
    }

    /** 生成带给定参数列表的普通函数，默认返回 Unit。 */
    static KSFunctionDeclaration function(String name, KSValueParameter... parameters) {
        Map<String, Object> values = declaration(name);
        values.put("getParameters", List.of(parameters));
        values.put("getReturnType", type("kotlin.Unit", false));
        return proxy(KSFunctionDeclaration.class, values);
    }
}
