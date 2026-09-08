package com.asyncanimator.launcher.async

import android.os.Handler

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
 *    `AnimExecutors.ANIM_CONTROL_EXECUTOR` 均绑定真实 android.os.Handler）
 *
 * JVM 单测环境下（android stub，returnDefaultValues）拿不到主 Looper，
 * [handler] 为 null，全部退化为"就地执行"，保证单测可跑。
 */
class LooperExecutor internal constructor(private val handler: Handler?) {

    /** 目标线程。handler 为 null（JVM 单测）时视为"调用方线程"。 */
    private val thread: Thread?
        get() = handler?.looper?.thread ?: Thread.currentThread()

    val isCurrentThread: Boolean
        get() = thread === Thread.currentThread()

    fun execute(runnable: Runnable?) {
        if (runnable == null) return
        if (isCurrentThread) runnable.run() else post(runnable)
    }

    fun post(runnable: Runnable) {
        handler?.post(runnable) ?: runnable.run()
    }

    fun postDelayed(runnable: Runnable, delayMs: Long) {
        handler?.postDelayed(runnable, delayMs) ?: Thread({
            try {
                Thread.sleep(delayMs)
            } catch (ignored: InterruptedException) {
            }
            runnable.run()
        }, "AsyncAnimator-Delayed").start()
    }
}
