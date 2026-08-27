package com.asyncanimator.dyn.anim;

/**
 * SpringForce — 阻尼弹簧力。
 *
 * <p>对应 Android 平台 {@code androidx.dynamicanimation.animation.SpringForce}（简化版）和分析文档 §3.5。
 *
 * <p>物理方程：{@code m·a + c·v + k·(x - x_target) = 0}，设 m=1 归一化后：
 * <pre>
 * a = -ω²·(x - x_target) - 2·ζ·ω·v
 *   其中 ω = √k，ζ = dampingRatio
 * </pre>
 *
 * <p>三种 damping regime 的闭式解析解（{@link #updateValues}）：
 * <ul>
 *   <li>ζ > 1（过阻尼）：双指数衰减</li>
 *   <li>ζ = 1（临界阻尼）：{@code (1 + ωt)·e^(-ωt)}</li>
 *   <li>0 ≤ ζ < 1（欠阻尼）：{@code e^(-ζωt)·(sin·cos)}</li>
 * </ul>
 */
public class SpringForce {

    public static final float DAMPING_RATIO_HIGH_BOUNCY = 0.2f;
    public static final float DAMPING_RATIO_MEDIUM_BOUNCY = 0.5f;
    public static final float DAMPING_RATIO_LOW_BOUNCY = 0.75f;
    public static final float DAMPING_RATIO_NO_BOUNCY = 1.0f;

    public static final float STIFFNESS_HIGH = 10_000f;
    public static final float STIFFNESS_MEDIUM = 1_500f;
    public static final float STIFFNESS_LOW = 200f;
    public static final float STIFFNESS_VERY_LOW = 50f;

    private double mNaturalFreq;        // ω
    double mDampingRatio;               // ζ
    private double mFinalPosition;       // x_target
    private double mGammaPlus;
    private double mGammaMinus;
    private double mDampedFreq;
    private boolean mInitialized = false;

    private double mValueThreshold = 0.5d;
    private double mVelocityThreshold = 0.5d * 62.5d;

    public SpringForce() {
        this(STIFFNESS_MEDIUM, DAMPING_RATIO_MEDIUM_BOUNCY);
    }

    public SpringForce(float finalPosition) {
        this(STIFFNESS_MEDIUM, DAMPING_RATIO_MEDIUM_BOUNCY, finalPosition);
    }

    public SpringForce(float stiffness, float dampingRatio) {
        setStiffness(stiffness);
        setDampingRatio(dampingRatio);
    }

    public SpringForce(float stiffness, float dampingRatio, float finalPosition) {
        setStiffness(stiffness);
        setDampingRatio(dampingRatio);
        setFinalPosition(finalPosition);
    }

    public SpringForce setStiffness(float stiffness) {
        if (stiffness <= 0) throw new IllegalArgumentException("stiffness must be > 0");
        mNaturalFreq = Math.sqrt(stiffness);
        mInitialized = false;
        return this;
    }

    public SpringForce setDampingRatio(float dampingRatio) {
        if (dampingRatio < 0) throw new IllegalArgumentException("dampingRatio must be >= 0");
        mDampingRatio = dampingRatio;
        mInitialized = false;
        return this;
    }

    public SpringForce setFinalPosition(float finalPosition) {
        mFinalPosition = finalPosition;
        mInitialized = false;
        return this;
    }

    public float getFinalPosition() {
        return (float) mFinalPosition;
    }

    public SpringForce setValueThreshold(float threshold) {
        if (threshold < 0) throw new IllegalArgumentException("threshold must be >= 0");
        mValueThreshold = threshold;
        mVelocityThreshold = threshold * 62.5d;
        return this;
    }

    /**
     * F = ma 在 mass=1 下的加速度。
     * 对应分析文档 §3.5.1 getAcceleration。
     */
    public float getAcceleration(float value, float velocity) {
        double displacement = value - mFinalPosition;
        double omega = mNaturalFreq;
        double zeta = mDampingRatio;
        return (float) (-(omega * omega) * displacement - ((omega * 2.0 * zeta) * velocity));
    }

