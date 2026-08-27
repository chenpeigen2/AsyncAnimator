package com.asyncanimator.util;

/**
 * FloatProperty — 浮点属性抽象类。
 *
 * <p>对应 Android 平台 {@code android.util.FloatProperty}（简化版）。
 * 让 ObjectAnimator 等可以按名称+ setter 访问对象的某个 float 属性。
 */
public abstract class FloatProperty<T> {

    private final String mName;

    protected FloatProperty(String name) {
        mName = name;
    }

    public abstract void setValue(T object, float value);

    public abstract Float getValue(T object);

    public String getName() {
        return mName;
    }
}