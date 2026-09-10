package com.asyncanimator.anim

import android.animation.TimeInterpolator

/**
 * 记录最近一次输入进度的插值器包装，用于后续从实际输入位置创建续行动画。
 * @param realInterpolator 实际负责曲线计算的插值器；本包装不复制或同步其内部状态。
 */
internal class RecordInputInterpolator(private val realInterpolator: TimeInterpolator) : TimeInterpolator {

    /**
     * 最近一次传入 getInterpolation 的原始进度，首次调用前为零。
     * 只由插值入口写入，不保证跨线程并发读取或修改的同步性。
     */
    var inputed = 0f
        private set

    /**
     * 先记录原始 input，再交给被包装插值器求值并返回其结果，不对输入做钳制。
     * 记录的是插值前进度而非输出值；即使委托抛出异常也保留本次输入，异常继续向外传播。
     */
    override fun getInterpolation(input: Float): Float {
        this.inputed = input
        return realInterpolator.getInterpolation(input)
    }
}
