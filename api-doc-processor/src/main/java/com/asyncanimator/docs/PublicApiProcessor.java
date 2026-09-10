package com.asyncanimator.docs;

import com.google.devtools.ksp.processing.*;
import com.google.devtools.ksp.symbol.*;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** 编译期收集显式公共 API，校验可访问性与中文文档，并按变体生成聚合 Markdown。 */
final class PublicApiProcessor implements SymbolProcessor {
    static final String ANNOTATION = "com.asyncanimator.api.PublicApi";
    private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fff]");
    private final CodeGenerator generator;
    private final KSPLogger logger;
    private final Path sourceRoot;
    private final Map<String, ApiEntry> entries = new TreeMap<>();
    private final Map<String, KSFile> sources = new TreeMap<>();
    private final Set<String> deferredNames = new TreeSet<>();
    private final boolean requireComplete;
    private boolean failed;

    /** 构造只保存本次编译服务与源码根路径，不扫描文件或生成输出。 */
    PublicApiProcessor(SymbolProcessorEnvironment environment) {
        requireComplete = Boolean.parseBoolean(environment.getOptions().get("publicApi.requireComplete"));
        generator = environment.getCodeGenerator();
        logger = environment.getLogger();
        String root = environment.getOptions().get("publicApi.sourceRoot");
        sourceRoot = root == null ? null : Path.of(root).toAbsolutePath().normalize();
    }

    /** 每轮处理新符号，使用解析后的注解限定名识别别名导入，延后尚未解析的类型。 */
    @Override
    public List<KSAnnotated> process(Resolver resolver) {
        for (KSFile file : ApiSignatures.list(resolver.getAllFiles())) sources.put(file.getFilePath(), file);
        if (requireComplete) {
            for (KSDeclaration missing : PublicApiCoverage.missingAnnotations(sources.values(), sourceRoot)) {
                error("Public API is missing @PublicApi: " + ApiSignatures.qualifiedName(missing), missing);
            }
        }
        List<KSAnnotated> deferred = new ArrayList<>();
        deferredNames.clear();
        for (KSAnnotated symbol : ApiSignatures.list(resolver.getSymbolsWithAnnotation(ANNOTATION, false))) {
            if (!(symbol instanceof KSDeclaration declaration)) {
                error("@PublicApi must annotate a declaration", symbol);
                continue;
            }
            if (!ApiSignatures.externallyVisible(declaration)) {
                error("@PublicApi requires a public declaration and public enclosing types", declaration);
                continue;
            }
            String documentation = documentation(declaration);
            if (documentation == null || !CHINESE.matcher(documentation).find()) {
                error("@PublicApi requires Chinese KDoc describing the API contract", declaration);
                continue;
            }
            try {
                String signature = ApiSignatures.render(declaration);
                String name = ApiSignatures.qualifiedName(declaration);
                KSFile file = declaration.getContainingFile();
                if (file == null) throw new IllegalArgumentException("API source file is unavailable");
                Path path = Path.of(file.getFilePath()).toAbsolutePath().normalize();
                if (sourceRoot == null || !path.startsWith(sourceRoot)) {
                    throw new IllegalArgumentException("@PublicApi source must be under publicApi.sourceRoot");
                }
                int line = declaration.getLocation() instanceof FileLocation location ? location.getLineNumber() : 0;
                ApiEntry entry = new ApiEntry(name, signature, documentation,
                    sourceRoot.relativize(path).toString().replace('\\', '/'), line);
                entries.put(entry.key(), entry);
            } catch (ApiSignatures.UnresolvedType unresolved) {
                deferred.add(declaration);
                deferredNames.add(declaration.getSimpleName().asString());
            } catch (IllegalArgumentException invalid) {
                error(invalid.getMessage(), declaration);
            }
        }
        return deferred;
    }

    /** 构造器可复用所属类型的构造契约，其余声明必须提供自身 KDoc，避免默默继承不匹配的说明。 */
    private String documentation(KSDeclaration declaration) {
        String documentation = declaration.getDocString();
        if ((documentation == null || documentation.isBlank())
            && declaration instanceof KSFunctionDeclaration
            && declaration.getSimpleName().asString().equals("<init>")
            && declaration.getParentDeclaration() != null) {
            documentation = declaration.getParentDeclaration().getDocString();
        }
        return documentation;
    }

    /** 所有轮次完成后只写一次聚合资源；把全部源码登记为依赖以覆盖增删标记及文档更新。 */
    @Override
    public void finish() {
        if (!deferredNames.isEmpty()) error("Unresolved @PublicApi types: " + deferredNames, null);
        if (failed) return;
        Dependencies dependencies = new Dependencies(true, sources.values().toArray(KSFile[]::new));
        try (OutputStream output = generator.createNewFile(dependencies, "", "public-api", "md")) {
            output.write(ApiMarkdown.render(entries.values()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            error("Cannot generate public-api.md: " + failure.getMessage(), null);
        }
    }

    /** 编译出错时禁止生成成功文档，不吞掉 KSP 已记录的诊断。 */
    @Override
    public void onError() {
        failed = true;
    }

    /** 将错误绑定到源码位置并阻止本次输出，KSP 会令对应编译任务失败。 */
    private void error(String message, KSNode node) {
        failed = true;
        logger.error(message, node);
    }
}
