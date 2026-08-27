package com.asyncanimator.demo

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.asyncanimator.launcher.feature.AnimationFeatureHelper
import com.asyncanimator.launcher.manager.OplusAnimManager

/**
 * Demo 8 — Feature Flag 工厂 + 远程灰度配置。
 *
 * <p>对应分析文档 §6.10 + §6.11。展示：
 * <ul>
 *   <li>OplusAnimManager.setInterruptionEnabled(false) → 全部退化到 no-op</li>
 *   <li>AnimationFeatureHelper.simulateRemoteUpdate() 模拟远程灰度下发</li>
 * </ul>
 */
class Demo8FeatureFlagActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 8: Feature Flag 工厂 + 远程灰度"
    override val docSection = "§6.10 + §6.11"

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(Button(this).apply {
            text = "当前 OplusAnimManager.supportInterruption"
            setOnClickListener {
                val mgr = OplusAnimManager.getInstance()
                log("supportInterruption = ${mgr.supportInterruption()}")
                log("getAnimController() = ${mgr.getAnimController().javaClass.simpleName}")
            }
        })

        root.addView(Button(this).apply {
            text = "关闭 feature（降级为 no-op）"
            setOnClickListener {
                OplusAnimManager.getInstance().setInterruptionEnabled(false)
                val mgr = OplusAnimManager.getInstance()
                log("supportInterruption = ${mgr.supportInterruption()}")
                log("getAnimController() = ${mgr.getAnimController().javaClass.simpleName}")
                log("→ DefaultAnimationController (no-op)")
            }
        })

        root.addView(Button(this).apply {
            text = "重新打开 feature"
            setOnClickListener {
                OplusAnimManager.getInstance().setInterruptionEnabled(true)
                val mgr = OplusAnimManager.getInstance()
                log("supportInterruption = ${mgr.supportInterruption()}")
                log("getAnimController() = ${mgr.getAnimController().javaClass.simpleName}")
                log("→ AnimationController (Impl)")
            }
        })

        // 远程灰度配置
        root.addView(TextView(this).apply { text = "远程灰度配置（AnimationFeatureHelper）"; textSize = 14f })

        root.addView(Button(this).apply {
            text = "查看当前 9 个配置"
            setOnClickListener {
                val fh = AnimationFeatureHelper.getInstance()
                log("mAsyncEnable = ${fh.getAsyncEnable()}")
                log("mRTUnlockEnable = ${fh.getRTUnlockEnable()}")
                log("mMultiAppBlockEnable = ${fh.getMultiAppBlockEnable()}")
                log("mIconBlurEnable = ${fh.getIconBlurEnable()}")
                log("m1pxEnable = ${fh.get1pxEnable()}")
                log("mInterruptThreshold = ${fh.getInterruptThreshold()}")
                log("mLimtSize = ${fh.getLimtSize()}")
            }
        })

        root.addView(Button(this).apply {
            text = "模拟远程下发：异步关 + 图标模糊关"
            setOnClickListener {
                AnimationFeatureHelper.getInstance().simulateRemoteUpdate(
                    async = 0, rtUnlock = 0, multiApp = 0, iconBlur = 0,
                    threshold = 0.5f, limtSize = 100
                )
                log("simulateRemoteUpdate 已下发")
                log("mAsyncEnable = 0, mIconBlurEnable = 0")
                log("→ 业务读到的字段立刻变化（volatile）")
            }
        })

        return root
    }
}