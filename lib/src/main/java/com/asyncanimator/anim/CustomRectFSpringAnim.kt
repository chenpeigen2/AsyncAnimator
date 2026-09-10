package com.asyncanimator.anim

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.RectF
import com.asyncanimator.thread.LooperExecutor

/**
 * Rect-transition lifecycle handle. A bare handle supports AnimationController bookkeeping;
 * playback requires a Driver that reports physical completion, not just logical cancellation.
 * [RectSpringDriver] supplies portable six-axis AndroidX geometry; neither this handle nor that
 * driver implements OEM SurfaceControl/remote-window integration or hidden SF-VSYNC providers.
 */
class CustomRectFSpringAnim internal constructor(
    internal var animType: AnimType,
    internal val driver: Driver?,
    mainExecutor: LooperExecutor?,
    animExecutor: LooperExecutor?
) {
    constructor(animType: AnimType, driver: Driver? = null) : this(animType, driver, null, null)

    private val lifecycle by lazy { RectAnimationLifecycle(this, driver, mainExecutor, animExecutor) }

    /** Copied into immutable events at start; subsequent runs cannot relabel an old event. */
    var animationId: Int
        get() = lifecycle.animationId
        set(value) { lifecycle.animationId = value }
    val isRunning: Boolean get() = lifecycle.isRunning
    val isReverseToOpen: Boolean get() = lifecycle.isReverseToOpen
    val isJustNotifyEndCallback: Boolean get() = lifecycle.isJustNotifyEndCallback

    fun setAsyncStart(enabled: Boolean) = lifecycle.setAsyncStart(enabled)
    fun addListener(listener: Listener) = lifecycle.addListener(listener)
    fun removeListener(listener: Listener) = lifecycle.removeListener(listener)
    @JvmOverloads fun start(onActualEnd: (() -> Unit)? = null) = lifecycle.start(onActualEnd)
    fun cancel() = lifecycle.cancel()
    fun skipToEnd() = lifecycle.skipToEnd()
    fun justNotifyEndCallback() = lifecycle.justNotifyEndCallback()
    fun reverseToOpen(target: RectF, endRadius: Float, afterReverse: () -> Unit) =
        lifecycle.reverseToOpen(RectF(target), endRadius, afterReverse)
    fun dispose() = lifecycle.dispose()
    internal fun clearEndCallback() = lifecycle.clearEndCallback()

    /** Rect-specific, non-null events: no fabricated platform Animator is needed as a payload. */
    data class Event(val animationId: Int, val runId: Long, val cancelled: Boolean)
    interface Listener {
        fun onStart(animation: CustomRectFSpringAnim, event: Event) {}
        fun onCancel(animation: CustomRectFSpringAnim, event: Event) {}
        fun onEnd(animation: CustomRectFSpringAnim, event: Event) {}
        fun onActualEnd(animation: CustomRectFSpringAnim, event: Event) {}
    }

    /** Portable demo adapter. The supplied animator owns the actual geometry/value updates. */
    constructor(animType: AnimType, animator: Animator) : this(animType, AnimatorDriver(animator))

    enum class AnimType {
        OPEN_FROM_HOME,
        REMOTE_CLOSE_TO_HOME,
        REMOTE_CLOSE_TO_HOME_ASSISTANT,
        GESTURE_TO_DRAG,
        SWIPE_TO_HOME,
        SWIPE_TO_HOME_ASSISTANT,
        REVERSE_TO_OPEN
    }

    /** Lifecycle seam for a real geometry engine; start must replace any prior end callback. */
    interface Driver {
        /** Opt in only for engines whose frame source and property writes support launcher.anim. */
        val supportsAnimationThread: Boolean get() = false
        fun start(onActualEnd: () -> Unit)
        fun cancel()
        fun skipToEnd()
        fun clearEndCallback()
    }

    /** Optional final teardown for engines that own frame subscriptions/native registrations. */
    interface DisposableDriver : Driver {
        fun dispose()
    }

    /** Optional retargeting capability: an unsupported geometry engine must not silently no-op. */
    interface ReversibleDriver : Driver {
        fun reverseToOpen(target: RectF, endRadius: Float)
    }

    private class AnimatorDriver(private val animator: Animator) : Driver {
        private var listener: AnimatorListenerAdapter? = null

        override fun start(onActualEnd: () -> Unit) {
            clearEndCallback()
            listener = object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    clearEndCallback()
                    onActualEnd()
                }
            }.also(animator::addListener)
            animator.start()
        }

        override fun cancel() = animator.cancel()
        override fun skipToEnd() = animator.end()
        override fun clearEndCallback() {
            listener?.let(animator::removeListener)
            listener = null
        }
    }
}
