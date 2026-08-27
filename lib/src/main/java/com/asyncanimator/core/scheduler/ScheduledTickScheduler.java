package com.asyncanimator.core.scheduler;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ScheduledTickScheduler — JVM 仿真 TickScheduler。
 *
 * <p>用 {@link ScheduledExecutorService#scheduleAtFixedRate} 每 N 毫秒触发一次"tick"，
 * tick 内遍历所有已注册的 callback 并调用 {@code doFrame(frameTimeNanos)}。
 *
 * <p>对应分析文档 §2.3 FrameCallbackProvider14 的退化路径 —— 当 Choreographer 不可用时，
 * 用 Handler.postDelayed(this, 16) 定时驱动。本类是这种思路的纯 Java 实现。
 *
 * <p>特点：
 * <ul>
 *   <li>线程安全：callback 列表是 ConcurrentLinkedQueue，多线程 post/remove 安全</li>
 *   <li>迭代快照：tick 时用 toArray 拍快照再遍历，避免迭代中外部修改</li>
 *   <li>异常隔离：单个 callback 抛异常不影响其他 callback</li>
 *   <li>可调帧率：构造参数 frameIntervalMs 可以任意调整（demo 用 16ms=60Hz，单元测试可降到 100ms）</li>
 * </ul>
 */
public class ScheduledTickScheduler implements TickScheduler {

    private final long frameIntervalMs;
    private final ScheduledExecutorService exec;
    private final ConcurrentLinkedQueue<FrameCallback> callbacks = new ConcurrentLinkedQueue<>();
    private final AtomicLong frameCount = new AtomicLong(0);
    private final AtomicLong frameTimeNanos = new AtomicLong(0);
    private volatile boolean running = false;
    private ScheduledFuture<?> task;

    public ScheduledTickScheduler() {
        this(16); // 默认 60Hz
    }

    public ScheduledTickScheduler(long frameIntervalMs) {
        this.frameIntervalMs = frameIntervalMs;
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AsyncAnimator-Tick");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void postFrameCallback(FrameCallback callback) {
        if (callback == null) return;
        callbacks.add(callback);
    }

    @Override
    public void removeFrameCallback(FrameCallback callback) {
        if (callback == null) return;
        callbacks.remove(callback);
    }

    @Override
    public long getFrameTimeNanos() {
        return frameTimeNanos.get();
    }

    @Override
    public long getFrameCount() {
        return frameCount.get();
    }

    @Override
    public long getFrameIntervalMs() {
        return frameIntervalMs;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        // 第一次 tick 立即触发（scheduleAtFixedRate 的 initialDelay=0）
        task = exec.scheduleAtFixedRate(this::tick, 0, frameIntervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    /**
     * 一次 tick：取时间戳 → 遍历 callbacks（快照）→ 逐个 doFrame。
     * 这是分析文档 §2.5 onAnimationFrame 主循环的仿真实现。
     */
    void tick() {
        long count = frameCount.incrementAndGet();
        // frameTimeNanos 用模拟 uptimeMillis（毫秒）转 nanos，跟 Android 平台语义一致
        long t = System.nanoTime();
        frameTimeNanos.set(t);
        // 快照遍历：避免遍历中 callback 列表被修改
        FrameCallback[] snapshot = callbacks.toArray(new FrameCallback[0]);
        for (FrameCallback cb : snapshot) {
            try {
                cb.doFrame(t);
            } catch (Throwable t2) {
                // 单个 callback 抛异常不连累其他；典型情况：动画已 cancel 但仍被 fire
            }
        }
        // 帧数自增仅作为参考（调试 / 日志用）
        if (count < 0) {
            // 极少见：long 溢出，重置
            frameCount.set(0);
        }
    }

    /** 关闭底层 executor。一般在程序退出时调用。 */
    public void shutdown() {
        stop();
        exec.shutdownNow();
    }
}