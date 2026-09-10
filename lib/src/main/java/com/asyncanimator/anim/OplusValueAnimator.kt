package com.asyncanimator.anim

import android.animation.Animator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.animation.PropertyValuesHolder
import android.util.FloatProperty
import android.view.animation.LinearInterpolator
import com.asyncanimator.playback.PendingAnimation
import com.asyncanimator.core.Trace
import com.asyncanimator.core.LogUtils

/** 把当前动画值应用到 target 的回调。 */
internal typealias ValueApplicator = (value: Any?) -> Unit

/**
 * OplusValueAnimator — timeController 委托模式 + 续行动画。
 *
 * 对应 `docs/review/04-frame-spring-continuation.md`。核心洞察：**this 是 wrapper**，把所有生命周期操作委托给 timeController；
 * timeController 写 CURRENT_FRACTION FloatProperty → setCurrentFraction → AOSP onAnimationUpdate
 * → lambda 转给 valueApplicator.applyValue。
 *
 * 续行动画 API：[generateContinuationAnim] 从已有 anim 拿 RecordInputInterpolator.inputed
 * 作起点，新对象 timeController 从该 fraction 跑到 1.0。
 */
internal class OplusValueAnimator<T>(
    val param: AnimParam,
    private val timeController: TimeControllerObjectAnimator?
) : ValueAnimator() {

    constructor() : this(AnimParam(), null)

    init {
        // A continuation is a real value animator, not just a fraction container.
        super.setFloatValues(param.fromValue, param.toValue)
        if (param.duration > 0) setDuration(param.duration)
        param.interpolator?.let { super.setInterpolator(it) }
        // 自监听帧更新，把值应用到 target
        addUpdateListener { a ->
            param.applicator?.invoke(a.animatedValue)
        }
    }

    val animName: String get() = param.name

    // Construction seeds Float holders. Reusing those holders for Int keyframes crashes in
    // platform PropertyValuesHolder; replace the value definition with the requested type.
    // Apply any custom evaluator after setting values (or provide configured holders via setValues).
    override fun setIntValues(vararg values: Int) {
        if (values.isNotEmpty()) setValues(PropertyValuesHolder.ofInt("", *values))
    }

    override fun setFloatValues(vararg values: Float) {
        if (values.isNotEmpty()) setValues(PropertyValuesHolder.ofFloat("", *values))
    }

    override fun setCurrentFraction(f: Float) {
        super.setCurrentFraction(f)
        param.currentFraction = f
    }

    override fun setInterpolator(i: TimeInterpolator?) {
        super.setInterpolator(i)
        param.interpolator = i
    }

    // ──── 委托给 timeController ──────────────────────────────────

    override fun start() {
        if (timeController != null) timeController.start()
        else super.start()
    }

    override fun cancel() {
        if (timeController != null) timeController.cancel()
        else super.cancel()
    }

    override fun end() {
        if (timeController != null) timeController.end()
        else super.end()
    }

    override fun pause() {
        if (timeController != null) timeController.pause()
        else super.pause()
    }

    override fun isRunning(): Boolean =
        timeController?.isRunning() ?: super.isRunning()

    override fun getDuration(): Long =
        timeController?.duration ?: super.getDuration()

    override fun getCurrentPlayTime(): Long =
        timeController?.currentPlayTime ?: super.getCurrentPlayTime()

    override fun setDuration(duration: Long): ValueAnimator {
        if (timeController != null) {
            timeController.setDuration(duration)
        } else {
            super.setDuration(duration)
        }
        param.duration = duration
        return this
    }

    override fun addListener(l: Animator.AnimatorListener?) {
        if (timeController != null && l != null) timeController.addListener(l)
        else super.addListener(l)
    }

    // ──── AnimParam ─────────────────────────

    /**
     * copy() creates a new parameter container: scalar fields are independent, while
     * interpolator/applicator references (and any state captured by them) remain shared.
     * This is not a generic deep copy of a target or callback closure.
     */
    data class AnimParam(
        var name: String = "default",
        var fromValue: Float = 0f,
        var toValue: Float = 1f,
        var currentFraction: Float = -1f,
        var interpolator: TimeInterpolator? = null,
        var applicator: ValueApplicator? = null,
        var duration: Long = 0
    )

    // ──── 简化的 TimeController（用 ValueAnimator + FloatProperty 模拟） ──────

    open class TimeControllerObjectAnimator : PendingAnimation.ObjectAnimator(null, null, 0f, 1f) {

        private var target: OplusValueAnimator<*>? = null
        private var property: FloatProperty<OplusValueAnimator<*>>? = null

        init {
            // One listener reads the current binding; rebinding never retains an old target.
            addUpdateListener { animator ->
                val currentTarget = target
                val value = animator.animatedValue as? Float
                if (currentTarget != null && value != null) property?.setValue(currentTarget, value)
            }
        }

        fun setProperty(prop: FloatProperty<OplusValueAnimator<*>>?): TimeControllerObjectAnimator = apply {
            property = prop
        }

        override fun setFloatValues(vararg values: Float): TimeControllerObjectAnimator = apply {
            super.setFloatValues(*values)
        }

        override fun setDuration(duration: Long): TimeControllerObjectAnimator = apply {
            super.setDuration(duration)
        }

        fun setTarget(target: OplusValueAnimator<*>?): TimeControllerObjectAnimator = apply {
            this.target = target
        }
    }

    companion object {

        /** Library wrapper-selection factory; true does not move execution to another thread.
         * For thread-marshalled animation use AsyncValueAnimator instead. */
        fun ofFloat(isAsync: Boolean, vararg values: Float): ValueAnimator {
            val anim: ValueAnimator = if (isAsync) OplusValueAnimator<Any>() else ValueAnimator()
            anim.setFloatValues(*values)
            return anim
        }

        /** 续行动画：从已有 anim 复制状态生成新 anim 从当前 fraction 跑到 1.0。 */
        fun <T> generateContinuationAnim(anim: OplusValueAnimator<T>?,
                                         durationMs: Long): OplusValueAnimator<T>? {
            if (anim == null) {
                LogUtils.i("OplusValueAnimator", "continuation rejected: null source")
                return null
            }
            // 从 RecordInputInterpolator 取最近一次 input
            (anim.param.interpolator as? RecordInputInterpolator)?.let {
                anim.param.currentFraction = it.inputed
            }
            val f = anim.param.currentFraction
            if (!f.isFinite() || f < 0f || f >= 1f) {
                LogUtils.i("OplusValueAnimator", "continuation rejected: fraction=$f")
                Trace.traceBegin(Trace.TAG_VIEW, "Continuation-fail f=$f")
                Trace.traceEnd(Trace.TAG_VIEW)
                return null
            }
            // 新 timeController 驱动 CURRENT_FRACTION
            val timeController = TimeControllerObjectAnimator()
            val newAnim = OplusValueAnimator<T>(anim.param.copy(), timeController)
            // setFloatValues/setValues may have configured more than the parameter endpoints.
            // Clone holders so later source edits cannot mutate the continuation's keyframes.
            newAnim.setValues(*anim.values.map { it.clone() }.toTypedArray())
            timeController.setTarget(newAnim)
            timeController.setProperty(CURRENT_FRACTION)
            timeController.setInterpolator(LinearInterpolator())
            timeController.setFloatValues(f, 1f)
            if (durationMs > 0) timeController.setDuration(durationMs)
            LogUtils.i("OplusValueAnimator", "continuation name=${anim.animName} fraction=$f durationMs=${newAnim.duration}")
            Trace.traceBegin(Trace.TAG_VIEW, "Continuation-$f")
            Trace.traceEnd(Trace.TAG_VIEW)
            return newAnim
        }

        // ──── CURRENT_FRACTION FloatProperty ─────────────────────────

        private val CURRENT_FRACTION: FloatProperty<OplusValueAnimator<*>> =
            object : FloatProperty<OplusValueAnimator<*>>("currentFraction") {
                // 平台 FloatProperty 继承 Property<T,Float>：读走抽象方法 get(T)，写走 setValue(T,float)
                override fun get(anim: OplusValueAnimator<*>): Float =
                    anim.param.currentFraction

                override fun setValue(anim: OplusValueAnimator<*>, v: Float) {
                    anim.setCurrentFraction(v)
                }
            }
    }
}
