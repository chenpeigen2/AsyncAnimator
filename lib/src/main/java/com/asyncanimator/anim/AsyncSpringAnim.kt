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
 * 弹簧物理用 androidx.dynamicanimation（ThreadLocal AnimationHandler + 调用线程
 * Choreographer），start 在哪个线程帧回调就在哪个线程；end 回调经
 * [AsyncAnimWrapper.runOnMainThread] 回主线程。
 */
class AsyncSpringAnim(
    private val real: SpringAnimation,
    private val supportAnimThread: Boolean
) : AsyncAnimWrapper() {

    fun start() = dispatch { real.start() }

    fun cancel() = dispatch { real.cancel() }

    fun skipToEnd() = dispatch { real.skipToEnd() }

    fun animateToFinalPosition(position: Float) = dispatch { real.animateToFinalPosition(position) }

    fun setStartVelocity(velocity: Float) = dispatch { real.setStartVelocity(velocity) }

    /** 结束回调：弹簧在 anim 线程 tick，但 end 回调 marshal 回主线程（对齐原厂约定）。 */
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
