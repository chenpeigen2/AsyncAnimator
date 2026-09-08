package com.asyncanimator.launcher.async

import androidx.dynamicanimation.animation.SpringAnimation

/**
 * AsyncSpringAnim — 把 androidx SpringAnimation 的生命周期方法按 `supportAnimThread`
 * marshal 到独立动画线程。
 *
 * 对应原厂 `com.android.quickstep.util.OplusAsyncSpringAnimWrapper`（extends
 * `com.android.launcher3.anim.AsyncAnimWrapper`）的线程切换骨架：
 *
 * ```
 * if (viewSupportAnimThread) runOnAnimThread(() -> springAnim.xxx());
 * else                      springAnim.xxx();
 * ```
 *
 * 原厂 SpringAnimation 内部走 ThreadLocal 的框架 AnimationHandler，start 在哪个线程，
 * 帧回调就在哪个线程；本移植用 androidx.dynamicanimation（同样是 ThreadLocal
 * AnimationHandler + 调用线程 Choreographer），语义一致。真正的弹簧物理直接用
 * androidx 库，不再手写 SpringForce/DynamicAnimation。
 */
class AsyncSpringAnim(
    private val real: SpringAnimation,
    private val supportAnimThread: Boolean
) {

    fun start() = dispatch { real.start() }

    fun cancel() = dispatch { real.cancel() }

    fun skipToEnd() = dispatch { real.skipToEnd() }

    fun animateToFinalPosition(position: Float) = dispatch { real.animateToFinalPosition(position) }

    fun setStartVelocity(velocity: Float) = dispatch { real.setStartVelocity(velocity) }

    private inline fun dispatch(crossinline action: () -> Unit) {
        if (supportAnimThread) {
            Executors.ANIM_CONTROL_EXECUTOR.execute { action() }
        } else {
            action()
        }
    }
}
