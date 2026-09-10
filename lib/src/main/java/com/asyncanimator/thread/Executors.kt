package com.asyncanimator.thread

import android.os.Handler
import android.os.Looper

/**
 * Executors - 预定义的 LooperExecutor 单例集合。
 *
 * 对应原 OPPO 代码 `com.oplus.basecommon.thread.Executors` + `OplusExecutors`（简化版）
 * 和 `docs/review/01-async-animthread.md`。
 *
 *  - [MAIN_EXECUTOR] 直接绑 `Looper.getMainLooper()`，与"哪个线程先触发类加载"无关。
 *    JVM 单测环境（android stub，returnDefaultValues）拿不到 Looper，退化为就地执行。
 *  - [ANIM_CONTROL_EXECUTOR] 绑独立的 launcher.anim HandlerThread（首次访问时拉起），
 *    对应原厂 `OplusExecutors.ANIM_EXECUTOR`。
 */
object Executors {

    /** 主线程 LooperExecutor（绑定真实 Main Looper）。 */
    val MAIN_EXECUTOR = LooperExecutor(mainHandlerOrNull())

    /**
     * 独立动画线程 LooperExecutor（launcher.anim，首次访问即启动该线程）。
     * 取得执行器不代表 onLooperPrepared 已返回；通过它提交的普通任务会在准备完成后运行。
     */
    val ANIM_CONTROL_EXECUTOR: LooperExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LooperExecutor(Handler(AnimationControlThread.instance.looper))
    }

    private fun mainHandlerOrNull(): Handler? =
        runCatching { Looper.getMainLooper()?.let(::Handler) }.getOrNull()
}

/*
 * NOTE: OPPO's OplusLooperExecutor extends LooperExecutor with executeBlockWait()
 * (OplusLooperExecutor.java:46-71) — a blocking execute that waits up to 5 seconds
 * on the caller thread. This is intentionally NOT ported: it risks ANR if the target
 * looper is congested, and lib consumers should prefer the non-blocking execute()/post()
 * APIs instead.
 */
