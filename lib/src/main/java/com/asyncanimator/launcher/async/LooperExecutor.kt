package com.asyncanimator.launcher.async

import android.os.Handler
import android.os.Message

/**
 * LooperExecutor — 跨线程 Executor 封装。
 *
 * 对应原 OPPO 代码 `com.oplus.basecommon.thread.LooperExecutor`（简化版）
 * 和 `docs/review/01-async-animthread.md`。
 *
 * 关键设计：[execute] 自动判断"当前线程 vs 目标 Looper"：
 *
 *  - 同一线程：直接执行
 *  - 不同线程：用 Handler.post 投递（[Executors.MAIN_EXECUTOR] 与
 *    `Executors.ANIM_CONTROL_EXECUTOR` 均绑定真实 android.os.Handler）
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

    fun execute(action: (() -> Unit)?) {
        if (action == null) return
        if (isCurrentThread) action() else post(action)
    }

    fun post(action: () -> Unit) {
        if (handler != null) handler.post { action() } else action()
    }

    /**
     * 以**异步消息**投递（`Message.setAsynchronous(true)`）：可穿透主线程 sync-barrier，
     * measure/layout（traversal）期间也能按时执行。
     *
     * 对齐原厂 `com/android/launcher3/Utilities.java:631-637` 的 `postAsyncCallback`
     * （`Message.obtain(handler, r)` + `setAsynchronous(true)` + `sendMessage`），
     * 原厂 AsyncAnimCallbacks 的 listener 派发走的就是这条路径
     * （`AsyncAnimCallbacks.java:120, 160`）。普通任务请用 [post]/[execute]。
     */
    fun postAsync(action: () -> Unit) {
        val h = handler ?: return action()
        val msg = Message.obtain(h) { action() }
        msg.isAsynchronous = true
        h.sendMessage(msg)
    }
}
