package com.asyncanimator.demo

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.asyncanimator.demo.scene.LauncherStageView
import com.asyncanimator.demo.widget.CurvePlotView
import com.asyncanimator.demo.widget.DemoStyle

/**
 * Demo 9 — 完整 AllApps ↔ Workspace 转场（端到端）。
 *
 * <p>对应分析文档 §7.1。转场链路概念：StateManager.goToState → PendingAnimation(addFloat×3)
 * → AnimatorPlaybackController(250ms 主时钟) → 逐帧 setPlayFraction → Holder.setProgress
 * → onAnimationEnd → OnAnimationEndDispatcher。
 *
 * <p>可视化：LauncherStageView 仿桌面舞台——点任意图标，窗口 leash 以弹簧曲线从图标位
 * 放大到全屏（OPEN_FROM_HOME：图标淡出 + 壁纸放大 + 圆角收缩同步推进）；点窗口 / 上滑缩回；
 * 上滑手势可进 recents 卡片位。底部曲线图实时描窗口 leash 与 recents 两条进度。
 */
class Demo9AllAppsTransitionActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 9: 完整 AllApps ↔ Workspace 转场"
    override val docSection = "§7.1"

    private lateinit var stage: LauncherStageView
    private lateinit var plot: CurvePlotView
    private var laneWindow = 0
    private var laneRecents = 1

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ── 仿桌面舞台（主体）──────────────────────────────
        stage = LauncherStageView(this)
        root.addView(stage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        stage.banner = "WORKSPACE · 点图标打开应用"
        stage.onIconTapped = { i -> openFromIcon(i) }
        stage.onWindowTapped = { closeWindow("点击窗口") }
        stage.onFrame = { s ->
            plot.sample(laneWindow, s.windowProgress)
            plot.sample(laneRecents, s.recentsProgress)
        }

        // ── 转场进度曲线 ─────────────────────────────────
        root.addView(TextView(this).apply {
            textSize = 11f
            setTextColor(DemoStyle.GRAY)
            text = "转场进度曲线：靛蓝 = 窗口 leash　青 = recents"
            setPadding(0, DemoStyle.dp(this@Demo9AllAppsTransitionActivity, 6f), 0, 0)
        })
        plot = CurvePlotView(this)
        laneWindow = plot.addLane(DemoStyle.PRIMARY)
        laneRecents = plot.addLane(DemoStyle.ACCENT)
        root.addView(plot, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── 按钮 ────────────────────────────────────────
        DemoStyle.addButtonRow(root,
            DemoStyle.primaryButton("打开应用", this) { openFromIcon(0) },
            DemoStyle.outlineButton("上滑关闭", this) { closeWindow("上滑手势") })
        DemoStyle.addButtonRow(root,
            DemoStyle.outlineButton("上滑进 recents", this, DemoStyle.ACCENT) { goRecents() },
            DemoStyle.outlineButton("收回桌面", this, DemoStyle.GRAY) { backHome() })

        log("转场链路（概念）：StateManager.goToState → PendingAnimation(addFloat×3)")
        log("→ AnimatorPlaybackController(250ms 主时钟) → 逐帧 setPlayFraction → Holder.setProgress")
        log("→ onAnimationEnd → OnAnimationEndDispatcher.onAnimationSuccess")
        return root
    }

    /** 点击图标：leash 从图标位置弹簧放大到全屏（OPEN_FROM_HOME）。 */
    private fun openFromIcon(i: Int) {
        if (stage.isAppOpen) return
        stage.banner = "OPEN_FROM_HOME · 窗口弹簧展开"
        stage.openApp(i)
        log("点击图标[$i] → playbackController.start()（概念）")
        log("→ APC 主时钟逐帧 setPlayFraction(f)，3 个 Holder(高度/alpha/缩放) 同步推进")
        log("→ 舞台演示：图标淡出 + 壁纸放大 + 窗口 leash 弹簧(stiffness≈300, damping≈0.87)到全屏")
    }

    /** 缩回图标。 */
    private fun closeWindow(from: String) {
        if (!stage.isAppOpen && stage.windowProgress <= 0.005f) return
        stage.banner = "WORKSPACE · 窗口收回图标"
        stage.closeApp()
        log("$from → playbackController.reverse()（概念）：Holder 同步反向推进")
        log("→ 舞台演示：窗口弹簧缩回图标位，壁纸复位，图标淡入")
    }

    /** 上滑手势进 recents：窗口从全屏插值到卡片位。 */
    private fun goRecents() {
        stage.banner = "上滑手势 · 进入 RECENTS"
        stage.swipeToRecents(1f)
        log("上滑手势 → recentsProgress 0→1：窗口从全屏插值到 recents 卡片位")
    }

    /** 收回桌面。 */
    private fun backHome() {
        stage.banner = "WORKSPACE · 点图标打开应用"
        stage.exitRecents()
        log("收回桌面：recents 弹簧回 0，窗口缩回图标")
    }

    @Deprecated("demo 用旧回调拦截返回键")
    override fun onBackPressed() {
        if (stage.isAppOpen) closeWindow("onBackPressed")
        else @Suppress("DEPRECATION") super.onBackPressed()
    }
}
