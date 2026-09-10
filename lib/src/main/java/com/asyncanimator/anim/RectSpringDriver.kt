package com.asyncanimator.anim

import android.animation.ValueAnimator
import android.graphics.RectF
import android.os.SystemClock
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.FrameCallbackScheduler
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.asyncanimator.core.AnimationHandler
import com.asyncanimator.core.TickScheduler
import kotlin.math.max
import kotlin.math.min

/**
 * Six independent AndroidX springs aggregated into one rectangular frame (not SurfaceControl).
 * Uses AndroidX 1.1's public FrameCallbackScheduler, never hidden handlers or force reflection.
 * Driver operations/updates belong to the thread that starts the run; consumers must marshal View
 * writes themselves. currentFrame is an immutable cross-thread snapshot. Use CustomRectFSpringAnim
 * for main-thread logical/physical events, owner marshaling and reversal acknowledgment.
 */
class RectSpringDriver internal constructor(
    startRect: RectF,
    targetRect: RectF,
    private val animType: CustomRectFSpringAnim.AnimType,
    private val config: RectSpringConfig,
    private val startRadius: Float,
    private val targetRadius: Float,
    private val startAlpha: Float,
    private val targetAlpha: Float,
    private val initialVelocity: RectSpringValues,
    private val onUpdate: (RectSpringFrame) -> Unit,
    private val sourceProvider: () -> TickScheduler,
    private val initialProgress: Float = 0f,
    private val initiallyReversing: Boolean = false
) : CustomRectFSpringAnim.ReversibleDriver, CustomRectFSpringAnim.DisposableDriver {
    @JvmOverloads constructor(
        startRect: RectF,
        targetRect: RectF,
        animType: CustomRectFSpringAnim.AnimType = CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME,
        config: RectSpringConfig = RectSpringConfig(),
        startRadius: Float = 0f,
        targetRadius: Float = 0f,
        startAlpha: Float = 1f,
        targetAlpha: Float = 1f,
        initialVelocity: RectSpringValues = RectSpringValues(),
        onUpdate: (RectSpringFrame) -> Unit
    ) : this(startRect, targetRect, animType, config, startRadius, targetRadius, startAlpha,
        targetAlpha, initialVelocity, onUpdate, { AnimationHandler.instance.scheduler })

    private val startRect = checkedRect(startRect)
    private val targetRect = checkedRect(targetRect)
    private var current: Run? = null
    private var disposed = false
    @Volatile var currentFrame: RectSpringFrame? = null
        private set
    override val supportsAnimationThread: Boolean get() = true

    init {
        checkedRadius(startRadius); checkedRadius(targetRadius)
        require(startAlpha.isFinite() && startAlpha in 0f..1f)
        require(targetAlpha.isFinite() && targetAlpha in 0f..1f)
        require(initialVelocity.array().all { it.isFinite() })
    }

    private enum class Stop { CANCEL, END }
    private inner class Run(val source: TickScheduler, var callback: (() -> Unit)?) : FrameCallbackScheduler {
        val owner = Thread.currentThread()
        val startedAt = SystemClock.uptimeMillis()
        val queued = linkedSetOf<Runnable>()
        val axes = mutableListOf<Axis>()
        var stop: Stop? = null
        var widthMode = widthMode(startRect.height() / startRect.width(), targetRect.height() / targetRect.width(), animType)
        var progressStart = if (widthMode) startRect.width() else startRect.height()
        var progressBase = initialProgress
        var reversing = initiallyReversing
        val tick = TickScheduler.FrameCallback { if (current === this) tick(this) }
        override fun postFrameCallback(frameCallback: Runnable) {
            check(isCurrentThread()) { "Spring frames belong to their run owner" }
            if (current === this) queued.add(frameCallback)
        }
        override fun isCurrentThread() = Thread.currentThread() === owner
    }

    private inner class Axis(
        val run: Run, val index: Int, value: Float, velocity: Float, target: Float,
        var parameters: RectSpringConfig.Spring
    ) {
        var value = value
        var velocity = velocity
        var done = false
        var firstFrame = true
        var waiting = index == 5 && config.alphaStartDelayMillis > 0
        var low = -Float.MAX_VALUE
        var high = Float.MAX_VALUE
        val force = SpringForce(target).setStiffness(parameters.stiffness).setDampingRatio(parameters.dampingRatio)
        val animation = SpringAnimation(FloatValueHolder(value)).apply {
            spring = force
            setScheduler(run)
            minimumVisibleChange = parameters.minimumVisibleChange
            addUpdateListener { _, v, speed ->
                this@Axis.value = v
                this@Axis.velocity = speed
                firstFrame = false
            }
            addEndListener { _, _, v, speed ->
                this@Axis.value = v
                this@Axis.velocity = speed
                done = true
            }
        }
        fun bounds(target: Float) {
            low = when (index) {
                2 -> config.minimumSize
                3 -> if (config.limitAspectRatio) min(value, target).coerceAtLeast(EPSILON) else EPSILON
                4, 5 -> 0f
                else -> -Float.MAX_VALUE
            }
            high = when (index) {
                3 -> if (config.limitAspectRatio) max(value, target).coerceAtLeast(low) else Float.MAX_VALUE
                5 -> 1f
                else -> Float.MAX_VALUE
            }
            animation.setMinValue(low); animation.setMaxValue(high)
            force.finalPosition = target.coerceIn(low, high)
            value = value.coerceIn(low, high)
        }
        fun start() {
            waiting = false
            done = false
            firstFrame = true
            animation.setStartValue(value).setStartVelocity(velocity)
            animation.start()
        }
        fun retarget(target: Float, restartValue: Float? = null, restartVelocity: Float? = null,
                     updated: RectSpringConfig.Spring? = null) {
            if (updated != null) {
                parameters = updated
                force.stiffness = updated.stiffness
                force.dampingRatio = updated.dampingRatio
                animation.minimumVisibleChange = updated.minimumVisibleChange
            }
            if (restartValue != null) {
                animation.cancel()
                value = restartValue
                velocity = requireNotNull(restartVelocity)
            }
            bounds(target)
            // A live force is retargeted directly, like OEM updateEndTargetRectF. This preserves
            // velocity and avoids introducing AndroidX animateToFinalPosition's half-frame handoff.
            if (!waiting) {
                if (!animation.isRunning) start()
                else if (updated != null) {
                    // SpringAnimation.start refreshes its public minimum-change threshold;
                    // DynamicAnimation.start does not restart an already-running animation.
                    animation.start()
                }
            }
        }
    }

    override fun start(onActualEnd: () -> Unit) {
        check(!disposed) { "Rect spring driver is disposed" }
        check(current == null) { "Rect spring driver is already running" }
        val run = Run(sourceProvider(), onActualEnd)
        current = run
        try {
            val from = values(startRect, startRadius, startAlpha, run.widthMode)
            val to = values(targetRect, targetRadius, targetAlpha, run.widthMode)
            val velocities = initialVelocity.array()
            val parameters = config.parameters(animType)
            for (index in 0..5) {
                val axis = Axis(run, index, from[index], velocities[index], to[index], parameters[index])
                axis.bounds(to[index])
                run.axes.add(axis)
            }
            run.progressStart = run.axes[2].value
            currentFrame = frame(run)
            run.axes.filterNot { it.waiting }.forEach { it.start() }
            run.source.postFrameCallback(run.tick)
        } catch (t: Throwable) {
            finish(run, notify = false)
            throw t
        }
    }

    /** Logical cancellation is supplied by the handle; physical completion waits for the next tick. */
    override fun cancel() { ownedRun()?.let { if (it.stop == null) it.stop = Stop.CANCEL } }
    override fun skipToEnd() { ownedRun()?.let { if (it.stop == null) it.stop = Stop.END } }
    override fun clearEndCallback() { ownedRun()?.callback = null }

    /** Final disposal detaches now, including AndroidX duration-scale registrations; no VSYNC needed. */
    override fun dispose() {
        val run = ownedRun()
        disposed = true
        if (run != null) finish(run, notify = false)
    }

    override fun reverseToOpen(target: RectF, endRadius: Float) {
        retarget(checkedRect(target), checkedRadius(endRadius), reverse = true)
    }

    /** Changes the target without discarding the six current velocities. Call on the driver owner. */
    @JvmOverloads fun updateEndTargetRectF(target: RectF, endRadius: Float? = null) {
        retarget(checkedRect(target), endRadius?.let(::checkedRadius), reverse = false)
    }

    private fun retarget(target: RectF, radius: Float?, reverse: Boolean) {
        val run = ownedRun() ?: return
        if (run.stop != null) return
        val old = frame(run)
        val targetRatio = target.height() / target.width()
        val nextMode = if (reverse) widthMode(old.values.ratio, targetRatio,
            CustomRectFSpringAnim.AnimType.REVERSE_TO_OPEN) else run.widthMode
        var sizeValue: Float? = null
        var sizeVelocity: Float? = null
        if (nextMode != run.widthMode) {
            val size = old.values.size
            val ratio = old.values.ratio
            val speed = old.velocities.size
            val ratioSpeed = old.velocities.ratio
            if (nextMode) {
                sizeValue = size / ratio
                sizeVelocity = speed / ratio - size * ratioSpeed / (ratio * ratio)
            } else {
                sizeValue = size * ratio
                sizeVelocity = speed * ratio + size * ratioSpeed
            }
        }
        run.widthMode = nextMode
        run.progressBase = old.progress
        run.reversing = reverse || run.reversing
        val alpha = if (reverse) 1f else run.axes[5].force.finalPosition
        val to = values(target, radius ?: run.axes[4].force.finalPosition, alpha, nextMode)
        val updated = if (reverse) config.parameters(CustomRectFSpringAnim.AnimType.REVERSE_TO_OPEN) else null
        run.axes.forEachIndexed { index, axis ->
            axis.retarget(to[index], if (index == 2) sizeValue else null,
                if (index == 2) sizeVelocity else null, updated?.get(index))
        }
        run.progressStart = run.axes[2].value
        currentFrame = frame(run)
    }

    /** Pure next-frame preview; assumes unchanged targets, policy/system duration scale and owner.
     * Delay and first-frame warm-up are retained. Does not advance the live animation or callbacks.
     */
    @JvmOverloads fun copyNextAnimState(deltaMillis: Long = 16): RectSpringFrame {
        require(deltaMillis >= 0)
        val run = requireNotNull(ownedRun()) { "Prediction requires an active run" }
        val scale = ValueAnimator.getDurationScale()
        val dt = if (scale == 0f) Long.MAX_VALUE else (deltaMillis / scale).toLong()
        val values = FloatArray(6)
        val velocities = FloatArray(6)
        val settled = BooleanArray(6)
        run.axes.forEachIndexed { index, axis ->
            val projected = when {
                run.stop == Stop.END -> axis.force.finalPosition to 0f
                run.stop == Stop.CANCEL || axis.waiting || axis.done || axis.firstFrame -> axis.value to axis.velocity
                scale == 0f -> axis.force.finalPosition to 0f
                else -> SpringProjection.advance(axis.force, axis.value, axis.velocity, dt)
            }
            var value = projected.first.coerceIn(axis.low, axis.high)
            var velocity = projected.second
            if (run.stop != Stop.CANCEL && !axis.waiting && !axis.firstFrame && axis.force.isAtEquilibrium(value, velocity)) {
                value = axis.force.finalPosition
                velocity = 0f
            }
            values[index] = value
            velocities[index] = velocity
            settled[index] = axis.done || (!axis.waiting && !axis.firstFrame &&
                axis.force.isAtEquilibrium(value, velocity))
        }
        return frame(run, values, velocities,
            terminal = run.stop == Stop.END || (run.stop != Stop.CANCEL && settled.all { it }))
    }

    /** Builds, but does not start, a successor from the predicted next frame and all six velocities.
     * Does not cancel this driver. Dispose the old handle before starting the returned driver.
     * Size velocity is converted if the successor selects a different width/height coordinate.
     */
    @JvmOverloads fun createContinuation(
        target: RectF, deltaMillis: Long = 16, onUpdate: (RectSpringFrame) -> Unit
    ): RectSpringDriver {
        val destination = checkedRect(target)
        val run = requireNotNull(ownedRun()) { "Continuation requires an active run" }
        val state = copyNextAnimState(deltaMillis)
        val type = if (run.reversing) CustomRectFSpringAnim.AnimType.REVERSE_TO_OPEN else animType
        val nextMode = widthMode(state.values.ratio, destination.height() / destination.width(), type)
        val speed = if (nextMode == state.sizeIsWidth) state.velocities.size else if (nextMode)
            state.velocities.size / state.values.ratio - state.values.size * state.velocities.ratio /
                (state.values.ratio * state.values.ratio)
        else state.velocities.size * state.values.ratio + state.values.size * state.velocities.ratio
        return RectSpringDriver(state.rect, destination, type, config, state.values.radius,
            run.axes[4].force.finalPosition, state.values.alpha, run.axes[5].force.finalPosition,
            state.velocities.copy(size = speed), onUpdate, sourceProvider, state.progress, run.reversing)
    }

    private fun tick(run: Run) {
        check(run.isCurrentThread())
        if (run.stop != null) {
            if (run.stop == Stop.END) {
                run.axes.forEach { it.value = it.force.finalPosition; it.velocity = 0f }
                currentFrame = frame(run, terminal = true)
                try { onUpdate(requireNotNull(currentFrame)) } finally { if (current === run) finish(run) }
            } else finish(run)
            return
        }
        val elapsed = SystemClock.uptimeMillis() - run.startedAt
        run.axes.filter { it.waiting && (elapsed >= config.alphaStartDelayMillis || ValueAnimator.getDurationScale() == 0f) }
            .forEach { it.start() }
        val work = run.queued.toList()
        run.queued.clear()
        try {
            work.forEach { it.run() }
            val state = frame(run, terminal = run.axes.all { it.done })
            currentFrame = state
            onUpdate(state)
        } catch (t: Throwable) {
            // Include any undelivered native handlers so disposal can still compact their lists.
            run.queued.addAll(work)
            if (current === run) finish(run)
            throw t
        }
        if (current === run && run.axes.all { it.done }) finish(run)
    }

    private fun finish(run: Run, notify: Boolean = true) {
        if (current !== run) return
        current = null // consume before any external completion can start a successor
        run.source.removeFrameCallback(run.tick)
        run.axes.forEach { if (it.animation.isRunning) it.animation.cancel() }
        // AndroidX 1.1 unregisters duration-scale listeners during handler list compaction, NOT
        // cancel(). Drain its already posted cleanup runnables even during immediate disposal.
        val cleanup = run.queued.toList()
        run.queued.clear()
        cleanup.forEach { it.run() }
        val callback = run.callback
        run.callback = null
        if (notify) callback?.invoke()
    }

    private fun ownedRun(): Run? = current.also {
        check(it == null || it.isCurrentThread()) { "Rect spring driver operations belong to the run owner" }
    }

    private fun frame(
        run: Run,
        values: FloatArray = FloatArray(6) { run.axes[it].value },
        velocities: FloatArray = FloatArray(6) { run.axes[it].velocity },
        terminal: Boolean = false
    ): RectSpringFrame {
        val size = values[2].coerceAtLeast(config.minimumSize)
        val ratio = values[3].coerceAtLeast(EPSILON)
        val width = if (run.widthMode) size else size / ratio
        val height = if (run.widthMode) size * ratio else size
        val top = when (config.tracking) {
            RectSpringConfig.Tracking.TOP -> values[1]
            RectSpringConfig.Tracking.CENTER -> values[1] - height / 2f
            RectSpringConfig.Tracking.BOTTOM -> values[1] - height
        }
        val distance = run.axes[2].force.finalPosition - run.progressStart
        val fraction = if (terminal) 1f else if (distance == 0f) 0f
            else ((size - run.progressStart) / distance).coerceIn(0f, 1f)
        val progress = if (run.reversing) run.progressBase * (1f - fraction)
            else run.progressBase + (1f - run.progressBase) * fraction
        return RectSpringFrame(values[0] - width / 2f, top, values[0] + width / 2f, top + height,
            RectSpringValues.from(values.copyOf().also { it[2] = size; it[3] = ratio }),
            RectSpringValues.from(velocities), progress, run.widthMode)
    }

    private fun values(rect: RectF, radius: Float, alpha: Float, widthMode: Boolean): FloatArray = floatArrayOf(
        rect.left / 2f + rect.right / 2f,
        when (config.tracking) {
            RectSpringConfig.Tracking.TOP -> rect.top
            RectSpringConfig.Tracking.CENTER -> rect.top / 2f + rect.bottom / 2f
            RectSpringConfig.Tracking.BOTTOM -> rect.bottom
        },
        if (widthMode) rect.width() else rect.height(), rect.height() / rect.width(), radius, alpha
    )

    private companion object {
        const val EPSILON = 0.000001f
        fun widthMode(startRatio: Float, endRatio: Float, type: CustomRectFSpringAnim.AnimType): Boolean =
            if (type == CustomRectFSpringAnim.AnimType.OPEN_FROM_HOME || type == CustomRectFSpringAnim.AnimType.REVERSE_TO_OPEN)
                startRatio < endRatio else endRatio < startRatio
        fun checkedRadius(radius: Float): Float {
            require(radius.isFinite() && radius >= 0)
            return radius
        }
        fun checkedRect(rect: RectF): RectF {
            require(listOf(rect.left, rect.top, rect.right, rect.bottom, rect.width(), rect.height()).all { it.isFinite() })
            require(rect.width() > 0 && rect.height() > 0 && (rect.height() / rect.width()).isFinite())
            return RectF(rect)
        }
    }
}
