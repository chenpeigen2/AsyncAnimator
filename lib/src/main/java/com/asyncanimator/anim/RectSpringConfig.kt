package com.asyncanimator.anim

/**
 * 矩形弹簧的不可变配置，各轴参数和设备/场景策略由调用方显式提供。
 * 构造时要求minimumSize有限且大于0，透明度延迟毫秒数非负，durationMultiplier有限且非负，并预检派生刚度。
 * centerX/trackedY/size/radius使用几何坐标单位，ratio为高宽比，alpha为透明度；tracking选择纵向锚点。
 * limitAspectRatio限制比例轴在起终值范围内，durationMultiplier是额外业务时长策略，不代替系统动画倍率。
 * 数据类copy保留未指定字段并重新执行构造校验；配置本身不订阅帧、不修改运行中的驱动。
 */
data class RectSpringConfig(
    val centerX: Spring = Spring(),
    val trackedY: Spring = Spring(),
    val size: Spring = Spring(),
    val ratio: Spring = Spring(minimumVisibleChange = 0.005f),
    val radius: Spring = Spring(minimumVisibleChange = 1f),
    val alpha: Spring = Spring(minimumVisibleChange = 0.05f),
    val tracking: Tracking = Tracking.TOP,
    val minimumSize: Float = 0.1f,
    val limitAspectRatio: Boolean = false,
    val alphaStartDelayMillis: Long = 0,

    val durationMultiplier: Float = 1f,
    val lightAnimation: Boolean = false,
    val evaluationScene: Boolean = false,
    val tablet: Boolean = false,
    val foldExpanded: Boolean = false,
    val foldWide: Boolean = false
) {
    /**
     * 矩形纵向位置的追踪锚点：TOP为顶边、CENTER为中心、BOTTOM为底边。
     * 驱动据此将trackedY还原为矩形top；改变锚点意味着位置和速度也必须使用对应坐标。
     */
    enum class Tracking { TOP, CENTER, BOTTOM }
    /**
     * 单轴弹簧参数：stiffness为刚度，dampingRatio为阻尼比，minimumVisibleChange为该轴可见变化阈值。
     * 构造及copy均要求数值有限，刚度和阈值为正，阻尼比非负；默认200、1、0.1，不接受非法物理参数。
     */
    data class Spring(
        val stiffness: Float = 200f,
        val dampingRatio: Float = 1f,
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
data class RectSpringValues(
    val centerX: Float = 0f, val trackedY: Float = 0f, val size: Float = 0f,
    val ratio: Float = 0f, val radius: Float = 0f, val alpha: Float = 0f
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
data class RectSpringFrame(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val values: RectSpringValues, val velocities: RectSpringValues,
    val progress: Float, val sizeIsWidth: Boolean
) {
    /**
     * 按本帧四边坐标每次创建新的RectF；调用方可以修改返回矩形而不影响此快照或驱动。
     * 读取不推进动画、不缓存可变对象，也不切换线程。
     */
    val rect: android.graphics.RectF get() = android.graphics.RectF(left, top, right, bottom)
}
