package com.asyncanimator.demo

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.asyncanimator.demo.widget.DemoStyle
import com.asyncanimator.demo.widget.FrameGapHistogramView
import com.asyncanimator.anim.AsyncSpringAnim
import java.util.concurrent.atomic.AtomicLong

/**
 * Demo 11 — View 属性弹簧跑在独立线程。
 *
 * <p>对应 OPPO 链路：
 * - com/android/launcher3/anim/AsyncAnimWrapper.java —— runOnAnimThread / runOnMainThread 骨架
 * - com/android/quickstep/util/OplusAsyncSpringAnimWrapper.java —— viewSupportAnimThread 时
 *   start/cancel/skipToEnd/animateToFinalPosition/setStartVelocity 全部 marshal 到 ANIM_EXECUTOR
 * - com/android/quickstep/util/animation/SpringAnimation.java —— 弹簧物理（原厂 OPPO fork）
 *
 * <p>本 demo 用 androidx.dynamicanimation 的 SpringAnimation（同样是 ThreadLocal AnimationHandler
 * + 调用线程 Choreographer），所以：
 * - 主线程路：`SpringAnimation.start()` 直接在主线程启动 → 帧回调在主线程；
 * - launcher.anim 路：`AsyncSpringAnim(supportAnimThread=true).start()` 把真实 start marshal 到
 *   launcher.anim → 帧回调与 View 属性写都在 launcher.anim 线程。
 *
 * <p>点"主线程加压 800ms"后，主线程路卡住、红柱；launcher.anim 路弹簧继续收敛、绿柱平稳。
 */
class Demo11ViewSpringAnimThreadActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 11: View 属性弹簧跑独立线程"
    override val docSection = "AsyncAnimWrapper / AsyncSpringAnim / androidx SpringAnimation"

    private lateinit var card: View
    private lateinit var stats: TextView
    private lateinit var hist: FrameGapHistogramView

    private var realSpring: SpringAnimation? = null
    private var asyncSpring: AsyncSpringAnim? = null
    private var supportAnimThread = true

    private class FrameStats {
        val count = AtomicLong(0)
        val lastMs = AtomicLong(0)
        val maxGapMs = AtomicLong(0)
        val sumGapMs = AtomicLong(0)
        @Volatile var thread = "-"

        fun sample(nowMs: Long): Long {
            thread = Thread.currentThread().name
            val last = lastMs.getAndSet(nowMs)
            if (last > 0) {
                val gap = nowMs - last
                if (gap > maxGapMs.get()) maxGapMs.set(gap)
                sumGapMs.addAndGet(gap)
                count.incrementAndGet()
                return gap
            }
            return 0
        }

        fun avg(): Long = if (count.get() == 0L) 0 else sumGapMs.get() / count.get()
        fun reset() { count.set(0); lastMs.set(0); maxGapMs.set(0); sumGapMs.set(0) }
    }

    private val statsData = FrameStats()

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ── 被弹簧驱动的卡片（真实 View.translationY 属性）──
        card = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(DemoStyle.PRIMARY, DemoStyle.ACCENT)).apply {
                cornerRadius = DemoStyle.dp(this@Demo11ViewSpringAnimThreadActivity, 18f).toFloat()
            }
        }
        root.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            DemoStyle.dp(this, 140f)).apply {
            topMargin = DemoStyle.dp(this@Demo11ViewSpringAnimThreadActivity, 8f)
        })

        stats = TextView(this).apply {
            textSize = 12f
            setTextColor(DemoStyle.ANIM_THREAD)
            setPadding(0, DemoStyle.dp(this@Demo11ViewSpringAnimThreadActivity, 6f), 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(stats)

        root.addView(sectionLabel("帧间隔直方图：绿 ≤20ms　黄 ≤50ms　红 >50ms（当前驱动线程）"))
        hist = FrameGapHistogramView(this).apply {
            addChannel(if (supportAnimThread) DemoStyle.ANIM_THREAD else DemoStyle.MAIN_THREAD)
        }
        root.addView(hist, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        DemoStyle.addButtonRow(root,
            DemoStyle.primaryButton("启动：弹到 -260（translationY）", this) { startSpring(-260f) },
            DemoStyle.outlineButton("启动：回弹到 0", this) { startSpring(0f) })
        DemoStyle.addButtonRow(root,
            DemoStyle.outlineButton(
                if (supportAnimThread) "当前：launcher.anim（点切主线程）" else "当前：主线程（点切动画线程）",
                this, DemoStyle.GRAY) { toggleThread() },
            DemoStyle.dangerButton("主线程加压 800ms", this) { stressMainThread() })
        DemoStyle.addButtonRow(root,
            DemoStyle.outlineButton("cancel", this, DemoStyle.WARN) { stop() },
            DemoStyle.outlineButton("skipToEnd", this, DemoStyle.GRAY) { skipToEnd() })

        refreshStats()
        return ScrollView(this).apply { addView(root) }
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        textSize = 11f
        setTextColor(DemoStyle.GRAY)
        this.text = text
        setPadding(0, DemoStyle.dp(this@Demo11ViewSpringAnimThreadActivity, 8f), 0,
            DemoStyle.dp(this@Demo11ViewSpringAnimThreadActivity, 2f))
    }

    private fun buildSpring(finalPosition: Float): SpringAnimation =
        SpringAnimation(card, SpringAnimation.TRANSLATION_Y).apply {
            spring = SpringForce(finalPosition).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                stiffness = SpringForce.STIFFNESS_MEDIUM
            }
            addUpdateListener { _: DynamicAnimation<*>?, value: Float, _: Float ->
                val gap = statsData.sample(System.currentTimeMillis())
                runOnUiThread {
                    if (gap > 0) hist.sample(0, gap.toFloat())
                    refreshStats()
                }
            }
        }

    private fun startSpring(finalPosition: Float) {
        stop()
        statsData.reset()
        hist.clear()
        card.translationY = if (finalPosition == 0f) -260f else 0f
        val s = buildSpring(finalPosition)
        realSpring = s
        if (supportAnimThread) {
            asyncSpring = AsyncSpringAnim(s, supportAnimThread = true)
            asyncSpring?.addEndListener { _, canceled, _, _ ->
                log("[弹簧结束] canceled=$canceled  线程=${Thread.currentThread().name}（回主线程）")
            }
            asyncSpring?.start()
            log("launcher.anim 路：AsyncSpringAnim.start() 已 marshal 到独立线程；end 回调回主线程")
        } else {
            s.addEndListener { _, canceled, _, _ ->
                log("[弹簧结束] canceled=$canceled  线程=${Thread.currentThread().name}")
            }
            s.start()
            log("主线程路：SpringAnimation.start() 直接在主线程启动")
        }
    }

    private fun toggleThread() {
        stop()
        supportAnimThread = !supportAnimThread
        log("切换驱动线程 → ${if (supportAnimThread) "launcher.anim" else "主线程"}")
        // 重建 hist 通道颜色
        hist.clear()
    }

    private fun stressMainThread() {
        log(">>> 主线程 sleep(800)：观察弹簧是否继续收敛…")
        statsData.maxGapMs.set(0)
        Thread.sleep(800)
        log("<<< 主线程恢复：主线程路应有一根 ~800ms 红柱；launcher.anim 路保持平稳")
    }

    private fun skipToEnd() {
        if (supportAnimThread) asyncSpring?.skipToEnd() else realSpring?.skipToEnd()
        log("skipToEnd() → ${if (supportAnimThread) "launcher.anim" else "主线程"}")
    }

    private fun stop() {
        realSpring?.cancel()
        realSpring = null
        asyncSpring = null
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun refreshStats() {
        stats.text = "驱动线程=${shorten(statsData.thread)}　帧数=${statsData.count.get()}  " +
                "平均间隔=${statsData.avg()}ms　最大间隔=${statsData.maxGapMs.get()}ms"
    }

    private fun shorten(name: String): String = if (name.length > 30) name.substring(0, 30) + "…" else name
}
