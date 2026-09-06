package com.asyncanimator.demo.scene

import android.view.Choreographer
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SceneSpring — 解析解弹簧（与 androidx/OPPO SpringForce 同款闭式公式）。
 *
 * <p>按帧步进：每帧用当前 (value, velocity) 与目标值，按 dt 解析推进，
 * 支持随时改目标（带速度接力，续行/反转无跳变）。
 */
class SceneSpring(
    var stiffness: Float = 320f,
    var dampingRatio: Float = 0.86f
) {
    var value: Float = 0f; private set
    var velocity: Float = 0f; private set
    var target: Float = 0f; private set

    private val naturalFreq = sqrt(stiffness.toDouble())
    private val dampedFreq = naturalFreq * sqrt(1 - dampingRatio * dampingRatio)

    fun snapTo(v: Float) {
        value = v; velocity = 0f; target = v
    }

    fun setValue(v: Float, vel: Float = velocity) {
        value = v; velocity = vel
    }

    fun animateTo(t: Float) {
        target = t
    }

    val isAtRest: Boolean
        get() = kotlin.math.abs(value - target) < 0.0015f && kotlin.math.abs(velocity) < 0.01f

    /** 按 dt（秒）解析推进一步。 */
    fun advance(dtSec: Float) {
        if (isAtRest) { value = target; velocity = 0f; return }
        val dt = dtSec.toDouble().coerceAtMost(0.05) // 掉帧保护：单步不超 50ms
        val delta = (value - target).toDouble()
        val v0 = velocity.toDouble()
        val newValue: Double
        val newVelocity: Double
        when {
            dampingRatio > 1.0 -> {
                // 过阻尼
                val xa = naturalFreq * (dampingRatio - sqrt(dampingRatio * dampingRatio - 1))
                val xb = naturalFreq * (dampingRatio + sqrt(dampingRatio * dampingRatio - 1))
                val c2 = (v0 - delta * xa) / (xb - xa)
                val c1 = delta - c2
                val eA = exp(-xa * dt); val eB = exp(-xb * dt)
                newValue = target + c1 * eA + c2 * eB
                newVelocity = -(c1 * xa * eA + c2 * xb * eB)
            }
            dampingRatio == 1.0f -> {
                // 临界阻尼
                val c2 = v0 + naturalFreq * delta
                val e = exp(-naturalFreq * dt)
                newValue = target + (delta + c2 * dt) * e
                newVelocity = (c2 - naturalFreq * (delta + c2 * dt)) * e
            }
            else -> {
                // 欠阻尼（有过冲，最常用）
                val c2 = (v0 + dampingRatio * naturalFreq * delta) / dampedFreq
                val e = exp(-dampingRatio * naturalFreq * dt)
                val cosW = cos(dampedFreq * dt)
                val sinW = sin(dampedFreq * dt)
                newValue = target + e * (delta * cosW + c2 * sinW)
                newVelocity = -dampingRatio * naturalFreq * e * (delta * cosW + c2 * sinW) +
                        e * (-delta * dampedFreq * sinW + c2 * dampedFreq * cosW)
            }
        }
        value = newValue.toFloat()
        velocity = newVelocity.toFloat()
    }
}

/**
 * SceneClock — 舞台帧时钟（Choreographer 帧回调循环）。
 *
 * <p>有活跃任务时自续帧；每帧给回调真实 dt（秒）。
 */
class SceneClock(private val onFrame: (dtSec: Float) -> Unit) : Choreographer.FrameCallback {

    private var running = false
    private var lastNs = 0L

    fun start() {
        if (running) return
        running = true
        lastNs = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    val isRunning get() = running

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastNs == 0L) 0.016f else (frameTimeNanos - lastNs) / 1e9f
        lastNs = frameTimeNanos
        onFrame(dt.coerceIn(0f, 0.05f))
        if (running) Choreographer.getInstance().postFrameCallback(this)
    }
}
