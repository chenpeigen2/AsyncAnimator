package com.asyncanimator.launcher.playback;

import android.util.FloatProperty;

/**
 * PropertySetter — "业务想要 set 什么属性"的契约接口。
 *
 * <p>对应原 OPPO 代码 {@code com.android.launcher3.anim.PropertySetter}。
 * PendingAnimation 实现这个接口（每个 setFloat/setViewAlpha 调用包成 ObjectAnimator）；
 * NO_ANIM_PROPERTY_SETTER 是 no-op 实现（直接 setValue，不动画）。
 */
public interface PropertySetter {

    /** no-op 版本：直接 setValue，不构造 ObjectAnimator。 */
    PropertySetter NO_ANIM_PROPERTY_SETTER = new PropertySetter() {
        @Override public <T> void setFloat(T target, FloatProperty<T> property, float value) {
            property.setValue(target, value);
        }
    };

    <T> void setFloat(T target, FloatProperty<T> property, float value);

    default <T> PropertySetter setFloat(T target, FloatProperty<T> property, float value,
                                       android.animation.TimeInterpolator interpolator) {
        setFloat(target, property, value);
        return this;
    }
}