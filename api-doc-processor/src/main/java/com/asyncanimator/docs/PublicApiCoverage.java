package com.asyncanimator.docs;

import com.google.devtools.ksp.symbol.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** 独立遍历整个源码根下的公开声明，防止仅检查已有注解而漏掉新增类型、模块或成员。 */
final class PublicApiCoverage {
    private PublicApiCoverage() { }

    /** 返回所有漏标的公开源码声明；外部依赖、生成目录及合成成员不属于手写 API 清单。 */
    static List<KSDeclaration> missingAnnotations(Collection<KSFile> files, Path sourceRoot) {
        List<KSDeclaration> missing = new ArrayList<>();
        if (sourceRoot == null) return missing;
        for (KSFile file : files) {
            if (!Path.of(file.getFilePath()).toAbsolutePath().normalize().startsWith(sourceRoot)) continue;
            for (KSDeclaration declaration : ApiSignatures.list(file.getDeclarations())) visit(declaration, missing);
        }
        return missing;
    }

    /** 仅沿声明容器展开，不把函数体局部对象、私有实现或不可访问外层下的声明提升为 API。 */
    private static void visit(KSDeclaration declaration, List<KSDeclaration> missing) {
        if (!ApiSignatures.externallyVisible(declaration) || declaration.getOrigin() == Origin.SYNTHETIC) return;
        if (declaration instanceof KSClassDeclaration || declaration instanceof KSFunctionDeclaration
            || declaration instanceof KSPropertyDeclaration || declaration instanceof KSTypeAlias) {
            boolean marked = ApiSignatures.list(declaration.getAnnotations()).stream().anyMatch(annotation -> {
                KSName name = annotation.getAnnotationType().resolve().getDeclaration().getQualifiedName();
                return name != null && name.asString().equals(PublicApiProcessor.ANNOTATION);
            });
            if (!marked) missing.add(declaration);
        }
        if (declaration instanceof KSClassDeclaration type) {
            List<KSDeclaration> children = ApiSignatures.list(type.getDeclarations());
            KSFunctionDeclaration constructor = type.getPrimaryConstructor();
            if (constructor != null && !children.contains(constructor)) visit(constructor, missing);
            for (KSDeclaration child : children) visit(child, missing);
        }
    }
}
