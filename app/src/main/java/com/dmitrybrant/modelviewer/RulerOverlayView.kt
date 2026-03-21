package com.dmitrybrant.modelviewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay that draws ruler line and endpoint dots.
 * Does NOT appear in screenshots (separate layer).
 */
class RulerOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF00")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(15f, 8f), 0f)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4444")
        style = Paint.Style.FILL
    }

    private val dotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    var pointAScreen: Pair<Float, Float>? = null
    var pointBScreen: Pair<Float, Float>? = null

    fun reset() {
        pointAScreen = null; pointBScreen = null; invalidate()
    }

    fun setPointA(x: Float, y: Float) {
        pointAScreen = Pair(x, y); invalidate()
    }

    fun setPointB(x: Float, y: Float) {
        pointBScreen = Pair(x, y); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val a = pointAScreen ?: return

        // Draw point A dot
        canvas.drawCircle(a.first, a.second, 12f, dotPaint)
        canvas.drawCircle(a.first, a.second, 12f, dotBorderPaint)

        val b = pointBScreen ?: return

        // Draw line A→B
        canvas.drawLine(a.first, a.second, b.first, b.second, linePaint)

        // Draw point B dot
        canvas.drawCircle(b.first, b.second, 12f, dotPaint)
        canvas.drawCircle(b.first, b.second, 12f, dotBorderPaint)
    }
}
