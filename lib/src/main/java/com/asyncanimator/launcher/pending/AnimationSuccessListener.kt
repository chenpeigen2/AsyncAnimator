package com.asyncanimator.launcher.pending

import android.animation.Animator

/**
 * AnimationSuccessListener — 区分 cancel 和 success 的 listener 基类。
 *
 * 对应 `docs/review/02-pending-playback.md`。核心设计：
 *
 *  - cancel 路径把 `cancelled = true`
 *  - end 路径上若 cancelled=true 则不触发 onAnimationSuccess
 *  - 业务只 override [onAnimationSuccess]
 *
 * 原厂的 `ActualEndAnimListener` 中间层（cancel/end 都触发的 hook）在本移植中没有第二个
 * 子类，已合并进本类——[onAnimActualEnd] 保留为扩展点。
 */
internal abstract class AnimationSuccessListener : NullableAnimatorListenerAdapter() {

    override fun onAnimationCancel(animator: Animator) {
        super.onAnimationCancel(animator)
        cancelled = true
    }

    override fun onAnimationEnd(animator: Animator) {
        if (cancelled) return
        onAnimationSuccess(animator)
    }

    abstract fun onAnimationSuccess(animator: Animator)

    /** cancel/end 都触发的 hook（原厂 ActualEndAnimListener 的语义，"无论如何都要清理"）。 */
    open fun onAnimActualEnd(animator: Animator) {}
}
