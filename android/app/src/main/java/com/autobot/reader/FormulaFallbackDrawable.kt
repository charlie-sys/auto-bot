package com.autobot.reader

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/** Unsupported formula commands stay readable instead of silently disappearing. */
class FormulaFallbackDrawable(latex: String, size: Float, color: Int) : Drawable() {
    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color }
    private val text = "公式：" + latex.take(800)
    private val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, (size * 24).toInt())
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
        .setMaxLines(12).setEllipsize(TextUtils.TruncateAt.END).build()
    init { setBounds(0, 0, layout.width, layout.height) }
    override fun getIntrinsicWidth() = layout.width
    override fun getIntrinsicHeight() = layout.height
    override fun draw(canvas: Canvas) { canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); layout.draw(canvas); canvas.restore() }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
