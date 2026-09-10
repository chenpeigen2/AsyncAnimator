package com.asyncanimator.anim

import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 用于预览弹簧状态的无副作用解析计算器。
 * 求解相对目标位置的阻尼振子方程；实际播放仍由 AndroidX SpringAnimation 负责调度和结束判断。
 */
internal object SpringProjection {
    /**
     * 根据当前位移、速度和 SpringForce 参数，解析预测给定毫秒后的位置与速度。
     * 先以目标位置为原点，将毫秒转换为秒，再按欠阻尼、临界阻尼或过阻尼分别计算闭式解。
     * 零阻尼走欠阻尼分支；内部以 Double 运算，返回时转换为 Float，不修改力、动画或时钟。
     * @param force 提供刚度、阻尼比和最终位置的参数对象；求值期间应保持配置不变。
     * @param value 当前绝对位置，与 force.finalPosition 使用相同坐标单位。
     * @param velocity 当前每秒速度，与位置坐标一致。
     * @param millis 预测时长，调用方应先保证非负；本方法不校验输入或处理帧预热、延迟、系统时长倍率。
     * @return 预测绝对位置与每秒速度；不做边界裁剪、平衡阈值吸附或完成回调。
     */
    fun advance(force: SpringForce, value: Float, velocity: Float, millis: Long): Pair<Float, Float> {
        val t = millis / 1000.0
        val w = sqrt(force.stiffness.toDouble())
        val z = force.dampingRatio.toDouble()
        val x = value.toDouble() - force.finalPosition
        val v = velocity.toDouble()
        val next: Double
        val speed: Double
        if (z == 1.0) {
            val b = v + w * x
            val decay = exp(-w * t)
            next = (x + b * t) * decay
            speed = (b - w * (x + b * t)) * decay
        } else if (z < 1.0) {
            val frequency = w * sqrt(1.0 - z * z)
            val b = (v + z * w * x) / frequency
            val decay = exp(-z * w * t)
            next = decay * (x * cos(frequency * t) + b * sin(frequency * t))
            speed = -z * w * next + decay * frequency * (-x * sin(frequency * t) + b * cos(frequency * t))
        } else {
            val root = sqrt(z * z - 1.0)
            val r1 = -w * (z - root)
            val r2 = -w * (z + root)
            val a = (v - r2 * x) / (r1 - r2)
            val b = x - a
            next = a * exp(r1 * t) + b * exp(r2 * t)
            speed = a * r1 * exp(r1 * t) + b * r2 * exp(r2 * t)
        }
        return (next + force.finalPosition).toFloat() to speed.toFloat()
    }
}
