package com.asyncanimator.control

import com.asyncanimator.anim.CustomRectFSpringAnim
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 破环后的构造注入回归：完成策略默认降级不放行，完成清理回调可注入且默认无操作。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class AnimationControllerInjectionTest {
    private val controllers = mutableListOf<AnimationController>()
    private val rects = mutableListOf<CustomRectFSpringAnim>()

    private fun controller(
        canFinishRecent: () -> Boolean = { false },
        onClearFinishRecents: () -> Unit = {}
    ) = AnimationController(canFinishRecent = canFinishRecent, onClearFinishRecents = onClearFinishRecents)
        .also(controllers::add)

    private fun rect(id: Int = 1) = CustomRectFSpringAnim(
        CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME
    ).also {
        it.animationId = id
        rects.add(it)
    }

    @After
    fun cleanup() {
        controllers.forEach { it.destroy() }
        rects.forEach { it.dispose() }
    }

    @Test
    fun testNoArgConstructorDefaultsToConservativeFinishGate() {
        val c = AnimationController()
        controllers.add(c)
        assertFalse(c.canFinishRecentsAnim(rect(), 1))
    }

    @Test
    fun testInjectedFinishGateIsReadOnEveryQuery() {
        var allowed = false
        val c = controller(canFinishRecent = { allowed })
        val rect = rect()
        assertFalse(c.canFinishRecentsAnim(rect, 1))
        allowed = true
        assertTrue(c.canFinishRecentsAnim(rect, 1))
    }

    @Test
    fun testClearFinishRecentsCallbackFiresOnLaunchStart() {
        var cleared = 0
        val c = controller(onClearFinishRecents = { cleared++ })
        c.appLaunchAnimStartOrEnd(false, object : RemoteAnimationFactory {
            override fun createAnimation() = android.animation.AnimatorSet()
            override fun onAnimationFinished() {}
        }, null)
        assertEquals(1, cleared)
    }

    @Test
    fun testDefaultClearFinishRecentsCallbackIsNoOp() {
        val c = controller()
        c.appLaunchAnimStartOrEnd(false, object : RemoteAnimationFactory {
            override fun createAnimation() = android.animation.AnimatorSet()
            override fun onAnimationFinished() {}
        }, null)
        assertEquals(AnimationState.OPEN, c.animState)
    }
}
