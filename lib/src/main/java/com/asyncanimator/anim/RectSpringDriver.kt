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
 * 由六条AndroidX弹簧聚合矩形帧的驱动，帧推进和onUpdate固定在start的调用线程，不实现表面事务。
 * 主构造保存动画策略、初始速度、帧源提供器及续行进度/反转状态，并复制校验起终矩形。
 * 圆角必须有限非负，透明度须在0到1且有限，初始六轴速度须有限；构造本身不启动或获取帧源。
 * 仅currentFrame作为volatile不可变快照供跨线程读取，其他活动运行操作须由所属线程串行调用。
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
    /**
     * 创建使用AnimationHandler当前调度器的六轴驱动，默认滑回桌面、零圆角、全不透明及零初速度。
     * startRect/targetRect立即复制校验；配置、圆角、透明度和速度决定初始物理状态，onUpdate在启动线程接收帧。
     * 帧源在start时才解析，调用方若操作View必须自行满足UI线程约束。
     */
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
    /**
     * 最近发布的不可变帧，首次start前为null，初始化、重定向和tick时更新，外部只读。
     * 允许跨线程读取，结束或dispose后仍保留最后快照；读取不会触发onUpdate或推进弹簧。
     */
    @Volatile var currentFrame: RectSpringFrame? = null
        private set
    /**
     * 声明六轴驱动可在动画线程使用统一帧源和AndroidX调度器，恒返回true。
     * 这不保证onUpdate中的任意业务写入都线程安全，调用方仍需约束View等线程绑定对象。
     */
    override val supportsAnimationThread: Boolean get() = true

    init {
        checkedRadius(startRadius); checkedRadius(targetRadius)
        require(startAlpha.isFinite() && startAlpha in 0f..1f)
        require(targetAlpha.isFinite() && targetAlpha in 0f..1f)
        require(initialVelocity.array().all { it.isFinite() })
    }

    /**
     * 延后到统一tick处理的停止原因，CANCEL保留现值清理，END先提交目标帧。
     * 只接受首个请求，与上层主线程逻辑通知分别维护。
     */
    private enum class Stop { CANCEL, END }
    /**
     * 一次六轴播放的线程绑定上下文，构造时捕获Thread和单调启动时间，并持有帧源与可空结束钩子。
     * 维护AndroidX去重回调队列、各轴、停止请求、尺寸模式和进度基准；统一tick通过身份检查拒绝过期运行。
     */
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
        /**
         * 在本轮所有者线程把AndroidX回调加入去重队列，交给统一tick推进。
         * 非所属线程抛出状态异常，已失效运行不接收新回调；不会为每条轴另建帧源。
         */
        override fun postFrameCallback(frameCallback: Runnable) {
            check(isCurrentThread()) { "Spring frames belong to their run owner" }
            if (current === this) queued.add(frameCallback)
        }
        /**
         * 按Thread实例身份检查当前线程是否是创建本轮Run的线程。
         * 供AndroidX调度器和驱动入口验证线程约束，只做判断，不自动切换线程。
         */
        override fun isCurrentThread() = Thread.currentThread() === owner
    }

    /**
     * 单条标量弹簧及其运行缓存，index按中心X、追踪Y、尺寸、高宽比、圆角、透明度编号。
     * 构造时用给定值/速度/目标/参数创建SpringForce和SpringAnimation并安装更新/结束监听，但不立即启动。
     * 透明度可延迟启动；缓存值、速度、边界、首帧和完成标记均由所属Run线程维护。
     */
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
        /**
         * 按轴类型计算合法范围并同步到弹簧：尺寸不小于minimumSize、比例为正、圆角非负、透明度在0到1。
         * 可选比例限制使用当前值与目标的闭区间；同时钳制当前值和force终点，不清零现有速度。
         */
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
        /**
         * 结束该轴延迟等待，重置完成/首帧标记，用当前保存的值和速度启动AndroidX弹簧。
         * 必须在Run所有者线程调用；只启动单轴，统一帧订阅由外层驱动管理。
         */
        fun start() {
            waiting = false
            done = false
            firstFrame = true
            animation.setStartValue(value).setStartVelocity(velocity)
            animation.start()
        }
        /**
         * 更新可选弹簧参数和目标边界；有restartValue时取消旧轴并用配套非空restartVelocity重建起始坐标。
         * 运行中直接更新force目标以保留速度；等待透明度延迟的轴不提前启动，其他已停止轴重启，更新参数时刷新可见变化阈值。
         * 供所有者线程内部调用，缺少配套速度或非法参数由require/底层校验抛出。
         */
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

            if (!waiting) {
                if (!animation.isRunning) start()
                else if (updated != null) {

                    animation.start()
                }
            }
        }
    }

    /**
     * 在当前线程创建唯一活动Run，初始化六轴及快照，启动非延迟轴并订阅统一帧源。
     * 已释放或已运行时抛出状态异常；初始化/订阅失败会尝试静默清理本轮，清理成功后重抛原Throwable，清理失败不另行合并。
     * onActualEnd在运行真正结束清理后由所有者线程调用；onUpdate也在此线程执行，View写入需调用方自行安排。
     */
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

    /**
     * 在活动运行所属线程登记首个取消请求，无运行时无操作，跨线程操作会失败。
     * 不立即停轴或调用结束钩子，下一次tick处理物理清理；上层负责即时逻辑取消通知。
     */
    override fun cancel() { ownedRun()?.let { if (it.stop == null) it.stop = Stop.CANCEL } }
    /**
     * 在活动运行所属线程登记首个到终点请求，不覆盖先前取消或结束请求。
     * 下一次tick会发布终点快照并清理；无运行时无操作，本次返回不保证已提交最终帧。
     */
    override fun skipToEnd() { ownedRun()?.let { if (it.stop == null) it.stop = Stop.END } }
    /**
     * 在活动运行所有者线程清空物理结束钩子，无运行时无操作。
     * 不停止弹簧、不移除帧订阅，也不改变当前帧快照。
     */
    override fun clearEndCallback() { ownedRun()?.callback = null }

    /**
     * 在运行所有者线程永久标记释放并立即静默清理活动运行，不等待下一次帧信号。
     * 取消各轴、移除统一帧回调并执行已排队清理回调；不调用实际结束钩子，后续start会失败。
     */
    override fun dispose() {
        val run = ownedRun()
        disposed = true
        if (run != null) finish(run, notify = false)
    }

    /**
     * 先校验并复制目标矩形、校验非负有限圆角，再在所有者上反转当前运行。
     * 按打开策略选择尺寸坐标和弹簧参数，透明度目标置1；无运行或已经请求停止时不重定向。
     */
    override fun reverseToOpen(target: RectF, endRadius: Float) {
        retarget(checkedRect(target), checkedRadius(endRadius), reverse = true)
    }

    /**
     * 校验并复制新的正尺寸目标，在所有者线程保留六轴速度重设终点。
     * endRadius为空保留当前圆角目标，否则要求非负有限；普通重定向不改变宽/高模式、弹簧策略或既有反转状态。
     */
    @JvmOverloads fun updateEndTargetRectF(target: RectF, endRadius: Float? = null) {
        retarget(checkedRect(target), endRadius?.let(::checkedRadius), reverse = false)
    }

    /**
     * 为当前未停止运行计算新目标并保留连续状态；反转时必要的宽/高切换使用乘积/商求导转换尺寸速度。
     * 以旧快照进度作为新段基准，更新六轴和可选打开参数，再发布重定向快照但不调用onUpdate。
     * 目标输入应已校验；无运行/已停止时忽略，所有活动操作必须处于Run所有者线程。
     */
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

    /**
     * 在活动运行所有者线程预测deltaMillis毫秒后的快照，不推进真实轴、不修改队列也不派发回调。
     * 间隔须非负；根据系统动画倍率缩放预测时间，并保留延迟轴、已结束轴和首帧预热的当前状态。
     * END请求投影到终点，CANCEL保留当前值；假设目标/策略不变，预测不保证未来真实帧时序一致。
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

    /**
     * 从下一帧预测状态创建尚未启动的后继驱动，继承配置、帧源、圆角/透明度目标、进度及六轴速度。
     * 新目标须是有限正尺寸矩形，deltaMillis非负且当前运行存在；尺寸坐标模式变化时用导数公式转换速度。
     * 不会取消本驱动，调用方应释放旧句柄再启动返回驱动；新的onUpdate属于后继运行的所有者线程。
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

    /**
     * 在Run所有者上推进统一帧：优先处理停止请求，END发布终点后清理，CANCEL直接清理。
     * 正常帧按延迟条件启动透明度轴，消费队列快照并发布聚合帧；六轴均结束时完成本轮。
     * 帧回调或onUpdate抛错时保留必要清理任务、结束当前运行再重抛，重入改变current时不误清理后继运行。
     */
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

            run.queued.addAll(work)
            if (current === run) finish(run)
            throw t
        }
        if (current === run && run.axes.all { it.done }) finish(run)
    }

    /**
     * 只清理仍为current的Run，先消费current，再退订统一帧源并取消运行中的各轴。
     * 排空已投递的AndroidX清理回调以释放时长倍率监听，随后消费可空结束钩子，notify为false时静默丢弃。
     * 在Run所有者执行，不吞掉清理或外部钩子异常；清理后保留最后的currentFrame供读取。
     */
    private fun finish(run: Run, notify: Boolean = true) {
        if (current !== run) return
        current = null
        run.source.removeFrameCallback(run.tick)
        run.axes.forEach { if (it.animation.isRunning) it.animation.cancel() }

        val cleanup = run.queued.toList()
        run.queued.clear()
        cleanup.forEach { it.run() }
        val callback = run.callback
        run.callback = null
        if (notify) callback?.invoke()
    }

    /**
     * 返回当前运行，并在存在运行时强制校验调用线程就是该Run的创建线程。
     * 无运行返回null且不限制线程；不获取锁、不等待帧，也不改变运行生命周期。
     */
    private fun ownedRun(): Run? = current.also {
        check(it == null || it.isCurrentThread()) { "Rect spring driver operations belong to the run owner" }
    }

    /**
     * 将六轴位置/速度还原为不可变矩形帧，默认读取实时轴，也可传入预测数组。
     * 尺寸与比例取正下限，按宽/高模式和纵向锚点还原边界；进度由尺寸段比例计算，反转向0推进，terminal强制段完成。
     * 数组按固定六轴顺序读取并拷贝为值对象，不修改调用方数组或推进真实动画。
     */
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

    /**
     * 把矩形与圆角/透明度转换为中心X、追踪Y、尺寸、高宽比、圆角、透明度六轴数组。
     * 纵向锚点来自配置，widthMode选择宽或高，比例恒为高除宽；调用方须先保证矩形有限且尺寸为正。
     */
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
        /**
         * 比较起终高宽比选择尺寸轴是否使用宽：打开/反转打开取起比小于终比，其他类型取终比小于起比。
         * 相等时返回false即使用高度；纯计算不校验比例范围、不修改任何动画状态。
         */
        fun widthMode(startRatio: Float, endRatio: Float, type: CustomRectFSpringAnim.AnimType): Boolean =
            if (type == CustomRectFSpringAnim.AnimType.OPEN_FROM_HOME || type == CustomRectFSpringAnim.AnimType.REVERSE_TO_OPEN)
                startRatio < endRatio else endRatio < startRatio
        /**
         * 要求圆角半径有限且非负，通过后返回原值，非法值抛出IllegalArgumentException。
         * 不做自动钳制或单位转换，允许0表示无圆角。
         */
        fun checkedRadius(radius: Float): Float {
            require(radius.isFinite() && radius >= 0)
            return radius
        }
        /**
         * 验证四边、宽高均有限，宽高为正且高宽比有限，通过后返回独立RectF副本。
         * 非法几何抛出IllegalArgumentException；不修改传入对象，副本隔离后续外部矩形变化。
         */
        fun checkedRect(rect: RectF): RectF {
            require(listOf(rect.left, rect.top, rect.right, rect.bottom, rect.width(), rect.height()).all { it.isFinite() })
            require(rect.width() > 0 && rect.height() > 0 && (rect.height() / rect.width()).isFinite())
            return RectF(rect)
        }
    }
}
