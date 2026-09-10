package com.asyncanimator.core

import android.animation.Animator
import android.animation.ValueAnimator
import android.os.Looper
import com.asyncanimator.BuildConfig
import com.asyncanimator.anim.AsyncAnimCallbacks
import com.asyncanimator.playback.NullableAnimatorListenerAdapter
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Before
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
class TraceLogTest {
    private val output = ByteArrayOutputStream()
    private val originalErr = System.err
    private var originalLevel = LogUtils.OFF
    @Before fun capture() {
        originalLevel = when {
            LogUtils.isAlwayson() -> LogUtils.ALWAYS
            LogUtils.isLogOpen() -> LogUtils.INFO
            else -> LogUtils.OFF
        }
        Trace.clear()
        System.setErr(PrintStream(output, true, Charsets.UTF_8))
    }
    @After fun restore() {
        System.setErr(originalErr)
        Trace.clear()
        LogUtils.setLogLevel(originalLevel)
    }
    private fun text() = output.toString(Charsets.UTF_8)
    private fun worker(action: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        val thread = Thread({ try { action() } catch (t: Throwable) { failure.set(t) } }, "trace-worker")
        thread.start(); thread.join(5000)
        assertFalse(thread.isAlive)
        failure.get()?.let { throw it }
    }

    @Test fun testVariantDefaultsAndExplicitLogLevels() {
        assertEquals(BuildConfig.DEBUG, originalLevel != LogUtils.OFF)
        LogUtils.setLogLevel(LogUtils.ALWAYS)
        assertTrue(LogUtils.isAlwayson())
        LogUtils.i("example", "value")
        assertTrue(text().contains("example: value"))
        LogUtils.setLogLevel(LogUtils.INFO)
        assertFalse(LogUtils.isAlwayson())
        assertThrows(IllegalArgumentException::class.java) { LogUtils.setLogLevel(3) }
    }

    @Test fun testThreadLocalStackDoesNotMatchEndOnAnotherThread() {
        LogUtils.setLogLevel(LogUtils.INFO)
        Trace.traceBegin(8, "main-section")
        worker {
            Trace.traceEnd(8)
            assertEquals(0, Trace.depth)
            Trace.traceBegin(8, "worker-section")
            Trace.traceEnd(8)
        }
        assertEquals(1, Trace.depth)
        Trace.traceEnd(8)
        assertEquals(0, Trace.depth)
        assertTrue(text().contains("[trace-worker]"))
    }

    @Test fun testDisabledLoggingSuppressesNewTraceAndInfo() {
        LogUtils.setLogLevel(LogUtils.OFF)
        Trace.traceBegin(8, "hidden")
        Trace.traceEnd(8)
        LogUtils.i("hidden", "hidden")
        assertEquals("", text())
        assertEquals(0, Trace.depth)
    }

    @Test fun testPolicyChangesDoNotUnbalanceNestedSections() {
        LogUtils.setLogLevel(LogUtils.INFO)
        Trace.traceBegin(8, "outer")
        LogUtils.setLogLevel(LogUtils.OFF)
        Trace.traceBegin(8, "hidden-inner")
        Trace.traceEnd(8)
        Trace.traceEnd(8)
        assertEquals(0, Trace.depth)
        assertFalse(text().contains("hidden-inner"))
        assertTrue(text().contains(">>> [8] outer"))
        assertTrue(text().contains("<<< [8] outer"))
    }

    @Test fun testThrowingMainListenerStillClosesTrace() {
        LogUtils.setLogLevel(LogUtils.INFO)
        val callbacks = AsyncAnimCallbacks()
        callbacks.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationStart(animator: Animator) { throw IllegalStateException("expected") }
        })
        assertThrows(IllegalStateException::class.java) { callbacks.onAnimationStart(ValueAnimator()) }
        assertEquals(0, Trace.depth)
        callbacks.dispose()
    }

    @Test fun testAsyncDeliveryIsTracedOnMainRatherThanOnlyPostingThread() {
        LogUtils.setLogLevel(LogUtils.INFO)
        val callbacks = AsyncAnimCallbacks()
        var depthDuringCallback = -1
        var deliveredOnMain = false
        callbacks.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationStart(animator: Animator) {
                depthDuringCallback = Trace.depth
                deliveredOnMain = Looper.myLooper() === Looper.getMainLooper()
            }
        })
        val animator = ValueAnimator()
        worker { callbacks.onAnimationStart(animator); assertEquals(0, Trace.depth) }
        assertEquals(-1, depthDuringCallback)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(deliveredOnMain)
        assertEquals(1, depthDuringCallback)
        assertEquals(0, Trace.depth)
        callbacks.dispose()
    }

    @Test fun testQueuedCallbackTraceCapturesTypeAndIdBeforeMainDelivery() {
        LogUtils.setLogLevel(LogUtils.INFO)
        val callbacks = AsyncAnimCallbacks()
        callbacks.animationId = 41
        callbacks.setAnimType(com.asyncanimator.anim.CustomRectFSpringAnim.AnimType.OPEN_FROM_HOME)
        val animator = ValueAnimator()
        worker {
            callbacks.onAnimationStart(animator)
            callbacks.onAnimationCancel(animator)
            callbacks.onAnimationEnd(animator)
            callbacks.onAnimActualEnd(animator)
        }
        callbacks.animationId = 42
        callbacks.setAnimType(com.asyncanimator.anim.CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME)
        shadowOf(Looper.getMainLooper()).idle()
        for (prefix in listOf("AsyncAnimStart-", "AsyncAnimCancel-", "AsyncAnimEnd-", "ActualEnd-")) {
            assertTrue(text(), text().contains("${prefix}41 type=OPEN_FROM_HOME"))
        }
        assertFalse(text().contains("type=SWIPE_TO_HOME"))
        assertEquals(0, Trace.depth)
        callbacks.dispose()
    }

    @Test fun testDisposeClearsDiagnosticTypeForNewGeneration() {
        LogUtils.setLogLevel(LogUtils.INFO)
        val callbacks = AsyncAnimCallbacks()
        callbacks.setAnimType(com.asyncanimator.anim.CustomRectFSpringAnim.AnimType.OPEN_FROM_HOME)
        callbacks.dispose()
        callbacks.onAnimationStart(ValueAnimator())
        assertTrue(text(), text().contains("AsyncAnimStart--1 type=UNSPECIFIED"))
        assertFalse(text().contains("type=OPEN_FROM_HOME"))
        callbacks.dispose()
    }

    @Test fun testNestedSectionReturnsResultAndClosesOnException() {
        LogUtils.setLogLevel(LogUtils.INFO)
        val failure = IllegalStateException("nested failure")
        assertEquals(7, Trace.section(8, "outer-value") {
            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                Trace.section(32, "inner-error") { throw failure }
            })
            assertEquals(1, Trace.depth)
            7
        })
        assertEquals(0, Trace.depth)
        Trace.traceEnd(8) // Empty-stack end remains harmless.
        assertEquals(0, Trace.depth)
    }
}
