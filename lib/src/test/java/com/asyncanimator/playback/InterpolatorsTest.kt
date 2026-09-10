package com.asyncanimator.playback

import android.animation.TimeInterpolator
import org.junit.Assert.*
import org.junit.Test

class InterpolatorsTest {
    private val square = TimeInterpolator { it * it }

    @Test fun testClampScalesInputAndHoldsEndpoints() {
        val curve = Interpolators.clampToProgress(square, 0.25f, 0.75f)
        for ((input, expected) in listOf(0f to 0f, 0.25f to 0f, 0.5f to 0.25f,
                                        0.75f to 1f, 1f to 1f)) {
            assertEquals(expected, curve.getInterpolation(input), 0f)
            assertEquals(expected, Interpolators.clampToProgress(square, input, 0.25f, 0.75f), 0f)
        }
        assertEquals(0.5f, Interpolators.clampToProgress(0.5f, 0.25f, 0.75f), 0f)
    }

    @Test fun testZeroWidthClampMatchesVendorStepIncludingZero() {
        val atZero = Interpolators.clampToProgress(square, 0f, 0f)
        assertEquals(0f, atZero.getInterpolation(0f), 0f)
        assertEquals(1f, atZero.getInterpolation(0.01f), 0f)
        val atHalf = Interpolators.clampToProgress(square, 0.5f, 0.5f)
        assertEquals(0f, atHalf.getInterpolation(0.49f), 0f)
        assertEquals(1f, atHalf.getInterpolation(0.5f), 0f)
        assertEquals(1f, atHalf.getInterpolation(1f), 0f)
    }

    @Test fun testClampRejectsInvertedBoundsInBothOverloads() {
        assertThrows(IllegalArgumentException::class.java) {
            Interpolators.clampToProgress(square, 0.75f, 0.25f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Interpolators.clampToProgress(square, 0.5f, 0.75f, 0.25f)
        }
    }

    @Test fun testMapSupportsDescendingRangeAndDoesNotClipOutput() {
        assertEquals(4f, Interpolators.mapToProgress(square, 2f, 10f).getInterpolation(0.5f), 0f)
        assertEquals(8f, Interpolators.mapToProgress(square, 10f, 2f).getInterpolation(0.5f), 0f)
        assertEquals(18f, Interpolators.mapToProgress(TimeInterpolator { 2f }, 2f, 10f)
            .getInterpolation(1f), 0f)
    }

    @Test fun testReverseComplementsBothTimeAndOutput() {
        val reverse = Interpolators.reverse(square)
        assertEquals(0f, reverse.getInterpolation(0f), 0f)
        assertEquals(0.75f, reverse.getInterpolation(0.5f), 0f)
        assertEquals(1f, reverse.getInterpolation(1f), 0f)
    }

    @Test fun testVelocityThresholdIsStrictAndIndependentOfDirection() {
        for (velocity in listOf(-10f, 0f, 10f)) {
            assertSame(Interpolators.SCROLL_CUBIC, Interpolators.scrollInterpolatorForVelocity(velocity))
        }
        for (velocity in listOf(-10.01f, 10.01f)) {
            assertSame(Interpolators.SCROLL, Interpolators.scrollInterpolatorForVelocity(velocity))
        }
        assertEquals(0.875f, Interpolators.SCROLL_CUBIC.getInterpolation(0.5f), 0f)
        assertEquals(0.9375f, Interpolators.SCROLL.getInterpolation(0.5f), 0f)
    }
}
