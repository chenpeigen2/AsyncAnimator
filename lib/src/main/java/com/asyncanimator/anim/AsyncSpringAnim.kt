package com.asyncanimator.anim

import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import com.asyncanimator.api.PublicApi
import com.asyncanimator.thread.AsyncAnimWrapper

/**
 * SpringAnimation 的生命周期线程转发包装，不修改其物理求解或属性写入策略。
 * @param real 宿主配置并管理的实际弹簧对象，需自行设置参数和可用的帧调度器。
 * @param supportAnimThread 为 true 时把生命周期命令转发到动画线程；仅设置此标志不能使任意 View 弹簧后台安全。
 */
@PublicApi
class AsyncSpringAnim @PublicApi constructor(
    private val real: SpringAnimation,
    private val supportAnimThread: Boolean
) : AsyncAnimWrapper() {

    /**
     * 按配置的线程策略调用底层 SpringAnimation.start，不自行创建弹簧参数或帧源。
     * 异步模式要求底层引擎已支持动画线程调度；否则线程检查等异常仍由底层操作抛出。
     */
    @PublicApi
    fun start() = dispatch { real.start() }

    /**
     * 把取消命令发送到配置线程，命令到达底层所属线程时立即停止并保留当前值。
     * 异步模式下调用返回不等于已经停止；取消结束通知是否回主线程由 addEndListener 的包装决定。
     */
    @PublicApi
    fun cancel() = dispatch { real.cancel() }

    /**
     * 请求底层弹簧在后续帧到达最终位置，不等同于立即完成或取消。
     * 保留原生首帧预热语义；零阻尼弹簧不支持该操作，会在执行线程抛出 UnsupportedOperationException。
     */
    @PublicApi
    fun skipToEnd() = dispatch { real.skipToEnd() }

    /**
     * 把目标位置交给底层弹簧；空闲时可启动动画，运行时遵循原生重定向行为。
     * @param position 新的最终属性值，单位与底层弹簧属性一致，边界与合法性由底层检查。
     */
    @PublicApi
    fun animateToFinalPosition(position: Float) = dispatch { real.animateToFinalPosition(position) }

    /**
     * 按线程策略设置底层弹簧的起始速度，不主动启动或重启动画。
     * @param velocity 每秒属性变化量，单位与底层属性一致；本包装不换算或钳制数值。
     */
    @PublicApi
    fun setStartVelocity(velocity: Float) = dispatch { real.setStartVelocity(velocity) }

    /**
     * 直接向底层注册一个转发监听器，原样传递动画实例、取消标志、值和速度。
     * 异步模式只把结束通知送回主线程，同步模式在原派发线程直接通知；注册动作本身不做线程切换。
     * 每次调用创建独立包装，本类未提供移除句柄，宿主应在底层生命周期结束时管理监听清理。
     */
    @PublicApi
    fun addEndListener(listener: DynamicAnimation.OnAnimationEndListener) {
        real.addEndListener { anim, canceled, value, velocity ->
            val notify = { listener.onAnimationEnd(anim, canceled, value, velocity) }
            if (supportAnimThread) runOnMainThread { notify() } else notify()
        }
    }

    /**
     * 按 supportAnimThread 将动作交给共享动画线程，或直接在当前调用线程执行。
     * 同步模式不是强制主线程模式；本方法不捕获业务异常，也不替底层引擎补充线程安全能力。
     */
    private inline fun dispatch(crossinline action: () -> Unit) {
        if (supportAnimThread) {
            runOnAnimThread { action() }
        } else {
            action()
        }
    }
}
