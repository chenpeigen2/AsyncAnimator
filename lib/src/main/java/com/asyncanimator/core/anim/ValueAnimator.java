package com.asyncanimator.core.anim;

import com.asyncanimator.core.scheduler.ScheduledTickScheduler;
import com.asyncanimator.core.scheduler.TickScheduler;

import java.util.ArrayList;

/**
 * ValueAnimator — 时序动画基类。
 *
 * <p>对应 Android 平台 {@code androidx.core.animation.ValueAnimator}（简化版）和分析文档 §4。
 *
 * <p>核心时间模型：
 * <ul>
 *   <li>{@code mStartTime} — 启动时间戳（绝对 uptime ms），-1 表示未初始化</li>
 *   <li>{@code mLastFrameTime} — 上一帧的 frameTimeMs，{@code >= 0} 表示"已在 pulse 中"</li>
 *   <li>{@code mOverallFraction} — 全局进度 [0, mRepeatCount+1]，clamp 后</li>
 *   <li>{@code mCurrentFraction} — 经插值器映射后的最终 fraction（用户可见）</li>
 *   <li>{@code mSeekFraction} — 显式 seek 目标，-1 表示无 seek</li>
 *   <li>{@code mReversing} — 当前迭代方向</li>
 * </ul>
 *
 * <p>实现 {@link AnimationHandler.AnimationFrameCallback}，每帧由 AnimationHandler.onTick 触发。
 */
public class ValueAnimator extends Animator implements AnimationHandler.AnimationFrameCallback {

    public static final int INFINITE = -1;
    public static final int RESTART = 1;
    public static final int REVERSE = 2;

    private static final Interpolator sDefaultInterpolator = new AccelerateDecelerateInterpolator();
    private static volatile float sDurationScale = 1.0f;

    private boolean mReversing = false;
    PropertyValuesHolder[] mValues;
    private float mSeekFraction = -1.0f;
    private float mOverallFraction = 0.0f;
    private float mCurrentFraction = 0.0f;
    long mStartTime = -1;
    long mLastFrameTime = -1;
    private boolean mRunning = false;
    private boolean mStarted = false;
    private boolean mInitialized = false;
    private boolean mAnimationEndRequested = false;
    private boolean mStartListenersCalled = false;
    private long mDuration = 300;
    private long mStartDelay = 0;
    private int mRepeatCount = 0;
    private int mRepeatMode = RESTART;
    private boolean mSelfPulse = true;
    private boolean mSuppressSelfPulseRequested = false;
    private Interpolator mInterpolator = sDefaultInterpolator;
    private float mDurationScale = -1.0f;

    public ValueAnimator() {
    }

    public static ValueAnimator ofFloat(float... values) {
        ValueAnimator va = new ValueAnimator();
        va.setFloatValues(values);
        return va;
    }

    public static ValueAnimator ofInt(int... values) {
        ValueAnimator va = new ValueAnimator();
        va.setIntValues(values);
        return va;
    }

    public static ValueAnimator ofObject(TypeEvaluator evaluator, Object... values) {
        ValueAnimator va = new ValueAnimator();
        va.setObjectValues(values);
        va.setEvaluator(evaluator);
        return va;
    }

    public void setFloatValues(float... values) {
        if (values == null || values.length == 0) return;
        if (mValues == null || mValues.length == 0) {
            mValues = new PropertyValuesHolder[values.length];
        }
        PropertyValuesHolder[] pvh = new PropertyValuesHolder[values.length];
        pvh[0] = new PropertyValuesHolder.FloatPropertyValuesHolder("", values[0], values[0]);
        for (int i = 1; i < values.length; i++) {
            pvh[i] = new PropertyValuesHolder.FloatPropertyValuesHolder(
                "", values[i - 1], values[i]);
        }
        mValues = pvh;
    }

    public void setIntValues(int... values) {
        setFloatValues(toFloats(values));
    }

    public void setObjectValues(Object... values) {
        // 简化版：仅保留 Object 引用
    }

