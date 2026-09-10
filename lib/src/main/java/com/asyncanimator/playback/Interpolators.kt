package com.asyncanimator.playback

import android.animation.TimeInterpolator
import kotlin.math.abs

/** Portable interpolation helpers from OPPO launcher3/anim/Interpolators.java. */
internal object Interpolators {
    val LINEAR = TimeInterpolator { it }
    val SCROLL = TimeInterpolator { input ->
        val remaining = abs(input - 1f)
        1f - remaining * remaining * remaining * remaining
    }
    val SCROLL_CUBIC = TimeInterpolator { input ->
        val offset = input - 1f
        offset * offset * offset + 1f
    }

    /** Velocity is in pixels/ms; exactly +/-10 still uses the cubic curve. */
    fun scrollInterpolatorForVelocity(velocity: Float): TimeInterpolator =
        if (abs(velocity) > 10f) SCROLL else SCROLL_CUBIC

    /** Run the curve within [lowerBound, upperBound], holding 0/1 outside it. */
    fun clampToProgress(interpolator: TimeInterpolator, lowerBound: Float,
                        upperBound: Float): TimeInterpolator {
        require(upperBound >= lowerBound) { "upperBound must be >= lowerBound" }
        return TimeInterpolator { input ->
            clampToProgress(interpolator, input, lowerBound, upperBound)
        }
    }

    fun clampToProgress(interpolator: TimeInterpolator, progress: Float,
                        lowerBound: Float, upperBound: Float): Float {
        require(upperBound >= lowerBound) { "upperBound must be >= lowerBound" }
        // Match OPPO's zero-width step, including the special step at zero.
        if (progress == lowerBound && progress == upperBound) {
            return if (progress == 0f) 0f else 1f
        }
        if (progress < lowerBound) return 0f
        if (progress > upperBound) return 1f
        return interpolator.getInterpolation((progress - lowerBound) / (upperBound - lowerBound))
    }

    fun clampToProgress(progress: Float, lowerBound: Float, upperBound: Float): Float =
        clampToProgress(LINEAR, progress, lowerBound, upperBound)

    /** Map curve output to a value range; decreasing ranges and overshoot are supported. */
    fun mapToProgress(interpolator: TimeInterpolator, start: Float, end: Float): TimeInterpolator =
        TimeInterpolator { start + (end - start) * interpolator.getInterpolation(it) }

    fun reverse(interpolator: TimeInterpolator): TimeInterpolator =
        TimeInterpolator { 1f - interpolator.getInterpolation(1f - it) }
}
