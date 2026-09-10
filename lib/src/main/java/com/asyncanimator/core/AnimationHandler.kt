package com.asyncanimator.core

/**
 * 线程内的动画回调注册表与帧源桥接器。
 * 默认实例按线程隔离；注册、移除、换源和派发应由同一所属线程串行调用。
 * 帧源持续订阅，动画需主动移除回调；本类不替换平台或 AndroidX 动画内部的调度器。
 * @param scheduler 可选的初始帧源，未提供时惰性创建公开 Choreographer 调度器。
 */
internal class AnimationHandler(scheduler: TickScheduler? = null) {

    /**
     * 接收毫秒级帧时间的动画更新接口。
     * 回调由所属处理器串行调用，其 Boolean 结果不表示自动取消订阅。
     */
    fun interface AnimationFrameCallback {

        /**
         * 处理所属线程的一帧动画更新。
         * @param frameTimeMs 当前帧时间戳，单位为毫秒；由帧源的纳秒值截断而来。
         * @return 返回值不参与调度决策；动画结束时必须显式移除自己的回调。
         */
        fun doAnimationFrame(frameTimeMs: Long): Boolean
    }

    private var schedulerHolder = TickSchedulerHolder(scheduler)

    private var schedulerGeneration = 0L
    private var tickCallback = tickCallbackFor(schedulerGeneration)

    /**
     * 创建携带指定调度代次的帧回调。
     * 旧帧源可能已复制待派发的回调；只有代次仍与当前帧源一致时才进入本实例的帧循环。
     */
    private fun tickCallbackFor(generation: Long) = TickScheduler.FrameCallback { time ->

        if (generation == schedulerGeneration) onTick(time)
    }

    private val animationCallbacks = mutableListOf<AnimationFrameCallback?>()

    private var listDirty = false

    /**
     * 取得本处理器当前使用的帧源，首次读取可能创建默认实例，但不会启动帧循环。
     */
    val scheduler: TickScheduler get() = schedulerHolder.get()

    /**
     * 读取当前非空回调槽数量；懒删除立即影响计数，无需等待本帧末尾压缩。
     */
    val callbackSize: Int
        get() = animationCallbacks.count { it != null }

    /**
     * 在所属线程注册持续执行的动画回调；null 和已存在的实例不会重复入列。
     * 列表完全为空时先启动帧源并订阅本实例的帧入口；列表仅含懒删除槽时仍沿用尚未清理的订阅。
     */
    fun addAnimationFrameCallback(callback: AnimationFrameCallback?) {
        if (callback == null) return
        if (animationCallbacks.isEmpty()) {

            scheduler.start()
            scheduler.postFrameCallback(tickCallback)
        }
        if (!animationCallbacks.contains(callback)) {
            animationCallbacks.add(callback)
        }
    }

    /**
     * 在所属线程取消指定回调，null 或不存在的实例不产生变化。
     * 通过置空而非立即删除避免遍历下标错位；本帧尚未执行的对应槽立即失效，帧末统一压缩。
     */
    fun removeCallback(callback: AnimationFrameCallback?) {
        if (callback == null) return
        val idx = animationCallbacks.indexOf(callback)
        if (idx >= 0) {
            animationCallbacks[idx] = null
            listDirty = true
        }
    }

    /**
     * 将帧源的纳秒时间戳截断为毫秒，派发动画回调并压缩懒删除槽。
     * 列表清空后仅退订本实例的帧入口，不停止共享帧源中的其他订阅者。
     */
    private fun onTick(frameTimeNanos: Long) {
        val frameTimeMs = frameTimeNanos / 1_000_000L
        doAnimationFrame(frameTimeMs)
        cleanUpList()
        if (animationCallbacks.isEmpty()) scheduler.removeFrameCallback(tickCallback)
    }

    /**
     * 按注册顺序更新当前列表，每次迭代重新读取长度，因此同帧追加的回调也可被执行。
     * 跳过已移除的空槽，忽略回调返回值；逐项捕获异常，使一个回调失败不影响后续回调。
     */
    private fun doAnimationFrame(frameTimeMs: Long) {

        var i = 0
        while (i < animationCallbacks.size) {
            val cb = animationCallbacks[i]
            i++
            if (cb != null) runCatching { cb.doAnimationFrame(frameTimeMs) }
        }
    }

