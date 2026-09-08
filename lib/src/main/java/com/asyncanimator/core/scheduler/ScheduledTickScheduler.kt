package com.asyncanimator.core.scheduler

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * ScheduledTickScheduler — JVM 仿真 TickScheduler。
 *
 * 用 [ScheduledExecutorService.scheduleAtFixedRate] 每 N 毫秒触发一次"tick"，
 * tick 内遍历所有已注册的 callback 并调用 `doFrame(frameTimeNanos)`。
 *
 * 对应 v3 文档 `docs/animation-thread-analysis.md` §2 中 FrameCallbackProvider14 的退化路径 —— 当 Choreographer 不可用时，
 * 用 Handler.postDelayed(this, 16) 定时驱动。本类是这种思路的纯 Java 实现。
 *
 * 特点：
 *
 *  - 线程安全：callback 列表是 ConcurrentLinkedQueue，多线程 post/remove 安全
 *  - 迭代快照：tick 时用 toTypedArray 拍快照再遍历，避免迭代中外部修改
 *  - 异常隔离：单个 callback 抛异常不影响其他 callback
 *  - 可调帧率：[frameIntervalMs] 可以任意调整（demo 用 16ms=60Hz，单元测试可降到 100ms）
 */
internal class ScheduledTickScheduler(
    override val frameIntervalMs: Long = 16 // 默认 60Hz
) : TickScheduler {

    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        thread(start = false, name = "AsyncAnimator-Tick", isDaemon = true) { r.run() }
    }
    private val callbacks = ConcurrentLinkedQueue<TickScheduler.FrameCallback>()
    private val frameCountAtomic = AtomicLong(0)
    private val frameTimeNanosAtomic = AtomicLong(0)

    @Volatile
    private var running = false
    private var task: ScheduledFuture<*>? = null

    override val frameTimeNanos: Long get() = frameTimeNanosAtomic.get()

    override val frameCount: Long get() = frameCountAtomic.get()

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
        // 第一次 tick 立即触发（scheduleAtFixedRate 的 initialDelay=0）
        task = exec.scheduleAtFixedRate(::tick, 0, frameIntervalMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    override fun stop() {
        running = false
        task?.cancel(false)
        task = null
    }

    /**
     * 一次 tick：取时间戳 → 遍历 callbacks（快照）→ 逐个 doFrame。
     * 这是原厂 onAnimationFrame 主循环的仿真实现（review 04）。
     */
    private fun tick() {
        val count = frameCountAtomic.incrementAndGet()
        val t = System.nanoTime()
        frameTimeNanosAtomic.set(t)
        // 快照遍历：避免遍历中 callback 列表被修改；
        // runCatching 做异常隔离（单个 callback 抛异常不连累其他）
        for (cb in callbacks.toTypedArray()) {
            runCatching { cb.doFrame(t) }
        }
        if (callbacks.isEmpty()) {
            stop()
        }
        if (count < 0) {
            // 极少见：long 溢出，重置
            frameCountAtomic.set(0)
        }
    }}
