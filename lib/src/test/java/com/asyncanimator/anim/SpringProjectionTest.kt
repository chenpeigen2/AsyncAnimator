package com.asyncanimator.anim

import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class SpringProjectionTest {
    private fun force(damping: Float, final: Float = 10f) = SpringForce(final).apply {
        stiffness = 200f
        dampingRatio = damping
    }

    @Test fun testZeroTimeIsIdentityForAllDampingBranches() {
        for (damping in listOf(0f, 0.5f, 1f, 2f)) {
            val result = SpringProjection.advance(force(damping), -3f, 17f, 0)
            assertEquals(-3f, result.first, 0.00001f)
            assertEquals(17f, result.second, 0.00001f)
        }
    }

    @Test fun testEquilibriumRemainsStationaryAtAnyPreviewTime() {
        for (damping in listOf(0f, 0.2f, 1f, 4f)) {
            for (time in listOf(0L, 16L, 1000L, 100000L)) {
                val result = SpringProjection.advance(force(damping), 10f, 0f, time)
                assertEquals(10f, result.first, 0f)
                assertEquals(0f, result.second, 0f)
            }
        }
    }

    // Independent small-step integration of acceleration, not a copy of production's closed form.
    private fun integrate(damping: Float, millis: Int): Pair<Float, Float> {
        var position = -3.0
        var velocity = 17.0
        val dt = 0.00001
        val friction = 2 * damping * sqrt(200.0)
        repeat(millis * 100) {
            val acceleration = -200 * (position - 10) - friction * velocity
            velocity += acceleration * dt
            position += velocity * dt
        }
        return position.toFloat() to velocity.toFloat()
    }

    @Test fun testUnderCriticalAndOverDampedSolutionsMatchIndependentIntegration() {
        for (damping in listOf(0f, 0.5f, 1f, 2f)) {
            val actual = SpringProjection.advance(force(damping), -3f, 17f, 120)
            val reference = integrate(damping, 120)
            assertEquals("position damping=$damping", reference.first, actual.first, 0.01f)
            assertEquals("velocity damping=$damping", reference.second, actual.second, 0.03f)
        }
    }

    @Test fun testPreviewCompositionAgreesWithOneLongStep() {
        for (damping in listOf(0.3f, 1f, 2f)) {
            val spring = force(damping)
            val first = SpringProjection.advance(spring, -3f, 17f, 50)
            val second = SpringProjection.advance(spring, first.first, first.second, 70)
            val whole = SpringProjection.advance(spring, -3f, 17f, 120)
            assertEquals(whole.first, second.first, 0.0001f)
            assertEquals(whole.second, second.second, 0.001f)
        }
    }

    @Test fun testTranslationDoesNotChangeVelocityAndPreviewDoesNotMutateForce() {
        val spring = force(0.5f)
        val before = Triple(spring.finalPosition, spring.stiffness, spring.dampingRatio)
        val result = SpringProjection.advance(spring, -3f, 17f, 90)
        val shifted = SpringProjection.advance(force(0.5f, 110f), 97f, 17f, 90)
        assertEquals(result.first + 100, shifted.first, 0.0001f)
        assertEquals(result.second, shifted.second, 0.0001f)
        assertEquals(before, Triple(spring.finalPosition, spring.stiffness, spring.dampingRatio))
        assertEquals(result, SpringProjection.advance(spring, -3f, 17f, 90))
    }

    @Test fun testPositiveDampingConvergesForLongPreviewWithoutNaN() {
        for (damping in listOf(0.5f, 1f, 2f)) {
            val result = SpringProjection.advance(force(damping), -100f, 1000f, 10000)
            assertEquals(10f, result.first, 0.001f)
            assertEquals(0f, result.second, 0.001f)
        }
    }
}
