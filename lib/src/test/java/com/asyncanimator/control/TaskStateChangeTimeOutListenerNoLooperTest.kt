package com.asyncanimator.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 纯 JVM 环境（无 Robolectric、无主 Looper）下 [TaskStateChangeTimeOutListener] 的降级行为。
 * 定时通道不可用时构造不抛异常，仅保留事件竞争入口，释放通知仍恰好一次；
 * 对应协程化后主调度器解析失败的路径，锁定与旧 Handler=null 降级语义一致。
 */
class TaskStateChangeTimeOutListenerNoLooperTest {
    private val type = TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH

    @Test
    fun testConstructionWithoutMainLooperKeepsEventOnlyPath() {
        var calls = 0
        val listener = TaskStateChangeTimeOutListener(type, 0) { calls++ }
        listener.onTimeOut(type, 0)
        listener.onTimeOut(type, 0)
        assertEquals(1, calls)
    }

    @Test
    fun testDisposeWithoutMainLooperNotifiesDisposalOnce() {
        var disposals = 0
        val listener = TaskStateChangeTimeOutListener(type, 100, {}, { disposals++ })
        listener.dispose()
        listener.dispose()
        listener.onTimeOut(type, 100)
        assertEquals(1, disposals)
    }

    @Test
    fun testAccessRejectionStillPrecedesSchedulingWithoutMainLooper() {
        val failure = IllegalStateException("access denied")
        val thrown = assertThrows(IllegalStateException::class.java) {
            TaskStateChangeTimeOutListener(type, 1, {}, {}, { throw failure })
        }
        assertSame(failure, thrown)
    }
}
