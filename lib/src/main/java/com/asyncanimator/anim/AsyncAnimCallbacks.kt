package com.asyncanimator.anim

import android.animation.Animator
import com.asyncanimator.api.PublicApi
import com.asyncanimator.core.LogUtils
import com.asyncanimator.core.Trace
import com.asyncanimator.playback.NullableAnimatorListener
import com.asyncanimator.playback.NullableAnimatorListenerAdapter
import com.asyncanimator.thread.Executors

/**
 * 线程安全的监听注册容器与主线程生命周期派发器。
 * 注册表和事件代次受同一锁保护，业务回调在锁外执行；主线程外投递使用异步消息。
 * 逻辑结束与实际结束分开派发，后者仅通知具备相应入口的监听器，不自动取消底层动画。
 */
@PublicApi
class AsyncAnimCallbacks {

    private val listenerLock = Any()
    private var generation = 0L
    private var animType: CustomRectFSpringAnim.AnimType? = null

    /**
     * 在监听锁内更新可选动画类型，供随后创建的事件记录诊断上下文。
     * 不选择动画引擎，不改变监听器集合或事件代次，已排队事件继续使用投递前捕获的类型。
     */
    @PublicApi
    fun setAnimType(type: CustomRectFSpringAnim.AnimType) = synchronized(listenerLock) {
        animType = type
    }

    private val animListeners = mutableListOf<NullableAnimatorListener?>()

    /**
     * 随后创建事件使用的诊断动画标识，初始为 -1，通过 volatile 发布。
     * 事件投递前会捕获该值，之后的修改不会重新标记已经排队的旧事件。
     */
    @Volatile
    internal var animationId = -1

    /**
     * 在监听锁内登记非空监听器，相等实例已存在时不重复追加。
     * 允许在回调中重入注册；当前已取得的派发快照不会增加新成员，后续交付才会看到它。
     */
    @PublicApi
    fun addListener(l: NullableAnimatorListener?) {
        synchronized(listenerLock) {
            if (l != null && !animListeners.contains(l)) animListeners.add(l)
        }
    }

    /**
     * 在监听锁内把第一个匹配条目置为空槽，不存在时无操作。
     * 之后取快照会统一压缩空槽；本次调用不能撤回已经进入派发快照的监听器。
     */
    @PublicApi
    fun removeListener(l: NullableAnimatorListener?) {
        synchronized(listenerLock) {
            val idx = animListeners.indexOf(l)
            if (idx >= 0) animListeners[idx] = null
        }
    }

    /**
     * 在监听锁内清空当前注册表，但不改变事件代次或诊断标识。
     * 排队事件在交付时重新取监听快照，因此可能通知清空后新注册的监听器；需要丢弃旧事件时使用 dispose。
     */
    internal fun clearListeners() = synchronized(listenerLock) { animListeners.clear() }

    /**
     * 递增事件代次、清空监听器并重置动画标识与类型，释放本轮注册状态。
     * 排队旧事件和重入释放后尚未执行的旧快照项会被丢弃；已开始的回调无法撤回。
     * 容器本身允许随后重新注册，但本方法不取消底层动画，也不使一次性动画包装重新可用。
     */
    @PublicApi
    fun dispose() = synchronized(listenerLock) {
        generation++
        animListeners.clear()
        animationId = -1
        animType = null
    }

    /**
     * 为指定非空动画创建开始事件，捕获当前代次和诊断身份后安排主线程交付。
     * 不修改底层动画或容器状态；主线程内可立即回调，跨线程时使用异步消息。
     */
    internal fun onAnimationStart(animator: Animator) =
        dispatch("AsyncAnimStart-") { it.onAnimationStart(animator) }

    /**
     * 为指定非空动画派发逻辑结束事件，遵守同一主线程、代次和监听快照协议。
     * 不合成实际结束，也不在发送后自动清空注册表；回调异常由交付路径向外传播。
     */
    internal fun onAnimationEnd(animator: Animator) =
        dispatch("AsyncAnimEnd-") { it.onAnimationEnd(animator) }

