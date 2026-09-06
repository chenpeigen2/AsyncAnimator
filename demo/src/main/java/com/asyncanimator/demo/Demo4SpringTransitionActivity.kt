package com.asyncanimator.demo

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.asyncanimator.demo.scene.LauncherStageView
import com.asyncanimator.demo.scene.SceneSpring
import com.asyncanimator.demo.widget.CurvePlotView
import com.asyncanimator.demo.widget.DemoStyle

/**
 * Demo 4 — Spring 渐进切换（同一对象内 swap 驱动源）。
 *
 * <p>对应分析文档 §6.4。展示 OplusSpringObjectAnimator 的 SpringProperty 装饰器：
 * 平时 ObjectAnimator 推进；调用 startSpring() 后 spring.animateToFinalPosition() 接管。
 *
 * <p>可视化：LauncherStageView 舞台 —— 先 openApp 打开窗口，随后关闭分两段：
 * <ul>
 *   <li>前 60%：ObjectAnimator 匀速（外部逐帧 driveWindowProgress 线性 1→0.4）</li>
 *   <li>后 40%：Spring 接管（同参数 SceneSpring 带速度接力，阻尼 0.55 有可见过冲；
 *       窗口进度钳制 ≥0 避免 RectF 反折，过冲完整画在下方曲线上）</li>
 * </ul>
 *
 * <p>注意：移植版 OplusSpringObjectAnimator 内部的 mSpring 不写回 target（仅通知自身
 * listener 且无外部访问口），因此 Spring 阶段由场景侧同参数解析解弹簧实际驱动——
 * 这正是 startSpring() 想要达到的物理行为，用于把过冲画出来。
 */
class Demo4SpringTransitionActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 4: SpringProperty 渐进切换"
    override val docSection = "§6.4"

    private lateinit var stage: LauncherStageView
    private lateinit var plotView: CurvePlotView

    private var laneObject = 0
    private var laneSpring = 1

    // 阶段机：0 打开中 → 1 ObjectAnimator 匀速段 → 2 Spring 接管段 → 3 完成
    private var phase = 0
    private var runId = 0
    private var lastNs = 0L
    private var uniformP = 1f
    private var spring: SceneSpring? = null

    /** 匀速段速度：1 → 0.4（前 60%）用 600ms。 */
    private val uniformSpeed = 1f

    /** Spring 接管点（后 40% 起点）。 */
    private val springStartP = 0.4f

    /** 曲线映射：0→0.15、1→0.85，给弹簧过冲（<0 部分）留出底部空间。 */
    private fun plotV(p: Float) = 0.15f + p * 0.7f

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        stage = LauncherStageView(this)
        root.addView(stage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(TextView(this).apply {
            text = "关闭曲线：灰 = ObjectAnimator 匀速段　青 = Spring 接管段（过冲后收敛到 0）"
            textSize = 11f
            setTextColor(DemoStyle.GRAY)
            setPadding(0, DemoStyle.dp(this@Demo4SpringTransitionActivity, 6f), 0,
                DemoStyle.dp(this@Demo4SpringTransitionActivity, 2f))
        })

        plotView = CurvePlotView(this)
        laneObject = plotView.addLane(DemoStyle.GRAY)
        laneSpring = plotView.addLane(DemoStyle.ACCENT)
        root.addView(plotView)

        DemoStyle.addButtonRow(root,
            DemoStyle.primaryButton("播放渐进切换", this) { playTransition() })
        log("一次播放完整流程：打开窗口 → 关闭前 60% 匀速 → 后 40% Spring 过冲收敛")
        return root
    }

    /** 一键播放：openApp → 等窗口全开 → 匀速关到 0.4 → startSpring 接管到 0。 */
    private fun playTransition() {
        runId++
        val myRun = runId
        phase = 0
        spring = null
        lastNs = 0L
        stage.setExternalDrive(false)
        stage.resetScene()
        plotView.clear()
        stage.banner = "打开应用…"
        log("OplusSpringObjectAnimator created：mObjectAnimator + mSpring 双驱动")
        log("start() → 先走 ObjectAnimator（SpringProperty.useSpring=false）")
        stage.openApp(0)

        // 舞台帧回调驱动整个阶段机（UI 线程；外部驱动期间舞台帧钟持续运行）
        stage.onFrame = { s -> if (myRun == runId) tickTransition(s) }
    }

    private fun tickTransition(stage: LauncherStageView) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceAtMost(0.05f)
        lastNs = now
        when (phase) {
            0 -> if (stage.windowProgress >= 0.995f) {
                // 窗口已全开：进入关闭流程，前 60% 匀速（外部驱动）
                phase = 1
                uniformP = 1f
                stage.setExternalDrive(true)
                stage.driveWindowProgress(1f)
                stage.banner = "① ObjectAnimator 匀速段（灰线）"
                log("关闭开始：前 60% ObjectAnimator 匀速推进（1 → 0.4）")
            }
            1 -> {
                uniformP -= uniformSpeed * dt
                if (uniformP <= springStartP) {
                    uniformP = springStartP
                    switchToSpring(stage)
                }
                stage.driveWindowProgress(uniformP)
                plotView.sample(laneObject, plotV(uniformP))
            }
            2 -> {
                val s = spring ?: return
                s.advance(dt)
                // 窗口进度钳制 >=0（过冲段不反折 RectF）；曲线画原始值，过冲一目了然
                stage.driveWindowProgress(s.value.coerceAtLeast(0f))
                plotView.sample(laneSpring, plotV(s.value))
                if (s.isAtRest) finishTransition(stage)
            }
        }
    }

    /** 阶段切换：SpringProperty.switchToSpring()，随后 Spring 带速度接力接管。 */
    private fun switchToSpring(stage: LauncherStageView) {
        phase = 2
        stage.banner = "② Spring 接管（青线，过冲后收敛）"
        log("startSpring() → mProperty.switchToSpring() = true")
        log("后续 setValue → spring.animateToFinalPosition(v)，Spring 接管")
        // 从匀速段接力：位置 0.4、初速度 = 匀速段速度，阻尼 0.55 制造可见过冲
        spring = SceneSpring(300f, 0.55f).apply {
            setValue(springStartP, -uniformSpeed)
            animateTo(0f)
        }
    }

    private fun finishTransition(stage: LauncherStageView) {
        phase = 3
        stage.driveWindowProgress(0f)
        stage.setExternalDrive(false)   // 外部驱动用完复位
        stage.closeApp()                // 恢复图标、收尾停帧钟
        stage.banner = "完成：匀速段 + Spring 过冲收敛"
        log("Spring 收敛到位：value=0.0（过冲后稳定）")
    }
}
