package com.asyncanimator.core.scheduler

/**
 * TickScheduler — 仿真 Choreographer 的核心接口。
 *
 * 设计动机：原始 Android [android.view.Choreographer] 只能在 Android 设备上跑，
 * 本接口抽象出"每帧调用 callback"的本质，让 lib 模块的代码可以在 JVM 里测试。
 *
 * 对应 v3 文档 `docs/animation-thread-analysis.md` §1/§2 — 平台层 Choreographer + 框架层 AnimationHandler。
 *
 * 实现类：
 *
 *  - [ScheduledTickScheduler] — JVM 实现，用 ScheduledExecutorService 驱动帧
 *  - [com.asyncanimator.launcher.animthread.HandlerTickScheduler] — 绑定 Looper 的实现
 *  - [com.asyncanimator.launcher.animthread.ChoreographerTickScheduler] — 公开 Choreographer 实现（真 VSYNC）
 */
internal interface TickScheduler {

    /** 注册帧回调。下一次 tick 时 callback.doFrame(frameTimeNanos) 会被调用。 */
    fun postFrameCallback(callback: FrameCallback?)

    /** 取消注册。下一次 tick 时 callback 不会被调用。 */
    fun removeFrameCallback(callback: FrameCallback?)

    /** 当前帧时间戳（单位：纳秒）。模拟 Choreographer 的 frameTimeNanos。 */
    val frameTimeNanos: Long

    /** 启动调度循环。 */
    fun start()

    /** 停止调度循环（已注册的 callback 保留，等下次 start 恢复）。 */
    fun stop()

    /** 当前帧索引（从 0 开始，每 tick +1）。便于测试与日志。 */
    val frameCount: Long

    /** 帧间隔（毫秒）。 */
    val frameIntervalMs: Long

    /** 帧回调契约。对应 Android 原生 `Choreographer.FrameCallback`。 */
    fun interface FrameCallback {
        fun doFrame(frameTimeNanos: Long)
    }
}
