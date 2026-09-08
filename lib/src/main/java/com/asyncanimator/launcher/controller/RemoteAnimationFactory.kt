package com.asyncanimator.launcher.controller

import android.animation.AnimatorSet

/**
 * RemoteAnimationFactory — 远程动画工厂接口（demo 模块实现）。
 *
 * 对应原 OPPO 代码 `com.android.launcher3.LauncherAnimationRunner.RemoteAnimationFactory`。
 */
interface RemoteAnimationFactory {

    /** 创建一次转场需要的 AnimatorSet（demo 实现）。 */
    fun createAnimation(): AnimatorSet

    /** 动画结束回调（demo 自定义触发）。 */
    fun onAnimationFinished()
}
