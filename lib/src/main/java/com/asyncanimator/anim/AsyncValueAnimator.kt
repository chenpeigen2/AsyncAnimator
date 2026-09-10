package com.asyncanimator.anim

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import java.util.concurrent.atomic.AtomicBoolean
import com.asyncanimator.thread.LooperExecutor
import com.asyncanimator.playback.NullableAnimatorListener
import com.asyncanimator.thread.Executors

/**
 * AsyncValueAnimator — 跨 Looper 安全的 ValueAnimator。
 *
 * 对应 `docs/review/01-async-animthread.md`。start/cancel/end 先判断"当前线程 vs 目标 Looper"，
 * 不一致时通过 LooperExecutor marshal 过去。
 *
 * listener 跨线程派发（[asyncAnimCallbacks]）：listener fire 时再 marshal 回主线程。
 */
class AsyncValueAnimator : ValueAnimator() {

    private val lifecycleLock = Any()
    @Volatile private var disposed = false
    private var owner: LooperExecutor? = null
    private var configuredExecutor: LooperExecutor = Executors.MAIN_EXECUTOR

    /** Configure before the first lifecycle command; a live animator never migrates Loopers. */
    var executor: LooperExecutor
        get() = synchronized(lifecycleLock) { configuredExecutor }
        set(value) = synchronized(lifecycleLock) {
            check(!disposed) { "Animator is disposed" }
            check(owner == null || owner === value) { "Animator executor is already bound" }
            configuredExecutor = value
        }

    /** listener 容器 + 跨线程派发器（listener 始终回主线程 fire）。 */
    val asyncAnimCallbacks = AsyncAnimCallbacks()

    private val isEnd = AtomicBoolean(false)

    init {
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(a: Animator) {
                if (!isEnd.get()) asyncAnimCallbacks.onAnimationCancel(a)
            }

            override fun onAnimationEnd(a: Animator) {
                if (isEnd.compareAndSet(false, true)) asyncAnimCallbacks.onAnimationEnd(a)
            }

            override fun onAnimationStart(a: Animator) {
                if (!isEnd.get()) asyncAnimCallbacks.onAnimationStart(a)
            }
        })
    }

    /** Pin even a queued cancel/end so subsequent commands cannot use another Looper. */
    private fun marshal(starting: Boolean = false, action: () -> Unit) {
        val target = synchronized(lifecycleLock) {
            if (disposed) {
                check(!starting) { "Animator is disposed" }
                return
            }
            owner ?: configuredExecutor.also { owner = it }
        }
        target.execute {
            if (!disposed) {
                try {
                    action()
                } finally {
                    // onStart may dispose reentrantly before platform start finishes scheduling.
                    if (disposed) releaseOnOwner()
                }
            }
        }
    }

    override fun start() = marshal(starting = true) { super.start() }

    override fun cancel() = marshal { super.cancel() }

    override fun end() = marshal { super.end() }

    /**
     * Final, idempotent teardown. Invalidates queued lifecycle commands/callbacks immediately;
     * native listeners and frame registration are removed on the pinned owner Looper.
     * An already executing callback cannot be recalled. Configure native properties/listeners
     * before start and do not register new listeners (including directly on the container) after
     * disposal. This is not cancel-for-reuse and does not quit the process animation thread.
     */
    fun dispose() {
        val target = synchronized(lifecycleLock) {
            if (disposed) return
            disposed = true
            asyncAnimCallbacks.dispose()
            owner ?: configuredExecutor.also { owner = it }
        }
        target.execute { releaseOnOwner() }
    }

    private fun releaseOnOwner() {
        removeAllUpdateListeners()
        removeAllListeners()
        super.cancel()
    }

    // ---- 兼容原厂调用方式 ----

    /** 兼容原厂 [Animator.addListener]：委托给 [asyncAnimCallbacks] 保持跨线程 marshal。 */
    fun addAnimatorListener(l: NullableAnimatorListener?) = synchronized(lifecycleLock) {
        check(!disposed) { "Animator is disposed" }
        asyncAnimCallbacks.addListener(l)
    }

    /** 兼容原厂 [Animator.removeListener]：委托给 [asyncAnimCallbacks]。 */
    fun removeAnimatorListener(l: NullableAnimatorListener?) { asyncAnimCallbacks.removeListener(l) }

    companion object {
        /** 保留 Kotlin 调用方使用的异步快捷工厂。 */
        fun ofFloat(vararg values: Float): AsyncValueAnimator =
            AsyncValueAnimator().apply { setFloatValues(*values) }

        /** 对齐原厂 ofFloat(isAsync, values)，并提供 Java 静态入口。 */
        @JvmStatic
        fun ofFloat(isAsync: Boolean, vararg values: Float): ValueAnimator =
            (if (isAsync) AsyncValueAnimator() else ValueAnimator()).apply {
                setFloatValues(*values)
            }
    }
}
