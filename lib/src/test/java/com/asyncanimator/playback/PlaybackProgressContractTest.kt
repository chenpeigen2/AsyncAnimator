package com.asyncanimator.playback

import android.animation.AnimatorSet
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackProgressContractTest {
    private fun child(duration: Long = 100) = ValueAnimator.ofFloat(2f, 10f).apply {
        this.duration = duration
        interpolator = Interpolators.LINEAR
    }

    @Test fun testHolderRelativeDurationStopsAtChildEndButLongChildKeepsFraction() {
        for ((duration, expected) in listOf(50L to 10f, 100L to 10f, 200L to 6f)) {
            val animator = child(duration)
            val holder = AnimatorPlaybackController.Holder(animator, 100f)
            assertSame(animator, holder.anim)
            assertNull(holder.springProperty)
            assertEquals(duration / 100f, holder.globalEndProgress, 0f)
            holder.setProgress(1f)
            assertEquals(expected, animator.animatedValue as Float, 0.0001f)
        }
    }

    @Test fun testCustomMapperReceivesUnmodifiedGlobalValuesAndResetRestoresOriginalCurve() {
        val animator = child(50)
        val original = animator.interpolator
        val holder = AnimatorPlaybackController.Holder(animator, 100f)
        val inputs = mutableListOf<Pair<Float, Float>>()
        holder.mapper = { f, end -> inputs.add(f to end); 0.25f }
        animator.interpolator = TimeInterpolator { it * it }
        holder.setProgress(0.8f)
        assertEquals(listOf(0.8f to 0.5f), inputs)
        assertEquals(2.5f, animator.animatedValue as Float, 0.0001f)
        holder.reset()
        assertSame(original, animator.interpolator)
        holder.setProgress(0.25f)
        assertEquals(6f, animator.animatedValue as Float, 0.0001f)
        assertEquals(1, inputs.size)
    }

    @Test fun testControllerKeepsRawProgressButClampsChildSeekAndCopiesHolderList() {
        val animator = child()
        val holders = mutableListOf(AnimatorPlaybackController.Holder(animator, 100f))
        val controller = AnimatorPlaybackController(AnimatorSet(), 100, holders)
        holders.clear()
        for ((fraction, expected) in listOf(-2f to 2f, 0.5f to 6f, 3f to 10f)) {
            controller.setPlayFraction(fraction)
            assertEquals(fraction, controller.progressFraction, 0f)
            assertEquals(expected, animator.animatedValue as Float, 0.0001f)
        }
    }

    @Test fun testRootCancelSuppressesChildUpdatesButEndReenablesSeeking() {
        val root = AnimatorSet()
        val animator = child()
        val controller = AnimatorPlaybackController(root, 100,
            listOf(AnimatorPlaybackController.Holder(animator, 100f)))
        controller.setPlayFraction(0.25f)
        controller.dispatchOnCancel()
        controller.setPlayFraction(0.75f)
        assertEquals(0.75f, controller.progressFraction, 0f)
        assertEquals(4f, animator.animatedValue as Float, 0.0001f)
        controller.dispatchOnEnd()
        controller.setPlayFraction(0.75f)
        assertEquals(8f, animator.animatedValue as Float, 0.0001f)
    }

    @Test fun testDurationClampCoversNegativeFractionRoundingAndOvershoot() {
        val controller = AnimatorPlaybackController(AnimatorSet(), 101, emptyList())
        for ((fraction, expected) in listOf(-1f to 0L, 0f to 0L, 0.5f to 50L, 1f to 101L, 2f to 101L)) {
            assertEquals(expected, controller.clampDuration(fraction))
        }
        assertEquals(0L, AnimatorPlaybackController(AnimatorSet(), 0, emptyList()).clampDuration(1f))
    }

    @Test fun testStartAndReverseUseRemainingDistanceAndPauseResetsHolderOverrides() {
        val animator = child()
        val holder = AnimatorPlaybackController.Holder(animator, 100f)
        val controller = AnimatorPlaybackController(AnimatorSet(), 200, listOf(holder))
        try {
            controller.setPlayFraction(0.25f)
            controller.start()
            assertEquals(150L, controller.animationPlayer.duration)
            controller.pause()
            controller.setPlayFraction(0.75f)
            controller.reverse()
            assertEquals(150L, controller.animationPlayer.duration)
            holder.mapper = { _, _ -> 0f }
            animator.interpolator = TimeInterpolator { 0f }
            controller.pause()
            assertSame(Interpolators.LINEAR, animator.interpolator)
            controller.setPlayFraction(0.5f)
            assertEquals(6f, animator.animatedValue as Float, 0.0001f)
        } finally { controller.pause() }
    }

    @Test fun testUpdateListenerUsesFloatAnimatedValueNotIntegerOrAnimatedFraction() {
        val controller = AnimatorPlaybackController(AnimatorSet(), 100, emptyList())
        val source = ValueAnimator.ofFloat(4f, 8f).apply {
            interpolator = Interpolators.LINEAR
            setCurrentFraction(0.5f)
        }
        controller.onAnimationUpdate(source)
        assertEquals(6f, controller.progressFraction, 0f)
        val integer = ValueAnimator.ofInt(0, 10).apply { setCurrentFraction(0.5f) }
        controller.onAnimationUpdate(integer)
        assertEquals(6f, controller.progressFraction, 0f)
    }
}
