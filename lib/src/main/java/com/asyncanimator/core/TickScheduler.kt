package com.asyncanimator.core

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
 *  - [ChoreographerTickScheduler] — 公开 Choreographer 实现（真 VSYNC）
 */
internal interface TickScheduler {

    /** 持续注册帧回调，后续 tick 时派发；不是 Choreographer 的单次订阅。
     * Choreographer 实现在首次请求帧时固定 owner，post 自动启动；null 为 no-op。
     */
    fun postFrameCallback(callback: FrameCallback?)

    /** 取消注册，之后的新快照不再包含它；已复制/正在派发的当前快照不能撤回。 */
    fun removeFrameCallback(callback: FrameCallback?)

    /** 当前帧时间戳（单位：纳秒）。模拟 Choreographer 的 frameTimeNanos。 */
    val frameTimeNanos: Long

    /** 启动调度循环。 */
    fun start()

    /** 暂停后续帧（保留订阅，start 恢复）；不终止线程，不撤回已开始的当前帧派发。 */
    fun stop()

    /** 当前帧索引（从 0 开始，每 tick +1）。便于测试与日志。 */
    val frameCount: Long

    /** 帧回调契约。对应 Android 原生 `Choreographer.FrameCallback`。 */
    fun interface FrameCallback {
        fun doFrame(frameTimeNanos: Long)
    }
}
