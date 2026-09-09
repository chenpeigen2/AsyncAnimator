package com.asyncanimator.demo.scene

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.asyncanimator.demo.widget.DemoStyle
import kotlin.math.min

/**
 * LauncherStageView — 仿 ColorOS 桌面舞台。
 *
 * <p>所有场景动画由内置 SceneClock（Choreographer）+ SceneSpring（解析解）驱动，60fps 真动。
 * 也支持外部驱动模式（Demo 10：进度由 launcher.anim 线程逐帧写入）。
 *
 * <p>场景原语：
 * <ul>
 *   <li>{@link #openApp} / {@link #closeApp} —— 图标→全屏窗口的弹簧转场</li>
 *   <li>{@link #bounceIcons} —— 图标按压回弹（同步 / 独立时钟对照）</li>
 *   <li>{@link #flyIcon} —— 图标沿 直线/过冲/减速 路径飞行（带拖尾）</li>
 *   <li>{@link #swipeToRecents} —— 虚拟手指上滑，窗口进 recents 卡片位（支持断点续行）</li>
 * </ul>
 */
class LauncherStageView(context: Context) : View(context) {

    // ── 状态 ─────────────────────────────────────────────
    private class IconState(val spec: SceneSprites.IconSpec) {
        var cx = 0f; var cy = 0f; var size = 0f
        val scale = SceneSpring(420f, 0.55f).apply { snapTo(1f) }
        var alpha = 1f
        var hidden = false
    }

    enum class FlyCurve { LINEAR, OVERSHOOT, DECEL }
    private class FlyAnim(val fromIdx: Int, val toIdx: Int, val curve: FlyCurve,
                          val color: Int) {
        var t = 0f
        val trail = ArrayList<FloatArray>()
        var done = false
    }

    private val icons = SceneSprites.DEFAULT_ICONS.map { IconState(it) }
    private val dockStart = 8

    // 窗口 leash
    private val windowP = SceneSpring(300f, 0.87f).apply { snapTo(0f) }
    private var windowSrcIcon = 0
    private var windowTitle = ""
    private var externalDrive = false

    // 壁纸
    private val wallpaperZoom = SceneSpring(260f, 0.9f).apply { snapTo(1f) }

    // recents
    private val recentsP = SceneSpring(280f, 0.9f).apply { snapTo(0f) }

    // 手势
    private var gestureActive = false
    private var gestureY = 0f
    private var gestureX = 0f
    private var gestureAnim: ((Float) -> Boolean)? = null  // dt -> finished?

    // 飞行
    private val flyAnims = ArrayList<FlyAnim>()

    // 其他
    var banner: String? = null
        set(v) { field = v; invalidate() }
    var onFrame: ((LauncherStageView) -> Unit)? = null
    var onIconTapped: ((Int) -> Unit)? = null
    var onWindowTapped: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconRect = RectF()
    private val fullRect = RectF()
    private val windowRect = RectF()
    private val cardRect = RectF()

    private val clock = SceneClock { dt -> tick(dt) }

