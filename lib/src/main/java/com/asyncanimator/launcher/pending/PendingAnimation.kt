package com.asyncanimator.launcher.pending

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.util.FloatProperty
import com.asyncanimator.launcher.playback.AnimatorPlaybackController
import com.asyncanimator.launcher.playback.PropertySetter
import java.util.function.Consumer

/**
 * PendingAnimation — 转场动画的"构建器"。
 *
 * 对应分析文档 §6.2。
 *
 * 关键设计：
 *
 *  - 实现 [PropertySetter]（addFloat/setViewAlpha 等），让代码可以在"动画 vs no-op"之间无缝切换
 *  - `add(Animator, TimeInterpolator, springProperty)` 把每个属性动画加到 [animHolders]
 *  - `progressAnimator` 是辅助 ValueAnimator，挂 onEndListener / onFrameListener
 *  - [buildAnim] 把 progressAnimator 合并到顶层 AnimatorSet，[createPlaybackController] 包装成 APC
 */
class PendingAnimation(duration: Long) : PropertySetter {

    private val anim = AnimatorSet()
    private val animHolders = ArrayList<AnimatorPlaybackController.Holder>()
    private val durationMs: Long = if (duration > 0) duration else 0
    private var progressAnimator: ValueAnimator? = null
    private var controller: AnimatorPlaybackController? = null

    var isAnimFinished = false

    init {
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(a: Animator) { isAnimFinished = false }
            override fun onAnimationEnd(a: Animator) { isAnimFinished = true }
            override fun onAnimationCancel(a: Animator) { isAnimFinished = true }
        })
    }

    fun add(child: Animator): PendingAnimation {
        child.duration = durationMs
        anim.playTogether(child)
        addToHolders(child)
        return this
    }

    fun add(child: Animator, ip: TimeInterpolator?): PendingAnimation {
        child.interpolator = ip
        return add(child)
    }

    fun add(child: Animator, ip: TimeInterpolator?, springProperty: Any?): PendingAnimation {
        child.interpolator = ip
        return add(child)
    }

    fun addWithoutDuration(child: Animator): PendingAnimation {
        anim.playTogether(child)
        addToHolders(child)
        return this
    }

    fun <T> addFloat(target: T, property: FloatProperty<T>,
                     from: Float, to: Float, ip: TimeInterpolator?): PendingAnimation {
        val oa = ObjectAnimator.ofFloat(target, property, from, to)
        oa.setInterpolator(ip)
        return add(oa.buildAnimator())
    }

    override fun <T> setFloat(target: T, property: FloatProperty<T>, value: Float) {
        property.setValue(target, value)
    }

    fun addEndListener(consumer: Consumer<Boolean>?): PendingAnimation {
        if (progressAnimator == null) progressAnimator = ValueAnimator.ofFloat(0f, 1f)
        progressAnimator!!.addListener(AnimatorListeners.forEndCallback(consumer))
        return this
    }

    fun addOnFrameCallback(runnable: Runnable): PendingAnimation {
        if (progressAnimator == null) progressAnimator = ValueAnimator.ofFloat(0f, 1f)
        progressAnimator!!.addUpdateListener { runnable.run() }
        return this
    }

    fun addListener(l: Animator.AnimatorListener): PendingAnimation {
        anim.addListener(l)
        return this
    }

    fun buildAnim(): AnimatorSet {
        progressAnimator?.let {
            addWithoutDuration(it)
            progressAnimator = null
        }
        if (animHolders.isEmpty()) {
            addWithoutDuration(ValueAnimator.ofFloat(0f, 1f).setDuration(durationMs))
        }
        return anim
    }

    fun createPlaybackController(): AnimatorPlaybackController {
        if (controller == null) {
            controller = AnimatorPlaybackController(buildAnim(), durationMs, animHolders)
        }
        return controller!!
    }

    private fun addToHolders(child: Animator) {
        AnimatorPlaybackController.addHoldersRecur(child, durationMs, animHolders)
    }

    // ──── 简化的 ObjectAnimator ────────────────────────────────

    open class ObjectAnimator(
        private val target: Any?,
        private val property: FloatProperty<*>?,
        private val from: Float,
        private val to: Float
    ) {

        private val va: ValueAnimator = ValueAnimator.ofFloat(0f, 1f)

        open fun setInterpolator(ip: TimeInterpolator?): ObjectAnimator = apply {
            va.interpolator = ip
        }

        open fun setDuration(duration: Long): ObjectAnimator = apply {
            va.duration = duration
        }

        open fun setFloatValues(vararg values: Float): ObjectAnimator = apply {
            va.setFloatValues(*values)
        }

        /**
         * 产出一个平台 Animator：内部 va 推进时把 fraction 映射到 from→to 再 setValue。
         * 平台 Animator 的抽象方法比仿写件多（getStartDelay/setStartDelay/
         * setDuration/setInterpolator），全部委托给内部 va，保持原包装语义不变。
         */
        fun buildAnimator(): Animator {
            va.addUpdateListener { a ->
                val f = a.animatedValue as? Float
                if (f != null && property != null && target != null) {
                    val v = from + (to - from) * f
                    @Suppress("UNCHECKED_CAST")
                    (property as FloatProperty<Any?>).setValue(target, v)
                }
            }
            return object : Animator() {
                override fun start() = va.start()
                override fun cancel() = va.cancel()
                override fun end() = va.end()
                override fun isRunning(): Boolean = va.isRunning
                override fun getDuration(): Long = va.duration
                override fun setDuration(duration: Long): Animator = apply { va.duration = duration }
                override fun getStartDelay(): Long = va.startDelay
                override fun setStartDelay(startDelay: Long) { va.startDelay = startDelay }
                override fun setInterpolator(ip: TimeInterpolator?) { va.interpolator = ip }
            }
        }

        val duration: Long get() = va.duration

        // ──── 生命周期委托（TimeControllerObjectAnimator 依赖） ────────
        fun start() = va.start()
        fun cancel() = va.cancel()
        fun end() = va.end()
        fun pause() = va.pause()
        fun isRunning(): Boolean = va.isRunning
        fun addListener(l: Animator.AnimatorListener) = va.addListener(l)

        companion object {

            fun <T> ofFloat(target: T, property: FloatProperty<T>,
                            from: Float, to: Float): ObjectAnimator =
                ObjectAnimator(target, property, from, to)
        }
    }
}
