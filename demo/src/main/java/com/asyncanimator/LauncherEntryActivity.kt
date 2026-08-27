package com.asyncanimator.demo

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.asyncanimator.demo.databinding.ActivityEntryBinding

/**
 * LauncherEntryActivity — Demo 入口列表。
 *
 * <p>9 个 Demo 的入口，点击跳转到对应 Activity。
 */
class LauncherEntryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEntryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEntryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val demos = listOf(
            DemoInfo("Demo 1: MasterClock 主时钟驱动",
                "§6.1 — 一个 LINEAR 0..1 ValueAnimator 同步推动 N 个 Holder",
                Demo1MasterClockActivity::class.java),
            DemoInfo("Demo 2: Holder 进度 + ProgressMapper",
                "§6.1 — 不同 mapper 让子动画走出不同曲线（弹簧 / 线性）",
                Demo2HolderProgressActivity::class.java),
            DemoInfo("Demo 3: Async 跨 Looper 安全",
                "§6.3 — start/cancel/end 和 listener 都 marshal 到目标 Looper",
                Demo3AsyncCrossThreadActivity::class.java),
            DemoInfo("Demo 4: Spring 渐进切换",
                "§6.4 — OplusSpringObjectAnimator 内部 ObjectAnimator + Spring 切换",
                Demo4SpringTransitionActivity::class.java),
            DemoInfo("Demo 5: 续行动画",
                "§6.5 — OplusValueAnimator.generateContinuationAnim 从当前 fraction 继续",
                Demo5ContinuationActivity::class.java),
            DemoInfo("Demo 6: 状态机 + 3 种超时",
                "§6.8 — 11 个 AnimationState + 3 种独立超时 listener",
                Demo6StateMachineActivity::class.java),
            DemoInfo("Demo 7: SeqId 防抖",
                "§6.9 — 500ms 内只 finish 第一个，其余延后",
                Demo7SeqIdDedupActivity::class.java),
            DemoInfo("Demo 8: Feature Flag 工厂",
                "§6.10 + §6.11 — OplusAnimManager 切换 Default/Impl + 9 个 RUS 配置",
                Demo8FeatureFlagActivity::class.java),
            DemoInfo("Demo 9: 完整 AllApps ↔ Workspace 转场",
                "§7.1 — 端到端演示：StateManager → PendingAnimation → APC → Choreographer",
                Demo9AllAppsTransitionActivity::class.java)
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = DemoAdapter(demos)
    }

    private data class DemoInfo(
        val title: String,
        val description: String,
        val activityClass: Class<*>
    )

    private class DemoAdapter(private val demos: List<DemoInfo>) :
        RecyclerView.Adapter<DemoAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(android.R.id.text1)
            val description: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = android.widget.LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 24, 32, 24)
            }
            view.addView(TextView(parent.context).apply {
                id = android.R.id.text1
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            view.addView(TextView(parent.context).apply {
                id = android.R.id.text2
                textSize = 12f
                setTextColor(0xFF666666.toInt())
                setPadding(0, 8, 0, 0)
            })
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val demo = demos[position]
            holder.title.text = demo.title
            holder.description.text = demo.description
            holder.itemView.setOnClickListener { v ->
                val ctx = v.context
                ctx.startActivity(Intent(ctx, demo.activityClass))
            }
        }

        override fun getItemCount() = demos.size
    }
}