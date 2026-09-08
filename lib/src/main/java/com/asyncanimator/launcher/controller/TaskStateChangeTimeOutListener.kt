package com.asyncanimator.launcher.controller

import android.os.Handler
import android.os.Looper

/**
 * TaskStateChangeTimeOutListener - 自管理超时对象（对齐原厂 TaskStateHelper$TaskStateChangeTimeOutListener）。
 *
 * 构造即向主线程 Handler postDelayed 一个超时兜底：事件（onTimeOut 匹配）或超时任一先到，
 * 都执行一次 option 并 dispose。防止 startActivity 在事件丢失时永久挂起。
 */
class TaskStateChangeTimeOutListener(
    private val type: Type,
    duration: Long,
    private val option: () -> Unit
) {

    enum class Type {
        ON_LAND_SCAPE_SCENE_EXIT,
        ON_TRANSITION_FINISH,
        ON_APP_TO_OVERVIEW_CONTINUATION
    }

    private val handler: Handler? = mainLooper()?.let(::Handler)
    private val timeOutOption = Runnable {
        dispose()
        option()
    }

    init {
        handler?.postDelayed(timeOutOption, duration)
    }

    fun onTimeOut(type: Type, duration: Long) {
        if (type == this.type) {
            dispose()
            option()
        }
    }

    fun dispose() {
        handler?.removeCallbacks(timeOutOption)
    }

    private fun mainLooper(): Looper? =
        runCatching { Looper.getMainLooper() }.getOrNull()
}
