package com.asyncanimator.playback

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.TimeInterpolator
import android.animation.ValueAnimator

/**
 * 全局进度到子动画原始fraction的映射函数，第二参数为子时长占总时长的比例。
 * 自定义实现直接参与seek，调用方负责输出范围和线程约束，不隐式执行插值器。
 */
internal typealias ProgressMapper = (globalFraction: Float, globalEndProgress: Float) -> Float

/**
 * 默认线性映射：子结束比例非正或全局进度已越过结束点时返回1，否则按比例相除。
 * 零时长子动画即使首次seek到0也在终点，避免除零；输入截断由外层setPlayFraction负责。
 */
private val DEFAULT_PROGRESS_MAPPER: ProgressMapper = { f, g ->

    if (g <= 0f || f > g) 1f else f / g
}

/**
 * 以单个线性ValueAnimator主时钟同步seek全部子值动画的回放控制器，不启动子动画自己的帧循环。
 * 构造保存根动画/总毫秒时长，复制Holder列表为数组，安装主时钟更新/结束监听和根生命周期跟踪监听。
 * 调用方应提供非负总时长并在动画所属线程串行操作；根及Holder不克隆，后续新增列表项不会进入已有控制器。
 */
internal class AnimatorPlaybackController(
    anim: Animator,
    private val duration: Long,
    holders: List<Holder>
) : ValueAnimator.AnimatorUpdateListener {

    private val childAnimations: Array<Holder>
    private var targetCancelled = false
    private var isDispatchStartPending = false

    /**
     * 主时钟取消时同步调用的可空动作，默认null，不在调用后自动清空。
     * 暂停也会取消主时钟而触发它；放弃控制器前由宿主解除可能捕获界面的引用。
     */
    var cancelAction: (() -> Unit)? = null

    /**
     * 按字符串键保存成功结束动作，同键覆盖；取消/暂停保留，成功时先消费整份快照再调用。
     * 回调中新注册动作保留给后继运行，异常不会重放已消费快照；宿主在放弃控制器时应主动清理。
     */
    val endActions = mutableMapOf<String, () -> Unit>()

    /**
     * 最近输入的原始全局进度，外部只读，不保证在0到1内。
     * 实际写子动画时另行裁剪，目标已取消时也会更新此记录，随后start/reverse以它作为起点。
     */
    var progressFraction = 0f
        private set

    /**
     * 唯一自动产帧的主ValueAnimator，初始0到1并在构造中配置线性插值器。
     * 对外暴露同一可变实例，外部修改须保留更新/结束链路并满足所属线程约束。
     */
    val animationPlayer: ValueAnimator = ValueAnimator.ofFloat(0f, 1f)

    init {
        animationPlayer.interpolator = Interpolators.LINEAR
        animationPlayer.addUpdateListener(this)
        animationPlayer.addListener(OnAnimationEndDispatcher())
        childAnimations = holders.toTypedArray()

        anim.addListener(object : AnimatorListenerAdapter() {
            /**
             * 根动画收到取消时标记目标已取消并清除待启动派发标记。
             * 此后setPlayFraction仍记录输入，但在根动画再次开始或结束前不向子动画写进度。
             */
            override fun onAnimationCancel(a: Animator) {
                targetCancelled = true
                isDispatchStartPending = false
            }

            /**
             * 根动画收到结束时解除目标取消标记并清除待启动标记，允许后续手动seek。
             * 不直接执行endActions，其一次性成功消费由主时钟结束分发器负责。
             */
            override fun onAnimationEnd(a: Animator) {
                targetCancelled = false
                isDispatchStartPending = false
            }

            /**
             * 根动画收到开始时解除取消状态并清除待启动派发标记。
             * 仅同步控制器标志，不启动主时钟或子动画自身的帧循环。
             */
            override fun onAnimationStart(a: Animator) {
                targetCancelled = false
                isDispatchStartPending = false
            }
        })
    }

    /**
     * 单个ValueAnimator的进度代理，构造强制转换animator，非值动画会抛类型转换异常。
     * 按子时长/全局时长快照结束比例，全局时长非正记为0，并保存原插值器供reset恢复；不包含延迟或顺序依赖。
     */
    class Holder(animator: Animator, totalDuration: Float) {
        val anim: ValueAnimator = animator as ValueAnimator
        /**
         * 构造时快照的子时长占总时长比例，总时长非正时为0，之后改duration不会重算。
         * 默认映射以此决定子动画何时到终点，比例可大于1。
         */
        val globalEndProgress: Float =
            if (totalDuration <= 0f) 0f else animator.duration / totalDuration
        /**
         * 构造时保存的子动画插值器引用，reset会把同一引用写回。
         * 不是插值器状态的深拷贝，外部修改有状态插值器仍可能影响回放。
         */
        val interpolator: TimeInterpolator? = anim.interpolator
        /**
         * 当前进度映射策略，默认线性截断，可由宿主替换以改变全局到本地fraction的映射。
         * reset会恢复默认，不验证自定义输出或自动同步访问。
         */
        var mapper: ProgressMapper = DEFAULT_PROGRESS_MAPPER
        /**
         * 当前实现始终为null，未提供基于弹簧属性的速度启动或收敛驱动。
         * 保留只读接口字段，不应将其解释为已经安装物理弹簧能力。
         */
        val springProperty: Any? = null

        /**
         * 使用当前mapper把全局进度与此子动画结束比例映射为本地fraction，然后同步seek子ValueAnimator。
         * 默认在子动画到期后截断为1，零时长立即为终点；本方法不另外钳制自定义mapper输出或切线程。
         */
        fun setProgress(f: Float) {
            anim.setCurrentFraction(mapper(f, globalEndProgress))
        }

        /**
         * 恢复构造时保存的子动画插值器，并把进度映射器重置为默认策略。
         * 不重置子动画当前值、不取消它，也不重新计算构造时快照的结束比例。
         */
        fun reset() {
            anim.interpolator = interpolator
            mapper = DEFAULT_PROGRESS_MAPPER
        }
    }

    /**
     * 主时钟更新时只接受浮点animatedValue并转交setPlayFraction同步推进子动画。
     * 非浮点值忽略，不另外触发生命周期或结束动作。
     */
    override fun onAnimationUpdate(animator: ValueAnimator) {
        (animator.animatedValue as? Float)?.let(::setPlayFraction)
    }

    /**
     * 先记录未经截断的输入进度，目标未取消时把钳制到0到1的值逐个传给Holder。
     * 目标已取消则只记不写；不会启动主时钟，不单独验证NaN或有限性，调用方须提供合理进度。
     */
    fun setPlayFraction(f: Float) {
        progressFraction = f
        if (targetCancelled) return
        val clamped = f.coerceIn(0f, 1f)
        for (holder in childAnimations) holder.setProgress(clamped)
    }

    /**
     * 把主时钟配置为从已记录进度向1播放，时长按剩余比例裁剪到总时长范围，再启动。
     * 启动后清待启动派发标记；不自动递归派发根/子onStart，需要时由调用方先调用dispatchOnStart。
     */
    fun start() {
        animationPlayer.setFloatValues(progressFraction, 1f)
        animationPlayer.duration = clampDuration(1f - progressFraction)
        animationPlayer.start()
        isDispatchStartPending = false
    }

    /**
     * 把主时钟配置为从已记录进度向0播放，按当前比例计算裁剪后的毫秒时长并启动。
     * 不是调用平台reverse，也不反转各子动画内部时间轴；根/子生命周期仍由显式派发入口管理。
     */
    fun reverse() {
        animationPlayer.setFloatValues(progressFraction, 0f)
        animationPlayer.duration = clampDuration(progressFraction)
        animationPlayer.start()
        isDispatchStartPending = false
    }

    /**
     * 先恢复所有Holder的初始插值器和默认映射，再cancel主时钟而非平台pause。
     * 取消动作可能同步执行，成功结束动作保留供以后重启；放弃控制器时宿主应清除捕获引用。
     */
    fun pause() {
        for (holder in childAnimations) holder.reset()
        animationPlayer.cancel()
    }

    /**
     * 将总毫秒时长乘给定比例后转Long，并裁剪到0至总时长之间。
     * 要求构造总时长非负以形成合法区间；比例不在此验证有限性，不改变主时钟配置。
     */
    fun clampDuration(f: Float): Long =
        (duration * f).toLong().coerceIn(0L, duration)

    /**
     * 仅当主时钟正在运行且自身animatedFraction严格大于0.95时调用end。
     * 检查的是本轮播放器进度而非目标几何进度，反向播放也按同一阈值完成本轮。
     */
    fun forceFinishIfCloseToEnd() {
        if (!animationPlayer.isRunning || animationPlayer.animatedFraction <= 0.95f) return
        animationPlayer.end()
    }

    /**
     * 主时钟正在运行时强制end并走成功结束链路，未运行时无操作。
     * 不单独修改子动画列表或取消标记，终值由主时钟更新回调应用。
     */
    fun forceFinishIfNeed() {
        if (animationPlayer.isRunning) animationPlayer.end()
    }

    /**
     * 主时钟成功结束分发器，构造初始未派发，继承取消判定并为每轮增加一次性门。
     * 先消费动作再调用外部代码，避免重入/异常让旧动作泄漏到后继运行。
     */
    private inner class OnAnimationEndDispatcher : AnimationSuccessListener() {
        private var dispatched = false

        /**
         * 每轮主时钟开始时清除取消和已派发标记，使本轮可以执行一次成功收尾。
         * 不清空endActions，先前暂停留下的动作仍可参与此次成功完成。
         */
        override fun onAnimationStart(animator: Animator) {
            cancelled = false
            dispatched = false
        }

        /**
         * 首次成功完成时先置已派发、快照并清空endActions，再递归通知根/子结束，最后顺序执行动作。
         * 回调重入新注册的动作归后继运行；任一步抛错立即向外传播且剩余快照不重放。
         */
        override fun onAnimationSuccess(animator: Animator) {
            if (dispatched) return

            dispatched = true
            val actions = endActions.values.toList()
            endActions.clear()
            dispatchOnEnd()

            actions.forEach { it() }
        }

        /**
         * 先委托父类标记本轮取消，阻止后续成功收尾，再同步调用可空cancelAction。
         * 不消费endActions，也不清空cancelAction；业务异常按原样传播。
         */
        override fun onAnimationCancel(animator: Animator) {
            super.onAnimationCancel(animator)
            cancelAction?.invoke()
        }
    }

    /**
     * 控制器持有的根动画引用，用于递归生命周期派发，通常为组装层的AnimatorSet。
     * 不克隆、不隐式启动；各监听接收原节点而非主时钟作为事件源。
     */
    private val rootAnim: Animator = anim

    /**
     * 从根动画进行前序深度优先遍历，对各节点监听快照执行指定命令并返回控制器自身。
     * 节点监听允许重入增删；访问节点后才取其子集合快照，动作异常立即终止遍历，不启动真实帧循环。
     */
    private fun dispatchToListeners(
        action: Animator.AnimatorListener.(Animator) -> Unit
    ): AnimatorPlaybackController = apply {

        /**
         * 先按当前节点监听快照发送命令，再递归访问AnimatorSet当时的子动画快照。
         * 每个回调接收其所属Animator，父节点先于子节点；快照保护遍历但不吞掉监听异常。
         */
        fun visit(animator: Animator) {
            animator.listeners?.toList()?.forEach { it.action(animator) }
            if (animator is AnimatorSet) animator.childAnimations.toList().forEach(::visit)
        }
        visit(rootAnim)
    }

    /**
     * 同步递归派发根及所有子动画的onAnimationStart，再设置待启动标记并返回自身。
     * 仅通知不启动帧；派发异常时后续标记不执行，目标跟踪监听在通知过程中清除取消状态。
     */
    fun dispatchOnStart(): AnimatorPlaybackController {
        dispatchToListeners(Animator.AnimatorListener::onAnimationStart)
        isDispatchStartPending = true
        return this
    }

    /**
     * 同步递归派发根和子动画结束事件并返回自身，不强制停止播放器或写终值。
     * 直接调用不会消费endActions；监听异常保持fail-fast行为。
     */
    fun dispatchOnEnd() = dispatchToListeners(Animator.AnimatorListener::onAnimationEnd)

    /**
     * 同步递归通知根和子动画取消并返回自身，根跟踪监听据此屏蔽后续子进度写入。
     * 不等于取消主时钟，主时钟cancelAction只由主时钟自己的取消通知触发。
     */
    fun dispatchOnCancel() = dispatchToListeners(Animator.AnimatorListener::onAnimationCancel)

    companion object {

        /**
         * 递归收集AnimatorSet中的ValueAnimator并创建控制器，根集合本身不启动。
         * 收集时会把有效父时长/插值器写入子动画；duration是全局毫秒基准，调用方应提供非负值。
         */
        fun wrap(set: AnimatorSet, duration: Long): AnimatorPlaybackController {
            val holders = mutableListOf<Holder>()
            addHoldersRecur(set, duration, holders)
            return AnimatorPlaybackController(set, duration, holders)
        }

        /**
         * 深度优先收集ValueAnimator代理；AnimatorSet只把严格正时长和非空插值器继承给子节点再递归。
         * 父时长0或未设置不会覆盖，Holder随后快照子时长/插值器；不建模顺序依赖或startDelay，未知Animator类型抛RuntimeException。
         */
        fun addHoldersRecur(anim: Animator, totalDuration: Long, out: MutableList<Holder>) {
            when (anim) {
                is ValueAnimator -> out.add(Holder(anim, totalDuration.toFloat()))
                is AnimatorSet -> {

                    val setDuration = anim.duration
                    val setInterpolator = anim.interpolator
                    anim.childAnimations.forEach { child ->
                        if (setDuration > 0) child.duration = setDuration
                        if (setInterpolator != null) child.interpolator = setInterpolator
                        addHoldersRecur(child, totalDuration, out)
                    }
                }

                else -> throw RuntimeException("Unknown animation type $anim")
            }
        }
    }
}
