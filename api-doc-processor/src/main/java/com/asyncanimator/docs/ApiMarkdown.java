package com.asyncanimator.docs;

import java.util.Collection;
import java.util.Comparator;

/** 把不可变 API 快照渲染为可重复生成的 UTF-8 Markdown，不写入时间戳或绝对路径。 */
final class ApiMarkdown {
    private ApiMarkdown() { }

    /** 按限定名和签名稳定排序；零标记时也生成明确的空目录，避免沿用旧文档。 */
    static String render(Collection<ApiEntry> entries) {
        StringBuilder out = new StringBuilder("# AsyncAnimator 对外 API\n\n");
        out.append("由 `@PublicApi` 与中文 KDoc 在编译时生成，请勿手工修改。\n\n")
            .append("只收录显式标记的声明；类型标记不自动包含成员、继承成员或生成方法。\n")
            .append("签名中的 `= …` 表示参数有默认值，不代表实际默认表达式。类型名称使用限定名。\n\n")
            .append("API 声明数：").append(entries.size()).append("\n\n");
        entries.stream().sorted(Comparator.comparing(ApiEntry::key)).forEach(entry -> {
            out.append("## ").append(entry.name().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("`", "")).append("\n\n");
            out.append("来源：`").append(entry.source().replace("`", "")).append(":")
                .append(entry.line()).append("`\n\n");
            String fence = "```";
            while (entry.signature().contains(fence)) {
                fence += "`";
            }
            out.append(fence).append("kotlin\n").append(entry.signature()).append("\n")
                .append(fence).append("\n\n").append(entry.documentation().strip()).append("\n\n");
        });
        return out.toString();
    }
}
