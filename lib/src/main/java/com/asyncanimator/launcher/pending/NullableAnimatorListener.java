package com.asyncanimator.launcher.pending;

import com.asyncanimator.core.anim.Animator;

/**
 * NullableAnimatorListener — Animator 参数可空的 listener 接口。
 *
 * <p>对应分析文档 §6.3.4。
 */
public interface NullableAnimatorListener {
    default void onAnimationCancel(Animator animator) {}
    default void onAnimationEnd(Animator animator) {}
    default void onAnimationStart(Animator animator) {}
}