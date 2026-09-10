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

/**
 * 接收平台本帧animatedValue并应用到宿主目标的可空值回调类型。
 * 值可能是浮点、整型或自定义holder结果；在动画更新线程同步调用，宿主负责类型及线程安全。
 */
internal typealias ValueApplicator = (value: Any?) -> Unit

/**
 * 支持时间控制器委托与进度续行的值动画包装器，泛型仅作为调用侧类型标记。
 * 主构造保存参数及可空控制器，初始化浮点端点、正时长和可选插值器，并安装读取当前applicator的更新监听。
 * 有控制器时由其写fraction推进包装动画求值；本类不提供线程投递，参数及目标绑定应由动画所属线程维护。
 */
internal class OplusValueAnimator<T>(
    val param: AnimParam,
    private val timeController: TimeControllerObjectAnimator?
) : ValueAnimator() {

    /**
     * 创建默认0到1的浮点包装动画，参数名default、记录进度-1，且不带额外时间控制器。
     * 未指定正时长时沿用平台默认时长，尚无目标应用回调，构造不会启动。
     */
    constructor() : this(AnimParam(), null)

    init {

        super.setFloatValues(param.fromValue, param.toValue)
        if (param.duration > 0) setDuration(param.duration)
        param.interpolator?.let { super.setInterpolator(it) }

        addUpdateListener { a ->
            param.applicator?.invoke(a.animatedValue)
        }
    }

    /**
     * 返回参数容器当前name供日志和诊断使用，不缓存副本。
     * 外部修改param.name会立即反映在后续读取中，不影响动画的值或生命周期。
     */
    val animName: String get() = param.name

    /**
     * 用新的整型PropertyValuesHolder替换整个值定义，避免沿用初始化的浮点holder造成类型冲突。
     * 空参数不修改现值；自定义求值器应在设置值后安装，参数容器中的from/to不会随之同步。
     */
    override fun setIntValues(vararg values: Int) {
        if (values.isNotEmpty()) setValues(PropertyValuesHolder.ofInt("", *values))
    }

    /**
     * 用新的浮点PropertyValuesHolder替换整个关键帧值定义，支持多于两个取值。
     * 空参数不修改现定义；本方法不启动动画，也不更新AnimParam端点。
     */
    override fun setFloatValues(vararg values: Float) {
        if (values.isNotEmpty()) setValues(PropertyValuesHolder.ofFloat("", *values))
    }

    /**
     * 先委托平台计算并应用指定原始进度，再把输入值记录到参数容器用于后续续行。
     * 平台更新回调可能同步执行且此时记录仍是旧值；本方法不自行钳制或校验范围。
     */
    override fun setCurrentFraction(f: Float) {
        super.setCurrentFraction(f)
        param.currentFraction = f
    }

    /**
     * 把可空插值器交给平台并同步保存原引用到参数容器，供续行复制与输入记录识别。
     * 不克隆插值器，不改变时间控制器自身的线性驱动策略；null按平台默认行为处理。
     */
    override fun setInterpolator(i: TimeInterpolator?) {
        super.setInterpolator(i)
        param.interpolator = i
    }

    /**
     * 有时间控制器时启动控制器，由其属性写入推进本包装动画的fraction；否则启动平台自身。
     * 不自动切线程，更新值仍由本动画求值并调用applicator；未覆写的其他平台API不保证同样委托。
     */
    override fun start() {
        if (timeController != null) timeController.start()
        else super.start()
    }

    /**
     * 取消时间控制器或没有控制器时取消本平台动画，沿用所选对象的取消/结束回调。
     * 不同时取消两者，也不清空参数、目标绑定或外部监听。
     */
    override fun cancel() {
        if (timeController != null) timeController.cancel()
        else super.cancel()
    }

    /**
     * 结束时间控制器，或无控制器时结束本平台动画，使对应驱动应用终值。
     * 不会单独计算剩余时长或解除目标引用；线程与异常约束沿用实际驱动。
     */
    override fun end() {
        if (timeController != null) timeController.end()
        else super.end()
    }

    /**
     * 暂停存在的时间控制器，否则暂停本平台动画，不更改续行参数记录。
     * 该覆盖仅处理pause，不意味着所有未覆盖的播放控制方法都转发给控制器。
     */
    override fun pause() {
        if (timeController != null) timeController.pause()
        else super.pause()
    }

    /**
     * 优先返回时间控制器运行状态，无控制器时查询平台自身。
     * 只读查询，不依据currentFraction判断，也不表示目标值写入是否已在其他线程消费。
     */
    override fun isRunning(): Boolean =
        timeController?.isRunning() ?: super.isRunning()

    /**
     * 返回实际时间驱动的时长毫秒数：有控制器时取其duration，否则取平台duration。
     * 不直接读取参数容器，因此外部改写param.duration不会自动改变此返回值。
     */
    override fun getDuration(): Long =
        timeController?.duration ?: super.getDuration()

    /**
     * 读取时间控制器的播放毫秒数，无控制器时读取平台动画播放时间。
     * 只查询实际驱动，不由保存的fraction反算，也不触发一次值更新。
     */
    override fun getCurrentPlayTime(): Long =
        timeController?.currentPlayTime ?: super.getCurrentPlayTime()

    /**
     * 把毫秒时长写入实际驱动，成功后同步param.duration并返回当前包装对象用于链式调用。
     * 负数按底层约束失败，不在本层自动取绝对值；驱动设置失败时不更新参数记录。
     */
    override fun setDuration(duration: Long): ValueAnimator {
        if (timeController != null) {
            timeController.setDuration(duration)
        } else {
            super.setDuration(duration)
        }
        param.duration = duration
        return this
    }

    /**
     * 有控制器且监听非空时把生命周期监听注册给控制器，其余情况委托平台自身。
     * 不会同时注册到两处；更新监听仍由包装动画负责，未覆盖的监听管理方法不保证同步转发。
     */
    override fun addListener(l: Animator.AnimatorListener?) {
        if (timeController != null && l != null) timeController.addListener(l)
        else super.addListener(l)
    }

    /**
     * 可变动画配置和原始进度记录：名称、浮点端点、fraction、可空插值器/应用回调及毫秒时长。
     * 构造不校验参数；copy创建独立标量字段容器，但插值器和applicator及其捕获状态继续共享，绝非目标的深拷贝。
     * 直接修改字段不会普遍反向配置平台动画，应使用相应包装入口保持记录与驱动一致。
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

    /**
     * 把浮点时间值写入当前包装动画属性的控制器，初始无目标/属性且值域0到1。
     * 构造安装唯一更新监听，读取当前绑定并仅在目标与浮点值存在时写属性；重新绑定不会新增闭包保留旧目标。
     */
    open class TimeControllerObjectAnimator : PendingAnimation.ObjectAnimator(null, null, 0f, 1f) {

        private var target: OplusValueAnimator<*>? = null
        private var property: FloatProperty<OplusValueAnimator<*>>? = null

        init {

            addUpdateListener { animator ->
                val currentTarget = target
                val value = animator.animatedValue as? Float
                if (currentTarget != null && value != null) property?.setValue(currentTarget, value)
            }
        }

        /**
         * 替换时间控制器每帧写入的可空FloatProperty并返回控制器自身。
         * 传null暂停属性写入而不停止时间推进；单一更新监听每帧读取最新绑定，不累计旧属性闭包。
         */
        fun setProperty(prop: FloatProperty<OplusValueAnimator<*>>?): TimeControllerObjectAnimator = apply {
            property = prop
        }

        /**
         * 委托父类替换时间控制器的浮点值序列，并保留具体返回类型以支持链式配置。
         * 控制器通常用fraction到1作为取值，空数组行为沿用父类，不启动动画。
         */
        override fun setFloatValues(vararg values: Float): TimeControllerObjectAnimator = apply {
            super.setFloatValues(*values)
        }

        /**
         * 委托父类设置毫秒时长并返回当前时间控制器，负值由底层拒绝。
         * 只修改控制器，不反向同步其已绑定包装对象的参数容器。
         */
        override fun setDuration(duration: Long): TimeControllerObjectAnimator = apply {
            super.setDuration(duration)
        }

        /**
         * 更新或清空时间控制器当前包装动画目标，并返回自身。
         * 每帧统一监听读取最新目标，重新绑定不会继续写旧对象；null仅禁用写入，不停止控制器。
         */
        fun setTarget(target: OplusValueAnimator<*>?): TimeControllerObjectAnimator = apply {
            this.target = target
        }
    }

    companion object {

        /**
         * 按isAsync选择库包装动画或普通ValueAnimator，再设置浮点取值序列。
         * 此开关只选实现，不会移交动画线程；需要线程投递应使用AsyncValueAnimator，返回对象尚未启动。
         */
        fun ofFloat(isAsync: Boolean, vararg values: Float): ValueAnimator {
            val anim: ValueAnimator = if (isAsync) OplusValueAnimator<Any>() else ValueAnimator()
            anim.setFloatValues(*values)
            return anim
        }

        /**
         * 从源动画当前原始fraction创建续行动画：记录型插值器存在时先回写其最近输入，否则使用参数记录。
         * 源为空、进度非有限或不在[0,1)时记录失败并返回null；有效时复制参数并克隆全部holder，线性时间控制器从该进度推进到1。
         * 插值器/applicator引用仍共享，不复制源监听、不停止源动画；正durationMs覆盖控制器时长，非正保留构造时长，返回对象未启动。
         */
        fun <T> generateContinuationAnim(anim: OplusValueAnimator<T>?,
                                         durationMs: Long): OplusValueAnimator<T>? {
            if (anim == null) {
                LogUtils.i("OplusValueAnimator", "continuation rejected: null source")
                return null
            }

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

            val timeController = TimeControllerObjectAnimator()
            val newAnim = OplusValueAnimator<T>(anim.param.copy(), timeController)

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

        /**
         * 时间控制器与包装动画间的原始进度属性，读取参数记录，写入调用setCurrentFraction。
         * 保持单一求值/应用路径，不将插值后fraction误作续行的线性时间输入。
         */
        private val CURRENT_FRACTION: FloatProperty<OplusValueAnimator<*>> =
            object : FloatProperty<OplusValueAnimator<*>>("currentFraction") {

                /**
                 * 读取包装动画保存的原始currentFraction，供FloatProperty接口使用。
                 * 不查询平台插值后进度或时间控制器，不推进动画；尚未记录时可能为默认-1。
                 */
                override fun get(anim: OplusValueAnimator<*>): Float =
                    anim.param.currentFraction

                /**
                 * 通过包装动画setCurrentFraction应用控制器生成的原始进度。
                 * 由包装动画触发平台求值和applicator再记录进度，本属性不重复调用业务更新或自行插值。
                 */
                override fun setValue(anim: OplusValueAnimator<*>, v: Float) {
                    anim.setCurrentFraction(v)
                }
            }
    }
}
