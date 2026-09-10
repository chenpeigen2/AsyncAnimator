package com.asyncanimator.core


/**
 * 自有帧调度内核，主要参考 OPPO vendored androidx.core.animation.AnimationHandler。
 * 不是平台隐藏 android.animation.AnimationHandler，也不替换 AndroidX 动画的内部 handler。
 * 逐项证据及与 dynamicanimation 延迟/时钟语义的差异见 review/vs-oppo-14。
 *
 * - ThreadLocal 提供默认每线程实例；注册、删除、换源和帧派发须在各自 owner 上串行执行。
 *   局部 synchronized 与 volatile 测试入口不使整个 handler 支持跨线程共享写入。
 * - 第一次注册订阅 scheduler；逐项重读列表长度，同帧新增可见，删除立刻置 null 跳过。
 * - 帧结束清理 null 槽；没有活跃回调时仅退订自己的 tick，不停止帧源的其他订阅者。
 * - scheduler 传入纳秒时间戳，本类截断为毫秒传给业务；Boolean 返回值不自动取消订阅。
 *
 * 本库自己的选择：TickScheduler 持续订阅而非 OEM provider 的逐帧重投；回调异常隔离；
 * 带代次的换源协议。未实现 dynamicanimation 的 delayed-callback map、dispatcher 或
 * ObjectAnimator auto-cancel，不将多个不同内核声称为完整合并。
 */
internal class AnimationHandler(scheduler: TickScheduler? = null) {

    /** 每帧回调契约：与原厂一致，返回值被忽略；动画结束必须显式 removeCallback。 */
    fun interface AnimationFrameCallback {
        fun doAnimationFrame(frameTimeMs: Long): Boolean
    }

    /** TickScheduler 持有者（懒构造）。可被 [replaceThreadScheduler] 替换。 */
    private var schedulerHolder = TickSchedulerHolder(scheduler)

    private var schedulerGeneration = 0L
    private var tickCallback = tickCallbackFor(schedulerGeneration)

    private fun tickCallbackFor(generation: Long) = TickScheduler.FrameCallback { time ->
        // A detached provider may already have copied its callback for dispatch.
        if (generation == schedulerGeneration) onTick(time)
    }

    /** 当前线程上活跃的 animation callbacks。懒删除（null 槽）。 */
    private val animationCallbacks = mutableListOf<AnimationFrameCallback?>()

    /** 懒删除标志：true 表示本帧末尾需要 cleanUpList。 */
    private var listDirty = false

    /** 当前线程的 TickScheduler（懒构造：首次访问时注入默认实现）。 */
    val scheduler: TickScheduler get() = schedulerHolder.get()

    /** 当前帧回调数（不计 null 槽）。 */
    val callbackSize: Int
        get() = animationCallbacks.count { it != null }

    // ──── 注册/取消 ────────────────────────────────────────────────

    /**
     * 注册一个 animation callback。
     * 若列表为空，会同时调用 [TickScheduler.start] 启动调度循环（如果是首次注册）。
     */
    fun addAnimationFrameCallback(callback: AnimationFrameCallback?) {
        if (callback == null) return
        if (animationCallbacks.isEmpty()) {
            // 首次注册：确保 scheduler 已就绪，并注册 self-pulse
            scheduler.start()
            scheduler.postFrameCallback(tickCallback)
        }
        if (!animationCallbacks.contains(callback)) {
            animationCallbacks.add(callback)
        }
    }

    /**
     * 取消注册。懒删除：置 null + listDirty=true（避免迭代中修改）。
     */
    fun removeCallback(callback: AnimationFrameCallback?) {
        if (callback == null) return
        val idx = animationCallbacks.indexOf(callback)
        if (idx >= 0) {
            animationCallbacks[idx] = null
            listDirty = true
        }
    }

    // ──── 帧循环（每 tick 调一次）────────────────────────────────────

    /**
     * TickScheduler 每帧调一次。这是原厂 onAnimationFrame 的入口（对比见 review 04）。
     *
     * 顺序：分发所有 callback → cleanUpList 压缩 null 槽 → 若还有 callback 则由 TickScheduler 续帧
     * 所有动画移除后退订 self-pulse；ChoreographerTickScheduler 无订阅者时自行停止。
     */
    private fun onTick(frameTimeNanos: Long) {
        val frameTimeMs = frameTimeNanos / 1_000_000L // nanos → ms（对齐 AndroidX 行为）
        doAnimationFrame(frameTimeMs)
        cleanUpList()
        if (animationCallbacks.isEmpty()) scheduler.removeFrameCallback(tickCallback)
    }

