package com.asyncanimator.control

/**
 * 可供 Kotlin 和 Java 以单方法接口使用的状态监听契约。
 * 监听器接收非空的旧/新状态及可空任务上下文；实例身份和生命周期由注册方管理。
 */
fun interface OnAnimStateChangeListener {

    /**
     * 接收控制器发布的一次状态变化通知。
     * @param oldState 变化前的业务状态，不保证与 newState 不同。
     * @param newState 已更新后的业务状态；通知不是另一次状态写入请求。
     * @param runningTask 控制器当前保存的任务上下文，可为 null；监听器不取得其所有权。
     * 调用线程与异常策略由控制器决定；注销监听时应保留并传回原注册实例。
     */
    fun onAnimStateChanged(oldState: AnimationState, newState: AnimationState, runningTask: Any?)
}
