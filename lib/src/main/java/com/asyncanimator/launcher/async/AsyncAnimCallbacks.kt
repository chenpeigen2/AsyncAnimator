package com.asyncanimator.launcher.async

import android.animation.Animator
import com.asyncanimator.launcher.pending.NullableAnimatorListener
import com.asyncanimator.launcher.pending.NullableAnimatorListenerAdapter
import com.asyncanimator.util.Trace

/**
 * AsyncAnimCallbacks — listener 容器 + 跨线程派发器。
 *
 * 对应 `docs/review/01-async-animthread.md`。业务 listener 在主线程 fire，避免业务代码自己处理线程切换。
 *
 * 配合 [AsyncValueAnimator]：
 *
 *  1. AsyncValueAnimator.start/cancel/end marshal 到目标 Looper
 *  2. ValueAnimator 在目标 Looper 上 fire listener
 *  3. 本类在 fire listener 时再 marshal 回主线程（兜底）
 */
class AsyncAnimCallbacks {

    private val animListeners = mutableListOf<NullableAnimatorListener?>()

    internal var animationId = -1

    fun addListener(l: NullableAnimatorListener?) {
        if (l != null && !animListeners.contains(l)) animListeners.add(l)
    }

    internal fun removeListener(l: NullableAnimatorListener?) {
        val idx = animListeners.indexOf(l)
        if (idx >= 0) animListeners[idx] = null
    }

    internal fun clearListeners() = animListeners.clear()

    internal fun onAnimationStart(animator: Animator) =
        dispatch("AsyncAnimStart-") { it.onAnimationStart(animator) }

    internal fun onAnimationEnd(animator: Animator) =
        dispatch("AsyncAnimEnd-") { it.onAnimationEnd(animator) }

    internal fun onAnimationCancel(animator: Animator) =
        dispatch("AsyncAnimCancel-") { it.onAnimationCancel(animator) }

    /** 打 trace → 回主线程逐个 fire（跳过懒删除的 null 槽，同步 animationId）。 */
    private fun dispatch(traceTagPrefix: String, action: (NullableAnimatorListener) -> Unit) {
        Trace.traceBegin(8L, "$traceTagPrefix$animationId")
        runOnMainThread {
            for (l in animListeners) {
                if (l == null) continue
                (l as? NullableAnimatorListenerAdapter)?.animationId = animationId
                action(l)
            }
        }
        Trace.traceEnd(8L)
    }

    /** listener 跨线程派发：marshal 到主线程（兜底）。 */
    private fun runOnMainThread(action: () -> Unit) {
        val exec = Executors.MAIN_EXECUTOR
        if (exec.isCurrentThread) action() else exec.post { action() }
    }
}
