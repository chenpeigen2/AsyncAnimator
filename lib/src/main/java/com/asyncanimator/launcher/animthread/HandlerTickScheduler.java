package com.asyncanimator.launcher.animthread;

import android.os.Handler;

import com.asyncanimator.core.scheduler.TickScheduler;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HandlerTickScheduler — 绑定指定 Looper 的帧调度器。
 *
 * <p>对应分析文档 §2.3 FrameCallbackProvider14 的退化路径：
 * {@code mHandler.postDelayed(this, frameDelay)} 定时驱动帧。
 * 真机上若把本类换成 per-thread Choreographer 适配（FrameCallbackProvider16 语义），
 * 即可获得 VSYNC 精度——本类保留同样接口，替换成本为零。
 *
 * <p>与 {@link com.asyncanimator.core.scheduler.ScheduledTickScheduler} 的区别：
 * <ul>
 *   <li>后者用共享 JVM 调度线程 tick，与"动画在哪个线程启动"无关；</li>
 *   <li>本类把帧回调投递到绑定 Looper（独立动画线程）上执行，
 *       使"start 与帧推进同线程"在移植框架上真实成立。</li>
 * </ul>
 *
 * <p>自维持回路：有活跃 callback 时每帧重投递；列表清空后停止（等价
 * Choreographer 无订阅者时不再订阅 VSYNC），下次 {@code addAnimationFrameCallback}
 * 会重新 start。
 */
public class HandlerTickScheduler implements TickScheduler {

    private final Handler mHandler;
    private final long mFrameIntervalMs;
    private final ConcurrentLinkedQueue<FrameCallback> mCallbacks = new ConcurrentLinkedQueue<>();
    private final AtomicLong mFrameTimeNanos = new AtomicLong(0);
    private final AtomicLong mFrameCount = new AtomicLong(0);

    private volatile boolean mRunning = false;
    private boolean mPulsePosted = false;

    public HandlerTickScheduler(Handler handler) {
        this(handler, 16); // 默认 60Hz
    }

    public HandlerTickScheduler(Handler handler, long frameIntervalMs) {
        mHandler = handler;
        mFrameIntervalMs = frameIntervalMs;
    }

    @Override
    public void postFrameCallback(FrameCallback callback) {
        if (callback == null) return;
        mCallbacks.add(callback);
    }

    @Override
    public void removeFrameCallback(FrameCallback callback) {
        if (callback == null) return;
        mCallbacks.remove(callback);
    }

    @Override
    public long getFrameTimeNanos() {
        return mFrameTimeNanos.get();
    }

    @Override
    public long getFrameCount() {
        return mFrameCount.get();
    }

    @Override
    public long getFrameIntervalMs() {
        return mFrameIntervalMs;
    }

    @Override
    public synchronized void start() {
        if (mRunning) return;
        mRunning = true;
        scheduleNextFrame();
    }

    @Override
    public synchronized void stop() {
        mRunning = false;
    }

    /** 帧脉冲：每帧执行一次 tick，再按需续帧。 */
    private void scheduleNextFrame() {
        if (!mRunning || mPulsePosted || mHandler == null) return;
        mPulsePosted = true;
        mHandler.postDelayed(() -> {
            mPulsePosted = false;
            if (!mRunning) return;
            tick();
            if (!mCallbacks.isEmpty()) {
                scheduleNextFrame();
            } else {
                mRunning = false; // 无订阅者，停止脉冲（下次 add 时 start() 重启）
            }
        }, mFrameIntervalMs);
    }

    /** 一次 tick：取时间戳 → 快照遍历 callbacks → 逐个 doFrame。 */
    private void tick() {
        long count = mFrameCount.incrementAndGet();
        long t = android.os.SystemClock.uptimeNanos();
        mFrameTimeNanos.set(t);
        FrameCallback[] snapshot = mCallbacks.toArray(new FrameCallback[0]);
        for (FrameCallback cb : snapshot) {
            try {
                cb.doFrame(t);
            } catch (Throwable ignored) {
                // 单个 callback 抛异常不连累其他（对齐 ScheduledTickScheduler 行为）
            }
        }
        if (count < 0) {
            mFrameCount.set(0);
        }
    }
}
