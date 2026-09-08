package com.asyncanimator.launcher.pending;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.util.FloatProperty;

import com.asyncanimator.launcher.playback.AnimatorPlaybackController;
import com.asyncanimator.launcher.playback.PropertySetter;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * PendingAnimation — 转场动画的"构建器"。
 *
 * <p>对应分析文档 §6.2。
 *
 * <p>关键设计：
 * <ul>
 *   <li>实现 {@link PropertySetter}（addFloat/setViewAlpha 等），让代码可以在"动画 vs no-op"之间无缝切换</li>
 *   <li>{@code add(Animator, TimeInterpolator, SpringProperty)} 把每个属性动画加到 {@link #mAnimHolders}</li>
 *   <li>{@code mProgressAnimator} 是辅助 ValueAnimator，挂 onEndListener / onFrameListener</li>
 *   <li>{@link #buildAnim()} 把 mProgressAnimator 合并到顶层 AnimatorSet，{@link #createPlaybackController()} 包装成 APC</li>
 * </ul>
 */
public class PendingAnimation implements PropertySetter {

    private final AnimatorSet mAnim = new AnimatorSet();
    private final ArrayList<AnimatorPlaybackController.Holder> mAnimHolders = new ArrayList<>();
    private final long mDuration;
    private ValueAnimator mProgressAnimator;
    private AnimatorPlaybackController mController;

    public boolean isAnimFinished = false;

    public PendingAnimation(long duration) {
        mDuration = duration > 0 ? duration : 0;
        mAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationStart(Animator a) { isAnimFinished = false; }
            @Override public void onAnimationEnd(Animator a)   { isAnimFinished = true; }
            @Override public void onAnimationCancel(Animator a) { isAnimFinished = true; }
        });
    }

    public PendingAnimation add(Animator anim) {
        anim.setDuration(mDuration);
        mAnim.playTogether(anim);
        addToHolders(anim);
        return this;
    }

    public PendingAnimation add(Animator anim, TimeInterpolator ip) {
        anim.setInterpolator(ip);
        return add(anim);
    }

    public PendingAnimation add(Animator anim, TimeInterpolator ip, Object springProperty) {
        anim.setInterpolator(ip);
        return add(anim);
    }

    public PendingAnimation addWithoutDuration(Animator anim) {
        mAnim.playTogether(anim);
        addToHolders(anim);
        return this;
    }

    public <T> PendingAnimation addFloat(T target, FloatProperty<T> property,
                                         float from, float to, TimeInterpolator ip) {
        ObjectAnimator oa = ObjectAnimator.ofFloat(target, property, from, to);
        oa.setInterpolator(ip);
        return add(oa.getAnimator());
    }

    @Override
    public <T> void setFloat(T target, FloatProperty<T> property, float value) {
        property.setValue(target, value);
    }

    public PendingAnimation addEndListener(Consumer<Boolean> consumer) {
        if (mProgressAnimator == null) mProgressAnimator = ValueAnimator.ofFloat(0f, 1f);
        mProgressAnimator.addListener(AnimatorListeners.forEndCallback(consumer));
        return this;
    }

    public PendingAnimation addOnFrameCallback(Runnable runnable) {
        if (mProgressAnimator == null) mProgressAnimator = ValueAnimator.ofFloat(0f, 1f);
        mProgressAnimator.addUpdateListener(animator -> runnable.run());
        return this;
    }

    public PendingAnimation addListener(Animator.AnimatorListener l) {
        mAnim.addListener(l);
        return this;
    }

    public AnimatorSet buildAnim() {
        if (mProgressAnimator != null) {
            addWithoutDuration(mProgressAnimator);
            mProgressAnimator = null;
        }
        if (mAnimHolders.isEmpty()) {
            addWithoutDuration(ValueAnimator.ofFloat(0f, 1f).setDuration(mDuration));
        }
        return mAnim;
    }

    public AnimatorPlaybackController createPlaybackController() {
        if (mController == null) {
            mController = new AnimatorPlaybackController(buildAnim(), mDuration, mAnimHolders);
        }
        return mController;
    }

    private void addToHolders(Animator anim) {
        AnimatorPlaybackController.addHoldersRecur(anim, mDuration, mAnimHolders);
    }

    // ──── 简化的 ObjectAnimator ────────────────────────────────

    public static class ObjectAnimator {
        private final Object target;
        private final FloatProperty property;
        private final float from;
        private final float to;
        private final ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);

        public static <T> ObjectAnimator ofFloat(T target, FloatProperty<T> property,
                                                float from, float to) {
            return new ObjectAnimator(target, property, from, to);
        }

        /** public 以便 continuation 包的 TimeControllerObjectAnimator 子类可调用 super(...)。 */
        public ObjectAnimator(Object target, FloatProperty property, float from, float to) {            this.target = target;
            this.property = property;
            this.from = from;
            this.to = to;
        }

        public ObjectAnimator setInterpolator(TimeInterpolator ip) {
            va.setInterpolator(ip);
            return this;
        }

        public ObjectAnimator setDuration(long duration) {
            va.setDuration(duration);
            return this;
        }

        public ObjectAnimator setFloatValues(float... values) {
            va.setFloatValues(values);
            return this;
        }

        public Animator getAnimator() {
            // 内部 va 推进时把 fraction 映射到 from→to 再 setValue
            va.addUpdateListener(a -> {
                Float f = (Float) a.getAnimatedValue();
                if (f != null && property != null && target != null) {
                    float v = from + (to - from) * f;
                    property.setValue((Object) target, v);
                }
            });
            // 平台 Animator 的抽象方法比仿写件多（getStartDelay/setStartDelay/
            // setDuration/setInterpolator），全部委托给内部 va，保持原包装语义不变
            return new Animator() {
                @Override public void start() { va.start(); }
                @Override public void cancel() { va.cancel(); }
                @Override public void end() { va.end(); }
                @Override public boolean isRunning() { return va.isRunning(); }
                @Override public long getDuration() { return va.getDuration(); }
                @Override public Animator setDuration(long duration) { va.setDuration(duration); return this; }
                @Override public long getStartDelay() { return va.getStartDelay(); }
                @Override public void setStartDelay(long startDelay) { va.setStartDelay(startDelay); }
                @Override public void setInterpolator(TimeInterpolator ip) { va.setInterpolator(ip); }
            };
        }

        // 让 add() 可以直接接收 ObjectAnimator
        public long getDuration() { return va.getDuration(); }
        public TimeInterpolator getInterpolator() { return va.getInterpolator(); }
        public void setDurationOnly(long d) { va.setDuration(d); }

        // ──── 生命周期委托（TimeControllerObjectAnimator 依赖） ────────
        public void start() { va.start(); }
        public void cancel() { va.cancel(); }
        public void end() { va.end(); }
        public void pause() { va.pause(); }
        public boolean isRunning() { return va.isRunning(); }
        public void addListener(Animator.AnimatorListener l) { va.addListener(l); }
    }
}