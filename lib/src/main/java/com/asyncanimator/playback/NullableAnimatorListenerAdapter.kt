package com.asyncanimator.playback

import android.animation.Animator
import android.animation.AnimatorListenerAdapter

/**
 * 同时适配平台监听器与本库生命周期接口的基类，回调接收非空动画实例。
 * 提供粘性的取消标记和供内部派发器写入的动画标识；本类不负责线程切换。
 */
open class NullableAnimatorListenerAdapter : AnimatorListenerAdapter(), NullableAnimatorListener {

    /**
     * 是否曾收到取消通知；开始和结束的默认实现均不会复位该标志。
     */
    protected var cancelled = false

    /**
     * 内部派发器设置的诊断动画标识，初始为 -1；本字段本身不分配序号或控制动画播放。
     */
    internal var animationId = -1

    /**
     * 记录本监听器已经收到取消事件，将 cancelled 置为 true。
     * 标记会保留到实例后续使用中；此方法不触发结束通知，也不清除 animationId。
     */
    override fun onAnimationCancel(animator: Animator) {
        cancelled = true
    }

    /**
     * 默认忽略逻辑结束事件，供子类选择实现业务收尾。
     * 不会清除取消标记、合成成功事件或派发实际结束事件。
     */
    override fun onAnimationEnd(animator: Animator) {}

    /**
     * 默认忽略开始事件，不把实例已有的取消标记重置为 false。
     * 需要按每轮重新初始化状态的子类必须显式实现自己的开始处理。
     */
    override fun onAnimationStart(animator: Animator) {}
}
