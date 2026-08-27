package com.asyncanimator.launcher.controller;

import com.asyncanimator.core.anim.AnimatorSet;
import com.asyncanimator.core.anim.ValueAnimator;

/**
 * RemoteAnimationFactory — 远程动画工厂接口（demo 模块实现）。
 *
 * <p>对应原 OPPO 代码 {@code com.android.launcher3.LauncherAnimationRunner.RemoteAnimationFactory}。
 */
public interface RemoteAnimationFactory {

    /** 创建一次转场需要的 AnimatorSet（demo 实现）。 */
    AnimatorSet createAnimation();

    /** 动画结束回调（demo 自定义触发）。 */
    void onAnimationFinished();
}