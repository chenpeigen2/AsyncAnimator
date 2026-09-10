package com.asyncanimator.playback

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.animation.ObjectAnimator as PlatformObjectAnimator
import android.util.FloatProperty

/**
 * 将属性动画、普通子动画及帧/结束回调组装为并行集合，并提供单主时钟回放入口。
 * 构造将总毫秒时长钳制为非负，创建空根集合和Holder列表，安装完成标记监听；不立即启动。
 * 配置/回放应在动画所属线程串行完成，控制器首次创建会快照Holder，因此请先组装再创建。
 */
internal class PendingAnimation(duration: Long) : PropertySetter {

    private val anim = AnimatorSet()
    private val animHolders = mutableListOf<AnimatorPlaybackController.Holder>()
    private val durationMs: Long = duration.coerceAtLeast(0)
    private var progressAnimator: ValueAnimator? = null
    private var controller: AnimatorPlaybackController? = null

    /**
     * 根集合最近收到结束或取消通知时为true，开始通知清零，初始false。
     * 属性可由外部写入且不带线程同步，仅为逻辑标记，不作为真实帧完成屏障。
     */
    var isAnimFinished = false

    init {
        anim.addListener(object : AnimatorListenerAdapter() {
            /**
             * 根集合开始通知时把isAnimFinished清为false，标识新一轮逻辑生命周期。
             * 不重建子动画或清除缓存控制器，也不主动驱动帧更新。
             */
            override fun onAnimationStart(a: Animator) { isAnimFinished = false }
            /**
             * 根集合结束通知时把isAnimFinished置true，不区分自然到终点还是取消后结束。
             * 仅记录逻辑事件，不证明所有外部动画或表面事务已物理完成。
             */
            override fun onAnimationEnd(a: Animator) { isAnimFinished = true }
            /**
             * 根集合取消通知时立即将isAnimFinished置true，不必等待随后结束通知。
             * 不清空已组装动画或控制器，具体资源释放由持有者负责。
             */
            override fun onAnimationCancel(a: Animator) { isAnimFinished = true }
        })
    }

    /**
     * 将子动画时长统一为本组durationMs，加入并行动画集合并递归登记Holder，返回组装器。
     * 会修改传入动画，未知子类型在收集阶段失败；应在创建缓存控制器前完成组装。
     */
    fun add(child: Animator): PendingAnimation = apply {
        child.duration = durationMs
        anim.playTogether(child)
        addToHolders(child)
    }

    /**
     * 先设置子动画可空插值器，再走普通add统一时长并登记Holder。
     * null沿用平台解释，不复制动画或插值器；设置/收集异常直接传播。
     */
    fun add(child: Animator, ip: TimeInterpolator?): PendingAnimation {
        child.interpolator = ip
        return add(child)
    }

    /**
     * 把子动画加入并行集合并收集Holder，不主动覆写该子节点时长，返回组装器。
     * 若子节点是集合，其自身有效时长/插值器仍会由递归收集传播到下层。
     */
    fun addWithoutDuration(child: Animator): PendingAnimation = apply {
        anim.playTogether(child)
        addToHolders(child)
    }

    /**
     * 使用显式from/to创建平台浮点属性动画，设置可空插值器后按本组时长登记。
     * 构造不立即写目标属性，真实求值发生于平台初始化/seek/播放；调用方负责属性目标的线程约束。
     */
    fun <T> addFloat(target: T, property: FloatProperty<T>,
                     from: Float, to: Float, ip: TimeInterpolator?): PendingAnimation {
        val oa = PlatformObjectAnimator.ofFloat(target, property, from, to)
        oa.interpolator = ip
        return add(oa)
    }

