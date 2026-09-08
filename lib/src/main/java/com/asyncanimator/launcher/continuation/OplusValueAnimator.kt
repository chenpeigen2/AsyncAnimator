package com.asyncanimator.launcher.continuation

import android.animation.Animator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.util.FloatProperty
import com.asyncanimator.launcher.pending.PendingAnimation
import com.asyncanimator.util.Trace

/**
 * OplusValueAnimator — timeController 委托模式 + 续行动画。
 *
 * 对应分析文档 §6.5。核心洞察：**this 是 wrapper**，把所有生命周期操作委托给 timeController；
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

    fun interface ValueApplicator {
        fun applyValue(value: Any?)
    }

    constructor() : this(AnimParam(), null)

    private var animatorEnded = false

    init {
        param.interpolator?.let { super.setInterpolator(it) }
        // 自监听帧更新，把值应用到 target
        addUpdateListener { a ->
            param.applicator?.applyValue(a.animatedValue)
        }
    }

    val animName: String get() = param.name

    override fun setCurrentFraction(f: Float) {
        super.setCurrentFraction(f)
        param.currentFraction = f
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

    override fun addListener(l: Animator.AnimatorListener?) {
        if (timeController != null && l != null) timeController.addListener(l)
        else super.addListener(l)
    }

    // ──── AnimParam ─────────────────────────

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

        fun setProperty(prop: FloatProperty<*>?): TimeControllerObjectAnimator = apply {
            // 把 prop 当作"内部 property"；setValue 走 FloatProperty 协议
            // 实际值 = 0..1，由 timeController 推进
        }

        override fun setFloatValues(vararg values: Float): TimeControllerObjectAnimator = apply {
            super.setFloatValues(*values)
        }

        override fun setDuration(duration: Long): TimeControllerObjectAnimator = apply {
            super.setDuration(duration)
        }

        fun setTarget(target: OplusValueAnimator<*>?) {
            // 实际应用由 lambda 处理
        }
    }

    companion object {

        /** 静态工厂：ofFloat 可选 async 版本。 */
        fun ofFloat(isAsync: Boolean, vararg values: Float): ValueAnimator {
            val anim: ValueAnimator = if (isAsync) OplusValueAnimator<Any>() else ValueAnimator()
            anim.setFloatValues(*values)
            return anim
        }

        /** 续行动画：从已有 anim 复制状态生成新 anim 从当前 fraction 跑到 1.0。 */
        fun <T> generateContinuationAnim(anim: OplusValueAnimator<T>?,
                                         durationMs: Long): OplusValueAnimator<T>? {
            if (anim == null) return null
            // 从 RecordInputInterpolator 取最近一次 input
            (anim.param.interpolator as? RecordInputInterpolator)?.let {
                anim.param.currentFraction = it.inputed
            }
            val f = anim.param.currentFraction
            if (f < 0f || f >= 1f) {
                Trace.traceBegin(8L, "Continuation-fail")
                Trace.traceEnd(8L)
                return null
            }
            // 新 timeController 驱动 CURRENT_FRACTION
            val timeController = TimeControllerObjectAnimator()
            val newAnim = OplusValueAnimator<T>(anim.param, timeController)
            timeController.setTarget(newAnim)
            timeController.setProperty(CURRENT_FRACTION)
            timeController.setFloatValues(f, 1f)
            if (durationMs > 0) timeController.setDuration(durationMs)
            Trace.traceBegin(8L, "Continuation-$f")
            Trace.traceEnd(8L)
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
