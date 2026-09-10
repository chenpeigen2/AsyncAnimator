package com.asyncanimator.api

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证每个变体实际生成的文档，而不是用手写快照替代编译期产物。 */
class PublicApiDocumentationTest {
    /** 读取 Gradle 注入的本变体输出路径，缺少生成任务或文件时明确失败。 */
    private fun documentation(): String {
        val path = checkNotNull(System.getProperty("publicApi.documentation")) {
            "Gradle must provide the generated API documentation path"
        }
        return File(path).readText(Charsets.UTF_8)
    }

    /** 只收录显式声明；同名工厂重载分别保留，内部方法和未标记的公开字段不泄漏。 */
    @Test
    fun testGeneratedDocumentContainsOnlyAnnotatedDeclarations() {
        val text = documentation()
        val headings = text.lineSequence().filter { it.startsWith("## ") }.toList()
        assertEquals(10, headings.size)
        assertEquals(2, headings.count { it.endsWith(".Companion.ofFloat") })
        assertTrue(headings.any { it.endsWith(".executor") })
        assertTrue(headings.any { it.endsWith(".dispose") })
        assertFalse(headings.any { it.endsWith(".marshal") || it.endsWith(".asyncAnimCallbacks") })
    }

    /** 生成结果保留中文线程契约、可空参数和变长参数，来源使用仓库相对路径。 */
    @Test
    fun testGeneratedDocumentPreservesContractsAndSignatures() {
        val text = documentation()
        assertTrue(text.contains("生命周期命令使用的执行器"))
        assertTrue(text.contains("NullableAnimatorListener?"))
        assertTrue(text.contains("vararg values: kotlin.Float"))
        assertTrue(text.contains("com/asyncanimator/anim/AsyncValueAnimator.kt:"))
        assertFalse(text.contains("D:\\"))
    }

    /** 注解保留在二进制供工具识别，不依赖运行时反射建立 API 注册表。 */
    @Test
    fun testAnnotationUsesBinaryRetention() {
        val retention = PublicApi::class.java.getAnnotation(java.lang.annotation.Retention::class.java)
        assertEquals(java.lang.annotation.RetentionPolicy.CLASS, checkNotNull(retention).value)
    }

    /** 库资源处理任务完成后不得保留文档，避免项目依赖的使用方将其打入 APK。 */
    @Test
    fun testGeneratedDocumentIsExcludedFromLibraryJavaResources() {
        val path = checkNotNull(System.getProperty("publicApi.javaResources")) {
            "Gradle must provide the processed library Java resources path"
        }
        assertFalse(File(path, "public-api.md").exists())
        assertTrue(documentation().contains("API 声明数：10"))
    }

}
