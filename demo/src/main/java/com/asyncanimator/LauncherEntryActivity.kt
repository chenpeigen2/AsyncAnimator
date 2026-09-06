package com.asyncanimator.demo

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.asyncanimator.demo.databinding.ActivityEntryBinding
import com.asyncanimator.demo.widget.DemoStyle

private const val BADGE_ID = 0x7001
private const val TITLE_ID = 0x7002
private const val DESC_ID = 0x7003
private const val TAG_ID = 0x7004

/**
 * LauncherEntryActivity — Demo 入口列表（卡片式）。
 *
 * <p>每个条目：左侧编号圆徽（主色渐变）+ 标题 + 描述 + 章节胶囊 tag。
 */
class LauncherEntryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEntryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEntryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setBackgroundColor(DemoStyle.BG_PAGE)

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
                Demo9AllAppsTransitionActivity::class.java),
            DemoInfo("Demo 10: 独立动画线程",
                "launcher.anim — 主线程加压时独立线程动画不掉帧",
                Demo10IndependentThreadActivity::class.java)
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = DemoAdapter(demos)
        binding.recyclerView.setPadding(
            DemoStyle.dp(this, 10f), DemoStyle.dp(this, 6f),
            DemoStyle.dp(this, 10f), DemoStyle.dp(this, 10f))
        binding.recyclerView.clipToPadding = false
    }

    private data class DemoInfo(
        val title: String,
        val description: String,
        val activityClass: Class<*>
    )

    private class DemoAdapter(private val demos: List<DemoInfo>) :
        RecyclerView.Adapter<DemoAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val badge: TextView = view.findViewById(BADGE_ID)
            val title: TextView = view.findViewById(TITLE_ID)
            val description: TextView = view.findViewById(DESC_ID)
            val tag: TextView = view.findViewById(TAG_ID)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val ctx = parent.context
            fun dp(d: Float) = DemoStyle.dp(ctx, d)

            // 编号圆徽（主色渐变）
            val badge = TextView(ctx).apply {
                id = BADGE_ID
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(DemoStyle.PRIMARY, DemoStyle.ACCENT)
                ).apply { shape = GradientDrawable.OVAL }
                layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    marginEnd = dp(12f)
                }
            }

            val title = TextView(ctx).apply {
                id = TITLE_ID
                textSize = 15f
                setTextColor(DemoStyle.INK)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val description = TextView(ctx).apply {
                id = DESC_ID
                textSize = 11f
                setTextColor(DemoStyle.GRAY)
                setPadding(0, dp(2f), 0, 0)
            }
            val tag = DemoStyle.pill("", ctx).apply { id = TAG_ID }

            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(description)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(badge)
                addView(textCol)
                addView(tag, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER_VERTICAL })
            }

            // 卡片容器：白底圆角 + 轻阴影 + 水波纹
            val card = FrameLayout(ctx).apply {
                val outValue = TypedValue()
                ctx.theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, outValue, true)
                foreground = ctx.getDrawable(outValue.resourceId)
                background = DemoStyle.roundRect(DemoStyle.BG_CARD, 14f, ctx)
                elevation = dp(2f).toFloat()
                isClickable = true
                addView(row, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp(14f), dp(14f), dp(14f), dp(14f))
                })
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(2f), dp(6f), dp(2f), dp(6f)) }
            }
            return ViewHolder(card)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val demo = demos[position]
            holder.badge.text = "${position + 1}"
            holder.title.text = demo.title
            holder.description.text = demo.description
            holder.tag.text = demo.description.substringBefore(" ").ifBlank { "§" }
            holder.itemView.setOnClickListener { v ->
                val ctx = v.context
                ctx.startActivity(Intent(ctx, demo.activityClass))
            }
        }

        override fun getItemCount() = demos.size
    }
}
