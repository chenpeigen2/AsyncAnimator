package com.asyncanimator.core

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36], manifest = org.robolectric.annotation.Config.NONE)
class AnimationHandlerTest {
    private class ManualScheduler : TickScheduler {
        val callbacks = linkedSetOf<TickScheduler.FrameCallback>()
        var running = false
        override var frameTimeNanos = 0L
        override var frameCount = 0L
        override fun postFrameCallback(callback: TickScheduler.FrameCallback?) { callback?.let(callbacks::add) }
        override fun removeFrameCallback(callback: TickScheduler.FrameCallback?) { callbacks.remove(callback) }
        override fun start() { running = true }
        override fun stop() { running = false }
        fun pulse(timeNanos: Long = frameTimeNanos + 16_000_000) {
            if (!running) return
            frameTimeNanos = timeNanos
            frameCount++
            callbacks.toList().forEach { it.doFrame(frameTimeNanos) }
        }
    }

    @Test
    fun testThreadLocalInstance() {
        assertSame(AnimationHandler.instance, AnimationHandler.instance)
        var other: AnimationHandler? = null
        Thread { other = AnimationHandler.instance }.apply { start(); join() }
        assertNotSame(AnimationHandler.instance, other)
    }

    @Test
    fun testRemoveLastCallbackUnsubscribesAndRestartTicksOnce() {
        val scheduler = ManualScheduler()
        val handler = AnimationHandler(scheduler)
        var calls = 0
        val callback = AnimationHandler.AnimationFrameCallback { calls++; false }
        handler.addAnimationFrameCallback(callback)
        scheduler.pulse()
        assertEquals(1, calls)
        handler.removeCallback(callback)
        scheduler.pulse()
        assertEquals(1, calls)
        assertEquals(0, handler.callbackSize)
        assertTrue(scheduler.callbacks.isEmpty())
        handler.addAnimationFrameCallback(callback)
        scheduler.pulse()
        assertEquals(2, calls)
        assertEquals(1, scheduler.callbacks.size)
    }

    @Test
    fun testReturnValueDoesNotReplaceExplicitRemoval() {
        val scheduler = ManualScheduler()
        val handler = AnimationHandler(scheduler)
        var calls = 0
        val callback = AnimationHandler.AnimationFrameCallback { calls++; true }
        handler.addAnimationFrameCallback(callback)
        scheduler.pulse()
        scheduler.pulse()
        assertEquals(2, calls)
        handler.removeCallback(callback)
        scheduler.pulse()
        assertEquals(2, calls)
    }

    @Test
    fun testCallbackCanRemoveItselfAndAddAnotherInSameFrame() {
        val scheduler = ManualScheduler()
        val handler = AnimationHandler(scheduler)
        val calls = mutableListOf<String>()
        val second = AnimationHandler.AnimationFrameCallback { calls.add("second"); false }
        lateinit var first: AnimationHandler.AnimationFrameCallback
        first = AnimationHandler.AnimationFrameCallback {
            calls.add("first")
            handler.removeCallback(first)
            handler.addAnimationFrameCallback(second)
            false
        }
        handler.addAnimationFrameCallback(first)
        scheduler.pulse()
        assertEquals(listOf("first", "second"), calls)
        scheduler.pulse()
        assertEquals(listOf("first", "second", "second"), calls)
    }

    @Test
    fun testAddSameCallbackTwice() {
        val scheduler = ManualScheduler()
        val handler = AnimationHandler(scheduler)
        var calls = 0
        val callback = AnimationHandler.AnimationFrameCallback { calls++; false }
        handler.addAnimationFrameCallback(callback)
        handler.addAnimationFrameCallback(callback)
        scheduler.pulse()
        assertEquals(1, calls)
        assertEquals(1, scheduler.callbacks.size)
    }

    @Test
    fun testCallbackExceptionIsIsolatedAndSelfRemovalStillCleansUp() {
        val scheduler = ManualScheduler()
        val handler = AnimationHandler(scheduler)
        var healthyCalls = 0
        lateinit var failing: AnimationHandler.AnimationFrameCallback
        failing = AnimationHandler.AnimationFrameCallback {
            handler.removeCallback(failing)
            throw IllegalStateException("expected callback failure")
        }
        handler.addAnimationFrameCallback(failing)
        handler.addAnimationFrameCallback { healthyCalls++; false }
        scheduler.pulse()
        scheduler.pulse()
        assertEquals(2, healthyCalls)
        assertEquals(1, handler.callbackSize)
    }

    @Test
    fun testRemoveNonExistentCallback() {
        val handler = AnimationHandler(ManualScheduler())
        handler.removeCallback(AnimationHandler.AnimationFrameCallback { false })
        assertEquals(0, handler.callbackSize)
    }

    @Test
    fun testFrameTimestampUsesSchedulerNanosTruncatedToMillis() {
        val scheduler = ManualScheduler()
        val handler = AnimationHandler(scheduler)
        val received = mutableListOf<Long>()
        handler.addAnimationFrameCallback { received.add(it); false }
        scheduler.pulse(1_999_999)
        scheduler.pulse(17_123_456)
        scheduler.pulse(1_234_567_890)
        assertEquals(listOf(1L, 17L, 1234L), received)
        assertEquals(1_234_567_890L, handler.scheduler.frameTimeNanos)
        assertEquals(3L, handler.scheduler.frameCount)
    }

    @Test
    fun testRemovalSkipsUpcomingCallbackAndCompactionPreservesSurvivorOrder() {
        val scheduler = ManualScheduler()
        val handler = AnimationHandler(scheduler)
        val calls = mutableListOf<String>()
        val removed = AnimationHandler.AnimationFrameCallback { calls.add("removed"); false }
        var removeOnce = true
        handler.addAnimationFrameCallback {
            calls.add("first")
            if (removeOnce) {
                removeOnce = false
                handler.removeCallback(removed)
                assertEquals(2, handler.callbackSize) // null slot is already excluded before compaction
            }
            false
        }
        handler.addAnimationFrameCallback(removed)
        handler.addAnimationFrameCallback { calls.add("last"); false }
        scheduler.pulse()
        scheduler.pulse()
        assertEquals(listOf("first", "last", "first", "last"), calls)
        assertEquals(2, handler.callbackSize)
    }

    @Test
    fun testNullOperationsStayIdleAndReaddBeforeCleanupKeepsOneSubscription() {
        val scheduler = ManualScheduler()
        val handler = AnimationHandler(scheduler)
        handler.addAnimationFrameCallback(null)
        handler.removeCallback(null)
        assertFalse(scheduler.running)
        assertTrue(scheduler.callbacks.isEmpty())
        var calls = 0
        val callback = AnimationHandler.AnimationFrameCallback { calls++; false }
        handler.addAnimationFrameCallback(callback)
        handler.removeCallback(callback)
        assertEquals(0, handler.callbackSize)
        // Still awaiting cleanup; a new registration must reuse the existing self-pulse.
        handler.addAnimationFrameCallback(callback)
        handler.addAnimationFrameCallback(callback)
        scheduler.pulse()
        scheduler.pulse()
        assertEquals(2, calls)
        assertEquals(1, handler.callbackSize)
        assertEquals(1, scheduler.callbacks.size)
        handler.removeCallback(callback)
        scheduler.pulse()
        assertTrue(scheduler.callbacks.isEmpty())
    }

}
