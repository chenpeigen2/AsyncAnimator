package com.asyncanimator.playback

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.util.FloatProperty
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackCompletionTest {
    private fun controller(root: AnimatorSet = AnimatorSet()) =
        AnimatorPlaybackController(root, 200, emptyList())

    // Exercise duplicate/reentrant listener events without relying on platform end()'s own guard.
    private fun completion(controller: AnimatorPlaybackController) =
        checkNotNull(controller.animationPlayer.listeners).single()

    @Test fun testForceCloseDoesNothingBeforeStartOrAfterPauseOrEnd() {
        val c = controller()
        var ends = 0
        var cancels = 0
        c.endActions["end"] = { ends++ }
        c.cancelAction = { cancels++ }
        c.forceFinishIfCloseToEnd()
        assertEquals(0, ends)
        assertEquals(setOf("end"), c.endActions.keys)
        c.start()
        c.pause()
        assertEquals(1, cancels)
        c.forceFinishIfCloseToEnd()
        assertEquals(0, ends)
        assertEquals(setOf("end"), c.endActions.keys)
        c.start()
        c.animationPlayer.end()
        assertEquals(1, ends)
        c.forceFinishIfCloseToEnd()
        assertEquals(1, ends)
        assertFalse(c.animationPlayer.isRunning)
    }

    @Test fun testForceCloseRequiresRunningAndStrictlyMoreThan95Percent() {
        val c = controller()
        var ends = 0
        c.endActions["end"] = { ends++ }
        c.start()
        c.animationPlayer.setCurrentFraction(0.95f)
        c.forceFinishIfCloseToEnd()
        assertTrue(c.animationPlayer.isRunning)
        assertEquals(0, ends)
        c.animationPlayer.setCurrentFraction(0.951f)
        c.forceFinishIfCloseToEnd()
        assertFalse(c.animationPlayer.isRunning)
        assertEquals(1, ends)
        // Unconditional running-only finish has the same idle guard.
        c.forceFinishIfNeed()
        assertEquals(1, ends)
    }

    @Test fun testPauseRetainsEndActionsForRestartAndDoesNotDispatchRootCancel() {
        val calls = mutableListOf<String>()
        val root = AnimatorSet().apply {
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) { calls.add("rootCancel") }
                override fun onAnimationEnd(animation: Animator) { calls.add("rootEnd") }
            })
        }
        val c = controller(root)
        c.cancelAction = { calls.add("cancel") }
        c.endActions["end"] = { calls.add("success") }
        c.start()
        c.pause()
        assertEquals(listOf("cancel"), calls)
        assertEquals(setOf("end"), c.endActions.keys)
        c.start()
        c.animationPlayer.end()
        assertEquals(listOf("cancel", "rootEnd", "success"), calls)
        assertTrue(c.endActions.isEmpty())
        c.dispatchOnCancel()
        assertEquals("rootCancel", calls.last())
        assertEquals(1, calls.count { it == "cancel" })
    }

    @Test fun testCompletionConsumesSnapshotBeforeRootAndActionCallbacksMutateMap() {
        val calls = mutableListOf<String>()
        val root = AnimatorSet()
        val c = controller(root)
        var firstRootEnd = true
        root.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                calls.add("root")
                if (firstRootEnd) {
                    firstRootEnd = false
                    c.endActions["rootNext"] = { calls.add("rootNext") }
                }
            }
        })
        c.endActions["first"] = {
            calls.add("first")
            c.endActions.remove("second")
            c.endActions["actionNext"] = { calls.add("actionNext") }
        }
        c.endActions["second"] = { calls.add("second") }
        val listener = completion(c)
        listener.onAnimationStart(c.animationPlayer)
        listener.onAnimationEnd(c.animationPlayer)
        assertEquals(listOf("root", "first", "second"), calls)
        assertEquals(setOf("rootNext", "actionNext"), c.endActions.keys)
        listener.onAnimationStart(c.animationPlayer)
        listener.onAnimationEnd(c.animationPlayer)
        assertEquals(listOf("root", "first", "second", "root", "rootNext", "actionNext"), calls)
    }

    @Test fun testReentrantSuccessDoesNotRedispatchRootOrActions() {
        val root = AnimatorSet()
        val c = controller(root)
        val listener = completion(c)
        var rootEnds = 0
        var actions = 0
        root.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                rootEnds++
                if (rootEnds == 1) listener.onAnimationEnd(c.animationPlayer)
            }
        })
        c.endActions["end"] = { actions++ }
        listener.onAnimationStart(c.animationPlayer)
        listener.onAnimationEnd(c.animationPlayer)
        listener.onAnimationEnd(c.animationPlayer)
        assertEquals(1, rootEnds)
        assertEquals(1, actions)
    }

    @Test fun testCompletionCallbackCanStartNextGenerationWithoutOuterCleanupErasingIt() {
        val c = controller()
        val listener = completion(c)
        val calls = mutableListOf<String>()
        c.endActions["first"] = {
            calls.add("first")
            listener.onAnimationStart(c.animationPlayer)
            c.endActions["next"] = { calls.add("next") }
        }
        listener.onAnimationStart(c.animationPlayer)
        listener.onAnimationEnd(c.animationPlayer)
        assertEquals(setOf("next"), c.endActions.keys)
        listener.onAnimationEnd(c.animationPlayer)
        assertEquals(listOf("first", "next"), calls)
        assertTrue(c.endActions.isEmpty())
    }

    @Test fun testThrowingCompletionIsConsumedAndFutureActionsRemainAvailable() {
        val c = controller()
        val listener = completion(c)
        val failure = IllegalStateException("end failed")
        var attempts = 0
        var future = 0
        var remainingSnapshot = 0
        c.endActions["failure"] = {
            attempts++
            c.endActions["future"] = { future++ }
            throw failure
        }
        c.endActions["remaining"] = { remainingSnapshot++ }
        listener.onAnimationStart(c.animationPlayer)
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            listener.onAnimationEnd(c.animationPlayer)
        })
        listener.onAnimationEnd(c.animationPlayer)
        assertEquals(1, attempts)
        assertEquals(0, remainingSnapshot) // Deliberate fail-fast; never replay an old completion.
        assertEquals(setOf("future"), c.endActions.keys)
        listener.onAnimationStart(c.animationPlayer)
        listener.onAnimationEnd(c.animationPlayer)
        assertEquals(1, future)
    }

    @Test fun testZeroDurationHoldersFinishWithoutNaNAtZeroProgress() {
        for ((childDuration, totalDuration) in listOf(0L to 200f, 0L to 0f, 100L to 0f)) {
            val child = ValueAnimator.ofFloat(2f, 10f).apply {
                duration = childDuration
                interpolator = Interpolators.LINEAR
            }
            val holder = AnimatorPlaybackController.Holder(child, totalDuration)
            assertEquals(0f, holder.globalEndProgress, 0f)
            holder.setProgress(0f)
            assertEquals(10f, child.animatedValue as Float, 0f)
            holder.reset()
            holder.setProgress(0.5f)
            assertEquals(10f, child.animatedValue as Float, 0f)
        }
        class Target(var value: Float = 2f)
        val property = object : FloatProperty<Target>("value") {
            override fun get(target: Target) = target.value
            override fun setValue(target: Target, value: Float) { target.value = value }
        }
        val target = Target()
        PendingAnimation(0).addFloat(target, property, 2f, 10f, Interpolators.LINEAR)
            .createPlaybackController().setPlayFraction(0f)
        assertEquals(10f, target.value, 0f)
    }

    @Test fun testPendingFlagFiveFalseTouchpointsRemainAligned() {
        val root = AnimatorSet()
        val c = controller(root)
        val field = AnimatorPlaybackController::class.java.getDeclaredField("isDispatchStartPending")
            .apply { isAccessible = true }
        fun pending() = field.getBoolean(c)
        assertFalse(pending())
        c.dispatchOnStart()
        assertTrue(pending())
        c.dispatchOnCancel()
        assertFalse(pending())
        c.dispatchOnStart()
        c.dispatchOnEnd()
        assertFalse(pending())
        c.dispatchOnStart()
        checkNotNull(root.listeners).forEach { it.onAnimationStart(root) }
        assertFalse(pending())
        c.dispatchOnStart()
        c.start()
        assertFalse(pending())
        c.pause()
        c.dispatchOnStart()
        c.reverse()
        assertFalse(pending())
        c.pause()
    }
}
