package com.asyncanimator.playback

import android.animation.TimeInterpolator
import android.util.FloatProperty

/**
 * 统一表达浮点属性设置请求的接口，可由即时写入或动画组装实现消费。
 * 默认实现直接设置属性，不是吞掉请求；业务代码无需为了是否动画而切换属性描述方式。
 */
internal interface PropertySetter {

    /**
     * 立即调用给定 FloatProperty 写入目标值；属性为 null 时无操作。
     * 不读取旧值、不创建动画、不求值 interpolator；属性实现抛出的异常原样传播。
     * @param target 被写入属性的对象，由调用方保证其线程归属。
     * @param property 实际属性访问器，可为空以跳过设置。
     * @param value 写入值，不进行有限性或范围校验。
     * @param interpolator 保留给动画型实现使用的曲线参数，默认即时实现不会调用它。
     */
    fun <T> setFloat(target: T, property: FloatProperty<T>?, value: Float,
                     interpolator: TimeInterpolator) {
        property?.setValue(target, value)
    }

    companion object {

        /**
         * 使用接口默认即时写入行为的共享实例；不创建动画，也不保留目标或插值器引用。
         */
        val NO_ANIM_PROPERTY_SETTER: PropertySetter = object : PropertySetter {}
    }
}
