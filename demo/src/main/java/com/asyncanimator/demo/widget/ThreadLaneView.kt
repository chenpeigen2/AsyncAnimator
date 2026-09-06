package com.asyncanimator.demo.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.util.ArrayDeque

/**
 * ThreadLaneView — 线程泳道图（跨线程 marshal 可视化）。
 *
 * <p>每条泳道是一个线程；事件在泳道上打点（向右滚动），
 * `marshal()` 画一条从源泳道飞向目标泳道的箭头弧线（1s 动画）。
 *
 * <pre>
 * lanes.setLaneNames(listOf("worker", "main"))
 * lanes.event(0)                 // 泳道 0 上打一个点
 * lanes.marshal(0, 1, "start()") // worker → main 的 marshal 箭头
 * lanes.setLaneBusy(1, true)     // 泳道高亮（正在跑帧）
 * </pre>
 */
class ThreadLaneView(context: Context) : View(context) {

    private class Ev(val t: Long)
    private class Arrow(val from: Int, val to: Int, val label: String, val startT: Long)

    private var names = listOf("main", "launcher.anim")
    private var colors = listOf(DemoStyle.MAIN_THREAD, DemoStyle.ANIM_THREAD)
    private val events = ArrayList<ArrayDeque<Ev>>()
    private val busy = ArrayList<Boolean>()
    private val arrows = ArrayList<Arrow>()
    private var windowMs = 4000L

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f); isFakeBoldText = true
    }
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND
    }
    private val arrowTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(9f); color = DemoStyle.INK
    }
    private val rect = RectF()

    init { setLaneNames(names) }

    fun setLaneNames(n: List<String>, c: List<Int>? = null) {
        names = n
        if (c != null && c.size == n.size) colors = c
        events.clear(); busy.clear()
        repeat(n.size) { events.add(ArrayDeque()); busy.add(false) }
        requestLayout(); invalidate()
    }

    /** 在泳道上打一个事件点。 */
    fun event(lane: Int) {
        events.getOrNull(lane)?.addLast(Ev(System.nanoTime() / 1_000_000))
        trim()
        invalidate()
    }

    /** 画 from→to 的 marshal 箭头（1s 飞行动画）。 */
    fun marshal(from: Int, to: Int, label: String) {
        arrows.add(Arrow(from, to, label, System.nanoTime() / 1_000_000))
        event(from)
        postInvalidateOnAnimation()
    }

    fun setLaneBusy(lane: Int, b: Boolean) {
        if (lane in busy.indices) { busy[lane] = b; invalidate() }
    }

    fun reset() {
        events.forEach { it.clear() }; arrows.clear(); invalidate()
    }

    private fun trim() {
        val cutoff = System.nanoTime() / 1_000_000 - windowMs
        events.forEach { q -> while (q.isNotEmpty() && q.first.t < cutoff) q.removeFirst() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = paddingTop + paddingBottom + names.size * dp(40f)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        trim()
        val now = System.nanoTime() / 1_000_000
        val labelW = dp(96f)
        val left = paddingLeft + labelW
        val right = (width - paddingRight - dp(8f)).toFloat()
        val laneH = dp(40f)

        for (i in names.indices) {
            val cy = paddingTop + i * laneH + laneH / 2f

            // 泳道背景（busy 时高亮）
            rect.set(paddingLeft.toFloat(), cy - laneH / 2f + 3,
                width - paddingRight.toFloat(), cy + laneH / 2f - 3)
            lanePaint.color = if (busy.getOrElse(i) { false })
                (colors[i] and 0x00FFFFFF) or 0x22000000 else 0xFFF0F1F7.toInt()
            canvas.drawRoundRect(rect, dp(10f).toFloat(), dp(10f).toFloat(), lanePaint)

            // 泳道名
            labelPaint.color = colors[i]
            canvas.drawText(names[i], paddingLeft + dp(10f).toFloat(), cy + sp(4f), labelPaint)

            // 事件点（新事件在右，向左滚动淡出）
            events[i].forEach { ev ->
                val age = now - ev.t
                val x = right - age.toFloat() / windowMs * (right - left)
                val alpha = (255 * (1f - age.toFloat() / windowMs)).toInt().coerceIn(30, 255)
                dotPaint.color = colors[i]; dotPaint.alpha = alpha
                canvas.drawCircle(x, cy, dp(5f).toFloat(), dotPaint)
            }
        }

        // marshal 箭头（1s 动画：弧线上的飞点 + 标签）
        val iter = arrows.iterator()
        var needMore = false
        while (iter.hasNext()) {
            val a = iter.next()
            val t = (now - a.startT) / 1000f
            if (t >= 1f) { iter.remove(); event(a.to); continue }
            needMore = true
            val y1 = paddingTop + a.from * laneH + laneH / 2f
            val y2 = paddingTop + a.to * laneH + laneH / 2f
            // 弧线：从 (right, y1) 抛物线到 (right*0.7, y2)
            val sx = right
            val sy = y1
            val ex = right * 0.75f + left * 0.25f
            val ey = y2
            val mx = (sx + ex) / 2f + dp(30f) * (if (a.to > a.from) 1 else -1)
            // 二次贝塞尔求点
            val u = 1 - t
            val px = u * u * sx + 2 * u * t * mx + t * t * ex
            val py = u * u * sy + 2 * u * t * ((sy + ey) / 2f) + t * t * ey
            arrowPaint.color = DemoStyle.PRIMARY
            arrowPaint.alpha = 160
            canvas.drawLine(sx, sy, px, py, arrowPaint)
            dotPaint.color = DemoStyle.PRIMARY; dotPaint.alpha = 255
            canvas.drawCircle(px, py, dp(6f).toFloat(), dotPaint)
            canvas.drawText(a.label, px + dp(10f), py - dp(6f), arrowTextPaint)
        }
        if (needMore) postInvalidateOnAnimation()
    }

    private fun dp(d: Float): Int = DemoStyle.dp(this, d)
    private fun sp(s: Float): Float =
        android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, s, resources.displayMetrics)
}
