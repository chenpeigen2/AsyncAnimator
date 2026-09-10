package com.asyncanimator.seq

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class AnimationSeqFeatureGateTest {
    private val originalClock = AnimSeqTimeStamp.clock
    private var now = 1000L
    private var surface = true
    private var interruption = true
    private val helper = AnimationSeqHelper({ surface }, { interruption })
    @Before fun setup() {
        AnimSeqTimeStamp.resetAllForTest()
        AnimSeqTimeStamp.clock = { now }
        AnimSeqTimeStamp.updateLastRecentFinishTime()
        AnimSeqTimeStamp.updateLastStartAppTime()
    }
    @After fun cleanup() {
        helper.clearFinishRecentsRunnable()
        AnimSeqTimeStamp.resetAllForTest()
        AnimSeqTimeStamp.clock = originalClock
    }

    @Test fun testBothFeaturesRequiredToBlockInsideEachWindow() {
        for (mask in 0..3) {
            surface = mask and 1 != 0
            interruption = mask and 2 != 0
            assertEquals(mask != 3, helper.canFinishRecent)
            assertEquals(mask != 3, helper.canInterceptGesture)
        }
    }

    @Test fun testEnabledFeaturesPreserveInclusive300And500msBoundaries() {
        now = 1300
        assertFalse(helper.canInterceptGesture)
        now++
        assertTrue(helper.canInterceptGesture)
        now = 1500
        assertFalse(helper.canFinishRecent)
        now++
        assertTrue(helper.canFinishRecent)
    }

    @Test fun testImmediateSuccessorAfterFeatureDisableReplacesQueuedFinish() {
        val calls = mutableListOf<String>()
        assertTrue(helper.delayFinishRecents { calls.add("old") })
        interruption = false
        assertFalse(helper.delayFinishRecents { calls.add("new") })
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(501))
        assertEquals(listOf("new"), calls)
    }

    @Test fun testDisabledFeatureDoesNotQueueFinish() {
        surface = false
        var calls = 0
        assertFalse(helper.delayFinishRecents { calls++ })
        assertEquals(1, calls)
    }

    @Test fun testDisabledInterruptionLeavesBundleAndSequenceUnchanged() {
        val key = "interrupt.transition.startActivity.seqId"
        val bundle = android.os.Bundle().apply { putLong(key, 42L) }
        interruption = false
        helper.addSeqId(bundle)
        assertEquals(42L, bundle.getLong(key))
        val empty = android.os.Bundle()
        helper.addSeqId(empty)
        assertFalse(empty.containsKey(key))
        interruption = true
        helper.addSeqId(null)
        helper.addSeqId(empty)
        assertEquals(1L, empty.getLong(key))
    }

    @Test fun testDisabledInterruptionDoesNotCreateOrReplaceControllerPair() {
        val first = Any()
        val second = Any()
        interruption = false
        helper.updateNextFinishSeqIdIfNeed(first)
        assertEquals(0L, helper.getNextFinishSeqId(first))
        interruption = true
        helper.updateNextFinishSeqIdIfNeed(first)
        assertEquals(1L, helper.getNextFinishSeqId(first))
        interruption = false
        helper.updateNextFinishSeqIdIfNeed(second)
        // OEM does not gate reads or clear an existing pair when mutation is disabled.
        assertEquals(1L, helper.getNextFinishSeqId(first))
        assertEquals(0L, helper.getNextFinishSeqId(second))
        interruption = true
        helper.updateNextFinishSeqIdIfNeed(second)
        assertEquals(2L, helper.getNextFinishSeqId(second))
    }

    @Test fun testSequenceWritesUseInterruptionOnlyAndShareOneCounter() {
        val standalone = AnimationSeqHelper(
            startingSurfaceSupported = { error("Surface gate belongs to timing windows only") },
            interruptionSupported = { true }
        )
        data class Controller(val id: Int)
        standalone.updateNextFinishSeqIdIfNeed(Controller(1))
        assertEquals(1L, standalone.getNextFinishSeqId(Controller(1)))
        val bundle = android.os.Bundle()
        standalone.addSeqId(bundle)
        assertEquals(2L, bundle.getLong("interrupt.transition.startActivity.seqId"))
        standalone.updateNextFinishSeqIdIfNeed(Controller(1))
        assertEquals(1L, standalone.getNextFinishSeqId(Controller(1)))
        standalone.updateNextFinishSeqIdIfNeed(Controller(2))
        assertEquals(3L, standalone.getNextFinishSeqId(Controller(2)))
    }

    @Test fun testMonotonicClockCrossingDeadlineBetweenReadsQueuesZeroDelay() {
        // The feature/window check reads 1499; delay calculation then reads 1501.
        now = 1499L
        AnimSeqTimeStamp.clock = { now.also { now += 2L } }
        var calls = 0
        assertTrue(helper.delayFinishRecents { calls++ })
        assertEquals(0, calls)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, calls)
    }
}
