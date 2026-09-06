package com.asyncanimator.demo

import android.view.View
import android.widget.LinearLayout
import com.asyncanimator.demo.scene.LauncherStageView
import com.asyncanimator.demo.widget.DemoStyle

/**
 * Demo 1 — MasterClock 主时钟驱动。
 *
 * <p>对应分析文档 §6.1。展示 AnimatorPlaybackController 的核心设计：
 * <ul>
 *   <li>一个 LINEAR 0..1 主 ValueAnimator（mAnimationPlayer）作为唯一被 Choreographer 驱动的对象</li>
 *   <li>N 个 Holder 跟随主时钟同步推进</li>
 *   <li>每帧 setPlayFraction(f) → 循环 Holder.setProgress → 子 anim.setCurrentFraction</li>
 * </ul>
 *
 * <p>可视化：LauncherStageView 桌面舞台，4 个网格图标做按压回弹。
 * 对照两种模式：
 * <ul>
 *   <li>主时钟同步驱动：4 图标被同一节拍 seek，参数一致、完全齐整</li>
 *   <li>独立时钟对照：4 个独立时钟，stiffness/damping 各异，逐渐错开</li>
 * </ul>
 */
class Demo1MasterClockActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 1: MasterClock 主时钟驱动"
    override val docSection = "§6.1"

    private lateinit var stage: LauncherStageView

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        stage = LauncherStageView(this)
        root.addView(stage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        DemoStyle.addButtonRow(root,
            DemoStyle.primaryButton("主时钟同步驱动", this) { startSyncMode() },
            DemoStyle.outlineButton("独立时钟对照", this) { startIndependentMode() })

        log("「主时钟同步驱动」：1 个主 ValueAnimator seek 驱动 4 个 Holder，完全同步")
        log("「独立时钟对照」：4 个独立 ValueAnimator，各自 duration/startDelay，逐渐错开")
        return root
    }

    /** 模式一：主时钟同步驱动 —— 4 图标同一节拍，完全齐整。 */
    private fun startSyncMode() {
        stage.resetScene()
        stage.banner = "主时钟同步驱动：4 图标同一节拍"
        log("AnimatorPlaybackController created with 4 holders")
        log("主时钟 mAnimationPlayer = LINEAR 0..1 ValueAnimator，Choreographer 驱动")
        log("每帧 setPlayFraction(f) → 循环 Holder.setProgress → 4 图标完全同步")
        stage.bounceIcons(sync = true)
    }

    /** 模式二：独立时钟对照 —— 各自刚度/阻尼，逐渐错开。 */
    private fun startIndependentMode() {
        stage.resetScene()
        stage.banner = "独立时钟对照：各自参数，逐渐错开"
        log("对照：不经过 AnimatorPlaybackController，每个图标一个独立时钟")
        log("stiffness/damping 各异（无统一节拍）→ 回弹相位逐渐错开")
        stage.bounceIcons(sync = false)
    }
}
