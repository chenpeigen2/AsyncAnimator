package com.asyncanimator.manager

import android.os.Looper
import com.asyncanimator.control.AnimationController
import com.asyncanimator.control.DefaultAnimationController
import com.asyncanimator.seq.AnimSeqTimeStamp
import java.time.Duration
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
class ManagerLifecycleTest {
    private var previousEnabled = true
    @Before fun setup() {
        previousEnabled = OplusAnimManager.interruptionEnabled
        OplusAnimManager.interruptionEnabled = false
        OplusAnimManager.interruptionEnabled = true
        AnimSeqTimeStamp.resetAllForTest()
    }
    @After fun cleanup() {
        OplusAnimManager.interruptionEnabled = false
        OplusAnimManager.interruptionEnabled = previousEnabled
        AnimSeqTimeStamp.resetAllForTest()
    }

    @Test fun testDisableDisposesAllTimeoutSlotsAndQueuedSeqFinish() {
        val controller = OplusAnimManager.animController as AnimationController
        val seq = OplusAnimManager.animationSeqHelper
        var finishes = 0
        controller.registerSpecialSceneExitTimeOutListener(1000)
        controller.registerTransitionFinishTimeOutListener(1000)
        controller.registerOverviewContinuationTimeOutListener(1000)
        controller.recentsAnimFinishCallback = { finishes++ }
        controller.appLaunchAnimFinishCallback = { finishes++ }
        AnimSeqTimeStamp.updateLastRecentFinishTime()
        assertTrue(seq.delayFinishRecents { finishes++ })
        OplusAnimManager.interruptionEnabled = false
        assertNull(controller.specialSceneExitTimeOutListener)
        assertNull(controller.transitionFinishTimeOutListener)
        assertNull(controller.overviewContinuationTimeOutListener)
        assertNull(controller.recentsAnimFinishCallback)
        assertNull(controller.appLaunchAnimFinishCallback)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(0, finishes)
        assertEquals(DefaultAnimationController::class.java, OplusAnimManager.animController.javaClass)
    }

