package com.asyncanimator.playback

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.animation.ObjectAnimator as PlatformObjectAnimator
import android.util.FloatProperty

/**
 * PendingAnimation — 转场动画的"待组装"层
 *
 * 对应 `docs/review/02-pending-playback.md`；原厂 `com.android.launcher3.anim.PendingAnimation`。
 *
 * 关键设计：
 *
 *  - 实现 [PropertySetter] 的动画版：[setFloat]/[addFloat] 把属性动画包成 ObjectAnimator
 *    加进 AnimatorSet；[PropertySetter.NO_ANIM_PROPERTY_SETTER] 则是 no-op（直接 setValue），
 *    同一段业务代码可在"动画 vs no-op"之间无缝切换
 *  - `add(Animator, TimeInterpolator)` 时每个子动画都登记进 [animHolders]
 *  - `progressAnimator` 是个普通 ValueAnimator，挂 onEndListener / onFrameListener
 *  - [buildAnim] 把 progressAnimator 走 [add]（时长覆写为 [durationMs]，原厂
 *    PendingAnimation.java:93-98）并进 AnimatorSet，[createPlaybackController] 再包装成 APC
 */
internal class PendingAnimation(duration: Long) : PropertySetter {

    private val anim = AnimatorSet()
    private val animHolders = mutableListOf<AnimatorPlaybackController.Holder>()
    private val durationMs: Long = duration.coerceAtLeast(0)
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

    fun add(child: Animator): PendingAnimation = apply {
        child.duration = durationMs
        anim.playTogether(child)
        addToHolders(child)
    }

    fun add(child: Animator, ip: TimeInterpolator?): PendingAnimation {
        child.interpolator = ip
        return add(child)
    }

    fun addWithoutDuration(child: Animator): PendingAnimation = apply {
        anim.playTogether(child)
        addToHolders(child)
    }

    fun <T> addFloat(target: T, property: FloatProperty<T>,
                     from: Float, to: Float, ip: TimeInterpolator?): PendingAnimation {
        val oa = PlatformObjectAnimator.ofFloat(target, property, from, to)
        oa.interpolator = ip
        return add(oa)
    }

    /**
     * 动画版 setFloat（原厂 PendingAnimation.java:127-134）：构造 ObjectAnimator 并 [add]，
     * 属性为 null 或当前值已等于目标值时短路；起点在平台初始化/首次 seek 时读取。
     * 无动画的立即写入语义见 [PropertySetter] 默认实现。
     */
    override fun <T> setFloat(target: T, property: FloatProperty<T>?, value: Float,
                              interpolator: TimeInterpolator) {
        if (property == null || property.get(target) == value) return
        // A single end value lets the platform capture the start at initialization/first seek,
        // rather than freezing a potentially stale value while assembling the transition.
        val oa = PlatformObjectAnimator.ofFloat(target, property, value)
        oa.interpolator = interpolator
        add(oa)
    }

    fun addEndListener(onEnd: ((success: Boolean) -> Unit)?): PendingAnimation = apply {
        progressAnimator().addListener(AnimatorListeners.forEndCallback(onEnd))
    }

    fun addOnFrameCallback(onFrame: () -> Unit): PendingAnimation = apply {
        progressAnimator().addUpdateListener { onFrame() }
    }

    fun addListener(l: Animator.AnimatorListener): PendingAnimation = apply {
        anim.addListener(l)
    }

    fun buildAnim(): AnimatorSet {
        progressAnimator?.let {
            add(it) // 原厂走 add()（PendingAnimation.java:96），时长覆写为 mDuration
            progressAnimator = null
        }
        if (animHolders.isEmpty()) {
            addWithoutDuration(ValueAnimator.ofFloat(0f, 1f).setDuration(durationMs))
        }
        return anim
    }

    /** Finish assembling children/callbacks first: the cached controller snapshots the holders. */
    fun createPlaybackController(): AnimatorPlaybackController =
        controller ?: AnimatorPlaybackController(buildAnim(), durationMs, animHolders)
            .also { controller = it }

    /** 懒建 progress ValueAnimator（挂 end/frame 回调用）。 */
    private fun progressAnimator(): ValueAnimator =
        progressAnimator ?: ValueAnimator.ofFloat(0f, 1f).also { progressAnimator = it }

    private fun addToHolders(child: Animator) {
        AnimatorPlaybackController.addHoldersRecur(child, durationMs, animHolders)
    }

    // ───── TimeController-compatible ValueAnimator adapter (not used by property setters) ─────

    open class ObjectAnimator(
        private val target: Any?,
        private val property: FloatProperty<*>?,
        private val from: Float,
        private val to: Float
    ) {

        private val va: ValueAnimator = ValueAnimator.ofFloat(0f, 1f)

        init {
            // Install once, not on every buildAnimator() call. TimeController subclasses
            // have no property binding here and keep their own update listener.
            if (property != null && target != null) {
                va.addUpdateListener { a ->
                    val f = a.animatedValue as? Float
                    if (f != null) {
                        @Suppress("UNCHECKED_CAST")
                        (property as FloatProperty<Any?>).setValue(target, from + (to - from) * f)
                    }
                }
            }
        }

        open fun setInterpolator(ip: TimeInterpolator?): ObjectAnimator = apply {
            va.interpolator = ip
        }

        open fun setDuration(duration: Long): ObjectAnimator = apply {
            va.duration = duration
        }


        open fun setFloatValues(vararg values: Float): ObjectAnimator = apply {
            va.setFloatValues(*values)
        }

        /** The stable backing animator; repeated calls do not register additional listeners. */
        fun buildAnimator(): ValueAnimator = va

        val duration: Long get() = va.duration
        val currentPlayTime: Long get() = va.currentPlayTime

        // 时间控制类委托（TimeControllerObjectAnimator 等子类用这些直接驱动内部 va）
        fun start() = va.start()
        fun cancel() = va.cancel()
        fun end() = va.end()
        fun pause() = va.pause()
        fun isRunning(): Boolean = va.isRunning
        fun addListener(l: Animator.AnimatorListener) = va.addListener(l)
        fun addUpdateListener(l: ValueAnimator.AnimatorUpdateListener): ObjectAnimator = apply {
            va.addUpdateListener(l)
        }

        companion object {

            fun <T> ofFloat(target: T, property: FloatProperty<T>,
                            from: Float, to: Float): ObjectAnimator =
                ObjectAnimator(target, property, from, to)
        }
    }
}
