package com.asyncanimator.demo.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max

/**
 * BallTrackView — N 条球道可视化。
 *
 * <p>每条道：左侧标签 + 轨道 + 一个球（带发光）。用于"多个对象被同一动画驱动"的直观对比。
 *
 * <pre>
 * val track = BallTrackView(ctx)
 * track.setLaneCount(3)
 * track.setLaneLabel(0, "线性", DemoStyle.MAIN_THREAD)
 * track.setProgress(0, 0.5f)          // 动画每帧调用
 * track.setLaneNote(1, "✓", green)    // 终点标记
 * track.markCheckpoint(0, 0.4f)       // 断点竖虚线
 * </pre>
 */
class BallTrackView(context: Context) : View(context) {

    private class Lane {
        var label: String = ""
        var color: Int = DemoStyle.PRIMARY
        var progress: Float = 0f          // 0..1
        var note: String? = null
        var noteColor: Int = DemoStyle.GRAY
        var active: Boolean = true        // false 时球变灰
        var checkpoint: Float? = null     // 断点位置 0..1
        var glowPulse: Boolean = false    // 主时钟脉冲灯用：球呼吸发光
        var pulsePhase: Long = 0
    }

    private val lanes = ArrayList<Lane>()
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f); color = DemoStyle.INK
    }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f); isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DemoStyle.BG_TRACK; style = Paint.Style.FILL
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC9CEDD.toInt(); strokeWidth = 1f
    }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DemoStyle.WARN; strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private val laneHeight = dp(44f)
    private val labelWidth = dp(86f)
    private val ballR = dp(11f).toFloat()
    private val rect = RectF()

    fun setLaneCount(n: Int) {
        lanes.clear()
        repeat(n) { lanes.add(Lane()) }
        requestLayout(); invalidate()
    }

    fun setLaneLabel(i: Int, text: String, color: Int) {
        lanes.getOrNull(i)?.let { it.label = text; it.color = color; invalidate() }
    }

    /** 动画每帧调用。 */
    fun setProgress(i: Int, p: Float) {
        lanes.getOrNull(i)?.let { it.progress = p.coerceIn(0f, 1f); invalidate() }
    }

    fun setLaneNote(i: Int, note: String?, color: Int = DemoStyle.GRAY) {
        lanes.getOrNull(i)?.let { it.note = note; it.noteColor = color; invalidate() }
    }

    fun setLaneActive(i: Int, active: Boolean) {
        lanes.getOrNull(i)?.let { it.active = active; invalidate() }
    }

    fun markCheckpoint(i: Int, p: Float?) {
        lanes.getOrNull(i)?.let { it.checkpoint = p; invalidate() }
    }

    /** 球呼吸发光（主时钟脉冲等）。 */
    fun setGlowPulse(i: Int, on: Boolean) {
        lanes.getOrNull(i)?.let {
            it.glowPulse = on
            it.pulsePhase = System.nanoTime()
            if (on) postInvalidateOnAnimation() else invalidate()
        }
    }

    fun resetProgress() {
        lanes.forEach { it.progress = 0f; it.note = null; it.checkpoint = null; it.active = true }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = paddingTop + paddingBottom + max(1, lanes.size) * laneHeight
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(h, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val trackLeft = paddingLeft + labelWidth
        val trackRight = width - paddingRight - dp(10f)
        val trackW = (trackRight - trackLeft).toFloat()

        lanes.forEachIndexed { i, lane ->
            val cy = paddingTop + i * laneHeight + laneHeight / 2f

            // 标签
            labelPaint.color = if (lane.active) DemoStyle.INK else DemoStyle.GRAY
            canvas.drawText(lane.label, paddingLeft.toFloat(), cy + sp(4f), labelPaint)

            // 轨道（圆角长条）
            val th = dp(8f).toFloat()
            rect.set(trackLeft.toFloat(), cy - th / 2, trackRight.toFloat(), cy + th / 2)
            canvas.drawRoundRect(rect, th / 2, th / 2, trackPaint)

            // 刻度（0/50/100%）
            for (t in 0..2) {
                val x = trackLeft + trackW * t / 2f
                canvas.drawLine(x, cy - th, x, cy + th, tickPaint)
            }

            // 断点竖虚线
            lane.checkpoint?.let { cp ->
                val x = trackLeft + trackW * cp
                canvas.drawLine(x, cy - laneHeight / 2f + 4, x, cy + laneHeight / 2f - 4, dashPaint)
            }

            // 球（带发光）
            val bx = trackLeft + trackW * lane.progress
            val ballColor = if (lane.active) lane.color else DemoStyle.GRAY
            if (lane.glowPulse && lane.active) {
                val t = (System.nanoTime() - lane.pulsePhase) / 1e9f
                val pulse = 0.5f + 0.5f * kotlin.math.sin(t * 6f)
                glowPaint.color = lane.color
                glowPaint.alpha = (60 + 80 * pulse).toInt()
                canvas.drawCircle(bx, cy, ballR + dp(6f) * pulse, glowPaint)
                postInvalidateOnAnimation()
            } else {
                glowPaint.color = ballColor
                glowPaint.alpha = 50
                canvas.drawCircle(bx, cy, ballR + dp(3f), glowPaint)
            }
            ballPaint.color = ballColor
            canvas.drawCircle(bx, cy, ballR, ballPaint)
            // 球心高光
            ballPaint.color = Color.WHITE
            ballPaint.alpha = 90
            canvas.drawCircle(bx - ballR * 0.3f, cy - ballR * 0.3f, ballR * 0.35f, ballPaint)
            ballPaint.alpha = 255

            // note（终点/状态标记）
            lane.note?.let { n ->
                notePaint.color = lane.noteColor
                canvas.drawText(n, trackRight + dp(6f) + sp(8f), cy + sp(4f), notePaint)
            }
        }
    }

    private fun dp(d: Float): Int = DemoStyle.dp(this, d)
    private fun sp(s: Float): Float =
        android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, s, resources.displayMetrics)
}
