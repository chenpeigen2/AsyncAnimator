package com.asyncanimator.core.anim;

/**
 * Interpolator — 简化版时间插值器接口。
 *
 * <p>对应 Android 平台 {@code android.view.animation.Interpolator}。
 * 分析文档 §4 ValueAnimator.animateValue 用 Interpolator 把 fraction [0,1] 映射成
 * 实际的动画进度（e.g. AccelerateDecelerateInterpolator 让 fraction=0.5 时输出 ≈0.5）。
 */
public interface Interpolator {
    float getInterpolation(float input);
}