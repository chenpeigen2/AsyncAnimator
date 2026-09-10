package com.asyncanimator.anim

import com.asyncanimator.api.PublicApi

/**
 * 矩形弹簧的不可变配置，各轴参数和设备/场景策略由调用方显式提供。
 * 构造时要求minimumSize有限且大于0，透明度延迟毫秒数非负，durationMultiplier有限且非负，并预检派生刚度。
 * centerX/trackedY/size/radius使用几何坐标单位，ratio为高宽比，alpha为透明度；tracking选择纵向锚点。
 * limitAspectRatio限制比例轴在起终值范围内，durationMultiplier是额外业务时长策略，不代替系统动画倍率。
 * 数据类copy保留未指定字段并重新执行构造校验；配置本身不订阅帧、不修改运行中的驱动。
 */
@PublicApi
data class RectSpringConfig @PublicApi constructor(
    /** 中心 X 坐标轴的不可变弹簧参数；坐标单位与输入矩形一致。 */
    @property:PublicApi
    val centerX: Spring = Spring(),
    /** 纵向追踪锚点的弹簧参数；锚点由 tracking 选择，位置与速度须使用同一坐标系。 */
    @property:PublicApi
    val trackedY: Spring = Spring(),
    /** 主尺寸轴的弹簧参数；使用宽还是高由驱动本轮的 sizeIsWidth 决定。 */
    @property:PublicApi
    val size: Spring = Spring(),
    /** 高宽比轴的弹簧参数；默认可见变化阈值为 0.005。 */
    @property:PublicApi
    val ratio: Spring = Spring(minimumVisibleChange = 0.005f),
    /** 圆角轴的弹簧参数；单位由帧消费者约定，默认可见变化阈值为 1。 */
    @property:PublicApi
    val radius: Spring = Spring(minimumVisibleChange = 1f),
    /** 透明度轴的弹簧参数；默认可见变化阈值为 0.05，不自行执行 View 或表面写入。 */
    @property:PublicApi
    val alpha: Spring = Spring(minimumVisibleChange = 0.05f),
    /** 纵向追踪锚点，默认顶边；改变配置时调用方须同步转换纵向位置及速度。 */
    @property:PublicApi
    val tracking: Tracking = Tracking.TOP,
    /** 还原矩形时使用的最小尺寸下界，必须为有限正数，默认 0.1。 */
    @property:PublicApi
    val minimumSize: Float = 0.1f,
    /** 是否把比例轴约束在起终值范围内；默认关闭，不限制其他轴的弹簧超调。 */
    @property:PublicApi
    val limitAspectRatio: Boolean = false,
    /** 透明度轴开始推进前的延迟毫秒数，必须非负，默认零。 */
    @property:PublicApi
    val alphaStartDelayMillis: Long = 0,

    /** 业务时长倍率，必须有限且非负；默认 1，非零时按倍率平方反向调整刚度。 */
    @property:PublicApi
    val durationMultiplier: Float = 1f,
    /** 轻动画策略标记；启用且时长倍率非零时，刚度计算使用倍率乘 0.4 后的平方。 */
    @property:PublicApi
    val lightAnimation: Boolean = false,
    /** 评估场景策略标记；启用时刚度计算的缩放因子额外乘 0.36。 */
    @property:PublicApi
    val evaluationScene: Boolean = false,
    /** 调用方提供的平板策略标记；影响打开动画纵向轴的可见变化阈值，不自行探测设备。 */
    @property:PublicApi
    val tablet: Boolean = false,
    /** 调用方提供的折叠设备展开标记；影响打开动画纵向轴的可见变化阈值。 */
    @property:PublicApi
    val foldExpanded: Boolean = false,
    /** 调用方提供的宽折叠设备标记；启用时比例轴可见变化阈值采用 0.001。 */
    @property:PublicApi
    val foldWide: Boolean = false
) {
    /**
     * 矩形纵向位置的追踪锚点：TOP为顶边、CENTER为中心、BOTTOM为底边。
     * 驱动据此将trackedY还原为矩形top；改变锚点意味着位置和速度也必须使用对应坐标。
     */
    @PublicApi
    enum class Tracking {
        /** 追踪矩形顶边，纵向位置和速度均以顶边为基准。 */
        @PublicApi
        TOP,
        /** 追踪矩形中心，纵向位置和速度均以中心为基准。 */
        @PublicApi
        CENTER,
        /** 追踪矩形底边，纵向位置和速度均以底边为基准。 */
        @PublicApi
        BOTTOM
    }
    /**
     * 单轴弹簧参数：stiffness为刚度，dampingRatio为阻尼比，minimumVisibleChange为该轴可见变化阈值。
     * 构造及copy均要求数值有限，刚度和阈值为正，阻尼比非负；默认200、1、0.1，不接受非法物理参数。
     */
    @PublicApi
    data class Spring @PublicApi constructor(
        /** 单轴刚度，必须为有限正数；默认 200，copy 也会重新校验。 */
        @property:PublicApi
        val stiffness: Float = 200f,
        /** 单轴阻尼比，必须有限且非负；默认 1 表示临界阻尼。 */
        @property:PublicApi
        val dampingRatio: Float = 1f,
        /** 该轴停止判定的可见变化阈值，必须为有限正数；默认 0.1，单位与轴值一致。 */
        @property:PublicApi
        val minimumVisibleChange: Float = 0.1f
    ) {
        init {
            require(stiffness.isFinite() && stiffness > 0)
            require(dampingRatio.isFinite() && dampingRatio >= 0)
            require(minimumVisibleChange.isFinite() && minimumVisibleChange > 0)
        }
    }
    init {
        require(minimumSize.isFinite() && minimumSize > 0)
        require(alphaStartDelayMillis >= 0)
        require(durationMultiplier.isFinite() && durationMultiplier >= 0)

        parameters(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME)
    }

    /**
     * 按中心X、追踪Y、尺寸、高宽比、圆角、透明度顺序生成本次动画的六轴参数副本。
     * 打开类动画和设备形态覆盖可见变化阈值；除拖拽类型外按时长策略的平方反向缩放刚度，评估场景再乘0.36。
     * durationMultiplier为0时不执行时长平方缩放；极端缩放导致派生刚度非有限或非正时，Spring构造校验抛出异常。
     */
    internal fun parameters(type: CustomRectFSpringAnim.AnimType): List<Spring> {
        val opening = type == CustomRectFSpringAnim.AnimType.OPEN_FROM_HOME ||
            type == CustomRectFSpringAnim.AnimType.REVERSE_TO_OPEN
        val thresholds = listOf(
            if (opening) 1f else centerX.minimumVisibleChange,
            if (opening) { if (tablet || foldExpanded) 0.1f else 1f } else trackedY.minimumVisibleChange,
            if (opening) 1f else size.minimumVisibleChange,
            if (opening || foldWide) 0.001f else ratio.minimumVisibleChange,
            radius.minimumVisibleChange, alpha.minimumVisibleChange
        )
        var factor = if (durationMultiplier == 0f) 1.0 else durationMultiplier.toDouble().let {
            val scale = if (lightAnimation) it * 0.4 else it
            scale * scale
        }
        if (evaluationScene) factor *= 0.36
        return listOf(centerX, trackedY, size, ratio, radius, alpha).mapIndexed { index, spring ->
            val stiffness = if (type == CustomRectFSpringAnim.AnimType.GESTURE_TO_DRAG) spring.stiffness
                else (spring.stiffness / factor).toFloat()
            spring.copy(stiffness = stiffness, minimumVisibleChange = thresholds[index])
        }
    }
}

