package com.asyncanimator.core.anim;

/**
 * AnimatorListenerAdapter — Animator.AnimatorListener 的空实现基类。
 *
 * <p>对应 Android 平台 {@code android.animation.AnimatorListenerAdapter}。
 * 让业务 listener 可以选择性 override。
 */
public class AnimatorListenerAdapter implements Animator.AnimatorListener {

    @Override public void onAnimationCancel(Animator animator) {}
    @Override public void onAnimationEnd(Animator animator) {}
    @Override public void onAnimationEnd(Animator animator, boolean isReversing) {
        onAnimationEnd(animator);
    }
    @Override public void onAnimationRepeat(Animator animator) {}
    @Override public void onAnimationStart(Animator animator) {}
    @Override public void onAnimationStart(Animator animator, boolean isReversing) {
        onAnimationStart(animator);
    }
}