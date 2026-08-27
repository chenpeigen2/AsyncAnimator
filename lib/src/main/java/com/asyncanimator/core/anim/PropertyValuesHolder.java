package com.asyncanimator.core.anim;

/**
 * PropertyValuesHolder — 简化版的"属性值持有器"。
 *
 * <p>对应 Android 平台 {@code android.animation.PropertyValuesHolder}。
 * 真实版本包含 keyframes / TypeEvaluator / setter 反射等复杂逻辑，本实现只保留
 * "每帧计算当前值"的核心：{@link #calculateValue(float)}。
 *
 * <p>分析文档 §4 ValueAnimator.animateValue 的第③步调用此方法。
 */
public abstract class PropertyValuesHolder {

    protected final String propertyName;

    protected PropertyValuesHolder(String propertyName) {
        this.propertyName = propertyName;
    }

    /**
     * 用 interpolated fraction 计算当前属性值。
     * @param fraction 经插值器映射后的最终 fraction [0,1]
     */
    public abstract void calculateValue(float fraction);

    public String getPropertyName() {
        return propertyName;
    }

    /** Float 版本的简单实现：线性 lerp fromValue→toValue。 */
    public static class FloatPropertyValuesHolder extends PropertyValuesHolder {
        private final float fromValue;
        private final float toValue;

        public FloatPropertyValuesHolder(String name, float from, float to) {
            super(name);
            this.fromValue = from;
            this.toValue = to;
        }

        @Override
        public void calculateValue(float fraction) {
            // 简化版：子类可以 override 实现 keyframes
            setInternalValue(fromValue + (toValue - fromValue) * fraction);
        }

        // 简化：直接写值到内部字段
        protected float internalValue;

        public float getValue() {
            return internalValue;
        }

        protected void setInternalValue(float v) {
            this.internalValue = v;
        }
    }
}