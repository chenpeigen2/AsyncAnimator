package com.asyncanimator.control

import com.asyncanimator.api.PublicApi
import com.asyncanimator.core.LogUtils
import com.asyncanimator.thread.MainThreadScheduler
import com.asyncanimator.thread.mainThreadScheduler
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 事件与主线程定时器竞争的一次性任务，构造时保存类型、动作和释放通知并启动毫秒级挂起计时。
 * checkAccess在构造及公开消费/释放入口调用，控制器注入主线程检查；独立实例使用默认空检查。
 * 动作和释放通知分别原子消费，定时器在主线程执行、事件在调用线程执行；无主Looper时仅保留事件入口。
 * 定时路径的动作异常经主线程消息泵同步外抛，与旧 Handler 实现的崩溃语义一致；事件路径在调用线程直接抛出。
 * 不校验duration的正负，调度行为委托协程延迟；释放不等待已开始的动作，异常不被静默吞掉。
 */
@PublicApi
class TaskStateChangeTimeOutListener internal constructor(
    private val type: Type,
    duration: Long,
    option: () -> Unit,
    onDisposed: () -> Unit,
    private val checkAccess: () -> Unit = {}
) {
    /**
     * 创建独立的一次性超时监听，不附加所有者清理动作或线程访问限制。
     * duration为主线程调度器上的挂起毫秒数，构造即尝试计时；事件或定时器先到者执行option，调用方需最终dispose未消费实例。
     */
    @PublicApi
    constructor(type: Type, duration: Long, option: () -> Unit) : this(type, duration, option, {})

    /**
     * 可等待的任务状态类别：特殊横屏场景退出、转场完成、应用到概览续行完成。
     * 仅同类型事件能触发该监听，类别本身不订阅系统事件，由宿主显式桥接。
     */
    @PublicApi
    enum class Type {
        /** 横屏特殊退出场景的事件通道；事件与定时器竞争一次性消费动作。 */
        @PublicApi
        ON_LAND_SCAPE_SCENE_EXIT,
        /** 转场完成事件通道；只消费本类型的待处理动作，不是平台完成信号本身。 */
        @PublicApi
        ON_TRANSITION_FINISH,
        /** 应用进入概览续行事件通道；由宿主事件或超时触发一次性动作。 */
        @PublicApi
        ON_APP_TO_OVERVIEW_CONTINUATION
    }

    /**
     * 主线程调度设施，构造时按主Looper可用性解析；无Looper环境为null，与定时器降级语义一致。
     */
    private val scheduler: MainThreadScheduler? = mainThreadScheduler()

    /**
     * 承载定时任务的实例级作用域；调度设施不可用时为null，不安装定时兜底。
     * Supervisor隔离使定时任务的异常不进入协程未捕获处理器，而由外抛路径按旧语义处理。
     */
    private val scope: CoroutineScope? = scheduler?.let {
        CoroutineScope(SupervisorJob() + it.dispatcher)
    }

    /**
     * 待执行动作的原子槽，事件与定时器竞争消费，仅最先成功者执行。
     */
    private val pendingAction = AtomicReference<(() -> Unit)?>(option)

    /**
     * 释放通知的原子槽，随动作被消费或实例被释放恰好取走一次。
     */
    private val disposalCallback = AtomicReference<(() -> Unit)?>(onDisposed)

    /**
     * 定时任务句柄；事件获胜或dispose时用于取消尚未执行的挂起，跨线程可见。
     */
    @Volatile
    private var timerJob: Job? = null

    init {
        checkAccess()
        timerJob = scope?.launch {
            delay(duration)
            runCatching { fireOnce("timer") }
                .exceptionOrNull()
                ?.let { failure -> scheduler?.handler?.post { throw failure } }
        }
        if (LogUtils.isLogOpen()) LogUtils.i("TaskStateTimeout", "registered type=$type delayMs=$duration")
    }

    /**
     * 原子取走待执行动作，事件和定时器仅最先成功者执行；事件通道随后取消尚未执行的定时挂起并运行该动作。
     * 无论动作成功与否都尝试一次释放通知；动作与清理同时失败时保留动作异常为主，并附加不同的清理异常。
     * 不切换线程、不再次检查访问条件；dispose无法撤回已被其他线程取走的动作。
     * 异常向调用线程抛出：事件路径即调用线程，定时路径由启动处经主线程消息泵重新外抛。
     */
    private fun fireOnce(source: String) {
        val action = pendingAction.getAndSet(null) ?: return
        if (source == "event") timerJob?.cancel()
        if (LogUtils.isLogOpen()) LogUtils.i("TaskStateTimeout", "firing type=$type source=$source")

        val result = runCatching { action() }
        try { notifyDisposed() }
        catch (cleanupFailure: Throwable) {
            val primary = result.exceptionOrNull() ?: throw cleanupFailure
            if (primary !== cleanupFailure) primary.addSuppressed(cleanupFailure)
        }
        result.getOrThrow()
    }

    /**
     * 先检查访问约束，仅type与注册类型一致时竞争消费动作，不匹配不改变定时任务。
     * duration参数保留接口形状但不参与匹配或重新计时；事件动作同步在调用线程执行，异常保持可见。
     */
    @PublicApi
    fun onTimeOut(type: Type, duration: Long) {
        checkAccess()
        if (type == this.type) {
            fireOnce("event")
        }
    }

    /**
     * 先检查访问约束，再丢弃尚未消费的动作、取消尚未执行的定时挂起并发出一次释放通知。
     * 可重复调用且不会执行挂起动作；不是执行中动作的完成屏障，已经被其他线程取得的动作仍可能运行。
     */
    @PublicApi
    fun dispose() {
        checkAccess()
        pendingAction.set(null)
        timerJob?.cancel()
        notifyDisposed()
    }

    /**
     * 原子消费可空释放通知并在当前线程调用，最多一次，即使通知抛错也不重新挂回。
     * 用于让所有者移除对应监听槽，失败由调用方处理；不再次执行超时动作。
     */
    private fun notifyDisposed() {
        val callback = disposalCallback.getAndSet(null) ?: return
        if (LogUtils.isLogOpen()) LogUtils.i("TaskStateTimeout", "disposed type=$type")
        callback()
    }
}
