package com.asyncanimator.anim

import android.os.Looper
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.FrameCallbackScheduler
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.asyncanimator.thread.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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

    @Test fun testVelocityAndRetargetCommandsReachNativeSpringWithoutPrematureEnd() {
        listen()
        wrapper.setStartVelocity(-80f)
        var firstVelocity: Float? = null
        real.addUpdateListener { _, _, velocity -> if (firstVelocity == null) firstVelocity = velocity }
        wrapper.animateToFinalPosition(150f) // Starts an idle native spring.
        assertTrue(real.isRunning)
        assertEquals(150f, real.spring.finalPosition, 0f)
        pulse()
        assertEquals(-80f, checkNotNull(firstVelocity), 0f)
        wrapper.animateToFinalPosition(-20f)
        pulse()
        assertEquals(-20f, real.spring.finalPosition, 0f)
        assertTrue(endings.isEmpty())
        wrapper.skipToEnd(); pulse()
        assertEquals(listOf(false to -20f), endings)
    }

    @Test fun testIdleCancelAndSkipDoNotEmitEndAndRepeatedStartDoesNotDuplicateFrames() {
        listen()
        wrapper.cancel(); wrapper.skipToEnd()
        assertFalse(real.isRunning)
        assertTrue(endings.isEmpty())
        wrapper.start(); wrapper.start()
        assertEquals(1, work.size)
        pulse(); wrapper.cancel(); wrapper.cancel()
        assertEquals(listOf(true to 0f), endings)
    }

    @Test fun testEndListenerReceivesActualNativeIdentityValueVelocityAndOwnerThread() {
        var calls = 0
        wrapper.addEndListener { animation, canceled, value, velocity ->
            assertSame(real, animation)
            assertSame(owner, Thread.currentThread())
            assertTrue(canceled)
            assertEquals(0f, value, 0f)
            assertEquals(37f, velocity, 0f)
            calls++
        }
        wrapper.setStartVelocity(37f)
        wrapper.start(); pulse(); wrapper.cancel()
        assertEquals(1, calls)
    }

    @Test fun testSynchronousListenerExceptionPropagatesAfterNativeStops() {
        val failure = IllegalStateException("end listener")
        wrapper.addEndListener { _, _, _, _ -> throw failure }
        wrapper.start(); pulse()
        assertSame(failure, assertThrows(IllegalStateException::class.java) { wrapper.cancel() })
        assertFalse(real.isRunning)
    }

    @Test fun testAsyncCommandsUseActualOwnerAndEndNotificationReturnsToMain() {
        val executor = Executors.ANIM_CONTROL_EXECUTOR
        fun onOwner(action: () -> Unit) {
            val done = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            executor.execute {
                try { action() } catch (t: Throwable) { failure.set(t) }
                finally { done.countDown() }
            }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("animation owner", it) }
        }
        val frames = linkedSetOf<Runnable>() // Only read/written on owner.
        val background = SpringAnimation(FloatValueHolder(2f)).apply {
            spring = SpringForce(100f).setDampingRatio(1f)
            setScheduler(object : FrameCallbackScheduler {
                override fun postFrameCallback(callback: Runnable) {
                    assertTrue(executor.isCurrentThread)
                    frames.add(callback)
                }
                override fun isCurrentThread() = executor.isCurrentThread
            })
        }
        val async = AsyncSpringAnim(background, true)
        val events = mutableListOf<String>() // Main only.
        async.addEndListener { animation, canceled, value, velocity ->
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            assertSame(background, animation)
            assertEquals(2f, value, 0f)
            assertEquals(25f, velocity, 0f)
            events.add(if (canceled) "cancel" else "end")
        }
        try {
            async.setStartVelocity(25f)
            async.animateToFinalPosition(80f)
            async.start() // Already running; no extra start registration.
            onOwner {
                assertTrue(background.isRunning)
                assertEquals(80f, background.spring.finalPosition, 0f)
                assertEquals(1, frames.size)
            }
            async.skipToEnd() // Queues native request, not an immediate callback.
            onOwner { assertTrue(background.isRunning) }
            assertTrue(events.isEmpty())
            async.cancel()
            onOwner { assertFalse(background.isRunning) }
            assertTrue(events.isEmpty())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("cancel"), events)
        } finally {
            onOwner {
                background.cancel()
                val pending = frames.toList(); frames.clear()
                pending.forEach(Runnable::run) // Compact AndroidX callbacks on their owner.
            }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
