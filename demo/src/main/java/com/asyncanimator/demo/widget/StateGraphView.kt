package com.asyncanimator.demo.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * StateGraphView — 状态机节点图（Demo 6）。
 *
 * <p>节点横向排布（可换行），当前状态发光高亮；
 * `transition(from, to)` 沿两节点连线做小球飞行动画。
 *
 * <pre>
 * graph.setStates(listOf("NONE","OPEN","CLOSE","REVERSE_OPEN","WAITING","UNKNOWN"))
 * graph.setCurrent("NONE")
 * graph.transition("NONE", "OPEN")
 * </pre>
 */
class StateGraphView(context: Context) : View(context) {

    private var states = listOf<String>()
    private var current: String? = null
    private var flying: Flying? = null

    private class Flying(val from: Int, val to: Int, val startT: Long)

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f); isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC9CEDD.toInt(); strokeWidth = 3f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DemoStyle.PRIMARY }
    private val rect = RectF()

    fun setStates(s: List<String>) {
        states = s
        requestLayout(); invalidate()
    }

    fun setCurrent(name: String?) {
        current = name
        invalidate()
    }

    fun transition(from: String, to: String) {
        val fi = states.indexOf(from); val ti = states.indexOf(to)
        if (fi >= 0 && ti >= 0) {
            flying = Flying(fi, ti, System.nanoTime() / 1_000_000)
            postInvalidateOnAnimation()
        }
        current = to
        invalidate()
    }

    private fun nodeCenter(i: Int, cols: Int, cellW: Float, cellH: Float): Pair<Float, Float> {
        val row = i / cols; val col = i % cols
        val x = paddingLeft + cellW * col + cellW / 2f
        val y = paddingTop + cellH * row + cellH / 2f
        return x to y
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cols = cols()
        val rows = if (states.isEmpty()) 1 else (states.size + cols - 1) / cols
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(paddingTop + paddingBottom + rows * dp(56f), heightMeasureSpec)
        )
    }

    private fun cols(): Int {
        val w = if (width > 0) width else resources.displayMetrics.widthPixels
        return maxOf(2, (w - paddingLeft - paddingRight) / dp(120f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (states.isEmpty()) return
        val cols = cols()
        val cellW = (width - paddingLeft - paddingRight) / cols.toFloat()
        val cellH = dp(56f).toFloat()
        val nw = cellW * 0.82f; val nh = dp(34f).toFloat()

        // 连线（相邻节点）
        for (i in 0 until states.size - 1) {
            if ((i + 1) % cols == 0) continue
            val (x1, y1) = nodeCenter(i, cols, cellW, cellH)
            val (x2, y2) = nodeCenter(i + 1, cols, cellW, cellH)
            canvas.drawLine(x1 + nw / 2, y1, x2 - nw / 2, y2, linePaint)
        }

        // 节点
        states.forEachIndexed { i, name ->
            val (cx, cy) = nodeCenter(i, cols, cellW, cellH)
            val isCur = name == current
            if (isCur) {
                glowPaint.color = DemoStyle.PRIMARY; glowPaint.alpha = 60
                rect.set(cx - nw / 2 - dp(5f), cy - nh / 2 - dp(5f),
                    cx + nw / 2 + dp(5f), cy + nh / 2 + dp(5f))
                canvas.drawRoundRect(rect, nh, nh, glowPaint)
            }
            rect.set(cx - nw / 2, cy - nh / 2, cx + nw / 2, cy + nh / 2)
            nodePaint.color = if (isCur) DemoStyle.PRIMARY else DemoStyle.BG_CARD
            canvas.drawRoundRect(rect, nh / 2, nh / 2, nodePaint)
            nodeStrokePaint.color = if (isCur) DemoStyle.PRIMARY_DARK else 0xFFC9CEDD.toInt()
            canvas.drawRoundRect(rect, nh / 2, nh / 2, nodeStrokePaint)
            textPaint.color = if (isCur) 0xFFFFFFFF.toInt() else DemoStyle.INK
            canvas.drawText(name, cx, cy + sp(3.5f), textPaint)
        }

        // 飞行小球（0.6s）
        flying?.let { f ->
            val t = (System.nanoTime() / 1_000_000 - f.startT) / 600f
            if (t >= 1f) { flying = null; return }
            val (x1, y1) = nodeCenter(f.from, cols, cellW, cellH)
            val (x2, y2) = nodeCenter(f.to, cols, cellW, cellH)
            canvas.drawCircle(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, dp(7f).toFloat(), flyPaint)
            postInvalidateOnAnimation()
        }
    }

    private fun dp(d: Float): Int = DemoStyle.dp(this, d)
    private fun sp(s: Float): Float =
        android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, s, resources.displayMetrics)
}
