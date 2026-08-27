package com.asyncanimator.dyn.anim;

import java.util.ArrayList;

/**
 * DynamicAnimation — 物理动画抽象基类。
 *
 * <p>对应 Android 平台 {@code androidx.dynamicanimation.animation.DynamicAnimation}（简化版）
 * 和分析文档 §3.3。
 *
 * <p>关键设计（与 ValueAnimator 对比）：
 * <ul>
 *   <li>用 {@code dt = j8 - mLastFrameTime}（帧间 dt）而非绝对时间</li>
 *   <li>{@link #updateValueAndVelocity(long)} 是模板方法，由子类实现物理积分</li>
 *   <li>{@link #getAcceleration} / {@link #isAtEquilibrium} / {@link #updateValueAndVelocity} 是三个抽象方法</li>
 *   <li>每帧通过 {@link AnimationHandler} 调度</li>
 * </ul>
 */
public abstract class DynamicAnimation<T extends DynamicAnimation<T>>
        implements AnimationHandler.AnimationFrameCallback {

    public interface OnAnimationEndListener {
        void onAnimationEnd(DynamicAnimation animation, boolean canceled, float value, float velocity);
    }

    public interface OnAnimationUpdateListener {
        void onAnimationUpdate(DynamicAnimation animation, float value, float velocity);
    }

    public static final float MIN_VISIBLE_CHANGE_PIXELS = 1.0f;
    public static final float MIN_VISIBLE_CHANGE_ALPHA = 0.00390625f;
    public static final float MIN_VISIBLE_CHANGE_SCALE = 0.002f;
    public static final float MIN_VISIBLE_CHANGE_ROTATION_DEGREES = 0.1f;

    protected float mMinVisibleChange;
    protected float mValue = Float.MAX_VALUE;
    protected float mVelocity = 0f;
    protected float mMinValue = -Float.MAX_VALUE;
    protected float mMaxValue = Float.MAX_VALUE;
    protected long mLastFrameTime = 0;
    protected boolean mRunning = false;
    private boolean mStartValueIsSet = false;

    private final ArrayList<OnAnimationEndListener> mEndListeners = new ArrayList<>();
    private final ArrayList<OnAnimationUpdateListener> mUpdateListeners = new ArrayList<>();
    private final Object mTarget;

    protected DynamicAnimation(Object target) {
        mTarget = target;
        mMinVisibleChange = MIN_VISIBLE_CHANGE_PIXELS;
    }

    public T addEndListener(OnAnimationEndListener listener) {
        if (!mEndListeners.contains(listener)) mEndListeners.add(listener);
        return (T) this;
    }

    public T removeEndListener(OnAnimationEndListener listener) {
        removeEntry(mEndListeners, listener);
        return (T) this;
    }

    public T addUpdateListener(OnAnimationUpdateListener listener) {
        if (isRunning()) {
            throw new UnsupportedOperationException("Update listeners must be added before the animation");
        }
        if (!mUpdateListeners.contains(listener)) mUpdateListeners.add(listener);
        return (T) this;
    }

    public T removeUpdateListener(OnAnimationUpdateListener listener) {
        removeEntry(mUpdateListeners, listener);
        return (T) this;
    }

    public boolean isRunning() {
        return mRunning;
    }

    public T setMinValue(float min) {
        mMinValue = min;
        return (T) this;
    }

    public T setMaxValue(float max) {
        mMaxValue = max;
        return (T) this;
    }

    public T setStartValue(float value) {
        mValue = value;
        mStartValueIsSet = true;
        return (T) this;
    }

    public T setStartVelocity(float velocity) {
        mVelocity = velocity;
        return (T) this;
    }

    public float getMinimumVisibleChange() {
        return mMinVisibleChange;
    }

    public T setMinimumVisibleChange(float change) {
        mMinVisibleChange = change;
        return (T) this;
    }

    public float getValueThreshold() {
        return mMinVisibleChange * 0.75f;
    }

    public T setValueThreshold(float threshold) {
        if (threshold > 0) mMinVisibleChange = threshold / 0.75f;
        return (T) this;
    }

    /** 主线程校验：物理动画必须 main thread。 */
    public void start() {
        // 简化：lib 模块不强校验主线程（demo 模块会用 Android Looper 校验）
        if (mRunning) return;
        startAnimationInternal();
    }

    public void cancel() {
        if (mRunning) endAnimationInternal(true);
    }

    public T animateToFinalPosition(float finalPosition) {
        mValue = finalPosition;
        return (T) this;
    }

    protected void startAnimationInternal() {
        sanityCheck();
        if (!mStartValueIsSet) {
            // 默认从 0 起
            mValue = 0f;
        }
        mRunning = true;
        AnimationHandler.getInstance().addAnimationFrameCallback(this, 0L);
    }

    protected void sanityCheck() {
        if (mValue > mMaxValue || mValue < mMinValue) {
            throw new IllegalStateException(
                "Value must be in [min, max]: value=" + mValue
                    + " min=" + mMinValue + " max=" + mMaxValue);
        }
    }

    protected void endAnimationInternal(boolean canceled) {
        mRunning = false;
        AnimationHandler.getInstance().removeCallback(this);
        mLastFrameTime = 0;
        mStartValueIsSet = false;
        // 通知 listener（带 cancel 标记和最终 value/velocity）
        ArrayList<OnAnimationEndListener> tmp = (ArrayList<OnAnimationEndListener>) mEndListeners.clone();
        for (OnAnimationEndListener l : tmp) {
            if (l != null) l.onAnimationEnd(this, canceled, mValue, mVelocity);
        }
        // 清理 null 槽
        removeNullEntries(mEndListeners);
    }

    @Override
    public boolean doAnimationFrame(long frameTimeMs) {
        long last = mLastFrameTime;
        if (last == 0) {
            // 首帧：记录时间，应用初值
            mLastFrameTime = frameTimeMs;
            setPropertyValue(mValue);
            return false;
        }
        mLastFrameTime = frameTimeMs;
        boolean ended = updateValueAndVelocity(frameTimeMs - last);   // ★ 帧间 dt
        // clamp 到 min/max
        mValue = Math.min(mValue, mMaxValue);
        mValue = Math.max(mValue, mMinValue);
        setPropertyValue(mValue);
        if (ended) endAnimationInternal(false);
        return ended;
    }

    /** 物理积分（模板方法）：由子类实现。返回 true 表示到达平衡点。 */
    public abstract boolean updateValueAndVelocity(long dtMs);

    /** 加速度 a = F/m，由子类（{@link SpringForce}）实现。 */
    public abstract float getAcceleration(float value, float velocity);

    /** 平衡判定：由子类实现。 */
    public abstract boolean isAtEquilibrium(float value, float velocity);

    /**
     * 把当前值写到 target + 通知 updateListener。
     * 简化版：直接通知 listener；不直接调用 mTarget.setXxx（不同子类有不同 property）。
     */
    protected void setPropertyValue(float value) {
        ArrayList<OnAnimationUpdateListener> tmp =
            (ArrayList<OnAnimationUpdateListener>) mUpdateListeners.clone();
        for (OnAnimationUpdateListener l : tmp) {
            if (l != null) l.onAnimationUpdate(this, value, mVelocity);
        }
        removeNullEntries(mUpdateListeners);
    }

    private static <L> void removeEntry(ArrayList<L> list, L item) {
        int idx = list.indexOf(item);
        if (idx >= 0) list.set(idx, null);
    }

    private static <L> void removeNullEntries(ArrayList<L> list) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i) == null) list.remove(i);
        }
    }
}