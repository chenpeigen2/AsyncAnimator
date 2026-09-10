package com.asyncanimator.manager

import com.asyncanimator.control.AnimationController
import com.asyncanimator.seq.AnimationSeqHelper
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, instrumentedPackages = ["com.asyncanimator.manager"])
@LooperMode(LooperMode.Mode.PAUSED)
class ManagerBootstrapTest {
    @After fun cleanup() { OplusAnimManager.interruptionEnabled = false }

    @Test fun testConcurrentColdLookupSharesOneEnabledControllerAndSequenceOwner() {
        // A distinct sandbox starts this object cold; workers retrieve but do not mutate owners.
        val executor = Executors.newFixedThreadPool(8)
        val barrier = CyclicBarrier(8)
        try {
            val futures = (0 until 8).map {
                executor.submit<Pair<Any, Any>> {
                    barrier.await(10, TimeUnit.SECONDS)
                    OplusAnimManager.animController to OplusAnimManager.animationSeqHelper
                }
            }
            val owners = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertTrue(owners.first().first is AnimationController)
            assertTrue(owners.first().second is AnimationSeqHelper)
            for (pair in owners) {
                assertSame(owners.first().first, pair.first)
                assertSame(owners.first().second, pair.second)
            }
            assertTrue(OplusAnimManager.interruptionEnabled)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
