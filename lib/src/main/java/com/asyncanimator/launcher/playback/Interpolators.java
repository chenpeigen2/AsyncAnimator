package com.asyncanimator.launcher.playback;

import com.asyncanimator.core.anim.Interpolator;

/**
 * 预定义插值器集合。
 * 对应原 OPPO 代码 {@code com.android.launcher3.anim.Interpolators}（简化版）。
 */
public final class Interpolators {

    private Interpolators() {}

    public static final Interpolator LINEAR = input -> input;

    public static final Interpolator DECELERATE = input -> {
        float t = input + 1.0f;
        return t * t * ((1.7f + 1.0f) * t - 1.7f) / 2.0f;
    };
}