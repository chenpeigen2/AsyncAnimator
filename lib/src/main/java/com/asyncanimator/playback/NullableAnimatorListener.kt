package com.asyncanimator.playback

import android.animation.Animator

/**
 * 提供默认空实现的动画生命周期监听契约，所有事件参数均为非空 Animator。
 * 类型名称不改变参数的空值约束；需要取消标记或线程转发时应使用对应适配器或派发器。
 */
interface NullableAnimatorListener {
    /**
     * 接收指定动画的取消通知，默认不执行任何动作。
     * 参数必须非空；本接口不记录取消状态，也不自行补发结束事件，线程与顺序由派发方决定。
     */
    fun onAnimationCancel(animator: Animator) {}
    /**
     * 接收指定动画的逻辑结束通知，默认实现为空。
     * 结束不自动等同于成功或底层帧停止，调用方需要依据对应派发器及驱动的协议判断。
     */
    fun onAnimationEnd(animator: Animator) {}
    /**
     * 接收指定动画的开始通知，默认实现为空。
     * 参数为实际的非空动画实例；本接口不重置子类状态，也不自动安排线程切换。
     */
    fun onAnimationStart(animator: Animator) {}
}
