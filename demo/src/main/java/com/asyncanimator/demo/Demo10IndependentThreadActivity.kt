package com.asyncanimator.demo

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.asyncanimator.core.anim.Animator
import com.asyncanimator.core.anim.AnimationHandler
import com.asyncanimator.core.anim.ValueAnimator
import com.asyncanimator.launcher.animthread.AnimExecutors
import com.asyncanimator.launcher.animthread.AnimationControlThread
import com.asyncanimator.launcher.animthread.HandlerTickScheduler
import com.asyncanimator.launcher.async.AsyncValueAnimator
import com.asyncanimator.launcher.pending.NullableAnimatorListenerAdapter
import java.util.concurrent.atomic.AtomicLong

/**
 * Demo 10 — 独立动画线程（对齐 OPPO 手势/转场动画主链）。
 *
 * <p>对应 OPPO 源码（15.8.24 反编译）：
 * - com/oplus/basecommon/thread/OplusExecutors.java:95 —— ANIM_EXECUTOR = "launcher.anim", prio -19
 * - com/oplus/basecommon/thread/OplusExecutors.java:169 —— 线程 init：AnimationHandler.setProvider(
 *   SfVsyncFrameCallbackProvider()) + LauncherBooster.setUxThreadValue(myTid())
 * - com/android/launcher3/anim/AsyncAnimWrapper.java —— runOnAnimThread / runOnMainThread 骨架
 * - com/android/quickstep/util/OplusAsyncSpringAnimWrapper.java —— viewSupportAnimThread 开关模式
 * - com/android/quickstep/util/animation/CustomRectFSpringAnim.java:888 —— mStartAsync ?
 *   ANIM_EXECUTOR : MAIN_EXECUTOR，再判 looper.isCurrentThread()
 * - com/android/quickstep/util/animation/MultiDynamicAnimation.java:127 —— 帧回调注册到
 *   android.animation.AnimationHandler（平台隐藏类，ThreadLocal）
 *
 * <p>演示内容：两个球做同样的往复动画——
 * - 蓝球：动画在主线程计算（传统做法）；
 * - 绿球：AsyncValueAnimator.setExecutor(ANIM_CONTROL_EXECUTOR)，
 *   start 与帧推进都在 "Launcher Animation Control" 线程。
 *
 * <p>点"主线程加压 800ms"后：蓝球的最大帧间隔飙升（动画计算被阻塞），
 * 绿球在动画线程上的计算帧间隔保持 ~16ms（仅 UI 文本刷新短暂滞后）——
 * 这就是独立动画线程的核心收益。
 */
