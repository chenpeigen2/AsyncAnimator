package com.asyncanimator.core.anim;

import java.util.ArrayList;

/**
 * AnimatorSet — 动画集合，并行/串行播放多个子动画。
 *
 * <p>对应 Android 平台 {@code android.animation.AnimatorSet}（简化版）。
 *
 * <p>简化实现：只支持 playTogether（并行）；playSequentially 在 lib 模块不做实现。
 */
public class AnimatorSet extends Animator {

    private final ArrayList<Animator> mChildren = new ArrayList<>();
    private boolean mStarted = false;

    public AnimatorSet playTogether(Animator... animators) {
        for (Animator a : animators) mChildren.add(a);
        return this;
    }

    public ArrayList<Animator> getChildAnimations() {
        return mChildren;
    }

    @Override
    public void start() {
        mStarted = true;
        for (Animator child : mChildren) child.start();
    }

    @Override
    public void cancel() {
        for (Animator child : mChildren) child.cancel();
        mStarted = false;
    }

    @Override
    public void end() {
        for (Animator child : mChildren) child.end();
        mStarted = false;
    }

    @Override
    public boolean isRunning() {
        for (Animator child : mChildren) if (child.isRunning()) return true;
        return false;
    }

    @Override
    public long getDuration() {
        long max = 0;
        for (Animator child : mChildren) max = Math.max(max, child.getDuration());
        return max;
    }

    @Override
    public void setupStartValues() {
        for (Animator child : mChildren) child.setupStartValues();
    }

    @Override
    public void setupEndValues() {
        for (Animator child : mChildren) child.setupEndValues();
    }
}