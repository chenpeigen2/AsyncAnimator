package com.asyncanimator.demo

import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.asyncanimator.launcher.async.AsyncValueAnimator
import com.asyncanimator.launcher.async.Executors
import com.asyncanimator.launcher.pending.NullableAnimatorListener
import com.asyncanimator.launcher.pending.NullableAnimatorListenerAdapter

/**
 * Demo 3 — Async 跨 Looper 安全。
 *
 * <p>对应分析文档 §6.3。从 worker 线程调用 AsyncValueAnimator.start()，
 * 验证 start 已经被 marshal 到主线程，listener 也在主线程 fire。
 */
class Demo3AsyncCrossThreadActivity : DemoBaseActivity() {

    override val demoTitle = "Demo 3: AsyncValueAnimator 跨 Looper"
    override val docSection = "§6.3"

    private lateinit var statusLabel: TextView

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        statusLabel = TextView(this).apply {
            textSize = 14f
            text = "状态：未启动\n\n"
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            setPadding(20, 20, 20, 20)
        }
        root.addView(statusLabel)

        root.addView(Button(this).apply {
            text = "从 worker 线程调 start()"
            setOnClickListener { startFromWorker() }
        })

        root.addView(Button(this).apply {
            text = "从主线程调 start()"
            setOnClickListener { startFromMain() }
        })

        return root
    }

    private fun startFromMain() {
        log("=== 主线程 start() ===")
        log("主线程 = ${Thread.currentThread().name}")
        val anim = AsyncValueAnimator.ofFloat(false, 0f, 1f) as AsyncValueAnimator
        anim.setExecutor(Executors.MAIN_EXECUTOR)
        anim.duration = 500
        anim.getAsyncAnimCallbacks().addListener(object : NullableAnimatorListenerAdapter() {
            override fun onAnimationStart(animator: com.asyncanimator.core.anim.Animator?) {
                log("onAnimationStart on ${Thread.currentThread().name}  (main=${Thread.currentThread() == Looper.getMainLooper().thread})")
            }
            override fun onAnimationEnd(animator: com.asyncanimator.core.anim.Animator?) {
                log("onAnimationEnd on ${Thread.currentThread().name}")
            }
        })
        anim.start()
        log("start() 立即返回；实际 frame 在主线程 tick")
    }

    private fun startFromWorker() {
        log("=== worker 线程 start() ===")
        Thread {
            log("worker thread = ${Thread.currentThread().name}")
            val anim = AsyncValueAnimator.ofFloat(false, 0f, 1f) as AsyncValueAnimator
            anim.setExecutor(Executors.MAIN_EXECUTOR)
            anim.duration = 500
            anim.getAsyncAnimCallbacks().addListener(object : NullableAnimatorListenerAdapter() {
                override fun onAnimationStart(animator: com.asyncanimator.core.anim.Animator?) {
                    log("onAnimationStart on ${Thread.currentThread().name}")
                }
                override fun onAnimationEnd(animator: com.asyncanimator.core.anim.Animator?) {
                    log("onAnimationEnd on ${Thread.currentThread().name}")
                }
            })
            anim.start()
            log("worker: start() 已 marshal 到主线程")
        }.start()
    }

    object Looper {
        fun getMainLooper(): android.os.Looper = android.os.Looper.getMainLooper()
    }
}