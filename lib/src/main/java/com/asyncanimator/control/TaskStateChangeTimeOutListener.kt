package com.asyncanimator.control

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicReference

/**
 * TaskStateChangeTimeOutListener - 自管理超时对象（对齐原厂 TaskStateHelper$TaskStateChangeTimeOutListener）。
 *
 * 构造即向主线程 Handler postDelayed 一个超时兜底：事件（onTimeOut 匹配）或超时任一先到，
 * 都执行一次 option 并 dispose。防止 startActivity 在事件丢失时永久挂起。
 */
class TaskStateChangeTimeOutListener(
    private val type: Type,
    duration: Long,
    option: () -> Unit
) {

    enum class Type {
        ON_LAND_SCAPE_SCENE_EXIT,
        ON_TRANSITION_FINISH,
        ON_APP_TO_OVERVIEW_CONTINUATION
    }

    private val handler: Handler? = mainLooper()?.let(::Handler)
    private val pendingAction = AtomicReference<(() -> Unit)?>(option)
    private val timeOutOption = Runnable { fireOnce() }

    private fun fireOnce() {
        val action = pendingAction.getAndSet(null) ?: return
        handler?.removeCallbacks(timeOutOption)
        action()
    }

    init {
        handler?.postDelayed(timeOutOption, duration)
    }

    fun onTimeOut(type: Type, duration: Long) {
        if (type == this.type) {
            fireOnce()
        }
    }

    fun dispose() {
        pendingAction.set(null)
        handler?.removeCallbacks(timeOutOption)
    }

    private fun mainLooper(): Looper? =
        runCatching { Looper.getMainLooper() }.getOrNull()
}
