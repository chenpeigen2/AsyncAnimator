package com.asyncanimator.thread

import android.os.Looper
import com.asyncanimator.core.AnimationHandler
import com.asyncanimator.core.ChoreographerTickScheduler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AsyncAnimWrapperTest {
    private class Wrapper : AsyncAnimWrapper() {
        fun main(action: (() -> Unit)?) = runOnMainThread(action)
        fun anim(action: (() -> Unit)?) = runOnAnimThread(action)
    }

    @Test fun testMainOwnerExecutesInlineAndNullActionsHaveNoEffect() {
        val wrapper = Wrapper()
        val calls = mutableListOf<String>()
        wrapper.main { calls.add("inline"); assertSame(Looper.getMainLooper(), Looper.myLooper()) }
        calls.add("after")
        wrapper.main(null); wrapper.anim(null)
        assertEquals(listOf("inline", "after"), calls)
    }

    @Test fun testWorkerToMainDeliveryIsDeferredUntilMainLoopRuns() {
        val wrapper = Wrapper()
        var called = false
        val worker = Thread { wrapper.main {
            called = true
            assertSame(Looper.getMainLooper(), Looper.myLooper())
        } }
        worker.start(); worker.join(5000)
        assertFalse(worker.isAlive)
        assertFalse(called)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(called)
    }

    @Test fun testAnimationOwnerHasInstalledSchedulerAndNestedTransferRunsInline() {
        val wrapper = Wrapper()
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val calls = mutableListOf<String>()
        wrapper.anim {
            try {
                assertSame(Executors.ANIM_CONTROL_EXECUTOR.getLooper(), Looper.myLooper())
                assertSame(AnimationControlThread.instance, Thread.currentThread())
                assertTrue(AnimationHandler.instance.scheduler is ChoreographerTickScheduler)
                calls.add("outer")
                wrapper.anim { calls.add("nested") }
                calls.add("after")
            } catch (t: Throwable) { failure.set(t) }
            finally { done.countDown() }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
        assertEquals(listOf("outer", "nested", "after"), calls)
        assertTrue(AnimationControlThread.instance.isAlive)
    }

    @Test fun testInlineBusinessExceptionIsNotSwallowed() {
        val failure = IllegalStateException("business failure")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            Wrapper().main { throw failure }
        })
    }
}
