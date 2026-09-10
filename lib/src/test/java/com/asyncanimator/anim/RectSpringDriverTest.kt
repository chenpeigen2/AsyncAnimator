package com.asyncanimator.anim

import android.animation.ValueAnimator
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.asyncanimator.core.TickScheduler
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE,
    instrumentedPackages = ["com.asyncanimator.anim", "com.asyncanimator.core"])
class RectSpringDriverTest {
    private class Clock : TickScheduler {
        val callbacks = linkedSetOf<TickScheduler.FrameCallback>()
        override var frameTimeNanos = 0L
        override var frameCount = 0L
        override fun postFrameCallback(callback: TickScheduler.FrameCallback?) { callback?.let(callbacks::add) }
        override fun removeFrameCallback(callback: TickScheduler.FrameCallback?) { callbacks.remove(callback) }
        override fun start() {}
        override fun stop() {}
        fun pulse() {
            frameTimeNanos = SystemClock.uptimeMillis() * 1_000_000
            frameCount++
            callbacks.toList().forEach { it.doFrame(frameTimeNanos) }
        }
    }
    private val clock = Clock()
    private val drivers = mutableListOf<RectSpringDriver>()
    private val start = RectF(0f, 0f, 300f, 500f)
    private val target = RectF(400f, 500f, 460f, 560f)
    private fun driver(
        config: RectSpringConfig = RectSpringConfig(),
        type: CustomRectFSpringAnim.AnimType = CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME,
        from: RectF = start, to: RectF = target,
        onUpdate: (RectSpringFrame) -> Unit = {}
    ) = RectSpringDriver(from, to, type, config, 24f, 6f, 0f, 1f,
        RectSpringValues(), onUpdate, { clock }).also(drivers::add)
    private fun step(ms: Long = 16) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        clock.pulse()
    }
    private fun finish() { repeat(500) { if (clock.callbacks.isNotEmpty()) step() } }
    private fun assertRect(expected: RectF, actual: RectF) {
        assertEquals(expected.left, actual.left, 0.002f); assertEquals(expected.top, actual.top, 0.002f)
        assertEquals(expected.right, actual.right, 0.002f); assertEquals(expected.bottom, actual.bottom, 0.002f)
    }
    private fun nativeListeners(): Int {
        val field = ValueAnimator::class.java.getDeclaredField("sDurationScaleChangeListeners").apply { isAccessible = true }
        return (field.get(null) as Collection<*>).size
    }
    @After fun cleanup() { drivers.forEach { it.dispose() } }

    @Test fun testSixAxesPublishOneCoherentFrameAndFinishAtTargets() {
        val frames = mutableListOf<RectSpringFrame>()
        val d = driver(onUpdate = frames::add)
        var ends = 0
        d.start { ends++ }
        assertEquals(1, clock.callbacks.size)
        step(); assertEquals(1, frames.size); assertRect(start, frames.single().rect)
        step(); assertEquals(2, frames.size)
        assertTrue(frames.last().values.centerX > start.centerX())
        assertTrue(frames.last().values.ratio < start.height() / start.width())
        assertTrue(frames.last().values.radius < 24f)
        assertTrue(frames.last().values.alpha > 0f)
        finish()
        assertEquals(1, ends); assertTrue(clock.callbacks.isEmpty())
        assertRect(target, d.currentFrame!!.rect)
        assertEquals(6f, d.currentFrame!!.values.radius, 0f)
        assertEquals(1f, d.currentFrame!!.values.alpha, 0f)
        assertEquals(1f, d.currentFrame!!.progress, 0f)
        assertEquals(RectSpringValues(), d.currentFrame!!.velocities)
    }

    @Test fun testAllTrackingModesAndBothSizeCoordinatesEndAtCorrectGeometry() {
        for (tracking in RectSpringConfig.Tracking.values()) {
            for (type in listOf(CustomRectFSpringAnim.AnimType.OPEN_FROM_HOME, CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME)) {
                val d = driver(RectSpringConfig(tracking = tracking), type)
                d.start {}; step(); assertRect(start, d.currentFrame!!.rect)
                assertEquals(type == CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME, d.currentFrame!!.sizeIsWidth)
                finish(); assertRect(target, d.currentFrame!!.rect)
            }
        }
    }

    @Test fun testIndependentSpringConfigurationChangesOnlyItsAxisResponse() {
        val normal = driver()
        val slowX = driver(RectSpringConfig(centerX = RectSpringConfig.Spring(stiffness = 25f)))
        normal.start {}; slowX.start {}; repeat(10) { step() }
        assertTrue(normal.currentFrame!!.values.centerX > slowX.currentFrame!!.values.centerX)
        assertEquals(normal.currentFrame!!.values.trackedY, slowX.currentFrame!!.values.trackedY, 0.001f)
        assertEquals(normal.currentFrame!!.values.alpha, slowX.currentFrame!!.values.alpha, 0.001f)
    }

    @Test fun testAlphaDelayHoldsAlphaWithoutDelayingOtherAxesAndEndWaitsForAlpha() {
        val d = driver(RectSpringConfig(alphaStartDelayMillis = 2000))
        var ends = 0
        d.start { ends++ }; repeat(50) { step() }
        assertEquals(0f, d.currentFrame!!.values.alpha, 0f)
        assertTrue(d.currentFrame!!.values.centerX > start.centerX())
        assertEquals(0, ends)
        finish()
        assertEquals(1, ends); assertEquals(1f, d.currentFrame!!.values.alpha, 0f)
    }

    @Test fun testMinimumSizeAndAspectBoundsPreventInvalidRectangles() {
        val bouncy = RectSpringConfig.Spring(stiffness = 500f, dampingRatio = 0.15f)
        val d = driver(RectSpringConfig(size = bouncy, ratio = bouncy, minimumSize = 80f, limitAspectRatio = true))
        d.start {}
        repeat(100) {
            step()
            val f = d.currentFrame!!
            assertTrue(f.rect.width() >= 79.99f)
            assertTrue(f.rect.height() > 0f)
            assertTrue(f.values.ratio in 1f..(500f / 300f))
        }
        finish(); assertEquals(80f, d.currentFrame!!.values.size, 0f)
    }

    @Test fun testTargetUpdateKeepsCurrentSixVelocitiesAndConvergesToNewRect() {
        val d = driver(); d.start {}; repeat(8) { step() }
        val before = d.currentFrame!!
        val next = RectF(80f, 130f, 200f, 430f)
        d.updateEndTargetRectF(next, 15f)
        assertEquals(before.velocities, d.currentFrame!!.velocities)
        assertRect(before.rect, d.currentFrame!!.rect)
        finish(); assertRect(next, d.currentFrame!!.rect)
        assertEquals(15f, d.currentFrame!!.values.radius, 0f)
    }

    @Test fun testReverseChangesSizeCoordinateWithoutGeometryJumpAndPreservesMomentum() {
        val d = driver(); d.start {}; repeat(8) { step() }
        val before = d.currentFrame!!
        // Current ratio is > 1; REVERSE_TO_OPEN to ratio 0.5 selects height mode.
        val next = RectF(0f, 0f, 500f, 250f)
        d.reverseToOpen(next, 18f)
        val after = d.currentFrame!!
        assertTrue(before.sizeIsWidth); assertFalse(after.sizeIsWidth)
        assertRect(before.rect, after.rect)
        assertEquals(before.velocities.centerX, after.velocities.centerX, 0f)
        val expected = before.velocities.size * before.values.ratio + before.values.size * before.velocities.ratio
        assertEquals(expected, after.velocities.size, 0.002f)
        assertEquals(before.progress, after.progress, 0f)
        finish(); assertRect(next, d.currentFrame!!.rect)
        assertEquals(0f, d.currentFrame!!.progress, 0f)
    }

    @Test fun testNextFramePredictionMatchesNativeUnderCriticalAndOverDampedUpdates() {
        for (damping in listOf(0.5f, 1f, 1.6f)) {
            val spring = RectSpringConfig.Spring(dampingRatio = damping)
            val d = driver(RectSpringConfig(centerX = spring, trackedY = spring, size = spring,
                ratio = spring.copy(minimumVisibleChange = 0.005f), radius = spring, alpha = spring))
            d.start {}; repeat(5) { step() }
            val before = d.currentFrame!!
            val predicted = d.copyNextAnimState(16)
            assertSame(before, d.currentFrame)
            step()
            val actual = d.currentFrame!!
            predicted.values.array().zip(actual.values.array()).forEach { (a, b) -> assertEquals(a, b, 0.002f) }
            predicted.velocities.array().zip(actual.velocities.array()).forEach { (a, b) -> assertEquals(a, b, 0.004f) }
            d.dispose()
        }
    }

    @Test fun testPredictionDoesNotStartDelayedAlphaOrMutateItsState() {
        val d = driver(RectSpringConfig(alphaStartDelayMillis = 100))
        d.start {}; step()
        val before = d.currentFrame!!
        assertEquals(0f, d.copyNextAnimState(200).values.alpha, 0f)
        assertSame(before, d.currentFrame)
    }

    @Test fun testCancelIsConsumedOnNextFrameAndOnlyOnce() {
        val d = driver(); var ends = 0
        d.start { ends++ }; repeat(3) { step() }
        val last = d.currentFrame
        d.cancel(); d.cancel(); assertEquals(0, ends)
        assertEquals(1, clock.callbacks.size)
        step(); assertEquals(1, ends); assertSame(last, d.currentFrame)
        assertTrue(clock.callbacks.isEmpty()); step(); assertEquals(1, ends)
    }

    @Test fun testSkipEndsDelayedAndUndampedAxesOnNextTick() {
        val d = driver(RectSpringConfig(centerX = RectSpringConfig.Spring(dampingRatio = 0f), alphaStartDelayMillis = 5000))
        var ends = 0
        d.start { ends++ }; step(); d.skipToEnd()
        assertEquals(0, ends); step()
        assertEquals(1, ends); assertRect(target, d.currentFrame!!.rect)
        assertEquals(1f, d.currentFrame!!.values.alpha, 0f)
    }

    @Test fun testDisposalUnregistersNativeDurationListenersWithoutAnotherFrame() {
        val before = nativeListeners()
        val d = driver(); var ends = 0
        d.start { ends++ }
        assertEquals(before + 6, nativeListeners())
        d.dispose()
        assertEquals(before, nativeListeners())
        assertEquals(0, ends); assertTrue(clock.callbacks.isEmpty())
        assertThrows(IllegalStateException::class.java) { d.start {} }
    }

    @Test fun testCompletionCanRestartWithoutAStalePulseTouchingSuccessor() {
        val d = driver(); var ends = 0
        d.start { ends++; d.start { ends++ } }
        val oldTick = clock.callbacks.single()
        d.skipToEnd(); step()
        assertEquals(1, ends); assertEquals(1, clock.callbacks.size)
        val initial = d.currentFrame
        oldTick.doFrame(SystemClock.uptimeMillis() * 1_000_000)
        assertSame(initial, d.currentFrame)
        finish(); assertEquals(2, ends)
    }

    @Test fun testInputAndReturnedRectAreDefensiveCopies() {
        val from = RectF(start); val to = RectF(target)
        val d = driver(from = from, to = to)
        from.setEmpty(); to.setEmpty()
        d.start {}; step()
        d.currentFrame!!.rect.setEmpty()
        assertRect(start, d.currentFrame!!.rect)
        finish(); assertRect(target, d.currentFrame!!.rect)
    }

    @Test fun testInvalidGeometryParametersAndScaleFailBeforeScheduling() {
        assertThrows(IllegalArgumentException::class.java) { driver(from = RectF()) }
        assertThrows(IllegalArgumentException::class.java) { driver(to = RectF(Float.NaN, 0f, 1f, 1f)) }
        assertThrows(IllegalArgumentException::class.java) { RectSpringConfig.Spring(stiffness = -1f) }
        assertThrows(IllegalArgumentException::class.java) { RectSpringConfig(durationMultiplier = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { RectSpringConfig(alphaStartDelayMillis = -1) }
        assertTrue(clock.callbacks.isEmpty())
    }

    @Test fun testExplicitLauncherRateAndDeviceThresholdPolicies() {
        val close = CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME
        val open = CustomRectFSpringAnim.AnimType.OPEN_FROM_HOME
        assertEquals(50f, RectSpringConfig(durationMultiplier = 2f).parameters(close)[0].stiffness, 0f)
        assertEquals(200f, RectSpringConfig(durationMultiplier = 0f).parameters(close)[0].stiffness, 0f)
        assertEquals(200f, RectSpringConfig(durationMultiplier = 2f).parameters(CustomRectFSpringAnim.AnimType.GESTURE_TO_DRAG)[0].stiffness, 0f)
        assertEquals(200f / (0.8f * 0.8f * 0.36f), RectSpringConfig(durationMultiplier = 2f,
            lightAnimation = true, evaluationScene = true).parameters(close)[0].stiffness, 0.001f)
        assertEquals(1f, RectSpringConfig().parameters(open)[1].minimumVisibleChange, 0f)
        assertEquals(0.1f, RectSpringConfig(tablet = true).parameters(open)[1].minimumVisibleChange, 0f)
        assertEquals(0.001f, RectSpringConfig(foldWide = true).parameters(close)[3].minimumVisibleChange, 0f)
    }

    @Test fun testHandleLogicalEndPrecedesNextFramePhysicalEndAndDisposeIsImmediate() {
        val before = nativeListeners()
        val d = driver()
        val handle = CustomRectFSpringAnim(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME, d)
        handle.setAsyncStart(false)
        val events = mutableListOf<String>()
        handle.addListener(object : CustomRectFSpringAnim.Listener {
            override fun onCancel(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) { events.add("cancel") }
            override fun onEnd(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) { events.add("logical") }
            override fun onActualEnd(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) { events.add("actual") }
        })
        handle.start(); step(); handle.cancel()
        assertEquals(listOf("cancel", "logical"), events)
        step(); assertEquals(listOf("cancel", "logical", "actual"), events)
        handle.start(); assertEquals(before + 6, nativeListeners())
        handle.dispose(); assertEquals(before, nativeListeners())
        assertTrue(clock.callbacks.isEmpty())
    }

    @Test fun testNativeSpringsCanRunOnAnExplicitBackgroundSchedulerOwner() {
        val thread = HandlerThread("rect-spring-owner").apply { start() }
        val owner = Handler(thread.looper)
        val failure = AtomicReference<Throwable?>()
        val observed = AtomicReference<Thread?>()
        val d = driver(onUpdate = { observed.set(Thread.currentThread()) })
        fun onOwner(action: () -> Unit) {
            val done = CountDownLatch(1)
            owner.post { try { action() } catch (t: Throwable) { failure.set(t) } finally { done.countDown() } }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("background spring owner", it) }
        }
        try {
            onOwner { d.start {} }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)); onOwner { clock.pulse() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)); onOwner { clock.pulse() }
            assertSame(thread, observed.get())
            assertTrue(d.currentFrame!!.values.centerX > start.centerX())
            assertThrows(IllegalStateException::class.java) { d.cancel() }
        } finally {
            onOwner { d.dispose() }
            thread.quitSafely(); thread.join(5000); assertFalse(thread.isAlive)
        }
    }

    @Test fun testContinuationStartsFromPredictedStateWithCoordinateConvertedVelocity() {
        val d = driver(); d.start {}; repeat(8) { step() }
        val predicted = d.copyNextAnimState(16)
        // Switch from width to height mode for the continuation.
        val next = RectF(60f, 80f, 100f, 300f)
        val successor = d.createContinuation(next, 16) {}.also(drivers::add)
        d.dispose(); successor.start {}
        assertRect(predicted.rect, successor.currentFrame!!.rect)
        assertEquals(predicted.progress, successor.currentFrame!!.progress, 0f)
        assertFalse(successor.currentFrame!!.sizeIsWidth)
        assertEquals(predicted.velocities.centerX, successor.currentFrame!!.velocities.centerX, 0f)
        val expectedSizeVelocity = predicted.velocities.size * predicted.values.ratio +
            predicted.values.size * predicted.velocities.ratio
        assertEquals(expectedSizeVelocity, successor.currentFrame!!.velocities.size, 0.002f)
        finish(); assertRect(next, successor.currentFrame!!.rect)
    }

    @Test fun testThrowingUpdateStillDetachesSpringsAndReportsPhysicalCompletion() {
        val before = nativeListeners()
        val d = driver(onUpdate = { throw IllegalStateException("consumer") })
        var ends = 0
        d.start { ends++ }
        assertThrows(IllegalStateException::class.java) { step() }
        assertEquals(1, ends); assertEquals(before, nativeListeners())
        assertTrue(clock.callbacks.isEmpty())
    }


    @Test fun testMultiAnimatorSetWaitsForTheActualSixAxisDriverToConsumeCancel() {
        val d = driver()
        val rect = CustomRectFSpringAnim(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME, d)
        rect.setAsyncStart(false)
        val group = MultiAnimatorSet(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME)
        var ends = 0
        group.addListener(object : com.asyncanimator.playback.NullableAnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { ends++ }
        })
        group.play(rect)
        group.start(); step()
        group.cancel()
        assertEquals(0, ends)
        step()
        assertEquals(1, ends)
        assertTrue(clock.callbacks.isEmpty())
    }


    @Test fun testReverseContinuationPreservesProgressDirectionAndEndsAtZero() {
        val d = driver(); d.start {}; repeat(8) { step() }
        val destination = RectF(0f, 0f, 500f, 250f)
        d.reverseToOpen(destination, 18f); repeat(4) { step() }
        val predicted = d.copyNextAnimState(16)
        val successor = d.createContinuation(destination, 16) {}.also(drivers::add)
        d.dispose(); successor.start {}
        assertEquals(predicted.progress, successor.currentFrame!!.progress, 0f)
        finish(); assertEquals(0f, successor.currentFrame!!.progress, 0f)
        assertRect(destination, successor.currentFrame!!.rect)
    }

    @Test fun testPredictionIncludesFinalProgressWhenOnlyPositionChanges() {
        val destination = RectF(100f, 100f, 400f, 600f) // size and aspect ratio remain unchanged
        val d = driver(to = destination); d.start {}
        var frames = 0
        while (clock.callbacks.isNotEmpty() && frames++ < 500) {
            val predicted = d.copyNextAnimState(16)
            step()
            assertEquals(predicted.progress, d.currentFrame!!.progress, 0.0001f)
        }
        assertTrue(clock.callbacks.isEmpty())
        assertEquals(1f, d.currentFrame!!.progress, 0f)
    }

    @Test fun testLongFrameGapMatchesClosedFormWithoutInventedDtClamp() {
        for (damping in listOf(0.5f, 1f, 2f)) {
            val d = driver(config = RectSpringConfig(
                centerX = RectSpringConfig.Spring(100f, damping)))
            d.start {}; step()
            val expected = d.copyNextAnimState(250)
            step(250)
            assertEquals(expected.values.centerX, d.currentFrame!!.values.centerX, 0.002f)
            assertEquals(expected.velocities.centerX, d.currentFrame!!.velocities.centerX, 0.002f)
            assertTrue(d.currentFrame!!.rect.width() > 0f)
            d.dispose()
        }
    }

    @Test fun testNativeThresholdUsesPointSevenFiveAndSixtyTwoPointFive() {
        val d = driver(config = RectSpringConfig(
            centerX = RectSpringConfig.Spring(minimumVisibleChange = 2f)))
        d.start {}
        val runField = RectSpringDriver::class.java.getDeclaredField("current").apply { isAccessible = true }
        val run = runField.get(d)!!
        val axesField = run.javaClass.getDeclaredField("axes").apply { isAccessible = true }
        val axis = (axesField.get(run) as List<*>).first()!!
        val forceField = axis.javaClass.getDeclaredField("force").apply { isAccessible = true }
        val force = forceField.get(axis) as androidx.dynamicanimation.animation.SpringForce
        val target = force.finalPosition
        assertTrue(force.isAtEquilibrium(target + 1.49f, 93f))
        assertFalse(force.isAtEquilibrium(target + 1.51f, 0f))
        assertFalse(force.isAtEquilibrium(target, 94f))
    }


    @Test fun testFirstStopWinsAndPreviewMatchesCancelVersusEndWithoutPublishingIt() {
        for (cancelFirst in listOf(false, true)) {
            val updates = mutableListOf<RectSpringFrame>()
            val d = driver(onUpdate = updates::add)
            var ends = 0
            d.start { ends++ }; step(); step()
            val before = d.currentFrame!!
            val count = updates.size
            if (cancelFirst) { d.cancel(); d.skipToEnd() } else { d.skipToEnd(); d.cancel() }
            val predicted = d.copyNextAnimState()
            assertSame(before, d.currentFrame)
            assertEquals(count, updates.size)
            assertEquals(0, ends)
            if (cancelFirst) {
                assertRect(before.rect, predicted.rect)
                assertEquals(before.velocities, predicted.velocities)
            } else {
                assertRect(target, predicted.rect)
                assertEquals(RectSpringValues(), predicted.velocities)
                assertEquals(1f, predicted.progress, 0f)
            }
            step()
            assertEquals(1, ends)
            assertEquals(count + if (cancelFirst) 0 else 1, updates.size)
            assertTrue(clock.callbacks.isEmpty())
        }
    }

    @Test fun testClearCallbackRetainsPhysicalPlaybackAndDisposeIsFinal() {
        val d = driver()
        d.start { fail("cleared callback") }
        d.clearEndCallback()
        d.skipToEnd(); step()
        assertRect(target, d.currentFrame!!.rect)
        assertTrue(clock.callbacks.isEmpty())
        d.dispose(); d.dispose(); d.cancel(); d.skipToEnd(); d.clearEndCallback()
        assertThrows(IllegalStateException::class.java) { d.start {} }
        assertThrows(IllegalArgumentException::class.java) { d.copyNextAnimState() }
        assertThrows(IllegalArgumentException::class.java) { d.createContinuation(target) {} }
    }

    @Test fun testDuplicateStartAndInvalidRetargetDoNotReplaceRunningStateOrCompletion() {
        val d = driver(); var ends = 0
        d.start { ends++ }; step(); step()
        val before = d.currentFrame
        assertThrows(IllegalStateException::class.java) { d.start { fail("replacement callback") } }
        assertThrows(IllegalArgumentException::class.java) { d.updateEndTargetRectF(RectF()) }
        assertThrows(IllegalArgumentException::class.java) { d.updateEndTargetRectF(target, Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { d.reverseToOpen(target, -1f) }
        assertThrows(IllegalArgumentException::class.java) { d.copyNextAnimState(-1) }
        assertSame(before, d.currentFrame)
        assertEquals(1, clock.callbacks.size)
        d.skipToEnd(); step()
        assertEquals(1, ends)
    }

    @Test fun testSchedulingFailureDetachesAllNativeRegistrationsAndAllowsRetry() {
        val before = nativeListeners()
        val failure = IllegalStateException("frame registration")
        var reject = true
        val source = object : TickScheduler by clock {
            override fun postFrameCallback(callback: TickScheduler.FrameCallback?) {
                if (reject) throw failure
                clock.postFrameCallback(callback)
            }
        }
        val d = RectSpringDriver(start, target, CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME,
            RectSpringConfig(), 0f, 0f, 1f, 1f, RectSpringValues(), {}, { source }).also(drivers::add)
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            d.start { fail("failed start must not complete successfully") }
        })
        assertEquals(before, nativeListeners())
        assertTrue(clock.callbacks.isEmpty())
        reject = false
        var ends = 0
        d.start { ends++ }; d.skipToEnd(); step()
        assertEquals(1, ends)
        assertEquals(before, nativeListeners())
    }

    @Test fun testThrowingTerminalUpdateStillCompletesAndReleasesOnSkip() {
        val before = nativeListeners()
        val failure = AssertionError("terminal update")
        val d = driver(onUpdate = { throw failure })
        var ends = 0
        d.start { ends++ }; d.skipToEnd()
        assertSame(failure, assertThrows(AssertionError::class.java) { step() })
        assertEquals(1, ends)
        assertTrue(clock.callbacks.isEmpty())
        assertEquals(before, nativeListeners())
    }

    @Test fun testReentrantDisposeFromUpdateDoesNotDeliverCompletionOrAnotherFrame() {
        val before = nativeListeners()
        lateinit var d: RectSpringDriver
        var updates = 0
        d = driver(onUpdate = { updates++; d.dispose() })
        d.start { fail("disposal is silent") }
        step(); step()
        assertEquals(1, updates)
        assertTrue(clock.callbacks.isEmpty())
        assertEquals(before, nativeListeners())
    }

    @Test fun testZeroDurationScaleStartsDelayedAlphaAndFinishesAtTarget() {
        val previous = ValueAnimator.getDurationScale()
        val d = driver(RectSpringConfig(alphaStartDelayMillis = 10000))
        var ends = 0
        try {
            ValueAnimator.setDurationScale(0f)
            d.start { ends++ }
            repeat(3) { step() }
            assertEquals(1, ends)
            assertRect(target, d.currentFrame!!.rect)
            assertEquals(1f, d.currentFrame!!.values.alpha, 0f)
            assertTrue(clock.callbacks.isEmpty())
        } finally { d.dispose(); ValueAnimator.setDurationScale(previous) }
    }

    @Test fun testInvalidRadiusAlphaAndEachVelocityComponentFailBeforeNativeRegistration() {
        val before = nativeListeners()
        fun construct(radius: Float = 0f, alpha: Float = 1f, velocity: RectSpringValues = RectSpringValues()) =
            RectSpringDriver(start, target, startRadius = radius, startAlpha = alpha,
                initialVelocity = velocity, onUpdate = {})
        for (invalid in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { construct(radius = invalid) }
        }
        for (invalid in listOf(-0.1f, 1.1f, Float.NaN, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { construct(alpha = invalid) }
        }
        for (index in 0..5) {
            val velocity = RectSpringValues.from(FloatArray(6).also { it[index] = Float.NaN })
            assertThrows(IllegalArgumentException::class.java) { construct(velocity = velocity) }
        }
        assertEquals(before, nativeListeners())
        assertTrue(clock.callbacks.isEmpty())
    }

}
