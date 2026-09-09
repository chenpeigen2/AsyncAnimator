package com.asyncanimator.core.anim

import com.asyncanimator.core.scheduler.ChoreographerTickScheduler
import com.asyncanimator.core.scheduler.TickScheduler

/**
 * AnimationHandler — 核心动画调度中枢。
 *
 * 对应 Android 平台 `androidx.core.animation.AnimationHandler`（简化版），逐项对比见 `docs/review/04-frame-spring-continuation.md`。
 *
 * 核心不变式（与原版完全一致）：
 *
 *  - **ThreadLocal 单例**：每线程一份，避免多线程竞争
 *  - **懒注册**：首次 addAnimationFrameCallback 才向 TickScheduler 注册
 *  - **自维持回路**：每次 tick 后若还有 callback 活跃，则由 TickScheduler 内部自己续帧
 *  - **懒删除**：removeCallback 置 null + listDirty=true，cleanUpList 在下一帧才真正压缩
 *
 * 本类不直接持有 TickScheduler 实例，而是用 [TickSchedulerHolder] 关联（让 TickScheduler 实例可替换/可测）。
 * 这是和原 AndroidX 实现的细微差异——原版用 FrameCallbackProvider14/16 包装 Choreographer，
 * 这里用统一的 TickScheduler（统一抽象）。
 */
internal class AnimationHandler(scheduler: TickScheduler? = null) {

    /** 每帧回调契约：返回 true 表示本动画已结束，Handler 据此调度续帧。 */
    fun interface AnimationFrameCallback {
        fun doAnimationFrame(frameTimeMs: Long): Boolean
    }

    /** TickScheduler 持有者（懒构造）。可被 [replaceThreadScheduler] 替换。 */
    private var schedulerHolder = TickSchedulerHolder(scheduler)

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
            scheduler.postFrameCallback(::onTick)
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
     * （这里续帧逻辑由 ScheduledTickScheduler.scheduleAtFixedRate 自动完成，
     * 不需要手动 postFrameCallback）。
     */
    private fun onTick(frameTimeNanos: Long) {
        val frameTimeMs = frameTimeNanos / 1_000_000L // nanos → ms（对齐 AndroidX 行为）
        doAnimationFrame(frameTimeMs)
        cleanUpList()
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
        schedulerHolder.get().stop()
        schedulerHolder = TickSchedulerHolder(s)
        if (callbackSize > 0) {
            s.start()
            s.postFrameCallback(::onTick)
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

        /** 测试 hook：设置后 [instance] 永远返回它。 */
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
         * 让该线程的 AnimationHandler（ThreadLocal 单例）用绑定本线程 Looper 的帧调度器，
         * 而不是默认的 ScheduledTickScheduler（共享 JVM 调度线程）。
         *
         * 必须在该线程首次访问 [instance] 之前调用；若已被创建则不生效。
         */
        fun installThreadScheduler(scheduler: TickScheduler?) {
            if (testHandler != null) return
            if (scheduler == null) return
            if (threadLocalHandler.get() == null) {
                threadLocalHandler.set(AnimationHandler(scheduler))
            }
        }

        /**
         * 强制替换当前线程 AnimationHandler 的 TickScheduler（demo / 实验用）。
         *
         * 与 [installThreadScheduler] 的区别：本方法在线程的 handler 已存在时也生效。
         * 行为：停掉旧 scheduler 的脉冲 → 换新 → 若仍有活跃动画则在新 scheduler 上重建回路。
         */
        fun replaceThreadScheduler(scheduler: TickScheduler?) {
            if (testHandler != null) return
            if (scheduler == null) return
            instance.swapScheduler(scheduler)
        }

        /** 当前线程活跃动画数。 */
        val animationCount: Int get() = instance.callbackSize
    }
}
