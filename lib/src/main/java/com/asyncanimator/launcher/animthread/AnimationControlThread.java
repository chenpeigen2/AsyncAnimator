package com.asyncanimator.launcher.animthread;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import com.asyncanimator.core.anim.AnimationHandler;

/**
 * AnimationControlThread — 独立动画线程（"launcher.anim"）。
 *
 * <p>还原自 OPPO Launcher 15.8.24（ColorOS 15）反编译源码的**手势/转场动画主链**：
 * <pre>
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
 * </pre>
 *
 * <p>两个要点必须一起看，这才是方案能成立的原因：
 * <ol>
 *   <li><b>线程</b>：优先级 -19（{@code THREAD_PRIORITY_DISPLAY - 17}，比 URGENT_DISPLAY 更激进），
 *       并通过 LauncherBooster 注册为 UX 线程（提权 / 绑大核，OPPO 私有）；</li>
 *   <li><b>帧源</b>：在该线程的 {@code android.animation.AnimationHandler}（平台隐藏类，ThreadLocal）
 *       上装 {@code SfVsyncFrameCallbackProvider} —— 直接吃 SurfaceFlinger 的 VSYNC，
 *       而不是 UI 线程 Choreographer。这样动画帧不排在主线程 traversal 后面。</li>
 * </ol>
 *
 * <p>移植取舍（AOSP 无对应公开 API）：
 * <ul>
 *   <li>{@code SfVsyncFrameCallbackProvider}、{@code AnimationHandler.setProvider}、
 *       {@code LauncherBooster} 都是 hidden/私有，这里用
 *       {@link HandlerTickScheduler}（绑本线程 Looper 的 postDelayed 帧循环）等价替代，
 *       并保留 {@link #onLooperPrepared()} 作为"线程 init 回调"的落点；</li>
 *   <li>优先级用 {@link Process#THREAD_PRIORITY_DISPLAY} - 17 还原 -19 这个数值。</li>
 * </ul>
 *
 * <p>线程安全模型（对齐原厂）：动画参数 volatile/Atomic；View 与 listener 回主线程
 * （{@code AsyncAnimWrapper.runOnMainThread} / AsyncAnimCallbacks）；
 * start/cancel/end 按"当前线程 vs 目标 Looper"自动 marshal
 * （见 CustomRectFSpringAnim.start()：先取 executor 再判 {@code looper.isCurrentThread()}）。
 */
public final class AnimationControlThread extends HandlerThread {

    /** 原厂线程名，便于在 systrace / logcat 上对照。 */
    private static final String NAME = "launcher.anim";

    /** 原厂优先级：-19（见 OplusExecutors.java:95）。 */
    private static final int PRIORITY = Process.THREAD_PRIORITY_DISPLAY - 17;

    /** holder 单例：类加载即创建线程并 start（原厂 ANIM_EXECUTOR 是静态 final，同样随进程常驻）。 */
    private static class ThreadHolder {
        private static final AnimationControlThread INSTANCE = new AnimationControlThread();
    }

    private AnimationControlThread() {
        super(NAME, PRIORITY);
        start();
    }

    public static AnimationControlThread getInstance() {
        return ThreadHolder.INSTANCE;
    }

    /** 线程名（demo/日志用）。 */
    public static String getThreadName() {
        return NAME;
    }

    /**
     * 等价于原厂 {@code ANIM_EXECUTOR$lambda$0()}：在**本线程内**完成两件事——
     * 装帧源、把自己注册成 UX 线程。
     *
     * <p>本方法由 HandlerThread 在新线程上、Looper 就绪后调用，
     * 所以此处 {@code AnimationHandler.getInstance()}（ThreadLocal）拿到的正是本线程那一份，
     * 与原厂 {@code setProvider} 的作用域完全一致。
     */
    @Override
    protected void onLooperPrepared() {
        // ① 帧源：原厂 setProvider(SfVsyncFrameCallbackProvider())，移植为绑本线程 Looper 的帧循环
        AnimationHandler.installThreadScheduler(
                new HandlerTickScheduler(new Handler(getLooper())));
        // ② UX 线程提权：原厂 LauncherBooster.getCpu().setUxThreadValue(Process.myTid())，
        //    AOSP 无对应 API；退化为在本线程再确认一次优先级（构造参数已设，此处兜住被外部改动的情况）
        try {
            Process.setThreadPriority(Process.myTid(), PRIORITY);
        } catch (RuntimeException ignored) {
            // 某些设备不允许设置该优先级，忽略即可（不影响动画正确性，仅影响调度优先级）
        }
    }
}
