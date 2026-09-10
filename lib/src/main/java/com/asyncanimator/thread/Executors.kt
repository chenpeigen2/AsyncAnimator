package com.asyncanimator.thread

import android.os.Handler
import android.os.Looper

/**
 * 集中提供主线程和独立动画线程的共享执行器。
 * 主线程执行器初始化不启动动画线程；动画执行器只在首次访问时以同步惰性方式创建。
 */
object Executors {

    /**
     * 绑定系统主 Looper 的共享执行器；无法取得主 Looper 时使用无 Handler 的就地执行模式。
     */
    val MAIN_EXECUTOR = LooperExecutor(mainHandlerOrNull())

    /**
     * 首次访问时取得并启动共享动画线程，创建绑定其 Looper 的执行器。
     * 取得引用不表示 onLooperPrepared 已返回；经 Handler 提交的任务将在准备完成、消息循环开始后执行。
     */
    val ANIM_CONTROL_EXECUTOR: LooperExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LooperExecutor(Handler(AnimationControlThread.instance.looper))
    }

    /**
     * 尝试为系统主 Looper 创建 Handler，与哪个线程首先加载本对象无关。
     * 主 Looper 不可用或访问过程抛出异常时返回 null，使主线程执行器可使用就地执行的降级模式。
     */
    private fun mainHandlerOrNull(): Handler? =
        runCatching { Looper.getMainLooper()?.let(::Handler) }.getOrNull()
}
