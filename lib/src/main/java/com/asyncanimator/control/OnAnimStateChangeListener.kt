package com.asyncanimator.control

/**
 * 状态变更监听器。
 *
 * 对应 `docs/review/03-controller-manager-seq.md`。`(旧状态, 新状态, 当前 runningTask)`。
 *
 * 用 fun interface 而非 typealias：函数类型没有引用相等性，
 * `removeOnAnimStateChangeListener(同一个 lambda)` 会静默失效（review 30 bug #1）。
 */
fun interface OnAnimStateChangeListener {

    fun onAnimStateChanged(oldState: AnimationState, newState: AnimationState, runningTask: Any?)
}
