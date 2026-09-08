package com.asyncanimator.launcher.playback

import android.animation.TimeInterpolator
import android.util.FloatProperty

/**
 * PropertySetter — "业务想要 set 什么属性"的契约接口。
 *
 * 对应原 OPPO 代码 `com.android.launcher3.anim.PropertySetter`。
 * PendingAnimation 实现这个接口（每个 setFloat/setViewAlpha 调用包成 ObjectAnimator）；
 * NO_ANIM_PROPERTY_SETTER 是 no-op 实现（直接 setValue，不动画）。
 */
internal interface PropertySetter {

    fun <T> setFloat(target: T, property: FloatProperty<T>, value: Float)

    fun <T> setFloat(target: T, property: FloatProperty<T>, value: Float,
                     interpolator: TimeInterpolator): PropertySetter {
        setFloat(target, property, value)
        return this
    }

    companion object {

        /** no-op 版本：直接 setValue，不构造 ObjectAnimator。 */
        val NO_ANIM_PROPERTY_SETTER: PropertySetter = object : PropertySetter {
            override fun <T> setFloat(target: T, property: FloatProperty<T>, value: Float) {
                property.setValue(target, value)
            }
        }
    }
}
