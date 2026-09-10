package com.asyncanimator.control

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36], manifest = org.robolectric.annotation.Config.NONE)
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
class TaskStateChangeTimeOutListenerTest {
    private val type = TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH

    @Test
    fun testMatchingEventRunsOnlyOnce() {
        var calls = 0
        val listener = TaskStateChangeTimeOutListener(type, 100) { calls++ }
        listener.onTimeOut(type, 100)
        listener.onTimeOut(type, 100)
        assertEquals(1, calls)
    }

    @Test
    fun testDisposedListenerCannotRun() {
        var calls = 0
        val listener = TaskStateChangeTimeOutListener(type, 100) { calls++ }
        listener.dispose()
        listener.onTimeOut(type, 100)
        assertEquals(0, calls)
    }

    @Test
    fun testUnrelatedEventDoesNotConsumeListener() {
        var calls = 0
        val listener = TaskStateChangeTimeOutListener(type, 100) { calls++ }
        listener.onTimeOut(TaskStateChangeTimeOutListener.Type.ON_LAND_SCAPE_SCENE_EXIT, 100)
        assertEquals(0, calls)
        listener.onTimeOut(type, 100)
        assertEquals(1, calls)
    }

    @Test
    fun testTimerThenEventRunsOnlyOnce() {
        var calls = 0
        val listener = TaskStateChangeTimeOutListener(type, 100) { calls++ }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(100))
        assertEquals(1, calls)
        listener.onTimeOut(type, 100)
        assertEquals(1, calls)
    }

    @Test
    fun testDisposeCancelsScheduledTimer() {
        var calls = 0
        val listener = TaskStateChangeTimeOutListener(type, 100) { calls++ }
        listener.dispose()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(100))
        assertEquals(0, calls)
    }
    @Test fun testActionFailureRemainsPrimaryWhenDisposalAlsoFails() {
        val actionFailure = IllegalStateException("action")
        val disposalFailure = IllegalArgumentException("dispose")
        var actions = 0; var disposals = 0
        val listener = TaskStateChangeTimeOutListener(type, 100,
            { actions++; throw actionFailure }, { disposals++; throw disposalFailure })
        val actual = assertThrows(IllegalStateException::class.java) { listener.onTimeOut(type, 0) }
        assertSame(actionFailure, actual)
        assertEquals(listOf(disposalFailure), actual.suppressed.toList())
        listener.dispose(); listener.onTimeOut(type, 0)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(101))
        assertEquals(1, actions); assertEquals(1, disposals)
    }

    @Test fun testDisposalOnlyFailureIsReportedOnceAfterAction() {
        val failure = IllegalStateException("dispose")
        var actions = 0; var disposals = 0
        val listener = TaskStateChangeTimeOutListener(type, 100,
            { actions++ }, { disposals++; throw failure })
        assertSame(failure, assertThrows(IllegalStateException::class.java) { listener.onTimeOut(type, 0) })
        listener.onTimeOut(type, 0); listener.dispose()
        assertEquals(1, actions); assertEquals(1, disposals)
    }

    @Test fun testConcurrentMatchingStandaloneEventsClaimActionAndDisposalOnce() {
        val start = java.util.concurrent.CountDownLatch(1)
        val actions = java.util.concurrent.atomic.AtomicInteger()
        val disposals = java.util.concurrent.atomic.AtomicInteger()
        val actionThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val listener = TaskStateChangeTimeOutListener(type, 1000,
            { actions.incrementAndGet(); actionThread.set(Thread.currentThread()) },
            { disposals.incrementAndGet() })
        val workers = (0..7).map { index -> Thread({
            try { start.await(); listener.onTimeOut(type, 0) }
            catch (t: Throwable) { failure.compareAndSet(null, t) }
        }, "timeout-event-$index").apply { start() } }
        try {
            start.countDown()
            workers.forEach { it.join(5000); assertFalse(it.isAlive) }
            assertNull(failure.get())
            assertEquals(1, actions.get()); assertEquals(1, disposals.get())
            assertTrue(workers.contains(actionThread.get()))
        } finally { start.countDown(); listener.dispose(); workers.forEach { it.join(5000) } }
    }

    @Test fun testDisposeAfterClaimCannotRecallRunningStandaloneAction() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.atomic.AtomicInteger()
        val disposals = java.util.concurrent.atomic.AtomicInteger()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val listener = TaskStateChangeTimeOutListener(type, 1000, {
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            completed.incrementAndGet()
        }, { disposals.incrementAndGet() })
        val worker = Thread {
            try { listener.onTimeOut(type, 0) } catch (t: Throwable) { failure.set(t) }
        }.apply { start() }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            listener.dispose()
            assertEquals(0, completed.get())
            assertEquals(1, disposals.get())
            release.countDown(); worker.join(5000)
            assertFalse(worker.isAlive); assertNull(failure.get())
            assertEquals(1, completed.get()); assertEquals(1, disposals.get())
        } finally { release.countDown(); worker.join(5000); listener.dispose() }
    }

    @Test fun testNonpositiveTimeoutIsQueuedNotInlineInConstructor() {
        for (delay in listOf(0L, -1L)) {
            var calls = 0
            val listener = TaskStateChangeTimeOutListener(type, delay) { calls++ }
            assertEquals(0, calls)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, calls)
            listener.onTimeOut(type, delay); listener.dispose()
            assertEquals(1, calls)
        }
    }

    @Test fun testLifecycleDiagnosticsAreGatedAndDisposedIsLoggedOnce() {
        val out = java.io.ByteArrayOutputStream(); val original = System.err
        val logs = com.asyncanimator.core.LogUtils
        val level = if (logs.isAlwayson()) logs.ALWAYS else if (logs.isLogOpen()) logs.INFO else logs.OFF
        try {
            System.setErr(java.io.PrintStream(out, true, Charsets.UTF_8)); logs.setLogLevel(logs.INFO)
            val listener = TaskStateChangeTimeOutListener(type, 100) {}
            listener.onTimeOut(type, 0); listener.dispose()
            val text = out.toString(Charsets.UTF_8)
            assertTrue(text.contains("registered type=ON_TRANSITION_FINISH"))
            assertTrue(text.contains("firing type=ON_TRANSITION_FINISH"))
            assertEquals(1, Regex("disposed type=ON_TRANSITION_FINISH").findAll(text).count())
            out.reset(); logs.setLogLevel(logs.OFF)
            TaskStateChangeTimeOutListener(type, 100) {}.dispose()
            assertEquals("", out.toString(Charsets.UTF_8))
        } finally { System.setErr(original); logs.setLogLevel(level) }
    }


    @Test fun testAllTypePairsMatchOnlyTypeAndIgnoreEventDuration() {
        assertEquals(listOf("ON_LAND_SCAPE_SCENE_EXIT", "ON_TRANSITION_FINISH",
            "ON_APP_TO_OVERVIEW_CONTINUATION"), TaskStateChangeTimeOutListener.Type.entries.map { it.name })
        for (expected in TaskStateChangeTimeOutListener.Type.entries) {
            var calls = 0
            val listener = TaskStateChangeTimeOutListener(expected, 1000) { calls++ }
            try {
                TaskStateChangeTimeOutListener.Type.entries.filter { it != expected }.forEach {
                    listener.onTimeOut(it, Long.MAX_VALUE)
                }
                assertEquals(0, calls)
                listener.onTimeOut(expected, Long.MIN_VALUE)
                assertEquals(1, calls)
            } finally { listener.dispose() }
        }
    }

    @Test fun testTimerFiresAtExactDeadlineOnMainWithDisposalAfterAction() {
        val calls = mutableListOf<String>()
        val listener = TaskStateChangeTimeOutListener(type, 100, {
            assertSame(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
            calls.add("action")
        }, { calls.add("dispose") })
        try {
            val main = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            main.idleFor(java.time.Duration.ofMillis(99))
            assertTrue(calls.isEmpty())
            main.idleFor(java.time.Duration.ofMillis(1))
            assertEquals(listOf("action", "dispose"), calls)
            listener.onTimeOut(type, 0)
            assertEquals(2, calls.size)
        } finally { listener.dispose() }
    }

    @Test fun testReentrantDisposeAndMatchingEventCannotRunClaimedActionTwice() {
        val calls = mutableListOf<String>()
        lateinit var listener: TaskStateChangeTimeOutListener
        listener = TaskStateChangeTimeOutListener(type, 100, {
            calls.add("action-enter")
            listener.onTimeOut(type, 0)
            listener.dispose()
            calls.add("action-exit")
        }, { calls.add("dispose"); listener.dispose() })
        listener.onTimeOut(type, 0)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(100))
        assertEquals(listOf("action-enter", "dispose", "action-exit"), calls)
    }

    @Test fun testIdenticalActionAndCleanupFailureDoesNotSelfSuppress() {
        val failure = AssertionError("shared failure")
        val listener = TaskStateChangeTimeOutListener(type, 100, { throw failure }, { throw failure })
        assertSame(failure, assertThrows(AssertionError::class.java) { listener.onTimeOut(type, 0) })
        assertTrue(failure.suppressed.isEmpty())
        listener.dispose()
    }

    @Test fun testRejectedConstructionDoesNotScheduleTimerOrDisposeUnownedAction() {
        val failure = IllegalStateException("access denied")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            TaskStateChangeTimeOutListener(type, 1,
                { fail("not constructed") }, { fail("not owned") }, { throw failure })
        })
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(100))
    }

}
