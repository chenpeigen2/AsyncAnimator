package com.asyncanimator.thread

import android.os.Looper
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A nonredundant package gives this cold-initialization test a fresh sandbox.
@Config(sdk = [36], manifest = Config.NONE, instrumentedPackages = ["com.asyncanimator.thread"])
class ExecutorInitializationTest {
    @Test fun testMainLookupDoesNotStartAnimationExecutorAndConcurrentFirstAccessIsSingleton() {
        assertSame(Looper.getMainLooper(), Executors.MAIN_EXECUTOR.getLooper())
        val delegate = AnimationControlThread::class.java.getDeclaredField("instance\$delegate")
            .apply { isAccessible = true }.get(null) as Lazy<*>
        assertFalse("main lookup must leave the animation thread uninitialized", delegate.isInitialized())
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val results = ConcurrentLinkedQueue<LooperExecutor>()
        val failure = AtomicReference<Throwable?>()
        val workers = List(8) {
            Thread {
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    results.add(Executors.ANIM_CONTROL_EXECUTOR)
                } catch (t: Throwable) { failure.set(t) }
                finally { done.countDown() }
            }.apply { start() }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        workers.forEach { it.join(1000); assertFalse(it.isAlive) }
        failure.get()?.let { throw AssertionError("concurrent executor lookup", it) }
        assertTrue(delegate.isInitialized())
        val executor = Executors.ANIM_CONTROL_EXECUTOR
        assertEquals(8, results.size)
        results.forEach { assertSame(executor, it) }
        assertNotSame(Looper.getMainLooper(), executor.getLooper())
        assertEquals(AnimationControlThread.THREAD_NAME, checkNotNull(executor.getThread()).name)
        assertTrue(checkNotNull(executor.getThread()).isAlive)
        // This is the library process singleton, not a fixture-owned HandlerThread: do not quit it.
    }
}
