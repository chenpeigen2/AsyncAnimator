package com.asyncanimator.anim

import android.graphics.RectF
import com.asyncanimator.core.LogUtils
import com.asyncanimator.thread.Executors
import com.asyncanimator.thread.LooperExecutor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main-thread lifecycle state, pinned per-run driver owner, and an actual-end barrier.
 * Driver methods never migrate mid-run. Geometry and frame integration belong to Driver.
 */
internal class RectAnimationLifecycle(
    private val animation: CustomRectFSpringAnim,
    private val driver: CustomRectFSpringAnim.Driver?,
    mainExecutor: LooperExecutor?,
    animExecutor: LooperExecutor?
) {
    private val main by lazy { mainExecutor ?: Executors.MAIN_EXECUTOR }
    private val anim by lazy { animExecutor ?: Executors.ANIM_CONTROL_EXECUTOR }
    private enum class Stop { CANCEL, END }
    private class Run(val id: Long, val animationId: Int, val owner: LooperExecutor,
                      var actualEnd: (() -> Unit)?) {
        @Volatile var driverStarted = false
        @Volatile var stop: Stop? = null
        val stopDispatched = AtomicBoolean(false)
        val physicalReported = AtomicBoolean(false)
        var logicalEnded = false // Main-thread confined, like the listener set.
        var cancelled = false
        fun event() = CustomRectFSpringAnim.Event(animationId, id, cancelled)
    }

    private val listeners = linkedSetOf<CustomRectFSpringAnim.Listener>()
    @Volatile private var current: Run? = null
    @Volatile private var disposed = false
    @Volatile private var serial = 0L
    private var startAsync = driver?.supportsAnimationThread == true
    @Volatile private var configuredAnimationId = -1
    @Volatile var isReverseToOpen = false
        private set
    @Volatile var isJustNotifyEndCallback = false
        private set
    val isRunning: Boolean get() = current != null

    var animationId: Int
        get() = configuredAnimationId
        set(value) {
            checkConfigurable()
            configuredAnimationId = value
        }

    fun setAsyncStart(enabled: Boolean) {
        checkConfigurable()
        require(!enabled || driver?.supportsAnimationThread == true) {
            "Driver does not support animation-thread frames/property writes"
        }
        startAsync = enabled
    }

    fun addListener(listener: CustomRectFSpringAnim.Listener) {
        checkMain()
        check(!disposed) { "Rect animation is disposed" }
        listeners.add(listener)
    }

    fun removeListener(listener: CustomRectFSpringAnim.Listener) {
        checkMain()
        listeners.remove(listener)
    }

    fun start(onActualEnd: (() -> Unit)?) = onMain {
        check(!disposed) { "Rect animation is disposed" }
        if (current != null) return@onMain
        val engine = requireNotNull(driver) { "Rect playback requires a Driver" }
        val run = Run(++serial, configuredAnimationId, if (startAsync) anim else main, onActualEnd)
        current = run
        isReverseToOpen = false
        isJustNotifyEndCallback = false
        notify(run) { listener, event -> listener.onStart(animation, event) }
        if (!active(run)) return@onMain // onStart may dispose the animation.
        run.owner.execute {
            if (!active(run)) return@execute
            engine.start { reportActualEnd(run) }
            run.driverStarted = true
            // A cancellation requested before the posted start is applied after start, not lost.
            dispatchStop(run)
        }
    }

    fun cancel() = requestStop(Stop.CANCEL)
    fun skipToEnd() = requestStop(Stop.END)

    private fun requestStop(stop: Stop) = onMain {
        val run = current ?: return@onMain
        if (run.stop != null) return@onMain
        run.stop = stop
        run.cancelled = stop == Stop.CANCEL
        if (!run.logicalEnded) {
            // Commit the logical terminal state before callbacks can reenter.
            run.logicalEnded = true
            if (run.cancelled) notify(run) { listener, event -> listener.onCancel(animation, event) }
            notify(run) { listener, event -> listener.onEnd(animation, event) }
        }
        if (active(run) && run.driverStarted) run.owner.execute { dispatchStop(run) }
    }

    /** Called on the run's owner; an atomic gate prevents a start/stop handoff race duplicating it. */
    private fun dispatchStop(run: Run) {
        if (!active(run) || !run.driverStarted) return
        val stop = run.stop ?: return
        if (!run.stopDispatched.compareAndSet(false, true)) return
        if (stop == Stop.CANCEL) driver!!.cancel() else driver!!.skipToEnd()
    }

    /** Logical end only: allow the driver to continue until its separate physical callback. */
    fun justNotifyEndCallback() = onMain {
        val run = current ?: return@onMain
        if (run.logicalEnded) return@onMain
        isJustNotifyEndCallback = true
        run.logicalEnded = true
        notify(run) { listener, event -> listener.onEnd(animation, event) }
    }

    private fun reportActualEnd(run: Run) {
        if (!active(run) || !run.physicalReported.compareAndSet(false, true)) return
        // Cleanup on the same owner BEFORE exposing completion/reuse on main.
        run.owner.execute {
            if (!active(run)) return@execute
            driver!!.clearEndCallback()
            onMain {
                if (!active(run)) return@onMain
                current = null
                val callback = run.actualEnd
                run.actualEnd = null
                // One terminal bundle has one audience. onEnd may start a successor and
                // register listeners that must not receive this run's actual-end event.
                val terminalListeners = listeners.toList()
                if (!run.logicalEnded) {
                    run.logicalEnded = true
                    if (run.cancelled) notify(run, terminalListeners) { listener, event -> listener.onCancel(animation, event) }
                    notify(run, terminalListeners) { listener, event -> listener.onEnd(animation, event) }
                }
                notify(run, terminalListeners) { listener, event -> listener.onActualEnd(animation, event) }
                // A listener disposing the object must also cancel the owner's queued end hook.
                if (!disposed) callback?.invoke()
            }
        }
    }

    fun reverseToOpen(target: RectF, endRadius: Float, afterReverse: () -> Unit) = onMain {
        val run = current ?: return@onMain
        if (run.stop != null) return@onMain
        val engine = driver as? CustomRectFSpringAnim.ReversibleDriver
            ?: throw UnsupportedOperationException("This rect driver cannot retarget geometry")
        run.owner.execute {
            if (!active(run) || run.stop != null) return@execute
            engine.reverseToOpen(target, endRadius)
            onMain {
                if (disposed || serial != run.id) return@onMain
                animation.animType = CustomRectFSpringAnim.AnimType.REVERSE_TO_OPEN
                isReverseToOpen = true
                afterReverse()
            }
        }
    }

    fun clearEndCallback() {
        checkMain()
        current?.actualEnd = null
    }

    fun dispose() = onMain {
        if (disposed) return@onMain
        disposed = true
        serial++
        val run = current
        current = null
        run?.actualEnd = null
        listeners.clear()
        if (run != null) run.owner.execute {
            if (driver is CustomRectFSpringAnim.DisposableDriver) driver.dispose()
            else {
                driver!!.clearEndCallback()
                driver.cancel()
            }
        }
    }

    private fun notify(
        run: Run,
        snapshot: List<CustomRectFSpringAnim.Listener> = listeners.toList(),
        action: (CustomRectFSpringAnim.Listener, CustomRectFSpringAnim.Event) -> Unit
    ) {
        val event = run.event()
        for (listener in snapshot) {
            if (disposed) return
            if (listener in listeners) {
                // Observer failures must not strand start/stop or prevent the physical-end barrier.
                // This is a library safety policy, not OEM exception-propagation equivalence.
                try { action(listener, event) }
                catch (error: Exception) {
                    LogUtils.i("RectAnimationLifecycle", "Listener failed: ${error.javaClass.simpleName}: ${error.message}")
                }
            }
        }
    }

    private fun active(run: Run) = !disposed && current === run
    private fun onMain(action: () -> Unit) {
        if (main.isCurrentThread) action() else main.postAsync(action)
    }
    private fun checkMain() { check(main.isCurrentThread) { "Rect configuration/listeners belong to the main thread" } }
    private fun checkConfigurable() {
        checkMain()
        check(!disposed) { "Rect animation is disposed" }
        check(current == null) { "Rect owner/id cannot change before actual end" }
    }
}
