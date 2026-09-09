package com.asyncanimator.control

import org.junit.Assert.assertEquals
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36], manifest = org.robolectric.annotation.Config.NONE)
class TaskStateChangeTimeOutListenerTest {
    private val type = TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH

    @Test
    fun testMatchingEventRunsOnlyOnce() {
        var calls = 0
        val listener = TaskStateChangeTimeOutListener(type, 100) { calls++ }
        listener.onTimeOut(type, 100)
        listener.onTimeOut(type, 100)
        assertEquals(1, calls)
    }

    @Test
    fun testDisposedListenerCannotRun() {
        var calls = 0
        val listener = TaskStateChangeTimeOutListener(type, 100) { calls++ }
        listener.dispose()
        listener.onTimeOut(type, 100)
        assertEquals(0, calls)
    }

    @Test
    fun testUnrelatedEventDoesNotConsumeListener() {
        var calls = 0
        val listener = TaskStateChangeTimeOutListener(type, 100) { calls++ }
        listener.onTimeOut(TaskStateChangeTimeOutListener.Type.ON_LAND_SCAPE_SCENE_EXIT, 100)
        assertEquals(0, calls)
        listener.onTimeOut(type, 100)
        assertEquals(1, calls)
    }

    @Test
    fun testTimerThenEventRunsOnlyOnce() {
        var calls = 0
        val listener = TaskStateChangeTimeOutListener(type, 100) { calls++ }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(100))
        assertEquals(1, calls)
        listener.onTimeOut(type, 100)
        assertEquals(1, calls)
    }

    @Test
    fun testDisposeCancelsScheduledTimer() {
        var calls = 0
        val listener = TaskStateChangeTimeOutListener(type, 100) { calls++ }
        listener.dispose()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(100))
        assertEquals(0, calls)
    }
}
