package com.autobot.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** Fit the original photograph, then pinch, drag or double tap to inspect diagrams. */
class ZoomImageView(context: Context, private val bitmap: Bitmap) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var scale = 1f
    private var dx = 0f
    private var dy = 0f
    private fun fit() = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
    private fun bounds() {
        val limitX = maxOf(0f, (bitmap.width * fit() * scale - width) / 2)
        val limitY = maxOf(0f, (bitmap.height * fit() * scale - height) / 2)
        dx = dx.coerceIn(-limitX, limitX); dy = dy.coerceIn(-limitY, limitY)
    }
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old = scale
            scale = (scale * detector.scaleFactor).coerceIn(1f, 8f)
            val ratio = scale / old
            dx = (dx - (detector.focusX - width / 2f)) * ratio + detector.focusX - width / 2f
            dy = (dy - (detector.focusY - height / 2f)) * ratio + detector.focusY - height / 2f
            bounds(); invalidate(); return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!pinch.isInProgress) { dx -= distanceX; dy -= distanceY; bounds(); invalidate() }
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            scale = if (scale > 1f) 1f else 3f; dx = 0f; dy = 0f; bounds(); invalidate(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean { performClick(); return true }
    })
    init { setBackgroundColor(Color.rgb(20, 27, 31)); contentDescription = "原始题图，可双指缩放、拖动，双击放大或复位" }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        pinch.onTouchEvent(event); gestures.onTouchEvent(event); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = fit() * scale
        canvas.save()
        canvas.translate(width / 2f + dx, height / 2f + dy)
        canvas.scale(s, s)
        canvas.drawBitmap(bitmap, -bitmap.width / 2f, -bitmap.height / 2f, paint)
        canvas.restore()
    }
}
