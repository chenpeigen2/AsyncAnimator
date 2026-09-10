package com.asyncanimator.playback

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator

/**
 * 按不同收尾协议创建平台动画监听器的工厂。
 * 无参数结束回调、一次性布尔结果和未取消成功回调语义不同，调用方应按业务需求选用。
 */
internal object AnimatorListeners {

    /**
     * 创建在每次逻辑结束时调用无参数 onEnd 的平台监听器。
     * null 回调被忽略；不区分取消后结束与自然结束，不做一次性消费，也不会在取消通知本身调用回调。
     */
    fun forEndCallback(onEnd: (() -> Unit)?): Animator.AnimatorListener =
        object : AnimatorListenerAdapter() {
            /**
             * 在当前派发线程执行保存的非空结束回调，每次收到结束事件都会调用。
             * 不检查动画进度或取消状态；业务回调异常直接向派发方传播。
             */
            override fun onAnimationEnd(animator: Animator) {
                onEnd?.invoke()
            }
        }

    /**
     * 创建一次性结束状态监听器：取消报告 false，自然结束按进度判定成功。
     * ValueAnimator 的 animatedFraction 必须严格大于 0.5，其他 Animator 的结束视为成功。
     * 第一次取消或结束后即消费，不因重新开始而复位；null 回调仍保留相同消费规则。
     */
    fun forEndCallback(onEnd: ((success: Boolean) -> Unit)?): Animator.AnimatorListener =
        object : AnimatorListenerAdapter() {
            private var listenerCalled = false

            /**
             * 按动画类型计算本次结束的成功值，再交给一次性消费入口。
             * ValueAnimator 需要严格越过半程；其他 Animator 直接视为成功，已消费的监听器不会再次通知。
             */
            override fun onAnimationEnd(animator: Animator) {
                val va = animator as? ValueAnimator
                fire(va == null || va.animatedFraction > 0.5f)
            }

            /**
             * 将取消结果 false 交给一次性消费入口，不等待之后的逻辑结束。
             * 若此前已经收到并消费了结束或取消事件，本次不会再次调用业务回调。
             */
            override fun onAnimationCancel(animator: Animator) = fire(false)

            /**
             * 在第一次通知前先标记已消费，再执行非空业务回调。
             * 这样即使业务重入或抛出异常也不会重复触发；后续调用直接返回且不重置标记。
             */
            private fun fire(success: Boolean) {
                if (listenerCalled) return
                listenerCalled = true
                onEnd?.invoke(success)
            }
        }

    /**
     * 创建仅在未取消的逻辑结束时调用 onSuccess 的监听器。
     * 使用 AnimationSuccessListener 的粘性取消状态，不采用半程阈值，也不提供一次性消费保证。
     */
    fun forSuccessCallback(onSuccess: (() -> Unit)?): Animator.AnimatorListener =
        object : AnimationSuccessListener() {
            /**
             * 在基类确认本次结束未取消后，执行保存的非空成功回调。
             * 回调在当前派发线程运行，异常不吞掉；null 表示无需执行成功动作。
             */
            override fun onAnimationSuccess(animator: Animator) {
                onSuccess?.invoke()
            }
        }
}
