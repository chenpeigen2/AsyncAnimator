package com.asyncanimator.demo

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TraceLogRedirectorTest {
    private lateinit var original: PrintStream
    private lateinit var sink: PrintStream
    private val bytes = ByteArrayOutputStream()
    private val subscriptions = mutableListOf<AutoCloseable>()
    private val router = TraceLogRedirector()

    @Before fun setup() {
        original = System.err
        sink = PrintStream(bytes, true, "UTF-8")
        System.setErr(sink)
    }

    @After fun cleanup() {
        subscriptions.reversed().forEach { it.close() }
        System.setErr(original)
        sink.close()
    }

    private fun subscribe(callback: (String) -> Unit): AutoCloseable =
        router.subscribe(callback).also(subscriptions::add)

    @Test fun testSubscribersShareOneWrapperAndDelegateOnce() {
        val calls = mutableListOf<String>()
        val first = subscribe { calls.add("first") }
        val wrapper = System.err
        val second = subscribe { calls.add("second") }
        assertSame(wrapper, System.err)
        System.err.println("Trace shared")
        assertEquals(listOf("first", "second"), calls)
        assertEquals("Trace shared", bytes.toString("UTF-8").trim())
        second.close(); first.close()
        assertSame(sink, System.err)
    }

    @Test fun testOutOfOrderCloseNeverRestoresAnInactiveWrapper() {
        var firstCalls = 0
        var secondCalls = 0
        val first = subscribe { firstCalls++ }
        val second = subscribe { secondCalls++ }
        first.close()
        System.err.println("Trace remaining")
        assertEquals(0, firstCalls)
        assertEquals(1, secondCalls)
        second.close()
        assertSame(sink, System.err)
        val third = subscribe { secondCalls++ }
        System.err.println("Trace new generation")
        third.close()
        assertSame(sink, System.err)
        assertEquals(0, firstCalls)
        assertEquals(2, secondCalls)
    }

    @Test fun testCloseDoesNotClobberForeignStderrReplacement() {
        val subscription = subscribe {}
        val foreign = PrintStream(ByteArrayOutputStream())
        try {
            System.setErr(foreign)
            subscription.close()
            assertSame(foreign, System.err)
        } finally { foreign.close() }
    }

    @Test fun testThrowingSubscriberCannotBreakStderrOrOtherSubscribers() {
        subscribe { throw IllegalStateException("UI logger failed") }
        var received = 0
        subscribe { received++ }
        System.err.println("Trace resilient")
        assertEquals(1, received)
        assertEquals("Trace resilient", bytes.toString("UTF-8").trim())
    }

    @Test fun testReentrantUnsubscribeSkipsReleasedSnapshotEntry() {
        lateinit var second: AutoCloseable
        subscribe { second.close() }
        var received = 0
        second = subscribe { received++ }
        System.err.println("Trace reentrant")
        assertEquals(0, received)
    }

    @Test fun testNonTracePassThroughAndRepeatedCloseKeepOriginalWritable() {
        var received = 0
        val subscription = subscribe { received++ }
        System.err.println("ordinary stderr")
        assertEquals(0, received)
        subscription.close(); subscription.close()
        assertSame(sink, System.err)
        System.err.println("still writable")
        assertEquals(listOf("ordinary stderr", "still writable"),
            bytes.toString("UTF-8").trim().lines())
    }
}
