package com.asyncanimator.demo

import android.view.View
import android.widget.LinearLayout
import com.asyncanimator.demo.scene.LauncherStageView
import com.asyncanimator.demo.widget.CurvePlotView
import com.asyncanimator.demo.widget.DemoStyle

/**
 * Demo 2 — Holder 进度 + ProgressMapper 钩子。
 *
 * <p>对应分析文档 §6.1.4。三个 Holder 挂在同一个 AnimatorPlaybackController 主时钟上，
 * duration 相同（globalEndProgress 均 = 1.0，同一起跑线），但 ProgressMapper 不同：
 * <ul>
 *   <li>线性：mapper = DEFAULT（f/g 直通）</li>
 *   <li>弹簧：mapper = easeOutBack 过冲曲线</li>
 *   <li>减速：mapper = 1-(1-x)²</li>
 * </ul>
 *
 * <p>可视化：LauncherStageView 舞台上三个图标同时飞向第二排（蓝=线性、青=过冲、黄=减速，
 * 带拖尾），下方 CurvePlotView 用 stage.onFrame 逐帧采样三条曲线——
 * 同一驱动走出不同形状。
 */
class Demo2HolderProgressActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 2: Holder 进度 + ProgressMapper"
    override val docSection = "§6.1.4"

    private lateinit var stage: LauncherStageView
    private lateinit var plotView: CurvePlotView

    private val laneColors = intArrayOf(
        DemoStyle.MAIN_THREAD, DemoStyle.ACCENT, DemoStyle.AMBER)

    /** 与舞台 flyIcon 相同的飞行时长（0.9s），用于下方实时描线。 */
    private val flightMs = 900f

    /** 本轮飞行起始时刻（ms）；<0 表示未在飞行。 */
    @Volatile private var flightStartMs = -1L

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        stage = LauncherStageView(this)
        root.addView(stage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // 3 条实时曲线（蓝=线性，青=过冲，黄=减速）
        plotView = CurvePlotView(this)
        for (i in 0 until 3) plotView.addLane(laneColors[i])
        root.addView(plotView)

        stage.onFrame = { sampleCurves() }

        DemoStyle.addButtonRow(root,
            DemoStyle.primaryButton("三曲线同屏飞行", this) { playAll() },
            DemoStyle.outlineButton("重置", this, DemoStyle.GRAY) { reset() })

        log("3 个 Holder：duration 均 = 1000，globalEndProgress 均 = 1.0（同一起跑线）")
        log("蓝（线性）: mapper = DEFAULT（f/g 直通）")
        log("青（弹簧）: mapper = easeOutBack 过冲曲线；黄（减速）: mapper = 1-(1-x)²")
        log("点「三曲线同屏飞行」：同一主时钟 seek 驱动，三种 mapper 各自整形")
        return root
    }

    /** 同一飞行任务 × 三种曲线：三个图标同时从第一排飞向第二排。 */
    private fun playAll() {
        reset()
        flightStartMs = System.nanoTime() / 1_000_000
        stage.banner = "同一飞行任务 × 三种 ProgressMapper"
        stage.flyIcon(0, 5, LauncherStageView.FlyCurve.LINEAR, laneColors[0])
        stage.flyIcon(1, 6, LauncherStageView.FlyCurve.OVERSHOOT, laneColors[1])
        stage.flyIcon(2, 7, LauncherStageView.FlyCurve.DECEL, laneColors[2])
        log("主时钟 start → 3 个 Holder 同步 setProgress，mapper 各自整形")
    }

    private fun reset() {
        flightStartMs = -1L
        stage.banner = null
        stage.resetScene()
        plotView.clear()
    }

    /** 每帧按飞行时间重算三条 mapper 曲线并采样（与舞台上的拖尾一一对应）。 */
    private fun sampleCurves() {
        val start = flightStartMs
        if (start < 0) return
        val t = ((System.nanoTime() / 1_000_000 - start) / flightMs).coerceIn(0f, 1f)
        // ×0.75 缩放：给弹簧过冲（>1 部分）留出顶部空间
        plotView.sample(0, t * 0.75f)
        plotView.sample(1, overshoot(t) * 0.75f)
        plotView.sample(2, decel(t) * 0.75f)
        if (t >= 1f) {
            flightStartMs = -1L
            log("controller end: success=true，三条曲线同起点/同终点、形状不同")
        }
    }

    /** 弹簧感：easeOutBack 过冲曲线（与舞台 OVERSHOOT 同款，中段会超过 1）。 */
    private fun overshoot(t: Float): Float {
        val s = 1.70158f
        val u = t - 1f
        return 1f + (s + 1f) * u * u * u + s * u * u
    }

    /** 减速：1-(1-x)²，起步快、收尾慢。 */
    private fun decel(t: Float): Float = 1f - (1f - t) * (1f - t)
}
