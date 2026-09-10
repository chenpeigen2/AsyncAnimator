package com.asyncanimator.thread

import android.os.Handler
import android.os.Looper
import android.os.Message
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
class LooperExecutorTest {
    @Test fun testAsyncPostUsesAsynchronousMessageWhileOrdinaryPostDoesNot() {
        val flags = mutableListOf<Boolean>()
        val threads = mutableListOf<Thread>()
        val handler = object : Handler(Looper.getMainLooper()) {
            override fun dispatchMessage(msg: Message) {
                flags.add(msg.isAsynchronous)
                super.dispatchMessage(msg)
            }
        }
        val executor = LooperExecutor(handler)
        Thread {
            executor.postAsync { threads.add(Thread.currentThread()) }
            executor.post { threads.add(Thread.currentThread()) }
        }.apply { start(); join() }
        assertTrue(flags.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true, false), flags)
        assertEquals(listOf(Looper.getMainLooper().thread, Looper.getMainLooper().thread), threads)
    }

    @Test fun testExecuteIsInlineOnlyOnOwnerThread() {
        val executor = LooperExecutor(Handler(Looper.getMainLooper()))
        val calls = mutableListOf<String>()
        executor.execute { calls.add("inline") }
        assertEquals(listOf("inline"), calls)
        Thread { executor.execute { calls.add("posted") } }.apply { start(); join() }
        assertEquals(listOf("inline"), calls)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("inline", "posted"), calls)
    }
    @Test fun testThreadAccessorsAgreeAndDoNotInventAJvmFallbackOwner() {
        val main = LooperExecutor(Handler(Looper.getMainLooper()))
        assertSame(Looper.getMainLooper().thread, main.getThread())
        assertSame(main.getThread(), main.getTargetThread())
        val fallback = LooperExecutor(null)
        assertNull(fallback.getThread())
        assertNull(fallback.getTargetThread())
        assertTrue(fallback.isCurrentThread)
    }

    @Test fun testPriorityChangesOwningHandlerThreadNotCaller() {
        val thread = android.os.HandlerThread("priority-test").apply { start() }
        try {
            val executor = LooperExecutor(Handler(thread.looper))
            val callerTid = android.os.Process.myTid()
            val callerPriority = android.os.Process.getThreadPriority(callerTid)
            executor.setThreadPriority(4)
            assertEquals(4, android.os.Process.getThreadPriority(thread.threadId))
            assertEquals(callerPriority, android.os.Process.getThreadPriority(callerTid))
        } finally { thread.quitSafely(); thread.join(1000) }
    }

    @Test fun testPriorityRejectsMainOrUnboundExecutorsExplicitly() {
        assertThrows(IllegalStateException::class.java) {
            LooperExecutor(Handler(Looper.getMainLooper())).setThreadPriority(0)
        }
        assertThrows(IllegalStateException::class.java) { LooperExecutor(null).setThreadPriority(0) }
    }
    @Test fun testPostOnOwnerRemainsQueuedWhileExecuteIsInline() {
        val executor = LooperExecutor(Handler(Looper.getMainLooper()))
        val calls = mutableListOf<String>()
        executor.post { calls.add("post") }
        executor.postAsync { calls.add("async") }
        executor.execute { calls.add("execute") }
        executor.execute(null)
        assertEquals(listOf("execute"), calls)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("execute", "post", "async"), calls)
    }

    @Test fun testNullHandlerFallbackRunsAllOperationsInlineWithoutInventingALooper() {
        val executor = LooperExecutor(null)
        val calls = mutableListOf<String>()
        executor.execute { calls.add("execute") }
        executor.post { calls.add("post") }
        executor.postAsync { calls.add("async") }
        assertEquals(listOf("execute", "post", "async"), calls)
        assertNull(executor.getHandler())
        assertNull(executor.getLooper())
        assertNull(executor.getThread())
        assertTrue(executor.isCurrentThread)
    }

    @Test fun testInlineExceptionsPropagateAndDoNotDisableSubsequentCalls() {
        val executor = LooperExecutor(null)
        val failure = IllegalStateException("inline failure")
        for (dispatch in listOf<(()->Unit)->Unit>(executor::execute, executor::post, executor::postAsync)) {
            assertSame(failure, assertThrows(IllegalStateException::class.java) { dispatch { throw failure } })
        }
        var calls = 0
        executor.execute { calls++ }
        assertEquals(1, calls)
    }
}