class Demo10IndependentThreadActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 10: 独立动画线程 (launcher.anim)"
    override val docSection = "OplusExecutors.ANIM_EXECUTOR / AsyncAnimWrapper / CustomRectFSpringAnim"

    private lateinit var mainStats: TextView
    private lateinit var asyncStats: TextView
    private lateinit var mainBall: View
    private lateinit var asyncBall: View

    private var mainAnim: ValueAnimator? = null
    private var asyncAnim: AsyncValueAnimator? = null

    // 帧统计（在动画计算线程上采样， marshal 回 UI 更新）
    private class FrameStats {
        val count = AtomicLong(0)
        val lastFrameMs = AtomicLong(0)
        val maxGapMs = AtomicLong(0)
        val sumGapMs = AtomicLong(0)
        @Volatile var displayThread = "-"
        fun sample(frameMs: Long) {
            displayThread = Thread.currentThread().name
            val last = lastFrameMs.getAndSet(frameMs)
            if (last > 0) {
                val gap = frameMs - last
                if (gap > maxGapMs.get()) maxGapMs.set(gap)
                sumGapMs.addAndGet(gap)
                count.incrementAndGet()
            }
        }
        fun avgGapMs(): Long = if (count.get() == 0L) 0 else sumGapMs.get() / count.get()
    }

    private val mainStatsData = FrameStats()
    private val asyncStatsData = FrameStats()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 把主线程的帧驱动也换成主线程 Looper 的 postDelayed（等价真机主线程
        // Choreographer 语义；移植框架默认用共享 JVM tick 线程，会使对照失真）。
        // 影响范围仅 demo 进程主线程的移植版动画。
        AnimationHandler.replaceThreadScheduler(
                HandlerTickScheduler(android.os.Handler(android.os.Looper.getMainLooper())))
    }

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        mainStats = TextView(this).apply {
            textSize = 13f
            setPadding(16, 8, 16, 8)
        }
        asyncStats = TextView(this).apply {
            textSize = 13f
            setPadding(16, 8, 16, 8)
        }
        root.addView(mainStats)
        root.addView(asyncStats)

        // 动画轨道
        val track = FrameLayout(this).apply { minimumHeight = 320 }
        mainBall = makeBall(Color.parseColor("#3F7FE0"))
        asyncBall = makeBall(Color.parseColor("#3EA65C"))
        track.addView(mainBall, FrameLayout.LayoutParams(96, 96, Gravity.TOP or Gravity.START))
        track.addView(asyncBall, FrameLayout.LayoutParams(96, 96, Gravity.TOP or Gravity.START).apply { topMargin = 160 })
        root.addView(track)

        root.addView(Button(this).apply {
            text = "同时启动两个动画"
            setOnClickListener { startBoth() }
        })
        root.addView(Button(this).apply {
            text = "主线程加压 800ms"
            setOnClickListener { stressMainThread() }
        })
        root.addView(Button(this).apply {
            text = "停止"
            setOnClickListener { stopBoth() }
        })

        refreshStats()
        return root
    }

    private fun makeBall(color: Int): View {
        return View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
    }

    private fun startBoth() {
        stopBoth()
        mainStatsData.lastFrameMs.set(0); mainStatsData.count.set(0)
        mainStatsData.maxGapMs.set(0); mainStatsData.sumGapMs.set(0)
        asyncStatsData.lastFrameMs.set(0); asyncStatsData.count.set(0)
        asyncStatsData.maxGapMs.set(0); asyncStatsData.sumGapMs.set(0)

        val trackWidth = contentContainer.width
        if (trackWidth <= 0) {
            log("布局未完成，请重试")
            return
        }
        val distance = (trackWidth - 96).toFloat()

        // ── 蓝球：主线程 ValueAnimator（传统做法）──────────────────────
        mainAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                mainStatsData.sample(System.currentTimeMillis())
                runOnUiThread {
                    mainBall.translationX = v * distance
                    refreshStats()
                }
            }
            addListener(object : NullableAnimatorListenerAdapter() {
                override fun onAnimationStart(animator: Animator?) {
                    log("[蓝球] onAnimationStart 线程 = ${Thread.currentThread().name}")
                }
            })
            start() // 主线程启动，帧推进也在主线程
        }

        // ── 绿球：AsyncValueAnimator + 独立动画线程 ────────────────────
        asyncAnim = AsyncValueAnimator()
        asyncAnim!!.apply {
            setExecutor(AnimExecutors.ANIM_CONTROL_EXECUTOR) // start/帧推进 → "Launcher Animation Control"
            setFloatValues(0f, 1f)
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                asyncStatsData.sample(System.currentTimeMillis()) // 本回调在独立线程执行
                runOnUiThread {
                    asyncBall.translationX = v * distance
                    refreshStats()
                }
            }
            getAsyncAnimCallbacks().addListener(object : NullableAnimatorListenerAdapter() {
                override fun onAnimationStart(animator: Animator?) {
                    log("[绿球] onAnimationStart 线程 = ${Thread.currentThread().name}")
                }
            })
            start() // 当前在主线程 → 自动 marshal 到独立线程
        }
        log("已启动：蓝球=主线程，绿球=${AnimationControlThread.getThreadName()} 线程")
    }

    private fun stressMainThread() {
        log(">>> 主线程 sleep(800)，观察两球帧间隔差异…")
        mainStatsData.maxGapMs.set(0)
        asyncStatsData.maxGapMs.set(0)
        Thread.sleep(800) // 故意阻塞主线程（模拟重布局/重测量/GC）
        log("<<< 主线程恢复")
    }

    private fun stopBoth() {
        mainAnim?.cancel(); mainAnim = null
        asyncAnim?.cancel(); asyncAnim = null
    }

    private fun refreshStats() {
        mainStats.text = "蓝球(主线程)  线程=${shorten(mainStatsData.displayThread)}  " +
                "帧数=${mainStatsData.count.get()}  平均间隔=${mainStatsData.avgGapMs()}ms  " +
                "最大间隔=${mainStatsData.maxGapMs.get()}ms"
        asyncStats.text = "绿球(独立线程) 线程=${shorten(asyncStatsData.displayThread)}  " +
                "帧数=${asyncStatsData.count.get()}  平均间隔=${asyncStatsData.avgGapMs()}ms  " +
                "最大间隔=${asyncStatsData.maxGapMs.get()}ms"
    }

    private fun shorten(name: String): String = if (name.length > 30) name.substring(0, 30) + "…" else name
}
