package com.asyncanimator.launcher.async

import android.os.Handler

/**
 * Executors — 预定义的 LooperExecutor 单例集合。
 *
 * 对应原 OPPO 代码 `com.oplus.basecommon.thread.Executors`（简化版）
 * 和分析文档 §6.3.2。
 *
 * [MAIN_EXECUTOR] 直接绑定 `android.os.Looper.getMainLooper()`，
 * 与"哪个线程先触发类加载"无关——在任意线程首次引用都指向真正的主线程。
 * JVM 单测环境下（android stub，returnDefaultValues）拿不到主 Looper，
 * 退化为"就地执行"，保证单测可跑。
 */
object Executors {

    /** 主线程 LooperExecutor（绑定真实 Main Looper）。 */
    val MAIN_EXECUTOR = LooperExecutor(MainHandlerAdapter())

    private fun mainLooperOrNull(): android.os.Looper? = try {
        android.os.Looper.getMainLooper()
    } catch (t: Throwable) {
        null // JVM 单测无 android runtime
    }

    /** android.os.Handler(mainLooper) → LooperExecutor.Handler 适配。 */
    private class MainHandlerAdapter : LooperExecutor.Handler {

        @Volatile
        private var mainHandler: Handler? = null

        private fun handlerOrNull(): Handler? {
            if (mainHandler == null) {
                val main = mainLooperOrNull()
                if (main != null) {
                    mainHandler = Handler(main)
                }
            }
            return mainHandler
        }

        override val looper: LooperExecutor.Looper
            get() {
                val h = handlerOrNull()
                if (h != null) {
                    val l = h.looper
                    return LooperExecutor.Looper { l.thread }
                }
                // JVM 单测兜底：把当前线程当目标线程 → execute() 就地执行
                return LooperExecutor.Looper { Thread.currentThread() }
            }

        override fun post(r: Runnable): Boolean {
            val h = handlerOrNull()
            if (h != null) {
                return h.post(r)
            }
            r.run()
            return true
        }

        override fun postDelayed(r: Runnable, delayMs: Long): Boolean {
            val h = handlerOrNull()
            if (h != null) {
                return h.postDelayed(r, delayMs)
            }
            Thread({
                try {
                    Thread.sleep(delayMs)
                } catch (ignored: InterruptedException) {
                }
                r.run()
            }, "AsyncAnimator-Delayed").start()
            return true
        }
    }
}
