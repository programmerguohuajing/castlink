package com.castlink.receiver.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.castlink.receiver.R

/**
 * v0.1.19: PIN 显示改用 7 段数码管绘制，只画直线/矩形，不用任何字体。
 * 创维 E900V22C 盒子的 Canvas.drawText() 完全失效（即使 APK 内置字体），
 * 7 段显示只用 drawRect，是最底层的几何绘制，一定能显示。
 */
class SevenSegmentPinView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var pin: String = "----"
        set(value) {
            field = value.filter { it.isDigit() }.take(4).padEnd(4, '-')
            invalidate()
        }

    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()  // nebula_cyan
        style = Paint.Style.FILL
    }
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A2A4A.toInt()  // 暗色（熄灭的段）
        style = Paint.Style.FILL
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0A1226.toInt()
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    // 7 段编码：a,b,c,d,e,f,g (顺序：上，右上，右下，下，左下，左上，中)
    private val digitSegments = mapOf(
        '0' to booleanArrayOf(true, true, true, true, true, true, false),
        '1' to booleanArrayOf(false, true, true, false, false, false, false),
        '2' to booleanArrayOf(true, true, false, true, true, false, true),
        '3' to booleanArrayOf(true, true, true, true, false, false, true),
        '4' to booleanArrayOf(false, true, true, false, false, true, true),
        '5' to booleanArrayOf(true, false, true, true, false, true, true),
        '6' to booleanArrayOf(true, false, true, true, true, true, true),
        '7' to booleanArrayOf(true, true, true, false, false, false, false),
        '8' to booleanArrayOf(true, true, true, true, true, true, true),
        '9' to booleanArrayOf(true, true, true, true, false, true, true),
        '-' to booleanArrayOf(false, false, false, false, false, false, true)
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 背景
        val radius = 24f
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, bgPaint)
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, strokePaint)

        // 4 个数字，每个占 1/4 宽度
        val digitW = w / 4f
        val padding = digitW * 0.15f
        for (i in 0 until 4) {
            val ch = pin.getOrNull(i) ?: '-'
            val left = i * digitW + padding
            val right = (i + 1) * digitW - padding
            val top = h * 0.12f
            val bottom = h * 0.88f
            drawDigit(canvas, ch, left, top, right, bottom)
        }
    }

    private fun drawDigit(canvas: Canvas, ch: Char, left: Float, top: Float, right: Float, bottom: Float) {
        val segs = digitSegments[ch] ?: digitSegments['-']!!
        val thickness = (right - left) * 0.18f
        val midY = (top + bottom) / 2f

        // a: 上横
        drawHSegment(canvas, segs[0], left, top, right, top, thickness)
        // b: 右上竖
        drawVSegment(canvas, segs[1], right - thickness, top, right, midY, thickness)
        // c: 右下竖
        drawVSegment(canvas, segs[2], right - thickness, midY, right, bottom, thickness)
        // d: 下横
        drawHSegment(canvas, segs[3], left, bottom - thickness, right, bottom, thickness)
        // e: 左下竖
        drawVSegment(canvas, segs[4], left, midY, left + thickness, bottom, thickness)
        // f: 左上竖
        drawVSegment(canvas, segs[5], left, top, left + thickness, midY, thickness)
        // g: 中横
        drawHSegment(canvas, segs[6], left, midY - thickness / 2, right, midY + thickness / 2, thickness)
    }

    private fun drawHSegment(canvas: Canvas, on: Boolean, left: Float, top: Float, right: Float, bottom: Float, thickness: Float) {
        val paint = if (on) onPaint else offPaint
        // 六边形（斜角）更像数码管
        val slant = thickness / 2f
        val path = android.graphics.Path().apply {
            moveTo(left + slant, top)
            lineTo(right - slant, top)
            lineTo(right, (top + bottom) / 2f)
            lineTo(right - slant, bottom)
            lineTo(left + slant, bottom)
            lineTo(left, (top + bottom) / 2f)
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun drawVSegment(canvas: Canvas, on: Boolean, left: Float, top: Float, right: Float, bottom: Float, thickness: Float) {
        val paint = if (on) onPaint else offPaint
        val slant = thickness / 2f
        val path = android.graphics.Path().apply {
            moveTo((left + right) / 2f, top)
            lineTo(right, top + slant)
            lineTo(right, bottom - slant)
            lineTo((left + right) / 2f, bottom)
            lineTo(left, bottom - slant)
            lineTo(left, top + slant)
            close()
        }
        canvas.drawPath(path, paint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredW = (480 * resources.displayMetrics.density).toInt()
        val desiredH = (160 * resources.displayMetrics.density).toInt()
        val w = resolveSize(desiredW, widthMeasureSpec)
        val h = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }
}