    public void setEvaluator(TypeEvaluator evaluator) {
        // 简化版：暂存，便于将来扩展
    }

    private static float[] toFloats(int[] values) {
        float[] f = new float[values.length];
        for (int i = 0; i < values.length; i++) f[i] = values[i];
        return f;
    }

    private static class TypeEvaluator {}

    public void setInterpolator(Interpolator interpolator) {
        if (interpolator != null) mInterpolator = interpolator;
    }

    public Interpolator getInterpolator() {
        return mInterpolator;
    }

    /** 返回 this 以对齐 AOSP builder 风格（ValueAnimator.setDuration 返回 ValueAnimator）。 */
    public ValueAnimator setDuration(long duration) {
        if (duration < 0) throw new IllegalArgumentException("duration < 0");
        mDuration = duration;
        return this;
    }

    @Override
    public long getDuration() {
        return mDuration;
    }

    public void setStartDelay(long startDelay) {
        mStartDelay = startDelay > 0 ? startDelay : 0;
    }

    public long getStartDelay() {
        return mStartDelay;
    }

    public void setRepeatCount(int repeatCount) {
        mRepeatCount = repeatCount;
    }

    public int getRepeatCount() {
        return mRepeatCount;
    }

    public void setRepeatMode(int repeatMode) {
        mRepeatMode = repeatMode;
    }

    public int getRepeatMode() {
        return mRepeatMode;
    }

    public static void setDurationScale(float scale) {
        sDurationScale = scale;
    }

    public static float getDurationScale() {
        return sDurationScale;
    }

    private float resolveDurationScale() {
        return mDurationScale >= 0 ? mDurationScale : sDurationScale;
    }

    private long getScaledDuration() {
        return (long) (mDuration * resolveDurationScale());
    }

    public float getAnimatedFraction() {
        return mCurrentFraction;
    }

    public Object getAnimatedValue() {
        if (mValues == null || mValues.length == 0) return 0f;
        if (mValues[0] instanceof PropertyValuesHolder.FloatPropertyValuesHolder) {
            return ((PropertyValuesHolder.FloatPropertyValuesHolder) mValues[0]).getValue();
        }
        return null;
    }

    public void setCurrentFraction(float fraction) {
        initAnimation();
        fraction = clampFraction(fraction);
        mOverallFraction = fraction;
        mSeekFraction = fraction;
        // AOSP 语义：seek 立即求值——经插值器更新 mCurrentFraction、计算属性值并派发 update listener
        animateValue(getCurrentIterationFraction(fraction, mReversing));
    }

    public void setCurrentPlayTime(long playTime) {
        setCurrentFraction(mDuration > 0 ? (float) playTime / mDuration : 1f);
    }

    public long getCurrentPlayTime() {
        if (mStartTime < 0) return 0;
        return AnimationHandler.getInstance().getScheduler().getFrameTimeNanos() / 1_000_000L - mStartTime;
    }

    private float clampFraction(float f) {
        if (f < 0f) return 0f;
        return mRepeatCount != INFINITE ? Math.min(f, mRepeatCount + 1) : f;
    }

    private boolean isPulsingInternal() {
        return mLastFrameTime >= 0;
    }

    // ──── 启动 ────────────────────────────────────────────────

    @Override
    public void start() {
        start(false);
    }

    public void startWithoutPulsing(boolean inReverse) {
        mSuppressSelfPulseRequested = true;
        if (inReverse) reverse();
        else start();
        mSuppressSelfPulseRequested = false;
    }

    public void start(boolean reverse) {
        if ((LooperHelper.myLooper() == null)) {
            throw new IllegalStateException("Animators may only be run on Looper threads");
        }
        mReversing = reverse;
        mSelfPulse = !mSuppressSelfPulseRequested;
        if (mStartDelay == 0 || mSeekFraction >= 0.0f || mReversing) {
            startAnimation();
            float f = mSeekFraction;
            if (f == -1.0f) setCurrentPlayTime(0);
            else setCurrentFraction(f);
        }
        addAnimationCallback();
    }

