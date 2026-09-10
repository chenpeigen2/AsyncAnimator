package com.asyncanimator.control

import android.animation.AnimatorSet
import com.android.launcher3.LauncherAnimationRunner
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class RemoteAnimationBoundaryTest {
    private val controller = AnimationController()
    private class Factory : RemoteAnimationFactory {
        var created = 0
        var finished = 0
        override fun createAnimation() = AnimatorSet().also { created++ }
        override fun onAnimationFinished() { finished++ }
    }
    @After fun cleanup() { controller.destroy() }

    @Test fun testNonEmptyOpaqueTargetsDoNotInvokePlatformFieldsOrFactoryMethods() {
        val factory = Factory(); val leash = Any()
        val target = LauncherAnimationRunner.RemoteAnimationTarget(42, leash)
        val targets = arrayOf(target)
        controller.appLaunchAnimStartOrEnd(false, factory, targets)
        assertEquals(AnimationState.OPEN, controller.animState)
        controller.appLaunchAnimStartOrEnd(true, factory, targets)
        assertEquals(AnimationState.NONE, controller.animState)
        assertEquals(42, target.taskId)
        assertSame(leash, target.leash)
        assertEquals(0, factory.created)
        assertEquals(0, factory.finished)
    }

    @Test fun testNullAndEmptyTargetsRemainValidLocalLifecycleInputs() {
        for (targets in listOf(null, emptyArray<LauncherAnimationRunner.RemoteAnimationTarget>())) {
            val factory = Factory()
            controller.appLaunchAnimStartOrEnd(false, factory, targets)
            assertEquals(AnimationState.OPEN, controller.animState)
            controller.appLaunchAnimStartOrEnd(true, factory, targets)
            assertEquals(AnimationState.NONE, controller.animState)
        }
    }

    @Test fun testFeatureOffControllerDoesNotAcquireOrFinishFactory() {
        val factory = Factory()
        val fallback = DefaultAnimationController()
        val targets = arrayOf(LauncherAnimationRunner.RemoteAnimationTarget())
        fallback.appLaunchAnimStartOrEnd(false, factory, targets)
        fallback.appLaunchAnimStartOrEnd(true, factory, targets)
        assertEquals(0, factory.created)
        assertEquals(0, factory.finished)
        assertEquals(AnimationState.NONE, fallback.animState)
    }
}
