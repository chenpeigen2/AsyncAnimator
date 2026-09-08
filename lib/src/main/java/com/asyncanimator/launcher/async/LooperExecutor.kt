package com.asyncanimator.launcher.async

/**
 * LooperExecutor — 跨线程 Executor 封装。
 *
 * 对应原 OPPO 代码 `com.oplus.basecommon.thread.LooperExecutor`（简化版）
 * 和分析文档 §6.3.2。
 *
 * 关键设计：[execute] 自动判断"当前线程 vs 目标 Looper"：
 *
 *  - 同一线程：直接 `runnable.run()`
 *  - 不同线程：用 Handler.post 投递（[Executors.MAIN_EXECUTOR] 与
 *    `AnimExecutors.ANIM_CONTROL_EXECUTOR` 均绑定真实 android.os.Handler；
 *    仅 JVM 单测兜底为就地执行）
 */
class LooperExecutor(private val handler: Handler?) {

    /** 简化版 Handler 契约。 */
    interface Handler {
        val looper: Looper?
        fun post(r: Runnable): Boolean
        fun postDelayed(r: Runnable, delayMs: Long): Boolean
    }

    /** 简化版 Looper 契约。demo 模块会用真实 Android Looper 实现。 */
    fun interface Looper {
        fun thread(): Thread
    }

    internal val looper: Looper? get() = handler?.looper

    internal val thread: Thread? get() = handler?.looper?.thread()

    internal val isCurrentThread: Boolean
        get() = looper?.let { it.thread() === Thread.currentThread() } ?: false

    internal fun execute(runnable: Runnable?) {
        if (runnable == null) return
        if (isCurrentThread) {
            runnable.run()
        } else {
            handler?.post(runnable)
        }
    }

    internal fun post(runnable: Runnable) {
        handler?.post(runnable)
    }

    internal fun postDelayed(runnable: Runnable, delayMs: Long) {
        handler?.postDelayed(runnable, delayMs)
    }
}
