package com.asyncanimator.manager

import android.os.Looper
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class FeatureNotificationTest {
    private val f = AnimationFeatureHelper
    private val old = f.snapshot()
    @Before fun setup() { f.onDestroy(); f.setAdaptiveAnimationEnabled(false) }
    @After fun cleanup() {
        f.onDestroy()
        f.setAdaptiveAnimationEnabled(old.adaptiveAnimationEnabled)
        f.simulateRemoteUpdate(old.asyncEnable, old.rtUnlockEnable, old.multiAppBlockEnable,
            old.iconBlurEnable, old.onePxEnable, old.interruptThreshold, old.limtSize,
            old.onePxPkgDisableList, old.onePxCardDisableList)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun update(value: Int) = f.simulateRemoteUpdate(value, value, value, value, value,
        value.toFloat(), value, listOf(value.toString()), listOf(value))
    private fun worker(action: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        Thread { try { action() } catch (t: Throwable) { failure.set(t) } }
            .apply { start(); join(5000); assertFalse(isAlive) }
        failure.get()?.let { throw it }
    }

    @Test fun testWorkerPublicationNotifiesOnMainAndExposesCoherentLatestSnapshot() {
        var thread: Thread? = null
        var snapshot: AnimationFeatureHelper.Snapshot? = null
        val subscription = f.addRemoteUpdateListener {
            thread = Thread.currentThread(); snapshot = f.snapshot()
        }
        worker { update(1); update(2) }
        assertNull(thread)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(Looper.getMainLooper().thread, thread)
        assertEquals(2, checkNotNull(snapshot).asyncEnable)
        assertEquals(listOf("2"), checkNotNull(snapshot).onePxPkgDisableList)
        subscription.close()
    }

    @Test fun testCloseAndRemoveInvalidateQueuedEventsWithoutHittingNewRegistration() {
        var calls = 0
        val callback = { calls++; Unit }
        val first = f.addRemoteUpdateListener(callback)
        update(1); first.close(); first.close()
        val second = f.addRemoteUpdateListener(callback)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, calls)
        update(2); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, calls)
        update(3); f.removeRemoteUpdateListener(callback)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, calls)
        second.close()
    }

    @Test fun testGlobalTeardownClearsCallbacksButKeepsConfigurationAndAllowsNewOwners() {
        var calls = 0
        f.addRemoteUpdateListener { calls++ }
        update(8); f.onDestroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, calls)
        assertEquals(8, f.snapshot().asyncEnable)
        f.addRemoteUpdateListener { calls++ }
        update(9); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, calls)
    }

    @Test fun testReentrantRemovalRegistrationAndUpdateAreSafe() {
        val calls = mutableListOf<String>()
        var first = true
        lateinit var second: AutoCloseable
        f.addRemoteUpdateListener {
            calls.add("a")
            if (first) {
                first = false
                second.close()
                f.addRemoteUpdateListener { calls.add("c") }
                update(2)
            }
        }
        second = f.addRemoteUpdateListener { calls.add("b") }
        update(1); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("a", "a", "c"), calls)
    }

    @Test fun testListenerRunsOutsideConfigLockAndExceptionsDoNotStarveOtherListeners() {
        var first = true
        var calls = 0
        f.addRemoteUpdateListener {
            if (first) { first = false; worker { update(2) } }
            throw IllegalArgumentException("bad consumer")
        }
        f.addRemoteUpdateListener { calls++ }
        update(1); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, calls)
    }

    @Test fun testAdaptivePolicyForcesThresholdAndDisablesRadiusUntilExplicitlyChanged() {
        update(3)
        assertEquals(3f, f.interruptThreshold, 0f)
        assertTrue(f.getRadiusAnimationEnable())
        f.setAdaptiveAnimationEnabled(true)
        assertFalse(f.getRadiusAnimationEnable())
        assertFalse(f.snapshot().radiusAnimationEnable)
        assertEquals(1f, f.interruptThreshold, 0f)
        update(4)
        assertEquals(1f, f.interruptThreshold, 0f)
        f.simulateRemoteUpdate(5, 5, 5, 5, 9f, 5)
        assertEquals(1f, f.interruptThreshold, 0f)
        assertEquals(4, f.onePxEnable)
        f.setAdaptiveAnimationEnabled(false)
        assertEquals(1f, f.interruptThreshold, 0f) // no hidden restoration of an earlier request
        update(6)
        assertEquals(6f, f.interruptThreshold, 0f)
    }

    @Test fun testConcurrentSnapshotNeverMixesFieldsFromDifferentFullUpdates() {
        update(0)
        val failure = AtomicReference<Throwable?>()
        val writer = Thread {
            try { repeat(200) { update(it) } } catch (t: Throwable) { failure.set(t) }
        }.apply { start() }
        repeat(500) {
            val snapshot = f.snapshot()
            val n = snapshot.asyncEnable
            assertEquals(n, snapshot.rtUnlockEnable)
            assertEquals(n, snapshot.multiAppBlockEnable)
            assertEquals(n, snapshot.iconBlurEnable)
            assertEquals(n, snapshot.onePxEnable)
            assertEquals(n, snapshot.limtSize)
            assertEquals(n.toFloat(), snapshot.interruptThreshold, 0f)
            assertEquals(listOf(n.toString()), snapshot.onePxPkgDisableList)
            assertEquals(listOf(n), snapshot.onePxCardDisableList)
        }
        writer.join(5000); assertFalse(writer.isAlive)
        failure.get()?.let { throw it }
    }

    @Test fun testDuplicateCallbackHandlesAreIndependentAndRemoveClearsAllIdenticalRegistrations() {
        var calls = 0
        val callback = { calls++; Unit }
        val first = f.addRemoteUpdateListener(callback)
        val second = f.addRemoteUpdateListener(callback)
        update(1)
        first.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, calls)
        val third = f.addRemoteUpdateListener(callback)
        update(2)
        f.removeRemoteUpdateListener(callback)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, calls)
        first.close(); second.close(); third.close()
    }

    @Test fun testMainPublicationIsAlwaysQueuedAndDoesNotReplayToLateSubscribers() {
        val seen = mutableListOf<Int>()
        f.addRemoteUpdateListener { seen.add(f.snapshot().asyncEnable) }
        update(1); update(2)
        var lateCalls = 0
        f.addRemoteUpdateListener { lateCalls++ }
        assertTrue(seen.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(2, 2), seen) // Notification, not a historical payload stream.
        assertEquals(0, lateCalls)
        update(2) // Equal configuration still publishes one notification per registration.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(2, 2, 2), seen)
        assertEquals(1, lateCalls)
    }

    @Test fun testNullListsKeepSnapshotButEmptyListsExplicitlyClearIt() {
        update(4)
        val before = f.snapshot()
        f.simulateRemoteUpdate(-1, 0, 1, 2, 0, 0.4f, 3, null, null)
        val kept = f.snapshot()
        assertSame(before.onePxPkgDisableList, kept.onePxPkgDisableList)
        assertSame(before.onePxCardDisableList, kept.onePxCardDisableList)
        assertFalse(f.isAsyncConfigured)
        f.simulateRemoteUpdate(0, 0, 0, 0, 0, 0.1f, 0, emptyList(), emptyList())
        assertTrue(f.isAsyncConfigured) // Configured-disabled is not unconfigured.
        assertTrue(f.snapshot().onePxPkgDisableList.isEmpty())
        assertTrue(f.snapshot().onePxCardDisableList.isEmpty())
        assertEquals(listOf("4"), before.onePxPkgDisableList)
        assertEquals(4, before.copy(asyncEnable = 9).onePxEnable)
        assertEquals(4, before.asyncEnable)
    }

    @Test fun testFatalObserverErrorIsNotSwallowedAndRegistrationCanStillBeClosed() {
        val failure = AssertionError("fatal observer")
        val bad = f.addRemoteUpdateListener { throw failure }
        var calls = 0
        f.addRemoteUpdateListener { calls++ }
        update(1)
        assertSame(failure, assertThrows(AssertionError::class.java) {
            shadowOf(Looper.getMainLooper()).idle()
        })
        assertEquals(0, calls)
        bad.close()
        update(2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, calls)
    }

}
