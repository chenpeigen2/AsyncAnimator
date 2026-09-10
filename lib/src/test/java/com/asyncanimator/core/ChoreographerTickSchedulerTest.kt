package com.asyncanimator.core

import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
// Isolate this controlled VSYNC fixture from other tests' background display receivers.
// Robolectric 4.16 retains static clock listeners and shares one next-vsync timestamp.
// Its InstrumentationConfiguration removes redundant android.view under android.; that
// old setting did NOT create a separate sandbox. The project core package is nonredundant
// and gives this fixture a distinct sandbox without changing SDK, clock or assertions.
@Config(sdk = [36], manifest = Config.NONE, instrumentedPackages = ["com.asyncanimator.core"])
@LooperMode(LooperMode.Mode.PAUSED)
class ChoreographerTickSchedulerTest {
    @Before fun pauseVsync() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(10))
    }

    @Test fun testFramesFollowVsyncSourceRatherThanFixed16Millis() {
        // These are controlled source cadences, not measurements of a physical display.
        for (interval in listOf(8L, 11L, 17L)) {
            ShadowChoreographer.setFrameDelay(Duration.ofMillis(interval))
            val scheduler = ChoreographerTickScheduler()
            val times = mutableListOf<Long>()
            val callback = TickScheduler.FrameCallback { times.add(it) }
            scheduler.postFrameCallback(callback)
            repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(interval)) }
            assertEquals("source interval=$interval", 3, times.size)
            assertEquals(listOf(interval, interval), times.zipWithNext { a, b -> (b - a) / 1_000_000 })
            assertEquals(times.last(), scheduler.frameTimeNanos)
            assertEquals(3L, scheduler.frameCount)
            scheduler.removeFrameCallback(callback)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(interval))
        }
    }

    @Test fun testRemovingLastSubscriberStopsAndNewSubscriberRestarts() {
        val scheduler = ChoreographerTickScheduler()
        var calls = 0
        val callback = TickScheduler.FrameCallback { calls++ }
        scheduler.postFrameCallback(callback)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        assertEquals(1, calls)
        scheduler.removeFrameCallback(callback)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        assertEquals(1, calls)
        val stoppedCount = scheduler.frameCount
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        assertEquals(stoppedCount, scheduler.frameCount)
        scheduler.postFrameCallback(callback)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        assertEquals(2, calls)
        scheduler.stop()
    }

    @Test fun testStopRetainsSubscriptionsAndRestartDoesNotDuplicatePulse() {
        val scheduler = ChoreographerTickScheduler()
        var calls = 0
        scheduler.postFrameCallback { calls++ }
        scheduler.stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        assertEquals(0, calls)
        scheduler.start()
        scheduler.stop()
        scheduler.start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        assertEquals(1, calls)
        scheduler.stop()
    }

    @Test fun testCallbackFailureDoesNotStopHealthySubscriberOrFollowingFrames() {
        val scheduler = ChoreographerTickScheduler()
        var failures = 0
        var healthy = 0
        lateinit var failing: TickScheduler.FrameCallback
        failing = TickScheduler.FrameCallback {
            failures++
            scheduler.removeFrameCallback(failing)
            throw IllegalStateException("expected consumer failure")
        }
        try {
            scheduler.postFrameCallback(failing)
            scheduler.postFrameCallback { healthy++ }
            repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10)) }
            assertEquals(1, failures)
            assertEquals(2, healthy)
            assertEquals(2L, scheduler.frameCount)
        } finally {
            scheduler.stop()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        }
    }

    @Test fun testSubscriberMutationsApplyToNextSnapshotNotCurrentDelivery() {
        val scheduler = ChoreographerTickScheduler()
        val calls = mutableListOf<String>()
        val removed = TickScheduler.FrameCallback { calls.add("removed") }
        val added = TickScheduler.FrameCallback { calls.add("added") }
        var mutate = true
        try {
            scheduler.postFrameCallback {
                calls.add("first")
                if (mutate) {
                    mutate = false
                    scheduler.removeFrameCallback(removed)
                    scheduler.postFrameCallback(added)
                }
            }
            scheduler.postFrameCallback(removed)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            assertEquals(listOf("first", "removed"), calls)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            assertEquals(listOf("first", "removed", "first", "added"), calls)
        } finally {
            scheduler.stop()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        }
    }

    @Test fun testReentrantStopStartDoesNotQueueAnExtraPulse() {
        val scheduler = ChoreographerTickScheduler()
        var calls = 0
        try {
            scheduler.postFrameCallback {
                calls++
                scheduler.stop()
                scheduler.start()
            }
            repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10)) }
            assertEquals(3, calls)
            assertEquals(3L, scheduler.frameCount)
        } finally {
            scheduler.stop()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        }
    }


    @Test fun testCallbackCanWaitForAnotherThreadToRegisterWithoutHoldingStateLock() {
        val scheduler = ChoreographerTickScheduler()
        val failure = AtomicReference<Throwable?>()
        var registeredWhileInCallback = false
        var worker: Thread? = null
        var successorCalls = 0
        lateinit var successor: TickScheduler.FrameCallback
        successor = TickScheduler.FrameCallback {
            successorCalls++
            scheduler.removeFrameCallback(successor)
        }
        lateinit var first: TickScheduler.FrameCallback
        first = TickScheduler.FrameCallback {
            scheduler.removeFrameCallback(first)
            val done = CountDownLatch(1)
            worker = Thread {
                try {
                    scheduler.postFrameCallback(successor)
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    done.countDown()
                }
            }.apply { start() }
            // Do not assert inside an intentionally exception-isolating consumer callback.
            registeredWhileInCallback = done.await(5, TimeUnit.SECONDS)
        }
        try {
            scheduler.postFrameCallback(first)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            assertTrue("consumer callbacks must run outside the scheduler state lock", registeredWhileInCallback)
            failure.get()?.let { throw AssertionError("concurrent registration", it) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            assertEquals("new registration must survive empty-list cleanup", 1, successorCalls)
        } finally {
            scheduler.stop()
            worker?.join(5000)
            assertFalse(worker?.isAlive ?: false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        }
    }


    @Test fun testMissingLooperAttemptDoesNotPermanentlyCacheAFailedFrameSource() {
        val scheduler = ChoreographerTickScheduler()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                scheduler.start() // existing no-Looper compatibility path: no source, no frame
                scheduler.stop()
            } catch (t: Throwable) {
                failure.set(t)
            }
        }.apply { start() }
        worker.join(5000)
        assertFalse(worker.isAlive)
        failure.get()?.let { throw AssertionError("no-Looper compatibility", it) }
        var calls = 0
        try {
            scheduler.postFrameCallback { calls++ }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            assertEquals("only a successful source lookup establishes ownership", 1, calls)
        } finally {
            scheduler.stop()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        }
    }

    @Test fun testNullCallbacksDoNotStartClockAndRemovalOfUnknownIsHarmless() {
        val scheduler: TickScheduler = ChoreographerTickScheduler()
        scheduler.postFrameCallback(null)
        scheduler.removeFrameCallback(null)
        scheduler.removeFrameCallback { fail("unknown callback") }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
        assertEquals(0L, scheduler.frameCount)
        assertEquals(0L, scheduler.frameTimeNanos)
    }

    @Test fun testClockIsPublishedBeforeEachPersistentCallback() {
        val scheduler: TickScheduler = ChoreographerTickScheduler()
        val samples = mutableListOf<Pair<Long, Long>>()
        val callback = TickScheduler.FrameCallback { timestamp ->
            assertEquals(timestamp, scheduler.frameTimeNanos)
            samples.add(scheduler.frameCount to timestamp)
        }
        try {
            scheduler.postFrameCallback(callback)
            repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10)) }
            assertEquals(listOf(1L, 2L, 3L), samples.map { it.first })
            assertTrue(samples.zipWithNext().all { (a, b) -> b.second > a.second })
        } finally { scheduler.removeFrameCallback(callback); scheduler.stop() }
    }

    @Test fun testStopInsideCallbackCompletesSnapshotButDoesNotScheduleNextFrame() {
        val scheduler: TickScheduler = ChoreographerTickScheduler()
        val calls = mutableListOf<String>()
        val first = TickScheduler.FrameCallback { calls.add("first"); scheduler.stop() }
        val second = TickScheduler.FrameCallback { calls.add("second") }
        try {
            scheduler.postFrameCallback(first)
            scheduler.postFrameCallback(second)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertEquals(listOf("first", "second"), calls)
            assertEquals(1L, scheduler.frameCount)
        } finally {
            scheduler.removeFrameCallback(first); scheduler.removeFrameCallback(second); scheduler.stop()
        }
    }
}
