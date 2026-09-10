package com.asyncanimator.playback

import android.animation.TimeInterpolator
import kotlin.math.abs

/**
 * 无状态的基础插值曲线和进度映射工具，不持有动画或目标对象。
 * 区间裁剪、输出映射与曲线反转各自保持明确的端点策略，调用方负责选择合适的单位和输入域。
 */
internal object Interpolators {
    /**
     * 线性映射，直接返回输入；不会把越界或非有限值限制在零到一。
     */
    val LINEAR = TimeInterpolator { it }
    /**
     * 四次滚动减速曲线，在标准零到一区间内从零平滑趋近一；区间外不保证单调或钳制。
     */
    val SCROLL = TimeInterpolator { input ->
        val remaining = abs(input - 1f)
        1f - remaining * remaining * remaining * remaining
    }
    /**
     * 三次滚动减速曲线，标准区间端点为零和一；用于较低速度的滚动收尾。
     */
    val SCROLL_CUBIC = TimeInterpolator { input ->
        val offset = input - 1f
        offset * offset * offset + 1f
    }

    /**
     * 根据速度绝对值选择滚动减速曲线，不改变传入速度或保存状态。
     * @param velocity 速度，单位像素/毫秒；绝对值严格大于 10 使用四次曲线，恰好正负 10 使用三次曲线。
     * 非有限输入不单独校验，按 Float 比较结果选择曲线。
     */
    fun scrollInterpolatorForVelocity(velocity: Float): TimeInterpolator =
        if (abs(velocity) > 10f) SCROLL else SCROLL_CUBIC

    /**
     * 创建只在指定输入区间内运行的插值器，区间外分别保持零或一。
     * 构造时验证上界不小于下界，返回对象在每次求值时委托数值重载处理边界与归一化。
     * @throws IllegalArgumentException 上界小于下界，或任一边界为 NaN 导致比较不成立。
     */
    fun clampToProgress(interpolator: TimeInterpolator, lowerBound: Float,
                        upperBound: Float): TimeInterpolator {
        require(upperBound >= lowerBound) { "upperBound must be >= lowerBound" }
        return TimeInterpolator { input ->
            clampToProgress(interpolator, input, lowerBound, upperBound)
        }
    }

    /**
     * 把 progress 按区间归一化后交给指定曲线，低于区间返回零，高于区间返回一。
     * 零宽区间在等于边界时直接形成阶跃：边界为零返回零，其他边界返回一，不调用委托曲线。
     * 边界需要满足 upperBound >= lowerBound；委托的返回值不再钳制，异常原样传播。
     */
    fun clampToProgress(interpolator: TimeInterpolator, progress: Float,
                        lowerBound: Float, upperBound: Float): Float {
        require(upperBound >= lowerBound) { "upperBound must be >= lowerBound" }

        if (progress == lowerBound && progress == upperBound) {
            return if (progress == 0f) 0f else 1f
        }
        if (progress < lowerBound) return 0f
        if (progress > upperBound) return 1f
        return interpolator.getInterpolation((progress - lowerBound) / (upperBound - lowerBound))
    }

    /**
     * 以线性曲线将 progress 映射到区间内的零到一进度，区间外保持端点。
     * 与带插值器的数值重载使用相同的区间校验、零宽区间和边界语义，不创建新的曲线实例。
     */
    fun clampToProgress(progress: Float, lowerBound: Float, upperBound: Float): Float =
        clampToProgress(LINEAR, progress, lowerBound, upperBound)

    /**
     * 创建把曲线输出仿射映射到 start/end 数值范围的插值器。
     * 允许递减区间、相同端点和曲线超调；不钳制输入或输出，每次求值都会调用委托曲线。
     */
    fun mapToProgress(interpolator: TimeInterpolator, start: Float, end: Float): TimeInterpolator =
        TimeInterpolator { start + (end - start) * interpolator.getInterpolation(it) }

    /**
     * 创建按 1 - f(1 - input) 反转时间与输出方向的插值器。
     * 不修改被包装曲线，不进行区间校验或钳制；重复反转可恢复原曲线的数学映射。
     */
    fun reverse(interpolator: TimeInterpolator): TimeInterpolator =
        TimeInterpolator { 1f - interpolator.getInterpolation(1f - it) }
}
