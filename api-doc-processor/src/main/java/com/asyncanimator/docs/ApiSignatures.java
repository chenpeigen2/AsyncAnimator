package com.asyncanimator.docs;

import com.google.devtools.ksp.symbol.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import kotlin.sequences.Sequence;

/** 使用 KSP 已解析类型生成签名，保留可空性、泛型、扩展接收者与重载信息。 */
final class ApiSignatures {
    private ApiSignatures() { }

    /** 把 Kotlin 惰性序列转换成当前轮次的快照，防止重复解析和迭代次序漂移。 */
    static <T> List<T> list(Sequence<? extends T> sequence) {
        List<T> result = new ArrayList<>();
        sequence.iterator().forEachRemaining(result::add);
        return result;
    }

    /** 拒绝本身或外层非公开的符号；局部声明和匿名类型也不能构成对外契约。 */
    static boolean externallyVisible(KSDeclaration declaration) {
        for (KSDeclaration current = declaration; current != null; current = current.getParentDeclaration()) {
            Set<Modifier> modifiers = current.getModifiers();
            if (modifiers.contains(Modifier.PRIVATE) || modifiers.contains(Modifier.INTERNAL)
                || modifiers.contains(Modifier.PROTECTED)
                || (current.getQualifiedName() == null && !isConstructor(current))) {
                return false;
            }
            if (current != declaration && current instanceof KSFunctionDeclaration) {
                return false;
            }
        }
        return true;
    }

    /** KSP 构造器没有独立限定名，以所属类型命名；局部/匿名外层仍由可见性检查拒绝。 */
    static boolean isConstructor(KSDeclaration declaration) {
        return declaration instanceof KSFunctionDeclaration
            && declaration.getSimpleName().asString().equals("<init>")
            && declaration.getParentDeclaration() instanceof KSClassDeclaration;
    }

    /** 普通声明使用符号限定名，构造器使用所属类型加特殊名称来区分多个重载。 */
    static String qualifiedName(KSDeclaration declaration) {
        if (declaration.getQualifiedName() != null) return declaration.getQualifiedName().asString();
        if (isConstructor(declaration)) {
            return qualifiedName(declaration.getParentDeclaration()) + ".<init>";
        }
        throw new IllegalArgumentException("API declaration has no qualified name");
    }

    /** 为受支持声明生成签名，无法解析的类型交给后续 KSP 轮次重试。 */
    static String render(KSDeclaration declaration) {
        String name = declaration.getSimpleName().asString();
        String generics = typeParameters(declaration.getTypeParameters());
        String constraints = bounds(declaration.getTypeParameters());
        String prefix = modifiers(declaration);
        if (declaration instanceof KSClassDeclaration type) {
            if (type.getClassKind() == ClassKind.ENUM_ENTRY) return name;
            String kind = switch (type.getClassKind()) {
                case INTERFACE -> "interface";
                case OBJECT -> "object";
                case ENUM_CLASS -> "enum class";
                case ANNOTATION_CLASS -> "annotation class";
                case ENUM_ENTRY -> throw new IllegalStateException("Enum entry was already rendered");
                default -> "class";
            };
            String parents = list(type.getSuperTypes()).stream().map(ApiSignatures::type)
                .filter(parent -> !parent.equals("kotlin.Any")).collect(Collectors.joining(", "));
            return prefix + kind + " " + name + generics + (parents.isEmpty() ? "" : " : " + parents) + constraints;
        }
        if (declaration instanceof KSFunctionDeclaration function) {
            boolean constructor = name.equals("<init>");
            String parameters = function.getParameters().stream().map(ApiSignatures::parameter)
                .collect(Collectors.joining(", "));
            return prefix + (constructor ? "constructor" : "fun " + (generics.isEmpty() ? "" : generics + " ")
                + receiver(function.getExtensionReceiver()) + name)
                + "(" + parameters + ")"
                + (constructor ? "" : ": " + type(function.getReturnType())) + constraints;
        }
        if (declaration instanceof KSPropertyDeclaration property) {
            String signature = prefix + (property.isMutable() ? "var " : "val ")
                + (generics.isEmpty() ? "" : generics + " ") + receiver(property.getExtensionReceiver())
                + name + ": " + type(property.getType()) + constraints;
            if (property.isMutable() && property.getSetter() != null) {
                for (Modifier visibility : List.of(Modifier.PRIVATE, Modifier.INTERNAL, Modifier.PROTECTED)) {
                    if (property.getSetter().getModifiers().contains(visibility)) {
                        signature += "\n    " + visibility.name().toLowerCase(java.util.Locale.ROOT) + " set";
                    }
                }
            }
            return signature;
        }
        if (declaration instanceof KSTypeAlias alias) {
            return prefix + "typealias " + name + generics + " = " + type(alias.getType());
        }
        throw new IllegalArgumentException("Unsupported @PublicApi declaration: " + declaration.getClass().getSimpleName());
    }

