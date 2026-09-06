package com.asyncanimator.launcher.animthread;

import com.asyncanimator.launcher.async.Executors;

/**
 * AsyncAnimWrapper — 独立动画线程的调度基类。
 *
 * <p>1:1 还原 OPPO 源码 {@code com/android/launcher3/anim/AsyncAnimWrapper.java}（全类仅 20 行）：
 * <pre>
 *   public class AsyncAnimWrapper {
 *       public final void runOnAnimThread(Runnable task) {
 *           OplusExecutors.INSTANCE.getANIM_EXECUTOR().execute(task);
 *       }
 *       public final void runOnMainThread(Runnable task) {
 *           Executors.MAIN_EXECUTOR.execute(task);
 *       }
 *   }
 * </pre>
 *
 * <p>这是整套方案的**调度骨架**：所有需要跨线程的动画包装器都继承它，
 * 子类只做一件事——按 {@code viewSupportAnimThread} 开关决定"投到动画线程"还是"就地执行"。
 * 原厂两个子类（均在 com/android/quickstep/util/）：
 * <ul>
 *   <li>{@code OplusAsyncSpringAnimWrapper}（包 SpringAnimation）</li>
 *   <li>{@code OplusAsyncSwipeUpSpringAnimWrapper}（包 SwipeUpSpringAnim，上滑手势）</li>
 * </ul>
 * 两者的 start/cancel/end/animateToFinalPosition/setStartVelocity 全部是同一模式：
 * <pre>
 *   if (viewSupportAnimThread) runOnAnimThread(() -&gt; realAnim.xxx());
 *   else                       realAnim.xxx();
 * </pre>
 *
 * <p>注意 {@code execute()} 的语义（见 {@link com.asyncanimator.launcher.async.LooperExecutor}）：
 * 当前线程已是目标线程时直接 run，否则 post —— 所以"已在动画线程上"不会多跳一帧。
 */
public class AsyncAnimWrapper {

    /** 投递到独立动画线程（"launcher.anim"）执行。 */
    public final void runOnAnimThread(Runnable task) {
        if (task == null) return;
        AnimExecutors.ANIM_CONTROL_EXECUTOR.execute(task);
    }

    /** 投递回主线程执行（View 属性写入、listener 回调走这里）。 */
    public final void runOnMainThread(Runnable task) {
        if (task == null) return;
        Executors.MAIN_EXECUTOR.execute(task);
    }
}
