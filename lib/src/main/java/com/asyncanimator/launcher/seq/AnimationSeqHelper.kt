package com.asyncanimator.launcher.seq

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.asyncanimator.util.Trace

internal const val MAX_DELAY_TIME = 500L
internal const val MAX_INTERCEPT_GESTURE_DELAY_TIME = 300L

private const val MSG_EXC_RUNNABLE = 1
private const val KEY_INTERRUPT_TRANSITION_START_ACTIVITY_SEQ_ID =
    "interrupt.transition.startActivity.seqId"

/**
 * AnimationSeqHelper — Recents 动画 SeqId 防抖。
 *
 * 对应 `docs/review/03-controller-manager-seq.md`。
 *
 * 核心机制：
 *
 *  - 全局单调 seqId 写入 Bundle（跨进程同步）
 *  - [canFinishRecent] 检查 500ms 内是否刚结束过
 *  - [canInterceptGesture] 检查 300ms 内是否刚启动过 app
 *  - [delayFinishRecents] 500ms 内必须 finish
 */
class AnimationSeqHelper : DefaultAnimationSeqHelper() {

    private var seqId = 0L
    private var delayAction: (() -> Unit)? = null

    /** 懒创建：JVM 单测环境没有主 Looper，构造期不触碰 android.os.Handler。 */
    private var handler: Handler? = null

    /** (controller, seqId) Pair，记录当前 recents controller 对应的 seqId。 */
    private var nextFinishSeqId: Pair<Any?, Long>? = null

    private fun getOrCreateHandler(): Handler {
        if (handler == null) {
            handler = Handler(Looper.getMainLooper()) { msg ->
                if (msg.what == MSG_EXC_RUNNABLE) {
                    Trace.traceBegin(8L, "exc delayRunnable")
                    delayAction?.invoke()
                    delayAction = null
                    Trace.traceEnd(8L)
                }
                true
            }
        }
        return handler!!
    }

    private fun updateSeqId(): Long = ++seqId

    override fun addSeqId(bundle: Bundle?) {
        if (bundle == null) return
        val id = updateSeqId()
        bundle.putLong(KEY_INTERRUPT_TRANSITION_START_ACTIVITY_SEQ_ID, id)
    }

    override val canFinishRecent: Boolean
        get() = AnimSeqTimeStamp.timeGapToLastRecentFinishTime > MAX_DELAY_TIME

    override val canInterceptGesture: Boolean
        get() = AnimSeqTimeStamp.timeGapToLastStartAppTime > MAX_INTERCEPT_GESTURE_DELAY_TIME

    override fun delayFinishRecents(action: (() -> Unit)?): Boolean {
        if (canFinishRecent) {
            action?.invoke()
            return false
        }
        Trace.traceBegin(8L, "delayFinishRecents")
        clearFinishRecentsRunnable()
        delayAction = action
        val delay = MAX_DELAY_TIME - AnimSeqTimeStamp.timeGapToLastRecentFinishTime
        getOrCreateHandler().sendEmptyMessageDelayed(MSG_EXC_RUNNABLE, maxOf(0L, delay))
        Trace.traceEnd(8L)
        return true
    }

    override fun clearFinishRecentsRunnable() {
        handler?.removeMessages(MSG_EXC_RUNNABLE)
        delayAction = null
    }

    override fun resetInterceptState() {
        AnimSeqTimeStamp.resetLastStartAppTime()
    }

    override fun updateNextFinishSeqIdIfNeed(recentsController: Any?) {
        val p = nextFinishSeqId
        // 原厂仅在 pair 为空或 controller 变更时才更新（AnimationSeqHelper.java:123-127）
        if (p == null || p.first != recentsController) {
            nextFinishSeqId = recentsController to updateSeqId()
        }
    }

    override fun getNextFinishSeqId(recentsController: Any?): Long {
        val p = nextFinishSeqId
        // 原厂按引用比较 controller，这里用 === 保持一致
        if (p != null && p.first == recentsController) return p.second
        return 0L
    }
}
