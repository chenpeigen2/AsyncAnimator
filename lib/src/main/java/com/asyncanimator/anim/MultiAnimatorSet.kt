package com.asyncanimator.anim

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.os.Looper
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import com.asyncanimator.api.PublicApi
import com.asyncanimator.core.LogUtils
import com.asyncanimator.playback.NullableAnimatorListener
import com.asyncanimator.playback.NullableAnimatorListenerAdapter
import com.asyncanimator.thread.Executors

/**
 * 统一协调主平台集合、动画线程平台集合、主线程弹簧和矩形实际结束四条轨道。
 * 主构造接收两个平台集合、线程投递器及系统动画开关查询，构造本身不启动；聚合事件源为主animatorSet。
 * 公开操作和聚合状态属于主线程，异步集合运行操作经onAnimThread投递；后台值消费者只能依赖明确发布的取消信号。
 */
@PublicApi
class MultiAnimatorSet internal constructor(
    /** 聚合器的动画场景分类，供矩形通道及诊断使用；构造后保持不变。 */
    @property:PublicApi
    val animType: CustomRectFSpringAnim.AnimType,
    /** 主线程平台动画集合，作为聚合生命周期事件源；持有原实例，调用方不得绕过聚合器并发控制其运行。 */
    @property:PublicApi
    val animatorSet: AnimatorSet,
    /** 动画线程平台动画集合，运行命令由聚合器投递；读取引用不使平台集合具备跨线程安全性。 */
    @property:PublicApi
    val asyncAnimatorSet: AnimatorSet,
    private val onAnimThread: (() -> Unit) -> Unit,
    private val onMainThread: (() -> Unit) -> Unit,
    private val animationsEnabled: () -> Boolean
) {
    /**
     * 按指定场景创建空的主平台集合，并委托默认执行器构造完整聚合器。
     * 不启动任何轨道，调用方随后在主线程登记子动画和监听器。
     */
    @PublicApi
    constructor(animType: CustomRectFSpringAnim.AnimType) : this(AnimatorSet(), animType)

    /**
     * 复用传入主平台集合并新建异步集合，使用库的主/动画执行器和系统动画启用查询。
     * 持有而非克隆已有集合，其子动画会参与后续启动与销毁；调用方避免同时独立控制同一实例。
     */
    @PublicApi
    constructor(animatorSet: AnimatorSet, animType: CustomRectFSpringAnim.AnimType) : this(
        animType, animatorSet, AnimatorSet(),
        { Executors.ANIM_CONTROL_EXECUTOR.execute(it) },
        { Executors.MAIN_EXECUTOR.execute(it) },
        ValueAnimator::areAnimatorsEnabled
    )

    /**
     * 聚合完成屏障类别：MAIN、ASYNC分别对应两个平台集合，SPRINGS代表全部已登记弹簧，RECT代表矩形实际结束。
     * 各类别预先占位并独立移除，防止某个同步结束的子动画提前结束整个集合。
     */
    private enum class Track { MAIN, ASYNC, SPRINGS, RECT }
    private val pending = linkedSetOf<Track>()
    private val listeners = linkedSetOf<NullableAnimatorListener>()
    private val springs = linkedSetOf<SpringAnimation>()
    private val springListeners = mutableMapOf<SpringAnimation, DynamicAnimation.OnAnimationEndListener>()
    private val liveAnimators = linkedSetOf<Animator>()
    private val deferredCommands = mutableListOf<() -> Unit>()
    private var rect: CustomRectFSpringAnim? = null
    private var mainListener: AnimatorListenerAdapter? = null
    private var asyncListener: AnimatorListenerAdapter? = null
    private var resetViewState: ((Int) -> Unit)? = null
    private var starting = false
    private var cancelNotified = false
    @Volatile private var generation = 0L
    @Volatile private var started = false
    @Volatile private var disposed = false

    /**
     * 主线程配置的诊断标识，默认-1，启动矩形时复制给句柄，通知适配器时写入其animationId。
     * 该属性无运行期写保护或快照保证，宿主应保持单轮标识稳定。
     */
    @PublicApi
    var animationId: Int = -1

    /**
     * 本轮是否收到cancel请求，首次取消时置true，下一次start清零，外部只读。
     * 以volatile向后台数值/事务消费者发布；不表示所有轨道已经停帧，end不会置true。
     */
    @PublicApi
    @Volatile var hasRequestCancel: Boolean = false
        private set
    /**
     * 主线程读取是否仍有待完成轨道；只反映pending集合，不等同于内部started标记。
     * 空聚合或仅live追加动画不会产生独立屏障，不能将此属性作为后台同步信号。
     */
    @PublicApi
    val isRunning: Boolean get() = pending.isNotEmpty()
    /**
     * 返回当前绑定的矩形句柄引用，未登记或销毁后为null。
     * 主线程读取，不创建或复制句柄，调用方仍需遵守其生命周期与所有者约束。
     */
    @PublicApi
    val rectFSpringAnim: CustomRectFSpringAnim? get() = rect
    /**
     * 判断构造场景是否恰为OPEN_FROM_HOME，反转打开不算此分类。
     * 只读类型查询，不表示动画正在运行，也不检查驱动的实际几何方向。
     */
    @PublicApi
    val isAppOpenType: Boolean get() = animType == CustomRectFSpringAnim.AnimType.OPEN_FROM_HOME
    /**
     * 判断构造场景是否为GESTURE_TO_DRAG，供宿主选择手势相关策略。
     * 不读取轨道进度、不改变弹簧参数，也不触发任何动画操作。
     */
    @PublicApi
    val isGestureToDrag: Boolean get() = animType == CustomRectFSpringAnim.AnimType.GESTURE_TO_DRAG

    /**
     * 在主线程向去重集合添加聚合生命周期监听器，销毁后调用会失败。
     * 按快照通知，不会为新增监听补发历史事件；事件载荷使用主animatorSet。
     */
    @PublicApi
    fun addListener(listener: NullableAnimatorListener) {
        checkOwner()
        listeners.add(listener)
    }
    /**
     * 在主线程移除聚合监听器，未注册时无操作，销毁后拒绝调用。
     * 已取得的当前派发快照不重新检查成员资格，因此移除不保证撤回本次剩余通知。
     */
    @PublicApi
    fun removeListener(listener: NullableAnimatorListener) {
        checkOwner()
        listeners.remove(listener)
    }
    /**
     * 在主线程设置或清空聚合完成时的视图复位动作，传入null表示取消该动作。
     * 完成时先消费引用再回调并传入完成动画标识；停止时排除弹簧轨道会清除此动作。
     */
    @PublicApi
    fun setViewStateResetRunnable(callback: ((Int) -> Unit)?) {
        checkOwner()
        resetViewState = callback
    }

    /**
     * 在启动前把平台Animator登记到主线程AnimatorSet，等价于play(false, animator)。
     * 本方法不立即启动子动画，集合已启动时忽略；需在主线程且未销毁时调用。
     */
    @PublicApi
    fun play(animator: Animator) = play(false, animator)

    /**
     * 在启动前选择主或动画线程AnimatorSet并登记子动画，启动后忽略。
     * isAsync仅选择执行线程，异步子动画的属性写入必须适合后台线程，不能据此安全写View。
     */
    @PublicApi
    fun play(isAsync: Boolean, animator: Animator) {
        checkOwner()
        if (started) return
        (if (isAsync) asyncAnimatorSet else animatorSet).play(animator)
    }

    /**
     * 启动前按普通主线程子动画登记；运行中仅startImmediately为true时加入集合并立即独立启动。
     * 新增动画记入liveAnimators供取消/聚合完成清理，不另增完成屏障；此参数不是异步线程开关。
     */
    @PublicApi
    fun play(animator: Animator, startImmediately: Boolean) {
        checkOwner()
        if (!started) return play(animator)
        if (!startImmediately) return
        animatorSet.play(animator)
        liveAnimators.add(animator)
        animator.start()
    }

    /**
     * 按参数顺序逐个登记AndroidX弹簧，等价于重复调用单弹簧重载。
     * 重复实例会去重，运行中新增弹簧立即启动并纳入弹簧完成轨道；空数组无操作。
     */
    @PublicApi
    fun play(vararg animations: SpringAnimation) = animations.forEach(::play)

    /**
     * 在主线程登记去重弹簧；如果聚合已启动，立即补入SPRINGS屏障并安装结束监听/启动。
     * 已运行的弹簧不重复start，完成时由监听移除；本方法不把弹簧切到动画线程。
     */
    @PublicApi
    fun play(spring: SpringAnimation) {
        checkOwner()
        if (!springs.add(spring)) return
        if (started) {
            pending.add(Track.SPRINGS)
            startSpring(spring, generation)
        }
    }

    /**
     * 仅在尚未启动且未绑定矩形动画时保存句柄，缺少真实Driver时抛出参数异常。
     * 将句柄类型设为集合类型仅用于分类，不重配驱动几何参数；矩形实际结束才解除聚合屏障。
     */
    @PublicApi
    fun play(animation: CustomRectFSpringAnim) {
        checkOwner()
        if (started || rect != null) return
        requireNotNull(animation.driver) {
            "Rect playback requires an actual-end Driver; a bare handle cannot animate"
        }
        animation.animType = animType
        rect = animation
    }

    /**
     * 在主线程创建新代次，先预登记四类完成屏障，再通知启动并启动各轨道，防止同步结束过早完成。
     * 启动中的重入停止命令延后消费；同步启动失败会清除starting、销毁集合后重抛，动画线程稍后异常不由此捕获。
     * 系统禁用动画时只要求平台集合和弹簧结束，矩形仍等待自己的实际结束；已启动时无操作，空集合也会完成通知。
     */
    @PublicApi
    fun start() {
        checkOwner()
        if (started) return
        val run = ++generation
        started = true
        starting = true
        hasRequestCancel = false
        cancelNotified = false

        pending.clear()
        if (animatorSet.childAnimations.isNotEmpty()) pending.add(Track.MAIN)
        if (asyncAnimatorSet.childAnimations.isNotEmpty()) pending.add(Track.ASYNC)
        if (springs.isNotEmpty()) pending.add(Track.SPRINGS)
        if (rect != null) pending.add(Track.RECT)
        runCatching {
            notifyListeners(run) { it.onAnimationStart(animatorSet) }
            if (!active(run)) return@runCatching
            if (Track.MAIN in pending) {
                mainListener = object : AnimatorListenerAdapter() {
                    /**
                     * 主平台集合结束时先解绑本监听，仅在仍为当前监听时清空引用，再解除本代次MAIN屏障。
                     * 过期代次由finish忽略，不影响后继运行；不在此处直接宣布整个聚合结束。
                     */
                    override fun onAnimationEnd(animation: Animator) {
                        animation.removeListener(this)
                        if (mainListener === this) mainListener = null
                        finish(Track.MAIN, run)
                    }
                }.also(animatorSet::addListener)
                animatorSet.start()
            }
            springs.toList().forEach { if (active(run)) startSpring(it, run) }
            if (active(run)) rect?.let {
                it.animationId = animationId
                it.start { onMainThread { finish(Track.RECT, run) } }
            }
            if (active(run) && Track.ASYNC in pending) onAnimThread {
                if (active(run)) {
                    asyncListener = object : AnimatorListenerAdapter() {
                        /**
                         * 异步平台集合在动画线程结束时先移除自身监听并清空匹配引用，再回主线程解除ASYNC屏障。
                         * 捕获启动代次，旧运行消息不会结束后继运行；其他轨道仍须分别完成。
                         */
                        override fun onAnimationEnd(animation: Animator) {
                            animation.removeListener(this)
                            if (asyncListener === this) asyncListener = null
                            onMainThread { finish(Track.ASYNC, run) }
                        }
                    }.also(asyncAnimatorSet::addListener)
                    asyncAnimatorSet.start()
                }
            }
        }.also { starting = false }.onFailure { destroy() }.getOrThrow()
        val commands = deferredCommands.toList()
        deferredCommands.clear()
        commands.forEach { if (active(run)) it() }
        if (active(run) && !animationsEnabled()) end(TYPE_ANIMATOR_SET or TYPE_SPRING_ANIMATIONS)
        maybeOnEnd(run)
    }

    /**
     * 为尚未安装监听的弹簧注册一次结束观察，必要时启动，已运行弹簧仅接管完成观察。
     * 结束时解绑监听并从当前代次集合移除，最后一条弹簧完成才解除SPRINGS屏障；在主线程调用。
     */
    private fun startSpring(spring: SpringAnimation, run: Long) {
        if (springListeners.containsKey(spring)) return
        val listener = DynamicAnimation.OnAnimationEndListener { _, _, _, _ ->
            springListeners.remove(spring)?.let(spring::removeEndListener)
            if (active(run)) {
                springs.remove(spring)
                if (springs.isEmpty()) finish(Track.SPRINGS, run)
            }
        }
        springListeners[spring] = listener
        spring.addEndListener(listener)
        if (!spring.isRunning) spring.start()
    }

    /**
     * 在主线程按类型位掩码取消轨道，默认取消全部；未知位会抛出参数异常。
     * 首次取消会发布取消信号和聚合取消事件，物理结束继续等待各轨道；启动重入时命令排队。
     */
    @PublicApi
    fun cancel(type: Int = TYPE_ALL) = stopTracks(type, cancel = true)
    /**
     * 在主线程按类型位掩码要求轨道到终点，默认全部，不发布取消事件。
     * 弹簧只在canSkipToEnd为true时跳终点；未包含弹簧位会解绑弹簧而非停止其独立帧循环。
     */
    @PublicApi
    fun end(type: Int = TYPE_ALL) = stopTracks(type, cancel = false)
    /**
     * 取消主/异步平台集合及矩形动画，并把AndroidX弹簧从聚合屏障解绑但让其继续运行。
     * 同时清除视图复位动作；取消仍会处理运行中追加的live动画并通知聚合取消。
     */
    @PublicApi
    fun cancelAllAnimExceptSpringAnim() = cancel(TYPE_ANIMATOR_SET or TYPE_RECTF_SPRING_ANIM)
    /**
     * 让平台集合及矩形动画到终点，保留AndroidX弹簧自身运行但解除其监听和聚合屏障。
     * 清除视图复位动作，不标记取消；剩余受控轨道完成后可以结束聚合。
     */
    @PublicApi
    fun endAllAnimExceptSpringAnim() = end(TYPE_ANIMATOR_SET or TYPE_RECTF_SPRING_ANIM)

    /**
     * 验证主线程与合法位掩码，在启动阶段排队，否则按当前代次发送取消或到终点操作。
     * 取消最多通知一次并清理live动画；掩码排除弹簧时主动解绑弹簧及复位动作，包含时取消或尝试跳终点。
     * 主平台、异步平台和矩形分别在所属线程停止，重入后逐段检查代次，最后尝试聚合完成。
     */
    private fun stopTracks(type: Int, cancel: Boolean) {
        checkOwner()
        require(type and TYPE_ALL == type) { "Unknown animation type mask: $type" }
        if (!started) return
        if (starting) {
            deferredCommands.add { stopTracks(type, cancel) }
            return
        }
        val run = generation
        if (cancel) {
            hasRequestCancel = true
            if (!cancelNotified) {
                cancelNotified = true
                notifyListeners(run) { it.onAnimationCancel(animatorSet) }
            }
            if (!active(run)) return
            val live = liveAnimators.toList()
            liveAnimators.clear()
            live.forEach { if (active(run)) it.cancel() }
        }
        if (!active(run)) return

        if (type and TYPE_SPRING_ANIMATIONS == 0) {
            resetViewState = null
            springs.toList().forEach { spring -> springListeners.remove(spring)?.let(spring::removeEndListener) }
            springs.clear()
            pending.remove(Track.SPRINGS)
        }
        if (type and TYPE_ANIMATOR_SET != 0) {
            if (cancel) animatorSet.cancel() else animatorSet.end()

            if (!active(run)) return
            onAnimThread {
                if (active(run)) {
                    if (cancel) asyncAnimatorSet.cancel() else asyncAnimatorSet.end()
                }
            }
        }
        if (type and TYPE_SPRING_ANIMATIONS != 0) springs.toList().forEach {
            if (active(run)) {
                if (cancel) it.cancel() else if (it.canSkipToEnd()) it.skipToEnd()
            }
        }
        if (type and TYPE_RECTF_SPRING_ANIM != 0 && active(run)) {
            if (cancel) rect?.cancel() else rect?.skipToEnd()
        }
        maybeOnEnd(run)
    }

    /**
     * 仅为仍活动的指定代次移除一个轨道屏障，然后尝试完成聚合。
     * 重复完成不重复结束，过期代次或已销毁状态直接忽略；应在主线程调用。
     */
    private fun finish(track: Track, run: Long) {
        if (!active(run)) return
        pending.remove(track)
        maybeOnEnd(run)
    }

    /**
     * 只有有效代次、启动阶段结束且全部屏障已清空时才宣布完成。
     * 先标记未启动并消费复位回调/live集合，清矩形结束钩子，再取消live动画、执行复位并通知结束。
     * 回调允许重入创建新代次，新运行会使旧结束派发失效；复位动作异常由通知保护层记录。
     */
    private fun maybeOnEnd(run: Long) {
        if (!active(run) || starting || pending.isNotEmpty()) return
        started = false
        val callback = resetViewState
        val completedId = animationId
        val live = liveAnimators.toList()
        liveAnimators.clear()
        resetViewState = null
        rect?.clearEndCallback()
        live.forEach(Animator::cancel)
        if (callback != null) notifySafely { callback(completedId) }
        notifyListeners(run) { it.onAnimationEnd(animatorSet) }
    }

    /**
     * 遍历主线程监听快照，每项前检查销毁与代次，并向适配器写入当前animationId。
     * 允许回调重入，代次变化会停止后续通知；单个普通异常被记录，集合增删不破坏本轮遍历。
     */
    private fun notifyListeners(run: Long, notify: (NullableAnimatorListener) -> Unit) {
        for (listener in listeners.toList()) {
            if (disposed || generation != run) return
            (listener as? NullableAnimatorListenerAdapter)?.animationId = animationId
            notifySafely { notify(listener) }
        }
    }

    /**
     * 执行观察者或复位动作，捕获Exception并写诊断日志，使其不阻断后续轨道清理。
     * 不吞掉Error等严重失败，也不用于隐藏驱动自身启动/停止异常。
     */
    private inline fun notifySafely(action: () -> Unit) {
        try { action() }
        catch (error: Exception) {
            LogUtils.i("MultiAnimatorSet", "Observer failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /**
     * 在主线程最终销毁：递增代次、清空屏障/监听/延后命令，并解绑自身监听后取消所持轨道。
     * 矩形句柄dispose，异步平台监听与取消投递动画线程；不发送聚合结束或复位回调，旧消息失效。
     * 重复销毁直接返回；首次销毁后其他受检查入口拒绝使用。
     */
    @PublicApi
    fun destroy() {
        if (disposed) return
        checkOwner()
        disposed = true
        generation++
        started = false
        starting = false
        pending.clear()
        deferredCommands.clear()
        listeners.clear()
        resetViewState = null
        mainListener?.let(animatorSet::removeListener)
        mainListener = null
        animatorSet.cancel()
        springs.toList().forEach {
            springListeners.remove(it)?.let(it::removeEndListener)
            it.cancel()
        }
        springs.clear()
        rect?.dispose()
        rect = null
        liveAnimators.toList().forEach(Animator::cancel)
        liveAnimators.clear()
        onAnimThread {
            asyncListener?.let(asyncAnimatorSet::removeListener)
            asyncListener = null
            asyncAnimatorSet.cancel()
        }
    }

    /**
     * 判断集合未销毁、仍处于启动状态且代次与参数相等，供跨线程任务过滤旧运行。
     * 只读取volatile状态，不改变完成屏障；聚合完成后同代次也不再活动。
     */
    private fun active(run: Long) = !disposed && started && generation == run
    /**
     * 确认集合尚未销毁且当前Looper为主Looper，违反时抛出IllegalStateException。
     * 仅检查，不会自动投递线程；公开配置、监听和聚合状态变更依赖此约束。
     */
    private fun checkOwner() {
        check(!disposed) { "MultiAnimatorSet is destroyed" }
        check(Looper.myLooper() == Looper.getMainLooper()) { "MultiAnimatorSet must be called on the main thread" }
    }

    /** 四类聚合查询使用的通道位掩码常量容器；常量可在任意线程读取。 */
    @PublicApi
    companion object {
        /** 普通与异步 Animator 通道的查询位掩码，值为 1。 */
        @PublicApi
        const val TYPE_ANIMATOR_SET = 1
        /** View 属性弹簧通道的查询位掩码，值为 2。 */
        @PublicApi
        const val TYPE_SPRING_ANIMATIONS = 2
        /** 矩形弹簧通道的查询位掩码，值为 4。 */
        @PublicApi
        const val TYPE_RECTF_SPRING_ANIM = 4
        /** 全部动画通道的组合查询位掩码，值为 7。 */
        @PublicApi
        const val TYPE_ALL = 7
    }
}