    /**
     * 属性为空或当前读值与目标相等时不创建动画，否则只指定终值创建平台属性动画并登记。
     * 起值留给平台初始化或首次seek读取，避免组装时固定过期值；不是立即写入，读取/get异常直接传播。
     */
    override fun <T> setFloat(target: T, property: FloatProperty<T>?, value: Float,
                              interpolator: TimeInterpolator) {
        if (property == null || property.get(target) == value) return

        val oa = PlatformObjectAnimator.ofFloat(target, property, value)
        oa.interpolator = interpolator
        add(oa)
    }

    /**
     * 给懒建进度动画添加结束适配监听，成功/取消结果由AnimatorListeners.forEndCallback决定。
     * 可空动作按适配器规则处理，返回组装器；进度动画在buildAnim时才并入统一时长集合。
     */
    fun addEndListener(onEnd: ((success: Boolean) -> Unit)?): PendingAnimation = apply {
        progressAnimator().addListener(AnimatorListeners.forEndCallback(onEnd))
    }

    /**
     * 给懒建进度动画添加每次值更新时同步执行的动作，返回组装器。
     * 回调不携带进度，seek也可触发，不保证每个显示VSYNC恰好调用一次。
     */
    fun addOnFrameCallback(onFrame: () -> Unit): PendingAnimation = apply {
        progressAnimator().addUpdateListener { onFrame() }
    }

    /**
     * 直接向根AnimatorSet添加平台生命周期监听并返回组装器，不包装或去重。
     * 之后可由平台或回放控制器显式派发触发，调用方负责监听捕获资源的生命周期。
     */
    fun addListener(l: Animator.AnimatorListener): PendingAnimation = apply {
        anim.addListener(l)
    }

    /**
     * 把尚未并入的进度动画按统一时长加入集合并清空暂存引用；无Holder时补入占位值动画。
     * 返回同一个根集合且不启动，重复构建不会重复加入已消费的进度动画，但后来新建的进度动画仍可再并入。
     */
    fun buildAnim(): AnimatorSet {
        progressAnimator?.let {
            add(it)
            progressAnimator = null
        }
        if (animHolders.isEmpty()) {
            addWithoutDuration(ValueAnimator.ofFloat(0f, 1f).setDuration(durationMs))
        }
        return anim
    }

    /**
     * 首次构建根集合并创建快照当前Holder列表的回放控制器，以后返回缓存实例。
     * 应先完成子动画和回调组装；缓存创建后的新增Holder不会自动进入已有控制器。
     */
    fun createPlaybackController(): AnimatorPlaybackController =
        controller ?: AnimatorPlaybackController(buildAnim(), durationMs, animHolders)
            .also { controller = it }

    /**
     * 按需创建并缓存0到1的进度ValueAnimator，供帧/结束回调统一挂载。
     * 创建时不设置组时长、不加入根集合，buildAnim通过add补齐；消费后再次调用可创建新实例。
     */
    private fun progressAnimator(): ValueAnimator =
        progressAnimator ?: ValueAnimator.ofFloat(0f, 1f).also { progressAnimator = it }

    /**
     * 递归把子动画中的ValueAnimator登记到当前Holder列表，以durationMs作为全局基准。
     * 集合有效时长/插值器继承在收集中处理，未知类型抛错；不会清空既有Holder或更新已缓存控制器。
     */
    private fun addToHolders(child: Animator) {
        AnimatorPlaybackController.addHoldersRecur(child, durationMs, animHolders)
    }

