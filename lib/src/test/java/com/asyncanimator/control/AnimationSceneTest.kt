package com.asyncanimator.control

import java.time.Duration
import android.os.Looper
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
class AnimationSceneTest {
    private val c = AnimationController()
    @After fun cleanup() { c.destroy() }

    @Test fun testNullGestureStopsProcessing() {
        c.setOnceGestureProcessing(GestureScene())
        assertTrue(c.onceGestureProcessing)
        c.setOnceGestureProcessing(null)
        assertFalse(c.onceGestureProcessing)
    }

    @Test fun testNullAppExitDoesNotArmLandscape() {
        c.setOnAppExit(null)
        c.registerSpecialSceneExitTimeOutListener(100)
        assertFalse(c.delayStartActivityIfNeed(null, null, null) {})
    }

    @Test fun testAppExitRequiresEveryDevicePredicate() {
        for (mask in 0..15) {
            c.reset()
            c.specialSceneExitTimeOutListener?.dispose()
            c.setOnAppExit(AppExitScene(mask and 1 != 0, mask and 2 != 0,
                mask and 4 != 0, mask and 8 != 0))
            assertEquals("mask=$mask", mask == 7, c.specialSceneExitTimeOutListener != null)
        }
    }

    @Test fun testAppExitWaitsForExplicitBetweenWindowAndExpiresAt1500ms() {
        c.setOnAppExit(AppExitScene(true, true, true))
        assertNotNull(c.specialSceneExitTimeOutListener)
        c.setBetweenAppExitTransitionEndAndFinish(true)
        var launches = 0
        assertTrue(c.delayStartActivityIfNeed(null, null, null) { launches++ })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1499))
        assertEquals(0, launches)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(1, launches)
        assertNull(c.specialSceneExitTimeOutListener)
    }

    @Test fun testAppExitDoesNotInventBetweenWindow() {
        c.setOnAppExit(AppExitScene(true, true, true))
        assertFalse(c.delayStartActivityIfNeed(null, null, null) {})
    }

    @Test fun testGestureRegistrationMatrix() {
        for (mask in 0..15) {
            c.destroy()
            val scene = GestureScene(landscape = mask and 1 != 0,
                splitScreen = mask and 2 != 0, recentContinuation = mask and 4 != 0,
                tablet = mask and 8 != 0)
            c.setOnceGestureProcessing(scene)
            val special = scene.landscape && (scene.splitScreen || scene.recentContinuation)
            val transition = !scene.landscape || (!scene.splitScreen && !scene.recentContinuation && scene.tablet)
            assertEquals("special mask=$mask", special, c.specialSceneExitTimeOutListener != null)
            assertEquals("transition mask=$mask", transition, c.transitionFinishTimeOutListener != null)
        }
    }

    @Test fun testLandscapeGestureExitArms2500msOnlyForSharedHomeOverview() {
        c.setOnceGestureProcessing(GestureScene(landscape = true, homeAndOverviewSame = true))
        assertNull(c.specialSceneExitTimeOutListener)
        c.setOnceGestureProcessing(null)
        assertNotNull(c.specialSceneExitTimeOutListener)
        var launches = 0
        assertTrue(c.delayStartActivityIfNeed(null, null, null) { launches++ })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2499))
        assertEquals(0, launches)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(1, launches)
    }

    @Test fun testUnsharedOrTabletLandscapeGestureExitDoesNotArmTimer() {
        for (scene in listOf(GestureScene(landscape = true),
            GestureScene(landscape = true, tablet = true, homeAndOverviewSame = true))) {
            c.destroy()
            c.setOnceGestureProcessing(scene)
            c.setOnceGestureProcessing(null)
            assertNull(c.specialSceneExitTimeOutListener)
        }
    }

    @Test fun testGesturePackagePrefersBaseFallsBackToTopAndClears() {
        c.setOnceGestureProcessing(GestureScene(baseActivityPackage = "base", topActivityPackage = "top"))
        assertEquals("base", c.swipingUpActivityPkg)
        c.setOnceGestureProcessing(GestureScene(topActivityPackage = "top"))
        assertEquals("top", c.swipingUpActivityPkg)
        c.setOnceGestureProcessing(null)
        assertNull(c.swipingUpActivityPkg)
        c.setOnceGestureProcessing(GestureScene(baseActivityPackage = "base"))
        c.reset()
        assertNull(c.swipingUpActivityPkg)
    }
}
