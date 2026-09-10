package com.asyncanimator.manager

import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class FeatureSnapshotTest {
    private val feature = AnimationFeatureHelper
    private val old = listOf(feature.asyncEnable, feature.rtUnlockEnable, feature.multiAppBlockEnable,
        feature.iconBlurEnable, feature.onePxEnable, feature.limtSize)
    private val threshold = feature.interruptThreshold
    private val packages = feature.onePxPkgDisableList
    private val cards = feature.onePxCardDisableList
    @After fun restore() { feature.simulateRemoteUpdate(old[0], old[1], old[2], old[3], old[4],
        threshold, old[5], packages, cards) }

    @Test fun testPublishedListsCannotBeMutatedByCastingOrJavaCollections() {
        feature.simulateRemoteUpdate(0, 0, 0, 0, 0, 1f, 0, listOf("a", "b"), listOf(1, 2))
        assertThrows(UnsupportedOperationException::class.java) {
            (feature.onePxPkgDisableList as MutableList<String>).add("injected")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (feature.onePxCardDisableList as MutableList<Int>).clear()
        }
        assertEquals(listOf("a", "b"), feature.onePxPkgDisableList)
        assertEquals(listOf(1, 2), feature.onePxCardDisableList)
    }

    @Test fun testInputAndPreviouslyPublishedSnapshotsAreIndependent() {
        val input = mutableListOf("a", "b")
        feature.simulateRemoteUpdate(0, 0, 0, 0, 0, 1f, 0, input, listOf(1, 2))
        val snapshot = feature.onePxPkgDisableList
        input.clear()
        feature.simulateRemoteUpdate(1, 1, 1, 1, 1, 1f, 1, listOf("c", "d"), null)
        assertEquals(listOf("a", "b"), snapshot)
        assertEquals(listOf("c", "d"), feature.onePxPkgDisableList)
        assertEquals(listOf(1, 2), feature.onePxCardDisableList)
    }

    @Test fun testSixScalarUpdateCannotRestoreStaleOnePxConfiguration() {
        feature.simulateRemoteUpdate(0, 0, 0, 0, 0, 1f, 0, null, null)
        // Control lock contention, but assert only the public update result.
        val lock = requireNotNull(AnimationFeatureHelper::class.java.getDeclaredField("lock")
            .apply { isAccessible = true }.get(feature))
        val failure = AtomicReference<Throwable?>()
        val writer = Thread {
            try { feature.simulateRemoteUpdate(2, 2, 2, 2, 2f, 2) }
            catch (t: Throwable) { failure.set(t) }
        }
        synchronized(lock) {
            writer.start()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (writer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals("writer reached shared config lock", Thread.State.BLOCKED, writer.state)
            feature.simulateRemoteUpdate(1, 1, 1, 1, 1, 1f, 1, null, null)
        }
        writer.join(5000)
        assertFalse(writer.isAlive)
        failure.get()?.let { throw it }
        assertEquals(1, feature.onePxEnable)
        assertEquals(2, feature.asyncEnable)
    }

    @Test fun testPackageCopyFailureLeavesEntireConfigurationUnchanged() {
        val before = feature.snapshot()
        val failure = IllegalStateException("package copy failed")
        val packages = object : AbstractList<String>() {
            override val size = 1
            override fun get(index: Int): String = throw failure
        }
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            feature.simulateRemoteUpdate(91, 92, 93, 94, 95, 0.25f, 96, packages, listOf(97))
        })
        assertEquals(before, feature.snapshot())
    }

    @Test fun testCardCopyFailureCannotPublishScalarsOrTheFirstList() {
        val before = feature.snapshot()
        val failure = IllegalStateException("card copy failed")
        val cards = object : AbstractList<Int>() {
            override val size = 1
            override fun get(index: Int): Int = throw failure
        }
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            feature.simulateRemoteUpdate(91, 92, 93, 94, 95, 0.25f, 96, listOf("new.pkg"), cards)
        })
        assertEquals(before, feature.snapshot())
    }

    @Test fun testCallerListTraversalCannotObserveAPartiallyAppliedSnapshot() {
        val before = feature.snapshot()
        val observed = mutableListOf<AnimationFeatureHelper.Snapshot>()
        val packages = object : AbstractList<String>() {
            override val size = 1
            override fun get(index: Int): String {
                observed.add(feature.snapshot())
                return "new.pkg"
            }
        }
        feature.simulateRemoteUpdate(91, 92, 93, 94, 95, 0.25f, 96, packages, listOf(97))
        assertTrue(observed.isNotEmpty())
        assertTrue("Collection code runs before any configuration is changed", observed.all { it == before })
        val after = feature.snapshot()
        assertEquals(91, after.asyncEnable)
        assertEquals(95, after.onePxEnable)
        assertEquals(listOf("new.pkg"), after.onePxPkgDisableList)
        assertEquals(listOf(97), after.onePxCardDisableList)
    }
}
