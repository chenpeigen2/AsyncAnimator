package com.asyncanimator.control

/**
 * 状态变更监听器。
 *
 * 对应 `docs/review/03-controller-manager-seq.md`。`(旧状态, 新状态, 当前 runningTask)`。
 *
 * fun interface 便于 Kotlin/Java 共用明确的监听器类型；移除时应保留并传回同一实例。
 * 函数类型同样是对象，不能把重复创建 lambda 导致的身份不同归因于 typealias。
 */
fun interface OnAnimStateChangeListener {

    fun onAnimStateChanged(oldState: AnimationState, newState: AnimationState, runningTask: Any?)
}
