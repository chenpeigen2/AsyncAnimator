package com.asyncanimator.playback

import android.animation.TimeInterpolator

/**
 * 预定义插值器集合。
 * 对应原 OPPO 代码 `com.android.launcher3.anim.Interpolators`（简化版）。
 */
internal object Interpolators {

    val LINEAR = TimeInterpolator { input -> input }}
