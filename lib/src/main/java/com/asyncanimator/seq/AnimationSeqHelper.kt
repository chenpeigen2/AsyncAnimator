package com.asyncanimator.seq

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.asyncanimator.core.Trace
import com.asyncanimator.core.LogUtils
import com.asyncanimator.manager.OplusAnimManager

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
 *  - 单个 helper 内共享递增 seqId 写入 Bundle（由宿主传递，不自动跨进程同步）
 *  - [canFinishRecent] 检查 500ms 内是否刚结束过
 *  - [canInterceptGesture] 检查 300ms 内是否刚启动过 app
 *  - [delayFinishRecents] 在剩余窗口后执行最新请求，支持回调重入提交下一项
 *
 * 实例方法由主线程调用；本类不提供任意线程并发访问保证。
 */
class AnimationSeqHelper(
    private val startingSurfaceSupported: () -> Boolean = { true },
    private val interruptionSupported: () -> Boolean = { OplusAnimManager.supportInterruption() }
) : DefaultAnimationSeqHelper() {

    private var seqId = 0L
    private var delayAction: (() -> Unit)? = null

    /** 懒创建：仅真正排队时获取主 Looper，构造期不触碰 Handler。 */
    private var handler: Handler? = null

    /** (controller, seqId) Pair，记录当前 recents controller 对应的 seqId。 */
    private var nextFinishSeqId: Pair<Any?, Long>? = null

    private fun getOrCreateHandler(): Handler {
        if (handler == null) {
            handler = Handler(Looper.getMainLooper()) { msg ->
                if (msg.what == MSG_EXC_RUNNABLE) {
                    Trace.traceBegin(Trace.TAG_VIEW, "exc delayRunnable")
                    // Consume before invoking: a reentrant callback may enqueue its successor.
                    val action = delayAction
                    delayAction = null
                    runCatching {
                        action?.invoke()
                    }.also {
                        Trace.traceEnd(Trace.TAG_VIEW)
                    }.getOrThrow()
                }
                true
            }
        }
        return handler!!
    }

    private fun updateSeqId(): Long = ++seqId

    override fun addSeqId(bundle: Bundle?) {
        if (bundle == null) return
        if (!interruptionSupported()) {
            LogUtils.i("AnimationSeqHelper", "skip addSeqId: interruption unsupported")
            return
        }
        val id = updateSeqId()
        bundle.putLong(KEY_INTERRUPT_TRANSITION_START_ACTIVITY_SEQ_ID, id)
        LogUtils.i("AnimationSeqHelper", "add startActivity seqId=$id")
    }

    override val canFinishRecent: Boolean
        get() = !startingSurfaceSupported() || !interruptionSupported() ||
            AnimSeqTimeStamp.timeGapToLastRecentFinishTime > MAX_DELAY_TIME

    override val canInterceptGesture: Boolean
        get() = !startingSurfaceSupported() || !interruptionSupported() ||
            AnimSeqTimeStamp.timeGapToLastStartAppTime > MAX_INTERCEPT_GESTURE_DELAY_TIME

    override fun delayFinishRecents(action: (() -> Unit)?): Boolean {
        if (canFinishRecent) {
            // This request supersedes the queued one even if feature gates now allow it.
            clearFinishRecentsRunnable()
            action?.invoke()
            return false
        }
        return Trace.section(Trace.TAG_VIEW, "delayFinishRecents") {
            clearFinishRecentsRunnable()
            delayAction = action
            val delay = MAX_DELAY_TIME - AnimSeqTimeStamp.timeGapToLastRecentFinishTime
            LogUtils.i("AnimationSeqHelper", "delayFinishRecents delayMs=${maxOf(0L, delay)}")
            getOrCreateHandler().sendEmptyMessageDelayed(MSG_EXC_RUNNABLE, maxOf(0L, delay))
            true
        }
    }

    override fun clearFinishRecentsRunnable() {
        handler?.removeMessages(MSG_EXC_RUNNABLE)
        delayAction = null
    }

    override fun resetInterceptState() {
        AnimSeqTimeStamp.resetLastStartAppTime()
    }

    override fun updateNextFinishSeqIdIfNeed(recentsController: Any?) {
        if (!interruptionSupported()) {
            LogUtils.i("AnimationSeqHelper", "skip nextFinish update: interruption unsupported")
            return
        }
        val p = nextFinishSeqId
        // 原厂仅在 pair 为空或 controller 变更时才更新（AnimationSeqHelper.java:123-127）
        if (p == null || p.first != recentsController) {
            nextFinishSeqId = recentsController to updateSeqId()
            LogUtils.i("AnimationSeqHelper", "nextFinish seqId=${nextFinishSeqId?.second}")
        }
    }

    override fun getNextFinishSeqId(recentsController: Any?): Long {
        val p = nextFinishSeqId
        // 原厂 Intrinsics.areEqual 按 equals 比较，与更新 pair 的判断保持一致
        if (p != null && p.first == recentsController) return p.second
        return 0L
    }
}
