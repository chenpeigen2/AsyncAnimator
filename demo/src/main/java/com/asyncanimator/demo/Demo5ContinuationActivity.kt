package com.asyncanimator.demo

import android.view.View
import android.widget.LinearLayout
import com.asyncanimator.demo.scene.LauncherStageView
import com.asyncanimator.demo.widget.DemoStyle

/**
 * Demo 5 — 续行动画概念演示（断点接力）。
 *
 * LauncherStageView 在 40% 停顿 800ms 后继续到终点，使用自己的舞台动画，
 * 不调用 lib 的 internal OplusValueAnimator，也不是原厂速度连续性的验证。
 * 库的 RecordInputInterpolator 只记录输入 fraction；TimeController 的 target/property
 * 接线和续行值输出由库单测验证，不应从本页视觉表现推导。
 */
class Demo5ContinuationActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 5: OplusValueAnimator 续行动画"
    override val docSection = "§6.5"

    private lateinit var stage: LauncherStageView
    private var playToken = 0
    private var checkpointLogged = false
    private var resumedLogged = false
    private var doneLogged = false

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        stage = LauncherStageView(this)
        root.addView(stage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        DemoStyle.addButtonRow(root,
            DemoStyle.primaryButton("上滑（会在 40% 断住）", this) { playContinuation() },
            DemoStyle.outlineButton("重置", this) { resetAll() })

        return root
    }

    // ── 自动流程：上滑 → 40% 断住 → 800ms 后续行到 100% ─────────────

    private fun playContinuation() {
        val my = ++playToken
        checkpointLogged = false
        resumedLogged = false
        doneLogged = false
        stage.onFrame = { s -> onStageFrame(s) }
        stage.resetScene()
        log("=== 上滑进 recents（40% 断点续行） ===")
        log("概念：RecordInputInterpolator 记录输入 fraction，不记录速度")
        stage.banner = "上滑中：舞台窗口滑向 recents 卡片位"
        stage.openApp(0)
        // 等开屏转场走一段后，虚拟手指开始上滑
        stage.postDelayed({
            if (my == playToken) stage.swipeToRecents(stopAt = 0.4f, holdMs = 800)
        }, 600)
    }

    /** 每帧采样 recents 进度，在断点 / 续行 / 完成三个时刻打 banner 与 log。 */
    private fun onStageFrame(s: LauncherStageView) {
        val p = s.recentsProgress
        if (!checkpointLogged && p >= 0.399f && p < 0.45f) {
            checkpointLogged = true
            s.banner = "断点 40% · 舞台进度采样"
            log(">>> 断住：fraction=${"%.2f".format(p)}")
            log("概念对应 inputed（舞台采样）= ${"%.2f".format(p)}")
            log("概念对应 currentFraction（舞台采样）= ${"%.2f".format(p)}")
        }
        if (checkpointLogged && !resumedLogged && p > 0.45f) {
            resumedLogged = true
            s.banner = "从断点继续播放（舞台示意）"
            log("舞台从 0.40 → 1.0 继续；概念对应 generateContinuationAnim")
            log("库已接通 timeController target/property；本页未调用库续行入口")
        }
        if (resumedLogged && !doneLogged && p >= 0.999f) {
            doneLogged = true
            s.banner = "续行完成 ✓"
            log("舞台续播完成：recents 卡片落位；不作为速度连续性验证")
            s.postDelayed({ s.banner = null }, 1200)
        }
    }

    private fun resetAll() {
        playToken++
        stage.onFrame = null
        stage.banner = null
        stage.exitRecents()
        stage.resetScene()
        log("已重置")
    }
}
