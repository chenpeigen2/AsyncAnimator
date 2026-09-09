package com.asyncanimator.launcher.animthread

import android.os.Handler
import android.os.SystemClock
import com.asyncanimator.core.scheduler.TickScheduler
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * HandlerTickScheduler — 绑定指定 Looper 的帧调度器。
 *
 * 对应 v3 文档 `docs/animation-thread-analysis.md` §2 中 FrameCallbackProvider14 的退化路径：
 * `handler.postDelayed(this, frameDelay)` 定时驱动帧。
 * 真机上若把本类换成 per-thread Choreographer 适配（FrameCallbackProvider16 语义），
 * 即可获得 VSYNC 精度——本类保留同样接口，替换成本为零。
 *
 * 与 [com.asyncanimator.core.scheduler.ScheduledTickScheduler] 的区别：
 *
 *  - 后者用共享 JVM 调度线程 tick，与"动画在哪个线程启动"无关；
 *  - 本类把帧回调投递到绑定 Looper（独立动画线程）上执行，
 *    使"start 与帧推进同线程"在移植框架上真实成立。
 *
 * 自维持回路：有活跃 callback 时每帧重投递；列表清空后停止（等价
 * Choreographer 无订阅者时不再订阅 VSYNC），下次 `addAnimationFrameCallback`
 * 会重新 start。
 */
internal class HandlerTickScheduler(
    private val handler: Handler?,
    override val frameIntervalMs: Long = 16 // 默认 60Hz
) : TickScheduler {

    private val callbacks = ConcurrentLinkedQueue<TickScheduler.FrameCallback>()
    private val frameTimeNanosAtomic = AtomicLong(0)
    private val frameCountAtomic = AtomicLong(0)

    @Volatile
    private var running = false
    private var pulsePosted = false

    /** 上一帧时间戳（uptime ms），用于漂移补偿——对齐原厂 FrameCallbackProvider14。 */
    private var lastFrameMs = -1L

    override val frameTimeNanos: Long get() = frameTimeNanosAtomic.get()

    override val frameCount: Long get() = frameCountAtomic.get()

    override fun postFrameCallback(callback: TickScheduler.FrameCallback?) {
        if (callback == null) return
        callbacks.add(callback)
    }

    override fun removeFrameCallback(callback: TickScheduler.FrameCallback?) {
        if (callback == null) return
        callbacks.remove(callback)
    }

    @Synchronized
    override fun start() {
        if (running) return
        running = true
        scheduleNextFrame()
    }

    @Synchronized
    override fun stop() {
        running = false
    }

    /**
     * 帧脉冲：每帧执行一次 tick，再按需续帧。
     *
     * 延迟做漂移补偿：delay = frameInterval - (now - lastFrame)，clamp ≥ 0——
     * 对齐原厂 `FrameCallbackProvider14.postFrameCallback`
     * （androidx/core/animation/AnimationHandler.java:61-63）。
     * 上一帧处理耗时越长，下一帧延迟越短，保持整体节奏不漂移。
     */
    private fun scheduleNextFrame() {
        if (!running || pulsePosted || handler == null) return
        pulsePosted = true
        val now = SystemClock.uptimeMillis()
        val delay = if (lastFrameMs < 0) frameIntervalMs
                    else (frameIntervalMs - (now - lastFrameMs)).coerceAtLeast(0)
        handler.postDelayed({
            pulsePosted = false
            if (!running) return@postDelayed
            lastFrameMs = SystemClock.uptimeMillis()
            tick()
            if (!callbacks.isEmpty()) {
                scheduleNextFrame()
            } else {
                running = false // 无订阅者，停止脉冲（下次 add 时 start() 重启）
            }
        }, delay)
    }

    /** 一次 tick：取时间戳 → 快照遍历 callbacks → 逐个 doFrame。 */
    private fun tick() {
        val count = frameCountAtomic.incrementAndGet()
        val t = SystemClock.uptimeNanos()
        frameTimeNanosAtomic.set(t)
        // runCatching 做异常隔离（对齐 ScheduledTickScheduler 行为）
        for (cb in callbacks.toTypedArray()) {
            runCatching { cb.doFrame(t) }
        }
        if (count < 0) {
            frameCountAtomic.set(0)
        }
    }
}