    // ── 布局 ─────────────────────────────────────────────
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cols = 4
        val gridRows = 2
        val cellW = w / cols.toFloat()
        val iconSize = min(cellW * 0.42f, dp(56f).toFloat())
        // 网格图标（0..7）
        for (i in 0 until dockStart) {
            val row = i / cols; val col = i % cols
            icons[i].cx = cellW * col + cellW / 2f
            icons[i].cy = h * 0.12f + row * h * 0.22f + iconSize / 2f
            icons[i].size = iconSize
        }
        // dock 图标（8..10）
        val dockCols = 3
        val dockCellW = w / (dockCols + 1).toFloat()
        for (i in dockStart until icons.size) {
            val col = i - dockStart
            icons[i].cx = dockCellW * (col + 0.5f) + dockCellW / 2f
            icons[i].cy = h - dp(56f).toFloat()
            icons[i].size = iconSize
        }
        fullRect.set(dp(6f).toFloat(), dp(6f).toFloat(),
            w - dp(6f).toFloat(), h - dp(6f).toFloat())
        // recents 卡片位
        val cw = w * 0.62f; val ch = h * 0.55f
        cardRect.set((w - cw) / 2f, (h - ch) / 2f - h * 0.04f, (w + cw) / 2f, (h + ch) / 2f - h * 0.04f)
    }

    // ── 场景原语 ─────────────────────────────────────────

    /** 打开应用：图标淡出 + 壁纸放大 + 窗口弹簧到全屏。 */
    fun openApp(iconIndex: Int = 0) {
        windowSrcIcon = iconIndex.coerceIn(0, icons.size - 1)
        windowTitle = icons[windowSrcIcon].spec.label
        icons[windowSrcIcon].alpha = 0f
        wallpaperZoom.animateTo(1.06f)
        if (!externalDrive) windowP.animateTo(1f)
        ensureClock()
    }

    /** 关闭应用：窗口弹簧缩回图标位。 */
    fun closeApp() {
        wallpaperZoom.animateTo(1f)
        recentsP.animateTo(0f)
        if (!externalDrive) windowP.animateTo(0f)
        ensureClock()
    }

    val isAppOpen get() = windowP.target >= 1f || windowP.value > 0.5f
    val windowProgress get() = windowP.value

    /** 外部驱动模式（Demo 10：进度由别的线程逐帧写入）。 */
    fun setExternalDrive(on: Boolean) {
        externalDrive = on
    }

    /** 外部逐帧写入窗口进度（可在非 UI 线程调用，内部 postInvalidate）。 */
    fun driveWindowProgress(p: Float) {
        windowP.setValue(p)
        wallpaperZoom.setValue(1f + 0.06f * p)
        postInvalidate()
    }

    /** 图标按压回弹。sync=true 完全同步；false 各自参数（逐渐错开）。 */
    fun bounceIcons(sync: Boolean) {
        for (i in 0..3) {
            val ic = icons[i]
            if (!sync) {
                ic.scale.stiffness = 260f + i * 90f
                ic.scale.dampingRatio = 0.45f + i * 0.07f
            } else {
                ic.scale.stiffness = 420f
                ic.scale.dampingRatio = 0.55f
            }
            ic.scale.setValue(0.72f)
            ic.scale.animateTo(1f)
        }
        ensureClock()
    }

    /** 图标飞行（直线/过冲/减速，带拖尾）。 */
    fun flyIcon(fromIdx: Int, toIdx: Int, curve: FlyCurve, color: Int) {
        icons[toIdx].hidden = true
        flyAnims.add(FlyAnim(fromIdx, toIdx, curve, color))
        ensureClock()
    }

    /**
     * 虚拟手指上滑进 recents。
     * @param stopAt 在这个进度停住（断点）；1f = 不停
     * @param holdMs 断点停留时长；之后自动续行到 1f
     */
    fun swipeToRecents(stopAt: Float = 1f, holdMs: Long = 600) {
        if (!isAppOpen && windowP.value < 0.5f) openApp(windowSrcIcon)
        var phase = 0
        var holdLeft = holdMs / 1000f
        gestureActive = true
        gestureX = width / 2f
        gestureAnim = { dt ->
            when (phase) {
                0 -> { // 滑到 stopAt
                    val v = recentsP.value + dt * 1.6f
                    if (v >= stopAt) {
                        recentsP.setValue(stopAt)
                        if (stopAt >= 1f) { phase = 2 } else { phase = 1; holdLeft = holdMs / 1000f }
                    } else recentsP.setValue(v)
                    gestureY = height * (0.9f - 0.6f * recentsP.value)
                    false
                }
                1 -> { // 断点停留
                    holdLeft -= dt
                    if (holdLeft <= 0f) phase = 2
                    false
                }
                else -> { // 续行到 1
                    val v = recentsP.value + dt * 1.2f
                    if (v >= 1f) {
                        recentsP.setValue(1f)
                        gestureActive = false
                        true
                    } else {
                        recentsP.setValue(v)
                        gestureY = height * (0.9f - 0.6f * recentsP.value)
                        false
                    }
                }
            }
        }
        ensureClock()
    }

    /** recents 收回桌面。 */
    fun exitRecents() {
        recentsP.animateTo(0f)
        closeApp()
        ensureClock()
    }

    val recentsProgress get() = recentsP.value

    fun resetScene() {
        windowP.snapTo(0f); wallpaperZoom.snapTo(1f); recentsP.snapTo(0f)
        icons.forEach { it.alpha = 1f; it.hidden = false; it.scale.snapTo(1f) }
        flyAnims.clear()
        gestureActive = false; gestureAnim = null
        invalidate()
    }

    // ── 帧循环 ───────────────────────────────────────────
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        clock.start()
    }

    override fun onDetachedFromWindow() {
        clock.stop()
        super.onDetachedFromWindow()
    }

    private fun ensureClock() {
        if (isAttachedToWindow) clock.start()
    }

    private fun tick(dt: Float) {
        var busy = false

        if (!externalDrive) {
            if (!windowP.isAtRest) { windowP.advance(dt); busy = true }
            if (!wallpaperZoom.isAtRest) { wallpaperZoom.advance(dt); busy = true }
            if (windowP.value <= 0.005f && windowP.target == 0f) {
                // 窗口关完，恢复图标
                if (icons[windowSrcIcon].alpha < 1f) { icons[windowSrcIcon].alpha = 1f; busy = true }
            }
        } else busy = true

        if (!recentsP.isAtRest) { recentsP.advance(dt); busy = true }

        icons.forEach {
            if (!it.scale.isAtRest) { it.scale.advance(dt); busy = true }
        }

        // 手势动画
        gestureAnim?.let { ga ->
            val finished = ga(dt)
            if (finished) gestureAnim = null
            busy = true
        }

        // 飞行动画
        val it = flyAnims.iterator()
        while (it.hasNext()) {
            val f = it.next()
            f.t += dt / 0.9f
            if (f.t >= 1f) {
                f.t = 1f; f.done = true
                icons[f.toIdx].hidden = false
                it.remove()
            }
            busy = true
        }

        onFrame?.invoke(this)
        invalidate()
        if (!busy && !externalDrive) clock.stop()
    }

    // ── 绘制 ─────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()

        // 壁纸
        SceneSprites.drawWallpaper(canvas, w, h, wallpaperZoom.value, paint)

        // 图标
        icons.forEachIndexed { i, ic ->
            if (ic.hidden) return@forEachIndexed
            SceneSprites.drawIcon(canvas, ic.spec, ic.cx, ic.cy, ic.size,
                ic.scale.value, ic.alpha, paint, glyphPaint)
            // 名称
            textPaint.reset(); textPaint.isAntiAlias = true
            textPaint.color = 0xE6FFFFFF.toInt()
            textPaint.textSize = sp(10f)
            textPaint.textAlign = Paint.Align.CENTER
            if (ic.alpha > 0.5f) canvas.drawText(ic.spec.label, ic.cx, ic.cy + ic.size / 2f + sp(13f), textPaint)
        }

        // 飞行动画（拖尾 + 飞行体）
        flyAnims.forEach { f ->
            val from = icons[f.fromIdx]; val to = icons[f.toIdx]
            val x = from.cx + (to.cx - from.cx) * curveValue(f.curve, f.t)
            val y = from.cy + (to.cy - from.cy) * f.t  // y 恒线性，曲线只整形 x（视觉差异清晰）
            f.trail.add(floatArrayOf(x, y))
            if (f.trail.size > 14) f.trail.removeAt(0)
            f.trail.forEachIndexed { ti, p ->
                trailPaint.color = f.color
                trailPaint.alpha = (140 * ti / f.trail.size)
                canvas.drawCircle(p[0], p[1], from.size * 0.1f, trailPaint)
            }
            SceneSprites.drawIcon(canvas, from.spec, x, y, from.size, 1f, 1f, paint, glyphPaint)
        }

        // 窗口 leash（图标位 ↔ 全屏 ↔ recents 卡片位）
        val p = windowP.value
        if (p > 0.005f) {
            val src = icons[windowSrcIcon]
            iconRect.set(src.cx - src.size / 2f, src.cy - src.size / 2f,
                src.cx + src.size / 2f, src.cy + src.size / 2f)
            // 先按 windowP 插值 icon→全屏，再按 recentsP 插值 全屏→卡片位
            lerp(iconRect, fullRect, easeOut(p), windowRect)
            if (recentsP.value > 0.005f) {
                lerp(windowRect, cardRect, recentsP.value, windowRect)
            }
            val corner = lerpF(dp(24f).toFloat(), 0f, easeOut(p)) * (1f - recentsP.value) +
                    dp(20f).toFloat() * recentsP.value
            SceneSprites.drawWindowLeash(canvas, windowRect, corner,
                min(1f, p * 2.5f), windowTitle,
                icons[windowSrcIcon].spec.colorStart, paint, textPaint)
        }

        // 虚拟手指
        if (gestureActive) {
            SceneSprites.drawFinger(canvas, gestureX, gestureY, paint)
        }

        // 横幅（状态徽章）
        banner?.let { b ->
            textPaint.reset(); textPaint.isAntiAlias = true
            textPaint.color = 0xFFFFFFFF.toInt()
            textPaint.textSize = sp(13f)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.isFakeBoldText = true
            val bw = textPaint.measureText(b) + dp(28f)
            val bx = w / 2f - bw / 2f
            paint.reset(); paint.isAntiAlias = true
            paint.color = 0xB31F2430.toInt()
            canvas.drawRoundRect(RectF(bx, dp(10f).toFloat(), bx + bw, dp(40f).toFloat()),
                dp(15f).toFloat(), dp(15f).toFloat(), paint)
            canvas.drawText(b, w / 2f, dp(29f).toFloat() + sp(4f), textPaint)
        }
    }

    private fun curveValue(c: FlyCurve, t: Float): Float = when (c) {
        FlyCurve.LINEAR -> t
        FlyCurve.OVERSHOOT -> {
            val s = 1.70158f
            val u = t - 1f
            1f + (s + 1f) * u * u * u + s * u * u
        }
        FlyCurve.DECEL -> 1f - (1f - t) * (1f - t)
    }

    private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

    private fun lerp(a: RectF, b: RectF, t: Float, out: RectF) {
        out.set(a.left + (b.left - a.left) * t,
            a.top + (b.top - a.top) * t,
            a.right + (b.right - a.right) * t,
            a.bottom + (b.bottom - a.bottom) * t)
    }

    private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ── 触摸 ─────────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = ev.x; val y = ev.y
            if (windowP.value > 0.5f && recentsP.value < 0.5f) {
                onWindowTapped?.invoke()
                return true
            }
            icons.forEachIndexed { i, ic ->
                if (!ic.hidden && Math.abs(x - ic.cx) < ic.size * 0.7f &&
                    Math.abs(y - ic.cy) < ic.size * 0.7f) {
                    onIconTapped?.invoke(i)
                    return true
                }
            }
        }
        return true
    }

    private fun dp(d: Float): Int = DemoStyle.dp(this, d)
    private fun sp(s: Float): Float =
        android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, s, resources.displayMetrics)
}
