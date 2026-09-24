package com.asyncanimator.seq

import android.os.Bundle
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 破环后的能力注入回归：默认视为支持中断，注入关闭时不消耗序号且时间窗口放行。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class AnimationSeqHelperCapabilityTest {
    private lateinit var originalClock: () -> Long

    @Before
    fun setup() {
        originalClock = AnimSeqTimeStamp.clock
        AnimSeqTimeStamp.resetAllForTest()
    }

    @After
    fun cleanup() {
        AnimSeqTimeStamp.clock = originalClock
        AnimSeqTimeStamp.resetAllForTest()
    }

    @Test
    fun testNoArgHelperTreatsInterruptionAsSupported() {
        val helper = AnimationSeqHelper()
        assertTrue(helper.canFinishRecent)
        assertTrue(helper.canInterceptGesture)
        val bundle = Bundle()
        helper.addSeqId(bundle)
        assertTrue(bundle.containsKey("interrupt.transition.startActivity.seqId"))
    }

    @Test
    fun testInjectedUnsupportedSkipsSeqIdAndAllowsWindows() {
        val helper = AnimationSeqHelper(interruptionSupported = { false })
        assertTrue(helper.canFinishRecent)
        assertTrue(helper.canInterceptGesture)
        val bundle = Bundle()
        helper.addSeqId(bundle)
        assertFalse(bundle.containsKey("interrupt.transition.startActivity.seqId"))
    }

    @Test
    fun testInjectedUnsupportedSkipsNextFinishSeqId() {
        val helper = AnimationSeqHelper(interruptionSupported = { false })
        helper.updateNextFinishSeqIdIfNeed(Any())
        assertEquals(0L, helper.getNextFinishSeqId(Any()))
    }
}
