package com.asyncanimator.launcher.continuation

import android.animation.TimeInterpolator

/**
 * RecordInputInterpolator — 记录最近一次 input 副作用的插值器。
 *
 * 对应 `docs/review/04-frame-spring-continuation.md`。[getInterpolation] 时把 input 缓存到 [inputed]，
 * 续行动画时 `generateContinuationAnim` 通过 `getInputed()` 取出。
 */
internal class RecordInputInterpolator(private val realInterpolator: TimeInterpolator) : TimeInterpolator {

    var inputed = 0f
        private set

    override fun getInterpolation(input: Float): Float {
        this.inputed = input
        return realInterpolator.getInterpolation(input)
    }
}
