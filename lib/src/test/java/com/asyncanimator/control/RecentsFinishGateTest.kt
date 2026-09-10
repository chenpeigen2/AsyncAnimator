package com.asyncanimator.control

import com.asyncanimator.anim.CustomRectFSpringAnim
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class RecentsFinishGateTest {
    private var sequenceAllows = true
    private val c = AnimationController(canFinishRecent = { sequenceAllows })
    private val rects = mutableListOf<CustomRectFSpringAnim>()
    private fun rect(id: Int = 1) = CustomRectFSpringAnim(
        CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME).also { it.animationId = id; rects.add(it) }
    @After fun cleanup() { c.destroy(); rects.forEach { it.dispose() } }

    @Test fun testLiveLaunchVetoesEvenWhenRecentsListIsEmpty() {
        val rect = rect()
        assertTrue(c.canFinishRecentsAnim(rect, 1))
        c.appLaunchAnimStartOrEnd(false, object : RemoteAnimationFactory {
            override fun createAnimation() = android.animation.AnimatorSet()
            override fun onAnimationFinished() {}
        }, null)
        assertFalse(c.canFinishRecentsAnim(rect, 1))
    }

    @Test fun testSequenceProviderVetoIsReadOnEveryQuery() {
        val rect = rect()
        sequenceAllows = false
        assertFalse(c.canFinishRecentsAnim(rect, 1))
        sequenceAllows = true
        assertTrue(c.canFinishRecentsAnim(rect, 1))
    }

    @Test fun testOnlyOneRecentsAndMatchingOrUnassignedIdMayFinish() {
        val rect = rect(7)
        c.addRecentsAnim(rect, null, null)
        assertTrue(c.canFinishRecentsAnim(rect, 7))
        assertFalse(c.canFinishRecentsAnim(rect, 8))
        assertTrue(c.canFinishRecentsAnim(rect, -1))
        rect.animationId = -1
        assertTrue(c.canFinishRecentsAnim(rect, 8))
        c.addRecentsAnim(rect(9), null, null)
        assertFalse(c.canFinishRecentsAnim(rect, -1))
    }

    @Test fun testLogicalOnlyEndCannotFinishRecents() {
        val driver = object : CustomRectFSpringAnim.Driver {
            override fun start(onActualEnd: () -> Unit) {}
            override fun cancel() {}
            override fun skipToEnd() {}
            override fun clearEndCallback() {}
        }
        val rect = CustomRectFSpringAnim(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME, driver)
            .also(rects::add)
        rect.start()
        assertTrue(c.canFinishRecentsAnim(rect, -1))
        rect.justNotifyEndCallback()
        assertFalse(c.canFinishRecentsAnim(rect, -1))
    }

    @Test fun testDefaultControllerRemainsFeatureOffNoOp() {
        val default = DefaultAnimationController()
        default.setOnAppExit(AppExitScene(true, true, true))
        default.setOnceGestureProcessing(GestureScene())
        assertFalse(default.onceGestureProcessing)
        assertTrue(default.canFinishRecentsAnim(rect(), 1))
    }
}
