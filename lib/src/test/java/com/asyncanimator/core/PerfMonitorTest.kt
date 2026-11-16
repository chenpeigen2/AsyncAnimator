package com.asyncanimator.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PerfMonitorTest {

    @After fun cleanup() {
        PerfMonitor.setEnabled(false)
        PerfMonitor.reset()
    }

    @Test fun testDisabledRecordingIsNoOpAndKeepsChannelsEmpty() {
        PerfMonitor.setEnabled(false)
        repeat(10) { PerfMonitor.recordFrame("disabled", it * 1_000_000L) }
        assertTrue(PerfMonitor.snapshot().isEmpty())
        assertFalse(PerfMonitor.isEnabled())
    }

    @Test fun testRecordsGapAverageMaxAndLastPerChannel() {
        PerfMonitor.setEnabled(true)
        for (nanos in longArrayOf(0L, 16_000_000L, 50_000_000L)) {
            PerfMonitor.recordFrame("frames", nanos)
        }
        val stats = PerfMonitor.snapshot().getValue("frames")
        assertEquals(3L, stats.count)
        // 相邻两帧间隔分别为 16ms 与 34ms，平均间隔为 25ms
        assertEquals(25_000_000L, stats.avgGapNanos)
        assertEquals(34_000_000L, stats.maxGapNanos)
        assertEquals(50_000_000L, stats.lastNanos)
    }

    @Test fun testChannelsAreIndependentAndSnapshotIsReadOnlyView() {
        PerfMonitor.setEnabled(true)
        PerfMonitor.recordFrame("a", 0L)
        PerfMonitor.recordFrame("b", 0L)
        PerfMonitor.recordFrame("a", 20_000_000L)
        val snapshot = PerfMonitor.snapshot()
        assertEquals(setOf("a", "b"), snapshot.keys)
        assertEquals(2L, snapshot.getValue("a").count)
        assertEquals(1L, snapshot.getValue("b").count)
        assertEquals(0L, snapshot.getValue("b").avgGapNanos) // 只有一帧，平均间隔为 0
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot as MutableMap<String, PerfMonitor.FrameStatsSnapshot>)["x"] =
                PerfMonitor.FrameStatsSnapshot(0L, 0L, 0L, -1L)
        }
    }

    @Test fun testResetClearsChannelsButKeepsSwitchState() {
        PerfMonitor.setEnabled(true)
        PerfMonitor.recordFrame("frames", 0L)
        PerfMonitor.reset()
        assertTrue(PerfMonitor.snapshot().isEmpty())
        PerfMonitor.recordFrame("frames", 16_000_000L)
        assertEquals(1L, PerfMonitor.snapshot().getValue("frames").count)
        assertTrue(PerfMonitor.isEnabled())
    }

    @Test fun testTogglingMidStreamOnlyCountsEnabledFrames() {
        PerfMonitor.setEnabled(true)
        PerfMonitor.recordFrame("frames", 0L)
        PerfMonitor.setEnabled(false)
        PerfMonitor.recordFrame("frames", 1_000_000L)
        PerfMonitor.setEnabled(true)
        PerfMonitor.recordFrame("frames", 17_000_000L)
        val stats = PerfMonitor.snapshot().getValue("frames")
        assertEquals(2L, stats.count)
        assertEquals(17_000_000L, stats.avgGapNanos)
    }

    @Test fun testDumpEmitsOneLinePerChannelWithoutMutatingStats() {
        PerfMonitor.setEnabled(true)
        PerfMonitor.recordFrame("frames", 0L)
        PerfMonitor.recordFrame("frames", 32_000_000L)
        val output = java.io.ByteArrayOutputStream()
        val originalErr = System.err
        System.setErr(java.io.PrintStream(output, true, Charsets.UTF_8))
        try {
            PerfMonitor.dump()
        } finally {
            System.setErr(originalErr)
        }
        val text = output.toString(Charsets.UTF_8)
        assertTrue(text.contains("frames frames=2"))
        assertTrue(text.contains("avgGapMs=32.0"))
        assertEquals(2L, PerfMonitor.snapshot().getValue("frames").count)
    }

    /**
     * 验证 AnimationHandler 的帧回调钩子只在 PerfMonitor 打开时写入统计：
     * 关闭状态下不产生任何通道数据，打开后每一帧都记入 ANIMATION_HANDLER 通道。
     */
    @Test fun testAnimationHandlerHookRecordsOnlyWhileEnabled() {
        val source = object : TickScheduler {
            override var frameTimeNanos = 0L
            override var frameCount = 0L
            private var pending: TickScheduler.FrameCallback? = null
            override fun postFrameCallback(callback: TickScheduler.FrameCallback?) { pending = callback }
            override fun removeFrameCallback(callback: TickScheduler.FrameCallback?) { pending = null }
            override fun start() {}
            override fun stop() {}
            fun doFrame(timeNanos: Long) { pending?.doFrame(timeNanos) }
        }
        val handler = AnimationHandler(source)
        val callback = AnimationHandler.AnimationFrameCallback { true }
        PerfMonitor.setEnabled(false)
        handler.addAnimationFrameCallback(callback)
        source.doFrame(0L)
        assertFalse(PerfMonitor.snapshot().containsKey(PerfMonitor.Channels.ANIMATION_HANDLER))
        PerfMonitor.setEnabled(true)
        source.doFrame(16_000_000L)
        val stats = PerfMonitor.snapshot().getValue(PerfMonitor.Channels.ANIMATION_HANDLER)
        assertEquals(1L, stats.count)
        handler.removeCallback(callback)
    }
}
