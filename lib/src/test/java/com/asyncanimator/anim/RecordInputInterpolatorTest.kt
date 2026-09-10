package com.asyncanimator.anim

import android.animation.TimeInterpolator
import org.junit.Assert.*
import org.junit.Test

class RecordInputInterpolatorTest {
    @Test fun testInitialStateDoesNotEvaluateDelegate() {
        var calls = 0
        val recorder = RecordInputInterpolator { calls++; it }
        assertEquals(0f, recorder.inputed, 0f)
        assertEquals(0, calls)
    }

    @Test fun testStoresInputRatherThanNonlinearOutputOnEveryCall() {
        val inputs = mutableListOf<Float>()
        val recorder = RecordInputInterpolator { inputs.add(it); it * it }
        for (input in listOf(0.2f, 0.8f, 0.1f, 1f, 0f)) {
            assertEquals(input * input, recorder.getInterpolation(input), 0f)
            assertEquals(input, recorder.inputed, 0f)
        }
        assertEquals(listOf(0.2f, 0.8f, 0.1f, 1f, 0f), inputs)
    }

    @Test fun testDoesNotClampExtrapolatedOrNonFiniteInput() {
        val recorder = RecordInputInterpolator { it }
        for (input in listOf(-1f, 2f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(input, recorder.getInterpolation(input), 0f)
            assertEquals(input, recorder.inputed, 0f)
        }
        assertTrue(recorder.getInterpolation(Float.NaN).isNaN())
        assertTrue(recorder.inputed.isNaN())
    }

    @Test fun testRecordsInputBeforeDelegateThrowsAndRecoversOnNextCall() {
        val failure = IllegalStateException("delegate failed")
        val recorder = RecordInputInterpolator { if (it == 0.5f) throw failure else it + 1f }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { recorder.getInterpolation(0.5f) })
        assertEquals(0.5f, recorder.inputed, 0f)
        assertEquals(1.75f, recorder.getInterpolation(0.75f), 0f)
        assertEquals(0.75f, recorder.inputed, 0f)
    }

    @Test fun testIndependentRecordersDoNotShareLastInputWithSharedDelegate() {
        val delegate = TimeInterpolator { 1f - it }
        val first = RecordInputInterpolator(delegate)
        val second = RecordInputInterpolator(delegate)
        first.getInterpolation(0.25f)
        second.getInterpolation(0.75f)
        assertEquals(0.25f, first.inputed, 0f)
        assertEquals(0.75f, second.inputed, 0f)
    }
}