    /**
     * 顺序遍历 animationCallbacks，跳过 null 槽，调每个 callback 的 doAnimationFrame。
     * 对应原厂 doAnimationFrame（review 04）。
     */
    private fun doAnimationFrame(frameTimeMs: Long) {
        // 对齐原厂 androidx.core.animation.AnimationHandler:130-137：
        // 每轮重读 size，本帧内新增的 callback 当帧可见；null 槽跳过。
        // 异常隔离是 lib 新增语义：原厂单 callback 异常会中断整帧并沿 provider 上抛。
        var i = 0
        while (i < animationCallbacks.size) {
            val cb = animationCallbacks[i]
            i++
            if (cb != null) runCatching { cb.doAnimationFrame(frameTimeMs) }
        }
    }

    /** 清理 null 槽。仅当 listDirty=true 才执行（性能优化）。 */
    private fun cleanUpList() {
        if (!listDirty) return
        animationCallbacks.removeAll { it == null }
        listDirty = false
    }

    @Synchronized
    private fun swapScheduler(s: TickScheduler) {
        val old = schedulerHolder.get()
        if (old === s) return
        old.removeFrameCallback(tickCallback)
        // Do not stop unrelated subscribers or interrupt the current callback traversal.
        // ChoreographerTickScheduler stops itself when its subscription list becomes empty.
        schedulerGeneration++
        tickCallback = tickCallbackFor(schedulerGeneration)
        schedulerHolder = TickSchedulerHolder(s)
        if (callbackSize > 0) {
            s.start()
            s.postFrameCallback(tickCallback)
        }
    }

    /** 内部 Holder：TickScheduler 懒构造。 */
    private class TickSchedulerHolder(private var scheduler: TickScheduler?) {

        @Synchronized
        fun get(): TickScheduler =
            scheduler ?: ChoreographerTickScheduler().also { scheduler = it }
    }

    companion object {

        // ──── 单例管理（ThreadLocal）─────────────────────────────────────

        private val threadLocalHandler = ThreadLocal<AnimationHandler>()

        /** 进程级测试覆盖：非 null 时所有线程的 [instance] 都返回它。
         * 测试应串行使用并在 finally 恢复；volatile 只发布引用，不保护被共享实例的可变列表。
         */
        @Volatile
        var testHandler: AnimationHandler? = null

        /** 当前线程的 AnimationHandler 单例（测试 hook 优先）。 */
        val instance: AnimationHandler
            get() = testHandler ?: threadLocalHandler.get()
                ?: AnimationHandler(ChoreographerTickScheduler()).also(threadLocalHandler::set)

        /**
         * 为当前线程安装自定义 TickScheduler。
         *
         * 对应"独立动画线程"方案：独立线程在 onLooperPrepared() 时调用，
         * 在该线程第一次业务访问之前指定自有 handler 的帧源。
         * AnimationControlThread 当前显式安装的实现与默认实现同为 ChoreographerTickScheduler；
         * 安装动作建立确定的初始化边界，不表示切换为平台/SF 帧源。
         *
         * 必须在该线程首次访问 [instance] 之前调用；重复安装或启用全局 testHandler 时明确抛异常。
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
         * 强制替换当前线程 AnimationHandler 的 TickScheduler（demo / 实验用）。
         *
         * 与 [installThreadScheduler] 的区别：本方法在线程的 handler 已存在时也生效。
         * 行为：退订自己的旧回调 → 新代次 → 若仍有活跃动画则在新 scheduler 上重建回路。
         * 当前帧的动画遍历继续完成；迟到旧脉冲失效。不停止旧帧源的其他订阅者。
         * 下一帧时机由新 scheduler 决定，不承诺两个不同帧源无缝相位对齐。
         */
        fun replaceThreadScheduler(scheduler: TickScheduler?) {
            requireNotNull(scheduler) { "TickScheduler must not be null" }
            check(testHandler == null) { "Cannot replace a thread scheduler while testHandler overrides instance" }
            instance.swapScheduler(scheduler)
        }

        /** 当前 instance 的非 null 回调数；默认是当前线程，testHandler 非空时计数该测试实例。 */
        val animationCount: Int get() = instance.callbackSize
    }
}
