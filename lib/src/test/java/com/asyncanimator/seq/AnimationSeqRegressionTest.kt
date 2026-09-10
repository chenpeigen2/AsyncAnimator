package com.asyncanimator.seq

import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
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
class AnimationSeqRegressionTest {
    private var now = 10_000L
    private lateinit var originalClock: () -> Long
    private lateinit var helper: AnimationSeqHelper

    @Before fun setUp() {
        originalClock = AnimSeqTimeStamp.clock
        AnimSeqTimeStamp.resetAllForTest()
        AnimSeqTimeStamp.clock = { now }
        helper = AnimationSeqHelper()
    }

    @After fun tearDown() {
        helper.clearFinishRecentsRunnable()
        AnimSeqTimeStamp.resetAllForTest()
        AnimSeqTimeStamp.clock = originalClock
    }

    @Test fun testFinishWindowIncludesExactly500Milliseconds() {
        assertTrue(helper.canFinishRecent)
        AnimSeqTimeStamp.updateLastRecentFinishTime()
        for (gap in listOf(0L, 499L, 500L, 501L)) {
            now = 10_000L + gap
            assertEquals("gap=$gap", gap > 500, helper.canFinishRecent)
        }
    }

    @Test fun testGestureWindowIncludesExactly300Milliseconds() {
        assertTrue(helper.canInterceptGesture)
        AnimSeqTimeStamp.updateLastStartAppTime()
        for (gap in listOf(0L, 299L, 300L, 301L)) {
            now = 10_000L + gap
            assertEquals("gap=$gap", gap > 300, helper.canInterceptGesture)
        }
    }

    @Test fun testSameAndEqualControllersKeepSequenceUntilControllerChanges() {
        data class Controller(val id: Int)
        val first = Controller(1)
        helper.updateNextFinishSeqIdIfNeed(first)
        val id = helper.getNextFinishSeqId(first)
        assertTrue(id > 0)
        repeat(3) { helper.updateNextFinishSeqIdIfNeed(first) }
        assertEquals(id, helper.getNextFinishSeqId(first))
        helper.updateNextFinishSeqIdIfNeed(Controller(1))
        assertEquals(id, helper.getNextFinishSeqId(first))
        val second = Controller(2)
        helper.updateNextFinishSeqIdIfNeed(second)
        assertEquals(id + 1, helper.getNextFinishSeqId(second))
        assertEquals(0L, helper.getNextFinishSeqId(first))
    }

    @Test fun testEveryTimestampResetLeavesOtherThreeUntouched() {
        val updates = listOf(AnimSeqTimeStamp::updateLastStartAppTime,
            AnimSeqTimeStamp::updateLastRecentFinishTime, AnimSeqTimeStamp::updateLastRecentStartTime,
            AnimSeqTimeStamp::updateLastLaunchTaskTime)
        val resets = listOf(AnimSeqTimeStamp::resetLastStartAppTime,
            AnimSeqTimeStamp::resetLastRecentFinishTime, AnimSeqTimeStamp::resetLastRecentStartTime,
            AnimSeqTimeStamp::resetLastLaunchTaskTime)
        val gaps = listOf({ AnimSeqTimeStamp.timeGapToLastStartAppTime },
            { AnimSeqTimeStamp.timeGapToLastRecentFinishTime }, { AnimSeqTimeStamp.timeGapToLastRecentStartTime },
            { AnimSeqTimeStamp.timeGapToLastLaunchTaskTime })
        resets.forEachIndexed { resetIndex, reset ->
            updates.forEachIndexed { index, update -> now = 10_000L + index; update() }
            now = 11_000L
            reset()
            gaps.forEachIndexed { index, gap ->
                assertEquals("reset=$resetIndex field=$index",
                    if (index == resetIndex) Long.MAX_VALUE else 1_000L - index, gap())
            }
        }
    }

    @Test fun testOnlyLatestDelayedRequestRunsAndClearCancelsIt() {
        AnimSeqTimeStamp.updateLastRecentFinishTime()
        val calls = mutableListOf<Int>()
        repeat(5) { index -> assertTrue(helper.delayFinishRecents { calls.add(index) }) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(listOf(4), calls)
        helper.delayFinishRecents { calls.add(5) }
        helper.clearFinishRecentsRunnable()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(listOf(4), calls)
    }

    @Test fun testDelayedCallbackCanScheduleItsSuccessor() {
        AnimSeqTimeStamp.updateLastRecentFinishTime()
        val calls = mutableListOf<String>()
        helper.delayFinishRecents {
            calls.add("first")
            AnimSeqTimeStamp.updateLastRecentFinishTime()
            helper.delayFinishRecents { calls.add("second") }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(listOf("first"), calls)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(listOf("first", "second"), calls)
    }

    @Test fun testNullDelayedRequestReplacesOldActionWithoutDeliveringIt() {
        AnimSeqTimeStamp.updateLastRecentFinishTime()
        helper.delayFinishRecents { fail("replaced by null") }
        assertTrue(helper.delayFinishRecents(null))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        var calls = 0
        helper.delayFinishRecents { calls++ }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(1, calls)
    }

    @Test fun testDelayedExceptionConsumesOldActionButPreservesReentrantSuccessor() {
        val failure = IllegalStateException("delayed action")
        val calls = mutableListOf<String>()
        AnimSeqTimeStamp.updateLastRecentFinishTime()
        helper.delayFinishRecents {
            calls.add("first")
            helper.delayFinishRecents { calls.add("second") }
            throw failure
        }
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        })
        assertEquals(0, com.asyncanimator.core.Trace.depth)
        assertEquals(listOf("first"), calls)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(listOf("first", "second"), calls)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(2, calls.size)
    }

    @Test fun testImmediateExceptionAlsoSupersedesPreviouslyQueuedRequest() {
        AnimSeqTimeStamp.updateLastRecentFinishTime()
        helper.delayFinishRecents { fail("old delayed request") }
        now += 501
        val failure = AssertionError("immediate action")
        assertSame(failure, assertThrows(AssertionError::class.java) {
            helper.delayFinishRecents { throw failure }
        })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        var calls = 0
        assertFalse(helper.delayFinishRecents { calls++ })
        assertEquals(1, calls)
    }

    @Test fun testHelpersOwnIndependentQueuesAndSequenceCounters() {
        val second = AnimationSeqHelper()
        try {
            helper.updateNextFinishSeqIdIfNeed(null)
            assertEquals(1L, helper.getNextFinishSeqId(null))
            assertEquals(0L, second.getNextFinishSeqId(null))
            second.updateNextFinishSeqIdIfNeed(null)
            assertEquals(1L, second.getNextFinishSeqId(null))
            val calls = mutableListOf<String>()
            AnimSeqTimeStamp.updateLastRecentFinishTime()
            helper.delayFinishRecents { calls.add("first") }
            second.delayFinishRecents { calls.add("second") }
            helper.clearFinishRecentsRunnable()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals(listOf("second"), calls)
        } finally { second.clearFinishRecentsRunnable() }
    }

    @Test fun testResetInterceptStatePreservesFinishWindowAndQueuedFinish() {
        AnimSeqTimeStamp.updateLastStartAppTime()
        AnimSeqTimeStamp.updateLastRecentFinishTime()
        var calls = 0
        helper.delayFinishRecents { calls++ }
        helper.resetInterceptState()
        assertTrue(helper.canInterceptGesture)
        assertFalse(helper.canFinishRecent)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(1, calls)
    }

}
