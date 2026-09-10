package com.asyncanimator.anim

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.asyncanimator.playback.NullableAnimatorListenerAdapter
import com.asyncanimator.thread.LooperExecutor
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AsyncValueAnimatorLifecycleTest {
    private fun animator() = AsyncValueAnimator.ofFloat(0f, 1f).apply { duration = 1000 }

    @Test fun testQueuedStartIsInvalidatedBeforeOwnerExecutesIt() {
        val a = animator()
        var starts = 0
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) { starts++ }
        })
        Thread { a.start() }.apply { start(); join(5000); assertFalse(isAlive) }
        a.dispose()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, starts)
        assertFalse(a.isStarted)
        assertTrue(a.listeners.isNullOrEmpty())
    }

    @Test fun testDisposeRunningAnimatorRemovesNativeAndAsyncListenersWithoutEndDelivery() {
        val a = animator()
        var updates = 0
        var ends = 0
        a.addUpdateListener { updates++ }
        a.addAnimatorListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationEnd(animator: Animator) { ends++ }
        })
        a.start()
        assertTrue(a.isStarted)
        a.dispose()
        val before = updates
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        a.setCurrentFraction(0.5f)
        assertEquals(before, updates)
        assertEquals(0, ends)
        assertFalse(a.isStarted)
        assertTrue(a.listeners.isNullOrEmpty())
    }

    @Test fun testDisposeIsFinalAndIdempotentButCancelAndEndRemainHarmless() {
        val a = animator()
        a.dispose(); a.dispose(); a.cancel(); a.end()
        assertThrows(IllegalStateException::class.java) { a.start() }
        assertThrows(IllegalStateException::class.java) { a.executor = LooperExecutor(null) }
        assertThrows(IllegalStateException::class.java) { a.addAnimatorListener(null) }
        assertFalse(a.isStarted)
    }

    @Test fun testFirstLifecycleCommandPinsExecutorIncludingQueuedCancelAndEnd() {
        for (command in listOf<(AsyncValueAnimator) -> Unit>({ it.start() }, { it.cancel() }, { it.end() })) {
            val a = animator()
            val original = a.executor
            Thread { command(a) }.apply { start(); join(5000); assertFalse(isAlive) }
            a.executor = original
            assertThrows(IllegalStateException::class.java) { a.executor = LooperExecutor(null) }
            a.dispose()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun testReentrantDisposalDuringStartCannotResurrectPlatformAnimation() {
        val a = animator()
        a.addAnimatorListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationStart(animator: Animator) { a.dispose() }
        })
        a.start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(a.isStarted)
        assertTrue(a.listeners.isNullOrEmpty())
    }

    @Test fun testOffMainDisposeInvalidatesAlreadyQueuedMainCallbacks() {
        val a = animator()
        var starts = 0
        a.addAnimatorListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationStart(animator: Animator) { starts++ }
        })
        Thread {
            a.asyncAnimCallbacks.onAnimationStart(a)
            a.dispose()
        }.apply { start(); join(5000); assertFalse(isAlive) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, starts)
    }

    @Test fun testDisposalRunsOnActualPinnedHandlerThread() {
        val thread = HandlerThread("anim-disposal-test").apply { start() }
        val handler = Handler(thread.looper)
        val failure = AtomicReference<Throwable?>()
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val a = animator().apply { executor = LooperExecutor(handler) }
        try {
            handler.post {
                try { a.start(); assertTrue(a.isStarted) }
                catch (t: Throwable) { failure.set(t) }
                finally { started.countDown() }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            a.dispose() // main caller; cancellation must not run here
            handler.post {
                try {
                    assertFalse(a.isStarted)
                    assertTrue(a.listeners.isNullOrEmpty())
                } catch (t: Throwable) { failure.set(t) }
                finally { stopped.countDown() }
            }
            assertTrue(stopped.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("owner-thread lifecycle", it) }
        } finally {
            a.dispose()
            thread.quitSafely()
            thread.join(5000)
            assertFalse(thread.isAlive)
        }
    }
}
