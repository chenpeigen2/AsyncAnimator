package com.asyncanimator.anim

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.os.Looper
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import com.asyncanimator.core.LogUtils
import com.asyncanimator.playback.NullableAnimatorListener
import com.asyncanimator.playback.NullableAnimatorListenerAdapter
import com.asyncanimator.thread.Executors

/**
 * Four independent completion tracks: main AnimatorSet, animation-thread AnimatorSet,
 * main-thread AndroidX springs, and a rect driver reporting actual end.
 *
 * All public operations and aggregate notifications belong to the main thread. Only the
 * async AnimatorSet is touched on launcher.anim. Async values must not write View properties.
 * Unlike OPPO's nullable listener arguments, this library supplies [animatorSet] as the
 * aggregate event source. No SF-VSYNC, OEM spring solver or SurfaceControl integration is implied.
 */
class MultiAnimatorSet internal constructor(
    val animType: CustomRectFSpringAnim.AnimType,
    val animatorSet: AnimatorSet,
    val asyncAnimatorSet: AnimatorSet,
    private val onAnimThread: (() -> Unit) -> Unit,
    private val onMainThread: (() -> Unit) -> Unit,
    private val animationsEnabled: () -> Boolean
) {
    constructor(animType: CustomRectFSpringAnim.AnimType) : this(AnimatorSet(), animType)

    constructor(animatorSet: AnimatorSet, animType: CustomRectFSpringAnim.AnimType) : this(
        animType, animatorSet, AnimatorSet(),
        { Executors.ANIM_CONTROL_EXECUTOR.execute(it) },
        { Executors.MAIN_EXECUTOR.execute(it) },
        ValueAnimator::areAnimatorsEnabled
    )

    private enum class Track { MAIN, ASYNC, SPRINGS, RECT }
    private val pending = linkedSetOf<Track>()
    private val listeners = linkedSetOf<NullableAnimatorListener>()
    private val springs = linkedSetOf<SpringAnimation>()
    private val springListeners = mutableMapOf<SpringAnimation, DynamicAnimation.OnAnimationEndListener>()
    private val liveAnimators = linkedSetOf<Animator>()
    private val deferredCommands = mutableListOf<() -> Unit>()
    private var rect: CustomRectFSpringAnim? = null
    private var mainListener: AnimatorListenerAdapter? = null
    private var asyncListener: AnimatorListenerAdapter? = null // animation-thread confined
    private var resetViewState: ((Int) -> Unit)? = null
    private var starting = false
    private var cancelNotified = false
    @Volatile private var generation = 0L
    @Volatile private var started = false
    @Volatile private var disposed = false

    var animationId: Int = -1
    /** Published cancel signal for background value/transaction consumers; other queries are main-only. */
    @Volatile var hasRequestCancel: Boolean = false
        private set
    val isRunning: Boolean get() = pending.isNotEmpty()
    val rectFSpringAnim: CustomRectFSpringAnim? get() = rect
    val isAppOpenType: Boolean get() = animType == CustomRectFSpringAnim.AnimType.OPEN_FROM_HOME
    val isGestureToDrag: Boolean get() = animType == CustomRectFSpringAnim.AnimType.GESTURE_TO_DRAG

    fun addListener(listener: NullableAnimatorListener) { checkOwner(); listeners.add(listener) }
    fun removeListener(listener: NullableAnimatorListener) { checkOwner(); listeners.remove(listener) }
    fun setViewStateResetRunnable(callback: ((Int) -> Unit)?) { checkOwner(); resetViewState = callback }

    fun play(animator: Animator) = play(false, animator)

    /** This overload selects the execution thread; configuration happens before start only. */
    fun play(isAsync: Boolean, animator: Animator) {
        checkOwner()
        if (started) return
        (if (isAsync) asyncAnimatorSet else animatorSet).play(animator)
    }

    /** OPPO's reversed-argument overload means live-add, NOT async-thread selection. */
    fun play(animator: Animator, startImmediately: Boolean) {
        checkOwner()
        if (!started) return play(animator)
        if (!startImmediately) return
        animatorSet.play(animator)
        liveAnimators.add(animator)
        animator.start()
    }

    fun play(vararg animations: SpringAnimation) = animations.forEach(::play)

    fun play(spring: SpringAnimation) {
        checkOwner()
        if (!springs.add(spring)) return
        if (started) {
            pending.add(Track.SPRINGS)
            startSpring(spring, generation)
        }
    }

    fun play(animation: CustomRectFSpringAnim) {
        checkOwner()
        if (started || rect != null) return
        requireNotNull(animation.driver) { "Rect playback requires an actual-end Driver; a bare handle cannot animate" }
        animation.animType = animType // classification only; the driver owns geometry/physics parameters
        rect = animation
    }

    fun start() {
        checkOwner()
        if (started) return
        val run = ++generation
        started = true
        starting = true
        hasRequestCancel = false
        cancelNotified = false
        // Reserve every track BEFORE any child can synchronously finish (including zero duration).
        pending.clear()
        if (animatorSet.childAnimations.isNotEmpty()) pending.add(Track.MAIN)
        if (asyncAnimatorSet.childAnimations.isNotEmpty()) pending.add(Track.ASYNC)
        if (springs.isNotEmpty()) pending.add(Track.SPRINGS)
        if (rect != null) pending.add(Track.RECT)
        runCatching {
            notifyListeners(run) { it.onAnimationStart(animatorSet) }
            if (!active(run)) return@runCatching
            if (Track.MAIN in pending) {
                mainListener = object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        animation.removeListener(this)
                        if (mainListener === this) mainListener = null
                        finish(Track.MAIN, run)
                    }
                }.also(animatorSet::addListener)
                animatorSet.start()
            }
            springs.toList().forEach { if (active(run)) startSpring(it, run) }
            if (active(run)) rect?.let {
                it.animationId = animationId
                it.start { onMainThread { finish(Track.RECT, run) } }
            }
            if (active(run) && Track.ASYNC in pending) onAnimThread {
                if (active(run)) {
                    asyncListener = object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            animation.removeListener(this)
                            if (asyncListener === this) asyncListener = null
                            onMainThread { finish(Track.ASYNC, run) }
                        }
                    }.also(asyncAnimatorSet::addListener)
                    asyncAnimatorSet.start()
                }
            }
        }.also { starting = false }.onFailure { destroy() }.getOrThrow()
        val commands = deferredCommands.toList()
        deferredCommands.clear()
        commands.forEach { if (active(run)) it() }
        if (active(run) && !animationsEnabled()) end(TYPE_ANIMATOR_SET or TYPE_SPRING_ANIMATIONS)
        maybeOnEnd(run)
    }

    private fun startSpring(spring: SpringAnimation, run: Long) {
        if (springListeners.containsKey(spring)) return
        val listener = DynamicAnimation.OnAnimationEndListener { _, _, _, _ ->
            springListeners.remove(spring)?.let(spring::removeEndListener)
            if (active(run)) {
                springs.remove(spring)
                if (springs.isEmpty()) finish(Track.SPRINGS, run)
            }
        }
        springListeners[spring] = listener
        spring.addEndListener(listener)
        if (!spring.isRunning) spring.start()
    }

    fun cancel(type: Int = TYPE_ALL) = stopTracks(type, cancel = true)
    fun end(type: Int = TYPE_ALL) = stopTracks(type, cancel = false)
    fun cancelAllAnimExceptSpringAnim() = cancel(TYPE_ANIMATOR_SET or TYPE_RECTF_SPRING_ANIM)
    fun endAllAnimExceptSpringAnim() = end(TYPE_ANIMATOR_SET or TYPE_RECTF_SPRING_ANIM)

    private fun stopTracks(type: Int, cancel: Boolean) {
        checkOwner()
        require(type and TYPE_ALL == type) { "Unknown animation type mask: $type" }
        if (!started) return
        if (starting) {
            deferredCommands.add { stopTracks(type, cancel) }
            return
        }
        val run = generation
        if (cancel) {
            hasRequestCancel = true
            if (!cancelNotified) {
                cancelNotified = true
                notifyListeners(run) { it.onAnimationCancel(animatorSet) }
            }
            if (!active(run)) return
            val live = liveAnimators.toList()
            liveAnimators.clear()
            live.forEach { if (active(run)) it.cancel() }
        }
        if (!active(run)) return
        // Excluding springs detaches them rather than stopping their independent frame loop.
        if (type and TYPE_SPRING_ANIMATIONS == 0) {
            resetViewState = null
            springs.toList().forEach { spring -> springListeners.remove(spring)?.let(spring::removeEndListener) }
            springs.clear()
            pending.remove(Track.SPRINGS)
        }
        if (type and TYPE_ANIMATOR_SET != 0) {
            if (cancel) animatorSet.cancel() else animatorSet.end()
            // Native end/cancel can synchronously finish this run and start a successor.
            if (!active(run)) return
            onAnimThread {
                if (active(run)) {
                    if (cancel) asyncAnimatorSet.cancel() else asyncAnimatorSet.end()
                }
            }
        }
        if (type and TYPE_SPRING_ANIMATIONS != 0) springs.toList().forEach {
            if (active(run)) {
                if (cancel) it.cancel() else if (it.canSkipToEnd()) it.skipToEnd()
            }
        }
        if (type and TYPE_RECTF_SPRING_ANIM != 0 && active(run)) {
            if (cancel) rect?.cancel() else rect?.skipToEnd()
        }
        maybeOnEnd(run)
    }

    private fun finish(track: Track, run: Long) {
        if (!active(run)) return
        pending.remove(track)
        maybeOnEnd(run)
    }

    private fun maybeOnEnd(run: Long) {
        if (!active(run) || starting || pending.isNotEmpty()) return
        started = false
        val callback = resetViewState
        val completedId = animationId
        val live = liveAnimators.toList()
        liveAnimators.clear() // A cancel observer may install the next run's live animators.
        resetViewState = null // Consume before user code; a reentrant next run keeps its callback.
        rect?.clearEndCallback()
        live.forEach(Animator::cancel)
        if (callback != null) notifySafely { callback(completedId) }
        notifyListeners(run) { it.onAnimationEnd(animatorSet) }
    }

    private fun notifyListeners(run: Long, notify: (NullableAnimatorListener) -> Unit) {
        for (listener in listeners.toList()) {
            if (disposed || generation != run) return
            (listener as? NullableAnimatorListenerAdapter)?.animationId = animationId
            notifySafely { notify(listener) }
        }
    }

    // Observer failures are diagnostic, not permission to strand an owned animation track.
    // Deliberately catch Exception, not fatal VM/Error failures or native driver failures.
    private inline fun notifySafely(action: () -> Unit) {
        try { action() }
        catch (error: Exception) {
            LogUtils.i("MultiAnimatorSet", "Observer failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /** Final teardown: invalidate queued old events, detach our listeners, cancel owned tracks. */
    fun destroy() {
        if (disposed) return
        checkOwner()
        disposed = true
        generation++
        started = false
        starting = false
        pending.clear()
        deferredCommands.clear()
        listeners.clear()
        resetViewState = null
        mainListener?.let(animatorSet::removeListener)
        mainListener = null
        animatorSet.cancel()
        springs.toList().forEach {
            springListeners.remove(it)?.let(it::removeEndListener)
            it.cancel()
        }
        springs.clear()
        rect?.dispose()
        rect = null
        liveAnimators.toList().forEach(Animator::cancel)
        liveAnimators.clear()
        onAnimThread {
            asyncListener?.let(asyncAnimatorSet::removeListener)
            asyncListener = null
            asyncAnimatorSet.cancel()
        }
    }

    private fun active(run: Long) = !disposed && started && generation == run
    private fun checkOwner() {
        check(!disposed) { "MultiAnimatorSet is destroyed" }
        check(Looper.myLooper() == Looper.getMainLooper()) { "MultiAnimatorSet must be called on the main thread" }
    }

    companion object {
        const val TYPE_ANIMATOR_SET = 1
        const val TYPE_SPRING_ANIMATIONS = 2
        const val TYPE_RECTF_SPRING_ANIM = 4
        const val TYPE_ALL = 7
    }
}
