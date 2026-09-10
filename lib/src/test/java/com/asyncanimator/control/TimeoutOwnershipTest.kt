package com.asyncanimator.control

import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class TimeoutOwnershipTest {
    private val controllers = mutableListOf<AnimationController>()
    private fun controller() = AnimationController().also(controllers::add)
    @After fun destroy() { controllers.forEach { it.destroy() } }

    @Test fun testExplicitDisposeRemovesAllThreeOwnerSlotsAndPendingActions() {
        for (scenario in 0..2) {
            val c = controller()
            when (scenario) {
                0 -> { c.setOnAppExit(AppExitScene(true, true, true)); c.setBetweenAppExitTransitionEndAndFinish(true); c.registerSpecialSceneExitTimeOutListener(100) }
                1 -> c.registerTransitionFinishTimeOutListener(100)
                2 -> c.setAppToOverviewContinuationState(true)
            }
            val listener = when (scenario) {
                0 -> c.specialSceneExitTimeOutListener
                1 -> c.transitionFinishTimeOutListener
                else -> c.overviewContinuationTimeOutListener
            } ?: error("Expected timeout listener")
            var calls = 0
            c.setBetweenTransitionEndAndFinish(true)
            assertTrue(c.delayStartActivityIfNeed(null, null, { true }) { calls++ })
            listener.dispose(); listener.dispose()
            assertNull(c.specialSceneExitTimeOutListener)
            assertNull(c.transitionFinishTimeOutListener)
            assertNull(c.overviewContinuationTimeOutListener)
            assertFalse(c.isStartActivityBetweenTransitionEndAndFinish)
            assertFalse(c.delayStartActivityIfNeed(null, null, { true }) { calls++ })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertEquals(0, calls)
        }
    }

    @Test fun testOldDisposalCannotUnregisterReplacementFromReentrantAction() {
        val c = controller()
        c.registerTransitionFinishTimeOutListener(100)
        val old = checkNotNull(c.transitionFinishTimeOutListener)
        var calls = 0
        c.delayStartActivityIfNeed(null, null, { true }) {
            calls++
            c.registerTransitionFinishTimeOutListener(200)
            c.delayStartActivityIfNeed(null, null, { true }) { calls++ }
        }
        old.onTimeOut(TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH, 0)
        val replacement = c.transitionFinishTimeOutListener
        assertNotNull(replacement)
        old.dispose()
        assertSame(replacement, c.transitionFinishTimeOutListener)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals(2, calls)
        assertNull(c.transitionFinishTimeOutListener)
    }
    @Test fun testDisposedHandleReleasesActionsAndDoesNotBindAccessGuardToController() {
        val c = controller()
        c.registerTransitionFinishTimeOutListener(100)
        val timer = checkNotNull(c.transitionFinishTimeOutListener)
        timer.dispose()
        for (name in listOf("pendingAction", "disposalCallback")) {
            val field = timer.javaClass.getDeclaredField(name).apply { isAccessible = true }
            assertNull((field.get(timer) as java.util.concurrent.atomic.AtomicReference<*>).get())
        }
        val guard = timer.javaClass.getDeclaredField("checkAccess").apply { isAccessible = true }
            .get(timer) as kotlin.jvm.internal.CallableReference
        assertSame(kotlin.jvm.internal.CallableReference.NO_RECEIVER, guard.boundReceiver)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(101))
        assertNull(c.transitionFinishTimeOutListener)
    }

}
