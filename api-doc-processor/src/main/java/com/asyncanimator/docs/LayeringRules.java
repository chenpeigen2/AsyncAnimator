package com.asyncanimator.docs;

import com.google.devtools.ksp.symbol.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 编译期技术分层校验：源码根内每个顶层类声明必须标注 @Layer，代码中对更高层包的 import 或全限定引用都会编译失败。 */
final class LayeringRules {
    static final String ANNOTATION = "com.asyncanimator.api.Layer";
    /** 透明加密文件的物理头标记；读取到它说明构建进程无法校验真实源码，必须失败而不是跳过。 */
    private static final String ENCRYPTED_MARK = "%TSD-Header%";
    /** 注解载体包不参与分层：所有层都可引用 api，api 自身也不需要 @Layer 标注。 */
    private static final String EXEMPT_PACKAGE = "com.asyncanimator.api";

    /** 自底向上的层序，值即层级序号，越小越底层；同层互引与向下引用允许。 */
    private static final Map<String, Integer> LAYER_RANKS = Map.of(
        "com.asyncanimator.core", 0,
        "com.asyncanimator.thread", 0,
        "com.android.launcher3", 0,
        "com.asyncanimator.playback", 1,
        "com.asyncanimator.anim", 2,
        "com.asyncanimator.seq", 3,
        "com.asyncanimator.control", 4,
        "com.asyncanimator.manager", 5
    );

    /** 代码行内对库包的全限定引用；import、别名与通配符导入只是其中几种形态。 */
    private static final Pattern LIBRARY_REFERENCE = Pattern.compile(
        "\\bcom\\.(?:asyncanimator\\.([A-Za-z]\\w*)|android\\.launcher3)\\.(?:\\w+|\\*)");

    private LayeringRules() { }

    /** 一条分层违规，消息已包含限定名与源码位置，节点用于把诊断绑定到具体声明。 */
    static final class Violation {
        final String message;
        final KSNode node;

        Violation(String message, KSNode node) {
            this.message = message;
            this.node = node;
        }
    }

    /** 返回包所属层序；api 包返回 null 表示豁免，库内未登记的其他包返回 null 并由调用方按违规处理。 */
    static Integer rankOfPackage(String packageName) {
        if (packageName == null) return null;
        if (packageName.equals(EXEMPT_PACKAGE) || packageName.startsWith(EXEMPT_PACKAGE + ".")) return null;
        Integer best = null;
        int bestLength = -1;
        for (Map.Entry<String, Integer> layer : LAYER_RANKS.entrySet()) {
            String prefix = layer.getKey();
            if ((packageName.equals(prefix) || packageName.startsWith(prefix + "."))
                && prefix.length() > bestLength) {
                best = layer.getValue();
                bestLength = prefix.length();
            }
        }
        return best;
    }

    /** 遍历全部源码文件，返回漏标 @Layer、向上引用与未登记包三类违规；sourceRoot 为 null 时不校验。 */
    static List<Violation> check(Collection<KSFile> files, Path sourceRoot) {
        List<Violation> violations = new ArrayList<>();
        if (sourceRoot == null) return violations;
        for (KSFile file : files) {
            Path path = Path.of(file.getFilePath()).toAbsolutePath().normalize();
            if (!path.startsWith(sourceRoot)) continue;
            KSName packageName = file.getPackageName();
            Integer ownRank = rankOfPackage(packageName == null ? null : packageName.asString());
            if (ownRank == null) continue;
            checkDeclarations(file, violations);
            checkReferences(file, path, ownRank, violations);
        }
        return violations;
    }

    /** 顶层类、对象、接口与枚举都必须显式声明所属层；顶层函数、属性与类型别名不适用类目标规则。 */
    private static void checkDeclarations(KSFile file, List<Violation> violations) {
        for (KSDeclaration declaration : ApiSignatures.list(file.getDeclarations())) {
            if (!(declaration instanceof KSClassDeclaration) || declaration.getOrigin() == Origin.SYNTHETIC) {
                continue;
            }
            boolean marked = ApiSignatures.list(declaration.getAnnotations()).stream().anyMatch(annotation -> {
                KSName name = annotation.getAnnotationType().resolve().getDeclaration().getQualifiedName();
                return name != null && name.asString().equals(ANNOTATION);
            });
            if (!marked) {
                KSName qualified = declaration.getQualifiedName();
                String name = qualified != null ? qualified.asString() : declaration.getSimpleName().asString();
                violations.add(new Violation("技术分层缺少 @Layer 标注: " + name, declaration));
            }
        }
    }

    /**
     * 逐行扫描代码中对库包的全限定引用：import 行与普通代码行同一规则，目标层更高即违规。
     * 行注释、块注释、KDoc 与原始字符串会被跳过；库内未登记分层的包单独报违规以失败关闭。
     * 普通字符串字面量不做词法解析，其中出现的库包限定名可能误报，属于已知局限。
     */
    private static void checkReferences(KSFile file, Path path, int ownRank, List<Violation> violations) {
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException failure) {
            throw new IllegalStateException("分层校验无法读取源码文件: " + path, failure);
        }
        if (text.contains(ENCRYPTED_MARK)) {
            throw new IllegalStateException("分层校验读取到加密源码内容，构建进程必须能透明解密: " + path);
        }
        String packageName = file.getPackageName() == null ? "" : file.getPackageName().asString();
        String[] lines = text.split("\n");
        boolean inBlockComment = false;
        boolean inRawString = false;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (inRawString) {
                if (lines[i].contains("\"\"\"")) inRawString = false;
                continue;
            }
            if (inBlockComment) {
                int end = trimmed.indexOf("*/");
                if (end < 0) continue;
                inBlockComment = false;
                trimmed = trimmed.substring(end + 2).trim();
                if (trimmed.isEmpty()) continue;
            }
            if (trimmed.startsWith("//")) continue;
            // 去掉同行块注释段；未闭合的块注释开端记状态，后续行不再扫描直到闭合。
            while (true) {
                int open = trimmed.indexOf("/*");
                if (open < 0) break;
                int close = trimmed.indexOf("*/", open + 2);
                if (close < 0) {
                    trimmed = trimmed.substring(0, open);
                    inBlockComment = true;
                    break;
                }
                trimmed = (trimmed.substring(0, open) + " " + trimmed.substring(close + 2)).trim();
            }
            if (trimmed.isEmpty()) continue;
            if (trimmed.contains("\"\"\"")) {
                inRawString = true;
                continue;
            }
            int comment = trimmed.indexOf("//");
            String code = comment >= 0 ? trimmed.substring(0, comment) : trimmed;
            Matcher matcher = LIBRARY_REFERENCE.matcher(code);
            while (matcher.find()) {
                String reference = matcher.group();
                String segment = matcher.group(1);
                if (segment != null && segment.equals("api")) continue;
                if (segment != null && rankOfPackage("com.asyncanimator." + segment) == null) {
                    violations.add(new Violation(
                        "技术分层未登记包: " + packageName + " 引用了未登记分层的 " + reference
                            + "（" + path.getFileName() + ":" + (i + 1) + "）", file));
                    continue;
                }
                Integer targetRank = rankOfPackage(reference);
                if (targetRank != null && targetRank > ownRank) {
                    violations.add(new Violation(
                        "技术分层违规: " + packageName + " 不允许依赖更高层的 " + reference
                            + "（" + path.getFileName() + ":" + (i + 1) + "）", file));
                }
            }
        }
    }
}
