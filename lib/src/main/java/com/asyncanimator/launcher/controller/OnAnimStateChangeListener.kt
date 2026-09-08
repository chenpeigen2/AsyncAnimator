package com.asyncanimator.launcher.controller

/**
 * 状态变更监听器。
 *
 * 对应分析文档 §6.8.10。`(旧状态, 新状态, 当前 runningTask)`。
 */
typealias OnAnimStateChangeListener = (oldState: AnimationState, newState: AnimationState, runningTask: Any?) -> Unit
