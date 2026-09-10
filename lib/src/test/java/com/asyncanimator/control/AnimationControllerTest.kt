package com.asyncanimator.control

import com.asyncanimator.anim.CustomRectFSpringAnim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * AnimationController 单元测试。
 *
 * 验证：
 *
 *  - 12 个 AnimationState 枚举完整
 *  - updateAnimState 触发 listener
 *  - addRecentsAnim 状态转换
 *  - 3 种 listener 独立运行
 *  - delayStartActivityIfNeed 三层决策树
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36], manifest = org.robolectric.annotation.Config.NONE)
class AnimationControllerTest {

    @Test
    fun testAnimationStateEnumCount() {
        // 12 个状态：11 个基础状态 + SWIPE_UP_TO_CAPSULE / SWIPE_UP_TO_SPLIT_OR_FLOATING
        // 中额外的 SWIPE_UP_TO_CAPSULE（胶囊返回），以 AnimationState.kt 实际定义为准
        assertEquals(12, AnimationState.entries.size)
    }

    @Test
    fun testAnimationStateFlags() {
        assertFalse(AnimationState.NONE.withTaskbarAlignment)
        assertFalse(AnimationState.NONE.taskbarAlignmentToLauncher)
        assertTrue(AnimationState.CLOSE.withTaskbarAlignment)
        assertTrue(AnimationState.CLOSE.taskbarAlignmentToLauncher)
    }

    @Test
    fun testInitialState() {
        val controller = AnimationController()
        assertEquals(AnimationState.NONE, controller.animState)
        assertFalse(controller.hasRecentsAnim)
        assertFalse(controller.isOpeningAnim)
    }

    @Test
    fun testAddRecentsAnimTriggersClose() {
        val controller = AnimationController()
        val observed = AtomicReference<AnimationState>()
        controller.addOnAnimStateChangeListener { _, newS, _ -> observed.set(newS) }

        controller.addRecentsAnim(
            CustomRectFSpringAnim(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME),
            null, arrayOf())
        assertEquals(AnimationState.CLOSE, controller.animState)
        assertEquals(AnimationState.CLOSE, observed.get())
    }

    @Test
    fun testReset() {
        val controller = AnimationController()
        controller.addRecentsAnim(
            CustomRectFSpringAnim(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME),
            null, arrayOf())
        controller.reset()
        assertEquals(AnimationState.NONE, controller.animState)
    }

    @Test
    fun testIsOpeningAnim() {
        val controller = AnimationController()
        assertFalse(controller.isOpeningAnim)
    }

    @Test
    fun testThreeTimeoutListenersIndependent() {
        val controller = AnimationController()
        // 三个 listener 注册
        controller.registerSpecialSceneExitTimeOutListener(1500L)
        controller.registerTransitionFinishTimeOutListener(1500L)
        controller.registerOverviewContinuationTimeOutListener(100L)
        // 三个互不干扰
        assertNotNull(controller.specialSceneExitTimeOutListener)
        assertNotNull(controller.transitionFinishTimeOutListener)
        assertNotNull(controller.overviewContinuationTimeOutListener)
    }

    @Test
    fun testNullListenerEqualsNoScenario() {
        // 没注册 listener 时为 null
        val controller = AnimationController()
        assertNull(controller.specialSceneExitTimeOutListener)
        assertNull(controller.transitionFinishTimeOutListener)
        assertNull(controller.overviewContinuationTimeOutListener)
    }

    @Test
    fun testCleanUpRecentsAnim() {
        val controller = AnimationController()
        controller.addRecentsAnim(
            CustomRectFSpringAnim(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME),
            null, arrayOf())
        assertTrue(controller.hasRecentsAnim)
        val done = controller.cleanUpRecentsAnim()
        assertFalse(controller.hasRecentsAnim)
        assertTrue(done)
    }

    @Test
    fun testReplacingTimeoutDisposesPreviousListener() {
        val controller = AnimationController()
        var calls = 0
        controller.registerTransitionFinishTimeOutListener(100)
        val old = checkNotNull(controller.transitionFinishTimeOutListener)
        controller.registerTransitionFinishTimeOutListener(200)
        controller.delayStartActivityIfNeed(null, null, { true }) { calls++ }
        old.onTimeOut(TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH, 100)
        assertEquals(0, calls)
        checkNotNull(controller.transitionFinishTimeOutListener).onTimeOut(
            TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH, 200)
        assertEquals(1, calls)
        assertNull(controller.transitionFinishTimeOutListener)
    }

    @Test
    fun testDestroyDisposesAllTimeoutsAndStateObservers() {
        val controller = AnimationController()
        var stateChanges = 0
        controller.addOnAnimStateChangeListener { _, _, _ -> stateChanges++ }
        controller.registerSpecialSceneExitTimeOutListener(100)
        controller.registerTransitionFinishTimeOutListener(100)
        controller.registerOverviewContinuationTimeOutListener(100)
        controller.destroy()
        assertNull(controller.specialSceneExitTimeOutListener)
        assertNull(controller.transitionFinishTimeOutListener)
        assertNull(controller.overviewContinuationTimeOutListener)
        assertEquals(AnimationState.NONE, controller.animState)
        controller.reset()
        assertEquals(0, stateChanges)
    }

    @Test
    fun testTimeoutActionCanRegisterAnotherRequest() {
        val controller = AnimationController()
        var calls = 0
        controller.registerTransitionFinishTimeOutListener(100)
        controller.delayStartActivityIfNeed(null, null, { true }) {
            calls++
            controller.registerTransitionFinishTimeOutListener(100)
            controller.delayStartActivityIfNeed(null, null, { true }) { calls++ }
        }
        checkNotNull(controller.transitionFinishTimeOutListener).onTimeOut(
            TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH, 100)
        assertEquals(1, calls)
        checkNotNull(controller.transitionFinishTimeOutListener).onTimeOut(
            TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH, 100)
        assertEquals(2, calls)
        assertNull(controller.transitionFinishTimeOutListener)
    }
}
