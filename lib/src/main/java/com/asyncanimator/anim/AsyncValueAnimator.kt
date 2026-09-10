package com.asyncanimator.anim

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import com.asyncanimator.api.PublicApi
import com.asyncanimator.playback.NullableAnimatorListener
import com.asyncanimator.thread.Executors
import com.asyncanimator.thread.LooperExecutor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 为 start/cancel/end 增加固定 Looper 转发和最终释放协议的 ValueAnimator。
 * 业务监听经 AsyncAnimCallbacks 回到主线程，原生更新监听仍在原生动画线程执行。
 * 只保证这些生命周期入口的调度，不使继承的所有属性方法线程安全；业务结束通知为一次性。
 */
@PublicApi
class AsyncValueAnimator : ValueAnimator() {

    private val lifecycleLock = Any()
    @Volatile private var disposed = false
    private var owner: LooperExecutor? = null
    private var configuredExecutor: LooperExecutor = Executors.MAIN_EXECUTOR

    /**
     * 生命周期命令使用的执行器，默认主线程；读取与赋值在生命周期锁内完成。
     * 首次 start/cancel/end 或 dispose 会绑定所属执行器，之后只允许设置同一实例；释放后禁止赋值。
     */
    @PublicApi
    var executor: LooperExecutor
        get() = synchronized(lifecycleLock) { configuredExecutor }
        set(value) = synchronized(lifecycleLock) {
            check(!disposed) { "Animator is disposed" }
            check(owner == null || owner === value) { "Animator executor is already bound" }
            configuredExecutor = value
        }

    /**
     * 业务监听注册与主线程派发容器，不等同于平台 Animator 自带的原生监听集合。
     * 最终释放会清空其代次和注册项，宿主不应在释放后直接借此容器重新注册。
     */
    val asyncAnimCallbacks = AsyncAnimCallbacks()

    private val isEnd = AtomicBoolean(false)

    /**
     * 构造时安装一个原生监听器，把第一次结束前的生命周期事件转交业务容器。
     * 该监听器使用 isEnd 保证业务结束只消费一次，不在后续原生 start 时重新布防。
     */
    init {
        addListener(object : AnimatorListenerAdapter() {

            /**
             * 当内部尚未消费第一次结束事件时，将平台取消通知交给业务派发容器。
             * 第一次结束之后忽略后续取消；此监听器只负责转发，不再次取消底层动画。
             */
            override fun onAnimationCancel(a: Animator) {
                if (!isEnd.get()) asyncAnimCallbacks.onAnimationCancel(a)
            }

            /**
             * 通过原子比较把第一次平台结束事件消费并转发到业务监听容器。
             * 后续结束事件被忽略，重新调用原生 start 也不会复位此一次性业务状态。
             */
            override fun onAnimationEnd(a: Animator) {
                if (isEnd.compareAndSet(false, true)) asyncAnimCallbacks.onAnimationEnd(a)
            }

            /**
             * 仅在第一次结束尚未发生时转发平台开始事件。
             * 结束后原生动画即使被再次启动，也不重新发出业务开始通知。
             */
            override fun onAnimationStart(a: Animator) {
                if (!isEnd.get()) asyncAnimCallbacks.onAnimationStart(a)
            }
        })
    }

    /**
     * 在生命周期锁内把首次命令绑定到当前配置的执行器，再在其所属线程执行动作。
     * 已释放时 start 请求抛出异常，其他命令静默忽略；排队动作执行前会再次检查释放状态。
     * 若动作内部重入 dispose，即使动作抛出异常，也会在 finally 中再次清理可能刚登记的原生帧资源。
     */
    private fun marshal(starting: Boolean = false, action: () -> Unit) {
        val target = synchronized(lifecycleLock) {
            if (disposed) {
                check(!starting) { "Animator is disposed" }
                return
            }
            owner ?: configuredExecutor.also { owner = it }
        }
        target.execute {
            if (!disposed) {
                try {
                    action()
                } finally {

                    if (disposed) releaseOnOwner()
                }
            }
        }
    }

    /**
     * 通过固定所属执行器启动平台 ValueAnimator，首次调用会冻结执行器选择。
     * 已释放时抛出 IllegalStateException；仅转发此生命周期入口，原生属性配置仍需在启动前完成。
     */
    @PublicApi
    override fun start() = marshal(starting = true) { super.start() }

    /**
     * 将平台取消命令交给首次绑定的执行器，首次取消也会绑定线程。
     * 释放后为无操作；业务取消与结束通知遵守一次性转发状态，不将 cancel 当成最终 dispose。
     */
    @PublicApi
    override fun cancel() = marshal { super.cancel() }

    /**
     * 在固定所属执行器上调用平台结束操作，具体终值和帧状态遵循 ValueAnimator。
     * 释放后直接忽略；跨线程调用返回不表示平台结束或业务主线程回调已经执行。
     */
    @PublicApi
    override fun end() = marshal { super.end() }

    /**
     * 在锁内立即标记最终释放并使旧业务回调失效，再把原生监听与帧清理交给固定所属线程。
     * 重复释放无操作，排队生命周期动作会跳过；不能撤回已经执行中的回调，也不退出共享动画线程。
     * 释放后不要经本包装或公开监听容器继续注册原生/业务监听。
     */
    @PublicApi
    fun dispose() {
        val target = synchronized(lifecycleLock) {
            if (disposed) return
            disposed = true
            asyncAnimCallbacks.dispose()
            owner ?: configuredExecutor.also { owner = it }
        }
        target.execute { releaseOnOwner() }
    }

    /**
     * 在原生动画所属线程移除全部更新监听和普通监听，再调用父类取消以退订帧循环。
     * 先移除监听避免释放动作产生业务结束通知；可重复执行以覆盖开始回调重入释放后的尾部登记。
     */
    private fun releaseOnOwner() {
        removeAllUpdateListeners()
        removeAllListeners()
        super.cancel()
    }

    /**
     * 在生命周期锁内向业务容器注册监听，未释放时允许 null 并由容器负责去重。
     * 不是直接添加原生监听；业务生命周期事件经容器回到主线程，释放后即使传 null 也会抛出状态异常。
     */
    @PublicApi
    fun addAnimatorListener(l: NullableAnimatorListener?) = synchronized(lifecycleLock) {
        check(!disposed) { "Animator is disposed" }
        asyncAnimCallbacks.addListener(l)
    }

    /**
     * 从业务派发容器移除指定监听，允许 null、不存在的实例或释放后的重复调用。
     * 不删除通过原生 addListener 注册的监听，也不能撤回已经执行中的业务回调。
     */
    @PublicApi
    fun removeAnimatorListener(l: NullableAnimatorListener?) { asyncAnimCallbacks.removeListener(l) }

    companion object {

        /**
         * 创建并配置一个新的 AsyncValueAnimator，按给定浮点关键帧定义数值变化。
         * 默认执行器为主线程，方法名称不代表自动切到后台；创建后仍需设置所需时长、曲线及线程配置。
         */
        @PublicApi
        fun ofFloat(vararg values: Float): AsyncValueAnimator =
            AsyncValueAnimator().apply { setFloatValues(*values) }

        /**
         * 根据 isAsync 选择线程转发包装或普通 ValueAnimator，再写入传入的浮点关键帧。
         * 提供 Java 静态入口；两种分支均未启动动画，true 分支默认仍绑定主线程执行器。
         */
        @PublicApi
        @JvmStatic
        fun ofFloat(isAsync: Boolean, vararg values: Float): ValueAnimator =
            (if (isAsync) AsyncValueAnimator() else ValueAnimator()).apply {
                setFloatValues(*values)
            }
    }
}
