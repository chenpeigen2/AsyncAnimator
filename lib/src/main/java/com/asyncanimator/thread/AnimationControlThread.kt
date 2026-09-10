package com.asyncanimator.thread

import android.os.HandlerThread
import android.os.Process
import com.asyncanimator.core.AnimationHandler
import com.asyncanimator.core.ChoreographerTickScheduler

/**
 * 进程共享的独立动画 HandlerThread，私有构造时即启动线程。
 * 普通任务在调度器准备后运行；构造优先级为 -19，但不保证设备上的调度结果或帧性能。
 * 只安装本库的线程内帧源，不替换平台或 AndroidX 动画内部调度，View 操作仍须遵守主线程约束。
 */
class AnimationControlThread private constructor() : HandlerThread(THREAD_NAME, PRIORITY) {

    /**
     * 构造完成必要字段后立即启动 HandlerThread；调用方通过共享执行器投递初始化后的业务任务。
     */
    init {
        start()
    }

    /**
     * 在本线程 Looper 已准备、消息循环尚未开始时，为线程内动画处理器安装 Choreographer 帧源。
     * 安装失败直接传播，不能静默换源；帧源直到首次请求帧才绑定 Choreographer。
     * 随后尽力重设当前线程优先级，失败只记警告；这无法捕获 HandlerThread 更早阶段的初始化异常。
     */
    override fun onLooperPrepared() {
        AnimationHandler.installThreadScheduler(ChoreographerTickScheduler())

        runCatching { Process.setThreadPriority(Process.myTid(), PRIORITY) }
            .onFailure { android.util.Log.w(THREAD_NAME, "setThreadPriority($PRIORITY) failed: ${it.message}") }
    }

    companion object {

        /**
         * 动画线程的固定名称，用于线程识别、日志和调试，不代表额外系统调度能力。
         */
        const val THREAD_NAME = "launcher.anim"

        /**
         * 请求的 Android 线程优先级 -19，与 THREAD_PRIORITY_URGENT_AUDIO 数值相同；不是实际性能承诺。
         */
        private const val PRIORITY = -19

        /**
         * 按同步惰性方式创建并常驻的唯一线程实例；仅读取常量不会触发启动。
         * 构造具有启动线程的副作用，不宜改成允许多次初始化的惰性发布方式。
         */
        internal val instance: AnimationControlThread by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            AnimationControlThread()
        }
    }
}