    /**
     * 仅在存在懒删除标记时清理全部空槽，并复位清理标记。
     * 不主动取消帧订阅，订阅是否需要移除由本帧收尾逻辑根据清理后的列表决定。
     */
    private fun cleanUpList() {
        if (!listDirty) return
        animationCallbacks.removeAll { it == null }
        listDirty = false
    }

    /**
     * 在当前所属线程切换帧源；相同实例直接返回，不重置订阅或代次。
     * 先退订旧入口，再递增代次并创建新入口；仍有活跃回调时启动并订阅新帧源。
     * 方法锁仅保护换源过程，不使整个动画列表支持任意线程并发修改；不承诺两帧源相位连续。
     */
    @Synchronized
    private fun swapScheduler(s: TickScheduler) {
        val old = schedulerHolder.get()
        if (old === s) return
        old.removeFrameCallback(tickCallback)

        schedulerGeneration++
        tickCallback = tickCallbackFor(schedulerGeneration)
        schedulerHolder = TickSchedulerHolder(s)
        if (callbackSize > 0) {
            s.start()
            s.postFrameCallback(tickCallback)
        }
    }

    /**
     * 缓存注入或惰性创建的帧源；替换帧源时由外层整体替换持有者。
     * 构造本对象不会启动调度，也不会访问 Choreographer。
     */
    private class TickSchedulerHolder(private var scheduler: TickScheduler?) {

        /**
         * 返回注入的帧源；未注入时首次创建并缓存 Choreographer 调度器。
         * 使用同步保护惰性创建，仅获取实例不请求帧，也不绑定 Choreographer 所属线程。
         */
        @Synchronized
        fun get(): TickScheduler =
            scheduler ?: ChoreographerTickScheduler().also { scheduler = it }
    }

    companion object {

        private val threadLocalHandler = ThreadLocal<AnimationHandler>()

        /**
         * 进程级测试替代实例，非 null 时优先于所有线程的默认实例。
         * volatile 只保证引用发布，不保护替代实例的列表；测试应串行使用并在结束时恢复。
         */
        @Volatile
        var testHandler: AnimationHandler? = null

        /**
         * 返回测试替代实例或当前线程的缓存实例，没有缓存时创建并保存。
         * 该读取不启动帧循环；线程间默认不会共享动画回调列表。
         */
        val instance: AnimationHandler
            get() = testHandler ?: threadLocalHandler.get()
                ?: AnimationHandler(ChoreographerTickScheduler()).also(threadLocalHandler::set)

        /**
         * 在当前线程首次取得默认处理器之前安装非空帧源。
         * 动画线程可在 Looper 准备期间调用，以保证首条业务消息执行前调度内核已存在。
         * @param scheduler 该线程将使用的帧源；null 会抛出参数异常。
         * @throws IllegalStateException 已有线程处理器或启用了全局测试替代实例时拒绝安装。
         */
        fun installThreadScheduler(scheduler: TickScheduler?) {
            requireNotNull(scheduler) { "TickScheduler must not be null" }
            check(testHandler == null) { "Cannot install a thread scheduler while testHandler overrides instance" }
            check(threadLocalHandler.get() == null) {
                "AnimationHandler already instantiated on this thread; use replaceThreadScheduler instead"
            }
            threadLocalHandler.set(AnimationHandler(scheduler))
        }

        /**
         * 替换当前线程处理器的帧源，尚无处理器时会先取得默认实例再切换。
         * 已开始的当前帧遍历继续完成，迟到的旧代次脉冲被丢弃；不会停止旧帧源上的其他订阅。
         * @throws IllegalArgumentException 帧源为 null。
         * @throws IllegalStateException 全局测试替代实例正在生效。
         */
        fun replaceThreadScheduler(scheduler: TickScheduler?) {
            requireNotNull(scheduler) { "TickScheduler must not be null" }
            check(testHandler == null) { "Cannot replace a thread scheduler while testHandler overrides instance" }
            instance.swapScheduler(scheduler)
        }

        /**
         * 返回当前生效处理器的活跃回调数量；测试替代实例生效时不再代表当前线程独有的数量。
         */
        val animationCount: Int get() = instance.callbackSize
    }
}
