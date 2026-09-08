package com.asyncanimator.launcher.continuation

import android.animation.TimeInterpolator

/**
 * RecordInputInterpolator — 记录最近一次 input 副作用的插值器。
 *
 * 对应分析文档 §6.5.5。[getInterpolation] 时把 input 缓存到 [inputed]，
 * 续行动画时 `generateContinuationAnim` 通过 `getInputed()` 取出。
 */
class RecordInputInterpolator(private val realInterpolator: TimeInterpolator) : TimeInterpolator {

    var inputed = -1f
        private set

    override fun getInterpolation(input: Float): Float {
        this.inputed = input
        return realInterpolator.getInterpolation(input)
    }
}
