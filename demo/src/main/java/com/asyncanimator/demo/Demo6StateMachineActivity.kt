package com.asyncanimator.demo

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.asyncanimator.demo.scene.LauncherStageView
import com.asyncanimator.demo.widget.DemoStyle
import com.asyncanimator.demo.widget.StateGraphView
import com.asyncanimator.launcher.async.CustomRectFSpringAnim
import com.asyncanimator.launcher.controller.AnimationController
import com.asyncanimator.launcher.controller.AnimationState
import com.asyncanimator.launcher.controller.TaskStateChangeTimeOutListener

/**
 * Demo 6 — 状态机 + 3 种超时监听器。
 *
 * <p>对应分析文档 §6.8。展示 AnimationState 的转换和三种独立超时 listener 的注册/触发逻辑。
 *
 * <p>可视化：LauncherStageView 仿桌面舞台真动演示「开 app → 回桌面 → 上滑进 recents →
 * 反转回 app」完整手势链；下方 StateGraphView 画 6 个代表状态（12 个全画太密，
 * MULTI_* 归并到对应基础状态），每次迁移小球飞行 + 当前状态高亮 + 舞台 banner 同步；
 * 完整状态名仍打在日志和状态标签里。
 *
 * <p>注意：WAITING / REVERSE_OPEN 在 lib 里由 private 的 updateAnimState() 在手势流程内部
 * 设置，demo 没有公开事件入口能到达，这两步由舞台 + 状态图直接演示，日志中说明。
 */
