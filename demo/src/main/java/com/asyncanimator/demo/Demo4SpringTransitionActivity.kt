package com.asyncanimator.demo

import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.asyncanimator.dyn.anim.SpringForce
import com.asyncanimator.launcher.spring.OplusSpringObjectAnimator
import com.asyncanimator.util.FloatProperty

/**
 * Demo 4 — Spring 渐进切换（同一对象内 swap 驱动源）。
 *
 * <p>对应分析文档 §6.4。展示 OplusSpringObjectAnimator 的 SpringProperty 装饰器：
 * 平时 ObjectAnimator 推进；调用 startSpring() 后 spring.animateToFinalPosition() 接管。
 */
class Demo4SpringTransitionActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 4: SpringProperty 渐进切换"
    override val docSection = "§6.4"

    class Target { var value: Float = 0f }

    val PROP = object : FloatProperty<Target>("x") {
        override fun setValue(t: Target, v: Float) { t.value = v }
        override fun getValue(t: Target): Float? = t.value
    }

    private var target = Target()
    private var anim: OplusSpringObjectAnimator<Target>? = null

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(Button(this).apply {
            text = "1. 创建 OplusSpringObjectAnimator(0→100)"
            setOnClickListener {
                anim = OplusSpringObjectAnimator(
                    target, PROP,
                    1f,                        // minChange
                    0.5f,                      // dampingRatio
                    SpringForce.STIFFNESS_MEDIUM,
                    0f, 100f                   // values
                ).also { it.duration = 1000 }
                log("OplusSpringObjectAnimator created")
                log("内部：mObjectAnimator (ValueAnimator) + mSpring (SpringAnimation)")
                log("SpringProperty.useSpring = false （默认走 ObjectAnimator 直接赋值）")
            }
        })

        root.addView(Button(this).apply {
            text = "2. 启动 ObjectAnimator 路径"
            setOnClickListener {
                anim?.start()
                log("start() → mObjectAnimator 启动")
                log("SpringProperty.setValue → target.value = f")
            }
        })

        root.addView(Button(this).apply {
            text = "3. switchToSpring (startSpring)"
            setOnClickListener {
                // Java 方法不支持 Kotlin 命名参数，按位置传参；endListener 可为 null
                anim?.startSpring(0f, 200f, null)
                log("startSpring() → mProperty.switchToSpring() = true")
                log("后续 setValue → spring.animateToFinalPosition(v)")
                log("两套 AnimationHandler 并存：core 跑 mObjectAnimator，dyn 跑 mSpring")
            }
        })

        return root
    }
}