    @Test fun testDisabledControllerCannotDeliverPreviouslyQueuedLaunch() {
        val controller = OplusAnimManager.animController as AnimationController
        controller.registerTransitionFinishTimeOutListener(1000)
        val timer = checkNotNull(controller.transitionFinishTimeOutListener)
        var launches = 0
        assertTrue(controller.delayStartActivityIfNeed(null, null, { true }) { launches++ })
        OplusAnimManager.interruptionEnabled = false
        timer.onTimeOut(com.asyncanimator.control.TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(0, launches)
    }

    @Test fun testRepeatedToggleIsIdempotentAndReenableCreatesFreshOwners() {
        val controller = OplusAnimManager.animController
        val seq = OplusAnimManager.animationSeqHelper
        OplusAnimManager.interruptionEnabled = true
        assertSame(controller, OplusAnimManager.animController)
        OplusAnimManager.interruptionEnabled = false
        OplusAnimManager.interruptionEnabled = false
        OplusAnimManager.interruptionEnabled = true
        assertNotSame(controller, OplusAnimManager.animController)
        assertNotSame(seq, OplusAnimManager.animationSeqHelper)
    }

    @Test fun testOffMainToggleFailsBeforeDetachingCurrentOwners() {
        val controller = OplusAnimManager.animController
        val failure = AtomicReference<Throwable?>()
        Thread {
            try { OplusAnimManager.interruptionEnabled = false }
            catch (t: Throwable) { failure.set(t) }
        }.apply { start(); join(5000); assertFalse(isAlive) }
        assertTrue(failure.get() is IllegalStateException)
        assertSame(controller, OplusAnimManager.animController)
    }

    @Test fun testConfigurationPublicationDoesNotPretendToRecreateManagerOwners() {
        val controller = OplusAnimManager.animController
        val seq = OplusAnimManager.animationSeqHelper
        val feature = AnimationFeatureHelper
        val old = feature.snapshot()
        try {
            feature.simulateRemoteUpdate(0, 0, 0, 0, 0, 0.5f, 100, emptyList(), emptyList())
            assertSame(controller, OplusAnimManager.animController)
            assertSame(seq, OplusAnimManager.animationSeqHelper)
            assertTrue(OplusAnimManager.interruptionEnabled)
            assertTrue(OplusAnimManager.supportInterruption())
        } finally {
            feature.simulateRemoteUpdate(old.asyncEnable, old.rtUnlockEnable, old.multiAppBlockEnable,
                old.iconBlurEnable, old.onePxEnable, old.interruptThreshold, old.limtSize,
                old.onePxPkgDisableList, old.onePxCardDisableList)
        }
    }

    @Test fun testManagerRecentsCleanupDelegatesWithoutReplacingOwners() {
        val controller = OplusAnimManager.animController as AnimationController
        val seq = OplusAnimManager.animationSeqHelper
        controller.addRecentsAnim(com.asyncanimator.anim.CustomRectFSpringAnim(
            com.asyncanimator.anim.CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME), null, null)
        assertFalse(controller.allRecentsAnimationEnd)
        var calls = 0
        controller.recentsAnimFinishCallback = { calls++ }
        OplusAnimManager.cleanUpRecentsAnimation()
        assertTrue(controller.allRecentsAnimationEnd)
        assertEquals(com.asyncanimator.control.AnimationState.NONE, controller.animState)
        assertEquals(1, calls)
        OplusAnimManager.cleanUpRecentsAnimation()
        assertEquals(1, calls)
        assertSame(controller, OplusAnimManager.animController)
        assertSame(seq, OplusAnimManager.animationSeqHelper)
    }

    @Test fun testDisabledFallbackStillRunsImmediateSequenceWorkWithoutAllocatingIds() {
        OplusAnimManager.interruptionEnabled = false
        assertFalse(OplusAnimManager.interruptionEnabled)
        assertTrue(OplusAnimManager.supportInterruption()) // Capability placeholder, not the toggle.
        val seq = OplusAnimManager.animationSeqHelper
        assertEquals(com.asyncanimator.seq.DefaultAnimationSeqHelper::class.java, seq.javaClass)
        var calls = 0
        assertFalse(seq.delayFinishRecents { calls++ })
        assertEquals(1, calls)
        val key = "interrupt.transition.startActivity.seqId"
        val bundle = android.os.Bundle().apply { putLong(key, 42L) }
        seq.addSeqId(bundle)
        assertEquals(42L, bundle.getLong(key))
        val controller = Any()
        seq.updateNextFinishSeqIdIfNeed(controller)
        assertEquals(0L, seq.getNextFinishSeqId(controller))
        assertTrue(seq.canFinishRecent)
        assertTrue(seq.canInterceptGesture)
    }

    @Test fun testDisabledLookupsReturnIndependentFallbackOwnersAndKeepCapability() {
        OplusAnimManager.interruptionEnabled = false
        assertFalse(OplusAnimManager.interruptionEnabled)
        assertTrue(OplusAnimManager.supportInterruption())
        val first = OplusAnimManager.animController
        val second = OplusAnimManager.animController
        assertNotSame(first, second)
        assertNotSame(OplusAnimManager.animationSeqHelper, OplusAnimManager.animationSeqHelper)
        var calls = 0
        first.addOnAnimStateChangeListener(object : com.asyncanimator.control.OnAnimStateChangeListener {
            override fun onAnimStateChanged(oldState: com.asyncanimator.control.AnimationState,
                newState: com.asyncanimator.control.AnimationState, runningTask: Any?) { calls++ }
        })
        second.onAnimStateChanged(com.asyncanimator.control.AnimationState.NONE,
            com.asyncanimator.control.AnimationState.NONE, 1)
        assertEquals(0, calls)
        OplusAnimManager.cleanUpRecentsAnimation()
        assertFalse(OplusAnimManager.interruptionEnabled)
        assertSame(AnimationFeatureHelper, OplusAnimManager.featureHelper)
    }

    @Test fun testFactoryToggleDoesNotDestroyIndependentFeatureSubscribersOrConfiguration() {
        val before = AnimationFeatureHelper.snapshot()
        var calls = 0
        val subscription = AnimationFeatureHelper.addRemoteUpdateListener { calls++ }
        try {
            OplusAnimManager.interruptionEnabled = false
            OplusAnimManager.interruptionEnabled = true
            assertEquals(before, AnimationFeatureHelper.snapshot())
            AnimationFeatureHelper.setAdaptiveAnimationEnabled(before.adaptiveAnimationEnabled)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, calls)
        } finally { subscription.close() }
    }

}
