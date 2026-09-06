package com.asyncanimator.demo.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.ArrayDeque

/**
 * CurvePlotView — 位置-时间曲线实时描线。
 *
 * <p>每条 lane 一条彩色曲线，时间窗自动向右滚动。
 * 用于"同一驱动、不同插值曲线"（Demo 2）和"线性→弹簧过冲"（Demo 4）的直观对比。
 *
 * <pre>
 * plot.addLane(DemoStyle.MAIN_THREAD)   // 0
 * plot.addLane(DemoStyle.ACCENT)        // 1
 * plot.sample(0, value01)               // 每帧，value 0..1
 * plot.clear()
 * </pre>
 */
class CurvePlotView(context: Context) : View(context) {

    private class Pt(val t: Long, val v: Float)
    private class Lane(val color: Int) {
        val pts = ArrayDeque<Pt>()
    }

    private val lanes = ArrayList<Lane>()
    private var windowMs = 3000L
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE3E6F0.toInt(); strokeWidth = 1f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(9f); color = DemoStyle.GRAY
    }
    private val path = Path()

    fun addLane(color: Int): Int {
        lanes.add(Lane(color))
        return lanes.size - 1
    }

    /** 每帧采样。value 0..1（0=底部，1=顶部）。 */
    fun sample(lane: Int, value: Float) {
        val l = lanes.getOrNull(lane) ?: return
        val now = System.nanoTime() / 1_000_000
        l.pts.addLast(Pt(now, value.coerceIn(0f, 1f)))
        val cutoff = now - windowMs
        while (l.pts.isNotEmpty() && l.pts.first.t < cutoff) l.pts.removeFirst()
        invalidate()
    }

    fun clear() {
        lanes.forEach { it.pts.clear() }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(dp(110f), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        val h = bottom - top

        // 网格：0 / 0.5 / 1.0 横线
        for (f in listOf(0f, 0.5f, 1f)) {
            val y = bottom - f * h
            canvas.drawLine(left, y, right, y, gridPaint)
            canvas.drawText("${(f * 100).toInt()}%", left + 2, y - 3, textPaint)
        }

        val now = System.nanoTime() / 1_000_000
        lanes.forEach { lane ->
            if (lane.pts.size < 2) return@forEach
            path.reset()
            var first = true
            linePaint.color = lane.color
            lane.pts.forEach { p ->
                val x = right - (now - p.t).toFloat() / windowMs * (right - left)
                val y = bottom - p.v * h
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            }
            canvas.drawPath(path, linePaint)
        }
    }

    private fun dp(d: Float): Int = DemoStyle.dp(this, d)
    private fun sp(s: Float): Float =
        android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, s, resources.displayMetrics)
}
