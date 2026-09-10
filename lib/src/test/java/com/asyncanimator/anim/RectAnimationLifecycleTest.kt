package com.asyncanimator.anim

import android.animation.ValueAnimator
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import com.asyncanimator.thread.LooperExecutor
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class RectAnimationLifecycleTest {
    private val rects = mutableListOf<CustomRectFSpringAnim>()
    private val workers = mutableListOf<HandlerThread>()
    private val messages = mutableListOf<Boolean>()
    private val main = LooperExecutor(object : Handler(Looper.getMainLooper()) {
        override fun dispatchMessage(msg: Message) { messages.add(msg.isAsynchronous); super.dispatchMessage(msg) }
    })
    private val type = CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME

    private class Driver(override val supportsAnimationThread: Boolean = false) : CustomRectFSpringAnim.ReversibleDriver {
        val calls = CopyOnWriteArrayList<Pair<String, Thread>>()
        @Volatile var done: (() -> Unit)? = null
        var target: RectF? = null
        var radius = -1f
        override fun start(onActualEnd: () -> Unit) { calls.add("start" to Thread.currentThread()); done = onActualEnd }
        override fun cancel() { calls.add("cancel" to Thread.currentThread()) }
        override fun skipToEnd() { calls.add("end" to Thread.currentThread()) }
        override fun clearEndCallback() { calls.add("clear" to Thread.currentThread()); done = null }
        override fun reverseToOpen(target: RectF, endRadius: Float) {
            calls.add("reverse" to Thread.currentThread()); this.target = RectF(target); radius = endRadius
        }
        fun finish() { done?.invoke() }
        fun names() = calls.map { it.first }
    }

    private fun worker() = HandlerThread("rect-test.anim").apply { start() }.also(workers::add)
    private fun rect(driver: CustomRectFSpringAnim.Driver, thread: HandlerThread? = null) =
        CustomRectFSpringAnim(type, driver, main, thread?.let { LooperExecutor(Handler(it.looper)) } ?: main)
            .also(rects::add)
    private fun listen(rect: CustomRectFSpringAnim): MutableList<String> {
        val events = mutableListOf<String>()
        rect.addListener(object : CustomRectFSpringAnim.Listener {
            private fun event(name: String, event: CustomRectFSpringAnim.Event) {
                assertSame(Looper.getMainLooper().thread, Thread.currentThread())
                events.add("$name:${event.animationId}:${event.cancelled}")
            }
            override fun onStart(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) = event("start", event)
            override fun onCancel(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) = event("cancel", event)
            override fun onEnd(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) = event("end", event)
            override fun onActualEnd(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) = event("actual", event)
        })
        return events
    }

    @After fun cleanup() {
        rects.forEach { it.dispose() }
        workers.forEach { shadowOf(it.looper).idle(); it.quitSafely(); it.join(1000) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun testMainDriverNaturalEndAndDuplicateStartAreOnceOnly() {
        val driver = Driver(); val rect = rect(driver); rect.animationId = 7
        val events = listen(rect); var actual = 0
        rect.start { actual++ }; rect.start { actual += 100 }
        assertEquals(listOf("start"), driver.names())
        driver.finish()
        assertEquals(listOf("start:7:false", "end:7:false", "actual:7:false"), events)
        assertEquals(1, actual)
        assertFalse(rect.isRunning)
    }

    @Test fun testCancelNotifiesLogicallyButWaitsForActualDriverEnd() {
        val driver = Driver(); val rect = rect(driver); val events = listen(rect)
        rect.start(); rect.cancel(); rect.cancel(); rect.skipToEnd()
        assertEquals(listOf("start", "cancel"), driver.names())
        assertEquals(listOf("start:-1:false", "cancel:-1:true", "end:-1:true"), events)
        assertTrue(rect.isRunning)
        driver.finish()
        assertEquals("actual:-1:true", events.last())
        assertFalse(rect.isRunning)
    }

    @Test fun testSkipToEndDoesNotEmitCancelOrPretendPhysicalCompletion() {
        val driver = Driver(); val rect = rect(driver); val events = listen(rect)
        rect.start(); rect.skipToEnd(); rect.cancel()
        assertEquals(listOf("start", "end"), driver.names())
        assertEquals(listOf("start:-1:false", "end:-1:false"), events)
        assertTrue(rect.isRunning)
        driver.finish()
        assertEquals("actual:-1:false", events.last())
    }

    @Test fun testJustNotifyEndKeepsDriverRunningAndSuppressesDuplicateLogicalEnd() {
        val driver = Driver(); val rect = rect(driver); val events = listen(rect)
        rect.start(); rect.justNotifyEndCallback(); rect.justNotifyEndCallback()
        assertEquals(listOf("start"), driver.names())
        assertTrue(rect.isRunning)
        assertTrue(rect.isJustNotifyEndCallback)
        rect.cancel(); driver.finish()
        assertEquals(listOf("start:-1:false", "end:-1:false", "actual:-1:true"), events)
    }

    @Test fun testBackgroundDriverLifecycleStaysOnOwnerAndActualEndUsesAsyncMainMessage() {
        val worker = worker(); val driver = Driver(true); val rect = rect(driver, worker)
        val events = listen(rect)
        rect.start(); shadowOf(worker.looper).idle()
        rect.cancel(); shadowOf(worker.looper).idle()
        Handler(worker.looper).post { driver.finish() }; shadowOf(worker.looper).idle()
        assertTrue(rect.isRunning) // completion has not yet been delivered on main
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("start", "cancel", "clear"), driver.names())
        assertTrue(driver.calls.all { it.second === worker })
        assertEquals("actual:-1:true", events.last())
        assertTrue(messages.isNotEmpty())
        assertTrue(messages.all { it })
        assertFalse(rect.isRunning)
    }

    @Test fun testCancelBeforePostedStartIsAppliedAfterDriverStarts() {
        val worker = worker(); val driver = Driver(true); val rect = rect(driver, worker)
        rect.start(); rect.cancel(); rect.cancel()
        shadowOf(worker.looper).idle()
        assertEquals(listOf("start", "cancel"), driver.names())
        assertTrue(rect.isRunning)
        Handler(worker.looper).post { driver.finish() }; shadowOf(worker.looper).idle()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(rect.isRunning)
    }

    @Test fun testOwnerAndIdCannotChangeUntilCleanupAndActualEndHaveCompleted() {
        val worker = worker(); val driver = Driver(true); val rect = rect(driver, worker)
        rect.start(); shadowOf(worker.looper).idle()
        assertThrows(IllegalStateException::class.java) { rect.setAsyncStart(false) }
        assertThrows(IllegalStateException::class.java) { rect.animationId = 42 }
        Handler(worker.looper).post { driver.finish() }; shadowOf(worker.looper).idle()
        assertThrows(IllegalStateException::class.java) { rect.setAsyncStart(false) }
        shadowOf(Looper.getMainLooper()).idle()
        rect.setAsyncStart(false); rect.animationId = 42; rect.start()
        assertSame(Looper.getMainLooper().thread, driver.calls.last().second)
        assertNotNull(driver.done) // old owner cleanup must not clear the new callback
    }

    @Test fun testReverseCopiesTargetRunsOnOwnerAndReturnsToMain() {
        val worker = worker(); val driver = Driver(true); val rect = rect(driver, worker)
        rect.start(); shadowOf(worker.looper).idle()
        val target = RectF(1f, 2f, 30f, 40f)
        var afterThread: Thread? = null
        rect.reverseToOpen(target, 8f) { afterThread = Thread.currentThread() }
        target.setEmpty()
        shadowOf(worker.looper).idle()
        assertEquals(RectF(1f, 2f, 30f, 40f), driver.target)
        assertEquals(8f, driver.radius, 0f)
        assertSame(worker, driver.calls.last().second)
        assertNull(afterThread)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(Looper.getMainLooper().thread, afterThread)
        assertTrue(rect.isReverseToOpen)
        assertEquals(CustomRectFSpringAnim.AnimType.REVERSE_TO_OPEN, rect.animType)
    }

    @Test fun testAnimatorAdapterRejectsUnsafeBackgroundAndUnsupportedRetargeting() {
        val rect = CustomRectFSpringAnim(type, ValueAnimator.ofFloat(0f, 1f)).also(rects::add)
        assertThrows(IllegalArgumentException::class.java) { rect.setAsyncStart(true) }
        rect.start()
        assertThrows(UnsupportedOperationException::class.java) { rect.reverseToOpen(RectF(), 0f) {} }
    }

    @Test fun testDisposeDropsQueuedActualEndAndInvalidatesLateDriverCallback() {
        val worker = worker(); val driver = Driver(true); val rect = rect(driver, worker)
        val events = listen(rect); var actual = 0
        rect.start { actual++ }; shadowOf(worker.looper).idle()
        val late = driver.done!!
        Handler(worker.looper).post { driver.finish() }; shadowOf(worker.looper).idle()
        rect.dispose(); rect.dispose()
        shadowOf(Looper.getMainLooper()).idle()
        late(); shadowOf(worker.looper).idle(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("start:-1:false"), events)
        assertEquals(0, actual)
        assertFalse(rect.isRunning)
        assertThrows(IllegalStateException::class.java) { rect.start() }
    }

    @Test fun testDisposeFromEndListenerStopsRemainingEventsAndOwnerHook() {
        val driver = Driver(); val rect = rect(driver); var actual = 0
        rect.addListener(object : CustomRectFSpringAnim.Listener {
            override fun onEnd(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) { rect.dispose() }
        })
        val events = listen(rect)
        rect.start { actual++ }; driver.finish()
        assertEquals(listOf("start:-1:false"), events)
        assertEquals(0, actual)
    }

    @Test fun testNewListenerFromReentrantNextRunCannotReceiveOldActualEnd() {
        val driver = Driver(); val rect = rect(driver)
        val nextRunActualIds = mutableListOf<Int>()
        rect.animationId = 1
        rect.addListener(object : CustomRectFSpringAnim.Listener {
            override fun onEnd(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) {
                if (event.animationId == 1) {
                    rect.animationId = 2
                    rect.addListener(object : CustomRectFSpringAnim.Listener {
                        override fun onActualEnd(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) {
                            nextRunActualIds.add(event.animationId)
                        }
                    })
                    rect.start()
                }
            }
        })
        rect.start(); driver.finish()
        assertTrue(nextRunActualIds.isEmpty())
        assertTrue(rect.isRunning)
        driver.finish()
        assertEquals(listOf(2), nextRunActualIds)
    }

    @Test fun testDuplicateOldActualEndCannotStopNextRun() {
        val driver = Driver(); val rect = rect(driver)
        rect.start(); val old = driver.done!!; driver.finish()
        rect.start(); old()
        assertTrue(rect.isRunning)
        driver.finish()
        assertFalse(rect.isRunning)
    }

    @Test fun testListenerCanRemoveItselfWithoutSkippingOtherListeners() {
        val driver = Driver(); val rect = rect(driver); var otherStarts = 0
        rect.addListener(object : CustomRectFSpringAnim.Listener {
            override fun onStart(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) { rect.removeListener(this) }
        })
        rect.addListener(object : CustomRectFSpringAnim.Listener {
            override fun onStart(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) { otherStarts++ }
        })
        rect.start(); driver.finish()
        assertEquals(1, otherStarts)
    }

    @Test fun testBareHandleCannotPretendToStartAndInstantDriverEndsAfterStartEvent() {
        val bare = CustomRectFSpringAnim(type).also(rects::add)
        assertThrows(IllegalArgumentException::class.java) { bare.start() }
        assertFalse(bare.isRunning)
        val instant = rect(object : CustomRectFSpringAnim.Driver {
            override fun start(onActualEnd: () -> Unit) { onActualEnd() }
            override fun cancel() {}
            override fun skipToEnd() {}
            override fun clearEndCallback() {}
        })
        val events = listen(instant)
        instant.start()
        assertEquals(listOf("start:-1:false", "end:-1:false", "actual:-1:false"), events)
    }

    @Test fun testDisposeBeforeQueuedStartNeverStartsTheDriver() {
        val worker = worker(); val driver = Driver(true); val rect = rect(driver, worker)
        rect.start(); rect.dispose(); shadowOf(worker.looper).idle()
        assertFalse("queued start must be invalidated", "start" in driver.names())
        assertTrue(driver.calls.all { it.second === worker })
    }

    @Test fun testThrowingObserverCannotPreventDriverStartStopOrActualEndBarrier() {
        val driver = Driver()
        val rect = rect(driver)
        rect.addListener(object : CustomRectFSpringAnim.Listener {
            override fun onStart(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) { throw IllegalStateException("start observer") }
            override fun onCancel(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) { throw IllegalStateException("cancel observer") }
            override fun onEnd(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) { throw IllegalStateException("end observer") }
            override fun onActualEnd(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) { throw IllegalStateException("actual observer") }
        })
        val events = listen(rect)
        var completed = 0
        rect.start { completed++ }
        rect.cancel()
        assertEquals(listOf("start", "cancel"), driver.names())
        driver.finish()
        assertEquals(1, completed)
        assertEquals(4, events.size)
        assertFalse(rect.isRunning)
    }


    @Test fun testFirstStopWinsForBothOrdersAndRetargetAfterStopIsIgnored() {
        for (cancelFirst in listOf(false, true)) {
            val driver = Driver(); val rect = rect(driver)
            val events = listen(rect)
            rect.start()
            if (cancelFirst) { rect.cancel(); rect.skipToEnd() }
            else { rect.skipToEnd(); rect.cancel() }
            rect.reverseToOpen(RectF(0f, 0f, 10f, 10f), 2f) { fail("stopped run") }
            assertEquals(listOf("start", if (cancelFirst) "cancel" else "end"), driver.names())
            driver.finish()
            assertEquals(if (cancelFirst) 4 else 3, events.size)
            assertEquals("actual:-1:$cancelFirst", events.last())
            assertFalse(rect.isReverseToOpen)
        }
    }

    @Test fun testRunEventsAreImmutableAndSerialAdvancesOnlyForAcceptedStarts() {
        val driver = Driver(); val rect = rect(driver)
        val events = mutableListOf<CustomRectFSpringAnim.Event>()
        rect.addListener(object : CustomRectFSpringAnim.Listener {
            override fun onStart(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) {
                assertSame(rect, animation); events.add(event)
            }
            override fun onActualEnd(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) {
                events.add(event)
            }
        })
        rect.animationId = 8
        rect.start(); rect.start(); rect.cancel(); driver.finish()
        rect.animationId = 9
        rect.start(); driver.finish()
        assertEquals(listOf(8, 8, 9, 9), events.map { it.animationId })
        assertEquals(listOf(1L, 1L, 2L, 2L), events.map { it.runId })
        assertEquals(listOf(false, true, false, false), events.map { it.cancelled })
        assertEquals(events.first().copy(cancelled = true), events[1])
        assertFalse(events.first().cancelled)
    }

    @Test fun testClearOwnerHookDoesNotSuppressListenersOrNativeCleanup() {
        val driver = Driver(); val rect = rect(driver)
        val events = listen(rect)
        rect.start { fail("cleared owner hook") }
        rect.clearEndCallback(); rect.clearEndCallback()
        assertNotNull(driver.done)
        driver.finish()
        assertEquals(listOf("start", "clear"), driver.names())
        assertEquals(listOf("start:-1:false", "end:-1:false", "actual:-1:false"), events)
    }

    @Test fun testDefaultListenerDedupAndRemovingAnotherListenerAffectCurrentAudience() {
        val driver = Driver(); val rect = rect(driver)
        val quiet = object : CustomRectFSpringAnim.Listener {}
        rect.addListener(quiet); rect.addListener(quiet)
        var starts = 0
        val removed = object : CustomRectFSpringAnim.Listener {
            override fun onStart(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) {
                fail("membership is rechecked before delivery")
            }
        }
        val first = object : CustomRectFSpringAnim.Listener {
            override fun onStart(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) {
                starts++; rect.removeListener(removed)
            }
        }
        rect.addListener(first); rect.addListener(first); rect.addListener(removed)
        rect.start(); driver.finish()
        assertEquals(1, starts)
    }

    @Test fun testDisposableEngineReceivesFinalTeardownInsteadOfSeparateCancelAndClear() {
        val calls = mutableListOf<String>()
        val engine = object : CustomRectFSpringAnim.DisposableDriver {
            override fun start(onActualEnd: () -> Unit) { calls.add("start") }
            override fun cancel() { fail("dispose owns native cancellation") }
            override fun skipToEnd() {}
            override fun clearEndCallback() { fail("dispose owns native cleanup") }
            override fun dispose() { calls.add("dispose") }
        }
        val rect = rect(engine)
        rect.start(); rect.dispose(); rect.dispose()
        assertEquals(listOf("start", "dispose"), calls)
        assertThrows(IllegalStateException::class.java) { rect.animationId = 2 }
        assertThrows(IllegalStateException::class.java) { rect.addListener(object : CustomRectFSpringAnim.Listener {}) }
        rect.cancel(); rect.skipToEnd(); rect.justNotifyEndCallback()
        assertEquals(listOf("start", "dispose"), calls)
    }

    @Test fun testAnimatorAdapterPreservesHostListenersAcrossRepeatedRunsAndDispose() {
        val animator = ValueAnimator.ofFloat(0f, 1f).apply { duration = 1000 }
        var nativeEnds = 0
        val nativeListener = object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { nativeEnds++ }
        }
        animator.addListener(nativeListener)
        val rect = CustomRectFSpringAnim(type, animator).also(rects::add)
        var actualEnds = 0
        repeat(2) {
            rect.start { actualEnds++ }
            assertEquals(2, animator.listeners!!.size)
            rect.skipToEnd()
            assertEquals(listOf(nativeListener), animator.listeners)
        }
        assertEquals(2, actualEnds)
        rect.start { fail("dispose suppresses handle hook") }
        rect.dispose()
        assertEquals(3, nativeEnds)
        assertEquals(listOf(nativeListener), animator.listeners)
        assertFalse(animator.isStarted)
    }

    @Test fun testLogicalOnlyFlagResetsOnNextRunAndLaterCancelStillStopsDriver() {
        val driver = Driver(); val rect = rect(driver)
        val events = listen(rect)
        rect.start(); rect.justNotifyEndCallback(); rect.cancel()
        assertTrue(rect.isJustNotifyEndCallback)
        assertEquals(listOf("start", "cancel"), driver.names())
        driver.finish()
        assertEquals(listOf("start:-1:false", "end:-1:false", "actual:-1:true"), events)
        rect.start()
        assertFalse(rect.isJustNotifyEndCallback)
        assertFalse(rect.isReverseToOpen)
    }

}
