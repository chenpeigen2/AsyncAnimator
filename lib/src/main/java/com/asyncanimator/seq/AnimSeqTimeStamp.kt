package com.asyncanimator.seq

import android.os.SystemClock
import com.asyncanimator.core.LogUtils

/**
 * AnimSeqTimeStamp — 全局时间戳协调（demo 简化版）。
 *
 * 对应原 OPPO 代码 `com.android.systemui.shared.system.AnimSeqTimeStamp`。
 * 当前进程/类加载器内保存 4 个时间戳，不自动与另一个进程的 SystemUI 同步。
 * null 表示未记录；零毫秒是有效事件时间。未记录的 gap 返回 Long.MAX_VALUE，
 * 只用于窗口比较，不是可任意相加/相乘的持续时长。
 * 写入/reset 在 object monitor 下串行化，单字段读取通过 volatile 发布；
 * 连续读取多个 gap 不构成跨字段原子快照。
 */
object AnimSeqTimeStamp {

    @Volatile
    private var lastStartAppTime: Long? = null

    @Volatile
    private var lastRecentFinishTime: Long? = null

    @Volatile
    private var lastRecentStartTime: Long? = null

    @Volatile
    private var lastLaunchTaskTime: Long? = null

    /**
     * Internal test seam only. Supply monotonic milliseconds from one origin; reset timestamps
     * before changing origins, restore after the test, and never swap while workers are active.
     * Kotlin internal is a module contract, not a JVM access-control/security boundary.
     */
    @Volatile
    internal var clock: () -> Long = { SystemClock.uptimeMillis() }

    @Synchronized
    internal fun updateLastStartAppTime() {
        lastStartAppTime = clock()
        logTimestamp("startApp", lastStartAppTime)
    }

    @Synchronized
    fun updateLastRecentFinishTime() {
        lastRecentFinishTime = clock()
        logTimestamp("recentFinish", lastRecentFinishTime)
    }

    @Synchronized
    internal fun updateLastRecentStartTime() {
        lastRecentStartTime = clock()
        logTimestamp("recentStart", lastRecentStartTime)
    }

    @Synchronized
    internal fun updateLastLaunchTaskTime() {
        lastLaunchTaskTime = clock()
        logTimestamp("launchTask", lastLaunchTaskTime)
    }

    @Synchronized
    internal fun resetLastStartAppTime() {
        lastStartAppTime = null
        logTimestamp("startApp", null)
    }

    @Synchronized
    internal fun resetLastRecentFinishTime() {
        lastRecentFinishTime = null
        logTimestamp("recentFinish", null)
    }

    @Synchronized
    internal fun resetLastRecentStartTime() {
        lastRecentStartTime = null
        logTimestamp("recentStart", null)
    }

    @Synchronized
    internal fun resetLastLaunchTaskTime() {
        lastLaunchTaskTime = null
        logTimestamp("launchTask", null)
    }

    /** 测试辅助：复位全部时间戳（全局静态状态，测试间互相污染）。 */
    @Synchronized
    internal fun resetAllForTest() {
        lastStartAppTime = null
        lastRecentFinishTime = null
        lastRecentStartTime = null
        lastLaunchTaskTime = null
    }

    private fun logTimestamp(name: String, timestamp: Long?) {
        if (LogUtils.isLogOpen()) {
            LogUtils.i("AnimSeqTimeStamp", "$name timestampMs=${timestamp ?: "unset"}")
        }
    }

    private fun gapTo(name: String, timestamp: Long?): Long {
        val gap = if (timestamp == null) Long.MAX_VALUE else clock() - timestamp
        if (LogUtils.isLogOpen()) LogUtils.i("AnimSeqTimeStamp", "$name gapMs=$gap")
        return gap
    }

    internal val timeGapToLastStartAppTime: Long get() = gapTo("startApp", lastStartAppTime)

    internal val timeGapToLastRecentFinishTime: Long get() = gapTo("recentFinish", lastRecentFinishTime)

    internal val timeGapToLastRecentStartTime: Long get() = gapTo("recentStart", lastRecentStartTime)

    internal val timeGapToLastLaunchTaskTime: Long get() = gapTo("launchTask", lastLaunchTaskTime)
}
