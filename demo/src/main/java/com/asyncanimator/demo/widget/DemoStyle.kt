package com.asyncanimator.demo.widget

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * DemoStyle — demo 统一配色 / 尺寸 / 样式工厂。
 *
 * <p>配色语义与 trace 分析文档一致：
 * 主线程 = 蓝，独立动画线程(launcher.anim) = 绿，主色 = 靛蓝。
 */
object DemoStyle {

    // ── 配色 ──────────────────────────────────────────────
    const val PRIMARY = 0xFF5B6CFF.toInt()        // 主色 靛蓝
    const val PRIMARY_DARK = 0xFF3D4BD1.toInt()
    const val ACCENT = 0xFF00C2A8.toInt()         // 强调 青
    const val WARN = 0xFFFF5C5C.toInt()           // 警示 红
    const val MAIN_THREAD = 0xFF3F7FE0.toInt()    // 主线程 蓝
    const val ANIM_THREAD = 0xFF3EA65C.toInt()    // 动画线程 绿
    const val AMBER = 0xFFFFB020.toInt()          // 中间态 黄
    const val INK = 0xFF1F2430.toInt()            // 正文
    const val GRAY = 0xFF8A90A0.toInt()           // 次要
    const val BG_PAGE = 0xFFF4F5FA.toInt()        // 页面底
    const val BG_CARD = 0xFFFFFFFF.toInt()        // 卡片底
    const val BG_TRACK = 0xFFE6E8F2.toInt()       // 轨道底
    const val BG_LOG = 0xFF161A24.toInt()         // 日志底（深色）
    const val LOG_TEXT = 0xFF9FE8C0.toInt()       // 日志字（浅绿）

    fun dp(v: View, d: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, d, v.resources.displayMetrics).toInt()

    fun dp(ctx: Context, d: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, d, ctx.resources.displayMetrics).toInt()

    /** 圆角实底。 */
    fun roundRect(color: Int, radiusDp: Float, ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, radiusDp, ctx.resources.displayMetrics)
        }

    /** 圆角描边。 */
    fun strokeRect(color: Int, radiusDp: Float, widthDp: Float, ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, radiusDp, ctx.resources.displayMetrics)
            setStroke(dp(ctx, widthDp), color)
        }

    /** 胶囊 tag（章节标记等）。 */
    fun pill(text: String, ctx: Context, bg: Int = 0x1A5B6CFF, fg: Int = PRIMARY): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 10f
            setTextColor(fg)
            background = roundRect(bg, 100f, ctx)
            setPadding(dp(ctx, 8f), dp(ctx, 2f), dp(ctx, 8f), dp(ctx, 2f))
        }

    /** 主按钮（填充主色）。 */
    fun primaryButton(text: String, ctx: Context, onClick: () -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = roundRect(PRIMARY, 24f, ctx)
            setOnClickListener { onClick() }
        }

    /** 次按钮（描边）。 */
    fun outlineButton(text: String, ctx: Context, color: Int = PRIMARY, onClick: () -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            textSize = 14f
            setTextColor(color)
            isAllCaps = false
            background = strokeRect(color, 24f, 1.5f, ctx)
            setOnClickListener { onClick() }
        }

    /** 警示按钮（填充红）。 */
    fun dangerButton(text: String, ctx: Context, onClick: () -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = roundRect(WARN, 24f, ctx)
            setOnClickListener { onClick() }
        }

    /** 按钮横向等分加入容器。 */
    fun addButtonRow(container: android.widget.LinearLayout, vararg buttons: Button) {
        val row = android.widget.LinearLayout(container.context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        buttons.forEach { b ->
            val lp = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                marginStart = dp(container, 4f); marginEnd = dp(container, 4f)
                topMargin = dp(container, 6f)
            }
            row.addView(b, lp)
        }
        container.addView(row)
    }
}
