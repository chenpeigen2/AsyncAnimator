package com.asyncanimator.seq

import android.os.Bundle
import android.os.Looper
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
class DefaultAnimationSeqHelperTest {
    @Test fun testQueriesStayPermissiveAndControllerPairsNeverAllocateIds() {
        val helper = DefaultAnimationSeqHelper()
        for (controller in listOf(null, Any(), "same", "same")) {
            helper.updateNextFinishSeqIdIfNeed(controller)
            assertEquals(0L, helper.getNextFinishSeqId(controller))
            assertTrue(helper.canFinishRecent)
            assertTrue(helper.canInterceptGesture)
        }
        helper.resetInterceptState()
        helper.clearFinishRecentsRunnable()
        assertEquals(0L, helper.getNextFinishSeqId(Any()))
    }

    @Test fun testBundleContentsRemainUntouchedIncludingExistingSequence() {
        val helper = DefaultAnimationSeqHelper()
        val bundle = Bundle().apply {
            putLong("interrupt.transition.startActivity.seqId", 47L)
            putString("unrelated", "preserve")
        }
        helper.addSeqId(null)
        helper.addSeqId(bundle)
        assertEquals(setOf("interrupt.transition.startActivity.seqId", "unrelated"), bundle.keySet())
        assertEquals(47L, bundle.getLong("interrupt.transition.startActivity.seqId"))
        assertEquals("preserve", bundle.getString("unrelated"))
        val empty = Bundle()
        helper.addSeqId(empty)
        assertTrue(empty.isEmpty)
    }

    @Test fun testImmediateActionIsReentrantAndNeverScheduledAgain() {
        val helper = DefaultAnimationSeqHelper()
        val calls = mutableListOf<String>()
        assertFalse(helper.delayFinishRecents {
            calls.add("outer")
            assertFalse(helper.delayFinishRecents { calls.add("inner") })
            calls.add("after")
        })
        helper.clearFinishRecentsRunnable()
        helper.resetInterceptState()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(listOf("outer", "inner", "after"), calls)
        assertFalse(helper.delayFinishRecents(null))
    }

    @Test fun testActionExceptionPropagatesAndDoesNotPoisonNextRequest() {
        val helper = DefaultAnimationSeqHelper()
        val failure = IllegalStateException("immediate failed")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            helper.delayFinishRecents { throw failure }
        })
        var calls = 0
        assertFalse(helper.delayFinishRecents { calls++ })
        assertEquals(1, calls)
    }
}
