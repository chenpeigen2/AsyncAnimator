package com.asyncanimator.control

import android.os.Looper
import android.os.SystemClock
import com.asyncanimator.core.LogUtils
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Duration
import org.junit.After
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
class LaunchDecisionReentrancyTest {
    private val controllers = mutableListOf<AnimationController>()
    private fun own(c: AnimationController = AnimationController()) = c.also(controllers::add)
    @After fun cleanup() { controllers.forEach { it.destroy() } }
    private fun advance() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1001))

    @Test fun testSupplierUnregisterCannotLeaveAnOwnerlessPendingLaunch() {
        val c = own(); var actions = 0
        c.registerTransitionFinishTimeOutListener(1000)
        assertFalse(c.delayStartActivityIfNeed(null, null, {
            checkNotNull(c.transitionFinishTimeOutListener).dispose(); true
        }) { actions++ })
        assertFalse(c.forbidTouch())
        advance(); assertEquals(0, actions)
    }

    @Test fun testTabletProviderResetInvalidatesItsInFlightDecision() {
        lateinit var c: AnimationController
        c = own(AnimationController(isTablet = { c.reset(); false }))
        c.setOnceGestureProcessing(GestureScene(landscape = true))
        c.registerSpecialSceneExitTimeOutListener(1000)
        var actions = 0
        assertFalse(c.delayStartActivityIfNeed(null, null, null) { actions++ })
        assertFalse(c.forbidTouch())
        advance(); assertEquals(0, actions)
    }

    @Test fun testOverviewProviderStopCannotDeferToDisposedSlot() {
        lateinit var c: AnimationController
        c = own(AnimationController(isOverviewContinuationRunning = {
            c.setAppToOverviewContinuationState(false); true
        }))
        c.setAppToOverviewContinuationState(true)
        assertFalse(c.delayStartActivityIfNeed(null, null, null) { fail("disposed owner") })
        assertFalse(c.forbidTouch())
        assertNull(c.overviewContinuationTimeOutListener)
    }

    @Test fun testNestedDecisionKeepsNewActionForBothOuterPredicateResults() {
        for (outerResult in listOf(true, false)) {
            val c = own(); val actions = mutableListOf<String>()
            c.registerTransitionFinishTimeOutListener(1000)
            val listener = c.transitionFinishTimeOutListener
            assertFalse(c.delayStartActivityIfNeed(null, null, {
                assertTrue(c.delayStartActivityIfNeed(null, null, { true }) { actions.add("new") })
                outerResult
            }) { actions.add("old") })
            assertSame(listener, c.transitionFinishTimeOutListener)
            c.dispatchTaskStateChange(TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH)
            assertEquals(listOf("new"), actions)
        }
    }

    @Test fun testSupplierReplacementDoesNotInheritOldDecisionAction() {
        val c = own(); var old = 0; var next = 0
        c.registerTransitionFinishTimeOutListener(1000)
        val before = c.transitionFinishTimeOutListener
        assertFalse(c.delayStartActivityIfNeed(null, null, {
            c.registerTransitionFinishTimeOutListener(1000); true
        }) { old++ })
        assertNotSame(before, c.transitionFinishTimeOutListener)
        assertFalse(c.forbidTouch())
        assertTrue(c.delayStartActivityIfNeed(null, null, { true }) { next++ })
        c.dispatchTaskStateChange(TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH)
        assertEquals(0, old); assertEquals(1, next)
    }

    @Test fun testBetweenFlagIsDiagnosticNotAnExtraDeferralPredicate() {
        val c = own(); val output = ByteArrayOutputStream(); val previous = System.err
        val level = if (LogUtils.isAlwayson()) LogUtils.ALWAYS else if (LogUtils.isLogOpen()) LogUtils.INFO else LogUtils.OFF
        try {
            System.setErr(PrintStream(output, true, Charsets.UTF_8)); LogUtils.setLogLevel(LogUtils.INFO)
            c.registerTransitionFinishTimeOutListener(1000)
            c.setBetweenTransitionEndAndFinish(true)
            var checks = 0
            assertFalse(c.delayStartActivityIfNeed(null, null, { checks++; false }) {})
            assertEquals(1, checks)
            assertTrue(output.toString(Charsets.UTF_8).contains("between=true"))
            assertTrue(output.toString(Charsets.UTF_8).contains("defer=false"))
            output.reset(); LogUtils.setLogLevel(LogUtils.OFF)
            c.registerTransitionFinishTimeOutListener(1000)
            assertFalse(c.delayStartActivityIfNeed(null, null, { false }) {})
            assertEquals("", output.toString(Charsets.UTF_8))
        } finally { System.setErr(previous); LogUtils.setLogLevel(level) }
    }

    @Test fun testSpecialDeadlineEqualityDefersButPastDeadlineDoesNotFallThroughOrDispose() {
        val c = own(AnimationController(isTablet = { false }))
        c.setOnceGestureProcessing(GestureScene(landscape = true))
        c.registerSpecialSceneExitTimeOutListener(1000)
        c.registerTransitionFinishTimeOutListener(1000)
        val listener = c.specialSceneExitTimeOutListener
        val deadline = AnimationController::class.java.getDeclaredField("specialSceneExitTimeOutMaxTime").apply { isAccessible = true }
        deadline.setLong(c, SystemClock.uptimeMillis())
        var actions = 0
        assertTrue(c.delayStartActivityIfNeed(null, null, { fail("must not fall through"); true }) { actions++ })
        deadline.setLong(c, SystemClock.uptimeMillis() - 1)
        assertFalse(c.delayStartActivityIfNeed(null, null, { fail("must not fall through"); true }) { actions++ })
        assertSame(listener, c.specialSceneExitTimeOutListener)
        assertNotNull(c.transitionFinishTimeOutListener)
        advance(); assertEquals(0, actions)
    }
}
