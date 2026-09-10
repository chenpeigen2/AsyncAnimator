package com.asyncanimator.playback

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class AnimatorListenersTest {
    private fun value(fraction: Float) = ValueAnimator.ofFloat(0f, 1f).apply {
        interpolator = Interpolators.LINEAR
        setCurrentFraction(fraction)
    }

    @Test fun testPlainEndCallbackIgnoresCancelButRunsForEveryEnd() {
        var calls = 0
        val action: () -> Unit = { calls++ }
        val listener = AnimatorListeners.forEndCallback(action)
        val animator = value(0f)
        listener.onAnimationStart(animator)
        listener.onAnimationCancel(animator)
        assertEquals(0, calls)
        listener.onAnimationEnd(animator)
        listener.onAnimationEnd(animator)
        assertEquals(2, calls)
    }

    @Test fun testBooleanEndUsesStrictHalfProgressThreshold() {
        for (fraction in listOf(0f, 0.4999f, 0.5f, 0.5001f, 1f)) {
            val results = mutableListOf<Boolean>()
            val listener = AnimatorListeners.forEndCallback { success: Boolean -> results.add(success); Unit }
            listener.onAnimationEnd(value(fraction))
            assertEquals(listOf(fraction > 0.5f), results)
        }
    }

    @Test fun testNonValueAnimatorEndIsSuccessfulWithoutFractionQuery() {
        val results = mutableListOf<Boolean>()
        val listener = AnimatorListeners.forEndCallback { success: Boolean -> results.add(success); Unit }
        listener.onAnimationEnd(AnimatorSet())
        assertEquals(listOf(true), results)
    }

    @Test fun testCancelOrEndWinsOnceAndStartDoesNotRearmBooleanWrapper() {
        for (cancelFirst in listOf(true, false)) {
            val results = mutableListOf<Boolean>()
            val listener = AnimatorListeners.forEndCallback { success: Boolean -> results.add(success); Unit }
            val animator = value(1f)
            if (cancelFirst) listener.onAnimationCancel(animator) else listener.onAnimationEnd(animator)
            listener.onAnimationStart(animator)
            listener.onAnimationCancel(animator)
            listener.onAnimationEnd(animator)
            assertEquals(listOf(!cancelFirst), results)
        }
    }

    @Test fun testBooleanWrapperConsumesBeforeCallbackReenters() {
        lateinit var listener: Animator.AnimatorListener
        val animator = value(1f)
        var calls = 0
        listener = AnimatorListeners.forEndCallback { _: Boolean ->
            calls++
            listener.onAnimationCancel(animator)
            listener.onAnimationEnd(animator)
        }
        listener.onAnimationEnd(animator)
        assertEquals(1, calls)
    }

    @Test fun testBooleanWrapperDoesNotReplayThrowingCallback() {
        val failure = IllegalStateException("end failed")
        var calls = 0
        val listener = AnimatorListeners.forEndCallback { _: Boolean -> calls++; throw failure }
        val animator = value(1f)
        assertSame(failure, assertThrows(IllegalStateException::class.java) { listener.onAnimationEnd(animator) })
        listener.onAnimationCancel(animator)
        listener.onAnimationEnd(animator)
        assertEquals(1, calls)
    }

    @Test fun testNullableFactoriesAreSafeForEveryLifecycleEvent() {
        val plain: (() -> Unit)? = null
        val result: ((Boolean) -> Unit)? = null
        val animator = value(1f)
        for (listener in listOf(AnimatorListeners.forEndCallback(plain),
            AnimatorListeners.forEndCallback(result), AnimatorListeners.forSuccessCallback(null))) {
            listener.onAnimationStart(animator)
            listener.onAnimationCancel(animator)
            listener.onAnimationEnd(animator)
        }
    }

    @Test fun testSuccessFactoryTracksCancellationNotHalfProgress() {
        var calls = 0
        val listener = AnimatorListeners.forSuccessCallback { calls++ }
        val animator = value(0.25f)
        listener.onAnimationEnd(animator)
        assertEquals(1, calls)
        listener.onAnimationCancel(animator)
        listener.onAnimationStart(animator)
        listener.onAnimationEnd(animator)
        assertEquals(1, calls) // Listener cancellation is sticky; create a new listener per lifecycle.
    }
}
