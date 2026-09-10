package com.asyncanimator.playback

import android.animation.Animator
import com.asyncanimator.anim.ActualEndAnimListener

/**
 * 把未取消的逻辑结束转换为成功通知的监听基类，并保留实际结束入口。
 * 父类取消标记不会因再次开始自动复位，因此监听器复用需明确管理状态。
 * 成功仅表示未取消，不代表达到特定进度阈值，也不自动发出实际结束事件。
 */
internal abstract class AnimationSuccessListener : ActualEndAnimListener() {

    /**
     * 调用父类记录取消状态，并保持本监听器的取消标志为 true。
     * 只改变成功判断条件，不在取消时触发 onAnimationSuccess 或实际结束通知。
     */
    override fun onAnimationCancel(animator: Animator) {
        super.onAnimationCancel(animator)
        cancelled = true
    }

    /**
     * 在尚未收到取消通知时，将本次逻辑结束交给 onAnimationSuccess。
     * 取消标志为 true 时直接返回；本方法不做一次性消费，也不使用播放进度判定成功。
     */
    override fun onAnimationEnd(animator: Animator) {
        if (cancelled) return
        onAnimationSuccess(animator)
    }

    /**
     * 处理未取消的逻辑结束，由具体子类定义成功后的业务行为。
     * @param animator 对应的非空动画实例；可能被重复通知，若要求仅执行一次，子类需自行消费回调。
     */
    abstract fun onAnimationSuccess(animator: Animator)
}
