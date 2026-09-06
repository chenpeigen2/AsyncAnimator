package com.asyncanimator.core.anim;

import java.util.ArrayList;

/**
 * Animator — 动画抽象基类。
 *
 * <p>对应 Android 平台 {@code android.animation.Animator}（简化版）和分析文档 §5。
 *
 * <p>核心职责：
 * <ul>
 *   <li>维护三类 listener：start/end/cancel/repeat（{@link AnimatorListener}）、pause/resume（{@link AnimatorPauseListener}）、frame（{@link AnimatorUpdateListener}）</li>
 *   <li>提供 pause/resume/cancel/reverse 的公共 API</li>
 *   <li>提供静态 addAnimationCallback/removeAnimationCallback 委托给 {@link AnimationHandler}</li>
 * </ul>
 *
 * <p>子类必须实现的抽象方法：{@link #getDuration()}、{@link #isRunning()}。
 */
public abstract class Animator implements Cloneable {

    public static final long DURATION_INFINITE = -1L;

    protected ArrayList<AnimatorListener> mListeners;
    protected ArrayList<AnimatorPauseListener> mPauseListeners;
    protected ArrayList<AnimatorUpdateListener> mUpdateListeners;
    protected boolean mPaused = false;

    // ──── listener 接口 ────────────────────────────────────────────

    public interface AnimatorListener {
        void onAnimationCancel(Animator animator);
        void onAnimationEnd(Animator animator);
        default void onAnimationEnd(Animator animator, boolean isReversing) {
            onAnimationEnd(animator);
        }
        void onAnimationRepeat(Animator animator);
        void onAnimationStart(Animator animator);
        default void onAnimationStart(Animator animator, boolean isReversing) {
            onAnimationStart(animator);
        }
    }

    public interface AnimatorPauseListener {
        void onAnimationPause(Animator animator);
        void onAnimationResume(Animator animator);
    }

    public interface AnimatorUpdateListener {
        void onAnimationUpdate(Animator animator);
    }

    // ──── 静态便捷方法（调度入口）────────────────────────────────────

    public static void addAnimationCallback(AnimationHandler.AnimationFrameCallback callback) {
        AnimationHandler.getInstance().addAnimationFrameCallback(callback);
    }

    public static void removeAnimationCallback(AnimationHandler.AnimationFrameCallback callback) {
        AnimationHandler.getInstance().removeCallback(callback);
    }

    // ──── listener 管理 ─────────────────────────────────────────────

    public void addListener(AnimatorListener listener) {
        if (mListeners == null) mListeners = new ArrayList<>();
        mListeners.add(listener);
    }

    public void removeListener(AnimatorListener listener) {
        if (mListeners != null) mListeners.remove(listener);
    }

    public void removeAllListeners() {
        if (mListeners != null) mListeners.clear();
    }

    public ArrayList<AnimatorListener> getListeners() {
        return mListeners;
    }

    public void addPauseListener(AnimatorPauseListener listener) {
        if (mPauseListeners == null) mPauseListeners = new ArrayList<>();
        mPauseListeners.add(listener);
    }

    public void removePauseListener(AnimatorPauseListener listener) {
        if (mPauseListeners != null) mPauseListeners.remove(listener);
    }

    public void addUpdateListener(AnimatorUpdateListener listener) {
        if (mUpdateListeners == null) mUpdateListeners = new ArrayList<>();
        mUpdateListeners.add(listener);
    }

    public void removeUpdateListener(AnimatorUpdateListener listener) {
        if (mUpdateListeners != null) mUpdateListeners.remove(listener);
    }

    public void removeAllUpdateListeners() {
        if (mUpdateListeners != null) mUpdateListeners.clear();
    }

    // ──── 生命周期 API ──────────────────────────────────────────────

    public void pause() {
        if (!isStarted() || mPaused) return;
        mPaused = true;
        if (mPauseListeners != null) {
            ArrayList<AnimatorPauseListener> tmp =
                (ArrayList<AnimatorPauseListener>) mPauseListeners.clone();
            for (AnimatorPauseListener l : tmp) l.onAnimationPause(this);
        }
    }

    public void resume() {
        if (!mPaused) return;
        mPaused = false;
        if (mPauseListeners != null) {
            ArrayList<AnimatorPauseListener> tmp =
                (ArrayList<AnimatorPauseListener>) mPauseListeners.clone();
            for (AnimatorPauseListener l : tmp) l.onAnimationResume(this);
        }
    }

    public boolean isPaused() {
        return mPaused;
    }

    public boolean canReverse() {
        return false;
    }

    public void reverse() {
        throw new IllegalStateException("Reverse is not supported on Animator base class");
    }

    public boolean isStarted() {
        return isRunning();
    }

    public abstract boolean isRunning();

    public abstract long getDuration();

    /** AOSP 在 Animator 上声明 setDuration(long)（返回 Animator）；默认实现仅供 no-op 场景。 */
    public Animator setDuration(long duration) {
        return this;
    }

    /** AOSP 在 Animator 上声明 setInterpolator(TimeInterpolator)；基类 no-op，ValueAnimator 覆盖。 */
    public void setInterpolator(Interpolator interpolator) {
    }

    /**
     * AOSP 中 getAnimatedValue 位于 ValueAnimator；
     * 本移植版上移到基类，便于 AnimatorUpdateListener 统一处理 Animator 参数
     * （移植代码多处直接在 Animator 上调用）。基类默认返回 null。
     */
    public Object getAnimatedValue() {
        return null;
    }

    public long getTotalDuration() {
        long d = getDuration();
        return d == DURATION_INFINITE ? DURATION_INFINITE : d;
    }

    public abstract void start();

    public void startWithoutPulsing(boolean inReverse) {
        if (inReverse) reverse();
        else start();
    }

    public void cancel() {
        // 子类实现
    }

    public void end() {
        // 子类实现
    }

    public void setupStartValues() {}
    public void setupEndValues() {}

    /** clone 模式：浅拷贝 listener 列表，时间状态重置（子类应覆盖自己的字段）。 */
    @Override
    public Animator clone() {
        try {
            Animator anim = (Animator) super.clone();
            if (mListeners != null) anim.mListeners = new ArrayList<>(mListeners);
            if (mPauseListeners != null) anim.mPauseListeners = new ArrayList<>(mPauseListeners);
            if (mUpdateListeners != null) anim.mUpdateListeners = new ArrayList<>(mUpdateListeners);
            return anim;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("Animator clone not supported", e);
        }
    }

    public boolean pulseAnimationFrame(long frameTime) {
        return false;
    }
}