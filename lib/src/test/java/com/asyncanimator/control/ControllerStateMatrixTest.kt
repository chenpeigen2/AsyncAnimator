package com.asyncanimator.control

import android.animation.AnimatorSet
import android.os.Looper
import com.asyncanimator.anim.CustomRectFSpringAnim
import com.asyncanimator.manager.OplusAnimManager
import com.asyncanimator.seq.AnimSeqTimeStamp
import java.time.Duration
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
class ControllerStateMatrixTest {
    private val c = AnimationController(canFinishRecent = { true })
    private var wasEnabled = true
    private fun factory() = object : RemoteAnimationFactory {
        override fun createAnimation() = AnimatorSet()
        override fun onAnimationFinished() {}
    }
    private fun rect() = CustomRectFSpringAnim(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME)
    private fun seed(state: AnimationState) {
        AnimationController::class.java.getDeclaredField("animState").apply {
            isAccessible = true; set(c, state)
        }
    }
    @Before fun setup() {
        wasEnabled = OplusAnimManager.interruptionEnabled
        OplusAnimManager.interruptionEnabled = false
        OplusAnimManager.interruptionEnabled = true
        AnimSeqTimeStamp.resetAllForTest()
    }
    @After fun cleanup() {
        c.destroy()
        OplusAnimManager.interruptionEnabled = false
        OplusAnimManager.interruptionEnabled = wasEnabled
        AnimSeqTimeStamp.resetAllForTest()
    }

    @Test fun testAllTwelveEnumNamesOrderAndTaskbarRolesMatchReference() {
        val expected = listOf("NONE" to (false to false), "OPEN" to (false to false),
            "REVERSE_OPEN" to (true to false), "CLOSE" to (true to true),
            "MULTI_OPEN" to (true to false), "MULTI_REVERSE_OPEN" to (true to false),
            "MULTI_CLOSE" to (true to true), "WAITING" to (false to false),
            "MULTI_WAITING" to (false to false), "UNKNOWN" to (false to false),
            "SWIPE_UP_TO_CAPSULE" to (true to true), "SWIPE_UP_TO_SPLIT_OR_FLOATING" to (true to true))
        assertEquals(expected, AnimationState.entries.map {
            it.name to (it.withTaskbarAlignment to it.taskbarAlignmentToLauncher)
        })
    }

    @Test fun testLaunchEndWithoutGestureAndWithoutRecentsResetsEveryInputState() {
        for (state in AnimationState.entries) {
            c.reset(); val f = factory()
            c.appLaunchAnimStartOrEnd(false, f, null); seed(state)
            var finished = 0
            c.appLaunchAnimFinishCallback = { finished++ }
            c.appLaunchAnimStartOrEnd(true, f, null)
            assertEquals("$state", AnimationState.NONE, c.animState)
            assertEquals(1, finished)
            assertFalse(c.forbidTouch())
        }
    }

    @Test fun testCleanupWithNoLaunchResetsEveryStateAndConsumesCallbacksOnce() {
        for (state in AnimationState.entries) {
            c.reset(); c.addRecentsAnim(rect(), null, null)
            c.setOnceGestureProcessing(GestureScene()); seed(state)
            var finished = 0
            c.recentsAnimFinishCallback = { finished++ }
            assertTrue(c.cleanUpRecentsAnim())
            assertEquals("$state", AnimationState.NONE, c.animState)
            assertTrue(c.allRecentsAnimationEnd)
            assertFalse(c.onceGestureProcessing)
            assertEquals(1, finished)
            assertTrue(c.cleanUpRecentsAnim())
            assertEquals(1, finished)
        }
    }

    @Test fun testCleanupWithLaunchPreservesEveryStateUntilLastFactoryEnds() {
        for (state in AnimationState.entries) {
            c.reset(); val f = factory()
            c.appLaunchAnimStartOrEnd(false, f, null)
            c.addRecentsAnim(rect(), null, null)
            c.setOnceGestureProcessing(GestureScene()); seed(state)
            var finished = 0
            c.recentsAnimFinishCallback = { finished++ }
            assertFalse(c.cleanUpRecentsAnim())
            assertEquals("$state", state, c.animState)
            assertTrue(c.allRecentsAnimationEnd)
            assertFalse(c.onceGestureProcessing)
            assertEquals(0, finished)
            c.appLaunchAnimStartOrEnd(true, f, null)
            assertEquals(AnimationState.NONE, c.animState)
            assertEquals(1, finished)
        }
    }

    @Test fun testNewRecentsDropsOldCompletionBeforeStateObserverRegistersReplacement() {
        c.addRecentsAnim(rect(), null, null)
        var stale = 0; var replacement = 0
        c.recentsAnimFinishCallback = { stale++ }
        c.addOnAnimStateChangeListener { _, next, _ ->
            if (next != AnimationState.NONE) {
                assertNull("New recents must not inherit an old finish callback", c.recentsAnimFinishCallback)
                c.recentsAnimFinishCallback = { replacement++ }
            }
        }
        c.addRecentsAnim(rect(), null, null)
        assertEquals(0, stale)
        c.cleanUpRecentsAnim()
        assertEquals(0, stale)
        assertEquals(1, replacement)
    }

    @Test fun testLaunchStartClearsOldQueuedSeqFinishBeforeNotifyingOpen() {
        val seq = OplusAnimManager.animationSeqHelper
        AnimSeqTimeStamp.updateLastRecentFinishTime()
        var stale = 0
        assertTrue(seq.delayFinishRecents { stale++ })
        c.appLaunchAnimStartOrEnd(false, factory(), null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501))
        assertEquals("Old recents finish must not fire during the new launch", 0, stale)
        assertEquals(AnimationState.OPEN, c.animState)
    }
    @Test fun testWindowRunningQueryUsesStateNotTouchTimerOrCollectionPresence() {
        for (state in AnimationState.entries) {
            seed(state)
            assertEquals("$state", state != AnimationState.NONE && state != AnimationState.UNKNOWN,
                c.isAppWindowAnimRunning)
        }
        c.reset(); val f = factory()
        c.appLaunchAnimStartOrEnd(false, f, null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertFalse(c.forbidTouch())
        assertTrue(c.isAppWindowAnimRunning)
        c.appLaunchAnimStartOrEnd(true, f, null)
        assertFalse(c.isAppWindowAnimRunning)
    }

}
