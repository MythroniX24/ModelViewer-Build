package com.dmitrybrant.modelviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.dmitrybrant.modelviewer.util.Util.pxToDp
import kotlin.math.sqrt

class ModelSurfaceView(context: Context, model: Model?) : GLSurfaceView(context) {
    val renderer: ModelRenderer
    private var previousX = 0f; private var previousY = 0f
    private val pinchStartPoint = PointF()
    private var pinchStartDistance = 0.0f
    private var touchMode = TOUCH_NONE
    private var touchInterceptListener: ((MotionEvent) -> Boolean)? = null

    fun setOnTouchInterceptListener(listener: (MotionEvent) -> Boolean) {
        touchInterceptListener = listener
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Let ruler tool intercept first
        if (touchInterceptListener?.invoke(event) == true) return true

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> { previousX = event.x; previousY = event.y }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    if (touchMode != TOUCH_ROTATE) { previousX = event.x; previousY = event.y }
                    touchMode = TOUCH_ROTATE
                    val dx = event.x - previousX; val dy = event.y - previousY
                    previousX = event.x; previousY = event.y
                    renderer.rotate(pxToDp(dy), pxToDp(dx))
                } else if (event.pointerCount == 2) {
                    if (touchMode != TOUCH_ZOOM) {
                        pinchStartDistance = getPinchDistance(event)
                        getPinchCenterPoint(event, pinchStartPoint)
                        previousX = pinchStartPoint.x; previousY = pinchStartPoint.y
                        touchMode = TOUCH_ZOOM
                    } else {
                        val pt = PointF(); getPinchCenterPoint(event, pt)
                        val dx = pt.x - previousX; val dy = pt.y - previousY
                        previousX = pt.x; previousY = pt.y
                        val pinchScale = getPinchDistance(event) / pinchStartDistance
                        pinchStartDistance = getPinchDistance(event)
                        renderer.translate(pxToDp(dx), pxToDp(dy), pinchScale)
                    }
                }
                requestRender()
            }
            MotionEvent.ACTION_UP -> { pinchStartPoint.x = 0f; pinchStartPoint.y = 0f; touchMode = TOUCH_NONE }
        }
        return true
    }

    fun applyModelScale(scaleX: Float, scaleY: Float, scaleZ: Float) {
        queueEvent { renderer.applyModelScale(scaleX, scaleY, scaleZ) }; requestRender()
    }

    fun applyPartScale(partIndex: Int, scaleX: Float, scaleY: Float, scaleZ: Float) {
        queueEvent { renderer.applyPartScale(partIndex, scaleX, scaleY, scaleZ) }; requestRender()
    }

    fun setBackgroundColor(r: Float, g: Float, b: Float) {
        queueEvent { renderer.setBackgroundColor(r, g, b) }; requestRender()
    }

    fun setModelColor(r: Float, g: Float, b: Float) {
        queueEvent { renderer.setModelColor(r, g, b) }; requestRender()
    }

    fun resetView() { queueEvent { renderer.resetView() }; requestRender() }

    fun captureScreenshot(callback: (Bitmap) -> Unit) {
        queueEvent { renderer.requestScreenshot(callback) }; requestRender()
    }

    private fun getPinchDistance(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1); val y = event.getY(0) - event.getY(1)
        return sqrt((x * x + y * y).toDouble()).toFloat()
    }

    private fun getPinchCenterPoint(event: MotionEvent, pt: PointF) {
        pt.x = (event.getX(0) + event.getX(1)) * 0.5f
        pt.y = (event.getY(0) + event.getY(1)) * 0.5f
    }

    companion object {
        private const val TOUCH_NONE = 0; private const val TOUCH_ROTATE = 1; private const val TOUCH_ZOOM = 2
    }

    init {
        setEGLContextClientVersion(2)
        renderer = ModelRenderer(model)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }
}
