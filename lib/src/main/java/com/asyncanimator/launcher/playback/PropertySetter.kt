package com.asyncanimator.launcher.playback

import android.animation.TimeInterpolator
import android.util.FloatProperty

/**
 * PropertySetter — "业务想要 set 什么属性"的契约接口。
 *
 * 对应原 OPPO 代码 `com.android.launcher3.anim.PropertySetter`：
 * 接口默认方法就是 no-op 实现（直接 setValue，不动画，原厂 PropertySetter.java:23-28）；
 * [NO_ANIM_PROPERTY_SETTER] 是一个不重写任何方法的空匿名类（原厂 PropertySetter.java:11-12）。
 * PendingAnimation 把 [setFloat] override 成"构造 ObjectAnimator"的动画版
 * （原厂 PendingAnimation.java:127-134），从而让同一段业务代码在"动画 vs no-op"之间无缝切换。
 */
internal interface PropertySetter {

    /** 默认 no-op：直接 setValue，不构造 ObjectAnimator。 */
    fun <T> setFloat(target: T, property: FloatProperty<T>?, value: Float,
                     interpolator: TimeInterpolator) {
        property?.setValue(target, value)
    }

    companion object {

        /** no-op 版本：不重写任何方法，全部走接口默认实现。 */
        val NO_ANIM_PROPERTY_SETTER: PropertySetter = object : PropertySetter {}
    }
}
