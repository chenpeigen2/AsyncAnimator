package com.asyncanimator.launcher.pending;

import android.animation.Animator;

/**
 * AnimationSuccessListener — 区分 cancel 和 success 的 listener 基类。
 *
 * <p>对应分析文档 §6.3.4。核心设计：
 * <ul>
 *   <li>cancel 路径把 {@code mCancelled = true}</li>
 *   <li>end 路径上若 mCancelled=true 则不触发 onAnimationSuccess</li>
 *   <li>业务只 override {@link #onAnimationSuccess(Animator)}</li>
 * </ul>
 */
public abstract class AnimationSuccessListener extends ActualEndAnimListener {

    @Override
    public void onAnimationCancel(Animator animator) {
        super.onAnimationCancel(animator);
        mCancelled = true;
    }

    @Override
    public void onAnimationEnd(Animator animator) {
        if (mCancelled) return;
        onAnimationSuccess(animator);
    }

    public abstract void onAnimationSuccess(Animator animator);
}