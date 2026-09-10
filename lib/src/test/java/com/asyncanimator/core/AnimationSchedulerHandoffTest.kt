package com.asyncanimator.core

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class AnimationSchedulerHandoffTest {
    private class ManualScheduler : TickScheduler {
        val callbacks = linkedSetOf<TickScheduler.FrameCallback>()
        var stops = 0
        override val frameTimeNanos = 0L
        override val frameCount = 0L
        override fun postFrameCallback(callback: TickScheduler.FrameCallback?) { callback?.let(callbacks::add) }
        override fun removeFrameCallback(callback: TickScheduler.FrameCallback?) { callbacks.remove(callback) }
        override fun start() {}
        override fun stop() { stops++ }
        fun pulse() { callbacks.toList().forEach { it.doFrame(16_000_000) } }
    }

    // Each operation uses a fresh thread-local; propagate worker assertions to JUnit.
    private fun onFreshThread(action: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        val thread = Thread { try { action() } catch (t: Throwable) { failure.set(t) } }
        thread.start(); thread.join(5000)
        assertFalse("worker must terminate", thread.isAlive)
        failure.get()?.let { throw it }
    }

    @Test fun testInstallBeforeFirstAccessAndFailLoudAfterCreation() = onFreshThread {
        val first = ManualScheduler()
        AnimationHandler.installThreadScheduler(first)
        val handler = AnimationHandler.instance
        assertSame(first, handler.scheduler)
        assertThrows(IllegalStateException::class.java) {
            AnimationHandler.installThreadScheduler(ManualScheduler())
        }
        assertSame(handler, AnimationHandler.instance)
        assertSame(first, handler.scheduler)
    }

    @Test fun testNullInstallAndReplacementAreRejected() = onFreshThread {
        assertThrows(IllegalArgumentException::class.java) { AnimationHandler.installThreadScheduler(null) }
        assertThrows(IllegalArgumentException::class.java) { AnimationHandler.replaceThreadScheduler(null) }
    }

    @Test fun testLateOldPulseIsIgnoredAfterReplacement() = onFreshThread {
        val old = ManualScheduler(); val next = ManualScheduler()
        AnimationHandler.installThreadScheduler(old)
        var calls = 0
        AnimationHandler.instance.addAnimationFrameCallback { calls++; false }
        val stale = old.callbacks.single()
        old.pulse()
        AnimationHandler.replaceThreadScheduler(next)
        stale.doFrame(32_000_000)
        assertEquals(1, calls)
        next.pulse()
        assertEquals(2, calls)
        assertTrue(old.callbacks.isEmpty())
    }

    @Test fun testReentrantSwapFinishesCurrentFrameAndDoesNotStopOtherSubscribers() = onFreshThread {
        val old = ManualScheduler(); val next = ManualScheduler()
        AnimationHandler.installThreadScheduler(old)
        val handler = AnimationHandler.instance
        val calls = mutableListOf<String>()
        var replaced = false
        handler.addAnimationFrameCallback {
            calls.add("first")
            if (!replaced) { replaced = true; AnimationHandler.replaceThreadScheduler(next) }
            false
        }
        handler.addAnimationFrameCallback { calls.add("second"); false }
        val unrelated = TickScheduler.FrameCallback { calls.add("unrelated") }
        old.postFrameCallback(unrelated)
        old.pulse()
        assertEquals(listOf("first", "second", "unrelated"), calls)
        assertEquals(0, old.stops)
        assertEquals(setOf(unrelated), old.callbacks)
        next.pulse()
        assertEquals(listOf("first", "second", "unrelated", "first", "second"), calls)
    }

    @Test fun testSameSchedulerReplacementDoesNotRestartOrDuplicateSubscription() = onFreshThread {
        val scheduler = ManualScheduler()
        AnimationHandler.installThreadScheduler(scheduler)
        AnimationHandler.instance.addAnimationFrameCallback { false }
        val callback = scheduler.callbacks.single()
        AnimationHandler.replaceThreadScheduler(scheduler)
        assertEquals(0, scheduler.stops)
        assertSame(callback, scheduler.callbacks.single())
    }

    @Test fun testGlobalOverrideRejectsInstallAndSwapWithoutPoisoningThreadLocal() = onFreshThread {
        val overrideScheduler = ManualScheduler()
        val overrideHandler = AnimationHandler(overrideScheduler)
        val targetScheduler = ManualScheduler()
        val previous = AnimationHandler.testHandler
        assertNull("this fixture expects no other global override", previous)
        AnimationHandler.testHandler = overrideHandler
        try {
            assertSame(overrideHandler, AnimationHandler.instance)
            overrideHandler.addAnimationFrameCallback { false }
            assertEquals(1, AnimationHandler.animationCount)
            assertThrows(IllegalStateException::class.java) {
                AnimationHandler.installThreadScheduler(targetScheduler)
            }
            assertThrows(IllegalStateException::class.java) {
                AnimationHandler.replaceThreadScheduler(targetScheduler)
            }
            assertSame(overrideScheduler, overrideHandler.scheduler)
            assertEquals(1, overrideScheduler.callbacks.size)
            assertTrue(targetScheduler.callbacks.isEmpty())
        } finally {
            AnimationHandler.testHandler = previous
        }
        // Failed operations must not have created or replaced this thread's default instance.
        AnimationHandler.installThreadScheduler(targetScheduler)
        assertSame(targetScheduler, AnimationHandler.instance.scheduler)
        assertNotSame(overrideHandler, AnimationHandler.instance)
        assertEquals(0, AnimationHandler.animationCount)
    }

}
