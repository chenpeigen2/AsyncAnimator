package com.asyncanimator.demo

import android.animation.Animator
import android.view.View
import android.widget.LinearLayout
import com.asyncanimator.demo.scene.LauncherStageView
import com.asyncanimator.demo.widget.DemoStyle
import com.asyncanimator.demo.widget.ThreadLaneView
import com.asyncanimator.launcher.async.AsyncValueAnimator
import com.asyncanimator.launcher.async.Executors
import com.asyncanimator.launcher.pending.NullableAnimatorListenerAdapter

/**
 * Demo 3 — Async 跨 Looper 安全。
 *
 * <p>对应分析文档 §6.3。从 worker 线程调用 AsyncValueAnimator.start()，
 * 验证 start 已经被 marshal 到主线程，listener 也在主线程 fire。
 *
 * <p>可视化：LauncherStageView 桌面舞台 + ThreadLaneView 两条泳道（worker / main）。
 * worker 线程调 start() 时，泳道上先演示事件打包 marshal 飞向 main（箭头 1s），
 * 约 800ms 后主线程真正落地执行 —— 舞台图标开屏（openApp）。
 * marshal 先演、场景后动，因果关系直观。同时保留真实的 lib animator，
 * 其 onAnimationStart/End 的线程名 log 即 marshal 成功的证据。
 */
class Demo3AsyncCrossThreadActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 3: AsyncValueAnimator 跨 Looper"
    override val docSection = "§6.3"

    private lateinit var stage: LauncherStageView
    private lateinit var lanes: ThreadLaneView

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 桌面舞台（主体）
        stage = LauncherStageView(this)
        root.addView(stage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // 线程泳道：worker=灰，main=蓝
        lanes = ThreadLaneView(this).apply {
            setLaneNames(
                listOf("worker", "main"),
                listOf(DemoStyle.GRAY, DemoStyle.MAIN_THREAD))
        }
        root.addView(lanes, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        // 舞台图标点击 = 主线程直接 openApp
        stage.onIconTapped = { i ->
            log("舞台图标 #$i 被点击（${Thread.currentThread().name}）→ openApp($i)")
            lanes.event(1)
            stage.openApp(i)
        }

        DemoStyle.addButtonRow(root,
            DemoStyle.primaryButton("从 worker 线程调 start()", this) { startFromWorker() },
            DemoStyle.outlineButton("从主线程调 start()", this) { startFromMain() })

        return root
    }

    /** 真实的 lib animator：listener 的线程名 log 证明 start 已 marshal、回调落在主线程。 */
    private fun buildAnim(): AsyncValueAnimator {
        val anim = AsyncValueAnimator()
        anim.setFloatValues(0f, 1f)
        anim.setExecutor(Executors.MAIN_EXECUTOR)
        anim.duration = 500
        anim.getAsyncAnimCallbacks().addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationStart(animator: Animator) {
                log("onAnimationStart on ${Thread.currentThread().name}  (main=${Thread.currentThread() == android.os.Looper.getMainLooper().thread})")
                runOnUiThread {
                    lanes.event(1)
                    lanes.setLaneBusy(1, true)
                }
            }
            override fun onAnimationEnd(animator: Animator) {
                log("onAnimationEnd on ${Thread.currentThread().name}  —— listener 始终回主线程 fire")
                runOnUiThread {
                    lanes.event(1)
                    lanes.setLaneBusy(1, false)
                }
            }
        })
        return anim
    }

    private fun startFromMain() {
        log("=== 主线程 start() ===")
        log("主线程 = ${Thread.currentThread().name}，无需 marshal，直接执行")
        lanes.event(1)
        buildAnim().start()
        stage.banner = "主线程直接执行 → openApp"
        stage.openApp(0)
        stage.postDelayed({ stage.banner = null }, 1500)
        log("start() 立即返回；实际 frame 在主线程 tick")
    }

    private fun startFromWorker() {
        log("=== worker 线程 start() ===")
        Thread {
            log("worker thread = ${Thread.currentThread().name}")
            // worker 泳道打点 + start() marshal 箭头飞向 main
            runOnUiThread { lanes.marshal(0, 1, "start()") }
            buildAnim().start()
            log("worker: start() 立即返回，已 marshal 到主线程")
            // marshal 先演（箭头约 1s），主线程随后真正落地 → 图标开屏
            stage.postDelayed({
                stage.banner = "main 线程执行 start() → openApp"
                stage.openApp(0)
                log("main: start() 落地执行，openApp 开屏（${Thread.currentThread().name}）")
                stage.postDelayed({ stage.banner = null }, 1500)
            }, 800)
        }.start()
    }
}
