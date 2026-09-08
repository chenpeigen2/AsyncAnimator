package com.asyncanimator.launcher.async

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AsyncValueAnimator — 跨 Looper 安全的 ValueAnimator。
 *
 * 对应分析文档 §6.3。start/cancel/end 先判断"当前线程 vs 目标 Looper"，
 * 不一致时通过 LooperExecutor marshal 过去。
 *
 * listener 跨线程派发（[asyncAnimCallbacks]）：listener fire 时再 marshal 回主线程。
 */
class AsyncValueAnimator : ValueAnimator() {

    /** 动画执行器：start/cancel/end 与帧推进所在的 Looper。 */
    var executor: LooperExecutor = Executors.MAIN_EXECUTOR

    /** listener 容器 + 跨线程派发器（listener 始终回主线程 fire）。 */
    val asyncAnimCallbacks = AsyncAnimCallbacks()

    private val isEnd = AtomicBoolean(false)

    init {
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(a: Animator) {
                if (isEnd.get()) return
                asyncAnimCallbacks.onAnimationCancel(a)
            }

            override fun onAnimationEnd(a: Animator) {
                if (isEnd.compareAndSet(false, true)) {
                    asyncAnimCallbacks.onAnimationEnd(a)
                }
            }

            override fun onAnimationStart(a: Animator) {
                if (isEnd.get()) return
                asyncAnimCallbacks.onAnimationStart(a)
            }
        })
    }

    private val isCurrentExecutor: Boolean get() = executor.isCurrentThread

    override fun start() {
        if (isCurrentExecutor) super.start()
        else executor.execute { super@AsyncValueAnimator.start() }
    }

    override fun cancel() {
        if (isCurrentExecutor) super.cancel()
        else executor.execute { super@AsyncValueAnimator.cancel() }
    }

    override fun end() {
        if (isCurrentExecutor) super.end()
        else executor.execute { super@AsyncValueAnimator.end() }
    }
}
