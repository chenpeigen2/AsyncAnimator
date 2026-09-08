package com.asyncanimator.launcher.async;

/**
 * LooperExecutor — 跨线程 Executor 封装。
 *
 * <p>对应原 OPPO 代码 {@code com.oplus.basecommon.thread.LooperExecutor}（简化版）
 * 和分析文档 §6.3.2。
 *
 * <p>关键设计：{@link #execute(Runnable)} 自动判断"当前线程 vs 目标 Looper"：
 * <ul>
 *   <li>同一线程：直接 {@code runnable.run()}</li>
 *   <li>不同线程：用 Handler.post 投递（{@link Executors#MAIN_EXECUTOR} 与
 *       {@code AnimExecutors#ANIM_CONTROL_EXECUTOR} 均绑定真实 android.os.Handler；
 *       仅 JVM 单测兜底为就地执行）</li>
 * </ul>
 */
public class LooperExecutor {

    public interface Handler {
        Looper getLooper();
        boolean post(Runnable r);
        boolean postDelayed(Runnable r, long delayMs);
    }

    private final Handler mHandler;

    public LooperExecutor(Handler handler) {
        this.mHandler = handler;
    }

    public Handler getHandler() {
        return mHandler;
    }

    /** 当前线程 Looper 检测（简化：lib 模块假设总是非 null）。 */
    public static Object myLooper() {
        return Thread.currentThread();
    }

    public Looper getLooper() {
        return mHandler == null ? null : mHandler.getLooper();
    }

    public Thread getThread() {
        return mHandler == null ? null : mHandler.getLooper().getThread();
    }

    public boolean isCurrentThread() {
        Looper l = getLooper();
        return l != null && l.getThread() == Thread.currentThread();
    }

    public void execute(Runnable runnable) {
        if (runnable == null) return;
        if (isCurrentThread()) {
            runnable.run();
        } else {
            mHandler.post(runnable);
        }
    }

    public void post(Runnable runnable) {
        if (mHandler != null) mHandler.post(runnable);
    }

    public void postDelayed(Runnable runnable, long delayMs) {
        if (mHandler != null) mHandler.postDelayed(runnable, delayMs);
    }

    /** 简化版 Looper 接口。demo 模块会用真实 Android Looper 实现。 */
    public interface Looper {
        Thread getThread();
    }
}