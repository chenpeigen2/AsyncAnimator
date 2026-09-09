package com.asyncanimator.core.scheduler

import android.animation.ValueAnimator
import android.view.Choreographer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * ChoreographerTickScheduler — 真 VSYNC 帧调度器。
 *
 * 对齐原厂 `FrameCallbackProvider16`（androidx/core/animation/AnimationHandler.java:82-109）：
 * `Choreographer.getInstance().postFrameCallback(this)`，帧间隔跟随系统
 * （`ValueAnimator.getFrameDelay()`，公开 API；120Hz 屏自动 ~8ms）。
 *
 * `Choreographer.getInstance()` 是 ThreadLocal：在 launcher.anim 线程上首次访问即
 * 创建该线程的 Choreographer（挂在该线程 Looper 上），帧回调天然落在所属线程——
 * 满足"动画在哪个线程 start，帧回调就在哪个线程 tick"的 per-thread 语义。
 *
 * 与 [HandlerTickScheduler]（postDelayed 自走时钟）的区别：本类对齐显示 VSYNC，
 * 不自走时；掉帧由系统 vsync 自然补偿，无需手写 drift 计算。
 */
internal class ChoreographerTickScheduler : TickScheduler {

    private val callbacks = ConcurrentLinkedQueue<TickScheduler.FrameCallback>()
    private val frameTimeNanosAtomic = AtomicLong(0)
    private val frameCountAtomic = AtomicLong(0)

    @Volatile
    private var running = false
    @Volatile
    private var pulsePosted = false

    /** 本线程的 Choreographer；JVM 单测 stub 下为 null（start 变 no-op）。 */
    private val choreographer: Choreographer?
        get() = runCatching { Choreographer.getInstance() }.getOrNull()

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        pulsePosted = false
        if (!running) return@FrameCallback
        tick(frameTimeNanos)
        if (callbacks.isNotEmpty()) {
            scheduleNextFrame()
        } else {
            running = false // 无订阅者，停止订阅 vsync（下次 postFrameCallback 时 start 重启）
        }
    }

    override val frameTimeNanos: Long get() = frameTimeNanosAtomic.get()

    override val frameCount: Long get() = frameCountAtomic.get()

    /** 帧间隔：跟随系统设置（ValueAnimator.getFrameDelay，平台公开 API）。 */
    override val frameIntervalMs: Long get() = ValueAnimator.getFrameDelay()

    override fun postFrameCallback(callback: TickScheduler.FrameCallback?) {
        if (callback == null) return
        callbacks.add(callback)
        if (!running) start()
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

    /** 向本线程 Choreographer 订阅下一帧 vsync。 */
    private fun scheduleNextFrame() {
        if (!running || pulsePosted) return
        val choreo = choreographer ?: return
        pulsePosted = true
        choreo.postFrameCallback(frameCallback)
    }

    /** 一次 tick：记录帧时间戳 → 快照遍历 callbacks → 逐个 doFrame。 */
    private fun tick(frameTimeNanos: Long) {
        frameCountAtomic.incrementAndGet()
        frameTimeNanosAtomic.set(frameTimeNanos)
        // runCatching 做异常隔离（单个 callback 抛异常不连累其他）
        for (cb in callbacks.toTypedArray()) {
            runCatching { cb.doFrame(frameTimeNanos) }
        }
    }
}
