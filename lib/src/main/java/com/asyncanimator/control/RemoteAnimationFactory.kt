package com.asyncanimator.control

import android.animation.AnimatorSet

/**
 * 宿主动画工厂及转场登记的身份对象，不提供远程通信或系统窗口事务协议。
 * 控制器只记录实例的开始和结束；工厂方法的调用时机、播放与资源释放由宿主负责。
 */
interface RemoteAnimationFactory {

    /**
     * 由宿主主动创建一次转场使用的非空 AnimatorSet。
     * 控制器登记本工厂时不会调用此方法；实例复用、配置、启动和取消均由宿主负责。
     */
    fun createAnimation(): AnimatorSet

    /**
     * 由宿主显式通知工厂所属转场已完成，具体资源释放行为由实现决定。
     * 控制器移除或销毁本地登记记录不会自动调用此方法，也不保证通知仅发生一次。
     */
    fun onAnimationFinished()
}
