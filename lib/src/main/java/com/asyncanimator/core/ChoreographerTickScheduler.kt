package com.asyncanimator.core

import android.view.Choreographer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * ChoreographerTickScheduler — 真 VSYNC 帧调度器。
 *
 * 对齐原厂 `FrameCallbackProvider16`（androidx/core/animation/AnimationHandler.java:82-109）：
 * `Choreographer.getInstance().postFrameCallback(this)`，由系统派发帧时间戳，
 * 不使用固定 16ms 定时器。`ValueAnimator.getFrameDelay()` 是动画定时配置，
 * 不是显示刷新周期；本调度器不暴露名义刷新率，也不把掉帧后的时间差当作刷新率。
 *
 * 第一次请求帧时取得并固定该线程的 Choreographer（构造 scheduler 本身不绑定）。
 * 后续 stop/start 即使由其他 Looper 调用也不会迁移帧源；第一次调度应在预期的 owner 上。
 * 生命周期/订阅状态在短临界区内更新，业务回调在锁外按快照派发；stop/remove 不撤回
 * 已经开始派发的当前快照。后台只是计算/回调所在的线程，不意味着可以直接改 View。
 *
 * 与已删除的 HandlerTickScheduler（postDelayed 自走时钟）不同，本类使用 app VSYNC，
 * 不自走时；由系统派发下一帧，无需手写 drift 计算。
 * 单回调异常隔离是本库的选择；原厂 provider 路径会向外抛出，不保证继续整帧。
 */
internal class ChoreographerTickScheduler : TickScheduler {

    private val callbacks = ConcurrentLinkedQueue<TickScheduler.FrameCallback>()
    private val frameTimeNanosAtomic = AtomicLong(0)
    private val frameCountAtomic = AtomicLong(0)

    @Volatile
    private var running = false
    @Volatile
    private var pulsePosted = false

    private var frameSource: Choreographer? = null

    /** 固定首次成功取得的帧源；仅 scheduleNextFrame 在状态锁内读取。
     * 不在每次 start 时重新读取调用线程的 ThreadLocal，否则跨 Looper 重启会迁移帧。
     * 兼容既有无 Looper/获取失败（含 JVM stub）的 no-op；不缓存失败，stop/start 后可再尝试。
     */
    private val choreographer: Choreographer?
        get() = frameSource ?: runCatching { Choreographer.getInstance() }.getOrNull()
            ?.also { frameSource = it }

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        val dispatch = synchronized(this) {
            pulsePosted = false
            running
        }
        if (!dispatch) return@FrameCallback
        tick(frameTimeNanos) // 外部回调不持有状态锁，允许重入/其他线程注册与停止。
        synchronized(this) {
            if (callbacks.isNotEmpty()) {
                scheduleNextFrame()
            } else {
                // 与 postFrameCallback 的入列/启动一起串行化，防止空队列收尾覆盖新注册。
                running = false
            }
        }
    }

    override val frameTimeNanos: Long get() = frameTimeNanosAtomic.get()

    override val frameCount: Long get() = frameCountAtomic.get()

    @Synchronized
    override fun postFrameCallback(callback: TickScheduler.FrameCallback?) {
        if (callback == null) return
        callbacks.add(callback)
        if (!running) start()
    }

    @Synchronized
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

    /** 向固定 owner 的 Choreographer 订阅下一帧；与 start/帧尾共享 pulsePosted 的锁。 */
    @Synchronized
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
