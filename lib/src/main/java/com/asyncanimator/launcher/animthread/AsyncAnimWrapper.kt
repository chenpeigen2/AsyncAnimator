package com.asyncanimator.launcher.animthread

import com.asyncanimator.launcher.async.Executors

/**
 * AsyncAnimWrapper - 线程切换骨架基类（对齐原厂 com/android/launcher3/anim/AsyncAnimWrapper）。
 *
 * 提供 runOnAnimThread / runOnMainThread 两个 helper，供子类（如
 * com.asyncanimator.launcher.async.AsyncSpringAnim）继承后在 start/cancel/end 等方法里
 * 按 viewSupportAnimThread 决定"投到独立动画线程"还是"留在当前线程"。
 *
 * 原厂：
 * ```
 * public final void runOnAnimThread(Runnable task) { OplusExecutors.ANIM_EXECUTOR.execute(task); }
 * public final void runOnMainThread(Runnable task) { Executors.MAIN_EXECUTOR.execute(task); }
 * ```
 */
open class AsyncAnimWrapper {

    /** 投递到独立动画线程（launcher.anim）执行。 */
    protected fun runOnAnimThread(task: (() -> Unit)?) {
        if (task != null) Executors.ANIM_CONTROL_EXECUTOR.execute(task)
    }

    /** 投递回主线程执行。 */
    protected fun runOnMainThread(task: (() -> Unit)?) {
        if (task != null) Executors.MAIN_EXECUTOR.execute(task)
    }
}
