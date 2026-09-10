package com.asyncanimator.control

import android.os.Handler
import android.os.Looper
import com.asyncanimator.core.LogUtils
import java.util.concurrent.atomic.AtomicReference

/**
 * TaskStateChangeTimeOutListener - 自管理超时对象（对齐原厂 TaskStateHelper$TaskStateChangeTimeOutListener）。
 *
 * 构造即向主线程 Handler postDelayed 一个超时兜底：事件（onTimeOut 匹配）或超时任一先到，
 * 都执行一次 option 并 dispose。防止 startActivity 在事件丢失时永久挂起。
 * Controller-owned instances check main-thread access before consuming state; standalone
 * instances retain atomic once-only consumption and invoke event actions on the caller thread.
 * dispose cannot recall an action already claimed by another standalone event thread; it is not
 * an in-flight completion barrier. Both action and disposal failures remain observable.
 */
class TaskStateChangeTimeOutListener internal constructor(
    private val type: Type,
    duration: Long,
    option: () -> Unit,
    onDisposed: () -> Unit,
    private val checkAccess: () -> Unit = {}
) {
    constructor(type: Type, duration: Long, option: () -> Unit) : this(type, duration, option, {})

    enum class Type {
        ON_LAND_SCAPE_SCENE_EXIT,
        ON_TRANSITION_FINISH,
        ON_APP_TO_OVERVIEW_CONTINUATION
    }

    private val handler: Handler? = mainLooper()?.let(::Handler)
    private val pendingAction = AtomicReference<(() -> Unit)?>(option)
    private val disposalCallback = AtomicReference<(() -> Unit)?>(onDisposed)
    private val timeOutOption = Runnable { fireOnce("timer") }

    private fun fireOnce(source: String) {
        val action = pendingAction.getAndSet(null) ?: return
        handler?.removeCallbacks(timeOutOption)
        if (LogUtils.isLogOpen()) LogUtils.i("TaskStateTimeout", "firing type=$type source=$source")
        // Always release ownership, but do not let a secondary cleanup failure hide the action.
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

    fun onTimeOut(type: Type, duration: Long) {
        checkAccess()
        if (type == this.type) {
            fireOnce("event")
        }
    }

    fun dispose() {
        checkAccess()
        pendingAction.set(null)
        handler?.removeCallbacks(timeOutOption)
        notifyDisposed()
    }

    private fun notifyDisposed() {
        val callback = disposalCallback.getAndSet(null) ?: return
        if (LogUtils.isLogOpen()) LogUtils.i("TaskStateTimeout", "disposed type=$type")
        callback()
    }

    private fun mainLooper(): Looper? =
        runCatching { Looper.getMainLooper() }.getOrNull()
}
