package com.asyncanimator.anim

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import java.util.concurrent.atomic.AtomicBoolean
import com.asyncanimator.thread.LooperExecutor
import com.asyncanimator.thread.Executors

/**
 * AsyncValueAnimator — 跨 Looper 安全的 ValueAnimator。
 *
 * 对应 `docs/review/01-async-animthread.md`。start/cancel/end 先判断"当前线程 vs 目标 Looper"，
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
                if (!isEnd.get()) asyncAnimCallbacks.onAnimationCancel(a)
            }

            override fun onAnimationEnd(a: Animator) {
                if (isEnd.compareAndSet(false, true)) asyncAnimCallbacks.onAnimationEnd(a)
            }

            override fun onAnimationStart(a: Animator) {
                if (!isEnd.get()) asyncAnimCallbacks.onAnimationStart(a)
            }
        })
    }

    private val isCurrentExecutor: Boolean get() = executor.isCurrentThread

    /** 当前线程已在目标 Looper 上就直接执行，否则 marshal 过去。 */
    private inline fun marshal(crossinline action: () -> Unit) {
        if (isCurrentExecutor) action() else executor.execute { action() }
    }

    override fun start() = marshal { super@AsyncValueAnimator.start() }

    override fun cancel() = marshal { super@AsyncValueAnimator.cancel() }

    override fun end() = marshal { super@AsyncValueAnimator.end() }
}
