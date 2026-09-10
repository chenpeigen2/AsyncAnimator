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
    @Test fun testOpaqueDescriptorDefaultsAndMutationsAreNotCopiedOrInterpreted() {
        val target = LauncherAnimationRunner.RemoteAnimationTarget()
        assertEquals(0, target.taskId)
        assertNull(target.leash)
        val leash = Any()
        target.taskId = -42
        target.leash = leash
        val factory = Factory()
        controller.appLaunchAnimStartOrEnd(false, factory, arrayOf(target))
        target.taskId = 73
        target.leash = null
        controller.updateRunningRemoteTarget(arrayOf(target))
        controller.appLaunchAnimStartOrEnd(true, factory, arrayOf(target))
        assertEquals(73, target.taskId)
        assertNull(target.leash)
        assertEquals(0, factory.created)
        assertEquals(0, factory.finished)
        assertEquals(AnimationState.NONE, controller.animState)
    }

    @Test fun testControllerResetAndDestroyNeverCallHostFactoryHooks() {
        val factory = object : RemoteAnimationFactory {
            override fun createAnimation(): AnimatorSet = error("host creates animation")
            override fun onAnimationFinished() = error("host notifies completion")
        }
        controller.appLaunchAnimStartOrEnd(false, factory, null)
        controller.reset()
        assertEquals(AnimationState.NONE, controller.animState)
        controller.appLaunchAnimStartOrEnd(false, factory, emptyArray())
        controller.destroy()
        assertEquals(AnimationState.NONE, controller.animState)
    }
}
