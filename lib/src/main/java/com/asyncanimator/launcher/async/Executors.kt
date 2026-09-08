package com.asyncanimator.launcher.async

import android.os.Handler
import android.os.Looper

/**
 * Executors — 预定义的 LooperExecutor 单例集合。
 *
 * 对应原 OPPO 代码 `com.oplus.basecommon.thread.Executors`（简化版）
 * 和 `docs/review/01-async-animthread.md`。
 *
 * [MAIN_EXECUTOR] 直接绑定 `Looper.getMainLooper()`，
 * 与"哪个线程先触发类加载"无关——在任意线程首次引用都指向真正的主线程。
 * JVM 单测环境下（android stub，returnDefaultValues）拿不到主 Looper，
 * handler 为 null，[LooperExecutor] 退化为"就地执行"，保证单测可跑。
 */
object Executors {

    /** 主线程 LooperExecutor（绑定真实 Main Looper）。 */
    val MAIN_EXECUTOR = LooperExecutor(mainHandlerOrNull())

    private fun mainHandlerOrNull(): Handler? = try {
        Looper.getMainLooper()?.let(::Handler)
    } catch (t: Throwable) {
        null // JVM 单测无 android runtime
    }
}
