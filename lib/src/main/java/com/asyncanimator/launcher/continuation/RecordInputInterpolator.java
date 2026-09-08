package com.asyncanimator.launcher.continuation;

import android.animation.TimeInterpolator;

/**
 * RecordInputInterpolator — 记录最近一次 input 副作用的插值器。
 *
 * <p>对应分析文档 §6.5.5。{@link #getInterpolation(float)} 时把 input 缓存到 {@link #inputed}，
 * 续行动画时 {@code generateContinuationAnim} 通过 {@link #getInputed()} 取出。
 */
public class RecordInputInterpolator implements TimeInterpolator {

    private final TimeInterpolator realInterpolator;
    private float inputed = -1f;

    public RecordInputInterpolator(TimeInterpolator real) {
        this.realInterpolator = real;
    }

    @Override
    public float getInterpolation(float input) {
        this.inputed = input;
        return realInterpolator.getInterpolation(input);
    }

    public float getInputed() {
        return inputed;
    }
}