/**
 * 六轴不可变数值或速度快照，默认各项为0，不在构造时校验数值。
 * centerX与trackedY为位置，size依模式为宽或高，ratio为高宽比，radius为圆角半径，alpha为透明度；作为速度时使用各自单位每秒。
 * copy生成新值对象，未指定分量保持不变；尺寸速度必须与当前宽/高模式配套。
 */
@PublicApi
data class RectSpringValues @PublicApi constructor(
    /** 中心 X 位置或其速度分量；速度快照采用每秒单位，默认零。 */
    @property:PublicApi
    val centerX: Float = 0f,
    /** 所选纵向锚点的位置或其每秒速度分量；默认零。 */
    @property:PublicApi
    val trackedY: Float = 0f,
    /** 宽或高主尺寸的值或每秒速度分量；含义须与当前驱动的 sizeIsWidth 一致。 */
    @property:PublicApi
    val size: Float = 0f,
    /** 高宽比的值或每秒变化率分量；默认零，快照构造本身不校验物理有效性。 */
    @property:PublicApi
    val ratio: Float = 0f,
    /** 圆角值或每秒速度分量；单位由帧消费者约定，默认零。 */
    @property:PublicApi
    val radius: Float = 0f,
    /** 透明度值或每秒变化率分量；默认零，快照本身不执行范围裁剪。 */
    @property:PublicApi
    val alpha: Float = 0f
) {
    /**
     * 按中心X、追踪Y、尺寸、高宽比、圆角、透明度的固定顺序创建六元素数组。
     * 返回独立可变数组，修改数组不会改变此值对象；本方法不转换坐标、单位或检查数值范围。
     */
    internal fun array() = floatArrayOf(centerX, trackedY, size, ratio, radius, alpha)
    internal companion object {
        /**
         * 依次读取输入数组的前六项，创建独立的六轴值对象，不保留数组引用。
         * 要求至少六项，长度不足会抛出下标异常；多余元素忽略，不校验有限性或取值范围。
         */
        fun from(a: FloatArray) = RectSpringValues(a[0], a[1], a[2], a[3], a[4], a[5])
    }
}