class Demo6StateMachineActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 6: 状态机 + 3 种超时"
    override val docSection = "§6.8"

    /** 状态图上画的 6 个代表状态。 */
    private val graphStates = listOf("NONE", "OPEN", "CLOSE", "REVERSE_OPEN", "WAITING", "UNKNOWN")

    private lateinit var stage: LauncherStageView
    private lateinit var graph: StateGraphView
    private lateinit var stateLabel: TextView
    private lateinit var timeoutLabel: TextView
    private lateinit var listenerLabel: TextView
    private val controller = AnimationController()

    /** 状态图当前高亮（图上的代表状态，可能不等于 controller 的完整状态名）。 */
    private var graphCurrent: String? = "NONE"
    private val seqHandler = Handler(Looper.getMainLooper())
    private var seqRunning = false

    override fun createContentView(): View {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 状态变更回调 → 状态图小球飞行 + 高亮（按钮在主线程触发，回调也在主线程）
        controller.addOnAnimStateChangeListener { old, new, _ ->
            onStateChanged(old, new)
        }

        // ── 舞台：仿 launcher 桌面，动画真动 ──
        stage = LauncherStageView(this)
        content.addView(stage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 状态图：6 个代表状态 ──
        graph = StateGraphView(this).apply {
            setStates(graphStates)
            setCurrent("NONE")
        }
        content.addView(graph, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── 状态行：当前状态 pill + 最近一次超时事件 ──
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stateLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = DemoStyle.roundRect(DemoStyle.PRIMARY, 8f, this@Demo6StateMachineActivity)
            val ph = DemoStyle.dp(this@Demo6StateMachineActivity, 10f)
            val pv = DemoStyle.dp(this@Demo6StateMachineActivity, 5f)
            setPadding(ph, pv, ph, pv)
            text = "当前状态: NONE"
        }
        timeoutLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(DemoStyle.GRAY)
            val ph = DemoStyle.dp(this@Demo6StateMachineActivity, 8f)
            val pv = DemoStyle.dp(this@Demo6StateMachineActivity, 5f)
            setPadding(ph, pv, ph, pv)
            text = "超时: —"
        }
        statusRow.addView(stateLabel)
        statusRow.addView(timeoutLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = DemoStyle.dp(this@Demo6StateMachineActivity, 8f) })
        content.addView(statusRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = DemoStyle.dp(this@Demo6StateMachineActivity, 6f) })

        listenerLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(DemoStyle.INK)
            text = buildListenerStatus()
        }
        content.addView(listenerLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = DemoStyle.dp(this@Demo6StateMachineActivity, 4f) })

        // ── 自动演示 ──
        DemoStyle.addButtonRow(content,
            DemoStyle.primaryButton("自动演示序列", this) {
                startSequence()
            },
            DemoStyle.dangerButton("重置", this) {
                resetAll()
            }
        )

        // ── 三种超时 listener ──
        content.addView(sectionCaption("三种超时 listener（先注册，再模拟触发）"))
        DemoStyle.addButtonRow(content,
            DemoStyle.outlineButton("注册 SpecialSceneExit", this) {
                controller.registerSpecialSceneExitTimeOutListener(1500L)
                log("mSpecialSceneExitTimeOutListener 已注册")
                refreshUI()
            },
            DemoStyle.outlineButton("注册 TransitionFinish", this) {
                controller.registerTransitionFinishTimeOutListener(1500L)
                log("mTransitionFinishTimeOutListener 已注册")
                refreshUI()
            }
        )
        DemoStyle.addButtonRow(content,
            DemoStyle.outlineButton("注册 OverviewContinuation", this) {
                controller.registerOverviewContinuationTimeOutListener(100L)
                log("mOverviewContinuationTimeOutListener 已注册")
                refreshUI()
            },
            DemoStyle.dangerButton("模拟超时触发", this) {
                simulateTimeout()
            }
        )

        return content
    }

    // ── 自动演示序列 ──────────────────────────────────────

    /**
     * OPEN（开 app）→ 停 1.2s → CLOSE（回桌面）→ 停 1.2s →
     * WAITING（上滑进 recents）→ 停 1s → REVERSE_OPEN（反转回 app）。
     *
     * <p>状态机事件走 lib 的 AnimationController 真实入口
     * （appLaunchAnimStartOrEnd / addRecentsAnim）；WAITING、REVERSE_OPEN
     * 是 lib 内部态（updateAnimState 为 private，仅手势流程内设置），
     * 这两步由舞台 + 状态图直接演示并在日志说明。
     */
    private fun startSequence() {
        if (seqRunning) {
            log("演示序列进行中…（可点「重置」中止）")
            return
        }
        seqRunning = true
        stage.resetScene()
        controller.reset()
        log("═══ 自动演示序列: OPEN → CLOSE → WAITING → REVERSE_OPEN ═══")

        // Step 1: 开 app → OPEN
        seqHandler.postDelayed({
            stage.banner = "OPEN — 打开应用"
            stage.openApp(0)
            controller.appLaunchAnimStartOrEnd(false, null, arrayOf())
            log("lib 事件: appLaunchAnimStartOrEnd(isEnd=false)")
        }, 300L)

        // Step 2: 回桌面 → CLOSE（OPEN 下 addRecentsAnim 得 MULTI_CLOSE，图上归并 CLOSE）
        seqHandler.postDelayed({
            stage.banner = "CLOSE — 回到桌面"
            stage.closeApp()
            controller.addRecentsAnim(
                CustomRectFSpringAnim(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME),
                null, arrayOf())
            log("lib 事件: addRecentsAnim(SWIPE_TO_HOME)")
        }, 1500L)

        // Step 3: 上滑进 recents → WAITING（lib 内部态，无公开入口）
        seqHandler.postDelayed({
            stage.banner = "WAITING — 上滑进 Recents"
            stage.swipeToRecents()
            driveGraphState("WAITING")
            log("WAITING 由 lib 内部 updateAnimState() 设置（手势流程内，demo 无公开入口）→ 舞台直接演示")
        }, 2700L)

        // Step 4: 反转回 app → REVERSE_OPEN（lib 内部态）
        seqHandler.postDelayed({
            stage.banner = "REVERSE_OPEN — 从 Recents 反转回 App"
            stage.exitRecents()
            stage.openApp(0)
            driveGraphState("REVERSE_OPEN")
            log("REVERSE_OPEN 同为 lib 内部态（recents 反向打开时设置）→ 舞台直接演示")
        }, 3700L)

        seqHandler.postDelayed({
            stage.banner = null
            seqRunning = false
            log("═══ 序列结束（controller 停留在 ${controller.getAnimState()}，点「重置」归位 NONE）═══")
        }, 5200L)
    }

    private fun resetAll() {
        seqHandler.removeCallbacksAndMessages(null)
        seqRunning = false
        stage.banner = null
        stage.resetScene()
        controller.reset()          // → NONE，监听器同步状态图
        graphCurrent = "NONE"
        timeoutLabel.text = "超时: —"
        timeoutLabel.setTextColor(DemoStyle.GRAY)
        log("已重置: 舞台 + 状态机 → NONE")
    }

    override fun onDestroy() {
        seqHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ── 状态迁移 ──────────────────────────────────────────

    /** 实际状态 → 图上代表状态；MULTI_* 归并到基础状态，SWIPE_UP_TO_* 等只在日志体现。 */
    private fun mapToGraph(s: AnimationState): String? {
        if (s.name in graphStates) return s.name
        if (s.name.startsWith("MULTI_")) {
            val base = s.name.removePrefix("MULTI_")
            if (base in graphStates) return base
        }
        return null
    }

    private fun onStateChanged(old: AnimationState, new: AnimationState) {
        val from = mapToGraph(old)
        val to = mapToGraph(new)
        if (from != null && to != null && from != to) {
            graph.transition(from, to)   // 小球飞行 + setCurrent(to)
        } else {
            graph.setCurrent(to)         // to 为 null 时取消高亮（完整名见日志）
        }
        graphCurrent = to
        stateLabel.text = "当前状态: ${new.name}"
        // UNKNOWN 用红色警示
        stateLabel.background = DemoStyle.roundRect(
            if (new == AnimationState.UNKNOWN) DemoStyle.WARN else DemoStyle.PRIMARY,
            8f, this)
        log("状态迁移: ${old.name} → ${new.name}" +
            if (new.name.startsWith("MULTI_")) "（图上归并到 ${new.name.removePrefix("MULTI_")}）" else "")
    }

    /** lib 内部态（WAITING / REVERSE_OPEN）没有公开事件入口，直接驱动状态图演示。 */
    private fun driveGraphState(name: String) {
        val from = graphCurrent
        if (from != null && from != name) graph.transition(from, name) else graph.setCurrent(name)
        graphCurrent = name
        stateLabel.text = "当前状态: $name（lib 内部态）"
        stateLabel.background = DemoStyle.roundRect(DemoStyle.PRIMARY, 8f, this)
    }

    // ── 三种超时 listener ─────────────────────────────────

    /** 直接调用已注册 listener 的 onTimeOut，演示超时兜底路径。 */
    private fun simulateTimeout() {
        var fired = false
        controller.getSpecialSceneExitTimeOutListener()?.let {
            it.onTimeOut(TaskStateChangeTimeOutListener.Type.ON_LAND_SCAPE_SCENE_EXIT, 1500L)
            showTimeout("ON_LAND_SCAPE_SCENE_EXIT")
            fired = true
        }
        controller.getTransitionFinishTimeOutListener()?.let {
            it.onTimeOut(TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH, 1500L)
            showTimeout("ON_TRANSITION_FINISH")
            fired = true
        }
        controller.getOverviewContinuationTimeOutListener()?.let {
            it.onTimeOut(TaskStateChangeTimeOutListener.Type.ON_APP_TO_OVERVIEW_CONTINUATION, 100L)
            showTimeout("ON_APP_TO_OVERVIEW_CONTINUATION")
            fired = true
        }
        if (!fired) {
            log("没有已注册的 listener，请先点击上方注册按钮")
            timeoutLabel.text = "超时: 无已注册 listener"
            timeoutLabel.setTextColor(DemoStyle.GRAY)
        }
    }

    private fun showTimeout(type: String) {
        log("超时触发: $type → onTimeOut() 执行兜底逻辑")
        timeoutLabel.text = "超时: $type"
        timeoutLabel.setTextColor(DemoStyle.WARN)
    }

    private fun sectionCaption(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(DemoStyle.GRAY)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, DemoStyle.dp(this@Demo6StateMachineActivity, 10f), 0, 0)
    }

    private fun buildListenerStatus(): String {
        val sb = StringBuilder()
        sb.append("mSpecialSceneExit: ").append(if (controller.getSpecialSceneExitTimeOutListener() != null) "✓" else "✗").append("   ")
        sb.append("mTransitionFinish: ").append(if (controller.getTransitionFinishTimeOutListener() != null) "✓" else "✗").append("   ")
        sb.append("mOverviewContinuation: ").append(if (controller.getOverviewContinuationTimeOutListener() != null) "✓" else "✗")
        return sb.toString()
    }

    private fun refreshUI() {
        listenerLabel.text = buildListenerStatus()
        log("状态机更新: ${controller.getAnimState()}")
    }
}
