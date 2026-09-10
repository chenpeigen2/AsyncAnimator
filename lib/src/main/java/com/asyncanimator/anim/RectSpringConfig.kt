package com.asyncanimator.anim

/** Independent public-API spring parameters. Device/launcher policies are explicit host inputs. */
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
    // Additional launcher policy, not a second copy of Android's system animator duration scale.
    val durationMultiplier: Float = 1f,
    val lightAnimation: Boolean = false,
    val evaluationScene: Boolean = false,
    val tablet: Boolean = false,
    val foldExpanded: Boolean = false,
    val foldWide: Boolean = false
) {
    enum class Tracking { TOP, CENTER, BOTTOM }
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
        // Validate derived stiffness eagerly, before a driver can register native callbacks.
        parameters(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME)
    }

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

/** Six scalar values, or six velocities in the same coordinates (size may mean width or height). */
data class RectSpringValues(
    val centerX: Float = 0f, val trackedY: Float = 0f, val size: Float = 0f,
    val ratio: Float = 0f, val radius: Float = 0f, val alpha: Float = 0f
) {
    internal fun array() = floatArrayOf(centerX, trackedY, size, ratio, radius, alpha)
    internal companion object {
        fun from(a: FloatArray) = RectSpringValues(a[0], a[1], a[2], a[3], a[4], a[5])
    }
}

/** Immutable, independently readable frame. Each rect access returns a new mutable Android RectF. */
data class RectSpringFrame(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val values: RectSpringValues, val velocities: RectSpringValues,
    val progress: Float, val sizeIsWidth: Boolean
) {
    val rect: android.graphics.RectF get() = android.graphics.RectF(left, top, right, bottom)
}
