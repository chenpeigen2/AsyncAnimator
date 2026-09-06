package com.asyncanimator.demo.scene

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * SceneSprites — 舞台元素绘制（纯 Canvas，无图片资源）。
 */
object SceneSprites {

    enum class Glyph { CAMERA, PHOTOS, SETTINGS, BROWSER, MESSAGES, CLOCK, MUSIC, FILES }

    data class IconSpec(
        val glyph: Glyph,
        val label: String,
        val colorStart: Int,
        val colorEnd: Int
    )

    /** 默认 11 个图标（8 网格 + 3 dock）。 */
    val DEFAULT_ICONS = listOf(
        IconSpec(Glyph.CAMERA, "相机", 0xFF5B6CFF.toInt(), 0xFF8E9BFF.toInt()),
        IconSpec(Glyph.PHOTOS, "图库", 0xFF00C2A8.toInt(), 0xFF4ADE80.toInt()),
        IconSpec(Glyph.SETTINGS, "设置", 0xFF8A90A0.toInt(), 0xFFB8BECC.toInt()),
        IconSpec(Glyph.BROWSER, "浏览器", 0xFF3F7FE0.toInt(), 0xFF60A5FA.toInt()),
        IconSpec(Glyph.MESSAGES, "信息", 0xFFFFB020.toInt(), 0xFFFCD34D.toInt()),
        IconSpec(Glyph.CLOCK, "时钟", 0xFF1F2430.toInt(), 0xFF4B5265.toInt()),
        IconSpec(Glyph.MUSIC, "音乐", 0xFFFF5C5C.toInt(), 0xFFFF8A80.toInt()),
        IconSpec(Glyph.FILES, "文件", 0xFF7C6CFF.toInt(), 0xFFA78BFA.toInt()),
        // dock
        IconSpec(Glyph.BROWSER, "浏览器", 0xFF3F7FE0.toInt(), 0xFF60A5FA.toInt()),
        IconSpec(Glyph.CAMERA, "相机", 0xFF5B6CFF.toInt(), 0xFF8E9BFF.toInt()),
        IconSpec(Glyph.MESSAGES, "信息", 0xFF00C2A8.toInt(), 0xFF4ADE80.toInt())
    )

    private val rect = RectF()
    private val path = Path()

