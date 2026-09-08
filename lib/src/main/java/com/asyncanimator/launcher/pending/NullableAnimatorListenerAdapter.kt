package com.asyncanimator.launcher.pending

import android.animation.Animator
import android.animation.AnimatorListenerAdapter

/**
 * NullableAnimatorListenerAdapter — Animator listener 基类。
 *
 * 对应 `docs/review/02-pending-playback.md`。早期仿写件时代 AsyncAnimCallbacks 派发时常传 null，listener 需可容忍；
 * 换平台 `android.animation.Animator` 后参数为 `@NonNull`，现派发真实 animator。
 */
open class NullableAnimatorListenerAdapter : AnimatorListenerAdapter(), NullableAnimatorListener {

    /** cancel 路径置位，end 时据此区分 success/cancel（见 [AnimationSuccessListener]）。 */
    protected var cancelled = false

    internal var animationId = -1

    override fun onAnimationCancel(animator: Animator) {
        cancelled = true
    }

    override fun onAnimationEnd(animator: Animator) {}

    override fun onAnimationStart(animator: Animator) {}
}