    private void startAnimation() {
        mAnimationEndRequested = false;
        mInitialized = false;
        mRunning = true;
        mOverallFraction = mSeekFraction >= 0 ? mSeekFraction : 0f;
        // notifyStartListeners 简化
        notifyStartListeners();
    }

    private void notifyStartListeners() {
        if (mListeners != null && !mStartListenersCalled) {
            ArrayList<AnimatorListener> tmp = (ArrayList<AnimatorListener>) mListeners.clone();
            for (AnimatorListener l : tmp) l.onAnimationStart(this, mReversing);
        }
        mStartListenersCalled = true;
    }

    private void initAnimation() {
        if (mInitialized) return;
        mInitialized = true;
    }

    private void addAnimationCallback() {
        if (mSelfPulse) Animator.addAnimationCallback(this);
    }

    private void removeAnimationCallback() {
        if (mSelfPulse) Animator.removeAnimationCallback(this);
    }

    // ──── 帧回调 ────────────────────────────────────────────────

    /**
     * AnimationHandler 每帧调一次（如果 mSelfPulse=true）。
     * 对应分析文档 §4.2 doAnimationFrame 完整流程。
     */
    @Override
    public boolean doAnimationFrame(long frameTime) {
        // ① 首次：确定启动时间
        if (mStartTime < 0) {
            mStartTime = mReversing ? frameTime : frameTime + (long) (mStartDelay * resolveDurationScale());
        }
        // ② 暂停路径
        if (mPaused) {
            removeAnimationCallback();
            return false;
        }
        // ③ resume 后第一帧：补偿暂停时间
        // 简化：未实现 mPauseTime / mResumed 状态机
        // ④ 进入 RUNNING 状态
        if (!mRunning) {
            if (mStartTime > frameTime && mSeekFraction == -1.0f) return false;
            mRunning = true;
            startAnimation();
        }
        // ⑤ 首脉冲遇到 seek：反推启动时间
        if (mLastFrameTime < 0 && mSeekFraction >= 0.0f) {
            mStartTime = frameTime - (long) (getScaledDuration() * mSeekFraction);
            mSeekFraction = -1.0f;
        }
        mLastFrameTime = frameTime;
        // ⑥ 时间推进
        boolean ended = animateBasedOnTime(Math.max(frameTime, mStartTime));
        if (ended) endAnimation();
        return ended;
    }

    /**
     * 时间模型核心：基于当前时间计算 fraction，触发 animateValue。
     * 对应分析文档 §4.3 animateBasedOnTime。
     */
    private boolean animateBasedOnTime(long currentTime) {
        if (!mRunning) return false;
        boolean ended = false;
        long scaledDuration = getScaledDuration();
        float fraction = scaledDuration > 0 ? (currentTime - mStartTime) / (float) scaledDuration : 1f;

        // 跨过迭代边界
        boolean crossedIteration = ((int) fraction) > ((int) mOverallFraction);
        boolean pastDuration = fraction >= (mRepeatCount + 1) && mRepeatCount != INFINITE;

        if (scaledDuration != 0) {
            if (crossedIteration && !pastDuration) {
                // 触发 onAnimationRepeat
                if (mListeners != null) {
                    ArrayList<AnimatorListener> tmp =
                        (ArrayList<AnimatorListener>) mListeners.clone();
                    for (AnimatorListener l : tmp) l.onAnimationRepeat(this);
                }
            } else if (pastDuration) {
                ended = true;
            }
            float clamped = clampFraction(fraction);
            mOverallFraction = clamped;
            animateValue(getCurrentIterationFraction(clamped, mReversing));
        }
        return ended;
    }

