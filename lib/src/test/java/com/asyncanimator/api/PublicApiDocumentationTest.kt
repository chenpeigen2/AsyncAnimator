package com.asyncanimator.api

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证真实编译产物的跨模块覆盖，不用固定的小样本总数代替公开 API 完整性。 */
class PublicApiDocumentationTest {
    /** 读取 Gradle 注入的变体输出；缺少任务或文件时直接失败。 */
    private fun documentation(): String {
        val path = checkNotNull(System.getProperty("publicApi.documentation")) {
            "Gradle must provide the generated API documentation path"
        }
        return File(path).readText(Charsets.UTF_8)
    }

    /** 提取独立声明标题，重载保留重复名称，构造器标题使用转义后的特殊名称。 */
    private fun names(): List<String> = documentation().lineSequence()
        .filter { it.startsWith("## ") }
        .map { it.removePrefix("## ") }
        .toList()

    /** 覆盖各模块所有公开顶层类型，以及配置文件中的附加公开值类型，防止整块 API 漏标。 */
    @Test
    fun testEveryPublicModuleAndTopLevelTypeIsDocumented() {
        val modules = mapOf(
            "anim" to listOf(
                "ActualEndAnimListener", "AsyncAnimCallbacks", "AsyncSpringAnim", "AsyncValueAnimator",
                "CustomRectFSpringAnim", "MultiAnimatorSet", "RectSpringConfig", "RectSpringValues",
                "RectSpringFrame", "RectSpringDriver"
            ),
            "control" to listOf(
                "AnimationController", "AppExitScene", "GestureScene", "AnimationState",
                "DefaultAnimationController", "OnAnimStateChangeListener", "RemoteAnimationFactory",
                "TaskStateChangeTimeOutListener"
            ),
            "core" to listOf("LogUtils", "Trace"),
            "manager" to listOf("AnimationFeatureHelper", "OplusAnimManager"),
            "playback" to listOf("NullableAnimatorListener", "NullableAnimatorListenerAdapter"),
            "seq" to listOf("AnimationSeqHelper", "AnimSeqTimeStamp", "DefaultAnimationSeqHelper"),
            "thread" to listOf("AnimationControlThread", "AsyncAnimWrapper", "Executors", "LooperExecutor"),
            "api" to listOf("PublicApi")
        )
        val names = names().toSet()
        for ((module, types) in modules) {
            for (type in types) {
                val name = "com.asyncanimator.$module.$type"
                assertTrue("Missing API type: $name", name in names)
            }
            assertTrue(documentation().contains("| `com/asyncanimator/$module` |"))
        }
        assertTrue("com.android.launcher3.LauncherAnimationRunner.RemoteAnimationTarget" in names)
    }

    /** 公开成员不能再按示例范围排除，同名工厂重载仍分别生成。 */
    @Test
    fun testPublicMembersAndOverloadsAreNotLimitedToTheInitialExample() {
        val names = names()
        assertEquals(2, names.count { it == "com.asyncanimator.anim.AsyncValueAnimator.Companion.ofFloat" })
        for (name in listOf(
            "anim.AsyncValueAnimator.asyncAnimCallbacks",
            "anim.MultiAnimatorSet.animatorSet",
            "anim.MultiAnimatorSet.asyncAnimatorSet",
            "anim.CustomRectFSpringAnim.Listener.onActualEnd",
            "anim.CustomRectFSpringAnim.Driver.supportsAnimationThread",
            "manager.AnimationFeatureHelper.snapshot",
            "manager.OplusAnimManager.interruptionEnabled",
            "thread.Executors.MAIN_EXECUTOR",
            "core.LogUtils.setLogLevel"
        )) {
            assertTrue("Missing API member: $name", "com.asyncanimator.$name" in names)
        }
    }

    /** 构造器、构造参数属性与枚举项同样属于调用契约，不能仅记录函数和类型名称。 */
    @Test
    fun testConstructorsDataPropertiesAndEnumEntriesAreDocumented() {
        val names = names().toSet()
        for (name in listOf(
            "anim.RectSpringConfig.&lt;init&gt;",
            "anim.RectSpringConfig.Spring.stiffness",
            "anim.RectSpringConfig.Tracking.CENTER",
            "anim.RectSpringValues.trackedY",
            "anim.RectSpringFrame.velocities",
            "anim.CustomRectFSpringAnim.Event.runId",
            "anim.CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME",
            "control.AnimationState.SWIPE_UP_TO_CAPSULE",
            "control.AnimationState.withTaskbarAlignment",
            "control.GestureScene.baseActivityPackage",
            "control.TaskStateChangeTimeOutListener.&lt;init&gt;",
            "manager.AnimationFeatureHelper.Snapshot.asyncEnable"
        )) {
            assertTrue("Missing structured API: $name", "com.asyncanimator.$name" in names)
        }
        assertTrue(documentation().contains("private set"))
    }

    /** 内部帧调度、播放和续行内核以及私有实现不因扩充文档而变成对外接口。 */
    @Test
    fun testInternalAndPrivateImplementationIsExcluded() {
        val names = names()
        for (type in listOf(
            "core.AnimationHandler", "core.TickScheduler", "core.ChoreographerTickScheduler",
            "playback.PendingAnimation", "playback.AnimatorPlaybackController", "playback.PropertySetter",
            "playback.Interpolators", "anim.OplusValueAnimator", "anim.RecordInputInterpolator",
            "anim.RectAnimationLifecycle", "anim.SpringProjection"
        )) {
            assertFalse("Internal API leaked: $type", names.any { it.startsWith("com.asyncanimator.$type") })
        }
        assertFalse(names.any { it.endsWith(".marshal") || it.endsWith(".emit") })
        assertFalse(names.any { it.endsWith(".copy") || it.endsWith(".component1") })
    }

    /** 中文契约、类型限定名、可空与变长参数保留；声明计数必须与实际标题一致。 */
    @Test
    fun testGeneratedDocumentPreservesContractsAndSignatures() {
        val text = documentation()
        assertTrue(text.contains("生命周期命令使用的执行器"))
        assertTrue(text.contains("NullableAnimatorListener?"))
        assertTrue(text.contains("vararg values: kotlin.Float"))
        assertTrue(text.contains("com/asyncanimator/anim/AsyncValueAnimator.kt:"))
        assertTrue(text.contains("API 声明数：${names().size}"))
        assertFalse(text.contains("D:\\"))
    }

    /** 二进制保留用于工具识别，不把标记误解成运行时反射注册机制。 */
    @Test
    fun testAnnotationUsesBinaryRetention() {
        val retention = PublicApi::class.java.getAnnotation(java.lang.annotation.Retention::class.java)
        assertEquals(java.lang.annotation.RetentionPolicy.CLASS, checkNotNull(retention).value)
    }

    /** 库资源处理完成后文档只能留在文档目录，不能泄漏到使用方 APK。 */
    @Test
    fun testGeneratedDocumentIsExcludedFromLibraryJavaResources() {
        val path = checkNotNull(System.getProperty("publicApi.javaResources")) {
            "Gradle must provide the processed library Java resources path"
        }
        assertFalse(File(path, "public-api.md").exists())
        assertTrue(documentation().startsWith("# AsyncAnimator 对外 API"))
    }
}
