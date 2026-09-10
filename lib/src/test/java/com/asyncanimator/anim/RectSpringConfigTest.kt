package com.asyncanimator.anim

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class RectSpringConfigTest {
    private val closing = CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME
    private val opening = CustomRectFSpringAnim.AnimType.OPEN_FROM_HOME

    @Test fun testDefaultAxisOrderAndThresholds() {
        val config = RectSpringConfig()
        val springs = config.parameters(closing)
        assertEquals(6, springs.size)
        assertEquals(List(6) { 200f }, springs.map { it.stiffness })
        assertEquals(List(6) { 1f }, springs.map { it.dampingRatio })
        assertEquals(listOf(0.1f, 0.1f, 0.1f, 0.005f, 1f, 0.05f), springs.map { it.minimumVisibleChange })
        assertEquals(RectSpringConfig.Tracking.TOP, config.tracking)
    }

    @Test fun testEachSpringRejectsInvalidStiffnessDampingAndThreshold() {
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { RectSpringConfig.Spring(stiffness = bad) }
            assertThrows(IllegalArgumentException::class.java) { RectSpringConfig.Spring(minimumVisibleChange = bad) }
        }
        for (bad in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { RectSpringConfig.Spring(dampingRatio = bad) }
        }
        assertEquals(0f, RectSpringConfig.Spring(dampingRatio = 0f).dampingRatio, 0f)
    }

    @Test fun testConfigRejectsInvalidBoundsAndNonFiniteDerivedStiffness() {
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { RectSpringConfig(minimumSize = bad) }
        }
        for (bad in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, Float.MIN_VALUE, Float.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { RectSpringConfig(durationMultiplier = bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { RectSpringConfig(alphaStartDelayMillis = -1) }
        assertEquals(Long.MAX_VALUE, RectSpringConfig(alphaStartDelayMillis = Long.MAX_VALUE).alphaStartDelayMillis)
    }

    @Test fun testAllAnimTypesSelectOpeningThresholdsOnlyWhereSpecified() {
        for (type in CustomRectFSpringAnim.AnimType.entries) {
            val thresholds = RectSpringConfig().parameters(type).map { it.minimumVisibleChange }
            val isOpening = type == opening || type == CustomRectFSpringAnim.AnimType.REVERSE_TO_OPEN
            assertEquals(if (isOpening) listOf(1f, 1f, 1f, 0.001f, 1f, 0.05f)
                else listOf(0.1f, 0.1f, 0.1f, 0.005f, 1f, 0.05f), thresholds)
        }
    }

    @Test fun testTabletFoldAndWidePoliciesAreIndependent() {
        for (mask in 0..7) {
            val config = RectSpringConfig(tablet = mask and 1 != 0,
                foldExpanded = mask and 2 != 0, foldWide = mask and 4 != 0)
            assertEquals(if (mask and 3 != 0) 0.1f else 1f,
                config.parameters(opening)[1].minimumVisibleChange, 0f)
            assertEquals(if (mask and 4 != 0) 0.001f else 0.005f,
                config.parameters(closing)[3].minimumVisibleChange, 0f)
            assertEquals(0.001f, config.parameters(opening)[3].minimumVisibleChange, 0f)
        }
    }

    @Test fun testDurationLightEvaluationAndDragPoliciesDoNotMutateInputSprings() {
        val config = RectSpringConfig(durationMultiplier = 2f)
        assertEquals(50f, config.parameters(closing)[0].stiffness, 0.001f)
        assertEquals(312.5f, config.copy(lightAnimation = true).parameters(closing)[0].stiffness, 0.001f)
        assertEquals(200f / 1.44f, config.copy(evaluationScene = true).parameters(closing)[0].stiffness, 0.001f)
        assertEquals(200f, config.copy(durationMultiplier = 0f, lightAnimation = true).parameters(closing)[0].stiffness, 0f)
        assertEquals(200f, config.copy(lightAnimation = true, evaluationScene = true)
            .parameters(CustomRectFSpringAnim.AnimType.GESTURE_TO_DRAG)[0].stiffness, 0f)
        assertEquals(200f, config.centerX.stiffness, 0f)
    }

    @Test fun testCustomAxesAndTrackingCopiesKeepIndependentParameters() {
        val axes = (1..6).map { RectSpringConfig.Spring(it * 100f, it / 10f, it / 100f) }
        val original = RectSpringConfig(axes[0], axes[1], axes[2], axes[3], axes[4], axes[5])
        for (tracking in RectSpringConfig.Tracking.entries) {
            val copy = original.copy(tracking = tracking)
            assertEquals(axes, copy.parameters(closing))
            assertEquals(tracking, copy.tracking)
        }
        assertEquals(RectSpringConfig.Tracking.TOP, original.tracking)
    }

    @Test fun testScalarArrayRoundTripIsOrderedAndDefensivelyIndependent() {
        val values = RectSpringValues(1f, 2f, 3f, 4f, 5f, 6f)
        val array = values.array()
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), array, 0f)
        val copy = RectSpringValues.from(array)
        array.fill(100f)
        assertEquals(values, copy)
        assertEquals(1f, values.array()[0], 0f)
        assertEquals(RectSpringValues(), RectSpringValues.from(FloatArray(6)))
    }

    @Test fun testFrameRectAccessCannotMutateStoredBoundsOrCopiedFrame() {
        val frame = RectSpringFrame(1f, 2f, 11f, 22f, RectSpringValues(size = 10f),
            RectSpringValues(size = 3f), 0.25f, true)
        val rect = frame.rect
        rect.set(0f, 0f, 0f, 0f)
        assertNotSame(rect, frame.rect)
        assertEquals(10f, frame.rect.width(), 0f)
        assertEquals(20f, frame.rect.height(), 0f)
        val copy = frame.copy(progress = 0.75f, sizeIsWidth = false)
        assertEquals(0.25f, frame.progress, 0f)
        assertTrue(frame.sizeIsWidth)
        assertEquals(frame.values, copy.values)
        assertEquals(frame.velocities, copy.velocities)
        assertFalse(copy.sizeIsWidth)
    }
}
