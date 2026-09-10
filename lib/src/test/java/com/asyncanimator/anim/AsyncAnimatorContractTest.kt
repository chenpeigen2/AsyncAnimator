package com.asyncanimator.anim

import android.animation.Animator
import android.animation.ValueAnimator
import android.os.Looper
import com.asyncanimator.playback.NullableAnimatorListenerAdapter
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
class AsyncAnimatorContractTest {
    @Test fun testBooleanFactorySelectsAnimatorAndPreservesValues() {
        // Reflection also verifies the Java static entry point used by OPPO callers.
        val factory = AsyncValueAnimator::class.java.getDeclaredMethod(
            "ofFloat", Boolean::class.javaPrimitiveType, FloatArray::class.java)
        for (async in listOf(false, true)) {
            val animator = factory.invoke(null, async, floatArrayOf(2f, 8f)) as ValueAnimator
            assertEquals(async, animator is AsyncValueAnimator)
            animator.setCurrentFraction(0f)
            assertEquals(2f, animator.animatedValue as Float, 0f)
            animator.setCurrentFraction(1f)
            assertEquals(8f, animator.animatedValue as Float, 0f)
        }
        val shortcut: ValueAnimator = AsyncValueAnimator.ofFloat(0f, 1f)
        assertTrue(shortcut is AsyncValueAnimator)
    }

    private fun dispose(callbacks: AsyncAnimCallbacks) {
        AsyncAnimCallbacks::class.java.getMethod("dispose").invoke(callbacks)
    }

    @Test fun testDisposeClearsListenersAndAnimationId() {
        val callbacks = AsyncAnimCallbacks()
        var calls = 0
        callbacks.animationId = 42
        callbacks.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationEnd(animator: Animator) { calls++ }
        })
        dispose(callbacks)
        dispose(callbacks)
        callbacks.onAnimationEnd(ValueAnimator.ofFloat(0f, 1f))
        assertEquals(0, calls)
        assertEquals(-1, callbacks.animationId)
    }

    @Test fun testQueuedEventBeforeDisposeCannotReachReplacementListener() {
        val callbacks = AsyncAnimCallbacks()
        val animator = ValueAnimator.ofFloat(0f, 1f)
        val calls = mutableListOf<String>()
        callbacks.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationEnd(animator: Animator) { calls.add("old") }
        })
        Thread { callbacks.onAnimationEnd(animator) }.apply { start(); join() }
        dispose(callbacks)
        callbacks.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationEnd(animator: Animator) { calls.add("new") }
        })
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(calls.isEmpty())
        callbacks.onAnimationEnd(animator)
        assertEquals(listOf("new"), calls)
    }

    @Test fun testDisposedPhysicalEndCannotReachNewGeneration() {
        val callbacks = AsyncAnimCallbacks()
        val animator = ValueAnimator.ofFloat(0f, 1f)
        var actualEnds = 0
        val listener = object : ActualEndAnimListener() {
            override fun onAnimActualEnd(animator: Animator) { actualEnds++ }
        }
        callbacks.addListener(listener)
        Thread { callbacks.onAnimActualEnd(animator) }.apply { start(); join() }
        callbacks.dispose()
        callbacks.animationId = 73
        callbacks.addListener(listener)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, actualEnds)
        callbacks.onAnimActualEnd(animator)
        assertEquals(1, actualEnds)
        assertEquals(73, listener.animationId)
    }

    @Test fun testReentrantDisposeStopsRemainingListeners() {
        val callbacks = AsyncAnimCallbacks()
        val calls = mutableListOf<String>()
        callbacks.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationEnd(animator: Animator) {
                calls.add("first")
                callbacks.dispose()
            }
        })
        callbacks.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationEnd(animator: Animator) { calls.add("second") }
        })
        callbacks.onAnimationEnd(ValueAnimator.ofFloat(0f, 1f))
        assertEquals(listOf("first"), calls)
    }
    @Test fun testLogicalEndAndActualEndAreSeparateMainThreadEvents() {
        val callbacks = AsyncAnimCallbacks()
        callbacks.animationId = 91
        val animator = ValueAnimator.ofFloat(0f, 1f)
        val calls = mutableListOf<String>()
        callbacks.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationEnd(animator: Animator) { calls.add("ordinary-end") }
        })
        callbacks.addListener(object : ActualEndAnimListener() {
            override fun onAnimationEnd(animator: Animator) { calls.add("logical-end") }
            override fun onAnimActualEnd(animator: Animator) {
                assertSame(Looper.getMainLooper().thread, Thread.currentThread())
                assertEquals(91, animationId)
                calls.add("actual-end")
            }
        })
        Thread {
            callbacks.onAnimationEnd(animator)
            callbacks.onAnimActualEnd(animator)
        }.apply { start(); join() }
        assertTrue(calls.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("ordinary-end", "logical-end", "actual-end"), calls)
    }
}
