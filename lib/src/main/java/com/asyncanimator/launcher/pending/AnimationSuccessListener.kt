package com.asyncanimator.launcher.pending

import android.animation.Animator
import com.asyncanimator.launcher.async.ActualEndAnimListener

/**
 * AnimationSuccessListener — 区分 cancel 和 success 的 listener 基类。
 *
 * 对应 `docs/review/02-pending-playback.md`。父类链对齐原厂
 * （`com/android/launcher3/anim/AnimationSuccessListener.java:7`
 * `abstract class AnimationSuccessListener extends ActualEndAnimListener`）：
 *
 *  - cancel 路径把 `cancelled = true`
 *  - end 路径上若 cancelled=true 则不触发 onAnimationSuccess
 *  - 业务只 override [onAnimationSuccess]
 *  - 继承 [ActualEndAnimListener.onAnimActualEnd]：cancel/end 都会触发的
 *    "物理帧播完"钩子，由 `AsyncAnimCallbacks.onAnimActualEnd` 派发
 *    （双轨结束语义见 `docs/review/01-async-animthread.md` §②C-2）
 */
internal abstract class AnimationSuccessListener : ActualEndAnimListener() {

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