    /**
     * 稳定ValueAnimator的轻量浮点属性适配器，主构造保存可空目标/属性及固定from/to。
     * 两者均非空时仅安装一次监听，用内部animatedValue进行端点映射；任一为空不建立属性绑定。
     * 不是平台ObjectAnimator，setFloat/addFloat使用真正的平台实现；时间控制器子类可自行注册新的目标写入路径。
     */
    open class ObjectAnimator(
        private val target: Any?,
        private val property: FloatProperty<*>?,
        private val from: Float,
        private val to: Float
    ) {

        private val va: ValueAnimator = ValueAnimator.ofFloat(0f, 1f)

        init {

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

        /**
         * 把可空插值器写给内部ValueAnimator并返回适配器，用于链式配置。
         * 后续属性映射读取已插值的animatedValue，本方法不立即计算或写目标值。
         */
        open fun setInterpolator(ip: TimeInterpolator?): ObjectAnimator = apply {
            va.interpolator = ip
        }

        /**
         * 设置内部ValueAnimator的毫秒时长并返回适配器，非法负值由平台拒绝。
         * 不缩放from/to，也不启动或重建内部动画。
         */
        open fun setDuration(duration: Long): ObjectAnimator = apply {
            va.duration = duration
        }

        /**
         * 委托平台替换内部浮点关键帧值并返回适配器，空数组按平台规则处理。
         * 属性监听仍将该animatedValue当作映射参数from+(to-from)*f，非0到1值域可产生外推。
         */
        open fun setFloatValues(vararg values: Float): ObjectAnimator = apply {
            va.setFloatValues(*values)
        }

        /**
         * 返回构造时创建的同一个内部ValueAnimator，不再添加属性更新监听。
         * 重复调用不会重复写属性；调用方直接配置返回实例会影响后续所有委托操作。
         */
        fun buildAnimator(): ValueAnimator = va

        /**
         * 读取内部ValueAnimator当前毫秒时长，与setDuration配置同一实例。
         * 不返回构造端点或外部包装参数，也不推进动画。
         */
        val duration: Long get() = va.duration
        /**
         * 读取内部ValueAnimator的当前播放毫秒数，不由属性值反算。
         * 返回平台当前状态，读取不触发属性更新或重新初始化。
         */
        val currentPlayTime: Long get() = va.currentPlayTime

        /**
         * 在当前调用线程直接启动内部ValueAnimator，不创建替代实例或另行投递执行器。
         * 平台线程/Looper约束和启动异常原样保留，属性更新通过构造时安装的唯一监听完成。
         */
        fun start() = va.start()
        /**
         * 直接取消内部ValueAnimator并使用平台取消/结束通知，不移除更新或生命周期监听。
         * 不会清空目标绑定，也不把取消解释成成功完成。
         */
        fun cancel() = va.cancel()
        /**
         * 直接结束内部ValueAnimator，由平台应用终值并派发相应结束通知。
         * 不额外写一次目标属性，避免重复求值和回调。
         */
        fun end() = va.end()
        /**
         * 直接调用内部ValueAnimator.pause，保留其当前播放状态和监听绑定。
         * 与回放控制器用cancel实现的pause不同，此适配器使用平台暂停语义。
         */
        fun pause() = va.pause()
        /**
         * 返回内部ValueAnimator的isRunning状态，不查询外部包装对象或目标属性。
         * 只读且不推进时间，不意味着业务已经消费本帧结果。
         */
        fun isRunning(): Boolean = va.isRunning
        /**
         * 向稳定的内部ValueAnimator注册平台生命周期监听，不自行包装或去重。
         * 后续start/cancel/end都在同一实例触发，调用方负责需要时移除监听。
         */
        fun addListener(l: Animator.AnimatorListener) = va.addListener(l)
        /**
         * 把更新监听追加到内部ValueAnimator并返回适配器，允许控制器子类建立自定义写入链路。
         * 不在每次buildAnimator时重复注册，回调同步运行于动画更新线程。
         */
        fun addUpdateListener(l: ValueAnimator.AnimatorUpdateListener): ObjectAnimator = apply {
            va.addUpdateListener(l)
        }

        companion object {

            /**
             * 创建保存指定目标、FloatProperty和from/to的轻量适配器，内部用0到1值驱动线性端点映射。
             * 返回对象尚未启动，构造只安装一次更新监听；与属性组装入口使用的平台ObjectAnimator不是同一类型。
             */
            fun <T> ofFloat(target: T, property: FloatProperty<T>,
                            from: Float, to: Float): ObjectAnimator =
                ObjectAnimator(target, property, from, to)
        }
    }
}
