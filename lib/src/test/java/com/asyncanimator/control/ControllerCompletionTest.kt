package com.asyncanimator.control

import android.animation.AnimatorSet
import com.asyncanimator.anim.CustomRectFSpringAnim
import com.asyncanimator.core.LogUtils
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class ControllerCompletionTest {
    private val c = AnimationController(canFinishRecent = { true })
    private fun factory() = object : RemoteAnimationFactory {
        override fun createAnimation() = AnimatorSet()
        override fun onAnimationFinished() {}
    }
    private fun start(f: RemoteAnimationFactory) = c.appLaunchAnimStartOrEnd(false, f, emptyArray())
    private fun end(f: RemoteAnimationFactory) = c.appLaunchAnimStartOrEnd(true, f, emptyArray())
    @After fun cleanup() { c.destroy() }

    @Test fun testFinishCallbackCanStartSuccessorWithoutOldResetErasingIt() {
        val first = factory()
        val second = factory()
        val calls = mutableListOf<String>()
        start(first)
        c.recentsAnimFinishCallback = {
            calls.add("old-recents")
            assertEquals(AnimationState.NONE, c.animState)
            start(second)
            c.appLaunchAnimFinishCallback = { calls.add("new-launch") }
        }
        c.appLaunchAnimFinishCallback = { calls.add("old-launch") }
        end(first)
        assertEquals(listOf("old-recents", "old-launch"), calls)
        assertEquals(AnimationState.OPEN, c.animState)
        assertTrue(c.forbidTouch())
        assertNotNull(c.appLaunchAnimFinishCallback)
        end(second)
        assertEquals(listOf("old-recents", "old-launch", "new-launch"), calls)
        assertEquals(AnimationState.NONE, c.animState)
    }

    @Test fun testThrowingOldCallbackCannotClearSuccessorStateOrCallbacks() {
        val first = factory()
        val second = factory()
        val failure = IllegalStateException("old cycle")
        var oldLaunches = 0
        var newLaunches = 0
        start(first)
        c.recentsAnimFinishCallback = {
            start(second)
            c.appLaunchAnimFinishCallback = { newLaunches++ }
            throw failure
        }
        c.appLaunchAnimFinishCallback = { oldLaunches++ }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { end(first) })
        assertEquals(1, oldLaunches)
        assertEquals(AnimationState.OPEN, c.animState)
        assertTrue(c.forbidTouch())
        assertNotNull(c.appLaunchAnimFinishCallback)
        end(second)
        assertEquals(1, newLaunches)
        assertEquals(AnimationState.NONE, c.animState)
    }

    @Test fun testReentrantCleanupCannotReplayConsumedCompletionCallbacks() {
        val f = factory()
        var recents = 0
        var launch = 0
        start(f)
        c.recentsAnimFinishCallback = {
            recents++
            check(recents == 1) { "old callback was replayed" }
            c.cleanUpRecentsAnim()
        }
        c.appLaunchAnimFinishCallback = { launch++ }
        end(f)
        assertEquals(1, recents)
        assertEquals(1, launch)
        c.cleanUpRecentsAnim()
        assertEquals(1, launch)
    }

    @Test fun testThrowingCallbacksAreConsumedAndAllCleanupRunsBeforeErrorIsRethrown() {
        val f = factory()
        val first = IllegalStateException("recents failed")
        val second = IllegalArgumentException("launch failed")
        var launches = 0
        start(f)
        c.recentsAnimFinishCallback = { throw first }
        c.appLaunchAnimFinishCallback = { launches++; throw second }
        assertSame(first, assertThrows(IllegalStateException::class.java) { end(f) })
        assertArrayEquals(arrayOf(second), first.suppressed)
        assertEquals(1, launches)
        assertEquals(AnimationState.NONE, c.animState)
        assertFalse(c.forbidTouch())
        assertNull(c.recentsAnimFinishCallback)
        assertNull(c.appLaunchAnimFinishCallback)
        c.cleanUpRecentsAnim()
        assertEquals(1, launches)
    }

    @Test fun testNoneObserverFailureDoesNotPreventCompletionCallbacks() {
        val f = factory()
        val events = mutableListOf<String>()
        val failure = IllegalStateException("observer")
        start(f)
        c.addOnAnimStateChangeListener { _, state, _ ->
            if (state == AnimationState.NONE) { events.add("reset"); throw failure }
        }
        c.recentsAnimFinishCallback = { events.add("recents") }
        c.appLaunchAnimFinishCallback = { events.add("launch") }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { end(f) })
        assertEquals(listOf("reset", "recents", "launch"), events)
        assertEquals(AnimationState.NONE, c.animState)
        assertNull(c.recentsAnimFinishCallback)
        assertNull(c.appLaunchAnimFinishCallback)
        c.cleanUpRecentsAnim() // already idle; do not repeat NONE notification
    }

    @Test fun testIdleCleanupStillClearsPendingLaunchAndGestureStateWithoutAnotherNoneEvent() {
        var noneEvents = 0
        c.addOnAnimStateChangeListener { _, state, _ -> if (state == AnimationState.NONE) noneEvents++ }
        c.registerTransitionFinishTimeOutListener(2000)
        c.setBetweenTransitionEndAndFinish(true)
        assertTrue(c.delayStartActivityIfNeed(null, null, { true }) {})
        assertEquals(AnimationState.NONE, c.animState)
        assertTrue(c.forbidTouch())
        c.cleanUpRecentsAnim()
        assertFalse(c.forbidTouch())
        assertFalse(c.isStartActivityBetweenTransitionEndAndFinish)
        c.setOnceGestureProcessing(GestureScene())
        assertTrue(c.onceGestureProcessing)
        c.cleanUpRecentsAnim()
        assertFalse(c.onceGestureProcessing)
        assertEquals(0, noneEvents)
    }

    @Test fun testInvalidRecentsTransitionLogsOriginalStateAndStillPublishesUnknown() {
        val previousLevel = if (LogUtils.isAlwayson()) LogUtils.ALWAYS else if (LogUtils.isLogOpen()) LogUtils.INFO else LogUtils.OFF
        val previousErr = System.err
        val bytes = ByteArrayOutputStream()
        val sink = PrintStream(bytes, true, Charsets.UTF_8)
        try {
            System.setErr(sink)
            LogUtils.setLogLevel(LogUtils.INFO)
            val rect = CustomRectFSpringAnim(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME)
            c.addRecentsAnim(rect, null, null)
            assertFalse(bytes.toString(Charsets.UTF_8).contains("Error animation state"))
            c.addRecentsAnim(rect, null, null)
            assertEquals(AnimationState.UNKNOWN, c.animState)
            assertTrue(bytes.toString(Charsets.UTF_8).contains("Error animation state: CLOSE, add recents anim."))
        } finally {
            System.setErr(previousErr)
            LogUtils.setLogLevel(previousLevel)
            sink.close()
        }
    }
}
