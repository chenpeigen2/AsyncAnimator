package com.asyncanimator.launcher.async

import android.animation.Animator
import com.asyncanimator.launcher.pending.NullableAnimatorListener
import com.asyncanimator.launcher.pending.NullableAnimatorListenerAdapter
import com.asyncanimator.util.Trace

/**
 * AsyncAnimCallbacks — listener 容器 + 跨线程派发器。
 *
 * 对应分析文档 §6.3.3。业务 listener 在主线程 fire，避免业务代码自己处理线程切换。
 *
 * 配合 [AsyncValueAnimator]：
 *
 *  1. AsyncValueAnimator.start/cancel/end marshal 到目标 Looper
 *  2. ValueAnimator 在目标 Looper 上 fire listener
 *  3. 本类在 fire listener 时再 marshal 回主线程（兜底）
 */
class AsyncAnimCallbacks {

    private val animListeners = ArrayList<NullableAnimatorListener?>()

    internal var animationId = -1

    fun addListener(l: NullableAnimatorListener?) {
        if (l == null || animListeners.contains(l)) return
        animListeners.add(l)
    }

    internal fun removeListener(l: NullableAnimatorListener?) {
        val idx = animListeners.indexOf(l)
        if (idx >= 0) animListeners[idx] = null
    }

    internal fun clearListeners() = animListeners.clear()

    internal fun onAnimationStart(animator: Animator) {
        Trace.traceBegin(8L, "AsyncAnimStart-$animationId")
        runOnMainThread {
            for (l in animListeners) {
                if (l == null) continue
                if (l is NullableAnimatorListenerAdapter) l.animationId = animationId
                l.onAnimationStart(animator)
            }
        }
        Trace.traceEnd(8L)
    }

    internal fun onAnimationEnd(animator: Animator) {
        Trace.traceBegin(8L, "AsyncAnimEnd-$animationId")
        runOnMainThread {
            for (l in animListeners) {
                if (l == null) continue
                if (l is NullableAnimatorListenerAdapter) l.animationId = animationId
                l.onAnimationEnd(animator)
            }
        }
        Trace.traceEnd(8L)
    }

    internal fun onAnimationCancel(animator: Animator) {
        Trace.traceBegin(8L, "AsyncAnimCancel-$animationId")
        runOnMainThread {
            for (l in animListeners) {
                if (l == null) continue
                if (l is NullableAnimatorListenerAdapter) l.animationId = animationId
                l.onAnimationCancel(animator)
            }
        }
        Trace.traceEnd(8L)
    }

    /** listener 跨线程派发：marshal 到主线程（兜底）。 */
    private fun runOnMainThread(r: () -> Unit) {
        val exec = Executors.MAIN_EXECUTOR
        if (exec.isCurrentThread) r() else exec.post(Runnable(r))
    }
}
