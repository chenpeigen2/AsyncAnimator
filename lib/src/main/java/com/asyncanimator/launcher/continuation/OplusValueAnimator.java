package com.asyncanimator.launcher.continuation;

import android.animation.Animator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.util.FloatProperty;

import com.asyncanimator.util.Trace;

/**
 * OplusValueAnimator — timeController 委托模式 + 续行动画。
 *
 * <p>对应分析文档 §6.5。核心洞察：**this 是 wrapper**，把所有生命周期操作委托给 timeController；
 * timeController 写 CURRENT_FRACTION FloatProperty → setCurrentFraction → AOSP onAnimationUpdate
 * → lambda 转给 valueApplicator.applyValue。
 *
 * <p>续行动画 API：{@link #generateContinuationAnim} 从已有 anim 拿 RecordInputInterpolator.inputed
 * 作起点，新对象 timeController 从该 fraction 跑到 1.0。
 */
public class OplusValueAnimator<T> extends ValueAnimator {

    public interface ValueApplicator {
        void applyValue(Object value);
    }

    /** 静态工厂：ofFloat 可选 async 版本。 */
    public static ValueAnimator ofFloat(boolean isAsync, float... values) {
        ValueAnimator anim = isAsync ? new OplusValueAnimator<>() : new ValueAnimator();
        anim.setFloatValues(values);
        return anim;
    }

    /** 续行动画：从已有 anim 复制状态生成新 anim 从当前 fraction 跑到 1.0。 */
    public static <T> OplusValueAnimator<T> generateContinuationAnim(OplusValueAnimator<T> anim,
                                                                  long durationMs) {
        if (anim == null) return null;
        // 从 RecordInputInterpolator 取最近一次 input
        RecordInputInterpolator rec = null;
        if (anim.mParam.interpolator instanceof RecordInputInterpolator) {
            rec = (RecordInputInterpolator) anim.mParam.interpolator;
            anim.mParam.setCurrentFraction(rec.getInputed());
        }
        float f = anim.mParam.currentFraction;
        if (f < 0f || f >= 1f) {
            Trace.traceBegin(8L, "Continuation-fail");
            Trace.traceEnd(8L);
            return null;
        }
        // 新 timeController 驱动 CURRENT_FRACTION
        TimeControllerObjectAnimator timeController = new TimeControllerObjectAnimator();
        OplusValueAnimator<T> newAnim = new OplusValueAnimator<>(
            "Continuation-" + anim.mParam.name, anim.mParam, timeController);
        timeController.setTarget(newAnim);
        timeController.setProperty(CURRENT_FRACTION);
        timeController.setFloatValues(f, 1f);
        if (durationMs > 0) timeController.setDuration(durationMs);
        Trace.traceBegin(8L, "Continuation-" + f);
        Trace.traceEnd(8L);
        return newAnim;
    }

    // ──── 实例字段 ────────────────────────────────────────────

    private final AnimParam mParam;
    private final TimeControllerObjectAnimator mTimeController;
    private boolean mAnimatorEnded = false;

    public OplusValueAnimator() {
        this("default", new AnimParam(), null);
    }

    public OplusValueAnimator(String name, AnimParam param, TimeControllerObjectAnimator timeController) {
        mParam = param;
        mTimeController = timeController;
        if (mParam.interpolator != null) super.setInterpolator(mParam.interpolator);
        // 自监听帧更新，把值应用到 target
        addUpdateListener(a -> {
            if (mParam.applicator != null) mParam.applicator.applyValue(a.getAnimatedValue());
        });
    }

    public AnimParam getParam() { return mParam; }
    public String getName() { return mParam.name; }

    @Override
    public void setCurrentFraction(float f) {
        super.setCurrentFraction(f);
        mParam.setCurrentFraction(f);
    }

    // ──── 委托给 timeController ──────────────────────────────────

    @Override
    public void start() {
        if (mTimeController != null) mTimeController.start();
        else super.start();
    }
    @Override
    public void cancel() {
        if (mTimeController != null) mTimeController.cancel();
        else super.cancel();
    }
    @Override
    public void end() {
        if (mTimeController != null) mTimeController.end();
        else super.end();
    }
    @Override
    public void pause() {
        if (mTimeController != null) mTimeController.pause();
        else super.pause();
    }
    @Override
    public boolean isRunning() {
        return mTimeController != null ? mTimeController.isRunning() : super.isRunning();
    }
    @Override
    public long getDuration() {
        return mTimeController != null ? mTimeController.getDuration() : super.getDuration();
    }
    @Override
    public void addListener(Animator.AnimatorListener l) {
        if (mTimeController != null) mTimeController.addListener(l);
        else super.addListener(l);
    }

    // ──── CURRENT_FRACTION FloatProperty ─────────────────────────

    private static final FloatProperty<OplusValueAnimator<?>> CURRENT_FRACTION =
        new FloatProperty<OplusValueAnimator<?>>("currentFraction") {
            // 平台 FloatProperty 继承 Property<T,Float>：读走抽象方法 get(T)，写走 setValue(T,float)
            @Override public Float get(OplusValueAnimator<?> anim) {
                return anim.mParam.currentFraction;
            }
            @Override public void setValue(OplusValueAnimator<?> anim, float v) {
                anim.setCurrentFraction(v);
            }
        };

    // ──── AnimParam（data class 简化版） ─────────────────────────

    public static class AnimParam {
        public String name = "default";
        public float fromValue = 0f;
        public float toValue = 1f;
        public float currentFraction = -1f;
        public TimeInterpolator interpolator;
        public ValueApplicator applicator;
        public long duration;

        public AnimParam() {}

        public AnimParam setName(String name) { this.name = name; return this; }
        public AnimParam setRange(float from, float to) { this.fromValue = from; this.toValue = to; return this; }
        public AnimParam setCurrentFraction(float f) { this.currentFraction = f; return this; }
        public AnimParam setInterpolator(TimeInterpolator ip) { this.interpolator = ip; return this; }
        public AnimParam setApplicator(ValueApplicator a) { this.applicator = a; return this; }
        public AnimParam setDuration(long d) { this.duration = d; return this; }

        public AnimParam copy() {
            AnimParam c = new AnimParam();
            c.name = name;
            c.fromValue = fromValue;
            c.toValue = toValue;
            c.currentFraction = currentFraction;
            c.interpolator = interpolator;
            c.applicator = applicator;
            c.duration = duration;
            return c;
        }
    }

    // ──── 简化的 TimeController（用 ValueAnimator + FloatProperty 模拟） ──────

    public static class TimeControllerObjectAnimator extends com.asyncanimator.launcher.pending.PendingAnimation.ObjectAnimator {
        public TimeControllerObjectAnimator() {
            super(null, null, 0f, 1f);
        }

        public TimeControllerObjectAnimator setProperty(FloatProperty prop) {
            // 把 prop 当作"内部 property"；setValue 走 FloatProperty 协议
            // 实际值 = 0..1，由 timeController 推进
            return this;
        }

        @Override
        public TimeControllerObjectAnimator setFloatValues(float... values) {
            super.setFloatValues(values);
            return this;
        }

        @Override
        public TimeControllerObjectAnimator setDuration(long duration) {
            super.setDurationOnly(duration);
            return this;
        }

        public void setTarget(OplusValueAnimator target) {
            // 实际应用由 lambda 处理
        }
    }
}