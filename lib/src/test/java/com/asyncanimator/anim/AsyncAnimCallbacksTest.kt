package com.asyncanimator.anim

import com.asyncanimator.playback.NullableAnimatorListener
import com.asyncanimator.playback.NullableAnimatorListenerAdapter
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36], manifest = org.robolectric.annotation.Config.NONE)
class AsyncAnimCallbacksTest {
    @Suppress("UNCHECKED_CAST")
    private fun snapshot(callbacks: AsyncAnimCallbacks): List<NullableAnimatorListener> =
        AsyncAnimCallbacks::class.java.getDeclaredMethod("getListeners").let {
            it.isAccessible = true
            it.invoke(callbacks) as List<NullableAnimatorListener>
        }

    @Test
    fun testSnapshotIsStableAndRegistrationIsIdempotent() {
        val callbacks = AsyncAnimCallbacks()
        val listener = object : NullableAnimatorListenerAdapter() {}
        callbacks.addListener(listener)
        callbacks.addListener(listener)
        val before = snapshot(callbacks)
        callbacks.removeListener(listener)
        assertEquals(listOf(listener), before)
        assertTrue(snapshot(callbacks).isEmpty())
        callbacks.addListener(listener)
        callbacks.clearListeners()
        assertTrue(snapshot(callbacks).isEmpty())
    }

    @Test
    fun testConcurrentMutationAndSnapshots() {
        val callbacks = AsyncAnimCallbacks()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val jobs = (0 until 4).map {
                pool.submit {
                    val listener = object : NullableAnimatorListenerAdapter() {}
                    start.await()
                    repeat(2000) {
                        callbacks.addListener(listener)
                        val current = snapshot(callbacks)
                        assertEquals(current.distinct().size, current.size)
                        callbacks.removeListener(listener)
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(20, TimeUnit.SECONDS) }
            assertTrue(snapshot(callbacks).isEmpty())
        } finally {
            pool.shutdownNow()
        }
    }
}
