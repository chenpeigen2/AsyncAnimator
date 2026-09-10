package com.asyncanimator.thread

import com.asyncanimator.api.PublicApi

/**
 * 提供主线程与独立动画线程转发入口的基础封装。
 * 子类自行决定哪些操作需要转发；本类只安排任务，不安装具体动画引擎的帧源或接管 View 写入。
 */
@PublicApi
open class AsyncAnimWrapper {

    /**
     * 把非空任务交给独立动画线程执行器；已在该线程时内联执行，否则排队。
     * 首次非空调用可能启动共享动画线程；null 不访问执行器，也不触发初始化。
     */
    protected fun runOnAnimThread(task: (() -> Unit)?) {
        if (task != null) Executors.ANIM_CONTROL_EXECUTOR.execute(task)
    }

    /**
     * 把非空任务交给主线程执行器；在主线程内联执行，其他线程按普通消息排队。
     * null 直接忽略；本方法不等待任务完成，也不会自动改变任务内部 View 或动画对象的线程约束。
     */
    protected fun runOnMainThread(task: (() -> Unit)?) {
        if (task != null) Executors.MAIN_EXECUTOR.execute(task)
    }
}
