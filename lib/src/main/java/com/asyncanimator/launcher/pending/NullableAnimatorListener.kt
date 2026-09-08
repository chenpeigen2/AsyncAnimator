package com.asyncanimator.launcher.pending

import android.animation.Animator

/**
 * NullableAnimatorListener — Animator listener 接口（历史上演进自"参数可空"）。
 *
 * 对应分析文档 §6.3.4。换平台 `android.animation.Animator` 后参数为 `@NonNull`，
 * 现派发真实 animator，可空容忍仅作历史注释保留。
 */
interface NullableAnimatorListener {
    fun onAnimationCancel(animator: Animator) {}
    fun onAnimationEnd(animator: Animator) {}
    fun onAnimationStart(animator: Animator) {}
}
