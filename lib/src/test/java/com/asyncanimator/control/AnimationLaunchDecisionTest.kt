package com.asyncanimator.control

import android.content.Intent
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AnimationLaunchDecisionTest {
    private val controllers = mutableListOf<AnimationController>()
    private fun controller(tablet: Boolean = false, running: (() -> Boolean)? = null) =
        AnimationController(isTablet = { tablet }, isOverviewContinuationRunning = running)
            .also(controllers::add)

    @After fun tearDown() { controllers.forEach { it.destroy() } }

    // Seed independent gesture inputs to isolate the
    // individual conjuncts to check tablet exclusion without masking it via another OR branch.
    private fun flag(controller: AnimationController, name: String, value: Boolean) {
        AnimationController::class.java.getDeclaredField(name).apply {
            isAccessible = true
            setBoolean(controller, value)
        }
    }

    @Test fun testBothOppoSearchActionsDeferUntilTransitionFinishes() {
        for (action in listOf("android.search.action.DOCK_SEARCH", "com.oppo.quicksearchbox.action.Dispatch")) {
            val c = controller()
            var calls = 0
            c.registerTransitionFinishTimeOutListener(1000)
            assertTrue(action, c.delayStartActivityIfNeed(null, Intent(action), { false }) { calls++ })
            assertEquals(0, calls)
            c.transitionFinishTimeOutListener!!.onTimeOut(TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH, 0)
            assertEquals(1, calls)
        }
    }

    @Test fun testDrawerSearchSourceDefersWithoutSpecialAction() {
        val c = controller()
        c.registerTransitionFinishTimeOutListener(1000)
        assertTrue(c.delayStartActivityIfNeed(null,
            Intent("example.NORMAL").putExtra("source", "drawer_search"), { false }) {})
    }

    @Test fun testUnrelatedAndNullIntentsDoNotDefer() {
        for (intent in listOf(null, Intent(), Intent("example.NORMAL"), Intent().putExtra("source", "other"))) {
            val c = controller()
            c.registerTransitionFinishTimeOutListener(1000)
            assertFalse(c.delayStartActivityIfNeed(null, intent, { false }) { fail("caller owns immediate launch") })
            assertNull(c.transitionFinishTimeOutListener)
        }
    }

    @Test fun testSpecialSearchStillEvaluatesCallerPredicateOnce() {
        val c = controller()
        var calls = 0
        c.registerTransitionFinishTimeOutListener(1000)
        assertTrue(c.delayStartActivityIfNeed(null, Intent("android.search.action.DOCK_SEARCH"),
            { calls++; false }) {})
        assertEquals(1, calls)
    }

    @Test fun testLandscapeOnlyDefersOnPhoneNotTablet() {
        for (tablet in listOf(false, true)) {
            val c = controller(tablet)
            flag(c, "isLandScapeGesture", true)
            c.registerSpecialSceneExitTimeOutListener(1000)
            assertEquals(!tablet, c.delayStartActivityIfNeed(null, null, null) {})
        }
    }

    @Test fun testTabletStillDefersForNavigationExitAndSplitScreen() {
        val navigation = controller(tablet = true)
        navigation.setOnAppExit(AppExitScene(true, true, true)); navigation.setBetweenAppExitTransitionEndAndFinish(true)
        navigation.registerSpecialSceneExitTimeOutListener(1000)
        assertTrue(navigation.delayStartActivityIfNeed(null, null, null) {})
        val split = controller(tablet = true)
        flag(split, "isSplitScreenGesture", true)
        split.registerSpecialSceneExitTimeOutListener(1000)
        assertTrue(split.delayStartActivityIfNeed(null, null, null) {})
    }

    @Test
    @Config(qualifiers = "sw600dp")
    fun testDefaultTabletAdapterUsesSuppliedContext() {
        val c = AnimationController().also(controllers::add)
        flag(c, "isLandScapeGesture", true)
        c.registerSpecialSceneExitTimeOutListener(1000)
        assertFalse(c.delayStartActivityIfNeed(RuntimeEnvironment.getApplication(), null, null) {})
    }

    @Test fun testRegisteredTimerDoesNotImplyContinuationIsRunning() {
        val c = controller(running = { false })
        c.registerOverviewContinuationTimeOutListener(1000)
        assertFalse(c.delayStartActivityIfNeed(null, null, null) {})
        assertNull(c.overviewContinuationTimeOutListener)
    }

    @Test fun testRunningContinuationIsNotRejectedByExpiredClockBeforeTimerDelivery() {
        val c = controller(running = { true })
        // Queue an already-due timeout without dispatching it on the paused Looper.
        c.registerOverviewContinuationTimeOutListener(-1)
        assertTrue(c.delayStartActivityIfNeed(null, null, null) {})
    }

    @Test fun testLiveProviderIsReadForEveryDecision() {
        var running = true
        val c = controller(running = { running })
        c.registerOverviewContinuationTimeOutListener(1000)
        assertTrue(c.delayStartActivityIfNeed(null, null, null) {})
        running = false
        assertFalse(c.delayStartActivityIfNeed(null, null, null) {})
        assertNull(c.overviewContinuationTimeOutListener)
    }

    @Test fun testStandaloneContinuationStateStartsAndStopsExplicitly() {
        val c = controller()
        var calls = 0
        c.setAppToOverviewContinuationState(true)
        assertTrue(c.delayStartActivityIfNeed(null, null, null) { calls++ })
        val old = c.overviewContinuationTimeOutListener!!
        c.setAppToOverviewContinuationState(false)
        assertNull(c.overviewContinuationTimeOutListener)
        old.onTimeOut(TaskStateChangeTimeOutListener.Type.ON_APP_TO_OVERVIEW_CONTINUATION, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(0, calls)
        // No stale continuation action can leak into another scenario's timeout.
        c.registerTransitionFinishTimeOutListener(100)
        c.transitionFinishTimeOutListener!!.onTimeOut(TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH, 0)
        assertEquals(0, calls)
    }

    @Test fun testStoppingOverviewDoesNotDiscardAnotherScenariosPendingLaunch() {
        val c = controller()
        var calls = 0
        c.registerTransitionFinishTimeOutListener(1000)
        c.setAppToOverviewContinuationState(true)
        assertTrue(c.delayStartActivityIfNeed(null, null, { true }) { calls++ })
        c.setAppToOverviewContinuationState(false)
        c.transitionFinishTimeOutListener!!.onTimeOut(TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH, 0)
        assertEquals(1, calls)
    }

    @Test fun testUnrelatedTimeoutCannotConsumeHigherPriorityPendingLaunch() {
        val c = controller()
        var calls = 0
        c.setOnAppExit(AppExitScene(true, true, true)); c.setBetweenAppExitTransitionEndAndFinish(true)
        c.registerSpecialSceneExitTimeOutListener(1000)
        c.setAppToOverviewContinuationState(true)
        assertTrue(c.delayStartActivityIfNeed(null, null, null) { calls++ })
        c.overviewContinuationTimeOutListener!!.onTimeOut(TaskStateChangeTimeOutListener.Type.ON_APP_TO_OVERVIEW_CONTINUATION, 0)
        assertEquals(0, calls)
        c.specialSceneExitTimeOutListener!!.onTimeOut(TaskStateChangeTimeOutListener.Type.ON_LAND_SCAPE_SCENE_EXIT, 0)
        assertEquals(1, calls)
    }

    @Test fun testRunningContinuationStillHasExactlyOnceTimeoutFallback() {
        val c = controller()
        var calls = 0
        c.setAppToOverviewContinuationState(true)
        assertTrue(c.delayStartActivityIfNeed(null, null, null) { calls++ })
        val listener = c.overviewContinuationTimeOutListener!!
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(1, calls)
        listener.onTimeOut(TaskStateChangeTimeOutListener.Type.ON_APP_TO_OVERVIEW_CONTINUATION, 100)
        assertEquals(1, calls)
        assertNull(c.overviewContinuationTimeOutListener)
        assertFalse(c.delayStartActivityIfNeed(null, null, null) {})
    }
}
