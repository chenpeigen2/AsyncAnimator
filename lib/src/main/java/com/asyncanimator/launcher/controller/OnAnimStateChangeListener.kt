package com.asyncanimator.launcher.controller

/**
 * 状态变更监听器接口。
 *
 * 对应分析文档 §6.8.10。
 */
fun interface OnAnimStateChangeListener {
    fun onAnimStateChanged(oldState: AnimationState, newState: AnimationState, runningTask: Any?)
}