    /** 输出公开契约相关修饰符，不把 KSP 隐含的 FINAL 或 OVERRIDE 当作额外 API。 */
    private static String modifiers(KSDeclaration declaration) {
        StringBuilder result = new StringBuilder("public ");
        for (Modifier modifier : List.of(Modifier.EXPECT, Modifier.ACTUAL, Modifier.OPEN, Modifier.ABSTRACT,
            Modifier.SEALED, Modifier.CONST, Modifier.EXTERNAL, Modifier.INLINE, Modifier.TAILREC,
            Modifier.SUSPEND, Modifier.INNER, Modifier.DATA, Modifier.VALUE, Modifier.FUN,
            Modifier.INFIX, Modifier.OPERATOR)) {
            if (declaration.getModifiers().contains(modifier)) {
                result.append(modifier.name().toLowerCase(java.util.Locale.ROOT)).append(" ");
            }
        }
        return result.toString();
    }

    /** 参数保留变长、内联约束、名称和默认值存在性；KSP 不提供默认表达式，使用明确占位符。 */
    private static String parameter(KSValueParameter parameter) {
        String prefix = parameter.isVararg() ? "vararg " : "";
        if (parameter.isNoInline()) prefix += "noinline ";
        if (parameter.isCrossInline()) prefix += "crossinline ";
        if (parameter.getName() == null) throw new IllegalArgumentException("API parameter has no name");
        return prefix + parameter.getName().asString() + ": " + type(parameter.getType())
            + (parameter.getHasDefault() ? " = …" : "");
    }

    /** 扩展声明把接收者放在名称前，普通声明返回空前缀。 */
    private static String receiver(KSTypeReference reference) {
        return reference == null ? "" : type(reference) + ".";
    }

    /** 保留泛型名称、声明处型变及 reified，不递归展开类型参数自身的上界。 */
    private static String typeParameters(List<KSTypeParameter> parameters) {
        if (parameters.isEmpty()) return "";
        return parameters.stream().map(parameter -> (parameter.isReified() ? "reified " : "")
            + variance(parameter.getVariance()) + parameter.getName().asString())
            .collect(Collectors.joining(", ", "<", ">"));
    }

    /** 使用 where 列出所有非默认上界，避免遗漏多个约束或递归约束。 */
    private static String bounds(List<KSTypeParameter> parameters) {
        List<String> result = new ArrayList<>();
        for (KSTypeParameter parameter : parameters) {
            for (KSTypeReference bound : list(parameter.getBounds())) {
                String rendered = type(bound);
                if (!rendered.equals("kotlin.Any?")) result.add(parameter.getName().asString() + " : " + rendered);
            }
        }
        return result.isEmpty() ? "" : " where " + String.join(", ", result);
    }

    /** 型变只对 in/out 输出前缀，星投影由类型参数渲染入口独立处理。 */
    private static String variance(Variance variance) {
        return switch (variance) {
            case COVARIANT -> "out ";
            case CONTRAVARIANT -> "in ";
            default -> "";
        };
    }

    /** 解析类型并保留完整限定名、泛型投影及可空标记；错误类型不能进入最终文档。 */
    private static String type(KSTypeReference reference) {
        if (reference == null) throw new UnresolvedType();
        KSType type = reference.resolve();
        if (type.isError()) throw new UnresolvedType();
        KSDeclaration declaration = type.getDeclaration();
        String name = declaration instanceof KSTypeParameter ? declaration.getSimpleName().asString()
            : declaration.getQualifiedName() == null ? declaration.getSimpleName().asString()
            : declaration.getQualifiedName().asString();
        String arguments = type.getArguments().stream().map(argument -> argument.getVariance() == Variance.STAR
            ? "*" : variance(argument.getVariance()) + type(argument.getType()))
            .collect(Collectors.joining(", "));
        return name + (arguments.isEmpty() ? "" : "<" + arguments + ">")
            + (type.getNullability() == Nullability.NULLABLE ? "?" : "");
    }

    /** 本轮尚未生成的依赖类型标记，供处理器延后而不是输出不完整签名。 */
    static final class UnresolvedType extends RuntimeException { }
}