    /** 深靛蓝夜空壁纸 + 光斑。 */
    fun drawWallpaper(canvas: Canvas, w: Float, h: Float, zoom: Float, paint: Paint) {
        paint.reset()
        paint.isAntiAlias = true
        val cx = w / 2f; val cy = h / 2f
        canvas.save()
        canvas.scale(zoom, zoom, cx, cy)
        paint.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(0xFF1A1F3A.toInt(), 0xFF2A2F5C.toInt(), 0xFF3B3670.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        // 光斑
        drawBlob(canvas, w * 0.8f, h * 0.22f, w * 0.45f, 0x335B6CFF, paint)
        drawBlob(canvas, w * 0.15f, h * 0.7f, w * 0.5f, 0x2A00C2A8, paint)
        drawBlob(canvas, w * 0.6f, h * 0.9f, w * 0.35f, 0x22A78BFA, paint)
        canvas.restore()
    }

    private fun drawBlob(canvas: Canvas, x: Float, y: Float, r: Float, color: Int, paint: Paint) {
        paint.shader = RadialGradient(x, y, r, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, r, paint)
        paint.shader = null
    }

    /** 应用图标：圆角渐变底 + 白色 glyph + 阴影。 */
    fun drawIcon(
        canvas: Canvas, spec: IconSpec, cx: Float, cy: Float, size: Float,
        scale: Float, alpha: Float, paint: Paint, glyphPaint: Paint
    ) {
        if (alpha <= 0.01f || scale <= 0.01f) return
        val s = size * scale
        paint.reset(); paint.isAntiAlias = true
        glyphPaint.reset(); glyphPaint.isAntiAlias = true

        // 阴影
        paint.color = 0xFF000000.toInt()
        paint.alpha = (40 * alpha).toInt()
        rect.set(cx - s / 2, cy - s / 2 + s * 0.06f, cx + s / 2, cy + s / 2 + s * 0.10f)
        canvas.drawRoundRect(rect, s * 0.24f, s * 0.24f, paint)

        // 渐变底
        paint.alpha = (255 * alpha).toInt()
        paint.shader = LinearGradient(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2,
            spec.colorStart, spec.colorEnd, Shader.TileMode.CLAMP)
        rect.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        canvas.drawRoundRect(rect, s * 0.24f, s * 0.24f, paint)
        paint.shader = null

        // glyph
        glyphPaint.color = Color.WHITE
        glyphPaint.alpha = (235 * alpha).toInt()
        glyphPaint.strokeWidth = s * 0.055f
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeCap = Paint.Cap.ROUND
        drawGlyph(canvas, spec.glyph, cx, cy, s * 0.52f, glyphPaint)
    }

    private fun drawGlyph(canvas: Canvas, g: Glyph, cx: Float, cy: Float, r: Float, p: Paint) {
        when (g) {
            Glyph.CAMERA -> {
                rect.set(cx - r, cy - r * 0.7f, cx + r, cy + r * 0.7f)
                canvas.drawRoundRect(rect, r * 0.25f, r * 0.25f, p)
                canvas.drawCircle(cx, cy, r * 0.38f, p)
                path.reset()
                path.moveTo(cx - r * 0.4f, cy - r * 0.7f)
                path.lineTo(cx - r * 0.25f, cy - r)
                path.lineTo(cx + r * 0.25f, cy - r)
                path.lineTo(cx + r * 0.4f, cy - r * 0.7f)
                canvas.drawPath(path, p)
            }
            Glyph.PHOTOS -> {
                rect.set(cx - r, cy - r, cx + r, cy + r)
                canvas.drawRoundRect(rect, r * 0.2f, r * 0.2f, p)
                canvas.drawCircle(cx - r * 0.4f, cy - r * 0.35f, r * 0.14f, p)
                path.reset()
                path.moveTo(cx - r, cy + r * 0.6f)
                path.lineTo(cx - r * 0.25f, cy - r * 0.05f)
                path.lineTo(cx + r * 0.3f, cy + r * 0.45f)
                path.lineTo(cx + r, cy + r * 0.05f)
                canvas.drawPath(path, p)
            }
            Glyph.SETTINGS -> {
                canvas.drawCircle(cx, cy, r * 0.42f, p)
                for (i in 0..7) {
                    val a = Math.toRadians((i * 45).toDouble())
                    val x1 = cx + (r * 0.68f * kotlin.math.cos(a)).toFloat()
                    val y1 = cy + (r * 0.68f * kotlin.math.sin(a)).toFloat()
                    val x2 = cx + (r * 0.95f * kotlin.math.cos(a)).toFloat()
                    val y2 = cy + (r * 0.95f * kotlin.math.sin(a)).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, p)
                }
            }
            Glyph.BROWSER -> {
                canvas.drawCircle(cx, cy, r * 0.95f, p)
                rect.set(cx - r * 0.95f, cy - r * 0.35f, cx + r * 0.95f, cy + r * 0.35f)
                canvas.drawOval(rect, p)
                canvas.drawLine(cx, cy - r * 0.95f, cx, cy + r * 0.95f, p)
            }
            Glyph.MESSAGES -> {
                rect.set(cx - r, cy - r * 0.75f, cx + r, cy + r * 0.5f)
                canvas.drawRoundRect(rect, r * 0.3f, r * 0.3f, p)
                path.reset()
                path.moveTo(cx - r * 0.3f, cy + r * 0.5f)
                path.lineTo(cx - r * 0.3f, cy + r * 0.95f)
                path.lineTo(cx + r * 0.2f, cy + r * 0.5f)
                canvas.drawPath(path, p)
            }
            Glyph.CLOCK -> {
                canvas.drawCircle(cx, cy, r * 0.9f, p)
                canvas.drawLine(cx, cy, cx, cy - r * 0.55f, p)
                canvas.drawLine(cx, cy, cx + r * 0.4f, cy + r * 0.15f, p)
            }
            Glyph.MUSIC -> {
                canvas.drawLine(cx + r * 0.35f, cy - r * 0.8f, cx + r * 0.35f, cy + r * 0.45f, p)
                canvas.drawLine(cx + r * 0.35f, cy - r * 0.8f, cx - r * 0.45f, cy - r * 0.55f, p)
                canvas.drawLine(cx - r * 0.45f, cy - r * 0.55f, cx - r * 0.45f, cy + r * 0.65f, p)
                canvas.drawCircle(cx - r * 0.6f, cy + r * 0.6f, r * 0.22f, p)
                canvas.drawCircle(cx + r * 0.2f, cy + r * 0.4f, r * 0.22f, p)
            }
            Glyph.FILES -> {
                rect.set(cx - r * 0.85f, cy - r, cx + r * 0.85f, cy + r)
                canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, p)
                canvas.drawLine(cx - r * 0.5f, cy - r * 0.4f, cx + r * 0.5f, cy - r * 0.4f, p)
                canvas.drawLine(cx - r * 0.5f, cy, cx + r * 0.5f, cy, p)
                canvas.drawLine(cx - r * 0.5f, cy + r * 0.4f, cx + r * 0.2f, cy + r * 0.4f, p)
            }
        }
    }

    /** 窗口 leash：圆角矩形 + 假 app 内容（标题栏 + 列表占位）。 */
    fun drawWindowLeash(
        canvas: Canvas, bounds: RectF, cornerRadius: Float, alpha: Float,
        title: String, accentColor: Int, paint: Paint, textPaint: Paint
    ) {
        if (alpha <= 0.01f || bounds.width() < 4f) return
        paint.reset(); paint.isAntiAlias = true

        // 阴影
        paint.color = 0xFF000000.toInt()
        paint.alpha = (60 * alpha).toInt()
        rect.set(bounds); rect.offset(0f, bounds.height() * 0.015f)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

        // 白底
        paint.color = Color.WHITE
        paint.alpha = (255 * alpha).toInt()
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, paint)

        // 内容（裁剪到窗口内）
        canvas.save()
        canvas.clipRect(bounds)
        // 标题栏
        paint.color = accentColor
        paint.alpha = (255 * alpha).toInt()
        canvas.drawRect(bounds.left, bounds.top, bounds.right,
            bounds.top + bounds.height() * 0.09f, paint)
        textPaint.color = Color.WHITE
        textPaint.alpha = (255 * alpha).toInt()
        textPaint.textSize = bounds.height() * 0.032f
        textPaint.isFakeBoldText = true
        canvas.drawText(title, bounds.left + bounds.width() * 0.05f,
            bounds.top + bounds.height() * 0.062f, textPaint)
        // 列表占位条
        textPaint.isFakeBoldText = false
        val rowH = bounds.height() * 0.075f
        var y = bounds.top + bounds.height() * 0.14f
        var i = 0
        while (y + rowH < bounds.bottom - bounds.height() * 0.03f && i < 12) {
            paint.color = 0xFFE9EBF3.toInt()
            paint.alpha = (255 * alpha).toInt()
            val wFrac = if (i % 3 == 0) 0.9f else if (i % 3 == 1) 0.75f else 0.6f
            rect.set(bounds.left + bounds.width() * 0.05f, y,
                bounds.left + bounds.width() * 0.05f + bounds.width() * 0.9f * wFrac, y + rowH * 0.45f)
            canvas.drawRoundRect(rect, rowH * 0.2f, rowH * 0.2f, paint)
            y += rowH
            i++
        }
        canvas.restore()
    }

    /** 虚拟手指（手势轨迹点）。 */
    fun drawFinger(canvas: Canvas, x: Float, y: Float, paint: Paint) {
        paint.reset(); paint.isAntiAlias = true
        paint.color = Color.WHITE
        paint.alpha = 90
        canvas.drawCircle(x, y, 26f, paint)
        paint.alpha = 220
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawCircle(x, y, 26f, paint)
        paint.style = Paint.Style.FILL
    }
}