    /**
     * 为指定非空动画派发取消事件，并在交付前更新适配器的动画标识。
     * 取消不自动伴随结束事件或资源释放；底层驱动及调用方仍需负责完整生命周期。
     */
    internal fun onAnimationCancel(animator: Animator) =
        dispatch("AsyncAnimCancel-") { it.onAnimationCancel(animator) }

    /**
     * 在主线程向 ActualEndAnimListener 类型的监听器派发实际结束，并设置捕获的动画标识。
     * 其他监听器跳过；仅记录诊断日志，不额外创建逻辑事件的 Trace 区段，也不推断底层驱动状态。
     */
    internal fun onAnimActualEnd(animator: Animator) {
        withListenersOnMain(null) { listener, id ->
            if (listener is ActualEndAnimListener) {
                listener.animationId = id
                listener.onAnimActualEnd(animator)
            }
        }
    }

    /**
     * 在监听锁内压缩全部空槽并返回当前非空注册项的独立列表快照。
     * 调用方可在锁外迭代；后续集合增删不会修改这份快照，但 dispose 仍可通过代次检查中止派发。
     */
    private fun getListeners(): List<NullableAnimatorListener> = synchronized(listenerLock) {
        animListeners.removeAll { it == null }
        animListeners.filterNotNull()
    }

    /**
     * 投递前在锁内捕获代次、标识和类型，到主线程交付时再验证代次并取得最新监听快照。
     * 动作逐项在锁外执行；普通事件包围实际交付的诊断区段，异常退出时仍结束区段。
     * @param traceTagPrefix 普通事件的区段前缀；null 表示仅保留实际结束日志，不创建区段。
     * @param action 接收监听器与捕获标识的业务调用，异常不逐项吞掉。
     */
    private fun withListenersOnMain(
        traceTagPrefix: String?,
        action: (NullableAnimatorListener, Int) -> Unit
    ) {
        val (eventGeneration, id, type) = synchronized(listenerLock) {
            Triple(generation, animationId, animType)
        }
        val eventName = "${traceTagPrefix ?: "ActualEnd-"}$id type=${type ?: "UNSPECIFIED"}"
        runOnMainThread {
            val listeners = synchronized(listenerLock) {
                if (eventGeneration != generation) return@runOnMainThread
                getListeners()
            }

            /**
             * 记录当前事件的监听数量，并依次通知本轮快照中的监听器。
             * 每项执行前重新检查代次，重入 dispose 后停止余下派发；业务调用时不持有监听锁，异常直接退出。
             */
            fun notifyListeners() {
                LogUtils.i("AsyncAnimCallbacks", "$eventName listeners=${listeners.size}")
                for (listener in listeners) {
                    if (synchronized(listenerLock) { eventGeneration != generation }) return
                    action(listener, id)
                }
            }
            if (traceTagPrefix != null) {
                Trace.section(Trace.TAG_VIEW, eventName, ::notifyListeners)
            } else notifyListeners()
        }
    }

    /**
     * 通过主线程交付入口处理普通生命周期事件，并先向适配器写入该事件捕获的动画标识。
     * 非适配器监听仍正常接收动作；动作不在监听锁内执行，诊断区段由外层统一管理。
     */
    private fun dispatch(traceTagPrefix: String, action: (NullableAnimatorListener) -> Unit) {
        withListenersOnMain(traceTagPrefix) { listener, id ->
            (listener as? NullableAnimatorListenerAdapter)?.animationId = id
            action(listener)
        }
    }

    /**
     * 已在主执行器所属线程时立即执行动作，否则以异步消息投递到主线程。
     * 异步标记允许消息越过同步屏障，不代表新建线程或阻塞等待；无 Handler 的降级模式由执行器决定。
     */
    private fun runOnMainThread(action: () -> Unit) {
        val exec = Executors.MAIN_EXECUTOR
        if (exec.isCurrentThread) action() else exec.postAsync(action)
    }
}
