package com.asyncanimator.launcher.spring;

import com.asyncanimator.core.anim.Animator;
import com.asyncanimator.core.anim.AnimatorListenerAdapter;
import com.asyncanimator.core.anim.Interpolator;
import com.asyncanimator.core.anim.ValueAnimator;
import com.asyncanimator.dyn.anim.DynamicAnimation;
import com.asyncanimator.dyn.anim.SpringAnimation;
import com.asyncanimator.dyn.anim.SpringForce;
import com.asyncanimator.util.FloatProperty;
import com.asyncanimator.launcher.async.Executors;

/**
 * OplusSpringObjectAnimator — SpringProperty 装饰器实现"渐进切换"。
 *
 * <p>对应分析文档 §6.4。核心洞察：**this 是 wrapper**，内部用 ObjectAnimator + SpringProperty 装饰器；
 * ObjectAnimator 始终在跑，每帧调 setValue，SpringProperty 根据 useSpring 标志转发给 spring 或原 property。
 *
 * <p>两套 AnimationHandler 并存：
 * <ul>
 *   <li>core.anim.AnimationHandler 驱动 mObjectAnimator（即 ValueAnimator 路径）</li>
 *   <li>dyn.anim.AnimationHandler 驱动 mSpring（SpringAnimation 路径）</li>
 * </ul>
 */
public class OplusSpringObjectAnimator<T> extends ValueAnimator {

    private final SpringProperty<T> mProperty;
    private final SpringAnimation mSpring;
    private final ObjectAnimator mObjectAnimator;
    private final T mTarget;
    private final float[] mValues;
    private final String mName;
    private boolean mEnded = false;
    private boolean mAnimatorEnded = false;

    public OplusSpringObjectAnimator(T target, FloatProperty<T> property,
                                    float minChange, float dampingRatio, float stiffness,
                                    float... values) {
        mTarget = target;
        mValues = values;
        mName = "OplusSpringObjectAnimator@" + Integer.toHexString(hashCode());
        // SpringAnimation 用 property 的 wrapper
        mSpring = new SpringAnimation(target);
        mSpring.setMinimumVisibleChange(minChange);
        mSpring.setSpring(new SpringForce(0f).setDampingRatio(dampingRatio).setStiffness(stiffness));
        mSpring.setStartVelocity(0.01f);
        mSpring.addEndListener(new DynamicAnimation.OnAnimationEndListener() {
            @Override public void onAnimationEnd(DynamicAnimation a, boolean canceled, float value, float velocity) {
                mAnimatorEnded = true;
                tryEnding();
            }
        });
        mProperty = new SpringProperty<>(property, mSpring);
        // 真实驱动器：ObjectAnimator
        ObjectAnimator oa = ObjectAnimator.ofFloat(target, mProperty, values);
        oa.setInterpolator(Interpolators.LINEAR);
        mObjectAnimator = oa;
    }

    /** 装饰器转发：插值器作用于内部 ObjectAnimator（返回 void 以对齐 core 签名）。 */
    public void setInterpolator(Interpolator ip) {
        mObjectAnimator.setInterpolator(ip);
    }

    @Override
    public void start() {
        mObjectAnimator.start();
    }

    @Override
    public void cancel() {
        mObjectAnimator.cancel();
        mSpring.cancel();
    }

    @Override
    public void end() {
        mObjectAnimator.end();
    }

    @Override
    public boolean isRunning() {
        return mObjectAnimator.isRunning() || mSpring.isRunning();
    }

    @Override
    public long getDuration() {
        return mObjectAnimator.getDuration();
    }

    /**
     * 切换驱动：让 mSpring 接管 setValue。
     * 对应分析文档 §6.4.3 switchToSpring 时机。
     */
    public void startSpring(float startFraction, float velocity,
                            DynamicAnimation.OnAnimationEndListener endListener) {
        mSpring.removeEndListener(endListener);
        mSpring.cancel();
        mSpring.addEndListener(endListener);
        mProperty.switchToSpring();
        // 选择反向终点
        float target = startFraction == 0f ? mValues[1] : mValues[0];
        mSpring.setStartVelocity(Math.abs(velocity) * Math.signum(target - (startFraction == 0f ? mValues[0] : mValues[1])));
        // 切到 spring：延迟一帧启动（确保 ObjectAnimator setValue 已完成当前帧）
        Executors.MAIN_EXECUTOR.postDelayed(() -> mSpring.animateToFinalPosition(target), 16);
    }

    private void tryEnding() {
        if (!mAnimatorEnded || mEnded) return;
        if (mListeners != null) {
            for (Animator.AnimatorListener l : (Iterable<Animator.AnimatorListener>) mListeners.clone()) {
                if (l != null) l.onAnimationEnd(this);
            }
        }
        mEnded = true;
    }

    @Override
    public void addListener(Animator.AnimatorListener l) {
        super.addListener(l);
    }

    // ──── 简化的 ObjectAnimator ────────────────────────────────

    public static class ObjectAnimator {
        private final Object target;
        private final FloatProperty property;
        private final float from;
        private final float to;
        private final ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);

        public static <T> ObjectAnimator ofFloat(T target, FloatProperty<T> property, float... values) {
            float from = values.length > 1 ? values[0] : 0f;
            float to = values.length > 1 ? values[values.length - 1] : values[0];
            return new ObjectAnimator(target, property, from, to);
        }

        ObjectAnimator(Object target, FloatProperty property, float from, float to) {
            this.target = target;
            this.property = property;
            this.from = from;
            this.to = to;
        }

        public ObjectAnimator setInterpolator(Interpolator ip) {
            va.setInterpolator(ip);
            return this;
        }

        public ObjectAnimator setDuration(long duration) {
            va.setDuration(duration);
            return this;
        }

        public void start() {
            va.addUpdateListener(a -> {
                Float f = (Float) ((ValueAnimator) a).getAnimatedValue();
                if (f != null && property != null) {
                    float v = from + (to - from) * f;
                    property.setValue((Object) target, v);
                }
            });
            va.start();
        }

        public void cancel() { va.cancel(); }
        public void end() { va.end(); }
        public boolean isRunning() { return va.isRunning(); }
        public long getDuration() { return va.getDuration(); }
        public Interpolator getInterpolator() { return va.getInterpolator(); }
        public void addListener(Animator.AnimatorListener l) { va.addListener(l); }
    }

    // ──── SpringProperty ────────────────────────────────

    public static class SpringProperty<T> extends FloatProperty<T> {
        private final FloatProperty<T> mProperty;
        private final SpringAnimation mSpring;
        private boolean useSpring = false;

        public SpringProperty(FloatProperty<T> property, SpringAnimation spring) {
            super(property.getName());
            this.mProperty = property;
            this.mSpring = spring;
        }

        @Override
        public void setValue(T t, float v) {
            if (useSpring) mSpring.animateToFinalPosition(v);
            else mProperty.setValue(t, v);
        }

        @Override
        public Float getValue(T t) {
            return mProperty.getValue(t);
        }

        public void switchToSpring() { useSpring = true; }
    }

    // ──── 简化的 Interpolators ────────────────────────────────

    public static final class Interpolators {
        public static final Interpolator LINEAR = input -> input;
        private Interpolators() {}
    }
}