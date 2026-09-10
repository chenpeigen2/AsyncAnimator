package com.asyncanimator.playback

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.util.FloatProperty
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class PendingAnimationTest {
    private class Target(var value: Float = 2f)
    private val property = object : FloatProperty<Target>("value") {
        override fun get(target: Target): Float = target.value
        override fun setValue(target: Target, value: Float) { target.value = value }
    }

    @Test fun testSetFloatDefersMutationAndPlaybackSeeksProperty() {
        val target = Target()
        val pending = PendingAnimation(200)
        pending.setFloat(target, property, 10f, Interpolators.LINEAR)
        assertEquals(2f, target.value, 0f)
        val controller = pending.createPlaybackController()
        controller.setPlayFraction(0.5f)
        assertEquals(6f, target.value, 0.001f)
        controller.setPlayFraction(1f)
        assertEquals(10f, target.value, 0.001f)
    }

    @Test fun testAddFloatRegistersHolderAndRetainsCurve() {
        val target = Target()
        val pending = PendingAnimation(200)
        pending.addFloat(target, property, 2f, 10f, { it * it })
        pending.createPlaybackController().setPlayFraction(0.5f)
        assertEquals(4f, target.value, 0.001f)
    }

    @Test fun testProgressAnimatorAndEmptyFallbackUseRequestedDuration() {
        for (duration in listOf(0L, 80L, 700L)) {
            val pending = PendingAnimation(duration).addOnFrameCallback {}
            pending.addEndListener { _: Boolean -> }
            val root = pending.buildAnim()
            assertEquals(1, root.childAnimations.size)
            assertEquals(duration, root.childAnimations.single().duration)
            assertSame(root, pending.buildAnim())
            assertEquals(1, root.childAnimations.size)
            assertEquals(duration, PendingAnimation(duration).buildAnim().childAnimations.single().duration)
        }
    }

    @Test fun testSetFloatNullOrUnchangedAddsNoPropertyAnimation() {
        val target = Target()
        val pending = PendingAnimation(90)
        pending.setFloat(target, null, 10f, Interpolators.LINEAR)
        pending.setFloat(target, property, 2f, Interpolators.LINEAR)
        val root = pending.buildAnim()
        // Only the empty-set progress fallback remains.
        assertEquals(1, root.childAnimations.size)
        pending.createPlaybackController().setPlayFraction(1f)
        assertEquals(2f, target.value, 0f)
    }

    @Test fun testEndCallbackUsesHalfwayThresholdAndCancelIsOnceOnly() {
        for ((fraction, expected) in listOf(0.49f to false, 0.5f to false, 0.51f to true)) {
            val calls = mutableListOf<Boolean>()
            val listener = AnimatorListeners.forEndCallback { success: Boolean -> calls.add(success) }
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                interpolator = Interpolators.LINEAR
                setCurrentFraction(fraction)
            }
            listener.onAnimationEnd(animator)
            listener.onAnimationEnd(animator)
            assertEquals(listOf(expected), calls)
        }
        val calls = mutableListOf<Boolean>()
        val listener = AnimatorListeners.forEndCallback { success: Boolean -> calls.add(success) }
        val animator = ValueAnimator.ofFloat(0f, 1f).apply { setCurrentFraction(1f) }
        listener.onAnimationCancel(animator)
        listener.onAnimationEnd(animator)
        assertEquals(listOf(false), calls)
    }

    @Test fun testRootCancelBlocksSeekingUntilNextDispatchStart() {
        val target = Target()
        val pending = PendingAnimation(200)
        pending.addFloat(target, property, 2f, 10f, Interpolators.LINEAR)
        val controller = pending.createPlaybackController()
        controller.dispatchOnStart()
        controller.setPlayFraction(0.5f)
        assertEquals(6f, target.value, 0.001f)
        controller.dispatchOnCancel()
        controller.setPlayFraction(1f)
        assertEquals(6f, target.value, 0.001f)
        controller.dispatchOnStart()
        controller.setPlayFraction(1f)
        assertEquals(10f, target.value, 0.001f)
    }

    @Test fun testSetFloatCapturesStartAtFirstSeekNotAssembly() {
        val target = Target()
        val pending = PendingAnimation(200)
        var reads = 0
        val counted = object : FloatProperty<Target>("counted") {
            override fun get(target: Target): Float { reads++; return target.value }
            override fun setValue(target: Target, value: Float) { target.value = value }
        }
        pending.setFloat(target, counted, 10f, Interpolators.LINEAR)
        assertEquals("Assembly only checks for an unchanged value", 1, reads)
        val controller = pending.createPlaybackController()
        target.value = 6f
        controller.setPlayFraction(0.5f)
        assertEquals(8f, target.value, 0.001f)
        // Once initialized, subsequent seeks retain that start value.
        target.value = 100f
        controller.setPlayFraction(0.25f)
        assertEquals(7f, target.value, 0.001f)
    }

    @Test fun testAddFloatKeepsExplicitStartEvenIfTargetChangesBeforeSeek() {
        val target = Target()
        val pending = PendingAnimation(200)
        pending.addFloat(target, property, 2f, 10f, Interpolators.LINEAR)
        target.value = 6f
        pending.createPlaybackController().setPlayFraction(0.5f)
        assertEquals(6f, target.value, 0.001f)
    }

    @Test fun testPendingNestedSetPropagatesDurationAndInterpolatorBeforeHolders() {
        val leaf = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 20
            interpolator = Interpolators.LINEAR
        }
        val inner = AnimatorSet().apply { playTogether(leaf); duration = 50 }
        val outer = AnimatorSet().apply { playTogether(inner); duration = 100 }
        val curve = android.animation.TimeInterpolator { it * it }
        val pending = PendingAnimation(200).add(outer, curve)
        val controller = pending.createPlaybackController()
        assertEquals(200L, inner.duration)
        assertEquals(200L, leaf.duration)
        assertSame(curve, leaf.interpolator)
        controller.setPlayFraction(0.5f)
        assertEquals(0.25f, leaf.animatedValue as Float, 0.001f)
        leaf.interpolator = Interpolators.LINEAR
        controller.pause()
        assertSame("Holder reset must restore the inherited curve", curve, leaf.interpolator)
    }

    @Test fun testWrapNestedSetPropagatesBeforeComputingRelativeDuration() {
        val leaf = ValueAnimator.ofFloat(0f, 1f).apply { duration = 20 }
        val inner = AnimatorSet().apply { playTogether(leaf); duration = 50 }
        val curve = android.animation.TimeInterpolator { it * it }
        val root = AnimatorSet().apply {
            playTogether(inner)
            duration = 100
            interpolator = curve
        }
        val controller = AnimatorPlaybackController.wrap(root, 200)
        assertEquals(100L, leaf.duration)
        assertSame(curve, leaf.interpolator)
        controller.setPlayFraction(0.25f)
        assertEquals(0.25f, leaf.animatedValue as Float, 0.001f)
        controller.setPlayFraction(0.75f)
        assertEquals(1f, leaf.animatedValue as Float, 0f)
    }

    @Test fun testUnsetOrZeroParentDurationAndNullCurveRetainChildSettings() {
        // Match OEM's strict duration > 0 rule; this is not root.start() scheduling.
        for (parentDuration in listOf(-1L, 0L)) {
            val curve = android.animation.TimeInterpolator { it * it }
            val leaf = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 100
                interpolator = curve
            }
            val root = AnimatorSet().apply {
                playTogether(AnimatorSet().apply { playTogether(leaf) })
                if (parentDuration == 0L) duration = 0
            }
            val controller = PendingAnimation(200).addWithoutDuration(root).createPlaybackController()
            assertEquals(100L, leaf.duration)
            assertSame(curve, leaf.interpolator)
            controller.setPlayFraction(0.25f)
            assertEquals(0.25f, leaf.animatedValue as Float, 0.001f)
        }
    }

    @Test fun testRepeatedWrapperBuildDoesNotDuplicatePropertyWrites() {
        val target = Target()
        var writes = 0
        val counted = object : FloatProperty<Target>("counted") {
            override fun get(target: Target): Float = target.value
            override fun setValue(target: Target, value: Float) { writes++; target.value = value }
        }
        val wrapper = PendingAnimation.ObjectAnimator.ofFloat(target, counted, 2f, 10f)
            .setInterpolator(Interpolators.LINEAR)
        val animator = wrapper.buildAnimator()
        assertSame(animator, wrapper.buildAnimator())
        animator.setCurrentFraction(0.5f)
        assertEquals(1, writes)
        assertEquals(6f, target.value, 0.001f)
    }

    @Test fun testBuildAndControllerAreCachedWithoutDuplicatingProgressCallbacks() {
        var frames = 0
        val endings = mutableListOf<Boolean>()
        val pending = PendingAnimation(200).addOnFrameCallback { frames++ }
            .addEndListener { endings.add(it) }
        val root = pending.buildAnim()
        assertSame(root, pending.buildAnim())
        val controller = pending.createPlaybackController()
        assertSame(controller, pending.createPlaybackController())
        assertEquals(1, root.childAnimations.size)
        controller.dispatchOnStart()
        controller.setPlayFraction(1f)
        controller.dispatchOnEnd()
        assertEquals(1, frames)
        assertEquals(listOf(true), endings)
        assertTrue(pending.isAnimFinished)
    }
    @Test fun testNegativeDurationNormalizesToZeroAndStillAppliesEndValue() {
        val target = Target()
        val pending = PendingAnimation(-100).addFloat(target, property, 2f, 10f, Interpolators.LINEAR)
        assertEquals(0L, pending.buildAnim().childAnimations.single().duration)
        pending.createPlaybackController().setPlayFraction(0f)
        assertEquals(10f, target.value, 0f)
    }

    @Test fun testFinishedFlagTracksExplicitRootEventsAndResetsOnStart() {
        val pending = PendingAnimation(100)
        val controller = pending.createPlaybackController()
        assertFalse(pending.isAnimFinished)
        controller.dispatchOnStart()
        assertFalse(pending.isAnimFinished)
        controller.dispatchOnCancel()
        assertTrue(pending.isAnimFinished)
        controller.dispatchOnStart()
        assertFalse(pending.isAnimFinished)
        controller.dispatchOnEnd()
        assertTrue(pending.isAnimFinished)
    }

    @Test fun testAddWithoutDurationPreservesChildTimingComparedWithAddOverride() {
        val short = ValueAnimator.ofFloat(0f, 1f).apply { duration = 40; interpolator = Interpolators.LINEAR }
        val overridden = ValueAnimator.ofFloat(0f, 1f).apply { duration = 40; interpolator = Interpolators.LINEAR }
        val pending = PendingAnimation(100).addWithoutDuration(short).add(overridden)
        assertEquals(40L, short.duration)
        assertEquals(100L, overridden.duration)
        pending.createPlaybackController().setPlayFraction(0.4f)
        assertEquals(1f, short.animatedValue as Float, 0.0001f)
        assertEquals(0.4f, overridden.animatedValue as Float, 0.0001f)
    }

    @Test fun testWrapperFluentConfigurationAndLifecycleReachStableBackingAnimator() {
        val target = Target()
        val wrapper = PendingAnimation.ObjectAnimator.ofFloat(target, property, 2f, 10f)
        assertSame(wrapper, wrapper.setInterpolator(Interpolators.LINEAR))
        assertSame(wrapper, wrapper.setDuration(100))
        assertSame(wrapper, wrapper.setFloatValues(0f, 1f))
        var updates = 0
        assertSame(wrapper, wrapper.addUpdateListener { updates++ })
        var starts = 0
        var ends = 0
        wrapper.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationStart(animator: android.animation.Animator) { starts++ }
            override fun onAnimationEnd(animator: android.animation.Animator) { ends++ }
        })
        val backing = wrapper.buildAnimator()
        assertEquals(100L, wrapper.duration)
        try {
            wrapper.start()
            wrapper.end()
            assertSame(backing, wrapper.buildAnimator())
            assertFalse(wrapper.isRunning())
            assertEquals(1, starts)
            assertEquals(1, ends)
            assertTrue(updates > 0)
            assertEquals(10f, target.value, 0.0001f)
        } finally { wrapper.cancel() }
    }
}
