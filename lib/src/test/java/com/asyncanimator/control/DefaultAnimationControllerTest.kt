package com.asyncanimator.control

import android.animation.AnimatorSet
import com.android.launcher3.LauncherAnimationRunner
import com.asyncanimator.anim.CustomRectFSpringAnim
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class DefaultAnimationControllerTest {
    private class Exposed : DefaultAnimationController() {
        fun clearObservers() = clearOnAnimStateChangeListeners()
    }

    private fun assertDefaults(controller: DefaultAnimationController) {
        assertEquals(AnimationState.NONE, controller.animState)
        assertFalse(controller.allRecentsAnimationEnd)
        assertFalse(controller.hasRecentsAnim)
        assertFalse(controller.isOpeningAnim)
        assertFalse(controller.isMultiOpen)
        assertFalse(controller.isMultiClose)
        assertFalse(controller.isClosingAnimAndAnimClosed)
        assertFalse(controller.isAppWindowAnimRunning)
        assertFalse(controller.isStartActivityBetweenTransitionEndAndFinish)
        assertFalse(controller.forbidSwipeUpWhileStartingLandApp)
        assertFalse(controller.isCloseWidgetRemoteAnim)
        assertFalse(controller.onceGestureProcessing)
        assertFalse(controller.forbidTouch())
        assertEquals(0f, controller.openingProgress, 0f)
        assertNull(controller.clickAppView)
        assertNull(controller.runningTask)
        assertNull(controller.swipingUpActivityPkg)
        assertEquals(-1, controller.openingRemoteAnimWidgetId)
        assertNull(controller.recentsAnimFinishCallback)
        assertNull(controller.appLaunchAnimFinishCallback)
    }

    @Test fun testEveryDefaultQueryAndNoOpMutationStaysAtFallbackValues() {
        val controller = DefaultAnimationController()
        assertDefaults(controller)
        controller.enableSwipeUp()
        controller.setAppToOverviewContinuationState(true)
        controller.setBetweenAppExitTransitionEndAndFinish(true)
        controller.setBetweenTransitionEndAndFinish(true)
        controller.setClickAppView(Any())
        controller.setCloseWidgetRemoteAnim(true)
        controller.setOnAppExit(AppExitScene(true, true, true))
        controller.setOnceGestureProcessing(GestureScene(landscape = true))
        controller.setOpeningRemoteAnimWidgetId(42)
        controller.updateRunningRemoteTarget(arrayOf(Any(), null))
        controller.updateRunningTask(Any())
        for (event in TaskStateChangeTimeOutListener.Type.entries) controller.dispatchTaskStateChange(event)
        controller.forceStopAllRecentAnim()
        controller.removeTasksOnRealStart()
        controller.reset()
        assertDefaults(controller)
    }

    @Test fun testFallbackNeverOwnsFactoryRectOrCompletionActions() {
        val controller = DefaultAnimationController()
        val factory = object : RemoteAnimationFactory {
            override fun createAnimation(): AnimatorSet = error("Default must not create")
            override fun onAnimationFinished() = error("Default must not finish factory")
        }
        val rect = CustomRectFSpringAnim(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME)
        try {
            controller.recentsAnimFinishCallback = { error("Default must not retain callback") }
            controller.appLaunchAnimFinishCallback = { error("Default must not retain callback") }
            controller.addRecentsAnim(rect, Any(), arrayOf(Any()))
            controller.appLaunchAnimStartOrEnd(false, factory, arrayOf(LauncherAnimationRunner.RemoteAnimationTarget(1, Any())))
            controller.revertRecentsAnimation(rect)
            for (id in listOf(-1, 0, 10, Int.MAX_VALUE)) assertTrue(controller.canFinishRecentsAnim(rect, id))
            assertTrue(controller.cleanUpRecentsAnim())
            controller.appLaunchAnimStartOrEnd(true, factory, null)
            assertDefaults(controller)
        } finally { rect.dispose() }
    }

    @Test fun testDelayReturnsFalseWithoutInvokingPredicateOrAction() {
        val controller = DefaultAnimationController()
        assertFalse(controller.delayStartActivityIfNeed(Any(), null,
            { error("fallback must not evaluate predicate") }, { error("caller owns immediate action") }))
        assertFalse(controller.delayStartActivityIfNeed(null, null, null, null))
    }

    @Test fun testObserverReceivesEveryStatePairAndOriginalTaskWithoutChangingState() {
        val controller = DefaultAnimationController()
        val task = Any()
        val pairs = mutableListOf<Pair<AnimationState, AnimationState>>()
        val listener = OnAnimStateChangeListener { old, new, receivedTask ->
            assertSame(task, receivedTask)
            pairs.add(old to new)
        }
        controller.addOnAnimStateChangeListener(listener)
        for (old in AnimationState.entries) for (new in AnimationState.entries) {
            controller.onAnimStateChanged(old, new, task)
        }
        assertEquals(AnimationState.entries.flatMap { old -> AnimationState.entries.map { old to it } }, pairs)
        assertEquals(AnimationState.NONE, controller.animState) // Notification is not a state setter.
        controller.removeOnAnimStateChangeListener(listener)
    }

    @Test fun testDuplicateRegistrationRemovesOneEntryAndNullNeverRegisters() {
        val controller = Exposed()
        var calls = 0
        val listener = OnAnimStateChangeListener { _, _, _ -> calls++ }
        controller.addOnAnimStateChangeListener(null)
        controller.removeOnAnimStateChangeListener(null)
        controller.addOnAnimStateChangeListener(listener)
        controller.addOnAnimStateChangeListener(listener)
        controller.onAnimStateChanged(AnimationState.NONE, AnimationState.OPEN, null)
        assertEquals(2, calls)
        controller.removeOnAnimStateChangeListener(listener)
        controller.onAnimStateChanged(AnimationState.NONE, AnimationState.OPEN, null)
        assertEquals(3, calls)
        controller.clearObservers()
        controller.onAnimStateChanged(AnimationState.NONE, AnimationState.OPEN, null)
        assertEquals(3, calls)
    }

    @Test fun testObserverMutationsAffectNextSnapshotAndSupportReentrantNotification() {
        val controller = Exposed()
        val calls = mutableListOf<String>()
        val second = OnAnimStateChangeListener { _, _, _ -> calls.add("second") }
        val added = OnAnimStateChangeListener { _, _, _ -> calls.add("added") }
        var changed = false
        controller.addOnAnimStateChangeListener { _, _, _ ->
            calls.add("first")
            if (!changed) {
                changed = true
                controller.removeOnAnimStateChangeListener(second)
                controller.addOnAnimStateChangeListener(added)
                controller.onAnimStateChanged(AnimationState.OPEN, AnimationState.CLOSE, null)
            }
        }
        controller.addOnAnimStateChangeListener(second)
        controller.onAnimStateChanged(AnimationState.NONE, AnimationState.OPEN, null)
        assertEquals(listOf("first", "first", "added", "second"), calls)
        controller.clearObservers()
    }

    @Test fun testThrowingObserverFailsFastWithoutCorruptingRegistrations() {
        val controller = Exposed()
        val failure = IllegalStateException("observer failed")
        val throwing = OnAnimStateChangeListener { _, _, _ -> throw failure }
        var calls = 0
        controller.addOnAnimStateChangeListener(throwing)
        controller.addOnAnimStateChangeListener { _, _, _ -> calls++ }
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            controller.onAnimStateChanged(AnimationState.NONE, AnimationState.OPEN, null)
        })
        assertEquals(0, calls)
        controller.removeOnAnimStateChangeListener(throwing)
        controller.onAnimStateChanged(AnimationState.NONE, AnimationState.OPEN, null)
        assertEquals(1, calls)
        controller.clearObservers()
    }

    @Test fun testObserverMutationsRequireMainEvenForFallbackController() {
        val controller = Exposed()
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                val operations = listOf<() -> Unit>(
                    { controller.addOnAnimStateChangeListener(null) },
                    { controller.removeOnAnimStateChangeListener(null) },
                    { controller.clearObservers() },
                    { controller.onAnimStateChanged(AnimationState.NONE, AnimationState.OPEN, null) })
                for (operation in operations) assertThrows(IllegalStateException::class.java) { operation() }
            } catch (t: Throwable) { failure.set(t) }
        }
        thread.start(); thread.join(5000)
        assertFalse(thread.isAlive)
        failure.get()?.let { throw it }
    }
}
