package com.asyncanimator.control

import android.animation.AnimatorSet
import android.os.Looper
import com.asyncanimator.anim.CustomRectFSpringAnim
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
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
class TouchGateTest {
    private val c = AnimationController(canFinishRecent = { true })
    private val factory = object : RemoteAnimationFactory {
        override fun createAnimation() = AnimatorSet()
        override fun onAnimationFinished() {}
    }
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun start() = c.appLaunchAnimStartOrEnd(false, factory, emptyArray())
    private fun end() = c.appLaunchAnimStartOrEnd(true, factory, emptyArray())
    @After fun cleanup() { c.destroy() }

    @Test fun testOpenWindowGateReleasesAt600msWithoutEndingAnimationState() {
        assertFalse(c.forbidTouch())
        start()
        assertTrue(c.forbidTouch())
        advance(599)
        assertTrue(c.forbidTouch())
        advance(1)
        assertFalse(c.forbidTouch())
        assertEquals(AnimationState.OPEN, c.animState)
    }

    @Test fun testNewStartReplacesOldReleaseDeadline() {
        start(); advance(300); start(); advance(300)
        assertTrue(c.forbidTouch())
        advance(299); assertTrue(c.forbidTouch())
        advance(1); assertFalse(c.forbidTouch())
    }

    @Test fun testEndResetAndDestroyReleaseAndCannotClearSuccessorGateEarly() {
        for (release in listOf<() -> Unit>({ end() }, { c.reset() }, { c.destroy() })) {
            start(); advance(300)
            release()
            assertFalse(c.forbidTouch())
            start(); advance(300)
            assertTrue(c.forbidTouch())
            advance(300); assertFalse(c.forbidTouch())
            c.reset()
        }
    }

    @Test fun testReverseOpenAndMultiWaitingRemainBlockedAfterTimerExpires() {
        val rect = CustomRectFSpringAnim(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME)
        c.addRecentsAnim(rect, null, null)
        c.revertRecentsAnimation(rect)
        assertEquals(AnimationState.REVERSE_OPEN, c.animState)
        assertTrue(c.forbidTouch())
        c.reset()
        c.addRecentsAnim(rect, null, null)
        start()
        c.setOnceGestureProcessing(GestureScene())
        end()
        assertEquals(AnimationState.MULTI_WAITING, c.animState)
        advance(1000)
        assertTrue(c.forbidTouch())
    }

    @Test fun testOnlyActualPendingLaunchBlocksAndEventClearsBeforeAction() {
        c.registerTransitionFinishTimeOutListener(2000)
        assertFalse(c.forbidTouch())
        assertTrue(c.delayStartActivityIfNeed(null, null, { true }, null))
        assertFalse(c.forbidTouch())
        var calls = 0
        assertTrue(c.delayStartActivityIfNeed(null, null, { true }) {
            assertFalse(c.forbidTouch()); calls++
        })
        assertTrue(c.forbidTouch())
        c.dispatchTaskStateChange(TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH)
        assertFalse(c.forbidTouch())
        assertEquals(1, calls)
    }

    @Test fun testLiveQueryRequiresMainButDisabledBaseRemainsNoOp() {
        val failure = AtomicReference<Throwable?>()
        Thread {
            try { c.forbidTouch() } catch (t: Throwable) { failure.set(t) }
        }.apply { start(); join(5000); assertFalse(isAlive) }
        assertTrue(failure.get() is IllegalStateException)
        assertFalse(DefaultAnimationController().forbidTouch())
    }
}
