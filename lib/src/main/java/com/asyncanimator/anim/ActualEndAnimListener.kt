package com.asyncanimator.anim

import android.animation.Animator
import com.asyncanimator.playback.NullableAnimatorListenerAdapter

/**
 * ActualEndAnimListener — "物理帧播完"回调基类（双轨结束的第二轨）。
 *
 * 还原自原厂 `com/android/quickstep/util/animation/ActualEndAnimListener.java:10`
 * （`open class ActualEndAnimListener : NullableAnimatorListenerAdapter`，仅一个空钩子）。
 *
 * 双轨时序（`docs/animation-thread-analysis-v4.md` §4）：cancel 是"置标志、下一帧生效"——
 *
 *  - [onAnimationEnd]/[onAnimationCancel] 是**逻辑结束**：UI 线程即发，驱动业务状态；
 *  - [onAnimActualEnd] 是**物理结束**：动画线程帧循环真的停了才发，用于资源清理。
 *
 * 原厂 CustomRectFSpringAnim.maybeEnd（`CustomRectFSpringAnim.java:423-441`）：
 * 正常结束先发 `onAnimationEnd` 再发 `onAnimActualEnd`；cancel 保护路径
 * （`mJustNotifyEndCallback`）只发 `onAnimActualEnd`。
 *
 * [AsyncAnimCallbacks.onAnimActualEnd] 只对实现了本类的 listener 派发。
 */
open class ActualEndAnimListener : NullableAnimatorListenerAdapter() {

    /** 动画帧循环真实结束（cancel / end 都会走到）。 */
    open fun onAnimActualEnd(animator: Animator) {}
}
