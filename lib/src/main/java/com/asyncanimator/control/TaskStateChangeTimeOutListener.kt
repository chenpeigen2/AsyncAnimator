package com.asyncanimator.control

import android.os.Handler
import android.os.Looper
import com.asyncanimator.core.LogUtils
import java.util.concurrent.atomic.AtomicReference

/**
 * 事件与主线程定时器竞争的一次性任务，构造时保存类型、动作和释放通知并注册毫秒延迟。
 * checkAccess在构造及公开消费/释放入口调用，控制器注入主线程检查；独立实例使用默认空检查。
 * 动作和释放通知分别原子消费，定时器在主线程执行、事件在调用线程执行；无主Looper时仅保留事件入口。
 * 不校验duration的正负，调度行为委托Handler；释放不等待已开始的动作，异常不被静默吞掉。
 */
class TaskStateChangeTimeOutListener internal constructor(
    private val type: Type,
    duration: Long,
    option: () -> Unit,
    onDisposed: () -> Unit,
    private val checkAccess: () -> Unit = {}
) {
    /**
     * 创建独立的一次性超时监听，不附加所有者清理动作或线程访问限制。
     * duration为Handler延迟毫秒数，构造即尝试计时；事件或定时器先到者执行option，调用方需最终dispose未消费实例。
     */
    constructor(type: Type, duration: Long, option: () -> Unit) : this(type, duration, option, {})

    /**
     * 可等待的任务状态类别：特殊横屏场景退出、转场完成、应用到概览续行完成。
     * 仅同类型事件能触发该监听，类别本身不订阅系统事件，由宿主显式桥接。
     */
    enum class Type {
        ON_LAND_SCAPE_SCENE_EXIT,
        ON_TRANSITION_FINISH,
        ON_APP_TO_OVERVIEW_CONTINUATION
    }

    private val handler: Handler? = mainLooper()?.let(::Handler)
    private val pendingAction = AtomicReference<(() -> Unit)?>(option)
    private val disposalCallback = AtomicReference<(() -> Unit)?>(onDisposed)
    private val timeOutOption = Runnable { fireOnce("timer") }

    /**
     * 原子取走待执行动作，事件和定时器仅最先成功者执行；随后撤销定时回调并运行该动作。
     * 无论动作成功与否都尝试一次释放通知；动作与清理同时失败时保留动作异常为主，并附加不同的清理异常。
     * 不切换线程、不再次检查访问条件；dispose无法撤回已被其他线程取走的动作。
     */
    private fun fireOnce(source: String) {
        val action = pendingAction.getAndSet(null) ?: return
        handler?.removeCallbacks(timeOutOption)
        if (LogUtils.isLogOpen()) LogUtils.i("TaskStateTimeout", "firing type=$type source=$source")

        val result = runCatching { action() }
        try { notifyDisposed() }
        catch (cleanupFailure: Throwable) {
            val primary = result.exceptionOrNull() ?: throw cleanupFailure
            if (primary !== cleanupFailure) primary.addSuppressed(cleanupFailure)
        }
        result.getOrThrow()
    }

    init {
        checkAccess()
        handler?.postDelayed(timeOutOption, duration)
        if (LogUtils.isLogOpen()) LogUtils.i("TaskStateTimeout", "registered type=$type delayMs=$duration")
    }

    /**
     * 先检查访问约束，仅type与注册类型一致时竞争消费动作，不匹配不改变定时器。
     * duration参数保留接口形状但不参与匹配或重新计时；事件动作同步在调用线程执行，异常保持可见。
     */
    fun onTimeOut(type: Type, duration: Long) {
        checkAccess()
        if (type == this.type) {
            fireOnce("event")
        }
    }

    /**
     * 先检查访问约束，再丢弃尚未消费的动作、移除定时消息并发出一次释放通知。
     * 可重复调用且不会执行挂起动作；不是执行中动作的完成屏障，已经被其他线程取得的动作仍可能运行。
     */
    fun dispose() {
        checkAccess()
        pendingAction.set(null)
        handler?.removeCallbacks(timeOutOption)
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

    /**
     * 尝试获取平台主Looper，失败或不存在时返回null，使纯JVM环境可保留事件触发路径。
     * 不创建线程或Looper，返回null意味着不会安装Handler定时兜底。
     */
    private fun mainLooper(): Looper? =
        runCatching { Looper.getMainLooper() }.getOrNull()
}
