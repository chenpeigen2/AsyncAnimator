package com.asyncanimator.launcher.animthread

import android.os.Handler
import com.asyncanimator.launcher.async.LooperExecutor

/**
 * AnimExecutors — 独立动画线程的执行器集合。
 *
 * 还原自 OPPO 源码 com/oplus/basecommon/thread/Executors.java：
 * ```
 *   public static Looper createAndStartNewLooper(String name, int priority, int affinity) {
 *       HandlerThread thread = new HandlerThread(name, priority);
 *       ... Process.setThreadPriority + OPPO 私有绑核(affinity) ...
 *   }
 *   MAIN_EXECUTOR      = new LooperExecutor(Looper.getMainLooper());
 *   UI_HELPER_EXECUTOR = new LooperExecutor(createAndStartNewLooper("UiThreadHelper", -4, 2003));
 *   MODEL_EXECUTOR     = new OplusLooperExecutor(createAndStartNewLooper("launcher-loader", -4, 2001));
 * ```
 *
 * 第三参 affinity（2001/2003 是 OPPO 的大核绑定标记）属 UIFirst 私有能力，
 * AOSP 无公开 API，本移植仅保留"优先级 -4"语义。
 *
 * 用法（配合 [com.asyncanimator.launcher.async.AsyncValueAnimator]）：
 * ```
 *   val anim = AsyncValueAnimator()
 *   anim.executor = AnimExecutors.ANIM_CONTROL_EXECUTOR // start/帧推进都在独立线程
 *   anim.asyncAnimCallbacks.addListener(...)            // listener 仍回主线程
 *   anim.start()                                        // 任意线程调用都安全
 * ```
 */
object AnimExecutors {

    /** 独立动画线程执行器：首次加载即拉起 AnimationControlThread（单例）。 */
    val ANIM_CONTROL_EXECUTOR = LooperExecutor(Handler(AnimationControlThread.instance.looper))
}
