package com.asyncanimator.playback

import android.animation.TimeInterpolator
import android.util.FloatProperty
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class PropertySetterTest {
    private class Target(var value: Float = 2f, var writes: Int = 0)
    private val property = object : FloatProperty<Target>("value") {
        override fun get(target: Target) = error("Immediate setter must not read old value")
        override fun setValue(target: Target, value: Float) { target.value = value; target.writes++ }
    }
    private val unused = TimeInterpolator { error("Immediate setter must not interpolate") }

    @Test fun testNoAnimationSetterWritesImmediatelyWithoutReadOrInterpolation() {
        val target = Target()
        PropertySetter.NO_ANIM_PROPERTY_SETTER.setFloat(target, property, -8f, unused)
        assertEquals(-8f, target.value, 0f)
        assertEquals(1, target.writes)
    }

    @Test fun testDefaultInterfaceImplementationHasSameImmediateContract() {
        val setter = object : PropertySetter {}
        val target = Target()
        setter.setFloat(target, property, 2f, unused)
        setter.setFloat(target, property, 2f, unused)
        assertEquals(2, target.writes) // No equality-based suppression in the immediate implementation.
    }

    @Test fun testNullPropertyDoesNotTouchTargetOrInterpolator() {
        val target = Target()
        PropertySetter.NO_ANIM_PROPERTY_SETTER.setFloat(target, null, 10f, unused)
        assertEquals(2f, target.value, 0f)
        assertEquals(0, target.writes)
    }

    @Test fun testPropertyExceptionsPropagateWithoutFallbackWrite() {
        val target = Target()
        val failure = IllegalArgumentException("property failed")
        val throwing = object : FloatProperty<Target>("throwing") {
            override fun get(target: Target) = target.value
            override fun setValue(target: Target, value: Float) { throw failure }
        }
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) {
            PropertySetter.NO_ANIM_PROPERTY_SETTER.setFloat(target, throwing, 10f, unused)
        })
        assertEquals(0, target.writes)
    }

    @Test fun testImmediateSetterDoesNotClampNonFiniteValues() {
        val target = Target()
        for (value in listOf(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            PropertySetter.NO_ANIM_PROPERTY_SETTER.setFloat(target, property, value, unused)
            assertEquals(value, target.value, 0f)
        }
        PropertySetter.NO_ANIM_PROPERTY_SETTER.setFloat(target, property, Float.NaN, unused)
        assertTrue(target.value.isNaN())
        assertEquals(3, target.writes)
    }
}
