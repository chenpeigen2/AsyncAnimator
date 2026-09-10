package com.asyncanimator.control

import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AnimationBetweenStateTest {
    private val controller = AnimationController(isTablet = { true })
    @After fun tearDown() { controller.destroy() }

    private fun flag(name: String, value: Boolean) {
        AnimationController::class.java.getDeclaredField(name).apply {
            isAccessible = true
            setBoolean(controller, value)
        }
    }

    @Test fun testTransitionSetterAndPendingActionAreBothRequired() {
        controller.registerTransitionFinishTimeOutListener(100)
        controller.setBetweenTransitionEndAndFinish(true)
        assertFalse(controller.isStartActivityBetweenTransitionEndAndFinish)
        assertTrue(controller.delayStartActivityIfNeed(null, null, { true }) {})
        assertTrue(controller.isStartActivityBetweenTransitionEndAndFinish)
        controller.setBetweenTransitionEndAndFinish(false)
        assertFalse(controller.isStartActivityBetweenTransitionEndAndFinish)
        controller.setBetweenTransitionEndAndFinish(true)
        assertTrue(controller.isStartActivityBetweenTransitionEndAndFinish)
    }

    @Test fun testNullActionDoesNotCountAsPendingStart() {
        // Seed the flag independently so this assertion catches the old flag-only getter.
        flag("isBetweenTransitionEndAndFinish", true)
        controller.registerTransitionFinishTimeOutListener(100)
        assertTrue(controller.delayStartActivityIfNeed(null, null, { true }, null))
        assertFalse(controller.isStartActivityBetweenTransitionEndAndFinish)
    }

    @Test fun testTimeoutClearsQueryBeforeInvokingPendingAction() {
        controller.registerTransitionFinishTimeOutListener(100)
        controller.setBetweenTransitionEndAndFinish(true)
        var calls = 0
        assertTrue(controller.delayStartActivityIfNeed(null, null, { true }) {
            assertFalse(controller.isStartActivityBetweenTransitionEndAndFinish)
            calls++
        })
        assertTrue(controller.isStartActivityBetweenTransitionEndAndFinish)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(1, calls)
        assertFalse(controller.isStartActivityBetweenTransitionEndAndFinish)
    }

    @Test fun testResetClearsBetweenFlagsAndPendingAction() {
        controller.registerTransitionFinishTimeOutListener(100)
        controller.setBetweenTransitionEndAndFinish(true)
        assertTrue(controller.delayStartActivityIfNeed(null, null, { true }) {})
        assertTrue(controller.isStartActivityBetweenTransitionEndAndFinish)
        controller.reset()
        assertFalse(controller.isStartActivityBetweenTransitionEndAndFinish)
    }

    @Test fun testAppExitSetterCanClearNavigationConjunct() {
        controller.setOnAppExit(AppExitScene(true, true, true)); controller.setBetweenAppExitTransitionEndAndFinish(true)
        // AppExit sets only the navigation branch; clearing its between window must allow launch.
        controller.setBetweenAppExitTransitionEndAndFinish(false)
        controller.registerSpecialSceneExitTimeOutListener(100)
        assertFalse(controller.delayStartActivityIfNeed(null, null, null) {})
    }

    @Test fun testAppExitSetterIsIgnoredUntilNavigationExitIsActive() {
        controller.setBetweenAppExitTransitionEndAndFinish(true)
        // Only activate navigation now; a previously ignored setter must not be remembered.
        flag("isNavModeLandScapeOnAppExit", true)
        controller.registerSpecialSceneExitTimeOutListener(100)
        assertFalse(controller.delayStartActivityIfNeed(null, null, null) {})
        // The no-match path clears gesture flags; activate navigation for the next request.
        flag("isNavModeLandScapeOnAppExit", true)
        controller.setBetweenAppExitTransitionEndAndFinish(true)
        controller.registerSpecialSceneExitTimeOutListener(100)
        assertTrue(controller.delayStartActivityIfNeed(null, null, null) {})
    }
}
