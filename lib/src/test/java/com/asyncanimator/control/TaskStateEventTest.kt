package com.asyncanimator.control

import android.animation.AnimatorSet
import android.os.Looper
import com.asyncanimator.anim.CustomRectFSpringAnim
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class TaskStateEventTest {
    private val c = AnimationController()
    @After fun cleanup() { c.destroy() }

    @Test fun testEveryEventCompletesOnlyItsScenarioAndCancelsTimer() {
        for (type in TaskStateChangeTimeOutListener.Type.entries) {
            c.reset()
            when (type) {
                TaskStateChangeTimeOutListener.Type.ON_LAND_SCAPE_SCENE_EXIT -> {
                    c.setOnAppExit(AppExitScene(true, true, true))
                    c.setBetweenAppExitTransitionEndAndFinish(true)
                }
                TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH ->
                    c.registerTransitionFinishTimeOutListener(1500)
                TaskStateChangeTimeOutListener.Type.ON_APP_TO_OVERVIEW_CONTINUATION ->
                    c.setAppToOverviewContinuationState(true)
            }
            var calls = 0
            assertTrue(c.delayStartActivityIfNeed(null, null, { true }) { calls++ })
            val other = TaskStateChangeTimeOutListener.Type.entries.first { it != type }
            c.dispatchTaskStateChange(other)
            assertEquals(0, calls)
            c.dispatchTaskStateChange(type)
            c.dispatchTaskStateChange(type)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1501))
            assertEquals(1, calls)
            assertNull(c.specialSceneExitTimeOutListener)
            assertNull(c.transitionFinishTimeOutListener)
            assertNull(c.overviewContinuationTimeOutListener)
        }
    }

    @Test fun testDisposedScenarioAndFeatureOffIgnoreEvents() {
        c.registerTransitionFinishTimeOutListener(1500)
        var calls = 0
        assertTrue(c.delayStartActivityIfNeed(null, null, { true }) { calls++ })
        checkNotNull(c.transitionFinishTimeOutListener).dispose()
        c.dispatchTaskStateChange(TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH)
        DefaultAnimationController().dispatchTaskStateChange(TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1501))
        assertEquals(0, calls)
    }

    @Test fun testDemoSequenceReachesWaitingAndReverseThroughPublicEvents() {
        val factory = object : RemoteAnimationFactory {
            override fun createAnimation() = AnimatorSet()
            override fun onAnimationFinished() {}
        }
        val states = mutableListOf<AnimationState>()
        c.addOnAnimStateChangeListener { _, next, _ -> states.add(next) }
        c.appLaunchAnimStartOrEnd(false, factory, null)
        c.setOnceGestureProcessing(GestureScene())
        c.appLaunchAnimStartOrEnd(true, factory, null)
        val rect = CustomRectFSpringAnim(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME)
        c.addRecentsAnim(rect, null, null)
        c.revertRecentsAnimation(rect)
        c.setOnceGestureProcessing(null)
        assertEquals(listOf(AnimationState.OPEN, AnimationState.WAITING,
            AnimationState.CLOSE, AnimationState.REVERSE_OPEN), states)
        assertFalse(c.onceGestureProcessing)
    }
}
