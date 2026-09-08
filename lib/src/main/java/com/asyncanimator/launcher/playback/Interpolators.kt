package com.asyncanimator.launcher.playback

import android.animation.TimeInterpolator

/**
 * 预定义插值器集合。
 * 对应原 OPPO 代码 `com.android.launcher3.anim.Interpolators`（简化版）。
 */
internal object Interpolators {

    val LINEAR = TimeInterpolator { input -> input }

    val DECELERATE = TimeInterpolator { input ->
        val t = input + 1.0f
        t * t * ((1.7f + 1.0f) * t - 1.7f) / 2.0f
    }
}
