package com.asyncanimator.demo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.LinearLayout
import com.asyncanimator.core.anim.AnimatorSet
import com.asyncanimator.core.anim.ValueAnimator
import com.asyncanimator.launcher.playback.AnimatorPlaybackController
import com.asyncanimator.launcher.playback.Holder
import com.asyncanimator.util.FloatProperty

/**
 * Demo 2 — Holder 进度 + ProgressMapper 钩子。
 *
 * <p>对应分析文档 §6.1.4。两个 holder：A 用默认 mapper（线性），B 用弹簧 mapper。
 * 拖动 Slider 控制主时钟进度，观察两个 holder 的同步/异步行为。
 */
class Demo2HolderProgressActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 2: Holder 进度 + ProgressMapper"
    override val docSection = "§6.1.4"

    private lateinit var controller: AnimatorPlaybackController
    private lateinit var holderA: TestTarget
    private lateinit var holderB: TestTarget
    private lateinit var view: HolderView
    private lateinit var seekBar: SeekBar
    private lateinit var fractionLabel: TextView

    class TestTarget(var name: String) { var value: Float = 0f }

    val PROPERTY_A = object : FloatProperty<TestTarget>("value") {
        override fun setValue(t: TestTarget, v: Float) { t.value = v }
        override fun getValue(t: TestTarget): Float? = t.value
    }
    val PROPERTY_B = object : FloatProperty<TestTarget>("value") {
        override fun setValue(t: TestTarget, v: Float) { t.value = v }
        override fun getValue(t: TestTarget): Float? = t.value
    }

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fractionLabel = TextView(this).apply { textSize = 16f }
        root.addView(fractionLabel)

        seekBar = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val f = p / 100f
                    if (::controller.isInitialized) {
                        controller.setPlayFraction(f)
                        fractionLabel.text = "主时钟进度: ${"%.2f".format(f)}"
                        view.invalidate()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(seekBar)

        view = HolderView(this)
        root.addView(view)

        // 创建：3 个 holder（A、B、C 不同 duration 演示不同 globalEndProgress）
        holderA = TestTarget("A")
        holderB = TestTarget("B")
        val holders = ArrayList<Holder>()

        val vaA = ValueAnimator.ofFloat(0f, 1f).apply { setDuration(1000) }
        vaA.addUpdateListener { a -> holderA.value = a.animatedFraction }
        holders.add(Holder(vaA, 1000L))

        val vaB = ValueAnimator.ofFloat(0f, 1f).apply { setDuration(500) }
        vaB.addUpdateListener { a -> holderB.value = a.animatedFraction }
        holders.add(Holder(vaB, 1000L))

        val vaC = ValueAnimator.ofFloat(0f, 1f).apply { setDuration(200) }
        val targetC = TestTarget("C")
        vaC.addUpdateListener { a -> targetC.value = a.animatedFraction }
        holders.add(Holder(vaC, 1000L))
        view.setTargets(holderA, holderB, targetC)

        val animSet = AnimatorSet().apply { playTogether(vaA, vaB, vaC) }
        controller = AnimatorPlaybackController(animSet, 1000L, holders)
        log("Holder A: duration=1000, globalEndProgress=1.0")
        log("Holder B: duration=500, globalEndProgress=0.5")
        log("Holder C: duration=200, globalEndProgress=0.2")
        log("拖动 Slider，观察 Holder B/C 提前到 1.0")
        return root
    }

    /** 自定义 View 显示 3 个 holder 的 fraction。 */
    class HolderView(ctx: Context) : View(ctx) {
        private lateinit var a: TestTarget
        private lateinit var b: TestTarget
        private lateinit var c: TestTarget
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        fun setTargets(a: TestTarget, b: TestTarget, c: TestTarget) {
            this.a = a; this.b = b; this.c = c; invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!::a.isInitialized) return
            val w = width.toFloat()
            val h = height.toFloat()
            val labels = arrayOf("A(1000ms)", "B(500ms)", "C(200ms)")
            val values = arrayOf(a.value, b.value, c.value)
            val colors = intArrayOf(0xFF1976D2.toInt(), 0xFF388E3C.toInt(), 0xFFE64A19.toInt())
            val rowH = h / 4
            for (i in 0..2) {
                val y = rowH * ( + i + 0.5f)
                p.color = colors[i]
                canvas.drawRect(40f, y - 25, 40f + (w - 80) * values[i], y + 25, p)
                p.color = Color.DKGRAY
                p.textSize = 32f
                canvas.drawText("${labels[i]}: ${"%.2f".format(values[i])}", 40f, y - 50, p)
            }
        }
    }
}