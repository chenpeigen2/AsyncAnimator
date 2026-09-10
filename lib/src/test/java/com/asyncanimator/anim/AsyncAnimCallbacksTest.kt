package com.asyncanimator.anim

import android.animation.Animator
import android.animation.ValueAnimator
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import com.asyncanimator.playback.NullableAnimatorListener
import com.asyncanimator.playback.NullableAnimatorListenerAdapter
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36], manifest = org.robolectric.annotation.Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AsyncAnimCallbacksTest {
    @Suppress("UNCHECKED_CAST")
    private fun snapshot(callbacks: AsyncAnimCallbacks): List<NullableAnimatorListener> =
        AsyncAnimCallbacks::class.java.getDeclaredMethod("getListeners").let {
            it.isAccessible = true
            it.invoke(callbacks) as List<NullableAnimatorListener>
        }

    @Test
    fun testSnapshotIsStableAndRegistrationIsIdempotent() {
        val callbacks = AsyncAnimCallbacks()
        val listener = object : NullableAnimatorListenerAdapter() {}
        callbacks.addListener(listener)
        callbacks.addListener(listener)
        val before = snapshot(callbacks)
        callbacks.removeListener(listener)
        assertEquals(listOf(listener), before)
        assertTrue(snapshot(callbacks).isEmpty())
        callbacks.addListener(listener)
        callbacks.clearListeners()
        assertTrue(snapshot(callbacks).isEmpty())
    }

    @Test
    fun testConcurrentMutationAndSnapshots() {
        val callbacks = AsyncAnimCallbacks()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val jobs = (0 until 4).map {
                pool.submit {
                    val listener = object : NullableAnimatorListenerAdapter() {}
                    start.await()
                    repeat(2000) {
                        callbacks.addListener(listener)
                        val current = snapshot(callbacks)
                        assertEquals(current.distinct().size, current.size)
                        callbacks.removeListener(listener)
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(20, TimeUnit.SECONDS) }
            assertTrue(snapshot(callbacks).isEmpty())
        } finally {
            pool.shutdownNow()
        }
    }

    private fun worker(action: () -> Unit) {
        val pool = Executors.newSingleThreadExecutor()
        try { pool.submit(action).get(5, TimeUnit.SECONDS) }
        finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun testEveryQueuedEventCapturesIdentityButResolvesListenersAtDelivery() {
        val callbacks = AsyncAnimCallbacks()
        val animator = ValueAnimator()
        val calls = mutableListOf<String>()
        val old = object : ActualEndAnimListener() {
            override fun onAnimationStart(animator: Animator) { fail("removed before delivery") }
            override fun onAnimationCancel(animator: Animator) { fail("removed before delivery") }
            override fun onAnimationEnd(animator: Animator) { fail("removed before delivery") }
            override fun onAnimActualEnd(animator: Animator) { fail("removed before delivery") }
        }
        callbacks.addListener(old)
        callbacks.animationId = 11
        worker {
            callbacks.onAnimationStart(animator)
            callbacks.animationId = 12
            callbacks.onAnimationCancel(animator)
            callbacks.animationId = 13
            callbacks.onAnimationEnd(animator)
            callbacks.animationId = 14
            callbacks.onAnimActualEnd(animator)
        }
        callbacks.clearListeners() // Unlike dispose, clear does not invalidate the queued generation.
        callbacks.animationId = 99
        callbacks.addListener(object : ActualEndAnimListener() {
            private fun record(event: String, actual: Animator) {
                assertSame(animator, actual)
                assertSame(Looper.getMainLooper(), Looper.myLooper())
                calls.add("$event:$animationId")
            }
            override fun onAnimationStart(animator: Animator) = record("start", animator)
            override fun onAnimationCancel(animator: Animator) = record("cancel", animator)
            override fun onAnimationEnd(animator: Animator) = record("end", animator)
            override fun onAnimActualEnd(animator: Animator) = record("actual", animator)
        })
        assertTrue(calls.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("start:11", "cancel:12", "end:13", "actual:14"), calls)
    }

    @Test fun testReentrantMutationUsesCurrentSnapshotAndNewRegistrationOrderNextTime() {
        val callbacks = AsyncAnimCallbacks()
        val animator = ValueAnimator()
        val calls = mutableListOf<String>()
        val second = object : NullableAnimatorListenerAdapter() {
            override fun onAnimationStart(animator: Animator) { calls.add("second") }
        }
        val third = object : NullableAnimatorListenerAdapter() {
            override fun onAnimationStart(animator: Animator) { calls.add("third") }
        }
        callbacks.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationStart(animator: Animator) {
                calls.add("first")
                callbacks.removeListener(second)
                callbacks.addListener(third)
            }
        })
        callbacks.addListener(second)
        callbacks.onAnimationStart(animator)
        assertEquals(listOf("first", "second"), calls)
        calls.clear()
        callbacks.onAnimationStart(animator)
        assertEquals(listOf("first", "third"), calls)
    }

    @Test fun testReentrantDisposeAndNewDispatchUseIndependentGenerations() {
        val callbacks = AsyncAnimCallbacks()
        val calls = mutableListOf<String>()
        val animator = ValueAnimator()
        callbacks.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationStart(animator: Animator) {
                calls.add("old-first")
                callbacks.dispose()
                callbacks.addListener(object : NullableAnimatorListenerAdapter() {
                    override fun onAnimationStart(animator: Animator) { calls.add("new") }
                })
                callbacks.onAnimationStart(animator)
            }
        })
        callbacks.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationStart(animator: Animator) { fail("stale snapshot generation") }
        })
        callbacks.onAnimationStart(animator)
        assertEquals(listOf("old-first", "new"), calls)
    }

    @Test fun testListenerIsInvokedOutsideRegistrationLock() {
        val callbacks = AsyncAnimCallbacks()
        var next = 0
        val replacement = object : NullableAnimatorListenerAdapter() {
            override fun onAnimationEnd(animator: Animator) { next++ }
        }
        callbacks.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationStart(animator: Animator) {
                worker { callbacks.clearListeners(); callbacks.addListener(replacement) }
            }
        })
        callbacks.onAnimationStart(ValueAnimator())
        callbacks.onAnimationEnd(ValueAnimator())
        assertEquals(1, next)
    }

    @Test fun testThrowingListenerIsFailFastButContainerRemainsUsable() {
        val callbacks = AsyncAnimCallbacks()
        val failure = AssertionError("business")
        var calls = 0
        val bad = object : NullableAnimatorListenerAdapter() {
            override fun onAnimationCancel(animator: Animator) { throw failure }
        }
        callbacks.addListener(bad)
        callbacks.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationCancel(animator: Animator) { calls++ }
        })
        assertSame(failure, assertThrows(AssertionError::class.java) {
            callbacks.onAnimationCancel(ValueAnimator())
        })
        assertEquals(0, calls)
        callbacks.removeListener(bad)
        callbacks.onAnimationCancel(ValueAnimator())
        assertEquals(1, calls)
    }

    @Test fun testNullAndUnknownRemovalDoNotDisturbLiveRegistration() {
        val callbacks = AsyncAnimCallbacks()
        val listener = object : NullableAnimatorListenerAdapter() {}
        callbacks.addListener(null)
        callbacks.removeListener(null)
        callbacks.addListener(listener)
        callbacks.removeListener(object : NullableAnimatorListenerAdapter() {})
        callbacks.removeListener(null)
        assertEquals(listOf(listener), snapshot(callbacks))
        callbacks.removeListener(listener)
        callbacks.addListener(listener)
        callbacks.addListener(listener)
        assertEquals(listOf(listener), snapshot(callbacks))
    }
}
