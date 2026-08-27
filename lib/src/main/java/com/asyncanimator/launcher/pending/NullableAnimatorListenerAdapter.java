package com.asyncanimator.launcher.pending;

import com.asyncanimator.core.anim.Animator;
import com.asyncanimator.core.anim.AnimatorListenerAdapter;

/**
 * NullableAnimatorListenerAdapter — 允许 Animator 为 null 的 listener 基类。
 *
 * <p>对应分析文档 §6.3.4。AsyncAnimCallbacks 派发 listener 时常传 null，listener 需可容忍。
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