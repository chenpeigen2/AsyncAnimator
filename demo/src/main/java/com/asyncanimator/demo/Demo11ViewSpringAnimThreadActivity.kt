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
 * Demo 11 — AndroidX View 弹簧与 AsyncSpringAnim 生命周期包装。
 * 原厂有自定义后台弹簧引擎；本页没有为 AndroidX View 动画安装后台 scheduler（Demo12 的数值引擎另行配置）。
 * 两路均在主线程安全写 View；包装模式显式 supportAnimThread=false。
 * 这不是后台 View 渲染或独立线程物理能力演示。
 */
class Demo11ViewSpringAnimThreadActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 11: View 弹簧（主线程安全回退）"
    override val docSection = "AsyncAnimWrapper / AsyncSpringAnim / androidx SpringAnimation"

    private lateinit var card: View
    private lateinit var stats: TextView
    private lateinit var hist: FrameGapHistogramView

    private var realSpring: SpringAnimation? = null
    private var asyncSpring: AsyncSpringAnim? = null
    private var useWrapper = true
    private var destroyed = false
    private var generation = 0L
    private var updateListener: DynamicAnimation.OnAnimationUpdateListener? = null
    private var endListener: DynamicAnimation.OnAnimationEndListener? = null
    private lateinit var modeButton: android.widget.Button

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
            addChannel(DemoStyle.MAIN_THREAD)
        }
        root.addView(hist, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        DemoStyle.addButtonRow(root,
            DemoStyle.primaryButton("启动：弹到 -260（translationY）", this) { startSpring(-260f) },
            DemoStyle.outlineButton("启动：回弹到 0", this) { startSpring(0f) })
        DemoStyle.addButtonRow(root,
            DemoStyle.outlineButton("当前：包装模式（主线程）", this, DemoStyle.GRAY) {
                toggleThread()
            }.also { modeButton = it },
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

    private fun startSpring(finalPosition: Float) {
        if (destroyed) return
        stop()
        val run = generation
        statsData.reset()
        hist.clear()
        card.translationY = if (finalPosition == 0f) -260f else 0f
        val s = SpringAnimation(card, SpringAnimation.TRANSLATION_Y).apply {
            spring = SpringForce(finalPosition).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                stiffness = SpringForce.STIFFNESS_MEDIUM
            }
        }
        realSpring = s
        updateListener = DynamicAnimation.OnAnimationUpdateListener { _, _, _ ->
            if (!destroyed && run == generation) {
                val gap = statsData.sample(System.currentTimeMillis())
                if (gap > 0) hist.sample(0, gap.toFloat())
                refreshStats()
            }
        }.also { s.addUpdateListener(it) }
        endListener = DynamicAnimation.OnAnimationEndListener { _, canceled, _, _ ->
            if (!destroyed && run == generation) {
                log("[弹簧结束] canceled=$canceled  线程=${Thread.currentThread().name}")
            }
        }.also { s.addEndListener(it) }
        if (useWrapper) {
            // No background AndroidX scheduler/View engine is installed. Do not fake support.
            asyncSpring = AsyncSpringAnim(s, supportAnimThread = false)
            asyncSpring?.start()
            log("包装模式：AsyncSpringAnim(supportAnimThread=false)，View 弹簧安全回退主线程")
        } else {
            s.start()
            log("直接模式：SpringAnimation.start() 在主线程启动")
        }
    }

    private fun toggleThread() {
        stop()
        useWrapper = !useWrapper
        modeButton.text = if (useWrapper) "当前：包装模式（主线程）" else "当前：直接模式（主线程）"
        log("两种模式均使用主线程；未安装后台 AndroidX scheduler，不演示后台 View 写入")
        // 重建 hist 通道颜色
        hist.clear()
    }

    private fun stressMainThread() {
        log(">>> 主线程 sleep(800)：观察弹簧是否继续收敛…")
        statsData.maxGapMs.set(0)
        Thread.sleep(800)
        log("<<< 主线程恢复：两种模式均受主线程阻塞影响，以直方图实测为准")
    }

    private fun skipToEnd() {
        if (useWrapper) asyncSpring?.skipToEnd() else realSpring?.skipToEnd()
        log("skipToEnd() → 主线程")
    }

    private fun stop() {
        generation++
        realSpring?.let { spring ->
            updateListener?.let { spring.removeUpdateListener(it) }
            endListener?.let { spring.removeEndListener(it) }
            // Cancel through the same wrapper/owner used to start the animation.
            asyncSpring?.cancel() ?: spring.cancel()
        }
        updateListener = null
        endListener = null
        realSpring = null
        asyncSpring = null
    }

    override fun onCleanup() {
        destroyed = true
        stop()
        super.onCleanup()
    }

    private fun refreshStats() {
        stats.text = "驱动线程=${shorten(statsData.thread)}　帧数=${statsData.count.get()}  " +
                "平均间隔=${statsData.avg()}ms　最大间隔=${statsData.maxGapMs.get()}ms"
    }

    private fun shorten(name: String): String = if (name.length > 30) name.substring(0, 30) + "…" else name
}
