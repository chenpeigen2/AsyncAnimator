package com.asyncanimator.demo

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.asyncanimator.launcher.seq.AnimationSeqHelper
import com.asyncanimator.launcher.seq.AnimSeqTimeStamp

/**
 * Demo 7 — SeqId 防抖。
 *
 * <p>对应分析文档 §6.9。演示连续点击 5 次，每次加一个 seqId，但
 * delayFinishRecents 在 500ms 内只让第一个真的 finish，其余延后。
 */
class Demo7SeqIdDedupActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 7: SeqId 防抖"
    override val docSection = "§6.9"

    private val seqHelper = AnimationSeqHelper()
    private val seqIds = mutableListOf<Long>()
    private var pendingRuns = 0
    private var actualRuns = 0

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(Button(this).apply {
            text = "模拟最近一次 recent finish（500ms 内）"
            setOnClickListener {
                AnimSeqTimeStamp.updateLastRecentFinishTime()
                log("已模拟最近 recent finish 时间戳")
                log("现在 canFinishRecent = ${seqHelper.canFinishRecent()}")
            }
        })

        root.addView(Button(this).apply {
            text = "连续点击 5 次（添加 seqId + 尝试 finish）"
            setOnClickListener {
                seqIds.clear()
                pendingRuns = 0
                actualRuns = 0
                for (i in 1..5) {
                    val bundle = Bundle()
                    seqHelper.addSeqId(bundle)
                    val id = bundle.getLong("interrupt.transition.startActivity.seqId")
                    seqIds.add(id)
                    val delayed = seqHelper.delayFinishRecents {
                        actualRuns++
                        log("[run] finish #${actualRuns}")
                    }
                    if (delayed) pendingRuns++ else { actualRuns++ }
                    log("点击 $i: seqId=$id, delayed=${delayed}")
                }
                log("--- 5 次点击完成 ---")
                log("实际 finish 次数: $actualRuns")
                log("延后 finish 次数: $pendingRuns")
            }
        })

        root.addView(Button(this).apply {
            text = "清除延后队列"
            setOnClickListener {
                seqHelper.clearFinishRecentsRunnable()
                log("已清除")
            }
        })

        return root
    }
}