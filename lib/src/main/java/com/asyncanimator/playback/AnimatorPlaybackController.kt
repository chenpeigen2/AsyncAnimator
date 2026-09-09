package com.asyncanimator.playback

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.TimeInterpolator
import android.animation.ValueAnimator

/** 全局进度 → 子动画进度的映射策略（默认线性截断）。 */
internal typealias ProgressMapper = (globalFraction: Float, globalEndProgress: Float) -> Float

private val DEFAULT_PROGRESS_MAPPER: ProgressMapper = { f, g -> if (f > g) 1f else f / g }

/**
 * AnimatorPlaybackController — "主时钟驱动所有子动画"的统一播放控制器。
 *
 * 对应 `docs/review/02-pending-playback.md`，是 Launcher 转场动画的核心抽象。
 *
 * 关键设计：
 *
 *  - 内部一个 LINEAR 0..1 主 ValueAnimator（[animationPlayer]）作为唯一被 Choreographer 驱动的对象
 *  - 所有子动画通过 [Holder] 同步推进（每帧主时钟回调 → Holder.setProgress → 子 anim.setCurrentFraction）
 *  - reverse / setPlayFraction 只需修改主时钟
 *  - [ProgressMapper] 提供"全局进度→子动画进度"的策略钩子
 */
internal class AnimatorPlaybackController(
    anim: Animator,
    private val duration: Long,
    holders: List<Holder>
) : ValueAnimator.AnimatorUpdateListener {

    private val anims = mutableListOf<Animator>()
    private val childAnimations: Array<Holder>
    private var targetCancelled = false
    private var isDispatchStartPending = false

    var cancelAction: (() -> Unit)? = null
    val endActions = mutableMapOf<String, () -> Unit>()

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
        // 跟踪根 animator（对齐原厂挂到根 AnimatorSet 而非 anims[0]），三触点同步 isDispatchStartPending=false（OPPO :140/:147/:154）
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(a: Animator) {
                targetCancelled = true
                isDispatchStartPending = false
            }

            override fun onAnimationEnd(a: Animator) {
                targetCancelled = false
                isDispatchStartPending = false
            }

            override fun onAnimationStart(a: Animator) {
                targetCancelled = false
                isDispatchStartPending = false
            }
        })
    }

    // ──── Holder（子动画代理） ────────────────────────────────

    class Holder(animator: Animator, totalDuration: Float) {
        val anim: ValueAnimator = animator as ValueAnimator
        val globalEndProgress: Float = animator.duration / totalDuration
        val interpolator: TimeInterpolator? = anim.interpolator
        var mapper: ProgressMapper = DEFAULT_PROGRESS_MAPPER
        val springProperty: Any? = null // 占位字段：SpringProperty / startWithVelocity 弹簧沉降链路按 review ②-保持简化-1 决定不回移，暂无赋值方

        fun setProgress(f: Float) {
            anim.setCurrentFraction(mapper(f, globalEndProgress))
        }

        fun reset() {
            anim.interpolator = interpolator
            mapper = DEFAULT_PROGRESS_MAPPER
        }
    }

    // ──── 帧回调 ────────────────────────────────

    override fun onAnimationUpdate(animator: ValueAnimator) {
        (animator.animatedValue as? Float)?.let(::setPlayFraction)
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
        isDispatchStartPending = false
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

    fun clampDuration(f: Float): Long =
        (duration * f).toLong().coerceIn(0L, duration)

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
            cancelled = false
            dispatched = false
        }

        override fun onAnimationSuccess(animator: Animator) {
            if (dispatched) return
            dispatchOnEnd()
            endActions.values.forEach { it() }
            endActions.clear()
            dispatched = true
        }

        override fun onAnimationCancel(animator: Animator) {
            super.onAnimationCancel(animator)
            cancelAction?.invoke()
        }
    }

    /** 根动画（通常是 PendingAnimation.buildAnim() 的 AnimatorSet）。 */
    private val rootAnim: Animator = anim

    private inline fun dispatchToListeners(
        action: Animator.AnimatorListener.(Animator) -> Unit
    ): AnimatorPlaybackController = apply {
        // 对齐原厂 callListenerCommandRecursively 的前序 DFS：根 → 子动画。
        // 根 AnimatorSet 自身从不被 APC 启动（只有 animationPlayer 在跑），
        // 挂在根上的 listener 只能经这里收到回调。
        val targets = if (anims.size == 1 && anims[0] === rootAnim) anims
                      else listOf(rootAnim) + anims
        for (a in targets) a.listeners.orEmpty().forEach { it.action(a) }
    }

    fun dispatchOnStart(): AnimatorPlaybackController {
        dispatchToListeners(Animator.AnimatorListener::onAnimationStart)
        isDispatchStartPending = true
        return this
    }

    fun dispatchOnEnd() = dispatchToListeners(Animator.AnimatorListener::onAnimationEnd)

    fun dispatchOnCancel() = dispatchToListeners(Animator.AnimatorListener::onAnimationCancel)

    companion object {

        /** 静态工厂：从 AnimatorSet 构造，递归收集所有 ValueAnimator 子动画。 */
        fun wrap(set: AnimatorSet, duration: Long): AnimatorPlaybackController {
            val holders = mutableListOf<Holder>()
            addHoldersRecur(set, duration, holders)
            return AnimatorPlaybackController(set, duration, holders)
        }

        fun addHoldersRecur(anim: Animator, totalDuration: Long, out: MutableList<Holder>) {
            when (anim) {
                is ValueAnimator -> out.add(Holder(anim, totalDuration.toFloat()))
                is AnimatorSet -> anim.childAnimations.forEach { addHoldersRecur(it, totalDuration, out) }
                // 原厂抛 RuntimeException（AnimatorPlaybackController.java:168-169）：
                // 不认识的动画类型显式失败，而不是静默丢弃出 Holder 链
                else -> throw RuntimeException("Unknown animation type $anim")
            }
        }
    }
}
