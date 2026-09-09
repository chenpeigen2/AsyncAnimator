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
        override val frameIntervalMs = 16L
        override fun postFrameCallback(callback: TickScheduler.FrameCallback?) { callback?.let(callbacks::add) }
        override fun removeFrameCallback(callback: TickScheduler.FrameCallback?) { callbacks.remove(callback) }
        override fun start() { running = true }
        override fun stop() { running = false }
        fun pulse() {
            if (!running) return
            frameTimeNanos += 16_000_000
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
    fun testRemoveNonExistentCallback() {
        val handler = AnimationHandler(ManualScheduler())
        handler.removeCallback(AnimationHandler.AnimationFrameCallback { false })
        assertEquals(0, handler.callbackSize)
    }
}
