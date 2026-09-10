package com.asyncanimator.control

import com.asyncanimator.anim.CustomRectFSpringAnim
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class ControllerThreadContractTest {
    private val controllers = mutableListOf<AnimationController>()
    private fun controller() = AnimationController(canFinishRecent = { true }).also(controllers::add)
    @After fun cleanup() { controllers.forEach { it.destroy() } }
    private fun offMain(action: () -> Unit): Throwable? {
        val failure = AtomicReference<Throwable?>()
        val worker = Thread { try { action() } catch (t: Throwable) { failure.set(t) } }
        worker.start(); worker.join(5000)
        assertFalse("worker must terminate", worker.isAlive)
        return failure.get()
    }

    @Test fun testStateMutationsRejectOffMainBeforeChangingState() {
        val rect = CustomRectFSpringAnim(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME)
        val actions: List<Pair<String, (AnimationController) -> Unit>> = listOf(
            "launch" to { it.appLaunchAnimStartOrEnd(false, null, null) },
            "recents" to { it.addRecentsAnim(rect, null, null) },
            "cleanup" to { it.cleanUpRecentsAnim(); Unit },
            "reverse" to { it.revertRecentsAnimation(rect) },
            "canFinish" to { it.canFinishRecentsAnim(rect, -1); Unit },
            "forbidTouch" to { it.forbidTouch(); Unit },
            "reset" to { it.reset() }, "destroy" to { it.destroy() },
            "special" to { it.registerSpecialSceneExitTimeOutListener(1000) },
            "transition" to { it.registerTransitionFinishTimeOutListener(1000) },
            "overview" to { it.registerOverviewContinuationTimeOutListener(1000) },
            "exit" to { it.setOnAppExit(AppExitScene(true, true, true)) },
            "gesture" to { it.setOnceGestureProcessing(GestureScene()) },
            "betweenExit" to { it.setBetweenAppExitTransitionEndAndFinish(true) },
            "between" to { it.setBetweenTransitionEndAndFinish(true) },
            "continuation" to { it.setAppToOverviewContinuationState(true) },
            "delay" to { it.delayStartActivityIfNeed(null, null, null, null); Unit },
            "event" to { it.dispatchTaskStateChange(TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH) }
        )
        for ((name, action) in actions) {
            val c = controller()
            assertTrue(name, offMain { action(c) } is IllegalStateException)
            assertEquals(AnimationState.NONE, c.animState)
            assertNull(c.specialSceneExitTimeOutListener)
            assertNull(c.transitionFinishTimeOutListener)
            assertNull(c.overviewContinuationTimeOutListener)
        }
    }

    @Test fun testFinishCallbackAssignmentRequiresMain() {
        val c = controller()
        assertTrue(offMain { c.recentsAnimFinishCallback = {} } is IllegalStateException)
        assertTrue(offMain { c.appLaunchAnimFinishCallback = {} } is IllegalStateException)
        assertNull(c.recentsAnimFinishCallback)
        assertNull(c.appLaunchAnimFinishCallback)
    }

    @Test fun testBaseObserverMutationAndDispatchRequireMain() {
        val base = DefaultAnimationController()
        val listener = OnAnimStateChangeListener { _, _, _ -> fail("must not dispatch") }
        assertTrue(offMain { base.addOnAnimStateChangeListener(listener) } is IllegalStateException)
        assertTrue(offMain { base.removeOnAnimStateChangeListener(listener) } is IllegalStateException)
        assertTrue(offMain { base.onAnimStateChanged(AnimationState.NONE, AnimationState.OPEN, null) }
            is IllegalStateException)
    }

    @Test fun testOwnedTimeoutRejectsOffMainBeforeConsumingActionOrDisposal() {
        val c = controller()
        c.registerTransitionFinishTimeOutListener(1000)
        var calls = 0
        assertTrue(c.delayStartActivityIfNeed(null, null, { true }) { calls++ })
        val listener = checkNotNull(c.transitionFinishTimeOutListener)
        assertTrue(offMain { listener.onTimeOut(TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH, 0) }
            is IllegalStateException)
        assertTrue(offMain { listener.dispose() } is IllegalStateException)
        assertSame(listener, c.transitionFinishTimeOutListener)
        assertEquals(0, calls)
        c.dispatchTaskStateChange(TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH)
        assertEquals(1, calls)
    }

    @Test fun testStandaloneTimeoutKeepsAtomicCallerThreadContract() {
        val count = AtomicInteger()
        val type = TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH
        val listener = TaskStateChangeTimeOutListener(type, 1000) { count.incrementAndGet() }
        assertNull(offMain { listener.onTimeOut(type, 0); listener.dispose() })
        listener.onTimeOut(type, 0)
        assertEquals(1, count.get())
    }

    @Test fun testFeatureOffNoOpsRemainSafeOffMain() {
        val base = DefaultAnimationController()
        assertNull(offMain {
            base.setOnceGestureProcessing(GestureScene())
            base.setOnAppExit(AppExitScene(true, true, true))
            base.recentsAnimFinishCallback = { fail("no-op") }
            base.reset()
        })
        assertEquals(AnimationState.NONE, base.animState)
        assertNull(base.recentsAnimFinishCallback)
    }
}
