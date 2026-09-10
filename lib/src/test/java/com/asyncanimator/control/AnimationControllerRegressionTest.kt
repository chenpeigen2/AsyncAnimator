package com.asyncanimator.control

import android.animation.AnimatorSet
import com.asyncanimator.anim.CustomRectFSpringAnim
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AnimationControllerRegressionTest {
    private fun anim() = CustomRectFSpringAnim(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME)
    private fun factory() = object : RemoteAnimationFactory {
        override fun createAnimation() = AnimatorSet()
        override fun onAnimationFinished() = Unit
    }

    // Some retained states have no public producer in this simplified library.
    // Seed only the input state; exercise the real public transition methods below.
    private fun seed(controller: AnimationController, state: AnimationState) {
        AnimationController::class.java.getDeclaredField("animState").apply {
            isAccessible = true
            set(controller, state)
        }
    }

    private fun checkTransitions(expected: Map<AnimationState, AnimationState>,
                                 action: (AnimationController) -> Unit,
                                 otherwise: (AnimationState) -> AnimationState = { AnimationState.UNKNOWN }) {
        AnimationState.entries.forEach { initial ->
            val controller = AnimationController()
            try {
                seed(controller, initial)
                action(controller)
                assertEquals("initial=$initial", expected[initial] ?: otherwise(initial), controller.animState)
            } finally { controller.destroy() }
        }
    }

    @Test fun testAddRecentsAcrossAllTwelveStates() = checkTransitions(mapOf(
        AnimationState.NONE to AnimationState.CLOSE,
        AnimationState.OPEN to AnimationState.CLOSE,
        AnimationState.REVERSE_OPEN to AnimationState.CLOSE,
        AnimationState.WAITING to AnimationState.CLOSE,
        AnimationState.MULTI_OPEN to AnimationState.MULTI_CLOSE,
        AnimationState.MULTI_WAITING to AnimationState.MULTI_CLOSE,
        AnimationState.MULTI_REVERSE_OPEN to AnimationState.MULTI_CLOSE
    ), { it.addRecentsAnim(anim(), null, emptyArray()) })

    @Test fun testLaunchStartAcrossAllTwelveStates() = checkTransitions(mapOf(
        AnimationState.NONE to AnimationState.OPEN,
        AnimationState.CLOSE to AnimationState.MULTI_OPEN,
        AnimationState.MULTI_CLOSE to AnimationState.MULTI_OPEN
    ), { it.appLaunchAnimStartOrEnd(false, factory(), emptyArray()) })

    @Test fun testRevertAcrossAllTwelveStates() = checkTransitions(mapOf(
        AnimationState.CLOSE to AnimationState.REVERSE_OPEN,
        AnimationState.MULTI_CLOSE to AnimationState.MULTI_REVERSE_OPEN
    ), { it.revertRecentsAnimation(anim()) })

    @Test fun testLaunchEndWhileGestureProcessingAcrossAllTwelveStates() = checkTransitions(mapOf(
        AnimationState.OPEN to AnimationState.WAITING,
        AnimationState.MULTI_OPEN to AnimationState.MULTI_WAITING
    ), { controller ->
        controller.setOnceGestureProcessing(GestureScene())
        controller.appLaunchAnimStartOrEnd(true, null, emptyArray())
    }, { it })

    @Test fun testFinishWaitsForBothCollectionsAndConsumesBothCallbacks() {
        val controller = AnimationController()
        val launch = factory()
        val calls = mutableListOf<String>()
        try {
            controller.appLaunchAnimStartOrEnd(false, launch, emptyArray())
            controller.addRecentsAnim(anim(), null, emptyArray())
            controller.recentsAnimFinishCallback = { calls.add("recents") }
            controller.appLaunchAnimFinishCallback = { calls.add("launch") }
            assertFalse(controller.cleanUpRecentsAnim())
            assertTrue(calls.isEmpty())
            controller.appLaunchAnimStartOrEnd(true, launch, emptyArray())
            assertEquals(listOf("recents", "launch"), calls)
            assertEquals(AnimationState.NONE, controller.animState)
            assertNull(controller.recentsAnimFinishCallback)
            assertNull(controller.appLaunchAnimFinishCallback)
            controller.cleanUpRecentsAnim()
            assertEquals(listOf("recents", "launch"), calls)
        } finally { controller.destroy() }
    }

    @Test fun testFinishWaitsForRecentsAfterLastLaunchEnds() {
        val controller = AnimationController()
        val launch = factory()
        var calls = 0
        try {
            controller.appLaunchAnimStartOrEnd(false, launch, emptyArray())
            controller.addRecentsAnim(anim(), null, emptyArray())
            controller.recentsAnimFinishCallback = { calls++ }
            controller.appLaunchAnimFinishCallback = { calls++ }
            controller.appLaunchAnimStartOrEnd(true, launch, emptyArray())
            assertEquals(0, calls)
            assertTrue(controller.cleanUpRecentsAnim())
            assertEquals(2, calls)
        } finally { controller.destroy() }
    }

    @Test fun testLandscapeWinsOverLowerPriorityScenarios() {
        val controller = AnimationController()
        var calls = 0
        var predicateCalls = 0
        try {
            controller.setOnAppExit(AppExitScene(true, true, true)); controller.setBetweenAppExitTransitionEndAndFinish(true)
            controller.registerSpecialSceneExitTimeOutListener(1000)
            controller.registerTransitionFinishTimeOutListener(1000)
            controller.registerOverviewContinuationTimeOutListener(1000)
            assertTrue(controller.delayStartActivityIfNeed(null, null, { predicateCalls++; true }) { calls++ })
            assertEquals(0, predicateCalls)
            assertEquals(0, calls)
            controller.specialSceneExitTimeOutListener!!.onTimeOut(
                TaskStateChangeTimeOutListener.Type.ON_LAND_SCAPE_SCENE_EXIT, 1000)
            assertEquals(1, calls)
        } finally { controller.destroy() }
    }

    @Test fun testTransitionPredicateDefersUntilMatchingEvent() {
        val controller = AnimationController()
        var calls = 0
        try {
            controller.registerTransitionFinishTimeOutListener(1000)
            assertTrue(controller.delayStartActivityIfNeed(null, null, { true }) { calls++ })
            assertEquals(0, calls)
            controller.transitionFinishTimeOutListener!!.onTimeOut(
                TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH, 1000)
            assertEquals(1, calls)
        } finally { controller.destroy() }
    }

    @Test fun testOverviewDefersWhileExplicitlyRunning() {
        val controller = AnimationController()
        var calls = 0
        try {
            controller.setAppToOverviewContinuationState(true)
            assertTrue(controller.delayStartActivityIfNeed(null, null, null) { calls++ })
            controller.overviewContinuationTimeOutListener!!.onTimeOut(
                TaskStateChangeTimeOutListener.Type.ON_APP_TO_OVERVIEW_CONTINUATION, 1000)
            assertEquals(1, calls)
        } finally { controller.destroy() }
    }

    @Test fun testNonmatchingSpecialSceneDoesNotFallThroughAndClearsListeners() {
        val controller = AnimationController()
        try {
            controller.registerSpecialSceneExitTimeOutListener(1000)
            controller.registerTransitionFinishTimeOutListener(1000)
            controller.registerOverviewContinuationTimeOutListener(1000)
            assertFalse(controller.delayStartActivityIfNeed(null, null, { fail("must not fall through"); true }) {})
            assertNull(controller.specialSceneExitTimeOutListener)
            assertNull(controller.transitionFinishTimeOutListener)
            assertNull(controller.overviewContinuationTimeOutListener)
        } finally { controller.destroy() }
    }

    @Test fun testExpiredSpecialAndInactiveOverviewDoNotRetainStartAction() {
        for (special in listOf(true, false)) {
            val controller = AnimationController()
            var calls = 0
            try {
                if (special) {
                    controller.setOnAppExit(AppExitScene(true, true, true)); controller.setBetweenAppExitTransitionEndAndFinish(true)
                    controller.registerSpecialSceneExitTimeOutListener(-1)
                } else controller.registerOverviewContinuationTimeOutListener(0)
                assertFalse(controller.delayStartActivityIfNeed(null, null, null) { calls++ })
                controller.specialSceneExitTimeOutListener?.onTimeOut(
                    TaskStateChangeTimeOutListener.Type.ON_LAND_SCAPE_SCENE_EXIT, -1)
                assertEquals(0, calls)
            } finally { controller.destroy() }
        }
    }

    @Test fun testNoScenarioAndRejectedPredicateReturnFalseWithoutRunningAction() {
        val controller = AnimationController()
        try {
            assertFalse(controller.delayStartActivityIfNeed(null, null, null) { fail("caller must launch") })
            controller.registerTransitionFinishTimeOutListener(1000)
            assertFalse(controller.delayStartActivityIfNeed(null, null, { false }) { fail("caller must launch") })
            assertNull(controller.transitionFinishTimeOutListener)
        } finally { controller.destroy() }
    }

    @Test fun testSpecialDeadlineIsInclusiveButOverviewTimerAloneDoesNotDefer() {
        val special = AnimationController()
        val overview = AnimationController()
        try {
            special.setOnAppExit(AppExitScene(true, true, true)); special.setBetweenAppExitTransitionEndAndFinish(true)
            special.registerSpecialSceneExitTimeOutListener(0)
            assertTrue(special.delayStartActivityIfNeed(null, null, null) {})
            overview.registerOverviewContinuationTimeOutListener(0)
            assertFalse(overview.delayStartActivityIfNeed(null, null, null) {})
        } finally {
            special.destroy()
            overview.destroy()
        }
    }
}
