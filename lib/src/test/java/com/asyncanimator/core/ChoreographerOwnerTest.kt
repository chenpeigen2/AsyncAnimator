package com.asyncanimator.core

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Choreographer
import java.util.concurrent.atomic.AtomicReference
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer

@RunWith(RobolectricTestRunner::class)
// The extra nonredundant package isolates this background display receiver from main-only VSYNC tests.
@Config(sdk = [36], manifest = Config.NONE,
    instrumentedPackages = ["com.asyncanimator.core", "com.asyncanimator.playback"])
@LooperMode(LooperMode.Mode.PAUSED)
class ChoreographerOwnerTest {
    @Test fun testRestartFromAnotherLooperDoesNotMigrateTheFrameSource() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(10))
        val owner = HandlerThread("scheduler-owner-test").apply { start() }
        // Construction does not bind the scheduler; first scheduling on owner does.
        val scheduler = ChoreographerTickScheduler()
        val firstSource = AtomicReference<Choreographer>()
        val threads = ConcurrentLinkedQueue<Thread>()
        val frames = Semaphore(0)
        try {
            assertTrue(Handler(owner.looper).post {
                firstSource.set(Choreographer.getInstance())
                scheduler.postFrameCallback {
                    threads.add(Thread.currentThread())
                    scheduler.stop() // retain subscription, but leave no next pulse queued
                    frames.release()
                }
            })
            shadowOf(owner.looper).idle()
            shadowOf(owner.looper).idleFor(Duration.ofMillis(10))
            assertTrue("initial frame must arrive", frames.tryAcquire(5, TimeUnit.SECONDS))
            assertEquals(listOf(owner), threads.toList())

            // A restart is a lifecycle operation, not permission to change the frame owner.
            scheduler.start()
            // Inspect the actual source getter too: Robolectric's shared next-vsync timestamp
            // cannot reliably demonstrate migration by racing two different display receivers.
            val sourceGetter = scheduler.javaClass.getDeclaredMethod("getChoreographer")
                .apply { isAccessible = true }
            assertSame("restart must retain the first scheduling thread's frame source",
                firstSource.get(), sourceGetter.invoke(scheduler))
            // Cross-thread Choreographer.postFrameCallback queues a VSYNC scheduling message.
            // Drain it BEFORE advancing the controlled display clock.
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(owner.looper).idle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            shadowOf(owner.looper).idle()
            assertTrue("resumed frame must arrive", frames.tryAcquire(5, TimeUnit.SECONDS))
            assertEquals("both frames must use the original Choreographer", listOf(owner, owner), threads.toList())
            assertEquals(2L, scheduler.frameCount)
        } finally {
            scheduler.stop()
            shadowOf(owner.looper).idle()
            owner.quitSafely()
            owner.join(5000)
            assertFalse("fixture-owned thread must exit", owner.isAlive)
        }
    }
}
