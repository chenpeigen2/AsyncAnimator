package com.asyncanimator.demo

import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.asyncanimator.launcher.controller.AnimationController
import com.asyncanimator.launcher.controller.AnimationState
import com.asyncanimator.launcher.controller.TaskStateChangeTimeOutListener
import com.asyncanimator.launcher.async.CustomRectFSpringAnim

/**
 * Demo 6 — 状态机 + 3 种超时监听器。
 *
 * <p>对应分析文档 §6.8。展示 11 个 AnimationState 的转换和
 * 三种独立超时 listener 的注册/触发逻辑。
 */
class Demo6StateMachineActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 6: 状态机 + 3 种超时"
    override val docSection = "§6.8"

    private lateinit var stateLabel: TextView
    private lateinit var listenerLabel: TextView
    private val controller = AnimationController()

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        stateLabel = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            text = "当前状态: ${controller.getAnimState()}"
            setBackgroundColor(Color.parseColor("#E3F2FD"))
            setPadding(20, 20, 20, 20)
        }
        root.addView(stateLabel)

        listenerLabel = TextView(this).apply {
            textSize = 12f
            text = buildListenerStatus()
            setPadding(20, 10, 20, 10)
        }
        root.addView(listenerLabel)

        // 状态触发按钮
        root.addView(TextView(this).apply {
            text = "触发 addRecentsAnim（NONE → CLOSE）"
            textSize = 14f
        })
        root.addView(Button(this).apply {
            text = "添加 Recents 动画"
            setOnClickListener {
                controller.addRecentsAnim(
                    CustomRectFSpringAnim(CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME),
                    null, arrayOf()
                )
                refreshUI()
            }
        })

        root.addView(Button(this).apply {
            text = "appLaunchAnimStartOrEnd(isEnd=false)"
            setOnClickListener {
                controller.appLaunchAnimStartOrEnd(
                    false, null, arrayOf()
                )
                refreshUI()
            }
        })

        root.addView(Button(this).apply {
            text = "reset()"
            setOnClickListener {
                controller.reset()
                refreshUI()
            }
        })

        // Listener 注册
        root.addView(TextView(this).apply { text = "三种超时 listener 注册"; textSize = 14f })

        root.addView(Button(this).apply {
            text = "注册 SpecialSceneExit (1500ms)"
            setOnClickListener {
                controller.registerSpecialSceneExitTimeOutListener(1500L)
                log("mSpecialSceneExitTimeOutListener 已注册")
                refreshUI()
            }
        })
        root.addView(Button(this).apply {
            text = "注册 TransitionFinish (1500ms)"
            setOnClickListener {
                controller.registerTransitionFinishTimeOutListener(1500L)
                log("mTransitionFinishTimeOutListener 已注册")
                refreshUI()
            }
        })
        root.addView(Button(this).apply {
            text = "注册 OverviewContinuation (100ms)"
            setOnClickListener {
                controller.registerOverviewContinuationTimeOutListener(100L)
                log("mOverviewContinuationTimeOutListener 已注册")
                refreshUI()
            }
        })

        return root
    }

    private fun buildListenerStatus(): String {
        val sb = StringBuilder()
        sb.append("mSpecialSceneExit: ").append(if (controller.getSpecialSceneExitTimeOutListener() != null) "✓" else "✗").append("\n")
        sb.append("mTransitionFinish: ").append(if (controller.getTransitionFinishTimeOutListener() != null) "✓" else "✗").append("\n")
        sb.append("mOverviewContinuation: ").append(if (controller.getOverviewContinuationTimeOutListener() != null) "✓" else "✗")
        return sb.toString()
    }

    private fun refreshUI() {
        stateLabel.text = "当前状态: ${controller.getAnimState()}"
        listenerLabel.text = buildListenerStatus()
        log("状态机更新: ${controller.getAnimState()}")
    }
}