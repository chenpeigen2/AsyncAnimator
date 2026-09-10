package com.asyncanimator.anim

import android.os.Looper
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.FrameCallbackScheduler
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual AndroidX integration, with only the public one-shot frame scheduler controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, instrumentedPackages = ["com.asyncanimator.anim"])
class AsyncSpringAnimTest {
    private val work = linkedSetOf<Runnable>()
    private val owner = Thread.currentThread()
    private val holder = FloatValueHolder(0f)
    private val real = SpringAnimation(holder).apply {
        spring = SpringForce(100f).setStiffness(100f).setDampingRatio(1f)
        setScheduler(object : FrameCallbackScheduler {
            override fun postFrameCallback(callback: Runnable) { work.add(callback) }
            override fun isCurrentThread() = Thread.currentThread() === owner
        })
    }
    private val wrapper = AsyncSpringAnim(real, false)
    private val endings = mutableListOf<Pair<Boolean, Float>>()

    private fun listen() {
        wrapper.addEndListener { _, canceled, value, _ -> endings.add(canceled to value) }
    }
    private fun pulse() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        val callbacks = work.toList(); work.clear()
        callbacks.forEach(Runnable::run)
    }
    @After fun cleanup() {
        real.cancel()
        // AndroidX removes its duration-scale listener during posted null-list compaction.
        val callbacks = work.toList(); work.clear()
        callbacks.forEach(Runnable::run)
    }

    @Test fun testCancelOnOwnerIsImmediateAndPreservesCurrentValue() {
        listen(); wrapper.start(); pulse(); pulse()
        val before = holder.value
        assertTrue(before > 0f && before < 100f)
        wrapper.cancel()
        assertFalse(real.isRunning)
        assertEquals(listOf(true to before), endings)
        pulse()
        assertEquals(before, holder.value, 0f)
        assertEquals(1, endings.size)
    }

    @Test fun testSkipAfterWarmupWaitsForNextFrameAndReportsNotCancelled() {
        listen(); wrapper.start(); pulse(); pulse()
        wrapper.skipToEnd()
        assertTrue(real.isRunning)
        assertTrue(endings.isEmpty())
        pulse()
        assertFalse(real.isRunning)
        assertEquals(listOf(false to 100f), endings)
        assertEquals(100f, holder.value, 0f)
    }

    @Test fun testSkipBeforeFirstFrameRetainsNativeWarmup() {
        listen(); wrapper.start(); wrapper.skipToEnd()
        pulse()
        assertTrue(real.isRunning)
        assertEquals(0f, holder.value, 0f)
        assertTrue(endings.isEmpty())
        pulse()
        assertFalse(real.isRunning)
        assertEquals(listOf(false to 100f), endings)
    }

    @Test fun testZeroDampingRejectsSkipButAllowsCancel() {
        real.spring.dampingRatio = 0f
        listen(); wrapper.start(); pulse()
        assertFalse(real.canSkipToEnd())
        assertThrows(UnsupportedOperationException::class.java) { wrapper.skipToEnd() }
        assertTrue(real.isRunning)
        wrapper.cancel()
        assertFalse(real.isRunning)
        assertEquals(listOf(true to 0f), endings)
    }
}
