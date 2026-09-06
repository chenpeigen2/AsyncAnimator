package com.asyncanimator.demo.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import java.util.ArrayDeque

/**
 * FrameGapHistogramView — 实时帧间隔柱状图（掉帧可视化）。
 *
 * <p>每个 channel 一列柱：绿 ≤20ms，黄 20~50ms，红 >50ms，从右往左滚动。
 * Demo 10 核心卖点：主线程加压时蓝柱飙红、绿柱保持平稳。
 *
 * <pre>
 * hist.addChannel(DemoStyle.MAIN_THREAD)   // 0
 * hist.addChannel(DemoStyle.ANIM_THREAD)   // 1
 * hist.sample(0, gapMs)                    // 每帧
 * hist.clear()
 * </pre>
 */
class FrameGapHistogramView(context: Context) : View(context) {

    private class Channel(val color: Int) {
        val gaps = ArrayDeque<Float>()  // 最近的帧间隔 ms
    }

    private val channels = ArrayList<Channel>()
    private var maxSamples = 60
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC9CEDD.toInt(); strokeWidth = 1f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(9f); color = DemoStyle.GRAY
    }
    private val capMs = 100f  // 纵轴满量程 = 100ms

    fun addChannel(color: Int): Int {
        channels.add(Channel(color))
        invalidate()
        return channels.size - 1
    }

    fun sample(channel: Int, gapMs: Float) {
        val ch = channels.getOrNull(channel) ?: return
        ch.gaps.addLast(gapMs)
        while (ch.gaps.size > maxSamples) ch.gaps.removeFirst()
        invalidate()
    }

    fun clear() {
        channels.forEach { it.gaps.clear() }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(dp(90f), heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        maxSamples = maxOf(20, w / dp(6f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        val h = bottom - top

        // 基准线：16.7ms(60fps) 与 50ms
        for (ms in listOf(16.7f, 50f)) {
            val y = bottom - (ms / capMs).coerceAtMost(1f) * h
            canvas.drawLine(left, y, right, y, axisPaint)
            canvas.drawText("${ms.toInt()}ms", left + 2, y - 3, textPaint)
        }

        if (channels.isEmpty()) return
        val laneH = h / channels.size
        val slotW = (right - left) / maxSamples
        val barW = slotW * 0.7f

        channels.forEachIndexed { ci, ch ->
            val laneBottom = bottom - ci * laneH
            val laneTop = laneBottom - laneH
            canvas.drawLine(left, laneTop, right, laneTop, axisPaint)

            var x = right - slotW
            val iter = ch.gaps.descendingIterator()
            while (iter.hasNext()) {
                val gap = iter.next()
                val frac = (gap / capMs).coerceAtMost(1f)
                val bh = frac * (laneH - dp(6f))
                barPaint.color = when {
                    gap <= 20f -> DemoStyle.ANIM_THREAD
                    gap <= 50f -> DemoStyle.AMBER
                    else -> DemoStyle.WARN
                }
                canvas.drawRect(x, laneBottom - bh, x + barW, laneBottom, barPaint)
                x -= slotW
                if (x < left) break
            }
        }
    }

    private fun dp(d: Float): Int = DemoStyle.dp(this, d)
    private fun sp(s: Float): Float =
        android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, s, resources.displayMetrics)
}
