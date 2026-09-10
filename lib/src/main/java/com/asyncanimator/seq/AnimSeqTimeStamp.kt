package com.asyncanimator.seq

import android.os.SystemClock
import com.asyncanimator.api.PublicApi
import com.asyncanimator.core.LogUtils

/**
 * 当前进程内四类动画事件的时间戳容器，默认使用 uptimeMillis 单调时钟。
 * null 表示未记录，零是有效时刻；写入和清理使用对象锁，单字段通过 volatile 发布。
 * 连续读取多个 gap 不构成跨字段原子快照，也不自动与其他进程同步。
 */
@PublicApi
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
     * 仅用于模块内测试的毫秒时钟入口，默认使用系统运行时间。
     * 切换时间原点前先清除旧事件，测试结束恢复原时钟；不得在并发工作线程活跃时替换。
     */
    @Volatile
    internal var clock: () -> Long = { SystemClock.uptimeMillis() }

    /**
     * 在对象锁内读取一次 clock，替换最近启动应用的毫秒时间戳并记录诊断日志。
     * 不改变其他事件；clock 抛出异常时赋值尚未发生，旧时间戳保留。
     */
    @Synchronized
    internal fun updateLastStartAppTime() {
        lastStartAppTime = clock()
        logTimestamp("startApp", lastStartAppTime)
    }

    /**
     * 在对象锁内读取一次 clock，记录最近完成 Recents 的时刻，供 500ms 窗口判断使用。
     * 零毫秒是有效事件时间；clock 失败会向外传播且不会抹掉旧值。
     */
    @PublicApi
    @Synchronized
    fun updateLastRecentFinishTime() {
        lastRecentFinishTime = clock()
        logTimestamp("recentFinish", lastRecentFinishTime)
    }

    /**
     * 在对象锁内记录最近启动 Recents 的毫秒时刻，只替换这一字段。
     * 时间来自可注入的单调时钟，时钟异常在写入前传播，其他三个事件保持不变。
     */
    @Synchronized
    internal fun updateLastRecentStartTime() {
        lastRecentStartTime = clock()
        logTimestamp("recentStart", lastRecentStartTime)
    }

    /**
     * 在对象锁内记录最近启动任务的毫秒时刻，仅取一次时钟样本。
     * 重复调用以最新样本覆盖旧记录；时钟异常时保留原记录，不影响其他事件。
     */
    @Synchronized
    internal fun updateLastLaunchTaskTime() {
        lastLaunchTaskTime = clock()
        logTimestamp("launchTask", lastLaunchTaskTime)
    }

    /**
     * 在对象锁内将最近启动应用时间设为未记录，并按日志策略输出清理标记。
     * 不读取时钟，后续该字段的 gap 返回 Long.MAX_VALUE，其他事件不受影响。
     */
    @Synchronized
    internal fun resetLastStartAppTime() {
        lastStartAppTime = null
        logTimestamp("startApp", null)
    }

    /**
     * 清除最近完成 Recents 的时间而非将其写成零，避免把未记录误判为时钟原点事件。
     * 操作在对象锁内完成，不查询时钟，也不清理 helper 已经排队的消息。
     */
    @Synchronized
    internal fun resetLastRecentFinishTime() {
        lastRecentFinishTime = null
        logTimestamp("recentFinish", null)
    }

    /**
     * 在对象锁内清除最近启动 Recents 的事件，只改变对应时间戳。
     * 重复清理仍保持未记录状态，不使用当前时间替代空值。
     */
    @Synchronized
    internal fun resetLastRecentStartTime() {
        lastRecentStartTime = null
        logTimestamp("recentStart", null)
    }

    /**
     * 在对象锁内清除最近启动任务时间，其他三个事件原样保留。
     * 不访问时钟；该字段之后的间隔查询返回未记录哨兵值。
     */
    @Synchronized
    internal fun resetLastLaunchTaskTime() {
        lastLaunchTaskTime = null
        logTimestamp("launchTask", null)
    }

    /**
     * 在对象锁内一次清空四个时间戳，供测试用例之间隔离全局状态。
     * 不替换注入时钟、不输出逐项日志，也不向其他持有者提供跨字段原子读取接口。
     */
    @Synchronized
    internal fun resetAllForTest() {
        lastStartAppTime = null
        lastRecentFinishTime = null
        lastRecentStartTime = null
        lastLaunchTaskTime = null
    }

    /**
     * 在信息日志开启时输出事件名和毫秒时间戳，null 输出为未设置标记。
     * 该方法不读取时钟或修改记录；调用方已完成状态更新，日志仅用于诊断。
     */
    private fun logTimestamp(name: String, timestamp: Long?) {
        if (LogUtils.isLogOpen()) {
            LogUtils.i("AnimSeqTimeStamp", "$name timestampMs=${timestamp ?: "unset"}")
        }
    }

    /**
     * 计算已读取时间戳与当前时钟样本之间的毫秒差，并按策略输出诊断日志。
     * 未记录时直接返回 Long.MAX_VALUE 且不取时钟样本；该哨兵只用于窗口比较，不能任意参与时间运算。
     * @param name 日志中的事件名称，不决定读取哪个字段。
     * @param timestamp 调用方读取的单字段快照；有效记录应与 clock 使用同一单调时间原点。
     */
    private fun gapTo(name: String, timestamp: Long?): Long {
        val gap = if (timestamp == null) Long.MAX_VALUE else clock() - timestamp
        if (LogUtils.isLogOpen()) LogUtils.i("AnimSeqTimeStamp", "$name gapMs=$gap")
        return gap
    }

    /**
     * 读取最近启动应用事件的毫秒间隔；未记录返回 Long.MAX_VALUE，只保证单字段快照。
     */
    internal val timeGapToLastStartAppTime: Long get() = gapTo("startApp", lastStartAppTime)

    /**
     * 读取最近完成 Recents 的毫秒间隔，用于完成防抖；未记录时不调用 clock。
     */
    internal val timeGapToLastRecentFinishTime: Long get() = gapTo("recentFinish", lastRecentFinishTime)

    /**
     * 读取最近启动 Recents 的毫秒间隔；若已经清理则返回未记录哨兵值。
     */
    internal val timeGapToLastRecentStartTime: Long get() = gapTo("recentStart", lastRecentStartTime)

    /**
     * 读取最近启动任务的毫秒间隔；使用与写入一致的单调时钟，不转换为墙上时间。
     */
    internal val timeGapToLastLaunchTaskTime: Long get() = gapTo("launchTask", lastLaunchTaskTime)
}
