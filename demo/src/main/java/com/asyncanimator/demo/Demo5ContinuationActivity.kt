package com.asyncanimator.demo

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.asyncanimator.core.anim.Animator
import com.asyncanimator.launcher.continuation.OplusValueAnimator
import com.asyncanimator.launcher.continuation.RecordInputInterpolator
import com.asyncanimator.util.FloatProperty

/**
 * Demo 5 — 续行动画（两对象接力）。
 *
 * <p>对应分析文档 §6.5。generateContinuationAnim 用 RecordInputInterpolator 记录的
 * 当前 fraction 作起点，新对象 timeController 从该 fraction 跑到 1.0。
 */
class Demo5ContinuationActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 5: OplusValueAnimator 续行动画"
    override val docSection = "§6.5"

    class Target { var value: Float = 0f }

    val PROP = object : FloatProperty<Target>("x") {
        override fun setValue(t: Target, v: Float) { t.value = v }
        override fun getValue(t: Target): Float? = t.value
    }

    private var target = Target()
    private var currentAnim: OplusValueAnimator<Target>? = null

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(Button(this).apply {
            text = "1. 创建 + 模拟手势拖动到 0.6"
            setOnClickListener {
                val rec = RecordInputInterpolator { it }
                val param = OplusValueAnimator.AnimParam()
                    .setName("drag")
                    .setRange(0f, 1f)
                    .setInterpolator(rec)
                    .setDuration(1000)
                    .setApplicator { v -> target.value = v as Float }
                val anim = OplusValueAnimator<Target>("drag", param, null)
                anim.setCurrentFraction(0.6f)
                // setCurrentFraction → animateValue → rec.getInterpolation(0.6f)
                // → RecordInputInterpolator 自动记录 inputed（无需手动赋值）
                currentAnim = anim
                log("手势拖动：target.value 累积到 0.6")
                log("RecordInputInterpolator.inputed = 0.6")
                log("anim.getParam().currentFraction = 0.6")
            }
        })

        root.addView(Button(this).apply {
            text = "2. 松手 → generateContinuationAnim"
            setOnClickListener {
                val src = currentAnim ?: return@setOnClickListener
                val newAnim = OplusValueAnimator.generateContinuationAnim(src, 500)
                if (newAnim == null) {
                    log("generateContinuationAnim 失败（fraction 非法）")
                } else {
                    log("新对象 OplusValueAnimator 创建")
                    log("timeController: 0.6 → 1.0, duration=500ms")
                    log("CURRENT_FRACTION FloatProperty 反向 setCurrentFraction")
                    log("AOSP onAnimationUpdate → valueApplicator.applyValue")
                    newAnim.addListener(object : com.asyncanimator.core.anim.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animator: Animator) {
                            log("续行动画结束，target.value = ${target.value}")
                        }
                    })
                    newAnim.start()
                }
            }
        })

        root.addView(Button(this).apply {
            text = "3. 当前 target.value"
            setOnClickListener {
                log("target.value = ${target.value}")
            }
        })

        return root
    }
}