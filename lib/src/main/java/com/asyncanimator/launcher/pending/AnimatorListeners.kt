package com.asyncanimator.launcher.pending

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import java.util.function.Consumer

/**
 * AnimatorListeners — 工厂方法集合。
 *
 * 对应分析文档 §6.3.7。提供三种 listener 工厂：
 *
 *  - [forEndCallback]（Runnable）— 任何 end 都触发
 *  - [forEndCallback]（Consumer）— 区分 success (true) / cancel (false)
 *  - [forSuccessCallback] — 仅 success 触发
 */
internal object AnimatorListeners {

    fun forEndCallback(r: Runnable?): Animator.AnimatorListener =
        object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animator: Animator) {
                r?.run()
            }
        }

    fun forEndCallback(c: Consumer<Boolean>?): Animator.AnimatorListener =
        object : AnimatorListenerAdapter() {
            private var listenerCalled = false

            override fun onAnimationEnd(animator: Animator) {
                if (listenerCalled) return
                listenerCalled = true
                c?.accept(java.lang.Boolean.TRUE)
            }

            override fun onAnimationCancel(animator: Animator) {
                if (listenerCalled) return
                listenerCalled = true
                c?.accept(java.lang.Boolean.FALSE)
            }
        }

    fun forSuccessCallback(r: Runnable?): Animator.AnimatorListener =
        object : AnimationSuccessListener() {
            override fun onAnimationSuccess(animator: Animator) {
                r?.run()
            }
        }
}
