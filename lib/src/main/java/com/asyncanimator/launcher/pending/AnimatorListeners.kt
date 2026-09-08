package com.asyncanimator.launcher.pending

import android.animation.Animator
import android.animation.AnimatorListenerAdapter

/**
 * AnimatorListeners — 工厂方法集合。
 *
 * 对应 `docs/review/02-pending-playback.md`。提供三种 listener 工厂：
 *
 *  - [forEndCallback]（Runnable）— 任何 end 都触发
 *  - [forEndCallback]（函数类型）— 区分 success (true) / cancel (false)
 *  - [forSuccessCallback] — 仅 success 触发
 */
internal object AnimatorListeners {

    fun forEndCallback(onEnd: (() -> Unit)?): Animator.AnimatorListener =
        object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animator: Animator) {
                onEnd?.invoke()
            }
        }

    /** success 回调 true，cancel 回调 false；最多 fire 一次。 */
    fun forEndCallback(onEnd: ((success: Boolean) -> Unit)?): Animator.AnimatorListener =
        object : AnimatorListenerAdapter() {
            private var listenerCalled = false

            override fun onAnimationEnd(animator: Animator) = fire(true)
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
