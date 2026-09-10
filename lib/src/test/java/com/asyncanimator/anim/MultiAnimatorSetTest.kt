package com.asyncanimator.anim

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.asyncanimator.playback.NullableAnimatorListenerAdapter
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class MultiAnimatorSetTest {
    private val groups = mutableListOf<MultiAnimatorSet>()
    private val ownedSprings = mutableListOf<SpringAnimation>()
    private val asyncQueue = ArrayDeque<() -> Unit>()
    private val mainQueue = ArrayDeque<() -> Unit>()
    private val type = CustomRectFSpringAnim.AnimType.OPEN_FROM_HOME

    @Before fun pauseFrames() { ShadowChoreographer.setPaused(true) }
    @After fun cleanUp() {
        groups.forEach { it.destroy() }
        ownedSprings.forEach { it.cancel() }
        flushAsync(); flushMain()
    }

    private fun group(enabled: Boolean = true) = MultiAnimatorSet(type, AnimatorSet(), AnimatorSet(),
        { asyncQueue.addLast(it) }, { mainQueue.addLast(it) }, { enabled }).also(groups::add)
    private fun value(duration: Long = 1000) = ValueAnimator.ofFloat(0f, 1f).setDuration(duration)
    private fun spring() = SpringAnimation(FloatValueHolder(0f)).apply {
        spring = SpringForce(1f).setStiffness(100f).setDampingRatio(0.7f)
    }.also(ownedSprings::add)
    private fun flushAsync() { while (asyncQueue.isNotEmpty()) asyncQueue.removeFirst()() }
    private fun flushMain() { while (mainQueue.isNotEmpty()) mainQueue.removeFirst()() }
    private class RectDriver : CustomRectFSpringAnim.Driver {
        var done: (() -> Unit)? = null
        var starts = 0
        var cancels = 0
        var ends = 0
        override fun start(onActualEnd: () -> Unit) { starts++; done = onActualEnd }
        override fun cancel() { cancels++ } // logical request deliberately does not mean physical end
        override fun skipToEnd() { ends++ }
        override fun clearEndCallback() { done = null }
        fun finish() { val callback = done; done = null; callback?.invoke() }
    }

    @Test fun testEnumExactlyMatchesSevenVendorNames() {
        assertEquals(listOf("OPEN_FROM_HOME", "REMOTE_CLOSE_TO_HOME", "REMOTE_CLOSE_TO_HOME_ASSISTANT",
            "GESTURE_TO_DRAG", "SWIPE_TO_HOME", "SWIPE_TO_HOME_ASSISTANT", "REVERSE_TO_OPEN"),
            CustomRectFSpringAnim.AnimType.entries.map { it.name })
    }

    @Test fun testTypeQueriesMatchVendorClassificationsRatherThanAllOpeningNames() {
        for (type in CustomRectFSpringAnim.AnimType.entries) {
            val group = MultiAnimatorSet(type, AnimatorSet(), AnimatorSet(),
                { asyncQueue.addLast(it) }, { mainQueue.addLast(it) }, { true }).also(groups::add)
            assertEquals(type == CustomRectFSpringAnim.AnimType.OPEN_FROM_HOME, group.isAppOpenType)
            assertEquals(type == CustomRectFSpringAnim.AnimType.GESTURE_TO_DRAG, group.isGestureToDrag)
        }
    }

    @Test fun testUndampedSpringCannotBeForcedToEndButCanBeCancelled() {
        val group = group(); val spring = spring().apply { spring.dampingRatio = 0f }
        group.play(spring); group.start()
        group.end(MultiAnimatorSet.TYPE_SPRING_ANIMATIONS)
        assertTrue(group.isRunning)
        assertTrue(spring.isRunning)
        group.cancel(MultiAnimatorSet.TYPE_SPRING_ANIMATIONS)
        assertFalse(group.isRunning)
    }

    @Test fun testEmptyGroupFinishesOnceAndDuplicateStartWhileRunningIsIgnored() {
        val group = group()
        val events = mutableListOf<String>()
        group.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationStart(animator: Animator) { events.add("start") }
            override fun onAnimationEnd(animator: Animator) { events.add("end") }
        })
        group.start()
        assertEquals(listOf("start", "end"), events)
        group.play(value())
        group.start(); group.start()
        assertEquals(listOf("start", "end", "start"), events)
        group.end()
        assertEquals(listOf("start", "end", "start", "end"), events)
    }

    @Test fun testAllFourTracksMustFinishAndAsyncEndMustReturnToMain() {
        val group = group()
        val main = value()
        val async = value()
        val spring = spring()
        val rect = RectDriver()
        var ends = 0
        group.animationId = 42
        group.setViewStateResetRunnable { assertEquals(42, it); ends++ }
        group.play(main); group.play(true, async); group.play(spring)
        group.play(CustomRectFSpringAnim(type, rect))
        group.start()
        assertFalse(async.isStarted)
        flushAsync()
        group.animatorSet.end(); spring.cancel(); rect.finish(); flushMain()
        assertEquals(0, ends)
        assertTrue(group.isRunning)
        group.asyncAnimatorSet.end()
        assertEquals(0, ends)
        flushMain()
        assertEquals(1, ends)
        assertFalse(group.isRunning)
        group.end(); group.cancel(); flushAsync(); flushMain()
        assertEquals(1, ends)
    }

    @Test fun testZeroDurationMainCannotFinishGroupBeforeRectStarts() {
        val group = group()
        val rect = RectDriver()
        var ends = 0
        group.setViewStateResetRunnable { ends++ }
        group.play(value(0))
        group.play(CustomRectFSpringAnim(type, rect))
        group.start()
        group.animatorSet.end()
        assertEquals(1, rect.starts)
        assertEquals(0, ends)
        rect.finish(); flushMain()
        assertEquals(1, ends)
    }

    @Test fun testEveryCancelMaskSelectsTracksAndDetachesExcludedSprings() {
        for (mask in 0..7) {
            val group = group()
            val main = value(); val async = value(); val spring = spring(); val rect = RectDriver()
            var ends = 0; var cancels = 0; var resets = 0
            group.addListener(object : NullableAnimatorListenerAdapter() {
                override fun onAnimationEnd(animator: Animator) { ends++ }
                override fun onAnimationCancel(animator: Animator) { cancels++ }
            })
            group.setViewStateResetRunnable { resets++ }
            group.play(main); group.play(true, async); group.play(spring)
            group.play(CustomRectFSpringAnim(type, rect))
            group.start(); flushAsync()
            group.cancel(mask); group.cancel(mask); flushAsync(); flushMain()
            assertEquals("mask=$mask", 1, cancels)
            assertEquals(mask and 1 == 0, main.isStarted)
            assertEquals(mask and 1 == 0, async.isStarted)
            assertEquals(mask and 2 == 0, spring.isRunning)
            // Rect lifecycle deduplicates terminal requests, even if the group mask is repeated.
            assertEquals(if (mask and 4 != 0) 1 else 0, rect.cancels)
            assertEquals(0, ends) // rect physical end is still outstanding, regardless of cancel mask
            group.animatorSet.end(); group.asyncAnimatorSet.end(); spring.cancel(); rect.finish(); flushMain()
            assertEquals(1, ends)
            assertEquals(if (mask and 2 != 0) 1 else 0, resets)
        }
    }

    @Test fun testEndExceptSpringDetachesRatherThanCancelsSpring() {
        val group = group(); val spring = spring(); val rect = RectDriver()
        group.play(value()); group.play(spring); group.play(CustomRectFSpringAnim(type, rect))
        group.start()
        group.endAllAnimExceptSpringAnim()
        assertTrue(spring.isRunning)
        assertEquals(1, rect.ends)
        assertFalse(group.hasRequestCancel)
        rect.finish(); flushMain()
        assertFalse(group.isRunning)
        assertTrue(spring.isRunning)
    }

    @Test fun testLiveAddedSpringExtendsCompletionAndDeduplicatesRegistration() {
        val group = group(); val rect = RectDriver(); val spring = spring()
        group.play(CustomRectFSpringAnim(type, rect)); group.start()
        group.play(spring); group.play(spring)
        rect.finish(); flushMain()
        assertTrue(group.isRunning)
        spring.cancel()
        assertFalse(group.isRunning)
    }

    @Test fun testLiveAnimatorOverloadDoesNotMeanAsyncAndIsCancelledAtAggregateEnd() {
        val group = group(); val rect = RectDriver(); val extra = value()
        group.play(CustomRectFSpringAnim(type, rect)); group.start()
        group.play(extra, true)
        assertTrue(extra.isStarted)
        assertTrue(group.asyncAnimatorSet.childAnimations.isEmpty())
        rect.finish(); flushMain()
        assertFalse(extra.isStarted)
    }

    @Test fun testLogicalRectCancelDoesNotConsumePhysicalEndBarrier() {
        val group = group(); val rect = RectDriver()
        group.play(CustomRectFSpringAnim(type, rect)); group.start()
        group.cancel()
        assertTrue(group.hasRequestCancel)
        assertTrue(group.isRunning)
        rect.finish(); flushMain()
        assertFalse(group.isRunning)
    }

    @Test fun testDestroyInvalidatesQueuedStartAndLateRectEnd() {
        val group = group(); val rect = RectDriver(); val async = value()
        var starts = 0; var ends = 0
        async.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) { starts++ }
        })
        group.setViewStateResetRunnable { ends++ }
        group.play(true, async); group.play(CustomRectFSpringAnim(type, rect)); group.start()
        val lateEnd = rect.done!!
        group.destroy(); group.destroy(); flushAsync()
        lateEnd(); flushMain()
        assertEquals(0, starts)
        assertEquals(0, ends)
        assertFalse(group.isRunning)
        assertThrows(IllegalStateException::class.java) { group.start() }
    }

    @Test fun testReentrantResetCallbackKeepsSuccessorCallback() {
        val group = group(); val events = mutableListOf<Int>()
        group.setViewStateResetRunnable {
            events.add(1)
            group.setViewStateResetRunnable { events.add(2) }
            group.start()
        }
        group.start()
        assertEquals(listOf(1, 2), events)
    }

    @Test fun testCancelFromStartListenerIsAppliedAfterTracksAreLaunched() {
        val group = group(); var ends = 0
        group.play(value())
        group.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationStart(animator: Animator) { group.cancel() }
            override fun onAnimationEnd(animator: Animator) { ends++ }
        })
        group.start()
        assertEquals(1, ends)
        assertFalse(group.isRunning)
    }

    @Test fun testDisabledAnimationsEndClockTracksButStillWaitForRectPhysicalEnd() {
        val group = group(enabled = false); val rect = RectDriver()
        group.play(value()); group.play(true, value()); group.play(CustomRectFSpringAnim(type, rect))
        group.start(); flushAsync(); flushMain()
        assertTrue(group.isRunning)
        assertEquals(0, rect.ends)
        rect.finish(); flushMain()
        assertFalse(group.isRunning)
    }

    @Test fun testBareRectHandleIsRejectedInsteadOfPretendingToAnimate() {
        assertThrows(IllegalArgumentException::class.java) { group().play(CustomRectFSpringAnim(type)) }
    }

    @Test fun testRectAnimatorAdapterActuallyDrivesValuesAndFinishesAggregate() {
        val group = group(); val values = mutableListOf<Float>()
        val animator = value().apply { addUpdateListener { values.add(it.animatedValue as Float) } }
        group.play(CustomRectFSpringAnim(type, animator)); group.start(); group.end(); flushMain()
        assertEquals(1f, values.last(), 0f)
        assertFalse(group.isRunning)
        assertEquals(0, animator.listeners?.size ?: 0)
    }

    @Test fun testRealHandlerThreadOwnsAsyncAnimatorAndCompletionIsMarshalledToMain() {
        val thread = HandlerThread("multi-test.anim").apply { start() }
        try {
            val handler = Handler(thread.looper)
            val mainHandler = Handler(Looper.getMainLooper())
            val group = MultiAnimatorSet(type, AnimatorSet(), AnimatorSet(),
                { handler.post(it) }, { mainHandler.post(it) }, { true })
            val async = value()
            var startThread: Thread? = null; var endThread: Thread? = null
            async.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) { startThread = Thread.currentThread() }
            })
            group.play(true, async)
            group.setViewStateResetRunnable { endThread = Thread.currentThread() }
            group.start(); shadowOf(thread.looper).idle()
            assertSame(thread, startThread)
            group.end(); shadowOf(thread.looper).idle()
            assertNull(endThread)
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(Looper.getMainLooper().thread, endThread)
            group.destroy(); shadowOf(thread.looper).idle()
        } finally { thread.quitSafely(); thread.join(1000) }
    }
    @Test fun testCancellationFlagHasVolatilePublicationAndResetsOnRestart() {
        val field = MultiAnimatorSet::class.java.getDeclaredField("hasRequestCancel")
        assertTrue(java.lang.reflect.Modifier.isVolatile(field.modifiers))
        val group = group(); group.play(value()); group.start(); group.cancel()
        assertTrue(group.hasRequestCancel)
        group.start()
        assertFalse(group.hasRequestCancel)
    }

    @Test fun testThrowingCancelObserverDoesNotStrandTracksOrOtherObservers() {
        val group = group(); val rect = RectDriver(); val events = mutableListOf<String>()
        group.play(value()); group.play(CustomRectFSpringAnim(type, rect))
        group.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationCancel(animator: Animator) { error("cancel observer") }
        })
        group.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationCancel(animator: Animator) { events.add("cancel") }
            override fun onAnimationEnd(animator: Animator) { events.add("end") }
        })
        group.start(); group.cancel()
        assertTrue(group.isRunning)
        assertEquals(1, rect.cancels)
        rect.finish(); flushMain()
        assertFalse(group.isRunning)
        assertEquals(listOf("cancel", "end"), events)
    }

    @Test fun testThrowingStartAndEndObserversDoNotPreventPlaybackOrOtherObservers() {
        val group = group(); val animator = value(); val events = mutableListOf<String>()
        group.play(animator)
        group.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationStart(animator: Animator) { error("start observer") }
            override fun onAnimationEnd(animator: Animator) { error("end observer") }
        })
        group.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationStart(animator: Animator) { events.add("start") }
            override fun onAnimationEnd(animator: Animator) { events.add("end") }
        })
        group.start(); assertTrue(animator.isStarted)
        group.end()
        assertEquals(listOf("start", "end"), events)
        assertFalse(group.isRunning)
    }

    @Test fun testThrowingResetCallbackDoesNotSuppressEndAndCanRestart() {
        val group = group(); var resets = 0; var ends = 0
        group.setViewStateResetRunnable { resets++; error("reset callback") }
        group.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationEnd(animator: Animator) { ends++ }
        })
        group.start(); group.start()
        assertEquals(1, resets)
        assertEquals(2, ends)
        assertFalse(group.isRunning)
    }

    @Test fun testOldStopCannotCancelSpringInstalledByCompletionCallback() {
        for (cancel in listOf(true, false)) {
            val group = group(); val successor = spring()
            group.play(value())
            group.setViewStateResetRunnable {
                group.play(successor)
                group.start()
            }
            group.start()
            if (cancel) group.cancel() else group.end()
            assertTrue("Old stop must not reach the successor spring (cancel=$cancel)", successor.isRunning)
            assertFalse("The successor owns a fresh cancellation flag", group.hasRequestCancel)
            group.destroy()
        }
    }

    @Test fun testLiveAnimatorCleanupCannotRewriteOldCompletionId() {
        val group = group(); val rect = RectDriver(); val live = value()
        val ids = mutableListOf<Int>()
        group.animationId = 7
        group.setViewStateResetRunnable { ids.add(it) }
        group.play(CustomRectFSpringAnim(type, rect)); group.start()
        live.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) {
                group.animationId = 8
                group.setViewStateResetRunnable { ids.add(it) }
                group.start()
            }
        })
        group.play(live, true)
        rect.finish(); flushMain()
        assertEquals(listOf(7), ids)
        group.end(); rect.finish(); flushAsync(); flushMain()
        assertEquals(listOf(7, 8), ids)
    }

}
