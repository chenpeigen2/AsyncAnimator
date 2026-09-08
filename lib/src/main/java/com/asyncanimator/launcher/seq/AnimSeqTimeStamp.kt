package com.asyncanimator.launcher.seq

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

    internal fun updateLastStartAppTime() {
        lastStartAppTime = System.currentTimeMillis()
    }

    fun updateLastRecentFinishTime() {
        lastRecentFinishTime = System.currentTimeMillis()
    }

    internal fun updateLastRecentStartTime() {
        lastRecentStartTime = System.currentTimeMillis()
    }

    internal fun updateLastLaunchTaskTime() {
        lastLaunchTaskTime = System.currentTimeMillis()
    }

    internal fun resetLastStartAppTime() {
        lastStartAppTime = 0
    }

    /** 测试辅助：复位全部时间戳（全局静态状态，测试间互相污染）。 */
    internal fun resetAllForTest() {
        lastStartAppTime = 0
        lastRecentFinishTime = 0
        lastRecentStartTime = 0
        lastLaunchTaskTime = 0
    }

    internal val timeGapToLastStartAppTime: Long
        get() = if (lastStartAppTime == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastStartAppTime

    internal val timeGapToLastRecentFinishTime: Long
        get() = if (lastRecentFinishTime == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastRecentFinishTime

    internal val timeGapToLastRecentStartTime: Long
        get() = if (lastRecentStartTime == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastRecentStartTime

    internal val timeGapToLastLaunchTaskTime: Long
        get() = if (lastLaunchTaskTime == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastLaunchTaskTime
}
