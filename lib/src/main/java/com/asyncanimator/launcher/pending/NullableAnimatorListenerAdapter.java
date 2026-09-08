package com.asyncanimator.launcher.pending;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;

/**
 * NullableAnimatorListenerAdapter — 允许 Animator 为 null 的 listener 基类。
 *
 * <p>对应分析文档 §6.3.4。早期仿写件时代 AsyncAnimCallbacks 派发时常传 null，listener 需可容忍；
 * 换平台 {@code android.animation.Animator} 后参数为 {@code @NonNull}，现派发真实 animator，
 * 本类的 null 容忍仅作防御保留。
 */
public class NullableAnimatorListenerAdapter extends AnimatorListenerAdapter implements NullableAnimatorListener {

    protected boolean mCancelled = false;
    private int mAnimationId = -1;

    @Override public void onAnimationCancel(Animator animator) {
        mCancelled = true;
    }
    @Override public void onAnimationEnd(Animator animator) {}
    @Override public void onAnimationStart(Animator animator) {}

    public int getAnimationId() { return mAnimationId; }
    public void setAnimationId(int id) { mAnimationId = id; }
}