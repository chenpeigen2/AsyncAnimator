package com.asyncanimator.demo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.asyncanimator.core.anim.AnimatorSet
import com.asyncanimator.core.anim.ValueAnimator
import com.asyncanimator.launcher.playback.AnimatorPlaybackController
import com.asyncanimator.launcher.playback.Holder
import com.asyncanimator.util.FloatProperty

/**
 * Demo 1 — MasterClock 主时钟驱动。
 *
 * <p>对应分析文档 §6.1。展示 AnimatorPlaybackController 的核心设计：
 * <ul>
 *   <li>一个 LINEAR 0..1 主 ValueAnimator（mAnimationPlayer）作为唯一被 Choreographer 驱动的对象</li>
 *   <li>N 个 Holder 跟随主时钟同步推进</li>
 *   <li>每帧 setPlayFraction(f) → 循环 Holder.setProgress → 子 anim.setCurrentFraction</li>
 * </ul>
 */
class Demo1MasterClockActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 1: MasterClock 主时钟驱动"
    override val docSection = "§6.1"

    private lateinit var controller: AnimatorPlaybackController
    private lateinit var masterView: MasterClockView
    private val targets = mutableListOf<TestTarget>()

    /** 测试目标类。 */
    class TestTarget(var id: Int) {
        var value: Float = 0f
    }

    val TEST_PROPERTY = object : FloatProperty<TestTarget>("value") {
        override fun setValue(t: TestTarget, v: Float) { t.value = v }
        override fun getValue(t: TestTarget): Float? = t.value
    }

    override fun createContentView(): View {
        masterView = MasterClockView(this)
        // 创建 5 个子动画（每个 Holder 推进自己的 TestTarget.value）
        val holders = ArrayList<Holder>()
        val animators = ArrayList<ValueAnimator>()
        for (i in 0 until 5) {
            val target = TestTarget(i).also { targets.add(it) }
            val va = ValueAnimator.ofFloat(0f, 1f).apply { setDuration(1000L + i * 200L) }
            va.addUpdateListener { a ->
                val f = a.animatedFraction
                target.value = f
                masterView.postInvalidate()
            }
            animators.add(va)
            holders.add(Holder(va, 1000L))
        }
        val animSet = AnimatorSet().apply { playTogether(*animators.toTypedArray()) }
        controller = AnimatorPlaybackController(animSet, 1000L, holders)
        log("AnimatorPlaybackController created with 5 holders")
        log("主时钟 mAnimationPlayer = LINEAR 0..1 ValueAnimator")
        log("每个 Holder.globalEndProgress = 子动画duration / 总duration")
        controller.addEndListener { success ->
            log("controller end: success=$success")
        }
        controller.start()
        log("controller started, Choreographer 驱动主时钟")
        return masterView
    }

    /** 自定义 View：显示主时钟进度条 + N 个 Holder 圆点。 */
    class MasterClockView(ctx: Context) : View(ctx) {
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            strokeWidth = 4f
        }
        private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF5722")
        }
        private val holderPaints = arrayOf(
            Color.parseColor("#1976D2"), Color.parseColor("#388E3C"),
            Color.parseColor("#FBC02D"), Color.parseColor("#7B1FA2"),
            Color.parseColor("#E64A19"))

        var progressFraction: Float = 0f
        var holderFractions: Float =List(5) { 0f }
            set(v) { field = v; invalidate() }
        var controller: AnimatorPlaybackController? = null

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val margin = 60f
            val barTop = h / 2 - 20
            val barBottom = h / 2 + 20

            // 主时钟进度条
            canvas.drawLine(margin, h / 2, w - margin, h / 2, barPaint)
            // 主时钟游标
            val cursorX = margin + (w - 2 * margin) * progressFraction
            canvas.drawCircle(cursorX, h / 2, 18f, cursorPaint)

            // Holder 圆点（在主时钟进度条上方/下方）
            val n = holderFractions.size
            for (i in 0 until n) {
                val f = holderFractions[i]
                val x = margin + (w - 2 * margin) * f
                val y = if (i % 2 == 0) barTop - 50 else barBottom + 50
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = holderPaints[i % holderPaints.size] }
                canvas.drawCircle(x, y, 14f, p)
            }

            // 文字
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = 32f
            }
            canvas.drawText("主时钟 f=${"%.2f".format(progressFraction)}",
                margin, 40f, textPaint)
        }
    }

    /** 自定义 View 通过 setProgressFraction 和 holderFractions 实时绘制。 */
    override fun onResume() {
        super.onResume()
        // 每帧刷新：在 doAnimationFrame 时已经被 log 调用，但 View 重绘需要 schedule
        // 简化：用 postInvalidateOnAnimation 每帧刷新
        masterView.postInvalidateOnAnimation()
        // 模拟主时钟回调：每帧读取 targets.value 并更新 view
        masterView.viewTreeObserver.addOnDrawListener {
            val fractions = targets.map { it.value }
            masterView.holderFractions = fractions
            // 进度 = 所有 holder 的最大 progress
            masterView.progressFraction = fractions.maxOrNull() ?: 0f
            null
        }
    }
}