    private float getCurrentIterationFraction(float fraction, boolean reversing) {
        float clamped = clampFraction(fraction);
        // 精确到达结束边界（fraction == mRepeatCount + 1）视为完成态返回 1.0，
        // 避免 floor 折返到 0 —— 否则 seek(1.0) 的表现是"回到起点"
        if (mRepeatCount != INFINITE && clamped >= mRepeatCount + 1) return 1f;
        int iter = (int) Math.floor(clamped);
        float sub = clamped - iter;
        // REVERSE 模式下奇数迭代反向
        return shouldPlayBackward(iter, reversing) ? 1f - sub : sub;
    }

    private boolean shouldPlayBackward(int iteration, boolean reversing) {
        return reversing && (mRepeatMode == REVERSE) && (iteration % 2 == 1);
    }

    /**
     * 应用插值器、计算每个 PropertyValuesHolder、通知 UpdateListener。
     * 对应分析文档 §4.4 animateValue。
     */
    public void animateValue(float fraction) {
        fraction = mInterpolator.getInterpolation(fraction);
        mCurrentFraction = fraction;
        if (mValues != null) {
            for (PropertyValuesHolder pvh : mValues) pvh.calculateValue(fraction);
        }
        if (mUpdateListeners != null) {
            ArrayList<AnimatorUpdateListener> tmp =
                (ArrayList<AnimatorUpdateListener>) mUpdateListeners.clone();
            for (AnimatorUpdateListener l : tmp) l.onAnimationUpdate(this);
        }
    }

    // ──── 收尾 ────────────────────────────────────────────────

    private void endAnimation() {
        if (mAnimationEndRequested) return;
        removeAnimationCallback();
        mAnimationEndRequested = true;
        mPaused = false;
        boolean startedOrRunning = (mStarted || mRunning) && mListeners != null;
        if (startedOrRunning && !mRunning) notifyStartListeners();
        mRunning = false;
        mStarted = false;
        mStartListenersCalled = false;
        mLastFrameTime = -1;
        mStartTime = -1;
        if (startedOrRunning && mListeners != null) {
            ArrayList<AnimatorListener> tmp = (ArrayList<AnimatorListener>) mListeners.clone();
            for (AnimatorListener l : tmp) l.onAnimationEnd(this, mReversing);
        }
        mReversing = false;
    }

    @Override
    public void cancel() {
        if (LooperHelper.myLooper() == null) {
            throw new IllegalStateException("Animators may only be run on Looper threads");
        }
        if (mListeners != null && (mStarted || mRunning)) {
            ArrayList<AnimatorListener> tmp = (ArrayList<AnimatorListener>) mListeners.clone();
            for (AnimatorListener l : tmp) l.onAnimationCancel(this);
        }
        endAnimation();
    }

    @Override
    public void end() {
        if (LooperHelper.myLooper() == null) {
            throw new IllegalStateException("Animators may only be run on Looper threads");
        }
        if (!mRunning) {
            // 尚未运行：补发 start listener 让 onAnimationEnd 语义完整
            if (mListeners != null) notifyStartListeners();
            mStarted = true;
        }
        animateValue(mReversing ? 0f : 1f);
        endAnimation();
    }

    public void skipToEndValue(boolean reversing) {
        animateValue(reversing ? 0f : 1f);
        endAnimation();
    }

    @Override
    public void reverse() {
        if (isPulsingInternal()) {
            long currentPlayTime = getCurrentPlayTime();
            mStartTime = currentPlayTime - (getScaledDuration() - currentPlayTime);
            mReversing = !mReversing;
            endAnimation();
        } else if (!mStarted) {
            start(true);
        } else {
            mReversing = !mReversing;
            end();
        }
    }

    @Override
    public boolean isRunning() {
        return mStarted || mRunning;
    }

    // ──── LooperHelper ─────────────────────────────────────────

    /**
     * 简化的 Looper 检测：在 demo 模块会替换为真实检测 Android Looper 的实现。
     * lib 模块默认返回非 null（用 ScheduledTickScheduler 自带的 daemon thread）。
     */
    static class LooperHelper {
        static Object myLooper() {
            // lib 模块不依赖 Android Looper，认为有 Looper
            return new Object();
        }
    }
}