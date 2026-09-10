package com.asyncanimator.anim

import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import com.asyncanimator.thread.AsyncAnimWrapper

/**
 * AsyncSpringAnim - 把 androidx SpringAnimation 的生命周期方法按 `supportAnimThread`
 * marshal 到独立动画线程。继承 [AsyncAnimWrapper]，对齐原厂
 * `OplusAsyncSpringAnimWrapper extends AsyncAnimWrapper` 的骨架：
 *
 * ```
 * if (viewSupportAnimThread) runOnAnimThread(() -> springAnim.xxx());
 * else                      springAnim.xxx();
 * ```
 *
 * 这里只纠偏生命周期调用，不安装 AndroidX 的后台帧调度器，也不接管 View 写入。
 * supportAnimThread=true 不能保证后台 SpringAnimation.start() 或 View 属性写入可用；
 * 调用方必须提供支持该线程的真实引擎/调度配置。end 回调经 runOnMainThread 返回主线程。
 */
class AsyncSpringAnim(
    private val real: SpringAnimation,
    private val supportAnimThread: Boolean
) : AsyncAnimWrapper() {

    fun start() = dispatch { real.start() }

    /** Stops immediately when this command reaches the native owner; preserves current value. */
    fun cancel() = dispatch { real.cancel() }

    /** Requests a native terminal frame, not immediate end. First-frame warm-up still applies.
     * Zero damping throws UnsupportedOperationException; cancel remains available.
     */
    fun skipToEnd() = dispatch { real.skipToEnd() }

    fun animateToFinalPosition(position: Float) = dispatch { real.animateToFinalPosition(position) }

    fun setStartVelocity(velocity: Float) = dispatch { real.setStartVelocity(velocity) }

    /** 结束回调：异步模式下把通知 marshal 回主线程，不保证物理帧已正确运行在后台。 */
    fun addEndListener(listener: DynamicAnimation.OnAnimationEndListener) {
        real.addEndListener { anim, canceled, value, velocity ->
            val notify = { listener.onAnimationEnd(anim, canceled, value, velocity) }
            if (supportAnimThread) runOnMainThread { notify() } else notify()
        }
    }

    private inline fun dispatch(crossinline action: () -> Unit) {
        if (supportAnimThread) {
            runOnAnimThread { action() }
        } else {
            action()
        }
    }
}