/**
 * 可跨线程读取的不可变矩形帧，保存四条边、六轴数值/速度、进度以及尺寸坐标模式。
 * 构造器按原样存储参数，不验证几何或进度范围；sizeIsWidth决定size是宽还是高，反转进度可能向0回落。
 * copy保留未指定字段，嵌套值对象不可变；与可变RectF的边界通过rect属性的防御性创建隔离。
 */
@PublicApi
data class RectSpringFrame @PublicApi constructor(
    /** 本帧还原后的矩形左边界；与输入矩形使用同一坐标单位。 */
    @property:PublicApi
    val left: Float,
    /** 本帧还原后的矩形上边界；已按配置的纵向锚点还原。 */
    @property:PublicApi
    val top: Float,
    /** 本帧还原后的矩形右边界；快照不自行写入平台窗口。 */
    @property:PublicApi
    val right: Float,
    /** 本帧还原后的矩形下边界；快照不自行写入平台窗口。 */
    @property:PublicApi
    val bottom: Float,
    /** 本帧六轴不可变数值快照，不与后续积分步骤共享可变数组。 */
    @property:PublicApi
    val values: RectSpringValues,
    /** 本帧六轴每秒速度快照；用于衔接时须与数值轴及锚点语义保持一致。 */
    @property:PublicApi
    val velocities: RectSpringValues,
    /** 本帧归一化进度估计，用于观察而不是平台动画结束屏障。 */
    @property:PublicApi
    val progress: Float,
    /** 本帧 size 轴是否代表宽度；为 false 时代表高度，用于正确解释数值与速度。 */
    @property:PublicApi
    val sizeIsWidth: Boolean
) {
    /**
     * 按本帧四边坐标每次创建新的RectF；调用方可以修改返回矩形而不影响此快照或驱动。
     * 读取不推进动画、不缓存可变对象，也不切换线程。
     */
    @PublicApi
    val rect: android.graphics.RectF get() = android.graphics.RectF(left, top, right, bottom)
}
