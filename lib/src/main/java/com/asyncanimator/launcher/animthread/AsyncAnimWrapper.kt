package com.asyncanimator.launcher.animthread

import com.asyncanimator.launcher.async.Executors

/**
 * AsyncAnimWrapper — 独立动画线程的调度基类。
 *
 * 1:1 还原 OPPO 源码 `com/android/launcher3/anim/AsyncAnimWrapper.java`（全类仅 20 行）：
 * ```
 * public class AsyncAnimWrapper {
 *     public final void runOnAnimThread(Runnable task) {
 *         OplusExecutors.INSTANCE.getANIM_EXECUTOR().execute(task);
 *     }
 *     public final void runOnMainThread(Runnable task) {
 *         Executors.MAIN_EXECUTOR.execute(task);
 *     }
 * }
 * ```
 *
 * 这是整套方案的**调度骨架**：所有需要跨线程的动画包装器都继承它，
 * 子类只做一件事——按 `viewSupportAnimThread` 开关决定"投到动画线程"还是"就地执行"。
 * 原厂两个子类（均在 com/android/quickstep/util/）：
 *
 *  - `OplusAsyncSpringAnimWrapper`（包 SpringAnimation）
 *  - `OplusAsyncSwipeUpSpringAnimWrapper`（包 SwipeUpSpringAnim，上滑手势）
 *
 * 两者的 start/cancel/end/animateToFinalPosition/setStartVelocity 全部是同一模式：
 * ```
 * if (viewSupportAnimThread) runOnAnimThread(() -> realAnim.xxx());
 * else                       realAnim.xxx();
 * ```
 *
 * 注意 `execute()` 的语义（见 [com.asyncanimator.launcher.async.LooperExecutor]）：
 * 当前线程已是目标线程时直接 run，否则 post —— 所以"已在动画线程上"不会多跳一帧。
 */
internal open class AsyncAnimWrapper {

    /** 投递到独立动画线程（"launcher.anim"）执行。 */
    fun runOnAnimThread(task: (() -> Unit)?) {
        if (task != null) AnimExecutors.ANIM_CONTROL_EXECUTOR.execute(task)
    }

    /** 投递回主线程执行（View 属性写入、listener 回调走这里）。 */
    fun runOnMainThread(task: (() -> Unit)?) {
        if (task != null) Executors.MAIN_EXECUTOR.execute(task)
    }
}
