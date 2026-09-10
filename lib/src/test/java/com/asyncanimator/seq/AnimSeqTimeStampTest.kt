package com.asyncanimator.seq

import com.asyncanimator.core.LogUtils
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AnimSeqTimeStampTest {
    private var now = 100L
    private lateinit var originalClock: () -> Long
    private val updates = listOf(AnimSeqTimeStamp::updateLastStartAppTime,
        AnimSeqTimeStamp::updateLastRecentFinishTime, AnimSeqTimeStamp::updateLastRecentStartTime,
        AnimSeqTimeStamp::updateLastLaunchTaskTime)
    private val resets = listOf(AnimSeqTimeStamp::resetLastStartAppTime,
        AnimSeqTimeStamp::resetLastRecentFinishTime, AnimSeqTimeStamp::resetLastRecentStartTime,
        AnimSeqTimeStamp::resetLastLaunchTaskTime)
    private val gaps = listOf({ AnimSeqTimeStamp.timeGapToLastStartAppTime },
        { AnimSeqTimeStamp.timeGapToLastRecentFinishTime }, { AnimSeqTimeStamp.timeGapToLastRecentStartTime },
        { AnimSeqTimeStamp.timeGapToLastLaunchTaskTime })

    @Before fun setup() {
        originalClock = AnimSeqTimeStamp.clock
        AnimSeqTimeStamp.resetAllForTest()
        AnimSeqTimeStamp.clock = { now }
    }

    @After fun cleanup() {
        AnimSeqTimeStamp.resetAllForTest()
        AnimSeqTimeStamp.clock = originalClock
    }

    @Test fun testRecordedZeroIsDifferentFromAnUnsetTimestampForAllFourFields() {
        assertEquals(List(4) { Long.MAX_VALUE }, gaps.map { it() })
        now = 0L
        updates.forEach { it() }
        assertEquals(List(4) { 0L }, gaps.map { it() })
        now = 10L
        assertEquals(List(4) { 10L }, gaps.map { it() })
        resets.forEach { it() }
        assertEquals(List(4) { Long.MAX_VALUE }, gaps.map { it() })
    }

    @Test fun testEventsAtClockOriginStillEnforceBothTimingWindows() {
        now = 0L
        val helper = AnimationSeqHelper()
        AnimSeqTimeStamp.updateLastStartAppTime()
        AnimSeqTimeStamp.updateLastRecentFinishTime()
        assertFalse(helper.canFinishRecent)
        assertFalse(helper.canInterceptGesture)
        now = 300L
        assertFalse(helper.canInterceptGesture)
        now = 301L
        assertTrue(helper.canInterceptGesture)
        now = 500L
        assertFalse(helper.canFinishRecent)
        now = 501L
        assertTrue(helper.canFinishRecent)
    }

    @Test fun testUnsetAndResetQueriesDoNotCallInjectedClock() {
        AnimSeqTimeStamp.clock = { error("An unset timestamp needs no clock sample") }
        assertEquals(List(4) { Long.MAX_VALUE }, gaps.map { it() })
        AnimSeqTimeStamp.clock = { now }
        updates.forEach { it() }
        resets.forEach { it() }
        AnimSeqTimeStamp.clock = { error("Reset must remove the recorded value") }
        assertEquals(List(4) { Long.MAX_VALUE }, gaps.map { it() })
    }

    @Test fun testClockFailureDoesNotErasePreviousRecordedValue() {
        updates.forEach { it() }
        val failure = IllegalStateException("clock failed")
        AnimSeqTimeStamp.clock = { throw failure }
        updates.forEach { update ->
            assertSame(failure, assertThrows(IllegalStateException::class.java) { update() })
        }
        AnimSeqTimeStamp.clock = { 150L }
        assertEquals(List(4) { 50L }, gaps.map { it() })
    }

    @Test fun testConcurrentFieldUpdatesAndResetsPublishToReader() {
        // One immutable clock domain; no claim of a four-field atomic reader snapshot.
        AnimSeqTimeStamp.clock = { 100L }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val start = CountDownLatch(1)
            val writes = updates.map { update -> executor.submit { start.await(); update() } }
            start.countDown()
            writes.forEach { it.get(3, TimeUnit.SECONDS) }
            AnimSeqTimeStamp.clock = { 200L }
            assertEquals(List(4) { 100L }, gaps.map { it() })
            val clears = resets.map { reset -> executor.submit { reset() } }
            clears.forEach { it.get(3, TimeUnit.SECONDS) }
            assertEquals(List(4) { Long.MAX_VALUE }, gaps.map { it() })
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun testTimestampDiagnosticsCoverUpdateGapResetAndRespectOff() {
        val level = when {
            LogUtils.isAlwayson() -> LogUtils.ALWAYS
            LogUtils.isLogOpen() -> LogUtils.INFO
            else -> LogUtils.OFF
        }
        val originalErr = System.err
        val output = ByteArrayOutputStream()
        val stream = PrintStream(output, true, "UTF-8")
        try {
            System.setErr(stream)
            LogUtils.setLogLevel(LogUtils.INFO)
            updates.forEach { it() }
            gaps.forEach { it() }
            resets.forEach { it() }
            val lines = output.toString("UTF-8").lineSequence().filter { it.isNotBlank() }.toList()
            assertEquals(12, lines.size)
            for (name in listOf("startApp", "recentFinish", "recentStart", "launchTask")) {
                assertTrue(lines.any { it.contains("$name timestampMs=100") })
                assertTrue(lines.any { it.contains("$name gapMs=0") })
                assertTrue(lines.any { it.contains("$name timestampMs=unset") })
            }
            output.reset()
            LogUtils.setLogLevel(LogUtils.OFF)
            updates.forEach { it() }; gaps.forEach { it() }; resets.forEach { it() }
            assertEquals("", output.toString("UTF-8"))
        } finally {
            System.setErr(originalErr)
            LogUtils.setLogLevel(level)
            stream.close()
        }
    }
    @Test fun testRepeatedConcurrentWritesAndReadsStayInSingleFieldValueDomain() {
        val previousLevel = when {
            LogUtils.isAlwayson() -> LogUtils.ALWAYS
            LogUtils.isLogOpen() -> LogUtils.INFO
            else -> LogUtils.OFF
        }
        LogUtils.setLogLevel(LogUtils.OFF)
        // Fixed immutable clock domain: each field is either unset or recorded at this time.
        // Barriers bound each round, not the ordering of the writer and reader inside it.
        AnimSeqTimeStamp.clock = { 100L }
        val pool = Executors.newFixedThreadPool(2)
        val barrier = java.util.concurrent.CyclicBarrier(2)
        try {
            val writer = pool.submit {
                repeat(1000) { round ->
                    barrier.await(5, TimeUnit.SECONDS)
                    (if (round % 2 == 0) updates else resets).forEach { it() }
                    barrier.await(5, TimeUnit.SECONDS)
                }
            }
            val reader = pool.submit<Int> {
                var reads = 0
                repeat(1000) {
                    barrier.await(5, TimeUnit.SECONDS)
                    for (gap in gaps) {
                        val value = gap()
                        assertTrue("Unexpected single-field gap: $value",
                            value == 0L || value == Long.MAX_VALUE)
                        reads++
                    }
                    barrier.await(5, TimeUnit.SECONDS)
                }
                reads
            }
            writer.get(15, TimeUnit.SECONDS)
            assertEquals(4000, reader.get(15, TimeUnit.SECONDS).toInt())
            assertEquals(List(4) { Long.MAX_VALUE }, gaps.map { it() })
        } finally {
            pool.shutdownNow()
            try { assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
            finally { LogUtils.setLogLevel(previousLevel) }
        }
    }

    @Test fun testEachUpdateAndRecordedGapSamplesClockExactlyOnce() {
        var samples = 0
        AnimSeqTimeStamp.clock = { samples++; 250L }
        updates.forEachIndexed { index, update ->
            val before = samples
            update()
            assertEquals(before + 1, samples)
            assertEquals(0L, gaps[index]())
            assertEquals(before + 2, samples)
            resets[index]()
            assertEquals(Long.MAX_VALUE, gaps[index]())
            assertEquals(before + 2, samples)
        }
    }

    @Test fun testRepeatedUpdateReplacesOnlyItsOwnLastEvent() {
        updates.forEach { it() }
        now = 170L
        updates.forEachIndexed { index, update ->
            update()
            assertEquals(List(4) { if (it <= index) 0L else 70L }, gaps.map { it() })
        }
    }

    @Test fun testLargeMonotonicTimestampsRemainLongPrecisionWithoutMillisTruncation() {
        now = Long.MAX_VALUE - 1000
        updates.forEach { it() }
        now += 999
        assertEquals(List(4) { 999L }, gaps.map { it() })
    }

}
