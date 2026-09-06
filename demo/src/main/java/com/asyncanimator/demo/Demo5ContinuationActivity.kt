package com.asyncanimator.demo

import android.view.View
import android.widget.LinearLayout
import com.asyncanimator.demo.scene.LauncherStageView
import com.asyncanimator.demo.widget.DemoStyle

/**
 * Demo 5 — 续行动画（断点接力）。
 *
 * <p>对应分析文档 §6.5。真机上 generateContinuationAnim 用 RecordInputInterpolator
 * 记录的当前 fraction（和速度）作起点，新对象的 timeController 从该 fraction 无缝
 * 跑到 1.0，速度无跳变。
 *
 * <p>可视化：LauncherStageView 桌面舞台。点「上滑」后虚拟手指上滑、窗口滑向
 * recents 卡片位，到 40% 断住（banner「断点 40%·已记录速度」），800ms 后自动
 * 从断点续行滑到 100%（banner「从断点续行，速度无跳变」）。
 *
 * <p>注意：移植版 TimeControllerObjectAnimator.setTarget/setProperty 是 no-op
 * （lib 侧简化），因此续行段只在 log 里说明概念，动画由舞台演示等价效果。
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
        log("RecordInputInterpolator 实时记录 fraction 与速度")
        stage.banner = "上滑中：窗口滑向 recents 卡片位"
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
            s.banner = "断点 40% · 已记录速度"
            log(">>> 断住：fraction=${"%.2f".format(p)}")
            log("RecordInputInterpolator.inputed = ${"%.2f".format(p)}")
            log("anim.getParam().currentFraction = ${"%.2f".format(p)}")
        }
        if (checkpointLogged && !resumedLogged && p > 0.45f) {
            resumedLogged = true
            s.banner = "从断点续行，速度无跳变"
            log("松手 → generateContinuationAnim：新对象从 0.40 → 1.0 接力")
            log("真机 timeController 驱动 CURRENT_FRACTION；移植版为 no-op，由舞台演示等价效果")
        }
        if (resumedLogged && !doneLogged && p >= 0.999f) {
            doneLogged = true
            s.banner = "续行完成 ✓"
            log("续行完成：recents 卡片落位，全程速度连续、无跳变")
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
