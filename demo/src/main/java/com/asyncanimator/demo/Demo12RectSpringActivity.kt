package com.asyncanimator.demo

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.asyncanimator.anim.CustomRectFSpringAnim
import com.asyncanimator.anim.RectSpringConfig
import com.asyncanimator.anim.RectSpringDriver
import com.asyncanimator.anim.RectSpringFrame
import com.asyncanimator.demo.widget.DemoStyle
import com.asyncanimator.thread.Executors
import com.asyncanimator.thread.LooperExecutor

/** Actual six-axis library geometry. Canvas updates always run on main, including anim-thread mode. */
class Demo12RectSpringActivity : DemoBaseActivity() {
    override val demoTitle = "Demo 12: 六轴矩形弹簧"
    override val docSection = "RectSpringDriver / 公开 AndroidX scheduler / 非系统窗口"
    private lateinit var stage: RectStage
    private lateinit var status: TextView
    private var handle: CustomRectFSpringAnim? = null
    private var driver: RectSpringDriver? = null
    private var owner: LooperExecutor = Executors.MAIN_EXECUTOR
    private var asyncRun = false
    private var asyncNext = false
    private var tracking = RectSpringConfig.Tracking.CENTER
    private var multiplier = 1f
    private var target = RectF()
    @Volatile private var generation = 0L

    override fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { setTextColor(DemoStyle.PRIMARY); textSize = 12f }
        root.addView(status)
        stage = RectStage()
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        fun row(vararg actions: Pair<String, () -> Unit>) {
            val line = LinearLayout(this)
            actions.forEach { (text, action) ->
                line.addView(Button(this).apply {
                    this.text = text; textSize = 11f; setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            root.addView(line)
        }
        row("开始六轴" to { startNew() }, "反向打开" to {
            handle?.reverseToOpen(fullRect(), 24f) { log("reverse 已由 owner 修改目标，回执在主线程") }
            target = fullRect()
        }, "中途改目标" to {
            target = RectF(30f, 70f, 150f, 250f)
            val destination = RectF(target)
            onOwner { it.updateEndTargetRectF(destination, 8f) }
        })
        row("16ms 预测接续" to { continueNextFrame() }, "cancel" to { handle?.cancel() },
            "skipToEnd" to { handle?.skipToEnd() })
        row("切换线程" to { asyncNext = !asyncNext; showOptions() },
            "切换锚点" to {
                tracking = RectSpringConfig.Tracking.entries[(tracking.ordinal + 1) % 3]; showOptions()
            }, "额外倍率 1/2" to { multiplier = if (multiplier == 1f) 2f else 1f; showOptions() })
        showOptions()
        return root
    }

    private fun showOptions() {
        status.text = "下次启动：${if (asyncNext) "launcher.anim" else "main"} / $tracking / 倍率 $multiplier\n" +
            "X/Y/尺寸/高宽比/圆角/透明度独立弹簧；alpha 延迟 180ms"
    }
    private fun fullRect() = RectF(24f, 36f, (stage.width - 24f).coerceAtLeast(260f),
        (stage.height - 36f).coerceAtLeast(360f))

    private fun publish(frame: RectSpringFrame, token: Long) {
        val thread = Thread.currentThread().name
        Executors.MAIN_EXECUTOR.execute {
            if (generation == token) {
                stage.frame = frame
                status.text = "计算线程 $thread → Canvas 主线程 / p=%.2f\nsize=%.1f ratio=%.3f radius=%.1f alpha=%.2f vX=%.1f"
                    .format(frame.progress, frame.values.size, frame.values.ratio, frame.values.radius,
                        frame.values.alpha, frame.velocities.centerX)
            }
        }
    }

    private fun startNew() {
        if (stage.width <= 0 || stage.height <= 0) return
        val token = ++generation
        handle?.dispose()
        target = RectF(stage.width - 110f, stage.height - 115f, stage.width - 30f, stage.height - 35f)
        val d = RectSpringDriver(fullRect(), target,
            config = RectSpringConfig(tracking = tracking, durationMultiplier = multiplier,
                centerX = RectSpringConfig.Spring(220f, 0.75f),
                size = RectSpringConfig.Spring(260f, 0.85f), alphaStartDelayMillis = 180),
            startRadius = 24f, targetRadius = 12f, startAlpha = 1f, targetAlpha = 0.3f,
            onUpdate = { publish(it, token) })
        install(d, token, asyncNext, CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME)
    }

    private fun install(d: RectSpringDriver, token: Long, async: Boolean, type: CustomRectFSpringAnim.AnimType) {
        driver = d
        asyncRun = async
        owner = if (async) Executors.ANIM_CONTROL_EXECUTOR else Executors.MAIN_EXECUTOR
        handle = CustomRectFSpringAnim(type, d).also { animation ->
            animation.setAsyncStart(async)
            animation.addListener(object : CustomRectFSpringAnim.Listener {
                override fun onEnd(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) {
                    if (generation == token) log("logical end / cancelled=${event.cancelled}")
                }
                override fun onActualEnd(animation: CustomRectFSpringAnim, event: CustomRectFSpringAnim.Event) {
                    if (generation == token) log("actual end：六轴结束，帧订阅和 AndroidX 注册已释放")
                }
            })
            animation.start()
        }
    }

    private fun onOwner(action: (RectSpringDriver) -> Unit) {
        val d = driver ?: return
        val token = generation
        owner.execute {
            if (generation == token) runCatching { action(d) }
                .onFailure { log("操作未执行：${it.message}") }
        }
    }

    private fun continueNextFrame() {
        val old = driver ?: return
        if (handle?.isRunning != true) { log("请在动画运行时接续"); return }
        val token = ++generation
        val destination = RectF(target)
        val async = asyncRun
        val type = if (handle?.isReverseToOpen == true) CustomRectFSpringAnim.AnimType.REVERSE_TO_OPEN
            else CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME
        owner.execute {
            if (generation != token) return@execute
            runCatching {
                val predicted = old.copyNextAnimState(16)
                val successor = old.createContinuation(destination, 16) { publish(it, token) }
                Executors.MAIN_EXECUTOR.execute main@{
                    if (generation != token) { successor.dispose(); return@main }
                    handle?.dispose() // queued on the old owner before the successor's start
                    stage.preview = predicted
                    install(successor, token, async, type)
                    log("按 16ms 假设预测位置+六轴速度接续；虚线为预测位置，不是重新从零速度开始")
                }
            }.onFailure { log("接续未执行（可能已经结束）：${it.message}") }
        }
    }

    override fun onCleanup() {
        generation++
        handle?.dispose()
        handle = null; driver = null
        stage.frame = null; stage.preview = null
    }

    private inner class RectStage : View(this@Demo12RectSpringActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var frame: RectSpringFrame? = null
            set(value) { field = value; invalidate() }
        var preview: RectSpringFrame? = null
            set(value) { field = value; invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(DemoStyle.BG_PAGE)
            frame?.let {
                paint.style = Paint.Style.FILL; paint.color = DemoStyle.PRIMARY
                paint.alpha = (it.values.alpha * 255).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(it.rect, it.values.radius, it.values.radius, paint)
            }
            preview?.let {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = DemoStyle.ACCENT; paint.alpha = 180
                paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 5f), 0f)
                canvas.drawRect(it.rect, paint); paint.pathEffect = null
            }
        }
    }
}
