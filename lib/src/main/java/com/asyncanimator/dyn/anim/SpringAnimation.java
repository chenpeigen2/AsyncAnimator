package com.asyncanimator.dyn.anim;

/**
 * SpringAnimation — 阻尼弹簧动画。
 *
 * <p>对应 Android 平台 {@code androidx.dynamicanimation.animation.SpringAnimation}（简化版）
 * 和分析文档 §3.4 / §6.4.3。
 *
 * <p>核心算法（{@link #updateValueAndVelocity}）：
 * <ul>
 *   <li>mEndRequested=true（skipToEnd）：直接跳到 finalPosition，返回 true</li>
 *   <li>mPendingPosition ≠ UNSET（动画中改目标）：**2 阶段过渡**，dt 拆半</li>
 *   <li>正常：单次 updateValues 物理积分</li>
 *   <li>clamp 到 [min, max]，isAtEquilibrium 判定结束</li>
 * </ul>
 */
public class SpringAnimation extends DynamicAnimation<SpringAnimation> {

    public static final float UNSET = Float.MAX_VALUE;

    private SpringForce mSpring;
    private float mPendingPosition = UNSET;
    private boolean mEndRequested = false;

    public SpringAnimation(Object target) {
        super(target);
        mSpring = null;
    }

    public SpringAnimation(Object target, float finalPosition) {
        super(target);
        mSpring = new SpringForce(finalPosition);
    }

    public SpringAnimation setSpring(SpringForce spring) {
        mSpring = spring;
        return this;
    }

    public SpringForce getSpring() {
        return mSpring;
    }

    @Override
    public SpringAnimation animateToFinalPosition(float finalPosition) {
        if (isRunning()) {
            mPendingPosition = finalPosition;  // 推迟到下一帧处理
            return this;
        }
        if (mSpring == null) mSpring = new SpringForce(finalPosition);
        else mSpring.setFinalPosition(finalPosition);
        return this;
    }

    public boolean canSkipToEnd() {
        return mSpring != null && mSpring.mDampingRatio > 0.0d;
    }

    public void skipToEnd() {
        if (!canSkipToEnd()) {
            throw new UnsupportedOperationException(
                "Spring animations can only come to an end when there is damping");
        }
        if (mRunning) mEndRequested = true;
    }

    @Override
    public void cancel() {
        super.cancel();
        if (mPendingPosition != UNSET) {
            if (mSpring == null) mSpring = new SpringForce(mPendingPosition);
            else mSpring.setFinalPosition(mPendingPosition);
            mPendingPosition = UNSET;
        }
    }

    @Override
    protected void startAnimationInternal() {
        sanityCheck();
        if (mSpring != null) mSpring.setValueThreshold(getValueThreshold());
        super.startAnimationInternal();
    }

    @Override
    protected void sanityCheck() {
        if (mSpring == null) {
            throw new UnsupportedOperationException(
                "Incomplete SpringAnimation: Either final position or a spring force needs to be set.");
        }
        float fp = mSpring.getFinalPosition();
        if (fp > mMaxValue || fp < mMinValue) {
            throw new UnsupportedOperationException(
                "Final position of the spring must be in [min, max]");
        }
    }

    @Override
    public float getAcceleration(float value, float velocity) {
        return mSpring == null ? 0f : mSpring.getAcceleration(value, velocity);
    }

    @Override
    public boolean isAtEquilibrium(float value, float velocity) {
        return mSpring != null && mSpring.isAtEquilibrium(value, velocity);
    }

    /**
     * 物理积分 + 2 阶段过渡。
     * 对应分析文档 §3.4.2。
     */
    @Override
    public boolean updateValueAndVelocity(long dt) {
        // ① skipToEnd 路径
        if (mEndRequested) {
            if (mPendingPosition != UNSET) {
                mSpring.setFinalPosition(mPendingPosition);
                mPendingPosition = UNSET;
            }
            mValue = mSpring.getFinalPosition();
            mVelocity = 0f;
            mEndRequested = false;
            return true;
        }

        // ② 动画中改 finalPosition：2 阶段过渡（Half-step transition）
        if (mPendingPosition != UNSET) {
            long halfDt = dt / 2;
            // 第一阶段：用旧 finalPosition 跑 dt/2
            SpringForce.MassState s1 = mSpring.updateValues(mValue, mVelocity, halfDt);
            // 切目标
            mSpring.setFinalPosition(mPendingPosition);
            mPendingPosition = UNSET;
            // 第二阶段：用新 finalPosition 跑另 dt/2
            SpringForce.MassState s2 = mSpring.updateValues(s1.mValue, s1.mVelocity, halfDt);
            mValue = s2.mValue;
            mVelocity = s2.mVelocity;
        } else {
            // ③ 正常 dt 积分
            SpringForce.MassState s = mSpring.updateValues(mValue, mVelocity, dt);
            mValue = s.mValue;
            mVelocity = s.mVelocity;
        }

        // ④ 平衡判定
        if (!isAtEquilibrium(mValue, mVelocity)) return false;
        mValue = mSpring.getFinalPosition();
        mVelocity = 0f;
        return true;
    }
}