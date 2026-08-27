package com.asyncanimator.core.scheduler;

/**
 * TickScheduler — 仿真 Choreographer 的核心接口。
 *
 * <p>设计动机：原始 Android {@link android.view.Choreographer} 只能在 Android 设备上跑，
 * 本接口抽象出"每帧调用 callback"的本质，让 lib 模块的代码可以在 JVM 里测试。
 *
 * <p>对应分析文档 §1 / §2 — 平台层 Choreographer + 框架层 AnimationHandler。
 *
 * <p>实现类：
 * <ul>
 *   <li>{@link ScheduledTickScheduler} — JVM 实现，用 ScheduledExecutorService 驱动帧</li>
 *   <li>（Android 设备上）真实 Choreographer 适配器 — 见 demo 模块或独立 Android lib</li>
 * </ul>
 */
public interface TickScheduler {

    /**
     * 注册一次帧回调。下一次 tick 时 callback.doFrame(frameTimeNanos) 会被调用。
     * 若已注册（equals 相同），不会重复添加。
     */
    void postFrameCallback(FrameCallback callback);

    /**
     * 取消注册。下一次 tick 时 callback 不会被调用。
     */
    void removeFrameCallback(FrameCallback callback);

    /**
     * 当前帧时间戳（单位：纳秒）。模拟 Choreographer 的 frameTimeNanos。
     */
    long getFrameTimeNanos();

    /** 启动调度循环。 */
    void start();

    /** 停止调度循环（已注册的 callback 保留，等下次 start 恢复）。 */
    void stop();

    /** 当前帧索引（从 0 开始，每 tick +1）。便于测试与日志。 */
    long getFrameCount();

    /** 帧间隔（毫秒）。 */
    long getFrameIntervalMs();

    /**
     * 帧回调契约。
     * 对应 Android 原生 {@code Choreographer.FrameCallback}。
     */
    interface FrameCallback {
        void doFrame(long frameTimeNanos);
    }
}