package com.asyncanimator.core

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class LogUtilsTest {
    private val bytes = ByteArrayOutputStream()
    private lateinit var previousErr: PrintStream
    private lateinit var capture: PrintStream
    private var previousLevel = LogUtils.OFF

    @Before fun setup() {
        previousErr = System.err
        previousLevel = when {
            LogUtils.isAlwayson() -> LogUtils.ALWAYS
            LogUtils.isLogOpen() -> LogUtils.INFO
            else -> LogUtils.OFF
        }
        capture = PrintStream(bytes, true, "UTF-8")
        System.setErr(capture)
    }

    @After fun cleanup() {
        System.setErr(previousErr)
        capture.close()
        LogUtils.setLogLevel(previousLevel)
    }

    @Test fun testEveryLevelDefinesGateAndAlwaysFlagIndependently() {
        for (level in listOf(LogUtils.OFF, LogUtils.INFO, LogUtils.ALWAYS, LogUtils.OFF)) {
            LogUtils.setLogLevel(level)
            assertEquals(level != LogUtils.OFF, LogUtils.isLogOpen())
            assertEquals(level == LogUtils.ALWAYS, LogUtils.isAlwayson())
        }
    }

    @Test fun testInvalidLevelsThrowBeforeChangingPolicy() {
        for (level in listOf(LogUtils.OFF, LogUtils.INFO, LogUtils.ALWAYS)) {
            LogUtils.setLogLevel(level)
            for (bad in listOf(Int.MIN_VALUE, -1, 3, Int.MAX_VALUE)) {
                assertThrows(IllegalArgumentException::class.java) { LogUtils.setLogLevel(bad) }
                assertEquals(level != LogUtils.OFF, LogUtils.isLogOpen())
                assertEquals(level == LogUtils.ALWAYS, LogUtils.isAlwayson())
            }
        }
    }

    @Test fun testInfoIncludesExactCallingThreadTagAndMessageOnlyWhenEnabled() {
        LogUtils.setLogLevel(LogUtils.OFF)
        LogUtils.i("tag", "hidden")
        assertEquals("", bytes.toString("UTF-8"))
        LogUtils.setLogLevel(LogUtils.INFO)
        LogUtils.i("tag", "消息")
        assertEquals("Trace [${Thread.currentThread().name}] tag: 消息", bytes.toString("UTF-8").trim())
    }

    @Test fun testInternalEmitBypassesGateForBeginTimeTracePolicy() {
        LogUtils.setLogLevel(LogUtils.OFF)
        LogUtils.emit("Trace", "matching end")
        assertTrue(bytes.toString("UTF-8").contains("Trace: matching end"))
        assertFalse(LogUtils.isLogOpen())
    }

    @Test fun testWorkerSeesPublishedPolicyAndOutputUsesWorkerName() {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "log-policy-worker") }
        try {
            LogUtils.setLogLevel(LogUtils.ALWAYS)
            assertTrue(worker.submit<Boolean> {
                LogUtils.i("worker", "enabled")
                LogUtils.isAlwayson()
            }.get(5, TimeUnit.SECONDS))
            LogUtils.setLogLevel(LogUtils.OFF)
            assertFalse(worker.submit<Boolean> {
                LogUtils.i("worker", "disabled")
                LogUtils.isLogOpen()
            }.get(5, TimeUnit.SECONDS))
            assertTrue(bytes.toString("UTF-8").contains("[log-policy-worker] worker: enabled"))
            assertFalse(bytes.toString("UTF-8").contains("disabled"))
        } finally {
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
