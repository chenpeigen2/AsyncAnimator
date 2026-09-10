package com.asyncanimator.anim

import android.graphics.RectF
import com.asyncanimator.core.LogUtils
import com.asyncanimator.thread.Executors
import com.asyncanimator.thread.LooperExecutor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 矩形动画的主线程生命周期协调器，将每轮驱动固定到一个所有者，并区分逻辑结束与实际结束。
 * 构造只保存动画句柄、可空驱动和可选执行器；执行器按需解析，空驱动可登记信息但不能播放。
 * 几何推进归驱动所有，监听集合和逻辑标记在主线程维护，跨线程状态通过volatile/原子门发布。
 */
internal class RectAnimationLifecycle(
    private val animation: CustomRectFSpringAnim,
    private val driver: CustomRectFSpringAnim.Driver?,
    mainExecutor: LooperExecutor?,
    animExecutor: LooperExecutor?
) {
    private val main by lazy { mainExecutor ?: Executors.MAIN_EXECUTOR }
    private val anim by lazy { animExecutor ?: Executors.ANIM_CONTROL_EXECUTOR }
    /**
     * 一轮运行的首个停止原因：CANCEL保留当前位置，END要求驱动完成到终点。
     * 原因一旦写入不被后续请求替换，用于协调先通知逻辑结束、后停止物理驱动的顺序。
     */
    private enum class Stop { CANCEL, END }
    /**
     * 一次运行的状态容器，构造时固定代次、动画标识、驱动执行器及可空结束钩子。
     * 驱动启动/停止和物理上报跨线程可见，逻辑结束与取消标记在主线程维护；两个原子门分别防止重复停止和重复物理完成。
     */
    private class Run(val id: Long, val animationId: Int, val owner: LooperExecutor,
                      var actualEnd: (() -> Unit)?) {
        @Volatile var driverStarted = false
        @Volatile var stop: Stop? = null
        val stopDispatched = AtomicBoolean(false)
        val physicalReported = AtomicBoolean(false)
        var logicalEnded = false
        var cancelled = false
        /**
         * 以本轮启动时固定的动画标识、递增运行编号及当前取消状态构造不可变事件。
         * 在主线程派发时调用，不复用可变载荷，后续运行不会重新标记已经发出的事件。
         */
        fun event() = CustomRectFSpringAnim.Event(animationId, id, cancelled)
    }

    private val listeners = linkedSetOf<CustomRectFSpringAnim.Listener>()
    @Volatile private var current: Run? = null
    @Volatile private var disposed = false
    @Volatile private var serial = 0L
    private var startAsync = driver?.supportsAnimationThread == true
    @Volatile private var configuredAnimationId = -1
    /**
     * 本句柄最近一次有效反转确认的标记，只有主线程收到驱动完成确认后置true。
     * 外部只读，下一轮启动清零；不表示反转后的物理动画已经完成。
     */
    @Volatile var isReverseToOpen = false
        private set
    /**
     * 本轮是否通过仅通知接口首次完成逻辑结束，外部只读并以volatile发布。
     * 下一轮启动清零；该标志不等同于停止驱动，也不因普通cancel/end自动置true。
     */
    @Volatile var isJustNotifyEndCallback = false
        private set
    /**
     * 只要current仍存在即为运行中，包括已逻辑结束但尚未通过实际结束清理屏障的阶段。
     * 读取不切线程；dispose会立即清空current，不代表驱动所有者上的释放任务已经执行完毕。
     */
    val isRunning: Boolean get() = current != null

    /**
     * 下一轮事件的动画标识，读取返回volatile配置值；写入必须在主线程且对象未释放、无活动运行。
     * 启动时复制到Run，禁止在物理结束前修改，防止旧事件被重新标记。
     */
    var animationId: Int
        get() = configuredAnimationId
        set(value) {
            checkConfigurable()
            configuredAnimationId = value
        }

    /**
     * 在主线程且未释放、无活动运行时设置下一轮使用主执行器还是动画执行器。
     * 启用异步要求驱动声明支持动画线程，否则抛出参数异常；不会迁移已经启动的驱动。
     */
    fun setAsyncStart(enabled: Boolean) {
        checkConfigurable()
        require(!enabled || driver?.supportsAnimationThread == true) {
            "Driver does not support animation-thread frames/property writes"
        }
        startAsync = enabled
    }

    /**
     * 在主线程登记去重的生命周期监听器，已释放对象拒绝新增并抛出状态异常。
     * 按插入顺序派发；新增监听不会加入已生成的事件快照，但可以接收后续事件。
     */
    fun addListener(listener: CustomRectFSpringAnim.Listener) {
        checkMain()
        check(!disposed) { "Rect animation is disposed" }
        listeners.add(listener)
    }

    /**
     * 在主线程移除监听器，不存在时无操作，释放后也允许调用。
     * 事件派发会检查当前注册集合，因此可在回调重入时阻止该监听接收快照中尚未交付的事件。
     */
    fun removeListener(listener: CustomRectFSpringAnim.Listener) {
        checkMain()
        listeners.remove(listener)
    }

    /**
     * 切到主线程创建一轮运行，固定动画标识、驱动所有者及可空实际结束钩子；已有运行时忽略本次请求。
     * 已释放或无驱动会抛出异常；先通知onStart，再在固定所有者上启动驱动，启动前的停止请求随后补发。
     * 实际结束到达并完成清理前不允许重启；驱动启动异常不在本层吞掉或自动回滚。
     */
    fun start(onActualEnd: (() -> Unit)?) = onMain {
        check(!disposed) { "Rect animation is disposed" }
        if (current != null) return@onMain
        val engine = requireNotNull(driver) { "Rect playback requires a Driver" }
        val run = Run(++serial, configuredAnimationId, if (startAsync) anim else main, onActualEnd)
        current = run
        isReverseToOpen = false
        isJustNotifyEndCallback = false
        notify(run) { listener, event -> listener.onStart(animation, event) }
        if (!active(run)) return@onMain
        run.owner.execute {
            if (!active(run)) return@execute
            engine.start { reportActualEnd(run) }
            run.driverStarted = true

            dispatchStop(run)
        }
    }

    /**
     * 请求取消当前轮次，尚未逻辑结束时主线程先派发取消及逻辑结束，再由固定所有者通知驱动停止。
     * 无活动运行或已有停止请求时忽略；不承诺帧循环已停止，实际结束仍等待驱动上报。
     */
    fun cancel() = requestStop(Stop.CANCEL)
    /**
     * 请求当前轮次到达终点，主线程先派发逻辑结束但不标为取消。
     * 第一次停止请求胜出，驱动在其所有者线程处理；无活动运行时无操作，物理结束单独等待。
     */
    fun skipToEnd() = requestStop(Stop.END)

    /**
     * 在主线程记录首个停止原因，并在回调前写入取消/逻辑结束状态以抵御重入。
     * 必要时依次通知onCancel和onEnd；已启动驱动交由所有者执行停止，尚未启动则保留请求待启动后消费。
     */
    private fun requestStop(stop: Stop) = onMain {
        val run = current ?: return@onMain
        if (run.stop != null) return@onMain
        run.stop = stop
        run.cancelled = stop == Stop.CANCEL
        if (!run.logicalEnded) {

            run.logicalEnded = true
            if (run.cancelled) notify(run) { listener, event -> listener.onCancel(animation, event) }
            notify(run) { listener, event -> listener.onEnd(animation, event) }
        }
        if (active(run) && run.driverStarted) run.owner.execute { dispatchStop(run) }
    }

    /**
     * 在本轮固定所有者线程将待处理停止请求转交驱动。
     * 仅活动且已启动的运行有效，以原子门确保启动补发与主线程停止投递最多执行一次；驱动异常向外传播。
     */
    private fun dispatchStop(run: Run) {
        if (!active(run) || !run.driverStarted) return
        val stop = run.stop ?: return
        if (!run.stopDispatched.compareAndSet(false, true)) return
        if (stop == Stop.CANCEL) checkNotNull(driver).cancel() else checkNotNull(driver).skipToEnd()
    }

    /**
     * 在主线程仅标记并通知当前运行的逻辑结束，不取消驱动也不释放实际结束屏障。
     * 无运行或已逻辑结束时忽略；成功调用后isJustNotifyEndCallback保持为true，直至下一轮启动。
     */
    fun justNotifyEndCallback() = onMain {
        val run = current ?: return@onMain
        if (run.logicalEnded) return@onMain
        isJustNotifyEndCallback = true
        run.logicalEnded = true
        notify(run) { listener, event -> listener.onEnd(animation, event) }
    }

    /**
     * 以原子门只接收一次有效物理结束，先在驱动所有者清除驱动回调，再回主线程释放current。
     * 终止事件共用一份监听快照：补齐尚未发送的取消/逻辑结束，再通知实际结束，最后调用已消费的结束钩子。
     * 快照避免重入启动的新监听收到旧事件；回调中dispose会阻止后续派发及结束钩子，过期运行全程忽略。
     */
    private fun reportActualEnd(run: Run) {
        if (!active(run) || !run.physicalReported.compareAndSet(false, true)) return

        run.owner.execute {
            if (!active(run)) return@execute
            checkNotNull(driver).clearEndCallback()
            onMain {
                if (!active(run)) return@onMain
                current = null
                val callback = run.actualEnd
                run.actualEnd = null

                val terminalListeners = listeners.toList()
                if (!run.logicalEnded) {
                    run.logicalEnded = true
                    if (run.cancelled) {
                        notify(run, terminalListeners) { listener, event ->
                            listener.onCancel(animation, event)
                        }
                    }
                    notify(run, terminalListeners) { listener, event -> listener.onEnd(animation, event) }
                }
                notify(run, terminalListeners) { listener, event -> listener.onActualEnd(animation, event) }

                if (!disposed) callback?.invoke()
            }
        }
    }

    /**
     * 切到主线程选择当前未停止运行，再在固定所有者上调用可反转驱动重设目标和圆角。
     * 缺少反转能力时抛出UnsupportedOperationException；完成后仅在对象未释放且代次仍一致时回主线程更新类型/标记并调用确认动作。
     * 本层不校验或复制矩形，调用方须提供稳定输入；无运行或已有停止请求时不会调用确认动作。
     */
    fun reverseToOpen(target: RectF, endRadius: Float, afterReverse: () -> Unit) = onMain {
        val run = current ?: return@onMain
        if (run.stop != null) return@onMain
        val engine = driver as? CustomRectFSpringAnim.ReversibleDriver
            ?: throw UnsupportedOperationException("This rect driver cannot retarget geometry")
        run.owner.execute {
            if (!active(run) || run.stop != null) return@execute
            engine.reverseToOpen(target, endRadius)
            onMain {
                if (disposed || serial != run.id) return@onMain
                animation.animType = CustomRectFSpringAnim.AnimType.REVERSE_TO_OPEN
                isReverseToOpen = true
                afterReverse()
            }
        }
    }

    /**
     * 要求主线程，仅清空当前运行附带的实际结束钩子，无活动运行时无操作。
     * 不移除生命周期监听器、不解绑驱动内部回调，也不取消动画。
     */
    fun clearEndCallback() {
        checkMain()
        current?.actualEnd = null
    }

    /**
     * 在主线程幂等释放：使运行代次失效，清空当前运行、结束钩子及全部监听器。
     * 有活动运行时在原所有者上调用驱动dispose，或先清结束回调再cancel；不会为释放主动派发生命周期。
     * 释放不可逆，旧排队事件不再有效；当前无运行时不额外调用驱动释放入口。
     */
    fun dispose() = onMain {
        if (disposed) return@onMain
        disposed = true
        serial++
        val run = current
        current = null
        run?.actualEnd = null
        listeners.clear()
        if (run != null) run.owner.execute {
            if (driver is CustomRectFSpringAnim.DisposableDriver) driver.dispose()
            else {
                checkNotNull(driver).clearEndCallback()
                driver.cancel()
            }
        }
    }

    /**
     * 在主线程为本次通知构造事件，按快照顺序交付仍注册的监听器；默认取当前集合副本。
     * 每项前检查释放状态，回调增删不会破坏遍历；捕获并记录Exception以继续生命周期，但不吞掉Error等其他Throwable。
     */
    private fun notify(
        run: Run,
        snapshot: List<CustomRectFSpringAnim.Listener> = listeners.toList(),
        action: (CustomRectFSpringAnim.Listener, CustomRectFSpringAnim.Event) -> Unit
    ) {
        val event = run.event()
        for (listener in snapshot) {
            if (disposed) return
            if (listener in listeners) {

                try { action(listener, event) }
                catch (error: Exception) {
                    LogUtils.i(
                        "RectAnimationLifecycle",
                        "Listener failed: ${error.javaClass.simpleName}: ${error.message}"
                    )
                }
            }
        }
    }

    /**
     * 检查对象尚未释放且current与给定Run是同一实例，用于跨执行器丢弃过期任务。
     * 只读取状态，不改变代次或运行；身份检查不能撤回已经执行中的业务回调。
     */
    private fun active(run: Run) = !disposed && current === run
    /**
     * 已经处于主执行器所属线程时立即执行动作，否则以异步消息投递。
     * 不等待动作完成，不捕获动作异常；无Handler执行器的具体降级行为由LooperExecutor负责。
     */
    private fun onMain(action: () -> Unit) {
        if (main.isCurrentThread) action() else main.postAsync(action)
    }
    /**
     * 验证当前线程属于主执行器，违反配置/监听器线程约束时抛出IllegalStateException。
     * 本方法只做前置检查，不会自动投递或切换到主线程。
     */
    private fun checkMain() {
        check(main.isCurrentThread) {
            "Rect configuration/listeners belong to the main thread"
        }
    }
    /**
     * 依次确认主线程、未释放以及没有尚待物理结束的运行，否则抛出状态异常。
     * 供动画标识和执行器选择的修改使用，逻辑结束但尚未实际结束仍禁止重新配置。
     */
    private fun checkConfigurable() {
        checkMain()
        check(!disposed) { "Rect animation is disposed" }
        check(current == null) { "Rect owner/id cannot change before actual end" }
    }
}
