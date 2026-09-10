package com.asyncanimator.demo

import android.animation.Animator
import android.animation.AnimatorSet
import com.asyncanimator.control.AnimationController
import com.asyncanimator.control.RemoteAnimationFactory
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.asyncanimator.anim.CustomRectFSpringAnim
import com.asyncanimator.anim.MultiAnimatorSet
import com.asyncanimator.playback.NullableAnimatorListenerAdapter
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.asyncanimator.demo.scene.LauncherStageView
import com.asyncanimator.demo.widget.CurvePlotView
import com.asyncanimator.demo.widget.DemoStyle

/**
 * Demo 9 — real library four-track aggregation with a portable Canvas window adapter.
 * Main fade, launcher.anim numeric progress, AndroidX View spring and the rect Animator
 * driver are independently started/ended by MultiAnimatorSet. Rect geometry is illustrative,
 * not the OEM six-axis spring/SurfaceControl implementation. Recents buttons remain stage demos.
 */
class Demo9AllAppsTransitionActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 9: 四通道转场聚合"
    override val docSection = "§7.1"

    private lateinit var stage: LauncherStageView
    private lateinit var plot: CurvePlotView
    private var laneWindow = 0
    private var laneRecents = 1
    private var laneAsync = 2
    private var transition: MultiAnimatorSet? = null
    @Volatile private var asyncProgress = 0f
    @Volatile private var transitionGeneration = 0
    private var nextAnimationId = 0
    private val launchController = AnimationController()
    private var launchRegistered = false
    private val launchFactory = object : RemoteAnimationFactory {
        override fun createAnimation() = AnimatorSet() // lifecycle identity; actual animation is group
        override fun onAnimationFinished() {}
    }

    private fun acceptTouch(): Boolean {
        if (!launchController.forbidTouch()) return true
        log("输入被 Controller.forbidTouch 拦截：开窗 600ms 定时保护，主线程忙时可能延后；结束可提前释放")
        return false
    }

    private fun finishLaunch() {
        if (launchRegistered) {
            launchRegistered = false
            launchController.appLaunchAnimStartOrEnd(true, launchFactory, emptyArray())
        }
    }

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ── 仿桌面舞台（主体）──────────────────────────────
        stage = LauncherStageView(this)
        root.addView(stage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        stage.banner = "WORKSPACE · 点图标打开应用"
        stage.onIconTapped = { i -> openFromIcon(i) }
        stage.onWindowTapped = { closeWindow("点击窗口") }
        stage.onFrame = { s ->
            plot.sample(laneWindow, s.windowProgress)
            plot.sample(laneRecents, s.recentsProgress)
        }

        // ── 转场进度曲线 ─────────────────────────────────
        root.addView(TextView(this).apply {
            textSize = 11f
            setTextColor(DemoStyle.GRAY)
            text = "靛蓝 = 窗口驱动　青 = recents 示意　橙 = 后台数值进度"
            setPadding(0, DemoStyle.dp(this@Demo9AllAppsTransitionActivity, 6f), 0, 0)
        })
        plot = CurvePlotView(this)
        laneWindow = plot.addLane(DemoStyle.PRIMARY)
        laneRecents = plot.addLane(DemoStyle.ACCENT)
        laneAsync = plot.addLane(0xffff9800.toInt())
        root.addView(plot, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── 按钮 ────────────────────────────────────────
        DemoStyle.addButtonRow(root,
            DemoStyle.primaryButton("打开应用", this) { openFromIcon(0) },
            DemoStyle.outlineButton("上滑关闭", this) { closeWindow("上滑手势") })
        DemoStyle.addButtonRow(root,
            DemoStyle.outlineButton("上滑进 recents", this, DemoStyle.ACCENT) { goRecents() },
            DemoStyle.outlineButton("收回桌面", this, DemoStyle.GRAY) { backHome() })

        log("真实聚合：MultiAnimatorSet → main / launcher.anim / View spring / rect driver")
        log("窗口为 Canvas + Animator adapter；不是 OEM Rect 弹簧或系统窗口事务")
        return root
    }

    private fun cancelTransition() {
        transitionGeneration++
        transition?.destroy()
        finishLaunch()
        transition = null
        stage.alpha = 1f
        stage.translationY = 0f
    }

    private fun openFromIcon(i: Int) {
        if (!acceptTouch()) return
        if (stage.isAppOpen && stage.windowProgress >= 0.999f) return
        cancelTransition()
        stage.setExternalDrive(true)
        stage.openApp(i)
        playTransition(1f, CustomRectFSpringAnim.AnimType.OPEN_FROM_HOME)
    }

    private fun closeWindow(from: String, fromTouch: Boolean = true) {
        if (fromTouch && !acceptTouch()) return
        if (!stage.isAppOpen && stage.windowProgress <= 0.005f) return
        cancelTransition()
        stage.setExternalDrive(true)
        stage.closeApp()
        log("$from → 取消旧聚合并从当前窗口进度收回")
        playTransition(0f, CustomRectFSpringAnim.AnimType.REMOTE_CLOSE_TO_HOME)
    }

    private fun playTransition(target: Float, type: CustomRectFSpringAnim.AnimType) {
        val generation = transitionGeneration
        val group = MultiAnimatorSet(type)
        transition = group
        group.animationId = ++nextAnimationId
        stage.banner = "$type · 四通道运行中"
        asyncProgress = 0f
        group.play(ValueAnimator.ofFloat(0.88f, 1f).apply {
            duration = 180
            addUpdateListener { stage.alpha = it.animatedValue as Float }
        })
        group.play(true, ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            // No View/Canvas access from launcher.anim. The UI samples this volatile scalar.
            addUpdateListener {
                if (transitionGeneration == generation) asyncProgress = it.animatedValue as Float
            }
        })
        stage.translationY = 12f
        group.play(SpringAnimation(stage, SpringAnimation.TRANSLATION_Y).apply {
            spring = SpringForce(0f).setStiffness(500f).setDampingRatio(0.7f)
            addUpdateListener { _, _, _ -> plot.sample(laneAsync, asyncProgress) }
        })
        val rectDriver = ValueAnimator.ofFloat(stage.windowProgress, target).apply {
            duration = 400
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val progress = it.animatedValue as Float
                stage.driveWindowProgress(progress)
                plot.sample(laneWindow, progress)
                plot.sample(laneAsync, asyncProgress)
            }
        }
        group.play(CustomRectFSpringAnim(type, rectDriver))
        group.addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationStart(animator: Animator) {
                log("#${group.animationId} start：四条独立通道，不是 APC 单主时钟")
            }
            override fun onAnimationCancel(animator: Animator) { log("#${group.animationId} cancel 请求") }
        })
        group.setViewStateResetRunnable { id ->
            if (transition === group) {
                finishLaunch()
                plot.sample(laneAsync, asyncProgress)
                stage.banner = "#$id 四通道全部结束"
                log("#$id maybeOnEnd：main / async / spring / rect 均完成")
            }
        }
        if (target == 1f) {
            launchRegistered = true
            launchController.appLaunchAnimStartOrEnd(false, launchFactory, emptyArray())
        }
        try { group.start() }
        catch (error: Throwable) {
            cancelTransition()
            throw error
        }
    }

    /** 上滑手势进 recents：窗口从全屏插值到卡片位。 */
    private fun goRecents() {
        if (!acceptTouch()) return
        cancelTransition()
        stage.setExternalDrive(false)
        log("RECENTS 按钮保留舞台示意，不作为 MultiAnimatorSet 集成验证")
        stage.banner = "上滑手势 · 进入 RECENTS"
        stage.swipeToRecents(1f)
        log("上滑手势 → recentsProgress 0→1：窗口从全屏插值到 recents 卡片位")
    }

    /** 收回桌面。 */
    private fun backHome() {
        if (!acceptTouch()) return
        cancelTransition()
        stage.setExternalDrive(false)
        stage.banner = "WORKSPACE · 点图标打开应用"
        stage.exitRecents()
        log("收回桌面：recents 弹簧回 0，窗口缩回图标")
    }

    override fun onCleanup() {
        cancelTransition()
        launchController.destroy()
        stage.onFrame = null
        stage.onIconTapped = null
        stage.onWindowTapped = null
        stage.setExternalDrive(false)
    }

    @Deprecated("demo 用旧回调拦截返回键")
    override fun onBackPressed() {
        if (stage.isAppOpen) closeWindow("onBackPressed", fromTouch = false)
        else @Suppress("DEPRECATION") super.onBackPressed()
    }
}
