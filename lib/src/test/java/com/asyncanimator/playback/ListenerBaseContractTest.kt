package com.asyncanimator.playback

import android.animation.Animator
import android.animation.ValueAnimator
import com.asyncanimator.anim.ActualEndAnimListener
import com.asyncanimator.anim.AsyncAnimCallbacks
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class ListenerBaseContractTest {
    private class InspectableAdapter : NullableAnimatorListenerAdapter() {
        val wasCancelled get() = cancelled
    }

    @Test fun testNullableInterfaceDefaultEventsNeedNoOverrides() {
        val listener = object : NullableAnimatorListener {}
        val animator = ValueAnimator()
        listener.onAnimationStart(animator)
        listener.onAnimationCancel(animator)
        listener.onAnimationEnd(animator)
        val callbacks = AsyncAnimCallbacks()
        try {
            callbacks.addListener(listener)
            callbacks.onAnimationStart(animator)
            callbacks.onAnimationCancel(animator)
            callbacks.onAnimationEnd(animator)
            callbacks.onAnimActualEnd(animator)
        } finally { callbacks.dispose() }
    }

    @Test fun testAdapterCancellationIsStickyAndIdentityDefaultsToUnassigned() {
        val listener = InspectableAdapter()
        val animator = ValueAnimator()
        assertFalse(listener.wasCancelled)
        assertEquals(-1, listener.animationId)
        listener.onAnimationEnd(animator)
        assertFalse(listener.wasCancelled)
        listener.onAnimationCancel(animator)
        listener.onAnimationStart(animator)
        listener.onAnimationEnd(animator)
        assertTrue(listener.wasCancelled)
    }

    @Test fun testActualEndBaseHookDoesNotSynthesizeLogicalEndOrCancel() {
        val events = mutableListOf<String>()
        val listener = object : ActualEndAnimListener() {
            override fun onAnimationEnd(animator: Animator) { events.add("end") }
            override fun onAnimationCancel(animator: Animator) { events.add("cancel") }
        }
        listener.onAnimActualEnd(ValueAnimator())
        assertTrue(events.isEmpty())
    }

    @Test fun testActualEndConsumerReceivesAnimatorAndCurrentDispatchId() {
        val callbacks = AsyncAnimCallbacks()
        val animator = ValueAnimator()
        var observed: Animator? = null
        var observedId = -1
        val listener = object : ActualEndAnimListener() {
            override fun onAnimActualEnd(animator: Animator) {
                observed = animator
                observedId = animationId
            }
        }
        try {
            callbacks.animationId = 73
            callbacks.addListener(listener)
            callbacks.onAnimActualEnd(animator)
            assertSame(animator, observed)
            assertEquals(73, observedId)
        } finally { callbacks.dispose() }
    }

    @Test fun testSuccessListenerSeparatesPhysicalEndAndCancellationFromSuccess() {
        val successes = mutableListOf<Animator>()
        var actualEnds = 0
        val listener = object : AnimationSuccessListener() {
            override fun onAnimationSuccess(animator: Animator) { successes.add(animator) }
            override fun onAnimActualEnd(animator: Animator) { actualEnds++ }
        }
        val animator = ValueAnimator()
        listener.onAnimActualEnd(animator)
        assertTrue(successes.isEmpty())
        listener.onAnimationEnd(animator)
        assertEquals(listOf(animator), successes)
        listener.onAnimationCancel(animator)
        listener.onAnimationEnd(animator)
        listener.onAnimActualEnd(animator)
        assertEquals(1, successes.size)
        assertEquals(2, actualEnds)
    }

    @Test fun testSuccessListenerPropagatesBusinessException() {
        val failure = IllegalStateException("success failed")
        val listener = object : AnimationSuccessListener() {
            override fun onAnimationSuccess(animator: Animator) { throw failure }
        }
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            listener.onAnimationEnd(ValueAnimator())
        })
        listener.onAnimationCancel(ValueAnimator())
        listener.onAnimationEnd(ValueAnimator()) // Cancellation suppresses success, including its exception.
    }
}
