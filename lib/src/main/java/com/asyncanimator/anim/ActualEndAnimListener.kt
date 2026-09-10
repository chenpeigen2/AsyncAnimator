package com.asyncanimator.anim

import android.animation.Animator
import com.asyncanimator.playback.NullableAnimatorListenerAdapter

/**
 * 在普通动画监听器上增加实际结束通知入口，用于区分业务逻辑结束与底层驱动完成。
 * 只有派发方显式调用该入口才会收到事件；普通 onAnimationEnd 不会自动转换成实际结束。
 * 经 AsyncAnimCallbacks 派发时通知回到主线程，直接调用时仍由调用方决定线程。
 */
open class ActualEndAnimListener : NullableAnimatorListenerAdapter() {

    /**
     * 接收外部明确报告的实际结束事件；默认实现为空，子类按需执行收尾。
     * @param animator 此次事件对应的非空动画实例。
     * 不会自动触发逻辑结束或取消，也不会自行确认底层帧循环已停止；派发方负责信号真实性和调用线程。
     */
    open fun onAnimActualEnd(animator: Animator) {}
}
