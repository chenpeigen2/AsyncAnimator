package com.asyncanimator.launcher.pending

import android.animation.Animator

/**
 * AnimationSuccessListener — 区分 cancel 和 success 的 listener 基类。
 *
 * 对应分析文档 §6.3.4。核心设计：
 *
 *  - cancel 路径把 `cancelled = true`
 *  - end 路径上若 cancelled=true 则不触发 onAnimationSuccess
 *  - 业务只 override [onAnimationSuccess]
 */
abstract class AnimationSuccessListener : ActualEndAnimListener() {

    override fun onAnimationCancel(animator: Animator) {
        super.onAnimationCancel(animator)
        cancelled = true
    }

    override fun onAnimationEnd(animator: Animator) {
        if (cancelled) return
        onAnimationSuccess(animator)
    }

    abstract fun onAnimationSuccess(animator: Animator)
}
