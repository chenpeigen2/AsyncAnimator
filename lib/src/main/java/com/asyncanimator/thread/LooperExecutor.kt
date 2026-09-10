package com.asyncanimator.thread

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.util.concurrent.TimeUnit
import android.os.Looper
import android.os.Message

/**
 * 基于 Handler 的线程归属执行器，不拥有底层线程的关闭权。
 * execute 可在所属线程内联执行，post/postAsync 在有 Handler 时始终入队。
 * @param handler 目标 Handler；为 null 时三个任务入口均退化为调用线程就地执行。
 */
class LooperExecutor internal constructor(private val handler: Handler?) {

    /**
     * 用于执行判断的有效所属线程：优先读取 Handler 的线程，无 Handler 时使用当前调用线程。
     */
    private val currentTargetThread: Thread?
        get() = handler?.looper?.thread ?: Thread.currentThread()

    /**
     * 判断调用方是否处于执行器的有效所属线程；无 Handler 的就地模式恒为 true。
     */
    val isCurrentThread: Boolean
        get() = currentTargetThread === Thread.currentThread()

    /**
     * 返回构造时传入的 Handler 实例，不创建替代对象。
     * 没有 Handler 的就地执行模式返回 null；调用方直接使用该对象时需自行遵守其 Looper 归属。
     */
    fun getHandler(): Handler? = handler

    /**
     * 返回底层 Handler 绑定的 Looper；未绑定 Handler 时返回 null。
     * 该访问不会启动线程，也不等待线程初始化或队列内已有任务完成。
     */
    fun getLooper(): Looper? = handler?.looper

    /**
     * 返回底层 Handler 的实际所属线程；就地执行模式返回 null。
     * 与内部用于判断就地执行的 currentTargetThread 不同，本方法不伪造一个绑定线程。
     */
    fun getTargetThread(): Thread? = handler?.looper?.thread

    /**
     * 返回与 getTargetThread 相同的底层所属线程，供需要线程访问器的调用方使用。
     * 没有 Handler 时仍返回 null，不将当前调用线程视为固定所属线程。
     */
    fun getThread(): Thread? = getTargetThread()

    /**
     * 设置底层 HandlerThread 的 Android 调度优先级，而不是修改调用方线程。
     * @param priority 传给 Process.setThreadPriority 的优先级数值，权限或范围异常由平台抛出。
     * @throws IllegalStateException 所属线程不是 HandlerThread，例如主线程执行器或无 Handler 模式。
     */
    fun setThreadPriority(priority: Int) {
        val target = getThread() as? HandlerThread
            ?: throw IllegalStateException("Thread priority requires a HandlerThread-backed executor")
        Process.setThreadPriority(target.threadId, priority)
    }

    /**
     * 执行可空任务：null 直接返回，已在所属线程时内联执行，否则通过普通消息入队。
     * 无 Handler 时视调用方为所属线程并就地运行；内联任务异常直接传播，排队任务在目标线程执行。
     */
    fun execute(action: (() -> Unit)?) {
        if (action == null) return
        if (isCurrentThread) action() else post(action)
    }

    /**
     * 通过底层 Handler 排入普通任务，即使当前已在所属线程也不会内联执行。
     * 无 Handler 时退化为就地执行；不返回投递结果，也不等待执行完成，已退出的 Looper 可能无法接收任务。
     */
    fun post(action: () -> Unit) {
        if (handler != null) handler.post { action() } else action()
    }

    /**
     * 以异步 Message 向底层 Handler 投递任务，使消息不被主线程同步屏障阻塞。
     * 这不创建新的工作线程，也不等待执行完成；没有 Handler 时直接在调用线程执行。
     */
    fun postAsync(action: () -> Unit) {
        val h = handler ?: return action()
        val msg = Message.obtain(h) { action() }
        msg.isAsynchronous = true
        h.sendMessage(msg)
    }

    /**
     * 拒绝关闭进程共享执行器，始终抛出 UnsupportedOperationException。
     * 不发送退出消息、不清空队列，也不改变 isShutdown 的返回值。
     */
    @Deprecated("LooperExecutor never quits; shutdown() always throws")
    fun shutdown(): Unit = throw UnsupportedOperationException("LooperExecutor never quits")

    /**
     * 拒绝立即终止进程共享执行器，始终抛出 UnsupportedOperationException。
     * 不会取消待执行任务，也不会返回任何未完成任务列表。
     */
    @Deprecated("LooperExecutor never quits; shutdownNow() always throws")
    fun shutdownNow(): List<Runnable> = throw UnsupportedOperationException("LooperExecutor never quits")

    /**
     * 始终返回 false；共享执行器不支持 shutdown，读取该属性不会探测底层 Looper 是否仍存活。
     */
    val isShutdown: Boolean get() = false

    /**
     * 始终返回 false；不把底层线程的状态包装成可终止线程池的生命周期。
     */
    val isTerminated: Boolean get() = false

    /**
     * 拒绝等待共享执行器退出，始终抛出 UnsupportedOperationException。
     * 参数 timeout 和 unit 不参与等待；本执行器不提供关闭或终止生命周期。
     */
    @Deprecated("LooperExecutor never quits; awaitTermination() always throws")
    fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
        throw UnsupportedOperationException("LooperExecutor never quits")
}
