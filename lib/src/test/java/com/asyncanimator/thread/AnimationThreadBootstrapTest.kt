package com.asyncanimator.thread

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import com.asyncanimator.core.AnimationHandler
import com.asyncanimator.core.ChoreographerTickScheduler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Unique nonredundant configuration: cold singleton, separate from VSYNC/concurrency fixtures.
@Config(
    sdk = [36],
    manifest = Config.NONE,
    instrumentedPackages = ["com.asyncanimator.thread", "com.asyncanimator.core"]
)
class AnimationThreadBootstrapTest {
    @Test fun testFirstBusinessMessageSeesInstalledSchedulerAndOwnerPriority() {
        val delegate = AnimationControlThread::class.java.getDeclaredField("instance\$delegate")
            .apply { isAccessible = true }.get(null) as Lazy<*>
        assertFalse("this test must exercise cold initialization", delegate.isInitialized())
        val local = AnimationHandler::class.java.getDeclaredField("threadLocalHandler")
            .apply { isAccessible = true }.get(null) as ThreadLocal<*>
        val mainHandler = AnimationHandler.instance
        val executor = Executors.ANIM_CONTROL_EXECUTOR
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        executor.post {
            try {
                // Check BEFORE instance: its default lazy path would mask a missing bootstrap.
                val installed = local.get()
                assertNotNull("onLooperPrepared must install before the first business message", installed)
                val handler = AnimationHandler.instance
                assertSame(installed, handler)
                assertNotSame(mainHandler, handler)
                assertTrue(handler.scheduler is ChoreographerTickScheduler)
                assertEquals(0L, handler.scheduler.frameCount)
                assertEquals(0, handler.callbackSize)
                assertSame(executor.getLooper(), Looper.myLooper())
                assertSame(executor.getThread(), Thread.currentThread())
                assertEquals("launcher.anim", Thread.currentThread().name)
                assertEquals(-19, Process.getThreadPriority(Process.myTid()))
                var inline = false
                executor.execute {
                    assertSame(handler, AnimationHandler.instance)
                    inline = true
                }
                assertTrue("owner execute must not add an initialization queue hop", inline)
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                done.countDown()
            }
        }
        assertTrue("first business message must run", done.await(10, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("animation thread bootstrap", it) }
        assertSame(mainHandler, AnimationHandler.instance)
        // Process singleton: do not quit it or register frame subscriptions in this fixture.
    }

    @Test fun testLooperCanBePublishedBeforePreparationButMessagesWaitForPreparation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val prepared = AtomicBoolean(false)
        val ran = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val thread = object : HandlerThread("bootstrap-order-test") {
            override fun onLooperPrepared() {
                entered.countDown()
                try {
                    assertTrue("test must release preparation", release.await(10, TimeUnit.SECONDS))
                    prepared.set(true)
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
        }
        thread.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // getLooper returns while onLooperPrepared is deliberately still blocked.
            val handler = Handler(thread.looper)
            assertFalse(prepared.get())
            assertTrue(handler.post {
                try {
                    assertTrue("queued work must follow preparation", prepared.get())
                    ran.set(true)
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    done.countDown()
                }
            })
            assertFalse("posting is allowed; executing before preparation is not", ran.get())
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("HandlerThread preparation order", it) }
            assertTrue(ran.get())
        } finally {
            release.countDown()
            thread.quitSafely()
            thread.join(5000)
            assertFalse("fixture-owned thread must exit", thread.isAlive)
        }
    }
}
