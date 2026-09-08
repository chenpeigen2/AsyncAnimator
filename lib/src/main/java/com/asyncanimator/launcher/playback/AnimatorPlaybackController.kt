package com.asyncanimator.launcher.playback

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import com.asyncanimator.launcher.pending.AnimationSuccessListener

/**
 * AnimatorPlaybackController — "主时钟驱动所有子动画"的统一播放控制器。
 *
 * 对应分析文档 §6.1，是 Launcher 转场动画的核心抽象。
 *
 * 关键设计：
 *
 *  - 内部一个 LINEAR 0..1 主 ValueAnimator（animationPlayer）作为唯一被 Choreographer 驱动的对象
 *  - 所有子动画通过 [Holder] 同步推进（每帧主时钟回调 → Holder.setProgress → 子 anim.setCurrentFraction）
 *  - reverse / setPlayFraction 只需修改主时钟
 *  - [ProgressMapper] 提供"全局进度→子动画进度"的策略钩子
 */
internal class AnimatorPlaybackController(
    anim: Animator,
    private val duration: Long,
    holders: List<Holder>
) : ValueAnimator.AnimatorUpdateListener {

    private val anims = ArrayList<Animator>()
    private val childAnimations: Array<Holder>
    private var targetCancelled = false
    private var isDispatchStartPending = false
    private var cancelAction: Runnable? = null
    private val endActionMap = HashMap<String, Runnable>()

    var progressFraction = 0f
        private set

    val animationPlayer: ValueAnimator = ValueAnimator.ofFloat(0f, 1f)

    init {
        if (anim is AnimatorSet) {
            anims.addAll(anim.childAnimations)
        } else {
            anims.add(anim)
        }
        animationPlayer.interpolator = Interpolators.LINEAR // 强制 LINEAR
        animationPlayer.addUpdateListener(this)
        animationPlayer.addListener(OnAnimationEndDispatcher())
        childAnimations = holders.toTypedArray()
        // 跟踪 anims cancel 状态
        anims[0].addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(a: Animator) {
                targetCancelled = true
            }

            override fun onAnimationEnd(a: Animator) {
                targetCancelled = false
            }

            override fun onAnimationStart(a: Animator) {
                targetCancelled = false
            }
        })
    }

    // ──── Holder（子动画代理） ────────────────────────────────

    class Holder(animator: Animator, totalDuration: Float) {
        val anim: ValueAnimator = animator as ValueAnimator
        val globalEndProgress: Float = animator.duration / totalDuration
        val interpolator: TimeInterpolator? = anim.interpolator
        var mapper: ProgressMapper = ProgressMapper.DEFAULT
        val springProperty: Any? = null // 保留字段（弹簧场景用，本 demo 简化）

        fun setProgress(f: Float) {
            val local = mapper.getProgress(f, globalEndProgress)
            anim.setCurrentFraction(local)
        }

        fun reset() {
            anim.interpolator = interpolator
            mapper = ProgressMapper.DEFAULT
        }
    }

    // ──── ProgressMapper ────────────────────────────────

    fun interface ProgressMapper {
        fun getProgress(globalFraction: Float, globalEndProgress: Float): Float

        companion object {
            val DEFAULT = ProgressMapper { f, g -> if (f > g) 1f else f / g }
        }
    }

    // ──── 帧回调 ────────────────────────────────

    override fun onAnimationUpdate(animator: ValueAnimator) {
        val v = animator.animatedValue as? Float
        if (v != null) setPlayFraction(v)
    }

    fun setPlayFraction(f: Float) {
        progressFraction = f
        if (targetCancelled) return
        val clamped = f.coerceIn(0f, 1f)
        for (holder in childAnimations) holder.setProgress(clamped)
    }

    // ──── 播放控制 ────────────────────────────────

    fun start() {
        animationPlayer.setFloatValues(progressFraction, 1f)
        animationPlayer.duration = clampDuration(1f - progressFraction)
        animationPlayer.start()
        isDispatchStartPending = true
    }

    fun reverse() {
        animationPlayer.setFloatValues(progressFraction, 0f)
        animationPlayer.duration = clampDuration(progressFraction)
        animationPlayer.start()
        isDispatchStartPending = false
    }

    fun pause() {
        for (holder in childAnimations) holder.reset()
        animationPlayer.cancel()
    }

    fun clampDuration(f: Float): Long {
        val d = (duration * f).toLong()
        return d.coerceIn(0L, duration)
    }

    fun forceFinishIfCloseToEnd() {
        if (animationPlayer.isRunning && animationPlayer.animatedFraction <= 0.95f) return
        animationPlayer.end()
    }

    fun forceFinishIfNeed() {
        if (animationPlayer.isRunning) animationPlayer.end()
    }

    // ──── End Dispatcher ────────────────────────────────

    private inner class OnAnimationEndDispatcher : AnimationSuccessListener() {
        private var dispatched = false

        override fun onAnimationStart(animator: Animator) {
            this.cancelled = false
            this.dispatched = false
        }

        override fun onAnimationSuccess(animator: Animator) {
            if (dispatched) return
            dispatchOnEnd()
            if (endActionMap.isNotEmpty()) {
                for (r in endActionMap.values) r.run()
                endActionMap.clear()
            }
            dispatched = true
        }

        override fun onAnimationCancel(animator: Animator) {
            super.onAnimationCancel(animator)
            cancelAction?.run()
        }
    }

    fun dispatchOnStart(): AnimatorPlaybackController {
        for (a in anims) {
            for (l in a.listeners.orEmpty()) {
                l.onAnimationStart(a)
            }
        }
        isDispatchStartPending = true
        return this
    }

    fun dispatchOnEnd(): AnimatorPlaybackController {
        for (a in anims) {
            for (l in a.listeners.orEmpty()) {
                l.onAnimationEnd(a)
            }
        }
        return this
    }

    fun dispatchOnCancel(): AnimatorPlaybackController {
        for (a in anims) {
            for (l in a.listeners.orEmpty()) {
                l.onAnimationCancel(a)
            }
        }
        return this
    }

    fun setCancelAction(r: Runnable?) {
        cancelAction = r
    }

    fun setEndAction(key: String, r: Runnable) {
        endActionMap[key] = r
    }

    companion object {

        /** 静态工厂：从 AnimatorSet 构造，递归收集所有 ValueAnimator 子动画。 */
        fun wrap(set: AnimatorSet, duration: Long): AnimatorPlaybackController {
            val list = ArrayList<Holder>()
            addHoldersRecur(set, duration, list)
            return AnimatorPlaybackController(set, duration, list)
        }

        fun addHoldersRecur(anim: Animator, totalDuration: Long, list: MutableList<Holder>) {
            if (anim is ValueAnimator) {
                list.add(Holder(anim, totalDuration.toFloat()))
                return
            }
            if (anim is AnimatorSet) {
                for (child in anim.childAnimations) {
                    addHoldersRecur(child, totalDuration, list)
                }
            }
        }
    }
}
