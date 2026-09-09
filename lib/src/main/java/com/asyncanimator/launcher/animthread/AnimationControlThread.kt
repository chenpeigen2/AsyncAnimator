package com.asyncanimator.launcher.animthread

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import com.asyncanimator.core.anim.AnimationHandler
import com.asyncanimator.core.scheduler.TickScheduler

/**
 * AnimationControlThread — 独立动画线程（"launcher.anim"）。
 *
 * 还原自 OPPO Launcher 15.8.24（ColorOS 15）反编译源码的**手势/转场动画主链**：
 * ```
 *   com/oplus/basecommon/thread/OplusExecutors.java:95
 *     ANIM_EXECUTOR = new OplusLooperExecutor(
 *         Executors.createAndStartNewLooper("launcher.anim", -19,
 *             LauncherBooster.LAUNCHER_STATIC_LAUNCHER_ANIM),
 *         new f(1));            // ← 线程 init 回调，见 ANIM_EXECUTOR$lambda$0
 *
 *   com/oplus/basecommon/thread/OplusExecutors.java:169
 *     private static void ANIM_EXECUTOR$lambda$0() {
 *         AnimationHandler.getInstance().setProvider(new SfVsyncFrameCallbackProvider());
 *         LauncherBooster.getCpu().setUxThreadValue(Process.myTid());
 *     }
 * ```
 *
 * 两个要点必须一起看，这才是方案能成立的原因：
 *
 *  1. **线程**：优先级字面量 -19（数值上等于 `THREAD_PRIORITY_URGENT_AUDIO`，
 *     比 `THREAD_PRIORITY_URGENT_DISPLAY`(-8) 更激进），
 *     并通过 LauncherBooster 注册为 UX 线程（提权 / 绑大核，OPPO 私有）；
 *  2. **帧源**：在该线程的 `android.animation.AnimationHandler`（平台隐藏类，ThreadLocal）
 *     上装 `SfVsyncFrameCallbackProvider` —— 直接吃 SurfaceFlinger 的 VSYNC，
 *     而不是 UI 线程 Choreographer。这样动画帧不排在主线程 traversal 后面。
 *
 * 移植取舍（AOSP 无对应公开 API）：
 *
 *  - `SfVsyncFrameCallbackProvider`、`AnimationHandler.setProvider`、`LauncherBooster`
 *    都是 hidden/私有，这里用 [ChoreographerTickScheduler]（公开 Choreographer，真 VSYNC）/ [HandlerTickScheduler]（postDelayed 兜底）
 *    等价替代，并保留 [onLooperPrepared] 作为"线程 init 回调"的落点；
 *  - 优先级按原厂字面量 -19 直接设置（见 [PRIORITY]；不使用 SDK 常量，
 *    因为没有一个公开常量等于 -19）。
 *
 * 线程安全模型（对齐原厂）：动画参数 volatile/Atomic；View 与 listener 回主线程
 * （AsyncAnimCallbacks）；
 * start/cancel/end 按"当前线程 vs 目标 Looper"自动 marshal
 * （原厂 `CustomRectFSpringAnim.start()` 的模式：先取 executor 再判 `isCurrentThread`；
 * 本 lib 未复刻该协议，见 `docs/review/04-frame-spring-continuation.md` §4.2-2）。
 */
class AnimationControlThread private constructor() : HandlerThread(THREAD_NAME, PRIORITY) {

    init {
        start()
    }

    /**
     * 等价于原厂 `ANIM_EXECUTOR$lambda$0()`：在**本线程内**完成两件事——
     * 装帧源、把自己注册成 UX 线程。
     *
     * 本方法由 HandlerThread 在新线程上、Looper 就绪后调用，
     * 所以此处 `AnimationHandler.instance`（ThreadLocal）拿到的正是本线程那一份，
     * 与原厂 `setProvider` 的作用域完全一致。
     */
    override fun onLooperPrepared() {
        // ① 帧源：原厂 setProvider(SfVsyncFrameCallbackProvider())，移植为绑本线程 Looper 的帧循环
        // 帧源：优先公开 Choreographer（真 VSYNC，对齐平台 FrameCallbackProvider16 语义）；
        // 拿不到（JVM/无 Looper 环境）退化为 HandlerTickScheduler（postDelayed 帧循环）
        val scheduler: TickScheduler =
            if (choreographerAvailable()) ChoreographerTickScheduler()
            else HandlerTickScheduler(Handler(looper))
        AnimationHandler.installThreadScheduler(scheduler)
        // ② UX 线程提权：原厂 LauncherBooster.getCpu().setUxThreadValue(Process.myTid())，
        //    AOSP 无对应 API；退化为在本线程再确认一次优先级（构造参数已设，此处兜住被外部改动的情况）
        runCatching { Process.setThreadPriority(Process.myTid(), PRIORITY) }
    }

    /** 本线程是否可用公开 Choreographer（onLooperPrepared 在本线程执行，正常必为 true）。 */
    private fun choreographerAvailable(): Boolean =
        runCatching { Choreographer.getInstance() != null }.getOrDefault(false)

    companion object {

        /** 原厂线程名，便于在 systrace / logcat 上对照。 */
        const val THREAD_NAME = "launcher.anim"

        /**
         * 原厂优先级：字面量 -19（OplusExecutors.java:95 `createAndStartNewLooper("launcher.anim", -19, …)`）。
         * 数值上等于 `Process.THREAD_PRIORITY_URGENT_AUDIO`；
         * 注意不是 `THREAD_PRIORITY_URGENT_DISPLAY`（后者是 -8，曾是本库的 bug，见 review 01 §②C-1）。
         */
        private const val PRIORITY = -19

        /** 单例：类加载即创建线程并 start（原厂 ANIM_EXECUTOR 是静态 final，同样随进程常驻）。 */
        internal val instance: AnimationControlThread by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            AnimationControlThread()
        }
    }
}
