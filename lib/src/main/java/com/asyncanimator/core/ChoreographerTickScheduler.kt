package com.asyncanimator.core

import android.view.Choreographer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 基于公开 Choreographer 的持续订阅帧源，不使用固定间隔定时器模拟刷新。
 * 首次请求帧时固定所属线程，之后跨线程暂停或恢复也不会迁移帧源。
 * 短临界区保护订阅和调度状态，锁外执行快照；后台回调不意味着可以安全写入 View。
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

    /**
     * 惰性取得并缓存首次成功获取的 Choreographer，仅由持有状态锁的调度方法读取。
     * 获取异常或无 Looper 时返回 null 且不缓存失败，避免跨线程重启时重新绑定已成功的帧源。
     */
    private val choreographer: Choreographer?
        get() = frameSource ?: runCatching { Choreographer.getInstance() }.getOrNull()
            ?.also { frameSource = it }

    /**
     * 处理 Choreographer 的单次通知：先清除待派发标志，运行时在锁外更新订阅者。
     * 帧尾在锁内决定续帧或停止，使空队列收尾不会覆盖并发的新注册。
     */
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        val dispatch = synchronized(this) {
            pulsePosted = false
            running
        }
        if (!dispatch) return@FrameCallback
        tick(frameTimeNanos)
        synchronized(this) {
            if (callbacks.isNotEmpty()) {
                scheduleNextFrame()
            } else {

                running = false
            }
        }
    }

    /**
     * 读取最近一次已派发帧的纳秒时间戳；首次帧前为零，不表示设备的固定刷新周期。
     */
    override val frameTimeNanos: Long get() = frameTimeNanosAtomic.get()

    /**
     * 读取本实例累计派发的帧数；start/stop 不清零，首次派发时由零递增。
     */
    override val frameCount: Long get() = frameCountAtomic.get()

    /**
     * 在状态锁内追加持续订阅，null 为无操作；同一回调多次注册会保留多个条目。
     * 当前未运行时启动调度；移除一次只消耗一个匹配条目，调用方需管理注册次数。
     */
    @Synchronized
    override fun postFrameCallback(callback: TickScheduler.FrameCallback?) {
        if (callback == null) return
        callbacks.add(callback)
        if (!running) start()
    }

    /**
     * 在状态锁内移除第一个匹配订阅，null 或未知回调不改变队列。
     * 不撤回本帧已取得的回调快照；队列变空后的自动停止由帧尾逻辑完成。
     */
    @Synchronized
    override fun removeFrameCallback(callback: TickScheduler.FrameCallback?) {
        if (callback == null) return
        callbacks.remove(callback)
    }

    /**
     * 在状态锁内将调度设为运行，并尝试请求下一帧；已运行时保持幂等。
     * 首次成功获取帧源的线程成为固定所属线程；获取失败时本次不排帧，可经 stop/start 重试。
     */
    @Synchronized
    override fun start() {
        if (running) return
        running = true
        scheduleNextFrame()
    }

    /**
     * 暂停后续帧请求，但保留订阅和已固定的帧源，随后可用 start 恢复。
     * 不终止线程，不撤回正在派发的当前快照；已排队的帧到达时会检查运行标志。
     */
    @Synchronized
    override fun stop() {
        running = false
    }

    /**
     * 在共享状态锁下向固定 Choreographer 请求一次帧回调。
     * 停止状态、已有待派发请求或暂时无法获取帧源时直接返回，防止同一周期重复排帧。
     */
    @Synchronized
    private fun scheduleNextFrame() {
        if (!running || pulsePosted) return
        val choreo = choreographer ?: return
        pulsePosted = true
        choreo.postFrameCallback(frameCallback)
    }

    /**
     * 先发布帧计数和纳秒时间戳，再按当前队列快照逐项调用订阅者。
     * 业务回调不持有状态锁，允许重入和其他线程注册；每项异常独立捕获，不中断其余派发。
     */
    private fun tick(frameTimeNanos: Long) {
        frameCountAtomic.incrementAndGet()
        frameTimeNanosAtomic.set(frameTimeNanos)

        for (cb in callbacks.toTypedArray()) {
            runCatching { cb.doFrame(frameTimeNanos) }
        }
    }
}
