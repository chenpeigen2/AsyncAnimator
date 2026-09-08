package com.asyncanimator.launcher.pending

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator

/**
 * AnimatorListeners — 监听器工厂集合。
 *
 * 对应 `docs/review/02-pending-playback.md`；原厂 `com.android.launcher3.anim.AnimatorListeners`。
 * 提供三种 listener 工厂：
 *
 *  - [forEndCallback]（Runnable 版）：任何 end 都回调
 *  - [forEndCallback]（带布尔参数）：回调 success (true) / cancel (false)
 *  - [forSuccessCallback]：仅 success 回调
 */
internal object AnimatorListeners {

    fun forEndCallback(onEnd: (() -> Unit)?): Animator.AnimatorListener =
        object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animator: Animator) {
                onEnd?.invoke()
            }
        }

    /**
     * success 回调 true、cancel 回调 false，仅 fire 一次。
     *
     * success 判定按原厂 AnimatorListeners.EndStateCallbackWrapper（AnimatorListeners.java:34-40）：
     * 动画自然结束但停在 [ValueAnimator.getAnimatedFraction] <= 0.5 时视为**未成功**
     * （手势抬手后"播完≠成功"，未过半程要回退）。cancel 一律 false。
     */
    fun forEndCallback(onEnd: ((success: Boolean) -> Unit)?): Animator.AnimatorListener =
        object : AnimatorListenerAdapter() {
            private var listenerCalled = false

            override fun onAnimationEnd(animator: Animator) {
                val va = animator as? ValueAnimator
                fire(va == null || va.animatedFraction > 0.5f)
            }

            override fun onAnimationCancel(animator: Animator) = fire(false)

            private fun fire(success: Boolean) {
                if (listenerCalled) return
                listenerCalled = true
                onEnd?.invoke(success)
            }
        }

    fun forSuccessCallback(onSuccess: (() -> Unit)?): Animator.AnimatorListener =
        object : AnimationSuccessListener() {
            override fun onAnimationSuccess(animator: Animator) {
                onSuccess?.invoke()
            }
        }
}
