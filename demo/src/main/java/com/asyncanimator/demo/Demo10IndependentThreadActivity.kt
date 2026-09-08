package com.asyncanimator.demo

import android.animation.Animator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.asyncanimator.demo.scene.LauncherStageView
import com.asyncanimator.demo.widget.DemoStyle
import com.asyncanimator.demo.widget.FrameGapHistogramView
import com.asyncanimator.launcher.animthread.AnimExecutors
import com.asyncanimator.launcher.animthread.AnimationControlThread
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
 * - com/android/quickstep/util/animation/CustomRectFSpringAnim.java:888 —— mStartAsync ?
 *   ANIM_EXECUTOR : MAIN_EXECUTOR，再判 looper.isCurrentThread()
 *
 * <p>演示内容：同一舞台窗口做 0→1→0 往复转场，分两路分时驱动（进度逐帧写入窗口 leash）——
 * - 主线程路：平台 ValueAnimator，帧推进走主线程 Choreographer（传统做法）；
 * - launcher.anim 路：AsyncValueAnimator.setExecutor(ANIM_CONTROL_EXECUTOR)，
 *   start 与帧推进都在 "Launcher Animation Control" 线程（默认演示这路）。
 *
 * <p>点"主线程加压 800ms"后：主线程路窗口卡住、直方图飙出一根红柱；
 * launcher.anim 路动画计算帧间隔保持 ~16ms，UI 恢复后可见窗口进度持续推进——
 * 这就是独立动画线程的核心收益。
 *
 * <p>可视化：LauncherStageView 舞台（setExternalDrive 外部逐帧驱动）+ 线程名统计文本
 * + 帧间隔直方图（下=主线程路 上=launcher.anim 路），整体 ScrollView 防裁切。
 */
