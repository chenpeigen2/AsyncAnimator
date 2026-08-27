package com.asyncanimator.core.anim;

/**
 * AccelerateDecelerateInterpolator — 默认插值器，先加速后减速。
 *
 * <p>对应 Android 平台 {@code android.view.animation.AccelerateDecelerateInterpolator}。
 * 公式：{@code ((input * 2 - 1)^3 + 1) / 2}，input=0.5 时输出 0.5（对称）。
 */
public class AccelerateDecelerateInterpolator implements Interpolator {
    @Override
    public float getInterpolation(float input) {
        return (float) (Math.pow((input * 2.0f - 1.0f), 3.0) + 1.0) / 2.0f;
    }
}