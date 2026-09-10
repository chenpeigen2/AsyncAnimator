package com.asyncanimator.control

import android.animation.AnimatorSet

/**
 * RemoteAnimationFactory — 本地演示的动画工厂/生命周期身份。
 *
 * 与 OPPO LauncherAnimationRunner.RemoteAnimationFactory 不是源码/二进制兼容接口。
 * Controller 只用实例记录启动/结束，不自动调用 createAnimation/onAnimationFinished；
 * 创建、播放、取消与完成通知均由宿主负责。无 Binder、merge 或远程 ready 回调协议。
 */
interface RemoteAnimationFactory {

    /** 创建一次转场需要的 AnimatorSet（demo 实现）。 */
    fun createAnimation(): AnimatorSet

    /** 动画结束回调（demo 自定义触发）。 */
    fun onAnimationFinished()
}