class Demo10IndependentThreadActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 10: 独立动画线程 (launcher.anim)"
    override val docSection = "OplusExecutors.ANIM_EXECUTOR / AsyncAnimWrapper / CustomRectFSpringAnim"

    private lateinit var stage: LauncherStageView
    private lateinit var mainStats: TextView
    private lateinit var asyncStats: TextView
    private lateinit var hist: FrameGapHistogramView

    private var mainAnim: ValueAnimator? = null
    private var asyncAnim: AsyncValueAnimator? = null

    // 帧统计（在动画计算线程上采样，marshal 回 UI 更新）
    private class FrameStats {
        val count = AtomicLong(0)
        val lastFrameMs = AtomicLong(0)
        val maxGapMs = AtomicLong(0)
        val sumGapMs = AtomicLong(0)
        @Volatile var displayThread = "-"

        /** 采样一帧，返回与上一帧的间隔 ms（首帧返回 0）。 */
        fun sample(frameMs: Long): Long {
            displayThread = Thread.currentThread().name
            val last = lastFrameMs.getAndSet(frameMs)
            if (last > 0) {
                val gap = frameMs - last
                if (gap > maxGapMs.get()) maxGapMs.set(gap)
                sumGapMs.addAndGet(gap)
                count.incrementAndGet()
                return gap
            }
            return 0
        }
        fun avgGapMs(): Long = if (count.get() == 0L) 0 else sumGapMs.get() / count.get()
        fun reset() {
            count.set(0); lastFrameMs.set(0); maxGapMs.set(0); sumGapMs.set(0)
        }
    }

    private val mainStatsData = FrameStats()
    private val asyncStatsData = FrameStats()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 默认演示 launcher.anim 路：舞台一进来就在动
        stage.post { startAnimThreadDrive() }
    }

    override fun onDestroy() {
        stopDrivers()
        super.onDestroy()
    }

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ── 仿桌面舞台（外部驱动模式：进度由动画线程逐帧写入）──
        stage = LauncherStageView(this)
        stage.setExternalDrive(true)
        stage.banner = "待机 · 选择驱动线程"
        root.addView(stage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            DemoStyle.dp(this, 300f)))

        // ── 统计文本（线程名 = 动画跑在哪个线程的关键证据）─────
        mainStats = TextView(this).apply {
            textSize = 12f
            setTextColor(DemoStyle.MAIN_THREAD)
            setPadding(0, DemoStyle.dp(this@Demo10IndependentThreadActivity, 6f), 0, 0)
        }
        asyncStats = TextView(this).apply {
            textSize = 12f
            setTextColor(DemoStyle.ANIM_THREAD)
            setPadding(0, DemoStyle.dp(this@Demo10IndependentThreadActivity, 2f), 0, 0)
        }
        root.addView(mainStats)
        root.addView(asyncStats)

        // ── 帧间隔直方图（核心卖点：加压时主线程路飙红、anim 路平稳）──
        root.addView(sectionLabel("帧间隔直方图：绿 ≤20ms　黄 ≤50ms　红 >50ms（下=主线程路 上=launcher.anim 路）"))
        hist = FrameGapHistogramView(this).apply {
            addChannel(DemoStyle.MAIN_THREAD) // 0 主线程路
            addChannel(DemoStyle.ANIM_THREAD) // 1 launcher.anim 路
        }
        root.addView(hist, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── 按钮 ────────────────────────────────────────
        DemoStyle.addButtonRow(root,
            DemoStyle.primaryButton("启动：launcher.anim 驱动", this) { startAnimThreadDrive() },
            DemoStyle.outlineButton("启动：主线程驱动", this, DemoStyle.MAIN_THREAD) { startMainThreadDrive() })
        DemoStyle.addButtonRow(root,
            DemoStyle.dangerButton("主线程加压 800ms", this) { stressMainThread() },
            DemoStyle.outlineButton("停止", this, DemoStyle.GRAY) { stopDrivers() })

        refreshStats()
        return ScrollView(this).apply { addView(root) }
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        textSize = 11f
        setTextColor(DemoStyle.GRAY)
        this.text = text
        setPadding(0, DemoStyle.dp(this@Demo10IndependentThreadActivity, 8f), 0,
            DemoStyle.dp(this@Demo10IndependentThreadActivity, 2f))
    }

    // ── 主线程路：平台 ValueAnimator，帧推进走主线程 Choreographer ──────────
    private fun startMainThreadDrive() {
        stopDrivers()
        mainStatsData.reset()
        hist.clear()
        stage.banner = "主线程驱动窗口动画 (0→1→0 往复)"

        mainAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                // 本回调在主线程执行
                val v = anim.animatedValue as Float
                val gap = mainStatsData.sample(System.currentTimeMillis())
                stage.driveWindowProgress(v)
                runOnUiThread {
                    if (gap > 0) hist.sample(0, gap.toFloat())
                    refreshStats()
                }
            }
            addListener(object : NullableAnimatorListenerAdapter() {
                override fun onAnimationStart(animator: Animator) {
                    log("[主线程路] onAnimationStart 线程 = ${Thread.currentThread().name}")
                }
            })
            start() // 主线程启动，帧推进也在主线程
        }
        log("主线程路启动：平台 ValueAnimator + 主线程 Choreographer，帧推进在主线程")
    }

    // ── launcher.anim 路：AsyncValueAnimator + 独立动画线程 ─────
    private fun startAnimThreadDrive() {
        stopDrivers()
        asyncStatsData.reset()
        hist.clear()
        stage.banner = "launcher.anim 线程驱动窗口动画 (0→1→0 往复)"

        asyncAnim = AsyncValueAnimator()
        asyncAnim!!.apply {
            setExecutor(AnimExecutors.ANIM_CONTROL_EXECUTOR) // start/帧推进 → "Launcher Animation Control"
            setFloatValues(0f, 1f)
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                // 本回调在独立动画线程执行：直接写舞台进度（内部 postInvalidate）
                val v = anim.animatedValue as Float
                val gap = asyncStatsData.sample(System.currentTimeMillis())
                stage.driveWindowProgress(v)
                runOnUiThread {
                    if (gap > 0) hist.sample(1, gap.toFloat())
                    refreshStats()
                }
            }
            getAsyncAnimCallbacks().addListener(object : NullableAnimatorListenerAdapter() {
                override fun onAnimationStart(animator: Animator) {
                    log("[launcher.anim 路] onAnimationStart 线程 = ${Thread.currentThread().name}")
                }
            })
            start() // 当前在主线程 → 自动 marshal 到独立线程
        }
        log("launcher.anim 路启动：AsyncValueAnimator → ${AnimationControlThread.getThreadName()} 线程")
        log("→ 主线程被阻塞时，动画计算帧间隔仍 ~16ms（UI 恢复后可见进度持续推进）")
    }

    private fun stressMainThread() {
        log(">>> 主线程 sleep(800)：模拟重布局/重测量/GC，观察两路帧间隔差异…")
        mainStatsData.maxGapMs.set(0)
        asyncStatsData.maxGapMs.set(0)
        Thread.sleep(800) // 故意阻塞主线程
        log("<<< 主线程恢复：主线程路应有一根 ~800ms 红柱；launcher.anim 路保持平稳绿柱")
    }

    private fun stopDrivers() {
        mainAnim?.cancel(); mainAnim = null
        asyncAnim?.cancel(); asyncAnim = null
        if (::stage.isInitialized) stage.banner = "待机 · 选择驱动线程"
    }

    private fun refreshStats() {
        mainStats.text = "主线程路　线程=${shorten(mainStatsData.displayThread)}  " +
                "帧数=${mainStatsData.count.get()}  平均间隔=${mainStatsData.avgGapMs()}ms  " +
                "最大间隔=${mainStatsData.maxGapMs.get()}ms"
        asyncStats.text = "launcher.anim 路　线程=${shorten(asyncStatsData.displayThread)}  " +
                "帧数=${asyncStatsData.count.get()}  平均间隔=${asyncStatsData.avgGapMs()}ms  " +
                "最大间隔=${asyncStatsData.maxGapMs.get()}ms"
    }

    private fun shorten(name: String): String = if (name.length > 30) name.substring(0, 30) + "…" else name
}
