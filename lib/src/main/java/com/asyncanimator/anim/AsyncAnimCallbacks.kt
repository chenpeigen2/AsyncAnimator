package com.asyncanimator.anim

import android.animation.Animator
import com.asyncanimator.playback.NullableAnimatorListener
import com.asyncanimator.playback.NullableAnimatorListenerAdapter
import com.asyncanimator.core.Trace
import com.asyncanimator.core.LogUtils
import com.asyncanimator.thread.Executors

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
 *  - **快照迭代**：增删与快照生成使用同一把锁，派发前压缩懒删除的 null 槽再拷贝快照（原厂 `getListeners()`
 *    = `removeNullEntries` + `toArray`，:29-32, 81-97），库额外加锁以消除"动画线程 add/remove、
 *    主线程迭代"的 CME 窗口，null 槽也不会只增不减；
 *  - **异步消息**：主线程外投递用 `Message.setAsynchronous(true)`（原厂
 *    `Utilities.postAsyncCallback`，`Utilities.java:631-637`），sync-barrier
 *    （traversal）期间回调不被阻塞；
 *  - **双轨结束**：[onAnimActualEnd] 只对 [ActualEndAnimListener] 派发，
 *    与 onAnimationEnd（逻辑结束）区分"帧循环真的停了"（物理结束）。
 */
class AsyncAnimCallbacks {

    private val listenerLock = Any()
    private var generation = 0L
    private var animType: CustomRectFSpringAnim.AnimType? = null

    /** Optional host-supplied diagnostic context; it does not select an animation engine. */
    fun setAnimType(type: CustomRectFSpringAnim.AnimType) = synchronized(listenerLock) {
        animType = type
    }

    private val animListeners = mutableListOf<NullableAnimatorListener?>()

    @Volatile
    internal var animationId = -1

    fun addListener(l: NullableAnimatorListener?) {
        synchronized(listenerLock) {
            if (l != null && !animListeners.contains(l)) animListeners.add(l)
        }
    }

    fun removeListener(l: NullableAnimatorListener?) {
        synchronized(listenerLock) {
            val idx = animListeners.indexOf(l)
            if (idx >= 0) animListeners[idx] = null
        }
    }

    internal fun clearListeners() = synchronized(listenerLock) { animListeners.clear() }

    /**
     * Release the current registration generation, listeners and diagnostic identity.
     * Queued old events and remaining listeners after reentrant disposal are dropped.
     * Registration may resume, but an executing callback cannot be recalled.
     * This does not cancel the animator or make a one-shot animator reusable.
     */
    fun dispose() = synchronized(listenerLock) {
        generation++
        animListeners.clear()
        animationId = -1
        animType = null
    }

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
        withListenersOnMain(null) { listener, id ->
            if (listener is ActualEndAnimListener) {
                listener.animationId = id
                listener.onAnimActualEnd(animator)
            }
        }
    }

    /**
     * 压缩懒删除的 null 槽 + 返回快照拷贝（原厂 `getListeners()` 模式，
     * `AsyncAnimCallbacks.java:29-32, 81-97`）。锁保护快照生成；调用 listener 时已释放锁，允许重入增删。
     */
    private fun getListeners(): List<NullableAnimatorListener> = synchronized(listenerLock) {
        animListeners.removeAll { it == null }
        animListeners.filterNotNull()
    }

    /** Capture identity before posting; trace actual delivery on main, not just queue submission. */
    private fun withListenersOnMain(
        traceTagPrefix: String?,
        action: (NullableAnimatorListener, Int) -> Unit
    ) {
        val (eventGeneration, id, type) = synchronized(listenerLock) {
            Triple(generation, animationId, animType)
        }
        val eventName = "${traceTagPrefix ?: "ActualEnd-"}$id type=${type ?: "UNSPECIFIED"}"
        runOnMainThread {
            val listeners = synchronized(listenerLock) {
                if (eventGeneration != generation) return@runOnMainThread
                getListeners()
            }
            fun notifyListeners() {
                LogUtils.i("AsyncAnimCallbacks", "$eventName listeners=${listeners.size}")
                for (listener in listeners) {
                    if (synchronized(listenerLock) { eventGeneration != generation }) return
                    action(listener, id)
                }
            }
            if (traceTagPrefix != null) {
                Trace.section(Trace.TAG_VIEW, eventName, ::notifyListeners)
            } else notifyListeners() // Actual-end retains a log anchor without inventing an OEM trace slice.
        }
    }

    private fun dispatch(traceTagPrefix: String, action: (NullableAnimatorListener) -> Unit) {
        withListenersOnMain(traceTagPrefix) { listener, id ->
            (listener as? NullableAnimatorListenerAdapter)?.animationId = id
            action(listener)
        }
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
