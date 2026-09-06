package com.asyncanimator.demo

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.asyncanimator.core.anim.AnimatorSet
import com.asyncanimator.core.anim.ValueAnimator
import com.asyncanimator.launcher.playback.AnimatorPlaybackController
import com.asyncanimator.util.FloatProperty

/**
 * Demo 9 — 完整 AllApps ↔ Workspace 转场（端到端）。
 *
 * <p>对应分析文档 §7.1。端到端演示 StateManager → PendingAnimation → APC → Choreographer
 * 完整调用链（简化版）。
 */
class Demo9AllAppsTransitionActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 9: 完整 AllApps ↔ Workspace 转场"
    override val docSection = "§7.1"

    private var state = "WORKSPACE"
    private var controller: AnimatorPlaybackController? = null

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val stateLabel = TextView(this).apply { textSize = 16f; text = "状态: $state" }
        root.addView(stateLabel)

        val buildButton = Button(this).apply {
            text = "1. 构建转场 (PendingAnimation + APC)"
            setOnClickListener {
                val holders = arrayListOf<AnimatorPlaybackController.Holder>()

                // AllApps 容器高度动画
                val vaHeight = ValueAnimator.ofFloat(0f, 1f).apply { setDuration(250) }
                holders.add(AnimatorPlaybackController.Holder(vaHeight, 250f))

                // Workspace alpha 动画
                val vaAlpha = ValueAnimator.ofFloat(0f, 1f).apply { setDuration(200) }
                holders.add(AnimatorPlaybackController.Holder(vaAlpha, 250f))

                // Icon 缩放动画
                val vaScale = ValueAnimator.ofFloat(0f, 1f).apply { setDuration(180) }
                holders.add(AnimatorPlaybackController.Holder(vaScale, 250f))

                val animSet = AnimatorSet().apply { playTogether(vaHeight, vaAlpha, vaScale) }
                controller = AnimatorPlaybackController(animSet, 250L, holders)
                log("PendingAnimation-like: addFloat x3 + APC")
                log("3 个 Holder: 容器高度/alpha/缩放")
                log("主时钟 mAnimationPlayer = LINEAR 0..1, duration 250ms")
            }
        }
        root.addView(buildButton)

        root.addView(Button(this).apply {
            text = "2. Workspace → AllApps (start)"
            setOnClickListener {
                controller?.start()
                state = "ALL_APPS"
                stateLabel.text = "状态: $state"
                log("playbackController.start()")
                log("→ APC.onAnimationUpdate 每帧 → setPlayFraction(f)")
                log("→ 3 个 Holder.setProgress(f) → 子 anim.setCurrentFraction")
                log("→ onAnimationEnd → OnAnimationEndDispatcher.onAnimationSuccess")
            }
        })

        root.addView(Button(this).apply {
            text = "3. AllApps → Workspace (reverse)"
            setOnClickListener {
                controller?.reverse()
                state = "WORKSPACE"
                stateLabel.text = "状态: $state"
                log("playbackController.reverse()")
                log("→ mAnimationPlayer.setFloatValues(currentFraction, 0f)")
                log("→ 所有 Holder 同步反向推进")
            }
        })

        root.addView(Button(this).apply {
            text = "4. setPlayFraction(0.3)（手势拖动）"
            setOnClickListener {
                controller?.setPlayFraction(0.3f)
                log("playbackController.setPlayFraction(0.3)")
                log("→ 立即广播 f=0.3 给所有 Holder（不通过 start）")
            }
        })

        return root
    }
}