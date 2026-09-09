package com.asyncanimator.launcher.async

import android.animation.Animator
import com.asyncanimator.launcher.pending.NullableAnimatorListener
import com.asyncanimator.launcher.pending.NullableAnimatorListenerAdapter
import com.asyncanimator.core.Trace

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
 *
 * 三个对齐原厂（`com/android/quickstep/util/animation/AsyncAnimCallbacks.java`）的派发语义：
 *
 *  - **快照迭代**：每次派发先压缩懒删除的 null 槽再拷贝快照（原厂 `getListeners()`
 *    = `removeNullEntries` + `toArray`，:29-32, 81-97），消除"动画线程 add/remove、
 *    主线程迭代"的 CME 窗口，null 槽也不会只增不减；
 *  - **异步消息**：主线程外投递用 `Message.setAsynchronous(true)`（原厂
 *    `Utilities.postAsyncCallback`，`Utilities.java:631-637`），sync-barrier
 *    （traversal）期间回调不被阻塞；
 *  - **双轨结束**：[onAnimActualEnd] 只对 [ActualEndAnimListener] 派发，
 *    与 onAnimationEnd（逻辑结束）区分"帧循环真的停了"（物理结束）。
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

    /**
     * 双轨结束的"物理帧播完"轨道：只对 [ActualEndAnimListener] 派发，其余 listener 收不到。
     * 对齐原厂 `AsyncAnimCallbacks.java:34-43, 111-122`。
     */
    internal fun onAnimActualEnd(animator: Animator) {
        runOnMainThread {
            for (l in getListeners()) {
                if (l is ActualEndAnimListener) {
                    l.animationId = animationId
                    l.onAnimActualEnd(animator)
                }
            }
        }
    }

    /**
     * 压缩懒删除的 null 槽 + 返回快照拷贝（原厂 `getListeners()` 模式，
     * `AsyncAnimCallbacks.java:29-32, 81-97`）。快照保证迭代期间并发 add/remove 不会 CME。
     */
    private fun getListeners(): List<NullableAnimatorListener> {
        animListeners.removeAll { it == null }
        return animListeners.filterNotNull()
    }

    /** 打 trace → 回主线程按快照逐个 fire（同步 animationId）。 */
    private fun dispatch(traceTagPrefix: String, action: (NullableAnimatorListener) -> Unit) {
        Trace.traceBegin(8L, "$traceTagPrefix$animationId")
        runOnMainThread {
            for (l in getListeners()) {
                (l as? NullableAnimatorListenerAdapter)?.animationId = animationId
                action(l)
            }
        }
        Trace.traceEnd(8L)
    }

    /**
     * listener 跨线程派发：marshal 到主线程（兜底）。
     * 主线程外投递用异步消息（[LooperExecutor.postAsync]），可穿透主线程 sync-barrier。
     */
    private fun runOnMainThread(action: () -> Unit) {
        val exec = Executors.MAIN_EXECUTOR
        if (exec.isCurrentThread) action() else exec.postAsync(action)
    }
}
