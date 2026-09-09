package com.asyncanimator.launcher.seq

import android.os.SystemClock

/**
 * AnimSeqTimeStamp — 全局时间戳协调（demo 简化版）。
 *
 * 对应原 OPPO 代码 `com.android.systemui.shared.system.AnimSeqTimeStamp`。
 * 跨模块（launcher + systemui）共享 4 个时间戳，用于判断"上次 recent finish/start app 时间"。
 */
object AnimSeqTimeStamp {

    @Volatile
    private var lastStartAppTime = 0L

    @Volatile
    private var lastRecentFinishTime = 0L

    @Volatile
    private var lastRecentStartTime = 0L

    @Volatile
    private var lastLaunchTaskTime = 0L

    /** 可注入时钟：生产用 uptimeMillis，JVM 单测可换成 nanoTime 单调源。 */
    @Volatile
    var clock: () -> Long = { SystemClock.uptimeMillis() }

    internal fun updateLastStartAppTime() {
        lastStartAppTime = clock()
    }

    fun updateLastRecentFinishTime() {
        lastRecentFinishTime = clock()
    }

    internal fun updateLastRecentStartTime() {
        lastRecentStartTime = clock()
    }

    internal fun updateLastLaunchTaskTime() {
        lastLaunchTaskTime = clock()
    }

    internal fun resetLastStartAppTime() {
        lastStartAppTime = 0
    }

    internal fun resetLastRecentFinishTime() {
        lastRecentFinishTime = 0
    }

    internal fun resetLastRecentStartTime() {
        lastRecentStartTime = 0
    }

    internal fun resetLastLaunchTaskTime() {
        lastLaunchTaskTime = 0
    }

    /** 测试辅助：复位全部时间戳（全局静态状态，测试间互相污染）。 */
    internal fun resetAllForTest() {
        lastStartAppTime = 0
        lastRecentFinishTime = 0
        lastRecentStartTime = 0
        lastLaunchTaskTime = 0
    }

    private fun gapTo(timestamp: Long): Long =
        if (timestamp == 0L) Long.MAX_VALUE else clock() - timestamp

    internal val timeGapToLastStartAppTime: Long get() = gapTo(lastStartAppTime)

    internal val timeGapToLastRecentFinishTime: Long get() = gapTo(lastRecentFinishTime)

    internal val timeGapToLastRecentStartTime: Long get() = gapTo(lastRecentStartTime)

    internal val timeGapToLastLaunchTaskTime: Long get() = gapTo(lastLaunchTaskTime)
}
