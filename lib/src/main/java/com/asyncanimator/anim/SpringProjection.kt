package com.asyncanimator.anim

import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Non-mutating solution of x'' + 2*zeta*omega*x' + omega^2*x = 0 for preview only.
 * Playback itself uses AndroidX SpringAnimation, including its public scheduler and equilibrium API.
 */
internal object SpringProjection {
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