    public boolean isAtEquilibrium(float value, float velocity) {
        return Math.abs(velocity) < mVelocityThreshold
            && Math.abs(value - mFinalPosition) < mValueThreshold;
    }

    /** 系数预算：第一次 updateValues 时算 √ 项缓存。 */
    private void init() {
        if (mInitialized) return;
        if (mDampingRatio > 1.0d) {
            double z = mDampingRatio;
            double oz = z * mNaturalFreq;
            double oz2 = z * z * mNaturalFreq * mNaturalFreq;
            mGammaPlus = -oz + Math.sqrt(oz2 - mNaturalFreq * mNaturalFreq);
            mGammaMinus = -oz - Math.sqrt(oz2 - mNaturalFreq * mNaturalFreq);
        } else if (mDampingRatio == 1.0d || mDampingRatio == 0.0d) {
            mDampedFreq = mNaturalFreq;
        } else {
            mDampedFreq = Math.sqrt(1.0d - mDampingRatio * mDampingRatio) * mNaturalFreq;
        }
        mInitialized = true;
    }

    /**
     * 闭式解析解：给定 (value, velocity, dt) 求（新 value, 新 velocity）。
     * 返回 MassState 对象，避免分配（用静态 cached 实例）。
     *
     * <p>对应分析文档 §3.5.4 updateValues。
     */
    public MassState updateValues(double value, double velocity, long dtMs) {
        init();
        double dt = dtMs / 1000.0d;
        double disp = value - mFinalPosition;
        double zeta = mDampingRatio;

        double newDisp, newVel;
        if (zeta > 1.0d) {
            // 过阻尼：双指数
            double c1 = disp - ((mGammaMinus * disp - velocity) / (mGammaMinus - mGammaPlus));
            double c2 = ((mGammaMinus * disp - velocity) / (mGammaMinus - mGammaPlus));
            double decayPlus = Math.exp(mGammaPlus * dt);
            double decayMinus = Math.exp(mGammaMinus * dt);
            newDisp = decayPlus * c2 + decayMinus * c1;
            newVel = decayPlus * c2 * mGammaPlus + decayMinus * c1 * mGammaMinus;
        } else if (zeta == 1.0d) {
            // 临界阻尼：(1 + ωt)·e^(-ωt)
            double c = mNaturalFreq * disp + velocity;
            double decay = Math.exp(-mNaturalFreq * dt);
            newDisp = (disp + c * dt) * decay;
            newVel = (c * decay) - (mNaturalFreq * (disp + c * dt) * decay);
        } else if (zeta == 0.0d) {
            // 无阻尼：纯正弦
            newDisp = disp * Math.cos(mNaturalFreq * dt) + velocity / mNaturalFreq * Math.sin(mNaturalFreq * dt);
            newVel = -disp * mNaturalFreq * Math.sin(mNaturalFreq * dt) + velocity * Math.cos(mNaturalFreq * dt);
        } else {
            // 欠阻尼：e^(-ζωt)·(sin·ωd·t + cos)
            double oz = zeta * mNaturalFreq;
            double c = (oz * disp + velocity) / mDampedFreq;
            double decay = Math.exp(-oz * dt);
            double cosDt = Math.cos(mDampedFreq * dt);
            double sinDt = Math.sin(mDampedFreq * dt);
            newDisp = (sinDt * c + cosDt * disp) * decay;
            newVel = (-oz * newDisp * c + (cosDt * c * mDampedFreq + sinDt * (-mDampedFreq) * disp)) * decay;
        }

        MassState state = new MassState();
        state.mValue = (float) (newDisp + mFinalPosition);
        state.mVelocity = (float) newVel;
        return state;
    }

    /**
     * 力学状态快照（避免 GC）。
     */
    public static class MassState {
        public float mValue;
        public float mVelocity;
    }
}