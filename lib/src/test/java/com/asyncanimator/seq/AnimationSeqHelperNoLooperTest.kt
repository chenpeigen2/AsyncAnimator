package com.asyncanimator.seq

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * 纯 JVM 环境（无 Robolectric、无主 Looper）下 [AnimationSeqHelper] 的行为。
 * 立即完成路径不依赖主线程设施，仍在调用线程同步执行；
 * 防抖延迟路径按旧 Handler 语义要求主 Looper 存在，缺失时抛出 [NullPointerException]。
 */
class AnimationSeqHelperNoLooperTest {
    private lateinit var originalClock: () -> Long

    @Before
    fun setUp() {
        originalClock = AnimSeqTimeStamp.clock
        AnimSeqTimeStamp.resetAllForTest()
        AnimSeqTimeStamp.clock = { 10_000L }
    }

    @After
    fun tearDown() {
        AnimSeqTimeStamp.resetAllForTest()
        AnimSeqTimeStamp.clock = originalClock
    }

    @Test
    fun testImmediateFinishPathRunsInlineWithoutMainLooper() {
        val helper = AnimationSeqHelper(
            startingSurfaceSupported = { true },
            interruptionSupported = { true }
        )
        var calls = 0
        val delayed = helper.delayFinishRecents { calls++ }
        assertFalse(delayed)
        assertEquals(1, calls)
    }

    @Test
    fun testDelayedFinishPathThrowsNullPointerWithoutMainLooper() {
        AnimSeqTimeStamp.updateLastRecentFinishTime()
        val helper = AnimationSeqHelper(
            startingSurfaceSupported = { true },
            interruptionSupported = { true }
        )
        assertThrows(NullPointerException::class.java) { helper.delayFinishRecents { } }
    }

    @Test
    fun testClearWithoutMainLooperIsSafeNoOp() {
        val helper = AnimationSeqHelper()
        helper.clearFinishRecentsRunnable()
    }
}
