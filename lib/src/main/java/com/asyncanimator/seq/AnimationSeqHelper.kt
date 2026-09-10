package com.asyncanimator.seq

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.asyncanimator.core.Trace
import com.asyncanimator.core.LogUtils
import com.asyncanimator.manager.OplusAnimManager

/**
 * Recents 完成防抖窗口，单位毫秒；查询在恰好 500ms 时仍认为处于窗口内。
 */
internal const val MAX_DELAY_TIME = 500L
/**
 * 启动后的手势拦截窗口，单位毫秒；只有间隔严格大于 300ms 才因时间条件放行。
 */
internal const val MAX_INTERCEPT_GESTURE_DELAY_TIME = 300L

/**
 * 本 helper 主线程 Handler 用于执行最新完成动作的消息类型。
 */
private const val MSG_EXC_RUNNABLE = 1
/**
 * 写入 Bundle 的启动序号键；该名称是通信数据格式的一部分，不应随注释调整而变化。
 */
private const val KEY_INTERRUPT_TRANSITION_START_ACTIVITY_SEQ_ID =
    "interrupt.transition.startActivity.seqId"

/**
 * 主线程内的 Recents 完成防抖与启动序号协调器。
 * 保存一个最新待完成动作和一个控制器序号配对，多个实例的队列/计数独立，但使用共享时间戳。
 * @param startingSurfaceSupported 查询启动表面能力；关闭时两个时间窗口都直接放行。
 * @param interruptionSupported 查询中断能力；关闭时不分配序号且时间窗口放行，不自动撤销已排队消息。
 */
class AnimationSeqHelper(
    private val startingSurfaceSupported: () -> Boolean = { true },
    private val interruptionSupported: () -> Boolean = { OplusAnimManager.supportInterruption() }
) : DefaultAnimationSeqHelper() {

    private var seqId = 0L
    private var delayAction: (() -> Unit)? = null

    private var handler: Handler? = null

    /**
     * 保存最近一次登记的控制器及序号；控制器可为 null，配对存在与否不能仅按控制器值判断。
     */
    private var nextFinishSeqId: Pair<Any?, Long>? = null

    /**
     * 仅在确实需要排队时创建并缓存主线程 Handler，构造 helper 本身不访问 Handler。
     * 匹配的完成消息先取走并清空旧 action，再执行回调；回调重入提交的后继不会被旧轮清理覆盖。
     * 业务异常在结束当前诊断区段后继续抛出；其他消息被消费但不执行完成动作。
     */
    private fun getOrCreateHandler(): Handler {
        if (handler == null) {
            handler = Handler(Looper.getMainLooper()) { msg ->
                if (msg.what == MSG_EXC_RUNNABLE) {
                    Trace.traceBegin(Trace.TAG_VIEW, "exc delayRunnable")

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

    /**
     * 递增并返回本 helper 的序号，Bundle 写入和控制器配对共用此计数器。
     * 必须由主线程串行调用，不提供跨实例、跨进程的全局序号或溢出处理。
     */
    private fun updateSeqId(): Long = ++seqId

    /**
     * 在非空 Bundle 中写入新的启动序号；仅中断能力开启时才消耗计数器。
     * null 或能力关闭时保留容器内容和当前计数；只修改序号键，不负责跨进程传递。
     */
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

    /**
     * 能力关闭或距上次 Recents 完成严格超过 500ms 时返回 true；没有记录时也放行。
     * 每次读取重新查询能力和共享时钟，不缓存判断结果，不保证与后续排队使用的时间组成原子快照。
     */
    override val canFinishRecent: Boolean
        get() = !startingSurfaceSupported() || !interruptionSupported() ||
            AnimSeqTimeStamp.timeGapToLastRecentFinishTime > MAX_DELAY_TIME

    /**
     * 能力关闭或距最近应用启动严格超过 300ms 时返回 true；恰好处于边界时仍不放行。
     * 不自动消费时间戳；显式 resetInterceptState 只清除启动事件的限制。
     */
    override val canInterceptGesture: Boolean
        get() = !startingSurfaceSupported() || !interruptionSupported() ||
            AnimSeqTimeStamp.timeGapToLastStartAppTime > MAX_INTERCEPT_GESTURE_DELAY_TIME

    /**
     * 根据最近完成时间决定立即执行还是延后最新的完成请求；两条路径均先取消上一请求。
     * 允许完成时在调用线程执行 action 并返回 false；仍在窗口内时按剩余毫秒数排队并返回 true。
     * 第二次读时钟可能已跨过截止点，此时延迟钳制为零；null 也会替换旧任务，但不会执行业务动作。
     */
    override fun delayFinishRecents(action: (() -> Unit)?): Boolean {
        if (canFinishRecent) {

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

    /**
     * 删除本 helper 的待完成消息并释放保存的 action；尚未创建 Handler 时不触发初始化。
     * 不执行被取消动作，不影响其他 helper 的队列，重复调用安全；已经开始执行的回调无法撤回。
     */
    override fun clearFinishRecentsRunnable() {
        handler?.removeMessages(MSG_EXC_RUNNABLE)
        delayAction = null
    }

    /**
     * 清除共享的最近启动应用时间，使手势拦截窗口立即恢复放行。
     * 不修改最近 Recents 完成时间，不取消已经排队的完成任务，也不重置本实例的序号。
     */
    override fun resetInterceptState() {
        AnimSeqTimeStamp.resetLastStartAppTime()
    }

    /**
     * 中断能力开启时，为首次控制器或与已有对象不相等的控制器分配新完成序号。
     * 使用 Kotlin 相等性而非引用身份判断，null 也可形成有效配对；相等控制器重复更新不会增加序号。
     */
    override fun updateNextFinishSeqIdIfNeed(recentsController: Any?) {
        if (!interruptionSupported()) {
            LogUtils.i("AnimationSeqHelper", "skip nextFinish update: interruption unsupported")
            return
        }
        val p = nextFinishSeqId

        if (p == null || p.first != recentsController) {
            nextFinishSeqId = recentsController to updateSeqId()
            LogUtils.i("AnimationSeqHelper", "nextFinish seqId=${nextFinishSeqId?.second}")
        }
    }

    /**
     * 读取与传入控制器相等的当前配对序号，不存在匹配时返回零。
     * 只读取本 helper 的状态，不创建配对或递增计数，也不重新检查功能开关。
     */
    override fun getNextFinishSeqId(recentsController: Any?): Long {
        val p = nextFinishSeqId

        if (p != null && p.first == recentsController) return p.second
        return 0L
    }
}
