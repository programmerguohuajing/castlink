package com.castlink.receiver.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.castlink.receiver.R

/**
 * v0.1.14: PIN 显示改用 Canvas 直接绘制，不走 TextView。
 * Hisense 等电视的 TextView 大字号渲染失败（v0.1.12/v0.1.13 均验证无效），
 * Canvas.drawText 是最底层的文本绘制，绕开所有 TextView 的字体/排版问题。
 */
class PinCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var pin: String = "----"
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()  // nebula_cyan
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    init {
        // v0.1.17: 使用 APK 内置字体，不依赖盒子系统字体（E900V22C 系统字体渲染失败）
        // minSdk 26，Resources.getFont() 可用，无需 AndroidX
        runCatching {
            val tf = context.resources.getFont(R.font.pin_font)
            paint.typeface = tf
        }
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0A1226.toInt()  // 深蓝底
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 背景圆角矩形
        val radius = 24f
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, bgPaint)
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, strokePaint)

        // PIN 文字：字号按高度自适应，留边距
        paint.textSize = h * 0.55f
        // 字母间距：手动在字符间加空格
        val spaced = pin.toCharArray().joinToString("\u2009\u2009")
        val x = w / 2f
        val y = h / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(spaced, x, y, paint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 默认尺寸：宽 480dp，高 160dp（由布局指定则用布局值）
        val desiredW = (480 * resources.displayMetrics.density).toInt()
        val desiredH = (160 * resources.displayMetrics.density).toInt()
        val w = resolveSize(desiredW, widthMeasureSpec)
        val h = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }
}
