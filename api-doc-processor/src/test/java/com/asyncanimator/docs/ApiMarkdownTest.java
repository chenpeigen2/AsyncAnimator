package com.asyncanimator.docs;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** 验证 Markdown 顺序、空输出和代码围栏，避免文档随遍历顺序漂移。 */
public class ApiMarkdownTest {
    /** 同一集合即使输入顺序改变，也得到字节一致的文档。 */
    @Test public void outputIsDeterministicAndKeepsOverloads() {
        ApiEntry first = new ApiEntry("sample.load", "fun load(x: Int)", "整型输入。", "Api.kt", 2);
        ApiEntry second = new ApiEntry("sample.load", "fun load(x: String)", "字符串输入。", "Api.kt", 4);
        assertEquals(ApiMarkdown.render(List.of(first, second)), ApiMarkdown.render(List.of(second, first)));
        assertTrue(ApiMarkdown.render(List.of(first, second)).contains("API 声明数：2"));
    }

    /** 最后一个标记删除后仍有零声明文档，不能残留旧 API。 */
    @Test public void emptySelectionProducesExplicitEmptyDocument() {
        assertTrue(ApiMarkdown.render(List.of()).contains("API 声明数：0"));
    }

    /** 中文说明与示例正文原样保留，签名围栏可以包容反引号标识符。 */
    @Test public void preservesChineseKDocAndSafeCodeFence() {
        String result = ApiMarkdown.render(List.of(new ApiEntry("sample.example", "fun ` ``` `()",
            "中文说明\n\n```kotlin\nexample()\n```", "Api.kt", 9)));
        assertTrue(result.contains("````kotlin"));
        assertTrue(result.contains("中文说明\n\n```kotlin"));
        assertTrue(result.contains("`Api.kt:9`"));
    }
    /** 构造器特殊名称不能被 Markdown 当成 HTML 标签而隐藏。 */
    @Test public void escapesConstructorHeading() {
        String text = ApiMarkdown.render(List.of(new ApiEntry("sample.Owner.<init>",
            "public constructor()", "公开构造器。", "Owner.kt", 1)));
        assertTrue(text.contains("## sample.Owner.&lt;init&gt;"));
    }
}
