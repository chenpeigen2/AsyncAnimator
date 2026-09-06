package com.asyncanimator.demo

import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.asyncanimator.demo.scene.LauncherStageView
import com.asyncanimator.demo.widget.DemoStyle
import com.asyncanimator.launcher.controller.AnimationController
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
 *
 * <p>可视化：顶部 Switch 切换 Impl / Default(no-op) 工厂；LauncherStageView 舞台上
 * 播放同一个 openApp —— Impl 走完整弹簧转场（图标→全屏，60fps），
 * Default(no-op) 同一调用被吞掉、窗口瞬间出现（外部驱动一步到 1f）。
 * 下方文本实时列出 9 个 RUS 配置项。
 */
class Demo8FeatureFlagActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 8: Feature Flag 工厂 + 远程灰度"
    override val docSection = "§6.10 + §6.11"

    private lateinit var stage: LauncherStageView
    private lateinit var implLabel: TextView
    private lateinit var configView: TextView

    override fun createContentView(): View {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ── 顶部：Feature 开关 ──
        val switchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val switchTitle = TextView(this).apply {
            text = "Feature 开关（Interruption）"
            textSize = 13f
            setTextColor(DemoStyle.INK)
            setTypeface(typeface, Typeface.BOLD)
        }
        val featureSwitch = SwitchCompat(this).apply {
            isChecked = true
            setOnCheckedChangeListener { _, checked -> onFeatureToggled(checked) }
        }
        switchRow.addView(switchTitle, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        switchRow.addView(featureSwitch)
        content.addView(switchRow)

        implLabel = TextView(this).apply {
            textSize = 11f
            text = currentImplText()
            setTextColor(currentImplColor())
        }
        content.addView(implLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = DemoStyle.dp(this@Demo8FeatureFlagActivity, 2f) })

        // 初始日志（沿用原有查询逻辑）
        val mgr = OplusAnimManager.getInstance()
        log("supportInterruption = ${mgr.supportInterruption()}")
        log("getAnimController() = ${mgr.getAnimController().javaClass.simpleName}")

        // ── 舞台：同一 openApp，两种实现的直观对比 ──
        stage = LauncherStageView(this).apply {
            onIconTapped = { playOpenApp() }
            onWindowTapped = {
                closeApp()
                banner = null
                log("点击窗口 → closeApp()（舞台弹簧收回图标位）")
            }
        }
        content.addView(stage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        DemoStyle.addButtonRow(content,
            DemoStyle.primaryButton("播放 openApp", this) {
                playOpenApp()
            },
            DemoStyle.outlineButton("查询当前实现", this) {
                val m = OplusAnimManager.getInstance()
                log("supportInterruption = ${m.supportInterruption()}")
                log("getAnimController() = ${m.getAnimController().javaClass.simpleName}")
            }
        )

        // ── 远程灰度配置（9 项实时列表）──
        content.addView(sectionCaption("远程灰度配置（AnimationFeatureHelper · 9 项）"))
        configView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(DemoStyle.INK)
            text = renderConfigs()
        }
        content.addView(configView)

        DemoStyle.addButtonRow(content,
            DemoStyle.outlineButton("模拟远程下发", this, DemoStyle.AMBER) {
                AnimationFeatureHelper.getInstance().simulateRemoteUpdate(
                    0, 0, 0, 0,
                    0.5f, 100
                )
                log("simulateRemoteUpdate 已下发")
                log("mAsyncEnable = 0, mIconBlurEnable = 0")
                log("→ 业务读到的字段立刻变化（volatile）")
                configView.text = renderConfigs()
            },
            DemoStyle.outlineButton("查看当前 9 个配置", this) {
                logConfigs()
            }
        )

        return content
    }

    // ── Feature 开关 ────────────────────────────────────────

    private fun onFeatureToggled(checked: Boolean) {
        OplusAnimManager.getInstance().setInterruptionEnabled(checked)
        val mgr = OplusAnimManager.getInstance()
        log("setInterruptionEnabled($checked)")
        log("supportInterruption = ${mgr.supportInterruption()}")
        log("getAnimController() = ${mgr.getAnimController().javaClass.simpleName}")
        log(if (checked) "→ AnimationController (Impl)" else "→ DefaultAnimationController (no-op)")
        implLabel.text = currentImplText()
        implLabel.setTextColor(currentImplColor())
        stage.resetScene()
        stage.banner = if (checked) "Impl 生效中 — 点图标或按钮播放" else "Default(no-op) 生效中 — 点图标或按钮播放"
    }

    private fun isImplActive(): Boolean =
        OplusAnimManager.getInstance().getAnimController() is AnimationController

    private fun currentImplText(): String =
        "当前生效实现: " + if (isImplActive()) "AnimationController (Impl)"
        else "DefaultAnimationController (no-op)"

    private fun currentImplColor(): Int =
        if (isImplActive()) DemoStyle.PRIMARY else DemoStyle.GRAY

    // ── 同一 openApp 的两种实现路径 ─────────────────────────

    private fun playOpenApp() {
        // 舞台复位，保证每次从桌面出发
        if (stage.windowProgress > 0.005f || stage.isAppOpen) stage.resetScene()

        val mgr = OplusAnimManager.getInstance()
        val implActive = mgr.getAnimController() is AnimationController
        log("播放 openApp → 当前实现 = ${mgr.getAnimController().javaClass.simpleName}")

        if (implActive) {
            // Impl：窗口 leash 走舞台弹簧，图标→全屏逐帧推进（真 60fps）
            stage.banner = "Impl — 弹簧转场"
            stage.openApp(0)
            log("Impl：窗口 leash 由舞台 SceneSpring 逐帧推进（图标 → 全屏）")
        } else {
            // Default(no-op)：同一调用立即返回，没有任何逐帧过程 —— 窗口瞬间出现。
            // 舞台侧用外部驱动一步把进度写到 1f（用完立刻恢复 internal 弹簧模式）。
            stage.banner = "Default(no-op) — 瞬间完成"
            stage.setExternalDrive(true)
            stage.driveWindowProgress(1f)
            stage.setExternalDrive(false)
            log("Default(no-op)：同一调用被吞掉，无动画过程 —— 窗口一步到全屏")
        }
    }

    // ── 远程灰度配置 ────────────────────────────────────────

    private fun renderConfigs(): String {
        val fh = AnimationFeatureHelper.getInstance()
        return buildString {
            append("mAsyncEnable         = ${fh.getAsyncEnable()}\n")
            append("mRTUnlockEnable      = ${fh.getRTUnlockEnable()}\n")
            append("mMultiAppBlockEnable = ${fh.getMultiAppBlockEnable()}\n")
            append("mIconBlurEnable      = ${fh.getIconBlurEnable()}\n")
            append("m1pxEnable           = ${fh.get1pxEnable()}\n")
            append("mInterruptThreshold  = ${fh.getInterruptThreshold()}\n")
            append("mLimtSize            = ${fh.getLimtSize()}\n")
            append("m1pxPkgDisableList   = ${fh.get1pxPkgDisableList().size} 项\n")
            append("m1pxCardDisableList  = ${fh.get1pxCardDisableList().size} 项")
        }
    }

    private fun logConfigs() {
        val fh = AnimationFeatureHelper.getInstance()
        log("mAsyncEnable = ${fh.getAsyncEnable()}")
        log("mRTUnlockEnable = ${fh.getRTUnlockEnable()}")
        log("mMultiAppBlockEnable = ${fh.getMultiAppBlockEnable()}")
        log("mIconBlurEnable = ${fh.getIconBlurEnable()}")
        log("m1pxEnable = ${fh.get1pxEnable()}")
        log("mInterruptThreshold = ${fh.getInterruptThreshold()}")
        log("mLimtSize = ${fh.getLimtSize()}")
    }

    private fun sectionCaption(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(DemoStyle.GRAY)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, DemoStyle.dp(this@Demo8FeatureFlagActivity, 10f), 0, 0)
    }
}
