package com.asyncanimator.demo

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import com.asyncanimator.demo.scene.LauncherStageView
import com.asyncanimator.demo.widget.DemoStyle
import com.asyncanimator.seq.AnimationSeqHelper
import com.asyncanimator.seq.AnimSeqTimeStamp

/**
 * Demo 7 — SeqId 防抖。
 *
 * <p>对应分析文档 §6.9。模拟 500ms 内连续 5 次回桌面（finish recents）请求：
 * delayFinishRecents 在 500ms 防抖窗口内只让第 1 个真的 finish，其余被拦截延后，
 * 且相互去重 —— 只有最后一个延后 runnable 会在 500ms 后补发。
 *
 * <p>可视化：LauncherStageView 桌面舞台先 openApp(0)。第 1 次请求正常执行 ——
 * 窗口弹簧缩回图标位（closeApp）；其余 4 次在舞台下方生成灰色小胶囊
 * 「#2 已拦截」「#3 已拦截」…；500ms 后去重补发的那次在 log 中可见。
 */
class Demo7SeqIdDedupActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 7: SeqId 防抖"
    override val docSection = "§6.9"

    private val seqHelper = AnimationSeqHelper()
    private var actualRuns = 0
    private var intercepted = 0

    private lateinit var stage: LauncherStageView
    private lateinit var capsuleRow: LinearLayout

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        stage = LauncherStageView(this)
        root.addView(stage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // 拦截胶囊行（屏幕下方、按钮之上）
        capsuleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(capsuleRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        DemoStyle.addButtonRow(root,
            DemoStyle.primaryButton("狂点回桌面 ×5", this) { fireFive() },
            DemoStyle.outlineButton("重置", this) { resetAll() })

        // 舞台先开 app，等待「回桌面」请求
        stage.openApp(0)
        return root
    }

    /** 在胶囊行追加一个灰色小胶囊。 */
    private fun addCapsule(text: String) {
        val pill = DemoStyle.pill(text, this, bg = 0x1A8A90A0, fg = DemoStyle.GRAY)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = DemoStyle.dp(this, 6f)
        lp.topMargin = DemoStyle.dp(this, 6f)
        capsuleRow.addView(pill, lp)
    }

    // ── 演示逻辑 ────────────────────────────────────────────────

    private fun fireFive() {
        capsuleRow.removeAllViews()
        actualRuns = 0
        intercepted = 0
        if (!stage.isAppOpen) stage.openApp(0)
        log("=== 500ms 内狂点 5 次回桌面 ===")
        for (i in 1..5) {
            val bundle = Bundle()
            seqHelper.addSeqId(bundle)
            val id = bundle.getLong("interrupt.transition.startActivity.seqId")
            val delayed = seqHelper.delayFinishRecents {
                actualRuns++
                // 真实 finish 会刷新时间戳，500ms 防抖窗口由此生效
                AnimSeqTimeStamp.updateLastRecentFinishTime()
                log("[run] 真正 finish #${actualRuns}（请求 #$i, seqId=$id）")
                runOnUiThread {
                    stage.banner = "回桌面"
                    stage.closeApp()
                    stage.postDelayed({ stage.banner = null }, 1200)
                }
            }
            if (delayed) {
                intercepted++
                addCapsule("#$i 已拦截")
            }
            log("请求 $i: seqId=$id, delayed=$delayed")
        }
        log("--- 5 次连发完成：立即执行 ${5 - intercepted} 次，防抖拦截 $intercepted 次 ---")
        log("500ms 防抖窗口内后续请求相互去重：只有最后一个会在 500ms 后补发")
    }

    private fun resetAll() {
        seqHelper.clearFinishRecentsRunnable()
        capsuleRow.removeAllViews()
        stage.banner = null
        stage.resetScene()
        stage.openApp(0)
        actualRuns = 0
        intercepted = 0
        log("已重置：延后队列清除，app 重新打开")
    }
}
