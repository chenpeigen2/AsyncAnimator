package com.asyncanimator.launcher.async;

/**
 * Executors — 预定义的 LooperExecutor 单例集合。
 *
 * <p>对应原 OPPO 代码 {@code com.oplus.basecommon.thread.Executors}（简化版）
 * 和分析文档 §6.3.2。
 *
 * <p>lib 模块提供线程安全的同步实现；demo 模块会替换为基于 Android Handler 的版本。
 */
public final class Executors {

    private Executors() {}

    /** 主线程 LooperExecutor（demo 模块会用真实 Android Main Looper 替换）。 */
    public static final LooperExecutor MAIN_EXECUTOR = newMainExecutor();

    private static LooperExecutor newMainExecutor() {
        // lib 模块默认实现：用 Thread 引用
        final Thread mainThread = Thread.currentThread();
        LooperExecutor.Handler handler = new LooperExecutor.Handler() {
            @Override public LooperExecutor.Looper getLooper() {
                return new LooperExecutor.Looper() {
                    @Override public Thread getThread() { return mainThread; }
                };
            }
            @Override public boolean post(Runnable r) { r.run(); return true; }
            @Override public boolean postDelayed(Runnable r, long delayMs) {
                new Thread(() -> {
                    try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
                    r.run();
                }, "AsyncAnimator-Delayed").start();
                return true;
            }
        };
        return new LooperExecutor(handler);
    }
}