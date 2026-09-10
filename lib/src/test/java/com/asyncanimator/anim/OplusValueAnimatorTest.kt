package com.asyncanimator.anim

import android.animation.TimeInterpolator
import android.util.FloatProperty
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class OplusValueAnimatorTest {
    @Test fun testParamCopyHasIndependentScalarFieldsButSharesExplicitCallbackReferences() {
        var lastValue: Any? = null
        val callback: ValueApplicator = { lastValue = it }
        val interpolator = TimeInterpolator { it }
        val source = OplusValueAnimator.AnimParam(
            currentFraction = 0.25f, interpolator = interpolator, applicator = callback)
        val copy = source.copy()
        assertNotSame(source, copy)
        copy.currentFraction = 0.75f
        assertEquals(0.25f, source.currentFraction, 0f)
        assertSame(source.interpolator, copy.interpolator)
        assertSame(source.applicator, copy.applicator)
        copy.applicator!!.invoke(42f)
        assertEquals(42f, lastValue)
    }

    @Test fun testRecordInputStartsAtZeroAndTracksUntransformedInput() {
        val record = RecordInputInterpolator(TimeInterpolator { it * it })
        assertEquals(0f, record.inputed, 0f)
        assertEquals(0.25f, record.getInterpolation(0.5f), 0f)
        assertEquals(0.5f, record.inputed, 0f)
    }

    @Test fun testRuntimeInterpolatorAndDurationAreCopiedIntoParam() {
        val animator = OplusValueAnimator<Any>()
        val record = RecordInputInterpolator(TimeInterpolator { it })
        animator.interpolator = record
        animator.duration = 700
        assertSame(record, animator.param.interpolator)
        assertEquals(700L, animator.param.duration)
    }

    @Test fun testContinuationStartsAtRecordedInputAndAppliesRealValuesToEnd() {
        ShadowChoreographer.setPaused(true)
        val values = mutableListOf<Float>()
        val source = OplusValueAnimator<Any>()
        source.setFloatValues(2f, 10f)
        source.param.applicator = { values.add(it as Float) }
        source.interpolator = RecordInputInterpolator(TimeInterpolator { it * it })
        source.setCurrentFraction(0.5f)
        assertEquals(4f, values.last(), 0.001f)
        val continuation = OplusValueAnimator.generateContinuationAnim(source, 200)!!
        assertNotSame(source.param, continuation.param)
        assertEquals(200L, continuation.duration)
        values.clear()
        continuation.start()
        assertEquals(4f, values.first(), 0.001f)
        continuation.end()
        assertEquals(10f, values.last(), 0.001f)
        assertEquals(1f, continuation.param.currentFraction, 0f)
        assertEquals(0.5f, source.param.currentFraction, 0f)
    }

    @Test fun testContinuationRetainsConfiguredKeyframesWithoutSharingValueHolders() {
        val source = OplusValueAnimator<Any>()
        source.setFloatValues(2f, 8f, 10f)
        source.interpolator = TimeInterpolator { it }
        source.param.currentFraction = 0.25f
        val continuation = OplusValueAnimator.generateContinuationAnim(source, 200)!!
        assertNotSame(source.values.single(), continuation.values.single())
        // Editing the source after copying must not change the continuation's value range.
        source.setFloatValues(100f, 200f)
        continuation.setCurrentFraction(0.5f)
        assertEquals(8f, continuation.animatedValue as Float, 0.001f)
    }

    @Test fun testInvalidContinuationFractionsAndNullSourceAreRejected() {
        assertNull(OplusValueAnimator.generateContinuationAnim<Any>(null, 100))
        for (fraction in listOf(-1f, 1f, 1.5f)) {
            val source = OplusValueAnimator<Any>()
            source.param.currentFraction = fraction
            assertNull(OplusValueAnimator.generateContinuationAnim(source, 100))
        }
    }

    @Test fun testTimeControllerUsesCurrentTargetAndPropertyWithoutDuplicateListeners() {
        val first = OplusValueAnimator<Any>().apply { setFloatValues(0f, 1f) }
        val second = OplusValueAnimator<Any>().apply { setFloatValues(0f, 1f) }
        val writes = mutableListOf<Pair<OplusValueAnimator<*>, Float>>()
        val property = object : FloatProperty<OplusValueAnimator<*>>("record") {
            override fun get(target: OplusValueAnimator<*>): Float = 0f
            override fun setValue(target: OplusValueAnimator<*>, value: Float) {
                writes.add(target to value)
            }
        }
        val controller = OplusValueAnimator.TimeControllerObjectAnimator()
        val player = controller.buildAnimator().apply { interpolator = TimeInterpolator { it } }
        controller.setFloatValues(0.4f, 1f)
        controller.setProperty(property)
        controller.setTarget(first)
        player.setCurrentFraction(0.5f)
        assertEquals(1, writes.size)
        assertSame(first, writes.single().first)
        assertEquals(0.7f, writes.single().second, 0.001f)
        writes.clear()
        controller.setTarget(second)
        controller.setTarget(second)
        player.setCurrentFraction(1f)
        assertEquals(listOf(second to 1f), writes)
        writes.clear()
        controller.setProperty(null)
        player.setCurrentFraction(0.5f)
        assertTrue(writes.isEmpty())
        controller.setProperty(property)
        controller.setTarget(null)
        player.setCurrentFraction(1f)
        assertTrue(writes.isEmpty())
    }
    @Test fun testContinuationClonesPlatformIntValueHoldersWithoutChangingOutputType() {
        val source = OplusValueAnimator<Any>()
        source.setIntValues(10, 90)
        source.interpolator = TimeInterpolator { it }
        source.param.currentFraction = 0.25f
        val output = mutableListOf<Any?>()
        source.param.applicator = { output.add(it) }
        val continuation = OplusValueAnimator.generateContinuationAnim(source, 200)!!
        assertNotSame(source.values.single(), continuation.values.single())
        continuation.setCurrentFraction(0.5f)
        assertEquals(50, output.last())
        assertTrue(output.last() is Int)
        continuation.setCurrentFraction(1f)
        assertEquals(90, output.last())
    }

    @Test fun testTimeAndDurationQueriesDelegateToControllerPlayer() {
        val controller = OplusValueAnimator.TimeControllerObjectAnimator()
        val wrapper = OplusValueAnimator<Any>(OplusValueAnimator.AnimParam(), controller)
        wrapper.duration = 400
        controller.buildAnimator().currentPlayTime = 75
        assertEquals(75L, wrapper.currentPlayTime)
        assertEquals(400L, wrapper.duration)
        assertEquals(400L, wrapper.param.duration)
        assertEquals(400L, controller.buildAnimator().duration)
    }

    @Test fun testNonpositiveContinuationDurationFallsBackToCopiedDuration() {
        val source = OplusValueAnimator<Any>()
        source.duration = 700
        source.param.currentFraction = 0.25f
        for (duration in listOf(0L, -1L)) {
            val continuation = OplusValueAnimator.generateContinuationAnim(source, duration)!!
            assertEquals(700L, continuation.duration)
            assertEquals(700L, continuation.param.duration)
        }
    }

    @Test fun testValueTypeCanSwitchBackFromIntToFloat() {
        val animator = OplusValueAnimator<Any>()
        animator.interpolator = TimeInterpolator { it }
        animator.setIntValues(10, 90)
        animator.setCurrentFraction(0.5f)
        assertEquals(50, animator.animatedValue)
        animator.setFloatValues(2f, 10f)
        animator.setCurrentFraction(0.5f)
        assertEquals(6f, animator.animatedValue as Float, 0.001f)
    }


    @Test fun testDefaultParametersAndFactorySelectWrapperWithoutChangingValueDefinition() {
        val param = OplusValueAnimator.AnimParam()
        assertEquals("default", param.name)
        assertEquals(0f, param.fromValue, 0f)
        assertEquals(1f, param.toValue, 0f)
        assertEquals(-1f, param.currentFraction, 0f)
        assertNull(param.interpolator); assertNull(param.applicator)
        assertEquals(0L, param.duration)
        for (wrapped in listOf(false, true)) {
            val a = OplusValueAnimator.ofFloat(wrapped, 2f, 7f, 10f)
            assertEquals(wrapped, a is OplusValueAnimator<*>)
            a.interpolator = TimeInterpolator { it }
            a.setCurrentFraction(0.5f)
            assertEquals(7f, a.animatedValue as Float, 0f)
        }
    }

    @Test fun testEmptyTypedValueUpdatesKeepExistingDefinitionAndDurationFailureKeepsParam() {
        val a = OplusValueAnimator<Any>().apply {
            setIntValues(4, 12)
            interpolator = TimeInterpolator { it }
            duration = 80
        }
        a.setIntValues(); a.setFloatValues()
        a.setCurrentFraction(0.5f)
        assertEquals(8, a.animatedValue)
        assertSame(a, a.setDuration(0))
        assertEquals(0L, a.param.duration)
        assertThrows(IllegalArgumentException::class.java) { a.duration = -1 }
        assertEquals(0L, a.duration)
        assertEquals(0L, a.param.duration)
    }

    @Test fun testTimeControllerDelegatesStartPauseCancelEndAndNativeListenerIdentity() {
        ShadowChoreographer.setPaused(true)
        val controller = OplusValueAnimator.TimeControllerObjectAnimator()
        val a = OplusValueAnimator<Any>(OplusValueAnimator.AnimParam(name = "delegated"), controller)
        val player = controller.buildAnimator()
        val calls = mutableListOf<String>()
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: android.animation.Animator) {
                assertSame(player, animation); calls.add("start")
            }
            override fun onAnimationCancel(animation: android.animation.Animator) { calls.add("cancel") }
            override fun onAnimationEnd(animation: android.animation.Animator) { calls.add("end") }
        })
        try {
            assertEquals("delegated", a.animName)
            a.start()
            assertTrue(a.isRunning)
            a.pause()
            assertTrue(player.isPaused)
            a.cancel()
            assertFalse(a.isRunning)
            assertEquals(listOf("start", "cancel", "end"), calls)
            calls.clear()
            a.start(); a.end()
            assertEquals(listOf("start", "end"), calls)
        } finally { a.cancel() }
    }

    @Test fun testControllerPropertyFailurePropagatesAndRebindingRecovers() {
        val controller = OplusValueAnimator.TimeControllerObjectAnimator()
        val a = OplusValueAnimator<Any>()
        val failure = IllegalStateException("property write")
        val property = object : FloatProperty<OplusValueAnimator<*>>("throwing") {
            override fun get(target: OplusValueAnimator<*>): Float = error("must not read")
            override fun setValue(target: OplusValueAnimator<*>, value: Float) { throw failure }
        }
        assertSame(controller, controller.setTarget(a))
        assertSame(controller, controller.setProperty(property))
        assertSame(controller, controller.setFloatValues(0f, 1f))
        assertSame(controller, controller.setDuration(100))
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            controller.buildAnimator().setCurrentFraction(0.5f)
        })
        controller.setProperty(null)
        controller.buildAnimator().setCurrentFraction(1f)
        assertEquals(1f, controller.buildAnimator().animatedValue as Float, 0f)
    }

    @Test fun testNullInterpolatorIsRecordedWhilePlatformUsesLinearValues() {
        val a = OplusValueAnimator<Any>()
        a.setFloatValues(10f, 30f)
        a.interpolator = null
        a.setCurrentFraction(0.25f)
        assertNull(a.param.interpolator)
        assertEquals(15f, a.animatedValue as Float, 0f)
        assertEquals(0.25f, a.param.currentFraction, 0f)
    }

    @Test fun testContinuationAcceptsZeroAndRejectsEveryNonFiniteFraction() {
        val source = OplusValueAnimator<Any>()
        source.param.currentFraction = 0f
        assertNotNull(OplusValueAnimator.generateContinuationAnim(source, 100))
        for (fraction in listOf(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NaN)) {
            source.param.currentFraction = fraction
            assertNull("invalid fraction=$fraction", OplusValueAnimator.generateContinuationAnim(source, 100))
        }
    }

}
