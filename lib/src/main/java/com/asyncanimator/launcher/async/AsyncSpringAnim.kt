package com.asyncanimator.launcher.async

import androidx.dynamicanimation.animation.SpringAnimation
import com.asyncanimator.launcher.animthread.AsyncAnimWrapper

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
 * 原厂 SpringAnimation 走 ThreadLocal 的框架 AnimationHandler（start 在哪个线程，帧回调
 * 就在哪个线程）；本移植用 androidx.dynamicanimation（同样是 ThreadLocal AnimationHandler
 * + 调用线程 Choreographer），语义一致，且不碰框架 @hide API。
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

    private inline fun dispatch(crossinline action: () -> Unit) {
        if (supportAnimThread) {
            runOnAnimThread { action() }
        } else {
            action()
        }
    }
}
